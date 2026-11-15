package com.jay.fxi

import androidx.test.platform.app.InstrumentationRegistry
import com.jay.fxi.UserIntentTestFiles.BANK
import com.jay.fxi.UserIntentTestFiles.BANK_OWNER
import com.jay.fxi.UserIntentTestFiles.RATE_ROW_OWNER
import com.jay.fxi.UserIntentTestFiles.inMemory
import com.jay.fxi.UserIntentTestFiles.onDisk
import com.jay.fxi.UserIntentTestFiles.seed
import com.jay.fxi.UserIntentTestFiles.seedOthers
import com.jay.fxi.UserIntentTestFiles.withoutBank
import com.jay.fxi.data.local.DataStoreLastSelectedBankStore
import com.jay.fxi.data.local.DataStoreRateRowPreferenceStore
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The last selected bank on a real DataStore, sharing `fxi_user_intent` with the rate row
 * preferences (S7 E5b4a).
 *
 * Two things only a device answers: what actually reaches the file, and that the two concerns in
 * one file never read, clear or replace each other's keys — including when they edit at once.
 */
class LastSelectedBankStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = DataStoreLastSelectedBankStore(context)
    private val rateRows = DataStoreRateRowPreferenceStore(context)

    private fun file() = UserIntentTestFiles.file(context)

    private fun clear() = UserIntentTestFiles.clear(context)

    @Before fun clean() = clear()

    @After fun cleanUp() = clear()

    @Test
    fun aRememberedBankComesBackForTheSameUid() = runBlocking {
        assertNull("기억한 적 없는데 값이 있다", store.read("u1"))
        store.remember("u1", "hana")
        assertEquals("hana", store.read("u1"))
        store.remember("u1", "sc")
        assertEquals("sc", store.read("u1"))
    }

    /** A new process reads what this one wrote: the two keys, under their agreed names. */
    @Test
    fun theFileHoldsTheOwnerAndTheCode() = runBlocking {
        store.remember("u1", "woori")
        val disk = onDisk(context)
        assertEquals("u1", disk[BANK_OWNER])
        assertEquals("woori", disk[BANK])
        // …and a separate instance of the store answers the same, with no state of its own.
        assertEquals("woori", DataStoreLastSelectedBankStore(context).read("u1"))
    }

    /**
     * Another UID reads nothing, and the reading clears this concern's two keys — only those.
     *
     * Leaving them would keep one account's choice on disk under the next account's session; taking
     * the rate row keys with them would erase a choice that was never this concern's.
     */
    @Test
    fun anotherUidReadsNothingAndClearsOnlyTheBankKeys() = runBlocking {
        rateRows.remember("u1", RateRowList.FX_BANKS, RateRowPreference(order = listOf("hana", "kb")))
        store.remember("u1", "hana")

        assertNull(store.read("u2"))

        val disk = onDisk(context)
        assertNull("은행 owner 가 남아 있다", disk[BANK_OWNER])
        assertNull("은행 값이 남아 있다", disk[BANK])
        assertEquals("u1", disk[RATE_ROW_OWNER])
        assertEquals(
            listOf("hana", "kb"),
            rateRows.preferences("u1").getValue(RateRowList.FX_BANKS).order
        )
        // The first UID does not get its value back: it was cleared, not hidden.
        assertNull(store.read("u1"))
    }

    /** Writing as another UID replaces the owner and the code together; nothing is merged. */
    @Test
    fun anotherUidWritingReplaces() = runBlocking {
        store.remember("u1", "hana")
        store.remember("u2", "kb")
        assertEquals("kb", store.read("u2"))
        assertNull(store.read("u1"))
    }

    /**
     * Every UID Kotlin calls blank is refused before anything is touched.
     *
     * Not only the empty string and spaces: tabs, line breaks and the wider Unicode spaces are blank
     * too, and a check that refused them only after clearing would still have changed the file.
     */
    @Test
    fun everyBlankUidIsRejectedBeforeAnyMutation() = runBlocking {
        val read: suspend (String) -> Unit = { uid -> store.read(uid) }
        val remember: suspend (String) -> Unit = { uid -> store.remember(uid, "kb") }
        val calls = listOf("read" to read, "remember" to remember)
        val fixtures: List<Pair<String, () -> Unit>> = listOf(
            // An empty file written through the shared DataStore — written, because clearing an already
            // empty store writes nothing — so "no file" is not the state under test.
            "빈 store" to {
                seed(context) { p -> p[BANK] = "x" }
                clear()
            },
            "값 있는 store" to {
                clear()
                seedOthers(context)
                seed(context) { p ->
                    p[BANK_OWNER] = "u1"
                    p[BANK] = "hana"
                }
            },
        )
        BLANKS.forEach { blank ->
            assertTrue("premise: ${blank.map { it.code }} 는 blank", blank.isBlank())
            calls.forEach { (callName, call) ->
                fixtures.forEach { (fixtureName, prepare) ->
                    prepare()
                    val before = inMemory(context)
                    val bytes = file().readBytes()
                    val where = "$callName × $fixtureName × ${blank.map { it.code }}"
                    assertThrows(where, IllegalArgumentException::class.java) { runBlocking { call(blank) } }
                    assertEquals("$where: 값이 바뀌었다", before, inMemory(context))
                    assertArrayEquals("$where: 파일이 바뀌었다", bytes, file().readBytes())
                }
            }
        }
    }

    /**
     * Each concern's owner change leaves the other concern's keys where they were.
     *
     * The rate rows changing owner wipes the rate row keys, never the bank; the bank changing owner
     * replaces the bank keys, never the rate rows.
     */
    @Test
    fun theTwoConcernsPreserveEachOther() = runBlocking {
        rateRows.remember("u1", RateRowList.FX_BANKS, RateRowPreference(hidden = setOf("citi")))
        store.remember("u1", "hana")

        rateRows.remember("u2", RateRowList.TETHER_EXCHANGES, RateRowPreference(hidden = setOf("gopax")))
        assertEquals("rate row 의 owner 교체가 은행을 지웠다", "hana", store.read("u1"))

        store.remember("u3", "nh")
        assertEquals(
            "은행 owner 교체가 rate row 를 지웠다",
            setOf("gopax"),
            rateRows.preferences("u2").getValue(RateRowList.TETHER_EXCHANGES).hidden
        )
        assertEquals("nh", store.read("u3"))
    }

    /** Edits from both concerns at once all land; neither transaction overwrites the other's keys. */
    @Test
    fun concurrentEditsFromBothConcernsAllLand() = runBlocking {
        coroutineScope {
            (0 until ROUNDS).flatMap { i ->
                listOf(
                    async(Dispatchers.IO) { store.remember("u1", BANKS[i % BANKS.size]) },
                    async(Dispatchers.IO) {
                        rateRows.remember(
                            "u1", RateRowList.FX_BANKS,
                            RateRowPreference(order = listOf("kb", "hana"), hidden = setOf("citi"))
                        )
                    },
                    async(Dispatchers.IO) { store.read("u1") },
                )
            }.awaitAll()
        }
        assertTrue(store.read("u1") in BANKS)
        val fx = rateRows.preferences("u1").getValue(RateRowList.FX_BANKS)
        assertEquals(listOf("kb", "hana"), fx.order)
        assertEquals(setOf("citi"), fx.hidden)
        val disk = onDisk(context)
        assertEquals("u1", disk[BANK_OWNER])
        assertEquals("u1", disk[RATE_ROW_OWNER])
    }

    /**
     * Process-start readiness reads and writes nothing.
     *
     * Not the bytes only: a file owned by another UID is not reset by it either, so the owner's
     * value is still there afterwards.
     */
    @Test
    fun verifyReadableWritesNothing() = runBlocking {
        rateRows.remember("u9", RateRowList.FX_BANKS, RateRowPreference(hidden = setOf("bs")))
        store.remember("u1", "ibk")
        val before = file().readBytes()

        store.verifyReadable()

        assertArrayEquals("verifyReadable 가 파일을 바꿨다", before, file().readBytes())
        assertEquals("ibk", store.read("u1"))
    }

    /**
     * Each instance compares the owner the file holds now, not one it remembers writing.
     *
     * Two wrappers over the one file: when the other has since claimed it, or cleared it, this one's
     * own last write is not the owner any more. Sequential, so it does not depend on a race.
     */
    @Test
    fun eachInstanceChecksTheLatestOwnerInsideItsReadEdit() = runBlocking {
        val a = DataStoreLastSelectedBankStore(context)
        val b = DataStoreLastSelectedBankStore(context)

        seedOthers(context)
        val others = withoutBank(inMemory(context))
        a.remember("u1", "hana")
        b.remember("u2", "sc")
        assertNull("A 가 자기가 쓴 owner 를 기억해 비교했다", a.read("u1"))
        val disk = onDisk(context)
        assertFalse("은행 owner 가 남아 있다", BANK_OWNER in disk)
        assertFalse("은행 값이 남아 있다", BANK in disk)
        assertEquals("은행 밖 값이 바뀌었다", others, withoutBank(disk))

        clear()
        seedOthers(context)
        a.remember("u1", "hana")
        assertNull(b.read("u2"))
        assertNull("다른 인스턴스가 지운 값을 A 가 돌려줬다", a.read("u1"))
        val cleared = onDisk(context)
        assertFalse("(b) 은행 owner 가 남아 있다", BANK_OWNER in cleared)
        assertFalse("(b) 은행 값이 남아 있다", BANK in cleared)
        assertEquals("(b) 은행 밖 값이 바뀌었다", others, withoutBank(cleared))
    }

    /**
     * A bank with no owner, or a blank one, belongs to nobody and is cleared, not handed out.
     *
     * remember always writes the two together, so these states only come from a file this build did
     * not write; the read is where the rule has to hold.
     */
    @Test
    fun anOrphanBankIsClearedRatherThanAdopted() = runBlocking {
        listOf<String?>(null, "", "   ").forEach { owner ->
            clear()
            seedOthers(context)
            seed(context) { p ->
                if (owner == null) p.remove(BANK_OWNER) else p[BANK_OWNER] = owner
                p[BANK] = "hana"
            }
            val others = withoutBank(inMemory(context))

            assertNull("owner=$owner 의 은행을 돌려줬다", store.read("u1"))

            val disk = onDisk(context)
            assertFalse("owner=$owner: 은행 owner 가 남아 있다", BANK_OWNER in disk)
            assertFalse("owner=$owner: 은행 값이 남아 있다", BANK in disk)
            assertEquals("owner=$owner: 은행 밖 값이 바뀌었다", others, withoutBank(disk))
        }

        // Control: this UID's owner with no bank is an honest "nothing remembered" and is left alone.
        clear()
        seedOthers(context)
        seed(context) { p -> p[BANK_OWNER] = "u1" }
        val bytes = file().readBytes()
        assertNull(store.read("u1"))
        assertArrayEquals("값 없는 owner 일치에서 파일을 바꿨다", bytes, file().readBytes())
    }

    /**
     * No bank operation changes anything that is not the bank: every rate row axis of every list,
     * and any other tenant's entry of any type.
     */
    @Test
    fun bankOperationsPreserveEveryNonBankEntry() = runBlocking {
        val operations: List<Pair<String, suspend () -> Unit>> = listOf(
            "같은 UID remember" to suspend { store.remember("u1", "hana") },
            "같은 UID read" to suspend { store.read("u1").let { } },
            "다른 UID remember" to suspend { store.remember("u2", "sc") },
            "다른 UID read" to suspend { store.read("u2").let { } },
        )
        operations.forEach { (name, operation) ->
            clear()
            seedOthers(context)
            seed(context) { p ->
                p[BANK_OWNER] = "u1"
                p[BANK] = "kb"
            }
            val others = withoutBank(inMemory(context))

            operation()

            assertEquals("$name: 메모리의 은행 밖 값이 바뀌었다", others, withoutBank(inMemory(context)))
            val disk = onDisk(context)
            assertEquals("$name: 디스크의 은행 밖 값이 바뀌었다", others, withoutBank(disk))
            if (name == "다른 UID read") {
                assertFalse("$name: 은행 owner 가 남아 있다", BANK_OWNER in disk)
                assertFalse("$name: 은행 값이 남아 있다", BANK in disk)
            }
        }
    }

    /** remember adds the agreed two keys and nothing else; replacing an owner keeps the same two. */
    @Test
    fun rememberAddsExactlyTheTwoAgreedBankKeys() = runBlocking {
        seedOthers(context)
        val seeded = inMemory(context).keys.map { it.name }.toSet()
        val bank = setOf(BANK_OWNER.name, BANK.name)

        store.remember("u1", "hana")
        assertEquals("remember 가 합의 밖 키를 만들었다", seeded + bank, onDisk(context).keys.map { it.name }.toSet())

        store.remember("u2", "sc")
        val replaced = onDisk(context)
        assertEquals("owner 교체가 합의 밖 키를 만들었다", seeded + bank, replaced.keys.map { it.name }.toSet())
        assertEquals("u2", replaced[BANK_OWNER])
        assertEquals("sc", replaced[BANK])

        assertNull(store.read("u3"))
        assertEquals("불일치 read 뒤 키가 남았다", seeded, onDisk(context).keys.map { it.name }.toSet())
    }

    /**
     * The store keeps the code it was given and gives back the code it holds.
     *
     * Which codes are offered, and what an unknown one falls back to, belong to the editor model; a
     * store that mapped them would be a second place that decides.
     */
    @Test
    fun bankCodesAreStoredAndReturnedVerbatim() = runBlocking {
        RAW_CODES.forEach { code ->
            clear()
            store.remember("u1", code)
            assertEquals("remember→read: [$code]", code, store.read("u1"))
            assertEquals("새 인스턴스: [$code]", code, DataStoreLastSelectedBankStore(context).read("u1"))
            assertEquals("디스크: [$code]", code, onDisk(context)[BANK])
            assertEquals("u1", onDisk(context)[BANK_OWNER])
        }
        // Written by the file, not by this store: what the reader does on its own.
        RAW_CODES.forEach { code ->
            clear()
            seed(context) { p ->
                p[BANK_OWNER] = "u1"
                p[BANK] = code
            }
            assertEquals("seed→read: [$code]", code, store.read("u1"))
        }
    }

    private companion object {
        const val ROUNDS = 25
        val BANKS = listOf("kb", "hana", "shinhan", "woori", "ibk")
        val BLANKS = listOf("", " ", "  ", "\t", "\n", "\r\n", " \t ", " ")
        val RAW_CODES = listOf("citi", "future_bank", "", "  hana  ")
    }
}
