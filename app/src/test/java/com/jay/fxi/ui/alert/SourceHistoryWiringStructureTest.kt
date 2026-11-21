package com.jay.fxi.ui.alert

import com.jay.fxi.PostS6ApprovedDelta
import com.jay.fxi.S6Krx3bOffWiringContractTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8-4b (`R4c/S8/s8_4b_contract.agreed.r1.md` §3 "JVM 구조"): the tether history's production wiring, over the comment-free
 * code and strings of every production root. Only the premium route mounts the entry and names the production wiring (its
 * default); the entry point is resolved in the entry's file only, once, with no binding; the logs read has one production
 * call, the entry's read, with the one limit and the session's asset; the floor is the process's, never one of the history's
 * own; the history files name no other repository read or write (bank, source or comparison) and observe no process lifecycle.
 */
class SourceHistoryWiringStructureTest {
    private val base = "src/main/java/com/jay/fxi/"
    private val entry = base + "ui/alert/SourceHistoryEntry.kt"
    private val holder = base + "ui/alert/SourceHistoryStateHolder.kt"
    private val sheet = base + "ui/alert/SourceHistorySheet.kt"
    private val route = base + "ui/premium/view/PremiumTopicRoute.kt"
    private val repository = base + "data/repository/AlertRepository.kt"

    private val code: Map<String, String> = S6Krx3bOffWiringContractTest.ROOTS.flatMap { r ->
        File(r).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }.associate { it.invariantSeparatorsPath to PostS6ApprovedDelta.codeAndStrings(it.readText()) }

    private fun files(pattern: String) = code.filterValues { Regex(pattern).containsMatchIn(it) }.keys
    private fun count(path: String, pattern: String) = Regex(pattern).findAll(code.getValue(path)).count()
    private val history = listOf(entry, holder, sheet)

    @Test fun `S8_4B_N01 the entry is mounted by the route only and its entry point is resolved once in its own file`() {
        assertTrue("premise: the scan sees the files", (history + route + repository).all { it in code } && code.size > 100)
        assertEquals("the entry is mounted by the route only", setOf(route), files("""\bSourceHistoryEntry\(""") - entry)
        assertEquals("the route mounts it once", 1, count(route, """\bSourceHistoryEntry\("""))
        assertEquals("the production wiring is named by the route's default and its own file", setOf(route, entry),
            files("""\bProductionSourceHistoryWiring\b"""))
        assertEquals("the entry point is named in its own file only", setOf(entry), files("""\bSourceHistoryEntryPoint\b"""))
        assertEquals("one resolution", 1, count(entry, """EntryPointAccessors\.fromApplication\("""))
        assertFalse("the entry point declares no binding", Regex("""@(Provides|Binds|Module)\b""").containsMatchIn(code.getValue(entry)))
    }

    @Test fun `S8_4B_N02 the logs read has one production caller, with the one limit and the session's asset`() {
        assertEquals("getSourceHistory: the repository's declaration and the entry's read", setOf(repository, entry),
            files("""\bgetSourceHistory\b"""))
        assertEquals("the repository names it once, its declaration", 1, count(repository, """\bgetSourceHistory\b"""))
        assertEquals("the entry calls it once", 1, count(entry, """\bgetSourceHistory\b"""))
        assertEquals("one use outside the declaration in all production code", 1,
            code.values.sumOf { Regex("""\bgetSourceHistory\b""").findAll(it).count() } - 1)
        assertEquals("the read passes what it is given, with the one limit", 1,
            count(entry, """repository\.getSourceHistory\(\s*identity\s*,\s*asset\s*,\s*SOURCE_HISTORY_LIMIT\s*,\s*guard\s*\)"""))
        assertEquals("the limit is 100", 1, count(entry, """\bconst val SOURCE_HISTORY_LIMIT = 100\b"""))
        assertEquals("the holder reads the session's identity and asset", 1,
            count(holder, """read\.read\(\s*request\.session\.owner\.identity\s*,\s*request\.session\.asset\s*\)"""))
        assertEquals("the session's asset is the settings asset, fixed once", 1,
            count(holder, """Session\(\s*owner\s*,\s*SourceAlertRowPolicy\.SETTINGS_ASSET\s*\)"""))
    }

    @Test fun `S8_4B_N03 the floor is the process's logs key and the history adds no settings GET, write or observer`() {
        assertEquals("no history file constructs a floor", emptySet<String>(),
            history.filter { Regex("""\bAlertRateLimitFloor\s*[({]""").containsMatchIn(code.getValue(it)) }.toSet())
        assertEquals("the entry's floor is the process singleton's", 1, count(entry, """\bentry\.alertRateLimitFloor\(\)"""))
        assertEquals("both floor calls use the logs key", 2, count(entry, """\bAuthenticatedEndpoint\.SOURCE_NOTIFICATION_LOGS\b"""))
        assertEquals("and no other key", 2, count(entry, """\bAuthenticatedEndpoint\.\w+"""))
        // Every repository member read or write other than the logs read: bank, source and comparison settings, history and writes.
        val others = Regex("""\bsuspend\s+fun\s+(\w+)\s*\(""").findAll(code.getValue(repository)).map { it.groupValues[1] }.toSet() - "getSourceHistory"
        assertTrue("premise: the other members include the bank, source and comparison settings and writes", others.containsAll(listOf(
            "getSettings", "createSetting", "deleteSetting", "getSourceSettings", "createSourceSetting", "updateSourceSetting",
            "deleteSourceSetting", "getComparisonAlerts", "deleteComparisonAlert", "getHistory", "getComparisonHistory")))
        for (path in history) {
            for (name in others + listOf("ProcessLifecycleOwner", "addObserver", "SourceAlertSettingsOwner")) {
                assertEquals("$path names no $name", 0, count(path, """\b$name\b"""))
            }
        }
    }
}
