package com.jay.fxi.data.local

import androidx.activity.ComponentActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jay.fxi.data.remote.FXiApiService
import com.jay.fxi.data.repository.NewsRepositoryImpl
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.NewsItem
import com.jay.fxi.domain.model.NewsMetadata
import com.jay.fxi.domain.model.NewsResponse
import com.jay.fxi.ui.screen.NewsTabContent
import com.jay.fxi.ui.viewmodel.NewsViewModel
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class S102NewsCacheJourneyTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val cacheFile = File(target.filesDir, "news_cache.json")
    private val legacyBytes by lazy {
        readAsset("news_cache.json").also {
            assertEquals("test APK legacy fixture SHA-256", LEGACY_SHA256, sha256(it))
        }
    }
    private val legacyArticles by lazy {
        // Build expectations from the manifest's input table without the production decoder.
        val inputs = JSONObject(readAsset("news_cache.provenance.json").decodeToString()).getJSONArray("inputs")
        List(inputs.length()) { index ->
            val row = inputs.getJSONObject(index)
            NewsItem(
                id = row.getString("id"),
                title = row.getString("title"),
                link = if (row.isNull("link")) null else row.getString("link"),
                source = row.getString("source"),
                contentType = row.getString("content_type"),
                publishedAt = Instant.parse(row.getString("published_at"))
            )
        }
    }
    private val refreshedArticle = NewsItem(
        id = "n7",
        title = "재조회로 추가된 일반 기사",
        link = "https://example.org/news/n7",
        source = "합성통신",
        contentType = "external_link",
        publishedAt = Instant.parse("2025-01-02T03:04:00.123Z")
    )
    private val refreshedArticles by lazy { legacyArticles + refreshedArticle }

    private var tabVisible by mutableStateOf(false)
    private var renderPremium by mutableStateOf(false)

    @Test
    fun legacyCacheRendersFreeAndPremiumBeforeRefresh() = withCacheFile {
        cacheFile.writeBytes(legacyBytes)
        withNewsViewModel { viewModel, api ->
            assertLegacyBeforeTabEntry(viewModel, api)
            showTab(viewModel)
            assertSingleFetch(api)

            assertEquals("legacy SHA-256 while response is held", LEGACY_SHA256, sha256(cacheFile.readBytes()))
            assertFreeArticles(listOf("n1", "n6"))
            compose.runOnUiThread { renderPremium = true }
            assertList(legacyArticles.map { it.id }, legacyArticles, isPremium = true)
            assertSingleFetch(api)
        }
    }

    @Test
    fun refreshReplacesLegacyCacheWithAllSevenArticles() = withCacheFile {
        cacheFile.writeBytes(legacyBytes)
        withNewsViewModel { viewModel, api ->
            assertLegacyBeforeTabEntry(viewModel, api)
            showTab(viewModel)
            assertSingleFetch(api)
            assertEquals("legacy SHA-256 before replacement", LEGACY_SHA256, sha256(cacheFile.readBytes()))
            assertFreeArticles(listOf("n1", "n6"))

            assertRefreshAndReplacement(viewModel, api, LEGACY_SHA256)
        }
    }

    @Test
    fun truncatedCacheRecoversAfterFirstFreeTabFetch() = withCacheFile {
        val truncated = legacyBytes.copyOf(legacyBytes.size - 1)
        cacheFile.writeBytes(truncated)
        assertNull("truncated news cache is a miss", readCache())

        withNewsViewModel { viewModel, api ->
            assertTrue("VM starts without articles on a cache miss", viewModel.newsItems.value.isEmpty())
            assertTrue("cache miss does not fetch before tab entry", api.calls.isEmpty())
            showTab(viewModel)
            assertSingleFetch(api)

            compose.onNodeWithText("뉴스 불러오는 중...").assertIsDisplayed()
            for (article in refreshedArticles) assertTextCount(article.title, 0)
            assertTextCount(HEADLINE, 0)
            assertTextCount(SUBHEAD, 0)
            assertArrayEquals("truncated bytes while response is held", truncated, cacheFile.readBytes())

            assertRefreshAndReplacement(viewModel, api, sha256(truncated))
        }
    }

    @Test
    fun cacheLoaderDistinguishesFullListsEmptyListAndMisses() = withCacheFile {
        cacheFile.writeBytes(legacyBytes)
        assertEquals("legacy original", legacyArticles, readCache())
        val legacy = legacyBytes.decodeToString()
        val accepted = mapOf(
            "explicit null link" to legacy.replaceFirst(""""id":"n1",""", """"id":"n1","link":null,"""),
            "unknown object property" to legacy.replaceFirst(""""id":"n4",""", """"id":"n4","future_meta":{"edition":2},""")
        )
        for ((label, input) in accepted) {
            cacheFile.writeText(input)
            assertEquals(label, legacyArticles, readCache())
        }
        val rejected = mapOf(
            "missing required id" to legacy.replaceFirst(""""id":"n3",""", ""),
            "invalid published_at" to legacy.replaceFirst("2025-01-02T03:04:03.123Z", "not-an-instant"),
            "truncated array" to legacy.dropLast(1),
            "zero bytes" to "",
            "top-level object" to """{"news":$legacy}"""
        )
        for ((label, input) in rejected) {
            cacheFile.writeText(input)
            assertNull(label, readCache())
        }
        cacheFile.writeText("[]")
        assertEquals("valid empty cache", emptyList<NewsItem>(), readCache())
        assertTrue("delete the empty cache before testing absence", cacheFile.delete())
        assertNull("missing cache file", readCache())
    }

    /** Battery control: the test APK's fixture and manifest agree on device without CacheService, the VM or the UI. */
    @Test
    fun fixtureAndManifestAgreeOnDevice() {
        assertEquals("test APK legacy fixture SHA-256", LEGACY_SHA256, sha256(legacyBytes))
        assertEquals("manifest input ids", listOf("n1", "n2", "n3", "n4", "n5", "n6"), legacyArticles.map { it.id })
    }

    private fun assertLegacyBeforeTabEntry(viewModel: NewsViewModel, api: GatedNewsApi) {
        val articles = viewModel.newsItems.value
        assertEquals("S10-2 before tab entry: legacy cache item count", 6, articles.size)
        assertEquals("all six cached articles before tab entry", legacyArticles, articles)
        assertTrue("VM initialization made no API call", api.calls.isEmpty())
    }

    private fun assertSingleFetch(api: GatedNewsApi) {
        compose.waitUntil(TIMEOUT_MS) { api.calls.isNotEmpty() }
        assertEquals("free tab fetch count and arguments", listOf(100 to 24.0), api.calls.toList())
    }

    private fun assertRefreshAndReplacement(viewModel: NewsViewModel, api: GatedNewsApi, previousSha: String) {
        api.release.complete(Unit)
        val refreshed = runBlocking {
            withTimeout(TIMEOUT_MS) { viewModel.newsItems.first { it.size == refreshedArticles.size } }
        }
        assertEquals("all seven refreshed articles in the VM", refreshedArticles, refreshed)
        assertFreeArticles(listOf("n1", "n6", "n7"), refreshedArticles)
        assertSingleFetch(api)

        // The VM publishes before its IO write completes, so wait on actual reads of the file.
        val freshService = newCacheService()
        var reloaded: List<NewsItem>? = null
        runBlocking {
            withTimeoutOrNull(TIMEOUT_MS) {
                do {
                    reloaded = freshService.loadNewsCache()
                    if (reloaded?.size == refreshedArticles.size) break
                    delay(25)
                } while (true)
            }
        }
        assertEquals("S10-2 after refresh: fresh CacheService item count", 7, reloaded?.size ?: 0)
        assertEquals("all seven articles read from the replacement file", refreshedArticles, reloaded)
        assertNotEquals("cache file SHA-256 changed after refresh", previousSha, sha256(cacheFile.readBytes()))
    }

    private fun showTab(viewModel: NewsViewModel) {
        compose.runOnUiThread { tabVisible = true }
        compose.setContent {
            if (tabVisible) {
                val items by viewModel.newsItems.collectAsStateWithLifecycle()
                val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
                val error by viewModel.error.collectAsStateWithLifecycle()
                val refreshTrigger by viewModel.refreshTrigger.collectAsStateWithLifecycle()
                DisposableEffect(viewModel) {
                    viewModel.onTabAppear(isPremium = false)
                    onDispose { viewModel.onTabDisappear() }
                }
                NewsTabContent(
                    newsItems = items,
                    isLoading = isLoading,
                    error = error,
                    refreshTrigger = refreshTrigger,
                    isPremium = renderPremium,
                    isTabVisible = true,
                    isDetailOpen = false,
                    onRetry = { viewModel.retry(isPremium = false) },
                    onDetailOpen = {}
                )
            }
        }
    }

    private fun assertFreeArticles(ids: List<String>, articles: List<NewsItem> = legacyArticles) {
        assertList(ids, articles, isPremium = false)
    }

    private fun assertList(ids: List<String>, articles: List<NewsItem>, isPremium: Boolean) {
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(0)
        val bannerCount = if (isPremium) 0 else 1
        assertTextCount(HEADLINE, bannerCount)
        assertTextCount(SUBHEAD, bannerCount)

        // The lazy list's key indices also cover rows beyond the current viewport.
        val indexForKey = list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
        compose.runOnIdle {
            assertEquals("subscription banner index", if (isPremium) -1 else 0, indexForKey("non_premium_banner"))
            for (article in articles) {
                val expectedIndex = ids.indexOf(article.id)
                val expected = if (expectedIndex < 0) -1 else expectedIndex + bannerCount
                assertEquals("lazy list index for " + article.id, expected, indexForKey(article.id))
            }
        }
        for ((index, id) in ids.withIndex()) {
            list.performScrollToIndex(index + bannerCount)
            val title = articles.single { it.id == id }.title
            assertTextCount(title, 1)
            compose.onNodeWithText(title).assertIsDisplayed()
        }
        for (article in articles.filter { it.id !in ids }) assertTextCount(article.title, 0)
    }

    private fun assertTextCount(text: String, count: Int) {
        assertEquals("text count for $text", count, compose.onAllNodesWithText(text).fetchSemanticsNodes().size)
    }

    private fun withNewsViewModel(test: (NewsViewModel, GatedNewsApi) -> Unit) {
        val api = GatedNewsApi(refreshedArticles)
        val viewModel = compose.runOnUiThread { NewsViewModel(NewsRepositoryImpl(api, newCacheService())) }
        val job = viewModel.viewModelScope.coroutineContext.job
        try {
            val initialReads = job.children.toList()
            runBlocking { withTimeout(TIMEOUT_MS) { initialReads.joinAll() } }
            test(viewModel, api)
        } finally {
            try {
                compose.runOnUiThread { tabVisible = false }
                compose.waitForIdle()
            } finally {
                runBlocking { withTimeout(TIMEOUT_MS) { job.cancelAndJoin() } }
            }
        }
    }

    private fun withCacheFile(test: () -> Unit) {
        val before = if (cacheFile.exists()) cacheFile.readBytes() else null
        try {
            test()
        } finally {
            if (before != null) {
                cacheFile.writeBytes(before)
            } else {
                assertTrue("restore original absence of news_cache.json", !cacheFile.exists() || cacheFile.delete())
            }
        }
    }

    private fun newCacheService() = CacheService(target, NetworkModule.provideStorageJson())

    private fun readCache() = runBlocking { withTimeout(TIMEOUT_MS) { newCacheService().loadNewsCache() } }

    private fun readAsset(name: String): ByteArray =
        instrumentation.context.assets.open("fixtures/v122/$name").use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class GatedNewsApi(articles: List<NewsItem>) : FXiApiService {
        val calls = CopyOnWriteArrayList<Pair<Int, Double>>()
        val release = CompletableDeferred<Unit>()
        private val response = NewsResponse(articles, NewsMetadata(articles.size, 24.0, "2025-01-02T03:05:00Z"))

        override suspend fun getNews(limit: Int, hours: Double): NewsResponse {
            calls.add(limit to hours)
            release.await()
            return response
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val LEGACY_SHA256 = "8d41534c682d52f76e38c2c943f224f201c3521212851d264aedf0129d87e39b"
        const val HEADLINE = "구독 시 뉴스속보·환율 보고서 보기"
        const val SUBHEAD = "일부 콘텐츠는 구독자에게만 제공됩니다"
    }
}
