package com.jay.fxi.data.remote

import com.jay.fxi.admission.ReleaseAdmissionInterceptor
import org.junit.Assert.assertEquals
import org.junit.Test

class WebSocketServiceAdmissionTest {
    @Test
    fun `D24 admission is the first WebSocket interceptor`() {
        val client = buildWebSocketClient()

        assertEquals(ReleaseAdmissionInterceptor::class, client.interceptors.first()::class)
    }
}
