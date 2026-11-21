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
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.SourceAlertSetting
import com.jay.fxi.service.AlertEvent
import com.jay.fxi.service.AlertFamily
import com.jay.fxi.service.AlertPushRoute
import com.jay.fxi.service.AlertPushRouting
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
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S8-4a (`R4c/S8/s8_4a_contract.agreed.r1.md` §3 "JVM U2", §4): the corpus FCM goldens through the registry with the production
 * family list — the real bank owner and the real source owner, each behind a counting delegate that keeps its owner's type (the
 * bank family takes bank pushes, the source family is lifecycle-only). From the cold start's baseline: one sync golden in the
 * idle foreground is one delivery resolve, one main post, no further family resolve, one sync and one settings GET for each
 * family, each adopting its own answer; held in the background, repeated syncs send nothing and make the next return inside
 * both snapshots' freshness authoritative for each (a return without a sync sends nothing); a bank push or tap, readable or
 * not, reaches the bank family alone; a source or comparison golden, pushed or tapped, with a readable id or not, reaches
 * neither family for a minute.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class S84ATwoFamilyGoldenTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val grant = TopicGrantToken(11)
    private val corpus by lazy { ContractCorpusVerifier.verify(ContractResourceLoader.load()) }
    private val stamp = Instant.parse("2026-11-21T00:00:00Z")

    private fun golden(id: String): Map<String, String> =
        Json.parseToJsonElement(corpus.text(id)).jsonObject.getValue("data").jsonObject.mapValues { it.value.jsonPrimitive.content }
    private fun variant(id: String, key: String, value: String?): Map<String, String> =
        golden(id).toMutableMap().apply { if (value == null) remove(key) else put(key, value) }

    private fun bankRow(id: Int) = AlertSetting(id, "user-a", "kb", "usd-krw", AlertCondition.ABOVE, 1400.0 + id, true, false, stamp, stamp, null, null)
    private fun sourceRow(id: Int) =
        SourceAlertSetting(id, "user-a", "upbit", "usdt-krw", AlertCondition.ABOVE, 1400.0 + id, true, false, null, stamp, stamp, null)

    private class Process : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private inner class Rig(val test: TestScope) {
        private val identity: AuthIdentityFence = alice
        private val snapshot = TopicAccessSnapshot.INITIAL.copy(
            revision = 1L, facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet()), userInvalidations = 0L
        )
        private val premium = MutableStateFlow(OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.PremiumConfirmed))
        private val access = AlertSettingsAccess({ identity }, { TopicSessionFence(alice, "epoch-1", grant) }, SnapshotTopicUseAuthority { snapshot },
            { premium.value }, { true }, GraphProtectedAdmission({ identity }, DeletionAdmissionStore()))
        private val floor = AlertRateLimitFloor({ test.testScheduler.currentTime })
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
        val source = SourceAlertSettingsOwner(
            { _, _ -> CompletableDeferred<AuthBoundResult<List<SourceAlertSetting>>>().also { sourceGets += it }.await() },
            access, floor, AlertRetryPolicy(), { test.testScheduler.currentTime }, { 0.0 }, CoroutineScope(job + dispatcher), { delay(it) },
            premium, MutableStateFlow(0L), { _, _, _ -> error("S8-5a: no create") }, { _, _, _, _ -> error("S8-5a: no edit") }, { _, _, _ -> error("S8-5a: no delete") }
        )

        /** Every call the registry makes on each family, in order; each is handed to its owner exactly once. */
        val calls = mutableListOf<String>()
        private fun foreground(authoritative: Boolean) = if (authoritative) "foreground(authoritative)" else "foreground"
        private val countingBank = object : AlertFamilyAdapter {
            override fun onForeground(authoritative: Boolean) { calls += "bank:" + foreground(authoritative); bank.onForeground(authoritative) }
            override fun onBackground() { calls += "bank:background"; bank.onBackground() }
            override fun onSync() { calls += "bank:sync"; bank.onSync() }
            override fun onBankPush(id: Int?, isRepeat: String?) { calls += "bank:push($id,$isRepeat)"; bank.onBankPush(id, isRepeat) }
        }
        private val countingSource = object : AlertLifecycleAdapter {
            override fun onForeground(authoritative: Boolean) { calls += "source:" + foreground(authoritative); source.onForeground(authoritative) }
            override fun onBackground() { calls += "source:background"; source.onBackground() }
            override fun onSync() { calls += "source:sync"; source.onSync() }
        }
        var familyResolves = 0
        var deliveryResolves = 0
        var posts = 0
        var failures = 0
        val process = Process().also { it.registry.currentState = Lifecycle.State.STARTED }
        val registry = AlertFamilyRefresherRegistry(
            families = listOf({ familyResolves++; countingBank }, { familyResolves++; countingSource }),
            lifecycle = { process.registry },
            postToMain = { posts++; it(); Unit },
            premium = premium,
            liveIdentity = { identity },
            scope = CoroutineScope(job + dispatcher),
            reportFailure = { failures++ }
        )

        private var baseCalls = 0
        private var baseBankGets = 0
        private var baseSourceGets = 0
        private var basePosts = 0
        private var baseFamilyResolves = 0

        fun settle() = test.runCurrent()
        fun advance(millis: Long) { test.advanceTimeBy(millis); test.runCurrent() }
        fun newCalls() = calls.drop(baseCalls)
        /** (bank, source) settings GETs since the baseline. */
        fun newGets() = listOf(bankGets.size - baseBankGets, sourceGets.size - baseSourceGets)
        fun newPosts() = posts - basePosts
        fun newFamilyResolves() = familyResolves - baseFamilyResolves
        fun bankIds() = bank.state.value.settings?.map { it.id }
        fun sourceIds() = source.state.value.settings?.map { it.id }

        fun answerBank(rows: List<AlertSetting>) {
            val open = bankGets.filter { !it.isCompleted }
            check(open.size == 1) { "exactly one bank GET awaits its answer, not ${open.size}" }
            open.single().complete(AuthBoundResult({}, Result.success(rows)))
            settle()
        }
        fun answerSource(rows: List<SourceAlertSetting>) {
            val open = sourceGets.filter { !it.isCompleted }
            check(open.size == 1) { "exactly one source GET awaits its answer, not ${open.size}" }
            open.single().complete(AuthBoundResult({}, Result.success(rows)))
            settle()
        }

        /** The cold start in the foreground: one first load each, answered with one row each, adopted. The baseline. */
        fun loaded() {
            registry.start(); settle()
            check(calls == listOf("bank:foreground", "source:foreground") && familyResolves == 2) { "the start: $calls, $familyResolves" }
            answerBank(listOf(bankRow(7)))
            answerSource(listOf(sourceRow(21)))
            check(bankIds() == listOf(7) && sourceIds() == listOf(21) && failures == 0) { "the first loads were not adopted" }
            baseCalls = calls.size
            baseBankGets = bankGets.size
            baseSourceGets = sourceGets.size
            basePosts = posts
            baseFamilyResolves = familyResolves
        }

        fun toBackground() { process.registry.currentState = Lifecycle.State.CREATED; settle() }
        fun toForeground() { process.registry.currentState = Lifecycle.State.STARTED; settle() }

        /** FXiMessagingService's order: the route of the raw type and id, then the raw hand-over. */
        fun push(data: Map<String, String>): AlertPushRoute? {
            val type = data["type"] ?: return null
            val route = AlertPushRouting.route(type, data["setting_id"]) ?: return null
            AlertFamilyRefresherRegistry.deliverPush(type, data["setting_id"], data["is_repeat"], gateOpen = true, reportFailure = { failures++ }) {
                deliveryResolves++
                registry
            }
            settle()
            return route
        }

        /** The OFF tap's order: the route of the raw type and id, then its event with the raw is_repeat. */
        fun tap(data: Map<String, String>): AlertPushRoute? {
            val route = AlertPushRouting.route(data["type"], data["setting_id"]) ?: return null
            AlertFamilyRefresherRegistry.deliverTap(route.event, data["is_repeat"], gateOpen = true, reportFailure = { failures++ }) {
                deliveryResolves++
                registry
            }
            settle()
            return route
        }

        /** Neither family was reached and neither whole state changed, at once and again a minute later. */
        fun assertUntouched(label: String, beforeBank: BankAlertSettingsState, beforeSource: SourceAlertSettingsState) {
            fun untouched(at: String) {
                assertEquals("$label: no family call$at", emptyList<String>(), newCalls())
                assertEquals("$label: no delivery resolve, no main post, no family resolve$at", listOf(0, 0, 0),
                    listOf(deliveryResolves, newPosts(), newFamilyResolves()))
                assertEquals("$label: no GET$at", listOf(0, 0), newGets())
                assertEquals("$label: the bank state is kept$at", beforeBank, bank.state.value)
                assertEquals("$label: the source state is kept$at", beforeSource, source.state.value)
            }
            untouched("")
            advance(60_000)
            untouched(" a minute later")
            assertEquals("$label: no failure", 0, failures)
        }

        fun close() = job.cancel()
    }

    private fun rig(test: TestScope, body: Rig.() -> Unit) {
        val r = Rig(test)
        try {
            r.loaded()
            r.body()
        } finally {
            r.close()
        }
    }

    @Test fun `S8_4A_U01 the sync golden in the idle foreground is one silent delivery, one sync and one GET for each family`() = runTest {
        rig(this) {
            val route = push(golden(SYNC))
            assertEquals("a silent refresh", AlertPushRoute(AlertEvent.RefreshNeeded, showBanner = false), route)
            assertEquals("one sync for each family, in the registry's order", listOf("bank:sync", "source:sync"), newCalls())
            assertEquals("one delivery resolve, one main post, no further family resolve", listOf(1, 1, 0),
                listOf(deliveryResolves, newPosts(), newFamilyResolves()))
            assertEquals("one more GET for each family", listOf(1, 1), newGets())
            answerBank(listOf(bankRow(8)))
            answerSource(listOf(sourceRow(22)))
            assertEquals("each family adopts its own answer", listOf(listOf(8), listOf(22)), listOf(bankIds(), sourceIds()))
            advance(60_000)
            assertEquals("nothing more for a minute", listOf(1, 1), newGets())
            assertEquals("and no other family call", listOf("bank:sync", "source:sync"), newCalls())
            assertEquals("nothing reported", 0, failures)
        }
    }

    @Test fun `S8_4A_U02 syncs held in the background send nothing and make a fresh return authoritative for each family`() = runTest {
        // Inside both snapshots' 30 s freshness: only a held sync can make the return ask.
        rig(this) {
            toBackground()
            assertEquals("premise: both families went to the background", listOf("bank:background", "source:background"), newCalls())
            assertEquals("a silent route", false, push(golden(SYNC))!!.showBanner)
            push(golden(SYNC))
            advance(5_000)
            assertEquals("no GET in the background", listOf(0, 0), newGets())
            toForeground()
            assertEquals("the fresh return is authoritative for each family",
                listOf("bank:background", "source:background", "bank:foreground(authoritative)", "source:foreground(authoritative)"), newCalls())
            assertEquals("one GET each although both snapshots are fresh", listOf(1, 1), newGets())
            answerBank(listOf(bankRow(8)))
            answerSource(listOf(sourceRow(22)))
            advance(60_000)
            assertEquals("and nothing more for a minute", listOf(1, 1), newGets())
            assertEquals("two deliveries, two main posts, no further family resolve", listOf(2, 2, 0),
                listOf(deliveryResolves, newPosts(), newFamilyResolves()))
        }
        // The control: the same return without a sync asks nothing.
        rig(this) {
            toBackground()
            advance(5_000)
            toForeground()
            assertEquals("the control's return is plain for each family",
                listOf("bank:background", "source:background", "bank:foreground", "source:foreground"), newCalls())
            advance(60_000)
            assertEquals("the control sends nothing", listOf(0, 0), newGets())
        }
        // A minute in the background with the syncs held: still nothing sent before the return.
        rig(this) {
            toBackground()
            push(golden(SYNC)); push(golden(SYNC))
            advance(60_000)
            assertEquals("no GET in a minute of background", listOf(0, 0), newGets())
            toForeground()
            assertEquals("one authoritative GET each at the return", listOf(1, 1), newGets())
            assertEquals("authoritative for each family", listOf("bank:foreground(authoritative)", "source:foreground(authoritative)"),
                newCalls().drop(2))
        }
    }

    @Test fun `S8_4A_U03 a bank push or tap reaches the bank family alone`() = runTest {
        data class Case(val label: String, val data: Map<String, String>, val tapped: Boolean, val event: AlertEvent, val call: String)
        for (case in listOf(
            Case("once push", golden(ONCE), false, AlertEvent.SettingTriggered(101, AlertFamily.BANK), "bank:push(101,false)"),
            Case("repeat push", golden(REPEAT), false, AlertEvent.SettingTriggered(104, AlertFamily.BANK), "bank:push(104,true)"),
            Case("once tap", golden(ONCE), true, AlertEvent.SettingTriggered(101, AlertFamily.BANK), "bank:push(101,false)"),
            Case("repeat tap", golden(REPEAT), true, AlertEvent.SettingTriggered(104, AlertFamily.BANK), "bank:push(104,true)"),
            Case("unreadable id push", variant(ONCE, "setting_id", "x"), false, AlertEvent.RefreshNeeded, "bank:push(null,false)"),
            Case("unreadable id tap", variant(ONCE, "setting_id", "x"), true, AlertEvent.RefreshNeeded, "bank:push(null,null)"))) {
            rig(this) {
                val beforeSource = source.state.value
                val route = if (case.tapped) tap(case.data) else push(case.data)
                assertEquals("${case.label}: its route", AlertPushRoute(case.event, showBanner = true), route)
                assertEquals("${case.label}: one bank push hand-over and no source call", listOf(case.call), newCalls())
                assertEquals("${case.label}: one delivery resolve, one main post, no further family resolve", listOf(1, 1, 0),
                    listOf(deliveryResolves, newPosts(), newFamilyResolves()))
                assertEquals("${case.label}: the bank asks once and the source not at all", listOf(1, 0), newGets())
                assertEquals("${case.label}: the source state is kept", beforeSource, source.state.value)
                answerBank(listOf(bankRow(8)))
                advance(60_000)
                assertEquals("${case.label}: the bank adopts", listOf(8), bankIds())
                assertEquals("${case.label}: nothing more for a minute", listOf(1, 0), newGets())
                assertEquals("${case.label}: still one hand-over and no source call a minute later", listOf(case.call), newCalls())
                assertEquals("${case.label}: still one delivery resolve, one main post, no further family resolve a minute later",
                    listOf(1, 1, 0), listOf(deliveryResolves, newPosts(), newFamilyResolves()))
                assertEquals("${case.label}: the source state is still kept a minute later", beforeSource, source.state.value)
                assertEquals("${case.label}: no failure", 0, failures)
            }
        }
    }

    @Test fun `S8_4A_U04 a source or comparison golden, pushed or tapped, readable id or not, reaches neither family`() = runTest {
        val cases = OTHERS.flatMap { (id, expected) ->
            listOf(true, false).map { tapped -> Triple("$id ${if (tapped) "tap" else "push"}", golden(id) to tapped,
                AlertEvent.SettingTriggered(expected.second, expected.first)) }
        } + listOf(SOURCE_ONCE to AlertFamily.SOURCE, COMPARISON_ONCE to AlertFamily.COMPARISON).flatMap { (id, family) ->
            listOf(null, "x", "2147483648").flatMap { value ->
                listOf(true, false).map { tapped -> Triple("$id setting_id=$value ${if (tapped) "tap" else "push"}",
                    variant(id, "setting_id", value) to tapped, AlertEvent.FamilyRefreshNeeded(family)) }
            }
        }
        for ((label, input, event) in cases) {
            rig(this) {
                val (data, tapped) = input
                val beforeBank = bank.state.value
                val beforeSource = source.state.value
                val route = if (tapped) tap(data) else push(data)
                assertEquals("$label: its family's route with a banner", AlertPushRoute(event, showBanner = true), route)
                assertUntouched(label, beforeBank, beforeSource)
            }
        }
    }

    private companion object {
        const val ONCE = "fcm-rate-alert-once"
        const val REPEAT = "fcm-rate-alert-repeat"
        const val SYNC = "fcm-sync-alerts"
        const val SOURCE_ONCE = "fcm-source-alert-once"
        const val COMPARISON_ONCE = "fcm-comparison-alert-absolute-once"
        val OTHERS: Map<String, Pair<AlertFamily, Int>> = linkedMapOf(
            SOURCE_ONCE to (AlertFamily.SOURCE to 102), "fcm-source-alert-repeat-krx" to (AlertFamily.SOURCE to 105),
            COMPARISON_ONCE to (AlertFamily.COMPARISON to 103), "fcm-comparison-alert-signed-repeat" to (AlertFamily.COMPARISON to 106)
        )
    }
}
