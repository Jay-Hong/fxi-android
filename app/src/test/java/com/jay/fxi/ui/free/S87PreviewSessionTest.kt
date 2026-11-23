package com.jay.fxi.ui.free

import com.jay.fxi.data.alerts.SourceAlertRowPolicy
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.domain.model.SourceAlertSetting
import com.jay.fxi.ui.alert.SourceAlertDraftRules
import com.jay.fxi.ui.alert.SourceAlertEditor
import com.jay.fxi.ui.alert.SourceAlertEditorInput
import com.jay.fxi.ui.alert.SourceAlertEditorMode
import kotlinx.datetime.Instant
import org.junit.Test

/**
 * S8-7 (`R4c/S8/s8_7_contract.codex.r2.md` §2·§3·§4): the tether free preview's reducer and the draft rules it shares with the
 * premium add editor. Each test proves its premises first and then fails, if at all, with its one contract message.
 */
class S87PreviewSessionTest {

    private val t1 = Instant.parse("2026-11-23T01:30:00Z")
    private val sources = SourceAlertRowPolicy.EXCHANGES.sources(SourceAlertRowPolicy.SETTINGS_ASSET)

    private fun check(message: String, ok: Boolean) {
        if (!ok) throw AssertionError(message)
    }

    private fun opened(uid: String = "u1", quotes: Map<String, Double> = mapOf("upbit" to 1432.5)): Pair<FreeSourceAlertPreviewSession, Long> {
        val s = FreeSourceAlertPreviewSession()
        s.invalidate(uid, FreeTab.TETHER)
        val token = requireNotNull(s.open(uid, FreeTab.TETHER, s.currentBinding, quotes, t1)) { "premise: the preview opens" }
        return s to token
    }

    private fun setting(source: String) = SourceAlertSetting(
        id = 7, userId = "u1", source = source, asset = "usdt-krw", condition = AlertCondition.ABOVE, threshold = 1500.0,
        isEnabled = true, triggered = false, repeatIntervalSec = 300, createdAt = t1, updatedAt = null, triggeredAt = null
    )

    /** The free seed and the premium add seed are one rule; so are a pick's refill, its kept text, and the repeat line. */
    @Test
    fun S01_sharedAddSeedAndPick() {
        check("premise: 정책 순서의 첫 거래소는 upbit", sources.first() == "upbit" && sources.size == 5)
        val quotes = mapOf("upbit" to 1432.5, "bithumb" to 1431.4)
        val free = SourceAlertDraftRules.seed(sources, quotes, null)
        val wantSeed = SourceAlertEditorInput("upbit", AlertCondition.BELOW, "1433", RepeatIntervalChange.Once)
        val typed = free.copy(thresholdText = "1500", condition = AlertCondition.ABOVE, repeat = RepeatIntervalChange.Every(RepeatInterval.FIVE_MINUTES))
        val picks = listOf(
            SourceAlertDraftRules.pick(typed, "bithumb", quotes) == typed.copy(source = "bithumb", thresholdText = "1431"),
            SourceAlertDraftRules.pick(typed, "coinone", quotes) == typed.copy(source = "coinone"),
            SourceAlertDraftRules.pick(typed, "upbit", quotes) == typed.copy(source = "upbit", thresholdText = "1433")
        )
        val premium = listOf(
            SourceAlertEditor.initial(SourceAlertEditorMode.Add, sources, quotes) == free,
            SourceAlertEditor.initial(SourceAlertEditorMode.Add, sources, quotes, "bithumb") == SourceAlertDraftRules.seed(sources, quotes, "bithumb"),
            SourceAlertEditor.initial(SourceAlertEditorMode.Add, sources, quotes, "krx") == free,
            SourceAlertEditor.onSourcePicked(typed, "bithumb", SourceAlertEditorMode.Add, quotes) == SourceAlertDraftRules.pick(typed, "bithumb", quotes),
            SourceAlertEditor.onSourcePicked(typed, "coinone", SourceAlertEditorMode.Add, quotes) == SourceAlertDraftRules.pick(typed, "coinone", quotes)
        )
        val edit = SourceAlertEditorMode.Edit(setting("korbit"))
        val editInput = SourceAlertEditor.initial(edit, sources, quotes)
        val editKeeps = editInput.repeat == RepeatIntervalChange.Keep && editInput.source == "korbit" &&
            SourceAlertEditor.onSourcePicked(editInput, "upbit", edit, quotes) == editInput.copy(source = "upbit")
        val labels = listOf("1분", "5분", "10분", "30분", "1시간", "2시간", "4시간", "6시간", "12시간", "1일")
        val choices = listOf<RepeatInterval?>(null) + RepeatInterval.entries
        val expected = listOf("조건 충족 시 1회만 발송") + labels.map { "조건 충족 동안 ${it}마다 발송" }
        val repeatLine = choices.size == expected.size && choices.zip(expected).all { (interval, want) ->
            val repeat = interval?.let { RepeatIntervalChange.Every(it) } ?: RepeatIntervalChange.Once
            SourceAlertDraftRules.addRepeatDescription(interval) == want &&
                SourceAlertEditor.repeatTexts(SourceAlertEditorMode.Add, repeat).description == want
        }
        check("추가 입력의 초기값과 재선택", free == wantSeed && SourceAlertDraftRules.seed(sources, emptyMap(), null).thresholdText == "" &&
            picks.all { it } && premium.all { it } && editKeeps && repeatLine)
    }

    /** The quotes are copied at open: later changes to the caller's map reach neither the frozen quotes nor the draft. */
    @Test
    fun S02_openCopiesQuotes() {
        val caller = linkedMapOf("upbit" to 1432.5)
        val s = FreeSourceAlertPreviewSession()
        s.invalidate("u1", FreeTab.TETHER)
        val token = requireNotNull(s.open("u1", FreeTab.TETHER, s.currentBinding, caller, t1))
        caller["upbit"] = 1.0
        caller["bithumb"] = 2.0
        val open = s.state as FreeSourceAlertPreviewSession.State.Open
        val frozen = open.quotes == mapOf("upbit" to 1432.5) && open.asOf == t1 && open.input.thresholdText == "1433"
        check("premise: 닫힌다", s.close(token) && s.dismissed(token) == null && s.state == FreeSourceAlertPreviewSession.State.Closed)
        val next = requireNotNull(s.open("u1", FreeTab.TETHER, s.currentBinding, mapOf("upbit" to 1500.4), null))
        val reopened = (s.state as FreeSourceAlertPreviewSession.State.Open).let { it.token == next && it.quotes == mapOf("upbit" to 1500.4) && it.input.thresholdText == "1500" }
        check("열 때 복사한 시세", frozen && reopened)
    }

    /** Only the current token acts, the first wish to close is the one kept, and a closing sheet takes no input. */
    @Test
    fun S03_tokensAndFirstIntent() {
        val refused = FreeSourceAlertPreviewSession().let { s ->
            s.invalidate("u1", FreeTab.TETHER)
            val b = s.currentBinding
            val wrongUid = s.open("u2", FreeTab.TETHER, b, emptyMap(), null) == null
            val first = s.open("u1", FreeTab.TETHER, b, emptyMap(), null)
            val nested = s.open("u1", FreeTab.TETHER, b, emptyMap(), null) == null
            s.invalidate("u1", FreeTab.USD)
            val offTether = s.open("u1", FreeTab.USD, s.currentBinding, emptyMap(), null) == null
            wrongUid && first != null && nested && offTether
        }
        val (s, token) = opened()
        val stale = token + 1
        val base = s.state
        s.edit(stale, (base as FreeSourceAlertPreviewSession.State.Open).input.copy(thresholdText = "9"))
        val staleIgnored = s.state == base && !s.close(stale) && !s.subscribe(stale) && s.dismissed(stale) == null && s.state == base
        val keepBad = base.input.copy(repeat = RepeatIntervalChange.Keep)
        s.edit(token, keepBad)
        s.edit(token, base.input.copy(source = "krx"))
        val badEditsIgnored = s.state == base
        val closeFirst = s.close(token) && !s.subscribe(token)
        s.edit(token, base.input.copy(thresholdText = "1"))
        val lockedWhileClosing = (s.state as FreeSourceAlertPreviewSession.State.Closing).let { it.input == base.input && !it.paywall }
        val closeSignal = s.dismissed(token)
        val (c, ctoken) = opened()
        val ctaFirst = c.subscribe(ctoken) && !c.close(ctoken) && (c.state as FreeSourceAlertPreviewSession.State.Closing).paywall
        val ctaSignal = c.dismissed(ctoken)
        check("현재 토큰의 최초 종료 의도", refused && staleIgnored && badEditsIgnored && closeFirst && lockedWhileClosing && closeSignal == null &&
            ctaFirst && ctaSignal != null && c.consume(ctaSignal))
    }

    /** An opener built under an earlier binding opens nothing after the owner or the tab left and came back. */
    @Test
    fun S04_oldBindingCannotOpen() {
        fun cycle(away: () -> Unit): Boolean {
            val s = FreeSourceAlertPreviewSession()
            s.invalidate("u1", FreeTab.TETHER)
            val before = s.currentBinding
            s.invalidate("u2", FreeTab.TETHER).also { away() }
            s.invalidate("u1", FreeTab.TETHER)
            return s.open("u1", FreeTab.TETHER, before, emptyMap(), null) == null &&
                s.open("u1", FreeTab.TETHER, s.currentBinding, emptyMap(), null) != null
        }
        val tabTrip = FreeSourceAlertPreviewSession().let { s ->
            s.invalidate("u1", FreeTab.TETHER)
            val before = s.currentBinding
            s.invalidate("u1", FreeTab.USD)
            s.invalidate("u1", FreeTab.TETHER)
            s.currentBinding != before && s.open("u1", FreeTab.TETHER, before, emptyMap(), null) == null &&
                s.open("u1", FreeTab.TETHER, s.currentBinding, emptyMap(), null) != null
        }
        check("복귀 전 opener 거부", cycle {} && tabTrip)
    }

    /** A paywall signal is used up once, and never by another session, after dispose, or after a newer open. */
    @Test
    fun S05_signalIsConsumedOnce() {
        val (s, token) = opened()
        check("premise: 구독 신호가 나온다", s.subscribe(token))
        val signal = requireNotNull(s.dismissed(token))
        val (other, otherToken) = opened()
        check("premise: 다른 session 도 같은 token 번호로 열렸다", otherToken == token && other.subscribe(otherToken))
        val own = requireNotNull(other.dismissed(otherToken))
        val foreign = !other.consume(signal) && other.consume(own)
        val once = s.consume(signal) && !s.consume(signal)
        val (n, nt) = opened()
        n.subscribe(nt)
        val late = requireNotNull(n.dismissed(nt))
        requireNotNull(n.open("u1", FreeTab.TETHER, n.currentBinding, emptyMap(), null))
        val afterReopen = !n.consume(late)
        val (d, dt) = opened()
        d.subscribe(dt)
        val disposedSignal = requireNotNull(d.dismissed(dt))
        d.dispose()
        val afterDispose = !d.consume(disposedSignal)
        check("신호 소비 정확히 한 번", once && foreign && afterReopen && afterDispose)
    }

    /**
     * The bank and the tether preview end the same way: each step's state kind, token and wish, and how many paywalls the host
     * hands on — the bank host asks `deliverable` and then drops its pending signal, the tether host consumes it.
     */
    @Test
    fun S06_bankAndSourceTransitionTracesAgree() {
        val bank = FreeAlertPreviewSession()
        val source = FreeSourceAlertPreviewSession()
        val bankTrace = mutableListOf<String>()
        val sourceTrace = mutableListOf<String>()
        fun kind(state: Any): String = when (state) {
            is FreeAlertPreviewSession.State.Open -> "open ${state.token}"
            is FreeAlertPreviewSession.State.Closing -> "closing ${state.token} ${state.paywall}"
            is FreeSourceAlertPreviewSession.State.Open -> "open ${state.token}"
            is FreeSourceAlertPreviewSession.State.Closing -> "closing ${state.token} ${state.paywall}"
            else -> "closed"
        }
        var bankPending: FreeAlertPreviewSession.PaywallSignal? = null
        var sourcePending: FreeSourceAlertPreviewSession.PaywallSignal? = null
        var bankDelivered = 0
        var sourceDelivered = 0
        fun step(label: String, onBank: () -> Any?, onSource: () -> Any?) {
            bankTrace += "$label ${onBank()} ${kind(bank.state)}"
            sourceTrace += "$label ${onSource()} ${kind(source.state)}"
        }
        fun deliver() {
            bankPending?.let { if (bank.deliverable(it)) bankDelivered++ }
            bankPending = null
            sourcePending?.let { if (source.consume(it)) sourceDelivered++ }
            sourcePending = null
        }
        bank.invalidate("u1", FreeTab.USD)
        source.invalidate("u1", FreeTab.TETHER)
        step("open", { bank.open("u1", FreeTab.USD, emptyMap(), null) }, { source.open("u1", FreeTab.TETHER, source.currentBinding, emptyMap(), null) })
        step("edit", {
            bank.edit(1, (bank.state as FreeAlertPreviewSession.State.Open).input.copy(thresholdText = "1777"))
            (bank.state as FreeAlertPreviewSession.State.Open).input.thresholdText
        }, {
            source.edit(1, (source.state as FreeSourceAlertPreviewSession.State.Open).input.copy(thresholdText = "1777"))
            (source.state as FreeSourceAlertPreviewSession.State.Open).input.thresholdText
        })
        check("은행과 테더의 종료 순서", (source.state as FreeSourceAlertPreviewSession.State.Open).input.thresholdText == "1777")
        step("cta", { bank.subscribe(1) }, { source.subscribe(1) })
        step("cta again", { bank.subscribe(1) }, { source.subscribe(1) })
        step("close after cta", { bank.close(1) }, { source.close(1) })
        step("dismissed", { bank.dismissed(1)?.also { bankPending = it } != null }, { source.dismissed(1)?.also { sourcePending = it } != null })
        deliver()
        deliver()
        step("open 2", { bank.open("u1", FreeTab.USD, emptyMap(), null) }, { source.open("u1", FreeTab.TETHER, source.currentBinding, emptyMap(), null) })
        step("close first", { bank.close(2) }, { source.close(2) })
        step("cta after close", { bank.subscribe(2) }, { source.subscribe(2) })
        step("dismissed 2", { bank.dismissed(2) != null }, { source.dismissed(2) != null })
        step("open 3", { bank.open("u1", FreeTab.USD, emptyMap(), null) }, { source.open("u1", FreeTab.TETHER, source.currentBinding, emptyMap(), null) })
        step("cta 3", { bank.subscribe(3) }, { source.subscribe(3) })
        step("leave", { bank.invalidate("u1", FreeTab.NEWS) }, { source.invalidate("u1", FreeTab.NEWS) })
        step("dismissed 3", { bank.dismissed(3) != null }, { source.dismissed(3) != null })
        step("dispose", { bank.dispose() }, { source.dispose() })
        step("open after dispose", { bank.open("u1", FreeTab.USD, emptyMap(), null) }, { source.open("u1", FreeTab.TETHER, source.currentBinding, emptyMap(), null) })
        val normalised = bankTrace.map { it.replace("kotlin.Unit", "unit") }
        check("은행과 테더의 종료 순서", normalised == sourceTrace.map { it.replace("kotlin.Unit", "unit") } && bankDelivered == 1 && sourceDelivered == 1)
    }
}
