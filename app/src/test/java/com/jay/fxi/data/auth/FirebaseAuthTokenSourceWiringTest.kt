package com.jay.fxi.data.auth

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural checks for publication wiring not exercised by the current JVM fixtures.
 *
 * The test setup does not construct `FirebaseAuthTokenSource`, which depends on `FirebaseAuth`.
 * Review measured that deleting **every** `deliverPending()` call left the previous suite green.
 * These checks pin the source wiring; they do not replace behavioural tests of the pump.
 *
 * These are text checks, not semantic lock analysis. They pin the names and lock-call counts of
 * members matched by memberHeader and identityLockCall, and check the hand-over calls there.
 * A new matching lock site changes the expected list. Qualified lock references, helper-based
 * locking or unrecognised member headers can evade detection; this does not cover every possible
 * path that mutates the tracker.
 *
 * Behaviour is covered elsewhere, on purpose — the tracker's replay and announce rules by
 * [AuthSessionGenerationTrackerTest], and the hand-over's serialisation, reentrancy and exception
 * handling by [SerialDeliveryPumpTest], which can run real threads against the real pump because
 * the pump no longer needs a `FirebaseAuth` to exist.
 */
class FirebaseAuthTokenSourceWiringTest {

    private val source: String by lazy {
        val file = File("src/main/java/com/jay/fxi/data/auth/FirebaseAuthTokenSource.kt")
        assertTrue(
            "소스를 찾지 못했다 — 이 시험은 파일을 읽으므로 경로가 바뀌면 조용히 통과하면 안 된다: ${file.absolutePath}",
            file.isFile
        )
        file.readText()
    }

    /** Includes property initializers: the pump owns the drain lambda. */
    private val memberHeader = Regex(
        """(?m)^    (?:(?:override|private|internal|public|protected|suspend)\s+)*(fun|val|var)\s+([A-Za-z0-9_]+)"""
    )
    private val identityLockCall = Regex("""\bsynchronized\s*\(\s*identityLock\s*\)""")

    private fun sourceMembers(): List<Pair<String, String>> {
        val headers = memberHeader.findAll(source).toList()
        return headers.mapIndexed { index, header ->
            val end = headers.getOrNull(index + 1)?.range?.first ?: source.length
            val name = header.groupValues[1] + " " + header.groupValues[2]
            name to source.substring(header.range.first, end)
        }
    }

    /** A new lock site, including one in an initializer, requires an explicit review. */
    @Test
    fun theSetOfLockHoldersIsClosed() {
        val holders = sourceMembers().flatMap { (name, body) ->
            identityLockCall.findAll(body).map { name }.toList()
        }
        assertEquals(
            listOf(
                "fun observe",
                "val pump",
                "fun currentIdentity",
                "fun invalidateCurrentIdentity",
                "fun invalidateCurrentSession",
                "fun observeCurrentUser"
            ).sorted(),
            holders.sorted()
        )
    }

    /** Pins the five hand-over calls, the locked drain, and the shared pump delegate. */
    @Test
    fun everyLockHolderDrainsAfterwards() {
        val members = sourceMembers()
        val handOver = Regex("""(?m)^        deliverPending\(\)[ \t]*$""")
        members.filter { (name, body) ->
            name != "val pump" && identityLockCall.containsMatchIn(body)
        }.forEach { (name, body) ->
            val calls = handOver.findAll(body).toList()
            assertEquals("배달 호출이 없거나 중복됐다: " + name, 1, calls.size)
            assertTrue(
                "배달 호출이 락 획득보다 앞에 있다: " + name,
                calls.single().range.first > identityLockCall.find(body)!!.range.last
            )
        }

        val pump = members.single { it.first == "val pump" }.second
        assertTrue(
            "공유 pump 가 identityLock 아래에서 outbox 를 비우지 않는다",
            Regex(
                """private val pump = SerialDeliveryPump\s*\{\s*synchronized\(identityLock\)\s*\{\s*generationTracker\.drainOutbox\(\)\s*}\s*}"""
            ).containsMatchIn(pump)
        )
        assertEquals(
            "deliverPending 은 공유 pump 에 정확히 한 번 위임해야 한다",
            1,
            Regex("""(?m)^    private fun deliverPending\(\) = pump\.run\(\)[ \t]*$""")
                .findAll(source).count()
        )
    }

    /**
     * The two invalidation paths use the two tracker entry points, and not each other.
     *
     * A source mutation replacing `retire` with `invalidate` at the sign-out call site survived
     * the suite before this check was added. AuthSessionGenerationTrackerTest calls the tracker
     * directly; it does not execute FirebaseAuthTokenSource's sign-out entry point. The assertions
     * below now reject that source mutation by checking the entry-point pairing.
     *
     * Announcing the intermediate same-uid fence can trigger rebinding, an owner-store write and
     * a scheduled entitlement query in AuthAccessBinder. The uid adapter suppresses that
     * generation-only event if it has already delivered the uid; these costs are not shared by
     * every subscriber.
     */
    @Test
    fun eachInvalidationPathUsesItsOwnTrackerEntryPoint() {
        val signOut = source.substringAfter("fun invalidateCurrentSession(").substringBefore("\n    }")
        val signIn = source.substringAfter("fun invalidateCurrentIdentity(").substringBefore("\n    }")

        assertTrue("로그아웃 경로가 retire 를 쓰지 않는다", "generationTracker.retire(" in signOut)
        assertTrue("로그아웃 경로가 알리는 invalidate 를 쓴다", "generationTracker.invalidate(" !in signOut)
        assertTrue("로그인 경로가 알리는 invalidate 를 쓰지 않는다", "generationTracker.invalidate(" in signIn)
        assertTrue("로그인 경로가 retire 를 쓴다 — 같은 uid 재로그인이 영영 안 알려진다", "generationTracker.retire(" !in signIn)
    }
}
