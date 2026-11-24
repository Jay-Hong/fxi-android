package com.jay.fxi.data.alerts

import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.confirmsPremiumFor
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.domain.model.ComparisonAlertSetting
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

internal data class ComparisonAlertSettingsState(
    val phase: AlertLoadPhase = AlertLoadPhase.Idle,
    val identity: AuthIdentityFence? = null,
    val adopted: ComparisonAlertRowPolicy.Adopted? = null
)

@Singleton
internal class ComparisonAlertSettingsOwner internal constructor(
    private val getSettings: suspend (AlertSettingsUse, () -> Boolean) -> AuthBoundResult<List<ComparisonAlertSetting>>,
    private val access: AlertSettingsAccess,
    private val eligibleIdentity: () -> AuthIdentityFence?,
    private val premium: StateFlow<OwnedPremiumAccess>,
    accessRevisions: StateFlow<Long>,
    private val scope: CoroutineScope,
    private val sleep: suspend (Long) -> Unit,
    private val jitter: () -> Double
) : AlertLifecycleAdapter {
    @Inject constructor(
        repository: AlertRepository,
        access: AlertSettingsAccess,
        tokens: AuthTokenProvider,
        deletions: DeletionAdmissionStore,
        coordinator: PremiumAccessCoordinator
    ) : this(
        comparisonSettingsGet(repository), access,
        GraphProtectedAdmission(tokens::currentIdentityFence, deletions).let { protectedAdmission ->
            {
                tokens.currentIdentityFence()?.takeIf {
                    ReleaseAdmission.isOpen && protectedAdmission() && tokens.currentIdentityFence() == it
                }
            }
        },
        coordinator.state, coordinator.accessRevisions,
        CoroutineScope(SupervisorJob() + Dispatchers.Main), { delay(it) }, { Random.nextDouble(-0.2, 0.2) }
    )

    private val mutableState = MutableStateFlow(ComparisonAlertSettingsState())
    val state = mutableState.asStateFlow()
    private val policy = AlertRetryPolicy()
    private var foreground = false
    private var session = permittedIdentity()
    internal var generation = 0L
        private set
    private var active: Chain? = null

    private class Chain(val generation: Long, val identity: AuthIdentityFence, var use: AlertSettingsUse?) {
        @Volatile var retired = false
        var fetching = false
        var dirty = false
        val hints = Channel<Unit>(Channel.CONFLATED)
        lateinit var job: Job
    }

    init {
        scope.launch { premium.collect { accessChanged() } }
        scope.launch { accessRevisions.collect { accessChanged() } }
    }

    private fun permittedIdentity() = eligibleIdentity()?.takeIf { premium.value.confirmsPremiumFor(it) }

    // A missing graph fence may be temporary, including during a background transition.
    // Account admission is independent of that fence and must also be checked in background.
    private fun updateSession(): Boolean {
        val next = permittedIdentity()
        if (next == session) return false
        session = next
        reset()
        return true
    }

    private fun accessChanged() {
        val changed = updateSession()
        val chain = active
        val use = chain?.use
        when {
            changed && foreground && session != null -> ensure()
            use != null && !access.admits(use) -> withdrawUse()
            chain != null && use == null -> chain.hints.trySend(Unit)
        }
    }

    override fun onForeground(authoritative: Boolean) {
        foreground = true
        if (authoritative) this.authoritative() else ensure()
    }

    override fun onBackground() {
        foreground = false
        abandon()
    }

    override fun onSync() = authoritative()
    fun ensure() = demand(force = false)
    fun authoritative() = demand(force = true)
    fun manual() = authoritative()

    fun reset() {
        abandon()
        mutableState.value = ComparisonAlertSettingsState()
    }

    // A use can end while the same account still has premium access to its last success.
    private fun withdrawUse() {
        abandon()
        show(AlertLoadPhase.Idle)
    }

    private fun abandon() {
        generation++
        val previous = active ?: return
        previous.retired = true
        active = null
        previous.job.cancel()
    }

    private fun demand(force: Boolean) {
        updateSession()
        val identity = session ?: return
        if (!foreground) return
        val chain = active
        if (chain != null) {
            if (chain.use?.let { !access.admits(it) } == true) {
                withdrawUse()
            } else {
                if (force && chain.fetching) chain.dirty = true
                return
            }
        }
        if (!force && hasSnapshot(identity)) return
        val use = access.acquire()?.takeIf { it.identity == identity }
        val next = Chain(generation, identity, use)
        active = next
        next.job = scope.launch(start = CoroutineStart.LAZY) { runChain(next) }
        next.job.start()
    }

    private fun hasSnapshot(identity: AuthIdentityFence) =
        state.value.identity == identity && state.value.adopted != null

    private fun current(chain: Chain) =
        active === chain && chain.generation == generation && foreground && chain.job.isActive

    private suspend fun awaitUse(chain: Chain): AlertSettingsUse? {
        while (current(chain) && permittedIdentity() == chain.identity) {
            val use = chain.use ?: access.acquire()
            if (use != null && use.identity == chain.identity && access.admits(use)) {
                chain.use = use
                return use
            }
            if (chain.use != null) return null
            coroutineScope {
                val timer = async(start = CoroutineStart.UNDISPATCHED) { sleep(1_000) }
                try {
                    select<Unit> {
                        chain.hints.onReceive { }
                        timer.onAwait { }
                    }
                } finally {
                    timer.cancel()
                }
            }
        }
        return null
    }

    private suspend fun runChain(chain: Chain) {
        try {
            val use = awaitUse(chain) ?: return
            val guard = { !chain.retired && access.admits(use) }
            var attempt = 0
            while (current(chain) && guard()) {
                chain.dirty = false
                chain.fetching = true
                if (attempt == 0) show(AlertLoadPhase.Loading)
                val bound = fetch(use, guard)
                chain.fetching = false
                if (!current(chain) || !guard()) return
                val result = bound.fold({ Result.success(it) }, { Result.failure(it) })
                val failure = result.exceptionOrNull()
                if (failure == null) {
                    val rows = result.getOrThrow()
                    val adopted = ComparisonAlertRowPolicy.adopt(use.identity.uid, rows)
                    mutableState.value = ComparisonAlertSettingsState(AlertLoadPhase.Ready, use.identity, adopted)
                    if (!chain.dirty) return
                    attempt = 0
                } else {
                    when (val decision = policy.decide(classify(failure), attempt, jitter)) {
                        AlertRetryDecision.Silent -> return
                        is AlertRetryDecision.Terminal -> {
                            show(AlertLoadPhase.TerminalError(decision.failure))
                            return
                        }
                        is AlertRetryDecision.Retry -> {
                            show(AlertLoadPhase.Retrying)
                            attempt++
                            sleep(decision.delayMillis)
                        }
                    }
                }
            }
        } catch (_: AuthIdentityChangedException) {
            if (current(chain)) finishWithdrawn(chain)
        } catch (_: TopicUseWithheldException) {
            if (active === chain) show(AlertLoadPhase.Idle)
        } catch (cancelled: CancellationException) {
            if (active === chain) show(AlertLoadPhase.Idle)
            throw cancelled
        } finally {
            chain.retired = true
            if (active === chain) {
                active = null
                if (permittedIdentity() != chain.identity) {
                    reset()
                } else if (chain.use?.let { !access.admits(it) } == true) {
                    withdrawUse()
                }
            }
        }
    }

    // This job already reached its exit. Withdrawal completes silently.
    private fun finishWithdrawn(chain: Chain) {
        chain.retired = true
        active = null
        reset()
    }

    private suspend fun fetch(use: AlertSettingsUse, guard: () -> Boolean): AuthBoundResult<List<ComparisonAlertSetting>> {
        return try {
            getSettings(use, guard)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AuthBoundResult({}, Result.failure(e))
        }
    }

    private fun show(phase: AlertLoadPhase) {
        if (state.value.adopted == null) mutableState.value = state.value.copy(phase = phase)
    }

    private fun classify(failure: Throwable): AlertFetchFailure = when (failure) {
        is AlertRepositoryException -> failure.httpFailure?.let {
            AlertFetchFailure.Http(it.statusCode, AlertRetryAfter.parseSeconds(it.retryAfter))
        } ?: AlertFetchFailure.Repository(failure.failureKind)
        is AuthUnavailableException -> AlertFetchFailure.Repository(AlertRepositoryFailureKind.AUTH_UNAVAILABLE)
        is IOException -> AlertFetchFailure.Repository(AlertRepositoryFailureKind.NETWORK)
        else -> AlertFetchFailure.Repository(AlertRepositoryFailureKind.UNEXPECTED)
    }
}

internal fun comparisonSettingsGet(repository: AlertRepository):
    suspend (AlertSettingsUse, () -> Boolean) -> AuthBoundResult<List<ComparisonAlertSetting>> =
    { use, guard -> repository.getComparisonAlerts(use.identity, tab = null, useAdmitted = guard) }
