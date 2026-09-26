package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Claude-owned 6-1C T8 contract, part b (API r2 §2.1 and §4 rows REQUEST/HOLD/intent/seal/AUTH/floor; revision 06
 * §4.2 typed comparators). Pure only, one field changed per row. REQUEST: owner/subject first, a same-owner binding or
 * origin change is an unsupported rebind (never passed by a bigger order), then intent and same-scope order. AUTH is an
 * exact scope/state preservation, not an ordinal bound. floor needs the field (even at zero remaining) and the same-now
 * remainder. Latest uniqueness, storage confirmation and actual completion are 6-4b. The implementation thread reads
 * but does not edit this file.
 */
class ObligationComparatorContractTest {
    private val life = LifetimeId("life")
    private val demand = ControlSchema.read(ControlKind.DEMAND, node(ControlObligationFixtures.request)) as DemandV1
    private val need = RequestNeed(demand, RefreshIntent.FORCE_ENTITLEMENTS, EventOrderV1(demand.raisedAt.origin, demand.raisedAt.value))
    private fun actual(intent: RefreshIntent = RefreshIntent.FORCE_PREMIUM, value: Long = demand.raisedAt.value + 1,
        owner: String? = demand.ownerUid, id: String = demand.id, binding: Long = demand.binding, origin: LifetimeId = demand.raisedAt.origin) =
        demand.copy(id = id, ownerUid = owner, binding = binding, intent = intent, raisedAt = EventOrderV1(origin, value))

    // ── REQUEST ──
    @Test fun T8b_01_requestStrongerIntentAndLaterOrderMatch() {
        assertEquals("D2B6/T8b.01", TypedComparison.Matches, compareRequest(need, actual()))
        assertEquals("D2B6/T8b.01: equalBoundsMatch", TypedComparison.Matches,
            compareRequest(need, actual(intent = RefreshIntent.FORCE_ENTITLEMENTS, value = demand.raisedAt.value)))
    }
    @Test fun T8b_02_requestOwnerMismatch() {
        assertEquals("D2B6/T8b.02", TypedComparison.Mismatch(RequestField.OWNER), compareRequest(need, actual(owner = "B")))
    }
    @Test fun T8b_03_requestSubjectMismatch() {
        assertEquals("D2B6/T8b.03", TypedComparison.Mismatch(RequestField.SUBJECT), compareRequest(need, actual(id = "other")))
    }
    @Test fun T8b_04_sameOwnerRebindIsUnsupportedEvenWithBiggerOrder() {
        val rebind = TypedComparison.Unavailable(TypedUnavailableReason.REBIND_UNSUPPORTED)
        assertEquals("D2B6/T8b.04a: binding", rebind, compareRequest(need, actual(binding = demand.binding + 1, value = 1_000)))
        assertEquals("D2B6/T8b.04b: origin", rebind, compareRequest(need, actual(origin = LifetimeId("other-life"), value = 1_000)))
    }
    @Test fun T8b_05_requestIntentBelowBound() {
        assertEquals("D2B6/T8b.05", TypedComparison.Mismatch(RequestField.INTENT), compareRequest(need, actual(intent = RefreshIntent.IF_STALE)))
    }
    @Test fun T8b_05b_userAxisLowerBoundIsForcePremium() {
        val userNeed = need.copy(minIntent = RefreshIntent.FORCE_PREMIUM)
        assertEquals("D2B6/T8b.05b", TypedComparison.Mismatch(RequestField.INTENT),
            compareRequest(userNeed, actual(intent = RefreshIntent.FORCE_ENTITLEMENTS)))
    }
    @Test fun T8b_06_requestSameScopeOrderBelowBound() {
        assertEquals("D2B6/T8b.06", TypedComparison.Mismatch(RequestField.ORDER), compareRequest(need, actual(value = demand.raisedAt.value - 1)))
    }
    @Test fun T8b_07_minOrderFromAnotherScopeIsInvalidInput() {
        val bad = need.copy(minOrder = EventOrderV1(LifetimeId("other-life"), demand.raisedAt.value))
        assertEquals("D2B6/T8b.07", TypedComparison.Unavailable(TypedUnavailableReason.INVALID_INPUT), compareRequest(bad, actual()))
    }

    // ── HOLD / intent / seal direct preservation ──
    private val hold = ControlSchema.read(ControlKind.HOLD, node(ControlObligationFixtures.hold)) as RestoredHold
    @Test fun T8b_10_holdPreservation() {
        assertEquals("D2B6/T8b.10a: same", TypedComparison.Matches, compareHold(hold, hold.copy()))
        assertEquals("D2B6/T8b.10b: id", TypedComparison.Mismatch(HoldField.ID), compareHold(hold, hold.copy(id = "h2")))
        assertEquals("D2B6/T8b.10c: axes", TypedComparison.Mismatch(HoldField.AXES), compareHold(hold, hold.copy(axes = setOf(PurgeScope.USER))))
        assertEquals("D2B6/T8b.10d: subject", TypedComparison.Mismatch(HoldField.SUBJECT), compareHold(hold, hold.copy(binding = hold.binding + 1)))
        assertEquals("D2B6/T8b.10e: floor", TypedComparison.Mismatch(HoldField.FLOOR), compareHold(hold, hold.copy(floor = null)))
        val otherQuery = ControlObligationFixtures.hold.replace("\"order\":7", "\"order\":8").also { check(it != ControlObligationFixtures.hold) }
        val otherProvenance = (ControlSchema.read(ControlKind.HOLD, node(otherQuery)) as RestoredHold).provenance
        assertEquals("D2B6/T8b.10f: provenance", TypedComparison.Mismatch(HoldField.PROVENANCE), compareHold(hold, hold.copy(provenance = otherProvenance)))
        assertEquals("D2B6/T8b.10g: origin", TypedComparison.Mismatch(HoldField.SUBJECT), compareHold(hold, hold.copy(originLifetimeId = LifetimeId("other"))))
        // HoldField.KIND = the archived outcome (kind/krxVisible/retry), fixed by this contract. Both HOLDs are
        // schema-valid: STABLE_INACTIVE carries no floor and allows either visibility.
        fun inactive(visible: Boolean) = ControlSchema.read(ControlKind.HOLD, node("""{"id":"h","originLifetimeId":"life","binding":3,"axes":["USER","CAPABILITY"],"outcome":{"kind":"STABLE_INACTIVE","krxVisible":$visible},"provenance":${ControlObligationFixtures.query}}""")) as RestoredHold
        assertEquals("D2B6/T8b.10h: outcome", TypedComparison.Mismatch(HoldField.KIND), compareHold(inactive(true), inactive(false)))
    }
    private val intent = RecoveryIntentV1("i", "session-1", "A", PurgeScope.USER, "u")
    @Test fun T8b_11_intentPreservation() {
        assertEquals("D2B6/T8b.11a: same", TypedComparison.Matches, compareRecoveryIntent(intent, intent.copy()))
        assertEquals("D2B6/T8b.11b: id", TypedComparison.Mismatch(IntentField.ID), compareRecoveryIntent(intent, intent.copy(id = "j")))
        assertEquals("D2B6/T8b.11c: session", TypedComparison.Mismatch(IntentField.SESSION), compareRecoveryIntent(intent, intent.copy(sessionId = "session-2")))
        assertEquals("D2B6/T8b.11d: owner", TypedComparison.Mismatch(IntentField.OWNER), compareRecoveryIntent(intent, intent.copy(ownerUid = "B")))
        assertEquals("D2B6/T8b.11e: axis", TypedComparison.Mismatch(IntentField.AXIS), compareRecoveryIntent(intent, intent.copy(axis = PurgeScope.CAPABILITY)))
        assertEquals("D2B6/T8b.11f: nullTargetIsATarget", TypedComparison.Mismatch(IntentField.TARGET_EPOCH),
            compareRecoveryIntent(intent, intent.copy(targetEpoch = null)))
    }
    private val seal = SealV1("s", SealTargetKind.NAMESPACE, SealKey("A", PurgeScope.USER, "u"), null)
    @Test fun T8b_12_sealPreservation() {
        assertEquals("D2B6/T8b.12a: same", TypedComparison.Matches, compareSeal(seal, seal.copy()))
        assertEquals("D2B6/T8b.12b: id", TypedComparison.Mismatch(SealField.ID), compareSeal(seal, seal.copy(id = "t")))
        assertEquals("D2B6/T8b.12c: kind", TypedComparison.Mismatch(SealField.KIND), compareSeal(seal, seal.copy(kind = SealTargetKind.NULL_NAMESPACE)))
        assertEquals("D2B6/T8b.12d: key", TypedComparison.Mismatch(SealField.KEY), compareSeal(seal, seal.copy(key = SealKey("A", PurgeScope.USER, null))))
        val settled = ControlSchema.read(ControlKind.SEAL, node(ControlObligationFixtures.settledSeal)) as SealV1
        assertEquals("D2B6/T8b.12e: settlement", TypedComparison.Mismatch(SealField.SETTLEMENT), compareSeal(settled, settled.copy(settlement = null)))
    }

    // ── AUTH exact scope/state ──
    // Schema-valid stopped snapshot: authStopAppliedOrder > authStateOrder > 0 (ControlSchema auth).
    private val auth = AuthSnapshotV1("A", 2, 3, life, true, 10, 11)
    @Test fun T8b_13_authExactPreservation() {
        assertEquals("D2B6/T8b.13a: same", TypedComparison.Matches, compareAuth(auth, auth.copy()))
        assertEquals("D2B6/T8b.13b: newOwner", TypedComparison.Mismatch(AuthField.SCOPE), compareAuth(auth, auth.copy(ownerUid = "B")))
        assertEquals("D2B6/T8b.13c: otherBinding", TypedComparison.Mismatch(AuthField.SCOPE), compareAuth(auth, auth.copy(binding = 4)))
        assertEquals("D2B6/T8b.13d: otherGeneration", TypedComparison.Mismatch(AuthField.SCOPE), compareAuth(auth, auth.copy(authGeneration = 3)))
        assertEquals("D2B6/T8b.13e: otherOrigin", TypedComparison.Mismatch(AuthField.SCOPE), compareAuth(auth, auth.copy(originLifetimeId = LifetimeId("other"))))
        assertEquals("D2B6/T8b.13f: otherStateOrder", TypedComparison.Mismatch(AuthField.STATE), compareAuth(auth, auth.copy(authStateOrder = 9)))
        assertEquals("D2B6/T8b.13g: biggerAppliedOrderIsNotBetter", TypedComparison.Mismatch(AuthField.STATE), compareAuth(auth, auth.copy(authStopAppliedOrder = 12)))
        assertEquals("D2B6/T8b.13h: resumedIsNotBetter", TypedComparison.Mismatch(AuthField.STATE), compareAuth(auth, auth.copy(authStopped = false)))
    }

    // ── floor lower bound against the latest field ──
    private fun floor(boot: String?, elapsed: Long, wait: Long) = FloorV1(boot, elapsed, wait, life)
    @Test fun T8b_14_floorLowerBound() {
        val now = BootReading("B", 1100)
        assertEquals("D2B6/T8b.14a: equalRemainder", TypedComparison.Matches, compareFloor(900, floor("B", 1000, 1000), now))
        assertEquals("D2B6/T8b.14b: shortBy1", TypedComparison.Mismatch(FloorField.LOWER_BOUND), compareFloor(900, floor("B", 1000, 999), now))
        assertEquals("D2B6/T8b.14c: fieldAbsent", TypedComparison.Mismatch(FloorField.ABSENT), compareFloor(0, null, now))
        assertEquals("D2B6/T8b.14d: zeroRemainderStillNeedsTheField", TypedComparison.Matches, compareFloor(0, floor("B", 0, 100), now))
        assertEquals("D2B6/T8b.14e: invalidReading", TypedComparison.Unavailable(TypedUnavailableReason.INVALID_READING),
            compareFloor(900, floor("B", 1000, 1000), BootReading("", 1100)))
        assertEquals("D2B6/T8b.14f: invalidActualFloor", TypedComparison.Unavailable(TypedUnavailableReason.INVALID_READING),
            compareFloor(900, floor("", 1000, 1000), now))
    }

    // ── r7 (6-1C measurement r1): each half of a bound, and inputs the comparator cannot interpret ──
    @Test fun T8b_05c_strongerSourceIntentIsTheBound() {
        // API r2 §2.1: "원 요구가 더 강하면 그 값을 쓴다".
        val strong = demand.copy(intent = RefreshIntent.FORCE_PREMIUM)
        val strongNeed = RequestNeed(strong, RefreshIntent.FORCE_ENTITLEMENTS, need.minOrder)
        fun at(intent: RefreshIntent) = compareRequest(strongNeed, strong.copy(intent = intent, raisedAt = EventOrderV1(demand.raisedAt.origin, demand.raisedAt.value + 1)))
        assertEquals("D2B6/T8b.05c: belowSource", TypedComparison.Mismatch(RequestField.INTENT), at(RefreshIntent.FORCE_ENTITLEMENTS))
        assertEquals("D2B6/T8b.05c: equalSource", TypedComparison.Matches, at(RefreshIntent.FORCE_PREMIUM))
    }
    @Test fun T8b_06b_orderBoundIsTheLargerOfSourceAndMinOrder() {
        val v = demand.raisedAt.value
        val lowMin = need.copy(minOrder = EventOrderV1(demand.raisedAt.origin, v - 1))
        assertEquals("D2B6/T8b.06b: belowSourceAboveMin", TypedComparison.Mismatch(RequestField.ORDER), compareRequest(lowMin, actual(value = v - 1)))
        val highMin = need.copy(minOrder = EventOrderV1(demand.raisedAt.origin, v + 5))
        assertEquals("D2B6/T8b.06c: aboveSourceBelowMin", TypedComparison.Mismatch(RequestField.ORDER), compareRequest(highMin, actual(value = v + 1)))
        assertEquals("D2B6/T8b.06d: atMin", TypedComparison.Matches, compareRequest(highMin, actual(value = v + 5)))
    }
    @Test fun T8b_08_negativeOrdersAreInvalidInput() {
        val invalid = TypedComparison.Unavailable(TypedUnavailableReason.INVALID_INPUT)
        assertEquals("D2B6/T8b.08a: negativeMinOrder", invalid,
            compareRequest(need.copy(minOrder = EventOrderV1(demand.raisedAt.origin, -1)), actual()))
        val negative = demand.copy(raisedAt = EventOrderV1(demand.raisedAt.origin, -1))
        assertEquals("D2B6/T8b.08b: negativeSourceOrder", invalid, compareRequest(
            RequestNeed(negative, RefreshIntent.FORCE_ENTITLEMENTS, EventOrderV1(demand.raisedAt.origin, 0)),
            negative.copy(intent = RefreshIntent.FORCE_PREMIUM, raisedAt = EventOrderV1(demand.raisedAt.origin, 5))))
    }
    @Test fun T8b_15_negativeRequiredRemainderIsInvalidInput() {
        assertEquals("D2B6/T8b.15", TypedComparison.Unavailable(TypedUnavailableReason.INVALID_INPUT),
            compareFloor(-1, floor("B", 1000, 1000), BootReading("B", 1100)))
    }
}
