package com.jay.fxi.data.entitlements.purge

import com.jay.fxi.data.entitlements.CapabilityScopePurger
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeResult
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.UserScopePurger
import kotlinx.coroutines.CancellationException

/** A purge that could not finish, named by the targets that refused. */
class PurgeTargetsFailedException(val targets: List<String>, cause: Throwable? = null) :
    Exception("purge failed for ${targets.joinToString()}", cause)

/**
 * The purger the journal calls: a manifest, the adapters registered for it, and honest arithmetic.
 *
 * **Completion is the whole point.** `PremiumAccessCoordinator.resumePendingPurgesLocked` drops a
 * journal entry when every scope of it answered [PurgeResult.Completed], so a purger that reports
 * success for work it did not do erases the only record that the work is owed. That is why an
 * target this axis must delete but nothing is registered for contributes an outstanding
 * obligation: "nothing was registered to delete it" is not "there was nothing to delete". An axis
 * the manifest gives no applicable target may still complete, and a failure outranks a deferral.
 *
 * What it may delete is decided by [PurgeDecision] from the manifest, the axis and the cause, and
 * nothing here re-decides it. Targets another owner must clear — a slice's cutover, the push path,
 * S10's compatibility check — count as outstanding, so the entry stays owed until those land.
 *
 * Not wired into production in this slice: `EntitlementsModule` still provides the placeholder
 * (purger 설계 v3 final §9, P1). [causeOf] reads a cause the journal cannot carry yet, so it
 * answers [PurgeCause.UNKNOWN] until P2's codec, and [authorizationFor] has no minter until P3.
 */
class ManifestScopePurger(
    private val adapters: Map<String, PurgeTargetAdapter>,
    private val manifest: List<PurgeTarget> = PurgeManifest.TARGETS,
    private val causeOf: (PendingPurge) -> PurgeCause = { PurgeCause.UNKNOWN },
    private val authorizationFor: (PurgeNamespace) -> DeletionAuthorization? = { null }
) : UserScopePurger, CapabilityScopePurger {

    override suspend fun purgeUserScope(namespace: PurgeNamespace): PurgeResult =
        run(PurgeScope.USER, namespace)

    override suspend fun purgeCapabilityScope(namespace: PurgeNamespace): PurgeResult =
        run(PurgeScope.CAPABILITY, namespace)

    private suspend fun run(scope: PurgeScope, namespace: PurgeNamespace): PurgeResult {
        val cause = causeOf(namespace.pending)
        val authorization = authorizationFor(namespace)
        val failed = mutableListOf<String>()
        var firstFailure: Throwable? = null
        val outstanding = mutableListOf<String>()

        manifest.forEach { target ->
            when (PurgeDecision.disposition(target, scope, cause, namespace, authorization)) {
                TargetDisposition.NOT_APPLICABLE -> Unit
                TargetDisposition.OUTSTANDING -> outstanding += "${target.id}(${target.owner})"
                TargetDisposition.DELETE_NOW -> {
                    val adapter = adapters[target.id]
                    if (adapter == null) {
                        // Registered in the manifest, not registered to be deleted. The obligation
                        // is real and unmet, which is a deferral rather than a failure.
                        outstanding += "${target.id}(no adapter)"
                        return@forEach
                    }
                    val outcome = try {
                        adapter.purge(PurgeRequest(target, scope, cause, namespace))
                    } catch (cancelled: CancellationException) {
                        // The caller is going away; the obligation survives untouched.
                        throw cancelled
                    } catch (thrown: Throwable) {
                        TargetOutcome.Failed(thrown.message ?: thrown::class.java.name, thrown)
                    }
                    when (outcome) {
                        TargetOutcome.Removed, TargetOutcome.NothingToRemove -> Unit
                        is TargetOutcome.Failed -> {
                            failed += "${target.id}: ${outcome.reason}"
                            if (firstFailure == null) firstFailure = outcome.cause
                        }
                    }
                }
            }
        }

        return when {
            // A failure is reported even when other targets are also outstanding: the journal is
            // kept either way, and the louder answer is the one that says something went wrong.
            failed.isNotEmpty() -> PurgeResult.Failed(PurgeTargetsFailedException(failed, firstFailure))
            outstanding.isNotEmpty() -> PurgeResult.Deferred("still owed: ${outstanding.joinToString()}")
            else -> PurgeResult.Completed
        }
    }
}
