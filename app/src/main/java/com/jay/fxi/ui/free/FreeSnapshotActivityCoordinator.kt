package com.jay.fxi.ui.free

import com.jay.fxi.data.free.FreeSnapshotKey
import com.jay.fxi.domain.model.GraphPeriod

/** Main-thread presentation boundary. The scheduler alone owns deadlines and request admission. */
class FreeSnapshotActivityCoordinator(
    private val onActivated: (String, GraphPeriod) -> Unit,
    private val onDeactivated: () -> Unit
) {
    private data class Active(val uid: String, val key: FreeSnapshotKey)
    private var active: Active? = null

    /**
     * [tab] is the server tab key, or null when the selection has none — 뉴스, and the moment
     * before a restored selection has landed. A null tab is not a lesser form of activation: the
     * plan requires zero snapshot traffic while 뉴스 is on screen, so it deactivates outright.
     */
    fun update(
        uid: String,
        tab: String?,
        period: GraphPeriod,
        isForeground: Boolean,
        isActive: Boolean
    ) {
        require(uid.isNotBlank())
        val next = if (tab != null && isForeground && isActive) Active(uid, FreeSnapshotKey(tab, period)) else null
        if (next == active) return
        if (active != null && (next == null || active?.uid != next.uid)) onDeactivated()
        active = next
        next?.let { onActivated(it.key.tab, it.key.period) }
    }

    fun dispose() {
        if (active == null) return
        active = null
        onDeactivated()
    }
}
