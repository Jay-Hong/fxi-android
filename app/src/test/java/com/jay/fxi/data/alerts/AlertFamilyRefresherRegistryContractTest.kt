package com.jay.fxi.data.alerts

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Claude-owned S7 N1 contract (R4c/S7/s7n1 API r1-r3): the process alert-family registry and its FCM entry (rows N1-M, N1-N,
 * N1-O registry parts; N1-P part 1). It locks that [AlertFamilyRefresherRegistry.start] is idempotent and is the only place
 * families are resolved, one lifecycle observer and one premium subscription are installed; that the state at start and every
 * ON_START / ON_STOP go through one transition that a repeat of the same value never re-dispatches (also when the real
 * registry replays the current state into the new observer); that a foreground `sync_alerts` reaches each adapter at once,
 * while a background or pre-start one is held for the live premium identity it arrived for, is dropped by the premium flow
 * when that identity loses premium or changes, and turns the next foreground authoritative once only if the live identity
 * is still that one and premium-confirmed, else that foreground is a plain ensure; that a sync without a premium-confirmed
 * live identity leaves nothing held; that a throwing family supplier or adapter is reported once and isolated; and that
 * `deliverPush` resolves only an exact `sync_alerts` (handed to onSyncAlerts) or, since S7 E5c, an exact `rate_alert`
 * (handed to onBankPush only, never onSync) with the gate open, reporting a throwing resolve or hand-over once and
 * returning normally (E5c delivery details: S7E5CRegistryDeliveryContractTest). Failure reporting is an injected counter
 * (android.util.Log throws on the JVM), main is `postToMain`, and the premium subscription runs on a test scope settled
 * with runCurrent.
 * The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertFamilyRefresherRegistryContractTest {

    /** A process lifecycle that records each registration and delivers only what a test sends: it never replays on add. */
    private class FakeLifecycle(initial: Lifecycle.State) : Lifecycle() {
        val observers = mutableListOf<LifecycleObserver>()
        var registrations = 0
            private set
        private var state = initial
        private val owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = this@FakeLifecycle
        }

        override val currentState: Lifecycle.State get() = state

        override fun addObserver(observer: LifecycleObserver) {
            registrations++
            observers += observer
        }

        override fun removeObserver(observer: LifecycleObserver) {
            observers -= observer
        }

        fun send(event: Lifecycle.Event) {
            state = when (event) {
                Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_STOP -> Lifecycle.State.CREATED
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_PAUSE -> Lifecycle.State.STARTED
                Lifecycle.Event.ON_RESUME -> Lifecycle.State.RESUMED
                Lifecycle.Event.ON_DESTROY -> Lifecycle.State.DESTROYED
                Lifecycle.Event.ON_ANY -> state
            }
            for (observer in observers.toList()) deliver(observer, event)
        }

        private fun deliver(observer: LifecycleObserver, event: Lifecycle.Event) {
            var handled = false
            if (observer is DefaultLifecycleObserver) {
                handled = true
                when (event) {
                    Lifecycle.Event.ON_CREATE -> observer.onCreate(owner)
                    Lifecycle.Event.ON_START -> observer.onStart(owner)
                    Lifecycle.Event.ON_RESUME -> observer.onResume(owner)
                    Lifecycle.Event.ON_PAUSE -> observer.onPause(owner)
                    Lifecycle.Event.ON_STOP -> observer.onStop(owner)
                    Lifecycle.Event.ON_DESTROY -> observer.onDestroy(owner)
                    Lifecycle.Event.ON_ANY -> Unit
                }
            }
            if (observer is LifecycleEventObserver) {
                handled = true
                observer.onStateChanged(owner, event)
            }
            if (!handled) fail("unsupported observer type ${observer.javaClass.name}")
        }
    }

    /** The real registry, without main-thread checks: it replays the current state into an observer on add. */
    private class Process : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class RecordingAdapter(val name: String, private val throwAll: Boolean) : AlertFamilyAdapter {
        val calls = mutableListOf<String>()
        override fun onForeground(authoritative: Boolean) = record(if (authoritative) AUTH else FG)
        override fun onBackground() = record(BG)
        override fun onSync() = record(SYNC)
        override fun onBankPush(id: Int?, isRepeat: String?) = record(bank(id, isRepeat))

        private fun record(call: String) {
            calls += call
            if (throwAll) throw IllegalStateException("$name $call")
        }
    }

    private enum class MainMode { IMMEDIATE, DEFERRED, THROWING }

    private class Harness(
        private val observed: Lifecycle,
        familyCount: Int = 2,
        private val throwingSupplier: Int? = null,
        throwingAdapter: Int? = null,
        private val mainMode: MainMode = MainMode.IMMEDIATE
    ) {
        val adapters = List(familyCount) { RecordingAdapter("family$it", throwAll = it == throwingAdapter) }
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
                if (index == throwingSupplier) throw IllegalStateException("family $index supplier")
                adapters[index]
            }
        }
        val registry = AlertFamilyRefresherRegistry(
            families = families,
            lifecycle = { observed },
            postToMain = { action ->
                posts++
                when (mainMode) {
                    MainMode.IMMEDIATE -> action()
                    MainMode.DEFERRED -> queued.add(action)
                    MainMode.THROWING -> throw IllegalStateException("main unavailable")
                }
                Unit
            },
            premium = premium,
            liveIdentity = { live },
            scope = scope,
            reportFailure = { failures++ }
        )

        fun settle() = scope.runCurrent()

        fun runMain() {
            while (queued.isNotEmpty()) queued.removeAt(0)()
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

    private class Gate(val name: String, val live: AuthIdentityFence?, val premium: OwnedPremiumAccess, val confirms: Boolean)

    /** What the identity and grant move to while a sync is held; null [premium] means no move (the control). */
    private class Interim(val name: String, val live: AuthIdentityFence?, val premium: OwnedPremiumAccess?)

    /** A change made between holding a sync and the next ON_START, with nothing settled in between. */
    private class ConsumeCase(val name: String, val change: Harness.() -> Unit, val expected: String)

    /** A mismatch in force at one ON_START and its undoing before the next one, with nothing settled in between. */
    private class SpentCase(val name: String, val mismatch: Harness.() -> Unit, val restore: Harness.() -> Unit)

    /** The live identity when onSyncAlerts is called, and the identity and grant main sees when it runs that call. */
    private class Handover(
        val name: String,
        val atCall: AuthIdentityFence?,
        val onMain: AuthIdentityFence?,
        val grant: OwnedPremiumAccess,
        val served: Boolean
    )

    private val gates = listOf(
        Gate("premium-confirmed live identity (control)", ALICE, confirmed(ALICE), confirms = true),
        Gate("signed out", null, OwnedPremiumAccess(), confirms = false),
        Gate("signed out while the old grant is still published", null, confirmed(ALICE), confirms = false),
        Gate("no grant yet", ALICE, OwnedPremiumAccess(), confirms = false),
        Gate("pending decision", ALICE, access(ALICE, PremiumAccessState.Pending(continuityEligible = false)), confirms = false),
        Gate("free", ALICE, access(ALICE, PremiumAccessState.FreeConfirmed), confirms = false),
        Gate("rejected", ALICE, access(ALICE, PremiumAccessState.Rejected), confirms = false),
        Gate("another account's grant", ALICE, confirmed(BOB), confirms = false),
        Gate("the previous session's grant", ALICE_RELOGIN, confirmed(ALICE), confirms = false)
    )

    // ---- N1-M: start, observer, hand-over, transitions ----

    @Test
    fun `S7N1_M01 nothing is resolved, observed, subscribed or dispatched before start`() {
        val process = FakeLifecycle(Lifecycle.State.STARTED)
        withHarness(Harness(process)) {
            settle()
            assertEquals("S7N1_M01 no family resolved before start", listOf(0, 0), supplierCalls.toList())
            assertEquals("S7N1_M01 no observer before start", 0, process.registrations)
            assertEquals("S7N1_M01 no premium subscription before start", 0, premium.subscriptionCount.value)
            assertEach("S7N1_M01 no dispatch before start", emptyList())

            registry.start()
            settle()
            assertEquals("S7N1_M01 start resolves each family once", listOf(1, 1), supplierCalls.toList())
            assertEquals("S7N1_M01 start installs one observer", 1, process.registrations)
            assertEquals("S7N1_M01 start installs one observer", 1, process.observers.size)
            assertEquals("S7N1_M01 start installs one premium subscription", 1, premium.subscriptionCount.value)
            assertEach("S7N1_M01 start hands the foreground over", listOf(FG))
        }
    }

    @Test
    fun `S7N1_M02 a repeated start adds no observer, family resolve, subscription or dispatch`() {
        for ((state, handedOver) in listOf(Lifecycle.State.STARTED to FG, Lifecycle.State.CREATED to BG)) {
            val process = FakeLifecycle(state)
            withHarness(Harness(process)) {
                registry.start()
                settle()
                assertEquals("S7N1_M02 $state premise: one registration", 1, process.registrations)
                assertEquals("S7N1_M02 $state premise: one family resolve each", listOf(1, 1), supplierCalls.toList())
                assertEquals("S7N1_M02 $state premise: one subscription", 1, premium.subscriptionCount.value)
                assertEach("S7N1_M02 $state premise: one hand-over", listOf(handedOver))

                registry.start()
                registry.start()
                settle()
                assertEquals("S7N1_M02 $state repeated start: no observer", 1, process.registrations)
                assertEquals("S7N1_M02 $state repeated start: no observer", 1, process.observers.size)
                assertEquals("S7N1_M02 $state repeated start: no family resolve", listOf(1, 1), supplierCalls.toList())
                assertEquals("S7N1_M02 $state repeated start: no subscription", 1, premium.subscriptionCount.value)
                assertEach("S7N1_M02 $state repeated start: no dispatch", listOf(handedOver))
            }
        }
    }

    @Test
    fun `S7N1_M03 start hands the current state over once and a late callback of the same value adds nothing`() {
        for ((state, expected) in listOf(
            Lifecycle.State.INITIALIZED to BG,
            Lifecycle.State.CREATED to BG,
            Lifecycle.State.STARTED to FG,
            Lifecycle.State.RESUMED to FG
        )) {
            val process = FakeLifecycle(state)
            withHarness(Harness(process)) {
                registry.start()
                settle()
                assertEach("S7N1_M03 registered in $state: one hand-over", listOf(expected))
                // The observer's own callback for the state start already handed over arrives afterwards.
                process.send(if (expected == FG) Lifecycle.Event.ON_START else Lifecycle.Event.ON_STOP)
                assertEach("S7N1_M03 registered in $state: the same value again adds nothing", listOf(expected))
            }
        }
    }

    @Test
    fun `S7N1_M04 the real registry initial callback and the start hand-over make one transition`() {
        for ((state, expected, replay) in listOf(
            Triple(Lifecycle.State.CREATED, BG, listOf(Lifecycle.Event.ON_CREATE)),
            Triple(Lifecycle.State.STARTED, FG, listOf(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_START)),
            Triple(
                Lifecycle.State.RESUMED, FG,
                listOf(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME)
            )
        )) {
            val process = Process()
            process.registry.currentState = state
            val probe = mutableListOf<Lifecycle.Event>()
            process.registry.addObserver(LifecycleEventObserver { _, event -> probe += event })
            assertEquals("S7N1_M04 premise: the real registry replays $state into a new observer", replay, probe)
            val before = process.registry.observerCount
            withHarness(Harness(process.registry)) {
                registry.start()
                settle()
                assertEquals("S7N1_M04 $state: one observer added", before + 1, process.registry.observerCount)
                assertEach("S7N1_M04 $state: replay and hand-over are one transition", listOf(expected))
                registry.start()
                settle()
                assertEquals("S7N1_M04 $state: a repeated start adds no observer", before + 1, process.registry.observerCount)
                assertEach("S7N1_M04 $state: a repeated start adds no dispatch", listOf(expected))
            }
        }
    }

    @Test
    fun `S7N1_M05 a sync held before start is served once through the real registry initial callback`() {
        val process = Process()
        process.registry.currentState = Lifecycle.State.STARTED
        withHarness(Harness(process.registry)) {
            registry.onSyncAlerts()
            assertEquals("S7N1_M05 premise: the sync reached main", 1, posts)
            assertEach("S7N1_M05 nothing before start", emptyList())
            registry.start()
            settle()
            assertEach("S7N1_M05 one authoritative foreground, no plain one after it", listOf(AUTH))
        }
    }

    @Test
    fun `S7N1_M06 ON_START and ON_STOP each dispatch once and other events or repeats dispatch nothing`() {
        val process = FakeLifecycle(Lifecycle.State.CREATED)
        withHarness(Harness(process)) {
            registry.start()
            settle()
            assertEach("S7N1_M06 background hand-over", listOf(BG))
            process.send(Lifecycle.Event.ON_STOP)
            process.send(Lifecycle.Event.ON_CREATE)
            assertEach("S7N1_M06 a repeated background and ON_CREATE add nothing", listOf(BG))
            process.send(Lifecycle.Event.ON_START)
            assertEach("S7N1_M06 ON_START", listOf(BG, FG))
            process.send(Lifecycle.Event.ON_RESUME)
            process.send(Lifecycle.Event.ON_START)
            process.send(Lifecycle.Event.ON_PAUSE)
            assertEach("S7N1_M06 ON_RESUME, a repeated ON_START and ON_PAUSE add nothing", listOf(BG, FG))
            process.send(Lifecycle.Event.ON_STOP)
            assertEach("S7N1_M06 ON_STOP", listOf(BG, FG, BG))
            process.send(Lifecycle.Event.ON_STOP)
            assertEach("S7N1_M06 a repeated ON_STOP adds nothing", listOf(BG, FG, BG))
            process.send(Lifecycle.Event.ON_START)
            assertEach("S7N1_M06 ON_START again", listOf(BG, FG, BG, FG))
            assertEquals("S7N1_M06 no failure", 0, failures)
        }
    }

    @Test
    fun `S7N1_M07 a real process walk dispatches one call per foreground change`() {
        val process = Process()
        process.registry.currentState = Lifecycle.State.CREATED
        withHarness(Harness(process.registry)) {
            registry.start()
            settle()
            assertEach("S7N1_M07 background hand-over", listOf(BG))
            process.registry.currentState = Lifecycle.State.STARTED
            assertEach("S7N1_M07 to STARTED", listOf(BG, FG))
            process.registry.currentState = Lifecycle.State.RESUMED
            process.registry.currentState = Lifecycle.State.STARTED
            assertEach("S7N1_M07 resume and pause keep the value", listOf(BG, FG))
            process.registry.currentState = Lifecycle.State.CREATED
            assertEach("S7N1_M07 to CREATED", listOf(BG, FG, BG))
            process.registry.currentState = Lifecycle.State.RESUMED
            assertEach("S7N1_M07 straight to RESUMED", listOf(BG, FG, BG, FG))
        }
    }

    @Test
    fun `S7N1_M08 premium and identity changes in the background dispatch nothing`() {
        val process = FakeLifecycle(Lifecycle.State.CREATED)
        withHarness(Harness(process)) {
            premium.value = access(ALICE, PremiumAccessState.FreeConfirmed)
            registry.start()
            settle()
            assertEquals("S7N1_M08 premise: subscribed to premium", 1, premium.subscriptionCount.value)
            premium.value = confirmed(ALICE)
            settle()
            live = BOB
            premium.value = confirmed(BOB)
            settle()
            live = null
            premium.value = OwnedPremiumAccess()
            settle()
            assertEach("S7N1_M08 activation and identity changes in the background", listOf(BG))
            live = ALICE
            premium.value = confirmed(ALICE)
            settle()
            process.send(Lifecycle.Event.ON_START)
            assertEach("S7N1_M08 positive: the next foreground still reaches each family", listOf(BG, FG))
        }
    }

    @Test
    fun `S7N1_M09 an FCM sync that resolves the registry before start resolves no family and is served at the first foreground`() {
        val process = FakeLifecycle(Lifecycle.State.STARTED)
        withHarness(Harness(process)) {
            var resolves = 0
            var reports = 0
            AlertFamilyRefresherRegistry.deliverPush(AlertFamilyRefresherRegistry.SYNC_ALERTS, null, null, true, reportFailure = { reports++ }) {
                resolves++
                registry
            }
            assertEquals("S7N1_M09 premise: the registry was resolved", 1, resolves)
            assertEquals("S7N1_M09 premise: the sync reached main", 1, posts)
            assertEquals("S7N1_M09 no family resolved by the FCM path", listOf(0, 0), supplierCalls.toList())
            assertEquals("S7N1_M09 no observer by the FCM path", 0, process.registrations)
            assertEach("S7N1_M09 no dispatch before start", emptyList())
            registry.start()
            settle()
            assertEquals("S7N1_M09 start resolves the families", listOf(1, 1), supplierCalls.toList())
            assertEach("S7N1_M09 the first foreground serves the sync", listOf(AUTH))
            assertEquals("S7N1_M09 no failure", 0, reports + failures)
        }
    }

    // ---- N1-N: sync, pending, identity ----

    @Test
    fun `S7N1_N01 a foreground sync reaches each adapter at once and is not held`() {
        val process = FakeLifecycle(Lifecycle.State.STARTED)
        withHarness(Harness(process)) {
            registry.start()
            settle()
            assertEach("S7N1_N01 premise: foreground hand-over", listOf(FG))
            registry.onSyncAlerts()
            assertEquals("S7N1_N01 through main once", 1, posts)
            assertEach("S7N1_N01 onSync on each adapter", listOf(FG, SYNC))
            process.send(Lifecycle.Event.ON_STOP)
            process.send(Lifecycle.Event.ON_START)
            assertEach("S7N1_N01 a served sync is not held for the next foreground", listOf(FG, SYNC, BG, FG))
        }
    }

    @Test
    fun `S7N1_N02 background syncs are held as one and make the next foreground authoritative once`() {
        val process = FakeLifecycle(Lifecycle.State.CREATED)
        withHarness(Harness(process)) {
            registry.start()
            settle()
            repeat(3) { registry.onSyncAlerts() }
            assertEquals("S7N1_N02 premise: each sync reached main", 3, posts)
            assertEach("S7N1_N02 nothing to an adapter in the background", listOf(BG))
            process.send(Lifecycle.Event.ON_STOP)
            assertEach("S7N1_N02 a repeated background adds nothing", listOf(BG))
            process.send(Lifecycle.Event.ON_START)
            assertEach("S7N1_N02 one authoritative foreground, never onSync", listOf(BG, AUTH))
            process.send(Lifecycle.Event.ON_START)
            assertEach("S7N1_N02 a repeated foreground adds nothing", listOf(BG, AUTH))
            process.send(Lifecycle.Event.ON_STOP)
            process.send(Lifecycle.Event.ON_START)
            assertEach("S7N1_N02 the consumed sync reaches nobody later", listOf(BG, AUTH, BG, FG))
        }
    }

    @Test
    fun `S7N1_N03 a sync before start is held and served at the first foreground`() {
        val atStart = FakeLifecycle(Lifecycle.State.STARTED)
        withHarness(Harness(atStart)) {
            registry.onSyncAlerts()
            assertEquals("S7N1_N03 premise: the sync reached main", 1, posts)
            assertEquals("S7N1_N03 the sync resolves no family", listOf(0, 0), supplierCalls.toList())
            registry.start()
            settle()
            assertEach("S7N1_N03 foreground at start serves it", listOf(AUTH))
        }
        val later = FakeLifecycle(Lifecycle.State.CREATED)
        withHarness(Harness(later)) {
            registry.onSyncAlerts()
            registry.start()
            settle()
            assertEach("S7N1_N03 background at start holds it", listOf(BG))
            later.send(Lifecycle.Event.ON_START)
            assertEach("S7N1_N03 the first foreground serves it", listOf(BG, AUTH))
        }
    }

    @Test
    fun `S7N1_N04 a sync without a premium-confirmed live identity is dropped and leaves no pending`() {
        for (gate in gates) {
            val process = FakeLifecycle(Lifecycle.State.CREATED)
            withHarness(Harness(process)) {
                live = gate.live
                premium.value = gate.premium
                registry.start()
                settle()
                registry.onSyncAlerts()
                assertEquals("S7N1_N04 background, ${gate.name}: premise: the sync reached main", 1, posts)
                assertEach("S7N1_N04 background, ${gate.name}: nothing to an adapter", listOf(BG))
                // The live identity becomes premium-confirmed before the next foreground: a dropped sync must not come back.
                live = ALICE
                premium.value = confirmed(ALICE)
                settle()
                process.send(Lifecycle.Event.ON_START)
                assertEach("S7N1_N04 background, ${gate.name}", listOf(BG, if (gate.confirms) AUTH else FG))
            }
        }
        for (gate in gates) {
            withHarness(Harness(FakeLifecycle(Lifecycle.State.STARTED))) {
                live = gate.live
                premium.value = gate.premium
                registry.start()
                settle()
                registry.onSyncAlerts()
                assertEquals("S7N1_N04 foreground, ${gate.name}: premise: the sync reached main", 1, posts)
                assertEach("S7N1_N04 foreground, ${gate.name}", if (gate.confirms) listOf(FG, SYNC) else listOf(FG))
            }
        }
    }

    @Test
    fun `S7N1_N05 the premium flow drops a held sync when its identity loses premium or changes`() {
        for (interim in listOf(
            Interim("no change (control)", ALICE, null),
            Interim("premium lost", ALICE, access(ALICE, PremiumAccessState.FreeConfirmed)),
            Interim("premium rejected", ALICE, access(ALICE, PremiumAccessState.Rejected)),
            Interim("signed out", null, OwnedPremiumAccess()),
            Interim("another account", BOB, confirmed(BOB)),
            Interim("same account, new session", ALICE_RELOGIN, confirmed(ALICE_RELOGIN))
        )) {
            val process = FakeLifecycle(Lifecycle.State.CREATED)
            withHarness(Harness(process)) {
                registry.start()
                settle()
                assertEquals("S7N1_N05 ${interim.name} premise: subscribed to premium", 1, premium.subscriptionCount.value)
                registry.onSyncAlerts()
                val moved = interim.premium
                if (moved != null) {
                    live = interim.live
                    premium.value = moved
                    settle()
                }
                // Back to exactly the identity and grant the sync arrived for: only the flow can have dropped it.
                live = ALICE
                premium.value = confirmed(ALICE)
                settle()
                process.send(Lifecycle.Event.ON_START)
                assertEach("S7N1_N05 ${interim.name}", listOf(BG, if (moved == null) AUTH else FG))
            }
        }
    }

    @Test
    fun `S7N1_N06 a held sync is consumed only for the same live identity with premium confirmed`() {
        for (case in listOf(
            ConsumeCase("no change (control)", {}, AUTH),
            ConsumeCase("live identity moved to a new session of the same account", { live = ALICE_RELOGIN }, FG),
            ConsumeCase("live identity moved to another account", { live = BOB }, FG),
            ConsumeCase("signed out", { live = null }, FG),
            ConsumeCase("premium lost, not yet seen by the flow", {
                premium.value = access(ALICE, PremiumAccessState.FreeConfirmed)
            }, FG),
            ConsumeCase("grant and identity moved to a new session", {
                live = ALICE_RELOGIN
                premium.value = confirmed(ALICE_RELOGIN)
            }, FG)
        )) {
            val process = FakeLifecycle(Lifecycle.State.CREATED)
            withHarness(Harness(process)) {
                registry.start()
                settle()
                assertEquals("S7N1_N06 ${case.name} premise: subscribed to premium", 1, premium.subscriptionCount.value)
                registry.onSyncAlerts()
                case.change(this)
                process.send(Lifecycle.Event.ON_START)
                assertEach("S7N1_N06 ${case.name}", listOf(BG, case.expected))
                settle()
                process.send(Lifecycle.Event.ON_STOP)
                process.send(Lifecycle.Event.ON_START)
                assertEach("S7N1_N06 ${case.name}: nothing held afterwards", listOf(BG, case.expected, BG, FG))
            }
        }
    }

    @Test
    fun `S7N1_N07 a later sync for a new premium identity is held for that identity`() {
        for (resent in listOf(true, false)) {
            val process = FakeLifecycle(Lifecycle.State.CREATED)
            withHarness(Harness(process)) {
                registry.start()
                settle()
                registry.onSyncAlerts()
                live = BOB
                premium.value = confirmed(BOB)
                settle()
                if (resent) registry.onSyncAlerts()
                process.send(Lifecycle.Event.ON_START)
                assertEach("S7N1_N07 resent for the new identity: $resent", listOf(BG, if (resent) AUTH else FG))
            }
        }
    }

    @Test
    fun `S7N1_N08 onSyncAlerts only posts to main and acts when main runs it`() {
        withHarness(Harness(FakeLifecycle(Lifecycle.State.STARTED), mainMode = MainMode.DEFERRED)) {
            registry.start()
            settle()
            registry.onSyncAlerts()
            assertEquals("S7N1_N08 posted once", 1, posts)
            assertEach("S7N1_N08 nothing before main runs", listOf(FG))
            runMain()
            assertEach("S7N1_N08 served when main runs", listOf(FG, SYNC))
        }
        val process = FakeLifecycle(Lifecycle.State.CREATED)
        withHarness(Harness(process, mainMode = MainMode.DEFERRED)) {
            registry.start()
            settle()
            registry.onSyncAlerts()
            process.send(Lifecycle.Event.ON_START)
            assertEach("S7N1_N08 not yet on main: the foreground is plain", listOf(BG, FG))
            runMain()
            assertEach("S7N1_N08 decided on main, now in the foreground", listOf(BG, FG, SYNC))
        }
    }

    @Test
    fun `S7N1_N09 a new registry carries no held sync from an earlier one`() {
        val first = FakeLifecycle(Lifecycle.State.CREATED)
        withHarness(Harness(first)) {
            registry.start()
            settle()
            registry.onSyncAlerts()
            first.send(Lifecycle.Event.ON_START)
            assertEach("S7N1_N09 premise: the first registry held and served it", listOf(BG, AUTH))
        }
        val restarted = FakeLifecycle(Lifecycle.State.CREATED)
        withHarness(Harness(restarted)) {
            registry.start()
            settle()
            restarted.send(Lifecycle.Event.ON_START)
            assertEach("S7N1_N09 a restarted process starts with nothing held", listOf(BG, FG))
        }
    }

    /** S7N1_N10: a mismatched consume drops the held sync, so the identity and grant coming back do not revive it. */
    @Test
    fun `S7N1_N10 a held sync spent on a mismatched foreground is not served when the identity and grant come back`() {
        for (case in listOf(
            SpentCase("live identity moved to another account", { live = BOB }, { live = ALICE }),
            SpentCase("signed out", { live = null }, { live = ALICE }),
            SpentCase("premium decision pending, not yet seen by the flow", {
                premium.value = access(ALICE, PremiumAccessState.Pending(continuityEligible = false))
            }, { premium.value = confirmed(ALICE) })
        )) {
            // Premise: the same mismatch undone before any foreground leaves the sync held, and the undone state serves it.
            val twin = FakeLifecycle(Lifecycle.State.CREATED)
            withHarness(Harness(twin)) {
                registry.start()
                settle()
                registry.onSyncAlerts()
                case.mismatch(this)
                case.restore(this)
                twin.send(Lifecycle.Event.ON_START)
                assertEach("S7N1_N10 ${case.name} premise: undone before any foreground, the sync is served", listOf(BG, AUTH))
            }
            val process = FakeLifecycle(Lifecycle.State.CREATED)
            withHarness(Harness(process)) {
                registry.start()
                settle()
                registry.onSyncAlerts()
                assertEquals("S7N1_N10 ${case.name} premise: the sync reached main", 1, posts)
                case.mismatch(this)
                process.send(Lifecycle.Event.ON_START)
                assertEach("S7N1_N10 ${case.name}: the mismatched foreground is plain", listOf(BG, FG))
                // Window: from the mismatched consume to the next ON_START, back on the held identity and grant, nothing settled.
                case.restore(this)
                process.send(Lifecycle.Event.ON_STOP)
                process.send(Lifecycle.Event.ON_START)
                assertEach("S7N1_N10 ${case.name}: the spent sync is not served again", listOf(BG, FG, BG, FG))
            }
        }
    }

    /** S7N1_N11: a background sync for a new premium identity re-binds a pending the flow has not dropped yet. */
    @Test
    fun `S7N1_N11 a sync for a new premium identity replaces a held sync the flow has not dropped yet`() {
        // Premise: with nothing settled the flow has not run, so a sync held for ALICE survives a move to BOB and back.
        val excursion = FakeLifecycle(Lifecycle.State.CREATED)
        withHarness(Harness(excursion)) {
            registry.start()
            settle()
            registry.onSyncAlerts()
            live = BOB
            premium.value = confirmed(BOB)
            live = ALICE
            premium.value = confirmed(ALICE)
            excursion.send(Lifecycle.Event.ON_START)
            assertEach("S7N1_N11 premise: ALICE's sync is still held while nothing is settled", listOf(BG, AUTH))
        }
        for (settledBeforeForeground in listOf(false, true)) {
            for (resent in listOf(false, true)) {
                val process = FakeLifecycle(Lifecycle.State.CREATED)
                withHarness(Harness(process)) {
                    val row = "S7N1_N11 resent for BOB: $resent, settled before the foreground: $settledBeforeForeground"
                    registry.start()
                    settle()
                    registry.onSyncAlerts()
                    live = BOB
                    premium.value = confirmed(BOB)
                    // Window: nothing settled since ALICE's sync was held, so it is still held when BOB's arrives.
                    if (resent) registry.onSyncAlerts()
                    assertEquals("$row premise: each sync reached main", if (resent) 2 else 1, posts)
                    assertEach("$row: nothing to an adapter in the background", listOf(BG))
                    if (settledBeforeForeground) settle()
                    process.send(Lifecycle.Event.ON_START)
                    val served = if (resent) AUTH else FG
                    assertEach(row, listOf(BG, served))
                    process.send(Lifecycle.Event.ON_STOP)
                    process.send(Lifecycle.Event.ON_START)
                    assertEach("$row: nothing held afterwards", listOf(BG, served, BG, FG))
                }
            }
        }
    }

    /** S7N1_N12: onSyncAlerts checks the identity live when main runs the call, not the one live at the call. */
    @Test
    fun `S7N1_N12 a sync is decided for the identity live when main runs it and not the one at the call`() {
        val handovers = listOf(
            Handover("unchanged (control)", ALICE, ALICE, confirmed(ALICE), served = true),
            Handover("signed in only after the call", null, ALICE, confirmed(ALICE), served = true),
            Handover("another premium account by the time main runs", ALICE, BOB, confirmed(BOB), served = true),
            Handover("signed out before main runs, old grant published", ALICE, null, confirmed(ALICE), served = false),
            Handover("new session before main runs, old grant published", ALICE, ALICE_RELOGIN, confirmed(ALICE), served = false)
        )
        for (inForeground in listOf(true, false)) {
            for (case in handovers) {
                val process = FakeLifecycle(if (inForeground) Lifecycle.State.STARTED else Lifecycle.State.CREATED)
                withHarness(Harness(process, mainMode = MainMode.DEFERRED)) {
                    val row = "S7N1_N12 ${if (inForeground) "foreground" else "background"}, ${case.name}"
                    val handedOver = if (inForeground) FG else BG
                    registry.start()
                    settle()
                    assertEach("$row premise: hand-over", listOf(handedOver))
                    live = case.atCall
                    registry.onSyncAlerts()
                    assertEquals("$row premise: posted once", 1, posts)
                    assertEach("$row: nothing before main runs", listOf(handedOver))
                    // Window: between the call and main running it, the identity and grant move to what main sees.
                    live = case.onMain
                    premium.value = case.grant
                    runMain()
                    if (inForeground) {
                        assertEach(row, if (case.served) listOf(FG, SYNC) else listOf(FG))
                    } else {
                        assertEach("$row: still nothing in the background", listOf(BG))
                        process.send(Lifecycle.Event.ON_START)
                        assertEach(row, listOf(BG, if (case.served) AUTH else FG))
                    }
                }
            }
        }
    }

    // ---- N1-O: isolation ----

    @Test
    fun `S7N1_O01 a family supplier that throws is reported once and the other family stays registered`() {
        for (thrower in 0..1) {
            val other = 1 - thrower
            val process = FakeLifecycle(Lifecycle.State.STARTED)
            withHarness(Harness(process, throwingSupplier = thrower)) {
                registry.start()
                settle()
                assertEquals("S7N1_O01 supplier $thrower premise: both suppliers were asked", listOf(1, 1), supplierCalls.toList())
                assertEquals("S7N1_O01 supplier $thrower: reported once", 1, failures)
                assertEquals("S7N1_O01 supplier $thrower: the observer is still installed", 1, process.registrations)
                assertEquals("S7N1_O01 supplier $thrower: the other family got the hand-over", listOf(FG), adapters[other].calls)
                registry.onSyncAlerts()
                assertEquals("S7N1_O01 supplier $thrower: the other family got the sync", listOf(FG, SYNC), adapters[other].calls)
                process.send(Lifecycle.Event.ON_STOP)
                assertEquals("S7N1_O01 supplier $thrower: the other family got ON_STOP", listOf(FG, SYNC, BG), adapters[other].calls)
                registry.start()
                settle()
                assertEquals("S7N1_O01 supplier $thrower: a repeated start does not retry", listOf(1, 1), supplierCalls.toList())
                assertEquals("S7N1_O01 supplier $thrower: still one report", 1, failures)
                assertEquals("S7N1_O01 supplier $thrower: the failed family is not registered", emptyList<String>(), adapters[thrower].calls)
            }
        }
    }

    @Test
    fun `S7N1_O02 an adapter that throws is reported once per call and the other adapters are still called`() {
        val process = FakeLifecycle(Lifecycle.State.STARTED)
        withHarness(Harness(process, familyCount = 3, throwingAdapter = 1)) {
            fun assertOthers(row: String, expected: List<String>) {
                assertEquals("$row: the throwing adapter was called", expected, adapters[1].calls)
                assertEquals("$row: the adapter before it", expected, adapters[0].calls)
                assertEquals("$row: the adapter after it", expected, adapters[2].calls)
            }
            registry.start()
            settle()
            assertOthers("S7N1_O02 foreground hand-over", listOf(FG))
            assertEquals("S7N1_O02 foreground hand-over: reported once", 1, failures)
            registry.onSyncAlerts()
            assertOthers("S7N1_O02 sync", listOf(FG, SYNC))
            assertEquals("S7N1_O02 sync: reported once more", 2, failures)
            process.send(Lifecycle.Event.ON_STOP)
            assertOthers("S7N1_O02 ON_STOP", listOf(FG, SYNC, BG))
            assertEquals("S7N1_O02 ON_STOP: reported once more", 3, failures)
            registry.onSyncAlerts()
            process.send(Lifecycle.Event.ON_START)
            assertOthers("S7N1_O02 held sync at ON_START", listOf(FG, SYNC, BG, AUTH))
            assertEquals("S7N1_O02 held sync at ON_START: reported once more", 4, failures)
        }
    }

    // ---- N1-P part 1: deliverPush ----

    @Test
    fun `S7N1_P01 deliverPush resolves and hands over only an exact sync_alerts or rate_alert with the gate open`() {
        assertEquals("S7N1_P01 premise: the raw sync type", "sync_alerts", AlertFamilyRefresherRegistry.SYNC_ALERTS)
        assertEquals("S7N1_P01 premise: the raw bank alert type", "rate_alert", AlertFamilyRefresherRegistry.RATE_ALERT)
        val types = listOf(
            "sync_alerts", "rate_alert", "source_rate_alert", "comparison_alert", "unknown", "",
            "SYNC_ALERTS", " sync_alerts", "sync_alerts ", "sync-alerts", "sync_alert", "sync_alerts_v2",
            "RATE_ALERT", "rate_alert ", "rate_alerts"
        )
        val admittedCombinations = mutableListOf<String>()
        for (type in types) {
            for (gateOpen in listOf(true, false)) {
                withHarness(Harness(FakeLifecycle(Lifecycle.State.STARTED))) {
                    registry.start()
                    settle()
                    var resolves = 0
                    var reports = 0
                    AlertFamilyRefresherRegistry.deliverPush(type, "7", "false", gateOpen, reportFailure = { reports++ }) {
                        resolves++
                        registry
                    }
                    val admitted = gateOpen && (type == "sync_alerts" || type == "rate_alert")
                    if (admitted) admittedCombinations += type
                    val n = if (admitted) 1 else 0
                    val label = "S7N1_P01 type='$type' gateOpen=$gateOpen"
                    assertEquals("$label: resolve", n, resolves)
                    assertEquals("$label: onSyncAlerts or onBankPush reached (main post)", n, posts)
                    val handedOver = when {
                        !admitted -> listOf(FG)
                        type == "sync_alerts" -> listOf(FG, SYNC)
                        else -> listOf(FG, bank(7, "false"))
                    }
                    assertEach("$label: adapters (sync: onSync only; rate_alert: onBankPush only)", handedOver)
                    assertEquals("$label: no delivery failure", 0, reports)
                    assertEquals("$label: no registry failure", 0, failures)
                }
            }
        }
        assertEquals("S7N1_P01 exactly the two admitted combinations", listOf("sync_alerts", "rate_alert"), admittedCombinations)
    }

    @Test
    fun `S7N1_P02 a resolve that throws is reported once and deliverPush returns normally`() {
        for (type in listOf("sync_alerts", "rate_alert")) {
            var resolves = 0
            var reports = 0
            AlertFamilyRefresherRegistry.deliverPush(type, null, null, true, reportFailure = { reports++ }) {
                resolves++
                throw IllegalStateException("provider failed")
            }
            assertEquals("S7N1_P02 type='$type' premise: resolve was called", 1, resolves)
            assertEquals("S7N1_P02 type='$type' reported once", 1, reports)
        }
        for ((type, gateOpen) in listOf(
            "sync_alerts" to false, "rate_alert" to false, "source_rate_alert" to true, "" to true
        )) {
            var notAdmittedResolves = 0
            var notAdmittedReports = 0
            AlertFamilyRefresherRegistry.deliverPush(type, null, null, gateOpen, reportFailure = { notAdmittedReports++ }) {
                notAdmittedResolves++
                throw IllegalStateException("provider failed")
            }
            assertEquals("S7N1_P02 type='$type' gateOpen=$gateOpen: no resolve", 0, notAdmittedResolves)
            assertEquals("S7N1_P02 type='$type' gateOpen=$gateOpen: nothing reported", 0, notAdmittedReports)
        }
    }

    @Test
    fun `S7N1_P03 an onSyncAlerts that throws is reported once and deliverPush returns normally`() {
        withHarness(Harness(FakeLifecycle(Lifecycle.State.STARTED), mainMode = MainMode.THROWING)) {
            registry.start()
            settle()
            assertTrue("S7N1_P03 premise: onSyncAlerts throws here", runCatching { registry.onSyncAlerts() }.isFailure)
            val postsBefore = posts
            var resolves = 0
            var reports = 0
            AlertFamilyRefresherRegistry.deliverPush("sync_alerts", null, null, true, reportFailure = { reports++ }) {
                resolves++
                registry
            }
            assertEquals("S7N1_P03 resolve was called", 1, resolves)
            assertEquals("S7N1_P03 onSyncAlerts was reached", postsBefore + 1, posts)
            assertEquals("S7N1_P03 reported once by deliverPush", 1, reports)
            assertEquals("S7N1_P03 not reported a second time by the registry", 0, failures)
            assertEach("S7N1_P03 no adapter sync", listOf(FG))
        }
    }

    private companion object {
        const val FG = "onForeground(false)"
        const val AUTH = "onForeground(true)"
        const val BG = "onBackground()"
        const val SYNC = "onSync()"

        /** One onBankPush call; isRepeat is quoted so the raw string "null" and an absent value stay apart. */
        fun bank(id: Int?, isRepeat: String?) = "onBankPush($id, ${isRepeat?.let { "\"$it\"" } ?: "null"})"

        val ALICE = AuthIdentityFence("user-a", 1)
        val ALICE_RELOGIN = AuthIdentityFence("user-a", 2)
        val BOB = AuthIdentityFence("user-b", 1)

        fun confirmed(who: AuthIdentityFence) =
            OwnedPremiumAccess(who.uid, who.authGeneration, PremiumAccessState.PremiumConfirmed)

        fun access(who: AuthIdentityFence, state: PremiumAccessState) = OwnedPremiumAccess(who.uid, who.authGeneration, state)
    }
}
