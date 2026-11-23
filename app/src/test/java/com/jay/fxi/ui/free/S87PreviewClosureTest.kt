package com.jay.fxi.ui.free

import com.jay.fxi.BuildConfig
import java.io.File
import java.security.MessageDigest
import org.junit.Test

/**
 * S8-7 (`R4c/S8/s8_7_contract.codex.r2.md` §4 클로저): what the tether free preview's compiled classes can reach, sealed.
 *
 * The roots are every class compiled out of the four free tether preview files, the shared draft rules and the CTA. The bank
 * preview's own analyzer ([S7E5DClassClosure]) follows their instructions into app classes, a whole class at a time, and
 * reports what lies outside the app. The result is held to the reviewed lists under `src/test/resources/s87/`, which were
 * copied byte for byte from this test's own report under `build/s87_closure_report/` and named by hash in
 * `closure_review.md`. Nothing unresolved, no static write outside a class initialiser, and none of what D26 keeps at zero —
 * the premium editor object, the source owner and history, the remembered source, saving, permission, push, notification,
 * reflection, Hilt — may appear, whether or not it is in the sealed lists.
 */
class S87PreviewClosureTest {

    private fun location(c: Class<*>): File = File(c.protectionDomain.codeSource.location.toURI())
    private val main = location(FreeSourceAlertPreviewSession::class.java)
    private val testKotlin = location(S7E5DClassClosure::class.java)
    private val testJava = location(S7E5DClosureRecordFixture::class.java)
    private val closure = S7E5DClassClosure(listOf(main, testKotlin, testJava))

    private fun rootsOf(sources: List<String>): List<String> = sources.flatMap { path ->
        closure.classesOf("com/jay/fxi/" + path.substringBeforeLast('/'), path.substringAfterLast('/'))
    }

    private fun check(message: String, ok: Boolean) {
        if (!ok) throw AssertionError(message)
    }

    /** Every root source has classes, a missing one has none, and the sheet's Compose and the host's coroutine are reached. */
    @Test
    fun C01_everyRootIncludesGeneratedClasses() {
        val perSource = PREVIEW_SOURCES.associateWith { path ->
            closure.classesOf("com/jay/fxi/" + path.substringBeforeLast('/'), path.substringAfterLast('/'))
        }
        val missing = closure.classesOf("com/jay/fxi/ui/free", "S87NoSuchSource.kt")
        val reached = closure.close(rootsOf(PREVIEW_SOURCES)).appClasses
        check("모든 소스의 합성 루트", perSource.values.all { it.isNotEmpty() } && missing.isEmpty() &&
            reached.any { it.contains("FreeSourceAlertPreviewSheetKt") && it.contains("$") } &&
            reached.any { it.contains("requestHide") })
    }

    /**
     * Nothing reached is a capability D26 keeps at zero. Each detector is first shown to fire on a fixture in this file that
     * reaches that capability, so a silent detector cannot pass.
     */
    @Test
    fun C02_forbiddenCapabilitiesAreUnreachable() {
        val fixtures = closure.classesOf("com/jay/fxi/ui/free", "S87PreviewClosureTest.kt")
        for (name in listOf("S87FixtureEditor", "S87FixtureMemory", "S87FixtureSaveable", "S87FixtureNotify", "S87FixtureReflect", "S87FixtureHistory", "S87FixtureRegistry",
            "S87FixtureLauncher", "S87FixtureIo", "S87FixtureStoreWrite", "S87FixtureOwner", "S87FixtureRegister", "S87FixtureToken", "S87FixtureHilt")) {
            val root = fixtures.filter { it.endsWith("/$name") }
            if (root.size != 1) throw AssertionError("premise: fixture $name 의 class $root")
            val hits = forbidden(closure.close(root))
            val needle = when (name) {
                "S87FixtureEditor" -> "/SourceAlertEditor"
                "S87FixtureMemory" -> "/LastSelectedSourceStore"
                "S87FixtureSaveable" -> "androidx/compose/runtime/saveable/"
                "S87FixtureNotify" -> "android/app/NotificationManager.notify"
                "S87FixtureHistory" -> "BankHistory"
                "S87FixtureRegistry" -> "androidx/savedstate/"
                "S87FixtureLauncher" -> "androidx/activity/result/ActivityResultLauncher.launch"
                "S87FixtureIo" -> "java/nio/file/Files.readAllBytes"
                "S87FixtureStoreWrite" -> "/LastSelectedSourceStore"
                "S87FixtureOwner" -> "/SourceAlertSettingsOwner"
                "S87FixtureRegister" -> "/PushNotificationManager"
                "S87FixtureToken" -> "com/google/firebase/messaging/FirebaseMessaging.getToken"
                "S87FixtureHilt" -> "dagger/hilt/"
                else -> "java/lang/Class.forName"
            }
            if (hits.none { needle in it }) throw AssertionError("premise: $name $needle")
        }
        val preview = closure.close(rootsOf(PREVIEW_SOURCES))
        check("무료 클로저의 금지 능력", forbidden(preview).isEmpty())
    }

    /** No unresolved instruction or stray static write, and the report is byte for byte the reviewed one for this build type. */
    @Test
    fun C03_reportMatchesReviewedBytes() {
        val result = closure.close(rootsOf(PREVIEW_SOURCES))
        val sources = result.appClasses.mapNotNull { result.sourceOf[it] }.toSortedSet()
            .associateWith { path -> sourceFile(path)?.let { sha256(it.readBytes()) } ?: "no-source" }
        val report = mapOf(
            "closure_app_classes.txt" to result.appClasses.sorted().joinToString("\n"),
            "closure_uses.txt" to result.uses.map { it.toString() }.sorted().joinToString("\n"),
            "closure_callbacks.txt" to result.callbackSites.map { "${it.owner}.${it.method} ${it.target}" }.sorted().joinToString("\n"),
            "closure_sources.txt" to sources.entries.joinToString("\n") { "${it.value}  ${it.key}" },
            "closure_versions.txt" to "${sha256(File(VERSION_CATALOG).readBytes())}  gradle/libs.versions.toml"
        ).mapValues { it.value + "\n" }
        val out = File("build/s87_closure_report/${BuildConfig.BUILD_TYPE}")
        out.mkdirs()
        report.forEach { (name, text) -> File(out, name).writeText(text) }
        File(out, "unresolved_and_writes.txt").writeText((result.unresolved + result.staticWrites).joinToString("\n") + "\n")
        File(out, "roots.txt").writeText(rootsOf(PREVIEW_SOURCES).sorted().joinToString("\n") + "\n")
        val review = resource("closure_review.md")
        val sealedMatch = report.all { (name, text) -> resource(if (name == "closure_uses.txt") usesSeal() else name) == text }
        val reviewed = report.values.all { sha256(it.toByteArray()) in review }
        check("검토된 클로저 바이트", result.unresolved.isEmpty() && result.staticWrites.isEmpty() &&
            result.appClasses == result.sourceOf.keys && sources.values.none { it == "no-source" } && sealedMatch && reviewed)
    }

    private fun forbidden(result: S7E5DClassClosure.Result): List<String> =
        result.appClasses.filter { FORBIDDEN_APP.containsMatchIn(it) } +
            result.uses.map { it.toString() }.filter { FORBIDDEN_USE.containsMatchIn(it) } +
            result.callbackSites.filter { site ->
                val file = result.sourceOf[site.owner]?.substringAfterLast('/')
                when (file) {
                    "FreeSourceAlertPreviewSheet.kt" -> site.target != "kotlin/jvm/functions/Function1"
                    "FreeSourceAlertPreviewSession.kt" -> site.target !in setOf("kotlin/jvm/functions/Function0",
                        "kotlin/jvm/functions/Function1", "kotlin/jvm/functions/Function2", "kotlin/jvm/functions/Function4")
                    else -> true
                }
            }.map { "${it.owner}.${it.method} ${it.target}" }

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("s87/$name")?.readBytes()?.toString(Charsets.UTF_8) ?: "(sealed $name missing)"

    private fun usesSeal(): String =
        checkNotNull(USES_SEAL[BuildConfig.BUILD_TYPE]) { "build type ${BuildConfig.BUILD_TYPE} 의 closure_uses 봉인이 없다" }

    private fun sourceFile(path: String): File? = listOf("src/main/java/$path", "src/main/kotlin/$path").map(::File).firstOrNull { it.isFile }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val PREVIEW_SOURCES = listOf(
            "ui/free/FreeSourceAlertPreviewQuotes.kt",
            "ui/free/FreeSourceAlertPreviewSession.kt",
            "ui/free/FreeSourceAlertPreviewSection.kt",
            "ui/free/FreeSourceAlertPreviewSheet.kt",
            "ui/alert/SourceAlertDraftRules.kt",
            "ui/components/SubscribeCTA.kt"
        )
        const val VERSION_CATALOG = "../gradle/libs.versions.toml"

        val USES_SEAL = mapOf(
            "debug" to "closure_uses.txt",
            "ciMinified" to "closure_uses.release.txt",
            "benchmark" to "closure_uses.release.txt",
            "s5Benchmark" to "closure_uses.release.txt"
        )

        /** App classes D26 keeps at zero for the tether preview. `SourceAlertEditorInput` is the pure value and is allowed. */
        val FORBIDDEN_APP = Regex(
            """/SourceAlertEditor(?![A-Za-z])|SourceAlertEditorSheet|SourceAlertEditorMode|SourceAlertEditorCheck|SourceAlertSection|""" +
                """SourceAlertSubmit|data/alerts/SourceAlertDraft(?![A-Za-z])|SourceAlertEdit(?![A-Za-z])|SourceAlertSaveOutcome|""" +
                """SourceAlertSettingsOwner|SourceHistory|LastSelectedSource|LastSelectedBank|LastBankSelection|BankHistory|BankAlertSettingsOwner|AlertRepository|""" +
                """AlertSettingsAccess|AlertFamily|NotificationPermission|NotificationCapability|PushNotification|PushRegistration|""" +
                """PushIntent|Topic|WebSocket|GraphV2|FreeSnapshotScheduler|FreeSnapshotActivityCoordinator|DataStore|""" +
                """SubscriptionManager|PremiumAccess|AuthenticatedApi|data/remote/(?!dto/)|data/local/"""
        )

        /** Library members D26 keeps at zero: saving, notification, system services, reflection, Firebase, Hilt lookups. */
        val FORBIDDEN_USE = Regex(
            """androidx/compose/runtime/saveable/|androidx/lifecycle/SavedStateHandle|androidx/datastore/|android/app/NotificationManager|""" +
                """androidx/core/app/NotificationManagerCompat|android/content/Context\.|android/content/SharedPreferences|""" +
                """java/lang/Class\.forName|java/lang/reflect/|kotlin/reflect/|com/google/firebase/|dagger/|""" +
                """androidx/savedstate/|androidx/activity/result/|androidx/room/|android/database/|android/content/ContentResolver|""" +
                """android/(?:app/Activity|content/ContextWrapper)\.(?:getSystemService|requestPermissions|getSharedPreferences|openFile)|""" +
                """androidx/core/app/ActivityCompat\.requestPermissions|android/os/(?:Bundle|Parcel)\.write|android/os/Bundle\.put|""" +
                """java/lang/Class\.(?:getDeclared|getMethod|getField|newInstance)|java/nio/(?:file|channels)/|java/io/|""" +
                """kotlin/io/|android/system/Os\.|okhttp3/|retrofit2/|okio/|androidx/hilt/|java/net/"""
        )
    }
}

/** Fixtures for C02: each reaches one capability the preview must not, through a neutral-looking member. */
private object S87FixtureEditor {
    fun reach(): String = com.jay.fxi.ui.alert.SourceAlertEditor.formatThreshold(1.0)
}

private object S87FixtureMemory {
    suspend fun reach(m: com.jay.fxi.data.local.LastSelectedSourceStore, uid: String) = m.read(uid)
}

private object S87FixtureSaveable {
    fun reach(): Any = androidx.compose.runtime.saveable.autoSaver<Int>()
}

private object S87FixtureNotify {
    fun reach(m: android.app.NotificationManager, n: android.app.Notification) = m.notify(7, n)
}

private object S87FixtureHistory {
    fun reach() = S87BankHistoryProbe.touch()
}

private object S87BankHistoryProbe {
    fun touch() = Unit
}

private object S87FixtureRegistry {
    fun reach(owner: androidx.savedstate.SavedStateRegistryOwner) = owner.savedStateRegistry
}

private object S87FixtureLauncher {
    fun reach(l: androidx.activity.result.ActivityResultLauncher<String>) = l.launch("android.permission.POST_NOTIFICATIONS")
}

private object S87FixtureIo {
    fun reach(path: java.nio.file.Path): ByteArray = java.nio.file.Files.readAllBytes(path)
}

private object S87FixtureStoreWrite {
    suspend fun reach(s: com.jay.fxi.data.local.LastSelectedSourceStore) = s.remember("fixture", "upbit")
}

private object S87FixtureOwner {
    fun reach(entry: com.jay.fxi.ui.alert.SourceAlertSectionEntryPoint) = entry.sourceAlertSettingsOwner().ensure()
}

private object S87FixtureRegister {
    suspend fun reach(m: com.jay.fxi.service.PushNotificationManager) = m.registerIfNeeded()
}

private object S87FixtureToken {
    fun reach(m: com.google.firebase.messaging.FirebaseMessaging): Any = m.token
}

private object S87FixtureHilt {
    fun reach(c: android.content.Context): Any = dagger.hilt.android.EntryPointAccessors.fromApplication(
        c, com.jay.fxi.ui.alert.SourceAlertSectionEntryPoint::class.java)
}

private object S87FixtureReflect {
    fun reach(): Any = Class.forName("java.lang.String")
}
