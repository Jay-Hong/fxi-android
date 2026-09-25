package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Claude-owned 5e-2 supplement (measure ledger r2 review, J9): the facade's observed-then-lost path (4b) with a real
 * RECOVER_INTENT command. The intent lands but its return fails; the retry confirms and records that its own Applied row
 * was observed; a later snapshot without that row must yield RecoveryRequired(CommandEvidenceLost) with no write and no
 * re-application (the removed source is not recreated). The existing owner tests use the REMOVE_EMPTY_GUARD descriptor.
 * This is a regression check of the intent connection, not a mutant kill. The implementation thread reads but does not edit this file.
 */
class RecoverIntentSupplementObservationLossContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("intent observation-loss cleanup", 30000) { opened.reversed().forEach { it.close() } } }
    private suspend fun disk(file: File) = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    private fun retry(id: String) = ControlLifecycleEvidenceFixtures.retry(id)

    private val newLife = LifetimeId("new-life")
    private val executor = SettlementExecutor("A", 3, newLife)
    private val binding = LifecycleBinding(executor, IdentityV1("A", 2), 1, "binding-start")
    private val source = ControlObligationFixtures.node("""{"id":"r","sessionId":"session","ownerUid":"A","axis":"CAPABILITY","targetEpoch":"k"}""")
    private val closure = HoldRecoveryClosure.AfterRestart(source, executor, "old-tracking", true, true)
    private val input = RecoverIntentInput(source, FenceV1("A", "u", "k"), binding, closure)
    private val context = AttemptContext("A", 3, newLife, false, false,
        intentRecovery = HoldRecoveryRuntime(binding, 5, true, emptySet(), closure))
    private val intentKey = ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)
    private val evidenceKey = ControlLifecycleEvidenceFixtures.evidenceKey
    private fun record(): Preferences = ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
        this[OWNER_UID] = "A"; this[USER_EPOCH] = "u"; this[KRX_EPOCH] = "k"
        this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
        this[intentKey] = "[${source.toPayloadEntry().fields}]"
    }.toPreferences()
    private fun intents(p: Preferences) = FloorGuardFixtures.read(p).arrays.getValue(ControlKind.RECOVERY_INTENT).entries

    @Test fun J9_observedThenLostIsRecoveryWithoutWrite() = runReleaseTest {
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("J9 seed") { s.data.updateData { record() } }
        val tracking = ControlCommandTracking.forOwner(s.owner)
        val c = s.control.prepareRecoverIntent(input, LifecycleOrderSource(newLife, 21))
        s.storage.afterScope = true
        val failed = controlTestTimeout("J9 failure") { s.control.execute(c, context) }
        assertTrue(retry("J9_unconfirmed"), failed is ControlStoreResult.Unconfirmed)
        assertTrue(atomic("J9_landedApplied"), disk(file)[evidenceKey]?.contains(c.id) == true)
        val confirmed = controlTestTimeout("J9 confirm") { s.control.execute(c, context) }
        assertTrue(retry("J9_confirmed"), confirmed is ControlStoreResult.Confirmed)
        assertTrue(retry("J9_observed"), checkNotNull(tracking.findPrepared(c)).observedApplied.get())
        // A later snapshot no longer carries this command's Applied row; nothing else changes.
        controlTestTimeout("J9 lose") { s.data.updateData { cur ->
            cur.toMutablePreferences().apply { this[evidenceKey] = checkNotNull(ControlLifecycleEvidenceFixtures.raw()[evidenceKey]) }.toPreferences()
        } }
        val lostSnapshot = disk(file)
        assertFalse(lostSnapshot[evidenceKey]?.contains(c.id) == true)
        val result = controlTestTimeout("J9 lost") { s.control.execute(c, context) }
        assertTrue(retry("J9_recovery"), result is ControlStoreResult.RecoveryRequired)
        assertEquals(retry("J9_reason"), RecoveryReason.CommandEvidenceLost, (result as ControlStoreResult.RecoveryRequired).reason)
        assertEquals(atomic("J9_noWrite"), lostSnapshot, disk(file))
        assertTrue(atomic("J9_sourceNotRecreated"), intents(disk(file)).isEmpty())
    }
}
