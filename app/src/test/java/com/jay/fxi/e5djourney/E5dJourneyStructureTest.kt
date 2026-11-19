package com.jay.fxi.e5djourney

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * S7 E5d-J J2a (`e5dj_j2_plan.claude.r3.md` §2 E5dJourneyStructureTest, R6): the five premises under which the journey fixture
 * stays out of [com.jay.fxi.S6Krx3bOffWiringContractTest.ROOTS] (`s7e5dj_j1.codex.r1.md` ROOTS_EXCLUDE_AGREE), each read from
 * the real tree and each with the violations it must catch.
 *
 * 1. The fixture's whole inventory, accessor included, is exactly what was agreed, with no link and no other root; none of its
 *    sources names KRX.
 * 2. Nothing the fixture alone defines — every top-level declaration (a form the collector does not know fails closed), its
 *    control action, permission and authority, its package — is named by any production or other build type's tree. The shared
 *    accessor contract, at its one package, file and signature, is the one boundary they share. MockWebServer stays a test, S5
 *    fixture and journey fixture dependency, never a shared one.
 * 3. The two shared directory lists keep exactly their build types and no other line of this script reroutes a source; the four
 *    variants' merged manifests carry nothing of the fixture; and the inputs AGP finally resolves for debug, release,
 *    ciMinified, benchmark, s5Benchmark and e5dJourney (`reportE5dJourneyVariantInputs`) are exactly the expected roots of
 *    each kind, keep the fixture and the shared directories apart, compile exactly one accessor each, and leave the journey
 *    debuggable and unminified (`s7e5dj_j2a.codex.r1.md` Q2-A). Where the model cannot be read (release resources), the script
 *    boundary stands in: exactly five Gradle scripts, no buildSrc or build-logic, no variant or source line outside the app's
 *    locked script, no included build in the invocation, and no init script in it but those CI's setup-gradle writes into
 *    the Gradle user home's init.d, by name (`s7e5dj_j2a.codex.r2.md` Q2-A, amended for CI). Every file read here is an
 *    input of the Test task (Q2-B).
 * 4. The accessor is declared nowhere in main, once in the default directory and once in the journey's.
 * 5. The control permission is signature; the control provider is one, exported at its one authority, bound to it, in the
 *    app's own process, and its call checks the permission itself before anything else; the old control receiver is gone; the
 *    test APK asks for the permission; and the local merged-manifest
 *    check still checks both.
 *
 * File names are walked without following links; google-services.json is never opened (only Kotlin, Java and XML are read).
 */
class E5dJourneyStructureTest {

    // 1 ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun one_theInventoryIsExact_unlinked_andKrxFree() {
        assertEquals("inventory", emptyList<String>(), inventoryProblems(fixtureEntries(), fixtureLinks()))
        val sources = fixtureTexts()
        assertTrue("premise: every fixture source is read", sources.values.all { it.isNotBlank() })
        assertEquals("KRX", emptyList<String>(), krxProblems(sources))
        assertEquals("declarations", emptyList<String>(), declarations(sources).problems)
    }

    @Test
    fun one_negatives() {
        val real = fixtureEntries()
        assertTrue(inventoryProblems(real - ACCESSOR_FILE, emptySet()).any { "missing" in it })
        assertTrue(inventoryProblems(real + "kotlin/com/jay/fxi/e5djourney/Extra.kt", emptySet()).any { "unexpected" in it })
        assertTrue(inventoryProblems(real + "java/com/jay/fxi/e5djourney/Extra.kt", emptySet()).any { "unexpected" in it })
        assertTrue(inventoryProblems(real, setOf("java/com/jay/fxi/ui")).any { "link" in it })
        assertTrue(krxProblems(mapOf("java/X.kt" to "val s = SourceKey.KRX")).isNotEmpty())
        assertTrue(krxProblems(mapOf("java/X.kt" to "import com.jay.fxi.data.krx.Foo")).isNotEmpty())
    }

    // 2 ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun two_fixtureOnlyCapabilitiesStayInTheFixture_andMockWebServerIsNotPromoted() {
        val names = declarations(fixtureTexts()).names
        assertTrue("premise: the fixture's own declarations are found $names",
            names.containsAll(setOf("E5dJourneyControlProvider", "E5dJourneyControl", "E5dJourneyLifecycleProvider", "E5dJourneyHooks", "HookSet",
                "E5dJourneyAccess",
                "E5dJourneyServer", "E5dJourneyIdentity", "E5dJourneyStart", "E5dJourneyRecord")) &&
                ACCESSOR !in names)
        val trees = otherTreeTexts()
        assertTrue("premise: the other trees are read", trees.keys.any { it.startsWith("src/main/") } && trees.size > 300)
        assertEquals("leak", emptyList<String>(), leakProblems(names, trees))
        assertEquals("MockWebServer", emptyList<String>(), mockWebServerProblems(gradle(), catalog(), trees + fixtureTextsByPath()))
        assertTrue("positive control: the reviewed S5 fixture server keeps its MockWebServer",
            trees.getValue(S5_SERVER).contains("import okhttp3.mockwebserver.MockWebServer"))
    }

    @Test
    fun two_negatives() {
        val names = declarations(fixtureTexts()).names
        val clean = mapOf("src/main/java/com/jay/fxi/ui/free/A.kt" to "internal fun currentPreviewHostHooks(): PreviewHostHooks = NoPreviewHostHooks")
        assertEquals("the shared accessor contract is the boundary", emptyList<String>(), leakProblems(names, clean))
        for ((path, text) in listOf(
            "src/release/java/com/jay/fxi/A.kt" to "fun a() = E5dJourneyRecord.add(\"x\", \"y\")",
            "src/ciMinified/java/com/jay/fxi/A.kt" to "val c = E5dJourneyControlProvider::class",
            "src/main/java/com/jay/fxi/data/entitlements/A.kt" to "fun a(c: PremiumAccessCoordinator) = E5dJourneyAccess.read(c)",
            "src/main/java/com/jay/fxi/B.kt" to "val u = android.net.Uri.parse(\"content://com.jay.fxi.e5djourney.control\")",
            "src/main/java/com/jay/fxi/ui/free/A.kt" to "internal val set: HookSet? = null",
            "src/main/AndroidManifest.xml" to "<provider android:name=\"com.jay.fxi.e5djourney.E5dJourneyControlProvider\" android:authorities=\"com.jay.fxi.e5djourney.control\"/>",
            "src/debug/res/values/a.xml" to "<string name=\"a\">com.jay.fxi.e5djourney.CONTROL</string>",
            "src/benchmark/java/com/jay/fxi/A.kt" to "val p = \"com.jay.fxi.e5djourney.permission.CONTROL\""
        )) {
            assertTrue("$path must be caught", leakProblems(names, mapOf(path to text)).isNotEmpty())
        }
        val gradle = gradle()
        val promoted = listOf(
            "    implementation(libs.okhttp.mockwebserver)",
            "    add(\"mainImplementation\", libs.okhttp.mockwebserver)",
            "    debugImplementation(libs.okhttp.mockwebserver)",
            "    add(\"releaseImplementation\", libs.okhttp.mockwebserver)",
            "    implementation(\"com.squareup.okhttp3:mockwebserver:4.12.0\")"
        )
        for (line in promoted) {
            assertTrue("$line must be caught", mockWebServerProblems(gradle.replace("dependencies {\n", "dependencies {\n$line\n"),
                catalog(), emptyMap()).isNotEmpty())
        }
        assertTrue("a bundle must be caught", mockWebServerProblems(gradle,
            catalog() + "\n[bundles]\ntesting = [\"okhttp-mockwebserver\"]\n", emptyMap()).isNotEmpty())
        assertTrue("a new production import must be caught", mockWebServerProblems(gradle, catalog(),
            mapOf("src/main/java/com/jay/fxi/A.kt" to "import okhttp3.mockwebserver.MockWebServer")).isNotEmpty())
    }

    @Test
    fun two_declarations_areCollectedInEveryForm_orRefused() {
        val real = fixtureTexts()
        assertEquals("premise: the real fixture reads cleanly", emptyList<String>(), declarations(real).problems)
        fun with(file: String, line: String) = real + (file to real.getValue(file) + "\n$line\n")
        val hooks = "java/com/jay/fxi/e5djourney/E5dJourneyHooks.kt"
        // Used unqualified from the accessor's own package elsewhere: no fixture package string, no other fixture name, so only
        // the collected name can make it a leak.
        fun usedFromUiFree(name: String) = mapOf("src/main/java/com/jay/fxi/ui/free/A.kt" to "package com.jay.fxi.ui.free\nfun a() = $name")
        for ((file, line, collected) in listOf(
            Triple(hooks, "internal val specialHooks = Any()", listOf("specialHooks")),
            Triple(hooks, "private var specialCount = 0", listOf("specialCount")),
            Triple(hooks, "internal suspend fun specialControl() = Unit", listOf("specialControl")),
            Triple(hooks, "internal typealias SpecialAlias = Int", listOf("SpecialAlias")),
            Triple(hooks, "internal fun String.specialExt(): String = this", listOf("specialExt")),
            Triple(hooks, "@Suppress(\"unused\") internal const val SPECIAL_ACTION = \"{x}\"", listOf("SPECIAL_ACTION")),
            Triple(hooks, "private inline fun <reified T> specialPick(): T? = null", listOf("specialPick")),
            Triple(hooks, "internal enum class SpecialMode { A }", listOf("SpecialMode")),
            Triple(ACCESSOR_FILE, "internal val firstHooks = Any(); internal val secondHooks = Any()", listOf("firstHooks", "secondHooks")),
            Triple(ACCESSOR_FILE, "internal val templated = \"${'$'}{listOf(\"}\").size}\"; internal val afterTemplate = 1", listOf("templated", "afterTemplate"))
        )) {
            val found = declarations(with(file, line))
            assertTrue("$line is collected: ${found.problems}", found.names.containsAll(collected) && found.problems.isEmpty())
            for (name in collected) {
                val use = usedFromUiFree(name)
                assertTrue("premise: $name's use names nothing else", leakProblems(found.names - name, use).isEmpty())
                assertTrue("$name used from com.jay.fxi.ui.free", leakProblems(found.names, use).isNotEmpty())
            }
        }
        // After an import in the accessor's own import block (`s7e5dj_j2a.codex.r3.md` B): the declaration is still read.
        val importLine = "import androidx.compose.runtime.remember"
        val withImport = real + (ACCESSOR_FILE to real.getValue(ACCESSOR_FILE)
            .replace(importLine, "$importLine\nimport kotlin.Any; internal val hiddenHooks = Any()"))
        assertTrue("premise: the import block is where it was", real.getValue(ACCESSOR_FILE).contains("$importLine\n"))
        val afterImport = declarations(withImport)
        assertTrue("after an import: ${afterImport.problems}", "hiddenHooks" in afterImport.names && afterImport.problems.isEmpty())
        assertTrue("premise: hiddenHooks's use names nothing else", leakProblems(afterImport.names - "hiddenHooks", usedFromUiFree("hiddenHooks")).isEmpty())
        assertTrue("hiddenHooks used from com.jay.fxi.ui.free", leakProblems(afterImport.names, usedFromUiFree("hiddenHooks")).isNotEmpty())
        for ((file, line) in listOf(
            hooks to "expect fun platformThing()",
            hooks to "internal fun <T> List<T>.specialGeneric(): T = first()",
            hooks to "internal fun a.b.specialQualified() = Unit",
            ACCESSOR_FILE to " internal val specialHooks = Any()",
            ACCESSOR_FILE to "\tinternal val tabbedHooks = Any()",
            ACCESSOR_FILE to "/* marker */ internal val hiddenHooks = Any()",
            ACCESSOR_FILE to "internal object Holder { } internal val trailingHooks = Any()",
            ACCESSOR_FILE to "@file:Suppress(\"unused\")"
        )) {
            assertTrue("'$line' fails closed", declarations(with(file, line)).problems.isNotEmpty())
        }
        assertTrue("an accessor elsewhere", declarations(with(hooks,
            "internal fun currentPreviewHostHooks(): PreviewHostHooks = NoPreviewHostHooks")).problems.isNotEmpty())
        assertTrue("the accessor gone", declarations(real + (ACCESSOR_FILE to real.getValue(ACCESSOR_FILE)
            .replace("internal fun currentPreviewHostHooks(): PreviewHostHooks {", "internal fun otherHooks(): PreviewHostHooks {"))).problems.isNotEmpty())
        assertTrue("the accessor in another package", declarations(real + (ACCESSOR_FILE to real.getValue(ACCESSOR_FILE)
            .replace("package com.jay.fxi.ui.free", "package com.jay.fxi.e5djourney"))).problems.isNotEmpty())
        // Inside a function, an indented line is not top level and is not a declaration of the file.
        assertTrue("inside a body", declarations(real + (ACCESSOR_FILE to real.getValue(ACCESSOR_FILE)
            .replace("    val hooks = remember", "    val localOnly = 1\n    val hooks = remember"))).let { "localOnly" !in it.names && it.problems.isEmpty() })
    }

    // 3 ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun three_theSharedListsAndRoutesAreExact_andTheFourMergedManifestsCarryNoFixture() {
        assertEquals("source sets", emptyList<String>(), sourceSetProblems(gradle()))
        assertEquals("links", emptyList<String>(), linkProblems())
        for (variant in MERGED_VARIANTS) {
            val merged = File("build/intermediates/merged_manifests/$variant/process${variant.replaceFirstChar { it.uppercase() }}Manifest/AndroidManifest.xml")
            assertTrue("premise: $variant merged manifest is a Test input", merged.isFile)
            assertTrue("$variant merged manifest names the fixture", !merged.readText().contains("e5djourney", ignoreCase = true))
        }
    }

    @Test
    fun three_theFinalVariantInputsKeepTheFixtureApart() {
        val reports = variantReports()
        assertTrue("premise: six reports", reports.values.all { it != null })
        assertEquals("variant inputs", emptyList<String>(), modelProblems(reports))
    }

    @Test
    fun three_modelNegatives() {
        val observed = variantReports().mapValues { checkNotNull(it.value) }
        assertEquals("premise: actual variant inputs", emptyList<String>(), modelProblems(observed))
        val real = observed.mapValues { (_, r) ->
            r.copy(values = r.values + ("initScripts" to "0"), initScripts = emptyList())
        }
        assertEquals("premise: no-init fixture", emptyList<String>(), modelProblems(real))
        val app = APP.toString()
        fun location(kind: String, lexical: String, canonical: String = lexical) = Location(kind, Path.of(lexical), Path.of(canonical))
        fun change(variant: String, edit: (VariantReport) -> VariantReport): Map<String, VariantReport?> =
            real + (variant to edit(real.getValue(variant)))
        fun plus(variant: String, vararg extra: Location) = change(variant) { it.copy(locations = it.locations + extra) }
        val home = Path.of(real.getValue("ciMinified").values.getValue("gradleUserHome"))
        for ((what, reports) in listOf(
            "release takes the journey's java" to plus("release", location("java", "$app/src/e5dJourney/java")),
            "release reaches it through an alias" to plus("release", location("java", "$app/src/release/java", "$app/src/e5dJourney/java")),
            "debug takes the journey's manifest" to plus("debug", location("manifest", "$app/src/e5dJourney/AndroidManifest.xml")),
            "ciMinified takes the journey's res" to plus("ciMinified", location("res-dsl", "$app/src/e5dJourney/res")),
            "s5Benchmark takes the test module" to plus("s5Benchmark", location("java", "${APP.parent}/e5dJourney/src/main/kotlin")),
            "the journey takes the default accessor" to plus("e5dJourney", location("java", "$app/src/e5dHooksDefault/java")),
            "the journey takes the production bindings" to plus("e5dJourney", location("kotlin", "$app/src/productionBindings/java")),
            "the journey minifies" to change("e5dJourney") { it.copy(values = it.values + ("minify" to "true")) },
            "the journey shrinks" to change("e5dJourney") { it.copy(values = it.values + ("shrink" to "true")) },
            "the journey is not debuggable" to change("e5dJourney") { it.copy(values = it.values + ("debuggable" to "false")) },
            "the journey loses its java" to change("e5dJourney") { r -> r.copy(locations = r.locations.filterNot { "src/e5dJourney/java" in it.lexical.toString() }) },
            "debug loses the default accessor" to change("debug") { r -> r.copy(locations = r.locations.filterNot { "e5dHooksDefault" in it.lexical.toString() }) },
            "debug's model and DSL res differ" to plus("debug", location("res", "$app/src/extra/res")),
            "a report is missing" to (real + ("benchmark" to null)),
            "a report is for another variant" to change("benchmark") { it.copy(name = "debug") },
            "the journey takes an extra root without an accessor" to plus("e5dJourney", location("kotlin", "$app/src/e5dJourneyExtra/kotlin")),
            "release takes an extra resources root" to plus("release", location("resources", "$app/src/extra/resources")),
            "release reads its model resources" to plus("release", location("res", "$app/src/release/res")),
            "debug loses a root" to change("debug") { r -> r.copy(locations = r.locations.filterNot { it.kind == "assets" && "src/debug/assets" in it.lexical.toString() }) },
            "an init script" to change("ciMinified") { it.copy(values = it.values + ("initScripts" to "1")) },
            "an init script in the repository" to change("ciMinified") {
                it.copy(values = it.values + ("initScripts" to "1"), initScripts = listOf(APP.parent.resolve("gradle/gradle-actions.inject-develocity.init.gradle")))
            },
            "CI's script name outside init.d" to change("ciMinified") {
                it.copy(values = it.values + ("initScripts" to "1"), initScripts = listOf(home.resolve("gradle-actions.inject-develocity.init.gradle")))
            },
            "another script in init.d" to change("ciMinified") {
                it.copy(values = it.values + ("initScripts" to "1"), initScripts = listOf(home.resolve("init.d/extra.init.gradle")))
            },
            "an init script listed twice" to change("ciMinified") {
                it.copy(values = it.values + ("initScripts" to "2"), initScripts = List(2) { home.resolve("init.d/gradle-actions.inject-develocity.init.gradle") })
            },
            "no Gradle user home" to change("ciMinified") { it.copy(values = it.values - "gradleUserHome") },
            "an included build" to change("s5Benchmark") { it.copy(values = it.values + ("includedBuilds" to "1")) }
        )) {
            assertTrue("$what must be caught", modelProblems(reports).isNotEmpty())
        }
        // The invocation CI runs, whatever this one's: the three setup-gradle init scripts in each report's init.d pass.
        val ci = real.mapValues { (_, r) ->
            val initD = Path.of(r.values.getValue("gradleUserHome")).resolve("init.d")
            r.copy(values = r.values + ("initScripts" to "3"), initScripts = CI_INIT_SCRIPTS.map { initD.resolve(it) })
        }
        assertEquals("CI's setup-gradle init scripts", emptyList<String>(), modelProblems(ci))
    }

    @Test
    fun three_theScriptBoundaryIsFixed() {
        val scripts = repoScripts()
        assertTrue("premise: the scripts are found $scripts", "app/build.gradle.kts" in scripts && "settings.gradle.kts" in scripts)
        assertEquals("script boundary", emptyList<String>(), scriptProblems(scripts, scriptTexts(), presentBuildDirs()))
        assertEquals("boundary tokens", emptyList<String>(), tokenProblems(allScriptTexts()))
        assertEquals("apply lines", emptyList<String>(), applyProblems(allScriptTexts()))
    }

    @Test
    fun three_tokenNegatives() {
        val texts = allScriptTexts()
        assertEquals("premise: the approved counts hold", emptyList<String>(), tokenProblems(texts))
        assertEquals("premise: the approved apply lines hold", emptyList<String>(), applyProblems(texts))
        fun caught(broken: Map<String, String>) = tokenProblems(broken).isNotEmpty() || applyProblems(broken).isNotEmpty()
        fun edit(path: String, extra: String) = texts + (path to texts.getValue(path) + "\n$extra\n")
        for ((what, broken) in listOf(
            // `s7e5dj_j2a.codex.r3.md` A: a script applied from the app's own script, outside every scanned directory.
            "app applies a script on one line" to edit("app/build.gradle.kts", "apply(from = \"../build/e5dj-extra.gradle.kts\")"),
            // The same from the root script, split over lines.
            "root applies a script over lines" to edit("build.gradle.kts", "apply(\n    from = \"build/e5dj-extra.gradle.kts\"\n)"),
            "a script applied in a block" to edit("settings.gradle.kts", "apply {\n    from(\"gradle/extra.gradle.kts\")\n}"),
            "a plugin applied by type" to edit("macrobenchmark/build.gradle.kts", "apply<org.gradle.api.Plugin<org.gradle.api.Project>>()"),
            "a plugin applied by the manager" to edit("e5dJourney/build.gradle.kts", "pluginManager.apply(\"com.example.extra\")"),
            "app reaches another project" to edit("app/build.gradle.kts", "project(\":macrobenchmark\") { }"),
            "app adds a variant hook over lines" to edit("app/build.gradle.kts", "androidComponents.onVariants(\n    androidComponents.selector().withBuildType(\"release\")\n) { }"),
            "root configures every project" to edit("build.gradle.kts", "subprojects {\n}"),
            "a buildscript block" to edit("build.gradle.kts", "buildscript {\n}")
        )) {
            assertTrue("$what must be caught", tokenProblems(broken).isNotEmpty())
        }
        // `s7e5dj_j2a.codex.r4.md` A: the same application written otherwise.
        for ((what, broken) in listOf(
            "an escaped apply" to edit("app/build.gradle.kts", "project.`apply`(mapOf(\"from\" to \"../build/e5dj-extra.gradle.kts\"))"),
            "a callable reference to apply" to edit("app/build.gradle.kts",
                "val e5djApply: (Map<String, Any>) -> Unit = project::apply\ne5djApply(mapOf(\"from\" to \"../build/e5dj-extra.gradle.kts\"))"),
            "apply by reflection" to edit("app/build.gradle.kts",
                "project.javaClass.getMethod(\"apply\", Map::class.java).invoke(project, mapOf(\"from\" to \"x\"))"),
            "an escaped include" to edit("settings.gradle.kts", "`include`(\":extra\")"),
            "a reference to project" to edit("macrobenchmark/build.gradle.kts", "val other = ::project")
        )) {
            assertTrue("$what must be caught", caught(broken))
        }
        // Words inside comments and strings are not code.
        assertTrue("comments and strings", tokenProblems(edit("build.gradle.kts", "// apply(from = \"x\")\nval note = \"subprojects { project(\\\":app\\\") }\"")).isEmpty())
    }

    @Test
    fun three_scriptNegatives() {
        val scripts = repoScripts()
        val texts = scriptTexts()
        fun edit(path: String, extra: String) = texts + (path to texts.getValue(path) + "\n$extra\n")
        for ((what, broken) in listOf(
            "root adds a release resource root" to edit("build.gradle.kts",
                "subprojects { plugins.withId(\"com.android.application\") { extensions.getByType<com.android.build.api.variant.ApplicationAndroidComponentsExtension>().onVariants(selector().withBuildType(\"release\")) { it.sources.res?.addStaticSourceDirectory(\"src/e5dJourney/res\") } } }"),
            "root configures projects later" to edit("build.gradle.kts", "allprojects { afterEvaluate { } }"),
            "settings includes a build" to edit("settings.gradle.kts", "includeBuild(\"build-logic\")"),
            "macrobenchmark reaches into app" to edit("macrobenchmark/build.gradle.kts", "evaluationDependsOn(\":app\")"),
            "the journey module adds a variant hook" to edit("e5dJourney/build.gradle.kts", "androidComponents { onVariants { } }"),
            "a script is applied" to edit("build.gradle.kts", "apply(from = \"gradle/extra.gradle.kts\")")
        )) {
            assertTrue("$what must be caught", scriptProblems(scripts, broken, emptySet()).isNotEmpty())
        }
        assertTrue("a buildSrc script", scriptProblems(scripts + "buildSrc/build.gradle.kts", texts, emptySet()).isNotEmpty())
        assertTrue("an in-repo init script", scriptProblems(scripts + "gradle/init.gradle.kts", texts, emptySet()).isNotEmpty())
        assertTrue("a buildSrc directory", scriptProblems(scripts, texts, setOf("buildSrc")).isNotEmpty())
        assertTrue("a script gone", scriptProblems(scripts - "macrobenchmark/build.gradle.kts", texts, emptySet()).isNotEmpty())
    }

    @Test
    fun three_negatives() {
        val gradle = gradle()
        val bindings = "        listOf(\"debug\", \"release\", \"ciMinified\", \"benchmark\").forEach { type ->"
        val hooks = "        listOf(\"debug\", \"release\", \"ciMinified\", \"benchmark\", \"s5Benchmark\").forEach { type ->"
        assertTrue(gradle.count(bindings) == 1 && gradle.count(hooks) == 1)
        for (broken in listOf(
            gradle.replace(hooks, hooks.replace("\"s5Benchmark\")", "\"s5Benchmark\", \"e5dJourney\")")),
            gradle.replace(bindings, bindings.replace("\"benchmark\")", "\"benchmark\", \"e5dJourney\")")),
            gradle.replace(hooks, hooks.replace("\"debug\", ", "")),
            gradle.replace("    sourceSets {\n", "    sourceSets {\n        getByName(\"release\").java.srcDir(\"src/e5dJourney/java\")\n"),
            gradle.replace("    sourceSets {\n", "    sourceSets {\n        getByName(\"e5dJourney\").kotlin.srcDir(\"src/e5dJourneyExtra/kotlin\")\n"),
            gradle.replace("    sourceSets {\n", "    sourceSets {\n        getByName(\"e5dJourney\").java.srcDirs(\"src/e5dHooksDefault/java\")\n"),
            gradle + "\nandroidComponents { onVariants { it.sources.java?.addStaticSourceDirectory(\"src/e5dJourney/java\") } }\n",
            gradle.replace("    sourceSets {\n", "    sourceSets {\n        getByName(\"release\").setRoot(\"src/e5dJourney\")\n")
        )) {
            assertTrue("must be caught", sourceSetProblems(broken).isNotEmpty())
        }
    }

    // 4 ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun four_theAccessorIsDeclared_zeroOneOne() {
        val declarations = accessorDeclarations(otherTreeTexts() + fixtureTextsByPath())
        assertEquals("accessor", emptyList<String>(), accessorProblems(declarations))
        assertTrue("the journey's accessor hands out the journey's hook set",
            fixtureTexts().getValue(ACCESSOR_FILE).contains("remember { E5dJourneyHooks.newSet() }"))
        assertTrue("the default accessor stays the no-op",
            File(DEFAULT_ACCESSOR).readText().contains("internal fun currentPreviewHostHooks(): PreviewHostHooks = NoPreviewHostHooks"))
    }

    @Test
    fun four_negatives() {
        val real = accessorDeclarations(otherTreeTexts() + fixtureTextsByPath())
        assertTrue("premise: both accessors are found", real.keys == setOf("$FIXTURE/$ACCESSOR_FILE", DEFAULT_ACCESSOR))
        assertTrue("journey accessor missing", accessorProblems(real.filterKeys { !it.startsWith("$FIXTURE/") }).isNotEmpty())
        assertTrue("default accessor missing", accessorProblems(real.filterKeys { !it.startsWith("src/e5dHooksDefault/") }).isNotEmpty())
        assertTrue("declared in main", accessorProblems(real + ("src/main/java/com/jay/fxi/ui/free/X.kt" to 1)).isNotEmpty())
        assertTrue("declared twice in the journey", accessorProblems(real + ("src/e5dJourney/java/com/jay/fxi/ui/free/X.kt" to 1)).isNotEmpty())
    }

    // 5 ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun five_stateCommandsWaitForReadiness_beforeTheGraph() {
        assertEquals("readiness", emptyList<String>(), readinessProblems(controlSource()))
        val source = controlSource()
        val ready = READY_LINES[1]
        assertTrue(source.count(READY_LINES[2]) == 1)
        for ((what, broken) in listOf(
            "local-premium not waiting" to source.replace("RELEASE, LOCAL_PREMIUM, ACCESS, SEED_LAST_BANK)", "RELEASE, ACCESS, SEED_LAST_BANK)"),
            "access not waiting" to source.replace("RELEASE, LOCAL_PREMIUM, ACCESS, SEED_LAST_BANK)", "RELEASE, LOCAL_PREMIUM, SEED_LAST_BANK)"),
            "seed-last-bank not waiting" to source.replace("RELEASE, LOCAL_PREMIUM, ACCESS, SEED_LAST_BANK)", "RELEASE, LOCAL_PREMIUM, ACCESS)"),
            "readiness checked after the graph" to source.replace("$ready\n", "").replace(READY_LINES[2], READY_LINES[2] + "\n        $ready"),
            "readiness removed" to source.replace("$ready\n", "")
        )) {
            assertTrue("$what changes the source", broken != source)
            assertTrue("$what must be caught", readinessProblems(broken).isNotEmpty())
        }
    }

    @Test
    fun five_thePermissionIsSignature_bound_askedFor_andStillCheckedWhenMerged() {
        assertEquals("permission", emptyList<String>(), permissionProblems(FIXTURE_MANIFEST.readText(), TEST_MANIFEST.readText(), controlSource()))
        assertEquals("local merged check", emptyList<String>(), mergedCheckProblems(gradle(), File(TEST_BUILD).readText()))
    }

    @Test
    fun five_negatives() {
        val fixture = FIXTURE_MANIFEST.readText()
        val test = TEST_MANIFEST.readText()
        val source = controlSource()
        val bound = "android:permission=\"$CONTROL\""
        val signature = "android:protectionLevel=\"signature\""
        val authority = "android:authorities=\"$CONTROL_AUTHORITY\""
        val exported = "android:name=\"com.jay.fxi.e5djourney.E5dJourneyControlProvider\"\n            android:authorities=\"$CONTROL_AUTHORITY\"\n            android:exported=\"true\""
        assertTrue(fixture.count(bound) == 1 && fixture.count(signature) == 1 && fixture.count(authority) == 1 && fixture.count(exported) == 1 &&
            test.count("android:name=\"$CONTROL\"") == 1)
        val provider = "<provider\n            android:name=\"com.jay.fxi.e5djourney.E5dJourneyControlProvider\""
        assertTrue(fixture.count(provider) == 1)
        for ((what, broken) in listOf(
            "provider unbound" to fixture.replace(bound, ""),
            "provider bound to another name" to fixture.replace(bound, "android:permission=\"$CONTROL.OTHER\""),
            "permission normal" to fixture.replace(signature, "android:protectionLevel=\"normal\""),
            "permission level missing" to fixture.replace(signature, ""),
            "provider not exported" to fixture.replace(exported, exported.replace("android:exported=\"true\"", "android:exported=\"false\"")),
            "authority misspelt" to fixture.replace(authority, "android:authorities=\"$CONTROL_AUTHORITY.x\""),
            "authority missing" to fixture.replace(authority, ""),
            "a second authority" to fixture.replace(authority, "android:authorities=\"$CONTROL_AUTHORITY;com.jay.fxi.other\""),
            "a process of its own" to fixture.replace(provider, "$provider\n            android:process=\":control\""),
            "the old receiver back" to fixture.replace("</application>",
                "<receiver android:name=\"com.jay.fxi.e5djourney.E5dJourneyControlReceiver\" android:exported=\"true\" android:permission=\"$CONTROL\" />\n    </application>")
        )) {
            assertTrue("$what must be caught", permissionProblems(broken, test, source).isNotEmpty())
        }
        assertTrue("test APK does not ask", permissionProblems(fixture, test.replace("android:name=\"$CONTROL\"", "android:name=\"$CONTROL.OTHER\""), source).isNotEmpty())
        val check = "if (context.checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) throw SecurityException(\"control: ${'$'}PERMISSION is required\")"
        assertTrue(source.count(check) == 1)
        assertTrue("the call's own check removed", permissionProblems(fixture, test, source.replace(check, "")).isNotEmpty())
        assertTrue("the call's check moved after the work", permissionProblems(fixture, test, source.replace(check, "").replace(
            "return synchronized(serial) { E5dJourneyControl.handle(context, method, extras ?: Bundle()) }",
            "return synchronized(serial) { E5dJourneyControl.handle(context, method, extras ?: Bundle()) }.also { $check }")).isNotEmpty())
        assertTrue("the permission name changed", permissionProblems(fixture, test,
            source.replace("const val PERMISSION = \"$CONTROL\"", "const val PERMISSION = \"$CONTROL.OTHER\"")).isNotEmpty())
        val gradle = gradle()
        val testBuild = File(TEST_BUILD).readText()
        assertTrue("merged check weakened", mergedCheckProblems(gradle.replace("getAttributeNS(ns, \"protectionLevel\") == \"signature\"",
            "getAttributeNS(ns, \"protectionLevel\") != \"\""), testBuild).isNotEmpty())
        assertTrue("merged provider check weakened", mergedCheckProblems(gradle.replace(
            "controls.single().getAttributeNS(ns, \"exported\") == \"true\" && ", ""), testBuild).isNotEmpty())
        assertTrue("merged old-receiver check dropped", mergedCheckProblems(gradle.replace(
            "check(elements(application, \"receiver\").none { it.getAttributeNS(ns, \"name\").contains(\"E5dJourneyControl\") }) {",
            "check(true) {"), testBuild).isNotEmpty())
        assertTrue("merged check unhooked", mergedCheckProblems(gradle, testBuild.replace("\":app:verifyE5dJourneyManifest\", ", "")).isNotEmpty())
    }

    // R7: the variant is debug-based and nothing is minified --------------------------------------------------------------

    @Test
    fun variant_startsFromDebug_andMinifiesNothing() {
        assertEquals("variant", emptyList<String>(), variantProblems(gradle()))
    }

    @Test
    fun variant_negatives() {
        val gradle = gradle()
        val start = "        create(\"e5dJourney\") {\n            initWith(getByName(\"debug\"))\n"
        assertTrue(gradle.count(start) == 1)
        assertTrue("minify", variantProblems(gradle.replace(start, start + "            isMinifyEnabled = true\n")).isNotEmpty())
        assertTrue("shrink", variantProblems(gradle.replace(start, start + "            isShrinkResources = true\n")).isNotEmpty())
        assertTrue("from release", variantProblems(gradle.replace(start, start.replace("\"debug\"))", "\"release\"))"))).isNotEmpty())
        val catalogKey = ASSET_KEY_LINES[0]
        assertTrue(gradle.count(catalogKey) == 1)
        assertTrue("asset key value", variantProblems(gradle.replace("catalog-krx-hidden.json", "catalog-krx-visible.json")).isNotEmpty())
        assertTrue("asset key missing", variantProblems(gradle.replace("            $catalogKey\n", "")).isNotEmpty())
        assertTrue("asset key in debug", variantProblems(gradle.replace(start, "        getByName(\"debug\") {\n            $catalogKey\n        }\n$start")).isNotEmpty())
        val types = "    buildTypes {\n"
        assertTrue(gradle.count(types) == 1)
        for (opener in listOf("getByName(\"debug\") {", "debug {")) {
            assertTrue("debug minifies ($opener)", variantProblems(gradle.replace(types,
                types + "        $opener\n            isMinifyEnabled = true\n        }\n")).isNotEmpty())
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------

    internal companion object {
        const val FIXTURE = "src/e5dJourney"
        const val ACCESSOR = "currentPreviewHostHooks"
        const val ACCESSOR_FILE = "java/com/jay/fxi/ui/free/PreviewHostHooksJourney.kt"
        const val DEFAULT_ACCESSOR = "src/e5dHooksDefault/java/com/jay/fxi/ui/free/PreviewHostHooksDefault.kt"
        const val S5_SERVER = "src/s5Benchmark/java/com/jay/fxi/s5fixture/S5FixtureServer.kt"
        const val CONTROL = "com.jay.fxi.e5djourney.permission.CONTROL"
        const val TEST_BUILD = "../e5dJourney/build.gradle.kts"
        val FIXTURE_MANIFEST = File("$FIXTURE/AndroidManifest.xml")
        val TEST_MANIFEST = File("../e5dJourney/src/main/AndroidManifest.xml")
        private const val FIREBASE = "google-services.json"

        /** Relative to [FIXTURE]; google-services.json is a gitignored local cp and is left out, unread. */
        val EXPECTED_INVENTORY = setOf(
            "AndroidManifest.xml",
            "res/xml/network_security_config.xml",
            "java/com/jay/fxi/e5djourney/E5dJourneyAccess.kt",
            "java/com/jay/fxi/e5djourney/E5dJourneyControl.kt",
            "java/com/jay/fxi/e5djourney/E5dJourneyHooks.kt",
            "java/com/jay/fxi/e5djourney/E5dJourneyIdentity.kt",
            "java/com/jay/fxi/e5djourney/E5dJourneyLifecycle.kt",
            "java/com/jay/fxi/e5djourney/E5dJourneyModules.kt",
            "java/com/jay/fxi/e5djourney/E5dJourneyRecord.kt",
            "java/com/jay/fxi/e5djourney/E5dJourneyServer.kt",
            "java/com/jay/fxi/e5djourney/E5dJourneyStart.kt",
            ACCESSOR_FILE
        )

        /** Every tree a build type other than the journey's compiles or packages from (release has none yet; it is still read). */
        val OTHER_TREES = listOf("main", "productionBindings", "e5dHooksDefault", "debug", "release", "ciMinified", "benchmark", "s5Benchmark")
        val MERGED_VARIANTS = listOf("debug", "benchmark", "ciMinified", "s5Benchmark")
        private val READ = setOf("kt", "java", "xml")

        private fun walk(root: Path): List<Path> =
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) emptyList() else Files.walk(root).use { it.toList() }

        fun fixtureEntries(): Set<String> = walk(File(FIXTURE).toPath())
            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(it) }
            .map { File(FIXTURE).toPath().relativize(it).toString() }
            .filter { it != FIREBASE }
            .toSet()

        fun fixtureLinks(): Set<String> = walk(File(FIXTURE).toPath()).filter { Files.isSymbolicLink(it) }
            .map { File(FIXTURE).toPath().relativize(it).toString() }.toSet()

        fun fixtureTexts(): Map<String, String> = EXPECTED_INVENTORY.associateWith { File("$FIXTURE/$it").readText() }

        fun fixtureTextsByPath(): Map<String, String> = fixtureTexts().mapKeys { (path, _) -> "$FIXTURE/$path" }

        fun otherTreeTexts(): Map<String, String> = OTHER_TREES.flatMap { tree ->
            walk(File("src/$tree").toPath())
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.toFile().extension in READ }
                .map { it.toString() to it.toFile().readText() }
        }.toMap()

        fun gradle(): String = File("build.gradle.kts").readText()
        fun catalog(): String = File("../gradle/libs.versions.toml").readText()

        fun inventoryProblems(entries: Set<String>, links: Set<String>): List<String> =
            (EXPECTED_INVENTORY - entries).map { "missing $it" } + (entries - EXPECTED_INVENTORY).map { "unexpected $it" } +
                links.map { "link $it" }

        fun krxProblems(sources: Map<String, String>): List<String> =
            sources.filterValues { it.contains("krx", ignoreCase = true) }.keys.map { "KRX in $it" }

        class Declarations(val names: Set<String>, val problems: List<String>)

        /** One source line seen from the file's top level: whether it starts there, and its top-level text. */
        class TopLine(val number: Int, val raw: String, val startsAtTop: Boolean, val text: String)

        private class Frame(val kind: Char, var depth: Int = 0)

        /**
         * Each line of a Kotlin [source] with only the characters that sit at its top level: outside every string (templates
         * included), character literal and comment (nested block comments included), and outside every (), [] and {}. A line
         * starts at the top level when nothing is open before its first character.
         */
        fun topLevelLines(source: String): List<TopLine> {
            val raws = source.split('\n')
            val out = ArrayList<TopLine>()
            val frames = ArrayDeque<Frame>().apply { addLast(Frame('c')) }
            var block = 0
            var lineComment = false
            val text = StringBuilder()
            fun atTop() = frames.size == 1 && frames.first().depth == 0 && block == 0
            var startsAtTop = true
            var i = 0
            fun endLine() {
                out += TopLine(out.size + 1, raws[out.size], startsAtTop, text.toString())
                text.clear()
                lineComment = false
                startsAtTop = atTop()
            }
            while (i < source.length) {
                val c = source[i]
                val next = source.getOrNull(i + 1)
                if (c == '\n') { endLine(); i++; continue }
                if (lineComment) { i++; continue }
                if (block > 0) {
                    when {
                        c == '/' && next == '*' -> { block++; i += 2 }
                        c == '*' && next == '/' -> { block--; i += 2 }
                        else -> i++
                    }
                    continue
                }
                val frame = frames.last()
                when (frame.kind) {
                    'c', 't' -> when {
                        c == '/' && next == '/' -> { lineComment = true; i += 2 }
                        c == '/' && next == '*' -> { block = 1; i += 2 }
                        source.startsWith("\"\"\"", i) -> { frames.addLast(Frame('r')); i += 3 }
                        c == '"' -> { frames.addLast(Frame('s')); i++ }
                        c == '\'' -> {
                            var j = i + 1
                            while (j < source.length && source[j] != '\'') { if (source[j] == '\\') j++; j++ }
                            i = j + 1
                        }
                        c in "([{" -> { frame.depth++; i++ }
                        c in ")]}" -> {
                            if (frame.kind == 't' && c == '}' && frame.depth == 0) frames.removeLast() else frame.depth--
                            i++
                        }
                        else -> { if (atTop()) text.append(c); i++ }
                    }
                    's' -> when {
                        c == '\\' -> i += 2
                        c == '"' -> { frames.removeLast(); i++ }
                        c == '$' && next == '{' -> { frames.addLast(Frame('t')); i += 2 }
                        else -> i++
                    }
                    else -> when {
                        source.startsWith("\"\"\"", i) -> { frames.removeLast(); i += 3; while (source.getOrNull(i) == '"') i++ }
                        c == '$' && next == '{' -> { frames.addLast(Frame('t')); i += 2 }
                        else -> i++
                    }
                }
            }
            if (out.size < raws.size) endLine()
            return out
        }

        private val ANNOTATIONS = Regex("""^(?:@[\w.]+\s*)+""")
        private val DECLARATION = Regex(
            """^(?:(?:internal|private|public|data|sealed|abstract|open|enum|annotation|inline|value|suspend|operator|infix|tailrec|""" +
                """const|lateinit|external|fun)\s+)*(class|object|interface|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:(\w+)\.)?(\w+)"""
        )
        private val DECLARATION_WORD = Regex("""\b(class|object|interface|fun|val|var|typealias)\b""")
        private const val ACCESSOR_LINE = "internal fun currentPreviewHostHooks(): PreviewHostHooks {"

        /**
         * Every top-level declaration in the fixture's Kotlin (`s7e5dj_j2a.codex.r1.md` Q2-C, `s7e5dj_j2a.codex.r2.md` Q2-C), read
         * at the real top level ([topLevelLines]), each statement of a line on its own. A top-level line that is indented, a
         * statement this collector cannot read as a class, object, interface, function, property or type alias, a second
         * declaration after the first's block, or a declaration word left at the top level of a line that did not start there
         * is a problem: nothing is skipped silently. The shared accessor is not a fixture name: it must be exactly the one agreed
         * line, in [ACCESSOR_FILE], in package com.jay.fxi.ui.free.
         */
        fun declarations(sources: Map<String, String>): Declarations {
            val names = HashSet<String>()
            val problems = ArrayList<String>()
            val accessors = ArrayList<String>()
            for ((path, source) in sources.filterKeys { it.endsWith(".kt") }) {
                for (line in topLevelLines(source)) {
                    val where = "$path:${line.number}"
                    if (!line.startsAtTop) {
                        if (DECLARATION_WORD.containsMatchIn(line.text)) problems += "a declaration after a bracket at $where: ${line.raw}"
                        continue
                    }
                    if (line.text.isBlank()) continue
                    if (line.text[0].isWhitespace()) {
                        problems += "an indented or comment-led top-level line at $where: ${line.raw}"
                        continue
                    }
                    for (part in line.text.split(';')) {
                        val trimmed = part.trim()
                        // A package or import statement is passed over on its own; the rest of its line is still read.
                        if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) continue
                        val statement = trimmed.replaceFirst(ANNOTATIONS, "")
                        if (statement.isEmpty()) continue
                        val match = DECLARATION.find(statement)
                        val rest = match?.let { statement.substring(it.range.last + 1) }
                        val keyword = match?.groupValues?.get(1)
                        val closed = rest != null && (rest.isEmpty() || rest[0] in ": =<") &&
                            !(keyword in setOf("fun", "val", "var") && rest.startsWith("<")) && !DECLARATION_WORD.containsMatchIn(rest)
                        if (match == null || !closed) {
                            problems += "an unsupported top-level statement at $where: ${line.raw}"
                            continue
                        }
                        val name = match.groupValues[3]
                        if (name == ACCESSOR) accessors += "$path: ${line.raw}" else names += name
                    }
                }
            }
            val accessorPackage = sources[ACCESSOR_FILE]?.lines()?.firstOrNull { it.startsWith("package ") }
            if (accessors != listOf("$ACCESSOR_FILE: $ACCESSOR_LINE") || accessorPackage != "package com.jay.fxi.ui.free") {
                problems += "the shared accessor must be exactly '$ACCESSOR_LINE' in $ACCESSOR_FILE (package com.jay.fxi.ui.free): $accessors"
            }
            return Declarations(names, problems)
        }

        fun leakProblems(names: Set<String>, trees: Map<String, String>): List<String> = trees.flatMap { (path, text) ->
            val words = names.filter { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(text) }
            words.map { "$path names $it" } + if (text.contains("e5djourney", ignoreCase = true)) listOf("$path names the fixture") else emptyList()
        }

        private val MOCK_LINES = setOf(
            "testImplementation(libs.okhttp.mockwebserver)",
            "add(\"s5BenchmarkImplementation\", libs.okhttp.mockwebserver)",
            "add(\"e5dJourneyImplementation\", libs.okhttp.mockwebserver)"
        )

        fun mockWebServerProblems(gradle: String, catalog: String, sources: Map<String, String>): List<String> {
            val lines = gradle.lines().map(String::trim).filter { it.contains("mockwebserver", ignoreCase = true) && !it.startsWith("//") }
            val catalogLines = catalog.lines().filter { it.contains("mockwebserver", ignoreCase = true) }
            val imports = sources.filter { (path, text) ->
                text.contains("okhttp3.mockwebserver") && !path.startsWith("src/s5Benchmark/") && !path.startsWith("$FIXTURE/")
            }.keys
            return (lines.toSet() - MOCK_LINES).map { "gradle: $it" } + (MOCK_LINES - lines.toSet()).map { "gradle missing: $it" } +
                (if (lines.size != MOCK_LINES.size) listOf("gradle: ${lines.size} lines") else emptyList()) +
                (if (catalogLines.size != 1) listOf("catalog: $catalogLines") else emptyList()) +
                imports.map { "import in $it" }
        }

        private val SOURCE_LINES = listOf(
            "sourceSets {",
            "getByName(type).java.srcDir(\"src/productionBindings/java\")",
            "getByName(\"test\" + type.replaceFirstChar { it.uppercase() }).java.srcDir(\"src/testProductionBindings/java\")",
            "getByName(\"s5Benchmark\").assets.srcDir(\"src/benchmark/assets/s5_4\")",
            "getByName(\"e5dJourney\").assets.srcDir(\"src/test/resources/contracts/v2/free\")",
            "getByName(\"e5dJourney\").assets.srcDir(\"src/test/resources/contracts/v2/topic\")",
            "getByName(\"e5dJourney\").assets.srcDir(\"src/test/resources/contracts/v2/graph\")",
            "getByName(\"e5dJourney\").assets.srcDir(\"src/test/resources/contracts/v2/alerts\")",
            "getByName(type).java.srcDir(\"src/e5dHooksDefault/java\")",
            // Reads, never routes: the variant report's DSL resource directories (Q2-A).
            "lines.addAll(provider { ownSourceSets.flatMap { android.sourceSets.getByName(it).res.srcDirs }.map { location(\"res-dsl\", it) } })"
        )
        private val ROUTE = Regex("""srcDir|srcFile|SrcDir|SrcFile|sourceSets|setRoot|\.root\b|addStaticSourceDirectory|addGeneratedSourceDirectory|\.sources\.""")
        private val BINDINGS_BLOCK = listOf(
            "listOf(\"debug\", \"release\", \"ciMinified\", \"benchmark\").forEach { type ->",
            "getByName(type).java.srcDir(\"src/productionBindings/java\")"
        )
        private val HOOKS_BLOCK = listOf(
            "listOf(\"debug\", \"release\", \"ciMinified\", \"benchmark\", \"s5Benchmark\").forEach { type ->",
            "getByName(type).java.srcDir(\"src/e5dHooksDefault/java\")"
        )

        fun sourceSetProblems(gradle: String): List<String> {
            val lines = gradle.lines().map(String::trim).filter { !it.startsWith("//") }
            val routes = lines.filter { ROUTE.containsMatchIn(it) }
            fun once(block: List<String>) = lines.windowed(block.size).count { it == block } == 1
            return (if (routes != SOURCE_LINES) listOf("routes: $routes") else emptyList()) +
                (if (!once(BINDINGS_BLOCK)) listOf("productionBindings list") else emptyList()) +
                (if (!once(HOOKS_BLOCK)) listOf("e5dHooksDefault list") else emptyList())
        }

        fun linkProblems(): List<String> = (OTHER_TREES.map { "src/$it" } + FIXTURE).flatMap { root ->
            walk(File(root).toPath()).filter { Files.isSymbolicLink(it) }.map { "link $it" }
        }

        /** Path to the number of top-level `fun currentPreviewHostHooks(` declarations in it. */
        fun accessorDeclarations(sources: Map<String, String>): Map<String, Int> = sources.filterKeys { it.endsWith(".kt") }
            .mapValues { (_, text) -> Regex("""^(?:@\w+\s+)*(?:(?:internal|private|public)\s+)?fun\s+$ACCESSOR\(""", RegexOption.MULTILINE).findAll(text).count() }
            .filterValues { it > 0 }

        fun accessorProblems(declarations: Map<String, Int>): List<String> {
            fun count(prefix: String) = declarations.filterKeys { it.startsWith(prefix) }.values.sum()
            val expected = mapOf("src/main/" to 0, "src/e5dHooksDefault/" to 1, "$FIXTURE/" to 1)
            val problems = expected.mapNotNull { (prefix, want) -> count(prefix).takeIf { it != want }?.let { "$prefix declares $it" } }
            val elsewhere = declarations.keys.filter { path -> expected.keys.none { path.startsWith(it) } }
            return problems + elsewhere.map { "declared in $it" }
        }

        private val ANDROID = "http://schemas.android.com/apk/res/android"

        private fun parse(xml: String): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream()).documentElement

        private fun Element.all(tag: String): List<Element> =
            getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

        const val CONTROL_AUTHORITY = "com.jay.fxi.e5djourney.control"
        private const val CONTROL_PROVIDER = "com.jay.fxi.e5djourney.E5dJourneyControlProvider"

        /** J2b2 (D5, D8): the nonce check, then readiness, then the graph — in that order — and the state commands that wait. */
        val READY_LINES = listOf(
            "if (command != DISCOVER && expected != E5dJourneyRecord.nonce) return answer(REJECT, mapOf(\"reason\" to \"nonce\"))",
            "if (command in NEEDS_READY && !ready()) return answer(REJECT, mapOf(\"reason\" to \"not-ready\"))",
            "val entry by lazy { EntryPointAccessors.fromApplication(context.applicationContext, E5dJourneyEntryPoint::class.java) }"
        )
        const val NEEDS_READY_LINE = "private val NEEDS_READY = setOf(START, PHASE, ANSWER, SIGN_OUT, SIGN_IN, ARM, RELEASE, LOCAL_PREMIUM, ACCESS, SEED_LAST_BANK)"

        fun readinessProblems(source: String): List<String> {
            val lines = source.lines().map(String::trim)
            val at = READY_LINES.map { line -> lines.indexOf(line).takeIf { lines.count { it == line } == 1 } }
            return listOfNotNull(
                "order".takeUnless { at.all { it != null } && at.filterNotNull().zipWithNext().all { (a, b) -> b == a + 1 } },
                "waiting commands".takeUnless { lines.count { it == NEEDS_READY_LINE } == 1 }
            )
        }

        fun controlSource(): String = File("$FIXTURE/java/com/jay/fxi/e5djourney/E5dJourneyControl.kt").readText()

        /** The provider call's first statements: the caller's permission is checked before the command is looked at at all. */
        private val CALL_HEAD = listOf(
            "override fun call(method: String, arg: String?, extras: Bundle?): Bundle {",
            "val context = context ?: throw SecurityException(\"control: no context\")",
            "if (context.checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) throw SecurityException(\"control: ${'$'}PERMISSION is required\")",
            "return synchronized(serial) { E5dJourneyControl.handle(context, method, extras ?: Bundle()) }"
        )

        fun permissionProblems(fixtureManifest: String, testManifest: String, controlSource: String): List<String> {
            val fixture = parse(fixtureManifest)
            val application = fixture.all("application").single()
            val permissions = fixture.all("permission").filter { it.getAttributeNS(ANDROID, "name") == CONTROL }
            val providers = fixture.all("provider").filter { it.getAttributeNS(ANDROID, "name") == CONTROL_PROVIDER }
            val oldReceivers = fixture.all("receiver").filter { "E5dJourneyControl" in it.getAttributeNS(ANDROID, "name") }
            val asks = parse(testManifest).all("uses-permission").count { it.getAttributeNS(ANDROID, "name") == CONTROL }
            val source = controlSource.lines().map(String::trim)
            return listOfNotNull(
                "permission".takeUnless { permissions.size == 1 && permissions.single().getAttributeNS(ANDROID, "protectionLevel") == "signature" },
                "provider".takeUnless {
                    providers.size == 1 && providers.single().getAttributeNS(ANDROID, "authorities") == CONTROL_AUTHORITY &&
                        providers.single().getAttributeNS(ANDROID, "exported") == "true" &&
                        providers.single().getAttributeNS(ANDROID, "permission") == CONTROL &&
                        !providers.single().hasAttributeNS(ANDROID, "process") && !application.hasAttributeNS(ANDROID, "process")
                },
                "old receiver".takeUnless { oldReceivers.isEmpty() },
                "test APK".takeUnless { asks == 1 },
                "call check".takeUnless { source.windowed(CALL_HEAD.size).count { it == CALL_HEAD } == 1 },
                "permission name".takeUnless { source.count { it == "const val PERMISSION = \"$CONTROL\"" } == 1 }
            )
        }

        private val MERGED_CHECK = listOf(
            "val control = \"com.jay.fxi.e5djourney.permission.CONTROL\"",
            "val permissions = elements(manifest, \"permission\").filter { it.getAttributeNS(ns, \"name\") == control }",
            "check(permissions.size == 1 && permissions.single().getAttributeNS(ns, \"protectionLevel\") == \"signature\") {"
        )
        private val MERGED_BINDING = listOf(
            "val controls = elements(application, \"provider\").filter { it.getAttributeNS(ns, \"name\") == \"com.jay.fxi.e5djourney.E5dJourneyControlProvider\" }",
            "check(controls.size == 1 && controls.single().getAttributeNS(ns, \"authorities\") == \"com.jay.fxi.e5djourney.control\" &&",
            "controls.single().getAttributeNS(ns, \"exported\") == \"true\" && controls.single().getAttributeNS(ns, \"permission\") == control &&",
            "!controls.single().hasAttributeNS(ns, \"process\") && !application.hasAttributeNS(ns, \"process\")) {"
        )
        private val MERGED_NO_RECEIVER = listOf(
            "check(elements(application, \"receiver\").none { it.getAttributeNS(ns, \"name\").contains(\"E5dJourneyControl\") }) {",
            "\"e5dJourney: the old control receiver is still declared\""
        )
        private const val TEST_HOOK = "dependsOn(\":app:verifyE5dJourneyManifest\", \":app:verifyE5dJourneyFirebaseFixture\")"

        fun mergedCheckProblems(gradle: String, testBuild: String): List<String> {
            val lines = gradle.lines().map(String::trim)
            fun once(block: List<String>) = lines.windowed(block.size).count { it == block } == 1
            return listOfNotNull(
                "permission check".takeUnless { once(MERGED_CHECK) },
                "provider check".takeUnless { once(MERGED_BINDING) },
                "old receiver check".takeUnless { once(MERGED_NO_RECEIVER) },
                "test module hook".takeUnless { testBuild.lines().map(String::trim).count { it == TEST_HOOK } == 1 }
            )
        }

        /** The lines of the `create(name)` or `getByName(name)` build type block, braces balanced; empty if absent. */
        private fun block(gradle: String, opener: String): List<String> {
            val lines = gradle.lines()
            val from = lines.indexOfFirst { it.trim() == opener }
            if (from < 0) return emptyList()
            var depth = 0
            val out = ArrayList<String>()
            for (line in lines.drop(from)) {
                out += line.trim()
                depth += line.count { it == '{' } - line.count { it == '}' }
                if (depth == 0) break
            }
            return out
        }

        fun variantProblems(gradle: String): List<String> {
            val journey = block(gradle, "create(\"e5dJourney\") {")
            val debug = block(gradle, "getByName(\"debug\") {") + block(gradle, "debug {")
            val minify = Regex("""isMinifyEnabled\s*=\s*true|isShrinkResources\s*=\s*true|minifyEnabled|shrinkResources""")
            val keys = gradle.lines().map(String::trim).filter { it.contains("E5DJ_") }
            return listOfNotNull(
                "journey block".takeUnless { journey.size > 2 && journey[1] == "initWith(getByName(\"debug\"))" },
                // J2b2 (`s7e5dj_j2b2_design.codex.r2.md` §2-1): the two graph asset keys, in the journey block and nowhere else.
                "journey asset keys".takeUnless { keys == ASSET_KEY_LINES && journey.containsAll(ASSET_KEY_LINES) },
                "journey minifies".takeIf { journey.any { minify.containsMatchIn(it) } },
                "debug minifies".takeIf { debug.any { minify.containsMatchIn(it) } }
            )
        }

        /** The two graph contract documents the journey fixture serves, named only in its build type (J2b2). */
        val ASSET_KEY_LINES = listOf(
            "buildConfigField(\"String\", \"E5DJ_GRAPH_CATALOG_ASSET\", \"\\\"catalog-krx-hidden.json\\\"\")",
            "buildConfigField(\"String\", \"E5DJ_GRAPH_USD_1D_ASSET\", \"\\\"usd-1d-krx-hidden.json\\\"\")"
        )

        /** The app module directory, the Test task's working directory, canonical. */
        val APP: Path = File(".").canonicalFile.toPath()
        const val JOURNEY = "e5dJourney"
        val MODEL_VARIANTS = listOf("debug", "release", "ciMinified", "benchmark", "s5Benchmark", JOURNEY)
        private val LOCATION_KINDS = setOf("java", "kotlin", "resources", "res", "res-dsl", "assets", "manifest")
        private val VALUE_KEYS = setOf("debuggable", "minify", "shrink", "initScripts", "gradleUserHome", "includedBuilds")

        /**
         * The init scripts CI's gradle/actions/setup-gradle@v5 writes into the Gradle user home's init.d (copyInitScripts in its
         * sources/src/caching/gradle-user-home-cache.ts; the two .groovy files it writes beside them are not init scripts).
         */
        val CI_INIT_SCRIPTS = setOf(
            "gradle-actions.build-result-capture.init.gradle",
            "gradle-actions.github-dependency-graph.init.gradle",
            "gradle-actions.inject-develocity.init.gradle"
        )

        data class Location(val kind: String, val lexical: Path, val canonical: Path)
        data class VariantReport(
            val name: String,
            val buildType: String,
            val values: Map<String, String>,
            val locations: List<Location>,
            val initScripts: List<Path> = emptyList()
        )

        /** `reportE5dJourneyVariantInputs`'s six reports, a Test input; null where one is missing. */
        fun variantReports(): Map<String, VariantReport?> = MODEL_VARIANTS.associateWith { variant ->
            File("build/intermediates/e5dj_variant_inputs/$variant.txt").takeIf { it.isFile }?.let { parseReport(it.readText()) }
        }

        /** One report; a row it does not know is kept as `unparsed` and fails [modelProblems]. */
        fun parseReport(text: String): VariantReport {
            val rows = text.lines().filter { it.isNotEmpty() }.map { it.split('\t') }
            val head = rows.firstOrNull()?.takeIf { it.size == 3 && it[0] == "variant" } ?: listOf("variant", "?", "?")
            val values = LinkedHashMap<String, String>()
            val locations = ArrayList<Location>()
            val initScripts = ArrayList<Path>()
            rows.drop(1).forEachIndexed { index, row ->
                when {
                    row.size == 2 && row[0] in VALUE_KEYS -> values[row[0]] = row[1]
                    row.size == 2 && row[0] in LOCATION_KINDS && row[1] == "absent" -> values["absent:${row[0]}"] = "true"
                    row.size == 3 && row[0] in LOCATION_KINDS -> locations += Location(row[0], Path.of(row[1]), Path.of(row[2]))
                    row.size == 2 && row[0] == "initScript" -> initScripts.add(Path.of(row[1]))
                    else -> values["unparsed:$index"] = row.joinToString("\t")
                }
            }
            return VariantReport(head[1], head[2], values, locations, initScripts)
        }

        private val accessorScan = HashMap<Path, Set<Path>>()

        /** The files under [dirs] that declare the accessor, read once per directory without following links. */
        private fun accessorFiles(dirs: Collection<Path>): Set<Path> = dirs.flatMap { dir ->
            accessorScan.getOrPut(dir) {
                walk(dir).filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.toFile().extension in setOf("kt", "java") }
                    .filter { Regex("""^(?:@\w+\s+)*(?:(?:internal|private|public)\s+)?fun\s+$ACCESSOR\(""", RegexOption.MULTILINE).containsMatchIn(it.toFile().readText()) }
                    .map { it.toFile().canonicalFile.toPath() }.toSet()
            }
        }.toSet()

        /**
         * Each kind's exact static inputs for [variant], relative to [APP] (`s7e5dj_j2a.codex.r2.md` Q2-A): AGP's own source-set
         * locations for main and the build type, the two shared directories exactly where the build script's two lists put them,
         * the two assets references, and for the model's resources the resValues generator's directory. Release has no model
         * resources: they cannot be read without running its generator, which is what the script boundary below stands in for.
         */
        fun expectedRoots(variant: String): Map<String, Set<String>> {
            val shared = when (variant) {
                JOURNEY -> emptyList()
                "s5Benchmark" -> listOf("src/e5dHooksDefault/java")
                else -> listOf("src/productionBindings/java", "src/e5dHooksDefault/java")
            }
            val assets = when (variant) {
                "s5Benchmark" -> listOf("src/benchmark/assets/s5_4")
                JOURNEY -> listOf("free", "topic", "graph", "alerts").map { "src/test/resources/contracts/v2/$it" }
                else -> emptyList()
            }
            val roots = mapOf(
                "java" to setOf("src/main/java", "src/$variant/java") + shared,
                "kotlin" to setOf("src/main/kotlin", "src/main/java", "src/$variant/kotlin", "src/$variant/java") + shared,
                "resources" to setOf("src/main/resources", "src/$variant/resources"),
                "assets" to setOf("src/main/assets", "src/$variant/assets") + assets,
                "res-dsl" to setOf("src/main/res", "src/$variant/res"),
                "manifest" to setOf("src/main/AndroidManifest.xml", "src/$variant/AndroidManifest.xml")
            )
            return if (variant == "release") roots else roots + ("res" to setOf("build/generated/res/resValues/$variant", "src/main/res", "src/$variant/res"))
        }

        /**
         * The invocation's init scripts (`s7e5dj_j2a.codex.r2.md` Q2-A, amended for CI): the reported count equals the paths
         * listed, no path is listed twice, and each is one of [CI_INIT_SCRIPTS] directly in the reported Gradle user home's
         * init.d. Their contents are not read: like the pinned external plugins, they are an external tool's, outside the
         * reviewed local script boundary.
         */
        fun initScriptProblems(variant: String, report: VariantReport): List<String> {
            val problems = ArrayList<String>()
            val listed = report.initScripts
            if (report.values["initScripts"] != listed.size.toString()) problems += "$variant: initScripts=${report.values["initScripts"]}, ${listed.size} listed"
            if (listed.toSet().size != listed.size) problems += "$variant: an init script listed twice"
            val initD = report.values["gradleUserHome"]?.let { Path.of(it).resolve("init.d") }
            for (script in listed) {
                if (initD == null || script.parent != initD || (script.fileName?.toString() ?: "") !in CI_INIT_SCRIPTS) {
                    problems += "$variant: init script $script"
                }
            }
            return problems
        }

        /**
         * R6 ③④ on what AGP resolved (`s7e5dj_j2a.codex.r1.md` Q2-A): each of the six reports present and its own; every kind's
         * inputs exactly [expectedRoots], so an extra root is refused even when it holds no accessor (`s7e5dj_j2a.codex.r2.md`);
         * no included build in the invocation and no init script but CI's ([initScriptProblems]); no location
         * reached through an alias; no variant but the journey's with a location in the fixture or the test module, and the
         * journey's with none in the shared bindings or default-accessor directories; the model's resource layers equal the DSL's
         * where both are read (generated ones aside); each variant compiling exactly its one accessor; and the journey debuggable,
         * unminified, unshrunk, with its own java, res and manifest.
         */
        fun modelProblems(reports: Map<String, VariantReport?>): List<String> {
            val problems = ArrayList<String>()
            val fixtureRoots = listOf(APP.resolve(FIXTURE), APP.parent.resolve("e5dJourney"))
            val shared = listOf(APP.resolve("src/productionBindings"), APP.resolve("src/e5dHooksDefault"))
            for (variant in MODEL_VARIANTS) {
                val report = reports[variant]
                if (report == null) {
                    problems += "$variant: no report"
                    continue
                }
                if (report.name != variant || report.buildType != variant) problems += "$variant: the report is ${report.name}/${report.buildType}'s"
                report.values.filterKeys { it.startsWith("unparsed") }.values.forEach { problems += "$variant: unparsed row $it" }
                (VALUE_KEYS - report.values.keys).forEach { problems += "$variant: no $it" }
                if (report.values["includedBuilds"] != "0") problems += "$variant: includedBuilds=${report.values["includedBuilds"]}"
                problems += initScriptProblems(variant, report)
                val expected = expectedRoots(variant)
                val kinds = expected.keys + report.locations.map { it.kind }
                for (kind in kinds) {
                    val found = report.locations.filter { it.kind == kind }.map { APP.relativize(it.canonical).toString().replace('\\', '/') }.toSet()
                    val want = expected[kind].orEmpty()
                    if (found != want) problems += "$variant: $kind ${found - want} unexpected, ${want - found} missing"
                }
                for (location in report.locations) {
                    fun under(roots: List<Path>) = roots.any { location.lexical.startsWith(it) || location.canonical.startsWith(it) }
                    if (location.lexical != location.canonical) problems += "$variant: ${location.kind} ${location.lexical} is an alias of ${location.canonical}"
                    if (variant != JOURNEY && under(fixtureRoots)) problems += "$variant: ${location.kind} ${location.canonical} is the fixture's"
                    if (variant == JOURNEY && under(shared)) problems += "$variant: ${location.kind} ${location.canonical} is a shared directory"
                }
                val modelRes = report.locations.filter { it.kind == "res" && !it.canonical.startsWith(APP.resolve("build")) }.map { it.canonical }.toSet()
                val dslRes = report.locations.filter { it.kind == "res-dsl" }.map { it.canonical }.toSet()
                if (variant != "release" && modelRes != dslRes) problems += "$variant: the model's res $modelRes is not the DSL's $dslRes"
                if (dslRes.isEmpty()) problems += "$variant: no res-dsl"
                val accessors = accessorFiles(report.locations.filter { it.kind == "java" || it.kind == "kotlin" }.map { it.canonical })
                val want = if (variant == JOURNEY) APP.resolve("$FIXTURE/$ACCESSOR_FILE") else APP.resolve(DEFAULT_ACCESSOR)
                if (accessors != setOf(want)) problems += "$variant: compiles the accessors $accessors, not $want"
            }
            reports[JOURNEY]?.let { journey ->
                if (journey.values["debuggable"] != "true" || journey.values["minify"] != "false" || journey.values["shrink"] != "false") {
                    problems += "$JOURNEY: ${journey.values}"
                }
                for ((kind, path) in listOf("java" to "java", "res-dsl" to "res", "manifest" to "AndroidManifest.xml")) {
                    if (journey.locations.none { it.kind == kind && it.canonical == APP.resolve("$FIXTURE/$path") }) problems += "$JOURNEY: no $kind $path"
                }
            }
            return problems
        }

        /** The repository root: the app module's parent. */
        val REPO: Path = APP.parent
        val EXPECTED_SCRIPTS = setOf("app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "macrobenchmark/build.gradle.kts",
            "e5dJourney/build.gradle.kts")
        private val SKIPPED_DIRS = setOf("build", ".gradle", ".git", ".idea", ".kotlin")
        private val SCRIPT_ROUTE = Regex(
            """androidComponents|onVariants|beforeVariants|\bsources\b|sourceSets|srcDir|SrcDir|setRoot|addStatic|addGenerated|subprojects|""" +
                """allprojects|afterEvaluate|projectsEvaluated|evaluationDependsOn|\bproject\(|includeBuild|apply\s*\(\s*from|apply\s+from|initscript"""
        )
        private val SCRIPT_ALLOWED = mapOf(
            "e5dJourney/build.gradle.kts" to listOf("androidComponents {", "beforeVariants(selector().withBuildType(\"debug\")) { it.enable = false }")
        )

        /** Every Gradle script under [REPO], by name only, outside build and tool directories. */
        fun repoScripts(): Set<String> {
            val found = HashSet<String>()
            Files.walkFileTree(REPO, object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
                    if (dir != REPO && dir.fileName.toString() in SKIPPED_DIRS) java.nio.file.FileVisitResult.SKIP_SUBTREE
                    else java.nio.file.FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    val name = file.fileName.toString()
                    if (name.endsWith(".gradle.kts") || name.endsWith(".gradle")) found += REPO.relativize(file).toString().replace('\\', '/')
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
            return found
        }

        /**
         * The script boundary the model cannot see for release resources (`s7e5dj_j2a.codex.r2.md` Q2-A): exactly the five
         * Gradle scripts, no buildSrc or build-logic, and no line in a script other than the app's (which has its own lock) that
         * touches variants or sources, configures another project or applies a script, beyond the journey module's one debug-off
         * filter. This check fixes the repository script-name set and the listed source/project lines. Together with tokenProblems,
         * applyProblems, absence of buildSrc/build-logic, zero invocation included builds and no invocation init script but
         * CI's setup-gradle ones ([initScriptProblems]), it supplies the
         * reviewed local script boundary. It does not inspect generated all directories or prove the behavior of pinned external
         * plugins.
         */
        fun scriptProblems(scripts: Set<String>, texts: Map<String, String>, presentDirs: Set<String>): List<String> {
            val problems = ArrayList<String>()
            (scripts - EXPECTED_SCRIPTS).forEach { problems += "unexpected script $it" }
            (EXPECTED_SCRIPTS - scripts).forEach { problems += "missing script $it" }
            presentDirs.forEach { problems += "$it exists" }
            for ((path, text) in texts) {
                if (path == "app/build.gradle.kts") continue
                val lines = text.lines().map(String::trim).filter { !it.startsWith("//") && SCRIPT_ROUTE.containsMatchIn(it) }
                val allowed = SCRIPT_ALLOWED[path].orEmpty()
                if (lines != allowed) problems += "$path: $lines, not $allowed"
            }
            return problems
        }

        fun scriptTexts(): Map<String, String> = (EXPECTED_SCRIPTS - "app/build.gradle.kts").associateWith { REPO.resolve(it).toFile().readText() }

        fun allScriptTexts(): Map<String, String> = EXPECTED_SCRIPTS.associateWith { REPO.resolve(it).toFile().readText() }

        /**
         * [source]'s code with comments, character literals and string contents taken out (template code kept) and identifier
         * backticks dropped, across lines.
         */
        fun codeOnly(source: String): String {
            val out = StringBuilder()
            val frames = ArrayDeque<Frame>().apply { addLast(Frame('c')) }
            var block = 0
            var lineComment = false
            var i = 0
            while (i < source.length) {
                val c = source[i]
                val next = source.getOrNull(i + 1)
                if (c == '\n') { out.append('\n'); lineComment = false; i++; continue }
                if (lineComment) { i++; continue }
                if (block > 0) {
                    when {
                        c == '/' && next == '*' -> { block++; i += 2 }
                        c == '*' && next == '/' -> { block--; i += 2; if (block == 0) out.append(' ') }
                        else -> i++
                    }
                    continue
                }
                val frame = frames.last()
                when (frame.kind) {
                    'c', 't' -> when {
                        c == '/' && next == '/' -> { lineComment = true; i += 2 }
                        c == '/' && next == '*' -> { block = 1; i += 2 }
                        source.startsWith("\"\"\"", i) -> { frames.addLast(Frame('r')); out.append('"'); i += 3 }
                        c == '"' -> { frames.addLast(Frame('s')); out.append('"'); i++ }
                        c == '\'' -> {
                            var j = i + 1
                            while (j < source.length && source[j] != '\'') { if (source[j] == '\\') j++; j++ }
                            out.append("' '")
                            i = j + 1
                        }
                        c == '}' && frame.kind == 't' && frame.depth == 0 -> { frames.removeLast(); out.append(' '); i++ }
                        // An escaped identifier is the same identifier: `apply` is apply (`s7e5dj_j2a.codex.r4.md` A).
                        c == '`' -> i++
                        else -> {
                            if (c in "([{") frame.depth++ else if (c in ")]}") frame.depth--
                            out.append(c)
                            i++
                        }
                    }
                    's' -> when {
                        c == '\\' -> i += 2
                        c == '"' -> { frames.removeLast(); out.append('"'); i++ }
                        c == '$' && next == '{' -> { frames.addLast(Frame('t')); out.append(' '); i += 2 }
                        else -> i++
                    }
                    else -> when {
                        source.startsWith("\"\"\"", i) -> { frames.removeLast(); out.append('"'); i += 3; while (source.getOrNull(i) == '"') i++ }
                        c == '$' && next == '{' -> { frames.addLast(Frame('t')); out.append(' '); i += 2 }
                        else -> i++
                    }
                }
            }
            return out.toString()
        }

        /**
         * The tokens by which a script could reach another project, apply a script or plugin, or touch variants and sources —
         * counted in its code only, so a call split over lines counts the same (`s7e5dj_j2a.codex.r3.md` A).
         */
        val BOUNDARY_TOKENS = linkedMapOf(
            "apply(" to Regex("""\bapply\s*[(<]"""),
            "apply {" to Regex("""\bapply\s*\{"""),
            "apply true/false" to Regex("""\bapply\s+(?:true|false)\b"""),
            "from" to Regex("""\bfrom\s*[(=]"""),
            "pluginManager" to Regex("""\bpluginManager\b"""),
            "plugins {" to Regex("""\bplugins\s*\{"""),
            "id(" to Regex("""\bid\s*\("""),
            "alias(" to Regex("""\balias\s*\("""),
            "buildscript/initscript" to Regex("""\b(?:buildscript|initscript)\b"""),
            "include(" to Regex("""\binclude(?:Build|Flat)?\s*\(|::include(?:Build|Flat)?\b"""),
            "subprojects/allprojects" to Regex("""\b(?:subprojects|allprojects)\b"""),
            "evaluation hooks" to Regex("""\b(?:afterEvaluate|beforeEvaluate|projectsEvaluated|beforeProject|afterProject|evaluationDependsOn\w*)\b"""),
            "project(" to Regex("""\b(?:project|findProject)\s*\(|::(?:project|findProject)\b"""),
            "gradle." to Regex("""(?<![.\w])gradle\s*\."""),
            "androidComponents" to Regex("""\bandroidComponents\b"""),
            "variant hooks" to Regex("""\b(?:onVariants|beforeVariants|finalizeDsl|registerSourceType)\b"""),
            ".sources" to Regex("""\.sources\b"""),
            "sourceSets" to Regex("""\bsourceSets\b"""),
            "source paths" to Regex("""\b(?:srcDirs?|setSrcDirs|srcFile|setSrcFile|setRoot)\b"""),
            "source directory calls" to Regex("""\badd(?:Static|Generated)SourceDirectory\b"""),
            // A call by name through reflection hides the name in a string (`s7e5dj_j2a.codex.r4.md` A).
            "reflection" to Regex(
                """\b(?:getMethod|getDeclaredMethod|getMethods|getDeclaredMethods|invoke|callBy|members|declaredMembers|""" +
                    """declaredFunctions|memberFunctions|javaClass|forName|KFunction|KCallable)\b|::class"""
            )
        )

        /**
         * Every code line of [source] that names `apply` in any form — a call, a block, an infix `apply false`, an escaped
         * identifier or a callable reference — trimmed (`s7e5dj_j2a.codex.r4.md` A).
         */
        fun applyLines(source: String): List<String> =
            codeOnly(source).lines().filter { Regex("""\bapply\b""").containsMatchIn(it) }.map(String::trim)

        /**
         * The reviewed context of every `apply` in each script: the app's two Kotlin scope-function applies and the root's nine
         * plugin aliases declared without being applied. Any other `apply`, however written, is a new reach to review.
         */
        val APPROVED_APPLY_LINES: Map<String, List<String>> = mapOf(
            "app/build.gradle.kts" to listOf(
                "val localProperties = Properties().apply {",
                "fun parse(file: File) = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }"
            ),
            "build.gradle.kts" to listOf("android.application", "android.test", "kotlin.android", "kotlin.compose", "kotlin.serialization",
                "google.services", "firebase.crashlytics", "hilt", "ksp").map { "alias(libs.plugins.$it) apply false" },
            "settings.gradle.kts" to emptyList(),
            "macrobenchmark/build.gradle.kts" to emptyList(),
            "e5dJourney/build.gradle.kts" to emptyList()
        )

        fun applyProblems(texts: Map<String, String>): List<String> = EXPECTED_SCRIPTS.flatMap { path ->
            val found = texts[path]?.let { applyLines(it) }
            val approved = APPROVED_APPLY_LINES[path]
            if (found == approved) emptyList() else listOf("$path: apply lines $found, approved $approved")
        }

        fun boundaryTokens(source: String): Map<String, Int> {
            val code = codeOnly(source)
            return BOUNDARY_TOKENS.mapValues { (_, token) -> token.findAll(code).count() }.filterValues { it > 0 }
        }

        /** The approved count of each boundary token in each script, as reviewed; any other count is a new reach to review. */
        val APPROVED_TOKENS: Map<String, Map<String, Int>> = mapOf(
            // Two Kotlin scope-function applies; the variant report's androidComponents.onVariants (and its return label), its
            // variant.sources and DSL res read; the two source-set lists, two assets references and that res read; the release
            // gate's and the report's bare gradle receivers; the structure inputs' fileTree include; the report's one
            // `String::class.java` element type.
            "app/build.gradle.kts" to mapOf("apply {" to 2, "plugins {" to 1, "alias(" to 8, "include(" to 1, "gradle." to 6,
                "androidComponents" to 1, "variant hooks" to 2, ".sources" to 1, "sourceSets" to 2, "source paths" to 9, "reflection" to 1),
            // Nine plugin aliases, none applied here.
            "build.gradle.kts" to mapOf("apply true/false" to 9, "plugins {" to 1, "alias(" to 9),
            "settings.gradle.kts" to mapOf("include(" to 3),
            "macrobenchmark/build.gradle.kts" to mapOf("plugins {" to 1, "alias(" to 2),
            // The journey module's one debug-off filter.
            "e5dJourney/build.gradle.kts" to mapOf("plugins {" to 1, "alias(" to 2, "androidComponents" to 1, "variant hooks" to 1)
        )

        fun tokenProblems(texts: Map<String, String>): List<String> = EXPECTED_SCRIPTS.flatMap { path ->
            val found = texts[path]?.let { boundaryTokens(it) }
            val approved = APPROVED_TOKENS[path]
            if (found == approved) emptyList() else listOf("$path: boundary tokens $found, approved $approved")
        }

        fun presentBuildDirs(): Set<String> = setOf("buildSrc", "build-logic").filter { Files.exists(REPO.resolve(it), LinkOption.NOFOLLOW_LINKS) }.toSet()

        private fun String.count(part: String): Int = split(part).size - 1
    }
}
