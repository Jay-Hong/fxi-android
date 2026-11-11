package com.jay.fxi.data.alerts

import com.jay.fxi.data.auth.AuthIdentityChangedException
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
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.repository.AlertRateLimitResponseEvidence
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.mapAlertHttpFailure
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 E5a1 contract (R4c/S7/s7e5 API r2): the bank owner's re-readable manual-refresh state (API r2 §1 rules 1-4,
 * §5 row 1). requested is a manual demand held by the active chain and sending that demand attached to the chain's fetch, so
 * sending implies requested; an automatic-only fetch, and the retry or trailing fetch after a manual result, are never sending; a
 * manual joining a fetch, or re-tapped while its chain waits for the floor or for access, adds no GET; Silent, an admission drop,
 * the background, a reset and an account change release the demand, and a retired chain whose transport answers late publishes
 * nothing. cooldownUntilMillis is the owner's own 3 s / 5-10-20 s deadline in nowMillis units and coolingDown is true until
 * exactly that deadline, cleared by a timer that the background keeps, a reset cancels, a new deadline replaces and that never
 * writes once replaced, even when it wakes late or early; a manual at the deadline is taken even before that timer ran. None of it
 * changes the outcome stream, the settings state, the GETs, the floor or the freshness.
 * The rig is the N1 owner rig rebuilt here (the scripted GET, the production access, a real floor and policy, jitter 0), with
 * nowMillis, the owner scope and the injected sleep on one TestCoroutineScheduler; an eager collector records every value the
 * state publishes, and every rig ends by checking that no recorded value had sending without requested or cooled with no deadline.
 * The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BankAlertManualRefreshStateContractTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val bob = AuthIdentityFence("user-b", 1)
    private val grantA = TopicGrantToken(11)
    private val grantB = TopicGrantToken(12)
    private val stamp = Instant.parse("2026-11-11T01:00:00Z")

    private val nothing = BankAlertSettingsState(AlertLoadPhase.Idle, null, null)

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
    private fun http(status: Int, retryAfter: String? = null): Outcome {
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
                {}, Result.failure(mapAlertHttpFailure(failure)), AlertRateLimitResponseEvidence(status, retryAfter)
            )
        )
    }

    private fun thrown(error: Throwable): Outcome = Outcome.Throw(error)

    private val identityChanged: () -> Unit = { throw AuthIdentityChangedException() }

    // ---- the rig ----

    /** One injected sleep, as offsets from the rig's start: when it began, what it asked for and how it ended. */
    private class SleepRecord(val startedAt: Long, val millis: Long) {
        var wokeAt: Long? = null
        var cancelledAt: Long? = null
        val open: Boolean get() = wokeAt == null && cancelledAt == null
    }

    /** A collector started after the fact: what a re-subscribing screen reads. */
    private class Subscriber {
        val refreshes = mutableListOf<ManualRefreshState>()
        val outcomes = mutableListOf<ManualRefreshOutcome>()
    }

    /**
     * Every access input standing for [alice], the production access, a real floor and policy on the test clock, jitter 0, the
     * owner on a StandardTestDispatcher (or an eager one) and eager collectors of the outcomes, the manual state and the settings
     * state. The injected sleep is recorded and can ignore its cancellation (a non-cooperative timer that wakes late) or return
     * once early. Times are offsets from the rig's own start [t0].
     */
    private inner class Rig(val test: TestScope, eager: Boolean) {
        val t0: Long = test.testScheduler.currentTime
        var identity: AuthIdentityFence? = alice
        var fence: TopicSessionFence? = fenceFor(alice, grantA)
        var snapshot: TopicAccessSnapshot = standing(grantA)
        var release = true
        val deletions = DeletionAdmissionStore()
        val premium = MutableStateFlow(confirmed(alice))
        val accessRevisions = MutableStateFlow(0L)
        val graphHostReady = MutableStateFlow(false)
        val access = AlertSettingsAccess(
            { identity }, { fence }, SnapshotTopicUseAuthority { snapshot }, { premium.value }, { release },
            GraphProtectedAdmission({ identity }, deletions)
        )
        val floor = AlertRateLimitFloor({ test.testScheduler.currentTime })
        val calls = mutableListOf<Call>()
        val sleeps = mutableListOf<SleepRecord>()
        var ignoresCancellation = false
        /** While true, every injected sleep runs to its own end even after its Job was cancelled. */
        var sleepIgnoresCancellation = false
        /** The next injected sleep returns this much early, once. */
        var earlyWakeOnce = 0L

        val now: Long get() = test.testScheduler.currentTime

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
        private suspend fun recordedSleep(millis: Long) {
            val record = SleepRecord(now - t0, millis).also { sleeps += it }
            val actual = (millis - earlyWakeOnce).coerceAtLeast(0L)
            earlyWakeOnce = 0L
            try {
                if (sleepIgnoresCancellation) {
                    withContext(NonCancellable) {
                        delay(actual)
                        record.wokeAt = now - t0
                    }
                } else {
                    delay(actual)
                    record.wokeAt = now - t0
                }
            } catch (e: CancellationException) {
                record.cancelledAt = now - t0
                throw e
            }
        }
        private val sleep: suspend (Long) -> Unit = { recordedSleep(it) }
        private val job = SupervisorJob()
        val owner = BankAlertSettingsOwner(
            read, access, floor, AlertRetryPolicy(), { test.testScheduler.currentTime }, { 0.0 },
            CoroutineScope(job + if (eager) UnconfinedTestDispatcher(test.testScheduler) else StandardTestDispatcher(test.testScheduler)),
            sleep, premium, accessRevisions, graphHostReady, RECHECK
        )
        val outcomes = mutableListOf<ManualRefreshOutcome>()
        /** Every value [BankAlertSettingsOwner.manualRefresh] published, in order. */
        val refreshes = mutableListOf<ManualRefreshState>()
        /** Every value [BankAlertSettingsOwner.state] published, in order. */
        val states = mutableListOf<BankAlertSettingsState>()

        init {
            val collectors = CoroutineScope(job + UnconfinedTestDispatcher(test.testScheduler))
            collectors.launch { owner.manualOutcomes.collect { outcomes += it } }
            collectors.launch { owner.manualRefresh.collect { refreshes += it } }
            collectors.launch { owner.state.collect { states += it } }
            settle()
        }

        val refresh: ManualRefreshState get() = owner.manualRefresh.value
        val settings: List<AlertSetting>? get() = owner.state.value.settings
        val phase: AlertLoadPhase get() = owner.state.value.phase

        /** Released from any manual demand and cooling until [deadlineOffset] after [t0]. */
        fun cooling(deadlineOffset: Long) =
            ManualRefreshState(requested = false, sending = false, coolingDown = true, cooldownUntilMillis = t0 + deadlineOffset)

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
        /** Advances to [offset] after [t0] without running what is due exactly then. */
        fun arriveAt(offset: Long) {
            val target = t0 + offset
            check(target > now) { "time only runs forward: asked for $offset, at ${now - t0}" }
            test.advanceTimeBy(target - now)
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
        fun subscribeLate(): Subscriber {
            val late = Subscriber()
            val scope = CoroutineScope(job + UnconfinedTestDispatcher(test.testScheduler))
            scope.launch { owner.manualRefresh.collect { late.refreshes += it } }
            scope.launch { owner.manualOutcomes.collect { late.outcomes += it } }
            return late
        }
        /** Rules 1 and 2 on every value ever published, not only the ones a test happened to read. */
        fun assertPublishedInvariants() {
            refreshes.forEachIndexed { i, v ->
                assertTrue("rule 1: published value $i ($v) is sending only while requested", !v.sending || v.requested)
                assertTrue("rule 2: published value $i ($v) cools only with a deadline", !v.coolingDown || v.cooldownUntilMillis != null)
            }
        }
        fun close() = job.cancel()
    }

    private fun rig(test: TestScope, eager: Boolean = false, body: Rig.() -> Unit) {
        val r = Rig(test, eager)
        try {
            r.body()
            r.assertPublishedInvariants()
        } finally {
            r.close()
        }
    }

    // ---- rule 1: requested and sending ----

    /** Rule 1 / §5 row 1: the state starts idle; a subscriber arriving at any time reads the current value at once, never an outcome. */
    @Test fun `S7E5A1_MR01 the state starts idle and a late subscriber reads the current value, never an old outcome`() = runTest {
        rig(this) {
            assertEquals("MR01: the initial value", IDLE, refresh)
            assertEquals("MR01: the first collector reads the initial value", listOf(IDLE), refreshes)
            foreground()
            answer(ok(1))
            assertEquals("premise: an automatic load leaves it idle", IDLE, refresh)
            owner.manual(); settle()
            assertEquals("premise: a standalone manual's own GET", 2, calls.size)
            assertEquals("premise: requested and sending", JOINED, refresh)
            assertEquals("MR01: a subscriber arriving during the fetch reads it", listOf(JOINED), subscribeLate().refreshes)
            answer(ok(2))
            assertEquals("premise: served, cooling 3 s", cooling(3_000), refresh)
            assertEquals("premise: its outcome was emitted once", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            val late = subscribeLate()
            assertEquals("MR01: a subscriber arriving after the result reads the current value", listOf(cooling(3_000)), late.refreshes)
            assertTrue("MR01: and no outcome, which is not replayed", late.outcomes.isEmpty())
            assertEquals("MR01: re-reading gives the same value", cooling(3_000), owner.manualRefresh.value)
            at(3_000)
            val expired = refresh
            assertFalse("premise: the cooldown ended", expired.coolingDown)
            assertEquals("MR01: after the deadline, the value then", listOf(expired), subscribeLate().refreshes)
            owner.reset(); settle()
            assertEquals("MR01: after a reset, the idle value", listOf(IDLE), subscribeLate().refreshes)
        }
    }

    /** Rule 1: across joined, standalone, retried, trailing, backgrounded and reset fetches every published value sends only while requested. */
    @Test fun `S7E5A1_MR02 every published value is sending only while requested`() = runTest {
        rig(this) {
            foreground()
            owner.manual(); settle()
            answer(http(503))
            at(2_000)
            answer(ok(1))
            at(5_000)
            owner.manual(); settle()
            owner.authoritative(); settle()
            answer(ok(2))
            answer(ok(3))
            at(8_000)
            owner.manual(); settle()
            background()
            foreground()
            owner.manual(); settle()
            owner.reset(); settle()
            assertEquals("premise: the tour sent six GETs", 6, calls.size)
            assertTrue("premise: sending was published, so the check is not empty", refreshes.any { it.sending })
            refreshes.forEach { assertTrue("MR02: sending implies requested ($it)", !it.sending || it.requested) }
        }
    }

    /** Rule 1: a first fetch, its retry, a sync with its trailing fetch and an authoritative foreground, none manual, publish nothing. */
    @Test fun `S7E5A1_MR03 an automatic-only GET is never requested or sending`() = runTest {
        rig(this) {
            foreground()
            assertEquals("premise: a cold automatic GET in flight", 1, open().size)
            assertEquals("premise: loading", AlertLoadPhase.Loading, phase)
            assertEquals("MR03 first fetch: neither requested nor sending", IDLE, refresh)
            answer(http(503))
            at(2_000)
            assertEquals("premise: the automatic retry in flight", 2, calls.size)
            assertEquals("premise: one GET open", 1, open().size)
            assertEquals("premise: retrying", AlertLoadPhase.Retrying, phase)
            assertEquals("MR03 retry: neither", IDLE, refresh)
            answer(ok(1))
            at(3_000)
            owner.authoritative(); settle()
            repeat(3) {
                owner.ensure()
                owner.onSync()
            }
            settle()
            assertEquals("premise: an authoritative GET in flight", 3, calls.size)
            assertEquals("MR03 authoritative: neither", IDLE, refresh)
            answer(ok(2))
            assertEquals("premise: the owed trailing GET in flight", 4, calls.size)
            assertEquals("premise: one GET open", 1, open().size)
            assertEquals("MR03 trailing: neither", IDLE, refresh)
            answer(ok(3))
            background()
            foreground(authoritative = true)
            assertEquals("premise: an authoritative foreground GET in flight", 5, calls.size)
            assertEquals("MR03 foreground: neither", IDLE, refresh)
            answer(ok(4))
            assertEquals("MR03: nothing but the initial value was ever published", listOf(IDLE), refreshes)
            assertTrue("MR03: and no outcome", outcomes.isEmpty())
        }
    }

    /** Rule 1: a manual joining the GET in flight (a first fetch or a retry) is sending at once, adds no GET, and its result releases it. */
    @Test fun `S7E5A1_MR04 a manual joining a GET in flight is sending at once and adds no GET`() = runTest {
        rig(this) {
            foreground()
            assertEquals("premise: an automatic GET in flight", 1, open().size)
            assertEquals("premise: not requested yet", IDLE, refresh)
            owner.manual(); settle()
            assertEquals("MR04: the joined manual is requested and sending", JOINED, refresh)
            assertEquals("MR04: with no GET of its own", 1, calls.size)
            repeat(3) { owner.manual() }
            settle()
            assertEquals("MR04: a re-tap joins as well", JOINED, refresh)
            assertEquals("MR04: and adds no GET", 1, calls.size)
            assertTrue("MR04: no outcome before the result", outcomes.isEmpty())
            val fetchEndMark = refreshes.size
            answer(ok(1))
            assertEquals(
                "MR04: fetch end publishes not-sending while the demand is still held, before finishManual releases it",
                listOf(WAITING, cooling(3_000)), refreshes.drop(fetchEndMark)
            )
            assertEquals("MR04: one outcome from that fetch", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("MR04: released, cooling 3 s from the fetch's end", cooling(3_000), refresh)
            at(2_999)
            assertEquals("MR04: no trailing GET for the joined manual", 1, calls.size)
        }
        rig(this) {
            foreground()
            answer(http(503))
            at(2_000)
            assertEquals("premise: the automatic retry in flight", 2, calls.size)
            owner.manual(); settle()
            assertEquals("MR04 retry: a manual joining the retry's GET is sending", JOINED, refresh)
            assertEquals("MR04 retry: with no GET of its own", 2, calls.size)
            answer(ok(1))
            assertEquals("MR04 retry: served by that fetch", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("MR04 retry: cooling 3 s from its end", cooling(5_000), refresh)
        }
    }

    /** Rule 4: a manual whose chain waits for the floor or for access is requested and not sending; a re-tap joins it and adds nothing. */
    @Test fun `S7E5A1_MR05 waiting for the floor or for access is requested without sending and a re-tap only joins`() = runTest {
        rig(this) {
            foreground()
            answer(ok(1))
            seedFloor("20")
            assertEquals("premise: a 20 s floor", 20_000L, floorAt())
            owner.manual(); settle()
            assertEquals("MR05 floor: requested, not sending", WAITING, refresh)
            assertEquals("MR05 floor: no GET under the floor", 1, calls.size)
            val stateMark = states.size
            at(5_000)
            repeat(3) { owner.manual() }
            settle()
            assertEquals("MR05 floor: a re-tap joins the waiting chain", WAITING, refresh)
            assertEquals("MR05 floor: and adds no GET", 1, calls.size)
            assertTrue("MR05 floor: nor an outcome", outcomes.isEmpty())
            assertEquals("MR05 floor: nor a settings state", stateMark, states.size)
            at(19_999)
            assertEquals("MR05 floor: still waiting just before the floor", WAITING, refresh)
            assertEquals("MR05 floor: still no GET", 1, calls.size)
            at(20_000)
            assertEquals("premise: the floor passed, one GET", 2, calls.size)
            assertEquals("MR05 floor: now sending", JOINED, refresh)
            answer(ok(2))
            assertEquals("MR05 floor: one outcome for all the taps", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("MR05 floor: released, cooling 3 s", cooling(23_000), refresh)
        }
        rig(this) {
            fence = null
            foreground()
            assertEquals("premise: no access, no GET", 0, calls.size)
            owner.manual(); settle()
            assertEquals("MR05 access: requested, not sending", WAITING, refresh)
            // Window: ten rechecks with the graph never ready (N1 T05), a re-tap before each.
            repeat(10) {
                owner.manual()
                advance(RECHECK)
            }
            assertEquals("MR05 access: the re-taps only joined, the demand is still held", WAITING, refresh)
            assertEquals("MR05 access: no GET while the graph is not ready", 0, calls.size)
            assertTrue("MR05 access: and no outcome", outcomes.isEmpty())
            fence = fenceFor(alice, grantA)
            accessRevisions.value += 1
            settle()
            assertEquals("premise: the hint lets one GET go", 1, calls.size)
            assertEquals("MR05 access: now sending", JOINED, refresh)
            val servedAt = now - t0
            answer(ok(1))
            assertEquals("MR05 access: one outcome for all the taps", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("MR05 access: released, cooling 3 s", cooling(servedAt + 3_000), refresh)
        }
    }

    /** Rule 1: once the manual has its result, the automatic retry, the trailing fetch and a promoted manual's retry are not sending. */
    @Test fun `S7E5A1_MR06 after the manual result an automatic retry or trailing fetch is not sending`() = runTest {
        rig(this) {
            foreground()
            owner.manual(); settle()
            assertEquals("premise: joined", JOINED, refresh)
            answer(http(503))
            assertEquals("premise: Failed at once", listOf(ManualRefreshOutcome.Failed), outcomes)
            assertEquals("premise: the automatic chain waits to retry", AlertLoadPhase.Retrying, phase)
            assertEquals("MR06 retry: the result released the demand and set 5 s", cooling(5_000), refresh)
            val released = refreshes.indexOf(cooling(5_000))
            at(2_000)
            assertEquals("premise: the automatic retry in flight", 2, calls.size)
            assertEquals("premise: one GET open", 1, open().size)
            assertEquals("MR06 retry: not requested, not sending", cooling(5_000), refresh)
            answer(http(503))
            at(6_000)
            assertEquals("premise: the second retry in flight", 3, calls.size)
            assertFalse("MR06 second retry: not requested", refresh.requested)
            assertFalse("MR06 second retry: not sending", refresh.sending)
            answer(ok(1))
            assertEquals("MR06 retry: no second outcome", listOf(ManualRefreshOutcome.Failed), outcomes)
            assertTrue(
                "MR06 retry: nothing published after the result was requested or sending",
                refreshes.drop(released).none { it.requested || it.sending }
            )
        }
        rig(this) {
            foreground()
            owner.manual(); owner.authoritative(); settle()
            assertEquals("premise: joined, with a trailing owed", JOINED, refresh)
            answer(ok(1))
            assertEquals("premise: served", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("premise: the trailing GET in flight", 2, calls.size)
            assertEquals("premise: one GET open", 1, open().size)
            assertEquals("MR06 trailing: not requested, not sending", cooling(3_000), refresh)
            val released = refreshes.indexOf(cooling(3_000))
            answer(ok(2))
            assertEquals("MR06 trailing: no second outcome", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("premise: the trailing answer applies", BankAlertSettingsState(AlertLoadPhase.Ready, listOf(setting(2)), alice), owner.state.value)
            assertTrue(
                "MR06 trailing: nothing published after the result was requested or sending",
                refreshes.drop(released).none { it.requested || it.sending }
            )
        }
        rig(this) {
            foreground()
            answer(http(400))
            owner.manual(); settle()
            owner.ensure(); settle()
            assertEquals("premise: a standalone manual promoted by an ensure", JOINED, refresh)
            assertEquals("premise: its own GET", 2, calls.size)
            answer(http(503))
            assertEquals("premise: Failed at once", listOf(ManualRefreshOutcome.Failed), outcomes)
            val released = refreshes.indexOf(cooling(5_000))
            assertTrue("premise: the result was published", released >= 0)
            at(2_000)
            assertEquals("premise: the promoted retry in flight", 3, calls.size)
            assertEquals("MR06 promoted: not requested, not sending", cooling(5_000), refresh)
            answer(ok(1))
            assertTrue(
                "MR06 promoted: nothing published after the result was requested or sending",
                refreshes.drop(released).none { it.requested || it.sending }
            )
        }
    }

    private class Silencer(val label: String, val drop: Rig.() -> Unit, val outcome: () -> Outcome)

    /** Ways a joined manual's fetch ends without a result while a new use stays acquirable: the admission moved, or the answer was Silent. */
    private fun silencers(): List<Silencer> = listOf(
        Silencer("admission dropped: a new access epoch, a success", { fence = fenceFor(alice, grantA, "epoch-2") }, { ok(2) }),
        Silencer("admission dropped: a new access epoch, a 503", { fence = fenceFor(alice, grantA, "epoch-2") }, { http(503) }),
        Silencer("admission dropped: the use invalidated and recovered", { snapshot = standing(grantA, invalidations = 1) }, { ok(2) }),
        Silencer("admission dropped: premium lost and regranted under a new grant", {
            // Conflated on purpose: no reset, only the captured use tells the old from the new.
            premium.value = premiumOf(alice, PremiumAccessState.FreeConfirmed)
            premium.value = confirmed(alice)
            snapshot = standing(grantB)
            fence = fenceFor(alice, grantB)
        }, { ok(2) }),
        Silencer("Silent: the answer's own session check fails", {}, { okGuarded(identityChanged, 2) }),
        Silencer("Silent: the GET ended by an identity exception", {}, { thrown(AuthIdentityChangedException()) }),
        Silencer("Silent: the GET withheld by its use", {}, { thrown(TopicUseWithheldException(emptyList())) })
    )

    /** Rule 1 with N1 H07/H09: an answer dropped Silent or by admission releases the joined manual with no deadline and no outcome. */
    @Test fun `S7E5A1_MR07 an answer dropped Silent or by admission releases the manual with no deadline`() = runTest {
        for (case in silencers()) rig(this) {
            foreground()
            owner.manual(); settle()
            assertEquals("premise(${case.label}): joined", JOINED, refresh)
            case.drop(this)
            answer(case.outcome())
            assertEquals("premise(${case.label}): dropped, nothing shown", nothing, owner.state.value)
            assertEquals("MR07 ${case.label}: released, nothing cooling, no deadline", IDLE, refresh)
            assertTrue("MR07 ${case.label}: no outcome", outcomes.isEmpty())
            advance(60_000)
            assertEquals("premise(${case.label}): no retry", 1, calls.size)
            assertEquals("MR07 ${case.label}: and it stays released", IDLE, refresh)
            owner.manual(); settle()
            assertEquals("MR07 ${case.label}: no cooldown was set: a new manual is served at once", 2, calls.size)
            assertEquals("MR07 ${case.label}: and sends", JOINED, refresh)
        }
    }

    /** Rule 1 with N1 H08/T04: the background, a reset and an account change release a manual joined to a fetch or waiting before it. */
    @Test fun `S7E5A1_MR08 background, reset and an account change release a joined or waiting manual`() = runTest {
        for (way in listOf("background", "reset", "account change")) for (wait in listOf("joined", "floor", "access")) rig(this) {
            val label = "$way, a manual $wait"
            when (wait) {
                "joined" -> foreground()
                "floor" -> {
                    foreground()
                    answer(ok(1))
                    seedFloor("20")
                }
                else -> {
                    fence = null
                    foreground()
                }
            }
            owner.manual(); settle()
            assertEquals("premise($label): the manual is held", if (wait == "joined") JOINED else WAITING, refresh)
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
            // Window: from the drop through the next fetch, answered: a demand kept across the drop would show or be served here.
            val mark = refreshes.size
            assertFalse("MR08 $label: no longer requested", refresh.requested)
            assertFalse("MR08 $label: nor sending", refresh.sending)
            assertNull("MR08 $label: and the drop set no deadline", refresh.cooldownUntilMillis)
            val who = if (way == "account change") bob else alice
            at(25_000)
            open().forEach { it.outcome.complete(ok(9, uid = who.uid)) }
            settle()
            assertTrue("MR08 $label: no outcome", outcomes.isEmpty())
            assertTrue("MR08 $label: the demand did not come back", refreshes.drop(mark).none { it.requested || it.sending })
            assertNull("MR08 $label: still no deadline", refresh.cooldownUntilMillis)
        }
    }

    /** Rule 1: a chain dropped by the background or a reset whose transport answers late publishes nothing over whatever replaced it. */
    @Test fun `S7E5A1_MR09 a retired chain whose transport answers late publishes nothing`() = runTest {
        for (way in listOf("background", "reset")) for (next in listOf("none", "automatic", "manual")) for (late in listOf("success", "failure")) rig(this) {
            val label = "dropped by $way, replaced by $next, a late $late"
            ignoresCancellation = true
            foreground()
            owner.manual(); settle()
            assertEquals("premise($label): a manual joined to the first GET", JOINED, refresh)
            if (way == "background") background() else {
                owner.reset(); settle()
            }
            if (next != "none") {
                if (way == "background") foreground() else {
                    owner.ensure(); settle()
                }
                if (next == "manual") {
                    owner.manual(); settle()
                }
            }
            val expected = if (next == "manual") JOINED else IDLE
            val sent = if (next == "none") 1 else 2
            assertEquals("premise($label): the replacement's own state", expected, refresh)
            assertEquals("premise($label): the old GET still awaits its late answer", sent, open().size)
            val mark = refreshes.size
            // Window: the retired chain's late answer runs its fetch's inner finally and its outer finally now.
            answer(0, if (late == "success") ok(1) else http(400))
            assertEquals("MR09 $label: nothing was published", mark, refreshes.size)
            assertEquals("MR09 $label: the current state is untouched", expected, refresh)
            assertTrue("MR09 $label: no outcome", outcomes.isEmpty())
            assertNull("MR09 $label: the late answer is not applied", settings)
            assertEquals("MR09 $label: and owes no GET", sent, calls.size)
            if (next != "none") {
                answer(1, ok(2))
                if (next == "manual") {
                    assertEquals("premise($label): the replacement serves its own manual", listOf(ManualRefreshOutcome.Succeeded), outcomes)
                    assertEquals("MR09 $label: released by its own result", cooling(3_000), refresh)
                } else {
                    assertEquals("MR09 $label: an automatic replacement is still idle", IDLE, refresh)
                }
            }
        }
    }

    // ---- rule 2: the deadline and coolingDown ----

    /** Rule 2 with N1 H03: each 3 / 5-10-20 s deadline is exposed as cooldownUntilMillis, cooling until 1 ms before it and not at it. */
    @Test fun `S7E5A1_MR10 the 3 s and 5 10 20 s deadlines are exposed and cool until exactly the deadline`() = runTest {
        rig(this) {
            foreground()
            answer(ok(1))
            assertEquals("premise: an automatic success sets no deadline", IDLE, refresh)
            val expected = mutableListOf<ManualRefreshOutcome>()
            fun served(offset: Long, outcome: Outcome, result: ManualRefreshOutcome, cooldown: Long) {
                at(offset)
                assertFalse("premise at $offset: not cooling", refresh.coolingDown)
                val before = calls.size
                owner.manual(); settle()
                assertEquals("premise at $offset: the manual is served", before + 1, calls.size)
                assertTrue("premise at $offset: and sending", refresh.requested && refresh.sending)
                answer(outcome)
                expected += result
                assertEquals("MR10 at $offset: the outcome stream", expected, outcomes)
                val deadline = t0 + offset + cooldown
                assertEquals("MR10 at $offset: the $cooldown ms deadline in nowMillis units", cooling(offset + cooldown), refresh)
                owner.manual(); settle()
                expected += ManualRefreshOutcome.CoolingDown(cooldown)
                assertEquals("MR10 at $offset: the owner's own deadline: a tap now has the whole cooldown left", expected, outcomes)
                assertEquals("MR10 at $offset: the same value", cooldown, refresh.cooldownUntilMillis!! - now)
                at(offset + cooldown - 1)
                assertEquals("MR10 at $offset: cooling 1 ms before the deadline", cooling(offset + cooldown), refresh)
                owner.manual(); settle()
                expected += ManualRefreshOutcome.CoolingDown(1)
                assertEquals("MR10 at $offset: a tap then is refused with 1 ms left", expected, outcomes)
                assertEquals("MR10 at $offset: cooling sends nothing", before + 1, calls.size)
                at(offset + cooldown)
                assertFalse("MR10 at ${offset + cooldown}: not cooling at the deadline, with no tap", refresh.coolingDown)
                assertFalse("MR10 at ${offset + cooldown}: not requested", refresh.requested)
                assertFalse("MR10 at ${offset + cooldown}: not sending", refresh.sending)
                // After expiry the deadline may stay as the owner keeps it (API r2 §1 rule 2); it is never another value.
                assertTrue(
                    "MR10 at ${offset + cooldown}: the expired deadline kept or cleared (${refresh.cooldownUntilMillis})",
                    refresh.cooldownUntilMillis == deadline || refresh.cooldownUntilMillis == null
                )
            }
            served(0, ok(2), ManualRefreshOutcome.Succeeded, 3_000)
            served(3_000, http(400), ManualRefreshOutcome.Failed, 5_000)
            served(8_000, http(400), ManualRefreshOutcome.Failed, 10_000)
            served(18_000, http(400), ManualRefreshOutcome.Failed, 20_000)
            served(38_000, http(400), ManualRefreshOutcome.Failed, 20_000)
            served(58_000, ok(3), ManualRefreshOutcome.Succeeded, 3_000)
            served(61_000, http(400), ManualRefreshOutcome.Failed, 5_000)
            assertEquals("premise: the snapshot stayed through every failure", listOf(setting(3)), settings)
        }
    }

    /** Rule 2: a manual exactly at the deadline is taken and the state recomputed from now, though the timer due then has not run. */
    @Test fun `S7E5A1_MR11 a manual exactly at the deadline is accepted before the timer has run`() = runTest {
        rig(this) {
            foreground()
            answer(ok(1))
            owner.manual(); settle()
            answer(ok(2))
            assertEquals("premise: cooling until 3 000", cooling(3_000), refresh)
            at(2_999)
            assertEquals("premise: cooling 1 ms before", cooling(3_000), refresh)
            arriveAt(3_000)
            assertEquals("premise: exactly at the deadline", t0 + 3_000, now)
            assertTrue("premise: the timer due now has not run, so the published value still cools", refresh.coolingDown)
            owner.manual()
            assertEquals("MR11: no CoolingDown at the deadline", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            assertTrue("MR11: the manual is taken", refresh.requested)
            assertFalse("MR11: and coolingDown recomputed from now, not left to the timer", refresh.coolingDown)
            settle()
            assertEquals("MR11: still no CoolingDown", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("MR11: it is served", 3, calls.size)
            assertTrue("MR11: sending", refresh.requested && refresh.sending)
            assertFalse("MR11: not cooling", refresh.coolingDown)
            answer(ok(3))
            assertEquals("MR11: its own outcome", listOf(ManualRefreshOutcome.Succeeded, ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("MR11: and its own 3 s deadline", cooling(6_000), refresh)
            at(5_999)
            assertTrue("MR11: cooling 1 ms before it", refresh.coolingDown)
            at(6_000)
            assertFalse("MR11: not at it", refresh.coolingDown)
        }
    }

    /** Rule 2 with N1 H04: the background keeps the deadline and its timer, which still clears coolingDown in the background. */
    @Test fun `S7E5A1_MR12 the background keeps the deadline and its timer still clears coolingDown`() = runTest {
        rig(this) {
            foreground()
            answer(ok(1))
            owner.manual(); settle()
            answer(ok(2))
            assertEquals("premise: cooling until 3 000", cooling(3_000), refresh)
            val timer = sleeps.single { it.open }
            at(1_000)
            background()
            assertEquals("MR12: the background keeps the deadline", cooling(3_000), refresh)
            assertTrue("MR12: and does not cancel its timer", timer.open)
            at(2_999)
            assertEquals("MR12: cooling 1 ms before the deadline, in the background", cooling(3_000), refresh)
            at(3_000)
            assertEquals("premise: the kept timer woke at the deadline", 3_000L, timer.wokeAt)
            assertFalse("MR12: it cleared coolingDown in the background", refresh.coolingDown)
            assertFalse("MR12: not requested", refresh.requested)
            assertFalse("MR12: not sending", refresh.sending)
            assertTrue(
                "MR12: the expired deadline kept or cleared (${refresh.cooldownUntilMillis})",
                refresh.cooldownUntilMillis == t0 + 3_000 || refresh.cooldownUntilMillis == null
            )
            assertEquals("MR12: the timer sends no GET", 2, calls.size)
            at(4_000)
            foreground()
            assertEquals("premise: the 4 s old snapshot is fresh", 2, calls.size)
            owner.manual(); settle()
            assertEquals("MR12: a manual after the deadline is served", 3, calls.size)
            assertEquals("MR12: with no CoolingDown", listOf(ManualRefreshOutcome.Succeeded), outcomes)
        }
    }

    /** Rule 2 with N1 H04: a reset, in the foreground or the background, publishes no deadline and not cooling and cancels the timer. */
    @Test fun `S7E5A1_MR13 a reset clears the deadline to null and cancels its timer`() = runTest {
        for (inBackground in listOf(false, true)) rig(this) {
            val label = if (inBackground) "in the background" else "in the foreground"
            foreground()
            answer(ok(1))
            owner.manual(); settle()
            answer(ok(2))
            assertEquals("premise($label): cooling until 3 000", cooling(3_000), refresh)
            val timer = sleeps.single { it.open }
            at(1_000)
            if (inBackground) background()
            assertTrue("premise($label): the timer still sleeps", timer.open)
            owner.reset(); settle()
            assertEquals("MR13 $label: nothing requested, not cooling, no deadline", IDLE, refresh)
            assertEquals("MR13 $label: the reset cancelled its timer", 1_000L, timer.cancelledAt)
            val mark = refreshes.size
            at(3_000)
            advance(5_000)
            assertEquals("MR13 $label: nothing is published at the old deadline or after", mark, refreshes.size)
            if (inBackground) foreground()
            owner.manual(); settle()
            assertEquals("MR13 $label: no CoolingDown, the next manual is taken at once", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("MR13 $label: and sends", JOINED, refresh)
            assertEquals("MR13 $label: its GET", 3, calls.size)
        }
    }

    /** Rule 2: a new deadline gets its own timer, even for a reused value; a replaced timer that ignores its cancellation and wakes late writes nothing. */
    @Test fun `S7E5A1_MR14 a new deadline replaces the timer and a replaced timer waking late writes nothing`() = runTest {
        rig(this) {
            foreground()
            answer(ok(1))
            owner.manual(); settle()
            answer(ok(2))
            val first = sleeps.single { it.open }
            owner.reset(); settle()
            assertEquals("premise: the reset cancelled the first timer", 0L, first.cancelledAt)
            owner.manual(); settle()
            answer(ok(3))
            assertEquals("premise: the same deadline value again", cooling(3_000), refresh)
            assertTrue("premise: a timer sleeps for it", sleeps.any { it.open })
            at(2_999)
            assertTrue("MR14 reused: cooling 1 ms before", refresh.coolingDown)
            at(3_000)
            assertFalse("MR14 reused: the reused deadline is cleared at its time by its own timer", refresh.coolingDown)
        }
        rig(this) {
            foreground()
            answer(ok(1))
            sleepIgnoresCancellation = true
            owner.manual(); settle()
            answer(ok(2))
            val old = sleeps.single { it.open }
            at(500)
            owner.reset(); settle()
            assertEquals("premise: reset", IDLE, refresh)
            assertTrue("premise: the old timer sleeps on past its cancellation", old.open)
            owner.manual(); settle()
            answer(http(400))
            assertEquals("premise: the new deadline, 500 + 5 000", cooling(5_500), refresh)
            val mark = refreshes.size
            // Window: the replaced timer wakes at its old deadline, before the new one.
            at(3_000)
            assertEquals("premise: the old timer woke now", 3_000L, old.wokeAt)
            assertEquals("MR14 late: it published nothing", mark, refreshes.size)
            assertEquals("MR14 late: the new deadline stands", cooling(5_500), refresh)
            owner.manual(); settle()
            assertEquals("MR14 late: a tap is still refused by it", ManualRefreshOutcome.CoolingDown(2_500), outcomes.last())
            at(5_499)
            assertEquals("MR14 late: cooling 1 ms before the new deadline", cooling(5_500), refresh)
            at(5_500)
            assertFalse("MR14 late: the new timer clears it at its own deadline", refresh.coolingDown)
        }
        rig(this) {
            foreground()
            answer(ok(1))
            sleepIgnoresCancellation = true
            owner.manual(); settle()
            answer(ok(2))
            owner.reset(); settle()
            owner.manual(); settle()
            answer(ok(3))
            assertEquals("premise: the same deadline value twice", cooling(3_000), refresh)
            val asleep = sleeps.filter { it.open }
            assertEquals("premise: a timer for each, the first past its cancellation", 2, asleep.size)
            at(1_000)
            owner.reset(); settle()
            owner.manual(); settle()
            answer(http(400))
            assertEquals("premise: a third deadline, 1 000 + 5 000", cooling(6_000), refresh)
            val mark = refreshes.size
            // Window: both timers of the reused value wake late at 3 000, while the third deadline runs to 6 000.
            at(3_000)
            assertTrue("premise: both old timers woke now", asleep.all { it.wokeAt == 3_000L })
            assertEquals("MR14 reused, late: nothing was published", mark, refreshes.size)
            assertEquals("MR14 reused, late: the third deadline stands", cooling(6_000), refresh)
            at(5_999)
            assertEquals("MR14 reused, late: cooling 1 ms before it", cooling(6_000), refresh)
            at(6_000)
            assertFalse("MR14 reused, late: cleared at it", refresh.coolingDown)
        }
    }

    /** Rule 2: a timer whose sleep returns before the deadline rechecks, waits again, and clears coolingDown only at the deadline. */
    @Test fun `S7E5A1_MR15 a timer waking before the deadline waits again and clears coolingDown only at the deadline`() = runTest {
        rig(this) {
            foreground()
            answer(ok(1))
            owner.manual(); settle()
            earlyWakeOnce = 1_000
            answer(ok(2))
            assertEquals("premise: cooling until 3 000", cooling(3_000), refresh)
            val timer = sleeps.single { it.open }
            at(2_000)
            assertEquals("premise: its sleep returned 1 s early", 2_000L, timer.wokeAt)
            assertEquals("MR15: an early wake leaves it cooling", cooling(3_000), refresh)
            at(2_999)
            assertEquals("MR15: until 1 ms before the deadline", cooling(3_000), refresh)
            at(3_000)
            assertFalse("MR15: and clears it at the deadline", refresh.coolingDown)
        }
    }

    // ---- rule 3: nothing else changes ----

    /** Rule 3: the timer and the state add no outcome, settings state or GET, leave the floor, and leave the freshness timed from the success. */
    @Test fun `S7E5A1_MR16 the state changes no outcome, settings state, GET, floor or freshness`() = runTest {
        rig(this) {
            foreground()
            answer(ok(1))
            owner.manual(); settle()
            answer(ok(2))
            assertEquals(
                "MR16: the settings state is the three-field value it was",
                BankAlertSettingsState(AlertLoadPhase.Ready, listOf(setting(2)), alice), owner.state.value
            )
            assertEquals("premise: served", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            val stateMark = states.size
            at(3_000)
            assertFalse("premise: the timer ran", refresh.coolingDown)
            assertEquals("MR16: the timer published no settings state", stateMark, states.size)
            assertEquals("MR16: nor an outcome", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("MR16: nor a GET", 2, calls.size)
            at(29_999)
            owner.ensure(); settle()
            assertEquals("MR16: freshness still runs from the success at 0", 2, calls.size)
            at(30_000)
            owner.ensure(); settle()
            assertEquals("MR16: and loads from exactly 30 s", 3, calls.size)
            assertFalse("MR16: an ensure is not requested", refresh.requested)
            assertFalse("MR16: nor sending", refresh.sending)
        }
        rig(this) {
            foreground()
            answer(ok(1))
            owner.manual(); settle()
            answer(http(429, "40"))
            assertEquals("premise: Failed", listOf(ManualRefreshOutcome.Failed), outcomes)
            assertEquals("premise: its Retry-After is the floor", 40_000L, floorAt())
            assertEquals("premise: cooling 5 s", cooling(5_000), refresh)
            at(5_000)
            assertFalse("premise: the timer ran", refresh.coolingDown)
            assertEquals("MR16 floor: the timer left the floor as it was", 40_000L, floorAt())
            owner.manual(); settle()
            assertTrue("MR16 floor: the manual is taken", refresh.requested)
            assertFalse("MR16 floor: but waits for the floor, not sending", refresh.sending)
            assertFalse("MR16 floor: not cooling", refresh.coolingDown)
            at(39_999)
            assertEquals("MR16 floor: the floor still holds the GET", 2, calls.size)
            at(40_000)
            assertEquals("MR16 floor: sent at the floor", 3, calls.size)
            assertTrue("MR16 floor: now sending", refresh.sending)
            answer(ok(3))
            assertEquals("MR16 floor: the outcome stream as before", listOf(ManualRefreshOutcome.Failed, ManualRefreshOutcome.Succeeded), outcomes)
        }
    }

    /** Rule 1: begin keeps its handle-before-run order, so on an eager owner dispatcher a manual still holds its chain, sends and is released. */
    @Test fun `S7E5A1_MR17 on an eager owner dispatcher a manual still holds its chain, sends and is released`() = runTest {
        rig(this, eager = true) {
            foreground()
            assertEquals("premise: the eager foreground sent its GET", 1, calls.size)
            answer(ok(1))
            assertEquals("premise: an automatic load leaves it idle", IDLE, refresh)
            owner.manual()
            assertEquals("MR17: the standalone manual's eager chain sent its own GET", 2, calls.size)
            assertEquals("MR17: requested and sending, not dropped by its own chain", JOINED, refresh)
            answer(ok(2))
            assertEquals("MR17: one outcome", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("MR17: released with its 3 s deadline", cooling(3_000), refresh)
            at(2_999)
            assertTrue("MR17: cooling 1 ms before", refresh.coolingDown)
            at(3_000)
            assertFalse("MR17: not at the deadline", refresh.coolingDown)
        }
    }

    // ---- rule 2 at its edges: the deadline's timer, a refused tap and a late replaced timer ----

    /** Rule 2: a manual taken at the deadline before its timer ran joins the automatic GET in flight; the timer then recomputes from that chain, so it stays requested and sending until the answer. */
    @Test fun `S7E5A1_MR18 a manual at the deadline joining an automatic GET stays requested and sending when the timer then runs`() = runTest {
        rig(this) {
            foreground()
            owner.manual(); settle()
            answer(http(503))
            assertEquals("premise: Failed at once", listOf(ManualRefreshOutcome.Failed), outcomes)
            assertEquals("premise: released, cooling 5 s", cooling(5_000), refresh)
            val timer = sleeps.single { it.open && it.millis == 5_000L }
            assertEquals("premise: the cooldown timer started with the result", 0L, timer.startedAt)
            at(2_000)
            assertEquals("premise: the automatic retry in flight", 2, calls.size)
            assertEquals("premise: one GET open", 1, open().size)
            arriveAt(5_000)
            assertEquals("premise: exactly at the deadline", t0 + 5_000, now)
            assertTrue("premise: the timer due now has not run", timer.open)
            assertEquals("premise: so the published value still cools", cooling(5_000), refresh)
            val joined = JOINED.copy(cooldownUntilMillis = t0 + 5_000)
            val mark = refreshes.size
            owner.manual()
            assertEquals("MR18 retry: no CoolingDown at the deadline", listOf(ManualRefreshOutcome.Failed), outcomes)
            assertEquals("MR18 retry: the tap joins the retry's GET, requested and sending, the deadline expired", joined, refresh)
            assertEquals("MR18 retry: with no GET of its own", 2, calls.size)
            // Window: the timer's own run at the deadline, after the tap.
            settle()
            assertEquals("premise: the timer ran at the deadline", 5_000L, timer.wokeAt)
            assertEquals("MR18 retry: the timer recomputed from the active chain: still requested and sending", joined, refresh)
            assertEquals("MR18 retry: no value without the joined demand was published, not even for a moment", listOf(joined), refreshes.drop(mark))
            at(6_000)
            assertEquals("MR18 retry: and it stays so while the GET is in flight", joined, refresh)
            assertEquals("MR18 retry: with nothing else published", listOf(joined), refreshes.drop(mark))
            answer(ok(1))
            assertEquals("MR18 retry: the GET's answer serves the joined manual", listOf(ManualRefreshOutcome.Failed, ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("MR18 retry: then released, cooling 3 s from the answer", cooling(9_000), refresh)
            at(8_999)
            assertEquals("MR18 retry: cooling 1 ms before it", cooling(9_000), refresh)
            assertEquals("MR18 retry: no trailing GET for the joined manual", 2, calls.size)
            at(9_000)
            assertFalse("MR18 retry: not at it", refresh.coolingDown)
        }
        rig(this) {
            foreground()
            answer(ok(1))
            owner.manual(); settle()
            answer(ok(2))
            assertEquals("premise: served, cooling until 3 000", cooling(3_000), refresh)
            val timer = sleeps.single { it.open }
            at(1_000)
            owner.onSync(); settle()
            assertEquals("premise: the sync's automatic GET in flight", 3, calls.size)
            assertEquals("premise: one GET open", 1, open().size)
            assertEquals("premise: the automatic GET is neither requested nor sending", cooling(3_000), refresh)
            arriveAt(3_000)
            assertTrue("premise: the timer due now has not run", timer.open)
            assertEquals("premise: so the published value still cools", cooling(3_000), refresh)
            val joined = JOINED.copy(cooldownUntilMillis = t0 + 3_000)
            val mark = refreshes.size
            owner.manual()
            assertEquals("MR18 sync: no CoolingDown at the deadline", listOf(ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("MR18 sync: the tap joins the sync's GET, requested and sending", joined, refresh)
            // Window: the timer's own run at the deadline, after the tap.
            settle()
            assertEquals("premise: the timer ran at the deadline", 3_000L, timer.wokeAt)
            assertEquals("MR18 sync: the timer recomputed from the active chain: still requested and sending", joined, refresh)
            assertEquals("MR18 sync: no value without the joined demand was published, not even for a moment", listOf(joined), refreshes.drop(mark))
            at(3_500)
            assertEquals("MR18 sync: and it stays so while the GET is in flight", joined, refresh)
            assertEquals("MR18 sync: with no GET of its own", 3, calls.size)
            answer(ok(3))
            assertEquals("MR18 sync: the GET's answer serves the joined manual", listOf(ManualRefreshOutcome.Succeeded, ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("MR18 sync: then released, cooling 3 s from the answer", cooling(6_500), refresh)
            at(6_499)
            assertTrue("MR18 sync: cooling 1 ms before it", refresh.coolingDown)
            at(6_500)
            assertFalse("MR18 sync: not at it", refresh.coolingDown)
        }
    }

    /** Rules 1 and 2: a tap refused while cooling emits only CoolingDown and is not held by an active automatic chain, sleeping or fetching, whose later answer then is no manual result. */
    @Test fun `S7E5A1_MR19 a tap refused while cooling is not held by an active automatic chain`() = runTest {
        rig(this) {
            foreground()
            owner.manual(); settle()
            answer(http(503))
            assertEquals("premise: Failed at once", listOf(ManualRefreshOutcome.Failed), outcomes)
            assertEquals("premise: the automatic chain sleeps in its 2 s backoff", AlertLoadPhase.Retrying, phase)
            assertEquals("premise: released, cooling 5 s", cooling(5_000), refresh)
            val mark = refreshes.size
            // Window: the tap while the chain sleeps in its backoff, through its retry and that retry's answer.
            at(1_000)
            assertEquals("premise: the backoff still holds the retry", 1, calls.size)
            owner.manual()
            assertEquals(
                "MR19 backoff: the refused tap emits only CoolingDown with its remaining time",
                listOf(ManualRefreshOutcome.Failed, ManualRefreshOutcome.CoolingDown(4_000)), outcomes
            )
            assertEquals("MR19 backoff: and is not held by the sleeping chain: not requested", cooling(5_000), refresh)
            settle()
            assertFalse("MR19 backoff: still not requested", refresh.requested)
            at(2_000)
            assertEquals("premise: the automatic retry in flight", 2, calls.size)
            assertEquals("premise: one GET open", 1, open().size)
            assertEquals("MR19 backoff: the chain's later automatic fetch is neither requested nor sending", cooling(5_000), refresh)
            at(3_000)
            answer(ok(1))
            assertEquals("premise: the retry's answer applies", BankAlertSettingsState(AlertLoadPhase.Ready, listOf(setting(1)), alice), owner.state.value)
            assertEquals(
                "MR19 backoff: that answer emits no further manual outcome",
                listOf(ManualRefreshOutcome.Failed, ManualRefreshOutcome.CoolingDown(4_000)), outcomes
            )
            assertEquals("MR19 backoff: nor moves the deadline the manual result set", t0 + 5_000, refresh.cooldownUntilMillis)
            assertEquals("MR19 backoff: cooling to that same deadline, released", cooling(5_000), refresh)
            assertEquals("MR19 backoff: nothing was published from the tap through the answer", mark, refreshes.size)
            assertEquals("MR19 backoff: the refused tap added no GET", 2, calls.size)
            at(4_999)
            assertEquals("MR19 backoff: cooling 1 ms before the deadline", cooling(5_000), refresh)
            at(5_000)
            assertFalse("MR19 backoff: not at it", refresh.coolingDown)
        }
        rig(this) {
            foreground()
            answer(ok(1))
            owner.manual(); settle()
            answer(ok(2))
            assertEquals("premise: served, cooling until 3 000", cooling(3_000), refresh)
            at(1_000)
            owner.onSync(); settle()
            assertEquals("premise: the sync's automatic GET in flight", 3, calls.size)
            assertEquals("premise: one GET open", 1, open().size)
            assertEquals("premise: the automatic GET is neither requested nor sending", cooling(3_000), refresh)
            val mark = refreshes.size
            // Window: the tap while the automatic GET is in flight, through its answer.
            owner.manual()
            assertEquals(
                "MR19 in flight: the refused tap emits only CoolingDown with its remaining time",
                listOf(ManualRefreshOutcome.Succeeded, ManualRefreshOutcome.CoolingDown(2_000)), outcomes
            )
            assertEquals("MR19 in flight: and does not join the GET in flight: neither requested nor sending", cooling(3_000), refresh)
            settle()
            at(2_000)
            assertEquals("MR19 in flight: still neither", cooling(3_000), refresh)
            answer(ok(3))
            assertEquals("premise: the automatic answer applies", BankAlertSettingsState(AlertLoadPhase.Ready, listOf(setting(3)), alice), owner.state.value)
            assertEquals(
                "MR19 in flight: that answer emits no further manual outcome",
                listOf(ManualRefreshOutcome.Succeeded, ManualRefreshOutcome.CoolingDown(2_000)), outcomes
            )
            assertEquals("MR19 in flight: nor moves the deadline", t0 + 3_000, refresh.cooldownUntilMillis)
            assertEquals("MR19 in flight: nothing was published from the tap through the answer", mark, refreshes.size)
            assertEquals("MR19 in flight: the refused tap added no GET", 3, calls.size)
            at(2_999)
            assertEquals("MR19 in flight: cooling 1 ms before the deadline", cooling(3_000), refresh)
            at(3_000)
            assertFalse("MR19 in flight: not at it", refresh.coolingDown)
        }
    }

    /** Rule 2 / §5 row 1: after a replaced timer that ignored its cancellation wakes late, a reset still cancels the current timer, which then writes nothing. */
    @Test fun `S7E5A1_MR20 after a replaced timer wakes late a reset still cancels the current timer`() = runTest {
        rig(this) {
            foreground()
            answer(ok(1))
            sleepIgnoresCancellation = true
            owner.manual(); settle()
            answer(ok(2))
            val old = sleeps.single { it.open }
            at(500)
            owner.reset(); settle()
            assertEquals("premise: reset", IDLE, refresh)
            assertTrue("premise: the replaced timer sleeps on past its cancellation", old.open)
            // The current timer's sleep is cooperative, so a cancellation of it shows as cancelledAt, as in MR13.
            sleepIgnoresCancellation = false
            owner.manual(); settle()
            answer(http(400))
            assertEquals("premise: the current deadline, 500 + 5 000", cooling(5_500), refresh)
            val current = sleeps.single { it.open && it !== old }
            assertEquals("premise: the current timer started with that result", 500L, current.startedAt)
            // Window: the replaced timer wakes late at its old deadline, after the current timer was installed.
            at(3_000)
            assertEquals("premise: the replaced timer woke now", 3_000L, old.wokeAt)
            assertTrue("premise: the current timer still sleeps", current.open)
            assertEquals("premise: the current deadline stands", cooling(5_500), refresh)
            at(4_000)
            owner.reset(); settle()
            assertEquals("MR20: the reset publishes nothing requested, not cooling, no deadline", IDLE, refresh)
            assertEquals(
                "MR20: the reset cancelled the current timer, though a replaced timer finished after it was installed",
                4_000L, current.cancelledAt
            )
            val mark = refreshes.size
            at(5_500)
            advance(5_000)
            assertNull("MR20: the cancelled current timer never woke", current.wokeAt)
            assertEquals("MR20: the current timer published nothing after the reset, at its deadline or after", mark, refreshes.size)
            assertEquals("MR20: the state stays idle", IDLE, refresh)
            owner.manual(); settle()
            assertEquals(
                "MR20: no CoolingDown, the next manual is taken at once",
                listOf(ManualRefreshOutcome.Succeeded, ManualRefreshOutcome.Failed), outcomes
            )
            assertEquals("MR20: and sends", JOINED, refresh)
            assertEquals("MR20: its GET", 4, calls.size)
        }
    }

    /**
     * Rule 2 (API r2 §1: a new deadline invalidates the previous token first and cancels the previous Job): only an eager owner
     * can set a new deadline before the old timer's due wake has run (a standard dispatcher always runs that earlier-queued wake
     * first), and then the new deadline cancels the old timer instead of leaving it to wake and exit on its token.
     */
    @Test fun `S7E5A1_MR21 a new deadline set before the old timer's due wake has run cancels that timer`() = runTest {
        rig(this, eager = true) {
            foreground()
            answer(ok(1))
            owner.manual()
            answer(ok(2))
            assertEquals("premise: cooling until 3 000", cooling(3_000), refresh)
            val old = sleeps.single { it.open }
            assertEquals("premise: the old timer sleeps its 3 s from the start", listOf(0L, 3_000L), listOf(old.startedAt, old.millis))
            arriveAt(3_000)
            assertTrue("premise: the old timer's wake is due now and has not run", old.open)
            owner.manual()
            assertEquals("premise: the manual at the deadline is taken and sends at once", 3, calls.size)
            answer(ok(3))
            assertEquals("premise: its own new 3 s deadline", cooling(6_000), refresh)
            assertEquals("MR21: the new deadline cancelled the old timer at once", 3_000L, old.cancelledAt)
            assertNull("MR21: so the old timer never woke", old.wokeAt)
            assertEquals("MR21: one timer sleeps, for the new deadline", 1, sleeps.count { it.open })
            at(5_999)
            assertTrue("MR21: cooling 1 ms before the new deadline", refresh.coolingDown)
            at(6_000)
            assertFalse("MR21: cleared at it", refresh.coolingDown)
        }
    }

    private companion object {
        const val RECHECK = 1_000L
        val SETTINGS = AuthenticatedEndpoint.NOTIFICATION_SETTINGS
        /** Nothing requested, nothing sent, no deadline: the initial value and the value after a reset. */
        val IDLE = ManualRefreshState(requested = false, sending = false, coolingDown = false, cooldownUntilMillis = null)
        /** A manual demand attached to the fetch in flight, with no deadline yet. */
        val JOINED = ManualRefreshState(requested = true, sending = true, coolingDown = false, cooldownUntilMillis = null)
        /** A manual demand held while its chain waits for the floor or for access, with no deadline yet. */
        val WAITING = ManualRefreshState(requested = true, sending = false, coolingDown = false, cooldownUntilMillis = null)
    }
}
