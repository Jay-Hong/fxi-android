package com.jay.fxi.ui.alert

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.remote.dto.AlertHistoryItem
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.BankHistoryAccess
import com.jay.fxi.data.repository.BankHistoryUse
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.Bank
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 step (b) contract: the bank history holder (`R4c/S7/s7b_api_proposal.claude.r3.md` §2.1 contracts 1–6 and §3
 * S7B_01–14; scope H02·H05·H06 and the holder side of H07·H08 — not the wire, the Route, the issuer's events or the render).
 * The access fake hands out a distinct use per acquire and withdraws uses one by one; the read fake records every call and
 * finishes each on the test's command, optionally ignoring cancellation so a late answer really reaches the holder.
 * The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BankHistoryStateHolderTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val bob = AuthIdentityFence("user-b", 1)
    private val ownerA = TopicDisplayOwner(alice, 3)
    private val ownerB = TopicDisplayOwner(bob, 4)
    private val generic = BankHistoryBody.Failed(BANK_HISTORY_GENERIC_FAILURE, temporary = false)

    private class FakeAccess : BankHistoryAccess {
        val issued = mutableListOf<BankHistoryUse>()
        private val withdrawn = ConcurrentHashMap.newKeySet<BankHistoryUse>()
        @Volatile var grants = true
        var acquires = 0
        private var tokens = 0L

        override fun acquire(owner: TopicDisplayOwner): BankHistoryUse? {
            acquires += 1
            if (!grants) return null
            val token = TopicGrantToken(++tokens)
            return BankHistoryUse(owner, TopicSessionFence(owner.identity, "epoch", token), TopicUseLifetime(token, 0))
                .also { issued += it }
        }

        override fun admits(use: BankHistoryUse): Boolean = use !in withdrawn
        fun withdraw(use: BankHistoryUse) { withdrawn += use }
        fun restoreAll() = withdrawn.clear()
    }

    private sealed interface Outcome {
        data class Answer(val result: AuthBoundResult<List<AlertHistoryItem>>) : Outcome
        data class Throw(val error: Throwable) : Outcome
    }

    private class Call(val owner: AuthIdentityFence, val currency: String, val guard: () -> Boolean) {
        val started = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<Outcome>()
        /** Set as the read hands its answer back: the holder's completion boundary is reached right after. */
        @Volatile var handedBack = false
    }

    private class FakeRead : BankHistoryRead {
        val calls = mutableListOf<Call>()
        /** When set, the read awaits its outcome even after cancellation (a non-cooperative transport). */
        @Volatile var ignoresCancellation = false
        /** When set, every read ends at once with this outcome, without suspending. */
        @Volatile var immediately: Outcome? = null

        override suspend fun read(owner: AuthIdentityFence, currency: String, useAdmitted: () -> Boolean): AuthBoundResult<List<AlertHistoryItem>> {
            val call = Call(owner, currency, useAdmitted).also { calls += it }
            immediately?.let { return deliver(call, it) }
            call.started.complete(Unit)
            val outcome = if (ignoresCancellation) withContext(NonCancellable) { call.outcome.await() } else call.outcome.await()
            return deliver(call, outcome)
        }

        private fun deliver(call: Call, outcome: Outcome): AuthBoundResult<List<AlertHistoryItem>> {
            call.handedBack = true
            return when (outcome) {
                is Outcome.Answer -> outcome.result
                is Outcome.Throw -> throw outcome.error
            }
        }
    }

    private class World(val test: TestScope, immediate: Boolean = false) {
        val access = FakeAccess()
        val read = FakeRead()
        val scopeJob = SupervisorJob()
        val dispatcher = if (immediate) UnconfinedTestDispatcher(test.testScheduler) else StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(scopeJob + dispatcher)
        val holder = BankHistoryStateHolder(access, read, scope)
        fun settle() = test.runCurrent()
        fun body() = holder.state.value?.body
        fun last() = read.calls.last()
    }

    private val start = Instant.parse("2026-07-16T01:00:00Z")
    private fun item(id: Int, currency: String, bank: String = "hana") = AlertHistoryItem(
        id, id, bank, currency, AlertCondition.ABOVE, 1400.5, 1401.2, Instant.fromEpochSeconds(start.epochSeconds - id * 60L)
    )
    private fun answer(items: List<AlertHistoryItem>, requireCurrent: () -> Unit = {}) =
        Outcome.Answer(AuthBoundResult(requireCurrent, Result.success(items)))
    private fun failed(error: Throwable, requireCurrent: () -> Unit = {}) =
        Outcome.Answer(AuthBoundResult(requireCurrent, Result.failure(error)))
    private fun httpFailure(status: Int, headers: Headers = headersOf()) =
        AuthenticatedHttpFailure(status, headers, ByteArray(0), null, null, AuthenticatedFailureKind.OTHER_HTTP)
    private fun Call.finish(outcome: Outcome) = check(this.outcome.complete(outcome)) { "outcome already given" }

    /** One case: a label and the single move it makes, typed so the lambda gets [World] as its receiver. */
    private fun step(label: String, move: World.() -> Unit): Pair<String, World.() -> Unit> = label to move

    // --- S7B_01 ------------------------------------------------------------------------------------------------------------

    @Test fun S7B_01_constructionReadsNothing_andOneOpenReadsOnceWithItsOwnerCurrencyAndGuard() = runTest {
        val w = World(this)
        w.holder.state.value; w.holder.currentState(); w.settle()
        assertEquals("construction and observation read nothing", 0, w.read.calls.size)
        assertEquals("nor acquire", 0, w.access.acquires)

        w.holder.open(ownerA, "usd-krw")
        assertEquals("Loading at once", BankHistoryBody.Loading, w.body())
        w.settle()
        assertEquals("one read", 1, w.read.calls.size)
        assertEquals("the session's identity", alice, w.last().owner)
        assertEquals("the session's currency", "usd-krw", w.last().currency)
        assertTrue("the guard admits the live use", w.last().guard())

        w.last().finish(answer(listOf(item(1, "usd-krw"), item(2, "usd-krw"))))
        w.settle()
        assertEquals(BankHistoryBody.Loaded(listOf(item(1, "usd-krw"), item(2, "usd-krw")), refreshing = false), w.body())

        for ((label, items) in listOf("empty" to emptyList(), "only other currencies" to listOf(item(3, "jpy-krw"), item(4, "eur-krw")))) {
            val v = World(this)
            v.holder.open(ownerA, "usd-krw"); v.settle()
            v.last().finish(answer(items)); v.settle()
            assertEquals(label, BankHistoryBody.Empty, v.body())
        }
    }

    // --- S7B_02 ------------------------------------------------------------------------------------------------------------

    @Test fun S7B_02_everyReadOfASessionKeepsItsCurrency_andOnlyItsRowsAreShownInOrder() = runTest {
        for (currency in listOf("usd-krw", "jpy-krw", "eur-krw")) {
            val other = if (currency == "usd-krw") "jpy-krw" else "usd-krw"
            val w = World(this)
            w.holder.open(ownerA, currency); w.settle()
            w.last().finish(failed(AlertRepositoryException("네트워크 오류"))); w.settle()
            assertEquals("$currency: failed", BankHistoryBody.Failed("네트워크 오류", temporary = false), w.body())

            w.holder.retry()
            assertEquals("$currency: retry loads", BankHistoryBody.Loading, w.body())
            w.settle()
            w.last().finish(answer(listOf(item(1, currency), item(2, other), item(3, currency)))); w.settle()
            assertEquals("$currency: retry filtered in order",
                BankHistoryBody.Loaded(listOf(item(1, currency), item(3, currency)), refreshing = false), w.body())

            w.holder.refresh()
            assertEquals("$currency: refreshing keeps the list",
                BankHistoryBody.Loaded(listOf(item(1, currency), item(3, currency)), refreshing = true), w.body())
            w.settle()
            w.last().finish(answer(listOf(item(4, other), item(5, currency), item(6, currency)))); w.settle()
            assertEquals("$currency: refresh filtered in order",
                BankHistoryBody.Loaded(listOf(item(5, currency), item(6, currency)), refreshing = false), w.body())

            assertEquals("$currency: three reads", 3, w.read.calls.size)
            assertTrue("$currency: every read keeps the owner and currency",
                w.read.calls.all { it.owner == alice && it.currency == currency })
            assertEquals("$currency: a fresh use per read", 3, w.access.issued.toSet().size)
        }
    }

    // --- S7B_03 ------------------------------------------------------------------------------------------------------------

    @Test fun S7B_03_aReadInFlightIsJoined_noTrailingRead_andIneligibleActionsReadNothing() = runTest {
        val w = World(this)
        w.holder.open(ownerA, "usd-krw"); w.settle()
        repeat(2) { w.holder.open(ownerA, "usd-krw"); w.holder.retry(); w.holder.refresh() }
        w.settle()
        assertEquals("Loading: joined", 1, w.read.calls.size); assertEquals(1, w.access.acquires)
        w.last().finish(answer(listOf(item(1, "usd-krw")))); w.settle()
        assertEquals("no trailing read", 1, w.read.calls.size)

        w.holder.refresh(); w.settle()
        repeat(2) { w.holder.open(ownerA, "usd-krw"); w.holder.retry(); w.holder.refresh() }
        w.settle()
        assertEquals("refreshing: joined", 2, w.read.calls.size); assertEquals(2, w.access.acquires)
        w.last().finish(answer(emptyList())); w.settle()
        assertEquals(BankHistoryBody.Empty, w.body())
        assertEquals("no trailing read", 2, w.read.calls.size)

        // Empty: neither the same open, a retry nor a refresh reads.
        w.holder.open(ownerA, "usd-krw"); w.holder.retry(); w.holder.refresh(); w.settle()
        assertEquals("Empty: nothing", 2, w.read.calls.size)

        val f = World(this)
        f.holder.open(ownerA, "eur-krw"); f.settle()
        f.last().finish(failed(AlertRepositoryException("서버 오류 (500)"))); f.settle()
        f.holder.open(ownerA, "eur-krw"); f.holder.refresh(); f.settle()
        assertEquals("Failed: no open re-read, no refresh", 1, f.read.calls.size)

        val l = World(this)
        l.holder.open(ownerA, "jpy-krw"); l.settle()
        l.last().finish(answer(listOf(item(1, "jpy-krw")))); l.settle()
        l.holder.open(ownerA, "jpy-krw"); l.holder.retry(); l.settle()
        assertEquals("Loaded: no open re-read, no retry", 1, l.read.calls.size)

        l.holder.close(); l.holder.open(ownerA, "jpy-krw"); l.settle()
        assertEquals("close → open reads anew", 2, l.read.calls.size)
    }

    // --- S7B_04 ------------------------------------------------------------------------------------------------------------

    @Test fun S7B_04_timeAloneNeverReads() = runTest {
        val day = 24L * 60 * 60 * 1000
        val w = World(this)
        advanceTimeBy(day); w.settle()
        assertEquals("before open", 0, w.read.calls.size)
        w.holder.open(ownerA, "usd-krw"); w.settle()
        advanceTimeBy(day); w.settle()
        assertEquals("in flight", 1, w.read.calls.size)
        w.last().finish(answer(listOf(item(1, "usd-krw")))); w.settle()
        advanceTimeBy(day); w.settle()
        assertEquals("Loaded", 1, w.read.calls.size)
        w.holder.refresh(); w.settle(); w.last().finish(answer(emptyList())); w.settle()
        advanceTimeBy(day); w.settle()
        assertEquals("Empty", 2, w.read.calls.size)
        val f = World(this)
        f.holder.open(ownerA, "usd-krw"); f.settle(); f.last().finish(failed(AlertRepositoryException("x"))); f.settle()
        advanceTimeBy(day); f.settle()
        assertEquals("Failed", 1, f.read.calls.size)
    }

    // --- S7B_05 ------------------------------------------------------------------------------------------------------------

    private fun World.startOld(refresh: Boolean): Call {
        holder.open(ownerA, "usd-krw"); settle()
        if (refresh) {
            last().finish(answer(listOf(item(1, "usd-krw")))); settle()
            holder.refresh(); settle()
        }
        return last()
    }

    @Test fun S7B_05_anOldReadReachingTheHolderAfterItsSessionEndedChangesNothing() = runTest {
        val actions: List<Pair<String, World.() -> Unit>> = listOf(
            step("close only") { holder.close() },
            step("another owner") { holder.open(ownerB, "usd-krw") },
            step("the same owner, another currency") { holder.open(ownerA, "jpy-krw") },
            step("close and reopen the same") { holder.close(); holder.open(ownerA, "usd-krw") },
            step("A → B → A") { holder.open(ownerB, "usd-krw"); holder.open(ownerA, "usd-krw") }
        )
        val outcomes: List<Pair<String, () -> Outcome>> = listOf(
            "success" to { answer(listOf(item(9, "usd-krw"))) },
            "failure" to { failed(AlertRepositoryException("네트워크 오류")) },
            "withheld" to { Outcome.Throw(TopicUseWithheldException(emptyList())) }
        )
        for (refresh in listOf(false, true)) for ((action, act) in actions) for ((kind, outcome) in outcomes) {
            val label = "${if (refresh) "refresh" else "initial"} / $action / $kind"
            val w = World(this)
            w.read.ignoresCancellation = true
            val old = w.startOld(refresh)
            w.act(); w.settle()
            val before = w.holder.state.value
            val calls = w.read.calls.size
            assertFalse("$label: the old guard is off", old.guard())
            old.finish(outcome()); w.settle()
            assertTrue("$label: positive control — the old answer reached the holder", old.handedBack)
            assertEquals("$label: nothing changed", before, w.holder.state.value)
            assertEquals("$label: no new read", calls, w.read.calls.size)
            if (before != null) {
                val current = w.last()
                assertNotSame("$label: a new read exists", old, current)
                assertTrue("$label: the new read's guard is untouched", current.guard())
                current.finish(answer(listOf(item(2, before.currency)))); w.settle()
                assertEquals("$label: the new session still completes",
                    BankHistoryBody.Loaded(listOf(item(2, before.currency)), refreshing = false), w.body())
            }
        }
    }

    // --- S7B_06 ------------------------------------------------------------------------------------------------------------

    @Test fun S7B_06_aRefusedUseShowsTheGenericFailure_andOnlyAUserRetryReadsAgainUnderAFreshUse() = runTest {
        val cases: List<Pair<String, World.() -> Unit>> = listOf(
            step("withdrawn before the answer") {
                holder.open(ownerA, "usd-krw"); settle()
                access.withdraw(access.issued.last()); last().finish(answer(listOf(item(1, "usd-krw")))); settle()
            },
            step("withheld thrown") {
                holder.open(ownerA, "usd-krw"); settle()
                last().finish(Outcome.Throw(TopicUseWithheldException(emptyList()))); settle()
            },
            step("no use granted") { access.grants = false; holder.open(ownerA, "usd-krw"); settle() }
        )
        for ((label, setUp) in cases) {
            val w = World(this)
            w.setUp()
            assertEquals("$label: generic failure, no list, no spinner", generic, w.body())
            if (label == "no use granted") assertEquals("$label: no read", 0, w.read.calls.size)
            val reads = w.read.calls.size
            val previous = w.access.issued.toList()
            w.access.grants = true; w.access.restoreAll()
            w.holder.currentState(); w.settle()
            assertEquals("$label: access back alone reads nothing", reads, w.read.calls.size)
            assertEquals("$label: and shows nothing old", generic, w.body())
            w.holder.retry(); w.settle()
            assertEquals("$label: the retry reads once", reads + 1, w.read.calls.size)
            assertTrue("$label: under a fresh use", w.access.issued.last() !in previous)
            w.last().finish(answer(listOf(item(5, "usd-krw")))); w.settle()
            assertEquals("$label: loaded", BankHistoryBody.Loaded(listOf(item(5, "usd-krw")), refreshing = false), w.body())
        }
    }

    // --- S7B_07 ------------------------------------------------------------------------------------------------------------

    @Test fun S7B_07_aWithdrawnShownUseRetiresTheBackingState_andNothingOldComesBack() = runTest {
        val w = World(this)
        w.holder.open(ownerA, "usd-krw"); w.settle()
        w.last().finish(answer(listOf(item(1, "usd-krw")))); w.settle()
        w.access.withdraw(w.access.issued.last())
        val masked = w.holder.currentState()
        assertEquals("returned", generic, masked?.body)
        assertEquals("backing", generic, w.body())

        // Refreshing: the in-flight read is withdrawn too, and its late answer restores nothing.
        val r = World(this)
        r.read.ignoresCancellation = true
        r.holder.open(ownerA, "usd-krw"); r.settle()
        r.last().finish(answer(listOf(item(1, "usd-krw")))); r.settle()
        r.holder.refresh(); r.settle()
        val late = r.last()
        r.access.withdraw(r.access.issued.last())
        assertEquals("refreshing withdrawn", generic, r.holder.currentState()?.body)
        assertFalse("its guard is off", late.guard())
        late.finish(answer(listOf(item(2, "usd-krw")))); r.settle()
        assertTrue("positive control: the late answer reached the holder", late.handedBack)
        assertEquals("the late answer restores nothing", generic, r.body())
        r.access.restoreAll(); r.holder.currentState(); r.settle()
        assertEquals("access back: still nothing old", generic, r.body())
        assertEquals("no read", 2, r.read.calls.size)
        r.holder.retry(); r.settle()
        assertEquals("the retry reads", 3, r.read.calls.size)

        for ((label, finish) in listOf<Pair<String, () -> Outcome>>(
            "Empty" to { answer(emptyList()) }, "Failed" to { failed(AlertRepositoryException("서버 오류 (500)")) }
        )) {
            val e = World(this)
            e.holder.open(ownerA, "usd-krw"); e.settle()
            e.last().finish(finish()); e.settle()
            e.access.withdraw(e.access.issued.last())
            assertEquals("$label withdrawn", generic, e.holder.currentState()?.body)
            assertEquals("$label backing", generic, e.body())
        }
    }

    // --- S7B_08 ------------------------------------------------------------------------------------------------------------

    @Test fun S7B_08_aRepositoryFailureKeepsItsLocalMessage_andIsTemporaryOnlyOn503OrRetryAfter() = runTest {
        val noMessage = object : AlertRepositoryException("x") { override val message: String? get() = null }
        val cases = listOf(
            Triple("503", AlertRepositoryException("구독 확인 중", httpFailure(503)), BankHistoryBody.Failed("구독 확인 중", true)),
            Triple("429 + Retry-After", AlertRepositoryException("요청이 너무 많습니다", httpFailure(429, headersOf("Retry-After", "5"))),
                BankHistoryBody.Failed("요청이 너무 많습니다", true)),
            Triple("500 + Retry-After", AlertRepositoryException("서버 오류 (500)", httpFailure(500, headersOf("Retry-After", "1"))),
                BankHistoryBody.Failed("서버 오류 (500)", true)),
            Triple("429", AlertRepositoryException("요청이 너무 많습니다", httpFailure(429)), BankHistoryBody.Failed("요청이 너무 많습니다", false)),
            Triple("500", AlertRepositoryException("서버 오류 (500)", httpFailure(500)), BankHistoryBody.Failed("서버 오류 (500)", false)),
            Triple("401", AlertRepositoryException("인증 오류", httpFailure(401)), BankHistoryBody.Failed("인증 오류", false)),
            Triple("403", AlertRepositoryException("인증 오류", httpFailure(403)), BankHistoryBody.Failed("인증 오류", false)),
            Triple("no HTTP failure", AlertRepositoryException("네트워크 오류"), BankHistoryBody.Failed("네트워크 오류", false)),
            Triple("no message", noMessage, generic)
        )
        for ((label, error, expected) in cases) {
            val w = World(this)
            w.holder.open(ownerA, "usd-krw"); w.settle()
            w.last().finish(failed(error)); w.settle()
            assertEquals(label, expected, w.body())
        }
    }

    // --- S7B_09 ------------------------------------------------------------------------------------------------------------

    @Test fun S7B_09_aRowShowsTheConditionOnlyWhenBothPartsExist_inFixedFormats() {
        val at = Instant.parse("2026-07-16T01:00:00Z")
        val full = AlertHistoryItem(7, 3, "hana", "usd-krw", AlertCondition.ABOVE, 1400.5, 1401.2, at)
        assertEquals(BankHistoryRow(Bank.HANA, "하나은행", "달러", BankHistoryConditionText("1400.50", "△", "이상"),
            "1401.20", "07-16", "10:00"), bankHistoryRow(full))
        assertEquals("below", BankHistoryConditionText("930.00", "▽", "이하"),
            bankHistoryRow(full.copy(condition = AlertCondition.BELOW, threshold = 930.0)).condition)
        assertEquals("no grouping", "12345.68", bankHistoryRow(full.copy(rate = 12345.678)).rate)
        for ((label, row) in listOf(
            "condition only null" to full.copy(condition = null),
            "threshold only null" to full.copy(threshold = null),
            "both null" to full.copy(condition = null, threshold = null)
        )) assertNull("$label: an old row", bankHistoryRow(row).condition)
        assertEquals("settingId alone null is a full row", BankHistoryConditionText("1400.50", "△", "이상"),
            bankHistoryRow(full.copy(settingId = null)).condition)
        val unknown = bankHistoryRow(full.copy(bank = "newbank", currency = "cny-krw"))
        assertEquals("unknown bank", null to "newbank", unknown.bank to unknown.bankName)
        assertEquals("unknown currency", "CNY-KRW", unknown.currencyLabel)
        assertEquals("엔화", bankHistoryRow(full.copy(currency = "jpy-krw")).currencyLabel)
        assertEquals("유로", bankHistoryRow(full.copy(currency = "eur-krw")).currencyLabel)
        assertEquals("the DTO value as is, no 100-yen rescale", "930.12", bankHistoryRow(full.copy(currency = "jpy-krw", rate = 930.12)).rate)
        val boundaries = listOf(
            "2026-07-16T14:59:00Z" to ("07-16" to "23:59"),
            "2026-07-16T15:00:00Z" to ("07-17" to "00:00"),
            "2026-07-31T15:00:00Z" to ("08-01" to "00:00"),
            "2026-12-31T15:00:00Z" to ("01-01" to "00:00")
        )
        for ((utc, kst) in boundaries) {
            val row = bankHistoryRow(full.copy(sentAt = Instant.parse(utc)))
            assertEquals(utc, kst, row.date to row.time)
        }
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("a comma-decimal default locale changes nothing", "1401.20", bankHistoryRow(full).rate)
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    // --- S7B_10 ------------------------------------------------------------------------------------------------------------

    @Test fun S7B_10_anAnswerAndACloseAtTheSameInstantFollowMainsOrder() = runTest {
        // Answer applied, then close: the list may show until the close, then nothing.
        val a = World(this)
        a.read.ignoresCancellation = true
        a.holder.open(ownerA, "usd-krw"); a.settle()
        a.last().finish(answer(listOf(item(1, "usd-krw")))); a.settle()
        assertEquals(BankHistoryBody.Loaded(listOf(item(1, "usd-krw")), refreshing = false), a.body())
        a.holder.close()
        assertNull("closed", a.holder.state.value)

        // Answer released, then close before it is applied: nothing applied, and a reopen is not touched.
        val b = World(this)
        b.read.ignoresCancellation = true
        b.holder.open(ownerA, "usd-krw"); b.settle()
        val old = b.last()
        old.finish(answer(listOf(item(1, "usd-krw"))))
        b.holder.close()
        b.settle()
        assertTrue("positive control: the old answer reached the holder", old.handedBack)
        assertNull("not applied", b.holder.state.value)
        b.holder.open(ownerA, "usd-krw")
        b.settle()
        assertEquals("the reopen is loading, untouched", BankHistoryBody.Loading, b.body())
        assertEquals("one new read", 2, b.read.calls.size)
    }

    // --- S7B_11 ------------------------------------------------------------------------------------------------------------

    @Test fun S7B_11_anEndedScopeRetiresTheSheet_andAnOrdinaryCancellationEndsTheSpinner() = runTest {
        val w = World(this)
        w.read.ignoresCancellation = true
        w.holder.open(ownerA, "usd-krw"); w.settle()
        val waiting = w.last()
        w.scopeJob.cancel()
        assertFalse("the guard is off at once", waiting.guard())
        assertNull("currentState retires", w.holder.currentState())
        assertNull("and the backing state", w.holder.state.value)
        waiting.finish(answer(listOf(item(1, "usd-krw")))); w.settle()
        assertNull("a late answer restores nothing", w.holder.state.value)
        val acquires = w.access.acquires
        w.holder.open(ownerA, "usd-krw"); w.holder.retry(); w.holder.refresh(); w.settle()
        assertEquals("inactive scope: no read", 1, w.read.calls.size)
        assertEquals("inactive scope: no acquire", acquires, w.access.acquires)
        assertNull(w.holder.state.value)

        for ((label, outcome) in listOf<Pair<String, () -> Outcome>>(
            "a late failure" to { failed(AlertRepositoryException("x")) },
            "a late withheld" to { Outcome.Throw(TopicUseWithheldException(emptyList())) }
        )) {
            val v = World(this)
            v.read.ignoresCancellation = true
            v.holder.open(ownerA, "usd-krw"); v.settle()
            val call = v.last()
            v.scopeJob.cancel()
            call.finish(outcome()); v.settle()
            assertTrue("$label: positive control", call.handedBack)
            assertNull("$label: nothing restored", v.holder.state.value)
        }

        val c = World(this)
        c.holder.open(ownerA, "usd-krw"); c.settle()
        val child: Job = c.scopeJob.children.single()
        c.last().finish(Outcome.Throw(CancellationException("dropped below")))
        c.settle()
        assertEquals("the spinner ends", generic, c.body())
        assertTrue("the cancellation is rethrown", child.isCancelled)
        assertTrue("the scope lives on", c.scopeJob.isActive)
    }

    // --- S7B_12 ------------------------------------------------------------------------------------------------------------

    @Test fun S7B_12_everyCompletionPassesTheSameApplicationGate() = runTest {
        val identity = World(this)
        identity.holder.open(ownerA, "usd-krw"); identity.settle()
        identity.last().finish(answer(listOf(item(1, "usd-krw"))) { throw AuthIdentityChangedException() }); identity.settle()
        assertEquals("an identity refusal at the fold", generic, identity.body())

        val withdrawingOutcomes: List<Pair<String, (World) -> Outcome>> = listOf(
            "success" to { w: World -> answer(listOf(item(1, "usd-krw"))) { w.access.withdraw(w.access.issued.last()) } },
            "failure" to { w: World -> failed(AlertRepositoryException("네트워크 오류")) { w.access.withdraw(w.access.issued.last()) } }
        )
        for ((label, outcomeFor) in withdrawingOutcomes) {
            val w = World(this)
            w.holder.open(ownerA, "usd-krw"); w.settle()
            w.last().finish(outcomeFor(w)); w.settle()
            assertEquals("withdrawn inside the fold, $label: refused at the commit", generic, w.body())
        }

        for (refresh in listOf(false, true)) {
            val phase = if (refresh) "refreshing" else "Loading"
            val w = World(this)
            w.startOld(refresh)
            w.access.withdraw(w.access.issued.last())
            assertFalse("$phase: the in-flight guard follows the use alone", w.last().guard())
            w.last().finish(Outcome.Throw(AlertRepositoryException("네트워크 오류"))); w.settle()
            assertEquals("$phase: a direct throw after a withdrawal is refused", generic, w.body())

            val control = World(this)
            control.startOld(refresh)
            control.last().finish(Outcome.Throw(AlertRepositoryException("네트워크 오류"))); control.settle()
            assertEquals("$phase control: admitted, the repository message stays",
                BankHistoryBody.Failed("네트워크 오류", false), control.body())
        }
    }

    // --- S7B_13 ------------------------------------------------------------------------------------------------------------

    @Test fun S7B_13_aReadThatEndsWithoutSuspendingLeavesNoOwnershipBehind() = runTest {
        val cases: List<Triple<String, Outcome, BankHistoryBody>> = listOf(
            Triple("success", answer(listOf(item(1, "usd-krw"))), BankHistoryBody.Loaded(listOf(item(1, "usd-krw")), false)),
            Triple("empty", answer(emptyList()), BankHistoryBody.Empty),
            Triple("repository failure", failed(AlertRepositoryException("서버 오류 (500)")), BankHistoryBody.Failed("서버 오류 (500)", false)),
            Triple("withheld", Outcome.Throw(TopicUseWithheldException(emptyList())), generic),
            Triple("another exception", Outcome.Throw(IllegalStateException("boom")), generic)
        )
        for ((label, outcome, expected) in cases) {
            val w = World(this, immediate = true)
            w.read.immediately = outcome
            w.holder.open(ownerA, "usd-krw")
            assertEquals("$label: settled at once", expected, w.body())
            w.read.immediately = answer(listOf(item(2, "usd-krw")))
            when (expected) {
                is BankHistoryBody.Failed -> w.holder.retry()
                is BankHistoryBody.Loaded -> w.holder.refresh()
                else -> { w.holder.close(); w.holder.open(ownerA, "usd-krw") }
            }
            assertEquals("$label: the next eligible action reads exactly once", 2, w.read.calls.size)
            assertEquals("$label: and settles", BankHistoryBody.Loaded(listOf(item(2, "usd-krw")), false), w.body())
        }
    }

    // --- S7B_14 ------------------------------------------------------------------------------------------------------------

    @Test fun S7B_14_theGuardReadOnAnotherThreadSeesEveryEnd() = runTest {
        val thread = Executors.newSingleThreadExecutor()
        try {
            fun offMain(guard: () -> Boolean): Boolean = thread.submit<Boolean> { guard() }.get(5, TimeUnit.SECONDS)
            val ends: List<Pair<String, World.() -> Unit>> = listOf(
                step("close") { holder.close() },
                step("another owner") { holder.open(ownerB, "usd-krw") },
                step("another currency") { holder.open(ownerA, "eur-krw") },
                step("scope ended") { scopeJob.cancel() }
            )
            for ((label, end) in ends) {
                val w = World(this)
                w.holder.open(ownerA, "usd-krw"); w.settle()
                val guard = w.read.calls.first().guard
                assertTrue("$label: admitted off Main before", offMain(guard))
                w.end()
                assertFalse("$label: refused off Main after", offMain(guard))
            }
        } finally {
            thread.shutdownNow()
        }
    }
}
