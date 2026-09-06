package com.jay.fxi.ui.subscription

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jay.fxi.subscription.SubscriptionManager
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.InputBackground
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PackageType
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.interfaces.PurchaseCallback
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.models.Period
import android.content.Intent
import android.net.Uri
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.launch

/** iOS PaywallView.swift 파리티: Gold accent 색상 */
private val GoldColor = Color(0xFFF5A623)
private val GoldGradient = Brush.linearGradient(colors = listOf(Color(0xFFFFD700), Color(0xFFFFA500)))
private const val PRIVACY_POLICY_URL = "https://fxfxi.blogspot.com/2026/01/blog-post.html"

@Composable
fun PaywallScreen(
    subscriptionManager: SubscriptionManager,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val coroutineScope = rememberCoroutineScope()

    val offerings by subscriptionManager.offerings.collectAsStateWithLifecycle()
    val offeringsError by subscriptionManager.offeringsError.collectAsStateWithLifecycle()
    val isLoadingOfferings by subscriptionManager.isLoadingOfferings.collectAsStateWithLifecycle()
    val trialDurationText by subscriptionManager.trialDurationText.collectAsStateWithLifecycle()
    val isPremium by subscriptionManager.isPremium.collectAsStateWithLifecycle()

    var selectedPackage by remember { mutableStateOf<Package?>(null) }
    var isPurchasing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showError by remember { mutableStateOf(false) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }

    val annualPkg = subscriptionManager.annualPackage
    val monthlyPkg = subscriptionManager.monthlyPackage
    val hasAnyPackage = annualPkg != null || monthlyPkg != null

    // offerings 로드 (이미 로드됐으면 no-op)
    LaunchedEffect(Unit) {
        subscriptionManager.loadOfferings()
    }

    // offerings 로드 완료 시 기본 선택: 연간 우선
    LaunchedEffect(offerings) {
        if (selectedPackage == null) {
            selectedPackage = annualPkg ?: monthlyPkg
        }
    }

    // 구매 완료 → 프리미엄 전환 시 자동 닫기
    LaunchedEffect(isPremium) {
        if (isPremium) onClose()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 60.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // 헤더 (왕관 + 파티클 + "Premium" + 핵심 가치)
            PaywallHeader()

            Spacer(modifier = Modifier.height(24.dp))

            // 기능 목록
            FeaturesCard()

            Spacer(modifier = Modifier.height(24.dp))

            // 가격 섹션
            PricingSection(
                annualPkg = annualPkg,
                monthlyPkg = monthlyPkg,
                selectedPackage = selectedPackage,
                onSelect = { selectedPackage = it },
                trialDurationText = trialDurationText,
                isLoadingOfferings = isLoadingOfferings,
                offeringsError = offeringsError,
                hasOfferings = offerings != null,
                hasAnyPackage = hasAnyPackage,
                onRetry = {
                    coroutineScope.launch {
                        subscriptionManager.loadOfferings(force = true)
                    }
                },
                onClose = onClose
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 구매 버튼
            PurchaseButton(
                selectedPackage = selectedPackage,
                trialDurationText = trialDurationText,
                isPurchasing = isPurchasing,
                isLoadingOfferings = isLoadingOfferings,
                hasAnyPackage = hasAnyPackage,
                onPurchase = {
                    val pkg = selectedPackage ?: return@PurchaseButton
                    val act = activity ?: return@PurchaseButton
                    isPurchasing = true
                    errorMessage = null

                    Purchases.sharedInstance.purchase(
                        PurchaseParams.Builder(act, pkg).build(),
                        object : PurchaseCallback {
                            override fun onCompleted(
                                storeTransaction: com.revenuecat.purchases.models.StoreTransaction,
                                customerInfo: com.revenuecat.purchases.CustomerInfo
                            ) {
                                isPurchasing = false
                                val premium = customerInfo.entitlements["premium"]?.isActive == true
                                // Signal only. The server decides; this just opens the re-query
                                // window its caches may need. Non-suspend, so no scope is needed.
                                if (premium) subscriptionManager.onLocalPremiumSignal()
                                if (!premium) {
                                    errorMessage = "결제가 처리 중입니다. 잠시 후 앱을 다시 시작해주세요."
                                    showError = true
                                }
                                // premium true → RootScreen의 isPremium 변화로 자동 dismiss
                            }

                            override fun onError(error: PurchasesError, userCancelled: Boolean) {
                                isPurchasing = false
                                if (!userCancelled) {
                                    errorMessage = userFriendlyErrorMessage(error)
                                    showError = true
                                }
                            }
                        }
                    )
                }
            )

            // 무료 체험 → 유료 전환 안내
            if (selectedPackage?.packageType == PackageType.ANNUAL && trialDurationText != null) {
                Text(
                    text = "체험 종료 후 ${selectedPackage?.product?.price?.formatted ?: ""}/년 자동 결제",
                    color = SecondaryText,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 구매 복원 (텍스트 버튼, iOS처럼 subtle)
            RestoreButton(
                isPurchasing = isPurchasing,
                isLoadingOfferings = isLoadingOfferings,
                hasAnyPackage = hasAnyPackage,
                onRestore = {
                    isPurchasing = true
                    errorMessage = null

                    Purchases.sharedInstance.restorePurchases(
                        object : ReceiveCustomerInfoCallback {
                            override fun onReceived(customerInfo: com.revenuecat.purchases.CustomerInfo) {
                                isPurchasing = false
                                val premium = customerInfo.entitlements["premium"]?.isActive == true
                                // A completed restore is not the same as "found an active
                                // subscription" — only the active case is worth re-querying for.
                                if (premium) subscriptionManager.onLocalPremiumSignal()
                                if (!premium) {
                                    errorMessage = "복원할 구독이 없습니다"
                                    showError = true
                                }
                                // premium true → 자동 dismiss
                            }

                            override fun onError(error: PurchasesError) {
                                isPurchasing = false
                                errorMessage = userFriendlyErrorMessage(error)
                                showError = true
                            }
                        }
                    )
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 약관
            TermsSection(onPrivacyPolicy = { showPrivacyPolicy = true })
        }

        // 닫기 버튼 (우상단, iOS xmark.circle.fill 대응)
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "닫기",
                tint = SecondaryText,
                modifier = Modifier.size(28.dp)
            )
        }

        // 에러 다이얼로그
        if (showError && errorMessage != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showError = false },
                title = { Text("오류") },
                text = { Text(errorMessage ?: "") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showError = false }) {
                        Text("확인")
                    }
                }
            )
        }

        // 개인정보처리방침 (Paywall 내부 오버레이)
        if (showPrivacyPolicy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Background)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                PrivacyPolicyOverlay(onBack = { showPrivacyPolicy = false })
            }
        }
    }
}

// ── 헤더 ──

@Composable
private fun PaywallHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp),
        contentAlignment = Alignment.Center
    ) {
        // 통화 기호 파티클 배경
        CurrencyParticlesBackground()

        // 중앙 콘텐츠
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 왕관 아이콘 (WorkspacePremium = Material 대체, gold gradient)
            Icon(
                imageVector = Icons.Default.WorkspacePremium,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = GoldColor // Icon은 단색만 지원; gradient 근사치
            )

            Spacer(modifier = Modifier.height(4.dp))

            // "Premium" 라벨 (gold gradient)
            Text(
                text = "Premium",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(brush = GoldGradient)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 핵심 가치 제안
            Text(
                text = "환율 동향을 한눈에",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryText
            )
        }
    }
}

/** 통화 기호 파티클 배경 (iOS generateParticles 포팅) */
@Composable
private fun CurrencyParticlesBackground() {
    val symbols = listOf("$", "¥", "€", "₩")
    // iOS와 동일한 고정 위치 (비율 기반, 중앙 회피)
    data class Particle(
        val xRatio: Float, val yRatio: Float,
        val size: Float, val opacity: Float, val rotation: Float
    )

    val particles = listOf(
        Particle(0.06f, 0.10f, 17f, 0.25f, -15f),
        Particle(0.20f, 0.05f, 14f, 0.18f, 10f),
        Particle(0.12f, 0.28f, 15f, 0.22f, -8f),
        Particle(0.88f, 0.08f, 15f, 0.20f, 12f),
        Particle(0.80f, 0.25f, 14f, 0.18f, -5f),
        Particle(0.94f, 0.30f, 17f, 0.25f, 20f),
        Particle(0.08f, 0.75f, 15f, 0.20f, 8f),
        Particle(0.22f, 0.88f, 16f, 0.22f, -12f),
        Particle(0.04f, 0.58f, 13f, 0.15f, 5f),
        Particle(0.90f, 0.70f, 14f, 0.18f, -10f),
        Particle(0.78f, 0.85f, 15f, 0.22f, 15f),
        Particle(0.96f, 0.90f, 12f, 0.15f, -18f),
        Particle(0.02f, 0.45f, 14f, 0.18f, 6f),
        Particle(0.98f, 0.55f, 14f, 0.18f, -8f),
        Particle(0.38f, 0.02f, 12f, 0.14f, 22f),
        Particle(0.62f, 0.02f, 12f, 0.14f, -20f),
        Particle(0.32f, 0.96f, 12f, 0.14f, 10f),
        Particle(0.68f, 0.96f, 12f, 0.14f, -10f),
        Particle(0.28f, 0.32f, 13f, 0.16f, -12f),
        Particle(0.72f, 0.32f, 13f, 0.16f, 12f),
        Particle(0.25f, 0.68f, 12f, 0.14f, 8f),
        Particle(0.75f, 0.68f, 12f, 0.14f, -8f)
    )

    Box(modifier = Modifier.fillMaxSize()) {
        particles.forEachIndexed { index, p ->
            Text(
                text = symbols[index % symbols.size],
                fontSize = p.size.sp,
                fontWeight = FontWeight.Medium,
                color = GoldColor.copy(alpha = p.opacity),
                modifier = Modifier
                    .align { size, space, _ ->
                        val x = (space.width * p.xRatio).toInt() - size.width / 2
                        val y = (space.height * p.yRatio).toInt() - size.height / 2
                        androidx.compose.ui.unit.IntOffset(x, y)
                    }
                    .rotate(p.rotation)
            )
        }
    }
}

// ── 기능 목록 ──

@Composable
private fun FeaturesCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FeatureRow("은행별 목표 환율 알림")
        FeatureRow("달러, 엔화, 유로")
        FeatureRow("24시간 추이 그래프")
        FeatureRow("인베스팅 및 9개 은행 비교")
    }
}

@Composable
private fun FeatureRow(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = GoldColor,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = text,
            color = PrimaryText,
            fontSize = 15.sp
        )
    }
}

// ── 가격 섹션 ──

@Composable
private fun PricingSection(
    annualPkg: Package?,
    monthlyPkg: Package?,
    selectedPackage: Package?,
    onSelect: (Package) -> Unit,
    trialDurationText: String?,
    isLoadingOfferings: Boolean,
    offeringsError: String?,
    hasOfferings: Boolean,
    hasAnyPackage: Boolean,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            // 로딩 중
            !hasOfferings && isLoadingOfferings && offeringsError == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardBackground)
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = SecondaryText, strokeWidth = 2.dp)
                        Text(
                            text = "구독 옵션을 불러오는 중...",
                            color = SecondaryText,
                            fontSize = 14.sp
                        )
                    }
                }
            }
            // 에러
            !hasOfferings && offeringsError != null && !hasAnyPackage -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardBackground)
                        .padding(vertical = 30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "구독 옵션을 불러올 수 없습니다",
                            color = PrimaryText,
                            fontSize = 14.sp
                        )
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Primary,
                                contentColor = Color.White
                            )
                        ) {
                            Text("다시 시도")
                        }
                    }
                }
            }
            // offerings 있지만 패키지 없음
            hasOfferings && !isLoadingOfferings && !hasAnyPackage -> {
                EmptyOfferingsView(onClose = onClose)
            }
            // 정상: 연간/월간 카드
            else -> {
                val discountBadge = calculateDiscountBadge(annualPkg, monthlyPkg)

                if (annualPkg != null) {
                    PricingCard(
                        pkg = annualPkg,
                        title = "연간 구독",
                        subtitle = monthlyEquivalent(annualPkg),
                        badge = discountBadge,
                        highlight = trialDurationText?.let { "첫 구독자 $it 무료" },
                        isRecommended = true,
                        isSelected = selectedPackage?.identifier == annualPkg.identifier,
                        onClick = { onSelect(annualPkg) }
                    )
                }

                if (monthlyPkg != null) {
                    PricingCard(
                        pkg = monthlyPkg,
                        title = "월간 구독",
                        subtitle = null,
                        badge = null,
                        highlight = null,
                        isRecommended = false,
                        isSelected = selectedPackage?.identifier == monthlyPkg.identifier,
                        onClick = { onSelect(monthlyPkg) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PricingCard(
    pkg: Package,
    title: String,
    subtitle: String?,
    badge: String?,
    highlight: String?,
    isRecommended: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) GoldColor else Color.Transparent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        color = PrimaryText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (isRecommended) {
                        Text(
                            text = "추천",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(Primary)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = SecondaryText,
                        fontSize = 12.sp
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = pkg.product.price.formatted,
                    color = PrimaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (pkg.packageType == PackageType.ANNUAL) "/ 년" else "/ 월",
                    color = SecondaryText,
                    fontSize = 12.sp
                )
            }
        }

        if (badge != null || highlight != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = badge ?: "",
                    color = GoldColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (highlight != null) {
                    Text(
                        text = highlight,
                        color = Color(0xFF2ECC71),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyOfferingsView(onClose: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .padding(vertical = 30.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "현재 이용 가능한 구독 상품이 없습니다",
            color = PrimaryText,
            fontSize = 14.sp
        )
        Text(
            text = "문제가 지속되면 아래 이메일로 문의해주세요",
            color = SecondaryText,
            fontSize = 12.sp
        )
        Text(
            text = "fxi.app.kr@gmail.com",
            color = Primary,
            fontSize = 14.sp,
            modifier = Modifier.clickable {
                uriHandler.openUri("mailto:fxi.app.kr@gmail.com")
            }
        )
        Text(
            text = "닫기",
            color = SecondaryText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(InputBackground)
                .clickable { onClose() }
                .padding(horizontal = 20.dp, vertical = 8.dp)
        )
    }
}

// ── 구매 버튼 ──

@Composable
private fun PurchaseButton(
    selectedPackage: Package?,
    trialDurationText: String?,
    isPurchasing: Boolean,
    isLoadingOfferings: Boolean,
    hasAnyPackage: Boolean,
    onPurchase: () -> Unit
) {
    if (!hasAnyPackage) return

    Button(
        onClick = onPurchase,
        enabled = !isPurchasing && !isLoadingOfferings && selectedPackage != null,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = Primary,
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (isPurchasing) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text(
                text = purchaseButtonText(selectedPackage, trialDurationText),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

// ── 구매 복원 ──

@Composable
private fun RestoreButton(
    isPurchasing: Boolean,
    isLoadingOfferings: Boolean,
    hasAnyPackage: Boolean,
    onRestore: () -> Unit
) {
    if (!hasAnyPackage) return

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "구매 복원",
            color = if (isPurchasing) SecondaryText.copy(alpha = 0.5f) else SecondaryText,
            fontSize = 14.sp,
            modifier = Modifier.clickable(
                enabled = !isPurchasing && !isLoadingOfferings,
                onClick = onRestore
            )
        )
    }
}

// ── 약관 ──

@Composable
private fun TermsSection(onPrivacyPolicy: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "구독은 Google Play 설정에서 언제든 관리하거나 해지할 수 있습니다.\n" +
                "결제는 구매 확인 시 Google 계정으로 청구됩니다.\n" +
                "종료 24시간 전까지 해지하지 않으면 자동 갱신됩니다.\n" +
                "무료 체험 중 구독 시 남은 체험 기간은 소멸됩니다.",
            color = SecondaryText,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Start
        )

        Text(
            text = "개인정보처리방침",
            color = Primary,
            fontSize = 12.sp,
            modifier = Modifier.clickable { onPrivacyPolicy() }
        )
    }
}

// ── 개인정보처리방침 (In-app WebView 오버레이, Settings 동일 방식) ──

@Composable
fun PrivacyPolicyOverlay(onBack: () -> Unit) {
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }

    // 뒤로가기 버튼 처리
    androidx.activity.compose.BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        // 헤더
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "닫기",
                    tint = PrimaryText,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "개인정보처리방침",
                color = PrimaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.weight(1f))
            // 오른쪽 균형용 빈 공간
            Spacer(modifier = Modifier.size(48.dp))
        }

        // WebView
        Box(modifier = Modifier.fillMaxSize()) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { context ->
                    android.webkit.WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.setSupportMultipleWindows(false)
                        setBackgroundColor(android.graphics.Color.parseColor("#121212"))
                        setOnTouchListener { v, _ ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            false
                        }
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                isLoading = false
                            }
                            override fun onReceivedError(
                                view: android.webkit.WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    isLoading = false
                                    hasError = true
                                }
                            }
                            override fun shouldOverrideUrlLoading(
                                view: android.webkit.WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): Boolean {
                                // 개인정보처리방침: 기본적으로 WebView 내부 처리.
                                // 단, intent:// / market:// 등은 기기에서 외부 앱을 강제로 띄울 수 있어 차단한다.
                                val scheme = request?.url?.scheme
                                return scheme != null && scheme !in setOf("http", "https", "about", "data", "file")
                            }
                        }
                        loadUrl(PRIVACY_POLICY_URL)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Background),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 3.dp)
                }
            }

            if (hasError) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Background),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "페이지를 불러올 수 없습니다",
                        color = SecondaryText,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    androidx.compose.material3.TextButton(onClick = {
                        hasError = false
                        isLoading = true
                    }) {
                        Text("다시 시도", color = Primary)
                    }
                }
            }
        }
    }
}

// ── 헬퍼 함수 ──

private fun purchaseButtonText(selectedPackage: Package?, trialDurationText: String?): String {
    if (selectedPackage == null) return "구독 옵션을 선택하세요"

    if (selectedPackage.packageType == PackageType.ANNUAL && trialDurationText != null) {
        return "${trialDurationText} 무료 후 구독 시작"
    }

    return "구독하기"
}

/** 연간 vs 월간 할인율 계산 (iOS discountBadge 동일) */
private fun calculateDiscountBadge(annualPkg: Package?, monthlyPkg: Package?): String? {
    if (annualPkg == null || monthlyPkg == null) return null

    val monthlyPrice = monthlyPkg.product.price.amountMicros
    val annualPrice = annualPkg.product.price.amountMicros
    val fullYearPrice = monthlyPrice * 12

    if (fullYearPrice <= 0) return null

    val discount = ((fullYearPrice - annualPrice).toDouble() / fullYearPrice.toDouble() * 100).toInt()
    if (discount <= 0) return null

    return "${discount}% 할인"
}

/** 연간 패키지의 월 환산 가격 (iOS monthlyEquivalent 동일) */
private fun monthlyEquivalent(annualPkg: Package): String? {
    val annualMicros = annualPkg.product.price.amountMicros
    if (annualMicros <= 0) return null

    val monthlyMicros = annualMicros / 12
    val monthly = BigDecimal(monthlyMicros).divide(BigDecimal(1_000_000), 0, RoundingMode.HALF_UP)

    val formatter = NumberFormat.getCurrencyInstance(Locale.KOREA)
    return "월 ${formatter.format(monthly)}"
}

/** RevenueCat 에러 → 사용자 친화 메시지 (iOS userFriendlyErrorMessage 파리티) */
private fun userFriendlyErrorMessage(error: PurchasesError): String {
    return when (error.code.code) {
        0 -> "알 수 없는 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
        1 -> "구매가 취소되었습니다."
        2 -> "Google Play에 연결할 수 없습니다. 네트워크 연결을 확인해주세요."
        3 -> "이 기기에서는 결제가 허용되지 않습니다."
        4 -> "잘못된 구매 요청입니다."
        5 -> "현재 구매할 수 없는 상품입니다."
        6 -> "이미 구매한 상품입니다. 구매 복원을 시도해주세요."
        7 -> "이 구독은 다른 계정에서 사용 중입니다."
        10 -> "영수증을 찾을 수 없습니다. 앱을 재시작해주세요."
        11 -> "인증에 실패했습니다. 다시 로그인해주세요."
        14 -> "네트워크 연결을 확인해주세요."
        17 -> "이미 처리 중입니다. 잠시 기다려주세요."
        25 -> "결제 승인 대기 중입니다. 잠시 후 다시 확인해주세요."
        else -> "오류가 발생했습니다. 잠시 후 다시 시도해주세요."
    }
}
