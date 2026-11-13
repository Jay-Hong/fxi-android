package com.jay.fxi.ui.alert

import com.jay.fxi.PostS6ApprovedDelta
import com.jay.fxi.S6Krx3bOffWiringContractTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 E5b2b1 structure (s7e5b2b_start_agreed.r1.md D3·D5·D6·D10 and §4-3, errata r2 E5–E7):
 * - H: the helper asks and decides only. It names no lifecycle observer, registration, token, foreground entry or family;
 *   it writes the request record and never reads it; only it names the environment seam and no production source provides
 *   one; it declares the remembered helper once and only the settings screen calls it (b2 adds the holder).
 * - S: the settings screen calls the helper once, shows the row only on needsAction, runs it on a click, between the
 *   profile card and the general section, and names nothing of the OS, the monitor or the seam.
 * Each lock has a mutation it is shown to catch, beside the unaltered tree that it accepts.
 */
class S7E5B2B1StructureTest {

    private companion object {
        val BASE = S6Krx3bOffWiringContractTest.BASE
        val HELPER = BASE + "ui/alert/NotificationPermissionAction.kt"
        val SETTINGS = BASE + "ui/settings/SettingsScreen.kt"
        val VIEW_MODEL = BASE + "ui/viewmodel/AlertViewModel.kt"
        const val MARKER = "has_requested_notification_permission"

        /** Every Kotlin production source of the scanned roots, comment-free with every literal kept; Java as written. */
        val code: Map<String, String> by lazy {
            S6Krx3bOffWiringContractTest.ROOTS.flatMap { root ->
                File(root).walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
            }.associate { f ->
                val text = f.readText()
                f.invariantSeparatorsPath to if (f.extension == "kt") PostS6ApprovedDelta.codeAndStrings(text) else text
            }
        }

        fun squash(text: String): String = Regex("""\s+""").replace(text, " ").trim()
        fun count(text: String, pattern: String): Int = Regex(pattern).findAll(text).count()
        fun files(code: Map<String, String>, pattern: String): Set<String> = Regex(pattern).let { r -> code.filterValues { r.containsMatchIn(it) }.keys }

        val HELPER_FORBIDDEN = listOf(
            "lifecycle" to """\bProcessLifecycleOwner\b|\baddObserver\b|\bremoveObserver\b|\b\w*LifecycleObserver\b|\bLifecycle(?:Resume|Start|Event)Effect\b|\brepeatOnLifecycle\b|\bLocalLifecycleOwner\b|\bOnLifecycleEvent\b""",
            "registration" to """\bPushNotificationManager\b|\bPushRegistrationCoordinator\b|\bPushRegistrationLedger\b|\bApiPushDeviceServer\b|\bregisterIfNeeded\b|\bshouldRegisterForPush\b|\breconcileRegistration\b""",
            "token" to """\bFirebaseMessaging\w*|\bgetToken\b|\bonNewToken\b""",
            "foreground" to """\bonForeground\b|\bAlertFamilyRefresherRegistry\b""",
            "marker-read" to """\bgetBoolean\b|\bgetAll\b|\bgetString\b|\bcontains\s*\(""",
            "family" to """\bBankAlertSettingsOwner\b|\bAlertFamilyAdapter\b|\bAlertViewModel\b|\bBankAlertSection\w*""")

        /** The only record write: the agreed key, true, on the v1 store (any receiver). */
        val MARKER_WRITE = squash(""".getSharedPreferences("alert_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("$MARKER", true).apply()""")
        const val DECLARATION = "@Composable internal fun rememberNotificationPermissionAction(): NotificationPermissionAction {"
        val CALL = """\brememberNotificationPermissionAction\s*\("""
        val LOCAL = """\bLocalNotificationPermissionEnvironment\b"""

        /** G01: production resolves nothing before the app-owned gate, then wires the live singletons exactly. */
        val PRODUCTION_ENVIRONMENT = squash("""private fun productionEnvironmentOrNull(context: Context): NotificationPermissionEnvironment? {
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
        }""")
        /** G01: a null seam is production; a missing environment or Activity is inert. */
        val ENVIRONMENT_CHOICE = squash("""val provided = LocalNotificationPermissionEnvironment.current
            val context = LocalContext.current
            val env = remember(provided, context) { provided ?: productionEnvironmentOrNull(context) }
            val activity = remember(context) { context.componentActivity() }
            if (env == null || activity == null) return NotificationPermissionAction.INERT""")
        /** G02: every premium publication reaches the action, and both flows recompose the projection. */
        val PUBLICATIONS = squash("""LaunchedEffect(action) { env.premium.collect { action.onPremium(it) } }""")
        val RECOMPOSITION = squash("""env.premium.collectAsState().value
            env.displayed.collectAsState().value""")

        fun hVerdicts(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            val helper = code[HELPER] ?: return listOf("H-missing")
            HELPER_FORBIDDEN.filter { (_, p) -> Regex(p).containsMatchIn(helper) }.forEach { (tag, _) -> v += "H-name $tag" }
            if (count(helper, Regex.escape(MARKER)) != 1 || !squash(helper).contains(MARKER_WRITE)) v += "H-marker"
            val markerFiles = files(code, Regex.escape(MARKER))
            if (markerFiles != setOf(VIEW_MODEL, HELPER)) v += "H-marker-reach ${markerFiles.sorted()}"
            val localFiles = files(code, LOCAL)
            if (localFiles != setOf(HELPER)) v += "H-local-reach ${localFiles.sorted()}"
            if (code.values.any { Regex("""\bprovides\b|\bprovidesDefault\b""").containsMatchIn(it) && Regex(LOCAL).containsMatchIn(it) }) v += "H-local-provide"
            if (count(squash(helper), Regex.escape(DECLARATION)) != 1 || count(helper, """\bfun\s+rememberNotificationPermissionAction\b""") != 1) v += "H-declare"
            val callers = files(code, CALL) - HELPER
            if (callers != setOf(SETTINGS)) v += "H-callers ${callers.sorted()}"
            if (count(helper, CALL) != count(helper, """\bfun\s+rememberNotificationPermissionAction\b""")) v += "H-self-call"
            val flat = squash(helper)
            if (count(flat, Regex.escape(PRODUCTION_ENVIRONMENT)) != 1 || count(helper, """\bEntryPointAccessors\s*\.""") != 1) v += "H-production"
            if (count(flat, Regex.escape(ENVIRONMENT_CHOICE)) != 1) v += "H-choice"
            if (count(flat, Regex.escape(PUBLICATIONS)) != 1 || count(flat, Regex.escape(RECOMPOSITION)) != 1 || count(helper, """\bonPremium\s*\(""") != 2) v += "H-publications"
            if (count(helper, """\bgetSharedPreferences\s*\(""") != 1 || count(helper, """\.edit\s*\(\s*\)""") != 1 || count(helper, """\.put(?:Boolean|String|StringSet|Int|Long|Float)\s*\(""") != 1) v += "H-store"
            val accessors = files(code, """\bvalueFor\b""")
            if (accessors != setOf(HELPER) || count(helper, """\bvalueFor\b""") != 1) v += "H-accessor ${accessors.sorted()}"
            if (count(helper, """\bnotificationLaunchOutcome\s*\{""") != 2 || count(helper, """\bnotificationSettingsIntent\s*\(""") != 2) v += "H-port"
            return v
        }

        val SETTINGS_FORBIDDEN = listOf(
            "os" to """(?i)POST_NOTIFICATIONS|\bActivityResult\w*|\brememberLauncherForActivityResult\b|\bACTION_\w*SETTINGS\b|\bareNotificationsEnabled\b|\bshouldShowRequestPermissionRationale\b|\breadNotificationCapability\b""",
            "monitor" to """\bNotificationCapabilityMonitor\b|\bonRequestResult\b|\bonAlreadyGranted\b|\bNotificationCapability\b""",
            "push" to """\bPushNotificationManager\b|\bregisterIfNeeded\b|\bshouldRegisterForPush\b|\breconcileRegistration\b""",
            "seam" to """\bNotificationPermissionEnvironment\b|\bLocalNotificationPermissionEnvironment\b|\bNotificationPermissionLane\b|\bNotificationPermissionOs\b|\bNotificationPermissionDialog\b""",
            "storage" to """\balert_prefs\b|\bhas_requested_notification_permission\b""")

        /** The row's use of the helper, squashed: one remembered action, shown on needsAction, run on a click. */
        val ROW_BLOCK = squash("""val notificationAction = rememberNotificationPermissionAction()
            if (notificationAction.needsAction) {
                NotificationSettingsRow(onClick = { notificationAction.run() })
            }""")
        const val PROFILE = "ProfileCard(userInfo = userInfo, onClick = onNavigateProfile) }"
        const val GENERAL = "SettingsSection { SettingsRow( icon = Icons.Filled.Info,"
        const val SEAM = "@Composable internal fun SettingsMainContent("

        fun sVerdicts(code: Map<String, String>): List<String> {
            val v = mutableListOf<String>()
            val settings = code[SETTINGS] ?: return listOf("S-missing")
            SETTINGS_FORBIDDEN.filter { (_, p) -> Regex(p).containsMatchIn(settings) }.forEach { (tag, _) -> v += "S-name $tag" }
            val flat = squash(settings)
            if (count(settings, CALL) != 1) v += "S-calls"
            if (count(flat, Regex.escape(ROW_BLOCK)) != 1) v += "S-row"
            if (count(flat, Regex.escape(SEAM)) != 1) v += "S-seam"
            val main = flat.indexOf(SEAM)
            val profile = flat.indexOf(PROFILE, main)
            val row = flat.indexOf(ROW_BLOCK, main)
            val general = flat.indexOf(GENERAL, main)
            if (main < 0 || profile < 0 || row < 0 || general < 0 || !(profile < row && row < general)) v += "S-place"
            val members = Regex("""\bnotificationAction\s*\.\s*(\w+)""").findAll(settings).map { it.groupValues[1] }.toSet()
            if (members != setOf("needsAction", "run")) v += "S-members $members"
            return v
        }

        fun appended(path: String, extra: String) = code + (path to (code.getValue(path) + "\n" + extra))
        fun edited(path: String, literal: String, replacement: String): Map<String, String> {
            val text = code.getValue(path)
            assertTrue("premise: <$literal> is in $path", text.contains(literal))
            return code + (path to text.replaceFirst(literal, replacement))
        }

        fun assertCaught(label: String, tag: String, verdicts: List<String>) =
            assertTrue("$label: expected $tag in $verdicts", verdicts.any { it.startsWith(tag) })
    }

    @Test fun `S7E5B2B1_S01 the helper asks and decides only writes its record never reads it and owns its seam`() {
        assertEquals(emptyList<String>(), hVerdicts(code))
    }

    @Test fun `S7E5B2B1_S02 the helper lock catches an observer a registration a token a foreground entry a record read and a widened seam`() {
        for ((label, tree, tag) in listOf(
            Triple("a lifecycle observer", appended(HELPER, "private fun s(o: androidx.lifecycle.LifecycleOwner, x: androidx.lifecycle.LifecycleEventObserver) = o.lifecycle.addObserver(x)"), "H-name lifecycle"),
            Triple("a resume effect", appended(HELPER, "@Composable private fun s() = androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { onPauseOrDispose { } }"), "H-name lifecycle"),
            Triple("a direct register", appended(HELPER, "private suspend fun s(m: com.jay.fxi.service.PushNotificationManager) = m.registerIfNeeded()"), "H-name registration"),
            Triple("a token read", appended(HELPER, "private fun s() = com.google.firebase.messaging.FirebaseMessaging.getInstance()"), "H-name token"),
            Triple("a foreground entry", appended(HELPER, "private fun s(m: NotificationCapabilityMonitor) = m.onForeground()"), "H-name foreground"),
            Triple("a record read", appended(HELPER, "private fun s(c: Context) = c.getSharedPreferences(\"alert_prefs\", Context.MODE_PRIVATE).getBoolean(\"x\", false)"), "H-name marker-read"),
            Triple("a second record key", appended(HELPER, "private const val S = \"$MARKER\""), "H-marker"),
            Triple("a false record", edited(HELPER, ".putBoolean(\"$MARKER\", true)", ".putBoolean(\"$MARKER\", false)"), "H-marker"),
            Triple("another store names the key", appended(SETTINGS, "private const val S = \"$MARKER\""), "H-marker-reach"),
            Triple("another file names the seam", appended(SETTINGS, "private val s = LocalNotificationPermissionEnvironment"), "H-local-reach"),
            Triple("a production provider", appended(HELPER, "@Composable private fun s(e: NotificationPermissionEnvironment, c: @Composable () -> Unit) = androidx.compose.runtime.CompositionLocalProvider(LocalNotificationPermissionEnvironment provides e, content = c)"), "H-local-provide"),
            Triple("a parameter on the helper", edited(HELPER, "internal fun rememberNotificationPermissionAction(): NotificationPermissionAction {", "internal fun rememberNotificationPermissionAction(x: Int = 0): NotificationPermissionAction {"), "H-declare"),
            Triple("another caller", appended(BASE + "ui/alert/AlertSection.kt", "@Composable private fun s() = rememberNotificationPermissionAction()"), "H-callers"),
            Triple("a family owner", appended(HELPER, "private val s: com.jay.fxi.data.alerts.BankAlertSettingsOwner? = null"), "H-name family"),
            Triple("the graph resolved before the gate", edited(HELPER,
                "    if (!shouldStartAppOwnedServices(ReleaseAdmission.isOpen, BuildConfig.BENCHMARK_NO_DATA_MODE)) return null\n    val entry = EntryPointAccessors.fromApplication(context.applicationContext, NotificationPermissionEntryPoint::class.java)\n",
                "    val entry = EntryPointAccessors.fromApplication(context.applicationContext, NotificationPermissionEntryPoint::class.java)\n    if (!shouldStartAppOwnedServices(ReleaseAdmission.isOpen, BuildConfig.BENCHMARK_NO_DATA_MODE)) return null\n"), "H-production"),
            Triple("a release-only gate", edited(HELPER, "admitted = { shouldStartAppOwnedServices(ReleaseAdmission.isOpen, BuildConfig.BENCHMARK_NO_DATA_MODE) }", "admitted = { ReleaseAdmission.isOpen }"), "H-production"),
            Triple("an inert production", edited(HELPER, "{ provided ?: productionEnvironmentOrNull(context) }", "{ provided }"), "H-choice"),
            Triple("publications not passed on", edited(HELPER, "LaunchedEffect(action) { env.premium.collect { action.onPremium(it) } }", "LaunchedEffect(action) { env.premium.collect { } }"), "H-publications"),
            Triple("a second store write", appended(HELPER, "private fun s(c: Context) = c.getSharedPreferences(\"alert_prefs\", Context.MODE_PRIVATE).edit().putBoolean(\"asked_v2\", true).apply()"), "H-store"),
            Triple("the accessor used in production", appended(SETTINGS, "private fun s(a: androidx.activity.ComponentActivity) = com.jay.fxi.ui.alert.NotificationPermissionLanes.valueFor(a)"), "H-accessor"),
            Triple("an intent built outside the builder", edited(HELPER, "val intent = notificationSettingsIntent(target, appContext.packageName)", "val intent = Intent(Settings.ACTION_SETTINGS)"), "H-port"))) {
            assertCaught(label, tag, hVerdicts(tree))
        }
    }

    @Test fun `S7E5B2B1_S03 the settings row calls the helper once shows on needsAction runs on a click and sits after the profile`() {
        assertEquals(emptyList<String>(), sVerdicts(code))
    }

    @Test fun `S7E5B2B1_S04 the settings lock catches OS monitor push seam and storage names a second call a moved or ungated row and other members`() {
        for ((label, tree, tag) in listOf(
            Triple("an OS request", appended(SETTINGS, "private val s = android.Manifest.permission.POST_NOTIFICATIONS"), "S-name os"),
            Triple("the monitor", appended(SETTINGS, "private val s: com.jay.fxi.service.NotificationCapabilityMonitor? = null"), "S-name monitor"),
            Triple("a capability read", appended(SETTINGS, "private fun s(c: Context) = com.jay.fxi.service.readNotificationCapability(c)"), "S-name os"),
            Triple("the push facade", appended(SETTINGS, "private val s: com.jay.fxi.service.PushNotificationManager? = null"), "S-name push"),
            Triple("the seam", appended(SETTINGS, "private val s = com.jay.fxi.ui.alert.LocalNotificationPermissionEnvironment"), "S-name seam"),
            Triple("the store", appended(SETTINGS, "private const val S = \"alert_prefs\""), "S-name storage"),
            Triple("a second call", appended(SETTINGS, "@Composable private fun s() = rememberNotificationPermissionAction()"), "S-calls"),
            Triple("an ungated row", edited(SETTINGS, "if (notificationAction.needsAction) {", "if (true) {"), "S-row"),
            Triple("a run without a click", edited(SETTINGS, "NotificationSettingsRow(onClick = { notificationAction.run() })", "NotificationSettingsRow(onClick = {}).also { notificationAction.run() }"), "S-row"),
            Triple("a private screen", edited(SETTINGS, "internal fun SettingsMainContent(", "private fun SettingsMainContent("), "S-seam"),
            Triple("another member", appended(SETTINGS, "@Composable private fun s() { val notificationAction = NotificationPermissionAction.INERT; notificationAction.dialog }"), "S-members"))) {
            assertCaught(label, tag, sVerdicts(tree))
        }
        val flat = squash(code.getValue(SETTINGS))
        val profileBlock = "if (userInfo != null) { $PROFILE"
        assertTrue("premise: the profile block and the row are in the screen", flat.contains(profileBlock) && flat.contains(ROW_BLOCK))
        val moved = flat.replaceFirst("$ROW_BLOCK ", "").replaceFirst(profileBlock, "$ROW_BLOCK $profileBlock")
        assertCaught("a row moved above the profile", "S-place", sVerdicts(code + (SETTINGS to moved)))
        val after = flat.replaceFirst("$ROW_BLOCK ", "").replaceFirst(GENERAL, "$GENERAL $ROW_BLOCK")
        assertCaught("a row moved into the general section", "S-place", sVerdicts(code + (SETTINGS to after)))
    }
}
