package com.jay.fxi.data.alerts

import android.os.SystemClock
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.remote.AuthenticatedEndpoint
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.domain.model.SourceAlertSetting
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** [settings] null is no success yet; an empty list is a successful empty answer. */
internal data class SourceAlertSettingsState(
    val phase: AlertLoadPhase,
    val settings: List<SourceAlertSetting>?,
    val identity: AuthIdentityFence?
)

/**
 * S8-3: the source alert settings, read for the exchange family. It follows the bank owner's GET rules (one chain, joining,
 * absorption, one trailing fetch, 30 s freshness, the retry policy, the process floor, the manual cooldowns, background
 * abandonment) and adopts only the rows [SourceAlertRowPolicy.EXCHANGES] allows. It writes nothing; the editor and its
 * mutations are S8-5's. Main owns the chain and the snapshot; only a captured use's admission supplier crosses threads.
 */
@Singleton
internal class SourceAlertSettingsOwner internal constructor(
    private val getSettings: suspend (AuthIdentityFence, () -> Boolean) -> AuthBoundResult<List<SourceAlertSetting>>,
    private val access: AlertSettingsAccess,
    private val floor: AlertRateLimitFloor,
    private val policy: AlertRetryPolicy,
    private val nowMillis: () -> Long,
    private val jitter: () -> Double,
    private val scope: CoroutineScope,
    private val sleep: suspend (Long) -> Unit,
    premium: StateFlow<OwnedPremiumAccess>,
    accessRevisions: StateFlow<Long>,
    private val accessRecheckMillis: Long = 1_000
) : AlertLifecycleAdapter {
    @Inject constructor(
        repository: AlertRepository,
        access: AlertSettingsAccess,
        floor: AlertRateLimitFloor,
        coordinator: PremiumAccessCoordinator
    ) : this(
        { owner, admitted -> repository.getSourceSettings(owner, SourceAlertRowPolicy.SETTINGS_ASSET, admitted) },
        access,
        floor,
        AlertRetryPolicy(),
        SystemClock::elapsedRealtime,
        { Random.nextDouble(-0.2, 0.2) },
        CoroutineScope(SupervisorJob() + Dispatchers.Main),
        { delay(it) },
        coordinator.state,
        coordinator.accessRevisions
    )

    private val mutableState = MutableStateFlow(SourceAlertSettingsState(AlertLoadPhase.Idle, null, null))
    val state: StateFlow<SourceAlertSettingsState> = mutableState.asStateFlow()
    private val mutableManualOutcomes = MutableSharedFlow<ManualRefreshOutcome>(replay = 0, extraBufferCapacity = 1)
    val manualOutcomes: SharedFlow<ManualRefreshOutcome> = mutableManualOutcomes.asSharedFlow()
    private val mutableManualRefresh = MutableStateFlow(ManualRefreshState())
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

    /**
     * A retired chain can finish late, but cannot change any of its replacement's bookkeeping. [retired] is set before the
     * chain is cancelled; its send guard reads it from transport threads, so a guard kept past a background or reset refuses
     * even while the same identity is still admitted.
     */
    private class Chain(val generation: Long, var automatic: Boolean, var manual: Boolean) {
        @Volatile var retired = false
        var inFetch = false
        var dirty = false
        var waitingAccess = false
        var retryNotBefore: Long? = null
        val hints = Channel<Unit>(Channel.CONFLATED)
    }

    private sealed interface FetchResult {
        data class Loaded(val settings: List<SourceAlertSetting>) : FetchResult
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
        cancelCooldownTimer()
        cooldownUntil = null
        abandon()
        lastSuccessAt = null
        manualFailures = 0
        mutableState.value = SourceAlertSettingsState(AlertLoadPhase.Idle, null, null)
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
        publishManualRefresh()
        next.start()
    }

    private fun abandon() {
        active?.retired = true
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
                        lastSuccessAt = nowMillis()
                        mutableState.value = SourceAlertSettingsState(
                            AlertLoadPhase.Ready,
                            SourceAlertRowPolicy.EXCHANGES.adopt(result.settings, use.identity.uid),
                            use.identity
                        )
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
            val wait = maxOf(
                floor.remainingMillis(AuthenticatedEndpoint.SOURCE_NOTIFICATION_SETTINGS),
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
            if (floor.remainingMillis(AuthenticatedEndpoint.SOURCE_NOTIFICATION_SETTINGS) > 0) continue
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
            getSettings(use.identity) { !chain.retired && access.admits(use) }
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
        floor.record(AuthenticatedEndpoint.SOURCE_NOTIFICATION_SETTINGS, evidence)
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
