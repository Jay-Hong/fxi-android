package com.jay.fxi.ui.alert

import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.alerts.SourceAlertDraft
import com.jay.fxi.data.alerts.SourceAlertEdit
import com.jay.fxi.data.alerts.SourceAlertRowPolicy
import com.jay.fxi.data.alerts.SourceAlertSaveOutcome
import com.jay.fxi.data.alerts.SourceAlertSaveRequest
import com.jay.fxi.data.alerts.SourceAlertSettingsState
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.local.DataStoreLastSelectedSourceStore
import com.jay.fxi.data.local.LastSelectedSourceMemory
import com.jay.fxi.data.local.LastSelectedSourceStore
import com.jay.fxi.data.local.SourceSelectionSnapshot
import com.jay.fxi.data.local.userIntentDataStore
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.SourceAlertSetting
import com.jay.fxi.service.NotificationCapability
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S8-5b D2 (`R4c/S8/s8_5b_contract.agreed.r1.md` §3 D2) on the emulator. The production section takes its helper from
 * `rememberNotificationPermissionAction()`, whose OS port is the Android one; this test puts the section's Main holder over the
 * real helper and lane with a counting OS port, under the real dialog host, with the production adapter's two arguments
 * (`S7E5B2B1StructureTest` T-* lock that adapter in the section). It counts OS launches, opens and saves through an allowed
 * request, a refusal from the drawn dialog, a settings return, a busy second tap and a grant after the mount left; opening an
 * edit, switching and deleting ask nothing.
 *
 * The memory half runs the holder over the real [LastSelectedSourceMemory] and the real Android DataStore store. Each "cold"
 * memory is made anew over the process's one DataStore (the file's process cache is the store's own, as for the bank memory):
 * after A adds upbit and edits that row to bithumb, a new memory seeds A's next add with upbit; B gets B's own; each later add
 * replaces it; a success that arrives after its editor was dismissed is not remembered and nothing is sent twice.
 */
@RunWith(AndroidJUnit4::class)
class S85BTetherAlertPermissionMemoryTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val a = AuthIdentityFence("s85b-d2-a", 1L)
    private val b = AuthIdentityFence("s85b-d2-b", 1L)
    private val stamp = Instant.parse("2026-11-22T00:00:00Z")
    private val sources = SourceAlertRowPolicy.EXCHANGES.sources(SourceAlertRowPolicy.SETTINGS_ASSET)
    private val scopes = mutableListOf<CoroutineScope>()

    @Before @After fun clearKeys() = runBlocking {
        context.userIntentDataStore.edit { preferences ->
            for (uid in listOf(a.uid, b.uid)) preferences.remove(stringPreferencesKey("last_selected_source_uid_$uid"))
        }
        Unit
    }

    @After fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun row(id: Int, uid: String, source: String = "upbit", threshold: Double = 1400.0) =
        SourceAlertSetting(id, uid, source, "usdt-krw", AlertCondition.BELOW, threshold, true, false, null, stamp, stamp, null)

    /** The seam over a counting owner and the given memory; each add or edit returns a handle the test finishes. */
    private class Seam(identity: AuthIdentityFence, private val memory: LastSelectedSourceMemory?) : SourceAlertSectionSource {
        override val settings = MutableStateFlow(SourceAlertSettingsState(AlertLoadPhase.Ready, emptyList(), identity))
        override val manualRefresh = MutableStateFlow(ManualRefreshState())
        private val noMemory = MutableStateFlow<SourceSelectionSnapshot>(SourceSelectionSnapshot.Ready(identity, 1, null))
        override val selection = memory?.selection ?: noMemory
        val adds = CopyOnWriteArrayList<SourceAlertDraft>()
        val edits = CopyOnWriteArrayList<Pair<Int, SourceAlertEdit>>()
        val toggles = CopyOnWriteArrayList<Pair<Int, Boolean>>()
        val deletes = CopyOnWriteArrayList<Int>()
        val handles = CopyOnWriteArrayList<SourceAlertSaveRequest>()
        override suspend fun prepareSelection(identity: AuthIdentityFence) { memory?.prepare(identity) }
        override fun acceptAddedSource(identity: AuthIdentityFence, sourceCode: String) { memory?.acceptAddedSource(identity, sourceCode) }
        override fun ensure() = Unit
        override fun authoritative() = Unit
        override fun manual() = Unit
        override fun addAlert(draft: SourceAlertDraft) = SourceAlertSaveRequest().also { adds += draft; handles += it }
        override fun editAlert(id: Int, edit: SourceAlertEdit) = SourceAlertSaveRequest().also { edits += id to edit; handles += it }
        override fun setEnabled(id: Int, enabled: Boolean) { toggles += id to enabled }
        override fun delete(id: Int) { deletes += id }
    }

    private class Os : NotificationPermissionOs {
        @Volatile var capability = NotificationCapability.RUNTIME_MISSING
        val launches = CopyOnWriteArrayList<String>()
        override fun read(): NotificationCapability = capability
        override fun shouldShowRationale(): Boolean = false
        override fun launchRequest(): NotificationLaunchOutcome { launches += "request"; return NotificationLaunchOutcome.ACCEPTED }
        override fun launchSettings(target: NotificationSettingsTarget): NotificationLaunchOutcome { launches += "settings:$target"; return NotificationLaunchOutcome.ACCEPTED }
        override fun markRequested() = Unit
    }

    /** The real store, with each write counted and the next one failed on demand. */
    private class FlakyStore(private val real: LastSelectedSourceStore) : LastSelectedSourceStore {
        @Volatile var failNext = false
        val attempts = CopyOnWriteArrayList<String>()
        override suspend fun read(uid: String): String? = real.read(uid)
        override suspend fun remember(uid: String, sourceCode: String) {
            attempts += sourceCode
            if (failNext) {
                failNext = false
                error("write failed")
            }
            real.remember(uid, sourceCode)
        }
    }

    private fun main(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scopes += it }
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        compose.runOnIdle { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun tap(tag: String) {
        compose.waitUntil(5_000) { exists(tag) }
        compose.onNode(hasTestTag(tag)).performClick()
        compose.waitForIdle()
    }

    @Test fun s85b_d2a_theRealHelperAndHostOpenAnAddOnlyOnAGrant_andEditSwitchDeleteAskNothing() {
        val os = Os()
        var grants = false
        val lane = NotificationPermissionLane(orphanResult = {})
        val action = NotificationPermissionAction(NotificationPermissionEnvironment(
            admitted = { true },
            liveIdentity = { a },
            premium = MutableStateFlow(OwnedPremiumAccess(a.uid, a.authGeneration, PremiumAccessState.PremiumConfirmed)),
            displayed = MutableStateFlow(NotificationCapability.RUNTIME_MISSING),
            onRequestResult = { grants },
            onAlreadyGranted = { true }
        ), os, lane)
        val seam = Seam(a, null)
        seam.settings.value = SourceAlertSettingsState(AlertLoadPhase.Ready, listOf(row(1, a.uid)), a)
        var shown = true
        val holder = onMain {
            SourceAlertSectionHolder(seam, a, sources, main(), isShown = { shown }, liveQuotes = { mapOf("upbit" to 1500.0) },
                permission = { granted -> action.run { granted() } }, enableNotifications = { if (action.needsAction) action.run() })
        }
        compose.setContent { NotificationPermissionDialogHost(action) }
        compose.waitForIdle()

        // The empty list's permission card is the add entry: one request, and the grant opens the add once.
        val emptySeam = Seam(a, null)
        val emptyHolder = onMain {
            SourceAlertSectionHolder(emptySeam, a, sources, main(), isShown = { shown }, liveQuotes = { mapOf("upbit" to 1500.0) },
                permission = { granted -> action.run { granted() } }, enableNotifications = { if (action.needsAction) action.run() })
        }
        onMain { emptyHolder.openAdd() }
        assertEquals("the card's tap is one request", listOf("request"), os.launches.toList())
        grants = true
        onMain { lane.onRequestResult() }
        val cardEditor = onMain { emptyHolder.editor }
        assertNotNull("granted: the card's add opens", cardEditor)
        onMain { emptyHolder.openAdd() }
        assertTrue("and only once", onMain { emptyHolder.editor } === cardEditor)
        onMain { emptyHolder.dismiss(emptyHolder.editor!!); emptyHolder.dispose() }
        grants = false
        os.launches.clear()

        onMain { holder.openEdit(row(1, a.uid)); holder.dismiss(holder.editor!!) }
        onMain { holder.toggle(row(1, a.uid), false); holder.requestDelete(row(1, a.uid)); holder.confirmDelete(holder.deletePrompt) }
        assertEquals("opening an edit, switching and deleting launch nothing", emptyList<String>(), os.launches.toList())
        assertEquals(listOf(1 to false), seam.toggles.toList())
        assertEquals(listOf(1), seam.deletes.toList())

        onMain { holder.openAdd(); holder.openAdd() }
        assertEquals("one request; the busy lane takes no second tap", listOf("request"), os.launches.toList())
        grants = true
        onMain { lane.onRequestResult() }
        assertNotNull("granted: the add opens", onMain { holder.editor })
        onMain { holder.dismiss(holder.editor!!) }

        onMain { holder.requestNotifications() }
        assertEquals("the banner over rows asks once", listOf("request", "request"), os.launches.toList())
        onMain { lane.onRequestResult() }
        assertNull("the banner's grant opens nothing", onMain { holder.editor })
        assertEquals("and sends nothing", 0, seam.adds.size + seam.edits.size)

        grants = false
        onMain { holder.openAdd() }
        onMain { lane.onRequestResult() }
        tap(NotificationPermissionTags.DENIED_CANCEL)
        assertNull("a refusal cancelled from the drawn dialog opens nothing", onMain { holder.editor })

        onMain { holder.openAdd() }
        onMain { lane.onRequestResult() }
        tap(NotificationPermissionTags.DENIED_SETTINGS)
        assertEquals("the dialog's settings", "settings:APP", os.launches.last())
        os.capability = NotificationCapability.GRANTED
        onMain { lane.onSettingsResult() }
        assertNull("a settings return runs no old intent", onMain { holder.editor })
        onMain { holder.openAdd() }
        val open = onMain { holder.editor }
        assertNotNull("granted now: a new tap opens", open)
        open!!

        os.capability = NotificationCapability.RUNTIME_MISSING
        val launched = os.launches.size
        onMain { holder.submit(open) }
        assertEquals("the save asked the system once", listOf("request"), os.launches.drop(launched))
        grants = true
        shown = false
        onMain { lane.onRequestResult() }
        assertEquals("a grant after the mount left sends nothing", 0, seam.adds.size)
        assertTrue("and the helper's dialog is gone", !exists(NotificationPermissionTags.DENIED_DIALOG) && !exists(NotificationPermissionTags.EXPLAIN_DIALOG))
        // A valid session's new save after that withdrawal: its grant is held, then delivered, and it is taken once.
        shown = true
        val again = onMain {
            SourceAlertSectionHolder(seam, a, sources, main(), isShown = { shown }, liveQuotes = { mapOf("upbit" to 1500.0) },
                permission = { granted -> action.run { granted() } }, enableNotifications = { if (action.needsAction) action.run() })
        }
        os.capability = NotificationCapability.GRANTED
        onMain { again.openAdd() }
        val draft = onMain { again.editor }
        assertNotNull("granted: the new session's add opens", draft)
        os.capability = NotificationCapability.RUNTIME_MISSING
        grants = false
        val beforeSave = os.launches.size
        onMain { again.submit(draft!!) }
        assertEquals("the new save asked the system once", listOf("request"), os.launches.drop(beforeSave))
        assertEquals("and waits for the grant", 0, seam.adds.size)
        grants = true
        onMain { lane.onRequestResult() }
        assertEquals("a valid session's held save is taken once", 1, seam.adds.size)
        onMain { seam.handles.last().finish(SourceAlertSaveOutcome.NotSent(com.jay.fxi.data.alerts.NotSentReason.RESET)) }
        // An add-open whose grant arrives after the mount was disposed opens nothing.
        shown = true
        val next = onMain {
            SourceAlertSectionHolder(emptySeam, a, sources, main(), isShown = { shown }, liveQuotes = { mapOf("upbit" to 1500.0) },
                permission = { granted -> action.run { granted() } }, enableNotifications = { if (action.needsAction) action.run() })
        }
        val beforeOpen = os.launches.size
        onMain { next.openAdd() }
        assertEquals("the add asked the system once", listOf("request"), os.launches.drop(beforeOpen))
        onMain { next.dispose(); lane.onRequestResult() }
        assertNull("a late grant after dispose opens nothing", onMain { next.editor })
        assertTrue(emptySeam.adds.isEmpty())
    }

    @Test fun s85b_d2b_eachUidRestoresItsOwnLastAdd_fromTheRealDataStore() {
        val store = FlakyStore(DataStoreLastSelectedSourceStore(context))
        fun memory() = LastSelectedSourceMemory(store, Dispatchers.Main.immediate, CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }) { }
        fun holderOver(seam: Seam, identity: AuthIdentityFence) = onMain {
            SourceAlertSectionHolder(seam, identity, sources, main(), isShown = { true }, liveQuotes = { mapOf("upbit" to 1500.0, "bithumb" to 1490.0, "gopax" to 1480.0, "korbit" to 1470.0) },
                permission = { it() }, enableNotifications = {}).also { it.start() }
        }
        fun settled(m: LastSelectedSourceMemory, identity: AuthIdentityFence): String? {
            compose.waitUntil(5_000) { onMain { (m.selection.value as? SourceSelectionSnapshot.Ready)?.identity == identity } }
            return onMain { (m.selection.value as SourceSelectionSnapshot.Ready).code }
        }
        fun add(holder: SourceAlertSectionHolder, seam: Seam, pick: String?, answer: (SourceAlertSetting) -> Unit = {}) {
            onMain {
                holder.openAdd()
                val open = holder.editor!!
                pick?.let { holder.pick(open, it) }
                holder.submit(open)
            }
            val draft = seam.adds.last()
            val adopted = row(10 + seam.adds.size, seam.settings.value.identity!!.uid, draft.source, draft.threshold)
            answer(adopted)
            onMain { seam.handles.last().finish(SourceAlertSaveOutcome.Saved(adopted)) }
            compose.waitForIdle()
        }

        val first = memory()
        val seamA = Seam(a, first)
        val holderA = holderOver(seamA, a)
        assertEquals("nothing remembered yet", null, settled(first, a))
        add(holderA, seamA, null) { adopted -> seamA.settings.value = SourceAlertSettingsState(AlertLoadPhase.Ready, listOf(adopted), a) }
        assertEquals("A's add of upbit is remembered at once", "upbit", onMain { (first.selection.value as SourceSelectionSnapshot.Ready).code })
        val adopted = seamA.settings.value.settings!!.single()
        onMain {
            holderA.openEdit(adopted)
            val edit = holderA.editor!!
            holderA.pick(edit, "bithumb")
            holderA.submit(edit)
        }
        onMain { seamA.handles.last().finish(SourceAlertSaveOutcome.Saved(adopted.copy(source = "bithumb"))) }
        compose.waitForIdle()
        assertEquals(listOf(adopted.id to SourceAlertEdit(source = "bithumb")), seamA.edits.toList())

        compose.waitUntil(5_000) { runBlocking { store.read(a.uid) } == "upbit" }
        val second = memory()
        assertEquals("a new memory: an edit's exchange is not remembered", "upbit", settled(second.also { m -> runBlocking { m.prepare(a) } }, a))
        val seamB = Seam(b, second)
        val holderB = holderOver(seamB, b)
        assertEquals("B starts with B's own", null, settled(second, b))
        store.failNext = true
        add(holderB, seamB, "gopax")
        compose.waitUntil(5_000) { store.attempts.size == 2 }
        assertEquals("a failed write keeps the process's choice", "gopax", onMain { (second.selection.value as SourceSelectionSnapshot.Ready).code })
        assertEquals("the disk keeps its last persisted value", null, runBlocking { store.read(b.uid) })
        assertEquals("and the add was sent once", 1, seamB.adds.size)
        add(holderB, seamB, "korbit")
        assertEquals("B's later add replaces it", "korbit", onMain { (second.selection.value as SourceSelectionSnapshot.Ready).code })

        // A success that arrives after its editor was dismissed is not remembered.
        onMain {
            holderB.openAdd()
            val open = holderB.editor!!
            holderB.pick(open, "bithumb")
            holderB.submit(open)
            holderB.dismiss(open)
        }
        onMain { seamB.handles.last().finish(SourceAlertSaveOutcome.Saved(row(30, b.uid, "bithumb"))) }
        compose.waitForIdle()
        assertEquals("three adds, none sent twice", 3, seamB.adds.size)

        assertEquals("the dismissed add did not reach the memory", "korbit", onMain { (second.selection.value as SourceSelectionSnapshot.Ready).code })
        // A prepare queues its read behind every write already accepted, so after it nothing more can be written.
        runBlocking { second.prepare(a) }
        assertEquals("nor the store: the writes were upbit, gopax (failed) and korbit", listOf("upbit", "gopax", "korbit"), store.attempts.toList())
        assertEquals("korbit", runBlocking { store.read(b.uid) })
        val third = memory()
        // Each UID's next add, from a cold memory over the store, opens on its own last added exchange.
        for ((identity, expected) in listOf(a to "upbit", b to "korbit")) {
            val coldSeam = Seam(identity, third)
            val coldHolder = holderOver(coldSeam, identity)
            assertEquals("${identity.uid}'s last add from the store", expected, settled(third, identity))
            onMain {
                coldHolder.openAdd()
                val opened = checkNotNull(coldHolder.editor)
                assertEquals("${identity.uid}'s next add starts on it", expected, opened.input.source)
                coldHolder.dismiss(opened)
                coldHolder.dispose()
            }
            assertTrue(coldSeam.adds.isEmpty())
        }
    }
}
