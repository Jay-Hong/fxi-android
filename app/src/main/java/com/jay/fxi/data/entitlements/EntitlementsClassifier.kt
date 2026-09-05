package com.jay.fxi.data.entitlements

import com.jay.fxi.data.remote.AuthenticatedApiException
import com.jay.fxi.data.remote.AuthenticatedBodyDecodingException
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.dto.EntitlementsResponse
import java.io.IOException

/**
 * Maps a `/api/entitlements` transport result onto a reducer input.
 *
 * The endpoint has no typed premium error body. `app/main.py:4660` returns 200 for every decided
 * or pending answer, and its only non-200 paths come from `verify_firebase_token`: 401, 403, 503.
 * The typed `premium_required` and `krx_entitlement_required` codes are **WebSocket topic
 * rejection codes** (`app/topic_wire.py:43-44`), so they enter the reducer from the WS path, not
 * from here. This classifier therefore never produces them.
 *
 * Every branch that is not a decided 200 becomes [EntitlementsOutcome.Indeterminate], which the
 * reducer is required to treat as "no new grant, existing grant preserved".
 */
object EntitlementsClassifier {

    fun classify(response: AuthenticatedHttpResponse<EntitlementsResponse>): EntitlementsOutcome {
        val failure = response.failure
        if (failure != null) {
            return EntitlementsOutcome.Indeterminate(
                reason = when (failure.kind) {
                    AuthenticatedFailureKind.AUTHENTICATION -> IndeterminateReason.AUTHENTICATION
                    AuthenticatedFailureKind.KNOWN_AUTHORIZATION,
                    AuthenticatedFailureKind.UNKNOWN_AUTHORIZATION ->
                        IndeterminateReason.UNTYPED_FORBIDDEN
                    else -> IndeterminateReason.TRANSIENT
                },
                retryAfterSeconds = failure.retryAfter?.toLongOrNull()
            )
        }
        if (!response.isSuccessful) {
            // A non-2xx with no preserved failure evidence is a protocol violation, not a decision.
            return EntitlementsOutcome.Indeterminate(IndeterminateReason.DECODE)
        }
        val body = response.body
            ?: return EntitlementsOutcome.Indeterminate(IndeterminateReason.DECODE)

        // Pending is checked before the active/inactive split: its `premium_active=false` is a
        // fail-closed placeholder, not an authoritative loss.
        if (body.premiumPending) {
            return EntitlementsOutcome.Pending(
                krxVisible = body.krxVisible,
                retryAfterSeconds = body.retryAfterSeconds?.toLong()
            )
        }
        return if (body.premiumActive) {
            EntitlementsOutcome.StableActive(krxVisible = body.krxVisible)
        } else {
            EntitlementsOutcome.StableInactive(krxVisible = body.krxVisible)
        }
    }

    /** Maps a thrown transport error onto the same vocabulary. */
    fun classify(error: Throwable): EntitlementsOutcome = when (error) {
        is AuthenticatedBodyDecodingException ->
            EntitlementsOutcome.Indeterminate(IndeterminateReason.DECODE)

        is AuthenticatedApiException -> EntitlementsOutcome.Indeterminate(
            reason = when (error.failure.kind) {
                AuthenticatedFailureKind.AUTHENTICATION -> IndeterminateReason.AUTHENTICATION
                AuthenticatedFailureKind.KNOWN_AUTHORIZATION,
                AuthenticatedFailureKind.UNKNOWN_AUTHORIZATION ->
                    IndeterminateReason.UNTYPED_FORBIDDEN
                else -> IndeterminateReason.TRANSIENT
            },
            retryAfterSeconds = error.failure.retryAfter?.toLongOrNull()
        )

        is IOException -> EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT)

        else -> EntitlementsOutcome.Indeterminate(IndeterminateReason.DECODE)
    }
}
