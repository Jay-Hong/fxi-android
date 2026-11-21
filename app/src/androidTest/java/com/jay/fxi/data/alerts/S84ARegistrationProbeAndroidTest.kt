package com.jay.fxi.data.alerts

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.FXiApplication
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.ui.alert.BankAlertSectionEntryPoint
import com.jay.fxi.ui.alert.NotificationPermissionEntryPoint
import com.jay.fxi.ui.alert.ProductionSourceAlertSectionSourceFactory
import com.jay.fxi.ui.alert.SourceAlertSection
import com.jay.fxi.ui.alert.SourceAlertSectionEntryPoint
import com.jay.fxi.ui.alert.SourceAlertSectionSource
import com.jay.fxi.ui.alert.SourceAlertSectionSourceFactory
import com.jay.fxi.ui.alert.SourceAlertSectionTags
import dagger.hilt.android.EntryPointAccessors
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit

/**
 * S8-4a (`R4c/S8/s8_4a_contract.agreed.r1.md` §4, design §4 "운영 @Inject start/foreground probe") on the emulator, in the real
 * debug app's SingletonComponent.
 *
 * R01 reads the production registration without starting it: the registry Hilt builds through its @Inject constructor holds two
 * families, which resolve the very bank and source owners the bank and tether sections' entry points resolve, the same on
 * every resolve, and the production tether factory reads that source owner's flows. The D24 release admission is off in this
 * build, the registry is never started and no owner is foregrounded, so nothing is sent.
 *
 * R02 runs the @Inject constructor itself under the real process lifecycle, with counting providers that hand out a bank and a
 * source owner assembled over the real repository, client and transport, the HTTP exchange ending at an interceptor with a
 * canned answer (nothing leaves the device). A cold foreground start and a tether section reading that same source owner are
 * one settings GET for each family; each provider is resolved once through two starts and a background/foreground return. The
 * capability hook's provider counts and throws: the registry contains a hook failure, so the real monitor is never touched.
 * The registry built here would stay an observer of the process lifecycle with its own scope's premium collector, so the test
 * ends by cancelling that scope and removing that observer (test-only reflection; production keeps no stop API), and a further
 * background/foreground return then reaches no hook, no provider and no GET.
 */
class S84ARegistrationProbeAndroidTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FXiApplication
    private val a1 = AuthIdentityFence("A", 1L)
    private val fence = TopicSessionFence(a1, "e1", TopicGrantToken(1L))
    private val scopes = mutableListOf<CoroutineScope>()
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @After fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun <T> onMain(block: () -> T): T {
        var result: Any? = null
        rule.runOnUiThread { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
    private fun Any.field(name: String): Any? = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
    private fun processState(): Lifecycle.State = onMain { ProcessLifecycleOwner.get().lifecycle.currentState }
    private fun gets(path: String) = requests.count { it == "GET $path" }

    /** R01: the production registration resolves the sections' own singletons; nothing is started. */
    @Test fun s84a_r01_theProductionRegistrationResolvesTheSectionsOwnSingletons() {
        val registry = onMain { app.alertFamilies.get() }
        assertSame("the registry is the process singleton", registry, onMain { app.alertFamilies.get() })
        assertFalse("premise: the D24-OFF process never started it", registry.field("started") as Boolean)
        @Suppress("UNCHECKED_CAST")
        val families = registry.field("families") as List<() -> AlertLifecycleAdapter>
        assertEquals("two families", 2, families.size)
        val bank = onMain { families[0]() }
        val source = onMain { families[1]() }
        assertSame("the first family is the bank section's owner",
            EntryPointAccessors.fromApplication(app, BankAlertSectionEntryPoint::class.java).bankAlertSettingsOwner(), bank)
        assertSame("the second family is the tether section's owner",
            EntryPointAccessors.fromApplication(app, SourceAlertSectionEntryPoint::class.java).sourceAlertSettingsOwner(), source)
        assertSame("a second resolve is the same source owner", source, onMain { families[1]() })
        assertSame("a second resolve is the same bank owner", bank, onMain { families[0]() })
        val section = ProductionSourceAlertSectionSourceFactory.create(app)
        source as SourceAlertSettingsOwner
        assertSame("the production tether factory reads its settings", source.state, section.settings)
        assertSame("and its manual refresh", source.manualRefresh, section.manualRefresh)
        assertFalse("still not started", registry.field("started") as Boolean)
    }

    /** R02: through the @Inject constructor, a cold foreground with the section is one GET a family and one resolve a provider. */
    @Test fun s84a_r02_theInjectConstructorResolvesEachProviderOnce_andAColdForegroundWithTheSectionIsOneGetEach() {
        val tokens = AuthTokenProvider(object : AuthTokenSource {
            override fun currentIdentity() = AuthIdentity("A", 1L)
            override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean) = "credential"
        }, orders = AccessOrderSequence())
        val http = OkHttpClient.Builder()
            .addInterceptor(AuthSnapshotInterceptor(tokens))
            .addInterceptor { chain ->
                val url = chain.request().url
                requests += "${chain.request().method} ${url.encodedPath}" + (url.encodedQuery?.let { "?$it" } ?: "")
                val body = if (url.encodedPath == "/api/source-notification-settings") SOURCE_JSON else BANK_JSON
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }
            .build()
        val wireJson = NetworkModule.provideWireJson()
        val retrofit = Retrofit.Builder().baseUrl("http://localhost/").client(http)
            .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build()
        val repository = AlertRepository(AuthenticatedApiClient(retrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(tokens, admitted = { true }), wireJson))
        val snapshot = TopicAccessSnapshot.INITIAL.copy(
            revision = 1L, facts = TopicAccessFacts.NONE.copy(token = fence.grant, tokenStanding = true, userBlocks = emptySet()),
            userInvalidations = 0L
        )
        val premium = MutableStateFlow(OwnedPremiumAccess("A", 1L, PremiumAccessState.PremiumConfirmed))
        val access = AlertSettingsAccess(tokens::currentIdentityFence, { fence }, SnapshotTopicUseAuthority { snapshot },
            { premium.value }, { true }, GraphProtectedAdmission(tokens::currentIdentityFence, DeletionAdmissionStore()))
        val floor = AlertRateLimitFloor(SystemClock::elapsedRealtime)
        lateinit var bank: BankAlertSettingsOwner
        lateinit var source: SourceAlertSettingsOwner
        rule.runOnUiThread {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scopes += it }
            bank = BankAlertSettingsOwner(
                { o, admitted -> repository.getSettings(o, admitted) }, access, floor, AlertRetryPolicy(), SystemClock::elapsedRealtime,
                { 0.0 }, scope, { delay(it) }, premium, MutableStateFlow(0L), MutableStateFlow(true),
                { _, _, _, _ -> error("S8-4a: no toggle") }, { _, _, _ -> error("S8-4a: no delete") },
                { _, _, _ -> error("S8-4a: no create") }, { _, _, _, _ -> error("S8-4a: no edit") }
            )
            source = SourceAlertSettingsOwner(
                { o, admitted -> repository.getSourceSettings(o, SourceAlertRowPolicy.SETTINGS_ASSET, admitted) }, access, floor,
                AlertRetryPolicy(), SystemClock::elapsedRealtime, { 0.0 }, scope, { delay(it) }, premium, MutableStateFlow(0L)
            )
        }
        val bankResolves = AtomicInteger()
        val sourceResolves = AtomicInteger()
        val hooks = AtomicInteger()
        val entry = EntryPointAccessors.fromApplication(app, NotificationPermissionEntryPoint::class.java)
        val registry = AlertFamilyRefresherRegistry(
            Provider { bankResolves.incrementAndGet(); bank },
            Provider { sourceResolves.incrementAndGet(); source },
            entry.premiumAccessCoordinator(),
            entry.authTokenProvider(),
            Provider { hooks.incrementAndGet(); throw IllegalStateException("S8-4a probe: the real monitor is not touched") }
        )
        val sameOwner = SourceAlertSectionSourceFactory {
            object : SourceAlertSectionSource {
                override val settings = source.state
                override val manualRefresh = source.manualRefresh
                override fun ensure() = source.ensure()
                override fun authoritative() = source.authoritative()
                override fun manual() = source.manual()
            }
        }

        assertTrue("premise: the activity holds the process in the foreground", processState().isAtLeast(Lifecycle.State.STARTED))
        var removed = -1
        try {
            rule.runOnUiThread { registry.start() }
            rule.setContent { SourceAlertSection(tab = FreeTab.TETHER, owner = TopicDisplayOwner(a1, 1L), isShown = { true }, sourceFactory = sameOwner) }
            rule.waitUntil(5_000) { onMain { source.state.value.phase == AlertLoadPhase.Ready && bank.state.value.phase == AlertLoadPhase.Ready } }
            rule.waitUntil(5_000) { rule.onAllNodesWithTag(SourceAlertSectionTags.ROW).fetchSemanticsNodes().size == 1 }
            quiet()
            assertEquals("the section drew the owner's one adopted row", listOf(21), onMain { source.state.value.settings?.map { it.id } })
            assertEquals("one source settings GET for the cold foreground and the section's ensure", 1,
                gets("/api/source-notification-settings?asset=usdt-krw"))
            assertEquals("one bank settings GET", 1, gets("/api/notification-settings"))
            assertEquals("each provider resolved once, the capability hook once", listOf(1, 1, 1),
                listOf(bankResolves.get(), sourceResolves.get(), hooks.get()))

            rule.runOnUiThread { registry.start() }
            rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            rule.waitUntil(10_000) { processState() == Lifecycle.State.CREATED }
            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            rule.waitUntil(10_000) { processState().isAtLeast(Lifecycle.State.STARTED) }
            quiet()
            assertEquals("a second start and a return resolve no provider again", listOf(1, 1),
                listOf(bankResolves.get(), sourceResolves.get()))
            assertEquals("the return ran the hook once more", 2, hooks.get())
            assertEquals("a fresh return sends no second GET", listOf(1, 1),
                listOf(gets("/api/source-notification-settings?asset=usdt-krw"), gets("/api/notification-settings")))
            assertEquals("every request was a settings GET", setOf("GET /api/source-notification-settings?asset=usdt-krw",
                "GET /api/notification-settings"), requests.toSet())
        } finally {
            // Test-only teardown of the registry built here, whatever happened above: its own scope and its process observer.
            (registry.field("scope") as CoroutineScope).cancel()
            removed = removeObserversOf(registry)
        }
        assertEquals("the registry built here observed the process once", 1, removed)
        // Then a return must reach nothing of it.
        val before = listOf(bankResolves.get(), sourceResolves.get(), hooks.get(), requests.size)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.waitUntil(10_000) { processState() == Lifecycle.State.CREATED }
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil(10_000) { processState().isAtLeast(Lifecycle.State.STARTED) }
        quiet()
        assertEquals("after the teardown a return reaches no provider, no hook and no GET", before,
            listOf(bankResolves.get(), sourceResolves.get(), hooks.get(), requests.size))
    }

    /** Removes from the process lifecycle every observer that belongs to [registry]; returns how many there were. */
    private fun removeObserversOf(registry: AlertFamilyRefresherRegistry): Int = onMain {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        val map = LifecycleRegistry::class.java.declaredFields.first { it.name == "observerMap" || it.name == "mObserverMap" }
            .apply { isAccessible = true }.get(lifecycle) as Iterable<*>
        val observers = map.map { (it as Map.Entry<*, *>).key as LifecycleObserver }
        val mine = observers.filter { observer ->
            observer.javaClass.declaredFields.any { field -> field.isAccessible = true; field.get(observer) === registry }
        }
        mine.forEach { lifecycle.removeObserver(it) }
        mine.size
    }

    /** A pause long enough for a chain a transition might have started to reach the transport. */
    private fun quiet() {
        rule.waitForIdle()
        SystemClock.sleep(1_500)
        rule.waitForIdle()
    }

    private companion object {
        const val BANK_JSON = """{"settings":[{"id":7,"user_id":"A","bank":"kb","currency":"usd-krw","condition":"above",""" +
            """"threshold":1400.5,"is_enabled":true,"triggered":false,"created_at":"2026-10-01T10:00:00+09:00",""" +
            """"updated_at":"2026-10-01T10:00:00+09:00","triggered_at":null,"repeat_interval_sec":null}],"total_count":1}"""
        const val SOURCE_JSON = """{"settings":[{"id":21,"user_id":"A","source":"upbit","asset":"usdt-krw","condition":"above",""" +
            """"threshold":1400.5,"is_enabled":true,"triggered":false,"repeat_interval_sec":null,""" +
            """"created_at":"2026-10-01T10:00:00+09:00","updated_at":"2026-10-01T10:00:00+09:00","triggered_at":null}],"total_count":1}"""
    }
}
