package com.jay.fxi.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeVisibleSeriesCodecTest {

    /**
     * The distinction the feature rests on, at the layer that decides it. An entry that is simply
     * absent is the caller's `null`; a *stored* empty string is a user who turned everything off,
     * and decoding that to "nothing chosen" would reopen the tab on its default every launch.
     */
    @Test
    fun anEmptyStoredValueIsAllOff_notAnAbsentChoice() {
        assertEquals(emptySet<String>(), FreeVisibleSeriesCodec.decode(""))
        assertEquals("", FreeVisibleSeriesCodec.encode(emptySet()))
        assertEquals(emptySet<String>(), FreeVisibleSeriesCodec.decode(FreeVisibleSeriesCodec.encode(emptySet())))
    }

    @Test
    fun everyRealisticSetRoundTrips() {
        listOf(
            setOf("investing.usd"),
            setOf("investing.usd", "kb.usd", "hana.usd", "dxy"),
            setOf("upbit.usdt-krw", "bithumb.usdt-krw", "hana.usd"),
            setOf("dxy_futures")
        ).forEach { ids ->
            assertEquals(ids, FreeVisibleSeriesCodec.decode(FreeVisibleSeriesCodec.encode(ids)))
        }
    }

    /** Same choice, same bytes — so an unchanged set never looks like a change on disk. */
    @Test
    fun encodingIsStableRegardlessOfIterationOrder() {
        val a = FreeVisibleSeriesCodec.encode(linkedSetOf("dxy", "kb.usd", "investing.usd"))
        val b = FreeVisibleSeriesCodec.encode(linkedSetOf("investing.usd", "dxy", "kb.usd"))
        assertEquals(a, b)
    }

    /**
     * The separator is the one character the encoding cannot carry, so a value containing it is
     * refused rather than written and silently split back into two ids on the way out.
     */
    @Test
    fun anIdContainingTheSeparatorIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            FreeVisibleSeriesCodec.encode(setOf("investing.usd\nkb.usd"))
        }
    }

    /** A value written by some other build must not decode into an id that is the empty string. */
    @Test
    fun straySeparatorsInStoredTextDoNotBecomeEmptyIds() {
        assertEquals(setOf("dxy"), FreeVisibleSeriesCodec.decode("\n\ndxy\n"))
        assertTrue(FreeVisibleSeriesCodec.decode("\n\n").isEmpty())
    }
}
