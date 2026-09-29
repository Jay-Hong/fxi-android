package com.jay.fxi.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Claude-owned ②b-1 contract (S2b/rule_consensus_codex.r1.md): nginx's own limit_req arithmetic — the first request of an address
 * sets excess to 0; each later one computes `max(0, excess − rate·elapsedMs + 1000)` in thousandths; a result **above** `burst·1000`
 * is refused and leaves the state as it was. The implementation thread reads but does not edit this file.
 */
class NginxLimitModelTest {

    private val api = LimitReqZone(ratePerSecond = 3, burst = 20)

    @Test
    fun N01_noSends_nothingHeld() {
        assertEquals(LimitReqVerdict(0L, emptyList()), NginxLimitModel.limitReq(api, emptyList()))
    }

    @Test
    fun N02_theFirstRequest_startsAtZero() {
        assertEquals(LimitReqVerdict(0L, emptyList()), NginxLimitModel.limitReq(api, listOf(5_000L)))
    }

    @Test
    fun N03_simultaneousRequests_eachAddOne() {
        assertEquals(LimitReqVerdict(2_000L, emptyList()), NginxLimitModel.limitReq(api, listOf(0L, 0L, 0L)))
    }

    @Test
    fun N04_excessDrainsAtTheRate_byTheMillisecond() {
        // 0, 0 → 1000; at 333 ms: 1000 − 999 + 1000 = 1001; at 334 ms instead: 1000 − 1002 + 1000 = 998.
        assertEquals(1_001L, NginxLimitModel.limitReq(api, listOf(0L, 0L, 333L)).maxExcessMilli)
        assertEquals(1_000L, NginxLimitModel.limitReq(api, listOf(0L, 0L, 334L)).maxExcessMilli)
    }

    @Test
    fun N05_aFullyDrainedAddress_addsNothing_theFloorIsTakenAfterTheIncrement() {
        // max(0, 0 − 3000 + 1000) = 0, not max(0, 0 − 3000) + 1000 = 1000.
        assertEquals(LimitReqVerdict(0L, emptyList()), NginxLimitModel.limitReq(api, listOf(0L, 1_000L)))
        // Drained, then two together: 0, 0, 1000 — the wrong order would give 0, 1000, 2000.
        assertEquals(LimitReqVerdict(1_000L, emptyList()), NginxLimitModel.limitReq(api, listOf(0L, 1_000L, 1_000L)))
    }

    @Test
    fun N06_exactlyTheBurst_isAllowed_oneMoreIsRefused() {
        val sends = List(22) { 0L }
        assertEquals(LimitReqVerdict(20_000L, listOf(21)), NginxLimitModel.limitReq(api, sends))
    }

    @Test
    fun N07_aRefusedRequest_leavesTheStateAsItWas() {
        val sends = List(23) { 0L } + 334L
        // Both extra sends see 21000 and are refused; the one at 334 ms sees 20000 − 1002 + 1000 = 19998 and passes.
        assertEquals(LimitReqVerdict(20_000L, listOf(21, 22)), NginxLimitModel.limitReq(api, sends))
    }

    @Test
    fun N08_theRateIsTheZones() {
        val ws = LimitReqZone(ratePerSecond = 10, burst = 20)
        // 0, 0 → 1000; at 100 ms: 1000 − 1000 + 1000 = 1000.
        assertEquals(1_000L, NginxLimitModel.limitReq(ws, listOf(0L, 0L, 100L)).maxExcessMilli)
        assertEquals(1_700L, NginxLimitModel.limitReq(api, listOf(0L, 0L, 100L)).maxExcessMilli)
    }

    @Test
    fun N09_sendsOutOfOrder_areRefusedAsInput() {
        assertThrows(IllegalArgumentException::class.java) { NginxLimitModel.limitReq(api, listOf(10L, 5L)) }
    }

    @Test
    fun N10_theServersZones_areWhatTheConfigurationSays() {
        assertEquals(LimitReqZone(3, 20), NginxLimitModel.API)
        assertEquals(LimitReqZone(10, 20), NginxLimitModel.WS_HANDSHAKE)
        assertEquals(20, NginxLimitModel.API_CONNECTIONS)
    }

    @Test
    fun C01_concurrency_countsOverlap_endExclusive() {
        assertEquals(0, NginxLimitModel.maxConcurrent(emptyList()))
        assertEquals(2, NginxLimitModel.maxConcurrent(listOf(0L until 100L, 50L until 150L, 100L until 200L)))
        assertEquals(3, NginxLimitModel.maxConcurrent(listOf(0L until 10L, 0L until 10L, 0L until 10L)))
        assertEquals(1, NginxLimitModel.maxConcurrent(listOf(0L until 10L, 10L until 20L)))
        // The first is still in flight at 10 ms, when the second starts.
        assertEquals(2, NginxLimitModel.maxConcurrent(listOf(0L until 11L, 10L until 20L)))
    }
}
