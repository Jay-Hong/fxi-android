package com.jay.fxi.data.alerts

import android.os.SystemClock
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.remote.AuthenticatedEndpoint
import com.jay.fxi.data.remote.TopicRuntimeOwner
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.remote.dto.AlertSettingUpdateRequest
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.domain.model.AlertSetting
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

internal data class BankAlertSettingsState(
    val phase: AlertLoadPhase,
    val settings: List<AlertSetting>?,
    val identity: AuthIdentityFence?
)

/** S7 E5a1: the header refresh button's state. Not an outcome: it is re-readable, and a dropped manual simply stops being requested. */
internal data class ManualRefreshState(
    /** A manual demand is held by the active chain (it may be waiting for the floor or for access). */
    val requested: Boolean = false,
    /** A manual demand is attached to the active chain's fetch call. This is a logical fetch indicator, not proof of an HTTP send; admission may withhold the call. */
    val sending: Boolean = false,
    /** True while the owner's monotonic cooldown deadline is still in the future. */
    val coolingDown: Boolean = false,
    /** Same value as the owner's cooldownUntil, in injected nowMillis units (production: elapsedRealtime); null after reset. Not wall time. */
    val cooldownUntilMillis: Long? = null
)

internal sealed interface ManualRefreshOutcome {
    data object Succeeded : ManualRefreshOutcome
    data object Failed : ManualRefreshOutcome
    data class CoolingDown(val remainingMillis: Long) : ManualRefreshOutcome
}

/** Main owns requests, snapshots and chains; only a captured use's admission supplier crosses threads. */
@Singleton
internal class BankAlertSettingsOwner internal constructor(
    private val getSettings: suspend (AuthIdentityFence, () -> Boolean) -> AuthBoundResult<List<AlertSetting>>,
    private val access: AlertSettingsAccess,
    private val floor: AlertRateLimitFloor,
    private val policy: AlertRetryPolicy,
    private val nowMillis: () -> Long,
    private val jitter: () -> Double,
    private val scope: CoroutineScope,
    private val sleep: suspend (Long) -> Unit,
    premium: StateFlow<OwnedPremiumAccess>,
    accessRevisions: StateFlow<Long>,
    graphHostReady: Flow<Boolean>,
    private val setEnabledRemote: suspend (AuthIdentityFence, Int, Boolean, () -> Boolean) -> AuthBoundResult<AlertSetting>,
    private val deleteRemote: suspend (AuthIdentityFence, Int, () -> Boolean) -> AuthBoundResult<Unit>,
    private val accessRecheckMillis: Long = 1_000
) : AlertFamilyAdapter {
    @Inject constructor(
        repository: AlertRepository,
        access: AlertSettingsAccess,
        floor: AlertRateLimitFloor,
        coordinator: PremiumAccessCoordinator,
        topic: TopicRuntimeOwner
    ) : this(
        { owner, admitted -> repository.getSettings(owner, admitted) },
        access,
        floor,
        AlertRetryPolicy(),
        SystemClock::elapsedRealtime,
        { Random.nextDouble(-0.2, 0.2) },
        CoroutineScope(SupervisorJob() + Dispatchers.Main),
        { delay(it) },
        coordinator.state,
        coordinator.accessRevisions,
        topic.graphHost.map { it != null },
        { owner, id, enabled, admitted -> repository.updateSettingPartial(owner, id, AlertSettingUpdateRequest(isEnabled = enabled), admitted) },
        { owner, id, admitted -> repository.deleteSetting(owner, id, admitted) }
    )

    private val mutableState = MutableStateFlow(BankAlertSettingsState(AlertLoadPhase.Idle, null, null))
    val state: StateFlow<BankAlertSettingsState> = mutableState.asStateFlow()
    private val mutableManualOutcomes = MutableSharedFlow<ManualRefreshOutcome>(replay = 0, extraBufferCapacity = 1)
    val manualOutcomes: SharedFlow<ManualRefreshOutcome> = mutableManualOutcomes.asSharedFlow()
    private val mutableManualRefresh = MutableStateFlow(ManualRefreshState())
    /** Re-readable header refresh state, not an outcome; a dropped manual simply stops being requested. */
    val manualRefresh: StateFlow<ManualRefreshState> = mutableManualRefresh.asStateFlow()

    private var foreground = false
    private var generation = 0L
    private var job: Job? = null
    private var active: Chain? = null
    private var lastSuccessAt: Long? = null
    private var cooldownUntil: Long? = null
    private var cooldownTimerToken: Any? = null
    private var cooldownTimerJob: Job? = null
    private var manualFailures = 0

    // Mutation ownership survives GET-chain abandonment, but never a reset.
    private var epoch = 0L
    private var revision = 0L
    private val rowOps = mutableMapOf<Int, RowOp>()
    private var reconcileOwed: AuthIdentityFence? = null

    private class RowOp(
        val id: Int,
        val epoch: Long,
        val identity: AuthIdentityFence,
        val token: Any,
        var approved: AlertSetting,
        var position: Int,
        var requestUse: AlertSettingsUse,
        var continuationUse: AlertSettingsUse,
        var desired: Boolean,
        var deleting: Boolean
    ) {
        var version = 0L
        var shown: AlertSetting? = approved.copy()
        var job: Job? = null
        var inFlight = false
        val hints = Channel<Unit>(Channel.CONFLATED)
    }

    private sealed interface MutationResult {
        data class Loaded(val row: AlertSetting) : MutationResult
        data object Deleted : MutationResult
        data class Failed(val failure: Throwable) : MutationResult
        data object Dropped : MutationResult
        data object Unadmitted : MutationResult
    }

    /** A retired chain can finish late, but cannot change any of its replacement's bookkeeping. */
    private class Chain(val generation: Long, var automatic: Boolean, var manual: Boolean) {
        var startRevision = 0L
        var inFetch = false
        var dirty = false
        var waitingAccess = false
        var retryNotBefore: Long? = null
        val hints = Channel<Unit>(Channel.CONFLATED)
    }

    private sealed interface FetchResult {
        data class Loaded(val settings: List<AlertSetting>) : FetchResult
        data class Failed(val failure: AlertFetchFailure) : FetchResult
        data object Silent : FetchResult
    }

    init {
        var previous = premium.value
        scope.launch {
            premium.collect { next ->
                val old = previous
                previous = next
                val identityChanged = old.uid != next.uid || old.authGeneration != next.authGeneration
                val wasPremium = hasPremium(old)
                val isPremium = hasPremium(next)
                if (identityChanged || (wasPremium && !isPremium)) reset()
                if (foreground && isPremium && (identityChanged || !wasPremium)) ensure()
                // A premium publication is also a hint for an already pending access wait.
                hintAccess()
            }
        }
        scope.launch { accessRevisions.collect { hintAccess() } }
        scope.launch { graphHostReady.collect { if (it) hintAccess() } }
    }

    fun ensure() {
        if (!foreground) return
        active?.let {
            it.automatic = true
            return
        }
        val success = lastSuccessAt
        if (reconcileOwed == null && mutableState.value.settings != null && success != null && remaining(addDelay(success, 30_000)) > 0) return
        begin(automatic = true, manual = false)
    }

    fun authoritative() {
        if (!foreground) return
        val chain = active
        if (chain == null) {
            begin(automatic = true, manual = false)
        } else {
            chain.automatic = true
            if (chain.inFetch) chain.dirty = true
        }
    }

    fun manual() {
        if (!foreground) {
            publishManualRefresh()
            return
        }
        val remaining = cooldownUntil?.let(::remaining) ?: 0
        if (remaining > 0) {
            publishManualRefresh()
            mutableManualOutcomes.tryEmit(ManualRefreshOutcome.CoolingDown(remaining))
            return
        }
        val chain = active
        if (chain == null) {
            begin(automatic = false, manual = true)
        } else {
            chain.manual = true
            publishManualRefresh()
        }
    }

    fun reset() {
        epoch++
        val previousOps = rowOps.values.toList()
        rowOps.clear()
        reconcileOwed = null
        previousOps.forEach { it.job?.cancel() }
        cancelCooldownTimer()
        cooldownUntil = null
        abandon()
        lastSuccessAt = null
        manualFailures = 0
        mutableState.value = BankAlertSettingsState(AlertLoadPhase.Idle, null, null)
    }

    override fun onForeground(authoritative: Boolean) {
        foreground = true
        if (authoritative) this.authoritative() else ensure()
    }

    override fun onBackground() {
        foreground = false
        rowOps.values.forEach { it.hints.trySend(Unit) }
        abandon()
        show(AlertLoadPhase.Idle)
    }

    override fun onSync() {
        if (foreground) authoritative()
    }

    /** Main: a target value, with one serial worker per row. */
    fun setEnabled(id: Int, enabled: Boolean) {
        requestRow(id, enabled, deleting = false)
    }

    /** Main: removal takes precedence over any queued toggle for the same row. */
    fun delete(id: Int) {
        requestRow(id, enabled = null, deleting = true)
    }

    private fun requestRow(id: Int, enabled: Boolean?, deleting: Boolean) {
        if (!foreground) return
        val existing = rowOps[id]
        if (existing?.deleting == true) return
        val state = mutableState.value
        val rows = state.settings ?: return
        val row = rows.firstOrNull { it.id == id } ?: return
        val use = access.acquire() ?: return
        if (state.identity != use.identity || !access.admits(use)) return
        if (existing != null && !owns(existing)) return
        val op = existing ?: RowOp(
            id, epoch, use.identity, Any(), row.copy(), rows.indexOf(row),
            use, use, enabled ?: row.isEnabled, deleting
        ).also { rowOps[id] = it }
        op.version++
        op.requestUse = use
        if (deleting) op.deleting = true else op.desired = enabled!!
        display(op, if (deleting) null else optimistic(row, op.desired))
        op.hints.trySend(Unit)
        if (existing == null) {
            val next = scope.launch(start = CoroutineStart.LAZY) { runRow(op) }
            op.job = next
            next.start()
        }
    }

    private fun optimistic(row: AlertSetting, enabled: Boolean): AlertSetting =
        row.copy(isEnabled = enabled, triggered = if (enabled) false else row.triggered)

    private fun owns(op: RowOp): Boolean {
        val current = rowOps[op.id] ?: return false
        val snapshot = mutableState.value
        return current === op && current.token === op.token && op.epoch == epoch &&
            snapshot.settings != null && snapshot.identity == op.identity
    }

    /** Change only the row this op last displayed; a GET or a replacement op cannot be overwritten. */
    private fun display(op: RowOp, row: AlertSetting?) {
        if (!owns(op)) return
        val state = mutableState.value
        val rows = state.settings ?: return
        val index = rows.indexOfFirst { it.id == op.id }
        val current = rows.getOrNull(index)
        if (current != op.shown) return
        val next = rows.toMutableList()
        if (index >= 0) {
            if (row == null) {
                op.position = index
                next.removeAt(index)
            } else next[index] = row.copy()
        } else if (row != null) {
            next.add(op.position.coerceIn(0, next.size), row.copy())
        }
        op.shown = row?.copy()
        if (next != rows) {
            revision++
            mutableState.value = state.copy(settings = next)
        }
    }

    private fun owe(op: RowOp) {
        if (owns(op)) reconcileOwed = op.identity
    }

    private suspend fun awaitRowHint(op: RowOp, millis: Long) = coroutineScope {
        val timer = async(start = CoroutineStart.UNDISPATCHED) { sleep(millis) }
        try {
            select<Unit> {
                op.hints.onReceive { }
                timer.onAwait { }
            }
        } finally {
            timer.cancel()
        }
    }

    private suspend fun runRow(op: RowOp) {
        try {
            while (owns(op)) {
                // A fresh acquire cannot renew an invalid acceptance or a retired attempt.
                if (!foreground ||
                    !access.admits(op.requestUse) || !access.admits(op.continuationUse)) {
                    owe(op)
                    return
                }
                val deleting = op.deleting
                val endpoint = if (deleting) AuthenticatedEndpoint.DELETE_NOTIFICATION_SETTING
                    else AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING
                val wait = floor.remainingMillis(endpoint)
                if (wait > 0) {
                    awaitRowHint(op, wait)
                    continue
                }
                val use = access.acquire()
                if (use == null || use.identity != op.identity || !access.admits(use)) {
                    owe(op)
                    return
                }
                if (!owns(op)) return
                if (floor.remainingMillis(endpoint) > 0) continue
                val version = op.version
                val target = op.desired
                op.inFlight = true
                val result = try {
                    sendRow(op, use, endpoint, deleting, target)
                } finally {
                    op.inFlight = false
                }
                if (!owns(op)) return
                if (!access.admits(use)) {
                    owe(op)
                    return
                }
                when (result) {
                    MutationResult.Dropped -> return
                    MutationResult.Unadmitted -> {
                        owe(op)
                        return
                    }
                    MutationResult.Deleted -> return
                    is MutationResult.Loaded -> {
                        val row = result.row
                        if (row.id != op.id || row.userId != op.identity.uid) {
                            owe(op)
                            return
                        }
                        op.approved = row.copy()
                        op.continuationUse = use
                        if (op.deleting) continue
                        val newerTarget = op.version != version && op.desired != row.isEnabled
                        display(op, if (newerTarget) optimistic(row, op.desired) else row.copy())
                        if (!newerTarget) return
                    }
                    is MutationResult.Failed -> {
                        val failure = result.failure
                        val http = (failure as? AlertRepositoryException)
                            ?.takeIf { it.failureKind == AlertRepositoryFailureKind.HTTP }?.httpFailure
                        if (http?.statusCode == 404) {
                            if (!deleting) {
                                display(op, null)
                                owe(op)
                            }
                            return
                        }
                        val preWire = failure is TopicUseWithheldException && failure.exchanges.isEmpty()
                        val httpFailure = http != null && http.statusCode !in 200..299
                        if ((preWire || httpFailure) && (deleting || !op.deleting)) {
                            display(op, op.approved.copy())
                        }
                        if (!preWire) owe(op)
                        if (failure is AuthIdentityChangedException) return
                        // Only a separately accepted delete may follow a failed toggle.
                        if (!deleting && op.deleting) {
                            op.continuationUse = use
                            continue
                        }
                        return
                    }
                }
            }
        } finally {
            if (rowOps[op.id]?.token === op.token) {
                rowOps.remove(op.id)
                hintAccess()
                if (foreground && rowOps.isEmpty() && reconcileOwed == op.identity) authoritative()
            }
        }
    }

    /** Recover response evidence before any body, ownership, admission or fold check. No mutation replay. */
    private suspend fun sendRow(
        op: RowOp,
        use: AlertSettingsUse,
        endpoint: AuthenticatedEndpoint,
        deleting: Boolean,
        target: Boolean
    ): MutationResult {
        return try {
            val bound: AuthBoundResult<*> = if (deleting) {
                deleteRemote(op.identity, op.id) { access.admits(use) }
            } else {
                setEnabledRemote(op.identity, op.id, target) { access.admits(use) }
            }
            bound.rateLimitResponseEvidence?.let { floor.record(endpoint, AlertRateLimitEvidence.of(it)) }
            if (!owns(op)) return MutationResult.Dropped
            if (!access.admits(use)) return MutationResult.Unadmitted
            bound.fold(
                onSuccess = {
                    if (deleting) MutationResult.Deleted
                    else if (it is AlertSetting) MutationResult.Loaded(it.copy())
                    else MutationResult.Failed(AlertRepositoryException("Invalid row", failureKind = AlertRepositoryFailureKind.DECODE))
                },
                onFailure = { MutationResult.Failed(it) }
            )
        } catch (changed: AuthIdentityChangedException) {
            floor.record(endpoint, AlertRateLimitEvidence.of(changed.exchanges, changed.statusCode, changed.retryAfter))
            MutationResult.Failed(changed)
        } catch (withheld: TopicUseWithheldException) {
            floor.record(endpoint, AlertRateLimitEvidence.of(withheld.exchanges, null, null))
            MutationResult.Failed(withheld)
        } catch (cancelled: CancellationException) {
            MutationResult.Failed(cancelled)
        } catch (failure: Exception) {
            MutationResult.Failed(failure)
        }
    }

    private fun begin(automatic: Boolean, manual: Boolean) {
        val chain = Chain(generation, automatic, manual)
        active = chain
        // Publish the handle before running, even with an eager injected dispatcher.
        val next = scope.launch(start = CoroutineStart.LAZY) { runChain(chain) }
        job = next
        publishManualRefresh()
        next.start()
    }

    private fun abandon() {
        generation++
        active = null
        val previous = job
        job = null
        previous?.cancel()
        publishManualRefresh()
    }

    private fun current(chain: Chain): Boolean =
        foreground && active === chain && chain.generation == generation && job?.isActive == true

    private fun hintAccess() {
        val chain = active ?: return
        if (current(chain) && chain.waitingAccess) chain.hints.trySend(Unit)
    }

    private suspend fun runChain(chain: Chain) {
        var attempt = 0
        try {
            while (current(chain)) {
                val use = awaitUse(chain) ?: return
                if (!current(chain)) return
                // Requests received in any sleep are served by this fetch.
                chain.dirty = false
                chain.startRevision = revision
                chain.inFetch = true
                publishManualRefresh()
                show(if (attempt == 0) AlertLoadPhase.Loading else AlertLoadPhase.Retrying)
                val result = try {
                    fetch(chain, use)
                } finally {
                    if (active === chain) {
                        chain.inFetch = false
                        publishManualRefresh()
                    }
                }
                if (!current(chain)) return
                if (!access.admits(use)) {
                    show(AlertLoadPhase.Idle)
                    return
                }
                when (result) {
                    FetchResult.Silent -> {
                        show(AlertLoadPhase.Idle)
                        return
                    }
                    is FetchResult.Loaded -> {
                        if (revision != chain.startRevision || rowOps.isNotEmpty()) {
                            chain.dirty = true
                            attempt = 0
                            chain.retryNotBefore = null
                            continue
                        }
                        reconcileOwed = null
                        lastSuccessAt = nowMillis()
                        mutableState.value = BankAlertSettingsState(AlertLoadPhase.Ready, result.settings, use.identity)
                        finishManual(chain, succeeded = true)
                        if (!chain.dirty) return
                        // A trailing fetch starts a fresh retry budget.
                        attempt = 0
                        chain.retryNotBefore = null
                    }
                    is FetchResult.Failed -> {
                        val decision = policy.decide(result.failure, attempt, jitter)
                        if (decision == AlertRetryDecision.Silent) {
                            show(AlertLoadPhase.Idle)
                            return
                        }
                        finishManual(chain, succeeded = false)
                        when (decision) {
                            AlertRetryDecision.Silent -> return
                            is AlertRetryDecision.Terminal -> {
                                show(AlertLoadPhase.TerminalError(decision.failure))
                                return
                            }
                            is AlertRetryDecision.Retry -> {
                                if (!chain.automatic) {
                                    val terminal = policy.decide(result.failure, Int.MAX_VALUE, jitter) as AlertRetryDecision.Terminal
                                    show(AlertLoadPhase.TerminalError(terminal.failure))
                                    return
                                }
                                show(AlertLoadPhase.Retrying)
                                chain.retryNotBefore = addDelay(nowMillis(), decision.delayMillis)
                                attempt++
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (current(chain)) show(AlertLoadPhase.Idle)
            throw cancelled
        } finally {
            if (active === chain) {
                active = null
                job = null
                publishManualRefresh()
            }
        }
    }

    /** Every wake and every send rechecks the process floor, independently of account and retry delay. */
    private suspend fun awaitUse(chain: Chain): AlertSettingsUse? {
        while (current(chain)) {
            if (rowOps.isNotEmpty()) {
                chain.waitingAccess = true
                try {
                    if (rowOps.isNotEmpty()) awaitAccessHint(chain)
                } finally {
                    chain.waitingAccess = false
                }
                continue
            }
            val wait = maxOf(
                floor.remainingMillis(AuthenticatedEndpoint.NOTIFICATION_SETTINGS),
                chain.retryNotBefore?.let(::remaining) ?: 0
            )
            if (wait > 0) {
                sleep(wait)
                continue
            }
            val use = access.acquire()
            if (use == null) {
                show(AlertLoadPhase.Idle)
                chain.waitingAccess = true
                try {
                    awaitAccessHint(chain)
                } finally {
                    chain.waitingAccess = false
                }
                continue
            }
            if (!current(chain)) return null
            if (floor.remainingMillis(AuthenticatedEndpoint.NOTIFICATION_SETTINGS) > 0) continue
            if (access.admits(use)) return use
        }
        return null
    }

    /** Signals interrupt this timer, never restart the owning chain or spend a retry. */
    private suspend fun awaitAccessHint(chain: Chain) = coroutineScope {
        val timer = async(start = CoroutineStart.UNDISPATCHED) { sleep(accessRecheckMillis) }
        try {
            select<Unit> {
                chain.hints.onReceive { }
                timer.onAwait { }
            }
        } finally {
            timer.cancel()
        }
    }

    private suspend fun fetch(chain: Chain, use: AlertSettingsUse): FetchResult {
        val bound = try {
            getSettings(use.identity) { access.admits(use) }
        } catch (changed: AuthIdentityChangedException) {
            record(AlertRateLimitEvidence.of(changed.exchanges, changed.statusCode, changed.retryAfter))
            return FetchResult.Silent
        } catch (withheld: TopicUseWithheldException) {
            record(AlertRateLimitEvidence.of(withheld.exchanges, null, null))
            return FetchResult.Silent
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return FetchResult.Failed(classify(failure))
        }
        // Status/header evidence is independent of the guarded body and survives even a retired chain.
        bound.rateLimitResponseEvidence?.let { record(AlertRateLimitEvidence.of(it)) }
        if (!current(chain) || !access.admits(use)) return FetchResult.Silent
        return try {
            bound.fold(
                onSuccess = { FetchResult.Loaded(it) },
                onFailure = { FetchResult.Failed(classify(it)) }
            )
        } catch (changed: AuthIdentityChangedException) {
            // The result's evidence was already recorded before fold's identity check.
            FetchResult.Silent
        } catch (withheld: TopicUseWithheldException) {
            FetchResult.Silent
        }
    }

    private fun record(evidence: AlertRateLimitEvidence) {
        floor.record(AuthenticatedEndpoint.NOTIFICATION_SETTINGS, evidence)
    }

    private fun classify(failure: Throwable): AlertFetchFailure = when (failure) {
        is AlertRepositoryException -> failure.httpFailure?.let {
            AlertFetchFailure.Http(it.statusCode, AlertRetryAfter.parseSeconds(it.retryAfter))
        } ?: AlertFetchFailure.Repository(failure.failureKind)
        is AuthUnavailableException -> AlertFetchFailure.Repository(AlertRepositoryFailureKind.AUTH_UNAVAILABLE)
        is IOException -> AlertFetchFailure.Repository(AlertRepositoryFailureKind.NETWORK)
        else -> AlertFetchFailure.Repository(AlertRepositoryFailureKind.UNEXPECTED)
    }

    private fun finishManual(chain: Chain, succeeded: Boolean) {
        if (active !== chain || !chain.manual) return
        chain.manual = false
        val cooldown = if (succeeded) {
            manualFailures = 0
            3_000L
        } else {
            manualFailures = (manualFailures + 1).coerceAtMost(3)
            5_000L * (1L shl (manualFailures - 1))
        }
        cooldownUntil = addDelay(nowMillis(), cooldown)
        startCooldownTimer(cooldownUntil!!)
        mutableManualOutcomes.tryEmit(if (succeeded) ManualRefreshOutcome.Succeeded else ManualRefreshOutcome.Failed)
    }

    /** Recompute from the live chain, including when a timer wakes after a new manual joined. */
    private fun publishManualRefresh() {
        val chain = active
        val requested = chain?.manual == true
        val deadline = cooldownUntil
        mutableManualRefresh.value = ManualRefreshState(
            requested = requested,
            sending = requested && chain?.inFetch == true,
            coolingDown = deadline != null && remaining(deadline) > 0,
            cooldownUntilMillis = deadline
        )
    }

    private fun cancelCooldownTimer() {
        cooldownTimerToken = null
        val previous = cooldownTimerJob
        cooldownTimerJob = null
        previous?.cancel()
    }

    private fun startCooldownTimer(deadline: Long) {
        cancelCooldownTimer()
        val token = Any()
        cooldownTimerToken = token
        // Install before start, as with request chains, even on an eager dispatcher.
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (cooldownTimerToken === token && cooldownUntil == deadline) {
                    val wait = remaining(deadline)
                    if (wait == 0L) {
                        publishManualRefresh()
                        return@launch
                    }
                    sleep(wait)
                }
            } finally {
                if (cooldownTimerToken === token) {
                    cooldownTimerJob = null
                }
            }
        }
        cooldownTimerJob = next
        publishManualRefresh()
        next.start()
    }

    private fun show(phase: AlertLoadPhase) {
        val previous = mutableState.value
        mutableState.value = previous.copy(phase = if (previous.settings != null) AlertLoadPhase.Ready else phase)
    }

    private fun hasPremium(value: OwnedPremiumAccess): Boolean =
        value.uid != null && value.authGeneration != null && value.state == PremiumAccessState.PremiumConfirmed

    private fun addDelay(now: Long, delay: Long): Long =
        if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay

    private fun remaining(deadline: Long): Long {
        val now = nowMillis()
        if (deadline <= now) return 0
        return if (now < 0 && deadline > Long.MAX_VALUE + now) Long.MAX_VALUE else deadline - now
    }
}
