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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.ui.components.BankIcon
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

/**
 * The bank alert editor's fields, shared by the premium [AlertAddSheet] and the free preview sheet (S7 E5d, B′): the bank grid,
 * the threshold and its condition, the current quote with its range and warnings, the repeat choice and the banner. Every
 * displayed decision comes from [BankAlertEditor]; the input is the caller's, changed only through [onInput]. The fields save,
 * reach and start nothing of their own.
 */
@Composable
internal fun BankAlertEditorFields(
    mode: BankAlertEditorMode,
    currency: SupportedCurrency,
    quotes: Map<String, Double>,
    input: BankAlertEditorInput,
    check: BankAlertEditorCheck,
    enabled: Boolean,
    remaining: Int?,
    onInput: (BankAlertEditorInput) -> Unit,
    modifier: Modifier = Modifier
) {
    val thresholdColor = when (BankAlertEditor.tone(check)) {
        BankAlertThresholdTone.NONE, BankAlertThresholdTone.SAME -> PrimaryText
        BankAlertThresholdTone.ABOVE -> PositiveColor.copy(alpha = 0.85f)
        BankAlertThresholdTone.BELOW -> NegativeColor.copy(alpha = 0.85f)
    }
    val repeatTexts = BankAlertEditor.repeatTexts(mode, input.repeat)
    val bankName = input.bank?.displayName
        ?: (mode as? BankAlertEditorMode.Edit)?.setting?.bank.orEmpty()
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        BankPickerSection(
            bankName = bankName,
            selectedBank = input.bank,
            banks = BankAlertEditor.BANK_CHOICES,
            currency = currency,
            enabled = enabled,
            onBankSelected = { bank ->
                if (enabled) onInput(BankAlertEditor.onBankPicked(input, bank, mode, quotes))
            }
        )
        Spacer(Modifier.height(16.dp))
        ThresholdInputSection(
            thresholdText = input.thresholdText,
            onThresholdChange = { raw ->
                if (enabled) onInput(input.copy(thresholdText = BankAlertEditor.sanitize(raw)))
            },
            thresholdColor = thresholdColor,
            condition = input.condition,
            enabled = enabled,
            onConditionToggle = {
                if (enabled) onInput(input.copy(
                    condition = if (input.condition == AlertCondition.ABOVE) AlertCondition.BELOW
                    else AlertCondition.ABOVE
                ))
            }
        )
        HelperTextSection(bankName, check, currency)
        Spacer(Modifier.height(12.dp))
        RepeatSection(
            mode = mode,
            texts = repeatTexts,
            choices = BankAlertEditor.REPEAT_CHOICES,
            enabled = enabled,
            onRepeatSelected = { repeat ->
                if (enabled) onInput(input.copy(repeat = repeat))
            }
        )
        Spacer(Modifier.height(12.dp))
        InfoBanner(texts = repeatTexts, remaining = remaining)
    }
}

/** The bottom sheets' common drag handle. */
@Composable
internal fun BankAlertSheetDragHandle() {
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

private fun formatRangeValue(value: Double): String =
    NumberFormat.getNumberInstance(Locale.KOREA).apply {
        isGroupingUsed = false
        minimumFractionDigits = 0
        maximumFractionDigits = 0
    }.format(value)
