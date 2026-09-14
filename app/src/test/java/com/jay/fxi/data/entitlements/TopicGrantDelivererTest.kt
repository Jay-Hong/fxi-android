package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicCommandClock
import com.jay.fxi.data.remote.TopicGrantSink
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.domain.model.TopicRejectionReason
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L-4e E3: the deliverer between the issuer's topic grant and one session (`l4e_e3_design_v4.md`).
 *
 * The issuer, the session and the auth stream are fakes here; the joined behaviour is in `TopicSessionCoordinatorTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicGrantDelivererTest {

    private val f1 = TopicSessionFence(AuthIdentityFence("u1", 1L), "epoch-1", TopicGrantToken(1L))
    private val f2 = TopicSessionFence(AuthIdentityFence("u1", 1L), "epoch-1", TopicGrantToken(2L))

    private class Issuer : TopicGrantIssuer {
        val revisions = MutableStateFlow(0L)
        override val accessRevisions: StateFlow<Long> get() = revisions

        /** Answers each pull; reads [revisions] when it answers unless the answer names its own. */
        var answer: suspend () -> TopicGrantResult = { result(null, userAllowed = false) }
        var pulls = 0
        val rejected = mutableListOf<Pair<TopicGrantToken, List<TopicRejectionReason>>>()
        var onRejected: suspend (TopicGrantToken) -> Unit = {}

        override suspend fun topicGrantResult(): TopicGrantResult {
            pulls += 1
            return answer()
        }

        /** The same reservation contract as the issuer's: numbered on report, ended once however it goes (L-4e E4a). */
        val ledger = TopicRejectionLedger(AtomicLong(0L)::incrementAndGet)

        override fun reserveRejection(grant: TopicGrantToken, reasons: Collection<TopicRejectionReason>) =
            ledger.reserve(grant, reasons)

        override fun abandonRejection(reservation: TopicRejectionReservation) = ledger.abandon(reservation)

        override suspend fun onTopicRejected(reservation: TopicRejectionReservation) {
            var current = false
            try {
                onRejected(reservation.grant)
                rejected += reservation.grant to reservation.reasons
                current = true
            } finally {
                ledger.complete(reservation, current)
            }
        }

        fun result(
            fence: TopicSessionFence?,
            userAllowed: Boolean = true,
            endSequence: Long = 0L,
            revision: Long = revisions.value
        ) = TopicGrantResult(
            fence,
            TopicAccessSnapshot.INITIAL.copy(
                revision = revision,
                facts = TopicAccessFacts.NONE.copy(
                    token = fence?.grant,
                    tokenStanding = fence != null,
                    userBlocks = if (userAllowed) emptySet() else setOf(TopicAccessBlock.NOT_GRANTED)
                ),
                lastUserEnd = endSequence.takeIf { it > 0 }?.let {
                    TopicAccessEnd(it, TopicAccessEndReason.AUTHORITATIVE_LOSS, null, null, null)
                }
            )
        )
    }

    private class Sink : TopicGrantSink {
        val calls = mutableListOf<String>()
        var onAccess: () -> Unit = {}
        override fun setAccess(allowed: Boolean, fence: TopicSessionFence?) {
            calls += "access:$allowed:${fence?.grant?.value}"
            onAccess()
        }

        override fun accessRevised() {
            calls += "revised"
        }
    }

    private class Fences : AuthFenceStream {
        var callback: ((AuthIdentityFence?) -> Unit)? = null
        override fun observe(onFence: (AuthIdentityFence?) -> Unit) {
            callback = onFence
            onFence(AuthIdentityFence("u1", 1L))
        }
    }

    private class Setup(test: TestScope) {
        val issuer = Issuer()
        val sink = Sink()
        val fences = Fences()
        val job = Job()
        val scope = CoroutineScope(job + StandardTestDispatcher(test.testScheduler))
        val clock = object : TopicCommandClock {
            override fun nowMillis() = test.testScheduler.currentTime
            override suspend fun sleep(duration: Duration) = delay(duration)
        }
        val deliverer = TopicGrantDeliverer(
            issuer, sink, fences, scope, clock,
            deliveryDispatcher = StandardTestDispatcher(test.testScheduler)
        )
    }

    private fun deliverTest(body: suspend TestScope.(Setup) -> Unit) = runTest {
        val s = Setup(this)
        try {
            body(s)
        } finally {
            s.scope.cancel()
        }
    }

    @Test
    fun theStart_pullsOnce_andSettledStateStopsPulling() = deliverTest { s ->
        s.issuer.answer = { s.issuer.result(f1) }
        s.deliverer.start()
        s.deliverer.start()
        runCurrent()
        assertEquals(listOf("access:true:1", "revised"), s.sink.calls)
        val settled = s.issuer.pulls
        assertTrue("시작이 여러 번 읽었다: $settled", settled in 1..2)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals("안정된 상태에서 계속 읽었다", settled, s.issuer.pulls)
    }

    @Test
    fun aRevision_isReadAgain_andAGrantGoesOverAsAccess() = deliverTest { s ->
        s.issuer.answer = { s.issuer.result(f1) }
        s.deliverer.start()
        runCurrent()
        val before = s.issuer.pulls
        s.sink.calls.clear()

        s.issuer.answer = { s.issuer.result(f2) }
        s.issuer.revisions.value = 1L
        runCurrent()
        assertEquals(before + 1, s.issuer.pulls)
        assertEquals(listOf("access:true:2", "revised"), s.sink.calls)
    }

    @Test
    fun aPublishedBlock_goesOverAsARevisionAlone_andSettles() = deliverTest { s ->
        s.issuer.answer = { s.issuer.result(f1) }
        s.deliverer.start()
        runCurrent()
        s.sink.calls.clear()

        s.issuer.answer = { s.issuer.result(null, userAllowed = false) }
        s.issuer.revisions.value = 1L
        runCurrent()
        assertEquals("보류를 철회로 보냈다", listOf("revised"), s.sink.calls)
        val pulls = s.issuer.pulls
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals("게시된 차단을 재시도했다", pulls, s.issuer.pulls)
    }

    @Test
    fun anEndRecordedSinceTheGrant_endsIt_once() = deliverTest { s ->
        s.issuer.answer = { s.issuer.result(f1, endSequence = 2L) }
        s.deliverer.start()
        runCurrent()
        s.sink.calls.clear()

        s.issuer.answer = { s.issuer.result(null, userAllowed = false, endSequence = 3L) }
        s.issuer.revisions.value = 1L
        runCurrent()
        assertEquals(listOf("access:false:1", "revised"), s.sink.calls)

        s.issuer.revisions.value = 2L
        runCurrent()
        assertEquals("같은 종료를 다시 보냈다", listOf("access:false:1", "revised", "revised"), s.sink.calls)
    }

    @Test
    fun aMissingGrantTheSnapshotDoesNotExplain_isRevisedAndReadAgain_untilItIsAnswered() = deliverTest { s ->
        s.issuer.answer = { s.issuer.result(null, userAllowed = true) }
        s.deliverer.start()
        runCurrent()
        assertEquals(listOf("revised"), s.sink.calls)
        val first = s.issuer.pulls

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals("재시도가 일렀다", first, s.issuer.pulls)
        // The identity read recovers under the same revision and the same auth generation: no signal but the retry.
        s.issuer.answer = { s.issuer.result(f1) }
        advanceTimeBy(1)
        runCurrent()
        assertEquals(first + 1, s.issuer.pulls)
        assertEquals(listOf("revised", "access:true:1", "revised"), s.sink.calls)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals("답을 받은 뒤에도 재시도했다", first + 1, s.issuer.pulls)
    }

    @Test
    fun anEndThatMeetsAnUnexplainedMissingGrant_endsItAndStillReadsAgain() = deliverTest { s ->
        s.issuer.answer = { s.issuer.result(f1, endSequence = 1L) }
        s.deliverer.start()
        runCurrent()
        s.sink.calls.clear()

        s.issuer.answer = { s.issuer.result(null, userAllowed = true, endSequence = 2L) }
        s.issuer.revisions.value = 1L
        runCurrent()
        assertEquals(listOf("access:false:1", "revised"), s.sink.calls)
        val pulls = s.issuer.pulls
        s.issuer.answer = { s.issuer.result(f2, endSequence = 2L) }
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("종료와 겹친 판정 불가 null 을 재시도하지 않았다", pulls + 1, s.issuer.pulls)
        assertEquals(listOf("access:false:1", "revised", "access:true:2", "revised"), s.sink.calls)
    }

    @Test
    fun anEndAndANewGrantReadTogether_goOverAsTheNewGrantAlone() = deliverTest { s ->
        s.issuer.answer = { s.issuer.result(f1, endSequence = 1L) }
        s.deliverer.start()
        runCurrent()
        s.sink.calls.clear()

        s.issuer.answer = { s.issuer.result(f2, endSequence = 2L) }
        s.issuer.revisions.value = 5L
        runCurrent()
        assertEquals(listOf("access:true:2", "revised"), s.sink.calls)
    }

    @Test
    fun aResultTheIssuerMovedPast_isDroppedAndReadAgain() = deliverTest { s ->
        val gate = CompletableDeferred<Unit>()
        var first = true
        s.issuer.answer = {
            if (first) {
                first = false
                val answer = s.issuer.result(f1, revision = 0L)
                gate.await()
                answer
            } else {
                s.issuer.result(f2)
            }
        }
        s.deliverer.start()
        runCurrent()
        s.issuer.revisions.value = 1L
        gate.complete(Unit)
        runCurrent()
        assertTrue("낡은 결과를 전달했다: ${s.sink.calls}", "access:true:1" !in s.sink.calls)
        assertEquals(listOf("access:true:2", "revised"), s.sink.calls)
    }

    @Test
    fun aFailedPull_keepsWhatWasDelivered_andRetriesOnAGrowingWait() = deliverTest { s ->
        s.issuer.answer = { s.issuer.result(f1) }
        s.deliverer.start()
        runCurrent()
        s.sink.calls.clear()
        val times = mutableListOf<Long>()
        s.issuer.answer = {
            times += s.clock.nowMillis()
            throw IOException("store")
        }
        s.issuer.revisions.value = 1L
        runCurrent()
        advanceTimeBy(2_000 + 4_000 + 8_000)
        runCurrent()
        assertEquals("실패를 철회나 재평가로 보냈다", emptyList<String>(), s.sink.calls)
        assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L), times.map { it - times.first() })

        s.issuer.answer = { s.issuer.result(f1) }
        advanceTimeBy(16_000)
        runCurrent()
        assertEquals(listOf("access:true:1", "revised"), s.sink.calls)
        times.clear()
        s.issuer.answer = {
            times += s.clock.nowMillis()
            throw IOException("store")
        }
        s.issuer.revisions.value = 2L
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("성공 뒤 대기가 처음으로 돌아가지 않았다", listOf(0L, 2_000L), times.map { it - times.first() })
    }

    @Test
    fun aSignalBeforeTheRetry_readsAtOnce_andAReplacedRetryDoesNotBringTheNextOneForward() = deliverTest { s ->
        val times = mutableListOf<Long>()
        s.issuer.answer = {
            times += s.clock.nowMillis()
            throw IOException("store")
        }
        s.deliverer.start()
        runCurrent()
        advanceTimeBy(1_000)
        s.issuer.revisions.value = 1L
        runCurrent()
        // The second failure replaced the two-second wait with a four-second one from here.
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals("대체된 재시도가 다음 재시도를 앞당겼다", listOf(0L, 1_000L), times.map { it - times.first() })
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 5_000L), times.map { it - times.first() })
    }

    @Test
    fun aCancellationTheIssuerThrows_whileTheDelivererRuns_isAFailedPull() = deliverTest { s ->
        s.issuer.answer = { throw CancellationException("store cancelled") }
        s.deliverer.start()
        runCurrent()
        val first = s.issuer.pulls
        s.issuer.answer = { s.issuer.result(f1) }
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(first + 1, s.issuer.pulls)
        assertEquals(listOf("access:true:1", "revised"), s.sink.calls)
    }

    @Test
    fun anAuthTransition_isOnlyASignal_andNothingRunsAfterTheScopeEnds() = deliverTest { s ->
        s.issuer.answer = { s.issuer.result(null, userAllowed = false) }
        s.deliverer.start()
        runCurrent()
        val pulls = s.issuer.pulls
        s.fences.callback!!(AuthIdentityFence("u1", 2L))
        assertEquals("auth 콜백이 발급자를 바로 불렀다", pulls, s.issuer.pulls)
        runCurrent()
        assertEquals(pulls + 1, s.issuer.pulls)

        s.scope.cancel()
        runCurrent()
        val calls = s.sink.calls.size
        s.fences.callback!!(AuthIdentityFence("u1", 3L))
        s.issuer.revisions.value = 9L
        s.deliverer.forwardRejection(f1, mapOf("usdt:krw" to TopicRejectionReason.PREMIUM_REQUIRED))
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(pulls + 1, s.issuer.pulls)
        assertEquals(calls, s.sink.calls.size)
        assertEquals(emptyList<Pair<TopicGrantToken, List<TopicRejectionReason>>>(), s.issuer.rejected)
    }

    @Test
    fun refusals_reachTheIssuerInOrder_pastOneThatFails() = deliverTest { s ->
        s.issuer.onRejected = { grant ->
            // The first one is slow: refusals handed over side by side would let the third overtake it.
            if (grant.value == 1L) delay(1_000)
            if (grant.value == 2L) throw IOException("identity")
        }
        s.deliverer.start()
        runCurrent()
        s.deliverer.forwardRejection(f1, mapOf("usdt:krw" to TopicRejectionReason.PREMIUM_REQUIRED))
        s.deliverer.forwardRejection(f2, mapOf("fx:usd-krw" to TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED))
        s.deliverer.forwardRejection(
            TopicSessionFence(AuthIdentityFence("u1", 1L), "epoch-1", TopicGrantToken(3L)),
            mapOf("usdt:krw" to TopicRejectionReason.TOPICS_DISABLED)
        )
        runCurrent()
        assertEquals(emptyList<Pair<TopicGrantToken, List<TopicRejectionReason>>>(), s.issuer.rejected)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(
            listOf(
                TopicGrantToken(1L) to listOf(TopicRejectionReason.PREMIUM_REQUIRED),
                TopicGrantToken(3L) to listOf(TopicRejectionReason.TOPICS_DISABLED)
            ),
            s.issuer.rejected
        )
    }

    @Test
    fun aLateSignalForARevisionAlreadyRead_doesNotBringTheRetryForward() = deliverTest { s ->
        val times = mutableListOf<Long>()
        s.issuer.answer = {
            times += s.clock.nowMillis()
            throw IOException("store")
        }
        s.deliverer.start()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        // Read at once for the auth transition; the revision's own signal lands after that read has already seen revision 1.
        s.fences.callback!!(AuthIdentityFence("u1", 1L))
        s.issuer.revisions.value = 1L
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(listOf(0L, 2_000L, 2_000L, 10_000L), times)
    }

    @Test
    fun anImmediateOwnerScope_doesNotRunTheIssuerInsideTheAuthCallback() = deliverTest { s ->
        val owner = CoroutineScope(s.job + kotlinx.coroutines.Dispatchers.Unconfined)
        val deliverer = TopicGrantDeliverer(
            s.issuer, s.sink, s.fences, owner, s.clock,
            deliveryDispatcher = StandardTestDispatcher(testScheduler)
        )
        s.issuer.answer = { s.issuer.result(f1) }
        deliverer.start()
        runCurrent()
        val pulls = s.issuer.pulls
        val calls = s.sink.calls.size

        s.fences.callback!!(AuthIdentityFence("u1", 2L))
        assertEquals("auth 콜백 안에서 발급자를 실행했다", pulls, s.issuer.pulls)
        assertEquals("auth 콜백 안에서 sink 를 실행했다", calls, s.sink.calls.size)
        runCurrent()
        assertEquals(pulls + 1, s.issuer.pulls)
    }

    @Test
    fun theAuthCallback_holdsTheSignalAlone() = deliverTest { s ->
        s.deliverer.start()
        runCurrent()
        val callback = checkNotNull(s.fences.callback)
        val held = callback.javaClass.declaredFields.map { field ->
            field.isAccessible = true
            field.get(callback)
        }
        assertTrue(
            "auth 콜백이 전달자·발급자·sink 를 붙잡는다: ${held.map { it?.javaClass?.simpleName }}",
            held.none { it === s.deliverer || it === s.issuer || it === s.sink }
        )
    }

    @Test
    fun aReplacedTimerThatWritesLate_doesNotHideTheTimerThatReplacedIt() = deliverTest { s ->
        var stalled: Continuation<Unit>? = null
        var sleeps = 0
        val clock = object : TopicCommandClock {
            override fun nowMillis() = testScheduler.currentTime
            override suspend fun sleep(duration: Duration) {
                sleeps += 1
                when (sleeps) {
                    // Already past its wait when it is replaced: a plain suspension, which cancelling does not interrupt.
                    1 -> suspendCoroutine<Unit> { stalled = it }
                    // The replacement lets the first one go on just as it wakes, so the first writes after it.
                    2 -> {
                        delay(duration)
                        checkNotNull(stalled).resume(Unit)
                    }
                    else -> delay(duration)
                }
            }
        }
        val times = mutableListOf<Long>()
        s.issuer.answer = {
            times += testScheduler.currentTime
            throw IOException("store")
        }
        val deliverer = TopicGrantDeliverer(
            s.issuer, s.sink, s.fences, s.scope, clock,
            deliveryDispatcher = StandardTestDispatcher(testScheduler)
        )
        deliverer.start()
        runCurrent()
        s.fences.callback!!(AuthIdentityFence("u1", 2L))
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(listOf(0L, 0L, 4_000L), times)
    }

    @Test
    fun nothingBufferedIsReadOrHandedOver_afterTheScopeEnds() = deliverTest { s ->
        s.issuer.answer = { s.issuer.result(f1) }
        s.sink.onAccess = {
            s.fences.callback!!(AuthIdentityFence("u1", 2L))
            s.scope.cancel()
        }
        s.deliverer.start()
        runCurrent()
        assertEquals("끝난 뒤 쌓인 신호로 발급자를 다시 불렀다", 1, s.issuer.pulls)
    }

    @Test
    fun aBufferedRefusal_isNotHandedOver_afterTheScopeEnds() = deliverTest { s ->
        s.issuer.onRejected = { grant -> if (grant.value == 1L) s.scope.cancel() }
        s.deliverer.start()
        runCurrent()
        s.deliverer.forwardRejection(f1, mapOf("usdt:krw" to TopicRejectionReason.PREMIUM_REQUIRED))
        s.deliverer.forwardRejection(f2, mapOf("fx:usd-krw" to TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED))
        runCurrent()
        assertEquals(listOf(TopicGrantToken(1L) to listOf(TopicRejectionReason.PREMIUM_REQUIRED)), s.issuer.rejected)
        assertEquals("넘기지 않은 예약이 남았다", emptyList<Long>(), s.issuer.ledger.view(f2.grant).pendingOrders)
        assertEquals(emptyList<Long>(), s.issuer.ledger.view(f1.grant).pendingOrders)
    }

    @Test
    fun aRefusalReportedAfterTheDelivererStopped_isAbandonedAtOnce() = deliverTest { s ->
        s.deliverer.start()
        runCurrent()
        s.scope.cancel()
        runCurrent()
        s.deliverer.forwardRejection(f1, mapOf("usdt:krw" to TopicRejectionReason.PREMIUM_REQUIRED))
        assertEquals(emptyList<Long>(), s.issuer.ledger.view(f1.grant).pendingOrders)
        assertEquals(emptyList<Pair<TopicGrantToken, List<TopicRejectionReason>>>(), s.issuer.rejected)
    }

    @Test
    fun aReceivedRefusalCancelledBeforeResumption_isAbandoned() = deliverTest { s ->
        s.issuer.ledger.grantIssued(f1.grant)
        s.deliverer.start()
        runCurrent()
        s.deliverer.forwardRejection(f1, mapOf("usdt:krw" to TopicRejectionReason.PREMIUM_REQUIRED))
        val reported = s.issuer.ledger.view(f1.grant).latestReported
        assertTrue(reported != null)
        s.scope.cancel()
        runCurrent()
        assertTrue(s.issuer.rejected.isEmpty())
        assertEquals(TopicRejectionView(emptyList(), reported, null), s.issuer.ledger.view(f1.grant))
    }

    @Test
    fun refusalsLeftInTheBufferWhenTheScopeEnds_areAbandoned() = deliverTest { s ->
        val slow = CompletableDeferred<Unit>()
        s.issuer.onRejected = { grant -> if (grant.value == 1L) slow.await() }
        s.deliverer.start()
        runCurrent()
        val third = TopicSessionFence(AuthIdentityFence("u1", 1L), "epoch-1", TopicGrantToken(3L))
        for (fence in listOf(f1, f2, third)) {
            s.deliverer.forwardRejection(fence, mapOf("usdt:krw" to TopicRejectionReason.PREMIUM_REQUIRED))
        }
        runCurrent()
        assertEquals(1, s.issuer.ledger.view(f2.grant).pendingOrders.size)
        s.scope.cancel()
        runCurrent()
        for (fence in listOf(f1, f2, third)) {
            assertEquals("${fence.grant} 의 예약이 남았다", emptyList<Long>(), s.issuer.ledger.view(fence.grant).pendingOrders)
        }
        assertEquals(emptyList<Pair<TopicGrantToken, List<TopicRejectionReason>>>(), s.issuer.rejected)
    }

    @Test
    fun refusalsReportedToADelivererThatNeverStarted_areAbandonedWhenTheScopeEnds() = deliverTest { s ->
        s.deliverer.forwardRejection(f1, mapOf("usdt:krw" to TopicRejectionReason.PREMIUM_REQUIRED))
        assertEquals("시작 전 보고가 버퍼링되지 않았다", 1, s.issuer.ledger.view(f1.grant).pendingOrders.size)
        s.scope.cancel()
        runCurrent()
        assertEquals(emptyList<Long>(), s.issuer.ledger.view(f1.grant).pendingOrders)

        val ended = CoroutineScope(Job().also { it.cancel() } + StandardTestDispatcher(testScheduler))
        val late = TopicGrantDeliverer(s.issuer, s.sink, s.fences, ended, s.clock)
        late.forwardRejection(f2, mapOf("usdt:krw" to TopicRejectionReason.PREMIUM_REQUIRED))
        assertEquals("끝난 scope 의 전달자가 예약을 남겼다", emptyList<Long>(), s.issuer.ledger.view(f2.grant).pendingOrders)
    }

    @Test
    fun aStartCancelledBeforeItRuns_leavesNoReservation() = deliverTest { s ->
        s.deliverer.forwardRejection(f1, mapOf("usdt:krw" to TopicRejectionReason.PREMIUM_REQUIRED))
        s.deliverer.start()
        s.scope.cancel()
        runCurrent()
        assertEquals(emptyList<Long>(), s.issuer.ledger.view(f1.grant).pendingOrders)
        assertEquals(emptyList<Pair<TopicGrantToken, List<TopicRejectionReason>>>(), s.issuer.rejected)
    }

    @Test
    fun theDefaultRetryWait_doublesFromTwoSecondsUpToAMinute() = deliverTest { s ->
        val times = mutableListOf<Long>()
        s.issuer.answer = {
            times += s.clock.nowMillis()
            throw IOException("store")
        }
        s.deliverer.start()
        runCurrent()
        advanceTimeBy(2_000 + 4_000 + 8_000 + 16_000 + 32_000 + 60_000 + 60_000)
        runCurrent()
        val waits = times.zipWithNext { a, b -> b - a }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), waits)
    }
}
