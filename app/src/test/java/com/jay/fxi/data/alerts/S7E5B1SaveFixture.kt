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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * The shared rig of the S7 E5b1 owner save contracts (`R4c/S7/s7e5/s7e5b1_api_agreed.r1.md`): the S7E5A2 owner rig, every call
 * waiting for the test's answer; access, use authority, deletion store and floor are the production ones on virtual time.
 * The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class S7E5B1SaveFixture {
    val alice = AuthIdentityFence("user-a", 1)
    val alice2 = AuthIdentityFence("user-a", 2)
    val grantA = TopicGrantToken(11)
    val grantA2 = TopicGrantToken(12)
    val stamp = Instant.parse("2026-11-13T01:00:00Z")
    val later = Instant.parse("2026-11-13T02:00:00Z")
    val wire = NetworkModule.provideWireJson()

    fun setting(id: Int, enabled: Boolean = true, currency: String = "usd-krw", bank: String = "hana", repeat: Int? = null) =
        AlertSetting(id, "user-a", bank, currency, AlertCondition.ABOVE, 1400.0 + id, enabled, false, stamp, stamp, null, repeat)

    /** The server's own row: a later updatedAt marks that the whole row came from the answer. */
    fun server(id: Int, enabled: Boolean = true, currency: String = "usd-krw", bank: String = "hana", repeat: Int? = null) =
        setting(id, enabled, currency, bank, repeat).copy(updatedAt = later)

    val draft = BankAlertDraft("usd-krw", "kb", AlertCondition.BELOW, 1350.0, true, null)

    fun confirmed(id: AuthIdentityFence) = OwnedPremiumAccess(id.uid, id.authGeneration, PremiumAccessState.PremiumConfirmed)
    fun fenceFor(id: AuthIdentityFence, grant: TopicGrantToken) = TopicSessionFence(id, "epoch-1", grant)
    fun standing(grant: TopicGrantToken) = TopicAccessSnapshot.INITIAL.copy(
        revision = 1L,
        facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet())
    )

    // ---- scripted calls ----

    sealed interface Outcome {
        class Answer(val result: AuthBoundResult<*>) : Outcome
        class Throw(val error: Throwable) : Outcome
    }

    class Call(val kind: String, val identity: AuthIdentityFence, val id: Int?, val payload: Any?, val admitted: () -> Boolean) {
        val outcome = CompletableDeferred<Outcome>()
        var cancelled = false
        val open: Boolean get() = !outcome.isCompleted && !cancelled
    }

    fun okList(vararg rows: AlertSetting): Outcome = Outcome.Answer(AuthBoundResult<List<AlertSetting>>({}, Result.success(rows.toList())))
    fun okRow(row: AlertSetting): Outcome =
        Outcome.Answer(AuthBoundResult({}, Result.success(row), AlertRateLimitResponseEvidence(200, null)))

    fun http(status: Int, retryAfter: String? = null): Outcome {
        val kind = if (status == 404) AuthenticatedFailureKind.UNKNOWN_NOT_FOUND else AuthenticatedFailureKind.OTHER_HTTP
        val headers = if (retryAfter == null) headersOf() else headersOf("Retry-After", retryAfter)
        val failure = AuthenticatedHttpFailure(status, headers, ByteArray(0), null, null, kind)
        return Outcome.Answer(AuthBoundResult<Any>({}, Result.failure(mapAlertHttpFailure(failure)),
            AlertRateLimitResponseEvidence(status, retryAfter)))
    }

    fun json(request: Any?): JsonObject = when (request) {
        is AlertSettingUpdateRequest -> wire.parseToJsonElement(wire.encodeToString(AlertSettingUpdateRequest.serializer(), request)).jsonObject
        is AlertSettingRequest -> wire.parseToJsonElement(wire.encodeToString(AlertSettingRequest.serializer(), request)).jsonObject
        else -> error("no request: $request")
    }

    // ---- the rig ----

    inner class Rig(val test: TestScope, eager: Boolean = false) {
        /** S7 E5b1 gap hunt G20: a call that keeps waiting through its coroutine's cancellation, as a transport may. */
        var ignoresCancellation = false
        /** S7 E5b1 gap hunt G07: runs once at the next live identity read, inside the owner's own Main step. */
        var beforeIdentity: (() -> Unit)? = null
        var identity: AuthIdentityFence? = alice
        var fence: TopicSessionFence? = fenceFor(alice, grantA)
        var snapshot: TopicAccessSnapshot = standing(grantA)
        var release = true
        val deletions = DeletionAdmissionStore()
        val premium = MutableStateFlow(confirmed(alice))
        val access = AlertSettingsAccess(
            { beforeIdentity?.let { hook -> beforeIdentity = null; hook() }; identity }, { fence }, SnapshotTopicUseAuthority { snapshot },
            { premium.value }, { release },
            GraphProtectedAdmission({ identity }, deletions)
        )
        val floor = AlertRateLimitFloor({ test.testScheduler.currentTime })
        val calls = mutableListOf<Call>()

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
            CoroutineScope(job + if (eager) UnconfinedTestDispatcher(test.testScheduler) else StandardTestDispatcher(test.testScheduler)),
            { delay(it) },
            premium, MutableStateFlow(0L), MutableStateFlow(false),
            { who, id, enabled, admitted -> scripted(Call("PUT", who, id, enabled, admitted)) },
            { who, id, admitted -> scripted(Call("DELETE", who, id, null, admitted)) },
            { who, request, admitted -> scripted(Call("POST", who, null, request, admitted)) },
            { who, id, request, admitted -> scripted(Call("EDIT", who, id, request, admitted)) },
            RECHECK
        )

        /** Every manual outcome the owner emits, collected eagerly (S7 E5b1 gap hunt G23). */
        val outcomes = mutableListOf<ManualRefreshOutcome>()

        init {
            CoroutineScope(job + UnconfinedTestDispatcher(test.testScheduler)).launch { owner.manualOutcomes.collect { outcomes += it } }
        }

        val rows: List<AlertSetting>? get() = owner.state.value.settings
        fun settle() = test.runCurrent()
        fun advance(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }
        fun open(kind: String): List<Call> = calls.filter { it.kind == kind && it.open }
        fun count(kind: String): Int = calls.count { it.kind == kind }
        fun sent(kind: String): List<Call> = calls.filter { it.kind == kind }
        fun answer(kind: String, outcome: Outcome) {
            val pending = open(kind)
            check(pending.size == 1) { "exactly one $kind awaits its answer, not ${pending.size}" }
            pending.single().outcome.complete(outcome)
            settle()
        }
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
        fun switchToAlice2() {
            identity = alice2
            fence = fenceFor(alice2, grantA2)
            snapshot = standing(grantA2)
            premium.value = confirmed(alice2)
        }
        fun create(d: BankAlertDraft = draft): BankAlertSaveRequest = owner.addAlert(d).also { settle() }
        fun edit(id: Int, e: BankAlertEdit): BankAlertSaveRequest = owner.editAlert(id, e).also { settle() }
        fun toggle(id: Int, enabled: Boolean) {
            owner.setEnabled(id, enabled)
            settle()
        }
        fun delete(id: Int) {
            owner.delete(id)
            settle()
        }
        /** No GET is owed: one more foreground inside the 30 s skip sends nothing. */
        fun nothingOwed(label: String) {
            val before = count("GET")
            background()
            foreground(false)
            assertEquals("$label: no GET owed", before, count("GET"))
        }
        /** A GET is owed: the next foreground sends one even inside the 30 s skip. */
        fun owed(label: String) {
            val before = count("GET")
            background()
            foreground(false)
            assertEquals("$label: one GET owed", before + 1, count("GET"))
        }
        fun holdFloor(endpoint: AuthenticatedEndpoint, seconds: String) =
            floor.record(endpoint, AlertRateLimitEvidence.of(emptyList(), 429, seconds))
        fun close() = job.cancel()
    }

    fun rig(test: TestScope, eager: Boolean = false, body: Rig.() -> Unit) {
        val r = Rig(test, eager)
        try {
            r.body()
        } finally {
            r.close()
        }
    }

    fun notSent(reason: NotSentReason) = BankAlertSaveOutcome.NotSent(reason)
    fun rowsOf(n: Int, currency: (Int) -> String = { "usd-krw" }) = Array(n) { setting(it + 1, currency = currency(it + 1)) }

    val RECHECK = 1_000L
}
