package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.dto.GraphV2CatalogResponse
import com.jay.fxi.data.remote.dto.GraphV2TabResponse

/**
 * Delegates graph reads with the caller's owner and use guard, using the key's period code.
 *
 * This adapter does not reacquire the owner, retry requests or convert exceptions. The transport
 * can invoke the guard from its threads, so the guard must be thread-safe, non-blocking and
 * side-effect free.
 */
internal class AuthenticatedGraphV2Fetcher(private val api: AuthenticatedApiClient) : GraphV2Fetching {
    override suspend fun catalog(
        owner: AuthSnapshot,
        useAdmitted: () -> Boolean
    ): AuthenticatedHttpResponse<GraphV2CatalogResponse> = api.getGraphV2Catalog(owner, useAdmitted)

    override suspend fun tab(
        owner: AuthSnapshot,
        key: GraphKey,
        useAdmitted: () -> Boolean
    ): AuthenticatedHttpResponse<GraphV2TabResponse> = api.getGraphV2Tab(owner, key.tab, key.period.code, useAdmitted)
}
