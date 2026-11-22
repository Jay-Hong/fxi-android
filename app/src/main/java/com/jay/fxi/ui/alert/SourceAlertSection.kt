package com.jay.fxi.ui.alert

import android.content.Context
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.ui.text.style.TextAlign
import com.jay.fxi.data.alerts.SourceAlertSaveOutcome
import com.jay.fxi.data.alerts.SourceAlertSaveRequest
import com.jay.fxi.data.alerts.SourceAlertDraft
import com.jay.fxi.data.alerts.SourceAlertEdit
import com.jay.fxi.data.local.LastSelectedSourceMemory
import com.jay.fxi.data.local.SourceSelectionSnapshot
import com.jay.fxi.ui.theme.StatusError
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.data.alerts.AlertLoadFailure
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.alerts.SourceAlertRowPolicy
import com.jay.fxi.data.alerts.SourceAlertSettingsOwner
import com.jay.fxi.data.alerts.SourceAlertSettingsState
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.Exchange
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.SourceAlertSetting
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.NegativeColor
import com.jay.fxi.ui.theme.PositiveColor
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.theme.StatusConnecting
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * S8-4a/S8-5b: the premium tether tab's one source settings owner and its last-added-exchange memory; this section is the
 * owner's only outside writer.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SourceAlertSectionEntryPoint {
    fun sourceAlertSettingsOwner(): SourceAlertSettingsOwner
    fun lastSelectedSourceMemory(): LastSelectedSourceMemory
}

internal data class SourceAlertSectionState(
    val content: Content,
    val activeCount: Int,
    val refreshSpinning: Boolean,
    val refreshEnabled: Boolean
) {
    sealed interface Content {
        data object Loading : Content
        data class Error(val message: String) : Content   // Fixed copy, never server text.
        data object Empty : Content
        data class Rows(val rows: List<SourceAlertSetting>) : Content
    }
}

/**
 * A success is shown only for [shownIdentity] (UID and generation); another identity's success shows nothing yet. Before any
 * success, a terminal failure shows its error, whatever identity the snapshot carries (none, on a first failure).
 */
internal fun sourceAlertSectionState(
    settings: SourceAlertSettingsState,
    manual: ManualRefreshState,
    shownIdentity: AuthIdentityFence
): SourceAlertSectionState {
    val snapshot = settings.settings?.takeIf { settings.identity == shownIdentity }
    val rows = snapshot?.let { adopted ->
        SourceAlertRowPolicy.EXCHANGES.project(adopted, SourceAlertRowPolicy.SETTINGS_ASSET).sortedByDescending { it.createdAt }
    }.orEmpty()
    val phase = settings.phase
    val content = when {
        rows.isNotEmpty() -> SourceAlertSectionState.Content.Rows(rows)
        snapshot != null -> SourceAlertSectionState.Content.Empty
        settings.settings != null -> SourceAlertSectionState.Content.Loading
        phase is AlertLoadPhase.TerminalError -> SourceAlertSectionState.Content.Error(sourceAlertErrorMessage(phase.failure))
        else -> SourceAlertSectionState.Content.Loading
    }
    return SourceAlertSectionState(
        content = content,
        activeCount = rows.count { it.isEnabled },
        refreshSpinning = manual.sending,
        refreshEnabled = !manual.coolingDown && !manual.sending
    )
}

private fun sourceAlertErrorMessage(failure: AlertLoadFailure): String = when (failure) {
    AlertLoadFailure.AuthRequired -> "로그인이 필요합니다."
    AlertLoadFailure.Forbidden -> "접근 권한이 없습니다."
    is AlertLoadFailure.Failed -> if (failure.kind == AlertRepositoryFailureKind.NETWORK) {
        "네트워크 연결을 확인해 주세요."
    } else {
        "알림 설정을 불러오지 못했습니다."
    }
}

/** A projection/delegation seam over the one source owner, never another canonical settings owner. */
internal interface SourceAlertSectionSource {
    val settings: StateFlow<SourceAlertSettingsState>
    val manualRefresh: StateFlow<ManualRefreshState>
    val selection: StateFlow<SourceSelectionSnapshot>
    suspend fun prepareSelection(identity: AuthIdentityFence)
    fun acceptAddedSource(identity: AuthIdentityFence, sourceCode: String)
    fun ensure()
    fun authoritative()
    fun manual()
    fun addAlert(draft: SourceAlertDraft): SourceAlertSaveRequest
    fun editAlert(id: Int, edit: SourceAlertEdit): SourceAlertSaveRequest
    fun setEnabled(id: Int, enabled: Boolean)
    fun delete(id: Int)
}

internal fun interface SourceAlertSectionSourceFactory {
    fun create(context: Context): SourceAlertSectionSource
}

internal object ProductionSourceAlertSectionSourceFactory : SourceAlertSectionSourceFactory {
    override fun create(context: Context): SourceAlertSectionSource {
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, SourceAlertSectionEntryPoint::class.java)
        val owner = entryPoint.sourceAlertSettingsOwner()
        val memory = entryPoint.lastSelectedSourceMemory()
        return object : SourceAlertSectionSource {
            override val settings = owner.state
            override val manualRefresh = owner.manualRefresh
            override val selection = memory.selection
            override suspend fun prepareSelection(identity: AuthIdentityFence) = memory.prepare(identity)
            override fun acceptAddedSource(identity: AuthIdentityFence, sourceCode: String) = memory.acceptAddedSource(identity, sourceCode)
            override fun ensure() = owner.ensure()
            override fun authoritative() = owner.authoritative()
            override fun manual() = owner.manual()
            override fun addAlert(draft: SourceAlertDraft) = owner.addAlert(draft)
            override fun editAlert(id: Int, edit: SourceAlertEdit) = owner.editAlert(id, edit)
            override fun setEnabled(id: Int, enabled: Boolean) = owner.setEnabled(id, enabled)
            override fun delete(id: Int) = owner.delete(id)
        }
    }
}

internal object SourceAlertSectionTags {
    const val ROOT = "source_alert_section"
    const val REFRESH = "source_alert_refresh"
    const val TOGGLE_EXPANDED = "source_alert_toggle_expanded"
    const val RETRY = "source_alert_retry"
    const val ACTIVE_COUNT = "source_alert_active_count"
    const val ROW = "source_alert_row"
    const val NOTIFY_PROMPT = "source_alert_notify_prompt"
    const val NOTIFY_PROMPT_BUTTON = "source_alert_notify_prompt_button"
    const val NOTIFY_BANNER = "source_alert_notify_banner"
    const val ADD_BUTTON = "source_alert_add_button"
    const val ADD_AT_CAP = "source_alert_add_at_cap"
    const val NOTICE = "source_alert_notice"
    fun rowOpen(id: Int) = "source_alert_row_open_$id"
    fun toggle(id: Int) = "source_alert_toggle_$id"
    fun delete(id: Int) = "source_alert_delete_$id"
    const val DELETE_DIALOG = "source_alert_delete_dialog"
    const val DELETE_CONFIRM = "source_alert_delete_confirm"
    const val DELETE_CANCEL = "source_alert_delete_cancel"
}

internal const val SOURCE_ALERT_LIMIT = 30

internal sealed interface SourceAlertAddAvailability {
    data object Hidden : SourceAlertAddAvailability
    data class Available(val remaining: Int) : SourceAlertAddAvailability
    data object AtCap : SourceAlertAddAvailability
}

/** Every adopted row of [shownIdentity] counts, whatever its state; the owner's CAP stays the final word. */
internal fun sourceAlertAddAvailability(settings: SourceAlertSettingsState, shownIdentity: AuthIdentityFence): SourceAlertAddAvailability {
    val rows = settings.settings
    if (settings.identity != shownIdentity || rows == null) return SourceAlertAddAvailability.Hidden
    val count = rows.mapTo(mutableSetOf()) { it.id }.size
    return if (count < SOURCE_ALERT_LIMIT) SourceAlertAddAvailability.Available(SOURCE_ALERT_LIMIT - count) else SourceAlertAddAvailability.AtCap
}

internal fun sourceSelectionSettled(selection: SourceSelectionSnapshot, identity: AuthIdentityFence): Boolean = when (selection) {
    is SourceSelectionSnapshot.Ready -> selection.identity == identity
    is SourceSelectionSnapshot.Failed -> selection.identity == identity
    is SourceSelectionSnapshot.Loading, SourceSelectionSnapshot.None -> false
}

internal fun sourceSelectionSeed(selection: SourceSelectionSnapshot): String? = (selection as? SourceSelectionSnapshot.Ready)?.code

/**
 * S8-5b (`R4c/S8/s8_5b_contract.agreed.r1.md` §2 표시): what the section draws around its content; the view draws exactly this.
 * An empty list with notifications blocked shows the permission card, whose button is the add entry, and no add button; rows
 * with notifications blocked show the banner. Otherwise a listed snapshot shows the add button with its remaining count from
 * 3 down, or the cap's notice in its place. The card's button and the add button take a click only once the selection settled.
 */
internal data class SourceAlertSurfaces(
    val permissionCard: Boolean,
    val banner: Boolean,
    val addButton: Boolean,
    val remainingNote: String?,
    val atCap: Boolean,
    val addEnabled: Boolean
)

internal fun sourceAlertSurfaces(
    state: SourceAlertSectionState,
    availability: SourceAlertAddAvailability,
    selectionSettled: Boolean,
    notificationsBlocked: Boolean
): SourceAlertSurfaces {
    val content = state.content
    val listed = content == SourceAlertSectionState.Content.Empty || content is SourceAlertSectionState.Content.Rows
    val card = content == SourceAlertSectionState.Content.Empty && notificationsBlocked && availability != SourceAlertAddAvailability.Hidden
    val area = listed && !card
    val available = availability as? SourceAlertAddAvailability.Available
    return SourceAlertSurfaces(
        permissionCard = card,
        banner = content is SourceAlertSectionState.Content.Rows && notificationsBlocked,
        addButton = area && available != null,
        remainingNote = available?.remaining?.takeIf { area && it <= 3 }?.let { "(${it}개 남음)" },
        atCap = area && availability == SourceAlertAddAvailability.AtCap,
        addEnabled = selectionSettled && available != null
    )
}

/** One editor; its draft, request and error change without changing its identity. A Saved add is claimed once. */
internal class SourceAlertOpenEditor internal constructor(
    val mode: SourceAlertEditorMode,
    val identity: AuthIdentityFence,
    input: SourceAlertEditorInput
) {
    var input by mutableStateOf(input)
        internal set
    /** The current request, from its registration until its result is consumed; no save starts while it is set. */
    var handle by mutableStateOf<SourceAlertSaveRequest?>(null)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    /** Advances on every change of [input]; a save waiting for the grant sends only the input it was clicked with. */
    internal var inputRevision = 0L
    internal var claimed = false
    internal var watch: Job? = null
}

/** One delete confirmation; only the prompt that is open confirms or cancels, once. */
internal class SourceAlertDeletePrompt internal constructor(val row: SourceAlertSetting)

/**
 * S8-5b: the tether section's Main holder for one mount and one shown identity. It owns the editor with its draft, handle and
 * error, the delete confirmation, the notice and the permission continuations; the view raises only intents and target
 * values. Every read, write and open checks, at the moment it runs, that the mount is alive and [isShown]; once it finds itself
 * not shown the holder retires for good (the screen keys the section by its session, so a return is a new holder). Writes and
 * opens also need the owner to hold [identity]; an add continuation needs no editor to have opened or closed since its click,
 * a save's continuation the input it was clicked with. [permission] runs its block only in the grant continuation (at once when
 * already granted); [enableNotifications] is the banner's bare request; [liveQuotes] is null for a withdrawn session.
 */
internal class SourceAlertSectionHolder(
    private val source: SourceAlertSectionSource,
    val identity: AuthIdentityFence,
    private val sources: List<String>,
    private val scope: CoroutineScope,
    private val isShown: () -> Boolean,
    private val liveQuotes: () -> Map<String, Double>?,
    private val permission: (onGranted: () -> Unit) -> Unit,
    private val enableNotifications: () -> Unit
) {
    var editor by mutableStateOf<SourceAlertOpenEditor?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var deletePrompt by mutableStateOf<SourceAlertDeletePrompt?>(null)
        private set
    private var alive = true
    private var started = false
    // Advances whenever an editor opens or closes and at dispose; an add continuation from an older value opens nothing.
    private var editorEpoch = 0L

    /**
     * Once per mount, when the mount is applied: the owner's ensure, the selection's one prepare for [identity], and the editor's
     * reconciliation. A prepare that has to wait for the first listing starts only if this mount is still shown when it arrives.
     */
    fun start() {
        if (started || !alive) return
        started = true
        fun adopted(state: SourceAlertSettingsState) = state.identity == identity && state.settings != null
        val listedAtMount = adopted(source.settings.value)
        source.ensure()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!listedAtMount || !adopted(source.settings.value)) {
                source.settings.first { adopted(it) }
                if (!shownNow()) return@launch
            }
            if (alive && adopted(source.settings.value)) source.prepareSelection(identity)
        }
        scope.launch { source.settings.collect { reconcile(it) } }
    }

    fun dispose() {
        alive = false
        editorEpoch++
        editor?.watch?.cancel()
        editor = null
        deletePrompt = null
    }

    private fun shownNow(): Boolean {
        if (!alive) return false
        if (isShown()) return true
        dispose()
        return false
    }

    private fun liveNow(): Boolean = shownNow() && source.settings.value.identity == identity

    private fun liveRows(state: SourceAlertSettingsState = source.settings.value): List<SourceAlertSetting>? =
        state.takeIf { it.identity == identity }?.settings
            ?.let { SourceAlertRowPolicy.EXCHANGES.project(it, SourceAlertRowPolicy.SETTINGS_ASSET) }

    private fun liveRow(row: SourceAlertSetting, state: SourceAlertSettingsState = source.settings.value): SourceAlertSetting? =
        liveRows(state)?.firstOrNull { it.id == row.id && it.source == row.source && it.asset == row.asset }

    /** A free place by the adopted rows of [identity] and a settled selection; the owner's CAP stays the final word. */
    private fun addReady(): Boolean =
        sourceAlertAddAvailability(source.settings.value, identity) is SourceAlertAddAvailability.Available &&
            sourceSelectionSettled(source.selection.value, identity)

    fun retry() { if (shownNow()) source.authoritative() }

    fun refresh() { if (shownNow()) source.manual() }

    /** The banner is drawn over rows only; its click is the helper's bare request and nothing else. */
    fun requestNotifications() { if (liveNow() && !liveRows().isNullOrEmpty()) enableNotifications() }

    /** The add button and the empty list's permission card both come here; the editor opens only in the continuation. */
    fun openAdd() {
        if (!liveNow() || editor != null || !addReady()) return
        val epoch = editorEpoch
        permission {
            if (epoch != editorEpoch || !liveNow() || editor != null || !addReady()) return@permission
            val quotes = liveQuotes() ?: return@permission
            val mode = SourceAlertEditorMode.Add
            show(SourceAlertOpenEditor(mode, identity, SourceAlertEditor.initial(mode, sources, quotes, sourceSelectionSeed(source.selection.value))))
        }
    }

    /** Opening an edit runs no permission helper; it starts from the row as it is now. */
    fun openEdit(row: SourceAlertSetting) {
        if (!liveNow() || editor != null) return
        val live = liveRow(row) ?: return
        val mode = SourceAlertEditorMode.Edit(live.copy())
        show(SourceAlertOpenEditor(mode, identity, SourceAlertEditor.initial(mode, sources, emptyMap())))
    }

    /** Typing, the condition and the repeat, kept as typed; the exchange changes only through [pick]. */
    fun input(open: SourceAlertOpenEditor, input: SourceAlertEditorInput) {
        if (!liveNow() || editor !== open || saving(open)) return
        setInput(open, input.copy(source = open.input.source))
    }

    /** An explicit exchange pick; an add refills from the live quote of that exchange, an edit keeps its text. */
    fun pick(open: SourceAlertOpenEditor, code: String) {
        if (!liveNow() || editor !== open || saving(open) || code !in sources) return
        val quotes = liveQuotes() ?: return
        setInput(open, SourceAlertEditor.onSourcePicked(open.input, code, open.mode, quotes))
    }

    /** A save runs the helper only for an input that may be sent now and sends only if that input is unchanged at the grant. */
    fun submit(open: SourceAlertOpenEditor) {
        if (checkedSubmission(open) == null) return
        val revision = open.inputRevision
        var used = false
        permission {
            if (used) return@permission
            used = true
            if (open.inputRevision != revision) return@permission
            val submit = checkedSubmission(open) ?: return@permission
            open.error = null
            val request = when (submit) {
                is SourceAlertSubmit.Add -> source.addAlert(submit.draft)
                is SourceAlertSubmit.Edit -> source.editAlert(submit.id, submit.edit)
            }
            open.handle = request
            watch(open, request)
        }
    }

    fun dismissError(open: SourceAlertOpenEditor) { if (alive && editor === open) open.error = null }

    fun dismiss(open: SourceAlertOpenEditor) { if (alive) close(open) }

    /** Toggling, including turning on, runs no permission helper and sends the target value. */
    fun toggle(row: SourceAlertSetting, enabled: Boolean) {
        if (liveNow() && liveRow(row) != null) source.setEnabled(row.id, enabled)
    }

    fun requestDelete(row: SourceAlertSetting) {
        if (liveNow() && deletePrompt == null && liveRow(row) != null) deletePrompt = SourceAlertDeletePrompt(row)
    }

    fun confirmDelete(prompt: SourceAlertDeletePrompt?) {
        if (prompt == null || prompt !== deletePrompt) return
        deletePrompt = null
        if (liveNow() && liveRow(prompt.row) != null) source.delete(prompt.row.id)
    }

    fun cancelDelete(prompt: SourceAlertDeletePrompt?) {
        if (prompt != null && prompt === deletePrompt) deletePrompt = null
    }

    private fun saving(open: SourceAlertOpenEditor): Boolean = open.handle != null

    private fun setInput(open: SourceAlertOpenEditor, next: SourceAlertEditorInput) {
        if (next == open.input) return
        open.inputRevision++
        open.input = next
    }

    /** [open]'s input checked now against the live rows and quotes, or null with the reason shown. */
    private fun checkedSubmission(open: SourceAlertOpenEditor): SourceAlertSubmit? {
        if (!liveNow() || editor !== open || saving(open)) return null
        val quotes = liveQuotes() ?: return null
        val rows = liveRows() ?: return null
        val mode = open.mode
        if (mode is SourceAlertEditorMode.Edit && liveRow(mode.setting) == null) {
            close(open)
            return null
        }
        if (mode == SourceAlertEditorMode.Add && !addReady()) {
            if (sourceAlertAddAvailability(source.settings.value, identity) == SourceAlertAddAvailability.AtCap) {
                open.error = SourceAlertEditor.Copy.CAP
            }
            return null
        }
        val check = SourceAlertEditor.check(mode, open.input, sources, SourceAlertRowPolicy.SETTINGS_ASSET, quotes, rows)
        if (!check.canSave) {
            open.error = SourceAlertEditor.hint(check) ?: SourceAlertEditor.Copy.NOT_SAVED
            return null
        }
        return SourceAlertEditor.submit(mode, open.input, sources)
    }

    private fun show(open: SourceAlertOpenEditor) {
        notice = null
        editorEpoch++
        editor = open
    }

    private fun close(open: SourceAlertOpenEditor, message: String? = null) {
        if (editor !== open) return
        open.watch?.cancel()
        editorEpoch++
        editor = null
        if (message != null) notice = message
    }

    /** The edited id is still adopted, but with another pair: the old edit is discarded. */
    private fun replaced(mode: SourceAlertEditorMode, state: SourceAlertSettingsState): Boolean {
        val original = (mode as? SourceAlertEditorMode.Edit)?.setting ?: return false
        return state.identity == identity && state.settings.orEmpty().any {
            it.id == original.id && (it.source != original.source || it.asset != original.asset)
        }
    }

    /**
     * Another identity, or the edited id adopted with another pair, ends the editor at once. An edit whose id is gone closes now
     * when no request is in flight; with one in flight, that request's own result decides, once (a 404 is Removed, with its notice).
     */
    private fun reconcile(state: SourceAlertSettingsState) {
        deletePrompt?.let { if (liveRow(it.row, state) == null) deletePrompt = null }
        val open = editor ?: return
        val mode = open.mode
        if (state.identity != open.identity || replaced(mode, state)) {
            close(open)
        } else if (mode is SourceAlertEditorMode.Edit && open.handle == null && liveRow(mode.setting, state) == null) {
            close(open)
        }
    }

    private fun watch(open: SourceAlertOpenEditor, handle: SourceAlertSaveRequest) {
        open.watch?.cancel()
        // An immediate refusal is read before submit returns; a later terminal value on the holder's Main scope.
        open.watch = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            consume(open, handle, handle.outcome.first { it != SourceAlertSaveOutcome.Pending })
        }
    }

    private fun consume(open: SourceAlertOpenEditor, handle: SourceAlertSaveRequest, outcome: SourceAlertSaveOutcome) {
        if (!liveNow() || editor !== open || open.handle !== handle) return
        if (replaced(open.mode, source.settings.value)) {
            close(open)
            return
        }
        val view = SourceAlertEditor.result(outcome) ?: return
        if (outcome is SourceAlertSaveOutcome.Saved && open.mode == SourceAlertEditorMode.Add && !open.claimed) {
            // The adopted add is the one write the memory takes; an edit, a failure or another handle is not.
            open.claimed = true
            source.acceptAddedSource(open.identity, outcome.row.source)
        }
        if (view.close) {
            close(open, if (outcome == SourceAlertSaveOutcome.Removed) view.message else null)
            return
        }
        // Consumed: a new explicit save may start. An edit whose row went away or changed pair meanwhile is discarded.
        open.handle = null
        val mode = open.mode
        if (mode is SourceAlertEditorMode.Edit && liveRow(mode.setting) == null) {
            close(open)
            return
        }
        open.error = view.message
    }
}

/**
 * The tether tab's source alerts. [owner] is the screen's display owner; [isShown] answers, at the moment of a click or a
 * permission continuation, whether this owner's tether tab is active, settings and news detail closed and no fullscreen
 * shown. [quotes] are the shown exchange quotes; [liveQuotes] reads the current owner's tab map when an action runs, null
 * when the session is withdrawn.
 */
@Composable
internal fun SourceAlertSection(
    tab: FreeTab,
    owner: TopicDisplayOwner,
    isShown: () -> Boolean,
    modifier: Modifier = Modifier,
    sourceFactory: SourceAlertSectionSourceFactory = ProductionSourceAlertSectionSourceFactory,
    quotes: Map<String, Double> = emptyMap(),
    liveQuotes: () -> Map<String, Double>? = { quotes }
) {
    if (tab != FreeTab.TETHER) return
    val notificationAction = rememberNotificationPermissionAction()
    val applicationContext = LocalContext.current.applicationContext
    val source = remember(applicationContext, sourceFactory) { sourceFactory.create(applicationContext) }
    val sources = remember { SourceAlertRowPolicy.EXCHANGES.sources(SourceAlertRowPolicy.SETTINGS_ASSET) }
    key(source, owner) {
        val settings by source.settings.collectAsState()
        val manual by source.manualRefresh.collectAsState()
        val selection by source.selection.collectAsState()
        val shownNow by rememberUpdatedState(isShown)
        val quotesNow by rememberUpdatedState(liveQuotes)
        val actionNow by rememberUpdatedState(notificationAction)
        val scope = rememberCoroutineScope()
        val identity = owner.identity
        val holder = remember {
            SourceAlertSectionHolder(
                source = source,
                identity = identity,
                sources = sources,
                scope = scope,
                isShown = { shownNow() },
                liveQuotes = { quotesNow() },
                permission = { granted -> actionNow.run { granted() } },
                enableNotifications = { if (actionNow.needsAction) actionNow.run() }
            )
        }
        // Started when the mount is applied, not at the first effect dispatch after it.
        DisposableEffect(holder) {
            holder.start()
            onDispose { holder.dispose() }
        }
        val availability = sourceAlertAddAvailability(settings, identity)
        // While the memory is read, the add is drawn but disabled; the cap's notice is drawn as it is.
        val addEnabled = sourceSelectionSettled(selection, identity)
        val prompt = holder.deletePrompt
        val state = sourceAlertSectionState(settings, manual, identity)
        SourceAlertSectionView(
            state = state,
            surfaces = sourceAlertSurfaces(state, availability, addEnabled, notificationAction.needsAction),
            notice = holder.notice,
            deleteTarget = prompt?.row,
            onRetry = holder::retry,
            onRefresh = holder::refresh,
            onAdd = holder::openAdd,
            onEnableNotifications = holder::requestNotifications,
            onEdit = holder::openEdit,
            onToggle = holder::toggle,
            onDelete = holder::requestDelete,
            onDeleteConfirm = { holder.confirmDelete(prompt) },
            onDeleteCancel = { holder.cancelDelete(prompt) },
            modifier = modifier
        )
        val open = holder.editor
        if (open != null) {
            key(open) {
                val rows = settings.takeIf { it.identity == open.identity }?.settings
                    ?.let { SourceAlertRowPolicy.EXCHANGES.project(it, SourceAlertRowPolicy.SETTINGS_ASSET) }.orEmpty()
                SourceAlertEditorSheet(
                    mode = open.mode,
                    sources = sources,
                    asset = SourceAlertRowPolicy.SETTINGS_ASSET,
                    quotes = quotes,
                    existing = rows,
                    saving = open.handle != null,
                    errorMessage = open.error,
                    remaining = (availability as? SourceAlertAddAvailability.Available)?.remaining
                        ?: if (availability == SourceAlertAddAvailability.AtCap) 0 else null,
                    addEnabled = addEnabled && availability is SourceAlertAddAvailability.Available,
                    input = open.input,
                    onInput = { holder.input(open, it) },
                    onPick = { holder.pick(open, it) },
                    onSubmit = { holder.submit(open) },
                    onErrorDismiss = { holder.dismissError(open) },
                    onDismiss = { holder.dismiss(open) }
                )
            }
        }
    }
}

@Composable
private fun SourceAlertSectionView(
    state: SourceAlertSectionState,
    surfaces: SourceAlertSurfaces,
    notice: String?,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onEnableNotifications: () -> Unit,
    deleteTarget: SourceAlertSetting?,
    onEdit: (SourceAlertSetting) -> Unit,
    onToggle: (SourceAlertSetting, Boolean) -> Unit,
    onDelete: (SourceAlertSetting) -> Unit,
    onDeleteConfirm: () -> Unit,
    onDeleteCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }
    Column(
        modifier = modifier
            .testTag(SourceAlertSectionTags.ROOT)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .padding(vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable { isExpanded = !isExpanded }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Notifications, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("테더 알림", color = PrimaryText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (state.activeCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.testTag(SourceAlertSectionTags.ACTIVE_COUNT).size(18.dp).clip(CircleShape).background(Primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(state.activeCount.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            IconButton(
                onClick = onRefresh,
                enabled = state.refreshEnabled,
                modifier = Modifier.testTag(SourceAlertSectionTags.REFRESH).size(32.dp)
            ) {
                if (state.refreshSpinning) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "새로고침",
                        tint = if (state.refreshEnabled) SecondaryText else SecondaryText.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            IconButton(
                onClick = { isExpanded = !isExpanded },
                modifier = Modifier.testTag(SourceAlertSectionTags.TOGGLE_EXPANDED).size(32.dp)
            ) {
                Icon(
                    if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "접기" else "펼치기",
                    tint = SecondaryText,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        AnimatedVisibility(visible = isExpanded) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                notice?.let {
                    Text(it, color = SecondaryText, fontSize = 12.sp,
                        modifier = Modifier.testTag(SourceAlertSectionTags.NOTICE).padding(horizontal = 16.dp, vertical = 4.dp))
                }
                when (val content = state.content) {
                    SourceAlertSectionState.Content.Loading -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                    is SourceAlertSectionState.Content.Error -> Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(content.message, color = SecondaryText, fontSize = 13.sp)
                        TextButton(onClick = onRetry, modifier = Modifier.testTag(SourceAlertSectionTags.RETRY)) {
                            Text("다시 시도", color = Primary, fontSize = 13.sp)
                        }
                    }
                    SourceAlertSectionState.Content.Empty -> if (surfaces.permissionCard) {
                        Column(
                            modifier = Modifier.testTag(SourceAlertSectionTags.NOTIFY_PROMPT).fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("테더 알림을 받으려면\n알림 권한이 필요합니다", color = PrimaryText, fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                            Button(
                                onClick = onAdd,
                                enabled = surfaces.addEnabled,
                                modifier = Modifier.testTag(SourceAlertSectionTags.NOTIFY_PROMPT_BUTTON),
                                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White)
                            ) { Text("알림 권한 설정하기") }
                        }
                    } else {
                        Text(
                            "설정된 거래소 알림이 없습니다",
                            color = SecondaryText,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        SourceAlertAddArea(surfaces, onAdd)
                    }
                    is SourceAlertSectionState.Content.Rows -> Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        if (surfaces.banner) {
                            Row(
                                modifier = Modifier.testTag(SourceAlertSectionTags.NOTIFY_BANNER).fillMaxWidth()
                                    .clickable(onClick = onEnableNotifications).padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Notifications, contentDescription = null, tint = StatusError, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("알림을 받을 수 없습니다", color = PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("알림 권한을 설정해주세요", color = SecondaryText, fontSize = 12.sp)
                                }
                            }
                        }
                        content.rows.forEach { row ->
                            SourceAlertRow(row, onEdit = { onEdit(row) }, onToggle = { onToggle(row, it) }, onDelete = { onDelete(row) })
                        }
                        SourceAlertAddArea(surfaces, onAdd)
                    }
                }
            }
        }
    }
    deleteTarget?.let { row ->
        AlertDialog(
            onDismissRequest = onDeleteCancel,
            modifier = Modifier.testTag(SourceAlertSectionTags.DELETE_DIALOG),
            title = { Text("알림 삭제") },
            text = { Text("'${Exchange.fromCode(row.source)?.displayName ?: row.source}' 알림을 삭제하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = onDeleteConfirm, modifier = Modifier.testTag(SourceAlertSectionTags.DELETE_CONFIRM)) {
                    Text("삭제", color = StatusError)
                }
            },
            dismissButton = {
                TextButton(onClick = onDeleteCancel, modifier = Modifier.testTag(SourceAlertSectionTags.DELETE_CANCEL)) { Text("취소") }
            }
        )
    }
}

@Composable
private fun SourceAlertAddArea(surfaces: SourceAlertSurfaces, onAdd: () -> Unit) {
    when {
        surfaces.addButton -> Row(
            modifier = Modifier.testTag(SourceAlertSectionTags.ADD_BUTTON).fillMaxWidth().clickable(enabled = surfaces.addEnabled, onClick = onAdd)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("알림 추가", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            surfaces.remainingNote?.let {
                Spacer(Modifier.width(4.dp))
                Text(it, color = SecondaryText, fontSize = 12.sp)
            }
        }
        surfaces.atCap -> Text(
            SourceAlertEditor.Copy.ADD_AT_CAP,
            color = SecondaryText,
            fontSize = 12.sp,
            modifier = Modifier.testTag(SourceAlertSectionTags.ADD_AT_CAP).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SourceAlertRow(setting: SourceAlertSetting, onEdit: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val conditionColor = when (setting.condition) {
        AlertCondition.ABOVE -> PositiveColor
        AlertCondition.BELOW -> NegativeColor
    }
    Row(
        modifier = Modifier
            .testTag(SourceAlertSectionTags.ROW)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f).testTag(SourceAlertSectionTags.rowOpen(setting.id)).clickable(onClick = onEdit)
                .alpha(if (setting.isEnabled) 1f else 0.5f).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.width(96.dp)) {
                Text(
                    Exchange.fromCode(setting.source)?.displayName ?: setting.source,
                    color = PrimaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(if (setting.isEnabled) "켜짐" else "꺼짐", color = SecondaryText, fontSize = 11.sp)
                if (setting.triggered) Text("알림 발송됨", color = StatusConnecting, fontSize = 11.sp)
            }
            Text(
                ExchangeRate.formatRate(setting.threshold, setting.asset).replace("원", ""),
                color = PrimaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(Modifier.width(4.dp))
            Text(setting.condition.symbol, color = conditionColor, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(2.dp))
            Text(setting.condition.displayText, color = conditionColor, fontSize = 14.sp, maxLines = 1)
            if (setting.repeatIntervalSec != null) {
                Spacer(Modifier.width(8.dp))
                Text("반복", color = SecondaryText, fontSize = 12.sp)
            }
        }
        Switch(
            checked = setting.isEnabled,
            onCheckedChange = onToggle,
            modifier = Modifier.testTag(SourceAlertSectionTags.toggle(setting.id))
        )
        IconButton(onClick = onDelete, modifier = Modifier.testTag(SourceAlertSectionTags.delete(setting.id)).size(32.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "삭제", tint = SecondaryText, modifier = Modifier.size(18.dp))
        }
    }
}
