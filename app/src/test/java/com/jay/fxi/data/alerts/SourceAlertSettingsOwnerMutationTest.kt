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
import com.jay.fxi.data.remote.dto.SourceAlertSettingRequest
import com.jay.fxi.data.remote.dto.SourceAlertSettingUpdateRequest
import com.jay.fxi.data.repository.AlertRateLimitResponseEvidence
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.mapAlertHttpFailure
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.SourceAlertSetting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import java.io.IOException
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8-5a (`R4c/S8/s8_5a_contract.agreed.r1.md` §2·§3 "source 전용 JVM"): the source owner's own write rules over the production
 * [AlertSettingsAccess] on virtual time, with remote writes that end only when the test answers them. The five exchange pairs
 * and the picker's order come from the one row policy; a reference or unknown draft or patch is Unsupported and sends nothing;
 * ids the GET did not adopt (another asset, another user, a repeated id) take no write; a source change of an allowed row is
 * sent to its new allowed target only and its answer must be that exact pair; work accepted against the row's earlier pair is
 * never re-bound after an edit changed it; another user's, another id's or another pair's answer is never saved; a definite
 * HTTP 400 keeps its typed failure; at 30 and 31 rows every row stays editable; the bank owner's same integer id is untouched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceAlertSettingsOwnerMutationTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val grant = TopicGrantToken(11)
    private val stamp = Instant.parse("2026-11-21T01:00:00Z")

    private fun row(id: Int, source: String = "upbit", asset: String = "usdt-krw", uid: String = "user-a",
                    threshold: Double = 1400.0, enabled: Boolean = true) =
        SourceAlertSetting(id, uid, source, asset, AlertCondition.ABOVE, threshold, enabled, false, null, stamp, stamp, null)

    private class Write(
        val kind: String,
        val id: Int?,
        val create: SourceAlertSettingRequest?,
        val update: SourceAlertSettingUpdateRequest?,
        val guard: () -> Boolean
    ) {
        val reply = CompletableDeferred<Result<Any>>()
        /** Set by [Rig.wire]: the answer first passes the transport's last send check, as a production send does. */
        var atWire = false
        var withheld = false
        var evidence: AlertRateLimitResponseEvidence? = null
        var thrown: Throwable? = null
        /** The result's own identity check, run by its fold. */
        var requireCurrent: () -> Unit = {}
    }

    private inner class Rig(val test: TestScope) {
        val premium = MutableStateFlow(OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.PremiumConfirmed))
        /** A use reissued for the same UID and generation: every use acquired before the increase no longer admits. */
        var invalidations = 0L
        val access = AlertSettingsAccess(
            { alice }, { TopicSessionFence(alice, "epoch-1", grant) },
            SnapshotTopicUseAuthority {
                TopicAccessSnapshot.INITIAL.copy(revision = 1L, userInvalidations = invalidations,
                    facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet()))
            },
            { premium.value }, { true }, GraphProtectedAdmission({ alice }, DeletionAdmissionStore())
        )
        val floor = AlertRateLimitFloor({ test.testScheduler.currentTime })
        val gets = mutableListOf<CompletableDeferred<List<SourceAlertSetting>>>()
        val writes = mutableListOf<Write>()
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + StandardTestDispatcher(test.testScheduler))

        private suspend fun <T> write(w: Write): AuthBoundResult<T> {
            writes += w
            val answer = withContext(NonCancellable) { w.reply.await() }
            if (w.atWire && !w.guard()) {
                // The transport's last send check refused it: nothing reached the server.
                w.withheld = true
                throw TopicUseWithheldException(emptyList())
            }
            w.thrown?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return AuthBoundResult(w.requireCurrent, answer as Result<T>, w.evidence)
        }

        val owner = SourceAlertSettingsOwner(
            { _, _ ->
                val get = CompletableDeferred<List<SourceAlertSetting>>().also { gets += it }
                AuthBoundResult({}, Result.success(withContext(NonCancellable) { get.await() }))
            },
            access, floor, AlertRetryPolicy(), { test.testScheduler.currentTime }, { 0.0 }, scope, { delay(it) }, premium, MutableStateFlow(0L),
            { _, request, guard -> write(Write("create", null, request, null, guard)) },
            { _, id, request, guard ->
                write(Write(if (request.threshold == null && request.source == null && request.isEnabled != null) "toggle" else "edit", id, null, request, guard))
            },
            { _, id, guard -> write(Write("delete", id, null, null, guard)) },
            1_000L
        )

        fun settle() = test.runCurrent()
        fun advance(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }
        fun ready(rows: List<SourceAlertSetting>) {
            owner.onForeground(false)
            settle()
            gets.single { !it.isCompleted }.complete(rows)
            settle()
        }
        fun open(kind: String) = writes.filter { it.kind == kind && !it.reply.isCompleted }
        fun reply(kind: String, result: Result<Any>) {
            open(kind).single().reply.complete(result)
            settle()
        }
        fun replyTo(kind: String, id: Int, result: Result<Any>) {
            open(kind).single { it.id == id }.reply.complete(result)
            settle()
        }
        /** The send reaches its last check now: refused there if its guard no longer admits, else answered with [result]. */
        fun wire(kind: String, result: Result<Any>) {
            val w = open(kind).single()
            w.atWire = true
            w.reply.complete(result)
            settle()
        }
        /** Answer with [result], carrying the response's rate-limit evidence or throwing [thrown] out of the remote. */
        fun answer(kind: String, result: Result<Any>, evidence: AlertRateLimitResponseEvidence? = null, thrown: Throwable? = null,
                   requireCurrent: () -> Unit = {}) {
            val w = open(kind).single()
            w.evidence = evidence
            w.thrown = thrown
            w.requireCurrent = requireCurrent
            w.reply.complete(result)
            settle()
        }
        fun replyWithEvidence(id: Int, evidence: AlertRateLimitResponseEvidence) {
            val w = writes.single { it.id == id && !it.reply.isCompleted }
            w.evidence = evidence
            w.reply.complete(Result.failure(http(evidence.statusCode)))
            settle()
        }
        fun openGets() = gets.count { !it.isCompleted }
        fun answerGet(rows: List<SourceAlertSetting>) {
            gets.single { !it.isCompleted }.complete(rows)
            settle()
        }
        fun ids() = owner.state.value.settings?.map { it.id }
        fun shown(id: Int) = owner.state.value.settings?.firstOrNull { it.id == id }
        fun close() {
            writes.filter { !it.reply.isCompleted }.forEach { it.reply.complete(Result.failure(java.io.IOException("rig closed"))) }
            gets.filter { !it.isCompleted }.forEach { it.complete(emptyList()) }
            test.runCurrent()
            job.cancel()
        }
    }

    private fun rig(test: TestScope, body: Rig.() -> Unit) {
        val r = Rig(test)
        try {
            // Invoked as `body(r)`, never `r.body()`, so that no member of Rig can be called in its place.
            body(r)
        } finally {
            r.close()
        }
    }

    private fun http(status: Int): Throwable =
        mapAlertHttpFailure(AuthenticatedHttpFailure(status, headersOf(), ByteArray(0), null, null, AuthenticatedFailureKind.OTHER_HTTP))

    private fun draft(source: String = "upbit", threshold: Double = 1500.0) = SourceAlertDraft(source, AlertCondition.ABOVE, threshold, true, null)

    private fun notSent(reason: NotSentReason) = SourceAlertSaveOutcome.NotSent(reason)

    @Test fun `S8_5A_S01 the five exchange pairs and the picker's order come from the one row policy`() {
        val policy = SourceAlertRowPolicy.EXCHANGES
        assertEquals("the picker", listOf("upbit", "bithumb", "coinone", "korbit", "gopax"), policy.sources(SourceAlertRowPolicy.SETTINGS_ASSET))
        for (source in policy.sources(SourceAlertRowPolicy.SETTINGS_ASSET)) assertTrue("$source is allowed", policy.admits(source, "usdt-krw"))
        for ((source, asset) in listOf("kb" to "usdt-krw", "hana" to "usdt-krw", "investing" to "usdt-krw", "UPBIT" to "usdt-krw",
                " upbit" to "usdt-krw", "krx" to "usd-krw-futures", "upbit" to "usd-krw-futures", "upbit" to "USDT-KRW")) {
            assertTrue("($source, $asset) is refused", !policy.admits(source, asset))
        }
        assertEquals("no source for another asset", emptyList<String>(), policy.sources("usd-krw-futures"))
    }

    @Test fun `S8_5A_S02 a reference or unknown draft or patch is Unsupported and reserves and sends nothing`() = runTest {
        rig(this) {
            ready((1..29).map { row(it) })
            for (source in listOf("kb", "hana", "investing", "UPBIT", "unknown")) {
                val h = owner.addAlert(SourceAlertDraft(source, AlertCondition.ABOVE, 1500.0, true, null))
                assertEquals("draft $source", SourceAlertSaveOutcome.Unsupported, h.outcome.value)
                val e = owner.editAlert(1, SourceAlertEdit(source = source))
                assertEquals("patch $source", SourceAlertSaveOutcome.Unsupported, e.outcome.value)
            }
            settle()
            assertEquals("nothing sent", 0, writes.size)
            assertEquals("and no place taken: an allowed add is still accepted at 29", SourceAlertSaveOutcome.Pending,
                owner.addAlert(SourceAlertDraft("gopax", AlertCondition.ABOVE, 1500.0, true, null)).outcome.value)
        }
    }

    @Test fun `S8_5A_S03 ids the GET did not adopt take no write`() = runTest {
        rig(this) {
            ready(listOf(row(1), row(2, asset = "usd-krw-futures"), row(3, uid = "user-b"), row(4), row(4, source = "bithumb"), row(5, source = "kb")))
            assertEquals("premise: only the allowed, unrepeated row of this user is adopted", listOf(1), owner.state.value.settings?.map { it.id })
            for (id in listOf(2, 3, 4, 5)) {
                assertEquals("edit $id", SourceAlertSaveOutcome.NotSent(NotSentReason.NOT_READY), owner.editAlert(id, SourceAlertEdit(threshold = 1.0)).outcome.value)
                owner.setEnabled(id, false)
                owner.delete(id)
            }
            settle()
            assertEquals("no write for any of them", 0, writes.size)
        }
        rig(this) {
            ready((1..29).map { row(it) } + listOf(row(30, asset = "usd-krw-futures"), row(31, uid = "user-b"), row(32),
                row(32, source = "kb"), row(33, source = "hana")))
            assertEquals("premise: the 29 allowed rows only", (1..29).toList(), ids())
            for (id in 30..33) {
                assertEquals("edit $id", notSent(NotSentReason.NOT_READY), owner.editAlert(id, SourceAlertEdit(threshold = 1.0)).outcome.value)
                owner.setEnabled(id, false)
                owner.delete(id)
            }
            settle()
            assertEquals("no write for a refused id", 0, writes.size)
            assertEquals("and no place taken by one: the 30th add is accepted", SourceAlertSaveOutcome.Pending, owner.addAlert(draft()).outcome.value)
        }
    }

    @Test fun `S8_5A_S04 a source change goes to its new allowed target and only that exact pair is adopted`() = runTest {
        rig(this) {
            ready(listOf(row(1)))
            val h = owner.editAlert(1, SourceAlertEdit(source = "bithumb")); settle()
            assertEquals("the PUT names the new source and no asset", listOf("bithumb", null), open("edit").single().update!!.let { listOf(it.source, it.asset) })
            reply("edit", Result.success(row(1, source = "coinone")))
            assertTrue("another allowed source in the answer is not saved", h.outcome.value is SourceAlertSaveOutcome.Failed)
            assertEquals("and the row keeps its pair", "upbit", owner.state.value.settings?.single()?.source)
            answerGet(listOf(row(1, threshold = 1450.0)))
            assertEquals("premise: the canonical row is adopted", listOf("upbit" to 1450.0), owner.state.value.settings?.map { it.source to it.threshold })
            val ok = owner.editAlert(1, SourceAlertEdit(source = "bithumb")); settle()
            reply("edit", Result.success(row(1, source = "bithumb")))
            assertTrue("the exact target is saved", ok.outcome.value is SourceAlertSaveOutcome.Saved)
            assertEquals("bithumb", owner.state.value.settings?.single()?.source)
        }
    }

    @Test fun `S8_5A_S05 work accepted against the row's earlier pair is never re-bound after an edit changed it`() = runTest {
        rig(this) {
            ready(listOf(row(1), row(2)))
            owner.editAlert(1, SourceAlertEdit(source = "korbit")); settle()
            owner.setEnabled(1, false); settle()
            val waiting = owner.editAlert(1, SourceAlertEdit(threshold = 1500.0)); settle()
            reply("edit", Result.success(row(1, source = "korbit")))
            assertEquals("the waiting edit of the old pair ends unsent", SourceAlertSaveOutcome.NotSent(NotSentReason.SUPERSEDED), waiting.outcome.value)
            assertEquals("no toggle and no second edit are sent", listOf("edit"), writes.map { it.kind })
            assertEquals("the row shows its new pair as answered", listOf("korbit", true),
                owner.state.value.settings?.first { it.id == 1 }?.let { listOf(it.source, it.isEnabled) })
            assertEquals("the dropped work owes one canonical GET", 1, openGets())
            owner.editAlert(2, SourceAlertEdit(source = "gopax")); settle()
            owner.delete(2); settle()
            reply("edit", Result.success(row(2, source = "gopax")))
            assertEquals("a delete of the old pair is not sent either", listOf("edit", "edit"), writes.map { it.kind })
            assertEquals("and the row is shown again with its new pair", "gopax", owner.state.value.settings?.firstOrNull { it.id == 2 }?.source)
            val add = owner.addAlert(draft("gopax")); settle()
            reply("create", Result.success(row(2, source = "gopax", threshold = 1500.0)))
            assertTrue("the dropped delete's reservation is gone: a create answered with its id updates it in place",
                add.outcome.value is SourceAlertSaveOutcome.Saved)
        }
    }

    @Test fun `S8_5A_S06 another user's, another id's or another pair's answer is never saved`() = runTest {
        for ((label, answer) in listOf(
            "another user" to row(1, uid = "user-b", threshold = 1500.0),
            "another id" to row(7, threshold = 1500.0),
            "another asset" to row(1, asset = "usd-krw-futures", threshold = 1500.0))) rig(this) {
            ready(listOf(row(1)))
            val h = owner.editAlert(1, SourceAlertEdit(threshold = 1500.0)); settle()
            reply("edit", Result.success(answer))
            assertTrue("$label: not saved", h.outcome.value is SourceAlertSaveOutcome.Failed)
            assertEquals("$label: the row is unchanged", 1400.0, owner.state.value.settings?.single()?.threshold)
        }
        rig(this) {
            ready(listOf(row(1)))
            val h = owner.addAlert(SourceAlertDraft("upbit", AlertCondition.ABOVE, 1500.0, true, null)); settle()
            reply("create", Result.success(row(9, source = "bithumb", threshold = 1500.0)))
            assertTrue("a create answered with another pair is not saved", h.outcome.value is SourceAlertSaveOutcome.Failed)
            assertEquals("and not adopted", listOf(1), owner.state.value.settings?.map { it.id })
        }
        rig(this) {
            ready(listOf(row(1)))
            val h = owner.addAlert(draft()); settle()
            reply("create", Result.success(row(9, uid = "user-b", threshold = 1500.0)))
            assertTrue("a create answered for another user is not saved", h.outcome.value is SourceAlertSaveOutcome.Failed)
            assertEquals("and not adopted", listOf(1), ids())
        }
        rig(this) {
            ready(listOf(row(1)))
            owner.setEnabled(1, false); settle()
            reply("toggle", Result.success(row(1, source = "bithumb", enabled = false)))
            assertEquals("a toggle answered with another pair is not adopted", "upbit", shown(1)?.source)
            assertEquals("and is read again", 1, openGets())
            answerGet(listOf(row(1)))
            assertEquals("the canonical row decides", listOf("upbit", true), shown(1)?.let { listOf(it.source, it.isEnabled) })
        }
    }

    @Test fun `S8_5A_S07 a definite HTTP 400 keeps its typed failure, and an unknown result stays a failure to settle`() = runTest {
        rig(this) {
            ready(listOf(row(1)))
            val h = owner.addAlert(SourceAlertDraft("upbit", AlertCondition.ABOVE, 1500.0, true, null)); settle()
            reply("create", Result.failure(http(400)))
            val failure = (h.outcome.value as SourceAlertSaveOutcome.Failed).failure as AlertRepositoryException
            assertEquals("the typed status", 400, failure.httpFailure?.statusCode)
            assertEquals("no canonical GET is owed for a definite rejection", 0, gets.count { !it.isCompleted })
        }
        rig(this) {
            ready(listOf(row(1)))
            val h = owner.addAlert(SourceAlertDraft("upbit", AlertCondition.ABOVE, 1500.0, true, null)); settle()
            reply("create", Result.failure(AlertRepositoryException("net", failureKind = AlertRepositoryFailureKind.NETWORK)))
            assertTrue("an unknown result", h.outcome.value is SourceAlertSaveOutcome.Failed)
            assertEquals("is settled by one canonical GET", 1, gets.count { !it.isCompleted })
        }
    }

    @Test fun `S8_5A_S08 at 30 and 31 rows the add is refused and every row stays editable`() = runTest {
        for (n in listOf(30, 31)) rig(this) {
            ready((1..n).map { row(it) })
            assertEquals("$n: add refused", SourceAlertSaveOutcome.NotSent(NotSentReason.CAP),
                owner.addAlert(SourceAlertDraft("upbit", AlertCondition.ABOVE, 1500.0, true, null)).outcome.value)
            val edit = owner.editAlert(n, SourceAlertEdit(threshold = 1500.0)); settle()
            reply("edit", Result.success(row(n, threshold = 1500.0)))
            assertEquals("$n: the edit is saved with its row", 1500.0, (edit.outcome.value as SourceAlertSaveOutcome.Saved).row.threshold, 0.0)
            owner.setEnabled(n, false); settle()
            reply("toggle", Result.success(row(n, threshold = 1500.0, enabled = false)))
            assertEquals("$n: the toggle's answer is shown", listOf(1500.0, false), shown(n)?.let { listOf(it.threshold, it.isEnabled) })
            owner.delete(n); settle()
            reply("delete", Result.success(Unit))
            assertEquals("$n: the last row was edited, toggled and deleted", listOf("edit", "toggle", "delete"), writes.map { it.kind })
            assertEquals("$n: each write named it", listOf(n, n, n), writes.map { it.id })
            assertEquals("$n: and only it is gone", (1 until n).toList(), ids())
        }
    }

    @Test fun `S8_5A_S09 the bank owner's same integer id is untouched by source writes`() = runTest {
        rig(this) {
            val bankWrites = mutableListOf<String>()
            val bankGets = mutableListOf<CompletableDeferred<List<AlertSetting>>>()
            val bank = BankAlertSettingsOwner(
                { _, _ -> AuthBoundResult({}, Result.success(CompletableDeferred<List<AlertSetting>>().also { bankGets += it }.await())) },
                access, floor, AlertRetryPolicy(), { testScheduler.currentTime }, { 0.0 }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                { delay(it) }, premium, MutableStateFlow(0L), MutableStateFlow(false),
                { _, _, _, _ -> bankWrites += "toggle"; error("bank: no write") }, { _, _, _ -> bankWrites += "delete"; error("bank: no write") },
                { _, _, _ -> bankWrites += "create"; error("bank: no write") }, { _, _, _, _ -> bankWrites += "edit"; error("bank: no write") }
            )
            bank.onForeground(false); settle()
            bankGets.single().complete(listOf(AlertSetting(1, "user-a", "hana", "usd-krw", AlertCondition.ABOVE, 1400.0, true, false, stamp, stamp, null, null)))
            settle()
            ready(listOf(row(1)))
            owner.setEnabled(1, false); settle()
            reply("toggle", Result.success(row(1, enabled = false)))
            owner.delete(1); settle()
            reply("delete", Result.success(Unit))
            assertEquals("no bank write", emptyList<String>(), bankWrites)
            assertEquals("and the bank row is as it was", listOf(1, true), bank.state.value.settings?.single()?.let { listOf(it.id, it.isEnabled) })
            assertEquals("and no further bank GET", 1, bankGets.size)
            bank.onBackground(); settle()
        }
        rig(this) {
            val bankJob = SupervisorJob()
            val bankGets = mutableListOf<CompletableDeferred<List<AlertSetting>>>()
            val bank = BankAlertSettingsOwner(
                { _, _ -> AuthBoundResult({}, Result.success(CompletableDeferred<List<AlertSetting>>().also { bankGets += it }.await())) },
                access, floor, AlertRetryPolicy(), { testScheduler.currentTime }, { 0.0 }, CoroutineScope(bankJob + StandardTestDispatcher(testScheduler)),
                { delay(it) }, premium, MutableStateFlow(0L), MutableStateFlow(false),
                { _, _, _, _ -> error("bank: no write") }, { _, _, _ -> error("bank: no write") },
                { _, _, _ -> error("bank: no write") }, { _, _, _, _ -> error("bank: no write") }
            )
            bank.onForeground(false); settle()
            bankGets.single().complete((1..29).map { AlertSetting(it, "user-a", "hana", "usd-krw", AlertCondition.ABOVE, 1400.0, true, false, stamp, stamp, null, null) })
            settle()
            ready(listOf(row(1)))
            owner.addAlert(draft()); settle()
            assertEquals("premise: the source POST awaits its answer", 1, open("create").size)
            val bankAdd = bank.addAlert(BankAlertDraft("usd-krw", "hana", AlertCondition.ABOVE, 1500.0, true, null))
            assertEquals("the bank's 30th place is its own: the source reservation takes none of it", BankAlertSaveOutcome.Pending, bankAdd.outcome.value)
            bankJob.cancel()
        }
    }

    /**
     * S8-5a impl review r1 A1: a send its transport refused at the last check never reached the server, whatever the original
     * use is now. A create frees its place at once and ends unsent, an edit ends unsent, a delete frees its reservation and its
     * row comes back; one the server received and did not answer stays unknown and holds its place.
     */
    @Test fun `S8_5A_S10 a send refused at its last check is not sent and holds nothing, a received one stays unknown`() = runTest {
        rig(this) {
            ready((1..29).map { row(it) })
            val h = owner.addAlert(draft()); settle()
            invalidations++
            wire("create", Result.success(row(30)))
            assertTrue("premise: refused at the wire", writes.single().withheld)
            assertEquals("the create ends unsent", notSent(NotSentReason.UNADMITTED), h.outcome.value)
            assertEquals("and owes no canonical GET", 0, openGets())
            assertEquals("its place is free at once", SourceAlertSaveOutcome.Pending, owner.addAlert(draft()).outcome.value)
        }
        rig(this) {
            ready((1..29).map { row(it) })
            val h = owner.addAlert(draft()); settle()
            invalidations++
            reply("create", Result.failure(IOException("received, then dropped")))
            assertTrue("control: a received send is unknown", h.outcome.value is SourceAlertSaveOutcome.Failed)
            assertEquals("control: and holds its place", notSent(NotSentReason.CAP), owner.addAlert(draft()).outcome.value)
        }
        rig(this) {
            ready(listOf(row(1)))
            val h = owner.editAlert(1, SourceAlertEdit(threshold = 1500.0)); settle()
            invalidations++
            wire("edit", Result.success(row(1, threshold = 1500.0)))
            assertEquals("an edit refused at the wire ends unsent", notSent(NotSentReason.UNADMITTED), h.outcome.value)
            assertEquals("and the row is as approved", 1400.0, shown(1)?.threshold)
        }
        for ((label, end) in listOf<Pair<String, Rig.() -> Unit>>(
            "refused at the wire" to { invalidations++; wire("delete", Result.success(Unit)) },
            "rejected with 400" to { reply("delete", Result.failure(http(400))) })) rig(this) {
            ready(listOf(row(1), row(2), row(3)))
            owner.delete(2); settle()
            assertEquals("premise: hidden at once", listOf(1, 3), ids())
            end(this)
            assertEquals("$label: the row is back in its place", listOf(1, 2, 3), ids())
            // A delete reservation is hidden by the shown id in the cap; a create's overlap check is where it would still act.
            val add = owner.addAlert(draft()); settle()
            reply("create", Result.success(row(2, threshold = 1500.0)))
            assertTrue("$label: its reservation is gone, so a create answered with its id updates it in place",
                add.outcome.value is SourceAlertSaveOutcome.Saved)
            assertEquals("$label: in its place", listOf(1400.0, 1500.0, 1400.0), owner.state.value.settings?.map { it.threshold })
            owner.delete(2); settle()
            assertEquals("$label: and the row takes a new delete", 1, open("delete").size)
        }
    }

    /**
     * S8-5a impl review r1 A2: work accepted in one foreground is bound to it. A background closes it for good: a worker that
     * did not run before the return sends nothing, and a send that was still in the transport is refused at its last check.
     */
    @Test fun `S8_5A_S11 work accepted before a background is never sent after the return`() = runTest {
        rig(this) {
            ready(listOf(row(1), row(2), row(3)))
            val add = owner.addAlert(draft())
            owner.setEnabled(1, false)
            owner.delete(2)
            val edit = owner.editAlert(3, SourceAlertEdit(threshold = 1500.0))
            owner.onBackground()
            owner.onForeground(false)
            settle()
            assertEquals("nothing is sent after the return", 0, writes.size)
            assertEquals("the create ends unsent", notSent(NotSentReason.BACKGROUND), add.outcome.value)
            assertEquals("the edit ends unsent", notSent(NotSentReason.BACKGROUND), edit.outcome.value)
            assertEquals("every row is shown as approved", listOf(1 to true, 2 to true, 3 to true),
                owner.state.value.settings?.map { it.id to it.isEnabled })
            assertEquals("one canonical GET settles the dropped work", 1, openGets())
        }
        for (kind in listOf("create", "edit", "toggle", "delete")) rig(this) {
            ready(listOf(row(1)))
            val handle = when (kind) {
                "create" -> owner.addAlert(draft())
                "edit" -> owner.editAlert(1, SourceAlertEdit(threshold = 1500.0))
                "toggle" -> { owner.setEnabled(1, false); null }
                else -> { owner.delete(1); null }
            }
            settle()
            owner.onBackground(); settle()
            owner.onForeground(false); settle()
            wire(kind, Result.success(if (kind == "delete") Unit else row(if (kind == "create") 9 else 1, enabled = kind != "toggle")))
            assertTrue("$kind: refused at its last check after the return", writes.single().withheld)
            if (handle != null) assertEquals("$kind: ends unsent", notSent(NotSentReason.BACKGROUND), handle.outcome.value)
            assertEquals("$kind: the approved list is shown", listOf(1 to true), owner.state.value.settings?.map { it.id to it.isEnabled })
        }
    }

    /** S8-5a impl review r1 A3: an accepted toggle to the value already shown still makes a GET that raced it stale. */
    @Test fun `S8_5A_S12 a toggle to the shown value still makes a racing GET stale`() = runTest {
        rig(this) {
            ready(listOf(row(1, enabled = true)))
            owner.authoritative(); settle()
            assertEquals("premise: a GET is in flight", 1, openGets())
            owner.setEnabled(1, true); settle()
            reply("toggle", Result.success(row(1, enabled = true)))
            assertEquals("premise: the shown row did not change", true, shown(1)?.isEnabled)
            answerGet(listOf(row(1, enabled = false)))
            assertEquals("the GET that started before the write is not adopted", true, shown(1)?.isEnabled)
            assertEquals("and the chain reads again", 1, openGets())
        }
    }

    /**
     * S8-5a impl review r1 A4: once an edit that may have changed the row's pair ends without a confirmed answer, the delete
     * accepted against the old pair is dropped, never sent, and not replayed after the canonical list. An unchanged pair or a
     * definite 400 lets it go on.
     */
    @Test fun `S8_5A_S13 a delete accepted before an uncertain source change is never sent`() = runTest {
        for ((label, answer) in listOf<Pair<String, Result<Any>>>(
            "no answer" to Result.failure(IOException("received, then dropped")),
            "another allowed pair" to Result.success(row(1, source = "coinone")))) rig(this) {
            ready(listOf(row(1), row(2)))
            owner.editAlert(1, SourceAlertEdit(source = "korbit")); settle()
            owner.delete(1); settle()
            reply("edit", answer)
            assertEquals("$label: no DELETE follows", listOf("edit"), writes.map { it.kind })
            assertEquals("$label: the row stays hidden until the canonical list", listOf(2), ids())
            assertEquals("$label: one canonical GET settles both", 1, openGets())
            answerGet(listOf(row(1, source = "korbit"), row(2)))
            assertEquals("$label: the canonical row is shown", listOf(1, 2), ids())
            assertEquals("$label: and the old delete is not replayed", listOf("edit"), writes.map { it.kind })
        }
        rig(this) {
            ready((1..30).map { row(it) })
            owner.editAlert(30, SourceAlertEdit(source = "korbit")); settle()
            owner.delete(30); settle()
            reply("edit", Result.failure(IOException("received, then dropped")))
            assertEquals("premise: hidden, no DELETE", (1..29).toList() to listOf("edit"), ids() to writes.map { it.kind })
            assertEquals("the dropped delete's place still counts until the canonical list", notSent(NotSentReason.CAP),
                owner.addAlert(draft()).outcome.value)
        }
        for ((label, edit, answer) in listOf<Triple<String, SourceAlertEdit, Result<Any>>>(
            Triple("an unchanged pair", SourceAlertEdit(threshold = 1500.0), Result.failure(IOException("received, then dropped"))),
            Triple("a definitely rejected source change", SourceAlertEdit(source = "korbit"), Result.failure(http(400))))) rig(this) {
            ready(listOf(row(1), row(2)))
            owner.editAlert(1, edit); settle()
            owner.delete(1); settle()
            reply("edit", answer)
            assertEquals("control, $label: the delete is sent after it", listOf("edit", "delete"), writes.map { it.kind })
            assertEquals("control, $label: for its row", 1, open("delete").single().id)
        }
    }

    /** S8-5a impl review r1 A5: a rejected delete comes back after its nearest approved neighbour, whatever moved meanwhile. */
    @Test fun `S8_5A_S14 rejected deletes come back in the approved order`() = runTest {
        rig(this) {
            ready(listOf(row(1), row(2), row(3, threshold = 1450.0)))
            owner.delete(1); settle()
            owner.delete(2); settle()
            assertEquals("premise: both hidden", listOf(3), ids())
            replyTo("delete", 1, Result.failure(http(400)))
            replyTo("delete", 2, Result.failure(http(400)))
            assertEquals("before the canonical list, the approved order", listOf(1, 2, 3), ids())
            assertEquals("and the rows' values", listOf(1400.0, 1400.0, 1450.0), owner.state.value.settings?.map { it.threshold })
            assertEquals("premise: the canonical GET is still owed", 1, openGets())
        }
        rig(this) {
            ready(listOf(row(1), row(2), row(3)))
            owner.delete(2); settle()
            owner.addAlert(draft()); settle()
            reply("create", Result.success(row(9, threshold = 1500.0)))
            assertEquals("premise: the created row leads", listOf(9, 1, 3), ids())
            reply("delete", Result.failure(http(400)))
            assertEquals("the rejected delete returns after its approved neighbour", listOf(9, 1, 2, 3), ids())
        }
    }

    /**
     * S8-5a impl review r1 A6: a hidden row whose delete awaits the canonical list still holds its id, so a create answered with
     * it overlaps: no success, the row stays hidden, the create keeps its place, and one canonical list settles both. A shown
     * row with no work and no reservation is still updated in place.
     */
    @Test fun `S8_5A_S15 a create answered with a hidden delete's id overlaps it`() = runTest {
        rig(this) {
            ready((1..29).map { row(it) })
            owner.delete(29); settle()
            reply("delete", Result.failure(IOException("received, then dropped")))
            assertEquals("premise: hidden until the canonical list", (1..28).toList(), ids())
            assertEquals("premise: the canonical GET is in flight", 1, openGets())
            val add = owner.addAlert(draft()); settle()
            reply("create", Result.success(row(29)))
            assertTrue("no success", add.outcome.value is SourceAlertSaveOutcome.Failed)
            assertEquals("the hidden row is not shown again", (1..28).toList(), ids())
            assertEquals("the create keeps its place", notSent(NotSentReason.CAP), owner.addAlert(draft()).outcome.value)
            answerGet((1..29).map { row(it) })
            answerGet((1..28).map { row(it) } + row(30))
            assertEquals("the canonical list settles both", (1..28).toList() + 30, ids())
            assertEquals("once", 0, openGets())
        }
        rig(this) {
            ready(listOf(row(1), row(2), row(3)))
            val add = owner.addAlert(draft()); settle()
            reply("create", Result.success(row(3, threshold = 1500.0)))
            assertTrue("control: a shown row with no work is updated in place", add.outcome.value is SourceAlertSaveOutcome.Saved)
            assertEquals("control: in its place", listOf(1400.0, 1400.0, 1500.0), owner.state.value.settings?.map { it.threshold })
            owner.delete(3); settle()
            reply("delete", Result.failure(http(400)))
            assertEquals("control: a later rejected delete returns it to its approved place", listOf(1, 2, 3), ids())
            assertEquals("control: with its new value", 1500.0, shown(3)?.threshold ?: 0.0, 0.0)
        }
    }

    /** S8-5a impl review r1 (a): only HTTP 400 is a definite rejection; another status keeps the create's place to the canonical GET. */
    @Test fun `S8_5A_S16 a create rejected with another status than 400 stays unknown and holds its place`() = runTest {
        for (status in listOf(403, 408, 409, 422, 500)) rig(this) {
            ready((1..29).map { row(it) })
            val h = owner.addAlert(draft()); settle()
            reply("create", Result.failure(http(status)))
            assertTrue("$status: a failure", h.outcome.value is SourceAlertSaveOutcome.Failed)
            assertEquals("$status: the place is held", notSent(NotSentReason.CAP), owner.addAlert(draft()).outcome.value)
            assertEquals("$status: one canonical GET is owed", 1, openGets())
        }
    }

    /** S8-5a impl review r1 A7(7): an old-pair toggle whose target the answer happens to show is dropped with its debt, not consumed. */
    @Test fun `S8_5A_S17 an old-pair toggle the answer matches is dropped and owes the canonical list`() = runTest {
        rig(this) {
            ready(listOf(row(1, enabled = true)))
            owner.editAlert(1, SourceAlertEdit(source = "korbit")); settle()
            owner.setEnabled(1, true); settle()
            reply("edit", Result.success(row(1, source = "korbit", enabled = true)))
            assertEquals("no toggle is sent", listOf("edit"), writes.map { it.kind })
            assertEquals("the dropped toggle owes one canonical GET", 1, openGets())
        }
    }

    /**
     * S8-5a impl review r1 B: each verb's response evidence reaches its own floor before any ownership, admission or fold check,
     * extends it to the longer wait, and leaves the other endpoints open; an identity change hands over its exchanges first and
     * falls back to its status; a withheld send with exchanges hands them over; no response, a success or a decode failure
     * leave every floor at zero.
     */
    @Test fun `S8_5A_S18 each verb's evidence lands on its own floor before any check`() = runTest {
        val endpoints = mapOf(
            "create" to AuthenticatedEndpoint.SOURCE_NOTIFICATION_SETTINGS,
            "edit" to AuthenticatedEndpoint.UPDATE_SOURCE_NOTIFICATION_SETTING,
            "delete" to AuthenticatedEndpoint.DELETE_SOURCE_NOTIFICATION_SETTING)
        for ((kind, endpoint) in endpoints) rig(this) {
            ready(listOf(row(1)))
            fun start() {
                when (kind) {
                    "create" -> owner.addAlert(draft())
                    "edit" -> owner.editAlert(1, SourceAlertEdit(threshold = 1500.0))
                    else -> owner.delete(1)
                }
                settle()
            }
            start()
            answer(kind, Result.failure(http(429)), AlertRateLimitResponseEvidence(429, "5"))
            assertEquals("$kind: its floor", 5_000L, floor.remainingMillis(endpoint))
            for (other in endpoints.values - endpoint + listOf(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING,
                    AuthenticatedEndpoint.SOURCE_NOTIFICATION_LOGS)) {
                assertEquals("$kind: $other stays open", 0L, floor.remainingMillis(other))
            }
            floor.record(endpoint, AlertRateLimitEvidence.of(emptyList(), 429, "2"))
            assertEquals("$kind: a shorter wait never shortens it", 5_000L, floor.remainingMillis(endpoint))
            start()
            advance(4_999)
            assertEquals("$kind: nothing is sent before the deadline", 0, open(kind).size)
            advance(1)
            assertEquals("$kind: one send at it", 1, open(kind).size)
        }
        rig(this) {
            ready(listOf(row(1)))
            val created = owner.addAlert(draft()); settle()
            premium.value = OwnedPremiumAccess("user-b", 1, PremiumAccessState.PremiumConfirmed); settle()
            assertTrue("premise: the account switch ended the create", created.outcome.value is SourceAlertSaveOutcome.Failed)
            answer("create", Result.failure(http(429)), AlertRateLimitResponseEvidence(429, "7"))
            assertEquals("a late answer's evidence still lands", 7_000L, floor.remainingMillis(AuthenticatedEndpoint.SOURCE_NOTIFICATION_SETTINGS))
        }
        for ((label, thrown, expected) in listOf(
            Triple("an identity change with exchanges", AuthIdentityChangedException(429, "3", listOf(HttpExchangeEvidence(1, 1, 429, "9"))), 9_000L),
            Triple("an identity change without exchanges", AuthIdentityChangedException(429, "3"), 3_000L),
            Triple("a withheld send with exchanges", TopicUseWithheldException(listOf(HttpExchangeEvidence(1, 1, 429, "4"))), 4_000L),
            Triple("no response", IOException("no response"), 0L),
            Triple("a cancelled send", kotlinx.coroutines.CancellationException("cancelled"), 0L))) rig(this) {
            ready(listOf(row(1)))
            owner.editAlert(1, SourceAlertEdit(threshold = 1500.0)); settle()
            answer("edit", Result.success(Unit), thrown = thrown)
            assertEquals(label, expected, floor.remainingMillis(AuthenticatedEndpoint.UPDATE_SOURCE_NOTIFICATION_SETTING))
        }
        rig(this) {
            ready(listOf(row(1), row(2), row(3)))
            val endpoint = AuthenticatedEndpoint.UPDATE_SOURCE_NOTIFICATION_SETTING
            owner.editAlert(1, SourceAlertEdit(threshold = 1500.0))
            owner.editAlert(2, SourceAlertEdit(threshold = 1500.0))
            settle()
            replyWithEvidence(1, AlertRateLimitResponseEvidence(429, "2"))
            advance(1_000)
            replyWithEvidence(2, AlertRateLimitResponseEvidence(429, "5"))
            owner.editAlert(3, SourceAlertEdit(threshold = 1500.0)); settle()
            advance(4_999)
            assertEquals("a later, longer answer extends the floor: nothing before its deadline", 0, open("edit").size)
            advance(1)
            assertEquals("one send at it", 1, open("edit").size)
            assertEquals("premise: the floor is clear", 0L, floor.remainingMillis(endpoint))
        }
        rig(this) {
            ready(listOf(row(1)))
            owner.editAlert(1, SourceAlertEdit(threshold = 1500.0)); settle()
            answer("edit", Result.success(row(1, threshold = 1500.0)), AlertRateLimitResponseEvidence(429, "7"),
                requireCurrent = { throw AuthIdentityChangedException() })
            assertEquals("evidence recorded before a fold that throws is kept", 7_000L,
                floor.remainingMillis(AuthenticatedEndpoint.UPDATE_SOURCE_NOTIFICATION_SETTING))
        }
        for ((label, result) in listOf<Pair<String, Result<Any>>>(
            "a success" to Result.success(row(1, threshold = 1500.0)),
            "a decode failure" to Result.failure(AlertRepositoryException("decode", failureKind = AlertRepositoryFailureKind.DECODE)))) rig(this) {
            ready(listOf(row(1)))
            owner.editAlert(1, SourceAlertEdit(threshold = 1500.0)); settle()
            answer("edit", result, AlertRateLimitResponseEvidence(200, "30"))
            assertEquals("$label: no floor, even with a Retry-After", 0L, floor.remainingMillis(AuthenticatedEndpoint.UPDATE_SOURCE_NOTIFICATION_SETTING))
        }
    }

    /**
     * S8-5a impl review r2 A1: a waiting delete whose worker first runs in the background frees what it added and shows its
     * row again in the same Main step, so the return finds every row and its place counted.
     */
    @Test fun `S8_5A_S19 a delete whose worker runs in the background shows its row again and keeps the count`() = runTest {
        rig(this) {
            ready((1..30).map { row(it) })
            owner.delete(30)
            owner.onBackground(); settle()
            owner.onForeground(false); settle()
            assertEquals("no DELETE", 0, writes.size)
            assertEquals("every row in its order", (1..30).toList(), ids())
            assertEquals("its place still counts", notSent(NotSentReason.CAP), owner.addAlert(draft()).outcome.value)
            assertEquals("one canonical GET", 1, openGets())
        }
    }

    /** S8-5a impl review r2 A1(iv): a toggle refused before the wire is dropped and the approved value is shown again. */
    @Test fun `S8_5A_S20 a toggle refused before the wire shows the approved value again`() = runTest {
        rig(this) {
            ready(listOf(row(1, enabled = true)))
            owner.setEnabled(1, false)
            assertEquals("premise: shown at once", false, shown(1)?.isEnabled)
            invalidations++
            settle()
            assertEquals("no PUT", 0, writes.size)
            assertEquals("the approved value before the canonical list", true, shown(1)?.isEnabled)
        }
    }

    /**
     * S8-5a impl review r2 A3: a second delete of the same id never frees the place an earlier, unresolved delete holds:
     * a create answered with that id overlaps it, the row keeps its value, and the create keeps its own place.
     */
    @Test fun `S8_5A_S21 a second delete of the same id keeps the earlier unresolved delete's place`() = runTest {
        rig(this) {
            ready((1..29).map { row(it) })
            owner.delete(29); settle()
            reply("delete", Result.failure(http(500)))
            assertEquals("premise: the row is shown again, its delete unresolved", (1..29).toList(), ids())
            owner.delete(29); settle()
            reply("delete", Result.failure(http(400)))
            val add = owner.addAlert(draft()); settle()
            reply("create", Result.success(row(29, threshold = 1500.0)))
            assertTrue("the earlier delete still holds the id: no success", add.outcome.value is SourceAlertSaveOutcome.Failed)
            assertEquals("the row keeps its value", 1400.0, shown(29)?.threshold ?: 0.0, 0.0)
            assertEquals("and the create keeps its place", notSent(NotSentReason.CAP), owner.addAlert(draft()).outcome.value)
            var reads = 0
            while (openGets() > 0 && reads++ < 3) answerGet((1..29).map { row(it) })
            assertEquals("the canonical list frees both", SourceAlertSaveOutcome.Pending, owner.addAlert(draft()).outcome.value)
        }
    }

    /**
     * S8-5a impl review r2 A4: a definite 400 that arrives after the use alone was reissued (same UID and generation) is still
     * a rejection: the create's typed failure and freed place, the delete's freed reservation.
     */
    @Test fun `S8_5A_S22 a 400 after the use alone was reissued is still a definite rejection`() = runTest {
        rig(this) {
            ready((1..29).map { row(it) })
            val h = owner.addAlert(draft()); settle()
            invalidations++
            reply("create", Result.failure(http(400)))
            val outcome = h.outcome.value
            assertTrue("a failure, not an unknown result", outcome is SourceAlertSaveOutcome.Failed &&
                (outcome.failure as? AlertRepositoryException)?.httpFailure?.statusCode == 400)
            assertEquals("its place is free", SourceAlertSaveOutcome.Pending, owner.addAlert(draft()).outcome.value)
        }
        rig(this) {
            ready(listOf(row(1), row(2), row(3)))
            owner.delete(2); settle()
            invalidations++
            reply("delete", Result.failure(http(400)))
            assertEquals("the rejected delete's row is back", listOf(1, 2, 3), ids())
            val add = owner.addAlert(draft()); settle()
            reply("create", Result.success(row(2, threshold = 1500.0)))
            assertTrue("its reservation is gone", add.outcome.value is SourceAlertSaveOutcome.Saved)
        }
    }

    /**
     * S8-5a impl review r2 A5: when a send of an ended foreground is refused at its last check, only it ends unsent; work
     * accepted for the same row after the return goes once under its own check.
     */
    @Test fun `S8_5A_S23 an earlier foreground's refused send leaves work accepted after the return`() = runTest {
        for (later in listOf("edit", "toggle")) rig(this) {
            ready(listOf(row(1)))
            val first = owner.editAlert(1, SourceAlertEdit(threshold = 1500.0)); settle()
            owner.onBackground(); settle()
            owner.onForeground(false); settle()
            val second = if (later == "edit") owner.editAlert(1, SourceAlertEdit(threshold = 1600.0)) else null
            if (later == "toggle") owner.setEnabled(1, false)
            settle()
            wire("edit", Result.success(row(1, threshold = 1500.0)))
            assertEquals("$later: the first ends unsent", notSent(NotSentReason.BACKGROUND), first.outcome.value)
            assertTrue("$later: and never reached the wire", writes.first().withheld)
            assertEquals("$later: the later work is sent", 1, open(later).size)
            val next = open(later).single()
            if (later == "edit") {
                assertEquals("its patch", 1600.0, next.update?.threshold ?: 0.0, 0.0)
                reply("edit", Result.success(row(1, threshold = 1600.0)))
                assertTrue("and saved", second?.outcome?.value is SourceAlertSaveOutcome.Saved)
            } else {
                assertEquals("its value", false, next.update?.isEnabled)
                reply("toggle", Result.success(row(1, enabled = false)))
                assertEquals("and shown", false, shown(1)?.isEnabled)
            }
            assertEquals("$later: two remote calls in all", 2, writes.size)
        }
    }

    /**
     * S8-5a impl review r2 A6: once a source change ended without a confirmed answer, the row's pair is unknown: no new edit,
     * toggle or delete is accepted for it until the canonical list, other rows still are, and after the list a new delete
     * goes. A definite 400 leaves the pair known.
     */
    @Test fun `S8_5A_S24 a row whose source change is unconfirmed takes no new work until the canonical list`() = runTest {
        for ((label, answer) in listOf<Pair<String, Result<Any>>>(
            "no answer" to Result.failure(IOException("received, then dropped")),
            "another allowed pair" to Result.success(row(1, source = "coinone")))) rig(this) {
            ready(listOf(row(1), row(2)))
            owner.editAlert(1, SourceAlertEdit(source = "korbit")); settle()
            reply("edit", answer)
            assertEquals("$label: premise: the canonical GET is in flight", 1, openGets())
            owner.delete(1)
            owner.setEnabled(1, false)
            assertEquals("$label: an edit of it is not accepted", notSent(NotSentReason.NOT_READY),
                owner.editAlert(1, SourceAlertEdit(threshold = 1.0)).outcome.value)
            settle()
            assertEquals("$label: no new write for it", listOf("edit"), writes.map { it.kind })
            assertEquals("$label: and it is still shown", listOf(1, 2), ids())
            val add = owner.addAlert(draft()); settle()
            reply("create", Result.success(row(1)))
            assertTrue("$label: a create answered with its id is not adopted", add.outcome.value is SourceAlertSaveOutcome.Failed)
            owner.editAlert(2, SourceAlertEdit(threshold = 1500.0)); settle()
            assertEquals("$label: another row still takes work", 1, open("edit").size)
            reply("edit", Result.success(row(2, threshold = 1500.0)))
            var reads = 0
            while (openGets() > 0 && reads++ < 3) answerGet(listOf(row(1, source = "korbit"), row(2, threshold = 1500.0)))
            assertEquals("$label: the canonical pair is shown", "korbit", shown(1)?.source)
            owner.delete(1); settle()
            assertEquals("$label: and a new delete goes", 1, open("delete").size)
        }
        rig(this) {
            ready(listOf(row(1)))
            owner.editAlert(1, SourceAlertEdit(source = "korbit")); settle()
            reply("edit", Result.failure(http(400)))
            owner.delete(1); settle()
            assertEquals("control: after a definite 400 a new delete goes", 1, open("delete").size)
        }
    }

    /**
     * S8-5a impl review r3 §1 (contract test written before the fix): once an earlier send of the same row is refused at its
     * last check, work accepted after it under a reissued use (same UID and generation) is judged by its own use, not by the
     * use the row's worker started with: it is sent once, its check admits, and its answer is adopted. With and without a
     * foreground change in between, for an edit and for a toggle.
     */
    @Test fun `S8_5A_S25 work accepted under a reissued use after a refused send of the same row goes once`() = runTest {
        for (roundTrip in listOf(true, false)) for (later in listOf("edit", "toggle")) rig(this) {
            val label = "${if (roundTrip) "background and back" else "same foreground"}, $later"
            ready(listOf(row(1)))
            val first = owner.editAlert(1, SourceAlertEdit(threshold = 1500.0)); settle()
            if (roundTrip) { owner.onBackground(); settle() }
            invalidations++
            if (roundTrip) { owner.onForeground(false); settle() }
            val second = if (later == "edit") owner.editAlert(1, SourceAlertEdit(threshold = 1600.0)) else null
            if (later == "toggle") owner.setEnabled(1, false)
            settle()
            assertTrue("$label: premise: the later work is accepted", second?.outcome?.value != notSent(NotSentReason.UNADMITTED))
            wire("edit", Result.success(row(1, threshold = 1500.0)))
            assertTrue("$label: premise: the first never reached the wire", writes.first().withheld)
            assertEquals("$label: the first ends unsent", notSent(if (roundTrip) NotSentReason.BACKGROUND else NotSentReason.UNADMITTED),
                first.outcome.value)
            assertEquals("$label: the later work is sent once", 1, open(later).size)
            assertTrue("$label: under its own check, which admits", open(later).single().guard())
            if (later == "edit") {
                reply("edit", Result.success(row(1, threshold = 1600.0)))
                assertTrue("$label: and saved", second?.outcome?.value is SourceAlertSaveOutcome.Saved)
                assertEquals("$label: shown", 1600.0, shown(1)?.threshold ?: 0.0, 0.0)
            } else {
                reply("toggle", Result.success(row(1, enabled = false)))
                assertEquals("$label: and its answer shown", false, shown(1)?.isEnabled)
            }
            assertEquals("$label: two remote calls in all", 2, writes.size)
        }
    }

    /**
     * S8-5a impl review r3 §2 (contract test written before the fix): a lone send refused at its last check while the owner is
     * still in the background ends unsent and owes nothing, exactly as when the refusal comes after the return: the approved
     * row stays, nothing is sent, and the return of a fresh list reads nothing more.
     */
    @Test fun `S8_5A_S26 a lone send refused in the background owes nothing`() = runTest {
        for (whileAway in listOf(true, false)) rig(this) {
            val label = if (whileAway) "refused while away" else "refused after the return"
            ready(listOf(row(1)))
            val edit = owner.editAlert(1, SourceAlertEdit(threshold = 1500.0)); settle()
            val before = gets.size
            owner.onBackground(); settle()
            if (whileAway) wire("edit", Result.success(row(1, threshold = 1500.0)))
            owner.onForeground(false); settle()
            if (!whileAway) wire("edit", Result.success(row(1, threshold = 1500.0)))
            assertTrue("$label: premise: refused at the wire", writes.single().withheld)
            assertEquals("$label: ends unsent", notSent(NotSentReason.BACKGROUND), edit.outcome.value)
            assertEquals("$label: the approved row stays", 1400.0, shown(1)?.threshold ?: 0.0, 0.0)
            assertEquals("$label: and the fresh list is not read again", before, gets.size)
        }
    }

    /**
     * S8-5a impl review r3 §3 (contract test written before the fix): a delete whose foreground ended before it was sent is
     * cleaned up in the same Main step even when the earlier PUT's answer arrives under a withdrawn use: after an unchanged
     * pair its row is shown again in its place and its reservation is gone (a create answered with its id updates it in
     * place); after an uncertain source change its row stays hidden and its place counted until the canonical list.
     */
    @Test fun `S8_5A_S27 a closed delete is cleaned up when the earlier PUT is answered under a withdrawn use`() = runTest {
        rig(this) {
            ready(listOf(row(1), row(2), row(3)))
            owner.editAlert(2, SourceAlertEdit(threshold = 1500.0)); settle()
            owner.delete(2); settle()
            assertEquals("premise: hidden", listOf(1, 3), ids())
            owner.onBackground(); settle()
            invalidations++
            reply("edit", Result.success(row(2, threshold = 1500.0)))
            owner.onForeground(false); settle()
            assertEquals("no DELETE", listOf("edit"), writes.map { it.kind })
            assertEquals("unchanged pair: the row is back in its place before the canonical list", listOf(1, 2, 3), ids())
            val add = owner.addAlert(draft()); settle()
            reply("create", Result.success(row(2, threshold = 1600.0)))
            assertTrue("and its reservation is gone", add.outcome.value is SourceAlertSaveOutcome.Saved)
        }
        rig(this) {
            ready((1..30).map { row(it) })
            owner.editAlert(30, SourceAlertEdit(source = "korbit")); settle()
            owner.delete(30); settle()
            owner.onBackground(); settle()
            invalidations++
            reply("edit", Result.success(row(30, source = "korbit")))
            owner.onForeground(false); settle()
            assertEquals("no DELETE", listOf("edit"), writes.map { it.kind })
            assertEquals("uncertain source change: the row stays hidden until the canonical list", (1..29).toList(), ids())
            assertEquals("and its place still counts", notSent(NotSentReason.CAP), owner.addAlert(draft()).outcome.value)
        }
    }

    /**
     * S8-5a owner patch r4 notes §1: a delete accepted under a reissued use after an earlier send of the same row is judged by
     * its own use — sent once under a check that admits, its 204 taken (no GET, a create answered with its id is saved) — and
     * when that use too is withdrawn before its check it is not sent and its row comes back with its place freed.
     */
    @Test fun `S8_5A_S28 a delete accepted under a reissued use is judged by its own use`() = runTest {
        for (roundTrip in listOf(true, false)) for (first in listOf("edit", "toggle")) rig(this) {
            val label = "${if (roundTrip) "background and back" else "same foreground"}, after a $first"
            ready(listOf(row(1)))
            val handle = if (first == "edit") owner.editAlert(1, SourceAlertEdit(threshold = 1500.0)) else null
            if (first == "toggle") owner.setEnabled(1, false)
            settle()
            if (roundTrip) { owner.onBackground(); settle() }
            invalidations++
            if (roundTrip) { owner.onForeground(false); settle() }
            owner.delete(1); settle()
            val before = gets.size
            wire(first, Result.success(row(1, threshold = 1500.0)))
            assertTrue("$label: the earlier send never reached the wire", writes.first().withheld)
            if (handle != null) assertEquals("$label: and ends unsent", notSent(if (roundTrip) NotSentReason.BACKGROUND else NotSentReason.UNADMITTED),
                handle.outcome.value)
            assertEquals("$label: the delete is sent once", 1, open("delete").size)
            assertTrue("$label: under its own check, which admits", open("delete").single().guard())
            reply("delete", Result.success(Unit))
            assertEquals("$label: removed", emptyList<Int>(), ids())
            assertEquals("$label: with no GET", before, gets.size)
            val add = owner.addAlert(draft()); settle()
            reply("create", Result.success(row(1, threshold = 1500.0)))
            assertTrue("$label: its place is free", add.outcome.value is SourceAlertSaveOutcome.Saved)
        }
        rig(this) {
            ready(listOf(row(1)))
            owner.editAlert(1, SourceAlertEdit(threshold = 1500.0)); settle()
            invalidations++
            owner.delete(1); settle()
            invalidations++
            wire("edit", Result.success(row(1, threshold = 1500.0)))
            assertEquals("control: a delete whose own use is withdrawn too is not sent", listOf("edit"), writes.map { it.kind })
            assertEquals("control: its row is back", listOf(1), ids())
            val add = owner.addAlert(draft()); settle()
            reply("create", Result.success(row(1, threshold = 1600.0)))
            assertTrue("control: and its place is free before the canonical list", add.outcome.value is SourceAlertSaveOutcome.Saved)
        }
    }

    /**
     * S8-5a owner patch r4 notes §2: waiting work of one row is judged intent by intent at the floor's end — work accepted under
     * a withdrawn use is dropped (an edit unsent, a toggle shown as approved again), work under the reissued use goes once, and
     * the dropped work's debt is read once. A toggle replaced under the reissued use while the old one is in the transport is
     * sent with its own target after the old one is refused, with no GET.
     */
    @Test fun `S8_5A_S29 waiting work of one row is judged intent by intent`() = runTest {
        val endpoint = AuthenticatedEndpoint.UPDATE_SOURCE_NOTIFICATION_SETTING
        rig(this) {
            ready(listOf(row(1, enabled = true)))
            floor.record(endpoint, AlertRateLimitEvidence.of(emptyList(), 429, "5"))
            val edit = owner.editAlert(1, SourceAlertEdit(threshold = 1500.0)); settle()
            invalidations++
            owner.setEnabled(1, false); settle()
            advance(5_000)
            assertEquals("the edit under the withdrawn use ends unsent", notSent(NotSentReason.UNADMITTED), edit.outcome.value)
            assertEquals("only the toggle is sent", listOf("toggle"), writes.map { it.kind })
            reply("toggle", Result.success(row(1, enabled = false)))
            assertEquals("and adopted", false, shown(1)?.isEnabled)
            assertEquals("the dropped edit's debt is read once", 1, openGets())
            answerGet(listOf(row(1, enabled = false)))
            assertEquals("and nothing more", 0, openGets())
        }
        rig(this) {
            ready(listOf(row(1, enabled = true)))
            floor.record(endpoint, AlertRateLimitEvidence.of(emptyList(), 429, "5"))
            owner.setEnabled(1, false); settle()
            invalidations++
            val edit = owner.editAlert(1, SourceAlertEdit(threshold = 1500.0)); settle()
            advance(5_000)
            assertEquals("the toggle under the withdrawn use is not sent", listOf("edit"), writes.map { it.kind })
            assertEquals("and the approved value is shown again", true, shown(1)?.isEnabled)
            reply("edit", Result.success(row(1, threshold = 1500.0)))
            assertTrue("the edit is saved", edit.outcome.value is SourceAlertSaveOutcome.Saved)
            assertEquals("the dropped toggle's debt is read once", 1, openGets())
            answerGet(listOf(row(1, threshold = 1500.0)))
            assertEquals("and nothing more after it", 0, openGets())
        }
        rig(this) {
            ready(listOf(row(1, enabled = true)))
            owner.setEnabled(1, false); settle()
            invalidations++
            owner.setEnabled(1, true); settle()
            val before = gets.size
            wire("toggle", Result.success(row(1, enabled = false)))
            assertTrue("the old toggle never reached the wire", writes.first().withheld)
            assertEquals("the replacing toggle is sent with its own target", true, open("toggle").single().update?.isEnabled)
            assertTrue("under its own check", open("toggle").single().guard())
            reply("toggle", Result.success(row(1, enabled = true)))
            assertEquals("adopted", true, shown(1)?.isEnabled)
            assertEquals("with no GET", before, gets.size)
        }
    }

    /**
     * S8-5a owner patch r4 notes §3: a lone toggle or delete refused at its last check while away owes nothing and leaves the
     * approved list (the delete's place free); with another delete still waiting, both stay unsent, the waiting delete's row
     * comes back, and the return reads the list once.
     */
    @Test fun `S8_5A_S30 a lone toggle or delete refused while away owes nothing`() = runTest {
        for (kind in listOf("toggle", "delete")) rig(this) {
            ready(listOf(row(1, enabled = true), row(2)))
            if (kind == "toggle") owner.setEnabled(1, false) else owner.delete(1)
            settle()
            val before = gets.size
            owner.onBackground(); settle()
            wire(kind, Result.success(if (kind == "delete") Unit else row(1, enabled = false)))
            owner.onForeground(false); settle()
            assertTrue("$kind: refused at the wire", writes.single().withheld)
            assertEquals("$kind: the approved list", listOf(1 to true, 2 to true), owner.state.value.settings?.map { it.id to it.isEnabled })
            assertEquals("$kind: and no GET", before, gets.size)
            if (kind == "delete") {
                val add = owner.addAlert(draft()); settle()
                reply("create", Result.success(row(1, threshold = 1500.0)))
                assertTrue("delete: its place is free", add.outcome.value is SourceAlertSaveOutcome.Saved)
            }
        }
        rig(this) {
            ready(listOf(row(1), row(2)))
            owner.editAlert(1, SourceAlertEdit(threshold = 1500.0)); settle()
            owner.delete(1); settle()
            val before = gets.size
            owner.onBackground(); settle()
            wire("edit", Result.success(row(1, threshold = 1500.0)))
            owner.onForeground(false); settle()
            assertEquals("control: nothing is sent", listOf("edit"), writes.map { it.kind })
            assertTrue("control: the edit was refused at the wire", writes.single().withheld)
            assertEquals("control: the waiting delete's row is back", listOf(1, 2), ids())
            assertEquals("control: and the return reads the list once", before + 1, gets.size)
        }
        rig(this) {
            ready(listOf(row(1, enabled = true), row(2)))
            owner.editAlert(2, SourceAlertEdit(threshold = 1500.0)); settle()
            owner.setEnabled(1, false); settle()
            val before = gets.size
            owner.onBackground(); settle()
            reply("edit", Result.failure(IOException("received, then dropped")))
            wire("toggle", Result.success(row(1, enabled = false)))
            owner.onForeground(false); settle()
            assertTrue("another id's debt: premise: the toggle was refused at the wire", writes.first { it.kind == "toggle" }.withheld)
            assertEquals("another id's debt survives the lone refused send: the return reads the list once", before + 1, gets.size)
        }
    }

    /**
     * S8-5a owner patch r4 notes §4: whatever ends an unchanged-pair PUT under a withdrawn use — an HTTP 500, a dropped
     * connection, a withheld send with exchanges — the delete accepted after it is not sent, its row is back with its
     * approved value, and its place is free; a delete that was itself sent and dropped keeps its place.
     */
    @Test fun `S8_5A_S31 any end of an unchanged-pair PUT under a withdrawn use cleans up the closed delete`() = runTest {
        for ((label, end) in listOf<Pair<String, Rig.() -> Unit>>(
            "HTTP 500" to { reply("edit", Result.failure(http(500))) },
            "a dropped connection" to { answer("edit", Result.success(Unit), thrown = IOException("dropped")) },
            "a withheld send with exchanges" to {
                answer("edit", Result.success(Unit), thrown = TopicUseWithheldException(listOf(HttpExchangeEvidence(1, 1, 503, null))))
            })) rig(this) {
            ready(listOf(row(1), row(2), row(3)))
            val edit = owner.editAlert(2, SourceAlertEdit(threshold = 1500.0)); settle()
            owner.delete(2); settle()
            owner.onBackground(); settle()
            invalidations++
            end(this)
            owner.onForeground(false); settle()
            assertTrue("$label: the PUT is not saved", edit.outcome.value is SourceAlertSaveOutcome.Failed)
            assertEquals("$label: no DELETE", listOf("edit"), writes.map { it.kind })
            assertEquals("$label: the row is back with its approved value", listOf(1400.0, 1400.0, 1400.0) to listOf(1, 2, 3),
                owner.state.value.settings?.map { it.threshold } to ids())
            val add = owner.addAlert(draft()); settle()
            reply("create", Result.success(row(2, threshold = 1600.0)))
            assertTrue("$label: and its place is free", add.outcome.value is SourceAlertSaveOutcome.Saved)
        }
        rig(this) {
            ready(listOf(row(1), row(2), row(3)))
            owner.delete(2); settle()
            invalidations++
            answer("delete", Result.success(Unit), thrown = IOException("dropped"))
            val add = owner.addAlert(draft()); settle()
            reply("create", Result.success(row(2)))
            assertTrue("control: a delete that was itself sent keeps its place", add.outcome.value is SourceAlertSaveOutcome.Failed)
        }
    }

    /**
     * S8-5a owner patch r4 notes §5: an identity check that throws in an unchanged-pair PUT's fold, with no reset, ends the
     * row's work: the delete accepted after it is not sent and frees the place it added (but never an earlier unresolved
     * delete's); after a source change the row stays hidden and its place counted.
     */
    @Test fun `S8_5A_S32 an identity check that throws in the fold ends the row's work`() = runTest {
        val throwing: () -> Unit = { throw AuthIdentityChangedException() }
        rig(this) {
            ready(listOf(row(1), row(2), row(3)))
            owner.editAlert(2, SourceAlertEdit(threshold = 1500.0)); settle()
            owner.delete(2); settle()
            answer("edit", Result.success(row(2, threshold = 1500.0)), requireCurrent = throwing)
            assertEquals("no DELETE", listOf("edit"), writes.map { it.kind })
            assertEquals("the row is back in its place", listOf(1, 2, 3), ids())
            val add = owner.addAlert(draft()); settle()
            reply("create", Result.success(row(2, threshold = 1600.0)))
            assertTrue("and its place is free", add.outcome.value is SourceAlertSaveOutcome.Saved)
        }
        rig(this) {
            ready((1..30).map { row(it) })
            owner.editAlert(30, SourceAlertEdit(source = "korbit")); settle()
            owner.delete(30); settle()
            answer("edit", Result.success(row(30, source = "korbit")), requireCurrent = throwing)
            assertEquals("after a source change the row stays hidden", (1..29).toList(), ids())
            assertEquals("and its place counts", notSent(NotSentReason.CAP), owner.addAlert(draft()).outcome.value)
        }
        rig(this) {
            ready(listOf(row(1), row(2), row(3)))
            owner.delete(2); settle()
            reply("delete", Result.failure(http(500)))
            owner.editAlert(2, SourceAlertEdit(threshold = 1500.0)); settle()
            owner.delete(2); settle()
            answer("edit", Result.success(row(2, threshold = 1500.0)), requireCurrent = throwing)
            val add = owner.addAlert(draft()); settle()
            reply("create", Result.success(row(2)))
            assertTrue("an earlier unresolved delete's place is kept", add.outcome.value is SourceAlertSaveOutcome.Failed)
        }
    }

    /**
     * S8-5a owner patch r4 notes §6: an answer that cannot be taken under a withdrawn use ends the row's work without sending
     * the toggle waiting behind it, whose approved value is shown again; a typed 400 under a withdrawn use is still a
     * rejection, after which a delete accepted under the reissued use goes once, or, if its foreground ended, not at all.
     */
    @Test fun `S8_5A_S33 a withdrawn answer drops the waiting toggle, a 400 still lets a valid delete go`() = runTest {
        rig(this) {
            ready(listOf(row(1, enabled = true)))
            owner.editAlert(1, SourceAlertEdit(threshold = 1500.0)); settle()
            owner.setEnabled(1, false); settle()
            invalidations++
            reply("edit", Result.success(row(1, threshold = 1500.0)))
            assertEquals("the waiting toggle is not sent", listOf("edit"), writes.map { it.kind })
            assertEquals("and the approved value is shown again", true, shown(1)?.isEnabled)
            assertEquals("the worker's end owes one canonical GET", 1, openGets())
        }
        rig(this) {
            ready(listOf(row(1, enabled = true)))
            owner.editAlert(1, SourceAlertEdit(threshold = 1500.0)); settle()
            owner.setEnabled(1, false); settle()
            answer("edit", Result.success(row(1, threshold = 1500.0)), requireCurrent = { throw AuthIdentityChangedException() })
            assertEquals("an identity failure in the fold: the waiting toggle is not sent", listOf("edit"), writes.map { it.kind })
            assertEquals("an identity failure in the fold: its approved value is shown again", true, shown(1)?.isEnabled)
        }
        for (closed in listOf(false, true)) rig(this) {
            val label = if (closed) "a delete whose foreground ended" else "a valid delete"
            ready(listOf(row(1), row(2)))
            val edit = owner.editAlert(1, SourceAlertEdit(source = "korbit")); settle()
            invalidations++
            owner.delete(1); settle()
            if (closed) { owner.onBackground(); settle() }
            reply("edit", Result.failure(http(400)))
            val outcome = edit.outcome.value
            assertTrue("$label: the typed 400", outcome is SourceAlertSaveOutcome.Failed &&
                (outcome.failure as? AlertRepositoryException)?.httpFailure?.statusCode == 400)
            if (closed) {
                assertEquals("$label: is not sent", listOf("edit"), writes.map { it.kind })
                assertEquals("$label: and its row is back", listOf(1, 2), ids())
                owner.onForeground(false); settle()
                val add = owner.addAlert(draft()); settle()
                reply("create", Result.success(row(1, threshold = 1500.0)))
                assertTrue("$label: and its place is free", add.outcome.value is SourceAlertSaveOutcome.Saved)
            } else {
                assertEquals("$label: is sent once", 1, open("delete").size)
                assertTrue("$label: under its own check", open("delete").single().guard())
            }
        }
    }

    /**
     * S8-5a impl review r5 §4 L1: a toggle whose worker first runs in the background is dropped there and the approved value
     * is shown again before the return and before any canonical list.
     */
    @Test fun `S8_5A_S34 a toggle whose worker first runs in the background shows the approved value again`() = runTest {
        rig(this) {
            ready(listOf(row(1, enabled = true)))
            owner.setEnabled(1, false)
            assertEquals("premise: shown at once", false, shown(1)?.isEnabled)
            owner.onBackground(); settle()
            assertEquals("no PUT", 0, writes.size)
            assertEquals("the approved value while still away", true, shown(1)?.isEnabled)
        }
    }
}
