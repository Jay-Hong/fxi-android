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
        topic.graphHost.map { it != null }
    )

    private val mutableState = MutableStateFlow(BankAlertSettingsState(AlertLoadPhase.Idle, null, null))
    val state: StateFlow<BankAlertSettingsState> = mutableState.asStateFlow()
    private val mutableManualOutcomes = MutableSharedFlow<ManualRefreshOutcome>(replay = 0, extraBufferCapacity = 1)
    val manualOutcomes: SharedFlow<ManualRefreshOutcome> = mutableManualOutcomes.asSharedFlow()

    private var foreground = false
    private var generation = 0L
    private var job: Job? = null
    private var active: Chain? = null
    private var lastSuccessAt: Long? = null
    private var cooldownUntil: Long? = null
    private var manualFailures = 0

    /** A retired chain can finish late, but cannot change any of its replacement's bookkeeping. */
    private class Chain(val generation: Long, var automatic: Boolean, var manual: Boolean) {
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
        if (mutableState.value.settings != null && success != null && remaining(addDelay(success, 30_000)) > 0) return
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
        if (!foreground) return
        val remaining = cooldownUntil?.let(::remaining) ?: 0
        if (remaining > 0) {
            mutableManualOutcomes.tryEmit(ManualRefreshOutcome.CoolingDown(remaining))
            return
        }
        val chain = active
        if (chain == null) begin(automatic = false, manual = true) else chain.manual = true
    }

    fun reset() {
        abandon()
        lastSuccessAt = null
        cooldownUntil = null
        manualFailures = 0
        mutableState.value = BankAlertSettingsState(AlertLoadPhase.Idle, null, null)
    }

    override fun onForeground(authoritative: Boolean) {
        foreground = true
        if (authoritative) this.authoritative() else ensure()
    }

    override fun onBackground() {
        foreground = false
        abandon()
        show(AlertLoadPhase.Idle)
    }

    override fun onSync() {
        if (foreground) authoritative()
    }

    private fun begin(automatic: Boolean, manual: Boolean) {
        val chain = Chain(generation, automatic, manual)
        active = chain
        // Publish the handle before running, even with an eager injected dispatcher.
        val next = scope.launch(start = CoroutineStart.LAZY) { runChain(chain) }
        job = next
        next.start()
    }

    private fun abandon() {
        generation++
        active = null
        val previous = job
        job = null
        previous?.cancel()
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
                chain.inFetch = true
                show(if (attempt == 0) AlertLoadPhase.Loading else AlertLoadPhase.Retrying)
                val result = try {
                    fetch(chain, use)
                } finally {
                    chain.inFetch = false
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
            }
        }
    }

    /** Every wake and every send rechecks the process floor, independently of account and retry delay. */
    private suspend fun awaitUse(chain: Chain): AlertSettingsUse? {
        while (current(chain)) {
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
        if (!chain.manual) return
        chain.manual = false
        val cooldown = if (succeeded) {
            manualFailures = 0
            3_000L
        } else {
            manualFailures = (manualFailures + 1).coerceAtMost(3)
            5_000L * (1L shl (manualFailures - 1))
        }
        cooldownUntil = addDelay(nowMillis(), cooldown)
        mutableManualOutcomes.tryEmit(if (succeeded) ManualRefreshOutcome.Succeeded else ManualRefreshOutcome.Failed)
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
