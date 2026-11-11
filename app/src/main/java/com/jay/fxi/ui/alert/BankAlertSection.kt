package com.jay.fxi.ui.alert

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.jay.fxi.data.alerts.AlertLoadFailure
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.BankAlertSettingsOwner
import com.jay.fxi.data.alerts.BankAlertSettingsState
import com.jay.fxi.data.alerts.ManualRefreshState
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

internal object BankAlertSectionTags {
    const val ROOT = "bank-alert-section"
    const val REFRESH = "bank-alert-refresh"
    const val TOGGLE_EXPANDED = "bank-alert-toggle-expanded"
    const val LOADING = "bank-alert-loading"
    const val ERROR = "bank-alert-error"
    const val RETRY = "bank-alert-retry"
    const val EMPTY = "bank-alert-empty"
    const val ROWS = "bank-alert-rows"
    fun row(id: Int): String = "bank-alert-row-$id"
}

/** Defined in this same holder file. A projection/delegation seam, never another canonical settings owner. */
internal interface BankAlertSectionSource {
    val settings: StateFlow<BankAlertSettingsState>
    val manualRefresh: StateFlow<ManualRefreshState>
    fun ensure()
    fun authoritative()
    fun manual()
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
        }
    }
}

@Composable
internal fun BankAlertSection(
    tab: FreeTab,
    modifier: Modifier = Modifier,
    sourceFactory: BankAlertSectionSourceFactory = ProductionBankAlertSectionSourceFactory
) {
    val currency = bankCurrencyOf(tab) ?: return
    val applicationContext = LocalContext.current.applicationContext
    val source = remember(applicationContext, sourceFactory) {
        sourceFactory.create(applicationContext)
    }
    key(source) {
        val settings by source.settings.collectAsState()
        val manual by source.manualRefresh.collectAsState()
        LaunchedEffect(source, tab) { source.ensure() }
        AlertSection(
            currency = currency,
            state = bankAlertSectionState(settings, manual, currency),
            onRetry = source::authoritative,
            onRefresh = source::manual,
            modifier = modifier
        )
    }
}
