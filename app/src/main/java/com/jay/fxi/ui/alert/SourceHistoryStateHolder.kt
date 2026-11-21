package com.jay.fxi.ui.alert

import com.jay.fxi.data.alerts.AlertRateLimitEvidence
import com.jay.fxi.data.alerts.AlertSettingsUse
import com.jay.fxi.data.alerts.SourceAlertRowPolicy
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.remote.dto.SourceAlertHistoryItem
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.domain.model.Exchange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Production reads repository.getSourceHistory(identity, asset, 100, useAdmitted). */
internal fun interface SourceHistoryRead {
    suspend fun read(
        owner: AuthIdentityFence,
        asset: String,
        useAdmitted: () -> Boolean
    ): AuthBoundResult<List<SourceAlertHistoryItem>>
}

/** The access one history request runs under, checked against the screen owner it was opened for. */
internal interface SourceHistoryAccess {
    /** Main. A use for one new request shown under [owner] and of its identity, or null. */
    fun acquire(owner: TopicDisplayOwner): AlertSettingsUse?

    /** Any thread, non-blocking, side-effect free: whether [use] may still send under [owner], and its answer still be applied. */
    fun admits(owner: TopicDisplayOwner, use: AlertSettingsUse): Boolean
}

/** The source logs endpoint's process floor, as this holder reads and records it (Main). */
internal interface SourceHistoryFloor {
    fun remainingMillis(): Long
    fun record(evidence: AlertRateLimitEvidence)
}

internal sealed interface SourceHistoryBody {
    data object Loading : SourceHistoryBody
    data class Loaded(val items: List<SourceAlertHistoryItem>, val refreshing: Boolean) : SourceHistoryBody
    data object Empty : SourceHistoryBody
    data class Failed(val message: String, val temporary: Boolean) : SourceHistoryBody
}

internal data class SourceHistorySheetState(
    val owner: TopicDisplayOwner,
    val asset: String,
    val body: SourceHistoryBody
)

/**
 * One user-opened tether history session, on the route's Main scope. It ends for good when closed or when its access is lost:
 * the sheet state goes to null, [onRetired] runs once, and a new session needs a new holder (a new click). Every request
 * (first, retry, pull) first waits out the logs floor, then reads the session's one asset under its own use; the answer's
 * floor evidence is recorded before the fold, and only rows of that asset and a known exchange are kept.
 */
internal class SourceHistoryStateHolder(
    private val access: SourceHistoryAccess,
    private val read: SourceHistoryRead,
    private val floor: SourceHistoryFloor,
    private val scope: CoroutineScope,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val onRetired: () -> Unit = {}
) {
    private data class Session(val owner: TopicDisplayOwner, val asset: String)

    private class Request(val session: Session, val use: AlertSettingsUse) {
        /** Set on Main before the request loses ownership; the transport guard reads it. */
        @Volatile var retired = false
        // Main only; the transport guard reads neither this job nor any sheet state.
        var job: Job? = null
    }

    private val backingState = MutableStateFlow<SourceHistorySheetState?>(null)
    val state: StateFlow<SourceHistorySheetState?> = backingState.asStateFlow()

    private var ended = false
    private var session: Session? = null
    private var displayedUse: AlertSettingsUse? = null

    @Volatile
    private var active: Request? = null

    /** Whether this holder can still show anything; once false it never becomes true again. */
    val live: Boolean get() = !ended

    /** Main. Opens the session once; a repeated open of the same owner joins it. */
    fun open(owner: TopicDisplayOwner) {
        if (ended) return
        val opened = session
        if (opened != null) {
            if (opened.owner != owner) retire()
            return
        }
        if (!scope.isActive) {
            retire()
            return
        }
        val fresh = Session(owner, SourceAlertRowPolicy.SETTINGS_ASSET)
        session = fresh
        startRequest(fresh, SourceHistoryBody.Loading)
    }

    /** Main. Only from a shown failure, with no request in flight. */
    fun retry() {
        val shown = currentState() ?: return
        if (active != null || shown.body !is SourceHistoryBody.Failed) return
        val opened = session ?: return
        startRequest(opened, SourceHistoryBody.Loading)
    }

    /** Main. Only from shown rows, with no request in flight; the rows stay while it waits and sends. */
    fun refresh() {
        val shown = currentState() ?: return
        if (active != null) return
        val loaded = shown.body as? SourceHistoryBody.Loaded ?: return
        val opened = session ?: return
        startRequest(opened, loaded.copy(refreshing = true))
    }

    /** Main. Dismissal: ends the session for good. */
    fun close() = retire()

    /** Main. The state to draw now, re-checking the shown owner, tab and the last request's use first. */
    fun currentState(): SourceHistorySheetState? {
        if (ended) return null
        if (!scope.isActive) {
            retire()
            return null
        }
        val opened = session ?: return null
        val use = displayedUse
        if (use != null && !access.admits(opened.owner, use)) {
            retire()
            return null
        }
        return backingState.value
    }

    private fun startRequest(opened: Session, body: SourceHistoryBody) {
        val use = access.acquire(opened.owner)?.takeIf { it.identity == opened.owner.identity }
        if (use == null || !scope.isActive) {
            retire()
            return
        }
        displayedUse = use
        backingState.value = opened.sheet(body)
        val request = Request(opened, use)
        // Lazy start installs both owners before even a non-suspending read can finish.
        val job = scope.launch(start = CoroutineStart.LAZY) { execute(request) }
        request.job = job
        active = request
        job.start()
    }

    /** Main. Whether [request] still owns the session and its use is still admitted; a lost access ends the session. */
    private fun stillOwns(request: Request): Boolean {
        if (active !== request || request.retired || ended) return false
        if (!scope.isActive || !access.admits(request.session.owner, request.use)) {
            retire()
            return false
        }
        return true
    }

    private suspend fun execute(request: Request) {
        try {
            // The process floor of the logs endpoint: wait it out, re-checking at every wake (it can be extended meanwhile).
            while (true) {
                if (!stillOwns(request)) return
                val remaining = floor.remainingMillis()
                if (remaining <= 0) break
                sleep(remaining)
            }
            val bound = try {
                read.read(request.session.owner.identity, request.session.asset) {
                    !request.retired && active === request && scope.isActive && access.admits(request.session.owner, request.use)
                }
            } catch (changed: AuthIdentityChangedException) {
                floor.record(AlertRateLimitEvidence.of(changed.exchanges, changed.statusCode, changed.retryAfter))
                lost(request)
                return
            } catch (withheld: TopicUseWithheldException) {
                floor.record(AlertRateLimitEvidence.of(withheld.exchanges, null, null))
                lost(request)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                completeFailure(request, failure)
                return
            }
            // Status and header evidence is independent of the guarded body: it is recorded before any adoption check.
            bound.rateLimitResponseEvidence?.let { floor.record(AlertRateLimitEvidence.of(it)) }
            val outcome: Result<List<SourceAlertHistoryItem>> = try {
                bound.fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(it) })
            } catch (changed: AuthIdentityChangedException) {
                lost(request)
                return
            } catch (withheld: TopicUseWithheldException) {
                lost(request)
                return
            }
            outcome.fold(
                onSuccess = { items ->
                    val kept = items.filter { it.asset == request.session.asset && Exchange.fromCode(it.source) != null }
                    commit(request, if (kept.isEmpty()) SourceHistoryBody.Empty else SourceHistoryBody.Loaded(kept, refreshing = false))
                },
                onFailure = { completeFailure(request, it) }
            )
        } finally {
            // An old completion must never remove the next request's ownership or spinner.
            if (active === request) {
                active = null
                request.job = null
            }
        }
    }

    /** A withdrawal or identity refusal publishes no error and no retry: if it is still this session's request, the session ends. */
    private fun lost(request: Request) {
        if (active === request && !request.retired) retire()
    }

    private fun completeFailure(request: Request, error: Throwable) {
        val body = if (error is AlertRepositoryException) {
            SourceHistoryBody.Failed(
                message = error.message ?: BANK_HISTORY_GENERIC_FAILURE,
                temporary = error.httpFailure?.statusCode == 503 || error.httpFailure?.retryAfter != null
            )
        } else {
            SourceHistoryBody.Failed(BANK_HISTORY_GENERIC_FAILURE, temporary = false)
        }
        commit(request, body)
    }

    /** The only gate through which a read completion can publish state. */
    private fun commit(request: Request, body: SourceHistoryBody) {
        if (!stillOwns(request)) return
        backingState.value = request.session.sheet(body)
        active = null
        request.job = null
    }

    /** Main. Ends the session: ownership and visible data first, then the job, then the owner's release. */
    private fun retire() {
        if (ended) return
        ended = true
        val previous = active
        active = null
        previous?.retired = true
        val previousJob = previous?.job
        previous?.job = null
        session = null
        displayedUse = null
        backingState.value = null
        previousJob?.cancel()
        onRetired()
    }

    private fun Session.sheet(body: SourceHistoryBody) = SourceHistorySheetState(owner, asset, body)
}
