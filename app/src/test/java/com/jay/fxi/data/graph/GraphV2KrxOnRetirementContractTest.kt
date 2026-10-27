package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.graph.KrxBetaGraphData.KEY
import com.jay.fxi.data.graph.KrxBetaGraphData.OTHER
import com.jay.fxi.data.graph.KrxBetaGraphData.JPY
import com.jay.fxi.data.graph.KrxBetaGraphData.ONLINE
import com.jay.fxi.data.graph.KrxBetaGraphData.KRX_SERIES
import com.jay.fxi.data.graph.KrxBetaGraphData.onlineDto
import com.jay.fxi.data.graph.KrxBetaGraphData.ok
import com.jay.fxi.data.graph.KrxBetaGraphData.status
import com.jay.fxi.data.graph.KrxBetaGraphData.serverTab
import com.jay.fxi.data.graph.KrxBetaGraphData.joined
import com.jay.fxi.data.graph.KrxBetaGraphData.put
import com.jay.fxi.data.graph.KrxBetaGraphData.putGeneral
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.TopicRejectionReason
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** β r2 §5: R01–R15. R04/R14 alone use S-1; every other row reads a real checked ON PAC. */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphV2KrxOnRetirementContractTest {
    private val evidenceRoot = File(if (File("app/src/test").isDirectory) ".beta_evidence/b1b/tmp" else "../.beta_evidence/b1b/tmp").apply { mkdirs() }
    @get:Rule val folder = TemporaryFolder(evidenceRoot)
    private val graphs = mutableListOf<KrxBetaGraphRig>()
    private val pacs = mutableListOf<KrxBetaGraphPac>()
    private var row = "R01"
    private fun c(message: String) = "contract($row): $message"
    private fun trial(id: String, body: suspend TestScope.() -> Unit) = runTest(timeout = 30.seconds) {
        row = id
        var failure: Throwable? = null
        try {
            body()
            graphs.forEach { assertEquals(c("no event failures"), emptyList<Throwable>(), it.failures) }
        } catch (t: Throwable) { failure = t; throw t }
        finally {
            graphs.forEach { it.close() }
            var endFailure = failure
            for (pac in pacs) try { pac.finish() } catch (t: Throwable) {
                if (endFailure == null) endFailure = t else endFailure.addSuppressed(t)
            }
            if (failure == null) endFailure?.let { throw it }
        }
    }
    private suspend fun TestScope.real(withWrites: Boolean = false): Pair<KrxBetaGraphPac, KrxBetaGraphRig> {
        val p = KrxBetaGraphPac(this, row).also { pacs += it }; p.open()
        val g = KrxBetaGraphRig(this, folder.newFolder(), p.snapshot, p.fence, row, { p.snapshot }, writePorts = if (!withWrites) null else GraphV2WritePorts(
            IssuerGraphWritePreparation(p.c::markGraphData),
            onPreparationBlocked = { error(c("write preparation blocked: $it")) },
            onWriteDiagnostic = { error(c("write diagnostic: $it")) }
        )).also { graphs += it }
        return p to g
    }
    private fun TestScope.hand(initial: TopicAccessSnapshot = a1Snap(userInvalidations = 3, capabilityInvalidations = 0, krxPermitSeq = 1)) =
        KrxBetaGraphRig(this, folder.newFolder(), initial, KrxHandSnapshots.FENCE7, row).also { graphs += it }
    private fun TestScope.step(ms: Long) { advanceTimeBy(ms); runCurrent() }
    private fun TestScope.notice(g: KrxBetaGraphRig) { g.coordinator.onContextChanged(); runCurrent() }
    private fun GraphEntry.generalOnly() = copy(tab = tab.copy(
        graph = tab.graph.copy(series = tab.graph.series.filterNot { it.seriesId.startsWith("krx.") }),
        inProgress = tab.inProgress.filterKeys { !it.startsWith("krx.") }))
    private fun epoch(p: KrxBetaGraphPac) = checkNotNull(p.store.record.krxCapabilityEpoch) { c("actual epoch") }
    private fun removed(g: KrxBetaGraphRig, old: String, live: String) {
        assertEquals(c("candidate set and selected retirement"), setOf(g.scope(live), g.scope(old)) to GraphRuntimeRetirement.REMOVED,
            g.retire { it == g.scope(old) })
    }
    private fun TestScope.request(g: KrxBetaGraphRig): KrxBetaGraphData.Sent {
        g.coordinator.onRefreshRequested(force = true); runCurrent()
        return g.sent.last()
    }
    private fun TestScope.parkOther(g: KrxBetaGraphRig): KrxBetaGraphData.Sent {
        g.coordinator.onActivated(OTHER); runCurrent()
        val old = g.sent.last()
        assertTrue("premise: $row K1 request for other key outstanding", old.key == OTHER && !old.tab.isCompleted)
        g.coordinator.onActivated(KEY); runCurrent()
        return old
    }
    @Test fun R01_oldValid200KeepsGeneralAndUsesCompletionConfigurationForFreshness() = trial("R01") {
        for (reopen in listOf(false, true)) {
            val (p, g) = real(); g.adopt(this, onlineDto(1500.0)); val old = request(g)
            val oldUse = checkNotNull(g.uses.acquire(p.fence)); val before = p.snapshot
            p.rotate(); if (reopen) p.reopen(); notice(g)
            assertTrue(c("same old GENERAL use admitted"), g.uses.admits(oldUse)); p.sameGeneral(before)
            assertTrue("premise: R01 old 200 parked and no newer answer", !old.tab.isCompleted && g.rate() == 1500.0)
            old.tab.complete(ok(onlineDto(1600.0))); runCurrent()
            assertEquals(c("old valid GENERAL 1500 to 1600"), 1600.0, g.rate())
            assertEquals(c("no K1 in raw slot"), listOf(ONLINE), g.ids())
            assertEquals(c("no K1 in protected slot"), listOf(ONLINE), g.exposedIds())
            if (reopen) assertNull(c("open K2 cannot confirm K1 response"), g.entry().online200At)
            else assertNotNull(c("closed completion configuration confirms GENERAL"), g.entry().online200At)
        }
    }

    @Test fun R02_portStripsEveryKeyBeforeNoticeAndPreservesGeneralStampCatalogAndConfiguration() = trial("R02") {
        val (p, g) = real(); val k1 = epoch(p); g.tabs = listOf("usd", "jpy")
        g.adopt(this, onlineDto(1400.0, period = GraphPeriod.ONE_YEAR), OTHER)
        g.coordinator.onActivated(JPY); runCurrent(); g.sent.last().tab.complete(ok(onlineDto(1200.0, tab = "jpy"))); runCurrent()
        g.coordinator.onActivated(KEY); runCurrent(); g.sent.last().tab.complete(ok(onlineDto(1500.0, inProgress = true))); runCurrent()
        val before = g.state
        assertEquals("premise: R02 active current buckets", setOf(ONLINE, KRX_SERIES), g.entry().tab.inProgress.keys)
        val k2 = p.rotate()
        assertEquals(c("live K2 is refused even hidden"), setOf(g.scope(k2), g.scope(k1)) to GraphRuntimeRetirement.LIVE_SCOPE_SELECTED, g.retire { it == g.scope(k2) })
        assertEquals(c("live selection changes nothing"), before, g.state)
        removed(g, k1, k2)
        assertEquals(c("only KRX series and buckets removed across tabs/periods"), before.copy(entries = before.entries.mapValues { it.value.generalOnly() }), g.state)
        listOf(KEY, OTHER, JPY).forEach { key -> assertEquals(c("GENERAL reader remains $key"), listOf(ONLINE), g.exposedIds(key)) }
        assertEquals(c("only GENERAL bucket remains"), setOf(ONLINE), g.entry().tab.inProgress.keys)
        assertEquals(c("repeat retirement removes nothing"), setOf(g.scope(k2)) to GraphRuntimeRetirement.NOTHING_TO_REMOVE, g.retire { it == g.scope(k1) })
        p.reopen(); notice(g)
        listOf(KEY, OTHER, JPY).forEach { assertNull(c("notice still detects changed configuration $it"), g.entry(it).online200At) }
    }

    @Test fun R03_realUserHoldMakesNoContextWhileRetirementStillStripsOnlyKrx() = trial("R03") {
        val (p, g) = real(); val k1 = epoch(p); g.adopt(this, onlineDto(1500.0)); val before = g.entry()
        val k2 = p.rotate()
        // A genuine USER hold, without changing the confirmed user namespace or fabricating a snapshot.
        p.store.loadFailures = 1; p.store.loadSkipsBeforeFailure = 1
        p.source.next = { EntitlementsOutcome.StableInactive(false) }
        p.c.refresh(RefreshIntent.FORCE_PREMIUM)
        assertEquals("premise: R03 USER loss candidate application read failed", 0, p.store.loadFailures)
        p.store.loadFailures = 100 // Keep real candidate recovery unreadable through the no-context/port window.
        assertNull(c("real USER hold cannot acquire a context"), g.uses.acquire(p.fence))
        assertEquals(c("record K2 stays in same USER scope"), p.fence.userAccessEpoch, p.snapshot.facts.recordFence?.userAccessEpoch)
        notice(g)
        assertEquals("premise: R03 no-context configuration strip has not run", before, g.entry())
        removed(g, k1, k2)
        assertEquals(c("no-context port preserves GENERAL and stamp"), before.generalOnly(), g.entry())
    }

    @Test fun R04_unsetConfigurationUsesSlotsOwnEpochAndKeepsLiveK2() = trial("R04") {
        val g = hand(); var hooks = 0
        g.atGateRead(1) { hooks++; g.handSnapshot = a1Snap(userBlocks = setOf(TopicAccessBlock.NOT_GRANTED), userInvalidations = 3, capabilityInvalidations = 0, krxPermitSeq = 1) }
        g.atGateRead(2) { hooks++; g.handSnapshot = a1Snap(userInvalidations = 3, capabilityInvalidations = 0, krxPermitSeq = 1) }
        g.adopt(this, onlineDto(1500.0))
        assertEquals("premise: R04 both original gate-read hooks reached", 2, hooks)
        assertEquals("premise: R04 K1 slot adopted in unset window", listOf(ONLINE, KRX_SERIES), g.ids())
        val before = g.state
        g.handSnapshot = a1Snap(record = KrxHandSnapshots.RECORD_K2, issuedRecord = KrxHandSnapshots.RECORD_K1,
            capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED), userInvalidations = 3, capabilityInvalidations = 1, krxPermitSeq = 1)
        assertTrue(c("A1 same token standing"), g.snapshot.facts.tokenStanding)
        assertEquals(c("live K2 refused in unset configuration"), setOf(g.scope("K2"), g.scope("K1")) to GraphRuntimeRetirement.LIVE_SCOPE_SELECTED, g.retire { it == g.scope("K2") })
        assertEquals(c("live refusal atomic"), before, g.state)
        removed(g, "K1", "K2")
        assertEquals(c("slot K1 removed despite unset configuration"), before.copy(entries = before.entries.mapValues { it.value.generalOnly() }), g.state)
    }

    @Test fun R05_retiredFillEmptyExpiresWithoutPortSeedAndNormalStartRetriesGeneral() = trial("R05") {
        val (p, g) = real(); val k1 = epoch(p); val disk = g.componentsFor(serverTab(), k1); g.put(disk)
        g.store.hold[1] = CompletableDeferred(); g.start(); g.coordinator.onActivated(KEY); runCurrent()
        assertEquals("premise: R05 FillEmpty read parked", 1, g.store.generalReads.size)
        val k2 = p.rotate(); removed(g, k1, k2); runCurrent()
        assertEquals(c("port initiates no seed"), 1, g.store.generalReads.size)
        p.reopen()
        assertTrue("premise: R05 old seed still parked before release", !g.store.hold.getValue(1).isCompleted)
        g.store.release(1); runCurrent()
        assertNull(c("expired FillEmpty applies neither half"), g.state.entries[KEY])
        assertEquals(c("expired owner diagnostic"), listOf("Seed ownership expired"), g.seedReasons())
        g.coordinator.onRefreshRequested(); runCurrent()
        assertTrue(c("normal start retries seed"), g.store.generalReads.size > 1)
        assertEquals(c("new seed restores GENERAL only"), GraphEntry(joined(disk, false), null), g.state.entries[KEY])
        assertEquals(c("fresh K2 capture is allowed"), k2, g.gate.bind(p.fence, checkNotNull(g.uses.acquire(p.fence)))?.krxCapabilityEpoch)
    }

    @Test fun R06_selectedSupplementExpiresAndRepeatRetirementRemovesNothing() = trial("R06") {
        val (p, g) = real(); val k1 = epoch(p); val disk = g.componentsFor(serverTab(), k1); g.put(disk)
        p.hold(); g.start(); g.coordinator.onActivated(KEY); runCurrent()
        assertEquals(c("closed seed GENERAL only"), GraphEntry(joined(disk, false), null), g.state.entries[KEY])
        // H1′ release requires the recheck delay; no graph event is emitted until after actual PAC reopening.
        step(5_010); p.reopen(); g.store.hold[2] = CompletableDeferred(); notice(g)
        assertEquals("premise: R06 SupplementOccupied parked", 2, g.store.generalReads.size)
        val before = g.entry(); val k2 = p.rotate(); removed(g, k1, k2)
        assertTrue("premise: R06 supplement still parked", !g.store.hold.getValue(2).isCompleted)
        g.store.release(2); runCurrent()
        assertEquals(c("retired supplement preserves GENERAL"), before, g.entry())
        assertEquals(c("supplement owner expired"), listOf("Seed ownership expired"), g.seedReasons())
        assertEquals(c("repeat has live epoch only"), setOf(g.scope(k2)) to GraphRuntimeRetirement.NOTHING_TO_REMOVE, g.retire { it == g.scope(k1) })
    }

    @Test fun R07_seedAloneRemainsCandidateAfterHttpFailure() = trial("R07") {
        val (p, g) = real(); val k1 = epoch(p); g.put(g.componentsFor(serverTab(), k1))
        g.store.hold[1] = CompletableDeferred(); g.start(); g.coordinator.onActivated(KEY); runCurrent()
        g.sent.single().tab.complete(status(503)); runCurrent()
        assertTrue("premise: R07 HTTP registration ended, seed still parked", g.state.inFlight.isEmpty() && !g.store.hold.getValue(1).isCompleted)
        val k2 = p.rotate(); removed(g, k1, k2)
        g.store.release(1); runCurrent()
        assertNull(c("retired seed alone applies nothing"), g.state.entries[KEY])
        assertEquals(c("seed alone ownership expired"), listOf("Seed ownership expired"), g.seedReasons())
    }

    @Test fun R08_spentAttemptAloneIsSelectedExactlyOnce() = trial("R08") {
        val (p, g) = real(); val k1 = epoch(p); val disk = g.componentsFor(serverTab(), k1); g.putGeneral(disk.general)
        p.hold(); g.start(); g.coordinator.onActivated(KEY); runCurrent()
        step(5_010); p.reopen(); notice(g)
        assertEquals("premise: R08 supplement completed without KRX file", 2, g.store.generalReads.size)
        assertEquals(c("GENERAL remains only half"), GraphEntry(joined(disk, false), null), g.state.entries[KEY])
        // Clear K1 request ownership by completing its GENERAL-only HTTP before retirement.
        g.sent.single().tab.complete(ok(onlineDto(1500.0, krx = false))); runCurrent()
        assertTrue("premise: R08 no HTTP registration or KRX slot remains", g.state.inFlight.isEmpty() && KRX_SERIES !in g.ids())
        val k2 = p.rotate(); removed(g, k1, k2)
        assertEquals(c("spent attempt removed once"), setOf(g.scope(k2)) to GraphRuntimeRetirement.NOTHING_TO_REMOVE, g.retire { it == g.scope(k1) })
    }

    @Test fun R09_closedSeedOwnsNoRetirableKrxAndRestoresOnlyGeneralWithoutExpiry() = trial("R09") {
        val (p, g) = real(); val k1 = epoch(p)
        g.adopt(this, onlineDto(1400.0, krx = false, period = GraphPeriod.ONE_YEAR), OTHER)
        val disk = g.componentsFor(serverTab(), k1); g.put(disk)
        val otherDisk = KrxBetaGraphData.components(serverTab(1200.0).copy(period = GraphPeriod.ONE_YEAR),
            g.generalKey(OTHER), null, "R09-other-general")
        g.putGeneral(otherDisk.general)
        val generalFiles = g.root.walkTopDown().filter { it.isFile && it.relativeTo(g.root).path.startsWith("general/") }
            .associate { it.relativeTo(g.root).path to it.readBytes().toList() }
        assertEquals("premise: R09 two distinct GENERAL files exist", 2, generalFiles.size)
        p.hold()
        val closed = p.snapshot; val use = checkNotNull(g.uses.acquire(p.fence))
        val binding = checkNotNull(g.gate.bindWithConfiguration(p.fence, use))
        assertTrue("premise: R09 starts closed with record K1 and a null-K capture",
            !binding.configuration.allowed && binding.configuration.recordEpoch == k1 && binding.captured.krxCapabilityEpoch == null)
        val heldRead = g.store.generalReads.size + 1
        g.store.hold[heldRead] = CompletableDeferred()
        g.coordinator.onActivated(KEY); runCurrent()
        assertEquals("premise: R09 FillEmpty read reached", heldRead, g.store.generalReads.size)
        assertTrue("premise: R09 closed seed has found GENERAL and remains parked in an empty slot",
            g.store.generalReads.last() is GraphV2DiskRead.Found && g.store.generalKeys.last() == g.generalKey() &&
                !g.store.hold.getValue(heldRead).isCompleted && KEY !in g.state.entries && !g.snapshot.facts.capabilityAllowed)
        val k2 = p.rotate(); p.sameGeneral(closed)
        assertTrue("premise: R09 same use and GENERAL gate remain admitted while seed is parked",
            g.uses.admits(use) && g.gate.admits(binding.captured, GraphV2DiskComponent.GENERAL) &&
                !g.store.hold.getValue(heldRead).isCompleted && KEY !in g.state.entries)
        val before = g.state
        assertEquals(c("closed seed contributes no K1 candidate"), setOf(g.scope(k2)) to GraphRuntimeRetirement.NOTHING_TO_REMOVE,
            g.retire { it == g.scope(k1) })
        assertEquals(c("retirement preserves existing GENERAL state"), before, g.state)
        g.store.release(heldRead); runCurrent()
        assertEquals(c("closed seed is not an expired owner or a closed GENERAL gate"), emptyList<String>(), g.seedReasons())
        assertEquals(c("same-token/U seed restores only GENERAL"), GraphEntry(joined(disk, false), null), g.state.entries[KEY])
        assertEquals(c("protected exposure also contains only GENERAL"), g.entry(), g.exposed())
        assertFalse(c("no raw KRX restored"), KRX_SERIES in g.ids())
        assertFalse(c("no protected KRX restored"), KRX_SERIES in g.exposedIds())
        assertEquals(c("other GENERAL entry survives"), before.entries[OTHER], g.state.entries[OTHER])
        assertEquals(c("no other GENERAL file is purged or rewritten"), generalFiles,
            g.root.walkTopDown().filter { it.isFile && it.relativeTo(g.root).path.startsWith("general/") }
                .associate { it.relativeTo(g.root).path to it.readBytes().toList() })
    }

    @Test fun R10_closedSupplementAttemptOwnsNoRetirableKrx() = trial("R10") {
        val (p, g) = real(); val k1 = epoch(p); g.adopt(this, onlineDto(1500.0))
        p.hold(); notice(g)
        assertEquals("premise: R10 closed supplement attempted", 2, g.store.generalReads.size)
        assertEquals(c("GENERAL survives hold"), listOf(ONLINE), g.ids())
        val k2 = p.rotate(); val before = g.state
        assertEquals(c("closed attempt contributes no K1"), setOf(g.scope(k2)) to GraphRuntimeRetirement.NOTHING_TO_REMOVE, g.retire { it == g.scope(k1) })
        assertEquals(c("no GENERAL data purged"), before, g.state)
    }

    @Test fun R11_unselectedK2StateAndOwnersSurviveLiveRefusalThrowAndLateK1() = trial("R11") {
        val (p, g) = real(withWrites = true); val k1 = epoch(p)
        g.adopt(this, onlineDto(1500.0)); val old = parkOther(g)
        val k2 = p.rotate(); p.reopen()
        val heldRead = g.store.generalReads.size + 1
        g.store.hold[heldRead] = CompletableDeferred(); notice(g); step(3_000)
        assertEquals("premise: R11 K2 supplement parked on a real GENERAL file", heldRead, g.store.generalReads.size)
        assertTrue("premise: R11 held supplement read found the original response", g.store.generalReads.last() is GraphV2DiskRead.Found)
        g.sent.last().tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals(c("real fresh K2 half adopted"), listOf(ONLINE, KRX_SERIES), g.exposedIds())
        assertEquals(c("K2 answer durably writes both matching halves"), GraphV2WriteReport(
            GraphV2ComponentWriteOutcome.Replaced, GraphV2ComponentWriteOutcome.Replaced), g.store.writes.last())
        assertEquals(c("two real response reservations"), 2, g.store.reservations.size)
        val k2out = request(g); val before = g.state
        assertTrue("premise: R11 K1 and K2 requests both outstanding", !old.tab.isCompleted && !k2out.tab.isCompleted && k2out !== old)
        assertEquals(c("live refusal"), setOf(g.scope(k2), g.scope(k1)) to GraphRuntimeRetirement.LIVE_SCOPE_SELECTED, g.retire { true })
        assertEquals(c("live refusal leaves all state"), before, g.state)
        val boom = IllegalStateException("selector"); var calls = 0
        assertSame(c("selector exception propagates"), boom, assertThrows(IllegalStateException::class.java) {
            g.coordinator.retireCapabilities { if (++calls == 2) throw boom else it == g.scope(k1) }
        })
        assertEquals(c("throw is atomic"), before, g.state)
        removed(g, k1, k2); assertEquals(c("unselected K2 slot/stamp/request unchanged"), before, g.state)
        old.tab.complete(ok(onlineDto(1700.0, period = GraphPeriod.ONE_YEAR))); runCurrent()
        assertEquals(c("late K1 cannot lower K2 or synthesize halves"), before.entries[KEY], g.entry())
        assertEquals(c("late K1 keeps its own GENERAL only"), listOf(ONLINE), g.ids(OTHER))
        g.store.release(heldRead); runCurrent()
        assertEquals(c("K2 seed owner/configuration survive; newer response forbids half composition"),
            listOf("Supplement response ID does not match"), g.seedReasons())
        assertEquals(c("supplement cannot replace the newer K2 slot"), before.entries[KEY], g.entry())
        k2out.tab.complete(ok(onlineDto(1800.0))); runCurrent()
        assertEquals(c("K2 request retains its epoch"), listOf(ONLINE, KRX_SERIES), g.ids())
        assertEquals(c("new GENERAL answer"), 1800.0, g.rate())
        assertNotNull(c("K2 confirms stamp"), g.entry().online200At)
    }

    @Test fun R12_twoRealRotationsKeepUAndExactSelectionRemovesOnlyK2() = trial("R12") {
        val (p, g) = real(); val k1 = epoch(p); g.adopt(this, onlineDto(1500.0)); parkOther(g)
        val initial = p.snapshot; val k2 = p.rotate(); p.reopen(); notice(g); step(3_000)
        g.sent.last().tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals(c("K2 half before second rotation"), listOf(ONLINE, KRX_SERIES), g.exposedIds())
        val k3 = p.rotate(); p.sameGeneral(initial); val before = g.state
        val all = setOf(g.scope(k3), g.scope(k2), g.scope(k1))
        assertEquals(c("three exact candidates"), all to GraphRuntimeRetirement.NOTHING_TO_REMOVE, g.retire { false })
        val boom = IllegalStateException("after selection"); var calls = 0
        assertSame(c("throw after selecting ended epochs"), boom, assertThrows(IllegalStateException::class.java) {
            g.coordinator.retireCapabilities { if (++calls == all.size) throw boom else it != g.scope(k3) }
        })
        assertEquals(c("no mutation before throw"), before, g.state)
        assertEquals(c("K1 exact removal"), all to GraphRuntimeRetirement.REMOVED, g.retire { it == g.scope(k1) })
        assertEquals(c("K1 does not remove K2 half"), before, g.state)
        assertEquals(c("K2 exact removal"), setOf(g.scope(k3), g.scope(k2)) to GraphRuntimeRetirement.REMOVED, g.retire { it == g.scope(k2) })
        assertEquals(c("only K2 KRX removed"), before.copy(entries = before.entries.mapValues { it.value.generalOnly() }), g.state)
    }

    @Test fun R13_liveFailureAndOriginalColdRetryDeadlineSurviveK1Retirement() = trial("R13") {
        val (p, g) = real(); val k1 = epoch(p); g.adopt(this, onlineDto(1500.0)); val old = parkOther(g)
        val k2 = p.rotate(); p.reopen(); notice(g); step(3_000)
        assertTrue("premise: R13 actual K2 request is distinct", g.sent.last() !== old)
        g.sent.last().tab.complete(status(503)); runCurrent()
        val failed = g.state; assertTrue("premise: R13 actual 503 failure published", KEY in failed.failures)
        val times = g.tabTimes(KEY); step(1_000); removed(g, k1, k2)
        assertEquals(c("live failure unchanged"), failed, g.state)
        assertTrue("premise: R13 original retry deadline still ahead", KEY !in g.state.inFlight)
        step(1_999); assertEquals(c("no early retry"), times, g.tabTimes(KEY))
        step(1); assertEquals(c("one retry on original deadline"), times + 6_000L, g.tabTimes(KEY))
    }

    @Test fun R14_k2HoldRecheckKeepsOriginalThreeSecondDeadlineAcrossK1Retirement() = trial("R14") {
        val g = hand(); g.adopt(this, onlineDto(1500.0)); parkOther(g)
        val k2 = a1Snap(record = KrxHandSnapshots.RECORD_K2, issuedRecord = KrxHandSnapshots.RECORD_K1,
            userInvalidations = 3, capabilityInvalidations = 1, krxPermitSeq = 2)
        g.handSnapshot = a1Snap(record = KrxHandSnapshots.RECORD_K2, issuedRecord = KrxHandSnapshots.RECORD_K1,
            capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED), userInvalidations = 3, capabilityInvalidations = 1, krxPermitSeq = 1)
        g.flip(this, k2); step(3_000); g.sent.last().tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals(c("K2 adopted"), listOf(ONLINE, KRX_SERIES), g.exposedIds())
        g.flip(this, a1Snap(record = KrxHandSnapshots.RECORD_K2, issuedRecord = KrxHandSnapshots.RECORD_K1,
            capabilityBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE), userInvalidations = 3, capabilityInvalidations = 2, krxPermitSeq = 2))
        assertEquals(c("hold increases C while U stays"), 3L, g.snapshot.userInvalidations)
        val times = g.tabTimes(KEY); step(1_000); removed(g, "K1", "K2")
        assertTrue("premise: R14 hold recheck window before original deadline", KEY !in g.state.inFlight)
        step(1_999); assertEquals(c("not before original hold deadline"), times, g.tabTimes(KEY))
        step(1); assertEquals(c("hold recheck exactly once at original deadline"), times + 6_000L, g.tabTimes(KEY))
    }

    @Test fun R15_k2SupplementMatchesItsFileAndResponseWhileLateK1CannotRestoreKrx() = trial("R15") {
        val (p, g) = real(); val k1 = epoch(p)
        // Only a disk fixture predicts the deterministic next ID. The actual issuer epoch is asserted after rotation.
        val expectedK2 = p.nextEpochForDiskFixture
        val disk = g.componentsFor(serverTab(1300.0), expectedK2, "R15-response-K2"); g.put(disk)
        g.start(); g.coordinator.onActivated(KEY); runCurrent(); val old = g.sent.single()
        assertEquals(c("K1 seed reads matching GENERAL but cannot join K2"), GraphEntry(joined(disk, false), null), g.state.entries[KEY])
        g.store.hold[2] = CompletableDeferred()
        val k2 = p.rotate(); assertEquals(c("file names actual newly generated K2"), expectedK2, k2)
        p.reopen(); notice(g)
        assertEquals("premise: R15 K2 supplement parked", 2, g.store.generalReads.size)
        removed(g, k1, k2)
        assertTrue("premise: R15 K2 supplement still parked", !g.store.hold.getValue(2).isCompleted)
        g.store.release(2); runCurrent()
        assertEquals(c("matching K2 file joins same response ID"), GraphEntry(joined(disk), null), g.state.entries[KEY])
        assertEquals(c("unselected supplement owner remains valid"), emptyList<String>(), g.seedReasons())
        old.tab.complete(ok(onlineDto(1700.0))); runCurrent()
        assertEquals(c("late valid K1 keeps GENERAL"), 1700.0, g.rate())
        assertEquals(c("late K1 cannot restore raw KRX"), listOf(ONLINE), g.ids())
        assertEquals(c("late K1 cannot restore protected KRX"), listOf(ONLINE), g.exposedIds())
    }
}
