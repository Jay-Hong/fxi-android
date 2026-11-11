package com.jay.fxi.ui.alert

import com.jay.fxi.PostS6ApprovedDelta
import com.jay.fxi.S6Krx3bOffWiringContractTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 step (c) structure contract (`R4c/S7/s7c_api_proposal.claude.r4.md` §4 S7C_N01·N02, Codex r2 C06/C10): where the
 * bank history can be reached from, what its file never touches, and that its singletons are the shared ones. Read from the
 * comment-free production sources of every root, as the S6 scans read them. The implementation thread reads but does not edit
 * this file.
 */
class BankHistoryWiringStructureTest {
    private val base = S6Krx3bOffWiringContractTest.BASE
    private val entry = base + "ui/alert/BankHistoryEntry.kt"
    private val route = base + "ui/premium/view/PremiumTopicRoute.kt"
    private val root = base + "ui/screen/RootScreen.kt"
    private val revisions = base + "data/repository/DeletionAdmissionRevisions.kt"
    private val settings = base + "ui/settings/SettingsViewModel.kt"

    private val code: Map<String, String> = S6Krx3bOffWiringContractTest.ROOTS.flatMap { r ->
        File(r).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }.associate { it.invariantSeparatorsPath to PostS6ApprovedDelta.codeAndStrings(it.readText()) }

    private fun files(pattern: String) = code.filterValues { Regex(pattern).containsMatchIn(it) }.keys
    private fun count(path: String, pattern: String) = Regex(pattern).findAll(code.getValue(path)).count()

    /**
     * S7C_N01s: only the premium route mounts the entry and names the production wiring, and only the armed root, past the D24
     * gate, mounts that route: free, no-grant and OFF processes never compose it (their Root branches are RootDestinationTest's and
     * the OFF start gate FXiApplicationStartTest's), so they make, resolve and read nothing of it.
     */
    @Test fun S7C_N01s_theEntryIsReachableFromThePremiumRouteOnly() {
        assertTrue("premise: the scan sees the files", listOf(entry, route, root, revisions, settings).all { it in code } && code.size > 100)
        assertEquals("the entry is mounted by the route only", setOf(route), files("""\bBankHistoryEntry\(""") - entry)
        assertEquals("the production wiring is named by the route's default and its own file", setOf(route, entry),
            files("""\bProductionBankHistoryWiring\b"""))
        assertEquals("the bank entry point is resolved in its own file only", setOf(entry), files("""\bBankHistoryEntryPoint\b"""))
        assertEquals("one resolution", 1, count(entry, """EntryPointAccessors\.fromApplication\("""))
        val text0 = code.getValue(entry)
        val factory = Regex("""\bProductionBankHistoryWiring\b""").find(text0)?.range?.first ?: -1
        val resolution = text0.indexOf("EntryPointAccessors.fromApplication(")
        // The next top-level declaration after the factory's: whichever form the factory takes, its body ends before it.
        val next = Regex("""\n(?:internal |private |public )?(?:const val|val|var|fun|object|class|interface|enum class)\b""")
            .findAll(text0).map { it.range.first }.firstOrNull { it > factory } ?: text0.length
        assertTrue("inside the production factory's body ($factory < $resolution < $next)", factory in 0 until resolution && resolution < next)
        assertEquals("the route is mounted by the root only", setOf(root), files("""\bPremiumTopicRoute\(""") - route)
        val text = code.getValue(root)
        val gate = Regex("""if \(!ReleaseAdmission\.isOpen\) \{\s*ReleaseUnavailableScreen\(\)\s*return\s*}""").find(text)
        assertTrue("the root returns before arming when D24 is closed", gate != null && gate.range.first < text.indexOf("PremiumTopicRoute("))
        assertTrue("the route is mounted in the premium destination", Regex("""RootDestination\.Premium -> \{[^}]*?PremiumTopicRoute\(""").containsMatchIn(text))
    }

    /** S7C_N02s: the entry's file touches no lifecycle, storage, saved state, permission, push or last-bank surface. */
    @Test fun S7C_N02s_theEntryTouchesNoOtherSurface() {
        val text = code.getValue(entry)
        for (name in listOf("LifecycleEventObserver", "LocalLifecycleOwner", "ProcessLifecycleOwner", "DataStore", "SharedPreferences",
                "rememberSaveable", "SavedStateHandle", "Permission", "PushNotification", "FirebaseMessaging", "NotificationManager",
                "lastSelectedBank", "LastSelectedBank", "AlertViewModel", "getSettings", "createSetting", "updateSetting", "deleteSetting",
                "registerDevice")) {
            assertFalse("the entry names $name", Regex("""\b$name\b""").containsMatchIn(text))
        }
        assertEquals("its one read is the repository's history", 1, count(entry, """\brepository\.\w+\("""))
        assertEquals(1, count(entry, """\brepository\.getHistory\("""))
    }

    /**
     * S7C_W03s: the deletion revisions are one process singleton — scoped, injected, constructed by nobody — so the settings
     * screen's publisher and the entry point's reader are the same object; the entry point adds no binding.
     */
    @Test fun S7C_W03s_theDeletionRevisionsAreTheOneSingleton() {
        val text = code.getValue(revisions)
        assertTrue("scoped and injected", Regex("""@Singleton\s+internal class DeletionAdmissionRevisions @Inject constructor\(\)""").containsMatchIn(text))
        assertEquals("constructed by nobody", emptySet<String>(), files("""(?<!class )\bDeletionAdmissionRevisions\(\)"""))
        assertEquals("named by its file, the settings screen and the entry only", setOf(revisions, settings, entry),
            files("""\bDeletionAdmissionRevisions\b"""))
        assertEquals("the settings screen takes it injected and hands its publication over", listOf(1, 1),
            listOf(count(settings, """\brevisions: DeletionAdmissionRevisions\b"""), count(settings, """\brevisions::published\b""")))
        assertFalse("the entry point declares no binding", Regex("""@(Provides|Binds|Module)\b""").containsMatchIn(code.getValue(entry)))
    }
}
