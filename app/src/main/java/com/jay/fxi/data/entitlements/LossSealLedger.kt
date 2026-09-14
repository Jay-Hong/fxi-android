package com.jay.fxi.data.entitlements

/**
 * A validated loss whose target had no epoch on that axis when it was decided.
 *
 * Kept apart from [LossObligation]: with no epoch there is nothing to compare a later record against, so it is released
 * only by evidence bound to an operation — see [LossSealLedger.releaseByRotation] and [LossSealLedger.observe].
 */
data class NullTargetSeal(val ownerUid: String?, val axis: PurgeScope)

/** What a validated loss must retire on one axis, as the decision record stood. */
sealed interface LossTarget {
    val axis: PurgeScope

    data class Namespace(val obligation: LossObligation) : LossTarget {
        override val axis: PurgeScope get() = obligation.axis
    }

    data class NullNamespace(val seal: NullTargetSeal) : LossTarget {
        override val axis: PurgeScope get() = seal.axis
    }

    companion object {
        fun of(record: AccessEpochRecord, axis: PurgeScope): LossTarget =
            when (val epoch = LossObligations.epochOf(record, axis)) {
                null -> NullNamespace(NullTargetSeal(record.ownerUid, axis))
                else -> Namespace(LossObligation(record.ownerUid, axis, epoch))
            }
    }
}

/** The store call a confirmed record came from. Only some of them are evidence for releasing a [NullTargetSeal]. */
enum class StoreOp {
    LOAD,
    BIND_OWNER,
    SIGN_OUT,
    RETIRE_UNVERIFIED_START,
    BEGIN_SIGN_OUT,
    BEGIN_ROTATION,
    COMPLETE_PURGES,
    JOURNAL_RETIRED,
    MARK_MAY_CONTAIN_DATA
}

/**
 * Explicit losses whose rotation this process could not establish, and what they seal until it can.
 *
 * Not thread-safe. [PremiumAccessCoordinator] reads and writes it only under its mutex.
 *
 * Every record handed to [observe] must be one whose persistence is established. Two different questions are answered
 * from them and kept apart: whether a remembered namespace was retired with its cleanup handed on (the obligations and
 * null-target seals), and whether the namespace live now has an epoch at all ([sealedAxes] derives that from
 * [lastConfirmed] every time and stores nothing). Ending an obligation is never evidence that the live namespace is valid.
 */
class LossSealLedger {

    private val obligations = linkedSetOf<LossObligation>()
    private val nullTargets = linkedSetOf<NullTargetSeal>()

    /** Bindings, by probe epoch, owed a re-approval once the axes recorded for them are no longer sealed. */
    private val reapproval = linkedMapOf<Long, MutableSet<PurgeScope>>()

    /** The last record whose persistence was established. Null until the first one is observed. */
    var lastConfirmed: AccessEpochRecord? = null
        private set

    /** No obligation and no null-target seal. The derived epoch check is not part of this. */
    val isEmpty: Boolean
        get() = obligations.isEmpty() && nullTargets.isEmpty()

    fun obligations(): Set<LossObligation> = obligations.toSet()

    fun nullTargets(): Set<NullTargetSeal> = nullTargets.toSet()

    /**
     * Takes [after] as the latest confirmed record. [before] is the confirmed record the store call started from, or null
     * when there was none; it matters only for the identity retirements.
     *
     * An obligation is dropped the first time a confirmed record shows it retired: epochs are never reused, so it cannot
     * become current again, and dropping it here is what lets a later completed purge remove the covering entry.
     *
     * A null-target seal is released here only by an identity retirement that landed: [before] was owned by the seal's
     * owner, [after] is not, and [after]'s journal names that owner with that axis and no epoch. Allocation alone —
     * a same-uid bind filling a missing epoch — and an entry merely being present do not release it.
     *
     * Returns whether anything was retired or released. The journal it was handed to still needs cleaning, and whoever
     * observes the record owns that from here.
     */
    fun observe(op: StoreOp, before: AccessEpochRecord?, after: AccessEpochRecord): Boolean {
        lastConfirmed = after
        var ended = obligations.removeAll { LossObligations.judge(it, after) == ObligationStatus.RETIRED }
        if (op in IDENTITY_RETIREMENTS && before != null) {
            ended = nullTargets.removeAll { seal ->
                before.ownerUid == seal.ownerUid &&
                    after.ownerUid != seal.ownerUid &&
                    after.pendingPurges.any { entry ->
                        entry.ownerUid == seal.ownerUid && seal.axis in entry.scopes && entryEpoch(entry, seal.axis) == null
                    }
            } || ended
        }
        return ended
    }

    /** Where [obligation] stands against [lastConfirmed]. */
    fun status(obligation: LossObligation): ObligationStatus =
        LossObligations.judge(obligation, checkNotNull(lastConfirmed) { "no confirmed record yet" })

    /**
     * Whether [target] is already owned by recovery: the same obligation still current, or the same null-target seal.
     * A repeated loss for it joins that ownership instead of writing again.
     */
    fun joins(target: LossTarget): Boolean = when (target) {
        is LossTarget.Namespace ->
            target.obligation in obligations && status(target.obligation) == ObligationStatus.STILL_CURRENT
        is LossTarget.NullNamespace -> target.seal in nullTargets
    }

    /**
     * Records [targets] as owed. Adding one already present changes nothing, and an obligation [lastConfirmed] already
     * shows retired is not kept: sealing on it would wait for an observation that has already happened.
     */
    fun open(targets: Collection<LossTarget>) {
        targets.forEach { target ->
            when (target) {
                is LossTarget.Namespace -> obligations += target.obligation
                is LossTarget.NullNamespace -> nullTargets += target.seal
            }
        }
        lastConfirmed?.let { record -> obligations.removeAll { LossObligations.judge(it, record) == ObligationStatus.RETIRED } }
    }

    /**
     * The axes whose access stays sealed for [ownerUid]: an obligation not yet retired, a null-target seal, or — derived
     * from [lastConfirmed] and not stored — a confirmed record of that owner with no epoch on the axis.
     */
    fun sealedAxes(ownerUid: String?): Set<PurgeScope> {
        val axes = lossSealedAxes(ownerUid).toMutableSet()
        val record = lastConfirmed
        if (ownerUid != null && record != null && record.ownerUid == ownerUid) {
            if (record.userAccessEpoch == null) axes += PurgeScope.USER
            if (record.krxCapabilityEpoch == null) axes += PurgeScope.CAPABILITY
        }
        return axes
    }

    /**
     * Releases [seal] on a rotation this process asked for on its behalf and that returned normally.
     *
     * [before] is the confirmed record the rotation was asked on and [after] what it returned. Released only if both
     * are owned by the seal's owner, the axis now has a new epoch, and [after]'s journal has an entry for that axis with
     * no epoch whose owner is the seal's or unknown. `rotate` journals whatever epoch its input had, so the entry is
     * looked for rather than assumed to be the one this rotation appended.
     */
    fun releaseByRotation(seal: NullTargetSeal, before: AccessEpochRecord, after: AccessEpochRecord): Boolean {
        if (seal !in nullTargets) return false
        val newEpoch = LossObligations.epochOf(after, seal.axis)
        val released = before.ownerUid == seal.ownerUid &&
            after.ownerUid == seal.ownerUid &&
            newEpoch != null &&
            newEpoch != LossObligations.epochOf(before, seal.axis) &&
            after.pendingPurges.any { entry ->
                seal.axis in entry.scopes &&
                    (entry.ownerUid == null || entry.ownerUid == seal.ownerUid) &&
                    entryEpoch(entry, seal.axis) == null
            }
        if (released) nullTargets.remove(seal)
        return released
    }

    /** Owes [binding] a re-approval on every axis currently loss-sealed for [ownerUid]. The derived epoch check owes none. */
    fun registerReapproval(binding: Long, ownerUid: String?) {
        val axes = lossSealedAxes(ownerUid)
        if (axes.isNotEmpty()) reapproval.getOrPut(binding) { linkedSetOf() } += axes
    }

    /**
     * The axes [binding] is owed a re-approval on, once none of them is loss-sealed for [ownerUid]; null while any is, or
     * when nothing is owed. Every other binding's debt is forgotten: it ended, and a debt is not handed to a successor.
     */
    fun takeReapproval(binding: Long, ownerUid: String?): Set<PurgeScope>? {
        reapproval.keys.retainAll(setOf(binding))
        val axes = reapproval[binding] ?: return null
        val sealed = lossSealedAxes(ownerUid)
        if (axes.any { it in sealed }) return null
        reapproval.remove(binding)
        return axes.toSet()
    }

    /** The axes sealed for [ownerUid] by an obligation or a null-target seal — [sealedAxes] without the derived part. */
    fun explicitSealedAxes(ownerUid: String?): Set<PurgeScope> = lossSealedAxes(ownerUid)

    private fun lossSealedAxes(ownerUid: String?): Set<PurgeScope> = buildSet {
        obligations.filter { it.ownerUid == ownerUid }.forEach { add(it.axis) }
        nullTargets.filter { it.ownerUid == ownerUid }.forEach { add(it.axis) }
    }

    private fun entryEpoch(entry: PendingPurge, axis: PurgeScope): String? = when (axis) {
        PurgeScope.USER -> entry.userAccessEpoch
        PurgeScope.CAPABILITY -> entry.krxCapabilityEpoch
    }

    private companion object {
        val IDENTITY_RETIREMENTS = setOf(StoreOp.SIGN_OUT, StoreOp.BIND_OWNER, StoreOp.RETIRE_UNVERIFIED_START)
    }
}
