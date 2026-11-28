package com.jay.fxi.data.alerts

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.jay.fxi.data.auth.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.domain.model.*
import com.jay.fxi.service.*
import com.jay.fxi.ui.alert.ComparisonSectionMount
import com.jay.fxi.ui.alert.OwnerComparisonSectionSource
import java.lang.reflect.Proxy
import javax.inject.Provider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class S9ComparisonRegistryContractTest {
    private val a1 = AuthIdentityFence("user-a", 1L)
    private val stamp = Instant.parse("2026-10-01T01:00:00Z")
    private fun row(id: Int, tab: String = "usd", kind: String = "absolute"): ComparisonAlertSetting {
        val tether = tab == "tether"
        val asset = if (tether) "usdt-krw" else "$tab-krw"
        return ComparisonAlertSetting(id, a1.uid, tab, if (tether) "bithumb" else "kb", asset,
            if (tether && kind == "absolute") "upbit" else "hana", if (kind == "signed") "usd-krw" else asset,
            kind, "gte", 4.25, true, false, 60, null, Instant.fromEpochMilliseconds(stamp.toEpochMilliseconds() + (id % 7) * 1000L), stamp)
    }

    private fun golden(name: String): Map<String, String> {
        val input = checkNotNull(javaClass.getResource("/contracts/v2/fcm/$name.json")).readText()
        return Json.parseToJsonElement(input).jsonObject.getValue("data").jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    private class Process : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    private class Feed<T>(private val now: () -> Long) {
        val starts = mutableListOf<Long>()
        val calls = mutableListOf<CompletableDeferred<List<T>>>()
        var inflight = 0
        var holdCancelledRead = false
        var peak = 0
        suspend fun read(): AuthBoundResult<List<T>> {
            starts += now()
            inflight++
            peak = maxOf(peak, inflight)
            try {
                val response = CompletableDeferred<List<T>>().also { calls += it }
                val rows = if (holdCancelledRead) withContext(NonCancellable) { response.await() } else response.await()
                return AuthBoundResult({}, Result.success(rows))
            } finally {
                inflight--
            }
        }
        fun answer(rows: List<T>) { assertTrue(calls.last().complete(rows)) }
    }

    private inner class Rig(val test: TestScope) {
        var identity: AuthIdentityFence = a1
        val premium = MutableStateFlow(confirmed(a1))
        val grant = TopicGrantToken(11L)
        val snapshot = TopicAccessSnapshot.INITIAL.copy(revision = 1L,
            facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet()))
        val access = AlertSettingsAccess({ identity }, { TopicSessionFence(identity, "epoch", grant) },
            SnapshotTopicUseAuthority { snapshot }, { premium.value }, { true },
            GraphProtectedAdmission({ identity }, DeletionAdmissionStore()))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val now = { test.testScheduler.currentTime }
        val floor = AlertRateLimitFloor(now)
        val banks = Feed<AlertSetting>(now)
        val sources = Feed<SourceAlertSetting>(now)
        val comparisons = Feed<ComparisonAlertSetting>(now)
        val bank = BankAlertSettingsOwner({ _, _ -> banks.read() }, access, floor, AlertRetryPolicy(), now, { 0.0 },
            scope, { delay(it) }, premium, MutableStateFlow(0L), MutableStateFlow(false),
            { _, _, _, _ -> error("toggle") }, { _, _, _ -> error("delete") },
            { _, _, _ -> error("create") }, { _, _, _, _ -> error("edit") })
        val source = SourceAlertSettingsOwner({ _, _ -> sources.read() }, access, floor, AlertRetryPolicy(), now, { 0.0 },
            scope, { delay(it) }, premium, MutableStateFlow(0L),
            { _, _, _ -> error("create") }, { _, _, _, _ -> error("edit") }, { _, _, _ -> error("delete") })
        val writerCalls = IntArray(3)
        val comparison = ComparisonAlertSettingsOwner({ _, _ -> comparisons.read() }, access, { identity }, premium,
            MutableStateFlow(0L), scope, { delay(it) }, { 0.0 },
                post = { _, _, _ -> writerCalls[0]++; error("POST") },
                put = { _, _, _, _ -> writerCalls[1]++; error("PUT") },
                remove = { _, _, _ -> writerCalls[2]++; error("DELETE") })
        val process = Process().also { it.lifecycle.currentState = Lifecycle.State.STARTED }
        val callbacks = mutableListOf<Pair<Int, String>>()
        val enteredAt = List(3) { mutableListOf<Long>() }
        val publishedAt = List(3) { mutableListOf<Long>() }
        val versions = List(3) { mutableListOf<List<Int>>() }
        init {
            val monitor = CoroutineScope(scope.coroutineContext + UnconfinedTestDispatcher(test.testScheduler))
            monitor.launch { bank.state.collect { s -> s.settings?.map { it.id }?.let { if (s.phase == AlertLoadPhase.Ready) { versions[0].add(it); publishedAt[0] += now() } } } }
            monitor.launch { source.state.collect { s -> s.settings?.map { it.id }?.let { if (s.phase == AlertLoadPhase.Ready) { versions[1].add(it); publishedAt[1] += now() } } } }
            monitor.launch { comparison.state.collect { s -> s.adopted?.rows?.map { it.id }?.let { if (s.phase == AlertLoadPhase.Ready) { versions[2].add(it); publishedAt[2] += now() } } } }
        }
        var supplierFailure = -1
        var adapterFailure = -1
        var failures = 0
        var resolves = 0
        val queue = ArrayDeque<() -> Unit>()
        fun traced(index: Int, target: AlertLifecycleAdapter) = object : AlertLifecycleAdapter {
            fun call(name: String, action: () -> Unit) {
                enteredAt[index] += now()
                callbacks += index to name
                if (index == adapterFailure) error("adapter failure")
                action()
            }
            override fun onForeground(authoritative: Boolean) = call(if (authoritative) "AUTH" else "FG") { target.onForeground(authoritative) }
            override fun onBackground() = call("BG", target::onBackground)
            override fun onSync() = call("SYNC", target::onSync)
        }
        val targets = listOf(bank, source, comparison)
        fun registry(families: List<() -> AlertLifecycleAdapter>, lifecycle: () -> Lifecycle = { process.lifecycle }) =
            AlertFamilyRefresherRegistry(families, lifecycle, { queue.addLast(it) }, premium, { identity }, scope,
                reportFailure = { failures++ }, captureSourceUse = { access.acquire() })
        val registry = registry(targets.mapIndexed { i, owner -> { if (i == supplierFailure) error("supplier"); traced(i, owner) } })
        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst()()
            test.runCurrent()
        }
        fun start() { registry.start(); drain() }
        fun bg() { process.lifecycle.currentState = Lifecycle.State.CREATED; drain() }
        fun fg() { process.lifecycle.currentState = Lifecycle.State.STARTED; drain() }
        fun sync() = registry.onSyncAlerts()
        fun counts() = listOf(banks.calls.size, sources.calls.size, comparisons.calls.size)
        fun inflight() = listOf(banks.inflight, sources.inflight, comparisons.inflight)
        fun answerBankSource(v: Int) {
            banks.answer(listOf(AlertSetting(v, identity.uid, "hana", "usd-krw", AlertCondition.ABOVE, 1401.0, true, false, stamp, stamp, null, null)))
            sources.answer(listOf(SourceAlertSetting(v+10, identity.uid, "upbit", "usdt-krw", AlertCondition.ABOVE, 1402.0, true, false, null, stamp, stamp, null)))
        }
        fun answerComparison(rows: List<ComparisonAlertSetting>) = comparisons.answer(rows)
        fun answer(v: Int) {
            answerBankSource(v)
            answerComparison(listOf(row(v+20, "tether", "signed"), row(v+30, "tether"), row(90).copy(leftSource = "krx"),
                row(91).copy(userId = "other"), row(92), row(92).copy(userId = "other")).map {
                    if (it.userId == a1.uid) it.copy(userId = identity.uid) else it
                })
            drain()
        }
    }

    private fun confirmed(id: AuthIdentityFence) = OwnedPremiumAccess(id.uid, id.authGeneration, PremiumAccessState.PremiumConfirmed)
    private fun TestScope.rig(block: Rig.() -> Unit) {
        val rig = Rig(this)
        try {
            rig.block()
        } finally {
            rig.scope.cancel()
            (rig.banks.calls + rig.sources.calls + rig.comparisons.calls).forEach { it.cancel() }
            runCurrent()
            assertEquals(listOf(0, 0, 0), rig.writerCalls.toList())
        }
    }

    private inline fun <reified T> unused(): T = T::class.java.cast(
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> error("unexpected ${method.name}") })

    @Test fun B01_injectResolvesInOrderBeforeStartedReplay() = runTest {
        rig {
            val orders = AccessOrderSequence()
            val coordinator = PremiumAccessCoordinator(source = unused(), store = unused(), userPurger = unused(),
                capabilityPurger = unused(), scope = scope, clock = { now() }, liveFence = { identity }, orders = orders)
            val tokens = AuthTokenProvider(object : AuthTokenSource {
                override fun currentIdentity() = AuthIdentity(identity.uid, identity.authGeneration)
                override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean) = "test"
            }, orders = orders)
            val order = mutableListOf<String>()
            val inject = AlertFamilyRefresherRegistry(Provider { order += "bank"; bank }, Provider { order += "source"; source },
                Provider { order += "comparison"; comparison }, coordinator, tokens, Provider { error("unused hook") })
            @Suppress("UNCHECKED_CAST")
            val families = inject.javaClass.getDeclaredField("families").apply { isAccessible = true }.get(inject) as List<() -> AlertLifecycleAdapter>
            assertTrue(order.isEmpty())
            val tested = registry(families) { order += "observer"; process.lifecycle }
            order += "start"
            tested.start(); tested.start(); drain()
            assertEquals(listOf("start", "bank", "source", "comparison", "observer"), order)
            assertEquals(1, process.lifecycle.observerCount)
            repeat(2) { ComparisonSectionMount({ true }, {}).runShown(OwnerComparisonSectionSource(comparison)::ensure) }
            drain()
            assertEquals(listOf(1, 1, 1), counts())
            @Suppress("UNCHECKED_CAST")
            val adapters = tested.javaClass.getDeclaredField("adapters").apply { isAccessible = true }.get(tested) as List<AlertLifecycleAdapter>
            assertEquals(3, adapters.size)
            targets.zip(adapters).forEach { (expected, actual) -> assertSame(expected, actual) }
        }
    }

    @Test fun B02_coldReadAndBothMountsShareOneFlight() = runTest {
        rig {
            val tested = registry(targets.map { { it } })
            tested.start(); drain()
            val first = OwnerComparisonSectionSource(comparison)
            val second = OwnerComparisonSectionSource(comparison)
            assertSame(comparison.state, first.state)
            assertSame(first.state, second.state)
            listOf(first, second).forEach { ComparisonSectionMount({ true }, {}).runShown(it::ensure) }
            drain(); assertEquals(listOf(1, 1, 1), counts()); answer(1)
            first.ensure(); second.ensure(); drain()
            assertEquals(listOf(21), first.state.value.adopted?.project("tether", "signed")?.map { it.id })
            assertEquals(listOf(31), second.state.value.adopted?.project("tether", "absolute")?.map { it.id })
            assertEquals(1, comparisons.peak); assertEquals(0, comparisons.inflight)
            assertEquals(listOf(1, 1, 1), counts())
        }
    }

    @Test fun B03_goldenSyncPublishesAllThreeNewVersions() = runTest {
        rig {
            start(); answer(1)
            val coldAt = now()
            test.advanceTimeBy(1); drain()
            val syncAt = now()
            val sync = golden("sync-alerts")
            val emits = mutableListOf<AlertEvent>()
            var posts = 0
            val routed = S86Entry.push(sync, true, { resolves++; registry }, { emits += it }, { posts++ }) as AlertPushRoute
            assertFalse(routed.showBanner)
            assertEquals(listOf(AlertEvent.RefreshNeeded), emits)
            assertEquals(0, posts)
            drain(); assertEquals(1, resolves); assertEquals(listOf(2, 2, 2), counts())
            answer(2)
            val expectedTimes = List(3) { listOf(coldAt, syncAt) }
            assertEquals(expectedTimes, listOf(banks.starts, sources.starts, comparisons.starts))
            assertEquals(expectedTimes, enteredAt)
            assertEquals(expectedTimes, publishedAt)
            assertEquals((0..2).map { it to "SYNC" }, callbacks.takeLast(3))
            assertEquals(listOf(listOf(listOf(1), listOf(2)), listOf(listOf(11), listOf(12)),
                listOf(listOf(21, 31), listOf(22, 32))), versions)
            assertEquals(listOf(2, 2, 2), counts())
        }
    }

    @Test fun B04_backgroundCoalescesAndOrdinaryComparisonDoesNotExpire() = runTest {
        for (age in listOf(100L, 29_999L, 30_000L)) rig {
            start(); answer(1); bg()
            val before = listOf(bank.state.value, source.state.value, comparison.state.value)
            if (age == 100L) { sync(); sync(); drain() }
            test.advanceTimeBy(age); drain()
            assertEquals(listOf(1, 1, 1), counts())
            assertEquals(before, listOf(bank.state.value, source.state.value, comparison.state.value))
            fg()
            assertEquals(if (age == 100L) listOf(2, 2, 2) else if (age < 30_000L) listOf(1, 1, 1) else listOf(2, 2, 1), counts())
            assertEquals(if (age == 100L) 3 else 0, callbacks.count { it.second == "AUTH" })
            if (age == 100L) {
                answer(2); assertEquals(listOf(listOf(2), listOf(12), listOf(22, 32)), versions.map { it.last() })
                bg(); fg()
                assertEquals(listOf(2, 2, 2), counts()); assertEquals((0..2).map { it to "FG" }, callbacks.takeLast(3))
            }
        }
    }

    @Test fun B05_pendingNeverCrossesAnAccessChange() = runTest {
        for (change in listOf("user", "generation", "regrant", "roundtrip", "live", "free")) {
            fun runCase(withSync: Boolean): List<Int> {
                var result = emptyList<Int>()
                rig {
                    start(); answer(1); bg()
                    if (withSync) { sync(); drain() }
                    val other = if (change == "generation") AuthIdentityFence(a1.uid, 2) else AuthIdentityFence("user-b", 2)
                    when (change) {
                        "regrant", "free" -> {
                            premium.value = confirmed(a1).copy(state = PremiumAccessState.FreeConfirmed); drain()
                            if (change == "regrant") premium.value = confirmed(a1)
                        }
                        "live" -> identity = other
                        else -> {
                            identity = other; premium.value = confirmed(other); drain()
                            if (change == "roundtrip") { identity = a1; premium.value = confirmed(a1) }
                        }
                    }
                    fg()
                    assertFalse(callbacks.any { it.second == "AUTH" })
                    result = counts()
                    if (change == "free" || change == "live") assertEquals(listOf(1, 1, 1), result)
                    if (result == listOf(2, 2, 2)) {
                        answer(2); assertEquals(identity, comparison.state.value.identity)
                        assertEquals(listOf(22, 32), comparison.state.value.adopted?.rows?.map { it.id })
                    }
                }
                return result
            }
            assertEquals(runCase(false), runCase(true))
        }
    }

    @Test fun B05_liveFenceWinsBeforeThePremiumCollectorRuns() = runTest {
        for (next in listOf(AuthIdentityFence(a1.uid, 2), AuthIdentityFence("user-b", 2))) rig {
            start(); answer(1); bg(); sync(); drain()
            identity = next
            premium.value = confirmed(next)
            process.lifecycle.currentState = Lifecycle.State.STARTED
            assertEquals((0..2).map { it to "FG" }, callbacks.takeLast(3))
            drain(); assertEquals(listOf(2, 2, 2), counts())
            answer(2)
            assertEquals(next, comparison.state.value.identity)
        }
    }

    @Test fun B05_lateOldGenerationResponseCannotReplaceTheNewSnapshot() = runTest {
        rig {
            comparisons.holdCancelledRead = true
            try {
                start(); answer(1)
                sync(); drain()
                val delayed = comparisons.calls.last()
                bg(); sync(); drain()
                identity = AuthIdentityFence(a1.uid, 2)
                premium.value = confirmed(identity); drain(); fg()
                delayed.complete(listOf(row(900))); drain()
                assertFalse(versions[2].any { 900 in it })
                assertFalse(callbacks.any { it.second == "AUTH" })
                answer(2)
                assertEquals(identity, comparison.state.value.identity)
                assertEquals(listOf(22, 32), versions[2].last())
            } finally {
                comparisons.calls.forEach { it.complete(emptyList()) }
            }
        }
    }

    @Test fun B06_liveMainAdmissionAndPerFamilyFailureBoundaries() = runTest {
        for (allow in listOf(false, true)) rig {
            start(); answer(1)
            if (allow) premium.value = confirmed(a1).copy(state = PremiumAccessState.FreeConfirmed)
            sync()
            assertFalse(callbacks.any { it.second == "SYNC" })
            premium.value = if (allow) confirmed(a1) else confirmed(a1).copy(state = PremiumAccessState.FreeConfirmed)
            drain(); assertEquals(if (allow) 3 else 0, callbacks.count { it.second == "SYNC" })
        }
        for (becomesCurrent in listOf(false, true)) rig {
            start(); answer(1)
            val next = AuthIdentityFence(a1.uid, 2)
            if (becomesCurrent) premium.value = confirmed(next)
            sync()
            assertEquals(0, callbacks.count { it.second == "SYNC" })
            identity = next
            drain()
            assertEquals(if (becomesCurrent) 3 else 0, callbacks.count { it.second == "SYNC" })
        }
        for (index in listOf(1, 2)) rig {
            supplierFailure = index; start()
            assertEquals(1, failures); assertEquals(1, process.lifecycle.observerCount)
            assertEquals((0..2).filter { it != index }, callbacks.map { it.first })
        }
        for (index in listOf(1, 2)) rig {
            adapterFailure = index; start(); sync(); drain(); bg(); fg()
            assertEquals(4, failures)
            for (family in 0..2) assertEquals(listOf("FG", "SYNC", "BG", "FG"), callbacks.filter { it.first == family }.map { it.second })
        }
    }

    private class Extras(private val data: MutableMap<String, String>) : AlertTapExtras {
        override fun kind(key: String) = if (key in data) AlertTapExtraKind.STRING else AlertTapExtraKind.MISSING
        override fun readString(key: String) = data[key]
        override fun readInt(key: String, default: Int) = default
        override fun remove(key: String) { data.remove(key) }
    }

    @Test fun B07_individualPushAndOffTapPreserveTheComparisonSnapshot() = runTest {
        rig {
            var bankDeliveries = 0
            val bankProbe = object : AlertFamilyAdapter by bank {
                override fun onBankPush(id: Int?, isRepeat: String?) {
                    bankDeliveries++
                    bank.onBankPush(id, isRepeat)
                }
            }
            val tested = registry(listOf({ bankProbe }, { source }, { comparison }))
            tested.start(); drain(); answer(1)
            assertFalse((comparison as AlertLifecycleAdapter) is AlertFamilyAdapter)
            val before = comparison.state.value
            fun push(data: Map<String, String>, gate: Boolean) {
                S95Entry.push(data, gate, { resolves++; tested }, {}, {})
            }
            fun tap(data: Map<String, String>, gate: Boolean) {
                S95Entry.tap(Extras(data.toMutableMap()), gate) { resolves++; tested }
            }
            val names = listOf("rate-alert-once", "rate-alert-repeat", "source-alert-once", "source-alert-repeat-krx",
                "comparison-alert-absolute-once", "comparison-alert-signed-repeat")
            val fixtures = names.map(::golden) + listOf(golden("source-alert-once") + ("is_repeat" to "true"),
                golden("comparison-alert-absolute-once") + ("setting_id" to "invalid"))
            for (data in fixtures) {
                for (deliver in listOf(::push, ::tap)) {
                    val n = resolves
                    deliver(data, false); drain(); assertEquals(n, resolves)
                    val cmp = data["type"] == "comparison_alert"
                    val accept = !cmp && data["asset"] != "usd-krw-futures" && data["source"] != "krx"
                    val previous = counts()
                    val bankBefore = bankDeliveries
                    deliver(data, true); drain()
                    assertEquals(n + if (accept) 1 else 0, resolves)
                    assertEquals(bankBefore + if (data["type"] == "rate_alert") 1 else 0, bankDeliveries)
                    assertEquals(previous[1] + if (accept && data["type"] == "source_rate_alert") 1 else 0, sources.calls.size)
                    if (cmp) {
                        val route = checkNotNull(AlertPushRouting.route(data["type"], data["setting_id"]))
                        assertTrue(route.showBanner)
                        val id = data["setting_id"]?.toIntOrNull()
                        assertEquals(if (id == null) AlertEvent.FamilyRefreshNeeded(AlertFamily.COMPARISON)
                            else AlertEvent.SettingTriggered(id, AlertFamily.COMPARISON), route.event)
                        assertEquals(previous, counts())
                    }
                    if (banks.calls.size > previous[0]) banks.answer(emptyList())
                    if (sources.calls.size > previous[1]) sources.answer(emptyList())
                    drain()
                    assertSame(before, comparison.state.value); assertEquals(1, comparisons.calls.size)
                }
            }
        }
    }

    @Test fun B08_sameIdentityAdoptsEditedRowsInServerOrder() = runTest {
        rig {
            val cold = startReplacement("B08")
            val replacement = listOf(cold[1].copy(threshold = 6.5, isEnabled = false),
                row(45, "tether", "signed"), cold[0])
            val adopted = finishReplacement("B08", replacement)
            assertEquals("B08 유지 ID 편집과 서버 순서 채택", replacement, adopted.rows)
            verifyQuiet("B08")
        }
    }

    @Test fun B09_sameIdentityAdoptsEmptyReady() = runTest {
        rig {
            startReplacement("B09")
            val adopted = finishReplacement("B09", emptyList())
            assertEquals("B09 비어 있는 정본 Ready 채택", List(3) { emptyList<ComparisonAlertSetting>() },
                listOf(adopted.rows, adopted.project("tether", "absolute"), adopted.project("tether", "signed")))
            verifyQuiet("B09")
        }
    }

    private fun Rig.startReplacement(case: String): List<ComparisonAlertSetting> {
        val cold = listOf(row(21, "tether", "signed"), row(31, "tether"), row(41, "tether", "signed"))
        val one = listOf(1, 1, 1)
        val two = listOf(2, 2, 2)
        start()
        assertEquals("$case 세 family GET 각 1 증가", one to one, counts() to inflight())
        answerBankSource(1)
        answerComparison(cold)
        drain()
        val before = comparison.state.value
        test.advanceTimeBy(1)
        S86Entry.push(golden("sync-alerts"), true, { resolves++; registry }, {}, {})
        drain()
        assertEquals("$case 세 family GET 각 1 증가", Triple(two, one, 1), Triple(counts(), inflight(), resolves))

        fun sample() = comparison.state.value.let { Triple(it.identity, it.phase, it.adopted?.rows) }
        val barrier = sample()
        answerBankSource(2)
        drain()
        val expected = Triple(a1, AlertLoadPhase.Ready, cold)
        assertEquals("$case 응답 전 기존 행 유지",
            listOf(expected, expected, expected, listOf(0, 0, 1), listOf(2), listOf(12)),
            listOf(Triple(before.identity, before.phase, before.adopted?.rows), barrier, sample(), inflight(),
                bank.state.value.settings?.map { it.id }, source.state.value.settings?.map { it.id }))
        return cold
    }

    private fun Rig.finishReplacement(case: String, rows: List<ComparisonAlertSetting>): ComparisonAlertRowPolicy.Adopted {
        answerComparison(rows)
        drain()
        val state = comparison.state.value
        assertEquals("$case 동일 신원 Ready", Triple(a1, AlertLoadPhase.Ready, true),
            Triple(state.identity, state.phase, state.adopted != null))
        return requireNotNull(state.adopted)
    }

    private fun Rig.verifyQuiet(case: String) {
        test.advanceTimeBy(60_000)
        drain()
        val zero = listOf(0, 0, 0)
        assertEquals("$case 60초 추가 GET·미결 0", Triple(listOf(2, 2, 2), zero, zero),
            Triple(counts(), inflight(), writerCalls.toList()))
    }
}
