package com.jay.fxi.ui.subscription

import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.GraphBucket
import com.jay.fxi.domain.model.GraphSource
import com.jay.fxi.domain.model.SupportedCurrency
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.datetime.Clock

object SampleData {

    fun sampleGraphData(
        currency: SupportedCurrency = SupportedCurrency.USD_KRW
    ): Map<GraphSource, List<GraphBucket>> {
        val nowSec = Clock.System.now().epochSeconds
        val endTs = (nowSec - (nowSec % BUCKET_SECONDS)).toInt()
        val startTs = endTs - HOURS_24_SECONDS

        val base = baseRate(currency)
        val sources = listOf(GraphSource.INVESTING, GraphSource.KB, GraphSource.HANA)

        return sources.associateWith { source ->
            val random = Random(source.ordinal * 97 + 13)
            val sourceOffset = when (source) {
                GraphSource.INVESTING -> 0.0
                GraphSource.KB -> 0.6
                GraphSource.HANA -> -0.4
            }

            val buckets = mutableListOf<GraphBucket>()
            val totalPoints = HOURS_24_SECONDS / BUCKET_SECONDS
            for (i in 0 until totalPoints) {
                val ts = startTs + i * BUCKET_SECONDS
                val wave = sin(i / 10.0) * 1.1
                val noise = (random.nextDouble() - 0.5) * 0.7
                val close = base + sourceOffset + wave + noise
                val spread = 0.25 + random.nextDouble() * 0.45

                buckets.add(
                    GraphBucket(
                        bucketTs = ts,
                        max = close + spread,
                        min = close - spread,
                        close = close
                    )
                )
            }
            buckets
        }
    }

    fun sampleRates(
        currency: SupportedCurrency = SupportedCurrency.USD_KRW
    ): List<ExchangeRate> {
        val base = baseRate(currency) + 1.4
        val now = Clock.System.now()
        val random = Random(42)

        return Bank.sortedEntries.mapIndexed { index, bank ->
            val offset = when (bank) {
                Bank.INVESTING -> 0.0
                else -> (index - 2) * 0.12 + (random.nextDouble() - 0.5) * 0.08
            }
            ExchangeRate(
                currency = currency.code,
                bank = bank.code,
                rate = base + offset,
                timestamp = now
            )
        }
    }

    private fun baseRate(currency: SupportedCurrency): Double = when (currency) {
        SupportedCurrency.USD_KRW -> 1402.4
        SupportedCurrency.JPY_KRW -> 936.2
        SupportedCurrency.EUR_KRW -> 1523.7
    }

    private const val BUCKET_SECONDS = 600
    private const val HOURS_24_SECONDS = 24 * 60 * 60
}
