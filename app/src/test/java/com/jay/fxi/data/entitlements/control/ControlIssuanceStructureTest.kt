package com.jay.fxi.data.entitlements.control

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Raw-source trip-wires, not a Kotlin parser or a proof of random issuance. Identifier rows use
 * word boundaries, including occurrences in comments, strings, spaced calls and callable references.
 * Fixed wiring statements require a whole trimmed line; an inline comment cannot satisfy them.
 * Only moves to uncalled registration helpers were paired with behaviour tests. Helpers still
 * called by prepare methods or run and coordinated rewrites require review of the diff.
 *
 * fxi.seal.sourceRoot is a direct-runner override for an isolated app/src tree, validated for that
 * shape. Gradle uses discovery from its working directory. Neither path proves snapshot freshness.
 */
class ControlIssuanceStructureTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun mainSourceOccurrenceTableIsFixed() {
        SealSourceTripwire.verify(SealSourceTripwire.read(productionRoot()), expected)
    }

    @Test fun trackerConstructorAndCommandMapStayPrivate() {
        val source = SealSourceTripwire.read(productionRoot()).getValue(control + "ControlCommandTracking.kt")
        assertEquals(1, source.countLiteral("internal class ControlCommandTracking private constructor()"))
        assertEquals(1, source.countLiteral("private val commands = ConcurrentHashMap<String, TrackedControlCommand>()"))
        assertEquals(7, Regex("\\bcommands\\b").findAll(source).count()) // declaration, insertion, lookup, conditional removal, three comments
        assertEquals(1, source.countLiteral("commands.putIfAbsent(command.id, TrackedControlCommand(command))"))
    }

    @Test fun trackerLifetimeIssuanceAndOwnerDecisionSitesStayPinned() {
        val sources = SealSourceTripwire.read(productionRoot())
        val tracking = sources.getValue(control + "ControlCommandTracking.kt")
        val store = sources.getValue(control + "ControlRecordStore.kt")
        val ref = sources.getValue(control + "ControlStoreResult.kt")
        assertEquals(1, tracking.countExactLine("val lifetimeId = OwnerTrackingLifetimeId.issue()"))
        assertEquals(1, tracking.countExactLine("fun issue(): OwnerTrackingLifetimeId = OwnerTrackingLifetimeId(UUID.randomUUID().toString())"))
        assertEquals(1, tracking.countExactLine("check(command.ownerTrackingLifetimeId === lifetimeId) { \"command belongs to another tracker lifetime\" }"))
        assertEquals(1, ref.countExactLine("val ownerTrackingLifetimeId: OwnerTrackingLifetimeId"))
        assertEquals(4, store.countExactLine("tracking.observe(read)"))
        assertEquals(1, store.countExactLine("if (known != null) tracked.bindFirstConfirm(tracking.evidenceDiscontinuityCount)"))
        assertEquals(0, sources.filterKeys { it != control + "ControlCommandTracking.kt" }
            .values.sumOf { it.countLiteral("OwnerTrackingLifetimeId.issue()") })
    }

    @Test fun randomImplementationAndCorruptionSourceStayPinned() {
        val sources = SealSourceTripwire.read(productionRoot())
        assertEquals(1, sources.getValue(ent + "AccessEpochStore.kt")
            .countExactLine("val Random = EpochIdGenerator { UUID.randomUUID().toString() }"))
        assertEquals(1, sources.getValue(ent + "DataStoreAccessEpochStore.kt")
            .countExactLine("corruptionHandler = accessEpochCorruptionHandler(EpochIdGenerator.Random)"))
    }

    @Test fun injectedConstructorForwardsItsEpochGenerator() {
        val source = SealSourceTripwire.read(productionRoot()).getValue(ent + "DataStoreAccessEpochStore.kt")
        assertEquals(1, source.countExactLine(") : this(context.accessEpochDataStore, ids)"))
    }

    @Test fun fixedWiringRequiresAWholeLineInsteadOfACommentCopy() {
        val statement = ") : this(context.accessEpochDataStore, ids)"
        assertEquals(1, "    $statement\n".countExactLine(statement))
        for (source in listOf("// $statement", "changed() // $statement", "\"$statement\"")) {
            assertEquals(0, source.countExactLine(statement))
        }
    }

    @Test fun projectAndGradleWorkingDirectoriesResolveTheSameSourceRoot() {
        // The expected path is constructed independently of locate and the runner's override.
        val root = folder.newFolder("project")
        val expectedRoot = sourceTree(root)
        writeSource(expectedRoot, "main/java/Required.kt", "class Required")
        assertEquals(expectedRoot.canonicalFile, SealSourceTripwire.locate(root).canonicalFile)
        assertEquals(expectedRoot.canonicalFile, SealSourceTripwire.locate(File(root, "app")).canonicalFile)
        assertTrue(File(expectedRoot, "main/java/Required.kt").isFile)
    }

    @Test fun missingSourceRootIsAnExactFailure() {
        val missing = File(folder.root, "missing/app/src")
        expectFailure(IllegalStateException::class.java, "production source root missing: ${missing.path}") {
            SealSourceTripwire.read(missing)
        }
        assertTrue(SealSourceTripwire.read(productionRoot()).isNotEmpty())
    }

    @Test fun runnerOverrideRequiresAnExistingAppSrcTree() {
        val root = folder.newFolder("override-project")
        val valid = sourceTree(root)
        assertEquals(valid.canonicalFile, SealSourceTripwire.sourceRoot(root, valid.path).canonicalFile)
        val wrong = folder.newFolder("wrong-shape")
        expectFailure(IllegalStateException::class.java, "expected app/src root: ${wrong.path}") {
            SealSourceTripwire.sourceRoot(root, wrong.path)
        }
        val missing = File(root, "missing/app/src")
        expectFailure(IllegalStateException::class.java, "production source root missing: ${missing.path}") {
            SealSourceTripwire.sourceRoot(root, missing.path)
        }
    }

    @Test fun allProductionSourceSetsAndLanguagesAreScannedButTestSetsAreExcluded() {
        val root = sourceTree(folder.newFolder("source-sets"))
        val included = listOf("main", "debug", "benchmark", "ciMinified", "release", "custom")
            .flatMap { set -> listOf("$set/java/Probe.kt", "$set/kotlin/Probe.kt") }
        for (path in included) writeSource(root, path, "registerPrepared")
        for (set in listOf("test", "androidTest", "testFixtures")) {
            for (language in listOf("java", "kotlin")) writeSource(root, "$set/$language/Probe.kt", "excluded")
        }
        writeSource(root, "main/java/JavaProbe.java", "java-source")
        writeSource(root, "main/resources/NotCode.kt", "excluded-resource")
        val expectedSources = included.associateWith { "registerPrepared" } +
            ("main/java/JavaProbe.java" to "java-source")
        assertEquals(expectedSources, SealSourceTripwire.read(root))
    }

    @Test fun absentProductionDirectoriesAndEmptySourceFilesFailExplicitly() {
        val root = sourceTree(folder.newFolder("empty-source-tree"))
        writeSource(root, "test/java/TestOnly.kt", "test-only")
        expectFailure(IllegalStateException::class.java, "production source directories are empty: ${root.path}") {
            SealSourceTripwire.read(root)
        }
        assertTrue(File(root, "main/kotlin").mkdirs())
        expectFailure(IllegalStateException::class.java, "production source root is empty: ${root.path}") {
            SealSourceTripwire.read(root)
        }
        writeSource(root, "main/kotlin/Present.kt", "present")
        assertEquals(mapOf("main/kotlin/Present.kt" to "present"), SealSourceTripwire.read(root))
    }

    @Test fun missingRequiredFileAndZeroOccurrencesAreExactFailures() {
        val table = mapOf("symbol" to mapOf("Required.kt" to 1))
        expectFailure(IllegalStateException::class.java, "required source file missing: Required.kt") {
            SealSourceTripwire.verify(mapOf("Other.kt" to "symbol"), table)
        }
        expectFailure(IllegalStateException::class.java, "required symbol missing: symbol") {
            SealSourceTripwire.verify(mapOf("Required.kt" to "no match"), table)
        }
        SealSourceTripwire.verify(mapOf("Required.kt" to "symbol"), table)
    }

    @Test fun occurrencesInAnUnlistedFileAreRejected() {
        val table = mapOf("symbol" to mapOf("Required.kt" to 1))
        val source = mapOf("Required.kt" to "symbol", "Other.kt" to "symbol")
        expectFailure(AssertionError::class.java,
            "occurrences of symbol expected:<{Required.kt=1}> but was:<{Required.kt=1, Other.kt=1}>") {
            SealSourceTripwire.verify(source, table)
        }
        SealSourceTripwire.verify(source - "Other.kt", table)
    }

    @Test fun commentsStringsAndCallableReferencesAlsoCount() {
        val sources = mapOf("A.kt" to "symbol() // symbol\nval s = \"symbol\"\nval ref = ::symbol")
        assertEquals(mapOf("A.kt" to 4), SealSourceTripwire.occurrences(sources, "symbol"))
        SealSourceTripwire.verify(sources, mapOf("symbol" to mapOf("A.kt" to 4)))
        expectFailure(AssertionError::class.java,
            "occurrences of symbol expected:<{A.kt=1}> but was:<{A.kt=4}>") {
            SealSourceTripwire.verify(sources, mapOf("symbol" to mapOf("A.kt" to 1)))
        }
    }

    @Test fun identifierRowsIncludeSpacedCallsAndReferencesButNotLongerNames() {
        val source = "ControlRecordStore(owner)\nControlRecordStore (owner)\n::ControlRecordStore\n" +
            "ControlRecordStoreAlias xControlRecordStore \"ControlRecordStore\" // ControlRecordStore"
        assertEquals(mapOf("A.kt" to 5), SealSourceTripwire.occurrences(mapOf("A.kt" to source), "ControlRecordStore"))
        assertEquals(emptyMap<String, Int>(), SealSourceTripwire.occurrences(
            mapOf("A.kt" to "ControlRecordStoreAlias xControlRecordStore"), "ControlRecordStore"))
    }

    private fun sourceTree(project: File): File = File(project, "app/src").apply { check(mkdirs()) }
    private fun writeSource(root: File, path: String, contents: String) {
        File(root, path).apply { parentFile?.mkdirs(); writeText(contents) }
    }
    private fun expectFailure(type: Class<out Throwable>, message: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertEquals(type, failure?.javaClass)
        assertEquals(message, failure?.message)
    }

    companion object {
        private const val ent = "main/java/com/jay/fxi/data/entitlements/"
        private const val control = ent + "control/"
        private val expected = mapOf(
            "registerPrepared" to mapOf(control + "ControlCommandTracking.kt" to 1, control + "ControlRecordStore.kt" to 3),
            // Includes R metadata and N/L rejection return labels; no additional transaction call.
            "transactRecord" to mapOf(ent + "DataStoreAccessEpochStore.kt" to 1, control + "ControlRecordStore.kt" to 15),
            // Identifier rows include declarations, types, imports, comments and references.
            "RotateAndSettleNamespaces" to mapOf(control + "NamespaceSettlement.kt" to 1,
                control + "ControlCommand.kt" to 1, control + "ControlRecordStore.kt" to 1,
                control + "NamespaceSettlementTransition.kt" to 5),
            "ControlCommandTracking" to mapOf(control + "ControlCommandTracking.kt" to 4, control + "ControlRecordStore.kt" to 1),
            "EpochIdGenerator.Random" to mapOf("main/java/com/jay/fxi/di/EntitlementsModule.kt" to 1, ent + "DataStoreAccessEpochStore.kt" to 1),
            "UUID::randomUUID" to mapOf(control + "ControlRecordStore.kt" to 1),
            "ControlRecordStore" to mapOf(control + "ControlRecordStore.kt" to 1, control + "NamespaceSettlementTransition.kt" to 1,
                control + "RetiredNamespaceSettlementTransition.kt" to 1),
            "RotateAndSettle" to mapOf(control + "ControlCommand.kt" to 1, control + "ControlRecordStore.kt" to 2,
                control + "ControlStoreResult.kt" to 1, control + "ControlAppliedEvidence.kt" to 1)
        )

        private fun productionRoot(): File = SealSourceTripwire.sourceRoot(
            File(checkNotNull(System.getProperty("user.dir"))), System.getProperty("fxi.seal.sourceRoot"))
    }
}

private fun String.countLiteral(symbol: String): Int = Regex(Regex.escape(symbol)).findAll(this).count()
private fun String.countExactLine(statement: String): Int = lines().count { it.trim() == statement }
private fun String.countTokenOrLiteral(symbol: String): Int {
    val pattern = if (symbol.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")))
        Regex("\\b${Regex.escape(symbol)}\\b") else Regex(Regex.escape(symbol))
    return pattern.findAll(this).count()
}

internal object SealSourceTripwire {
    fun locate(start: File): File = generateSequence(start.absoluteFile) { it.parentFile }
        .map { File(it, "app/src") }.firstOrNull { it.isDirectory }
        ?: error("cannot locate app/src from: ${start.path}")

    fun sourceRoot(start: File, override: String?): File =
        if (override == null) locate(start) else File(override).also { requireSourceRoot(it) }

    private fun requireSourceRoot(root: File) {
        check(root.isDirectory) { "production source root missing: ${root.path}" }
        check(root.name == "src" && root.parentFile?.name == "app") { "expected app/src root: ${root.path}" }
    }

    fun read(root: File): Map<String, String> {
        requireSourceRoot(root)
        val sourceSets = root.listFiles().orEmpty().filter { it.isDirectory && it.name !in setOf("test", "androidTest", "testFixtures") }
        val roots = sourceSets.flatMap { set -> listOf(File(set, "java"), File(set, "kotlin")) }.filter { it.isDirectory }
        check(roots.isNotEmpty()) { "production source directories are empty: ${root.path}" }
        val files = roots.flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList() }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
        check(files.isNotEmpty()) { "production source root is empty: ${root.path}" }
        return files
    }

    fun occurrences(sources: Map<String, String>, symbol: String): Map<String, Int> =
        sources.mapValues { (_, source) -> source.countTokenOrLiteral(symbol) }.filterValues { it > 0 }

    fun verify(sources: Map<String, String>, expected: Map<String, Map<String, Int>>) {
        check(expected.isNotEmpty()) { "occurrence table is empty" }
        for ((symbol, files) in expected) {
            for (file in files.keys) check(file in sources) { "required source file missing: $file" }
            val actual = occurrences(sources, symbol)
            check(actual.values.sum() > 0) { "required symbol missing: $symbol" }
            assertEquals("occurrences of $symbol", files, actual)
        }
    }
}
