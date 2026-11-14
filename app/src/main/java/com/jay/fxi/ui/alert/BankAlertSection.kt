package com.jay.fxi.ui.alert

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.jay.fxi.data.alerts.AlertLoadFailure
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.BankAlertDraft
import com.jay.fxi.data.alerts.BankAlertEdit
import com.jay.fxi.data.alerts.BankAlertSaveOutcome
import com.jay.fxi.data.alerts.BankAlertSaveRequest
import com.jay.fxi.data.alerts.BankAlertSettingsOwner
import com.jay.fxi.data.alerts.BankAlertSettingsState
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.alerts.NotSentReason
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.SupportedCurrency
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface BankAlertSectionEntryPoint {
    fun bankAlertSettingsOwner(): BankAlertSettingsOwner
}

internal data class BankAlertSectionState(
    val content: Content,
    val activeCount: Int,
    val refreshSpinning: Boolean,
    val refreshEnabled: Boolean
) {
    sealed interface Content {
        data object Loading : Content
        data class Error(val message: String) : Content   // Fixed copy, never server text.
        data object Empty : Content
        data class Rows(val rows: List<AlertSetting>) : Content
    }
}

internal fun bankCurrencyOf(tab: FreeTab): SupportedCurrency? = when (tab) {
    FreeTab.USD -> SupportedCurrency.USD_KRW
    FreeTab.JPY -> SupportedCurrency.JPY_KRW
    FreeTab.EUR -> SupportedCurrency.EUR_KRW
    else -> null
}

internal fun bankAlertSectionState(
    settings: BankAlertSettingsState,
    manual: ManualRefreshState,
    currency: SupportedCurrency
): BankAlertSectionState {
    val rows = settings.settings.orEmpty()
        .filter { it.currency == currency.code }
        .distinctBy { it.id }
        .sortedByDescending { it.createdAt }
    val phase = settings.phase
    val content = when {
        rows.isNotEmpty() -> BankAlertSectionState.Content.Rows(rows)
        phase == AlertLoadPhase.Loading || phase == AlertLoadPhase.Retrying -> BankAlertSectionState.Content.Loading
        phase is AlertLoadPhase.TerminalError -> BankAlertSectionState.Content.Error(bankAlertErrorMessage(phase.failure))
        else -> BankAlertSectionState.Content.Empty
    }
    return BankAlertSectionState(
        content = content,
        activeCount = rows.count { it.isEnabled },
        refreshSpinning = manual.sending,
        refreshEnabled = !manual.coolingDown && !manual.sending
    )
}

private fun bankAlertErrorMessage(failure: AlertLoadFailure): String = when (failure) {
    AlertLoadFailure.AuthRequired -> "로그인이 필요합니다."
    AlertLoadFailure.Forbidden -> "접근 권한이 없습니다."
    is AlertLoadFailure.Failed -> if (failure.kind == AlertRepositoryFailureKind.NETWORK) {
        "네트워크 연결을 확인해 주세요."
    } else {
        "알림 설정을 불러오지 못했습니다."
    }
}

internal const val BANK_ALERT_LIMIT = 30

internal sealed interface BankAlertAddAvailability {
    data object Hidden : BankAlertAddAvailability
    data class Available(val remaining: Int) : BankAlertAddAvailability
    data object AtCap : BankAlertAddAvailability
}

internal fun bankAlertAddAvailability(settings: BankAlertSettingsState): BankAlertAddAvailability {
    val rows = settings.settings
    if (settings.identity == null || rows == null) return BankAlertAddAvailability.Hidden
    val count = rows.map { it.id }.toSet().size
    return if (count < BANK_ALERT_LIMIT) {
        BankAlertAddAvailability.Available(BANK_ALERT_LIMIT - count)
    } else {
        BankAlertAddAvailability.AtCap
    }
}

internal object BankAlertEditorCopy {
    const val FAILED = "저장 결과를 확인하지 못했습니다. 알림 목록을 확인한 뒤 다시 시도해 주세요."
    const val CAP = "알림은 최대 30개까지 설정할 수 있습니다."
    const val NOT_SENT = "알림을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요."
}

internal fun bankAlertSheetRemaining(
    mode: BankAlertEditorMode,
    availability: BankAlertAddAvailability
): Int? = if (mode == BankAlertEditorMode.Add) {
    when (availability) {
        is BankAlertAddAvailability.Available -> availability.remaining
        BankAlertAddAvailability.AtCap -> 0
        BankAlertAddAvailability.Hidden -> null
    }
} else {
    null
}

private class BankAlertEditorSession {
    var alive = true
    var editor by mutableStateOf<OpenBankAlertEditor?>(null)
}

/** One instance per entry; request and error changes keep its reference stable. */
private class OpenBankAlertEditor(
    val mode: BankAlertEditorMode,
    val identity: AuthIdentityFence
) {
    var handle by mutableStateOf<BankAlertSaveRequest?>(null)
    var error by mutableStateOf<String?>(null)
}

internal object BankAlertSectionTags {
    const val ROOT = "bank-alert-section"
    const val REFRESH = "bank-alert-refresh"
    const val TOGGLE_EXPANDED = "bank-alert-toggle-expanded"
    const val LOADING = "bank-alert-loading"
    const val ERROR = "bank-alert-error"
    const val RETRY = "bank-alert-retry"
    const val EMPTY = "bank-alert-empty"
    const val NOTIFY_PROMPT = "bank-alert-notify-prompt"
    const val NOTIFY_PROMPT_BUTTON = "bank-alert-notify-prompt-button"
    const val NOTIFY_BANNER = "bank-alert-notify-banner"
    const val ROWS = "bank-alert-rows"
    const val ADD_BUTTON = "bank-alert-add-button"
    const val ADD_AT_CAP = "bank-alert-add-at-cap"
    fun rowOpen(id: Int): String = "bank-alert-row-open-$id"
    fun row(id: Int): String = "bank-alert-row-$id"
    fun toggle(id: Int): String = "bank-alert-toggle-$id"
    fun delete(id: Int): String = "bank-alert-delete-$id"
    const val DELETE_DIALOG = "bank-alert-delete-dialog"
    const val DELETE_CONFIRM = "bank-alert-delete-confirm"
    const val DELETE_CANCEL = "bank-alert-delete-cancel"
}

/** Defined in this same holder file. A projection/delegation seam, never another canonical settings owner. */
internal interface BankAlertSectionSource {
    val settings: StateFlow<BankAlertSettingsState>
    val manualRefresh: StateFlow<ManualRefreshState>
    fun ensure()
    fun authoritative()
    fun manual()
    fun setEnabled(id: Int, enabled: Boolean)
    fun delete(id: Int)
    fun add(draft: BankAlertDraft): BankAlertSaveRequest
    fun edit(id: Int, edit: BankAlertEdit): BankAlertSaveRequest
}

internal fun interface BankAlertSectionSourceFactory {
    fun create(context: Context): BankAlertSectionSource
}

internal object ProductionBankAlertSectionSourceFactory : BankAlertSectionSourceFactory {
    override fun create(context: Context): BankAlertSectionSource {
        val owner = EntryPointAccessors.fromApplication(
            context.applicationContext,
            BankAlertSectionEntryPoint::class.java
        ).bankAlertSettingsOwner()
        return object : BankAlertSectionSource {
            override val settings = owner.state
            override val manualRefresh = owner.manualRefresh
            override fun ensure() = owner.ensure()
            override fun authoritative() = owner.authoritative()
            override fun manual() = owner.manual()
            override fun setEnabled(id: Int, enabled: Boolean) = owner.setEnabled(id, enabled)
            override fun delete(id: Int) = owner.delete(id)
            override fun add(draft: BankAlertDraft) = owner.addAlert(draft)
            override fun edit(id: Int, edit: BankAlertEdit) = owner.editAlert(id, edit)
        }
    }
}

@Composable
internal fun BankAlertSection(
    tab: FreeTab,
    modifier: Modifier = Modifier,
    sourceFactory: BankAlertSectionSourceFactory = ProductionBankAlertSectionSourceFactory,
    alertQuotes: Map<String, Double> = emptyMap()
) {
    val currency = bankCurrencyOf(tab) ?: return
    val notificationAction = rememberNotificationPermissionAction()
    val applicationContext = LocalContext.current.applicationContext
    val source = remember(applicationContext, sourceFactory) {
        sourceFactory.create(applicationContext)
    }
    key(source) {
        val settings by source.settings.collectAsState()
        val manual by source.manualRefresh.collectAsState()
        val actionIdentity: AuthIdentityFence? = settings.identity
        val session = remember(currency) { BankAlertEditorSession() }
        val currentSession by rememberUpdatedState(session)
        val currentQuotes by rememberUpdatedState(alertQuotes)
        DisposableEffect(session) {
            onDispose { session.alive = false }
        }
        val availability = bankAlertAddAvailability(settings)
        val open = session.editor
        LaunchedEffect(session, open, settings) {
            if (open != null && session.editor === open) {
                val mode = open.mode
                if (settings.identity != open.identity ||
                    (mode is BankAlertEditorMode.Edit && settings.settings.orEmpty().none {
                        it.id == mode.setting.id && it.currency == currency.code
                    })
                ) {
                    session.editor = null
                }
            }
        }
        LaunchedEffect(source, tab) { source.ensure() }
        AlertSection(
            currency = currency,
            state = bankAlertSectionState(settings, manual, currency),
            onRetry = source::authoritative,
            onRefresh = source::manual,
            actionIdentity = actionIdentity,
            onToggle = { id, enabled -> if (actionIdentity != null && source.settings.value.identity == actionIdentity) source.setEnabled(id, enabled) },
            onDelete = { id -> if (actionIdentity != null && source.settings.value.identity == actionIdentity) source.delete(id) },
            notificationsBlocked = notificationAction.needsAction,
            onEnableNotifications = { notificationAction.run() },
            addAvailability = availability,
            onAdd = {
                val captured = source.settings.value.identity
                if (session.alive && currentSession === session && session.editor == null && captured != null) {
                    notificationAction.run {
                        val live = source.settings.value
                        if (session.alive && currentSession === session && session.editor == null &&
                            live.identity == captured &&
                            bankAlertAddAvailability(live) is BankAlertAddAvailability.Available
                        ) {
                            session.editor = OpenBankAlertEditor(BankAlertEditorMode.Add, captured)
                        }
                    }
                }
            },
            onEdit = { id ->
                val live = source.settings.value
                val row = live.settings.orEmpty().firstOrNull {
                    it.id == id && it.currency == currency.code
                }
                val identity = live.identity
                if (session.alive && currentSession === session && session.editor == null &&
                    identity != null && identity == actionIdentity && row != null
                ) {
                    session.editor = OpenBankAlertEditor(BankAlertEditorMode.Edit(row.copy()), identity)
                }
            },
            modifier = modifier
        )
        if (open != null) {
            key(open) {
                val mode = open.mode
                val handle = open.handle
                // A new request starts with its own value, including an immediate terminal refusal.
                val outcome = key(handle) {
                    handle?.outcome?.collectAsState()?.value
                }
                LaunchedEffect(open, handle, outcome) {
                    if (handle == null || session.editor !== open || open.handle !== handle) return@LaunchedEffect
                    if (!session.alive) return@LaunchedEffect
                    when (outcome) {
                        null, BankAlertSaveOutcome.Pending -> Unit
                        is BankAlertSaveOutcome.Saved, BankAlertSaveOutcome.Removed -> session.editor = null
                        is BankAlertSaveOutcome.Failed -> open.error = BankAlertEditorCopy.FAILED
                        is BankAlertSaveOutcome.NotSent -> when (outcome.reason) {
                            NotSentReason.CAP -> open.error = BankAlertEditorCopy.CAP
                            NotSentReason.NOT_READY, NotSentReason.UNADMITTED, NotSentReason.BACKGROUND ->
                                open.error = BankAlertEditorCopy.NOT_SENT
                            NotSentReason.SUPERSEDED -> Unit
                            NotSentReason.RESET -> session.editor = null
                        }
                    }
                }
                AlertAddSheet(
                    mode = mode,
                    currency = currency,
                    quotes = alertQuotes,
                    existing = settings.settings.orEmpty().filter { it.currency == currency.code },
                    saving = outcome == BankAlertSaveOutcome.Pending,
                    errorMessage = open.error,
                    remaining = bankAlertSheetRemaining(mode, availability),
                    onSubmit = { input ->
                        notificationAction.run {
                            val live = source.settings.value
                            val rows = live.settings.orEmpty().filter { it.currency == currency.code }
                            if (!session.alive || currentSession !== session || session.editor !== open) return@run
                            if (live.identity != open.identity) return@run
                            if (open.handle?.outcome?.value == BankAlertSaveOutcome.Pending) return@run
                            if (mode is BankAlertEditorMode.Edit && rows.none { it.id == mode.setting.id }) return@run
                            val check = BankAlertEditor.check(mode, input, currency, currentQuotes, rows)
                            if (!check.canSave) {
                                open.error = BankAlertEditorCopy.NOT_SENT
                                return@run
                            }
                            val submit = BankAlertEditor.submit(mode, input, currency)
                            open.error = null
                            open.handle = when (submit) {
                                is BankAlertSubmit.Add -> source.add(submit.draft)
                                is BankAlertSubmit.Edit -> source.edit(submit.id, submit.edit)
                            }
                        }
                    },
                    onErrorDismiss = {
                        if (session.alive && session.editor === open) open.error = null
                    },
                    onDismiss = {
                        if (session.alive && session.editor === open) session.editor = null
                    }
                )
            }
        }
    }
}
