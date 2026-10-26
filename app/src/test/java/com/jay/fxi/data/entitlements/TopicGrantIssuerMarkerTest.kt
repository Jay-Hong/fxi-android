package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicCommandClock
import com.jay.fxi.data.remote.TopicGrantOrigin
import com.jay.fxi.data.remote.TopicGrantSink
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.domain.model.TopicRejectionReason
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-2b-1b-α contract tests r1 — TA-06 issuer·TGD 표지 운반(설계 r5 13절 T.7, 3절 D-21, 6.5 ④). 행 06-1~06-7, 06-O1.
 *
 * 하네스는 KrxContestHarness(무변)와 S-4다. 켬 PAC는 [CHECKED_ON]으로만 만들고 그 본문은 [checkedOnTest] 안에서 돈다. TGD는
 * [TopicGrantDeliverer]를 직접 만든다(TSCT `delivering` 꼴). 예약을 세는 시험 issuer는 2·3인자를 모두 override한다(S-5 규칙, D-21 (1)) —
 * 단 06-5는 그 규칙의 근거인 `by` 위임 특성을 고정하려고 2인자만 override한 래퍼를 쓴다.
 *
 * 표지 단언은 [PremiumAccessTopicGrantIssuer] 경로에서만 한다(D-21 (2)). 2인자 가짜(06-4)는 표지를 관측하지 않고 호환만 고정한다.
 *
 * r1 검토 반영(g3-fix): 06-O1은 '같다' 앞에 표지가 issuer 3인자·PAC 예약까지 갔는지의 창 단언을 둔다(stub에서 그 단언으로 실패). 06-2·06-3은
 * 예약 시점 스냅숏의 krxPermitSeq = 0을 premise(OFF)로 단언한다. view 헬퍼의 실패 메시지가 행 id를 담는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TopicGrantIssuerMarkerTest {

    private val active = EntitlementsOutcome.StableActive(krxVisible = true)
    private val krxTopicRefusal: Map<String, TopicRejectionReason> =
        mapOf("krx:usd-krw-futures" to TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED)

    // --- 시험 issuer·TGD ----------------------------------------------------------------------------------------------------------

    /** S-5 꼴 기록 issuer: 2·3인자를 모두 override해 실제 issuer로 넘기고, 어느 쪽으로 왔는지와 abandon을 기록한다. */
    private class RecordingIssuer(private val inner: TopicGrantIssuer) : TopicGrantIssuer {
        val twoArg = mutableListOf<TopicRejectionReservation>()
        val threeArg = mutableListOf<Pair<KrxSendMarker?, TopicRejectionReservation>>()
        val abandoned = mutableListOf<TopicRejectionReservation>()
        val reservations: List<TopicRejectionReservation> get() = (twoArg + threeArg.map { it.second }).sortedBy { it.order }

        override val accessRevisions: StateFlow<Long> get() = inner.accessRevisions
        override suspend fun topicGrantResult(): TopicGrantResult = inner.topicGrantResult()

        override fun reserveRejection(grant: TopicGrantToken, reasons: Collection<TopicRejectionReason>): TopicRejectionReservation =
            inner.reserveRejection(grant, reasons).also { twoArg += it }

        override fun reserveRejection(
            grant: TopicGrantToken,
            reasons: Collection<TopicRejectionReason>,
            marker: KrxSendMarker?
        ): TopicRejectionReservation = inner.reserveRejection(grant, reasons, marker).also { threeArg += marker to it }

        override fun abandonRejection(reservation: TopicRejectionReservation) {
            abandoned += reservation
            inner.abandonRejection(reservation)
        }

        override suspend fun onTopicRejected(reservation: TopicRejectionReservation) = inner.onTopicRejected(reservation)
    }

    /** TSCT:7612·:7894 꼴 `by` 위임 래퍼: 2인자만 override한다(06-5 특성 고정용 — 예약을 세는 시험 래퍼로 쓰면 안 되는 꼴). */
    private class TwoArgOnlyWrapper(private val inner: TopicGrantIssuer) : TopicGrantIssuer by inner {
        var twoArg = 0
        override fun reserveRejection(grant: TopicGrantToken, reasons: Collection<TopicRejectionReason>): TopicRejectionReservation {
            twoArg += 1
            return inner.reserveRejection(grant, reasons)
        }
    }

    /** TSCT:8224-8240 꼴 2인자만 구현한 가짜(3인자는 인터페이스 기본 구현). */
    private class TwoArgOnlyFake : TopicGrantIssuer {
        val ledger = TopicRejectionLedger(AtomicLong(0L)::incrementAndGet)
        var twoArg = 0
        override val accessRevisions: StateFlow<Long> = MutableStateFlow(0L)
        override suspend fun topicGrantResult(): TopicGrantResult = error("06-4: 쓰지 않는다")
        override fun reserveRejection(grant: TopicGrantToken, reasons: Collection<TopicRejectionReason>): TopicRejectionReservation {
            twoArg += 1
            return ledger.reserve(grant, reasons)
        }
        override fun abandonRejection(reservation: TopicRejectionReservation) = ledger.abandon(reservation)
        override suspend fun onTopicRejected(reservation: TopicRejectionReservation) = ledger.complete(reservation, current = false)
    }

    private class RecordingSink : TopicGrantSink {
        val calls = mutableListOf<String>()
        override fun setAccess(allowed: Boolean, fence: TopicSessionFence?, origin: TopicGrantOrigin) {
            calls += "access:$allowed:${fence?.grant?.value}"
        }
        override fun accessRevised() {
            calls += "revised"
        }
    }

    private class Delivery(val deliverer: TopicGrantDeliverer, val job: Job)

    /** 시험 스케줄러 위의 TGD. 자기 scope(따로 둔 Job)에서 돈다 — PAC scope 자식 수(06-3)와 섞이지 않는다. 시작하지 않은 채 돌려준다. */
    private fun TestScope.delivery(issuer: TopicGrantIssuer): Delivery {
        val job = Job()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = object : TopicCommandClock {
            override fun nowMillis(): Long = testScheduler.currentTime
            override suspend fun sleep(duration: Duration) = delay(duration)
        }
        val deliverer = TopicGrantDeliverer(
            issuer, RecordingSink(), AuthFenceStream { onFence -> onFence(AuthIdentityFence(KrxHarnessDefaults.OWNER, 1L)) },
            CoroutineScope(job + dispatcher), clock, deliveryDispatcher = dispatcher
        )
        return Delivery(deliverer, job)
    }

    // --- 상태 비교 ----------------------------------------------------------------------------------------------------------------

    /** 켬 PAC의 contest view. [row]는 실패 메시지에 넣을 행 id다(T.0: 모든 단언 메시지가 행을 가리킨다). */
    private fun KrxHarness.view(row: String): KrxContestView = checkNotNull(coordinator.krxContestView) { "$row premise: 켬 PAC면 view가 있다" }

    /** 상태 변화 0 비교용(PermitTest Full과 같은 항목): view, demand, RS attempts·floor·revision, 후보 수, 송신 수, epoch, latestCurrent. */
    private data class Full(
        val view: KrxContestView, val demand: String?, val attempts: Any?, val floor: Any?, val revision: Any?,
        val held: Int, val sends: Int, val epoch: String?, val latestCurrent: Long?
    )

    private suspend fun KrxHarness.full(row: String, grant: TopicGrantToken): Full {
        val rs = recheckSchedule()
        return Full(
            view(row), recheckDemandSnapshot()?.toString(), rs.readPrivate("consecutiveAttempts"), rs.readPrivate("earliestAllowedAtMillis"),
            rs.readPrivate("revision"), coordinator.heldLossCandidateCount(), source.calls.size, store.record.krxCapabilityEpoch,
            coordinator.rejectionView(grant).latestCurrent
        )
    }

    private fun KrxHarness.pending(grant: TopicGrantToken): List<Long> = coordinator.rejectionView(grant).pendingOrders

    private suspend fun TestScope.round(h: KrxHarness) {
        h.rejectKrx(h.issueGrant())
        step(100)
    }

    // --- 06-1 ----------------------------------------------------------------------------------------------------------------------

    /**
     * 06-1 실제 issuer 3인자: [PremiumAccessTopicGrantIssuer]를 켬·꺼짐 PAC 위에 두고 `reserveRejection(grant, KRX_REFUSAL, m)`.
     * 기대: 예약 marker = m, PAC 원장이 그 예약을 소유한다(그 grant의 대기 목록). 잡을 변이: I-X(3인자 override 누락 — 표지 버림).
     */
    @Test fun r06_1_realIssuerThreeArgument_keepsTheMarker_onAndOff() = checkedOnTest { h ->
        h.grantedKrxVisible()
        val grant = h.issueGrant()
        val onMarker = KrxSendMarker(h.store.record.krxCapabilityEpoch, h.view("06-1").state.permitSeq)
        val onIssuer = PremiumAccessTopicGrantIssuer(h.coordinator)
        val onReservation = onIssuer.reserveRejection(grant, KrxHarnessDefaults.KRX_REFUSAL, onMarker)
        val onPending = h.pending(grant)

        val offJob = SupervisorJob()
        try {
            val off = krxHarness(offJob, HEAD_FACTORY)
            off.grantedKrxVisible()
            val offGrant = off.issueGrant()
            val offMarker = KrxSendMarker(off.store.record.krxCapabilityEpoch, 0L)
            val offIssuer = PremiumAccessTopicGrantIssuer(off.coordinator)
            val offReservation = offIssuer.reserveRejection(offGrant, KrxHarnessDefaults.KRX_REFUSAL, offMarker)
            val offPending = off.pending(offGrant)

            assertTrue("06-1 premise(ON): 켬 예약 시점 krxPermitSeq ≥ 1", h.coordinator.accessSnapshot.krxPermitSeq >= 1L)
            assertNull("06-1 premise(OFF): 꺼짐 PAC에는 contest view가 없다", off.coordinator.krxContestView)
            assertEquals("06-1 premise(OFF): 꺼짐 예약 시점 krxPermitSeq = 0", 0L, off.coordinator.accessSnapshot.krxPermitSeq)
            assertTrue("06-1: 켬 PAC 원장이 예약을 소유한다(대기 중)", onReservation.order in onPending)
            assertTrue("06-1: 꺼짐 PAC 원장이 예약을 소유한다(대기 중)", offReservation.order in offPending)
            assertEquals("06-1: 켬 실제 issuer 3인자의 예약 marker = m", onMarker, onReservation.marker)
            assertEquals("06-1: 꺼짐 실제 issuer 3인자의 예약 marker = m", offMarker, offReservation.marker)
            offIssuer.abandonRejection(offReservation)
        } finally {
            offJob.cancel()
        }
        onIssuer.abandonRejection(onReservation)
    }

    // --- 06-2 ----------------------------------------------------------------------------------------------------------------------

    /**
     * 06-2 TGD overload: `forwardRejection(owner, reasons, m)`(TGD 표지 overload)에서 기록 issuer의 3인자가 m을 받는다. 꺼짐 PAC 위의 실제
     * issuer를 기록 issuer로 감쌌다. 잡을 변이: T-X(TGD 표지 overload가 2인자 호출).
     */
    @Test fun r06_2_delivererMarkerOverload_reachesTheIssuersThreeArgumentForm() = runTest {
        val processJob = SupervisorJob()
        var tgd: Delivery? = null
        try {
            val h = krxHarness(processJob, HEAD_FACTORY)
            h.grantedKrxVisible()
            val owner = checkNotNull(h.coordinator.topicGrantResult().fence) { "06-2 premise: 발급된 grant" }
            val issuer = RecordingIssuer(PremiumAccessTopicGrantIssuer(h.coordinator))
            val d = delivery(issuer).also { tgd = it }
            val m = KrxSendMarker(h.store.record.krxCapabilityEpoch, 0L)
            val permitAtReservation = h.coordinator.accessSnapshot.krxPermitSeq
            d.deliverer.forwardRejection(owner, krxTopicRefusal, m)
            val pending = h.pending(owner.grant)

            assertNull("06-2 premise(OFF): 꺼짐 PAC", h.coordinator.krxContestView)
            assertEquals("06-2 premise(OFF): 예약 시점 스냅숏의 krxPermitSeq = 0", 0L, permitAtReservation)
            assertEquals("06-2 premise: 표지 overload 호출 하나가 예약 하나를 만들었다", 1, issuer.reservations.size)
            assertTrue("06-2 premise: 그 예약이 PAC 원장에 대기 중", issuer.reservations.single().order in pending)
            assertEquals("06-2: 기록 issuer 3인자가 m을 받았다", listOf<KrxSendMarker?>(m), issuer.threeArg.map { it.first })
            assertEquals("06-2: 2인자 호출 0", 0, issuer.twoArg.size)
            assertEquals("06-2: 그 예약의 marker = m", m, issuer.threeArg.single().second.marker)
        } finally {
            tgd?.job?.cancel()
            processJob.cancel()
        }
    }

    // --- 06-3 ----------------------------------------------------------------------------------------------------------------------

    /**
     * 06-3 TGD 중지 뒤: 시작했다가 scope가 끝난(중지된) TGD에 표지 overload를 부르면 trySend가 실패해(TopicGrantDeliverer.kt forwardRejection)
     * abandon을 같은 호출 안에서 동기로 부르고, PAC scope 자식 수는 디스패치 전·후 모두 그대로다(KW07 KrxContestWiringContractTest.kt:158-187 꼴).
     * 전제: 꺼짐 PAC. 잡을 변이: AB-X(abandon 비동기화).
     */
    @Test fun r06_3_stoppedDeliverer_markerOverload_abandonsSynchronously() = runTest {
        val processJob = SupervisorJob()
        try {
            val h = krxHarness(processJob, HEAD_FACTORY)
            h.grantedKrxVisible()
            val owner = checkNotNull(h.coordinator.topicGrantResult().fence) { "06-3 premise: 발급된 grant" }
            val issuer = RecordingIssuer(PremiumAccessTopicGrantIssuer(h.coordinator))
            val d = delivery(issuer)
            d.deliverer.start()
            step(0)
            d.job.cancel()
            step(0)
            val stopped = d.job.isCompleted
            val baseline = processJob.children.count()
            val abandonedBefore = issuer.abandoned.size
            val reservedBefore = issuer.reservations.size
            val permitAtReservation = h.coordinator.accessSnapshot.krxPermitSeq

            d.deliverer.forwardRejection(owner, krxTopicRefusal, KrxSendMarker(h.store.record.krxCapabilityEpoch, 0L))
            val abandonedInCall = issuer.abandoned.size - abandonedBefore
            val childrenInCall = processJob.children.count()
            val pendingInCall = h.pending(owner.grant)
            step(0)
            val childrenAfterDispatch = processJob.children.count()

            assertNull("06-3 premise(OFF): 꺼짐 PAC", h.coordinator.krxContestView)
            assertEquals("06-3 premise(OFF): 예약 시점 스냅숏의 krxPermitSeq = 0", 0L, permitAtReservation)
            assertTrue("06-3 premise: TGD가 중지됐다(scope 완료)", stopped)
            assertEquals("06-3 premise: 그 호출이 예약 하나를 만들었다", reservedBefore + 1, issuer.reservations.size)
            val reserved = issuer.reservations.last()
            assertEquals("06-3: abandon이 그 호출 안에서 동기로 한 번 불렸다(디스패치 전)", 1, abandonedInCall)
            assertSame("06-3: 버린 것은 그 호출의 예약", reserved, issuer.abandoned.last())
            assertTrue("06-3: 그 예약은 호출이 끝날 때 이미 끝났다(대기 목록에 없음)", reserved.order !in pendingInCall)
            assertEquals("06-3: PAC scope 자식 수 그대로(디스패치 전)", baseline, childrenInCall)
            assertEquals("06-3: PAC scope 자식 수 그대로(디스패치 뒤)", baseline, childrenAfterDispatch)
        } finally {
            processJob.cancel()
        }
    }

    // --- 06-4 ----------------------------------------------------------------------------------------------------------------------

    /**
     * 06-4 2인자 가짜 호환: TSCT:8232-8235 꼴 2인자만 구현한 가짜에 3인자를 부르면 기본 구현이 2인자로 내려가고, 예약 marker는 null이며
     * 예외가 없다(호환 고정, B5). 잡을 변이: 3인자를 추상 멤버로 만듦(이 가짜가 컴파일되지 않음 — 컴파일 수준).
     */
    @Test fun r06_4_twoArgumentOnlyFake_threeArgumentCallFallsBack() {
        val fake = TwoArgOnlyFake()
        val reservation = fake.reserveRejection(TopicGrantToken(7L), KrxHarnessDefaults.KRX_REFUSAL, KrxSendMarker("K1", 1L))
        assertEquals("06-4: 기본 구현이 2인자로 내려갔다(2인자 호출 1)", 1, fake.twoArg)
        assertTrue("06-4: 그 예약은 가짜 원장의 것", fake.ledger.owns(reservation))
        assertNull("06-4: 2인자 가짜의 예약 marker는 null(기본 구현이 표지를 버린다)", reservation.marker)
    }

    // --- 06-5 ----------------------------------------------------------------------------------------------------------------------

    /**
     * 06-5 `by inner` 위임 래퍼: 2인자만 override한 `by` 위임 래퍼(TSCT:7612·:7894 꼴)를 실제 issuer 앞에 두고 3인자를 부른다. Kotlin `by`
     * 위임은 인터페이스 기본 멤버(3인자)도 inner의 3인자로 넘기므로(비평 실측 B5) 래퍼의 2인자 override는 불리지 않고, inner가 실제 issuer면
     * 표지가 보존된다. 그래서 예약을 세는 시험 래퍼는 3인자도 override한다(S-5 규칙). (특성 고정 — 잡을 변이 없음. 실제 issuer가 3인자를
     * override하지 않으면 inner의 기본 구현이 표지를 버려 이 행이 실패한다.)
     */
    @Test fun r06_5_byDelegationWrapper_forwardsTheThreeArgumentCall_andKeepsTheMarker() = checkedOnTest { h ->
        h.grantedKrxVisible()
        val grant = h.issueGrant()
        val m = KrxSendMarker(h.store.record.krxCapabilityEpoch, h.view("06-5").state.permitSeq)
        val wrapper = TwoArgOnlyWrapper(PremiumAccessTopicGrantIssuer(h.coordinator))
        val reservation = wrapper.reserveRejection(grant, KrxHarnessDefaults.KRX_REFUSAL, m)
        val pending = h.pending(grant)

        assertTrue("06-5 premise(ON): 예약 시점 krxPermitSeq ≥ 1", h.coordinator.accessSnapshot.krxPermitSeq >= 1L)
        assertTrue("06-5 premise: 3인자 호출이 실제 PAC 원장에 예약을 남겼다", reservation.order in pending)
        assertEquals("06-5: 래퍼의 2인자 override는 불리지 않는다", 0, wrapper.twoArg)
        assertEquals("06-5: inner(실제 issuer)의 3인자로 가 표지가 보존된다", m, reservation.marker)
        wrapper.abandonRejection(reservation)
    }

    // --- 06-6 ----------------------------------------------------------------------------------------------------------------------

    /** 06-6 한 변형: 같은 준비(OPEN p = 2, epoch K, 앞 epoch K_old)에서 표지 하나의 KRX 거절을 판정한 관측. */
    private class HandoverRun(
        val label: String,
        val expectHandover: Boolean,
        val kOld: String?,
        val k: String?,
        val p: Long,
        val tokenBefore: TopicGrantToken,
        val grant: TopicGrantToken,
        val snapshotPermit: Long,
        val phaseBefore: KrxContestPhase,
        val rejectedBefore: Long,
        val before: Full,
        val after: Full,
        val ended: Boolean,
        val phaseAfter: KrxContestPhase,
        val rejectedAfter: Long
    )

    private suspend fun TestScope.handoverRun(h: KrxHarness, label: String, expectHandover: Boolean, marker: (k: String?, kOld: String?, p: Long) -> KrxSendMarker): HandoverRun {
        h.grantedKrxVisible()
        val kOld = h.store.record.krxCapabilityEpoch
        val tokenBefore = h.issueGrant()
        round(h)
        val k = h.store.record.krxCapabilityEpoch
        val p = h.view("06-6").state.permitSeq
        val grant = h.issueGrant()
        val phaseBefore = h.view("06-6").state.phase
        val rejectedBefore = h.view("06-6").rejectedPermit
        val before = h.full("06-6", grant)
        val issuer = PremiumAccessTopicGrantIssuer(h.coordinator)
        val reservation = issuer.reserveRejection(grant, KrxHarnessDefaults.KRX_REFUSAL, marker(k, kOld, p))
        issuer.onTopicRejected(reservation)
        return HandoverRun(
            label, expectHandover, kOld, k, p, tokenBefore, grant, h.coordinator.accessSnapshot.krxPermitSeq, phaseBefore,
            rejectedBefore, before, h.full("06-6", grant), reservation.order !in h.pending(grant), h.view("06-6").state.phase, h.view("06-6").rejectedPermit
        )
    }

    /**
     * 06-6 켬 인계 판정: OPEN(p = 2, epoch K, 앞 epoch K_old)에서 issuer 3인자로 표지 (K, p)·(K, p−1)·(K_old, p)의 KRX 거절을 넣는다.
     * 기대: 차례로 인계(AWAITING, rejectedPermit = p) / 상태 불변 / 상태 불변.
     * r1 구성: 세 표지를 같은 준비의 서로 다른 [CHECKED_ON] PAC에서 각각 판정한다(본 PAC는 (K, p), 나머지 둘은 본문에서 만든 PAC — 그 검사기는
     * 직접 단언한다). 한 PAC에서 차례로 넣으면 앞 인계 뒤 rejectedPermit = p라 표지를 버린 구현(I-X)도 '불변'이 되어 공허해진다.
     * 잡을 변이: I-X.
     */
    @Test fun r06_6_onHandoverJudgement_followsTheCarriedMarker() = checkedOnTest { h ->
        val extraJob = SupervisorJob()
        try {
            val stale = krxHarness(extraJob, CHECKED_ON)
            val old = krxHarness(extraJob, CHECKED_ON)
            val runs = listOf(
                handoverRun(h, "(K, p)", expectHandover = true) { k, _, p -> KrxSendMarker(k, p) },
                handoverRun(stale, "(K, p−1)", expectHandover = false) { k, _, p -> KrxSendMarker(k, p - 1) },
                handoverRun(old, "(K_old, p)", expectHandover = false) { _, kOld, p -> KrxSendMarker(kOld, p) }
            )

            for (run in runs) {
                val label = run.label
                assertTrue("06-6 $label premise: 한 바퀴로 epoch가 K_old → K로 회전했다", run.kOld != run.k)
                assertEquals("06-6 $label premise(A1): 회전 뒤 같은 토큰", run.tokenBefore, run.grant)
                assertEquals("06-6 $label premise(ON): permit 2", 2L, run.p)
                assertEquals("06-6 $label premise(ON): 판정 스냅숏의 krxPermitSeq = p", run.p, run.snapshotPermit)
                assertEquals("06-6 $label premise: 판정 전 OPEN", KrxContestPhase.OPEN, run.phaseBefore)
                assertTrue("06-6 $label premise: p는 아직 인계되지 않았다(표지가 없으면 인계될 창)", run.rejectedBefore < run.p)
                assertTrue("06-6 $label premise: 그 예약이 끝났다", run.ended)
            }
            stale.publicationCheck.assertHeld()
            old.publicationCheck.assertHeld()

            for (run in runs.filter { !it.expectHandover }) {
                assertEquals("06-6 ${run.label}: 상태 불변", run.before, run.after)
            }
            runs.single { it.expectHandover }.let { run ->
                assertEquals("06-6 ${run.label}: 인계 — AWAITING", KrxContestPhase.AWAITING, run.phaseAfter)
                assertEquals("06-6 ${run.label}: 인계 — rejectedPermit = p", run.p, run.rejectedAfter)
            }
        } finally {
            extraJob.cancel()
        }
    }

    // --- 06-7 ----------------------------------------------------------------------------------------------------------------------

    /**
     * 06-7 CAP-R07 (iv) 운반 층(6.5 ④): 3인자 overload가 있어도 TGD 2인자 `forwardRejection` → issuer → PAC 경로는 표지 없는 예약을 만든다.
     * (i) 미인계 상태에서 H1·FE 1 (ii) 이미 인계된 상태에서 같은 표지 없는 거절은 변화 없음 — PAC 판정은 2b-1a(PremiumAccessKrxPermitTest
     * P3, :224-239)와 같다. 기록 issuer는 2·3인자를 모두 override한다(어느 쪽으로 왔든 PAC에 닿은 예약의 marker를 본다).
     * 잡을 변이: 2인자 경로가 3인자 기본값으로 가며 표지·판정을 바꿈.
     */
    @Test fun r06_7_twoArgumentDelivererPath_makesAMarkerlessReservation_judgedAsBefore() = checkedOnTest { h ->
        h.grantedKrxVisible()
        round(h)
        val p = h.view("06-7").state.permitSeq
        val owner = checkNotNull(h.coordinator.topicGrantResult().fence) { "06-7 premise: 발급된 grant" }
        val issuer = RecordingIssuer(PremiumAccessTopicGrantIssuer(h.coordinator))
        val d = delivery(issuer)
        try {
            d.deliverer.start()
            step(0)
            assertTrue("06-7 premise(ON): p > 1(첫 바퀴로)", p > 1L)
            assertEquals("06-7 premise(ON): OPEN", KrxContestPhase.OPEN, h.view("06-7").state.phase)
            assertTrue("06-7 premise: p는 아직 인계되지 않았다", h.view("06-7").rejectedPermit < p)
            val epoch = h.store.record.krxCapabilityEpoch
            val callsBefore = h.source.calls.size
            val feAnswer = CompletableDeferred<EntitlementsOutcome>()
            h.source.gates.addLast(feAnswer)

            d.deliverer.forwardRejection(owner, krxTopicRefusal)
            step(100)
            assertEquals("06-7 (i) premise: 거절 하나가 예약됐다", 1, issuer.reservations.size)
            val first = issuer.reservations.single()
            assertTrue("06-7 (i) premise: 그 예약이 처리돼 끝났다", first.order !in h.pending(owner.grant))
            assertEquals("06-7 (i) contract(06-7): H1의 FE가 시작돼 답을 기다린다(표지 없는 예약의 H1 인계 — α 동작)", callsBefore + 1, h.source.calls.size)
            assertNull("06-7 (i): 예약 marker null(표지 없는 예약)", first.marker)
            assertEquals("06-7 (i): H1 인계 — AWAITING", KrxContestPhase.AWAITING, h.view("06-7").state.phase)
            assertEquals("06-7 (i): H1 인계 — rejectedPermit = p", p, h.view("06-7").rejectedPermit)
            assertTrue("06-7 (i): H1은 KRX epoch를 회전했다", epoch != h.store.record.krxCapabilityEpoch)

            val before = h.full("06-7", owner.grant)
            d.deliverer.forwardRejection(owner, krxTopicRefusal)
            step(0)
            assertEquals("06-7 (ii) premise: 두 번째 거절이 예약됐다", 2, issuer.reservations.size)
            val dup = issuer.reservations[1]
            assertTrue("06-7 (ii) premise: 그 예약이 처리돼 끝났다", dup.order !in h.pending(owner.grant))
            assertNull("06-7 (ii): 예약 marker null", dup.marker)
            assertEquals("06-7 (ii): 이미 인계된 허가의 표지 없는 거절은 변화 없음", before, h.full("06-7", owner.grant))

            feAnswer.complete(active)
            settleKrx()
            assertEquals("06-7 (i): FE는 정확히 1", callsBefore + 1, h.source.calls.size)
            assertEquals("06-7 (i): 그 FE의 true(T2)에서 허가 p + 1", p + 1, h.view("06-7").state.permitSeq)
        } finally {
            d.job.cancel()
        }
    }

    // --- 06-O1 ---------------------------------------------------------------------------------------------------------------------

    private class OffRun(
        val trace: List<String>,
        val published: List<TopicAccessSnapshot>,
        val reservations: Int,
        val stillPending: Int,
        val epochMovedBeforeSecond: Boolean,
        val threeArgCalls: Int,
        /** issuer 3인자가 받은 표지, 받은 순서대로(창 확인 — 표지가 issuer까지 갔는가). */
        val threeArgMarkers: List<KrxSendMarker?>,
        /** PAC 원장에 닿은 예약의 marker, 예약 순번 순(창 확인 — 표지가 PAC 예약까지 갔는가). */
        val reservationMarkers: List<KrxSendMarker?>,
        /** 표지 실행이 실은 표지(2인자 실행이면 null). */
        val marker: KrxSendMarker?
    )

    /**
     * 꺼짐 PAC + 실제 issuer(기록 래퍼) + TGD. 같은 입력열: granted → TGD 시작 → KRX 거절(표지 (현재 epoch, 0)) → 정착 → 다시 발급된 grant의
     * KRX 거절(표지 epoch는 회전 전 값 — 꺼짐이 표지를 판정에 쓰면 갈리는 입력) → 정착. [withMarker]면 TGD 표지 overload, 아니면 2인자.
     */
    private fun offRun(withMarker: Boolean): OffRun {
        var result: OffRun? = null
        runTest {
            val processJob = SupervisorJob()
            var tgd: Delivery? = null
            try {
                val h = krxHarness(processJob, HEAD_FACTORY)
                val published = h.coordinator.observePublications()
                val trace = mutableListOf<String>()
                h.grantedKrxVisible()
                val issuer = RecordingIssuer(PremiumAccessTopicGrantIssuer(h.coordinator))
                val d = delivery(issuer).also { tgd = it }
                d.deliverer.start()
                step(0)
                trace += h.traceLine("TGD 시작")
                val owner1 = checkNotNull(h.coordinator.topicGrantResult().fence) { "06-O1 premise: 발급된 grant" }
                val epoch1 = h.store.record.krxCapabilityEpoch
                val marker = KrxSendMarker(epoch1, 0L)
                if (withMarker) d.deliverer.forwardRejection(owner1, krxTopicRefusal, marker)
                else d.deliverer.forwardRejection(owner1, krxTopicRefusal)
                settleKrx()
                trace += h.traceLine("첫 거절 뒤")
                val owner2 = checkNotNull(h.coordinator.topicGrantResult().fence) { "06-O1 premise: 다시 발급된 grant" }
                val moved = h.store.record.krxCapabilityEpoch != epoch1
                if (withMarker) d.deliverer.forwardRejection(owner2, krxTopicRefusal, marker)
                else d.deliverer.forwardRejection(owner2, krxTopicRefusal)
                settleKrx()
                trace += h.traceLine("둘째 거절 뒤")
                val pending = issuer.reservations.count { it.order in h.pending(it.grant) }
                result = OffRun(
                    trace, published.toList(), issuer.reservations.size, pending, moved, issuer.threeArg.size,
                    issuer.threeArg.map { it.first }, issuer.reservations.map { it.marker }, if (withMarker) marker else null
                )
            } finally {
                tgd?.job?.cancel()
                processJob.cancel()
            }
        }
        return checkNotNull(result) { "06-O1 premise: 실행이 끝났다" }
    }

    /**
     * 06-O1 OFF: issuer 3인자(표지) 대 2인자, 같은 입력열. 기대: traceLine 열·발행 열이 같다. DormantTrace D7·D8은 PAC 직접 호출이고 이 행은
     * issuer·TGD 층을 더한다. 전제(OFF): 두 실행의 모든 발행에서 krxPermitSeq = 0. 잡을 변이: 꺼짐에서 표지를 판정에 씀.
     * 창 확인(r1 검토 P1): '같다'는 차이 없음 단언이므로, 그 앞에서 표지 실행의 두 거절이 실제로 issuer 3인자로 표지를 싣고 PAC 예약
     * marker = 표지에 이르렀음(06-2·06-1 꺼짐의 운반)과 2인자 실행의 예약 marker가 null임을 단언한다. 이 창 단언이 없으면 표지를 issuer·PAC
     * 앞에서 잃는 구현에서도 두 실행이 같은 2인자 실행이 되어 공허하게 통과한다. 그래서 이 행은 stub에서 창 단언으로 실패한다(α 뒤 통과).
     */
    @Test fun r06_O1_offPac_markerAndMarkerlessDeliveries_traceTheSame() {
        val marked = offRun(withMarker = true)
        val plain = offRun(withMarker = false)

        for ((label, run) in listOf("표지" to marked, "2인자" to plain)) {
            assertTrue("06-O1 premise(OFF): [$label] 발행이 있었다", run.published.isNotEmpty())
            run.published.forEach { s ->
                assertEquals("06-O1 premise(OFF): [$label] rev=${s.revision} krxPermitSeq = 0", 0L, s.krxPermitSeq)
            }
            assertEquals("06-O1 premise: [$label] 거절 둘이 예약됐다", 2, run.reservations)
            assertEquals("06-O1 premise: [$label] 두 예약 모두 처리돼 끝났다", 0, run.stillPending)
            assertTrue("06-O1 premise: [$label] 첫 거절이 KRX epoch를 회전했다(둘째 표지 epoch는 낡았다)", run.epochMovedBeforeSecond)
        }
        assertEquals("06-O1 premise: 2인자 실행은 3인자를 부르지 않았다", 0, plain.threeArgCalls)
        assertEquals("06-O1 premise: [2인자] 실은 표지 없음", null, plain.marker)
        assertTrue("06-O1 premise: [표지] 실은 표지가 있다", marked.marker != null)
        // 창(α 운반 — 06-2·06-1 꺼짐의 계약): 아래 '같다'가 표지를 실제로 실은 실행과 2인자 실행을 비교하는지 먼저 본다.
        assertEquals(
            "06-O1 창: [표지] 두 거절 모두 issuer 3인자로 표지를 실었다",
            listOf(marked.marker, marked.marker), marked.threeArgMarkers
        )
        assertEquals("06-O1 창: [표지] PAC 예약 두 건의 marker = (epoch1, 0)", listOf(marked.marker, marked.marker), marked.reservationMarkers)
        assertEquals("06-O1 창: [2인자] PAC 예약 marker 없음", listOf<KrxSendMarker?>(null, null), plain.reservationMarkers)
        assertEquals("06-O1: traceLine 열이 같다", plain.trace, marked.trace)
        assertEquals("06-O1: 발행 열이 같다", plain.published, marked.published)
    }
}
