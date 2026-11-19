package com.jay.fxi.data.alerts

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.jay.fxi.contract.ContractCorpusVerifier
import com.jay.fxi.contract.ContractResourceLoader
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.repository.AlertRateLimitResponseEvidence
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.mapAlertHttpFailure
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.service.AlertPushRouting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Claude-owned S7 U4 contract (R4c/S7/s7u4 contract, user decision R4c/S7/user_decision_s7_u1gate.r1.md G-U4): the client side
 * of "another device changed my bank alerts". The other device and the server are not here: the tests supply scripted GET
 * lists and, in D05, a scripted toggle 404, as if the server had already taken the other device's create, edit and delete.
 * What is real is this device's path — the `sync_alerts` golden through [AlertPushRouting.route] and
 * [AlertFamilyRefresherRegistry.deliverPush], the registry's lifecycle and pending rule, and [BankAlertSettingsOwner] with its
 * real access, use authority and floor.
 *
 * Two ways to converge are kept apart, because the server's sync push is best effort: by a sync injected at the client routing
 * boundary (the foreground at once, the background at the next foreground), and with no sync at all by the next ordinary load,
 * which the 30 s freshness skip may postpone. A mutation that meets a row the other device already deleted is its own case:
 * the 404 removes the row and the GET that follows adopts whatever else changed.
 *
 * Not this file's: a real second device, the server's commit and send order, an FCM transport or a delivered silent push in a
 * killed process, source/comparison families (S8/S9), and the retry, floor, identity and trailing-GET combinations already
 * locked by the S7N1/S7E5A2/S7E5C contracts. Every GET count is the number of injected getSettings calls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class S7U4OtherDeviceConvergenceContractTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val grantA = TopicGrantToken(11)
    private val stamp = Instant.parse("2026-11-20T01:00:00Z")
    private val later = Instant.parse("2026-11-20T02:00:00Z")

    private fun setting(id: Int) =
        AlertSetting(id, "user-a", "hana", "usd-krw", AlertCondition.ABOVE, 1400.0 + id, true, false, stamp, stamp, null, null)

    /** This device's snapshot before the other device acts. */
    private val before = listOf(setting(101), setting(102), setting(104))

    /** A row the other device created: another bank and currency, repeating every five minutes. */
    private val created = AlertSetting(201, "user-a", "kb", "jpy-krw", AlertCondition.BELOW, 905.5, true, false, later, later, null, 300)

    /** Row 102 as the other device left it: every editable field moved, switched off. */
    private val edited = AlertSetting(102, "user-a", "hana", "usd-krw", AlertCondition.BELOW, 1388.0, false, false, stamp, later, null, 3600)

    /** The server's list after the other device created 201, edited 102 and deleted 104, in the server's order. */
    private val after = listOf(created, setting(101), edited)

    private fun confirmed(id: AuthIdentityFence) = OwnedPremiumAccess(id.uid, id.authGeneration, PremiumAccessState.PremiumConfirmed)
    private fun fenceFor(id: AuthIdentityFence, grant: TopicGrantToken) = TopicSessionFence(id, "epoch-1", grant)
    private fun standing(grant: TopicGrantToken) = TopicAccessSnapshot.INITIAL.copy(
        revision = 1L,
        facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet())
    )

    /** The server's sync golden, its data strings as they are. */
    private val syncData: Map<String, String> by lazy {
        val corpus = ContractCorpusVerifier.verify(ContractResourceLoader.load())
        Json.parseToJsonElement(corpus.text("fcm-sync-alerts")).jsonObject.getValue("data").jsonObject
            .mapValues { (_, value) -> value.jsonPrimitive.also { check(it.isString) }.content }
    }

    private class Call(val kind: String, val id: Int?) {
        val outcome = CompletableDeferred<AuthBoundResult<*>>()
        var cancelled = false
        val open: Boolean get() = !outcome.isCompleted && !cancelled
    }

    private fun okList(rows: List<AlertSetting>): AuthBoundResult<List<AlertSetting>> = AuthBoundResult({}, Result.success(rows))

    private fun notFound(): AuthBoundResult<Any> {
        val failure = AuthenticatedHttpFailure(404, headersOf(), ByteArray(0), null, null, AuthenticatedFailureKind.UNKNOWN_NOT_FOUND)
        return AuthBoundResult({}, Result.failure(mapAlertHttpFailure(failure)), AlertRateLimitResponseEvidence(404, null))
    }

    /** The real lifecycle registry, without main-thread checks. */
    private class Process : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private inner class Rig(val test: TestScope) {
        var identity: AuthIdentityFence? = alice
        val premium = MutableStateFlow(confirmed(alice))
        val access = AlertSettingsAccess(
            { identity }, { fenceFor(alice, grantA) }, SnapshotTopicUseAuthority { standing(grantA) }, { premium.value }, { true },
            GraphProtectedAdmission({ identity }, DeletionAdmissionStore())
        )
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
            return outcome as T
        }

        private val job = SupervisorJob()
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        val owner = BankAlertSettingsOwner(
            { _, _ -> scripted(Call("GET", null)) },
            access, AlertRateLimitFloor({ test.testScheduler.currentTime }), AlertRetryPolicy(), { test.testScheduler.currentTime }, { 0.0 },
            CoroutineScope(job + dispatcher),
            { delay(it) },
            premium, MutableStateFlow(0L), MutableStateFlow(false),
            { _, id, _, _ -> scripted(Call("PUT", id)) },
            { _, id, _ -> scripted(Call("DELETE", id)) },
            { _, _, _ -> error("S7U4: no create in this test") },
            { _, _, _, _ -> error("S7U4: no edit in this test") }
        )

        var failures = 0
        val process = Process().also { it.registry.currentState = Lifecycle.State.STARTED }
        val registry = AlertFamilyRefresherRegistry(
            families = listOf({ owner }),
            lifecycle = { process.registry },
            postToMain = { action ->
                action()
                Unit
            },
            premium = premium,
            liveIdentity = { identity },
            scope = CoroutineScope(job + dispatcher),
            reportFailure = { failures++ }
        )

        private var baseGets = 0
        val state: BankAlertSettingsState get() = owner.state.value
        val rows: List<AlertSetting>? get() = state.settings
        fun settle() = test.runCurrent()
        fun advance(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }

        fun open(kind: String): List<Call> = calls.filter { it.kind == kind && it.open }

        /** GETs since the start's first load, and those still awaiting an answer. */
        fun newGets(): List<Int> = listOf(calls.count { it.kind == "GET" } - baseGets, open("GET").size)

        fun answerGet(rows: List<AlertSetting>) {
            val pending = open("GET")
            check(pending.size == 1) { "exactly one GET awaits its answer, not ${pending.size}" }
            pending.single().outcome.complete(okList(rows.map { it.copy() }))
            settle()
        }

        /** The registry starts in the foreground and the owner's first GET is answered with [before]. */
        fun loaded() {
            registry.start()
            settle()
            answerGet(before)
            check(state.phase == AlertLoadPhase.Ready && rows == before && open("GET").isEmpty() && failures == 0)
            baseGets = calls.count { it.kind == "GET" }
        }

        /** FXiMessagingService's order for the sync golden: its route, then the raw hand-over. */
        fun sync() {
            val type = syncData.getValue("type")
            checkNotNull(AlertPushRouting.route(type, syncData["setting_id"]))
            AlertFamilyRefresherRegistry.deliverPush(type, syncData["setting_id"], syncData["is_repeat"], true, reportFailure = { failures++ }) { registry }
            settle()
        }

        fun background() {
            process.registry.currentState = Lifecycle.State.CREATED
            settle()
        }

        fun foreground() {
            process.registry.currentState = Lifecycle.State.STARTED
            settle()
        }

        fun close() = job.cancel()
    }

    private fun rig(test: TestScope, body: Rig.() -> Unit) {
        val r = Rig(test)
        try {
            r.loaded()
            r.body()
            assertEquals("no delivery or dispatch failure", 0, r.failures)
        } finally {
            r.close()
        }
    }

    // ---- converging by an injected sync ----

    @Test fun `S7U4_D01 a foreground sync is one GET whose list replaces the snapshot, created edited and deleted rows alike`() = runTest {
        rig(this) {
            sync()
            assertEquals("the snapshot is kept until the answer", before, rows)
            assertEquals("one GET", listOf(1, 1), newGets())
            answerGet(after)
            assertEquals("the scripted GET list is adopted in order, including new row 201, edited row 102 and removal of 104",
                after, rows)
            assertEquals("the identity and phase are kept", listOf<Any?>(alice, AlertLoadPhase.Ready), listOf(state.identity, state.phase))
            advance(60_000)
            assertEquals("no additional getSettings call during the next minute", listOf(1, 0), newGets())
        }
    }

    @Test fun `S7U4_D02 a sync answered with no rows leaves a ready empty list, not an error`() = runTest {
        rig(this) {
            sync()
            assertEquals("one GET", listOf(1, 1), newGets())
            answerGet(emptyList())
            assertEquals(emptyList<AlertSetting>(), rows)
            assertEquals(AlertLoadPhase.Ready, state.phase)
            advance(60_000)
            assertEquals(listOf(1, 0), newGets())
        }
    }

    @Test fun `S7U4_D03 two injected background syncs produce one getSettings call on foreground return at 10 s`() = runTest {
        rig(this) {
            background()
            sync()
            sync()
            advance(10_000)
            assertEquals("no getSettings call during the background interval", listOf(0, 0), newGets())
            assertEquals("and the snapshot is kept", before, rows)
            foreground()
            assertEquals("one GET, 10 s after the last success", listOf(1, 1), newGets())
            answerGet(after)
            assertEquals(after, rows)
            advance(60_000)
            assertEquals("one getSettings call in total and none pending 60 s after the reply", listOf(1, 0), newGets())
        }
    }

    // ---- converging with no sync at all ----

    @Test fun `S7U4_D04 with no sync a return inside 30 s keeps the old list and the first return from 30 s loads the new one`() = runTest {
        rig(this) {
            background()
            advance(29_999)
            foreground()
            assertEquals("a fresh snapshot is not reloaded: this device does not know yet", listOf(0, 0), newGets())
            assertEquals(before, rows)
            background()
            advance(1)
            foreground()
            assertEquals("the ordinary load at 30 s", listOf(1, 1), newGets())
            answerGet(after)
            assertEquals("the next ordinary load converges", after, rows)
        }
    }

    // ---- a mutation that meets the other device's delete ----

    @Test fun `S7U4_D05 a scripted toggle 404 removes row 104 and the following GET adopts the replacement list`() = runTest {
        rig(this) {
            owner.setEnabled(104, false)
            settle()
            val put = open("PUT")
            assertEquals("one PUT for row 104", listOf(104), put.map { it.id })
            assertEquals("row 104 is still present while the toggle awaits its reply", listOf(101, 102, 104), rows?.map { it.id })
            assertEquals("no getSettings call before the scripted 404", listOf(0, 0), newGets())
            put.single().outcome.complete(notFound())
            settle()
            assertEquals("row 104 is removed, the other rows are untouched", listOf(setting(101), setting(102)), rows)
            assertEquals("a GET follows the 404", listOf(1, 1), newGets())
            answerGet(after)
            assertEquals("the owner adopts the scripted replacement list after the 404", after, rows)
            advance(60_000)
            assertEquals(listOf(1, 0), newGets())
        }
    }
}
