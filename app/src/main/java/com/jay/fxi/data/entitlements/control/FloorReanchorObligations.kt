package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope

internal data class FloorSource(
    val sourceId: String, val originalHold: ControlNode,
    val parsedHold: RestoredHold, val floor: FloorV1,
    val guardBefore: ControlNode?
)
internal data class ConfirmedHoldReanchor(
    val source: FloorSource, val oldGuard: ControlNode?,
    val mergeNow: BootReading, val origin: LifetimeId,
    val destinationId: String, val output: ControlNode,
    val confirmedOutput: ControlNode, val proof: ConfirmationProof
)
internal enum class ReanchorField {
    SOURCE_ID, SOURCE_TEXT, SOURCE_SUBJECT, PROVENANCE, SOURCE_FLOOR,
    GUARD_PREIMAGE, GUARD_ID, AUTH, OUTPUT_FLOOR,
    ANCHOR_BOOT, ANCHOR_ELAPSED, ORIGIN, EXACT_MAX, INVALID_READING
}
internal sealed interface ReanchorValidation {
    data class Valid(val outputFloor: FloorV1) : ReanchorValidation
    data class Invalid(val field: ReanchorField) : ReanchorValidation
}

private fun samePayload(a: ControlNode?, b: ControlNode?): Boolean =
    a?.toPayloadEntry() == b?.toPayloadEntry()

/** The source predicates are ordered to report the exact broken binding. */
private fun sourceProblem(source: FloorSource): ReanchorField? {
    if (source.sourceId != source.parsedHold.id) return ReanchorField.SOURCE_ID
    val original = ControlSchema.read(ControlKind.HOLD, source.originalHold) as? RestoredHold
        ?: return ReanchorField.SOURCE_TEXT
    if (original.id != source.parsedHold.id) return ReanchorField.SOURCE_ID
    if (original.originLifetimeId != source.parsedHold.originLifetimeId ||
        original.binding != source.parsedHold.binding || original.axes != source.parsedHold.axes)
        return ReanchorField.SOURCE_SUBJECT
    if (original.provenance != source.parsedHold.provenance) return ReanchorField.PROVENANCE
    if (original.outcome != source.parsedHold.outcome) return ReanchorField.SOURCE_TEXT
    if (original.floor != source.parsedHold.floor || source.floor != source.parsedHold.floor)
        return ReanchorField.SOURCE_FLOOR
    return null
}

/** Recomputes the named RECOVER_HOLD exact max from fixed preimages. */
internal fun validHoldReanchor(
    source: FloorSource, oldGuard: ControlNode?, mergeNow: BootReading,
    origin: LifetimeId, destinationId: String, output: ControlNode
): ReanchorValidation {
    fun invalid(field: ReanchorField) = ReanchorValidation.Invalid(field)
    sourceProblem(source)?.let { return invalid(it) }
    if (!samePayload(source.guardBefore, oldGuard)) return invalid(ReanchorField.GUARD_PREIMAGE)
    if (mergeNow.bootId == "" || mergeNow.elapsedMillis < 0 || origin.value.isEmpty())
        return invalid(ReanchorField.INVALID_READING)
    val before = oldGuard?.let { guard(it) ?: return invalid(ReanchorField.GUARD_PREIMAGE) }
    val sourceRemaining = source.floor.remainingAt(mergeNow) ?: return invalid(ReanchorField.INVALID_READING)
    // Absence is zero; an existing but invalid floor is never silently lowered to zero.
    val oldRemaining = before?.floor?.remainingAt(mergeNow)
    if (before?.floor != null && oldRemaining == null) return invalid(ReanchorField.INVALID_READING)
    val after = guard(output) ?: return invalid(ReanchorField.OUTPUT_FLOOR)
    if (destinationId.isEmpty() || after.id != (before?.id ?: destinationId)) return invalid(ReanchorField.GUARD_ID)
    if (after.auth != before?.auth || output.toPayloadEntry().fields["auth"] !=
        oldGuard?.toPayloadEntry()?.fields?.get("auth")) return invalid(ReanchorField.AUTH)
    val floor = after.floor ?: return invalid(ReanchorField.OUTPUT_FLOOR)
    if (floor.remainingAt(mergeNow) == null) return invalid(ReanchorField.INVALID_READING)
    if (floor.anchorBootId != mergeNow.bootId) return invalid(ReanchorField.ANCHOR_BOOT)
    if (floor.anchorElapsedMillis != mergeNow.elapsedMillis) return invalid(ReanchorField.ANCHOR_ELAPSED)
    if (floor.originLifetimeId != origin) return invalid(ReanchorField.ORIGIN)
    if (floor.waitMillis != maxOf(sourceRemaining, oldRemaining ?: 0L)) return invalid(ReanchorField.EXACT_MAX)
    return ReanchorValidation.Valid(floor)
}

internal enum class FloorBoundUnavailableReason {
    INVALID_SOURCE, INVALID_READING, LINK_SOURCE_MISMATCH,
    LINK_OUTPUT_MISMATCH, DUPLICATE_LINK, INCOMPLETE_LINK, INVALID_REANCHOR
}
internal sealed interface FloorBoundResult {
    data class Bound(val remainingMillis: Long) : FloorBoundResult
    data class Unavailable(val reason: FloorBoundUnavailableReason) : FloorBoundResult
}

internal fun floorLowerBound(
    sources: List<FloorSource>, confirmedReanchors: List<ConfirmedHoldReanchor>, now: BootReading
): FloorBoundResult {
    fun unavailable(reason: FloorBoundUnavailableReason) = FloorBoundResult.Unavailable(reason)
    if (now.bootId == "" || now.elapsedMillis < 0) return unavailable(FloorBoundUnavailableReason.INVALID_READING)
    if (sources.map { it.sourceId }.distinct().size != sources.size ||
        sources.any { sourceProblem(it) != null || it.floor.remainingAt(now) == null })
        return unavailable(FloorBoundUnavailableReason.INVALID_SOURCE)
    val byId = sources.associateBy { it.sourceId }
    if (confirmedReanchors.groupingBy { it.source.sourceId }.eachCount().any { it.value > 1 })
        return unavailable(FloorBoundUnavailableReason.DUPLICATE_LINK)
    val links = mutableMapOf<String, ConfirmedHoldReanchor>()
    for (link in confirmedReanchors) {
        val fixed = byId[link.source.sourceId] ?: return unavailable(FloorBoundUnavailableReason.LINK_SOURCE_MISMATCH)
        if (fixed.sourceId != link.source.sourceId || fixed.parsedHold != link.source.parsedHold ||
            fixed.floor != link.source.floor || !samePayload(fixed.originalHold, link.source.originalHold) ||
            !samePayload(fixed.guardBefore, link.source.guardBefore))
            return unavailable(FloorBoundUnavailableReason.LINK_SOURCE_MISMATCH)
        if (link.destinationId.isEmpty() || link.origin.value.isEmpty())
            return unavailable(FloorBoundUnavailableReason.INCOMPLETE_LINK)
        if (fixed.guardBefore != null && link.oldGuard == null)
            return unavailable(FloorBoundUnavailableReason.INCOMPLETE_LINK)
        if (!samePayload(link.output, link.confirmedOutput))
            return unavailable(FloorBoundUnavailableReason.LINK_OUTPUT_MISMATCH)
        if (validHoldReanchor(fixed, link.oldGuard, link.mergeNow, link.origin,
                link.destinationId, link.output) !is ReanchorValidation.Valid)
            return unavailable(FloorBoundUnavailableReason.INVALID_REANCHOR)
        links[fixed.sourceId] = link
    }
    var maximum = 0L
    for (source in sources) {
        val link = links[source.sourceId]
        val representative = if (link == null) source.floor else
            (guard(link.confirmedOutput)?.floor ?: return unavailable(FloorBoundUnavailableReason.INCOMPLETE_LINK))
        val remaining = representative.remainingAt(now) ?: return unavailable(FloorBoundUnavailableReason.INVALID_READING)
        maximum = maxOf(maximum, remaining)
    }
    return FloorBoundResult.Bound(maximum)
}

internal enum class NJournalUnavailableReason { INCOMPLETE_INPUT, INVALID_TARGET, SUBJECT_MISMATCH, SLOT_COLLISION }
internal sealed interface NJournalKeysResult {
    data class Available(val physicalKeys: List<JournalTargetV1>, val slots: List<NJournalSlot>) : NJournalKeysResult
    data class Unavailable(val reason: NJournalUnavailableReason) : NJournalKeysResult
}
internal data class NJournalSlot(val sealId: String, val branch: LandingBranch, val key: JournalTargetV1)

/** A physical null-epoch key may serve distinct source and branch slots. */
internal fun nSettlementJournalKeys(input: CurrentNullSettlement): NJournalKeysResult {
    fun unavailable(reason: NJournalUnavailableReason) = NJournalKeysResult.Unavailable(reason)
    if (input.nullTargets.isEmpty() || input.nulls.size != input.nullTargets.size ||
        input.accompanying.size != input.companions.size) return unavailable(NJournalUnavailableReason.INCOMPLETE_INPUT)
    if (input.nulls.any { it.seal.kind != SealTargetKind.NULL_NAMESPACE || it.seal.settlement != null } ||
        input.accompanying.any { it.seal.kind != SealTargetKind.NAMESPACE || it.seal.settlement != null })
        return unavailable(NJournalUnavailableReason.INVALID_TARGET)
    val axes = input.nulls.map { it.seal.key.axis }
    if (axes.distinct().size != axes.size || input.targets.map { it.seal.id }.distinct().size != input.targets.size ||
        input.accompanying.map { it.seal.key.axis }.distinct().size != input.accompanying.size)
        return unavailable(NJournalUnavailableReason.SLOT_COLLISION)
    if (input.targets.any { it.seal.key.ownerUid != input.before.ownerUid } ||
        input.nulls.any { it.seal.key.epoch != null } ||
        input.accompanying.any { it.seal.key.axis !in axes ||
            it.seal.key.epoch != input.before.epoch(it.seal.key.axis) })
        return unavailable(NJournalUnavailableReason.SUBJECT_MISMATCH)
    val keys = axes.map { JournalTargetV1(input.before.ownerUid, it, null) }
    val slots = input.targets.flatMap { target ->
        val key = JournalTargetV1(input.before.ownerUid, target.seal.key.axis, null)
        listOf(NJournalSlot(target.seal.id, LandingBranch.L, key), NJournalSlot(target.seal.id, LandingBranch.N, key))
    }
    return NJournalKeysResult.Available(keys, slots)
}
