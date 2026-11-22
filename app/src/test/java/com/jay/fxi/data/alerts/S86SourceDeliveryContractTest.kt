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
import com.jay.fxi.data.remote.AuthenticatedEndpoint
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.mapAlertHttpFailure
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.SourceAlertSetting
import com.jay.fxi.service.AlertEvent
import com.jay.fxi.service.AlertFamily
import com.jay.fxi.service.AlertTapCodec
import com.jay.fxi.service.AlertTapExtraKind
import com.jay.fxi.service.AlertTapExtras
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S8-6 J1–J5 (`R4c/S8/user_decision_s8_6.r1.md` §3–4, Codex r1 §5, r3 §2, r4 §1–3): an exchange `source_rate_alert` push and a
 * KRX-switch-OFF tap reach the real source owner only, through the production entry order mirrored in [S86Entry].
 * - J1: an admitted once push marks exactly its row at once, asks the source settings once, publishes once, emits nothing on the
 *   shared bus, and the bank family stays untouched; the answer is adopted as the server's.
 * - J2: repeat, any is_repeat other than exactly "false", an unreadable id, a row the snapshot lacks or a row of another tuple marks
 *   nothing and still asks the source settings once.
 * - J3: refused before publication: KRX, a tuple outside the five exchanges × usdt-krw, no premium, no identity, a closed gate.
 * - J4: an admitted OFF source tap returns no slot or bus event, clears the routing keys that are present and requests a source GET.
 * - J5: lifetimes and races: an account switch between intake and Main, a failed GET before the event's own GET, a manual refresh
 *   kept successful, an expired tap under a floor, the publish predicate after a revocation, a tap before the foreground.
 * newGets() counts the (bank, source) GETs sent after the answered cold-start loads, openSource() the source GETs whose reply deferreds are still incomplete;
 * acquisitions is compared with a value read in the same test, and the other counters are still zero when the cold start ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class S86SourceDeliveryContractTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val bob = AuthIdentityFence("user-b", 1)
    private val grantA = TopicGrantToken(11)
    private val grantB = TopicGrantToken(12)
    private val corpus by lazy { ContractCorpusVerifier.verify(ContractResourceLoader.load()) }
    private val stamp = Instant.parse("2026-11-22T00:00:00Z")

    private fun golden(id: String): Map<String, String> =
        Json.parseToJsonElement(corpus.text(id)).jsonObject.getValue("data").jsonObject.mapValues { it.value.jsonPrimitive.content }
    private fun once(vararg changes: Pair<String, String?>): Map<String, String> =
        golden(SOURCE_ONCE).toMutableMap().apply { changes.forEach { (k, v) -> if (v == null) remove(k) else put(k, v) } }

    private fun bankRow(id: Int) = AlertSetting(id, "user-a", "kb", "usd-krw", AlertCondition.ABOVE, 1400.0 + id, true, false, stamp, stamp, null, null)
    private fun sourceRow(id: Int, source: String = "upbit", uid: String = "user-a", enabled: Boolean = true, triggered: Boolean = false) =
        SourceAlertSetting(id, uid, source, "usdt-krw", AlertCondition.ABOVE, 1400.0 + id, enabled, triggered, null, stamp, stamp, null)

    private class Process : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** One fake intent: kinds as the app-made (Int id) or the OS-delivered (String id) extras. */
    private class Extras(initial: Map<String, Any?>) : AlertTapExtras {
        val values = initial.toMutableMap()
        override fun readInt(key: String, default: Int): Int = (values[key] as? Int) ?: default
        override fun readString(key: String): String? = values[key] as? String
        override fun kind(key: String): AlertTapExtraKind = when {
            !values.containsKey(key) -> AlertTapExtraKind.MISSING
            values[key] == null -> AlertTapExtraKind.NULL
            values[key] is String -> AlertTapExtraKind.STRING
            values[key] is Int -> AlertTapExtraKind.INT
            else -> AlertTapExtraKind.OTHER
        }
        override fun remove(key: String) { values.remove(key) }
    }

    private fun appMade(source: String = "upbit", asset: String = "usdt-krw", id: Int = 102, repeat: String = "false") = Extras(mapOf(
        AlertTapCodec.CUSTOM_TYPE to "source_rate_alert", AlertTapCodec.CUSTOM_ID to id, "is_repeat" to repeat, "source" to source, "asset" to asset))
    private fun osDelivered(source: String = "upbit", asset: String = "usdt-krw", id: String = "102", repeat: String = "false") = Extras(mapOf(
        AlertTapCodec.SERVER_TYPE to "source_rate_alert", AlertTapCodec.SERVER_ID to id, "is_repeat" to repeat, "source" to source, "asset" to asset))

    private inner class Rig(val test: TestScope, probe: AlertLifecycleAdapter? = null) {
        var identity: AuthIdentityFence? = alice
        var fence: TopicSessionFence? = TopicSessionFence(alice, "epoch-1", grantA)
        var snapshot = TopicAccessSnapshot.INITIAL.copy(
            revision = 1L, facts = TopicAccessFacts.NONE.copy(token = grantA, tokenStanding = true, userBlocks = emptySet()), userInvalidations = 0L)
        val premium = MutableStateFlow(OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.PremiumConfirmed))
        val deletions = DeletionAdmissionStore()
        val issuer = SnapshotTopicUseAuthority { snapshot }
        var acquisitions = 0
        private val counted = object : com.jay.fxi.data.remote.TopicUseAuthority by issuer {
            override fun acquire(fence: TopicSessionFence): com.jay.fxi.data.remote.TopicUseLifetime? {
                acquisitions++
                return issuer.acquire(fence)
            }
        }
        val access = AlertSettingsAccess({ identity }, { fence }, counted, { premium.value }, { true },
            GraphProtectedAdmission({ identity }, deletions))
        val floor = AlertRateLimitFloor({ test.testScheduler.currentTime })
        private val job = SupervisorJob()
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        val bankGets = mutableListOf<CompletableDeferred<AuthBoundResult<List<AlertSetting>>>>()
        val sourceGets = mutableListOf<CompletableDeferred<AuthBoundResult<List<SourceAlertSetting>>>>()
        val bank = BankAlertSettingsOwner(
            { _, _ -> CompletableDeferred<AuthBoundResult<List<AlertSetting>>>().also { bankGets += it }.await() },
            access, floor, AlertRetryPolicy(), { test.testScheduler.currentTime }, { 0.0 }, CoroutineScope(job + dispatcher), { delay(it) },
            premium, MutableStateFlow(0L), MutableStateFlow(false),
            { _, _, _, _ -> error("no toggle") }, { _, _, _ -> error("no delete") }, { _, _, _ -> error("no create") }, { _, _, _, _ -> error("no edit") }
        )
        var beforeWire: (() -> Unit)? = null
        val creates = mutableListOf<CompletableDeferred<AuthBoundResult<SourceAlertSetting>>>()
        val edits = mutableListOf<CompletableDeferred<AuthBoundResult<SourceAlertSetting>>>()
        val source = SourceAlertSettingsOwner(
            { _, admitted ->
                beforeWire?.invoke()
                if (admitted()) {
                    val reply = CompletableDeferred<AuthBoundResult<List<SourceAlertSetting>>>()
                    sourceGets += reply
                    try { reply.await() } finally { if (!reply.isCompleted) reply.cancel() }
                } else AuthBoundResult({}, Result.success(emptyList<SourceAlertSetting>()))
            },
            access, floor, AlertRetryPolicy(), { test.testScheduler.currentTime }, { 0.0 }, CoroutineScope(job + dispatcher), { delay(it) },
            premium, MutableStateFlow(0L),
            { _, _, _ -> CompletableDeferred<AuthBoundResult<SourceAlertSetting>>().also { creates += it }.await() },
            { _, _, _, _ -> CompletableDeferred<AuthBoundResult<SourceAlertSetting>>().also { edits += it }.await() },
            { _, _, _ -> error("no delete") }
        )
        val outcomes = mutableListOf<ManualRefreshOutcome>()
        var deferPosts = false
        val posted = mutableListOf<() -> Unit>()
        var resolves = 0
        var failures = 0
        val emitted = mutableListOf<AlertEvent>()
        /** Each publication request and the predicate it was handed; [published] counts the requests whose predicate held. */
        val predicates = mutableListOf<() -> Boolean>()
        var published = 0
        val process = Process().also { it.registry.currentState = Lifecycle.State.STARTED }
        val registry = S86Entry.registry(
            families = listOf<() -> AlertLifecycleAdapter>({ bank }, { source }) +
                listOfNotNull(probe).map { adapter -> { adapter } },
            lifecycle = { process.registry },
            postToMain = { action -> if (deferPosts) posted += action else action(); Unit },
            premium = premium,
            liveIdentity = { identity },
            scope = CoroutineScope(job + dispatcher),
            reportFailure = { failures++ },
            captureSourceUse = { access.acquire() }
        )

        init {
            CoroutineScope(job + UnconfinedTestDispatcher(test.testScheduler)).launch { source.manualOutcomes.collect { outcomes += it } }
        }

        private var baseBankGets = 0
        private var baseSourceGets = 0
        fun settle() = test.runCurrent()
        fun advance(millis: Long) { test.advanceTimeBy(millis); test.runCurrent() }
        /** (bank, source) settings GETs since the baseline. */
        fun newGets() = listOf(bankGets.size - baseBankGets, sourceGets.size - baseSourceGets)
        fun sourceRows() = source.state.value.settings
        fun row(id: Int) = sourceRows()?.single { it.id == id }?.let { it.triggered to it.isEnabled }

        fun answerBank(rows: List<AlertSetting>) {
            val open = bankGets.filter { !it.isCompleted }
            check(open.size == 1) { "exactly one bank GET awaits its answer, not ${open.size}" }
            open.single().complete(AuthBoundResult({}, Result.success(rows)))
            settle()
        }
        fun answerSource(rows: List<SourceAlertSetting>) = completeSource(AuthBoundResult({}, Result.success(rows)))
        fun failSource(status: Int) {
            val failure = AuthenticatedHttpFailure(status, headersOf(), ByteArray(0), null, null, AuthenticatedFailureKind.OTHER_HTTP)
            completeSource(AuthBoundResult({}, Result.failure(mapAlertHttpFailure(failure))))
        }
        private fun completeSource(result: AuthBoundResult<List<SourceAlertSetting>>) {
            val open = sourceGets.filter { !it.isCompleted }
            check(open.size == 1) { "exactly one source GET awaits its answer, not ${open.size}" }
            open.single().complete(result)
            settle()
        }
        fun openSource() = sourceGets.count { !it.isCompleted }

        /** The cold start in the foreground: one first load each, adopted. The baseline. */
        fun loaded(sourceRowsAtStart: List<SourceAlertSetting> = listOf(sourceRow(102), sourceRow(21))) {
            registry.start(); settle()
            answerBank(listOf(bankRow(102)))
            answerSource(sourceRowsAtStart)
            check(failures == 0 && sourceRows()?.map { it.id } == sourceRowsAtStart.map { it.id }) { "the first loads were not adopted" }
            baseBankGets = bankGets.size
            baseSourceGets = sourceGets.size
        }

        fun toBackground() { process.registry.currentState = Lifecycle.State.CREATED; settle() }
        fun toForeground() { process.registry.currentState = Lifecycle.State.STARTED; settle() }

        fun push(data: Map<String, String>, gateOpen: Boolean = true) {
            S86Entry.push(data, gateOpen, { resolves++; registry }, { emitted += it }) { admitted ->
                predicates += admitted
                if (admitted()) published++
            }
            settle()
        }
        fun tap(extras: AlertTapExtras, gateOpen: Boolean = true): AlertEvent? =
            S86Entry.tap(extras, gateOpen) { resolves++; registry }.also { settle() }

        fun switchTo(next: AuthIdentityFence?, grant: TopicGrantToken, premiumState: PremiumAccessState = PremiumAccessState.PremiumConfirmed) {
            identity = next
            fence = next?.let { TopicSessionFence(it, "epoch-1", grant) }
            snapshot = snapshot.copy(facts = snapshot.facts.copy(token = grant))
            premium.value = OwnedPremiumAccess(next?.uid, next?.authGeneration, premiumState)
            settle()
        }

        fun close() = job.cancel()
    }

    private fun rig(test: TestScope, rows: List<SourceAlertSetting>? = null, probe: AlertLifecycleAdapter? = null, body: Rig.() -> Unit) {
        val r = Rig(test, probe)
        try {
            if (rows == null) r.loaded() else r.loaded(rows)
            r.body()
        } finally {
            r.close()
        }
    }

    @Test fun `S86_J1 an admitted exchange once push marks its row at once, asks the source settings once and publishes once`() = runTest {
        rig(this) {
            val beforeBank = bank.state.value
            push(golden(SOURCE_ONCE))
            assertEquals("J1: the once row is marked at once", true to false, row(102))
            assertEquals("J1: the source settings are asked once and the bank not at all", listOf(0, 1), newGets())
            assertEquals("J1: one publication", 1, published)
            assertEquals("J1: nothing on the shared bus", emptyList<AlertEvent>(), emitted)
            assertEquals("J1: the other source row is kept", false to true, row(21))
            assertEquals("J1: the bank state is kept", beforeBank, bank.state.value)
            answerSource(listOf(sourceRow(102, enabled = true, triggered = false), sourceRow(21)))
            assertEquals("J1: the server's answer is adopted over the local mark", false to true, row(102))
            advance(60_000)
            assertEquals("J1: nothing more for a minute", listOf(0, 1), newGets())
            assertEquals("J1: no failure", 0, failures)
        }
    }

    @Test fun `S86_J2 a repeat, an unreadable id or a missing or foreign row marks nothing and still asks once`() = runTest {
        val cases = listOf(
            "repeat true" to once("is_repeat" to "true"),
            "repeat missing" to once("is_repeat" to null),
            "repeat False" to once("is_repeat" to "False"),
            "id missing" to once("setting_id" to null),
            "id x" to once("setting_id" to "x"),
            "id overflow" to once("setting_id" to "2147483648"),
            "row the snapshot lacks" to once("setting_id" to "103")
        )
        for ((label, data) in cases) rig(this) {
            push(data)
            assertEquals("J2 $label: the source settings are asked once and the bank not at all", listOf(0, 1), newGets())
            assertEquals("J2 $label: no row is marked", listOf(false to true, false to true), listOf(row(102), row(21)))
            assertEquals("J2 $label: one publication", 1, published)
        }
        // The same id under another exchange: the tuple of the row and of the payload differ.
        rig(this, rows = listOf(sourceRow(102, source = "bithumb"), sourceRow(21))) {
            push(golden(SOURCE_ONCE))
            assertEquals("J2 foreign tuple: the source settings are asked once", listOf(0, 1), newGets())
            assertEquals("J2 foreign tuple: the bithumb row is not marked", false to true, row(102))
        }
    }

    @Test fun `S86_J3 a refused push publishes nothing, emits nothing, marks nothing and sends no GET`() = runTest {
        data class Case(val label: String, val data: Map<String, String>, val gateOpen: Boolean = true, val setup: Rig.() -> Unit = {})
        val cases = listOf(
            Case("KRX repeat golden", golden(SOURCE_KRX)),
            Case("exchange with the futures asset", once("asset" to "usd-krw-futures")),
            Case("a bank as source", once("source" to "kb")),
            Case("source missing", once("source" to null)),
            Case("asset missing", once("asset" to null)),
            Case("closed gate", golden(SOURCE_ONCE), gateOpen = false),
            Case("free", golden(SOURCE_ONCE)) { premium.value = OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.FreeConfirmed); settle() },
            Case("undecided", golden(SOURCE_ONCE)) { premium.value = OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.Pending(false)); settle() },
            Case("deleting", golden(SOURCE_ONCE)) { deletions.begin(alice, "delete-a") },
            Case("signed out", golden(SOURCE_ONCE)) { identity = null; settle() }
        )
        // The positive control first: the same rig admits the exchange push.
        rig(this) {
            push(golden(SOURCE_ONCE))
            assertEquals("J3 control: an admitted push publishes once", 1, published)
        }
        for (case in cases) rig(this) {
            case.setup(this)
            val before = sourceRows()
            val baseGets = newGets()
            push(case.data, case.gateOpen)
            assertEquals("J3 ${case.label}: nothing on the shared bus", emptyList<AlertEvent>(), emitted)
            assertEquals("J3 ${case.label}: no publication request", 0, predicates.size)
            assertEquals("J3 ${case.label}: no GET", baseGets, newGets())
            assertEquals("J3 ${case.label}: no row changed", before, sourceRows())
            if (!case.gateOpen) assertEquals("J3 closed gate: no registry resolve", 0, resolves)
            if (case.label in setOf("KRX repeat golden", "exchange with the futures asset", "a bank as source", "source missing", "asset missing")) {
                assertEquals("J3 ${case.label}: tuple rejected before resolve", 0, resolves)
            }
        }
    }

    @Test fun `S86_J4 an admitted OFF tap is consumed by the source family, clears its keys and asks once`() = runTest {
        for ((label, extras) in listOf("app-made Int id" to appMade(), "OS String id" to osDelivered())) rig(this) {
            val beforeBank = bank.state.value
            val event = tap(extras)
            assertNull("J4 $label: no event for the cold slot or the warm bus", event)
            assertEquals("J4 $label: the source settings are asked once and the bank not at all", listOf(0, 1), newGets())
            assertEquals("J4 $label: the once row is marked", true to false, row(102))
            assertEquals("J4 $label: its routing keys are gone", emptySet<String>(), extras.values.keys)
            assertEquals("J4 $label: the bank state is kept", beforeBank, bank.state.value)
        }
        // A second read of a consumed Intent cannot enqueue another refresh.
        rig(this) {
            val extras = osDelivered()
            tap(extras)
            answerSource(listOf(sourceRow(102)))
            val before = newGets()
            assertNull("J4 replay: no event", tap(extras))
            assertEquals("J4 replay: no new request", before, newGets())
        }
        // A valid tuple with an unreadable id is the source family's refresh: consumed, asked once, no row marked.
        rig(this) {
            val extras = osDelivered(id = "x")
            assertNull("J4 unreadable id: no event", tap(extras))
            assertEquals("J4 unreadable id: the source settings are asked once and the bank not at all", listOf(0, 1), newGets())
            assertEquals("J4 unreadable id: no row is marked", false to true, row(102))
            assertEquals("J4 unreadable id: its routing keys are gone", emptySet<String>(), extras.values.keys)
        }
        for (raw in listOf<String?>(null, "true", "False")) rig(this) {
            val extras = Extras(osDelivered().values.toMutableMap().apply {
                put(AlertTapCodec.CUSTOM_TYPE, "source_rate_alert")
                if (raw == null) remove("is_repeat") else put("is_repeat", raw)
                put("unrelated", "keep")
            })
            assertNull("J4 raw repeat: no event", tap(extras))
            assertEquals("J4 raw repeat: only unrelated extras survive", mapOf("unrelated" to "keep"), extras.values)
            assertEquals("J4 raw repeat: no mark", false to true, row(102))
            assertEquals("J4 raw repeat: only the source asks", listOf(0, 1), newGets())
        }
    }

    @Test fun `S86_J4b a source tap with refused admission or invalid type or tuple clears its routing keys without delivery`() = runTest {
        val cases = listOf(
            "KRX" to appMade(source = "krx", asset = "usd-krw-futures"),
            "asset wrong kind" to Extras(appMade().values + ("asset" to 7)),
            "type wrong kind" to Extras(osDelivered().values + (AlertTapCodec.CUSTOM_TYPE to 42)),
            "type conflict" to Extras(mapOf(AlertTapCodec.CUSTOM_TYPE to "rate_alert", AlertTapCodec.SERVER_TYPE to "source_rate_alert",
                AlertTapCodec.CUSTOM_ID to 102, "is_repeat" to "false", "source" to "upbit", "asset" to "usdt-krw")),
            "source missing" to Extras(appMade().values - "source")
        )
        for ((label, extras) in cases) rig(this) {
            val event = tap(extras)
            assertNull("J4b $label: no event for the cold slot or the warm bus", event)
            assertEquals("J4b $label: no GET", listOf(0, 0), newGets())
            assertEquals("J4b $label: no row marked", false to true, row(102))
            assertEquals("J4b $label: its routing keys are gone", emptySet<String>(), extras.values.keys)
        }
        rig(this) {
            val extras = appMade()
            assertNull("J4b closed gate: consumed", tap(extras, gateOpen = false))
            assertEquals("J4b closed gate: no resolve", 0, resolves)
            assertEquals("J4b closed gate: no GET", listOf(0, 0), newGets())
            assertEquals("J4b closed gate: keys cleared", emptySet<String>(), extras.values.keys)
        }
        rig(this) {
            premium.value = OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.FreeConfirmed); settle()
            val extras = appMade()
            assertNull("J4b free: no event", tap(extras))
            assertEquals("J4b free: no outstanding source GET", 0, openSource())
            assertEquals("J4b free: its routing keys are gone", emptySet<String>(), extras.values.keys)
        }
    }

    @Test fun `S86_J5a a push captured before an account switch publishes and asks nothing for the next account`() = runTest {
        rig(this) {
            deferPosts = true
            push(golden(SOURCE_ONCE))
            deferPosts = false
            switchTo(bob, grantB)
            // Bob's own sign-in load, answered without row 102.
            if (openSource() == 1) answerSource(listOf(sourceRow(31, uid = "user-b")))
            if (bankGets.any { !it.isCompleted }) answerBank(emptyList())
            val getsBefore = newGets()
            posted.toList().forEach { it() }
            posted.clear()
            settle()
            assertEquals("J5a: nothing published for the next account", 0, published)
            assertEquals("J5a: no GET caused by the earlier push", getsBefore, newGets())
            assertEquals("J5a: bob's list is kept", listOf(31), sourceRows()?.map { it.id })
        }
    }

    @Test fun `S86_J5b a GET that fails after the event started keeps the event's own GET`() = runTest {
        rig(this) {
            push(golden(SYNC))
            assertEquals("premise: the sync's source GET is out", 1, openSource())
            push(once("setting_id" to "103"))
            failSource(400)
            assertEquals("J5b: the event's own source GET follows the failed one", 1, openSource())
            answerSource(listOf(sourceRow(102), sourceRow(21), sourceRow(103)))
            assertEquals("J5b: the new row is shown", listOf(102, 21, 103), sourceRows()?.map { it.id })
        }
    }

    @Test fun `S86_J5c a repeat during a manual refresh keeps the manual success`() = runTest {
        rig(this) {
            source.manual(); settle()
            assertEquals("premise: the manual GET is out", 1, openSource())
            push(once("is_repeat" to "true"))
            answerSource(listOf(sourceRow(102), sourceRow(21)))
            assertEquals("J5c: the manual refresh succeeded on its own answer", listOf<ManualRefreshOutcome>(ManualRefreshOutcome.Succeeded), outcomes)
            assertEquals("J5c: the event's own source GET follows", 1, openSource())
            failSource(400)
            assertEquals("J5c: a failure of the event's GET does not turn the manual outcome", listOf<ManualRefreshOutcome>(ManualRefreshOutcome.Succeeded), outcomes)
        }
    }

    @Test fun `S86_J5d an admitted tap waiting on the floor asks once, and once its use expired asks nothing`() = runTest {
        for (expire in listOf(false, true)) rig(this) {
            floor.record(AuthenticatedEndpoint.SOURCE_NOTIFICATION_SETTINGS, AlertRateLimitEvidence.of(emptyList(), 429, "5"))
            tap(appMade(repeat = "true"))
            assertEquals("premise: nothing is sent under the floor", 0, openSource())
            if (expire) {
                snapshot = snapshot.copy(userInvalidations = 1L)
                settle()
            }
            advance(6_000)
            if (!expire) assertEquals("J5d control: the tap's GET goes after the floor", listOf(0, 1), newGets())
            else assertEquals("J5d expired: no GET caused by the expired tap", listOf(0, 0), newGets())
        }
    }

    @Test fun `S86_J5e the publication predicate sees a revocation after the request`() = runTest {
        rig(this) {
            push(golden(SOURCE_ONCE))
            assertEquals("premise: one publication request", 1, predicates.size)
            premium.value = OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.FreeConfirmed)
            settle()
            assertEquals("J5e: the predicate refuses after the revocation", false, predicates.single()())
        }
    }

    @Test fun `S86_J5f an admitted tap before the foreground asks once at a fresh return`() = runTest {
        rig(this) {
            toBackground()
            tap(appMade(repeat = "true"))
            assertEquals("premise: no GET in the background", listOf(0, 0), newGets())
            toForeground()
            assertEquals("J5f: the fresh return asks the source settings once for the tap", listOf(0, 1), newGets())
        }
    }

    @Test fun `S86_J5g an older success cannot settle a later miss across background`() = runTest {
        rig(this) {
            source.authoritative()
            settle()
            assertEquals("premise: G0", 1, openSource())
            push(once("setting_id" to "103"))
            answerSource(listOf(sourceRow(102), sourceRow(21)))
            assertEquals("J5g: G0 leaves the follow-up GET outstanding", 1, openSource())
            toBackground()
            advance(1_000)
            val requests = newGets()[1]
            toForeground()
            assertEquals("J5g: a fresh return restores the interrupted GET", listOf(0, requests + 1), newGets())
            answerSource(listOf(sourceRow(102), sourceRow(21), sourceRow(103)))
            assertEquals("J5g: the new row is visible", false to true, row(103))
        }
    }

    @Test fun `S86_J5h a GET issued before a mark cannot undo that mark`() = runTest {
        rig(this) {
            source.authoritative()
            settle()
            assertEquals("premise: G0", 1, openSource())
            push(golden(SOURCE_ONCE))
            assertEquals("premise: marked", true to false, row(102))
            answerSource(listOf(sourceRow(102), sourceRow(21)))
            assertEquals("J5h: the older body cannot undo the mark", true to false, row(102))
            assertEquals("J5h: G1 is outstanding", 1, openSource())
            answerSource(listOf(sourceRow(102), sourceRow(21)))
            assertEquals("J5h: G1 may replace the mark", false to true, row(102))
        }
    }

    @Test fun `S86_J5i a create echo cannot undo an overlapping delivery mark`() = runTest {
        rig(this) {
            val request = source.addAlert(SourceAlertDraft("upbit", AlertCondition.ABOVE, 1502.0, true, null))
            settle()
            assertEquals("premise: create entered", 1, creates.size)
            push(golden(SOURCE_ONCE))
            assertEquals("premise: once marked during create", true to false, row(102))
            assertEquals("J5i: GET waits for create", 0, openSource())
            creates.single().complete(AuthBoundResult({}, Result.success(sourceRow(102))))
            settle()
            assertEquals("J5i: overlapping create cannot report Saved", true, request.outcome.value is SourceAlertSaveOutcome.Failed)
            assertEquals("J5i: create kept the mark", true to false, row(102))
            assertEquals("J5i: one settlement GET", listOf(0, 1), newGets())
            answerSource(listOf(sourceRow(102)))
            assertEquals("J5i: settlement adopted", false to true, row(102))
        }
    }

    @Test fun `S86_J5j a background tap cannot borrow the next grant`() = runTest {
        rig(this) {
            toBackground()
            tap(appMade(repeat = "true"))
            snapshot = snapshot.copy(userInvalidations = snapshot.userInvalidations + 1)
            toForeground()
            assertEquals("J5j: expired background tap stays silent on return", listOf(0, 0), newGets())
        }
    }

    @Test fun `S86_J5k sync dispatches once and a held sync survives until return`() = runTest {
        var syncs = 0
        val probe = object : AlertLifecycleAdapter {
            override fun onForeground(authoritative: Boolean) {}
            override fun onBackground() {}
            override fun onSync() { syncs++ }
        }
        val r = Rig(this, probe)
        try {
            r.loaded()
            r.push(golden(SYNC))
            assertEquals("J5k: one sync dispatch", 1, syncs)
            r.answerBank(listOf(bankRow(102)))
            r.answerSource(listOf(sourceRow(102)))
            r.toBackground()
            r.push(golden(SYNC))
            r.push(golden(SYNC))
            assertEquals("J5k: background adds no GET", listOf(1, 1), r.newGets())
            r.toForeground()
            assertEquals("J5k: one additional GET per family on return after two held syncs", listOf(2, 2), r.newGets())
        } finally { r.close() }
    }

    @Test fun `S86_J5l the final wire guard and a late body use the original grant`() = runTest {
        rig(this) {
            beforeWire = { snapshot = snapshot.copy(userInvalidations = snapshot.userInvalidations + 1) }
            tap(appMade(repeat = "true"))
            assertEquals("J5l: no wire after last-moment revocation", listOf(0, 0), newGets())
        }
        rig(this) {
            tap(appMade(repeat = "true"))
            assertEquals("premise: admitted send", 1, openSource())
            snapshot = snapshot.copy(userInvalidations = snapshot.userInvalidations + 1)
            answerSource(listOf(sourceRow(31)))
            assertEquals("J5l: expired response cannot replace the list", listOf(102, 21), sourceRows()?.map { it.id })
        }
    }

    @Test fun `S86_J5m a pending row edit retains its row across a delivery`() = runTest {
        rig(this) {
            val request = source.editAlert(102, SourceAlertEdit(threshold = 1502.0))
            settle()
            assertEquals("premise: edit entered", 1, edits.size)
            val before = row(102)
            push(golden(SOURCE_ONCE))
            assertEquals("J5m: no delivery overwrite during edit", before, row(102))
            assertEquals("J5m: event waits for edit", listOf(0, 0), newGets())
            edits.single().complete(AuthBoundResult({}, Result.success(sourceRow(102))))
            settle()
            assertEquals("J5m: edit succeeds", true, request.outcome.value is SourceAlertSaveOutcome.Saved)
            assertEquals("J5m: outstanding event GET", listOf(0, 1), newGets())
        }
    }

    @Test fun `S86_J5n revocation before token update or post stops that effect`() = runTest {
        val service = com.jay.fxi.S6Krx3cShellSourceContract.load().getValue("service/FXiMessagingService.kt")
        val raw = com.jay.fxi.S6Krx3cShellSourceContract.function(service, "showAlertNotification").raw
        val statements = com.jay.fxi.PostS6ApprovedDelta.codeAndStrings(raw).trim().replace(Regex("""\s+"""), " ")
        val entryGuard = statements.startsWith("if (!ReleaseAdmission.isOpen || !admitted()) return ")
        val tokenGuard = statements.contains("if (!admitted()) return val pendingIntent = PendingIntent.getActivity(")
        val postGuard = statements.endsWith("if (admitted()) notificationManager.notify(notificationId, notification)")
        for (cut in 0..2) rig(this) {
            var tokens = 0
            var posts = 0
            S86Entry.push(golden(SOURCE_ONCE), true, { registry }, { emitted += it }) { admitted ->
                fun revoke() { snapshot = snapshot.copy(userInvalidations = snapshot.userInvalidations + 1) }
                if (!entryGuard || admitted()) {
                    if (cut == 1) revoke()
                    if (!tokenGuard || admitted()) {
                        tokens++
                        if (cut == 2) revoke()
                        if (!postGuard || admitted()) posts++
                    }
                }
            }
            settle()
            val expected = when (cut) { 1 -> 0 to 0; 2 -> 1 to 0; else -> 1 to 1 }
            assertEquals("J5n: token updates and posts at cut $cut", expected, tokens to posts)
        }
    }

    @Test fun `S86_J4c a valid exchange fallback keeps the bank idle through settlement`() = runTest {
        for (id in listOf<String?>("102", null, "x", "2147483648")) {
            var bankDispatches = 0
            val observer = object : AlertFamilyAdapter {
                override fun onForeground(authoritative: Boolean) {}
                override fun onBackground() {}
                override fun onSync() { bankDispatches++ }
                override fun onBankPush(id: Int?, isRepeat: String?) { bankDispatches++ }
            }
            for (tapped in listOf(false, true)) rig(this, probe = observer) {
                val before = bank.state.value
                if (tapped) {
                    val extras = Extras(osDelivered().values.toMutableMap().apply {
                        if (id == null) remove(AlertTapCodec.SERVER_ID) else put(AlertTapCodec.SERVER_ID, id)
                    })
                    assertNull("J4c: no slot or bus event", tap(extras))
                    assertEquals("J4c: consumed", emptySet<String>(), extras.values.keys)
                } else push(once("setting_id" to id))
                assertEquals("J4c: only the source asks", listOf(0, 1), newGets())
                assertEquals("J4c: no bank dispatch", 0, bankDispatches)
                assertEquals("J4c: bank snapshot at intake", before, bank.state.value)
                assertEquals("J4c: immediate mark only for readable hit",
                    if (id == "102") true to false else false to true, row(102))
                answerSource(listOf(sourceRow(102), sourceRow(21)))
                advance(60_000)
                assertEquals("J4c: no delayed bank dispatch", 0, bankDispatches)
                assertEquals("J4c: bank snapshot after settlement", before, bank.state.value)
                assertEquals("J4c: bank stays idle for a minute", listOf(0, 1), newGets())
                assertEquals("J4c: no shared event", emptyList<AlertEvent>(), emitted)
            }
        }
    }

    @Test fun `S86_J5o queued work stays with its auth generation and use`() = runTest {
        for (relogin in listOf(false, true)) rig(this) {
            deferPosts = true
            push(golden(SOURCE_ONCE))
            deferPosts = false
            assertEquals("premise: J5o queued one source callback before revocation", 1, posted.size)
            if (relogin) {
                switchTo(AuthIdentityFence(alice.uid, alice.authGeneration + 1), grantB)
                if (openSource() == 1) answerSource(listOf(sourceRow(102)))
                if (bankGets.any { !it.isCompleted }) answerBank(listOf(bankRow(102)))
            } else snapshot = snapshot.copy(userInvalidations = snapshot.userInvalidations + 1)
            val before = sourceRows()
            val gets = newGets()
            posted.single().invoke()
            settle()
            assertEquals("J5o: revoked callback cannot request publication", 0, predicates.size)
            assertEquals("J5o: no old-session mark", before, sourceRows())
            assertEquals("J5o: no old-session GET", gets, newGets())
        }
    }

    @Test fun `S86_J5p a live delivery waiting on the floor sends with its captured use`() = runTest {
        rig(this) {
            floor.record(AuthenticatedEndpoint.SOURCE_NOTIFICATION_SETTINGS, AlertRateLimitEvidence.of(emptyList(), 429, "5"))
            tap(appMade(repeat = "true"))
            assertEquals("premise: floor holds the delivery", 0, openSource())
            val beforeWake = acquisitions
            advance(6_000)
            assertEquals("premise: live tap sent", listOf(0, 1), newGets())
            assertEquals("J5p: no second use acquisition at send", beforeWake, acquisitions)
        }
    }

    @Test fun `S86_J5q a withdrawn tap waiting on a row write adds no wire`() = runTest {
        val counts = mutableListOf<List<Int>>()
        for (inject in listOf(false, true)) rig(this) {
            val request = source.editAlert(102, SourceAlertEdit(threshold = 1502.0))
            settle()
            assertEquals("premise: edit in flight", 1, edits.size)
            if (inject) tap(appMade(repeat = "true"))
            assertEquals("J5q: event cannot send through the write", listOf(0, 0), newGets())
            snapshot = snapshot.copy(userInvalidations = snapshot.userInvalidations + 1)
            edits.single().complete(AuthBoundResult({}, Result.success(sourceRow(102))))
            settle()
            assertEquals("premise: edit retired", false, request.outcome.value == SourceAlertSaveOutcome.Pending)
            if (openSource() == 1) answerSource(listOf(sourceRow(102)))
            advance(5_000)
            counts += newGets()
        }
        assertEquals("J5q: no incremental wire from the expired tap", counts.first(), counts.last())
    }

    private companion object {
        const val SOURCE_ONCE = "fcm-source-alert-once"
        const val SOURCE_KRX = "fcm-source-alert-repeat-krx"
        const val SYNC = "fcm-sync-alerts"
    }
}
