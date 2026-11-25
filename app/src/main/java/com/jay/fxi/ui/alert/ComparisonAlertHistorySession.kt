package com.jay.fxi.ui.alert

import android.content.Context
import com.jay.fxi.data.alerts.AlertSettingsAccess
import com.jay.fxi.data.alerts.AlertSettingsUse
import com.jay.fxi.data.alerts.ComparisonAlertRowPolicy
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.remote.dto.ComparisonAlertHistoryItem
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.DeletionAdmissionRevisions
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ComparisonAlertHistoryEntryPoint {
    fun alertSettingsAccess(): AlertSettingsAccess
    fun alertRepository(): AlertRepository
    fun premiumAccessCoordinator(): PremiumAccessCoordinator
    fun deletionAdmissionRevisions(): DeletionAdmissionRevisions
}

internal fun interface ComparisonHistoryRead {
    suspend fun read(owner: TopicDisplayOwner, tab: String, diffType: String, useAdmitted: () -> Boolean):
        AuthBoundResult<List<ComparisonAlertHistoryItem>>
}

internal fun comparisonHistoryRead(repository: AlertRepository) =
    ComparisonHistoryRead { owner, tab, diffType, useAdmitted ->
        repository.getComparisonHistory(owner.identity, tab, diffType, 100, useAdmitted)
    }

internal data class ComparisonHistoryWiring(
    val access: AlertSettingsAccess,
    val read: ComparisonHistoryRead,
    val accessRevisions: StateFlow<Long>,
    val deletionRevisions: StateFlow<Long>
)

internal fun interface ComparisonAlertHistoryWiringFactory {
    fun create(context: Context): ComparisonHistoryWiring
}

internal object ProductionComparisonAlertHistoryWiring : ComparisonAlertHistoryWiringFactory {
    override fun create(context: Context): ComparisonHistoryWiring {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, ComparisonAlertHistoryEntryPoint::class.java)
        return ComparisonHistoryWiring(entry.alertSettingsAccess(), comparisonHistoryRead(entry.alertRepository()),
            entry.premiumAccessCoordinator().accessRevisions, entry.deletionAdmissionRevisions().revisions)
    }
}

internal sealed interface ComparisonHistoryBody {
    data object Loading : ComparisonHistoryBody
    data object Empty : ComparisonHistoryBody
    data class Loaded(val items: List<ComparisonAlertHistoryItem>, val refreshing: Boolean = false) : ComparisonHistoryBody
    data class Failed(val temporary: Boolean) : ComparisonHistoryBody {
        val message get() = if (temporary) "구독 확인 중입니다. 잠시 후 다시 시도해주세요." else "히스토리를 불러오지 못했습니다."
    }
}

/** The mount retains the active request across dismiss/reopen. A late finally can release only its own request. */
internal class ComparisonRequestSlot {
    class Request(val use: AlertSettingsUse) {
        var job: Job? = null
    }

    @Volatile var active: Request? = null
}

/** Main owns this one opening. Only the transport predicate reads the request identity off Main. */
internal class ComparisonAlertHistorySession(
    private val owner: TopicDisplayOwner,
    private val tab: String,
    private val diffType: String,
    private val wiring: ComparisonHistoryWiring,
    private val scope: CoroutineScope,
    private val requests: ComparisonRequestSlot,
    private val shown: () -> Boolean,
    private val removeSession: (ComparisonAlertHistorySession) -> Unit
) {
    private var displayedUse: AlertSettingsUse? = null
    private var ended = false
    private var stopListening: (() -> Unit)? = null
    private var rechecks: Job? = null
    private val body = MutableStateFlow<ComparisonHistoryBody?>(null)
    val state = body.asStateFlow()

    fun open(listen: (() -> Unit) -> (() -> Unit)) {
        if (ended || stopListening != null) return
        val stop = listen(::close)
        if (ended) {
            stop()
            return
        }
        stopListening = stop
        rechecks = scope.launch(start = CoroutineStart.LAZY) {
            try {
                merge(wiring.accessRevisions, wiring.deletionRevisions).collect { currentState() }
            } finally {
                close()
            }
        }
        rechecks?.start()
        startRequest(ComparisonHistoryBody.Loading)
    }

    fun retry() {
        if (currentState() is ComparisonHistoryBody.Failed && requests.active == null) startRequest(ComparisonHistoryBody.Loading)
    }

    fun refresh() {
        val loaded = currentState() as? ComparisonHistoryBody.Loaded ?: return
        if (requests.active == null) startRequest(loaded.copy(refreshing = true))
    }

    fun currentState(): ComparisonHistoryBody? {
        if (!ended && (!scope.isActive || !shown() || displayedUse?.let(wiring.access::admits) == false)) close()
        return body.value
    }

    private fun startRequest(next: ComparisonHistoryBody) {
        if (ended) return
        if (!scope.isActive || !shown() || ended) {
            close()
            return
        }
        val use = wiring.access.acquire()
        if (use == null || use.identity != owner.identity || ended) {
            close()
            return
        }
        val request = ComparisonRequestSlot.Request(use)
        request.job = scope.launch(start = CoroutineStart.LAZY) { execute(request) }
        requests.active = request
        displayedUse = use
        body.value = next
        request.job?.start()
    }

    private fun owns(request: ComparisonRequestSlot.Request): Boolean =
        requests.active === request && currentState() != null && requests.active === request

    private suspend fun execute(request: ComparisonRequestSlot.Request) {
        try {
            if (!owns(request)) return
            val bound = wiring.read.read(owner, tab, diffType) {
                requests.active === request && scope.isActive && wiring.access.admits(request.use)
            }
            if (!owns(request)) return
            val items = bound.fold(onSuccess = { it }, onFailure = { throw it })
            val kept = ComparisonAlertRowPolicy.adoptHistory(tab, diffType, items)
            if (owns(request)) {
                body.value = if (kept.isEmpty()) ComparisonHistoryBody.Empty else ComparisonHistoryBody.Loaded(kept)
            }
        } catch (failure: Exception) {
            when (failure) {
                is AuthIdentityChangedException, is TopicUseWithheldException, is CancellationException ->
                    if (requests.active === request) close()
                else -> if (owns(request)) {
                    body.value = ComparisonHistoryBody.Failed((failure as? AlertRepositoryException)?.httpFailure?.statusCode == 503)
                }
            }
        } finally {
            if (requests.active === request) requests.active = null
        }
    }

    fun close() {
        if (ended) return
        ended = true
        val job = requests.active?.job
        requests.active = null
        displayedUse = null
        body.value = null
        job?.cancel()
        onRetired()
    }

    private fun onRetired() {
        stopListening?.invoke()
        stopListening = null
        rechecks?.cancel()
        rechecks = null
        removeSession(this)
    }
}
