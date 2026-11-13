# FCM 푸시 알림 구현 계획

iOS PushNotificationService + DeviceService 패턴을 Android로 이식.

## 구현 범위

FCM 인프라 구현 (Alert UI는 별도 단계):

1. Auth 인터셉터 (Bearer 토큰)
2. API 엔드포인트 (register-device + notification-settings)
3. PushNotificationManager (Hilt @Singleton, 토큰 관리 + 서버 등록)
4. FXiMessagingService (FirebaseMessagingService)
5. AndroidManifest (FCM 서비스 + POST_NOTIFICATIONS 권한)
6. 알림 채널 + 알림 표시
7. 호출부 수정 (AuthViewModel, SubscriptionManager)

## 파일 변경 목록

| 파일 | 작업 |
|------|------|
| `di/NetworkModule.kt` | **수정**: AuthInterceptor 추가 + redactHeader |
| `data/remote/FXiApiService.kt` | **수정**: register-device + notification-settings 엔드포인트 |
| `data/remote/dto/DeviceRequest.kt` | **신규**: `@SerialName("device_token")`, `platform = "android"` |
| `data/remote/dto/AlertSettingRequest.kt` | **신규**: 알림 설정 요청 DTO |
| `service/PushNotificationManager.kt` | **수정**: object stub → Hilt @Singleton |
| `service/FXiMessagingService.kt` | **신규**: @AndroidEntryPoint + Hilt 주입 |
| `FXiApplication.kt` | **수정**: 알림 채널 생성 |
| `AndroidManifest.xml` | **수정**: FCM 서비스 + POST_NOTIFICATIONS 권한 |
| `ui/auth/AuthViewModel.kt` | **수정**: PushNotificationManager Hilt 주입 |
| `ui/screen/RootScreen.kt` | **수정**: PushNotificationManager 파라미터 수신 |
| `MainActivity.kt` | **수정**: PushNotificationManager @Inject → RootScreen 전달 |
| `subscription/SubscriptionManager.kt` | **수정**: PushNotificationManager Hilt 주입 (유일한 rehydrate 호출부) |

## 상세 설계

### Step 1: Auth 인터셉터

```kotlin
class AuthInterceptor @Inject constructor(
    private val auth: FirebaseAuth
) : Interceptor {
    override fun intercept(chain: Chain): Response {
        val request = chain.request()
        val token = try {
            auth.currentUser?.let { Tasks.await(it.getIdToken(false)).token }
        } catch (e: Exception) { null }

        return if (token != null) {
            chain.proceed(request.newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build())
        } else {
            chain.proceed(request)
        }
    }
}
```

- OkHttp Interceptor는 IO 스레드 → `Tasks.await()` 안전
- 토큰 없으면 헤더 없이 진행 (공개 API는 Bearer 불필요)
- **HttpLoggingInterceptor에 `redactHeader("Authorization")` 추가** (토큰 로그 노출 방지)

### Step 2: API 엔드포인트

```kotlin
@POST("api/register-device")
suspend fun registerDevice(@Body request: DeviceRequest): Response<Unit>

@DELETE("api/register-device")
suspend fun unregisterDevice(@Query("device_token") deviceToken: String): Response<Unit>

@GET("api/notification-settings")
suspend fun getNotificationSettings(): List<AlertSetting>

@POST("api/notification-settings")
suspend fun createNotificationSetting(@Body request: AlertSettingRequest): AlertSetting

@PUT("api/notification-settings/{id}")
suspend fun updateNotificationSetting(@Path("id") id: Int, @Body request: AlertSettingRequest): AlertSetting

@DELETE("api/notification-settings/{id}")
suspend fun deleteNotificationSetting(@Path("id") id: Int): Response<Unit>
```

### Step 3: PushNotificationManager (Hilt @Singleton)

```kotlin
@Singleton
class PushNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: FXiApiService,
    private val auth: FirebaseAuth
)
```

핵심 메서드:

- `onNewToken(token)`: 현재 owner로 등록 의도를 다시 구성한 뒤 알려진 token으로 coordinator 등록을 평가한다 (old token rotation, S7 E5b2a)
- `registerIfNeeded(isPremium)`: 등록 자격(아래 D21) 확인 → POST. savedToken == null이면 `Tasks.await(FirebaseMessaging.getInstance().token)`으로 토큰 확보. **내부 네트워크/토큰 획득은 Dispatchers.IO에서 수행.**
- `unregisterDeviceFromServer()`: DELETE + shouldRegisterForPush=false + savedToken 클리어
- `rehydratePushTokenIfNeeded()`: `reconcileRegistration(현재 owner)` 위임 (S7 E5b2a)
- `reconcileRegistration(owner)`: 의도 재구성 + coordinator 위임의 단일 facade. OS 거부면 의도 false(이 false만으로 서버 해제는 하지 않는다), 현재 세션·teardown 아님이면 그 세션의 서버 premium 확정 여부로 true/false, 그 밖은 저장값 유지. owner가 없거나 현재 세션이 아니면 coordinator에 넘기지 않는다

등록 자격(D21, S7 E5b2a): release gate ∧ 재구성된 `shouldRegisterForPush` ∧ OS 알림 capability(runtime 권한·앱 알림·`rate_alerts` 채널, 호출마다 새로 읽음) ∧ 그 세션의 서버 확정 premium(`confirmsPremiumFor(owner)`). 의도 값은 등록 성공 표지가 아니다. 옛 3중 가드(`auth.currentUser != null && isPremium && shouldRegisterForPush`)를 대체한다.

**호출 책임 정리**:

- `rehydratePushTokenIfNeeded()` → **SubscriptionManager.onAuthCompleted()에서만** 호출 (단일 책임)
- `reconcileRegistration(owner)` → `NotificationCapabilityMonitor`가 호출한다: alert-family registry의 foreground hook(첫 foreground 포함), premium publication(owner 바뀜 포함), 권한 helper의 결과(E5b2b). 진행 중 평가와 같은 관측은 합류한다
- `unregisterDeviceFromServer(owner)` → **AuthViewModel 로그아웃 실행자(`handedOffSignOut`)에서만** 호출한다. owner 는 로그아웃을 시작한 세션이다.
- ~~RootScreen은 상태 전환 감지 시 unregister 호출~~ — L-4b-2 4c(3b-2)에서 지웠다. 앱 로그아웃의 해제는 실행자가 하고, 외부 로그아웃은
  A 의 인증이 없어 DELETE 할 수 없으며, 늦게 도는 효과는 새 세션을 해제 대상으로 잡을 수 있었다. 등록·회전·해제의 현재 구조는
  `PushRegistrationCoordinator`(장부 위 직렬화)와 `ApiPushDeviceServer` 의 KDoc 에 있다. 위 메서드 목록은 v1 구현 당시의 기록이다.

### Step 4: FXiMessagingService

```kotlin
@AndroidEntryPoint
class FXiMessagingService : FirebaseMessagingService() {
    @Inject lateinit var pushNotificationManager: PushNotificationManager
    @Inject lateinit var subscriptionManager: SubscriptionManager

    override fun onNewToken(token: String) {
        serviceScope.launch {
            pushNotificationManager.onNewToken(token, subscriptionManager.isPremium.value)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "rate_alert") {
            showRateAlertNotification(message.data)
        }
    }
}
```

### Step 5: AndroidManifest

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".service.FXiMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

### Step 6: 알림 채널 (FXiApplication.kt)

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    val channel = NotificationChannel(
        "rate_alerts", "환율 알림", NotificationManager.IMPORTANCE_HIGH
    ).apply { description = "환율 목표 도달 시 알림" }
    getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
}
```

### Step 7: DTO 스펙

```kotlin
@Serializable
data class DeviceRequest(
    @SerialName("device_token") val deviceToken: String,
    val platform: String = "android"
)

@Serializable
data class AlertSettingRequest(
    val bank: String,
    val currency: String,
    val condition: AlertCondition,
    val threshold: Double,
    @SerialName("is_enabled") val isEnabled: Boolean = true
)
```

## 런타임 권한 (POST_NOTIFICATIONS)

이 단계에서는 manifest 선언 + 3중 가드만 구현.
실제 런타임 권한 요청은 Alert UI 단계에서:

- 알림 설정 첫 생성 시 권한 요청
- 승인 → shouldRegisterForPush=true → registerIfNeeded()
- 거부 → shouldRegisterForPush=false
- S7 E5b2a: 앱 복귀 때의 capability 재확인과 거부 시 의도 false는 `NotificationCapabilityMonitor`가 중앙 foreground hook에서 한다. 요청·설정 유도 UI는 E5b2b.

## 주의사항

- **알림 아이콘**: 임시로 `R.mipmap.ic_launcher` 사용. 릴리즈 전 단색/투명 전용 아이콘(`ic_stat_notification`) 교체 필요.
- **토큰 획득**: `kotlinx-coroutines-play-services` 미사용. `Tasks.await()` (IO 스레드) 또는 `suspendCancellableCoroutine` 래핑.

## 검증

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:assembleDebug
```
