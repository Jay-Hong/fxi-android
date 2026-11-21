package com.jay.fxi.data.alerts

import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.SourceAlertSetting
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8-3 row policy (`R4c/S8/s8_3_contract` §1.1, §2.1; design `s8_2_3_design.agreed.r1.md` §2 "snapshot의 단위와 행 검증"): the
 * exact (source, asset) pair and the request's user decide adoption, an id carried more than once is dropped with all its
 * rows, a tab projects only its exact asset, and the cap counts distinct adopted ids whatever their state.
 */
class SourceAlertRowPolicyTest {
    private val stamp = Instant.parse("2026-11-21T01:00:00Z")
    private val exchanges = listOf("upbit", "bithumb", "coinone", "korbit", "gopax")

    private fun row(id: Int, source: String, asset: String = "usdt-krw", uid: String = "user-a", enabled: Boolean = true,
                    triggered: Boolean = false, repeat: Int? = null) =
        SourceAlertSetting(id, uid, source, asset, AlertCondition.ABOVE, 1400.0 + id, enabled, triggered, repeat, stamp, stamp, null)

    private val policy = SourceAlertRowPolicy.EXCHANGES

    @Test fun `S8_3_P01 each of the five exchanges with usdt-krw is adopted, in order`() {
        val rows = exchanges.mapIndexed { i, source -> row(10 + i, source) }
        assertEquals("all five, in the answer's order", rows, policy.adopt(rows, "user-a"))
        for (r in rows) assertEquals("${r.source} alone", listOf(r), policy.adopt(listOf(r), "user-a"))
    }

    @Test fun `S8_3_P02 any other pair, spelling or user is dropped and only the allowed rows remain in order`() {
        val allowed = listOf(row(1, "upbit"), row(5, "gopax"), row(9, "korbit"))
        val refused = listOf(
            row(2, "kb"),                                     // a quoted reference source, not an alert pair
            row(3, "binance"),                                // an unregistered source
            row(4, "Upbit"),                                  // no case folding
            row(6, " gopax"),                                 // no trimming
            row(7, "upbit", asset = "usd-krw-futures"),       // an exchange with another asset
            row(8, "krx", asset = "usd-krw-futures"),         // the futures pair, closed here
            row(10, "krx", asset = "usdt-krw"),               // a cross pair
            row(11, "bithumb", asset = "USDT-KRW"),
            row(12, "coinone", uid = "user-b")                // another user
        )
        val mixed = listOf(refused[0], allowed[0], refused[1], refused[2], allowed[1]) + refused.drop(3) + allowed[2]
        assertEquals("only the allowed rows, in the answer's order", allowed, policy.adopt(mixed, "user-a"))
        assertTrue("nothing refused survives", policy.adopt(refused, "user-a").isEmpty())
    }

    @Test fun `S8_3_P03 an id carried more than once is dropped with every one of its rows`() {
        val keep = row(1, "upbit")
        val twiceAllowed = listOf(row(2, "upbit"), row(2, "bithumb"))
        val allowedAndRefused = listOf(row(3, "coinone"), row(3, "kb"))
        val identical = listOf(row(4, "korbit"), row(4, "korbit"))
        val answer = listOf(keep) + twiceAllowed + allowedAndRefused + identical + listOf(row(5, "upbit"), row(5, "upbit", uid = "user-b"))
        assertEquals("only the id carried once", listOf(keep), policy.adopt(answer, "user-a"))
        assertEquals("the reverse order drops the same ids", listOf(keep), policy.adopt(answer.reversed(), "user-a"))
    }

    @Test fun `S8_3_P04 a tab projects its exact asset only and two assets split the snapshot`() {
        val adopted = policy.adopt(exchanges.mapIndexed { i, s -> row(i + 1, s) }, "user-a")
        assertEquals("the tether tab shows every adopted row", adopted, policy.project(adopted, "usdt-krw"))
        assertTrue("another asset shows nothing", policy.project(adopted, "usd-krw-futures").isEmpty())
        assertTrue("no spelling variant", policy.project(adopted, "USDT-KRW").isEmpty())

        val two = SourceAlertRowPolicy(setOf("upbit" to "usdt-krw", "krx" to "usd-krw-futures"))
        val both = two.adopt(listOf(row(1, "upbit"), row(2, "krx", "usd-krw-futures"), row(3, "upbit"), row(4, "krx", "usdt-krw")), "user-a")
        val tether = two.project(both, "usdt-krw")
        val futures = two.project(both, "usd-krw-futures")
        assertTrue("a test policy with two assets: the tabs share no row", tether.intersect(futures.toSet()).isEmpty())
        assertEquals("and together are the adopted rows", both.toSet(), (tether + futures).toSet())
        assertEquals("the cross pair is in neither", listOf(1, 2, 3), both.map { it.id })
        for (n in listOf(29, 30, 31)) {
            val split = two.adopt((1..n).map { i -> if (i % 2 == 0) row(i, "upbit") else row(i, "krx", "usd-krw-futures") }, "user-a")
            val parts = two.project(split, "usdt-krw") to two.project(split, "usd-krw-futures")
            assertEquals("$n ids split over two assets add up to $n", n, two.count(parts.first) + two.count(parts.second))
            assertEquals("and the $n-th valid row is kept", n, split.last().id)
        }
    }

    @Test fun `S8_3_P05 the cap counts distinct adopted ids, disabled, triggered and repeating included`() {
        fun rows(n: Int) = (1..n).map { i ->
            row(i, exchanges[i % 5], enabled = i % 3 != 0, triggered = i % 4 == 0, repeat = if (i % 2 == 0) 300 else null)
        }
        for (n in listOf(0, 29, 30, 31)) assertEquals("$n rows", n, policy.count(policy.adopt(rows(n), "user-a")))
        val dropped = rows(29) + row(100, "kb") + row(101, "upbit", uid = "user-b") + row(5, "upbit")
        assertEquals("refused rows and a repeated id do not count", 28, policy.count(policy.adopt(dropped, "user-a")))
        val a = row(7, "upbit")
        assertEquals("count itself counts distinct ids", 1, policy.count(listOf(a, a)))
    }
}
