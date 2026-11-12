package com.jay.fxi.data.alerts

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.HttpExchangeEvidence
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
import com.jay.fxi.data.repository.AlertRateLimitResponseEvidence
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.mapAlertHttpFailure
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 E5a2 contract (R4c/S7/s7e5 API r3 §4 + scope amendment r1): the bank owner's row toggle and delete.
 * - Entry: no-op unless foreground, a loaded snapshot, the live identity of that snapshot, the row and an admitted access (§4.2).
 * - One op per row: a toggle merges into the latest target, a delete wins over it and is never revived by the toggle's result;
 *   another row is independent and a rollback touches only its own row (§4.3, §4.4 1-2, 6).
 * - Each send waits for its own endpoint floor (PUT = UPDATE_NOTIFICATION_SETTING, DELETE = DELETE_NOTIFICATION_SETTING), acquires a
 *   fresh use and passes only that use's admission (§4.4 3).
 * - A result records its evidence first, then needs reset epoch, op token, snapshot identity and the captured use; a late or
 *   revoked result changes nothing but, while the structure still holds, owes one GET (§4.4 4).
 * - Success applies the server row; HTTP 404 keeps a delete and removes a toggled row with a GET; another HTTP failure rolls the
 *   row back and owes a GET; IO, cancellation, identity loss and an undecodable 2xx keep the row and owe a GET; a pre-wire refusal
 *   under a still-admitted use rolls back; nothing is replayed (§4.4 5).
 * - A GET body that raced a local change is discarded without a success, and a trailing GET follows once rows settle; an owed GET
 *   ignores the 30 s freshness; background starts no send and keeps the obligation; reset cancels and forgets (§4.5).
 * The mutations are scripted like the GET: every call waits for the test's answer. The access, use authority, deletion store and
 * floor are the production ones on virtual time. The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class S7E5A2OwnerMutationContractTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val alice2 = AuthIdentityFence("user-a", 2)
    private val grantA = TopicGrantToken(11)
    private val grantA2 = TopicGrantToken(12)
    private val stamp = Instant.parse("2026-11-12T01:00:00Z")
    private val later = Instant.parse("2026-11-12T02:00:00Z")

    private fun setting(id: Int, enabled: Boolean = true, triggered: Boolean = false) =
        AlertSetting(id, "user-a", "hana", "usd-krw", AlertCondition.ABOVE, 1400.0 + id, enabled, triggered, stamp, stamp, null, null)

    /** The server's own row after a toggle: a later updatedAt marks that the whole row came from the answer. */
    private fun server(id: Int, enabled: Boolean, triggered: Boolean = false) = setting(id, enabled, triggered).copy(updatedAt = later)

    private fun confirmed(id: AuthIdentityFence) = OwnedPremiumAccess(id.uid, id.authGeneration, PremiumAccessState.PremiumConfirmed)
    private fun fenceFor(id: AuthIdentityFence, grant: TopicGrantToken) = TopicSessionFence(id, "epoch-1", grant)
    private fun standing(grant: TopicGrantToken, invalidations: Long = 0L) = TopicAccessSnapshot.INITIAL.copy(
        revision = 1L,
        facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet()),
        userInvalidations = invalidations
    )

    // ---- scripted calls ----

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
    private fun okRow(row: AlertSetting, status: Int = 200, retryAfter: String? = null, requireCurrent: () -> Unit = {}): Outcome =
        Outcome.Answer(AuthBoundResult(requireCurrent, Result.success(row), AlertRateLimitResponseEvidence(status, retryAfter)))

    /** A valid server row in which every field a toggle does not send differs from the fixture rows. */
    private fun rewritten(id: Int, enabled: Boolean) =
        AlertSetting(id, "user-a", "hana", "usd-krw", AlertCondition.BELOW, 1500.5 + id, enabled, true, stamp, later, later, 600)
    private fun okDelete(): Outcome = Outcome.Answer(AuthBoundResult({}, Result.success(Unit), AlertRateLimitResponseEvidence(204, null)))

    private fun http(status: Int, retryAfter: String? = null, requireCurrent: () -> Unit = {}): Outcome {
        val headers = if (retryAfter == null) headersOf() else headersOf("Retry-After", retryAfter)
        val kind = if (status == 404) AuthenticatedFailureKind.UNKNOWN_NOT_FOUND else AuthenticatedFailureKind.OTHER_HTTP
        val failure = AuthenticatedHttpFailure(status, headers, ByteArray(0), null, null, kind)
        return Outcome.Answer(AuthBoundResult<Any>(requireCurrent, Result.failure(mapAlertHttpFailure(failure)),
            AlertRateLimitResponseEvidence(status, retryAfter)))
    }

    private fun failed(kind: AlertRepositoryFailureKind, status: Int? = null): Outcome = Outcome.Answer(
        AuthBoundResult<Any>({}, Result.failure(AlertRepositoryException("x", failureKind = kind)),
            status?.let { AlertRateLimitResponseEvidence(it, null) }))

    // ---- the rig ----

    private inner class Rig(val test: TestScope) {
        var identity: AuthIdentityFence? = alice
        var fence: TopicSessionFence? = fenceFor(alice, grantA)
        var snapshot: TopicAccessSnapshot = standing(grantA)
        var release = true
        val deletions = DeletionAdmissionStore()
        val premium = MutableStateFlow(confirmed(alice))
        val access = AlertSettingsAccess(
            { identity }, { fence }, SnapshotTopicUseAuthority { snapshot }, { premium.value }, { release },
            GraphProtectedAdmission({ identity }, deletions)
        )
        val floor = AlertRateLimitFloor({ test.testScheduler.currentTime })
        val calls = mutableListOf<Call>()
        var ignoresCancellation = false

        private suspend fun <T> scripted(call: Call): T {
            calls += call
            val outcome = try {
                if (ignoresCancellation) withContext(NonCancellable) { call.outcome.await() } else call.outcome.await()
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
            CoroutineScope(job + StandardTestDispatcher(test.testScheduler)), { delay(it) },
            premium, MutableStateFlow(0L), MutableStateFlow(false),
            { who, id, enabled, admitted -> scripted(Call("PUT", who, id, enabled, admitted)) },
            { who, id, admitted -> scripted(Call("DELETE", who, id, null, admitted)) },
            RECHECK
        )
        /** Every manual outcome the owner emits, collected eagerly. */
        val outcomes = mutableListOf<ManualRefreshOutcome>()

        init {
            CoroutineScope(job + UnconfinedTestDispatcher(test.testScheduler)).launch { owner.manualOutcomes.collect { outcomes += it } }
        }

        val now: Long get() = test.testScheduler.currentTime
        val rows: List<AlertSetting>? get() = owner.state.value.settings
        fun settle() = test.runCurrent()
        fun advance(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }
        fun open(kind: String): List<Call> = calls.filter { it.kind == kind && it.open }
        fun count(kind: String): Int = calls.count { it.kind == kind }
        fun answer(kind: String, outcome: Outcome) {
            val pending = open(kind)
            check(pending.size == 1) { "exactly one $kind awaits its answer, not ${pending.size}" }
            pending.single().outcome.complete(outcome)
            settle()
        }
        /** Foreground, one GET answered with [loaded]: the snapshot every test starts from. */
        fun loaded(vararg loaded: AlertSetting = arrayOf(setting(1), setting(2))) {
            owner.onForeground(false)
            settle()
            answer("GET", okList(*loaded))
            check(owner.state.value.phase == AlertLoadPhase.Ready && open("GET").isEmpty())
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

    private fun rig(test: TestScope, body: Rig.() -> Unit) {
        val r = Rig(test)
        try {
            r.body()
        } finally {
            r.close()
        }
    }

    // ---- entry ----

    @Test fun `S7E5A2_M01 a toggle shows its target at once sends one partial PUT of that target and applies the server row`() = runTest {
        rig(this) {
            loaded(setting(1, enabled = false, triggered = true), setting(2))
            toggle(1, true)
            assertEquals("optimistic: enabled and no longer triggered, as the server's enable rule shows",
                listOf(setting(1, enabled = true, triggered = false), setting(2)), rows)
            val put = open("PUT").single()
            assertEquals(listOf<Any?>(alice, 1, true), listOf(put.identity, put.id, put.enabled))
            assertTrue("the send carries the captured use's admission", put.admitted())
            answer("PUT", okRow(server(1, enabled = true)))
            assertEquals("the server's whole row replaces the optimistic one", listOf(server(1, true), setting(2)), rows)
            assertEquals("one PUT, no GET after a clean success", listOf(1, 1), listOf(count("PUT"), count("GET")))
        }
    }

    @Test fun `S7E5A2_M02 the entry refuses in the background without a snapshot for another identity for no row or a refused access`() = runTest {
        rig(this) {
            toggle(1, false)
            delete(1)
            assertEquals("no snapshot, no op", 0, calls.size)
            loaded()
            val before = rows
            owner.onBackground()
            settle()
            toggle(1, false)
            delete(2)
            assertEquals("background", listOf(before, 0, 0), listOf(rows, count("PUT"), count("DELETE")))
            owner.onForeground(false)
            settle()
            toggle(99, false)
            delete(99)
            assertEquals("no such row", listOf(before, 0, 0), listOf(rows, count("PUT"), count("DELETE")))
            release = false
            toggle(1, false)
            delete(1)
            assertEquals("access refused", listOf(before, 0, 0), listOf(rows, count("PUT"), count("DELETE")))
            release = true
            // A new session whose premium is confirmed but not yet seen by the owner: the use is admitted, for another identity.
            identity = alice2
            fence = fenceFor(alice2, grantA2)
            snapshot = standing(grantA2)
            premium.value = confirmed(alice2)
            owner.setEnabled(1, false)
            owner.delete(2)
            assertEquals("the live identity is not the snapshot's", listOf(before, 0, 0), listOf(rows, count("PUT"), count("DELETE")))
            settle()
            assertEquals("and nothing is sent once the reset ran", listOf(0, 0), listOf(count("PUT"), count("DELETE")))
        }
    }

    // ---- one op per row ----

    @Test fun `S7E5A2_M03 a toggle during a send merges into the latest target and sends only a newer target the server lacks`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            toggle(1, true)
            toggle(1, false)
            assertEquals("one PUT in flight, the display at the latest target", listOf(1, false), listOf(count("PUT"), rows!![0].isEnabled))
            answer("PUT", okRow(server(1, enabled = false)))
            assertEquals("the server already holds the latest target: nothing more", listOf(1, server(1, false)), listOf(count("PUT"), rows!![0]))
            toggle(1, true)
            toggle(1, false)
            answer("PUT", okRow(server(1, enabled = true)))
            assertEquals("a newer target is shown over the server row", false, rows!![0].isEnabled)
            val second = open("PUT").single()
            assertEquals("and sent once as its own request", listOf(3, false), listOf(count("PUT"), second.enabled))
            answer("PUT", okRow(server(1, enabled = false)))
            assertEquals(listOf(server(1, false), setting(2)), rows)
            assertEquals("no GET", 1, count("GET"))
        }
    }

    @Test fun `S7E5A2_M04 another row is independent and a rollback touches only its own row`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            toggle(2, false)
            assertEquals("both rows send", 2, count("PUT"))
            calls.single { it.kind == "PUT" && it.id == 2 }.outcome.complete(okRow(server(2, false)))
            settle()
            calls.single { it.kind == "PUT" && it.id == 1 }.outcome.complete(http(500))
            settle()
            assertEquals("row 1 back to its approved row, row 2 the server's", listOf(setting(1), server(2, false)), rows)
        }
    }

    @Test fun `S7E5A2_M05 a delete removes the row sends one DELETE and a success or a 404 keeps it removed without a GET`() = runTest {
        for (reply in listOf(okDelete(), http(404))) rig(this) {
            loaded()
            delete(1)
            assertEquals("optimistic removal", listOf(setting(2)), rows)
            assertEquals(listOf<Any?>("DELETE", alice, 1), open("DELETE").single().let { listOf(it.kind, it.identity, it.id) })
            answer("DELETE", reply)
            assertEquals("kept removed", listOf(setting(2)), rows)
            assertEquals("no GET, no replay", listOf(1, 1), listOf(count("GET"), count("DELETE")))
            delete(1)
            toggle(1, false)
            assertEquals("a removed row takes no op", listOf(1, 0), listOf(count("DELETE"), count("PUT")))
        }
    }

    @Test fun `S7E5A2_M06 a delete during a toggle wins and the toggle result never revives the row`() = runTest {
        for (toggleAnswer in listOf(okRow(server(1, false)), http(500), failed(AlertRepositoryFailureKind.NETWORK))) rig(this) {
            loaded()
            toggle(1, false)
            delete(1)
            toggle(1, true)
            delete(1)
            assertEquals("removed at once; a later toggle or delete of the row is a no-op", listOf(setting(2)), rows)
            answer("PUT", toggleAnswer)
            assertEquals("not revived", listOf(setting(2)), rows)
            assertEquals("then exactly one DELETE, no second PUT", listOf(1, 1), listOf(count("DELETE"), count("PUT")))
            answer("DELETE", okDelete())
            assertEquals(listOf(setting(2)), rows)
        }
    }

    @Test fun `S7E5A2_M07 a refused DELETE restores only the last approved row at its own place and owes a GET`() = runTest {
        rig(this) {
            loaded(setting(1), setting(2), setting(3))
            toggle(2, false)
            delete(2)
            answer("PUT", okRow(server(2, false)))
            answer("DELETE", http(500))
            assertEquals("the toggle's server row, back at index 1", listOf(setting(1), server(2, false), setting(3)), rows)
            assertEquals("the obligation asks one GET", 1, open("GET").size)
            answer("GET", okList(setting(1), setting(3)))
            assertEquals("the accepted GET is the final list", listOf(setting(1), setting(3)), rows)
        }
    }

    // ---- results ----

    @Test fun `S7E5A2_M08 an HTTP failure rolls the row back and owes a GET that ignores the freshness skip`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            answer("PUT", http(500))
            assertEquals("rolled back", listOf(setting(1), setting(2)), rows)
            assertEquals("owed: a GET inside the 30 s freshness", 2, count("GET"))
            answer("GET", okList(setting(1, enabled = false), setting(2)))
            assertEquals(listOf(setting(1, enabled = false), setting(2)), rows)
            owner.ensure()
            settle()
            assertEquals("the accepted GET settled the obligation: fresh again", 2, count("GET"))
            assertEquals("no replay of the failed toggle", 1, count("PUT"))
        }
    }

    @Test fun `S7E5A2_M09 an IO failure an undecodable 2xx an identity loss or a cancellation keeps the row and owes a GET`() = runTest {
        val unknowns = listOf(
            failed(AlertRepositoryFailureKind.NETWORK),
            failed(AlertRepositoryFailureKind.DECODE, status = 200),
            failed(AlertRepositoryFailureKind.UNEXPECTED),
            Outcome.Throw(java.io.IOException("reset")),
            Outcome.Throw(CancellationException("transport cancelled"))
        )
        for (unknown in unknowns) rig(this) {
            loaded()
            toggle(1, false)
            answer("PUT", unknown)
            assertEquals("kept as shown", listOf(setting(1, enabled = false), setting(2)), rows)
            assertEquals("owed GET, no replay", listOf(2, 1), listOf(count("GET"), count("PUT")))
        }
    }

    @Test fun `S7E5A2_M10 a toggled row the server no longer has is removed and fetched again`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            answer("PUT", http(404))
            assertEquals("removed", listOf(setting(2)), rows)
            assertEquals("a GET follows", 2, count("GET"))
        }
    }

    @Test fun `S7E5A2_M11 a refusal before the wire under a still admitted use rolls back and owes nothing`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            answer("PUT", Outcome.Throw(TopicUseWithheldException(emptyList())))
            assertEquals("rolled back", listOf(setting(1), setting(2)), rows)
            assertEquals("nothing reached the server: no GET", 1, count("GET"))
        }
    }

    @Test fun `S7E5A2_M12 a result whose captured use was revoked changes nothing and owes a GET once the access returns`() = runTest {
        for (revoked in listOf(okRow(server(1, false)), http(500), Outcome.Throw(TopicUseWithheldException(emptyList())))) rig(this) {
            loaded()
            toggle(1, false)
            val put = open("PUT").single()
            release = false
            assertFalse("the captured use is revoked", put.admitted())
            answer("PUT", revoked)
            assertEquals("neither applied nor rolled back", listOf(setting(1, enabled = false), setting(2)), rows)
            release = true
            advance(RECHECK)
            assertEquals("the GET serves the obligation", 2, count("GET"))
            answer("GET", okList(setting(1), setting(2)))
            assertEquals(listOf(setting(1), setting(2)), rows)
        }
    }

    @Test fun `S7E5A2_M13 a reset cancels the op and its late result changes neither the list nor an obligation`() = runTest {
        rig(this) {
            loaded()
            ignoresCancellation = true
            toggle(1, false)
            val put = open("PUT").single()
            owner.reset()
            settle()
            put.outcome.complete(http(500))
            settle()
            assertEquals("reset forgot the list and nothing came back", null, rows)
            owner.ensure()
            settle()
            assertEquals("a fresh GET is the reset's own, not a reconcile", 2, count("GET"))
            answer("GET", okList(setting(1), setting(2)))
            assertEquals(listOf(setting(1), setting(2)), rows)
            assertEquals("no replay", 1, count("PUT"))
            toggle(1, false)
            assertEquals("the reset left no op behind: the same row starts a new send", 2, count("PUT"))
        }
    }

    @Test fun `S7E5A2_M14 every result records its endpoint floor first even when it is dropped and the next send waits for it`() = runTest {
        rig(this) {
            loaded()
            ignoresCancellation = true
            toggle(1, false)
            val put = open("PUT").single()
            owner.reset()
            settle()
            put.outcome.complete(http(429, retryAfter = "30"))
            settle()
            assertTrue("the PUT floor holds the answer", floor.remainingMillis(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING) > 0)
            assertEquals("the GET floor is its own", 0L, floor.remainingMillis(AuthenticatedEndpoint.NOTIFICATION_SETTINGS))
            owner.ensure()
            settle()
            answer("GET", okList(setting(1), setting(2)))
            toggle(2, false)
            assertEquals("the next PUT waits for the floor", 1, count("PUT"))
            delete(1)
            assertEquals("a DELETE has its own floor", 1, open("DELETE").size)
            answer("DELETE", okDelete())
            advance(30_000)
            assertEquals("then the waiting PUT goes", 2, count("PUT"))
            answer("PUT", okRow(server(2, false)))
            assertEquals(listOf(server(2, false)), rows)
        }
    }

    @Test fun `S7E5A2_M15 a delete queued during the floor wait is sent instead of the toggle`() = runTest {
        rig(this) {
            loaded()
            floor.record(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, AlertRateLimitEvidence.of(emptyList(), 429, "10"))
            toggle(1, false)
            assertEquals("waiting", 0, count("PUT"))
            delete(1)
            assertEquals("removed at once; the toggle is not sent", listOf(listOf(setting(2)), 0), listOf(rows, count("PUT")))
            advance(10_000)
            assertEquals("by the PUT floor's end at the latest the delete is chosen, once", listOf(0, 1, 1),
                listOf(count("PUT"), count("DELETE"), open("DELETE").size))
            answer("DELETE", okDelete())
            advance(10_000)
            assertEquals("the toggle is never sent", 0, count("PUT"))
            assertEquals(listOf(setting(2)), rows)
        }
    }

    // ---- GET ----

    @Test fun `S7E5A2_M16 a GET body that raced a local change is discarded and a trailing GET follows once the row settles`() = runTest {
        rig(this) {
            loaded()
            owner.manual()
            settle()
            assertEquals("a manual GET is in flight", 1, open("GET").size)
            toggle(1, false)
            answer("GET", okList(setting(1), setting(2), setting(3)))
            assertEquals("discarded: the optimistic row stays and nothing was added", listOf(setting(1, enabled = false), setting(2)), rows)
            assertTrue("the manual demand is still requested", owner.manualRefresh.value.requested)
            assertEquals("no GET while the row op runs", 0, open("GET").size)
            answer("PUT", okRow(server(1, false)))
            assertEquals("the op's end wakes the trailing GET at once, not the recheck timer", 1, open("GET").size)
            answer("GET", okList(server(1, false), setting(2), setting(3)))
            assertEquals(listOf(server(1, false), setting(2), setting(3)), rows)
            assertFalse("the manual demand settled on the accepted body", owner.manualRefresh.value.requested)
        }
    }

    @Test fun `S7E5A2_M17 the background starts no send and keeps the obligation for the next foreground`() = runTest {
        rig(this) {
            loaded()
            floor.record(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, AlertRateLimitEvidence.of(emptyList(), 429, "5"))
            toggle(1, false)
            owner.onBackground()
            settle()
            advance(5_000)
            assertEquals("never sent", 0, count("PUT"))
            owner.onForeground(false)
            settle()
            assertEquals("the owed GET ignores the 30 s freshness", 2, count("GET"))
            answer("GET", okList(setting(1), setting(2)))
            assertEquals(listOf(setting(1), setting(2)), rows)
        }
    }

    @Test fun `S7E5A2_M18 the background does not cancel a send in flight and its admitted answer still applies`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            owner.onBackground()
            settle()
            answer("PUT", okRow(server(1, false)))
            assertEquals(listOf(server(1, false), setting(2)), rows)
            assertEquals("no replay", 1, count("PUT"))
        }
    }

    @Test fun `S7E5A2_M19 an identity change while a delete waits resets it and sends nothing as anyone`() = runTest {
        rig(this) {
            loaded()
            floor.record(AuthenticatedEndpoint.DELETE_NOTIFICATION_SETTING, AlertRateLimitEvidence.of(emptyList(), 429, "5"))
            delete(1)
            identity = alice2
            fence = fenceFor(alice2, grantA2)
            snapshot = standing(grantA2)
            premium.value = confirmed(alice2)
            settle()
            advance(5_000)
            assertEquals("no DELETE for either session", 0, count("DELETE"))
            assertEquals("the new session starts from its own GET", listOf<Any?>(null, alice2), listOf(rows, open("GET").singleOrNull()?.identity))
        }
    }

    @Test fun `S7E5A2_M20 an identity loss thrown by the send keeps the row and records the response floor`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            answer("PUT", Outcome.Throw(AuthIdentityChangedException(statusCode = 429, retryAfter = "20")))
            assertTrue("its floor is recorded", floor.remainingMillis(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING) > 0)
            assertEquals("kept as shown", listOf(setting(1, enabled = false), setting(2)), rows)
        }
    }

    @Test fun `S7E5A2_M21 an accepted request whose use was revoked while it waited is not re-approved by a fresh acquire`() = runTest {
        rig(this) {
            loaded()
            floor.record(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, AlertRateLimitEvidence.of(emptyList(), 429, "5"))
            toggle(1, false)
            snapshot = standing(grantA, invalidations = 1)
            advance(5_000)
            assertEquals("the new use is admitted, yet the old acceptance sends nothing", 0, count("PUT"))
            assertEquals("kept as shown, with a GET owed", listOf(listOf(setting(1, enabled = false), setting(2)), 2),
                listOf(rows, count("GET")))
        }
    }

    @Test fun `S7E5A2_M22 a stale op still waiting on its send after a reset holds neither the reload's GET nor a new op on the same row`() = runTest {
        rig(this) {
            loaded()
            ignoresCancellation = true
            toggle(1, false)
            val stale = open("PUT").single()
            owner.reset()
            settle()
            owner.ensure()
            settle()
            assertEquals("the reload's GET is not held by the stale op", 1, open("GET").size)
            answer("GET", okList(setting(1), setting(2)))
            toggle(1, false)
            assertEquals("a new op on the same row sends", 2, count("PUT"))
            stale.outcome.complete(http(500))
            settle()
            assertEquals("the stale answer changes nothing", listOf(setting(1, enabled = false), setting(2)), rows)
        }
    }

    // ---- gap hunt r1 (G05-G24) ----

    @Test fun `S7E5A2_M23 a server answer that differs from the target is adopted and the same request is never sent again`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            answer("PUT", okRow(server(1, enabled = true)))
            assertEquals("the server's row is shown as it answered", listOf(server(1, true), setting(2)), rows)
            advance(60_000)
            assertEquals("no repeat of the same request, no GET", listOf(1, 1, 0), listOf(count("PUT"), count("GET"), open("PUT").size))
        }
    }

    @Test fun `S7E5A2_M24 a success adopts every field of the server row and a failed delete restores that whole row`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            answer("PUT", okRow(rewritten(1, false)))
            assertEquals(listOf(rewritten(1, false), setting(2)), rows)
            toggle(2, false)
            delete(2)
            answer("PUT", okRow(rewritten(2, false)))
            answer("DELETE", http(500))
            assertEquals("the whole approved server row comes back", listOf(rewritten(1, false), rewritten(2, false)), rows)
        }
    }

    @Test fun `S7E5A2_M25 a failed or refused successor rolls back to the last approved server row and not the first row`() = runTest {
        for (failure in listOf(http(500), Outcome.Throw(TopicUseWithheldException(emptyList())))) rig(this) {
            loaded()
            toggle(1, false)
            toggle(1, true)
            answer("PUT", okRow(server(1, enabled = false)))
            assertEquals("the successor is shown over the approved row", server(1, false).copy(isEnabled = true), rows!![0])
            toggle(2, false)
            calls.single { it.kind == "PUT" && it.id == 2 }.outcome.complete(okRow(server(2, false)))
            settle()
            calls.last { it.kind == "PUT" && it.id == 1 }.outcome.complete(failure)
            settle()
            assertEquals("back to the approved server row; the other row keeps its own answer", listOf(server(1, false), server(2, false)), rows)
            assertEquals(3, count("PUT"))
        }
    }

    @Test fun `S7E5A2_M26 every result needs its own captured use even when a fresh use would be admitted`() = runTest {
        for (reply in listOf(okRow(server(1, false)), http(404), http(500), Outcome.Throw(TopicUseWithheldException(emptyList())))) rig(this) {
            loaded()
            toggle(1, false)
            val put = open("PUT").single()
            snapshot = standing(grantA, invalidations = 1)
            assertFalse("premise: the captured use is revoked", put.admitted())
            assertTrue("premise: a fresh use would be admitted", access.acquire() != null)
            answer("PUT", reply)
            assertEquals("neither applied, rolled back nor removed", listOf(setting(1, enabled = false), setting(2)), rows)
            assertEquals("only the owed GET", 2, count("GET"))
        }
        for (reply in listOf(okDelete(), http(404), http(500))) rig(this) {
            loaded()
            delete(1)
            val del = open("DELETE").single()
            snapshot = standing(grantA, invalidations = 1)
            assertFalse("premise: the captured use is revoked", del.admitted())
            answer("DELETE", reply)
            assertEquals("kept removed, nothing restored", listOf(setting(2)), rows)
            assertEquals("the owed GET", 2, count("GET"))
        }
    }

    @Test fun `S7E5A2_M27 a result refused by its own identity check still records its floor and changes no row`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            answer("PUT", http(429, retryAfter = "7", requireCurrent = { throw AuthIdentityChangedException() }))
            assertEquals("the floor from the answer", 7_000L, floor.remainingMillis(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING))
            assertEquals("the GET floor is its own", 0L, floor.remainingMillis(AuthenticatedEndpoint.NOTIFICATION_SETTINGS))
            assertEquals("kept as shown, not rolled back", listOf(setting(1, enabled = false), setting(2)), rows)
            assertEquals("owed GET", 2, count("GET"))
        }
        rig(this) {
            loaded()
            toggle(1, false)
            answer("PUT", okRow(server(1, false), requireCurrent = { throw AuthIdentityChangedException() }))
            assertEquals("the server row is not applied", listOf(setting(1, enabled = false), setting(2)), rows)
            assertEquals("owed GET", 2, count("GET"))
        }
    }

    @Test fun `S7E5A2_M28 a stale op's end does not retire the op that replaced it`() = runTest {
        rig(this) {
            loaded()
            ignoresCancellation = true
            toggle(1, false)
            val stale = open("PUT").single()
            owner.reset()
            settle()
            owner.ensure()
            settle()
            answer("GET", okList(setting(1), setting(2)))
            toggle(1, false)
            stale.outcome.complete(http(500))
            settle()
            toggle(1, true)
            assertEquals("the live op merged the new target: no parallel PUT", 2, count("PUT"))
            owner.authoritative()
            settle()
            assertEquals("a GET still waits for the live op", 0, open("GET").size)
            calls.last { it.kind == "PUT" }.outcome.complete(okRow(server(1, false)))
            settle()
            assertEquals("then the newer target, once", 3, count("PUT"))
            answer("PUT", okRow(server(1, true)))
            assertEquals("all ops ended: the waiting GET goes at once", 1, open("GET").size)
        }
    }

    @Test fun `S7E5A2_M29 a reset cancels every waiting mutation call while the background does not`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            delete(2)
            val put = open("PUT").single()
            val del = open("DELETE").single()
            owner.onBackground()
            settle()
            assertEquals("the background cancels nothing", listOf(false, false), listOf(put.cancelled, del.cancelled))
            owner.onForeground(false)
            settle()
            owner.reset()
            settle()
            assertEquals("the reset cancels both", listOf(true, true), listOf(put.cancelled, del.cancelled))
        }
    }

    @Test fun `S7E5A2_M30 the floor is read again at every wake for whichever endpoint the op then needs`() = runTest {
        rig(this) {
            loaded()
            floor.record(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, AlertRateLimitEvidence.of(emptyList(), 429, "5"))
            toggle(1, false)
            advance(4_000)
            floor.record(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, AlertRateLimitEvidence.of(emptyList(), 429, "6"))
            advance(1_000)
            assertEquals("the extended floor holds at the first wake", 0, count("PUT"))
            advance(4_999)
            assertEquals(0, count("PUT"))
            advance(1)
            assertEquals("sent at the extended deadline", 1, count("PUT"))
        }
        rig(this) {
            loaded()
            floor.record(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, AlertRateLimitEvidence.of(emptyList(), 429, "5"))
            floor.record(AuthenticatedEndpoint.DELETE_NOTIFICATION_SETTING, AlertRateLimitEvidence.of(emptyList(), 429, "12"))
            toggle(1, false)
            delete(1)
            advance(5_000)
            assertEquals("woken for the PUT floor, the delete waits for its own", listOf(0, 0), listOf(count("PUT"), count("DELETE")))
            advance(6_999)
            assertEquals(0, count("DELETE"))
            advance(1)
            assertEquals(listOf(0, 1), listOf(count("PUT"), count("DELETE")))
        }
    }

    @Test fun `S7E5A2_M31 after a send in flight the background starts neither a newer target nor a queued delete`() = runTest {
        for (queueDelete in listOf(false, true)) rig(this) {
            loaded()
            toggle(1, false)
            if (queueDelete) delete(1) else toggle(1, true)
            owner.onBackground()
            settle()
            answer("PUT", okRow(server(1, false)))
            assertEquals("nothing more is sent in the background", listOf(1, 0), listOf(count("PUT"), count("DELETE")))
            owner.onForeground(false)
            settle()
            assertEquals("the obligation asks one GET instead", 2, count("GET"))
        }
    }

    @Test fun `S7E5A2_M32 each answer's evidence goes to its own endpoint floor from the exchanges first and nothing is invented`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            answer("PUT", Outcome.Throw(AuthIdentityChangedException(503, "90", listOf(HttpExchangeEvidence(1, 1, 429, "10")))))
            assertEquals("the exchanges replace the fallback pair",
                listOf(10_000L, 0L, 0L), listOf(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, AuthenticatedEndpoint.DELETE_NOTIFICATION_SETTING, AuthenticatedEndpoint.NOTIFICATION_SETTINGS).map { floor.remainingMillis(it) })
        }
        rig(this) {
            loaded()
            toggle(1, false)
            answer("PUT", Outcome.Throw(TopicUseWithheldException(listOf(HttpExchangeEvidence(1, 1, 503, "9")))))
            assertEquals("a refusal hands over what it had observed", 9_000L, floor.remainingMillis(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING))
        }
        rig(this) {
            loaded()
            delete(1)
            answer("DELETE", http(429, retryAfter = "7"))
            assertEquals("a delete's answer is the delete's floor", listOf(7_000L, 0L), listOf(AuthenticatedEndpoint.DELETE_NOTIFICATION_SETTING, AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING).map { floor.remainingMillis(it) })
        }
        rig(this) {
            loaded()
            toggle(1, false)
            answer("PUT", Outcome.Throw(java.io.IOException("reset")))
            assertEquals("no answer, no floor", listOf(0L, 0L, 0L), listOf(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, AuthenticatedEndpoint.DELETE_NOTIFICATION_SETTING, AuthenticatedEndpoint.NOTIFICATION_SETTINGS).map { floor.remainingMillis(it) })
        }
    }

    @Test fun `S7E5A2_M33 a refusal that had already seen an answer is no proof the request stayed off the wire`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            answer("PUT", Outcome.Throw(TopicUseWithheldException(listOf(HttpExchangeEvidence(1, 1, 200, null)))))
            assertEquals("kept as shown, not rolled back", listOf(setting(1, enabled = false), setting(2)), rows)
            assertEquals("owed GET", 2, count("GET"))
        }
    }

    @Test fun `S7E5A2_M34 a GET body is discarded for a change it started before even when every op already ended`() = runTest {
        rig(this) {
            loaded()
            owner.manual()
            settle()
            toggle(1, false)
            answer("PUT", okRow(server(1, false)))
            assertEquals("premise: the op ended while the GET is still out", 1, open("GET").size)
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("the older body is not adopted", listOf(server(1, false), setting(2)), rows)
            assertEquals("a trailing GET follows at once", 1, open("GET").size)
            answer("GET", okList(server(1, false), setting(2)))
            assertEquals(listOf(server(1, false), setting(2)), rows)
        }
    }

    @Test fun `S7E5A2_M35 a discarded body is no success for freshness or the manual outcome`() = runTest {
        rig(this) {
            loaded()
            advance(31_000)
            owner.manual()
            settle()
            toggle(1, false)
            answer("GET", okList(setting(1), setting(2)))
            assertEquals("no manual outcome for a discarded body", emptyList<ManualRefreshOutcome>(), outcomes)
            answer("PUT", okRow(server(1, false)))
            assertEquals("nor for a toggle success", emptyList<ManualRefreshOutcome>(), outcomes)
            answer("GET", http(403))
            assertEquals("the manual demand ends with its own GET", listOf<ManualRefreshOutcome>(ManualRefreshOutcome.Failed), outcomes)
            owner.ensure()
            settle()
            assertEquals("the last success is still the first load, 31 s ago: an ensure fetches", 1, open("GET").size)
        }
    }

    @Test fun `S7E5A2_M36 local changes never refresh the GET's freshness`() = runTest {
        val changes = listOf<Rig.() -> Unit>(
            { toggle(1, false); answer("PUT", okRow(server(1, false))) },
            { toggle(1, false); answer("PUT", Outcome.Throw(TopicUseWithheldException(emptyList()))) },
            { delete(1); answer("DELETE", okDelete()) })
        for (change in changes) rig(this) {
            loaded()
            advance(29_000)
            change()
            assertEquals("premise: nothing was owed", 1, count("GET"))
            advance(2_000)
            owner.ensure()
            settle()
            assertEquals("the first load is 31 s old: an ensure fetches", 2, count("GET"))
        }
    }

    @Test fun `S7E5A2_M37 an obligation ends only with an accepted GET`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            answer("PUT", http(500))
            assertEquals("premise: the owed GET started", 1, open("GET").size)
            owner.onBackground()
            settle()
            owner.onForeground(false)
            settle()
            assertEquals("a started but abandoned GET does not settle it", 3, count("GET"))
        }
        rig(this) {
            loaded()
            toggle(1, false)
            answer("PUT", http(500))
            answer("GET", http(403))
            owner.ensure()
            settle()
            assertEquals("a failed GET does not settle it", 3, count("GET"))
        }
        rig(this) {
            loaded()
            toggle(1, false)
            toggle(2, false)
            calls.single { it.kind == "PUT" && it.id == 1 }.outcome.complete(http(500))
            settle()
            calls.single { it.kind == "PUT" && it.id == 2 }.outcome.complete(okRow(server(2, false)))
            settle()
            assertEquals("premise: the owed GET started once the rows settled", 1, open("GET").size)
            owner.onBackground()
            settle()
            owner.onForeground(false)
            settle()
            assertEquals("another row's success does not settle it", 3, count("GET"))
        }
        rig(this) {
            loaded()
            toggle(1, false)
            delete(2)
            calls.single { it.kind == "PUT" }.outcome.complete(http(500))
            settle()
            answer("DELETE", okDelete())
            owner.onBackground()
            settle()
            owner.onForeground(false)
            settle()
            assertEquals("a delete's success does not settle it", 3, count("GET"))
        }
    }

    @Test fun `S7E5A2_M38 an unknown delete result keeps the row removed and owes a GET`() = runTest {
        val unknowns = listOf(failed(AlertRepositoryFailureKind.NETWORK), Outcome.Throw(java.io.IOException("reset")),
            Outcome.Throw(CancellationException("transport cancelled")))
        for (unknown in unknowns) rig(this) {
            loaded()
            delete(1)
            answer("DELETE", unknown)
            assertEquals("kept removed", listOf(setting(2)), rows)
            assertEquals("owed GET", 2, count("GET"))
        }
    }

    @Test fun `S7E5A2_M39 mutation results never touch the manual outcomes or cooldown`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            answer("PUT", http(500))
            delete(2)
            answer("DELETE", failed(AlertRepositoryFailureKind.NETWORK))
            assertEquals(emptyList<ManualRefreshOutcome>(), outcomes)
            assertFalse("no cooldown from a mutation", owner.manualRefresh.value.coolingDown)
        }
    }

    @Test fun `S7E5A2_M40 the send's admission asks only the captured use`() = runTest {
        rig(this) {
            loaded()
            toggle(1, false)
            val put = open("PUT").single()
            floor.record(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING, AlertRateLimitEvidence.of(emptyList(), 429, "30"))
            assertTrue("a floor is not the use's admission", put.admitted())
            snapshot = standing(grantA, invalidations = 1)
            assertFalse("a fresh use does not admit the captured use's send", put.admitted())
        }
    }

    @Test fun `S7E5A2_M41 a failed or unknown result is never replayed later`() = runTest {
        for (reply in listOf(http(500), failed(AlertRepositoryFailureKind.NETWORK))) rig(this) {
            loaded()
            toggle(1, false)
            answer("PUT", reply)
            answer("GET", okList(setting(1), setting(2)))
            advance(120_000)
            assertEquals("one PUT ever", 1, count("PUT"))
        }
        rig(this) {
            loaded()
            delete(1)
            answer("DELETE", failed(AlertRepositoryFailureKind.NETWORK))
            answer("GET", okList(setting(2)))
            advance(120_000)
            assertEquals("one DELETE ever", 1, count("DELETE"))
        }
    }

    // ---- implementation review r1 (owner F1-F3) ----

    @Test fun `S7E5A2_M42 back in the foreground an op in flight sends the user's newer target or delete after its answer`() = runTest {
        val scenes: List<Pair<String, Rig.() -> Unit>> = listOf(
            "a delete requested after returning" to { owner.onBackground(); settle(); owner.onForeground(false); settle(); delete(1) },
            "a target requested after returning" to { owner.onBackground(); settle(); owner.onForeground(false); settle(); toggle(1, true) },
            "a target queued before leaving" to { toggle(1, true); owner.onBackground(); settle(); owner.onForeground(false); settle() })
        for ((label, scene) in scenes) rig(this) {
            loaded()
            toggle(1, false)
            scene()
            answer("PUT", okRow(server(1, false)))
            if (label.startsWith("a delete")) {
                assertEquals("$label: the delete is sent", listOf(1, 1), listOf(count("PUT"), count("DELETE")))
                answer("DELETE", okDelete())
                assertEquals("$label: and the row stays gone", listOf(setting(2)), rows)
            } else {
                assertEquals("$label: the newer target is sent", 2, count("PUT"))
                assertEquals("$label", true, open("PUT").single().enabled)
            }
        }
    }

    @Test fun `S7E5A2_M43 a failed delete comes back at the place the row was removed from, after other rows moved`() = runTest {
        rig(this) {
            loaded(setting(1), setting(2), setting(3), setting(4))
            toggle(3, false)
            delete(1)
            calls.single { it.kind == "DELETE" && it.id == 1 }.outcome.complete(okDelete())
            settle()
            delete(3)
            assertEquals("premise", listOf(setting(2), setting(4)), rows)
            answer("PUT", okRow(server(3, false)))
            answer("DELETE", http(500))
            assertEquals("restored where it was removed", listOf(setting(2), server(3, false), setting(4)), rows)
        }
    }

    @Test fun `S7E5A2_M44 the GET after a discarded body starts a fresh retry budget as any trailing GET does`() = runTest {
        rig(this) {
            loaded()
            owner.authoritative()
            settle()
            answer("GET", http(503))
            advance(2_000)
            assertEquals("premise: the first retry is out", 3, count("GET"))
            toggle(1, false)
            answer("GET", okList(setting(1), setting(2)))
            answer("PUT", okRow(server(1, false)))
            assertEquals("premise: the trailing GET", 4, count("GET"))
            answer("GET", http(503))
            advance(1_999)
            assertEquals("the first backoff of a fresh budget, not the second", 4, count("GET"))
            advance(1)
            assertEquals(5, count("GET"))
        }
    }

    private companion object {
        const val RECHECK = 1_000L
    }
}
