package com.jay.fxi.data.alerts

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.HttpExchangeEvidence
import com.jay.fxi.data.remote.AuthenticatedEndpoint
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.repository.AlertRateLimitResponseEvidence
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.mapAlertHttpFailure
import com.jay.fxi.domain.model.AlertSetting
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 E5b1 contract, gap group B (R4c/S7/s7e5/s7e5b1_gaphunt.codex.r1.md §3: G05 G06 G07 G08 G10 G13 G15 G16 G17 G18 G19
 * G20 G24), for the agreed owner API (R4c/S7/s7e5/s7e5b1_api_agreed.r1.md; create/update are named addAlert/editAlert).
 * - G05: an accepted request use or a row's continuation is never renewed by a fresh acquire or a later acceptance.
 * - G06: each send's admission supplier, and the check of its answer, ask that send's captured attempt use only.
 * - G07: the endpoint floor is read again at every wake and once more after the final acquire.
 * - G08: response evidence reaches the request's own endpoint floor before any ownership, admission or identity check.
 * - G10: an edit answer for another row or user is not reflected. G13: an edit's enabled intent is never optimistic and keeps
 *   acceptance order against separately accepted toggles. G15–G18: what an edit's 404, pre-wire refusal, unknown result and
 *   failure do to the shown row, the debt and the requests queued behind it. G19, G20, G24: background, reset and transport
 *   cancellation settle every handle once and send nothing more.
 * Built on the shared [S7E5B1SaveFixture] rig. The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class S7E5B1GapBContractTest : S7E5B1SaveFixture() {
    private val getFloor = AuthenticatedEndpoint.NOTIFICATION_SETTINGS
    private val editFloor = AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING
    private val deleteFloor = AuthenticatedEndpoint.DELETE_NOTIFICATION_SETTING
    private val saves = listOf("POST", "EDIT")

    private fun floorOf(kind: String) = if (kind == "POST") getFloor else editFloor

    /** A create of [draft], or an edit of row 1's threshold. */
    private fun Rig.save(kind: String): BankAlertSaveRequest =
        if (kind == "POST") create() else edit(1, BankAlertEdit(threshold = 1500.0))

    private fun Rig.saveUnsettled(kind: String): BankAlertSaveRequest =
        if (kind == "POST") owner.addAlert(draft) else owner.editAlert(1, BankAlertEdit(threshold = 1500.0))

    private fun Rig.floors(): List<Long> =
        listOf(floor.remainingMillis(getFloor), floor.remainingMillis(editFloor), floor.remainingMillis(deleteFloor))

    private fun <T> bound(requireCurrent: () -> Unit, result: Result<T>, evidence: AlertRateLimitResponseEvidence?): Outcome =
        Outcome.Answer(AuthBoundResult(requireCurrent, result, evidence))

    private fun httpFailure(status: Int, retryAfter: String): Throwable = mapAlertHttpFailure(
        AuthenticatedHttpFailure(status, headersOf("Retry-After", retryAfter), ByteArray(0), null, null, AuthenticatedFailureKind.OTHER_HTTP)
    )

    private fun decodeFailure(): Outcome = bound({},
        Result.failure<AlertSetting>(AlertRepositoryException("bad body", failureKind = AlertRepositoryFailureKind.DECODE)),
        AlertRateLimitResponseEvidence(200, null))

    private fun okDelete(): Outcome = bound({}, Result.success(Unit), AlertRateLimitResponseEvidence(204, null))

    private fun failed(value: BankAlertSaveOutcome) = value is BankAlertSaveOutcome.Failed

    /**
     * Runs [action] once, at the first live identity read made inside [AlertSettingsAccess.acquire]: the final acquire of a send, in
     * the owner's own Main step. Earlier reads (an admission check) re-arm the shared rig's one-shot hook. Returns whether it ran.
     */
    private fun Rig.insideNextAcquire(action: () -> Unit): () -> Boolean {
        var fired = false
        lateinit var hook: () -> Unit
        hook = {
            val inAcquire = Throwable().stackTrace.any {
                it.className == AlertSettingsAccess::class.java.name && it.methodName == "acquire"
            }
            if (inAcquire) {
                fired = true
                action()
            } else {
                beforeIdentity = hook
            }
        }
        beforeIdentity = hook
        return { fired }
    }

    // ======== G05: an acceptance is not renewed ========

    @Test fun `S7E5B1_GAP05 a create or edit whose accepted use expired while it waited is UNADMITTED though a fresh acquire works`() = runTest {
        for (kind in saves) rig(this) {
            loaded()
            holdFloor(floorOf(kind), "5")
            val h = save(kind)
            snapshot = snapshot.copy(userInvalidations = 1)
            assertTrue("$kind: premise, a fresh use is admitted", access.acquire() != null)
            advance(5_000)
            assertEquals("$kind: never sent", 0, count(kind))
            assertEquals(kind, notSent(NotSentReason.UNADMITTED), h.outcome.value)
            assertEquals("$kind: nothing reflected", listOf(setting(1), setting(2)), rows)
            assertEquals("$kind: owed", 1, open("GET").size)
        }
    }

    @Test fun `S7E5B1_GAP05 an edit accepted with a fresh use does not renew the expired continuation of its row's waiting toggle`() = runTest {
        rig(this) {
            loaded()
            holdFloor(editFloor, "5")
            toggle(1, false)
            snapshot = snapshot.copy(userInvalidations = 1)
            val h = edit(1, BankAlertEdit(threshold = 1500.0))
            advance(5_000)
            assertEquals("neither the edit nor the toggle", listOf(0, 0), listOf(count("EDIT"), count("PUT")))
            assertEquals(notSent(NotSentReason.UNADMITTED), h.outcome.value)
        }
    }

    // ======== G06: the captured attempt use ========

    @Test fun `S7E5B1_GAP06 a create's or edit's admission supplier asks its own attempt use and no fresh or later one`() = runTest {
        for (kind in saves) rig(this) {
            loaded()
            save(kind)
            val call = open(kind).single()
            holdFloor(floorOf(kind), "5")
            assertTrue("$kind: admitted while its use stands, whatever the floor", call.admitted())
            snapshot = snapshot.copy(userInvalidations = 1)
            assertTrue("$kind: premise, a fresh use is admitted", access.acquire() != null)
            assertFalse("$kind: the captured attempt is not", call.admitted())
            if (kind == "POST") create() else edit(2, BankAlertEdit(threshold = 1.0))
            assertFalse("$kind: nor renewed by a later request's use", call.admitted())
        }
    }

    @Test fun `S7E5B1_GAP06 an answer after the attempt use expired is FAILED and neither reflected, rolled back nor removed`() = runTest {
        for (kind in saves) for (reply in listOf("ok", "404", "500", "withheld")) rig(this) {
            loaded()
            val h = save(kind)
            if (kind == "EDIT") toggle(1, false)
            val shown = rows
            snapshot = snapshot.copy(userInvalidations = 1)
            answer(kind, when (reply) {
                "ok" -> okRow(if (kind == "POST") server(9) else server(1).copy(threshold = 1500.0))
                "404" -> http(404)
                "500" -> http(500)
                else -> Outcome.Throw(TopicUseWithheldException(emptyList()))
            })
            assertTrue("$kind $reply: FAILED", failed(h.outcome.value))
            assertEquals("$kind $reply: the shown rows stay", shown, rows)
            settle()
            assertEquals("$kind $reply: owed", 1, open("GET").size)
        }
    }

    // ======== G07: the floor is read at every wake and after the final acquire ========

    @Test fun `S7E5B1_GAP07 a floor extended while a create or edit waits holds it to the new deadline`() = runTest {
        for (kind in saves) rig(this) {
            loaded()
            holdFloor(floorOf(kind), "5")
            save(kind)
            advance(4_000)
            holdFloor(floorOf(kind), "6")
            advance(1_000)
            assertEquals("$kind: not at the first deadline", 0, count(kind))
            advance(4_999)
            assertEquals(0, count(kind))
            advance(1)
            assertEquals("$kind: at the extended one", 1, count(kind))
        }
    }

    @Test fun `S7E5B1_GAP07 a floor raised inside the final acquire still holds the create or edit`() = runTest {
        for (kind in saves) rig(this) {
            loaded()
            saveUnsettled(kind)
            val fired = insideNextAcquire { holdFloor(floorOf(kind), "5") }
            settle()
            assertTrue("$kind: premise, the floor rose inside the final acquire", fired())
            assertEquals("$kind: not sent past it", 0, count(kind))
            advance(4_999)
            assertEquals(0, count(kind))
            advance(1)
            assertEquals("$kind: sent once at its deadline", 1, count(kind))
        }
    }

    @Test fun `S7E5B1_GAP07 a delete that ends a waiting edit is held by the delete floor, not the edit's`() = runTest {
        rig(this) {
            loaded()
            holdFloor(editFloor, "5")
            holdFloor(deleteFloor, "12")
            val h = edit(1, BankAlertEdit(threshold = 1500.0))
            delete(1)
            assertEquals(notSent(NotSentReason.SUPERSEDED), h.outcome.value)
            advance(5_000)
            assertEquals("the edit floor ended", listOf(0, 0), listOf(count("EDIT"), count("DELETE")))
            advance(6_999)
            assertEquals(0, count("DELETE"))
            advance(1)
            assertEquals("the delete alone, at its floor", listOf(0, 1), listOf(count("EDIT"), count("DELETE")))
        }
    }

    // ======== G08: evidence first, on the request's own floor ========

    @Test fun `S7E5B1_GAP08 a create or edit records thrown identity and use evidence on its own floor, the exchanges first`() = runTest {
        for (kind in saves) {
            val own = if (kind == "POST") 0 else 1
            val cases = listOf(
                Outcome.Throw(AuthIdentityChangedException(503, "90", listOf(HttpExchangeEvidence(1, 1, 429, "10")))) to 10_000L,
                Outcome.Throw(TopicUseWithheldException(listOf(HttpExchangeEvidence(1, 1, 503, "9")))) to 9_000L,
                Outcome.Throw(IOException("no response")) to 0L,
                Outcome.Throw(CancellationException("no response")) to 0L,
                decodeFailure() to 0L
            )
            for ((i, case) in cases.withIndex()) rig(this) {
                loaded()
                val h = save(kind)
                answer(kind, case.first)
                assertTrue("$kind #$i: FAILED", failed(h.outcome.value))
                val expected = mutableListOf(0L, 0L, 0L).also { it[own] = case.second }
                assertEquals("$kind #$i: floors (GET, EDIT, DELETE)", expected, floors())
                assertEquals("$kind #$i: nothing reflected", listOf(setting(1), setting(2)), rows)
            }
        }
    }

    @Test fun `S7E5B1_GAP08 a create's or edit's answer evidence is on its floor before the identity check inside fold`() = runTest {
        for (kind in saves) rig(this) {
            loaded()
            var seen: Long? = null
            val h = save(kind)
            answer(kind, bound({
                seen = floor.remainingMillis(floorOf(kind))
                throw AuthIdentityChangedException()
            }, Result.failure<AlertSetting>(httpFailure(429, "7")), AlertRateLimitResponseEvidence(429, "7")))
            assertEquals("$kind: recorded before fold's identity check", 7_000L, seen)
            assertTrue("$kind: FAILED", failed(h.outcome.value))
            val expected = if (kind == "POST") listOf(7_000L, 0L, 0L) else listOf(0L, 7_000L, 0L)
            assertEquals(kind, expected, floors())
            assertEquals("$kind: nothing reflected", listOf(setting(1), setting(2)), rows)
        }
    }

    @Test fun `S7E5B1_GAP08 a late create or edit answer after a reset still records its floor and changes nothing else`() = runTest {
        for (kind in saves) rig(this) {
            ignoresCancellation = true
            loaded()
            val h = save(kind)
            val call = open(kind).single()
            owner.reset()
            settle()
            assertTrue("$kind: FAILED by the reset", failed(h.outcome.value))
            val settled = h.outcome.value
            call.outcome.complete(http(429, "7"))
            settle()
            val expected = if (kind == "POST") listOf(7_000L, 0L, 0L) else listOf(0L, 7_000L, 0L)
            assertEquals("$kind: the late answer's floor", expected, floors())
            assertEquals("$kind: the reset list stays", null, rows)
            assertEquals("$kind: the handle stays", settled, h.outcome.value)
        }
    }

    // ======== G10: an edit answer must be this row's ========

    @Test fun `S7E5B1_GAP10 an edit answer for another row or another user is FAILED, unreflected and owed`() = runTest {
        for (wrong in listOf(server(2).copy(threshold = 1500.0), server(1).copy(userId = "user-b", threshold = 1500.0))) rig(this) {
            loaded(setting(1), setting(2))
            val h = edit(1, BankAlertEdit(threshold = 1500.0))
            answer("EDIT", okRow(wrong))
            assertTrue("$wrong: FAILED", failed(h.outcome.value))
            assertEquals("$wrong: no row changed", listOf(setting(1), setting(2)), rows)
            settle()
            assertEquals("$wrong: owed", 1, open("GET").size)
            assertEquals("$wrong: not resent", 1, count("EDIT"))
        }
    }

    // ======== G13: enabled intents ========

    @Test fun `S7E5B1_GAP13 an edit's enabled value is not shown before its answer`() = runTest {
        rig(this) {
            loaded(setting(1, enabled = false), setting(2))
            holdFloor(editFloor, "5")
            edit(1, BankAlertEdit(isEnabled = true))
            assertEquals("not optimistic", listOf(setting(1, enabled = false), setting(2)), rows)
            advance(5_000)
            assertEquals("the edit alone", listOf(1, 0), listOf(count("EDIT"), count("PUT")))
            assertEquals("still not shown", listOf(setting(1, enabled = false), setting(2)), rows)
            answer("EDIT", okRow(server(1, enabled = true)))
            assertEquals(listOf(server(1, enabled = true), setting(2)), rows)
        }
    }

    @Test fun `S7E5B1_GAP13 replacing a waiting enabling edit keeps a toggle accepted before it`() = runTest {
        rig(this) {
            loaded(setting(1, enabled = true))
            holdFloor(editFloor, "5")
            toggle(1, false)
            val a = edit(1, BankAlertEdit(isEnabled = true))
            val b = edit(1, BankAlertEdit(threshold = 1500.0))
            assertEquals(notSent(NotSentReason.SUPERSEDED), a.outcome.value)
            advance(5_000)
            assertEquals("only b, whole", listOf(mapOf("threshold" to "1500.0")),
                sent("EDIT").map { json(it.payload).mapValues { e -> e.value.toString() } })
            assertEquals(0, count("PUT"))
            val edited = server(1, enabled = true).copy(threshold = 1500.0)
            answer("EDIT", okRow(edited))
            assertEquals(BankAlertSaveOutcome.Saved(edited), b.outcome.value)
            assertEquals("the separate toggle still goes", listOf(false), sent("PUT").map { it.payload })
            answer("PUT", okRow(edited.copy(isEnabled = false)))
            advance(60_000)
            assertEquals("once each", listOf(1, 1), listOf(count("EDIT"), count("PUT")))
            assertEquals(listOf(edited.copy(isEnabled = false)), rows)
        }
    }

    @Test fun `S7E5B1_GAP13 a toggle accepted after an enabling edit was sent goes after its answer, shown over the server row`() = runTest {
        rig(this) {
            loaded(setting(1, enabled = false), setting(2))
            val h = edit(1, BankAlertEdit(isEnabled = true, threshold = 1500.0))
            toggle(1, false)
            val edited = server(1, enabled = true).copy(threshold = 1500.0)
            answer("EDIT", okRow(edited))
            assertEquals(BankAlertSaveOutcome.Saved(edited), h.outcome.value)
            assertEquals("the later toggle goes", listOf(false), sent("PUT").map { it.payload })
            assertEquals("meanwhile shown over the edit's server row", listOf(edited.copy(isEnabled = false), setting(2)), rows)
            val toggled = server(1, enabled = false).copy(threshold = 1500.0)
            answer("PUT", okRow(toggled))
            assertEquals(listOf(toggled, setting(2)), rows)
            advance(60_000)
            assertEquals(listOf(1, 1), listOf(count("EDIT"), count("PUT")))
        }
    }

    @Test fun `S7E5B1_GAP13 a toggle behind a waiting enabling edit goes after it and only if it still differs`() = runTest {
        rig(this) {
            loaded(setting(1, enabled = false))
            holdFloor(editFloor, "5")
            edit(1, BankAlertEdit(isEnabled = true))
            toggle(1, false)
            assertEquals("nothing ahead of the edit", listOf(0, 0), listOf(count("EDIT"), count("PUT")))
            advance(5_000)
            assertEquals("the edit first", listOf(1, 0), listOf(count("EDIT"), count("PUT")))
            answer("EDIT", okRow(server(1, enabled = false)))
            advance(60_000)
            assertEquals("a toggle equal to the approved row is not sent", 0, count("PUT"))
        }
    }

    // ======== G15: an edit 404 ========

    @Test fun `S7E5B1_GAP15 an edit 404 settles the waiting edit NOT_READY and sends no queued toggle`() = runTest {
        rig(this) {
            loaded(setting(1), setting(2))
            val a = edit(1, BankAlertEdit(threshold = 1.0))
            val b = edit(1, BankAlertEdit(threshold = 2.0))
            toggle(1, false)
            answer("EDIT", http(404))
            assertEquals(BankAlertSaveOutcome.Removed, a.outcome.value)
            assertEquals(notSent(NotSentReason.NOT_READY), b.outcome.value)
            assertEquals(listOf(setting(2)), rows)
            settle()
            assertEquals("owed", 1, open("GET").size)
            advance(60_000)
            foreground()
            assertEquals("nothing replayed", listOf(1, 0), listOf(count("EDIT"), count("PUT")))
        }
    }

    @Test fun `S7E5B1_GAP15 an edit 404 ends a delete queued behind it without sending it`() = runTest {
        rig(this) {
            loaded(setting(1), setting(2))
            val a = edit(1, BankAlertEdit(threshold = 1.0))
            delete(1)
            answer("EDIT", http(404))
            assertEquals(BankAlertSaveOutcome.Removed, a.outcome.value)
            assertEquals("still hidden", listOf(setting(2)), rows)
            settle()
            assertEquals("owed", 1, open("GET").size)
            advance(60_000)
            assertEquals("the row is gone, so is the delete", 0, count("DELETE"))
        }
    }

    // ======== G16: pre-wire refusal ========

    @Test fun `S7E5B1_GAP16 an edit refused before the wire under a valid use is UNADMITTED, unreflected and owes nothing`() = runTest {
        rig(this) {
            loaded()
            val h = edit(1, BankAlertEdit(threshold = 1500.0))
            answer("EDIT", Outcome.Throw(TopicUseWithheldException(emptyList())))
            assertEquals(notSent(NotSentReason.UNADMITTED), h.outcome.value)
            assertEquals(listOf(setting(1), setting(2)), rows)
            settle()
            assertEquals("no GET starts", 0, open("GET").size)
            nothingOwed("a refused edit")
            advance(60_000)
            assertEquals("not resent", 1, count("EDIT"))
        }
    }

    @Test fun `S7E5B1_GAP16 a create or edit refused before the wire keeps a GET debt that was already owed`() = runTest {
        for (kind in saves) rig(this) {
            loaded()
            val h = save(kind)
            owner.onBankPush(null, null)
            settle()
            assertEquals("$kind: premise, the owed GET waits for the request", 0, open("GET").size)
            background()
            answer(kind, Outcome.Throw(TopicUseWithheldException(emptyList())))
            assertEquals(kind, notSent(NotSentReason.UNADMITTED), h.outcome.value)
            foreground(false)
            assertEquals("$kind: the earlier debt still sends a GET", 1, open("GET").size)
            answer("GET", okList(setting(1), setting(2)))
            nothingOwed("$kind: paid by the accepted GET")
        }
    }

    // ======== G17: an unknown edit result ========

    @Test fun `S7E5B1_GAP17 an unknown edit result is FAILED and neither rolls back nor removes the shown row`() = runTest {
        val replies = listOf(
            Outcome.Throw(IOException("lost")),
            decodeFailure(),
            Outcome.Throw(AuthIdentityChangedException()),
            bound({ throw AuthIdentityChangedException() }, Result.success(server(1)), AlertRateLimitResponseEvidence(200, null)),
            Outcome.Throw(TopicUseWithheldException(listOf(HttpExchangeEvidence(1, 1, 503, null))))
        )
        for ((i, reply) in replies.withIndex()) rig(this) {
            loaded(setting(1), setting(2))
            val h = edit(1, BankAlertEdit(threshold = 1500.0))
            toggle(1, false)
            assertEquals("premise: the later toggle is shown", listOf(setting(1, enabled = false), setting(2)), rows)
            answer("EDIT", reply)
            assertTrue("#$i: FAILED", failed(h.outcome.value))
            assertEquals("#$i: still shown as it was", listOf(setting(1, enabled = false), setting(2)), rows)
            settle()
            assertEquals("#$i: owed", 1, open("GET").size)
            advance(60_000)
            assertEquals("#$i: nothing resent", listOf(1, 0), listOf(count("EDIT"), count("PUT")))
        }
    }

    // ======== G18: a delete behind a failed edit ========

    @Test fun `S7E5B1_GAP18 a delete accepted behind a failed, unknown or refused edit goes once and the row stays hidden`() = runTest {
        val isFailed: (BankAlertSaveOutcome) -> Boolean = { it is BankAlertSaveOutcome.Failed }
        val isUnadmitted: (BankAlertSaveOutcome) -> Boolean = { it == notSent(NotSentReason.UNADMITTED) }
        val cases = listOf(
            http(500) to isFailed,
            Outcome.Throw(IOException("lost")) to isFailed,
            Outcome.Throw(TopicUseWithheldException(emptyList())) to isUnadmitted
        )
        for ((i, case) in cases.withIndex()) rig(this) {
            loaded(setting(1), setting(2))
            val h = edit(1, BankAlertEdit(threshold = 1500.0))
            delete(1)
            assertEquals(listOf(setting(2)), rows)
            answer("EDIT", case.first)
            assertTrue("#$i: the edit's own result, ${h.outcome.value}", case.second(h.outcome.value))
            assertEquals("#$i: still hidden", listOf(setting(2)), rows)
            assertEquals("#$i: then the delete", 1, open("DELETE").size)
            answer("DELETE", okDelete())
            assertEquals(listOf(setting(2)), rows)
            advance(60_000)
            assertEquals("#$i: once each", listOf(1, 1), listOf(count("EDIT"), count("DELETE")))
        }
    }

    @Test fun `S7E5B1_GAP18 a delete behind a failed edit does not follow it after a background or a lost admission`() = runTest {
        for (lose in listOf("background", "admission")) rig(this) {
            loaded(setting(1), setting(2))
            edit(1, BankAlertEdit(threshold = 1500.0))
            delete(1)
            if (lose == "background") background() else snapshot = snapshot.copy(userInvalidations = 1)
            answer("EDIT", http(500))
            advance(60_000)
            assertEquals("$lose: no delete", 0, count("DELETE"))
            assertEquals("$lose: still hidden", listOf(setting(2)), rows)
        }
    }

    // ======== G19: background ========

    @Test fun `S7E5B1_GAP19 an edit waiting at the background is BACKGROUND, never sent and owes a GET inside the freshness window`() = runTest {
        rig(this) {
            loaded()
            holdFloor(editFloor, "5")
            val h = edit(1, BankAlertEdit(threshold = 1500.0))
            background()
            assertEquals(notSent(NotSentReason.BACKGROUND), h.outcome.value)
            advance(10_000)
            assertEquals(0, count("EDIT"))
            foreground(false)
            assertEquals("owed inside the 30 s skip", 1, open("GET").size)
        }
    }

    @Test fun `S7E5B1_GAP19 an edit in flight at the background is not cancelled, lands, and nothing behind it is sent`() = runTest {
        for (behind in listOf("toggle", "delete")) rig(this) {
            loaded(setting(1), setting(2))
            val a = edit(1, BankAlertEdit(threshold = 1500.0))
            val b = edit(1, BankAlertEdit(threshold = 1600.0))
            if (behind == "toggle") toggle(1, false) else delete(1)
            background()
            val call = sent("EDIT").single()
            assertTrue("$behind: not cancelled by the background", call.open)
            val answered = server(1).copy(threshold = 1500.0)
            answer("EDIT", okRow(answered))
            assertEquals(behind, BankAlertSaveOutcome.Saved(answered), a.outcome.value)
            if (behind == "toggle") {
                assertEquals("the waiting edit", notSent(NotSentReason.BACKGROUND), b.outcome.value)
                assertEquals("the later toggle shown over the answer", listOf(answered.copy(isEnabled = false), setting(2)), rows)
            } else {
                assertEquals("the delete ended it first", notSent(NotSentReason.SUPERSEDED), b.outcome.value)
                assertEquals("still hidden", listOf(setting(2)), rows)
            }
            advance(60_000)
            assertEquals("$behind: nothing more", listOf(1, 0, 0), listOf(count("EDIT"), count("PUT"), count("DELETE")))
            foreground(false)
            assertEquals("$behind: owed", 1, open("GET").size)
        }
    }

    // ======== G20: reset ========

    @Test fun `S7E5B1_GAP20 a reset settles a create or edit on the spot, before its worker ever runs`() = runTest {
        for (kind in saves) rig(this) {
            loaded()
            val h = saveUnsettled(kind)
            assertEquals("$kind: premise", BankAlertSaveOutcome.Pending, h.outcome.value)
            owner.reset()
            assertEquals("$kind: settled by the reset itself", notSent(NotSentReason.RESET), h.outcome.value)
            settle()
            assertEquals(kind, notSent(NotSentReason.RESET), h.outcome.value)
            assertEquals("$kind: never sent", 0, count(kind))
        }
        for (kind in saves) rig(this) {
            loaded()
            val h = save(kind)
            assertEquals(1, open(kind).size)
            owner.reset()
            assertTrue("$kind: entered, so FAILED by the reset itself", failed(h.outcome.value))
            val settled = h.outcome.value
            settle()
            assertEquals(kind, settled, h.outcome.value)
        }
    }

    @Test fun `S7E5B1_GAP20 a late create answer of a reset identity leaves the new identity's list and cap alone`() = runTest {
        rig(this) {
            ignoresCancellation = true
            loaded(*rowsOf(28))
            val old = create()
            val post = open("POST").single()
            switchToAlice2()
            settle()
            assertTrue("FAILED by the reset", failed(old.outcome.value))
            val settled = old.outcome.value
            answer("GET", okList(*rowsOf(29)))
            assertEquals(alice2, owner.state.value.identity)
            post.outcome.complete(okRow(server(30)))
            settle()
            assertEquals("the old handle stays", settled, old.outcome.value)
            assertEquals("the new list stays", rowsOf(29).toList(), rows)
            val fresh = create()
            assertEquals("the new identity's last slot is still free", BankAlertSaveOutcome.Pending, fresh.outcome.value)
            assertEquals(alice2, sent("POST").last().identity)
        }
    }

    // ======== G24: transport cancellation ========

    @Test fun `S7E5B1_GAP24 a transport cancellation of a create or edit is FAILED, owed, sets no floor and is not resent`() = runTest {
        for (kind in saves) rig(this) {
            loaded()
            val h = save(kind)
            answer(kind, Outcome.Throw(CancellationException("transport cancelled")))
            assertTrue("$kind: FAILED", failed(h.outcome.value))
            assertEquals("$kind: no floor", listOf(0L, 0L, 0L), floors())
            assertEquals("$kind: nothing reflected", listOf(setting(1), setting(2)), rows)
            settle()
            assertEquals("$kind: owed", 1, open("GET").size)
            advance(60_000)
            assertEquals("$kind: not resent", 1, count(kind))
        }
    }

    @Test fun `S7E5B1_GAP24 a cancelled edit settles the waiting edit behind it SUPERSEDED and nothing follows`() = runTest {
        rig(this) {
            loaded()
            val a = edit(1, BankAlertEdit(threshold = 1.0))
            val b = edit(1, BankAlertEdit(threshold = 2.0))
            answer("EDIT", Outcome.Throw(CancellationException("transport cancelled")))
            assertTrue(failed(a.outcome.value))
            assertEquals(notSent(NotSentReason.SUPERSEDED), b.outcome.value)
            advance(60_000)
            assertEquals(listOf(1, 0), listOf(count("EDIT"), count("PUT")))
        }
    }

    @Test fun `S7E5B1_GAP24 a cancelled save's handle shows one terminal value, collected before or after`() = runTest {
        for (kind in saves) rig(this) {
            loaded()
            val h = save(kind)
            val seen = mutableListOf<BankAlertSaveOutcome>()
            val early = CoroutineScope(UnconfinedTestDispatcher(test.testScheduler)).launch { h.outcome.collect { seen += it } }
            answer(kind, Outcome.Throw(CancellationException("transport cancelled")))
            val terminal = h.outcome.value
            assertTrue("$kind: FAILED", failed(terminal))
            owner.reset()
            settle()
            foreground()
            assertEquals("$kind: Pending, then one terminal", listOf(BankAlertSaveOutcome.Pending, terminal), seen)
            early.cancel()
            val later = mutableListOf<BankAlertSaveOutcome>()
            val late = CoroutineScope(UnconfinedTestDispatcher(test.testScheduler)).launch { h.outcome.collect { later += it } }
            assertEquals("$kind: a later collector sees the terminal value", listOf(terminal), later)
            late.cancel()
        }
    }
}
