package com.jay.fxi.ui.subscription

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Icon
import com.jay.fxi.BuildConfig
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.NegativeColor
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PackageType
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.interfaces.PurchaseCallback
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.interfaces.ReceiveOfferingsCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.revenuecat.purchases.models.Period
import androidx.compose.ui.platform.LocalUriHandler

@Composable
fun PaywallScreen(
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var isLoading by remember { mutableStateOf(true) }
    var isPurchasing by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var packages by remember { mutableStateOf<List<Package>>(emptyList()) }
    var selectedPackage by remember { mutableStateOf<Package?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableStateOf(0) }

    LaunchedEffect(reloadToken) {
        isLoading = true
        errorMessage = null
        infoMessage = null

        if (BuildConfig.REVENUECAT_API_KEY.isBlank()) {
            errorMessage = "구독 기능이 비활성화되어 있습니다."
            isLoading = false
            return@LaunchedEffect
        }

        try {
            val offerings = Purchases.sharedInstance.getOfferingsSuspend()
            val available = offerings.current?.availablePackages.orEmpty()
            packages = available
            selectedPackage = available.firstOrNull()
        } catch (e: Exception) {
            errorMessage = e.message ?: "구독 정보를 불러올 수 없습니다."
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Premium",
                color = PrimaryText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "닫기",
                color = SecondaryText,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onClose() }
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = Primary
            )
            Text(
                text = "실시간 알림과 전체 기능을 모두 사용하세요.",
                color = SecondaryText,
                fontSize = 14.sp
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FeatureRow(text = "실시간 환율 알림 (최대 30개)")
            FeatureRow(text = "24시간 그래프 + 은행별 비교 전체 해제")
            FeatureRow(text = "오프라인 캐시 + 빠른 동기화")
        }

        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 3.dp)
                }
            }
            errorMessage != null -> {
                Text(
                    text = errorMessage ?: "",
                    color = SecondaryText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        reloadToken += 1
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = Background
                    )
                ) {
                    Text(text = "다시 시도")
                }
            }
            packages.isEmpty() -> {
                Text(
                    text = "현재 이용 가능한 구독 상품이 없습니다.",
                    color = SecondaryText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    packages.forEach { pkg ->
                        PackageOption(
                            pkg = pkg,
                            isSelected = selectedPackage == pkg,
                            onSelect = { selectedPackage = pkg }
                        )
                    }
                }
            }
        }

        if (!infoMessage.isNullOrBlank()) {
            Text(
                text = infoMessage ?: "",
                color = SecondaryText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (packages.isNotEmpty()) {
            Button(
                onClick = {
                    val targetPackage = selectedPackage ?: return@Button
                    if (activity == null) return@Button

                    isPurchasing = true
                    infoMessage = null
                    errorMessage = null

                    val purchaseParams = PurchaseParams.Builder(activity, targetPackage).build()
                    Purchases.sharedInstance.purchase(
                        purchaseParams,
                        object : PurchaseCallback {
                            override fun onCompleted(
                                storeTransaction: com.revenuecat.purchases.models.StoreTransaction,
                                customerInfo: com.revenuecat.purchases.CustomerInfo
                            ) {
                                isPurchasing = false
                                infoMessage = "구독이 활성화되었습니다."
                            }

                            override fun onError(error: PurchasesError, userCancelled: Boolean) {
                                isPurchasing = false
                                if (!userCancelled) {
                                    errorMessage = error.message
                                }
                            }
                        }
                    )
                },
                enabled = !isPurchasing && selectedPackage != null && activity != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = Background
                )
            ) {
                if (isPurchasing) {
                    CircularProgressIndicator(
                        color = Background,
                        strokeWidth = 2.dp,
                        modifier = Modifier.height(16.dp)
                    )
                } else {
                    Text(
                        text = purchaseButtonText(selectedPackage),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 무료 체험 → 유료 전환 안내 (연간 + 체험 가능할 때만)
            trialConversionText(selectedPackage)?.let { text ->
                Text(
                    text = text,
                    color = SecondaryText,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = {
                    if (isRestoring) return@Button
                    isRestoring = true
                    infoMessage = null
                    errorMessage = null
                    Purchases.sharedInstance.restorePurchases(
                        object : ReceiveCustomerInfoCallback {
                            override fun onReceived(customerInfo: com.revenuecat.purchases.CustomerInfo) {
                                isRestoring = false
                                infoMessage = "복원이 완료되었습니다."
                            }

                            override fun onError(error: PurchasesError) {
                                isRestoring = false
                                errorMessage = error.message
                            }
                        }
                    )
                },
                enabled = !isRestoring,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CardBackground,
                    contentColor = PrimaryText
                )
            ) {
                Text(text = if (isRestoring) "복원 중..." else "구매 복원")
            }

            TermsSection()
        }
    }
}

@Composable
private fun FeatureRow(
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = "•", color = Primary, fontSize = 14.sp)
        Text(text = text, color = SecondaryText, fontSize = 14.sp)
    }
}

@Composable
private fun PackageOption(
    pkg: Package,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val textColor = if (isSelected) PrimaryText else SecondaryText

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground)
            .padding(12.dp)
            .clickable { onSelect() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onSelect
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = formatPackageTitle(pkg),
                color = textColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = pkg.product.price.formatted,
                color = SecondaryText,
                fontSize = 13.sp
            )
            getTrialBadgeText(pkg)?.let { badge ->
                Text(
                    text = badge,
                    color = NegativeColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Text(
            text = formatPackagePeriod(pkg),
            color = textColor,
            fontSize = 12.sp
        )
    }
}

private fun formatPackageTitle(pkg: Package): String {
    return when (pkg.packageType) {
        PackageType.ANNUAL -> "연간"
        PackageType.MONTHLY -> "월간"
        PackageType.WEEKLY -> "주간"
        PackageType.SIX_MONTH -> "6개월"
        PackageType.THREE_MONTH -> "3개월"
        PackageType.TWO_MONTH -> "2개월"
        PackageType.LIFETIME -> "평생 이용권"
        else -> pkg.product.title.ifBlank { pkg.product.name }
    }
}

private fun formatPackagePeriod(pkg: Package): String {
    return when (pkg.packageType) {
        PackageType.ANNUAL -> "연간"
        PackageType.MONTHLY -> "월간"
        PackageType.WEEKLY -> "주간"
        PackageType.SIX_MONTH -> "6개월"
        PackageType.THREE_MONTH -> "3개월"
        PackageType.TWO_MONTH -> "2개월"
        PackageType.LIFETIME -> "평생"
        else -> formatPeriod(pkg.product.period)
    }
}

private fun formatPeriod(period: Period?): String {
    if (period == null) return "기간"
    val unitLabel = when (period.unit) {
        Period.Unit.YEAR -> "년"
        Period.Unit.MONTH -> "개월"
        Period.Unit.WEEK -> "주"
        Period.Unit.DAY -> "일"
        Period.Unit.UNKNOWN -> ""
    }
    return if (period.value == 1) {
        if (unitLabel.isBlank()) "기간" else unitLabel
    } else {
        "${period.value}$unitLabel"
    }
}

private fun purchaseButtonText(selectedPackage: Package?): String {
    if (selectedPackage == null) return "구독 옵션을 선택하세요"

    val trialPeriod = selectedPackage.product.defaultOption?.freePhase?.billingPeriod
    if (selectedPackage.packageType == PackageType.ANNUAL && trialPeriod != null) {
        val duration = formatPeriod(trialPeriod)
        return "${duration} 무료 후 구독 시작"
    }

    return "${selectedPackage.product.price.formatted} · ${formatPackagePeriod(selectedPackage)}"
}

private fun trialConversionText(selectedPackage: Package?): String? {
    if (selectedPackage == null) return null
    if (selectedPackage.packageType != PackageType.ANNUAL) return null
    if (selectedPackage.product.defaultOption?.freePhase?.billingPeriod == null) return null

    return "체험 종료 후 ${selectedPackage.product.price.formatted}/년 자동 결제"
}

private fun getTrialBadgeText(pkg: Package): String? {
    val trialPeriod = pkg.product.defaultOption?.freePhase?.billingPeriod ?: return null
    return "${formatPeriod(trialPeriod)} 무료 체험"
}

@Composable
private fun TermsSection() {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "• 구독은 Google Play 설정에서 언제든 관리하거나 해지할 수 있습니다.\n" +
                "• 결제는 구매 확인 시 Google 계정으로 청구됩니다.\n" +
                "• 종료 24시간 전까지 해지하지 않으면 자동 갱신 및 청구됩니다.\n" +
                "• 무료 체험 중 구독 시 남은 체험 기간은 소멸됩니다.",
            color = SecondaryText,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "이용약관",
                color = Primary,
                fontSize = 12.sp,
                modifier = Modifier.clickable {
                    uriHandler.openUri("https://play.google.com/intl/ko_kr/about/play-terms/")
                }
            )
            Text(text = "·", color = SecondaryText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "개인정보처리방침",
                color = Primary,
                fontSize = 12.sp,
                modifier = Modifier.clickable {
                    uriHandler.openUri("https://fxfxi.blogspot.com/2026/01/blog-post.html")
                }
            )
        }
    }
}

private suspend fun Purchases.getOfferingsSuspend(): Offerings =
    suspendCancellableCoroutine { cont ->
        getOfferings(
            object : ReceiveOfferingsCallback {
                override fun onReceived(offerings: Offerings) {
                    cont.resume(offerings)
                }

                override fun onError(error: PurchasesError) {
                    cont.resumeWithException(PurchasesException(error))
                }
            }
        )
    }
