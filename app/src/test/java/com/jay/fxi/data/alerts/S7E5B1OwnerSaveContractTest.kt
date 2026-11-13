package com.jay.fxi.data.alerts

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
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.remote.dto.AlertSettingRequest
import com.jay.fxi.data.remote.dto.AlertSettingUpdateRequest
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.data.repository.AlertRateLimitResponseEvidence
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.mapAlertHttpFailure
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.RepeatInterval
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 E5b1 contract (R4c/S7/s7e5/s7e5b1_api_agreed.r1.md): the bank owner's create and edit.
 * - addAlert(draft) and editAlert(id, edit) return a handle whose outcome settles exactly once: Saved (a checked server success; its
 *   local reflection may wait for an accepted GET), Removed (edit 404), NotSent(reason) or Failed (failed or unknown).
 * - A create is accepted only in the foreground, with a snapshot, a fresh admitted use of the snapshot's identity and a free cap
 *   slot: snapshot ids, rows hidden by an unsettled delete, creates in flight and unsettled creates together stay under 30.
 * - Each request is sent at most once, behind its endpoint floor, with a fresh attempt use; a failed or unknown request owes an
 *   accepted GET; a pre-wire refusal under a valid use does not. A create answer never covers a row change it overlapped.
 * - An edit joins its row's serial worker (delete, then the waiting edit, then a toggle newer than the last consumed enabled intent);
 *   a waiting edit is replaced whole; its enabled intent leaves with it; a server value that differs is adopted, never resent.
 * - Accepting a create or an edit bars any GET that started before it; GETs never start while a create or row op is active.
 * The rig is the S7E5A2 owner rig: every call waits for the test's answer; access, use authority, deletion store and floor are the
 * production ones on virtual time. The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class S7E5B1OwnerSaveContractTest : S7E5B1SaveFixture() {
    // ======== create: acceptance ========

    @Test fun `S7E5B1_C01 a create without the foreground or a snapshot is NOT_READY and sends nothing`() = runTest {
        rig(this) {
            assertEquals("never loaded", notSent(NotSentReason.NOT_READY), owner.addAlert(draft).outcome.value)
            loaded()
            background()
            assertEquals("background", notSent(NotSentReason.NOT_READY), create().outcome.value)
            assertEquals(0, count("POST"))
        }
    }

    @Test fun `S7E5B1_C02 a create without an admitted use of the snapshot identity is UNADMITTED and sends nothing`() = runTest {
        rig(this) {
            loaded()
            release = false
            assertEquals(notSent(NotSentReason.UNADMITTED), create().outcome.value)
            release = true
            fence = null
            assertEquals(notSent(NotSentReason.UNADMITTED), create().outcome.value)
            assertEquals(0, count("POST"))
        }
    }

    @Test fun `S7E5B1_C03 the cap counts every currency and every create in flight`() = runTest {
        rig(this) {
            loaded(*rowsOf(29) { if (it % 3 == 0) "jpy-krw" else "usd-krw" })
            val first = create()
            assertEquals("29 rows of any currency leave one slot", BankAlertSaveOutcome.Pending, first.outcome.value)
            assertEquals("the create in flight takes it", notSent(NotSentReason.CAP), create().outcome.value)
            assertEquals(1, count("POST"))
        }
        rig(this) {
            loaded(*rowsOf(31))
            assertEquals("above the cap the rows stay, the create is refused", notSent(NotSentReason.CAP), create().outcome.value)
            assertEquals("and the rows above it stay editable", BankAlertSaveOutcome.Pending, edit(31, BankAlertEdit(threshold = 1.0)).outcome.value)
        }
    }

    @Test fun `S7E5B1_C04 a row hidden by a delete keeps its cap slot until the delete settles`() = runTest {
        rig(this) {
            loaded(*rowsOf(30))
            delete(30)
            assertEquals("hidden by a delete in flight", 29, rows!!.size)
            assertEquals(notSent(NotSentReason.CAP), create().outcome.value)
            answer("DELETE", http(500))
            assertEquals("restored", 30, rows!!.size)
            assertEquals(notSent(NotSentReason.CAP), create().outcome.value)
        }
        rig(this) {
            loaded(*rowsOf(30))
            delete(30)
            answer("DELETE", Outcome.Throw(IOException("lost")))
            assertEquals("an unknown delete keeps the row hidden", 29, rows!!.size)
            assertEquals("and its slot until an accepted GET", notSent(NotSentReason.CAP), create().outcome.value)
            settle()
            answer("GET", okList(*rowsOf(29)))
            assertEquals("the GET settled it", BankAlertSaveOutcome.Pending, create().outcome.value)
        }
    }

    @Test fun `S7E5B1_C05 a create whose effect is unknown keeps its slot until an accepted GET`() = runTest {
        for (failure in listOf(Outcome.Throw(IOException("lost")), http(500))) rig(this) {
            loaded(*rowsOf(29))
            val first = create()
            answer("POST", failure)
            assertTrue("failed", first.outcome.value is BankAlertSaveOutcome.Failed)
            assertEquals("the slot is still taken", notSent(NotSentReason.CAP), create().outcome.value)
            settle()
            answer("GET", okList(*rowsOf(29)))
            assertEquals("an accepted GET settles it", BankAlertSaveOutcome.Pending, create().outcome.value)
        }
    }

    // ======== create: send and answer ========

    @Test fun `S7E5B1_C06 a create sends the draft once and never again`() = runTest {
        rig(this) {
            loaded()
            val h = create(BankAlertDraft("eur-krw", "woori", AlertCondition.ABOVE, 1612.5, false, RepeatInterval.FIVE_MINUTES))
            val body = json(sent("POST").single().payload)
            assertEquals(mapOf("bank" to "\"woori\"", "currency" to "\"eur-krw\"", "condition" to "\"above\"",
                "threshold" to "1612.5", "is_enabled" to "false", "repeat_interval_sec" to "300"), body.mapValues { it.value.toString() })
            assertEquals("the identity of the snapshot", alice, sent("POST").single().identity)
            answer("POST", Outcome.Throw(IOException("lost")))
            advance(120_000)
            assertEquals("no resend", 1, count("POST"))
            assertTrue(h.outcome.value is BankAlertSaveOutcome.Failed)
        }
        rig(this) {
            loaded()
            create()
            assertEquals("a once draft omits the repeat", null, json(sent("POST").single().payload)["repeat_interval_sec"])
        }
    }

    @Test fun `S7E5B1_C07 a new id goes first, the whole server row, and the handle holds a copy`() = runTest {
        rig(this) {
            loaded(setting(1), setting(2))
            val h = create()
            answer("POST", okRow(server(9, repeat = 600)))
            assertEquals(listOf(server(9, repeat = 600), setting(1), setting(2)), rows)
            val saved = h.outcome.value as BankAlertSaveOutcome.Saved
            assertEquals(server(9, repeat = 600), saved.row)
            assertNotSame("a copy", rows!!.first(), saved.row)
            saved.row.isEnabled = false
            assertEquals("changing the copy changes nothing", true, rows!!.first().isEnabled)
            nothingOwed("a reflected create")
        }
    }

    @Test fun `S7E5B1_C08 an id already listed is replaced in place by the server row and nothing is sent to match the draft`() = runTest {
        rig(this) {
            loaded(setting(1), setting(2), setting(3))
            val h = create(draft.copy(repeat = RepeatInterval.ONE_HOUR))
            answer("POST", okRow(server(2, enabled = false, repeat = null)))
            assertEquals(listOf(setting(1), server(2, enabled = false), setting(3)), rows)
            assertEquals(BankAlertSaveOutcome.Saved(server(2, enabled = false)), h.outcome.value)
            advance(60_000)
            assertEquals("no follow-up PUT", listOf(0, 0), listOf(count("PUT"), count("EDIT")))
        }
    }

    @Test fun `S7E5B1_C09 an answer for a row a delete hides is neither inserted nor revived and a GET is owed`() = runTest {
        rig(this) {
            loaded(setting(1), setting(2))
            val h = create()
            delete(2)
            answer("POST", okRow(server(2)))
            assertEquals("still hidden", listOf(setting(1)), rows)
            assertEquals("a checked success", BankAlertSaveOutcome.Saved(server(2)), h.outcome.value)
            answer("DELETE", okRow(server(2)))
            assertEquals("deleted", listOf(setting(1)), rows)
            settle()
            assertEquals("the owed GET goes once the ops end", 1, open("GET").size)
        }
        rig(this) {
            loaded(setting(1), setting(2))
            val h = create()
            delete(2)
            answer("DELETE", okRow(server(2)))
            answer("POST", okRow(server(2)))
            assertEquals("a delete that ended first is not undone", listOf(setting(1)), rows)
            assertEquals(BankAlertSaveOutcome.Saved(server(2)), h.outcome.value)
            settle()
            assertEquals(1, open("GET").size)
        }
    }

    @Test fun `S7E5B1_C10 an answer never covers a toggle, another create or a once mark it overlapped`() = runTest {
        rig(this) {
            loaded(setting(1), setting(2))
            create()
            toggle(2, false)
            answer("PUT", okRow(server(2, enabled = false)))
            answer("POST", okRow(server(2, enabled = true)))
            assertEquals("the toggle's row stays", listOf(setting(1), server(2, enabled = false)), rows)
            settle()
            assertEquals("a GET decides", 1, open("GET").size)
        }
        rig(this) {
            loaded(setting(1))
            create()
            create(draft.copy(threshold = 1360.0))
            val (a, b) = open("POST")
            b.outcome.complete(okRow(server(7, repeat = 60)))
            settle()
            a.outcome.complete(okRow(server(7, repeat = 3600)))
            settle()
            assertEquals("the first reflected answer stays", listOf(server(7, repeat = 60), setting(1)), rows)
            settle()
            assertEquals(1, open("GET").size)
        }
        rig(this) {
            loaded(setting(1), setting(2))
            create()
            owner.onBankPush(2, "false")
            settle()
            answer("POST", okRow(server(2)))
            assertEquals("the once mark stays", false, rows!!.single { it.id == 2 }.isEnabled)
        }
        rig(this) {
            loaded(setting(1), setting(2))
            toggle(2, false)
            create()
            answer("PUT", okRow(server(2, enabled = false)))
            answer("POST", okRow(server(2, enabled = true)))
            assertEquals("an op alive at acceptance counts even after it ended", false, rows!!.single { it.id == 2 }.isEnabled)
        }
        rig(this) {
            loaded(setting(1), setting(2))
            toggle(2, false)
            create()
            answer("PUT", okRow(setting(2, enabled = false)))
            assertEquals("premise: the toggle's answer changed nothing on screen", setting(2, enabled = false), rows!!.single { it.id == 2 })
            answer("POST", okRow(server(2, enabled = true)))
            assertEquals("an op alive at acceptance counts even when it ended unseen", setting(2, enabled = false), rows!!.single { it.id == 2 })
        }
    }

    @Test fun `S7E5B1_C11 an answer of another user or currency is not reflected, fails and owes a GET`() = runTest {
        for (wrong in listOf(server(9).copy(userId = "user-b"), server(9, currency = "jpy-krw"))) rig(this) {
            loaded(setting(1))
            val h = create()
            answer("POST", okRow(wrong))
            assertEquals(listOf(setting(1)), rows)
            assertTrue(h.outcome.value is BankAlertSaveOutcome.Failed)
            settle()
            assertEquals(1, open("GET").size)
        }
        rig(this) {
            loaded(setting(1), setting(2, currency = "jpy-krw"))
            val h = create()
            answer("POST", okRow(server(2)))
            assertEquals("a listed row of another currency is not replaced", setting(2, currency = "jpy-krw"), rows!![1])
            assertTrue(h.outcome.value is BankAlertSaveOutcome.Failed)
        }
    }

    @Test fun `S7E5B1_C12 a pre-wire refusal under a valid use is NOT_SENT and owes nothing`() = runTest {
        rig(this) {
            loaded()
            val h = create()
            answer("POST", Outcome.Throw(TopicUseWithheldException(emptyList())))
            assertEquals(notSent(NotSentReason.UNADMITTED), h.outcome.value)
            assertEquals("its slot is free", BankAlertSaveOutcome.Pending, create().outcome.value)
            answer("POST", Outcome.Throw(TopicUseWithheldException(emptyList())))
            nothingOwed("a refused create")
        }
    }

    @Test fun `S7E5B1_C13 a failed or unknown create is FAILED with nothing reflected and a GET owed`() = runTest {
        for (failure in listOf(http(500), http(400), Outcome.Throw(IOException("lost")))) rig(this) {
            loaded()
            val before = rows
            val h = create()
            answer("POST", failure)
            assertTrue(h.outcome.value is BankAlertSaveOutcome.Failed)
            assertEquals(before, rows)
            settle()
            assertEquals("owed", 1, open("GET").size)
        }
    }

    @Test fun `S7E5B1_C14 an answer that lands after its use stopped being admitted is FAILED, unreflected and owed`() = runTest {
        for (reply in listOf(okRow(server(9)), http(500), Outcome.Throw(TopicUseWithheldException(emptyList())))) rig(this) {
            loaded()
            val h = create()
            release = false
            answer("POST", reply)
            assertEquals(listOf(setting(1), setting(2)), rows)
            assertTrue(h.outcome.value is BankAlertSaveOutcome.Failed)
            release = true
            owed("after an unadmitted answer")
        }
    }

    // ======== create: floor, background, reset ========

    @Test fun `S7E5B1_C15 a create waits for the notification-settings floor that a GET shares and records its own evidence`() = runTest {
        rig(this) {
            loaded()
            holdFloor(AuthenticatedEndpoint.NOTIFICATION_SETTINGS, "5")
            create()
            advance(4_999)
            assertEquals(0, count("POST"))
            advance(1)
            assertEquals(1, count("POST"))
            answer("POST", http(429, "8"))
            assertTrue("the answer set the shared floor", floor.remainingMillis(AuthenticatedEndpoint.NOTIFICATION_SETTINGS) > 7_000)
        }
        rig(this) {
            loaded()
            holdFloor(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, "50")
            holdFloor(AuthenticatedEndpoint.DELETE_NOTIFICATION_SETTING, "50")
            create()
            assertEquals("another endpoint's floor does not hold a create", 1, count("POST"))
        }
    }

    @Test fun `S7E5B1_C16 a create not yet sent at the background is NOT_SENT BACKGROUND, never sent and owed`() = runTest {
        rig(this) {
            loaded()
            holdFloor(AuthenticatedEndpoint.NOTIFICATION_SETTINGS, "5")
            val h = create()
            background()
            assertEquals(notSent(NotSentReason.BACKGROUND), h.outcome.value)
            advance(10_000)
            assertEquals(0, count("POST"))
            foreground()
            assertEquals("owed", 1, open("GET").size)
        }
        rig(this) {
            loaded()
            val h = create()
            background()
            answer("POST", okRow(server(9)))
            assertEquals("an answer in flight still lands", BankAlertSaveOutcome.Saved(server(9)), h.outcome.value)
            assertEquals(server(9), rows!!.first())
        }
    }

    @Test fun `S7E5B1_C17 a reset settles a waiting create as RESET and one in flight as FAILED, and its late answer changes nothing`() = runTest {
        rig(this) {
            loaded()
            holdFloor(AuthenticatedEndpoint.NOTIFICATION_SETTINGS, "5")
            val waiting = create()
            switchToAlice2()
            settle()
            assertEquals(notSent(NotSentReason.RESET), waiting.outcome.value)
        }
        rig(this) {
            loaded()
            val flying = create()
            switchToAlice2()
            settle()
            assertTrue(flying.outcome.value is BankAlertSaveOutcome.Failed)
            open("POST").forEach { it.outcome.complete(okRow(server(9))) }
            settle()
            assertTrue("still FAILED", flying.outcome.value is BankAlertSaveOutcome.Failed)
            assertEquals("no row from the old identity", null, rows)
        }
    }

    // ======== edit ========

    @Test fun `S7E5B1_U01 an edit sends only its fields, and repeat in its three states`() = runTest {
        val cases = listOf(
            BankAlertEdit(threshold = 1500.0) to mapOf("threshold" to "1500.0"),
            BankAlertEdit(bank = "kb", condition = AlertCondition.BELOW) to mapOf("bank" to "\"kb\"", "condition" to "\"below\""),
            BankAlertEdit(isEnabled = true, repeat = RepeatIntervalChange.Once) to mapOf("is_enabled" to "true", "repeat_interval_sec" to "null"),
            BankAlertEdit(repeat = RepeatIntervalChange.Every(RepeatInterval.ONE_DAY)) to mapOf("repeat_interval_sec" to "86400"),
            BankAlertEdit() to emptyMap()
        )
        for ((e, expected) in cases) rig(this) {
            loaded(setting(1, repeat = 999), setting(2))
            edit(1, e)
            val call = sent("EDIT").single()
            assertEquals("row 1", 1, call.id)
            assertEquals("$e", expected, json(call.payload).mapValues { it.value.toString() })
        }
    }

    @Test fun `S7E5B1_U02 an edit is not shown before its answer, then the whole server row is`() = runTest {
        rig(this) {
            loaded(setting(1), setting(2))
            val h = edit(1, BankAlertEdit(threshold = 1500.0))
            assertEquals("no optimistic edit", listOf(setting(1), setting(2)), rows)
            answer("EDIT", okRow(server(1).copy(threshold = 1500.0)))
            assertEquals(listOf(server(1).copy(threshold = 1500.0), setting(2)), rows)
            assertEquals(BankAlertSaveOutcome.Saved(server(1).copy(threshold = 1500.0)), h.outcome.value)
            nothingOwed("a reflected edit")
        }
    }

    @Test fun `S7E5B1_U03 an edit 404 removes the row, is REMOVED and owes a GET`() = runTest {
        rig(this) {
            loaded(setting(1), setting(2))
            val h = edit(1, BankAlertEdit(threshold = 1.0))
            answer("EDIT", http(404))
            assertEquals(listOf(setting(2)), rows)
            assertEquals(BankAlertSaveOutcome.Removed, h.outcome.value)
            settle()
            assertEquals(1, open("GET").size)
        }
    }

    @Test fun `S7E5B1_U04 a failed edit rolls back to the approved row, an unknown one does not, and both owe a GET`() = runTest {
        rig(this) {
            loaded(setting(1), setting(2))
            toggle(1, false)
            answer("PUT", okRow(server(1, enabled = false)))
            val h = edit(1, BankAlertEdit(threshold = 1.0))
            answer("EDIT", http(500))
            assertEquals("the approved row", listOf(server(1, enabled = false), setting(2)), rows)
            assertTrue(h.outcome.value is BankAlertSaveOutcome.Failed)
            settle()
            assertEquals(1, open("GET").size)
        }
        rig(this) {
            loaded(setting(1), setting(2))
            edit(1, BankAlertEdit(threshold = 1.0))
            toggle(1, false)
            assertEquals("premise: the later toggle is shown", false, rows!![0].isEnabled)
            answer("EDIT", http(500))
            assertEquals("the failed edit rolls the row back to the approved one", listOf(setting(1), setting(2)), rows)
            advance(60_000)
            assertEquals("and the later toggle is never sent", 0, count("PUT"))
        }
        rig(this) {
            loaded(setting(1), setting(2))
            val h = edit(1, BankAlertEdit(threshold = 1.0))
            answer("EDIT", Outcome.Throw(IOException("lost")))
            assertEquals(listOf(setting(1), setting(2)), rows)
            assertTrue(h.outcome.value is BankAlertSaveOutcome.Failed)
            settle()
            assertEquals(1, open("GET").size)
        }
    }

    @Test fun `S7E5B1_U05 a waiting edit is replaced whole, one in flight is kept and the next goes after it`() = runTest {
        rig(this) {
            loaded()
            holdFloor(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, "5")
            val first = edit(1, BankAlertEdit(threshold = 1.0, isEnabled = false))
            val second = edit(1, BankAlertEdit(bank = "kb"))
            assertEquals(notSent(NotSentReason.SUPERSEDED), first.outcome.value)
            advance(5_000)
            assertEquals("only the second, whole", listOf(mapOf("bank" to "\"kb\"")),
                sent("EDIT").map { json(it.payload).mapValues { e -> e.value.toString() } })
            answer("EDIT", okRow(server(1, bank = "kb")))
            assertEquals(BankAlertSaveOutcome.Saved(server(1, bank = "kb")), second.outcome.value)
        }
        rig(this) {
            loaded()
            val a = edit(1, BankAlertEdit(threshold = 1.0))
            val b = edit(1, BankAlertEdit(threshold = 2.0))
            assertEquals(1, count("EDIT"))
            answer("EDIT", okRow(server(1).copy(threshold = 1.0)))
            assertEquals(BankAlertSaveOutcome.Saved(server(1).copy(threshold = 1.0)), a.outcome.value)
            assertEquals("then the next", 2, count("EDIT"))
            answer("EDIT", okRow(server(1).copy(threshold = 2.0)))
            assertEquals(BankAlertSaveOutcome.Saved(server(1).copy(threshold = 2.0)), b.outcome.value)
        }
    }

    @Test fun `S7E5B1_U06 a replaced edit's enabled intent leaves with it and makes no toggle`() = runTest {
        rig(this) {
            loaded(setting(1, enabled = false), setting(2))
            holdFloor(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, "5")
            edit(1, BankAlertEdit(isEnabled = true, threshold = 1.0))
            edit(1, BankAlertEdit(threshold = 2.0))
            advance(5_000)
            answer("EDIT", okRow(server(1, enabled = false).copy(threshold = 2.0)))
            advance(60_000)
            assertEquals("no ghost toggle", 0, count("PUT"))
            assertEquals(1, count("EDIT"))
        }
    }

    @Test fun `S7E5B1_U07 a toggle accepted after an edit goes after it if it still differs and one before an enabling edit is absorbed`() = runTest {
        rig(this) {
            loaded(setting(1, enabled = false))
            edit(1, BankAlertEdit(threshold = 1.0))
            toggle(1, true)
            answer("EDIT", okRow(server(1, enabled = false).copy(threshold = 1.0)))
            assertEquals("the later toggle goes", listOf(true), sent("PUT").map { it.payload })
            answer("PUT", okRow(server(1, enabled = true).copy(threshold = 1.0)))
            assertEquals(true, rows!!.single().isEnabled)
        }
        rig(this) {
            loaded(setting(1, enabled = true))
            holdFloor(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, "5")
            toggle(1, false)
            edit(1, BankAlertEdit(isEnabled = true, threshold = 1.0))
            advance(5_000)
            assertEquals("the edit first", listOf(1, 0), listOf(count("EDIT"), count("PUT")))
            answer("EDIT", okRow(server(1, enabled = true).copy(threshold = 1.0)))
            advance(60_000)
            assertEquals("the earlier toggle is absorbed by the enabling edit", 0, count("PUT"))
        }
        rig(this) {
            loaded(setting(1, enabled = true))
            holdFloor(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, "5")
            toggle(1, false)
            edit(1, BankAlertEdit(threshold = 1.0))
            advance(5_000)
            answer("EDIT", okRow(server(1, enabled = true).copy(threshold = 1.0)))
            assertEquals("an edit without isEnabled leaves the earlier toggle", listOf(false), sent("PUT").map { it.payload })
        }
    }

    @Test fun `S7E5B1_U08 a server enabled value that differs from the edit is adopted and nothing is resent`() = runTest {
        rig(this) {
            loaded(setting(1, enabled = false))
            val h = edit(1, BankAlertEdit(isEnabled = true))
            answer("EDIT", okRow(server(1, enabled = false)))
            advance(60_000)
            assertEquals(listOf(1, 0), listOf(count("EDIT"), count("PUT")))
            assertEquals(server(1, enabled = false), rows!!.single())
            assertEquals(BankAlertSaveOutcome.Saved(server(1, enabled = false)), h.outcome.value)
        }
    }

    @Test fun `S7E5B1_U09 a delete ends a waiting edit, follows one in flight and keeps the row hidden`() = runTest {
        rig(this) {
            loaded()
            holdFloor(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, "5")
            val h = edit(1, BankAlertEdit(threshold = 1.0))
            delete(1)
            assertEquals(notSent(NotSentReason.SUPERSEDED), h.outcome.value)
            assertEquals("an edit of a deleting row", notSent(NotSentReason.SUPERSEDED), edit(1, BankAlertEdit(threshold = 2.0)).outcome.value)
            advance(5_000)
            assertEquals(listOf(0, 1), listOf(count("EDIT"), count("DELETE")))
        }
        rig(this) {
            loaded()
            val h = edit(1, BankAlertEdit(threshold = 1.0))
            delete(1)
            assertEquals(listOf(setting(2)), rows)
            answer("EDIT", okRow(server(1).copy(threshold = 1.0)))
            assertEquals(BankAlertSaveOutcome.Saved(server(1).copy(threshold = 1.0)), h.outcome.value)
            assertEquals("still hidden", listOf(setting(2)), rows)
            assertEquals(1, count("DELETE"))
        }
    }

    @Test fun `S7E5B1_U10 after a failed toggle or edit the waiting edit is SUPERSEDED and nothing but a delete follows`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            val waiting = edit(1, BankAlertEdit(threshold = 1.0))
            answer("PUT", http(500))
            assertEquals(notSent(NotSentReason.SUPERSEDED), waiting.outcome.value)
            advance(60_000)
            assertEquals(0, count("EDIT"))
        }
        rig(this) {
            loaded()
            edit(1, BankAlertEdit(threshold = 1.0))
            val waiting = edit(1, BankAlertEdit(threshold = 2.0))
            toggle(1, false)
            answer("EDIT", Outcome.Throw(IOException("lost")))
            assertEquals(notSent(NotSentReason.SUPERSEDED), waiting.outcome.value)
            advance(60_000)
            assertEquals("no edit, no toggle", listOf(1, 0), listOf(count("EDIT"), count("PUT")))
        }
    }

    @Test fun `S7E5B1_U11 an edit of an absent row or without a use is refused at once`() = runTest {
        rig(this) {
            loaded()
            assertEquals(notSent(NotSentReason.NOT_READY), edit(9, BankAlertEdit(threshold = 1.0)).outcome.value)
            release = false
            assertEquals(notSent(NotSentReason.UNADMITTED), edit(1, BankAlertEdit(threshold = 1.0)).outcome.value)
            release = true
            background()
            assertEquals(notSent(NotSentReason.NOT_READY), edit(1, BankAlertEdit(threshold = 1.0)).outcome.value)
            assertEquals(0, count("EDIT"))
        }
    }

    @Test fun `S7E5B1_U12 a waiting edit at the background is BACKGROUND, at a reset RESET, in flight at a reset FAILED`() = runTest {
        rig(this) {
            loaded()
            holdFloor(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, "5")
            val h = edit(1, BankAlertEdit(threshold = 1.0))
            background()
            assertEquals(notSent(NotSentReason.BACKGROUND), h.outcome.value)
            advance(10_000)
            assertEquals(0, count("EDIT"))
        }
        rig(this) {
            loaded()
            holdFloor(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, "5")
            val h = edit(1, BankAlertEdit(threshold = 1.0))
            switchToAlice2()
            settle()
            assertEquals(notSent(NotSentReason.RESET), h.outcome.value)
        }
        rig(this) {
            loaded()
            val h = edit(1, BankAlertEdit(threshold = 1.0))
            switchToAlice2()
            settle()
            assertTrue(h.outcome.value is BankAlertSaveOutcome.Failed)
        }
    }

    @Test fun `S7E5B1_U13 a waiting edit behind an op whose admission ended is UNADMITTED, and behind a 404 NOT_READY`() = runTest {
        rig(this) {
            loaded()
            edit(1, BankAlertEdit(threshold = 1.0))
            val waiting = edit(1, BankAlertEdit(threshold = 2.0))
            release = false
            answer("EDIT", okRow(server(1)))
            assertTrue("waiting is settled", waiting.outcome.value != BankAlertSaveOutcome.Pending)
            assertEquals(notSent(NotSentReason.UNADMITTED), waiting.outcome.value)
        }
        rig(this) {
            loaded()
            toggle(1, false)
            val waiting = edit(1, BankAlertEdit(threshold = 2.0))
            answer("PUT", http(404))
            assertEquals(notSent(NotSentReason.NOT_READY), waiting.outcome.value)
        }
    }

    // ======== GET ========

    @Test fun `S7E5B1_G01 a GET that started before a create or an edit is not accepted after it, even if nothing changed`() = runTest {
        for (kind in listOf("POST", "EDIT")) rig(this) {
            loaded()
            owner.manual()
            settle()
            assertEquals(1, open("GET").size)
            if (kind == "POST") create() else edit(1, BankAlertEdit(threshold = 1.0))
            answer(kind, Outcome.Throw(IOException("lost")))
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("$kind: the early body is discarded and a trailing GET goes", 1, open("GET").size)
            answer("GET", okList(setting(1), setting(2)))
            nothingOwed("$kind: the trailing GET paid the debt")
        }
    }

    @Test fun `S7E5B1_G04 accepting a create or an edit alone bars an earlier GET, even when it is refused before the wire`() = runTest {
        for (kind in listOf("POST", "EDIT")) rig(this) {
            loaded()
            owner.manual()
            settle()
            assertEquals(1, open("GET").size)
            if (kind == "POST") create() else edit(1, BankAlertEdit(threshold = 1.0))
            answer(kind, Outcome.Throw(TopicUseWithheldException(emptyList())))
            answer("GET", okList(setting(1)))
            assertEquals("$kind: the early body is not accepted", listOf(setting(1), setting(2)), rows)
            assertEquals("$kind: a trailing GET goes instead", 1, open("GET").size)
        }
    }

    @Test fun `S7E5B1_G02 no GET starts while a create is in flight, and the last one ending wakes a waiting GET`() = runTest {
        rig(this) {
            loaded()
            create()
            owner.manual()
            settle()
            assertEquals("waits for the create", 0, open("GET").size)
            answer("POST", okRow(server(9)))
            assertEquals("woken", 1, open("GET").size)
        }
    }

    @Test fun `S7E5B1_G03 a create or edit does not change the manual refresh state or its cooldown`() = runTest {
        rig(this) {
            loaded()
            val before = owner.manualRefresh.value
            create()
            answer("POST", okRow(server(9)))
            edit(9, BankAlertEdit(threshold = 1.0))
            answer("EDIT", okRow(server(9)))
            assertEquals(before, owner.manualRefresh.value)
        }
    }

    // ======== common ========

    @Test fun `S7E5B1_X01 a handle settles once and a later reset or answer does not change it`() = runTest {
        rig(this) {
            loaded()
            val h = create()
            answer("POST", okRow(server(9)))
            val settled = h.outcome.value
            switchToAlice2()
            settle()
            assertEquals(settled, h.outcome.value)
        }
    }

    @Test fun `S7E5B1_X02 on an eager dispatcher a create and an edit still send once and settle`() = runTest {
        rig(this, eager = true) {
            loaded()
            val c = owner.addAlert(draft)
            assertEquals(1, count("POST"))
            answer("POST", okRow(server(9)))
            assertEquals(BankAlertSaveOutcome.Saved(server(9)), c.outcome.value)
            val e = owner.editAlert(1, BankAlertEdit(threshold = 1.0))
            assertEquals(1, count("EDIT"))
            answer("EDIT", okRow(server(1)))
            assertEquals(BankAlertSaveOutcome.Saved(server(1)), e.outcome.value)
        }
    }
}
