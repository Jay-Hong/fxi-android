package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RefreshIntent

internal sealed interface ComparisonField
internal enum class RequestField : ComparisonField { OWNER, SUBJECT, INTENT, ORDER }
internal enum class JournalField : ComparisonField { ABSENT }
internal enum class HoldField : ComparisonField { KIND, ID, SUBJECT, PROVENANCE, AXES, FLOOR }
internal enum class IntentField : ComparisonField { ID, SESSION, OWNER, AXIS, TARGET_EPOCH }
internal enum class SealField : ComparisonField { KIND, ID, KEY, SETTLEMENT }
internal enum class AuthField : ComparisonField { SCOPE, STATE }
internal enum class FloorField : ComparisonField { ABSENT, LOWER_BOUND }
internal enum class TypedUnavailableReason {
    REBIND_UNSUPPORTED, REPLACEMENT_UNSUPPORTED, NAMED_LINK_REQUIRED,
    INVALID_INPUT, INVALID_READING
}
internal sealed interface TypedComparison {
    data object Matches : TypedComparison
    data class Mismatch(val field: ComparisonField) : TypedComparison
    data class Unavailable(val reason: TypedUnavailableReason) : TypedComparison
}
internal data class RequestNeed(val source: DemandV1, val minIntent: RefreshIntent,
    val minOrder: EventOrderV1)

private fun RefreshIntent.strength(): Int = when (this) {
    RefreshIntent.IF_STALE -> 0
    RefreshIntent.FORCE_ENTITLEMENTS -> 1
    RefreshIntent.FORCE_PREMIUM -> 2
}

internal fun compareRequest(need: RequestNeed, actual: DemandV1): TypedComparison {
    val source = need.source
    if (need.minOrder.origin != source.raisedAt.origin || need.minOrder.value < 0 || source.raisedAt.value < 0)
        return TypedComparison.Unavailable(TypedUnavailableReason.INVALID_INPUT)
    if (actual.ownerUid != source.ownerUid) return TypedComparison.Mismatch(RequestField.OWNER)
    if (actual.id != source.id) return TypedComparison.Mismatch(RequestField.SUBJECT)
    if (actual.binding != source.binding || actual.raisedAt.origin != source.raisedAt.origin)
        return TypedComparison.Unavailable(TypedUnavailableReason.REBIND_UNSUPPORTED)
    if (actual.intent.strength() < maxOf(source.intent.strength(), need.minIntent.strength()))
        return TypedComparison.Mismatch(RequestField.INTENT)
    if (actual.raisedAt.value < maxOf(source.raisedAt.value, need.minOrder.value))
        return TypedComparison.Mismatch(RequestField.ORDER)
    return TypedComparison.Matches
}

internal fun compareJournal(key: JournalTargetV1, canonical: List<PendingPurge>): TypedComparison {
    if (key.ownerUid == "" || key.epoch == "" || canonical.any {
        it.scopes.isEmpty() || it.ownerUid == "" || it.userAccessEpoch == "" || it.krxCapabilityEpoch == ""
    }) return TypedComparison.Unavailable(TypedUnavailableReason.INVALID_INPUT)
    return if (canonical.any { entry -> key.axis in entry.scopes &&
        key.ownerUid == entry.ownerUid && key.epoch == when (key.axis) {
            PurgeScope.USER -> entry.userAccessEpoch
            PurgeScope.CAPABILITY -> entry.krxCapabilityEpoch
        }
    }) TypedComparison.Matches else TypedComparison.Mismatch(JournalField.ABSENT)
}

internal fun compareHold(source: RestoredHold, actual: RestoredHold): TypedComparison = when {
    source.id != actual.id -> TypedComparison.Mismatch(HoldField.ID)
    source.originLifetimeId != actual.originLifetimeId || source.binding != actual.binding ->
        TypedComparison.Mismatch(HoldField.SUBJECT)
    source.axes != actual.axes -> TypedComparison.Mismatch(HoldField.AXES)
    source.provenance != actual.provenance -> TypedComparison.Mismatch(HoldField.PROVENANCE)
    source.outcome != actual.outcome -> TypedComparison.Mismatch(HoldField.KIND)
    source.floor != actual.floor -> TypedComparison.Mismatch(HoldField.FLOOR)
    else -> TypedComparison.Matches
}

internal fun compareRecoveryIntent(source: RecoveryIntentV1, actual: RecoveryIntentV1): TypedComparison = when {
    source.id != actual.id -> TypedComparison.Mismatch(IntentField.ID)
    source.sessionId != actual.sessionId -> TypedComparison.Mismatch(IntentField.SESSION)
    source.ownerUid != actual.ownerUid -> TypedComparison.Mismatch(IntentField.OWNER)
    source.axis != actual.axis -> TypedComparison.Mismatch(IntentField.AXIS)
    source.targetEpoch != actual.targetEpoch -> TypedComparison.Mismatch(IntentField.TARGET_EPOCH)
    else -> TypedComparison.Matches
}

internal fun compareSeal(source: SealV1, actual: SealV1): TypedComparison = when {
    source.kind != actual.kind -> TypedComparison.Mismatch(SealField.KIND)
    source.id != actual.id -> TypedComparison.Mismatch(SealField.ID)
    source.key != actual.key -> TypedComparison.Mismatch(SealField.KEY)
    source.settlement != actual.settlement -> TypedComparison.Mismatch(SealField.SETTLEMENT)
    else -> TypedComparison.Matches
}

internal fun compareAuth(required: AuthSnapshotV1, actual: AuthSnapshotV1): TypedComparison = when {
    required.ownerUid != actual.ownerUid || required.binding != actual.binding ||
        required.originLifetimeId != actual.originLifetimeId || required.authGeneration != actual.authGeneration ->
        TypedComparison.Mismatch(AuthField.SCOPE)
    required.authStopped != actual.authStopped || required.authStateOrder != actual.authStateOrder ||
        required.authStopAppliedOrder != actual.authStopAppliedOrder -> TypedComparison.Mismatch(AuthField.STATE)
    else -> TypedComparison.Matches
}

internal fun compareFloor(requiredRemaining: Long, actual: FloorV1?, now: BootReading): TypedComparison {
    if (requiredRemaining < 0) return TypedComparison.Unavailable(TypedUnavailableReason.INVALID_INPUT)
    if (actual == null) return TypedComparison.Mismatch(FloorField.ABSENT)
    val remaining = actual.remainingAt(now)
        ?: return TypedComparison.Unavailable(TypedUnavailableReason.INVALID_READING)
    return if (remaining >= requiredRemaining) TypedComparison.Matches else TypedComparison.Mismatch(FloorField.LOWER_BOUND)
}
