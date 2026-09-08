package com.jay.fxi.domain.model

/**
 * 막대 그래프 스케일 정책 스냅샷.
 *
 * 외곽값(outlier) 1~2개로 인해 cluster 해상도가 눌리는 문제를 완화한다.
 * iOS `Utils/ScalePolicy.swift`와 동등한 수식·상수를 사용한다.
 */
data class ScalePolicy(
    /** 실제 데이터의 전체 범위 */
    val rawRange: Pair<Double, Double>?,
    /** 바 스케일 분모로 쓸 범위 (robust 활성 시 clip된 bulk, 비활성 시 rawRange와 동일) */
    val displayRange: Pair<Double, Double>?,
    /** robust 모드 활성 여부 (displayRange가 raw 범위를 실제로 clip했을 때만 true) */
    val isRobustActive: Boolean
) {
    companion object {
        val empty = ScalePolicy(
            rawRange = null,
            displayRange = null,
            isRobustActive = false
        )
    }
}

// MARK: - Calculator

object ScalePolicyCalculator {
    /** 기본 활성 조건: 전체 스팬이 IQR의 몇 배를 넘으면 robust mode로 진입할지 */
    const val DEFAULT_ACTIVATION_RATIO: Double = 3.0

    /** Small-N fallback: 가시 은행 수가 이 값 미만이면 무조건 raw 모드 */
    const val DEFAULT_MIN_VISIBLE_FOR_ROBUST: Int = 5

    /**
     * Tukey fence 계수. 통계 관습 1.5보다 약간 엄격(1.2)하여 은행 호가 비교
     * 맥락에서 낮은 쪽 outlier도 놓치지 않도록 조정.
     */
    const val DEFAULT_FENCE_COEFFICIENT: Double = 1.2

    // NOTE: 현재 compute()는 stateless로 단일 임계값(activationRatio)만 사용한다.
    // 실측 결과 임계 경계에서 raw ↔ robust flapping이 관찰되면 비대칭 threshold
    // + 연속 tick 조건 기반 hysteresis를 ViewModel 측 stored state로 추가할 것.
    // 정상 outlier 시나리오의 ratio는 보통 10 이상이라 3.0 경계에서 실제 flap
    // 확률은 낮을 것으로 예상.

    /**
     * 환율 목록에서 스케일 정책 계산 (pure function).
     */
    fun compute(
        rates: List<ExchangeRate>,
        activationRatio: Double = DEFAULT_ACTIVATION_RATIO,
        minVisibleForRobust: Int = DEFAULT_MIN_VISIBLE_FOR_ROBUST
    ): ScalePolicy = computeValues(rates.map { it.rate }, activationRatio, minVisibleForRobust)

    /**
     * 값만 있는 목록에서 스케일 정책 계산 (거래소 시세처럼 `ExchangeRate` 가 아닌 탭용).
     *
     * iOS 도 같은 이유로 두 진입점을 둔다(`ScalePolicy.swift` 의 `compute(rates:)` / `compute(values:)`).
     * 은행 목록 쪽은 값을 뽑아 이리로 넘기므로 두 표면이 다른 답을 낼 수 없다.
     * 이름이 갈린 것은 JVM 에서 `List<ExchangeRate>` 와 `List<Double>` 의 시그니처가 같기 때문이다.
     */
    fun computeValues(
        values: List<Double>,
        activationRatio: Double = DEFAULT_ACTIVATION_RATIO,
        minVisibleForRobust: Int = DEFAULT_MIN_VISIBLE_FOR_ROBUST
    ): ScalePolicy {
        val rawMin = values.minOrNull() ?: return ScalePolicy.empty
        val rawMax = values.maxOrNull() ?: return ScalePolicy.empty
        val rawRange = rawMin to rawMax

        // Small-N: raw 유지
        if (values.size < minVisibleForRobust) {
            return rawFallback(rawRange)
        }

        val sorted = values.sorted()
        val q1 = percentile(sorted, 0.25)
        val q3 = percentile(sorted, 0.75)
        val iqr = q3 - q1
        val fullSpan = rawMax - rawMin

        // IQR=0 엣지 (중앙 50%가 정확히 동일한 호가):
        // fullSpan이 유의하면 중앙값 기준 tolerance로 폴백
        if (iqr == 0.0) {
            return computeWithZeroIQR(sorted, rawRange, fullSpan)
        }

        val ratio = fullSpan / iqr

        // 활성 임계 미달 → raw 유지
        if (ratio <= activationRatio) {
            return rawFallback(rawRange)
        }

        // Tukey fence (통계 1.5 대비 약간 엄격, DEFAULT_FENCE_COEFFICIENT로 튜닝)
        val fenceLow = q1 - DEFAULT_FENCE_COEFFICIENT * iqr
        val fenceHigh = q3 + DEFAULT_FENCE_COEFFICIENT * iqr
        val dispMin = maxOf(rawMin, fenceLow)
        val dispMax = minOf(rawMax, fenceHigh)

        // displayRange가 degenerate(min >= max)이면 raw 유지
        if (dispMin >= dispMax) {
            return rawFallback(rawRange)
        }

        // 실제 clip이 일어났는지 판정: 둘 중 하나라도 raw 범위 안쪽으로 들어왔으면 clip.
        // fence가 raw 범위 바깥이면 데이터에 영향 없음 → raw 폴백.
        val hasClippedRange = dispMin > rawMin || dispMax < rawMax
        if (!hasClippedRange) {
            return rawFallback(rawRange)
        }

        return ScalePolicy(
            rawRange = rawRange,
            displayRange = dispMin to dispMax,
            isRobustActive = true
        )
    }

    /**
     * IQR=0일 때(중앙 50% 동일 호가) 중앙값 기준 거리 tolerance로 폴백.
     * 예: [1395, 1408, 1408, 1408, 1408] → IQR=0이지만 SC(1395)는 여전히 outlier.
     */
    private fun computeWithZeroIQR(
        sorted: List<Double>,
        rawRange: Pair<Double, Double>,
        fullSpan: Double
    ): ScalePolicy {
        // fullSpan이 작으면 outlier 없음 → raw 유지
        // 0.1원 임계: 통화 소수점 2자리 기준 유의한 차이의 대략 10배
        if (fullSpan <= 0.1) {
            return rawFallback(rawRange)
        }

        val median = sorted[sorted.size / 2]
        // 중앙값으로부터 fullSpan의 20% 이상 떨어진 값을 outlier로.
        // tolerance는 bulk 쪽 band 폭의 절반.
        val tolerance = fullSpan * 0.2
        // 본 경로(IQR>0)와 동일하게 raw 범위로 clamp.
        // Unclamped로 두면 bulk 값이 displayRange 중앙에 모여 bar 해상도가 절반만 쓰임.
        val dispMin = maxOf(rawRange.first, median - tolerance)
        val dispMax = minOf(rawRange.second, median + tolerance)

        // clamp 후 degenerate → raw 폴백
        if (dispMin >= dispMax) {
            return rawFallback(rawRange)
        }

        // 실제 clip 여부 (본 경로와 동일 의미)
        val hasClippedRange = dispMin > rawRange.first || dispMax < rawRange.second
        if (!hasClippedRange) {
            return rawFallback(rawRange)
        }

        return ScalePolicy(
            rawRange = rawRange,
            displayRange = dispMin to dispMax,
            isRobustActive = true
        )
    }

    private fun rawFallback(rawRange: Pair<Double, Double>): ScalePolicy =
        ScalePolicy(
            rawRange = rawRange,
            displayRange = rawRange,
            isRobustActive = false
        )

    /** 선형 보간 percentile (p ∈ [0, 1]) */
    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val position = p * (sorted.size - 1)
        val lower = position.toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        val fraction = position - lower
        return sorted[lower] + fraction * (sorted[upper] - sorted[lower])
    }
}
