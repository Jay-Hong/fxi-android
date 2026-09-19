package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Synthetic in-memory state only: these helpers never claim owner-confirmed release or reclaim data. */
internal object ControlReleaseFixtures {
    fun fixture(): TrackedControlCommand {
        val command = CommandRef(UUID(0, 20).toString(), listOf(
            ControlMutation.Edit.prepare(ControlKind.DEMAND, node(ControlObligationFixtures.request)) {
                set("raisedAt", ControlScalar.Integer(5))
            },
            ControlMutation.Edit.prepare(ControlKind.RECOVERY_INTENT, node(ControlObligationFixtures.recovery)) {}
        ), OwnerTrackingLifetimeId.issue())
        return TrackedControlCommand(command).apply {
            confirmed.set(true)
            confirmationRequested.set(true)
            expectedApplied = row(command)
        }
    }

    fun row(command: CommandRef, id: String = command.id, lifetime: String = command.ownerTrackingLifetimeId.value,
        targets: List<AppliedTarget> = listOf(AppliedTarget(0, ControlKind.DEMAND, "d", false, true),
            AppliedTarget(1, ControlKind.RECOVERY_INTENT, "r", false, false))) =
        AppliedEvidence.Mutations(id, lifetime, targets)

    fun wire(row: AppliedEvidence.Mutations): String =
        """{"version":2,"commandId":"${row.commandId}","ownerTrackingLifetimeId":"${row.ownerTrackingLifetimeId}","kind":"MUTATIONS","targets":[${row.targets.joinToString(",") {
            """{"index":${it.index},"kind":"${it.kind}","id":"${it.id}","joined":${it.joined},"written":${it.written}}"""
        }}]}"""

    fun raw(evidence: String = "[]"): Preferences = ReclamationFixtures.raw(seals = "[]", evidence = evidence)
    fun read(evidence: String = "[]") = ControlRecordReader().read(raw(evidence))

    fun bind(tracked: TrackedControlCommand, descriptor: ReleasePendingDescriptor) =
        invoke(tracked, "bindReleaseDescriptor", arrayOf(ReleasePendingDescriptor::class.java), arrayOf(descriptor))

    fun pending(command: CommandRef) = invoke(command, "beginRelease")
    fun released(command: CommandRef) = invoke(command, "completeRelease")

    fun simulatePending(tracker: ControlCommandTracking, command: CommandRef,
        descriptor: ReleasePendingDescriptor = ReleasePendingDescriptor.ConfirmedWithoutApplied) {
        val tracked = checkNotNull(tracker.findPrepared(command))
        bind(tracked, descriptor)
        val work = tracker.recoverySnapshot()
        replaceRecovery(tracker, LocalRecoveryWork(work.unresolvedCommands, work.pendingReleases + command))
        pending(command)
    }

    fun simulateReleased(tracker: ControlCommandTracking, command: CommandRef) {
        val tracked = checkNotNull(tracker.findPrepared(command))
        released(command)
        val work = tracker.recoverySnapshot()
        replaceRecovery(tracker, LocalRecoveryWork(work.unresolvedCommands, work.pendingReleases - command))
        check(commands(tracker).remove(command.id, tracked))
    }

    @Suppress("UNCHECKED_CAST")
    fun replaceRecovery(tracker: ControlCommandTracking, work: LocalRecoveryWork) {
        val field = ControlCommandTracking::class.java.getDeclaredField("recoveryWork").apply { isAccessible = true }
        (field.get(tracker) as AtomicReference<LocalRecoveryWork>).set(work)
    }

    @Suppress("UNCHECKED_CAST")
    fun commands(tracker: ControlCommandTracking): ConcurrentHashMap<String, TrackedControlCommand> =
        ControlCommandTracking::class.java.getDeclaredField("commands").apply { isAccessible = true }.get(tracker)
            as ConcurrentHashMap<String, TrackedControlCommand>

    fun replaceLease(tracker: ControlCommandTracking, lease: MutableSet<CommandRef>) {
        ControlCommandTracking::class.java.getDeclaredField("executing").apply { isAccessible = true }.set(tracker, lease)
    }

    fun sameLifetimeText(lifetime: OwnerTrackingLifetimeId): OwnerTrackingLifetimeId =
        OwnerTrackingLifetimeId::class.java.getDeclaredConstructor(String::class.java).apply { isAccessible = true }
            .newInstance(lifetime.value)

    private fun invoke(target: Any, name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any> = emptyArray()) {
        try { target.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(target, *args) }
        catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
    }
}
