package com.jay.fxi.ui.alert

import com.jay.fxi.data.alerts.ComparisonAlertDraft
import com.jay.fxi.data.alerts.ComparisonAlertPair
import com.jay.fxi.domain.model.ComparisonAlertSetting
import com.jay.fxi.domain.model.TopicQuoteKey
import kotlinx.datetime.Instant
import org.junit.Assert.*
import org.junit.Test

internal fun comparisonFixture(id: Int = 41, tab: String = "tether", signed: Boolean = false): ComparisonAlertSetting {
    val asset = if (tab == "tether") "usdt-krw" else "$tab-krw"
    return ComparisonAlertSetting(id, "a", tab, if (tab == "tether") "bithumb" else "kb", asset,
        if (signed) "investing" else if (tab == "tether") "upbit" else "hana", if (signed) "usd-krw" else asset,
        if (signed) "signed" else "absolute", "gte", 2.0, true, false, null, null, Instant.fromEpochMilliseconds(0), null)
}

class ComparisonAlertDraftRulesTest {
    private val exchanges = listOf("upbit", "bithumb", "coinone", "korbit", "gopax")
    private val banks = listOf("investing", "kb", "hana", "shinhan", "woori", "ibk", "nh", "sc", "bs")
    private val refs = listOf("investing", "kb", "hana")
    private val usd = "usd-krw"
    private val tether = "usdt-krw"
    private fun seed(tab: String, kind: String = "absolute") = ComparisonFields.create(tab, kind, emptyMap())
    private fun quotes(vararg values: Pair<ComparisonLeg, Double>) =
        values.associate { (leg, price) -> TopicQuoteKey(leg.first, leg.second) to price }
    private fun market(delta: Double) = quotes(("bithumb" to tether) to (2000 + delta), ("investing" to usd) to 2000.0)

    @Test fun D01_pickerOrderAndPairAdmission() {
        for (tab in listOf("usd", "jpy", "eur", "tether")) {
            val f = seed(tab)
            val names = if (tab == "tether") exchanges else banks
            val legs = names.map { it to if (tab == "tether") tether else "$tab-krw" }
            assertEquals(legs, f.candidates())
            assertEquals(legs.flatMap { a -> legs.filter { it != a }.map { b -> ComparisonAlertPair(tab, "absolute", a, b) } }, f.pairs())
            assertFalse(ComparisonAlertPair(tab, "absolute", legs.first(), legs.first()).supported())
            assertFalse(ComparisonAlertPair(tab, "absolute", legs.first(), legs[1].first to "xxx").supported())
            val three = legs.take(3).fold(f) { state, leg -> state.select(leg, false, emptyMap()) }
            assertEquals(legs.slice(1..2), three.selection)
            assertEquals(listOf(legs[2]), three.select(legs[1], false, emptyMap()).selection)
        }
        val f = seed("tether", "signed")
        assertEquals(refs.map { it to usd }, f.candidates(true))
        assertEquals(exchanges.flatMap { a -> refs.map { b -> ComparisonAlertPair("tether","signed",a to tether,b to usd) } }, f.pairs())
        val p = ComparisonAlertPair("tether", "signed", "upbit" to tether, "kb" to usd)
        for (bad in listOf(p.copy(left = p.right, right = p.left), p.copy(tab = "usd"), p.copy(tab = "news"),
            p.copy(right = "citi" to usd), p.copy(left = "UPBIT" to tether), p.copy(left = " upbit" to tether),
            p.copy(left = "upbit " to tether), p.copy(left = "upbit" to "USDT-KRW"))) {
            assertFalse(bad.toString(), bad.supported())
        }
    }

    @Test fun D02_restoreSuggestStepAndValidate() {
        val row = comparisonFixture()
        val signed = comparisonFixture(signed = true)
        fun restore(value: Double, r: ComparisonAlertSetting = row) = ComparisonFields.restore(r.copy(threshold = value, operator = "lte", repeatIntervalSec = 600))
        for ((r, value) in listOf(row to 10000.0, signed to -10000.0, signed to 1500.0, comparisonFixture(tab="usd") to 500.05)) {
            val state = restore(value,r)
            val changed = state.select(state.candidates()[2], false, emptyMap())
            assertEquals(value, changed.threshold, 0.0)
            assertEquals(value, state.copy(repeatSeconds = 60).draft()?.threshold ?: Double.NaN, 0.0)
            assertEquals("lte", changed.operator)
            assertEquals(600, changed.repeatSeconds)
        }
        for (bad in listOf(10000.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(restore(bad).draft())
            assertNotNull(restore(bad).step(-1).draft())
        }
        assertNull(restore(-10000.001, signed).draft())
        val f = restore(0.0)
        assertFalse(ComparisonAlertDraft(row.originalPair(), "gte", -1.0, null).supported())
        assertNull(f.copy(operator = "unknown").draft())
        assertNotNull(f.copy(operator = "unknown").condition("gte", emptyMap()).draft())
        for (fixture in listOf(row, signed)) {
            var edited = ComparisonFields.restore(fixture.copy(operator = "future"))
            assertEquals("future", edited.operator)
            assertNull(edited.draft())
            assertEquals(ComparisonCondition.Unknown, edited.conditionLabel)
            assertEquals("조건 선택", edited.conditionLabel.label)
            for (expected in listOf("gte", "lte", "gte")) {
                edited = edited.condition(nextOperator(edited.operator), market(6.0))
                assertEquals(expected, edited.operator)
                assertEquals(fixture.threshold, edited.threshold, 0.0)
                assertNotNull(edited.draft())
            }
        }
        val absSeed = seed("tether")
        val (left, right) = absSeed.candidates().take(2)
        fun prices(diff: Double) = quotes(left to (100 + diff), right to 100.0)
        var absPair = absSeed.select(left, false, prices(2.0)).select(right, false, prices(2.0))
        var signedPair = ComparisonFields.create("tether", "signed", market(2.0)).step(1)
        for ((direction, diff) in listOf("lte" to 6.0, "gte" to 10.0)) {
            val manual = absPair.step(1)
            assertTrue(manual.manual)
            absPair = manual.condition(nextOperator(manual.operator), prices(diff))
            assertEquals(direction, absPair.operator)
            assertEquals(diff + 1, absPair.threshold, 0.0)
            assertFalse(absPair.manual)
            signedPair = signedPair.condition(nextOperator(signedPair.operator), market(diff))
            assertEquals(direction, signedPair.operator)
            assertEquals(2.1, signedPair.threshold, 0.0)
            assertTrue(signedPair.manual)
        }
        val retapped = signedPair.select(signedPair.selection.first(), false, market(10.0))
        assertEquals(10.0, retapped.threshold, 0.0)
        assertFalse(retapped.manual)
        val unknown = f.copy(repeatSeconds = 61)
        assertEquals("기존 반복 간격 유지", unknown.repeatLabel)
        assertNull(unknown.draft())
        for (n in listOf(null,60,300,600,1800,3600,7200,14400,21600,43200,86400)) assertNotNull(unknown.copy(repeatSeconds=n).draft())
        for ((tab,diff,expected) in listOf(Triple("usd",0.25,0.4), Triple("usd",1.125,1.2), Triple("usd",4.125,4.2), Triple("tether",2.5,4.0))) {
            val seed = seed(tab)
            val legs = seed.candidates().take(2)
            val q = quotes(legs[0] to (100 + diff), legs[1] to 100.0)
            val first = seed.select(legs[0],false,q)
            val step = if (tab == "usd") 0.1 else 1.0
            assertEquals(step,first.threshold,0.0)
            val pair = first.select(legs[1],false,q)
            assertEquals(expected,pair.threshold,0.0)
            assertEquals(expected,pair.step(1).condition("lte",q).threshold,0.0)
            assertEquals(step,pair.select(legs[0],false,q).threshold,0.0)
            assertEquals(step,first.select(legs[1],false,emptyMap()).threshold,0.0)
        }
        fun newSigned(d: Double) = ComparisonFields.create("tether","signed",market(d))
        assertEquals(-14.3,newSigned(-14.25).threshold,0.0)
        assertEquals(-15.0,newSigned(-14.5).widen(market(-14.5)).threshold,0.0)
        assertEquals(-1000.0,newSigned(-1013.5).threshold,0.0)
        assertTrue(newSigned(-150.0).wide)
        assertEquals(-150.0, newSigned(-150.0).threshold, 0.0)
        assertTrue(restore(1500.0,signed).wide)
        for (v in listOf(100.0, -100.0)) {
            assertFalse(newSigned(v).wide)
            assertEquals(v, newSigned(v).threshold, 0.0)
        }
        val widened = newSigned(-14.5).widen(market(-14.5))
        assertTrue(widened.widen(market(0.0)).wide)
        assertTrue(widened.step(1).wide)
        assertTrue(widened.select(widened.selection[0], false, market(3.0)).wide)
        val absent = seed("tether","signed")
        val picked = absent.select(absent.selection[0],false,market(-14.25))
        assertEquals(0.0,absent.threshold,0.0)
        assertEquals(-14.3,picked.threshold,0.0)
        assertEquals(-14.3,picked.condition("lte",market(6.0)).threshold,0.0)
        assertEquals(-14.3,picked.select(picked.selection[1],true,emptyMap()).threshold,0.0)
        for (direction in listOf(-1,1)) assertEquals(1000.0,restore(1500.0,signed).step(direction).threshold,0.0)
        val off = restore(50.55, comparisonFixture(tab="usd"))
        assertEquals(50.6,off.step(1).threshold,0.0)
        assertEquals(50.5,off.step(-1).threshold,0.0)
        for ((ms,n) in listOf(399L to 0,400L to 1,1300L to 10,1400L to 11)) {
            assertEquals(n,ComparisonStepRepeat.count(ms))
            val end = (1..n).fold(f) { state, rep -> state.step(1,rep) }
            assertEquals(if (n == 11) 20.0 else n.toDouble(),end.threshold,0.0)
        }
        var elapsed = 0L
        for (fired in 0 until 11) {
            elapsed += ComparisonStepRepeat.nextDelay(fired)
            assertEquals(fired, ComparisonStepRepeat.count(elapsed - 1))
            assertEquals(fired + 1, ComparisonStepRepeat.count(elapsed))
        }
    }

    @Test fun D03_duplicateUsesOriginalPairAndEpsilon() {
        val row = comparisonFixture().copy(threshold=0.0)
        val f = ComparisonFields.restore(row)
        assertFalse(f.duplicate(listOf(row),row.id))
        assertTrue(f.duplicate(listOf(row.copy(id=2,isEnabled=false,triggered=true,repeatIntervalSec=60)),row.id))
        for (delta in listOf(0.000999,0.001,0.001001))
            assertEquals(delta < 0.001,f.duplicate(listOf(row.copy(threshold=delta)),null))
        val pair = row.originalPair()
        assertTrue(pair.matches(pair.copy(left=pair.right,right=pair.left)))
        assertTrue(f.duplicate(listOf(row.copy(leftSource=row.rightSource,rightSource=row.leftSource)),null))
        val signed = comparisonFixture(signed=true).originalPair()
        assertFalse(signed.matches(signed.copy(left=signed.right,right=signed.left)))
        for (other in listOf(row.copy(operator="lte"),row.copy(tab="usd"),row.copy(rightAsset=usd))) assertFalse(f.duplicate(listOf(other),null))
    }

    @Test fun D04_currentDifferenceUsesMatchingQuotes() {
        val f = seed("tether","signed")
        assertEquals(listOf("지금 빗썸-인베스팅","-14.0원","-1.00%"), f.current(quotes(f.selection[0] to 1386.0, f.selection[1] to 1400.0)))
        for ((diff, current) in listOf(-14.0 to ComparisonTone.Down, 0.0 to ComparisonTone.Up,
            20.0 to ComparisonTone.Up, -0.01 to ComparisonTone.Down)) {
            val live = market(diff)
            assertEquals(current, f.currentTone(f.difference(live)!!))
            for ((threshold, tone) in listOf(-1.0 to ComparisonTone.Down, 0.0 to ComparisonTone.Up, 1.0 to ComparisonTone.Up)) {
                assertEquals(tone, f.thresholdTone(threshold))
                assertEquals(ComparisonTone.Neutral, seed("usd").thresholdTone(threshold))
            }
        }
        assertEquals("-0.0원", f.current(market(-0.01))[1])
        assertEquals(ComparisonTone.Up, seed("usd").currentTone(-14.0))
        assertEquals(listOf("+0.0원","+0.00%"), f.current(market(0.0)).drop(1))
        assertEquals(listOf("+20.0원","+1.00%"), f.current(market(20.0)).drop(1))
        for (leg in f.selection) assertTrue(f.current(quotes(leg to 1400.0)).isEmpty())
        assertTrue(f.current(quotes(f.selection[0] to 1386.0,f.selection[1] to 0.0)).isEmpty())
        assertNotNull(f.draft())
        for (tab in listOf("usd","tether")) {
            val abs = ComparisonFields.restore(comparisonFixture(tab=tab))
            assertEquals(if (tab == "usd") "2.5원" else "3원", abs.current(quotes(abs.selection[0] to 2.5,abs.selection[1] to 5.0)).last())
        }
    }

    @Test fun D05_fieldAndSummaryStrings() {
        val abs = ComparisonFields.restore(comparisonFixture(tab="usd"))
        for (tab in listOf("tether", "usd")) {
            var picking = seed(tab)
            val tetherTab = tab == "tether"
            val prompt = if (tetherTab) "비교할 거래소 2곳을 선택해주세요" else "비교할 은행 2곳을 선택해주세요"
            val (a, b, c) = picking.candidates().take(3)
            assertEquals(prompt, picking.headline())
            picking = picking.select(a, false, emptyMap())
            assertEquals(prompt, picking.headline())
            picking = picking.select(b, false, emptyMap())
            val first = if (tetherTab) "업비트·빗썸 차이가 1원" else "인베스팅·국민 차이가 0.1원"
            assertEquals("\"$first 이상 벌어지면 알려드려요\"", picking.headline())
            picking = picking.select(c, false, emptyMap())
            val next = if (tetherTab) "빗썸·코인원 차이가 1원" else "국민·하나 차이가 0.1원"
            assertEquals("\"$next 이상 벌어지면 알려드려요\"", picking.headline())
            assertEquals(prompt, picking.select(c, false, emptyMap()).headline())
        }
        val signedRow = comparisonFixture(signed = true)
        assertEquals("\"빗썸 김프가 인베스팅 대비 +0원 이상으로 오르면 알려드려요\"", seed("tether", "signed").headline())
        assertEquals("\"빗썸 김프가 인베스팅 대비 +2원 이상으로 오르면 알려드려요\"", ComparisonFields.restore(signedRow).headline())
        for (row in listOf(signedRow, comparisonFixture())) {
            assertEquals("알림 조건을 선택해주세요", ComparisonFields.restore(row.copy(operator = "?")).headline())
        }
        assertEquals("비교 알림 수정",abs.title)
        assertEquals("비교 알림",seed("usd").title)
        assertEquals("차이 기준",abs.heading)
        assertEquals("비교할 은행 2곳을 선택해주세요",abs.guidance)
        assertEquals("비교할 거래소 2곳을 선택해주세요",seed("tether").guidance)
        assertEquals("2.0",abs.fieldText)
        assertEquals("\"국민·하나 차이가 2원 이상 벌어지면 알려드려요\"",abs.summary)
        assertEquals("\"국민·하나 차이가 2원 이내로 좁혀지면 알려드려요\"",abs.copy(operator="lte").summary)
        assertEquals("0.3",seed("tether").copy(threshold=0.1+0.2).fieldText)
        val f = seed("tether","signed")
        assertEquals("김프 알림",f.title)
        assertEquals("김프 알림 수정",ComparisonFields.restore(comparisonFixture(signed=true)).title)
        assertEquals("김프 기준",f.heading)
        assertEquals("\"빗썸 김프가 인베스팅 대비 +0원 이상으로 오르면 알려드려요\"",f.summary)
        assertEquals("\"빗썸 김프가 인베스팅 대비 -14원 이하로 떨어지면 알려드려요\"",f.copy(threshold=-14.0, operator="lte").summary)
    }
}
