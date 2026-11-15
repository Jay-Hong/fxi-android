package com.jay.fxi.data.alerts

import com.jay.fxi.data.repository.AlertRateLimitResponseEvidence
import com.jay.fxi.data.repository.AuthBoundResult
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 E5b4b (`s7e5b4_api_agreed.r1.md` §2.5): the owner hands a validated Add success to the last-bank memory, once,
 * synchronously and before Saved — the server row's bank, the request's identity. Nothing else hands anything over, and a
 * hand-over that throws is reported without costing the save.
 */
internal class S7E5B4BOwnerAcceptTest : S7E5B1SaveFixture() {

    @Test fun `S7E5B4B_O01 a validated Add success hands over the row's bank once, before Saved`() = runTest {
        rig(this) {
            loaded()
            val request = create()
            watched = request
            answer("POST", okRow(server(3, bank = "woori")))
            assertEquals("서버 행의 은행, 요청 identity, Saved 전", listOf(Triple(alice, "woori", BankAlertSaveOutcome.Pending as BankAlertSaveOutcome?)), accepted)
            assertTrue(request.outcome.value is BankAlertSaveOutcome.Saved)
            assertEquals(emptyList<Throwable>(), acceptReports)
        }
    }

    @Test fun `S7E5B4B_O02 an Edit, a toggle, a delete and every failed or refused Add hand nothing over`() = runTest {
        rig(this) {
            loaded()
            edit(1, BankAlertEdit(threshold = 1350.0))
            answer("EDIT", okRow(server(1, bank = "sc")))
            toggle(2, false)
            answer("PUT", okRow(server(2, enabled = false)))
            delete(2)
            answer("DELETE", Outcome.Answer(AuthBoundResult({}, Result.success(Unit), AlertRateLimitResponseEvidence(200, null))))
            create()
            answer("POST", http(500))
            create()
            answer("POST", okRow(server(4).copy(userId = "user-b")))
            assertEquals("전달이 있었다: $accepted", emptyList<Any>(), accepted)
        }
        rig(this) {
            loaded()
            background()
            create()
            assertEquals("보내지 않은 Add", 0, count("POST"))
            assertEquals(emptyList<Any>(), accepted)
        }
    }

    @Test fun `S7E5B4B_O03 an Add whose identity changed before the answer hands nothing over`() = runTest {
        rig(this) {
            loaded()
            create()
            switchToAlice2()
            foreground(true)
            open("POST").singleOrNull()?.outcome?.complete(okRow(server(3, bank = "woori")))
            settle()
            assertEquals(emptyList<Any>(), accepted)
        }
    }

    /**
     * Any general exception, checked or not, is reported once and costs nothing: Saved stands, the owner keeps the saved row,
     * and nothing is sent again.
     */
    @Test fun `S7E5B4B_O04 a hand-over that throws is reported and the save still stands, sent once`() = runTest {
        for (failure in listOf<Exception>(IllegalStateException("closed"), IOException("accept"), Exception("accept"))) {
            rig(this) {
                loaded()
                acceptFailure = failure
                val request = create()
                answer("POST", okRow(server(3, bank = "woori")))
                assertTrue("$failure: Saved 가 아니다: ${request.outcome.value}", request.outcome.value is BankAlertSaveOutcome.Saved)
                assertEquals("$failure: 같은 실패를 한 번 report 해야 한다", listOf<Throwable>(failure), acceptReports)
                assertEquals("$failure: 재송신했다", 1, count("POST"))
                assertEquals("$failure: owner 가 성공 행을 잃었다", "woori", rows?.singleOrNull { it.id == 3 }?.bank)
            }
        }
    }
}
