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
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.data.remote.dto.SourceAlertSettingRequest
import com.jay.fxi.data.remote.dto.SourceAlertSettingUpdateRequest
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.RepeatInterval
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

/** S8-5a: a new exchange alert; its asset is always [SourceAlertRowPolicy.SETTINGS_ASSET]. */
internal data class SourceAlertDraft(
    val source: String,
    val condition: AlertCondition,
    val threshold: Double,
    val isEnabled: Boolean,
    val repeat: RepeatInterval?
)

/** S8-5a: a partial change of one exchange alert; null leaves a field as it is, and the asset cannot change. */
internal data class SourceAlertEdit(
    val source: String? = null,
    val condition: AlertCondition? = null,
    val threshold: Double? = null,
    val isEnabled: Boolean? = null,
    val repeat: RepeatIntervalChange = RepeatIntervalChange.Keep
)

internal sealed interface SourceAlertSaveOutcome {
    data object Pending : SourceAlertSaveOutcome
    data class Saved(val row: SourceAlertSetting) : SourceAlertSaveOutcome
    data object Removed : SourceAlertSaveOutcome
    data class NotSent(val reason: NotSentReason) : SourceAlertSaveOutcome
    /** The target (source, asset) pair is not one the exchange family allows: nothing was reserved or sent. */
    data object Unsupported : SourceAlertSaveOutcome
    /** Sent, but not confirmed: an HTTP rejection keeps its typed failure; an unknown result is settled by the next GET. */
    data class Failed(val failure: Throwable) : SourceAlertSaveOutcome
}

internal class SourceAlertSaveRequest internal constructor() {
    private val mutableOutcome = MutableStateFlow<SourceAlertSaveOutcome>(SourceAlertSaveOutcome.Pending)
    val outcome: StateFlow<SourceAlertSaveOutcome> = mutableOutcome.asStateFlow()

    internal fun finish(value: SourceAlertSaveOutcome) {
        if (mutableOutcome.value == SourceAlertSaveOutcome.Pending) mutableOutcome.value = value
    }
}

/**
 * S8-3: the source alert settings, read for the exchange family. It follows the bank owner's GET rules (one chain, joining,
 * absorption, one trailing fetch, 30 s freshness, the retry policy, the process floor, the manual cooldowns, background
 * abandonment) and adopts only the rows [SourceAlertRowPolicy.EXCHANGES] allows. S8-5a: it also owns the family's four
 * writes, with the bank owner's ordering, reservations and GET settlement (s8_5a_contract.agreed.r1 §1·§2), stricter in
 * four places: a target pair must be allowed (else nothing is sent), each send's guard reads only that request's original
 * use and withdrawal marks, an overlapped create reports no success, and a definite HTTP rejection of a create frees its
 * reservation at once. Main owns the chain, the snapshot and the writes; only immutable send guards cross threads.
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
    private val createRemote: suspend (AuthIdentityFence, SourceAlertSettingRequest, () -> Boolean) -> AuthBoundResult<SourceAlertSetting>,
    private val editRemote: suspend (AuthIdentityFence, Int, SourceAlertSettingUpdateRequest, () -> Boolean) -> AuthBoundResult<SourceAlertSetting>,
    private val deleteRemote: suspend (AuthIdentityFence, Int, () -> Boolean) -> AuthBoundResult<Unit>,
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
        coordinator.accessRevisions,
        productionSourceCreate(repository),
        productionSourceEdit(repository),
        productionSourceDelete(repository)
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

    // Mutation ownership survives GET-chain abandonment, but never a reset (the bank owner's bookkeeping).
    private var epoch = 0L
    private var revision = 0L
    private val rowOps = mutableMapOf<Int, RowOp>()
    private var reconcileOwed: AuthIdentityFence? = null
    private var pendingDelivery: AlertSettingsUse? = null
    private var deliveryMarks = 0L
    private val createOps = mutableMapOf<Any, CreateOp>()
    // Settled, unresolved effects reserve capacity without holding up the reconciling GET.
    private val createReservations = mutableSetOf<Any>()
    private val deleteReservations = mutableSetOf<Int>()
    private var acceptanceSequence = 0L
    /** One foreground's permission to send: a background closes it for good, and the next foreground opens a new one. */
    private class SendLifetime {
        @Volatile var open = true
    }
    /** Captured by every accepted intent and read by its send guard on transport threads; closed until the first foreground. */
    private var lifetime = SendLifetime().also { it.open = false }
    /** Ids in the order the canonical list, an adopted create or a confirmed removal last left them, hidden rows included. */
    private val approvedOrder = mutableListOf<Int>()
    /**
     * Ids whose pair an unconfirmed source change may have moved: until the canonical list, no new row work is accepted for
     * them and no create answered with one of them is adopted.
     */
    private val uncertainPairs = mutableSetOf<Int>()

    private class CreateOp(
        val epoch: Long,
        val identity: AuthIdentityFence,
        val token: Any,
        val draft: SourceAlertDraft,
        val requestUse: AlertSettingsUse,
        val handle: SourceAlertSaveRequest,
        val overlaps: MutableSet<Int>,
        val lifetime: SendLifetime
    ) {
        /** Set on Main before a reset drops this op; its send guard reads it from transport threads. */
        @Volatile var retired = false
        var entered = false
        var job: Job? = null
        val hints = Channel<Unit>(Channel.CONFLATED)
    }

    /** [pair] is the row's pair at acceptance: this patch is never re-bound to a pair an earlier edit gave the row. */
    private class PendingEdit(
        val epoch: Long,
        val identity: AuthIdentityFence,
        val token: Any,
        val patch: SourceAlertEdit,
        val requestUse: AlertSettingsUse,
        val sequence: Long,
        val handle: SourceAlertSaveRequest,
        val pair: Pair<String, String>,
        val lifetime: SendLifetime
    )

    private class ToggleIntent(
        val enabled: Boolean,
        val sequence: Long,
        val requestUse: AlertSettingsUse,
        val pair: Pair<String, String>,
        val lifetime: SendLifetime
    )

    private class DeleteIntent(
        val requestUse: AlertSettingsUse,
        val pair: Pair<String, String>,
        val lifetime: SendLifetime,
        val ownsReservation: Boolean
    )

    private class RowOp(
        val id: Int,
        val epoch: Long,
        val identity: AuthIdentityFence,
        val token: Any,
        var approved: SourceAlertSetting,
        var position: Int
    ) {
        @Volatile var retired = false
        var delete: DeleteIntent? = null
        var toggle: ToggleIntent? = null
        var edit: PendingEdit? = null
        var sendingEdit: PendingEdit? = null
        var consumedEnabled = 0L
        var shown: SourceAlertSetting? = approved.copy()
        var job: Job? = null
        var inFlight = false
        val hints = Channel<Unit>(Channel.CONFLATED)
    }

    private sealed interface MutationResult {
        data class Loaded(val row: SourceAlertSetting) : MutationResult
        data object Deleted : MutationResult
        data class Failed(val failure: Throwable, val preWire: Boolean = false) : MutationResult
        data object Dropped : MutationResult
        data object Unadmitted : MutationResult
    }

    /**
     * A retired chain can finish late, but cannot change any of its replacement's bookkeeping. [retired] is set before the
     * chain is cancelled; its send guard reads it from transport threads, so a guard kept past a background or reset refuses
     * even while the same identity is still admitted.
     */
    private class Chain(val generation: Long, var automatic: Boolean, var manual: Boolean, var deliveryUse: AlertSettingsUse?) {
        var servingDelivery: AlertSettingsUse? = null
        @Volatile var retired = false
        var startRevision = 0L
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

    private fun normalChain(): Chain? {
        val chain = active ?: return null
        val use = chain.deliveryUse ?: return chain
        if (!access.admits(use)) {
            abandon()
            return null
        }
        chain.deliveryUse = null
        return chain
    }

    fun ensure() {
        if (!foreground) return
        val success = lastSuccessAt
        val fresh = reconcileOwed == null && mutableState.value.settings != null && success != null &&
            remaining(addDelay(success, 30_000)) > 0
        if (fresh && active?.deliveryUse != null) return
        normalChain()?.let {
            it.automatic = true
            return
        }
        if (fresh) return
        begin(automatic = true, manual = false)
    }

    fun authoritative() {
        if (!foreground) return
        val chain = normalChain()
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
        val chain = normalChain()
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
        val previousCreates = createOps.values.toList()
        previousOps.forEach { it.retired = true }
        previousCreates.forEach { it.retired = true }
        rowOps.clear()
        createOps.clear()
        reconcileOwed = null
        pendingDelivery = null
        createReservations.clear()
        deleteReservations.clear()
        approvedOrder.clear()
        uncertainPairs.clear()
        previousOps.forEach {
            discardEdit(it, NotSentReason.RESET)
            it.sendingEdit?.handle?.finish(unknownSave())
        }
        previousCreates.forEach {
            it.handle.finish(if (it.entered) unknownSave() else notSent(NotSentReason.RESET))
        }
        previousOps.forEach { it.job?.cancel() }
        previousCreates.forEach { it.job?.cancel() }
        cancelCooldownTimer()
        cooldownUntil = null
        abandon()
        lastSuccessAt = null
        manualFailures = 0
        mutableState.value = SourceAlertSettingsState(AlertLoadPhase.Idle, null, null)
    }

    override fun onForeground(authoritative: Boolean) {
        foreground = true
        if (!lifetime.open) lifetime = SendLifetime()
        if (authoritative || reconcileOwed != null) this.authoritative() else ensure()
        startPendingDelivery()
    }

    override fun onBackground() {
        foreground = false
        if (pendingDelivery == null) pendingDelivery = active?.servingDelivery
        lifetime.open = false
        rowOps.values.forEach {
            if (it.edit != null) {
                discardEdit(it, NotSentReason.BACKGROUND)
                owe(it)
            }
            it.hints.trySend(Unit)
        }
        createOps.values.forEach { it.hints.trySend(Unit) }
        abandon()
        show(AlertLoadPhase.Idle)
    }

    fun admitsDelivery(use: AlertSettingsUse): Boolean = access.admits(use)

    fun onSourceDelivery(event: SourceAlertDelivery, use: AlertSettingsUse) {
        if (!access.admits(use) || !allowed(event.source to event.asset)) return
        val state = mutableState.value
        val id = event.id
        val rows = state.settings
        if (event.repeat == "false" && id != null && rows != null && state.identity == use.identity &&
            id !in rowOps && id !in uncertainPairs && id !in deleteReservations) {
            val index = rows.indexOfFirst {
                it.id == id && it.userId == use.identity.uid && it.source == event.source && it.asset == event.asset
            }
            if (index >= 0 && access.admits(use)) {
                val next = rows.toMutableList()
                next[index] = next[index].copy(isEnabled = false, triggered = true)
                deliveryMarks++
                touchRow(id)
                mutableState.value = state.copy(settings = next)
            }
        }
        if (!access.admits(use)) return
        pendingDelivery = use
        startPendingDelivery()
    }

    private fun startPendingDelivery() {
        val use = pendingDelivery ?: return
        if (!access.admits(use)) {
            pendingDelivery = null
        } else if (foreground && active == null) {
            begin(automatic = true, manual = false, deliveryUse = use)
        }
    }

    override fun onSync() {
        if (foreground) authoritative()
    }

    /** Main: a target value, with one serial worker per row. */
    fun setEnabled(id: Int, enabled: Boolean) {
        requestRow(id, enabled, deleting = false)
    }

    /** Main: removal takes precedence over any queued toggle or edit for the same row. */
    fun delete(id: Int) {
        requestRow(id, enabled = null, deleting = true)
    }

    /** Main: a pair the family does not allow is never reserved or sent; a create reserves before its worker starts. */
    fun addAlert(draft: SourceAlertDraft): SourceAlertSaveRequest {
        if (!allowed(draft.source to SourceAlertRowPolicy.SETTINGS_ASSET)) return finished(SourceAlertSaveOutcome.Unsupported)
        val state = mutableState.value
        val rows = state.settings
        if (!foreground || rows == null) return refused(NotSentReason.NOT_READY)
        val use = access.acquire()
        if (use == null || state.identity != use.identity || !access.admits(use)) {
            return refused(NotSentReason.UNADMITTED)
        }
        val occupied = rows.mapTo(mutableSetOf()) { it.id }.apply { addAll(deleteReservations) }
        if (occupied.size + createReservations.size >= 30) return refused(NotSentReason.CAP)
        val handle = SourceAlertSaveRequest()
        val token = Any()
        val op = CreateOp(epoch, use.identity, token, draft.copy(), use, handle, rowOps.keys.toMutableSet(), lifetime)
        createReservations.add(token)
        createOps[token] = op
        revision++
        val next = scope.launch(start = CoroutineStart.LAZY) { runCreate(op) }
        op.job = next
        next.start()
        return handle
    }

    /** Main: one replaceable waiting patch; the patch already sent keeps its own handle. Both pairs must be allowed. */
    fun editAlert(id: Int, edit: SourceAlertEdit): SourceAlertSaveRequest {
        if (edit.source != null && !allowed(edit.source to SourceAlertRowPolicy.SETTINGS_ASSET)) {
            return finished(SourceAlertSaveOutcome.Unsupported)
        }
        val state = mutableState.value
        val rows = state.settings
        if (!foreground || rows == null) return refused(NotSentReason.NOT_READY)
        val use = access.acquire()
        if (use == null || state.identity != use.identity || !access.admits(use)) {
            return refused(NotSentReason.UNADMITTED)
        }
        val existing = rowOps[id]
        if (existing?.delete != null) return refused(NotSentReason.SUPERSEDED)
        // The row's pair is unknown until the canonical list: nothing is bound to the pair shown.
        if (id in uncertainPairs) return refused(NotSentReason.NOT_READY)
        val row = rows.firstOrNull { it.id == id } ?: return refused(NotSentReason.NOT_READY)
        val origin = row.pair()
        if (!allowed(origin) || !allowed((edit.source ?: row.source) to row.asset)) return finished(SourceAlertSaveOutcome.Unsupported)
        if (existing != null && !owns(existing)) return refused(NotSentReason.UNADMITTED)
        val op = existing ?: newRow(row, rows.indexOf(row), use.identity)
        val handle = SourceAlertSaveRequest()
        discardEdit(op, NotSentReason.SUPERSEDED)
        op.edit = PendingEdit(epoch, use.identity, Any(), edit.copy(), use, ++acceptanceSequence, handle, origin, lifetime)
        revision++
        touchRow(id)
        op.hints.trySend(Unit)
        if (existing == null) startRow(op)
        return handle
    }

    private fun notSent(reason: NotSentReason) = SourceAlertSaveOutcome.NotSent(reason)

    private fun finished(outcome: SourceAlertSaveOutcome) = SourceAlertSaveRequest().also { it.finish(outcome) }

    private fun refused(reason: NotSentReason) = finished(notSent(reason))

    private fun unknownSave(failure: Throwable = IOException("Save result unknown")) = SourceAlertSaveOutcome.Failed(failure)

    private fun SourceAlertSetting.pair() = source to asset

    private fun allowed(pair: Pair<String, String>) = SourceAlertRowPolicy.EXCHANGES.admits(pair.first, pair.second)

    /**
     * The one answer the contract names as a definite rejection, HTTP 400: the server did not apply the request. Every other
     * status stays unknown until the canonical list settles it.
     */
    private fun definiteRejection(failure: Throwable): Boolean {
        val http = (failure as? AlertRepositoryException)
            ?.takeIf { it.failureKind == AlertRepositoryFailureKind.HTTP }?.httpFailure ?: return false
        return http.statusCode == 400
    }

    private fun discardEdit(op: RowOp, reason: NotSentReason) {
        val pending = op.edit
        op.edit = null
        pending?.handle?.finish(notSent(reason))
    }

    private fun newRow(row: SourceAlertSetting, position: Int, identity: AuthIdentityFence): RowOp =
        RowOp(row.id, epoch, identity, Any(), row.copy(), position).also { rowOps[row.id] = it }

    private fun startRow(op: RowOp) {
        val next = scope.launch(start = CoroutineStart.LAZY) { runRow(op) }
        op.job = next
        next.start()
    }

    private fun touchRow(id: Int) {
        createOps.values.forEach { it.overlaps.add(id) }
    }

    private fun requestRow(id: Int, enabled: Boolean?, deleting: Boolean) {
        if (!foreground) return
        val existing = rowOps[id]
        if (existing?.delete != null) return
        if (id in uncertainPairs) return
        val state = mutableState.value
        val rows = state.settings ?: return
        val row = rows.firstOrNull { it.id == id } ?: return
        val origin = row.pair()
        if (!allowed(origin)) return
        val use = access.acquire() ?: return
        if (state.identity != use.identity || !access.admits(use)) return
        if (existing != null && !owns(existing)) return
        val op = existing ?: newRow(row, rows.indexOf(row), use.identity)
        if (deleting) {
            op.delete = DeleteIntent(use, origin, lifetime, deleteReservations.add(id))
            discardEdit(op, NotSentReason.SUPERSEDED)
            op.toggle = null
        } else {
            op.toggle = ToggleIntent(enabled!!, ++acceptanceSequence, use, origin, lifetime)
        }
        // An accepted intent always invalidates a GET that raced it, even when the shown row does not change.
        revision++
        touchRow(id)
        display(op, if (deleting) null else optimistic(row, enabled!!))
        op.hints.trySend(Unit)
        if (existing == null) startRow(op)
    }

    private fun optimistic(row: SourceAlertSetting, enabled: Boolean): SourceAlertSetting =
        row.copy(isEnabled = enabled, triggered = if (enabled) false else row.triggered)

    private fun owns(op: RowOp): Boolean {
        val current = rowOps[op.id] ?: return false
        val snapshot = mutableState.value
        return current === op && current.token === op.token && op.epoch == epoch &&
            snapshot.settings != null && snapshot.identity == op.identity
    }

    /** Change only the row this op last displayed; a GET or a replacement op cannot be overwritten. */
    private fun display(op: RowOp, row: SourceAlertSetting?) {
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
            next.add(restoreIndex(next, op), row.copy())
        }
        op.shown = row?.copy()
        if (next != rows) {
            revision++
            touchRow(op.id)
            mutableState.value = state.copy(settings = next)
        }
    }

    /** After the nearest earlier id of the approved order that is shown now; first when none is. */
    private fun restoreIndex(next: List<SourceAlertSetting>, op: RowOp): Int {
        val order = approvedOrder.indexOf(op.id)
        if (order < 0) return op.position.coerceIn(0, next.size)
        for (i in order - 1 downTo 0) {
            val at = next.indexOfFirst { it.id == approvedOrder[i] }
            if (at >= 0) return at + 1
        }
        return 0
    }

    private fun owe(op: RowOp) {
        if (owns(op)) reconcileOwed = op.identity
    }

    /**
     * The worker ends before any of its waiting work reached the wire: the waiting edit ends unsent, a waiting toggle is
     * dropped, a waiting delete frees the reservation it added, the approved row is shown again, and the debt stays owed.
     */
    private fun abandonUnsent(op: RowOp, reason: NotSentReason) {
        discardEdit(op, reason)
        op.toggle = null
        if (op.delete != null) cancelDelete(op) else display(op, op.approved.copy())
        owe(op)
    }

    /** A delete known not to have removed its row can release only the place it added. */
    private fun cancelDelete(op: RowOp) {
        val delete = op.delete ?: return
        if (delete.ownsReservation) deleteReservations.remove(op.id)
        op.delete = null
        display(op, op.approved.copy())
    }

    /** A discarded optimistic toggle may be rolled back only while its value is still the one shown. */
    private fun discardToggle(op: RowOp) {
        if (op.toggle == null) return
        op.toggle = null
        if (op.delete == null) display(op, op.approved.copy())
    }

    /** Drop each waiting intent under its own acceptance conditions before selecting or consuming it. */
    private fun dropStale(op: RowOp) {
        val pair = op.approved.pair()
        op.edit?.let { edit ->
            val reason = when {
                !edit.lifetime.open -> NotSentReason.BACKGROUND
                edit.pair != pair -> NotSentReason.SUPERSEDED
                edit.epoch != epoch || edit.identity != op.identity || !access.admits(edit.requestUse) ->
                    NotSentReason.UNADMITTED
                else -> null
            }
            if (reason != null) {
                discardEdit(op, reason)
                owe(op)
            }
        }
        op.toggle?.let { toggle ->
            if (toggle.pair != pair || !toggle.lifetime.open || !access.admits(toggle.requestUse)) {
                discardToggle(op)
                owe(op)
            }
        }
        op.delete?.let { delete ->
            if (delete.pair != pair || !delete.lifetime.open || !access.admits(delete.requestUse)) {
                cancelDelete(op)
                owe(op)
            }
        }
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

    private fun owns(op: CreateOp): Boolean =
        createOps[op.token] === op && op.epoch == epoch &&
            mutableState.value.settings != null && mutableState.value.identity == op.identity

    private fun afterMutation(identity: AuthIdentityFence) {
        // Join the debt before waking an eager waiting chain into its fetch.
        if (foreground && rowOps.isEmpty() && createOps.isEmpty() &&
            mutableState.value.identity == identity && reconcileOwed == identity) authoritative()
        hintAccess()
    }

    private suspend fun awaitCreateHint(op: CreateOp, millis: Long) = coroutineScope {
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

    private suspend fun runCreate(op: CreateOp) {
        val endpoint = AuthenticatedEndpoint.SOURCE_NOTIFICATION_SETTINGS
        try {
            while (owns(op)) {
                if (!foreground || !op.lifetime.open || !access.admits(op.requestUse)) {
                    reconcileOwed = op.identity
                    createReservations.remove(op.token)
                    op.handle.finish(notSent(if (!op.lifetime.open) NotSentReason.BACKGROUND else NotSentReason.UNADMITTED))
                    return
                }
                val wait = floor.remainingMillis(endpoint)
                if (wait > 0) {
                    awaitCreateHint(op, wait)
                    continue
                }
                val use = access.acquire()
                if (!owns(op)) return
                if (!foreground || !op.lifetime.open || use == null || use.identity != op.identity ||
                    !access.admits(op.requestUse) || !access.admits(use)) {
                    reconcileOwed = op.identity
                    createReservations.remove(op.token)
                    op.handle.finish(notSent(if (!op.lifetime.open) NotSentReason.BACKGROUND else NotSentReason.UNADMITTED))
                    return
                }
                if (floor.remainingMillis(endpoint) > 0) continue
                val draft = op.draft
                val target = draft.source to SourceAlertRowPolicy.SETTINGS_ASSET
                val request = SourceAlertSettingRequest(
                    source = draft.source, asset = SourceAlertRowPolicy.SETTINGS_ASSET, condition = draft.condition,
                    threshold = draft.threshold, isEnabled = draft.isEnabled, repeatIntervalSec = draft.repeat?.seconds
                )
                val original = op.requestUse
                op.entered = true
                val result = sendMutation(original, endpoint, { owns(op) }) {
                    createRemote(op.identity, request) { op.lifetime.open && !op.retired && access.admits(original) }
                }
                if (!owns(op)) return
                // A send that never reached the wire is not sent, whatever the original use has become since.
                val unsent = result is MutationResult.Failed && result.preWire
                // So is a definite rejection of this request: the server changed nothing.
                val rejected = result is MutationResult.Failed && definiteRejection(result.failure)
                if ((!unsent && !rejected && !access.admits(original)) || result == MutationResult.Unadmitted) {
                    reconcileOwed = op.identity
                    op.handle.finish(unknownSave())
                    return
                }
                when (result) {
                    is MutationResult.Loaded -> {
                        val row = result.row
                        val state = mutableState.value
                        val rows = state.settings ?: return
                        val listed = rows.filter { it.id == row.id }
                        if (row.userId != op.identity.uid || row.pair() != target ||
                            listed.any { it.userId != row.userId || it.pair() != row.pair() }) {
                            reconcileOwed = op.identity
                            op.handle.finish(unknownSave(invalidRow()))
                            return
                        }
                        // A completed row operation, or a hidden row whose delete awaits the canonical list, overlaps this request.
                        if (rowOps.containsKey(row.id) || row.id in op.overlaps || row.id in deleteReservations ||
                            row.id in uncertainPairs) {
                            reconcileOwed = op.identity
                            op.handle.finish(unknownSave())
                            return
                        }
                        val next = rows.toMutableList()
                        val index = next.indexOfFirst { it.id == row.id }
                        if (index < 0) {
                            next.add(0, row.copy())
                            approvedOrder.remove(row.id)
                            approvedOrder.add(0, row.id)
                        } else {
                            // An answer for a shown row updates it in place: its approved place does not move.
                            next[index] = row.copy()
                        }
                        revision++
                        touchRow(row.id)
                        createReservations.remove(op.token)
                        mutableState.value = state.copy(settings = next)
                        op.handle.finish(SourceAlertSaveOutcome.Saved(row.copy()))
                    }
                    is MutationResult.Failed -> {
                        if (result.preWire) {
                            createReservations.remove(op.token)
                            op.handle.finish(notSent(if (!op.lifetime.open) NotSentReason.BACKGROUND else NotSentReason.UNADMITTED))
                        } else if (definiteRejection(result.failure)) {
                            // The server answered with a rejection: nothing was created, so the reservation is freed now.
                            createReservations.remove(op.token)
                            op.handle.finish(SourceAlertSaveOutcome.Failed(result.failure))
                        } else {
                            reconcileOwed = op.identity
                            op.handle.finish(unknownSave(result.failure))
                        }
                    }
                    MutationResult.Dropped -> return
                    MutationResult.Unadmitted, MutationResult.Deleted -> {
                        reconcileOwed = op.identity
                        op.handle.finish(unknownSave())
                    }
                }
                return
            }
        } finally {
            if (owns(op)) {
                if (op.handle.outcome.value == SourceAlertSaveOutcome.Pending) {
                    reconcileOwed = op.identity
                    if (!op.entered) createReservations.remove(op.token)
                    op.handle.finish(if (op.entered) unknownSave() else notSent(NotSentReason.UNADMITTED))
                }
                createOps.remove(op.token)
                afterMutation(op.identity)
            }
        }
    }

    private fun changesPair(edit: PendingEdit?): Boolean =
        edit?.patch?.source != null && edit.patch.source != edit.pair.first

    private fun invalidRow() =
        AlertRepositoryException("Invalid row", failureKind = AlertRepositoryFailureKind.DECODE)

    private suspend fun runRow(op: RowOp) {
        try {
            while (owns(op)) {
                // With no foreground nothing at all is left to send; otherwise work whose foreground has ended is dropped.
                if (!foreground) {
                    abandonUnsent(op, NotSentReason.BACKGROUND)
                    return
                }
                dropStale(op)
                val delete = op.delete
                val deleting = delete != null
                val edit = if (deleting) null else op.edit
                val toggle = if (deleting || edit != null) null else op.toggle
                if (!deleting && edit == null && toggle == null) {
                    display(op, op.approved.copy())
                    return
                }
                val original = delete?.requestUse ?: edit?.requestUse ?: toggle!!.requestUse
                val intentLifetime = delete?.lifetime ?: edit?.lifetime ?: toggle!!.lifetime
                val endpoint = if (deleting) AuthenticatedEndpoint.DELETE_SOURCE_NOTIFICATION_SETTING
                    else AuthenticatedEndpoint.UPDATE_SOURCE_NOTIFICATION_SETTING
                val wait = floor.remainingMillis(endpoint)
                if (wait > 0) {
                    awaitRowHint(op, wait)
                    continue
                }
                val use = access.acquire()
                if (!owns(op)) return
                // An acquire may observe a new floor or a publication. Recheck the selected work too.
                if (op.delete !== delete || op.edit !== edit || (!deleting && edit == null && op.toggle !== toggle)) continue
                if (!intentLifetime.open || !access.admits(original)) continue
                if (!foreground || use == null || use.identity != op.identity || !access.admits(use)) {
                    abandonUnsent(op, if (!foreground) NotSentReason.BACKGROUND else NotSentReason.UNADMITTED)
                    return
                }
                if (floor.remainingMillis(endpoint) > 0) continue
                if (edit != null) {
                    op.edit = null
                    op.sendingEdit = edit
                }
                val target = when {
                    delete != null -> delete.pair
                    edit != null -> (edit.patch.source ?: edit.pair.first) to edit.pair.second
                    else -> toggle!!.pair
                }
                op.inFlight = true
                val result = try {
                    sendMutation(original, endpoint, { owns(op) }, deleting) {
                        val guard = { intentLifetime.open && !op.retired && access.admits(original) }
                        when {
                            deleting -> deleteRemote(op.identity, op.id, guard)
                            edit != null -> {
                                val patch = edit.patch
                                val request = SourceAlertSettingUpdateRequest(
                                    source = patch.source, condition = patch.condition, threshold = patch.threshold,
                                    isEnabled = patch.isEnabled, repeatIntervalSec = patch.repeat.toJson()
                                )
                                editRemote(op.identity, op.id, request, guard)
                            }
                            else -> editRemote(op.identity, op.id, SourceAlertSettingUpdateRequest(isEnabled = toggle!!.enabled), guard)
                        }
                    }
                } finally {
                    op.inFlight = false
                }
                if (!owns(op)) return
                // A send that never reached the wire is not sent, whatever the original use has become since.
                val unsent = result is MutationResult.Failed && result.preWire
                // So is a definite rejection of this request: the server changed nothing.
                val rejected = result is MutationResult.Failed && definiteRejection(result.failure)
                if ((!unsent && !rejected && !access.admits(original)) || result == MutationResult.Unadmitted) {
                    if (changesPair(edit)) uncertainPairs.add(op.id)
                    else if (!deleting) cancelDelete(op)
                    if (op.toggle !== toggle) discardToggle(op)
                    edit?.handle?.finish(unknownSave())
                    op.sendingEdit = null
                    discardEdit(op, NotSentReason.UNADMITTED)
                    owe(op)
                    return
                }
                when (result) {
                    MutationResult.Dropped -> return
                    MutationResult.Unadmitted -> return
                    MutationResult.Deleted -> {
                        deleteReservations.remove(op.id)
                        approvedOrder.remove(op.id)
                        return
                    }
                    is MutationResult.Loaded -> {
                        val row = result.row
                        if (row.id != op.id || row.userId != op.identity.uid || row.pair() != target) {
                            edit?.handle?.finish(unknownSave(invalidRow()))
                            op.sendingEdit = null
                            discardEdit(op, NotSentReason.SUPERSEDED)
                            if (op.toggle !== toggle) discardToggle(op)
                            owe(op)
                            if (changesPair(edit)) {
                                uncertainPairs.add(op.id)
                                return
                            }
                            if (!deleting && op.delete != null) continue
                            return
                        }
                        op.approved = row.copy()
                        val consumed = if (edit != null) {
                            if (edit.patch.isEnabled != null) edit.sequence else op.consumedEnabled
                        } else toggle!!.sequence
                        op.consumedEnabled = maxOf(op.consumedEnabled, consumed)
                        // Old-pair work is dropped (and owes its debt) before a later toggle can be consumed silently.
                        dropStale(op)
                        val laterToggle = op.toggle
                        if (laterToggle != null &&
                            (laterToggle.sequence <= op.consumedEnabled || laterToggle.enabled == row.isEnabled)) {
                            op.toggle = null
                        }
                        if (op.delete == null) {
                            display(op, op.toggle?.let { optimistic(row, it.enabled) } ?: row.copy())
                        }
                        edit?.handle?.finish(SourceAlertSaveOutcome.Saved(row.copy()))
                        op.sendingEdit = null
                    }
                    is MutationResult.Failed -> {
                        val failure = result.failure
                        if (result.preWire) {
                            // Nothing of the selected work reached the wire: it alone ends, unsent, and owes nothing. Work
                            // accepted after it is checked on its own at the loop's start and may still go.
                            when {
                                deleting -> cancelDelete(op)
                                edit != null -> {
                                    edit.handle.finish(notSent(if (!intentLifetime.open) NotSentReason.BACKGROUND else NotSentReason.UNADMITTED))
                                    op.sendingEdit = null
                                }
                                op.toggle === toggle -> discardToggle(op)
                            }
                            if (op.delete == null && op.edit == null && op.toggle == null) {
                                display(op, op.approved.copy())
                                return
                            }
                            continue
                        }
                        val http = (failure as? AlertRepositoryException)
                            ?.takeIf { it.failureKind == AlertRepositoryFailureKind.HTTP }?.httpFailure
                        if (http?.statusCode == 404) {
                            // Gone on the server: removed here, and the canonical GET confirms it (also for a delete).
                            deleteReservations.remove(op.id)
                            approvedOrder.remove(op.id)
                            if (!deleting) display(op, null)
                            owe(op)
                            edit?.handle?.finish(SourceAlertSaveOutcome.Removed)
                            op.sendingEdit = null
                            discardEdit(op, NotSentReason.NOT_READY)
                            op.toggle = null
                            return
                        }
                        val httpFailure = http != null && http.statusCode !in 200..299
                        // A definitely rejected delete frees the reservation it added.
                        if (deleting && definiteRejection(failure)) {
                            cancelDelete(op)
                        } else if (httpFailure && (deleting || op.delete == null)) {
                            display(op, op.approved.copy())
                        }
                        owe(op)
                        edit?.handle?.finish(unknownSave(failure))
                        op.sendingEdit = null
                        discardEdit(op, NotSentReason.SUPERSEDED)
                        if (op.toggle !== toggle) discardToggle(op)
                        if (changesPair(edit) && !definiteRejection(failure)) {
                            uncertainPairs.add(op.id)
                            return
                        }
                        if (failure is AuthIdentityChangedException) {
                            if (!deleting) cancelDelete(op)
                            return
                        }
                        // A separately accepted delete can continue only while its original pair is known.
                        if (!deleting && op.delete != null) continue
                        return
                    }
                }
            }
        } finally {
            if (rowOps[op.id]?.token === op.token) {
                op.sendingEdit?.handle?.finish(unknownSave())
                op.sendingEdit = null
                discardEdit(op, if (!foreground) NotSentReason.BACKGROUND else NotSentReason.SUPERSEDED)
                rowOps.remove(op.id)
                afterMutation(op.identity)
            }
        }
    }

    /** Recover response evidence before any body, ownership, admission or fold check. No mutation replay. */
    private suspend fun sendMutation(
        use: AlertSettingsUse,
        endpoint: AuthenticatedEndpoint,
        owned: () -> Boolean,
        deleting: Boolean = false,
        remote: suspend () -> AuthBoundResult<*>
    ): MutationResult {
        var received = false
        return try {
            val bound = remote()
            received = true
            bound.rateLimitResponseEvidence?.let { floor.record(endpoint, AlertRateLimitEvidence.of(it)) }
            if (!owned()) return MutationResult.Dropped
            if (!access.admits(use)) {
                // A definite rejection of this owner's own request still applies: it only frees what the request held.
                // Any other answer under a withdrawn use stays unknown, and no body is adopted.
                val rejection = bound.fold(onSuccess = { null }, onFailure = { it })
                return if (rejection != null && definiteRejection(rejection)) MutationResult.Failed(rejection) else MutationResult.Unadmitted
            }
            bound.fold(
                onSuccess = {
                    if (deleting) MutationResult.Deleted
                    else if (it is SourceAlertSetting) MutationResult.Loaded(it.copy())
                    else MutationResult.Failed(invalidRow())
                },
                onFailure = { MutationResult.Failed(it) }
            )
        } catch (changed: AuthIdentityChangedException) {
            floor.record(endpoint, AlertRateLimitEvidence.of(changed.exchanges, changed.statusCode, changed.retryAfter))
            MutationResult.Failed(changed)
        } catch (withheld: TopicUseWithheldException) {
            floor.record(endpoint, AlertRateLimitEvidence.of(withheld.exchanges, null, null))
            MutationResult.Failed(withheld, preWire = !received && withheld.exchanges.isEmpty())
        } catch (cancelled: CancellationException) {
            MutationResult.Failed(cancelled)
        } catch (failure: Exception) {
            MutationResult.Failed(failure)
        }
    }

    private fun begin(automatic: Boolean, manual: Boolean, deliveryUse: AlertSettingsUse? = null) {
        val chain = Chain(generation, automatic, manual, deliveryUse)
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
                chain.startRevision = revision
                val marksAtStart = deliveryMarks
                val deliveryOnly = chain.deliveryUse != null
                chain.servingDelivery = pendingDelivery?.takeIf(access::admits) ?: chain.servingDelivery?.takeIf(access::admits)
                pendingDelivery = null
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
                    if (deliveryOnly && chain.deliveryUse == null) continue
                    show(AlertLoadPhase.Idle)
                    return
                }
                when (result) {
                    FetchResult.Silent -> {
                        show(AlertLoadPhase.Idle)
                        return
                    }
                    is FetchResult.Loaded -> {
                        // A write that began or ended after this GET started makes its body stale (the bank owner's rule).
                        if (revision != chain.startRevision || rowOps.isNotEmpty() || createOps.isNotEmpty()) {
                            chain.dirty = true
                            attempt = 0
                            chain.retryNotBefore = null
                            continue
                        }
                        if (marksAtStart != deliveryMarks) {
                            if (chain.manual || chain.dirty) {
                                attempt = 0
                                chain.retryNotBefore = null
                                continue
                            }
                            return
                        }
                        chain.servingDelivery = null
                        reconcileOwed = null
                        createReservations.clear()
                        deleteReservations.clear()
                        lastSuccessAt = nowMillis()
                        val adopted = SourceAlertRowPolicy.EXCHANGES.adopt(result.settings, use.identity.uid)
                        uncertainPairs.clear()
                        approvedOrder.clear()
                        adopted.mapTo(approvedOrder) { it.id }
                        mutableState.value = SourceAlertSettingsState(AlertLoadPhase.Ready, adopted, use.identity)
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
                startPendingDelivery()
            }
        }
    }

    /** Every wake and every send rechecks the process floor, independently of account and retry delay. */
    private suspend fun awaitUse(chain: Chain): AlertSettingsUse? {
        while (current(chain)) {
            if (chain.deliveryUse?.let { !access.admits(it) } == true) return null
            // A GET is not sent while a write is active; the last write's end wakes this wait.
            if (rowOps.isNotEmpty() || createOps.isNotEmpty()) {
                chain.waitingAccess = true
                try {
                    if (rowOps.isNotEmpty() || createOps.isNotEmpty()) awaitAccessHint(chain)
                } finally {
                    chain.waitingAccess = false
                }
                continue
            }
            val wait = maxOf(
                floor.remainingMillis(AuthenticatedEndpoint.SOURCE_NOTIFICATION_SETTINGS),
                chain.retryNotBefore?.let(::remaining) ?: 0
            )
            if (wait > 0) {
                sleep(wait)
                continue
            }
            val use = chain.deliveryUse ?: access.acquire()
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
            if (rowOps.isNotEmpty() || createOps.isNotEmpty()) continue
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

/** The production delegation of the three writes: each request's own use check goes to the repository unchanged. */
internal fun productionSourceCreate(
    repository: AlertRepository
): suspend (AuthIdentityFence, SourceAlertSettingRequest, () -> Boolean) -> AuthBoundResult<SourceAlertSetting> =
    { owner, request, admitted -> repository.createSourceSetting(owner, request, admitted) }

internal fun productionSourceEdit(
    repository: AlertRepository
): suspend (AuthIdentityFence, Int, SourceAlertSettingUpdateRequest, () -> Boolean) -> AuthBoundResult<SourceAlertSetting> =
    { owner, id, request, admitted -> repository.updateSourceSetting(owner, id, request, admitted) }

internal fun productionSourceDelete(
    repository: AlertRepository
): suspend (AuthIdentityFence, Int, () -> Boolean) -> AuthBoundResult<Unit> =
    { owner, id, admitted -> repository.deleteSourceSetting(owner, id, admitted) }
