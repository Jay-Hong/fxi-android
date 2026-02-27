package com.jay.fxi.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.jay.fxi.BuildConfig
import com.jay.fxi.R
import com.jay.fxi.domain.model.AuthProvider
import com.jay.fxi.domain.model.UserInfo
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.theme.StatusError

private const val PRIVACY_POLICY_URL = "https://fxfxi.blogspot.com/2026/01/blog-post.html"
private const val SUPPORT_EMAIL = "forexi.korea@gmail.com"

private enum class SettingsPage {
    Main, ProfileDetail, AppInfo, Notices, AppRating, PrivacyPolicy
}

/**
 * 설정 화면 (ModalBottomSheet, 전체화면) - iOS SettingsView 스타일
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    userInfo: UserInfo? = null,
    onSignOut: (() -> Unit)? = null,
    showRatingOption: Boolean = true,
    onDismiss: () -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDeleting by settingsViewModel.isDeleting.collectAsStateWithLifecycle()
    val deletionStep by settingsViewModel.deletionStep.collectAsStateWithLifecycle()
    val errorMessage by settingsViewModel.errorMessage.collectAsStateWithLifecycle()
    val serverDeletedButFirebaseFailed by settingsViewModel.serverDeletedButFirebaseFailed.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val activity = context as? Activity

    var deleteConfirmStep by remember { mutableIntStateOf(0) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf(SettingsPage.Main) }

    ModalBottomSheet(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        sheetState = sheetState,
        containerColor = Background,
        dragHandle = null
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    if (targetState == SettingsPage.Main) {
                        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                    } else {
                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                    }
                },
                label = "settings_nav"
            ) { page ->
                when (page) {
                    SettingsPage.Main -> SettingsMainContent(
                        userInfo = userInfo,
                        showRatingOption = showRatingOption,
                        onDismiss = { if (!isDeleting) onDismiss() },
                        onNavigateProfile = { currentPage = SettingsPage.ProfileDetail },
                        onNavigateAppInfo = { currentPage = SettingsPage.AppInfo },
                        onNavigateNotices = { currentPage = SettingsPage.Notices },
                        onNavigateAppRating = { currentPage = SettingsPage.AppRating },
                        onNavigatePrivacyPolicy = { currentPage = SettingsPage.PrivacyPolicy }
                    )
                    SettingsPage.ProfileDetail -> ProfileDetailScreen(
                        userInfo = userInfo,
                        onBack = { currentPage = SettingsPage.Main },
                        onSignOut = if (onSignOut != null) {
                            { if (!isDeleting) showLogoutConfirm = true }
                        } else {
                            null
                        },
                        isDeleting = isDeleting,
                        deletionStep = deletionStep,
                        errorMessage = errorMessage,
                        serverDeletedButFirebaseFailed = serverDeletedButFirebaseFailed,
                        onRetryFirebaseDelete = { activity?.let { settingsViewModel.retryFirebaseDelete(it) } },
                        onDeleteAccount = { deleteConfirmStep = 1 }
                    )
                    SettingsPage.AppInfo -> AppInfoScreen(onBack = { currentPage = SettingsPage.Main })
                    SettingsPage.Notices -> NoticesScreen(onBack = { currentPage = SettingsPage.Main })
                    SettingsPage.AppRating -> AppRatingScreen(
                        onBack = { currentPage = SettingsPage.Main },
                        onDismiss = { if (!isDeleting) onDismiss() }
                    )
                    SettingsPage.PrivacyPolicy -> PrivacyPolicyScreen(
                        onBack = { currentPage = SettingsPage.Main }
                    )
                }
            }
        }
    }

    // 로그아웃 확인 다이얼로그
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("로그아웃", color = PrimaryText) },
            text = { Text("정말 로그아웃하시겠습니까?", color = SecondaryText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        onSignOut?.invoke()
                    }
                ) {
                    Text("로그아웃", color = StatusError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("취소", color = SecondaryText)
                }
            },
            containerColor = CardBackground
        )
    }

    // 1차 확인 다이얼로그
    if (deleteConfirmStep == 1) {
        AlertDialog(
            onDismissRequest = { deleteConfirmStep = 0 },
            title = { Text("계정 삭제", color = PrimaryText) },
            text = { Text("계정을 삭제하면 모든 데이터가 영구적으로 삭제됩니다. 이 작업은 되돌릴 수 없습니다.", color = SecondaryText) },
            confirmButton = {
                TextButton(onClick = { deleteConfirmStep = 2 }) {
                    Text("계속", color = StatusError)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmStep = 0 }) {
                    Text("취소", color = SecondaryText)
                }
            },
            containerColor = CardBackground
        )
    }

    // 2차 최종 확인 다이얼로그
    if (deleteConfirmStep == 2) {
        AlertDialog(
            onDismissRequest = { deleteConfirmStep = 0 },
            title = { Text("정말 삭제하시겠습니까?", color = PrimaryText) },
            text = { Text("알림 설정, 구독 정보 등 모든 데이터가 삭제됩니다.", color = SecondaryText) },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirmStep = 0
                    if (activity != null) {
                        settingsViewModel.deleteAccount(activity)
                    }
                }) {
                    Text("삭제", color = StatusError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmStep = 0 }) {
                    Text("취소", color = SecondaryText)
                }
            },
            containerColor = CardBackground
        )
    }
}

@Composable
private fun SettingsMainContent(
    userInfo: UserInfo?,
    showRatingOption: Boolean,
    onDismiss: () -> Unit,
    onNavigateProfile: () -> Unit,
    onNavigateAppInfo: () -> Unit,
    onNavigateNotices: () -> Unit,
    onNavigateAppRating: () -> Unit,
    onNavigatePrivacyPolicy: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 헤더: "설정" + "닫기"
        SettingsHeader(onDismiss = onDismiss)

        // 프로필 카드 (로그인 시에만) - 탭하면 ProfileDetailView로 이동
        if (userInfo != null) {
            ProfileCard(userInfo = userInfo, onClick = onNavigateProfile)
        }

        // 일반 섹션
        SettingsSection {
            SettingsRow(
                icon = Icons.Filled.Info,
                iconColor = Primary,
                label = "앱 정보",
                trailing = { ChevronIcon() },
                onClick = onNavigateAppInfo
            )
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.Warning,
                iconColor = Color(0xFFF39C12),
                label = "유의사항",
                trailing = { ChevronIcon() },
                onClick = onNavigateNotices
            )
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.PrivacyTip,
                iconColor = Color(0xFF8E8E93),
                label = "개인정보처리방침",
                trailing = { ChevronIcon() },
                onClick = onNavigatePrivacyPolicy
            )
        }

        // 지원 섹션
        SettingsSection {
            SettingsRow(
                icon = Icons.Filled.Email,
                iconColor = Color(0xFF2196F3),
                label = "문의하기",
                trailing = { ChevronIcon() },
                onClick = {
                    val subject = Uri.encode("[환율알림] 문의")
                    val body = Uri.encode(
                        "\n\n───────────────────\n" +
                        "앱 버전: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "Android: ${Build.VERSION.RELEASE}\n" +
                        "기기: ${Build.MANUFACTURER} ${Build.MODEL}"
                    )
                    val uri = Uri.parse("mailto:$SUPPORT_EMAIL?subject=$subject&body=$body")
                    context.startActivity(Intent(Intent.ACTION_SENDTO, uri))
                }
            )
            if (showRatingOption) {
                SectionDivider()
                SettingsRow(
                    icon = Icons.Filled.Star,
                    iconColor = Color(0xFFFFD700),
                    label = "앱 평가하기",
                    trailing = { ChevronIcon() },
                    onClick = onNavigateAppRating
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// MARK: - 프로필 상세 화면 (iOS ProfileDetailView)

@Composable
private fun ProfileDetailScreen(
    userInfo: UserInfo?,
    onBack: () -> Unit,
    onSignOut: (() -> Unit)?,
    isDeleting: Boolean,
    deletionStep: DeletionStep?,
    errorMessage: String?,
    serverDeletedButFirebaseFailed: Boolean,
    onRetryFirebaseDelete: () -> Unit,
    onDeleteAccount: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
        ) {
            // 헤더
            SubScreenHeader(title = "계정", onBack = onBack)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 프로필 상세 카드 (큰 이미지)
                if (userInfo != null) {
                    ProfileDetailCard(userInfo = userInfo)
                }

                Spacer(modifier = Modifier.weight(1f))

                // 계정 관리 섹션 (하단 배치)
                if (userInfo != null && onSignOut != null) {
                    SettingsSection {
                        SettingsRow(
                            icon = Icons.AutoMirrored.Filled.ExitToApp,
                            iconColor = SecondaryText,
                            label = "로그아웃",
                            labelColor = SecondaryText,
                            onClick = { if (!isDeleting) onSignOut() }
                        )
                        SectionDivider()
                        SettingsRow(
                            icon = Icons.Filled.PersonOff,
                            iconColor = SecondaryText,
                            label = "계정삭제",
                            labelColor = SecondaryText,
                            onClick = { if (!isDeleting) onDeleteAccount() }
                        )
                    }
                }

                // 에러 메시지
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = StatusError,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                    )
                    if (serverDeletedButFirebaseFailed) {
                        TextButton(
                            onClick = onRetryFirebaseDelete,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("다시 시도", color = Primary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }

        // 삭제 중 오버레이
        if (isDeleting) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Background.copy(alpha = 0.85f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 3.dp)
                    Text(
                        text = when (deletionStep) {
                            DeletionStep.REAUTH -> "재인증 중..."
                            DeletionStep.SERVER_DELETE -> "서버 데이터 삭제 중..."
                            DeletionStep.REVENUECAT_LOGOUT -> "구독 정리 중..."
                            DeletionStep.FIREBASE_DELETE -> "계정 삭제 중..."
                            DeletionStep.LOCAL_CLEANUP -> "로컬 데이터 정리 중..."
                            null -> "처리 중..."
                        },
                        color = SecondaryText,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileDetailCard(userInfo: UserInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(12.dp))
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 큰 프로필 이미지
        ProfileImage(userInfo = userInfo, size = 80)

        // 이름
        Text(
            text = userInfo.displayName ?: "사용자",
            color = PrimaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        // 이메일
        if (userInfo.email != null) {
            Text(
                text = userInfo.email,
                color = SecondaryText,
                fontSize = 14.sp
            )
        }

        // 프로바이더 라벨
        ProviderLabel(provider = userInfo.provider)
    }
}

// MARK: - 앱 정보 화면 (iOS AppInfoView)

@Composable
private fun AppInfoScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        // 헤더
        SubScreenHeader(title = "앱 정보", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(0.dp))

            // 앱 헤더 카드 (iOS: VStack(spacing: 12), padding(.vertical, 20))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(12.dp))
                    .padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 앱 아이콘 (실제 런처 아이콘)
                Image(
                    painter = painterResource(id = R.drawable.ic_splash_icon),
                    contentDescription = "앱 아이콘",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(18.dp))
                )

                Text(
                    text = "환율알림",
                    color = PrimaryText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "버전 ${BuildConfig.VERSION_NAME}",
                    color = SecondaryText,
                    fontSize = 14.sp
                )
            }

            // 서비스 정보
            Column(modifier = Modifier.fillMaxWidth()) {
                SectionLabel("서비스 정보")
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardBackground, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "서비스",
                        color = PrimaryText,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "주요은행 환율비교 및 환율알림",
                            color = SecondaryText,
                            fontSize = 15.sp,
                            lineHeight = 18.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                tint = SecondaryText.copy(alpha = 0.7f),
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = "인터넷전문은행 제외",
                                color = SecondaryText.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            // 데이터 출처
            Column(modifier = Modifier.fillMaxWidth()) {
                SectionLabel("데이터 출처")
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardBackground, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 기준 환율
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Filled.ShowChart,
                            contentDescription = null,
                            tint = Color(0xFF2196F3),
                            modifier = Modifier.padding(top = 4.dp).size(16.dp)
                        )
                        Column {
                            Text(
                                text = "기준 환율",
                                color = PrimaryText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Investing.com",
                                color = SecondaryText,
                                fontSize = 12.sp
                            )
                        }
                    }

                    HorizontalDivider(color = SecondaryText.copy(alpha = 0.2f))

                    // 은행 환율
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Filled.AccountBalance,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.padding(top = 4.dp).size(16.dp)
                        )
                        Column {
                            Text(
                                text = "은행 환율",
                                color = PrimaryText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "국민, 하나, 신한, 우리, 기업, 농협, SC제일, 부산, 씨티",
                                color = SecondaryText,
                                fontSize = 12.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// MARK: - 유의사항 화면 (iOS NoticesView)

private data class Notice(
    val icon: ImageVector,
    val iconColor: Color,
    val title: String,
    val content: String
)

@Composable
private fun NoticesScreen(onBack: () -> Unit) {
    val notices = remember {
        listOf(
            Notice(
                icon = Icons.Filled.Warning,
                iconColor = Color(0xFFF39C12),
                title = "환율 정보 참고용",
                content = "본 앱에서 제공하는 환율 정보는 참고용이며, 실제 환전 시 해당 은행에서 적용 환율을 확인하시기 바랍니다."
            ),
            Notice(
                icon = Icons.Filled.Schedule,
                iconColor = Color(0xFF2196F3),
                title = "환율 데이터 지연",
                content = "환율 데이터는 네트워크 상황에 따라 기본적으로 수 초 또는 수 분의 지연이 발생합니다. 은행서버 지연의 경우 이보다 더 긴 시간이 소요될 수 있으며, 자체서버 문제일 경우 최선을 다해 빠르게 복구하겠습니다."
            ),
            Notice(
                icon = Icons.Filled.Payments,
                iconColor = Color(0xFF4CAF50),
                title = "환전 수수료",
                content = "표시된 환율에는 각 은행의 환전 수수료가 포함되어 있지 않습니다. 실제 환전 금액은 수수료에 따라 달라질 수 있습니다."
            ),
            Notice(
                icon = Icons.Filled.VerifiedUser,
                iconColor = Color(0xFF673AB7),
                title = "서버 점검",
                content = "평일 새벽 2~6시 그리고 주말 중에는 서버 점검으로 서비스가 잠시 원활하지 않을 수 있습니다."
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        // 헤더
        SubScreenHeader(title = "유의사항", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            notices.forEach { notice ->
                NoticeCard(notice = notice)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NoticeCard(notice: Notice) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            notice.icon,
            contentDescription = null,
            tint = notice.iconColor,
            modifier = Modifier
                .size(18.dp)
                .padding(top = 2.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = notice.title,
                color = PrimaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = notice.content,
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 15.sp
            )
        }
    }
}

// MARK: - 앱 평가 화면 (iOS AppRatingFlowView)

private enum class RatingStep { Ask, Positive, Negative }

@Composable
private fun AppRatingScreen(onBack: () -> Unit, onDismiss: () -> Unit) {
    var step by remember { mutableStateOf(RatingStep.Ask) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        SubScreenHeader(title = "앱 평가", onBack = onBack)

        when (step) {
            RatingStep.Ask -> RatingAskStep(
                onPositive = { step = RatingStep.Positive },
                onNegative = { step = RatingStep.Negative }
            )
            RatingStep.Positive -> RatingPositiveStep(
                onReview = {
                    val uri = Uri.parse("market://details?id=com.jay.fxi")
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.jay.fxi"))
                        )
                    }
                    onDismiss()
                },
                onLater = onBack
            )
            RatingStep.Negative -> RatingNegativeStep(
                onFeedback = {
                    val subject = Uri.encode("[환율알림] 피드백")
                    val body = Uri.encode(
                        "\n\n───────────────────\n" +
                        "앱 버전: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "Android: ${Build.VERSION.RELEASE}\n" +
                        "기기: ${Build.MANUFACTURER} ${Build.MODEL}"
                    )
                    val uri = Uri.parse("mailto:$SUPPORT_EMAIL?subject=$subject&body=$body")
                    context.startActivity(Intent(Intent.ACTION_SENDTO, uri))
                    onDismiss()
                },
                onLater = onBack
            )
        }
    }
}

@Composable
private fun RatingAskStep(onPositive: () -> Unit, onNegative: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(60.dp))

        // 앱 아이콘
        Image(
            painter = painterResource(id = R.drawable.ic_splash_icon),
            contentDescription = "앱 아이콘",
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(28.dp))
        )

        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = "환율알림을 사용해 주셔서",
            color = SecondaryText,
            fontSize = 15.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "감사합니다",
            color = PrimaryText,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "앱이 마음에 드셨나요?",
            color = SecondaryText,
            fontSize = 18.sp
        )

        Spacer(modifier = Modifier.weight(1f))

        // 긍정 버튼
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Primary)
                .clickable { onPositive() },
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "네, 좋아요!",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 부정 버튼 (iOS: cardBackground + stroke border)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CardBackground)
                .border(1.dp, SecondaryText.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                .clickable { onNegative() },
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Forum,
                    contentDescription = null,
                    tint = PrimaryText,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "개선이 필요해요",
                    color = PrimaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(50.dp))
    }
}

@Composable
private fun RatingPositiveStep(onReview: () -> Unit, onLater: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                // 배경 글로우 (iOS: 별 아래 텍스트 영역에 은은하게 퍼짐)
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFFFFD700).copy(alpha = 0.12f),
                            0.4f to Color(0xFFFFD700).copy(alpha = 0.05f),
                            0.7f to Color(0xFFFFD700).copy(alpha = 0.02f),
                            1.0f to Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height * 0.35f),
                        radius = size.width * 0.8f
                    ),
                    radius = size.width * 0.8f,
                    center = Offset(size.width / 2f, size.height * 0.35f)
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(80.dp))

            // 별 아이콘
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(140.dp)
            ) {
                // 별에 yellow → orange 세로 그라데이션 (iOS: LinearGradient top→bottom)
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(80.dp)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color(0xFFFFD700), Color(0xFFFF8C00))
                                ),
                                blendMode = BlendMode.SrcIn
                            )
                        }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "감사합니다!",
                color = PrimaryText,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Play 스토어 리뷰로 응원해 주시면\n더욱 좋은 서비스로 보답하겠습니다",
                color = SecondaryText,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.weight(1f))

            // 리뷰 버튼 (iOS: orange → yellow 수평 그라데이션)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFFFF8C00), Color(0xFFFFD700).copy(alpha = 0.9f))
                        )
                    )
                    .clickable { onReview() },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "리뷰 남기기",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "다음에 할게요",
                color = SecondaryText,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onLater() }
            )

            Spacer(modifier = Modifier.height(50.dp))
        }
    }
}

@Composable
private fun RatingNegativeStep(onFeedback: () -> Unit, onLater: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                // 배경 글로우 (iOS: 아이콘 아래 텍스트 영역에 은은하게 퍼짐)
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFF2196F3).copy(alpha = 0.10f),
                            0.4f to Color(0xFF2196F3).copy(alpha = 0.04f),
                            0.7f to Color(0xFF2196F3).copy(alpha = 0.015f),
                            1.0f to Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height * 0.35f),
                        radius = size.width * 0.8f
                    ),
                    radius = size.width * 0.8f,
                    center = Offset(size.width / 2f, size.height * 0.35f)
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(80.dp))

            // 아이콘 (blue → cyan 그라데이션)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(140.dp)
            ) {
                // blue → cyan 그라데이션 아이콘
                Icon(
                    Icons.Filled.Chat,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(70.dp)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(Color(0xFF2196F3), Color(0xFF00BCD4)),
                                    start = Offset.Zero,
                                    end = Offset(size.width, size.height)
                                ),
                                blendMode = BlendMode.SrcIn
                            )
                        }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "의견을 들려주세요",
                color = PrimaryText,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "불편하셨던 점이나 개선사항을\n알려주시면 검토후 반영하겠습니다",
                color = SecondaryText,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.weight(1f))

            // 피드백 버튼 (iOS: blue → cyan 수평 그라데이션)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF2196F3), Color(0xFF00BCD4).copy(alpha = 0.8f))
                        )
                    )
                    .clickable { onFeedback() },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Email,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "피드백 보내기",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "괜찮아요",
                color = SecondaryText,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onLater() }
            )

            Spacer(modifier = Modifier.height(50.dp))
        }
    }
}

// MARK: - 개인정보처리방침 (In-app WebView)

@Composable
private fun PrivacyPolicyScreen(onBack: () -> Unit) {
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        SubScreenHeader(title = "개인정보처리방침", onBack = onBack)

        Box(modifier = Modifier.fillMaxSize()) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { context ->
                    android.webkit.WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
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
                    TextButton(onClick = {
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

// MARK: - 공통 컴포넌트

@Composable
private fun SubScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "뒤로",
                tint = PrimaryText,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = title,
            color = PrimaryText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.weight(1f))
        // 균형을 위한 빈 공간
        Spacer(modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = SecondaryText,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun SettingsHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "설정",
            color = PrimaryText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = "닫기",
                color = PrimaryText,
                fontSize = 15.sp,
                modifier = Modifier.clickable { onDismiss() }
            )
        }
    }
}

@Composable
private fun ProfileCard(userInfo: UserInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 프로필 이미지 (Google: 사진, Apple: 그라데이션 + 이니셜)
        ProfileImage(userInfo = userInfo, size = 50)

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = userInfo.displayName ?: "사용자",
                color = PrimaryText,
                fontSize = 16.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            // 프로바이더 아이콘 + 라벨
            ProviderLabel(provider = userInfo.provider)
        }

        ChevronIcon()
    }
}

@Composable
private fun ProfileImage(userInfo: UserInfo, size: Int) {
    val sizeDp = size.dp

    if (userInfo.provider == AuthProvider.GOOGLE && userInfo.photoUrl != null) {
        // Google: 실제 프로필 사진
        SubcomposeAsyncImage(
            model = userInfo.photoUrl,
            contentDescription = "프로필 사진",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(sizeDp)
                .clip(CircleShape),
            loading = {
                Box(
                    modifier = Modifier
                        .size(sizeDp)
                        .clip(CircleShape)
                        .background(Primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = SecondaryText,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size((size / 3).dp)
                    )
                }
            },
            error = {
                // 사진 로드 실패: 이니셜 fallback
                InitialCircle(userInfo = userInfo, size = size)
            }
        )
    } else if (userInfo.provider == AuthProvider.APPLE) {
        // Apple: 그라데이션 원 + 이니셜 (iOS와 동일)
        Box(
            modifier = Modifier
                .size(sizeDp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF636366), Color(0xFF48484A))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            val initial = userInfo.displayName?.firstOrNull()?.uppercase()
                ?: userInfo.email?.firstOrNull()?.uppercase()
                ?: "?"
            Text(
                text = initial,
                color = Color.White,
                fontSize = (size / 2.5).sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    } else {
        // Google without photo: 이니셜
        InitialCircle(userInfo = userInfo, size = size)
    }
}

@Composable
private fun InitialCircle(userInfo: UserInfo, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Primary.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        val initial = userInfo.displayName?.firstOrNull()?.uppercase()
            ?: userInfo.email?.firstOrNull()?.uppercase()
            ?: "?"
        Text(
            text = initial,
            color = Primary,
            fontSize = (size / 2.5).sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ProviderLabel(provider: AuthProvider) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        when (provider) {
            AuthProvider.APPLE -> {
                Image(
                    painter = painterResource(id = R.drawable.ic_apple),
                    contentDescription = null,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "Apple 계정",
                    color = SecondaryText,
                    fontSize = 12.sp
                )
            }
            AuthProvider.GOOGLE -> {
                Image(
                    painter = painterResource(id = R.drawable.ic_google),
                    contentDescription = null,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "Google 계정",
                    color = SecondaryText,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(12.dp))
    ) {
        content()
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        color = SecondaryText.copy(alpha = 0.15f),
        modifier = Modifier.padding(start = 52.dp)
    )
}

@Composable
private fun ChevronIcon() {
    Icon(
        Icons.Filled.ChevronRight,
        contentDescription = null,
        tint = SecondaryText.copy(alpha = 0.5f),
        modifier = Modifier.size(20.dp)
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    iconColor: Color = PrimaryText.copy(alpha = 0.7f),
    label: String,
    labelColor: Color = PrimaryText,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            color = labelColor,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}
