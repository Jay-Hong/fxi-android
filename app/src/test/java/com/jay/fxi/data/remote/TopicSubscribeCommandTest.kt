package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.remote.dto.SubscriptionAck
import com.jay.fxi.data.remote.dto.SubscriptionAckTopic
import com.jay.fxi.data.remote.dto.SubscriptionError
import com.jay.fxi.data.remote.dto.SubscriptionRejection
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.domain.model.TopicAuthResolution
import com.jay.fxi.domain.model.TopicControlState
import com.jay.fxi.domain.model.TopicDeliveryState
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.domain.model.TopicSubscriptionStateStore
import com.jay.fxi.domain.model.TopicWholeRequestDecision
import com.jay.fxi.domain.model.TopicWholeRequestFailure
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One subscribe command's lifetime, on a virtual clock.
 *
 * The arithmetic these tests stand on — the two deadlines, the three attempts, the whole-request
 * matrix — is already checked where it lives. What is checked here is the part only the driver can
 * get wrong: *when* it registers, *what* it correlates, and *what it does not do* after an answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicSubscribeCommandTest {

    private companion object {
        const val USD = "fx:usd-krw"
        const val JPY = "fx:jpy-krw"
        const val ACK_TIMEOUT_MS = 20_000L
        const val DELIVERY_TIMEOUT_MS = 45_000L

        /** `TopicCommandRetryPolicy.SILENT_RETRY_COOLDOWN`, with the tests' jitter draw of 0. */
        const val COOLDOWN_MS = 5_000L
    }

    /**
     * The world around one command, with every seam a plain mutable field.
     *
     * The clock and the waits are the same scheduler on purpose — production's pair disagrees
     * across sleep, and [clockSkewMillis] is where that disagreement is put in deliberately, by
     * the one test about it, rather than left to leak into every other one.
     */
    private class Harness(test: TestScope) {
        val scheduler = test.testScheduler
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val store = TopicSubscriptionStateStore()

        /** Added to the monotonic reading only, so the clock can outrun the waits. */
        var clockSkewMillis = 0L

        val clock = object : TopicCommandClock {
            override fun nowMillis(): Long = scheduler.currentTime + clockSkewMillis
            override suspend fun sleep(duration: Duration) = delay(duration)
        }

        var credential = AuthSnapshot("u1", 1L, "token-1")
        var credentialFailures = 0

        /** How long acquiring a credential suspends for, so a test can act during the wait. */
        var credentialGateMillis = 0L
        var refreshGateMillis = 0L
        var identityChanges = 0
        var refreshed: AuthSnapshot? = AuthSnapshot("u1", 1L, "token-2")

        /** Run as the refresh returns, without suspending: where a teardown on the same thread lands just before the result is used. */
        var onRefreshReturn: (() -> Unit)? = null
        val refreshedFrom = mutableListOf<AuthSnapshot>()

        val credentials = object : TopicCommandCredentials {
            override suspend fun currentSnapshot(): AuthSnapshot {
                if (credentialGateMillis > 0) delay(credentialGateMillis)
                if (identityChanges > 0) {
                    identityChanges--
                    throw AuthIdentityChangedException()
                }
                if (credentialFailures > 0) {
                    credentialFailures--
                    throw AuthUnavailableException("no user")
                }
                return credential
            }

            override suspend fun refreshAfterUnauthorized(rejected: AuthSnapshot): AuthSnapshot? {
                refreshedFrom += rejected
                if (refreshGateMillis > 0) delay(refreshGateMillis)
                onRefreshReturn?.invoke()
                return refreshed
            }
        }

        val requests = mutableListOf<TopicSubscribeRequest>()

        /** Indexed by attempt, 1-based; anything past the end sends. */
        var sendSucceeds: List<Boolean> = emptyList()
        var onSend: ((TopicSubscribeRequest) -> Unit)? = null

        var wanted = setOf(USD)
        var jitterUnit = 0.0
        val acknowledgements = mutableListOf<Pair<Long, TopicCommandAcknowledgement>>()
        var onAcknowledgement: (() -> Unit)? = null

        /** What each application is answered with; every question is recorded in [admissions] first. */
        var admission: (refusesPremium: Boolean) -> TopicAnswerAdmission =
            { TopicAnswerAdmission.Admitted(clock.nowMillis()) }
        val admissions = mutableListOf<Boolean>()

        /** The whole refusal map each application was asked with (L-4e E2a); [admissions] keeps only whether it refused premium. */
        val admittedRejections = mutableListOf<Map<String, TopicRejectionReason>>()

        /** Fired every time the command asks what is still wanted. */
        var onScope: (() -> Unit)? = null
        private var nextId = 0

        lateinit var command: TopicSubscribeCommand

        fun build(purpose: TopicCommandPurpose = TopicCommandPurpose.FIRST_DELIVERY) {
            command = TopicSubscribeCommand(
                purpose = purpose,
                store = store,
                credentials = credentials,
                clock = clock,
                encode = { request -> requests += request; "encoded-${request.requestId}" },
                send = { _ ->
                    val request = requests.last()
                    val index = requests.size - 1
                    val ok = sendSucceeds.getOrElse(index) { true }
                    if (ok) onSend?.invoke(request)
                    ok
                },
                newRequestId = { "r${++nextId}" },
                jitter = { jitterUnit },
                scope = { onScope?.invoke(); wanted },
                onAcknowledged = {
                    acknowledgements += scheduler.currentTime to it
                    onAcknowledgement?.invoke()
                },
                admitAnswer = { rejected ->
                    val refusesPremium = rejected.values.any { it == TopicRejectionReason.PREMIUM_REQUIRED }
                    admissions += refusesPremium
                    admittedRejections += rejected
                    admission(refusesPremium)
                }
            )
        }

        fun ack(
            requestId: String,
            active: List<String>,
            rejected: Map<String, String> = emptyMap(),
            leaseSeconds: Long? = null
        ) = DecodedTopicFrame.Acknowledgement(
            SubscriptionAck(
                requestId = requestId,
                operation = "subscribe",
                acceptedTopics = active.map { SubscriptionAckTopic(it) },
                rejectedTopics = rejected.map { SubscriptionRejection(it.key, it.value) },
                removedTopics = emptyList(),
                activeSubscriptions = active.map {
                    if (leaseSeconds == null) {
                        SubscriptionAckTopic(it)
                    } else {
                        SubscriptionAckTopic(it, "lease-$it", leaseSeconds)
                    }
                }
            )
        )

        /** An acknowledgement whose lease is half-written, which the contract calls malformed. */
        fun ackWithBrokenLease(requestId: String, topic: String) =
            DecodedTopicFrame.Acknowledgement(
                SubscriptionAck(
                    requestId = requestId,
                    operation = "subscribe",
                    acceptedTopics = listOf(SubscriptionAckTopic(topic)),
                    rejectedTopics = emptyList(),
                    removedTopics = emptyList(),
                    activeSubscriptions = listOf(SubscriptionAckTopic(topic, leaseDurationSeconds = 600))
                )
            )

        fun error(requestId: String?, code: String, retryAfter: Long? = null) =
            DecodedTopicFrame.RequestFailure(SubscriptionError(requestId, code, retryAfter))

        fun cleanUp() = scope.cancel()
    }

    /** Runs the command and records both its answer and the instant it gave it. */
    private class Run(val job: Job) {
        var outcome: TopicCommandOutcome? = null
        var thrown: Throwable? = null
        var finishedAtMillis: Long? = null
    }

    private fun Harness.start(): Run {
        lateinit var run: Run
        val job = scope.launch {
            try {
                run.outcome = command.run()
            } catch (t: Throwable) {
                run.thrown = t
                throw t
            } finally {
                run.finishedAtMillis = scheduler.currentTime
            }
        }
        run = Run(job)
        return run
    }

    /**
     * An answer that arrives while `send` is still on the stack is this request's answer.
     *
     * Registration has to precede the send for that to be true, and it is the one ordering a test
     * against a real socket would almost never produce — a server that answers before `send`
     * returns needs a same-thread loopback to happen at all.
     */
    @Test
    fun `an acknowledgement delivered inside send is correlated`() = runTest {
        val harness = Harness(this)
        harness.build()
        harness.onSend = { request ->
            harness.command.deliver(harness.ack(request.requestId, active = listOf(USD)))
            harness.store.recordFrame(USD)
        }
        val run = harness.start()
        advanceUntilIdle()

        val outcome = run.outcome as TopicCommandOutcome.Acknowledged
        assertEquals(setOf(USD), outcome.accepted)
        assertEquals(emptySet<String>(), outcome.silent)
        assertEquals(1, harness.requests.size)
        harness.cleanUp()
    }

    /**
     * A frame that overtakes the acknowledgement still counts as delivery.
     *
     * Which is only true if the generation baseline was taken before the send. Taken at the ACK,
     * this frame would be part of the baseline — already there before we asked — and the topic
     * would be reported silent forty-five seconds later despite having answered immediately.
     */
    @Test
    fun `a frame that arrives before the acknowledgement counts`() = runTest {
        val harness = Harness(this)
        harness.build()
        harness.onSend = { harness.store.recordFrame(USD) }
        val run = harness.start()
        advanceTimeBy(1_000)
        harness.command.deliver(harness.ack("r1", active = listOf(USD)))
        advanceUntilIdle()

        val outcome = run.outcome as TopicCommandOutcome.Acknowledged
        assertEquals(emptySet<String>(), outcome.silent)
        assertEquals("배달을 기다리며 마감까지 앉아 있었다", 1_000L, run.finishedAtMillis)
        harness.cleanUp()
    }

    /** One of two accepted topics speaks; the other is the one reported silent. */
    @Test
    fun `only the topics that said nothing are reported silent`() = runTest {
        val harness = Harness(this)
        harness.wanted = setOf(USD, JPY)
        harness.build()
        val run = harness.start()
        advanceTimeBy(500)
        harness.command.deliver(harness.ack("r1", active = listOf(USD, JPY)))
        advanceTimeBy(500)
        harness.store.recordFrame(USD)
        advanceUntilIdle()

        val outcome = run.outcome as TopicCommandOutcome.Acknowledged
        assertEquals(setOf(USD, JPY), outcome.accepted)
        assertEquals(setOf(JPY), outcome.silent)
        assertEquals(TopicCommandPurpose.FIRST_DELIVERY, outcome.purpose)
        harness.cleanUp()
    }

    /**
     * The acknowledgement proves the command was heard and buys no more time to arrive in.
     *
     * `ANDROID_V2_PLAN.md §7 S3` calls the delivery deadline absolute, and this is what absolute
     * means: an ACK at nineteen seconds still leaves twenty-six, not another forty-five.
     */
    @Test
    fun `an acknowledgement does not extend the delivery deadline`() = runTest {
        val harness = Harness(this)
        harness.build()
        val run = harness.start()
        advanceTimeBy(19_000)
        harness.command.deliver(harness.ack("r1", active = listOf(USD)))
        advanceUntilIdle()

        val outcome = run.outcome as TopicCommandOutcome.Acknowledged
        assertEquals(setOf(USD), outcome.silent)
        assertEquals(DELIVERY_TIMEOUT_MS, run.finishedAtMillis)
        harness.cleanUp()
    }

    /**
     * A lease renewal is finished by the acknowledgement.
     *
     * Its question is "may we still receive", and the server answering it *is* the answer. The
     * other two purposes ask whether anything is arriving and so wait out the delivery deadline —
     * reusing one of them here would hold the session's control slot for forty-five seconds after
     * the renewal was done. Found by review.
     */
    @Test
    fun `a lease renewal ends at its acknowledgement`() = runTest {
        val harness = Harness(this)
        harness.build(TopicCommandPurpose.LEASE_RENEWAL)
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.ack("r1", active = listOf(USD)))
        advanceTimeBy(1)

        val outcome = run.outcome as TopicCommandOutcome.Acknowledged
        assertEquals(setOf(USD), outcome.accepted)
        // The ACK instant itself, not a tick later: nothing is waited for after it.
        assertEquals("갱신이 배달을 기다렸다", 100L, run.finishedAtMillis)
        // USD has said nothing and its generation has not moved — `an acknowledgement alone does
        // not satisfy the delivery watchdog` proves the same setup reports it silent for a first
        // delivery. A renewal reports nothing because it never waited to find out.
        assertEquals(
            "기다리지도 않고 침묵을 보고했다",
            emptySet<String>(), outcome.silent
        )
        harness.cleanUp()
    }

    /** A revalidation says so in its answer, because silence means something different for it. */
    @Test
    fun `a revalidation reports its own purpose`() = runTest {
        val harness = Harness(this)
        harness.build(TopicCommandPurpose.REVALIDATION)
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.ack("r1", active = listOf(USD)))
        advanceUntilIdle()

        assertEquals(
            TopicCommandPurpose.REVALIDATION,
            (run.outcome as TopicCommandOutcome.Acknowledged).purpose
        )
        harness.cleanUp()
    }

    /**
     * A revalidation whose topic starts speaking again while it waits does not ask.
     *
     * The frame answers the only question the revalidation was created to ask, so a retry after it
     * would be a subscribe sent to confirm something already confirmed.
     */
    @Test
    fun `a revalidation stops when its topic speaks again while it waits`() = runTest {
        val harness = Harness(this)
        harness.sendSucceeds = listOf(false)
        harness.build(TopicCommandPurpose.REVALIDATION)
        val run = harness.start()
        advanceTimeBy(1)
        assertEquals(1, harness.requests.size)

        harness.store.recordFrame(USD)
        advanceTimeBy(COOLDOWN_MS + 1)

        assertEquals("이미 답한 topic 에 다시 물었다", 1, harness.requests.size)
        assertEquals(TopicCommandOutcome.AlreadyDelivering(setOf(USD)), run.outcome)
        harness.cleanUp()
    }

    /**
     * A first delivery in the same position **does** ask again.
     *
     * Frames arriving say nothing about a control request that went unanswered: without an
     * acknowledgement there is no lease and no confirmed subscription, so the command still has
     * something to find out.
     */
    @Test
    fun `a first delivery still asks even after a frame arrives`() = runTest {
        val harness = Harness(this)
        harness.sendSucceeds = listOf(false)
        harness.build(TopicCommandPurpose.FIRST_DELIVERY)
        val run = harness.start()
        advanceTimeBy(1)
        harness.store.recordFrame(USD)
        advanceTimeBy(COOLDOWN_MS + 1)

        assertEquals(2, harness.requests.size)
        assertNull(run.outcome)
        harness.cleanUp()
    }

    /**
     * Acquiring a credential suspends, and the scope can empty while it does.
     *
     * The command asked before the wait and, until this was found, not after it — so a topic
     * dropped during the wait was subscribed to by an attempt that had already been overtaken.
     * Found by review.
     */
    @Test
    fun `a scope that empties during the token wait sends nothing`() = runTest {
        val harness = Harness(this)
        harness.credentialGateMillis = 1_000
        harness.build()
        val run = harness.start()
        advanceTimeBy(500)
        harness.wanted = emptySet()
        advanceTimeBy(1_000)

        assertEquals("토큰을 기다리는 동안 사라진 의도로 보냈다", 0, harness.requests.size)
        assertEquals(TopicCommandOutcome.Superseded, run.outcome)
        harness.cleanUp()
    }

    /** The same window, for the evidence a revalidation was waiting for. */
    @Test
    fun `a revalidation whose topic speaks during the token wait sends nothing`() = runTest {
        val harness = Harness(this)
        harness.credentialGateMillis = 1_000
        harness.build(TopicCommandPurpose.REVALIDATION)
        val run = harness.start()
        advanceTimeBy(500)
        harness.store.recordFrame(USD)
        advanceTimeBy(1_000)

        assertEquals(0, harness.requests.size)
        assertEquals(TopicCommandOutcome.AlreadyDelivering(setOf(USD)), run.outcome)
        harness.cleanUp()
    }

    /**
     * An acknowledgement that arrives after the deadline has passed is late, not on time.
     *
     * The wait is measured on the coroutine's clock and the deadline on the monotonic one, and
     * across sleep those disagree. Until this was found the deadline was only read *before* the
     * wait, so a wait that slept straight through its own deadline came back holding a frame and
     * the frame was accepted. Found by review.
     */
    @Test
    fun `an acknowledgement that arrives after the deadline is not accepted`() = runTest {
        val harness = Harness(this)
        harness.build()
        val run = harness.start()
        advanceTimeBy(1)
        assertEquals(1, harness.requests.size)

        harness.clockSkewMillis = 30_001
        harness.command.deliver(harness.ack("r1", active = listOf(USD)))
        advanceTimeBy(1)

        assertEquals("마감이 지난 ACK 를 받아들였다", false, harness.store.snapshot.stateFor(USD).confirmed)
        assertNull(run.outcome)
        assertTrue(harness.acknowledgements.isEmpty())

        advanceTimeBy(COOLDOWN_MS + 1)
        assertEquals(2, harness.requests.size)
        harness.cleanUp()
    }

    /**
     * The acknowledgement is handed over when it arrives, not when the delivery deadline expires.
     *
     * A lease can be shorter than the deadline — a second, or zero meaning *re-authenticate now* —
     * and a caller told about it forty-five seconds later cannot act on it at all. Found by review.
     */
    @Test
    fun `the acknowledgement is handed over the moment it is validated`() = runTest {
        val harness = Harness(this)
        harness.build()
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.ack("r1", active = listOf(USD), leaseSeconds = 1))
        advanceTimeBy(1)

        val (at, handed) = harness.acknowledgements.single()
        assertEquals("배달 마감까지 붙들고 있었다", 100L, at)
        assertEquals(100L, handed.acknowledgedAtMillis)
        assertEquals(setOf(USD), handed.accepted)
        assertEquals(1L, handed.leases.single().durationSeconds)
        assertNull("아직 배달 마감 전이다", run.outcome)

        advanceUntilIdle()
        assertEquals(DELIVERY_TIMEOUT_MS, run.finishedAtMillis)
        harness.cleanUp()
    }

    /** A refusal reaches the caller immediately too, rather than behind another topic's silence. */
    @Test
    fun `a refusal is handed over without waiting on another topic`() = runTest {
        val harness = Harness(this)
        harness.wanted = setOf(USD, JPY)
        harness.build()
        harness.start()
        advanceTimeBy(100)
        harness.command.deliver(
            harness.ack("r1", active = listOf(USD), rejected = mapOf(JPY to "premium_required"))
        )
        advanceTimeBy(1)

        val (at, handed) = harness.acknowledgements.single()
        assertEquals(100L, at)
        assertEquals(mapOf(JPY to TopicRejectionReason.PREMIUM_REQUIRED), handed.rejected)
        harness.cleanUp()
    }

    /**
     * A batch revalidation keeps the recovery of each topic separately.
     *
     * One topic answering during a cooldown is evidence about that topic, and a per-send baseline
     * absorbed it into the next attempt: both topics were resent and both came back silent, so a
     * topic that had plainly recovered was reported as one that had not. Found by review.
     */
    @Test
    fun `a batch revalidation does not lose a topic that recovered during a cooldown`() = runTest {
        val harness = Harness(this)
        harness.wanted = setOf(USD, JPY)
        harness.sendSucceeds = listOf(false)
        harness.build(TopicCommandPurpose.REVALIDATION)
        val run = harness.start()
        advanceTimeBy(1)
        assertEquals(setOf(USD, JPY), harness.requests.single().topics.toSet())

        harness.store.recordFrame(USD)
        advanceTimeBy(COOLDOWN_MS + 1)
        assertEquals("이미 답한 topic 을 다시 물었다", listOf(JPY), harness.requests[1].topics)

        harness.command.deliver(harness.ack("r2", active = listOf(JPY)))
        advanceUntilIdle()
        assertEquals(setOf(JPY), (run.outcome as TopicCommandOutcome.Acknowledged).silent)
        harness.cleanUp()
    }

    /** A command that gave up leaves no request reading as in flight. */
    @Test
    fun `a spent budget closes the request rather than leaving it pending`() = runTest {
        val harness = Harness(this)
        harness.sendSucceeds = listOf(false, false, false)
        harness.build()
        val run = harness.start()
        advanceUntilIdle()

        assertEquals(
            TopicCommandOutcome.Stopped(TopicWholeRequestDecision.Stop.Reason.BUDGET_SPENT),
            run.outcome
        )
        assertEquals(TopicControlState.FAILED, harness.store.snapshot.controlState)
        harness.cleanUp()
    }

    /**
     * A cancelled command releases its own request, and only its own.
     *
     * Cancelling used to leave `PENDING` standing with nothing behind it, because tidying it away
     * was unsafe while "is it still `PENDING`?" was the only test of ownership. The ticket makes
     * it safe: the replacement below keeps its request. Found by review.
     */
    @Test
    fun `a cancelled command releases its own request and leaves a replacement alone`() = runTest {
        val harness = Harness(this)
        harness.build()
        val run = harness.start()
        advanceTimeBy(1)
        assertEquals(TopicControlState.PENDING, harness.store.snapshot.controlState)

        run.job.cancel()
        advanceTimeBy(1)
        assertEquals(TopicControlState.IDLE, harness.store.snapshot.controlState)

        // …and a command torn down beside its replacement does not take the replacement with it.
        val second = Harness(this)
        second.build()
        val replaced = second.start()
        advanceTimeBy(1)
        val replacement = second.store.beginRequest()
        replaced.job.cancel()
        advanceTimeBy(1)
        assertEquals(TopicControlState.PENDING, second.store.snapshot.controlState)
        second.store.giveUpRequest(replacement)

        harness.cleanUp()
        second.cleanUp()
    }

    /** …and one that stopped because nobody wants it is not a failure. */
    @Test
    fun `a superseded command leaves the request idle`() = runTest {
        val harness = Harness(this)
        harness.sendSucceeds = listOf(false)
        harness.build()
        val run = harness.start()
        advanceTimeBy(1)
        harness.wanted = emptySet()
        advanceUntilIdle()

        assertEquals(TopicCommandOutcome.Superseded, run.outcome)
        assertEquals(TopicControlState.IDLE, harness.store.snapshot.controlState)
        harness.cleanUp()
    }

    /** A credential being refreshed is neither resolved nor finished, and says so while it runs. */
    @Test
    fun `authentication reads as refreshing while its one replay is being fetched`() = runTest {
        val harness = Harness(this)
        harness.refreshGateMillis = 1_000
        harness.build()
        harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.error("r1", "invalid_token"))
        advanceTimeBy(500)

        assertEquals(TopicAuthResolution.REFRESHING, harness.store.snapshot.authResolution)
        advanceTimeBy(1_000)
        assertEquals(2, harness.requests.size)
        harness.cleanUp()
    }

    /**
     * A recovery that finished leaves nothing reading as still recovering.
     *
     * The refresh produced a credential, the replay then went unanswered and the budget ran out —
     * and the store was left on `REFRESHING` with no fetch behind it. `REFRESHING` describes a
     * fetch in progress, so it ends when the fetch does. Found by review.
     */
    @Test
    fun `a refresh that outlives its command does not leave authentication refreshing`() = runTest {
        val harness = Harness(this)
        harness.build()
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.error("r1", "invalid_token"))
        advanceUntilIdle()

        assertEquals(
            TopicCommandOutcome.Stopped(TopicWholeRequestDecision.Stop.Reason.BUDGET_SPENT),
            run.outcome
        )
        assertEquals(
            "복구가 끝났는데 아직 갱신 중으로 읽힌다",
            TopicAuthResolution.RESOLVED,
            harness.store.snapshot.authResolution
        )
        harness.cleanUp()
    }

    /** A refresh that produced nothing usable converges back to a failed credential. */
    @Test
    fun `an unusable refresh leaves authentication failed`() = runTest {
        val harness = Harness(this)
        harness.refreshed = null
        harness.build()
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.error("r1", "invalid_token"))
        advanceTimeBy(1)

        assertEquals(TopicAuthResolution.FAILED, harness.store.snapshot.authResolution)
        assertEquals(
            TopicCommandOutcome.Stopped(TopicWholeRequestDecision.Stop.Reason.AUTH_REPLAY_SPENT),
            run.outcome
        )
        harness.cleanUp()
    }

    /**
     * A refusal the server can only reach after checking identity **is** evidence of a credential.
     *
     * `app/topic_dispatcher.py:715` authorises before topics are resolved, so `premium_required`
     * — and `unknown_topic`, `topic_unavailable`, `krx_entitlement_required` — are all decided
     * past that line. Reading only the accepted list was too narrow: a premium refusal would have
     * left a credential the server had just validated looking unproven. Found by review.
     */
    @Test
    fun `a post-identity refusal resolves a failed authentication`() = runTest {
        val harness = Harness(this)
        harness.build()
        harness.store.applyWholeFailure(TopicWholeRequestFailure.InvalidToken)
        harness.start()
        advanceTimeBy(100)
        harness.command.deliver(
            harness.ack("r1", active = emptyList(), rejected = mapOf(USD to "premium_required"))
        )
        advanceTimeBy(1)

        assertEquals(TopicAuthResolution.RESOLVED, harness.store.snapshot.authResolution)
        harness.cleanUp()
    }

    /**
     * `topics_disabled` is the one refusal decided **before** identity, and it resolves nothing.
     *
     * The flag is checked at `app/topic_dispatcher.py:600`, above the authorisation call, and
     * `:623` refuses every topic there. `TopicSubscriptionStateTest`'s `pre-auth topics-disabled
     * ack cannot resolve failed authentication` is the same contract at the store.
     */
    @Test
    fun `a pre-identity refusal leaves a failed authentication alone`() = runTest {
        val harness = Harness(this)
        harness.build()
        harness.store.applyWholeFailure(TopicWholeRequestFailure.InvalidToken)
        harness.start()
        advanceTimeBy(100)
        harness.command.deliver(
            harness.ack("r1", active = emptyList(), rejected = mapOf(USD to "topics_disabled"))
        )
        advanceTimeBy(1)

        assertEquals(
            "인증 전에 결정되는 거절이 인증을 해결된 것으로 만들었다",
            TopicAuthResolution.FAILED,
            harness.store.snapshot.authResolution
        )
        harness.cleanUp()
    }

    /**
     * A refusal for a topic this request never asked about is not this request's refusal.
     *
     * The store filters by the sent topics already; the callback did not, so a stray
     * `premium_required` reached the session as though this subscribe had been refused — and a
     * session that treats one of those as a global downgrade would act on somebody else's answer.
     * Found by review.
     */
    @Test
    fun `a refusal outside the request is not handed to the caller`() = runTest {
        val harness = Harness(this)
        harness.wanted = setOf(USD)
        harness.build()
        harness.start()
        advanceTimeBy(100)
        harness.command.deliver(
            harness.ack("r1", active = listOf(USD), rejected = mapOf(JPY to "premium_required"))
        )
        advanceTimeBy(1)

        assertEquals(emptyMap<String, TopicRejectionReason>(), harness.acknowledgements.single().second.rejected)
        harness.cleanUp()
    }

    /**
     * A command cancelled before its send neither sends nor claims the store.
     *
     * The cancellation check used to sit *after* `beginRequest`, so a cancelled command took the
     * replacement's ticket, handed it to its own `finally`, and released the replacement's request
     * on the way out. Sending nothing was never the whole contract. Found by review.
     */
    @Test
    fun `a command cancelled before its send neither sends nor takes the replacement's request`() =
        runTest {
            val harness = Harness(this)
            harness.build()
            val run = harness.start()

            val replacement = harness.store.beginRequest()
            harness.onScope = { run.job.cancel() }
            advanceTimeBy(1)

            assertEquals("취소된 command 가 보냈다", 0, harness.requests.size)
            assertTrue(run.job.isCancelled)
            assertEquals(TopicControlState.PENDING, harness.store.snapshot.controlState)

            // The replacement's ticket still works, which is the part sending nothing hides.
            harness.store.giveUpRequest(replacement)
            assertEquals(TopicControlState.FAILED, harness.store.snapshot.controlState)
            harness.cleanUp()
        }

    /**
     * A command cancelled while its credential recovery runs does not leave it running.
     *
     * `REFRESHING` describes a fetch in progress, and the command that started it is the only one
     * that can say it stopped. Cancelled, it used to say nothing and the store stayed mid-recovery
     * for good. Found by review.
     */
    @Test
    fun `a command cancelled during its refresh does not leave one running`() = runTest {
        val harness = Harness(this)
        harness.refreshGateMillis = 1_000
        harness.build()
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.error("r1", "invalid_token"))
        advanceTimeBy(100)
        assertEquals(TopicAuthResolution.REFRESHING, harness.store.snapshot.authResolution)

        run.job.cancel()
        advanceTimeBy(1)
        assertEquals(
            "취소됐는데 갱신 중으로 남았다",
            TopicAuthResolution.FAILED,
            harness.store.snapshot.authResolution
        )
        harness.cleanUp()
    }

    /**
     * A command its own callback cancelled does not go on to answer.
     *
     * The callback is where a session tears itself down on a refusal, and the branch after it can
     * return without suspending again — so cancellation went unobserved and the command handed
     * back an ordinary acknowledgement. Found by review.
     */
    @Test
    fun `a command cancelled by its own acknowledgement callback does not answer`() = runTest {
        val harness = Harness(this)
        harness.build()
        val run = harness.start()
        harness.onAcknowledgement = { run.job.cancel() }
        advanceTimeBy(100)
        harness.command.deliver(harness.ack("r1", active = emptyList()))
        advanceTimeBy(1)

        assertNull("취소됐는데 정상 결과를 냈다", run.outcome)
        assertTrue(run.job.isCancelled)
        harness.cleanUp()
    }

    /** A draw the retry policy refuses is a caller bug, not a reason to wait the plain interval. */
    @Test
    fun `a jitter draw the policy refuses stops the command`() = runTest {
        val harness = Harness(this)
        harness.sendSucceeds = listOf(false)
        harness.jitterUnit = Double.NaN
        harness.build()
        val run = harness.start()
        advanceUntilIdle()

        assertEquals("정책이 거부한 대기를 기본값으로 대신했다", 1, harness.requests.size)
        assertEquals(
            TopicCommandOutcome.Stopped(TopicWholeRequestDecision.Stop.Reason.PROGRAMMING_ERROR),
            run.outcome
        )
        harness.cleanUp()
    }

    /** A topic dropped while the delivery deadline ran is not reported as one to act on. */
    @Test
    fun `a topic dropped during the delivery wait is not reported silent`() = runTest {
        val harness = Harness(this)
        harness.wanted = setOf(USD, JPY)
        harness.build()
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.ack("r1", active = listOf(USD, JPY)))
        advanceTimeBy(1_000)
        harness.wanted = setOf(USD)
        advanceUntilIdle()

        val outcome = run.outcome as TopicCommandOutcome.Acknowledged
        assertEquals(setOf(USD, JPY), outcome.accepted)
        assertEquals(setOf(USD), outcome.silent)
        harness.cleanUp()
    }

    /** An answer to an attempt already thrown away is not this request's answer. */
    @Test
    fun `an answer carrying a discarded request id is ignored`() = runTest {
        val harness = Harness(this)
        harness.build()
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.ack("r0", active = listOf(USD)))

        // Still unanswered, so the first attempt's ACK deadline decided it — and the cooldown
        // after it, not the answer, is what put the second request on the wire.
        advanceTimeBy(ACK_TIMEOUT_MS + COOLDOWN_MS + 1)
        assertEquals(2, harness.requests.size)
        assertEquals(listOf("r1", "r2"), harness.requests.map { it.requestId })
        assertNull(run.outcome)
        harness.cleanUp()
    }

    /** An error with no request id cannot be attributed, so it is not consumed by this command. */
    @Test
    fun `an error without a request id is not claimed`() = runTest {
        val harness = Harness(this)
        harness.build()
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.error(null, "invalid_request"))
        advanceTimeBy(1_000)

        assertNull("귀속할 수 없는 오류를 이 command 가 먹었다", run.outcome)
        harness.cleanUp()
    }

    /**
     * A half-written lease fails the whole control frame closed.
     *
     * Not the topic, and not the lease alone: `ANDROID_V2_PLAN.md §7 S3` says the store does not
     * move. So the request stays pending and its ACK deadline decides — the same as never having
     * been answered, which is the safe reading of an answer this client could not parse.
     */
    @Test
    fun `a malformed lease leaves the store and the request untouched`() = runTest {
        val harness = Harness(this)
        harness.build()
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.ackWithBrokenLease("r1", USD))
        advanceTimeBy(500)

        assertEquals("확인되지 않았어야 한다", false, harness.store.snapshot.stateFor(USD).confirmed)
        assertNull(run.outcome)

        // …and the deadline, not the frame, is what ends the attempt.
        advanceTimeBy(ACK_TIMEOUT_MS + COOLDOWN_MS + 1)
        assertEquals(2, harness.requests.size)
        harness.cleanUp()
    }

    /**
     * An error this client does not recognise changes nothing — including the attempt count.
     *
     * The matrix answers `Ignore` and the contract is that nothing was consumed before asking it,
     * so the request goes back to waiting rather than starting a second attempt early.
     */
    @Test
    fun `an unrecognised error is isolated and the request keeps waiting`() = runTest {
        val harness = Harness(this)
        harness.build()
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.error("r1", "nobody_has_ever_sent_this"))
        advanceTimeBy(1_000)
        assertEquals("무시해야 할 오류로 재시도했다", 1, harness.requests.size)

        advanceTimeBy(ACK_TIMEOUT_MS + COOLDOWN_MS + 1)
        assertEquals(2, harness.requests.size)
        assertNull(run.outcome)
        harness.cleanUp()
    }

    /**
     * Send failures, silences and `temporarily_unavailable` all draw on one budget of three.
     *
     * Separate budgets would multiply: three of each is nine sends for one subscribe.
     */
    @Test
    fun `mixed retryable failures share one budget of three`() = runTest {
        val harness = Harness(this)
        harness.sendSucceeds = listOf(false)
        harness.build()
        val run = harness.start()

        // 1: the send never left. 2: answered `temporarily_unavailable`. 3: nobody answered.
        advanceTimeBy(COOLDOWN_MS + 1)
        assertEquals(2, harness.requests.size)
        harness.command.deliver(harness.error("r2", "temporarily_unavailable", retryAfter = 1))
        advanceTimeBy(1_001)
        assertEquals(3, harness.requests.size)

        advanceTimeBy(ACK_TIMEOUT_MS + COOLDOWN_MS + 1)
        assertEquals("예산이 셋을 넘겼다", 3, harness.requests.size)
        assertEquals(
            TopicCommandOutcome.Stopped(TopicWholeRequestDecision.Stop.Reason.BUDGET_SPENT),
            run.outcome
        )
        harness.cleanUp()
    }

    /** A failure before the send costs an attempt too, so three of them leave no send behind. */
    @Test
    fun `a credential failure spends an attempt without sending`() = runTest {
        val harness = Harness(this)
        harness.credentialFailures = 3
        harness.build()
        val run = harness.start()
        advanceUntilIdle()

        assertEquals("보낸 적 없는데 요청이 있다", 0, harness.requests.size)
        assertEquals(
            TopicCommandOutcome.Stopped(TopicWholeRequestDecision.Stop.Reason.BUDGET_SPENT),
            run.outcome
        )
        harness.cleanUp()
    }

    /**
     * `invalid_token` buys one forced refresh, and the replay is one of the three.
     *
     * The refresh is asked for with the snapshot that was actually refused — the provider shares
     * one refresh between callers by that key, and a different snapshot would ask for a different
     * one.
     */
    @Test
    fun `invalid_token refreshes once and replays inside the budget`() = runTest {
        val harness = Harness(this)
        harness.build()
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.error("r1", "invalid_token"))
        advanceTimeBy(1)

        assertEquals(listOf(harness.credential), harness.refreshedFrom)
        assertEquals("교체는 즉시다 — 서버가 준 대기가 없다", 2, harness.requests.size)
        assertEquals("갱신된 토큰으로 보내지 않았다", "token-2", harness.requests[1].idToken)

        // A second refusal has no licence left.
        harness.command.deliver(harness.error("r2", "invalid_token"))
        advanceTimeBy(1)
        assertEquals(
            TopicCommandOutcome.Stopped(
                TopicWholeRequestDecision.Stop.Reason.AUTH_REPLAY_SPENT
            ),
            run.outcome
        )
        harness.cleanUp()
    }

    /** A refresh that cannot produce a usable credential ends the command rather than replaying. */
    @Test
    fun `an unusable refresh stops the command`() = runTest {
        val harness = Harness(this)
        harness.refreshed = null
        harness.build()
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.error("r1", "invalid_token"))
        advanceUntilIdle()

        assertEquals(
            TopicCommandOutcome.Stopped(
                TopicWholeRequestDecision.Stop.Reason.AUTH_REPLAY_SPENT
            ),
            run.outcome
        )
        assertEquals(1, harness.requests.size)
        harness.cleanUp()
    }

    /** The account moving is a cancellation, not a failure to retry around. */
    @Test
    fun `an identity change is not swallowed`() = runTest {
        val harness = Harness(this)
        harness.identityChanges = 1
        harness.build()
        val run = harness.start()
        advanceUntilIdle()

        assertTrue(
            "AuthIdentityChangedException 이 결과로 삼켜졌다",
            run.thrown is AuthIdentityChangedException
        )
        assertNull(run.outcome)
        assertEquals(0, harness.requests.size)
        harness.cleanUp()
    }

    /**
     * A retry that waited longer than the intent lasted does not send.
     *
     * The scope is asked again after every wait, so a topic dropped while a cooldown was running
     * is not resubscribed to by an attempt that was already in flight when it was dropped.
     */
    @Test
    fun `a retry whose scope emptied while it waited sends nothing`() = runTest {
        val harness = Harness(this)
        harness.sendSucceeds = listOf(false)
        harness.build()
        val run = harness.start()
        advanceTimeBy(1)
        assertEquals(1, harness.requests.size)

        harness.wanted = emptySet()
        advanceUntilIdle()
        assertEquals("의도가 사라졌는데 다시 보냈다", 1, harness.requests.size)
        assertEquals(TopicCommandOutcome.Superseded, run.outcome)
        harness.cleanUp()
    }

    /** Refusals land in the store, and an ACK that accepted nothing still ends the command. */
    @Test
    fun `a request refused for every topic is acknowledged with nothing accepted`() = runTest {
        val harness = Harness(this)
        harness.wanted = setOf(USD, JPY)
        harness.build()
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(
            harness.ack(
                "r1",
                active = emptyList(),
                rejected = mapOf(USD to "premium_required", JPY to "topic_unavailable")
            )
        )
        advanceUntilIdle()

        val outcome = run.outcome as TopicCommandOutcome.Acknowledged
        assertEquals(emptySet<String>(), outcome.accepted)
        assertEquals(emptySet<String>(), outcome.silent)
        assertEquals("거절만 받았는데 배달을 기다렸다", 100L, run.finishedAtMillis)
        assertEquals(
            TopicRejectionReason.PREMIUM_REQUIRED,
            harness.store.snapshot.stateFor(USD).rejection
        )
        assertEquals(
            TopicRejectionReason.TOPIC_UNAVAILABLE,
            harness.store.snapshot.stateFor(JPY).rejection
        )
        harness.cleanUp()
    }

    /** The validated leases come out, because scheduling their renewal is somebody else's job. */
    @Test
    fun `validated leases are handed to the caller`() = runTest {
        val harness = Harness(this)
        harness.build()
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.ack("r1", active = listOf(USD), leaseSeconds = 900))
        harness.store.recordFrame(USD)
        advanceUntilIdle()

        val outcome = run.outcome as TopicCommandOutcome.Acknowledged
        assertEquals(1, outcome.leases.size)
        assertEquals(USD, outcome.leases.single().topic)
        assertEquals(900L, outcome.leases.single().durationSeconds)
        harness.cleanUp()
    }

    /**
     * A wait that spanned sleep is not credited with the time it slept through.
     *
     * Production's clock counts deep sleep and its waits do not, so a `delay` can return with the
     * deadline long gone. The loop re-reads the clock instead of trusting the wait, and here the
     * clock is pushed thirty seconds ahead of the scheduler to prove it: the ACK deadline has
     * passed even though only a moment of virtual time has been spent waiting.
     */
    @Test
    fun `a wait that spanned sleep is not credited with the time it slept`() = runTest {
        val harness = Harness(this)
        harness.build()
        val run = harness.start()
        advanceTimeBy(1)
        assertEquals(1, harness.requests.size)

        harness.clockSkewMillis = 30_000
        harness.command.deliver(harness.error("r1", "nobody_has_ever_sent_this"))
        advanceTimeBy(COOLDOWN_MS + 1)

        assertEquals("깨어나서 마감을 다시 읽지 않았다", 2, harness.requests.size)
        assertNull(run.outcome)
        harness.cleanUp()
    }

    /** Delivery is the topic's own generation moving, and an acknowledgement does not move it. */
    @Test
    fun `an acknowledgement alone does not satisfy the delivery watchdog`() = runTest {
        val harness = Harness(this)
        harness.build()
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.ack("r1", active = listOf(USD)))
        advanceUntilIdle()

        assertEquals(setOf(USD), (run.outcome as TopicCommandOutcome.Acknowledged).silent)
        assertEquals(
            TopicDeliveryState.NEVER_RECEIVED,
            harness.store.snapshot.stateFor(USD).deliveryState
        )
        harness.cleanUp()
    }

    // ---- the application admission (L-4c) -------------------------------------------------------

    /**
     * An acknowledgement refused at application is not applied and not handed over, and the command unwinds.
     *
     * Its request is still released on the way out: what `run`'s cleanup owns is the ticket, and a refusal is no reason to
     * leave `PENDING` standing.
     */
    @Test
    fun `an acknowledgement refused at application reaches neither the store nor the caller`() = runTest {
        val harness = Harness(this)
        harness.admission = { TopicAnswerAdmission.Denied(TopicAnswerDenial.IDENTITY_LOST) }
        harness.build()
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(
            harness.ack("r1", active = listOf(USD), rejected = mapOf(JPY to "premium_required"), leaseSeconds = 900)
        )
        advanceUntilIdle()

        assertEquals(
            TopicAnswerDenial.IDENTITY_LOST,
            (run.thrown as TopicAnswerNotAdmittedException).reason
        )
        assertNull(run.outcome)
        assertEquals("거절된 ACK 가 넘어갔다", 0, harness.acknowledgements.size)
        val snapshot = harness.store.snapshot
        assertEquals("거절된 ACK 가 적용됐거나 요청이 남았다", TopicControlState.IDLE, snapshot.controlState)
        assertEquals(false, snapshot.stateFor(USD).confirmed)
        assertEquals(listOf(false), harness.admissions)
        harness.cleanUp()
    }

    /**
     * A classified failure refused at application writes nothing and starts no refresh; answers that are set aside before
     * that are never asked about at all.
     */
    @Test
    fun `a failure refused at application is neither recorded nor refreshed`() = runTest {
        val harness = Harness(this)
        harness.admission = { TopicAnswerAdmission.Denied(TopicAnswerDenial.LEASE_EXPIRED) }
        harness.build()
        val run = harness.start()
        advanceTimeBy(100)

        harness.command.deliver(harness.ack("r0", active = listOf(USD)))
        harness.command.deliver(harness.error("r1", "no_such_error"))
        harness.command.deliver(harness.ackWithBrokenLease("r1", USD))
        advanceTimeBy(100)
        assertEquals("무시할 답이 적용 판정을 구동했다", emptyList<Boolean>(), harness.admissions)
        assertNull(run.outcome)
        assertNull(run.thrown)

        harness.command.deliver(harness.error("r1", "invalid_token"))
        advanceUntilIdle()

        assertEquals(
            TopicAnswerDenial.LEASE_EXPIRED,
            (run.thrown as TopicAnswerNotAdmittedException).reason
        )
        assertEquals(listOf(false), harness.admissions)
        assertEquals("거절된 실패로 갱신을 불렀다", emptyList<AuthSnapshot>(), harness.refreshedFrom)
        assertEquals(1, harness.requests.size)
        val snapshot = harness.store.snapshot
        assertNull("거절된 실패가 기록됐다", snapshot.wholeFailure)
        assertEquals(TopicAuthResolution.RESOLVED, snapshot.authResolution)
        assertEquals(TopicControlState.IDLE, snapshot.controlState)
        harness.cleanUp()
    }

    /**
     * The refresh suspended, so what it returns is a new answer: refused, the recovery it would have completed is abandoned
     * instead — `REFRESHING` gives way to the failure that started it — and nothing is replayed.
     */
    @Test
    fun `a refresh result refused at application abandons its recovery and replays nothing`() = runTest {
        val harness = Harness(this)
        harness.refreshGateMillis = 1_000
        harness.admission = {
            if (harness.admissions.size == 1) TopicAnswerAdmission.Admitted(harness.clock.nowMillis())
            else TopicAnswerAdmission.Denied(TopicAnswerDenial.ENDED_CONNECTION)
        }
        harness.build()
        val run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.error("r1", "invalid_token"))
        advanceTimeBy(500)
        assertEquals(TopicAuthResolution.REFRESHING, harness.store.snapshot.authResolution)

        advanceUntilIdle()

        assertEquals(
            TopicAnswerDenial.ENDED_CONNECTION,
            (run.thrown as TopicAnswerNotAdmittedException).reason
        )
        assertEquals(listOf(false, false), harness.admissions)
        assertEquals("거절된 갱신 결과로 replay 했다", 1, harness.requests.size)
        assertEquals(
            "거절된 갱신이 복구를 끝낸 것으로 남았다",
            TopicAuthResolution.FAILED,
            harness.store.snapshot.authResolution
        )
        harness.cleanUp()
    }

    /**
     * A command torn down as its refresh returns does not ask whether to apply the result.
     *
     * The refresh can return without suspending again, so the cancellation is not delivered by a resumption; it has to be
     * read before the question. Asked anyway, a session's admission would drive the live identity for a command that is gone.
     */
    @Test
    fun `a command cancelled as its refresh returns does not ask to apply the result`() = runTest {
        val harness = Harness(this)
        harness.build()
        lateinit var run: Run
        harness.onRefreshReturn = { run.job.cancel() }
        run = harness.start()
        advanceTimeBy(100)
        harness.command.deliver(harness.error("r1", "invalid_token"))
        advanceUntilIdle()

        assertEquals("취소된 명령이 갱신 결과의 적용을 물었다", listOf(false), harness.admissions)
        assertEquals(1, harness.requests.size)
        assertEquals(TopicAuthResolution.FAILED, harness.store.snapshot.authResolution)
        harness.cleanUp()
    }

    /** The premium exception is asked for exactly the refusals the session latches on: `premium_required`, for a topic this request sent. */
    @Test
    fun `the premium exception is asked only for a refusal of a topic this request sent`() = runTest {
        listOf(
            harnessAck(rejected = mapOf(USD to "premium_required"), active = emptyList()) to listOf(true),
            harnessAck(rejected = mapOf(JPY to "premium_required"), active = listOf(USD)) to listOf(false),
            harnessAck(rejected = mapOf(USD to "krx_entitlement_required"), active = emptyList()) to listOf(false)
        ).forEach { (answer, expected) ->
            val harness = Harness(this)
            harness.build(TopicCommandPurpose.LEASE_RENEWAL)
            harness.start()
            advanceTimeBy(100)
            harness.command.deliver(answer(harness))
            advanceUntilIdle()
            assertEquals(expected, harness.admissions)
            harness.cleanUp()
        }
    }

    private fun harnessAck(rejected: Map<String, String>, active: List<String>): (Harness) -> DecodedTopicFrame =
        { harness -> harness.ack("r1", active = active, rejected = rejected) }

    /**
     * Each application is asked with what its own answer refuses among the topics this request sent, and nothing else (L-4e E2a).
     *
     * A session hands these refusals over even when the use is withheld, so the map has to be the one the store would apply: every
     * reason, not only `premium_required`, and never a refusal of a topic this request did not send. A failure and a refresh result
     * refuse no topic.
     */
    @Test
    fun `each application is asked with its own answer's refusals of the topics this request sent`() = runTest {
        val eur = "fx:eur-krw"
        val ack = Harness(this)
        ack.wanted = setOf(USD, eur)
        ack.build(TopicCommandPurpose.LEASE_RENEWAL)
        ack.start()
        advanceTimeBy(100)
        ack.command.deliver(
            ack.ack(
                "r1",
                active = emptyList(),
                rejected = mapOf(USD to "krx_entitlement_required", eur to "premium_required", JPY to "premium_required")
            )
        )
        advanceUntilIdle()
        assertEquals(
            listOf(mapOf(USD to TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED, eur to TopicRejectionReason.PREMIUM_REQUIRED)),
            ack.admittedRejections
        )
        ack.cleanUp()

        val failure = Harness(this)
        failure.refreshed = null
        failure.build()
        failure.start()
        advanceTimeBy(100)
        failure.command.deliver(failure.error("r1", "invalid_token"))
        advanceUntilIdle()
        assertEquals(
            "실패·갱신 결과의 적용 판정이 거부를 실었다",
            listOf(emptyMap<String, TopicRejectionReason>(), emptyMap()),
            failure.admittedRejections
        )
        failure.cleanUp()
    }

    /**
     * The acknowledgement carries the instant it was admitted at, not a second reading of the clock.
     *
     * Read inside the deadline and admitted past it: the deadline was judged on receipt and is not judged again, and the
     * lease a session installs from this acknowledgement starts at the instant it was applied.
     */
    @Test
    fun `an acknowledgement is stamped with the instant it was admitted at`() = runTest {
        val harness = Harness(this)
        harness.admission = { TopicAnswerAdmission.Admitted(ACK_TIMEOUT_MS + 1) }
        harness.build(TopicCommandPurpose.LEASE_RENEWAL)
        val run = harness.start()
        advanceTimeBy(ACK_TIMEOUT_MS - 1)
        harness.command.deliver(harness.ack("r1", active = listOf(USD), leaseSeconds = 900))
        advanceTimeBy(1)

        assertTrue(run.outcome is TopicCommandOutcome.Acknowledged)
        assertEquals(ACK_TIMEOUT_MS + 1, harness.acknowledgements.single().second.acknowledgedAtMillis)
        harness.cleanUp()
    }
}
