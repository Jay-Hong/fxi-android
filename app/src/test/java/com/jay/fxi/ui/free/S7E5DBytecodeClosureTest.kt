package com.jay.fxi.ui.free

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 E5d gap hunt r1 G13·G14 (`e5d_gaphunt.claude.r4.md`, 교체 D1): what the preview's compiled classes can reach, sealed.
 *
 * From every class compiled out of the five preview files, [S7E5DClassClosure] follows the instructions into app classes and
 * stops at the library boundary. The test then holds the result to sealed lists under `src/test/resources/s7e5d/`: the app
 * classes reached, every library member used (kind, owner, name, JVM descriptor), every function value invoked, the sources of
 * those app classes by hash, and the version catalog by hash. Any change to any of them fails here until the lists and the
 * review record `closure_review.md` that names their hashes are reviewed again. Nothing unresolved, no static write outside a
 * class initialiser, and no app class of a capability D26 keeps at zero may appear at all.
 *
 * Each run also writes what it saw to `build/s7e5d_closure_report/`, for that review.
 */
class S7E5DBytecodeClosureTest {

    private val main = location(FreeAlertPreviewSession::class.java)
    private val testKotlin = location(S7E5DClassClosure::class.java)
    private val testJava = location(S7E5DClosureRecordFixture::class.java)
    private val closure = S7E5DClassClosure(listOf(main, testKotlin, testJava))

    private fun rootsOf(sources: List<String>): List<String> = sources.flatMap { path ->
        val dir = "com/jay/fxi/" + path.substringBeforeLast('/')
        closure.classesOf(dir, path.substringAfterLast('/'))
    }

    @Test
    fun premise_everyPreviewSourceHasItsClasses_andAMissingOneIsSeen() {
        for (path in PREVIEW_SOURCES) {
            val dir = "com/jay/fxi/" + path.substringBeforeLast('/')
            assertTrue("$path 의 class 가 없다", closure.classesOf(dir, path.substringAfterLast('/')).isNotEmpty())
        }
        assertEquals("없는 소스는 class 가 없다", emptyList<String>(), closure.classesOf("com/jay/fxi/ui/free", "S7E5DNoSuchSource.kt"))
        val sheet = closure.classesOf("com/jay/fxi/ui/free", "FreeBankAlertPreviewSheet.kt")
        assertTrue("Compose 합성 class 도 그 소스의 것이다: $sheet", sheet.any { it.contains("ComposableSingletons") })
    }

    @Test
    fun theClosureOfThePreviewIsExactlyTheSealedOne() {
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
        val out = File("build/s7e5d_closure_report")
        out.mkdirs()
        report.forEach { (name, text) -> File(out, name).writeText(text) }
        File(out, "unresolved_and_writes.txt").writeText((result.unresolved + result.staticWrites).joinToString("\n") + "\n")
        File(out, "roots.txt").writeText(listOf(main, testKotlin, testJava).joinToString("\n") { "${it.path} dir=${it.isDirectory}" } + "\n")

        assertEquals("풀리지 않은 것", emptySet<String>(), result.unresolved)
        assertEquals("초기화 밖 static 쓰기", emptySet<String>(), result.staticWrites)
        val forbidden = result.appClasses.filter { FORBIDDEN_APP.containsMatchIn(it) }
        assertEquals("D26 이 0으로 두는 capability 의 class", emptyList<String>(), forbidden)
        for ((name, text) in report) {
            val sealed = resource(name)
            assertEquals("$name 이 봉인과 다르다 — build/s7e5d_closure_report/$name 을 검토하라", sealed, text)
        }
        val review = resource("closure_review.md")
        for ((name, text) in report) {
            assertTrue("검토 기록이 $name 의 hash 를 적지 않았다", sha256(text.toByteArray()) in review)
        }
    }

    @Test
    fun theClosureCatchesEachWayOut() {
        val fixtures = closure.classesOf("com/jay/fxi/ui/free", "S7E5DClosureFixtures.kt")
        assertTrue("premise: fixture class 가 있다 $fixtures", fixtures.isNotEmpty())
        val sealedUses = resource("closure_uses.txt").lines().filter { it.isNotBlank() }.toSet()

        val reflection = closure.close(listOf("com/jay/fxi/ui/free/S7E5DClosureFixtureReflection"))
        val reflected = reflection.uses.map { it.toString() }.filter { "java/lang/Class.forName" in it }
        assertTrue("reflection 이 보인다", reflected.isNotEmpty())
        assertTrue("reflection 은 봉인 목록 밖이다", reflected.none { it in sealedUses })

        val fileClass = fixtures.single { it.endsWith("S7E5DClosureFixturesKt") }
        val all = closure.close(listOf(fileClass))
        assertTrue("callback 을 static 에 보관한 것이 보인다: ${all.staticWrites}", all.staticWrites.any { "s7e5dKeptCallback" in it })
        assertTrue("중립 이름의 helper 가 닿은 capability class 가 보인다", all.appClasses.any { FORBIDDEN_APP.containsMatchIn(it) })

        val overload = all.uses.filter { it.owner == "java/lang/Math" && it.member == "max" }
        assertTrue("premise: (JJ)J 로 불렀다 $overload", overload.any { it.descriptor == "(JJ)J" })
        assertTrue("같은 이름의 다른 descriptor 는 다른 열쇠다",
            S7E5DClassClosure.Use("invokestatic", "java/lang/Math", "max", "(JJ)J").toString() !in setOf("invokestatic java/lang/Math.max (II)I"))

        assertTrue("inline 본문의 참조가 호출자에 남는다", all.uses.any { it.owner == "java/lang/Math" && it.member == "abs" })

        val record = closure.close(listOf("com/jay/fxi/ui/free/S7E5DClosureRecordFixture"))
        assertTrue("모르는 bootstrap 은 풀리지 않은 것으로 보고된다: ${record.unresolved}", record.unresolved.any { "ObjectMethods" in it })

        val preview = closure.close(rootsOf(PREVIEW_SOURCES))
        assertTrue("Compose 합성 class 를 따라간다", preview.appClasses.any { it.contains("ComposableSingletons\$FreeBankAlertPreviewSheetKt") })
        assertTrue("coroutine 본문 class 를 따라간다", preview.appClasses.any { it.contains("requestHide") })
    }

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("s7e5d/$name")?.readBytes()?.toString(Charsets.UTF_8) ?: "(sealed $name missing)"

    private fun sourceFile(path: String): File? = listOf("src/main/java/$path", "src/main/kotlin/$path").map(::File).firstOrNull { it.isFile }

    private companion object {
        val PREVIEW_SOURCES = listOf(
            "ui/free/FreeAlertPreviewSession.kt",
            "ui/free/FreeBankAlertPreviewSheet.kt",
            "ui/free/FreeBankAlertPreviewSection.kt",
            "ui/components/SubscribeCTA.kt",
            "ui/alert/BankAlertEditorFields.kt"
        )
        const val VERSION_CATALOG = "../gradle/libs.versions.toml"

        /** App classes of what D26 keeps at zero for a free preview. Reaching any of them, by any path, fails. */
        val FORBIDDEN_APP = Regex(
            """LastSelectedBank|LastBankSelection|BankAlertSettingsOwner|AlertRepository|AlertSettingsAccess|BankHistory|""" +
                """NotificationPermission|NotificationCapability|PushNotification|PushRegistration|PushIntent|Topic|WebSocket|""" +
                """GraphV2|FreeSnapshotScheduler|FreeSnapshotActivityCoordinator|DataStore|SubscriptionManager|PremiumAccess|""" +
                """AuthenticatedApi|data/remote/(?!dto/)|data/local/"""
        )

        fun location(c: Class<*>): File = File(c.protectionDomain.codeSource.location.toURI())
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
