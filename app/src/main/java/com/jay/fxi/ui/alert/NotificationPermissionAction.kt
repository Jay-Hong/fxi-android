package com.jay.fxi.ui.alert

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.app.ActivityCompat
import com.jay.fxi.BuildConfig
import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.confirmsPremiumFor
import com.jay.fxi.service.FXiMessagingService
import com.jay.fxi.service.NotificationCapability
import com.jay.fxi.service.NotificationCapabilityMonitor
import com.jay.fxi.service.readNotificationCapability
import com.jay.fxi.shouldStartAppOwnedServices
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow

internal class NotificationPermissionEnvironment(
    val admitted: () -> Boolean,
    val liveIdentity: () -> AuthIdentityFence?,
    val premium: StateFlow<OwnedPremiumAccess>,
    val displayed: StateFlow<NotificationCapability?>,
    val onRequestResult: (AuthIdentityFence?) -> Boolean,
    val onAlreadyGranted: (AuthIdentityFence) -> Boolean
)

internal val LocalNotificationPermissionEnvironment =
    staticCompositionLocalOf<NotificationPermissionEnvironment?> { null }

internal enum class NotificationSettingsTarget { APP, CHANNEL, DETAILS }
internal enum class NotificationLaunchOutcome { ACCEPTED, UNAVAILABLE, FAILED }

internal interface NotificationPermissionOs {
    fun read(): NotificationCapability
    fun shouldShowRationale(): Boolean
    fun launchRequest(): NotificationLaunchOutcome
    fun launchSettings(target: NotificationSettingsTarget): NotificationLaunchOutcome
    fun markRequested()
}

internal sealed interface NotificationPermissionDialog {
    data object Explain : NotificationPermissionDialog
    data class Denied(val capability: NotificationCapability) : NotificationPermissionDialog
}

/** Main-thread lease. Only the matching token can end it; an unmatched request is a display refresh. */
internal class NotificationPermissionLane(private val orphanResult: () -> Unit) {
    private enum class Pending { REQUEST, SETTINGS }
    private var token: Any? = null
    private var pending: Pending? = null
    private var result: (() -> Unit)? = null
    val busy: Boolean get() = token != null

    internal fun acquire(candidate: Any): Boolean {
        if (busy) return false
        token = candidate
        return true
    }

    internal fun release(candidate: Any) {
        if (token !== candidate) return
        token = null
        pending = null
        result = null
    }

    internal fun awaitRequest(candidate: Any, callback: () -> Unit) {
        if (token !== candidate) return
        pending = Pending.REQUEST
        result = callback
    }

    internal fun awaitSettings(candidate: Any, callback: () -> Unit) {
        if (token !== candidate) return
        pending = Pending.SETTINGS
        result = callback
    }

    fun onRequestResult() {
        if (pending != Pending.REQUEST) {
            permissionValue { orphanResult() }
            return
        }
        val callback = result
        pending = null
        result = null
        callback?.invoke()
    }

    fun onSettingsResult() {
        if (pending != Pending.SETTINGS) return
        val callback = result
        pending = null
        result = null
        callback?.invoke()
    }
}

private inline fun <T> permissionValue(block: () -> T): T? = try {
    block()
} catch (_: Exception) {
    null
}

internal class NotificationPermissionAction(
    private val env: NotificationPermissionEnvironment,
    private val os: NotificationPermissionOs,
    private val lane: NotificationPermissionLane
) {
    private enum class Phase { DECIDING, EXPLAIN, REQUEST, DENIED, SETTINGS }
    private class Attempt(val owner: AuthIdentityFence, var continuation: (() -> Unit)?) {
        val token = Any()
        var phase = Phase.DECIDING
        var invalid = false
        var done = false
    }

    private var current: Attempt? = null
    private var disposed = false
    var dialog: NotificationPermissionDialog? by mutableStateOf(null)
        private set

    val needsAction: Boolean
        get() {
            if (disposed || !env.admitted()) return false
            val shown = env.displayed.value ?: return false
            return shown != NotificationCapability.GRANTED &&
                env.premium.value.confirmsPremiumFor(env.liveIdentity())
        }

    fun run(onGranted: () -> Unit = {}) {
        if (disposed || !env.admitted()) return
        val owner = env.liveIdentity() ?: return
        if (!env.premium.value.confirmsPremiumFor(owner)) return
        val attempt = Attempt(owner, onGranted)
        if (!lane.acquire(attempt.token)) return
        current = attempt
        decide(attempt, explain = true)
    }

    private fun valid(attempt: Attempt): Boolean {
        if (attempt.done || attempt.invalid || disposed || current !== attempt) return false
        if (!env.admitted() || env.liveIdentity() != attempt.owner ||
            !env.premium.value.confirmsPremiumFor(attempt.owner)
        ) {
            attempt.invalid = true
            attempt.continuation = null
            return false
        }
        return true
    }

    private fun finish(attempt: Attempt, granted: Boolean = false) {
        if (attempt.done) return
        val continuation = if (granted && valid(attempt)) attempt.continuation else null
        attempt.done = true
        attempt.continuation = null
        if (current === attempt) {
            current = null
            dialog = null
        }
        lane.release(attempt.token)
        // Release before calling user code, which may start another attempt synchronously.
        continuation?.invoke()
    }

    private fun decide(attempt: Attempt, explain: Boolean) {
        if (!valid(attempt)) return finish(attempt)
        val capability = permissionValue { os.read() } ?: return finish(attempt)
        if (!valid(attempt)) return finish(attempt)
        when (capability) {
            NotificationCapability.GRANTED -> {
                val confirmed = permissionValue { env.onAlreadyGranted(attempt.owner) } == true
                finish(attempt, granted = confirmed)
            }
            NotificationCapability.RUNTIME_MISSING -> {
                if (explain) {
                    val rationale = permissionValue { os.shouldShowRationale() } ?: return finish(attempt)
                    if (!valid(attempt)) return finish(attempt)
                    if (rationale) {
                        attempt.phase = Phase.EXPLAIN
                        dialog = NotificationPermissionDialog.Explain
                        return
                    }
                }
                request(attempt)
            }
            NotificationCapability.APP_DISABLED -> settings(attempt, NotificationSettingsTarget.APP)
            NotificationCapability.CHANNEL_DISABLED -> settings(attempt, NotificationSettingsTarget.CHANNEL)
        }
    }

    private fun request(attempt: Attempt) {
        if (!valid(attempt)) return finish(attempt)
        dialog = null
        attempt.phase = Phase.REQUEST
        val weakAction = WeakReference(this)
        val environment = env
        val sharedLane = lane
        val owner = attempt.owner
        val token = attempt.token
        // The lane keeps no continuation or strong action reference while the OS owns the request.
        lane.awaitRequest(token) {
            val action = weakAction.get()
            if (action != null) {
                action.requestResult(token)
            } else {
                try {
                    permissionValue { environment.onRequestResult(owner) }
                } finally {
                    sharedLane.release(token)
                }
            }
        }
        val outcome = permissionValue { os.launchRequest() }
        if (outcome == NotificationLaunchOutcome.ACCEPTED) {
            // A synchronous result may already have finished this attempt; the accepted launch still counts.
            permissionValue { os.markRequested() }
        } else {
            finish(attempt)
        }
    }

    private fun requestResult(token: Any) {
        val attempt = current?.takeIf { it.token === token && !it.done }
        if (attempt == null) {
            lane.release(token)
            return
        }
        // The monitor performs the fresh grant check for the original owner, even after dispose.
        val confirmed = permissionValue { env.onRequestResult(attempt.owner) } == true
        if (!valid(attempt)) return finish(attempt)
        if (confirmed) return finish(attempt, granted = true)
        val fresh = permissionValue { os.read() } ?: return finish(attempt)
        if (!valid(attempt) || fresh == NotificationCapability.GRANTED) return finish(attempt)
        attempt.phase = Phase.DENIED
        dialog = NotificationPermissionDialog.Denied(fresh)
    }

    private fun settings(attempt: Attempt, target: NotificationSettingsTarget) {
        if (!valid(attempt)) return finish(attempt)
        attempt.continuation = null
        attempt.phase = Phase.SETTINGS
        dialog = null
        val weakAction = WeakReference(this)
        val sharedLane = lane
        val token = attempt.token
        lane.awaitSettings(token) {
            val action = weakAction.get()
            val active = action?.current?.takeIf { it.token === token && !it.done }
            if (action != null && active != null) action.finish(active) else sharedLane.release(token)
        }
        var outcome = permissionValue { os.launchSettings(target) }
        if (outcome == NotificationLaunchOutcome.UNAVAILABLE && !attempt.done && valid(attempt)) {
            outcome = permissionValue { os.launchSettings(NotificationSettingsTarget.DETAILS) }
        }
        if (outcome != NotificationLaunchOutcome.ACCEPTED) finish(attempt)
    }

    fun onExplainContinue() {
        val attempt = current ?: return
        if (dialog != NotificationPermissionDialog.Explain || attempt.phase != Phase.EXPLAIN) return
        dialog = null
        decide(attempt, explain = false)
    }

    fun onDeniedRetry() {
        val attempt = current ?: return
        if (dialog !is NotificationPermissionDialog.Denied || attempt.phase != Phase.DENIED) return
        val retry = valid(attempt)
        finish(attempt)
        if (retry) run()
    }

    fun onDeniedSettings() {
        val attempt = current ?: return
        if (dialog !is NotificationPermissionDialog.Denied || attempt.phase != Phase.DENIED) return
        if (!valid(attempt)) return finish(attempt)
        attempt.continuation = null
        dialog = null
        val fresh = permissionValue { os.read() } ?: return finish(attempt)
        if (!valid(attempt)) return finish(attempt)
        when (fresh) {
            NotificationCapability.GRANTED -> finish(attempt)
            NotificationCapability.RUNTIME_MISSING,
            NotificationCapability.APP_DISABLED -> settings(attempt, NotificationSettingsTarget.APP)
            NotificationCapability.CHANNEL_DISABLED -> settings(attempt, NotificationSettingsTarget.CHANNEL)
        }
    }

    fun onDialogDismiss() {
        val attempt = current ?: return
        if (attempt.phase == Phase.EXPLAIN || attempt.phase == Phase.DENIED) finish(attempt)
    }

    fun onPremium(access: OwnedPremiumAccess) {
        val attempt = current ?: return
        if (!access.confirmsPremiumFor(attempt.owner) || env.liveIdentity() != attempt.owner) {
            attempt.invalid = true
            attempt.continuation = null
            if (attempt.phase == Phase.EXPLAIN || attempt.phase == Phase.DENIED) finish(attempt)
        }
    }

    fun dispose() {
        disposed = true
        val attempt = current ?: return
        attempt.invalid = true
        attempt.continuation = null
        if (attempt.phase != Phase.REQUEST && attempt.phase != Phase.SETTINGS) finish(attempt)
    }

    companion object {
        val INERT = NotificationPermissionAction(
            NotificationPermissionEnvironment(
                admitted = { false },
                liveIdentity = { null },
                premium = MutableStateFlow(OwnedPremiumAccess()),
                displayed = MutableStateFlow(null),
                onRequestResult = { false },
                onAlreadyGranted = { false }
            ),
            object : NotificationPermissionOs {
                override fun read() = NotificationCapability.RUNTIME_MISSING
                override fun shouldShowRationale() = false
                override fun launchRequest() = NotificationLaunchOutcome.FAILED
                override fun launchSettings(target: NotificationSettingsTarget) = NotificationLaunchOutcome.FAILED
                override fun markRequested() = Unit
            },
            NotificationPermissionLane(orphanResult = {})
        )
    }
}

internal object NotificationPermissionTags {
    const val EXPLAIN_DIALOG = "notification-explain-dialog"
    const val EXPLAIN_CONTINUE = "notification-explain-continue"
    const val EXPLAIN_CANCEL = "notification-explain-cancel"
    const val DENIED_DIALOG = "notification-denied-dialog"
    const val DENIED_SETTINGS = "notification-denied-settings"
    const val DENIED_RETRY = "notification-denied-retry"
    const val DENIED_CANCEL = "notification-denied-cancel"
}

@Composable
internal fun NotificationPermissionDialogHost(action: NotificationPermissionAction) {
    when (val dialog = action.dialog) {
        NotificationPermissionDialog.Explain -> AlertDialog(
            modifier = Modifier.testTag(NotificationPermissionTags.EXPLAIN_DIALOG),
            onDismissRequest = action::onDialogDismiss,
            title = { Text("환율 알림 권한") },
            text = { Text("원하는 환율에 도달하면 푸시 알림으로 알려드립니다. 알림을 받으려면 알림 권한을 허용해 주세요.") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag(NotificationPermissionTags.EXPLAIN_CONTINUE),
                    onClick = action::onExplainContinue
                ) { Text("계속") }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag(NotificationPermissionTags.EXPLAIN_CANCEL),
                    onClick = action::onDialogDismiss
                ) { Text("취소") }
            }
        )
        is NotificationPermissionDialog.Denied -> AlertDialog(
            modifier = Modifier.testTag(NotificationPermissionTags.DENIED_DIALOG),
            onDismissRequest = action::onDialogDismiss,
            title = { Text("알림 권한이 꺼져 있습니다") },
            text = {
                Text(
                    if (dialog.capability == NotificationCapability.RUNTIME_MISSING)
                        "알림을 받으려면 알림 권한을 허용해 주세요. 다시 요청해도 시스템 요청 창이 표시되지 않을 수 있습니다. 이 경우 설정에서 허용해 주세요."
                    else "알림을 받으려면 알림 권한을 허용해 주세요."
                )
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag(NotificationPermissionTags.DENIED_SETTINGS),
                    onClick = action::onDeniedSettings
                ) { Text("설정 열기") }
            },
            dismissButton = {
                Column {
                    if (dialog.capability == NotificationCapability.RUNTIME_MISSING) {
                        TextButton(
                            modifier = Modifier.testTag(NotificationPermissionTags.DENIED_RETRY),
                            onClick = action::onDeniedRetry
                        ) { Text("다시 요청") }
                    }
                    TextButton(
                        modifier = Modifier.testTag(NotificationPermissionTags.DENIED_CANCEL),
                        onClick = action::onDialogDismiss
                    ) { Text("취소") }
                }
            }
        )
        null -> Unit
    }
}

internal fun notificationSettingsIntent(target: NotificationSettingsTarget, packageName: String): Intent =
    when (target) {
        NotificationSettingsTarget.APP -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        NotificationSettingsTarget.CHANNEL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, FXiMessagingService.CHANNEL_RATE_ALERTS)
        NotificationSettingsTarget.DETAILS -> Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
    }

internal fun notificationLaunchOutcome(launch: () -> Unit): NotificationLaunchOutcome = try {
    launch()
    NotificationLaunchOutcome.ACCEPTED
} catch (_: ActivityNotFoundException) {
    NotificationLaunchOutcome.UNAVAILABLE
} catch (_: Exception) {
    NotificationLaunchOutcome.FAILED
}

/**
 * Registrations live with the Activity's registry, including when the last helper leaves.
 * Values never keep a registry or launcher: both retain the Activity behind a weak map key.
 */
internal object NotificationPermissionLanes {
    private const val REQUEST_KEY = "fxi-notification-permission"
    private const val SETTINGS_KEY = "fxi-notification-settings"
    private val entries = WeakHashMap<Activity, Entry>()

    internal class Entry(var environment: NotificationPermissionEnvironment) {
        val lane = NotificationPermissionLane(orphanResult = { environment.onRequestResult(null) })

        fun requestLauncher(activity: ComponentActivity): ActivityResultLauncher<String> =
            activity.activityResultRegistry.register(REQUEST_KEY, ActivityResultContracts.RequestPermission()) {
                lane.onRequestResult()
            }

        fun settingsLauncher(activity: ComponentActivity): ActivityResultLauncher<Intent> =
            activity.activityResultRegistry.register(SETTINGS_KEY, ActivityResultContracts.StartActivityForResult()) {
                lane.onSettingsResult()
            }
    }

    internal fun attach(activity: ComponentActivity, env: NotificationPermissionEnvironment): Entry {
        val entry = entries.getOrPut(activity) { Entry(env) }
        entry.environment = env
        // Re-registering a stable key reuses its request code and can deliver a restored pending result.
        entry.requestLauncher(activity)
        entry.settingsLauncher(activity)
        return entry
    }

    internal fun valueFor(activity: Activity): Any? = entries[activity]
}

private class AndroidNotificationPermissionOs(
    private val appContext: Context,
    private val activityRef: WeakReference<ComponentActivity>,
    private val entry: NotificationPermissionLanes.Entry
) : NotificationPermissionOs {
    override fun read(): NotificationCapability = readNotificationCapability(appContext)

    override fun shouldShowRationale(): Boolean {
        val activity = activityRef.get() ?: throw IllegalStateException("Activity unavailable")
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun launchRequest(): NotificationLaunchOutcome = notificationLaunchOutcome {
        val activity = availableActivity()
        entry.requestLauncher(activity).launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun launchSettings(target: NotificationSettingsTarget): NotificationLaunchOutcome = notificationLaunchOutcome {
        val activity = availableActivity()
        val intent = notificationSettingsIntent(target, appContext.packageName)
        entry.settingsLauncher(activity).launch(intent)
    }

    private fun availableActivity(): ComponentActivity =
        activityRef.get()?.takeUnless { it.isDestroyed || it.isFinishing }
            ?: throw ActivityNotFoundException("Activity unavailable")

    override fun markRequested() {
        appContext.getSharedPreferences("alert_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("has_requested_notification_permission", true).apply()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface NotificationPermissionEntryPoint {
    fun notificationCapabilityMonitor(): NotificationCapabilityMonitor
    fun authTokenProvider(): AuthTokenProvider
    fun premiumAccessCoordinator(): PremiumAccessCoordinator
}

private fun productionEnvironmentOrNull(context: Context): NotificationPermissionEnvironment? {
    if (!shouldStartAppOwnedServices(ReleaseAdmission.isOpen, BuildConfig.BENCHMARK_NO_DATA_MODE)) return null
    val entry = EntryPointAccessors.fromApplication(context.applicationContext, NotificationPermissionEntryPoint::class.java)
    val monitor = entry.notificationCapabilityMonitor()
    val tokens = entry.authTokenProvider()
    return NotificationPermissionEnvironment(
        admitted = { shouldStartAppOwnedServices(ReleaseAdmission.isOpen, BuildConfig.BENCHMARK_NO_DATA_MODE) },
        liveIdentity = tokens::currentIdentityFence,
        premium = entry.premiumAccessCoordinator().state,
        displayed = monitor.state,
        onRequestResult = monitor::onRequestResult,
        onAlreadyGranted = monitor::onAlreadyGranted
    )
}

private fun Context.componentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.componentActivity()
    else -> null
}

@Composable internal fun rememberNotificationPermissionAction(): NotificationPermissionAction {
    val provided = LocalNotificationPermissionEnvironment.current
    val context = LocalContext.current
    val env = remember(provided, context) { provided ?: productionEnvironmentOrNull(context) }
    val activity = remember(context) { context.componentActivity() }
    if (env == null || activity == null) return NotificationPermissionAction.INERT

    val entry = remember(activity, env) { NotificationPermissionLanes.attach(activity, env) }
    val os = remember(activity, entry) {
        AndroidNotificationPermissionOs(activity.applicationContext, WeakReference(activity), entry)
    }
    val action = remember(env, os, entry) { NotificationPermissionAction(env, os, entry.lane) }
    env.premium.collectAsState().value
    env.displayed.collectAsState().value
    LaunchedEffect(action) { env.premium.collect { action.onPremium(it) } }
    DisposableEffect(action) {
        onDispose { action.dispose() }
    }
    NotificationPermissionDialogHost(action)
    return action
}
