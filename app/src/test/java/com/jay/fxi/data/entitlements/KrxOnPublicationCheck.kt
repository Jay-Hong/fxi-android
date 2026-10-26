package com.jay.fxi.data.entitlements

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * S-4 / TA-09 켬 발행 검사기(S6-2b-1b 설계 r5 2절 ②(b) — D-2 개정문, 13절 T.10): 스위치 켬 PAC의 모든 access 발행에서
 * `capabilityAllowed ⟹ krxPermitSeq ≥ 1`.
 *
 * [CHECKED_ON]이 만든 PAC는 `onAccessPublishedForTest`(PAC:132)에 이 검사기를 단다. KrxContestHarness는 바꾸지 않는다(머리 :19-24 —
 * 후보 전용 이름을 그 파일에 두지 않는다). 기존 켬 시험 넷의 factory도 그대로다(0절 O-8). 그래서 검사 범위는 α의 새 켬 클래스와 TA-09다.
 *
 * **발행 안에서 던지지 않고 기록한 뒤 끝에서 단언한다.** 훅은 PAC가 mutex를 쥔 채 스냅숏을 바꾼 직후, 개정 흐름을 쓰기 전에 부른다
 * (publishAccessSnapshotLocked). 여기서 던지면 시험 대상의 제어 흐름을 바꾼다 — 발행이 반쯤 끝난 PAC가 남고, 이어지는 실패가 원인을
 * 가린다. 그리고 그 예외가 시험에 닿는다는 보장도 없다: TopicGrantDeliverer는 `topicGrantResult()`와 `onTopicRejected()`의 Throwable을
 * 잡아 재시도하거나 건너뛰고(TopicGrantDeliverer.kt consumeGrants·forwardRejections), PAC scope의 배경 작업(HOLD 타이머 등)에서 난
 * 예외는 그 시험 본문으로 오지 않는다. α의 TA-05·TA-06 rig는 바로 그 경로를 지난다. 그래서 위반은 순서대로 기록하고,
 * [assertHeld]가 끝에서 한꺼번에 AssertionError로 실패시킨다. 끝 단언을 빠뜨리지 않도록 [checkedOnTest]가 그 단언을 구조로 붙인다.
 *
 * 꺼짐 발행은 `capabilityAllowed` 참·p = 0이 정상이므로 이 검사기는 켬 PAC에만 단다(`krxContestView != null` ⟺ 켬, PAC:107·:126-127).
 */
internal class KrxOnPublicationCheck : (TopicAccessSnapshot) -> Unit {
    private val lock = Any()
    private val seen = mutableListOf<TopicAccessSnapshot>()
    private val broken = mutableListOf<TopicAccessSnapshot>()
    private val observers = mutableListOf<(TopicAccessSnapshot) -> Unit>()

    /** 지금까지 받은 발행 전부, 받은 순서대로. */
    val published: List<TopicAccessSnapshot> get() = synchronized(lock) { seen.toList() }

    /** 불변식을 깬 발행, 받은 순서대로. */
    val violations: List<TopicAccessSnapshot> get() = synchronized(lock) { broken.toList() }

    /** 기록이 먼저다: 관측기가 던져도 그 발행의 판정은 남는다. */
    override fun invoke(snapshot: TopicAccessSnapshot) {
        val listeners = synchronized(lock) {
            seen += snapshot
            if (snapshot.facts.capabilityAllowed && snapshot.krxPermitSeq < 1) broken += snapshot
            observers.toList()
        }
        for (observer in listeners) observer(snapshot)
    }

    /** 검사기 뒤에 관측기를 더한다(09-2 합성 보존). 훅을 대입하지 않으므로 검사기가 사라지지 않는다. */
    fun observe(observer: (TopicAccessSnapshot) -> Unit) {
        synchronized(lock) { observers += observer }
    }

    /** 위반이 하나라도 있거나, 받은 발행이 [minimumPublications]보다 적으면(공허한 검사) AssertionError. */
    fun assertHeld(minimumPublications: Int = 1) {
        val all = published
        val bad = violations
        if (bad.isNotEmpty()) {
            throw AssertionError(
                "켬 불변식(capabilityAllowed ⟹ krxPermitSeq ≥ 1) 위반 ${bad.size}건 / 발행 ${all.size}건: " +
                    bad.joinToString { "rev=${it.revision} permit=${it.krxPermitSeq} capInv=${it.capabilityInvalidations}" }
            )
        }
        if (all.size < minimumPublications) {
            throw AssertionError("premise: 검사한 발행이 ${minimumPublications}건 이상이어야 한다(${all.size}건)")
        }
    }
}

/** 스위치 켬 PAC(PremiumAccessKrxA1Test.ON과 같은 인자)에 [KrxOnPublicationCheck]를 단다. */
internal val CHECKED_ON = KrxHarnessFactory { p ->
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
        orders = p.orders,
        krxContest = KrxContestSwitch(true)
    ).also { coordinator ->
        checkNotNull(coordinator.krxContestView) { "CHECKED_ON은 스위치 켬 PAC만 만든다" }
        coordinator.onAccessPublishedForTest = KrxOnPublicationCheck()
    }
}

/** 이 PAC의 검사기. 없으면(CHECKED_ON이 아니거나 시험이 훅을 직접 대입함 — KW15) AssertionError. */
internal val KrxHarness.publicationCheck: KrxOnPublicationCheck
    get() = coordinator.onAccessPublishedForTest as? KrxOnPublicationCheck
        ?: throw AssertionError("발행 검사기가 없다: CHECKED_ON PAC가 아니거나 onAccessPublishedForTest를 직접 대입했다(KW15)")

/**
 * 발행 관측: 이 호출 뒤의 발행을 순서대로 모은 목록을 돌려주고, 발행마다 [observer]도 부른다. 검사기가 달린 PAC에서는 검사기 뒤에
 * 더하고(09-2), 검사기가 없는 PAC(꺼짐 [HEAD_FACTORY] — TA-11)에서는 기존 훅 뒤에 합성한다. 새 α 켬 시험은 훅을 직접 대입하지 않고
 * 이것만 쓴다(KW15).
 */
internal fun PremiumAccessCoordinator.observePublications(
    observer: (TopicAccessSnapshot) -> Unit = {}
): List<TopicAccessSnapshot> {
    val seen = mutableListOf<TopicAccessSnapshot>()
    val record: (TopicAccessSnapshot) -> Unit = { snapshot ->
        seen += snapshot
        observer(snapshot)
    }
    when (val hook = onAccessPublishedForTest) {
        is KrxOnPublicationCheck -> hook.observe(record)
        null -> onAccessPublishedForTest = record
        else -> onAccessPublishedForTest = { snapshot -> hook(snapshot); record(snapshot) }
    }
    return seen
}

/**
 * 켬 검사 시험 하나: [CHECKED_ON] PAC로 harness를 만들어 [body]에 넘기고, body가 끝나면 그 PAC의 모든 발행을 단언한다(최소
 * [minimumPublications]건). body가 실패하면 그 실패를 그대로 던지고, 검사기 위반은 suppressed로 붙인다. PAC scope는 끝에서 취소한다.
 * body 안에서 따로 만든 PAC는 이 단언에 들어가지 않는다 — 그 PAC는 시험이 [KrxOnPublicationCheck.assertHeld]를 직접 부른다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun checkedOnTest(minimumPublications: Int = 1, body: suspend TestScope.(KrxHarness) -> Unit): TestResult = runTest {
    val processJob = SupervisorJob()
    try {
        val h = krxHarness(processJob, CHECKED_ON)
        try {
            body(h)
        } catch (failure: Throwable) {
            runCatching { h.publicationCheck.assertHeld(minimumPublications = 0) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        h.publicationCheck.assertHeld(minimumPublications)
    } finally {
        processJob.cancel()
    }
}
