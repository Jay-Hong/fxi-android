# 다중 기기 계정 삭제 대응 구현 가이드

> **목적**: A 기기에서 계정 삭제 시 B 기기에서 적절히 처리
> **스코프**: MVP 이후 기술부채
> **상태**: 📋 설계 완료, 구현 대기
> **삭제 시점**: 구현 완료 및 검증 후

---

## 1. 문제 정의

```
A 기기: 계정 삭제 (DELETE /api/user/me → Firebase Auth 삭제)
    ↓
B 기기: 최대 1시간까지 토큰 유효 → 고아 데이터 생성 가능
```

**현재 상태:**
- Firebase ID Token은 삭제 후에도 최대 1시간 유효
- B 기기에서 Write API 호출 시 고아 데이터 생성 위험
- Push 기반 강제 로그아웃은 전달 신뢰성 문제 (특히 iOS)

---

## 2. 솔루션 우선순위

| 순위 | 방안 | 목적 | 필수 여부 |
|------|------|------|----------|
| **1** | 서버: `check_revoked=True` | 고아 데이터 방지 (보안) | ✅ 필수 |
| **2** | 클라이언트: 401 → signOut() | 즉시 로그아웃 (UX) | ✅ 필수 |
| **3** | force_logout 푸시 | 선제적 로그아웃 (UX 개선) | ❌ 선택 |

---

## 3. 서버 구현 가이드

### 3.1 check_revoked=True 적용 대상

| 엔드포인트 | 메서드 | 적용 | 이유 |
|-----------|--------|------|------|
| `/api/register-device` | POST | ✅ 필수 | 고아 토큰 생성 방지 |
| `/api/notification-settings` | POST | ✅ 필수 | 고아 알림 설정 방지 |
| `/api/notification-settings/{id}` | PUT | ✅ 필수 | 고아 데이터 수정 방지 |
| `/api/notification-settings` | GET | ⚡ 선택 | 즉시 로그아웃 UX |
| `/api/register-device` | DELETE | ❌ 유지 | 정리 작업 (best-effort) |
| `/api/notification-settings/{id}` | DELETE | ❌ 유지 | 정리 작업 |
| `/api/user/me` | DELETE | ✅ 이미 적용 | - |

### 3.2 verify_firebase_token() 에러 처리 강화

**파일**: `exchange-rate/app/main.py`

```python
from firebase_admin import auth
from google.auth.exceptions import TransportError

async def verify_firebase_token(request: Request, check_revoked: bool = False) -> str:
    """
    Firebase ID Token 검증 및 user_id 추출

    Args:
        check_revoked: True면 revoke된 토큰 차단 (Write API에 권장)

    예외 매핑 정책:
    - 인증 실패 (토큰 문제): 401 → 클라이언트에서 강제 로그아웃
    - 인프라 오류 (Firebase 장애): 503 → 재시도 유도, 로그아웃 X
    """
    auth_header = request.headers.get("Authorization")
    if not auth_header or not auth_header.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing or invalid Authorization header")

    token = auth_header.split(" ")[1]

    try:
        decoded_token = auth.verify_id_token(token, check_revoked=check_revoked)
        uid = decoded_token["uid"]

        # (선택) 사용자 존재 여부 명시적 검증
        # Note: verify_id_token은 UserNotFoundError를 항상 발생시키지 않을 수 있음
        # 확실한 검증이 필요하면 아래 주석 해제
        # try:
        #     auth.get_user(uid)
        # except auth.UserNotFoundError:
        #     raise HTTPException(status_code=401, detail="User not found")

        return uid

    # === 인증 실패 (401) - 클라이언트에서 강제 로그아웃 ===
    except auth.RevokedIdTokenError:
        # 의도: 삭제된 계정의 토큰 차단
        logger.info("Revoked token rejected", extra={"event": "revoked_token"})
        raise HTTPException(status_code=401, detail="Token revoked")

    except auth.UserDisabledError:
        # 의도: 비활성화된 계정 차단
        logger.info("Disabled user rejected", extra={"event": "disabled_user"})
        raise HTTPException(status_code=401, detail="User disabled")

    except auth.InvalidIdTokenError as e:
        logger.warning("Invalid token", extra={"event": "invalid_token", "error": str(e)})
        raise HTTPException(status_code=401, detail="Invalid token")

    except auth.ExpiredIdTokenError:
        logger.info("Expired token", extra={"event": "expired_token"})
        raise HTTPException(status_code=401, detail="Token expired")

    # === 인프라 오류 (503) - 재시도 유도, 강제 로그아웃 X ===
    except auth.CertificateFetchError as e:
        # Firebase 인증서 가져오기 실패 (네트워크/Firebase 장애)
        logger.error("Firebase certificate fetch failed", exc_info=True)
        raise HTTPException(status_code=503, detail="Authentication service temporarily unavailable")

    except TransportError as e:
        # Google Auth 네트워크 전송 오류
        logger.error("Firebase transport error", exc_info=True)
        raise HTTPException(status_code=503, detail="Authentication service temporarily unavailable")

    except Exception as e:
        # 알 수 없는 오류 - 503으로 처리 (401로 하면 무고한 사용자 강제 로그아웃)
        # 디버깅을 위해 예외 타입과 컨텍스트 명시
        logger.error(
            "Token verification failed unexpectedly",
            extra={
                "event": "token_verification_error",
                "exception_type": type(e).__name__,
                "exception_module": type(e).__module__,
                "error": str(e),
            },
            exc_info=True
        )
        raise HTTPException(status_code=503, detail="Authentication service error")
```

### 3.3 엔드포인트 수정 위치

```python
# POST /api/register-device (~line 1200)
user_id = await verify_firebase_token(request, check_revoked=True)

# POST /api/notification-settings (~line 1284)
user_id = await verify_firebase_token(request, check_revoked=True)

# PUT /api/notification-settings/{id} (~line 1377)
user_id = await verify_firebase_token(request, check_revoked=True)

# (선택) GET /api/notification-settings (~line 1337)
# UX용 즉시 로그아웃 원하면 적용
user_id = await verify_firebase_token(request, check_revoked=True)
```

### 3.4 DELETE 엔드포인트 주석 명시

```python
# DELETE /api/register-device (~line 1246)
# check_revoked=False 의도적 유지:
# - 정리 작업이므로 고아 데이터 생성 불가
# - 삭제된 계정의 토큰도 best-effort로 정리
user_id = await verify_firebase_token(request)

# DELETE /api/notification-settings/{id} (~line 1423)
# 동일 이유: 정리 작업
user_id = await verify_firebase_token(request)
```

---

## 4. 클라이언트 구현 가이드

### 4.1 Android: SessionManager + Interceptor

#### SessionManager (싱글톤)

**파일**: `app/src/main/java/com/jay/fxi/auth/SessionManager.kt`

```kotlin
/**
 * 401 강제 로그아웃 전용 매니저
 *
 * Note: AuthState 관리는 기존 AuthViewModel에서 담당.
 * SessionManager는 401 처리 + 중복 방지만 담당.
 */
@Singleton
class SessionManager @Inject constructor(
    private val auth: FirebaseAuth,
    private val pushNotificationManager: PushNotificationManager,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val isSigningOut = AtomicBoolean(false)

    init {
        // isSigningOut 플래그 해제 전용 (AuthState 관리 X)
        auth.addAuthStateListener { firebaseAuth ->
            if (firebaseAuth.currentUser == null) {
                // SignedOut 전이 완료 후 플래그 해제 (연속 401 중복 방지)
                isSigningOut.set(false)
            }
        }
    }

    /**
     * 401 수신 시 호출 - 중복 호출 방지 + 단발 처리
     *
     * Note: finally에서 플래그 해제 X → AuthStateListener에서 해제
     */
    fun handleUnauthorized() {
        if (isSigningOut.compareAndSet(false, true)) {
            appScope.launch {
                try {
                    // FCM 토큰 해제 (best-effort)
                    try {
                        pushNotificationManager.unregisterDeviceFromServer()
                    } catch (e: Exception) {
                        Log.w(TAG, "FCM unregister failed (ignored)", e)
                    }

                    // Firebase 로그아웃 (AuthStateListener가 플래그 해제)
                    auth.signOut()

                } catch (e: Exception) {
                    Log.e(TAG, "Forced signOut failed", e)
                    // 실패 시에만 여기서 해제 (AuthStateListener 호출 안 됨)
                    isSigningOut.set(false)
                }
            }
        }
    }

    companion object {
        private const val TAG = "SessionManager"
    }
}
```

#### ApplicationScope 제공 (Hilt)

**파일**: `app/src/main/java/com/jay/fxi/di/AppModule.kt`

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
```

#### 401 처리 Interceptor

**파일**: `app/src/main/java/com/jay/fxi/data/api/UnauthorizedInterceptor.kt`

```kotlin
/**
 * 401 응답 시 강제 로그아웃 처리
 *
 * Note: 기존 provideAuthInterceptor(토큰 주입)와 별도 인터셉터
 */
class UnauthorizedInterceptor @Inject constructor(
    private val sessionManager: SessionManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        if (response.code == 401) {
            // SessionManager가 단발 처리 보장
            sessionManager.handleUnauthorized()
        }

        return response
    }
}
```

#### NetworkModule 인터셉터 순서

**파일**: `app/src/main/java/com/jay/fxi/di/NetworkModule.kt`

```kotlin
@Provides
@Singleton
@Named("authInterceptor")
fun provideAuthInterceptor(auth: FirebaseAuth): Interceptor = Interceptor { chain ->
    // 기존 토큰 주입 로직 유지
    val request = chain.request()
    val token = try {
        auth.currentUser?.let { Tasks.await(it.getIdToken(false)).token }
    } catch (_: Exception) { null }

    val newRequest = if (token != null) {
        request.newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .build()
    } else {
        request
    }
    chain.proceed(newRequest)
}

@Provides
@Singleton
fun provideOkHttpClient(
    @Named("authInterceptor") authInterceptor: Interceptor,  // 1. Bearer 토큰 주입
    unauthorizedInterceptor: UnauthorizedInterceptor          // 2. 401 처리
): OkHttpClient {
    return OkHttpClient.Builder()
        .addInterceptor(authInterceptor)         // 요청 전: 토큰 주입
        .addInterceptor(unauthorizedInterceptor) // 응답 후: 401 처리
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                        redactHeader("Authorization")
                    }
                )
            }
        }
        .build()
}
```

### 4.2 iOS: AuthService 확장 (현재 아키텍처 유지)

현재 프로젝트는 `@Observable` + `AuthService` 싱글톤 패턴을 사용.
기존 AuthService에 401 처리 로직 추가.

**파일**: `FXi/Services/AuthService.swift`

```swift
// AuthService.swift 기존 코드에 추가

@MainActor
@Observable
final class AuthService: NSObject {
    // 기존 코드 유지...

    // === 401 강제 로그아웃 추가 ===

    private var isForceSigningOut = false

    /// 401 수신 시 호출 - 중복 호출 방지
    /// Note: 기존 signOut() 재사용 (FCM 해제 + Firebase + Google 로그아웃 포함)
    func handleUnauthorized() {
        guard !isForceSigningOut else { return }
        isForceSigningOut = true

        Task {
            do {
                // 기존 signOut() 재사용 (정리 로직 포함: FCM 해제 + Google signOut)
                try await self.signOut()
                // AuthStateListener가 플래그 해제
            } catch {
                print("[AuthService] Forced signOut failed: \(error)")
                isForceSigningOut = false  // 실패 시에만 여기서 해제
            }
        }
    }

    // 기존 setupAuthStateListener()에 플래그 해제 1줄 추가
    // 위치: else 분기의 self.authState = .signedOut 직후
    private func setupAuthStateListener() {
        authStateHandle = Auth.auth().addStateDidChangeListener { [weak self] _, user in
            guard let self = self else { return }
            Task { @MainActor in
                if let user = user {
                    // 기존 SignedIn 처리 로직 (RevenueCat 로그인, authState 업데이트 등)
                } else {
                    // 기존 SignedOut 처리 로직 (RevenueCat 로그아웃, SubscriptionManager 초기화)

                    self.authState = .signedOut

                    // ⬇️ 이 1줄만 추가: 강제 로그아웃 플래그 해제
                    self.isForceSigningOut = false
                }
            }
        }
    }
}
```

**API Client에서 401 처리:**

```swift
// NetworkService.swift 또는 각 Service에서
func handleResponse(_ response: HTTPURLResponse) async {
    if response.statusCode == 401 {
        await AuthService.shared.handleUnauthorized()
    }
}
```

---

## 5. 검증 기준

| # | 시나리오 | 예상 동작 | 확인 방법 |
|---|----------|----------|----------|
| 1 | A기기 계정 삭제 → B기기 Write API | 401 응답 + 즉시 로그아웃 | B기기에서 알림 추가 시도 |
| 2 | 401 연속 발생 | signOut 1회만 실행 | 로그 확인 (중복 호출 없음) |
| 3 | 삭제된 계정 토큰으로 DELETE 요청 | 200 응답 (정리 허용) | 기기 해제 API 테스트 |
| 4 | Firebase 일시 장애 | 503 응답 (로그아웃 X) | 네트워크 차단 테스트 |
| 5 | check_revoked 성능 | 응답 지연 50ms 이내 | 부하 테스트 |

---

## 6. 코드 리뷰 체크리스트

### 서버
- [ ] verify_firebase_token에 check_revoked 파라미터 추가
- [ ] RevokedIdTokenError, UserDisabledError → 401
- [ ] CertificateFetchError, TransportError → 503 (강제 로그아웃 방지)
- [ ] Write API 3개 + (선택) GET 1개에 check_revoked=True 적용
- [ ] DELETE API 주석으로 의도 명시

### Android
- [ ] SessionManager: 401 처리 전용 (AuthState 관리는 기존 AuthViewModel)
- [ ] SessionManager @Singleton + @ApplicationScope + PushNotificationManager 주입
- [ ] AtomicBoolean 해제는 AuthStateListener에서 (finally 아님)
- [ ] UnauthorizedInterceptor 별도 클래스로 분리
- [ ] NetworkModule에서 @Named("authInterceptor")로 구분
- [ ] MainScope() 직접 생성 금지

### iOS
- [ ] AuthService에 handleUnauthorized() 추가
- [ ] 기존 signOut() 재사용 (FCM 해제 + Google signOut 포함)
- [ ] isForceSigningOut 플래그 해제는 AuthStateListener에서
- [ ] @Observable 패턴 유지 (ObservableObject 아님)

---

## 7. 참고 자료

- Firebase Admin SDK: [verify_id_token](https://firebase.google.com/docs/auth/admin/verify-id-tokens#verify_id_tokens_using_the_firebase_admin_sdk)
- check_revoked 옵션: 토큰 발급 후 revoke 여부 확인 (추가 네트워크 요청 발생)
- Firebase 예외 계층: `InvalidIdTokenError` > `RevokedIdTokenError`, `ExpiredIdTokenError`

---

**작성일**: 2026-02-06
**수정일**: 2026-02-06 (Codex 피드백 3차 반영 - 최종)
**삭제 예정**: 구현 완료 및 검증 후
