# 환율 알림 UI 구현 계획 (Android)
  
iOS `AlertSection / AlertAddSheet`를 Android Compose로 이식하는 작업 계획서입니다.  
FCM 인프라(이전 단계)가 실제로 사용되는 첫 UI 단계입니다.

---

## 목표

- 환율 알림 CRUD UI 구현
- FCM 이벤트(알림 트리거)를 UI 상태에 반영
- Android 13+ 런타임 권한 흐름 연결
- iOS와 동일한 UX/비즈니스 규칙 유지

---

## 구현 범위

1. AlertEventBus (FCM → UI 이벤트 브릿지)
2. AlertRepository (CRUD API 래핑)
3. AlertViewModel (상태 + 옵티미스틱 업데이트 + 권한 흐름)
4. AlertSection / AlertRow / AlertAddSheet (Compose UI)
5. FXiMessagingService 이벤트 발행
6. MainScreen / CurrencyTabContent 연결

---

## 파일 변경 목록

| 순서 | 파일 | 작업 |
|------|------|------|
| 1 | `service/AlertEventBus.kt` | **신규**: SharedFlow 이벤트 버스 |
| 2 | `data/repository/AlertRepository.kt` | **신규**: 알림 CRUD |
| 3 | `ui/viewmodel/AlertViewModel.kt` | **신규**: 상태 관리 |
| 4 | `ui/alert/AlertSection.kt` | **신규**: 섹션 UI |
| 5 | `ui/alert/AlertRow.kt` | **신규**: 알림 행 |
| 6 | `ui/alert/AlertAddSheet.kt` | **신규**: 추가/편집 Sheet |
| 7 | `service/FXiMessagingService.kt` | **수정**: 이벤트 발행 |
| 8 | `ui/screen/MainScreen.kt` | **수정**: AlertViewModel 주입 |
| 9 | `ui/screen/CurrencyTabContent.kt` | **수정**: AlertSection 추가 |
| 10 | `ui/screen/RootScreen.kt` | **수정**: 로그아웃/구독해지 시 `alertViewModel.reset()` 호출 |

---

## 데이터 / 이벤트 흐름

1. FCM 수신 → `AlertEventBus.emit(...)`
2. `AlertViewModel`이 `AlertEventBus.events` 구독
3. `SettingTriggered(id)` → 로컬 상태에서 `triggered=true`, `isEnabled=false`
4. `RefreshNeeded` → 전체 목록 재로드

> 이벤트 버스는 **프로세스 생존 시**에만 동작하는 브릿지입니다.  
> 앱이 재실행된 경우에는 ViewModel `loadSettings()`로 다시 동기화.

---

## AlertEventBus

```kotlin
@Singleton
class AlertEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 10)
    val events: SharedFlow<AlertEvent> = _events.asSharedFlow()
    fun emit(event: AlertEvent) { _events.tryEmit(event) }
}

sealed class AlertEvent {
    data class SettingTriggered(val settingId: Int) : AlertEvent()
    object RefreshNeeded : AlertEvent()
}
```

---

## AlertRepository

- FXiApiService 호출 래핑
- auth.currentUser == null이면 즉시 실패
- 모든 메서드는 `Result<T>` 반환
- 예외 매핑:
  - 401/403 → "인증 오류"
  - 503 → "구독 확인 중"
  - 그 외 → "서버 오류"
  - 네트워크 → "네트워크 오류"

---

## AlertViewModel

### 상태

```kotlin
sealed class AlertState {
    object Idle : AlertState()
    object Loading : AlertState()
    data class Loaded(val settings: List<AlertSetting>) : AlertState()
    data class Error(val message: String) : AlertState()
}
```

### 핵심 동작

- `loadSettings()` / `loadSettingsIfNeeded()` (30초 디바운스)
  - `lastRefreshAt` 타임스탬프 추적
  - `state is Loading`이면 중복 호출 차단
- `createSetting()`:
  - 첫 생성 시 권한 요청 → 승인 시 `shouldRegisterForPush=true`
  - 승인 후 `registerIfNeeded(isPremium)` 호출
- `toggleSetting()` / `deleteSetting()` 옵티미스틱 처리
- `handleSettingTriggered(id)` 로컬 상태 업데이트
- `canAddMore` / `remainingCount` (최대 30개)
- `reset()` 로그아웃/구독 해지 시 상태 초기화

---

## 권한 흐름 (POST_NOTIFICATIONS)

- Android 13+에서만 런타임 권한 요청
  - 승인 → `shouldRegisterForPush=true` → `registerIfNeeded(isPremium)`
  - 거부 → 생성 취소
- Android 12 이하 → 항상 허용으로 처리

권한 요청은 **AlertAddSheet 저장 시점**에 수행.

---

## AlertSection UI

### 상태 분기

| 상태 | UI |
|------|----|
| Loading | 스피너 + 로딩 텍스트 |
| Error | 에러 + 재시도 |
| 권한 없음 + 빈 목록 | 권한 CTA |
| 권한 없음 + 목록 있음 | 경고 배너 + 목록 |
| 권한 있음 + 빈 목록 | 빈 상태 + 추가 버튼 |
| 권한 있음 + 목록 있음 | 목록 + 추가 버튼 |

### 기타
- 접이식 섹션 헤더 (🔔 아이콘 + 활성 개수 뱃지 + chevron)
- `canAddMore`가 false면 추가 버튼 숨김
- `remainingCount <= 3` 시 남은 개수 안내
- AlertAddSheet에서 현재 환율 계산을 위해 `rates: List<ExchangeRate>` 전달 필요

---

## AlertRow UI

```
[은행명] [조건 텍스트] [토글] [삭제]
triggered 시: "알림 발송됨" 배지
```

- `isEnabled=false` → 콘텐츠 50% opacity
- 삭제는 항상 100% opacity
- 탭 → 편집 Sheet

---

## AlertAddSheet UI

### 레이아웃 개요

```
[취소]  알림 설정  [저장]
은행 선택 (5x2)
환율 입력 + 조건 토글
현재 환율 / 유효 범위
활성화 토글
안내 배너(3개)
```

### 입력/검증 규칙

- 은행 그리드: 10개 (investing 포함)
- 입력 키보드: Decimal
- 유효 범위: **선택된 은행의 현재 환율** ±50% (10단위 반올림)
- `AlertAddSheet`에 `rates: List<ExchangeRate>` 전달 필요 (bank+currency 매칭)
- 은행 변경 시 현재 환율 기준으로 threshold 자동 업데이트 (추가 모드만)
- 현재 환율보다 높으면 `PositiveColor`, 낮으면 `NegativeColor`
- 중복 방지: 동일 bank + condition + threshold 존재 시 저장 불가
- 편집 모드: 변경 없으면 저장 비활성
- triggered 알림도 편집 가능. 토글 ON/PUT 시 서버가 `triggered=false`로 리셋 → 서버 응답을 그대로 반영

---

## FXiMessagingService 수정

- `AlertEventBus` 주입 후 이벤트 발행
- `setting_id` 파싱 성공 → `SettingTriggered`
- 파싱 실패 → `RefreshNeeded`

---

## MainScreen / CurrencyTabContent 연결

- `MainScreen`: `AlertViewModel` 생성 후 하위로 전달
- `CurrencyTabContent`: 환율 리스트 아래 `AlertSection` 추가
  - `AlertSection(currency, viewModel, rates)` 형태로 전달
- **비구독자 MainScreen 진입 자체가 불가**하므로 AlertSection은 자동으로 premium만 표시됨

---

## RootScreen 연동

- auth→signedOut / premium→false 전환 시 `alertViewModel.reset()` 호출
- 로그아웃/구독해지 시 Alert 상태를 `Idle`로 초기화

---

## 주의사항 (필수)

- **도메인 레이어에 Compose 의존성 금지**
- **JPY 표시 규칙 유지**: 숫자 스케일링 금지, 표기만 "원 (100엔)"
- **중복 API 호출 방지**: ViewModel에서 로딩/디바운스 제어
- **최대 30개 제한**: 생성 UI에서 반드시 확인
- **권한 흐름**: Android 13+만 런타임 요청

---

## 검증

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:assembleDebug
```
