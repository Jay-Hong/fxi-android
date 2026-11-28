package com.jay.fxi.ui.screen

import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jay.fxi.domain.model.NewsItem
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class S10NewsTabContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val externalIntents = mutableListOf<Intent>()
    private val detailUrls = mutableListOf<String>()
    private val kbArticle = article("kb", "KB 기사", "https://FX.KBSTAR.COM/article")
    private val pdfArticle = article("pdf", "보고서 기사", "http://RREPORT.EINFOMAX.CO.KR/report.pdf", "report_pdf")
    private val publicArticle = article(
        "public", "일반 기사", "https://news.example/fx.kbstar.com?source=rreport.einfomax.co.kr"
    )
    private val mixedArticles = listOf(pdfArticle, publicArticle, kbArticle)

    @Test
    fun freeMixedListHidesBothPremiumHostsAndShowsBannerBeforePublicRow() {
        show(mixedArticles)

        assertTextCount(kbArticle.title, 0)
        assertTextCount(pdfArticle.title, 0)
        assertTextCount(publicArticle.title, 1)
        assertBannerCount(1)
        compose.onNodeWithText(publicArticle.title).assertIsDisplayed()
        assertTrue("subscription banner appears before the public row", top(SUBHEAD) < top(publicArticle.title))
    }

    @Test
    fun freeAllPremiumListShowsOnlyBannerDespiteLoadingAndError() {
        show(listOf(kbArticle, pdfArticle), isLoading = true, error = FETCH_ERROR)

        assertBannerCount(1)
        for (text in listOf(kbArticle.title, pdfArticle.title, EMPTY, LOADING, ERROR, FETCH_ERROR)) {
            assertTextCount(text, 0)
        }
    }

    @Test
    fun premiumMixedListShowsAllRowsInSourceOrderWithoutBanner() {
        show(mixedArticles, isPremium = true)

        assertBannerCount(0)
        for (item in mixedArticles) {
            assertTextCount(item.title, 1)
            compose.onNodeWithText(item.title).assertIsDisplayed()
        }
        val rowTops = mixedArticles.map { top(it.title) }
        assertTrue("article rows stay in source order", rowTops.zipWithNext().all { (first, next) -> first < next })
    }

    @Test
    fun emptySourceShowsExistingEmptyState() {
        show(emptyList())

        assertTextCount(EMPTY, 1)
        assertBannerCount(0)
    }

    @Test
    fun emptySourceCanStillShowLoading() {
        show(emptyList(), isLoading = true, error = FETCH_ERROR)

        assertTextCount(LOADING, 1)
        assertTextCount(ERROR, 0)
        assertTextCount(EMPTY, 0)
        assertBannerCount(0)
    }

    @Test
    fun emptySourceCanStillShowErrorAndRetry() {
        var retries = 0
        show(emptyList(), error = FETCH_ERROR, onRetry = { retries++ })

        assertTextCount(ERROR, 1)
        assertTextCount(FETCH_ERROR, 1)
        assertTextCount(EMPTY, 0)
        assertBannerCount(0)
        compose.onNodeWithText("다시 시도").performClick()
        compose.runOnIdle { assertEquals("retry count", 1, retries) }
    }

    @Test
    fun pdfTapStartsOneViewIntentAndDoesNotOpenDetail() {
        show(listOf(pdfArticle), isPremium = true)

        compose.onNodeWithText(pdfArticle.title).performClick()
        compose.runOnIdle {
            assertEquals("PDF external Intent count", 1, externalIntents.size)
            val intent = externalIntents.single()
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(pdfArticle.link, intent.dataString)
            assertEquals("PDF detail callback count", 0, detailUrls.size)
        }
    }

    @Test
    fun ordinaryLinkTapOpensOneDetailAndDoesNotStartIntent() {
        show(listOf(publicArticle))

        compose.onNodeWithText(publicArticle.title).performClick()
        compose.runOnIdle {
            assertEquals("ordinary detail URLs", listOf(publicArticle.link), detailUrls)
            assertEquals("ordinary external Intent count", 0, externalIntents.size)
        }
    }

    @Test
    fun rowWithoutLinkDoesNotOpenDetailOrStartIntent() {
        val unlinked = publicArticle.copy(link = null)
        show(listOf(unlinked))

        compose.onNodeWithText(unlinked.title).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals("unlinked detail callback count", 0, detailUrls.size)
            assertEquals("unlinked external Intent count", 0, externalIntents.size)
        }
    }

    @Test
    fun freeBannerTextDoesNotOverlapOrClipAtLargeFontScale() {
        show(
            listOf(kbArticle, pdfArticle),
            modifier = Modifier.width(240.dp),
            fontScale = 2f
        )

        assertBannerCount(1)
        val headline = compose.onNodeWithText(HEADLINE)
        val subhead = compose.onNodeWithText(SUBHEAD)
        assertTrue(
            "subscription texts do not overlap",
            headline.getUnclippedBoundsInRoot().bottom <= subhead.getUnclippedBoundsInRoot().top
        )

        for (text in listOf(HEADLINE, SUBHEAD)) {
            val interaction = compose.onNodeWithText(text).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            interaction.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayouts ->
                assertTrue("$text has text layout", getLayouts(layouts))
            }
            assertFalse("$text fits within its text layout", layouts.single().hasVisualOverflow)
            val node = interaction.fetchSemanticsNode()
            val bounds = node.boundsInRoot
            val position = node.positionInRoot
            assertTrue(
                "$text is not clipped by the viewport",
                bounds.left <= position.x && bounds.right >= position.x + node.size.width &&
                    bounds.top <= position.y && bounds.bottom >= position.y + node.size.height
            )
        }
    }

    private fun show(
        items: List<NewsItem>,
        isPremium: Boolean = false,
        isLoading: Boolean = false,
        error: String? = null,
        onRetry: () -> Unit = {},
        modifier: Modifier = Modifier,
        fontScale: Float = 1f
    ) {
        val context = object : ContextWrapper(compose.activity) {
            override fun startActivity(intent: Intent) = startActivity(intent, null)

            override fun startActivity(intent: Intent, options: Bundle?) {
                externalIntents += Intent(intent)
            }
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalDensity provides Density(density.density, fontScale)
            ) {
                Box(modifier) {
                    NewsTabContent(
                        newsItems = items,
                        isLoading = isLoading,
                        error = error,
                        refreshTrigger = 0,
                        isPremium = isPremium,
                        isTabVisible = true,
                        isDetailOpen = false,
                        onRetry = onRetry,
                        onDetailOpen = { detailUrls.add(it) }
                    )
                }
            }
        }
    }

    private fun assertTextCount(text: String, expected: Int) {
        val nodes = compose.onAllNodesWithText(text).fetchSemanticsNodes()
        assertEquals("$text count", expected, nodes.size)
    }

    private fun assertBannerCount(expected: Int) {
        assertTextCount(HEADLINE, expected)
        assertTextCount(SUBHEAD, expected)
    }

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    private fun article(id: String, title: String, link: String, contentType: String = "external_link") = NewsItem(
        id = id,
        title = title,
        link = link,
        source = "합성 뉴스",
        contentType = contentType,
        publishedAt = Instant.parse("2026-11-27T00:00:00Z")
    )

    private companion object {
        const val HEADLINE = "구독 시 뉴스속보·환율 보고서 보기"
        const val SUBHEAD = "일부 콘텐츠는 구독자에게만 제공됩니다"
        const val EMPTY = "뉴스가 없습니다"
        const val LOADING = "뉴스 불러오는 중..."
        const val ERROR = "뉴스를 불러올 수 없습니다"
        const val FETCH_ERROR = "합성 조회 오류"
    }
}
