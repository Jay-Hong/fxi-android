package com.jay.fxi.e5djourney

import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.TopicAccessEnd
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import kotlinx.coroutines.runBlocking

/**
 * S7 E5d-J J2b2 (`e5dj_j2_plan.claude.r3.md` §C2; `s7e5dj_j2b2_design.codex.r1.md` D5): a read-only view of the production
 * access coordinator — the very singleton the app's consumers use, reached through the journey entry point — for G11's
 * "the free answer was processed" boundary. It reads only what the coordinator already exposes inside the module: the
 * published [PremiumAccessCoordinator.accessSnapshot], the owned [PremiumAccessCoordinator.state], and the mutex-guarded
 * [PremiumAccessCoordinator.recheckDiagnostics]. It installs no callback, makes no coordinator and changes nothing.
 *
 * One reading is the snapshot and owned state before, the diagnostics, and both again after; when anything moved in between
 * the reading is marked unstable and is no evidence of completion. The snapshot's identity binding and the diagnostics'
 * binding epoch are different axes and are reported apart. The diagnostics are read on the calling binder thread with
 * [runBlocking], which waits on the coordinator's mutex only — never on the main thread, a probe's end or a UI — and no record,
 * hook or identity lock is held while it waits. Token values are never read out.
 */
internal object E5dJourneyAccess {

    fun read(coordinator: PremiumAccessCoordinator): Map<String, Any?> {
        val snapshotBefore = coordinator.accessSnapshot
        val ownedBefore = coordinator.state.value
        val diagnostics = runBlocking { coordinator.recheckDiagnostics() }
        val snapshotAfter = coordinator.accessSnapshot
        val ownedAfter = coordinator.state.value
        return mapOf(
            "stable" to (snapshotBefore == snapshotAfter && ownedBefore == ownedAfter),
            "snapshotBefore" to snapshot(snapshotBefore),
            "snapshotAfter" to snapshot(snapshotAfter),
            "ownedBefore" to mapOf("uid" to ownedBefore.uid, "authGeneration" to ownedBefore.authGeneration, "state" to ownedBefore.state.toString()),
            "ownedAfter" to mapOf("uid" to ownedAfter.uid, "authGeneration" to ownedAfter.authGeneration, "state" to ownedAfter.state.toString()),
            "diagnostics" to mapOf(
                "bindingEpoch" to diagnostics.bindingEpoch,
                "registeredQueryCount" to diagnostics.registeredQueryCount,
                "inFlightOrders" to diagnostics.inFlightOrders,
                "owedIntent" to diagnostics.owedIntent?.name,
                "authStopped" to diagnostics.authStopped
            )
        )
    }

    private fun snapshot(snapshot: TopicAccessSnapshot): Map<String, Any?> = mapOf(
        "revision" to snapshot.revision,
        "binding" to identity(snapshot.facts.binding),
        "decisionGeneration" to snapshot.facts.decisionGeneration,
        "userAllowed" to snapshot.facts.userAllowed,
        "lastUserEnd" to end(snapshot.lastUserEnd)
    )

    private fun end(end: TopicAccessEnd?): Map<String, Any?>? =
        end?.let { mapOf("sequence" to it.sequence, "reason" to it.reason.name, "binding" to identity(it.binding)) }

    private fun identity(identity: EntitlementsIdentity?): Map<String, Any?>? =
        identity?.let { mapOf("uid" to it.ownerUid, "authGeneration" to it.authGeneration) }
}
