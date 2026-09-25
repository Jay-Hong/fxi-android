package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.HoldRecoveryFixtures as F
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Claude-owned 5e-2 supplement (completion review r1, G2 — design §10 686 "R/N/L/HOLD/REQUEST/AUTH 결합"): the
 * RECOVER_INTENT writer combined with RECOVER_HOLD and an AUTH-bearing guard through one real FileStorage. X2 covers
 * R/N/L → intent → REQUEST consumption without HOLD or a guard; this file adds the HOLD/AUTH part in one fixed order:
 *  1. RECOVER_HOLD on the 5d literal base (HoldRecoveryFixtures.before) with an extra intent row: the stored result is the
 *     5d literal candidate (KRX rotated, journal A||k|CAPABILITY, guard floor handed over, AUTH kept) plus the intent row.
 *  2. RECOVER_INTENT for that intent (owner A, CAPABILITY, target k): target k ≠ the now-current rotated epoch → design
 *     L414 (C0c): owner, both epochs and markers preserved, the exact source journal is already present and is not added
 *     again (L438), one new REQUEST for the current owner with the axis lower bound (L421: CAPABILITY →
 *     FORCE_ENTITLEMENTS), only the intent row removed. The HOLD writer's REQUEST, the handed-over guard original (AUTH and
 *     floor) and the other rows stay byte-for-byte.
 * Both lifecycle refs stay retained (release denied) and 2c keeps both Applied rows. The reverse order and the AUTH
 * transition writer itself are outside this file. The implementation thread reads but does not edit this file.
 */
class RecoverIntentSupplementHoldAuthCombinationContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("hold-auth combination cleanup", 30000) { opened.reversed().forEach { it.close() } } }
    private suspend fun disk(file: File) = file.source().buffer().use { PreferencesSerializer.readFrom(it) }

    private val intentKey = ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val holdKey = ControlRecordKeys.payload(ControlKind.HOLD)
    private val source = ControlObligationFixtures.node("""{"id":"r","sessionId":"session","ownerUid":"A","axis":"CAPABILITY","targetEpoch":"k"}""")
    private val intentClosure = HoldRecoveryClosure.AfterRestart(source, F.executor, "previous-tracker", true, true)
    private fun rows(p: Preferences, kind: ControlKind) =
        F.read(p).arrays.getValue(kind).entries.map { (it as ControlEntryRead.Interpreted).original.toPayloadEntry() }

    @Test fun XHA_holdThenIntentWithAuthGuard() = runReleaseTest {
        val holdInput = F.input()
        val seeded = F.before(holdInput).toMutablePreferences().apply { this[intentKey] = F.payload(listOf(source)) }.toPreferences()
        F.assertFixture(holdInput, seeded)
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("XHA seed") { s.data.updateData { seeded } }

        // 1. RECOVER_HOLD — the 5d literal candidate, with the intent row untouched.
        val hold = s.control.prepareRecoverHold(holdInput, LifecycleOrderSource(F.life, 21))
        val holdIds = ((hold.body as ControlCommandBody.Lifecycle).input.recoverHold)!!.ids
        assertTrue(F.atomic("XHA_hold"), controlTestTimeout("XHA hold") { s.control.execute(hold, F.context(holdInput)) } is ControlStoreResult.Confirmed)
        val afterHold = F.stripBarrier(disk(file))
        val expectedHold = F.candidate(hold, holdIds).toMutablePreferences().apply { this[intentKey] = F.payload(listOf(source)) }.toPreferences()
        assertEquals(F.atomic("XHA_holdLiteral"), expectedHold, afterHold)
        val rotated = checkNotNull(holdIds.epochs.capability)

        // 2. RECOVER_INTENT — C0c on the post-HOLD namespace.
        val intentInput = RecoverIntentInput(source, FenceV1("A", "u", rotated), F.binding, intentClosure)
        val intentContext = AttemptContext("A", 3, F.life, false, false,
            intentRecovery = HoldRecoveryRuntime(F.binding, 5, true, emptySet(), intentClosure))
        val intent = s.control.prepareRecoverIntent(intentInput, LifecycleOrderSource(F.life, 22))
        val intentIds = ((intent.body as ControlCommandBody.Lifecycle).input.recoverIntent)!!.ids
        assertEquals(F.atomic("XHA_noFreshForC0c"), RecoveryFreshEpochs(null, null), intentIds.epochs)
        assertTrue(F.atomic("XHA_intent"), controlTestTimeout("XHA intent") { s.control.execute(intent, intentContext) } is ControlStoreResult.Confirmed)
        val afterIntent = F.stripBarrier(disk(file))

        assertEquals(F.atomic("XHA_owner"), "A", afterIntent[OWNER_UID])
        assertEquals(F.atomic("XHA_userEpoch"), afterHold[USER_EPOCH], afterIntent[USER_EPOCH])
        assertEquals(F.atomic("XHA_krxEpoch"), rotated, afterIntent[KRX_EPOCH])
        assertEquals(F.atomic("XHA_premiumMarker"), afterHold[MAY_CONTAIN_PREMIUM], afterIntent[MAY_CONTAIN_PREMIUM])
        assertEquals(F.atomic("XHA_krxMarker"), false, afterIntent[MAY_CONTAIN_KRX])
        assertEquals(F.atomic("XHA_journalNotDuplicated"), "A||k|CAPABILITY", afterIntent[PURGE_JOURNAL])
        assertEquals(F.atomic("XHA_intentRemoved"), "[]", afterIntent[intentKey])
        assertEquals(F.atomic("XHA_holdStillGone"), afterHold[holdKey], afterIntent[holdKey])
        val newRequest = DemandAuthFixtures.request(id = intentIds.requestId, owner = "A", binding = 3, origin = F.life,
            intent = RefreshIntent.FORCE_ENTITLEMENTS, order = 23)
        assertEquals(F.atomic("XHA_demandRows"), rows(afterHold, ControlKind.DEMAND) + newRequest.toPayloadEntry(), rows(afterIntent, ControlKind.DEMAND))
        assertEquals(F.atomic("XHA_guardOriginal"), F.row(afterHold, ControlKind.DEMAND, "g").toPayloadEntry(), F.row(afterIntent, ControlKind.DEMAND, "g").toPayloadEntry())
        assertEquals(F.atomic("XHA_holdRequestOriginal"), F.row(afterHold, ControlKind.DEMAND, holdIds.requestId).toPayloadEntry(),
            F.row(afterIntent, ControlKind.DEMAND, holdIds.requestId).toPayloadEntry())
        val kept = F.read(afterIntent)
        assertNotNull(F.atomic("XHA_holdApplied"), ControlAppliedEvidence.own(kept, hold))
        assertNotNull(F.atomic("XHA_intentApplied"), ControlAppliedEvidence.own(kept, intent))

        // Both lifecycle refs stay retained; 2c keeps both Applied rows and the combined obligations.
        for (c in listOf(hold, intent)) assertTrue(F.eligible("XHA_releaseDenied"),
            controlTestTimeout("XHA release") { s.control.releaseAfterConsumption(c) } is ControlCommandReleaseResult.Rejected)
        controlTestTimeout("XHA close") { s.close() }
        val next = open(file); next.raw()
        controlTestTimeout("XHA 2c") { next.control.reclaimPreviousLifetimeEvidence() }
        val reclaimed = disk(file)
        assertNotNull(F.retry("XHA_2cHold"), ControlAppliedEvidence.own(F.read(reclaimed), hold))
        assertNotNull(F.retry("XHA_2cIntent"), ControlAppliedEvidence.own(F.read(reclaimed), intent))
        assertEquals(F.retry("XHA_2cDemand"), afterIntent[demandKey], reclaimed[demandKey])
        assertEquals(F.retry("XHA_2cJournal"), "A||k|CAPABILITY", reclaimed[PURGE_JOURNAL])
    }
}
