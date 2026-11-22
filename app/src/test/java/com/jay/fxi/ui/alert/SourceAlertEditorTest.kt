package com.jay.fxi.ui.alert

import com.jay.fxi.data.alerts.NotSentReason
import com.jay.fxi.data.alerts.SourceAlertSaveOutcome
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.data.repository.mapAlertHttpFailure
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.domain.model.SourceAlertSetting
import kotlinx.datetime.Instant
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8-5b J1 (`R4c/S8/s8_5b_contract.agreed.r1.md` §2–§3): the tether editor's pure rules — the seed of an add from the selected
 * exchange's quote and of an edit from its row, the one won-normalised price, the closed ±50% range rounded to tens, the
 * kept-price edit, the duplicate at 0.005, the disabled row's re-enable, and the minimal draft and patch.
 */
class SourceAlertEditorTest {
    private val sources = listOf("upbit", "bithumb", "coinone", "korbit", "gopax")
    private val asset = "usdt-krw"
    private val stamp = Instant.parse("2026-11-21T01:00:00Z")

    private fun row(id: Int, source: String = "upbit", threshold: Double = 1400.0, condition: AlertCondition = AlertCondition.BELOW,
                    enabled: Boolean = true, repeat: Int? = null) =
        SourceAlertSetting(id, "user-a", source, asset, condition, threshold, enabled, false, repeat, stamp, stamp, null)

    private fun check(mode: SourceAlertEditorMode, input: SourceAlertEditorInput, quotes: Map<String, Double>,
                      existing: List<SourceAlertSetting> = emptyList()) =
        SourceAlertEditor.check(mode, input, sources, asset, quotes, existing)

    @Test fun `S8_5B_J1a an add starts from the remembered exchange and its quote, else upbit and an empty price`() {
        val quotes = mapOf("upbit" to 1501.0, "bithumb" to 1499.4)
        assertEquals(SourceAlertEditorInput("bithumb", AlertCondition.BELOW, "1499", RepeatIntervalChange.Once),
            SourceAlertEditor.initial(SourceAlertEditorMode.Add, sources, quotes, remembered = "bithumb"))
        assertEquals("unknown remembered code falls back", "upbit",
            SourceAlertEditor.initial(SourceAlertEditorMode.Add, sources, quotes, remembered = "kb").source)
        assertEquals("no quote: an empty price", "",
            SourceAlertEditor.initial(SourceAlertEditorMode.Add, sources, mapOf("upbit" to Double.NaN)).thresholdText)
        assertEquals("a non-positive quote is none", "",
            SourceAlertEditor.initial(SourceAlertEditorMode.Add, sources, mapOf("upbit" to 0.0)).thresholdText)
    }

    @Test fun `S8_5B_J1b only an add's explicit pick refills, and a pick without a quote keeps the typed text`() {
        val quotes = mapOf("upbit" to 1501.0, "korbit" to 1488.6)
        val typed = SourceAlertEditorInput("upbit", AlertCondition.BELOW, "1600", RepeatIntervalChange.Once)
        assertEquals("1489", SourceAlertEditor.onSourcePicked(typed, "korbit", SourceAlertEditorMode.Add, quotes).thresholdText)
        assertEquals("1600", SourceAlertEditor.onSourcePicked(typed, "gopax", SourceAlertEditorMode.Add, quotes).thresholdText)
        assertEquals("edit keeps the text", "1600",
            SourceAlertEditor.onSourcePicked(typed, "korbit", SourceAlertEditorMode.Edit(row(1)), quotes).thresholdText)
    }

    @Test fun `S8_5B_J1c the price is normalised to the won, half up, and must stay positive`() {
        assertEquals(1500.0, SourceAlertEditor.normalize("1499.5")!!, 0.0)
        assertEquals(1499.0, SourceAlertEditor.normalize("1499.49")!!, 0.0)
        assertNull("0.4 rounds to zero", SourceAlertEditor.normalize("0.4"))
        for (bad in listOf("", ".", "1.2.3", "-5", "abc", "0")) assertNull(bad, SourceAlertEditor.normalize(bad))
        for (bad in listOf("NaN", "Infinity", "1,234.567원", "1".repeat(400), "1e3", ".", " 1500")) assertNull(bad, SourceAlertEditor.normalize(bad))
        assertEquals("exact decimal, not the nearest double", 1499.0, SourceAlertEditor.normalize("1499.49999999999999")!!, 0.0)
        assertEquals("a large finite price is shown whole", "100000000000000000000", SourceAlertEditor.formatThreshold(1e20))
    }

    @Test fun `S8_5B_J1d the range is the closed tens-rounded half to one and a half of the quote`() {
        assertEquals(750.0..2250.0, SourceAlertEditor.range(1501.0))
        assertEquals("1510 · 0.5 = 755 rounds up to 760", 760.0, SourceAlertEditor.range(1510.0)!!.start, 0.0)
        assertNull(SourceAlertEditor.range(null))
        assertNull(SourceAlertEditor.range(-1.0))
        assertNull("non-finite upper bound", SourceAlertEditor.range(Double.MAX_VALUE))
        val add = SourceAlertEditorMode.Add
        val quotes = mapOf("upbit" to 1501.0)
        for ((text, ok) in listOf("750" to true, "2250" to true, "749" to false, "2251" to false)) {
            assertEquals(text, ok, check(add, SourceAlertEditorInput("upbit", AlertCondition.BELOW, text, RepeatIntervalChange.Once), quotes).canSave)
        }
        assertFalse("an add without a quote cannot be saved",
            check(add, SourceAlertEditorInput("gopax", AlertCondition.BELOW, "1500", RepeatIntervalChange.Once), quotes).canSave)
    }

    @Test fun `S8_5B_J1e an edit that keeps the price may save without a quote or out of range, a price change may not`() {
        val original = row(1, threshold = 1400.0, repeat = null)
        val edit = SourceAlertEditorMode.Edit(original)
        val repeatOnly = SourceAlertEditorInput("upbit", AlertCondition.BELOW, "1400", RepeatIntervalChange.Every(RepeatInterval.FIVE_MINUTES))
        assertTrue("no quote, repeat only", check(edit, repeatOnly, emptyMap()).canSave)
        assertTrue("out of range, repeat only", check(edit, repeatOnly, mapOf("upbit" to 5000.0)).canSave)
        assertFalse("a price change without a quote", check(edit, repeatOnly.copy(thresholdText = "1450"), emptyMap()).canSave)
        val noOp = SourceAlertEditorInput("upbit", AlertCondition.BELOW, "1400", RepeatIntervalChange.Keep)
        assertFalse("an enabled row unchanged cannot be saved", check(edit, noOp, mapOf("upbit" to 1400.0)).canSave)
        val disabled = SourceAlertEditorMode.Edit(original.copy(isEnabled = false))
        assertTrue("a disabled row unchanged is saved to turn it on", check(disabled, noOp, emptyMap()).canSave)
        val patch = (SourceAlertEditor.submit(disabled, noOp, sources) as SourceAlertSubmit.Edit).edit
        assertEquals("only the re-enable", com.jay.fxi.data.alerts.SourceAlertEdit(isEnabled = true), patch)
    }

    @Test fun `S8_5B_J1f a duplicate within 0_005 of another row, never the edited one, blocks the save`() {
        val quotes = mapOf("upbit" to 1400.0)
        val existing = listOf(row(1, threshold = 1400.0), row(2, threshold = 1450.0, enabled = false))
        val add = SourceAlertEditorMode.Add
        assertTrue(check(add, SourceAlertEditorInput("upbit", AlertCondition.BELOW, "1400", RepeatIntervalChange.Once), quotes, existing).duplicate)
        assertTrue("a disabled row counts", check(add, SourceAlertEditorInput("upbit", AlertCondition.BELOW, "1450", RepeatIntervalChange.Once), quotes, existing).duplicate)
        assertFalse("another condition", check(add, SourceAlertEditorInput("upbit", AlertCondition.ABOVE, "1400", RepeatIntervalChange.Once), quotes, existing).duplicate)
        assertFalse("another exchange", check(add, SourceAlertEditorInput("bithumb", AlertCondition.BELOW, "1400", RepeatIntervalChange.Once), mapOf("bithumb" to 1400.0), existing).duplicate)
        assertFalse("the edited row itself", check(SourceAlertEditorMode.Edit(existing[0]),
            SourceAlertEditorInput("upbit", AlertCondition.BELOW, "1400", RepeatIntervalChange.Every(RepeatInterval.ONE_HOUR)), quotes, existing).duplicate)
        val kept = SourceAlertEditorInput("upbit", AlertCondition.BELOW, "1400", RepeatIntervalChange.Every(RepeatInterval.ONE_HOUR))
        val otherAsset = existing[0].copy(id = 3, asset = "usd-krw")
        assertFalse("another asset is no duplicate", check(add, kept, quotes, listOf(otherAsset)).duplicate)
        val otherId = existing[0].copy(id = 3, isEnabled = false)
        val unchanged = check(SourceAlertEditorMode.Edit(existing[0]), kept, emptyMap(), listOf(existing[0], otherId))
        assertTrue("the kept price", unchanged.priceUnchanged)
        assertTrue("still a duplicate of another row", unchanged.duplicate)
        assertFalse("so it cannot be saved", unchanged.canSave)
        val at1000 = SourceAlertEditorInput("upbit", AlertCondition.BELOW, "1000", RepeatIntervalChange.Once)
        val quotes1000 = mapOf("upbit" to 1000.0)
        for ((threshold, duplicate) in listOf(1399.995 to false, 1000.005 to false, 999.995 to false, 1000.004 to true, 999.996 to true)) {
            val sample = if (threshold > 1300) SourceAlertEditorInput("upbit", AlertCondition.BELOW, "1400", RepeatIntervalChange.Once) else at1000
            assertEquals("$threshold: exactly 0.005 apart is no duplicate, inside it is", duplicate,
                check(add, sample, if (threshold > 1300) quotes else quotes1000, listOf(row(3, threshold = threshold))).duplicate)
        }
    }

    @Test fun `S8_5B_J1g the draft and the patch carry only what changed`() {
        val add = SourceAlertEditor.submit(SourceAlertEditorMode.Add,
            SourceAlertEditorInput("korbit", AlertCondition.ABOVE, "1480.6", RepeatIntervalChange.Once), sources) as SourceAlertSubmit.Add
        assertEquals(com.jay.fxi.data.alerts.SourceAlertDraft("korbit", AlertCondition.ABOVE, 1481.0, true, null), add.draft)
        val original = row(7, source = "upbit", threshold = 1400.0, repeat = 300)
        val edited = SourceAlertEditor.submit(SourceAlertEditorMode.Edit(original),
            SourceAlertEditorInput("bithumb", AlertCondition.BELOW, "1400", RepeatIntervalChange.Once), sources) as SourceAlertSubmit.Edit
        assertEquals(7, edited.id)
        assertEquals(com.jay.fxi.data.alerts.SourceAlertEdit(source = "bithumb", repeat = RepeatIntervalChange.Once), edited.edit)
        for ((past, sent) in listOf(1000.004 to null, 1000.005 to 1000.0)) {
            val pastRow = row(8, threshold = past)
            val patch = (SourceAlertEditor.submit(SourceAlertEditorMode.Edit(pastRow),
                SourceAlertEditorInput("upbit", AlertCondition.ABOVE, "1000", RepeatIntervalChange.Keep), sources) as SourceAlertSubmit.Edit).edit
            assertEquals("$past: a past fraction within 0.005 is the same price, at 0.005 it is a change", sent, patch.threshold)
        }
        val reselected = SourceAlertEditor.submit(SourceAlertEditorMode.Edit(original),
            SourceAlertEditorInput("upbit", AlertCondition.ABOVE, "1400", RepeatIntervalChange.Every(RepeatInterval.FIVE_MINUTES)), sources) as SourceAlertSubmit.Edit
        assertEquals("re-picking the same interval is Keep", com.jay.fxi.data.alerts.SourceAlertEdit(condition = AlertCondition.ABOVE), reselected.edit)
    }

    @Test fun `S8_5B_J1h each result maps to its fixed copy and whether the sheet closes`() {
        fun http(status: Int) = mapAlertHttpFailure(AuthenticatedHttpFailure(status, headersOf(), ByteArray(0), null, null, AuthenticatedFailureKind.OTHER_HTTP))
        val stampRow = row(1)
        assertEquals(SourceAlertResultView(null, true), SourceAlertEditor.result(SourceAlertSaveOutcome.Saved(stampRow)))
        assertEquals(SourceAlertResultView("이미 삭제된 알림입니다.", true), SourceAlertEditor.result(SourceAlertSaveOutcome.Removed))
        assertEquals(SourceAlertResultView("지원되는 거래소를 선택해 주세요.", false), SourceAlertEditor.result(SourceAlertSaveOutcome.Unsupported))
        assertEquals(SourceAlertResultView("지원되는 거래소와 입력값을 확인해 주세요.", false), SourceAlertEditor.result(SourceAlertSaveOutcome.Failed(http(400))))
        assertEquals(SourceAlertResultView("저장 결과를 확인하지 못했습니다. 알림 목록을 확인한 뒤 다시 시도해 주세요.", false), SourceAlertEditor.result(SourceAlertSaveOutcome.Failed(http(404))))
        assertEquals(SourceAlertResultView("알림은 최대 30개까지 설정할 수 있습니다.", false), SourceAlertEditor.result(SourceAlertSaveOutcome.NotSent(NotSentReason.CAP)))
        assertEquals(SourceAlertResultView("알림을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.", false), SourceAlertEditor.result(SourceAlertSaveOutcome.NotSent(NotSentReason.BACKGROUND)))
        assertEquals(SourceAlertResultView(null, false), SourceAlertEditor.result(SourceAlertSaveOutcome.NotSent(NotSentReason.SUPERSEDED)))
        assertEquals(SourceAlertResultView(null, true), SourceAlertEditor.result(SourceAlertSaveOutcome.NotSent(NotSentReason.RESET)))
        assertNull(SourceAlertEditor.result(SourceAlertSaveOutcome.Pending))
    }
}
