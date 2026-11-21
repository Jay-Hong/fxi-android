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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * S8-5a shared virtual-time write contract (`R4c/S8/s8_5a_contract.agreed.r1.md` §3): one set of write scenarios — a GET that
 * a finished write makes stale, a delete overtaking a sent PUT, a waiting edit replaced under the floor, a create whose id a
 * row operation touched, the cap at 29 with two adds, an uncertain 30th create settled by the canonical GET, toggles joining,
 * PUT and DELETE 404, a background with a waiting edit, and an account switch with a create in flight — that the real bank
 * owner and the real source owner each pass. Each family's harness only hands the scenario's inputs to its owner and maps
 * what it shows; the clock, the order of events, the expected counts and the assertions live here once. A remote write ends
 * only when the test answers it, whatever happened to its job meanwhile. Where the agreed contract keeps the families apart
 * (an overlapped create's success report, a DELETE 404's canonical demand, a definite create rejection's reservation), the
 * assertion names the family; the bank owner's own contract tests keep its expectations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class AlertSettingsMutationLoopContractTest(private val family: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun families(): List<String> = listOf("bank", "source")

        private const val RECHECK = 1_000L
    }

    private val alice = AuthIdentityFence("user-a", 1)
    private val bob = AuthIdentityFence("user-b", 1)
    private val grantA = TopicGrantToken(11)
    private val grantB = TopicGrantToken(12)
    private val stamp = Instant.parse("2026-11-21T01:00:00Z")

    private fun confirmed(id: AuthIdentityFence) = OwnedPremiumAccess(id.uid, id.authGeneration, PremiumAccessState.PremiumConfirmed)
    private fun fenceFor(id: AuthIdentityFence, grant: TopicGrantToken) = TopicSessionFence(id, "epoch-1", grant)
    private fun standing(grant: TopicGrantToken) = TopicAccessSnapshot.INITIAL.copy(
        revision = 1L, facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet())
    )

    /** One row as both families know it: id, threshold and enabled; the owner's pair is the family's fixed one. */
    private data class Row(
        val id: Int,
        val threshold: Double = 1400.0,
        val enabled: Boolean = true,
        val uid: String = "user-a",
        val triggered: Boolean = false,
        val repeat: Int? = null
    )

    private sealed interface Reply {
        class Ok(val row: Row) : Reply
        object Done : Reply
        class Http(val status: Int, val retryAfter: String? = null) : Reply
        object Network : Reply
    }

    private class Get(val identity: AuthIdentityFence) {
        val answer = CompletableDeferred<List<Row>?>()
    }

    /** One remote write: it ends only when the test answers it, whatever happened to its job meanwhile. */
    private class Write(val kind: String, val id: Int?, val threshold: Double?, val enabled: Boolean?, val guard: () -> Boolean) {
        val reply = CompletableDeferred<Reply>()
    }

    /** What the scenarios see of an owner, whichever family it is. */
    private interface Subject {
        val ids: List<Int>?
        val phase: String
        val manualOutcomes: Flow<String>
        fun row(id: Int): Row?
        val update: AuthenticatedEndpoint
        fun onForeground(authoritative: Boolean)
        fun onBackground()
        fun authoritative()
        fun manual()
        fun add(threshold: Double): () -> String
        fun edit(id: Int, threshold: Double?, enabled: Boolean? = null): () -> String
        fun toggle(id: Int, enabled: Boolean)
        fun delete(id: Int)
    }

    /**
     * Runs every dispatched continuation at once, nested inside the call that resumed it — an eager Main, which the owners
     * allow. Unlike an unconfined dispatcher it keeps no event loop, so a chain woken inside `hintAccess` reaches its fetch
     * before `hintAccess` returns.
     */
    private object DirectDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = block.run()
    }

    /**
     * [eager]: the owner's scope is [DirectDispatcher], and its sleeps end only by cancellation, which [DirectDispatcher] also
     * runs at once (a sleep on another dispatcher would end its cancellation there and hold the woken chain back). The eager
     * scenario never needs a sleep to end by itself.
     */
    private inner class Rig(val test: TestScope, eager: Boolean = false) {
        var identity: AuthIdentityFence? = alice
        var fence: TopicSessionFence? = fenceFor(alice, grantA)
        var snapshot: TopicAccessSnapshot = standing(grantA)
        val premium = MutableStateFlow(confirmed(alice))
        val access = AlertSettingsAccess(
            { identity }, { fence }, SnapshotTopicUseAuthority { snapshot }, { premium.value }, { true },
            GraphProtectedAdmission({ identity }, DeletionAdmissionStore())
        )
        val floor = AlertRateLimitFloor({ test.testScheduler.currentTime })
        val gets = mutableListOf<Get>()
        val writes = mutableListOf<Write>()
        private val job = SupervisorJob()
        private val clock: () -> Long = { test.testScheduler.currentTime }
        private val scope = CoroutineScope(job + if (eager) DirectDispatcher else StandardTestDispatcher(test.testScheduler))
        private val sleep: suspend (Long) -> Unit =
            if (eager) { _ -> suspendCancellableCoroutine<Unit> { } } else { millis -> delay(millis) }

        private suspend fun <T> read(who: AuthIdentityFence, row: (Row) -> T): AuthBoundResult<List<T>> {
            val get = Get(who).also { gets += it }
            val rows = withContext(NonCancellable) { get.answer.await() }
                ?: return AuthBoundResult({}, Result.failure(AlertRepositoryException("net", failureKind = AlertRepositoryFailureKind.NETWORK)))
            return AuthBoundResult({}, Result.success(rows.map(row)))
        }

        private suspend fun <T> write(w: Write, row: (Row) -> T): AuthBoundResult<T> {
            writes += w
            return when (val r = withContext(NonCancellable) { w.reply.await() }) {
                is Reply.Ok -> AuthBoundResult({}, Result.success(row(r.row)))
                Reply.Done -> {
                    @Suppress("UNCHECKED_CAST")
                    AuthBoundResult({}, Result.success(Unit as T))
                }
                is Reply.Http -> {
                    val headers = if (r.retryAfter == null) headersOf() else headersOf("Retry-After", r.retryAfter)
                    val failure = AuthenticatedHttpFailure(r.status, headers, ByteArray(0), null, null, AuthenticatedFailureKind.OTHER_HTTP)
                    AuthBoundResult({}, Result.failure(mapAlertHttpFailure(failure)), AlertRateLimitResponseEvidence(r.status, r.retryAfter))
                }
                Reply.Network -> AuthBoundResult({}, Result.failure(AlertRepositoryException("net", failureKind = AlertRepositoryFailureKind.NETWORK)))
            }
        }

        private fun bankRow(r: Row) =
            AlertSetting(r.id, r.uid, "hana", "usd-krw", AlertCondition.ABOVE, r.threshold, r.enabled, r.triggered, stamp, stamp, null, r.repeat)
        private fun sourceRow(r: Row) =
            SourceAlertSetting(r.id, r.uid, "upbit", "usdt-krw", AlertCondition.ABOVE, r.threshold, r.enabled, r.triggered, r.repeat, stamp, stamp, null)

        val owner: Subject = when (family) {
            "bank" -> {
                val bank = BankAlertSettingsOwner(
                    { who, _ -> read(who, ::bankRow) }, access, floor, AlertRetryPolicy(), clock, { 0.0 },
                    scope, sleep, premium, MutableStateFlow(0L), MutableStateFlow(false),
                    { _, id, enabled, guard -> write(Write("toggle", id, null, enabled, guard), ::bankRow) },
                    { _, id, guard -> write(Write("delete", id, null, null, guard)) { } },
                    { _, request, guard -> write(Write("create", null, request.threshold, request.isEnabled, guard), ::bankRow) },
                    { _, id, request, guard -> write(Write("edit", id, request.threshold, request.isEnabled, guard), ::bankRow) },
                    RECHECK
                )
                object : Subject {
                    override val ids get() = bank.state.value.settings?.map { it.id }
                    override val phase get() = bank.state.value.phase.toString()
                    override val manualOutcomes = bank.manualOutcomes.map { it.toString() }
                    override fun row(id: Int) = bank.state.value.settings?.firstOrNull { it.id == id }
                        ?.let { Row(it.id, it.threshold, it.isEnabled, it.userId, it.triggered, it.repeatIntervalSec) }
                    override val update = AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING
                    override fun onForeground(authoritative: Boolean) = bank.onForeground(authoritative)
                    override fun onBackground() = bank.onBackground()
                    override fun authoritative() = bank.authoritative()
                    override fun manual() = bank.manual()
                    override fun add(threshold: Double): () -> String {
                        val h = bank.addAlert(BankAlertDraft("usd-krw", "hana", AlertCondition.ABOVE, threshold, true, null))
                        return { bankOutcome(h.outcome.value) }
                    }
                    override fun edit(id: Int, threshold: Double?, enabled: Boolean?): () -> String {
                        val h = bank.editAlert(id, BankAlertEdit(threshold = threshold, isEnabled = enabled))
                        return { bankOutcome(h.outcome.value) }
                    }
                    override fun toggle(id: Int, enabled: Boolean) = bank.setEnabled(id, enabled)
                    override fun delete(id: Int) = bank.delete(id)
                }
            }
            else -> {
                val source = SourceAlertSettingsOwner(
                    { who, _ -> read(who, ::sourceRow) }, access, floor, AlertRetryPolicy(), clock, { 0.0 },
                    scope, sleep, premium, MutableStateFlow(0L),
                    { _, request, guard -> write(Write("create", null, request.threshold, request.isEnabled, guard), ::sourceRow) },
                    { _, id, request, guard -> write(Write(if (onlyEnabled(request)) "toggle" else "edit", id, request.threshold, request.isEnabled, guard), ::sourceRow) },
                    { _, id, guard -> write(Write("delete", id, null, null, guard)) { } },
                    RECHECK
                )
                object : Subject {
                    override val ids get() = source.state.value.settings?.map { it.id }
                    override val phase get() = source.state.value.phase.toString()
                    override val manualOutcomes = source.manualOutcomes.map { it.toString() }
                    override fun row(id: Int) = source.state.value.settings?.firstOrNull { it.id == id }
                        ?.let { Row(it.id, it.threshold, it.isEnabled, it.userId, it.triggered, it.repeatIntervalSec) }
                    override val update = AuthenticatedEndpoint.UPDATE_SOURCE_NOTIFICATION_SETTING
                    override fun onForeground(authoritative: Boolean) = source.onForeground(authoritative)
                    override fun onBackground() = source.onBackground()
                    override fun authoritative() = source.authoritative()
                    override fun manual() = source.manual()
                    override fun add(threshold: Double): () -> String {
                        val h = source.addAlert(SourceAlertDraft("upbit", AlertCondition.ABOVE, threshold, true, null))
                        return { sourceOutcome(h.outcome.value) }
                    }
                    override fun edit(id: Int, threshold: Double?, enabled: Boolean?): () -> String {
                        val h = source.editAlert(id, SourceAlertEdit(threshold = threshold, isEnabled = enabled))
                        return { sourceOutcome(h.outcome.value) }
                    }
                    override fun toggle(id: Int, enabled: Boolean) = source.setEnabled(id, enabled)
                    override fun delete(id: Int) = source.delete(id)
                }
            }
        }

        fun settle() = test.runCurrent()
        fun advance(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }
        /** Foreground, and answer the first GET with [rows]. */
        fun ready(vararg rows: Row) {
            owner.onForeground(false)
            settle()
            answerGet(rows.toList())
            assertEquals("premise: the snapshot", rows.map { it.id }, owner.ids)
        }
        fun openGets() = gets.filter { !it.answer.isCompleted }
        fun answerGet(rows: List<Row>?) {
            val open = openGets()
            check(open.size == 1) { "exactly one GET awaits its answer, not ${open.size}" }
            open.single().answer.complete(rows)
            settle()
        }
        fun openWrites() = writes.filter { !it.reply.isCompleted }
        fun reply(kind: String, reply: Reply) {
            val open = openWrites().filter { it.kind == kind }
            check(open.size == 1) { "exactly one $kind awaits its answer, not ${open.size}" }
            open.single().reply.complete(reply)
            settle()
        }
        fun switchTo(id: AuthIdentityFence, grant: TopicGrantToken) {
            identity = id
            fence = fenceFor(id, grant)
            snapshot = standing(grant)
            premium.value = confirmed(id)
            settle()
        }
        fun close() {
            writes.filter { !it.reply.isCompleted }.forEach { it.reply.complete(Reply.Network) }
            gets.filter { !it.answer.isCompleted }.forEach { it.answer.complete(null) }
            test.runCurrent()
            job.cancel()
        }
    }

    private fun onlyEnabled(r: SourceAlertSettingUpdateRequest) =
        r.isEnabled != null && r.source == null && r.asset == null && r.condition == null && r.threshold == null && r.repeatIntervalSec == null

    private fun bankOutcome(o: BankAlertSaveOutcome): String = when (o) {
        BankAlertSaveOutcome.Pending -> "pending"
        is BankAlertSaveOutcome.Saved -> "saved"
        BankAlertSaveOutcome.Removed -> "removed"
        is BankAlertSaveOutcome.NotSent -> "notsent:${o.reason}"
        is BankAlertSaveOutcome.Failed -> "failed"
    }

    private fun sourceOutcome(o: SourceAlertSaveOutcome): String = when (o) {
        SourceAlertSaveOutcome.Pending -> "pending"
        is SourceAlertSaveOutcome.Saved -> "saved"
        SourceAlertSaveOutcome.Removed -> "removed"
        is SourceAlertSaveOutcome.NotSent -> "notsent:${o.reason}"
        SourceAlertSaveOutcome.Unsupported -> "unsupported"
        is SourceAlertSaveOutcome.Failed -> "failed"
    }

    private fun rig(test: TestScope, eager: Boolean = false, body: Rig.() -> Unit) {
        val r = Rig(test, eager)
        try {
            // Invoked as `body(r)`, never `r.body()`, so that no member of Rig can be called in its place.
            body(r)
        } finally {
            r.close()
        }
    }

    @Test fun `S8_5A_M01 a GET that a write finished after it started is discarded and read again`() = runTest {
        rig(this) {
            ready(Row(1))
            owner.authoritative(); settle()
            assertEquals("premise: a GET is in flight", 1, openGets().size)
            owner.toggle(1, false); settle()
            reply("toggle", Reply.Ok(Row(1, enabled = false)))
            assertEquals("the write is shown", false, owner.row(1)?.enabled)
            answerGet(listOf(Row(1, enabled = true)))
            assertEquals("the stale body is not adopted", false, owner.row(1)?.enabled)
            assertEquals("and the chain reads again", 1, openGets().size)
            answerGet(listOf(Row(1, enabled = false)))
            assertEquals("three GETs in all", 3, gets.size)
        }
    }

    @Test fun `S8_5A_M02 a delete overtakes a sent PUT and the late PUT does not show the row again`() = runTest {
        rig(this) {
            ready(Row(1), Row(2))
            owner.toggle(1, false); settle()
            owner.delete(1); settle()
            assertEquals("the delete hides the row at once", listOf(2), owner.ids)
            reply("toggle", Reply.Ok(Row(1, enabled = false)))
            assertEquals("the late PUT shows nothing", listOf(2), owner.ids)
            assertEquals("the delete is sent after it", 1, openWrites().count { it.kind == "delete" && it.id == 1 })
            reply("delete", Reply.Done)
            assertEquals("removed", listOf(2), owner.ids)
            assertEquals("one PUT and one DELETE", listOf("toggle", "delete"), writes.map { it.kind })
        }
    }

    @Test fun `S8_5A_M03 a waiting edit replaced under the floor ends once and only the new patch is sent`() = runTest {
        rig(this) {
            ready(Row(1))
            floor.record(owner.update, AlertRateLimitEvidence.of(emptyList(), 429, "5"))
            val first = owner.edit(1, 1500.0); settle()
            val second = owner.edit(1, 1600.0); settle()
            assertEquals("the replaced edit ends unsent", "notsent:SUPERSEDED", first())
            assertEquals("nothing is sent under the floor", 0, writes.size)
            advance(4_999)
            assertEquals("nor 1 ms before its deadline", 0, writes.size)
            advance(1)
            assertEquals("one PUT, the new patch", listOf(1600.0), writes.map { it.threshold })
            reply("edit", Reply.Ok(Row(1, threshold = 1600.0)))
            assertEquals("the new edit is saved", "saved", second())
            assertEquals("still one PUT", 1, writes.size)
        }
    }

    @Test fun `S8_5A_M04 a create whose id a row operation touched never overwrites that row and is settled by the canonical GET`() = runTest {
        rig(this) {
            ready(Row(1, threshold = 1400.0))
            val created = owner.add(1500.0); settle()
            owner.toggle(1, false); settle()
            reply("toggle", Reply.Ok(Row(1, enabled = false)))
            val before = gets.size
            reply("create", Reply.Ok(Row(1, threshold = 1500.0)))
            assertEquals("the row keeps its own threshold", 1400.0, owner.row(1)?.threshold)
            assertEquals("and its toggle", false, owner.row(1)?.enabled)
            assertEquals("the debt is read once", before + 1, gets.size)
            // s8_5a contract §2.4: the source reports no success for an overlapped create; the bank owner keeps its Saved (C10).
            assertEquals("$family: the overlapped create's report", if (family == "bank") "saved" else "failed", created())
        }
    }

    @Test fun `S8_5A_M05 at 29 two adds before any worker runs reserve one place and send one POST`() = runTest {
        rig(this) {
            ready(*(1..29).map { Row(it) }.toTypedArray())
            val a = owner.add(1500.0)
            val b = owner.add(1501.0)
            settle()
            assertEquals("the first is accepted", "pending", a())
            assertEquals("the second meets the cap", "notsent:CAP", b())
            assertEquals("one POST", 1, writes.count { it.kind == "create" })
        }
    }

    @Test fun `S8_5A_M06 an uncertain 30th create keeps its place until a canonical GET settles it`() = runTest {
        rig(this) {
            ready(*(1..29).map { Row(it) }.toTypedArray())
            val a = owner.add(1500.0); settle()
            reply("create", Reply.Network)
            assertEquals("an unknown result", "failed", a())
            assertEquals("the place is still held", "notsent:CAP", owner.add(1501.0)())
            assertEquals("the debt is read", 1, openGets().size)
            answerGet(null)
            assertEquals("a failed settlement keeps the place", "notsent:CAP", owner.add(1502.0)())
            advance(60_000)
            answerGet((1..29).map { Row(it) })
            assertEquals("the canonical GET without it frees the place", "pending", owner.add(1503.0)())
        }
    }

    @Test fun `S8_5A_M07 toggles join to their last target while a PUT is in flight`() = runTest {
        rig(this) {
            ready(Row(1, enabled = true))
            owner.toggle(1, false); settle()
            owner.toggle(1, true); settle()
            owner.toggle(1, false); settle()
            reply("toggle", Reply.Ok(Row(1, enabled = false)))
            assertEquals("the last target equals the answer: no second PUT", 1, writes.size)
            assertEquals(false, owner.row(1)?.enabled)
            owner.toggle(1, true); settle()
            owner.toggle(1, false); settle()
            reply("toggle", Reply.Ok(Row(1, enabled = true)))
            assertEquals("a later different target is sent once more", listOf(false, true, false), writes.map { it.enabled })
            reply("toggle", Reply.Ok(Row(1, enabled = false)))
            assertEquals(false, owner.row(1)?.enabled)
        }
    }

    @Test fun `S8_5A_M08 a PUT 404 removes the row and is read again, a DELETE 404 removes it too`() = runTest {
        rig(this) {
            ready(Row(1), Row(2))
            val edit = owner.edit(1, 1500.0); settle()
            val before = gets.size
            reply("edit", Reply.Http(404))
            assertEquals("PUT 404: the row is gone", listOf(2), owner.ids)
            assertEquals("and the edit is removed", "removed", edit())
            assertEquals("PUT 404: one canonical GET", before + 1, gets.size)
            answerGet(listOf(Row(2)))
            val beforeDelete = gets.size
            owner.delete(2); settle()
            reply("delete", Reply.Http(404))
            assertEquals("DELETE 404: the row is gone", emptyList<Int>(), owner.ids)
            // s8_5a contract §2.5: the source confirms a DELETE 404 with one canonical demand; the bank owner reads none (M05).
            assertEquals("$family: DELETE 404's canonical GETs", if (family == "bank") 0 else 1, gets.size - beforeDelete)
        }
    }

    @Test fun `S8_5A_M09 a background ends a waiting edit unsent and the return reads the canonical list first`() = runTest {
        rig(this) {
            ready(Row(1))
            floor.record(owner.update, AlertRateLimitEvidence.of(emptyList(), 429, "5"))
            val edit = owner.edit(1, 1500.0); settle()
            owner.onBackground(); settle()
            assertEquals("the waiting edit ends unsent", "notsent:BACKGROUND", edit())
            advance(10_000)
            assertEquals("nothing is sent in the background", 0, writes.size)
            val before = gets.size
            owner.onForeground(false); settle()
            assertEquals("the return reads at once, even fresh", before + 1, gets.size)
        }
    }

    @Test fun `S8_5A_M10 an account switch with a create in flight keeps the late answer from the next account`() = runTest {
        rig(this) {
            ready(Row(1))
            val created = owner.add(1500.0); settle()
            switchTo(bob, grantB)
            assertEquals("the switch ends the create's handle", "failed", created())
            assertEquals("and the next account starts empty", null, owner.ids)
            reply("create", Reply.Ok(Row(9, threshold = 1500.0)))
            answerGet(listOf(Row(5, uid = "user-b")))
            assertEquals("B sees only its own rows", listOf(5), owner.ids)
        }
    }

    @Test fun `S8_5A_M11 a definite create rejection keeps or frees the place as each family agreed`() = runTest {
        rig(this) {
            ready(*(1..29).map { Row(it) }.toTypedArray())
            val a = owner.add(1500.0); settle()
            reply("create", Reply.Http(400))
            assertEquals("a rejection is a failure", "failed", a())
            // s8_5a contract §3: the source frees a definitely rejected create's place at once; the bank owner holds it to the GET.
            assertEquals("$family: the next add before any settlement", if (family == "bank") "notsent:CAP" else "pending", owner.add(1501.0)())
            assertTrue("premise: no row was adopted", owner.ids?.size == 29)
        }
    }

    /**
     * S8-5a impl review r1 B: an edit that carries the enabled value joins with the toggles around it by acceptance order; a
     * waiting edit replaced while another is on the wire ends once, and a delete takes precedence over what still waits. Every
     * handle's end survives the final cleanup.
     */
    @Test fun `S8_5A_M12 edits with an enabled value and toggles join by acceptance order, a delete overtakes what waits`() = runTest {
        rig(this) {
            ready(Row(1, enabled = true))
            owner.toggle(1, false); settle()
            val edit = owner.edit(1, 1500.0, enabled = true); settle()
            reply("toggle", Reply.Ok(Row(1, enabled = false)))
            assertEquals("a later edit with enabled goes after the earlier toggle", listOf(null to false, 1500.0 to true),
                writes.map { it.threshold to it.enabled })
            reply("edit", Reply.Ok(Row(1, threshold = 1500.0, enabled = true)))
            assertEquals("saved", "saved", edit())
            assertEquals(Row(1, threshold = 1500.0, enabled = true), owner.row(1))
        }
        rig(this) {
            ready(Row(1, enabled = true))
            val edit = owner.edit(1, 1500.0, enabled = false); settle()
            owner.toggle(1, true); settle()
            reply("edit", Reply.Ok(Row(1, threshold = 1500.0, enabled = false)))
            assertEquals("a later toggle after an edit with enabled is sent", listOf(1500.0 to false, null to true),
                writes.map { it.threshold to it.enabled })
            reply("toggle", Reply.Ok(Row(1, threshold = 1500.0, enabled = true)))
            assertEquals("saved", "saved", edit())
            assertEquals(true, owner.row(1)?.enabled)
        }
        rig(this) {
            ready(Row(1, enabled = true))
            val first = owner.edit(1, 1450.0); settle()
            owner.toggle(1, false); settle()
            val second = owner.edit(1, 1500.0, enabled = true); settle()
            reply("edit", Reply.Ok(Row(1, threshold = 1450.0, enabled = true)))
            assertEquals("the waiting edit goes next", listOf(1450.0 to null, 1500.0 to true), writes.map { it.threshold to it.enabled })
            reply("edit", Reply.Ok(Row(1, threshold = 1500.0, enabled = true)))
            assertEquals("the earlier toggle is consumed by the later enabled value: no third PUT", 2, writes.size)
            assertEquals(listOf("saved", "saved"), listOf(first(), second()))
            assertEquals(true, owner.row(1)?.enabled)
        }
        rig(this) {
            ready(Row(1), Row(2))
            val sent = owner.edit(1, 1450.0); settle()
            val waiting = owner.edit(1, 1460.0); settle()
            val replacing = owner.edit(1, 1470.0); settle()
            assertEquals("the replaced waiting edit ends once", "notsent:SUPERSEDED", waiting())
            owner.delete(1); settle()
            assertEquals("the delete ends the edit still waiting", "notsent:SUPERSEDED", replacing())
            reply("edit", Reply.Ok(Row(1, threshold = 1450.0)))
            assertEquals("the sent edit ends with its answer", "saved", sent())
            reply("delete", Reply.Done)
            assertEquals("one PUT of the sent patch, then the DELETE", listOf("edit" to 1450.0, "delete" to null), writes.map { it.kind to it.threshold })
            assertEquals(listOf(2), owner.ids)
            settle()
            assertEquals("every handle keeps its end after the cleanup", listOf("saved", "notsent:SUPERSEDED", "notsent:SUPERSEDED"),
                listOf(sent(), waiting(), replacing()))
        }
    }

    /**
     * S8-5a impl review r1 B: demands for the canonical list while a row write or a create is active start no GET; the last
     * completion starts exactly one, and its adopted answer starts no other. The list is fresh for 30 s after a success.
     */
    @Test fun `S8_5A_M13 demands during writes start one GET after the last, and the list is fresh for 30 s`() = runTest {
        for (start in listOf("row", "create")) rig(this) {
            ready(Row(1))
            if (start == "row") owner.toggle(1, false) else owner.add(1500.0)
            settle()
            val before = gets.size
            owner.authoritative(); owner.manual(); owner.authoritative(); settle()
            assertEquals("$start: no GET while the write is active", before, gets.size)
            if (start == "row") reply("toggle", Reply.Network) else reply("create", Reply.Network)
            assertEquals("$start: one GET after its completion", before + 1, gets.size)
            answerGet(listOf(Row(1)))
            settle()
            assertEquals("$start: and none after the adopted answer", before + 1, gets.size)
        }
        rig(this) {
            ready(Row(1))
            val before = gets.size
            advance(29_999)
            owner.onBackground(); settle()
            owner.onForeground(false); settle()
            assertEquals("fresh at 29,999 ms: no GET", before, gets.size)
            owner.onBackground(); settle()
            advance(1)
            owner.onForeground(false); settle()
            assertEquals("stale at 30,000 ms: one GET", before + 1, gets.size)
        }
    }

    /**
     * S8-5a impl review r1 B: the cap counts shown rows of every state and every place a write still holds. At 30 an optimistic
     * delete keeps its place until it is confirmed; at 29 an overlapped create keeps its place until the canonical GET.
     */
    @Test fun `S8_5A_M14 places held by a delete or an overlapped create count until they are settled`() = runTest {
        rig(this) {
            ready(*(1..30).map { Row(it, enabled = it % 2 == 0) }.toTypedArray())
            owner.delete(30); settle()
            assertEquals("premise: hidden at once", (1..29).toList(), owner.ids)
            assertEquals("its place still counts", "notsent:CAP", owner.add(1500.0)())
            reply("delete", Reply.Done)
            assertEquals("a confirmed delete frees it", "pending", owner.add(1500.0)())
        }
        rig(this) {
            ready(*(1..29).map { Row(it, enabled = it % 3 != 0) }.toTypedArray())
            val created = owner.add(1500.0); settle()
            owner.toggle(1, false); settle()
            reply("toggle", Reply.Ok(Row(1, enabled = false)))
            reply("create", Reply.Ok(Row(1, threshold = 1500.0)))
            assertEquals("premise: overlapped", 1, openGets().size)
            assertEquals("the overlapped create's place still counts", "notsent:CAP", owner.add(1501.0)())
            answerGet((1..29).map { Row(it) })
            assertEquals("the canonical GET frees it", "pending", owner.add(1502.0)())
            assertTrue("premise: the overlapped create ended", created() != "pending")
        }
    }

    /** S8-5a impl review r1 A5: a rejected delete comes back in its place, beside another row's newer value, before any canonical GET. */
    @Test fun `S8_5A_M15 a rejected delete comes back in its place`() = runTest {
        rig(this) {
            ready(Row(1), Row(2), Row(3))
            owner.delete(2); settle()
            owner.edit(3, 1500.0); settle()
            reply("edit", Reply.Ok(Row(3, threshold = 1500.0)))
            reply("delete", Reply.Http(400))
            assertEquals("in its place", listOf(1, 2, 3), owner.ids)
            assertEquals("beside the other row's newer value", listOf(1400.0, 1400.0, 1500.0), owner.ids?.map { owner.row(it)?.threshold })
        }
    }

    /**
     * S8-5a impl review r1 B (M10): a late answer of the previous account never touches the next one's rows, place, handles or
     * reads, whether it is a success or a failure and while the next account's own write still waits; A → B → A as well.
     */
    @Test fun `S8_5A_M16 a late answer of the previous account leaves the next one's work alone`() = runTest {
        for (late in listOf<Reply>(Reply.Ok(Row(9, threshold = 1500.0)), Reply.Http(500))) rig(this) {
            ready(Row(1))
            owner.add(1500.0); settle()
            switchTo(bob, grantB)
            answerGet((1..29).map { Row(it + 100, uid = "user-b") })
            owner.toggle(101, false); settle()
            val bAdd = owner.add(1600.0); settle()
            val before = gets.size
            val aCreate = writes.first { it.kind == "create" }
            aCreate.reply.complete(late); settle()
            assertEquals("B's rows", (101..129).toList(), owner.ids)
            assertEquals("B's own create still waits", "pending", bAdd())
            assertEquals("B's toggle still shows", false, owner.row(101)?.enabled)
            assertEquals("no read for B", before, gets.size)
            assertEquals("B's places are its own", "notsent:CAP", owner.add(1700.0)())
            reply("toggle", Reply.Ok(Row(101, enabled = false, uid = "user-b")))
            val bCreate = openWrites().single { it.kind == "create" }
            bCreate.reply.complete(Reply.Ok(Row(130, threshold = 1600.0, uid = "user-b"))); settle()
            assertEquals("B's create is saved", "saved", bAdd())
            assertEquals((listOf(130) + (101..129)), owner.ids)
        }
        rig(this) {
            ready(Row(1))
            val aOld = owner.add(1500.0); settle()
            switchTo(bob, grantB)
            answerGet(listOf(Row(5, uid = "user-b")))
            switchTo(alice, grantA)
            answerGet(listOf(Row(1)))
            writes.first { it.kind == "create" }.reply.complete(Reply.Ok(Row(9, threshold = 1500.0))); settle()
            assertEquals("A's earlier session's create is not adopted by its next session", listOf(1), owner.ids)
            assertEquals("its handle ended with the switch", "failed", aOld())
            assertEquals("and A's next session reads nothing for it", 0, openGets().size)
        }
    }

    /**
     * S8-5a impl review r1 B: overlapping debts are read once. Two PUT 404s that end together start one canonical GET; demands
     * made while that GET is in its fetch read once more after it, and an adopted answer then starts nothing.
     */
    @Test fun `S8_5A_M17 overlapping 404s are read once, and demands during a fetch read once more`() = runTest {
        rig(this) {
            ready(Row(1), Row(2), Row(3))
            val a = owner.edit(1, 1500.0)
            val b = owner.edit(2, 1500.0)
            settle()
            val before = gets.size
            openWrites().single { it.id == 1 }.reply.complete(Reply.Http(404))
            openWrites().single { it.id == 2 }.reply.complete(Reply.Http(404))
            settle()
            assertEquals("premise: both edits removed", listOf("removed", "removed"), listOf(a(), b()))
            assertEquals("one canonical GET for both", before + 1, gets.size)
            owner.authoritative(); owner.manual(); owner.authoritative(); settle()
            assertEquals("demands during the fetch start no second GET", before + 1, gets.size)
            answerGet(listOf(Row(3)))
            assertEquals("they read once more after it", before + 2, gets.size)
            answerGet(listOf(Row(3)))
            settle()
            assertEquals("and the adopted answer starts nothing", before + 2, gets.size)
            assertEquals(listOf(3), owner.ids)
        }
    }

    /** S8-5a impl review r2 B: every shown row counts, whatever its state, and a 31st existing row is kept and editable. */
    @Test fun `S8_5A_M18 disabled, triggered and repeating rows all count, and a 31st existing row stays`() = runTest {
        rig(this) {
            ready(*(1..30).map { Row(it, enabled = it % 2 == 0, triggered = it % 3 == 0, repeat = if (it % 5 == 0) 300 else null) }.toTypedArray())
            assertEquals("premise: the states are as given", Row(15, enabled = false, triggered = true, repeat = 300), owner.row(15))
            assertEquals("30 rows of any state meet the cap", "notsent:CAP", owner.add(1500.0)())
        }
        rig(this) {
            ready(*(1..31).map { Row(it) }.toTypedArray())
            assertEquals("31 rows meet it too", "notsent:CAP", owner.add(1500.0)())
            val edit = owner.edit(31, 1500.0); settle()
            reply("edit", Reply.Ok(Row(31, threshold = 1500.0)))
            assertEquals("and the 31st stays editable", "saved", edit())
            assertEquals((1..31).toList(), owner.ids)
        }
    }

    /**
     * S8-5a impl review r2 B (M10): a late answer of the previous account's row write never removes or answers the next
     * account's worker for the same id: B's phase, row, handle and calls are unchanged, and B's own answer is adopted.
     */
    @Test fun `S8_5A_M19 the previous account's late row answer leaves the next account's same id alone`() = runTest {
        for (late in listOf<Reply>(Reply.Ok(Row(1, threshold = 1450.0)), Reply.Http(500))) rig(this) {
            ready(Row(1))
            owner.edit(1, 1450.0); settle()
            switchTo(bob, grantB)
            answerGet(listOf(Row(1, uid = "user-b")))
            val bEdit = owner.edit(1, 1600.0); settle()
            val phase = owner.phase
            val before = gets.size
            writes.first { it.kind == "edit" && it.threshold == 1450.0 }.reply.complete(late); settle()
            assertEquals("B's phase", phase, owner.phase)
            assertEquals("B's row", Row(1, uid = "user-b"), owner.row(1))
            assertEquals("B's edit still waits", "pending", bEdit())
            assertEquals("no read for B", before, gets.size)
            assertEquals("B's own PUT is the one open", listOf(1600.0), openWrites().map { it.threshold })
            reply("edit", Reply.Ok(Row(1, threshold = 1600.0, uid = "user-b")))
            assertEquals("B's answer is adopted", "saved", bEdit())
            assertEquals(1600.0, owner.row(1)?.threshold)
        }
        // S8-5a battery A9: A's late answer while B's first read is still open; B, with no snapshot yet, keeps its own phase.
        for (late in listOf<Reply>(Reply.Ok(Row(1, threshold = 1450.0)), Reply.Http(500))) rig(this) {
            ready(Row(1))
            owner.edit(1, 1450.0); settle()
            switchTo(bob, grantB)
            assertEquals("premise: B's first GET is open", 1, openGets().size)
            assertEquals("premise: B has no snapshot", null, owner.ids)
            val phase = owner.phase
            writes.first { it.kind == "edit" && it.threshold == 1450.0 }.reply.complete(late); settle()
            assertEquals("B's phase while its first read is open", phase, owner.phase)
            assertEquals("B still has no snapshot", null, owner.ids)
            answerGet(listOf(Row(1, uid = "user-b")))
            assertEquals("B's own answer is adopted", listOf(1), owner.ids)
        }
    }

    /**
     * S8-5a impl review r2 B (M04): a row operation active when a create is accepted overlaps it even if its completion
     * changes nothing shown; of two creates answered with the same new id, only the first adopts it.
     */
    @Test fun `S8_5A_M20 a row active at the create's acceptance overlaps it, and a second create of the same new id does not adopt it`() = runTest {
        rig(this) {
            ready(Row(1, enabled = false))
            owner.toggle(1, false); settle()
            val created = owner.add(1500.0); settle()
            reply("toggle", Reply.Ok(Row(1, enabled = false)))
            reply("create", Reply.Ok(Row(1, threshold = 1500.0)))
            assertEquals("the row keeps its own threshold", 1400.0, owner.row(1)?.threshold)
            assertEquals("the debt is read", 1, openGets().size)
            assertEquals("$family: the overlapped create's report", if (family == "bank") "saved" else "failed", created())
        }
        rig(this) {
            ready(Row(1))
            val first = owner.add(1500.0)
            val second = owner.add(1501.0)
            settle()
            openWrites().first { it.kind == "create" && it.threshold == 1500.0 }.reply.complete(Reply.Ok(Row(9, threshold = 1500.0))); settle()
            assertEquals("the first adopts the new id", "saved", first())
            openWrites().single { it.kind == "create" }.reply.complete(Reply.Ok(Row(9, threshold = 1501.0))); settle()
            assertEquals("the second does not overwrite it", 1500.0, owner.row(9)?.threshold)
            assertEquals("$family: the second's report", if (family == "bank") "saved" else "failed", second())
            assertEquals("and the canonical list is read", 1, openGets().size)
        }
    }

    /**
     * S8-5a impl review r2 C1: the list's freshness runs from its last adopted GET, never from a write's success; a manual
     * read joined to a GET that a write makes stale reports nothing until a valid read, then cools down for 3 s from it.
     */
    @Test fun `S8_5A_M21 freshness and the manual result follow the adopted GET, not the write`() = runTest {
        rig(this) {
            ready(Row(1))
            advance(10_000)
            owner.toggle(1, false); settle()
            reply("toggle", Reply.Ok(Row(1, enabled = false)))
            val before = gets.size
            advance(19_999)
            owner.onBackground(); settle()
            owner.onForeground(false); settle()
            assertEquals("fresh at 29,999 ms after the GET", before, gets.size)
            owner.onBackground(); settle()
            advance(1)
            owner.onForeground(false); settle()
            assertEquals("stale at 30,000 ms after it, whatever the write", before + 1, gets.size)
        }
        rig(this) {
            val outcomes = mutableListOf<String>()
            ready(Row(1))
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.manualOutcomes.collect { outcomes += it } }
            advance(10_000)
            owner.manual(); settle()
            assertEquals("premise: the manual GET is in flight", 1, openGets().size)
            owner.toggle(1, false); settle()
            reply("toggle", Reply.Ok(Row(1, enabled = false)))
            answerGet(listOf(Row(1, enabled = true)))
            assertEquals("the stale read reports nothing", emptyList<String>(), outcomes)
            assertEquals("the chain reads again", 1, openGets().size)
            answerGet(listOf(Row(1, enabled = false)))
            assertEquals("the valid read reports success once", listOf("Succeeded"), outcomes)
            advance(2_999)
            owner.manual(); settle()
            assertTrue("cooling down at 2,999 ms", outcomes.last().startsWith("CoolingDown"))
            val reads = gets.size
            advance(1)
            owner.manual(); settle()
            assertEquals("a new read at 3,000 ms", reads + 1, gets.size)
        }
    }

    /**
     * S8-5a impl review r2 C1: a write's debt joins the waiting chain before it is woken. With an owner scope that runs a
     * woken GET at once, the wrong order lets the GET start first and the late demand read once more after it.
     */
    @Test fun `S8_5A_M22 the debt joins the waiting chain before its wake`() = runTest {
        rig(this, eager = true) {
            ready(Row(1))
            val before = gets.size
            owner.toggle(1, false)
            owner.authoritative()
            assertEquals("premise: the chain waits for the write", before, gets.size)
            reply("toggle", Reply.Network)
            assertEquals("one GET after the write", before + 1, gets.size)
            answerGet(listOf(Row(1)))
            assertEquals("and none after its adopted answer", before + 1, gets.size)
        }
    }

    /**
     * S8-5a impl review r2 B (M16): a use reissued for the same UID and generation withdraws the check an earlier request
     * carries, and new work accepted under the reissued use carries its own check that admits.
     */
    @Test fun `S8_5A_M23 a use reissued for the same account withdraws the old request's check and admits new work`() = runTest {
        rig(this) {
            ready(Row(1), Row(2))
            owner.toggle(1, false); settle()
            val old = openWrites().single()
            assertTrue("premise: the old request's check admits", old.guard())
            snapshot = snapshot.copy(userInvalidations = snapshot.userInvalidations + 1)
            assertEquals("after the reissue it no longer admits", false, old.guard())
            owner.toggle(2, false); settle()
            val fresh = openWrites().single { it.id == 2 }
            assertEquals("new work is sent with its own check, which admits", true, fresh.guard())
        }
    }
}
