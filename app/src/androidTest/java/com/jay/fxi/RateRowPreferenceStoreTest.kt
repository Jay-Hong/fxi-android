package com.jay.fxi

import androidx.test.platform.app.InstrumentationRegistry
import com.jay.fxi.data.local.DataStoreRateRowPreferenceStore
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * What actually survives a write, on a real DataStore.
 *
 * The codec is checked on the JVM; this is the part that only a device can answer — that the two
 * axes come back independently, that an owner change wipes rather than merges, and that a file
 * written by one user answers nothing to another.
 */
class RateRowPreferenceStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = DataStoreRateRowPreferenceStore(context)

    private fun file() = File(context.filesDir, "datastore/fxi_user_intent.preferences_pb")

    @Before fun clean() = file().delete().let { }

    @After fun cleanUp() = file().delete().let { }

    @Test
    fun bothAxesComeBackAsTheyWereWritten() = runBlocking {
        store.remember(
            "u1", RateRowList.FX_BANKS,
            RateRowPreference(order = listOf("hana", "kb", "investing"), hidden = setOf("citi", "bs"))
        )
        val read = store.preferences("u1").getValue(RateRowList.FX_BANKS)
        assertEquals(listOf("hana", "kb", "investing"), read.order)
        assertEquals(setOf("citi", "bs"), read.hidden)
    }

    /**
     * An absent axis stays absent, which is not the same as an empty one.
     *
     * Absent order means "no opinion — draw them as they arrive". Writing it as an empty list would
     * be an order with nothing in it, and every source would then rank behind nothing at all.
     */
    @Test
    fun anAbsentAxisIsNotStoredAsAnEmptyOne() = runBlocking {
        store.remember("u1", RateRowList.FX_BANKS, RateRowPreference(hidden = setOf("citi")))
        val read = store.preferences("u1").getValue(RateRowList.FX_BANKS)
        assertNull("순서를 기록한 적 없는데 값이 있다", read.order)
        assertEquals(setOf("citi"), read.hidden)

        // …and an empty hidden set is an opinion, so it comes back as one.
        store.remember("u1", RateRowList.TETHER_EXCHANGES, RateRowPreference(hidden = emptySet()))
        assertEquals(emptySet<String>(), store.preferences("u1").getValue(RateRowList.TETHER_EXCHANGES).hidden)
    }

    /**
     * A file holding a repeated code answers with it once.
     *
     * Written through the store rather than by hand, because the claim is about the whole round
     * trip: whatever ends up on disk, what comes back is a list the rest of the app can key rows
     * by. The file outlives this build and, by D27, travels between devices, so the read side is
     * where the guarantee has to hold. Found by review.
     */
    @Test
    fun aRepeatedCodeComesBackOnce() = runBlocking {
        store.remember(
            "u1", RateRowList.FX_BANKS,
            RateRowPreference(order = listOf("kb", "sc", "kb", "hana"), hidden = setOf("citi"))
        )
        assertEquals(
            listOf("kb", "sc", "hana"),
            store.preferences("u1").getValue(RateRowList.FX_BANKS).order
        )
    }

    /** Writing one axis later does not disturb the other, nor the other list. */
    @Test
    fun oneAxisCanBeWrittenWithoutTouchingTheRest() = runBlocking {
        store.remember("u1", RateRowList.FX_BANKS, RateRowPreference(order = listOf("kb", "hana")))
        store.remember("u1", RateRowList.TETHER_EXCHANGES, RateRowPreference(hidden = setOf("gopax")))

        val all = store.preferences("u1")
        assertEquals(listOf("kb", "hana"), all.getValue(RateRowList.FX_BANKS).order)
        assertNull(all.getValue(RateRowList.FX_BANKS).hidden)
        assertEquals(setOf("gopax"), all.getValue(RateRowList.TETHER_EXCHANGES).hidden)
    }

    /** A list nobody has chosen for is absent from the map, not present and empty. */
    @Test
    fun aListNeverChosenForIsAbsent() = runBlocking {
        store.remember("u1", RateRowList.FX_BANKS, RateRowPreference(hidden = setOf("citi")))
        assertTrue(RateRowList.TETHER_EXCHANGES !in store.preferences("u1"))
    }

    /**
     * A different owner sees nothing, and writing as them clears what was there.
     *
     * Merging would put one person's arrangement under another's name on a shared device, which is
     * the failure the DoD names for the v1 file that has no owner at all.
     */
    @Test
    fun anotherOwnerNeitherReadsNorMergesWithTheFirst() = runBlocking {
        store.remember(
            "u1", RateRowList.FX_BANKS,
            RateRowPreference(order = listOf("hana", "kb"), hidden = setOf("citi"))
        )
        assertEquals(emptyMap<RateRowList, RateRowPreference>(), store.preferences("u2"))

        store.remember("u2", RateRowList.TETHER_EXCHANGES, RateRowPreference(hidden = setOf("gopax")))
        val second = store.preferences("u2")
        assertEquals(setOf(RateRowList.TETHER_EXCHANGES), second.keys)
        assertTrue("앞 사용자의 목록이 남아 있다", RateRowList.FX_BANKS !in second)

        // …and the first user does not get the second's file back either.
        assertEquals(emptyMap<RateRowList, RateRowPreference>(), store.preferences("u1"))
    }
}
