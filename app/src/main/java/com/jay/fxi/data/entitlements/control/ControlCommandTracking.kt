package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.math.BigInteger
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class TrackedControlCommand(val command: CommandRef) {
    val targets = AtomicReference<List<ControlCommandTarget?>>(List(command.actions.size) { null })
    val confirmationRequested = AtomicBoolean(false)
    val confirmed = AtomicBoolean(false)
    val observedApplied = AtomicBoolean(false)
    // Accessed only in the owner's synchronous decision, never at prepare or after return.
    var firstConfirmDiscontinuityCount: BigInteger? = null
        private set
    var expectedApplied: AppliedEvidence? = null
    fun bindFirstConfirm(count: BigInteger) {
        if (firstConfirmDiscontinuityCount == null) firstConfirmDiscontinuityCount = count
    }
}

/** Internal issuance is independent of command/epoch/origin generators. */
internal class OwnerTrackingLifetimeId private constructor(val value: String) {
    companion object {
        fun issue(): OwnerTrackingLifetimeId = OwnerTrackingLifetimeId(UUID.randomUUID().toString())
    }
}

/**
 * Metadata only. All file reads, writes, cache confirmation and serialization belong to the owner.
 * Prepared commands and adopted targets remain for the owner's entire tracking lifetime, including
 * after confirmation or rejection; forgetting either could turn a retry into a fresh command.
 * There is no per-command eviction in D2a. D2c must define an explicit release/retention contract
 * before pruning this potentially growing history, preserving unresolved references and fixed targets.
 */
internal class ControlCommandTracking private constructor() {
    val lifetimeId = OwnerTrackingLifetimeId.issue()
    var evidenceDiscontinuityCount: BigInteger = BigInteger.ZERO
        private set

    /** Only actual owner snapshots count; candidate validation and returned snapshots do not. */
    fun observe(read: ControlRecordRead) {
        if (read !is ControlRecordRead.Supported || read.schemaVersion != 2) {
            evidenceDiscontinuityCount = evidenceDiscontinuityCount.add(BigInteger.ONE)
        }
    }

    private val commands = ConcurrentHashMap<String, TrackedControlCommand>()

    internal fun registerPrepared(command: CommandRef): CommandRef {
        check(command.ownerTrackingLifetimeId === lifetimeId) { "command belongs to another tracker lifetime" }
        check(commands.putIfAbsent(command.id, TrackedControlCommand(command)) == null) {
            "command UUID collision; do not reissue an identity to hide it"
        }
        return command
    }

    internal fun findPrepared(command: CommandRef): TrackedControlCommand? =
        commands[command.id]?.takeIf { it.command === command }

    // Also covers previous-lifetime references, which are not locally prepared commands.
    // This nonblocking lease refuses duplicate execution; the owner alone serializes storage.
    val executing = ConcurrentHashMap.newKeySet<CommandRef>()
    private val unresolved = AtomicReference<Set<CommandRef>>(emptySet())
    fun isUnresolved(command: CommandRef): Boolean = command in unresolved.get()
    fun markUnresolved(command: CommandRef) { unresolved.updateAndGet { it + command } }
    fun resolve(command: CommandRef) { unresolved.updateAndGet { it - command } }
    // One immutable set version, not a weakly consistent iteration across concurrent attempts.
    fun snapshot(): Set<CommandRef> = Collections.unmodifiableSet(unresolved.get())

    companion object {
        private val collected = ReferenceQueue<DataStoreAccessEpochStore>()
        private val owners = ConcurrentHashMap<OwnerKey, ControlCommandTracking>()

        /**
         * Recreating a facade does not lose commands while the same owner is alive. No second lock.
         * A future injection boundary can replace this registry only if one retained facade/tracker
         * spans the owner's whole lifetime; merely injecting one facade at a time is insufficient.
         */
        fun forOwner(owner: DataStoreAccessEpochStore): ControlCommandTracking {
            while (true) {
                val dead = collected.poll() ?: break
                owners.remove(dead)
            }
            return owners.computeIfAbsent(OwnerKey(owner)) { ControlCommandTracking() }
        }

        private class OwnerKey(owner: DataStoreAccessEpochStore) :
            WeakReference<DataStoreAccessEpochStore>(owner, collected) {
            private val identity = System.identityHashCode(owner)
            override fun hashCode(): Int = identity
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                val owner = get() ?: return false
                return other is OwnerKey && owner === other.get()
            }
        }
    }
}
