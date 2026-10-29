package com.jay.fxi.service

import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.entitlements.KrxContestSwitch
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Singleton
class AlertEventBus() {
    private var krxContestSwitch = KrxContestSwitch.OFF
    private var admitted: () -> Boolean = { ReleaseAdmission.isOpen }
    private var tapOwner: PendingAlertTapOwner? = null
    private var snapshot: (() -> TopicAccessSnapshot)? = null
    private var liveIdentity: (() -> AuthIdentityFence?)? = null
    private val pumpLock = Any()
    private val consumers = linkedSetOf<Long>()
    private var consumerSequence = 0L

    @Inject
    internal constructor(
        krxContestSwitch: KrxContestSwitch,
        coordinatorProvider: Provider<PremiumAccessCoordinator>,
        authTokenProvider: Provider<AuthTokenProvider>,
        authFenceStream: Provider<AuthFenceStream>
    ) : this() {
        this.krxContestSwitch = krxContestSwitch
        // D24 precedes both the mode read and every provider resolution.
        if (ReleaseAdmission.isOpen && krxContestSwitch.enabled) {
            val coordinator = coordinatorProvider.get()
            val auth = authTokenProvider.get()
            install(
                PendingAlertTapOwner(), { coordinator.accessSnapshot }, auth::currentIdentityFence,
                coordinator.accessRevisions, authFenceStream.get(),
                CoroutineScope(SupervisorJob() + Dispatchers.Default)
            )
        }
    }

    internal constructor(
        tapOwner: PendingAlertTapOwner?, enabled: Boolean, admitted: () -> Boolean,
        accessSnapshot: () -> TopicAccessSnapshot = { TopicAccessSnapshot.INITIAL },
        liveFence: () -> AuthIdentityFence? = { null },
        revisions: StateFlow<Long>? = null, fences: AuthFenceStream? = null,
        scope: CoroutineScope? = null
    ) : this() {
        this.krxContestSwitch = KrxContestSwitch(enabled)
        this.admitted = admitted
        if (admitted() && enabled) install(tapOwner ?: PendingAlertTapOwner(), accessSnapshot, liveFence, revisions, fences, scope)
    }

    private fun install(
        owner: PendingAlertTapOwner,
        accessSnapshot: () -> TopicAccessSnapshot,
        liveFence: () -> AuthIdentityFence?,
        revisions: StateFlow<Long>?,
        fences: AuthFenceStream?,
        scope: CoroutineScope?
    ) {
        tapOwner = owner
        snapshot = accessSnapshot
        liveIdentity = liveFence
        if (scope == null) return
        if (fences != null) {
            val identities = Channel<AuthIdentityFence?>(Channel.UNLIMITED)
            fences.observe { identity ->
                // This may run under the auth delivery pump/PAC mutex: enqueue only.
                check(identities.trySend(identity).isSuccess)
            }
            scope.launch {
                for (identity in identities) {
                    owner.identityChanged(identity)
                    reconcileAndPump()
                }
            }
        }
        if (revisions != null) scope.launch { revisions.collect { reconcileAndPump() } }
        scope.launch {
            _events.subscriptionCount.collect {
                synchronized(pumpLock) {
                    if (_events.subscriptionCount.value == 0) owner.detach()
                }
                reconcileAndPump()
            }
        }
    }

    internal val pendingTapEnabled: Boolean get() = krxContestSwitch.enabled
    internal fun pendingTapReport(): PendingTapPurgeReport = tapOwner?.report() ?: PendingTapPurgeReport()

    internal fun pendingTapIntake(): AlertTapIntake = AlertTapIntake(
        context = {
            check(admitted() && pendingTapEnabled)
            val read = readAccess()
            tapOwner?.reconcile(read.first, read.second)
            AlertTapContext.capture(read.first, read.second)
        },
        capture = ::capturePendingTap,
        classify = { ticket -> tapOwner?.classify(ticket) ?: AlertTapClassification.UNKNOWN },
        forward = ::forwardPendingTap
    )

    internal fun capturePendingTap(
        event: AlertEvent?, metadata: AlertTapMetadata, context: AlertTapContext
    ): AlertTapCaptureResult {
        if (!admitted() || !pendingTapEnabled) return AlertTapCaptureResult.REJECTED
        return tapOwner?.capture(event, metadata, context) ?: AlertTapCaptureResult.REJECTED
    }

    internal fun forwardPendingTap(event: AlertEvent): Boolean {
        if (!admitted() || !pendingTapEnabled || event !is AlertEvent.PendingTap) return false
        val owner = tapOwner ?: return false
        if (!owner.hasCustody(event.ticket)) return false
        reconcileAndPump()
        return owner.hasCustody(event.ticket)
    }

    /** Registered only from SharedFlow.onSubscription, after collection is actually active. */
    internal fun registerPendingTapConsumer(): AutoCloseable {
        if (!admitted() || !pendingTapEnabled || tapOwner == null) return AutoCloseable { }
        val consumer = synchronized(pumpLock) {
            check(consumerSequence != Long.MAX_VALUE)
            (++consumerSequence).also { consumers.add(it) }
        }
        pendingTapConsumerReady(consumer)
        return AutoCloseable { detachPendingTapConsumer(consumer) }
    }

    private fun pendingTapConsumerReady(consumer: Long) {
        if (synchronized(pumpLock) { consumer in consumers }) reconcileAndPump()
    }

    private fun detachPendingTapConsumer(consumer: Long) {
        val detached = synchronized(pumpLock) { consumers.remove(consumer) }
        if (detached) {
            synchronized(pumpLock) { tapOwner?.detach() }
            reconcileAndPump()
        }
    }

    internal fun consumePendingTap(event: AlertEvent, action: (AlertEvent) -> AlertTapReceipt): AlertTapReceipt {
        if (!admitted()) return AlertTapReceipt.REJECTED
        if (event !is AlertEvent.PendingTap) {
            return try { action(event) } finally { reconcileAndPump() }
        }
        if (!pendingTapEnabled) return AlertTapReceipt.REJECTED
        val owner = tapOwner ?: return AlertTapReceipt.REJECTED
        return try {
            // Reservation precedes all external reads. The owner retries after contention or
            // version movement, so a bridge lock cannot hide a queued revocation at final claim.
            owner.consumeCurrent(event.ticket, ::readAccess, action)
        } finally {
            reconcileAndPump()
        }
    }

    private fun readAccess(): Pair<TopicAccessSnapshot, AuthIdentityFence?> {
        // Read live first: auth callbacks only enqueue and cannot re-enter this bridge.
        return try {
            val identitySupplier = liveIdentity
            val live = identitySupplier?.invoke()
            (snapshot?.invoke() ?: TopicAccessSnapshot.INITIAL) to live
        } catch (error: Throwable) {
            tapOwner?.recordFailure()
            throw error
        }
    }

    private fun reconcileAndPump() {
        if (!admitted() || !pendingTapEnabled) return
        val owner = tapOwner ?: return
        synchronized(pumpLock) {
            val read = readAccess()
            val ready = owner.dispatchable(read.first, read.second)
            if (consumers.isEmpty() || _events.subscriptionCount.value == 0) return
            for (ticket in ready) {
                if (!owner.markQueued(ticket)) continue
                if (!_events.tryEmit(AlertEvent.PendingTap(ticket))) {
                    owner.releaseQueued(ticket)
                    break
                }
            }
        }
    }

    private val _events = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 10)
    val events: SharedFlow<AlertEvent> = _events.asSharedFlow()

    fun emit(event: AlertEvent): Boolean {
        if (!admitted()) return false
        return _events.tryEmit(event)
    }
}

/** The three server alert families (FCM `type`: rate_alert / source_rate_alert / comparison_alert). */
enum class AlertFamily { BANK, SOURCE, COMPARISON }

sealed class AlertEvent {
    /** Only the owner can unwrap this process-local custody marker. */
    internal data class PendingTap(val ticket: AlertTapTicket) : AlertEvent()

    /** One setting of [family] fired. */
    data class SettingTriggered(val settingId: Int, val family: AlertFamily = AlertFamily.BANK) : AlertEvent()
    /** [family]'s settings need a reload (its push carried no readable setting id). */
    data class FamilyRefreshNeeded(val family: AlertFamily) : AlertEvent()
    /** Every family needs a reload (sync_alerts or an unreadable bank setting id). */
    data object RefreshNeeded : AlertEvent()
}
