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
import com.jay.fxi.domain.model.SourceAlertSetting
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
 * S8-8a (`R4c/S8/s8_8_scope.agreed.record.r1.md`, S7 U4's source counterpart): the client side of "another device changed my
 * exchange alerts". The other device and the server are not here: the tests supply scripted GET lists and, in D05, a scripted
 * toggle 404, as if the server had already taken the other device's create, edit and delete. What is real is this device's
 * path — the `sync_alerts` golden through [AlertPushRouting.route] and [AlertFamilyRefresherRegistry.deliverPush], the
 * registry's lifecycle and pending rule with the production family list (the bank owner, then the source owner), and both
 * owners with their real access, use authority and floors.
 *
 * Two ways to converge are kept apart, because the server's sync push is best effort: by a sync injected at the client routing
 * boundary (the foreground at once, the background at the next foreground), and with no sync at all by the next ordinary load,
 * which the 30 s freshness skip may postpone. A sync is a signal for both families, so each sync case also answers the bank's
 * GET with its unchanged list. A mutation that meets a row the other device already deleted is its own case: the 404 removes
 * the row and the GET that follows adopts whatever else changed; that is a source-only event, and the bank, which holds a row
 * with the same integer id 104, sends nothing and keeps its list.
 *
 * Every list holds only rows the exchange policy admits, for this user: the policy's own filter is S8-3 O01's. Not this file's:
 * a real second device, the server's commit and send order, an FCM transport or a delivered silent push in a killed process,
 * the comparison family (S9), and the retry, floor, identity and trailing-GET combinations already locked by the S8-3, S8-4a
 * and S8-5a contracts. Every GET count is the number of injected getSettings calls of that family.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class S88AOtherDeviceSourceConvergenceContractTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val grantA = TopicGrantToken(11)
    private val stamp = Instant.parse("2026-11-24T01:00:00Z")
    private val later = Instant.parse("2026-11-24T02:00:00Z")

    private fun row(id: Int, source: String) =
        SourceAlertSetting(id, "user-a", source, "usdt-krw", AlertCondition.ABOVE, 1400.0 + id / 100.0, true, false, null, stamp, stamp, null)

    /** This device's snapshot before the other device acts. */
    private val before = listOf(row(101, "upbit"), row(102, "bithumb"), row(104, "coinone"))

    /** A row the other device created: another exchange, below, repeating every five minutes. */
    private val created =
        SourceAlertSetting(201, "user-a", "korbit", "usdt-krw", AlertCondition.BELOW, 1388.5, true, false, 300, later, later, null)

    /**
     * Scripted row 102 keeps its id, user, asset and creation time. Its exchange, condition, threshold, enabled and triggered
     * flags, repeat interval, update time and trigger time change.
     */
    private val edited =
        SourceAlertSetting(102, "user-a", "gopax", "usdt-krw", AlertCondition.BELOW, 1377.0, false, true, 3600, stamp, later, later)

    /** The server's list after the other device created 201, edited 102 and deleted 104, in the server's order. */
    private val after = listOf(created, row(101, "upbit"), edited)

    /** The bank's list, unchanged by anything here; it shares the integer id 104 with a source row. */
    private val bankRows = listOf(AlertSetting(104, "user-a", "kb", "usd-krw", AlertCondition.ABOVE, 1404.0, true, false, stamp, stamp, null, null))

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

    /** One call to an injected family operation, answered by the test. */
    private class Call(val family: String, val kind: String, val id: Int?) {
        val outcome = CompletableDeferred<AuthBoundResult<*>>()
        var cancelled = false
        val open: Boolean get() = !outcome.isCompleted && !cancelled
    }

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
        private val floor = AlertRateLimitFloor({ test.testScheduler.currentTime })
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
        val bank = BankAlertSettingsOwner(
            { _, _ -> scripted(Call("bank", "GET", null)) },
            access, floor, AlertRetryPolicy(), { test.testScheduler.currentTime }, { 0.0 },
            CoroutineScope(job + dispatcher),
            { delay(it) },
            premium, MutableStateFlow(0L), MutableStateFlow(false),
            { _, _, _, _ -> error("S8-8a: no bank toggle") },
            { _, _, _ -> error("S8-8a: no bank delete") },
            { _, _, _ -> error("S8-8a: no bank create") },
            { _, _, _, _ -> error("S8-8a: no bank edit") }
        )
        val source = SourceAlertSettingsOwner(
            { _, _ -> scripted(Call("source", "GET", null)) },
            access, floor, AlertRetryPolicy(), { test.testScheduler.currentTime }, { 0.0 },
            CoroutineScope(job + dispatcher),
            { delay(it) },
            premium, MutableStateFlow(0L),
            { _, _, _ -> error("S8-8a: no source create") },
            { _, id, _, _ -> scripted(Call("source", "PUT", id)) },
            { _, _, _ -> error("S8-8a: no source delete") }
        )

        var failures = 0
        val process = Process().also { it.registry.currentState = Lifecycle.State.STARTED }
        val registry = AlertFamilyRefresherRegistry(
            families = listOf({ bank }, { source }),
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

        private var baseBankGets = 0
        private var baseSourceGets = 0
        val state: SourceAlertSettingsState get() = source.state.value
        val rows: List<SourceAlertSetting>? get() = state.settings
        val bankList: List<AlertSetting>? get() = bank.state.value.settings
        fun settle() = test.runCurrent()
        fun advance(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }

        fun open(family: String, kind: String): List<Call> = calls.filter { it.family == family && it.kind == kind && it.open }
        private fun count(family: String) = calls.count { it.family == family && it.kind == "GET" }

        /** (bank, source) GETs since the start's first loads. */
        fun newGets(): List<Int> = listOf(count("bank") - baseBankGets, count("source") - baseSourceGets)

        /** (bank, source) GETs still awaiting an answer. */
        fun openGets(): List<Int> = listOf(open("bank", "GET").size, open("source", "GET").size)

        fun answer(family: String, rows: List<*>) {
            val pending = open(family, "GET")
            check(pending.size == 1) { "exactly one $family GET awaits its answer, not ${pending.size}" }
            pending.single().outcome.complete(AuthBoundResult({}, Result.success(rows.toList())))
            settle()
        }

        /** A sync's two GETs: the bank's unchanged list, then the source's [rows]. */
        fun answerBoth(rows: List<SourceAlertSetting>) {
            answer("bank", bankRows.map { it.copy() })
            answer("source", rows)
        }

        /** The registry starts in the foreground and each family's first GET is answered: the bank's list, then [before]. */
        fun loaded() {
            registry.start()
            settle()
            answerBoth(before)
            check(state.phase == AlertLoadPhase.Ready && rows == before && bankList == bankRows && openGets() == listOf(0, 0) && failures == 0) {
                "the first loads were not adopted"
            }
            baseBankGets = count("bank")
            baseSourceGets = count("source")
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
            assertEquals("the bank's list is never changed here", bankRows, r.bankList)
        } finally {
            r.close()
        }
    }

    // ---- converging by an injected sync ----

    @Test fun `S88A_D01 a foreground sync is one source GET whose list replaces the snapshot, created edited and deleted rows alike`() = runTest {
        rig(this) {
            sync()
            assertEquals("the snapshot is kept until the answer", before, rows)
            assertEquals("one GET for each family", listOf(1, 1), newGets())
            answerBoth(after)
            assertEquals("the scripted GET list is adopted whole and in order, including new row 201, edited row 102 and removal of 104",
                after, rows)
            assertEquals("the identity and phase are kept", listOf<Any?>(alice, AlertLoadPhase.Ready), listOf(state.identity, state.phase))
            advance(60_000)
            assertEquals("no additional getSettings call during the next minute", listOf(1, 1), newGets())
            assertEquals("and none pending", listOf(0, 0), openGets())
        }
    }

    @Test fun `S88A_D02 a sync answered with no rows leaves a ready empty list, not an error`() = runTest {
        rig(this) {
            sync()
            assertEquals("one GET for each family", listOf(1, 1), newGets())
            answerBoth(emptyList())
            assertEquals("the rows the snapshot held are all gone", emptyList<SourceAlertSetting>(), rows)
            assertEquals("a ready list of this user, not an error", listOf<Any?>(alice, AlertLoadPhase.Ready), listOf(state.identity, state.phase))
            advance(60_000)
            assertEquals(listOf(1, 1), newGets())
            assertEquals(listOf(0, 0), openGets())
        }
    }

    @Test fun `S88A_D03 two injected background syncs produce one source GET on the foreground return at 10 s`() = runTest {
        rig(this) {
            background()
            sync()
            sync()
            advance(10_000)
            assertEquals("no getSettings call during the background interval", listOf(0, 0), newGets())
            assertEquals("and the snapshot is kept", before, rows)
            foreground()
            assertEquals("one GET for each family, 10 s after the last success", listOf(1, 1), newGets())
            answerBoth(after)
            assertEquals("the held sync's GET adopts the changed list whole", after, rows)
            assertEquals("the replacement belongs to the ready owner", alice to AlertLoadPhase.Ready, state.identity to state.phase)
            advance(60_000)
            assertEquals("one getSettings call in total for each family", listOf(1, 1), newGets())
            assertEquals("and none pending 60 s after the reply", listOf(0, 0), openGets())
        }
    }

    // ---- converging with no sync at all ----

    @Test fun `S88A_D04 with no sync a return inside 30 s keeps the old list and the first return from 30 s loads the new one`() = runTest {
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
            answerBoth(after)
            assertEquals("the next ordinary load converges", after, rows)
            assertEquals("the replacement belongs to the ready owner", alice to AlertLoadPhase.Ready, state.identity to state.phase)
            advance(60_000)
            assertEquals(listOf(1, 1), newGets())
            assertEquals(listOf(0, 0), openGets())
        }
    }

    // ---- a mutation that meets the other device's delete ----

    @Test fun `S88A_D05 a scripted toggle 404 removes row 104 and the following source GET adopts the replacement list`() = runTest {
        rig(this) {
            source.setEnabled(104, false)
            settle()
            val put = open("source", "PUT")
            assertEquals("one PUT for row 104", listOf(104), put.map { it.id })
            assertEquals("row 104 is still present while the toggle awaits its reply", listOf(101, 102, 104), rows?.map { it.id })
            assertEquals("no getSettings call before the scripted 404", listOf(0, 0), newGets())
            put.single().outcome.complete(notFound())
            settle()
            assertEquals("row 104 is removed, the other rows are untouched", listOf(row(101, "upbit"), row(102, "bithumb")), rows)
            assertEquals("a source GET follows the 404 and the bank sends none", listOf(0, 1), newGets())
            answer("source", after)
            assertEquals("the owner adopts the scripted replacement list after the 404", after, rows)
            assertEquals("the replacement belongs to the ready owner", alice to AlertLoadPhase.Ready, state.identity to state.phase)
            advance(60_000)
            assertEquals(listOf(0, 1), newGets())
            assertEquals(listOf(0, 0), openGets())
        }
    }
}
