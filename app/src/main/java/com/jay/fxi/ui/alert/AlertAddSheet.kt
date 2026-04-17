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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
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
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.ExchangeRate
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
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertAddSheet(
    currency: SupportedCurrency,
    rates: List<ExchangeRate>,
    editSetting: AlertSetting? = null,
    initialBank: Bank? = null,
    isSaving: Boolean = false,
    hasDuplicate: (String, AlertCondition, Double, Int?) -> Boolean,
    onSave: (bank: String, condition: AlertCondition, threshold: Double, isEnabled: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isEditMode = editSetting != null

    var selectedBank by remember {
        mutableStateOf(editSetting?.bankType ?: initialBank ?: Bank.KB)
    }
    var condition by remember {
        mutableStateOf(editSetting?.condition ?: AlertCondition.BELOW)
    }
    var thresholdText by remember {
        mutableStateOf(editSetting?.threshold?.let { formatThreshold(it) } ?: "")
    }
    var isEnabled by remember {
        mutableStateOf(editSetting?.isEnabled ?: true)
    }

    // Current rate for selected bank
    val currentRate = remember(rates, selectedBank, currency) {
        rates.find { it.bank == selectedBank.code && it.currency == currency.code }?.rate
    }

    // Initialize threshold from current rate in add mode
    LaunchedEffect(selectedBank) {
        if (!isEditMode) {
            val rate = rates.find { it.bank == selectedBank.code && it.currency == currency.code }?.rate
            if (rate != null) {
                thresholdText = formatThreshold(rate)
            }
        }
    }

    // Validation
    val thresholdValue = thresholdText.replace(",", "").toDoubleOrNull()
    val validRange = currentRate?.let { rate ->
        val lower = roundToTens(rate * 0.5)
        val upper = roundToTens(rate * 1.5)
        lower to upper
    }
    val isInRange = thresholdValue != null && validRange != null &&
        thresholdValue >= validRange.first && thresholdValue <= validRange.second
    val isDuplicate = thresholdValue != null && hasDuplicate(
        selectedBank.code, condition, thresholdValue, editSetting?.id
    )
    val hasChanges = if (editSetting != null) {
        selectedBank.code != editSetting.bank ||
            condition != editSetting.condition ||
            (thresholdValue != null && abs(thresholdValue - editSetting.threshold) >= 0.005) ||
            isEnabled != editSetting.isEnabled
    } else true
    val canSave = thresholdValue != null && thresholdValue > 0 && isInRange && !isDuplicate && hasChanges && !isSaving

    // Threshold color feedback (iOS와 동일)
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
        // iOS 스타일 drag indicator (상단에 더 가깝게)
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
        // iOS .large detent와 유사하게 화면 95% 채움
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
                            val tv = thresholdValue
                            if (canSave && tv != null) {
                                onSave(selectedBank.code, condition, tv, isEnabled)
                            }
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

                // Bank picker section (iOS 스타일)
                BankPickerSection(
                    selectedBank = selectedBank,
                    currency = currency,
                    onBankSelected = { bank ->
                        selectedBank = bank
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Threshold input section
                ThresholdInputSection(
                    thresholdText = thresholdText,
                    onThresholdChange = { input ->
                        val sanitized = sanitizeThresholdInput(input)
                        if (sanitized != null) thresholdText = sanitized
                    },
                    thresholdColor = thresholdColor,
                    condition = condition,
                    onConditionToggle = {
                        condition = if (condition == AlertCondition.ABOVE) AlertCondition.BELOW
                        else AlertCondition.ABOVE
                    },
                    currency = currency
                )

                // Helper text
                HelperTextSection(
                    selectedBank = selectedBank,
                    currentRate = currentRate,
                    validRange = validRange,
                    thresholdValue = thresholdValue,
                    isInRange = isInRange,
                    isDuplicate = isDuplicate,
                    currency = currency
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Enable toggle (iOS 스타일)
                EnableToggleSection(
                    isEnabled = isEnabled,
                    onEnabledChange = { isEnabled = it },
                    isEditMode = isEditMode,
                    isTriggered = editSetting?.triggered == true
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Info banner (iOS 스타일 - dividers, colored icons)
                InfoBanner()
            }

            // Saving overlay (iOS와 동일)
            if (isSaving) {
                SavingOverlay()
            }
        }
    }
}

// MARK: - Bank Picker Section (iOS 스타일)

@Composable
private fun BankPickerSection(
    selectedBank: Bank,
    currency: SupportedCurrency,
    onBankSelected: (Bank) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Header: 은행명 (왼쪽) + 통화 정보 (오른쪽)
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
                    text = currency.tabTitle,
                    color = PrimaryText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "(${currency.displayName})",
                    color = SecondaryText,
                    fontSize = 12.sp
                )
            }
        }

        // Segmented bank grid (5x2, iOS 스타일 with dividers)
        SegmentedBankGrid(
            selectedBank = selectedBank,
            onBankSelected = onBankSelected
        )
    }
}

@Composable
private fun SegmentedBankGrid(
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
        // iOS와 동일: 가로모드/태블릿(넓은 화면)에서는 1줄, 세로모드 폰에서는 2줄
        val useSingleRow = maxWidth >= 500.dp

        if (useSingleRow) {
            // 1줄 레이아웃 (10개 모두 한 줄)
            val cellSize = maxWidth / 10
            Row(modifier = Modifier.fillMaxWidth()) {
                banks.forEachIndexed { index, bank ->
                    BankSegmentCell(
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
            // 2줄 레이아웃 (5개씩)
            val firstRow = banks.take(5)
            val secondRow = banks.drop(5)
            val cellSize = maxWidth / 5

            Column {
                // First row
                Row(modifier = Modifier.fillMaxWidth()) {
                    firstRow.forEachIndexed { index, bank ->
                        BankSegmentCell(
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

                // Second row
                Row(modifier = Modifier.fillMaxWidth()) {
                    secondRow.forEachIndexed { index, bank ->
                        BankSegmentCell(
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
private fun BankSegmentCell(
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
    // 아이콘 크기: 셀의 68% (iOS와 동일)
    val iconSize = cellSize * 0.68f

    Box(
        modifier = Modifier
            .size(cellSize)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // 선택 시 배경색
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bankColor.copy(alpha = 0.15f))
            )
            // 선택 시 테두리
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .border(2.5.dp, bankColor, RoundedCornerShape(6.dp))
            )
        }

        // Bank icon (고해상도 사용 - 알림 추가/수정용)
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
private fun ThresholdInputSection(
    thresholdText: String,
    onThresholdChange: (String) -> Unit,
    thresholdColor: Color,
    condition: AlertCondition,
    onConditionToggle: () -> Unit,
    currency: SupportedCurrency
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
            // 환율 입력 필드 (iOS 스타일 - monospaced, 우측 정렬)
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

            // Condition toggle (iOS 스타일 - 테두리 있음)
            ConditionToggleButton(
                condition = condition,
                onClick = onConditionToggle
            )
        }
    }
}

@Composable
private fun ConditionToggleButton(
    condition: AlertCondition,
    onClick: () -> Unit
) {
    val metrics = LocalRateLayoutMetrics.current
    val conditionColor = when (condition) {
        AlertCondition.ABOVE -> PositiveColor
        AlertCondition.BELOW -> NegativeColor
    }
    val symbol = condition.symbol
    val text = when (condition) {
        AlertCondition.ABOVE -> "이상"
        AlertCondition.BELOW -> "이하"
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
        Text(
            text = symbol,
            color = conditionColor,
            fontSize = metrics.alertConditionToggleFontSize,
            fontWeight = FontWeight.Black
        )
        Text(
            text = text,
            color = conditionColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// MARK: - Helper Text Section

@Composable
private fun HelperTextSection(
    selectedBank: Bank,
    currentRate: Double?,
    validRange: Pair<Double, Double>?,
    thresholdValue: Double?,
    isInRange: Boolean,
    isDuplicate: Boolean,
    currency: SupportedCurrency
) {
    Column(
        modifier = Modifier.padding(top = 4.dp)
    ) {
        if (currentRate != null) {
            Text(
                text = "현재 ${selectedBank.displayName} 환율: ${ExchangeRate.formatRate(currentRate, currency.code)}",
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 14.sp
            )
        }
        if (validRange != null) {
            val rangeColor = if (thresholdValue != null && !isInRange) StatusConnecting else SecondaryText
            val rangeSuffix = if (currency.isJPY) "원 (100엔)" else "원"
            Text(
                text = "설정 가능: ${formatRangeValue(validRange.first)} ~ ${formatRangeValue(validRange.second)}$rangeSuffix",
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
private fun EnableToggleSection(
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
            // 수정 모드에서 triggered 상태일 때 안내 (iOS와 동일)
            if (isEditMode && isTriggered && !isEnabled) {
                Text(
                    text = "알림이 발송되어 비활성화됨",
                    color = StatusConnecting,
                    fontSize = 12.sp,
                    lineHeight = 15.sp
                )
            }
        }
        // 흰색 thumb + 일관된 크기 토글
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

// MARK: - Info Banner (iOS 스타일)

@Composable
private fun InfoBanner() {
    val dividerColor = SecondaryText.copy(alpha = 0.2f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .border(1.dp, SecondaryText.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        // 첫 번째 안내 (Primary 색상 아이콘)
        InfoItem(
            icon = Icons.Default.Notifications,
            iconTint = Primary,
            text = "알림은 1회 발송 후 자동으로 꺼집니다"
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = dividerColor
        )

        // 두 번째 안내 (Cyan 색상 아이콘)
        InfoItem(
            icon = Icons.AutoMirrored.Filled.TrendingUp,
            iconTint = Color.Cyan,
            text = "환율 변동으로 목표에 도달하면 알림을 보내드립니다"
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = dividerColor
        )

        // 세 번째 안내 (Orange 색상 아이콘)
        InfoItem(
            icon = Icons.Default.Schedule,
            iconTint = Color(0xFFF39C12),  // Orange
            text = "평일 아침 8:30 경 첫 고시 환율은 환전이 되지 않습니다"
        )
    }
}

@Composable
private fun InfoItem(
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

// MARK: - Saving Overlay (iOS와 동일)

@Composable
private fun SavingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(CardBackground)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = PrimaryText,
                strokeWidth = 3.dp
            )
            Text(
                text = "저장 중...",
                color = PrimaryText,
                fontSize = 14.sp
            )
        }
    }
}

// MARK: - Helpers

private fun sanitizeThresholdInput(input: String): String? {
    val cleaned = input.filter { it.isDigit() || it == '.' || it == ',' }
    val withoutCommas = cleaned.replace(",", "")

    val dotCount = withoutCommas.count { it == '.' }
    if (dotCount > 1) return null

    val parts = withoutCommas.split(".")
    if (parts.size == 2 && parts[1].length > 2) return null

    // 붙여넣기 등으로 들어온 콤마는 허용하되 표기에서는 제거
    return withoutCommas
}

private fun roundToTens(value: Double): Double {
    return (value / 10.0).roundToInt() * 10.0
}

private fun formatThreshold(value: Double): String {
    return NumberFormat.getNumberInstance(Locale.KOREA).apply {
        // iOS와 표기 통일: 천단위 구분자(,) 제거
        isGroupingUsed = false
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(value)
}

/** iOS와 동일: 설정 가능 범위는 소수점 없이 표시 */
private fun formatRangeValue(value: Double): String {
    return NumberFormat.getNumberInstance(Locale.KOREA).apply {
        // iOS와 표기 통일: 천단위 구분자(,) 제거
        isGroupingUsed = false
        minimumFractionDigits = 0
        maximumFractionDigits = 0
    }.format(value)
}
