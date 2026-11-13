package com.jay.fxi.ui.alert

import com.jay.fxi.data.alerts.BankAlertDraft
import com.jay.fxi.data.alerts.BankAlertEdit
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.data.remote.dto.RepeatIntervalChange.Every
import com.jay.fxi.data.remote.dto.RepeatIntervalChange.Keep
import com.jay.fxi.data.remote.dto.RepeatIntervalChange.Once
import com.jay.fxi.domain.model.AlertCondition.ABOVE
import com.jay.fxi.domain.model.AlertCondition.BELOW
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.domain.model.SupportedCurrency.EUR_KRW
import com.jay.fxi.domain.model.SupportedCurrency.JPY_KRW
import com.jay.fxi.domain.model.SupportedCurrency.USD_KRW
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 E5b3a contract (s7e5b3_api_proposal.claude.r2.md D2 as agreed): the editor's pure rules — choices, start values,
 * input trimming, the allowed range, duplicates, what counts as a change, and what a save sends. The sheet and the
 * holder are device evidence; this is the decision table they both rely on.
 */
class S7E5B3AEditorContractTest {

    private fun row(
        id: Int = 7,
        bank: String = "kb",
        currency: String = "usd-krw",
        condition: com.jay.fxi.domain.model.AlertCondition = BELOW,
        threshold: Double = 1400.0,
        enabled: Boolean = true,
        repeat: Int? = null
    ) = AlertSetting(
        id = id, userId = "u", bank = bank, currency = currency, condition = condition, threshold = threshold,
        isEnabled = enabled, triggered = false, createdAt = Instant.fromEpochSeconds(0),
        updatedAt = Instant.fromEpochSeconds(0), triggeredAt = null, repeatIntervalSec = repeat
    )

    private fun edit(setting: AlertSetting = row()) = BankAlertEditorMode.Edit(setting)
    private val add = BankAlertEditorMode.Add
    private fun input(bank: Bank? = Bank.KB, text: String = "1400.00", repeat: RepeatIntervalChange = Once, condition: com.jay.fxi.domain.model.AlertCondition = BELOW) =
        BankAlertEditorInput(bank, condition, text, repeat)

    // --- choices ---

    @Test fun `B01 bank choices are iOS displayCases without Citi, in order`() {
        assertEquals(
            listOf(Bank.INVESTING, Bank.KB, Bank.HANA, Bank.SHINHAN, Bank.WOORI, Bank.IBK, Bank.NH, Bank.SC, Bank.BS),
            BankAlertEditor.BANK_CHOICES
        )
        assertFalse(Bank.CITI in BankAlertEditor.BANK_CHOICES)
    }

    @Test fun `B02 repeat choices are once then the ten intervals`() {
        assertEquals(listOf<RepeatInterval?>(null) + RepeatInterval.entries, BankAlertEditor.REPEAT_CHOICES)
        assertEquals(listOf(60, 300, 600, 1800, 3600, 7200, 14400, 21600, 43200, 86400), RepeatInterval.entries.map { it.seconds })
    }

    // --- start values ---

    @Test fun `I01 adding starts on KB, below, KB's price and once`() {
        assertEquals(input(Bank.KB, "1400.55", Once), BankAlertEditor.initial(add, mapOf("kb" to 1400.55, "hana" to 1500.0)))
    }

    @Test fun `I02 adding without a usable KB price starts empty`() {
        for (quotes in listOf(emptyMap(), mapOf("hana" to 1500.0), mapOf("kb" to 0.0), mapOf("kb" to -1.0),
                mapOf("kb" to Double.NaN), mapOf("kb" to Double.POSITIVE_INFINITY))) {
            assertEquals("$quotes", "", BankAlertEditor.initial(add, quotes).thresholdText)
        }
    }

    @Test fun `I03 editing starts on the row and keeps its repeat`() {
        val s = row(bank = "hana", condition = ABOVE, threshold = 1412.3, enabled = false, repeat = 300)
        assertEquals(input(Bank.HANA, "1412.30", Keep, ABOVE), BankAlertEditor.initial(edit(s), mapOf("hana" to 1.0)))
    }

    @Test fun `I04 editing a Citi row keeps Citi, an unknown bank starts unpicked`() {
        assertEquals(Bank.CITI, BankAlertEditor.initial(edit(row(bank = "citi")), emptyMap()).bank)
        assertNull(BankAlertEditor.initial(edit(row(bank = "krx")), emptyMap()).bank)
        assertNull("bank codes match exactly", BankAlertEditor.initial(edit(row(bank = "KB")), emptyMap()).bank)
    }

    @Test fun `I05 picking a bank while adding starts at its price, again on a re-pick, and keeps the text without one`() {
        val q = mapOf("kb" to 1400.0, "hana" to 1500.256)
        val typed = input(Bank.KB, "1300.00")
        assertEquals(input(Bank.HANA, "1500.26"), BankAlertEditor.onBankPicked(typed, Bank.HANA, add, q))
        assertEquals(input(Bank.KB, "1400.00"), BankAlertEditor.onBankPicked(typed, Bank.KB, add, q))
        assertEquals(input(Bank.SC, "1300.00"), BankAlertEditor.onBankPicked(typed, Bank.SC, add, q))
    }

    @Test fun `I06 picking a bank while editing keeps the threshold`() {
        val typed = input(Bank.KB, "1300.00", Keep)
        assertEquals(input(Bank.HANA, "1300.00", Keep), BankAlertEditor.onBankPicked(typed, Bank.HANA, edit(), mapOf("hana" to 1500.0)))
    }

    @Test fun `I07 thresholds format with two decimals and no grouping`() {
        assertEquals("1400.50", BankAlertEditor.formatThreshold(1400.5))
        assertEquals("12345.68", BankAlertEditor.formatThreshold(12345.678))
        assertEquals("0.10", BankAlertEditor.formatThreshold(0.1))
    }

    // --- input trimming ---

    @Test fun `T01 sanitize trims like iOS and never refuses`() {
        val cases = mapOf(
            "1,234.567" to "1234.56", "12.3.4" to "12.34", "12345" to "1234", "12345.6" to "1234.6",
            "abc" to "", "." to ".", ".5" to ".5", "0012" to "0012", "1 4 0 0" to "1400", "-1400" to "1400",
            "1e3" to "13", "1.2,34" to "1.23", "1.,234" to "1.23", "12..3.4" to "12.34", "12345..6,789" to "1234.67",
            "a1,2b3,4,5.6x7,8.9" to "1234.67", "5." to "5.", "٣٤" to "", "１２" to "", "" to "", "9999.999" to "9999.99"
        )
        for ((raw, want) in cases) {
            assertEquals(raw, want, BankAlertEditor.sanitize(raw))
            assertEquals("sanitize is idempotent on $raw", want, BankAlertEditor.sanitize(want))
        }
    }

    // --- range ---

    @Test fun `R01 the range is half to one and a half, to the nearest ten with halves up, both ends included`() {
        assertEquals(700.0..2100.0, BankAlertEditor.range(1400.0))
        assertEquals("697.5 → 700, 2092.5 → 2090", 700.0..2090.0, BankAlertEditor.range(1395.0))
        assertEquals("725 rounds up to 730 (ties-to-even would give 720)", 730.0..2180.0, BankAlertEditor.range(1450.0))
    }

    @Test fun `R02 no usable quote, no range`() {
        for (q in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull("$q", BankAlertEditor.range(q))
        }
        assertNull("an upper end that overflows", BankAlertEditor.range(Double.MAX_VALUE))
    }

    @Test fun `R03 out of range only with a range, and the ends are inside`() {
        val q = mapOf("kb" to 1400.0)
        fun out(text: String) = BankAlertEditor.check(add, input(text = text), USD_KRW, q, emptyList()).outOfRange
        assertFalse(out("700")); assertFalse(out("2100")); assertTrue(out("699.99")); assertTrue(out("2100.01"))
        assertFalse("no quote, no range check",
            BankAlertEditor.check(add, input(text = "5"), USD_KRW, emptyMap(), emptyList()).outOfRange)
        val c = BankAlertEditor.check(add, input(text = "5"), USD_KRW, emptyMap(), emptyList())
        assertNull(c.range); assertNull(c.quote); assertTrue("no quote does not block saving", c.canSave)
    }

    // --- threshold parsing ---

    @Test fun `P01 only plain positive numbers are thresholds`() {
        for (text in listOf("", ".", "0", "0.00", "1e3", "12,3", "-5", " 5", "NaN", "Infinity", "1.2.3", "1".repeat(400),
                "+5", "5 ", "5\t", "5\n", "1 2", "1\u00a02", "1E3", "1e-3", "\u0661\u0662", "\uff11\uff12", "1.\u0662", "\u221e", "0x1.0p2")) {
            val c = BankAlertEditor.check(add, input(text = text), USD_KRW, emptyMap(), emptyList())
            assertNull(text.take(12), c.threshold); assertFalse(text.take(12), c.canSave)
        }
        assertEquals(0.5, BankAlertEditor.check(add, input(text = ".5"), USD_KRW, emptyMap(), emptyList()).threshold)
        for ((text, want) in listOf("5." to 5.0, "0005.00" to 5.0, "1.234567" to 1.234567)) {
            assertEquals("a plain decimal has no editor digit limit: $text", want,
                BankAlertEditor.check(add, input(text = text), USD_KRW, emptyMap(), emptyList()).threshold)
        }
        assertEquals("an edited row's five whole digits are not cut", 12345.0,
            BankAlertEditor.check(edit(row(threshold = 12345.0)), input(text = "12345.00", repeat = Keep), USD_KRW, emptyMap(), emptyList()).threshold)
    }

    // --- duplicates ---

    @Test fun `D01 a duplicate is the same currency, bank and condition within half a cent`() {
        val others = listOf(row(id = 1, threshold = 1400.004))
        fun dup(text: String = "1400.00", bank: Bank? = Bank.KB, condition: com.jay.fxi.domain.model.AlertCondition = BELOW, list: List<AlertSetting> = others) =
            BankAlertEditor.check(add, input(bank, text, condition = condition), USD_KRW, emptyMap(), list).duplicate
        assertTrue(dup())
        assertFalse(dup("1400.01"))
        assertFalse(dup(condition = ABOVE))
        assertFalse(dup(bank = Bank.HANA))
        assertFalse(dup(list = listOf(row(id = 1, currency = "jpy-krw", threshold = 1400.0))))
        assertFalse("bank raw compares exactly", dup(list = listOf(row(id = 1, bank = "KB", threshold = 1400.0))))
        assertTrue("enabled and repeat are not part of it", dup(list = listOf(row(id = 1, enabled = false, repeat = 60))))
        assertFalse("no threshold, no duplicate", dup(text = ""))
    }

    @Test fun `D02 editing skips its own row and compares an unknown bank by its raw code`() {
        val self = row(id = 7, bank = "krx", threshold = 1400.0)
        val list = listOf(self)
        assertFalse(BankAlertEditor.check(edit(self), input(null, "1400.00", Keep), USD_KRW, emptyMap(), list).duplicate)
        val twin = row(id = 8, bank = "krx", threshold = 1400.0)
        assertTrue(BankAlertEditor.check(edit(self), input(null, "1400.00", Keep), USD_KRW, emptyMap(), list + twin).duplicate)
        assertFalse("never compared as KB",
            BankAlertEditor.check(edit(self), input(null, "1400.00", Keep), USD_KRW, emptyMap(), list + row(id = 9, bank = "kb")).duplicate)
    }

    // --- selection ---

    @Test fun `S01 adding needs an offered bank and a real repeat choice`() {
        fun ok(bank: Bank?, repeat: RepeatIntervalChange) =
            BankAlertEditor.check(add, input(bank, "1400", repeat), USD_KRW, emptyMap(), emptyList()).validSelection
        for (b in BankAlertEditor.BANK_CHOICES) assertTrue("$b", ok(b, Once))
        assertTrue(ok(Bank.KB, Every(RepeatInterval.ONE_DAY)))
        assertFalse(ok(Bank.CITI, Once)); assertFalse(ok(null, Once)); assertFalse(ok(Bank.KB, Keep))
    }

    @Test fun `S02 editing keeps an unknown bank only for an unknown row and Citi only for a Citi row`() {
        fun ok(original: String, bank: Bank?, currency: com.jay.fxi.domain.model.SupportedCurrency = USD_KRW) =
            BankAlertEditor.check(edit(row(bank = original)), input(bank, "1400", Keep), currency, emptyMap(), emptyList()).validSelection
        assertTrue(ok("krx", null)); assertFalse(ok("kb", null)); assertFalse(ok("citi", null))
        assertTrue(ok("citi", Bank.CITI)); assertFalse(ok("kb", Bank.CITI)); assertFalse(ok("krx", Bank.CITI))
        assertTrue(ok("krx", Bank.HANA)); assertTrue(ok("citi", Bank.KB))
        assertFalse("the row's own currency only", ok("kb", Bank.KB, EUR_KRW))
        // gap hunt G01: every branch checks the currency, and Citi is the exact raw code
        assertFalse("an unknown row in another currency", ok("krx", null, JPY_KRW))
        assertFalse("a Citi row in another currency", ok("citi", Bank.CITI, EUR_KRW))
        assertFalse("CITI is not citi", ok("CITI", Bank.CITI))
        assertTrue("CITI is an unknown raw code, kept as it is", ok("CITI", null))
    }

    // --- changes ---

    @Test fun `C01 an untouched enabled row is not a change and cannot be saved`() {
        val s = row(enabled = true, repeat = 60)
        val c = BankAlertEditor.check(edit(s), input(Bank.KB, "1400.00", Keep), USD_KRW, emptyMap(), listOf(s))
        assertFalse(c.changed); assertFalse(c.canSave)
    }

    @Test fun `C02 a disabled row is a change by itself (editing turns it on)`() {
        val s = row(enabled = false)
        val c = BankAlertEditor.check(edit(s), input(Bank.KB, "1400.00", Keep), USD_KRW, emptyMap(), listOf(s))
        assertTrue(c.changed); assertTrue(c.canSave)
    }

    @Test fun `C03 repeat compares the stored seconds, not the picking`() {
        fun changed(original: Int?, pick: RepeatIntervalChange) =
            BankAlertEditor.check(edit(row(repeat = original)), input(Bank.KB, "1400.00", pick), USD_KRW, emptyMap(), emptyList()).changed
        assertFalse(changed(null, Once)); assertFalse(changed(60, Every(RepeatInterval.ONE_MINUTE)))
        assertTrue(changed(60, Every(RepeatInterval.FIVE_MINUTES))); assertTrue(changed(60, Once)); assertTrue(changed(null, Every(RepeatInterval.ONE_MINUTE)))
        assertFalse(changed(120, Keep)); assertTrue(changed(120, Once)); assertTrue(changed(120, Every(RepeatInterval.ONE_MINUTE)))
    }

    @Test fun `C04 bank, condition and threshold changes`() {
        fun changed(bank: Bank?, text: String = "1400.00", condition: com.jay.fxi.domain.model.AlertCondition = BELOW, original: String = "kb") =
            BankAlertEditor.check(edit(row(bank = original)), input(bank, text, Keep, condition), USD_KRW, emptyMap(), emptyList()).changed
        assertFalse(changed(Bank.KB)); assertTrue(changed(Bank.HANA)); assertTrue(changed(Bank.KB, condition = ABOVE))
        assertFalse(changed(Bank.KB, "1400.004")); assertTrue(changed(Bank.KB, "1400.01"))
        assertFalse("an unknown bank left alone", changed(null, original = "krx")); assertTrue(changed(Bank.KB, original = "krx"))
        assertFalse("Citi kept", changed(Bank.CITI, original = "citi"))
    }

    @Test fun `C05 can save needs every condition`() {
        val q = mapOf("kb" to 1400.0)
        fun can(text: String, list: List<AlertSetting> = emptyList(), bank: Bank? = Bank.KB) =
            BankAlertEditor.check(add, input(bank, text), USD_KRW, q, list).canSave
        assertTrue(can("1400"))
        assertFalse(can("")); assertFalse(can("2200")); assertFalse(can("1400", listOf(row(id = 1)))); assertFalse(can("1400", bank = Bank.CITI))
    }

    // --- submit ---

    @Test fun `M01 adding sends the whole draft, on, with the repeat`() {
        assertEquals(BankAlertSubmit.Add(BankAlertDraft("jpy-krw", "hana", ABOVE, 912.5, true, null)),
            BankAlertEditor.submit(add, input(Bank.HANA, "912.5", Once, ABOVE), JPY_KRW))
        assertEquals(BankAlertSubmit.Add(BankAlertDraft("usd-krw", "kb", BELOW, 1400.0, true, RepeatInterval.ONE_HOUR)),
            BankAlertEditor.submit(add, input(Bank.KB, "1400", Every(RepeatInterval.ONE_HOUR)), USD_KRW))
    }

    @Test fun `M02 editing sends only what changed`() {
        val s = row(id = 9, bank = "kb", condition = BELOW, threshold = 1400.0, enabled = true, repeat = 60)
        assertEquals(BankAlertSubmit.Edit(9, BankAlertEdit(threshold = 1410.0)),
            BankAlertEditor.submit(edit(s), input(Bank.KB, "1410", Every(RepeatInterval.ONE_MINUTE)), USD_KRW))
        assertEquals(BankAlertSubmit.Edit(9, BankAlertEdit(bank = "hana", condition = ABOVE, repeat = Once)),
            BankAlertEditor.submit(edit(s), input(Bank.HANA, "1400.004", Once, ABOVE), USD_KRW))
        assertEquals(BankAlertSubmit.Edit(9, BankAlertEdit(isEnabled = true)),
            BankAlertEditor.submit(edit(s.copy(isEnabled = false)), input(Bank.KB, "1400.00", Keep), USD_KRW))
        assertEquals(BankAlertSubmit.Edit(9, BankAlertEdit(repeat = Every(RepeatInterval.ONE_DAY))),
            BankAlertEditor.submit(edit(s), input(Bank.KB, "1400.00", Every(RepeatInterval.ONE_DAY)), USD_KRW))
    }

    @Test fun `M03 editing never sends a bank it did not pick`() {
        val unknown = row(id = 3, bank = "krx", enabled = false)
        assertEquals(BankAlertSubmit.Edit(3, BankAlertEdit(isEnabled = true)),
            BankAlertEditor.submit(edit(unknown), input(null, "1400.00", Keep), USD_KRW))
        assertEquals(BankAlertSubmit.Edit(3, BankAlertEdit(bank = "kb", isEnabled = true)),
            BankAlertEditor.submit(edit(unknown), input(Bank.KB, "1400.00", Keep), USD_KRW))
        val citi = row(id = 4, bank = "citi")
        assertEquals(BankAlertSubmit.Edit(4, BankAlertEdit(threshold = 1500.0)),
            BankAlertEditor.submit(edit(citi), input(Bank.CITI, "1500", Keep), USD_KRW))
    }

    @Test fun `M04 submit fails early on an input that cannot be saved`() {
        // gap hunt DISAGREE M04: only that it fails; the exception class is not part of the contract
        assertThrows(RuntimeException::class.java) { BankAlertEditor.submit(add, input(Bank.KB, "1400", Keep), USD_KRW) }
        assertThrows(RuntimeException::class.java) { BankAlertEditor.submit(add, input(null, "1400"), USD_KRW) }
        assertThrows(RuntimeException::class.java) { BankAlertEditor.submit(add, input(Bank.KB, ""), USD_KRW) }
    }

    // --- repeat words ---

    @Test fun `W01 repeat words follow the stored seconds and name an unknown interval 반복 알림`() {
        assertEquals(BankAlertRepeatTexts("한번만", "조건 충족 시 1회만 발송", "알림은 1회 발송 후 자동으로 꺼집니다", false),
            BankAlertEditor.repeatTexts(add, Once))
        assertEquals(BankAlertRepeatTexts("5분", "조건 충족 동안 5분마다 발송", "조건이 충족되는 동안 5분마다 반복 발송됩니다", true),
            BankAlertEditor.repeatTexts(add, Every(RepeatInterval.FIVE_MINUTES)))
        assertEquals("한번만", BankAlertEditor.repeatTexts(edit(row(repeat = null)), Keep).label)
        assertEquals("1시간", BankAlertEditor.repeatTexts(edit(row(repeat = 3600)), Keep).label)
        assertEquals(BankAlertRepeatTexts("반복 알림", "조건 충족 동안 반복 발송", "조건이 충족되는 동안 반복 발송됩니다", true),
            BankAlertEditor.repeatTexts(edit(row(repeat = 120)), Keep))
        assertEquals("한번만", BankAlertEditor.repeatTexts(edit(row(repeat = 120)), Once).label)
    }

    // --- gap hunt r1 (s7e5b3a_gaphunt.codex.r1.md) ---

    @Test fun `G02 an invalid add is judged safely and adding is always a change`() {
        val q = mapOf("kb" to 1400.0)
        val twin = listOf(row(id = 1, threshold = 1400.0))
        val nullBank = BankAlertEditor.check(add, input(null, "1400.00"), USD_KRW, q, twin)
        assertEquals(listOf(null, false, false, false, true),
            listOf(nullBank.quote, nullBank.duplicate, nullBank.validSelection, nullBank.canSave, nullBank.changed))
        val keep = BankAlertEditor.check(add, input(Bank.KB, "1500.00", Keep), USD_KRW, q, emptyList())
        assertEquals(listOf(false, false, true), listOf(keep.validSelection, keep.canSave, keep.changed))
        for (text in listOf("", "1400")) assertTrue(BankAlertEditor.check(add, input(text = text), USD_KRW, q, emptyList()).changed)
    }

    @Test fun `G03 every entry point uses only finite positive quotes, looked up by the effective raw bank`() {
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val q = mapOf("kb" to bad)
            assertEquals("$bad", "", BankAlertEditor.initial(add, q).thresholdText)
            assertEquals("$bad", "1300", BankAlertEditor.onBankPicked(input(Bank.HANA, "1300"), Bank.KB, add, q).thresholdText)
            val c = BankAlertEditor.check(add, input(Bank.KB, "1300"), USD_KRW, q, emptyList())
            assertEquals("$bad", listOf(null, null, false, true), listOf(c.quote, c.range, c.outOfRange, c.canSave))
        }
        val huge = BankAlertEditor.check(add, input(Bank.KB, "5"), USD_KRW, mapOf("kb" to Double.MAX_VALUE), emptyList())
        assertEquals(listOf(Double.MAX_VALUE, null, false), listOf(huge.quote, huge.range, huge.outOfRange))
        val q = mapOf("kb" to 1400.0, "hana" to 1500.0, "krx" to 1600.0)
        assertEquals(1500.0, BankAlertEditor.check(add, input(Bank.HANA, "1500"), USD_KRW, q, emptyList()).quote)
        val unknown = BankAlertEditor.check(edit(row(bank = "krx")), input(null, "1400", Keep), USD_KRW, q, emptyList())
        assertEquals("an unknown row's own price", listOf(1600.0, 800.0..2400.0), listOf(unknown.quote, unknown.range))
    }

    @Test fun `G05 half a cent is strict for a duplicate and inclusive for a change, in both directions`() {
        assertEquals(0.005, BankAlertEditor.SAME_THRESHOLD, 0.0)
        assertEquals("premise: the difference is exactly half a cent", 0.005, 0.01 - 0.005, 0.0)
        for ((original, text) in listOf(0.005 to "0.01", 0.01 to "0.005")) {
            val s = row(id = 7, threshold = original)
            val other = row(id = 8, threshold = original)
            assertFalse("$original vs $text", BankAlertEditor.check(add, input(text = text), USD_KRW, emptyMap(), listOf(other)).duplicate)
            val c = BankAlertEditor.check(edit(s), input(Bank.KB, text, Keep), USD_KRW, emptyMap(), listOf(s))
            assertTrue("$original vs $text", c.changed && c.canSave)
            assertEquals(text.toDouble(), (BankAlertEditor.submit(edit(s), input(Bank.KB, text, Keep), USD_KRW) as BankAlertSubmit.Edit).edit.threshold)
        }
        fun changed(text: String) = BankAlertEditor.check(edit(row(threshold = 1400.0)), input(Bank.KB, text, Keep), USD_KRW, emptyMap(), emptyList()).changed
        assertTrue("a larger fall", changed("1390")); assertFalse("a fall under half a cent", changed("1399.996"))
        fun dup(text: String) = BankAlertEditor.check(add, input(text = text), USD_KRW, emptyMap(), listOf(row(id = 1, threshold = 1400.0))).duplicate
        assertTrue("just below, too", dup("1399.996")); assertFalse(dup("1399.99"))
    }

    @Test fun `G06 any matching row is a duplicate, delivered or not, wherever it sits`() {
        for (triggered in listOf(true, false)) for (enabled in listOf(true, false)) for (repeat in listOf(null, 60, 120)) {
            val r = row(id = 1, enabled = enabled, repeat = repeat).copy(triggered = triggered)
            assertTrue("$triggered/$enabled/$repeat", BankAlertEditor.check(add, input(), USD_KRW, emptyMap(), listOf(r)).duplicate)
        }
        val hit = row(id = 3)
        val misses = listOf(row(id = 1, bank = "hana"), row(id = 2, condition = ABOVE), row(id = 4, threshold = 1500.0), row(id = 5, currency = "eur-krw"))
        assertTrue(BankAlertEditor.check(add, input(), USD_KRW, emptyMap(), misses + hit).duplicate)
        assertTrue(BankAlertEditor.check(add, input(), USD_KRW, emptyMap(), listOf(hit) + misses).duplicate)
        assertFalse(BankAlertEditor.check(add, input(), USD_KRW, emptyMap(), misses).duplicate)
        val self = row(id = 7, enabled = false)
        assertTrue("self, then a miss, then the twin", BankAlertEditor.check(edit(self), input(Bank.KB, "1400.00", Keep), USD_KRW, emptyMap(),
            listOf(self) + misses + row(id = 9)).duplicate)
    }

    @Test fun `G07 each edited axis is sent alone and the repeat follows the stored seconds`() {
        val s = row(id = 9, bank = "kb", condition = BELOW, threshold = 1400.0, enabled = true, repeat = null)
        fun sent(i: BankAlertEditorInput, base: AlertSetting = s) = (BankAlertEditor.submit(edit(base), i, USD_KRW) as BankAlertSubmit.Edit).edit
        assertEquals(BankAlertEdit(bank = "hana"), sent(input(Bank.HANA, "1400.00", Keep)))
        assertEquals(BankAlertEdit(condition = ABOVE), sent(input(Bank.KB, "1400.00", Keep, ABOVE)))
        assertEquals(BankAlertEdit(threshold = 1401.0), sent(input(Bank.KB, "1401", Keep)))
        assertEquals(BankAlertEdit(repeat = Every(RepeatInterval.ONE_HOUR)), sent(input(Bank.KB, "1400.00", Every(RepeatInterval.ONE_HOUR))))
        for (original in listOf<Int?>(null, 60, 120)) for (pick in listOf(Keep, Once, Every(RepeatInterval.ONE_MINUTE), Every(RepeatInterval.FIVE_MINUTES))) {
            val base = s.copy(repeatIntervalSec = original)
            val effective = when (pick) { Keep -> original; Once -> null; is Every -> pick.interval.seconds }
            val want = if (effective == original) Keep else pick
            assertEquals("$original/$pick", BankAlertEdit(condition = ABOVE, repeat = want), sent(input(Bank.KB, "1400.00", pick, ABOVE), base))
        }
        val large = row(id = 5, threshold = 12345.0, enabled = false)
        val start = BankAlertEditor.initial(edit(large), emptyMap())
        val c = BankAlertEditor.check(edit(large), start, USD_KRW, emptyMap(), listOf(large))
        assertEquals(listOf(12345.0, true, true), listOf(c.threshold, c.changed, c.canSave))
        assertEquals(BankAlertSubmit.Edit(5, BankAlertEdit(isEnabled = true)), BankAlertEditor.submit(edit(large), start, USD_KRW))
    }

    @Test fun `G08 picking away and back is no change`() {
        val s = row(bank = "kb", enabled = true, repeat = 60)
        val m = edit(s)
        val start = BankAlertEditor.initial(m, mapOf("kb" to 1400.0, "hana" to 1500.0))
        val away = BankAlertEditor.onBankPicked(start, Bank.HANA, m, mapOf("hana" to 1500.0))
        assertTrue(BankAlertEditor.check(m, away, USD_KRW, emptyMap(), listOf(s)).changed)
        val back = BankAlertEditor.onBankPicked(away, Bank.KB, m, mapOf("kb" to 1400.0))
        assertEquals(start, back)
        assertFalse(BankAlertEditor.check(m, back, USD_KRW, emptyMap(), listOf(s)).changed)
        val round = start.copy(condition = ABOVE).copy(condition = BELOW).copy(repeat = Every(RepeatInterval.FIVE_MINUTES)).copy(repeat = Every(RepeatInterval.ONE_MINUTE))
        assertFalse(BankAlertEditor.check(m, round, USD_KRW, emptyMap(), listOf(s)).changed)
    }

    @Test fun `G09 the range keeps double magnitudes and rounds each end half up`() {
        assertEquals(20_000_000_000.0..60_000_000_000.0, BankAlertEditor.range(40_000_000_000.0))
        assertEquals("715 → 720 and 2145 → 2150 (ties-to-even would give 720 and 2140)", 720.0..2150.0, BankAlertEditor.range(1430.0))
    }

    @Test fun `G13 every repeat choice and its kept seconds have the same words`() {
        for (interval in RepeatInterval.entries) {
            val every = BankAlertEditor.repeatTexts(add, Every(interval))
            assertEquals(BankAlertRepeatTexts(interval.label, "조건 충족 동안 ${interval.everyLabel} 발송", "조건이 충족되는 동안 ${interval.everyLabel} 반복 발송됩니다", true), every)
            assertEquals("$interval kept", every, BankAlertEditor.repeatTexts(edit(row(repeat = interval.seconds)), Keep))
        }
        assertEquals(BankAlertEditor.repeatTexts(add, Once), BankAlertEditor.repeatTexts(edit(row(repeat = null)), Keep))
    }

    @Test fun `G15 the threshold colour cue compares with the price, within half a cent is the same`() {
        fun tone(text: String, quotes: Map<String, Double> = mapOf("kb" to 1400.0)) =
            BankAlertEditor.tone(BankAlertEditor.check(add, input(text = text), USD_KRW, quotes, emptyList()))
        assertEquals(BankAlertThresholdTone.SAME, tone("1400.004")); assertEquals(BankAlertThresholdTone.ABOVE, tone("1400.01"))
        assertEquals(BankAlertThresholdTone.BELOW, tone("1399.99")); assertEquals(BankAlertThresholdTone.NONE, tone(""))
        assertEquals(BankAlertThresholdTone.NONE, tone("1400", emptyMap()))
    }
}
