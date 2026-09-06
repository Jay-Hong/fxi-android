package com.jay.fxi.data.remote

import java.io.IOException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.ResponseBody
import retrofit2.Response

/** Endpoint identity used when interpreting the server's existing, non-uniform error bodies. */
internal enum class AuthenticatedEndpoint {
    REGISTER_DEVICE,
    UNREGISTER_DEVICE,
    NOTIFICATION_SETTINGS,
    UPDATE_NOTIFICATION_SETTING,
    DELETE_NOTIFICATION_SETTING,
    DELETE_USER,

    /**
     * `GET /api/entitlements`.
     *
     * Carries no typed premium error body: every decided or pending answer is HTTP 200, and its
     * only non-200 paths come from token verification. A 403 here is therefore
     * [AuthenticatedFailureKind.UNKNOWN_AUTHORIZATION] by the default branch, which is what makes
     * the reducer re-ask with `fresh_premium=true` instead of treating it as a rejection.
     */
    ENTITLEMENTS,

    /** Firebase-only hourly snapshot; premium-required errors are not a known policy here. */
    FREE_SNAPSHOT
}

enum class AuthenticatedFailureKind {
    AUTHENTICATION,
    KNOWN_AUTHORIZATION,
    UNKNOWN_AUTHORIZATION,
    KNOWN_NOT_FOUND,
    UNKNOWN_NOT_FOUND,
    OTHER_HTTP
}

/**
 * Lossless transport evidence for a non-successful protected REST response.
 *
 * The raw body is copied before Retrofit/OkHttp can close it. Parsed fields are advisory views of
 * that byte sequence; callers must not invent a stable common error code that the server lacks.
 */
class AuthenticatedHttpFailure internal constructor(
    val statusCode: Int,
    val headers: Headers,
    rawBody: ByteArray,
    val error: String?,
    val detail: JsonElement?,
    val kind: AuthenticatedFailureKind
) {
    private val preservedBody = rawBody.copyOf()

    val retryAfter: String?
        get() = headers["Retry-After"]

    val rawBodyText: String
        get() = preservedBody.toString(Charsets.UTF_8)

    fun rawBodyBytes(): ByteArray = preservedBody.copyOf()
}

/** HTTP exception that retains status, headers, Retry-After and the original server body. */
class AuthenticatedApiException(
    val failure: AuthenticatedHttpFailure
) : IOException("Protected API returned HTTP ${failure.statusCode}")

/** A successful HTTP response whose preserved body does not satisfy its wire schema. */
class AuthenticatedBodyDecodingException internal constructor(
    val response: AuthenticatedHttpResponse<ByteArray>,
    cause: Throwable
) : IOException(
    "Protected API returned an invalid successful body (HTTP ${response.statusCode})",
    cause
)

/** App-facing response for protected endpoints, including the exact success or failure body. */
class AuthenticatedHttpResponse<T> internal constructor(
    val statusCode: Int,
    val headers: Headers,
    val body: T?,
    val failure: AuthenticatedHttpFailure?,
    rawBody: ByteArray
) {
    private val preservedBody = rawBody.copyOf()

    val isSuccessful: Boolean
        get() = statusCode in 200..299

    val retryAfter: String?
        get() = headers["Retry-After"]

    val rawBodyText: String
        get() = preservedBody.toString(Charsets.UTF_8)

    fun code(): Int = statusCode

    fun rawBodyBytes(): ByteArray = preservedBody.copyOf()

    internal fun requireBody(endpoint: String): T {
        failure?.let { throw AuthenticatedApiException(it) }
        if (!isSuccessful) {
            throw IOException("$endpoint returned HTTP $statusCode without failure evidence")
        }
        return body ?: throw IOException("$endpoint returned an empty successful body")
    }

    internal fun <R> withBody(value: R?): AuthenticatedHttpResponse<R> =
        AuthenticatedHttpResponse(
            statusCode = statusCode,
            headers = headers,
            body = value,
            failure = failure,
            rawBody = preservedBody
        )
}

internal fun Response<ResponseBody>.preserve(
    endpoint: AuthenticatedEndpoint
): AuthenticatedHttpResponse<ByteArray> {
    val responseBody = if (isSuccessful) body() else errorBody()
    val rawBody = responseBody?.use { it.bytes() } ?: byteArrayOf()
    val parsed = rawBody.parseObjectOrNull()
    val error = parsed?.get("error")?.stringOrNull()
    val detail = parsed?.get("detail")
    val failure = if (isSuccessful) {
        null
    } else {
        AuthenticatedHttpFailure(
            statusCode = code(),
            headers = headers(),
            rawBody = rawBody,
            error = error,
            detail = detail,
            kind = classifyFailure(endpoint, code(), error, detail)
        )
    }
    return AuthenticatedHttpResponse(
        statusCode = code(),
        headers = headers().newBuilder().build(),
        body = rawBody.takeIf { isSuccessful },
        failure = failure,
        rawBody = rawBody
    )
}

internal fun AuthenticatedHttpResponse<ByteArray>.asUnit(): AuthenticatedHttpResponse<Unit> =
    withBody(if (isSuccessful) Unit else null)

internal inline fun <reified T> AuthenticatedHttpResponse<ByteArray>.decodeSuccess(
    json: Json
): AuthenticatedHttpResponse<T> {
    if (!isSuccessful) return withBody(null)
    return try {
        val text = rawBodyBytes().decodeToString(throwOnInvalidSequence = true)
        withBody(json.decodeFromString<T>(text))
    } catch (error: Exception) {
        throw AuthenticatedBodyDecodingException(this, error)
    }
}

private fun ByteArray.parseObjectOrNull(): JsonObject? = runCatching {
    Json.parseToJsonElement(decodeToString(throwOnInvalidSequence = true)) as? JsonObject
}.getOrNull()

private fun JsonElement.stringOrNull(): String? = runCatching { jsonPrimitive.content }.getOrNull()

private fun classifyFailure(
    endpoint: AuthenticatedEndpoint,
    statusCode: Int,
    error: String?,
    detail: JsonElement?
): AuthenticatedFailureKind {
    val detailText = detail?.stringOrNull()
    return when (statusCode) {
        401 -> AuthenticatedFailureKind.AUTHENTICATION
        403 -> if (
            endpoint in NOTIFICATION_ENDPOINTS &&
            error == null &&
            detailText == "Premium subscription required"
        ) {
            AuthenticatedFailureKind.KNOWN_AUTHORIZATION
        } else {
            AuthenticatedFailureKind.UNKNOWN_AUTHORIZATION
        }
        404 -> if (
            (endpoint == AuthenticatedEndpoint.UNREGISTER_DEVICE &&
                error == null && detailText == "Device not found") ||
            (endpoint == AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING &&
                error == null && detailText == "Setting not found")
        ) {
            AuthenticatedFailureKind.KNOWN_NOT_FOUND
        } else {
            AuthenticatedFailureKind.UNKNOWN_NOT_FOUND
        }
        else -> AuthenticatedFailureKind.OTHER_HTTP
    }
}

private val NOTIFICATION_ENDPOINTS = setOf(
    AuthenticatedEndpoint.NOTIFICATION_SETTINGS,
    AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING,
    AuthenticatedEndpoint.DELETE_NOTIFICATION_SETTING
)
