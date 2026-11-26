package com.jay.fxi.data.alerts

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.jay.fxi.contract.ContractCorpusVerifier
import com.jay.fxi.contract.ContractFamily
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
import com.jay.fxi.service.AlertEvent
import com.jay.fxi.service.AlertFamily
import com.jay.fxi.service.AlertPushRoute
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 U2 contract (R4c/S7/s7u2/u2_contract.claude.r2.md, scope R4c/S7/s7_next_after_e5d.agreed.r1.md): the seven server FCM
 * golden payloads (`contracts/v2/fcm/`) supply raw data strings to [AlertPushRouting.route] and then
 * [AlertFamilyRefresherRegistry.deliverPush] (assembled in FXiMessagingService's order), or, for the six visible goldens,
 * [AlertFamilyRefresherRegistry.deliverTap] (the OFF tap's JVM meaning chain). Bank and sync inputs reach the real registry
 * and [BankAlertSettingsOwner]; source/comparison inputs assert routing and absence of bank delivery.
 *
 * The rig ports the S7E5C owner rig (real access, use authority, deletion store and floor on virtual time; only GET/PUT/DELETE
 * are scripted) and the registry delivery harness (the real registry through its internal constructor, an immediate main, a
 * lifecycle registry without main-thread checks, injected failure counters). The one family is a counting adapter that records
 * every call and hands it to the owner exactly once. Every GET count is the number of injected getSettings calls after the
 * start's first load, not an HTTP send and not an FCM transport.
 *
 * Not this file's: AlertEventBus emits, banners drawn or OS notifications (route.showBanner and the original goldens' notification shape
 * are asserted without invoking a renderer), MainActivity's extra reading, native extra types, cold/warm read/remove and restoration (AlertN1StructureTest
 * S7E5C_S01 keeps the structure lock; real Intents are a later device obligation), the ON custody path, a real FCM transport or
 * another device, source/comparison positive handlers (S8/S9), and the background debt × floor × retry × trailing-GET
 * combinations already locked by the S7N1/S7E5C contracts. The delivery `gateOpen` here is the helper's app-owned delivery gate,
 * not KrxContestSwitch.OFF and not ReleaseAdmission's whole-service early return.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class S7U2FcmGoldenConsumptionContractTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val grantA = TopicGrantToken(11)
    private val stamp = Instant.parse("2026-11-20T01:00:00Z")

    private fun setting(id: Int, enabled: Boolean = true, triggered: Boolean = false) =
        AlertSetting(id, "user-a", "hana", "usd-krw", AlertCondition.ABOVE, 1400.0 + id, enabled, triggered, stamp, stamp, null, null)

    /** The expected local once mark before the scripted GET answer: disabled and triggered. */
    private fun sent(id: Int) = setting(id).copy(isEnabled = false, triggered = true)

    /** The start's snapshot: the two bank golden ids (101, 104) and two unrelated control rows. */
    private val loadedRows = listOf(setting(101), setting(102), setting(104), setting(105))

    private fun confirmed(id: AuthIdentityFence) = OwnedPremiumAccess(id.uid, id.authGeneration, PremiumAccessState.PremiumConfirmed)
    private fun fenceFor(id: AuthIdentityFence, grant: TopicGrantToken) = TopicSessionFence(id, "epoch-1", grant)
    private fun standing(grant: TopicGrantToken) = TopicAccessSnapshot.INITIAL.copy(
        revision = 1L,
        facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet())
    )

    // ---- the golden corpus ----

    private val corpus by lazy { ContractCorpusVerifier.verify(ContractResourceLoader.load()) }

    private fun root(id: String): JsonObject = Json.parseToJsonElement(corpus.text(id)).jsonObject

    /** The golden's data, each value the JSON string itself: no trim, no case change, no Boolean reading. */
    private fun golden(id: String): Map<String, String> =
        root(id).getValue("data").jsonObject.mapValues { (key, value) ->
            val primitive = value.jsonPrimitive
            check(primitive.isString) { "$id data.$key is not a JSON string" }
            primitive.content
        }

    private fun variant(id: String, key: String, value: String?): Map<String, String> =
        golden(id).toMutableMap().apply { if (value == null) remove(key) else put(key, value) }

    // ---- scripted calls (the S7E5C owner rig) ----

    private class Call(val kind: String) {
        val outcome = CompletableDeferred<AuthBoundResult<*>>()
        var cancelled = false
        val open: Boolean get() = !outcome.isCompleted && !cancelled
    }

    private fun okList(rows: List<AlertSetting>): AuthBoundResult<List<AlertSetting>> = AuthBoundResult({}, Result.success(rows))

    /** The real lifecycle registry, without main-thread checks. */
    private class Process : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun bank(id: Int?, raw: String?) = "bank($id,${raw?.let { "\"$it\"" } ?: "null"})"

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
            { _, _ -> scripted(Call("GET")) },
            access, floor, AlertRetryPolicy(), { test.testScheduler.currentTime }, { 0.0 },
            CoroutineScope(job + dispatcher),
            { delay(it) },
            premium, MutableStateFlow(0L), MutableStateFlow(false),
            { _, _, _, _ -> scripted(Call("PUT")) },
            { _, _, _ -> scripted(Call("DELETE")) },
            { _, _, _ -> error("S7U2: no create in this test") },
            { _, _, _, _ -> error("S7U2: no edit in this test") }
        )

        /** Every call the registry makes on its one family, in order; each is handed to the owner exactly once. */
        val adapterCalls = mutableListOf<String>()
        private val counting = object : AlertFamilyAdapter {
            override fun onForeground(authoritative: Boolean) {
                adapterCalls += if (authoritative) "foreground(authoritative)" else "foreground"
                owner.onForeground(authoritative)
            }

            override fun onBackground() {
                adapterCalls += "background"
                owner.onBackground()
            }

            override fun onSync() {
                adapterCalls += "sync"
                owner.onSync()
            }

            override fun onBankPush(id: Int?, isRepeat: String?) {
                adapterCalls += bank(id, isRepeat)
                owner.onBankPush(id, isRepeat)
            }
        }

        var familyResolves = 0
        var deliveryResolves = 0
        var posts = 0
        var failures = 0
        val process = Process().also { it.registry.currentState = Lifecycle.State.STARTED }
        val registry = AlertFamilyRefresherRegistry(
            families = listOf({
                familyResolves++
                counting
            }),
            lifecycle = { process.registry },
            postToMain = { action ->
                posts++
                action()
                Unit
            },
            premium = premium,
            liveIdentity = { identity },
            scope = CoroutineScope(job + dispatcher),
            reportFailure = { failures++ }
        )

        private var baseGets = 0
        private var baseAdapter = 0
        private var basePosts = 0
        private var baseFamilyResolves = 0

        val state: BankAlertSettingsState get() = owner.state.value
        val rows: List<AlertSetting>? get() = state.settings
        fun settle() = test.runCurrent()
        fun advance(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }

        fun open(kind: String): List<Call> = calls.filter { it.kind == kind && it.open }
        fun count(kind: String): Int = calls.count { it.kind == kind }

        /** GETs since the start's first load, and those still awaiting an answer. */
        fun newGets(): List<Int> = listOf(count("GET") - baseGets, open("GET").size)
        fun newAdapterCalls(): List<String> = adapterCalls.drop(baseAdapter)
        fun newPosts(): Int = posts - basePosts
        fun newFamilyResolves(): Int = familyResolves - baseFamilyResolves

        fun answerGet(rows: List<AlertSetting>) {
            val pending = open("GET")
            check(pending.size == 1) { "exactly one GET awaits its answer, not ${pending.size}" }
            // Copies: the owner never holds the instances a test compares against.
            pending.single().outcome.complete(okList(rows.map { it.copy() }))
            settle()
        }

        /** The registry starts in the foreground, the owner's first GET is answered with [loadedRows]: the baseline. */
        fun loaded() {
            registry.start()
            settle()
            answerGet(loadedRows)
            check(state.phase == AlertLoadPhase.Ready && open("GET").isEmpty() && rows == loadedRows)
            check(adapterCalls == listOf("foreground") && familyResolves == 1 && failures == 0)
            baseGets = count("GET")
            baseAdapter = adapterCalls.size
            basePosts = posts
            baseFamilyResolves = familyResolves
        }

        /** Pre-S9-5 entry order, intentionally frozen. S95Entry and B07 exercise current comparison delivery. */
        fun push(data: Map<String, String>, gateOpen: Boolean = true): AlertPushRoute? {
            val type = data["type"] ?: return null
            val route = AlertPushRouting.route(type, data["setting_id"]) ?: return null
            AlertFamilyRefresherRegistry.deliverPush(
                type, data["setting_id"], data["is_repeat"], gateOpen, reportFailure = { failures++ }
            ) {
                deliveryResolves++
                registry
            }
            return route
        }

        /** Pre-S9-5 entry order, intentionally frozen. S95Entry and B07 exercise current comparison delivery. */
        fun tap(data: Map<String, String>, gateOpen: Boolean = true): AlertPushRoute? {
            val route = AlertPushRouting.route(data["type"], data["setting_id"]) ?: return null
            AlertFamilyRefresherRegistry.deliverTap(route.event, data["is_repeat"], gateOpen, reportFailure = { failures++ }) {
                deliveryResolves++
                registry
            }
            return route
        }

        /** Nothing reached the family and no GET followed, now and after a minute. */
        fun assertUntouched(label: String, before: BankAlertSettingsState) {
            assertEquals("$label: no family call", emptyList<String>(), newAdapterCalls())
            assertEquals("$label: no delivery resolve", 0, deliveryResolves)
            assertEquals("$label: no main post", 0, newPosts())
            assertEquals("$label: no family resolve", 0, newFamilyResolves())
            assertEquals("$label: the state is kept", before, state)
            settle()
            assertEquals("$label: no GET", listOf(0, 0), newGets())
            advance(60_000)
            assertEquals("$label: still no GET a minute later", listOf(0, 0), newGets())
            assertEquals("$label: the state is still kept", before, state)
            assertEquals("$label: no failure", 0, failures)
        }

        /** One GET followed and was the only one: answered with [loadedRows], nothing more for a minute. */
        fun assertOneGet(label: String) {
            settle()
            assertEquals("$label: one GET", listOf(1, 1), newGets())
            answerGet(loadedRows)
            assertEquals("$label: the server's rows are adopted", loadedRows, rows)
            advance(60_000)
            assertEquals("$label: no additional getSettings call during the next minute", listOf(1, 0), newGets())
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

    // ---- premise ----

    @Test fun `S7U2_G00 the seven fcm goldens are the agreed set, all data strings, notification on every one but sync`() {
        val ids = corpus.manifest.fixtures.filter { it.family == ContractFamily.FCM }.map { it.id }.toSet()
        assertEquals("premise: the fcm golden set", ALL, ids)
        for (id in ALL) golden(id)
        for (id in VISIBLE) assertTrue("premise: $id has a notification", root(id)["notification"] is JsonObject)
        assertNull("premise: sync has no notification", root(SYNC)["notification"])
        assertEquals("premise: the bank once golden", Triple("rate_alert", "101", "false"),
            golden(ONCE).let { Triple(it["type"], it["setting_id"], it["is_repeat"]) })
        assertEquals("premise: the bank repeat golden", Triple("rate_alert", "104", "true"),
            golden(REPEAT).let { Triple(it["type"], it["setting_id"], it["is_repeat"]) })
        assertEquals("premise: sync carries only its type", mapOf("type" to "sync_alerts"), golden(SYNC))
        assertEquals("premise: the source and comparison goldens",
            mapOf(SOURCE_ONCE to ("source_rate_alert" to "102"), SOURCE_REPEAT to ("source_rate_alert" to "105"),
                COMPARISON_ONCE to ("comparison_alert" to "103"), COMPARISON_REPEAT to ("comparison_alert" to "106")),
            OTHERS.keys.associateWith { id -> golden(id).let { it.getValue("type") to it.getValue("setting_id") } })
    }

    // ---- push: the goldens as they are ----

    @Test fun `S7U2_P01 the once golden marks only row 101 sent, then one GET whose answer the owner adopts`() = runTest {
        rig(this) {
            val old = state
            val route = push(golden(ONCE))
            assertEquals(AlertPushRoute(AlertEvent.SettingTriggered(101, AlertFamily.BANK), showBanner = true), route)
            assertEquals("one hand-over with the raw is_repeat", listOf(bank(101, "false")), newAdapterCalls())
            assertEquals("one main post, no new family resolve", listOf(1, 0), listOf(newPosts(), newFamilyResolves()))
            assertEquals("row 101 off and triggered, the others and the order kept",
                listOf(sent(101), setting(102), setting(104), setting(105)), rows)
            assertEquals("the snapshot from before the push is not changed in place", loadedRows, old.settings)
            assertEquals("the identity and phase are kept", listOf<Any?>(alice, AlertLoadPhase.Ready), listOf(state.identity, state.phase))
            assertOneGet("once")
        }
    }

    @Test fun `S7U2_P02 the repeat golden marks nothing and asks for the settings once`() = runTest {
        rig(this) {
            val before = state
            val route = push(golden(REPEAT))
            assertEquals(AlertPushRoute(AlertEvent.SettingTriggered(104, AlertFamily.BANK), showBanner = true), route)
            assertEquals(listOf(bank(104, "true")), newAdapterCalls())
            assertEquals("no local change before the answer", before, state)
            assertOneGet("repeat")
        }
    }

    @Test fun `S7U2_P03 the sync golden is a silent route, one onSync and one authoritative GET`() = runTest {
        rig(this) {
            val before = state
            val route = push(golden(SYNC))
            assertEquals(AlertPushRoute(AlertEvent.RefreshNeeded, showBanner = false), route)
            assertEquals("onSync only, never onBankPush", listOf("sync"), newAdapterCalls())
            assertEquals("one main post, no new family resolve", listOf(1, 0), listOf(newPosts(), newFamilyResolves()))
            assertEquals("no local change before the answer", before, state)
            assertOneGet("sync")
        }
    }

    @Test fun `S7U2_P04 the source and comparison goldens route to their family and reach no bank family at all`() = runTest {
        for ((id, expected) in OTHERS) rig(this) {
            val before = state
            val route = push(golden(id))
            assertEquals(id, AlertPushRoute(AlertEvent.SettingTriggered(expected.second, expected.first), showBanner = true), route)
            assertUntouched(id, before)
        }
    }

    // ---- push: variants of a golden, in memory ----

    @Test fun `S7U2_V01 an unreadable bank id routes to RefreshNeeded with showBanner true and reaches bank with no id`() = runTest {
        for (id in listOf(null, "x", "2147483648", " 101", "101 ")) rig(this) {
            val before = state
            val route = push(variant(ONCE, "setting_id", id))
            assertEquals("setting_id=$id", AlertPushRoute(AlertEvent.RefreshNeeded, showBanner = true), route)
            assertEquals("setting_id=$id", listOf(bank(null, "false")), newAdapterCalls())
            assertEquals("setting_id=$id: no local change", before, state)
            assertOneGet("setting_id=$id")
        }
    }

    @Test fun `S7U2_V02 a readable bank id the snapshot lacks marks nothing and asks once`() = runTest {
        rig(this) {
            val before = state
            val route = push(variant(ONCE, "setting_id", "999"))
            assertEquals(AlertPushRoute(AlertEvent.SettingTriggered(999, AlertFamily.BANK), showBanner = true), route)
            assertEquals(listOf(bank(999, "false")), newAdapterCalls())
            assertEquals("no local change", before, state)
            assertOneGet("absent id")
        }
    }

    @Test fun `S7U2_V03 an unreadable source or comparison id routes to its FamilyRefreshNeeded and reaches no bank family`() = runTest {
        for ((id, family) in listOf(SOURCE_ONCE to AlertFamily.SOURCE, COMPARISON_ONCE to AlertFamily.COMPARISON)) {
            for (value in listOf(null, "x", "2147483648")) rig(this) {
                val before = state
                val route = push(variant(id, "setting_id", value))
                assertEquals("$id setting_id=$value", AlertPushRoute(AlertEvent.FamilyRefreshNeeded(family), showBanner = true), route)
                assertUntouched("$id setting_id=$value", before)
            }
        }
    }

    @Test fun `S7U2_V04 an is_repeat other than exactly false is handed over raw and marks nothing`() = runTest {
        for (value in listOf(null, "TRUE", "1", "FALSE", " false")) rig(this) {
            val before = state
            push(variant(ONCE, "is_repeat", value))
            assertEquals("is_repeat=$value", listOf(bank(101, value)), newAdapterCalls())
            assertEquals("is_repeat=$value: no local change", before, state)
            assertOneGet("is_repeat=$value")
        }
    }

    @Test fun `S7U2_V05 a type the router does not know makes no delivery at all`() = runTest {
        val variants = listOf("Rate_alert", "rate_alert ", "rate-alert", "RATE_ALERT", null, "unknown").map { ONCE to it } +
            listOf(SYNC to "SYNC_ALERTS")
        for ((id, type) in variants) rig(this) {
            val before = state
            assertNull("$id type=$type: no route", push(variant(id, "type", type)))
            assertUntouched("$id type=$type", before)
        }
    }

    @Test fun `S7U2_V06 a closed delivery gate reaches nothing for every golden`() = runTest {
        for (id in ALL) rig(this) {
            val before = state
            push(golden(id), gateOpen = false)
            assertUntouched("$id gate closed", before)
        }
    }

    @Test fun `S7U2_V07 with only the owner's release input closed the once golden marks nothing and sends no GET`() = runTest {
        rig(this) {
            val before = rows
            release = false
            push(golden(ONCE))
            assertEquals("the delivery itself is open", listOf(bank(101, "false")), newAdapterCalls())
            assertEquals("no once mark", before, rows)
            settle()
            advance(60_000)
            assertEquals("no getSettings call", 0, newGets()[0])
            assertEquals(0, failures)
        }
    }

    @Test fun `S7U2_V08 after premium loss the once golden changes no settings immediately and adds no GET within a minute`() = runTest {
        rig(this) {
            premium.value = OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.FreeConfirmed)
            settle()
            val s0 = rows
            val getsAtS0 = newGets()[0]
            push(golden(ONCE))
            assertEquals("the delivery itself is open", listOf(bank(101, "false")), newAdapterCalls())
            assertEquals("settings are unchanged immediately after the push", s0, rows)
            settle()
            advance(60_000)
            assertEquals("no getSettings call after the push", getsAtS0, newGets()[0])
            assertEquals(0, failures)
        }
    }

    // ---- tap: the OFF meaning chain ----

    @Test fun `S7U2_T01 a bank golden tap hands its id and raw is_repeat over once, as its push does`() = runTest {
        rig(this) {
            val old = state
            assertEquals(AlertPushRoute(AlertEvent.SettingTriggered(101, AlertFamily.BANK), showBanner = true), tap(golden(ONCE)))
            assertEquals(listOf(bank(101, "false")), newAdapterCalls())
            assertEquals(listOf(sent(101), setting(102), setting(104), setting(105)), rows)
            assertEquals("the snapshot from before the tap is not changed in place", loadedRows, old.settings)
            assertOneGet("once tap")
        }
        rig(this) {
            val before = state
            assertEquals(AlertPushRoute(AlertEvent.SettingTriggered(104, AlertFamily.BANK), showBanner = true), tap(golden(REPEAT)))
            assertEquals(listOf(bank(104, "true")), newAdapterCalls())
            assertEquals(before, state)
            assertOneGet("repeat tap")
        }
    }

    @Test fun `S7U2_T02 a source or comparison golden tap reaches no bank family`() = runTest {
        for (id in OTHERS.keys) rig(this) {
            val before = state
            tap(golden(id))
            assertUntouched("$id tap", before)
        }
    }

    @Test fun `S7U2_T03 an unreadable bank id tap hands bank null id and null repeat despite the once golden's false repeat`() = runTest {
        for (id in listOf(null, "x")) rig(this) {
            val before = state
            assertEquals(AlertPushRoute(AlertEvent.RefreshNeeded, showBanner = true), tap(variant(ONCE, "setting_id", id)))
            assertEquals("setting_id=$id", listOf(bank(null, null)), newAdapterCalls())
            assertEquals("setting_id=$id: no local change", before, state)
            assertOneGet("tap setting_id=$id")
        }
    }

    @Test fun `S7U2_T04 a closed tap gate reaches nothing for every visible golden`() = runTest {
        for (id in VISIBLE) rig(this) {
            val before = state
            tap(golden(id), gateOpen = false)
            assertUntouched("$id tap gate closed", before)
        }
    }

    private companion object {
        const val ONCE = "fcm-rate-alert-once"
        const val REPEAT = "fcm-rate-alert-repeat"
        const val SYNC = "fcm-sync-alerts"
        const val SOURCE_ONCE = "fcm-source-alert-once"
        const val SOURCE_REPEAT = "fcm-source-alert-repeat-krx"
        const val COMPARISON_ONCE = "fcm-comparison-alert-absolute-once"
        const val COMPARISON_REPEAT = "fcm-comparison-alert-signed-repeat"

        /** id → (family, the golden's setting id). */
        val OTHERS: Map<String, Pair<AlertFamily, Int>> = linkedMapOf(
            SOURCE_ONCE to (AlertFamily.SOURCE to 102), SOURCE_REPEAT to (AlertFamily.SOURCE to 105),
            COMPARISON_ONCE to (AlertFamily.COMPARISON to 103), COMPARISON_REPEAT to (AlertFamily.COMPARISON to 106)
        )
        val VISIBLE: Set<String> = linkedSetOf(ONCE, REPEAT) + OTHERS.keys
        val ALL: Set<String> = VISIBLE + SYNC
    }
}
