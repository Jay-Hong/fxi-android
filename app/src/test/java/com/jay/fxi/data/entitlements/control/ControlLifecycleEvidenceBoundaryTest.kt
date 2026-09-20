package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.control.ControlLifecycleEvidenceFixtures as F
import org.junit.Assert.*
import org.junit.Test

class ControlLifecycleEvidenceBoundaryTest {
    @Test fun G01a_schemaOne() {
        val read = F.read(F.raw(schema = 1))
        assertFalse(read.hasUninterpretable)
        assertFalse(read.hasUninterpretableMetadata)
        assertNotNull(F.eligible("G01a"), ControlLifecycleBoundary.recordProblem(read))
    }
    @Test fun G01b_actualSchemaType() {
        val raw = F.raw().toMutablePreferences().apply { this[longPreferencesKey(ControlRecordKeys.SCHEMA)] = 2L }
        assertEquals(2L, raw.asMap().entries.single { it.key.name == ControlRecordKeys.SCHEMA }.value)
        assertTrue(F.eligible("G01b"), ControlRecordReader().read(raw) is ControlRecordRead.Unreadable)
    }
    @Test fun G01c_opaqueObligation() {
        val read = F.read(F.raw(hold = "[{}]"))
        assertEquals(2, read.schemaVersion)
        assertFalse(read.hasUninterpretableMetadata)
        assertNotNull(F.eligible("G01c"), ControlLifecycleBoundary.recordProblem(read))
    }
    @Test fun G01d_opaqueMetadata() {
        val read = F.read(F.raw(evidence = "[{}]"))
        assertEquals(2, read.schemaVersion)
        assertFalse(read.hasUninterpretable)
        assertNotNull(F.eligible("G01d"), ControlLifecycleBoundary.recordProblem(read))
    }
    @Test fun G01d2_nonemptyFence() {
        val raw = F.raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)] = "[{}]" }
        val read = F.read(raw)
        assertFalse(read.hasUninterpretable)
        assertFalse((read.metadata as ControlMetadataRead.V2).evidence.hasUninterpretable)
        assertNotNull(F.eligible("G01d2"), ControlLifecycleBoundary.recordProblem(read))
    }
    @Test fun G02a_absentIdBoundary() {
        val read = F.read()
        assertFalse(read.blocksProtectedAdmission)
        assertEquals(emptyList<Any>(), read.locations("g"))
        assertFalse(F.eligible("G02a"), ControlLifecycleBoundary.targetExists(read.locations("g")))
    }
    @Test fun G02b_wrongKindBoundary() {
        val raw = F.raw(hold = "[${ControlObligationFixtures.hold}]")
        val found = F.read(raw).locations("h").single()
        assertEquals(ControlKind.HOLD, found.first)
        assertTrue(found.second is ControlEntryRead.Interpreted)
        assertFalse(F.eligible("G02b"), ControlLifecycleBoundary.targetKindMatches(found.first, ControlKind.DEMAND))
    }
    @Test fun G02c_changedPreimageBoundary() {
        assertEquals("d", (ControlObligations.read(ControlKind.DEMAND, F.stronger) as ControlEntryRead.Interpreted).value.id)
        assertEquals(F.request.toPayloadEntry().fields - "intent", F.stronger.toPayloadEntry().fields - "intent")
        assertFalse(F.eligible("G02c"), ControlLifecycleBoundary.targetPreimageMatches(F.stronger, F.request))
    }
    @Test fun G02d_duplicateAcrossArrays() {
        val raw = F.raw(demand = "[${ControlObligationFixtures.request}]", hold = "[${ControlObligationFixtures.hold.replace("\"h\"", "\"d\"")}]")
        val read = F.read(raw)
        assertEquals(2, read.locations("d").size)
        assertTrue(F.eligible("G02d"), read.locations("d").all { it.second is ControlEntryRead.Uninterpretable })
    }

    private fun current(id: String, context: AttemptContext = F.context, raw: Preferences = F.raw()) {
        assertNull(ControlLifecycleBoundary.rawProblem(raw))
        assertTrue(ControlLifecycleBoundary.fence(raw, F.fence))
        assertFalse(F.read(raw).blocksProtectedAdmission)
        val differences = listOf(context.ownerUid != "A", context.binding != 3L, context.originLifetimeId != F.origin,
            context.signOutOpen, context.identityPersistencePending, raw[TEARDOWN_OWED_FOR] != null)
        assertEquals(1, differences.count { it })
        assertNotNull(F.eligible(id), ControlLifecycleBoundary.current(F.executor, context, raw))
    }
    @Test fun G03a_owner() = current("G03a", F.context.copy(ownerUid = "B"))
    @Test fun G03b_binding() = current("G03b", F.context.copy(binding = 4))
    @Test fun G03c_origin() = current("G03c", F.context.copy(originLifetimeId = LifetimeId("other")))
    @Test fun G03d_signOut() = current("G03d", F.context.copy(signOutOpen = true))
    @Test fun G03e_identityPending() = current("G03e", F.context.copy(identityPersistencePending = true))
    @Test fun G03f_teardown() = current("G03f", raw = F.raw().toMutablePreferences().apply { this[TEARDOWN_OWED_FOR] = "A" })

    private fun fence(id: String, raw: Preferences) {
        assertNull(ControlLifecycleBoundary.rawProblem(raw))
        assertNull(ControlLifecycleBoundary.current(F.executor, F.context, raw))
        assertEquals(1, listOf(raw[OWNER_UID] != "A", raw[USER_EPOCH] != "u", raw[KRX_EPOCH] != "k").count { it })
        assertFalse(F.eligible(id), ControlLifecycleBoundary.fence(raw, F.fence))
    }
    @Test fun G04a_owner() = fence("G04a", F.raw().toMutablePreferences().apply { this[OWNER_UID] = "B" })
    @Test fun G04b_user() = fence("G04b", F.raw().toMutablePreferences().apply { this[USER_EPOCH] = "next-u" })
    @Test fun G04c_capability() = fence("G04c", F.raw().toMutablePreferences().apply { this[KRX_EPOCH] = "next-k" })
    @Test fun G05a_evidenceCollision() {
        val read = F.read(F.raw(evidence = "[${F.wire(command = "lc")} ]"))
        assertFalse(read.blocksProtectedAdmission)
        assertTrue(read.arrays.getValue(ControlKind.SEAL).entries.isEmpty())
        assertFalse(F.eligible("G05a"), ControlLifecycleBoundary.commandIdAvailable(read, "lc"))
    }
    @Test fun G05b_sealOperationCollision() {
        val raw = F.raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.SEAL)] = "[${ReclamationFixtures.settled(operation = "lc")}]" }
        val read = F.read(raw)
        assertFalse(read.blocksProtectedAdmission)
        assertTrue((read.metadata as ControlMetadataRead.V2).evidence.entries.isEmpty())
        assertFalse(F.eligible("G05b"), ControlLifecycleBoundary.commandIdAvailable(read, "lc"))
    }
    @Test fun G05c_newIdCollision() {
        val read = F.read(F.raw(hold = "[${ControlObligationFixtures.hold}]"))
        assertFalse(read.blocksProtectedAdmission)
        assertEquals(ControlKind.HOLD, read.locations("h").single().first)
        assertFalse(F.eligible("G05c"), ControlLifecycleBoundary.createIdAvailable(read, "h"))
    }
    @Test fun positiveCommonBoundaries() {
        val read = F.read(F.raw(demand = "[${ControlObligationFixtures.request}]"))
        assertNull(ControlLifecycleBoundary.recordProblem(read))
        assertNull(ControlLifecycleBoundary.preimage(read, F.replaceRequest))
        assertNull(ControlLifecycleBoundary.current(F.executor, F.context, read.original))
        assertTrue(ControlLifecycleBoundary.fence(read.original, F.fence))
        assertTrue(ControlLifecycleBoundary.commandIdAvailable(read, "lc"))
        assertTrue(ControlLifecycleBoundary.createIdAvailable(read, "new"))
    }
    @Test fun G07_emptyDescriptorOperationId() {
        val input = F.descriptor(id = "")
        val command = F.command(input)
        assertEquals("", command.id)
        assertEquals("", input.operationId)
        assertEquals(LifecycleTransition.REMOVE_EMPTY_GUARD, input.transition)
        assertTrue(input.requiredUnchanged.isEmpty())
        assertNull(input.namespace)
        assertNull(input.executor)
        val fixed = input.targets.single()
        assertEquals(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REMOVE), fixed.target)
        assertEquals(LifecycleRole.GUARD, fixed.role)
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement(ControlObligationFixtures.emptyGuard),
            checkNotNull(fixed.before).toPayloadEntry().fields)
        assertNull(fixed.after)
        val confirmation = ControlLifecycleConfirmation(ControlPayloadCodec())
        val valid = F.descriptor(id = "nonempty", transition = input.transition, targets = input.targets)
        assertTrue(confirmation.validDescriptor(valid))
        assertFalse(F.eligible("G07"), confirmation.validDescriptor(input))
    }
}
