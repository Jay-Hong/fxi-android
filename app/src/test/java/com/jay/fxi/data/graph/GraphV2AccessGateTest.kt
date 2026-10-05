package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.AccessFence
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.TopicGrantContext
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
 * Claude-owned S4 B1b-1 contract r1: the Graph V2 access gate — a pure policy over the published topic access, no file I/O,
 * no coroutine, no A2 type.
 *
 * Oracles: ANDROID_V2_PLAN.md S4 :1288-1292 (read and exposure only under fresh approval; KRX only under the current capability);
 * SnapshotTopicUseAuthority.kt admitsUse (token, standing, user axis, invalidations from one snapshot); PremiumAccessCoordinator
 * topic grant issue (a fence is built from its token's issued context) and tokenStanding (issued context == current binding,
 * record fence and decision generation). Design: R4c/S4 b1b_design_codex.r1, b1b_verdict_codex.r1/r2 (gate API, GENERAL and
 * KRX conditions reduced to what tokenStanding does not already imply, one fresh snapshot per judgement, capability hold
 * round trip re-admits, rotation closes both axes).
 * Fixtures model these issuer invariants (PremiumAccessCoordinator accessFactsLocked / publishAccessSnapshotLocked): a token
 * stands exactly when its issued context is the current one and the user context is certain; a derived seal means the record's
 * epoch on that axis is null; a context-uncertain block comes with its flag; a transition that takes the user axis or the
 * standing token away counts one invalidation; every publication moves the revision.
 * G17 (B1b-2c-1 contract r1) adds the binding with the record's capability configuration.
 * Some fixtures are synthetic states that isolate a single block (the issuer publishes a seal together with NOT_GRANTED or
 * CONTEXT_UNCERTAIN); they do not claim the issuer publishes that exact combination or reaches it by that transition.
 */
class GraphV2AccessGateTest {

    private companion object {
        val T0: Instant = Instant.parse("2026-10-05T03:00:00Z")
        val ID1 = AuthIdentityFence("u1", 1L)
        val FENCE7 = TopicSessionFence(ID1, "U1", TopicGrantToken(7L))
        val LIFETIME7 = TopicUseLifetime(TopicGrantToken(7L), 3L)
        val BINDING1 = EntitlementsIdentity("u1", 1L)
        val RECORD_K1 = AccessFence("u1", "U1", "K1")
        val RECORD_K2 = AccessFence("u1", "U1", "K2")
        val GENERAL_ONLY = listOf("hana.usd-krw", "kb.usd-krw")
        val JOINED = listOf("hana.usd-krw", "krx.usd-krw-futures", "kb.usd-krw")
    }

    /**
     * A published snapshot whose token [token] was issued for [issuedRecord] at [issuedGeneration]. Standing is derived the way
     * the issuer derives it, so a fixture cannot keep a token standing over another context.
     */
    private fun snap(
        token: Long? = 7L,
        userBlocks: Set<TopicAccessBlock> = emptySet(),
        capabilityBlocks: Set<TopicAccessBlock> = emptySet(),
        binding: EntitlementsIdentity = BINDING1,
        record: AccessFence = RECORD_K1,
        issuedRecord: AccessFence = record,
        generation: Long = 10L,
        issuedGeneration: Long = generation,
        invalidations: Long = 3L,
        unconfirmed: Boolean = false
    ): TopicAccessSnapshot {
        val issued = token?.let { TopicGrantContext(binding, issuedRecord, issuedGeneration) }
        val userUncertain = TopicAccessBlock.CONTEXT_UNCERTAIN in userBlocks
        return TopicAccessSnapshot.INITIAL.copy(
            revision = 100L,
            facts = TopicAccessFacts.NONE.copy(
                token = token?.let(::TopicGrantToken),
                issuedFor = issued,
                binding = binding,
                recordFence = record,
                decisionGeneration = generation,
                tokenStanding = issued != null && !userUncertain && issued == TopicGrantContext(binding, record, generation),
                userBlocks = userBlocks,
                capabilityBlocks = capabilityBlocks,
                recordUnconfirmed = unconfirmed,
                userContextUncertain = userUncertain,
                capabilityContextUncertain = TopicAccessBlock.CONTEXT_UNCERTAIN in capabilityBlocks
            ),
            userInvalidations = invalidations
        )
    }

    /**
     * The live inputs the gate reads; a row changes them between judgements. Assigning [snapshot] publishes it with the next
     * revision. [reads] counts snapshot reads.
     */
    private class World(
        var identity: AuthIdentityFence? = ID1,
        var fence: TopicSessionFence? = FENCE7,
        initial: TopicAccessSnapshot,
        var protectedOpen: Boolean = true
    ) {
        private var revision = initial.revision
        var snapshot: TopicAccessSnapshot = initial
            set(value) {
                revision += 1
                field = value.copy(revision = revision)
            }
        var reads = 0
        val gate = GraphV2AccessGate(
            currentIdentity = { identity },
            currentAccessFence = { fence },
            snapshot = { reads++; snapshot },
            protectedAdmission = { protectedOpen }
        )
    }

    private fun world() = World(initial = snap())

    private fun World.captureK1(): GraphV2AccessCapture {
        val c = checkNotNull(gate.bind(FENCE7, LIFETIME7)) { "baseline bind" }
        assertEquals("baseline capability epoch", "K1", c.krxCapabilityEpoch)
        return c
    }

    private fun point(rate: Double) = FreeGraphPoint(T0 - 1.days, rate, rate + 1, rate - 1, "hana")

    /** General ordinals 0 and 2 around the KRX ordinal 1, so the joined order is visible. */
    private fun components(
        uid: String = "u1",
        epoch: String = "U1",
        krx: String? = "K1",
        response: String = "r1"
    ): GraphV2DiskComponents {
        val series = listOf(
            FreeGraphSeries("hana.usd-krw", listOf(point(1390.0)), "하나", "krw", "KRW", 2),
            FreeGraphSeries("krx.usd-krw-futures", listOf(point(1391.0)), "KRX", "krw", "KRW", 1),
            FreeGraphSeries("kb.usd-krw", listOf(point(1392.0)), "국민", "krw", "KRW", 2)
        )
        val tab = GraphV2Tab("usd", GraphPeriod.THREE_MONTHS, "1d", T0,
            FreeGraph("1d", series, "2026-07-05", "2026-10-05", T0 - 90.days, T0, "fixed_start"), emptyMap())
        val split = splitGraphV2ServerTab(tab, GraphV2GeneralKey(uid, epoch, "usd", GraphPeriod.THREE_MONTHS.code), krx, response, null)
        return (split as GraphV2Validation.Valid).value
    }

    private fun GraphV2ComponentJoin?.ids() = checkNotNull(this) { "join was refused" }.tab.graph.series.map { it.seriesId }

    private fun World.both(c: GraphV2AccessCapture) =
        gate.admits(c, GraphV2DiskComponent.GENERAL) to gate.admits(c, GraphV2DiskComponent.KRX)

    // --- baseline ----------------------------------------------------------------------------------------------

    /** A fresh approval opens both axes; the capture keeps the record's capability epoch; the exact pair joins in order. */
    @Test fun G01_aFreshApprovalOpensBothAxesAndJoinsTheExactPair() {
        val w = world()
        val c = w.captureK1()
        assertEquals(FENCE7, c.fence)
        assertEquals(LIFETIME7, c.lifetime)
        assertEquals(true to true, w.both(c))
        val joined = w.gate.joinForExposure(c, components())
        assertTrue(checkNotNull(joined).krxJoined)
        assertEquals(JOINED, joined.ids())
    }

    // --- B02 / B03: nothing opens without the issuer's current approval ------------------------------------------

    /**
     * No grant, a grant that no longer stands, another grant, or a user axis that is not granted: no bind, no old capture, no
     * join. Taking the user axis or the standing token away counts one invalidation; a same-context re-approval does not.
     */
    @Test fun G02_withoutAStandingGrantNothingOpens() {
        for ((label, s) in listOf(
            "not granted (resolving, pending without a grant)" to snap(userBlocks = setOf(TopicAccessBlock.NOT_GRANTED), invalidations = 4L),
            "no issued token (cached ACTIVE, a local purchase)" to snap(token = null, invalidations = 4L),
            "the decision moved on: the token no longer stands" to snap(generation = 11L, issuedGeneration = 10L, invalidations = 4L),
            "another grant is in force (a same-context re-approval)" to snap(token = 8L)
        )) {
            val w = world()
            val c = w.captureK1()
            w.snapshot = s
            assertNull("$label: bind", w.gate.bind(FENCE7, LIFETIME7))
            assertEquals("$label: old capture", false to false, w.both(c))
            assertNull("$label: join", w.gate.joinForExposure(c, components()))
        }
    }

    /** A user-axis seal or a closed protected admission (DeletionPending) closes both axes. */
    @Test fun G03_aSealOrAClosedProtectedAdmissionClosesBothAxes() {
        for (label in listOf("explicit seal", "derived seal", "protected admission closed")) {
            val w = world()
            val c = w.captureK1()
            when (label) {
                "explicit seal" -> w.snapshot = snap(userBlocks = setOf(TopicAccessBlock.EXPLICIT_SEAL), invalidations = 4L)
                // A derived seal is a record without a USER epoch: the record moved, so token 7 no longer stands.
                "derived seal" -> w.snapshot = snap(userBlocks = setOf(TopicAccessBlock.DERIVED_SEAL),
                    record = AccessFence("u1", null, "K1"), issuedRecord = RECORD_K1, invalidations = 4L)
                else -> w.protectedOpen = false
            }
            assertNull("$label: bind", w.gate.bind(FENCE7, LIFETIME7))
            assertEquals("$label: old capture", false to false, w.both(c))
            assertNull("$label: join", w.gate.joinForExposure(c, components()))
        }
    }

    /** Every judgement reads the live inputs again: a port made while open closes and reopens with them. */
    @Test fun G04_aPreparedPortAndAnEarlierJoinDoNotFixTheAnswer() {
        val w = world()
        val c = w.captureK1()
        val port = w.gate.ioAdmission(c)
        assertNotNull(w.gate.joinForExposure(c, components()))
        assertTrue(port.admits(GraphV2DiskComponent.GENERAL))
        w.protectedOpen = false
        assertEquals(false to false, w.both(c))
        assertFalse(port.admits(GraphV2DiskComponent.GENERAL))
        assertFalse(port.admits(GraphV2DiskComponent.KRX))
        assertNull(w.gate.joinForExposure(c, components()))
        w.protectedOpen = true
        assertTrue("the port reads again, it is not latched closed either", port.admits(GraphV2DiskComponent.KRX))
    }

    /** A capture belongs to its session: another auth generation, another fence or no USER epoch never opens. */
    @Test fun G05_anOldSessionNeverOpens() {
        val w = world()
        val c = w.captureK1()
        w.identity = AuthIdentityFence("u1", 2L)
        assertEquals("another auth generation, same uid", false to false, w.both(c))
        assertNull(w.gate.bind(FENCE7, LIFETIME7))
        w.identity = ID1
        w.fence = TopicSessionFence(ID1, "U1", TopicGrantToken(8L))
        assertEquals("the live fence moved on", false to false, w.both(c))
        w.fence = null
        assertEquals("no live fence", false to false, w.both(c))

        val noEpoch = TopicSessionFence(ID1, null, TopicGrantToken(7L))
        val w2 = World(fence = noEpoch, initial = snap())
        assertNull("no USER epoch", w2.gate.bind(noEpoch, LIFETIME7))
    }

    /** bind takes the caller's lifetime as is and refuses one that is not the fence's grant; it acquires nothing. */
    @Test fun G06_bindRefusesALifetimeOfAnotherGrant() {
        // The issuer has already moved to token 8 while the session still holds fence 7: a lifetime of 8 paired with fence 7
        // would pass every other check.
        val w = World(initial = snap(token = 8L))
        assertNull(w.gate.bind(FENCE7, TopicUseLifetime(TopicGrantToken(8L), 3L)))
    }

    /** The current approval opens again: a new session with its own grant binds. */
    @Test fun G07_theCurrentApprovalOfANewSessionBinds() {
        val id2 = AuthIdentityFence("u1", 2L)
        val fence8 = TopicSessionFence(id2, "U1", TopicGrantToken(8L))
        val w = World(identity = id2, fence = fence8,
            initial = snap(token = 8L, binding = EntitlementsIdentity("u1", 2L), generation = 11L))
        val c = checkNotNull(w.gate.bind(fence8, TopicUseLifetime(TopicGrantToken(8L), 3L)))
        assertEquals("K1", c.krxCapabilityEpoch)
        assertEquals(true to true, w.both(c))
    }

    // --- B10: KRX only under the current capability --------------------------------------------------------------

    /**
     * A capability block that leaves the record alone closes KRX alone: the general half stays open and joins alone; a bind during
     * it captures no epoch. (A capability derived seal moves the record: G08b.)
     */
    @Test fun G08_aCapabilityBlockClosesKrxAlone() {
        for (block in listOf(TopicAccessBlock.NOT_GRANTED, TopicAccessBlock.LOSS_CANDIDATE, TopicAccessBlock.EXPLICIT_SEAL,
            TopicAccessBlock.CONTEXT_UNCERTAIN)) {
            val w = world()
            val c = w.captureK1()
            w.snapshot = snap(capabilityBlocks = setOf(block))
            assertEquals("$block", true to false, w.both(c))
            val joined = w.gate.joinForExposure(c, components())
            assertFalse("$block", checkNotNull(joined).krxJoined)
            assertEquals("$block", GENERAL_ONLY, joined.ids())
            assertNull("$block: bind captures no epoch", checkNotNull(w.gate.bind(FENCE7, LIFETIME7)).krxCapabilityEpoch)
        }
    }

    /**
     * A capability derived seal is a record without a KRX epoch: token 7 no longer stands, so the old capture is closed on both
     * axes. A token issued for the new record opens the general half only; its bind captures no epoch.
     */
    @Test fun G08b_aCapabilityDerivedSealMovesTheRecord() {
        val w = world()
        val c = w.captureK1()
        val sealed = AccessFence("u1", "U1", null)
        w.snapshot = snap(capabilityBlocks = setOf(TopicAccessBlock.DERIVED_SEAL), record = sealed, issuedRecord = RECORD_K1,
            invalidations = 4L)
        assertEquals("token 7 no longer stands", false to false, w.both(c))
        assertNull(w.gate.joinForExposure(c, components()))

        val fence8 = TopicSessionFence(ID1, "U1", TopicGrantToken(8L))
        w.snapshot = snap(token = 8L, capabilityBlocks = setOf(TopicAccessBlock.DERIVED_SEAL), record = sealed, invalidations = 4L)
        w.fence = fence8
        val fresh = checkNotNull(w.gate.bind(fence8, TopicUseLifetime(TopicGrantToken(8L), 4L)))
        assertNull(fresh.krxCapabilityEpoch)
        assertEquals(true to false, w.both(fresh))
        assertEquals(GENERAL_ONLY, w.gate.joinForExposure(fresh, components()).ids())
    }

    /** A capture without an epoch never gains one: after the block lifts it is still general only; a new bind takes K1. */
    @Test fun G09_aCaptureWithoutAnEpochStaysWithoutOne() {
        val w = World(initial = snap(capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED)))
        val c = checkNotNull(w.gate.bind(FENCE7, LIFETIME7))
        assertNull(c.krxCapabilityEpoch)
        w.snapshot = snap()
        assertEquals(true to false, w.both(c))
        assertEquals(GENERAL_ONLY, w.gate.joinForExposure(c, components()).ids())
        assertEquals("K1", checkNotNull(w.gate.bind(FENCE7, LIFETIME7)).krxCapabilityEpoch)
    }

    /** A capability-only hold that comes and goes under the same epoch, token and invalidations re-admits the capture. */
    @Test fun G10_aCapabilityHoldRoundTripReadmits_aUserHoldDoesNot() {
        val w = world()
        val c = w.captureK1()
        w.snapshot = snap(capabilityBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE))
        assertEquals(true to false, w.both(c))
        w.snapshot = snap()
        assertEquals("same K1, token and invalidations", true to true, w.both(c))

        w.snapshot = snap(capabilityBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE), userBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE),
            invalidations = 4L)
        assertEquals("both held", false to false, w.both(c))
        w.snapshot = snap(invalidations = 4L)
        assertEquals("both released, but the user axis went away in between", false to false, w.both(c))
    }

    /**
     * A capability rotation as the issuer publishes it: the false edge holds KRX, the confirmed K2 record ends the old token's
     * standing (both axes), and only a fresh approval with KRX visible issues token 8 whose bind captures K2.
     */
    @Test fun G11_aRotationClosesBothAxesUntilAFreshApproval() {
        val w = world()
        val c = w.captureK1()
        w.snapshot = snap(capabilityBlocks = setOf(TopicAccessBlock.CONTEXT_UNCERTAIN))
        assertEquals("the false edge", true to false, w.both(c))
        w.snapshot = snap(record = RECORD_K2, issuedRecord = RECORD_K1,
            capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED), invalidations = 4L)
        assertEquals("K2 confirmed: token 7 no longer stands", false to false, w.both(c))

        val fence8 = TopicSessionFence(ID1, "U1", TopicGrantToken(8L))
        val lifetime8 = TopicUseLifetime(TopicGrantToken(8L), 4L)
        w.snapshot = snap(token = 8L, record = RECORD_K2, generation = 11L, capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED),
            invalidations = 4L)
        w.fence = fence8
        assertEquals("token 8 issued, KRX still hidden: old capture", false to false, w.both(c))
        assertNull("KRX hidden: the new bind captures no epoch", checkNotNull(w.gate.bind(fence8, lifetime8)).krxCapabilityEpoch)
        w.snapshot = snap(token = 8L, record = RECORD_K2, generation = 11L, invalidations = 4L)
        assertEquals("fresh approval with KRX visible: old capture", false to false, w.both(c))
        assertEquals("K2", checkNotNull(w.gate.bind(fence8, lifetime8)).krxCapabilityEpoch)
    }

    /** A new revision whose change does not touch the capture (the record became unconfirmed) is not a withdrawal. */
    @Test fun G12_aRevisionAloneChangesNothing() {
        val w = world()
        val c = w.captureK1()
        w.snapshot = snap(unconfirmed = true)
        assertEquals(true to true, w.both(c))
        assertEquals(JOINED, w.gate.joinForExposure(c, components()).ids())
    }

    // --- exposure join ---------------------------------------------------------------------------------------------

    /** The gate hands an allowed KRX half to the structural join, which keeps a mismatched pair apart. */
    @Test fun G13_aStructurallyMismatchedPairJoinsTheGeneralHalfOnly() {
        val w = world()
        val c = w.captureK1()
        val base = components()
        val krx = checkNotNull(base.krx)
        for ((label, pair) in listOf(
            "another response" to base.copy(krx = krx.copy(responseId = "r2")),
            "an ordinal collision" to base.copy(krx = krx.copy(component = krx.component.copy(
                series = krx.component.series.map { it.copy(ordinal = 0) }))),
            "another tab" to base.copy(krx = krx.copy(key = krx.key.copy(tab = "jpy")))
        )) {
            val joined = w.gate.joinForExposure(c, pair)
            assertFalse(label, checkNotNull(joined).krxJoined)
            assertEquals(label, GENERAL_ONLY, joined.ids())
        }
        val alone = w.gate.joinForExposure(c, base.copy(krx = null))
        assertFalse(checkNotNull(alone).krxJoined)
        assertEquals("no KRX half", GENERAL_ONLY, alone.ids())
    }

    /** The general half must be the capture's namespace; another uid or USER epoch is not exposed at all. */
    @Test fun G14_anotherNamespaceIsNotExposed() {
        val w = world()
        val c = w.captureK1()
        assertNull("another uid", w.gate.joinForExposure(c, components(uid = "u2")))
        assertNull("another USER epoch", w.gate.joinForExposure(c, components(epoch = "U0")))
    }

    /** A KRX half of another capability epoch kept in memory is not joined under the new capture. */
    @Test fun G15_anOlderKrxEpochIsNotJoinedUnderANewCapture() {
        val fence8 = TopicSessionFence(ID1, "U1", TopicGrantToken(8L))
        val w = World(fence = fence8, initial = snap(token = 8L, record = RECORD_K2, generation = 11L))
        val c = checkNotNull(w.gate.bind(fence8, TopicUseLifetime(TopicGrantToken(8L), 3L)))
        assertEquals("K2", c.krxCapabilityEpoch)
        val joined = w.gate.joinForExposure(c, components(krx = "K1"))
        assertFalse(checkNotNull(joined).krxJoined)
        assertEquals(GENERAL_ONLY, joined.ids())
        assertEquals("the K2 pair joins", JOINED, w.gate.joinForExposure(c, components(krx = "K2")).ids())
    }

    // --- carry-forward ---------------------------------------------------------------------------------------------

    /** CF1: a user hold that comes and goes closes the old capture for good; only a lifetime taken after it opens. */
    @Test fun CF1_aUserHoldRoundTripRetiresTheOldCapture() {
        val w = world()
        val c = w.captureK1()
        w.snapshot = snap(userBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE), invalidations = 4L)
        assertEquals(false to false, w.both(c))
        w.snapshot = snap(invalidations = 4L)
        assertEquals("released, but the old lifetime is spent", false to false, w.both(c))
        assertNull(w.gate.joinForExposure(c, components()))
        assertNull("the old lifetime does not bind either", w.gate.bind(FENCE7, LIFETIME7))
        val fresh = checkNotNull(w.gate.bind(FENCE7, TopicUseLifetime(TopicGrantToken(7L), 4L)))
        assertEquals(true to true, w.both(fresh))
    }

    /** CF3: a capture is checked whenever it is used; nothing it was given earlier keeps it open. */
    @Test fun CF3_aCaptureIsNotAPermission() {
        val w = world()
        val c = w.captureK1()
        val port = w.gate.ioAdmission(c)
        w.snapshot = snap(generation = 11L, issuedGeneration = 10L, invalidations = 4L)
        assertEquals(false to false, w.both(c))
        assertFalse(port.admits(GraphV2DiskComponent.GENERAL))
        assertNull(w.gate.joinForExposure(c, components()))
    }

    /** One judgement reads one fresh snapshot: GENERAL and KRX facts come from the same read, and nothing is cached. */
    @Test fun G16_eachJudgementReadsOneFreshSnapshot() {
        val w = world()
        val c = w.captureK1()
        val port = w.gate.ioAdmission(c)
        for ((label, judge) in listOf<Pair<String, () -> Any?>>(
            "bind" to { w.gate.bind(FENCE7, LIFETIME7) },
            "admits GENERAL" to { w.gate.admits(c, GraphV2DiskComponent.GENERAL) },
            "admits KRX" to { w.gate.admits(c, GraphV2DiskComponent.KRX) },
            "port" to { port.admits(GraphV2DiskComponent.KRX) },
            "join" to { w.gate.joinForExposure(c, components()) }
        )) {
            w.reads = 0
            judge()
            judge()
            assertEquals("$label: one read per judgement, two judgements", 2, w.reads)
        }
    }

    /**
     * B1b-2c: a binding carries the capture and the record's capability configuration, both from one admitted snapshot. A
     * capability block keeps the record's epoch in the configuration while the capture has none; a refused admission is null,
     * never a closed configuration; bind() is the binding's capture.
     */
    @Test fun G17_aBindingCarriesTheRecordsCapabilityConfiguration() {
        val w = world()
        fun bound(fence: TopicSessionFence, lifetime: TopicUseLifetime): GraphV2AccessBinding? {
            w.reads = 0
            val binding = w.gate.bindWithConfiguration(fence, lifetime)
            if (binding != null) assertEquals("one snapshot read", 1, w.reads) else assertTrue("at most one read", w.reads <= 1)
            assertEquals("bind() is the binding's capture", binding?.captured, w.gate.bind(fence, lifetime))
            return binding
        }
        fun binding(fence: TopicSessionFence, lifetime: TopicUseLifetime, captured: String?, record: String?, allowed: Boolean) =
            GraphV2AccessBinding(GraphV2AccessCapture(fence, lifetime, captured), GraphV2CapabilityConfiguration(record, allowed))

        assertEquals("allowed", binding(FENCE7, LIFETIME7, "K1", "K1", true), bound(FENCE7, LIFETIME7))
        for (block in listOf(TopicAccessBlock.NOT_GRANTED, TopicAccessBlock.LOSS_CANDIDATE, TopicAccessBlock.EXPLICIT_SEAL,
            TopicAccessBlock.CONTEXT_UNCERTAIN)) {
            w.snapshot = snap(capabilityBlocks = setOf(block))
            assertEquals("$block keeps the record's epoch", binding(FENCE7, LIFETIME7, null, "K1", false), bound(FENCE7, LIFETIME7))
        }

        // G11's fresh approval of the K2 record with KRX still hidden: the configuration names K2, the capture no epoch.
        val fence8 = TopicSessionFence(ID1, "U1", TopicGrantToken(8L))
        val lifetime8 = TopicUseLifetime(TopicGrantToken(8L), 4L)
        w.snapshot = snap(token = 8L, record = RECORD_K2, generation = 11L, capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED),
            invalidations = 4L)
        w.fence = fence8
        assertEquals("K2 hidden", binding(fence8, lifetime8, null, "K2", false), bound(fence8, lifetime8))
        w.snapshot = snap(token = 8L, record = RECORD_K2, generation = 11L, invalidations = 4L)
        assertEquals("K2 visible", binding(fence8, lifetime8, "K2", "K2", true), bound(fence8, lifetime8))

        // G08b's derived seal: the record has no epoch.
        w.snapshot = snap(token = 8L, capabilityBlocks = setOf(TopicAccessBlock.DERIVED_SEAL), record = AccessFence("u1", "U1", null),
            invalidations = 4L)
        assertEquals("derived seal", binding(fence8, lifetime8, null, null, false), bound(fence8, lifetime8))

        // A refused admission is null.
        w.protectedOpen = false
        assertNull("protected closed", bound(fence8, lifetime8))
        w.protectedOpen = true
        w.snapshot = snap(token = 8L, record = RECORD_K2, generation = 11L, userBlocks = setOf(TopicAccessBlock.NOT_GRANTED),
            invalidations = 5L)
        assertNull("a user hold", bound(fence8, lifetime8))
        assertNull("another grant's lifetime", bound(fence8, LIFETIME7))
    }
}
