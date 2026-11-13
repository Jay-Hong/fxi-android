package com.jay.fxi.service

import android.content.Context
import android.util.Log
import com.jay.fxi.BuildConfig
import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.confirmsPremiumFor
import com.jay.fxi.shouldStartAppOwnedServices
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Main-thread event bridge. Observations join only while their evaluation is in progress. */
@Singleton
internal class NotificationCapabilityMonitor internal constructor(
    private val read: () -> NotificationCapability,
    private val reconcile: suspend (AuthIdentityFence?) -> Unit,
    private val premium: StateFlow<OwnedPremiumAccess>,
    private val liveIdentity: () -> AuthIdentityFence?,
    private val scope: CoroutineScope,
    private val admitted: () -> Boolean = { true },
    private val reportFailure: () -> Unit = {
        Log.w("NotificationCapability", "capability evaluation failed")
        Unit
    }
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        manager: PushNotificationManager,
        coordinator: PremiumAccessCoordinator,
        tokens: AuthTokenProvider
    ) : this(
        { readNotificationCapability(context) },
        manager::reconcileRegistration,
        coordinator.state,
        tokens::currentIdentityFence,
        CoroutineScope(SupervisorJob() + Dispatchers.Main),
        { shouldStartAppOwnedServices(ReleaseAdmission.isOpen, BuildConfig.BENCHMARK_NO_DATA_MODE) }
    )

    private data class Observation(
        val owner: AuthIdentityFence?,
        val capability: NotificationCapability?,
        val access: OwnedPremiumAccess
    )

    private val mutableState = MutableStateFlow<NotificationCapability?>(null)
    val state: StateFlow<NotificationCapability?> = mutableState.asStateFlow()
    private var observing = false
    private var pending: Observation? = null
    private var inFlight: Observation? = null
    private var consumer: Job? = null

    fun onForeground() {
        if (!admitted()) return
        val capability = refresh()
        if (!observing) {
            observing = true
            // The initial publication supplies the first demand, coalescing its foreground.
            scope.launch {
                premium.collect { access ->
                    if (admitted()) {
                        try {
                            val current = refresh() ?: mutableState.value
                            demand(Observation(liveIdentity(), current, access))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            reportFailure()
                        }
                    }
                }
            }
        } else {
            demand(Observation(liveIdentity(), capability, premium.value))
        }
    }

    fun onRequestResult(owner: AuthIdentityFence?): Boolean {
        if (!admitted()) return false
        val capability = refresh()
        val access = premium.value
        if (owner == null || liveIdentity() != owner || !access.confirmsPremiumFor(owner)) return false
        demand(Observation(owner, capability, access))
        return capability == NotificationCapability.GRANTED
    }

    fun onAlreadyGranted(owner: AuthIdentityFence): Boolean = onRequestResult(owner)

    /** Failed reads retain the last display; the facade still reads fresh during evaluation. */
    private fun refresh(): NotificationCapability? = try {
        read().also { mutableState.value = it }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        reportFailure()
        null
    }

    private fun demand(observation: Observation) {
        if (observation == inFlight) return
        pending = observation
        startConsumer()
    }

    private fun startConsumer() {
        if (consumer != null || !scope.isActive || pending == null) return
        // Assign before starting, including when the injected dispatcher runs immediately.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val next = pending ?: break
                    pending = null
                    inFlight = next
                    try {
                        if (admitted()) reconcile(next.owner)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        reportFailure()
                    } finally {
                        inFlight = null
                    }
                }
            } finally {
                consumer = null
                // An inner cancellation ends this consumer, while an active scope keeps its demand.
                if (scope.isActive && pending != null) startConsumer()
            }
        }
        consumer = job
        job.start()
    }
}
