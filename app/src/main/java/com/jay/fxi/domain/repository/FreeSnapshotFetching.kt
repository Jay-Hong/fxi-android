package com.jay.fxi.domain.repository

import com.jay.fxi.domain.model.FreeSnapshot
import com.jay.fxi.domain.model.GraphPeriod

/** Returns only sanitized snapshots. HTTP/validation failures throw; cancellation propagates. */
fun interface FreeSnapshotFetching {
    suspend fun fetch(tab: String, period: GraphPeriod): FreeSnapshot
}
