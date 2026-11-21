package com.jay.fxi.ui.alert

import android.content.Context
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.alerts.SourceAlertSettingsState
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
    private val source = object : SourceAlertSectionSource {
        override val settings = this@CountingSourceAlertSection.settings
        override val manualRefresh = this@CountingSourceAlertSection.manualRefresh
        override fun ensure() { ensures.incrementAndGet() }
        override fun authoritative() { authoritatives.incrementAndGet() }
        override fun manual() { manuals.incrementAndGet() }
    }

    override fun create(context: Context): SourceAlertSectionSource = source.also { created.incrementAndGet() }

    /** (settings subscribers, manualRefresh subscribers). */
    fun subs() = settings.subscriptionCount.value to manualRefresh.subscriptionCount.value
}
