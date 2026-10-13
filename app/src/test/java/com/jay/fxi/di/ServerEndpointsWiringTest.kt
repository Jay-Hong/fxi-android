package com.jay.fxi.di

import java.io.File
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S5-4 slice 4a: both Retrofit graphs and the topic WebSocket take the bound server addresses, not ApiConfig directly. */
class ServerEndpointsWiringTest {

    private val endpoints = ServerEndpoints("http://127.0.0.1:9/", "ws://127.0.0.1:9/ws")

    @Test
    fun `S5-4 4a both Retrofit graphs use the bound REST address`() {
        val json = NetworkModule.provideWireJson()
        val client = OkHttpClient()
        assertEquals("public", "http://127.0.0.1:9/", NetworkModule.providePublicRetrofit(client, json, endpoints).baseUrl().toString())
        assertEquals("protected", "http://127.0.0.1:9/", NetworkModule.provideProtectedRetrofit(client, json, endpoints).baseUrl().toString())
    }

    @Test
    fun `S5-4 4a no consumer reads the production addresses directly`() {
        for (path in listOf("src/main/java/com/jay/fxi/di/NetworkModule.kt", "src/main/java/com/jay/fxi/data/remote/TopicRuntime.kt")) {
            val file = File(path)
            assertTrue("소스를 찾지 못했다: ${file.absolutePath}", file.isFile)
            assertFalse("$path reads ApiConfig", file.readText().contains("ApiConfig"))
        }
    }
}
