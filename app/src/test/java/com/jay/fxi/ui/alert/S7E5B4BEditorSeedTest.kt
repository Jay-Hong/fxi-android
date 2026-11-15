package com.jay.fxi.ui.alert

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.local.BankSelectionSnapshot
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.Bank
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S7 E5b4b (`s7e5b4_api_agreed.r1.md` §2.4): the bank an Add opens on. A remembered code that is one of the editor's choices
 * is taken as it is; no code, Citi, or a code this build does not know falls back to KB. The threshold follows the bank the
 * editor opened on. An Edit ignores the seed entirely.
 */
class S7E5B4BEditorSeedTest {

    private val quotes = BankAlertEditor.BANK_CHOICES.mapIndexed { i, bank -> bank.code to 1300.0 + i }.toMap()

    @Test
    fun everyChoiceIsTakenAsRemembered_withItsOwnQuote() {
        for (bank in BankAlertEditor.BANK_CHOICES) {
            val input = BankAlertEditor.initial(BankAlertEditorMode.Add, quotes, bank.code)
            assertEquals(bank.code, bank, input.bank)
            assertEquals(bank.code, BankAlertEditor.formatThreshold(quotes.getValue(bank.code)), input.thresholdText)
            assertEquals(AlertCondition.BELOW, input.condition)
            assertEquals(RepeatIntervalChange.Once, input.repeat)
        }
    }

    @Test
    fun noCodeCitiAndUnknownCodesOpenOnKb() {
        assertEquals("premise: Citi 는 선택지가 아니다", false, Bank.CITI in BankAlertEditor.BANK_CHOICES)
        // Spellings that a lenient match would read as another choice must still fall back: the store keeps the code verbatim.
        for (code in listOf(null, "citi", "future_bank", "", "  hana  ", "HANA", "Woori")) {
            val input = BankAlertEditor.initial(BankAlertEditorMode.Add, quotes, code)
            assertEquals("[$code]", Bank.KB, input.bank)
            assertEquals("[$code]", BankAlertEditor.formatThreshold(quotes.getValue("kb")), input.thresholdText)
        }
    }

    @Test
    fun theSeedsQuoteAloneFillsTheThreshold_andNoQuoteLeavesItEmpty() {
        val onlyKb = mapOf("kb" to 1350.0)
        assertEquals("", BankAlertEditor.initial(BankAlertEditorMode.Add, onlyKb, "hana").thresholdText)
        assertEquals(Bank.HANA, BankAlertEditor.initial(BankAlertEditorMode.Add, onlyKb, "hana").bank)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
            assertEquals("$bad", "", BankAlertEditor.initial(BankAlertEditorMode.Add, mapOf("sc" to bad), "sc").thresholdText)
        }
    }

    /** The seed is the Ready's code verbatim, whatever it is; Failed, Loading and None seed nothing (G06). */
    @Test
    fun theSeedIsTheReadyCodeVerbatim() {
        val who = AuthIdentityFence("u1", 1)
        val codes = BankAlertEditor.BANK_CHOICES.map { it.code } + listOf(null, "citi", "future_bank", "", "  hana  ", "HANA", "Woori")
        for (code in codes) assertEquals("[$code]", code, bankSelectionSeed(BankSelectionSnapshot.Ready(who, 7, code)))
        assertEquals(null, bankSelectionSeed(BankSelectionSnapshot.Failed(who, 7)))
        assertEquals(null, bankSelectionSeed(BankSelectionSnapshot.Loading(who, 7)))
        assertEquals(null, bankSelectionSeed(BankSelectionSnapshot.None))
    }

    /** The two-argument call is the old contract: KB, unchanged. */
    @Test
    fun withoutASeedTheAddIsWhatItWas() {
        assertEquals(BankAlertEditor.initial(BankAlertEditorMode.Add, quotes, null), BankAlertEditor.initial(BankAlertEditorMode.Add, quotes))
        assertEquals(Bank.KB, BankAlertEditor.initial(BankAlertEditorMode.Add, quotes).bank)
    }

    @Test
    fun anEditIgnoresTheSeed() {
        val setting = AlertSetting(
            id = 7, userId = "u1", bank = "nh", currency = "usd-krw", condition = AlertCondition.ABOVE, threshold = 1410.0,
            isEnabled = true, triggered = false, createdAt = Instant.fromEpochSeconds(0),
            updatedAt = Instant.fromEpochSeconds(0), triggeredAt = null, repeatIntervalSec = null
        )
        val mode = BankAlertEditorMode.Edit(setting)
        assertEquals(BankAlertEditor.initial(mode, quotes), BankAlertEditor.initial(mode, quotes, "hana"))
        assertEquals(Bank.NH, BankAlertEditor.initial(mode, quotes, "hana").bank)
    }
}
