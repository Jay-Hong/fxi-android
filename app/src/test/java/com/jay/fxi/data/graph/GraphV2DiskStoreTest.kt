package com.jay.fxi.data.graph

import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeResult
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.purge.ManifestScopePurger
import com.jay.fxi.data.entitlements.purge.PurgeCause
import com.jay.fxi.data.entitlements.purge.PurgeClassification
import com.jay.fxi.data.entitlements.purge.PurgeManifest
import com.jay.fxi.data.entitlements.purge.PurgeRequest
import com.jay.fxi.data.entitlements.purge.TargetOutcome
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphV2Tab
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned S4 B1a-2 contract r1: the Graph V2 disk store — atomic replacement, write reservations ordered by adoption,
 * purge serialized against writes, the two manifest targets and their unwired adapter.
 *
 * Oracles: ANDROID_V2_PLAN.md S4 :1288-1294 and §7 S1 / I4 purge semantics (a journal entry clears every past namespace of its
 * owner on its axes and keeps the live one; a null epoch is unknown, not "nothing"; an unnarrowed owner is refused); the
 * existing purge vocabulary (AccessEpochStore.kt PendingPurge, ScopePurger.kt PurgeNamespace, PurgeTargetAdapter.kt
 * TargetOutcome, PurgeDecision.kt DERIVED_HERE = delete now, ManifestScopePurger.kt unregistered = Deferred) and the S3
 * TopicLastKnownPurgeAdapter policy for a null owner. Design: R4c/S4 b1_verdict_codex.r1 §2-§5 (store API, file layout
 * general/… and krx/…, write and purge order, selection table, rows B12-s1..s3, B13-a..d, atomic replace a/b).
 * No gate, capture or A2 wiring here; no production caller registers the adapter.
 */
class GraphV2DiskStoreTest {

    private companion object {
        val T0: Instant = Instant.parse("2026-10-05T03:00:00Z")
        val GENERAL_TARGET get() = checkNotNull(PurgeManifest.byId("file:graph_v2_general"))
        val KRX_TARGET get() = checkNotNull(PurgeManifest.byId("file:graph_v2_krx"))
    }

    @get:Rule val folder = TemporaryFolder()

    private val codec: GraphV2EnvelopeCodec = JsonGraphV2EnvelopeCodec()
    private val open = GraphV2IoAdmission { true }

    private class Prepared(val target: File, val inner: GraphV2PreparedReplace) : GraphV2PreparedReplace

    /** The real file I/O with hooks to refuse or hold one step. */
    private class Files : GraphV2AtomicFileIo {
        val real: GraphV2AtomicFileIo = DefaultGraphV2AtomicFileIo()
        @Volatile var failPrepare: (File) -> Boolean = { false }
        @Volatile var failPublish: (File) -> Boolean = { false }
        @Volatile var holdPrepare: CountDownLatch? = null
        val prepareHeld = CountDownLatch(1)
        /** Which staged files [holdPrepare] holds; every one unless a row narrows it (S4 RT01-B2b-2). */
        @Volatile var holdPrepareOf: (File) -> Boolean = { true }
        /** Holds a publish inside the store's lock, before the real rename (S4 RT01-B2b-2). */
        @Volatile var holdPublish: CountDownLatch? = null
        @Volatile var holdPublishOf: (File) -> Boolean = { true }
        val publishHeld = CountDownLatch(1)
        val published: MutableList<File> = java.util.Collections.synchronizedList(mutableListOf())
        @Volatile var refuseDelete: (File) -> Boolean = { false }
        @Volatile var enumerateFailure: Throwable? = null
        /** Every read and staging call that reached the file boundary, in order. */
        val reads: MutableList<File> = java.util.Collections.synchronizedList(mutableListOf())
        val prepares: MutableList<File> = java.util.Collections.synchronizedList(mutableListOf())

        override fun read(file: File): ByteArray? {
            reads += file
            return real.read(file)
        }

        override fun prepareReplace(target: File, bytes: ByteArray): GraphV2PreparedReplace {
            prepares += target
            if (failPrepare(target)) throw IOException("prepare refused")
            val prepared = Prepared(target, real.prepareReplace(target, bytes))
            holdPrepare?.takeIf { holdPrepareOf(target) }?.let { latch ->
                prepareHeld.countDown()
                check(latch.await(10, TimeUnit.SECONDS)) { "held prepare was never released" }
            }
            return prepared
        }

        override fun publishReplace(prepared: GraphV2PreparedReplace) {
            val p = prepared as Prepared
            if (failPublish(p.target)) throw IOException("publish refused")
            holdPublish?.takeIf { holdPublishOf(p.target) }?.let { latch ->
                publishHeld.countDown()
                check(latch.await(10, TimeUnit.SECONDS)) { "held publish was never released" }
            }
            real.publishReplace(p.inner)
            published += p.target
        }

        override fun discardReplace(prepared: GraphV2PreparedReplace) = real.discardReplace((prepared as Prepared).inner)

        override fun enumerateFiles(root: File): List<File> {
            enumerateFailure?.let { throw it }
            return real.enumerateFiles(root)
        }

        override fun deleteIfExists(file: File): Boolean {
            if (refuseDelete(file)) throw IOException("delete refused")
            return real.deleteIfExists(file)
        }
    }

    private fun store(files: Files = Files()): GraphV2DiskStore =
        FileGraphV2DiskStore(folder.root, codec, files, Dispatchers.IO)

    private fun isKrx(file: File) = file.relativeTo(folder.root).invariantSeparatorsPath.startsWith("krx/")
    private fun files() = folder.root.walk().filter { it.isFile }.toList()

    private fun point(rate: Double) = FreeGraphPoint(T0 - 1.days, rate, rate + 1, rate - 1, "hana")

    private fun components(
        uid: String = "u1",
        epoch: String = "e1",
        krx: String? = "k1",
        tab: String = "usd",
        period: GraphPeriod = GraphPeriod.THREE_MONTHS,
        response: String = "r1",
        rate: Double = 1390.0
    ): GraphV2DiskComponents {
        val series = listOf(
            FreeGraphSeries("hana.usd-krw", listOf(point(rate)), "하나", "krw", "KRW", 2),
            FreeGraphSeries("krx.usd-krw-futures", listOf(point(rate + 1)), "KRX", "krw", "KRW", 1)
        )
        val serverTab = GraphV2Tab(tab, period, "1d", T0, FreeGraph("1d", series, "2026-07-05", "2026-10-05", T0 - 90.days, T0, "fixed_start"), emptyMap())
        val split = splitGraphV2ServerTab(serverTab, GraphV2GeneralKey(uid, epoch, tab, period.code), krx, response, null)
        return (split as GraphV2Validation.Valid).value
    }

    private fun reserved(s: GraphV2DiskStore, c: GraphV2DiskComponents): GraphV2WriteTicket {
        val r = s.reserveWrite(c)
        check(r is GraphV2WriteReservation.Reserved) { "reserve: $r" }
        return r.ticket
    }

    private suspend fun GraphV2DiskStore.save(c: GraphV2DiskComponents): GraphV2WriteReport = write(reserved(this, c), open)

    private suspend fun GraphV2DiskStore.general(c: GraphV2DiskComponents): Double? =
        when (val r = readGeneral(c.general.key, null, open)) {
            is GraphV2DiskRead.Found -> r.envelope.component.series.first().series.points.first().rate
            GraphV2DiskRead.Absent -> null
            else -> throw AssertionError("general read: $r")
        }

    private suspend fun GraphV2DiskStore.krx(c: GraphV2DiskComponents): Double? =
        when (val r = readKrx(checkNotNull(c.krx).key, null, open)) {
            is GraphV2DiskRead.Found -> r.envelope.component.series.first().series.points.first().rate
            GraphV2DiskRead.Absent -> null
            else -> throw AssertionError("krx read: $r")
        }

    private fun ns(owner: String?, currentUser: String?, currentKrx: String?, pendingUser: String?, pendingKrx: String?, scope: PurgeScope) =
        PurgeNamespace(owner, currentUser, currentKrx, PendingPurge(owner, pendingUser, pendingKrx, setOf(scope)))

    private fun skipped(label: String, o: GraphV2ComponentWriteOutcome?) =
        assertTrue("$label: expected Skipped, got $o", o is GraphV2ComponentWriteOutcome.Skipped)

    private fun failed(label: String, o: GraphV2ComponentWriteOutcome?) =
        assertTrue("$label: expected Failed, got $o", o is GraphV2ComponentWriteOutcome.Failed)

    // --- B12-s1 reservations, not lock order, decide which answer lands ---------------------------------------

    /** A later reservation of the same key wins whichever write runs first. */
    @Test fun B12s1a_theLaterReservationWins() = runBlocking {
        val s = store()
        val r1 = reserved(s, components(rate = 1390.0))
        val r2 = reserved(s, components(response = "r2", rate = 1500.0))
        val w2 = s.write(r2, open)
        assertEquals(GraphV2ComponentWriteOutcome.Replaced, w2.general)
        assertEquals(GraphV2ComponentWriteOutcome.Replaced, w2.krx)
        val w1 = s.write(r1, open)
        skipped("older general", w1.general)
        skipped("older krx", w1.krx)
        assertEquals(1500.0, s.general(components()))
        assertEquals(1501.0, s.krx(components()))
    }

    /** A newer reservation made while the older write is already prepared stops it at publish; its temporary file goes. */
    @Test fun B12s1b_aNewerReservationStopsAnOlderPreparedWrite() = runBlocking {
        val f = Files().apply { holdPrepare = CountDownLatch(1) }
        val s = store(f)
        val r1 = reserved(s, components(rate = 1390.0))
        val w1 = async(Dispatchers.IO) { s.write(r1, open) }
        check(f.prepareHeld.await(10, TimeUnit.SECONDS))
        val r2 = reserved(s, components(response = "r2", rate = 1500.0))
        val latch = checkNotNull(f.holdPrepare)
        f.holdPrepare = null
        latch.countDown()
        skipped("older general", withTimeout(10_000) { w1.await() }.general)
        assertNull("nothing of the older answer landed", s.general(components()))
        s.write(r2, open)
        assertEquals(1500.0, s.general(components()))
        assertEquals("general and KRX files only, no temporary left", 2, files().size)
    }

    /** When the newest write fails, an older reservation still does not land. */
    @Test fun B12s1c_aFailedNewerWriteDoesNotLetTheOlderLand() = runBlocking {
        val f = Files()
        val s = store(f)
        s.save(components(response = "r0", rate = 1300.0))
        val r1 = reserved(s, components(rate = 1390.0))
        val r2 = reserved(s, components(response = "r2", rate = 1500.0))
        f.failPublish = { true }
        failed("newest", s.write(r2, open).general)
        f.failPublish = { false }
        skipped("older", s.write(r1, open).general)
        assertEquals(1300.0, s.general(components()))
    }

    // --- B12-s2 purge against writes ----------------------------------------------------------------------

    /** A purge removes what was written, and the retired namespace cannot be reserved again. */
    @Test fun B12s2a_aPurgeRemovesWrittenFilesAndRetiresTheNamespace() = runBlocking {
        val s = store()
        val c = components(epoch = "e1")
        s.save(c)
        val n = ns("u1", "e2", null, "e1", null, PurgeScope.USER)
        assertEquals(TargetOutcome.Removed, s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, n))
        assertEquals(TargetOutcome.Removed, s.purge(GraphV2DiskComponent.KRX, PurgeScope.USER, n))
        assertNull(s.general(c))
        assertNull(s.krx(c))
        assertEquals(emptyList<File>(), files())
        assertTrue("a retired namespace is not reopened", s.reserveWrite(components(epoch = "e1", rate = 1600.0)) is GraphV2WriteReservation.Rejected)
    }

    /** A reservation with no file yet is cancelled by the purge; writing it afterwards lands nothing. */
    @Test fun B12s2b_aPurgeCancelsAPendingReservation() = runBlocking {
        val s = store()
        val c = components(epoch = "e1")
        val r = reserved(s, c)
        val n = ns("u1", "e2", null, "e1", null, PurgeScope.USER)
        assertEquals("a removed reservation counts", TargetOutcome.Removed, s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, n))
        assertEquals("a removed reservation counts", TargetOutcome.Removed, s.purge(GraphV2DiskComponent.KRX, PurgeScope.USER, n))
        assertEquals("an already removed one does not", TargetOutcome.NothingToRemove, s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, n))
        assertEquals("an already removed one does not", TargetOutcome.NothingToRemove, s.purge(GraphV2DiskComponent.KRX, PurgeScope.USER, n))
        val w = s.write(r, open)
        skipped("general", w.general)
        skipped("krx", w.krx)
        assertEquals(emptyList<File>(), files())
    }

    /** A purge that arrives while a write is prepared retires the namespace before it waits; the write then lands nothing. */
    @Test fun B12s2c_aPurgeArrivingDuringAPreparedWriteWins() = runBlocking {
        val f = Files().apply { holdPrepare = CountDownLatch(1) }
        val s = store(f)
        val c = components(epoch = "e1")
        val r = reserved(s, c)
        val w = async(Dispatchers.IO) { s.write(r, open) }
        check(f.prepareHeld.await(10, TimeUnit.SECONDS))
        val n = ns("u1", "e2", null, "e1", null, PurgeScope.USER)
        val p = async(Dispatchers.IO) { s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, n) }
        withTimeout(10_000) {
            // The purge records the retirement before it waits for the write lock: a fresh reservation of the
            // same namespace is refused once it has. A probe reserved too early is cancelled and retried.
            while (true) {
                when (val probe = s.reserveWrite(components(epoch = "e1", tab = "jpy"))) {
                    is GraphV2WriteReservation.Rejected -> break
                    is GraphV2WriteReservation.Reserved -> { s.cancelWrite(probe.ticket); delay(10) }
                }
            }
        }
        val latch = checkNotNull(f.holdPrepare)
        f.holdPrepare = null
        latch.countDown()
        skipped("prepared general", withTimeout(10_000) { w.await() }.general)
        assertFalse(withTimeout(10_000) { p.await() } is TargetOutcome.Failed)
        assertNull(s.general(c))
        assertTrue("no general file and no temporary beside it", files().none { !isKrx(it) })
    }

    // --- B12-s3 a capability purge spares the general half ---------------------------------------------------

    /** A capability purge cancels the KRX half of a pending write; the general half still lands. */
    @Test fun B12s3_aCapabilityPurgeSparesTheGeneralHalf() = runBlocking {
        val s = store()
        val c = components(epoch = "e1", krx = "k1")
        val r = reserved(s, c)
        assertFalse(s.purge(GraphV2DiskComponent.KRX, PurgeScope.CAPABILITY, ns("u1", "e1", "k2", "e1", "k1", PurgeScope.CAPABILITY)) is TargetOutcome.Failed)
        val w = s.write(r, open)
        assertEquals(GraphV2ComponentWriteOutcome.Replaced, w.general)
        skipped("retired krx", w.krx)
        assertEquals(1390.0, s.general(c))
        assertNull(s.krx(c))
    }

    // --- B13-a the selection table --------------------------------------------------------------------------

    /** Each axis clears every past namespace of the entry's owner on its own component and keeps the live one. */
    @Test fun B13a_theSelectionTable() = runBlocking {
        val s = store()
        val past = listOf(
            components("u1", "e1", "k1", "usd", GraphPeriod.THREE_MONTHS),
            components("u1", "e1", "k1", "usd", GraphPeriod.ONE_YEAR),
            components("u1", "e1", "k1", "jpy", GraphPeriod.THREE_MONTHS)
        )
        val live = components("u1", "e2", "k2", "usd", GraphPeriod.THREE_MONTHS)
        val liveUserOldKrx = components("u1", "e2", "k1", "jpy", GraphPeriod.THREE_MONTHS)
        val other = components("u2", "e1", "k1", "usd", GraphPeriod.THREE_MONTHS)
        (past + live + liveUserOldKrx + other).forEach { s.save(it) }

        val user = ns("u1", "e2", "k2", "e1", null, PurgeScope.USER)
        assertEquals(TargetOutcome.Removed, s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, user))
        past.forEach { assertNull("past general ${it.general.key}", s.general(it)) }
        assertEquals("a general purge touches no KRX file", 6, files().count { isKrx(it) })
        listOf(live, liveUserOldKrx, other).forEach { assertEquals("kept general ${it.general.key}", 1390.0, s.general(it)) }

        assertEquals(TargetOutcome.Removed, s.purge(GraphV2DiskComponent.KRX, PurgeScope.USER, user))
        assertEquals("live, live-user-old-capability and the other owner's KRX files remain", 3, files().count { isKrx(it) })
        listOf(live, liveUserOldKrx, other).forEach { assertEquals("kept krx ${it.general.key}", 1391.0, s.krx(it)) }

        val capability = ns("u1", "e2", "k2", "e2", "k1", PurgeScope.CAPABILITY)
        assertEquals(TargetOutcome.NothingToRemove, s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.CAPABILITY, capability))
        assertEquals(1390.0, s.general(liveUserOldKrx))
        assertEquals(TargetOutcome.Removed, s.purge(GraphV2DiskComponent.KRX, PurgeScope.CAPABILITY, capability))
        assertNull("old capability epoch under the live user epoch", s.krx(liveUserOldKrx))
        assertEquals(1391.0, s.krx(live))
        assertEquals(1391.0, s.krx(other))
    }

    // --- B13-b nulls and contradictions ---------------------------------------------------------------------

    /** A null pending epoch still clears every past namespace; no current namespace keeps nothing. */
    @Test fun B13b_unknownEpochsAreNotNothing() = runBlocking {
        val s = store()
        val e1 = components(epoch = "e1")
        val e2 = components(epoch = "e2")
        val e3 = components(epoch = "e3")
        listOf(e1, e2, e3).forEach { s.save(it) }
        assertEquals(TargetOutcome.Removed, s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, ns("u1", "e3", null, null, null, PurgeScope.USER)))
        assertNull(s.general(e1))
        assertNull(s.general(e2))
        assertEquals(1390.0, s.general(e3))
        assertEquals(TargetOutcome.Removed, s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, ns("u1", null, null, "e3", null, PurgeScope.USER)))
        assertNull("no current namespace, nothing kept", s.general(e3))
    }

    /** An unnarrowed owner, or a pending epoch equal to the live one, is refused before anything is touched. */
    @Test fun B13b_contradictoryRequestsAreRefusedUntouched() = runBlocking {
        val s = store()
        listOf(components(epoch = "e1"), components(epoch = "e2", krx = "k2")).forEach { s.save(it) }
        val before = files().map { it.path to it.readBytes().toList() }.toSet()
        for ((label, component, scope, n) in listOf(
            Quad("null owner", GraphV2DiskComponent.GENERAL, PurgeScope.USER, ns(null, "e2", null, "e1", null, PurgeScope.USER)),
            Quad("pending = current user", GraphV2DiskComponent.GENERAL, PurgeScope.USER, ns("u1", "e2", null, "e2", null, PurgeScope.USER)),
            Quad("pending = current capability", GraphV2DiskComponent.KRX, PurgeScope.CAPABILITY, ns("u1", "e2", "k2", "e2", "k2", PurgeScope.CAPABILITY)),
            Quad("entry owner null, live owner known", GraphV2DiskComponent.GENERAL, PurgeScope.USER,
                PurgeNamespace("u1", "e2", null, PendingPurge(null, "e1", null, setOf(PurgeScope.USER)))),
            Quad("live owner differs from the entry", GraphV2DiskComponent.GENERAL, PurgeScope.USER,
                PurgeNamespace("u2", "e2", null, PendingPurge("u1", "e1", null, setOf(PurgeScope.USER)))),
            Quad("scope not in the entry", GraphV2DiskComponent.GENERAL, PurgeScope.USER,
                PurgeNamespace("u1", "e2", null, PendingPurge("u1", "e1", null, setOf(PurgeScope.CAPABILITY)))),
            Quad("empty owner", GraphV2DiskComponent.GENERAL, PurgeScope.USER, ns("", "e2", null, "e1", null, PurgeScope.USER))
        )) {
            assertTrue("$label: Failed", s.purge(component, scope, n) is TargetOutcome.Failed)
            assertEquals("$label: nothing touched", before, files().map { it.path to it.readBytes().toList() }.toSet())
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    // --- B13-c failure, retry, cancellation ------------------------------------------------------------------

    /** A refused delete or a failed listing is Failed; a rerun finishes the rest; a further run finds nothing. */
    @Test fun B13c_failuresAreFailed_andARerunFinishes() = runBlocking {
        val f = Files()
        val s = store(f)
        listOf(components(epoch = "e1", tab = "usd"), components(epoch = "e1", tab = "jpy"), components(epoch = "e2")).forEach { s.save(it) }
        val n = ns("u1", "e2", null, "e1", null, PurgeScope.USER)

        f.enumerateFailure = IOException("listing failed")
        assertTrue("listing failure", s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, n) is TargetOutcome.Failed)
        f.enumerateFailure = null

        f.refuseDelete = { !isKrx(it) && it.path.contains(hexOf("usd")) }
        assertTrue("refused delete", s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, n) is TargetOutcome.Failed)
        f.refuseDelete = { false }
        assertEquals(TargetOutcome.Removed, s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, n))
        assertEquals(TargetOutcome.NothingToRemove, s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, n))
        assertNull(s.general(components(epoch = "e1", tab = "usd")))
        assertNull(s.general(components(epoch = "e1", tab = "jpy")))
        assertEquals(1390.0, s.general(components(epoch = "e2")))
    }

    /** Cancellation is not a failure to report: it propagates. */
    @Test fun B13c_cancellationPropagates() = runBlocking {
        val f = Files()
        val s = store(f)
        s.save(components(epoch = "e1"))
        f.enumerateFailure = CancellationException("caller left")
        try {
            s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, ns("u1", "e2", null, "e1", null, PurgeScope.USER))
            fail("cancellation was swallowed")
        } catch (expected: CancellationException) {
            assertEquals("caller left", expected.message)
        }
    }

    private fun hexOf(text: String) = text.encodeToByteArray().joinToString("") { "%02x".format(it) }

    // --- B14 retirement is concrete: a purge never closes an epoch minted after it --------------------------
    // Epochs are opaque ids that are never reused, and a purge is handed the live record, so every namespace
    // that exists when it runs, other than the kept one, is past. A namespace first seen later may be new or an unobserved past namespace; the store cannot distinguish them from opaque epoch IDs.

    /** A sweep keeps only the epoch it was given; an epoch minted later is a live namespace, before and after its own purge. */
    @Test fun B14a_aNewerEpochIsWritableBeforeAndAfterItsPurge() = runBlocking {
        val s = store()
        s.save(components(epoch = "e1"))
        assertEquals(TargetOutcome.Removed, s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, ns("u1", "e2", null, "e1", null, PurgeScope.USER)))
        // The account rotates again (e2 -> e3) and e3 is written before the e2 entry is purged.
        val e3 = components(epoch = "e3", krx = null)
        assertEquals(GraphV2ComponentWriteOutcome.Replaced, s.write(reserved(s, e3), open).general)
        assertEquals(1390.0, s.general(e3))
        assertFalse(s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, ns("u1", "e3", null, "e2", null, PurgeScope.USER)) is TargetOutcome.Failed)
        assertEquals("the live epoch survives the purge that keeps it", 1390.0, s.general(e3))
        assertTrue("still writable after its purge", s.reserveWrite(components(epoch = "e3", krx = null, response = "r2")) is GraphV2WriteReservation.Reserved)
        listOf("e1", "e2").forEach {
            assertTrue("$it is not reopened", s.reserveWrite(components(epoch = it, krx = null, response = "r3")) is GraphV2WriteReservation.Rejected)
        }
    }

    /** A user-only rotation brings no capability purge; the capability purge before it must not hold the new user epoch's KRX tuple. */
    @Test fun B14b_aUserOnlyRotationKeepsTheLiveKrxTupleWritable() = runBlocking {
        val s = store()
        s.save(components(epoch = "e1", krx = "k1"))
        // Capability-only rotation k1 -> k2: the journal entry carries no user epoch.
        assertEquals(TargetOutcome.Removed, s.purge(GraphV2DiskComponent.KRX, PurgeScope.CAPABILITY, ns("u1", "e1", "k2", null, "k1", PurgeScope.CAPABILITY)))
        // User-only rotation e1 -> e2: the entry's scopes are {USER} and the capability epoch stays k2.
        val user = ns("u1", "e2", "k2", "e1", null, PurgeScope.USER)
        assertEquals(TargetOutcome.Removed, s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, user))
        assertFalse(s.purge(GraphV2DiskComponent.KRX, PurgeScope.USER, user) is TargetOutcome.Failed)
        val live = components(epoch = "e2", krx = "k2", response = "r2", rate = 1500.0)
        val w = s.write(reserved(s, live), open)
        assertEquals(GraphV2ComponentWriteOutcome.Replaced, w.general)
        assertEquals(GraphV2ComponentWriteOutcome.Replaced, w.krx)
        assertEquals(1501.0, s.krx(live))
        for ((u, k) in listOf("e1" to "k1", "e1" to "k2", "e2" to "k1")) {
            assertTrue("($u,$k) is not reopened", s.reserveWrite(components(epoch = u, krx = k, response = "r3")) is GraphV2WriteReservation.Rejected)
        }
    }

    /** Without a named pending epoch, what the store knows when the purge starts, written or only reserved, stays retired. */
    @Test fun B14c_knownAndReservedNamespacesAreRetiredWithoutAPendingEpoch() = runBlocking {
        val s = store()
        s.save(components(epoch = "e0"))
        val pending = reserved(s, components(epoch = "e1", response = "r2"))
        val unknown = ns("u1", "e2", "k2", null, null, PurgeScope.USER)
        assertEquals(TargetOutcome.Removed, s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, unknown))
        assertEquals(TargetOutcome.Removed, s.purge(GraphV2DiskComponent.KRX, PurgeScope.USER, unknown))
        val w = s.write(pending, open)
        skipped("reserved general", w.general)
        skipped("reserved krx", w.krx)
        assertEquals(emptyList<File>(), files())
        listOf("e0", "e1").forEach {
            assertTrue("$it is not reopened", s.reserveWrite(components(epoch = it, response = "r3")) is GraphV2WriteReservation.Rejected)
        }
        assertTrue("the live tuple is open", s.reserveWrite(components(epoch = "e2", krx = "k2", response = "r4")) is GraphV2WriteReservation.Reserved)
    }

    /** B12s2c without a named pending epoch: the reserved namespace is retired before the purge waits for the write lock. */
    @Test fun B14d_aPreparedWriteLosesToAPurgeWithoutAPendingEpoch() = runBlocking {
        val f = Files().apply { holdPrepare = CountDownLatch(1) }
        val s = store(f)
        val c = components(epoch = "e1")
        val r = reserved(s, c)
        val w = async(Dispatchers.IO) { s.write(r, open) }
        check(f.prepareHeld.await(10, TimeUnit.SECONDS))
        val p = async(Dispatchers.IO) { s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, ns("u1", "e2", null, null, null, PurgeScope.USER)) }
        withTimeout(10_000) {
            while (true) {
                when (val probe = s.reserveWrite(components(epoch = "e1", tab = "jpy"))) {
                    is GraphV2WriteReservation.Rejected -> break
                    is GraphV2WriteReservation.Reserved -> { s.cancelWrite(probe.ticket); delay(10) }
                }
            }
        }
        val latch = checkNotNull(f.holdPrepare)
        f.holdPrepare = null
        latch.countDown()
        skipped("prepared general", withTimeout(10_000) { w.await() }.general)
        assertFalse(withTimeout(10_000) { p.await() } is TargetOutcome.Failed)
        assertNull(s.general(c))
        assertTrue("no general file and no temporary beside it", files().none { !isKrx(it) })
    }

    /** A named pending epoch is retired even when the store has never seen it; every other epoch stays open. */
    @Test fun B14e_aNamedPendingEpochIsRetiredWithNothingOnDisk() = runBlocking {
        val s = store()
        val user = ns("u1", "e2", "k2", "e1", null, PurgeScope.USER)
        assertEquals(TargetOutcome.NothingToRemove, s.purge(GraphV2DiskComponent.KRX, PurgeScope.USER, user))
        assertTrue("the KRX half of a named user epoch", s.reserveWrite(components(epoch = "e1", krx = "k2")) is GraphV2WriteReservation.Rejected)
        assertEquals(TargetOutcome.NothingToRemove, s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, user))
        assertTrue("a named user epoch", s.reserveWrite(components(epoch = "e1", krx = null)) is GraphV2WriteReservation.Rejected)
        assertEquals(TargetOutcome.NothingToRemove, s.purge(GraphV2DiskComponent.KRX, PurgeScope.CAPABILITY, ns("u1", "e2", "k2", null, "k1", PurgeScope.CAPABILITY)))
        listOf("e2", "e3").forEach {
            assertTrue("a named capability epoch under user epoch $it", s.reserveWrite(components(epoch = it, krx = "k1")) is GraphV2WriteReservation.Rejected)
        }
        for ((u, k) in listOf("e2" to "k2", "e3" to "k2", "e2" to "k3", "e3" to null)) {
            assertTrue("($u,$k) is open", s.reserveWrite(components(epoch = u, krx = k, response = "r-$u-$k")) is GraphV2WriteReservation.Reserved)
        }
        assertTrue("another owner is untouched", s.reserveWrite(components(uid = "u2", epoch = "e1", krx = "k1")) is GraphV2WriteReservation.Reserved)
    }

    /** A store that starts over an earlier process's files retires the past namespaces it finds while purging them. */
    @Test fun B14f_aFreshStoreRetiresWhatItFindsOnDisk() = runBlocking {
        val earlier = store()
        val past = listOf(components(epoch = "e1", tab = "usd"), components(epoch = "e1", tab = "jpy", period = GraphPeriod.ONE_YEAR))
        val live = components(epoch = "e2")
        val other = components(uid = "u2", epoch = "e1")
        (past + live + other).forEach { earlier.save(it) }
        val s = store()
        assertEquals(TargetOutcome.Removed, s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, ns("u1", "e2", null, null, null, PurgeScope.USER)))
        past.forEach { assertNull("past general ${it.general.key}", s.general(it)) }
        assertEquals(1390.0, s.general(live))
        assertEquals(1390.0, s.general(other))
        assertTrue("a found past epoch is not reopened", s.reserveWrite(components(epoch = "e1", tab = "eur", krx = null)) is GraphV2WriteReservation.Rejected)
        assertTrue("the live epoch is open", s.reserveWrite(components(epoch = "e2", krx = null, response = "r2")) is GraphV2WriteReservation.Reserved)
        assertTrue("another owner is open", s.reserveWrite(components(uid = "u2", epoch = "e1", krx = null, response = "r2")) is GraphV2WriteReservation.Reserved)
    }

    /** A purge that fails or is cancelled keeps what it retired before it waited, and never closes a later epoch. */
    @Test fun B14g_aFailedOrCancelledPurgeKeepsItsRetirementsOnly() = runBlocking {
        val f = Files()
        val s = store(f)
        s.save(components(epoch = "e1", krx = null))
        f.enumerateFailure = IOException("listing failed")
        assertTrue("listing failure", s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, ns("u1", "e2", null, null, null, PurgeScope.USER)) is TargetOutcome.Failed)
        assertTrue("retired before the failure", s.reserveWrite(components(epoch = "e1", krx = null, response = "r2")) is GraphV2WriteReservation.Rejected)
        assertTrue("a later epoch is open after a failure", s.reserveWrite(components(epoch = "e3", krx = null)) is GraphV2WriteReservation.Reserved)
        f.enumerateFailure = CancellationException("caller left")
        try {
            s.purge(GraphV2DiskComponent.GENERAL, PurgeScope.USER, ns("u1", "e4", null, null, null, PurgeScope.USER))
            fail("cancellation was swallowed")
        } catch (expected: CancellationException) {
            assertEquals("caller left", expected.message)
        }
        f.enumerateFailure = null
        assertTrue("retired before the cancellation", s.reserveWrite(components(epoch = "e3", krx = null, response = "r3")) is GraphV2WriteReservation.Rejected)
        assertTrue("a later epoch is open after a cancellation", s.reserveWrite(components(epoch = "e5", krx = null)) is GraphV2WriteReservation.Reserved)
    }

    /** What a purge finds is retired at its axis's unit: a whole past user epoch on USER, the exact tuple on CAPABILITY; only the entry's owner. */
    @Test fun B14h_foundNamespacesAreRetiredAtTheirAxisUnitForTheirOwnerOnly() = runBlocking {
        val s = store()
        val otherOwner = components(uid = "u2", epoch = "e0", krx = "k1")
        listOf(components(epoch = "e0", krx = "k2"), components(epoch = "e2", krx = "k1"), otherOwner).forEach { s.save(it) }
        // A capability entry whose epoch was lost: it retires only what it finds, as exact tuples.
        assertEquals(TargetOutcome.Removed, s.purge(GraphV2DiskComponent.KRX, PurgeScope.CAPABILITY, ns("u1", "e2", "k2", null, null, PurgeScope.CAPABILITY)))
        assertTrue("a found tuple", s.reserveWrite(components(epoch = "e2", krx = "k1", response = "r2")) is GraphV2WriteReservation.Rejected)
        assertTrue("a found tuple's capability epoch is not closed for the live user epoch",
            s.reserveWrite(components(epoch = "e2", krx = "k2", response = "r3")) is GraphV2WriteReservation.Reserved)
        // A user entry whose epoch was lost, on the KRX half only: the found past user epoch is closed under every capability epoch.
        assertFalse(s.purge(GraphV2DiskComponent.KRX, PurgeScope.USER, ns("u1", "e2", "k2", null, null, PurgeScope.USER)) is TargetOutcome.Failed)
        assertTrue("a found past user epoch under another capability epoch",
            s.reserveWrite(components(epoch = "e0", krx = "k9", response = "r4")) is GraphV2WriteReservation.Rejected)
        assertTrue("another owner's known namespace is untouched",
            s.reserveWrite(components(uid = "u2", epoch = "e0", krx = "k1", response = "r5")) is GraphV2WriteReservation.Reserved)
        assertEquals(1391.0, s.krx(otherOwner))
    }

    // --- B13-d the manifest and the unwired adapter -----------------------------------------------------------

    /** Two derived targets owned by S4; unregistered they defer, registered they complete; an adapter refuses another target. */
    @Test fun B13d_theManifestTargetsAndTheirAdapter() = runBlocking {
        assertEquals(PurgeClassification.DERIVED_HERE, GENERAL_TARGET.classification)
        assertEquals(setOf(PurgeScope.USER), GENERAL_TARGET.scopes)
        assertEquals("S4", GENERAL_TARGET.owner)
        assertEquals(PurgeClassification.DERIVED_HERE, KRX_TARGET.classification)
        assertEquals(setOf(PurgeScope.USER, PurgeScope.CAPABILITY), KRX_TARGET.scopes)
        assertEquals("S4", KRX_TARGET.owner)

        val s = store()
        val old = components(epoch = "e1", krx = "k1")
        val live = components(epoch = "e2", krx = "k2")
        listOf(old, live).forEach { s.save(it) }
        val manifest = listOf(GENERAL_TARGET, KRX_TARGET)
        val user = ns("u1", "e2", "k2", "e1", null, PurgeScope.USER)

        assertTrue("unregistered: Deferred", ManifestScopePurger(emptyMap(), manifest).purgeUserScope(user) is PurgeResult.Deferred)
        assertEquals("unregistered: nothing deleted", 1390.0, s.general(old))

        val generalAdapter = GraphV2PurgeAdapter(s, GraphV2DiskComponent.GENERAL)
        val adapters = mapOf(GENERAL_TARGET.id to generalAdapter, KRX_TARGET.id to GraphV2PurgeAdapter(s, GraphV2DiskComponent.KRX))
        assertTrue("an adapter answers for its own target only",
            generalAdapter.purge(PurgeRequest(KRX_TARGET, PurgeScope.USER, PurgeCause.IDENTITY_SWITCH, user)) is TargetOutcome.Failed)
        assertEquals("refused request deleted nothing", 1391.0, s.krx(old))
        assertTrue("another id with the same classification and scopes",
            generalAdapter.purge(PurgeRequest(GENERAL_TARGET.copy(id = "file:graph_v2_other"), PurgeScope.USER, PurgeCause.IDENTITY_SWITCH, user)) is TargetOutcome.Failed)
        assertTrue("a scope outside the target",
            generalAdapter.purge(PurgeRequest(GENERAL_TARGET, PurgeScope.CAPABILITY, PurgeCause.IDENTITY_SWITCH,
                ns("u1", "e2", "k2", "e2", "k1", PurgeScope.CAPABILITY))) is TargetOutcome.Failed)
        assertEquals("refused requests deleted nothing", 1390.0, s.general(old))

        assertEquals(PurgeResult.Completed, ManifestScopePurger(adapters, manifest).purgeUserScope(user))
        assertNull(s.general(old))
        assertNull(s.krx(old))
        assertEquals(1390.0, s.general(live))
        assertEquals(1391.0, s.krx(live))

        val oldCapability = components(epoch = "e2", krx = "k1", tab = "jpy")
        s.save(oldCapability)
        val capability = ns("u1", "e2", "k2", "e2", "k1", PurgeScope.CAPABILITY)
        assertEquals(PurgeResult.Completed, ManifestScopePurger(adapters, manifest).purgeCapabilityScope(capability))
        assertNull(s.krx(oldCapability))
        assertEquals("capability leaves the general half", 1390.0, s.general(oldCapability))
        assertEquals(1391.0, s.krx(live))
    }

    // --- atomic replacement -----------------------------------------------------------------------------------

    /** A replacement that fails before publishing leaves the previous file byte for byte; a good one replaces it whole. */
    @Test fun AtomicA_aFailedReplacementKeepsThePreviousFile() = runBlocking {
        val f = Files()
        val s = store(f)
        s.save(components(response = "r0", rate = 1300.0))
        val generalFile = files().single { !isKrx(it) }
        val before = generalFile.readBytes()
        val krxFile = files().single { isKrx(it) }
        val krxBefore = krxFile.readBytes()

        f.failPrepare = { !isKrx(it) }
        val prepareFailed = s.save(components(response = "r1", rate = 1390.0))
        failed("prepare", prepareFailed.general)
        skipped("krx after a failed general prepare", prepareFailed.krx)
        assertArrayEquals(before, generalFile.readBytes())
        assertArrayEquals(krxBefore, krxFile.readBytes())
        assertEquals("the two files only, no temporary left", 2, files().size)
        f.failPrepare = { false }

        f.failPublish = { !isKrx(it) }
        val publishFailed = s.save(components(response = "r2", rate = 1400.0))
        failed("publish", publishFailed.general)
        skipped("krx after a failed general publish", publishFailed.krx)
        assertArrayEquals(before, generalFile.readBytes())
        assertArrayEquals(krxBefore, krxFile.readBytes())
        assertEquals("the two files only, no temporary left", 2, files().size)
        f.failPublish = { false }

        assertEquals(GraphV2ComponentWriteOutcome.Replaced, s.save(components(response = "r3", rate = 1500.0)).general)
        assertEquals(1500.0, s.general(components()))
    }

    /** The general half replaced and the KRX half refused: the new general reads back, the old KRX half does not join it. */
    @Test fun AtomicB_aHalfReplacedPairRestoresTheGeneralOnly() = runBlocking {
        val f = Files()
        val s = store(f)
        s.save(components(response = "r1", rate = 1390.0))
        f.failPublish = { isKrx(it) }
        val w = s.save(components(response = "r2", rate = 1500.0))
        assertEquals(GraphV2ComponentWriteOutcome.Replaced, w.general)
        failed("krx", w.krx)
        f.failPublish = { false }

        val key = components()
        val general = (s.readGeneral(key.general.key, null, open) as GraphV2DiskRead.Found).envelope
        val krx = (s.readKrx(checkNotNull(key.krx).key, null, open) as GraphV2DiskRead.Found).envelope
        assertEquals("r2", general.responseId)
        assertEquals("r1", krx.responseId)
        val joined = joinGraphV2Components(general, krx)
        assertFalse(joined.krxJoined)
        assertEquals(listOf("hana.usd-krw"), joined.tab.graph.series.map { it.seriesId })
        assertEquals(1500.0, joined.tab.graph.series.single().points.single().rate, 0.0)
    }

    // --- admission is consulted at the file boundary ------------------------------------------------------------

    /** A closed admission touches no file: no read, no write. */
    @Test fun Admission_aClosedAdmissionTouchesNoFile() = runBlocking {
        val f = Files()
        val s = store(f)
        val c = components()
        s.save(c)
        f.reads.clear()
        f.prepares.clear()
        val closed = GraphV2IoAdmission { false }
        assertFalse(s.readGeneral(c.general.key, null, closed) is GraphV2DiskRead.Found)
        assertFalse(s.readKrx(checkNotNull(c.krx).key, null, closed) is GraphV2DiskRead.Found)
        val w = s.write(reserved(s, components(response = "r2", rate = 1500.0)), closed)
        assertFalse(w.general == GraphV2ComponentWriteOutcome.Replaced)
        assertEquals("a closed admission reads nothing", emptyList<File>(), f.reads.toList())
        assertEquals("a closed admission stages nothing", emptyList<File>(), f.prepares.toList())
        assertEquals(1390.0, s.general(c))
        val krxOnlyClosed = GraphV2IoAdmission { it == GraphV2DiskComponent.GENERAL }
        val w2 = s.write(reserved(s, components(response = "r3", rate = 1600.0)), krxOnlyClosed)
        assertEquals(GraphV2ComponentWriteOutcome.Replaced, w2.general)
        assertFalse(w2.krx == GraphV2ComponentWriteOutcome.Replaced)
        assertTrue("a closed KRX admission stages no KRX file", f.prepares.none { isKrx(it) })
        assertEquals(1391.0, s.krx(c))
    }

    // --- S4 RT01-B2b-2: withdrawing a reservation's KRX half ---------------------------------------------------------

    private fun reason(o: GraphV2ComponentWriteOutcome?) = (o as? GraphV2ComponentWriteOutcome.Skipped)?.reason

    /**
     * WK01 (S4 RT01-B2b-2): a reservation's valid KRX half is withdrawn once. Its GENERAL half, its place in the order and
     * the namespace stay: the write lands GENERAL alone, a newer reservation of the same namespace still writes KRX, another
     * open reservation in that namespace keeps its KRX half, and withdrawing the newest does not let an older one land. A
     * GENERAL-only, cancelled, written or unknown ticket answers false.
     */
    @Test fun WK01_aWithdrawnKrxHalfLeavesTheGeneralHalfTheOrderAndTheNamespace() = runBlocking {
        val s = store()
        val c = components(rate = 1390.0)
        val t = reserved(s, c)
        assertTrue(s.withdrawKrx(t))
        assertFalse("once", s.withdrawKrx(t))
        val w = s.write(t, open)
        assertEquals(GraphV2ComponentWriteOutcome.Replaced, w.general)
        assertEquals("Graph namespace is retired", reason(w.krx))
        assertEquals(1390.0, s.general(c))
        assertNull(s.krx(c))
        assertFalse("GENERAL only", s.withdrawKrx(reserved(s, components(krx = null, response = "g"))))
        val cancelled = reserved(s, components(response = "x"))
        s.cancelWrite(cancelled)
        assertFalse("cancelled", s.withdrawKrx(cancelled))
        assertFalse("unknown", s.withdrawKrx(GraphV2WriteTicket()))
        val againTicket = reserved(s, components(response = "r3", rate = 1500.0))
        val again = s.write(againTicket, open)
        assertEquals("the namespace is not retired", GraphV2ComponentWriteOutcome.Replaced, again.krx)
        assertEquals(1501.0, s.krx(c))
        assertFalse("a written ticket", s.withdrawKrx(againTicket))

        val sibling = reserved(s, components(tab = "eur", rate = 1300.0))
        val kept = reserved(s, components(tab = "eur", period = GraphPeriod.ONE_YEAR, rate = 1400.0))
        assertTrue(s.withdrawKrx(sibling))
        assertEquals("the withdrawal is the ticket's alone", GraphV2ComponentWriteOutcome.Replaced, s.write(kept, open).krx)

        val older = reserved(s, components(tab = "jpy", rate = 1100.0))
        val newer = reserved(s, components(tab = "jpy", response = "n", rate = 1200.0))
        assertTrue(s.withdrawKrx(newer))
        skipped("the older still loses", s.write(older, open).general)
        assertEquals(GraphV2ComponentWriteOutcome.Replaced, s.write(newer, open).general)
        assertEquals(1200.0, s.general(components(tab = "jpy")))
        assertNull(s.krx(components(tab = "jpy")))
    }

    /**
     * WK02 (S4 RT01-B2b-2): withdrawn while its write is under way - claimed, its GENERAL half published and its KRX file
     * staged - the KRX half is stopped at the final check: nothing of it lands and no staged file stays.
     */
    @Test fun WK02_aWithdrawalBeforeTheFinalCheckStopsTheKrxPublish() = runBlocking {
        val f = Files().apply { holdPrepareOf = { isKrx(it) }; holdPrepare = CountDownLatch(1) }
        val s = store(f)
        val c = components()
        val t = reserved(s, c)
        val w = async(Dispatchers.IO) { s.write(t, open) }
        check(f.prepareHeld.await(10, TimeUnit.SECONDS))
        assertTrue("premise: the GENERAL half is published", f.published.none { isKrx(it) } && f.published.isNotEmpty())
        assertTrue("the claimed reservation's KRX half is withdrawn", s.withdrawKrx(t))
        val latch = checkNotNull(f.holdPrepare)
        f.holdPrepare = null
        latch.countDown()
        val report = withTimeout(10_000) { w.await() }
        assertEquals(GraphV2ComponentWriteOutcome.Replaced, report.general)
        assertEquals("Graph namespace is retired", reason(report.krx))
        assertNull(s.krx(c))
        assertTrue("no KRX file, staged or published", files().none { isKrx(it) })
    }

    /**
     * WK03 (S4 RT01-B2b-2): a KRX publish that already holds the store's lock finishes first; the withdrawal waits for it,
     * and the published file stays (removing it is the disk purge's).
     */
    @Test fun WK03_aWithdrawalWaitsForAPublishInProgress() = runBlocking {
        val f = Files().apply { holdPublishOf = { isKrx(it) }; holdPublish = CountDownLatch(1) }
        val s = store(f)
        val c = components()
        val t = reserved(s, c)
        val w = async(Dispatchers.IO) { s.write(t, open) }
        check(f.publishHeld.await(10, TimeUnit.SECONDS))
        val publishedAtReturn = java.util.concurrent.atomic.AtomicReference<Boolean>()
        val withdrawer = Thread { s.withdrawKrx(t); publishedAtReturn.set(f.published.any { isKrx(it) }) }
        withdrawer.start()
        withTimeout(10_000) { while (withdrawer.state != Thread.State.BLOCKED) delay(5) }
        val latch = checkNotNull(f.holdPublish)
        f.holdPublish = null
        latch.countDown()
        withdrawer.join(10_000)
        assertEquals("the withdrawal returned after the publish", true, publishedAtReturn.get())
        assertEquals(GraphV2ComponentWriteOutcome.Replaced, withTimeout(10_000) { w.await() }.krx)
        assertEquals(1391.0, s.krx(c))
    }
}
