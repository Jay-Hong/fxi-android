package com.jay.fxi.domain.model

import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * What a frame is allowed to change.
 *
 * Two paths deliver the same quotes — the REST bootstrap and the socket — and they race. Every
 * rule here exists because one of those races puts a wrong number on screen: an old price winning,
 * a redelivered price flickering, or a group the server simply had nothing to say about being read
 * as a row that went away.
 */
class TopicRatesTest {

    private fun at(text: String) = Instant.parse(text)

    private fun quote(source: String, asset: String, rate: Double, at: String) =
        TopicQuote(source, asset, rate, at(at))

    private val kb = quote("kb", "usd-krw", 1400.0, "2026-09-08T06:00:00Z")

    @Test
    fun aQuoteNobodyHasSeenIsAdded() {
        val merged = TopicRates().merge(listOf(kb))
        assertEquals(mapOf(kb.key to kb), merged.quotes)
    }

    @Test
    fun aLaterQuoteReplacesTheOneHeld() {
        val later = kb.copy(rate = 1401.0, at = at("2026-09-08T06:00:01Z"))
        assertEquals(later, TopicRates().merge(listOf(kb)).merge(listOf(later)).quotes.getValue(kb.key))
    }

    /**
     * An older frame changes nothing, however new it is to arrive.
     *
     * This is the bootstrap race: a REST response built before the last live frame can be handed
     * over after it. Accepting on arrival order would put the stale price back.
     */
    @Test
    fun anOlderQuoteIsIgnoredEvenWhenItArrivesLast() {
        val older = kb.copy(rate = 1399.0, at = at("2026-09-08T05:59:59Z"))
        val held = TopicRates().merge(listOf(kb))
        assertSame(held, held.merge(listOf(older)))
    }

    /**
     * The same instant keeps what is held, even when the number differs.
     *
     * Usually that second arrival is one reading redelivered, and keeping the first stops the
     * display churning. It is not always: two real changes can share a millisecond, and v1 has no
     * per-message sequence to order them, so this cannot tell the cases apart. The contract picks
     * one answer — keep what is held — and this locks it, including that the *rate* is kept too.
     */
    @Test
    fun anEqualInstantKeepsWhatIsHeld() {
        val held = TopicRates().merge(listOf(kb))
        val sameTimeDifferentRate = kb.copy(rate = 1402.0)
        assertSame(held, held.merge(listOf(sameTimeDifferentRate)))
    }

    /**
     * A group missing from a frame is not a deletion.
     *
     * A snapshot without a reference quote means the server had none to send. Rows disappear when
     * the owner of a UID or epoch change says so, never because one frame was quieter.
     */
    @Test
    fun aGroupAbsentFromAFrameIsNotRemoved() {
        val reference = quote("investing", "usd-krw", 1400.5, "2026-09-08T06:00:00Z")
        val held = TopicRates().merge(listOf(kb, reference))

        val quieter = held.merge(listOf(kb.copy(rate = 1403.0, at = at("2026-09-08T06:00:05Z"))))

        assertEquals(reference, quieter.quotes.getValue(reference.key))
        assertEquals(setOf(kb.key, reference.key), quieter.quotes.keys)
    }

    /** Nothing to merge is not a reason to build a new value. */
    @Test
    fun anEmptyFrameChangesNothing() {
        val held = TopicRates().merge(listOf(kb))
        assertSame(held, held.merge(emptyList()))
    }

    /**
     * A key repeated inside one frame resolves by the same rule, in either order.
     *
     * No first-wins clause of its own: folding compares each against what is held so far, so the
     * later of the two wins exactly as it would across two frames.
     */
    @Test
    fun aRepeatedKeyInOneFrameResolvesByTheSameRule() {
        val later = kb.copy(rate = 1405.0, at = at("2026-09-08T06:00:02Z"))
        assertEquals(later, TopicRates().merge(listOf(kb, later)).quotes.getValue(kb.key))
        assertEquals(later, TopicRates().merge(listOf(later, kb)).quotes.getValue(kb.key))
    }

    /** One source can publish more than one asset, and they are different rows. */
    @Test
    fun oneSourceCanHoldSeveralAssets() {
        val usdt = quote("upbit", "usdt-krw", 1485.0, "2026-09-08T06:00:00Z")
        val other = quote("upbit", "usd-krw", 1400.0, "2026-09-08T06:00:00Z")
        assertEquals(2, TopicRates().merge(listOf(usdt, other)).quotes.size)
    }

    /** The index has one slot rather than a keyed map, and the same rule governs it. */
    @Test
    fun theDollarIndexFollowsTheSameRule() {
        val first = TopicDollarIndex(104.5, at("2026-09-08T06:00:00Z"), "investing")
        val held = TopicRates().merge(first)
        assertEquals(first, held.dollarIndex)

        assertSame(held, held.merge(first.copy(rate = 104.9)))
        assertSame(held, held.merge(first.copy(rate = 103.0, at = at("2026-09-08T05:59:00Z"))))

        val later = first.copy(rate = 105.1, at = at("2026-09-08T06:01:00Z"), source = "cnbc")
        assertEquals(later, held.merge(later).dollarIndex)
    }

    /** The index and the quotes do not touch each other. */
    @Test
    fun theIndexAndTheQuotesAreIndependent() {
        val withQuotes = TopicRates().merge(listOf(kb))
        assertNull(withQuotes.dollarIndex)
        val withBoth = withQuotes.merge(TopicDollarIndex(104.5, at("2026-09-08T06:00:00Z"), "investing"))
        assertEquals(kb, withBoth.quotes.getValue(kb.key))
    }
}
