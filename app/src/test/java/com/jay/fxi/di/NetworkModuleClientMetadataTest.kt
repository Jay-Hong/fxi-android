package com.jay.fxi.di

import com.jay.fxi.data.remote.ClientMetadataInterceptor
import okhttp3.Interceptor
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-039 REST 배선 잠금 — provideOkHttpClient가 ClientMetadataInterceptor를 실제로 연결하는지.
 * (interceptor 단위 테스트와 달리, NetworkModule의 .addInterceptor 라인을 제거하면 이 테스트가 실패한다.
 *  codex 지적: interceptor 자체만 검사하면 배선 제거를 못 잡음.)
 */
class NetworkModuleClientMetadataTest {

    @Test
    fun okHttpClient_wiresClientMetadataInterceptor() {
        val noopAuth = Interceptor { it.proceed(it.request()) }
        val client = NetworkModule.provideOkHttpClient(noopAuth)
        assertTrue(
            "provideOkHttpClient가 ClientMetadataInterceptor를 연결해야 함",
            client.interceptors.any { it is ClientMetadataInterceptor }
        )
    }
}
