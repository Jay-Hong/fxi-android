package com.jay.fxi.ui.alert

import com.jay.fxi.data.alerts.AlertRateLimitEvidence
import com.jay.fxi.data.alerts.AlertRateLimitFloor
import com.jay.fxi.data.alerts.AlertSettingsAccess
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.HttpExchangeEvidence
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.AuthenticatedEndpoint
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.remote.dto.SourceAlertHistoryItem
import com.jay.fxi.data.repository.AlertRateLimitResponseEvidence
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.domain.model.AlertCondition
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8-4b (`R4c/S8/s8_4b_contract.agreed.r1.md` §2, §3 "JVM holder"): one tether history session over the production
 * [SettingsSourceHistoryAccess], a real [AlertSettingsAccess] and a real [AlertRateLimitFloor] on virtual time, with a read
 * that ends only when the test answers it, whatever happened to its job meanwhile (as a transport can), and a fold counter
 * that shows an answer reached the holder. Opening reads once for the session's one asset; the logs floor is waited out at
 * every entry and extended while waiting; evidence is recorded before the fold, also an old session's, and holds the next
 * account; only rows of the asset and a known exchange are kept; a lost access, a closed session or another identity leaves
 * nothing to draw and no late answer, failure or retry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceHistoryStateHolderTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val alice2 = AuthIdentityFence("user-a", 2)
    private val bob = AuthIdentityFence("user-b", 1)
    private val grant = TopicGrantToken(11)
    private val t = Instant.parse("2026-11-21T01:00:00Z")
    private val logs = AuthenticatedEndpoint.SOURCE_NOTIFICATION_LOGS

    private fun item(id: Int, source: String = "upbit", asset: String = "usdt-krw", settingId: Int? = id + 100,
                     threshold: Double = 1400.5, rate: Double = 1401.25) =
        SourceAlertHistoryItem(id, settingId, source, asset, AlertCondition.ABOVE, threshold, rate, t)

    /** One read: it ends only when the test answers it, whatever happened to its job meanwhile. */
    private class Call(val identity: AuthIdentityFence, val asset: String, val guard: () -> Boolean, val job: Job?) {
        @Volatile var continuation: Continuation<AuthBoundResult<List<SourceAlertHistoryItem>>>? = null
        val waiting get() = continuation != null
        fun resume(with: Result<AuthBoundResult<List<SourceAlertHistoryItem>>>) {
            val waiting = checkNotNull(continuation) { "the read is not waiting" }
            continuation = null
            waiting.resumeWith(with)
        }
    }

    private inner class Rig(val test: TestScope) {
        var identity: AuthIdentityFence? = alice
        var fence: TopicSessionFence? = TopicSessionFence(alice, "epoch-1", grant)
        var snapshot = standing(0)
        val premium = MutableStateFlow(OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.PremiumConfirmed))
        var release = true
        val deletions = DeletionAdmissionStore()
        var display: TopicDisplayOwner? = TopicDisplayOwner(alice, 1L)
        var tetherShown = true
        val floor = AlertRateLimitFloor { test.testScheduler.currentTime }
        val calls = mutableListOf<Call>()
        /** Folds of the answers this rig made: an answer reached a holder. */
        val folds = AtomicInteger()
        var retired = 0
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + StandardTestDispatcher(test.testScheduler))
        private val access = AlertSettingsAccess({ identity }, { fence }, SnapshotTopicUseAuthority { snapshot }, { premium.value },
            { release }, GraphProtectedAdmission({ identity }, deletions))
        val holder = newHolder()

        fun standing(invalidations: Long) = TopicAccessSnapshot.INITIAL.copy(
            revision = 1L, facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet()),
            userInvalidations = invalidations
        )

        fun newHolder() = SourceHistoryStateHolder(
            SettingsSourceHistoryAccess(access) { display.takeIf { tetherShown } },
            SourceHistoryRead { o, a, g ->
                val call = Call(o, a, g, currentCoroutineContext()[Job]).also { calls += it }
                suspendCoroutine { call.continuation = it }
            },
            object : SourceHistoryFloor {
                override fun remainingMillis() = floor.remainingMillis(logs)
                override fun record(evidence: AlertRateLimitEvidence) = floor.record(logs, evidence)
            },
            scope, { delay(it) }, onRetired = { retired++ }
        )

        val owner get() = checkNotNull(display)
        fun settle() = test.runCurrent()
        fun advance(millis: Long) { test.advanceTimeBy(millis); test.runCurrent() }
        fun body() = holder.currentState()?.body
        fun ok(items: List<SourceAlertHistoryItem>, evidence: AlertRateLimitResponseEvidence? = null) =
            AuthBoundResult({ folds.incrementAndGet() }, Result.success(items), evidence)
        fun failed(error: Throwable, evidence: AlertRateLimitResponseEvidence? = null) =
            AuthBoundResult<List<SourceAlertHistoryItem>>({ folds.incrementAndGet() }, Result.failure(error), evidence)
        fun answer(result: AuthBoundResult<List<SourceAlertHistoryItem>>, call: Call = calls.last()) {
            call.resume(Result.success(result))
            settle()
        }
        fun throwInto(call: Call, error: Throwable) {
            call.resume(Result.failure(error))
            settle()
        }
        fun rateLimit(status: Int, seconds: String) =
            floor.record(logs, AlertRateLimitEvidence.of(emptyList(), status, seconds))

        fun switchTo(next: AuthIdentityFence) {
            identity = next
            fence = TopicSessionFence(next, "epoch-1", grant)
            premium.value = OwnedPremiumAccess(next.uid, next.authGeneration, PremiumAccessState.PremiumConfirmed)
            display = TopicDisplayOwner(next, 1L)
        }

        /** Ends the rig: every read still waiting is answered with a cancellation, so no coroutine is left suspended. */
        fun close() {
            calls.filter { it.waiting }.forEach { it.resume(Result.failure(CancellationException("rig closed"))) }
            job.cancel()
            test.runCurrent()
        }
    }

    private fun rig(test: TestScope, body: Rig.() -> Unit) {
        val r = Rig(test)
        try {
            // Invoked as `body(r)`, never `r.body()`: Rig has its own member body(), which that call would resolve to.
            body(r)
        } finally {
            r.close()
        }
    }

    @Test fun `S8_4B_H01 nothing before the open, one read of the session's identity and asset, a repeated open joins it`() = runTest {
        rig(this) {
            settle()
            assertEquals("no read before the open", 0, calls.size)
            assertNull("nothing to draw before the open", holder.currentState())
            holder.open(owner); settle()
            assertEquals("one read", 1, calls.size)
            assertEquals("for the shown identity and the tether asset", listOf<Any>(alice, "usdt-krw"), listOf(calls[0].identity, calls[0].asset))
            assertEquals("loading", SourceHistoryBody.Loading, body())
            assertTrue("the guard admits the live request", calls[0].guard())
            holder.open(owner); settle()
            assertEquals("a repeated open joins the session", 1, calls.size)
        }
    }

    @Test fun `S8_4B_H02 only rows of the asset and a known exchange are kept, in the server's order, an empty remainder is Empty`() = runTest {
        rig(this) {
            holder.open(owner); settle()
            answer(ok(listOf(item(1, settingId = null), item(2, "binance"), item(3, asset = "usd-krw-futures"), item(4, "krx", "usd-krw-futures"),
                item(5, "bithumb"), item(6, "coinone"), item(7, "korbit"), item(8, "gopax"), item(9, "UPBIT"))))
            val loaded = body() as SourceHistoryBody.Loaded
            assertEquals("the five exchanges' tether rows, the server's order", listOf(1, 5, 6, 7, 8), loaded.items.map { it.id })
            assertEquals("a valid row keeps its null setting id and both prices", listOf<Any?>(null, 1400.5, 1401.25),
                loaded.items.first().let { listOf(it.settingId, it.threshold, it.triggeredRate) })
            assertEquals("not refreshing", false, loaded.refreshing)
        }
        rig(this) {
            holder.open(owner); settle()
            answer(ok(listOf(item(2, "binance"), item(3, asset = "usd-krw-futures"))))
            assertEquals("all refused: Empty", SourceHistoryBody.Empty, body())
        }
    }

    @Test fun `S8_4B_H03 retry only from a failure, pull only from rows, the rows and a spinner stay while it sends`() = runTest {
        rig(this) {
            holder.open(owner); settle()
            answer(failed(AlertRepositoryException("알림 기록을 불러오지 못했습니다")))
            assertTrue("a failure", body() is SourceHistoryBody.Failed)
            holder.refresh(); settle()
            assertEquals("no pull from a failure", 1, calls.size)
            holder.retry(); settle()
            assertEquals("a retry reads again", 2, calls.size)
            assertEquals("the retry reads the session's asset", "usdt-krw", calls[1].asset)
            assertEquals("loading again", SourceHistoryBody.Loading, body())
            holder.retry(); settle()
            assertEquals("no second retry while one is in flight", 2, calls.size)
            answer(ok(listOf(item(1))))
            holder.retry(); settle()
            assertEquals("no retry from rows", 2, calls.size)
            holder.refresh(); settle()
            assertEquals("a pull reads again", 3, calls.size)
            assertEquals("the pull reads the session's asset", "usdt-krw", calls[2].asset)
            assertEquals("the rows stay, refreshing", SourceHistoryBody.Loaded(listOf(item(1)), refreshing = true), body())
            answer(ok(listOf(item(2))))
            assertEquals("the pull's rows, not refreshing", SourceHistoryBody.Loaded(listOf(item(2)), refreshing = false), body())
            assertEquals("every read is the same asset", listOf("usdt-krw", "usdt-krw", "usdt-krw"), calls.map { it.asset })
        }
    }

    @Test fun `S8_4B_H04 every entry waits out the logs floor to the millisecond, a wait extends, a close drops it`() = runTest {
        rig(this) {
            rateLimit(429, "5")
            holder.open(owner); settle()
            advance(4_999)
            assertEquals("open: nothing 1 ms before the floor", 0, calls.size)
            assertEquals("loading while it waits", SourceHistoryBody.Loading, body())
            advance(1)
            assertEquals("open: one read at the floor", 1, calls.size)
        }
        rig(this) {
            rateLimit(503, "5")
            holder.open(owner); settle()
            advance(3_000)
            rateLimit(429, "10")
            advance(9_999)
            assertEquals("an extension while waiting: nothing 1 ms before the new floor", 0, calls.size)
            advance(1)
            assertEquals("one read at the new floor", 1, calls.size)
        }
        rig(this) {
            rateLimit(429, "5")
            holder.open(owner); settle()
            advance(1_000)
            holder.close(); settle()
            assertEquals("closed while waiting: nothing to draw, released once", listOf<Any?>(null, 1), listOf(holder.currentState(), retired))
            advance(10_000)
            assertEquals("and the wait's demand was dropped", 0, calls.size)
        }
        rig(this) {
            holder.open(owner); settle()
            answer(ok(listOf(item(1))))
            rateLimit(429, "5")
            holder.refresh(); settle()
            advance(4_999)
            assertEquals("pull: nothing 1 ms before the floor", 1, calls.size)
            assertEquals("pull: the rows and the spinner stay while it waits", SourceHistoryBody.Loaded(listOf(item(1)), refreshing = true), body())
            advance(1)
            assertEquals("pull: one read at the floor", 2, calls.size)
        }
        rig(this) {
            holder.open(owner); settle()
            answer(failed(AlertRepositoryException("x")))
            rateLimit(429, "3")
            holder.retry(); settle()
            advance(2_999)
            assertEquals("retry: nothing 1 ms before the floor", 1, calls.size)
            advance(1)
            assertEquals("retry: one read at the floor", 2, calls.size)
        }
    }

    @Test fun `S8_4B_H05 evidence is recorded before the fold, an identity change prefers its exchanges, a withdrawal keeps only them`() = runTest {
        data class Case(val label: String, val outcome: Rig.(Call) -> Unit, val floorMillis: Long, val ends: Boolean)
        for (case in listOf(
            Case("a failure's own response", { c -> answer(failed(AlertRepositoryException("x"), AlertRateLimitResponseEvidence(503, "7")), c) }, 7_000, false),
            Case("a fold refused after the response", { c ->
                answer(AuthBoundResult({ throw AuthIdentityChangedException() }, Result.success(listOf(item(1))),
                    AlertRateLimitResponseEvidence(429, "4")), c)
            }, 4_000, true),
            Case("an identity change with exchanges", { c ->
                throwInto(c, AuthIdentityChangedException(503, "20", listOf(HttpExchangeEvidence(1, 1, 429, "6"))))
            }, 6_000, true),
            Case("an identity change without exchanges", { c ->
                throwInto(c, AuthIdentityChangedException(429, "8"))
            }, 8_000, true),
            Case("a withdrawal", { c ->
                throwInto(c, TopicUseWithheldException(listOf(HttpExchangeEvidence(1, 1, 429, "9"))))
            }, 9_000, true),
            Case("a success's 2xx header", { c -> answer(ok(listOf(item(1)), AlertRateLimitResponseEvidence(200, "30")), c) }, 0, false))) {
            rig(this) {
                holder.open(owner); settle()
                case.outcome(this, calls.single())
                assertEquals("${case.label}: the logs floor", case.floorMillis, floor.remainingMillis(logs))
                if (case.ends) {
                    assertEquals("${case.label}: no error, no retry, nothing to draw, released once", listOf<Any?>(null, 1),
                        listOf(holder.currentState(), retired))
                } else {
                    assertTrue("${case.label}: a published body", holder.currentState() != null)
                }
            }
        }
        rig(this) {
            holder.open(owner); settle()
            val call = calls.single()
            throwInto(call, CancellationException("plain"))
            assertEquals("a plain cancellation publishes no failure", SourceHistoryBody.Loading, holder.state.value?.body)
            assertEquals("and records no floor", 0L, floor.remainingMillis(logs))
            assertTrue("a plain cancellation ends the request as cancelled", call.job?.isCancelled == true)
        }
    }

    @Test fun `S8_4B_H06 a late answer of a session that lost its identity, its use, its tab or was closed draws nothing`() = runTest {
        data class Case(val label: String, val away: Rig.() -> Unit, val back: Rig.() -> Unit)
        for (case in listOf(
            Case("A to B", { switchTo(bob) }, {}),
            Case("the display owner replaced, same identity", { display = TopicDisplayOwner(alice, 2L) }, {}),
            Case("A to B to A2", { switchTo(bob) }, { switchTo(alice2) }),
            Case("the use reissued, same UID and generation", { snapshot = standing(1) }, {}),
            Case("the tab round trip", { tetherShown = false }, { tetherShown = true }),
            Case("closed and reopened", { holder.close() }, {}))) {
            for (late in listOf("success", "failure")) rig(this) {
                holder.open(owner); settle()
                val old = calls.single()
                case.away(this)
                assertNull("${case.label}: nothing to draw once it is gone", holder.currentState())
                case.back(this)
                val next = newHolder()
                next.open(owner); settle()
                assertNull("${case.label}: the old session stays gone", holder.state.value)
                assertEquals("${case.label}: released once", 1, retired)
                assertTrue("${case.label}: the old guard refuses", !old.guard())
                assertEquals("${case.label}: premise, no answer has reached a holder", 0, folds.get())
                answer(if (late == "success") ok(listOf(item(1))) else failed(AlertRepositoryException("x")), old)
                assertEquals("${case.label}, late $late: the late answer reached the old holder", 1, folds.get())
                assertNull("${case.label}, late $late: the old session holds nothing", holder.state.value)
                assertEquals("${case.label}, late $late: still released once", 1, retired)
                assertTrue("${case.label}, late $late: the new request's guard admits", calls[1].guard())
                assertEquals("${case.label}, late $late: the new session is still loading its own read", SourceHistoryBody.Loading, next.currentState()?.body)
                holder.retry(); holder.refresh(); advance(60_000)
                assertEquals("${case.label}, late $late: the old session asks nothing more", 2, calls.size)
            }
        }
    }

    @Test fun `S8_4B_H11 a late answer under a reissued use is not adopted even before any render check`() = runTest {
        rig(this) {
            holder.open(owner); settle()
            val old = calls.single()
            snapshot = standing(1)
            // No currentState() in between: the completion's own gate must refuse the old use, not a fresh one.
            answer(ok(listOf(item(1))), old)
            assertEquals("premise: the answer reached the holder", 1, folds.get())
            assertNull("the raw state holds no adopted rows", holder.state.value)
            assertEquals("released once", 1, retired)
        }
    }

    @Test fun `S8_4B_H07 rows whose use is revoked with no request in flight vanish and keep no retry or pull`() = runTest {
        for ((label, revoke) in listOf<Pair<String, Rig.() -> Unit>>(
            "premium lost" to { premium.value = OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.FreeConfirmed) },
            "release closed" to { release = false },
            "deletion begun" to { deletions.begin(alice, "op") },
            "the use reissued" to { snapshot = standing(1) })) rig(this) {
            holder.open(owner); settle()
            answer(ok(listOf(item(1))))
            assertTrue("premise($label): rows", body() is SourceHistoryBody.Loaded)
            revoke(this)
            assertNull("$label: the rows vanish at the next read", holder.currentState())
            assertEquals("$label: released once", 1, retired)
            holder.refresh(); holder.retry(); holder.open(owner); settle()
            assertEquals("$label: no read from a kept pull, retry or open", 1, calls.size)
        }
    }

    @Test fun `S8_4B_H08 an open the access refuses draws nothing and reads nothing`() = runTest {
        for ((label, refuse) in listOf<Pair<String, Rig.() -> Unit>>(
            "another live identity" to { identity = bob },
            "no fence" to { fence = null },
            "free" to { premium.value = OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.FreeConfirmed) },
            "release closed" to { release = false },
            "deletion begun" to { deletions.begin(alice, "op") },
            "not the tether tab" to { tetherShown = false })) rig(this) {
            val shown = owner
            refuse(this)
            holder.open(shown); settle()
            assertEquals("$label: no read", 0, calls.size)
            assertEquals("$label: nothing to draw, released once", listOf<Any?>(null, 1), listOf(holder.currentState(), retired))
        }
    }

    @Test fun `S8_4B_H12 a shown owner of the live UID but another generation reads nothing`() = runTest {
        rig(this) {
            // The live identity, fence and premium are alice's first generation; the screen already shows the second.
            display = TopicDisplayOwner(alice2, 1L)
            holder.open(owner); settle()
            assertEquals("another generation: no read", 0, calls.size)
            assertEquals("another generation: nothing to draw, released once", listOf<Any?>(null, 1), listOf(holder.currentState(), retired))
        }
    }

    @Test fun `S8_4B_H13 an old session's late 503 or 429 evidence holds the next account's logs read to its deadline`() = runTest {
        for ((status, seconds) in listOf(503 to 7L, 429 to 9L)) rig(this) {
            holder.open(owner); settle()
            val old = calls.single()
            holder.close(); settle()
            switchTo(bob)
            answer(failed(AlertRepositoryException("x"), AlertRateLimitResponseEvidence(status, seconds.toString())), old)
            assertEquals("$status: premise, the late answer reached the old holder", 1, folds.get())
            assertNull("$status: the old session holds nothing", holder.state.value)
            val next = newHolder()
            next.open(owner); settle()
            advance(seconds * 1_000 - 1)
            assertEquals("$status: B sends nothing 1 ms before the old deadline", 1, calls.size)
            advance(1)
            assertEquals("$status: B sends once at the deadline, as B", listOf<Any>(2, bob), listOf(calls.size, calls.last().identity))
            advance(60_000)
            assertEquals("$status: the old failure scheduled no retry", 2, calls.size)
        }
    }

    @Test fun `S8_4B_H09 the logs floor is its own key in both directions`() = runTest {
        val others = AuthenticatedEndpoint.entries - logs
        assertTrue("premise: the other keys include the bank and source writes", others.containsAll(listOf(
            AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, AuthenticatedEndpoint.DELETE_NOTIFICATION_SETTING,
            AuthenticatedEndpoint.SOURCE_NOTIFICATION_SETTINGS, AuthenticatedEndpoint.NOTIFICATION_LOGS)))
        rig(this) {
            for (key in others) {
                floor.record(key, AlertRateLimitEvidence.of(emptyList(), 429, "30"))
            }
            holder.open(owner); settle()
            assertEquals("other keys' floors do not hold the logs read", 1, calls.size)
            answer(failed(AlertRepositoryException("x"), AlertRateLimitResponseEvidence(429, "12")))
            assertEquals("its own evidence is the logs key", 12_000, floor.remainingMillis(logs))
        }
        rig(this) {
            holder.open(owner); settle()
            answer(failed(AlertRepositoryException("x"), AlertRateLimitResponseEvidence(429, "12")))
            for (key in others) {
                assertEquals("the logs evidence does not hold $key", 0L, floor.remainingMillis(key))
            }
        }
    }

    @Test fun `S8_4B_H10 a row shows the condition's threshold and, apart, the rate that fired it`() {
        val row = sourceHistoryRow(item(1, "bithumb", threshold = 1400.5, rate = 1402.75))
        assertEquals("the exchange's name", "빗썸", row.exchangeName)
        assertEquals("the condition carries the threshold", "1400.50 ${AlertCondition.ABOVE.symbol} ${AlertCondition.ABOVE.displayText}", row.condition)
        assertEquals("the rate field is the triggered rate", "1402.75", row.triggeredRate)
        assertEquals("the sent time in KST", listOf("11-21", "10:00"), listOf(row.date, row.time))
    }
}
