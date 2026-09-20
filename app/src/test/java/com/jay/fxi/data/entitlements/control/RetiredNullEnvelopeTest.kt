package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.READ_BARRIER
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.StoreOp
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.spec
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.raw
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.before
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.executor
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.context
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.life
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.target
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.transition
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.command
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.witness
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.read
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.landed
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.decide
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.negative
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.blob
import org.junit.Assert.*
import org.junit.Test

class RetiredNullEnvelopeTest : RetiredNullOwnerBase() {
    private fun sized(kind: ControlPayloadKey, bytes: Int, s: RetiredNullSettlement, c: CommandRef): Preferences {
        val current = raw(s)
        val final = landed(c, s)
        val key = ControlRecordKeys.payload(kind)
        val seed = when (kind) {
            ControlPayloadKey.SEAL -> NamespaceSettlementFixtures.krx.replace("\"c\"", "\"PAD\"")
            ControlPayloadKey.DEMAND -> ControlObligationFixtures.request.replace("\"d\"", "\"PAD\"")
            ControlPayloadKey.COMMAND_EVIDENCE -> """{"version":2,"commandId":"PAD","ownerTrackingLifetimeId":"${c.ownerTrackingLifetimeId.value}","kind":"MUTATIONS","targets":[{"index":0,"kind":"SEAL","id":"other","joined":false,"written":true}]}"""
            else -> error("not an L boundary payload")
        }
        val expected = final[key]!!.dropLast(1) + (if (final[key] == "[]") "" else ",") + seed + "]"
        val padBytes = bytes - expected.toByteArray(Charsets.UTF_8).size + 3
        val row = seed.replace("PAD", "한" + "x".repeat(padBytes - 3))
        assertEquals(bytes, expected.replace("PAD", "한" + "x".repeat(padBytes - 3)).toByteArray(Charsets.UTF_8).size)
        return current.toMutablePreferences().apply {
            this[key] = this[key]!!.dropLast(1) + (if (this[key] == "[]") "" else ",") + row + "]"
        }
    }
    private suspend fun boundary(kind: ControlPayloadKey, bytes: Int) {
        val s = spec()
        val c = registerL(s); val source = sized(kind, bytes, s, c)
        seedL(s, source); val saved = disk(); val writes = o.storage.writes
        val result = executeL(c, context.copy(ownerUid = s.executor.ownerUid))
        if (bytes == 65536) {
            successL(result, ConfirmedEffect.AppliedThisAttempt)
            assertEquals(65536, disk()[ControlRecordKeys.payload(kind)]!!.toByteArray(Charsets.UTF_8).size)
        } else {
            NamespaceSettlementFixtures.negative(result, if (kind == ControlPayloadKey.DEMAND) RecoveryReason.UnreadableRecord else RejectionReason.TooLarge(kind, bytes, 65536))
            assertEquals(saved, disk()); assertEquals(writes, o.storage.writes)
            assertNull(history(c).firstConfirmDiscontinuityCount)
        }
        assertFalse(c in tracking.executing)
    }
    private suspend fun depth(kind: ControlPayloadKey, depth: Int) {
        val key = ControlRecordKeys.payload(kind)
        val deep = "[".repeat(depth - 2) + "0" + "]".repeat(depth - 2)
        val source = raw().toMutablePreferences().apply {
            this[key] = if (kind == ControlPayloadKey.SEAL) "[${RetiredNullFixtures.nullUser},$deep]" else "[$deep]"
        }
        val reason = if (depth > 64) RecoveryReason.UnreadableRecord else
            if (kind == ControlPayloadKey.COMMAND_EVIDENCE) RecoveryReason.UninterpretableMetadata else RecoveryReason.UninterpretableObligations
        deniedL(source = source, reason = reason)
    }
    @Test fun G20_current_SEAL_65536() = runReleaseTest { boundary(ControlPayloadKey.SEAL, 65536) }
    @Test fun G20_current_SEAL_65537() = runReleaseTest { boundary(ControlPayloadKey.SEAL, 65537) }
    @Test fun G20_current_DEMAND_65536() = runReleaseTest { boundary(ControlPayloadKey.DEMAND, 65536) }
    @Test fun G20_current_DEMAND_65537() = runReleaseTest { boundary(ControlPayloadKey.DEMAND, 65537) }
    @Test fun G20_current_COMMAND_EVIDENCE_65536() = runReleaseTest { boundary(ControlPayloadKey.COMMAND_EVIDENCE, 65536) }
    @Test fun G20_current_COMMAND_EVIDENCE_65537() = runReleaseTest { boundary(ControlPayloadKey.COMMAND_EVIDENCE, 65537) }
    @Test fun G20_SEAL_depth64() = runReleaseTest { depth(ControlPayloadKey.SEAL, 64) }
    @Test fun G20_SEAL_depth65() = runReleaseTest { depth(ControlPayloadKey.SEAL, 65) }
    @Test fun G20_DEMAND_depth64() = runReleaseTest { depth(ControlPayloadKey.DEMAND, 64) }
    @Test fun G20_DEMAND_depth65() = runReleaseTest { depth(ControlPayloadKey.DEMAND, 65) }
    @Test fun G20_COMMAND_EVIDENCE_depth64() = runReleaseTest { depth(ControlPayloadKey.COMMAND_EVIDENCE, 64) }
    @Test fun G20_COMMAND_EVIDENCE_depth65() = runReleaseTest { depth(ControlPayloadKey.COMMAND_EVIDENCE, 65) }
}
