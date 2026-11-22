package com.jay.fxi.ui.alert

import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.alerts.NotSentReason
import com.jay.fxi.data.alerts.SourceAlertDraft
import com.jay.fxi.data.alerts.SourceAlertEdit
import com.jay.fxi.data.alerts.SourceAlertRowPolicy
import com.jay.fxi.data.alerts.SourceAlertSaveOutcome
import com.jay.fxi.data.alerts.SourceAlertSaveRequest
import com.jay.fxi.data.alerts.SourceAlertSettingsState
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.local.SourceSelectionSnapshot
import com.jay.fxi.service.NotificationCapability
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.data.repository.mapAlertHttpFailure
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.SourceAlertSetting
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8-5b J2, J3 and J5 (`R4c/S8/s8_5b_contract.agreed.r1.md` §2, §3): the tether section's Main holder over a counting seam and
 * a counting permission lane. Each action reads the mount, the screen's live check and the owner's identity when it runs; an
 * add opens and a save is sent only in the grant continuation; editing, toggling and deleting run no helper; each result shows
 * its fixed copy, keeps or closes the editor, and an adopted add is remembered once with the response's exchange.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceAlertSectionHolderTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val alice2 = AuthIdentityFence("user-a", 2)
    private val bob = AuthIdentityFence("user-b", 1)
    private val stamp = Instant.parse("2026-11-21T01:00:00Z")
    private val sources = SourceAlertRowPolicy.EXCHANGES.sources(SourceAlertRowPolicy.SETTINGS_ASSET)

    private fun row(id: Int, source: String = "upbit", threshold: Double = 1400.0, condition: AlertCondition = AlertCondition.BELOW,
                    enabled: Boolean = true, repeat: Int? = null, asset: String = "usdt-krw") =
        SourceAlertSetting(id, "user-a", source, asset, condition, threshold, enabled, false, repeat, stamp, stamp, null)

    private fun http(status: Int): Throwable = mapAlertHttpFailure(
        AuthenticatedHttpFailure(status, headersOf(), ByteArray(0), null, null, AuthenticatedFailureKind.OTHER_HTTP))

    /** Every call counted; each add or edit returns a fresh handle, finished at once when [immediate] is set. */
    private class Source : SourceAlertSectionSource {
        override val settings = MutableStateFlow(SourceAlertSettingsState(AlertLoadPhase.Idle, null, null))
        override val manualRefresh = MutableStateFlow(ManualRefreshState())
        override val selection = MutableStateFlow<SourceSelectionSnapshot>(SourceSelectionSnapshot.None)
        val prepares = mutableListOf<AuthIdentityFence>()
        val accepted = mutableListOf<Pair<AuthIdentityFence, String>>()
        val adds = mutableListOf<SourceAlertDraft>()
        val edits = mutableListOf<Pair<Int, SourceAlertEdit>>()
        val toggles = mutableListOf<Pair<Int, Boolean>>()
        val deletes = mutableListOf<Int>()
        val handles = mutableListOf<SourceAlertSaveRequest>()
        var ensures = 0
        var authoritatives = 0
        var manuals = 0
        var immediate: SourceAlertSaveOutcome? = null
        override suspend fun prepareSelection(identity: AuthIdentityFence) { prepares += identity }
        override fun acceptAddedSource(identity: AuthIdentityFence, sourceCode: String) { accepted += identity to sourceCode }
        override fun ensure() { ensures++ }
        override fun authoritative() { authoritatives++ }
        override fun manual() { manuals++ }
        override fun addAlert(draft: SourceAlertDraft): SourceAlertSaveRequest {
            adds += draft
            return request()
        }
        override fun editAlert(id: Int, edit: SourceAlertEdit): SourceAlertSaveRequest {
            edits += id to edit
            return request()
        }
        override fun setEnabled(id: Int, enabled: Boolean) { toggles += id to enabled }
        override fun delete(id: Int) { deletes += id }
        private fun request() = SourceAlertSaveRequest().also { handle ->
            handles += handle
            immediate?.let(handle::finish)
        }
    }

    /** The Activity lane as the holder sees it: a block runs at once when granted, otherwise when the test grants. */
    private class Permission {
        var granted = true
        val waiting = mutableListOf<() -> Unit>()
        var asks = 0
        var bare = 0
        fun ask(onGranted: () -> Unit) {
            asks++
            if (granted) onGranted() else waiting += onGranted
        }
        fun grant() {
            val each = waiting.toList()
            waiting.clear()
            each.forEach { it() }
        }
        fun refuse() = waiting.clear()
    }

    private inner class Rig(private val scope: CoroutineScope) {
        val source = Source()
        val permission = Permission()
        var shown = true
        var quotes: Map<String, Double> = mapOf("upbit" to 1501.0, "bithumb" to 1488.6, "kb" to 1380.0)
        fun holder(identity: AuthIdentityFence = alice) = SourceAlertSectionHolder(source, identity, sources, scope,
            isShown = { shown }, liveQuotes = { quotes }, permission = permission::ask, enableNotifications = { permission.bare++ })
        fun ready(vararg rows: SourceAlertSetting, who: AuthIdentityFence = alice) {
            source.settings.value = SourceAlertSettingsState(AlertLoadPhase.Ready, rows.toList(), who)
        }
        fun selected(code: String?, who: AuthIdentityFence = alice) {
            source.selection.value = SourceSelectionSnapshot.Ready(who, 1, code)
        }
    }

    private fun rig(body: suspend TestScope.(Rig) -> Unit) = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            body(this, Rig(scope))
        } finally {
            scope.cancel()
        }
    }

    private fun input(source: String = "upbit", text: String = "1500", condition: AlertCondition = AlertCondition.ABOVE,
                      repeat: RepeatIntervalChange = RepeatIntervalChange.Once) = SourceAlertEditorInput(source, condition, text, repeat)

    // ---- J2: rows, cap and the row controls ----

    @Test fun `S8_5B_J2a the add counts every adopted row of the shown identity against 30`() {
        fun availability(n: Int, who: AuthIdentityFence? = alice) = sourceAlertAddAvailability(
            SourceAlertSettingsState(AlertLoadPhase.Ready, (1..n).map { row(it, enabled = it % 2 == 0, repeat = if (it % 3 == 0) 300 else null) }, who),
            alice)
        assertEquals(SOURCE_ALERT_LIMIT, 30)
        for ((n, expected) in listOf(0 to SourceAlertAddAvailability.Available(30), 26 to SourceAlertAddAvailability.Available(4),
            27 to SourceAlertAddAvailability.Available(3), 29 to SourceAlertAddAvailability.Available(1),
            30 to SourceAlertAddAvailability.AtCap, 31 to SourceAlertAddAvailability.AtCap)) {
            assertEquals("$n rows", expected, availability(n))
        }
        assertEquals("another UID", SourceAlertAddAvailability.Hidden, availability(3, bob))
        assertEquals("the same UID's next generation", SourceAlertAddAvailability.Hidden, availability(3, alice2))
        assertEquals("no snapshot", SourceAlertAddAvailability.Hidden,
            sourceAlertAddAvailability(SourceAlertSettingsState(AlertLoadPhase.Loading, null, alice), alice))
        val repeated = SourceAlertSettingsState(AlertLoadPhase.Ready, (1..29).map { row(it) } + row(29, source = "gopax"), alice)
        assertEquals("a repeated id counts once", SourceAlertAddAvailability.Available(1), sourceAlertAddAvailability(repeated, alice))
    }

    @Test fun `S8_5B_J2b at the cap no add opens and no helper runs, and the last row is still edited, toggled and deleted`() = rig { r ->
        val rows = (1..31).map { row(it, threshold = 1000.0 + it) }
        r.ready(*rows.toTypedArray())
        r.selected(null)
        val holder = r.holder()
        holder.openAdd()
        assertNull("no add at 31", holder.editor)
        assertEquals("and no helper", 0, r.permission.asks)
        val last = rows.last()
        holder.openEdit(last)
        assertNotNull(holder.editor)
        val open = holder.editor!!
        assertEquals(SourceAlertEditorMode.Edit(last), open.mode)
        holder.dismiss(open)
        holder.toggle(last, false)
        holder.toggle(rows[0], true)
        assertEquals("each toggle sends its target value", listOf(31 to false, 1 to true), r.source.toggles)
        holder.requestDelete(last)
        assertEquals(last, holder.deletePrompt?.row)
        holder.cancelDelete(holder.deletePrompt)
        holder.requestDelete(last)
        holder.confirmDelete(holder.deletePrompt)
        assertEquals("one confirmed delete, none for the cancel", listOf(31), r.source.deletes)
        assertEquals("no helper for editing, toggling or deleting", 0, r.permission.asks)
    }

    @Test fun `S8_5B_J2c a row the live snapshot does not hold is neither opened, toggled nor deleted`() = rig { r ->
        r.ready(row(1), row(2, asset = "usd-krw"))
        val holder = r.holder()
        val stale = row(9)
        val otherPair = row(1, source = "bithumb")
        for (target in listOf(stale, otherPair, row(2, asset = "usd-krw"))) {
            holder.openEdit(target)
            holder.toggle(target, false)
            holder.requestDelete(target)
        }
        assertNull(holder.editor)
        assertNull(holder.deletePrompt)
        assertEquals(emptyList<Pair<Int, Boolean>>(), r.source.toggles)
        holder.requestDelete(row(1))
        r.ready(row(2, asset = "usd-krw"))
        holder.start()
        runCurrent()
        val gone = holder.deletePrompt
        assertNull("a confirmation whose row went away closes", gone)
        holder.confirmDelete(gone)
        assertEquals(emptyList<Int>(), r.source.deletes)
    }

    @Test fun `S8_5B_J2d one ensure and one prepare per mount, only for the shown identity`() = rig { r ->
        val holder = r.holder()
        holder.start()
        holder.start()
        runCurrent()
        assertEquals(1, r.source.ensures)
        r.ready(row(1), who = bob)
        runCurrent()
        assertEquals("another identity's snapshot prepares nothing", emptyList<AuthIdentityFence>(), r.source.prepares)
        r.ready(row(1))
        runCurrent()
        r.ready(row(1), row(2))
        runCurrent()
        assertEquals(listOf(alice), r.source.prepares)
        holder.retry()
        holder.refresh()
        assertEquals(1 to 1, r.source.authoritatives to r.source.manuals)
        r.shown = false
        holder.retry()
        holder.refresh()
        assertEquals("a hidden mount neither retries nor refreshes", 1 to 1, r.source.authoritatives to r.source.manuals)
    }

    @Test fun `S8_5B_J2i a first listing after leaving does not start the old memory read`() {
        for (observeExit in listOf(false, true)) rig { r ->
            val old = r.holder()
            old.start()
            runCurrent()
            r.shown = false
            if (observeExit) old.retry()
            r.ready(row(1))
            runCurrent()
            assertTrue("departed mount, observed=$observeExit", r.source.prepares.isEmpty())
            r.shown = true
            r.holder().start()
            runCurrent()
            assertEquals("a shown mount over the listing prepares", listOf(alice), r.source.prepares)
        }
    }

    @Test fun `S8_5B_J2e a first failure's retry needs no adopted identity`() = rig { r ->
        r.source.settings.value = SourceAlertSettingsState(AlertLoadPhase.Idle, null, null)
        val holder = r.holder()
        holder.retry()
        holder.refresh()
        assertEquals(1 to 1, r.source.authoritatives to r.source.manuals)
        holder.openAdd()
        holder.requestNotifications()
        assertEquals("but no write, open or permission request", 0 to 0, r.permission.asks to r.permission.bare)
    }

    // ---- J3: results and sessions ----

    private data class Expect(val open: Boolean, val error: String?, val notice: String?)

    private val addTable = listOf(
        SourceAlertSaveOutcome.Saved(row(9, source = "korbit", threshold = 1500.0)) to Expect(false, null, null),
        SourceAlertSaveOutcome.Unsupported to Expect(true, "지원되는 거래소를 선택해 주세요.", null),
        SourceAlertSaveOutcome.Failed(http(400)) to Expect(true, "지원되는 거래소와 입력값을 확인해 주세요.", null),
        SourceAlertSaveOutcome.Failed(http(404)) to Expect(true, "저장 결과를 확인하지 못했습니다. 알림 목록을 확인한 뒤 다시 시도해 주세요.", null),
        SourceAlertSaveOutcome.Failed(IOException("reset")) to Expect(true, "저장 결과를 확인하지 못했습니다. 알림 목록을 확인한 뒤 다시 시도해 주세요.", null),
        SourceAlertSaveOutcome.NotSent(NotSentReason.CAP) to Expect(true, "알림은 최대 30개까지 설정할 수 있습니다.", null),
        SourceAlertSaveOutcome.NotSent(NotSentReason.NOT_READY) to Expect(true, "알림을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.", null),
        SourceAlertSaveOutcome.NotSent(NotSentReason.UNADMITTED) to Expect(true, "알림을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.", null),
        SourceAlertSaveOutcome.NotSent(NotSentReason.BACKGROUND) to Expect(true, "알림을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.", null),
        SourceAlertSaveOutcome.NotSent(NotSentReason.SUPERSEDED) to Expect(true, null, null),
        SourceAlertSaveOutcome.NotSent(NotSentReason.RESET) to Expect(false, null, null)
    )

    @Test fun `S8_5B_J3a each add result, at once or after Pending, shows its copy, keeps the draft and is never resent`() {
        for (immediate in listOf(true, false)) for ((outcome, expect) in addTable) rig { r ->
            val label = "$outcome immediate=$immediate"
            r.ready(row(1))
            r.selected("bithumb")
            val holder = r.holder()
            holder.start()
            runCurrent()
            holder.openAdd()
            val open = holder.editor!!
            holder.input(open, open.input.copy(thresholdText = "1490"))
            val typed = open.input
            if (immediate) r.source.immediate = outcome
            holder.submit(open)
            if (!immediate) {
                assertTrue(label, open.handle?.outcome?.value == SourceAlertSaveOutcome.Pending)
                holder.submit(open)
                r.source.handles.single().finish(outcome)
            }
            runCurrent()
            assertEquals("$label: one request", 1, r.source.adds.size)
            assertEquals("$label: the draft", SourceAlertDraft("bithumb", AlertCondition.BELOW, 1490.0, true, null), r.source.adds.single())
            assertEquals("$label: open", expect.open, holder.editor === open)
            if (expect.open) {
                assertEquals("$label: copy", expect.error, open.error)
                assertEquals("$label: draft kept", typed, open.input)
            }
            assertEquals("$label: notice", expect.notice, holder.notice)
            val remembered = if (outcome is SourceAlertSaveOutcome.Saved) listOf(alice to "korbit") else emptyList()
            assertEquals("$label: memory, from the response's exchange", remembered, r.source.accepted)
            runCurrent()
            assertEquals("$label: nothing resent", 1, r.source.adds.size + r.source.edits.size)
        }
    }

    @Test fun `S8_5B_J3b an edit's Saved and Removed close it, only Removed leaves the notice, and neither is remembered`() {
        for (outcome in listOf(SourceAlertSaveOutcome.Saved(row(1, source = "gopax")), SourceAlertSaveOutcome.Removed)) rig { r ->
            r.ready(row(1))
            val holder = r.holder()
            holder.start()
            runCurrent()
            holder.openEdit(row(1))
            val open = holder.editor!!
            r.quotes = r.quotes + ("gopax" to 1400.0)
            holder.pick(open, "gopax")
            holder.submit(open)
            assertEquals(listOf(1 to SourceAlertEdit(source = "gopax")), r.source.edits)
            r.source.handles.single().finish(outcome)
            runCurrent()
            assertNull("$outcome closes", holder.editor)
            assertEquals("$outcome notice", if (outcome == SourceAlertSaveOutcome.Removed) "이미 삭제된 알림입니다." else null, holder.notice)
            assertEquals("an edit is never remembered, even with another exchange", emptyList<Pair<AuthIdentityFence, String>>(), r.source.accepted)
        }
    }

    @Test fun `S8_5B_J3c the edited row going away and Removed in one Main step close the editor once with the notice`() {
        for (resultFirst in listOf(true, false)) rig { r ->
            r.ready(row(1), row(2))
            val holder = r.holder()
            holder.start()
            runCurrent()
            holder.openEdit(row(1))
            val open = holder.editor!!
            holder.input(open, open.input.copy(thresholdText = "1450"))
            holder.submit(open)
            if (resultFirst) {
                r.source.handles.single().finish(SourceAlertSaveOutcome.Removed)
                r.ready(row(2))
            } else {
                r.ready(row(2))
                runCurrent()
                assertSame("the row disappeared before the result: its request decides", open, holder.editor)
                r.source.handles.single().finish(SourceAlertSaveOutcome.Removed)
            }
            runCurrent()
            assertNull(holder.editor)
            assertEquals("resultFirst=$resultFirst", "이미 삭제된 알림입니다.", holder.notice)
            holder.openEdit(row(2))
            assertNull("the next editor clears the notice", holder.notice)
        }
    }

    @Test fun `S8_5B_J3j a replaced pair discards the pending edit and its Removed notice`() {
        for (collectFirst in listOf(true, false)) rig { r ->
            r.ready(row(1))
            val holder = r.holder()
            holder.start()
            runCurrent()
            holder.openEdit(row(1))
            val open = holder.editor!!
            holder.input(open, open.input.copy(thresholdText = "1450"))
            holder.submit(open)
            val handle = r.source.handles.single()
            if (!collectFirst) handle.finish(SourceAlertSaveOutcome.Removed)
            r.ready(row(1, source = "bithumb"))
            if (collectFirst) {
                runCurrent()
                assertNull("collectFirst=$collectFirst: the replaced pair closes at once", holder.editor)
                handle.finish(SourceAlertSaveOutcome.Removed)
            }
            runCurrent()
            assertNull(holder.editor)
            assertNull("collectFirst=$collectFirst: no removed notice for a row that is still there", holder.notice)
            assertTrue(r.source.accepted.isEmpty())
        }
    }

    @Test fun `S8_5B_J3d after a failure the same editor may send again, a dismissed editor's late success is not consumed`() = rig { r ->
        r.ready(row(1))
        r.selected("upbit")
        val holder = r.holder()
        holder.start()
        runCurrent()
        holder.openAdd()
        val first = holder.editor!!
        holder.submit(first)
        r.source.handles[0].finish(SourceAlertSaveOutcome.NotSent(NotSentReason.BACKGROUND))
        runCurrent()
        holder.submit(first)
        assertEquals("an explicit second save is a new request", 2, r.source.adds.size)
        holder.dismiss(first)
        holder.openAdd()
        val second = holder.editor!!
        r.source.handles[1].finish(SourceAlertSaveOutcome.Saved(row(5, source = "upbit")))
        runCurrent()
        assertSame("the new editor stays", second, holder.editor)
        assertNull(second.error)
        assertEquals("the dismissed editor's success is not remembered", emptyList<Pair<AuthIdentityFence, String>>(), r.source.accepted)
    }

    @Test fun `S8_5B_J3e another identity closes the editor and its late result shows nothing and is not remembered`() {
        for (next in listOf(bob, alice2)) rig { r ->
            r.ready(row(1))
            r.selected(null)
            val holder = r.holder()
            holder.start()
            runCurrent()
            holder.openAdd()
            val open = holder.editor!!
            holder.submit(open)
            r.ready(row(1), who = next)
            runCurrent()
            assertNull("$next closes the editor", holder.editor)
            r.source.handles.single().finish(SourceAlertSaveOutcome.Saved(row(9)))
            runCurrent()
            assertEquals(emptyList<Pair<AuthIdentityFence, String>>(), r.source.accepted)
            assertNull(holder.notice)
            holder.openAdd()
            assertNull("and the old holder opens nothing for $next", holder.editor)
        }
    }

    @Test fun `S8_5B_J3f an invalid draft is never handed to the owner and shows its hint`() = rig { r ->
        r.ready(row(1, threshold = 1500.0, condition = AlertCondition.ABOVE))
        r.selected("upbit")
        val holder = r.holder()
        holder.openAdd()
        val open = holder.editor!!
        for ((text, condition, copy) in listOf(
            Triple("1500", AlertCondition.ABOVE, "동일한 알림이 이미 존재합니다"),
            Triple("2251", AlertCondition.BELOW, "현재 시세의 ±50% 범위를 벗어났습니다"),
            Triple("", AlertCondition.BELOW, "알림을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.")
        )) {
            holder.input(open, open.input.copy(thresholdText = text, condition = condition))
            holder.submit(open)
            assertEquals(text, copy, open.error)
        }
        holder.pick(open, "coinone")
        holder.input(open, open.input.copy(thresholdText = "1500"))
        holder.submit(open)
        assertEquals("no quote for the picked exchange", "현재 시세를 확인할 수 없어 가격 조건을 저장할 수 없습니다", open.error)
        assertEquals("the owner got nothing", 0, r.source.adds.size)
    }

    // ---- J5: the permission lane and the live check ----

    @Test fun `S8_5B_J5a an add opens only in the grant continuation, a refusal opens nothing and a new tap tries again`() = rig { r ->
        r.ready(row(1))
        r.selected("bithumb")
        r.permission.granted = false
        val holder = r.holder()
        holder.openAdd()
        assertEquals(1, r.permission.asks)
        assertNull(holder.editor)
        r.permission.refuse()
        assertNull(holder.editor)
        holder.openAdd()
        holder.openAdd()
        assertEquals("a second tap is the lane's to join", 3, r.permission.asks)
        r.quotes = mapOf("bithumb" to 1477.4)
        r.permission.grant()
        val open = holder.editor!!
        assertEquals("one editor, seeded at the grant from the remembered exchange's live quote",
            input("bithumb", "1477", AlertCondition.BELOW), open.input)
    }

    @Test fun `S8_5B_J5b a continuation after the screen, the identity, the mount or an editor changed opens nothing`() {
        val changes: List<Pair<String, (Rig, SourceAlertSectionHolder) -> Unit>> = listOf(
            "fullscreen or another tab" to { r, _ -> r.shown = false },
            "another UID" to { r, _ -> r.ready(row(1), who = bob) },
            "the next generation" to { r, _ -> r.ready(row(1), who = alice2) },
            "dispose" to { _, holder -> holder.dispose() },
            "an editor opened and closed" to { _, holder ->
                holder.openEdit(row(1))
                holder.dismiss(holder.editor!!)
            },
            "the selection of another identity" to { r, _ -> r.selected("upbit", who = bob) },
            "the cap reached" to { r, _ -> r.ready(*(1..30).map { row(it) }.toTypedArray()) }
        )
        for ((label, change) in changes) rig { r ->
            r.ready(row(1))
            r.selected(null)
            r.permission.granted = false
            val holder = r.holder()
            holder.openAdd()
            change(r, holder)
            r.permission.grant()
            assertNull(label, holder.editor)
            r.shown = true
            r.ready(row(1))
            r.selected(null)
            val fresh = r.holder()
            fresh.openAdd()
            r.permission.grant()
            assertNotNull("$label: a new session's tap opens", fresh.editor)
        }
    }

    @Test fun `S8_5B_J5o a save whose grant waits sends nothing once its session is withdrawn, and a valid session's new save sends once`() {
        val withdrawals: List<Pair<String, (Rig, SourceAlertSectionHolder) -> Unit>> = listOf(
            "fullscreen or another tab" to { r, _ -> r.shown = false },
            "dispose" to { _, holder -> holder.dispose() }
        )
        for ((label, withdraw) in withdrawals) rig { r ->
            r.ready(row(1))
            r.selected(null)
            val holder = r.holder()
            holder.openAdd()
            val open = holder.editor!!
            holder.input(open, open.input.copy(thresholdText = "1450"))
            r.permission.granted = false
            holder.submit(open)
            assertEquals("$label: premise: the save waits for the grant", 1, r.permission.waiting.size)
            withdraw(r, holder)
            r.permission.grant()
            assertEquals("$label: no add", 0, r.source.adds.size)
            assertTrue("$label: nothing remembered", r.source.accepted.isEmpty())
            r.shown = true
            val fresh = r.holder()
            fresh.openAdd()
            r.permission.grant()
            val next = fresh.editor!!
            fresh.input(next, next.input.copy(thresholdText = "1450"))
            fresh.submit(next)
            assertEquals("$label: premise: the new save waits for the grant", 0, r.source.adds.size)
            r.permission.grant()
            assertEquals("$label: a valid session's held save sends once", 1, r.source.adds.size)
        }
    }

    @Test fun `S8_5B_J5c a click the screen does not show runs no helper, opens nothing and sends nothing`() = rig { r ->
        r.ready(row(1))
        r.selected(null)
        val holder = r.holder()
        holder.openAdd()
        val open = holder.editor!!
        r.shown = false
        holder.submit(open)
        holder.pick(open, "bithumb")
        holder.openEdit(row(1))
        holder.toggle(row(1), false)
        holder.requestDelete(row(1))
        holder.requestNotifications()
        assertEquals("one helper run, the add's", 1, r.permission.asks)
        assertEquals(0, r.permission.bare)
        assertEquals("upbit", open.input.source)
        assertEquals(0, r.source.adds.size + r.source.toggles.size + r.source.deletes.size)
        r.shown = true
        holder.requestNotifications()
        assertEquals("a holder that found itself hidden stays retired", 0, r.permission.bare)
        holder.openEdit(row(1))
        assertNull(holder.editor)
        r.holder().requestNotifications()
        assertEquals("the new mount's banner may request", 1, r.permission.bare)
    }

    @Test fun `S8_5B_J5d a change while the save waits for the grant spends that save, and a new click sends the new draft`() = rig { r ->
        r.ready(row(1))
        r.selected("upbit")
        val holder = r.holder()
        holder.openAdd()
        val open = holder.editor!!
        r.permission.granted = false
        holder.input(open, open.input.copy(thresholdText = "1450"))
        holder.submit(open)
        holder.input(open, open.input.copy(thresholdText = "1460", condition = AlertCondition.ABOVE))
        r.permission.grant()
        assertEquals("the old intent is spent", emptyList<SourceAlertDraft>(), r.source.adds)
        assertEquals("the typing stays", input("upbit", "1460", AlertCondition.ABOVE), open.input)
        holder.input(open, open.input.copy(thresholdText = "1450", condition = AlertCondition.BELOW))
        holder.submit(open)
        holder.input(open, open.input.copy(thresholdText = "1460"))
        holder.input(open, open.input.copy(thresholdText = "1450"))
        r.permission.grant()
        assertEquals("a change and its undo spend it too", emptyList<SourceAlertDraft>(), r.source.adds)
        r.permission.granted = true
        holder.submit(open)
        assertEquals("a new click sends what is typed now", listOf(SourceAlertDraft("upbit", AlertCondition.BELOW, 1450.0, true, null)), r.source.adds)
    }

    @Test fun `S8_5B_J5e the continuation checks the quotes and rows as they are then and keeps the draft`() {
        val changes: List<Triple<String, (Rig) -> Unit, String>> = listOf(
            Triple("the quote moved", { r -> r.quotes = mapOf("upbit" to 3000.0) }, "현재 시세의 ±50% 범위를 벗어났습니다"),
            Triple("the quote went away", { r -> r.quotes = emptyMap() }, "현재 시세를 확인할 수 없어 가격 조건을 저장할 수 없습니다"),
            Triple("a duplicate arrived", { r -> r.ready(row(1), row(2, threshold = 1450.0)) }, "동일한 알림이 이미 존재합니다")
        )
        for ((label, change, copy) in changes) rig { r ->
            r.ready(row(1))
            r.selected("upbit")
            val holder = r.holder()
            holder.openAdd()
            val open = holder.editor!!
            holder.input(open, open.input.copy(thresholdText = "1450"))
            r.permission.granted = false
            holder.submit(open)
            change(r)
            r.permission.grant()
            assertEquals(label, 0, r.source.adds.size)
            assertEquals(label, copy, open.error)
            assertEquals(label, "1450", open.input.thresholdText)
        }
    }

    @Test fun `S8_5B_J5f an edit's row gone while the grant waits closes it and sends nothing`() = rig { r ->
        r.ready(row(1))
        val holder = r.holder()
        holder.openEdit(row(1))
        val open = holder.editor!!
        holder.input(open, open.input.copy(thresholdText = "1450"))
        r.permission.granted = false
        holder.submit(open)
        r.ready(row(2))
        r.permission.grant()
        assertNull(holder.editor)
        assertEquals(0, r.source.edits.size)
        assertNull("no copy", open.error)
    }

    @Test fun `S8_5B_J5g the add waits for the selection to settle and seeds from the live quote at that moment`() = rig { r ->
        r.ready(row(1))
        r.source.selection.value = SourceSelectionSnapshot.Loading(alice, 1)
        val holder = r.holder()
        holder.openAdd()
        assertEquals("no helper before the selection settles", 0, r.permission.asks)
        r.source.selection.value = SourceSelectionSnapshot.Failed(alice, 1)
        r.quotes = mapOf("bithumb" to 1488.6)
        holder.openAdd()
        assertEquals("a failed read falls back to upbit, with no quote an empty price", input("upbit", "", AlertCondition.BELOW), holder.editor!!.input)
        holder.dismiss(holder.editor!!)
        r.selected("bithumb")
        r.quotes = mapOf("bithumb" to 1488.6, "upbit" to 1501.0)
        holder.openAdd()
        assertEquals(input("bithumb", "1489", AlertCondition.BELOW), holder.editor!!.input)
    }

    @Test fun `S8_5B_J5h a pick refills an add from the live quote then, an edit keeps its text, later quotes rewrite nothing`() = rig { r ->
        r.ready(row(1, source = "upbit", threshold = 1400.0))
        r.selected(null)
        val holder = r.holder()
        holder.openAdd()
        val add = holder.editor!!
        r.quotes = mapOf("upbit" to 1501.0, "korbit" to 1488.6)
        holder.pick(add, "korbit")
        assertEquals("1489", add.input.thresholdText)
        holder.input(add, add.input.copy(thresholdText = "1470"))
        holder.pick(add, "gopax")
        assertEquals("a pick without a quote keeps the typed text", input("gopax", "1470", AlertCondition.BELOW), add.input)
        r.quotes = mapOf("gopax" to 1400.0)
        assertEquals("1470", add.input.thresholdText)
        holder.input(add, add.input.copy(source = "korbit", thresholdText = "-1500"))
        assertEquals("typing cannot change the exchange and is kept as typed", input("gopax", "-1500", AlertCondition.BELOW), add.input)
        val asks = r.permission.asks
        holder.submit(add)
        assertEquals("a malformed price runs no helper", asks, r.permission.asks)
        assertEquals("and shows why", "알림을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.", add.error)
        assertEquals(0, r.source.adds.size)
        holder.dismiss(add)
        holder.openEdit(row(1, source = "upbit", threshold = 1400.0))
        val edit = holder.editor!!
        holder.pick(edit, "korbit")
        assertEquals(SourceAlertEditorInput("korbit", AlertCondition.BELOW, "1400", RepeatIntervalChange.Keep), edit.input)
    }

    @Test fun `S8_5B_J5n cancelling a disabled edit and invoking an enabled no-op save ask nothing`() = rig { r ->
        val holder = r.holder()
        r.ready(row(1, enabled = false))
        holder.openEdit(row(1, enabled = false))
        holder.dismiss(holder.editor!!)
        r.ready(row(1))
        holder.openEdit(row(1))
        holder.submit(holder.editor!!)
        assertEquals(0, r.permission.asks)
        assertTrue(r.source.edits.isEmpty())
        assertTrue(r.source.accepted.isEmpty())
    }

    @Test fun `S8_5B_J5i opening an edit, toggling and deleting need no grant, and a disabled row's save sends only true`() = rig { r ->
        r.ready(row(1, enabled = false))
        r.permission.granted = false
        val holder = r.holder()
        holder.toggle(row(1, enabled = false), true)
        holder.requestDelete(row(1, enabled = false))
        holder.confirmDelete(holder.deletePrompt)
        holder.openEdit(row(1, enabled = false))
        val open = holder.editor!!
        assertEquals(0, r.permission.asks)
        assertEquals(listOf(1 to true), r.source.toggles)
        assertEquals(listOf(1), r.source.deletes)
        holder.submit(open)
        assertEquals("the save waits for the grant", 1, r.permission.asks)
        r.permission.grant()
        assertEquals(listOf(1 to SourceAlertEdit(isEnabled = true)), r.source.edits)
    }

    @Test fun `S8_5B_J2f only the open delete prompt confirms or cancels, once`() = rig { r ->
        r.ready(row(1), row(2, source = "bithumb"))
        val holder = r.holder()
        holder.requestDelete(row(1))
        val first = holder.deletePrompt
        holder.cancelDelete(first)
        holder.requestDelete(row(2, source = "bithumb"))
        val second = holder.deletePrompt
        holder.confirmDelete(first)
        holder.cancelDelete(first)
        assertSame("an old prompt neither confirms nor closes the open one", second, holder.deletePrompt)
        assertEquals(emptyList<Int>(), r.source.deletes)
        holder.confirmDelete(second)
        holder.confirmDelete(second)
        assertEquals("the open prompt confirms once", listOf(2), r.source.deletes)
        holder.requestDelete(row(1))
        val again = holder.deletePrompt
        holder.cancelDelete(again)
        holder.requestDelete(row(1))
        holder.confirmDelete(again)
        assertEquals("a reopened prompt for the same row is a new one", listOf(2), r.source.deletes)
    }

    @Test fun `S8_5B_J3h a result not yet consumed blocks a new save, and its consumption opens the next`() = rig { r ->
        r.ready(row(1))
        r.selected(null)
        val holder = r.holder()
        holder.openAdd()
        val open = holder.editor!!
        holder.submit(open)
        val asks = r.permission.asks
        holder.submit(open)
        assertEquals("pending: no helper, no request", asks to 1, r.permission.asks to r.source.adds.size)
        r.source.handles.single().finish(SourceAlertSaveOutcome.NotSent(NotSentReason.BACKGROUND))
        holder.submit(open)
        holder.input(open, open.input.copy(thresholdText = "1450"))
        assertEquals("finished but not consumed: still nothing", asks to 1, r.permission.asks to r.source.adds.size)
        assertEquals("1501", open.input.thresholdText)
        runCurrent()
        assertEquals("알림을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.", open.error)
        holder.submit(open)
        assertEquals("consumed: a new click is a new request", 2, r.source.adds.size)
        r.source.immediate = SourceAlertSaveOutcome.NotSent(NotSentReason.CAP)
        r.source.handles.last().finish(SourceAlertSaveOutcome.NotSent(NotSentReason.UNADMITTED))
        runCurrent()
        holder.submit(open)
        assertEquals("an immediate refusal is consumed before submit returns", "알림은 최대 30개까지 설정할 수 있습니다.", open.error)
        assertNull(open.handle)
        assertEquals(3, r.source.adds.size)
    }

    @Test fun `S8_5B_J3i an old editor's late answer never reaches the new editor, its error or the memory`() {
        for (late in listOf<SourceAlertSaveOutcome>(SourceAlertSaveOutcome.Saved(row(9)), SourceAlertSaveOutcome.Removed,
                SourceAlertSaveOutcome.Failed(http(404)), SourceAlertSaveOutcome.Failed(http(400)))) rig { r ->
            r.ready(row(1), row(2, source = "bithumb"))
            r.selected(null)
            val holder = r.holder()
            holder.start()
            runCurrent()
            holder.openEdit(row(1))
            val old = holder.editor!!
            holder.input(old, old.input.copy(thresholdText = "1450"))
            holder.submit(old)
            holder.dismiss(old)
            holder.openAdd()
            val next = holder.editor!!
            r.source.handles.single().finish(late)
            runCurrent()
            assertSame("$late: the new editor stays", next, holder.editor)
            assertNull("$late: with no error", next.error)
            assertNull("$late: and no notice", holder.notice)
            assertEquals("$late: nothing remembered", emptyList<Pair<AuthIdentityFence, String>>(), r.source.accepted)
        }
    }

    // ---- J5 with the real helper and lane over a counting OS port ---------------------------------------------------

    private class Os : NotificationPermissionOs {
        var capability = NotificationCapability.RUNTIME_MISSING
        val log = mutableListOf<String>()
        override fun read(): NotificationCapability { log += "read"; return capability }
        override fun shouldShowRationale(): Boolean = false
        override fun launchRequest(): NotificationLaunchOutcome { log += "request"; return NotificationLaunchOutcome.ACCEPTED }
        override fun launchSettings(target: NotificationSettingsTarget): NotificationLaunchOutcome { log += "settings"; return NotificationLaunchOutcome.ACCEPTED }
        override fun markRequested() = Unit
    }

    /** The production adapter's two arguments over a real action: the continuation runs only in the helper's grant. */
    private inner class Helper(val r: Rig) {
        val os = Os()
        var grants = false
        val lane = NotificationPermissionLane(orphanResult = {})
        val action = NotificationPermissionAction(NotificationPermissionEnvironment(
            admitted = { true },
            liveIdentity = { alice },
            premium = MutableStateFlow(OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.PremiumConfirmed)),
            displayed = MutableStateFlow(NotificationCapability.RUNTIME_MISSING),
            onRequestResult = { grants },
            onAlreadyGranted = { true }
        ), os, lane)
        fun holder(scope: CoroutineScope) = SourceAlertSectionHolder(r.source, alice, sources, scope, isShown = { r.shown }, liveQuotes = { r.quotes },
            permission = { granted -> action.run { granted() } }, enableNotifications = { if (action.needsAction) action.run() })
        fun launches() = os.log.count { it == "request" || it == "settings" }
    }

    @Test fun `S8_5B_J5l the real helper opens an add only on a grant, never after a refusal, a settings return or a dispose`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val r = Rig(scope)
            r.ready()
            r.selected(null)
            val h = Helper(r)
            val holder = h.holder(scope)
            holder.openAdd()
            holder.openAdd()
            assertEquals("one request; the busy lane takes no second tap", 1, h.launches())
            assertNull(holder.editor)
            h.grants = true
            h.lane.onRequestResult()
            assertNotNull("granted: the add opens once", holder.editor)
            holder.dismiss(holder.editor!!)

            h.grants = false
            holder.openAdd()
            h.lane.onRequestResult()
            assertTrue("refused: the helper asks again", h.action.dialog is NotificationPermissionDialog.Denied)
            h.action.onDialogDismiss()
            assertNull("and nothing opens", holder.editor)

            h.os.capability = NotificationCapability.APP_DISABLED
            holder.openAdd()
            assertEquals("the app's settings", "settings", h.os.log.last())
            h.os.capability = NotificationCapability.GRANTED
            h.lane.onSettingsResult()
            assertNull("a settings return runs no old intent", holder.editor)
            holder.openAdd()
            val open = holder.editor
            assertNotNull("granted now: a new tap opens", open)

            h.os.capability = NotificationCapability.RUNTIME_MISSING
            holder.input(open!!, open.input.copy(thresholdText = "1450"))
            holder.submit(open)
            h.grants = false
            h.lane.onRequestResult()
            h.action.onDialogDismiss()
            assertEquals("a refused save sends nothing", 0, r.source.adds.size)
            assertEquals("and keeps the draft", "1450", open.input.thresholdText)
            holder.submit(open)
            h.grants = true
            holder.dispose()
            h.lane.onRequestResult()
            assertEquals("a grant after the dispose sends nothing", 0, r.source.adds.size)
            assertEquals("nothing remembered", 0, r.source.accepted.size)
        } finally {
            scope.cancel()
        }
    }

    @Test fun `S8_5B_J5m the banner's request opens nothing and sends nothing when granted`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val r = Rig(scope)
            r.ready(row(1))
            r.selected(null)
            val h = Helper(r)
            val holder = h.holder(scope)
            holder.requestNotifications()
            assertEquals("the bare request", 1, h.launches())
            h.grants = true
            h.lane.onRequestResult()
            assertNull("no editor", holder.editor)
            assertEquals("no write", 0, r.source.adds.size + r.source.edits.size + r.source.toggles.size + r.source.deletes.size)
            r.ready()
            holder.requestNotifications()
            assertEquals("no banner over an empty list: no request", 1, h.launches())
        } finally {
            scope.cancel()
        }
    }

    // ---- J2: the surfaces around the content ------------------------------------------------------------------------

    @Test fun `S8_5B_J2g the card, the banner and the add follow the content, the permission, the count and the selection`() {
        fun state(content: SourceAlertSectionState.Content) = SourceAlertSectionState(content, 0, false, true)
        val empty = state(SourceAlertSectionState.Content.Empty)
        val rows = state(SourceAlertSectionState.Content.Rows(listOf(row(1))))
        val avail = { n: Int -> SourceAlertAddAvailability.Available(n) }
        fun surf(st: SourceAlertSectionState, a: SourceAlertAddAvailability, settled: Boolean = true, blocked: Boolean = false) =
            sourceAlertSurfaces(st, a, settled, blocked)
        for (st in listOf(state(SourceAlertSectionState.Content.Loading), state(SourceAlertSectionState.Content.Error("오류")))) {
            assertEquals("$st draws nothing around it", SourceAlertSurfaces(false, false, false, null, false, true), surf(st, avail(30), blocked = true))
        }
        assertEquals("empty, blocked: the card, no add button", SourceAlertSurfaces(true, false, false, null, false, true), surf(empty, avail(30), blocked = true))
        assertEquals("empty, blocked, reading: the card's button waits", false, surf(empty, avail(30), settled = false, blocked = true).addEnabled)
        assertEquals("empty, allowed: the add button", SourceAlertSurfaces(false, false, true, null, false, true), surf(empty, avail(30)))
        assertEquals("rows, blocked: the banner over them and the add below", SourceAlertSurfaces(false, true, true, null, false, true), surf(rows, avail(29), blocked = true))
        for ((n, note) in listOf(4 to null, 3 to "(3개 남음)", 1 to "(1개 남음)")) {
            assertEquals("$n left", note, surf(rows, avail(n)).remainingNote)
        }
        assertEquals("at the cap: the notice, no button", SourceAlertSurfaces(false, false, false, null, true, false), surf(rows, SourceAlertAddAvailability.AtCap))
        assertEquals("hidden: nothing to add", SourceAlertSurfaces(false, false, false, null, false, false), surf(rows, SourceAlertAddAvailability.Hidden))
        assertEquals("the selection still read: drawn, not clickable", SourceAlertSurfaces(false, false, true, null, false, false), surf(empty, avail(30), settled = false))
    }

    @Test fun `S8_5B_J2h an edit opens while the selection is still read`() = rig { r ->
        r.ready(row(1))
        r.source.selection.value = SourceSelectionSnapshot.Loading(alice, 1)
        val holder = r.holder()
        holder.openEdit(row(1))
        assertNotNull("an edit does not wait for the selection", holder.editor)
        assertEquals(SourceAlertEditorMode.Edit(row(1)), holder.editor!!.mode)
    }

    // ---- J1 through the real presenter's map ---------------------------------------------------------------------

    private fun presented(rates: com.jay.fxi.domain.model.TopicRates): Map<String, Double> {
        val owner = com.jay.fxi.data.remote.TopicDisplayOwner(alice, 1L)
        val display = com.jay.fxi.data.remote.TopicDisplayState(owner, rates, false, com.jay.fxi.data.remote.TopicConnectionDisplay.OPEN,
            cachedRefreshResolved = true)
        val hidden = mapOf(com.jay.fxi.domain.model.RateRowList.TETHER_EXCHANGES to com.jay.fxi.domain.model.RateRowPreference(hidden = setOf("bithumb")))
        return com.jay.fxi.ui.premium.PremiumTopicPresenter.present(display, com.jay.fxi.data.remote.OwnedTopicFocus(alice, com.jay.fxi.domain.model.FreeTab.TETHER),
            alice, hidden).alertQuotes
    }

    @Test fun `S8_5B_J1i the presenter's map seeds a hidden exchange, refuses one that never arrived, and accepts it once it does`() = rig { r ->
        val t = Instant.parse("2026-11-22T00:00:00Z")
        fun q(source: String, asset: String, rate: Double) = com.jay.fxi.domain.model.TopicQuote(source, asset, rate, t)
        val rates = com.jay.fxi.domain.model.TopicRates().merge(listOf(q("investing", "usd-krw", 1389.0), q("kb", "usd-krw", 1390.0),
            q("upbit", "usdt-krw", 1401.0), q("bithumb", "usdt-krw", 1398.5)))
        r.quotes = presented(rates)
        assertEquals("premise: the hidden exchange's quote, no reference or bank", mapOf("upbit" to 1401.0, "bithumb" to 1398.5), r.quotes)
        r.ready(row(1, threshold = 1300.0))
        r.selected("bithumb")
        val holder = r.holder()
        holder.openAdd()
        val remembered = holder.editor!!
        assertEquals("remembered bithumb: its own quote", input("bithumb", "1399", AlertCondition.BELOW), remembered.input)
        holder.submit(remembered)
        assertEquals("accepted", SourceAlertDraft("bithumb", AlertCondition.BELOW, 1399.0, true, null), r.source.adds.single())
        r.source.handles.single().finish(SourceAlertSaveOutcome.NotSent(NotSentReason.RESET))
        runCurrent()
        r.selected(null)
        holder.openAdd()
        val direct = holder.editor!!
        holder.pick(direct, "bithumb")
        assertEquals("picked bithumb: the same quote", "1399", direct.input.thresholdText)
        holder.pick(direct, "coinone")
        holder.input(direct, direct.input.copy(thresholdText = "1400"))
        holder.submit(direct)
        assertEquals("coinone never arrived: no save", 1, r.source.adds.size)
        assertEquals("현재 시세를 확인할 수 없어 가격 조건을 저장할 수 없습니다", direct.error)
        r.quotes = presented(rates.merge(listOf(q("coinone", "usdt-krw", 1402.0))))
        holder.submit(direct)
        assertEquals("once its quote arrives the same text is saved", SourceAlertDraft("coinone", AlertCondition.BELOW, 1400.0, true, null), r.source.adds.last())
        assertEquals("1400", direct.input.thresholdText)
    }
}
