package com.jay.fxi.ui.free

import com.jay.fxi.data.free.FreeSnapshotEntry
import com.jay.fxi.data.free.FreeSnapshotFreshness
import com.jay.fxi.data.free.FreeSnapshotKey
import com.jay.fxi.data.free.FreeSnapshotReadState
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.FreeSnapshot
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S7 E5d gap hunt r1 G15 (`e5d_gaphunt.claude.r1.md`): the preview's quotes and as_of come from exactly the selected tab's and
 * period's own snapshot. Every slot of the three bank tabs × four periods is cached at once with its own rates, as_of,
 * generatedAt and quote times, so a projection that takes another slot, the first one, or another of a snapshot's times is told
 * apart. Another owner's view of the same state shows none of it, and the state it came from is left as it was.
 */
class S7E5DPreviewProvenanceTest {

    private val basis = Instant.parse("2026-09-06T02:30:00Z")
    private val tabs = listOf(FreeTab.USD to "usd", FreeTab.JPY to "jpy", FreeTab.EUR to "eur")
    private val banks = listOf("investing", "kb", "hana", "shinhan")

    private data class Slot(val tab: FreeTab, val server: String, val period: GraphPeriod, val index: Int) {
        val currency = "$server-krw"
    }

    private val slots: List<Slot> = tabs.flatMap { (tab, server) -> GraphPeriod.entries.map { tab to server to it } }
        .mapIndexed { i, (pair, period) -> Slot(pair.first, pair.second, period, i) }

    /** The one slot that carries no editor bank: its map is empty and its as_of is still its own. */
    private val bare = slots.first { it.tab == FreeTab.JPY && it.period == GraphPeriod.THREE_MONTHS }

    private fun asOf(slot: Slot) = basis + (slot.index * 3).minutes
    private fun rateOf(slot: Slot, bank: Int) = 900.0 + slot.index * 10 + bank
    private fun freshness(slot: Slot) = if (slot.index % 2 == 0) FreeSnapshotFreshness.FRESH else FreeSnapshotFreshness.DELAYED

    private fun expectedQuotes(slot: Slot): Map<String, Double> =
        if (slot == bare) emptyMap() else banks.mapIndexed { i, bank -> bank to rateOf(slot, i) }.toMap()

    private fun snapshot(slot: Slot): FreeSnapshot {
        val at = asOf(slot)
        val quoteTime = at - (slot.index + 1).hours
        val entries = if (slot == bare) {
            listOf(ExchangeRate(slot.currency, "citi", 1234.0, quoteTime))
        } else {
            banks.mapIndexed { i, bank -> ExchangeRate(slot.currency, bank, rateOf(slot, i), quoteTime) }
        }
        return FreeSnapshot(
            tab = slot.server,
            period = slot.period,
            asOf = at,
            generatedAt = at + 7.minutes + slot.index.seconds,
            refreshNotBefore = at + 48.hours,
            rate = FreeRate.Flat(slot.currency, entries),
            graph = FreeGraph(null, listOf(FreeGraphSeries(
                seriesId = "investing.${slot.server}",
                points = listOf(FreeGraphPoint(at - 2.hours, 1.0 + slot.index, null, null), FreeGraphPoint(at, 2.0 + slot.index, null, null))
            )))
        )
    }

    private val readState = FreeSnapshotReadState(
        "u1",
        slots.associate { slot -> FreeSnapshotKey(slot.server, slot.period) to FreeSnapshotEntry(snapshot(slot), freshness(slot)) }
    )

    private fun state(slot: Slot, uid: String = "u1") = FreeSnapshotUiState.from(uid, slot.tab, slot.period, readState, emptySet())

    @Test
    fun everySlotProjectsExactlyItsOwnQuotesAndAsOf() {
        assertEquals("premise: 3 탭 × 4 기간", 12, slots.size)
        assertEquals("premise: 슬롯마다 as_of 가 다르다", 12, slots.map(::asOf).toSet().size)
        for (slot in slots) {
            val s = state(slot)
            assertEquals("${slot.tab}/${slot.period}", expectedQuotes(slot), s.previewQuotes)
            assertEquals("${slot.tab}/${slot.period}", asOf(slot), s.previewAsOf)
            val snap = snapshot(slot)
            assertNotEquals("premise: as_of ≠ generatedAt", snap.asOf, snap.generatedAt)
        }
    }

    /** DELAYED is still a live snapshot: every delayed slot carries its own pair, the same as a fresh one. */
    @Test
    fun aDelayedSlotCarriesItsOwnPairToo() {
        val delayed = slots.filter { freshness(it) == FreeSnapshotFreshness.DELAYED }
        assertEquals("premise: 지연 슬롯 6개", 6, delayed.size)
        for (slot in delayed) {
            assertEquals("${slot.tab}/${slot.period}", expectedQuotes(slot) to asOf(slot), state(slot).let { it.previewQuotes to it.previewAsOf })
        }
    }

    @Test
    fun aSlotWithNoEditorBankKeepsOnlyItsOwnAsOf() {
        val s = state(bare)
        assertEquals(emptyMap<String, Double>(), s.previewQuotes)
        assertEquals(asOf(bare), s.previewAsOf)
    }

    /** At render time another owner sees the neutral state, and the state it came from keeps its own preview. */
    @Test
    fun anotherOwnersViewShowsNoPreview_andTheOriginalStays() {
        for (slot in slots) {
            val original = state(slot)
            val before = original.copy()
            val other = original.forOwner("u2")
            assertEquals("${slot.tab}/${slot.period}", emptyMap<String, Double>(), other.previewQuotes)
            assertNull("${slot.tab}/${slot.period}", other.previewAsOf)
            assertEquals("${slot.tab}/${slot.period}: 원 state 불변", before, original)
            assertEquals("${slot.tab}/${slot.period}: 같은 owner", original, original.forOwner("u1"))
        }
    }

    @Test
    fun aReadStateOfAnotherOwnerProjectsNothingForAnySlot() {
        for (slot in slots) {
            val s = state(slot, uid = "u2")
            assertEquals("${slot.tab}/${slot.period}", emptyMap<String, Double>(), s.previewQuotes)
            assertNull("${slot.tab}/${slot.period}", s.previewAsOf)
        }
    }
}
