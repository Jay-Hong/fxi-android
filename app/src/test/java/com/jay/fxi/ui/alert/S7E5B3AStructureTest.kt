package com.jay.fxi.ui.alert

import com.jay.fxi.PostS6ApprovedDelta
import com.jay.fxi.S6Krx3bOffWiringContractTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 E5b3a structure (s7e5b3_api_agreed.r1.md: proposal r2 D2–D3; gap hunt r1 G15·G21):
 * - X01: the sheet takes every displayed decision from [BankAlertEditor]: it starts, trims, picks, checks, words the repeat
 *   and colours the threshold through it, binds save and the hints to the check's fields, and computes none of them itself.
 * - X03 (impl review r1): the sheet is the platform's modal bottom sheet, once, as before E5b3a; no window or drag of its own.
 * - X02: the editor model is plain Kotlin and the sheet reaches no owner, source, permission helper, Context, store, network,
 *   lifecycle or effect; the sheet is internal and the model never names it.
 * Each lock has mutations it is shown to catch, beside the unaltered sources that it accepts.
 */
class S7E5B3AStructureTest {

    private companion object {
        val BASE = S6Krx3bOffWiringContractTest.BASE
        val SHEET = BASE + "ui/alert/AlertAddSheet.kt"
        val EDITOR = BASE + "ui/alert/BankAlertEditor.kt"

        /** Comment-free with every literal kept (the shared reader of the other structure locks). */
        fun code(path: String): String = PostS6ApprovedDelta.codeAndStrings(File(path).readText())
        fun count(text: String, pattern: String): Int = Regex(pattern).findAll(text).count()

        /** What the sheet calls on the model, and how often. */
        val SHEET_DELEGATION = listOf(
            """\bBankAlertEditor\s*\.\s*initial\s*\(""" to 1,
            """\bBankAlertEditor\s*\.\s*check\s*\(""" to 1,
            """\bBankAlertEditor\s*\.\s*sanitize\s*\(""" to 1,
            """\bBankAlertEditor\s*\.\s*onBankPicked\s*\(""" to 1,
            """\bBankAlertEditor\s*\.\s*tone\s*\(""" to 1,
            """\bBankAlertEditor\s*\.\s*BANK_CHOICES\b""" to 1,
            """\bBankAlertEditor\s*\.\s*REPEAT_CHOICES\b""" to 1)
        /** The check's fields the sheet must draw from. */
        val SHEET_BINDINGS = listOf(
            "save" to """\.\s*canSave\s*&&\s*!\s*saving\b""",
            "quote" to """\.\s*quote\b""",
            "range" to """\.\s*range\b""",
            "out-of-range" to """\.\s*outOfRange\b""",
            "duplicate" to """\.\s*duplicate\b""")
        /** What would mean the sheet judges by itself. */
        val SHEET_JUDGING = listOf(
            "parse" to """\btoDouble\w*\b|\btoFloat\w*\b|\btoBigDecimal\w*\b""",
            "compare" to """\babs\s*\(|\bfloor\s*\(|\bceil\s*\(|\bround\w*\s*\(|\bisFinite\b|\bisNaN\b""",
            "constants" to """\b0\.005\b|\*\s*0\.5\b|\*\s*1\.5\b""",
            "list-search" to """\bexisting\s*\.\s*(?:any|all|none|filter\w*|find\w*|first\w*|last\w*|count|indexOf\w*|contains)\b""",
            "own-choices" to """\bBank\s*\.\s*(?:entries|values)\b|\bRepeatInterval\s*\.\s*(?:entries|values|from)\b""")

        /** X02: what neither file may reach. */
        val SHARED_FORBIDDEN = listOf(
            "owner" to """\bBankAlertSettingsOwner\b|\bBankAlertSectionSource\w*|\bAlertFamily\w*|\bAlertSettingsAccess\b|\bAlertRateLimitFloor\b""",
            "permission" to """(?i)permission""",
            "view-model" to """\bAlertViewModel\b|\bAlertRepository\b|\bhiltViewModel\b|\bEntryPoint\w*\b""",
            "store" to """\bSharedPreferences\b|\bgetSharedPreferences\b|(?i)datastore|\bPreferenceManager\b""",
            "io" to """\bjava\s*\.\s*(?:io|nio|net)\b|\bFile\b|\bRetrofit\b|\bOkHttp\w*|\bHttpURLConnection\b|\bURL\s*\(""",
            "system" to """\bContext\b|\bLocalContext\b|\bgetSystemService\b|\bstartActivit(?:y|ies)\w*|\bIntent\b|\bSystem\s*\.|\bThread\b""",
            "lifecycle" to """\bLifecycle\w*|\blifecycle\b|\bProcessLifecycleOwner\b|\brepeatOnLifecycle\b""",
            "push" to """\bFirebaseMessaging\w*|\bPushNotificationManager\b|\bregisterIfNeeded\b|\bNotificationManager\w*""")
        /** The sheet starts nothing of its own: no effect, coroutine or flow. */
        val SHEET_EFFECTS = """\b(?:LaunchedEffect|DisposableEffect|SideEffect|rememberCoroutineScope|CoroutineScope|GlobalScope|launch|async|snapshotFlow|produceState|collectAsState\w*|MutableStateFlow|MutableSharedFlow)\b"""
        /** The model is plain Kotlin: no Compose, Android or coroutine import, and it never names the sheet. */
        val EDITOR_FORBIDDEN = """\bandroidx\s*\.|\bandroid\s*\.|\bkotlinx\s*\.\s*coroutines\b|\bAlertAddSheet\w*|@Composable\b|\bremember\w*\b|\bmutableStateOf\b"""
        val SHEET_DECLARATION = Regex("""(?m)^internal fun AlertAddSheet\s*\(""")

        fun x01(sheet: String): List<String> {
            val v = mutableListOf<String>()
            for ((pattern, want) in SHEET_DELEGATION) count(sheet, pattern).let { if (it != want) v += "X01-delegation $pattern $it" }
            if (count(sheet, """\bBankAlertEditor\s*\.\s*repeatTexts\s*\(""") < 1) v += "X01-delegation repeatTexts"
            for ((name, pattern) in SHEET_BINDINGS) if (count(sheet, pattern) < 1) v += "X01-binding $name"
            for ((name, pattern) in SHEET_JUDGING) if (count(sheet, pattern) > 0) v += "X01-judging $name"
            return v
        }

        fun x02(sheet: String, editor: String): List<String> {
            val v = mutableListOf<String>()
            for ((name, pattern) in SHARED_FORBIDDEN) {
                if (count(sheet, pattern) > 0) v += "X02-sheet $name"
                if (count(editor, pattern) > 0) v += "X02-editor $name"
            }
            if (count(sheet, SHEET_EFFECTS) > 0) v += "X02-sheet effects"
            if (count(editor, EDITOR_FORBIDDEN) > 0) v += "X02-editor plain"
            if (SHEET_DECLARATION.findAll(sheet).count() != 1) v += "X02-sheet internal"
            return v
        }

        val SHEET_HOST = """\bModalBottomSheet\s*\("""
        val SHEET_OWN_WINDOW = """\bDialog\s*\(|\bPopup\s*\(|\bDialogProperties\b|\bdraggable\b|\bDraggableState\b|\brememberDraggableState\b|\banchoredDraggable\b|\bswipeable\b"""

        fun x03(sheet: String): List<String> {
            val v = mutableListOf<String>()
            count(sheet, SHEET_HOST).let { if (it != 1) v += "X03-host $it" }
            if (count(sheet, SHEET_OWN_WINDOW) > 0) v += "X03-own-window"
            return v
        }

        fun edited(text: String, old: String, new: String): String {
            assertEquals("premise: the mutation's anchor occurs once: $old", 1, text.split(old).size - 1)
            return text.replace(old, new)
        }
    }

    private val sheet by lazy { code(SHEET) }
    private val editor by lazy { code(EDITOR) }

    @Test fun S7E5B3A_X01_theSheetTakesEveryDisplayedDecisionFromTheEditor() {
        assertEquals(emptyList<String>(), x01(sheet))
        val checkCall = Regex("""\bBankAlertEditor\s*\.\s*check\s*\([^)]*\)""").find(sheet)!!.value
        for ((label, mutated, expected) in listOf(
            Triple("the check replaced by a copied rule", edited(sheet, checkCall, "BankAlertEditorCheck(input.thresholdText.toDoubleOrNull(), null, null, false, false, true, true, true)"),
                listOf("X01-delegation \\bBankAlertEditor\\s*\\.\\s*check\\s*\\( 0", "X01-judging parse")),
            Triple("save from its own rule", edited(sheet, ".canSave && !saving", ".validSelection && input.thresholdText.isNotEmpty()"),
                listOf("X01-binding save")),
            Triple("a duplicate searched by the sheet", edited(sheet, "val canSave =", "val ownDuplicate = existing.any { it.bank == input.bank?.code }\n    val canSave ="),
                listOf("X01-judging list-search")),
            Triple("its own bank list", edited(sheet, "BankAlertEditor.BANK_CHOICES", "Bank.entries.toList()"),
                listOf("X01-delegation \\bBankAlertEditor\\s*\\.\\s*BANK_CHOICES\\b 0", "X01-judging own-choices")),
            Triple("its own colour compare", edited(sheet, "BankAlertEditor.tone(check)", "if (kotlin.math.abs(1.0) < 0.005) BankAlertThresholdTone.SAME else BankAlertThresholdTone.NONE"),
                listOf("X01-delegation \\bBankAlertEditor\\s*\\.\\s*tone\\s*\\( 0", "X01-judging compare", "X01-judging constants")))) {
            assertEquals(label, expected, x01(mutated))
        }
    }

    @Test fun S7E5B3A_X02_theModelIsPlainAndTheSheetReachesNothingButItsCallbacks() {
        assertEquals(emptyList<String>(), x02(sheet, editor))
        val sheetAnchor = "val canSave ="
        for ((label, mutated, expected) in listOf(
            Triple("a store write from the sheet", edited(sheet, sheetAnchor, "androidx.compose.ui.platform.LocalContext.current.getSharedPreferences(\"editor\", 0)\n    val canSave ="),
                listOf("X02-sheet store", "X02-sheet system")),
            Triple("the permission helper through a wrapper", edited(sheet, sheetAnchor, "val helper = rememberNotificationPermissionAction()\n    val canSave ="),
                listOf("X02-sheet permission")),
            Triple("an effect on opening", edited(sheet, sheetAnchor, "LaunchedEffect(Unit) { }\n    val canSave ="),
                listOf("X02-sheet effects")),
            Triple("an aliased owner", edited(sheet, sheetAnchor, "val o: com.jay.fxi.data.alerts.BankAlertSettingsOwner? = null\n    val canSave ="),
                listOf("X02-sheet owner")),
            Triple("a public sheet", sheet.replace("internal fun AlertAddSheet(", "fun AlertAddSheet("),
                listOf("X02-sheet internal")))) {
            assertEquals(label, expected, x02(mutated, editor))
        }
        val editorAnchor = "internal object BankAlertEditor {"
        for ((label, mutated, expected) in listOf(
            Triple("a file read in the model", edited(editor, editorAnchor, "private val log = java.io.File(\"x\")\n$editorAnchor"), listOf("X02-editor io")),
            Triple("Compose in the model", edited(editor, editorAnchor, "private val s = androidx.compose.runtime.mutableStateOf(0)\n$editorAnchor"), listOf("X02-editor plain")),
            Triple("the model naming the sheet", edited(editor, editorAnchor, "private val n = \"AlertAddSheet\"\n$editorAnchor"), listOf("X02-editor plain")))) {
            assertEquals(label, expected, x02(sheet, mutated))
        }
        assertTrue("premise: the sheet and the model are both read", sheet.contains("AlertAddSheet") && editor.contains("BankAlertEditor"))
    }

    @Test fun S7E5B3A_X03_theSheetIsThePlatformModalBottomSheet() {
        assertEquals(emptyList<String>(), x03(sheet))
        assertTrue("premise: the error dialog is AlertDialog, which X03 allows", count(sheet, """\bAlertDialog\s*\(""") == 1)
        for ((label, mutated, expected) in listOf(
            Triple("a dialog of its own", sheet.replace("ModalBottomSheet(", "androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {}\n    ModalBottomSheetStub("),
                listOf("X03-host 0", "X03-own-window")),
            Triple("its own drag", edited(sheet, "val canSave =", "val drag = rememberDraggableState { }\n    val canSave ="),
                listOf("X03-own-window")),
            Triple("a second sheet", edited(sheet, "val canSave =", "val second = @Composable { ModalBottomSheet(onDismissRequest = {}) {} }\n    val canSave ="),
                listOf("X03-host 2")))) {
            assertEquals(label, expected, x03(mutated))
        }
    }
}
