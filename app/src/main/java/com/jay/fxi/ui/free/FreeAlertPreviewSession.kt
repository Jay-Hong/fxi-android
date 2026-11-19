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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.ui.alert.BankAlertEditor
import com.jay.fxi.ui.alert.BankAlertEditorInput
import com.jay.fxi.ui.alert.BankAlertEditorMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/** The tabs that carry the bank family's free preview (S7 E5d: D26 for banks; 테더 is S8's, 뉴스 has none). */
internal val FREE_BANK_ALERT_PREVIEW_TABS: Set<FreeTab> = setOf(FreeTab.USD, FreeTab.JPY, FreeTab.EUR)

/**
 * One free alert preview's life, as a plain state machine (S7 E5d, `s7e5d_api_agreed.r1.md`). Main only; it starts, saves and
 * reaches nothing.
 *
 * A preview opens on this owner's active bank tab with a copy of that moment's quotes and their as_of, which nothing changes
 * until it closes. Every later event names the token it was opened with, so an event from an earlier sheet, a replaced owner or
 * another tab changes nothing. A subscribe asks to close and remembers the wish; only a confirmed hide ends the preview, and
 * only then does it hand back one paywall signal. [dispose] ends this instance for good.
 */
internal class FreeAlertPreviewSession {

    sealed interface State {
        data object Closed : State

        data class Open(
            val uid: String,
            val tab: FreeTab,
            val token: Long,
            val quotes: Map<String, Double>,
            val asOf: Instant?,
            val input: BankAlertEditorInput
        ) : State

        data class Closing(
            val uid: String,
            val tab: FreeTab,
            val token: Long,
            val quotes: Map<String, Double>,
            val asOf: Instant?,
            val input: BankAlertEditorInput,
            val paywall: Boolean
        ) : State
    }

    /**
     * The paywall a finished preview asks for, bound to who and where it was asked from, to the session that issued it and to
     * the binding it was issued under: a later session's token may count from the same number, and an owner or tab that left
     * and came back is a new binding, so neither the number nor the names alone prove anything.
     */
    class PaywallSignal internal constructor(
        val uid: String,
        val tab: FreeTab,
        val token: Long,
        internal val origin: FreeAlertPreviewSession,
        internal val binding: Long
    )

    var state: State by mutableStateOf<State>(State.Closed)
        private set

    /** This session's own identity for the host's test seams: opaque, and this instance's alone. */
    internal val identity: Any = Any()

    /** The binding [invalidate] last applied, read only, for the host's test seams. */
    internal val currentBinding: Long get() = binding

    private var disposed = false
    private var owner: String? = null
    private var activeTab: FreeTab? = null
    private var binding = 0L
    private var lastToken = 0L

    /**
     * The owner and the active tab as they are now. A preview opened for another of either closes at once, wish and all, and any
     * change of either ends every signal already handed out, even if the owner and tab later come back.
     */
    fun invalidate(uid: String?, tab: FreeTab?) {
        if (disposed) return
        val nextTab = tab?.takeIf { it in FREE_BANK_ALERT_PREVIEW_TABS }
        if (uid != owner || nextTab != activeTab) binding++
        owner = uid
        activeTab = nextTab
        val (openUid, openTab) = when (val now = state) {
            State.Closed -> return
            is State.Open -> now.uid to now.tab
            is State.Closing -> now.uid to now.tab
        }
        if (openUid != owner || openTab != activeTab) state = State.Closed
    }

    /** Opens on [tab] for [uid] when nothing is open and both are the current ones. The quotes are copied, not held. */
    fun open(uid: String, tab: FreeTab, quotes: Map<String, Double>, asOf: Instant?): Long? {
        if (disposed || state != State.Closed || uid != owner || tab != activeTab) return null
        val token = ++lastToken
        val frozen: Map<String, Double> = LinkedHashMap(quotes)
        state = State.Open(uid, tab, token, frozen, asOf, BankAlertEditor.initial(BankAlertEditorMode.Add, frozen, null))
        return token
    }

    fun edit(token: Long, input: BankAlertEditorInput) {
        val now = state
        if (!disposed && now is State.Open && now.token == token && current(now.uid, now.tab)) state = now.copy(input = input)
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
                if (now.paywall && current(now.uid, now.tab)) PaywallSignal(now.uid, now.tab, now.token, this, binding) else null
            } else null
            State.Closed -> null
        }
    }

    /**
     * Whether [signal] may still open the paywall: this session issued it, owner and tab have not changed since, nothing newer
     * opened, nothing is open, and owner and tab are its own.
     */
    fun deliverable(signal: PaywallSignal): Boolean =
        !disposed && signal.origin === this && signal.binding == binding && state == State.Closed && signal.token == lastToken &&
            current(signal.uid, signal.tab)

    fun dispose() {
        disposed = true
        state = State.Closed
    }

    private fun current(uid: String, tab: FreeTab) = uid == owner && tab == activeTab
}

/** What a host tells its test seams about itself, read only: opaque identities of the host and its session, and that session's binding. */
internal class PreviewHostContext(val host: Any, val session: Any, val binding: Long)

/** The session, binding and token a paywall signal was issued under, read only. */
internal class PreviewSignalOrigin(val session: Any, val binding: Long, val token: Long)

/** What a bank tab's preview section calls to open the preview with that tab's own quotes and as_of. */
internal fun interface FreeAlertPreviewOpener {
    fun open(tab: FreeTab, quotes: Map<String, Double>, asOf: Instant?)
}

/**
 * Owns one [FreeAlertPreviewSession] per owner, for as long as it is composed, and the preview sheet when one is open.
 *
 * Remembered, never saved: a rotation, a process death or leaving the free surface ends the preview. The host hides the sheet
 * itself and counts a preview finished only when that hide completes normally and the sheet reports hidden, or when the
 * platform reports a dismiss the sheet already finished; a cancelled or failed hide finishes nothing. A paywall it asked for is
 * handed to [onSubscribe] after the sheet has left the composition, and only if the session still says it may.
 *
 * Four internal seams exist for the tests and change nothing by default: [hide] is handed the real hide and performs it,
 * [onEvent] hears the modal shown, removed and delivered (each with its token), [beforeDeliver] runs after the removal is
 * applied and before the last validity check and is told the session, binding and token the signal was issued under, and
 * [onContext] is told, read only, this host, its session and the binding that session applied — after every composition, so a
 * binding change with nothing open and a new session are both seen — and null when the host leaves. [hide] takes the action
 * rather than the sheet state so that the experimental sheet type stays inside this file and no caller has to opt in to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColumnScope.FreeAlertPreviewHost(
    uid: String?,
    activeTab: FreeTab?,
    onSubscribe: () -> Unit,
    hide: suspend (realHide: suspend () -> Unit) -> Unit = { it() },
    onEvent: (String) -> Unit = {},
    beforeDeliver: suspend (origin: PreviewSignalOrigin) -> Unit = {},
    onContext: (PreviewHostContext?) -> Unit = {},
    content: @Composable ColumnScope.(FreeAlertPreviewOpener) -> Unit
) {
    val session = remember(uid) { FreeAlertPreviewSession() }
    DisposableEffect(session) { onDispose { session.dispose() } }
    SideEffect { session.invalidate(uid, activeTab) }
    // Reported after the invalidate above, in every composition; withdrawn when this host leaves.
    val hostKey = remember { Any() }
    SideEffect { onContext(PreviewHostContext(hostKey, session.identity, session.currentBinding)) }
    DisposableEffect(hostKey) { onDispose { onContext(null) } }
    val opener = remember(session) {
        FreeAlertPreviewOpener { tab, quotes, asOf -> if (uid != null) session.open(uid, tab, quotes, asOf) }
    }
    content(opener)

    var signal by remember(session) { mutableStateOf<FreeAlertPreviewSession.PaywallSignal?>(null) }
    // One call site for open and closing. A call in each branch of a `when` sits in that branch's own group, so the move from
    // open to closing would remove this sheet and compose a fresh, fully shown one under the same token, cancelling the hide
    // that was running in the old sheet's scope.
    val shown = session.state
    if (shown != FreeAlertPreviewSession.State.Closed) PreviewSheet(session, shown, hide, onEvent) { signal = it }
    // Keyed on the signal and cleared only at the end: clearing it first would change the key and cancel this very effect while
    // [beforeDeliver] is suspended. A newer signal replaces this one and cancels it, which delivers nothing old.
    LaunchedEffect(signal) {
        val wanted = signal ?: return@LaunchedEffect
        beforeDeliver(PreviewSignalOrigin(wanted.origin.identity, wanted.binding, wanted.token))
        if (session.deliverable(wanted)) {
            onEvent("delivered ${wanted.token}")
            onSubscribe()
        }
        signal = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewSheet(
    session: FreeAlertPreviewSession,
    shown: FreeAlertPreviewSession.State,
    hide: suspend (realHide: suspend () -> Unit) -> Unit,
    onEvent: (String) -> Unit,
    onSignal: (FreeAlertPreviewSession.PaywallSignal) -> Unit
) {
    // Open and closing differ only in whether the fields take input.
    val token: Long; val tab: FreeTab; val quotes: Map<String, Double>; val input: BankAlertEditorInput; val enabled: Boolean
    when (shown) {
        FreeAlertPreviewSession.State.Closed -> return
        is FreeAlertPreviewSession.State.Open -> {
            token = shown.token; tab = shown.tab; quotes = shown.quotes; input = shown.input; enabled = true
        }
        is FreeAlertPreviewSession.State.Closing -> {
            token = shown.token; tab = shown.tab; quotes = shown.quotes; input = shown.input; enabled = false
        }
    }
    val currency = tab.currency ?: return
    key(token) {
        // Observed on the host's side, around the sheet, so the sheet itself starts nothing.
        DisposableEffect(token) {
            onEvent("shown $token")
            onDispose { onEvent("removed $token") }
        }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        // This sheet's own scope: when the sheet leaves — finished, invalidated or its host gone — a hide still running, and
        // its retry, end with it and finish nothing.
        val sheetScope = rememberCoroutineScope()
        // Finished only when the sheet says it is hidden. A request, or the platform's dismiss call while still shown, is not.
        fun finish() {
            if (!sheetState.isVisible) session.dismissed(token)?.let(onSignal)
        }
        // Only a hide that returns normally with the sheet then hidden can finish. A hide that was cancelled (a drag took the
        // sheet, say), failed, or returned with the sheet still shown is not a completion; one independent retry follows, and
        // if that does not hide it either, the preview stays closing until the user's own dismiss finishes it. One retry, not a
        // loop, so a hide that keeps failing cannot spin. A failure is not rethrown into this scope: not finishing loses and
        // invents nothing — the session stays closing and the sheet's own state still decides — whereas rethrowing would end
        // the app over an animation. The scope's own cancellation is never retried; it propagates.
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
        FreeBankAlertPreviewSheet(
            sheetState = sheetState,
            currency = currency,
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
