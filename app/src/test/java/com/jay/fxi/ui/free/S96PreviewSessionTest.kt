package com.jay.fxi.ui.free

import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.ui.free.FreeComparisonAlertPreviewSession.State
import kotlinx.datetime.Instant
import org.junit.Test

class S96PreviewSessionTest {
    private val tab = FreeTab.TETHER
    private val session = FreeComparisonAlertPreviewSession().apply { invalidate("u", tab) }
    private val t1 = Instant.parse("2026-11-27T01:00:00Z")
    private val t2 = Instant.parse("2026-11-27T02:00:00Z")
    private fun open() = session.open("u", tab, "signed", session.currentBinding, emptyMap(), t1)
    private fun opened() = session.state as? State.Open ?: throw AssertionError("premise: 열린 시트")
    private fun expect(label: String, expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("$label\nexpected=$expected actual=$actual")
    }
    @Test fun S01() {
        for (page in FreeTab.entries) for (kind in listOf("signed", "absolute", "other")) {
            val s = FreeComparisonAlertPreviewSession()
            s.invalidate("u", page)
            val token = s.open("u", page, kind, s.currentBinding, emptyMap(), null)
            val valid = page.isData && (kind == "absolute" || kind == "signed" && page == tab)
            expect("허용 tab과 kind", valid, token != null)
            if (!valid) continue
            val f = (s.state as State.Open).fields
            val left = (if (page == tab) listOf("upbit","bithumb","coinone","korbit","gopax")
                else listOf("investing","kb","hana","shinhan","woori","ibk","nh","sc","bs")).map { it to if (page == tab) "usdt-krw" else "${page.serverTab}-krw" }
            val right = if (kind == "signed") listOf("investing","kb","hana").map { it to "usd-krw" } else left
            expect("왼쪽 후보", left, f.candidates())
            expect("오른쪽 후보", right, f.candidates(true))
            val ordered = left.flatMap { a -> right.filter { it != a }.map { b -> a to b } }
            val actualPairs = f.pairs().map { it.left to it.right }
            expect("허용 쌍", ordered, actualPairs)
            expect("순서 무관 쌍", if (kind == "signed") 15 else if (page == tab) 10 else 36, actualPairs.map { setOf(it.first,it.second) }.toSet().size)
            expect("seed", listOf(if (f.signed) 0.0 else 2.0, "gte", null, false), listOf(f.threshold, f.operator, f.repeatSeconds, f.wide))
            expect("선택 seed", if (f.signed) listOf(left[1],right[0]) else emptyList(), f.selection)
            s.edit(requireNotNull(token)) { fields, quotes -> fields.select(left[0], false, quotes) }
            val selected = (s.state as State.Open).fields.selection
            expect("무시세 후보 선택", if (f.signed) listOf(left[0], right[0]) else listOf(left[0]), selected)
            if (f.signed) {
                expect("좌우 반전 거부",f,f.select(right[0],false,emptyMap()))
                expect("비교 후보 역순 거부",f,f.select(left[0],true,emptyMap()))
            }
            expect("다른 leg 거부", f, f.select("other" to "usd-krw", false, emptyMap()))
        }
    }
    @Test fun S02() {
        val left = "bithumb" to "usdt-krw"
        val right = "investing" to "usd-krw"
        val alternate = "upbit" to "usdt-krw"
        val raw = mutableMapOf(alternate to 1395.0, left to 1390.0, right to 1400.0, ("other" to "usd-krw") to 333.0)
        val token = session.open("u", tab, "signed", session.currentBinding, raw, t1) ?: throw AssertionError("premise: 열린 시트")
        raw[left] = 9000.0
        raw.clear()
        val s = opened()
        expect("열 때 복사한 비교 시세", listOf("1390", "1400.00", "-10.0원", "-0.71%", t1, 3),
            listOf(s.fields.caption(left, s.quotes), s.fields.caption(right,s.quotes), *s.fields.current(s.quotes).drop(1).toTypedArray(), s.asOf, s.quotes.size))
        session.edit(token) { f, quotes -> f.select(alternate, false, quotes) }
        expect("동결 시세로 선택", listOf(alternate, right) to -5.0, opened().fields.let { it.selection to it.threshold })
        session.edit(token) { f, _ -> f.step(1) }
        session.edit(token) { f, _ -> f.step(1).copy(repeatSeconds = 600) }
        expect("연속 편집", listOf(-4.8, 600), listOf(opened().fields.threshold, opened().fields.repeatSeconds))
        session.close(token)
        session.dismissed(token)
        session.open("u", tab, "signed", session.currentBinding, mapOf(left to 1601.0, right to 1400.0), t2)
        expect("재open", listOf(201.0, true, null, t2), opened().let { listOf(it.fields.threshold,it.fields.wide,it.fields.repeatSeconds,it.asOf) })
    }
    @Test fun S03() {
        val binding = session.currentBinding
        fun aba() { session.invalidate("u", FreeTab.USD); session.invalidate("u", tab) }
        aba()
        expect("이전 binding의 열기", null, session.open("u",tab,"signed",binding,emptyMap(),null))
        val first = open() ?: throw AssertionError("premise: 첫 token")
        expect("열림 중 중복", null, open())
        session.subscribe(first)
        val closing = session.state
        session.edit(first) { fields,_ -> fields.step(1) }
        expect("닫힘 중 편집",closing,session.state)
        expect("닫힘 중 중복", null, open())
        val signal = session.dismissed(first) ?: throw AssertionError("premise: 신호")
        val other = FreeComparisonAlertPreviewSession()
        other.invalidate("u",tab)
        other.invalidate("u",FreeTab.USD)
        other.invalidate("u",tab)
        other.open("u",tab,"signed",other.currentBinding,emptyMap(),null)
        other.close(first)
        other.dismissed(first)
        expect("premise: 같은 binding", signal.binding, other.currentBinding)
        expect("다른 session의 전달", false, other.consume(signal))
        aba()
        expect("이전 binding의 지연 전달", false, session.consume(signal))
        val second = open() ?: throw AssertionError("premise: 다음 token")
        session.edit(first) { f, _ -> f.step(1) }
        expect("이전 token의 편집", 0.0, opened().fields.threshold)
        expect("이전 token 닫기", false, session.close(first))
        expect("이전 token dismiss", null, session.dismissed(first))
        expect("새 token",second,opened().token)
        session.invalidate("v",tab)
        expect("UID 교체", State.Closed, session.state)
        expect("옛 UID", null, open())
        session.invalidate("u",tab)
        session.dispose()
        expect("폐기 후 열기",null,open())
    }
    @Test fun S04() {
        for (paywall in listOf(false,true)) {
            val t = open() ?: throw AssertionError("premise: 열린 token")
            expect("첫 의도",true,if (paywall) session.subscribe(t) else session.close(t))
            expect("다시 구독",false,session.subscribe(t))
            expect("다시 닫기",false,session.close(t))
            val signal = session.dismissed(t)
            expect("일반 닫기의 전달",paywall,signal != null)
            expect("반복 dismiss",null,session.dismissed(t))
            if (signal != null) expect("닫힌 token의 단일 소비", listOf(true,false), listOf(session.consume(signal), session.consume(signal)))
        }
        val t = open()!!
        session.subscribe(t)
        val old = session.dismissed(t)!!
        val fresh = open()!!
        session.close(fresh)
        session.dismissed(fresh)
        expect("새 열림 이후 전달",false,session.consume(old))
        val owned = requireNotNull(open())
        session.subscribe(owned)
        val pending = session.dismissed(owned) ?: throw AssertionError("premise: owner 교체 전 신호")
        session.invalidate("v", tab)
        expect("owner 교체 후 전달", false, session.consume(pending))
        session.invalidate("u", tab)
        expect("owner 복귀 후 전달", false, session.consume(pending))
    }
}
