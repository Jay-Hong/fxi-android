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
import com.jay.fxi.data.remote.TopicRuntimeOwner
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.service.AlertTapCodec
import com.jay.fxi.service.AlertTapExtraKind
import com.jay.fxi.service.AlertTapExtras
import com.jay.fxi.service.AlertEvent
import com.jay.fxi.service.AlertFamily
import com.jay.fxi.service.NotificationCapabilityMonitor
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** S8-3: what every registered family receives: the process lifecycle and the silent sync. */
internal interface AlertLifecycleAdapter {
    fun onForeground(authoritative: Boolean = false)
    fun onBackground()
    fun onSync()
}

/** A family that also takes the bank's individual push and tap; the registry hands those to such a family only. */
internal interface AlertFamilyAdapter : AlertLifecycleAdapter {
    fun onBankPush(id: Int?, isRepeat: String?) {}
}

internal data class SourceAlertDelivery(
    val source: String,
    val asset: String,
    val id: Int?,
    val repeat: String?
)

@Singleton
internal class AlertFamilyRefresherRegistry internal constructor(
    private val families: List<() -> AlertLifecycleAdapter>,
    private val lifecycle: () -> Lifecycle,
    private val postToMain: (() -> Unit) -> Unit,
    private val premium: StateFlow<OwnedPremiumAccess>,
    private val liveIdentity: () -> AuthIdentityFence?,
    private val scope: CoroutineScope,
    private val reportFailure: () -> Unit = {
        Log.w(TAG, "alert family adapter dispatch failed")
        Unit
    },
    private val foregroundHooks: List<() -> Unit> = emptyList(),
    private var captureSourceUse: () -> AlertSettingsUse? = { null }
) {
    @Inject constructor(
        bank: Provider<BankAlertSettingsOwner>,
        source: Provider<SourceAlertSettingsOwner>,
        coordinator: PremiumAccessCoordinator,
        tokens: AuthTokenProvider,
        capability: Provider<NotificationCapabilityMonitor>
    ) : this(
        listOf({ bank.get() }, { source.get() }),
        { ProcessLifecycleOwner.get().lifecycle },
        { action -> Handler(Looper.getMainLooper()).post { action() }; Unit },
        coordinator.state,
        tokens::currentIdentityFence,
        CoroutineScope(SupervisorJob() + Dispatchers.Main),
        foregroundHooks = listOf({ capability.get().onForeground() })
    )

    @Inject
    internal fun bindSourceUse(topic: TopicRuntimeOwner, uses: TopicUseAuthority, access: AlertSettingsAccess) {
        captureSourceUse = capture@{
            val fence = topic.currentAccessFence() ?: return@capture null
            val lifetime = uses.acquire(fence) ?: return@capture null
            AlertSettingsUse(fence.identity, fence, lifetime).takeIf(access::admits)
        }
    }

    private var started = false
    private var foreground: Boolean? = null
    private var pendingFor: AuthIdentityFence? = null
    private val adapters = mutableListOf<AlertLifecycleAdapter>()

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
        postToMain { dispatch { (it as? AlertFamilyAdapter)?.onBankPush(id, isRepeat) } }
    }

    private fun deliverSource(event: SourceAlertDelivery, publish: ((() -> Boolean) -> Unit)?) {
        val use = captureSourceUse() ?: return
        val deliver: () -> Unit = action@{
            try {
                val owner = adapters.filterIsInstance<SourceAlertSettingsOwner>().singleOrNull() ?: return@action
                if (publish != null && foreground != true) return@action
                if (!owner.admitsDelivery(use)) return@action
                owner.onSourceDelivery(event, use)
                if (publish != null && owner.admitsDelivery(use)) publish { foreground == true && owner.admitsDelivery(use) }
            } catch (failure: Exception) {
                reportFailure()
            }
        }
        if (publish == null) deliver() else postToMain(deliver)
    }

    private fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) {
            foregroundHooks.forEach { hook ->
                try {
                    hook()
                } catch (e: Exception) {
                    reportFailure()
                }
            }
            val pending = pendingFor
            pendingFor = null
            val authoritative = pending != null && liveIdentity() == pending && premium.value.confirmsPremiumFor(pending)
            dispatch { it.onForeground(authoritative) }
        } else {
            dispatch { it.onBackground() }
        }
    }

    private fun dispatch(action: (AlertLifecycleAdapter) -> Unit) {
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
        const val SOURCE_RATE_ALERT = "source_rate_alert"

        private fun acceptSource(
            source: String?, asset: String?, id: Int?, repeat: String?,
            resolve: () -> AlertFamilyRefresherRegistry, publish: ((() -> Boolean) -> Unit)?
        ) {
            if (source == null || asset == null || !SourceAlertRowPolicy.EXCHANGES.admits(source, asset)) return
            try {
                resolve().deliverSource(SourceAlertDelivery(source, asset, id, repeat), publish)
            } catch (failure: Exception) {
                Log.w(TAG, "source alert delivery failed")
            }
        }

        fun deliverSourcePush(
            data: Map<String, String>, resolve: () -> AlertFamilyRefresherRegistry, publish: (() -> Boolean) -> Unit
        ) {
            if (data["type"] != SOURCE_RATE_ALERT) return
            acceptSource(data["source"], data["asset"], data["setting_id"]?.toIntOrNull(), data["is_repeat"], resolve, publish)
        }

        fun consumeSourceTap(extras: AlertTapExtras, gateOpen: Boolean, resolve: () -> AlertFamilyRefresherRegistry): Boolean {
            fun string(key: String): String? = if (extras.kind(key) == AlertTapExtraKind.STRING) extras.readString(key) else null
            val custom = string(AlertTapCodec.CUSTOM_TYPE)
            val server = string(AlertTapCodec.SERVER_TYPE)
            if (custom != SOURCE_RATE_ALERT && server != SOURCE_RATE_ALERT) return false
            try {
                fun agrees(key: String, value: String?) =
                    extras.kind(key) == AlertTapExtraKind.MISSING || value == SOURCE_RATE_ALERT
                if (gateOpen && agrees(AlertTapCodec.CUSTOM_TYPE, custom) && agrees(AlertTapCodec.SERVER_TYPE, server)) {
                    val id = when (extras.kind(AlertTapCodec.CUSTOM_ID)) {
                        AlertTapExtraKind.INT -> extras.readInt(AlertTapCodec.CUSTOM_ID, -1)
                        AlertTapExtraKind.STRING -> extras.readString(AlertTapCodec.CUSTOM_ID)?.toIntOrNull()
                        else -> null
                    }
                    acceptSource(string("source"), string("asset"), id,
                        string("is_repeat"), resolve, null)
                }
            } finally {
                for (key in listOf(AlertTapCodec.CUSTOM_TYPE, AlertTapCodec.SERVER_TYPE, AlertTapCodec.CUSTOM_ID,
                        "is_repeat", "source", "asset")) extras.remove(key)
            }
            return true
        }

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
