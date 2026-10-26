package com.jay.fxi.data.graph

import com.jay.fxi.data.entitlements.KrxHandSnapshots.FENCE7
import com.jay.fxi.data.entitlements.KrxHandSnapshots.LIFETIME7
import com.jay.fxi.data.entitlements.KrxHandSnapshots.RECORD_K1
import com.jay.fxi.data.entitlements.KrxHandSnapshots.RECORD_K2
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.a1Snap
import com.jay.fxi.data.entitlements.admitsUse
import com.jay.fxi.data.graph.KrxGraphCoordinatorRig.Companion.KEY
import com.jay.fxi.data.graph.KrxGraphCoordinatorRig.Companion.KRX_BUCKET
import com.jay.fxi.data.graph.KrxGraphCoordinatorRig.Companion.KRX_SERIES
import com.jay.fxi.data.graph.KrxGraphCoordinatorRig.Companion.ONLINE
import com.jay.fxi.data.graph.KrxGraphCoordinatorRig.Companion.components
import com.jay.fxi.data.graph.KrxGraphCoordinatorRig.Companion.ok
import com.jay.fxi.data.graph.KrxGraphCoordinatorRig.Companion.onlineKrxDto
import com.jay.fxi.data.graph.KrxGraphCoordinatorRig.Companion.serverTab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * S6-2b-1b-α contract tests r1 — TA-03, the disk seed and the coordinator's memory (OD-B07) (design r5 §13 T.4; §3 graph gate seam
 * D-6·D-27, §4 seed and coordinator rows, §2 ③(b1), T.0 common rules). Rows 03-1, 03-2, 03-3, 03-4, 03-5 (tests r2, register E-05),
 * 03-O1 and 03-O2. 03-R (optional) is not written in r1.
 *
 * Contract: the seed follows the gate (GraphV2Cache.kt:103-104, GraphV2RequestCoordinator.kt:940); memory KRX belongs to the original
 * capInv of the request or seed that obtained it (`Registration.accessCapture`, GV2RC:225; seed capture :876), carried in ProtectedSlot,
 * and the exposure (`protectedEntry`, GV2RC:767-774 — the holder's only path, GraphV2ScreenStateHolder.kt:602) compares that original
 * capInv, not the freshly bound capture's, with the current one (D-6). Completion adoption (GV2RC:1488-1491) is not changed (#28), so no
 * row asserts `state.entries` after a completion (design r4 note on 03-3).
 *
 * Inputs: S-1 `a1Snap` (A1 form, `krxPermitSeq` 1 unless a row says otherwise; the capture's capInv at the start is c0 = 0, c0+1 = 1 after
 * one hold). Rig: S-2 [KrxGraphCoordinatorRig] — assigning a snapshot tells the coordinator nothing, so a hold round trip can sit between
 * two synchronizations, and [KrxGraphCoordinatorRig.controlEvents] shows that none happened. What the capture a coordinator bind takes
 * at some moment holds is stated with [KrxGraphCoordinatorRig.bindNow] (the same gate on the same inputs), through [bindNowCaptures].
 *
 * Message labels: `premise:` holds at HEAD (and only such facts carry it), `premise(ON):` is `krxPermitSeq ≥ 1` or `capabilityAllowed` at
 * the named snapshot, `premise(OFF):` is `krxPermitSeq = 0` at every published snapshot. A precondition that relies on α behaviour is
 * labelled `contract(<row or decision>):` and is placed before the row contract it guards (register E-18): here `contract(D-27):` — a
 * bind captures the snapshot's current capInv (design §3 D-27), which does not hold at HEAD. [bindNowCaptures] asserts it before a row's
 * own contract wherever the row relies on a bind capturing a non-zero capInv, so that M7-NOW (the exposure comparing the fresh capture's
 * capInv) cannot hide behind a bind that never fills capInv.
 *
 * The frozen HEAD expression for the OFF rows is [headAdmitsKrx], de50885 GraphV2AccessGate.kt:79-80 copied verbatim.
 */
internal class GraphV2KrxSeedAndMemoryTest {

    @get:Rule val folder = TemporaryFolder()

    private companion object {
        const val C0 = 0L
        const val C1 = 1L
        const val C2 = 2L

        /** Frozen de50885 GraphV2AccessGate.kt:79-80, verbatim. */
        fun headAdmitsKrx(captured: GraphV2AccessCapture, access: TopicAccessSnapshot): Boolean =
            captured.krxCapabilityEpoch != null && access.facts.capabilityAllowed
    }

    private fun rigTest(body: suspend TestScope.(open: (TopicAccessSnapshot) -> KrxGraphCoordinatorRig) -> Unit): TestResult =
        krxGraphRigTest({ folder.newFolder() }, body)

    private fun premiseOn(row: String, label: String, s: TopicAccessSnapshot) =
        assertTrue("$row premise(ON): $label krxPermitSeq ≥ 1 (${s.krxPermitSeq})", s.krxPermitSeq >= 1)

    private fun premiseAllowed(row: String, label: String, s: TopicAccessSnapshot) =
        assertTrue("$row premise(ON): $label capabilityAllowed", s.facts.capabilityAllowed)

    /** A HOLD step (T.0): capabilityAllowed false and the general use allowed, separately. */
    private fun KrxGraphCoordinatorRig.premiseHeld(row: String, label: String) {
        assertFalse("$row premise: $label capabilityAllowed = false", snapshot.facts.capabilityAllowed)
        assertTrue("$row premise: $label general use allowed", uses.admits(LIFETIME7))
    }

    private fun KrxGraphCoordinatorRig.premiseOff(row: String) {
        for (s in published) assertEquals("$row premise(OFF): rev ${s.revision} krxPermitSeq = 0", 0L, s.krxPermitSeq)
    }

    /**
     * The capture a coordinator bind takes now is (K1, [capInv]). At [capInv] = c0 = 0 this equals the capture's constructor default and
     * carries no D-27 evidence, so it holds at HEAD (labelled `premise:`); at a non-zero [capInv] it is the α precondition
     * `contract(D-27):`, asserted before the row contract so that M7-NOW stays detectable.
     */
    private fun KrxGraphCoordinatorRig.bindNowCaptures(row: String, label: String, capInv: Long): GraphV2AccessCapture {
        val n = checkNotNull(bindNow()) { "$row premise: $label a bind is admitted" }
        assertEquals("$row premise: $label a bind captures K1", "K1", n.krxCapabilityEpoch)
        val message = if (capInv == C0) {
            "$row premise: $label a bind captures capInv $capInv (= constructor default; carries no D-27 evidence)"
        } else {
            "$row contract(D-27): $label a bind captures capInv $capInv"
        }
        assertEquals(message, capInv, n.capabilityInvalidations)
        return n
    }

    private fun GraphEntry?.ids(row: String) = checkNotNull(this) { "$row: nothing exposed" }.tab.graph.series.map { it.seriesId }

    private fun GraphEntry.rate(id: String) = tab.graph.series.single { it.seriesId == id }.points.first().rate

    private fun GraphEntry.generalSeries() = tab.graph.series.filterNot { it.seriesId.startsWith("krx.") }

    /** A same-epoch hold round trip with no coordinator event: s1 [block] (capInv [capInv]) → s2 allowed (same K1, token, user invalidations). */
    private fun TestScope.roundTrip(
        f: KrxGraphCoordinatorRig,
        row: String,
        block: TopicAccessBlock,
        capInv: Long,
        heldPermit: Long,
        releasedPermit: Long
    ) {
        val events = f.controlEvents
        val time = testScheduler.currentTime
        f.snapshot = a1Snap(capabilityBlocks = setOf(block), capabilityInvalidations = capInv, krxPermitSeq = heldPermit)
        f.premiseHeld(row, "s1 {$block}")
        f.snapshot = a1Snap(capabilityInvalidations = capInv, krxPermitSeq = releasedPermit)
        assertEquals("$row premise: s2 is the same K1 record", RECORD_K1, f.snapshot.facts.recordFence)
        assertEquals("$row premise: no coordinator event during the round trip", events, f.controlEvents)
        assertEquals("$row premise: no virtual time passed during the round trip (no timer)", time, testScheduler.currentTime)
    }

    // --- 03-1 ----------------------------------------------------------------------------------------------------

    /**
     * 03-1 (#4 C13 pair · #28 seed; CacheTest :713-728 form): K1 GENERAL+KRX on disk, the seed's GENERAL read held. Meanwhile s1
     * {CONTEXT_UNCERTAIN} (c0+1) → s2 K2 allowed, p = 2, the same token 7 (A1, no rebind), then release. The slot is GENERAL only (no KRX
     * series, no KRX in-progress bucket) and no KRX disk read follows the release (the gate refuses at GraphV2Cache.kt:104). C13's second
     * half (the new context seeds K2) is not asserted. Mutations: E-X ∧ C-X (K1 KRX attached), the seed bypassing the gate (epoch-only).
     *
     * Input extension (same-epoch cell, a second rig): the design's input moves the record epoch K1 → K2, so a seed that skips the gate's
     * C-1 and compares only the epoch (the captured epoch with the record's, or the live configuration's as the SupplementOccupied path
     * does, GV2RC:924) refuses KRX there too and would pass. The same-epoch cell keeps K1 and moves only capInv (s1 {KRX_CONTESTED} c0+1,
     * p = 1 → s2 allowed, c0+1, p = 2, no coordinator event); the slot must still be the GENERAL half alone with no KRX disk read. This
     * catches the epoch-only seed and C-X on the seed. Its OFF twin is in 03-O1.
     */
    @Test fun r03_1_aSeedHeldAcrossAnA1K2ApprovalLandsWithoutKrx() = rigTest { open ->
        val row = "03-1"
        val f = open(a1Snap())
        val c = components(serverTab(inProgress = mapOf(KRX_SERIES to KRX_BUCKET)))
        f.put(c)
        f.store.hold[1] = CompletableDeferred()
        premiseOn(row, "seed start", f.snapshot)
        premiseAllowed(row, "seed start", f.snapshot)
        f.bindNowCaptures(row, "seed start:", C0)
        f.start(); f.activate(); runCurrent()
        assertEquals("$row premise: the seed's GENERAL read is out", 1, f.store.generalReads.size)
        assertTrue("$row premise: it found the stored GENERAL half", f.store.generalReads.single() is GraphV2DiskRead.Found)
        assertEquals("$row premise: no KRX read before the release", 0, f.store.krxReads.size)
        assertNull("$row premise: nothing landed before the release", f.state.entries[KEY])

        f.snapshot = a1Snap(capabilityBlocks = setOf(TopicAccessBlock.CONTEXT_UNCERTAIN), capabilityInvalidations = C1)
        f.premiseHeld(row, "s1")
        f.snapshot = a1Snap(record = RECORD_K2, issuedRecord = RECORD_K1, krxPermitSeq = 2L, capabilityInvalidations = C1)
        assertTrue("$row premise: s2 token 7 still stands (A1 form, no rebind)", f.snapshot.facts.tokenStanding)
        assertEquals("$row premise: the live session is unchanged", FENCE7, f.fence)
        assertEquals("$row premise: only the activation was delivered", 1, f.controlEvents)
        premiseOn(row, "s2", f.snapshot)
        premiseAllowed(row, "s2", f.snapshot)

        f.store.release(1); runCurrent()
        val entry = f.state.entries[KEY]
        assertNotNull("$row premise: the seed landed (inside the window)", entry)
        assertFalse("$row: no KRX series", KRX_SERIES in entry.ids(row))
        assertNull("$row: no KRX in-progress bucket", checkNotNull(entry) { "$row: nothing landed" }.tab.inProgress[KRX_SERIES])
        assertEquals("$row: the slot is the GENERAL half alone", GraphEntry(joinGraphV2Components(c.general, null).tab, null), entry)
        assertEquals("$row: no KRX disk read after the release", 0, f.store.krxReads.size)
        assertEquals("$row: no KRX file read", emptyList<java.io.File>(), f.krxFileReads())
        assertEquals("$row: no event failure", emptyList<Throwable>(), f.failures)
        f.close()

        // Same-epoch cell (input extension): K1 kept, only capInv moves. Catches a seed that checks only the epoch, and C-X on the seed.
        val g = open(a1Snap())
        val gc = components(serverTab(inProgress = mapOf(KRX_SERIES to KRX_BUCKET)))
        g.put(gc)
        g.store.hold[1] = CompletableDeferred()
        premiseOn(row, "same-epoch seed start", g.snapshot)
        premiseAllowed(row, "same-epoch seed start", g.snapshot)
        g.bindNowCaptures(row, "same-epoch seed start:", C0)
        g.start(); g.activate(); runCurrent()
        assertEquals("$row premise: same-epoch seed GENERAL read is out", 1, g.store.generalReads.size)
        assertTrue("$row premise: same-epoch it found the stored GENERAL half", g.store.generalReads.single() is GraphV2DiskRead.Found)
        assertEquals("$row premise: same-epoch no KRX read before the release", 0, g.store.krxReads.size)
        assertNull("$row premise: same-epoch nothing landed before the release", g.state.entries[KEY])
        roundTrip(g, "$row same-epoch", TopicAccessBlock.KRX_CONTESTED, capInv = C1, heldPermit = 1L, releasedPermit = 2L)
        premiseOn(row, "same-epoch s2", g.snapshot)
        premiseAllowed(row, "same-epoch s2", g.snapshot)
        assertEquals("$row premise: same-epoch s2 keeps the K1 record (only capInv moved)", RECORD_K1, g.snapshot.facts.recordFence)
        assertEquals("$row premise: same-epoch only the activation was delivered", 1, g.controlEvents)
        g.store.release(1); runCurrent()
        val ge = g.state.entries[KEY]
        assertNotNull("$row premise: same-epoch seed landed (inside the window)", ge)
        assertEquals("$row same-epoch (K1, c0→c0+1): GENERAL half alone",
            GraphEntry(joinGraphV2Components(gc.general, null).tab, null), ge)
        assertEquals("$row same-epoch: no KRX disk read", 0, g.store.krxReads.size)
        assertEquals("$row same-epoch: no KRX file read", emptyList<java.io.File>(), g.krxFileReads())
        assertEquals("$row same-epoch: no event failure", emptyList<Throwable>(), g.failures)
    }

    // --- 03-2 ----------------------------------------------------------------------------------------------------

    /**
     * 03-2 (OD-B07 ①, adopted KRX's round trip): a K1/c0 request completes online and adopts KRX; with no coordinator event, s1
     * {KRX_CONTESTED} (K1, c0+1, p = 1) → s2 allowed (K1, c0+1, p = 2); `protectedEntry` shows no KRX series, and the GENERAL series and
     * values are those before the round trip. Mutation: M7-NOW (the exposure compares the freshly bound capture's capInv, :773, including
     * joinForExposure called with its default).
     */
    @Test fun r03_2_adoptedKrxIsNotShownAfterASameEpochHoldRoundTrip() = rigTest { open ->
        val row = "03-2"
        val f = open(a1Snap())
        premiseOn(row, "request start", f.snapshot)
        premiseAllowed(row, "request start", f.snapshot)
        f.bindNowCaptures(row, "request start:", C0)
        f.start(); f.activate(); runCurrent()
        assertEquals("$row premise: one tab request out, admitted at send", 1, f.tabs().size)
        premiseOn(row, "completion", f.snapshot)
        premiseAllowed(row, "completion", f.snapshot)
        f.tabs().single().tab.complete(ok(onlineKrxDto(1500.0))); runCurrent()
        val before = f.exposed()
        assertTrue("$row premise: before the round trip the exposure shows KRX", KRX_SERIES in before.ids(row))
        val stateBefore = f.state

        roundTrip(f, row, TopicAccessBlock.KRX_CONTESTED, capInv = C1, heldPermit = 1L, releasedPermit = 2L)
        assertSame("$row premise: the coordinator published nothing during the round trip", stateBefore, f.state)
        premiseOn(row, "s2", f.snapshot)
        premiseAllowed(row, "s2", f.snapshot)
        f.bindNowCaptures(row, "s2 (protectedEntry's bind):", C1)

        val after = f.exposed()
        assertNotNull("$row premise: the exposure is admitted on GENERAL after the round trip (inside the window)", after)
        assertFalse("$row: no KRX series", KRX_SERIES in after.ids(row))
        assertNull("$row: no KRX in-progress bucket", checkNotNull(after) { "$row: nothing exposed after" }.tab.inProgress[KRX_SERIES])
        assertEquals("$row: the GENERAL series and values are those before the round trip",
            checkNotNull(before) { "$row: nothing exposed before" }.generalSeries(), after.generalSeries())
        assertEquals("$row: the stamp is the slot's", before.online200At, after.online200At)
    }

    // --- 03-3 ----------------------------------------------------------------------------------------------------

    /**
     * 03-3 (OD-B07 ②, a request from before the round trip completes after it): a K1/c0 request is out; 03-2's round trip; the answer
     * (with KRX) completes. `protectedEntry` has no KRX and the GENERAL half is adopted. `state.entries` and `online200At` are not asserted
     * (design r4: adoption :1488-1491 and completion confirmation :1379-1381 stay #28's). The optional KRX disk-write assertion is not
     * written: the rig installs no write ports (CacheTest's shape), so there is no observation path. Mutation: M7-NOW (attributed to the
     * completion's capInv).
     */
    @Test fun r03_3_aRequestFromBeforeTheRoundTripShowsNoKrxAfterIt() = rigTest { open ->
        val row = "03-3"
        val f = open(a1Snap())
        premiseOn(row, "request start", f.snapshot)
        premiseAllowed(row, "request start", f.snapshot)
        f.bindNowCaptures(row, "request start:", C0)
        f.start(); f.activate(); runCurrent()
        assertEquals("$row premise: one tab request out, admitted at send", 1, f.tabs().size)

        roundTrip(f, row, TopicAccessBlock.KRX_CONTESTED, capInv = C1, heldPermit = 1L, releasedPermit = 2L)
        assertFalse("$row premise: the request is still waiting for its answer", f.tabs().single().tab.isCompleted)
        premiseOn(row, "s2", f.snapshot)
        premiseAllowed(row, "s2", f.snapshot)
        f.bindNowCaptures(row, "s2 (completion and protectedEntry binds):", C1)

        f.tabs().single().tab.complete(ok(onlineKrxDto(1500.0))); runCurrent()
        val exposed = f.exposed()
        assertNotNull("$row premise: the exposure is admitted on GENERAL (inside the window)", exposed)
        assertTrue("$row: the GENERAL half is adopted", ONLINE in exposed.ids(row))
        assertEquals("$row: the adopted GENERAL value", 1500.0, checkNotNull(exposed) { "$row: nothing exposed" }.rate(ONLINE), 0.0)
        assertFalse("$row: no KRX series", KRX_SERIES in exposed.ids(row))
        assertEquals("$row: no event failure", emptyList<Throwable>(), f.failures)
    }

    // --- 03-4 ----------------------------------------------------------------------------------------------------

    /**
     * 03-4 (OD-B07 ③, after release a new capture is allowed): after 03-3, `onRefreshRequested(force = true)` → a new request captured at
     * (K1, c0+1) → its answer with KRX completes. KRX is adopted and shown (the non-vacuous contrast), GENERAL kept. Mutation: over-refusal
     * (KRX refused for good once a round trip happened, capInv compared with a fixed value). Before the refresh the row also checks 03-3's
     * state (the first request's KRX hidden), so the row shows the hidden-then-shown transition on its own. The row's own contract equals
     * HEAD; on the stub it fails on that `contract(03-3):` state assertion (α behaviour), and the `contract(D-27):` bind check at the second
     * request start is its other α dependency.
     */
    @Test fun r03_4_aNewCaptureAfterTheRoundTripShowsKrx() = rigTest { open ->
        val row = "03-4"
        val f = open(a1Snap())
        premiseOn(row, "first request start", f.snapshot)
        premiseAllowed(row, "first request start", f.snapshot)
        f.start(); f.activate(); runCurrent()
        assertEquals("$row premise: the first request is out", 1, f.tabs().size)
        roundTrip(f, row, TopicAccessBlock.KRX_CONTESTED, capInv = C1, heldPermit = 1L, releasedPermit = 2L)
        f.tabs().single().tab.complete(ok(onlineKrxDto(1500.0))); runCurrent()
        assertTrue("$row premise: 03-3's answer landed (GENERAL shown)", ONLINE in f.exposed().ids(row))
        assertFalse("$row contract(03-3): the round trip hid the first request's KRX", KRX_SERIES in f.exposed().ids(row))

        f.refresh(force = true); runCurrent()
        assertEquals("$row premise: the forced refresh sent a second admitted request", 2, f.tabs().size)
        premiseOn(row, "second request start", f.snapshot)
        premiseAllowed(row, "second request start", f.snapshot)
        f.bindNowCaptures(row, "second request start:", C1)
        f.tabs().last().tab.complete(ok(onlineKrxDto(1600.0))); runCurrent()
        val exposed = f.exposed()
        assertTrue("$row: KRX adopted and shown", KRX_SERIES in exposed.ids(row))
        assertTrue("$row: GENERAL kept", ONLINE in exposed.ids(row))
        assertEquals("$row: the new GENERAL value", 1600.0, checkNotNull(exposed) { "$row: nothing exposed" }.rate(ONLINE), 0.0)
        assertEquals("$row: no event failure", emptyList<Throwable>(), f.failures)
    }

    // --- 03-5 ----------------------------------------------------------------------------------------------------

    /**
     * 03-5 (D-6 seed carry; tests r2, register E-05 — Codex AGREE: every r5 seed starts at c0 = 0, where a slot that carries the default 0
     * cannot be told apart from one that carries the seed capture's own capInv). The seed capture's original capInv (the capture taken at
     * seed start, GV2RC:876, handed on in DiskSeedFinished :888) is carried into the ProtectedSlot the seed builds (:954), and
     * `protectedEntry` (:767-774) compares that value, not the freshly bound capture's, with the current one. Disk: K1 GENERAL+KRX (with a
     * KRX in-progress bucket). The seed starts at a non-zero capInv, s0 = (K1, c0+1, p = 2: one earlier hold already released), and lands
     * with its KRX half.
     *
     * (a) Before any round trip the seeded KRX is exposed under its original capInv c0+1 (a slot carrying the default 0 would hide it).
     * (b) With no coordinator event, s1 {KRX_CONTESTED} (K1, c0+2, p = 2) → s2 allowed (K1, c0+2, p = 3): the seeded KRX is not exposed (no
     * KRX series, no KRX in-progress bucket) and the GENERAL series are those before the round trip.
     * (c) A fresh capture after the release is exposed: a second rig on a new root holding the same disk content (`g.put(c)`), opened at the
     * released snapshot s2's values, seeds at (K1, c0+2) and shows KRX (the non-vacuous contrast to (b)). A second rig is used because the
     * first rig's activation request is still out, so a forced refresh there would join it (GV2RC:1093-1095, :1118-1124) instead of taking a
     * fresh capture.
     *
     * Every non-zero capture capInv the row relies on is asserted as `contract(D-27):` before the contract it guards (seed start c0+1,
     * protectedEntry's bind at s2 c0+2, the second rig's seed start c0+2). On the stub the row fails on the first of these (the stub's
     * bind leaves capInv 0). The g2-fix probe (`tests_r1/g2-fix/probe/G2FixProbeTest.kt`, `probe_03_5_seedCarryProposal`, on the same stub
     * r2) ran (a) and (b) without the D-27 check: (a) passed and (b) failed on 'hidden after round trip'.
     *
     * Mutations: the seed's slot carrying the default 0 instead of the seed capture's capInv ((a), and (c) at c0+2); M7-NOW on a seeded slot
     * — the exposure comparing the fresh capture's capInv or calling joinForExposure with its default ((b)); over-refusal of seeded KRX —
     * refused once capInv is non-zero or compared with a fixed value ((a) at c0+1, (c) at c0+2).
     */
    @Test fun r03_5_aSeededKrxBelongsToTheSeedCapturesOriginalCapInv() = rigTest { open ->
        val row = "03-5"
        val f = open(a1Snap(capabilityInvalidations = C1, krxPermitSeq = 2L))
        val c = components(serverTab(inProgress = mapOf(KRX_SERIES to KRX_BUCKET)))
        f.put(c)
        premiseOn(row, "seed start", f.snapshot)
        premiseAllowed(row, "seed start", f.snapshot)
        f.bindNowCaptures(row, "seed start:", C1)
        f.start(); f.activate(); runCurrent()
        assertEquals("$row premise: the seed's GENERAL read is out", 1, f.store.generalReads.size)
        assertTrue("$row premise: it found the stored GENERAL half", f.store.generalReads.single() is GraphV2DiskRead.Found)
        assertEquals("$row premise: the seed read the KRX half once", 1, f.store.krxReads.size)
        assertEquals("$row premise: the seed landed with its KRX half (the seed join admitted it)",
            GraphEntry(joinGraphV2Components(c.general, c.krx).tab, null), f.state.entries[KEY])
        assertEquals("$row premise: the activation's tab request is out", 1, f.tabs().size)
        assertFalse("$row premise: it is still waiting (the slot stays the seed's)", f.tabs().single().tab.isCompleted)

        val before = f.exposed()
        assertNotNull("$row premise: (a) the exposure is admitted (inside the window)", before)
        assertTrue("$row (a): the seeded KRX is exposed under its original capInv c0+1", KRX_SERIES in before.ids(row))
        assertEquals("$row (a): the seeded KRX in-progress bucket is exposed", KRX_BUCKET,
            checkNotNull(before) { "$row (a): nothing exposed" }.tab.inProgress[KRX_SERIES])
        val stateBefore = f.state

        roundTrip(f, row, TopicAccessBlock.KRX_CONTESTED, capInv = C2, heldPermit = 2L, releasedPermit = 3L)
        assertSame("$row premise: the coordinator published nothing during the round trip", stateBefore, f.state)
        assertFalse("$row premise: the activation's tab request is still waiting", f.tabs().single().tab.isCompleted)
        premiseOn(row, "s2", f.snapshot)
        premiseAllowed(row, "s2", f.snapshot)
        f.bindNowCaptures(row, "s2 (protectedEntry's bind):", C2)

        val after = f.exposed()
        assertNotNull("$row premise: (b) the exposure is admitted on GENERAL after the round trip (inside the window)", after)
        assertFalse("$row (b): the seeded KRX is not exposed after the round trip", KRX_SERIES in after.ids(row))
        assertNull("$row (b): no KRX in-progress bucket", checkNotNull(after) { "$row (b): nothing exposed" }.tab.inProgress[KRX_SERIES])
        assertEquals("$row (b): the GENERAL series and values are those before the round trip",
            before.generalSeries(), after.generalSeries())
        assertEquals("$row: no event failure", emptyList<Throwable>(), f.failures)
        val released = f.snapshot
        f.close()

        // (c) A fresh capture after the release: a seed taken at the released snapshot's values, on the same disk content.
        val g = open(a1Snap(capabilityInvalidations = C2, krxPermitSeq = 3L))
        assertEquals("$row premise: (c) the second rig starts at s2's values", released, g.snapshot.copy(revision = released.revision))
        g.put(c)
        premiseOn(row, "(c) seed start", g.snapshot)
        premiseAllowed(row, "(c) seed start", g.snapshot)
        g.bindNowCaptures(row, "(c) seed start:", C2)
        g.start(); g.activate(); runCurrent()
        assertEquals("$row premise: (c) the seed's GENERAL read is out", 1, g.store.generalReads.size)
        assertTrue("$row premise: (c) it found the stored GENERAL half", g.store.generalReads.single() is GraphV2DiskRead.Found)
        assertNotNull("$row premise: (c) the seed landed (inside the window)", g.state.entries[KEY])
        val fresh = g.exposed()
        assertNotNull("$row premise: (c) the exposure is admitted (inside the window)", fresh)
        assertTrue("$row (c): the fresh seed capture's KRX is exposed", KRX_SERIES in fresh.ids(row))
        assertEquals("$row (c): its KRX in-progress bucket is exposed", KRX_BUCKET,
            checkNotNull(fresh) { "$row (c): nothing exposed" }.tab.inProgress[KRX_SERIES])
        assertEquals("$row (c): no event failure", emptyList<Throwable>(), g.failures)
    }

    /**
     * 03-5 (d) (tests r5; mutation battery r1 `seed_count_now` survived 03-5, agreed with Codex) — an input extension of 03-5 (b), same
     * contract: the slot a seed builds carries the **seed capture's** original capInv (`event.captured`, GV2RC:957), not a count read
     * when the completion stores the slot. 03-5 cannot tell those apart, because nothing moves between the seed capture and the
     * completion. A round trip before the completion does not help either (Codex): the completion's own gate (`joinForExposure` on the
     * seed capture) then refuses KRX and both forms keep no KRX in the slot. The window needed is inside the completion: right after the
     * completion gate's read (which admits KRX under s0) and before anything the completion reads after it.
     *
     * Input: disk K1 GENERAL+KRX (with a KRX in-progress bucket); seed start s0 = (K1, allowed, c0+1, p = 2). The rig is armed
     * ([KrxGraphCoordinatorRig.switchAfterSeedCompletionGateRead]) to publish, right after the first gate read inside the seed completion,
     * s1 {KRX_CONTESTED} (K1, c0+2, p = 2) then s2 allowed (K1, c0+2, p = 3) — a same-epoch round trip with no coordinator event. Window:
     * that completion read happened once before the switch and returned s0 (allowed, c0+1), the seed landed with its KRX half, the switch
     * fired before `protectedEntry`, and protectedEntry's bind takes c0+2 (`contract(D-27):`). Contract (as (b)): the exposure is admitted
     * and shows no KRX series and no KRX in-progress bucket; the GENERAL series are those the seed landed.
     *
     * Mutation: the completion storing a fresh bind's capInv (`ports.gate.bind(…)?.capabilityInvalidations`) instead of the seed
     * capture's — that bind reads s2 (c0+2), so the slot's count equals protectedEntry's and KRX is exposed.
     */
    @Test fun r03_5b_aRoundTripInsideTheSeedCompletionKeepsTheSlotOnTheSeedCapturesCapInv() = rigTest { open ->
        val row = "03-5"
        val f = open(a1Snap(capabilityInvalidations = C1, krxPermitSeq = 2L))
        val c = components(serverTab(inProgress = mapOf(KRX_SERIES to KRX_BUCKET)))
        f.put(c)
        val s0 = f.snapshot
        premiseOn(row, "(d) seed start", s0)
        premiseAllowed(row, "(d) seed start", s0)
        f.bindNowCaptures(row, "(d) seed start:", C1)
        val s1 = a1Snap(capabilityBlocks = setOf(TopicAccessBlock.KRX_CONTESTED), capabilityInvalidations = C2, krxPermitSeq = 2L)
        val s2 = a1Snap(capabilityInvalidations = C2, krxPermitSeq = 3L)
        assertFalse("$row premise: (d) s1 holds the capability", s1.facts.capabilityAllowed)
        assertTrue("$row premise: (d) s1 keeps the general use", s1.admitsUse(LIFETIME7))
        assertEquals("$row premise: (d) s1 and s2 keep the K1 record",
            listOf(RECORD_K1, RECORD_K1), listOf(s1, s2).map { it.facts.recordFence })
        f.switchAfterSeedCompletionGateRead(1, s1, s2)

        f.start(); f.activate(); runCurrent()

        assertEquals("$row premise: (d) the seed's GENERAL read is out", 1, f.store.generalReads.size)
        assertTrue("$row premise: (d) it found the stored GENERAL half", f.store.generalReads.single() is GraphV2DiskRead.Found)
        assertEquals("$row premise: (d) the seed read the KRX half once", 1, f.store.krxReads.size)
        val switchedAt = checkNotNull(f.switchedAfterGateRead) {
            "$row premise: (d) the round trip was published inside the seed completion"
        }
        val completionReadsBefore = f.gateReads.take(switchedAt + 1).filter { it.inSeedCompletion }
        assertEquals("$row premise: (d) exactly one completion gate read before the round trip", 1, completionReadsBefore.size)
        val completionRead = completionReadsBefore.single()
        assertSame("$row premise: (d) the completion gate read took the seed start snapshot s0", s0, completionRead.snapshot)
        premiseAllowed(row, "(d) the completion gate read", completionRead.snapshot)
        assertEquals("$row premise: (d) the completion gate read is at capInv c0+1", C1, completionRead.snapshot.capabilityInvalidations)
        assertEquals("$row premise: (d) the round trip s1 → s2 was published right after it (the last two publications)",
            listOf(s1, s2).map { it.copy(revision = 0L) }, f.published.takeLast(2).map { it.copy(revision = 0L) })
        val landed = f.state.entries[KEY]
        assertEquals("$row premise: (d) the seed landed with its KRX half (the completion join admitted it under s0)",
            GraphEntry(joinGraphV2Components(c.general, c.krx).tab, null), landed)
        assertEquals("$row premise: (d) only the activation was delivered (no coordinator event in the round trip)", 1, f.controlEvents)
        assertEquals("$row premise: (d) before protectedEntry the current snapshot is s2", s2, f.snapshot.copy(revision = s2.revision))
        premiseOn(row, "(d) s2", f.snapshot)
        premiseAllowed(row, "(d) s2", f.snapshot)
        f.bindNowCaptures(row, "(d) s2 (protectedEntry's bind):", C2)

        val after = f.exposed()
        assertNotNull("$row premise: (d) the exposure is admitted on GENERAL after the round trip (inside the window)", after)
        assertFalse("$row (d): the seeded KRX is not exposed after a round trip inside its completion", KRX_SERIES in after.ids(row))
        assertNull("$row (d): no KRX in-progress bucket", checkNotNull(after) { "$row (d): nothing exposed" }.tab.inProgress[KRX_SERIES])
        assertEquals("$row (d): the GENERAL series and values are those the seed landed",
            checkNotNull(landed) { "$row (d): nothing landed" }.generalSeries(), after.generalSeries())
        assertEquals("$row (d): no event failure", emptyList<Throwable>(), f.failures)
    }

    // --- 03-O1 OFF (가) -------------------------------------------------------------------------------------------

    /**
     * 03-O1 OFF (가): 03-1's input with krxPermitSeq = 0 — both with 03-1's capInv (0 → 1, synthetic: the issuer keeps it 0 off) and with
     * capInv 0 throughout — and, likewise, 03-1's same-epoch cell (K1 kept, {KRX_CONTESTED} round trip) with p = 0. KRX is attached, as the
     * frozen HEAD expression (GraphV2AccessGate.kt:79-80) says for the seed capture at the release snapshot; the KRX half is read once.
     * Mutation: G-OFF (and G-CI in the synthetic cells).
     */
    @Test fun r03_O1_offTheHeldSeedLandsWithKrxAsHead() = rigTest { open ->
        val row = "03-O1"
        for ((label, capInv) in listOf("capInv 0 → 1 (synthetic)" to C1, "capInv 0 throughout" to C0)) {
            val f = open(a1Snap(krxPermitSeq = 0L))
            val c = components(serverTab(inProgress = mapOf(KRX_SERIES to KRX_BUCKET)))
            f.put(c)
            f.store.hold[1] = CompletableDeferred()
            val seedCapture = checkNotNull(f.bindNow()) { "$row premise: $label seed start bind" }
            assertEquals("$row premise: $label the seed capture takes K1", "K1", seedCapture.krxCapabilityEpoch)
            f.start(); f.activate(); runCurrent()
            assertEquals("$row premise: $label the seed's GENERAL read is out", 1, f.store.generalReads.size)
            f.snapshot = a1Snap(capabilityBlocks = setOf(TopicAccessBlock.CONTEXT_UNCERTAIN), capabilityInvalidations = capInv, krxPermitSeq = 0L)
            f.snapshot = a1Snap(record = RECORD_K2, issuedRecord = RECORD_K1, capabilityInvalidations = capInv, krxPermitSeq = 0L)
            assertTrue("$row premise: $label s2 token 7 still stands (A1 form)", f.snapshot.facts.tokenStanding)
            f.premiseOff("$row $label")
            assertTrue("$row premise: $label the frozen HEAD expression admits the seed capture's KRX at s2",
                headAdmitsKrx(seedCapture, f.snapshot))
            f.store.release(1); runCurrent()
            val entry = f.state.entries[KEY]
            assertEquals("$row $label: KRX attached, as HEAD", GraphEntry(joinGraphV2Components(c.general, c.krx).tab, null), entry)
            assertEquals("$row $label: the KRX in-progress bucket attached", KRX_BUCKET,
                checkNotNull(entry) { "$row $label: nothing landed" }.tab.inProgress[KRX_SERIES])
            assertEquals("$row $label: the KRX half was read once", 1, f.store.krxReads.size)
            f.premiseOff("$row $label")
            f.close()
        }
        // OFF twin of 03-1's same-epoch cell: K1 kept, only capInv moves, p = 0.
        for ((label, capInv) in listOf("same-epoch capInv 0 → 1 (synthetic)" to C1, "same-epoch capInv 0 throughout" to C0)) {
            val g = open(a1Snap(krxPermitSeq = 0L))
            val gc = components(serverTab(inProgress = mapOf(KRX_SERIES to KRX_BUCKET)))
            g.put(gc)
            g.store.hold[1] = CompletableDeferred()
            val seedCapture = checkNotNull(g.bindNow()) { "$row premise: $label seed start bind" }
            assertEquals("$row premise: $label the seed capture takes K1", "K1", seedCapture.krxCapabilityEpoch)
            g.start(); g.activate(); runCurrent()
            assertEquals("$row premise: $label the seed's GENERAL read is out", 1, g.store.generalReads.size)
            assertNull("$row premise: $label nothing landed before the release", g.state.entries[KEY])
            roundTrip(g, "$row $label", TopicAccessBlock.KRX_CONTESTED, capInv = capInv, heldPermit = 0L, releasedPermit = 0L)
            assertEquals("$row premise: $label only the activation was delivered", 1, g.controlEvents)
            g.premiseOff("$row $label")
            assertTrue("$row premise: $label the frozen HEAD expression admits the seed capture's KRX at s2",
                headAdmitsKrx(seedCapture, g.snapshot))
            g.store.release(1); runCurrent()
            val ge = g.state.entries[KEY]
            assertEquals("$row $label: KRX attached, as HEAD", GraphEntry(joinGraphV2Components(gc.general, gc.krx).tab, null), ge)
            assertEquals("$row $label: the KRX half was read once", 1, g.store.krxReads.size)
            assertEquals("$row $label: no event failure", emptyList<Throwable>(), g.failures)
            g.premiseOff("$row $label")
            g.close()
        }
    }

    // --- 03-O2 OFF (가) -------------------------------------------------------------------------------------------

    /**
     * 03-O2 OFF (가): 03-2 … 03-4 with a {LOSS_CANDIDATE} hold, krxPermitSeq = 0 and capInv 0, and the synthetic variant with p = 0 but capInv
     * 0 → 1. 03-2: KRX shown after the round trip, GENERAL unchanged; 03-3: KRX shown; 03-4: KRX shown (= HEAD, the frozen expression at
     * the exposure's bind). Mutations: G-OFF, G-CI.
     */
    @Test fun r03_O2_offMemoryKrxIsShownAsHead() = rigTest { open ->
        val row = "03-O2"
        for ((label, capInv) in listOf("capInv 0" to C0, "capInv 0 → 1 (synthetic)" to C1)) {
            // 03-2 at p = 0
            val f = open(a1Snap(krxPermitSeq = 0L))
            f.start(); f.activate(); runCurrent()
            assertEquals("$row premise: $label one tab request out", 1, f.tabs().size)
            f.tabs().single().tab.complete(ok(onlineKrxDto(1500.0))); runCurrent()
            val before = f.exposed()
            assertTrue("$row premise: $label 03-2 KRX shown before the round trip", KRX_SERIES in before.ids(row))
            roundTrip(f, "$row $label 03-2", TopicAccessBlock.LOSS_CANDIDATE, capInv = capInv, heldPermit = 0L, releasedPermit = 0L)
            f.premiseOff("$row $label 03-2")
            val n = checkNotNull(f.bindNow()) { "$row premise: $label 03-2 exposure bind" }
            assertTrue("$row premise: $label 03-2 the frozen HEAD expression admits KRX at the exposure's bind", headAdmitsKrx(n, f.snapshot))
            val after = f.exposed()
            assertTrue("$row $label 03-2: KRX shown, as HEAD", KRX_SERIES in after.ids(row))
            assertEquals("$row $label 03-2: GENERAL unchanged", checkNotNull(before) { "$row $label 03-2: nothing exposed before" }.generalSeries(),
                checkNotNull(after) { "$row $label 03-2: nothing exposed after" }.generalSeries())
            f.close()

            // 03-3 and 03-4 at p = 0
            val g = open(a1Snap(krxPermitSeq = 0L))
            g.start(); g.activate(); runCurrent()
            assertEquals("$row premise: $label 03-3 one tab request out", 1, g.tabs().size)
            roundTrip(g, "$row $label 03-3", TopicAccessBlock.LOSS_CANDIDATE, capInv = capInv, heldPermit = 0L, releasedPermit = 0L)
            g.tabs().single().tab.complete(ok(onlineKrxDto(1500.0))); runCurrent()
            g.premiseOff("$row $label 03-3")
            assertTrue("$row premise: $label 03-3 the frozen HEAD expression admits KRX at the exposure's bind",
                headAdmitsKrx(checkNotNull(g.bindNow()) { "$row premise: $label 03-3 exposure bind" }, g.snapshot))
            val third = g.exposed()
            assertTrue("$row $label 03-3: KRX shown, as HEAD", KRX_SERIES in third.ids(row))
            assertEquals("$row $label 03-3: GENERAL adopted", 1500.0, checkNotNull(third) { "$row $label 03-3: nothing exposed" }.rate(ONLINE), 0.0)

            g.refresh(force = true); runCurrent()
            assertEquals("$row premise: $label 03-4 a second request out", 2, g.tabs().size)
            g.tabs().last().tab.complete(ok(onlineKrxDto(1600.0))); runCurrent()
            g.premiseOff("$row $label 03-4")
            val fourth = g.exposed()
            assertTrue("$row $label 03-4: KRX shown, as HEAD", KRX_SERIES in fourth.ids(row))
            assertEquals("$row $label 03-4: GENERAL kept", 1600.0, checkNotNull(fourth) { "$row $label 03-4: nothing exposed" }.rate(ONLINE), 0.0)
            assertEquals("$row $label: no event failure", emptyList<Throwable>(), f.failures + g.failures)
            g.close()
        }
    }
}
