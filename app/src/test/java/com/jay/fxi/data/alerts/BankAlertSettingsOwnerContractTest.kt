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
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.repository.AlertRateLimitResponseEvidence
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.mapAlertHttpFailure
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 N1 contract (R4c/S7/s7n1 API r1-r3): the bank alert settings owner (API r1 §5 as replaced by r2/r3 §4, floor
 * rules r2/r3 §2) — a refused access sends no GET (N1-B); an answer is not applied once the identity, session, use lifetime or
 * generation moved, a deletion began, or its own session check failed (N1-C, N1-D); the phase and list for no, empty and
 * non-empty snapshots through retry, terminal and background, with failures never renewing the last success (N1-E); 30 s
 * freshness (N1-F); joining, trailing and absorption, with the retry budget apart from the trailing fetch (N1-G); manual
 * outcomes, promotion and the 3/5/10/20 s cooldowns (N1-H); the process Retry-After floor as the owner records it (before any
 * drop, exchanges first) and waits for it before every send (N1-I, N1-K); the 6/3 logical attempts (N1-L); the foreground gate
 * and the adapter (N1-M, N1-O); the late-fence wait and its rechecks (N1-T); and the premium signals (reset, regain, A to B).
 * The GET is scripted: every call waits for the test's answer, optionally ignoring cancellation so a dropped chain's answer
 * really comes back. The access is the production [AlertSettingsAccess] over the production use authority and deletion store,
 * with inputs the test moves; the floor, the policy and the clock are real, on virtual time.
 * The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BankAlertSettingsOwnerContractTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val alice2 = AuthIdentityFence("user-a", 2)
    private val bob = AuthIdentityFence("user-b", 1)
    private val grantA = TopicGrantToken(11)
    private val grantB = TopicGrantToken(12)
    private val stamp = Instant.parse("2026-11-11T01:00:00Z")

    private val nothing = BankAlertSettingsState(AlertLoadPhase.Idle, null, null)
    private fun ready(list: List<AlertSetting>, who: AuthIdentityFence = alice) = BankAlertSettingsState(AlertLoadPhase.Ready, list, who)
    private fun terminal(status: Int): AlertLoadPhase =
        AlertLoadPhase.TerminalError(AlertLoadFailure.Failed(AlertRepositoryFailureKind.HTTP, status))
    private fun terminal(failure: AlertLoadFailure): AlertLoadPhase = AlertLoadPhase.TerminalError(failure)

    private fun setting(id: Int, uid: String = "user-a") =
        AlertSetting(id, uid, "hana", "usd-krw", AlertCondition.ABOVE, 1400.0 + id, true, false, stamp, stamp, null, null)

    private fun confirmed(id: AuthIdentityFence) = OwnedPremiumAccess(id.uid, id.authGeneration, PremiumAccessState.PremiumConfirmed)
    private fun premiumOf(id: AuthIdentityFence, state: PremiumAccessState) = OwnedPremiumAccess(id.uid, id.authGeneration, state)
    private fun fenceFor(id: AuthIdentityFence, grant: TopicGrantToken, epoch: String = "epoch-1") = TopicSessionFence(id, epoch, grant)
    private fun standing(grant: TopicGrantToken, invalidations: Long = 0L) = TopicAccessSnapshot.INITIAL.copy(
        revision = 1L,
        facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet()),
        userInvalidations = invalidations
    )

    // ---- the scripted GET and its answers ----

    private sealed interface Outcome {
        class Answer(val result: AuthBoundResult<List<AlertSetting>>) : Outcome
        class Throw(val error: Throwable) : Outcome
    }

    private class Call(val identity: AuthIdentityFence, val admitted: () -> Boolean) {
        val outcome = CompletableDeferred<Outcome>()
        /** The GET's coroutine saw its cancellation (a cooperative transport). */
        var cancelled = false
        val open: Boolean get() = !outcome.isCompleted && !cancelled
    }

    private fun ok(vararg ids: Int, uid: String = "user-a"): Outcome =
        Outcome.Answer(AuthBoundResult<List<AlertSetting>>({}, Result.success(ids.map { setting(it, uid) })))

    private fun okGuarded(requireCurrent: () -> Unit, vararg ids: Int): Outcome =
        Outcome.Answer(AuthBoundResult<List<AlertSetting>>(requireCurrent, Result.success(ids.map { setting(it) })))

    /** An HTTP failure as the repository returns it: the typed exception and, readable before the guard, status and header. */
    private fun http(status: Int, retryAfter: String? = null, requireCurrent: () -> Unit = {}): Outcome {
        val headers = if (retryAfter == null) headersOf() else headersOf("Retry-After", retryAfter)
        val kind = when (status) {
            401 -> AuthenticatedFailureKind.AUTHENTICATION
            403 -> AuthenticatedFailureKind.KNOWN_AUTHORIZATION
            404 -> AuthenticatedFailureKind.UNKNOWN_NOT_FOUND
            else -> AuthenticatedFailureKind.OTHER_HTTP
        }
        val failure = AuthenticatedHttpFailure(status, headers, ByteArray(0), null, null, kind)
        return Outcome.Answer(
            AuthBoundResult<List<AlertSetting>>(
                requireCurrent, Result.failure(mapAlertHttpFailure(failure)), AlertRateLimitResponseEvidence(status, retryAfter)
            )
        )
    }

    private fun failed(kind: AlertRepositoryFailureKind, message: String): Outcome =
        Outcome.Answer(AuthBoundResult<List<AlertSetting>>({}, Result.failure(AlertRepositoryException(message, failureKind = kind))))

    private fun thrown(error: Throwable): Outcome = Outcome.Throw(error)

    private val identityChanged: () -> Unit = { throw AuthIdentityChangedException() }

    // ---- the rig ----

    /**
     * Every access input standing for [alice] (identity, fence, standing grant, release, no deletion, premium per [premiumAt]),
     * the production access, a real floor and policy on the test clock, jitter 0, the owner on a StandardTestDispatcher and an
     * eager outcome collector. Times are offsets from the rig's own start [t0].
     */
    private inner class Rig(val test: TestScope, recheck: Long, premiumAt: OwnedPremiumAccess) {
        val t0: Long = test.testScheduler.currentTime
        var identity: AuthIdentityFence? = alice
        var fence: TopicSessionFence? = fenceFor(alice, grantA)
        var snapshot: TopicAccessSnapshot = standing(grantA)
        var release = true
        val deletions = DeletionAdmissionStore()
        val premium = MutableStateFlow(premiumAt)
        val accessRevisions = MutableStateFlow(0L)
        val graphHostReady = MutableStateFlow(false)
        val access = AlertSettingsAccess(
            { identity }, { fence }, SnapshotTopicUseAuthority { snapshot }, { premium.value }, { release },
            GraphProtectedAdmission({ identity }, deletions)
        )
        val floor = AlertRateLimitFloor({ test.testScheduler.currentTime })
        val calls = mutableListOf<Call>()
        val sleeps = mutableListOf<Long>()
        var ignoresCancellation = false

        private val read: suspend (AuthIdentityFence, () -> Boolean) -> AuthBoundResult<List<AlertSetting>> = { who, admitted ->
            val call = Call(who, admitted).also { calls += it }
            val outcome = try {
                if (ignoresCancellation) withContext(NonCancellable) { call.outcome.await() } else call.outcome.await()
            } catch (e: CancellationException) {
                call.cancelled = true
                throw e
            }
            when (outcome) {
                is Outcome.Answer -> outcome.result
                is Outcome.Throw -> throw outcome.error
            }
        }
        private val sleep: suspend (Long) -> Unit = { millis ->
            sleeps += millis
            delay(millis)
        }
        private val job = SupervisorJob()
        val owner = BankAlertSettingsOwner(
            read, access, floor, AlertRetryPolicy(), { test.testScheduler.currentTime }, { 0.0 },
            CoroutineScope(job + StandardTestDispatcher(test.testScheduler)), sleep,
            premium, accessRevisions, graphHostReady,
            { _, _, _, _ -> error("S7E5A2: no toggle in this test") },
            { _, _, _ -> error("S7E5A2: no delete in this test") },
            recheck
        )
        val outcomes = mutableListOf<ManualRefreshOutcome>()

        init {
            CoroutineScope(job + UnconfinedTestDispatcher(test.testScheduler)).launch { owner.manualOutcomes.collect { outcomes += it } }
            settle()
        }

        val now: Long get() = test.testScheduler.currentTime
        val settings: List<AlertSetting>? get() = owner.state.value.settings
        val phase: AlertLoadPhase get() = owner.state.value.phase

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
        /** Answers the one GET awaiting an answer. */
        fun answer(outcome: Outcome) {
            val pending = open()
            check(pending.size == 1) { "exactly one GET awaits its answer, not ${pending.size}" }
            pending.single().outcome.complete(outcome)
            settle()
        }
        fun answer(index: Int, outcome: Outcome) {
            check(calls[index].outcome.complete(outcome)) { "GET $index was already answered" }
            settle()
        }
        /** Answers every GET with [outcomeFor] (its index) as soon as it is sent, for [forMillis] of virtual time. */
        fun answerAsSent(forMillis: Long, outcomeFor: (Int) -> Outcome) {
            val end = now + forMillis
            while (true) {
                settle()
                calls.forEachIndexed { i, c -> if (c.open) c.outcome.complete(outcomeFor(i)) }
                settle()
                if (now >= end) return
                test.advanceTimeBy(minOf(STEP, end - now))
            }
        }
        fun seedFloor(seconds: String) = floor.record(SETTINGS, AlertRateLimitEvidence.of(emptyList(), 429, seconds))
        /** The floor's deadline as an offset from [t0], or null when there is none. */
        fun floorAt(): Long? = floor.notBefore(SETTINGS).let { if (it == Long.MIN_VALUE) null else it - t0 }
        /** Moves the whole account to [id]: identity, a fence and a standing grant for it, then the premium signal. */
        fun switchTo(id: AuthIdentityFence, grant: TopicGrantToken) {
            identity = id
            fence = fenceFor(id, grant)
            snapshot = standing(grant)
            premium.value = confirmed(id)
            settle()
        }
        fun close() = job.cancel()
    }

    private fun rig(test: TestScope, recheck: Long = RECHECK, premiumAt: OwnedPremiumAccess = confirmed(alice), body: Rig.() -> Unit) {
        val r = Rig(test, recheck, premiumAt)
        try {
            r.body()
        } finally {
            r.close()
        }
    }

    // ---- N1-M / N1-O: construction, the foreground gate, the adapter ----

    /** N1-M (owner): the constructor subscribes and waits; only the registry's foreground starts the first GET. */
    @Test fun `S7N1_M01 constructing the owner sends nothing until it is told the foreground`() = runTest {
        rig(this) {
            assertNotNull("premise: every access input stands, so a GET could be sent", access.acquire())
            accessRevisions.value = 1L
            graphHostReady.value = true
            advance(60_000)
            assertEquals("N1-M: constructed, subscribed and hinted, yet no GET", 0, calls.size)
            assertEquals("N1-M: nothing to show yet", nothing, owner.state.value)
            foreground()
            assertEquals("N1-M: the first foreground sends the first GET", 1, calls.size)
            assertEquals("N1-M: made for the live identity", alice, calls.single().identity)
        }
    }

    /** N1-M (owner): every demand and every signal is foreground-only; none of them starts a GET in the background. */
    @Test fun `S7N1_M02 no demand or signal sends a GET in the background`() = runTest {
        rig(this) {
            fun storm() {
                val sent = calls.size
                val told = outcomes.size
                owner.ensure(); owner.authoritative(); owner.manual(); owner.onSync()
                // Alone first: a premium change below would reset a chain a demand had wrongly started, and hide it.
                advance(60_000)
                assertEquals("N1-M: the demands alone send nothing in the background", sent, calls.size)
                assertEquals("N1-M: and give no outcome", told, outcomes.size)
                premium.value = premiumOf(alice, PremiumAccessState.FreeConfirmed); settle()
                premium.value = confirmed(alice); settle()
                accessRevisions.value += 1; graphHostReady.value = !graphHostReady.value; settle()
            }
            storm()
            advance(60_000)
            assertEquals("N1-M: never told the foreground, nothing sent", 0, calls.size)
            foreground()
            assertEquals("premise: the foreground sends", 1, calls.size)
            answer(ok(1))
            background()
            advance(60_000)
            storm()
            advance(60_000)
            assertEquals("N1-M: back in the background, nothing more", 1, calls.size)
        }
    }

    /** N1-M (owner): a foreground serves exactly one demand: authoritative when asked, otherwise an ensure that keeps a fresh snapshot. */
    @Test fun `S7N1_M03 a foreground is authoritative when asked and an ensure otherwise`() = runTest {
        rig(this) {
            foreground(authoritative = true)
            assertEquals("N1-M: a cold authoritative foreground sends one GET", 1, calls.size)
            answer(ok(1))
            assertEquals("N1-M: and owes no second one", 1, calls.size)
            at(1_000); background(); foreground()
            assertEquals("N1-M: an ordinary foreground is an ensure: the 1 s old snapshot is kept", 1, calls.size)
            at(2_000); background(); foreground(authoritative = true)
            assertEquals("N1-M: an authoritative foreground loads it anyway", 2, calls.size)
            answer(ok(2))
            advance(60_000)
            assertEquals("N1-M: one demand, one GET", 2, calls.size)
        }
    }

    /** N1-O (owner): the owner is one family; an adapter call only hands it a demand, never waits on its chain, and adds no GET. */
    @Test fun `S7N1_O01 the owner is one family whose adapter calls only hand it a demand`() = runTest {
        rig(this) {
            val family: AlertFamilyAdapter = owner
            family.onForeground()
            assertEquals("N1-O: the call returned before the owner's own Job ran", 0, calls.size)
            settle()
            val hung = calls.single()
            repeat(3) {
                family.onForeground()
                family.onSync()
            }
            settle()
            assertEquals("N1-O: a hung GET holds no adapter call, and joining adds none", 1, calls.size)
            family.onBackground()
            settle()
            assertTrue("N1-O: the background ends the owner's own chain", hung.cancelled)
            advance(60_000)
            assertEquals("N1-O: and nothing more", 1, calls.size)
        }
    }

    // ---- N1-B: refused before the send ----

    private class Refusal(val label: String, val premiumAt: OwnedPremiumAccess, val refuse: Rig.() -> Unit, val lift: Rig.() -> Unit)

    private fun refusals(): List<Refusal> {
        val regain: Rig.() -> Unit = { premium.value = confirmed(alice) }
        return listOf(
            Refusal("signed out", confirmed(alice), { identity = null }, { identity = alice }),
            Refusal("premium never granted", premiumOf(alice, PremiumAccessState.NoGrant), {}, regain),
            Refusal("premium free", premiumOf(alice, PremiumAccessState.FreeConfirmed), {}, regain),
            Refusal("premium resolving", premiumOf(alice, PremiumAccessState.Pending(false)), {}, regain),
            Refusal("premium rejected", premiumOf(alice, PremiumAccessState.Rejected), {}, regain),
            Refusal("premium of another UID", confirmed(bob), {}, regain),
            Refusal("premium of the same UID in another session", confirmed(alice2), {}, regain),
            Refusal("live session other than the fence's", confirmed(alice2), { identity = alice2 }, { fence = fenceFor(alice2, grantA) }),
            Refusal("no fence, the graph not installed", confirmed(alice), { fence = null }, { fence = fenceFor(alice, grantA) }),
            Refusal("release closed", confirmed(alice), { release = false }, { release = true }),
            Refusal("deletion begun", confirmed(alice), { deletions.begin(alice, "op") }, { deletions.releaseUnsent("op") }),
            Refusal("deletion of the UID in another session", confirmed(alice), { deletions.begin(alice2, "op") }, { deletions.releaseUnsent("op") }),
            Refusal(
                "grant no longer standing", confirmed(alice),
                { snapshot = standing(grantA).let { it.copy(facts = it.facts.copy(tokenStanding = false)) } }, { snapshot = standing(grantA) }
            ),
            Refusal("grant not the fence's", confirmed(alice), { snapshot = standing(grantB) }, { snapshot = standing(grantA) })
        )
    }

    /** N1-B (owner): a refused acquire sends no GET at all (so no token is captured); the refusal lifting is what lets it go. */
    @Test fun `S7N1_B01 a refused access sends no GET until the refusal lifts`() = runTest {
        rig(this) {
            foreground()
            assertEquals("premise: with every input standing the foreground sends one GET", 1, calls.size)
        }
        for (case in refusals()) rig(this, premiumAt = case.premiumAt) {
            case.refuse(this)
            foreground()
            owner.authoritative(); owner.manual(); owner.onSync()
            advance(RECHECK * 5)
            assertEquals("N1-B ${case.label}: no GET", 0, calls.size)
            assertEquals("N1-B ${case.label}: nothing shown, no error", nothing, owner.state.value)
            assertTrue("N1-B ${case.label}: an unserved manual has no outcome", outcomes.isEmpty())
            case.lift(this)
            advance(RECHECK)
            assertEquals("N1-B ${case.label}: the refusal was what held it", 1, calls.size)
        }
    }

    private class Toggle(val label: String, val move: Rig.() -> Unit, val restore: Rig.() -> Unit)

    private fun guardToggles(): List<Toggle> = listOf(
        Toggle("identity to another UID", { identity = bob }, { identity = alice }),
        Toggle("the same UID in a new session", { identity = alice2 }, { identity = alice }),
        Toggle("signed out", { identity = null }, { identity = alice }),
        Toggle("release closed", { release = false }, { release = true }),
        Toggle("a new access epoch", { fence = fenceFor(alice, grantA, "epoch-2") }, { fence = fenceFor(alice, grantA) }),
        Toggle("graph given up", { fence = null }, { fence = fenceFor(alice, grantA) }),
        Toggle("use invalidated", { snapshot = standing(grantA, invalidations = 1) }, { snapshot = standing(grantA) }),
        Toggle("premium lost", { premium.value = premiumOf(alice, PremiumAccessState.FreeConfirmed) }, { premium.value = confirmed(alice) }),
        Toggle("deletion begun", { deletions.begin(alice, "op-guard") }, { deletions.releaseUnsent("op-guard") })
    )

    /** N1-B (owner): the GET is made for the use's identity and carries a guard that asks the access live, for each send. */
    @Test fun `S7N1_B02 a GET carries its use identity and a guard that reads the access live`() = runTest {
        rig(this) {
            foreground()
            val call = calls.single()
            assertEquals("N1-B: made for the use's identity", alice, call.identity)
            assertTrue("premise: the guard stands at send", call.admitted())
            for (toggle in guardToggles()) {
                toggle.move(this)
                assertFalse("N1-B ${toggle.label}: the guard falls with the access", call.admitted())
                toggle.restore(this)
                assertTrue("premise(${toggle.label}): restored", call.admitted())
            }
            answer(ok(1))
            assertEquals("N1-B: under a standing guard the answer applies", ready(listOf(setting(1))), owner.state.value)
        }
    }

    // ---- N1-C / N1-D: answers not applied ----

    private class Move(val label: String, val perform: Rig.() -> Unit)

    private fun moves(): List<Move> = listOf(
        Move("identity A to B") { identity = bob },
        Move("the same UID signed in again") { identity = alice2 },
        Move("A to B and back to a new session of A") { identity = bob; identity = alice2 },
        Move("a new access epoch in the same session") { fence = fenceFor(alice, grantA, "epoch-2") },
        Move("the use invalidated and recovered") { snapshot = standing(grantA, invalidations = 1) },
        Move("premium lost and regranted under a new grant") {
            // Conflated on purpose: the premium subscription sees no change, only the captured use tells the old from the new.
            premium.value = premiumOf(alice, PremiumAccessState.FreeConfirmed)
            premium.value = confirmed(alice)
            snapshot = standing(grantB)
            fence = fenceFor(alice, grantB)
        }
    )

    /** N1-C: an answer, success or failure, that arrives after the identity, the session or the use lifetime moved is dropped. */
    @Test fun `S7N1_C01 an answer arriving after the identity or use lifetime moved is not applied`() = runTest {
        for (move in moves()) for (prior in listOf(false, true)) for (success in listOf(true, false)) rig(this) {
            val label = "${move.label}, ${if (prior) "with" else "without"} a snapshot, ${if (success) "success" else "503"}"
            foreground()
            if (prior) {
                answer(ok(1))
                at(1_000); owner.authoritative(); settle()
            }
            val expected = if (prior) ready(listOf(setting(1))) else nothing
            assertTrue("premise($label): the GET in flight under a standing guard", open().single().admitted())
            owner.manual(); settle()
            move.perform(this)
            answer(if (success) ok(2) else http(503))
            assertEquals("N1-C $label: not applied", expected, owner.state.value)
            assertTrue("N1-C $label: no outcome for the joined manual", outcomes.isEmpty())
            val sent = calls.size
            advance(70_000)
            assertEquals("N1-C $label: no retry and no trailing", sent, calls.size)
        }
    }

    /** N1-C: the access still standing, an answer whose own session check fails (fold's requireCurrent) is dropped all the same. */
    @Test fun `S7N1_C02 an answer whose own session check fails is not applied`() = runTest {
        rig(this) {
            foreground()
            answer(okGuarded({}, 2))
            assertEquals("premise: a passing session check applies", ready(listOf(setting(2))), owner.state.value)
        }
        for (prior in listOf(false, true)) for (success in listOf(true, false)) rig(this) {
            val label = "${if (prior) "with" else "without"} a snapshot, ${if (success) "success" else "503"}"
            foreground()
            if (prior) {
                answer(ok(1))
                at(1_000); owner.authoritative(); settle()
            }
            owner.manual(); settle()
            answer(if (success) okGuarded(identityChanged, 2) else http(503, requireCurrent = identityChanged))
            assertEquals("N1-C $label: not applied", if (prior) ready(listOf(setting(1))) else nothing, owner.state.value)
            assertTrue("N1-C $label: no outcome", outcomes.isEmpty())
            val sent = calls.size
            advance(70_000)
            assertEquals("N1-C $label: no retry", sent, calls.size)
        }
    }

    /**
     * N1-C with N1-G: a chain dropped by the background whose transport still answers late has no effect at all on the chain that
     * replaced it: its answer is not applied, and the current fetch still owes its one trailing fetch to a demand made during it.
     */
    @Test fun `S7N1_C03 a dropped chain late answer changes nothing about the fetch that replaced it`() = runTest {
        rig(this) {
            ignoresCancellation = true
            foreground()
            background()
            foreground()
            assertEquals("premise: the old GET still awaits, the new one is sent", 2, open().size)
            answer(0, ok(1))
            assertNull("N1-C: the dropped chain's answer is not applied", settings)
            assertNull("N1-C: nor its identity", owner.state.value.identity)
            owner.authoritative(); settle()
            answer(1, ok(2))
            assertEquals("premise: the current fetch applies", ready(listOf(setting(2))), owner.state.value)
            assertEquals("N1-G: the current fetch still owes exactly one trailing GET", 3, calls.size)
        }
    }

    private class Deletion(val label: String, val refused: Boolean, val start: DeletionAdmissionStore.() -> Unit)

    private fun deletionCases(): List<Deletion> = listOf(
        Deletion("begun for this UID", refused = true) { begin(alice, "op-1") },
        Deletion("deleted on the server for this UID", refused = true) { begin(alice, "op-1"); serverDeleted("op-1") },
        Deletion("begun for this UID in another session", refused = true) { begin(alice2, "op-1") },
        Deletion("begun for another UID", refused = false) { begin(bob, "op-1") }
    )

    /** N1-D (owner): a deletion begun mid-flight drops the answer, the owed trailing, the retry and every new GET; another UID's does not. */
    @Test fun `S7N1_D01 a deletion begun while a GET is in flight drops its answer and every follow-up`() = runTest {
        for (case in deletionCases()) for (success in listOf(true, false)) rig(this) {
            val label = "${case.label}, ${if (success) "success" else "503"}"
            foreground()
            owner.authoritative(); owner.manual(); settle()
            assertEquals("premise($label): one GET, a trailing owed and a manual joined", 1, calls.size)
            case.start(deletions)
            answer(if (success) ok(1) else http(503))
            if (case.refused) {
                assertEquals("N1-D $label: not applied", nothing, owner.state.value)
                assertTrue("N1-D $label: no outcome", outcomes.isEmpty())
                advance(RECHECK * 10)
                assertEquals("N1-D $label: no trailing, retry or follow-up", 1, calls.size)
                owner.authoritative(); owner.manual(); settle()
                advance(RECHECK * 10)
                assertEquals("N1-D $label: no new GET while the deletion stands", 1, calls.size)
            } else if (success) {
                assertEquals("N1-D $label: applied", ready(listOf(setting(1))), owner.state.value)
                assertEquals("N1-D $label", listOf(ManualRefreshOutcome.Succeeded), outcomes)
                assertEquals("N1-D $label: the owed trailing is sent", 2, calls.size)
            } else {
                assertEquals("N1-D $label", listOf(ManualRefreshOutcome.Failed), outcomes)
                advance(2_000)
                assertEquals("N1-D $label: the retry is sent", 2, calls.size)
            }
        }
    }

    // ---- N1-E / N1-F: phase, list and freshness ----

    /** N1-E: no, empty and non-empty snapshot times retry, terminal and background; no failure moves the last success time. */
    @Test fun `S7N1_E01 phase and list for each snapshot kind through a retry, a terminal failure and the background`() = runTest {
        for (snap in listOf("none", "empty", "non-empty")) for (end in listOf("retry", "terminal", "background")) rig(this) {
            val label = "$snap snapshot, $end"
            val shown: List<AlertSetting>? = when (snap) {
                "none" -> null
                "empty" -> emptyList()
                else -> listOf(setting(1), setting(2))
            }
            foreground()
            if (shown == null) {
                assertEquals("N1-E $label: a cold fetch is Loading", BankAlertSettingsState(AlertLoadPhase.Loading, null, null), owner.state.value)
            } else {
                answer(if (shown.isEmpty()) ok() else ok(1, 2))
                assertEquals("premise($label): the snapshot, an empty list included", ready(shown), owner.state.value)
                at(1_000); owner.authoritative(); settle()
                assertEquals("N1-E $label: a later fetch keeps the list and Ready", ready(shown), owner.state.value)
            }
            val retrying = if (shown == null) BankAlertSettingsState(AlertLoadPhase.Retrying, null, null) else ready(shown)
            val ended = if (shown == null) BankAlertSettingsState(terminal(400), null, null) else ready(shown)
            when (end) {
                "retry" -> {
                    answer(http(503))
                    assertEquals("N1-E $label: waiting to retry", retrying, owner.state.value)
                    advance(2_000)
                    assertEquals("premise($label): the retry is sent", if (shown == null) 2 else 3, calls.size)
                    assertEquals("N1-E $label: during the retry", retrying, owner.state.value)
                    answer(http(400))
                    assertEquals("N1-E $label: ended", ended, owner.state.value)
                }
                "terminal" -> {
                    answer(http(400))
                    assertEquals("N1-E $label: ended", ended, owner.state.value)
                }
                else -> {
                    background()
                    assertEquals("N1-E $label: backgrounded", if (shown == null) nothing else ready(shown), owner.state.value)
                }
            }
            if (shown != null) {
                val sent = calls.size
                at(29_999)
                if (end == "background") owner.onForeground() else owner.ensure()
                settle()
                assertEquals("N1-E $label: still fresh from the success at 0", sent, calls.size)
                at(30_000)
                owner.ensure(); settle()
                assertEquals("N1-E $label: no failure renewed the last success", sent + 1, calls.size)
            }
        }
    }

    /** N1-F: the iOS strict under-30 s rule: 29 999 ms after a success an ensure skips; from exactly 30 000 ms it loads. */
    @Test fun `S7N1_F01 an ensure skips a success under 30 s old and loads from exactly 30 s`() = runTest {
        rig(this) {
            foreground()
            answer(ok(1))
            at(29_999)
            owner.ensure(); settle()
            background(); foreground()
            assertEquals("N1-F: 29 999 ms after the success an ensure and a foreground skip", 1, calls.size)
            at(30_000)
            assertEquals("N1-F: the skipped ensure left no demand behind", 1, calls.size)
            owner.ensure(); settle()
            assertEquals("N1-F: at exactly 30 000 ms ensure loads", 2, calls.size)
        }
    }

    /** N1-F: an empty list is a snapshot and is as fresh as any other. */
    @Test fun `S7N1_F02 an empty snapshot is fresh for 30 s too`() = runTest {
        rig(this) {
            foreground()
            answer(ok())
            assertEquals("premise: an empty snapshot", ready(emptyList()), owner.state.value)
            at(29_999); owner.ensure(); settle()
            assertEquals("N1-F: the empty snapshot is fresh", 1, calls.size)
            at(30_000); owner.ensure(); settle()
            assertEquals("N1-F: and stale at 30 s", 2, calls.size)
        }
    }

    /** N1-F: an authoritative demand (sync, authoritative foreground) is never skipped for freshness. */
    @Test fun `S7N1_F03 an authoritative demand is never skipped by a fresh snapshot`() = runTest {
        rig(this) {
            foreground()
            answer(ok(1))
            at(1); owner.authoritative(); settle()
            assertEquals("N1-F: authoritative 1 ms after a success loads", 2, calls.size)
            answer(ok(2))
            at(2); owner.onSync(); settle()
            assertEquals("N1-F: a foreground sync loads", 3, calls.size)
            answer(ok(3))
            at(3); background(); foreground(authoritative = true)
            assertEquals("N1-F: an authoritative foreground loads", 4, calls.size)
        }
    }

    /** N1-F: the last success is the answer, not the send: a GET answered 10 s after its send is fresh until 30 s after the answer. */
    @Test fun `S7N1_F04 a slow GET is fresh for 30 s from its success, not from its send`() = runTest {
        rig(this) {
            foreground()
            owner.manual(); settle()
            at(9_999)
            assertEquals("premise: the GET sent at 0 still awaits its answer", 1, open().size)
            at(10_000)
            answer(ok(1))
            assertEquals("premise: its answer applies at 10 s", ready(listOf(setting(1))), owner.state.value)
            assertEquals("premise: the joined manual is served", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            at(12_999); owner.manual(); settle()
            assertEquals(
                "N1-H: its 3 s cooldown runs from the fetch's end at 10 s",
                listOf(ManualRefreshOutcome.Succeeded, ManualRefreshOutcome.CoolingDown(1)), outcomes
            )
            // Window: [30 000, 40 000) ms after the send, stale when timed from the send and fresh when timed from the success.
            at(30_000); owner.ensure(); settle()
            assertEquals("N1-F: 30 s after the send but 20 s after the success, an ensure skips", 1, calls.size)
            at(39_999); owner.ensure(); settle()
            background(); foreground()
            assertEquals("N1-F: 29 999 ms after the success an ensure and a foreground skip", 1, calls.size)
            at(40_000); owner.ensure(); settle()
            assertEquals("N1-F: from exactly 30 s after the success it loads", 2, calls.size)
        }
    }

    // ---- N1-G: joining, trailing, absorption ----

    /** N1-G: ensure demands and access hints during a fetch add no GET and owe no trailing; a storm on a stale snapshot sends one. */
    @Test fun `S7N1_G01 an ensure storm adds no GET`() = runTest {
        rig(this) {
            foreground()
            repeat(20) {
                owner.ensure()
                owner.onForeground()
            }
            accessRevisions.value = 1L
            graphHostReady.value = true
            settle()
            assertEquals("N1-G: an ensure storm and hints during a fetch add no GET", 1, calls.size)
            answer(ok(1))
            advance(60_000)
            assertEquals("N1-G: and owe no trailing", 1, calls.size)
            repeat(20) { owner.ensure() }
            settle()
            assertEquals("N1-G: a storm on a stale snapshot sends one GET", 2, calls.size)
        }
    }

    /** N1-G: any number of authoritative demands during one fetch owe exactly one trailing fetch after its success. */
    @Test fun `S7N1_G02 authoritative demands during a fetch owe exactly one trailing fetch`() = runTest {
        rig(this) {
            foreground()
            repeat(5) {
                owner.authoritative()
                owner.onSync()
            }
            settle()
            assertEquals("N1-G: joined, nothing sent yet", 1, calls.size)
            answer(ok(1))
            assertEquals("N1-G: one trailing GET after the success", 2, calls.size)
            answer(ok(2))
            advance(60_000)
            assertEquals("N1-G: and only one", 2, calls.size)
            assertEquals("premise: the trailing answer applies", ready(listOf(setting(2))), owner.state.value)
        }
    }

    /** N1-G: a demand made while the Job sleeps (backoff, floor, access) is served by the next fetch and owes no trailing. */
    @Test fun `S7N1_G03 demands during a backoff, floor or access sleep are absorbed by the next fetch`() = runTest {
        rig(this) {
            foreground()
            answer(http(503))
            at(1_000)
            repeat(3) {
                owner.authoritative()
                owner.onSync()
            }
            owner.manual(); settle()
            assertEquals("N1-G backoff: nothing sent during the sleep", 1, calls.size)
            at(2_000)
            assertEquals("premise: the retry 2 s later", 2, calls.size)
            answer(ok(1))
            assertEquals("N1-H backoff: the absorbed manual is served by that fetch", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            advance(60_000)
            assertEquals("N1-G backoff: no trailing for absorbed demands", 2, calls.size)
        }
        rig(this) {
            seedFloor("20")
            foreground()
            at(5_000)
            repeat(3) {
                owner.authoritative()
                owner.onSync()
            }
            owner.manual(); settle()
            at(19_999)
            assertEquals("N1-G floor: nothing sent under the floor", 0, calls.size)
            at(20_000)
            assertEquals("premise: the floor passed", 1, calls.size)
            answer(ok(1))
            assertEquals("N1-H floor: the absorbed manual is served", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            advance(60_000)
            assertEquals("N1-G floor: no trailing for absorbed demands", 1, calls.size)
        }
        rig(this) {
            fence = null
            foreground()
            repeat(3) {
                owner.authoritative()
                owner.onSync()
            }
            owner.manual(); settle()
            assertEquals("N1-G access: nothing sent while waiting", 0, calls.size)
            fence = fenceFor(alice, grantA)
            accessRevisions.value += 1
            settle()
            assertEquals("premise: the hint lets the waiting Job send", 1, calls.size)
            answer(ok(1))
            assertEquals("N1-H access: the absorbed manual is served", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            advance(RECHECK * 10)
            assertEquals("N1-G access: no trailing for absorbed demands", 1, calls.size)
        }
    }

    /** N1-G: a trailing fetch starts its own six-attempt budget; the retries spent before it do not count against it. */
    @Test fun `S7N1_G04 the retry count is kept apart from the trailing fetch`() = runTest {
        rig(this) {
            foreground()
            repeat(3) {
                answer(http(503, "1"))
                advance(1_000)
            }
            assertEquals("premise: three retries, the fourth GET in flight", 4, calls.size)
            owner.authoritative(); settle()
            answer(ok(4))
            assertEquals("premise: the trailing GET", 5, calls.size)
            answerAsSent(300_000) { http(503, "1") }
            assertEquals("N1-G: the trailing chain had its own six logical attempts", 10, calls.size)
            assertEquals("N1-E: the snapshot stays through it", ready(listOf(setting(4))), owner.state.value)
        }
    }

    private class Silencer(val label: String, val drop: Rig.() -> Unit, val outcome: () -> Outcome)

    /** Ways a fetch in flight ends Silent while a new use stays acquirable: the access moved under it, or the GET or its fold saw an identity change. */
    private fun silencers(): List<Silencer> = listOf(
        Silencer("a new access epoch in the same session", { fence = fenceFor(alice, grantA, "epoch-2") }, { ok(2) }),
        Silencer("the use invalidated and recovered", { snapshot = standing(grantA, invalidations = 1) }, { ok(2) }),
        Silencer("premium lost and regranted under a new grant", {
            // Conflated on purpose, as in moves(): no reset, only the captured use tells the old from the new.
            premium.value = premiumOf(alice, PremiumAccessState.FreeConfirmed)
            premium.value = confirmed(alice)
            snapshot = standing(grantB)
            fence = fenceFor(alice, grantB)
        }, { ok(2) }),
        Silencer("the answer's own session check fails", {}, { okGuarded(identityChanged, 2) }),
        Silencer("the GET ended by an identity exception", {}, { thrown(AuthIdentityChangedException()) })
    )

    /** N1-G: an answer dropped Silent ends the chain together with the trailing fetch it owed; a later demand still sends. */
    @Test fun `S7N1_G05 an answer dropped Silent ends the chain with the trailing fetch it owed`() = runTest {
        rig(this) {
            foreground()
            owner.authoritative(); settle()
            answer(ok(1))
            assertEquals("premise: an authoritative during a fetch owes a trailing GET after its success", 2, calls.size)
        }
        for (case in silencers()) rig(this) {
            foreground()
            owner.authoritative(); settle()
            assertEquals("premise(${case.label}): one GET in flight, the authoritative joined it", 1, calls.size)
            case.drop(this)
            assertNotNull("premise(${case.label}): a new use is acquirable, so only the drop can hold a follow-up back", access.acquire())
            // Window: the dropped answer's own processing, where an owed trailing honoured would be sent at once.
            answer(case.outcome())
            assertEquals("N1-G ${case.label}: dropped, nothing shown", nothing, owner.state.value)
            assertEquals("N1-G ${case.label}: the owed trailing is not sent", 1, calls.size)
            advance(70_000)
            assertEquals("N1-G ${case.label}: nor later", 1, calls.size)
            owner.authoritative(); settle()
            assertEquals("N1-G ${case.label}: a new demand still sends at once", 2, calls.size)
        }
    }

    // ---- N1-H: manual ----

    /** N1-H: a standalone manual is one try with one outcome; a retryable failure does not make it retry. */
    @Test fun `S7N1_H01 a standalone manual is one try with exactly one outcome`() = runTest {
        for (prior in listOf(false, true)) rig(this) {
            val label = if (prior) "with a snapshot" else "without a snapshot"
            foreground()
            answer(if (prior) ok(1) else http(400))
            owner.manual(); settle()
            assertEquals("premise($label): the manual sends its own GET", 2, calls.size)
            answer(http(503))
            assertEquals("N1-H $label: Failed once", listOf(ManualRefreshOutcome.Failed), outcomes)
            advance(120_000)
            assertEquals("N1-H $label: a standalone manual does not retry", 2, calls.size)
            assertEquals("N1-H $label: and has no second outcome", listOf(ManualRefreshOutcome.Failed), outcomes)
            if (prior) {
                assertEquals("N1-E $label: the snapshot stays", ready(listOf(setting(1))), owner.state.value)
            } else {
                assertTrue("N1-H $label: no retry is pending ($phase)", phase != AlertLoadPhase.Retrying && phase != AlertLoadPhase.Loading)
            }
        }
        rig(this) {
            foreground()
            answer(http(400))
            owner.manual(); settle()
            answer(ok(5))
            assertEquals("N1-H: Succeeded once", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("N1-H: its list applies", ready(listOf(setting(5))), owner.state.value)
            advance(60_000)
            assertEquals("N1-H: one try", 2, calls.size)
        }
    }

    /** N1-H: a manual joined to an automatic fetch that fails retryably gets Failed at once; the retry goes on with no second outcome. */
    @Test fun `S7N1_H02 a retryable failure gives Failed at once and the automatic retry continues without a second outcome`() = runTest {
        rig(this) {
            foreground()
            owner.manual(); settle()
            assertEquals("premise: the manual joined the GET in flight", 1, calls.size)
            answer(http(503, "1"))
            assertEquals("N1-H: Failed at once, before the retry", listOf(ManualRefreshOutcome.Failed), outcomes)
            assertEquals("N1-E: waiting to retry", AlertLoadPhase.Retrying, phase)
            advance(1_000)
            assertEquals("N1-H: the automatic retry goes on", 2, calls.size)
            answer(ok(1))
            assertEquals("N1-H: no second outcome for the same manual", listOf(ManualRefreshOutcome.Failed), outcomes)
            assertEquals("premise: the retry applies", ready(listOf(setting(1))), owner.state.value)
            at(4_999)
            owner.manual(); settle()
            assertEquals(
                "N1-H: the served manual's failure set its 5 s cooldown; the later automatic success set none",
                listOf(ManualRefreshOutcome.Failed, ManualRefreshOutcome.CoolingDown(1)), outcomes
            )
            assertEquals("N1-H: cooling sends nothing", 2, calls.size)
        }
    }

    /** N1-H: the cooldown is 3 s after a served success and 5, 10, then 20 s after consecutive served failures; a success restarts it. */
    @Test fun `S7N1_H03 a manual cools 3 s after a success and 5 10 20 s after consecutive failures`() = runTest {
        rig(this) {
            foreground()
            answer(ok(1))
            val expected = mutableListOf<ManualRefreshOutcome>()
            fun served(offset: Long, outcome: Outcome, result: ManualRefreshOutcome) {
                at(offset)
                val before = calls.size
                owner.manual(); settle()
                assertEquals("N1-H at $offset ms: the manual is served", before + 1, calls.size)
                answer(outcome)
                expected += result
                assertEquals("N1-H at $offset ms", expected, outcomes)
            }
            fun cooling(offset: Long, remaining: Long) {
                at(offset)
                val before = calls.size
                owner.manual(); settle()
                expected += ManualRefreshOutcome.CoolingDown(remaining)
                assertEquals("N1-H at $offset ms: cooling sends nothing", before, calls.size)
                assertEquals("N1-H at $offset ms", expected, outcomes)
            }
            served(0, ok(2), ManualRefreshOutcome.Succeeded)
            cooling(0, 3_000)
            cooling(2_999, 1)
            served(3_000, http(400), ManualRefreshOutcome.Failed)
            cooling(7_999, 1)
            served(8_000, http(400), ManualRefreshOutcome.Failed)
            cooling(17_999, 1)
            served(18_000, http(400), ManualRefreshOutcome.Failed)
            cooling(37_999, 1)
            served(38_000, http(400), ManualRefreshOutcome.Failed)
            cooling(57_999, 1)
            served(58_000, ok(3), ManualRefreshOutcome.Succeeded)
            cooling(60_999, 1)
            served(61_000, http(400), ManualRefreshOutcome.Failed)
            cooling(65_999, 1)
            assertEquals("premise: the snapshot stayed through every failure", listOf(setting(3)), settings)
        }
    }

    /** N1-H: the cooldown crosses the background; a reset clears it. */
    @Test fun `S7N1_H04 a cooldown survives the background and a reset clears it`() = runTest {
        rig(this) {
            foreground()
            answer(ok(1))
            owner.manual(); settle()
            answer(ok(2))
            at(1_000); background()
            at(2_000); foreground()
            assertEquals("premise: the fresh snapshot was kept", 2, calls.size)
            at(2_999); owner.manual(); settle()
            assertEquals(
                "N1-H: the cooldown crossed the background",
                listOf(ManualRefreshOutcome.Succeeded, ManualRefreshOutcome.CoolingDown(1)), outcomes
            )
            assertEquals("N1-H: cooling sends nothing", 2, calls.size)
            owner.reset(); settle()
            owner.manual(); settle()
            assertEquals("N1-H: the reset cleared the cooldown, the manual is served", 3, calls.size)
            assertEquals("N1-H: no CoolingDown after the reset", 2, outcomes.size)
        }
    }

    /** N1-H: a manual joining an automatic fetch takes that fetch's result once and adds no trailing; an authoritative still trails. */
    @Test fun `S7N1_H05 a manual joining an automatic fetch takes its result and adds no trailing`() = runTest {
        rig(this) {
            foreground()
            owner.manual(); settle()
            assertEquals("premise: joined, no GET of its own", 1, calls.size)
            answer(ok(1))
            assertEquals("N1-H: Succeeded once from that fetch", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("N1-H: the joined manual owes no trailing", 1, calls.size)
            at(2_999); owner.manual(); settle()
            assertEquals(
                "N1-H: its 3 s cooldown runs from that fetch's end",
                listOf(ManualRefreshOutcome.Succeeded, ManualRefreshOutcome.CoolingDown(1)), outcomes
            )
            advance(60_000)
            assertEquals("N1-H: nothing more", 1, calls.size)
        }
        rig(this) {
            foreground()
            owner.manual(); owner.authoritative(); settle()
            answer(ok(1))
            assertEquals("N1-H: the manual is served by the first fetch", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("N1-G: the authoritative dirty is kept: one trailing", 2, calls.size)
            answer(ok(2))
            assertEquals("N1-H: the trailing fetch gives no second outcome", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            advance(60_000)
            assertEquals("N1-G: one trailing only", 2, calls.size)
        }
    }

    /** N1-H: an automatic demand joining a standalone manual promotes it, so a retryable failure is retried. */
    @Test fun `S7N1_H06 an automatic demand joining a standalone manual promotes it to retry`() = runTest {
        rig(this) {
            foreground()
            answer(http(400))
            owner.manual(); settle()
            assertEquals("premise: a standalone manual GET", 2, calls.size)
            owner.ensure(); settle()
            assertEquals("N1-G: the ensure joins and adds no GET", 2, calls.size)
            answer(http(503))
            assertEquals("N1-H: the manual's Failed at once", listOf(ManualRefreshOutcome.Failed), outcomes)
            advance(2_000)
            assertEquals("N1-H: promoted, the automatic retry follows", 3, calls.size)
            answer(ok(1))
            assertEquals("N1-H: and gives no second outcome", listOf(ManualRefreshOutcome.Failed), outcomes)
            assertEquals("premise: the retry applies", ready(listOf(setting(1))), owner.state.value)
            advance(60_000)
            assertEquals("N1-G: an ensure owes no trailing", 3, calls.size)
        }
        rig(this) {
            foreground()
            answer(http(400))
            owner.manual(); settle()
            owner.authoritative(); settle()
            answer(http(503))
            advance(2_000)
            assertEquals("N1-H: an authoritative joining promotes it as well", 3, calls.size)
        }
    }

    /** N1-H: Silent, background and reset end a manual with no outcome, and none of them counts as a failure or sets a cooldown. */
    @Test fun `S7N1_H07 Silent, background and reset give no outcome`() = runTest {
        rig(this) {
            foreground()
            owner.manual(); settle()
            snapshot = standing(grantA, invalidations = 1)
            answer(http(503))
            assertTrue("N1-H Silent: a dropped answer gives no outcome", outcomes.isEmpty())
            assertEquals("N1-H Silent: nothing shown, no error", nothing, owner.state.value)
            advance(60_000)
            assertEquals("N1-H Silent: and no retry", 1, calls.size)
            owner.manual(); settle()
            assertEquals("N1-H Silent: no cooldown was set: the next manual is served at once", 2, calls.size)
            val failedAt = now - t0
            answer(http(400))
            at(failedAt + 4_999); owner.manual(); settle()
            assertEquals(
                "N1-H Silent: no failure was counted: the first Failed cools 5 s",
                listOf(ManualRefreshOutcome.Failed, ManualRefreshOutcome.CoolingDown(1)), outcomes
            )
        }
        rig(this) {
            foreground()
            owner.manual(); settle()
            val hung = calls.single()
            background()
            assertTrue("premise: the background ended the GET", hung.cancelled)
            advance(60_000)
            assertTrue("N1-H background: no outcome", outcomes.isEmpty())
            foreground()
            owner.manual(); settle()
            assertEquals("premise: the next foreground sends", 2, calls.size)
            answer(ok(1))
            assertEquals("N1-H background: only the new manual has an outcome", listOf(ManualRefreshOutcome.Succeeded), outcomes)
        }
        rig(this) {
            foreground()
            owner.manual(); settle()
            val hung = calls.single()
            owner.reset(); settle()
            assertTrue("premise: the reset ended the GET", hung.cancelled)
            advance(60_000)
            assertTrue("N1-H reset: no outcome", outcomes.isEmpty())
            assertEquals("N1-H reset: a reset alone sends nothing", 1, calls.size)
        }
    }

    /** N1-H: a manual joined to a fetch or absorbed during its backoff and dropped by the background or a reset is not served by the next fetch. */
    @Test fun `S7N1_H08 a manual dropped by the background or a reset gets no outcome from the next fetch`() = runTest {
        for (way in listOf("background", "reset", "account change")) for (joined in listOf(true, false)) rig(this) {
            val label = "$way, a manual ${if (joined) "joined to the fetch" else "absorbed during the backoff"}"
            foreground()
            if (joined) {
                owner.manual(); settle()
                assertEquals("premise($label): the manual joined, no GET of its own", 1, calls.size)
            } else {
                answer(http(503))
                at(1_000); owner.manual(); settle()
                assertEquals("premise($label): absorbed, nothing sent during the backoff", 1, calls.size)
            }
            assertTrue("premise($label): not served yet", outcomes.isEmpty())
            when (way) {
                "background" -> {
                    background()
                    foreground()
                }
                "reset" -> {
                    owner.reset(); settle()
                    owner.ensure(); settle()
                }
                else -> switchTo(bob, grantB)
            }
            val who = if (way == "account change") bob else alice
            // Window: the first fetch after the drop, answered with no new manual: a manual kept across the drop is served here.
            assertEquals("premise($label): the next fetch is sent", 2, calls.size)
            answer(ok(1, uid = who.uid))
            assertEquals("premise($label): its answer applies", ready(listOf(setting(1, who.uid)), who), owner.state.value)
            assertTrue("N1-H $label: the dropped manual has no outcome", outcomes.isEmpty())
            owner.manual(); settle()
            assertEquals("N1-H $label: and set no cooldown: a new manual is served at once", 3, calls.size)
            answer(ok(2, uid = who.uid))
            assertEquals("N1-H $label: only the new manual has an outcome", listOf(ManualRefreshOutcome.Succeeded), outcomes)
        }
    }

    /** N1-H: a manual joined to a fetch that ends Silent has no outcome then, and a later fetch for another demand does not serve it. */
    @Test fun `S7N1_H09 a manual whose fetch ended Silent is not served by a later fetch`() = runTest {
        for (case in silencers()) rig(this) {
            foreground()
            owner.manual(); settle()
            assertEquals("premise(${case.label}): the manual joined the GET in flight", 1, calls.size)
            case.drop(this)
            answer(case.outcome())
            assertEquals("premise(${case.label}): dropped, nothing shown", nothing, owner.state.value)
            assertTrue("N1-H ${case.label}: no outcome at the drop", outcomes.isEmpty())
            // Window: the next fetch, made for an authoritative demand and answered: a manual kept past the Silent is served here.
            owner.authoritative(); settle()
            assertEquals("premise(${case.label}): the authoritative sends the next GET", 2, calls.size)
            answer(ok(3))
            assertEquals("premise(${case.label}): its answer applies", ready(listOf(setting(3))), owner.state.value)
            assertTrue("N1-H ${case.label}: it does not serve the Silent manual", outcomes.isEmpty())
            owner.manual(); settle()
            assertEquals("N1-H ${case.label}: no cooldown was set: a new manual is served at once", 3, calls.size)
            answer(ok(4))
            assertEquals("N1-H ${case.label}: only the new manual has an outcome", listOf(ManualRefreshOutcome.Succeeded), outcomes)
        }
    }

    /** N1-H: an automatic demand absorbed while a standalone manual's Job waits for a floor or for access promotes it to retry. */
    @Test fun `S7N1_H10 an automatic demand absorbed while a standalone manual waits promotes it to retry`() = runTest {
        rig(this) {
            foreground()
            answer(http(400))
            seedFloor("20")
            owner.manual(); settle()
            assertEquals("premise: the floor stands", 20_000L, floorAt())
            at(5_000); owner.authoritative(); settle()
            at(19_999)
            assertEquals("premise: the standalone manual waits under the floor", 1, calls.size)
            at(20_000)
            assertEquals("premise: one GET serves the manual and the absorbed authoritative", 2, calls.size)
            answer(http(503))
            assertEquals("N1-H floor: the manual's Failed at once", listOf(ManualRefreshOutcome.Failed), outcomes)
            // Window: the 2 s after the 503, where a chain still manual-only would already have ended terminal.
            assertEquals("N1-H floor: promoted, it waits to retry", AlertLoadPhase.Retrying, phase)
            at(21_999)
            assertEquals("N1-H floor: not before the backoff", 2, calls.size)
            at(22_000)
            assertEquals("N1-H floor: the retry follows", 3, calls.size)
            answer(ok(1))
            assertEquals("N1-H floor: and gives no second outcome", listOf(ManualRefreshOutcome.Failed), outcomes)
        }
        rig(this) {
            foreground()
            answer(http(400))
            fence = null
            owner.manual(); settle()
            assertEquals("premise: the standalone manual found no access and sleeps a recheck", RECHECK, sleeps.lastOrNull())
            at(300); owner.onSync(); settle()
            fence = fenceFor(alice, grantA)
            accessRevisions.value += 1
            settle()
            assertEquals("premise: the hint lets one GET serve the manual and the absorbed sync", 2, calls.size)
            answer(http(503))
            assertEquals("N1-H access: the manual's Failed at once", listOf(ManualRefreshOutcome.Failed), outcomes)
            // Window: the 2 s after the 503, as above.
            assertEquals("N1-H access: promoted, it waits to retry", AlertLoadPhase.Retrying, phase)
            at(2_299)
            assertEquals("N1-H access: not before the backoff", 2, calls.size)
            at(2_300)
            assertEquals("N1-H access: the retry follows", 3, calls.size)
        }
    }

    /** N1-H: a reset, an account change included, clears the failure escalation with the cooldown: the next served failure cools 5 s. */
    @Test fun `S7N1_H11 a reset clears the failure escalation so the next served failure cools 5 s`() = runTest {
        for (way in listOf("reset", "account change")) rig(this) {
            foreground()
            answer(ok(1))
            owner.manual(); settle()
            answer(http(400))
            at(5_000); owner.manual(); settle()
            answer(http(400))
            at(14_999); owner.manual(); settle()
            assertEquals(
                "premise($way): two served failures escalated the cooldown to 10 s",
                listOf(ManualRefreshOutcome.Failed, ManualRefreshOutcome.Failed, ManualRefreshOutcome.CoolingDown(1)), outcomes
            )
            val sent = calls.size
            when (way) {
                "reset" -> {
                    owner.reset(); settle()
                }
                else -> switchTo(bob, grantB)
            }
            owner.manual(); settle()
            assertEquals("premise($way): the cooldown went with it: one GET serves the new manual", sent + 1, calls.size)
            answer(http(400))
            val failedAt = now - t0
            // Window: the 5 s after this failure, where an escalation kept across the reset would still cool 20 s.
            at(failedAt + 4_999); owner.manual(); settle()
            assertEquals(
                "N1-H $way: the first failure since the reset cools 5 s",
                listOf(
                    ManualRefreshOutcome.Failed, ManualRefreshOutcome.Failed, ManualRefreshOutcome.CoolingDown(1),
                    ManualRefreshOutcome.Failed, ManualRefreshOutcome.CoolingDown(1)
                ),
                outcomes
            )
            at(failedAt + 5_000); owner.manual(); settle()
            assertEquals("N1-H $way: and no longer", sent + 2, calls.size)
        }
    }

    // ---- N1-I / N1-K: the floor as the owner records and waits for it ----

    /** N1-I (owner): a normally returned failure's Retry-After reaches the floor before any access, generation or fold check drops it. */
    @Test fun `S7N1_I01 a returned failure Retry-After reaches the floor before the answer is dropped`() = runTest {
        rig(this) {
            foreground()
            identity = bob
            answer(http(429, "40"))
            assertEquals("N1-I access lost: the answer is dropped", nothing, owner.state.value)
            assertEquals("N1-I access lost: its Retry-After is the floor", 40_000L, floorAt())
            advance(70_000)
            assertEquals("N1-I access lost: a dropped answer is not retried", 1, calls.size)
        }
        rig(this) {
            foreground()
            answer(http(503, "25", requireCurrent = identityChanged))
            assertEquals("N1-I fold identity exception: the answer is dropped", nothing, owner.state.value)
            assertEquals("N1-I fold identity exception: its Retry-After is the floor", 25_000L, floorAt())
            advance(70_000)
            assertEquals("N1-I fold identity exception: not retried", 1, calls.size)
        }
        rig(this) {
            ignoresCancellation = true
            foreground()
            background()
            answer(0, http(429, "30"))
            assertEquals("N1-I generation dropped: the answer is not applied", nothing, owner.state.value)
            assertEquals("N1-I generation dropped: its Retry-After is the floor", 30_000L, floorAt())
            at(1_000); foreground()
            at(29_999)
            assertEquals("N1-I generation dropped: the next foreground waits for it", 1, calls.size)
            at(30_000)
            assertEquals("N1-I generation dropped: and sends at it", 2, calls.size)
        }
    }

    private class Withdrawal(val label: String, val error: () -> Throwable, val floorMillis: Long?)

    private fun withdrawals(): List<Withdrawal> = listOf(
        Withdrawal("identity changed: exchanges first, the fallback ignored", {
            AuthIdentityChangedException(503, "90", listOf(HttpExchangeEvidence(1, 1, 429, "10")))
        }, 10_000L),
        Withdrawal("identity changed with no exchanges: the fallback pair", { AuthIdentityChangedException(503, "90") }, 90_000L),
        Withdrawal("identity changed: the longest of several exchanges", {
            AuthIdentityChangedException(429, "35", listOf(HttpExchangeEvidence(1, 1, 503, "5"), HttpExchangeEvidence(1, 2, 429, "35")))
        }, 35_000L),
        Withdrawal("identity changed: a 401 header makes none, the 503 beside it does", {
            AuthIdentityChangedException(503, "7", listOf(HttpExchangeEvidence(1, 1, 401, "50"), HttpExchangeEvidence(2, 1, 503, "7")))
        }, 7_000L),
        Withdrawal("identity changed with no evidence", { AuthIdentityChangedException() }, null),
        Withdrawal("use withheld with exchanges", { TopicUseWithheldException(listOf(HttpExchangeEvidence(1, 1, 503, "15"))) }, 15_000L),
        Withdrawal("use withheld before any answer", { TopicUseWithheldException(emptyList()) }, null)
    )

    /** N1-K (owner): a GET ended by the identity or use exception applies nothing and hands its own evidence to the floor once. */
    @Test fun `S7N1_K01 a GET ended by an identity or use exception applies nothing and records its evidence once`() = runTest {
        for (case in withdrawals()) rig(this) {
            foreground()
            owner.manual(); settle()
            answer(thrown(case.error()))
            assertEquals("N1-K ${case.label}: nothing applied", nothing, owner.state.value)
            assertTrue("N1-K ${case.label}: no outcome", outcomes.isEmpty())
            assertEquals("N1-K ${case.label}: the floor", case.floorMillis, floorAt())
            advance(0)
            assertEquals("N1-K ${case.label}: not retried", 1, calls.size)
            owner.authoritative(); settle()
            val deadline = case.floorMillis
            if (deadline == null) {
                assertEquals("N1-K ${case.label}: no floor, the next demand sends at once", 2, calls.size)
            } else {
                at(deadline - 1)
                assertEquals("N1-K ${case.label}: the next send waits for the floor", 1, calls.size)
                at(deadline)
                assertEquals("N1-K ${case.label}: and no longer: the evidence counted once", 2, calls.size)
            }
        }
    }

    /** N1-K (owner): a GET still ended by the identity or use exception after its chain was dropped hands its evidence to the floor. */
    @Test fun `S7N1_K02 the evidence of an identity or use exception is recorded after its chain was dropped`() = runTest {
        val withEvidence = withdrawals().filter { it.floorMillis != null }
        assertEquals("premise: the withdrawals that carry evidence", 5, withEvidence.size)
        for (case in withEvidence) for (way in listOf("background", "reset")) rig(this) {
            val label = "${case.label}, dropped by the $way"
            val deadline = checkNotNull(case.floorMillis)
            ignoresCancellation = true
            foreground()
            when (way) {
                "background" -> background()
                else -> {
                    owner.reset(); settle()
                }
            }
            // Window: the chain is already dropped when the transport ends its GET with the exception.
            assertEquals("premise($label): the dropped GET still awaits its answer", 1, open().size)
            answer(0, thrown(case.error()))
            assertEquals("N1-K $label: nothing applied", nothing, owner.state.value)
            assertEquals("N1-K $label: its evidence is the floor all the same", deadline, floorAt())
            at(1_000)
            when (way) {
                "background" -> foreground()
                else -> {
                    owner.ensure(); settle()
                }
            }
            at(deadline - 1)
            assertEquals("N1-K $label: the next chain waits for it", 1, calls.size)
            at(deadline)
            assertEquals("N1-K $label: and sends at it", 2, calls.size)
        }
    }

    /** N1-I (owner): a retry waits for the longer of its delay and the floor, and rechecks the floor after every wake. */
    @Test fun `S7N1_I02 a retry waits for the longer of its delay and the floor rechecked after every wake`() = runTest {
        rig(this) {
            foreground()
            answer(http(503, "7"))
            assertEquals("N1-I: the header is the floor", 7_000L, floorAt())
            at(6_999)
            assertEquals("N1-I: not before it", 1, calls.size)
            at(7_000)
            assertEquals("N1-I: the header is waited once, not as delay plus floor", 2, calls.size)
        }
        rig(this) {
            foreground()
            answer(http(503))
            at(500); seedFloor("9")
            at(2_000)
            assertEquals("N1-I: the woken retry rechecks the floor raised during its sleep", 1, calls.size)
            at(9_499)
            assertEquals("N1-I: still under it", 1, calls.size)
            at(9_500)
            assertEquals("N1-I: and sends at it", 2, calls.size)
        }
    }

    /** N1-I/N1-K (owner): the floor outlives the background, a reset, an account change, a manual's end and an exhausted chain. */
    @Test fun `S7N1_I03 the floor survives background, reset, account change, a terminal manual and exhaustion`() = runTest {
        for (way in listOf("background", "reset", "account change")) rig(this) {
            foreground()
            answer(http(429, "30"))
            at(1_000)
            when (way) {
                "background" -> {
                    background()
                    at(2_000); foreground()
                }
                "reset" -> {
                    owner.reset(); settle()
                    owner.ensure(); settle()
                }
                else -> switchTo(bob, grantB)
            }
            at(29_999)
            assertEquals("N1-I $way: the next send waits for the floor", 1, calls.size)
            at(30_000)
            assertEquals("N1-I $way: and goes at it", 2, calls.size)
            if (way == "account change") assertEquals("N1-K: the new account did not pass under the floor", bob, calls.last().identity)
        }
        rig(this) {
            foreground()
            answer(ok(1))
            owner.manual(); settle()
            answer(http(429, "45"))
            assertEquals("premise: the manual ended Failed", listOf(ManualRefreshOutcome.Failed), outcomes)
            at(1_000); owner.authoritative(); settle()
            at(44_999)
            assertEquals("N1-I terminal manual: the floor stays after it", 2, calls.size)
            at(45_000)
            assertEquals("N1-I terminal manual: and is waited for", 3, calls.size)
        }
        rig(this) {
            foreground()
            repeat(5) {
                answer(http(503, "1"))
                advance(1_000)
            }
            answer(http(503, "100"))
            assertEquals("premise: six attempts, exhausted", 6, calls.size)
            assertEquals("premise: terminal", terminal(503), phase)
            owner.authoritative(); settle()
            at(104_999)
            assertEquals("N1-I exhausted: the last floor stays", 6, calls.size)
            at(105_000)
            assertEquals("N1-I exhausted: and is waited for", 7, calls.size)
        }
    }

    /** N1-I (owner): the floor is waited for before every send: the first, a trailing, after an access wait, and when raised meanwhile. */
    @Test fun `S7N1_I04 the floor is waited for before every send`() = runTest {
        rig(this) {
            seedFloor("12")
            foreground()
            at(11_999)
            assertEquals("N1-I first send: under the floor", 0, calls.size)
            at(12_000)
            assertEquals("N1-I first send: at the floor", 1, calls.size)
        }
        rig(this) {
            foreground()
            owner.authoritative(); settle()
            seedFloor("20")
            answer(ok(1))
            at(19_999)
            assertEquals("N1-I trailing: under the floor", 1, calls.size)
            at(20_000)
            assertEquals("N1-I trailing: at the floor", 2, calls.size)
        }
        rig(this) {
            fence = null
            foreground()
            seedFloor("15")
            at(500)
            fence = fenceFor(alice, grantA)
            accessRevisions.value += 1
            settle()
            at(14_999)
            assertEquals("N1-I access wait: the woken Job checks the floor before sending", 0, calls.size)
            at(15_000)
            assertEquals("N1-I access wait: at the floor", 1, calls.size)
        }
        rig(this) {
            seedFloor("10")
            foreground()
            at(5_000); seedFloor("20")
            at(10_000)
            assertEquals("N1-I raised: the first deadline is not the last word", 0, calls.size)
            at(24_999)
            assertEquals("N1-I raised: under the raised floor", 0, calls.size)
            at(25_000)
            assertEquals("N1-I raised: at it", 1, calls.size)
        }
    }

    // ---- N1-J: the Retry-After grammar on the decision path ----

    /** N1-J (owner): a header outside the agreed grammar is no header for the retry decision as well: the backoff delay and no floor. */
    @Test fun `S7N1_J01 a 503 whose Retry-After is outside the grammar retries after the backoff as with no header`() = runTest {
        rig(this) {
            foreground()
            answer(http(503, "0.5"))
            assertEquals("premise: an in-grammar header is the floor", 500L, floorAt())
            at(499)
            assertEquals("premise: nothing before it", 1, calls.size)
            at(500)
            assertEquals("premise: the decision reads an in-grammar header, not the 2 s backoff", 2, calls.size)
        }
        for (header in listOf("1e3", "+5", "-5", ".5", "5.", "5d", "0x1p3", "Infinity", "Wed, 21 Oct 2015 07:28:00 GMT")) rig(this) {
            foreground()
            answer(http(503, header))
            assertEquals("premise('$header'): cold and waiting to retry", AlertLoadPhase.Retrying, phase)
            assertNull("N1-J '$header': out of the grammar, no floor", floorAt())
            // Window: (0, 2 000] ms after the 503, where a header read as a delay sends early (".5") or not yet (the others).
            at(1_999)
            assertEquals("N1-J '$header': nothing before the backoff", 1, calls.size)
            at(2_000)
            assertEquals("N1-J '$header': the retry goes at the 2 s backoff, as with no header", 2, calls.size)
        }
    }

    // ---- N1-L: logical attempts by kind ----

    private class Attempts(val label: String, val outcome: () -> Outcome, val attempts: Int, val ended: AlertLoadPhase, val noFloor: Boolean = false)

    private fun attemptCases(): List<Attempts> {
        val network = terminal(AlertLoadFailure.Failed(AlertRepositoryFailureKind.NETWORK, null))
        val unexpected = terminal(AlertLoadFailure.Failed(AlertRepositoryFailureKind.UNEXPECTED, null))
        val authRequired = terminal(AlertLoadFailure.AuthRequired)
        return listOf(
            Attempts("503 without Retry-After", { http(503) }, 6, terminal(503), noFloor = true),
            Attempts("429 without Retry-After", { http(429) }, 6, terminal(429), noFloor = true),
            Attempts("408", { http(408) }, 6, terminal(408), noFloor = true),
            Attempts("network", { failed(AlertRepositoryFailureKind.NETWORK, "네트워크 오류") }, 6, network),
            Attempts("an IOException from the GET itself", { thrown(IOException("capture")) }, 6, network),
            Attempts("credential unavailable", { failed(AlertRepositoryFailureKind.AUTH_UNAVAILABLE, "인증 오류") }, 3, authRequired),
            Attempts("401 with Retry-After", { http(401, "60") }, 1, authRequired, noFloor = true),
            Attempts("403 with Retry-After", { http(403, "60") }, 1, terminal(AlertLoadFailure.Forbidden), noFloor = true),
            Attempts("400", { http(400) }, 1, terminal(400)),
            Attempts("404", { http(404) }, 1, terminal(404)),
            Attempts("decode", { failed(AlertRepositoryFailureKind.DECODE, "서버 응답 형식 오류") }, 1,
                terminal(AlertLoadFailure.Failed(AlertRepositoryFailureKind.DECODE, null))),
            Attempts("unexpected", { failed(AlertRepositoryFailureKind.UNEXPECTED, "서버 오류") }, 1, unexpected),
            Attempts("network under the credential message", { failed(AlertRepositoryFailureKind.NETWORK, "인증 오류") }, 6, network),
            Attempts("unexpected under the network message", { failed(AlertRepositoryFailureKind.UNEXPECTED, "네트워크 오류") }, 1, unexpected),
            Attempts("credential under the network message", { failed(AlertRepositoryFailureKind.AUTH_UNAVAILABLE, "네트워크 오류") }, 3, authRequired)
        )
    }

    /** N1-L (owner): six logical attempts for a retryable failure, three for a missing credential, one otherwise; by kind, never by message. */
    @Test fun `S7N1_L01 logical attempts follow the repository kind, six for retryable and three for a missing credential`() = runTest {
        for (case in attemptCases()) rig(this) {
            foreground()
            answerAsSent(200_000) { case.outcome() }
            assertEquals("N1-L ${case.label}: logical attempts", case.attempts, calls.size)
            assertEquals("N1-L ${case.label}: how it ended", BankAlertSettingsState(case.ended, null, null), owner.state.value)
            if (case.noFloor) assertNull("N1-L ${case.label}: no floor", floorAt())
        }
    }

    // ---- N1-T: no fence ----

    /** N1-T: without a fence no GET goes; the waiting Job rechecks once per accessRecheckMillis through the injected sleep. */
    @Test fun `S7N1_T01 without a fence nothing is sent and the access is rechecked every accessRecheckMillis`() = runTest {
        rig(this, recheck = 700) {
            fence = null
            foreground()
            assertEquals("N1-T: no fence, no GET", 0, calls.size)
            assertEquals("N1-T: waiting shows nothing and no error", nothing, owner.state.value)
            advance(700L * 5)
            assertEquals("N1-T: five periods later still none", 0, calls.size)
            assertEquals("N1-T: one injected sleep of accessRecheckMillis per recheck", List(6) { 700L }, sleeps)
            at(3_850)
            fence = fenceFor(alice, grantA)
            at(4_199)
            assertEquals("N1-T: a fence published without a signal waits for the next recheck", 0, calls.size)
            at(4_200)
            assertEquals("N1-T: and that recheck finds it", 1, calls.size)
            answer(ok(1))
            val rechecks = sleeps.size
            advance(7_000)
            assertEquals("N1-T: with no demand left, no recheck runs", rechecks, sleeps.size)
        }
    }

    /** N1-T: an access hint (an access revision, the graph host) wakes the waiting Job at once; a hint under refusal changes nothing. */
    @Test fun `S7N1_T02 an access hint wakes the waiting Job before the recheck`() = runTest {
        for (hint in listOf("access revision", "graph host")) rig(this) {
            fence = null
            foreground()
            at(300)
            fence = fenceFor(alice, grantA)
            if (hint == "access revision") accessRevisions.value += 1 else graphHostReady.value = true
            settle()
            assertEquals("N1-T $hint: the hint woke it at once", 1, calls.size)
            answer(ok(1))
            assertEquals("premise($hint): applied", ready(listOf(setting(1))), owner.state.value)
        }
        rig(this) {
            fence = null
            foreground()
            at(300)
            accessRevisions.value += 1
            settle()
            assertEquals("N1-T: a hint while still refused sends nothing", 0, calls.size)
            at(500)
            fence = fenceFor(alice, grantA)
            advance(RECHECK)
            assertEquals("N1-T: the wait goes on and a recheck finds the fence", 1, calls.size)
        }
    }

    /** N1-T with the premium signal row: premium gained while the Job waits for access is one of the three hints, and wakes it. */
    @Test fun `S7N1_T03 premium gained while the Job waits for access wakes it`() = runTest {
        rig(this, premiumAt = premiumOf(alice, PremiumAccessState.Pending(false))) {
            foreground()
            at(300)
            assertEquals("premise: premium resolving, the Job waits", 0, calls.size)
            premium.value = confirmed(alice)
            settle()
            assertEquals("N1-T: the premium signal is a hint: the GET goes at once", 1, calls.size)
            at(2_000)
            assertEquals("N1-G: the woken wait sends no second GET", 1, calls.size)
        }
    }

    /** N1-T: background and reset drop the access wait with its demands; no recheck runs after; the snapshot stays Ready while waiting. */
    @Test fun `S7N1_T04 background or reset drops the access wait and its demands`() = runTest {
        rig(this) {
            fence = null
            foreground()
            owner.authoritative(); owner.manual(); settle()
            advance(RECHECK * 2)
            assertEquals("premise: the Job was rechecking while foreground", 3, sleeps.size)
            background()
            val rechecks = sleeps.size
            fence = fenceFor(alice, grantA)
            accessRevisions.value += 1
            graphHostReady.value = true
            advance(RECHECK * 10)
            assertEquals("N1-T background: the wait was dropped, no GET", 0, calls.size)
            assertEquals("N1-T background: no recheck runs in the background", rechecks, sleeps.size)
            assertTrue("N1-T background: no outcome", outcomes.isEmpty())
            foreground()
            assertEquals("premise: the next foreground sends", 1, calls.size)
        }
        rig(this) {
            fence = null
            foreground()
            owner.manual(); settle()
            advance(RECHECK * 2)
            assertEquals("premise: the Job was rechecking while foreground", 3, sleeps.size)
            owner.reset(); settle()
            val rechecks = sleeps.size
            fence = fenceFor(alice, grantA)
            accessRevisions.value += 1
            graphHostReady.value = true
            advance(RECHECK * 10)
            assertEquals("N1-T reset: the wait was dropped, no GET", 0, calls.size)
            assertEquals("N1-T reset: no recheck runs after it", rechecks, sleeps.size)
            assertTrue("N1-T reset: no outcome", outcomes.isEmpty())
            owner.ensure(); settle()
            assertEquals("premise: a new demand sends", 1, calls.size)
        }
        rig(this) {
            foreground()
            answer(ok(1))
            fence = null
            at(1_000); owner.authoritative(); settle()
            advance(RECHECK * 3)
            assertEquals("N1-T: waiting keeps the snapshot Ready", ready(listOf(setting(1))), owner.state.value)
            assertEquals("N1-T: and sends nothing", 1, calls.size)
        }
    }

    /** N1-T: a graph given up means no GET and no outcome whatever is demanded; the waiting spends no retry or manual failure. */
    @Test fun `S7N1_T05 a graph given up sends no GET or outcome and spends no retry budget`() = runTest {
        rig(this) {
            fence = null
            foreground()
            repeat(30) {
                owner.ensure(); owner.authoritative(); owner.manual(); owner.onSync()
                accessRevisions.value += 1
                graphHostReady.value = it % 2 == 0
                advance(RECHECK)
            }
            background()
            advance(RECHECK * 5)
            foreground()
            owner.reset(); settle()
            owner.ensure(); settle()
            advance(RECHECK * 30)
            assertEquals("N1-T: graph given up, zero GETs", 0, calls.size)
            assertTrue("N1-T: and no outcome, the manual was never served", outcomes.isEmpty())
            assertEquals("N1-T: waiting shows nothing and no error", nothing, owner.state.value)
            owner.manual(); settle()
            fence = fenceFor(alice, grantA)
            accessRevisions.value += 1
            settle()
            assertEquals("premise: a late graph lets the waiting chain send", 1, calls.size)
            val first = now - t0
            answer(http(503))
            assertEquals("premise: the absorbed manual is served", listOf(ManualRefreshOutcome.Failed), outcomes)
            at(first + 2_000)
            answer(http(503))
            at(first + 4_999); owner.manual(); settle()
            assertEquals(
                "N1-T: the waiting counted no manual failure: the first Failed cools 5 s",
                listOf(ManualRefreshOutcome.Failed, ManualRefreshOutcome.CoolingDown(1)), outcomes
            )
            answerAsSent(200_000) { http(503) }
            assertEquals("N1-T: and spent no retry: the full six logical attempts", 6, calls.size)
        }
    }

    /** N1-T: a cold chain that loses access in its backoff, or a demand after a cold terminal error, waits showing Idle, not its old phase. */
    @Test fun `S7N1_T06 a cold chain waiting for access shows Idle, not the Retrying or terminal phase it came from`() = runTest {
        rig(this) {
            foreground()
            answer(http(503))
            assertEquals("premise: cold and waiting to retry", BankAlertSettingsState(AlertLoadPhase.Retrying, null, null), owner.state.value)
            fence = null
            at(2_000)
            // Window: from the backoff's end at 2 000 ms until the access returns, the Job waits and sends nothing.
            assertEquals("premise: the woken retry found no access and sleeps a recheck", RECHECK, sleeps.lastOrNull())
            assertEquals("N1-T backoff: no GET while waiting", 1, calls.size)
            assertEquals("N1-T backoff: waiting with no snapshot shows Idle, not Retrying", nothing, owner.state.value)
            advance(RECHECK * 3)
            assertEquals("N1-T backoff: and through its rechecks", nothing, owner.state.value)
            fence = fenceFor(alice, grantA)
            accessRevisions.value += 1
            settle()
            assertEquals("premise: the hint lets the waiting retry go", 2, calls.size)
            answer(ok(1))
            assertEquals("premise: applied", ready(listOf(setting(1))), owner.state.value)
        }
        rig(this) {
            foreground()
            answer(http(400))
            assertEquals("premise: a cold terminal failure", BankAlertSettingsState(terminal(400), null, null), owner.state.value)
            fence = null
            owner.authoritative(); settle()
            // Window: the new demand's Job waits for access from its first check, before any GET.
            assertEquals("premise: the new demand's Job found no access and sleeps a recheck", RECHECK, sleeps.lastOrNull())
            assertEquals("N1-T terminal: no GET while waiting", 1, calls.size)
            assertEquals("N1-T terminal: waiting with no snapshot shows Idle, not the old terminal error", nothing, owner.state.value)
            fence = fenceFor(alice, grantA)
            accessRevisions.value += 1
            settle()
            assertEquals("premise: the hint lets it send", 2, calls.size)
        }
    }

    // ---- premium signals ----

    /** Premium signal: a changed identity (another UID, a new session, a sign-out) resets the account state; the floor stays. */
    @Test fun `S7N1_PS01 an identity change in the premium state resets the account state and keeps the floor`() = runTest {
        for ((label, next) in listOf(
            "another UID" to confirmed(bob),
            "another UID without premium" to premiumOf(bob, PremiumAccessState.FreeConfirmed),
            "the same UID in a new session" to confirmed(alice2),
            "signed out" to OwnedPremiumAccess()
        )) rig(this) {
            foreground()
            answer(ok(1))
            seedFloor("100")
            assertEquals("premise($label): the snapshot", ready(listOf(setting(1))), owner.state.value)
            premium.value = next
            settle()
            assertNull("PS $label: the snapshot is gone", settings)
            assertNull("PS $label: and the identity it belonged to", owner.state.value.identity)
            assertEquals("PS $label: the floor outlives the account", 100_000L, floorAt())
        }
    }

    /** Premium signal: a new session of the same UID is reset and loads; the reset cleared the old session's cooldown. */
    @Test fun `S7N1_PS02 a new session resets, clears the old cooldown and loads`() = runTest {
        rig(this) {
            foreground()
            answer(ok(1))
            owner.manual(); settle()
            answer(ok(2))
            assertEquals("premise: cooling 3 s after the manual success", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            at(1_000)
            switchTo(alice2, grantB)
            assertEquals("PS: the new session in the foreground loads at once", 3, calls.size)
            assertEquals("PS: for the new session", alice2, calls.last().identity)
            assertNull("PS: the old snapshot is gone", settings)
            owner.manual(); settle()
            answer(ok(3))
            assertEquals(
                "PS: the reset cleared the cooldown: the manual joined instead of cooling",
                listOf(ManualRefreshOutcome.Succeeded, ManualRefreshOutcome.Succeeded), outcomes
            )
            assertEquals("PS: the new session's list", ready(listOf(setting(3)), alice2), owner.state.value)
        }
    }

    /** Premium signal: a premium loss resets; the answer then in flight is never applied, even when its transport answers late. */
    @Test fun `S7N1_PS03 a premium loss resets and the answer then in flight is never applied`() = runTest {
        for (lost in listOf(
            PremiumAccessState.FreeConfirmed, PremiumAccessState.Rejected, PremiumAccessState.Pending(false), PremiumAccessState.NoGrant
        )) for (cooperative in listOf(true, false)) rig(this) {
            val label = "$lost, ${if (cooperative) "cooperative" else "late-answering"} transport"
            ignoresCancellation = !cooperative
            foreground()
            answer(ok(1))
            at(1_000); owner.authoritative(); settle()
            assertEquals("premise($label): the second GET in flight", 2, calls.size)
            premium.value = premiumOf(alice, lost)
            settle()
            assertEquals("PS $label: reset", nothing, owner.state.value)
            calls[1].outcome.complete(ok(2))
            settle()
            assertEquals("PS $label: the answer in flight is not applied", nothing, owner.state.value)
            advance(RECHECK * 5)
            assertEquals("PS $label: nothing is loaded without premium", 2, calls.size)
        }
    }

    /** Premium signal: premium regained in the foreground ensures at once, exactly once; regained in the background waits for it. */
    @Test fun `S7N1_PS04 premium regained in the foreground ensures at once and in the background waits for the foreground`() = runTest {
        rig(this) {
            foreground()
            answer(ok(1))
            premium.value = premiumOf(alice, PremiumAccessState.FreeConfirmed)
            settle()
            assertEquals("premise: the loss reset", nothing, owner.state.value)
            advance(5_000)
            assertEquals("premise: nothing loads without premium", 1, calls.size)
            premium.value = confirmed(alice)
            settle()
            assertEquals("PS: false to true in the foreground loads at once", 2, calls.size)
            answer(ok(2))
            advance(60_000)
            assertEquals("N1-O: one signal, one GET: one subscription per owner", 2, calls.size)
            assertEquals("premise: applied", ready(listOf(setting(2))), owner.state.value)
        }
        rig(this) {
            foreground()
            answer(ok(1))
            premium.value = premiumOf(alice, PremiumAccessState.FreeConfirmed)
            settle()
            background()
            premium.value = confirmed(alice)
            settle()
            advance(60_000)
            assertEquals("PS: regained in the background, nothing sent", 1, calls.size)
            foreground()
            assertEquals("PS: the foreground loads", 2, calls.size)
        }
    }

    /** Premium signal: from one premium account to another, B loads for B, and A's answer, early or late, is never applied. */
    @Test fun `S7N1_PS05 a change from one premium account to another loads the new one and never applies the old answer`() = runTest {
        for (oldAnswerLate in listOf(false, true)) rig(this) {
            val label = if (oldAnswerLate) "A answers after B" else "A answers during B's fetch"
            ignoresCancellation = true
            foreground()
            answer(ok(1))
            at(1_000); owner.authoritative(); settle()
            assertEquals("premise($label): A's second GET in flight", 2, calls.size)
            switchTo(bob, grantB)
            assertEquals("PS $label: B premium in the foreground loads at once", 3, calls.size)
            assertEquals("PS $label: for B", bob, calls[2].identity)
            assertNull("PS $label: A's snapshot went with the reset", settings)
            if (!oldAnswerLate) {
                answer(1, ok(2))
                assertNull("PS $label: A's answer is not applied", settings)
                assertNull("PS $label: nor A's identity", owner.state.value.identity)
            }
            answer(2, ok(7, uid = "user-b"))
            assertEquals("PS $label: B's list for B", ready(listOf(setting(7, "user-b")), bob), owner.state.value)
            if (oldAnswerLate) {
                answer(1, ok(2))
                assertEquals("PS $label: A's answer is not applied", ready(listOf(setting(7, "user-b")), bob), owner.state.value)
            }
        }
    }

    private companion object {
        const val RECHECK = 1_000L
        const val STEP = 250L
        val SETTINGS = AuthenticatedEndpoint.NOTIFICATION_SETTINGS
    }
}
