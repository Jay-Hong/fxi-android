package com.jay.fxi.data.alerts

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S8-3 registry with two families (`R4c/S8/s8_3_contract` §1.3, §2.4): a bank family ([AlertFamilyAdapter]) and a source family
 * ([AlertLifecycleAdapter] only). Each is resolved once however often start runs, one observer serves both, the lifecycle and
 * the silent sync reach each once, a held sync becomes one authoritative foreground for each, an adapter that throws is
 * reported and does not stop the other, and the bank's individual push and tap reach the bank family only. The production
 * registry lists the bank family alone (`AlertN1StructureTest` S11, unchanged); these two-family rows are the injected
 * configuration S8-4 makes the production one. The last row runs the real bank and source owners behind one sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class S8RegistryTwoFamilyContractTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val bob = AuthIdentityFence("user-b", 1)
    private fun confirmed(id: AuthIdentityFence) = OwnedPremiumAccess(id.uid, id.authGeneration, PremiumAccessState.PremiumConfirmed)

    private class Process : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private open class Recording(val name: String, var throwOn: String? = null) : AlertLifecycleAdapter {
        val calls = mutableListOf<String>()
        protected fun record(call: String) {
            calls += call
            if (call == throwOn) throw IllegalStateException("$name $call")
        }
        override fun onForeground(authoritative: Boolean) = record(if (authoritative) "AUTH" else "FG")
        override fun onBackground() = record("BG")
        override fun onSync() = record("SYNC")
    }

    private class RecordingBank : Recording("bank"), AlertFamilyAdapter {
        override fun onBankPush(id: Int?, isRepeat: String?) = record("push($id, $isRepeat)")
    }

    private inner class Harness(initial: Lifecycle.State) {
        val process = Process().also { it.registry.currentState = initial }
        val bank = RecordingBank()
        val source = Recording("source")
        val resolves = mutableMapOf("bank" to 0, "source" to 0)
        val premium = MutableStateFlow(confirmed(alice))
        var live: AuthIdentityFence? = alice
        val scope = TestScope(StandardTestDispatcher())
        var failures = 0
        var posts = 0
        var registrations = 0
        val registry = AlertFamilyRefresherRegistry(
            families = listOf(
                { resolves["bank"] = resolves.getValue("bank") + 1; bank },
                { resolves["source"] = resolves.getValue("source") + 1; source }
            ),
            lifecycle = {
                registrations++
                process.registry
            },
            postToMain = { action -> posts++; action(); Unit },
            premium = premium,
            liveIdentity = { live },
            scope = scope,
            reportFailure = { failures++ }
        )

        fun settle() = scope.runCurrent()
        fun toForeground() { process.registry.currentState = Lifecycle.State.STARTED; settle() }
        fun toBackground() { process.registry.currentState = Lifecycle.State.CREATED; settle() }
        fun started(): Harness = also { registry.start(); settle() }
        fun close() = scope.cancel()
    }

    private fun withHarness(h: Harness, block: Harness.() -> Unit) {
        try {
            h.block()
        } finally {
            h.close()
        }
    }

    @Test fun `S8_3_R01 each family is resolved once and one observer serves both`() {
        withHarness(Harness(Lifecycle.State.STARTED)) {
            registry.start(); registry.start(); settle()
            assertEquals("each supplier resolved once", mapOf("bank" to 1, "source" to 1), resolves)
            assertEquals("one lifecycle read for one observer", 1, registrations)
            assertEquals("one observer on the process lifecycle", 1, process.registry.observerCount)
            assertEquals("bank: the replayed foreground once", listOf("FG"), bank.calls)
            assertEquals("source: the replayed foreground once", listOf("FG"), source.calls)
            toBackground(); toForeground()
            assertEquals("bank: one transition each", listOf("FG", "BG", "FG"), bank.calls)
            assertEquals("source: one transition each", listOf("FG", "BG", "FG"), source.calls)
            assertEquals("nothing reported", 0, failures)
        }
    }

    @Test fun `S8_3_R02 a sync reaches each family once in the foreground and is held once for the next foreground`() {
        withHarness(Harness(Lifecycle.State.STARTED).started()) {
            registry.onSyncAlerts(); settle()
            assertEquals("one main post", 1, posts)
            assertEquals("bank onSync once", listOf("FG", "SYNC"), bank.calls)
            assertEquals("source onSync once", listOf("FG", "SYNC"), source.calls)
            toBackground()
            registry.onSyncAlerts(); registry.onSyncAlerts(); settle()
            assertEquals("held in the background: nothing reaches bank", listOf("FG", "SYNC", "BG"), bank.calls)
            assertEquals("nor source", listOf("FG", "SYNC", "BG"), source.calls)
            toForeground()
            assertEquals("bank: one authoritative foreground", listOf("FG", "SYNC", "BG", "AUTH"), bank.calls)
            assertEquals("source: one authoritative foreground", listOf("FG", "SYNC", "BG", "AUTH"), source.calls)
            toBackground(); toForeground()
            assertEquals("the hold is spent: a plain foreground next", "FG", bank.calls.last())
            assertEquals("for source too", "FG", source.calls.last())
        }
    }

    @Test fun `S8_3_R03 a held sync is dropped when the account changes or loses premium`() {
        for (change in listOf<Harness.() -> Unit>(
            { live = bob; premium.value = confirmed(bob) },
            { premium.value = OwnedPremiumAccess(alice.uid, alice.authGeneration, PremiumAccessState.FreeConfirmed) }
        )) {
            withHarness(Harness(Lifecycle.State.CREATED).started()) {
                registry.onSyncAlerts(); settle()
                change(); settle()
                toForeground()
                assertEquals("bank: a plain foreground", listOf("BG", "FG"), bank.calls)
                assertEquals("source: a plain foreground", listOf("BG", "FG"), source.calls)
            }
        }
    }

    @Test fun `S8_3_R04 the bank's push and tap reach the bank family only`() {
        withHarness(Harness(Lifecycle.State.STARTED).started()) {
            registry.onBankPush(7, "false")
            AlertFamilyRefresherRegistry.deliverPush("rate_alert", "8", "true", gateOpen = true) { registry }
            AlertFamilyRefresherRegistry.deliverTap(AlertEvent.SettingTriggered(9), "x", gateOpen = true) { registry }
            AlertFamilyRefresherRegistry.deliverTap(AlertEvent.RefreshNeeded, null, gateOpen = true) { registry }
            AlertFamilyRefresherRegistry.deliverTap(AlertEvent.SettingTriggered(10, AlertFamily.SOURCE), "false", gateOpen = true) { registry }
            settle()
            assertEquals("bank: each bank delivery once", listOf("FG", "push(7, false)", "push(8, true)", "push(9, x)", "push(null, null)"), bank.calls)
            assertEquals("source: none of them", listOf("FG"), source.calls)
            assertEquals("nothing reported", 0, failures)
        }
    }

    @Test fun `S8_3_R05 a family that throws is reported and does not stop the other`() {
        withHarness(Harness(Lifecycle.State.STARTED).started()) {
            bank.throwOn = "SYNC"
            registry.onSyncAlerts(); settle()
            assertEquals("the bank, dispatched first, threw on the sync", listOf("FG", "SYNC"), bank.calls)
            assertEquals("the source after it still got the sync once", listOf("FG", "SYNC"), source.calls)
            assertEquals("reported once", 1, failures)
            bank.throwOn = "BG"
            toBackground()
            assertEquals("the source still got the background once", listOf("FG", "SYNC", "BG"), source.calls)
            assertEquals("reported once more", 2, failures)
            source.throwOn = "FG"
            bank.throwOn = null
            toForeground()
            assertEquals("a source throwing last is reported too", 3, failures)
            assertEquals("and the bank got the foreground", listOf("FG", "SYNC", "BG", "FG"), bank.calls)
            bank.throwOn = "push(7, false)"
            registry.onBankPush(7, "false"); settle()
            assertEquals("the bank push threw and was reported", 4, failures)
            assertEquals("source untouched by it", listOf("FG", "SYNC", "BG", "FG"), source.calls)
        }
    }

    // ---- the real owners behind one sync ----

    private val stamp = Instant.parse("2026-11-21T01:00:00Z")
    private val grant = TopicGrantToken(11)

    @Test fun `S8_3_R06 one admitted sync reaches the real bank and source owners and each sends one GET`() = runTest {
        val identity: AuthIdentityFence = alice
        val fence = TopicSessionFence(alice, "epoch-1", grant)
        val snapshot = TopicAccessSnapshot.INITIAL.copy(
            revision = 1L, facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet()), userInvalidations = 0L
        )
        val premium = MutableStateFlow(confirmed(alice))
        val access = AlertSettingsAccess({ identity }, { fence }, SnapshotTopicUseAuthority { snapshot }, { premium.value }, { true },
            GraphProtectedAdmission({ identity }, DeletionAdmissionStore()))
        val floor = AlertRateLimitFloor({ testScheduler.currentTime })
        val job = SupervisorJob()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bankGets = mutableListOf<CompletableDeferred<AuthBoundResult<List<AlertSetting>>>>()
        val sourceGets = mutableListOf<CompletableDeferred<AuthBoundResult<List<SourceAlertSetting>>>>()
        val bank = BankAlertSettingsOwner(
            { _, _ -> CompletableDeferred<AuthBoundResult<List<AlertSetting>>>().also { bankGets += it }.await() },
            access, floor, AlertRetryPolicy(), { testScheduler.currentTime }, { 0.0 }, CoroutineScope(job + dispatcher), { delay(it) },
            premium, MutableStateFlow(0L), MutableStateFlow(false),
            { _, _, _, _ -> error("no toggle") }, { _, _, _ -> error("no delete") }, { _, _, _ -> error("no create") },
            { _, _, _, _ -> error("no edit") }
        )
        val source = SourceAlertSettingsOwner(
            { _, _ -> CompletableDeferred<AuthBoundResult<List<SourceAlertSetting>>>().also { sourceGets += it }.await() },
            access, floor, AlertRetryPolicy(), { testScheduler.currentTime }, { 0.0 }, CoroutineScope(job + dispatcher), { delay(it) },
            premium, MutableStateFlow(0L)
        )
        val lifecycleOnly: AlertLifecycleAdapter = source
        org.junit.Assert.assertFalse("the source owner is no bank-push family", lifecycleOnly is AlertFamilyAdapter)
        var familyResolves = 0
        var deliveryResolves = 0
        val process = Process().also { it.registry.currentState = Lifecycle.State.STARTED }
        val registry = AlertFamilyRefresherRegistry(
            families = listOf({ familyResolves++; bank }, { familyResolves++; source }),
            lifecycle = { process.registry },
            postToMain = { it(); Unit },
            premium = premium,
            liveIdentity = { identity },
            scope = CoroutineScope(job + dispatcher),
            reportFailure = { error("no failure expected") }
        )
        try {
            registry.start(); runCurrent()
            bankGets.single().complete(AuthBoundResult({}, Result.success(emptyList())))
            sourceGets.single().complete(AuthBoundResult({}, Result.success(emptyList())))
            runCurrent()
            assertEquals("premise: each owner loaded once at the start", listOf(1, 1), listOf(bankGets.size, sourceGets.size))
            AlertFamilyRefresherRegistry.deliverPush("sync_alerts", null, null, gateOpen = true) { deliveryResolves++; registry }
            runCurrent()
            assertEquals("one delivery resolve", 1, deliveryResolves)
            assertEquals("no family resolved again during the delivery", 2, familyResolves)
            assertEquals("bank: one more GET", 2, bankGets.size)
            assertEquals("source: one more GET", 2, sourceGets.size)
            bankGets.last().complete(AuthBoundResult({}, Result.success(listOf(
                AlertSetting(1, "user-a", "hana", "usd-krw", AlertCondition.ABOVE, 1401.0, true, false, stamp, stamp, null, null)))))
            sourceGets.last().complete(AuthBoundResult({}, Result.success(listOf(
                SourceAlertSetting(2, "user-a", "upbit", "usdt-krw", AlertCondition.ABOVE, 1402.0, true, false, null, stamp, stamp, null)))))
            runCurrent()
            assertEquals("bank adopted its answer", listOf(1), bank.state.value.settings?.map { it.id })
            assertEquals("source adopted its answer", listOf(2), source.state.value.settings?.map { it.id })
        } finally {
            job.cancel()
        }
    }
}
