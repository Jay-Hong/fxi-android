package com.jay.fxi.ui.screen

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText

/**
 * 뉴스 상세 풀스크린 오버레이 (iOS NewsDetailView 패리티)
 *
 * - 기존 SettingsScreen.PrivacyPolicyScreen WebView 패턴 기반
 * - KB 호스트에서만 앱 배너 제거 JS 주입
 * - 닫기(X) 버튼으로 복귀
 */
@Composable
fun NewsDetailOverlay(
    url: String,
    onDismiss: () -> Unit
) {
    // 시스템 뒤로가기로 오버레이 닫기 (PaywallScreen과 동일 패턴)
    BackHandler(onBack = onDismiss)

    val parsedUri = remember(url) { Uri.parse(url) }
    val isKBHost = remember(parsedUri) { parsedUri.host == "fx.kbstar.com" }

    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        if (hasError) {
            // 에러 화면
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "내용을 불러올 수 없습니다",
                    color = PrimaryText,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = {
                    hasError = false
                    isLoading = true
                }) {
                    Text("다시 시도", color = Primary)
                }
            }
        } else {
            // WebView
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // KB 호스트: HTTPS 페이지 내 HTTP 이미지(newsimage.einfomax.co.kr) 허용
                        if (isKBHost) {
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }
                        setBackgroundColor(AndroidColor.parseColor("#121212"))
                        setOnTouchListener { v, _ ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            false
                        }

                        webViewClient = object : WebViewClient() {
                            private var hasFinishedInitialLoad = false

                            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                hasFinishedInitialLoad = true
                                isLoading = false

                                // KB 호스트: 배너 제거 JS 주입
                                if (isKBHost) {
                                    view?.evaluateJavascript(KB_BANNER_REMOVAL_JS, null)
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    isLoading = false
                                    hasError = true
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                // iOS 패리티: 리다이렉트/리소스 로드는 허용,
                                // 사용자 클릭만 호스트 체크
                                val isUserClick = request?.hasGesture() == true
                                if (!isUserClick) return false

                                val requestHost = request?.url?.host ?: return true
                                return requestHost != parsedUri.host
                            }
                        }

                        // KB 호스트도 iOS처럼 직접 URL 로드 (loadDataWithBaseURL 제거)
                        // 배너 제거는 onPageFinished에서 JS 주입으로 처리
                        loadUrl(url)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 로딩 오버레이
        AnimatedVisibility(
            visible = isLoading && !hasError,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Background),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = SecondaryText, strokeWidth = 3.dp)
                    Text("불러오는 중...", color = SecondaryText, fontSize = 14.sp)
                }
            }
        }

        // 닫기 버튼 (좌상단)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 8.dp)
                .size(28.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "닫기",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// KB 배너 제거 + 공유 버튼 숨김 JS
// 배너: iOS와 동일 selector
// 공유: Android WebView에서 동작하지 않는 공유 기능 제거 (broken path 차단)
private const val KB_BANNER_REMOVAL_JS = """
(function() {
    function hideElements() {
        var sels = [
            '.header-mo.mo-device-only-header', '.wrap-header-top', '.header-banner',
            '.wrap-share', '.butn-share', '#mobilePop'
        ];
        sels.forEach(function(s) {
            document.querySelectorAll(s).forEach(function(el) { el.style.display = 'none'; });
        });
    }
    hideElements();
    var obs = new MutationObserver(function() { hideElements(); });
    if (document.body) {
        obs.observe(document.body, { childList: true, subtree: true });
    }
})();
"""

