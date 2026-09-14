package com.jay.fxi.di

import com.jay.fxi.admission.ReleaseAdmissionInterceptor
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.ClientMetadataInterceptor
import com.jay.fxi.data.remote.FXiApiService
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import okhttp3.Authenticator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT

/**
 * ADR-039 REST 배선 잠금 — provideOkHttpClient가 ClientMetadataInterceptor를 실제로 연결하는지.
 * (interceptor 단위 테스트와 달리, NetworkModule의 .addInterceptor 라인을 제거하면 이 테스트가 실패한다.
 *  codex 지적: interceptor 자체만 검사하면 배선 제거를 못 잡음.)
 */
class NetworkModuleClientMetadataTest {

    @Test
    fun protectedClient_wiresAuthAndMutationPolicy_withoutChangingPublicReadPolicy() {
        val provider = AuthTokenProvider(
            object : AuthTokenSource {
                override fun currentIdentity(): AuthIdentity? = null
                override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean) =
                    error("not used")
            }
        )
        val authInterceptor = AuthSnapshotInterceptor(provider)
        val useInterceptor = TopicUseNetworkInterceptor(provider)
        val client = NetworkModule.provideProtectedOkHttpClient(authInterceptor, useInterceptor)
        assertEquals(
            "D24 admission must be the first REST interceptor",
            ReleaseAdmissionInterceptor::class,
            client.interceptors.first()::class
        )
        assertTrue(
            "provideOkHttpClient가 ClientMetadataInterceptor를 연결해야 함",
            client.interceptors.any { it is ClientMetadataInterceptor }
        )
        val authIndex = client.interceptors.indexOfFirst { it === authInterceptor }
        val metadataIndex = client.interceptors.indexOfFirst { it is ClientMetadataInterceptor }
        val mutationIndex = client.interceptors.indexOfFirst { it is MutationOneShotInterceptor }
        assertTrue(
            "protected client must wire the exact explicit-snapshot interceptor instance",
            authIndex >= 0
        )
        assertTrue(
            "auth binding must run after metadata and before mutation replay protection",
            metadataIndex in 0 until authIndex && authIndex < mutationIndex
        )
        assertEquals(
            "mutation one-shot guard must be closest to OkHttp retry/follow-up",
            MutationOneShotInterceptor::class,
            client.interceptors.last()::class
        )
        assertSame(
            "the use check must be the exact instance, and the last network interceptor, so it sees every exchange",
            useInterceptor,
            client.networkInterceptors.last()
        )
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(Authenticator.NONE, client.proxyAuthenticator)

        val publicClient = NetworkModule.providePublicOkHttpClient()
        assertEquals(ReleaseAdmissionInterceptor::class, publicClient.interceptors.first()::class)
        assertTrue(publicClient.interceptors.any { it is ClientMetadataInterceptor })
        assertTrue(publicClient.retryOnConnectionFailure)
        assertTrue(publicClient.followRedirects)
        assertTrue(publicClient.followSslRedirects)
        assertFalse(publicClient.interceptors.any { it is AuthSnapshotInterceptor })
        assertFalse(publicClient.interceptors.any { it is MutationOneShotInterceptor })
        assertFalse(publicClient.networkInterceptors.any { it is TopicUseNetworkInterceptor })

        val protectedRetrofit = NetworkModule.provideProtectedRetrofit(
            client,
            NetworkModule.provideWireJson()
        )
        assertSame(
            "protected Retrofit must retain the hardened protected OkHttp client",
            client,
            protectedRetrofit.callFactory()
        )
    }

    @Test
    fun publicRetrofitSurface_remainsReadOnly() {
        val mutationAnnotations = setOf(
            POST::class.java,
            PUT::class.java,
            PATCH::class.java,
            DELETE::class.java
        )

        FXiApiService::class.java.declaredMethods.forEach { method ->
            assertTrue(
                "${method.name} must remain an explicit read operation",
                method.annotations.any { it.annotationClass.java == GET::class.java }
            )
            assertFalse(
                "${method.name} must move to ProtectedRest before becoming a mutation",
                method.annotations.any { it.annotationClass.java in mutationAnnotations }
            )
        }
    }
}
