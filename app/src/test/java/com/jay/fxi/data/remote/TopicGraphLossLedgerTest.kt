package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.graph.GraphDataScope
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 RT01-B1 (RT01-B design rt01b_design_codex.r1, AGREE_SPLIT; API agreed in rt01b1_api_codex.r1 from
 * rt01b1_api_proposal.r1): the topic graph-loss ledger on its own. It holds losses keyed by data scope and the original
 * lifetime's invalidation count, or in one unscoped bucket for an owner or epoch that is missing, and rebuilds the
 * session's cumulative view from what remains. A purge removes only what its selector picks and installs the boundary
 * its retirement names: a retired scope refuses every later loss, and an ended-use floor refuses a lifetime below it
 * (equality passes) or none at all. Revision counts accepted losses and purges that removed something.
 *
 * TopicSessionCoordinatorTest B07a-B07c check that the ledger reproduces the live session's own graphLoss for the losses a
 * session produces, across two data scopes; selective purges, boundaries and the rest of the view rules rest on these
 * rows. RT01-B1 changes nothing in the session.
 */
class TopicGraphLossLedgerTest {

    private val session = Any()
    private val a = GraphDataScope("u1", "e1")
    private val b = GraphDataScope("u1", "e2")
    private val c = GraphDataScope("u2", "e1")
    private val d = GraphDataScope("u3", "e1")
    private val e = GraphDataScope("u4", "e1")
    private val at = Instant.parse("2026-10-08T00:00:00Z")

    private fun owner(scope: GraphDataScope, grant: Long = 1L) =
        TopicSessionFence(AuthIdentityFence(scope.uid, 1L), scope.userAccessEpoch, TopicGrantToken(grant))

    private fun noEpoch(uid: String = "u1") = TopicSessionFence(AuthIdentityFence(uid, 1L), null, TopicGrantToken(9L))

    private fun obs(
        sequence: Long,
        owner: TopicSessionFence,
        invalidations: Long = 0L,
        topic: String = "fx:usd-krw",
        path: TopicGraphPath = TopicGraphPath.WS,
        grantEpoch: Long = 1L,
        prices: Int = 1
    ) = TopicGraphInput.Observations(
        sequence, topic, path, TopicUseAttribution(session, owner, grantEpoch, TopicUseLifetime(owner.grant, invalidations)),
        1L, List(prices) { TopicGraphCandidate.Quote("kb", "usd-krw", 1390.0 + it, at, null) }
    )

    private fun cont(
        sequence: Long,
        owner: TopicSessionFence?,
        invalidations: Long?,
        topics: Set<String> = setOf("usdt:krw"),
        paths: Set<TopicGraphPath> = setOf(TopicGraphPath.WS),
        occurredAt: Long = 0L,
        grantEpoch: Long = 1L
    ) = TopicGraphInput.Continuity(
        sequence, TopicGraphEventKind.AUTHORITY_ENDED, null, topics, paths,
        TopicGraphAuthority(session, owner, grantEpoch, invalidations?.let { TopicUseLifetime(checkNotNull(owner).grant, it) }),
        1L, occurredAt
    )

    private fun key(owner: TopicSessionFence?, grantEpoch: Long = 1L) = TopicGraphAuthorityKey(owner, grantEpoch)

    private fun retire(vararg scopes: GraphDataScope) = GraphLossRetirement(retiredScopes = scopes.toSet())
    private fun floors(vararg floors: Pair<GraphDataScope, Long>) = GraphLossRetirement(endedUseFloors = floors.toMap())

    /** The revision after one more accepted loss, read from the rebuilt view: refusals and boundaries must not have moved it. */
    private fun revisionAfterOneMore(l: TopicGraphLossLedger, sequence: Long): Long {
        assertTrue("probe accepted", l.record(cont(sequence, null, null), TopicGraphOffer.FULL, 0L))
        return checkNotNull(l.view).revision
    }

    /** L01: only FULL, CLOSED and FAILED count, one per batch; first and last follow the sequence and the given loss time. */
    @Test
    fun L01_onlyLossesCount_oneBatchAtATime_andFirstLastFollowTheSequence() {
        val l = TopicGraphLossLedger()
        assertNull(l.view)
        assertFalse("ENQUEUED is no loss", l.record(obs(1, owner(a)), TopicGraphOffer.ENQUEUED, 100L))
        assertFalse("DORMANT is no loss", l.record(obs(1, owner(a)), TopicGraphOffer.DORMANT, 100L))
        assertNull(l.view)

        assertTrue(l.record(input = obs(1, owner(a), prices = 3), offer = TopicGraphOffer.FULL, lossAtEpochMillis = 100L))
        assertEquals(
            "a batch of three prices is one loss",
            TopicGraphLoss(1, 1, 1, 1, setOf(TopicGraphOffer.FULL), setOf("fx:usd-krw"), setOf(TopicGraphPath.WS), 100L, 100L, setOf(key(owner(a)))),
            l.view
        )
        assertTrue(
            l.record(
                cont(2, owner(b), 0L, setOf("usdt:krw", "dxy:spot"), setOf(TopicGraphPath.WS, TopicGraphPath.REST_BOOTSTRAP), occurredAt = 999L),
                TopicGraphOffer.CLOSED, 80L
            )
        )
        assertEquals("the continuity's loss time given, not its own clock", 80L, checkNotNull(l.view).lastOccurredAtEpochMillis)
        assertTrue(l.record(obs(3, owner(a), topic = "fx:jpy-krw"), TopicGraphOffer.FAILED, 70L))
        assertEquals(
            "first and last by sequence: the clock went back across scopes; the continuity's paths are kept",
            TopicGraphLoss(
                3, 3, 1, 3,
                setOf(TopicGraphOffer.FULL, TopicGraphOffer.CLOSED, TopicGraphOffer.FAILED),
                setOf("fx:usd-krw", "usdt:krw", "dxy:spot", "fx:jpy-krw"),
                setOf(TopicGraphPath.WS, TopicGraphPath.REST_BOOTSTRAP),
                100L, 70L,
                setOf(key(owner(a)), key(owner(b)))
            ),
            l.view
        )
    }

    /** L02: a loss with no owner or no epoch sits in the unscoped bucket, which no selector or boundary ever reaches. */
    @Test
    fun L02_theUnscopedBucketIsNeverSelectedOrBounded() {
        val l = TopicGraphLossLedger()
        assertTrue(l.record(cont(1, null, null, setOf("fx:usd-krw")), TopicGraphOffer.CLOSED, 10L))
        assertTrue(l.record(obs(2, noEpoch()), TopicGraphOffer.FULL, 20L))
        assertTrue(l.record(obs(3, owner(a)), TopicGraphOffer.FULL, 30L))

        assertTrue(l.purge({ true }, retire(a)))
        assertEquals(
            "only the scoped loss went",
            TopicGraphLoss(4, 2, 1, 2, setOf(TopicGraphOffer.CLOSED, TopicGraphOffer.FULL), setOf("fx:usd-krw"), setOf(TopicGraphPath.WS), 10L, 20L, setOf(key(null), key(noEpoch()))),
            l.view
        )
        assertTrue("an unscoped loss still records", l.record(cont(4, null, null), TopicGraphOffer.FULL, 40L))
        assertTrue("so does one with no epoch for the same user", l.record(obs(5, noEpoch("u1")), TopicGraphOffer.FULL, 50L))
        assertEquals(4L, checkNotNull(l.view).count)
    }

    /**
     * L03: a sequence at or below the highest accepted one is refused untouched - scoped or not, one mark for all - and a
     * purge does not reset it. A refused loss does not move the mark.
     */
    @Test
    fun L03_oneSequenceMarkForAll_movedOnlyByAcceptedLosses() {
        val l = TopicGraphLossLedger()
        assertTrue(l.record(obs(5, owner(a)), TopicGraphOffer.FULL, 10L))
        val held = l.view
        assertFalse(l.record(obs(5, owner(b)), TopicGraphOffer.FULL, 20L))
        assertFalse(l.record(obs(4, owner(b)), TopicGraphOffer.FULL, 20L))
        assertFalse("an unscoped loss shares the mark", l.record(cont(3, null, null), TopicGraphOffer.FULL, 20L))
        assertEquals(held, l.view)
        assertTrue(l.purge({ it == a }, retire(a)))
        assertNull(l.view)
        assertFalse("still refused once emptied", l.record(obs(5, owner(b)), TopicGraphOffer.FULL, 30L))
        assertTrue(l.record(obs(6, owner(b)), TopicGraphOffer.FULL, 30L))
        assertFalse("a refused loss", l.record(obs(20, owner(a)), TopicGraphOffer.FULL, 40L))
        assertTrue("moves only the accepted mark: 7 is still above it", l.record(obs(7, owner(b)), TopicGraphOffer.FULL, 50L))
    }

    /**
     * L04: purging one of two interleaved scopes rebuilds the view from the other alone - its count, its first and last, its
     * sets - and counts once in the revision. Purging the same again removes nothing and counts nothing. Emptied, the view
     * is null and the next loss continues the revision.
     */
    @Test
    fun L04_aSelectivePurgeRebuildsTheViewFromWhatRemains() {
        val l = TopicGraphLossLedger()
        l.record(obs(1, owner(a), topic = "fx:usd-krw"), TopicGraphOffer.FULL, 10L)
        l.record(obs(2, owner(b), topic = "fx:jpy-krw", path = TopicGraphPath.REST_BOOTSTRAP), TopicGraphOffer.CLOSED, 20L)
        l.record(obs(3, owner(a), topic = "fx:eur-krw"), TopicGraphOffer.FAILED, 30L)
        l.record(obs(4, owner(b), topic = "usdt:krw"), TopicGraphOffer.FULL, 5L)
        assertEquals(
            "before: the last is b's later sequence, though a's clock read later",
            TopicGraphLoss(
                4, 4, 1, 4, setOf(TopicGraphOffer.FULL, TopicGraphOffer.CLOSED, TopicGraphOffer.FAILED),
                setOf("fx:usd-krw", "fx:jpy-krw", "fx:eur-krw", "usdt:krw"), setOf(TopicGraphPath.WS, TopicGraphPath.REST_BOOTSTRAP),
                10L, 5L, setOf(key(owner(a)), key(owner(b)))
            ),
            l.view
        )

        assertTrue(l.purge(selects = { it == a }, retirement = retire(a)))
        assertEquals(
            "b alone",
            TopicGraphLoss(
                5, 2, 2, 4, setOf(TopicGraphOffer.CLOSED, TopicGraphOffer.FULL), setOf("fx:jpy-krw", "usdt:krw"),
                setOf(TopicGraphPath.REST_BOOTSTRAP, TopicGraphPath.WS), 20L, 5L, setOf(key(owner(b)))
            ),
            l.view
        )
        val held = l.view
        assertFalse("nothing more to remove", l.purge({ it == a }, retire(a)))
        assertEquals("and no revision for it", held, l.view)

        assertTrue(l.purge({ it == b }, retire(b)))
        assertNull("emptied", l.view)
        assertTrue(l.record(obs(10, owner(c)), TopicGraphOffer.FULL, 50L))
        assertEquals(
            "the revision continues past the purges",
            TopicGraphLoss(7, 1, 10, 10, setOf(TopicGraphOffer.FULL), setOf("fx:usd-krw"), setOf(TopicGraphPath.WS), 50L, 50L, setOf(key(owner(c)))),
            l.view
        )
    }

    /**
     * L05: a retired scope loses every loss it held - also one with no lifetime - and refuses every later loss, also one
     * that never lost anything before its retirement. Retirements accumulate across purges. Refusals and a purge that only
     * installs a boundary move neither the view nor the revision.
     */
    @Test
    fun L05_retirementRemovesEverything_refusesLaterLosses_andAccumulates() {
        val l = TopicGraphLossLedger()
        l.record(obs(1, owner(a), invalidations = 3L), TopicGraphOffer.FULL, 10L)
        l.record(cont(2, owner(a), null), TopicGraphOffer.CLOSED, 20L)
        l.record(obs(3, owner(b)), TopicGraphOffer.FULL, 30L)
        val held = l.view
        assertFalse("nothing removed", l.purge({ it == c }, retire(c)))
        assertEquals("the boundary alone changes no view", held, l.view)
        assertFalse("its first late loss is refused", l.record(obs(4, owner(c)), TopicGraphOffer.FULL, 40L))
        assertFalse(l.record(cont(5, owner(c), null), TopicGraphOffer.CLOSED, 50L))
        assertEquals(held, l.view)
        assertEquals("no revision for refusals or the boundary", 4L, revisionAfterOneMore(l, 6))

        assertTrue(l.purge({ it == a }, retire(a)))
        assertEquals("a's losses, with and without a lifetime, are gone", setOf(key(owner(b)), key(null)), checkNotNull(l.view).authorities)
        assertEquals(2L, checkNotNull(l.view).count)
        assertFalse(l.record(obs(7, owner(a), invalidations = 99L), TopicGraphOffer.FULL, 70L))
        assertTrue(l.purge({ it == b }, retire(b)))
        assertFalse("c is still retired after later purges", l.record(obs(8, owner(c)), TopicGraphOffer.FULL, 80L))
        assertFalse("so is a", l.record(obs(9, owner(a)), TopicGraphOffer.FULL, 90L))
    }

    /**
     * L06: an ended-use floor removes, in its scope, only the losses of a lifetime below it and those with no lifetime -
     * continuities as well as observations; the floor's own lifetime, later ones and another scope stay. Afterwards a lifetime
     * below the floor or none is refused, whatever the grant, while the floor itself and above pass - also under the same
     * authority key, a fresh lifetime of the same grant. A second purge with nothing below the floor removes nothing.
     */
    @Test
    fun L06_anEndedUseFloorRemovesAndRefusesOnlyWhatIsBelowIt() {
        val l = TopicGraphLossLedger()
        val grant1 = owner(a, grant = 1L)
        l.record(obs(1, grant1, invalidations = 4L, topic = "t4"), TopicGraphOffer.FULL, 10L)
        l.record(obs(2, grant1, invalidations = 5L, topic = "t5"), TopicGraphOffer.CLOSED, 20L)
        l.record(cont(3, grant1, null, topics = setOf("tnull")), TopicGraphOffer.FAILED, 30L)
        l.record(obs(4, grant1, invalidations = 6L, topic = "t6", path = TopicGraphPath.REST_BOOTSTRAP), TopicGraphOffer.FULL, 40L)
        l.record(obs(5, owner(b), topic = "tb"), TopicGraphOffer.FULL, 50L)
        l.record(cont(6, grant1, 4L, topics = setOf("tc4")), TopicGraphOffer.CLOSED, 60L)
        l.record(cont(7, grant1, 5L, topics = setOf("tc5")), TopicGraphOffer.CLOSED, 70L)

        assertTrue(l.purge({ it == a }, floors(a to 5L)))
        assertEquals(
            "the floor's lifetime, a later one and the other scope remain",
            TopicGraphLoss(
                8, 4, 2, 7, setOf(TopicGraphOffer.CLOSED, TopicGraphOffer.FULL), setOf("t5", "t6", "tb", "tc5"),
                setOf(TopicGraphPath.WS, TopicGraphPath.REST_BOOTSTRAP), 20L, 70L, setOf(key(grant1), key(owner(b)))
            ),
            l.view
        )
        val held = l.view
        assertFalse("nothing left below the floor", l.purge({ it == a }, floors(a to 5L)))
        assertEquals(held, l.view)

        assertFalse("below the floor", l.record(obs(8, grant1, invalidations = 4L), TopicGraphOffer.FULL, 80L))
        assertFalse("below the floor, a new grant", l.record(obs(9, owner(a, grant = 2L), invalidations = 4L, grantEpoch = 2L), TopicGraphOffer.FULL, 90L))
        assertFalse("no lifetime", l.record(cont(10, grant1, null), TopicGraphOffer.FULL, 100L))
        assertFalse("a continuity below the floor", l.record(cont(11, grant1, 4L), TopicGraphOffer.FULL, 110L))
        assertTrue("the floor itself passes, under the same key", l.record(obs(12, grant1, invalidations = 5L), TopicGraphOffer.FULL, 120L))
        assertTrue("above the floor", l.record(obs(13, grant1, invalidations = 6L), TopicGraphOffer.FULL, 130L))
        assertTrue("a continuity at the floor", l.record(cont(14, grant1, 5L), TopicGraphOffer.FULL, 140L))
        assertTrue("a new grant in the same epoch", l.record(obs(15, owner(a, grant = 2L), invalidations = 5L, grantEpoch = 2L), TopicGraphOffer.FULL, 150L))
        assertTrue("another scope is untouched", l.record(obs(16, owner(b), invalidations = 0L), TopicGraphOffer.FULL, 160L))
        assertEquals("the second purge counted nothing", 13L, checkNotNull(l.view).revision)
    }

    /**
     * L07: floors only rise - a later lower floor never lowers one, a later higher one raises it - and accumulate across
     * scopes. A scope both retired and floored is retired: it loses even what is at or above the floor, and a later floor
     * never lifts it. Floor 0 still refuses a loss with no lifetime.
     */
    @Test
    fun L07_floorsOnlyRiseAndAccumulate_andRetirementWins() {
        val l = TopicGraphLossLedger()
        l.purge({ it == a }, floors(a to 6L))
        l.purge({ it == a }, floors(a to 5L))
        assertFalse("still 6", l.record(obs(1, owner(a), invalidations = 5L), TopicGraphOffer.FULL, 10L))
        assertTrue(l.record(obs(2, owner(a), invalidations = 6L), TopicGraphOffer.FULL, 20L))
        assertTrue(l.purge({ it == a }, floors(a to 7L)))
        assertFalse("raised to 7", l.record(obs(3, owner(a), invalidations = 6L), TopicGraphOffer.FULL, 30L))
        l.purge({ it == c }, floors(c to 5L))
        assertFalse("a keeps its floor after c's", l.record(obs(4, owner(a), invalidations = 6L), TopicGraphOffer.FULL, 40L))

        l.record(obs(5, owner(b), invalidations = 9L), TopicGraphOffer.FULL, 50L)
        l.record(cont(6, owner(b), null), TopicGraphOffer.FULL, 60L)
        assertTrue(l.purge({ it == b }, GraphLossRetirement(retiredScopes = setOf(b), endedUseFloors = mapOf(b to 1L))))
        assertNull("retired: even what is at or above the floor is gone", l.view)
        assertFalse("retired, whatever the floor", l.record(obs(7, owner(b), invalidations = 9L), TopicGraphOffer.FULL, 70L))
        l.purge({ it == b }, floors(b to 1L))
        assertFalse("a later floor does not lift the retirement", l.record(obs(8, owner(b), invalidations = 9L), TopicGraphOffer.FULL, 80L))

        l.purge({ it == d }, floors(d to 0L))
        assertFalse("floor 0 refuses no lifetime", l.record(cont(9, owner(d), null), TopicGraphOffer.FULL, 90L))
        assertTrue("and passes lifetime 0", l.record(obs(10, owner(d), invalidations = 0L), TopicGraphOffer.FULL, 100L))
    }

    /**
     * L08: a retirement naming an unselected scope (retired or floored), a selected scope that holds losses but has no
     * boundary - also with an empty retirement - and a selector that throws, on its first or a later call, each fail before
     * any change: the losses, the revision and the boundaries are as they were.
     */
    @Test
    fun L08_anInconsistentOrFailingPurgeChangesNothing() {
        val l = TopicGraphLossLedger()
        l.record(obs(1, owner(a)), TopicGraphOffer.FULL, 10L)
        l.record(obs(2, owner(b)), TopicGraphOffer.FULL, 20L)
        val held = checkNotNull(l.view)

        assertThrows(IllegalArgumentException::class.java) { l.purge({ false }, retire(a)) }
        assertThrows(IllegalArgumentException::class.java) { l.purge({ false }, floors(a to 1L)) }
        assertThrows(IllegalArgumentException::class.java) { l.purge({ true }, retire(a)) }
        assertThrows(IllegalArgumentException::class.java) { l.purge({ true }, GraphLossRetirement()) }
        val boom = IllegalStateException("selector")
        assertEquals(boom, assertThrows(IllegalStateException::class.java) { l.purge({ throw boom }, retire(a)) })
        var calls = 0
        assertEquals(
            boom,
            assertThrows(IllegalStateException::class.java) {
                l.purge({ if (++calls == 3) throw boom else true }, GraphLossRetirement(retiredScopes = setOf(a, b, c)))
            }
        )
        assertEquals("the third call threw", 3, calls)
        assertEquals("nothing removed", held, l.view)

        assertTrue("no boundary was installed for a", l.record(obs(3, owner(a)), TopicGraphOffer.FULL, 30L))
        assertTrue("nor for b", l.record(obs(4, owner(b)), TopicGraphOffer.FULL, 40L))
        assertTrue("nor for c", l.record(obs(5, owner(c)), TopicGraphOffer.FULL, 50L))
        assertEquals(
            "every loss is still held, and only the accepted ones counted",
            held.copy(revision = 5, count = 5, lastSequence = 5, lastOccurredAtEpochMillis = 50L, authorities = held.authorities + key(owner(c))),
            l.view
        )
    }

    /**
     * L09: the selector runs exactly once for each distinct scope that is held, under however many lifetimes, or named in
     * the retirement - and for no other, not even a scope a boundary already covers.
     */
    @Test
    fun L09_theSelectorRunsOncePerDistinctScope() {
        val l = TopicGraphLossLedger()
        l.purge({ it == d || it == e }, GraphLossRetirement(retiredScopes = setOf(d), endedUseFloors = mapOf(e to 3L)))
        l.record(obs(1, owner(a), invalidations = 4L), TopicGraphOffer.FULL, 10L)
        l.record(obs(2, owner(a), invalidations = 5L), TopicGraphOffer.FULL, 20L)
        l.record(cont(3, owner(a), null), TopicGraphOffer.FULL, 30L)
        l.record(obs(4, owner(b)), TopicGraphOffer.FULL, 40L)
        l.record(cont(5, null, null), TopicGraphOffer.FULL, 50L)
        val calls = mutableMapOf<GraphDataScope, Int>()
        assertTrue(
            l.purge(
                { scope -> calls[scope] = (calls[scope] ?: 0) + 1; scope != b },
                GraphLossRetirement(retiredScopes = setOf(c), endedUseFloors = mapOf(a to 5L))
            )
        )
        assertEquals(mapOf(a to 1, b to 1, c to 1), calls)
    }

    /**
     * L10: the retirement's collections are read once, at the start: changing them during the selector or after the purge
     * installs nothing more. Input sets are kept as copies: a later rebuild of the view does not see a caller's change.
     */
    @Test
    fun L10_retirementAndInputCollectionsAreCopied() {
        val l = TopicGraphLossLedger()
        val retired = mutableSetOf(a)
        val ended = mutableMapOf(c to 5L)
        l.purge({ scope -> retired += b; ended[c] = 9L; scope == a || scope == c }, GraphLossRetirement(retired, ended))
        retired += c
        assertTrue("b was never retired", l.record(obs(1, owner(b)), TopicGraphOffer.FULL, 10L))
        assertTrue("c's floor stayed 5", l.record(obs(2, owner(c), invalidations = 5L), TopicGraphOffer.FULL, 20L))

        val topics = mutableSetOf("usdt:krw")
        val paths = mutableSetOf(TopicGraphPath.WS)
        l.record(cont(3, null, null, topics, paths), TopicGraphOffer.FULL, 30L)
        topics += "dxy:spot"
        paths += TopicGraphPath.REST_BOOTSTRAP
        assertTrue("a later loss rebuilds the view", l.record(cont(4, null, null, setOf("usdt:krw")), TopicGraphOffer.FULL, 40L))
        assertEquals(setOf("fx:usd-krw", "usdt:krw"), checkNotNull(l.view).topics)
        assertEquals(setOf(TopicGraphPath.WS), checkNotNull(l.view).paths)
    }
}
