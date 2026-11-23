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
import com.jay.fxi.data.alerts.SourceAlertRowPolicy
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.ui.alert.SourceAlertDraftRules
import com.jay.fxi.ui.alert.SourceAlertEditorInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * One tether free alert preview's life, as a plain state machine (S8-7, `R4c/S8/s8_7_contract.codex.r2.md` §2·§3). Main
 * only; it starts, saves and reaches nothing.
 *
 * A preview opens on this owner's selected 테더 tab, under the binding its opener was issued with, with a copy of that moment's
 * quotes and their as_of, which nothing changes until it closes. Every later event names the token it was opened with, so an
 * event from an earlier sheet, a replaced owner or another tab changes nothing. A subscribe asks to close and remembers the
 * wish; only a confirmed hide ends the preview, and only then does it hand back one paywall signal, which can be consumed
 * once. [dispose] ends this instance for good.
 */
internal class FreeSourceAlertPreviewSession {

    sealed interface State {
        data object Closed : State

        data class Open(
            val uid: String,
            val tab: FreeTab,
            val token: Long,
            val quotes: Map<String, Double>,
            val asOf: Instant?,
            val input: SourceAlertEditorInput
        ) : State

        data class Closing(
            val uid: String,
            val tab: FreeTab,
            val token: Long,
            val quotes: Map<String, Double>,
            val asOf: Instant?,
            val input: SourceAlertEditorInput,
            val paywall: Boolean
        ) : State
    }

    /**
     * The paywall a finished preview asks for, bound to who and where it was asked from, to the session that issued it and to
     * the binding it was issued under. It can be consumed once.
     */
    class PaywallSignal internal constructor(
        val uid: String,
        val tab: FreeTab,
        val token: Long,
        internal val origin: FreeSourceAlertPreviewSession,
        internal val binding: Long
    ) {
        internal var consumed = false
    }

    var state: State by mutableStateOf<State>(State.Closed)
        private set

    /** This session's own identity for the host's test seams: opaque, and this instance's alone. */
    internal val identity: Any = Any()

    /** The binding [invalidate] last applied. Observable, so a host rebuilds its opener when it moves. */
    var currentBinding: Long by mutableLongStateOf(0L)
        private set

    private var disposed = false
    private var owner: String? = null
    private var activeTab: FreeTab? = null
    private var lastToken = 0L

    /**
     * The owner and the active tab as they are now. A preview opened for another of either closes at once, wish and all, and any
     * change of either ends every opener and signal already handed out, even if the owner and tab later come back.
     */
    fun invalidate(uid: String?, tab: FreeTab?) {
        if (disposed) return
        val nextTab = tab?.takeIf { it == FreeTab.TETHER }
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

    /**
     * Opens on [tab] for [uid] when nothing is open, both are the current ones and [binding] is the one in force. The quotes are
     * copied, not held; the draft starts from the policy's first exchange, never from a remembered one.
     */
    fun open(uid: String, tab: FreeTab, binding: Long, quotes: Map<String, Double>, asOf: Instant?): Long? {
        if (disposed || state != State.Closed || binding != currentBinding || !current(uid, tab)) return null
        val token = ++lastToken
        val frozen: Map<String, Double> = LinkedHashMap(quotes)
        state = State.Open(uid, tab, token, frozen, asOf, SourceAlertDraftRules.seed(SOURCES, frozen, null))
        return token
    }

    /** Takes [input] for the open sheet of [token] when its source is one of the policy's and its repeat is once or every. */
    fun edit(token: Long, input: SourceAlertEditorInput) {
        val now = state
        if (disposed || now !is State.Open || now.token != token || !current(now.uid, now.tab)) return
        if (input.source !in SOURCES || input.repeat == RepeatIntervalChange.Keep) return
        state = now.copy(input = input)
    }

    /** Asks to close. True when the host should now hide the sheet. A second request while closing changes nothing. */
    fun close(token: Long): Boolean = begin(token, paywall = false)

    /** Asks to close and then open the paywall. A second tap while closing changes nothing. */
    fun subscribe(token: Long): Boolean = begin(token, paywall = true)

    private fun begin(token: Long, paywall: Boolean): Boolean {
        val now = state
        if (disposed || now !is State.Open || now.token != token || !current(now.uid, now.tab)) return false
        state = State.Closing(now.uid, now.tab, now.token, now.quotes, now.asOf, now.input, paywall)
        return true
    }

    /**
     * The host saw this token's sheet actually hidden. The preview ends and its draft is gone; if it was closing for a
     * subscribe, and is still this owner's and tab's, one paywall signal comes back.
     */
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

    /**
     * Uses up [signal] when it may still open the paywall: this session issued it, it was not used, owner and tab have not
     * changed since, nothing newer opened, nothing is open, and owner and tab are its own. True exactly once.
     */
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

    private companion object {
        val SOURCES: List<String> = SourceAlertRowPolicy.EXCHANGES.sources(SourceAlertRowPolicy.SETTINGS_ASSET)
    }
}

/** What the tether page's preview section calls to open the preview with that page's own quotes and as_of. */
internal fun interface FreeSourceAlertPreviewOpener {
    fun open(tab: FreeTab, quotes: Map<String, Double>, asOf: Instant?)
}

/**
 * Owns one [FreeSourceAlertPreviewSession] per owner, for as long as it is composed, and the preview sheet when one is open.
 *
 * Remembered, never saved: a rotation, a process death or leaving the free surface ends the preview. Each opener it hands out
 * carries the binding in force when it was built; when the binding moves, a new opener replaces it and the old one opens
 * nothing. The host hides the sheet itself and counts a preview finished only when that hide completes normally and the sheet
 * reports hidden, or when the platform reports a dismiss the sheet already finished. A paywall it asked for is handed to
 * [onSubscribe] after the sheet has left the composition, and only if the session still consumes it.
 *
 * The test seams change nothing by default: [hide] is handed the real hide and performs it, [onEvent] hears the modal shown,
 * removed and delivered, [beforeDeliver] runs after the removal and before the last check, and [onContext] is told this host,
 * its session and the binding in force after every composition, and null when the host leaves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColumnScope.FreeSourceAlertPreviewHost(
    uid: String?,
    activeTab: FreeTab?,
    onSubscribe: () -> Unit,
    hide: suspend (realHide: suspend () -> Unit) -> Unit = { it() },
    onEvent: (String) -> Unit = {},
    beforeDeliver: suspend (origin: PreviewSignalOrigin) -> Unit = {},
    onContext: (PreviewHostContext?) -> Unit = {},
    content: @Composable ColumnScope.(FreeSourceAlertPreviewOpener) -> Unit
) {
    val session = remember(uid) { FreeSourceAlertPreviewSession() }
    DisposableEffect(session) { onDispose { session.dispose() } }
    SideEffect { session.invalidate(uid, activeTab) }
    val hostKey = remember { Any() }
    SideEffect { onContext(PreviewHostContext(hostKey, session.identity, session.currentBinding)) }
    DisposableEffect(hostKey) { onDispose { onContext(null) } }
    val binding = session.currentBinding
    val opener = remember(session, binding) {
        FreeSourceAlertPreviewOpener { tab, quotes, asOf -> if (uid != null) session.open(uid, tab, binding, quotes, asOf) }
    }
    content(opener)

    var signal by remember(session) { mutableStateOf<FreeSourceAlertPreviewSession.PaywallSignal?>(null) }
    // One call site for open and closing, so the move from open to closing keeps the same sheet and its running hide.
    val shown = session.state
    if (shown != FreeSourceAlertPreviewSession.State.Closed) SourcePreviewSheet(session, shown, hide, onEvent) { signal = it }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcePreviewSheet(
    session: FreeSourceAlertPreviewSession,
    shown: FreeSourceAlertPreviewSession.State,
    hide: suspend (realHide: suspend () -> Unit) -> Unit,
    onEvent: (String) -> Unit,
    onSignal: (FreeSourceAlertPreviewSession.PaywallSignal) -> Unit
) {
    val token: Long; val quotes: Map<String, Double>; val input: SourceAlertEditorInput; val enabled: Boolean
    when (shown) {
        FreeSourceAlertPreviewSession.State.Closed -> return
        is FreeSourceAlertPreviewSession.State.Open -> {
            token = shown.token; quotes = shown.quotes; input = shown.input; enabled = true
        }
        is FreeSourceAlertPreviewSession.State.Closing -> {
            token = shown.token; quotes = shown.quotes; input = shown.input; enabled = false
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
        FreeSourceAlertPreviewSheet(
            sheetState = sheetState,
            quotes = quotes,
            input = input,
            enabled = enabled,
            onInput = { session.edit(token, it) },
            onClose = {
                if (sheetState.isVisible) {
                    if (session.close(token)) requestHide()
                } else {
                    session.close(token)
                    finish()
                }
            },
            onSubscribe = { if (session.subscribe(token)) requestHide() }
        )
    }
}
