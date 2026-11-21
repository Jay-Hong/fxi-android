package com.jay.fxi.data.alerts

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.HttpExchangeEvidence
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.AuthenticatedEndpoint
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.repository.AlertRateLimitResponseEvidence
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.SourceAlertSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8-3 source owner (`R4c/S8/s8_3_contract` §1.2, §2.3): what is the source family's own — only the rows the exchange policy
 * allows reach the snapshot; its Retry-After floor is its own endpoint's, apart from the bank's in both directions; free,
 * closed release, deletion and no identity send nothing; the response evidence S8-2 handed over (late answers after a
 * background, a reset or a use revoked, identity-change and withheld evidence, plain cancellation, 2xx headers) reaches the
 * source floor before any drop and nothing else is adopted; and a chain's send guard refuses once the chain is dropped, even
 * while the same identity is admitted. The GET rules it shares with the bank owner are in [AlertSettingsGetLoopContractTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceAlertSettingsOwnerContractTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val bob = AuthIdentityFence("user-b", 1)
    private val grantA = TopicGrantToken(11)
    private val grantB = TopicGrantToken(12)
    private val stamp = Instant.parse("2026-11-21T01:00:00Z")
    private val source = AuthenticatedEndpoint.SOURCE_NOTIFICATION_SETTINGS
    private val bank = AuthenticatedEndpoint.NOTIFICATION_SETTINGS

    private fun confirmed(id: AuthIdentityFence) = OwnedPremiumAccess(id.uid, id.authGeneration, PremiumAccessState.PremiumConfirmed)
    private fun free(id: AuthIdentityFence) = OwnedPremiumAccess(id.uid, id.authGeneration, PremiumAccessState.FreeConfirmed)
    private fun fenceFor(id: AuthIdentityFence, grant: TopicGrantToken) = TopicSessionFence(id, "epoch-1", grant)
    private fun standing(grant: TopicGrantToken, invalidations: Long = 0L) = TopicAccessSnapshot.INITIAL.copy(
        revision = 1L,
        facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet()),
        userInvalidations = invalidations
    )
    private fun row(id: Int, source: String, asset: String = "usdt-krw", uid: String = "user-a") =
        SourceAlertSetting(id, uid, source, asset, AlertCondition.ABOVE, 1400.0 + id, true, false, null, stamp, stamp, null)

    private sealed interface Outcome {
        class Answer(val result: AuthBoundResult<List<SourceAlertSetting>>) : Outcome
        class Throw(val error: Throwable) : Outcome
    }

    /** [chainJob] is the GET's coroutine job, read before the uncancellable wait for its answer. */
    private class Call(val identity: AuthIdentityFence, val admitted: () -> Boolean, val chainJob: Job) {
        val outcome = CompletableDeferred<Outcome>()
        val open: Boolean get() = !outcome.isCompleted
    }

    private fun ok(vararg rows: SourceAlertSetting, evidence: AlertRateLimitResponseEvidence? = null): Outcome =
        Outcome.Answer(AuthBoundResult({}, Result.success(rows.toList()), evidence))

    private fun limited(status: Int, retryAfter: String): Outcome = Outcome.Answer(
        AuthBoundResult({}, Result.failure(AlertRepositoryException("limited")), AlertRateLimitResponseEvidence(status, retryAfter))
    )

    private inner class Rig(val test: TestScope, premiumAt: OwnedPremiumAccess) {
        val t0: Long = test.testScheduler.currentTime
        var identity: AuthIdentityFence? = alice
        var fence: TopicSessionFence? = fenceFor(alice, grantA)
        var snapshot: TopicAccessSnapshot = standing(grantA)
        var release = true
        val deletions = DeletionAdmissionStore()
        val premium = MutableStateFlow(premiumAt)
        val accessRevisions = MutableStateFlow(0L)
        val access = AlertSettingsAccess(
            { identity }, { fence }, SnapshotTopicUseAuthority { snapshot }, { premium.value }, { release },
            GraphProtectedAdmission({ identity }, deletions)
        )
        val floor = AlertRateLimitFloor({ test.testScheduler.currentTime })
        val calls = mutableListOf<Call>()
        /** Every manual outcome as it is emitted (the flow has no replay). */
        val manualEvents = mutableListOf<ManualRefreshOutcome>()
        private val job = SupervisorJob()
        val owner = SourceAlertSettingsOwner(
            { who, admitted ->
                val call = Call(who, admitted, checkNotNull(currentCoroutineContext()[Job])).also { calls += it }
                // The answer comes back even to a dropped chain, as a transport that ignores cancellation would deliver it.
                when (val outcome = withContext(NonCancellable) { call.outcome.await() }) {
                    is Outcome.Answer -> outcome.result
                    is Outcome.Throw -> throw outcome.error
                }
            },
            access, floor, AlertRetryPolicy(), { test.testScheduler.currentTime }, { 0.0 },
            CoroutineScope(job + StandardTestDispatcher(test.testScheduler)), { delay(it) },
            premium, accessRevisions, 1_000L
        )
        init {
            CoroutineScope(job + UnconfinedTestDispatcher(test.testScheduler)).launch { owner.manualOutcomes.collect { manualEvents += it } }
        }

        val settings: List<SourceAlertSetting>? get() = owner.state.value.settings
        fun settle() = test.runCurrent()
        fun advance(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }
        fun at(offset: Long) {
            val target = t0 + offset
            check(target >= test.testScheduler.currentTime) { "time only runs forward" }
            test.advanceTimeBy(target - test.testScheduler.currentTime)
            test.runCurrent()
        }
        fun foreground(authoritative: Boolean = false) {
            owner.onForeground(authoritative)
            settle()
        }
        fun answer(index: Int, outcome: Outcome) {
            check(calls[index].outcome.complete(outcome)) { "GET $index was already answered" }
            settle()
        }
        /** The floor's deadline as an offset from [t0], or null when there is none. */
        fun floorAt(endpoint: AuthenticatedEndpoint): Long? = floor.notBefore(endpoint).let { if (it == Long.MIN_VALUE) null else it - t0 }
        fun close() = job.cancel()
    }

    private fun rig(test: TestScope, premiumAt: OwnedPremiumAccess = confirmed(alice), body: Rig.() -> Unit) {
        val r = Rig(test, premiumAt)
        try {
            r.body()
        } finally {
            r.close()
        }
    }

    @Test fun `S8_3_O01 only the rows the exchange policy allows reach the snapshot and an undecodable answer keeps it`() = runTest {
        rig(this) {
            foreground()
            val allowed = listOf(row(1, "upbit"), row(4, "gopax"))
            answer(0, ok(row(2, "kb"), allowed[0], row(3, "krx", "usd-krw-futures"), row(5, "bithumb", uid = "user-b"),
                row(6, "coinone"), row(6, "korbit"), allowed[1]))
            assertEquals("the policy's adoption, in order", allowed, settings)
            assertEquals("for the GET's identity", alice, owner.state.value.identity)
            assertEquals("Ready", AlertLoadPhase.Ready, owner.state.value.phase)
            assertEquals("the settings GET asks for the exchange asset", "usdt-krw", SourceAlertRowPolicy.SETTINGS_ASSET)
            at(1_000); owner.authoritative(); settle()
            answer(1, Outcome.Answer(AuthBoundResult({}, Result.failure(
                AlertRepositoryException("undecodable", failureKind = com.jay.fxi.data.repository.AlertRepositoryFailureKind.DECODE)))))
            assertEquals("an undecodable answer keeps the adopted rows", allowed, settings)
            assertEquals("and the identity", alice, owner.state.value.identity)
        }
    }

    @Test fun `S8_3_O02 the source and bank floors are each their own endpoint's`() = runTest {
        rig(this) {
            floor.record(bank, AlertRateLimitEvidence.of(emptyList(), 429, "50"))
            foreground()
            assertEquals("a bank floor does not hold the source GET", 1, calls.size)
        }
        rig(this) {
            foreground()
            answer(0, limited(429, "20"))
            assertEquals("the source answer sets the source floor", 20_000L, floorAt(source))
            assertNull("and no bank floor", floorAt(bank))
            assertEquals("so a bank GET may go at once", 0L, floor.remainingMillis(bank))
        }
    }

    @Test fun `S8_3_O03 a premium loss or another account clears the snapshot and drops the answer in flight`() = runTest {
        rig(this) {
            foreground()
            answer(0, ok(row(1, "upbit")))
            owner.authoritative(); settle()
            assertEquals("premise: a second GET in flight", 2, calls.size)
            premium.value = free(alice)
            settle()
            assertNull("a premium loss clears the snapshot", settings)
            answer(1, ok(row(9, "upbit")))
            assertNull("and the answer then in flight is not applied", settings)
        }
        rig(this) {
            foreground()
            identity = bob; fence = fenceFor(bob, grantB); snapshot = standing(grantB); premium.value = confirmed(bob)
            settle()
            val bobs = calls.indexOfLast { it.identity == bob }
            answer(0, ok(row(5, "upbit")))
            assertTrue("alice's answer is not applied to bob", settings == null)
            answer(bobs, ok(row(7, "upbit", uid = "user-b"), row(8, "upbit")))
            assertEquals("bob's rows only, alice's row in bob's answer dropped", listOf(7), settings?.map { it.id })
            assertEquals("for bob", bob, owner.state.value.identity)
        }
    }

    @Test fun `S8_3_O04 free, a closed release, a deletion or no identity sends no GET and premium regained sends one`() = runTest {
        rig(this, premiumAt = free(alice)) {
            foreground()
            owner.onSync(); owner.ensure(); settle()
            advance(10_000)
            assertEquals("free: no GET, through rechecks", 0, calls.size)
            premium.value = confirmed(alice); settle()
            assertEquals("premium regained in the foreground: one GET", 1, calls.size)
        }
        for (closed in listOf<Rig.() -> Unit>({ release = false }, { deletions.begin(alice, "op") }, { identity = null })) {
            rig(this) {
                closed()
                foreground()
                owner.onSync(); settle()
                advance(10_000)
                assertEquals("no GET while the access is refused", 0, calls.size)
                assertFalse("and nothing shown", settings != null)
            }
        }
    }

    @Test fun `S8_3_O05 a dropped answer's Retry-After reaches the source floor before the drop and nothing else is adopted`() = runTest {
        val drops = listOf<Pair<String, Rig.() -> Unit>>(
            "background" to { owner.onBackground(); settle() },
            "reset" to { owner.reset(); settle() },
            "the use revoked, same UID and generation" to { snapshot = standing(grantA, invalidations = 1); settle() }
        )
        for ((name, drop) in drops) for ((status, header) in listOf(429 to "20", 503 to "20")) for (restart in listOf(true, false)) rig(this) {
            if (!restart && name != "the use revoked, same UID and generation") return@rig
            foreground()
            answer(0, ok(row(1, "upbit")))
            at(1_000); owner.authoritative(); owner.manual(); settle()
            val beforeEvents = manualEvents.toList()
            drop()
            val beforeAnswer = owner.state.value
            assertFalse("$name premise: the dropped GET's guard refuses", calls[1].admitted())
            answer(1, Outcome.Answer(AuthBoundResult({ error("stale fold") }, Result.failure(AlertRepositoryException("limited")),
                AlertRateLimitResponseEvidence(status, header))))
            assertEquals("$name $status: the floor is recorded from 1 s", 21_000L, floorAt(source))
            assertEquals("$name $status: no bank floor", null, floorAt(bank))
            val kept = if (name == "reset") null else listOf(1)
            assertEquals("$name $status: the expected rows survive the drop", kept, beforeAnswer.settings?.map { it.id })
            assertEquals("$name $status: the late answer preserves rows, identity and phase", beforeAnswer, owner.state.value)
            assertEquals("$name $status: no manual outcome from it", beforeEvents, manualEvents)
            assertEquals("$name $status: no retry before a new demand", 2, calls.size)
            if (!restart) {
                advance(70_000)
                assertEquals("$name $status: still no retry a while later", 2, calls.size)
                assertEquals("$name $status: and still no manual outcome", beforeEvents, manualEvents)
                return@rig
            }
            if (name == "background") foreground(authoritative = true) else { owner.authoritative(); settle() }
            at(20_999)
            assertEquals("$name $status: restarted, nothing before the floor", 2, calls.size)
            at(21_000)
            assertEquals("$name $status: one GET at its deadline", 3, calls.size)
        }
        rig(this) {
            foreground()
            var entered = 0
            var floorAtFold: Long? = null
            val bound = AuthBoundResult({
                entered++
                floorAtFold = floorAt(source)
                throw AuthIdentityChangedException(429, "20")
            }, Result.failure<List<SourceAlertSetting>>(AlertRepositoryException("limited")), AlertRateLimitResponseEvidence(429, "20"))
            answer(0, Outcome.Answer(bound))
            assertEquals("a live answer is folded once", 1, entered)
            assertEquals("its Retry-After is in the source floor before the fold's session check", 20_000L, floorAtFold)
            assertNull("an identity change at the fold adopts nothing", settings)
            assertEquals("and leaves only the floor", 20_000L, floorAt(source))
            assertNull("no bank floor", floorAt(bank))
        }
        val first = HttpExchangeEvidence(1, 1, 429, "30")
        val second = HttpExchangeEvidence(1, 2, 503, "4")
        val evidence = listOf(
            Triple("identity change with exchanges", AuthIdentityChangedException(503, "90", listOf(first, second)), 30_000L),
            Triple("identity change with exchanges reversed", AuthIdentityChangedException(503, "90", listOf(second, first)), 30_000L),
            Triple("identity change without exchanges", AuthIdentityChangedException(429, "7"), 7_000L),
            Triple("withheld with exchanges", TopicUseWithheldException(listOf(HttpExchangeEvidence(1, 1, 503, "9"))), 9_000L),
            Triple("withheld without exchanges", TopicUseWithheldException(emptyList()), null),
            Triple("plain cancellation", CancellationException("cancelled"), null)
        )
        for ((name, error, expected) in evidence) rig(this) {
            foreground()
            answer(0, Outcome.Throw(error))
            assertEquals("$name: the source floor", expected, floorAt(source))
            assertNull("$name: nothing adopted", settings)
            assertEquals("$name: a plain cancellation is rethrown, the others end the chain quietly",
                name == "plain cancellation", calls.single().chainJob.isCancelled)
            advance(70_000)
            assertEquals("$name: no retry", 1, calls.size)
        }
        rig(this) {
            foreground()
            answer(0, ok(row(1, "upbit"), evidence = AlertRateLimitResponseEvidence(200, "30")))
            assertNull("a 2xx header sets no floor", floorAt(source))
            assertEquals("and the rows are adopted", listOf(1), settings?.map { it.id })
            at(1_000); owner.authoritative(); settle()
            answer(1, Outcome.Answer(AuthBoundResult({}, Result.failure(AlertRepositoryException("undecodable",
                failureKind = com.jay.fxi.data.repository.AlertRepositoryFailureKind.DECODE)), AlertRateLimitResponseEvidence(200, "30"))))
            assertNull("an undecodable 2xx with a header sets no floor either", floorAt(source))
            assertEquals("and keeps the success", listOf(1), settings?.map { it.id })
        }
    }

    @Test fun `S8_3_O06 a dropped chain's send guard refuses although the same identity is still admitted`() = runTest {
        for (drop in listOf<Pair<String, Rig.() -> Unit>>("background" to { owner.onBackground(); settle() }, "reset" to { owner.reset(); settle() })) {
            rig(this) {
                foreground()
                val old = calls.single()
                assertTrue("${drop.first} premise: the guard admits while its chain runs", old.admitted())
                drop.second(this)
                assertNotNull("premise: the same identity is still admitted", access.acquire())
                assertFalse("${drop.first}: the dropped chain's guard refuses", old.admitted())
                foreground(authoritative = true)
                val next = calls.last()
                assertTrue("${drop.first}: the new chain's guard admits", next !== old && next.admitted())
                assertFalse("${drop.first}: the old guard stays refused", old.admitted())
            }
        }
    }
}
