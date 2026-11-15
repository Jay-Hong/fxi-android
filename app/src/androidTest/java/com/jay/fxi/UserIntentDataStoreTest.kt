package com.jay.fxi

import androidx.test.platform.app.InstrumentationRegistry
import com.jay.fxi.UserIntentTestFiles.BANK
import com.jay.fxi.UserIntentTestFiles.BANK_OWNER
import com.jay.fxi.UserIntentTestFiles.RATE_ROW_LISTS
import com.jay.fxi.UserIntentTestFiles.RATE_ROW_OWNER
import com.jay.fxi.UserIntentTestFiles.onDisk
import com.jay.fxi.UserIntentTestFiles.rateRowHidden
import com.jay.fxi.UserIntentTestFiles.rateRowOrder
import com.jay.fxi.UserIntentTestFiles.seed
import com.jay.fxi.data.local.DataStoreLastSelectedBankStore
import com.jay.fxi.data.local.DataStoreRateRowPreferenceStore
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The shared `fxi_user_intent` delegate after the move (S7 E5b4a).
 *
 * Moving the declaration must not move the rate rows' file: what an earlier install wrote is read
 * back, and rewritten, under the names it was written with. The rate row store's own test writes
 * and reads through the store, so a writer and reader that changed their names together would pass
 * it; this one seeds the old names directly.
 */
class UserIntentDataStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rateRows = DataStoreRateRowPreferenceStore(context)
    private val bank = DataStoreLastSelectedBankStore(context)

    @Before fun clean() = UserIntentTestFiles.clear(context)

    @After fun cleanUp() = UserIntentTestFiles.clear(context)

    @Test
    fun preexistingRateRowKeysSurviveTheDelegateMove() = runBlocking {
        assertEquals(
            "premise: 디스크 이름은 main 82e28ff 의 storageValue 그대로",
            RATE_ROW_LISTS, RateRowList.entries.map { it.storageValue }
        )
        seed(context) { p ->
            p[RATE_ROW_OWNER] = "u1"
            p[rateRowOrder("FX_BANKS")] = "hana\nkb\ninvesting"
            p[rateRowHidden("FX_BANKS")] = "bs\nciti"
            p[rateRowOrder("TETHER_EXCHANGES")] = "upbit\nbithumb"
            p[rateRowHidden("TETHER_EXCHANGES")] = "gopax"
        }
        val seededNames = onDisk(context).keys.map { it.name }.toSet()

        val read = rateRows.preferences("u1")
        assertEquals(RateRowPreference(order = listOf("hana", "kb", "investing"), hidden = setOf("bs", "citi")), read[RateRowList.FX_BANKS])
        assertEquals(RateRowPreference(order = listOf("upbit", "bithumb"), hidden = setOf("gopax")), read[RateRowList.TETHER_EXCHANGES])

        rateRows.remember("u1", RateRowList.FX_BANKS, RateRowPreference(order = listOf("kb", "hana"), hidden = setOf("citi")))
        val rewritten = onDisk(context)
        assertEquals("rate row 가 다른 이름으로 썼다", seededNames, rewritten.keys.map { it.name }.toSet())
        assertEquals("kb\nhana", rewritten[rateRowOrder("FX_BANKS")])
        assertEquals("citi", rewritten[rateRowHidden("FX_BANKS")])
        assertEquals("upbit\nbithumb", rewritten[rateRowOrder("TETHER_EXCHANGES")])
        assertEquals("gopax", rewritten[rateRowHidden("TETHER_EXCHANGES")])
        assertEquals("u1", rewritten[RATE_ROW_OWNER])

        // The bank concern joining and leaving the file takes none of it with it.
        val rateRowEntries = rewritten
        bank.remember("u1", "sc")
        // Pair(...), not `to`: DataStore has its own infix `to` on a key that makes a Preferences.Pair.
        assertEquals(rateRowEntries + mapOf(Pair(BANK_OWNER, "u1"), Pair(BANK, "sc")), onDisk(context))
        bank.read("u2")
        assertEquals("은행 owner 불일치가 rate row 를 건드렸다", rateRowEntries, onDisk(context))
    }
}
