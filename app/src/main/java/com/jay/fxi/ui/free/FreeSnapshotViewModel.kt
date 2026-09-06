package com.jay.fxi.ui.free

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jay.fxi.data.free.FreeSnapshotKey
import com.jay.fxi.data.free.FreeSnapshotReadState
import com.jay.fxi.data.free.FreeSnapshotScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class FreeSnapshotViewModel internal constructor(
    private val readState: StateFlow<FreeSnapshotReadState>,
    val activityCoordinator: FreeSnapshotActivityCoordinator
) : ViewModel() {
    @Inject constructor(scheduler: FreeSnapshotScheduler) : this(
        scheduler.readState,
        FreeSnapshotActivityCoordinator(scheduler::onActivated, scheduler::onDeactivated)
    )

    private var uid: String? = null
    private var key = FreeSnapshotUiState.DEFAULT_KEY
    private val _uiState = MutableStateFlow(FreeSnapshotUiState())
    val uiState: StateFlow<FreeSnapshotUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            readState.collect { state ->
                uid?.let { _uiState.value = FreeSnapshotUiState.from(it, key, state) }
            }
        }
    }

    /** Synchronous clearing prevents a previous UID or period surviving until the next emission. */
    fun bind(uid: String, key: FreeSnapshotKey = FreeSnapshotUiState.DEFAULT_KEY) {
        require(uid.isNotBlank())
        this.uid = uid
        this.key = key
        _uiState.value = FreeSnapshotUiState.from(uid, key, readState.value)
    }

    override fun onCleared() {
        activityCoordinator.dispose()
    }
}
