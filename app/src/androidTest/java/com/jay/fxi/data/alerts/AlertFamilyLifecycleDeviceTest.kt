package com.jay.fxi.data.alerts

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.AccessFence
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.TopicGrantContext
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.di.NetworkModule
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit

/**
 * Claude-owned S7 N1 (`R4c/S7/s7n1` API r3 A-3): the registry → owner → client path under the real process lifecycle
 * (ProcessLifecycleOwner, driven by the activity's own transitions). The registry, the real owner, a real AlertSettingsAccess,
 * the real repository, client and AuthenticatedTransport are assembled with their test constructors; the access's release
 * admission and the transport's admission are the fixture's true suppliers, the auth, use and deletion boundaries are the real
 * ones, and the HTTP exchange ends at an interceptor with a canned answer: nothing leaves the device. The D24-OFF process starts
 * no registry of its own, so every transition reaches only the one assembled here. The admitted FCM service is not observed on
 * a device (an unverified limit, recorded); its hand-over is `FXiMessagingServiceSyncHookTest` (OFF),
 * `AlertFamilyRefresherRegistryContractTest` (deliverPush) and `AlertN1StructureTest` (the call after the emit). The
 * implementation thread reads but does not edit this file.
 */
class AlertFamilyLifecycleDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val a1 = AuthIdentityFence("A", 1L)
    private val fence = TopicSessionFence(a1, "e1", TopicGrantToken(1L))

    /** The issuer's published access for [fence]: it admits A's grant 1 in user epoch e1. */
    @Volatile private var snapshot: TopicAccessSnapshot = run {
        val binding = EntitlementsIdentity("A", 1L)
        val record = AccessFence("A", "e1", "K1")
        TopicAccessSnapshot.INITIAL.copy(
            revision = 100L,
            facts = TopicAccessFacts.NONE.copy(
                token = fence.grant,
                issuedFor = TopicGrantContext(binding, record, 10L),
                binding = binding,
                recordFence = record,
                decisionGeneration = 10L,
                tokenStanding = true,
                userBlocks = emptySet(),
                capabilityBlocks = emptySet()
            ),
            userInvalidations = 3L
        )
    }
    private val premium = MutableStateFlow(OwnedPremiumAccess("A", 1L, PremiumAccessState.PremiumConfirmed))
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val failures = AtomicInteger()
    private val scopes = mutableListOf<CoroutineScope>()
    private lateinit var owner: BankAlertSettingsOwner
    private lateinit var registry: AlertFamilyRefresherRegistry

    @After fun cancelScopes() {
        // A registry stays an observer of the process lifecycle; with its scopes gone it starts nothing in a later test.
        scopes.forEach { it.cancel() }
    }

    /** The assembly, built on Main as production builds it; nothing is started. */
    private fun assemble() {
        val source = object : AuthTokenSource {
            override fun currentIdentity() = AuthIdentity("A", 1L)
            override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean) = "credential"
        }
        val provider = AuthTokenProvider(source, orders = AccessOrderSequence())
        val http = OkHttpClient.Builder()
            .addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor { chain ->
                requests += "${chain.request().method} ${chain.request().url.encodedPath}"
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(SETTINGS_JSON.toResponseBody("application/json".toMediaType())).build()
            }
            .build()
        val wireJson = NetworkModule.provideWireJson()
        val retrofit = Retrofit.Builder().baseUrl("http://localhost/").client(http)
            .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build()
        val repository = AlertRepository(AuthenticatedApiClient(retrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }), wireJson))
        val access = AlertSettingsAccess(
            provider::currentIdentityFence,
            { fence },
            SnapshotTopicUseAuthority { snapshot },
            { premium.value },
            { true },
            GraphProtectedAdmission(provider::currentIdentityFence, DeletionAdmissionStore())
        )
        rule.runOnUiThread {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scopes += it }
            owner = BankAlertSettingsOwner(
                { o, admitted -> repository.getSettings(o, admitted) }, access, AlertRateLimitFloor(SystemClock::elapsedRealtime),
                AlertRetryPolicy(), SystemClock::elapsedRealtime, { 0.0 }, scope, { delay(it) },
                premium, MutableStateFlow(0L), MutableStateFlow(true)
            )
            registry = AlertFamilyRefresherRegistry(
                listOf({ owner }),
                { ProcessLifecycleOwner.get().lifecycle },
                { action -> Handler(Looper.getMainLooper()).post { action() }; Unit },
                premium,
                provider::currentIdentityFence,
                scope,
                { failures.incrementAndGet(); Unit }
            )
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Any? = null
        rule.runOnUiThread { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun processState(): Lifecycle.State = onMain { ProcessLifecycleOwner.get().lifecycle.currentState }
    private fun reads() = requests.count { it == "GET /api/notification-settings" }
    private fun chain(): Any? = onMain {
        BankAlertSettingsOwner::class.java.getDeclaredField("job").apply { isAccessible = true }.get(owner)
    }
    private fun pending(): Any? = onMain {
        AlertFamilyRefresherRegistry::class.java.getDeclaredField("pendingFor").apply { isAccessible = true }.get(registry)
    }
    private fun settled() = onMain { owner.state.value }.let {
        it.phase == AlertLoadPhase.Ready && it.settings?.map { setting -> setting.id } == listOf(7) && it.identity == a1
    }

    /** A pause long enough for a chain a transition might have started to reach the transport. */
    private fun quiet() {
        rule.waitForIdle()
        SystemClock.sleep(1_000)
        rule.waitForIdle()
    }

    private fun toBackground() {
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.waitUntil(10_000) { processState() == Lifecycle.State.CREATED }
    }

    private fun toForeground() {
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil(10_000) { processState().isAtLeast(Lifecycle.State.STARTED) }
    }

    /** A `sync_alerts` hand-over from a thread other than Main, as FCM's. */
    private fun syncFromAnotherThread() {
        val thread = Thread { registry.onSyncAlerts() }
        thread.start()
        thread.join()
    }

    /** The start under a foreground process: one GET, decoded into the owner's snapshot. */
    private fun startAndSettle() {
        assemble()
        assertTrue("premise: the activity holds the process in the foreground", processState().isAtLeast(Lifecycle.State.STARTED))
        rule.runOnUiThread { registry.start() }
        rule.waitUntil(5_000) { settled() }
        quiet()
        assertEquals("the start in the foreground made one GET", 1, reads())
        assertNull("and its chain ended", chain())
    }

    /**
     * S7N1_A01: started in the foreground, the registry hands the current foreground over once: one GET through the real
     * transport, decoded into the owner's snapshot. A second start is nothing; a real return to the foreground under a snapshot
     * under 30 s old starts no chain and makes no GET.
     */
    @Test fun s7n1_a01_theStartInTheForegroundIsOneGet_andAFreshReturnIsNone() {
        startAndSettle()
        rule.runOnUiThread { registry.start() }
        quiet()
        assertEquals("a second start made no GET", 1, reads())
        toBackground()
        toForeground()
        assertNull("the fresh return started no chain", chain())
        quiet()
        assertEquals("and made no GET", 1, reads())
        assertTrue("the snapshot stays", settled())
        assertEquals("every request was the settings GET", listOf("GET /api/notification-settings"), requests.distinct())
        assertEquals("no family call failed", 0, failures.get())
    }

    /**
     * S7N1_A02: a `sync_alerts` in the background reads nothing and is held for the identity it arrived for; the next real
     * foreground is authoritative and makes one GET though the snapshot is fresh, and the hold is spent: a further return under
     * the fresh snapshot makes none.
     */
    @Test fun s7n1_a02_aSyncHeldInTheBackgroundMakesTheNextForegroundAuthoritative() {
        startAndSettle()
        toBackground()
        syncFromAnotherThread()
        quiet()
        assertEquals("the background sync made no GET", 1, reads())
        assertEquals("and was held for the identity it arrived for", a1, pending())
        toForeground()
        rule.waitUntil(5_000) { reads() == 2 }
        rule.waitUntil(5_000) { settled() && chain() == null }
        quiet()
        assertEquals("the return made exactly one authoritative GET under a fresh snapshot", 2, reads())
        assertNull("the hold was spent", pending())
        toBackground()
        toForeground()
        quiet()
        assertEquals("a further fresh return made no GET", 2, reads())
        assertEquals("no family call failed", 0, failures.get())
    }

    /** S7N1_A03: a `sync_alerts` in the foreground is one authoritative GET under a fresh snapshot, held for nothing. */
    @Test fun s7n1_a03_aSyncInTheForegroundIsOneAuthoritativeGet() {
        startAndSettle()
        syncFromAnotherThread()
        rule.waitUntil(5_000) { reads() == 2 }
        rule.waitUntil(5_000) { settled() && chain() == null }
        quiet()
        assertEquals("one authoritative GET", 2, reads())
        assertNull("nothing was held", pending())
        assertEquals("no family call failed", 0, failures.get())
    }

    private companion object {
        const val SETTINGS_JSON = """{"settings":[{"id":7,"user_id":"A","bank":"kb","currency":"usd-krw","condition":"above",""" +
            """"threshold":1400.5,"is_enabled":true,"triggered":false,"created_at":"2026-10-01T10:00:00+09:00",""" +
            """"updated_at":"2026-10-01T10:00:00+09:00","triggered_at":null,"repeat_interval_sec":null}],"total_count":1}"""
    }
}
