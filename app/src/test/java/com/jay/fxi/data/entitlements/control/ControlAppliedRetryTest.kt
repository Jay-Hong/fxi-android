package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.edit
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.recovery
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.request
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControlAppliedRetryTest {
    @get:Rule val folder=TemporaryFolder()
    private val key=ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    @Test fun interpretableMismatchCannotBecomeConfirmationOrApplication()=runBlocking {
        val o=ControlStoreTestStorage(File(folder.root,"mismatch.preferences_pb"))
        try{
            o.seed();val c=o.control.prepare(o.control.addition(ControlKind.RECOVERY_INTENT){id->literal(recovery);set("id",ControlScalar.Text(id))})
            assertTrue(o.control.execute(c) is ControlStoreResult.Confirmed)
            o.data.edit {it[key]=it[key]!!.replace(c.ownerTrackingLifetimeId.value,OwnerTrackingLifetimeId.issue().value)}
            val before=o.raw();val writes=o.storage.writes
            NamespaceSettlementFixtures.negative(o.control.execute(c),ConflictReason.CommandEvidenceMismatch)
            assertEquals(before,o.raw());assertEquals(writes,o.storage.writes)
        }finally{o.close()}
    }
    @Test fun ownOpaqueEvidenceRequiresRecovery() = assertOwnEvidenceRequiresRecovery("opaque")
    @Test fun ownDuplicateEvidenceRequiresRecovery() = assertOwnEvidenceRequiresRecovery("duplicate")

    private fun assertOwnEvidenceRequiresRecovery(variant: String) = runBlocking {
        val o=ControlStoreTestStorage(File(folder.root,"$variant.preferences_pb"))
        try{
            o.seed();val c=o.control.prepare(o.control.addition(ControlKind.RECOVERY_INTENT){id->literal(recovery);set("id",ControlScalar.Text(id))})
            assertTrue(o.control.execute(c) is ControlStoreResult.Confirmed)
            o.data.edit { val text=it[key]!!;it[key]=if(variant=="opaque") text.replace("\"version\":2","\"version\":3") else text.dropLast(1)+","+text.drop(1) }
            val before=o.raw();NamespaceSettlementFixtures.negative(o.control.execute(c),RecoveryReason.UninterpretableMetadata)
            assertEquals(before,o.raw())
        }finally{o.close()}
    }

    @Test fun opaqueEvidenceBlocksNewEffectsButKeepsIndependentConfirmation() = assertOpaqueMetadataBlocksNewEffects(ControlPayloadKey.COMMAND_EVIDENCE)
    @Test fun opaqueFenceBlocksNewEffectsButKeepsIndependentConfirmation() = assertOpaqueMetadataBlocksNewEffects(ControlPayloadKey.SCOPE_FENCE)

    private fun assertOpaqueMetadataBlocksNewEffects(payload: ControlPayloadKey) = runBlocking {
        val o=ControlStoreTestStorage(File(folder.root,"$payload.preferences_pb"))
        try{
            o.seed(demand="[$request]");o.data.edit{it[ControlRecordKeys.payload(payload)]="[null]"}
            val c=o.control.prepare(o.control.addition(ControlKind.RECOVERY_INTENT){id->literal(recovery);set("id",ControlScalar.Text(id))})
            val before=o.raw();NamespaceSettlementFixtures.negative(o.control.execute(c),RecoveryReason.UninterpretableMetadata)
            assertEquals(before,o.raw())
            val n=o.control.prepare(o.control.edit(ControlKind.DEMAND,node(request)) {})
            val result=o.control.execute(n) as ControlStoreResult.Confirmed
            assertTrue(result.snapshot.record.hasUninterpretableMetadata);assertEquals(before,o.raw())
        }finally{o.close()}
    }

    @Test fun rotationEvidenceLifetimeMismatch() = assertRotationEvidenceMismatch("lifetime")
    @Test fun rotationEvidenceKindMismatch() = assertRotationEvidenceMismatch("kind")
    @Test fun rotationEvidenceSealOrderMismatch() = assertRotationEvidenceMismatch("seals")
    @Test fun rotationEvidenceDemandMismatch() = assertRotationEvidenceMismatch("demand")

    private fun assertRotationEvidenceMismatch(variant: String) = runBlocking {
        val o=ControlStoreTestStorage(File(folder.root,"rotation-$variant.preferences_pb"))
        try{
            val spec=NamespaceSettlementFixtures.input(targets=listOf(node(NamespaceSettlementFixtures.user),node(NamespaceSettlementFixtures.krx)))
            o.data.updateData{NamespaceSettlementFixtures.raw(seals="[${NamespaceSettlementFixtures.user},${NamespaceSettlementFixtures.krx}]")}
            val c=NamespaceSettlementFixtures.command(o,spec)
            assertTrue(o.control.execute(c,NamespaceSettlementFixtures.context) is ControlStoreResult.Confirmed)
            val t=ControlCommandTracking.forOwner(o.owner).findPrepared(c)!!
            t.expectedApplied=null // isolate each immutable ref linkage from the redundant live candidate check
            o.data.edit {it[key]=when(variant){
                "lifetime"->it[key]!!.replace(c.ownerTrackingLifetimeId.value,OwnerTrackingLifetimeId.issue().value)
                "kind"->"""[{"version":2,"commandId":"${c.id}","ownerTrackingLifetimeId":"${c.ownerTrackingLifetimeId.value}","kind":"MUTATIONS","targets":[{"index":0,"kind":"SEAL","id":"s","joined":false,"written":true}]}]"""
                "seals"->it[key]!!.replace("[\"s\",\"c\"]","[\"c\",\"s\"]")
                else->it[key]!!.replace(spec.demandId,"other-demand")
            }}
            assertFalse((ControlRecordReader().read(o.raw()) as ControlRecordRead.Supported).hasUninterpretableMetadata)
            val before=o.raw();NamespaceSettlementFixtures.negative(o.control.execute(c),ConflictReason.CommandEvidenceMismatch)
            assertEquals(before,o.raw())
        }finally{o.close()}
    }

}
