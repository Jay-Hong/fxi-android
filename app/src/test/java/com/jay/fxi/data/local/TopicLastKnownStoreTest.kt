package com.jay.fxi.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.domain.model.TopicDollarIndex
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned S3-R2 contract (S3R2/decl_codex.r1.md, R2 rows 1-8): display seeds per `(uid, userAccessEpoch)`, only what the
 * rate lists accept, never KRX, written at most one throttle window after a change, and gone for good once their namespace is
 * purged. Real file DataStore and real time, with the throttle shortened to [THROTTLE] so the window can be watched; every wait
 * polls for the state it needs rather than sleeping a fixed time. The
 * implementation thread reads but does not edit this file.
 */
class TopicLastKnownStoreTest {

    private companion object {
        const val THROTTLE = 300L
        val A = TopicLastKnownOwner("uid-a", "epoch-1")
        val A2 = TopicLastKnownOwner("uid-a", "epoch-2")
        val B = TopicLastKnownOwner("uid-b", "epoch-1")
        val T0: Instant = Instant.parse("2026-09-29T00:00:00Z")
    }

    @get:Rule
    val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private var dataStore: DataStore<Preferences>? = null

    @After
    fun tearDown() = runBlocking { scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() } }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    /** A new store object over the same DataStore — what a restore reads, not a proof of surviving a process restart. */
    private fun open(clock: TopicLastKnownClock = TopicLastKnownClock { System.nanoTime() / 1_000_000 }): TopicLastKnownStore {
        val store = dataStore ?: PreferenceDataStoreFactory.create(scope = scope()) { File(folder.root, "last_known.preferences_pb") }
            .also { dataStore = it }
        return TopicLastKnownStore(store, clock, THROTTLE, scope())
    }

    private suspend fun disk(): Map<Preferences.Key<*>, Any> = checkNotNull(dataStore).data.first().asMap()

    private fun q(source: String, asset: String, rate: Double, at: Instant = T0) = TopicQuote(source, asset, rate, at)

    private val sample = TopicRates(dollarIndex = TopicDollarIndex(98.5, T0, "investing")).merge(
        listOf(
            q("kb", "usd-krw", 1400.0), q("investing", "usd-krw", 1399.0), q("hana", "jpy-krw", 950.0),
            q("kb", "eur-krw", 1600.0), q("upbit", "usdt-krw", 1420.0), q("citi", "usd-krw", 1398.0)
        )
    )

    /** Offers, then waits until a fresh store object restores [expected] — however many writes that takes. */
    private suspend fun roundTrip(owner: TopicLastKnownOwner, rates: TopicRates, expected: TopicRates = rates) {
        open().offer(owner, rates)
        withTimeout(5_000) { while (open().restore(owner) != expected) delay(20) }
    }

    @Test
    fun K01_nothingStored_restoresNothing() = runBlocking {
        assertEquals(TopicRates(), open().restore(A))
    }

    @Test
    fun K02_whatTheListsAccept_isRestoredExactly_citiIncluded() = runBlocking {
        roundTrip(A, sample)
        assertEquals(sample, open().restore(A))
    }

    @Test
    fun K03_futuresAndUnknownSources_areNeverStored() = runBlocking {
        val tainted = sample.merge(listOf(q("krx", "usd-krw-futures", 1405.0), q("zz_unknown", "usd-krw", 1401.0), q("binance", "usdt-krw", 1419.0)))
        roundTrip(A, tainted, expected = sample)
        assertTrue("krx reached the disk", disk().values.none { it.toString().contains("krx") })
    }

    @Test
    fun K04_impossiblePrices_areNeverStored() = runBlocking {
        val bad = sample.merge(
            listOf(
                q("hana", "usd-krw", Double.NaN), q("shinhan", "usd-krw", 0.0), q("woori", "usd-krw", -1.0),
                q("ibk", "usd-krw", Double.POSITIVE_INFINITY), q("nh", "usd-krw", 1e9)
            )
        )
        roundTrip(A, bad, expected = sample)
        roundTrip(B, TopicRates(dollarIndex = TopicDollarIndex(Double.NaN, T0, "investing")).merge(listOf(q("kb", "usd-krw", 1400.0))),
            expected = TopicRates().merge(listOf(q("kb", "usd-krw", 1400.0))))
        assertEquals("an impossible dollar index was stored", null, open().restore(B).dollarIndex)
    }

    @Test
    fun K04b_anEntryReadBackWithTheWrongSourceOrAnImpossiblePrice_isDropped_andTheRestSurvive() = runBlocking {
        roundTrip(A, sample)
        val stored = disk().mapKeys { it.key.name }.mapValues { it.value.toString() }
        val tether = stored.entries.single { it.value.contains("upbit") }.key
        val dollar = stored.entries.single { it.value.contains("1399.0") }.key
        val index = stored.entries.single { it.value.contains("98.5") }.key
        checkNotNull(dataStore).edit {
            it[stringPreferencesKey(tether)] = stored.getValue(tether).replace("upbit", "binance")
            it[stringPreferencesKey(dollar)] = stored.getValue(dollar).replace("1399.0", "-1.0")
            it[stringPreferencesKey(index)] = stored.getValue(index).replace("98.5", "-98.5")
        }
        val restored = open().restore(A)
        assertTrue("a relabelled exchange came back", restored.quotes.keys.none { it.asset == "usdt-krw" })
        assertTrue("an impossible price came back", restored.quotes.keys.none { it.source == "investing" })
        assertEquals("an impossible dollar index came back", null, restored.dollarIndex)
        assertEquals(
            "an untouched quote was lost",
            sample.quotes.filterKeys { it.asset != "usdt-krw" && it.source != "investing" },
            restored.quotes.filterKeys { it.asset != "usdt-krw" && it.source != "investing" }
        )
    }

    @Test
    fun K05_aDamagedEntry_isDroppedOnRead_andTheRestSurvive() = runBlocking {
        roundTrip(A, sample)
        val keys = disk().keys.map { it.name }
        assertTrue("fixture: more than one entry was written", keys.size > 1)
        val damaged = keys.first { key -> disk()[stringPreferencesKey(key)].toString().contains("upbit") }
        checkNotNull(dataStore).edit { it[stringPreferencesKey(damaged)] = "{not json" }
        val restored = open().restore(A)
        assertTrue("the damaged tether entry came back", restored.quotes.keys.none { it.asset == "usdt-krw" })
        assertEquals("an undamaged entry was lost", sample.quotes.filterKeys { it.asset != "usdt-krw" }, restored.quotes)
        assertEquals(sample.dollarIndex, restored.dollarIndex)
    }

    @Test
    fun K06_eachUserAndEpoch_seesOnlyItsOwn() = runBlocking {
        roundTrip(A, sample)
        assertEquals(TopicRates(), open().restore(B))
        assertEquals(TopicRates(), open().restore(A2))
        val other = TopicRates().merge(listOf(q("hana", "usd-krw", 1500.0)))
        roundTrip(B, other)
        assertEquals(sample, open().restore(A))
        assertEquals(other, open().restore(B))
    }

    @Test
    fun K07_aChangeIsNotWrittenAtOnce_butWithinTheWindow_evenWhileChangesKeepComing() = runBlocking {
        val store = open()
        val offeredAt = System.nanoTime()
        store.offer(A, sample)
        val early = disk()
        if ((System.nanoTime() - offeredAt) / 1_000_000 < THROTTLE / 2) {
            assertEquals("written well before the window", emptyMap<Preferences.Key<*>, Any>(), early)
        }
        withTimeout(THROTTLE * 5) { while (disk().isEmpty()) delay(10) }

        // Keep changing well past several windows: a write must land while the changes are still arriving.
        var rate = 1400.0
        val start = System.nanoTime()
        var landedWhileChanging = false
        val written = disk()
        while ((System.nanoTime() - start) / 1_000_000 < THROTTLE * 5) {
            rate += 1.0
            store.offer(A, sample.merge(listOf(q("kb", "usd-krw", rate, T0.plus(kotlin.time.Duration.parse("${rate.toLong()}s"))))))
            if (disk() != written) landedWhileChanging = true
            delay(THROTTLE / 6)
        }
        assertTrue("continuous changes postponed every write", landedWhileChanging)
    }

    @Test
    fun K08_aPurge_removesEveryOtherNamespace_andAWriteStillPendingForOneDoesNotBringItBack() = runBlocking {
        roundTrip(A, sample)
        roundTrip(B, sample)
        val store = open()
        store.offer(A2, sample)                 // pending, not yet written
        store.purgeAllExcept(B)
        delay(THROTTLE * 3)                     // past the pending write's window
        assertEquals(TopicRates(), open().restore(A))
        assertEquals(TopicRates(), open().restore(A2))
        assertEquals(sample, open().restore(B))

        store.offer(A, sample)                  // a retired owner, after the purge returned
        delay(THROTTLE * 3)
        assertEquals("a late write for a retired owner brought it back", TopicRates(), open().restore(A))
    }

    @Test
    fun K09_anOfferMissingAKind_leavesThatKindsSeed_onlyAPurgeRemoves() = runBlocking {
        // A group absent from what is offered is not a deletion (the TopicRates rule): early in a session only some topics
        // have answered, and the seeds of the rest must survive until they do.
        roundTrip(A, sample)
        val onlyDollar = TopicRates().merge(listOf(q("kb", "usd-krw", 1410.0, T0.plus(kotlin.time.Duration.parse("1s")))))
        val expected = sample.merge(onlyDollar.quotes.values.toList()).let { merged ->
            // the usd-krw kind is replaced by what was offered for it
            merged.copy(quotes = merged.quotes.filterKeys { it.asset != "usd-krw" } + onlyDollar.quotes)
        }
        roundTrip(A, onlyDollar, expected = expected)
        assertEquals(sample.dollarIndex, open().restore(A).dollarIndex)
    }

    @Test
    fun K10_nothingIsWritten_untilTheStoresClockReachesTheWindowsEnd() = runBlocking {
        var now = 0L
        val store = open { now }
        store.offer(A, sample)
        delay(THROTTLE * 2)                     // real time passes; the store's clock does not
        assertEquals("written before its clock reached the window's end", emptyMap<Preferences.Key<*>, Any>(), disk())
        now = THROTTLE
        withTimeout(5_000) { while (disk().isEmpty()) delay(20) }
    }
}
