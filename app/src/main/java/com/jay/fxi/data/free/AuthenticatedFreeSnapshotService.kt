package com.jay.fxi.data.free

import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.domain.model.FreeSnapshot
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.repository.FreeSnapshotFetching
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthenticatedFreeSnapshotService @Inject constructor(
    private val api: AuthenticatedApiClient,
    private val sanitizer: FreeSnapshotSanitizer
) : FreeSnapshotFetching {
    override suspend fun fetch(tab: String, period: GraphPeriod): FreeSnapshot {
        val owner = api.captureSnapshot()
        val response = api.getFreeSnapshot(owner, tab, period.code)
            .requireBody("GET /api/v2/free/snapshot")
        val snapshot = sanitizer.sanitize(response, tab, period)
        api.requireCurrent(owner)
        return snapshot
    }
}
