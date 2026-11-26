package com.jay.fxi.data.alerts

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.jay.fxi.PostS6ApprovedDelta
import com.jay.fxi.S6Krx3cShellSourceContract
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
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.mapAlertHttpFailure
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.ComparisonAlertSetting
import com.jay.fxi.domain.model.SourceAlertSetting
import com.jay.fxi.service.AlertEvent
import com.jay.fxi.service.AlertTapEntry
import com.jay.fxi.service.AlertTapExtraKind
import com.jay.fxi.service.AlertTapExtras
import java.io.File
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
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class S95ComparisonDeliveryContractTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val bob = AuthIdentityFence("user-b", 1)
    private val grantA = TopicGrantToken(11)
    private val grantB = TopicGrantToken(12)
    private val stamp = Instant.parse("2026-11-22T00:00:00Z")

    private val absolute = "comparison-alert-absolute-once"
    private val signed = "comparison-alert-signed-repeat"
    private val notifications = mutableMapOf<Map<String, String>, Pair<String, String>>()
    private fun payload(name: String, allowed: Boolean = false): Map<String, String> {
        val json = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/contracts/v2/fcm/$name.json")).readText()).jsonObject
        val data = json.getValue("data").jsonObject.mapValues { it.value.jsonPrimitive.content }.toMutableMap()
        val notice = json["notification"]?.jsonObject
        if (name == absolute || name == signed) {
            val side = if (name == absolute) "left" else "right"
            val bank = if (name == absolute) "kb" else "hana"
            val title = notice!!.getValue("title").jsonPrimitive.content
            assertEquals(data["title"], title)
            assertEquals(data["body"], notice.getValue("body").jsonPrimitive.content)
            var postedTitle = title
            if (allowed) {
                data[side + "_source"] = bank
                data[side + "_asset"] = "usd-krw"
                postedTitle = title.replace("달러선물", if (bank == "kb") "국민" else "하나")
                data["title"] = postedTitle
            }
            notifications[data.toMap()] = postedTitle to notice.getValue("body").jsonPrimitive.content
        }
        return data
    }

    private fun row(id: Int) = ComparisonAlertSetting(id, alice.uid, if (id == 106) "tether" else "usd",
        if (id == 106) "upbit" else "kb", if (id == 106) "usdt-krw" else "usd-krw", "hana", "usd-krw",
        if (id == 106) "signed" else "absolute", "gte", 8.0, true, false, if (id == 106) 60 else null, null, stamp, stamp)

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


    private val service by lazy {
        val source = S6Krx3cShellSourceContract.load().getValue("service/FXiMessagingService.kt")
        val raw = S6Krx3cShellSourceContract.function(source, "showAlertNotification").raw
        PostS6ApprovedDelta.codeAndStrings(raw).trim().replace(Regex("""\s+"""), " ")
    }
    private fun ownerBody(name: String): String {
        val text = File("src/main/java/com/jay/fxi/data/alerts/ComparisonAlertSettingsOwner.kt").readText()
        return PostS6ApprovedDelta.codeAndStrings(S6Krx3cShellSourceContract.function(text, name).raw)
            .trim().replace(Regex("""\s+"""), " ")
    }
    private inner class Rig(val test: TestScope, val initial: List<ComparisonAlertSetting>) {
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
        var released = true
        val access = AlertSettingsAccess({ identity }, { fence }, counted, { premium.value }, { released },
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
        val source = SourceAlertSettingsOwner(
            { _, admitted ->
                if (admitted()) {
                    val reply = CompletableDeferred<AuthBoundResult<List<SourceAlertSetting>>>()
                    sourceGets += reply
                    try { reply.await() } finally { if (!reply.isCompleted) reply.cancel() }
                } else AuthBoundResult({}, Result.success(emptyList<SourceAlertSetting>()))
            },
            access, floor, AlertRetryPolicy(), { test.testScheduler.currentTime }, { 0.0 }, CoroutineScope(job + dispatcher), { delay(it) },
            premium, MutableStateFlow(0L),
            { _, _, _ -> error("unexpected source create") },
            { _, _, _, _ -> error("unexpected source edit") },
            { _, _, _ -> error("no delete") }
        )

        val requests = mutableListOf<CompletableDeferred<AuthBoundResult<List<ComparisonAlertSetting>>>>()
        val uses = mutableListOf<AlertSettingsUse>()
        val guards = mutableListOf<() -> Boolean>()
        val captures = mutableListOf<AlertSettingsUse?>()
        val revisions = MutableStateFlow(0L)
        var keepLateResponse = false
        var beforeComparisonWire: (() -> Unit)? = null
        val updates = mutableListOf<CompletableDeferred<AuthBoundResult<ComparisonAlertSetting>>>()
        val inserts = mutableListOf<CompletableDeferred<AuthBoundResult<ComparisonAlertSetting>>>()
        val deletes = mutableListOf<CompletableDeferred<AuthBoundResult<Unit>>>()
        val comparison = ComparisonAlertSettingsOwner({ use, guard ->
            beforeComparisonWire?.invoke()
            if (!guard()) AuthBoundResult({}, Result.success(emptyList()))
            else {
                uses += use
                guards += guard
                val reply = CompletableDeferred<AuthBoundResult<List<ComparisonAlertSetting>>>().also { requests += it }
                if (keepLateResponse) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { reply.await() } else reply.await()
            }
        }, access, { identity }, premium, revisions, CoroutineScope(job + dispatcher), { delay(it) }, { 0.0 },
            post = { _, _, _ -> CompletableDeferred<AuthBoundResult<ComparisonAlertSetting>>().also { inserts += it }.await() },
            put = { _, _, _, _ -> CompletableDeferred<AuthBoundResult<ComparisonAlertSetting>>().also { updates += it }.await() },
            remove = { _, _, _ -> CompletableDeferred<AuthBoundResult<Unit>>().also { deletes += it }.await() })
        val process = Process().also { it.registry.currentState = Lifecycle.State.STARTED }
        var hold = false
        val queue = ArrayDeque<() -> Unit>()
        val emitted = mutableListOf<AlertEvent>()
        var resolves = 0
        var cut = -1
        var on = false
        var callbacks = 0
        var contents = 0
        var content: Pair<String?, String?>? = null
        var tokens = 0
        var notices = 0
        var copies = 0
        val intent = mutableMapOf<String, Any?>()
        val registry = S95Entry.registry(listOf({ bank }, { source }, { comparison }), { process.registry },
            { if (hold) queue.addLast(it) else it() }, premium, { identity }, CoroutineScope(job + dispatcher),
            { throw AssertionError("family dispatch failed") }, { access.acquire().also { captures += it } })
        fun settle() = test.runCurrent()
        fun advance(ms: Long) { test.advanceTimeBy(ms); settle() }
        fun bg() { process.registry.currentState = Lifecycle.State.CREATED; settle() }
        fun fg() { process.registry.currentState = Lifecycle.State.STARTED; settle() }
        fun revoke() { snapshot = snapshot.copy(userInvalidations = snapshot.userInvalidations + 1) }
        fun load() {
            registry.start(); settle()
            bankGets.single().complete(AuthBoundResult({}, Result.success(listOf(bankRow(103), bankRow(106)))))
            sourceGets.single().complete(AuthBoundResult({}, Result.success(listOf(sourceRow(103), sourceRow(106)))))
            answer(initial)
            assertEquals(0, gets)
        }
        fun answer(rows: List<ComparisonAlertSetting>) {
            assertTrue(requests.last().complete(AuthBoundResult({}, Result.success(rows))))
            settle()
        }
        fun fail(status: Int, seconds: String? = null) {
            val headers = if (seconds == null) headersOf() else headersOf("Retry-After", seconds)
            val failure = AuthenticatedHttpFailure(status, headers, ByteArray(0), null, null, AuthenticatedFailureKind.OTHER_HTTP)
            assertTrue(requests.last().complete(AuthBoundResult({}, Result.failure(mapAlertHttpFailure(failure)))))
            settle()
        }
        val gets get() = requests.size - 1
        fun flags(id: Int) = comparison.state.value.adopted?.rows?.singleOrNull { it.id == id }?.let { it.triggered to it.isEnabled }
        fun quietOther() {
            assertTrue(updates.isEmpty() && inserts.isEmpty() && deletes.isEmpty())
            assertEquals(1, bankGets.size); assertEquals(1, sourceGets.size)
            assertTrue(emitted.isEmpty())
        }
        fun push(data: Map<String, String>, gate: Boolean = true) {
            if (!released) return
            S95Entry.push(data, gate, { resolves++; registry }, { emitted += it }) { admitted ->
                callbacks++
                if (cut == 0) revoke()
                if (!service.startsWith("if (!ReleaseAdmission.isOpen || !admitted()) return val (title, body) = parseNotificationContent(message)") || admitted()) {
                    contents++
                    content = notifications[data] ?: (data["title"] to data["body"])
                    intent["notification_type"] = data["type"]
                    data["setting_id"]?.toIntOrNull()?.let { intent["setting_id"] = it }
                    data["is_repeat"]?.let { intent["is_repeat"] = it }
                    if (on && service.contains("if (krxContestSwitch.enabled) { AlertTapEntry.copyForegroundClassification(")) {
                        copies++
                        AlertTapEntry.copyForegroundClassification(data) { key, value -> intent[key] = value }
                    } else if (!on && service.contains("else if (type == AlertFamilyRefresherRegistry.COMPARISON_ALERT) { for (key in AlertFamilyRefresherRegistry.COMPARISON_PAIR_KEYS)")) {
                        AlertFamilyRefresherRegistry.COMPARISON_PAIR_KEYS.forEach { k -> data[k]?.let { intent[k] = it } }
                    }
                    if (cut == 1) revoke()
                    if (!service.contains("if (!admitted()) return val pendingIntent = PendingIntent.getActivity(") || admitted()) {
                        tokens++
                        if (cut == 2) revoke()
                        if (!service.endsWith("if (admitted()) notificationManager.notify(notificationId, notification)") || admitted()) notices++
                    }
                }
            }
            settle()
        }
        var reenter = false
        var slots = 0
        fun tap(extras: Extras, warm: Boolean = false, restored: Boolean = false, gate: Boolean = true) {
            if (!released || (!warm && restored)) return
            val event = S95Entry.tap(extras, gate) {
                resolves++
                if (reenter) { reenter = false; tap(extras, warm) }
                registry
            }
            if (event != null) { if (warm) emitted += event else slots++ }
            settle()
        }
        fun close() {
            job.cancel()
            requests.filterNot { it.isCompleted }.forEach { it.cancel() }
        }
    }

    private fun TestScope.runRig(initial: List<ComparisonAlertSetting> = listOf(row(103), row(106), row(107)), check: Rig.() -> Unit) {
        val fixture = Rig(this, initial)
        try { fixture.load(); check.invoke(fixture) } finally { fixture.close(); runCurrent() }
    }


    private fun custom(data: Map<String, String>) = Extras((data - "type" - "setting_id") +
        mapOf("notification_type" to data.getValue("type"), "setting_id" to data.getValue("setting_id").toInt()))
    private fun reverse(data: Map<String, String>) = data + listOf("asset", "source").flatMap { key ->
        listOf("left_$key" to data.getValue("right_$key"), "right_$key" to data.getValue("left_$key"))
    }

    @Test fun S95_P01_leftAndRightOriginalNeverEscape() = runTest {
        for (name in listOf(absolute, signed)) {
            val side = if (name == absolute) "left" else "right"
            val original = payload(name)
            for (data in listOf(original, original + (side + "_source" to "kb"), original + (side + "_asset" to "usd-krw"))) runRig {
                val before = comparison.state.value
                push(data)
                assertEquals("forbidden push publication", 0, callbacks)
                for (warm in listOf(false, true)) for (extras in listOf(Extras(data), custom(data))) {
                    tap(extras, warm); assertTrue(extras.values.isEmpty()); tap(extras, warm)
                }
                assertEquals(listOf(0, 0, 0, 0, 0, 0), listOf(contents, tokens, notices, slots, gets, resolves))
                assertSame(before, comparison.state.value); quietOther()
            }
        }
    }

    private fun TestScope.checkPair(name: String, repeat: String, reversed: Boolean = false) = runRig {
        val message = payload(name, true) + ("is_repeat" to repeat)
        val id = message.getValue("setting_id").toInt()
        push(if (reversed) reverse(message) else message)
        val allowed = !reversed || name == absolute
        assertEquals("comparison GET", if (allowed) 1 else 0, gets)
        val mark = allowed && repeat == "false"
        assertEquals(mark to !mark, flags(id))
        assertEquals(false to true, flags(if (id == 103) 106 else 103))
        assertEquals(if (allowed) 1 else 0, notices); quietOther()
        val sent = if (reversed) reverse(message) else message
        if (allowed) {
            assertEquals(sent["title"] to sent["body"], content)
            assertTrue(AlertFamilyRefresherRegistry.COMPARISON_PAIR_KEYS.all { intent[it] == sent[it] })
            val served = listOf(row(109))
            val publications = comparison.publications
            answer(served)
            assertEquals(publications + 1, comparison.publications)
            assertEquals(served, comparison.state.value.adopted?.rows)
        }
    }

    @Test fun S95_P02_absoluteOnce() = runTest {
        checkPair(absolute, "false"); checkPair(absolute, "true"); checkPair(absolute, "false", reversed = true)
    }
    @Test fun S95_P03_signedRepeat() = runTest {
        checkPair(signed, "true"); checkPair(signed, "false"); checkPair(signed, "false", reversed = true)
    }

    @Test fun S95_P04_rawRepeatAndMiss() = runTest {
        val data = payload(absolute, true)
        val cases = listOf("true", "False", " false", "").map { data + ("is_repeat" to it) } +
            listOf(data - "is_repeat", data - "setting_id", data + ("left_source" to "woori")) +
            listOf("x", "2147483648", "99").map { data + ("setting_id" to it) }
        for (example in cases) runRig {
            push(example)
            assertEquals(1, gets); assertEquals(listOf(false to true, false to true), listOf(flags(103), flags(107)))
        }
        runRig {
            tap(Extras(data + ("is_repeat" to false)))
            assertEquals(1, gets); assertEquals(false to true, flags(103))
        }
        runRig {
            push(data)
            assertEquals(1, gets)
            assertEquals(true to false, flags(103))
            assertEquals(false to true, flags(106))
            assertEquals(false to true, flags(107))
        }
        runRig {
            val before = comparison.state.value
            tap(Extras(data + ("setting_id" to 103)))
            assertEquals("SDK tap requires a string id", 0, resolves)
            assertEquals(0, gets)
            assertSame(before, comparison.state.value)
        }
        runRig(listOf(row(103), row(103), row(107))) {
            push(data); assertEquals(1, gets); assertNull(flags(103)); assertEquals(false to true, flags(107))
        }
        for (key in AlertFamilyRefresherRegistry.COMPARISON_PAIR_KEYS) runRig {
            push(data - key); assertEquals(0, resolves); assertEquals(0, gets); assertEquals(0, callbacks)
        }
    }

    @Test fun S95_P05_authorityAndRevocation() = runTest {
        val data = payload(absolute, true)
        val setups = listOf<Rig.() -> Unit>(
            { premium.value = premium.value.copy(state = PremiumAccessState.FreeConfirmed) },
            { premium.value = premium.value.copy(state = PremiumAccessState.Pending(false)) },
            { identity = null }, { released = false }, { deletions.begin(alice, "test") }, { identity = bob },
            { fence = null }, { fence = TopicSessionFence(alice, "epoch-1", grantB) })
        for (setup in setups) runRig {
            setup.invoke(this)
            push(data)
            assertEquals(0, callbacks); assertEquals(0, gets); quietOther()
        }
        runRig { push(data, false); assertEquals(listOf(0, 0, 0), listOf(resolves, callbacks, gets)) }
        runRig {
            released = false
            S95Entry.push(data, gateOpen = true, resolve = { resolves++; registry }, emit = { emitted += it }) { callbacks++ }
            settle()
            assertEquals("closed release reaches registry resolution", 1, resolves)
            assertEquals(listOf<AlertSettingsUse?>(null), captures)
            assertEquals(0, callbacks)
            assertEquals(0, gets)
            quietOther()
        }
        for (change in listOf<Rig.() -> Unit>({ revoke() }, { identity = bob }, { bg() })) runRig {
            hold = true; push(data); assertEquals(1, queue.size)
            change.invoke(this)
            val before = comparison.state.value
            queue.removeFirst()(); settle()
            assertSame(before, comparison.state.value); assertEquals(0, callbacks); assertEquals(0, gets)
            if (process.registry.currentState == Lifecycle.State.CREATED) {
                val pending = comparison.javaClass.getDeclaredField("pendingDelivery").apply { isAccessible = true }
                assertNull(pending.get(comparison))
                fg(); assertEquals(0, gets)
            }
        }
        val switchCounts = mutableListOf<List<Int>>()
        for (inject in listOf(false, true)) runRig {
            hold = true
            if (inject) push(data)
            identity = bob; fence = TopicSessionFence(bob, "epoch-2", grantB)
            snapshot = snapshot.copy(facts = snapshot.facts.copy(token = grantB))
            premium.value = OwnedPremiumAccess(bob.uid, bob.authGeneration, PremiumAccessState.PremiumConfirmed)
            settle()
            val before = comparison.state.value
            if (inject) queue.removeFirst()()
            settle()
            assertSame(before, comparison.state.value)
            switchCounts += listOf(bankGets.size, sourceGets.size, requests.size, callbacks)
        }
        assertEquals(switchCounts.first(), switchCounts.last())
        for (index in -1..2) runRig {
            cut = index; push(data)
            assertEquals(if (index == 0) 0 else 1, contents)
            assertEquals(if (index in 0..1) 0 else 1, tokens)
            assertEquals(if (index == -1) 1 else 0, notices)
        }
        runRig {
            beforeComparisonWire = { revoke() }; push(data)
            assertEquals(0, gets)
        }
        runRig {
            push(data); assertEquals(1, gets); revoke()
            assertFalse("replay admission", guards.last()())
            answer(listOf(row(109))); assertNull(flags(109)); assertEquals(1, gets)
        }
        assertTrue(ownerBody("reset").startsWith("pendingDelivery = null abandon(restoreSnapshot = false)"))
    }

    @Test fun S95_P06_pushTypeConflict() = runTest {
        val cases = listOf(absolute, signed).flatMap { name ->
            listOf("rate_alert", "source_rate_alert", "sync_alerts").map { payload(name, true) + ("notification_type" to it) }
        } + listOf("rate-alert-once", "source-alert-once", "sync-alerts").map { payload(it) + ("notification_type" to "comparison_alert") }
        for (data in cases) runRig {
            val before = comparison.state.value
            push(data); assertTrue(emitted.isEmpty())
            assertEquals(listOf(0,0,0), listOf(resolves, gets, callbacks)); assertSame(before, comparison.state.value); quietOther()
        }
    }

    @Test fun S95_T01_customSdkColdWarm() = runTest {
        val data = payload(absolute, true)
        assertEquals(18, data.size)
        for (warm in listOf(false, true)) for (extras in listOf(Extras(data), custom(data),
            Extras(data + ("notification_type" to "comparison_alert")), custom(data).also { it.values["type"] = "comparison_alert" })) runRig {
            extras.values["unrelated"] = 42
            reenter = true
            tap(extras, warm)
            assertEquals(setOf("unrelated"), extras.values.keys)
            tap(extras, warm)
            assertEquals(listOf(1, 1, 0, 0), listOf(resolves, gets, callbacks, slots))
            assertEquals(true to false, flags(103)); quietOther()
        }
        // Rig.tap mirrors the outer shell here. N1 and O04s lock the production guards.
        for (closed in listOf(false, true)) runRig {
            released = !closed
            val extras = Extras(data)
            tap(extras, restored = !closed)
            assertEquals(data, extras.values); assertEquals(0, resolves); assertEquals(0, gets)
        }
    }

    @Test fun S95_T02_conflictsConsumed() = runTest {
        val data = payload(absolute, true)
        val cases = listOf("source_rate_alert", "rate_alert", "sync_alerts", null, 42).flatMap { other ->
            listOf(Extras(data + ("notification_type" to other)), Extras(custom(data).values + ("type" to other)))
        } + listOf(null, 42, 42L, false).map { Extras(data + ("setting_id" to it)) } +
            listOf(Extras(custom(data).values + ("setting_id" to "103"))) +
            AlertFamilyRefresherRegistry.COMPARISON_PAIR_KEYS.map { Extras(data - it) }
        for (extras in cases) runRig {
            val before = comparison.state.value
            tap(extras)
            assertTrue("all six comparison fields consumed", AlertFamilyRefresherRegistry.COMPARISON_PAIR_KEYS.none { it in extras.values })
            assertTrue(extras.values.isEmpty()); assertSame(before, comparison.state.value)
            assertEquals(listOf(0,0,0,0), listOf(resolves, gets, callbacks, slots)); quietOther()
        }
        runRig {
            val extras = Extras(data)
            tap(extras, gate = false)
            assertTrue("closed gate consumes comparison extras", extras.values.isEmpty())
            assertEquals(0, gets); assertEquals(0, resolves); quietOther()
        }
    }

    @Test fun S95_L01_preForegroundAndGrant() = runTest {
        val data = payload(absolute, true)
        for (mode in 0..2) runRig {
            bg()
            if (mode == 2) fence = null
            tap(Extras(data))
            assertEquals(0, gets)
            if (mode == 1) revoke()
            if (mode == 2) fence = TopicSessionFence(alice, "epoch-1", grantA)
            val atResume = acquisitions
            fg()
            assertEquals("tap GET on return", if (mode == 0) 1 else 0, gets)
            assertEquals("no fresh acquisition on return", atResume, acquisitions)
            if (mode == 0) { assertSame(captures.last(), uses.last()); answer(listOf(row(109))); assertNotNull(flags(109)) }
        }
        val resumed = mutableListOf<Int>()
        for (inject in listOf(false, true)) {
            val fixture = Rig(this, listOf(row(103)))
            try {
                fixture.process.registry.currentState = Lifecycle.State.CREATED
                fixture.fence = null
                fixture.registry.start(); fixture.settle()
                if (inject) fixture.tap(Extras(data))
                assertTrue(fixture.requests.isEmpty())
                fixture.fence = TopicSessionFence(alice, "epoch-1", grantA)
                fixture.fg()
                resumed += fixture.requests.size
                fixture.answer(listOf(row(103)))
            } finally { fixture.close(); runCurrent() }
        }
        assertEquals("only ordinary cold-start demand", listOf(1, 1), resumed)
        assertEquals("liveDelivery() abandon() show(AlertLoadPhase.Idle) resumeDelivery()", ownerBody("withdrawUse"))
    }

    @Test fun S95_L02_trailing() = runTest {
        val data = payload(absolute, true)
        for (terminal in listOf(false, true)) runRig {
            push(data + ("is_repeat" to "true"))
            assertEquals("first delivery GET", 1, gets)
            push(data); assertEquals(true to false, flags(103))
            keepLateResponse = true
            if (terminal) fail(400) else answer(initial)
            assertEquals("one trailing GET", 2, gets); assertEquals(true to false, flags(103))
            val late = requests.last()
            bg(); keepLateResponse = false; fg(); assertEquals(3, gets)
            late.complete(AuthBoundResult({}, Result.success(listOf(row(110)))))
            settle(); assertNull(flags(110)); answer(initial); assertEquals(false to true, flags(103))
        }
        runRig {
            push(data)
            assertEquals(1, gets)
            push(data + ("setting_id" to "999"))
            answer(listOf(row(118)))
            assertNull(flags(118)); assertEquals(2, gets)
            answer(listOf(row(119))); assertNotNull(flags(119))
        }
        assertEquals(AlertRetryDecision.Silent, AlertRetryPolicy().decide(AlertFetchFailure.Withdrawn, 0) { 0.0 })
        assertTrue(ownerBody("runChain").contains("AlertRetryDecision.Silent, is AlertRetryDecision.Terminal -> { if (pendingDelivery !== ticket)"))
        val deadlines = listOf(429 to 5000L, 503 to 2000L, 429 to 30000L)
        for ((code, millis) in deadlines) for (duringSleep in listOf(false, true)) runRig {
            push(data); assertEquals(1, gets); push(data)
            fail(code, if (code == 429) (millis / 1000).toString() else null)
            advance(millis - 1); assertEquals("no early retry", 1, gets)
            if (duringSleep) repeat(2) { push(data) }
            assertEquals(1, gets)
            advance(1); assertEquals(2, gets); assertSame(captures.last(), uses.last())
            for (backoff in listOf(4000L, 8000L, 16000L, 32000L)) {
                val before = gets
                fail(503); advance(backoff - 1); assertEquals(before, gets)
                advance(1); assertEquals(before + 1, gets)
            }
            fail(503); advance(60000); assertEquals(6, gets)
        }
    }

    @Test fun S95_L03_writeOverlap() = runTest {
        val data = payload(absolute, true)
        for (sync in listOf(false, true)) runRig {
            push(data + ("is_repeat" to "true"))
            assertEquals("delivery-only chain", 1, gets)
            revoke(); revisions.value++
            val toggle = comparison.setEnabled(alice, 103, false)
            settle(); assertEquals(1, updates.size)
            if (sync) { comparison.onSync(); settle() }
            updates.single().complete(AuthBoundResult({}, Result.success(row(103).copy(isEnabled = false))))
            settle()
            assertTrue(toggle.outcome.value is ComparisonAlertOutcome.Saved)
            assertEquals("ordinary read debt", if (sync) 2 else 1, gets)
        }
        for (delete in listOf(false, true)) runRig {
            val handle = if (delete) comparison.delete(alice, 103) else comparison.setEnabled(alice, 103, false)
            settle()
            val overlay = flags(103)
            push(data)
            assertEquals(overlay, flags(103))
            push(data + ("setting_id" to "107"))
            assertEquals(true to false, flags(107)); assertEquals(0, gets)
            val failure = IllegalStateException("refused")
            if (delete) deletes.single().complete(AuthBoundResult({}, Result.failure(failure)))
            else updates.single().complete(AuthBoundResult({}, Result.failure(failure)))
            settle(); assertTrue(handle.outcome.value is ComparisonAlertOutcome.Failed)
            assertEquals(false to true, flags(103)); assertEquals(true to false, flags(107)); assertEquals(1, gets)
        }
        runRig {
            val origin = ComparisonAlertPair("usd", "absolute", "kb" to "usd-krw", "hana" to "usd-krw")
            val replacement = origin.copy(left = "woori" to "usd-krw")
            val handle = comparison.edit(alice, 103, origin, ComparisonAlertDraft(replacement, "gte", 8.0, null))
            settle()
            val fresh = row(109).copy(leftSource = "woori")
            inserts.single().complete(AuthBoundResult({}, Result.success(fresh)))
            settle(); assertEquals(1, deletes.size)
            push(data); push(data + mapOf("setting_id" to "109", "left_source" to "woori"))
            assertEquals(false to true, flags(103)); assertEquals(false to true, flags(109)); assertEquals(0, gets)
            deletes.single().complete(AuthBoundResult({}, Result.failure(IllegalStateException("old retained"))))
            settle()
            assertTrue(handle.outcome.value is ComparisonAlertOutcome.OldNotDeleted)
            assertEquals(false to true, flags(109)); assertEquals(1, gets)
        }
        runRig {
            val handle = comparison.setEnabled(alice, 103, false)
            settle(); push(data)
            revoke()
            updates.single().complete(AuthBoundResult({}, Result.success(row(103).copy(isEnabled = false))))
            settle()
            assertEquals(ComparisonAlertOutcome.Withdrawn, handle.outcome.value)
            assertEquals(false to true, flags(103)); assertEquals(0, gets)
        }
        val chain = ownerBody("runChain")
        assertTrue(chain.contains("val ticket = liveDelivery() if (ticket == null && !chain.ordinary) return val requestUse = ticket?.use ?: use"))
        assertTrue(chain.indexOf("val requestUse =") in 0 until chain.indexOf("val bound = fetch(requestUse, send)"))
    }

    @Test fun S95_R01_syncAndFamilyIsolation() = runTest {
        val sync = payload("sync-alerts")
        for (background in listOf(false, true)) runRig {
            if (background) bg()
            push(sync)
            if (background) {
                push(sync); assertEquals(listOf(1, 1, 0), listOf(bankGets.size, sourceGets.size, gets)); fg()
            }
            assertEquals(listOf(2, 2, 1), listOf(bankGets.size, sourceGets.size, gets))
            assertEquals(0, notices)
            bankGets.last().complete(AuthBoundResult({}, Result.success(listOf(bankRow(42)))))
            sourceGets.last().complete(AuthBoundResult({}, Result.success(listOf(sourceRow(42)))))
            answer(listOf(row(42)))
            assertEquals(listOf(42), bank.state.value.settings?.map { it.id })
            assertEquals(listOf(42), source.state.value.settings?.map { it.id })
            assertNotNull(flags(42))
        }
        for (inject in listOf(false, true)) runRig {
            bg(); if (inject) push(sync)
            identity = bob; fence = TopicSessionFence(bob, "epoch", grantB)
            snapshot = snapshot.copy(facts = snapshot.facts.copy(token = grantB))
            premium.value = OwnedPremiumAccess(bob.uid, bob.authGeneration, PremiumAccessState.PremiumConfirmed)
            settle(); fg()
            assertEquals(listOf(2, 2, 1), listOf(bankGets.size, sourceGets.size, gets))
            answer(emptyList()); assertEquals(1, gets)
        }
        for (name in listOf("rate-alert-once", "rate-alert-repeat", "source-alert-once", "source-alert-repeat-krx")) {
            for (unreadable in listOf(false, true)) runRig {
                val data = payload(name)
                push(if (unreadable) data + ("setting_id" to "x") else data)
                val bankEvent = name.startsWith("rate-")
                val exchange = name == "source-alert-once"
                assertEquals(if (bankEvent) 2 else 1, bankGets.size)
                assertEquals(if (exchange) 2 else 1, sourceGets.size)
                assertEquals(if (bankEvent || exchange) 1 else 0, notices)
                assertEquals(0, gets)
            }
        }
    }

    @Test fun S95_R02_onCustody() {
        runTest {
            for (allowed in listOf(false, true)) for (name in listOf(absolute, signed)) runRig {
                on = true
                push(payload(name, allowed))
                val effects = if (allowed) 1 else 0
                assertEquals("ON publication", effects, notices)
                assertEquals(List(4) { effects }, listOf(copies, callbacks, resolves, gets))
                assertEquals(allowed && name == absolute, flags(103)?.first)
                assertEquals(false to true, flags(106)); quietOther()
                if (allowed) {
                    assertFalse("diff_type is OFF-only", "diff_type" in intent)
                    assertEquals(payload(name, true)["left_asset"], intent["left_asset"])
                }
            }
        }
        com.jay.fxi.service.s63cTest("S95_R02", "cold", "warm") { p, mode ->
            for (name in listOf(absolute, signed)) for (app in listOf(false, true)) {
                val data = payload(name, true)
                val extras = if (app) custom(data) else Extras(data)
                val preserved = extras.values.toMap()
                val intake = p.intake(context = { assertEquals(preserved, extras.values); p.context() })
                assertEquals(com.jay.fxi.service.AlertTapCaptureResult.NOT_ALERT, AlertTapEntry.intakeNew(extras, true, intake))
                assertEquals(preserved, extras.values)
                val result = if (mode == "cold") AlertTapEntry.intakeNew(extras, false, intake) else AlertTapEntry.intakeWarm(extras, intake)
                assertTrue(result is com.jay.fxi.service.AlertTapCaptureResult.CAPTURED)
                val ticket = (result as com.jay.fxi.service.AlertTapCaptureResult.CAPTURED).ticket
                assertTrue(ticket in p.inventory())
                assertFalse("setting_id" in extras.values)
                assertEquals(com.jay.fxi.service.AlertTapReceipt.UNHANDLED,
                    p.owner.consume(ticket, p.g.snapshot, p.g.r.identity, p::bankConsumer))
            }
            val quarantined = listOf(payload(absolute, true) - "tab",
                payload(signed, true) + ("notification_type" to "rate_alert"))
            for (data in quarantined) {
                val extras = Extras(data)
                val result = if (mode == "cold") AlertTapEntry.intakeNew(extras, false, p.intake())
                    else AlertTapEntry.intakeWarm(extras, p.intake())
                assertTrue(result is com.jay.fxi.service.AlertTapCaptureResult.CAPTURED)
                val ticket = (result as com.jay.fxi.service.AlertTapCaptureResult.CAPTURED).ticket
                assertEquals(com.jay.fxi.service.AlertTapClassification.UNKNOWN, p.owner.classify(ticket))
            }
            val added = AlertTapEntry.intakeNew(Extras(payload(absolute)), false, p.intake())
            val krx = (added as com.jay.fxi.service.AlertTapCaptureResult.CAPTURED).ticket
            val collector = p.collect()
            p.bus.forwardPendingTap(AlertEvent.PendingTap(krx))
            runCurrent()
            assertTrue(krx in p.inventory())
            collector.cancel(); runCurrent()
            p.g.session.capEnd(); p.report()
            assertFalse(krx in p.inventory())
        }
    }
}
