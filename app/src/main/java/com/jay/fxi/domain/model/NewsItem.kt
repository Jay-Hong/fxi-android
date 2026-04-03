package com.jay.fxi.domain.model

import android.net.Uri
import com.jay.fxi.util.InstantSerializer
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 뉴스 아이템 (도메인 모델 + 직렬화)
 *
 * 기존 ExchangeRate, AlertSetting과 동일한 패턴:
 * domain model에 @Serializable을 붙여 Retrofit 응답에 직접 사용.
 */
@Serializable
data class NewsItem(
    val id: String,
    val title: String,
    val link: String? = null,
    val source: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("published_at")
    @Serializable(with = InstantSerializer::class)
    val publishedAt: Instant
) {
    val resolvedContentType: NewsContentType?
        get() = NewsContentType.fromCode(contentType)

    val isTappable: Boolean get() = link != null

    val shouldOpenExternally: Boolean
        get() = resolvedContentType == NewsContentType.REPORT_PDF

    val decodedTitle: String
        get() = title
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    /**
     * 프리미엄 콘텐츠 판정 (제품 노출 정책, 보안 아님)
     * - fx.kbstar.com: KB 전용 기사
     * - rreport.einfomax.co.kr: 은행 보고서 PDF
     */
    val isPremiumContent: Boolean
        get() {
            val url = link ?: return false
            val host = try { Uri.parse(url).host } catch (_: Exception) { null } ?: return false
            return host in PREMIUM_HOSTS
        }

    /**
     * 상대 시간 표시 (한국어)
     * refreshTrigger를 받아 Compose recomposition을 강제
     */
    fun timeAgo(@Suppress("UNUSED_PARAMETER") refreshTrigger: Int): String {
        val now = Clock.System.now()
        val diff = Duration.between(publishedAt.toJavaInstant(), now.toJavaInstant())

        val seconds = diff.seconds
        return when {
            seconds < 60 -> "방금 전"
            seconds < 3600 -> "${seconds / 60}분 전"
            seconds < 86400 -> "${seconds / 3600}시간 전"
            else -> {
                val days = seconds / 86400
                if (days == 1L) "어제"
                else {
                    val zdt = publishedAt.toJavaInstant().atZone(ZoneId.of("Asia/Seoul"))
                    DateTimeFormatter.ofPattern("M/d").format(zdt)
                }
            }
        }
    }

    companion object {
        private val PREMIUM_HOSTS = setOf(
            "fx.kbstar.com",
            "rreport.einfomax.co.kr"
        )
    }
}

@Serializable
data class NewsMetadata(
    @SerialName("returned_count") val returnedCount: Int,
    @SerialName("window_hours") val windowHours: Double,
    @SerialName("responded_at") val respondedAt: String
)

@Serializable
data class NewsResponse(
    val news: List<NewsItem>,
    val metadata: NewsMetadata
)
