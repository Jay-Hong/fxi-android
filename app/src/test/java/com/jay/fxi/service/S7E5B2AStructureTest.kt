package com.jay.fxi.service

import com.jay.fxi.PostS6ApprovedDelta
import com.jay.fxi.S6Krx3bOffWiringContractTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 E5b2a structure (s7e5b2_api_agreed.r1.md §2, R03, R07a): who may drive and what may be inside the new pieces.
 * - M: the monitor names no lifecycle observer, no token or registration machinery, and reaches the push facade only as
 *   `manager::reconcileRegistration` in its one `@Inject` delegation; the monitor type is named by the registry and itself.
 * - R: the reconciler is pure — no Android, Firebase or coordinator name — and the manager's token, rehydrate and
 *   reconcile paths go through it; the coordinator is asked to register only from the reconciler's register lambda.
 * - G: the manager's eligibility passes a fresh capability read.
 * Each lock has a mutation it is shown to catch, beside a control that it accepts.
 */
class S7E5B2AStructureTest {

    private companion object {
        val BASE = S6Krx3bOffWiringContractTest.BASE
        val MONITOR = BASE + "service/NotificationCapabilityMonitor.kt"
        val RECONCILER = BASE + "service/PushIntentReconciler.kt"
        val CAPABILITY = BASE + "service/NotificationCapability.kt"
        val MANAGER = BASE + "service/PushNotificationManager.kt"
        val REGISTRY = BASE + "data/alerts/AlertFamilyRefresherRegistry.kt"

        /** Every Kotlin production source of the scanned roots, comment-free with every literal kept; Java as written. */
        val code: Map<String, String> by lazy {
            S6Krx3bOffWiringContractTest.ROOTS.flatMap { root ->
                File(root).walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
            }.associate { f ->
                val text = f.readText()
                f.invariantSeparatorsPath to if (f.extension == "kt") PostS6ApprovedDelta.codeAndStrings(text) else text
            }
        }

        fun count(text: String, pattern: String): Int = Regex(pattern).findAll(text).count()
        fun files(code: Map<String, String>, pattern: String): Set<String> = Regex(pattern).let { r -> code.filterValues { r.containsMatchIn(it) }.keys }

        val MONITOR_FORBIDDEN = listOf(
            "lifecycle" to """\bProcessLifecycleOwner\b|\baddObserver\b|\bLifecycle\w*Observer\b|\brepeatOnLifecycle\b|\bLocalLifecycleOwner\b""",
            "token" to """\bFirebaseMessaging\w*|\bgetToken\b|\bonNewToken\b""",
            "registration" to """\bPushRegistrationCoordinator\b|\bPushRegistrationLedger\b|\bApiPushDeviceServer\b|\bregisterIfNeeded\b|\bshouldRegisterForPush\b""",
            "family" to """\bAlertFamilyAdapter\b|\bAlertFamilyRefresherRegistry\b|\bBankAlertSettingsOwner\b""",
            "screen" to """@Composable\b|\bstartActivit(?:y|ies)\w*|\bIntent\b|\bActivityResult\w*""")

        val RECONCILER_FORBIDDEN = listOf(
            "android" to """\bandroid\.|\bandroidx\.|\bContext\b|\bLog\b""",
            "firebase" to """\bfirebase\b|\bFirebase\w*""",
            "coordinator" to """\bPushRegistrationCoordinator\b|\bPushRegistrationLedger\b|\bPushNotificationManager\b""")

        fun mVerdicts(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            val monitor = code[MONITOR] ?: return listOf("M-missing")
            MONITOR_FORBIDDEN.filter { (_, p) -> Regex(p).containsMatchIn(monitor) }.forEach { (tag, _) -> v += "M-name $tag" }
            if (count(monitor, """\bPushNotificationManager\b""") != 1 ||
                count(monitor, """\bmanager\s*::\s*reconcileRegistration\b""") != 1 ||
                count(monitor, """\bmanager\b""") != 2) v += "M-facade"
            val named = files(code, """\bNotificationCapabilityMonitor\b""")
            if (named != setOf(MONITOR, REGISTRY)) v += "M-reach ${named.sorted()}"
            return v
        }

        fun rVerdicts(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            val reconciler = code[RECONCILER] ?: return listOf("R-missing")
            RECONCILER_FORBIDDEN.filter { (_, p) -> Regex(p).containsMatchIn(reconciler) }.forEach { (tag, _) -> v += "R-name $tag" }
            val manager = code[MANAGER] ?: return v + "R-manager-missing"
            if (count(manager, """\bcoordinator\s*\.\s*register\s*\(""") != 2) v += "R-register-calls"
            if (count(manager, """register\s*=\s*\{\s*owner\s*,\s*token\s*->\s*coordinator\s*\.\s*register\s*\(\s*owner\s*,\s*knownToken\s*=\s*token\s*\)\s*\}""") != 1) v += "R-register-lambda"
            if (count(manager, """\breconciler\s*\.\s*reconcile\s*\(""") != 2) v += "R-reconcile-calls"
            if (count(manager, """\bfun\s+rehydratePushTokenIfNeeded\s*\(\s*\)\s*\{\s*reconcileRegistration\s*\(\s*captureOwnerOrNull\s*\(\s*\)\s*\)\s*\}""") != 1) v += "R-rehydrate"
            if (count(manager, """\bshouldRegisterForPush\s*=\s*true\b""") != 0) v += "R-direct-arm"
            return v
        }

        /** The manager's reconciler and the platform read, squashed: each lambda is part of the agreed wiring. */
        val RECONCILER_FORM = squash("""private val reconciler = PushIntentReconciler(
            capability = { readNotificationCapability(context) },
            currentOwner = ::captureOwnerOrNull,
            premium = { premiumAccessCoordinator.state.value },
            held = { coordinator.isHeld },
            readIntent = { shouldRegisterForPush },
            writeIntent = { shouldRegisterForPush = it },
            register = { owner, token -> coordinator.register(owner, knownToken = token) }
        )""")
        val READ_FORM = squash("""internal fun readNotificationCapability(context: Context): NotificationCapability {
            val sdkInt = Build.VERSION.SDK_INT
            val runtimeGranted = sdkInt < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val appEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            val channelImportance = if (sdkInt >= Build.VERSION_CODES.O) {
                context.getSystemService(NotificationManager::class.java)
                    ?.getNotificationChannel(FXiMessagingService.CHANNEL_RATE_ALERTS)
                    ?.importance
            } else {
                null
            }
            return notificationCapabilityOf(sdkInt, runtimeGranted, appEnabled, channelImportance)
        }""")
        fun squash(text: String) = text.replace(Regex("""\s+"""), " ").trim()

        /** Gaps G07–G11: the monitor's production dependencies and the manager's entry bodies, squashed. */
        val MONITOR_INJECT_FORM = squash("""@Inject constructor(
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
        )""")
        val ENTRY_FORMS = listOf(
            "onNewToken" to squash("""suspend fun onNewToken(token: String) {
                if (!ReleaseAdmission.isOpen) return
                val owner = captureOwnerOrNull() ?: return
                Log.d(TAG, "Registration on token rotation: ${'$'}{reconciler.reconcile(owner, knownToken = token)}")
            }"""),
            "reconcileRegistration" to squash("""suspend fun reconcileRegistration(owner: AuthIdentityFence?) {
                if (!ReleaseAdmission.isOpen) return
                Log.d(TAG, "Registration on reconcile: ${'$'}{reconciler.reconcile(owner)}")
            }"""),
            "mayRegister" to squash("""private fun mayRegister(owner: AuthIdentityFence?): Boolean = pushRegistrationAllowed(
                owner, premiumAccessCoordinator.state.value, shouldRegisterForPush,
                capabilityGranted = readNotificationCapability(context) == NotificationCapability.GRANTED
            )"""),
            "setter" to squash("""var shouldRegisterForPush: Boolean
                get() = ReleaseAdmission.isOpen && prefs.getBoolean(KEY_SHOULD_REGISTER, false)
                set(value) {
                    if (!ReleaseAdmission.isOpen) return
                    prefs.edit().putBoolean(KEY_SHOULD_REGISTER, value).apply()
                }"""))

        fun eVerdicts(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            if (squash(code[MONITOR].orEmpty()).split(MONITOR_INJECT_FORM).size != 2) v += "E-monitor-inject"
            val manager = squash(code[MANAGER].orEmpty())
            for ((tag, form) in ENTRY_FORMS) if (manager.split(form).size != 2) v += "E-$tag"
            for (name in listOf("onNewToken", "reconcileRegistration", "mayRegister"))
                if (count(code[MANAGER].orEmpty(), """\bfun\s+$name\s*\(""") != 1) v += "E-$name-count"
            return v
        }

        fun wVerdicts(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            if (squash(code[MANAGER].orEmpty()).split(RECONCILER_FORM).size != 2) v += "W-reconciler"
            if (squash(code[CAPABILITY].orEmpty()).split(READ_FORM).size != 2) v += "W-read"
            return v
        }

        fun gVerdicts(code: Map<String, String>): List<String> {
            val manager = code[MANAGER] ?: return listOf("G-missing")
            val v = mutableListOf<String>()
            if (count(manager, """capabilityGranted\s*=\s*readNotificationCapability\s*\(\s*context\s*\)\s*==\s*NotificationCapability\s*\.\s*GRANTED""") != 1) v += "G-capability"
            if (count(manager, """\bhasNotificationPermission\b""") != 0) v += "G-second-reader"
            if (code[CAPABILITY] == null) v += "G-capability-file"
            return v
        }

        fun appended(path: String, extra: String) = code + (path to (code.getValue(path) + "\n" + extra))
        fun edited(path: String, pattern: String, replacement: String): Map<String, String> {
            val text = code.getValue(path)
            val regex = Regex(pattern)
            assertTrue("premise: $pattern is in $path", regex.containsMatchIn(text))
            return code + (path to regex.replaceFirst(text, replacement))
        }

        fun assertCaught(label: String, tag: String, verdicts: List<String>) =
            assertTrue("$label: expected $tag in $verdicts", verdicts.any { it.startsWith(tag) })
    }

    @Test fun `S7E5B2A_S01 the monitor reaches only the push facade's reconcile and is driven by the registry alone`() {
        assertEquals(emptyList<String>(), mVerdicts(code))
    }

    @Test fun `S7E5B2A_S02 the monitor lock catches an observer a token a registration a family a screen another facade use and another driver`() {
        for ((label, tree, tag) in listOf(
            Triple("an observer", appended(MONITOR, "fun s(l: androidx.lifecycle.Lifecycle, o: androidx.lifecycle.LifecycleEventObserver) = l.addObserver(o)"), "M-name lifecycle"),
            Triple("a token read", appended(MONITOR, "fun s() = com.google.firebase.messaging.FirebaseMessaging.getInstance()"), "M-name token"),
            Triple("a direct register", appended(MONITOR, "suspend fun s(m: PushNotificationManager) = m.registerIfNeeded()"), "M-name registration"),
            Triple("the intent flag", appended(MONITOR, "fun s(m: PushNotificationManager) { m.shouldRegisterForPush = true }"), "M-name registration"),
            Triple("a family adapter", appended(MONITOR, "private val s: com.jay.fxi.data.alerts.AlertFamilyAdapter? = null"), "M-name family"),
            Triple("a screen", appended(MONITOR, "fun s(c: android.content.Context) = c.startActivity(android.content.Intent())"), "M-name screen"),
            Triple("another facade member", edited(MONITOR, """manager::reconcileRegistration""", "{ manager.rehydratePushTokenIfNeeded() }"), "M-facade"),
            Triple("another driver", appended(MANAGER, "private val s: NotificationCapabilityMonitor? = null"), "M-reach"))) {
            assertCaught(label, tag, mVerdicts(tree))
        }
    }

    @Test fun `S7E5B2A_S03 the reconciler is pure and the manager registers only through it`() {
        assertEquals(emptyList<String>(), rVerdicts(code))
    }

    @Test fun `S7E5B2A_S04 the reconciler lock catches a platform name a bypassing register a direct arm and a rehydrate that skips it`() {
        for ((label, tree, tag) in listOf(
            Triple("a platform log", appended(RECONCILER, "private fun s() = android.util.Log.d(\"t\", \"m\")"), "R-name android"),
            Triple("the coordinator itself", appended(RECONCILER, "private val s: PushRegistrationCoordinator? = null"), "R-name coordinator"),
            Triple("a register outside the reconciler", appended(MANAGER, "suspend fun s(o: AuthIdentityFence) = coordinator.register(o)"), "R-register-calls"),
            Triple("a direct arm", appended(MANAGER, "fun s() { shouldRegisterForPush = true }"), "R-direct-arm"),
            Triple("a rehydrate that arms by itself", edited(MANAGER, """reconcileRegistration\s*\(\s*captureOwnerOrNull\s*\(\s*\)\s*\)\s*\}""",
                "shouldRegisterForPush = true; registerIfNeeded() }"), "R-rehydrate"))) {
            assertCaught(label, tag, rVerdicts(tree))
        }
    }

    @Test fun `S7E5B2A_S05 eligibility reads the capability fresh and the old reader is gone`() {
        assertEquals(emptyList<String>(), gVerdicts(code))
        assertCaught("a constant capability", "G-capability",
            gVerdicts(edited(MANAGER, """capabilityGranted\s*=\s*readNotificationCapability\s*\(\s*context\s*\)\s*==\s*NotificationCapability\s*\.\s*GRANTED""", "capabilityGranted = true")))
        assertCaught("a second reader", "G-second-reader", gVerdicts(appended(MANAGER, "private fun hasNotificationPermission() = true")))
    }

    @Test fun `S7E5B2A_S06 the manager wires the reconciler and the platform read exactly as agreed`() {
        assertEquals(emptyList<String>(), wVerdicts(code))
        for ((label, tree, tag) in listOf(
            Triple("a hold never seen", edited(MANAGER, """held\s*=\s*\{\s*coordinator\s*\.\s*isHeld\s*\}""", "held = { false }"), "W-reconciler"),
            Triple("no current owner", edited(MANAGER, """currentOwner\s*=\s*::captureOwnerOrNull""", "currentOwner = { null }"), "W-reconciler"),
            Triple("a cached capability", edited(MANAGER, """capability\s*=\s*\{\s*readNotificationCapability\s*\(\s*context\s*\)\s*\}""", "capability = { NotificationCapability.GRANTED }"), "W-reconciler"),
            Triple("the runtime boundary moved", edited(CAPABILITY, """sdkInt\s*<\s*Build\.VERSION_CODES\.TIRAMISU""", "sdkInt <= Build.VERSION_CODES.TIRAMISU"), "W-read"),
            Triple("another channel", edited(CAPABILITY, """FXiMessagingService\.CHANNEL_RATE_ALERTS""", "\"other\""), "W-read"))) {
            assertCaught(label, tag, wVerdicts(tree))
        }
    }

    @Test fun `S7E5B2A_GAP07 the monitor's production dependencies and the manager's entry bodies are the agreed ones`() {
        assertEquals(emptyList<String>(), eVerdicts(code))
    }

    @Test fun `S7E5B2A_GAP08 the entry lock catches another scope a fixed premium a dropped no-data gate a hidden branch an early return a short circuit a destructive setter and a missing guard`() {
        for ((label, tree, tag) in listOf(
            Triple("an IO scope", edited(MONITOR, """CoroutineScope\(SupervisorJob\(\) \+ Dispatchers\.Main\)""", "CoroutineScope(Job() + Dispatchers.IO)"), "E-monitor-inject"),
            Triple("a fixed premium", edited(MONITOR, """\bcoordinator\.state,""", "kotlinx.coroutines.flow.MutableStateFlow(com.jay.fxi.data.entitlements.OwnedPremiumAccess()),"), "E-monitor-inject"),
            Triple("no no-data gate", edited(MONITOR, """shouldStartAppOwnedServices\(ReleaseAdmission\.isOpen, BuildConfig\.BENCHMARK_NO_DATA_MODE\)""", "ReleaseAdmission.isOpen"), "E-monitor-inject"),
            Triple("a token branch", edited(MANAGER, """Log\.d\(TAG, "Registration on token rotation""", "if (token.isBlank()) Log.d(TAG, \"Registration on token rotation"), "E-onNewToken"),
            Triple("an early return", edited(MANAGER, """suspend fun reconcileRegistration\(owner: AuthIdentityFence\?\) \{""", "suspend fun reconcileRegistration(owner: AuthIdentityFence?) { if (owner != null) return;"), "E-reconcileRegistration"),
            Triple("a missing guard", edited(MANAGER, """(suspend fun reconcileRegistration\(owner: AuthIdentityFence\?\) \{\s*)if \(!ReleaseAdmission\.isOpen\) return""", "$1"), "E-reconcileRegistration"),
            Triple("a short circuit", edited(MANAGER, """Boolean = pushRegistrationAllowed\(""", "Boolean = true || pushRegistrationAllowed("), "E-mayRegister"),
            Triple("a destructive setter", edited(MANAGER, """prefs\.edit\(\)\.putBoolean\(KEY_SHOULD_REGISTER, value\)\.apply\(\)""",
                "prefs.edit().putBoolean(KEY_SHOULD_REGISTER, value).apply(); if (!value) FirebaseMessaging.getInstance().deleteToken()"), "E-setter"))) {
            assertCaught(label, tag, eVerdicts(tree))
        }
    }
}
