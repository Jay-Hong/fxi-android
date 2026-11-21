package com.jay.fxi.data.alerts

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.AuthenticatedEndpoint
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.repository.AlertRateLimitResponseEvidence
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.mapAlertHttpFailure
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.SourceAlertSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * S8-3 shared virtual-time contract (`R4c/S8/s8_3_contract` §2.2, design `s8_2_3_design.agreed.r1.md` §2): one set of
 * scenarios — joining, absorption, one trailing fetch, 30 s freshness at 29,999/30,000 ms, background abandonment and late
 * answers, the retry budget, the floor and manual cooldown kept and at their boundary, the empty success, another account's
 * late answer, and access recovery — that the real bank owner and the real source owner each pass. Each family's harness
 * only hands the scenario's inputs to its owner and maps what it shows (GET count, phase, ids or no success, manual state and
 * outcomes); the clock, the order of events, the expected counts and the assertions live here once. The access is the
 * production [AlertSettingsAccess] over the production use authority and deletion store; the floor, the retry policy and the
 * clock are real, on virtual time. The bank owner's own contract tests stay as they are.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class AlertSettingsGetLoopContractTest(private val family: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun families(): List<String> = listOf("bank", "source")

        private const val RECHECK = 1_000L
        private const val STEP = 100L
    }

    private val alice = AuthIdentityFence("user-a", 1)
    private val alice2 = AuthIdentityFence("user-a", 2)
    private val bob = AuthIdentityFence("user-b", 1)
    private val grantA = TopicGrantToken(11)
    private val grantB = TopicGrantToken(12)
    private val stamp = Instant.parse("2026-11-21T01:00:00Z")

    private fun confirmed(id: AuthIdentityFence) = OwnedPremiumAccess(id.uid, id.authGeneration, PremiumAccessState.PremiumConfirmed)
    private fun fenceFor(id: AuthIdentityFence, grant: TopicGrantToken) = TopicSessionFence(id, "epoch-1", grant)
    private fun standing(grant: TopicGrantToken, invalidations: Long = 0L) = TopicAccessSnapshot.INITIAL.copy(
        revision = 1L,
        facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet()),
        userInvalidations = invalidations
    )

    // ---- the scripted answer, the same for both families ----

    private sealed interface Answer {
        class Ok(val ids: List<Int>, val uid: String) : Answer
        class Http(val status: Int, val retryAfter: String?) : Answer
        class Failed(val kind: AlertRepositoryFailureKind) : Answer
    }

    private fun ok(vararg ids: Int, uid: String = "user-a"): Answer = Answer.Ok(ids.toList(), uid)
    private fun http(status: Int, retryAfter: String? = null): Answer = Answer.Http(status, retryAfter)
    private fun failed(kind: AlertRepositoryFailureKind): Answer = Answer.Failed(kind)

    private fun <T> resultOf(answer: Answer, row: (Int, String) -> T): AuthBoundResult<List<T>> = when (answer) {
        is Answer.Ok -> AuthBoundResult({}, Result.success(answer.ids.map { row(it, answer.uid) }))
        is Answer.Http -> {
            val headers = if (answer.retryAfter == null) headersOf() else headersOf("Retry-After", answer.retryAfter)
            val kind = when (answer.status) {
                401 -> AuthenticatedFailureKind.AUTHENTICATION
                403 -> AuthenticatedFailureKind.KNOWN_AUTHORIZATION
                else -> AuthenticatedFailureKind.OTHER_HTTP
            }
            val failure = AuthenticatedHttpFailure(answer.status, headers, ByteArray(0), null, null, kind)
            AuthBoundResult({}, Result.failure(mapAlertHttpFailure(failure)), AlertRateLimitResponseEvidence(answer.status, answer.retryAfter))
        }
        is Answer.Failed -> AuthBoundResult({}, Result.failure(AlertRepositoryException("failed", failureKind = answer.kind)))
    }

    private class Call(val identity: AuthIdentityFence, val admitted: () -> Boolean) {
        val answer = CompletableDeferred<Answer>()
        var cancelled = false
        val open: Boolean get() = !answer.isCompleted && !cancelled
    }

    /** What the scenarios see of an owner, whichever family it is. */
    private interface Subject {
        val endpoint: AuthenticatedEndpoint
        val phase: AlertLoadPhase
        /** The snapshot's ids, null before any success. */
        val ids: List<Int>?
        val identity: AuthIdentityFence?
        val manualState: StateFlow<ManualRefreshState>
        val outcomes: SharedFlow<ManualRefreshOutcome>
        fun onForeground(authoritative: Boolean)
        fun onBackground()
        fun ensure()
        fun authoritative()
        fun manual()
    }

    private inner class Rig(val test: TestScope) {
        val t0: Long = test.testScheduler.currentTime
        var identity: AuthIdentityFence? = alice
        var fence: TopicSessionFence? = fenceFor(alice, grantA)
        var snapshot: TopicAccessSnapshot = standing(grantA)
        val deletions = DeletionAdmissionStore()
        val premium = MutableStateFlow(confirmed(alice))
        val accessRevisions = MutableStateFlow(0L)
        val access = AlertSettingsAccess(
            { identity }, { fence }, SnapshotTopicUseAuthority { snapshot }, { premium.value }, { true },
            GraphProtectedAdmission({ identity }, deletions)
        )
        val floor = AlertRateLimitFloor({ test.testScheduler.currentTime })
        val calls = mutableListOf<Call>()
        var ignoresCancellation = false
        private val job = SupervisorJob()
        private val clock: () -> Long = { test.testScheduler.currentTime }
        private val scope = CoroutineScope(job + StandardTestDispatcher(test.testScheduler))
        /** Owner sleeps (backoff, floor, access recheck, cooldown timer) not yet over or cancelled. */
        var sleeping = 0
        private val sleep: suspend (Long) -> Unit = {
            sleeping++
            try {
                delay(it)
            } finally {
                sleeping--
            }
        }

        private suspend fun <T> read(who: AuthIdentityFence, admitted: () -> Boolean, row: (Int, String) -> T): AuthBoundResult<List<T>> {
            val call = Call(who, admitted).also { calls += it }
            val answer = try {
                if (ignoresCancellation) withContext(NonCancellable) { call.answer.await() } else call.answer.await()
            } catch (e: CancellationException) {
                call.cancelled = true
                throw e
            }
            return resultOf(answer, row)
        }

        val owner: Subject = when (family) {
            "bank" -> {
                val bank = BankAlertSettingsOwner(
                    { who, admitted -> read(who, admitted, ::bankRow) }, access, floor, AlertRetryPolicy(), clock, { 0.0 },
                    scope, sleep, premium, accessRevisions, MutableStateFlow(false),
                    { _, _, _, _ -> error("S8-3: no toggle in this test") },
                    { _, _, _ -> error("S8-3: no delete in this test") },
                    { _, _, _ -> error("S8-3: no create in this test") },
                    { _, _, _, _ -> error("S8-3: no edit in this test") },
                    RECHECK
                )
                object : Subject {
                    override val endpoint = AuthenticatedEndpoint.NOTIFICATION_SETTINGS
                    override val phase get() = bank.state.value.phase
                    override val ids get() = bank.state.value.settings?.map { it.id }
                    override val identity get() = bank.state.value.identity
                    override val manualState get() = bank.manualRefresh
                    override val outcomes get() = bank.manualOutcomes
                    override fun onForeground(authoritative: Boolean) = bank.onForeground(authoritative)
                    override fun onBackground() = bank.onBackground()
                    override fun ensure() = bank.ensure()
                    override fun authoritative() = bank.authoritative()
                    override fun manual() = bank.manual()
                }
            }
            else -> {
                val source = SourceAlertSettingsOwner(
                    { who, admitted -> read(who, admitted, ::sourceRow) }, access, floor, AlertRetryPolicy(), clock, { 0.0 },
                    scope, sleep, premium, accessRevisions,
                    { _, _, _ -> error("S8-5a: no create") }, { _, _, _, _ -> error("S8-5a: no edit") }, { _, _, _ -> error("S8-5a: no delete") }, RECHECK
                )
                object : Subject {
                    override val endpoint = AuthenticatedEndpoint.SOURCE_NOTIFICATION_SETTINGS
                    override val phase get() = source.state.value.phase
                    override val ids get() = source.state.value.settings?.map { it.id }
                    override val identity get() = source.state.value.identity
                    override val manualState get() = source.manualRefresh
                    override val outcomes get() = source.manualOutcomes
                    override fun onForeground(authoritative: Boolean) = source.onForeground(authoritative)
                    override fun onBackground() = source.onBackground()
                    override fun ensure() = source.ensure()
                    override fun authoritative() = source.authoritative()
                    override fun manual() = source.manual()
                }
            }
        }
        val outcomes = mutableListOf<ManualRefreshOutcome>()

        init {
            CoroutineScope(job + UnconfinedTestDispatcher(test.testScheduler)).launch { owner.outcomes.collect { outcomes += it } }
            settle()
        }

        val now: Long get() = test.testScheduler.currentTime
        fun settle() = test.runCurrent()
        fun advance(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }
        /** Advances to [offset] after [t0] and runs what is due exactly then. */
        fun at(offset: Long) {
            val target = t0 + offset
            check(target >= now) { "time only runs forward: asked for $offset, at ${now - t0}" }
            if (target > now) test.advanceTimeBy(target - now)
            test.runCurrent()
        }
        fun foreground(authoritative: Boolean = false) {
            owner.onForeground(authoritative)
            settle()
        }
        fun background() {
            owner.onBackground()
            settle()
        }
        fun open(): List<Call> = calls.filter { it.open }
        fun answer(answer: Answer) {
            val pending = open()
            check(pending.size == 1) { "exactly one GET awaits its answer, not ${pending.size}" }
            pending.single().answer.complete(answer)
            settle()
        }
        fun answer(index: Int, answer: Answer) {
            check(calls[index].answer.complete(answer)) { "GET $index was already answered" }
            settle()
        }
        /** Answers every GET with [answerFor] (its index) as soon as it is sent, for [forMillis] of virtual time. */
        fun answerAsSent(forMillis: Long, answerFor: (Int) -> Answer) {
            val end = now + forMillis
            while (true) {
                settle()
                calls.forEachIndexed { i, c -> if (c.open) c.answer.complete(answerFor(i)) }
                settle()
                if (now >= end) return
                test.advanceTimeBy(minOf(STEP, end - now))
            }
        }
        fun floorAt(): Long? = floor.notBefore(owner.endpoint).let { if (it == Long.MIN_VALUE) null else it - t0 }
        fun switchTo(id: AuthIdentityFence, grant: TopicGrantToken) {
            identity = id
            fence = fenceFor(id, grant)
            snapshot = standing(grant)
            premium.value = confirmed(id)
            settle()
        }
        fun close() = job.cancel()
    }

    private fun bankRow(id: Int, uid: String) =
        AlertSetting(id, uid, "hana", "usd-krw", AlertCondition.ABOVE, 1400.0 + id, true, false, stamp, stamp, null, null)

    private fun sourceRow(id: Int, uid: String) =
        SourceAlertSetting(id, uid, "upbit", "usdt-krw", AlertCondition.ABOVE, 1400.0 + id, true, false, null, stamp, stamp, null)

    private fun rig(test: TestScope, body: Rig.() -> Unit) {
        val r = Rig(test)
        try {
            r.body()
        } finally {
            r.close()
        }
    }

    @Test fun `S8_3_L01 ensure and manual join the GET in flight`() = runTest {
        rig(this) {
            foreground()
            assertEquals("$family: the foreground sends one GET", 1, calls.size)
            repeat(3) { owner.ensure() }
            owner.manual()
            settle()
            assertEquals("$family: ensures and a manual join it", 1, calls.size)
            assertTrue("$family: the manual is attached to the fetch", owner.manualState.value.requested && owner.manualState.value.sending)
            answer(ok(1))
            advance(60_000)
            assertEquals("$family: one GET served them all, no trailing", 1, calls.size)
            assertEquals("$family: the joined manual has one outcome", listOf<ManualRefreshOutcome>(ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("$family: its list", listOf(1), owner.ids)
        }
    }

    @Test fun `S8_3_L02 demands during a backoff or a floor sleep are absorbed by the next fetch`() = runTest {
        rig(this) {
            foreground()
            answer(failed(AlertRepositoryFailureKind.NETWORK))
            assertEquals("$family premise: a retry is waiting", AlertLoadPhase.Retrying, owner.phase)
            repeat(3) { owner.authoritative(); owner.ensure() }
            settle()
            at(1_999)
            assertEquals("$family: nothing sent during the 2 s backoff", 1, calls.size)
            at(2_000)
            assertEquals("$family: the next fetch serves the waiting demands", 2, calls.size)
            answer(ok(1))
            advance(60_000)
            assertEquals("$family: and owes nothing more", 2, calls.size)

            background()
            at(70_000)
            foreground(authoritative = true)
            answer(http(429, "20"))
            assertEquals("$family premise: the floor is 20 s on", 70_000L + 20_000L, floorAt())
            repeat(3) { owner.authoritative() }
            settle()
            at(89_999)
            assertEquals("$family: nothing sent before the floor", 3, calls.size)
            at(90_000)
            assertEquals("$family: one fetch after it", 4, calls.size)
            answer(ok(2))
            advance(60_000)
            assertEquals("$family: no further fetch", 4, calls.size)
        }
    }

    @Test fun `S8_3_L03 authoritative demands during a fetch owe exactly one trailing fetch`() = runTest {
        rig(this) {
            foreground()
            repeat(4) { owner.authoritative() }
            settle()
            assertEquals("$family: still one in flight", 1, calls.size)
            answer(ok(1))
            assertEquals("$family: exactly one trailing fetch", 2, calls.size)
            answer(ok(2))
            advance(60_000)
            assertEquals("$family: nothing after the trailing fetch", 2, calls.size)
            assertEquals("$family: the trailing answer is shown", listOf(2), owner.ids)
        }
    }

    @Test fun `S8_3_L04 a return under 30 s after a success sends nothing and at 30 s loads`() = runTest {
        rig(this) {
            foreground()
            answer(ok(1))
            at(29_999); background(); foreground()
            assertEquals("$family: 29,999 ms after the success, no GET", 1, calls.size)
            at(30_000); background(); foreground()
            assertEquals("$family: from 30,000 ms, one GET", 2, calls.size)
        }
    }

    @Test fun `S8_3_L05 the background drops the chain and a late answer is never applied`() = runTest {
        rig(this) {
            ignoresCancellation = true
            foreground()
            background()
            answer(0, ok(9))
            assertNull("$family: the late answer of a dropped chain is not applied", owner.ids)
            assertEquals("$family: Idle in the background", AlertLoadPhase.Idle, owner.phase)
            advance(60_000)
            assertEquals("$family: no wake in the background", 1, calls.size)
            foreground()
            assertEquals("$family: the next foreground loads", 2, calls.size)
            answer(1, ok(1))
            at(100_000); foreground(authoritative = true)
            val inFlight = calls.size - 1
            background()
            answer(inFlight, ok(5))
            assertEquals("$family: the success kept, the late answer not applied", listOf(1), owner.ids)
        }
        val waits = listOf<Pair<String, Rig.() -> Unit>>(
            "a backoff" to { foreground(); answer(failed(AlertRepositoryFailureKind.NETWORK)) },
            "a floor" to { foreground(); answer(http(429, "20")) },
            "an access recheck" to { fence = null; foreground() }
        )
        for ((name, wait) in waits) rig(this) {
            wait()
            val sent = calls.size
            assertEquals("$family $name premise: the chain sleeps", 1, sleeping)
            background()
            assertEquals("$family $name: the background cancels the chain's sleep", 0, sleeping)
            fence = fenceFor(alice, grantA)
            at(60_000)
            assertEquals("$family $name: no GET after the sleep would have ended", sent, calls.size)
        }
    }

    @Test fun `S8_3_L06 the retry budget is six attempts, three for a missing credential, one for a lone manual and none after 401`() = runTest {
        rig(this) {
            foreground()
            answerAsSent(200_000) { failed(AlertRepositoryFailureKind.NETWORK) }
            assertEquals("$family: a network failure is tried six times", 6, calls.size)
            assertEquals("$family: then terminal", AlertLoadPhase.TerminalError(AlertLoadFailure.Failed(AlertRepositoryFailureKind.NETWORK, null)), owner.phase)
        }
        rig(this) {
            foreground()
            answerAsSent(200_000) { http(503) }
            assertEquals("$family: a retryable HTTP failure is tried six times too", 6, calls.size)
        }
        rig(this) {
            foreground()
            answerAsSent(200_000) { http(403) }
            assertEquals("$family: 403 is terminal at once", 1, calls.size)
            assertEquals("$family: as forbidden", AlertLoadPhase.TerminalError(AlertLoadFailure.Forbidden), owner.phase)
        }
        rig(this) {
            foreground()
            answerAsSent(200_000) { failed(AlertRepositoryFailureKind.AUTH_UNAVAILABLE) }
            assertEquals("$family: a missing credential is tried three times", 3, calls.size)
        }
        rig(this) {
            foreground()
            answerAsSent(200_000) { http(401) }
            assertEquals("$family: 401 is terminal at once", 1, calls.size)
            assertEquals("$family: as auth required", AlertLoadPhase.TerminalError(AlertLoadFailure.AuthRequired), owner.phase)
        }
        rig(this) {
            foreground()
            answer(ok(1))
            at(1_000)
            owner.manual()
            settle()
            answerAsSent(200_000) { failed(AlertRepositoryFailureKind.NETWORK) }
            assertEquals("$family: a lone manual is one try", 2, calls.size)
            assertEquals("$family: with one Failed outcome", listOf<ManualRefreshOutcome>(ManualRefreshOutcome.Failed), outcomes)
        }
    }

    @Test fun `S8_3_L07 the floor and the manual cooldown survive the background and end at their boundary`() = runTest {
        rig(this) {
            foreground()
            owner.manual()
            settle()
            answer(ok(1))
            assertEquals("premise: one outcome", listOf<ManualRefreshOutcome>(ManualRefreshOutcome.Succeeded), outcomes)
            at(1_000); background(); foreground()
            at(2_999)
            owner.manual()
            settle()
            assertEquals("$family: still cooling 1 ms before the 3 s boundary", ManualRefreshOutcome.CoolingDown(1), outcomes.last())
            assertEquals("$family: no GET for it", 1, calls.size)
            at(3_000)
            owner.manual()
            settle()
            assertEquals("$family: at the boundary the manual sends", 2, calls.size)
            answer(http(429, "20"))
            assertEquals("$family premise: the floor is set from 3 s", 3_000L + 20_000L, floorAt())
            background()
            at(4_000)
            foreground(authoritative = true)
            at(22_999)
            assertEquals("$family: the floor survived the background: nothing before it", 2, calls.size)
            at(23_000)
            assertEquals("$family: at its deadline one GET", 3, calls.size)
        }
        rig(this) {
            foreground()
            answer(ok(1))
            /** A background round trip; an ensure it starts once 30 s are gone is answered, as an automatic success. */
            fun roundTrip() {
                background(); foreground()
                if (open().isNotEmpty()) answer(ok(1))
            }
            fun failManual(atOffset: Long) {
                at(atOffset)
                val sent = calls.size
                owner.manual(); settle()
                assertEquals("$family premise: the manual at $atOffset sends", sent + 1, calls.size)
                answer(failed(AlertRepositoryFailureKind.NETWORK))
                assertEquals("$family premise: it failed", ManualRefreshOutcome.Failed, outcomes.last())
            }
            fun coolingUntil(deadline: Long) {
                roundTrip()
                at(deadline - 1)
                val sent = calls.size
                owner.manual(); settle()
                assertEquals("$family: 1 ms before $deadline still cooling", ManualRefreshOutcome.CoolingDown(1), outcomes.last())
                assertEquals("$family: and no GET for it", sent, calls.size)
            }
            failManual(1_000); coolingUntil(6_000)
            failManual(6_000); coolingUntil(16_000)
            failManual(16_000); coolingUntil(36_000)
            failManual(36_000); coolingUntil(56_000)
            at(56_000)
            owner.manual(); settle()
            answer(ok(2))
            assertEquals("$family premise: a manual success", ManualRefreshOutcome.Succeeded, outcomes.last())
            coolingUntil(59_000)
            failManual(59_000)
            coolingUntil(64_000)
        }
    }

    @Test fun `S8_3_L08 an empty success is kept as a success`() = runTest {
        rig(this) {
            foreground()
            answer(ok())
            assertEquals("$family: an empty list, not no success", emptyList<Int>(), owner.ids)
            assertEquals("$family: Ready", AlertLoadPhase.Ready, owner.phase)
            at(29_999); background(); foreground()
            assertEquals("$family: the empty success is fresh for 30 s", 1, calls.size)
            at(30_000); background(); foreground()
            assertEquals("$family: and loads from 30 s", 2, calls.size)
            answerAsSent(200_000) { failed(AlertRepositoryFailureKind.NETWORK) }
            assertEquals("$family: a terminal failure keeps the empty success", emptyList<Int>(), owner.ids)
            assertEquals("$family: shown Ready", AlertLoadPhase.Ready, owner.phase)
        }
    }

    @Test fun `S8_3_L09 an answer that arrives after the identity, the session or the use lifetime moved is never applied`() = runTest {
        val moves = listOf<Pair<String, Rig.() -> Unit>>(
            "A to B" to { switchTo(bob, grantB) },
            "A1 to B to A2" to { switchTo(bob, grantB); switchTo(alice2, grantA) },
            "the use invalidated and recovered, same UID and generation" to {
                snapshot = standing(grantA, invalidations = 1)
                accessRevisions.value += 1
                settle()
            }
        )
        for ((name, move) in moves) for (success in listOf(true, false)) rig(this) {
            val label = "$family $name, ${if (success) "success" else "503"}"
            ignoresCancellation = true
            foreground()
            answer(ok(1))
            at(1_000); owner.authoritative(); owner.manual(); settle()
            val stale = calls.lastIndex
            assertTrue("premise($label): the GET in flight under a standing guard", calls[stale].admitted())
            move()
            assertTrue("premise($label): a new use is admitted", access.acquire()?.let(access::admits) == true)
            assertTrue("$label: the moved GET's guard refuses", !calls[stale].admitted())
            val before = listOf(owner.ids, owner.identity, owner.phase)
            val told = outcomes.toList()
            val sent = calls.size
            answer(stale, if (success) ok(2) else http(503))
            assertEquals("$label: the late answer changes nothing", before, listOf(owner.ids, owner.identity, owner.phase))
            assertEquals("$label: and gives no outcome", told, outcomes)
            assertEquals("$label: and starts no GET", sent, calls.size)
            advance(70_000)
            assertEquals("$label: nor a retry later", sent, calls.size)
        }
    }

    @Test fun `S8_3_L10 a refused access is rechecked each second or woken by a hint, resumes on a valid use and spends no retry`() = runTest {
        rig(this) {
            fence = null
            foreground()
            at(2_000)
            assertEquals("$family: no fence, no GET", 0, calls.size)
            fence = fenceFor(alice, grantA)
            at(2_999)
            assertEquals("$family: without a hint, nothing until the next recheck", 0, calls.size)
            at(3_000)
            assertEquals("$family: the recheck 1 s later sends", 1, calls.size)
            assertEquals("$family: for the live identity", alice, calls.single().identity)
            assertTrue("$family: with a guard that reads the access live", calls.single().admitted())
            identity = null
            assertTrue("$family: the guard refuses once the identity is gone", !calls.single().admitted())
        }
        rig(this) {
            fence = null
            foreground()
            at(2_000)
            fence = fenceFor(alice, grantA)
            accessRevisions.value += 1
            settle()
            assertEquals("$family: an access hint wakes the wait at once", 1, calls.size)
            at(3_000)
            assertEquals("$family: the old timer adds no GET", 1, calls.size)
        }
        rig(this) {
            fence = null
            foreground()
            at(10_000)
            fence = fenceFor(alice, grantA)
            at(11_000)
            assertEquals("premise: sent after ten rechecks", 1, calls.size)
            answerAsSent(200_000) { failed(AlertRepositoryFailureKind.NETWORK) }
            assertEquals("$family: the waits spent no retry: six tries", 6, calls.size)
        }
    }
}
