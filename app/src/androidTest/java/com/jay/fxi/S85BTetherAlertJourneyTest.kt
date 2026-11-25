package com.jay.fxi

import android.content.Context
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.AlertRateLimitFloor
import com.jay.fxi.data.alerts.AlertRetryPolicy
import com.jay.fxi.data.alerts.AlertSettingsAccess
import com.jay.fxi.data.alerts.BankAlertSettingsState
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.alerts.SourceAlertDraft
import com.jay.fxi.data.alerts.SourceAlertEdit
import com.jay.fxi.data.alerts.SourceAlertRowPolicy
import com.jay.fxi.data.alerts.SourceAlertSettingsOwner
import com.jay.fxi.data.alerts.productionSourceCreate
import com.jay.fxi.data.alerts.productionSourceDelete
import com.jay.fxi.data.alerts.productionSourceEdit
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
import com.jay.fxi.data.local.LastSelectedSourceMemory
import com.jay.fxi.data.local.LastSelectedSourceStore
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.local.SourceSelectionSnapshot
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.data.repository.BankHistoryAccess
import com.jay.fxi.data.repository.BankHistoryUse
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.AuthProvider
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.NewsItem
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.domain.model.UserInfo
import com.jay.fxi.domain.repository.NewsRepository
import com.jay.fxi.service.NotificationCapability
import com.jay.fxi.service.readNotificationCapability
import com.jay.fxi.ui.alert.BankAlertSectionSource
import com.jay.fxi.ui.alert.BankAlertSectionSourceFactory
import com.jay.fxi.ui.alert.BankHistoryRead
import com.jay.fxi.ui.alert.BankHistoryWiring
import com.jay.fxi.ui.alert.BankHistoryWiringFactory
import com.jay.fxi.ui.alert.CountingSourceHistoryWiring
import com.jay.fxi.ui.alert.LocalNotificationPermissionEnvironment
import com.jay.fxi.ui.alert.NotificationPermissionEnvironment
import com.jay.fxi.ui.alert.SourceAlertEditor
import com.jay.fxi.ui.alert.SourceAlertEditorSheetTags
import com.jay.fxi.ui.alert.SourceAlertSectionSource
import com.jay.fxi.ui.alert.SourceAlertSectionSourceFactory
import com.jay.fxi.ui.alert.SourceAlertSectionTags
import com.jay.fxi.ui.premium.PremiumTopicConsumer
import com.jay.fxi.ui.premium.view.PremiumTopicRoute
import com.jay.fxi.ui.viewmodel.NewsViewModel
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit

/**
 * S8-5b D1 (`R4c/S8/s8_5b_contract.agreed.r1.md` §3 D1) on the emulator: the real route, the real tether section and holder, the
 * real 5a source owner over the real repository, client and transport, the HTTP exchange ending at an in-process server that
 * keeps rows and answers each verb as the server does (nothing leaves the device). The permission helper runs under its
 * environment seam on a device that shows notifications, so its continuation runs at once. The tether list hides bithumb, whose
 * quote still arrives; coinone's never does.
 *
 * D1a is the write journey: an add from the remembered fallback, a pick of the hidden exchange with its own quote and range, a
 * typed price kept while that quote moves, one POST of the exact body, the edit's one PUT of its changed fields, the switch's
 * one PUT of its target, a cancelled delete that sends nothing and a confirmed one that sends one DELETE, each settled against
 * the server's rows. D1b is the refusals and the cap: an allowed POST answered 400 keeps the draft and its copy, an edit PUT
 * answered 404 closes with the removed notice, and at 30 and 31 rows no add opens while the last row still switches. D1c is a
 * disabled row's cancel and re-enable, an exchange with no quote, the once/every round trip and a failed switch's rollback.
 */
class S85BTetherAlertJourneyTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val t = Instant.parse("2026-11-22T00:00:00Z")
    private val a1 = AuthIdentityFence("A", 1L)
    private val o1 = TopicDisplayOwner(a1, 1L)
    private val fence = TopicSessionFence(a1, "e1", TopicGrantToken(1L))
    private val scopes = mutableListOf<CoroutineScope>()

    private fun quotes(bithumb: Double = 1398.5) = TopicRates().merge(listOf(
        TopicQuote("investing", "usd-krw", 1389.0, t), TopicQuote("kb", "usd-krw", 1390.0, t),
        TopicQuote("upbit", "usdt-krw", 1401.0, t), TopicQuote("bithumb", "usdt-krw", bithumb, t)))

    private class NoNews : NewsRepository {
        override suspend fun fetchNews(limit: Int, hours: Double): List<NewsItem> = emptyList()
        override suspend fun saveCache(items: List<NewsItem>) = Unit
        override suspend fun loadCache(): List<NewsItem>? = null
    }
    /** The tether list hides bithumb; its quote still arrives. */
    private class HiddenBithumb : RateRowPreferenceStore {
        override suspend fun preferences(uid: String) = mapOf(RateRowList.TETHER_EXCHANGES to RateRowPreference(hidden = setOf("bithumb")))
        override suspend fun remember(uid: String, list: RateRowList, preference: RateRowPreference) = Unit
    }
    private class Bank : BankAlertSectionSourceFactory {
        override fun create(context: Context): BankAlertSectionSource = object : BankAlertSectionSource {
            override val settings = MutableStateFlow(BankAlertSettingsState(AlertLoadPhase.Idle, null, null))
            override val manualRefresh = MutableStateFlow(ManualRefreshState())
            override fun ensure() {}
            override fun authoritative() {}
            override fun manual() {}
            override fun setEnabled(id: Int, enabled: Boolean) = error("S8-5b: no bank write")
            override fun add(draft: com.jay.fxi.data.alerts.BankAlertDraft): com.jay.fxi.data.alerts.BankAlertSaveRequest = error("S8-5b: no bank write")
            override fun edit(id: Int, edit: com.jay.fxi.data.alerts.BankAlertEdit): com.jay.fxi.data.alerts.BankAlertSaveRequest = error("S8-5b: no bank write")
            override val bankSelection = MutableStateFlow<com.jay.fxi.data.local.BankSelectionSnapshot>(com.jay.fxi.data.local.BankSelectionSnapshot.None)
            override suspend fun prepareBankSelection(identity: AuthIdentityFence) = Unit
            override fun delete(id: Int) = error("S8-5b: no bank write")
        }
    }
    /** The last added exchange per UID, in memory; [writes] counts what reached it. */
    private class MemoryStore : LastSelectedSourceStore {
        val values = java.util.concurrent.ConcurrentHashMap<String, String>()
        val writes = CopyOnWriteArrayList<Pair<String, String>>()
        override suspend fun read(uid: String): String? = values[uid]
        override suspend fun remember(uid: String, sourceCode: String) {
            writes += uid to sourceCode
            values[uid] = sourceCode
        }
    }

    /** One request the server received. */
    private data class Received(val method: String, val path: String, val body: String)

    /** Keeps rows by id and answers each verb as the server does; [answer] may answer one request in its place. */
    private class Server : Interceptor {
        private val rows = linkedMapOf<Int, JsonObject>()
        private var nextId = 1
        val received = CopyOnWriteArrayList<Received>()
        @Volatile var answer: (Received) -> Pair<Int, String>? = { null }

        fun seed(vararg seeded: JsonObject) = synchronized(this) {
            for (row in seeded) {
                rows[row.id()] = row
                nextId = maxOf(nextId, row.id() + 1)
            }
        }
        fun drop(id: Int) = synchronized(this) { rows.remove(id) }
        fun ids(): Set<Int> = synchronized(this) { rows.keys.toSet() }
        fun row(id: Int): JsonObject? = synchronized(this) { rows[id] }
        fun writes(): List<Received> = received.filter { it.method != "GET" }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            val r = Received(request.method, request.url.encodedPath, body)
            received += r
            val (code, text) = answer(r) ?: synchronized(this) { handle(r) }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message(if (code < 300) "OK" else "Error")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }

        private fun handle(r: Received): Pair<Int, String> {
            val id = r.path.removePrefix(PATH).removePrefix("/").toIntOrNull()
            return when (r.method) {
                "GET" -> 200 to """{"settings":[${rows.values.joinToString(",")}],"total_count":${rows.size}}"""
                "POST" -> {
                    val row = rowJson(nextId++, Json.parseToJsonElement(r.body).jsonObject)
                    rows[row.id()] = row
                    200 to row.toString()
                }
                "PUT" -> rows[id]?.let { old -> JsonObject(old + Json.parseToJsonElement(r.body).jsonObject) }
                    ?.also { rows[id!!] = it }?.let { 200 to it.toString() } ?: (404 to """{"detail":"not found"}""")
                "DELETE" -> if (id != null && rows.remove(id) != null) 204 to "" else 404 to """{"detail":"not found"}"""
                else -> 405 to "{}"
            }
        }
    }

    private val server = Server()
    private val store = MemoryStore()
    private val display = MutableStateFlow(TopicDisplayState(o1, quotes(), false, TopicConnectionDisplay.OPEN))
    private val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(a1, FreeTab.TETHER))
    private lateinit var owner: SourceAlertSettingsOwner
    private lateinit var memory: LastSelectedSourceMemory
    private lateinit var consumer: PremiumTopicConsumer
    private val env = NotificationPermissionEnvironment(
        admitted = { true },
        liveIdentity = { a1 },
        premium = MutableStateFlow(OwnedPremiumAccess(a1.uid, a1.authGeneration, PremiumAccessState.PremiumConfirmed)),
        displayed = MutableStateFlow(NotificationCapability.GRANTED),
        onRequestResult = { false },
        onAlreadyGranted = { true }
    )
    private val section = SourceAlertSectionSourceFactory {
        object : SourceAlertSectionSource {
            override val settings = owner.state
            override val manualRefresh = owner.manualRefresh
            override val selection = memory.selection
            override suspend fun prepareSelection(identity: AuthIdentityFence) = memory.prepare(identity)
            override fun acceptAddedSource(identity: AuthIdentityFence, sourceCode: String) = memory.acceptAddedSource(identity, sourceCode)
            override fun ensure() = owner.ensure()
            override fun authoritative() = owner.authoritative()
            override fun manual() = owner.manual()
            override fun addAlert(draft: SourceAlertDraft) = owner.addAlert(draft)
            override fun editAlert(id: Int, edit: SourceAlertEdit) = owner.editAlert(id, edit)
            override fun setEnabled(id: Int, enabled: Boolean) = owner.setEnabled(id, enabled)
            override fun delete(id: Int) = owner.delete(id)
        }
    }
    private val history = BankHistoryWiringFactory { _, _ ->
        BankHistoryWiring(object : BankHistoryAccess {
            override fun acquire(owner: TopicDisplayOwner): BankHistoryUse? = null
            override fun admits(use: BankHistoryUse) = false
        }, BankHistoryRead { _, _, _ -> awaitCancellation() }, MutableStateFlow(0L), MutableStateFlow(0L))
    }

    @Before fun grantedDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("precondition: this device shows notifications, so the helper's continuation runs at once",
            NotificationCapability.GRANTED, readNotificationCapability(context))
    }

    @After fun tearDown() = scopes.forEach { it.cancel() }

    private fun start(vararg seeded: JsonObject) {
        server.seed(*seeded)
        val tokens = AuthTokenProvider(object : AuthTokenSource {
            override fun currentIdentity() = AuthIdentity("A", 1L)
            override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean) = "credential"
        }, orders = AccessOrderSequence())
        val http = OkHttpClient.Builder().addInterceptor(AuthSnapshotInterceptor(tokens)).addInterceptor(server).build()
        val wireJson = NetworkModule.provideWireJson()
        val retrofit = Retrofit.Builder().baseUrl("http://localhost/").client(http)
            .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build()
        val repository = AlertRepository(AuthenticatedApiClient(retrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(tokens, admitted = { true }), wireJson))
        val snapshot = TopicAccessSnapshot.INITIAL.copy(revision = 1L,
            facts = TopicAccessFacts.NONE.copy(token = fence.grant, tokenStanding = true, userBlocks = emptySet()), userInvalidations = 0L)
        val premium = MutableStateFlow(OwnedPremiumAccess("A", 1L, PremiumAccessState.PremiumConfirmed))
        val access = AlertSettingsAccess(tokens::currentIdentityFence, { fence }, SnapshotTopicUseAuthority { snapshot },
            { premium.value }, { true }, GraphProtectedAdmission(tokens::currentIdentityFence, DeletionAdmissionStore()))
        rule.runOnUiThread {
            val main = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scopes += it }
            owner = SourceAlertSettingsOwner(
                { o, admitted -> repository.getSourceSettings(o, SourceAlertRowPolicy.SETTINGS_ASSET, admitted) }, access,
                AlertRateLimitFloor(SystemClock::elapsedRealtime), AlertRetryPolicy(), SystemClock::elapsedRealtime, { 0.0 }, main,
                { delay(it) }, premium, MutableStateFlow(0L),
                productionSourceCreate(repository), productionSourceEdit(repository), productionSourceDelete(repository))
            memory = LastSelectedSourceMemory(store, Dispatchers.Main.immediate,
                CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }) { }
            consumer = PremiumTopicConsumer(display, focus, { a1 }, HiddenBithumb(),
                { o, tab -> focus.value = OwnedTopicFocus(o, tab) }, { }, { _, _ -> }, main)
            owner.onForeground(false)
        }
        await("the first GET is adopted") { onMain { owner.state.value.settings } != null }
        val vm = NewsViewModel(NoNews())
        rule.setContent {
            CompositionLocalProvider(LocalNotificationPermissionEnvironment provides env) {
                PremiumTopicRoute(consumer = consumer, identity = a1, isActive = true, newsViewModel = vm,
                    userInfo = UserInfo("A", null, null, null, AuthProvider.entries.first()), onSignOut = {},
                    settingsContent = { _, _, _ -> }, graphHost = null, bankHistoryWiring = history,
                    bankAlertSectionSourceFactory = Bank(), sourceAlertSectionSourceFactory = section,
                    sourceHistoryWiring = CountingSourceHistoryWiring(),
                    comparisonAlertSectionSourceFactory = comparisonTestFactory(consumer))
            }
        }
        rule.waitForIdle()
        await("the section is drawn") { count(SourceAlertSectionTags.ROOT) == 1 }
        await("the selection settled") { onMain { memory.selection.value } is SourceSelectionSnapshot.Ready }
    }

    // ---- reads and taps ------------------------------------------------------------------------------------------------

    private fun <T> onMain(block: () -> T): T {
        var result: Any? = null
        rule.runOnUiThread { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
    private fun await(label: String, condition: () -> Boolean) {
        try {
            rule.waitUntil(10_000) { condition() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("timed out: $label", e)
        }
    }
    private fun count(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    /** S8-5b: the picker chips' exchange codes in drawing order, and the nodes matching [matcher] inside the open sheet. */
    private val pickerPrefix = SourceAlertEditorSheetTags.source("")
    private fun pickerCodes(): List<String> = rule.onAllNodes(SemanticsMatcher("a picker chip") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(pickerPrefix) == true
    }, useUnmergedTree = true).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].removePrefix(pickerPrefix) }
    private fun inSheet(matcher: SemanticsMatcher) = rule.onAllNodes(
        matcher and hasAnyAncestor(hasTestTag(SourceAlertEditorSheetTags.SHEET)), useUnmergedTree = true).fetchSemanticsNodes().size
    /** Contract r3 §4 rows 140 and 143: the open sheet offers exactly the policy's exchanges and no switch of its own. */
    private fun assertSheetControls(label: String) {
        assertEquals("$label: premise: the sheet's own nodes are found under it", 1, inSheet(hasTestTag(SourceAlertEditorSheetTags.SAVE)))
        assertEquals("$label: the picker offers exactly the policy's exchanges, in its order",
            listOf("upbit", "bithumb", "coinone", "korbit", "gopax"), pickerCodes())
        assertEquals("$label has no switch", 0, inSheet(isToggleable()))
    }
    private fun texts(text: String) = rule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun tap(tag: String) {
        rule.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
    }
    private fun tapInSheet(tag: String) {
        rule.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }
    private fun type(text: String) {
        rule.onNodeWithTag(SourceAlertEditorSheetTags.THRESHOLD, useUnmergedTree = true).performTextReplacement(text)
        rule.waitForIdle()
    }
    private fun threshold(): String = rule.onNodeWithTag(SourceAlertEditorSheetTags.THRESHOLD, useUnmergedTree = true)
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
    private fun textOf(tag: String): String = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private fun sheetOpen() = count(SourceAlertEditorSheetTags.SAVE) == 1
    private fun save() = tapInSheet(SourceAlertEditorSheetTags.SAVE)
    private fun repeat(interval: RepeatInterval?) = tapInSheet(SourceAlertEditorSheetTags.repeatOption(SourceAlertEditor.REPEAT_CHOICES.indexOf(interval)))
    private fun publishBithumb(value: Double) {
        rule.runOnUiThread { display.value = display.value.copy(rates = display.value.rates.merge(listOf(
            TopicQuote("bithumb", "usdt-krw", value, Instant.fromEpochMilliseconds(t.toEpochMilliseconds() + 60_000))))) }
        rule.waitForIdle()
    }
    private fun body(r: Received): Map<String, String> = Json.parseToJsonElement(r.body).jsonObject.mapValues { it.value.toString() }
    private fun shownIds(): Set<Int> = onMain { owner.state.value.settings.orEmpty().map { it.id }.toSet() }

    // ---- D1 ----------------------------------------------------------------------------------------------------------

    @Test fun s85b_d1a_anAddFromTheHiddenExchange_thenAnEditASwitchAndADeleteEachSendOnce() {
        start()
        assertEquals("the empty list offers one add", 1, count(SourceAlertSectionTags.ADD_BUTTON))
        val bithumb = checkNotNull(com.jay.fxi.domain.model.Exchange.fromCode("bithumb")).displayName
        await("premise: the hidden exchange has no row on the screen") { texts(bithumb) == 0 }
        tap(SourceAlertSectionTags.ADD_BUTTON)
        assertTrue("the add opened", sheetOpen())
        assertSheetControls("the add sheet")
        assertEquals("nothing remembered: upbit, seeded from its quote", "1401", threshold())
        tapInSheet(SourceAlertEditorSheetTags.source("bithumb"))
        assertEquals("the hidden exchange's own quote refills the price", "1399", threshold())
        assertEquals("현재 시세 1399원", textOf(SourceAlertEditorSheetTags.CURRENT_RATE))
        assertEquals("설정 가능 범위 700 ~ 2100원", textOf(SourceAlertEditorSheetTags.RANGE))
        type("1450")
        publishBithumb(1500.0)
        assertEquals("a quote that moves keeps the typed price", "1450", threshold())
        assertEquals("and moves the range", "설정 가능 범위 750 ~ 2250원", textOf(SourceAlertEditorSheetTags.RANGE))
        save()
        await("the add settled") { !sheetOpen() && shownIds().size == 1 }
        val post = server.writes().single()
        assertEquals("POST $PATH", "${post.method} ${post.path}")
        assertEquals("the draft as typed, once by omission", mapOf("source" to "\"bithumb\"", "asset" to "\"usdt-krw\"",
            "condition" to "\"below\"", "threshold" to "1450.0", "is_enabled" to "true"), body(post))
        val id = shownIds().single()
        assertEquals("the server's row is shown", server.ids(), shownIds())
        await("the add is remembered") { store.writes.toList() == listOf("A" to "bithumb") }

        tap(SourceAlertSectionTags.rowOpen(id))
        assertTrue("the edit opened", sheetOpen())
        assertSheetControls("the edit sheet")
        assertEquals("the row's price", "1450", threshold())
        type("1460")
        repeat(RepeatInterval.FIVE_MINUTES)
        save()
        await("the edit settled") { !sheetOpen() && server.writes().size == 2 }
        assertEquals("PUT $PATH/$id", server.writes()[1].let { "${it.method} ${it.path}" })
        assertEquals("only what changed", mapOf("threshold" to "1460.0", "repeat_interval_sec" to "300"), body(server.writes()[1]))
        assertEquals("an edit is not remembered", listOf("A" to "bithumb"), store.writes.toList())

        tap(SourceAlertSectionTags.toggle(id))
        await("the switch settled") { server.writes().size == 3 }
        assertEquals("the switch's target", mapOf("is_enabled" to "false"), body(server.writes()[2]))
        await("the row reads off") { rule.onAllNodes(hasTestTag(SourceAlertSectionTags.toggle(id)) and isOff()).fetchSemanticsNodes().size == 1 }

        tap(SourceAlertSectionTags.delete(id))
        tapInSheet(SourceAlertSectionTags.DELETE_CANCEL)
        rule.waitForIdle()
        assertEquals("a cancel sends nothing", 3, server.writes().size)
        tap(SourceAlertSectionTags.delete(id))
        tapInSheet(SourceAlertSectionTags.DELETE_CONFIRM)
        await("the delete settled") { server.writes().size == 4 && shownIds().isEmpty() }
        assertEquals("DELETE $PATH/$id", server.writes()[3].let { "${it.method} ${it.path}" })
        assertEquals("the server holds none", emptySet<Int>(), server.ids())
        // Re-entry: a row only the server holds proves that the new section shows a newly adopted canonical list.
        rule.runOnUiThread { focus.value = OwnedTopicFocus(a1, FreeTab.USD) }
        await("left tether") { count(SourceAlertSectionTags.ROOT) == 0 }
        server.seed(rowJson(91, threshold = 1301.0))
        val gets = server.received.count { it.method == "GET" }
        rule.runOnUiThread { focus.value = OwnedTopicFocus(a1, FreeTab.TETHER) }
        await("re-entered tether") { count(SourceAlertSectionTags.ROOT) == 1 }
        rule.runOnUiThread { owner.authoritative() }
        await("the re-entered section adopted the server list") {
            server.received.count { it.method == "GET" } > gets && shownIds() == setOf(91) && count(SourceAlertSectionTags.rowOpen(91)) == 1
        }
        assertEquals("four writes in all", 4, server.writes().size)
    }

    @Test fun s85b_d1b_aRejectedPostKeepsTheDraft_aGonePutClosesWithTheNotice_andTheCapStopsOnlyTheAdd() {
        start(rowJson(5, source = "upbit", threshold = 1300.0))
        server.answer = { r -> if (r.method == "POST") 400 to """{"detail":"rejected"}""" else null }
        tap(SourceAlertSectionTags.ADD_BUTTON)
        type("1410")
        save()
        await("the rejection's copy") { count(SourceAlertEditorSheetTags.ERROR_DIALOG) == 1 }
        assertEquals(1, texts(SourceAlertEditor.Copy.REJECTED))
        tapInSheet(SourceAlertEditorSheetTags.ERROR_CONFIRM)
        assertTrue("the sheet stays", sheetOpen())
        assertEquals("with its draft", "1410", threshold())
        tapInSheet(SourceAlertEditorSheetTags.CANCEL)
        assertEquals("one POST, never resent", 1, server.writes().size)
        assertEquals("nothing remembered", emptyList<Pair<String, String>>(), store.writes.toList())

        server.answer = { null }
        tap(SourceAlertSectionTags.rowOpen(5))
        // Deleted elsewhere while the editor is open: the server answers the PUT 404 from its own rows.
        server.drop(5)
        type("1320")
        save()
        await("the gone row closes the edit") { !sheetOpen() }
        await("with the notice") { texts(SourceAlertEditor.Copy.REMOVED) == 1 }
        await("and the row leaves") { 5 !in shownIds() }
        assertEquals("one PUT", 1, server.writes().count { it.method == "PUT" })

        server.seed(*(10 until 40).map { rowJson(it, threshold = 1000.0 + it) }.toTypedArray())
        rule.runOnUiThread { owner.authoritative() }
        await("thirty rows") { shownIds().size == 30 }
        await("the cap") { count(SourceAlertSectionTags.ADD_AT_CAP) == 1 }
        assertEquals("no add at 30", 0, count(SourceAlertSectionTags.ADD_BUTTON))
        tap(SourceAlertSectionTags.toggle(10))
        await("the last row still switches") { server.writes().count { it.method == "PUT" && it.path == "$PATH/10" } == 1 }
        server.seed(rowJson(40, threshold = 1040.0))
        rule.runOnUiThread { owner.authoritative() }
        await("thirty-one rows, none cut") { shownIds().size == 31 && count(SourceAlertSectionTags.rowOpen(40)) == 1 }
        assertEquals("still no add", 0, count(SourceAlertSectionTags.ADD_BUTTON))
        tap(SourceAlertSectionTags.rowOpen(10))
        assertTrue("the last row still opens at the cap", sheetOpen())
        tapInSheet(SourceAlertEditorSheetTags.CANCEL)
        assertEquals("no POST beyond the refused one", 1, server.writes().count { it.method == "POST" })
    }

    @Test fun s85b_d1c_aDisabledRowsCancelAndReenable_anUnquotedExchange_theRepeatRoundTrip_andAFailedSwitch() {
        start(rowJson(50, source = "upbit", threshold = 1300.0, enabled = false))
        tap(SourceAlertSectionTags.rowOpen(50))
        tapInSheet(SourceAlertEditorSheetTags.CANCEL)
        assertEquals("a cancel sends nothing", 0, server.writes().size)
        tap(SourceAlertSectionTags.rowOpen(50))
        save()
        await("the re-enable settled") { !sheetOpen() && server.writes().size == 1 }
        assertEquals("only the re-enable", mapOf("is_enabled" to "true"), body(server.writes().single()))

        tap(SourceAlertSectionTags.ADD_BUTTON)
        tapInSheet(SourceAlertEditorSheetTags.source("coinone"))
        assertEquals("an exchange with no quote keeps the typed text", "1401", threshold())
        assertEquals("현재 시세를 확인할 수 없습니다", textOf(SourceAlertEditorSheetTags.CURRENT_RATE))
        type("1450")
        save()
        assertTrue("no save without a quote", sheetOpen())
        assertEquals(SourceAlertEditor.Copy.NO_QUOTE, textOf(SourceAlertEditorSheetTags.HINT))
        assertEquals("no POST", 0, server.writes().count { it.method == "POST" })

        tapInSheet(SourceAlertEditorSheetTags.source("upbit"))
        repeat(RepeatInterval.FIVE_MINUTES)
        save()
        await("the repeating add settled") { !sheetOpen() && shownIds().size == 2 }
        val post = server.writes().single { it.method == "POST" }
        assertEquals("every five minutes", "300", body(post)["repeat_interval_sec"])
        val added = (shownIds() - 50).single()
        tap(SourceAlertSectionTags.rowOpen(added))
        repeat(null)
        save()
        await("the once edit settled") { !sheetOpen() && server.writes().count { it.method == "PUT" } == 2 }
        assertEquals("once is JSON null", mapOf("repeat_interval_sec" to "null"), body(server.writes().last()))

        server.answer = { r -> if (r.method == "PUT") 500 to """{"detail":"down"}""" else null }
        val before = server.writes().size
        tap(SourceAlertSectionTags.toggle(added))
        await("the failed switch was sent once") { server.writes().size == before + 1 }
        await("and rolled back") { rule.onAllNodes(hasTestTag(SourceAlertSectionTags.toggle(added)) and isOn()).fetchSemanticsNodes().size == 1 }
        assertEquals("the server row is unchanged", JsonPrimitive(true), server.row(added)?.get("is_enabled"))
    }

    private companion object {
        const val PATH = "/api/source-notification-settings"

        fun JsonObject.id(): Int = getValue("id").jsonPrimitive.int

        fun rowJson(id: Int, fields: Map<String, JsonElement> = emptyMap(), source: String = "upbit", threshold: Double = 1400.0,
                    enabled: Boolean = true): JsonObject = JsonObject(mapOf(
            "id" to JsonPrimitive(id), "user_id" to JsonPrimitive("A"), "source" to JsonPrimitive(source),
            "asset" to JsonPrimitive("usdt-krw"), "condition" to JsonPrimitive("below"), "threshold" to JsonPrimitive(threshold),
            "is_enabled" to JsonPrimitive(enabled), "triggered" to JsonPrimitive(false), "repeat_interval_sec" to JsonNull,
            "created_at" to JsonPrimitive("2026-11-22T09:%02d:00+09:00".format(id % 60)),
            "updated_at" to JsonPrimitive("2026-11-22T09:00:00+09:00"), "triggered_at" to JsonNull
        ) + fields)
    }

    /**
     * S8_5B_D3c: a save kept from a live sheet and run in the same Main runnable as the rate fullscreen's click reaches no owner,
     * so it leaves no reservation: with 29 rows, the next, normal add is the 30th and is accepted, and the cap follows.
     */
    @Test fun s85b_d3c_aKeptSaveUnderTheFullscreensClickLeavesNoReservation_andTheNext30thAddIsAccepted() {
        start(*(60 until 89).map { rowJson(it, threshold = 900.0 + it) }.toTypedArray())
        assertEquals("premise: 29 rows", 29, shownIds().size)
        tap(SourceAlertSectionTags.ADD_BUTTON)
        type("1420")
        val keptSave = rule.onNodeWithTag(SourceAlertEditorSheetTags.SAVE, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val fullscreen = rule.onNodeWithTag(com.jay.fxi.ui.premium.view.PremiumTopicTags.FULLSCREEN, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.runOnUiThread {
            fullscreen()
            keptSave()
        }
        rule.waitForIdle()
        assertEquals("the kept save reached no owner", 0, server.writes().size)
        rule.onNodeWithTag(com.jay.fxi.ui.premium.view.PremiumTopicTags.FULLSCREEN_CLOSE).performClick()
        rule.waitForIdle()
        await("back") { count(SourceAlertSectionTags.ADD_BUTTON) == 1 }
        rule.runOnUiThread { keptSave() }
        rule.waitForIdle()
        assertEquals("nor does it after the return", 0, server.writes().size)
        tap(SourceAlertSectionTags.ADD_BUTTON)
        type("1420")
        save()
        await("the 30th add settled") { !sheetOpen() && shownIds().size == 30 }
        assertEquals("one POST", 1, server.writes().count { it.method == "POST" })
        await("the cap") { count(SourceAlertSectionTags.ADD_AT_CAP) == 1 }
    }
}
