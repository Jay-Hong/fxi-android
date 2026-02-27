package com.jay.fxi.ui.subscription

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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.ui.components.BankIcon
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.InputBackground
import com.jay.fxi.ui.theme.NegativeColor
import com.jay.fxi.ui.theme.PositiveColor
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.theme.StatusConnecting
import com.jay.fxi.ui.theme.StatusError
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 체험용 알림 추가/수정 시트 (iOS SampleAlertAddSheet 동일)
 * AlertAddSheet와 동일한 UX이지만 서버 연동 없이 로컬 ViewModel 직접 호출
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleAlertAddSheet(
    viewModel: SamplePreviewViewModel,
    editSetting: SampleAlertSetting? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isEditMode = editSetting != null

    var selectedBank by remember {
        mutableStateOf(editSetting?.bank ?: Bank.KB)
    }
    var condition by remember {
        mutableStateOf(editSetting?.condition ?: AlertCondition.BELOW)
    }
    var thresholdText by remember {
        mutableStateOf(editSetting?.threshold?.let { formatSampleThreshold(it) } ?: "")
    }
    var isEnabled by remember {
        mutableStateOf(editSetting?.isEnabled ?: true)
    }

    val currentRate = remember(viewModel.rates, selectedBank) {
        viewModel.rates.find { it.bank == selectedBank.code }?.rate
    }

    // 추가 모드: 은행 선택 시 현재 환율로 threshold 업데이트
    LaunchedEffect(selectedBank) {
        if (!isEditMode) {
            val rate = viewModel.rates.find { it.bank == selectedBank.code }?.rate
            if (rate != null) {
                thresholdText = formatSampleThreshold(rate)
            }
        }
    }

    // Validation
    val thresholdValue = thresholdText.replace(",", "").toDoubleOrNull()
    val validRange = currentRate?.let { rate ->
        val lower = sampleRoundToTens(rate * 0.5)
        val upper = sampleRoundToTens(rate * 1.5)
        lower to upper
    }
    val isInRange = thresholdValue != null && validRange != null &&
        thresholdValue >= validRange.first && thresholdValue <= validRange.second
    val isDuplicate = thresholdValue != null && viewModel.alertSettings.any { setting ->
        if (editSetting != null && setting.id == editSetting.id) return@any false
        setting.bank == selectedBank &&
            setting.condition == condition &&
            abs(setting.threshold - thresholdValue) < 0.005
    }
    val hasChanges = if (editSetting != null) {
        selectedBank != editSetting.bank ||
            condition != editSetting.condition ||
            (thresholdValue != null && abs(thresholdValue - editSetting.threshold) >= 0.005) ||
            isEnabled != editSetting.isEnabled
    } else true
    val canSave = thresholdValue != null && thresholdValue > 0 && isInRange && !isDuplicate && hasChanges

    val thresholdColor = when {
        thresholdValue == null || currentRate == null -> PrimaryText
        abs(thresholdValue - currentRate) < 0.005 -> PrimaryText
        thresholdValue > currentRate -> PositiveColor.copy(alpha = 0.85f)
        else -> NegativeColor.copy(alpha = 0.85f)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Background,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(SecondaryText.copy(alpha = 0.4f))
                )
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                // Title bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("취소", color = SecondaryText)
                    }
                    Text(
                        text = if (isEditMode) "알림 수정" else "알림 추가",
                        color = PrimaryText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = {
                            val tv = thresholdValue ?: return@TextButton
                            if (!canSave) return@TextButton
                            if (editSetting != null) {
                                viewModel.updateAlert(
                                    id = editSetting.id,
                                    bank = if (selectedBank != editSetting.bank) selectedBank else null,
                                    condition = if (condition != editSetting.condition) condition else null,
                                    threshold = if (abs(tv - editSetting.threshold) >= 0.005) tv else null,
                                    isEnabled = if (isEnabled != editSetting.isEnabled) isEnabled else null
                                )
                            } else {
                                viewModel.addAlert(
                                    bank = selectedBank,
                                    condition = condition,
                                    threshold = tv,
                                    isEnabled = isEnabled
                                )
                            }
                            onDismiss()
                        },
                        enabled = canSave
                    ) {
                        Text(
                            "저장",
                            color = if (canSave) Primary else SecondaryText.copy(alpha = 0.5f),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 체험용 안내 배너
                SampleModeBanner()

                Spacer(modifier = Modifier.height(24.dp))

                // Bank picker section
                SampleBankPickerSection(
                    selectedBank = selectedBank,
                    onBankSelected = { selectedBank = it }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Threshold input + condition toggle
                SampleThresholdInputSection(
                    thresholdText = thresholdText,
                    onThresholdChange = { input ->
                        val sanitized = sanitizeSampleThresholdInput(input)
                        if (sanitized != null) thresholdText = sanitized
                    },
                    thresholdColor = thresholdColor,
                    condition = condition,
                    onConditionToggle = {
                        condition = if (condition == AlertCondition.ABOVE) AlertCondition.BELOW
                        else AlertCondition.ABOVE
                    }
                )

                // Helper text
                SampleHelperTextSection(
                    selectedBank = selectedBank,
                    currentRate = currentRate,
                    validRange = validRange,
                    thresholdValue = thresholdValue,
                    isInRange = isInRange,
                    isDuplicate = isDuplicate
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Enable toggle
                SampleEnableToggleSection(
                    isEnabled = isEnabled,
                    onEnabledChange = { isEnabled = it },
                    isEditMode = isEditMode,
                    isTriggered = editSetting?.triggered == true
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Info banner (체험용 - 3번째 항목이 다름)
                SampleInfoBanner()
            }
        }
    }
}

// MARK: - Sample Mode Banner

@Composable
private fun SampleModeBanner() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFFA500).copy(alpha = 0.1f))
            .padding(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = Color(0xFFFFA500),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = "예시 알림 모드",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = PrimaryText
            )
            Text(
                text = "알림은 샘플 환율 변동으로 목표에 도달할 때 표시됩니다",
                fontSize = 12.sp,
                lineHeight = 15.sp,
                color = SecondaryText
            )
        }
    }
}

// MARK: - Bank Picker Section

@Composable
private fun SampleBankPickerSection(
    selectedBank: Bank,
    onBankSelected: (Bank) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selectedBank.displayName,
                color = PrimaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "달러",
                    color = PrimaryText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "(USD/KRW)",
                    color = SecondaryText,
                    fontSize = 12.sp
                )
            }
        }

        SampleSegmentedBankGrid(
            selectedBank = selectedBank,
            onBankSelected = onBankSelected
        )
    }
}

@Composable
private fun SampleSegmentedBankGrid(
    selectedBank: Bank,
    onBankSelected: (Bank) -> Unit
) {
    val banks = Bank.entries.toList()
    val dividerColor = Color.Gray.copy(alpha = 0.3f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .border(1.dp, dividerColor, RoundedCornerShape(12.dp))
    ) {
        val useSingleRow = maxWidth >= 500.dp

        if (useSingleRow) {
            val cellSize = maxWidth / 10
            Row(modifier = Modifier.fillMaxWidth()) {
                banks.forEachIndexed { index, bank ->
                    SampleBankSegmentCell(
                        bank = bank,
                        isSelected = bank == selectedBank,
                        cellSize = cellSize,
                        onClick = { onBankSelected(bank) }
                    )
                    if (index < banks.size - 1) {
                        VerticalDivider(
                            modifier = Modifier.height(cellSize),
                            color = dividerColor
                        )
                    }
                }
            }
        } else {
            val firstRow = banks.take(5)
            val secondRow = banks.drop(5)
            val cellSize = maxWidth / 5

            Column {
                Row(modifier = Modifier.fillMaxWidth()) {
                    firstRow.forEachIndexed { index, bank ->
                        SampleBankSegmentCell(
                            bank = bank,
                            isSelected = bank == selectedBank,
                            cellSize = cellSize,
                            onClick = { onBankSelected(bank) }
                        )
                        if (index < firstRow.size - 1) {
                            VerticalDivider(
                                modifier = Modifier.height(cellSize),
                                color = dividerColor
                            )
                        }
                    }
                }

                HorizontalDivider(color = dividerColor)

                Row(modifier = Modifier.fillMaxWidth()) {
                    secondRow.forEachIndexed { index, bank ->
                        SampleBankSegmentCell(
                            bank = bank,
                            isSelected = bank == selectedBank,
                            cellSize = cellSize,
                            onClick = { onBankSelected(bank) }
                        )
                        if (index < secondRow.size - 1) {
                            VerticalDivider(
                                modifier = Modifier.height(cellSize),
                                color = dividerColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SampleBankSegmentCell(
    bank: Bank,
    isSelected: Boolean,
    cellSize: Dp,
    onClick: () -> Unit
) {
    val bankColor = Color(bank.colorHex)
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1f,
        label = "scale"
    )
    val iconSize = cellSize * 0.68f

    Box(
        modifier = Modifier
            .size(cellSize)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bankColor.copy(alpha = 0.15f))
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .border(2.5.dp, bankColor, RoundedCornerShape(6.dp))
            )
        }

        BankIcon(
            bank = bank,
            isSelected = isSelected,
            useHighRes = true,
            modifier = Modifier
                .size(iconSize)
                .scale(scale)
        )
    }
}

// MARK: - Threshold Input Section

@Composable
private fun SampleThresholdInputSection(
    thresholdText: String,
    onThresholdChange: (String) -> Unit,
    thresholdColor: Color,
    condition: AlertCondition,
    onConditionToggle: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "알림 받을 환율",
            color = SecondaryText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(InputBackground)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = thresholdText,
                    onValueChange = onThresholdChange,
                    modifier = Modifier.weight(1f),
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

            SampleConditionToggleButton(
                condition = condition,
                onClick = onConditionToggle
            )
        }
    }
}

@Composable
private fun SampleConditionToggleButton(
    condition: AlertCondition,
    onClick: () -> Unit
) {
    val conditionColor = when (condition) {
        AlertCondition.ABOVE -> PositiveColor
        AlertCondition.BELOW -> NegativeColor
    }
    val symbol = when (condition) {
        AlertCondition.ABOVE -> "△"
        AlertCondition.BELOW -> "▽"
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(conditionColor.copy(alpha = 0.15f))
            .border(2.dp, conditionColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = symbol, color = conditionColor, fontSize = 20.sp)
        Text(
            text = condition.displayText,
            color = conditionColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// MARK: - Helper Text Section

@Composable
private fun SampleHelperTextSection(
    selectedBank: Bank,
    currentRate: Double?,
    validRange: Pair<Double, Double>?,
    thresholdValue: Double?,
    isInRange: Boolean,
    isDuplicate: Boolean
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        if (currentRate != null) {
            Text(
                text = "현재 ${selectedBank.displayName} 환율: ${formatSampleThreshold(currentRate)}원",
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 14.sp
            )
        }
        if (validRange != null) {
            val rangeColor = if (thresholdValue != null && !isInRange) StatusConnecting else SecondaryText
            Text(
                text = "설정 가능: ${formatSampleRangeValue(validRange.first)} ~ ${formatSampleRangeValue(validRange.second)}원",
                color = rangeColor,
                fontSize = 12.sp,
                lineHeight = 14.sp
            )
        }
        if (thresholdValue != null && !isInRange && validRange != null) {
            Text(
                text = "현재 환율의 ±50% 범위를 벗어났습니다",
                color = StatusError,
                fontSize = 12.sp,
                lineHeight = 14.sp
            )
        }
        if (isDuplicate) {
            Text(
                text = "동일한 알림이 이미 존재합니다",
                color = StatusError,
                fontSize = 12.sp,
                lineHeight = 14.sp
            )
        }
    }
}

// MARK: - Enable Toggle Section

@Composable
private fun SampleEnableToggleSection(
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    isEditMode: Boolean,
    isTriggered: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "알림 활성화",
                color = PrimaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            if (isEditMode && isTriggered && !isEnabled) {
                Text(
                    text = "알림이 발송되어 비활성화됨",
                    color = StatusConnecting,
                    fontSize = 12.sp,
                    lineHeight = 15.sp
                )
            }
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = onEnabledChange,
            thumbContent = {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                uncheckedThumbColor = Color.White,
                checkedTrackColor = Primary,
                uncheckedTrackColor = SecondaryText.copy(alpha = 0.3f),
                checkedBorderColor = Color.Transparent,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

// MARK: - Info Banner (체험용)

@Composable
private fun SampleInfoBanner() {
    val dividerColor = SecondaryText.copy(alpha = 0.2f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .border(1.dp, SecondaryText.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        SampleInfoItem(
            icon = Icons.Default.Notifications,
            iconTint = Primary,
            text = "알림은 1회 발송 후 자동으로 꺼집니다"
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = dividerColor
        )

        SampleInfoItem(
            icon = Icons.AutoMirrored.Filled.TrendingUp,
            iconTint = Color.Cyan,
            text = "환율 변동으로 목표에 도달하면 알림을 보내드립니다"
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = dividerColor
        )

        SampleInfoItem(
            icon = Icons.Default.AutoAwesome,
            iconTint = Color(0xFFFFA500),
            text = "조건이 만족되면 알림 예시를 보실 수 있습니다"
        )
    }
}

@Composable
private fun SampleInfoItem(
    icon: ImageVector,
    iconTint: Color,
    text: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            color = SecondaryText,
            fontSize = 14.sp,
            lineHeight = 18.sp
        )
    }
}

// MARK: - Helpers

private fun sanitizeSampleThresholdInput(input: String): String? {
    val cleaned = input.filter { it.isDigit() || it == '.' || it == ',' }
    val withoutCommas = cleaned.replace(",", "")

    val dotCount = withoutCommas.count { it == '.' }
    if (dotCount > 1) return null

    val parts = withoutCommas.split(".")
    if (parts.size == 2 && parts[1].length > 2) return null

    return withoutCommas
}

private fun sampleRoundToTens(value: Double): Double {
    return (value / 10.0).roundToInt() * 10.0
}

private fun formatSampleThreshold(value: Double): String {
    return NumberFormat.getNumberInstance(Locale.KOREA).apply {
        isGroupingUsed = false
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(value)
}

private fun formatSampleRangeValue(value: Double): String {
    return NumberFormat.getNumberInstance(Locale.KOREA).apply {
        isGroupingUsed = false
        minimumFractionDigits = 0
        maximumFractionDigits = 0
    }.format(value)
}
