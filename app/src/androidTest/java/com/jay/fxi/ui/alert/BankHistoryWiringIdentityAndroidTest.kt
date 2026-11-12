package com.jay.fxi.ui.alert

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.jay.fxi.MainActivity
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.BankAlertSettingsState
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.domain.model.AuthProvider
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.NewsItem
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.domain.model.UserInfo
import com.jay.fxi.domain.repository.NewsRepository
import com.jay.fxi.ui.premium.PremiumTopicConsumer
import com.jay.fxi.ui.premium.view.PremiumTopicRoute
import com.jay.fxi.ui.screen.PremiumTopicOwnerEntryPoint
import com.jay.fxi.ui.settings.SettingsViewModel
import com.jay.fxi.ui.viewmodel.NewsViewModel
import dagger.hilt.android.EntryPointAccessors
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Claude-owned S7 step (c) S7C_W03 on a device, in the real debug app's SingletonComponent: the route resolves the production
 * bank history wiring at the user's first open and not before, once; what it consumes — the issuer's access revisions, the
 * deletion revisions, the topic use authority, the deletion store, the graph bridge's fence supplier and the identity — is the
 * very object the producers hold: the topic runtime and the graph builder share the authority and the store, and the account
 * deletion stage writes the store and publishes the revisions. The D24 release admission is off in this build and the owner is
 * not started, so the open acquires nothing and reads nothing over the network.
 *
 * Claude-owned S7 E5a1 contract (R4c/S7/s7e5 API r2 §5, harness row): this test is about the history wiring, not the settings
 * owner, so its route call passes a no-op bank alert section source factory: in this real app's SingletonComponent the section
 * under the bank tabs looks up no settings owner through its entry point and ensures nothing. The history assertions are
 * unchanged. The implementation thread reads but does not edit this file.
 */
class BankHistoryWiringIdentityAndroidTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val singletons = EntryPointAccessors.fromApplication(app, BankHistoryEntryPoint::class.java)

    private fun Any.field(name: String): Any? {
        var type: Class<*>? = javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }.get(this)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("no field $name on ${javaClass.name}")
    }

    private val t = Instant.parse("2026-10-01T01:00:00Z")
    private val a1 = AuthIdentityFence("A", 1L)
    private val display = MutableStateFlow(TopicDisplayState(TopicDisplayOwner(a1, 1L), TopicRates().merge(listOf(
        TopicQuote("kb", "usd-krw", 1390.0, t), TopicQuote("kb", "jpy-krw", 930.0, t))), false, TopicConnectionDisplay.OPEN))
    private val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(a1, FreeTab.USD))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val consumer = PremiumTopicConsumer(display, focus, { a1 }, object : RateRowPreferenceStore {
        override suspend fun preferences(uid: String) = emptyMap<RateRowList, RateRowPreference>()
        override suspend fun remember(uid: String, list: RateRowList, preference: RateRowPreference) = Unit
    }, { o, tab -> focus.value = OwnedTopicFocus(o, tab) }, { }, { _, _ -> }, scope)
    @After fun tearDown() = scope.cancel()

    /** S7 E5a1 harness: an idle, empty bank alert section source; it counts what the route asks of it and does nothing. */
    private class NoOpBankAlertSection : BankAlertSectionSourceFactory {
        val created = java.util.concurrent.atomic.AtomicInteger()
        val ensures = java.util.concurrent.atomic.AtomicInteger()
        val retries = java.util.concurrent.atomic.AtomicInteger()
        val refreshes = java.util.concurrent.atomic.AtomicInteger()
        override fun create(context: android.content.Context): BankAlertSectionSource {
            created.incrementAndGet()
            return object : BankAlertSectionSource {
                override val settings = MutableStateFlow(BankAlertSettingsState(AlertLoadPhase.Idle, null, null))
                override val manualRefresh = MutableStateFlow(ManualRefreshState())
                override fun ensure() { ensures.incrementAndGet() }
                override fun authoritative() { retries.incrementAndGet() }
                override fun manual() { refreshes.incrementAndGet() }
                override fun setEnabled(id: Int, enabled: Boolean) {}
                override fun delete(id: Int) {}
            }
        }
    }
    private val bankAlerts = NoOpBankAlertSection()

    /**
     * S7C_W03 (consumption): before the first open nothing is resolved, through tab moves and a foreground; the first open
     * resolves the production wiring once; it consumes the process singletons, the same objects the topic runtime and the graph
     * builder hold, and the same owner every route entry point resolves.
     */
    @Test fun s7c_w03_theFirstOpenResolvesTheProductionWiringOnceOverTheProcessSingletons() {
        var made = 0
        var wiring: BankHistoryWiring? = null
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val granted = java.util.concurrent.atomic.AtomicInteger()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        // The route draws over the production wiring through counting delegates; the identity checks read the wiring itself.
        val counting = BankHistoryWiringFactory { context, owner ->
            made += 1
            val production = ProductionBankHistoryWiring.create(context, owner).also { wiring = it }
            BankHistoryWiring(object : com.jay.fxi.data.repository.BankHistoryAccess {
                override fun acquire(owner: TopicDisplayOwner) = production.access.acquire(owner).also {
                    asked.incrementAndGet()
                    if (it != null) granted.incrementAndGet()
                }
                override fun admits(use: com.jay.fxi.data.repository.BankHistoryUse) = production.access.admits(use)
            }, BankHistoryRead { identity, currency, guard ->
                reads.incrementAndGet()
                production.read.read(identity, currency, guard)
            }, production.accessRevisions, production.deletionRevisions)
        }
        val vm = NewsViewModel(object : NewsRepository {
            override suspend fun fetchNews(limit: Int, hours: Double): List<NewsItem> = emptyList()
            override suspend fun saveCache(items: List<NewsItem>) = Unit
            override suspend fun loadCache(): List<NewsItem>? = null
        })
        rule.setContent {
            PremiumTopicRoute(consumer = consumer, identity = a1, isActive = true, newsViewModel = vm,
                userInfo = UserInfo("A", null, null, null, AuthProvider.entries.first()), onSignOut = {},
                settingsContent = { _, _, _ -> }, bankHistoryWiring = counting, bankAlertSectionSourceFactory = bankAlerts)
        }
        rule.waitForIdle()
        for (tab in listOf(FreeTab.JPY, FreeTab.TETHER, FreeTab.USD)) { focus.value = OwnedTopicFocus(a1, tab); rule.waitForIdle() }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        assertEquals("nothing resolved before the first open", 0, made)
        rule.onNodeWithTag(BankHistoryTags.ENTRY).performClick()
        rule.waitForIdle()
        assertEquals("the first open resolved the production wiring once", 1, made)
        val w = checkNotNull(wiring)

        val owner = singletons.topicRuntimeOwner()
        assertSame("the same owner the root's entry point resolves", owner,
            EntryPointAccessors.fromApplication(app, PremiumTopicOwnerEntryPoint::class.java).topicRuntimeOwner())
        assertSame("the issuer's access revisions", singletons.premiumAccessCoordinator().accessRevisions, w.accessRevisions)
        assertSame("the deletion revisions", singletons.deletionAdmissionRevisions().revisions, w.deletionRevisions)
        val access = w.access
        assertTrue("the issuer-backed access", access is com.jay.fxi.data.repository.IssuerBankHistoryAccess)
        val authority = singletons.topicUseAuthority()
        assertSame("the access's uses are the process authority", authority, access.field("uses"))
        assertSame("the topic runtime's factory holds the same authority", authority, owner.field("factory")!!.field("authority"))
        val graphBuilder = checkNotNull(owner.field("graphBuilder")) { "premise: the owner has its graph builder" }
        assertSame("the graph builder's authority is the same", authority, (graphBuilder.field("uses") as Provider<*>).get())
        assertSame("the access reads the graph bridge's own fence supplier", owner.currentAccessFence, access.field("currentAccessFence"))
        val admission = access.field("protectedAdmission")
        assertTrue("the protected admission is the graph's", admission is GraphProtectedAdmission)
        val store = singletons.deletionAdmissionStore()
        assertSame("over the process deletion store", store, admission!!.field("deletions"))
        assertSame("which the graph builder admits by too", store, (graphBuilder.field("deletions") as Provider<*>).get())
        assertNull("premise: the owner is not started, so the graph bridge has no fence", owner.currentAccessFence())
        assertFalse("premise: the release admission is closed in this build", com.jay.fxi.admission.ReleaseAdmission.isOpen)
        assertEquals("the open asked the production access once, was granted nothing and read nothing", listOf(1, 0, 0),
            listOf(asked.get(), granted.get(), reads.get()))
        assertEquals("so the sheet shows the generic failure", 1,
            rule.onAllNodesWithText(BANK_HISTORY_GENERIC_FAILURE).fetchSemanticsNodes().size)
    }

    /**
     * S7C_W03 (production): the settings screen's account deletion stage, built by Hilt for the real activity, writes the very
     * deletion store the wiring admits by, and its publication advances the very deletion revisions the wiring rechecks on.
     */
    @Test fun s7c_w03_theDeletionStageWritesTheSameStoreAndPublishesTheSameRevisions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val settings = ViewModelProvider(activity)[SettingsViewModel::class.java]
                val stage = checkNotNull(settings.field("accountDeletionServerStage")) { "premise: the stage" }
                assertSame("the stage writes the process deletion store", singletons.deletionAdmissionStore(), stage.field("deletions"))
                val revisions = singletons.deletionAdmissionRevisions().revisions
                val before = revisions.value
                @Suppress("UNCHECKED_CAST")
                val published = checkNotNull(stage.field("onPublished") as? () -> Unit) { "premise: the stage's publication" }
                published()
                assertEquals("its publication advances the revisions the wiring rechecks on", before + 1, revisions.value)
                assertNotNull(activity)
            }
        }
    }
}
