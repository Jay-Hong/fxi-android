package com.jay.fxi.data.remote

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * ADR-039 배선 통합 — ClientMetadataInterceptor가 실제 요청에 X-Client-*를 싣는지.
 * (headers() 헬퍼 단위 테스트와 달리, interceptor를 거친 요청을 terminal interceptor로 캡처해
 * 실제 전달 여부를 검증한다. NetworkModule/WS가 이 interceptor를 연결한다.)
 */
class ClientMetadataInterceptorTest {

    @Test
    fun interceptor_attachesClientHeadersToRequest() {
        var captured: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(ClientMetadataInterceptor())
            .addInterceptor { chain ->
                captured = chain.request()   // ClientMetadataInterceptor 이후의 요청
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body("{}".toResponseBody(null))
                    .build()
            }
            .build()

        client.newCall(Request.Builder().url("https://fxi.kr/api/rates").build())
            .execute().close()

        assertNotNull(captured)
        assertEquals("android", captured?.header("X-Client-Platform"))
        assertNotNull(captured?.header("X-Client-Version"))
        assertNotNull(captured?.header("X-Client-Build"))
    }
}
