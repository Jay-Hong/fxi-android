package com.jay.fxi.ui.subscription

import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.GraphBucket
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphSource
import com.jay.fxi.domain.model.GraphSourceData
import com.jay.fxi.domain.model.SupportedCurrency
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.datetime.Clock

/**
 * 샘플 데이터 생성 (체험용 LockedPreviewScreen)
 * iOS SampleData.swift 와 동일한 기준값/알고리즘 사용
 */
object SampleData {

    // ── 기준 환율 (iOS baseRates 동일) ──

    private val baseRates = mapOf(
        SupportedCurrency.USD_KRW to 1435.55,
        SupportedCurrency.JPY_KRW to 945.80,
        SupportedCurrency.EUR_KRW to 1620.30
    )

    // ── 은행별 고정 오프셋 (iOS bankDiffs 동일) ──

    private val bankDiffs = mapOf(
        Bank.INVESTING to 0.00,
        Bank.KB to 0.15,
        Bank.HANA to 0.35,
        Bank.SHINHAN to 0.25,
        Bank.WOORI to -0.10,
        Bank.IBK to 0.10,
        Bank.NH to 0.35,
        Bank.SC to -0.15,
        Bank.BS to 0.30,
        Bank.CITI to 0.20
    )

    // ── 공식 엔트리포인트 ──

    /**
     * 초기 샘플 환율 생성 (iOS initialRates 동일)
     */
    fun initialRates(currency: SupportedCurrency = SupportedCurrency.USD_KRW): List<ExchangeRate> {
        val base = baseRates[currency] ?: return emptyList()
        val now = Clock.System.now()

        return Bank.sortedEntries.map { bank ->
            val diff = bankDiffs[bank] ?: 0.0
            ExchangeRate(
                currency = currency.code,
                bank = bank.code,
                rate = base + diff,
                timestamp = now
            )
        }
    }

    fun initialRatesAll(): List<ExchangeRate> =
        SupportedCurrency.entries.flatMap { currency -> initialRates(currency) }

    /**
     * 24시간 샘플 그래프 생성 (iOS SampleGraphData.generate 동일)
     */
    fun generateGraph(
        currency: SupportedCurrency = SupportedCurrency.USD_KRW
    ): GraphSourceData {
        return generatePeriodGraph(currency, GraphPeriod.ONE_DAY)
    }

    fun generateAllPeriods(
        currency: SupportedCurrency = SupportedCurrency.USD_KRW
    ): Map<GraphPeriod, GraphSourceData> {
        return GraphPeriod.entries.associateWith { period ->
            generatePeriodGraph(currency, period)
        }
    }

    fun generatePeriodGraph(
        currency: SupportedCurrency,
        period: GraphPeriod
    ): GraphSourceData {
        val baseRate = graphBaseRate(currency)
        val now = Clock.System.now()
        val (bucketCount, bucketDuration) = when (period) {
            GraphPeriod.ONE_DAY -> 144 to 600L
            GraphPeriod.ONE_WEEK -> 168 to 3_600L
            GraphPeriod.THREE_MONTHS -> 92 to 86_400L
            GraphPeriod.ONE_YEAR -> 52 to 7 * 86_400L
        }

        // 시간대별 변동성 (시장 움직임 + 캔들 스프레드에서 공유)
        val volatility = generateMarketVolatility(bucketCount)
        // 공통 시장 움직임 (동일 volatility 주입)
        val marketMovement = generateMarketMovement(bucketCount, baseRate, volatility)

        val rateSources = when (period) {
            GraphPeriod.ONE_DAY -> GraphSource.realtimeSources
            else -> listOf(GraphSource.REFERENCE)
        }

        val rateData = rateSources.associate { source ->
            val sourceOffset = when (source) {
                GraphSource.INVESTING -> 0.0
                GraphSource.KB -> 0.20
                GraphSource.HANA -> 0.40
                GraphSource.REFERENCE -> 0.0
                else -> 0.0
            }
            val sensitivity = when (source) {
                GraphSource.INVESTING -> 1.0
                GraphSource.KB -> 0.85
                GraphSource.HANA -> 0.90
                GraphSource.REFERENCE -> 1.0
                else -> 1.0
            }

            val buckets = (0 until bucketCount).map { i ->
                val bucketTs = (now.epochSeconds - (bucketCount - 1 - i) * bucketDuration).toInt()
                val marketRate = marketMovement[i]
                val deviation = (marketRate - baseRate) * sensitivity
                val individualNoise = Random.nextDouble(-0.08, 0.08)
                val close = baseRate + sourceOffset + deviation + individualNoise

                val vol = volatility[i]
                val spread = 0.15 + vol * 0.25
                val maxRate = close + Random.nextDouble(spread * 0.3, spread)
                val minRate = close - Random.nextDouble(spread * 0.3, spread)

                GraphBucket(
                    bucketTs = bucketTs,
                    max = maxRate,
                    min = minRate,
                    close = close
                )
            }
            source.code to buckets
        }

        if (currency != SupportedCurrency.USD_KRW) {
            return rateData
        }

        // DXY는 환율과 양의 상관관계 (USD 강세 → 환율↑ & DXY↑)
        // 단기(1일): ~0.4, 장기(1주~1년): ~0.6 (KRW은 DXY 바스켓에 미포함, EM 고유 요인 존재)
        val dxyBase = 103.4
        val correlation = when (period) {
            GraphPeriod.ONE_DAY -> 0.8
            GraphPeriod.ONE_WEEK -> 0.8
            else -> 0.8
        }
        // DXY 자체 독립 움직임 (환율과 무관한 EUR/JPY 등 영향)
        val dxyOwnVolatility = generateMarketVolatility(bucketCount)
        val dxyOwnMovement = generateMarketMovement(bucketCount, dxyBase, dxyOwnVolatility.map { it * 0.12 })
        val dxyData = (0 until bucketCount).map { i ->
            val bucketTs = (now.epochSeconds - (bucketCount - 1 - i) * bucketDuration).toInt()
            // 환율 연동 성분 + DXY 독립 성분
            val rateDeviation = (marketMovement[i] - baseRate) / baseRate
            val correlatedPart = dxyBase * (1.0 + rateDeviation * correlation)
            val ownPart = dxyOwnMovement[i] - dxyBase
            val close = correlatedPart + ownPart * (1.0 - correlation)
            val spread = if (period == GraphPeriod.ONE_DAY) 0.06 else 0.12
            GraphBucket(
                bucketTs = bucketTs,
                max = close + Random.nextDouble(spread * 0.3, spread),
                min = close - Random.nextDouble(spread * 0.3, spread),
                close = close
            )
        }

        return rateData + mapOf(GraphSource.DXY.code to dxyData)
    }

    // ── 기존 호출부 호환 래퍼 ──

    fun sampleRates(
        currency: SupportedCurrency = SupportedCurrency.USD_KRW
    ): List<ExchangeRate> = initialRates(currency)

    fun sampleGraphData(
        currency: SupportedCurrency = SupportedCurrency.USD_KRW
    ): GraphSourceData = generateGraph(currency)

    // ══════════════════════════════════════════
    // 환율 랜덤화 (iOS SampleExchangeData.randomize 동일)
    // ══════════════════════════════════════════

    data class RandomizeResult(
        val rates: List<ExchangeRate>,
        val changedBanks: Set<Bank>
    )

    data class RandomizeAllResult(
        val rates: List<ExchangeRate>,
        val changedBanksByCurrency: Map<SupportedCurrency, Set<Bank>>
    )

    /**
     * 환율 변동 시뮬레이션 (1~3개 은행만 변동, 평균 회귀 확률 적용)
     * iOS SampleExchangeData.randomize() 동일 알고리즘
     */
    fun randomize(rates: List<ExchangeRate>): RandomizeResult {
        val now = Clock.System.now()
        val changeCount = Random.nextInt(1, 4) // 1~3

        // 주요 은행 개별 확률
        val banksToChange = mutableSetOf<Bank>()
        if (Random.nextBoolean()) banksToChange.add(Bank.INVESTING)       // 50%
        if (Random.nextDouble() < 0.35) banksToChange.add(Bank.KB)        // 35%
        if (Random.nextDouble() < 0.40) banksToChange.add(Bank.HANA)      // 40%

        // 나머지 은행 랜덤 채우기
        val frequentBanks = setOf(Bank.INVESTING, Bank.KB, Bank.HANA)
        val otherBanks = Bank.entries.filter { it !in frequentBanks }.shuffled()
        val remaining = max(0, changeCount - banksToChange.size)
        banksToChange.addAll(otherBanks.take(remaining))

        // 1단계: 인베스팅 새 환율 계산
        var newInvestingRate = rates.firstOrNull { it.bank == Bank.INVESTING.code }?.rate
            ?: return RandomizeResult(rates, emptySet())
        if (Bank.INVESTING in banksToChange) {
            newInvestingRate += gaussianRandom(
                stdDev = Tuning.INVESTING_STD_DEV,
                clamp = Tuning.INVESTING_CLAMP
            )
        }

        // 2단계: 모든 은행 평균 회귀 + 범위 제한
        val newRates = rates.map { rate ->
            if (rate.bank == Bank.INVESTING.code) {
                return@map rate.copy(rate = newInvestingRate, timestamp = now)
            }

            var newRate = rate.rate

            val bank = Bank.fromCode(rate.bank)
            if (bank != null && bank in banksToChange) {
                val diff = rate.rate - newInvestingRate
                val absDiff = abs(diff)
                val diffRatio = min(1.0, absDiff / Tuning.REVERSION_SCALE)

                val revertProbability = Tuning.MIN_PROB + diffRatio * (Tuning.MAX_PROB - Tuning.MIN_PROB)
                val shouldRevert = Random.nextDouble() < revertProbability
                val isActualRevert = shouldRevert && absDiff > Tuning.ZERO_EPS

                val direction: Double = if (isActualRevert) {
                    if (diff > 0) -1.0 else 1.0
                } else {
                    if (Random.nextBoolean()) 1.0 else -1.0
                }

                val magnitude: Double = if (isActualRevert) {
                    if (absDiff < Tuning.SMALL_DIFF_THRESHOLD) {
                        val ratio = absDiff / Tuning.SMALL_DIFF_THRESHOLD
                        Tuning.SMALL_MAG_MIN + (Tuning.SMALL_MAG_MAX - Tuning.SMALL_MAG_MIN) * ratio
                    } else {
                        val largeDiffRange = Tuning.REVERSION_SCALE - Tuning.SMALL_DIFF_THRESHOLD
                        val ratio = min(1.0, (absDiff - Tuning.SMALL_DIFF_THRESHOLD) / largeDiffRange)
                        Tuning.LARGE_MAG_MIN + (Tuning.LARGE_MAG_MAX - Tuning.LARGE_MAG_MIN) * ratio
                    }
                } else {
                    Random.nextDouble(Tuning.WANDER_MIN, Tuning.WANDER_MAX)
                }

                newRate += magnitude * direction
            }

            // 범위 제한
            val minAllowed = newInvestingRate - Tuning.MAX_DIFF
            val maxAllowed = newInvestingRate + Tuning.MAX_DIFF
            newRate = max(minAllowed, min(maxAllowed, newRate))

            rate.copy(rate = newRate, timestamp = now)
        }

        return RandomizeResult(newRates, banksToChange)
    }

    fun randomizeAll(rates: List<ExchangeRate>): RandomizeAllResult {
        val updatedRates = mutableListOf<ExchangeRate>()
        val changedBanksByCurrency = mutableMapOf<SupportedCurrency, Set<Bank>>()

        SupportedCurrency.entries.forEach { currency ->
            val currentRates = rates.filter { it.currency == currency.code }
            val result = randomize(currentRates)
            updatedRates += result.rates
            changedBanksByCurrency[currency] = result.changedBanks
        }

        return RandomizeAllResult(
            rates = updatedRates,
            changedBanksByCurrency = changedBanksByCurrency
        )
    }

    // ── 튜닝 파라미터 (iOS Tuning enum 동일) ──

    private object Tuning {
        const val INVESTING_STD_DEV = 0.08
        const val INVESTING_CLAMP = 0.35
        const val MIN_PROB = 0.30
        const val MAX_PROB = 0.95
        const val REVERSION_SCALE = 2.1
        const val SMALL_DIFF_THRESHOLD = 1.0
        const val SMALL_MAG_MIN = 0.08
        const val SMALL_MAG_MAX = 0.70
        const val LARGE_MAG_MIN = 0.70
        const val LARGE_MAG_MAX = 1.50
        const val WANDER_MIN = 0.05
        const val WANDER_MAX = 0.12
        const val ZERO_EPS = 0.001
        const val MAX_DIFF = 3.0
    }

    // ── 정규분포 랜덤 (Box-Muller 변환) ──

    private fun gaussianRandom(stdDev: Double, clamp: Double): Double {
        val u1 = Random.nextDouble(Double.MIN_VALUE, 1.0)
        val u2 = Random.nextDouble(0.0, 1.0)
        val gaussian = sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
        return max(-clamp, min(clamp, gaussian * stdDev))
    }

    // ══════════════════════════════════════════
    // 그래프 내부 생성 로직 (iOS SampleGraphData 동일)
    // ══════════════════════════════════════════

    private fun graphBaseRate(currency: SupportedCurrency): Double = when (currency) {
        SupportedCurrency.USD_KRW -> 1435.0
        SupportedCurrency.JPY_KRW -> 945.0
        SupportedCurrency.EUR_KRW -> 1620.0
    }

    /** 시간대별 변동성 (iOS marketVolatility 동일) */
    private fun generateMarketVolatility(bucketCount: Int): List<Double> {
        return (0 until bucketCount).map { i ->
            when {
                i < 36 -> 0.2 + Random.nextDouble(0.0, 0.15)    // 00-06시 새벽
                i < 54 -> 0.35 + Random.nextDouble(0.0, 0.20)   // 06-09시 아침
                i < 72 -> 0.6 + Random.nextDouble(0.0, 0.30)    // 09-12시 오전장
                i < 84 -> 0.4 + Random.nextDouble(0.0, 0.20)    // 12-14시 점심
                i < 108 -> 0.55 + Random.nextDouble(0.0, 0.35)  // 14-18시 오후장
                i < 126 -> 0.35 + Random.nextDouble(0.0, 0.15)  // 18-21시 저녁
                else -> 0.25 + Random.nextDouble(0.0, 0.10)     // 21-24시 밤
            }
        }
    }

    /** 공통 시장 움직임 (Random Walk + Trend + Mean Reversion) */
    private fun generateMarketMovement(
        bucketCount: Int,
        baseRate: Double,
        volatility: List<Double>
    ): List<Double> {
        val rates = DoubleArray(bucketCount)
        var currentRate = baseRate

        val overallTrend = Random.nextDouble(-2.0, 2.0)
        val trendPerBucket = overallTrend / bucketCount

        val trendChangePoints = generateTrendChangePoints(bucketCount)
        val jumpEvents = generateJumpEvents(bucketCount)

        var localTrend = trendPerBucket
        var momentum = 0.0

        for (i in 0 until bucketCount) {
            if (i in trendChangePoints) {
                localTrend = Random.nextDouble(-0.08, 0.08)
            }

            val vol = volatility[i]
            val randomStep = Random.nextDouble(-0.15, 0.15) * (1 + vol)

            momentum = momentum * 0.3 + randomStep * 0.7

            val deviation = currentRate - baseRate
            val meanReversion = -deviation * 0.02

            val jump = jumpEvents[i] ?: 0.0

            currentRate += localTrend + momentum + meanReversion + jump
            currentRate = max(baseRate - 4.0, min(baseRate + 4.0, currentRate))

            rates[i] = currentRate
        }

        return rates.toList()
    }

    /** 트렌드 전환점 (3~5개) */
    private fun generateTrendChangePoints(count: Int): Set<Int> {
        val numChanges = Random.nextInt(3, 6)
        return (0 until numChanges).map { Random.nextInt(20, count - 20) }.toSet()
    }

    /** 점프 이벤트 (2~4번, ±0.3~0.8원) */
    private fun generateJumpEvents(count: Int): Map<Int, Double> {
        val numJumps = Random.nextInt(2, 5)
        return (0 until numJumps).associate { _ ->
            val position = Random.nextInt(10, count - 10)
            val magnitude = Random.nextDouble(0.3, 0.8) * if (Random.nextBoolean()) 1.0 else -1.0
            position to magnitude
        }
    }

    private const val BUCKET_SECONDS = 600
    private const val HOURS_24_SECONDS = 24 * 60 * 60
}
