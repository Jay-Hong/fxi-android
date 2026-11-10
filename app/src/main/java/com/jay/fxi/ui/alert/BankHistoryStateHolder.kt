package com.jay.fxi.ui.alert

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.remote.dto.AlertHistoryItem
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.BankHistoryAccess
import com.jay.fxi.data.repository.BankHistoryUse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val BANK_HISTORY_GENERIC_FAILURE = "히스토리를 불러오지 못했습니다"

/** Production reads repository.getHistory(owner, currency, 100, useAdmitted). */
internal fun interface BankHistoryRead {
    suspend fun read(
        owner: AuthIdentityFence,
        currency: String,
        useAdmitted: () -> Boolean
    ): AuthBoundResult<List<AlertHistoryItem>>
}

internal sealed interface BankHistoryBody {
    data object Loading : BankHistoryBody
    data class Loaded(val items: List<AlertHistoryItem>, val refreshing: Boolean) : BankHistoryBody
    data object Empty : BankHistoryBody
    data class Failed(val message: String, val temporary: Boolean) : BankHistoryBody
}

internal data class BankHistorySheetState(
    val owner: TopicDisplayOwner,
    val currency: String,
    val body: BankHistoryBody
)

/** Public entry points and state publication run on the route's Main scope. */
internal class BankHistoryStateHolder(
    private val access: BankHistoryAccess,
    private val read: BankHistoryRead,
    private val scope: CoroutineScope
) {
    private data class Session(val generation: Long, val owner: TopicDisplayOwner, val currency: String)

    private class Request(val session: Session, val use: BankHistoryUse) {
        // Main only; the transport guard reads neither this job nor any sheet state.
        var job: Job? = null
    }

    private val backingState = MutableStateFlow<BankHistorySheetState?>(null)
    val state: StateFlow<BankHistorySheetState?> = backingState.asStateFlow()

    private var generation = 0L
    private var closed = true
    private var session: Session? = null
    private var displayedUse: BankHistoryUse? = null

    @Volatile
    private var active: Request? = null

    fun open(owner: TopicDisplayOwner, currency: String) {
        val shown = currentState()
        if (!scope.isActive) return
        if (shown != null && shown.owner == owner && shown.currency == currency) return

        close()
        val opened = Session(++generation, owner, currency)
        session = opened
        closed = false
        startRequest(opened, BankHistoryBody.Loading)
    }

    fun retry() {
        val shown = currentState() ?: return
        if (closed || active != null || shown.body !is BankHistoryBody.Failed) return
        val opened = session ?: return
        startRequest(opened, BankHistoryBody.Loading)
    }

    fun refresh() {
        val shown = currentState() ?: return
        if (closed || active != null) return
        val loaded = shown.body as? BankHistoryBody.Loaded ?: return
        val opened = session ?: return
        startRequest(opened, loaded.copy(refreshing = true))
    }

    fun close() {
        val previous = active
        val previousJob = previous?.job
        ++generation
        active = null
        closed = true
        session = null
        displayedUse = null
        backingState.value = null
        previous?.job = null
        // Revoke ownership and visible data before cancellation can resume an old completion.
        previousJob?.cancel()
    }

    fun currentState(): BankHistorySheetState? {
        if (closed || session == null || !scope.isActive) {
            close()
            return null
        }
        val use = displayedUse
        if (use != null && !access.admits(use)) {
            retireAccess()
        }
        return backingState.value
    }

    private fun startRequest(opened: Session, body: BankHistoryBody) {
        if (!scope.isActive) {
            close()
            return
        }
        val use = access.acquire(opened.owner)
        displayedUse = use
        if (use == null) {
            backingState.value = opened.sheet(genericFailure())
            return
        }

        backingState.value = opened.sheet(body)
        val request = Request(opened, use)
        // Lazy start installs both owners before even a non-suspending read can finish.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            execute(request)
        }
        request.job = job
        active = request
        job.start()
    }

    private suspend fun execute(request: Request) {
        try {
            val result = try {
                read.read(request.session.owner.identity, request.session.currency) {
                    active === request && scope.isActive && access.admits(request.use)
                }
            } catch (error: Exception) {
                completeFailure(request, error)
                return
            }

            // These callbacks only capture the result. Cancellation processing and commits
            // happen outside the fold's identity-check exception boundary.
            val outcome: Result<List<AlertHistoryItem>> = try {
                result.fold(
                    onSuccess = { Result.success(it) },
                    onFailure = { Result.failure(it) }
                )
            } catch (error: Exception) {
                completeFailure(request, error)
                return
            }

            outcome.fold(
                onSuccess = { items ->
                    val matching = items.filter { it.currency == request.session.currency }
                    val body = if (matching.isEmpty()) BankHistoryBody.Empty
                    else BankHistoryBody.Loaded(matching, refreshing = false)
                    commit(request, body)
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

    private fun completeFailure(request: Request, error: Throwable) {
        when (error) {
            is AuthIdentityChangedException, is TopicUseWithheldException ->
                commit(request, genericFailure())
            is CancellationException -> {
                commit(request, genericFailure())
                throw error
            }
            is AlertRepositoryException -> commit(
                request,
                BankHistoryBody.Failed(
                    message = error.message ?: BANK_HISTORY_GENERIC_FAILURE,
                    temporary = error.httpFailure?.statusCode == 503 || error.httpFailure?.retryAfter != null
                )
            )
            else -> commit(request, genericFailure())
        }
    }

    /** The only gate through which a read completion can publish state. */
    private fun commit(request: Request, body: BankHistoryBody) {
        if (active !== request) return
        if (!scope.isActive) {
            close()
            return
        }
        if (!access.admits(request.use)) {
            retireAccess()
            return
        }
        backingState.value = request.session.sheet(body)
        active = null
        request.job = null
        // Keep displayedUse after completion so render-time access checks still retire data.
    }

    private fun retireAccess() {
        val previous = active
        val previousJob = previous?.job
        active = null
        displayedUse = null
        backingState.value = session?.sheet(genericFailure())
        previous?.job = null
        previousJob?.cancel()
    }

    private fun Session.sheet(body: BankHistoryBody) = BankHistorySheetState(owner, currency, body)

    private fun genericFailure() = BankHistoryBody.Failed(BANK_HISTORY_GENERIC_FAILURE, temporary = false)
}
