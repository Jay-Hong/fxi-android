package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.domain.model.TopicRejectionReason
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals

/**
 * S6-2b0b 공용 harness (s6_2b0b_contracts.r2.md 4.0).
 *
 * HEAD(e0eef8e)에 있는 API만 쓴다. 그래서 기준 추출 트리(운영 소스가 e0eef8e 그대로)와 candidate 트리에서 같은 바이트로
 * 컴파일된다. candidate 전용 seam(스위치·observer·view)은 이 파일에 두지 않고, PAC 생성은 [KrxHarnessFactory]로 받는다.
 */
internal object KrxHarnessDefaults {
    const val OWNER = "user-a"
    const val OTHER = "user-b"
    const val RETRY = 1_000L

    /** 회복이 끝나기에 충분히 긴 가상 시간. advanceUntilIdle을 쓰지 않는다: 끝나지 않는 변이는 단언 실패로 드러나야 한다. */
    const val SETTLE = 60 * 60 * 1_000L

    val KRX_REFUSAL: List<TopicRejectionReason> = listOf(TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED)
}

/** production [AccessEpochTransitions]를 그대로 쓰는 메모리 store. 실패는 횟수로 주입한다. */
internal class KrxHarnessStore(private val ids: EpochIdGenerator) : AccessEpochStore {
    var record = AccessEpochRecord()

    /** 다음 회전 시도 n번을 쓰기 전에 실패시킨다(PAC:2266-2299의 seal 경로). */
    var rotationFailures = 0

    /** 다음 load n번을 실패시킨다(질의 후보·토픽 후보 보류, PAC:1833-1837, :1944-1948). */
    var loadFailures = 0

    /** [loadFailures]를 적용하기 전에 통과시킬 load 수. 조회 시작의 load(PAC:1572)는 통과시키고 답 적용의 load를 실패시킬 때 쓴다. */
    var loadSkipsBeforeFailure = 0

    var loads = 0
    var rotationAttempts = 0

    /** S6-2b-1a: 다음 load 한 번을 이 gate가 끝날 때까지 멈춘다(PAC 잠금 안에서 mutex를 붙잡아 두는 용도). 기본 null이면 기존과 같다. */
    var loadGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun load(): AccessEpochRecord {
        loadGate?.let { gate -> loadGate = null; gate.await() }
        loads += 1
        if (loadFailures > 0 && loadSkipsBeforeFailure > 0) {
            loadSkipsBeforeFailure -= 1
            return record
        }
        if (loadFailures > 0) {
            loadFailures -= 1
            throw IOException("load")
        }
        return record
    }
    override suspend fun bindOwner(uid: String): AccessEpochRecord =
        AccessEpochTransitions.bindOwner(record, uid, ids).also { record = it }
    /** 다음 signOut 한 번을 기록에 착지시킨 뒤 예외로 끝낸다(서명 해제 복구 경로, PAC:1203-1249). */
    var signOutLandsThenThrows = false

    override suspend fun signOut(): AccessEpochRecord {
        record = AccessEpochTransitions.signOut(record, ids)
        if (signOutLandsThenThrows) {
            signOutLandsThenThrows = false
            throw IOException("sign-out landed")
        }
        return record
    }
    override suspend fun retireUnverifiedStart(): AccessEpochRecord =
        AccessEpochTransitions.retireUnverifiedStart(record, ids).also { record = it }
    override suspend fun beginSignOut(uid: String): AccessEpochRecord =
        AccessEpochTransitions.beginSignOut(record, uid).also { record = it }
    /** S6-2b-1a: 다음 회전 시도 한 번을 이 gate가 끝날 때까지 멈춘다(결정 게시 전 취소 시점을 만드는 용도). 기본 null이면 기존과 같다. */
    var rotationGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun beginRotation(rotateUser: Boolean, rotateKrx: Boolean): AccessEpochRecord {
        rotationGate?.let { gate -> rotationGate = null; gate.await() }
        rotationAttempts += 1
        if (rotationFailures > 0) {
            rotationFailures -= 1
            throw IOException("rotation")
        }
        val landed = AccessEpochTransitions.rotate(record, rotateUser, rotateKrx, ids).also { record = it }
        landedGate?.let { gate -> landedGate = null; gate.await() }
        if (rotationLandsThenCancels > 0) {
            rotationLandsThenCancels -= 1
            throw kotlin.coroutines.cancellation.CancellationException("the store's own scope ended")
        }
        return landed
    }

    /** S6-2b-1a: 다음 회전 한 번이 착지한 뒤 반환 전에 이 gate가 끝날 때까지 멈춘다(PATST parkedAfterLanding의 ON 짝). 기본 null이면 기존과 같다. */
    var landedGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** S6-2b-1a: 다음 회전 n번이 착지한 뒤 store 자신의 취소로 끝난다(PATST cancelledAfterLanding의 ON 짝). 기본 0이면 기존과 같다. */
    var rotationLandsThenCancels = 0
    override suspend fun completePurges(completed: Collection<PendingPurge>): AccessEpochRecord =
        AccessEpochTransitions.completePurges(record, completed).also { record = it }
    override suspend fun journalRetired(obligation: LossObligation): AccessEpochRecord =
        AccessEpochTransitions.journalRetired(record, obligation).also { record = it }
    override suspend fun markMayContainData(premium: Boolean, krx: Boolean): AccessEpochRecord =
        AccessEpochTransitions.markMayContainData(record, premium, krx).also { record = it }
}

internal class KrxHarnessPurger : UserScopePurger, CapabilityScopePurger {
    var calls = 0

    /** purge 결과. 기본은 완료다. Deferred는 미확인 journal을 남겨 seal이 서명 해제를 넘어 서게 한다(K23 :1249 변형). */
    var result: PurgeResult = PurgeResult.Completed

    override suspend fun purgeUserScope(namespace: PurgeNamespace): PurgeResult = result.also { calls += 1 }
    override suspend fun purgeCapabilityScope(namespace: PurgeNamespace): PurgeResult = result.also { calls += 1 }
}

/** 조회 한 건의 기록: 몇 번째인지, 가상 시각, freshPremium. */
internal data class KrxFetchCall(val index: Int, val atMillis: Long, val freshPremium: Boolean)

/**
 * 조회마다 답을 낸다. 기본은 [next]다. [gates]에 넣은 deferred는 그 순서의 조회에 하나씩 쓰여, 시험이 응답 시각과 완료 순서를
 * 정한다(완료 순서 역전·후보 보류 시나리오).
 */
internal class KrxHarnessSource(
    private val nowMillis: () -> Long,
    var next: () -> EntitlementsOutcome
) : EntitlementsSource {
    var identity: EntitlementsIdentity? = EntitlementsIdentity(KrxHarnessDefaults.OWNER, 1L)
    var liveFence: AuthIdentityFence? = null

    /** 다음 currentIdentity n번을 null로 돌려준다(live 판독 실패, PAC:1848-1863). */
    var identityNulls = 0

    /** 다음 조회 n번을 인증 없는 답(Unauthenticated, 운영: 자격 증명 없음)으로 돌려준다. 기본 0이면 기존 기록과 같다. */
    var unauthenticatedFetches = 0

    val calls = mutableListOf<KrxFetchCall>()
    val gates = ArrayDeque<CompletableDeferred<EntitlementsOutcome>>()

    override suspend fun fetch(freshPremium: Boolean): EntitlementsResult {
        calls += KrxFetchCall(calls.size, nowMillis(), freshPremium)
        val gate = gates.removeFirstOrNull()
        val outcome = gate?.await() ?: next()
        if (unauthenticatedFetches > 0) {
            unauthenticatedFetches -= 1
            return EntitlementsResult.Unauthenticated(outcome)
        }
        return EntitlementsResult.Answered(checkNotNull(identity), outcome)
    }
    /** S6-2b-1a: 다음 currentIdentity 한 번을 이 gate가 끝날 때까지 멈춘다(잠금 안 live 판독 중 취소를 만드는 용도). 기본 null이면 기존과 같다. */
    var identityGate: CompletableDeferred<Unit>? = null

    override suspend fun currentIdentity(): EntitlementsIdentity? {
        identityGate?.let { gate -> identityGate = null; gate.await() }
        if (identityNulls > 0) {
            identityNulls -= 1
            return null
        }
        return identity
    }
}

/** harness 부품. candidate 시험은 이것으로 새 인자를 붙인 PAC를 만든다. */
internal class KrxHarnessParts(
    val scope: CoroutineScope,
    val clock: RecheckClock,
    val store: KrxHarnessStore,
    val source: KrxHarnessSource,
    val purger: KrxHarnessPurger,
    val orders: AccessOrderSequence,
    val reapprovals: MutableList<Pair<RefreshIntent, Long>>
)

internal fun interface KrxHarnessFactory {
    fun create(parts: KrxHarnessParts): PremiumAccessCoordinator
}

/** HEAD 생성자 인자만 쓰는 기본 생성. 기준 추출과 스위치 꺼짐 대조가 이것을 쓴다. */
internal val HEAD_FACTORY = KrxHarnessFactory { p ->
    PremiumAccessCoordinator(
        source = p.source,
        store = p.store,
        userPurger = p.purger,
        capabilityPurger = p.purger,
        scope = p.scope,
        clock = p.clock,
        jitter = ProbeJitter.None,
        persistenceRetryDelayMillis = KrxHarnessDefaults.RETRY,
        liveFence = { p.source.liveFence },
        onLossReapprovalScheduled = { intent, binding -> p.reapprovals += intent to binding },
        orders = p.orders
    )
}

internal class KrxHarness(val test: TestScope, val parts: KrxHarnessParts, val coordinator: PremiumAccessCoordinator) {
    val store get() = parts.store
    val source get() = parts.source
    val purger get() = parts.purger
    val reapprovals get() = parts.reapprovals
}

private fun deterministicIds(): EpochIdGenerator {
    var n = 0
    return EpochIdGenerator { "epoch-${n++}" }
}

/**
 * [onBackgroundFailure]가 있으면 PAC scope에 예외 수집기를 붙인다. 시험이 의도한 비동기 실패(예: true 답의 적용 load 실패가 RS 작업
 * 밖으로 나옴, PAC:2703)를 종류·개수까지 단언하려는 것이다. 없으면 기존처럼 수집기 없이 둔다(예외를 삼키지 않는다).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.krxHarness(
    processJob: Job,
    factory: KrxHarnessFactory = HEAD_FACTORY,
    onBackgroundFailure: ((Throwable) -> Unit)? = null
): KrxHarness {
    val clock = RecheckClock { testScheduler.currentTime }
    val context = onBackgroundFailure?.let { sink -> processJob + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> sink(e) } }
        ?: (processJob + StandardTestDispatcher(testScheduler))
    val parts = KrxHarnessParts(
        scope = CoroutineScope(context),
        clock = clock,
        store = KrxHarnessStore(deterministicIds()),
        source = KrxHarnessSource({ testScheduler.currentTime }) { EntitlementsOutcome.StableActive(krxVisible = true) },
        purger = KrxHarnessPurger(),
        orders = AccessOrderSequence(),
        reapprovals = mutableListOf()
    )
    return KrxHarness(this, parts, factory.create(parts))
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.step(millis: Long = 0L) {
    if (millis > 0L) advanceTimeBy(millis)
    runCurrent()
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.settleKrx() = step(KrxHarnessDefaults.SETTLE)

/** 바인딩·premium 확정·KRX VISIBLE(기존 seal 시험의 granted와 같은 입력). 표지를 세워 반복 상실이 다시 회전을 요구하게 한다. */
internal suspend fun KrxHarness.grantedKrxVisible() {
    coordinator.onIdentityChanged(AuthIdentityFence(KrxHarnessDefaults.OWNER, 1L)).applied()
    coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
    test.settleKrx()
    assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)
    assertEquals(KrxCapabilityState.VISIBLE, coordinator.krx.value)
    store.record = store.record.copy(mayContainPremiumData = true, mayContainKrxData = true)
}

/** 입력열에 명시하는 grant 발급(HEAD 경로 topicGrantResult, PAC:1688). 반환 fence의 grant를 예약에 쓴다. */
internal suspend fun KrxHarness.issueGrant(): TopicGrantToken = checkNotNull(coordinator.topicGrantResult().fence).grant

/** KRX 단독 거절을 지금 보고된 것으로 넣는다(PAC:1789 → :1777 예약 → :1819 처리). */
internal suspend fun KrxHarness.rejectKrx(grant: TopicGrantToken) = coordinator.onTopicRejected(grant, KrxHarnessDefaults.KRX_REFUSAL)

/**
 * H1′(live 판독 실패, 회전 없음, PAC:1848-1863): 미결정 거절. FE 요구는 5초 floor 뒤로 무장된다. 예약을 돌려준다
 * (barrier = reservation.order). KRX epoch가 그대로라 그 전에 시작한 조회의 답도 fence 검사(PAC:1957)를 통과한다.
 */
internal suspend fun KrxHarness.undecidedRejection(): TopicRejectionReservation {
    val grant = issueGrant()   // 발급도 신원을 읽으므로 실패 주입은 발급 뒤에 둔다
    val epoch = store.record.krxCapabilityEpoch
    source.identityNulls = 1
    val reservation = coordinator.reserveRejection(grant, KrxHarnessDefaults.KRX_REFUSAL)
    coordinator.onTopicRejected(reservation)
    assertEquals("premise: H1′은 회전하지 않는다", epoch, store.record.krxCapabilityEpoch)
    return reservation
}

/** S6-2b-1a: PAC의 private `recheckDemand`(순번·intent·reapprovalOnly)를 읽기 전용으로 꺼낸다. 상태 변화 0 비교용. */
internal fun KrxHarness.recheckDemandSnapshot(): Any? {
    val field = PremiumAccessCoordinator::class.java.getDeclaredField("recheckDemand")
    field.isAccessible = true
    return field.get(coordinator)
}

/** PAC의 private `schedule`을 읽기 전용으로 꺼낸다(RS 운영 파일은 바꾸지 않는다). */
internal fun KrxHarness.recheckSchedule(): RecheckSchedule {
    val field = PremiumAccessCoordinator::class.java.getDeclaredField("schedule")
    field.isAccessible = true
    return field.get(coordinator) as RecheckSchedule
}

/** RS의 private 필드 하나를 읽기 전용으로 꺼낸다. */
internal fun RecheckSchedule.readPrivate(name: String): Any? {
    val field = RecheckSchedule::class.java.getDeclaredField(name)
    field.isAccessible = true
    return field.get(this)
}

/**
 * HEAD에서도 보이는 관측값만으로 한 줄을 만든다(설계 3.1). snapshot 전체 toString은 쓰지 않는다: candidate에는 기본값 필드가
 * 하나 더 있어 문자열이 달라진다. facts와 기존 필드를 따로 적는다.
 */
internal fun KrxHarness.traceLine(label: String): String {
    val snapshot = coordinator.accessSnapshot
    val schedule = recheckSchedule()
    val lastFetch = source.calls.lastOrNull()
    return listOf(
        label,
        "krx=${coordinator.krx.value}",
        "state=${coordinator.state.value.state}",
        "rev=${snapshot.revision}",
        "facts=${snapshot.facts}",
        "inv=${snapshot.userInvalidations}",
        "uEnd=${snapshot.lastUserEnd}",
        "cEnd=${snapshot.lastCapabilityEnd}",
        "floor=${snapshot.userEndInvalidationsFloor}",
        "fetches=${source.calls.size}",
        "last=${lastFetch?.let { "${it.atMillis}:${it.freshPremium}" }}",
        "loads=${store.loads}",
        "rot=${store.rotationAttempts}",
        "rec=${store.record}",
        "purge=${purger.calls}",
        "reapp=${reapprovals}",
        "armed=${schedule.isArmed}",
        "attempts=${schedule.readPrivate("consecutiveAttempts")}",
        "floorAt=${schedule.readPrivate("earliestAllowedAtMillis")}",
        "intent=${schedule.readPrivate("pendingIntent")}",
        "rsRev=${schedule.readPrivate("revision")}"
    ).joinToString(" | ")
}

/**
 * S6-2b-1a 합성 상태(설계 r2 3.4·Codex 시험 3회차 판단 6): 같은 키·기존 grant를 그대로 둔 채 승인을 닫는다. 시작 정리(resumeStartupPurge)의
 * 첫 store.load를 실패시키면 cleanupOrHoldLocked가 CleanupPending 보류를 연다(PAC:833-845) — generation·binding을 바꾸지 않으므로 contest 키와
 * 발급된 grant가 남고 `accessAdmittedLocked()`만 거짓이 된다(PAC:351-352). 운영에서 시작 정리 보류는 첫 신원 처리 전에만 열리므로(AAB:176) 이것은
 * 운영 도달 경로가 아니다. 돌려주는 보류는 [reopenApproval]로 실제 재개 경로(resumePersistence → cleanupAndFinishLocked, PAC:890-891)를 태운다.
 */
internal suspend fun KrxHarness.closeApprovalOnTheSameKey(): IdentityStep.AwaitPersistence {
    store.loadFailures = 1
    val held = coordinator.resumeStartupPurge().heldByPersistence("시작 정리 보류(합성)")
    assertEquals("premise: 보류를 연 실패가 소비됐다", 0, store.loadFailures)
    return held
}

/** [closeApprovalOnTheSameKey]의 보류를 실제 재개 경로로 푼다. 보류의 다음 시도 시각까지 가상 시계를 옮긴다. */
internal suspend fun KrxHarness.reopenApproval(held: IdentityStep.AwaitPersistence) {
    val wait = (held.nextAttemptAt ?: test.testScheduler.currentTime) - test.testScheduler.currentTime
    if (wait > 0) test.step(wait)
    coordinator.resumePersistence(held.id).applied("같은 키 승인 재개(합성)")
}
