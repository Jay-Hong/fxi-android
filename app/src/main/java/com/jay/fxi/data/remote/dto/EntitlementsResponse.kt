package com.jay.fxi.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/entitlements` wire body.
 *
 * Mirrors `app/schemas.py::EntitlementsResponse` exactly:
 *  - `krx_visible` is required and is the single KRX signal (G3 ∧ G2 ∧ G1 ∧ premium). The client
 *    does not recompute the gate combination.
 *  - `premium_pending=true` arrives with HTTP 200, not 503, and carries a fail-closed
 *    `krx_visible=false` plus a suggested `retry_after_seconds`.
 *
 * `krx_visible` deliberately has no Kotlin default: WireJson forbids defaults on required fields,
 * so an envelope missing it is a decode error rather than a silent `false`.
 */
@Serializable
data class EntitlementsResponse(
    @SerialName("krx_visible") val krxVisible: Boolean,
    @SerialName("premium_active") val premiumActive: Boolean = false,
    @SerialName("premium_pending") val premiumPending: Boolean = false,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int? = null
)
