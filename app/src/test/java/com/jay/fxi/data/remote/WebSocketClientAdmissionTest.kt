package com.jay.fxi.data.remote

import com.jay.fxi.admission.ReleaseAdmissionInterceptor
import com.jay.fxi.util.WebSocketConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class WebSocketClientAdmissionTest {
    @Test
    fun `D24 admission is the first WebSocket interceptor`() {
        val client = buildWebSocketClient()

        assertEquals(ReleaseAdmissionInterceptor::class, client.interceptors.first()::class)
    }

    /** S4 D8: the client moved out of the removed legacy service with its interceptors and timeouts as they were. */
    @Test
    fun `D8 the moved client keeps its interceptors and timeouts`() {
        val client = buildWebSocketClient()

        assertEquals(listOf(ReleaseAdmissionInterceptor::class, ClientMetadataInterceptor::class), client.interceptors.map { it::class })
        assertEquals(WebSocketConfig.CONNECTION_TIMEOUT_MS.toInt(), client.connectTimeoutMillis)
        assertEquals(0, client.readTimeoutMillis)
        assertEquals(WebSocketConfig.CONNECTION_TIMEOUT_MS.toInt(), client.writeTimeoutMillis)
    }
}
