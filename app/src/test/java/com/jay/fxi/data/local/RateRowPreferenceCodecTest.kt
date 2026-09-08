package com.jay.fxi.data.local

import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.Exchange
import com.jay.fxi.domain.model.RateRowList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the two axes survive a trip to disk.
 *
 * Order is a sequence and visibility is a set, and the difference is the whole point: sorting the
 * order would write down something other than what the user arranged.
 */
class RateRowPreferenceCodecTest {

    @Test
    fun anOrderIsWrittenInOrder() {
        val codes = listOf("hana", "kb", "investing")
        assertEquals(codes, RateRowPreferenceCodec.decode(RateRowPreferenceCodec.encodeOrder(codes)))
        assertEquals("hana\nkb\ninvesting", RateRowPreferenceCodec.encodeOrder(codes))
    }

    /** A set has no order, so it is written sorted — an unchanged choice writes identical bytes. */
    @Test
    fun aHiddenSetIsWrittenTheSameWayEveryTime() {
        assertEquals(
            RateRowPreferenceCodec.encodeHidden(setOf("citi", "bs")),
            RateRowPreferenceCodec.encodeHidden(setOf("bs", "citi"))
        )
        assertEquals(setOf("bs", "citi"), RateRowPreferenceCodec.decode(RateRowPreferenceCodec.encodeHidden(setOf("citi", "bs"))).toSet())
    }

    /** Empty round-trips to empty, which is an opinion — "show everything" — not an absence. */
    @Test
    fun emptyRoundTripsToEmpty() {
        assertEquals(emptyList<String>(), RateRowPreferenceCodec.decode(RateRowPreferenceCodec.encodeHidden(emptySet())))
        assertEquals(emptyList<String>(), RateRowPreferenceCodec.decode(RateRowPreferenceCodec.encodeOrder(emptyList())))
    }

    /**
     * A code carrying the separator is refused rather than silently split into two.
     *
     * The same guard `FreeVisibleSeriesCodec` uses. Nothing on the wire looks like this today, and
     * the point is that if it ever does, the store says so instead of inventing a source.
     */
    @Test
    fun aCodeCarryingTheSeparatorIsRefused() {
        val thrown = runCatching { RateRowPreferenceCodec.encodeOrder(listOf("kb\nhana")) }.exceptionOrNull()
        assertTrue("구분자가 든 코드가 통과했다: $thrown", thrown is IllegalArgumentException)
        assertTrue(
            runCatching { RateRowPreferenceCodec.encodeHidden(setOf("kb\nhana")) }.exceptionOrNull()
                is IllegalArgumentException
        )
    }

    /**
     * A repeated code comes back once, in the place it first appeared.
     *
     * The disk is not something this build wrote — the file survives updates and, by D27, travels
     * between devices. Downstream treats the answer as distinct: the sheet keys its rows by code,
     * so a repeat would draw one row twice and then throw. `ANDROID_V2_PLAN.md:812` asks for dedup
     * with the relative order kept, and first-occurrence is what keeps it. Found by review.
     */
    @Test
    fun aRepeatedCodeComesBackOnceWhereItFirstAppeared() {
        assertEquals(
            listOf("kb", "sc", "hana"),
            RateRowPreferenceCodec.decode("kb\nsc\nkb\nhana\nsc")
        )
        // Blank entries were already dropped; they must not count as a first occurrence either.
        assertEquals(listOf("kb"), RateRowPreferenceCodec.decode("\nkb\n\nkb\n"))
    }

    /**
     * Every code this app can actually store survives the encoding.
     *
     * A structural check rather than a spot check: the guard above only fires for a separator, and
     * this is what says no real code contains one — including the list names the keys are built
     * from, which would otherwise be a second place for a bad character to hide.
     */
    @Test
    fun everyRealCodeAndListNameSurvives() {
        val codes = Bank.entries.map { it.code } + Exchange.entries.map { it.code }
        assertEquals(codes, RateRowPreferenceCodec.decode(RateRowPreferenceCodec.encodeOrder(codes)))
        RateRowList.entries.forEach {
            assertTrue("${it.storageValue} 가 키에 쓸 수 없다", !it.storageValue.contains(RateRowPreferenceCodec.SEPARATOR))
        }
    }
}
