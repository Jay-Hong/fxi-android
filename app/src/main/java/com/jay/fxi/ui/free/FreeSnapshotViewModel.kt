package com.jay.fxi.ui.free

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jay.fxi.data.free.FreeSnapshotReadState
import com.jay.fxi.data.free.FreeSnapshotScheduler
import com.jay.fxi.data.local.FreeTabStore
import com.jay.fxi.data.local.FreeVisibleSeriesStore
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.domain.model.FreeSeriesVisibility
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.RateRowRoster
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
    private val tabStore: FreeTabStore,
    private val seriesStore: FreeVisibleSeriesStore,
    private val rowStore: RateRowPreferenceStore
) : ViewModel() {
    @Inject constructor(
        scheduler: FreeSnapshotScheduler,
        tabStore: FreeTabStore,
        seriesStore: FreeVisibleSeriesStore,
        rowStore: RateRowPreferenceStore
    ) : this(
        scheduler.readState,
        FreeSnapshotActivityCoordinator(scheduler::onActivated, scheduler::onDeactivated),
        tabStore,
        seriesStore,
        rowStore
    )

    private var uid: String? = null
    private var tab: FreeTab? = null
    private var period = FreeSnapshotUiState.DEFAULT_PERIOD

    /** Only tabs chosen for. Absent means the tab's default; present-and-empty means all-off. */
    private var storedSeries: Map<FreeTab, Set<String>> = emptyMap()

    /** Only lists chosen for, and each axis of those independently. Absent means the defaults. */
    private var storedRows: Map<RateRowList, RateRowPreference> = emptyMap()
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
        // Read whole, once, so a tab switch afterwards needs no suspension of its own.
        val series = try {
            seriesStore.visibleSeries(uid)
        } catch (_: IOException) {
            emptyMap()
        }
        // Same reason as the series: read whole, once, so no later interaction has to suspend.
        val rows = try {
            rowStore.preferences(uid)
        } catch (_: IOException) {
            emptyMap()
        }
        this.uid = uid
        this.tab = restored
        this.period = FreeSnapshotUiState.DEFAULT_PERIOD
        this.storedSeries = series
        this.storedRows = rows
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

    /**
     * Show or hide one series on the selected tab.
     *
     * After the owner and data-tab checks, [FreeSeriesVisibility.toggle] always adds or removes the
     * requested id. The result therefore differs from the current set and is persisted as an
     * explicit choice.
     */
    fun toggleSeries(seriesId: String) {
        val owner = uid ?: return
        val selected = tab?.takeIf { it.isData } ?: return
        val current = FreeSeriesVisibility.resolve(selected, storedSeries[selected])
        val next = FreeSeriesVisibility.toggle(selected, current, seriesId)
        storedSeries = storedSeries + (selected to next)
        publish()
        viewModelScope.launch {
            try {
                seriesStore.remember(owner, selected, next)
            } catch (_: IOException) {
                // Losing the choice is not worth taking the surface down for.
            }
        }
    }

    /**
     * Adopt what the sheet came back with, for one list.
     *
     * **Only the codes that arrived are the sheet's to speak for.** A payload that carried three
     * banks says nothing about the seventh, so the seventh's stored place and visibility are kept
     * rather than dropped — otherwise opening the sheet on a thin day and pressing 완료 would
     * quietly forget every choice the user made about a source that happened to be absent.
     *
     * An axis the user has not touched stays absent. Recording the arrival order as though it were
     * chosen would freeze the list and send every source the server adds later to the end.
     */
    fun applyRowPreference(
        list: RateRowList,
        seeded: List<String>,
        order: List<String>,
        hidden: Set<String>
    ) {
        val owner = uid ?: return
        val arrived = order.toSet()
        val stored = storedRows[list]
        val defaults = RateRowRoster.hiddenByDefault(list)

        // A code that did not arrive keeps its seat; the sheet's answer is poured into the rest.
        //
        // Seats are held by position, so a source that is off the air today comes back where its
        // neighbours remember it. Everything the sheet showed — codes the stored order has never
        // seen included — is poured into the seats left over, in the order the user arranged, and
        // whatever does not fit follows at the end. An earlier version poured only the codes that
        // already had a seat and appended the rest, which sent a new source the user had just
        // dragged to the front straight back to the end. Found by review.
        //
        // Either way the codes that were on screen keep the order the user gave them.
        val mergedOrder = stored?.order?.let { previous ->
            val pouring = ArrayDeque(order)
            previous.map { if (it in arrived) pouring.removeFirstOrNull() else it }
                .filterNotNull() + pouring
        } ?: order

        val mergedHidden = (stored?.hidden ?: defaults).filterNot { it in arrived }.toSet() + hidden

        val next = RateRowPreference(
            // An order the user did not give is not recorded. Writing the arrival order down
            // because they happened to hide something would freeze the list, and every source the
            // server adds afterwards would land at the end for good.
            order = if (order == seeded) stored?.order else mergedOrder,
            hidden = mergedHidden
        )
        storedRows = storedRows + (list to next)
        publish()
        viewModelScope.launch {
            try {
                rowStore.remember(owner, list, next)
            } catch (_: IOException) {
                // Losing the choice is not worth taking the surface down for.
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
        _uiState.value = FreeSnapshotUiState.from(
            owner, selected, period, readState.value,
            FreeSeriesVisibility.resolve(selected, storedSeries[selected]),
            storedRows
        )
    }

    override fun onCleared() {
        activityCoordinator.dispose()
    }
}
