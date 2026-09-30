package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.purge.PurgeControlConfirmedRead

/** Why the runner stopped; none of these opens a protected entry. */
internal sealed interface RestartRunResult {
    /** The next step needs inputs the runner does not own (closure, binding, runtime, purger, server approval).
     * ReRead here means the re-read after a confirmed step was not confirmed. */
    data class Handoff(val step: RestartStep) : RestartRunResult
    /** A read or a step's storage was not confirmed. */
    data object Unconfirmed : RestartRunResult
    /** A step was confirmed but the re-read still decided the same step. */
    data class NoProgress(val step: RestartStep) : RestartRunResult
    /** The store refused the step without writing. */
    data class StepRefused(val step: RestartStep) : RestartRunResult
}

/** Runs one store-owned restart step, then confirms the next decision. */
internal class ControlRestartRunner(
    private val read: PurgeControlConfirmedRead,
    private val store: ControlRecordStore
) {
    suspend fun run(identity: RestartIdentity): RestartRunResult {
        val initial = read.read()
        if (initial is RestartRead.Unconfirmed) return RestartRunResult.Unconfirmed
        val step = ControlRestartModel.decide(initial, identity)
        val stopped = when (step) {
            RestartStep.LegacySchemaAbsentRecovery -> store.recoverSchemaAbsentLegacy().stop(step)
            RestartStep.SchemaUpgradeV1ToV2 -> store.upgradeControlSchemaV1ToV2().stop(step)
            RestartStep.ReclaimPreviousLifetimeEvidence -> when (store.reclaimPreviousLifetimeEvidence()) {
                is ControlEvidenceReclamationResult.Confirmed -> null
                is ControlEvidenceReclamationResult.RecoveryRequired,
                is ControlEvidenceReclamationResult.Rejected -> RestartRunResult.StepRefused(step)
                is ControlEvidenceReclamationResult.Unconfirmed -> RestartRunResult.Unconfirmed
            }
            else -> return RestartRunResult.Handoff(step)
        }
        if (stopped != null) return stopped

        val next = ControlRestartModel.decide(read.read(), identity)
        return if (next == step) RestartRunResult.NoProgress(next) else RestartRunResult.Handoff(next)
    }

    private fun ControlSchemaUpgradeResult.stop(step: RestartStep): RestartRunResult? = when (this) {
        is ControlSchemaUpgradeResult.Confirmed -> null
        is ControlSchemaUpgradeResult.RecoveryRequired -> RestartRunResult.StepRefused(step)
        is ControlSchemaUpgradeResult.Unconfirmed -> RestartRunResult.Unconfirmed
    }
}
