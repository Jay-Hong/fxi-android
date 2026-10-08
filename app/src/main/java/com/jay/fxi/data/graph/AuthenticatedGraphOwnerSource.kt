package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.remote.AuthenticatedApiClient

/**
 * S4 INT-a: the production-shaped [GraphOwnerSource]. The live identity is the token provider's fence read: it does not consult
 * D24 and is null when signed out. Capture is the client's fenced capture, so D24 admission and the stale-owner refusal stay in
 * the transport: a capture refused before acquisition (D24 off, or an owner already stale) fetches no token, and an owner that
 * moves during acquisition is refused after it. Dormant: nothing in production constructs it.
 */
internal class AuthenticatedGraphOwnerSource(
    private val tokens: AuthTokenProvider,
    private val api: AuthenticatedApiClient
) : GraphOwnerSource {
    override fun currentIdentity(): AuthIdentityFence? = tokens.currentIdentityFence()

    override suspend fun capture(expected: AuthIdentityFence): AuthSnapshot = api.captureSnapshot(expected)
}
