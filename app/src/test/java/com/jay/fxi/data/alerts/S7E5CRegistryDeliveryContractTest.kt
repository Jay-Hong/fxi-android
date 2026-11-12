package com.jay.fxi.data.alerts

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.service.AlertEvent
import com.jay.fxi.service.AlertFamily
import com.jay.fxi.service.AlertTapTicket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 E5c contract (R4c/S7/s7e5/s7e5c_exact_proposal.claude.r3.md §4 and §7, with the user decision errata
 * user_decision_s7e5c.errata.r1.md: the registry is the one path a bank push or tap takes to the owner): the registry
 * delivery of a bank alert push or OFF tap. It locks that [AlertFamilyRefresherRegistry.deliverPush] resolves nothing with
 * the gate closed, hands an exact `sync_alerts` to onSyncAlerts only and an exact `rate_alert` to onBankPush only, with
 * `setting_id` read as `toIntOrNull()` and the raw `is_repeat` string passed through unchanged, and resolves nothing for any
 * other type; that [AlertFamilyRefresherRegistry.deliverTap] resolves nothing with the gate closed, hands a bank
 * SettingTriggered to onBankPush with its id and the raw `is_repeat`, a RefreshNeeded to onBankPush(null, null) whatever
 * `is_repeat` the intent carried, and resolves nothing for any other event; that both report a throwing resolve or enqueue
 * once through their own reportFailure and return normally; that [AlertFamilyRefresherRegistry.onBankPush] posts to main
 * once per call and only there reaches every resolved family, in the foreground and in the background, isolates and
 * reports a throwing family through the registry's reportFailure, and resolves no family supplier before start; and that
 * one push or one tap is exactly one onBankPush on a family. The harness follows AlertFamilyRefresherRegistryContractTest
 * (injected main, failure counters, premium on a test scope); the lifecycle is the real registry without main-thread
 * checks. android.util.Log throws on the JVM, so every reportFailure is injected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class S7E5CRegistryDeliveryContractTest {

    /** The real lifecycle registry, without main-thread checks. */
    private class Process : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** Records every adapter call in order; [throwOnBank] throws [bankFailure] from onBankPush only, after recording it. */
    private class RecordingAdapter(
        val name: String,
        private val throwOnBank: Boolean,
        private val bankFailure: (String) -> Exception = { message -> IllegalStateException(message) }
    ) : AlertFamilyAdapter {
        val calls = mutableListOf<String>()
        override fun onForeground(authoritative: Boolean) {
            calls += if (authoritative) AUTH else FG
        }

        override fun onBackground() {
            calls += BG
        }

        override fun onSync() {
            calls += SYNC
        }

        override fun onBankPush(id: Int?, isRepeat: String?) {
            calls += bank(id, isRepeat)
            if (throwOnBank) throw bankFailure("$name bank push")
        }

        fun bankCalls() = calls.filter { it.startsWith(BANK_PREFIX) }
    }

    private enum class MainMode { IMMEDIATE, DEFERRED, THROWING }

    private class Harness(
        initial: Lifecycle.State,
        familyCount: Int = 2,
        throwingBankAdapter: Int? = null,
        private val mainMode: MainMode = MainMode.IMMEDIATE,
        bankFailure: (String) -> Exception = { message -> IllegalStateException(message) },
        throwingSupplier: Int? = null,
        supplierFailure: (String) -> Exception = { message -> IllegalStateException(message) },
        mainFailure: (String) -> Exception = { message -> IllegalStateException(message) }
    ) {
        val process = Process().also { it.registry.currentState = initial }
        val adapters = List(familyCount) { RecordingAdapter("family$it", throwOnBank = it == throwingBankAdapter, bankFailure = bankFailure) }
        val supplierCalls = IntArray(familyCount)
        val premium = MutableStateFlow(confirmed(ALICE))
        var live: AuthIdentityFence? = ALICE
        val scope = TestScope(StandardTestDispatcher())
        var failures = 0
        var posts = 0
        private val queued = mutableListOf<() -> Unit>()
        private val families: List<() -> AlertFamilyAdapter> = List(familyCount) { index ->
            {
                supplierCalls[index]++
                if (index == throwingSupplier) throw supplierFailure("family $index supplier")
                adapters[index]
            }
        }
        val registry = AlertFamilyRefresherRegistry(
            families = families,
            lifecycle = { process.registry },
            postToMain = { action ->
                posts++
                when (mainMode) {
                    MainMode.IMMEDIATE -> action()
                    MainMode.DEFERRED -> queued.add(action)
                    MainMode.THROWING -> throw mainFailure("main unavailable")
                }
                Unit
            },
            premium = premium,
            liveIdentity = { live },
            scope = scope,
            reportFailure = { failures++ }
        )

        fun started(): Harness {
            registry.start()
            scope.runCurrent()
            return this
        }

        fun runMain() {
            while (queued.isNotEmpty()) queued.removeAt(0)()
        }

        fun toForeground() {
            process.registry.currentState = Lifecycle.State.STARTED
        }

        fun toBackground() {
            process.registry.currentState = Lifecycle.State.CREATED
        }

        fun close() = scope.cancel()
    }

    private fun withHarness(harness: Harness, block: Harness.() -> Unit) {
        try {
            harness.block()
        } finally {
            harness.close()
        }
    }

    private fun Harness.assertEach(row: String, expected: List<String>) {
        for (adapter in adapters) assertEquals("$row: ${adapter.name}", expected, adapter.calls)
    }

    /** One deliverPush with counted resolve and reports; [resolveTo] null makes the resolve throw. */
    private class Delivery(var resolves: Int = 0, var reports: Int = 0, var escaped: Throwable? = null)

    private fun push(
        type: String,
        settingId: String?,
        isRepeat: String?,
        gateOpen: Boolean,
        resolveTo: AlertFamilyRefresherRegistry?,
        resolveFailure: (String) -> Exception = { message -> IllegalStateException(message) }
    ): Delivery {
        val delivery = Delivery()
        delivery.escaped = runCatching {
            AlertFamilyRefresherRegistry.deliverPush(type, settingId, isRepeat, gateOpen, reportFailure = { delivery.reports++ }) {
                delivery.resolves++
                resolveTo ?: throw resolveFailure("provider failed")
            }
        }.exceptionOrNull()
        return delivery
    }

    private fun tap(
        event: AlertEvent,
        isRepeat: String?,
        gateOpen: Boolean,
        resolveTo: AlertFamilyRefresherRegistry?,
        resolveFailure: (String) -> Exception = { message -> IllegalStateException(message) }
    ): Delivery {
        val delivery = Delivery()
        delivery.escaped = runCatching {
            AlertFamilyRefresherRegistry.deliverTap(event, isRepeat, gateOpen, reportFailure = { delivery.reports++ }) {
                delivery.resolves++
                resolveTo ?: throw resolveFailure("provider failed")
            }
        }.exceptionOrNull()
        return delivery
    }

    // ---- deliverPush ----

    @Test
    fun `S7E5C_P01 deliverPush with the gate closed resolves nothing for any type`() {
        for ((settingId, isRepeat) in PUSH_ARGUMENTS) {
            for (type in ADMITTED_TYPES + NEAR_MISS_TYPES) {
                val row = "S7E5C_P01 type='$type' setting_id=$settingId is_repeat=$isRepeat"
                if (type in ADMITTED_TYPES) {
                    withHarness(Harness(Lifecycle.State.STARTED).started()) {
                        val open = push(type, settingId, isRepeat, gateOpen = true, resolveTo = registry)
                        assertEquals("$row premise: the open gate resolves the same call", 1, open.resolves)
                    }
                }
                withHarness(Harness(Lifecycle.State.STARTED).started()) {
                    val closed = push(type, settingId, isRepeat, gateOpen = false, resolveTo = registry)
                    assertEquals("$row: no resolve", 0, closed.resolves)
                    assertEquals("$row: nothing posted", 0, posts)
                    assertEach("$row: no adapter call", listOf(FG))
                    assertEquals("$row: nothing reported", 0, closed.reports + failures)
                    assertEquals("$row: returns normally", null, closed.escaped)
                }
                // A resolve that would throw is never reached either.
                val closedThrowing = push(type, settingId, isRepeat, gateOpen = false, resolveTo = null)
                assertEquals("$row, throwing resolve: no resolve", 0, closedThrowing.resolves)
                assertEquals("$row, throwing resolve: nothing reported", 0, closedThrowing.reports)
            }
        }
    }

    @Test
    fun `S7E5C_P02 an exact sync_alerts goes to onSyncAlerts once and never to onBankPush`() {
        for ((settingId, isRepeat) in PUSH_ARGUMENTS) {
            val row = "S7E5C_P02 setting_id=$settingId is_repeat=$isRepeat"
            withHarness(Harness(Lifecycle.State.STARTED).started()) {
                val delivery = push("sync_alerts", settingId, isRepeat, gateOpen = true, resolveTo = registry)
                assertEquals("$row foreground: one resolve", 1, delivery.resolves)
                assertEquals("$row foreground: one main post (onSyncAlerts)", 1, posts)
                assertEach("$row foreground: onSync only", listOf(FG, SYNC))
                assertEquals("$row foreground: nothing reported", 0, delivery.reports + failures)
            }
            withHarness(Harness(Lifecycle.State.CREATED).started()) {
                val delivery = push("sync_alerts", settingId, isRepeat, gateOpen = true, resolveTo = registry)
                assertEquals("$row background: one resolve", 1, delivery.resolves)
                assertEquals("$row background: one main post (onSyncAlerts)", 1, posts)
                assertEach("$row background: held, nothing to an adapter", listOf(BG))
                toForeground()
                assertEach("$row background: the held sync is the next authoritative foreground, no onBankPush", listOf(BG, AUTH))
                assertEquals("$row background: nothing reported", 0, delivery.reports + failures)
            }
        }
    }

    @Test
    fun `S7E5C_P03 an exact rate_alert goes to onBankPush once with the read id and the raw is_repeat`() {
        assertEquals("S7E5C_P03 premise: the raw bank alert type", "rate_alert", AlertFamilyRefresherRegistry.RATE_ALERT)
        for ((settingId, id) in SETTING_IDS) {
            for (isRepeat in RAW_IS_REPEAT) {
                val row = "S7E5C_P03 setting_id=${quoted(settingId)} is_repeat=${quoted(isRepeat)}"
                withHarness(Harness(Lifecycle.State.STARTED).started()) {
                    val delivery = push("rate_alert", settingId, isRepeat, gateOpen = true, resolveTo = registry)
                    assertEquals("$row foreground: one resolve", 1, delivery.resolves)
                    assertEquals("$row foreground: one main post", 1, posts)
                    assertEach("$row foreground: onBankPush only, never onSync", listOf(FG, bank(id, isRepeat)))
                    assertEquals("$row foreground: nothing reported", 0, delivery.reports + failures)
                    assertEquals("$row foreground: returns normally", null, delivery.escaped)
                }
                withHarness(Harness(Lifecycle.State.CREATED).started()) {
                    val delivery = push("rate_alert", settingId, isRepeat, gateOpen = true, resolveTo = registry)
                    assertEquals("$row background: one resolve", 1, delivery.resolves)
                    assertEquals("$row background: one main post", 1, posts)
                    assertEach("$row background: onBankPush reaches the family in the background too", listOf(BG, bank(id, isRepeat)))
                    toForeground()
                    assertEach("$row background: the registry holds nothing for it, the next foreground is plain",
                        listOf(BG, bank(id, isRepeat), FG))
                    assertEquals("$row background: nothing reported", 0, delivery.reports + failures)
                }
            }
        }
    }

    @Test
    fun `S7E5C_P04 a near-miss type with the gate open resolves nothing`() {
        for ((settingId, isRepeat) in PUSH_ARGUMENTS) {
            withHarness(Harness(Lifecycle.State.STARTED).started()) {
                val exact = push("rate_alert", settingId, isRepeat, gateOpen = true, resolveTo = registry)
                assertEquals("S7E5C_P04 setting_id=$settingId is_repeat=$isRepeat premise: the exact type resolves", 1, exact.resolves)
            }
            for (type in NEAR_MISS_TYPES) {
                val row = "S7E5C_P04 type='$type' setting_id=$settingId is_repeat=$isRepeat"
                withHarness(Harness(Lifecycle.State.STARTED).started()) {
                    val delivery = push(type, settingId, isRepeat, gateOpen = true, resolveTo = registry)
                    assertEquals("$row: no resolve", 0, delivery.resolves)
                    assertEquals("$row: nothing posted", 0, posts)
                    assertEach("$row: no adapter call", listOf(FG))
                    assertEquals("$row: nothing reported", 0, delivery.reports + failures)
                }
            }
        }
    }

    @Test
    fun `S7E5C_P05 a throwing resolve is reported once and deliverPush returns normally`() {
        for (type in ADMITTED_TYPES) {
            for ((settingId, isRepeat) in PUSH_ARGUMENTS) {
                val row = "S7E5C_P05 type='$type' setting_id=$settingId is_repeat=$isRepeat"
                val delivery = push(type, settingId, isRepeat, gateOpen = true, resolveTo = null)
                assertEquals("$row premise: resolve was called", 1, delivery.resolves)
                assertEquals("$row: reported once", 1, delivery.reports)
                assertEquals("$row: returns normally", null, delivery.escaped)
            }
        }
    }

    @Test
    fun `S7E5C_P06 a rate_alert whose enqueue throws is reported once by deliverPush and not by the registry`() {
        withHarness(Harness(Lifecycle.State.STARTED, mainMode = MainMode.THROWING).started()) {
            assertTrue("S7E5C_P06 premise: onBankPush throws here", runCatching { registry.onBankPush(7, "false") }.isFailure)
            val postsBefore = posts
            val delivery = push("rate_alert", "7", "false", gateOpen = true, resolveTo = registry)
            assertEquals("S7E5C_P06 resolve was called", 1, delivery.resolves)
            assertEquals("S7E5C_P06 the enqueue was reached", postsBefore + 1, posts)
            assertEquals("S7E5C_P06 reported once by deliverPush", 1, delivery.reports)
            assertEquals("S7E5C_P06 not reported a second time by the registry", 0, failures)
            assertEquals("S7E5C_P06 returns normally", null, delivery.escaped)
            assertEach("S7E5C_P06 no adapter onBankPush", listOf(FG))
        }
    }

    // ---- deliverTap ----

    @Test
    fun `S7E5C_T01 deliverTap with the gate closed resolves nothing for any event`() {
        for (isRepeat in RAW_IS_REPEAT) {
            for (event in BANK_TAP_EVENTS + OTHER_TAP_EVENTS) {
                val row = "S7E5C_T01 event=$event is_repeat=${quoted(isRepeat)}"
                if (event in BANK_TAP_EVENTS) {
                    withHarness(Harness(Lifecycle.State.STARTED).started()) {
                        val open = tap(event, isRepeat, gateOpen = true, resolveTo = registry)
                        assertEquals("$row premise: the open gate resolves the same tap", 1, open.resolves)
                    }
                }
                withHarness(Harness(Lifecycle.State.STARTED).started()) {
                    val closed = tap(event, isRepeat, gateOpen = false, resolveTo = registry)
                    assertEquals("$row: no resolve", 0, closed.resolves)
                    assertEquals("$row: nothing posted", 0, posts)
                    assertEach("$row: no adapter call", listOf(FG))
                    assertEquals("$row: nothing reported", 0, closed.reports + failures)
                    assertEquals("$row: returns normally", null, closed.escaped)
                }
                val closedThrowing = tap(event, isRepeat, gateOpen = false, resolveTo = null)
                assertEquals("$row, throwing resolve: no resolve", 0, closedThrowing.resolves)
                assertEquals("$row, throwing resolve: nothing reported", 0, closedThrowing.reports)
            }
        }
    }

    @Test
    fun `S7E5C_T02 a bank SettingTriggered tap goes to onBankPush once with its id and the raw is_repeat`() {
        for (id in listOf(7, 0, 2_147_483_647)) {
            for (event in listOf(AlertEvent.SettingTriggered(id, AlertFamily.BANK), AlertEvent.SettingTriggered(id))) {
                for (isRepeat in RAW_IS_REPEAT) {
                    val row = "S7E5C_T02 event=$event is_repeat=${quoted(isRepeat)}"
                    for (state in listOf(Lifecycle.State.STARTED, Lifecycle.State.CREATED)) {
                        val handedOver = if (state == Lifecycle.State.STARTED) FG else BG
                        withHarness(Harness(state).started()) {
                            val delivery = tap(event, isRepeat, gateOpen = true, resolveTo = registry)
                            assertEquals("$row $state: one resolve", 1, delivery.resolves)
                            assertEquals("$row $state: one main post", 1, posts)
                            assertEach("$row $state: onBankPush(id, raw is_repeat) only", listOf(handedOver, bank(id, isRepeat)))
                            assertEquals("$row $state: nothing reported", 0, delivery.reports + failures)
                            assertEquals("$row $state: returns normally", null, delivery.escaped)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `S7E5C_T03 a RefreshNeeded tap goes to onBankPush(null, null) once whatever is_repeat it carried`() {
        for (isRepeat in RAW_IS_REPEAT) {
            val row = "S7E5C_T03 is_repeat=${quoted(isRepeat)}"
            for (state in listOf(Lifecycle.State.STARTED, Lifecycle.State.CREATED)) {
                val handedOver = if (state == Lifecycle.State.STARTED) FG else BG
                withHarness(Harness(state).started()) {
                    val delivery = tap(AlertEvent.RefreshNeeded, isRepeat, gateOpen = true, resolveTo = registry)
                    assertEquals("$row $state: one resolve", 1, delivery.resolves)
                    assertEquals("$row $state: one main post", 1, posts)
                    assertEach("$row $state: onBankPush(null, null) only", listOf(handedOver, bank(null, null)))
                    assertEquals("$row $state: nothing reported", 0, delivery.reports + failures)
                }
            }
        }
    }

    @Test
    fun `S7E5C_T04 a non-bank SettingTriggered, a FamilyRefreshNeeded or a PendingTap resolves nothing`() {
        for (isRepeat in RAW_IS_REPEAT) {
            for (event in OTHER_TAP_EVENTS) {
                val row = "S7E5C_T04 event=$event is_repeat=${quoted(isRepeat)}"
                withHarness(Harness(Lifecycle.State.STARTED).started()) {
                    val delivery = tap(event, isRepeat, gateOpen = true, resolveTo = registry)
                    assertEquals("$row: no resolve", 0, delivery.resolves)
                    assertEquals("$row: nothing posted", 0, posts)
                    assertEach("$row: no adapter call", listOf(FG))
                    assertEquals("$row: nothing reported", 0, delivery.reports + failures)
                }
            }
        }
    }

    @Test
    fun `S7E5C_T05 a throwing resolve on a bank tap is reported once and deliverTap returns normally`() {
        for (event in BANK_TAP_EVENTS) {
            for (isRepeat in RAW_IS_REPEAT) {
                val row = "S7E5C_T05 event=$event is_repeat=${quoted(isRepeat)}"
                val delivery = tap(event, isRepeat, gateOpen = true, resolveTo = null)
                assertEquals("$row premise: resolve was called", 1, delivery.resolves)
                assertEquals("$row: reported once", 1, delivery.reports)
                assertEquals("$row: returns normally", null, delivery.escaped)
            }
        }
    }

    @Test
    fun `S7E5C_T06 a bank tap whose enqueue throws is reported once by deliverTap and not by the registry`() {
        for (event in BANK_TAP_EVENTS) {
            withHarness(Harness(Lifecycle.State.STARTED, mainMode = MainMode.THROWING).started()) {
                val row = "S7E5C_T06 event=$event"
                assertTrue("$row premise: onBankPush throws here", runCatching { registry.onBankPush(null, null) }.isFailure)
                val postsBefore = posts
                val delivery = tap(event, "false", gateOpen = true, resolveTo = registry)
                assertEquals("$row: resolve was called", 1, delivery.resolves)
                assertEquals("$row: the enqueue was reached", postsBefore + 1, posts)
                assertEquals("$row: reported once by deliverTap", 1, delivery.reports)
                assertEquals("$row: not reported a second time by the registry", 0, failures)
                assertEquals("$row: returns normally", null, delivery.escaped)
                assertEach("$row: no adapter onBankPush", listOf(FG))
            }
        }
    }

    // ---- registry.onBankPush ----

    @Test
    fun `S7E5C_R01 onBankPush posts to main once per call and on main reaches every family with the same arguments`() {
        val pushes = listOf<Pair<Int?, String?>>(7 to "false", null to null, 8 to "x", 9 to "true", 10 to "null")
        for (state in listOf(Lifecycle.State.STARTED, Lifecycle.State.CREATED)) {
            val handedOver = if (state == Lifecycle.State.STARTED) FG else BG
            withHarness(Harness(state, familyCount = 3, mainMode = MainMode.DEFERRED).started()) {
                val row = "S7E5C_R01 $state"
                pushes.forEachIndexed { index, (id, isRepeat) ->
                    registry.onBankPush(id, isRepeat)
                    assertEquals("$row: call ${index + 1} posted to main exactly once", index + 1, posts)
                    assertEach("$row: call ${index + 1}: nothing before main runs", listOf(handedOver))
                }
                runMain()
                assertEach("$row: on main every family gets each call with its arguments, in order",
                    listOf(handedOver) + pushes.map { (id, isRepeat) -> bank(id, isRepeat) })
                assertEquals("$row: running main posts nothing more", pushes.size, posts)
                assertEquals("$row: nothing reported", 0, failures)
            }
        }
    }

    @Test
    fun `S7E5C_R02 a family that throws on onBankPush is reported by the registry and the others still get it`() {
        for (thrower in 0..2) {
            withHarness(Harness(Lifecycle.State.STARTED, familyCount = 3, throwingBankAdapter = thrower).started()) {
                val row = "S7E5C_R02 family$thrower throws"
                assertEquals("$row premise: nothing reported at start", 0, failures)
                val direct = runCatching { registry.onBankPush(7, "false") }
                assertEquals("$row direct: returns normally", null, direct.exceptionOrNull())
                assertEach("$row direct: every family got it, the thrower included", listOf(FG, bank(7, "false")))
                assertEquals("$row direct: reported once by the registry", 1, failures)

                val delivery = push("rate_alert", "8", "x", gateOpen = true, resolveTo = registry)
                assertEach("$row via deliverPush: every family got it", listOf(FG, bank(7, "false"), bank(8, "x")))
                assertEquals("$row via deliverPush: reported once more by the registry", 2, failures)
                assertEquals("$row via deliverPush: not reported by the delivery boundary", 0, delivery.reports)

                val tapped = tap(AlertEvent.SettingTriggered(9), "true", gateOpen = true, resolveTo = registry)
                assertEach("$row via deliverTap: every family got it",
                    listOf(FG, bank(7, "false"), bank(8, "x"), bank(9, "true")))
                assertEquals("$row via deliverTap: reported once more by the registry", 3, failures)
                assertEquals("$row via deliverTap: not reported by the delivery boundary", 0, tapped.reports)
            }
        }
    }

    @Test
    fun `S7E5C_R03 a bank push or tap before start resolves no family supplier`() {
        withHarness(Harness(Lifecycle.State.STARTED)) {
            registry.onBankPush(7, "false")
            val delivery = push("rate_alert", "8", "true", gateOpen = true, resolveTo = registry)
            val tapped = tap(AlertEvent.RefreshNeeded, "false", gateOpen = true, resolveTo = registry)
            assertEquals("S7E5C_R03 premise: each reached main", 3, posts)
            assertEquals("S7E5C_R03 premise: the registry was resolved", 2, delivery.resolves + tapped.resolves)
            assertEquals("S7E5C_R03 no family supplier resolved before start", listOf(0, 0), supplierCalls.toList())
            assertEach("S7E5C_R03 no adapter call before start", emptyList())
            assertEquals("S7E5C_R03 nothing reported", 0, delivery.reports + tapped.reports + failures)
            registry.start()
            scope.runCurrent()
            assertEquals("S7E5C_R03 premise: start resolves each family once", listOf(1, 1), supplierCalls.toList())
        }
    }

    // ---- one delivery, one onBankPush ----

    @Test
    fun `S7E5C_E01 one push or one tap is exactly one onBankPush on each family and never a second one later`() {
        val deliveries = listOf<Pair<String, Harness.() -> Delivery>>(
            "push rate_alert" to { push("rate_alert", "7", "false", gateOpen = true, resolveTo = registry) },
            "push rate_alert, unreadable id" to { push("rate_alert", "x", "true", gateOpen = true, resolveTo = registry) },
            "tap bank SettingTriggered" to { tap(AlertEvent.SettingTriggered(7), "false", gateOpen = true, resolveTo = registry) },
            "tap RefreshNeeded" to { tap(AlertEvent.RefreshNeeded, "false", gateOpen = true, resolveTo = registry) }
        )
        for ((name, deliver) in deliveries) {
            for (state in listOf(Lifecycle.State.STARTED, Lifecycle.State.CREATED)) {
                for (mainMode in listOf(MainMode.IMMEDIATE, MainMode.DEFERRED)) {
                    withHarness(Harness(state, mainMode = mainMode).started()) {
                        val row = "S7E5C_E01 $name, $state, main $mainMode"
                        val delivery = deliver(this)
                        runMain()
                        assertEquals("$row: one resolve", 1, delivery.resolves)
                        assertEquals("$row: one main post", 1, posts)
                        for (adapter in adapters) {
                            assertEquals("$row: ${adapter.name} got exactly one onBankPush", 1, adapter.bankCalls().size)
                        }
                        // Later lifecycle transitions and main turns re-deliver nothing.
                        if (state == Lifecycle.State.STARTED) toBackground() else toForeground()
                        if (state == Lifecycle.State.STARTED) toForeground() else toBackground()
                        runMain()
                        for (adapter in adapters) {
                            assertEquals("$row: ${adapter.name} still one onBankPush after a lifecycle round", 1, adapter.bankCalls().size)
                            assertTrue("$row: ${adapter.name} never onSync", SYNC !in adapter.calls)
                        }
                        assertEquals("$row: nothing reported", 0, delivery.reports + failures)
                    }
                }
            }
        }
    }

    // ---- gap G16: every boundary isolates any Exception, not only IllegalStateException ----

    /**
     * Gap G16 (`s7e5c_gaphunt.codex.r1.md` §3): the registry's dispatch isolates and reports any Exception a family throws on
     * onBankPush, directly and through deliverPush and deliverTap, and keeps dispatching later calls. The IllegalStateException
     * row is the control.
     */
    @Test
    fun `S7E5C_G16_1 a family that throws any Exception on onBankPush is reported by the registry and the others still get it`() {
        for ((kind, failure) in FAILURES) {
            for (thrower in 0..2) {
                withHarness(Harness(Lifecycle.State.STARTED, familyCount = 3, throwingBankAdapter = thrower, bankFailure = failure).started()) {
                    val row = "S7E5C_G16_1 family$thrower throws $kind"
                    assertEquals("$row premise: nothing reported at start", 0, failures)
                    val direct = runCatching { registry.onBankPush(7, "false") }
                    assertEquals("$row direct: returns normally", null, direct.exceptionOrNull())
                    assertEach("$row direct: every family got it once, the thrower included", listOf(FG, bank(7, "false")))
                    assertEquals("$row direct: reported once by the registry", 1, failures)

                    val delivery = push("rate_alert", "8", "x", gateOpen = true, resolveTo = registry)
                    assertEquals("$row via deliverPush: returns normally", null, delivery.escaped)
                    assertEach("$row via deliverPush: every family got it", listOf(FG, bank(7, "false"), bank(8, "x")))
                    assertEquals("$row via deliverPush: reported once more by the registry", 2, failures)
                    assertEquals("$row via deliverPush: not reported by the delivery boundary", 0, delivery.reports)

                    val tapped = tap(AlertEvent.SettingTriggered(9), "true", gateOpen = true, resolveTo = registry)
                    assertEquals("$row via deliverTap: returns normally", null, tapped.escaped)
                    assertEach("$row via deliverTap: every family got it", listOf(FG, bank(7, "false"), bank(8, "x"), bank(9, "true")))
                    assertEquals("$row via deliverTap: reported once more by the registry", 3, failures)
                    assertEquals("$row via deliverTap: not reported by the delivery boundary", 0, tapped.reports)

                    toBackground()
                    toForeground()
                    assertEach("$row: later lifecycle calls still reach every family",
                        listOf(FG, bank(7, "false"), bank(8, "x"), bank(9, "true"), BG, FG))
                    assertEquals("$row: the lifecycle calls report nothing", 3, failures)
                }
            }
        }
    }

    /**
     * Gap G16: a family supplier that throws any Exception at start is reported once, start returns normally, and the other
     * families get the hand-over, a bank push and the later lifecycle calls; a repeated start does not retry it.
     */
    @Test
    fun `S7E5C_G16_2 a family supplier that throws any Exception is reported once and the other families stay registered`() {
        for ((kind, failure) in FAILURES) {
            for (thrower in 0..2) {
                withHarness(Harness(Lifecycle.State.STARTED, familyCount = 3, throwingSupplier = thrower, supplierFailure = failure)) {
                    val row = "S7E5C_G16_2 supplier $thrower throws $kind"
                    val started = runCatching {
                        registry.start()
                        scope.runCurrent()
                    }
                    assertEquals("$row: start returns normally", null, started.exceptionOrNull())
                    assertEquals("$row premise: every supplier was asked once", listOf(1, 1, 1), supplierCalls.toList())
                    assertEquals("$row: reported once", 1, failures)
                    val others = adapters.filterIndexed { index, _ -> index != thrower }
                    fun assertOthers(label: String, expected: List<String>) {
                        for (adapter in others) assertEquals("$row $label: ${adapter.name}", expected, adapter.calls)
                        assertEquals("$row $label: the failed family is not registered", emptyList<String>(), adapters[thrower].calls)
                    }
                    assertOthers("hand-over", listOf(FG))
                    registry.onBankPush(7, "false")
                    assertOthers("bank push", listOf(FG, bank(7, "false")))
                    toBackground()
                    assertOthers("background", listOf(FG, bank(7, "false"), BG))
                    toForeground()
                    assertOthers("foreground", listOf(FG, bank(7, "false"), BG, FG))
                    registry.start()
                    scope.runCurrent()
                    assertEquals("$row: a repeated start does not retry", listOf(1, 1, 1), supplierCalls.toList())
                    assertEquals("$row: still one report", 1, failures)
                }
            }
        }
    }

    /**
     * Gap G16: deliverPush and deliverTap report a resolve or an enqueue that throws any Exception once through their own
     * reportFailure and return normally; the registry reports nothing for it.
     */
    @Test
    fun `S7E5C_G16_3 a resolve or an enqueue that throws any Exception is reported once by the delivery boundary`() {
        for ((kind, failure) in FAILURES) {
            for (type in ADMITTED_TYPES) {
                val row = "S7E5C_G16_3 push '$type', resolve throws $kind"
                val delivery = push(type, "7", "false", gateOpen = true, resolveTo = null, resolveFailure = failure)
                assertEquals("$row premise: resolve was called", 1, delivery.resolves)
                assertEquals("$row: reported once", 1, delivery.reports)
                assertEquals("$row: returns normally", null, delivery.escaped)
            }
            for (event in BANK_TAP_EVENTS) {
                val row = "S7E5C_G16_3 tap $event, resolve throws $kind"
                val delivery = tap(event, "false", gateOpen = true, resolveTo = null, resolveFailure = failure)
                assertEquals("$row premise: resolve was called", 1, delivery.resolves)
                assertEquals("$row: reported once", 1, delivery.reports)
                assertEquals("$row: returns normally", null, delivery.escaped)
            }
            withHarness(Harness(Lifecycle.State.STARTED, mainMode = MainMode.THROWING, mainFailure = failure).started()) {
                val row = "S7E5C_G16_3 enqueue throws $kind"
                val thrown = runCatching { registry.onBankPush(7, "false") }.exceptionOrNull()
                assertEquals("$row premise: the enqueue throws exactly that kind", failure("probe").javaClass, thrown?.javaClass)
                for (type in ADMITTED_TYPES) {
                    val postsBefore = posts
                    val delivery = push(type, "7", "false", gateOpen = true, resolveTo = registry)
                    assertEquals("$row push '$type': resolve was called", 1, delivery.resolves)
                    assertEquals("$row push '$type': the enqueue was reached", postsBefore + 1, posts)
                    assertEquals("$row push '$type': reported once by deliverPush", 1, delivery.reports)
                    assertEquals("$row push '$type': returns normally", null, delivery.escaped)
                }
                for (event in BANK_TAP_EVENTS) {
                    val postsBefore = posts
                    val delivery = tap(event, "false", gateOpen = true, resolveTo = registry)
                    assertEquals("$row tap $event: resolve was called", 1, delivery.resolves)
                    assertEquals("$row tap $event: the enqueue was reached", postsBefore + 1, posts)
                    assertEquals("$row tap $event: reported once by deliverTap", 1, delivery.reports)
                    assertEquals("$row tap $event: returns normally", null, delivery.escaped)
                }
                assertEquals("$row: never reported by the registry", 0, failures)
                assertEach("$row: no adapter call beyond the hand-over", listOf(FG))
            }
        }
    }

    private companion object {
        const val FG = "onForeground(false)"
        const val AUTH = "onForeground(true)"
        const val BG = "onBackground()"
        const val SYNC = "onSync()"
        const val BANK_PREFIX = "onBankPush("

        fun quoted(value: String?) = value?.let { "\"$it\"" } ?: "null"

        /** One onBankPush call; isRepeat is quoted so the raw string "null" and an absent value stay apart. */
        fun bank(id: Int?, isRepeat: String?) = "$BANK_PREFIX$id, ${quoted(isRepeat)})"

        val ADMITTED_TYPES = listOf("sync_alerts", "rate_alert")

        val NEAR_MISS_TYPES = listOf(
            "RATE_ALERT", " rate_alert", "rate_alerts", "source_rate_alert", "comparison_alert", "",
            "rate_alert ", "Rate_Alert", "rate-alert", "rate_alert\n", "SYNC_ALERTS", "unknown"
        )

        /** setting_id as received and what onBankPush must get: `toIntOrNull()` and nothing else. */
        val SETTING_IDS = listOf<Pair<String?, Int?>>(
            "7" to 7, "x" to null, null to null, "" to null, "-3" to -3, "007" to 7, " 7" to null, "7.0" to null,
            "2147483648" to null
        )

        /** is_repeat as the server sent it; it must reach onBankPush unchanged. */
        val RAW_IS_REPEAT = listOf("false", "true", "x", null, "FALSE", "", "null", " false")

        val PUSH_ARGUMENTS = listOf<Pair<String?, String?>>("7" to "false", null to null, "x" to "true", "8" to "x")

        val BANK_TAP_EVENTS = listOf<AlertEvent>(AlertEvent.SettingTriggered(7, AlertFamily.BANK), AlertEvent.RefreshNeeded)

        val OTHER_TAP_EVENTS = listOf<AlertEvent>(
            AlertEvent.SettingTriggered(7, AlertFamily.SOURCE),
            AlertEvent.SettingTriggered(7, AlertFamily.COMPARISON),
            AlertEvent.FamilyRefreshNeeded(AlertFamily.BANK),
            AlertEvent.FamilyRefreshNeeded(AlertFamily.SOURCE),
            AlertEvent.FamilyRefreshNeeded(AlertFamily.COMPARISON),
            AlertEvent.PendingTap(AlertTapTicket("foreign-owner", 1))
        )

        /** Every Exception kind a boundary must isolate and report (gap G16); IllegalStateException is the control row. */
        val FAILURES = listOf<Pair<String, (String) -> Exception>>(
            "IllegalStateException" to { message -> IllegalStateException(message) },
            "IllegalArgumentException" to { message -> IllegalArgumentException(message) },
            "IOException" to { message -> java.io.IOException(message) },
            "RuntimeException" to { message -> RuntimeException(message) },
            "Exception" to { message -> Exception(message) }
        )

        val ALICE = AuthIdentityFence("user-a", 1)

        fun confirmed(who: AuthIdentityFence) =
            OwnedPremiumAccess(who.uid, who.authGeneration, PremiumAccessState.PremiumConfirmed)
    }
}
