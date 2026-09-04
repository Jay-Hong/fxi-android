package com.jay.fxi.data.remote

import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Response

class AuthenticatedHttpResponseTest {

    @Test
    fun knownAndGeneric404_areKeptDistinct() {
        val known = errorResponse(404, """{"detail":"Setting not found"}""")
            .preserve(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING)
        val generic = errorResponse(404, "<html>proxy miss</html>")
            .preserve(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING)

        assertEquals(AuthenticatedFailureKind.KNOWN_NOT_FOUND, known.failure!!.kind)
        assertEquals(AuthenticatedFailureKind.UNKNOWN_NOT_FOUND, generic.failure!!.kind)
        assertEquals("<html>proxy miss</html>", generic.failure!!.rawBodyText)
        assertNull(generic.failure!!.detail)
    }

    @Test
    fun knownAndUnknown403_areKeptDistinct() {
        val known = errorResponse(403, """{"detail":"Premium subscription required"}""")
            .preserve(AuthenticatedEndpoint.NOTIFICATION_SETTINGS)
        val unknown = errorResponse(403, """{"detail":"new policy"}""")
            .preserve(AuthenticatedEndpoint.NOTIFICATION_SETTINGS)

        assertEquals(AuthenticatedFailureKind.KNOWN_AUTHORIZATION, known.failure!!.kind)
        assertEquals(AuthenticatedFailureKind.UNKNOWN_AUTHORIZATION, unknown.failure!!.kind)
    }

    @Test
    fun rawBodyAndRepeatedRetryAfterHeaders_arePreserved() {
        val original = byteArrayOf(0, 1, 2, 0x7f)
        val response = errorResponse(429, original, listOf("3", "7"))
            .preserve(AuthenticatedEndpoint.NOTIFICATION_SETTINGS)
        val failure = response.failure!!

        assertEquals(listOf("3", "7"), failure.headers.values("Retry-After"))
        assertEquals("7", failure.retryAfter)
        assertArrayEquals(original, failure.rawBodyBytes())

        val callerCopy = failure.rawBodyBytes()
        callerCopy[0] = 99
        assertArrayEquals(original, failure.rawBodyBytes())
    }

    @Test
    fun successfulRawBody_isPreservedIndependentlyFromCallerCopies() {
        val original = byteArrayOf(0, 1, 2, 0x7f)
        val response = successResponse(200, original, listOf("3", "7"))
            .preserve(AuthenticatedEndpoint.REGISTER_DEVICE)
            .asUnit()

        assertEquals(listOf("3", "7"), response.headers.values("Retry-After"))
        assertArrayEquals(original, response.rawBodyBytes())

        val callerCopy = response.rawBodyBytes()
        callerCopy[0] = 99
        assertArrayEquals(original, response.rawBodyBytes())
    }

    @Test
    fun malformedUtf8Success_isRejectedWithoutLosingOriginalBytes() {
        val original = byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0x80.toByte())
        val response = successResponse(200, original, emptyList())
            .preserve(AuthenticatedEndpoint.NOTIFICATION_SETTINGS)

        val failure = assertThrows(AuthenticatedBodyDecodingException::class.java) {
            response.decodeSuccess<Map<String, String>>(com.jay.fxi.di.NetworkModule.provideWireJson())
        }

        assertArrayEquals(original, failure.response.rawBodyBytes())
    }

    private fun errorResponse(code: Int, body: String): Response<ResponseBody> =
        errorResponse(code, body.toByteArray(), emptyList())

    private fun errorResponse(
        code: Int,
        body: ByteArray,
        retryAfter: List<String>
    ): Response<ResponseBody> {
        val headers = buildMap<String, String> {}.toHeaders().newBuilder().apply {
            retryAfter.forEach { add("Retry-After", it) }
        }.build()
        return Response.error(
            body.toResponseBody(),
            okhttp3.Response.Builder()
                .request(Request.Builder().url("https://example.invalid/test").build())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .headers(headers)
                .body(body.toResponseBody())
                .build()
        )
    }

    private fun successResponse(
        code: Int,
        body: ByteArray,
        retryAfter: List<String>
    ): Response<ResponseBody> {
        val headers = emptyMap<String, String>().toHeaders().newBuilder().apply {
            retryAfter.forEach { add("Retry-After", it) }
        }.build()
        return Response.success(
            body.toResponseBody(),
            okhttp3.Response.Builder()
                .request(Request.Builder().url("https://example.invalid/test").build())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .headers(headers)
                .body(body.toResponseBody())
                .build()
        )
    }
}
