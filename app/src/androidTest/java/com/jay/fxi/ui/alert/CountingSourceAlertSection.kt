package com.jay.fxi.ui.alert

import android.content.Context
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.alerts.SourceAlertDraft
import com.jay.fxi.data.alerts.SourceAlertEdit
import com.jay.fxi.data.alerts.SourceAlertSaveRequest
import com.jay.fxi.data.alerts.SourceAlertSettingsState
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.local.SourceSelectionSnapshot
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * S8-4a harness (`R4c/S8/s8_4a_contract.agreed.r1.md` §3): a tether section source that counts what is asked of it and does
 * nothing else. Every making hands out the same source, as the production factory exposes the one singleton. A route harness
 * that reaches the tether tab passes it, so the default production factory never resolves the process owner in a test.
 */
internal class CountingSourceAlertSection : SourceAlertSectionSourceFactory {
    val created = AtomicInteger()
    val ensures = AtomicInteger()
    val authoritatives = AtomicInteger()
    val manuals = AtomicInteger()
    val settings = MutableStateFlow(SourceAlertSettingsState(AlertLoadPhase.Idle, null, null))
    val manualRefresh = MutableStateFlow(ManualRefreshState())
    /** S8-5b: the memory's snapshot, its prepares and accepted adds, and every write with its arguments. */
    val selection = MutableStateFlow<SourceSelectionSnapshot>(SourceSelectionSnapshot.None)
    val prepares = CopyOnWriteArrayList<AuthIdentityFence>()
    val accepted = CopyOnWriteArrayList<Pair<AuthIdentityFence, String>>()
    val adds = CopyOnWriteArrayList<SourceAlertDraft>()
    val edits = CopyOnWriteArrayList<Pair<Int, SourceAlertEdit>>()
    val toggles = CopyOnWriteArrayList<Pair<Int, Boolean>>()
    val deletes = CopyOnWriteArrayList<Int>()
    /** Each add's or edit's handle; it stays Pending unless the test finishes it. */
    val handles = CopyOnWriteArrayList<SourceAlertSaveRequest>()
    private val source = object : SourceAlertSectionSource {
        override val settings = this@CountingSourceAlertSection.settings
        override val manualRefresh = this@CountingSourceAlertSection.manualRefresh
        override val selection = this@CountingSourceAlertSection.selection
        override suspend fun prepareSelection(identity: AuthIdentityFence) { prepares += identity }
        override fun acceptAddedSource(identity: AuthIdentityFence, sourceCode: String) { accepted += identity to sourceCode }
        override fun ensure() { ensures.incrementAndGet() }
        override fun authoritative() { authoritatives.incrementAndGet() }
        override fun manual() { manuals.incrementAndGet() }
        override fun addAlert(draft: SourceAlertDraft): SourceAlertSaveRequest {
            adds += draft
            return SourceAlertSaveRequest().also { handles += it }
        }
        override fun editAlert(id: Int, edit: SourceAlertEdit): SourceAlertSaveRequest {
            edits += id to edit
            return SourceAlertSaveRequest().also { handles += it }
        }
        override fun setEnabled(id: Int, enabled: Boolean) { toggles += id to enabled }
        override fun delete(id: Int) { deletes += id }
    }

    override fun create(context: Context): SourceAlertSectionSource = source.also { created.incrementAndGet() }

    /** (settings subscribers, manualRefresh subscribers). */
    fun subs() = settings.subscriptionCount.value to manualRefresh.subscriptionCount.value
}
