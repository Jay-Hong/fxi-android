package com.jay.fxi.ui.free

import com.jay.fxi.BuildConfig
import java.io.File
import java.security.MessageDigest
import org.junit.Test

class S96PreviewClosureTest {

    private fun location(c: Class<*>): File = File(c.protectionDomain.codeSource.location.toURI())
    private val main = location(FreeComparisonAlertPreviewSession::class.java)
    private val testKotlin = location(S7E5DClassClosure::class.java)
    private val testJava = location(S7E5DClosureRecordFixture::class.java)
    private val closure = S7E5DClassClosure(listOf(main, testKotlin, testJava))

    private fun rootsOf(sources: List<String>): List<String> = sources.flatMap { path ->
        closure.classesOf("com/jay/fxi/" + path.substringBeforeLast('/'), path.substringAfterLast('/'))
    }

    private fun check(message: String, ok: Boolean) {
        if (!ok) throw AssertionError(message)
    }

    @Test
    fun C01() {
        val perSource = PREVIEW_SOURCES.associateWith { path ->
            closure.classesOf("com/jay/fxi/" + path.substringBeforeLast('/'), path.substringAfterLast('/'))
        }
        val missing = closure.classesOf("com/jay/fxi/ui/free", "S87NoSuchSource.kt")
        val roots = rootsOf(PREVIEW_SOURCES)
        val reached = closure.close(roots).appClasses
        val sheet = perSource.getValue("ui/free/FreeComparisonAlertPreviewSheet.kt")
        val host = perSource.getValue("ui/free/FreeComparisonAlertPreviewSession.kt")
        check("모든 소스의 합성 루트", perSource.values.all { it.isNotEmpty() } && missing.isEmpty() &&
            sheet.any { it.substringAfterLast('/').startsWith("FreeComparisonAlertPreviewSheetKt\$") && it in roots && it in reached } &&
            host.any { "requestHide\$" in it && it in roots && it in reached })
    }

    @Test
    fun C02() {
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
        val extraFixtures = mapOf("S96TopicProbe" to "/TopicRates", "S96OwnerProbe" to "/ComparisonAlertSettingsOwner")
        for ((name, needle) in extraFixtures) {
            val probe = closure.close(listOf("com/jay/fxi/ui/free/$name"))
            check("premise: $name", forbidden(probe).any { needle in it })
        }
        val more = forbidden(closure.close(listOf("com/jay/fxi/ui/free/S96IndirectProbe")))
        for (needle in listOf("/WebSocketClientKt", "okhttp3/", "GraphV2SeedMode", "FreeSnapshotScheduler",
            "FreeSnapshotActivityCoordinator", "ComparisonAlertSectionSource", "ComparisonAlertHandle",
            "androidx/datastore/core/DataStore.getData",
            "ComparisonAlertHistorySession", "AlertFamilyRefresherRegistry", "NotificationPermissionOs", "/NotificationCapability")) {
            check("premise: 금지 대상 $needle", more.any { needle in it })
        }
        for (owner in listOf("ui/alert/ComparisonAlertSectionHolder", "ui/alert/ComparisonOpening",
            "data/repository/AlertRepository", "data/alerts/AlertSettingsAccess", "data/remote/TopicRuntime")) {
            check("premise: 금지 class $owner", "com/jay/fxi/$owner" in more)
        }
        val hits = forbidden(preview)
        if (hits.isNotEmpty()) throw AssertionError("무료 클로저의 금지 능력\n$hits")
        check("검토된 callback sites", digestCallbacks(preview) == reviewed()["closure_callbacks.txt"])
    }

    @Test
    fun C03_reportMatchesReviewedDigest() {
        val result = closure.close(rootsOf(PREVIEW_SOURCES))
        val sources = result.appClasses.mapNotNull { result.sourceOf[it] }.toSortedSet()
            .associateWith { path -> sourceFile(path)?.let { sha256(it.readBytes()) } ?: "no-source" }
        val report = mapOf(
            "roots.txt" to lines(rootsOf(PREVIEW_SOURCES)),
            "closure_app_classes.txt" to lines(result.appClasses),
            "closure_uses.txt" to lines(result.uses.map { it.toString() }),
            "closure_callbacks.txt" to callbacks(result),
            "closure_sources.txt" to lines(sources.map { (path, sha) -> "$sha  $path" }),
            "closure_versions.txt" to "${sha256(File(VERSION_CATALOG).readBytes())}  gradle/libs.versions.toml\n",
            "unresolved_and_writes.txt" to lines(result.unresolved + result.staticWrites)
        )
        val out = File("build/s96_closure_report/${BuildConfig.BUILD_TYPE}")
        out.mkdirs()
        report.forEach { (name, value) -> File(out,name).writeText(value, Charsets.UTF_8) }
        check("미해결 또는 static write: ${result.unresolved} ${result.staticWrites}", result.unresolved.isEmpty() && result.staticWrites.isEmpty())
        check("누락 source: $sources", result.sourceOf.keys == result.appClasses && "no-source" !in sources.values &&
            "com/jay/fxi/data/alerts/ComparisonAlertSettingsOwner.kt" in sources)
        val observed = report.mapValues { (file, _) -> sha256(File(out,file).readBytes()) }
        if (observed != reviewed()) throw AssertionError("검토된 클로저 보고서 digest\n${BuildConfig.BUILD_TYPE}: $observed")
    }

    private fun lines(values: Collection<String>) = values.sorted().joinToString("\n", postfix = "\n")
    private fun callbacks(result: S7E5DClassClosure.Result) =
        lines(result.callbackSites.map { "${it.owner}.${it.method} ${it.target}" })
    private fun digestCallbacks(result: S7E5DClassClosure.Result) = sha256(callbacks(result).toByteArray())
    private fun reviewed() = REVIEWED.getValue(BuildConfig.BUILD_TYPE)

    private fun forbidden(result: S7E5DClassClosure.Result) =
        result.appClasses.filter { it != "com/jay/fxi/domain/model/TopicQuoteKey" &&
            (FORBIDDEN_APP.containsMatchIn(it) || ("ComparisonAlertHistory" in it && "/data/remote/dto/" !in it)) } +
            result.uses.map { it.toString() }.filter { FORBIDDEN_USE.containsMatchIn(it) }

    private fun sourceFile(path: String): File? = listOf("src/main/java/$path", "src/main/kotlin/$path").map(::File).firstOrNull { it.isFile }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val PREVIEW_SOURCES = listOf(
            "ui/free/FreeComparisonAlertPreviewQuotes.kt",
            "ui/free/FreeComparisonAlertPreviewSession.kt",
            "ui/free/FreeComparisonAlertPreviewSection.kt",
            "ui/free/FreeComparisonAlertPreviewSheet.kt",
            "ui/alert/ComparisonAlertDraftRules.kt",
            "ui/alert/ComparisonAlertEditorSheet.kt",
            "ui/components/SubscribeCTA.kt"
        )
        const val VERSION_CATALOG = "../gradle/libs.versions.toml"

        // Per-file digests of the reports reviewed on 2026-11-27 (R4c/S9/s9_6_impl/closure_r2/review.wf1.md). The six
        // BankAlertEditorFieldsKt callback sites come in at class granularity through the premium sheet's drag handle and are
        // not executed on the free path; a change to that file or to the bank owner file also changes these pins.
        val REVIEWED: Map<String, Map<String, String>> = mapOf(
            "debug" to mapOf(
                "roots.txt" to "4a967c955a77bc6c12514e034b805ad1c86b14e483a9ac3ee7e1c04addcf6f56",
                "closure_app_classes.txt" to "eb2cadfc9cf2bb71f57293fa90a3627daa9d7009a0ecfb419cbfb94d75b59b0d",
                "closure_uses.txt" to "0b9e209ef7c188f6026572604cdba9d1f879403cfe06262ef082a01f56d34188",
                "closure_callbacks.txt" to "9ccc94e5b2ac4e4cbab67d9173da3768b199b8bb818d7f0f6c73531742b5bb30",
                "closure_sources.txt" to "3247f97b2adab9e261070cfa8aaf03578a2173ff162a55b69cdb9ef48c987cc2",
                "closure_versions.txt" to "7491fa63d0dab9261842f9b8f7d51e95f5191e381f25a717707614134a3a4faa",
                "unresolved_and_writes.txt" to "01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b"
            ),
            "ciMinified" to mapOf(
                "roots.txt" to "4a967c955a77bc6c12514e034b805ad1c86b14e483a9ac3ee7e1c04addcf6f56",
                "closure_app_classes.txt" to "eb2cadfc9cf2bb71f57293fa90a3627daa9d7009a0ecfb419cbfb94d75b59b0d",
                "closure_uses.txt" to "f594365cc8964f9d5f4e761d899aca23b7e1c6e3b3093e65e8ef475002d4fe67",
                "closure_callbacks.txt" to "9ccc94e5b2ac4e4cbab67d9173da3768b199b8bb818d7f0f6c73531742b5bb30",
                "closure_sources.txt" to "3247f97b2adab9e261070cfa8aaf03578a2173ff162a55b69cdb9ef48c987cc2",
                "closure_versions.txt" to "7491fa63d0dab9261842f9b8f7d51e95f5191e381f25a717707614134a3a4faa",
                "unresolved_and_writes.txt" to "01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b"
            ),
            "benchmark" to mapOf(
                "roots.txt" to "4a967c955a77bc6c12514e034b805ad1c86b14e483a9ac3ee7e1c04addcf6f56",
                "closure_app_classes.txt" to "eb2cadfc9cf2bb71f57293fa90a3627daa9d7009a0ecfb419cbfb94d75b59b0d",
                "closure_uses.txt" to "f594365cc8964f9d5f4e761d899aca23b7e1c6e3b3093e65e8ef475002d4fe67",
                "closure_callbacks.txt" to "9ccc94e5b2ac4e4cbab67d9173da3768b199b8bb818d7f0f6c73531742b5bb30",
                "closure_sources.txt" to "3247f97b2adab9e261070cfa8aaf03578a2173ff162a55b69cdb9ef48c987cc2",
                "closure_versions.txt" to "7491fa63d0dab9261842f9b8f7d51e95f5191e381f25a717707614134a3a4faa",
                "unresolved_and_writes.txt" to "01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b"
            ),
            "s5Benchmark" to mapOf(
                "roots.txt" to "4a967c955a77bc6c12514e034b805ad1c86b14e483a9ac3ee7e1c04addcf6f56",
                "closure_app_classes.txt" to "eb2cadfc9cf2bb71f57293fa90a3627daa9d7009a0ecfb419cbfb94d75b59b0d",
                "closure_uses.txt" to "f594365cc8964f9d5f4e761d899aca23b7e1c6e3b3093e65e8ef475002d4fe67",
                "closure_callbacks.txt" to "9ccc94e5b2ac4e4cbab67d9173da3768b199b8bb818d7f0f6c73531742b5bb30",
                "closure_sources.txt" to "3247f97b2adab9e261070cfa8aaf03578a2173ff162a55b69cdb9ef48c987cc2",
                "closure_versions.txt" to "7491fa63d0dab9261842f9b8f7d51e95f5191e381f25a717707614134a3a4faa",
                "unresolved_and_writes.txt" to "01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b"
            )
        )

        val FORBIDDEN_APP = Regex(
            """/SourceAlertEditor(?![A-Za-z])|SourceAlertEditorSheet|SourceAlertEditorMode|SourceAlertEditorCheck|SourceAlertSection|""" +
                """SourceAlertSubmit|data/alerts/SourceAlertDraft(?![A-Za-z])|SourceAlertEdit(?![A-Za-z])|SourceAlertSaveOutcome|""" +
                """SourceAlertSettingsOwner|SourceHistory|LastSelectedSource|LastSelectedBank|LastBankSelection|BankHistory|BankAlertSettingsOwner|AlertRepository|""" +
                """AlertSettingsAccess|AlertFamily|NotificationPermission|NotificationCapability|PushNotification|PushRegistration|""" +
                """ComparisonAlertSettingsOwner|ComparisonAlertHandle|ComparisonAlertSectionSource|ComparisonAlertSectionHolder|ComparisonOpening|PushIntent|Topic|WebSocket|GraphV2|FreeSnapshotScheduler|FreeSnapshotActivityCoordinator|DataStore|""" +
                """SubscriptionManager|PremiumAccess|AuthenticatedApi|data/remote/(?!dto/)|data/local/"""
        )

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

private object S96TopicProbe {
    fun read(topic: com.jay.fxi.domain.model.TopicRates) = topic.quotes
}

private object S96OwnerProbe {
    fun read(owner: com.jay.fxi.data.alerts.ComparisonAlertSettingsOwner) = owner.ensure()
}

private object S96IndirectProbe {
    fun socket() = com.jay.fxi.data.remote.buildWebSocketClient()
    fun graph(): Any = com.jay.fxi.data.graph.GraphV2SeedMode.FillEmpty
    fun snapshot(value: com.jay.fxi.data.free.FreeSnapshotScheduler) = value.start()
    fun coordinator(value: FreeSnapshotActivityCoordinator) = value.dispose()
    fun section(value: com.jay.fxi.ui.alert.ComparisonAlertSectionSource) = value.ensure()
    fun handle(value: com.jay.fxi.data.alerts.ComparisonAlertHandle) = value.outcome
    fun history(value: com.jay.fxi.ui.alert.ComparisonAlertHistorySession) = value.refresh()
    fun holder(value: com.jay.fxi.ui.alert.ComparisonAlertSectionHolder) = value.expanded
    fun opening(value: com.jay.fxi.ui.alert.ComparisonOpening) = value.pending
    fun repository(value: com.jay.fxi.data.repository.AlertRepository) = value.captureOwnerOrNull()
    fun access(value: com.jay.fxi.data.alerts.AlertSettingsAccess) = value.acquire()
    fun store(value: androidx.datastore.core.DataStore<String>) = value.data
    fun runtime(value: com.jay.fxi.data.remote.TopicRuntime) = value.start()
    fun registry(value: com.jay.fxi.data.alerts.AlertFamilyRefresherRegistry) = value.onSyncAlerts()
    fun readCapability(context: android.content.Context) = com.jay.fxi.service.readNotificationCapability(context)
    fun capability(value: com.jay.fxi.ui.alert.NotificationPermissionOs) = value.read()
}
