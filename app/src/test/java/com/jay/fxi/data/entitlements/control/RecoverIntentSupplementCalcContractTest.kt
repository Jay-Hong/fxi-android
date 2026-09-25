package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.RefreshIntent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 5e-2 supplement (measure ledger r1 review, OPEN 2): requiredEffects' expected-value computation
 * (markerKey axis choice, expectedEpoch axis/rotate choice, expectedMarker) checked by negatives that a wrong computation
 * would accept — each a literal candidate from design L412/L443 with one effect wrong. Both axes (USER, CAPABILITY) and
 * both rotate (current owner, target == current) and preserve (non-target axis) sides.
 * The implementation thread reads but does not edit this file.
 */
class RecoverIntentSupplementCalcContractTest {
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    private val writer = RecoverIntentTransition(ControlPayloadCodec())
    private val newLife = LifetimeId("new-life")
    private val executor = SettlementExecutor("A", 3, newLife)
    private val binding = LifecycleBinding(executor, IdentityV1("A", 2), 1, "binding-start")
    private val userFresh = "00000000-0000-0000-0000-000000000011"
    private val krxFresh = "00000000-0000-0000-0000-000000000012"
    private val grant = LifecycleOrderGrant(newLife, 21, 1, 0, 22)
    private fun intent(axis: String, target: String) =
        ControlObligationFixtures.node("""{"id":"r","sessionId":"session","ownerUid":"A","axis":"$axis","targetEpoch":"$target"}""")
    private fun payload(nodes: List<ControlNode>) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
    private fun input(src: ControlNode) = RecoverIntentInput(src, FenceV1("A", "u", "k"), binding, HoldRecoveryClosure.AfterRestart(src, executor, "previous-tracker", true, true))
    private fun before(src: ControlNode): Preferences = ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
        this[OWNER_UID] = "A"; this[USER_EPOCH] = "u"; this[KRX_EPOCH] = "k"
        this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
        this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = payload(listOf(src))
    }.toPreferences()
    private fun read(p: Preferences) = ControlLifecycleEvidenceFixtures.read(p)
    private fun edit(p: Preferences, change: (MutablePreferences) -> Unit) = p.toMutablePreferences().apply(change).toPreferences()
    private fun request(intent: RefreshIntent) = DemandAuthFixtures.request(id = "new-request", owner = "A", binding = 3, origin = newLife, intent = intent, order = 22)

    private val cap = intent("CAPABILITY", "k")
    private val capIds = RecoverIntentIds("00000000-0000-0000-0000-000000000101", "new-request", RecoveryFreshEpochs(null, krxFresh))
    private fun capGood() = edit(before(cap)) {
        it[KRX_EPOCH] = krxFresh; it[MAY_CONTAIN_KRX] = false; it[PURGE_JOURNAL] = "A||k|CAPABILITY"
        it[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = "[]"
        it[ControlRecordKeys.payload(ControlKind.DEMAND)] = payload(listOf(request(RefreshIntent.FORCE_ENTITLEMENTS)))
    }
    private val user = intent("USER", "u")
    private val userIds = RecoverIntentIds("00000000-0000-0000-0000-000000000101", "new-request", RecoveryFreshEpochs(userFresh, null))
    private fun userGood() = edit(before(user)) {
        it[USER_EPOCH] = userFresh; it[MAY_CONTAIN_PREMIUM] = false; it[PURGE_JOURNAL] = "A|u||USER"
        it[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = "[]"
        it[ControlRecordKeys.payload(ControlKind.DEMAND)] = payload(listOf(request(RefreshIntent.FORCE_PREMIUM)))
    }
    private fun capEffects(c: Preferences) = writer.requiredEffects(input(cap), capIds, grant, read(before(cap)), read(c))
    private fun userEffects(c: Preferences) = writer.requiredEffects(input(user), userIds, grant, read(before(user)), read(c))

    @Test fun N0_positives() {
        assertTrue(atomic("N0_cap"), capEffects(capGood()))
        assertTrue(atomic("N0_user"), userEffects(userGood()))
    }
    // N1 markerKey: the rotated axis' own marker must be cleared, not the other axis' marker.
    @Test fun N1_wrongMarkerCleared() {
        assertFalse(atomic("N1_capWrongMarker"), capEffects(edit(capGood()) { it[MAY_CONTAIN_KRX] = true; it[MAY_CONTAIN_PREMIUM] = false }))
        assertFalse(atomic("N1_userWrongMarker"), userEffects(edit(userGood()) { it[MAY_CONTAIN_PREMIUM] = true; it[MAY_CONTAIN_KRX] = false }))
    }
    // N2 expectedEpoch axis choice: the rotated axis must hold its own fixed fresh UUID (not be removed or carry the other axis' value).
    @Test fun N2_rotatedEpochMissing() {
        assertFalse(atomic("N2_capEpochRemoved"), capEffects(edit(capGood()) { it.remove(KRX_EPOCH) }))
        assertFalse(atomic("N2_userEpochRemoved"), userEffects(edit(userGood()) { it.remove(USER_EPOCH) }))
    }
    // N3 expectedEpoch rotate choice: the non-target axis epoch is preserved (not removed as if rotated to an absent fresh value).
    @Test fun N3_nonTargetEpochRemoved() {
        assertFalse(atomic("N3_capUserEpochRemoved"), capEffects(edit(capGood()) { it.remove(USER_EPOCH) }))
        assertFalse(atomic("N3_userKrxEpochRemoved"), userEffects(edit(userGood()) { it.remove(KRX_EPOCH) }))
    }
    // N4 expectedMarker: the non-target axis marker is preserved (not cleared as if rotated).
    @Test fun N4_nonTargetMarkerCleared() {
        assertFalse(atomic("N4_capUserMarkerCleared"), capEffects(edit(capGood()) { it[MAY_CONTAIN_PREMIUM] = false }))
        assertFalse(atomic("N4_userKrxMarkerCleared"), userEffects(edit(userGood()) { it[MAY_CONTAIN_KRX] = false }))
    }
}
