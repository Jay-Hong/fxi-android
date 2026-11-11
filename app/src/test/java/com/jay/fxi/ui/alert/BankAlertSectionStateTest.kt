package com.jay.fxi.ui.alert

import com.jay.fxi.data.alerts.AlertLoadFailure
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.AlertRateLimitEvidence
import com.jay.fxi.data.alerts.AlertRateLimitFloor
import com.jay.fxi.data.alerts.AlertRetryPolicy
import com.jay.fxi.data.alerts.AlertSettingsAccess
import com.jay.fxi.data.alerts.BankAlertSettingsOwner
import com.jay.fxi.data.alerts.BankAlertSettingsState
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.AuthenticatedEndpoint
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.SupportedCurrency
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 E5a1 contract (R4c/S7/s7e5 API r2): the read-only bank section's projection (API r2 §2 and §5 row 2) — the
 * rows are this currency's by its code, the first row of a repeated id, newest createdAt first with equal createdAt in input
 * order; the active count is the enabled rows left after that; loading (for loading and retrying) and the error card show only
 * when this currency has no row, idle and ready with no row are empty; the four fixed error copies are chosen by the typed
 * failure alone and no server text reaches the section even when the repository failure carries some; the refresh button
 * spins only while sending and is enabled unless cooling down or sending (a requested-but-waiting demand stays tappable); and
 * only the dollar, yen and euro tabs map to a bank currency. All but two tests are pure; P09 and P11 drive the real N1 owner
 * on virtual time (clock, owner scope and sleep on one TestCoroutineScheduler) and project what it really publishes.
 * The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BankAlertSectionStateTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val t1 = Instant.parse("2026-11-11T01:00:00Z")
    private val t2 = Instant.parse("2026-11-11T02:00:00Z")
    private val t3 = Instant.parse("2026-11-11T03:00:00Z")
    private val usd = SupportedCurrency.USD_KRW.code

    private fun row(
        id: Int,
        currency: String,
        createdAt: Instant = t1,
        enabled: Boolean = true,
        triggered: Boolean = false,
        threshold: Double = 1400.0 + id
    ) = AlertSetting(id, "user-a", "hana", currency, AlertCondition.ABOVE, threshold, enabled, triggered, createdAt, createdAt, null, null)

    private fun owned(phase: AlertLoadPhase, list: List<AlertSetting>?) = BankAlertSettingsState(phase, list, list?.let { alice })

    private fun project(
        phase: AlertLoadPhase,
        list: List<AlertSetting>?,
        currency: SupportedCurrency = SupportedCurrency.USD_KRW,
        manual: ManualRefreshState = ManualRefreshState()
    ): BankAlertSectionState = bankAlertSectionState(owned(phase, list), manual, currency)

    private val loading: BankAlertSectionState.Content = BankAlertSectionState.Content.Loading
    private val empty: BankAlertSectionState.Content = BankAlertSectionState.Content.Empty
    private fun errorCard(copy: String): BankAlertSectionState.Content = BankAlertSectionState.Content.Error(copy)
    private fun rows(vararg list: AlertSetting): BankAlertSectionState.Content = BankAlertSectionState.Content.Rows(list.toList())
    private fun shownRows(state: BankAlertSectionState): List<AlertSetting> {
        val content = state.content
        assertTrue("premise: rows are shown, not $content", content is BankAlertSectionState.Content.Rows)
        return (content as BankAlertSectionState.Content.Rows).rows
    }

    /** Every typed terminal failure with its one fixed copy: the kinds other than NETWORK, with and without an HTTP status. */
    private val terminals: List<Pair<AlertLoadFailure, String>> = buildList<Pair<AlertLoadFailure, String>> {
        add(AlertLoadFailure.AuthRequired to COPY_LOGIN)
        add(AlertLoadFailure.Forbidden to COPY_FORBIDDEN)
        add(AlertLoadFailure.Failed(AlertRepositoryFailureKind.NETWORK, null) to COPY_NETWORK)
        AlertRepositoryFailureKind.entries.filter { it != AlertRepositoryFailureKind.NETWORK }
            .forEach { add(AlertLoadFailure.Failed(it, null) to COPY_GENERIC) }
        listOf(401, 403, 404, 408, 429, 500, 503)
            .forEach { add(AlertLoadFailure.Failed(AlertRepositoryFailureKind.HTTP, it) to COPY_GENERIC) }
    }
    private fun copyFor(failure: AlertLoadFailure): String = terminals.first { it.first == failure }.second
    private val allPhases: List<AlertLoadPhase> =
        listOf<AlertLoadPhase>(AlertLoadPhase.Idle, AlertLoadPhase.Loading, AlertLoadPhase.Retrying, AlertLoadPhase.Ready) +
            terminals.map { AlertLoadPhase.TerminalError(it.first) }

    /** A manual state and the button it must give. Each keeps sending ⇒ requested, the one invariant the owner publishes. */
    private class ManualCase(val name: String, val state: ManualRefreshState, val spinning: Boolean, val enabled: Boolean)
    private val manualCases = listOf(
        ManualCase("no manual demand, no deadline", ManualRefreshState(), spinning = false, enabled = true),
        ManualCase("requested, waiting for the floor or access", ManualRefreshState(requested = true), spinning = false, enabled = true),
        ManualCase("requested and sending", ManualRefreshState(requested = true, sending = true), spinning = true, enabled = false),
        ManualCase("cooling down", ManualRefreshState(coolingDown = true, cooldownUntilMillis = 10_000), spinning = false, enabled = false),
        ManualCase("an expired deadline kept", ManualRefreshState(cooldownUntilMillis = 3_000), spinning = false, enabled = true),
        ManualCase(
            "requested while cooling down",
            ManualRefreshState(requested = true, coolingDown = true, cooldownUntilMillis = 10_000), spinning = false, enabled = false
        ),
        ManualCase(
            "sending while cooling down",
            ManualRefreshState(requested = true, sending = true, coolingDown = true, cooldownUntilMillis = 10_000),
            spinning = true, enabled = false
        )
    )

    private class ListCase(val name: String, val list: List<AlertSetting>?, val expectedRows: List<AlertSetting>, val expectedActive: Int)

    // ---- §2 projection: the whole matrix ----

    /** API r2 §2 / §5 row 2: typed phase × list (null, empty, other currencies only, this currency) × manual state, every currency. */
    @Test fun `S7E5A1_P01 every typed phase, list and manual state projects to the agreed section state`() {
        var cells = 0
        for (currency in SupportedCurrency.entries) {
            val otherCodes = SupportedCurrency.entries.filter { it != currency }.map { it.code }
            assertEquals("premise: two other currencies", 2, otherCodes.size)
            val (other1, other2) = otherCodes
            val oldest = row(1, currency.code, createdAt = t1)
            val newest = row(3, currency.code, createdAt = t3, enabled = false)
            val middle = row(4, currency.code, createdAt = t2)
            val lists = listOf(
                ListCase("no snapshot", null, emptyList(), 0),
                ListCase("an empty snapshot", emptyList(), emptyList(), 0),
                ListCase(
                    "other currencies only",
                    listOf(row(2, other1, createdAt = t3), row(5, other2, createdAt = t2), row(6, other1, enabled = false)),
                    emptyList(), 0
                ),
                ListCase(
                    "this currency among others",
                    listOf(oldest, row(2, other1, createdAt = t3), newest, middle, row(5, other2, createdAt = t2)),
                    listOf(newest, middle, oldest), 2
                )
            )
            for (phase in allPhases) for (listCase in lists) for (manual in manualCases) {
                val expectedContent = when {
                    listCase.expectedRows.isNotEmpty() -> BankAlertSectionState.Content.Rows(listCase.expectedRows)
                    phase == AlertLoadPhase.Loading || phase == AlertLoadPhase.Retrying -> loading
                    phase is AlertLoadPhase.TerminalError -> errorCard(copyFor(phase.failure))
                    phase == AlertLoadPhase.Idle || phase == AlertLoadPhase.Ready -> empty
                    else -> throw AssertionError("unlisted phase $phase")
                }
                val expected = BankAlertSectionState(
                    content = expectedContent,
                    activeCount = listCase.expectedActive,
                    refreshSpinning = manual.spinning,
                    refreshEnabled = manual.enabled
                )
                assertEquals(
                    "$currency / $phase / ${listCase.name} / ${manual.name}",
                    expected, bankAlertSectionState(owned(phase, listCase.list), manual.state, currency)
                )
                cells++
            }
        }
        assertEquals("premise: the whole matrix ran", SupportedCurrency.entries.size * allPhases.size * 4 * manualCases.size, cells)
    }

    // ---- §2 projection rules, one by one ----

    /** §2: the filter is equality with currency.code — not the display name, the server tab key, another case or padding. */
    @Test fun `S7E5A1_P02 only rows whose currency equals this currency's code are shown`() {
        val exact = row(1, usd)
        val nearMisses = listOf(
            row(2, "USD-KRW"), row(3, "usd"), row(4, "usd-krw "), row(5, SupportedCurrency.USD_KRW.displayName),
            row(6, SupportedCurrency.JPY_KRW.code), row(7, SupportedCurrency.EUR_KRW.code)
        )
        val state = project(AlertLoadPhase.Ready, nearMisses + exact)
        assertEquals("the exact code is shown, alone", rows(exact), state.content)
        assertEquals("and only it is counted", 1, state.activeCount)
        val onePerCurrency = SupportedCurrency.entries.map { row(10 + it.ordinal, it.code) }
        for (currency in SupportedCurrency.entries) {
            assertEquals(
                "$currency shows its own row alone",
                rows(onePerCurrency[currency.ordinal]), project(AlertLoadPhase.Ready, onePerCurrency, currency).content
            )
        }
    }

    /** §2: filter, then distinctBy id keeping the first, then the sort — a later, newer copy of an id is dropped. */
    @Test fun `S7E5A1_P03 a repeated id keeps its first row of this currency, deduplicated before the sort`() {
        val jpySeven = row(7, SupportedCurrency.JPY_KRW.code, createdAt = t3)
        val firstOne = row(1, usd, createdAt = t1, enabled = false, threshold = 1401.0)
        val laterOne = row(1, usd, createdAt = t3, enabled = true, threshold = 1499.0)
        val usdSeven = row(7, usd, createdAt = t2)
        val input = listOf(jpySeven, firstOne, laterOne, usdSeven)
        val dollar = project(AlertLoadPhase.Ready, input)
        assertEquals(
            "the first id-1 row stays although its later copy is newer; another currency's id 7 hides nothing",
            rows(usdSeven, firstOne), dollar.content
        )
        assertEquals("the count is taken after the dedup: the kept id-1 row is disabled", 1, dollar.activeCount)
        assertEquals("the yen section has its own id 7", rows(jpySeven), project(AlertLoadPhase.Ready, input, SupportedCurrency.JPY_KRW).content)
    }

    /** §2: createdAt descending; equal createdAt keeps the input order (not the id order, in either direction). */
    @Test fun `S7E5A1_P04 rows run newest createdAt first and equal createdAt keeps the input order`() {
        val a = row(10, usd, createdAt = t2)
        val b = row(11, usd, createdAt = t1)
        val c = row(12, usd, createdAt = t2)
        val d = row(13, usd, createdAt = t3)
        val e = row(14, usd, createdAt = t2)
        assertEquals("ties a, c, e in input order", rows(d, a, c, e, b), project(AlertLoadPhase.Ready, listOf(a, b, c, d, e)).content)
        assertEquals("ties e, c, a in input order", rows(d, e, c, a, b), project(AlertLoadPhase.Ready, listOf(e, d, c, b, a)).content)
    }

    /** §2: activeCount = enabled rows after the filter and the dedup; triggered does not decide it; the phase does not change it. */
    @Test fun `S7E5A1_P05 the active count is the enabled rows left after the filter and the dedup`() {
        val list = listOf(
            row(1, usd, enabled = true),
            row(2, usd, enabled = false),
            row(3, usd, enabled = true, triggered = true),
            row(4, usd, enabled = false, triggered = true),
            row(5, SupportedCurrency.JPY_KRW.code, enabled = true),
            row(2, usd, enabled = true)
        )
        for (phase in allPhases) {
            val state = project(phase, list)
            assertEquals("premise ($phase): four rows of this currency remain", 4, shownRows(state).size)
            assertEquals("$phase: rows 1 and 3 only", 2, state.activeCount)
        }
        val none = project(AlertLoadPhase.Ready, listOf(row(2, usd, enabled = false), row(5, SupportedCurrency.JPY_KRW.code, enabled = true)))
        assertEquals("premise: one disabled row of this currency is shown", 1, shownRows(none).size)
        assertEquals("nothing enabled here, the other currency's enabled row does not count", 0, none.activeCount)
    }

    /** §2 selection 4: rows of this currency show under every phase, loading and terminal included. */
    @Test fun `S7E5A1_P06 rows of this currency are shown under every phase`() {
        val mine = row(1, usd)
        for (phase in allPhases) {
            val state = project(phase, listOf(row(2, SupportedCurrency.EUR_KRW.code), mine))
            assertEquals("$phase: the row, not a loading view or an error card", rows(mine), state.content)
            assertEquals("$phase: counted", 1, state.activeCount)
        }
    }

    /** §2 selections 1–3: with no row of this currency (no snapshot, empty, other currencies only). */
    @Test fun `S7E5A1_P07 with no row of this currency loading and retrying show loading, a terminal error its card, idle and ready empty`() {
        val lists = listOf<List<AlertSetting>?>(
            null,
            emptyList<AlertSetting>(),
            listOf(row(2, SupportedCurrency.JPY_KRW.code), row(3, SupportedCurrency.EUR_KRW.code))
        )
        for (list in lists) {
            val label = list?.let { "${it.size} rows" } ?: "no snapshot"
            assertEquals("$label: loading", loading, project(AlertLoadPhase.Loading, list).content)
            assertEquals("$label: retrying is the same loading view", loading, project(AlertLoadPhase.Retrying, list).content)
            for ((failure, copy) in terminals) {
                assertEquals("$label: $failure", errorCard(copy), project(AlertLoadPhase.TerminalError(failure), list).content)
            }
            assertEquals("$label: idle", empty, project(AlertLoadPhase.Idle, list).content)
            assertEquals("$label: ready", empty, project(AlertLoadPhase.Ready, list).content)
        }
    }

    // ---- §2 error copies ----

    private fun copyShownFor(failure: AlertLoadFailure): String {
        val content = project(AlertLoadPhase.TerminalError(failure), null).content
        assertTrue("premise: an error card for $failure, not $content", content is BankAlertSectionState.Content.Error)
        return (content as BankAlertSectionState.Content.Error).message
    }

    /** §2: exactly four copies; Failed is chosen by its kind alone, so an HTTP 401/403 is not the login/forbidden copy. */
    @Test fun `S7E5A1_P08 the four error copies are fixed and chosen by the typed failure alone`() {
        assertEquals("로그인이 필요합니다.", copyShownFor(AlertLoadFailure.AuthRequired))
        assertEquals("접근 권한이 없습니다.", copyShownFor(AlertLoadFailure.Forbidden))
        assertEquals("네트워크 연결을 확인해 주세요.", copyShownFor(AlertLoadFailure.Failed(AlertRepositoryFailureKind.NETWORK, null)))
        assertEquals("알림 설정을 불러오지 못했습니다.", copyShownFor(AlertLoadFailure.Failed(AlertRepositoryFailureKind.UNEXPECTED, null)))
        val seen = mutableSetOf<String>()
        for (kind in AlertRepositoryFailureKind.entries) for (status in listOf(null, 401, 403, 404, 429, 500, 503)) {
            val expected = if (kind == AlertRepositoryFailureKind.NETWORK) COPY_NETWORK else COPY_GENERIC
            val shown = copyShownFor(AlertLoadFailure.Failed(kind, status))
            assertEquals("$kind / $status", expected, shown)
            seen += shown
        }
        seen += copyShownFor(AlertLoadFailure.AuthRequired)
        seen += copyShownFor(AlertLoadFailure.Forbidden)
        assertEquals("four distinct copies and no other", setOf(COPY_LOGIN, COPY_FORBIDDEN, COPY_NETWORK, COPY_GENERIC), seen)
    }

    // ---- the real owner on virtual time ----

    private sealed interface Reply {
        class Bound(val result: AuthBoundResult<List<AlertSetting>>) : Reply
        class Thrown(val error: Throwable) : Reply
    }

    private fun loaded(vararg list: AlertSetting): Reply =
        Reply.Bound(AuthBoundResult<List<AlertSetting>>({}, Result.success(list.toList())))

    private fun failing(error: Throwable): Reply = Reply.Bound(AuthBoundResult<List<AlertSetting>>({}, Result.failure(error)))

    /** An HTTP failure whose message, raw body, error and detail are all the server's text. */
    private fun serverHttp(status: Int, kind: AuthenticatedFailureKind) = AlertRepositoryException(
        SERVER_TEXT,
        httpFailure = AuthenticatedHttpFailure(status, headersOf(), SERVER_TEXT.toByteArray(), SERVER_TEXT, JsonPrimitive(SERVER_TEXT), kind)
    )

    /**
     * The N1 owner as the owner contract builds it: every access input standing for [alice], the production access, a real
     * floor and policy on the test clock, jitter 0, the owner and its sleep on a StandardTestDispatcher of the test's scheduler.
     * The GET waits for the test's answer.
     */
    private inner class OwnerRig(private val test: TestScope) {
        private val grant = TopicGrantToken(11)
        private val fence = TopicSessionFence(alice, "epoch-1", grant)
        private val snapshot = TopicAccessSnapshot.INITIAL.copy(
            revision = 1L,
            facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet())
        )
        private val premium = MutableStateFlow(OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.PremiumConfirmed))
        private val access = AlertSettingsAccess(
            { alice }, { fence }, SnapshotTopicUseAuthority { snapshot }, { premium.value }, { true },
            GraphProtectedAdmission({ alice }, DeletionAdmissionStore())
        )
        val floor = AlertRateLimitFloor({ test.testScheduler.currentTime })
        private val calls = mutableListOf<CompletableDeferred<Reply>>()
        private val job = SupervisorJob()
        val owner = BankAlertSettingsOwner(
            { _, _ ->
                val call = CompletableDeferred<Reply>().also { calls += it }
                when (val reply = call.await()) {
                    is Reply.Bound -> reply.result
                    is Reply.Thrown -> throw reply.error
                }
            },
            access, floor, AlertRetryPolicy(), { test.testScheduler.currentTime }, { 0.0 },
            CoroutineScope(job + StandardTestDispatcher(test.testScheduler)), { delay(it) },
            premium, MutableStateFlow(0L), MutableStateFlow(false), 1_000L
        )

        init {
            settle()
        }

        val now: Long get() = test.testScheduler.currentTime
        /** GETs sent and not yet answered. */
        val pending: Int get() = calls.count { !it.isCompleted }

        fun settle() = test.runCurrent()
        fun advance(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }
        fun foreground() {
            owner.onForeground(false)
            settle()
        }
        fun answer(reply: Reply) {
            val open = calls.filter { !it.isCompleted }
            check(open.size == 1) { "exactly one GET awaits its answer, not ${open.size}" }
            open.single().complete(reply)
            settle()
        }
        /** Answers every GET with [reply] as soon as it is sent, for [forMillis] of virtual time. */
        fun answerAsSent(forMillis: Long, reply: Reply) {
            val end = now + forMillis
            while (true) {
                settle()
                calls.filter { !it.isCompleted }.forEach { it.complete(reply) }
                settle()
                if (now >= end) return
                test.advanceTimeBy(minOf(STEP, end - now))
            }
        }
        /** What the section shows now for [currency], from the owner's two published states. */
        fun section(currency: SupportedCurrency = SupportedCurrency.USD_KRW): BankAlertSectionState =
            bankAlertSectionState(owner.state.value, owner.manualRefresh.value, currency)
        fun close() = job.cancel()
    }

    private fun ownerRig(test: TestScope, body: OwnerRig.() -> Unit) {
        val rig = OwnerRig(test)
        try {
            rig.body()
        } finally {
            rig.close()
        }
    }

    private class ServerCase(val name: String, val error: Throwable, val thrown: Boolean, val failure: AlertLoadFailure, val copy: String)

    /**
     * §2 / scope :28: the repository failure carries the server's text (message, body, error, detail; or a thrown exception's
     * message), the owner ends in its typed terminal error with no snapshot, and the section shows only the fixed copy — for every
     * currency, and in the retrying window before it as the plain loading view.
     */
    @Test fun `S7E5A1_P09 a server message in the repository failure never reaches the section`() = runTest {
        val cases = listOf(
            ServerCase("401", serverHttp(401, AuthenticatedFailureKind.AUTHENTICATION), false, AlertLoadFailure.AuthRequired, COPY_LOGIN),
            ServerCase("403", serverHttp(403, AuthenticatedFailureKind.KNOWN_AUTHORIZATION), false, AlertLoadFailure.Forbidden, COPY_FORBIDDEN),
            ServerCase(
                "network", AlertRepositoryException(SERVER_TEXT, failureKind = AlertRepositoryFailureKind.NETWORK), false,
                AlertLoadFailure.Failed(AlertRepositoryFailureKind.NETWORK, null), COPY_NETWORK
            ),
            ServerCase(
                "404", serverHttp(404, AuthenticatedFailureKind.UNKNOWN_NOT_FOUND), false,
                AlertLoadFailure.Failed(AlertRepositoryFailureKind.HTTP, 404), COPY_GENERIC
            ),
            ServerCase(
                "decode", AlertRepositoryException(SERVER_TEXT, failureKind = AlertRepositoryFailureKind.DECODE), false,
                AlertLoadFailure.Failed(AlertRepositoryFailureKind.DECODE, null), COPY_GENERIC
            ),
            ServerCase(
                "thrown", RuntimeException(SERVER_TEXT), true,
                AlertLoadFailure.Failed(AlertRepositoryFailureKind.UNEXPECTED, null), COPY_GENERIC
            )
        )
        for (scenario in cases) ownerRig(this) {
            assertTrue("premise (${scenario.name}): the failure carries the server text", scenario.error.message.orEmpty().contains(SERVER_MARK))
            val reply = if (scenario.thrown) Reply.Thrown(scenario.error) else failing(scenario.error)
            foreground()
            assertEquals("premise (${scenario.name}): the first GET is sent", 1, pending)
            answer(reply)
            if (scenario.failure == AlertLoadFailure.Failed(AlertRepositoryFailureKind.NETWORK, null)) {
                assertEquals("premise: a network failure is retried first", AlertLoadPhase.Retrying, owner.state.value.phase)
                val retrying = section()
                assertEquals("a retry with no row is the loading view", loading, retrying.content)
                assertFalse("and carries no server text", retrying.toString().contains(SERVER_MARK))
                answerAsSent(RETRY_WINDOW, reply)
            }
            assertEquals(
                "premise (${scenario.name}): the owner ends in its typed terminal error",
                AlertLoadPhase.TerminalError(scenario.failure), owner.state.value.phase
            )
            assertNull("premise (${scenario.name}): with no snapshot", owner.state.value.settings)
            for (currency in SupportedCurrency.entries) {
                val shown = section(currency)
                assertEquals("${scenario.name} / $currency: the fixed copy", errorCard(scenario.copy), shown.content)
                assertFalse("${scenario.name} / $currency: no server text in the section", shown.toString().contains(SERVER_MARK))
            }
        }
    }

    // ---- §2 refresh button ----

    /** §2: refreshSpinning = sending; refreshEnabled = !coolingDown && !sending; neither depends on the content, nor changes it. */
    @Test fun `S7E5A1_P10 the refresh button spins only while sending and is enabled unless cooling down or sending`() {
        val inputs = listOf<Pair<AlertLoadPhase, List<AlertSetting>?>>(
            AlertLoadPhase.Loading to null,
            AlertLoadPhase.Ready to listOf(row(1, usd)),
            AlertLoadPhase.TerminalError(AlertLoadFailure.Forbidden) to emptyList<AlertSetting>()
        )
        for ((phase, list) in inputs) {
            val reference = project(phase, list)
            for (manual in manualCases) {
                val state = project(phase, list, manual = manual.state)
                assertEquals("$phase / ${manual.name}: spinner", manual.spinning, state.refreshSpinning)
                assertEquals("$phase / ${manual.name}: enabled", manual.enabled, state.refreshEnabled)
                assertEquals("$phase / ${manual.name}: the content is the manual state's to ignore", reference.content, state.content)
                assertEquals("$phase / ${manual.name}: and so is the count", reference.activeCount, state.activeCount)
            }
        }
        val waitingInput = ManualRefreshState(requested = true, sending = false)
        assertTrue("premise: a manual demand is held", waitingInput.requested)
        val waiting = project(AlertLoadPhase.Ready, listOf(row(1, usd)), manual = waitingInput)
        assertTrue("requested but not sending keeps the button enabled", waiting.refreshEnabled)
        assertFalse("and draws no spinner", waiting.refreshSpinning)
    }

    /** §1 rule 4 seen through §2: an automatic GET and a manual demand held by the floor neither spin nor disable; sending does. */
    @Test fun `S7E5A1_P11 the real owner's manual states keep a waiting request tappable without a spinner`() = runTest {
        ownerRig(this) {
            val mine = row(1, usd)
            foreground()
            assertEquals("premise: an automatic GET is open", 1, pending)
            assertFalse("premise: it carries no manual demand", owner.manualRefresh.value.requested)
            val automatic = section()
            assertEquals("premise: no snapshot yet, the loading view", loading, automatic.content)
            assertTrue("an automatic fetch leaves the refresh button enabled", automatic.refreshEnabled)
            assertFalse("and draws no spinner", automatic.refreshSpinning)
            answer(loaded(mine))
            assertEquals("premise: the snapshot is shown", rows(mine), section().content)

            floor.record(AuthenticatedEndpoint.NOTIFICATION_SETTINGS, AlertRateLimitEvidence.of(emptyList(), 429, "30"))
            val floorWait = floor.remainingMillis(AuthenticatedEndpoint.NOTIFICATION_SETTINGS)
            assertTrue("premise: a floor holds the next GET", floorWait > 1)
            owner.manual()
            settle()
            assertTrue("premise: the manual demand is held", owner.manualRefresh.value.requested)
            assertFalse("premise: but it is not sending", owner.manualRefresh.value.sending)
            assertEquals("premise: no GET goes out under the floor", 0, pending)
            val waiting = section()
            assertTrue("a requested-but-waiting demand keeps the button enabled", waiting.refreshEnabled)
            assertFalse("and draws no spinner", waiting.refreshSpinning)

            advance(floorWait - 1)
            assertTrue("premise: still held just before the floor ends", owner.manualRefresh.value.requested && pending == 0)
            assertTrue("still enabled", section().refreshEnabled)
            assertFalse("still no spinner", section().refreshSpinning)
            advance(1)
            assertEquals("premise: the manual GET is open once the floor passes", 1, pending)
            assertTrue("premise: and the demand is sending", owner.manualRefresh.value.sending)
            val sending = section()
            assertTrue("sending spins", sending.refreshSpinning)
            assertFalse("and disables the button", sending.refreshEnabled)
            assertEquals("the snapshot stays shown while it runs", rows(mine), sending.content)

            answer(loaded(mine))
            val deadline = owner.manualRefresh.value.cooldownUntilMillis
            assertTrue("premise: the manual result starts a cooldown", owner.manualRefresh.value.coolingDown)
            assertTrue("premise: with a deadline ahead", deadline != null && deadline > now + 1)
            assertFalse("premise: the demand is no longer requested", owner.manualRefresh.value.requested)
            val cooling = section()
            assertFalse("cooling down disables the button", cooling.refreshEnabled)
            assertFalse("without a spinner", cooling.refreshSpinning)
            advance(deadline!! - now - 1)
            assertTrue("premise: still cooling just before the deadline", owner.manualRefresh.value.coolingDown)
            assertFalse("still disabled", section().refreshEnabled)
            advance(1)
            assertFalse("premise: the cooldown has ended at the deadline", owner.manualRefresh.value.coolingDown)
            val after = section()
            assertTrue("enabled again", after.refreshEnabled)
            assertFalse("with no spinner", after.refreshSpinning)
        }
    }

    // ---- §2 composable: the bank tabs ----

    /** §2: FreeTab → SupportedCurrency for USD/JPY/EUR only; every other tab (a data tab such as tether included) has none. */
    @Test fun `S7E5A1_P12 only the dollar, yen and euro tabs map to a bank currency`() {
        val expected = mapOf(
            FreeTab.USD to SupportedCurrency.USD_KRW,
            FreeTab.JPY to SupportedCurrency.JPY_KRW,
            FreeTab.EUR to SupportedCurrency.EUR_KRW
        )
        for ((tab, currency) in expected) assertEquals("$tab", currency, bankCurrencyOf(tab))
        val rest = FreeTab.entries - expected.keys
        assertTrue("premise: other tabs exist, tether among them and a data tab", FreeTab.TETHER in rest && FreeTab.TETHER.isData)
        assertTrue("premise: and news", FreeTab.NEWS in rest)
        for (tab in rest) assertNull("$tab has no bank section", bankCurrencyOf(tab))
    }

    /** §2/§3: the projection does not rewrite the settings it is given; the rows shown are those values as they were. */
    @Test fun `S7E5A1_P13 the projection leaves the given settings untouched`() {
        val input = listOf(
            row(1, usd, createdAt = t1, enabled = false, triggered = true),
            row(2, usd, createdAt = t2),
            row(1, usd, createdAt = t3),
            row(3, SupportedCurrency.JPY_KRW.code)
        )
        val before = input.map { it.copy() }
        val shown = shownRows(project(AlertLoadPhase.Ready, input))
        assertEquals("premise: two rows are shown", 2, shown.size)
        assertEquals("the given list and every setting in it are unchanged", before, input)
        assertEquals("the shown rows are the given values as they were", listOf(before[1], before[0]), shown)
    }

    /**
     * §2 / scope :19: the sort key is createdAt alone. An edit gives a row a newer updatedAt but moves it nowhere — an older
     * alert edited last stays below the newer ones, and within equal createdAt the input order holds even when the later
     * input row was edited more recently (in either id direction).
     */
    @Test fun `S7E5A1_P14 an older alert edited later stays below newer ones, rows sort by createdAt and never by updatedAt`() {
        val t7 = Instant.parse("2026-11-11T07:00:00Z")
        val t8 = Instant.parse("2026-11-11T08:00:00Z")
        val t9 = Instant.parse("2026-11-11T09:00:00Z")
        val oldestEditedLast = row(1, usd, createdAt = t1).copy(updatedAt = t9)
        val middleEdited = row(2, usd, createdAt = t2).copy(updatedAt = t8)
        val newestUnedited = row(3, usd, createdAt = t3)
        val distinct = listOf(oldestEditedLast, newestUnedited, middleEdited)
        assertTrue("premise: every edit comes after its creation", distinct.all { it.updatedAt >= it.createdAt })
        assertTrue(
            "premise: updatedAt runs exactly against createdAt",
            oldestEditedLast.updatedAt > middleEdited.updatedAt && middleEdited.updatedAt > newestUnedited.updatedAt
        )
        assertEquals(
            "createdAt descending: the alert edited last but created first stays at the bottom",
            rows(newestUnedited, middleEdited, oldestEditedLast), project(AlertLoadPhase.Ready, distinct).content
        )

        val tenUnedited = row(10, usd, createdAt = t2)
        val elevenEdited = row(11, usd, createdAt = t2).copy(updatedAt = t7)
        assertEquals(
            "equal createdAt keeps the input order although the later input row (id 11) was edited more recently",
            rows(tenUnedited, elevenEdited), project(AlertLoadPhase.Ready, listOf(tenUnedited, elevenEdited)).content
        )
        val elevenUnedited = row(11, usd, createdAt = t2)
        val tenEdited = row(10, usd, createdAt = t2).copy(updatedAt = t7)
        assertEquals(
            "equal createdAt keeps the input order although the later input row (id 10) was edited more recently",
            rows(elevenUnedited, tenEdited), project(AlertLoadPhase.Ready, listOf(elevenUnedited, tenEdited)).content
        )
    }

    private companion object {
        const val COPY_LOGIN = "로그인이 필요합니다."
        const val COPY_FORBIDDEN = "접근 권한이 없습니다."
        const val COPY_NETWORK = "네트워크 연결을 확인해 주세요."
        const val COPY_GENERIC = "알림 설정을 불러오지 못했습니다."
        const val SERVER_MARK = "SRV-7f3a"
        const val SERVER_TEXT = "SRV-7f3a 서버 점검 중입니다 (server says: maintenance window)"
        const val STEP = 250L
        /** Longer than the six network attempts' backoff (2 + 4 + 8 + 16 + 32 s at jitter 0). */
        const val RETRY_WINDOW = 120_000L
    }
}
