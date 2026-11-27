package com.jay.fxi.ui.free

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.jay.fxi.domain.model.TopicQuoteKey
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.ui.alert.ComparisonQuotes
import com.jay.fxi.ui.alert.ComparisonFields
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

internal class FreeComparisonAlertPreviewSession {

    sealed interface State {
        data object Closed : State

        data class Open(
            val uid: String,
            val tab: FreeTab,
            val token: Long,
            val quotes: ComparisonQuotes,
            val asOf: Instant?,
            val fields: ComparisonFields
        ) : State

        data class Closing(
            val uid: String,
            val tab: FreeTab,
            val token: Long,
            val quotes: ComparisonQuotes,
            val asOf: Instant?,
            val fields: ComparisonFields,
            val paywall: Boolean
        ) : State
    }

    class PaywallSignal internal constructor(
        val uid: String,
        val tab: FreeTab,
        val token: Long,
        internal val origin: FreeComparisonAlertPreviewSession,
        internal val binding: Long
    ) {
        internal var consumed = false
    }

    var state: State by mutableStateOf<State>(State.Closed)
        private set

    internal val identity: Any = Any()

    var currentBinding: Long by mutableLongStateOf(0L)
        private set

    private var disposed = false
    private var owner: String? = null
    private var activeTab: FreeTab? = null
    private var lastToken = 0L

    fun invalidate(uid: String?, tab: FreeTab?) {
        if (disposed) return
        val nextTab = tab?.takeIf { it.isData }
        if (uid != owner || nextTab != activeTab) currentBinding++
        owner = uid
        activeTab = nextTab
        val (openUid, openTab) = when (val now = state) {
            State.Closed -> return
            is State.Open -> now.uid to now.tab
            is State.Closing -> now.uid to now.tab
        }
        if (openUid != owner || openTab != activeTab) state = State.Closed
    }

    fun open(uid: String, tab: FreeTab, kind: String, binding: Long, quotes: Map<Pair<String, String>, Double>, asOf: Instant?): Long? {
        if (disposed || state != State.Closed || binding != currentBinding || !current(uid, tab)) return null
        if (!tab.isData || !(kind == COMPARISON_PREVIEW_ABSOLUTE || kind == COMPARISON_PREVIEW_SIGNED && tab == FreeTab.TETHER)) return null
        val tabCode = tab.serverTab ?: return null
        val empty = ComparisonFields.create(tabCode, kind, emptyMap())
        val allowed = (empty.candidates() + empty.candidates(right = true)).toSet()
        val frozen = quotes.filterKeys { it in allowed }.mapKeys { (leg, _) -> TopicQuoteKey(leg.first, leg.second) }
        val token = ++lastToken
        state = State.Open(uid, tab, token, frozen, asOf, ComparisonFields.create(tabCode, kind, frozen))
        return token
    }

    fun edit(token: Long, change: (ComparisonFields, ComparisonQuotes) -> ComparisonFields) {
        val now = state
        if (disposed || now !is State.Open || now.token != token || !current(now.uid, now.tab)) return
        state = now.copy(fields = change(now.fields, now.quotes))
    }

    fun close(token: Long): Boolean = begin(token, paywall = false)

    fun subscribe(token: Long): Boolean = begin(token, paywall = true)

    private fun begin(token: Long, paywall: Boolean): Boolean {
        val now = state
        if (disposed || now !is State.Open || now.token != token || !current(now.uid, now.tab)) return false
        state = State.Closing(now.uid, now.tab, now.token, now.quotes, now.asOf, now.fields, paywall)
        return true
    }

    fun dismissed(token: Long): PaywallSignal? {
        if (disposed) return null
        return when (val now = state) {
            is State.Open -> if (now.token == token) {
                state = State.Closed
                null
            } else null
            is State.Closing -> if (now.token == token) {
                state = State.Closed
                if (now.paywall && current(now.uid, now.tab)) PaywallSignal(now.uid, now.tab, now.token, this, currentBinding) else null
            } else null
            State.Closed -> null
        }
    }

    fun consume(signal: PaywallSignal): Boolean {
        if (disposed || signal.consumed || signal.origin !== this || signal.binding != currentBinding || state != State.Closed ||
            signal.token != lastToken || !current(signal.uid, signal.tab)
        ) return false
        signal.consumed = true
        return true
    }

    fun dispose() {
        disposed = true
        state = State.Closed
    }

    private fun current(uid: String, tab: FreeTab) = uid == owner && tab == activeTab
}

internal fun interface FreeComparisonAlertPreviewOpener {
    fun open(tab: FreeTab, kind: String, quotes: Map<Pair<String, String>, Double>, asOf: Instant?)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColumnScope.FreeComparisonAlertPreviewHost(
    uid: String?,
    activeTab: FreeTab?,
    onSubscribe: () -> Unit,
    hide: suspend (realHide: suspend () -> Unit) -> Unit = { it() },
    onEvent: (String) -> Unit = {},
    beforeDeliver: suspend (origin: PreviewSignalOrigin) -> Unit = {},
    onContext: (PreviewHostContext?) -> Unit = {},
    content: @Composable ColumnScope.(FreeComparisonAlertPreviewOpener) -> Unit
) {
    val session = remember(uid) { FreeComparisonAlertPreviewSession() }
    DisposableEffect(session) { onDispose { session.dispose() } }
    SideEffect { session.invalidate(uid, activeTab) }
    val hostKey = remember { Any() }
    SideEffect { onContext(PreviewHostContext(hostKey, session.identity, session.currentBinding)) }
    DisposableEffect(hostKey) { onDispose { onContext(null) } }
    val binding = session.currentBinding
    val opener = remember(session, binding) {
        FreeComparisonAlertPreviewOpener { tab, kind, quotes, asOf -> if (uid != null) session.open(uid, tab, kind, binding, quotes, asOf) }
    }
    var signal by remember(session) { mutableStateOf<FreeComparisonAlertPreviewSession.PaywallSignal?>(null) }
    // Keyed on the signal and cleared only at the end, so clearing it cannot cancel this effect while [beforeDeliver] runs.
    LaunchedEffect(signal) {
        val wanted = signal ?: return@LaunchedEffect
        beforeDeliver(PreviewSignalOrigin(wanted.origin.identity, wanted.binding, wanted.token))
        if (session.consume(wanted)) {
            onEvent("delivered ${wanted.token}")
            onSubscribe()
        }
        signal = null
    }
    content(opener)
    val shown = session.state
    if (shown != FreeComparisonAlertPreviewSession.State.Closed) {
        ComparisonPreviewModal(session, shown, hide, onEvent, acceptSubscription = { token -> session.subscribe(token) }) { signal = it }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComparisonPreviewModal(
    session: FreeComparisonAlertPreviewSession,
    shown: FreeComparisonAlertPreviewSession.State,
    hide: suspend (realHide: suspend () -> Unit) -> Unit,
    onEvent: (String) -> Unit,
    acceptSubscription: (Long) -> Boolean,
    onSignal: (FreeComparisonAlertPreviewSession.PaywallSignal) -> Unit
) {
    val token: Long; val quotes: ComparisonQuotes; val fields: ComparisonFields; val enabled: Boolean
    when (shown) {
        FreeComparisonAlertPreviewSession.State.Closed -> return
        is FreeComparisonAlertPreviewSession.State.Open -> {
            token = shown.token; quotes = shown.quotes; fields = shown.fields; enabled = true
        }
        is FreeComparisonAlertPreviewSession.State.Closing -> {
            token = shown.token; quotes = shown.quotes; fields = shown.fields; enabled = false
        }
    }
    key(token) {
        DisposableEffect(token) {
            onEvent("shown $token")
            onDispose { onEvent("removed $token") }
        }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        // This sheet's own scope: when the sheet leaves, a hide still running, and its retry, end with it and finish nothing.
        val sheetScope = rememberCoroutineScope()
        // Finished only when the sheet says it is hidden. A request, or the platform's dismiss call while still shown, is not.
        fun finish() {
            if (!sheetState.isVisible) session.dismissed(token)?.let(onSignal)
        }
        // Only a hide that returns normally with the sheet then hidden can finish; one independent retry, not a loop. A failure
        // is not rethrown: not finishing loses and invents nothing. The scope's own cancellation is never retried.
        fun requestHide() {
            sheetScope.launch {
                repeat(2) {
                    val hidden = try {
                        hide { sheetState.hide() }
                        !sheetState.isVisible
                    } catch (failure: Exception) {
                        if (failure is CancellationException) ensureActive()
                        false
                    }
                    if (hidden) {
                        finish()
                        return@launch
                    }
                }
            }
        }
        FreeComparisonAlertPreviewSheet(
            sheetState = sheetState,
            quotes = quotes,
            fields = fields,
            enabled = enabled,
            change = { session.edit(token, it) },
            onClose = {
                if (sheetState.isVisible) {
                    if (session.close(token)) requestHide()
                } else {
                    session.close(token)
                    finish()
                }
            },
            onSubscribe = { if (acceptSubscription(token)) requestHide() }
        )
    }
}
