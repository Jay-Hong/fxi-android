package com.jay.fxi.data.alerts

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.confirmsPremiumFor
import com.jay.fxi.service.AlertEvent
import com.jay.fxi.service.AlertFamily
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal interface AlertFamilyAdapter {
    fun onForeground(authoritative: Boolean = false)
    fun onBackground()
    fun onSync()
    fun onBankPush(id: Int?, isRepeat: String?) {}
}

@Singleton
internal class AlertFamilyRefresherRegistry internal constructor(
    private val families: List<() -> AlertFamilyAdapter>,
    private val lifecycle: () -> Lifecycle,
    private val postToMain: (() -> Unit) -> Unit,
    private val premium: StateFlow<OwnedPremiumAccess>,
    private val liveIdentity: () -> AuthIdentityFence?,
    private val scope: CoroutineScope,
    private val reportFailure: () -> Unit = {
        Log.w(TAG, "alert family adapter dispatch failed")
        Unit
    }
) {
    @Inject constructor(
        bank: Provider<BankAlertSettingsOwner>,
        coordinator: PremiumAccessCoordinator,
        tokens: AuthTokenProvider
    ) : this(
        listOf({ bank.get() }),
        { ProcessLifecycleOwner.get().lifecycle },
        { action -> Handler(Looper.getMainLooper()).post { action() }; Unit },
        coordinator.state,
        tokens::currentIdentityFence,
        CoroutineScope(SupervisorJob() + Dispatchers.Main)
    )

    private var started = false
    private var foreground: Boolean? = null
    private var pendingFor: AuthIdentityFence? = null
    private val adapters = mutableListOf<AlertFamilyAdapter>()

    /** Main. Resolve families only once, before an observer can replay the current foreground. */
    fun start() {
        if (started) return
        started = true
        families.forEach { supplier ->
            try {
                adapters += supplier()
            } catch (e: Exception) {
                reportFailure()
            }
        }
        val process = lifecycle()
        process.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                setForeground(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                setForeground(false)
            }
        })
        scope.launch {
            premium.collect { access ->
                val pending = pendingFor
                if (pending != null && (liveIdentity() != pending || !access.confirmsPremiumFor(pending))) {
                    pendingFor = null
                }
            }
        }
        setForeground(process.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    /** The enqueue itself may throw; the caller's delivery boundary reports that failure. */
    fun onSyncAlerts() {
        postToMain {
            val identity = liveIdentity()
            if (!premium.value.confirmsPremiumFor(identity)) {
                pendingFor = null
            } else if (foreground == true) {
                dispatch { it.onSync() }
            } else {
                pendingFor = identity
            }
        }
    }

    fun onBankPush(id: Int?, isRepeat: String?) {
        postToMain { dispatch { it.onBankPush(id, isRepeat) } }
    }

    private fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) {
            val pending = pendingFor
            pendingFor = null
            val authoritative = pending != null && liveIdentity() == pending && premium.value.confirmsPremiumFor(pending)
            dispatch { it.onForeground(authoritative) }
        } else {
            dispatch { it.onBackground() }
        }
    }

    private fun dispatch(action: (AlertFamilyAdapter) -> Unit) {
        adapters.toList().forEach { adapter ->
            try {
                action(adapter)
            } catch (e: Exception) {
                reportFailure()
            }
        }
    }

    companion object {
        private const val TAG = "AlertFamilies"
        const val SYNC_ALERTS = "sync_alerts"
        const val RATE_ALERT = "rate_alert"

        fun deliverPush(
            type: String,
            settingId: String?,
            isRepeat: String?,
            gateOpen: Boolean,
            reportFailure: () -> Unit = {
                Log.w(TAG, "alert family push delivery failed")
                Unit
            },
            resolve: () -> AlertFamilyRefresherRegistry
        ) {
            if (!gateOpen) return
            try {
                when (type) {
                    SYNC_ALERTS -> resolve().onSyncAlerts()
                    RATE_ALERT -> resolve().onBankPush(settingId?.toIntOrNull(), isRepeat)
                }
            } catch (e: Exception) {
                reportFailure()
            }
        }

        fun deliverTap(
            event: AlertEvent,
            isRepeat: String?,
            gateOpen: Boolean,
            reportFailure: () -> Unit = {
                Log.w(TAG, "alert family tap delivery failed")
                Unit
            },
            resolve: () -> AlertFamilyRefresherRegistry
        ) {
            if (!gateOpen) return
            try {
                when {
                    event is AlertEvent.SettingTriggered && event.family == AlertFamily.BANK -> resolve().onBankPush(event.settingId, isRepeat)
                    event == AlertEvent.RefreshNeeded -> resolve().onBankPush(null, null)
                }
            } catch (e: Exception) {
                reportFailure()
            }
        }
    }
}
