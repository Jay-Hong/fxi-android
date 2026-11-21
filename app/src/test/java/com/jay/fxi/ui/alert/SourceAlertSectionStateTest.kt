package com.jay.fxi.ui.alert

import com.jay.fxi.data.alerts.AlertLoadFailure
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.alerts.SourceAlertRowPolicy
import com.jay.fxi.data.alerts.SourceAlertSettingsState
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.SourceAlertSetting
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S8-4a (`R4c/S8/s8_4a_contract.agreed.r1.md` §2, §3): the tether section's pure view of the source owner — a success is shown
 * only for the screen's identity (UID and generation), a first terminal failure shows its error with no snapshot identity,
 * rows are the exact tether projection newest first, the badge counts enabled shown rows, and the refresh button follows the
 * manual state.
 */
class SourceAlertSectionStateTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val alice2 = AuthIdentityFence("user-a", 2)
    private val bob = AuthIdentityFence("user-b", 1)
    private val base = Instant.parse("2026-11-21T00:00:00Z")
    private val idle = ManualRefreshState()

    private fun row(id: Int, source: String = "upbit", asset: String = "usdt-krw", uid: String = "user-a", minute: Long = id.toLong(),
                    enabled: Boolean = true) =
        SourceAlertSetting(id, uid, source, asset, AlertCondition.ABOVE, 1400.0 + id, enabled, false, null,
            Instant.fromEpochMilliseconds(base.toEpochMilliseconds() + minute * 60_000), base, null)

    private fun state(phase: AlertLoadPhase, rows: List<SourceAlertSetting>?, who: AuthIdentityFence?) = SourceAlertSettingsState(phase, rows, who)
    private fun view(s: SourceAlertSettingsState, manual: ManualRefreshState = idle, shown: AuthIdentityFence = alice) =
        sourceAlertSectionState(s, manual, shown)

    @Test fun `S8_4A_V01 before any success a wait, a load and a retry are loading and a first terminal failure is its error`() {
        for (phase in listOf(AlertLoadPhase.Idle, AlertLoadPhase.Loading, AlertLoadPhase.Retrying)) {
            assertEquals("$phase", SourceAlertSectionState.Content.Loading, view(state(phase, null, null)).content)
        }
        val failures = mapOf(
            AlertLoadFailure.AuthRequired to "로그인이 필요합니다.",
            AlertLoadFailure.Forbidden to "접근 권한이 없습니다.",
            AlertLoadFailure.Failed(AlertRepositoryFailureKind.NETWORK, null) to "네트워크 연결을 확인해 주세요.",
            AlertLoadFailure.Failed(AlertRepositoryFailureKind.HTTP, 500) to "알림 설정을 불러오지 못했습니다."
        )
        for ((failure, message) in failures) {
            assertEquals("first failure $failure with no snapshot identity", SourceAlertSectionState.Content.Error(message),
                view(state(AlertLoadPhase.TerminalError(failure), null, null)).content)
        }
    }

    @Test fun `S8_4A_V02 the screen's own success is an empty list or its rows, kept through a refresh`() {
        assertEquals("a successful empty answer", SourceAlertSectionState.Content.Empty, view(state(AlertLoadPhase.Ready, emptyList(), alice)).content)
        val rows = listOf(row(1), row(2))
        for (phase in listOf(AlertLoadPhase.Ready, AlertLoadPhase.Loading, AlertLoadPhase.Retrying)) {
            val content = view(state(phase, rows, alice)).content
            assertEquals("$phase keeps the rows", listOf(2, 1), (content as SourceAlertSectionState.Content.Rows).rows.map { it.id })
        }
    }

    @Test fun `S8_4A_V03 another identity's success shows nothing, by UID and by generation`() {
        for (other in listOf(bob, alice2)) {
            for (rows in listOf(emptyList(), listOf(row(1), row(2, enabled = true)))) {
                val v = view(state(AlertLoadPhase.Ready, rows, other))
                assertEquals("$other ${rows.size}: no rows and no empty for another identity", SourceAlertSectionState.Content.Loading, v.content)
                assertEquals("$other ${rows.size}: and no badge", 0, v.activeCount)
            }
        }
    }

    @Test fun `S8_4A_V04 rows are the exact tether projection of the adopted answer, newest first, ties in adopted order`() {
        val answer = listOf(
            row(1, minute = 10), row(2, "kb"), row(3, "bithumb", minute = 30), row(4, uid = "user-b"),
            row(5, "krx", "usd-krw-futures"), row(6, "upbit", "usd-krw-futures"), row(7, "coinone", minute = 10),
            row(8, "korbit", minute = 50), row(8, "gopax", minute = 60), row(9, "upbit", minute = 70, enabled = false)
        ).map { if (it.id == 7) it.copy(isEnabled = false) else it }
        val adopted = SourceAlertRowPolicy.EXCHANGES.adopt(answer, "user-a")
        val v = view(state(AlertLoadPhase.Ready, adopted, alice))
        val content = v.content as SourceAlertSectionState.Content.Rows
        assertEquals("allowed tether rows only, newest first, the tie (1, 7) in adopted order", listOf(9, 3, 1, 7), content.rows.map { it.id })
        assertEquals("only the adopted enabled rows count: 3 and 1, not the refused ones nor the disabled 9 and 7", 2, v.activeCount)
        val projected = view(state(AlertLoadPhase.Ready, listOf(row(1), row(2, asset = "usd-krw-futures")), alice)).content
        assertEquals("another asset never shows", listOf(1), (projected as SourceAlertSectionState.Content.Rows).rows.map { it.id })
    }

    @Test fun `S8_4A_V05 every valid row shows at 29 30 and 31 and the badge counts enabled shown rows`() {
        for (n in listOf(29, 30, 31)) {
            val rows = (1..n).map { row(it, listOf("upbit", "bithumb", "coinone", "korbit", "gopax")[it % 5], enabled = it % 3 != 0) }
            val v = view(state(AlertLoadPhase.Ready, rows, alice))
            assertEquals("$n rows shown", n, (v.content as SourceAlertSectionState.Content.Rows).rows.size)
            assertEquals("$n: the badge counts enabled rows", rows.count { it.isEnabled }, v.activeCount)
        }
        assertEquals("all disabled: no badge", 0, view(state(AlertLoadPhase.Ready, listOf(row(1, enabled = false)), alice)).activeCount)
    }

    @Test fun `S8_4A_V06 the refresh button spins while sending and is off while sending or cooling down`() {
        val ready = state(AlertLoadPhase.Ready, emptyList(), alice)
        assertEquals(listOf(false, true), view(ready, ManualRefreshState()).let { listOf(it.refreshSpinning, it.refreshEnabled) })
        assertEquals(listOf(true, false), view(ready, ManualRefreshState(requested = true, sending = true)).let { listOf(it.refreshSpinning, it.refreshEnabled) })
        assertEquals(listOf(false, false), view(ready, ManualRefreshState(coolingDown = true, cooldownUntilMillis = 1)).let { listOf(it.refreshSpinning, it.refreshEnabled) })
    }
}
