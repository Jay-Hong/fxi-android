# FXi Android MVP 개발 가이드

> **목적**: Android 앱 구현 가이드 (iOS MVP 기반)
> **Phase**: 1 - Android App 개발
> **최종 수정**: 2026-04-03

---

## 프로젝트 개요

**FXi**는 실시간 은행 간 환율 비교 서비스입니다. 인베스팅닷컴 기준 환율과 9개 은행의 환율을 비교하여 사용자가 최적의 환전 시점을 파악할 수 있도록 합니다.

> **참고**: iOS 앱이 이미 App Store에 출시되어 있으며, 동일한 백엔드 API를 사용합니다.
> iOS 구현 참조: `../ios/CLAUDE.md`

### 핵심 기능

1. **실시간 환율 비교**: WebSocket으로 10초마다 업데이트
2. **기간별 그래프**: 1일/1주/3달/1년 환율 추이 시각화
3. **USD/KRW 달러지수(DXY) 그래프**: 달러 탭에서 환율과 함께 제공
4. **은행별 비교 바 차트**: 순서/표시 커스터마이징, 동적 기준 환율 지원
5. **오프라인 지원**: 캐시된 데이터로 오프라인 동작
6. **환율 알림**: 목표 환율 도달 시 푸시 알림 (1-shot 알림, 최대 30개)
7. **뉴스**: 실시간 환율 뉴스 리스트 + 인앱 WebView 상세 (인증 불필요, 비구독자도 공개 기사 열람 가능)
8. **소셜 로그인**: Google Sign-In + Apple Sign-In (Firebase Auth OAuth, 웹 플로우)
9. **프리미엄 구독**: RevenueCat 연동 (월간/연간, 무료 체험 지원)

> **Apple Sign-In**: Android에는 Apple 공식 SDK가 없어 **OAuth 웹 플로우**로 지원 예정.
> Firebase Auth의 `OAuthProvider("apple.com")`을 사용하면 Android에서도 Apple 로그인 연동 가능.

---

## 기술 스택

### Core

| 영역 | 기술 | 비고 |
|------|------|------|
| **언어** | Kotlin 2.x | 100% Kotlin |
| **UI** | Jetpack Compose | Material 3 |
| **아키텍처** | MVVM + Clean Architecture | |
| **DI** | Hilt | |
| **비동기** | Kotlin Coroutines + Flow | |
| **네트워크** | Retrofit + OkHttp | WebSocket 포함 |
| **직렬화** | Kotlinx Serialization | |
| **로컬 저장** | DataStore + File | |
| **차트** | Compose Canvas | 기간별 축 포맷, DXY 오버레이, Live Tail 지원 |

### Firebase & 외부 서비스

| 서비스 | 용도 |
|--------|------|
| **Firebase Auth** | Google/Apple 로그인, 사용자 인증 |
| **Firebase Cloud Messaging** | 푸시 알림 |
| **Firebase Crashlytics** | 크래시 리포팅 |
| **RevenueCat** | 구독 관리 |

### 최소 요구사항

- **minSdk**: 26 (Android 8.0)
- **targetSdk**: 35 (Android 15, Google Play 정책)
- **compileSdk**: 36 (AndroidX 라이브러리 호환)

---

## iOS → Android 기술 매핑

| iOS | Android |
|-----|---------|
| SwiftUI | Jetpack Compose |
| @Observable | StateFlow / MutableState |
| @Environment DI | Hilt @Inject |
| async/await | suspend + Coroutines |
| Combine | Flow / StateFlow |
| Swift Charts | Compose Canvas |
| URLSession WebSocket | OkHttp WebSocketListener |
| UserDefaults | DataStore Preferences |
| FileManager | Context.filesDir |
| NWPathMonitor | ConnectivityManager |
| UNUserNotificationCenter | NotificationManager |

---

## 앱 구조

### 화면 구성

```
┌─────────────────────────────────────────────┐
│  [달러]  [엔화]  [유로]  [뉴스]  ← 상단 탭    │
├─────────────────────────────────────────────┤
│                                             │
│  ┌─────────────────────────────────────┐   │
│  │  기간별 환율 그래프 + DXY(USD)       │   │
│  │  (1일/1주/3달/1년, 소스 토글)        │   │
│  └─────────────────────────────────────┘   │
│                                             │
│  ┌─────────────────────────────────────┐   │
│  │  인베스팅  ████████████  1,407.50   │   │
│  │  국민은행  ██████████    +1.70      │   │
│  │  하나은행  █████████     +1.30      │   │
│  │  신한은행  ████████      +2.60      │   │
│  │  ...                                 │   │
│  └─────────────────────────────────────┘   │
│                                             │
│  ┌─────────────────────────────────────┐   │
│  │  알림 설정 섹션                       │   │
│  └─────────────────────────────────────┘   │
│                                             │
└─────────────────────────────────────────────┘
```

### 프로젝트 구조

```
app/
├── src/main/
│   ├── java/com/jay/fxi/
│   │   ├── FXiApplication.kt           # Application class (Hilt)
│   │   ├── MainActivity.kt             # Single Activity
│   │   │
│   │   ├── data/
│   │   │   ├── local/
│   │   │   │   ├── BankPreferenceManager.kt  # 은행 순서/표시 설정 (DataStore)
│   │   │   │   ├── GraphPreferenceManager.kt # 그래프 토글 영속화 (DataStore)
│   │   │   │   └── CacheService.kt           # 기간별 그래프 캐시 (versioned JSON)
│   │   │   │
│   │   │   ├── network/
│   │   │   │   └── NetworkMonitor.kt
│   │   │   │
│   │   │   ├── remote/
│   │   │   │   ├── FXiApiService.kt    # Retrofit REST API
│   │   │   │   ├── WebSocketService.kt # OkHttp WebSocket
│   │   │   │   └── dto/                # API DTO (Response/Request)
│   │   │   │
│   │   │   └── repository/
│   │   │       ├── ExchangeRateRepositoryImpl.kt
│   │   │       ├── AlertRepository.kt
│   │   │       └── NewsRepositoryImpl.kt
│   │   │
│   │   ├── domain/
│   │   │   ├── model/
│   │   │   │   ├── ExchangeRate.kt
│   │   │   │   ├── GraphBucket.kt      # + GraphPoint, PeriodGraphCacheEntry, 타입 앨리어스
│   │   │   │   ├── GraphPeriod.kt      # 기간 enum (1d/1w/3m/1y)
│   │   │   │   ├── GraphSource.kt      # 소스 enum (+ REFERENCE, DXY)
│   │   │   │   ├── BankPreference.kt   # BankDisplayConfig, RatesDisplayState
│   │   │   │   ├── Bank.kt
│   │   │   │   ├── SupportedCurrency.kt
│   │   │   │   ├── AlertSetting.kt
│   │   │   │   ├── AppState.kt
│   │   │   │   ├── RatesResult.kt
│   │   │   │   ├── TabSelection.kt         # 탭 상태 (Currency | News)
│   │   │   │   ├── NewsItem.kt             # 뉴스 모델 + NewsResponse
│   │   │   │   └── NewsContentType.kt      # external_link | report_pdf
│   │   │   │
│   │   │   └── repository/
│   │   │       ├── ExchangeRateRepository.kt
│   │   │       └── NewsRepository.kt
│   │   │
│   │   ├── ui/
│   │   │   ├── theme/
│   │   │   │   ├── Color.kt
│   │   │   │   ├── ColorExtensions.kt
│   │   │   │   ├── RateLayoutMetrics.kt
│   │   │   │   ├── Theme.kt
│   │   │   │   └── Type.kt
│   │   │   │
│   │   │   ├── screen/
│   │   │   │   ├── MainScreen.kt       # 탭 + 환율 리스트 + 뉴스
│   │   │   │   ├── CurrencyTabContent.kt  # 통화별 탭 (그래프 + 바차트 + 알림)
│   │   │   │   ├── RootScreen.kt       # 인증/구독 분기, Paywall 오버레이
│   │   │   │   ├── NewsTabContent.kt   # 뉴스 리스트 (4번째 탭)
│   │   │   │   └── NewsDetailOverlay.kt # 풀스크린 WebView 오버레이
│   │   │   │
│   │   │   ├── viewmodel/
│   │   │   │   ├── ExchangeRateViewModel.kt
│   │   │   │   ├── GraphViewModel.kt   # 기간별 캐시, DXY, freshness
│   │   │   │   ├── AlertViewModel.kt
│   │   │   │   ├── BankPreferenceViewModel.kt  # 은행 순서/표시 ViewModel
│   │   │   │   └── NewsViewModel.kt    # 뉴스 (cooldown, adaptive polling, ticker)
│   │   │   │
│   │   │   ├── components/             # 공용 컴포넌트
│   │   │   │   ├── RateGraphView.kt    # Compose Canvas 차트
│   │   │   │   ├── RateBarView.kt
│   │   │   │   ├── SourceToggleRow.kt  # 1d 전용 소스 토글
│   │   │   │   ├── PeriodTabBar.kt     # 기간 선택 탭 (pill 스타일)
│   │   │   │   ├── DxyToggleButton.kt  # DXY 토글 (USD/KRW 전용)
│   │   │   │   ├── BankCustomizeSheet.kt  # 은행 순서 드래그 정렬 시트
│   │   │   │   ├── BankIcon.kt
│   │   │   │   ├── IOSStyleToggle.kt
│   │   │   │   ├── StatusComponents.kt
│   │   │   │   └── NewsRow.kt          # 뉴스 리스트 셀
│   │   │   │
│   │   │   ├── alert/
│   │   │   │   ├── AlertSection.kt
│   │   │   │   ├── AlertAddSheet.kt
│   │   │   │   └── AlertRow.kt
│   │   │   │
│   │   │   ├── auth/
│   │   │   │   ├── AuthViewModel.kt
│   │   │   │   └── LoginScreen.kt
│   │   │   │
│   │   │   ├── subscription/
│   │   │   │   ├── PaywallScreen.kt
│   │   │   │   ├── LockedPreviewScreen.kt
│   │   │   │   ├── SamplePreviewViewModel.kt
│   │   │   │   ├── SampleData.kt
│   │   │   │   ├── SampleAlertSetting.kt
│   │   │   │   ├── SampleAlertSection.kt
│   │   │   │   ├── SampleAlertAddSheet.kt
│   │   │   │   └── SampleAlertTriggerBanner.kt
│   │   │   │
│   │   │   └── settings/
│   │   │       ├── SettingsScreen.kt
│   │   │       └── SettingsViewModel.kt
│   │   │
│   │   ├── service/
│   │   │   ├── FXiMessagingService.kt  # FCM Service
│   │   │   ├── PushNotificationManager.kt
│   │   │   └── AlertEventBus.kt
│   │   │
│   │   ├── subscription/
│   │   │   └── SubscriptionManager.kt
│   │   │
│   │   ├── di/
│   │   │   ├── AuthModule.kt
│   │   │   ├── NetworkModule.kt
│   │   │   └── RepositoryModule.kt
│   │   │
│   │   └── util/
│   │       ├── Constants.kt            # ApiConfig, WebSocketConfig, GraphConfig, AlertConfig, AppColors
│   │       ├── InstantSerializer.kt
│   │       └── TaskExtensions.kt
│   │
│   ├── res/
│   │   ├── drawable/                   # 은행 아이콘
│   │   ├── values/
│   │   │   ├── colors.xml
│   │   │   └── strings.xml
│   │   ├── xml/
│   │   │   └── network_security_config.xml  # KB 이미지 HTTP 허용
│   │   └── ...
│   │
│   └── AndroidManifest.xml
│
├── build.gradle.kts
└── google-services.json                # Firebase 설정
```

---

## API 연동

### Base URL

```
REST: https://fxi.kr
WebSocket: wss://fxi.kr/ws
```

### 공통 규칙

- **타임존**: 모든 시간은 **KST (UTC+09:00)**, ISO8601 형식
- **환율 소수점**: 2자리 (예: `1407.50`)
- **JSON 키**: `snake_case` (Kotlinx Serialization: `@SerialName` 또는 naming policy)

### REST API

#### 전체 환율 조회

```http
GET /api/rates

Response:
{
  "rates": [
    {"currency": "usd-krw", "bank": "investing", "rate": 1407.50, "timestamp": "2025-12-05T14:30:00+09:00"},
    {"currency": "usd-krw", "bank": "kb", "rate": 1409.20, "timestamp": "2025-12-05T14:28:00+09:00"}
    // 총 30개 (10개 은행 x 3개 통화)
  ],
  "metadata": {
    "updated_at": "2025-12-05T14:30:00+09:00",
    "currencies": ["usd-krw", "jpy-krw", "eur-krw"],
    "banks": ["investing", "kb", "hana", "shinhan", "woori", "ibk", "nh", "sc", "bs", "citi"],
    "total_count": 30
  }
}
```

#### 그래프 데이터 조회 (기간별)

```http
GET /api/graph/{currency}
GET /api/graph/{currency}?range=1w
GET /api/graph/{currency}?range=3m
GET /api/graph/{currency}?range=1y

Parameters: currency = usd-krw | jpy-krw | eur-krw

Response:
{
  "pair": "usd-krw",
  "period": "1d",
  "bucket_size": "10m",
  "sources": {
    "investing": [[1733380800, 1407.8, 1407.2, 1407.5], ...],  // [timestamp, max, min, close]
    "kb": [[1733380800, 1409.5, 1409.0, 1409.2], ...],
    "hana": [[1733380800, 1409.0, 1408.5, 1408.8], ...],
    "dxy": [[1733380800, 103.8, 103.6, 103.7], ...]
  },
  "as_of": "2025-12-05T14:30:00+09:00"
}

※ `range` 생략 시 `1d`
※ `1d`: 10분 버킷, 실시간 소스(`investing`, `kb`, `hana`) 중심
※ `1w/3m/1y`: `reference` 중심 장기 그래프, USD/KRW는 `dxy` 포함 가능
※ REST는 배열 형식 [ts, max, min, close]
```

#### 알림 설정 API

```http
# 목록 조회
GET /api/notification-settings
Authorization: Bearer {firebase_id_token}

# 생성
POST /api/notification-settings
Authorization: Bearer {firebase_id_token}
{
  "bank": "kb",
  "currency": "usd-krw",
  "condition": "below",
  "threshold": 1400.0,
  "is_enabled": true
}

# 수정/토글
PUT /api/notification-settings/{id}
Authorization: Bearer {firebase_id_token}
{
  "is_enabled": true  // 또는 bank, condition, threshold 변경
}

# 삭제
DELETE /api/notification-settings/{id}
Authorization: Bearer {firebase_id_token}
```

#### 뉴스 조회 API (인증 불필요)

```http
GET /api/news?limit=100&hours=24.0

Response:
{
  "news": [
    {"id": "...", "title": "...", "link": "...", "source": "einfomax", "content_type": "external_link", "published_at": "..."}
  ],
  "metadata": {"returned_count": 50, "window_hours": 24.0, "responded_at": "..."}
}
```

> 상세 스펙: `../exchange-rate/NEWS_API_SPEC.md`
> content_type: `external_link` (인앱 WebView), `report_pdf` (외부 브라우저)
> 비구독자 필터링: `news.einfomax.co.kr` 공개, `fx.kbstar.com`/`rreport.einfomax.co.kr` 프리미엄
>
> **KB 호스트 예외 처리 (`fx.kbstar.com`)**: 앱 배너/공유 버튼 숨김 (JS 주입), 이미지 HTTP 허용 (`network_security_config.xml` + `mixedContentMode`)
>
> **새 뉴스 도착 동작 (NewsTabContent)**:
> - 상단 근처(`firstVisibleItemIndex == 0 && offset < 120dp`)면 `animateScrollToItem(0)`
> - 아래 읽는 중이면 "새 뉴스" 배너 표시 (탭 시 상단 이동)
> - 뉴스 탭 비가시(`pagerState.settledPage != newsPageIndex`) 또는 상세 오버레이 열림 시 보류, 조건 해제 시 재평가

#### 기기 등록 API

```http
# FCM 토큰 등록
POST /api/register-device
Authorization: Bearer {firebase_id_token}
{
  "device_token": "{fcm_token}",
  "platform": "android"
}

# FCM 토큰 해제
DELETE /api/register-device?device_token={fcm_token}
Authorization: Bearer {firebase_id_token}
```

#### 계정 삭제 API

```http
DELETE /api/user/me
Authorization: Bearer {firebase_id_token}

Response: 204 No Content
```

### WebSocket

#### 메시지 타입

**수신 - 환율 데이터 (10초마다, 변경 시에만):**
```json
{
  "type": "rates",
  "data": {
    "rates": [...],
    "indices": {
      "dxy": {"rate": 99.234, "timestamp": "2026-04-18T09:07:45.189537+09:00", "source": "investing"}
    },
    "metadata": {...}
  },
  "graph_buckets": {
    "usd-krw": {
      "investing": {"bucket_ts": 1733380800, "max": 1407.8, "min": 1407.2, "close": 1407.5},
      "kb": {...},
      "hana": {...},
      "dxy": {"bucket_ts": ..., "max": ..., "min": ..., "close": ...}
    }
  }
}
```

> **중요**: WebSocket graph_buckets는 **객체 형식** (REST는 배열)
> **`data.indices.dxy`** (2026-04-19 추가): 10초 해상도 DXY live tick. `ExchangeRateViewModel.dxyLive: StateFlow<DxyLiveTick?>`에 매핑. Kotlinx Serialization `ignoreUnknownKeys = true`로 구 서버 응답과 양방향 호환.
> **두 DXY 경로 분리**: `data.indices.dxy` = live(10초), `graph_buckets.usd-krw.dxy` = bucket close(1분 갱신). `CurrencyTabContent.buildGraphPayload`에서 `dxyLive?.rate ?: graphViewModel.latestDxyRate()` 우선순위.
> **Broadcast 트리거**: 서버는 `rates` 또는 `indices.dxy` 변화 어느 쪽이든 발화 (`build_rates_payload()` 전체 JSON 비교).

**수신 - Pong:**
```json
{"type": "pong"}
```

**송신 - Ping:**
```
ping
```
> Plain text 문자열 (JSON 아님)

#### 연결 동작

- **초기 연결 시**: 서버가 즉시 캐시된 환율 데이터 전송
  - `data.rates` + `data.metadata` + `data.indices.dxy` 포함 (`build_rates_payload()` 기반 Redis 캐시)
  - `graph_buckets`는 미포함 (브로드캐스트 append 시점에 합쳐지므로 Redis 캐시에 기록되지 않음)
- **10초 브로드캐스트**: 변경 시에만 전송 — rates 또는 indices.dxy 변화 어느 쪽이든 발화 → `data.rates` + `data.indices.dxy` + `graph_buckets`
- **Ping/Pong**: 연결 상태 확인용

---

## 데이터 모델

### Kotlin Data Classes

```kotlin
// 환율 데이터
@Serializable
data class ExchangeRate(
    val currency: String,      // "usd-krw", "jpy-krw", "eur-krw"
    val bank: String,          // "investing", "kb", "hana", ...
    val rate: Double,          // 1407.50
    val timestamp: Instant     // ISO8601 자동 파싱
) {
    val id: String get() = "$currency-$bank"
    val bankType: Bank? get() = Bank.fromCode(bank)
    val currencyType: SupportedCurrency? get() = SupportedCurrency.fromCode(currency)
    val isJPY: Boolean get() = currency == SupportedCurrency.JPY_KRW.code
    val formattedRate: String get() = formatRate(rate, currency)

    fun difference(from: ExchangeRate?): Double? = from?.let { rate - it.rate }
    fun formattedDifference(from: ExchangeRate?): String? = difference(from)?.let {
        val sign = if (it >= 0) "+" else ""
        "$sign${String.format("%.2f", it)}"
    }
}

// DXY live tick (WebSocket indices.dxy, 10초 realtime, iOS DxyLiveTick parity)
@Serializable
data class DxyLiveTick(
    val rate: Double,
    @Serializable(with = InstantSerializer::class)
    val timestamp: Instant,
    val source: String   // "investing" | "yahoo"
)

@Serializable
data class IndicesPayload(
    val dxy: DxyLiveTick? = null
)

// ExchangeRateViewModel가 `dxyLive: StateFlow<DxyLiveTick?>` 보유.
// WebSocketService.onIndicesReceived 콜백이 갱신. reset() 시 null 초기화.
// 그래프 렌더 시 CurrencyTabContent.buildGraphPayload에서 dxyLive?.rate 우선,
// 없으면 graphViewModel.latestDxyRate() (graph bucket close) fallback.

// follow-latest 10초 synthetic tick (iOS v2.6 parity, 2026-04-19 추가):
//  - RateGraphView 내부 계산: isFollowActive = pocIsFollowingLatest && pocVisibleDomain != null && period == ONE_DAY
//  - 상태 변경 시 RateGraphView가 onFollowActiveChanged 콜백으로 상위(CurrencyTabContent)에 전달
//  - CurrencyTabContent가 isFollowActive를 @State로 보관, LaunchedEffect(isFollowActive) 10초 delay 루프
//    조건 이탈 시 자동 취소 (불필요한 recomposition 방지, iOS .task(id:) gating과 등가)
//  - followTick이 graphPayload remember 키에 포함 → buildGraphPayload 재평가 유도
//  - buildGraphPayload의 tailTimestamp는 Clock.System.now() 기반 (이전엔 max rate timestamp)
//  - 효과: broadcast 없는 주말/저변동 구간에도 창이 끊김 없이 왼쪽으로 이동 (10초 step 갱신)
//  - 상세: ../ios/GRAPH_ZOOM_DESIGN.md v2.6 참조

// 그래프 버킷
@Serializable
data class GraphBucket(
    @SerialName("bucket_ts") val bucketTs: Int,   // Unix timestamp (초)
    val max: Double,
    val min: Double,
    val close: Double
) {
    val id: Int get() = bucketTs
    val date: Instant get() = Instant.fromEpochSeconds(bucketTs.toLong())

    companion object {
        // REST 배열 [ts, max, min, close]에서 변환
        fun fromArray(arr: List<Double>): GraphBucket? {
            if (arr.size != 4) return null
            return GraphBucket(
                bucketTs = arr[0].toInt(),
                max = arr[1],
                min = arr[2],
                close = arr[3]
            )
        }
    }
}

// 그래프 캐시 타입 앨리어스
typealias GraphCache = Map<String, Map<String, List<GraphBucket>>>
typealias MutableGraphCache = MutableMap<String, MutableMap<String, MutableList<GraphBucket>>>
typealias GraphSourceData = Map<String, List<GraphBucket>>
typealias PeriodGraphCache = Map<String, Map<String, GraphSourceData>>
typealias MutablePeriodGraphCache = MutableMap<String, MutableMap<String, GraphSourceData>>

// 장기 구간 디스크 캐시 엔트리
@Serializable
data class PeriodGraphCacheEntry(
    val sources: GraphSourceData,
    val freshnessDate: Instant
)

// 그래프 REST 응답의 도메인 모델
data class GraphDataResult(
    val period: GraphPeriod,
    val bucketSize: String,
    val sources: GraphSourceData,
    val asOf: Instant?
)

// 알림 설정
@Serializable
data class AlertSetting(
    val id: Int,
    @SerialName("user_id") val userId: String,
    val bank: String,
    val currency: String,
    val condition: AlertCondition,
    val threshold: Double,
    @SerialName("is_enabled") var isEnabled: Boolean,
    var triggered: Boolean,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") var updatedAt: Instant,
    @SerialName("triggered_at") var triggeredAt: Instant? = null
)

@Serializable
enum class AlertCondition {
    @SerialName("above") ABOVE,  // 이상
    @SerialName("below") BELOW   // 이하
}
```

### 앱 상태

```kotlin
// 앱 전체 상태
sealed class AppState {
    object Loading : AppState()
    data class Connected(val rates: List<ExchangeRate>) : AppState()
    data class Error(val message: String) : AppState()
    data class Offline(val cachedRates: List<ExchangeRate>?) : AppState()

    val rates: List<ExchangeRate>?
        get() = when (this) {
            is Connected -> rates
            is Offline -> cachedRates
            else -> null
        }

    val hasData: Boolean get() = !rates.isNullOrEmpty()
    val isLoading: Boolean get() = this is Loading
    val isError: Boolean get() = this is Error
    val isOffline: Boolean get() = this is Offline
    val isConnected: Boolean get() = this is Connected
}

// WebSocket 연결 상태
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Reconnecting(val attempt: Int) : ConnectionState()
    data class Failed(val message: String) : ConnectionState()

    val isConnecting: Boolean get() = this is Connecting || this is Reconnecting
    val isConnected: Boolean get() = this is Connected
    val isFailed: Boolean get() = this is Failed

    val statusText: String
        get() = when (this) {
            Disconnected -> "연결 끊김"
            Connecting -> "연결 중..."
            Connected -> "실시간 연결"
            is Reconnecting -> if (attempt == 1) "연결 중..." else "재연결 중 ($attempt/${WebSocketConfig.MAX_RECONNECT_ATTEMPTS})"
            is Failed -> message
        }
}

// 인증 상태
sealed class AuthState {
    object Unknown : AuthState()
    object SignedOut : AuthState()
    data class SignedIn(val user: UserInfo) : AuthState()
}

data class UserInfo(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
    val provider: AuthProvider
)

enum class AuthProvider { GOOGLE, APPLE }
```

### Enums

```kotlin
// 지원 통화
enum class SupportedCurrency(val code: String, val displayName: String, val tabTitle: String) {
    USD_KRW("usd-krw", "USD/KRW", "달러"),
    JPY_KRW("jpy-krw", "JPY/KRW", "엔화"),
    EUR_KRW("eur-krw", "EUR/KRW", "유로");

    val isJPY: Boolean get() = this == JPY_KRW

    companion object {
        fun fromCode(code: String): SupportedCurrency? = entries.find { it.code == code }
    }
}

// 그래프 기간
enum class GraphPeriod(val code: String, val displayName: String) {
    ONE_DAY("1d", "1일"),
    ONE_WEEK("1w", "1주"),
    THREE_MONTHS("3m", "3달"),
    ONE_YEAR("1y", "1년");

    val isRealtime: Boolean get() = this == ONE_DAY

    companion object {
        fun fromCode(code: String?): GraphPeriod? = entries.find { it.code == code }
    }
}

// 은행
enum class Bank(
    val code: String,
    val displayName: String,
    val shortName: String,
    val colorHex: Long
) {
    INVESTING("investing", "인베스팅", "인베스팅", 0xFF2C3E50),
    KB("kb", "국민은행", "국민", 0xFFFFB200),
    HANA("hana", "하나은행", "하나", 0xFF009792),
    SHINHAN("shinhan", "신한은행", "신한", 0xFF0052FF),
    WOORI("woori", "우리은행", "우리", 0xFF0089D4),
    IBK("ibk", "기업은행", "기업", 0xFF0049A0),
    NH("nh", "농협은행", "농협", 0xFF00AC41),
    SC("sc", "SC제일", "SC", 0xFF0075F2),
    BS("bs", "부산은행", "부산", 0xFFDD1A25),
    CITI("citi", "씨티은행", "씨티", 0xFF006EB3);

    val color: Color get() = Color(colorHex)
    val isReference: Boolean get() = this == INVESTING

    companion object {
        fun fromCode(code: String): Bank? = entries.find { it.code == code }
    }
}

// 그래프 소스
enum class GraphSource(val code: String, val displayName: String, val colorHex: Long) {
    INVESTING("investing", "인베스팅", 0xFF9DB6D8),
    KB("kb", "국민은행", 0xFFFFB200),
    HANA("hana", "하나은행", 0xFF00A7A0),
    REFERENCE("reference", "인베스팅", 0xFF9DB6D8),  // 장기 구간용 (백엔드가 investing→reference로 반환)
    DXY("dxy", "달러지수", 0xFFE06060);              // USD/KRW 전용

    companion object {
        fun fromCode(code: String): GraphSource? = entries.find { it.code == code }
        val realtimeSources: List<GraphSource> = listOf(INVESTING, KB, HANA)
    }
}
```

---

## 핵심 설정값

```kotlin
object WebSocketConfig {
    const val PING_INTERVAL_MS = 30_000L       // 30초마다 ping
    const val PONG_TIMEOUT_MS = 10_000L        // ping 후 10초 내 응답 없으면 연결 끊김
    const val CONNECTION_TIMEOUT_MS = 15_000L  // 연결 타임아웃 15초
    const val MAX_RECONNECT_ATTEMPTS = 5
    const val BASE_RECONNECT_DELAY_MS = 2_000L // 2초 × 시도횟수 (±20% 지터)
}

object GraphConfig {
    const val MAX_BUCKETS = 144                // 24시간 × 6 (10분 버킷)
    const val GAP_THRESHOLD_SEC = 900          // 15분 (1d 갭 감지 기본값)
    const val BUCKET_DURATION_SEC = 600        // 10분
    const val REFRESH_INTERVAL_SEC = 300       // 5분마다 갭 체크
    const val FULL_FETCH_COOLDOWN_SEC = 120    // REST 풀 로드 최소 간격 (2분)

    // 기간별 갭 감지 임계값
    fun gapThresholdSec(period: GraphPeriod): Int = when (period) {
        GraphPeriod.ONE_DAY -> 900              // 15분
        GraphPeriod.ONE_WEEK -> 7_200           // 2시간
        GraphPeriod.THREE_MONTHS -> 86_400      // 1일
        GraphPeriod.ONE_YEAR -> 86_400          // 1일
    }

    // 기간별 디스크 캐시 TTL
    fun cacheTtlMs(period: GraphPeriod): Long = when (period) {
        GraphPeriod.ONE_DAY -> 86_400_000L      // 24시간
        GraphPeriod.ONE_WEEK -> 3_600_000L      // 1시간
        GraphPeriod.THREE_MONTHS -> 21_600_000L // 6시간
        GraphPeriod.ONE_YEAR -> 86_400_000L     // 24시간
    }
}

object AlertConfig {
    const val MAX_COUNT = 30                   // 사용자당 최대 알림 개수
    const val SHOW_REMAINING_THRESHOLD = 3    // 남은 개수 표시 임계값
}

object ApiConfig {
    const val BASE_URL = "https://fxi.kr"
    const val WS_URL = "wss://fxi.kr/ws"
}
```

---

## 색상 팔레트 (다크 모드)

```kotlin
object AppColors {
    // 배경
    val background = Color(0xFF121212)
    val cardBackground = Color(0xFF1E1E1E)
    val inputBackground = Color(0xFF2C2C2C)

    // 텍스트
    val primaryText = Color(0xFFE0E0E0)
    val secondaryText = Color(0xFF95A5A6)

    // 상태 (환율 기준 - 한국 금융 관례 + 사용자 관점)
    val positive = Color(0xFFE74C3C)   // 기준보다 비쌈 (빨강)
    val negative = Color(0xFF29B44A)   // 기준보다 쌈 (초록)
    val neutral = Color(0xFFBDC3C7)

    // 연결 상태
    val statusOnline = Color(0xFF2ECC71)
    val statusConnecting = Color(0xFFF39C12)
    val statusError = Color(0xFFE74C3C)

    // 기준 표시
    val referenceBorder = Color(0xFF7EB3FF)
}
```

---

## WebSocket 연결 관리

### 서비스 활성화 패턴 (구독자 전용)

> **핵심**: WebSocketService는 `isActive` 플래그로 구독자 전용 연결을 보장합니다.
> 비구독자는 어떤 경로로도 WebSocket 연결이 불가능합니다.

```kotlin
class WebSocketService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val networkMonitor: NetworkMonitor
) {
    private var webSocket: WebSocket? = null
    private var isActive = false
    private var isIntentionalDisconnect = true

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _ratesFlow = MutableSharedFlow<List<ExchangeRate>>()
    val ratesFlow: SharedFlow<List<ExchangeRate>> = _ratesFlow.asSharedFlow()

    private val _graphBucketsFlow = MutableSharedFlow<Map<String, Map<String, GraphBucket>>>()
    val graphBucketsFlow: SharedFlow<Map<String, Map<String, GraphBucket>>> = _graphBucketsFlow.asSharedFlow()

    /** 서비스 활성화 및 연결 시작 (구독자 전용) */
    fun start() {
        if (isActive) return
        isActive = true
        isIntentionalDisconnect = false
        connect()
    }

    /** 서비스 비활성화 및 연결 종료 (비구독 전환 또는 로그아웃 시) */
    fun stop() {
        isActive = false
        disconnect()
    }

    /** WebSocket 연결 시작 */
    fun connect() {
        // 비활성 상태면 연결 차단 (비구독자 방어)
        if (!isActive) return
        if (_connectionState.value == ConnectionState.Connected ||
            _connectionState.value == ConnectionState.Connecting) return

        // 네트워크 연결 확인
        if (!networkMonitor.isConnected.value) {
            _connectionState.value = ConnectionState.Failed("네트워크 연결 없음")
            return
        }

        // ... 연결 로직
    }
}
```

### isActive 가드 적용 위치

| 연결 경로 | 가드 코드 |
|----------|----------|
| `connect()` | `if (!isActive) return` |
| `reconnect()` | `if (!isActive) return` |
| Foreground 복귀 | `if (!isActive \|\| isIntentionalDisconnect) return` |
| 네트워크 복구 | `if (isActive && !isIntentionalDisconnect && ...)` |
| Ping/Pong 타임아웃 | `if (!isActive) return` |
| `handleConnectionError()` | `if (!isActive \|\| isIntentionalDisconnect) return` |

### 재연결 전략

```kotlin
private suspend fun handleConnectionError(error: Throwable) {
    // 비활성 상태 또는 의도적 종료 시 재연결 차단 (비구독자 방어)
    if (!isActive || isIntentionalDisconnect) return

    cleanup()

    // 네트워크 없으면 즉시 실패 (재연결 시도 안 함)
    if (!networkMonitor.isConnected.value) {
        _connectionState.value = ConnectionState.Failed("네트워크 연결 없음")
        return
    }

    if (reconnectAttempts < WebSocketConfig.MAX_RECONNECT_ATTEMPTS) {
        reconnectAttempts++
        _connectionState.value = ConnectionState.Reconnecting(reconnectAttempts)

        // 지수 백오프: 2초 × 시도횟수 + 지터 (±20%)
        val baseDelay = WebSocketConfig.BASE_RECONNECT_DELAY_MS * reconnectAttempts
        val jitter = (baseDelay * Random.nextDouble(-0.2, 0.2)).toLong()
        val delay = baseDelay + jitter

        delay(delay)

        if (isActive && !isIntentionalDisconnect && networkMonitor.isConnected.value) {
            connect()
        }
    } else {
        _connectionState.value = ConnectionState.Failed("연결할 수 없습니다")
    }
}
```

### 앱 라이프사이클 처리

```kotlin
// ViewModel 또는 Service에서
private fun observeAppLifecycle() {
    ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
        private var backgroundEnteredAt: Long = 0

        override fun onStart(owner: LifecycleOwner) {
            // Foreground 복귀
            if (!isActive || isIntentionalDisconnect) return

            val duration = System.currentTimeMillis() - backgroundEnteredAt

            when {
                duration > 30_000 -> reconnect()  // 30초 이상 → 무조건 재연결
                !connectionState.value.isConnected -> reconnect()  // 연결 끊김 → 재연결
                else -> verifyConnectionWithPing()  // 연결 상태 → ping으로 확인
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            // Background 진입
            backgroundEnteredAt = System.currentTimeMillis()
            saveGraphCacheToFile()  // 그래프 캐시 저장
        }
    })
}
```

---

## 그래프 데이터 관리

### 캐시 유효성 검사 (`hasValidCache`, 1d 전용)

- 모든 실시간 소스(investing, kb, hana)가 **144개 이상**
- 버킷 간 **15분 이상 갭(중간 누락)이 없어야** 유효
- 백엔드가 항상 3소스를 제공한다는 전제

### 갭 감지 및 복구 전략

**1d (실시간)**:

| 상황 | 동작 |
|------|------|
| WebSocket 갭 (활성 통화) | 즉시 REST 복구 |
| WebSocket 갭 (비활성 통화) | `currenciesNeedingRefresh` 플래그 저장 → 탭 전환 시 강제 REST |
| 탭 전환 시 갭 플래그 | 30초 쿨다운 후 강제 REST (폭주 방지) |
| 캐시 무효 (개수/연속성) | 120초 쿨다운 존중하며 REST 시도 |
| **캐시 유효 + 15분 이상 오래됨** | **120초 쿨다운 존중하며 REST 시도** |
| 캐시 유효 + 15분 이내 | 스킵 (WebSocket으로 실시간 업데이트) |

**1w/3m/1y (장기)**:

| 상황 | 동작 |
|------|------|
| 캐시 없음 | REST 로드 |
| 캐시 있음 + TTL 만료 | 즉시 표시 → 백그라운드 REST 갱신 |
| 캐시 있음 + TTL 유효 | 캐시 사용 (REST 요청 없음) |
| 갭 임계값 | 1w: 2시간, 3m/1y: 1일 (`GraphConfig.gapThresholdSec(period)`) |

### 주기적 갭 체크 (5분마다)

```kotlin
// GraphRepository - 5분마다 활성 통화의 그래프 갭 체크 (Lazy 로딩)
private fun setupPeriodicRefresh() {
    scope.launch {
        while (isActive) {
            delay(GraphConfig.REFRESH_INTERVAL_SEC * 1000L)  // 5분
            if (this@GraphRepository.isActive) {
                loadGraph(activeCurrency)
            }
        }
    }
}
```

### 디스크 캐시 TTL (기간별)

파일명 규칙:

- `1d`: `graph_cache_v1_{currency}.json`
- `1w/3m/1y`: `graph_cache_v1_{currency}_{period}.json`
- legacy `graph_cache_{currency}.json`은 읽은 뒤 v1로 승격 가능

TTL (`GraphConfig.cacheTtlMs(period)`):

| 기간 | TTL | 비고 |
|------|-----|------|
| 1d | 24시간 | WebSocket 실시간 보완 |
| 1w | 1시간 | 자주 갱신 |
| 3m | 6시간 | |
| 1y | 24시간 | |

### 캐시 구조

```kotlin
// 메모리 캐시
// - 1d: 통화별 실시간 버킷
// - 기간 그래프: "{period}_{currency}" 키 기반 period cache

// 디스크 캐시 (File-based JSON)
// filesDir/graph_cache_v1_usd-krw.json
// filesDir/graph_cache_v1_usd-krw_1w.json
// filesDir/graph_cache_v1_usd-krw_3m.json
// filesDir/graph_cache_v1_usd-krw_1y.json
```

---

## 네트워크 모니터링

```kotlin
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()

    private val _isConnected = MutableStateFlow(checkCurrentConnectivity())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectionType = MutableStateFlow(getConnectionType())
    val connectionType: StateFlow<ConnectionType> = _connectionType.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isConnected.value = true
            _connectionType.value = getConnectionType()
        }

        override fun onLost(network: Network) {
            _isConnected.value = false
            _connectionType.value = ConnectionType.NONE
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager?.registerNetworkCallback(request, networkCallback)
    }

    private fun checkCurrentConnectivity(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    enum class ConnectionType { WIFI, CELLULAR, ETHERNET, NONE }
}
```

---

## 인증 시스템

### 인증 흐름

```
앱 시작 → Firebase Auth 상태 확인
       ↓
   .signedIn? → RevenueCat logIn(uid)
       ↓
   isPremium? → MainScreen (서버 연결)
       ↓
   !isPremium → LockedPreviewScreen (서버 요청 없음)
```

### AuthViewModel

```kotlin
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val subscriptionManager: SubscriptionManager
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        auth.addAuthStateListener { firebaseAuth ->
            viewModelScope.launch {
                val user = firebaseAuth.currentUser
                if (user != null) {
                    // RevenueCat 로그인 (성공/실패 콜백에서 후속 처리)
                    Purchases.sharedInstance.logIn(
                        appUserID = user.uid,
                        onError = { error ->
                            Log.e("Auth", "RevenueCat login failed: $error")
                            viewModelScope.launch { subscriptionManager.onAuthCompleted() }
                        },
                        onSuccess = { _, created ->
                            Log.d("Auth", "RevenueCat login success, created=$created")
                            viewModelScope.launch { subscriptionManager.onAuthCompleted() }
                        }
                    )

                    val providerId = user.providerData.firstOrNull { it.providerId != "firebase" }?.providerId
                    val provider = when (providerId) {
                        "apple.com" -> AuthProvider.APPLE
                        else -> AuthProvider.GOOGLE
                    }

                    _authState.value = AuthState.SignedIn(
                        UserInfo(
                            uid = user.uid,
                            email = user.email,
                            displayName = user.displayName,
                            photoUrl = user.photoUrl?.toString(),
                            provider = provider
                        )
                    )
                } else {
                    // ⚠️ 익명 사용자 상태에서 logOut() 호출 시 에러 발생
                    if (!Purchases.sharedInstance.isAnonymous) {
                        try {
                            Purchases.sharedInstance.logOut(
                                onError = { error -> Log.e("Auth", "RevenueCat logout failed: $error") },
                                onSuccess = { Log.d("Auth", "RevenueCat logout success") }
                            )
                        } catch (e: Exception) {
                            Log.e("Auth", "RevenueCat logout failed", e)
                        }
                    }
                    subscriptionManager.onAuthSignedOut()
                    _authState.value = AuthState.SignedOut
                }
            }
        }
    }

    fun signInWithGoogle(activity: Activity) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Google Sign-In 로직
            } catch (e: Exception) {
                // 에러 처리
            } finally {
                _isLoading.value = false
            }
        }
    }

    // TODO: Apple Sign-In (OAuth 웹 플로우) 진입 로직 추가

    fun signOut() {
        viewModelScope.launch {
            // FCM 토큰 해제
            // TODO: PushNotificationManager 구현 필요
            PushNotificationManager.unregisterDeviceFromServer()
            // Firebase 로그아웃
            auth.signOut()
        }
    }
}

// TODO: PushNotificationManager - FCM 토큰 서버 등록/해제 관리 싱글톤
```

---

## 구독 시스템 (RevenueCat)

### SubscriptionManager

```kotlin
@Singleton
class SubscriptionManager @Inject constructor() {

    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _isLoadingInitial = MutableStateFlow(true)
    val isLoadingInitial: StateFlow<Boolean> = _isLoadingInitial.asStateFlow()

    private val _isEligibleForIntro = MutableStateFlow(false)
    val isEligibleForIntro: StateFlow<Boolean> = _isEligibleForIntro.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        // CustomerInfo 업데이트 구독 (SDK 버전에 따라 Flow 또는 Listener)
        // Option A: Flow (SDK 제공 시, 예: customerInfoStream / customerInfoFlow)
        // scope.launch {
        //     Purchases.sharedInstance.customerInfoStream.collect { info ->
        //         _isPremium.value = info.entitlements["premium"]?.isActive == true
        //     }
        // }

        // Option B: Listener (호환성 높음)
        Purchases.sharedInstance.updatedCustomerInfoListener = UpdatedCustomerInfoListener { info ->
            _isPremium.value = info.entitlements["premium"]?.isActive == true
        }
    }

    suspend fun onAuthCompleted() {
        try {
            // getCustomerInfo() callback을 suspend로 래핑
            val info = Purchases.sharedInstance.getCustomerInfoSuspend()
            _isPremium.value = info.entitlements["premium"]?.isActive == true

            // 프리미엄 + 알림 권한 이미 승인 → 푸시 토큰 재등록
            // Note: PushNotificationManager.rehydratePushTokenIfNeeded() 구현 필요
            if (_isPremium.value) {
                // TODO: rehydratePushTokenIfNeeded() 구현
            }
        } catch (e: Exception) {
            Log.e("Subscription", "Failed to load subscription", e)
        }
        _isLoadingInitial.value = false
    }

    fun onAuthSignedOut() {
        _isPremium.value = false
        _isLoadingInitial.value = true
        _isEligibleForIntro.value = false
    }

    /**
     * 구매 수행
     * Note: RevenueCat Android SDK는 콜백 기반 purchase()를 제공
     */
    suspend fun purchase(activity: Activity, packageToPurchase: Package): Boolean {
        return try {
            val info = Purchases.sharedInstance.purchaseSuspend(
                PurchaseParams.Builder(activity, packageToPurchase).build()
            )
            _isPremium.value = info.entitlements["premium"]?.isActive == true
            true
        } catch (e: PurchasesException) {
            if (e.code != PurchasesErrorCode.PurchaseCancelledError) {
                Log.e("Subscription", "Purchase failed", e)
            }
            false
        }
    }
}

/**
 * RevenueCat suspend extensions (SDK 버전에 따라 직접 구현 필요)
 * Note: Android SDK 버전에 따라 API 시그니처가 다를 수 있으니 공식 문서 확인 필요
 */
// NOTE: 아래는 기본 형태 예시 (필요 시 수정)
suspend fun Purchases.getCustomerInfoSuspend(): CustomerInfo =
    suspendCancellableCoroutine { cont ->
        getCustomerInfo(
            onError = { cont.resumeWithException(PurchasesException(it)) },
            onSuccess = { cont.resume(it) }
        )
    }

suspend fun Purchases.purchaseSuspend(params: PurchaseParams): CustomerInfo =
    suspendCancellableCoroutine { cont ->
        purchase(
            params,
            onError = { cont.resumeWithException(PurchasesException(it)) },
            onSuccess = { customerInfo, _ ->
                cont.resume(customerInfo) // TODO: SDK 시그니처에 맞게 파라미터 순서 확인
            }
        )
    }
```

### RevenueCat 설정

```kotlin
// Application.onCreate()
Purchases.configure(
    PurchasesConfiguration.Builder(this, "goog_your_public_api_key")
        .build()
)
```

---

## 비구독자 서버 요청 정책

> **핵심 원칙**: 비구독자(LockedPreviewScreen)는 FXi 서버에 어떤 요청도 하지 않습니다.
> **예외**: `GET /api/news`는 인증 불필요 공개 API로, 비구독자도 요청 허용.

### 정책 범위

```
🔒 FXi 서버 (비구독자 차단)
├─ WebSocket 연결 (실시간 환율)
├─ Graph REST API (기간별 그래프)
├─ Alert Settings API (알림 CRUD)
└─ FCM 토큰 서버 등록 (푸시 알림)

✅ FXi 서버 (비구독자 예외 허용)
└─ GET /api/news (뉴스 조회, 인증 불필요)

✅ 외부 서비스 (비구독자도 허용)
├─ Firebase Auth (로그인/로그아웃)
├─ RevenueCat (구독 상태 조회/결제)
└─ Google/Apple Sign-In (소셜 로그인)
```

### 구현 가드

| 영역 | 가드 위치 | 조건 |
|------|----------|------|
| WebSocket 연결 | `WebSocketService.isActive` | `start()` 호출 시에만 `true` |
| Graph API | `GraphViewModel` 내부 활성 가드 | `start()` 호출 시에만 활성 |
| Alert API | MainScreen에서만 로드 | `isPremium` 체크 |
| 토큰 서버 등록 | `FXiMessagingService` | `Auth + isPremium + shouldRegisterForPush` |

---

## 환율 알림 기능

### 알림 동작 방식 (1-shot)

```
사용자: "1,400원 이하 도달 시 알림" 설정
    ↓
[환율이 1,399원 도달]
    ↓
서버: FCM 푸시 발송 + triggered=true, isEnabled=false 설정
    ↓
앱: 로컬 상태 즉시 업데이트
    ↓
[알림 1회성 완료] → 재활성화하려면 수동 토글 필요
```

### FCM Service

```kotlin
@AndroidEntryPoint
class FXiMessagingService : FirebaseMessagingService() {

    @Inject lateinit var pushNotificationManager: PushNotificationManager
    @Inject lateinit var subscriptionManager: SubscriptionManager
    @Inject lateinit var alertEventBus: AlertEventBus

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        serviceScope.launch {
            pushNotificationManager.onNewToken(token, subscriptionManager.isPremium.value)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        when (message.data["type"]) {
            "rate_alert" -> {
                val settingId = message.data["setting_id"]?.toIntOrNull()
                if (settingId != null) {
                    alertEventBus.emit(AlertEvent.SettingTriggered(settingId))
                } else {
                    alertEventBus.emit(AlertEvent.RefreshNeeded)
                }
                showRateAlertNotification(message)
            }
            "sync_alerts" -> {
                // 다중 기기 동기화: 사일런트 새로고침 (알림 표시 없음)
                alertEventBus.emit(AlertEvent.RefreshNeeded)
            }
        }
    }
}
```

---

## 통화별 표시 규칙

| 통화 | API 반환 단위 | 표시 형식 | 예시 |
|------|-------------|----------|------|
| **USD/KRW** | 1달러당 | 그대로 표시 | `1,407.50원` |
| **EUR/KRW** | 1유로당 | 그대로 표시 | `1,520.30원` |
| **JPY/KRW** | **100엔당** | 라벨 추가 | `949.50원 (100엔)` |

> **중요**: 백엔드 API는 JPY를 **이미 100엔당 환율**로 반환합니다.
> 클라이언트에서 스케일링(×100) 불필요. 라벨만 추가.

```kotlin
fun formatRate(rate: Double, currency: String): String {
    val formatted = NumberFormat.getInstance().apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(rate)

    return if (currency == SupportedCurrency.JPY_KRW.code) {
        "${formatted}원 (100엔)"
    } else {
        "${formatted}원"
    }
}
```

---

## 차트 구현 (Compose Canvas)

> **구현 위치**: `ui/components/RateGraphView.kt`

### 차트 모드

- **1일 + 단일 소스**: close 라인 + min/max band
- **1일 + 다중 소스**: 실시간 소스 비교 라인
- **1주/3달/1년**: `reference` 중심 기간 그래프
- **USD/KRW**: 필요 시 `dxy` 오버레이와 좌측 축 동시 표시

### 주요 규칙

- 기간별 X축 포맷 분기: `1d`, `1w`, `3m`, `1y`
- `1d`만 소스 토글 허용, 장기 구간은 `reference` 중심
- Live Tail은 렌더 단계에서 최신 환율/DXY 값을 우측 끝에 덧붙여 표현
- DXY는 별도 스케일을 사용하되 환율 차트에 정규화 오버레이

### X축 레이블

```kotlin
// 자정(00시)에는 날짜 표시, 그 외에는 시간만
fun formatXAxisLabel(epochSeconds: Long): String {
    val instant = Instant.fromEpochSeconds(epochSeconds)
    val dateTime = instant.toLocalDateTime(TimeZone.of("Asia/Seoul"))

    return if (dateTime.hour == 0) {
        "${dateTime.monthNumber}/${dateTime.dayOfMonth}"  // 예: 12/7
    } else {
        String.format("%02d", dateTime.hour)  // 예: 03, 06, 09...
    }
}
```

---

## 계정 삭제

> **중요**: 삭제 순서가 잘못되면 데이터 정합성/UX 문제 발생 가능.
> 원격 작업(서버/Firebase)이 모두 성공한 후에만 로컬 데이터를 정리해야 함.

### 삭제 흐름 (iOS와 동일)

1. **재인증** (필요 시 Google/Apple 재인증)
2. **서버 데이터 삭제**: `DELETE /api/user/me`
3. **RevenueCat 로그아웃** (best-effort)
4. **Firebase Auth 계정 삭제**
5. **로컬 데이터 정리** (캐시/FCM 토큰) ← 마지막에 수행

```kotlin
suspend fun deleteAccount(): Result<Unit> {
    val user = Firebase.auth.currentUser ?: return Result.failure(Exception("Not signed in"))

    // 1. 재인증 (Firebase 삭제 시 requires-recent-login 에러 방지)
    //    서버 삭제 전에 재인증해야 토큰이 유효함
    try {
        val providerId = user.providerData.firstOrNull { it.providerId != "firebase" }?.providerId
        val credential = when (providerId) {
            "google.com" -> getGoogleCredential()
            "apple.com" -> getAppleCredential()
            else -> null
        }
        if (credential != null) {
            user.reauthenticate(credential).await()
        }
    } catch (e: Exception) {
        // 최근 로그인이면 재인증 불필요할 수 있음
        Log.w("AccountDeletion", "Reauthentication skipped or failed: ${e.message}")
    }

    // 2. 서버 데이터 삭제 (실패 시 전체 중단)
    try {
        apiService.deleteUser()
    } catch (e: Exception) {
        Log.e("AccountDeletion", "Server deletion failed", e)
        return Result.failure(e)
    }

    // 3. RevenueCat 로그아웃 (best-effort, 데이터 삭제 아님)
    if (!Purchases.sharedInstance.isAnonymous) {
        try {
            Purchases.sharedInstance.logOut(
                onError = { Log.w("AccountDeletion", "RevenueCat logout failed: $it") },
                onSuccess = { Log.d("AccountDeletion", "RevenueCat logout success") }
            )
        } catch (e: Exception) {
            Log.w("AccountDeletion", "RevenueCat logout failed", e)
        }
    }

    // 4. Firebase Auth 계정 삭제
    try {
        user.delete().await()
    } catch (e: FirebaseAuthRecentLoginRequiredException) {
        // 재인증 후 재시도
        val providerId = user.providerData.firstOrNull { it.providerId != "firebase" }?.providerId
        val credential = when (providerId) {
            "google.com" -> getGoogleCredential()
            "apple.com" -> getAppleCredential()
            else -> null
        }
        if (credential != null) {
            user.reauthenticate(credential).await()
            user.delete().await()
        } else {
            return Result.failure(e)
        }
    }

    // 5. 로컬 데이터 정리 (모든 원격 작업 성공 후)
    cacheService.clearAllCache()
    pushNotificationManager.clearLocalStorage()

    return Result.success(Unit)
}

/**
 * Google 재인증 Credential 획득
 * Note: 실제 구현은 Google Sign-In 플로우에 따라 다름
 */
private suspend fun getGoogleCredential(): AuthCredential {
    // TODO: Google Sign-In으로 credential 획득
    // val googleSignInClient = GoogleSignIn.getClient(context, gso)
    // val account = googleSignInClient.silentSignIn().await()
    // return GoogleAuthProvider.getCredential(account.idToken, null)
    throw NotImplementedError("Google reauthentication flow 구현 필요")
}

/**
 * Apple 재인증 Credential 획득 (OAuth 웹 플로우)
 * Note: Firebase OAuthProvider("apple.com") 사용
 */
private suspend fun getAppleCredential(): AuthCredential {
    // TODO: OAuthProvider로 Apple 재인증 credential 획득
    throw NotImplementedError("Apple reauthentication flow 구현 필요")
}
```

### 오류 처리

| 오류 | 처리 |
|------|------|
| 401 (서버) | 재인증 후 재시도 |
| 503 (서버) | 서버 일시 오류 안내 |
| FirebaseAuthRecentLoginRequiredException | Google 재인증 후 재시도 |

---

## 테스트 참고사항

### 주말/야간 테스트

- 주말에는 환율 변동이 없어 WebSocket 메시지가 10초마다 오더라도 동일한 데이터
- Ping/Pong 메커니즘으로 연결 상태 확인

### 오프라인 테스트

1. 비행기 모드 활성화
2. 앱 실행 → 캐시 데이터로 오프라인 모드 표시
3. 네트워크 복구 → 자동 재연결 + 최신 데이터 로드

### RevenueCat Sandbox

- Google Play Console에서 라이선스 테스트 계정 등록 필요
- 테스트 구독은 빠르게 갱신됨 (월간 → 5분)

---

## 바 너비 계산 로직

```kotlin
/**
 * 웹/iOS 버전과 동일한 바 너비 계산 로직
 */
fun calculateBarWidth(rate: Double, minRate: Double, maxRate: Double): Float {
    val rateRange = maxRate - minRate
    if (rateRange == 0.0) return 0.75f

    val normalized = (rate - minRate) / rateRange

    return when {
        rateRange >= 4 -> 0.35f + normalized.toFloat() * 0.45f   // 35% ~ 80%
        rateRange >= 3 -> 0.40f + normalized.toFloat() * 0.40f   // 40% ~ 80%
        rateRange >= 2 -> 0.45f + normalized.toFloat() * 0.35f   // 45% ~ 80%
        rateRange >= 1 -> 0.50f + normalized.toFloat() * 0.30f   // 50% ~ 80%
        rateRange >= 0.6 -> 0.55f + normalized.toFloat() * 0.25f // 55% ~ 80%
        rateRange > 0.3 -> 0.60f + normalized.toFloat() * 0.18f  // 60% ~ 78%
        else -> 0.65f + normalized.toFloat() * 0.12f             // 65% ~ 77%
    }
}
```

---

## 백엔드 참조

> API 동작이 불명확할 때 백엔드 소스 직접 참조:
> 경로: `../exchange-rate/` (android/ 기준)

| 참조 목적 | 파일 | 주요 내용 |
|----------|------|----------|
| WebSocket 브로드캐스트 | `app/main.py` | `broadcast_rates_once()`, `build_graph_buckets()` |
| REST API 엔드포인트 | `app/main.py` | `/api/rates`, `/api/graph/{currency}` |
| 그래프 캐시 로직 | `app/admin/graph_cache.py` | 10분 버킷 집계, carry-forward |
| 알림 API | `app/main.py` | `/api/notification-settings` |

---

## 개인정보처리방침

> **상세 내용**: `../ios/privacy-policy.html` (iOS/Android 공용)

- **앱명**: 환율알림
- **시행일**: 2026-01-07

### 수집 항목

| 구분 | 항목 |
|------|------|
| **필수** | UID, 이메일 (Apple/Google 로그인) |
| **선택** | 이름/닉네임, 프로필 사진 URL (Google만) |
| **기능 이용 시** | FCM 토큰, 환율 알림 설정 |
| **자동 수집** | 충돌 로그, Crashlytics Installation UUID, Firebase Installation ID, 앱 버전, 기기 정보, 서비스 접속 기록 |

### 보유 기간

| 항목 | 기간 |
|------|------|
| 회원 정보 | 탈퇴 시까지 |
| 충돌 로그 | 최대 90일 (Crashlytics 정책) |
| 서버 접속 기록 | 최대 10일 |

### 국외 이전

| 이전받는 자 | 이전 국가 | 목적 |
|------------|----------|------|
| Google LLC (Firebase) | 미국 | 회원 인증, 푸시 알림, 충돌 분석 |
| Apple Inc. | 미국 | Apple 로그인, 인앱 결제 |
| RevenueCat, Inc. | 미국 | 구독 결제 처리 |

### 위탁 (국내)

| 수탁업체 | 위탁 업무 |
|---------|----------|
| Amazon Web Services Korea LLC | 데이터 저장 및 서비스 호스팅 |

---

## Phase 2 TODO (향후 구현)

### 접근성 (Accessibility)

- [ ] TalkBack 지원
- [ ] 동적 폰트 크기
- [ ] 색상 대비 개선

### 위젯

- [ ] 홈 화면 위젯 (환율 표시)

### Wear OS

- [ ] Wear OS 앱 (환율 확인)

---

**최종 수정**: 2026-04-03
**Phase**: 1 - Android MVP (v1.2.0 뉴스 서비스 추가)
