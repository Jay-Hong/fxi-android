package com.jay.fxi.data.alerts

import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.RepeatInterval
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 E5b1 gap contracts, group A: the gap hunt `R4c/S7/s7e5/s7e5b1_gaphunt.codex.r1.md` section 3, ids G01 G02 G03
 * G04 G09 G11 G12 G14 G21 G22 G23 G25, against the agreed API `R4c/S7/s7e5/s7e5b1_api_agreed.r1.md` (create/update named
 * addAlert/editAlert). Each test closes an input or ordering the base contract [S7E5B1OwnerSaveContractTest] does not reach:
 * - G01-G04: the cap counts unique ids; a slot is reserved at acceptance, before any worker runs; a settled create or delete
 *   returns exactly its slot; a Saved create whose reflection waits, and a GET that is discarded or fails, settle nothing.
 * - G09: a create answer for a listed id of another user is not reflected.
 * - G11: a Saved row is a copy of neither the snapshot nor the row op's approved row.
 * - G12: every repeat interval, the empty edit's whole round trip, unknown bank and repeat values passed through untouched.
 * - G14: an edit behind a successful toggle goes next; two rows' edits are in flight together and settle independently.
 * - G21: a once push for a row under edit is only a GET debt, never a mark now or a replay later.
 * - G22: no test of its own: both counterexamples are already killed by S7E5B1_C09/C10 (see the group report).
 * - G23: a create or an edit is not a GET success: no freshness, no manual outcome, no cooldown.
 * - G25: an edit adopts every field of the server row, never its own patch, including in the row op's approved row.
 * The rig is [S7E5B1SaveFixture]. The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class S7E5B1GapAContractTest : S7E5B1SaveFixture() {
    // ======== G01-G04: cap and reservations ========

    @Test fun `S7E5B1_GAP01 the cap counts unique ids, not list entries, and a listed id under a delete only once`() = runTest {
        rig(this) {
            loaded(*rowsOf(29), setting(1))
            assertEquals("premise: 30 entries of 29 ids", 30, rows!!.size)
            assertEquals("29 unique ids leave one slot", BankAlertSaveOutcome.Pending, create().outcome.value)
            assertEquals("the create in flight takes it", notSent(NotSentReason.CAP), create().outcome.value)
            assertEquals(1, count("POST"))
        }
        rig(this) {
            loaded(*rowsOf(29), setting(29))
            delete(29)
            assertEquals("premise: one copy of id 29 stays listed", 1, rows!!.count { it.id == 29 })
            assertEquals("an id both listed and under a delete counts once", BankAlertSaveOutcome.Pending, create().outcome.value)
            assertEquals(notSent(NotSentReason.CAP), create().outcome.value)
            assertEquals(1, count("POST"))
        }
    }

    @Test fun `S7E5B1_GAP02 a cap slot is reserved at acceptance, before any worker runs`() = runTest {
        rig(this) {
            loaded(*rowsOf(29))
            val a = owner.addAlert(draft)
            val b = owner.addAlert(draft.copy(threshold = 1360.0))
            assertEquals(BankAlertSaveOutcome.Pending, a.outcome.value)
            assertEquals("the second sees the first one's reservation at once", notSent(NotSentReason.CAP), b.outcome.value)
            assertEquals("no worker ran yet", 0, count("POST"))
            settle()
            assertEquals("one send", 1, count("POST"))
            answer("POST", okRow(server(90)))
            assertEquals(BankAlertSaveOutcome.Saved(server(90)), a.outcome.value)
            assertEquals("the refused one stays refused", notSent(NotSentReason.CAP), b.outcome.value)
        }
        rig(this, eager = true) {
            loaded(*rowsOf(29))
            owner.addAlert(draft)
            assertEquals(notSent(NotSentReason.CAP), owner.addAlert(draft.copy(threshold = 1360.0)).outcome.value)
            assertEquals(1, count("POST"))
        }
    }

    @Test fun `S7E5B1_GAP03 a settled create or delete leaves exactly the capacity it took`() = runTest {
        rig(this) {
            loaded(*rowsOf(29))
            create()
            answer("POST", okRow(server(90)))
            assertEquals(30, rows!!.size)
            assertEquals("a new id fills the last slot", notSent(NotSentReason.CAP), create().outcome.value)
        }
        rig(this) {
            loaded(*rowsOf(29))
            val h = create()
            answer("POST", okRow(server(2)))
            assertEquals(BankAlertSaveOutcome.Saved(server(2)), h.outcome.value)
            assertEquals("premise: a listed id adds no row", 29, rows!!.size)
            assertEquals("so its slot is free again", BankAlertSaveOutcome.Pending, create().outcome.value)
            assertEquals("but only that one", notSent(NotSentReason.CAP), create().outcome.value)
        }
        rig(this) {
            loaded(*rowsOf(29))
            val h = create()
            answer("POST", Outcome.Throw(TopicUseWithheldException(emptyList())))
            assertEquals(notSent(NotSentReason.UNADMITTED), h.outcome.value)
            assertEquals("a refusal before the wire frees its slot", BankAlertSaveOutcome.Pending, create().outcome.value)
            assertEquals(notSent(NotSentReason.CAP), create().outcome.value)
        }
        for (reply in listOf("deleted", "404")) rig(this) {
            loaded(*rowsOf(30))
            delete(30)
            answer("DELETE", if (reply == "deleted") okRow(server(30)) else http(404))
            assertEquals(29, rows!!.size)
            assertEquals("$reply: a settled delete frees its slot", BankAlertSaveOutcome.Pending, create().outcome.value)
            assertEquals("$reply: one slot only", notSent(NotSentReason.CAP), create().outcome.value)
        }
    }

    @Test fun `S7E5B1_GAP04 a Saved create whose reflection waits keeps its slot until an accepted GET`() = runTest {
        rig(this) {
            loaded(*rowsOf(29))
            val h = create()
            delete(29)
            answer("POST", okRow(server(29)))
            assertEquals("a checked success", BankAlertSaveOutcome.Saved(server(29)), h.outcome.value)
            assertEquals("premise: not reflected, the row stays hidden", 28, rows!!.size)
            assertEquals("the waiting create keeps its slot", notSent(NotSentReason.CAP), create().outcome.value)
            answer("DELETE", okRow(server(29)))
            settle()
            answer("GET", okList(*rowsOf(29)))
            assertEquals("the accepted GET settled it: one slot", BankAlertSaveOutcome.Pending, create().outcome.value)
            assertEquals(notSent(NotSentReason.CAP), create().outcome.value)
        }
    }

    @Test fun `S7E5B1_GAP04 a discarded or failed GET settles no create reservation`() = runTest {
        rig(this) {
            loaded(*rowsOf(29))
            owner.manual()
            settle()
            assertEquals(1, open("GET").size)
            create()
            answer("POST", Outcome.Throw(IOException("lost")))
            answer("GET", okList(*rowsOf(29)))
            assertEquals("premise: the early body is discarded and a trailing GET goes", 1, open("GET").size)
            assertEquals("a discarded body settles nothing", notSent(NotSentReason.CAP), create().outcome.value)
            answer("GET", okList(*rowsOf(29)))
            assertEquals("the trailing accepted GET does", BankAlertSaveOutcome.Pending, create().outcome.value)
        }
        rig(this) {
            loaded(*rowsOf(29))
            create()
            answer("POST", http(500))
            settle()
            answer("GET", http(403))
            assertEquals("premise: the GET ended without a retry", 0, open("GET").size)
            assertEquals("a failed GET settles nothing", notSent(NotSentReason.CAP), create().outcome.value)
        }
    }

    // ======== G09, G11: create answer checks and copies ========

    @Test fun `S7E5B1_GAP09 a create answer for a listed id of another user is not reflected, fails and owes a GET`() = runTest {
        rig(this) {
            loaded(setting(1), setting(2).copy(userId = "user-b"))
            val h = create()
            assertEquals("premise: the answer itself is this user's, in the draft's currency",
                listOf(alice.uid, draft.currency), listOf(server(2).userId, server(2).currency))
            answer("POST", okRow(server(2)))
            assertEquals("the listed row is kept", listOf(setting(1), setting(2).copy(userId = "user-b")), rows)
            assertTrue(h.outcome.value is BankAlertSaveOutcome.Failed)
            settle()
            assertEquals("owed", 1, open("GET").size)
        }
    }

    @Test fun `S7E5B1_GAP11 a Saved row is a copy of neither the snapshot nor the row op's approved row`() = runTest {
        rig(this) {
            loaded(setting(1), setting(2))
            val a = edit(1, BankAlertEdit(threshold = 1500.0))
            edit(1, BankAlertEdit(threshold = 1600.0))
            val whole = server(1, enabled = false).copy(threshold = 1500.0, triggered = true, triggeredAt = later)
            answer("EDIT", okRow(whole))
            val saved = a.outcome.value as BankAlertSaveOutcome.Saved
            assertEquals(whole, saved.row)
            assertEquals(listOf(whole, setting(2)), rows)
            assertNotSame("the edit's Saved row is not the listed one", rows!![0], saved.row)
            saved.row.isEnabled = true
            saved.row.triggered = false
            saved.row.updatedAt = stamp
            saved.row.triggeredAt = null
            assertEquals("changing the copy leaves the snapshot", listOf(whole, setting(2)), rows)
            answer("EDIT", http(500))
            assertEquals("and the rollback restores the server row, not the changed copy", listOf(whole, setting(2)), rows)
        }
        rig(this) {
            loaded(setting(1), setting(2))
            val h = create()
            answer("POST", okRow(server(2)))
            val saved = h.outcome.value as BankAlertSaveOutcome.Saved
            assertEquals(server(2), saved.row)
            assertNotSame("a create replacing a listed id hands out a copy", rows!![1], saved.row)
            saved.row.isEnabled = false
            saved.row.triggered = true
            assertEquals("changing the copy leaves the snapshot", listOf(setting(1), server(2)), rows)
        }
    }

    // ======== G12: the wire domain ========

    @Test fun `S7E5B1_GAP12 every repeat interval goes on the wire as its seconds, for a draft and an edit`() = runTest {
        for (interval in RepeatInterval.entries) {
            rig(this) {
                loaded()
                create(draft.copy(repeat = interval))
                assertEquals("$interval draft", interval.seconds.toString(),
                    json(sent("POST").single().payload)["repeat_interval_sec"].toString())
            }
            rig(this) {
                loaded()
                edit(1, BankAlertEdit(repeat = RepeatIntervalChange.Every(interval)))
                assertEquals("$interval edit", mapOf("repeat_interval_sec" to interval.seconds.toString()),
                    json(sent("EDIT").single().payload).mapValues { it.value.toString() })
            }
        }
    }

    @Test fun `S7E5B1_GAP12 an empty edit is one empty PUT that settles on its answer and is never resent`() = runTest {
        rig(this) {
            loaded()
            val h = edit(1, BankAlertEdit())
            assertEquals(emptyMap<String, String>(), json(sent("EDIT").single().payload).mapValues { it.value.toString() })
            answer("EDIT", okRow(server(1)))
            assertEquals(BankAlertSaveOutcome.Saved(server(1)), h.outcome.value)
            assertEquals(listOf(server(1), setting(2)), rows)
            advance(60_000)
            assertEquals("no resend, no toggle", listOf(1, 0), listOf(count("EDIT"), count("PUT")))
        }
    }

    @Test fun `S7E5B1_GAP12 the owner passes unknown bank and repeat values through untouched`() = runTest {
        rig(this) {
            loaded(setting(1, bank = "future-bank", repeat = 999), setting(2))
            val h = edit(1, BankAlertEdit(threshold = 1500.0))
            assertEquals("only the threshold", mapOf("threshold" to "1500.0"),
                json(sent("EDIT").single().payload).mapValues { it.value.toString() })
            val answered = server(1, bank = "future-bank", repeat = 999).copy(threshold = 1500.0)
            answer("EDIT", okRow(answered))
            assertEquals(BankAlertSaveOutcome.Saved(answered), h.outcome.value)
            assertEquals("the raw bank and repeat stay", listOf(answered, setting(2)), rows)
        }
        rig(this) {
            loaded()
            create(draft.copy(bank = "future-bank"))
            assertEquals("a draft's bank goes as given", "\"future-bank\"", json(sent("POST").single().payload)["bank"].toString())
        }
    }

    // ======== G14: per-row serial, rows independent ========

    @Test fun `S7E5B1_GAP14 an edit accepted behind a successful toggle goes next with its own patch`() = runTest {
        rig(this) {
            loaded(setting(1), setting(2))
            toggle(1, false)
            val h = edit(1, BankAlertEdit(threshold = 1500.0))
            assertEquals("serial behind the toggle", 0, count("EDIT"))
            answer("PUT", okRow(server(1, enabled = false)))
            assertEquals("then the edit, with its own fields", mapOf("threshold" to "1500.0"),
                json(sent("EDIT").single().payload).mapValues { it.value.toString() })
            val answered = server(1, enabled = false).copy(threshold = 1500.0)
            answer("EDIT", okRow(answered))
            assertEquals(BankAlertSaveOutcome.Saved(answered), h.outcome.value)
            assertEquals(listOf(answered, setting(2)), rows)
            assertEquals(1, count("PUT"))
        }
    }

    @Test fun `S7E5B1_GAP14 edits of two rows are in flight together and settle independently`() = runTest {
        rig(this) {
            loaded(setting(1), setting(2))
            val a = edit(1, BankAlertEdit(threshold = 1500.0))
            val b = edit(2, BankAlertEdit(threshold = 1600.0))
            assertEquals("one worker per row, not one for the owner", listOf(1, 2), open("EDIT").mapNotNull { it.id }.sorted())
            val answered = server(2).copy(threshold = 1600.0)
            open("EDIT").single { it.id == 2 }.outcome.complete(okRow(answered))
            settle()
            assertEquals(BankAlertSaveOutcome.Saved(answered), b.outcome.value)
            assertEquals("row 1 is still in flight", BankAlertSaveOutcome.Pending, a.outcome.value)
            open("EDIT").single { it.id == 1 }.outcome.complete(http(500))
            settle()
            assertTrue(a.outcome.value is BankAlertSaveOutcome.Failed)
            assertEquals("row 2 keeps its server row, row 1 its approved one", listOf(setting(1), answered), rows)
            assertEquals(BankAlertSaveOutcome.Saved(answered), b.outcome.value)
        }
    }

    // ======== G21: a once push during an edit ========

    @Test fun `S7E5B1_GAP21 a once push for a row under edit is only a GET debt, now and after the edit`() = runTest {
        rig(this) {
            loaded(setting(1), setting(2))
            val h = edit(1, BankAlertEdit(threshold = 1500.0))
            owner.onBankPush(1, "false")
            settle()
            assertEquals("no once mark on the row under edit", listOf(setting(1), setting(2)), rows)
            assertEquals("no GET while the edit is active", 0, open("GET").size)
            val answered = server(1).copy(threshold = 1500.0)
            answer("EDIT", okRow(answered))
            assertEquals(BankAlertSaveOutcome.Saved(answered), h.outcome.value)
            assertEquals("the server row, and no mark replayed over it", listOf(answered, setting(2)), rows)
            assertEquals("the owed GET goes", 1, open("GET").size)
            val truth = server(1, enabled = false).copy(threshold = 1500.0, triggered = true)
            answer("GET", okList(truth, setting(2)))
            assertEquals("the GET decides", listOf(truth, setting(2)), rows)
        }
        rig(this) {
            loaded(setting(1), setting(2))
            edit(1, BankAlertEdit(threshold = 1500.0))
            owner.onBankPush(2, "false")
            settle()
            val marked = setting(2, enabled = false).copy(triggered = true)
            assertEquals("another row is marked", listOf(setting(1), marked), rows)
            val answered = server(1).copy(threshold = 1500.0)
            answer("EDIT", okRow(answered))
            assertEquals("the edit does not undo it", listOf(answered, marked), rows)
            assertEquals(1, open("GET").size)
        }
    }

    // ======== G23: a mutation is not a GET success ========

    @Test fun `S7E5B1_GAP23 a create or an edit does not refresh the GET freshness`() = runTest {
        for (kind in listOf("POST", "EDIT")) rig(this) {
            loaded()
            advance(29_000)
            if (kind == "POST") {
                create()
                answer("POST", okRow(server(9)))
            } else {
                edit(1, BankAlertEdit(threshold = 1.0))
                answer("EDIT", okRow(server(1).copy(threshold = 1.0)))
            }
            advance(2_000)
            owner.ensure()
            settle()
            assertEquals("$kind: the last GET success is 31 s old, so a GET goes", 1, open("GET").size)
        }
    }

    @Test fun `S7E5B1_GAP23 a create or an edit finishes no manual refresh, only the GET it waits for does`() = runTest {
        for (kind in listOf("POST", "EDIT")) rig(this) {
            loaded()
            owner.manual()
            settle()
            assertEquals(1, open("GET").size)
            if (kind == "POST") {
                create()
                answer("POST", okRow(server(9)))
            } else {
                edit(1, BankAlertEdit(threshold = 1.0))
                answer("EDIT", okRow(server(1).copy(threshold = 1.0)))
            }
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("$kind: premise, the early body is discarded and a trailing GET goes", 1, open("GET").size)
            assertEquals("$kind: no manual outcome yet", emptyList<ManualRefreshOutcome>(), outcomes)
            assertEquals("$kind: no cooldown yet", null, owner.manualRefresh.value.cooldownUntilMillis)
            assertTrue("$kind: the manual demand is still held", owner.manualRefresh.value.requested)
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("$kind: the trailing GET's own success", listOf<ManualRefreshOutcome>(ManualRefreshOutcome.Succeeded), outcomes)
        }
    }

    // ======== G25: the server row is the whole truth ========

    @Test fun `S7E5B1_GAP25 an edit adopts every field of the server row, never its own patch`() = runTest {
        val whole = AlertSetting(1, "user-a", "woori", "eur-krw", AlertCondition.BELOW, 1600.5, false, true, later, later, later, 600)
        rig(this) {
            loaded(setting(1), setting(2))
            val h = edit(1, BankAlertEdit(bank = "kb", threshold = 1500.0, isEnabled = true))
            answer("EDIT", okRow(whole))
            assertEquals(BankAlertSaveOutcome.Saved(whole), h.outcome.value)
            assertEquals("the whole server row, in place", listOf(whole, setting(2)), rows)
            advance(60_000)
            assertEquals("nothing sent to match the patch", listOf(1, 0), listOf(count("EDIT"), count("PUT")))
        }
        rig(this) {
            loaded(setting(1), setting(2))
            edit(1, BankAlertEdit(bank = "kb", threshold = 1500.0, isEnabled = true))
            delete(1)
            answer("EDIT", okRow(whole))
            assertEquals("premise: still hidden by the delete", listOf(setting(2)), rows)
            answer("DELETE", http(500))
            assertEquals("the failed delete restores the edit's whole server row", listOf(whole, setting(2)), rows)
        }
    }
}
