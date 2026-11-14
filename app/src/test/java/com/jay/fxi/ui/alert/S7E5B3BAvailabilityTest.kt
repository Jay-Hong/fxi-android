package com.jay.fxi.ui.alert

import com.jay.fxi.data.alerts.AlertLoadFailure
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.BankAlertSettingsState
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S7 E5b3b contract (s7e5b3_api_proposal.claude.r2.md D6, D7): the editor's fixed copy and the add availability. The copy is the
 * agreed text, word for word. Availability is Hidden without an identity or a snapshot; otherwise it counts the distinct ids of every
 * currency (enabled or not, phase not read) against 30.
 */
class S7E5B3BAvailabilityTest {
    private val a = AuthIdentityFence("A", 1L)

    private fun row(id: Int, currency: String = "usd-krw", enabled: Boolean = true, bank: String = "kb", triggered: Boolean = false,
                    repeat: Int? = null) = AlertSetting(
        id = id, userId = "A", bank = bank, currency = currency, condition = AlertCondition.ABOVE, threshold = 1400.0 + id,
        isEnabled = enabled, triggered = triggered, createdAt = Instant.fromEpochSeconds(id.toLong()), updatedAt = Instant.fromEpochSeconds(0),
        triggeredAt = null, repeatIntervalSec = repeat
    )
    private fun state(rows: List<AlertSetting>?, identity: AuthIdentityFence? = a, phase: AlertLoadPhase = AlertLoadPhase.Ready) =
        BankAlertSettingsState(phase, rows, identity)
    private fun of(n: Int) = (1..n).map { row(it, currency = listOf("usd-krw", "jpy-krw", "eur-krw")[it % 3], enabled = it % 2 == 0) }

    @Test fun `C01 the copy is the agreed text`() {
        assertEquals("저장 결과를 확인하지 못했습니다. 알림 목록을 확인한 뒤 다시 시도해 주세요.", BankAlertEditorCopy.FAILED)
        assertEquals("알림은 최대 30개까지 설정할 수 있습니다.", BankAlertEditorCopy.CAP)
        assertEquals("알림을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.", BankAlertEditorCopy.NOT_SENT)
    }

    @Test fun `C02 no identity or no snapshot is Hidden, whatever the phase`() {
        assertEquals(30, BANK_ALERT_LIMIT)
        assertEquals("no identity", BankAlertAddAvailability.Hidden, bankAlertAddAvailability(state(of(3), identity = null)))
        assertEquals("no snapshot", BankAlertAddAvailability.Hidden, bankAlertAddAvailability(state(null)))
        assertEquals("loading with a snapshot still counts", BankAlertAddAvailability.Available(27),
            bankAlertAddAvailability(state(of(3), phase = AlertLoadPhase.Loading)))
        assertEquals("an empty snapshot", BankAlertAddAvailability.Available(30), bankAlertAddAvailability(state(emptyList())))
    }

    @Test fun `C03 distinct ids of every currency count against 30`() {
        for ((n, expected) in listOf(1 to BankAlertAddAvailability.Available(29), 27 to BankAlertAddAvailability.Available(3),
            29 to BankAlertAddAvailability.Available(1), 30 to BankAlertAddAvailability.AtCap, 31 to BankAlertAddAvailability.AtCap)) {
            assertEquals("$n", expected, bankAlertAddAvailability(state(of(n))))
        }
        val repeated = of(29) + row(29, currency = "jpy-krw")
        assertEquals("a repeated id counts once", BankAlertAddAvailability.Available(1), bankAlertAddAvailability(state(repeated)))
    }

    @Test fun `C05 the sheet's remaining count is r, zero at the cap and null when hidden or editing`() {
        val edit = BankAlertEditorMode.Edit(row(1))
        val table = listOf(BankAlertAddAvailability.Available(1) to 1, BankAlertAddAvailability.Available(2) to 2,
            BankAlertAddAvailability.Available(3) to 3, BankAlertAddAvailability.Available(4) to 4, BankAlertAddAvailability.Available(30) to 30,
            BankAlertAddAvailability.AtCap to 0, BankAlertAddAvailability.Hidden to null)
        for ((a, expected) in table) {
            assertEquals("add $a", expected, bankAlertSheetRemaining(BankAlertEditorMode.Add, a))
            assertEquals("edit $a", null, bankAlertSheetRemaining(edit, a))
        }
    }

    @Test fun `C04 no phase and no kind of row changes the count`() {
        // Every kind the list can hold: on and off, fired, an unknown bank, Citi, an unknown repeat; the thirtieth id has fired.
        val mixed = (1..30).map { i ->
            row(i, currency = listOf("usd-krw", "jpy-krw", "eur-krw")[i % 3], enabled = i % 2 == 0,
                bank = when (i % 4) { 0 -> "kb"; 1 -> "nobank"; 2 -> "citi"; else -> "hana" },
                triggered = i % 5 == 0 || i == 30, repeat = if (i % 7 == 0) 12345 else null)
        }
        val phases = listOf(AlertLoadPhase.Idle, AlertLoadPhase.Loading, AlertLoadPhase.Retrying, AlertLoadPhase.Ready,
            AlertLoadPhase.TerminalError(AlertLoadFailure.AuthRequired), AlertLoadPhase.TerminalError(AlertLoadFailure.Forbidden))
        for (phase in phases) {
            assertEquals("$phase at thirty", BankAlertAddAvailability.AtCap, bankAlertAddAvailability(state(mixed, phase = phase)))
            assertEquals("$phase one fewer", BankAlertAddAvailability.Available(1), bankAlertAddAvailability(state(mixed.dropLast(1), phase = phase)))
            assertEquals("$phase a repeated id", BankAlertAddAvailability.Available(1),
                bankAlertAddAvailability(state(mixed.dropLast(1) + mixed.first().copy(currency = "eur-krw"), phase = phase)))
        }
    }
}
