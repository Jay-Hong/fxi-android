package com.jay.fxi.admission

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseAdmissionInterceptorTest {
    @Test
    fun `OFF stops before the next interceptor or any socket work`() {
        var downstreamReached = false
        val client = client(
            admitted = false,
            onTerminal = { downstreamReached = true }
        )

        val error = assertThrows(IOException::class.java) {
            client.newCall(request()).execute()
        }

        assertEquals("D24 release admission is OFF", error.message)
        assertFalse(downstreamReached)
    }

    @Test
    fun `ON admits the request to the next interceptor`() {
        var downstreamReached = false
        val client = client(
            admitted = true,
            onTerminal = { downstreamReached = true }
        )

        client.newCall(request()).execute().use { response ->
            assertEquals(204, response.code)
        }

        assertTrue(downstreamReached)
    }

    private fun client(admitted: Boolean, onTerminal: () -> Unit): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(ReleaseAdmissionInterceptor { admitted })
            .addInterceptor(
                Interceptor { chain ->
                    onTerminal()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(204)
                        .message("No Content")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            )
            .build()

    private fun request(): Request = Request.Builder()
        // This address must never be contacted; the terminal interceptor synthesizes ON.
        .url("http://127.0.0.1:1/d24")
        .build()
}
