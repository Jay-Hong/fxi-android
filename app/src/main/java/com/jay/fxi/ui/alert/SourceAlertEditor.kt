package com.jay.fxi.ui.alert

import com.jay.fxi.data.alerts.NotSentReason
import com.jay.fxi.data.alerts.SourceAlertDraft
import com.jay.fxi.data.alerts.SourceAlertEdit
import com.jay.fxi.data.alerts.SourceAlertSaveOutcome
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.domain.model.SourceAlertSetting
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor

/**
 * S8-5b (`R4c/S8/s8_5b_contract.agreed.r1.md` §2): the tether alert editor's pure rules — seed, the one normalised price,
 * range, duplicate and change checks, the typed draft or minimal patch, and the fixed copy of each result. The allowed
 * sources come from the caller (the section reads the row policy once); this file names no exchange and no policy.
 */
internal sealed interface SourceAlertEditorMode {
    data object Add : SourceAlertEditorMode
    data class Edit(val setting: SourceAlertSetting) : SourceAlertEditorMode
}

internal data class SourceAlertEditorInput(
    val source: String,
    val condition: AlertCondition,
    val thresholdText: String,
    val repeat: RepeatIntervalChange
)

internal data class SourceAlertEditorCheck(
    /** The input normalised to the whole won, the one value range, duplicate, change and the request use. */
    val threshold: Double?,
    val quote: Double?,
    val range: ClosedFloatingPointRange<Double>?,
    /** An edit that keeps the source, the asset, the condition and the price: allowed without a quote or out of range. */
    val priceUnchanged: Boolean,
    val outOfRange: Boolean,
    val noQuote: Boolean,
    val duplicate: Boolean,
    val changed: Boolean,
    val validSelection: Boolean,
    val canSave: Boolean
)

internal sealed interface SourceAlertSubmit {
    data class Add(val draft: SourceAlertDraft) : SourceAlertSubmit
    data class Edit(val id: Int, val edit: SourceAlertEdit) : SourceAlertSubmit
}

internal data class SourceAlertRepeatTexts(val label: String, val description: String, val banner: String, val repeats: Boolean)

/** What the sheet shows after a result, and whether its draft stays open. */
internal data class SourceAlertResultView(val message: String?, val close: Boolean)

internal object SourceAlertEditor {
    /** Two prices are the same when their exact decimal difference is below 0.005; exactly 0.005 apart they are not. */
    private val SAME_THRESHOLD = BigDecimal("0.005")

    private fun samePrice(a: Double, b: Double): Boolean =
        a.isFinite() && b.isFinite() && BigDecimal.valueOf(a).subtract(BigDecimal.valueOf(b)).abs() < SAME_THRESHOLD

    val REPEAT_CHOICES: List<RepeatInterval?> = listOf(null) + RepeatInterval.entries

    object Copy {
        const val ADD_TITLE = "알림 추가"
        const val EDIT_TITLE = "알림 수정"
        const val CANCEL = "취소"
        const val SAVE = "저장"
        const val SAVING = "저장 중..."
        const val ERROR_TITLE = "오류"
        const val OK = "확인"
        const val DUPLICATE = "동일한 알림이 이미 존재합니다"
        const val OUT_OF_RANGE = "현재 시세의 ±50% 범위를 벗어났습니다"
        const val NO_QUOTE = "현재 시세를 확인할 수 없어 가격 조건을 저장할 수 없습니다"
        const val PRICE_KEPT = "가격 조건을 유지한 채 활성·반복 설정만 변경할 수 있습니다"
        const val CAP = "알림은 최대 30개까지 설정할 수 있습니다."
        const val ADD_AT_CAP = "최대 30개까지 설정할 수 있습니다"
        const val REMOVED = "이미 삭제된 알림입니다."
        const val UNSUPPORTED = "지원되는 거래소를 선택해 주세요."
        const val REJECTED = "지원되는 거래소와 입력값을 확인해 주세요."
        const val UNKNOWN = "저장 결과를 확인하지 못했습니다. 알림 목록을 확인한 뒤 다시 시도해 주세요."
        const val NOT_SAVED = "알림을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요."
    }

    fun initial(
        mode: SourceAlertEditorMode,
        sources: List<String>,
        quotes: Map<String, Double>,
        remembered: String? = null
    ): SourceAlertEditorInput = when (mode) {
        SourceAlertEditorMode.Add -> {
            val source = remembered?.takeIf { it in sources } ?: sources.first()
            SourceAlertEditorInput(
                source = source,
                condition = AlertCondition.BELOW,
                thresholdText = usableQuote(quotes[source])?.let(::formatThreshold).orEmpty(),
                repeat = RepeatIntervalChange.Once
            )
        }
        is SourceAlertEditorMode.Edit -> SourceAlertEditorInput(
            source = mode.setting.source,
            condition = mode.setting.condition,
            thresholdText = formatThreshold(mode.setting.threshold),
            repeat = RepeatIntervalChange.Keep
        )
    }

    /** Only an add's explicit pick refills from that source's quote; without one the typed text stays. */
    fun onSourcePicked(
        input: SourceAlertEditorInput,
        source: String,
        mode: SourceAlertEditorMode,
        quotes: Map<String, Double>
    ): SourceAlertEditorInput = input.copy(
        source = source,
        thresholdText = if (mode == SourceAlertEditorMode.Add) {
            usableQuote(quotes[source])?.let(::formatThreshold) ?: input.thresholdText
        } else {
            input.thresholdText
        }
    )

    /** `[round10(0.5q), round10(1.5q)]`, both ends included; none without a usable quote or for a non-finite bound. */
    fun range(quote: Double?): ClosedFloatingPointRange<Double>? {
        val q = usableQuote(quote) ?: return null
        val lower = floor(q * 0.5 / 10.0 + 0.5) * 10.0
        val upper = floor(q * 1.5 / 10.0 + 0.5) * 10.0
        return if (lower.isFinite() && upper.isFinite()) lower..upper else null
    }

    /**
     * The decimal input as typed, rounded to the nearest won, half up, in exact decimal; null for an empty, malformed, negative,
     * zero or overflowing input, or one that rounds to zero.
     */
    fun normalize(text: String): Double? {
        if (text.isEmpty() || text.any { it !in '0'..'9' && it != '.' } || text.count { it == '.' } > 1) return null
        val value = text.toBigDecimalOrNull() ?: return null
        if (value.signum() <= 0) return null
        return value.setScale(0, RoundingMode.HALF_UP).toDouble().takeIf { it.isFinite() && it > 0 }
    }

    fun check(
        mode: SourceAlertEditorMode,
        input: SourceAlertEditorInput,
        sources: List<String>,
        asset: String,
        quotes: Map<String, Double>,
        existing: List<SourceAlertSetting>
    ): SourceAlertEditorCheck {
        val threshold = normalize(input.thresholdText)
        val quote = usableQuote(quotes[input.source])
        val range = range(quote)
        val original = (mode as? SourceAlertEditorMode.Edit)?.setting
        val priceUnchanged = original != null && threshold != null &&
            original.source == input.source && original.asset == asset && original.condition == input.condition &&
            samePrice(original.threshold, threshold)
        val inRange = threshold != null && range != null && threshold in range
        val outOfRange = threshold != null && range != null && threshold !in range
        val duplicate = threshold != null && existing.any { row ->
            row.id != original?.id &&
                row.source == input.source &&
                row.asset == asset &&
                row.condition == input.condition &&
                samePrice(row.threshold, threshold)
        }
        val changed = when {
            original == null -> true
            else -> input.source != original.source ||
                input.condition != original.condition ||
                (threshold != null && !samePrice(threshold, original.threshold)) ||
                !original.isEnabled ||
                repeatSeconds(mode, input.repeat) != original.repeatIntervalSec
        }
        val validSelection = input.source in sources &&
            (mode !is SourceAlertEditorMode.Add || input.repeat != RepeatIntervalChange.Keep)
        return SourceAlertEditorCheck(
            threshold = threshold,
            quote = quote,
            range = range,
            priceUnchanged = priceUnchanged,
            outOfRange = outOfRange && !priceUnchanged,
            noQuote = range == null && !priceUnchanged,
            duplicate = duplicate,
            changed = changed,
            validSelection = validSelection,
            canSave = threshold != null && validSelection && (priceUnchanged || inRange) && !duplicate && changed
        )
    }

    /** The guidance under the price field, or null; the first of duplicate, out of range, no quote and the kept-price hint. */
    fun hint(check: SourceAlertEditorCheck): String? {
        val threshold = check.threshold
        val range = check.range
        return when {
            check.duplicate -> Copy.DUPLICATE
            check.outOfRange -> Copy.OUT_OF_RANGE
            check.noQuote && threshold != null -> Copy.NO_QUOTE
            check.priceUnchanged && (range == null || (threshold != null && threshold !in range)) -> Copy.PRICE_KEPT
            else -> null
        }
    }

    fun submit(mode: SourceAlertEditorMode, input: SourceAlertEditorInput, sources: List<String>): SourceAlertSubmit {
        require(input.source in sources)
        val threshold = requireNotNull(normalize(input.thresholdText))
        return when (mode) {
            SourceAlertEditorMode.Add -> SourceAlertSubmit.Add(
                SourceAlertDraft(
                    source = input.source,
                    condition = input.condition,
                    threshold = threshold,
                    isEnabled = true,
                    repeat = when (val repeat = input.repeat) {
                        RepeatIntervalChange.Once -> null
                        is RepeatIntervalChange.Every -> repeat.interval
                        RepeatIntervalChange.Keep -> error("Invalid repeat choice")
                    }
                )
            )
            is SourceAlertEditorMode.Edit -> {
                val original = mode.setting
                SourceAlertSubmit.Edit(
                    id = original.id,
                    edit = SourceAlertEdit(
                        source = input.source.takeIf { it != original.source },
                        condition = input.condition.takeIf { it != original.condition },
                        threshold = threshold.takeUnless { samePrice(it, original.threshold) },
                        isEnabled = if (original.isEnabled) null else true,
                        repeat = if (repeatSeconds(mode, input.repeat) == original.repeatIntervalSec) {
                            RepeatIntervalChange.Keep
                        } else {
                            input.repeat
                        }
                    )
                )
            }
        }
    }

    /** One result for the current request: its sheet guidance and whether the sheet closes. Pending shows nothing new. */
    fun result(outcome: SourceAlertSaveOutcome): SourceAlertResultView? = when (outcome) {
        SourceAlertSaveOutcome.Pending -> null
        is SourceAlertSaveOutcome.Saved -> SourceAlertResultView(null, close = true)
        SourceAlertSaveOutcome.Removed -> SourceAlertResultView(Copy.REMOVED, close = true)
        SourceAlertSaveOutcome.Unsupported -> SourceAlertResultView(Copy.UNSUPPORTED, close = false)
        is SourceAlertSaveOutcome.Failed -> {
            val http = (outcome.failure as? AlertRepositoryException)?.httpFailure
            SourceAlertResultView(if (http?.statusCode == 400) Copy.REJECTED else Copy.UNKNOWN, close = false)
        }
        is SourceAlertSaveOutcome.NotSent -> when (outcome.reason) {
            NotSentReason.CAP -> SourceAlertResultView(Copy.CAP, close = false)
            NotSentReason.NOT_READY, NotSentReason.UNADMITTED, NotSentReason.BACKGROUND -> SourceAlertResultView(Copy.NOT_SAVED, close = false)
            NotSentReason.SUPERSEDED -> SourceAlertResultView(null, close = false)
            NotSentReason.RESET -> SourceAlertResultView(null, close = true)
        }
    }

    fun repeatTexts(mode: SourceAlertEditorMode, repeat: RepeatIntervalChange): SourceAlertRepeatTexts {
        val seconds = repeatSeconds(mode, repeat)
            ?: return SourceAlertRepeatTexts("한번만", "조건 충족 시 1회만 발송", "알림은 1회 발송 후 자동으로 꺼집니다", false)
        val interval = RepeatInterval.from(seconds)
            ?: return SourceAlertRepeatTexts("기존 반복 간격 유지", "조건 충족 동안 반복 발송", "조건이 충족되는 동안 반복 발송됩니다", true)
        return SourceAlertRepeatTexts(
            interval.label,
            "조건 충족 동안 ${interval.everyLabel} 발송",
            "조건이 충족되는 동안 ${interval.everyLabel} 반복 발송됩니다",
            true
        )
    }

    /** A whole won, no grouping: the seed of a quote and the shown value of a row. */
    fun formatThreshold(value: Double): String = BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).toPlainString()

    private fun usableQuote(quote: Double?): Double? = quote?.takeIf { it.isFinite() && it > 0 }

    private fun repeatSeconds(mode: SourceAlertEditorMode, repeat: RepeatIntervalChange): Int? = when (repeat) {
        RepeatIntervalChange.Keep -> (mode as? SourceAlertEditorMode.Edit)?.setting?.repeatIntervalSec
        RepeatIntervalChange.Once -> null
        is RepeatIntervalChange.Every -> repeat.interval.seconds
    }
}
