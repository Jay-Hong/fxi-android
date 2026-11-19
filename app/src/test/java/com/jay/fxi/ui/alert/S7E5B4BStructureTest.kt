package com.jay.fxi.ui.alert

import com.jay.fxi.PostS6ApprovedDelta
import com.jay.fxi.S6Krx3bOffWiringContractTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S7 E5b4b structure (`s7e5b4_api_agreed.r1.md` §3, 구조): what the device and JVM tests cannot see from outside — where the
 * remembered bank is prepared, guarded and read in the holder; that the owner's hand-over sits in the Add success's own Main
 * step; that the memory's acceptance does no file work; that the legacy deletion touches the prefix only; and that the
 * process starts the migration through its one cutover.
 *
 * Read from the comment-free code and literals of the six production roots ([S6Krx3bOffWiringContractTest.ROOTS]), squashed.
 */
class S7E5B4BStructureTest {

    @Test
    fun premise_everyProductionRootIsScanned() {
        assertTrue(S6Krx3bOffWiringContractTest.ROOTS.all { File(it).isDirectory } && code.size > 300)
    }

    /** H: one preparation effect, keyed by the session and the identity, that only prepares. */
    @Test
    fun theHolderPreparesOncePerSessionAndIdentity() {
        val holder = flat(HOLDER)
        assertEquals(1, count(holder, Regex.escape(PREPARE_EFFECT)))
        assertEquals("prepare 호출은 그 효과 하나", 1, count(holder, """\bsource\s*\.\s*prepareBankSelection\s*\("""))
        assertEquals("selection 수집은 하나", 1, count(holder, Regex.escape("val selection by source.bankSelection.collectAsState()")))
    }

    /** H: the Add area waits for this identity's finished preparation; the sheet's remaining keeps the raw availability. */
    @Test
    fun theAddAreaIsHiddenUntilTheSelectionSettles() {
        val holder = flat(HOLDER)
        assertEquals(1, count(holder, Regex.escape(ADD_AREA)))
        assertEquals(1, count(holder, Regex.escape("addAvailability = addAreaAvailability,")))
        assertEquals(1, count(holder, Regex.escape("val availability = bankAlertAddAvailability(settings)")))
        assertEquals(1, count(holder, Regex.escape(SETTLED)))
    }

    /**
     * H: the Add continuation installs only on this identity's Ready or Failed, with the seed read in the same step; an Edit
     * carries no seed; the sheet gets the open editor's seed; the outcome effect does nothing with the selection.
     */
    @Test
    fun theAddInstallIsGuardedAndSeeded_theEditIsNot() {
        val holder = flat(HOLDER)
        assertEquals(1, count(holder, Regex.escape(ADD_GUARD)))
        assertEquals(1, count(holder, Regex.escape(ADD_INSTALL)))
        assertEquals(1, count(holder, Regex.escape(EDIT_INSTALL)))
        assertEquals("editor 생성은 둘(클래스 선언 제외)", 2, count(holder, """(?<!class )\bOpenBankAlertEditor\s*\("""))
        assertEquals(1, count(holder, Regex.escape("addBank = open.seed,")))
        assertEquals("selection 을 그 자리에서 읽는 곳은 Add continuation 의 guard 와 seed 둘", 2, count(holder, """\bsource\.bankSelection\.value\b"""))
        val outcome = holder.substringAfter("LaunchedEffect(open, handle, outcome) {").substringBefore("AlertAddSheet(")
        assertTrue("outcome 효과를 못 찾았다", outcome.isNotEmpty() && outcome.length < holder.length)
        for (name in listOf("bankSelection", "prepareBankSelection", "acceptAddedBank", "seed")) {
            assertEquals("outcome 효과에 $name", 0, count(outcome, """\b$name\b"""))
        }
    }

    /** O: the hand-over sits between the state application and Saved, synchronous, once; nothing else names it. */
    @Test
    fun theOwnerHandsOverInTheAddSuccessStepOnly() {
        val owner = flat(OWNER)
        assertEquals(1, count(owner, Regex.escape(OWNER_STEP)))
        assertEquals("rememberAddedBank: 매개변수·호출·주입", 3, count(owner, """\brememberAddedBank\b"""))
        assertEquals("reportRememberFailure: 매개변수·호출·주입", 3, count(owner, """\breportRememberFailure\b"""))
        assertEquals("Saved 는 Add 성공과 Edit 성공 두 곳", 2, count(owner, Regex.escape("finish(BankAlertSaveOutcome.Saved(row.copy()))")))
    }

    /** M: the memory's acceptance publishes and queues; it does not suspend, switch thread or touch the store. */
    @Test
    fun theMemoryAcceptsWithoutFileWork() {
        val memory = flat(MEMORY)
        val accept = memory.substringAfter("fun acceptAddedBank(identity: AuthIdentityFence, bankCode: String) {").substringBefore(" fun markConsumerBound")
        assertTrue("acceptAddedBank 를 못 찾았다", accept.length < memory.length)
        assertEquals("suspend 로 선언됐다", 0, count(memory, """suspend fun acceptAddedBank"""))
        val outsideQueue = accept.substringBefore("queue.trySend")
        for (name in listOf("withContext", "launch", "store", "runBlocking", "await")) {
            assertEquals("접수 단계에 $name", 0, count(outsideQueue, """\b$name\b"""))
        }
        assertEquals(1, count(memory, Regex.escape("@Singleton internal class LastSelectedBankMemory internal constructor(")))
        assertEquals(1, count(memory, Regex.escape("@Inject constructor(store: DataStoreLastSelectedBankStore) : this(")))
    }

    /**
     * M (G01): the production memory's own context — the injected store as it is, Main.immediate for its steps, a process
     * IO+SupervisorJob worker, a fixed log — a published bound flag, no owner type; and both work catches rethrow cancellation.
     * These are this land's canonical forms, disclosed as such: the injected rigs elsewhere are no evidence of production.
     */
    @Test
    fun theProductionMemoryRunsInItsAgreedContext() {
        val memory = flat(MEMORY)
        assertEquals(1, count(memory, Regex.escape(MEMORY_INJECT)))
        assertEquals("@Inject 생성자는 하나", 1, count(memory, """@Inject\b"""))
        assertEquals(1, count(memory, Regex.escape("@Volatile private var bound = false")))
        assertEquals("memory 가 owner 를 안다", 0, count(memory, """\bBankAlertSettingsOwner\b"""))
        assertEquals("작업 catch 둘 다 취소를 다시 던진다", 2, count(memory, Regex.escape(RETHROW)))
        assertEquals("catch 절은 그 두 쌍뿐", 4, count(memory, """\bcatch\s*\("""))
    }

    /**
     * M (G01): a read's completion is one Main step — the guard and the publish in the same `withContext(main)` block with
     * nothing between that could suspend or defer — and the read sets the selection nowhere else.
     */
    @Test
    fun aReadsCompletionIsOneMainStep() {
        assertEquals(emptyList<String>(), completionViolations(flat(MEMORY)))
    }

    /** The completion lock refuses a guard done on IO with the publish alone on Main, a pause, or a deferral; it passes the real step. */
    @Test
    fun theCompletionLockCatchesASplitStep() {
        val real = flat(MEMORY)
        assertEquals("premise: 실제 단계가 하나", 1, count(real, Regex.escape(COMPLETION_STEP)))
        val publish = "state.value = result.fold(onSuccess = { BankSelectionSnapshot.Ready(identity, ticket, it) }, onFailure = { BankSelectionSnapshot.Failed(identity, ticket) })"
        val guard = "if (current is BankSelectionSnapshot.Loading && current.identity == identity && current.revision == ticket) {"
        for ((label, variant) in listOf(
            "guard on IO, publish alone on Main" to real.replace(COMPLETION_STEP, "val current = state.value $guard withContext(main) { $publish } }"),
            "a pause between guard and publish" to real.replace(COMPLETION_STEP, COMPLETION_STEP.replace("$guard state.value", "$guard yield() state.value")),
            "a publish deferred inside the guard" to real.replace(COMPLETION_STEP, COMPLETION_STEP.replace("$guard $publish", "$guard launch { $publish }")),
            "a second publish outside the step" to real.replace(COMPLETION_STEP, "$COMPLETION_STEP state.value = BankSelectionSnapshot.None")
        )) {
            assertTrue("fixture 가 바뀌지 않았다: $label", variant != real)
            assertTrue("놓쳤다: $label", completionViolations(variant).isNotEmpty())
        }
    }

    /** C (G01): the cutover is a singleton with one runner, an IO+SupervisorJob scope of its own and one stored job. */
    @Test
    fun theCutoverIsOneSingletonRunnerWithOneJob() {
        val migration = flat(MIGRATION)
        assertEquals(1, count(migration, Regex.escape("@Singleton internal class LastBankSelectionCutover internal constructor(")))
        assertEquals(1, count(migration, Regex.escape(CUTOVER_INJECT)))
        assertEquals(1, count(migration, Regex.escape(CUTOVER_LAUNCH)))
        assertEquals("runner 필드는 job 하나", 1, count(migration, """\bprivate var job: Job\? = null"""))
        assertEquals("migration 생성은 주입 생성자 하나", 1, count(migration, """(?<!class )\bLastBankSelectionMigration\("""))
        assertEquals("launch 는 하나", 1, count(migration, """\bscope\.launch\b"""))
    }

    /**
     * G07: the seed is declared with no default other than none on the editor, the sheet and the model, and the sheet's input
     * is remembered by mode alone — a later seed or quote does not rebuild an open editor's input.
     */
    @Test
    fun theSeedIsAnInitialValueOnly() {
        assertEquals(1, count(flat(HOLDER), Regex.escape(squash("""private class OpenBankAlertEditor(
            val mode: BankAlertEditorMode,
            val identity: AuthIdentityFence,
            val seed: String? = null
        )"""))))
        val sheet = flat(SHEET)
        assertEquals(1, count(sheet, Regex.escape("remaining: Int?, addBank: String? = null, onSubmit:")))
        assertEquals(1, count(sheet, Regex.escape("var input by remember(mode) { mutableStateOf(BankAlertEditor.initial(mode, quotes, addBank)) }")))
        assertEquals("addBank 는 선언과 그 초기값 하나에만", 2, count(sheet, """\baddBank\b"""))
        assertEquals(1, count(flat(EDITOR), Regex.escape("fun initial(mode: BankAlertEditorMode, quotes: Map<String, Double>, addBank: String? = null): BankAlertEditorInput =")))
    }

    /** L: the legacy deletion is the prefix's keys of the one fxi_cache instance, in one edit; the v1 calls stay where they were. */
    @Test
    fun theLegacyDeletionIsThePrefixOnly_andTheV1CallsStayPut() {
        assertEquals(1, count(flat(CACHE), Regex.escape(DELETE_LAST_BANK)))
        assertEquals(
            "v1 save/load 운영 caller 는 AlertViewModel 뿐",
            setOf(CACHE, VIEW_MODEL),
            code.filterValues { Regex("""\b(?:saveLastSelectedBank|loadLastSelectedBank)\b""").containsMatchIn(it) }.keys
        )
        assertEquals("deleteLastBankKeys 를 부르는 곳은 cutover 배선 하나", setOf(CACHE, MIGRATION),
            code.filterValues { Regex("""\bdeleteLastBankKeys\b""").containsMatchIn(it) }.keys)
    }

    /** A: the process starts the one cutover after the alert families; the cutover's readiness is the memory's. */
    @Test
    fun theApplicationStartsTheOneCutover() {
        val app = flat(APP)
        assertEquals(1, count(app, Regex.escape("launchLastBankMigration = { lastBankSelectionCutover.get().launch() },")))
        assertEquals(1, count(app, Regex.escape("@Inject internal lateinit var lastBankSelectionCutover: Provider<LastBankSelectionCutover>")))
        assertEquals(1, count(flat(MIGRATION), Regex.escape(CUTOVER_WIRING)))
        assertEquals("cutover 는 Application 에서만", setOf(MIGRATION, APP),
            code.filterValues { Regex("""\bLastBankSelectionCutover\b""").containsMatchIn(it) }.keys)
    }

    private companion object {
        val BASE = S6Krx3bOffWiringContractTest.BASE
        val HOLDER = BASE + "ui/alert/BankAlertSection.kt"
        val OWNER = BASE + "data/alerts/BankAlertSettingsOwner.kt"
        val MEMORY = BASE + "data/local/LastSelectedBankMemory.kt"
        val CACHE = BASE + "data/local/CacheService.kt"
        val SHEET = BASE + "ui/alert/AlertAddSheet.kt"
        val EDITOR = BASE + "ui/alert/BankAlertEditor.kt"
        val MIGRATION = BASE + "data/local/LastBankSelectionMigration.kt"
        val VIEW_MODEL = BASE + "ui/viewmodel/AlertViewModel.kt"
        val APP = BASE + "FXiApplication.kt"

        val PREPARE_EFFECT = squash("""LaunchedEffect(session, actionIdentity) {
            if (actionIdentity != null) source.prepareBankSelection(actionIdentity)
        }""")
        val ADD_AREA = squash("""val addAreaAvailability = if (bankSelectionSettled(selection, actionIdentity)) availability else BankAlertAddAvailability.Hidden""")
        val SETTLED = squash("""internal fun bankSelectionSettled(selection: BankSelectionSnapshot, identity: AuthIdentityFence?): Boolean = when (selection) {
            is BankSelectionSnapshot.Ready -> identity != null && selection.identity == identity
            is BankSelectionSnapshot.Failed -> identity != null && selection.identity == identity
            is BankSelectionSnapshot.Loading, BankSelectionSnapshot.None -> false
        }""")
        val ADD_GUARD = squash("""bankAlertAddAvailability(live) is BankAlertAddAvailability.Available &&
            bankSelectionSettled(source.bankSelection.value, captured)
        ) {""")
        val ADD_INSTALL = "session.editor = OpenBankAlertEditor(BankAlertEditorMode.Add, captured, bankSelectionSeed(source.bankSelection.value))"
        val EDIT_INSTALL = "session.editor = OpenBankAlertEditor(BankAlertEditorMode.Edit(row.copy()), identity)"
        val OWNER_STEP = squash("""mutableState.value = state.copy(settings = next)
            }
            try {
                rememberAddedBank(op.identity, row.bank)
            } catch (failure: Exception) {
                reportRememberFailure(failure)
            }
            op.handle.finish(BankAlertSaveOutcome.Saved(row.copy()))""")
        val DELETE_LAST_BANK = squash("""internal suspend fun deleteLastBankKeys() {
            context.dataStore.edit { preferences ->
                preferences.asMap().keys
                    .filter { it.name.startsWith(KEY_LAST_BANK_PREFIX) }
                    .forEach { preferences.remove(stringPreferencesKey(it.name)) }
            }
        }""")
        val MEMORY_INJECT = squash("""@Inject constructor(store: DataStoreLastSelectedBankStore) : this(
            store,
            Dispatchers.Main.immediate,
            CoroutineScope(Dispatchers.IO + SupervisorJob()),
            { cause -> Log.w("LastSelectedBankMemory", "Last selected bank work failed", cause) }
        )""")
        val RETHROW = "} catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {"
        val COMPLETION_STEP = squash("""withContext(main) {
            val current = state.value
            if (current is BankSelectionSnapshot.Loading && current.identity == identity && current.revision == ticket) {
                state.value = result.fold(
                    onSuccess = { BankSelectionSnapshot.Ready(identity, ticket, it) },
                    onFailure = { BankSelectionSnapshot.Failed(identity, ticket) }
                )
            }
        }""")
        val CUTOVER_INJECT = squash("""@Inject constructor(
            memory: LastSelectedBankMemory,
            cacheService: CacheService,
            @ApplicationContext context: Context
        ) : this(
            LastBankSelectionMigration(LocalMigrationJournal.from(context), memory::requireReady, cacheService::deleteLastBankKeys),
            CoroutineScope(Dispatchers.IO + SupervisorJob()),
            { cause -> Log.w("LastBankSelectionCutover", "Last bank selection migration failed", cause) }
        )""")
        val CUTOVER_LAUNCH = squash("""@Synchronized fun launch(): Job = job ?: scope.launch {
            try {
                migration.run()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                report(error)
            }
        }.also { job = it }""")
        val CUTOVER_WIRING = squash("""LastBankSelectionMigration(LocalMigrationJournal.from(context), memory::requireReady, cacheService::deleteLastBankKeys)""")

        val code: Map<String, String> by lazy {
            S6Krx3bOffWiringContractTest.ROOTS.flatMap { root ->
                File(root).walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
            }.associate { f ->
                val text = f.readText()
                f.invariantSeparatorsPath to if (f.extension == "kt") PostS6ApprovedDelta.codeAndStrings(text) else text
            }
        }

        /** The ways a memory's read completion can stop being one Main step; empty when it is one. */
        fun completionViolations(memory: String): List<String> {
            val start = "private suspend fun read(identity: AuthIdentityFence, ticket: Long, completion: CompletableDeferred<Unit>) {"
            if (count(memory, Regex.escape(start)) != 1) return listOf("read 선언")
            val body = memory.substringAfter(start).substringBefore("completion.complete(Unit)")
            val v = mutableListOf<String>()
            if (count(body, Regex.escape(COMPLETION_STEP)) != 1) v += "한 Main 단계"
            if (count(body, """\bstate\.value\b""") != 2) v += "단계 밖 selection 접근 ${count(body, """\bstate\.value\b""")}"
            if (count(body, """\b(?:launch|async|yield|delay|await|runBlocking)\b""") != 0) v += "중단·예약"
            if (count(body, """\bwithContext\b""") != 1) v += "withContext ${count(body, """\bwithContext\b""")}"
            return v
        }

        fun squash(text: String): String = Regex("""\s+""").replace(text, " ").replace("( ", "(").replace(" )", ")").trim()
        fun flat(path: String): String = squash(code.getValue(path))
        fun count(text: String, pattern: String): Int = Regex(pattern).findAll(text).count()
    }
}
