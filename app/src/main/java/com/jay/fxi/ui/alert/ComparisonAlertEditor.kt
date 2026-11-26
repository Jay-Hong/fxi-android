package com.jay.fxi.ui.alert

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jay.fxi.data.alerts.ComparisonAlertHandle
import com.jay.fxi.data.alerts.ComparisonAlertNotSentReason
import com.jay.fxi.data.alerts.ComparisonAlertOutcome
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.domain.model.ComparisonAlertSetting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal const val comparisonCap = "최대 30개까지 설정할 수 있습니다"
internal const val comparisonDuplicate = "이미 등록된 알림이에요. 목록에서 켜서 사용하세요."

internal class ComparisonOpening(val original: ComparisonAlertSetting?, initial: ComparisonFields) {
    var fields by mutableStateOf(initial)
    var pending by mutableStateOf(false)
    var latched by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var unsupported: ComparisonFields? by mutableStateOf(null)
    var handle: ComparisonAlertHandle? = null
}

internal class ComparisonConfirmation(val row: ComparisonAlertSetting)

/** All entry points, including terminal callbacks, run on Main. */
internal class ComparisonAlertSectionHolder(
    private val source: ComparisonAlertSectionSource,
    private val identity: AuthIdentityFence,
    private val tab: String,
    private val kind: String,
    private val scope: CoroutineScope,
    private val live: () -> Boolean,
    private val historyOpen: () -> Boolean,
    private val quotes: () -> ComparisonQuotes,
    private val permission: (() -> Unit) -> Unit
) {
    var editor by mutableStateOf<ComparisonOpening?>(null)
        private set
    var confirm by mutableStateOf<ComparisonConfirmation?>(null)
        private set
    var rowMessage by mutableStateOf<String?>(null)
        private set
    var expanded by mutableStateOf(true)
        private set
    private var retired = false
    private var grantToken: Any? = null
    private var rowEpoch = Any()

    private fun active(): Boolean {
        if (!retired && !live()) retire()
        return !retired && expanded
    }
    private val supportedSlot = when (kind) {
        "absolute" -> tab in setOf("tether", "usd", "jpy", "eur")
        "signed" -> tab == "tether"
        else -> false
    }
    private fun snapshot() = source.state.value.takeIf { supportedSlot && active() && it.identity == identity }?.adopted
    private fun count() = snapshot()?.count
    val addAvailable get() = count()?.let { it < 30 } == true
    val remaining get() = count()?.let { 30 - it }?.takeIf { it in 1..3 }?.let { "(${it}개 남음)" }
    val modalFree get() = editor == null && confirm == null && !historyOpen()

    private fun current(row: ComparisonAlertSetting) = snapshot()?.rows?.firstOrNull {
        it.tab == tab && it.diffType == kind && it.id == row.id && it.originalPair().matches(row.originalPair())
    }

    fun add() {
        if (!addAvailable || !modalFree) return
        val token = Any()
        grantToken = token
        permission {
            if (grantToken === token) {
                grantToken = null
                if (addAvailable && modalFree) editor = ComparisonOpening(null, ComparisonFields.create(tab, kind, quotes()))
            }
        }
    }

    fun edit(row: ComparisonAlertSetting) {
        val target = current(row) ?: return
        if (modalFree) editor = ComparisonOpening(target, ComparisonFields.restore(target))
    }

    fun dismiss(token: ComparisonOpening) {
        if (editor === token) {
            editor = null
            grantToken = null
        }
    }

    fun dismiss(token: ComparisonConfirmation) {
        if (confirm === token) {
            confirm = null
            grantToken = null
        }
    }

    fun reconcile() {
        editor?.let { token ->
            if (!token.pending && (snapshot() == null || token.original?.let { current(it) == null } == true)) dismiss(token)
        }
        confirm?.let { if (current(it.row) == null) dismiss(it) }
    }

    fun expand(value: Boolean) {
        if (!value) resetOpenings()
        expanded = value
    }

    private fun resetOpenings() {
        grantToken = null
        editor = null
        confirm = null
        rowEpoch = Any()
        rowMessage = null
    }

    fun retire() {
        retired = true
        resetOpenings()
    }

    fun change(token: ComparisonOpening, update: (ComparisonFields, ComparisonQuotes) -> ComparisonFields) {
        if (active() && editor === token && !token.pending) token.fields = update(token.fields, quotes())
    }

    fun message(token: ComparisonOpening): String? = when {
        token.latched -> comparisonPartialFailure
        token.pending -> null
        token.error != null -> token.error
        token.original == null && !addAvailable -> comparisonCap
        token.fields.duplicate(snapshot()?.rows.orEmpty(), token.original?.id) -> comparisonDuplicate
        else -> null
    }

    fun canSave(token: ComparisonOpening): Boolean {
        val rows = snapshot() ?: return false
        val original = token.original
        return editor === token && !token.pending && !token.latched && token.fields != token.unsupported && token.fields.draft() != null &&
            (if (original == null) addAvailable else current(original) != null) &&
            !token.fields.duplicate(rows.rows, original?.id)
    }

    fun save(token: ComparisonOpening) {
        if (!canSave(token)) return
        val draft = token.fields.draft() ?: return
        val original = token.original
        token.error = null
        token.pending = true
        val handle = if (original == null) source.add(identity, draft)
            else source.edit(identity, original.id, original.originalPair(), draft)
        token.handle = handle
        observe(handle) { result ->
            if (editor === token && token.handle === handle && active()) {
                token.pending = false
                token.handle = null
                when (result) {
                    is ComparisonAlertOutcome.OldNotDeleted -> token.latched = true
                    is ComparisonAlertOutcome.Failed -> token.error = "저장에 실패했습니다. 잠시 후 다시 시도해주세요."
                    is ComparisonAlertOutcome.NotSent -> {
                        if (result.reason == ComparisonAlertNotSentReason.BACKGROUND ||
                            result.reason == ComparisonAlertNotSentReason.UNADMITTED && snapshot() == null) dismiss(token)
                        else {
                            if (result.reason == ComparisonAlertNotSentReason.UNSUPPORTED) token.unsupported = token.fields
                            token.error = reasonText(result.reason)
                        }
                    }
                    else -> dismiss(token)
                }
                reconcile()
            }
        }
    }

    fun toggle(row: ComparisonAlertSetting, enabled: Boolean) {
        if (current(row) == null || !modalFree) return
        rowResult(source.setEnabled(identity, row.id, enabled), "알림 변경에 실패했습니다.")
    }

    fun askDelete(row: ComparisonAlertSetting) {
        if (current(row) != null && modalFree) confirm = ComparisonConfirmation(row)
    }

    fun delete(token: ComparisonConfirmation) {
        if (token !== confirm) return
        confirm = null
        if (current(token.row) != null) rowResult(source.delete(identity, token.row.id), "알림을 삭제하지 못했습니다.")
    }

    private fun rowResult(handle: ComparisonAlertHandle, failure: String) {
        val epoch = rowEpoch
        rowMessage = null
        observe(handle) { result ->
            if (rowEpoch === epoch && active()) {
                rowMessage = when (result) {
                    is ComparisonAlertOutcome.Failed -> failure
                    is ComparisonAlertOutcome.NotSent -> reasonText(result.reason)
                    else -> null
                }
            }
        }
    }

    private fun observe(handle: ComparisonAlertHandle, apply: (ComparisonAlertOutcome) -> Unit) {
        val current = handle.outcome.value
        if (current != ComparisonAlertOutcome.Pending) apply(current)
        else scope.launch { apply(handle.outcome.first { it != ComparisonAlertOutcome.Pending }) }
    }

    private fun reasonText(reason: ComparisonAlertNotSentReason) = when (reason) {
        ComparisonAlertNotSentReason.NOT_READY -> "알림 목록을 다시 확인해 주세요."
        ComparisonAlertNotSentReason.UNSUPPORTED -> "지원하지 않는 알림 조건입니다."
        ComparisonAlertNotSentReason.CAP -> comparisonCap
        ComparisonAlertNotSentReason.BUSY -> "처리 중인 알림입니다."
        ComparisonAlertNotSentReason.UNADMITTED -> "접근 권한이 없습니다."
        ComparisonAlertNotSentReason.BACKGROUND -> null
    }
}
