package com.jay.fxi.domain.model

/** Raw user selection shared across periods; initialized ids keep defaults from returning. */
data class GraphSeriesSelection(
    val visibleSeriesIds: Set<String>,
    val initializedSeries: Set<String>
)

sealed interface GraphSelectionUniverse {
    data class Catalog(
        val allSeries: Set<String>,
        val defaultVisible: Set<String>
    ) : GraphSelectionUniverse

    /** Only ids admitted by the current tab/period domain boundary may be supplied. */
    data class CatalogUnavailable(
        val admittedSeries: Set<String>
    ) : GraphSelectionUniverse

    data object Unsupported : GraphSelectionUniverse
}

sealed interface GraphSelectionChange {
    data object Unchanged : GraphSelectionChange
    data class Replace(val selection: GraphSeriesSelection) : GraphSelectionChange
    data class Rejected(val reason: String) : GraphSelectionChange
}
