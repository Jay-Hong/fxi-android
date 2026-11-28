package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.purge.PurgeControlSnapshotRead.Journal
import com.jay.fxi.data.entitlements.purge.PurgeJournalCodec
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeletionAuthorizationSourceTest {
    @get:Rule val temp = TemporaryFolder()
    private val store by lazy { DeletionRecordStore({ temp.root }, DeletionAdmissionStore()) }
    private val source by lazy { DeletionAuthorizationSource(store) }
    private val owner = AuthIdentityFence("A", 1)
    private val plan = DeletionRetirement(DeletionEpochs("u1", "c1"), DeletionEpochs("u2", "c2"))
    private fun journal(vararg rows: String): Journal =
        if (rows.isEmpty()) Journal.Absent else Journal.Present(rows.joinToString("\n"), rows.map(PurgeJournalCodec::decode))
    private val before = RetirementObservation("A", plan.before, journal())
    private val row = PurgeJournalCodec.encode(plan.obligation("A"))
    private val after = RetirementObservation("A", plan.after, journal(row))
    private val target = "datastore:fxi_user_intent"

    private suspend fun server(): DeletionRecord {
        store.read()
        val q = checkNotNull(store.begin(owner))
        assertNull(issue(q))
        val sent = checkNotNull(store.markSending(q, owner))
        return checkNotNull(store.serverDeleted(sent, sent))
    }

    private suspend fun issue(record: DeletionRecord, evidence: RetirementObservation = after,
        obligation: String = row, scope: PurgeScope = PurgeScope.USER, id: String = target) =
        source.issue(record, obligation, scope, id) { evidence }

    private suspend fun bound(): DeletionRecord {
        val reserved = checkNotNull(store.reserve(server(), plan, before))
        assertNull(issue(reserved))
        return checkNotNull(store.confirmRotation(reserved, after))
    }

    @Test fun reservationBindsOneNewObligationAcrossReopen() = runBlocking {
        val s = server()
        assertNull(store.reserve(s, plan, before.copy(ownerUid = "B")))
        assertNull(store.reserve(s, plan, before.copy(epochs = plan.after)))
        assertNull(store.reserve(s, plan, before.copy(journal = journal(row))))
        val reserved = checkNotNull(store.reserve(s, plan, before))
        val reader = DeletionRecordStore({ temp.root }, DeletionAdmissionStore())
        val reopened = (reader.read() as DeletionSnapshot.Active).record
        assertEquals(reserved, reopened)
        assertNull(reader.confirmRotation(reopened, before))
        assertNull(reader.confirmRotation(reopened, after.copy(journal = journal(row, row))))
        val confirmed = checkNotNull(reader.confirmRotation(reopened, after))
        assertEquals(journalReference(row), confirmed.journalReference)
        assertNotNull(DeletionAuthorizationSource(reader).issue(confirmed, row, PurgeScope.USER, target) { after })
    }

    @Test fun ownerOperationPhaseEpochAndIndividualJournalRowMustAllMatch() = runBlocking {
        val s = bound()
        val wrongOp = s.copy(operationId = java.util.UUID.randomUUID().toString())
        assertNull(issue(wrongOp))
        assertNull(issue(s, after.copy(ownerUid = "B")))
        assertNull(issue(s, after.copy(ownerUid = null)))
        assertNull(issue(s, after.copy(epochs = plan.before)))
        assertNull(issue(s, after.copy(epochs = plan.after.copy(capability = "c3"))))
        for (other in listOf(row.replace("u1", "u0"), row.replace("A|", "|"), row.substringBeforeLast('|'),
            row.replace("ACCOUNT_DELETION", "SIGN_OUT"), row.replace("ACCOUNT_DELETION", "IDENTITY_SWITCH"),
            row.replace("ACCOUNT_DELETION", "CAPABILITY_REVOKE"), row.replace("ACCOUNT_DELETION", "UNKNOWN"))) {
            assertNull(issue(s, after.copy(journal = journal(other))))
            assertNull(issue(s, obligation = other))
        }
        assertNull(issue(s, scope = PurgeScope.CAPABILITY))
        assertNotNull(issue(s, scope = PurgeScope.CAPABILITY, id = "memory:graph_recorder"))
        assertNull(issue(s, id = "datastore:fxi_access_epoch"))
        val ticket = checkNotNull(issue(s))
        val f = checkNotNull(store.startSdk(s, owner))
        assertFalse(source.consume(ticket, { after }) { fail("stale S") })
        assertNotNull(issue(f))
        val d = checkNotNull(store.sdkResult(f, f, true))
        assertNotNull(issue(d))
    }

    @Test fun consumeRechecksLiveEvidenceAndRejectsReplayAfterClear() = runBlocking {
        val s = bound()
        var calls = 0
        val stale = checkNotNull(issue(s))
        assertFalse(source.consume(stale, { after.copy(journal = journal()) }) { calls++ })
        val live = checkNotNull(issue(s))
        assertTrue(source.consume(live, { after }) { calls++ })
        assertFalse("S104_AUTH_REPLAY", source.consume(live, { after }) { calls++ })
        val pending = checkNotNull(issue(s))
        // Simulate a later finalizer's confirmed empty without adding a production clear API.
        val empty = DeletionFileSerializer.defaultValue
        File(temp.root, "account_deletion/v1.pb").outputStream().use { DeletionFileSerializer.writeTo(empty, it) }
        assertFalse(source.consume(pending, { after }) { calls++ })
        assertNull(issue(s))
        assertEquals(1, calls)
    }
}
