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
import com.jay.fxi.data.repository.AlertRateLimitResponseEvidence
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.mapAlertHttpFailure
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
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
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 E5c contract (R4c/S7/s7e5 exact proposal r3 §4, §7 + user decision errata r1): the bank owner's push/tap entry.
 * - onBankPush(id, isRepeat) marks the row sent (isEnabled=false, triggered=true) only for exactly "false", an id, a loaded
 *   snapshot, a fresh acquired and admitted use of that snapshot's identity, the row present and no row operation on it.
 *   Anything else changes nothing locally, and nothing is kept to apply later (§4).
 * - Either way the push asks for the settings once: in the foreground through authoritative (a GET in fetch gets one trailing GET,
 *   never a parallel one; a body that raced the once mark is discarded), in the background as the owner's reconcile debt (§7).
 * - The debt is paid by one accepted GET on the next foreground, past the 30 s ensure skip, whichever onForeground flag; several
 *   pushes are one debt; an abandoned or failed GET keeps it; reset forgets it; no snapshot leaves nothing to owe (§4, §7).
 * The rig is the S7E5A2 owner rig: every call waits for the test's answer; access, use authority, deletion store and floor are the
 * production ones on virtual time. The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class S7E5COwnerPushContractTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val alice2 = AuthIdentityFence("user-a", 2)
    private val grantA = TopicGrantToken(11)
    private val grantA2 = TopicGrantToken(12)
    private val stamp = Instant.parse("2026-11-12T01:00:00Z")
    private val later = Instant.parse("2026-11-12T02:00:00Z")

    private fun setting(id: Int, enabled: Boolean = true, triggered: Boolean = false) =
        AlertSetting(id, "user-a", "hana", "usd-krw", AlertCondition.ABOVE, 1400.0 + id, enabled, triggered, stamp, stamp, null, null)

    /** The once mark: the row as the server will hold it after a once delivery. */
    private fun sent(id: Int) = setting(id).copy(isEnabled = false, triggered = true)

    /** The server's own row after a toggle: a later updatedAt marks that the whole row came from the answer. */
    private fun server(id: Int, enabled: Boolean, triggered: Boolean = false) = setting(id, enabled, triggered).copy(updatedAt = later)

    private fun confirmed(id: AuthIdentityFence) = OwnedPremiumAccess(id.uid, id.authGeneration, PremiumAccessState.PremiumConfirmed)
    private fun fenceFor(id: AuthIdentityFence, grant: TopicGrantToken) = TopicSessionFence(id, "epoch-1", grant)
    private fun standing(grant: TopicGrantToken) = TopicAccessSnapshot.INITIAL.copy(
        revision = 1L,
        facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet())
    )

    // ---- scripted calls (the S7E5A2 rig) ----

    private sealed interface Outcome {
        class Answer(val result: AuthBoundResult<*>) : Outcome
        class Throw(val error: Throwable) : Outcome
    }

    private class Call(val kind: String, val identity: AuthIdentityFence, val id: Int?, val enabled: Boolean?, val admitted: () -> Boolean) {
        val outcome = CompletableDeferred<Outcome>()
        var cancelled = false
        val open: Boolean get() = !outcome.isCompleted && !cancelled
    }

    private fun okList(vararg rows: AlertSetting): Outcome = Outcome.Answer(AuthBoundResult<List<AlertSetting>>({}, Result.success(rows.toList())))
    private fun okRow(row: AlertSetting): Outcome =
        Outcome.Answer(AuthBoundResult({}, Result.success(row), AlertRateLimitResponseEvidence(200, null)))

    private fun http(status: Int): Outcome {
        val kind = if (status == 404) AuthenticatedFailureKind.UNKNOWN_NOT_FOUND else AuthenticatedFailureKind.OTHER_HTTP
        val failure = AuthenticatedHttpFailure(status, headersOf(), ByteArray(0), null, null, kind)
        return Outcome.Answer(AuthBoundResult<Any>({}, Result.failure(mapAlertHttpFailure(failure)),
            AlertRateLimitResponseEvidence(status, null)))
    }

    // ---- the rig ----

    /** [eager]: the owner's scope runs on an unconfined test dispatcher, so a launched chain reaches its fetch inside the call. */
    private inner class Rig(val test: TestScope, eager: Boolean = false) {
        var identity: AuthIdentityFence? = alice
        var fence: TopicSessionFence? = fenceFor(alice, grantA)
        var snapshot: TopicAccessSnapshot = standing(grantA)
        var release = true
        /** Release answers read before [release]: one per admission check, to split an acquire from the admits after it. */
        val releaseAnswers = ArrayDeque<Boolean>()
        val deletions = DeletionAdmissionStore()
        val premium = MutableStateFlow(confirmed(alice))
        val access = AlertSettingsAccess(
            { identity }, { fence }, SnapshotTopicUseAuthority { snapshot }, { premium.value },
            { releaseAnswers.removeFirstOrNull() ?: release },
            GraphProtectedAdmission({ identity }, deletions)
        )
        val floor = AlertRateLimitFloor({ test.testScheduler.currentTime })
        val calls = mutableListOf<Call>()

        private suspend fun <T> scripted(call: Call): T {
            calls += call
            val outcome = try {
                call.outcome.await()
            } catch (e: CancellationException) {
                call.cancelled = true
                throw e
            }
            @Suppress("UNCHECKED_CAST")
            return when (outcome) {
                is Outcome.Answer -> outcome.result as T
                is Outcome.Throw -> throw outcome.error
            }
        }

        private val job = SupervisorJob()
        val owner = BankAlertSettingsOwner(
            { who, admitted -> scripted(Call("GET", who, null, null, admitted)) },
            access, floor, AlertRetryPolicy(), { test.testScheduler.currentTime }, { 0.0 },
            CoroutineScope(job + if (eager) UnconfinedTestDispatcher(test.testScheduler) else StandardTestDispatcher(test.testScheduler)),
            { delay(it) },
            premium, MutableStateFlow(0L), MutableStateFlow(false),
            { who, id, enabled, admitted -> scripted(Call("PUT", who, id, enabled, admitted)) },
            { who, id, admitted -> scripted(Call("DELETE", who, id, null, admitted)) },
            RECHECK
        )

        val rows: List<AlertSetting>? get() = owner.state.value.settings
        fun settle() = test.runCurrent()
        fun advance(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }
        fun open(kind: String): List<Call> = calls.filter { it.kind == kind && it.open }
        fun count(kind: String): Int = calls.count { it.kind == kind }
        /** Total GETs so far and those still awaiting an answer. */
        fun gets(): List<Int> = listOf(count("GET"), open("GET").size)
        fun answer(kind: String, outcome: Outcome) {
            val pending = open(kind)
            check(pending.size == 1) { "exactly one $kind awaits its answer, not ${pending.size}" }
            pending.single().outcome.complete(outcome)
            settle()
        }
        /** Answer the one open [kind] call for row [id] while other rows' calls stay open. */
        fun answerRow(kind: String, id: Int, outcome: Outcome) {
            val pending = open(kind).filter { it.id == id }
            check(pending.size == 1) { "exactly one $kind for row $id awaits its answer, not ${pending.size}" }
            pending.single().outcome.complete(outcome)
            settle()
        }
        /** Foreground, one GET answered with [loaded]: the snapshot a test starts from. */
        fun loaded(vararg loaded: AlertSetting = arrayOf(setting(1), setting(2))) {
            owner.onForeground(false)
            settle()
            answer("GET", okList(*loaded))
            check(owner.state.value.phase == AlertLoadPhase.Ready && open("GET").isEmpty())
        }
        fun background() {
            owner.onBackground()
            settle()
        }
        fun foreground(authoritative: Boolean = false) {
            owner.onForeground(authoritative)
            settle()
        }
        /** A new session of the same user that the access already serves; the owner hears of it at the next settle. */
        fun switchToAlice2() {
            identity = alice2
            fence = fenceFor(alice2, grantA2)
            snapshot = standing(grantA2)
            premium.value = confirmed(alice2)
        }
        fun toggle(id: Int, enabled: Boolean) {
            owner.setEnabled(id, enabled)
            settle()
        }
        fun delete(id: Int) {
            owner.delete(id)
            settle()
        }
        fun close() = job.cancel()
    }

    private fun rig(test: TestScope, eager: Boolean = false, body: Rig.() -> Unit) {
        val r = Rig(test, eager)
        try {
            r.body()
        } finally {
            r.close()
        }
    }

    // ---- once ----

    @Test fun `S7E5C_B01 a once push marks only its own loaded row sent in place and asks for the settings once`() = runTest {
        rig(this) {
            loaded(setting(1), setting(2), setting(3))
            owner.onBankPush(2, "false")
            assertEquals("row 2 off and triggered, the others untouched, the order kept",
                listOf(setting(1), sent(2), setting(3)), rows)
            assertEquals("the snapshot's identity and phase are kept", listOf<Any?>(alice, AlertLoadPhase.Ready),
                listOf(owner.state.value.identity, owner.state.value.phase))
            settle()
            assertEquals("one GET from the push", listOf(2, 1), gets())
            answer("GET", okList(setting(1), setting(2), setting(3)))
            assertEquals("P0: the push's own GET started after the mark and is accepted as the server answers",
                listOf(setting(1), setting(2), setting(3)), rows)
            advance(60_000)
            assertEquals("and nothing more", listOf(2, 0), gets())
        }
    }

    // ---- no local change, still one GET ----

    @Test fun `S7E5C_B02 an is_repeat other than exactly false changes nothing and still asks for the settings once`() = runTest {
        for (value in listOf("true", null, "", "False", "FALSE", "0")) rig(this) {
            loaded()
            val before = owner.state.value
            owner.onBankPush(1, value)
            assertEquals("is_repeat=$value: no local change", before, owner.state.value)
            settle()
            assertEquals("is_repeat=$value: one GET", listOf(2, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            advance(60_000)
            assertEquals("is_repeat=$value: never two", listOf(2, 0), gets())
        }
    }

    @Test fun `S7E5C_B03 no id or the id of an absent row changes nothing and still asks for the settings once`() = runTest {
        for ((id, value) in listOf<Pair<Int?, String?>>(null to "false", null to null, 99 to "false")) rig(this) {
            loaded()
            val before = owner.state.value
            owner.onBankPush(id, value)
            assertEquals("id=$id is_repeat=$value: no local change", before, owner.state.value)
            settle()
            assertEquals("id=$id is_repeat=$value: one GET", listOf(2, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            advance(60_000)
            assertEquals("id=$id is_repeat=$value: never two", listOf(2, 0), gets())
        }
    }

    @Test fun `S7E5C_B04 a row operation in flight keeps its row from the once mark now and later and the GET waits for it`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            val shown = rows
            owner.onBankPush(1, "false")
            assertEquals("the toggle's display is kept, not marked", shown, rows)
            settle()
            assertEquals("no GET while the op runs", listOf(1, 0), gets())
            answer("PUT", okRow(server(1, false)))
            assertEquals("the op's answer, and the push is not applied after it", listOf(server(1, false), setting(2)), rows)
            assertEquals("then the push's one GET", listOf(2, 1), gets())
            answer("GET", okList(server(1, false), setting(2)))
            advance(60_000)
            assertEquals("never two", listOf(2, 0), gets())
        }
        rig(this) {
            loaded()
            delete(1)
            owner.onBankPush(1, "false")
            assertEquals("the removal is kept", listOf(setting(2)), rows)
            settle()
            assertEquals("no GET while the op runs", listOf(1, 0), gets())
            answer("DELETE", http(500))
            assertEquals("the refused delete restores the approved row, unmarked", listOf(setting(1), setting(2)), rows)
            assertEquals("then one GET for both the push and the op", listOf(2, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            advance(60_000)
            assertEquals("never two", listOf(2, 0), gets())
        }
    }

    @Test fun `S7E5C_B05 a refused acquire changes nothing and the one GET goes when access returns`() = runTest {
        rig(this) {
            loaded()
            release = false
            val before = owner.state.value
            owner.onBankPush(1, "false")
            assertEquals("no use: no local change", before, owner.state.value)
            settle()
            advance(5_000)
            assertEquals("no GET while access is refused", listOf(1, 0), gets())
            assertEquals("still unchanged", before, owner.state.value)
            release = true
            advance(RECHECK)
            assertEquals("the push's one GET", listOf(2, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            advance(60_000)
            assertEquals("never two", listOf(2, 0), gets())
        }
    }

    @Test fun `S7E5C_B06 a fresh use of another identity than the snapshot's changes nothing and the settings are asked once`() = runTest {
        rig(this) {
            loaded()
            val before = owner.state.value
            switchToAlice2()
            assertEquals("premise: a fresh admitted use exists, for alice2", alice2, access.acquire()?.identity)
            owner.onBankPush(1, "false")
            assertEquals("the snapshot is alice's: no local change", before, owner.state.value)
            settle()
            assertEquals("one GET, as the new identity", listOf<Any?>(2, 1, alice2),
                gets() + open("GET").single().identity)
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("the new identity's list as answered", listOf<Any?>(listOf(setting(1), setting(2)), alice2),
                listOf(rows, owner.state.value.identity))
            advance(60_000)
            assertEquals("never two", listOf(2, 0), gets())
        }
    }

    @Test fun `S7E5C_B07 a use acquired but no longer admitted at the check changes nothing and still asks once`() = runTest {
        rig(this) {
            loaded()
            val before = owner.state.value
            // The acquire's own admission passes, the explicit admits right after it does not.
            releaseAnswers.addAll(listOf(true, false))
            owner.onBankPush(1, "false")
            assertEquals("not admitted: no local change", before, owner.state.value)
            assertTrue("premise: both admissions were read", releaseAnswers.isEmpty())
            settle()
            assertEquals("one GET", listOf(2, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            advance(60_000)
            assertEquals("never two", listOf(2, 0), gets())
        }
    }

    @Test fun `S7E5C_B08 without a snapshot yet nothing is marked now or on the later list and the push asks once`() = runTest {
        rig(this) {
            owner.onForeground(false)
            settle()
            assertEquals("premise: the first GET is in fetch", listOf(1, 1), gets())
            owner.onBankPush(1, "false")
            assertEquals("no snapshot: nothing to mark", null, rows)
            settle()
            assertEquals("joined: no parallel GET", listOf(1, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("the first list is the server's, not marked", listOf(setting(1), setting(2)), rows)
            assertEquals("the push's one trailing GET", listOf(2, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            advance(60_000)
            assertEquals("never two", listOf(2, 0), gets())
        }
        rig(this) {
            owner.onForeground(false)
            settle()
            answer("GET", http(403))
            assertEquals("premise: no list and no GET", listOf<Any?>(null, 1, 0), listOf(rows) + gets())
            owner.onBankPush(1, "false")
            assertEquals("no snapshot: nothing to mark", null, rows)
            settle()
            assertEquals("the push's one GET", listOf(2, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("the list is the server's, not marked", listOf(setting(1), setting(2)), rows)
            advance(60_000)
            assertEquals("never two", listOf(2, 0), gets())
        }
    }

    // ---- a GET already in fetch ----

    @Test fun `S7E5C_B09 a GET in fetch when a once push arrives is discarded and the trailing GET's answer is accepted`() = runTest {
        rig(this) {
            loaded()
            owner.authoritative()
            settle()
            assertEquals("premise: a GET in fetch", listOf(2, 1), gets())
            owner.onBankPush(1, "false")
            assertEquals("marked at once", listOf(sent(1), setting(2)), rows)
            settle()
            assertEquals("no parallel GET", listOf(2, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("the older body still showing the row enabled is discarded: the mark stays", listOf(sent(1), setting(2)), rows)
            assertEquals("one trailing GET", listOf(3, 1), gets())
            answer("GET", okList(sent(1), setting(2), setting(3)))
            assertEquals("the trailing answer is accepted", listOf(sent(1), setting(2), setting(3)), rows)
            advance(60_000)
            assertEquals("nothing more", listOf(3, 0), gets())
        }
    }

    @Test fun `S7E5C_B10 a foreground push while a GET is in fetch joins it with one trailing GET and no parallel GET`() = runTest {
        for ((id, value) in listOf<Pair<Int?, String?>>(null to null, 1 to "true")) rig(this) {
            loaded()
            owner.authoritative()
            settle()
            owner.onBankPush(id, value)
            settle()
            assertEquals("id=$id is_repeat=$value: still one GET in fetch", listOf(2, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("id=$id is_repeat=$value: that body is accepted", listOf(setting(1), setting(2)), rows)
            assertEquals("id=$id is_repeat=$value: one trailing GET for the push", listOf(3, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            advance(60_000)
            assertEquals("id=$id is_repeat=$value: nothing more", listOf(3, 0), gets())
        }
    }

    // ---- background ----

    @Test fun `S7E5C_B11 a background push sends nothing and the next foreground asks once inside the 30 s skip`() = runTest {
        rig(this) {
            loaded()
            background()
            owner.onBankPush(1, "true")
            settle()
            advance(5_000)
            assertEquals("no GET in the background", listOf(1, 0), gets())
            foreground()
            assertEquals("5 s after a success: one GET past the skip", listOf(2, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            advance(10_000)
            assertEquals("never two", listOf(2, 0), gets())
            background()
            foreground()
            assertEquals("the accepted GET paid the debt: the next foreground inside the skip asks nothing", listOf(2, 0), gets())
        }
    }

    @Test fun `S7E5C_B12 several background pushes are one debt paid by exactly one GET`() = runTest {
        rig(this) {
            loaded()
            background()
            owner.onBankPush(1, "false")
            owner.onBankPush(2, "true")
            owner.onBankPush(null, null)
            owner.onBankPush(99, "false")
            settle()
            advance(5_000)
            assertEquals("the once row is marked at once in the background too", listOf(sent(1), setting(2)), rows)
            assertEquals("no GET in the background", listOf(1, 0), gets())
            foreground()
            assertEquals("one GET for all four", listOf(2, 1), gets())
            answer("GET", okList(sent(1), setting(2)))
            advance(10_000)
            assertEquals("never two", listOf(2, 0), gets())
            background()
            foreground()
            assertEquals("nothing left owed", listOf(2, 0), gets())
        }
    }

    @Test fun `S7E5C_B13 a reset after a background push leaves only the reload's own GET and nothing owed after it`() = runTest {
        rig(this) {
            loaded()
            background()
            owner.onBankPush(1, "true")
            owner.reset()
            settle()
            advance(1_000)
            foreground()
            assertEquals("the reset forgot the list: one GET reloads it, none added for the debt", listOf(2, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            advance(10_000)
            assertEquals("never two", listOf(2, 0), gets())
            background()
            foreground()
            assertEquals("nothing owed inside the new skip", listOf(2, 0), gets())
        }
    }

    @Test fun `S7E5C_B14 an abandoned or failed GET keeps the debt and the following foreground asks again inside the skip`() = runTest {
        val scenes: List<Pair<String, Rig.() -> Unit>> = listOf(
            "back to the background before the answer" to {
                val get = open("GET").single()
                background()
                assertTrue("premise: the background abandoned the GET", get.cancelled)
            },
            "a failed answer, then the background" to {
                answer("GET", http(403))
                background()
            })
        for ((label, scene) in scenes) rig(this) {
            loaded()
            background()
            owner.onBankPush(null, null)
            advance(1_000)
            foreground()
            assertEquals("$label: premise, the debt's GET", listOf(2, 1), gets())
            scene()
            advance(1_000)
            foreground()
            assertEquals("$label: still owed 2 s after the success: one more GET", listOf(3, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            advance(10_000)
            assertEquals("$label: never two", listOf(3, 0), gets())
            background()
            foreground()
            assertEquals("$label: paid now", listOf(3, 0), gets())
        }
    }

    @Test fun `S7E5C_B15 an authoritative foreground with a debt asks exactly once and pays the debt`() = runTest {
        rig(this) {
            loaded()
            background()
            owner.onBankPush(1, "true")
            advance(1_000)
            foreground(authoritative = true)
            assertEquals("one GET for the flag and the debt together", listOf(2, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            advance(10_000)
            assertEquals("never two", listOf(2, 0), gets())
            background()
            foreground()
            assertEquals("the debt is paid: the skip holds", listOf(2, 0), gets())
        }
    }

    @Test fun `S7E5C_B16 a background push before any load leaves nothing and the foreground is a normal first load`() = runTest {
        rig(this) {
            owner.onBankPush(1, "false")
            owner.onBankPush(null, null)
            settle()
            advance(60_000)
            assertEquals("nothing in the background", listOf<Any?>(null, 0, 0), listOf(rows) + gets())
            foreground()
            assertEquals("one GET, as any first load", listOf(1, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("the server's list, the earlier once not applied to it", listOf(setting(1), setting(2)), rows)
            advance(1_000)
            background()
            foreground()
            assertEquals("nothing owed inside the skip", listOf(1, 0), gets())
        }
    }

    // ---- identity ----

    @Test fun `S7E5C_B17 a push the old identity could not apply is not applied to the new identity's rows after reset and reload`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            owner.onBankPush(1, "false")
            switchToAlice2()
            settle()
            assertEquals("premise: the reset dropped the list and the reload asks as alice2", listOf<Any?>(null, 2, 1, alice2),
                listOf(rows) + gets() + open("GET").single().identity)
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("held by a row op: alice2's rows as answered", listOf<Any?>(listOf(setting(1), setting(2)), alice2),
                listOf(rows, owner.state.value.identity))
            advance(60_000)
            assertEquals("held by a row op: nothing more", listOf(2, 0), gets())
        }
        rig(this) {
            loaded()
            background()
            release = false
            owner.onBankPush(1, "false")
            assertEquals("premise: not applied for alice", listOf(setting(1), setting(2)), rows)
            release = true
            switchToAlice2()
            settle()
            assertEquals("premise: the reset dropped the list", null, rows)
            foreground()
            assertEquals("the reload asks once as alice2", listOf<Any?>(2, 1, alice2), gets() + open("GET").single().identity)
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("refused access in the background: alice2's rows as answered",
                listOf<Any?>(listOf(setting(1), setting(2)), alice2), listOf(rows, owner.state.value.identity))
            advance(10_000)
            background()
            foreground()
            assertEquals("refused access in the background: nothing more, nothing owed", listOf(2, 0), gets())
        }
        rig(this) {
            loaded()
            switchToAlice2()
            settle()
            assertEquals("premise: alice2's reload is in fetch", listOf<Any?>(null, 2, 1), listOf(rows) + gets())
            owner.onBankPush(1, "false")
            settle()
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("between reset and reload: alice2's rows as answered",
                listOf<Any?>(listOf(setting(1), setting(2)), alice2), listOf(rows, owner.state.value.identity))
            assertEquals("between reset and reload: the push's one trailing GET", listOf(3, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("between reset and reload: still as answered", listOf(setting(1), setting(2)), rows)
            advance(60_000)
            assertEquals("between reset and reload: nothing more", listOf(3, 0), gets())
        }
    }

    // ---- gap hunt r1 (s7e5c_gaphunt.codex.r1.md §3): owner gaps ----

    @Test fun `S7E5C_G02_a foreground push's owed GET survives an abandon or a failure and the next foreground asks again inside the skip`() = runTest {
        val scenes: List<Pair<String, Rig.() -> Unit>> = listOf(
            "back to the background before the answer" to {
                val get = open("GET").single()
                background()
                assertTrue("premise: the background abandoned the GET", get.cancelled)
            },
            "a failed answer, then the background" to {
                answer("GET", http(403))
                background()
            })
        for ((id, value) in listOf<Pair<Int?, String?>>(null to null, 1 to "false")) for ((label, scene) in scenes) rig(this) {
            val tag = "id=$id is_repeat=$value, $label"
            loaded()
            advance(1_000)
            owner.onBankPush(id, value)
            settle()
            assertEquals("$tag: premise, the foreground push's own GET", listOf(2, 1), gets())
            scene()
            advance(1_000)
            foreground()
            assertEquals("$tag: still owed 2 s after the last success: one more GET", listOf(3, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            advance(10_000)
            assertEquals("$tag: never two", listOf(3, 0), gets())
            background()
            foreground()
            assertEquals("$tag: paid by the accepted GET, the skip holds", listOf(3, 0), gets())
        }
    }

    @Test fun `S7E5C_G03_another row's operation does not block the once mark and the one GET waits for every op`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            val toggled = setting(1, enabled = false)
            assertEquals("premise: row 1 shows the toggle", listOf(toggled, setting(2)), rows)
            owner.onBankPush(2, "false")
            assertEquals("row 2 is marked at once, row 1 keeps the toggle's display", listOf(toggled, sent(2)), rows)
            settle()
            assertEquals("no GET while the op runs", listOf(1, 0), gets())
            answer("PUT", okRow(server(1, false)))
            assertEquals("the op's answer on row 1, the mark kept on row 2", listOf(server(1, false), sent(2)), rows)
            assertEquals("then the push's one GET", listOf(2, 1), gets())
            answer("GET", okList(server(1, false), server(2, false, triggered = true)))
            assertEquals("the GET's answer is accepted", listOf(server(1, false), server(2, false, triggered = true)), rows)
            advance(60_000)
            assertEquals("never two", listOf(2, 0), gets())
        }
        rig(this) {
            loaded(setting(1), setting(2), setting(3))
            toggle(1, false)
            toggle(2, false)
            owner.onBankPush(3, "false")
            assertEquals("row 3 is marked at once beside two other rows' ops",
                listOf(setting(1, enabled = false), setting(2, enabled = false), sent(3)), rows)
            settle()
            assertEquals("premise: two PUTs open, no GET", listOf(2, 1, 0), listOf(open("PUT").size) + gets())
            answerRow("PUT", 1, okRow(server(1, false)))
            assertEquals("one op still runs: no GET", listOf(1, 0), gets())
            answerRow("PUT", 2, okRow(server(2, false)))
            assertEquals("the last op ended: the push's one GET", listOf(2, 1), gets())
            assertEquals("the mark is kept through both answers", listOf(server(1, false), server(2, false), sent(3)), rows)
            answer("GET", okList(server(1, false), server(2, false), sent(3)))
            advance(60_000)
            assertEquals("never two", listOf(2, 0), gets())
        }
    }

    @Test fun `S7E5C_G04_the raw is_repeat alone decides a once mark whatever the row's repeat interval says`() = runTest {
        for (interval in listOf(0, 60, 86_400)) rig(this) {
            val row = setting(1).copy(repeatIntervalSec = interval)
            loaded(row, setting(2))
            owner.onBankPush(1, "false")
            assertEquals("interval=$interval: raw false marks the row sent, the interval kept",
                listOf(row.copy(isEnabled = false, triggered = true), setting(2)), rows)
            settle()
            assertEquals("interval=$interval: one GET", listOf(2, 1), gets())
            answer("GET", okList(row, setting(2)))
            advance(60_000)
            assertEquals("interval=$interval: never two", listOf(2, 0), gets())
        }
        for (interval in listOf<Int?>(null, 0, 60)) for (value in listOf("true", null, "", "x")) rig(this) {
            val row = setting(1).copy(repeatIntervalSec = interval)
            loaded(row, setting(2))
            val before = owner.state.value
            owner.onBankPush(1, value)
            assertEquals("interval=$interval is_repeat=$value: the interval is never a fallback", before, owner.state.value)
            settle()
            assertEquals("interval=$interval is_repeat=$value: one GET", listOf(2, 1), gets())
            answer("GET", okList(row, setting(2)))
            advance(60_000)
            assertEquals("interval=$interval is_repeat=$value: never two", listOf(2, 0), gets())
        }
    }

    @Test fun `S7E5C_G05_a once mark copies the row changing only the two fields and leaves every earlier snapshot as it was`() = runTest {
        rig(this) {
            val original = setting(1).copy(triggeredAt = later, repeatIntervalSec = 60, updatedAt = later)
            val originalBefore = original.copy()
            val expected = original.copy(isEnabled = false, triggered = true)
            loaded(original, setting(2))
            val oldState = owner.state.value
            val oldRows = rows!!
            val oldRowsBefore = oldRows.map { it.copy() }
            val shown = oldRows[0]
            owner.onBankPush(1, "false")
            val marked = rows!!
            assertEquals("only isEnabled and triggered differ, every other field kept", listOf(expected, setting(2)), marked)
            assertTrue("the mark is a new row object", marked[0] !== shown)
            assertEquals("the shown row object is not changed in place", originalBefore, shown)
            assertEquals("the earlier list is not changed", oldRowsBefore, oldRows)
            assertEquals("the earlier state's rows are not changed", oldRowsBefore, oldState.settings)
            assertTrue("the state is a new value", owner.state.value !== oldState)
            settle()
            assertEquals("one GET", listOf(2, 1), gets())
            answer("GET", okList(originalBefore, setting(2)))
            assertEquals("P0: the GET's answer is accepted", listOf(originalBefore, setting(2)), rows)
            advance(60_000)
            assertEquals("never two", listOf(2, 0), gets())
        }
    }

    @Test fun `S7E5C_G06_a once push refused by access is not applied when access returns for the same identity`() = runTest {
        rig(this) {
            loaded()
            release = false
            val before = owner.state.value
            owner.onBankPush(1, "false")
            settle()
            advance(5_000)
            assertEquals("refused: no local change, no GET", listOf<Any?>(before, 1, 0), listOf<Any?>(owner.state.value) + gets())
            release = true
            advance(RECHECK)
            assertEquals("access returned: the push's one GET", listOf(2, 1), gets())
            assertEquals("before its answer the refused once is still not applied", before.settings, rows)
            answer("GET", okList(server(1, true), setting(2)))
            assertEquals("only the server's answer is shown", listOf(server(1, true), setting(2)), rows)
            advance(60_000)
            assertEquals("never two", listOf(2, 0), gets())
        }
        rig(this) {
            loaded()
            val before = owner.state.value
            // The acquire's own admission passes, the explicit admits right after it does not.
            releaseAnswers.addAll(listOf(true, false))
            owner.onBankPush(1, "false")
            assertEquals("not admitted: no local change", before, owner.state.value)
            assertTrue("premise: both admissions were read", releaseAnswers.isEmpty())
            settle()
            assertEquals("the push's one GET with a fresh admitted use", listOf(2, 1), gets())
            assertEquals("before its answer the refused once is still not applied", before.settings, rows)
            answer("GET", okList(server(1, true), setting(2)))
            assertEquals("only the server's answer is shown", listOf(server(1, true), setting(2)), rows)
            advance(60_000)
            assertEquals("never two", listOf(2, 0), gets())
        }
    }

    @Test fun `S7E5C_G07_a once push on a row already sent still discards a GET body that raced it`() = runTest {
        rig(this) {
            loaded(sent(1), setting(2))
            owner.authoritative()
            settle()
            assertEquals("premise: a GET in fetch", listOf(2, 1), gets())
            owner.onBankPush(1, "false")
            assertEquals("the row was already sent: the list looks the same", listOf(sent(1), setting(2)), rows)
            settle()
            assertEquals("no parallel GET", listOf(2, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("the older body still showing the row enabled is discarded", listOf(sent(1), setting(2)), rows)
            assertEquals("one trailing GET", listOf(3, 1), gets())
            answer("GET", okList(server(1, false, triggered = true), setting(2), setting(3)))
            assertEquals("the trailing answer is accepted", listOf(server(1, false, triggered = true), setting(2), setting(3)), rows)
            advance(60_000)
            assertEquals("nothing more", listOf(3, 0), gets())
        }
    }

    @Test fun `S7E5C_G08_a push that marks nothing keeps a GET in fetch valid and its new body is accepted`() = runTest {
        val pushes = listOf<Pair<Int?, String?>>(null to null, null to "false", 1 to "true", 1 to null, 1 to "x", 99 to "false")
        for ((id, value) in pushes) rig(this) {
            val tag = "id=$id is_repeat=$value"
            loaded()
            owner.authoritative()
            settle()
            assertEquals("$tag: premise, a GET in fetch", listOf(2, 1), gets())
            owner.onBankPush(id, value)
            settle()
            assertEquals("$tag: still one GET in fetch", listOf(2, 1), gets())
            answer("GET", okList(server(1, false), setting(2), setting(3)))
            assertEquals("$tag: that new body is accepted", listOf(server(1, false), setting(2), setting(3)), rows)
            assertEquals("$tag: one trailing GET for the push", listOf(3, 1), gets())
            answer("GET", okList(server(2, false), setting(3)))
            assertEquals("$tag: the trailing answer is accepted too", listOf(server(2, false), setting(3)), rows)
            advance(60_000)
            assertEquals("$tag: nothing more", listOf(3, 0), gets())
        }
    }

    // A successful row operation clearing the debt is already refused by S7E5A2_M37 (the same reconcileOwed field).
    @Test fun `S7E5C_G09_a GET body discarded for a racing once mark does not pay the bank debt`() = runTest {
        rig(this) {
            loaded()
            background()
            owner.onBankPush(null, null)
            advance(1_000)
            foreground()
            assertEquals("premise: the debt's GET", listOf(2, 1), gets())
            owner.onBankPush(1, "false")
            settle()
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("the raced body is discarded, the mark kept", listOf(sent(1), setting(2)), rows)
            assertEquals("the trailing GET", listOf(3, 1), gets())
            val trailing = open("GET").single()
            background()
            assertTrue("premise: the background abandoned the trailing GET", trailing.cancelled)
            advance(1_000)
            foreground()
            assertEquals("the discarded body paid nothing: 2 s after the last success one more GET", listOf(4, 1), gets())
            answer("GET", okList(sent(1), setting(2)))
            advance(10_000)
            background()
            foreground()
            assertEquals("paid by the accepted GET", listOf(4, 0), gets())
        }
    }

    @Test fun `S7E5C_G11_a bank push's GET waits for the GET floor and rechecks it`() = runTest {
        rig(this) {
            loaded()
            floor.record(AuthenticatedEndpoint.NOTIFICATION_SETTINGS, AlertRateLimitEvidence.of(emptyList(), 429, "10"))
            owner.onBankPush(1, "false")
            assertEquals("the once mark does not wait for the floor", listOf(sent(1), setting(2)), rows)
            settle()
            advance(9_999)
            assertEquals("no GET before the floor", listOf(1, 0), gets())
            advance(1)
            assertEquals("the push's one GET at the floor", listOf(2, 1), gets())
            answer("GET", okList(sent(1), setting(2)))
            advance(60_000)
            assertEquals("never two", listOf(2, 0), gets())
        }
        rig(this) {
            loaded()
            floor.record(AuthenticatedEndpoint.NOTIFICATION_SETTINGS, AlertRateLimitEvidence.of(emptyList(), 429, "10"))
            owner.onBankPush(null, null)
            settle()
            advance(5_000)
            floor.record(AuthenticatedEndpoint.NOTIFICATION_SETTINGS, AlertRateLimitEvidence.of(emptyList(), 429, "20"))
            advance(5_000)
            assertEquals("the floor extended while waiting holds at the old deadline", listOf(1, 0), gets())
            advance(14_999)
            assertEquals("and up to the new one", listOf(1, 0), gets())
            advance(1)
            assertEquals("the push's one GET at the new floor", listOf(2, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            advance(60_000)
            assertEquals("never two", listOf(2, 0), gets())
        }
        rig(this) {
            loaded()
            background()
            floor.record(AuthenticatedEndpoint.NOTIFICATION_SETTINGS, AlertRateLimitEvidence.of(emptyList(), 429, "10"))
            owner.onBankPush(null, null)
            advance(1_000)
            foreground()
            advance(8_999)
            assertEquals("the debt's GET waits for the floor", listOf(1, 0), gets())
            advance(1)
            assertEquals("the debt's one GET at the floor", listOf(2, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            advance(10_000)
            background()
            foreground()
            assertEquals("paid: nothing more", listOf(2, 0), gets())
        }
    }

    @Test fun `S7E5C_G12_a foreground push's GET keeps the automatic retry after a server error or a network failure`() = runTest {
        val failures = listOf<Pair<String, () -> Outcome>>("HTTP 500" to { http(500) }, "IOException" to { Outcome.Throw(IOException("offline")) })
        for ((id, value) in listOf<Pair<Int?, String?>>(null to null, 1 to "false")) for ((label, failure) in failures) rig(this) {
            val tag = "id=$id is_repeat=$value, $label"
            loaded()
            owner.onBankPush(id, value)
            settle()
            assertEquals("$tag: premise, the push's GET", listOf(2, 1), gets())
            answer("GET", failure())
            advance(1_999)
            assertEquals("$tag: the retry waits the first backoff", listOf(2, 0), gets())
            advance(1)
            assertEquals("$tag: then one automatic retry", listOf(3, 1), gets())
            answer("GET", okList(setting(1), setting(2)))
            advance(60_000)
            assertEquals("$tag: nothing more", listOf(3, 0), gets())
        }
    }

    @Test fun `S7E5C_G13_one foreground demand is one GET even when the owner's dispatcher runs eagerly`() = runTest {
        for (authoritative in listOf(false, true)) rig(this, eager = true) {
            val tag = "onForeground($authoritative) with a debt"
            loaded()
            background()
            owner.onBankPush(null, null)
            advance(1_000)
            owner.onForeground(authoritative)
            assertEquals("$tag: premise, the eager dispatcher put the GET in fetch inside the call", listOf(2, 1), gets())
            settle()
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("$tag: no trailing GET", listOf(2, 0), gets())
            advance(60_000)
            assertEquals("$tag: nothing more", listOf(2, 0), gets())
        }
        rig(this, eager = true) {
            loaded()
            advance(1_000)
            owner.onBankPush(null, null)
            assertEquals("a foreground push: premise, the GET in fetch inside the call", listOf(2, 1), gets())
            settle()
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("a foreground push: no trailing GET", listOf(2, 0), gets())
            advance(60_000)
            assertEquals("a foreground push: nothing more", listOf(2, 0), gets())
        }
    }

    private companion object {
        const val RECHECK = 1_000L
    }
}
