package com.jay.fxi.ui.premium.graph

internal enum class GraphV2CenterKind { NONE, CHART, STATUS }

internal data class GraphV2CenterPresentation(
    val kind: GraphV2CenterKind,
    val message: String?,
    val showSpinner: Boolean,
    val showSelectionRetry: Boolean,
)

internal data class GraphV2StatusPresentation(
    val center: GraphV2CenterPresentation,
    val noticeText: String?,
    val requestFailureText: String?,
    val showRefreshingSpinner: Boolean,
)

private const val LOADING_FAILED = "그래프를 불러오지 못했습니다"
private const val CHECKING_SELECTION = "그래프 표시 설정을 확인하고 있습니다."
private const val SAVE_NOT_COMMITTED = "그래프 표시 설정을 저장하지 못했습니다."

/** Display the holder's decided content without deriving a new content from chart or selection. */
internal fun resolveGraphV2Status(state: GraphV2ScreenState): GraphV2StatusPresentation {
    val failedInitialize = state.content == GraphV2Content.SELECTION_PENDING &&
        state.selectionStatus == GraphV2SelectionStatus.INITIALIZING &&
        state.notice == GraphV2Notice.SAVE_NOT_COMMITTED
    val center = when (state.content) {
        GraphV2Content.INACTIVE -> GraphV2CenterPresentation(GraphV2CenterKind.NONE, null, false, false)
        GraphV2Content.READY -> GraphV2CenterPresentation(GraphV2CenterKind.CHART, null, false, false)
        GraphV2Content.LOADING -> statusCenter(spinner = true)
        GraphV2Content.ERROR -> statusCenter(LOADING_FAILED)
        GraphV2Content.NO_DATA -> statusCenter("표시할 그래프 데이터가 없습니다")
        GraphV2Content.NO_SELECTION -> statusCenter("표시할 항목을 선택해 주세요.")
        GraphV2Content.UNSUPPORTED -> statusCenter("이 기간은 지원하지 않습니다.")
        GraphV2Content.BLOCKED -> statusCenter("그래프를 표시할 수 없습니다.")
        GraphV2Content.SELECTION_PENDING -> when (state.selectionStatus) {
            GraphV2SelectionStatus.UNBOUND -> statusCenter(CHECKING_SELECTION)
            GraphV2SelectionStatus.AWAITING_RESTORE -> statusCenter(CHECKING_SELECTION, spinner = true)
            GraphV2SelectionStatus.INITIALIZING -> if (failedInitialize) {
                statusCenter(SAVE_NOT_COMMITTED, retry = true)
            } else {
                statusCenter(CHECKING_SELECTION, spinner = true)
            }
            GraphV2SelectionStatus.CONFIRMING ->
                statusCenter("그래프 표시 설정을 다시 확인하고 있습니다.", spinner = true)
            GraphV2SelectionStatus.UNREADABLE ->
                statusCenter("그래프 표시 설정을 확인할 수 없습니다.", retry = true)
            GraphV2SelectionStatus.READY ->
                statusCenter(CHECKING_SELECTION, spinner = true)
        }
    }
    val usesEntry = when (state.content) {
        GraphV2Content.READY, GraphV2Content.NO_DATA, GraphV2Content.NO_SELECTION,
        GraphV2Content.SELECTION_PENDING -> true
        else -> false
    }
    return GraphV2StatusPresentation(
        center = center,
        noticeText = if (state.content != GraphV2Content.INACTIVE && !failedInitialize &&
            state.notice == GraphV2Notice.SAVE_NOT_COMMITTED) SAVE_NOT_COMMITTED else null,
        requestFailureText = if (usesEntry && state.requestFailure != null) LOADING_FAILED else null,
        showRefreshingSpinner = usesEntry && state.refreshing,
    )
}

private fun statusCenter(message: String? = null, spinner: Boolean = false, retry: Boolean = false) =
    GraphV2CenterPresentation(GraphV2CenterKind.STATUS, message, spinner, retry)
