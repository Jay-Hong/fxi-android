package com.jay.fxi.domain.model

import com.jay.fxi.ui.screen.NewsListState
import com.jay.fxi.ui.screen.resolveNewsListState
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S10NewsPolicyTest {
    private val hiddenArticle = article("hidden", "report_pdf")
    private val firstPublicArticle = article("public-1", "external_link")
    private val secondPublicArticle = article("public-2", "external_link")

    @Test
    fun acceptsBothLowercasePremiumHosts() {
        assertTrue("lowercase KB host", isPremiumNewsHost("fx.kbstar.com"))
        assertTrue("lowercase report host", isPremiumNewsHost("rreport.einfomax.co.kr"))
    }

    @Test
    fun acceptsBothMixedCasePremiumHosts() {
        assertTrue("mixed-case KB host", isPremiumNewsHost("Fx.KbStAr.CoM"))
        assertTrue("mixed-case report host", isPremiumNewsHost("RrEpOrT.EiNfOmAx.Co.Kr"))
    }

    @Test
    fun rejectsHostImpostors() {
        for (host in listOf(
            "xfx.kbstar.com",
            "fx.kbstar.com.evil.example",
            "sub.fx.kbstar.com",
            "xrreport.einfomax.co.kr",
            "rreport.einfomax.co.kr.evil.example",
            "sub.rreport.einfomax.co.kr",
            "fx.kbstar.com."
        )) {
            assertFalse("exact host required: $host", isPremiumNewsHost(host))
        }
    }

    @Test
    fun rejectsNonAsciiHostVariants() {
        assertFalse("dotted capital I must not match ASCII i", isPremiumNewsHost("rreport.eİnfomax.co.kr"))
        assertFalse("Kelvin sign must not match ASCII k", isPremiumNewsHost("fx.Kbstar.com"))
    }

    @Test
    fun rejectsAbsentAndOrdinaryHosts() {
        assertFalse("null host", isPremiumNewsHost(null))
        assertFalse("empty host", isPremiumNewsHost(""))
        assertFalse("ordinary host", isPremiumNewsHost("news.example"))
    }

    @Test
    fun emptySourceChoosesLoadingBeforeErrorThenEmpty() {
        for (isPremium in listOf(false, true)) {
            fun state(isLoading: Boolean, error: String?) =
                resolveNewsListState(emptyList(), emptyList(), isLoading, error, isPremium)

            assertEquals("empty source while loading", NewsListState.Loading, state(true, null))
            assertEquals("empty source with an error while loading", NewsListState.Loading, state(true, FETCH_ERROR))
            assertEquals("empty source after an error", NewsListState.Error(FETCH_ERROR), state(false, FETCH_ERROR))
            assertEquals("empty source after success", NewsListState.Empty, state(false, null))
        }
    }

    @Test
    fun freeSourceWithNoVisibleRowsStillShowsBanner() {
        assertContentForEveryFetchState(
            newsItems = listOf(hiddenArticle),
            visibleItems = emptyList(),
            isPremium = false,
            expected = NewsListState.Content(emptyList(), showSubscriptionBanner = true)
        )
    }

    @Test
    fun freeSourceWithVisibleRowsShowsBannerAndThoseRows() {
        for (visible in listOf(listOf(firstPublicArticle), listOf(firstPublicArticle, secondPublicArticle))) {
            assertContentForEveryFetchState(
                newsItems = listOf(hiddenArticle) + visible,
                visibleItems = visible,
                isPremium = false,
                expected = NewsListState.Content(visible, showSubscriptionBanner = true)
            )
        }
    }

    @Test
    fun premiumSourceShowsAllRowsWithoutBanner() {
        val source = listOf(secondPublicArticle, hiddenArticle, firstPublicArticle)
        assertContentForEveryFetchState(
            newsItems = source,
            visibleItems = source,
            isPremium = true,
            expected = NewsListState.Content(source, showSubscriptionBanner = false)
        )
    }

    @Test
    fun pdfContentTypeOpensExternally() {
        assertTrue("report_pdf opens externally", hiddenArticle.shouldOpenExternally)
    }

    @Test
    fun externalLinkAndUnknownTypesUseDetailWhenTappable() {
        val linked = firstPublicArticle.copy(link = "https://news.example/article")
        val unknown = linked.copy(contentType = "unknown")
        assertFalse("external_link uses detail", linked.shouldOpenExternally)
        assertFalse("unknown type uses detail", unknown.shouldOpenExternally)
        assertTrue("link is tappable", linked.isTappable)
        assertFalse("missing link is not tappable", firstPublicArticle.isTappable)
    }

    private fun assertContentForEveryFetchState(
        newsItems: List<NewsItem>,
        visibleItems: List<NewsItem>,
        isPremium: Boolean,
        expected: NewsListState.Content
    ) {
        for (isLoading in listOf(false, true)) {
            for (error in listOf(null, FETCH_ERROR)) {
                assertEquals(
                    "nonempty source: premium=$isPremium, visible=${visibleItems.size}, loading=$isLoading, error=$error",
                    expected,
                    resolveNewsListState(newsItems, visibleItems, isLoading, error, isPremium)
                )
            }
        }
    }

    private fun article(id: String, contentType: String) = NewsItem(
        id = id,
        title = id,
        source = "합성 뉴스",
        contentType = contentType,
        publishedAt = Instant.parse("2026-11-27T00:00:00Z")
    )

    private companion object {
        const val FETCH_ERROR = "합성 조회 오류"
    }
}
