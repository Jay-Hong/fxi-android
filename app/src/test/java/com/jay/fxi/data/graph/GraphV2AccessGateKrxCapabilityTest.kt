package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.AccessFence
import com.jay.fxi.data.entitlements.KrxHandSnapshots.FENCE7
import com.jay.fxi.data.entitlements.KrxHandSnapshots.IDENTITY
import com.jay.fxi.data.entitlements.KrxHandSnapshots.LIFETIME7
import com.jay.fxi.data.entitlements.KrxHandSnapshots.RECORD_K1
import com.jay.fxi.data.entitlements.KrxHandSnapshots.RECORD_K2
import com.jay.fxi.data.entitlements.KrxHandSnapshots.USER_EPOCH
import com.jay.fxi.data.entitlements.KrxHandSnapshots.record
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.a1Snap
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphV2Tab
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6-2b-1b-α contract tests r1 — TA-02, the graph gate's C-1 (design r5 §13 T.3; §2 ③(b1), §3 graph gate seam D-6·D-27, §4 gate
 * row, T.0 common rules). Rows 02-1 … 02-8 and 02-O1.
 *
 * Contract (§4): ON (`krxPermitSeq > 0`), `admitsKrx` = HEAD (`captured.krxCapabilityEpoch != null ∧ capabilityAllowed`) ∧ capture epoch
 * = current `recordFence.krxCapabilityEpoch` ∧ capture capInv = current `capabilityInvalidations`. OFF (`krxPermitSeq = 0`) it is HEAD.
 * `bind` captures the snapshot's capInv whatever the guard (D-27), and `joinForExposure(captured, components, krxCapabilityInvalidations)`
 * compares the passed original capInv, by default the capture's own (D-6).
 *
 * Inputs are S-1 `a1Snap` (KrxHandSnapshots.kt): `projectKrx = true` is the A1 form — standing is computed from the record fence without
 * its KRX epoch, so a record that moved only its KRX epoch keeps token 7 standing. HEAD's own gate fixtures (GraphV2AccessGateTest :63-94)
 * are the `projectKrx = false` form, used only for the HEAD-form half of 02-O1. `krxPermitSeq` defaults to 1 there; an ON row states it as
 * `premise(ON):`, an OFF row passes 0 and states `premise(OFF):` at every snapshot. The capture's capInv at s0 is c0 = 0, the issuer's value
 * before the first allowed-to-blocked transition; c0+1 = 1 after one hold.
 *
 * World is a copy of GraphV2AccessGateTest's private `World` (:100-119): assigning a snapshot publishes it with the next revision, [World.reads]
 * counts snapshot reads, and 02-7 replaces the supplier with one that hands out a different object per read.
 *
 * Message labels: `premise:` holds at HEAD (and only such facts carry it), `premise(ON):` is `krxPermitSeq ≥ 1` or `capabilityAllowed` at
 * the named snapshot, `premise(OFF):` is `krxPermitSeq = 0`. A precondition that relies on α behaviour is labelled
 * `contract(<row or decision>):` and is placed before the row contract it guards (register E-18): here `contract(D-27):` — a bind captures
 * the snapshot's current capInv — which does not hold at HEAD and sits before the contract on the new bind, where M7-NOW would otherwise
 * be undetectable.
 *
 * The frozen HEAD gate ([HeadGate]) is the de50885 expression copied verbatim (GraphV2AccessGate.kt:33-41, :43-49, :55-68, :71-77, :79-80
 * and SnapshotTopicUseAuthority.kt:21-22), the OFF contrast form (가) of T.0.
 */
internal class GraphV2AccessGateKrxCapabilityTest {

    private companion object {
        val T0: Instant = Instant.parse("2026-10-05T03:00:00Z")
        const val C0 = 0L
        const val C1 = 1L
        val GENERAL_ONLY = listOf("hana.usd-krw", "kb.usd-krw")
        val JOINED = listOf("hana.usd-krw", "krx.usd-krw-futures", "kb.usd-krw")
        val FENCE8 = TopicSessionFence(IDENTITY, USER_EPOCH, TopicGrantToken(8L))
        val LIFETIME8 = TopicUseLifetime(TopicGrantToken(8L), 4L)
    }

    /** GraphV2AccessGateTest `World` (:100-119), with a replaceable snapshot supplier for 02-7. */
    private class World(initial: TopicAccessSnapshot) {
        var identity: AuthIdentityFence? = IDENTITY
        var fence: TopicSessionFence? = FENCE7
        var protectedOpen = true
        private var revision = initial.revision
        var snapshot: TopicAccessSnapshot = initial
            set(value) {
                revision += 1
                field = value.copy(revision = revision)
            }
        var reads = 0
        /** When set, each snapshot read takes the next value from it instead of [snapshot]. */
        var supplier: (() -> TopicAccessSnapshot)? = null
        val gate = GraphV2AccessGate(
            currentIdentity = { identity },
            currentAccessFence = { fence },
            snapshot = {
                reads++
                supplier?.invoke() ?: snapshot
            },
            protectedAdmission = { protectedOpen }
        )
    }

    /**
     * Frozen HEAD (de50885) gate, verbatim. admitsUse: SnapshotTopicUseAuthority.kt:21-22. admittedSnapshot: GraphV2AccessGate.kt:71-77
     * (reads the world's current snapshot without counting). bindWithConfiguration: :33-41. admits: :43-49. joinForExposure: :55-68.
     * admitsKrx: :79-80. The world's live inputs stand in for the gate's constructor suppliers.
     */
    private object HeadGate {
        fun TopicAccessSnapshot.admitsUse(lifetime: TopicUseLifetime): Boolean =
            facts.token == lifetime.grant && facts.tokenStanding && facts.userAllowed && userInvalidations == lifetime.invalidations

        fun admittedSnapshot(w: World, fence: TopicSessionFence, lifetime: TopicUseLifetime): TopicAccessSnapshot? {
            if (!w.protectedOpen || w.identity != fence.identity || w.fence != fence ||
                fence.userAccessEpoch == null
            ) return null
            val access = w.snapshot
            return access.takeIf { it.admitsUse(lifetime) }
        }

        fun admits(w: World, captured: GraphV2AccessCapture, component: GraphV2DiskComponent): Boolean {
            val access = admittedSnapshot(w, captured.fence, captured.lifetime) ?: return false
            return when (component) {
                GraphV2DiskComponent.GENERAL -> true
                GraphV2DiskComponent.KRX -> admitsKrx(captured, access)
            }
        }

        fun joinForExposure(w: World, captured: GraphV2AccessCapture, components: GraphV2DiskComponents): GraphV2ComponentJoin? {
            val access = admittedSnapshot(w, captured.fence, captured.lifetime) ?: return null
            val general = components.general
            if (general.key.uid != captured.fence.identity.uid ||
                general.key.userAccessEpoch != captured.fence.userAccessEpoch
            ) return null
            val krx = components.krx?.takeIf {
                admitsKrx(captured, access) && it.key.krxCapabilityEpoch == captured.krxCapabilityEpoch
            }
            return joinGraphV2Components(general, krx)
        }

        /** GraphV2AccessGate.kt:79-80. */
        fun admitsKrx(captured: GraphV2AccessCapture, access: TopicAccessSnapshot): Boolean =
            captured.krxCapabilityEpoch != null && access.facts.capabilityAllowed

        /** GraphV2AccessGate.kt:33-41 (the capture's capInv is left at its default; HEAD has none). */
        fun bindWithConfiguration(w: World, fence: TopicSessionFence, lifetime: TopicUseLifetime): GraphV2AccessBinding? {
            if (lifetime.grant != fence.grant) return null
            val access = admittedSnapshot(w, fence, lifetime) ?: return null
            val configuration = GraphV2CapabilityConfiguration(
                access.facts.recordFence?.krxCapabilityEpoch, access.facts.capabilityAllowed
            )
            val epoch = configuration.recordEpoch.takeIf { configuration.allowed }
            return GraphV2AccessBinding(GraphV2AccessCapture(fence, lifetime, epoch), configuration)
        }
    }

    private fun point(rate: Double) = FreeGraphPoint(T0 - 1.days, rate, rate + 1, rate - 1, "hana")

    /** GraphV2AccessGateTest `components` (:129-146) for the S-1 session (uid u1, user epoch U1). */
    private fun components(krx: String? = "K1"): GraphV2DiskComponents {
        val series = listOf(
            FreeGraphSeries("hana.usd-krw", listOf(point(1390.0)), "하나", "krw", "KRW", 2),
            FreeGraphSeries("krx.usd-krw-futures", listOf(point(1391.0)), "KRX", "krw", "KRW", 1),
            FreeGraphSeries("kb.usd-krw", listOf(point(1392.0)), "국민", "krw", "KRW", 2)
        )
        val tab = GraphV2Tab("usd", GraphPeriod.THREE_MONTHS, "1d", T0,
            FreeGraph("1d", series, "2026-07-05", "2026-10-05", T0 - 90.days, T0, "fixed_start"), emptyMap())
        val split = splitGraphV2ServerTab(tab, GraphV2GeneralKey("u1", USER_EPOCH, "usd", GraphPeriod.THREE_MONTHS.code), krx, "r1", null)
        return (split as GraphV2Validation.Valid).value
    }

    private fun GraphV2ComponentJoin?.ids(row: String) = checkNotNull(this) { "$row: join was refused" }.tab.graph.series.map { it.seriesId }

    private fun World.both(c: GraphV2AccessCapture) =
        gate.admits(c, GraphV2DiskComponent.GENERAL) to gate.admits(c, GraphV2DiskComponent.KRX)

    private fun premiseOn(row: String, label: String, s: TopicAccessSnapshot) =
        assertTrue("$row premise(ON): $label krxPermitSeq ≥ 1 (${s.krxPermitSeq})", s.krxPermitSeq >= 1)

    private fun premiseAllowed(row: String, label: String, s: TopicAccessSnapshot) =
        assertTrue("$row premise(ON): $label capabilityAllowed", s.facts.capabilityAllowed)

    /** s0: K1 allowed, c0, p = 1, and the capture c taken there (premise(ON) + c's epoch, capInv and its open KRX — the window). */
    private fun World.captureAtS0(row: String): GraphV2AccessCapture {
        premiseOn(row, "s0", snapshot)
        premiseAllowed(row, "s0", snapshot)
        val c = checkNotNull(gate.bind(FENCE7, LIFETIME7)) { "$row premise: s0 bind" }
        assertEquals("$row premise: c captured K1", "K1", c.krxCapabilityEpoch)
        assertEquals("$row premise: c captured capInv c0 (= constructor default; carries no D-27 evidence)", C0, c.capabilityInvalidations)
        assertEquals("$row premise: c is open on both axes at s0 (inside the window)", true to true, both(c))
        return c
    }

    /** A step under a capability block that tests KRX refusal (T.0): capabilityAllowed false and the general use allowed, separately. */
    private fun World.premiseHeld(row: String, label: String, c: GraphV2AccessCapture) {
        assertFalse("$row premise: $label capabilityAllowed = false", snapshot.facts.capabilityAllowed)
        assertTrue("$row premise: $label general use allowed", gate.admits(c, GraphV2DiskComponent.GENERAL))
    }

    /** Before a KRX-false assertion on [c]: the judgement happens inside an admitted snapshot (GENERAL true). */
    private fun World.premiseGeneralOpen(row: String, label: String, c: GraphV2AccessCapture) =
        assertTrue("$row premise: $label c's GENERAL is still admitted (the KRX judgement is inside the window)",
            gate.admits(c, GraphV2DiskComponent.GENERAL))

    // --- 02-1 ----------------------------------------------------------------------------------------------------

    /**
     * 02-1 (#4 G11 pair · #28 core, the K1 capture pair row): under the same token, after K2 is allowed the K1 capture's KRX stays closed
     * (선행 재합의 r2:40); a new bind takes K2 and opens both; the K1 capture joins GENERAL only, the K2 capture joins. Mutation: E-X ∧ C-X.
     */
    @Test fun r02_1_aK1CaptureStaysKrxClosedAfterK2IsAllowedUnderTheSameToken() {
        val row = "02-1"
        val w = World(a1Snap())
        val c = w.captureAtS0(row)

        w.snapshot = a1Snap(capabilityBlocks = setOf(TopicAccessBlock.CONTEXT_UNCERTAIN), capabilityInvalidations = C1)
        premiseOn(row, "s1", w.snapshot)
        w.premiseHeld(row, "s1", c)
        assertEquals("$row s1 {CONTEXT_UNCERTAIN}: (GENERAL, KRX)", true to false, w.both(c))

        w.snapshot = a1Snap(record = RECORD_K2, issuedRecord = RECORD_K1, capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED),
            capabilityInvalidations = C1)
        assertTrue("$row premise: s2 token 7 still stands (A1 form; HEAD G11 :341 loses it)", w.snapshot.facts.tokenStanding)
        premiseOn(row, "s2", w.snapshot)
        w.premiseHeld(row, "s2", c)
        assertEquals("$row s2 K2 + {NOT_GRANTED}: (GENERAL, KRX) (HEAD G11 :341 was (false, false))", true to false, w.both(c))

        w.snapshot = a1Snap(record = RECORD_K2, issuedRecord = RECORD_K1, krxPermitSeq = 2L, capabilityInvalidations = C1)
        assertTrue("$row premise: s3 token 7 still stands (A1 form)", w.snapshot.facts.tokenStanding)
        premiseOn(row, "s3", w.snapshot)
        premiseAllowed(row, "s3", w.snapshot)
        w.premiseGeneralOpen(row, "s3", c)
        assertEquals("$row s3 K2 allowed: the K1 capture stays KRX-closed", true to false, w.both(c))

        val n = checkNotNull(w.gate.bind(FENCE7, LIFETIME7)) { "$row premise: s3 bind" }
        assertEquals("$row s3: the new bind with token 7 captures K2", "K2", n.krxCapabilityEpoch)
        assertEquals("$row contract(D-27): the new bind captures the current capInv", C1, n.capabilityInvalidations)
        assertEquals("$row s3: the K2 capture is open on both axes", true to true, w.both(n))

        val oldJoin = w.gate.joinForExposure(c, components(krx = "K1"))
        assertNotNull("$row premise: joinForExposure(c, K1 components) is admitted on GENERAL", oldJoin)
        assertFalse("$row s3: joinForExposure(c, K1 components) joins no KRX",
            checkNotNull(oldJoin) { "$row s3: joinForExposure(c, K1 components) refused" }.krxJoined)
        assertEquals("$row s3: joinForExposure(c, K1 components) is GENERAL only", GENERAL_ONLY, oldJoin.ids(row))
        val newJoin = w.gate.joinForExposure(n, components(krx = "K2"))
        assertTrue("$row s3: joinForExposure(K2 capture, K2 components) joins KRX",
            checkNotNull(newJoin) { "$row s3: K2 join refused" }.krxJoined)
        assertEquals("$row s3: joinForExposure(K2 capture, K2 components)", JOINED, newJoin.ids(row))
    }

    // --- 02-2 ----------------------------------------------------------------------------------------------------

    /** 02-2 (conditions apart): synthetic s3e (K2 allowed, capInv still c0) and s3c (K1 allowed, capInv c0+1) each close c's KRX. E-X, C-X. */
    @Test fun r02_2_epochAloneAndCapInvAloneEachCloseKrx() {
        val row = "02-2"
        for ((label, s3) in listOf(
            "s3e (K2 allowed, capInv c0)" to a1Snap(record = RECORD_K2, issuedRecord = RECORD_K1, krxPermitSeq = 2L, capabilityInvalidations = C0),
            "s3c (K1 allowed, capInv c0+1)" to a1Snap(record = RECORD_K1, krxPermitSeq = 2L, capabilityInvalidations = C1)
        )) {
            val w = World(a1Snap())
            val c = w.captureAtS0("$row $label")
            w.snapshot = s3
            assertTrue("$row premise: $label token 7 still stands (A1 form)", w.snapshot.facts.tokenStanding)
            premiseOn(row, label, w.snapshot)
            premiseAllowed(row, label, w.snapshot)
            w.premiseGeneralOpen(row, label, c)
            assertFalse("$row $label: c's KRX is closed", w.gate.admits(c, GraphV2DiskComponent.KRX))
        }
    }

    // --- 02-3 ----------------------------------------------------------------------------------------------------

    /**
     * 02-3 (#26 G10 pair, OD-2): a capability-only hold round trip under the same K1, token and user invalidations now raises capInv, so
     * the old capture's KRX stays closed after release (HEAD G10 :321 re-admits); a new bind opens both; the user-axis half of G10
     * (:323-327) stays closed ON too. Mutation: C-X. (Addition: c's exposure at s2 is GENERAL only — the same C-1 through joinForExposure's
     * default original capInv.)
     */
    @Test fun r02_3_aCapabilityHoldRoundTripNoLongerReadmitsTheOldCapture() {
        val row = "02-3"
        for (block in listOf(TopicAccessBlock.LOSS_CANDIDATE, TopicAccessBlock.KRX_CONTESTED)) {
            val w = World(a1Snap())
            val c = w.captureAtS0("$row $block")

            w.snapshot = a1Snap(capabilityBlocks = setOf(block), capabilityInvalidations = C1)
            premiseOn(row, "s1 {$block}", w.snapshot)
            w.premiseHeld(row, "s1 {$block}", c)
            assertEquals("$row s1 {$block}: (GENERAL, KRX)", true to false, w.both(c))

            w.snapshot = a1Snap(krxPermitSeq = 2L, capabilityInvalidations = C1)
            assertEquals("$row premise: s2 record K1", RECORD_K1, w.snapshot.facts.recordFence)
            assertEquals("$row premise: s2 token 7", TopicGrantToken(7L), w.snapshot.facts.token)
            assertTrue("$row premise: s2 token 7 stands", w.snapshot.facts.tokenStanding)
            assertEquals("$row premise: s2 user invalidations 3 (unchanged)", 3L, w.snapshot.userInvalidations)
            premiseOn(row, "s2 after {$block}", w.snapshot)
            premiseAllowed(row, "s2 after {$block}", w.snapshot)
            w.premiseGeneralOpen(row, "s2 after {$block}", c)
            assertEquals("$row s2 after {$block}: the old capture stays KRX-closed (HEAD G10 :321 was (true, true))", true to false, w.both(c))
            val join = w.gate.joinForExposure(c, components())
            assertNotNull("$row premise: s2 joinForExposure(c) is admitted on GENERAL", join)
            assertEquals("$row s2 after {$block}: joinForExposure(c) is GENERAL only", GENERAL_ONLY, join.ids(row))

            val n = checkNotNull(w.gate.bind(FENCE7, LIFETIME7)) { "$row premise: s2 bind" }
            assertEquals("$row contract(D-27): the new bind captures the current capInv", C1, n.capabilityInvalidations)
            assertEquals("$row s2 after {$block}: the new bind is open on both axes", true to true, w.both(n))

            // G10's second half (:323-327): a user-axis hold that comes and goes.
            w.snapshot = a1Snap(capabilityBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE), userBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE),
                userInvalidations = 4L, krxPermitSeq = 2L, capabilityInvalidations = C1 + 1)
            premiseOn(row, "user hold", w.snapshot)
            assertEquals("$row user hold: both held, ON too", false to false, w.both(c))
            w.snapshot = a1Snap(userInvalidations = 4L, krxPermitSeq = 3L, capabilityInvalidations = C1 + 1)
            premiseOn(row, "user released", w.snapshot)
            assertTrue("$row premise: released snapshot stands, user axis and capability allowed (only the invalidation differs)",
                w.snapshot.facts.tokenStanding && w.snapshot.facts.userAllowed && w.snapshot.facts.capabilityAllowed &&
                    w.snapshot.userInvalidations == 4L)
            assertEquals("$row user released: the user axis went away in between, ON too", false to false, w.both(c))
        }
    }

    // --- 02-4 ----------------------------------------------------------------------------------------------------

    /**
     * 02-4 (#16 G08b pair, OD-7): a KRX-only derived seal (record epoch null, token 7 standing by A1, user invalidations unchanged) closes KRX
     * alone and the exposure is GENERAL only (HEAD G08b :291-292 closed both); a new bind captures no epoch. With a USER derived seal as
     * well (user invalidations still 3, the design's base input), standing is lost: both closed, no join. The USER block itself also makes
     * `userAllowed` false (TopicAccessSnapshot.kt:103, SnapshotTopicUseAuthority.kt:22), so standing is not the sole cause of that half.
     * Mutation: a KRX-only seal mixed into the GENERAL judgement.
     */
    @Test fun r02_4_aKrxOnlyDerivedSealClosesKrxAlone() {
        val row = "02-4"
        val w = World(a1Snap())
        val c = w.captureAtS0(row)
        w.snapshot = a1Snap(record = record(null), issuedRecord = RECORD_K1, capabilityBlocks = setOf(TopicAccessBlock.DERIVED_SEAL),
            userInvalidations = 3L, capabilityInvalidations = C1)
        premiseOn(row, "s1 KRX-only seal", w.snapshot)
        assertTrue("$row premise: s1 token 7 still stands (A1 form)", w.snapshot.facts.tokenStanding)
        assertFalse("$row premise: s1 capabilityAllowed = false", w.snapshot.facts.capabilityAllowed)
        assertTrue("$row contract(02-4): s1 general use allowed (HEAD G08b was (false, false) — α keeps GENERAL)",
            w.gate.admits(c, GraphV2DiskComponent.GENERAL))
        assertEquals("$row s1 KRX-only seal: (GENERAL, KRX) (HEAD G08b :291 was (false, false))", true to false, w.both(c))
        val join = w.gate.joinForExposure(c, components())
        assertNotNull("$row s1: joinForExposure(c) is admitted (HEAD G08b :292 was null)", join)
        assertEquals("$row s1: joinForExposure(c) is GENERAL only", GENERAL_ONLY, join.ids(row))
        val fresh = checkNotNull(w.gate.bind(FENCE7, LIFETIME7)) { "$row premise: s1 bind with token 7" }
        assertNull("$row s1: the new bind captures no epoch", fresh.krxCapabilityEpoch)
        assertEquals("$row s1: the new bind", true to false, w.both(fresh))

        val u = World(a1Snap())
        val cu = u.captureAtS0("$row USER")
        u.snapshot = a1Snap(record = AccessFence("u1", null, null), issuedRecord = RECORD_K1,
            userBlocks = setOf(TopicAccessBlock.DERIVED_SEAL), capabilityBlocks = setOf(TopicAccessBlock.DERIVED_SEAL),
            userInvalidations = 3L, capabilityInvalidations = C1)
        premiseOn(row, "s1 USER seal too", u.snapshot)
        assertEquals("$row premise: USER seal too, user invalidations 3 (unchanged)", 3L, u.snapshot.userInvalidations)
        assertFalse("$row premise: with a USER seal, token 7 no longer stands", u.snapshot.facts.tokenStanding)
        assertEquals("$row USER seal too: both closed", false to false, u.both(cu))
        assertNull("$row USER seal too: no join", u.gate.joinForExposure(cu, components()))
    }

    // --- 02-5 ----------------------------------------------------------------------------------------------------

    /**
     * 02-5 (ioAdmission): 02-3 s2's capture through `ioAdmission` (GraphV2AccessGate.kt:52-53) — a port made before the round trip and
     * one made after — gives KRX false, GENERAL true, the same answer as `admits`. Mutation: ioAdmission implemented to bypass C-1.
     */
    @Test fun r02_5_ioAdmissionGivesTheSameC1Answer() {
        val row = "02-5"
        val w = World(a1Snap())
        val c = w.captureAtS0(row)
        val early = w.gate.ioAdmission(c)
        assertTrue("$row premise: the early port admits KRX at s0 (inside the window)", early.admits(GraphV2DiskComponent.KRX))
        w.snapshot = a1Snap(capabilityBlocks = setOf(TopicAccessBlock.KRX_CONTESTED), capabilityInvalidations = C1)
        w.snapshot = a1Snap(krxPermitSeq = 2L, capabilityInvalidations = C1)
        premiseOn(row, "s2", w.snapshot)
        premiseAllowed(row, "s2", w.snapshot)
        val late = w.gate.ioAdmission(c)
        for ((label, port) in listOf("port made at s0" to early, "port made at s2" to late)) {
            assertTrue("$row premise: $label admits GENERAL at s2 (inside the window)", port.admits(GraphV2DiskComponent.GENERAL))
            assertFalse("$row $label: KRX is closed at s2", port.admits(GraphV2DiskComponent.KRX))
        }
        assertEquals("$row: the port answers as admits", w.gate.admits(c, GraphV2DiskComponent.KRX), late.admits(GraphV2DiskComponent.KRX))
    }

    // --- 02-6 ----------------------------------------------------------------------------------------------------

    /**
     * 02-6 (HEAD conjunct kept): a capture bound during NOT_GRANTED (epoch null), a current record without an epoch, capabilityAllowed
     * true (synthetic) and equal capInv: KRX stays closed — null = null is not a match. Mutation: N-X.
     */
    @Test fun r02_6_aNullEpochCaptureNeverMatchesANullRecordEpoch() {
        val row = "02-6"
        val w = World(a1Snap(capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED)))
        premiseOn(row, "s0 (NOT_GRANTED)", w.snapshot)
        val c = checkNotNull(w.gate.bind(FENCE7, LIFETIME7)) { "$row premise: bind during NOT_GRANTED" }
        assertNull("$row premise: the capture has no epoch", c.krxCapabilityEpoch)
        w.snapshot = a1Snap(record = record(null), issuedRecord = RECORD_K1, capabilityInvalidations = C0)
        assertTrue("$row premise: token 7 still stands (A1 form)", w.snapshot.facts.tokenStanding)
        assertNull("$row premise: the current record has no epoch", w.snapshot.facts.recordFence?.krxCapabilityEpoch)
        premiseOn(row, "s1", w.snapshot)
        premiseAllowed(row, "s1 (synthetic)", w.snapshot)
        assertEquals("$row premise: capInv equal (capture : current)", w.snapshot.capabilityInvalidations, c.capabilityInvalidations)
        w.premiseGeneralOpen(row, "s1", c)
        assertFalse("$row s1: KRX is closed (HEAD conjunct `captured epoch != null`)", w.gate.admits(c, GraphV2DiskComponent.KRX))
        assertEquals("$row s1: joinForExposure is GENERAL only", GENERAL_ONLY, w.gate.joinForExposure(c, components()).ids(row))
    }

    // --- 02-7 ----------------------------------------------------------------------------------------------------

    /**
     * 02-7 (one read per judgement, B3): the supplier hands out a different object on each read (permit and epoch differ). admits(c, KRX)
     * and joinForExposure each read once (admittedSnapshot :75), and each result is the one the object read alone gives under the
     * contract. Mutation: G-SN. Expected values are written by hand per object (not computed by a formula).
     */
    @Test fun r02_7_eachJudgementReadsOnceAndIsExplainedByThatRead() {
        val row = "02-7"
        val w = World(a1Snap())
        val c = w.captureAtS0(row)
        // object → (admits KRX, joinForExposure joins KRX) under the contract, for c = (K1, c0).
        val a = a1Snap(capabilityInvalidations = C0, krxPermitSeq = 1L, revision = 201L)                 // ON, K1, c0: open
        val b = a1Snap(record = RECORD_K2, issuedRecord = RECORD_K1, capabilityInvalidations = C0, krxPermitSeq = 1L,
            revision = 202L)                                                                              // ON, K2: closed
        val x = a1Snap(capabilityInvalidations = C1, krxPermitSeq = 0L, revision = 203L)                 // OFF, c0+1: HEAD open
        val y = a1Snap(capabilityInvalidations = C1, krxPermitSeq = 1L, revision = 204L)                 // ON, c0+1: closed
        val expected = mapOf(a to true, b to false, x to true, y to false)
        for (s in expected.keys) {
            w.supplier = { s }
            assertTrue("$row premise: rev ${s.revision} admits c's general use (each object's KRX answer is inside the window)",
                w.gate.admits(c, GraphV2DiskComponent.GENERAL))
            assertTrue("$row premise: rev ${s.revision} capabilityAllowed (only the guard and C-1 can close KRX)", s.facts.capabilityAllowed)
        }
        premiseOn(row, "a", a)
        premiseOn(row, "b", b)
        premiseOn(row, "y", y)
        assertEquals("$row premise: x is the OFF object", 0L, x.krxPermitSeq)
        val order = listOf(a, b, x, y, y, x, b, a)
        var next = 0
        var handed: TopicAccessSnapshot? = null
        w.supplier = { order[next++ % order.size].also { handed = it } }
        for (i in order.indices) {
            w.reads = 0
            val admits = w.gate.admits(c, GraphV2DiskComponent.KRX)
            val read = checkNotNull(handed) { "$row premise: the supplier was read" }
            assertEquals("$row admits #$i (rev ${read.revision}): one snapshot read", 1, w.reads)
            assertEquals("$row admits #$i (rev ${read.revision}, p=${read.krxPermitSeq}, capInv=${read.capabilityInvalidations}, " +
                "record=${read.facts.recordFence?.krxCapabilityEpoch}): explained by that read alone", expected.getValue(read), admits)

            w.reads = 0
            val join = w.gate.joinForExposure(c, components())
            val joinRead = checkNotNull(handed) { "$row premise: join #$i: the supplier was read" }
            assertEquals("$row join #$i (rev ${joinRead.revision}): one snapshot read", 1, w.reads)
            assertNotNull("$row premise: join #$i is admitted on GENERAL", join)
            assertEquals("$row join #$i (rev ${joinRead.revision}): explained by that read alone", expected.getValue(joinRead),
                checkNotNull(join) { "$row join #$i: refused" }.krxJoined)
        }
    }

    // --- 02-8 ----------------------------------------------------------------------------------------------------

    /**
     * 02-8 (D-6, the exposure's original capInv parameter): after 02-3's round trip, a new bind n at s2. (i) joinForExposure(n, KRX
     * components, krxCapabilityInvalidations = c0) is GENERAL only — the original c0 is not the current c0+1. (ii) joinForExposure(n, KRX
     * components) with the default (n's own capInv = c0+1) joins: the non-vacuous contrast. Mutations: M7-NOW (the protectedEntry form is
     * 03-2's), the parameter ignored.
     */
    @Test fun r02_8_theExposureComparesThePassedOriginalCapInv() {
        val row = "02-8"
        val w = World(a1Snap())
        w.captureAtS0(row)
        w.snapshot = a1Snap(capabilityBlocks = setOf(TopicAccessBlock.KRX_CONTESTED), capabilityInvalidations = C1)
        w.snapshot = a1Snap(krxPermitSeq = 2L, capabilityInvalidations = C1)
        premiseOn(row, "s2", w.snapshot)
        premiseAllowed(row, "s2", w.snapshot)
        val n = checkNotNull(w.gate.bind(FENCE7, LIFETIME7)) { "$row premise: s2 bind" }
        assertEquals("$row premise: n captured K1", "K1", n.krxCapabilityEpoch)
        assertEquals("$row contract(D-27): n captured the current capInv c0+1", C1, n.capabilityInvalidations)

        val original = w.gate.joinForExposure(n, components(), krxCapabilityInvalidations = C0)
        assertNotNull("$row premise: (i) is admitted on GENERAL (inside the window)", original)
        assertFalse("$row (i) original c0 ≠ current c0+1: no KRX", checkNotNull(original) { "$row (i) refused" }.krxJoined)
        assertEquals("$row (i) GENERAL only", GENERAL_ONLY, original.ids(row))

        val byDefault = w.gate.joinForExposure(n, components())
        assertTrue("$row (ii) the default is n's own capInv: joined", checkNotNull(byDefault) { "$row (ii) refused" }.krxJoined)
        assertEquals("$row (ii) joined", JOINED, byDefault.ids(row))
    }

    // --- 02-O1 OFF (가) -------------------------------------------------------------------------------------------

    /**
     * One OFF judgement point: premise(OFF) on the current snapshot, then admits (both axes), a port made now, joinForExposure (default,
     * and with [originalCapInv] when given) and every new bind epoch equal the frozen HEAD gate on the same inputs.
     */
    private fun World.offPoint(row: String, label: String, captures: List<GraphV2AccessCapture>, originalCapInv: Long? = null) {
        assertEquals("$row premise(OFF): $label krxPermitSeq = 0", 0L, snapshot.krxPermitSeq)
        for ((i, c) in captures.withIndex()) {
            val comps = components(krx = c.krxCapabilityEpoch ?: "K1")
            val head = HeadGate.admits(this, c, GraphV2DiskComponent.GENERAL) to HeadGate.admits(this, c, GraphV2DiskComponent.KRX)
            assertEquals("$row $label capture#$i: admits = frozen HEAD (GraphV2AccessGate.kt:79-80)", head, both(c))
            val port = gate.ioAdmission(c)
            assertEquals("$row $label capture#$i: ioAdmission = frozen HEAD", head,
                port.admits(GraphV2DiskComponent.GENERAL) to port.admits(GraphV2DiskComponent.KRX))
            val headJoin = HeadGate.joinForExposure(this, c, comps)
            assertEquals("$row $label capture#$i: joinForExposure = frozen HEAD", headJoin, gate.joinForExposure(c, comps))
            if (originalCapInv != null) {
                assertEquals("$row $label capture#$i: joinForExposure(original capInv $originalCapInv) = frozen HEAD", headJoin,
                    gate.joinForExposure(c, comps, krxCapabilityInvalidations = originalCapInv))
            }
        }
        val headBound = HeadGate.bindWithConfiguration(this, FENCE7, LIFETIME7)?.captured
        val bound = gate.bind(FENCE7, LIFETIME7)
        assertEquals("$row $label: a new bind is refused exactly when frozen HEAD refuses (:33-41)", headBound == null, bound == null)
        assertEquals("$row $label: a new bind's epoch = frozen HEAD (:33-41)", headBound?.krxCapabilityEpoch, bound?.krxCapabilityEpoch)
    }

    /**
     * 02-O1 OFF (가): every input of 02-1 … 02-6 and 02-8 with krxPermitSeq = 0 (A1 form, capInv as in the ON row — the issuer keeps it 0
     * off, so the cells with capInv > 0 are synthetic and catch G-CI), and separately the HEAD-form inputs of G08b, G10 and G11
     * (GraphV2AccessGateTest :285-301, :315-328, :334-353 — standing that does not follow A1, capInv 0). Every cell equals the frozen HEAD
     * gate (GraphV2AccessGate.kt:79-80); named cells also state the value (02-1 s3 (true, true), 02-3 s2 (true, true), 02-8 (i) joined) and
     * the HEAD-form cells state G08b/G10/G11's own expected values. Mutations: G-OFF, G-CI, G-GE.
     */
    @Test fun r02_O1_offEveryInputMatchesTheFrozenHeadGate() {
        val row = "02-O1"
        fun off(s: TopicAccessSnapshot) = s.copy(krxPermitSeq = 0L)
        fun World.c0(label: String): GraphV2AccessCapture {
            offPoint(row, "$label s0", emptyList())
            val c = checkNotNull(gate.bind(FENCE7, LIFETIME7)) { "$row premise: $label s0 bind" }
            assertEquals("$row premise: $label c captured K1", "K1", c.krxCapabilityEpoch)
            offPoint(row, "$label s0", listOf(c))
            return c
        }

        // 02-1
        World(off(a1Snap())).apply {
            val c = c0("02-1")
            snapshot = off(a1Snap(capabilityBlocks = setOf(TopicAccessBlock.CONTEXT_UNCERTAIN), capabilityInvalidations = C1))
            offPoint(row, "02-1 s1", listOf(c))
            snapshot = off(a1Snap(record = RECORD_K2, issuedRecord = RECORD_K1, capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED),
                capabilityInvalidations = C1))
            offPoint(row, "02-1 s2", listOf(c))
            snapshot = off(a1Snap(record = RECORD_K2, issuedRecord = RECORD_K1, capabilityInvalidations = C1))
            offPoint(row, "02-1 s3", listOf(c))
            assertEquals("$row 02-1 s3: (true, true) as HEAD", true to true, both(c))
            val n = checkNotNull(gate.bind(FENCE7, LIFETIME7)) { "$row premise: 02-1 s3 OFF bind" }
            offPoint(row, "02-1 s3 new bind", listOf(n))
            assertEquals("$row 02-1 s3: the K1 capture still joins its K1 components", JOINED, gate.joinForExposure(c, components("K1")).ids(row))
        }
        // 02-2
        for ((label, s3) in listOf(
            "02-2 s3e" to a1Snap(record = RECORD_K2, issuedRecord = RECORD_K1, capabilityInvalidations = C0),
            "02-2 s3c" to a1Snap(record = RECORD_K1, capabilityInvalidations = C1)
        )) World(off(a1Snap())).apply {
            val c = c0(label)
            snapshot = off(s3)
            offPoint(row, label, listOf(c))
            assertTrue("$row $label: KRX open as HEAD", gate.admits(c, GraphV2DiskComponent.KRX))
        }
        // 02-3 and 02-5 (the same round trip)
        for (block in listOf(TopicAccessBlock.LOSS_CANDIDATE, TopicAccessBlock.KRX_CONTESTED)) World(off(a1Snap())).apply {
            val c = c0("02-3 {$block}")
            val early = gate.ioAdmission(c)
            snapshot = off(a1Snap(capabilityBlocks = setOf(block), capabilityInvalidations = C1))
            offPoint(row, "02-3 {$block} s1", listOf(c))
            snapshot = off(a1Snap(capabilityInvalidations = C1))
            offPoint(row, "02-3 {$block} s2", listOf(c))
            assertEquals("$row 02-3 {$block} s2: (true, true) as HEAD", true to true, both(c))
            assertTrue("$row 02-5 {$block}: the port made at s0 admits KRX as HEAD", early.admits(GraphV2DiskComponent.KRX))
            val n = checkNotNull(gate.bind(FENCE7, LIFETIME7)) { "$row premise: 02-3 {$block} s2 OFF bind" }
            offPoint(row, "02-3 {$block} s2 new bind", listOf(n), originalCapInv = C0)
            // 02-8 at p = 0
            assertTrue("$row 02-8 (i) {$block}: joinForExposure(n, original c0) joins as HEAD",
                checkNotNull(gate.joinForExposure(n, components(), krxCapabilityInvalidations = C0)) { "$row 02-8 (i) {$block}: refused" }
                    .krxJoined)
            snapshot = off(a1Snap(capabilityBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE), userBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE),
                userInvalidations = 4L, capabilityInvalidations = C1 + 1))
            offPoint(row, "02-3 {$block} user hold", listOf(c, n))
            snapshot = off(a1Snap(userInvalidations = 4L, capabilityInvalidations = C1 + 1))
            offPoint(row, "02-3 {$block} user released", listOf(c, n))
        }
        // 02-4
        World(off(a1Snap())).apply {
            val c = c0("02-4")
            snapshot = off(a1Snap(record = record(null), issuedRecord = RECORD_K1, capabilityBlocks = setOf(TopicAccessBlock.DERIVED_SEAL),
                capabilityInvalidations = C1))
            offPoint(row, "02-4 KRX-only seal", listOf(c))
            snapshot = off(a1Snap(record = AccessFence("u1", null, null), issuedRecord = RECORD_K1,
                userBlocks = setOf(TopicAccessBlock.DERIVED_SEAL), capabilityBlocks = setOf(TopicAccessBlock.DERIVED_SEAL),
                userInvalidations = 3L, capabilityInvalidations = C1))
            offPoint(row, "02-4 USER seal too", listOf(c))
        }
        // 02-6
        World(off(a1Snap(capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED)))).apply {
            offPoint(row, "02-6 s0", emptyList())
            val c = checkNotNull(gate.bind(FENCE7, LIFETIME7)) { "$row premise: 02-6 s0 OFF bind" }
            snapshot = off(a1Snap(record = record(null), issuedRecord = RECORD_K1, capabilityInvalidations = C0))
            offPoint(row, "02-6 s1", listOf(c))
        }

        // HEAD-form inputs (projectKrx = false, capInv 0): G08b, G10, G11 with their own expected values.
        fun head(s: TopicAccessSnapshot) = s.copy(krxPermitSeq = 0L, capabilityInvalidations = 0L)
        World(head(a1Snap(projectKrx = false))).apply {   // G08b :285-301
            val c = c0("G08b")
            snapshot = head(a1Snap(projectKrx = false, capabilityBlocks = setOf(TopicAccessBlock.DERIVED_SEAL), record = record(null),
                issuedRecord = RECORD_K1, userInvalidations = 4L))
            offPoint(row, "G08b sealed", listOf(c))
            assertEquals("$row G08b :291 token 7 no longer stands", false to false, both(c))
            assertNull("$row G08b :292", gate.joinForExposure(c, components()))
            fence = FENCE8
            snapshot = head(a1Snap(token = 8L, projectKrx = false, capabilityBlocks = setOf(TopicAccessBlock.DERIVED_SEAL), record = record(null),
                userInvalidations = 4L))
            assertEquals("$row premise(OFF): G08b token 8", 0L, snapshot.krxPermitSeq)
            val fresh = checkNotNull(gate.bind(FENCE8, LIFETIME8)) { "$row G08b fence 8 bind" }
            assertNull("$row G08b :298", fresh.krxCapabilityEpoch)
            assertEquals("$row G08b :299", true to false, both(fresh))
            assertEquals("$row G08b :300", GENERAL_ONLY, gate.joinForExposure(fresh, components()).ids(row))
        }
        World(head(a1Snap(projectKrx = false))).apply {   // G10 :315-328
            val c = c0("G10")
            snapshot = head(a1Snap(projectKrx = false, capabilityBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE)))
            offPoint(row, "G10 held", listOf(c))
            assertEquals("$row G10 :319", true to false, both(c))
            snapshot = head(a1Snap(projectKrx = false))
            offPoint(row, "G10 released", listOf(c))
            assertEquals("$row G10 :321 same K1, token and invalidations", true to true, both(c))
            snapshot = head(a1Snap(projectKrx = false, capabilityBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE),
                userBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE), userInvalidations = 4L))
            offPoint(row, "G10 both held", listOf(c))
            assertEquals("$row G10 :325 both held", false to false, both(c))
            snapshot = head(a1Snap(projectKrx = false, userInvalidations = 4L))
            offPoint(row, "G10 both released", listOf(c))
            assertEquals("$row G10 :327", false to false, both(c))
        }
        World(head(a1Snap(projectKrx = false))).apply {   // G11 :334-353
            val c = c0("G11")
            snapshot = head(a1Snap(projectKrx = false, capabilityBlocks = setOf(TopicAccessBlock.CONTEXT_UNCERTAIN)))
            offPoint(row, "G11 false edge", listOf(c))
            assertEquals("$row G11 :338 the false edge", true to false, both(c))
            snapshot = head(a1Snap(projectKrx = false, record = RECORD_K2, issuedRecord = RECORD_K1,
                capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED), userInvalidations = 4L))
            offPoint(row, "G11 K2 confirmed", listOf(c))
            assertEquals("$row G11 :341 token 7 no longer stands", false to false, both(c))
            fence = FENCE8
            snapshot = head(a1Snap(token = 8L, projectKrx = false, record = RECORD_K2, generation = 11L,
                capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED), userInvalidations = 4L))
            assertEquals("$row premise(OFF): G11 token 8 hidden", 0L, snapshot.krxPermitSeq)
            assertEquals("$row G11 :348", false to false, both(c))
            assertNull("$row G11 :349", checkNotNull(gate.bind(FENCE8, LIFETIME8)) { "$row G11 :349 fence 8 bind" }.krxCapabilityEpoch)
            snapshot = head(a1Snap(token = 8L, projectKrx = false, record = RECORD_K2, generation = 11L, userInvalidations = 4L))
            assertEquals("$row premise(OFF): G11 token 8 visible", 0L, snapshot.krxPermitSeq)
            assertEquals("$row G11 :351", false to false, both(c))
            assertEquals("$row G11 :352", "K2", checkNotNull(gate.bind(FENCE8, LIFETIME8)) { "$row G11 :352 fence 8 bind" }.krxCapabilityEpoch)
        }
    }
}
