package com.jay.fxi.ui.alert

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.ui.components.BankIcon
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.InputBackground
import com.jay.fxi.ui.theme.LocalRateLayoutMetrics
import com.jay.fxi.ui.theme.NegativeColor
import com.jay.fxi.ui.theme.PositiveColor
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.theme.StatusConnecting
import com.jay.fxi.ui.theme.StatusError
import java.text.NumberFormat
import java.util.Locale

internal object AlertAddSheetTags {
    const val SHEET = "alert-editor-sheet"
    const val CANCEL = "alert-editor-cancel"
    const val SAVE = "alert-editor-save"
    const val TITLE = "alert-editor-title"
    const val HEADER_BANK = "alert-editor-header-bank"
    fun bank(code: String) = "alert-editor-bank-$code"
    const val THRESHOLD = "alert-editor-threshold"
    const val CONDITION = "alert-editor-condition"
    const val CURRENT_RATE = "alert-editor-current-rate"
    const val RANGE = "alert-editor-range"
    const val OUT_OF_RANGE = "alert-editor-out-of-range"
    const val DUPLICATE = "alert-editor-duplicate"
    const val REPEAT = "alert-editor-repeat"
    const val REPEAT_LABEL = "alert-editor-repeat-label"
    const val REPEAT_DESCRIPTION = "alert-editor-repeat-description"
    fun repeatOption(i: Int) = "alert-editor-repeat-option-$i"
    const val BANNER_REPEAT = "alert-editor-banner-repeat"
    const val REMAINING = "alert-editor-remaining"
    const val REMAINING_ICON = "alert-editor-remaining-icon"
    const val SAVING = "alert-editor-saving"
    const val ERROR_DIALOG = "alert-editor-error-dialog"
    const val ERROR_CONFIRM = "alert-editor-error-confirm"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlertAddSheet(
    mode: BankAlertEditorMode,
    currency: SupportedCurrency,
    quotes: Map<String, Double>,
    existing: List<AlertSetting>,
    saving: Boolean,
    errorMessage: String?,
    remaining: Int?,
    onSubmit: (BankAlertEditorInput) -> Unit,
    onErrorDismiss: () -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember(mode) { mutableStateOf(BankAlertEditor.initial(mode, quotes)) }
    val check = BankAlertEditor.check(mode, input, currency, quotes, existing)
    val canSave = check.canSave && !saving
    val thresholdColor = when (BankAlertEditor.tone(check)) {
        BankAlertThresholdTone.NONE, BankAlertThresholdTone.SAME -> PrimaryText
        BankAlertThresholdTone.ABOVE -> PositiveColor.copy(alpha = 0.85f)
        BankAlertThresholdTone.BELOW -> NegativeColor.copy(alpha = 0.85f)
    }
    val repeatTexts = BankAlertEditor.repeatTexts(mode, input.repeat)
    val bankName = input.bank?.displayName
        ?: (mode as? BankAlertEditorMode.Edit)?.setting?.bank.orEmpty()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(AlertAddSheetTags.SHEET),
        containerColor = Background,
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.width(36.dp).height(5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(SecondaryText.copy(alpha = 0.4f))
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag(AlertAddSheetTags.CANCEL)) {
                        Text("취소", color = SecondaryText)
                    }
                    Text(
                        text = if (mode == BankAlertEditorMode.Add) "알림 추가" else "알림 수정",
                        modifier = Modifier.testTag(AlertAddSheetTags.TITLE),
                        color = PrimaryText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = { if (canSave) onSubmit(input) },
                        enabled = canSave,
                        modifier = Modifier.testTag(AlertAddSheetTags.SAVE)
                    ) {
                        Text(
                            "저장",
                            color = if (canSave) Primary else SecondaryText.copy(alpha = 0.5f),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp)
                ) {
                    Spacer(Modifier.height(8.dp))
                    BankPickerSection(
                        bankName = bankName,
                        selectedBank = input.bank,
                        banks = BankAlertEditor.BANK_CHOICES,
                        currency = currency,
                        enabled = !saving,
                        onBankSelected = { bank ->
                            if (!saving) input = BankAlertEditor.onBankPicked(input, bank, mode, quotes)
                        }
                    )
                    Spacer(Modifier.height(16.dp))
                    ThresholdInputSection(
                        thresholdText = input.thresholdText,
                        onThresholdChange = { raw ->
                            if (!saving) input = input.copy(thresholdText = BankAlertEditor.sanitize(raw))
                        },
                        thresholdColor = thresholdColor,
                        condition = input.condition,
                        enabled = !saving,
                        onConditionToggle = {
                            if (!saving) input = input.copy(
                                condition = if (input.condition == AlertCondition.ABOVE) AlertCondition.BELOW
                                else AlertCondition.ABOVE
                            )
                        }
                    )
                    HelperTextSection(bankName, check, currency)
                    Spacer(Modifier.height(12.dp))
                    RepeatSection(
                        mode = mode,
                        texts = repeatTexts,
                        choices = BankAlertEditor.REPEAT_CHOICES,
                        enabled = !saving,
                        onRepeatSelected = { repeat ->
                            if (!saving) input = input.copy(repeat = repeat)
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                    InfoBanner(
                        texts = repeatTexts,
                        remaining = remaining.takeIf { mode == BankAlertEditorMode.Add }
                    )
                }
            }
            if (saving) SavingOverlay()
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = onErrorDismiss,
            modifier = Modifier.testTag(AlertAddSheetTags.ERROR_DIALOG),
            title = { Text("오류") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(
                    onClick = onErrorDismiss,
                    modifier = Modifier.testTag(AlertAddSheetTags.ERROR_CONFIRM)
                ) { Text("확인") }
            }
        )
    }
}

@Composable
private fun BankPickerSection(
    bankName: String,
    selectedBank: Bank?,
    banks: List<Bank>,
    currency: SupportedCurrency,
    enabled: Boolean,
    onBankSelected: (Bank) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = bankName,
                modifier = Modifier.testTag(AlertAddSheetTags.HEADER_BANK),
                color = PrimaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(currency.tabTitle, color = PrimaryText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Text("(${currency.displayName})", color = SecondaryText, fontSize = 12.sp)
            }
        }
        SegmentedBankGrid(selectedBank, banks, enabled, onBankSelected)
    }
}

@Composable
private fun SegmentedBankGrid(
    selectedBank: Bank?,
    banks: List<Bank>,
    enabled: Boolean,
    onBankSelected: (Bank) -> Unit
) {
    val dividerColor = Color.Gray.copy(alpha = 0.3f)
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .border(1.dp, dividerColor, RoundedCornerShape(12.dp))
    ) {
        val columns = if (maxWidth >= 500.dp) banks.size else 5
        val cellSize = (maxWidth - (columns - 1).dp) / columns
        Column {
            banks.chunked(columns).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) HorizontalDivider(color = dividerColor)
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEachIndexed { index, bank ->
                        if (index > 0) VerticalDivider(modifier = Modifier.height(cellSize), color = dividerColor)
                        BankSegmentCell(bank, bank == selectedBank, cellSize, enabled) { onBankSelected(bank) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BankSegmentCell(
    bank: Bank,
    isSelected: Boolean,
    cellSize: Dp,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val bankColor = Color(bank.colorHex)
    val scale by animateFloatAsState(targetValue = if (isSelected) 1.05f else 1f, label = "scale")
    Box(
        modifier = Modifier.size(cellSize)
            .testTag(AlertAddSheetTags.bank(bank.code))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Box(Modifier.fillMaxSize().background(bankColor.copy(alpha = 0.15f)))
            Box(
                Modifier.fillMaxSize().padding(3.dp)
                    .border(2.5.dp, bankColor, RoundedCornerShape(6.dp))
            )
        }
        BankIcon(
            bank = bank,
            isSelected = isSelected,
            useHighRes = true,
            modifier = Modifier.size(cellSize * 0.68f).scale(scale)
        )
    }
}

@Composable
private fun ThresholdInputSection(
    thresholdText: String,
    onThresholdChange: (String) -> Unit,
    thresholdColor: Color,
    condition: AlertCondition,
    enabled: Boolean,
    onConditionToggle: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("알림 받을 환율", color = SecondaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(InputBackground)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = thresholdText,
                    onValueChange = onThresholdChange,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).testTag(AlertAddSheetTags.THRESHOLD),
                    textStyle = TextStyle(
                        color = thresholdColor,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.End
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    cursorBrush = SolidColor(Primary)
                )
            }
            ConditionToggleButton(condition, enabled, onConditionToggle)
        }
    }
}

@Composable
private fun ConditionToggleButton(condition: AlertCondition, enabled: Boolean, onClick: () -> Unit) {
    val metrics = LocalRateLayoutMetrics.current
    val conditionColor = if (condition == AlertCondition.ABOVE) PositiveColor else NegativeColor
    Row(
        modifier = Modifier.clip(RoundedCornerShape(12.dp))
            .background(conditionColor.copy(alpha = 0.15f))
            .border(2.dp, conditionColor, RoundedCornerShape(12.dp))
            .testTag(AlertAddSheetTags.CONDITION)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            condition.symbol,
            color = conditionColor,
            fontSize = metrics.alertConditionToggleFontSize,
            fontWeight = FontWeight.Black
        )
        Text(condition.displayText, color = conditionColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HelperTextSection(bankName: String, check: BankAlertEditorCheck, currency: SupportedCurrency) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        check.quote?.let { quote ->
            Text(
                text = "현재 $bankName 환율: ${ExchangeRate.formatRate(quote, currency.code)}",
                modifier = Modifier.testTag(AlertAddSheetTags.CURRENT_RATE),
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 14.sp
            )
        }
        check.range?.let { range ->
            val suffix = if (currency.isJPY) "원 (100엔)" else "원"
            Text(
                text = "설정 가능: ${formatRangeValue(range.start)} ~ ${formatRangeValue(range.endInclusive)}$suffix",
                modifier = Modifier.testTag(AlertAddSheetTags.RANGE),
                color = if (check.outOfRange) StatusConnecting else SecondaryText,
                fontSize = 12.sp,
                lineHeight = 14.sp
            )
        }
        if (check.outOfRange) {
            Text(
                "현재 환율의 ±50% 범위를 벗어났습니다",
                modifier = Modifier.testTag(AlertAddSheetTags.OUT_OF_RANGE),
                color = StatusError, fontSize = 12.sp, lineHeight = 14.sp
            )
        }
        if (check.duplicate) {
            Text(
                "동일한 알림이 이미 존재합니다",
                modifier = Modifier.testTag(AlertAddSheetTags.DUPLICATE),
                color = StatusError, fontSize = 12.sp, lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun RepeatSection(
    mode: BankAlertEditorMode,
    texts: BankAlertRepeatTexts,
    choices: List<RepeatInterval?>,
    enabled: Boolean,
    onRepeatSelected: (RepeatIntervalChange) -> Unit
) {
    var expanded by remember(mode) { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardBackground)
                .testTag(AlertAddSheetTags.REPEAT)
                .clickable(enabled = enabled) { expanded = true }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("반복 발송", color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    texts.description,
                    modifier = Modifier.testTag(AlertAddSheetTags.REPEAT_DESCRIPTION),
                    color = SecondaryText, fontSize = 12.sp, lineHeight = 15.sp
                )
            }
            Text(
                texts.label,
                modifier = Modifier.testTag(AlertAddSheetTags.REPEAT_LABEL)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Primary.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                color = Primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEachIndexed { index, interval ->
                val choice = if (interval == null) RepeatIntervalChange.Once else RepeatIntervalChange.Every(interval)
                DropdownMenuItem(
                    text = { Text(BankAlertEditor.repeatTexts(mode, choice).label) },
                    onClick = {
                        expanded = false
                        if (enabled) onRepeatSelected(choice)
                    },
                    enabled = enabled,
                    modifier = Modifier.testTag(AlertAddSheetTags.repeatOption(index))
                )
            }
        }
    }
}

@Composable
private fun InfoBanner(texts: BankAlertRepeatTexts, remaining: Int?) {
    val dividerColor = SecondaryText.copy(alpha = 0.2f)
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .border(1.dp, SecondaryText.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        InfoItem(
            icon = if (texts.repeats) Icons.Default.Repeat else Icons.Default.Notifications,
            iconTint = Primary,
            text = texts.banner,
            textModifier = Modifier.testTag(AlertAddSheetTags.BANNER_REPEAT)
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = dividerColor)
        InfoItem(
            icon = Icons.AutoMirrored.Filled.TrendingUp,
            iconTint = Color.Cyan,
            text = "환율 변동으로 목표에 도달하면 알림을 보내드립니다"
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = dividerColor)
        InfoItem(
            icon = Icons.Default.Schedule,
            iconTint = Color(0xFFF39C12),
            text = "평일 아침 8:30 경 첫 고시 환율은 환전이 되지 않습니다"
        )
        if (remaining != null && remaining <= 3) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = dividerColor)
            val atLimit = remaining <= 0
            InfoItem(
                icon = Icons.Default.Numbers,
                iconTint = if (atLimit) StatusError else Color.Yellow,
                text = if (atLimit) "알림을 더 추가할 수 없습니다" else "알림 ${remaining}개 더 추가 가능",
                textColor = if (atLimit) StatusError else SecondaryText,
                textModifier = Modifier.testTag(AlertAddSheetTags.REMAINING),
                iconModifier = Modifier.testTag(AlertAddSheetTags.REMAINING_ICON),
                circle = true
            )
        }
    }
}

@Composable
private fun InfoItem(
    icon: ImageVector,
    iconTint: Color,
    text: String,
    textColor: Color = SecondaryText,
    textModifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    circle: Boolean = false
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = iconModifier.size(18.dp).then(
                if (circle) Modifier.border(1.dp, iconTint, CircleShape).padding(2.dp) else Modifier
            )
        )
        Spacer(Modifier.width(12.dp))
        Text(text, modifier = textModifier, color = textColor, fontSize = 14.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun SavingOverlay() {
    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .testTag(AlertAddSheetTags.SAVING),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.clip(RoundedCornerShape(16.dp))
                .background(CardBackground)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(Modifier.size(32.dp), color = PrimaryText, strokeWidth = 3.dp)
            Text("저장 중...", color = PrimaryText, fontSize = 14.sp)
        }
    }
}

private fun formatRangeValue(value: Double): String =
    NumberFormat.getNumberInstance(Locale.KOREA).apply {
        isGroupingUsed = false
        minimumFractionDigits = 0
        maximumFractionDigits = 0
    }.format(value)
