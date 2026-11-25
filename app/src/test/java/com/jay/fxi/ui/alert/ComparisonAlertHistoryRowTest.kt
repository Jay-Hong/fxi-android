package com.jay.fxi.ui.alert

import com.jay.fxi.data.remote.dto.ComparisonAlertHistoryItem
import java.util.Locale
import java.util.TimeZone
import kotlinx.datetime.Instant
import org.junit.Assert.*
import org.junit.Test

internal fun delivery(id: Int = 1, tab: String = "usd", kind: String = "absolute"): ComparisonAlertHistoryItem {
    val asset = if (tab == "tether") "usdt-krw" else "$tab-krw"
    return ComparisonAlertHistoryItem(id, null, tab, if (tab == "tether") "upbit" else "hana", asset,
        if (kind == "signed") "investing" else if (tab == "tether") "bithumb" else "kb",
        if (kind == "signed") "usd-krw" else asset, kind, "lte", 3.0, 1400.0, 1398.0, -2.0,
        null, null, false, Instant.parse("2026-07-01T00:30:00Z"))
}

class ComparisonAlertHistoryRowTest {
    @Test fun HJ07_snapshot() {
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            for (tab in listOf("usd", "jpy", "eur")) {
                val row = comparisonHistoryRow(delivery(tab = tab).copy(operator = "unknown"))
                assertEquals(ComparisonHistoryRow(ComparisonHistoryLeg("hana", "하나", "1400.00"), "2.00", "↔",
                    ComparisonHistoryLeg("kb", "국민", "1398.00"), false, "3", "▽ 이하", "07-01", "09:30"), row)
            }
            assertEquals("8.25", comparisonHistoryRow(delivery().copy(spread = -8.25)).spread)
            val absolute = comparisonHistoryRow(delivery(tab = "tether").copy(leftRate = 1495.0, rightRate = 1498.0, spread = -3.0))
            assertEquals(listOf("업비트", "1495", "3", "↔", "빗썸", "1498"),
                listOf(absolute.left.name, absolute.left.rate, absolute.spread, absolute.detail, absolute.right.name, absolute.right.rate))
            val signed = delivery(tab = "tether", kind = "signed").copy(leftRate = 1390.0, rightRate = 1401.5, spread = -11.5,
                operator = "gte", threshold = -15.5, leftObservedAt = delivery().sentAt, rightObservedAt = delivery().sentAt,
                sentAt = Instant.parse("2026-07-01T15:01:00Z"))
            assertEquals(ComparisonHistoryRow(ComparisonHistoryLeg("upbit", "업비트", "1390"), "-11.5", "-0.82%",
                ComparisonHistoryLeg("investing", "인베스팅", "1401.50"), true, "-15.5", "△ 이상", "07-02", "00:01"),
                comparisonHistoryRow(signed))
            val positive = signed.copy(spread = 12.0, rightRate = 1500.0, threshold = 0.0)
            val row = comparisonHistoryRow(positive)
            assertEquals(listOf("+12.0", "+0.80%", "+0", false), listOf(row.spread, row.detail, row.threshold, row.negativeSpread))
            val zero = comparisonHistoryRow(positive.copy(rightRate = 0.0))
            assertEquals(listOf("+12.0", null, "0.00"), listOf(zero.spread, zero.detail, zero.right.rate))
            for ((value, expected) in listOf(-11.25 to "-11.2", -11.35 to "-11.3", -0.0 to "-0.0"))
                assertEquals(expected, comparisonHistoryRow(signed.copy(spread = value)).spread)
            for ((value, expected) in listOf(1.125 to "1.12", 1.375 to "1.38", 2.675 to "2.67", 4.0 to "4.00"))
                assertEquals(expected, comparisonHistoryRow(delivery().copy(leftRate = value)).left.rate)
            for ((value, expected) in listOf(-0.0 to "-0", 4.0 to "+4", 4.25 to "+4.25", 999999.75 to "+1e+06",
                1e-4 to "+0.0001", 1.25e-5 to "+1.25e-05"))
                assertEquals(expected, comparisonHistoryRow(signed.copy(threshold = value)).threshold)
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }
}
