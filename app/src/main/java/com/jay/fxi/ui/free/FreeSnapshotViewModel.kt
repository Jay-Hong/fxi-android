package com.jay.fxi.ui.free

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jay.fxi.data.free.FreeSnapshotReadState
import com.jay.fxi.data.free.FreeSnapshotScheduler
import com.jay.fxi.data.local.FreeTabStore
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class FreeSnapshotViewModel internal constructor(
    private val readState: StateFlow<FreeSnapshotReadState>,
    val activityCoordinator: FreeSnapshotActivityCoordinator,
    private val tabStore: FreeTabStore
) : ViewModel() {
    @Inject constructor(scheduler: FreeSnapshotScheduler, tabStore: FreeTabStore) : this(
        scheduler.readState,
        FreeSnapshotActivityCoordinator(scheduler::onActivated, scheduler::onDeactivated),
        tabStore
    )

    private var uid: String? = null
    private var tab: FreeTab? = null
    private var period = FreeSnapshotUiState.DEFAULT_PERIOD
    private val _uiState = MutableStateFlow(FreeSnapshotUiState())
    val uiState: StateFlow<FreeSnapshotUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { readState.collect { publish() } }
    }

    /**
     * Adopt [uid] and reopen where it left off.
     *
     * Nothing is published with a selection until the stored one has been read, so the surface
     * never activates 달러 on its way to a user whose tab is 테더. Until then it shows the same
     * neutral state it shows before any snapshot lands, which is what it would be showing anyway.
     */
    suspend fun bind(uid: String) {
        require(uid.isNotBlank())
        if (this.uid == uid) return
        // Drop the previous owner first: a suspension follows, and their values must not render
        // for even one frame under someone else's identity.
        this.uid = null
        this.tab = null
        _uiState.value = FreeSnapshotUiState()
        val restored = try {
            tabStore.lastTab(uid)
        } catch (_: IOException) {
            // A preference file that cannot be read is a reason to open on 달러, not a reason for
            // the only surface a free user has to fail to open at all.
            FreeTab.INITIAL
        }
        this.uid = uid
        this.tab = restored
        this.period = FreeSnapshotUiState.DEFAULT_PERIOD
        publish()
    }

    /** Called by the surface for each settled selection. Persisting it is this class's business. */
    fun selectTab(tab: FreeTab) {
        val owner = uid ?: return
        if (this.tab == tab) return
        this.tab = tab
        publish()
        viewModelScope.launch {
            try {
                tabStore.remember(owner, tab)
            } catch (_: IOException) {
                // Losing the restore point is not worth taking the surface down for.
            }
        }
    }

    fun selectPeriod(period: GraphPeriod) {
        if (uid == null || this.period == period) return
        this.period = period
        publish()
    }

    /** Synchronous, so a previous UID or selection never survives until the next emission. */
    private fun publish() {
        val owner = uid ?: return
        val selected = tab ?: return
        _uiState.value = FreeSnapshotUiState.from(owner, selected, period, readState.value)
    }

    override fun onCleared() {
        activityCoordinator.dispose()
    }
}
