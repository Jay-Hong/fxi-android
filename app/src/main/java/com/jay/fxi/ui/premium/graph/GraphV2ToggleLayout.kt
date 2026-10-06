package com.jay.fxi.ui.premium.graph

internal data class GraphV2ToggleLayout(
    val indexColumn: List<GraphV2SeriesToggle>,
    val referenceFlow: List<GraphV2SeriesToggle>,
)

private val REFERENCE_PRIORITY = listOf("krx.usd-krw-futures", "investing.usd", "kb.usd", "hana.usd")

/** Axis comes from the protected series. Exact USD ids have priority; all other ids keep input order. */
internal fun arrangeGraphV2Toggles(toggles: List<GraphV2SeriesToggle>): GraphV2ToggleLayout {
    val (indices, references) = toggles.partition { it.axisGroup == "index" }
    val ordered = references.withIndex().sortedWith(
        compareBy<IndexedValue<GraphV2SeriesToggle>> {
            REFERENCE_PRIORITY.indexOf(it.value.seriesId).takeIf { rank -> rank >= 0 } ?: REFERENCE_PRIORITY.size
        }.thenBy { it.index }
    ).map { it.value }
    return GraphV2ToggleLayout(indices, ordered)
}
