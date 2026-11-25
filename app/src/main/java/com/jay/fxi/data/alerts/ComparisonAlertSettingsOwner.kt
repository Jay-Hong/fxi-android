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
import com.jay.fxi.data.remote.dto.ComparisonAlertRequest
import com.jay.fxi.data.remote.dto.ComparisonAlertUpdateRequest
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.domain.model.RepeatInterval
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

internal data class ComparisonAlertPair(
    val tab: String,
    val diffType: String,
    val left: Pair<String, String>,
    val right: Pair<String, String>
) {
    fun supported() = ComparisonAlertRowPolicy.admits(tab, diffType, left, right)
    fun matches(other: ComparisonAlertPair) = tab == other.tab && diffType == other.diffType &&
        ((left == other.left && right == other.right) ||
            (diffType == "absolute" && left == other.right && right == other.left))
}

private fun ComparisonAlertSetting.pair() =
    ComparisonAlertPair(tab, diffType, leftSource to leftAsset, rightSource to rightAsset)

internal data class ComparisonAlertDraft(
    val pair: ComparisonAlertPair,
    val operator: String,
    val threshold: Double,
    val repeat: RepeatInterval?
) {
    fun supported() = pair.supported() && operator in setOf("gte", "lte") && threshold.isFinite() &&
        (pair.diffType == "signed" || threshold >= 0)

    fun createRequest() = ComparisonAlertRequest(pair.tab, pair.left.first, pair.left.second,
        pair.right.first, pair.right.second, pair.diffType, operator, threshold, true, repeat?.seconds)

    fun updateRequest() = ComparisonAlertUpdateRequest(isEnabled = true, operator = operator, threshold = threshold,
        repeatIntervalSec = (repeat?.let { RepeatIntervalChange.Every(it) } ?: RepeatIntervalChange.Once).toJson())
}

internal enum class ComparisonAlertNotSentReason { NOT_READY, UNSUPPORTED, CAP, BUSY, UNADMITTED, BACKGROUND }

internal sealed interface ComparisonAlertOutcome {
    data object Pending : ComparisonAlertOutcome
    data class Saved(val row: ComparisonAlertSetting) : ComparisonAlertOutcome
    data class Deleted(val id: Int) : ComparisonAlertOutcome
    data class OldNotDeleted(val oldId: Int, val newRow: ComparisonAlertSetting, val failure: Throwable) : ComparisonAlertOutcome
    data class Failed(val failure: Throwable) : ComparisonAlertOutcome
    data class NotSent(val reason: ComparisonAlertNotSentReason) : ComparisonAlertOutcome
    data object Withdrawn : ComparisonAlertOutcome
}

internal class ComparisonAlertHandle {
    internal var completions = 0
        private set
    private val result = MutableStateFlow<ComparisonAlertOutcome>(ComparisonAlertOutcome.Pending)
    val outcome = result.asStateFlow()
    internal fun finish(value: ComparisonAlertOutcome) {
        if (result.value != ComparisonAlertOutcome.Pending) return
        completions++
        result.compareAndSet(ComparisonAlertOutcome.Pending, value)
    }
}

@Singleton
internal class ComparisonAlertSettingsOwner internal constructor(
    private val getSettings: suspend (AlertSettingsUse, () -> Boolean) -> AuthBoundResult<List<ComparisonAlertSetting>>,
    private val access: AlertSettingsAccess,
    private val eligibleIdentity: () -> AuthIdentityFence?,
    private val premium: StateFlow<OwnedPremiumAccess>,
    accessRevisions: StateFlow<Long>,
    private val scope: CoroutineScope,
    private val sleep: suspend (Long) -> Unit,
    private val jitter: () -> Double,
    private val post: suspend (AlertSettingsUse, ComparisonAlertRequest, () -> Boolean) -> AuthBoundResult<ComparisonAlertSetting>,
    private val put: suspend (AlertSettingsUse, Int, ComparisonAlertUpdateRequest, () -> Boolean) -> AuthBoundResult<ComparisonAlertSetting>,
    private val remove: suspend (AlertSettingsUse, Int, () -> Boolean) -> AuthBoundResult<Unit>
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
        CoroutineScope(SupervisorJob() + Dispatchers.Main), { delay(it) }, { Random.nextDouble(-0.2, 0.2) },
        comparisonCreate(repository), comparisonUpdate(repository), comparisonDelete(repository)
    )

    private val mutableState = MutableStateFlow(ComparisonAlertSettingsState())
    val state = mutableState.asStateFlow()
    internal var publications = 0L
        private set
    private fun publishState(next: ComparisonAlertSettingsState) {
        publications++
        mutableState.value = next
    }

    private val policy = AlertRetryPolicy()
    private var foreground = false
    private var session = permittedIdentity()
    internal var generation = 0L
        private set
    private var active: Chain? = null

    private sealed class Change(val id: Int? = null) {
        class Add(val draft: ComparisonAlertDraft) : Change()
        class Edit(id: Int, val origin: ComparisonAlertPair, val draft: ComparisonAlertDraft) : Change(id)
        class Toggle(id: Int, val enabled: Boolean) : Change(id)
        class Delete(id: Int) : Change(id)
    }

    private class Write(val change: Change, val use: AlertSettingsUse, val origin: ComparisonAlertPair?) {
        val handle = ComparisonAlertHandle()
        @Volatile var retired = false
        var newId: Int? = null
    }

    private var confirmed: ComparisonAlertRowPolicy.Adopted? = null
    private val writes = ArrayDeque<Write>()
    private var worker: Job? = null
    private var readOwed = false
    internal var writeRevision = 0L
        private set

    fun add(identity: AuthIdentityFence, draft: ComparisonAlertDraft) = submit(identity, Change.Add(draft))
    fun edit(identity: AuthIdentityFence, oldId: Int, originPair: ComparisonAlertPair, draft: ComparisonAlertDraft) =
        submit(identity, Change.Edit(oldId, originPair, draft))
    fun setEnabled(identity: AuthIdentityFence, id: Int, enabled: Boolean) = submit(identity, Change.Toggle(id, enabled))
    fun delete(identity: AuthIdentityFence, id: Int) = submit(identity, Change.Delete(id))

    private fun submit(identity: AuthIdentityFence, change: Change): ComparisonAlertHandle {
        fun reject(reason: ComparisonAlertNotSentReason) =
            ComparisonAlertHandle().also { it.finish(ComparisonAlertOutcome.NotSent(reason)) }
        updateSession()
        if (identity != session) return reject(ComparisonAlertNotSentReason.UNADMITTED)
        if (!foreground) return reject(ComparisonAlertNotSentReason.BACKGROUND)
        if (writes.any { !access.admits(it.use) }) withdrawUse()
        val rows = confirmed?.rows ?: return reject(ComparisonAlertNotSentReason.NOT_READY)
        val id = change.id
        if (id != null && writes.any { it.change.id == id || it.newId == id }) return reject(ComparisonAlertNotSentReason.BUSY)
        val origin = rows.firstOrNull { it.id == id }?.pair()
        if (id != null && origin == null) return reject(ComparisonAlertNotSentReason.NOT_READY)
        when (change) {
            is Change.Add -> {
                if (!change.draft.supported()) return reject(ComparisonAlertNotSentReason.UNSUPPORTED)
                if (rows.size + writes.count { it.change is Change.Add } >= 30) return reject(ComparisonAlertNotSentReason.CAP)
            }
            is Change.Edit -> {
                if (!change.origin.supported() || !change.draft.supported() ||
                    change.origin.tab != change.draft.pair.tab || change.origin.diffType != change.draft.pair.diffType)
                    return reject(ComparisonAlertNotSentReason.UNSUPPORTED)
                if (origin?.matches(change.origin) != true) return reject(ComparisonAlertNotSentReason.NOT_READY)
            }
            else -> Unit
        }
        val use = access.acquire()?.takeIf { it.identity == identity && access.admits(it) }
            ?: return reject(ComparisonAlertNotSentReason.UNADMITTED)
        val write = Write(change, use, origin)
        writeRevision++
        if (active != null) {
            readOwed = true
            retireChain()
        }
        writes.addLast(write)
        if (change is Change.Toggle || change is Change.Delete) publish(identity)
        if (worker == null) {
            worker = scope.launch(start = CoroutineStart.LAZY) { drainWrites() }
            worker?.start()
        }
        return write.handle
    }

    private fun publish(identity: AuthIdentityFence) {
        val rows = confirmed ?: return
        val adopted = if (writes.none { it.change is Change.Toggle || it.change is Change.Delete }) rows
        else ComparisonAlertRowPolicy.Adopted(rows.rows.mapNotNull { row ->
            when (val pending = writes.firstOrNull { it.change.id == row.id }?.change) {
                is Change.Delete -> null
                is Change.Toggle -> row.copy(isEnabled = pending.enabled)
                else -> row
            }
        })
        publishState(ComparisonAlertSettingsState(AlertLoadPhase.Ready, identity, adopted))
    }

    private fun commitRows(identity: AuthIdentityFence, adopted: ComparisonAlertRowPolicy.Adopted) {
        confirmed = adopted
        publish(identity)
    }

    private fun applicable(write: Write): Boolean {
        if (write.retired || writes.firstOrNull() !== write) return false
        updateSession()
        if (write.retired) return false
        if (!access.admits(write.use)) withdrawUse()
        return !write.retired
    }

    private suspend fun drainWrites() {
        while (writes.isNotEmpty()) {
            val write = writes.first()
            if (!applicable(write)) return
            val outcome = try {
                execute(write)
            } catch (cancelled: CancellationException) {
                if (applicable(write)) withdrawUse()
                throw cancelled
            } catch (failure: Exception) {
                ComparisonAlertOutcome.Failed(failure)
            }
            if (!applicable(write)) return
            val currentWorker = worker
            writes.removeFirst()
            write.retired = true
            publish(write.use.identity)
            write.handle.finish(outcome)
            if (worker !== currentWorker) return
        }
        worker = null
        if (readOwed) {
            readOwed = false
            authoritative()
        }
    }

    private suspend fun execute(write: Write): ComparisonAlertOutcome {
        val use = write.use
        val guard = { !write.retired && access.admits(use) }
        val change = write.change
        val id = change.id
        fun targetValid() = confirmed?.rows?.any { it.id == id && write.origin?.matches(it.pair()) == true } == true
        if (id != null && !targetValid()) return ComparisonAlertOutcome.NotSent(ComparisonAlertNotSentReason.NOT_READY)
        val draft = when (change) {
            is Change.Add -> change.draft
            is Change.Edit -> change.draft
            else -> null
        }
        val create = change is Change.Add || (change is Change.Edit && !change.origin.matches(change.draft.pair))
        val newRow = if (change is Change.Delete) null else {
            val bound = if (create) post(use, requireNotNull(draft).createRequest(), guard) else {
                val request = if (change is Change.Toggle) ComparisonAlertUpdateRequest(isEnabled = change.enabled)
                    else requireNotNull(draft).updateRequest()
                put(use, requireNotNull(id), request, guard)
            }
            if (!applicable(write)) return ComparisonAlertOutcome.Withdrawn
            val row = bound.fold({ it }, { throw it })
            if (!applicable(write)) return ComparisonAlertOutcome.Withdrawn
            val pair = draft?.pair ?: requireNotNull(write.origin)
            val rows = requireNotNull(confirmed).rows
            val existing = rows.firstOrNull { it.id == row.id }
            if (row.userId != use.identity.uid || !row.pair().supported() || !pair.matches(row.pair()) ||
                (!create && row.id != id) || (create && row.id == id) ||
                (create && (row.operator != draft?.operator || row.threshold != draft?.threshold)) ||
                (existing != null && !pair.matches(existing.pair()))) {
                return ComparisonAlertOutcome.Failed(AlertRepositoryException("Invalid comparison alert response",
                    failureKind = AlertRepositoryFailureKind.DECODE))
            }
            val next = if (existing == null) listOf(row) + rows else rows.map { if (it.id == row.id) row else it }
            write.newId = row.id
            commitRows(use.identity, ComparisonAlertRowPolicy.Adopted(next))
            if (!(create && change is Change.Edit)) return ComparisonAlertOutcome.Saved(row)
            row
        }
        if (!applicable(write) || !targetValid()) return ComparisonAlertOutcome.Withdrawn
        val oldId = requireNotNull(id)
        val failure = try {
            val bound = remove(use, oldId, guard)
            if (!applicable(write)) return ComparisonAlertOutcome.Withdrawn
            bound.fold({ null }, { it })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error
        }
        if (!applicable(write)) return ComparisonAlertOutcome.Withdrawn
        if (failure != null && (failure as? AlertRepositoryException)?.httpFailure?.statusCode != 404) {
            return if (newRow == null) ComparisonAlertOutcome.Failed(failure)
                else ComparisonAlertOutcome.OldNotDeleted(oldId, newRow, failure)
        }
        confirmed = ComparisonAlertRowPolicy.Adopted(requireNotNull(confirmed).rows.filterNot { it.id == oldId })
        return if (newRow == null) ComparisonAlertOutcome.Deleted(oldId) else ComparisonAlertOutcome.Saved(newRow)
    }

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
            writes.any { !access.admits(it.use) } -> withdrawUse()
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
        abandon(restoreSnapshot = false)
        confirmed = null
        publishState(ComparisonAlertSettingsState())
    }

    // A use can end while the same account still has premium access to its last success.
    private fun withdrawUse() {
        abandon()
        show(AlertLoadPhase.Idle)
    }

    private fun abandon(restoreSnapshot: Boolean = true) {
        generation++
        retireChain()
        readOwed = false
        val retired = writes.toList()
        retired.forEach { it.retired = true }
        writes.clear()
        val previousWorker = worker
        worker = null
        previousWorker?.cancel()
        if (retired.isNotEmpty()) {
            if (restoreSnapshot) session?.takeIf { it == state.value.identity }?.let(::publish)
            retired.forEach { it.handle.finish(ComparisonAlertOutcome.Withdrawn) }
        }
    }

    private fun retireChain() {
        val previous = active ?: return
        previous.retired = true
        active = null
        previous.job.cancel()
    }

    private fun demand(force: Boolean) {
        updateSession()
        val identity = session ?: return
        if (!foreground) return
        if (writes.isNotEmpty()) {
            if (writes.any { !access.admits(it.use) }) withdrawUse() else {
                if (force) readOwed = true
                return
            }
        }
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
                val revision = writeRevision
                val bound = fetch(use, guard)
                chain.fetching = false
                if (revision != writeRevision || !current(chain) || !guard()) return
                val result = bound.fold({ Result.success(it) }, { Result.failure(it) })
                val failure = result.exceptionOrNull()
                if (failure == null) {
                    val rows = result.getOrThrow()
                    val adopted = ComparisonAlertRowPolicy.adopt(use.identity.uid, rows)
                    commitRows(use.identity, adopted)
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
        if (state.value.adopted == null) publishState(state.value.copy(phase = phase))
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

internal fun comparisonCreate(repository: AlertRepository):
    suspend (AlertSettingsUse, ComparisonAlertRequest, () -> Boolean) -> AuthBoundResult<ComparisonAlertSetting> =
    { use, request, guard -> repository.createComparisonAlert(use.identity, request, useAdmitted = guard) }

internal fun comparisonUpdate(repository: AlertRepository):
    suspend (AlertSettingsUse, Int, ComparisonAlertUpdateRequest, () -> Boolean) -> AuthBoundResult<ComparisonAlertSetting> =
    { use, id, request, guard -> repository.updateComparisonAlert(use.identity, id, request, useAdmitted = guard) }

internal fun comparisonDelete(repository: AlertRepository):
    suspend (AlertSettingsUse, Int, () -> Boolean) -> AuthBoundResult<Unit> =
    { use, id, guard -> repository.deleteComparisonAlert(use.identity, id, useAdmitted = guard) }
