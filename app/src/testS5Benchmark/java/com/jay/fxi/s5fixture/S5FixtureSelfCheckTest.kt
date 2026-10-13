package com.jay.fxi.s5fixture

import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityFence
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * S5-4 slice 4b: the start-up self-check's judgement and its run on the JVM, with notices delivered at once. The real main
 * looper, the real bindings and the real close are S5-4 4d's evidence; these tests do not stand in for it.
 */
class S5FixtureSelfCheckTest {

    private val signedOutOnMain = S5Notice(onMainThread = true, signedOut = true)
    private val signedInOnMain = S5Notice(onMainThread = true, signedOut = false)
    private val passing = S5SelfCheckSeen(true, signedOutOnMain, signedOutOnMain, signedInOnMain, 0)

    @Test
    fun `S5-4 4b the judgement passes only the expected run`() {
        assertEquals(emptyList<String>(), judgeS5SelfCheck(passing))
        val failing = mapOf(
            "bindings" to passing.copy(bindingsAreTheIdentity = false),
            "X replay missing" to passing.copy(closedReplay = null),
            "X replay off the main thread" to passing.copy(closedReplay = S5Notice(onMainThread = false, signedOut = true)),
            "X replay signed in" to passing.copy(closedReplay = signedInOnMain),
            "Y replay missing" to passing.copy(openReplay = null),
            "Y replay off the main thread" to passing.copy(openReplay = S5Notice(onMainThread = false, signedOut = true)),
            "Y sign-in missing" to passing.copy(openSignIn = null),
            "Y sign-in off the main thread" to passing.copy(openSignIn = S5Notice(onMainThread = false, signedOut = false)),
            "Y sign-in still signed out" to passing.copy(openSignIn = signedOutOnMain),
            "X heard after close" to passing.copy(closedAfterClose = 1)
        )
        for ((case, seen) in failing) assertEquals(case, 1, judgeS5SelfCheck(seen).size)
    }

    private val reports = mutableListOf<String>()
    private var seeds = 0

    private suspend fun start(
        identity: S5FixtureIdentity,
        isMainThread: () -> Boolean = { true },
        bindings: () -> List<Any> = { listOf(identity, identity, identity) },
        awaitPostedNotices: suspend () -> Unit = {},
        seed: suspend () -> Boolean = { true }
    ) = startS5Fixture(
        identity, isMainThread, bindings, { reports += it }, awaitPostedNotices, timeoutMillis = 1_000L,
        seed = { seeds++; seed() }
    )

    private fun failure(block: suspend () -> Unit): String? = try {
        kotlinx.coroutines.runBlocking { block() }
        null
    } catch (expected: IllegalStateException) {
        expected.message
    }

    @Test
    fun `S5-4 4b a run with the identity bound three times, notices on the main thread and a good seed passes and reports`() = runTest {
        val identity = S5FixtureIdentity { it.run() }
        start(identity)
        assertEquals(listOf(S5_SELF_CHECK_PASSED), reports)
        assertEquals(AuthIdentity("s5-fixture", 1L), identity.currentIdentity())
    }

    @Test
    fun `S5-4 4b a false or throwing seed is a startup failure and nothing is reported`() {
        val identity = S5FixtureIdentity { it.run() }
        assertEquals("S5 fixture selection seed failed", failure { start(identity, seed = { false }) })
        assertEquals("disk full", failure { start(identity, seed = { error("disk full") }) })
        assertNull(identity.currentUser())
        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun `S5-4 4b a wrong binding, a missing binding or an off-main notice fails the check`() {
        val other = S5FixtureIdentity { it.run() }
        for ((case, run) in listOf<Pair<String, suspend (S5FixtureIdentity) -> Unit>>(
            "another identity" to { id -> start(id, bindings = { listOf(id, other, id) }) },
            "only two bindings" to { id -> start(id, bindings = { listOf(id, id) }) },
            "no bindings" to { id -> start(id, bindings = { emptyList() }) },
            "off the main thread" to { id -> start(id, isMainThread = { false }) }
        )) {
            val message = failure { run(S5FixtureIdentity { it.run() }) }
            assertTrue("$case: $message", message?.startsWith("S5 fixture self-check failed: ") == true)
        }
        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun `S5-4 4b a missing, off-main or duplicated initial replay fails before the seed is called`() {
        for (case in listOf("missing", "off the main thread", "duplicated")) {
            val identity = when (case) {
                "missing" -> S5FixtureIdentity { /* never delivered */ }
                "duplicated" -> S5FixtureIdentity { it.run(); it.run() }
                else -> S5FixtureIdentity { it.run() }
            }
            seeds = 0
            val message = failure {
                start(identity, isMainThread = { case != "off the main thread" })
            }
            assertTrue("$case: $message", message?.startsWith("S5 fixture self-check failed: ") == true)
            assertEquals("$case: the seed is never called", 0, seeds)
            assertNull("$case: still signed out", identity.currentUser())
        }
        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun `S5-4 4b a main notice barrier that never completes fails the check, before the seed or after the sign-in`() {
        val beforeSeed = S5FixtureIdentity { it.run() }
        seeds = 0
        assertEquals(
            "S5 fixture self-check failed: main notice barrier timed out",
            failure { start(beforeSeed, awaitPostedNotices = { awaitCancellation() }) }
        )
        assertEquals("the seed is never called", 0, seeds)
        assertNull(beforeSeed.currentUser())

        val afterSignIn = S5FixtureIdentity { it.run() }
        var barriers = 0
        assertEquals(
            "S5 fixture self-check failed: main notice barrier timed out",
            failure { start(afterSignIn, awaitPostedNotices = { if (++barriers > 1) awaitCancellation() }) }
        )
        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun `S5-4 4b notices that never arrive fail the check after the timeout`() {
        val silent = S5FixtureIdentity { /* the looper never runs it */ }
        val message = failure { start(silent) }
        assertTrue(message, message?.contains("no signed-out replay") == true)
    }

    @Test
    fun `S5-4 4b nothing is published while the seed waits`() = runTest {
        val identity = S5FixtureIdentity { it.run() }
        val fences = mutableListOf<AuthIdentityFence?>()
        identity.observe { fences += it }
        val seeded = CompletableDeferred<Boolean>()
        val run = async { start(identity, seed = { seeded.await() }) }
        runCurrent()
        assertEquals("nothing is published while the seed waits", listOf<AuthIdentityFence?>(null), fences)
        assertNull(identity.currentUser())
        seeded.complete(true)
        run.await()
        assertEquals(listOf(null, AuthIdentityFence("s5-fixture", 1L)), fences)
        assertEquals(listOf(S5_SELF_CHECK_PASSED), reports)
    }

    @Test
    fun `S5-4 4b the identity provider runs the self-check with the three bindings and the main looper`() {
        val source = File("src/s5Benchmark/java/com/jay/fxi/s5fixture/S5FixtureModules.kt").readText()
        assertTrue(source.contains("CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {\n            startS5Fixture("))
        assertTrue(source.contains("isMainThread = { Looper.getMainLooper().isCurrentThread },"))
        assertTrue(source.contains("main.post { continuation.resume(Unit) }"))
        assertTrue(source.contains("bindings = { listOf(observations.get(), tokens.get(), fences.get()) },"))
        assertTrue(source.contains(") { seeder.seed() }"))
        assertFalse("no logged seed fallback", source.contains("signInAfter { seeder.seed() }"))
    }
}
