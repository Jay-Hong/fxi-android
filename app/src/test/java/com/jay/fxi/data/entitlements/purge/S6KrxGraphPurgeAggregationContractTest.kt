package com.jay.fxi.data.entitlements.purge

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.graph.*
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.setField
import com.jay.fxi.data.remote.S6KrxGraphSessionRig.Companion.AT
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class S6KrxGraphPurgeAggregationContractTest {
    @Test fun m05_fakeDiskAndMemoryAggregateTruthfully() {
        for (memory in listOf("Removed", "Nothing", "Failed", "unknown"))
            for (disk in listOf("Removed", "Nothing", "Failed", "missing", "Failed+missing"))
                s63b2Test("M05", "$memory-$disk") { p, _ ->
                    val g = p.g; g.start()
                    val original = if (memory == "Nothing") p.need(g.session.inputs.filterIsInstance<com.jay.fxi.data.remote.TopicGraphInput.Continuity>()
                        .lastOrNull { it.authority.krxGraphStamp != null }?.authority?.krxGraphStamp, "actual ACK-only CAP original")
                    else g.adopt().krxGraphStamp!!
                    val coordinator = g.readyCoordinator()
                    g.session.capEnd()
                    var namespace = p.namespace(original)
                    if (memory == "Failed") namespace = namespace.copy(ownerUid = "foreign")
                    if (memory == "unknown") namespace = namespace.copy(currentKrxCapabilityEpoch = null)
                    val target = p.memoryTarget
                    p.eq("real memory target participates on CAP axis", setOf(PurgeScope.USER, PurgeScope.CAPABILITY), target.scopes)
                    val actualAdapter = p.adapter(coordinator)
                    var memoryCalls = 0
                    var memoryAnswer: TargetOutcome? = null
                    val memoryAdapter = PurgeTargetAdapter { request ->
                        memoryCalls++; actualAdapter.purge(request).also { memoryAnswer = it }
                    }
                    val diskTarget = p.need(PurgeManifest.byId("file:graph_v2_krx"), "KRX disk manifest target")
                    // Fake disk inventory only. No production store or filesystem I/O.
                    val sentinel = mutableSetOf("old", "current", "foreign", "GENERAL")
                    if (disk == "Nothing") sentinel.remove("old")
                    var diskCalls = 0
                    val fakeDisk = PurgeTargetAdapter {
                        diskCalls++
                        if (disk.startsWith("Failed")) TargetOutcome.Failed("boundary-input: fake disk refusal")
                        else if (sentinel.remove("old")) TargetOutcome.Removed else TargetOutcome.NothingToRemove
                    }
                    val adapters = mutableMapOf(target.id to memoryAdapter)
                    if (disk != "missing") adapters[diskTarget.id] = fakeDisk
                    val fixture = listOf(target, diskTarget) + if (disk == "Failed+missing") listOf(
                        diskTarget.copy(id = "fake:missing-second-krx-disk")) else emptyList()
                    val result = ManifestScopePurger(adapters, fixture).purgeCapabilityScope(namespace)
                    p.eq("memory adapter really visited once", 1, memoryCalls)
                    when (memory) {
                        "Removed" -> p.eq("real memory removed its own payload", TargetOutcome.Removed, memoryAnswer)
                        "Nothing" -> p.eq("real empty memory answers only for itself", TargetOutcome.NothingToRemove, memoryAnswer)
                        else -> p.yes("real memory unknown/refusal is Failed", memoryAnswer is TargetOutcome.Failed)
                    }
                    p.eq("fake disk visitation is explicit", if (disk == "missing") 0 else 1, diskCalls)
                    when {
                        memory in setOf("Failed", "unknown") || disk.startsWith("Failed") ->
                            p.yes("failure outranks missing/deferred", result is PurgeResult.Failed)
                        disk == "missing" -> p.yes("missing fake disk remains Deferred", result is PurgeResult.Deferred)
                        else -> p.eq("only all fixture targets complete", PurgeResult.Completed, result)
                    }
                    p.yes("disk current, foreign and GENERAL sentinels survive", setOf("current", "foreign", "GENERAL").all { it in sentinel })
                    if (memory == "Removed" && result !is PurgeResult.Failed) p.rawRemoved(original)
                }
    }

    @Test fun m06_journalAndControlRecordsSurviveIncompletePurge() = s63b2Test("M06", "D1", "withheld", "Deferred", "Failed", "Completed") { p, col ->
        val g = p.g
        val memory = p.memoryTarget.copy(scopes = setOf(PurgeScope.USER, PurgeScope.CAPABILITY))
        val disk = p.need(PurgeManifest.byId("file:graph_v2_krx"), "disk fixture target")
        val visits = mutableListOf<String>()
        val adapters = mutableMapOf(memory.id to PurgeTargetAdapter { visits += "memory"; TargetOutcome.Removed })
        if (col == "Failed") adapters[disk.id] = PurgeTargetAdapter { visits += "disk"; TargetOutcome.Failed("boundary-input: fake disk failure") }
        if (col == "Completed") adapters[disk.id] = PurgeTargetAdapter { visits += "disk"; TargetOutcome.NothingToRemove }
        val purger = ManifestScopePurger(adapters, listOf(memory, disk))
        setField(g.r.pac, "capabilityPurger", purger)
        g.start(); val old = g.adopt(); g.general()
        val control = g.r.h.store.record
        val raw = g.raw
        val original = p.need(old.krxGraphStamp, "actual raw K1 before PAC-only fake-target exercise")
        p.yes("raw payload is positively held independently of fake memory result", original in raw.krxSeriesOwners.values && g.krxKey in raw.series)
        val general = g.generalPrint()
        val floor = raw.versionFloor
        val visitsBefore = visits.toList()
        if (col == "D1" || col == "withheld") {
            if (col == "D1") g.session.d1() else g.session.cleanup.hold()
            p.eq("D1/WITHHELD does not start whole namespace purge", visitsBefore, visits)
            p.eq("D1/WITHHELD preserves persisted control/journal", control, g.r.h.store.record)
            p.eq("USER floor remains unchanged", floor, g.raw.versionFloor)
        } else {
            g.session.capEnd()
            p.yes("actual PAC visited fake memory target", "memory" in visits)
            val current = g.r.h.store.record
            p.eq("CAP purge preserves USER epoch", control.userAccessEpoch, current.userAccessEpoch)
            p.eq("CAP purge preserves owner", control.ownerUid, current.ownerUid)
            val retired = old.krxGraphStamp!!.scope.use.krxCapabilityEpoch
            if (col == "Completed") {
                p.yes("all-target completion settles the selected journal entry", current.pendingPurges.none { it.krxCapabilityEpoch == retired })
            } else {
                val pending = p.need(current.pendingPurges.singleOrNull { it.krxCapabilityEpoch == retired }, "incomplete fake disk keeps exact journal obligation")
                p.yes("memory success does not settle incomplete scope", PurgeScope.CAPABILITY in pending.scopes)
                // Public journal-resume input, not a repeated stable-false answer (PAC:1562-1572).
                adapters[disk.id] = PurgeTargetAdapter { visits += "disk-completed"; TargetOutcome.Removed }
                p.yes("journal is still positively held before explicit resume", pending in g.r.h.store.record.pendingPurges)
                val resumeCalls = visits.size
                val beforeResume = g.r.h.store.record
                g.r.pac.resumePendingPurges(); g.r.drain()
                p.yes("public resume actually revisits outstanding targets", visits.size > resumeCalls)
                p.yes("only subsequent all-target completion settles the obligation", g.r.h.store.record.pendingPurges.none { it == pending })
                p.yes("fake disk really completed before settlement", "disk-completed" in visits)
                p.eq("resume only removes the completed obligation from raw control record", beforeResume.copy(
                    pendingPurges = beforeResume.pendingPurges.filterNot { it == pending }), g.r.h.store.record)
            }
        }
        // PAC-only aggregate fixture deliberately has fake memory targets; no physical-delete claim.
        p.yes("fake memory success does not fabricate physical REC removal", original in g.raw.krxSeriesOwners.values && g.krxKey in g.raw.series)
        p.eq("raw K1 data survives this PAC-only fake-target fixture", g.print(raw.series.getValue(g.krxKey)), g.print(g.raw.series.getValue(g.krxKey)))
        p.unchangedGeneral(general, floor)
    }
}
