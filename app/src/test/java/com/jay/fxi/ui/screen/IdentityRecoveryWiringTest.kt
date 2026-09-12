package com.jay.fxi.ui.screen

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins where the recovery banner is wired, which no JVM behavioural test can see.
 *
 * These JVM tests inspect source text; they do not compose `RootScreen`. CI compiles androidTest
 * without executing it, and debug Root stops at the D24 gate.
 * The checks pin source positions, mapping expressions, the current callback syntax and selected
 * type tokens. They do not resolve scopes or names, prove execution, or exclude all decision logic.
 * Matching text in comments or unreachable code can pass; equivalent alternative wiring can fail.
 */
class IdentityRecoveryWiringTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue(
            "소스를 찾지 못했다 — 이 시험은 파일을 읽으므로 경로가 바뀌면 조용히 통과하면 안 된다: ${file.absolutePath}",
            file.isFile
        )
        return file.readText()
    }

    private val root by lazy { source("src/main/java/com/jay/fxi/ui/screen/RootScreen.kt") }
    private val banner by lazy { source("src/main/java/com/jay/fxi/ui/screen/IdentityRecoveryBanner.kt") }

    /**
     * Below the gate. `RootScreen` returns before `ArmedRootScreen` when the build is not admitted;
     * wired above it, the released-off build would reach the view model and a banner it is meant never
     * to show.
     */
    @Test
    fun theHostIsWiredInsideTheArmedScreenOnly() {
        val armed = root.indexOf("private fun ArmedRootScreen(")
        assertTrue("ArmedRootScreen 을 찾지 못했다", armed >= 0)

        val calls = Regex("""IdentityRecoveryHost\(""").findAll(root).map { it.range.first }.toList()
        assertEquals("호스트 호출이 정확히 한 번이어야 한다: $calls", 1, calls.size)
        assertTrue("호스트가 D24 게이트 위(RootScreen 본문)에 배선됐다", calls.single() > armed)
    }

    /** The notice comes from the pure mapping, fed by the view model's flow. */
    @Test
    fun theNoticeIsTheMappingOfTheViewModelsFlow() {
        assertTrue(
            "배너 통지가 recoveryNoticeFor 를 거치지 않는다",
            root.contains("notice = recoveryNoticeFor(recovery)")
        )
        assertTrue(
            "recovery 가 view model 의 identityRecovery 흐름이 아니다",
            root.contains("val recovery by rootViewModel.identityRecovery.collectAsStateWithLifecycle()")
        )
    }

    /**
     * The re-check passes the id the host hands over. The pattern pins the current shape — the launch
     * body is that single call, naming the lambda's own parameter — so a shadowing `val` inside the
     * launch, or a `launch(start = …)`, no longer matches. Reshaping the wiring fails here too and
     * needs this pattern updated with it.
     */
    @Test
    fun theRecheckForwardsTheIdItIsGiven() {
        val forwarded = Regex("""onRecheck\s*=\s*\{\s*(\w+)\s*->\s*recheckScope\.launch\s*\{\s*rootViewModel\.requestRecheck\(\s*(\w+)\s*\)\s*\}\s*\}""")
            .find(root)
        assertTrue(
            "onRecheck 가 '받은 id 를 곧바로 requestRecheck 에 넘기는' 현재 형태가 아니다 — 배선을 바꿨다면 이 패턴도 함께 고친다",
            forwarded != null
        )
        val (param, argument) = forwarded!!.destructured
        assertEquals("requestRecheck 에 람다가 받은 id 가 아닌 값이 넘어간다", param, argument)
    }

    /**
     * No decision leaks back into Compose. Every rule about what to say lives in `recoveryNoticeFor`,
     * where the JVM tests and the mutation battery reach it; a surface that inspected progress or
     * reasons itself would be a second, unrun copy of those rules.
     */
    @Test
    fun theSurfaceMakesNoDecisionsOfItsOwn() {
        val decisionTypes = listOf("HoldProgress", "HeldWork", "NoAutoRetry", "IdentityRecoveryState.")
        for ((name, text) in listOf("RootScreen.kt" to root, "IdentityRecoveryBanner.kt" to banner)) {
            for (type in decisionTypes) {
                assertTrue("$name 가 $type 를 직접 본다", !text.contains(type))
            }
        }
    }
}
