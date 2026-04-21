package com.jay.fxi.util

import com.jay.fxi.domain.model.GraphPeriod

/**
 * API Configuration
 */
object ApiConfig {
    const val BASE_URL = "https://fxi.kr/"
    const val WS_URL = "wss://fxi.kr/ws"
}

/**
 * WebSocket Configuration
 */
object WebSocketConfig {
    const val PING_INTERVAL_MS = 30_000L       // 30초마다 ping
    const val PONG_TIMEOUT_MS = 10_000L        // ping 후 10초 내 응답 없으면 연결 끊김
    const val CONNECTION_TIMEOUT_MS = 15_000L  // 연결 타임아웃 15초
    const val MAX_RECONNECT_ATTEMPTS = 5
    const val BASE_RECONNECT_DELAY_MS = 2_000L // 2초 × 시도횟수 (±20% 지터)
}

/**
 * Graph Configuration
 */
object GraphConfig {
    const val MAX_BUCKETS = 144                // 24시간 × 6 (10분 버킷)
    const val GAP_THRESHOLD_SEC = 900          // 15분 (갭 감지 임계값)
    const val BUCKET_DURATION_SEC = 600        // 10분
    const val REFRESH_INTERVAL_SEC = 300       // 5분마다 갭 체크
    const val FULL_FETCH_COOLDOWN_SEC = 120    // REST 풀 로드 최소 간격 (2분)

    // Foreground resync (화면 off 후 복귀 시 stale 1d 그래프 복구)
    const val BACKGROUND_RESYNC_THRESHOLD_MS = 60_000L    // 60초 이상 background 시 silent refresh
    const val FOREGROUND_REFRESH_COOLDOWN_MS = 30_000L    // foreground refresh 전용 쿨다운
    const val X_DOMAIN_ABNORMAL_SEC = 26L * 3600L         // 1d 그래프 x-domain이 26h 초과면 stale 판정
    const val LAST_BUCKET_STALE_MS = 15L * 60L * 1000L    // 마지막 bucket 15분 이상 오래되면 stale

    fun gapThresholdSec(period: GraphPeriod): Int = when (period) {
        GraphPeriod.ONE_DAY -> 900
        GraphPeriod.ONE_WEEK -> 7_200
        GraphPeriod.THREE_MONTHS -> 86_400
        GraphPeriod.ONE_YEAR -> 86_400
    }

    fun cacheTtlMs(period: GraphPeriod): Long = when (period) {
        GraphPeriod.ONE_DAY -> 86_400_000L
        GraphPeriod.ONE_WEEK -> 3_600_000L
        GraphPeriod.THREE_MONTHS -> 21_600_000L
        GraphPeriod.ONE_YEAR -> 86_400_000L
    }
}

/**
 * News Configuration
 */
object NewsConfig {
    const val SUBSCRIBER_COOLDOWN_MS = 60_000L        // 구독자 쿨다운 60초
    const val NON_SUBSCRIBER_COOLDOWN_MS = 300_000L   // 비구독자 쿨다운 5분
    const val POLLING_INTERVAL_SHORT_MS = 120_000L    // adaptive: 새 기사 있을 때 2분
    const val POLLING_INTERVAL_LONG_MS = 300_000L     // adaptive: 변화 없을 때 5분
    const val TIME_REFRESH_INTERVAL_MS = 60_000L      // 상대 시간 갱신 주기 60초
}

/**
 * Alert Configuration
 */
object AlertConfig {
    const val MAX_COUNT = 30                   // 사용자당 최대 알림 개수
    const val SHOW_REMAINING_THRESHOLD = 3     // 남은 개수 표시 임계값
}

/**
 * App Color Palette (Dark Mode)
 * Long 상수로 정의하여 UI 레이어 의존성 제거
 * UI에서 Color(value)로 변환하여 사용
 */
object AppColors {
    // 배경
    const val BACKGROUND = 0xFF121212L
    const val CARD_BACKGROUND = 0xFF1E1E1EL
    const val INPUT_BACKGROUND = 0xFF2C2C2CL

    // 텍스트
    const val PRIMARY_TEXT = 0xFFE0E0E0L
    const val SECONDARY_TEXT = 0xFF95A5A6L

    // 상태 (환율 기준 - 한국 금융 관례 + 사용자 관점)
    const val POSITIVE = 0xFFEF6B5EL   // 기준보다 비쌈 (부드러운 빨강)
    const val NEGATIVE = 0xFF4DCC6AL   // 기준보다 쌈 (부드러운 초록)
    const val NEUTRAL = 0xFFBDC3C7L

    // 연결 상태
    const val STATUS_ONLINE = 0xFF2ECC71L
    const val STATUS_CONNECTING = 0xFFF39C12L
    const val STATUS_ERROR = 0xFFE74C3CL

    // 기준 표시
    const val REFERENCE_BORDER = 0xFF7EB3FFL
}
