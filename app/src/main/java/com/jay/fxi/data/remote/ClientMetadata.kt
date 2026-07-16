package com.jay.fxi.data.remote

import com.jay.fxi.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 클라이언트 버전 관측 헤더 (ADR-039 첫 계측 슬라이스). 서버(nginx access log)가 신규 앱 트래픽을
 * 식별하고 legacy 잔여를 측정하는 보조 신호. **권한 판정에는 절대 사용 금지**(spoofable) — authz는
 * Firebase UID + 구독 상태. 토큰/원문 UID는 이 헤더에 넣지 않는다.
 */
object ClientMetadata {
    fun headers(): Map<String, String> = mapOf(
        "X-Client-Platform" to "android",
        "X-Client-Version" to BuildConfig.VERSION_NAME,
        "X-Client-Build" to BuildConfig.VERSION_CODE.toString()
    )
}

/** REST/WS 요청에 X-Client-* 헤더를 붙이는 interceptor (인증과 독립). */
class ClientMetadataInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
        ClientMetadata.headers().forEach { (field, value) -> builder.addHeader(field, value) }
        return chain.proceed(builder.build())
    }
}
