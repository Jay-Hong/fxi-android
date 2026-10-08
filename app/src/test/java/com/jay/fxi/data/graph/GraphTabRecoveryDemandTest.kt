package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessEnd
import com.jay.fxi.data.entitlements.TopicAccessEndReason
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicGraphAuthority
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphEventKind
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicGraphPath
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAttribution
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphCatalogPeriod
import com.jay.fxi.domain.model.GraphCatalogTab
import com.jay.fxi.domain.model.GraphPeriod
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 RT03b-1a contract (API agreed in R4c/S4 rt03b1a_api_agreed.r2): the recorder's pure query of what recovery
 * it still owes one tab's 1d series. Q1a-02..Q1a-06 run the reducer; the recorder's own call (supplier reads, no stored
 * change, a closed recorder) is GraphRecorderTest's Q1a-01.
 *
 * Fixture: the reducer test's access and input shapes (named arguments kept), a catalog of usd (investing, kb, hana, dxy)
 * and jpy (kb) 1d series, and kb's first quote (20:01) adopted at 20:02, whose INITIAL_SYNC demands the bucket of the
 * adoption time, 20:00.
 */
class GraphTabRecoveryDemandTest {
    private fun kst(day: Int, hhmmss: String): Instant =
        Instant.parse("2026-10-${day.toString().padStart(2, '0')}T$hhmmss+09:00")

    private val adoptedAt = kst(7, "20:02:00")
    private val grant = TopicGrantToken(7L)
    private val identity = AuthIdentityFence("u1", 1L)
    private val fenceN = TopicSessionFence(identity, "e1", grant)
    private val fenceN2 = TopicSessionFence(identity, "e2", grant)
    private val scopeN = GraphDataScope("u1", "e1")
    private val lifetime = TopicUseLifetime(grant, 3L)

    private val kb = GraphObservationSeriesKey(scopeN, "kb.usd")
    private val kbJpy = GraphObservationSeriesKey(scopeN, "kb.jpy")

    private val catalog = GraphCatalog(
        1.hours,
        mapOf(
            "usd" to GraphCatalogTab("usd", "usd", emptyMap(), mapOf(
                GraphPeriod.ONE_DAY to GraphCatalogPeriod(listOf("investing.usd", "kb.usd", "hana.usd", "dxy"), listOf("kb.usd"))
            )),
            "jpy" to GraphCatalogTab("jpy", "jpy", emptyMap(), mapOf(
                GraphPeriod.ONE_DAY to GraphCatalogPeriod(listOf("kb.jpy"), listOf("kb.jpy"))
            ))
        )
    )

    /** usd offers only 3m; jpy keeps its 1d. */
    private val usdWithoutOneDay = GraphCatalog(
        1.hours,
        mapOf(
            "usd" to GraphCatalogTab("usd", "usd", emptyMap(), mapOf(
                GraphPeriod.THREE_MONTHS to GraphCatalogPeriod(listOf("kb.usd"), listOf("kb.usd"))
            )),
            "jpy" to GraphCatalogTab("jpy", "jpy", emptyMap(), mapOf(
                GraphPeriod.ONE_DAY to GraphCatalogPeriod(listOf("kb.jpy"), listOf("kb.jpy"))
            ))
        )
    )

    private fun attribution(owner: TopicSessionFence = fenceN) = TopicUseAttribution(Any(), owner, 1L, lifetime)

    private fun usdQuote(at: Instant) = TopicGraphInput.Observations(
        1L, "fx:usd-krw", TopicGraphPath.WS, attribution(), 1L,
        listOf(TopicGraphCandidate.Quote("kb", "usd-krw", 1341.7, at, null))
    )

    private fun jpyQuote(at: Instant) = TopicGraphInput.Observations(
        1L, "fx:jpy-krw", TopicGraphPath.WS, attribution(), 1L,
        listOf(TopicGraphCandidate.Quote("kb", "jpy-krw", 912.3, at, null))
    )

    private fun dollar(at: Instant) = TopicGraphInput.Observations(
        1L, "dxy:spot", TopicGraphPath.WS, attribution(), 1L,
        listOf(TopicGraphCandidate.DollarIndex(99.1, at, "investing"))
    )

    /** A usd delivery interruption held as a Continuity input. */
    private fun usdInterrupted() = TopicGraphInput.Continuity(
        1L, TopicGraphEventKind.DELIVERY_INTERRUPTED, null, setOf("fx:usd-krw"), setOf(TopicGraphPath.WS),
        TopicGraphAuthority(Any(), fenceN, 1L, lifetime), 1L, 1_000L
    )

    private fun snap(
        revision: Long,
        userEnd: Long? = null,
        userBlocks: Set<TopicAccessBlock> = emptySet(),
        namespace: String? = "e1"
    ) = TopicAccessSnapshot(
        revision = revision,
        facts = TopicAccessFacts.NONE.copy(
            token = TopicGrantToken(7L),
            binding = EntitlementsIdentity("u1", 1L),
            tokenStanding = true,
            userBlocks = userBlocks,
            capabilityBlocks = emptySet()
        ),
        userInvalidations = 3L,
        lastUserEnd = userEnd?.let {
            TopicAccessEnd(it, TopicAccessEndReason.AUTHORITATIVE_LOSS, EntitlementsIdentity("u1", 1L), "u1", namespace)
        },
        lastCapabilityEnd = null
    )

    private val R = GraphRecorderReducer

    private fun ready(): GraphRecorderState = R.syncAccess(R.empty(), snap(1), fenceN)

    /** kb.usd's 20:01 quote adopted at 20:02: its INITIAL_SYNC demand sits on bucket 20:00. */
    private fun withKb(): GraphRecorderState {
        val s = R.observe(ready(), usdQuote(kst(7, "20:01:00")), catalog, snap(1), fenceN, true, adoptedAt)
        assertEquals("premise: kb's own bucket is demanded", setOf(kst(7, "20:00:00")), s.series.getValue(kb).pending.keys)
        return s
    }

    private fun query(
        s: GraphRecorderState,
        tab: String = "usd",
        at: Instant,
        catalog: GraphCatalog? = this.catalog,
        snapshot: TopicAccessSnapshot = snap(1),
        fence: TopicSessionFence? = fenceN,
        admission: Boolean = true
    ) = R.recoveryDemand(s, tab, catalog, snapshot, fence, admission, at)

    /**
     * Q1a-02: access that is not usable is Unreadable, and it is judged before the catalog. A discard on a valid new scope
     * or user end is judged after it: neither the old demand nor the old held input counts. A discard whose candidate is not
     * usable is still Unreadable.
     */
    @Test fun Q1a02_accessThatIsNotUsableIsUnreadable() {
        val later = kst(7, "20:12:00")
        val s = R.offerPending(withKb(), usdQuote(kst(7, "20:11:00")), snap(1), fenceN, true)
        assertEquals("premise", GraphTabRecoveryDemand.Pending(closed = true, mappingWait = true), query(s, at = later))
        assertEquals("no current scope", GraphTabRecoveryDemand.Unreadable, query(s, at = later, fence = null))
        assertEquals("user axis blocked", GraphTabRecoveryDemand.Unreadable,
            query(s, at = later, snapshot = snap(1, userBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE))))
        assertEquals("admission refused", GraphTabRecoveryDemand.Unreadable, query(s, at = later, admission = false))
        assertEquals("an older revision", GraphTabRecoveryDemand.Unreadable, query(s, at = later, snapshot = snap(0)))
        assertEquals("access is judged before the catalog", GraphTabRecoveryDemand.Unreadable,
            query(s, at = later, fence = null, catalog = null))
        assertEquals("a refused admission hides a missing tab", GraphTabRecoveryDemand.Unreadable,
            query(s, tab = "eur", at = later, admission = false))
        assertEquals("a new scope discards the old demand and held input, then reads nothing owed", GraphTabRecoveryDemand.None,
            query(s, at = later, snapshot = snap(2), fence = fenceN2))
        assertEquals("and without a catalog it cannot resolve the tab", GraphTabRecoveryDemand.CatalogRequired,
            query(s, at = later, snapshot = snap(2), fence = fenceN2, catalog = null))
        assertEquals("a user end discards both, then reads nothing owed", GraphTabRecoveryDemand.None,
            query(s, at = later, snapshot = snap(2, userEnd = 1)))
        assertEquals("a discard whose candidate is refused is Unreadable", GraphTabRecoveryDemand.Unreadable,
            query(s, at = later, snapshot = snap(2), fence = fenceN2, admission = false))
    }

    /**
     * Q1a-03: without the tab's 1d series in the catalog the answer is CatalogRequired, whatever is held - nothing, a demand,
     * a held input, a lost topic or untransferred series; a resolved tab with nothing owed is None.
     */
    @Test fun Q1a03_aCatalogThatDoesNotResolveTheTabIsCatalogRequired() {
        val later = kst(7, "20:12:00")
        assertEquals("no catalog, nothing held", GraphTabRecoveryDemand.CatalogRequired, query(ready(), at = later, catalog = null))
        assertEquals("no catalog, a demand held", GraphTabRecoveryDemand.CatalogRequired, query(withKb(), at = later, catalog = null))
        val heldInput = R.offerPending(ready(), usdQuote(kst(7, "20:11:00")), snap(1), fenceN, true)
        assertEquals("no catalog, an input held", GraphTabRecoveryDemand.CatalogRequired, query(heldInput, at = later, catalog = null))
        val lost = R.loseTopics(ready(), scopeN, mapOf("fx:usd-krw" to 3L), null, snap(1), fenceN, later)
        assertEquals("no catalog, a lost topic held", GraphTabRecoveryDemand.CatalogRequired, query(lost, at = later, catalog = null))
        val untransferred = R.loseTopics(ready(), scopeN, mapOf("fx:usd-krw" to 3L), catalog, snap(1), fenceN, later)
        assertTrue("premise: untransferred", untransferred.untransferredSeries.isNotEmpty())
        assertEquals("no catalog, untransferred series held", GraphTabRecoveryDemand.CatalogRequired,
            query(untransferred, at = later, catalog = null))
        assertEquals("no such tab", GraphTabRecoveryDemand.CatalogRequired, query(withKb(), tab = "eur", at = later))
        assertEquals("no such tab, nothing held", GraphTabRecoveryDemand.CatalogRequired, query(ready(), tab = "eur", at = later))
        assertEquals("no 1d for the tab", GraphTabRecoveryDemand.CatalogRequired,
            query(withKb(), at = later, catalog = usdWithoutOneDay))
        assertEquals("resolved, nothing owed", GraphTabRecoveryDemand.None, query(ready(), at = later))
    }

    /**
     * Q1a-04: closed demands are those from 24 h before the current bucket up to, not including, it - the current and later
     * buckets are not closed - and only the tab's own 1d series count.
     */
    @Test fun Q1a04_onlyClosedDemandsOfTheRetainedWindowCount() {
        val s = withKb()
        assertEquals("its own bucket is still current", GraphTabRecoveryDemand.None, query(s, at = kst(7, "20:09:59")))
        assertEquals("a later bucket than now", GraphTabRecoveryDemand.None, query(s, at = kst(7, "19:59:59")))
        assertEquals("closed once the next bucket starts", GraphTabRecoveryDemand.Pending(closed = true, mappingWait = false),
            query(s, at = kst(7, "20:10:00")))
        assertEquals("still inside the window 24 h later", GraphTabRecoveryDemand.Pending(closed = true, mappingWait = false),
            query(s, at = kst(8, "20:09:59")))
        assertEquals("out of the window one bucket later", GraphTabRecoveryDemand.None, query(s, at = kst(8, "20:10:00")))

        val jpy = R.observe(ready(), jpyQuote(kst(7, "20:01:00")), catalog, snap(1), fenceN, true, adoptedAt)
        assertTrue("premise: kb.jpy's bucket is demanded", jpy.series.getValue(kbJpy).pending.isNotEmpty())
        assertEquals("another tab's demand is not usd's", GraphTabRecoveryDemand.None, query(jpy, at = kst(7, "20:12:00")))
        assertEquals("it is jpy's", GraphTabRecoveryDemand.Pending(closed = true, mappingWait = false),
            query(jpy, tab = "jpy", at = kst(7, "20:12:00")))
    }

    /** Q1a-05: held inputs, lost topics and untransferred series wait for mapping only where they map to the tab. */
    @Test fun Q1a05_mappingWaitsCountOnlyForTheTab() {
        val at = kst(7, "20:12:00")
        val heldUsd = R.offerPending(ready(), usdQuote(kst(7, "20:11:00")), snap(1), fenceN, true)
        assertTrue("premise: held", heldUsd.pending.inputs.isNotEmpty())
        assertEquals(GraphTabRecoveryDemand.Pending(closed = false, mappingWait = true), query(heldUsd, at = at))
        assertEquals("a usd input is not jpy's", GraphTabRecoveryDemand.None, query(heldUsd, tab = "jpy", at = at))

        val heldResume = R.offerPending(ready(), usdInterrupted(), snap(1), fenceN, true)
        assertTrue("premise: a Continuity input held", heldResume.pending.inputs.single() is TopicGraphInput.Continuity)
        assertEquals("a held Continuity's topics map too", GraphTabRecoveryDemand.Pending(closed = false, mappingWait = true),
            query(heldResume, at = at))
        assertEquals(GraphTabRecoveryDemand.None, query(heldResume, tab = "jpy", at = at))

        val heldDollar = R.offerPending(ready(), dollar(kst(7, "20:11:00")), snap(1), fenceN, true)
        assertEquals("the dollar index maps to usd's dxy", GraphTabRecoveryDemand.Pending(closed = false, mappingWait = true),
            query(heldDollar, at = at))
        assertEquals("not to jpy", GraphTabRecoveryDemand.None, query(heldDollar, tab = "jpy", at = at))

        val lostDollar = R.loseTopics(ready(), scopeN, mapOf("dxy:spot" to 3L), null, snap(1), fenceN, at)
        assertTrue("premise: a lost dollar topic waits", lostDollar.pending.lostTopics.isNotEmpty())
        assertEquals("a lost dollar topic maps to usd's dxy", GraphTabRecoveryDemand.Pending(closed = false, mappingWait = true),
            query(lostDollar, at = at))
        assertEquals(GraphTabRecoveryDemand.None, query(lostDollar, tab = "jpy", at = at))

        val lost = R.loseTopics(ready(), scopeN, mapOf("fx:usd-krw" to 3L), null, snap(1), fenceN, at)
        assertTrue("premise: a lost topic waits", lost.pending.lostTopics.isNotEmpty())
        assertEquals(GraphTabRecoveryDemand.Pending(closed = false, mappingWait = true), query(lost, at = at))
        assertEquals(GraphTabRecoveryDemand.None, query(lost, tab = "jpy", at = at))

        val untransferred = R.loseTopics(ready(), scopeN, mapOf("fx:usd-krw" to 3L), catalog, snap(1), fenceN, at)
        assertTrue("premise: series wait for their handover",
            untransferred.untransferredSeries.isNotEmpty() && untransferred.pending.lostTopics.isEmpty())
        assertEquals(GraphTabRecoveryDemand.Pending(closed = false, mappingWait = true), query(untransferred, at = at))
        assertEquals(GraphTabRecoveryDemand.None, query(untransferred, tab = "jpy", at = at))
    }

    /** Q1a-06: closed alone, a mapping wait alone, both, neither; a Pending needs one of them. */
    @Test fun Q1a06_combinations() {
        val at = kst(7, "20:12:00")
        val both = R.offerPending(withKb(), usdQuote(kst(7, "20:11:00")), snap(1), fenceN, true)
        assertEquals(GraphTabRecoveryDemand.Pending(closed = true, mappingWait = true), query(both, at = at))
        assertEquals(GraphTabRecoveryDemand.Pending(closed = true, mappingWait = false), query(withKb(), at = at))
        val mappingOnly = R.offerPending(ready(), usdQuote(kst(7, "20:11:00")), snap(1), fenceN, true)
        assertEquals(GraphTabRecoveryDemand.Pending(closed = false, mappingWait = true), query(mappingOnly, at = at))
        assertEquals(GraphTabRecoveryDemand.None, query(withKb(), at = kst(7, "20:05:00")))
        assertThrows(IllegalArgumentException::class.java) { GraphTabRecoveryDemand.Pending(closed = false, mappingWait = false) }
    }
}
