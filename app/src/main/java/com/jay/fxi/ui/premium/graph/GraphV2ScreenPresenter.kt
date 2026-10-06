package com.jay.fxi.ui.premium.graph

import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.graph.GraphPlot
import com.jay.fxi.ui.graph.GraphProjection
import com.jay.fxi.ui.graph.PreparedGraph
import kotlinx.datetime.Instant

internal enum class GraphV2Content {
    INACTIVE, BLOCKED, UNSUPPORTED, SELECTION_PENDING,
    LOADING, ERROR, NO_DATA, NO_SELECTION, READY
}

internal data class GraphV2ChartModel(
    val prepared: PreparedGraph,
    val renderedIds: Set<String>,
    val rightEdgeNow: Instant? = null
)

internal object GraphV2ScreenPresenter {
    /** Called after access, supported-period and selection-restore checks. */
    fun content(
        prepared: PreparedGraph,
        renderedIds: Set<String>
    ): GraphV2Content {
        if (prepared.bySeries.values.none { it.lastObservation != null }) {
            return GraphV2Content.NO_DATA
        }
        if (renderedIds.isEmpty()) return GraphV2Content.NO_SELECTION

        val plot = project(GraphV2ChartModel(prepared, renderedIds))
        return if (plot == null || plot.isEmpty) GraphV2Content.NO_DATA else GraphV2Content.READY
    }

    fun project(
        chart: GraphV2ChartModel,
        visibleDomain: ClosedRange<Instant>? = null
    ): GraphPlot? {
        // Drop unusable zooms before projection: even an invalid window can trigger Y tightening.
        val zoom = visibleDomain?.takeIf {
            chart.prepared.period == GraphPeriod.ONE_DAY && it.start < it.endInclusive
        }
        return GraphProjection.plot(
            prepared = chart.prepared,
            visibleIds = chart.renderedIds,
            visibleDomain = zoom,
            rightEdgeNow = chart.rightEdgeNow
        )
    }
}
