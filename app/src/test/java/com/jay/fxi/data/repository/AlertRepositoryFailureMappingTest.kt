package com.jay.fxi.data.repository

import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRepositoryFailureMappingTest {

    @Test
    fun onlyKnownNotFound_canTriggerLocalRemovalPath() {
        val known = failure(AuthenticatedFailureKind.KNOWN_NOT_FOUND)
        val unknown = failure(AuthenticatedFailureKind.UNKNOWN_NOT_FOUND)

        val knownMapped = mapAlertHttpFailure(known)
        val unknownMapped = mapAlertHttpFailure(unknown)

        assertTrue(knownMapped is AlertNotFoundException)
        assertFalse(unknownMapped is AlertNotFoundException)
        assertSame(known, knownMapped.httpFailure)
        assertSame(unknown, unknownMapped.httpFailure)
    }

    private fun failure(kind: AuthenticatedFailureKind) = AuthenticatedHttpFailure(
        statusCode = 404,
        headers = Headers.headersOf(),
        rawBody = "body".toByteArray(),
        error = null,
        detail = JsonPrimitive("detail"),
        kind = kind
    )
}
