package com.jay.fxi.ui.premium.graph

internal object GraphV2UiTags {
    private fun tag(surface: GraphV2Surface, name: String): String {
        val scope = when (surface) {
            GraphV2Surface.INLINE -> "inline"
            GraphV2Surface.FULLSCREEN -> "fullscreen"
        }
        return "graph_v2:$scope:$name"
    }

    fun root(surface: GraphV2Surface) = tag(surface, "root")
    fun periods(surface: GraphV2Surface) = tag(surface, "periods")
    fun indices(surface: GraphV2Surface) = tag(surface, "indices")
    fun references(surface: GraphV2Surface) = tag(surface, "references")
    fun toggle(surface: GraphV2Surface, id: String) = tag(surface, "toggle:$id")
    fun check(surface: GraphV2Surface, id: String) = tag(surface, "toggle:$id:check")
    fun expand() = tag(GraphV2Surface.INLINE, "expand")
    fun close() = tag(GraphV2Surface.FULLSCREEN, "close")
    fun status(surface: GraphV2Surface) = tag(surface, "status")
    fun statusText(surface: GraphV2Surface) = tag(surface, "status_text")
    fun statusSpinner(surface: GraphV2Surface) = tag(surface, "status_spinner")
    fun selectionRetry(surface: GraphV2Surface) = tag(surface, "selection_retry")
    fun notice(surface: GraphV2Surface) = tag(surface, "notice")
    fun requestFailure(surface: GraphV2Surface) = tag(surface, "request_failure")
    fun refreshing(surface: GraphV2Surface) = tag(surface, "refreshing")
    fun chart(surface: GraphV2Surface) = tag(surface, "chart")
}
