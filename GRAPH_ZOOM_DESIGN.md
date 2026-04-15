# 그래프 핀치 줌/팬 추가 — 권장 설계안 v1.0 (Android)

> **상태**: 확정 (v1.0, 2026-04-11)
> **플랫폼**: Android (Jetpack Compose Canvas 자체 구현). 공통 UX 목표는 iOS와 동일, 플랫폼 구현 경로는 다름. iOS 설계는 `../ios/GRAPH_ZOOM_DESIGN.md` 참조.
> **스코프**: [app/src/main/java/com/jay/fxi/ui/components/RateGraphView.kt](app/src/main/java/com/jay/fxi/ui/components/RateGraphView.kt) (본화면/예시화면 공용)
> **목적**: 현재 전체화면 토글만 가능한 환율 그래프에 손동작 확대/축소 + 좌우 이동 기능 추가
> **구현 여부**: 본 문서는 설계만 다룸. 착수 시점은 별도 지시.

---

## 0. 공통 UX 목표 (iOS와 동일)

- 핀치 줌으로 visible 구간 확대/축소
- 팬(드래그)으로 좌우 이동
- 더블탭으로 기본 보기 리셋
- 1d / 1w / 3m / 1y 모든 기간에서 동일 인터랙션
- DXY 이중 Y축 그래프에서도 정합성 유지
- 기간 변경 시 줌 리셋

> **iOS와 차이**: iOS는 Swift Charts의 `chartScrollableAxes` 같은 네이티브 스크롤 API가 있어 "경로 A(네이티브) vs 경로 B(수동)" 선택지가 존재하지만, Android는 Compose Canvas 수동 렌더링이라 **수동 경로 단일**. iOS의 경로 B에 해당.

---

## 1. 목표 / 비목표

**목표**
- 위 §0의 공통 UX 목표 전부
- [LockedPreviewScreen.kt](app/src/main/java/com/jay/fxi/ui/subscription/LockedPreviewScreen.kt)의 예시 화면에도 **자동 반영** (공용 composable이라 별도 작업 불필요)

**비목표**
- 전체화면 모드 유지(오히려 정리 대상, §7)
- 핀치 줌 중심점을 손가락 위치에 정확히 고정(1차는 중앙 기준 OK)
- Y축 방향 줌
- rotation 제스처 (`detectTransformGestures`가 자동 포함하지만 무시)

---

## 2. 상태 모델

```kotlin
// RateGraphView composable 내부
var visibleLength by remember { mutableStateOf<Long?>(null) }   // null = 전체 폭(기본 보기), 단위: 초
var scrollAnchor by remember { mutableStateOf<Long?>(null) }    // null = 기본 위치, 단위: epoch 초
var isInteracting by remember { mutableStateOf(false) }         // 핀치/팬 제스처 진행 중
```

### 파생 규칙 (computed)

```kotlin
val isZoomedOrPanned: Boolean by remember {
    derivedStateOf {
        (visibleLength != null && visibleLength!! < totalLength) ||
        (scrollAnchor != null && abs(scrollAnchor!! - defaultAnchor) > epsilon)
    }
}
```

- 기본 보기 복귀 시 자동으로 false
- 잠금 판단에 이 파생값만 사용 (raw state 직접 참조 금지)

### 저장 정책 (1차)

- **A안 (composable 로컬 `remember`)** 채택: 통화/기간 변경 시 줌 리셋
- 장점: 단순, "줌은 일시적 탐색" 멘탈 모델과 일치
- 향후 줌 보존 요구 발생 시 [GraphViewModel.kt](app/src/main/java/com/jay/fxi/ui/viewmodel/GraphViewModel.kt)로 승격

---

## 3. 줌 한계

### 공식

```
minVisibleLength = max(N × bucketSize, labelCollisionMin)
```

- [GraphPeriod.kt](app/src/main/java/com/jay/fxi/domain/model/GraphPeriod.kt) enum에 `val minVisibleLength: Duration` / `val maxVisibleLength: Duration` property 추가 → 매직 넘버 한 곳 집중

### bucketSize 확정값

> **확정일**: 2026-04-11
> **출처**: `../exchange-rate/app/main.py:1278-1283` `BUCKET_SIZE_LABELS` dict
> **iOS 문서와 동일값** — 두 플랫폼 UX 일치 보장

| period | bucketSize (확정) | 1차 minVisible (PoC 확정) | maxVisible |
|---|---|---|---|
| 1d | 10m | 1h (= 6 buckets) | 24h |
| 1w | 1h | 6h (= 6 buckets) | 7d |
| 3m | **1d** | 3d (= 3 buckets) | ~90d |
| 1y | **1d** | 7d (= 7 buckets) | 365d |

### 🔎 3m·1y 특이점

**3m과 1y는 둘 다 1일 버킷**입니다. 의미:

- 3m 뷰에서 1주일 단위로 줌인해도 일 단위 해상도가 최대. 10분/시간 단위 디테일은 **존재하지 않음** (서버가 안 내려줌)
- 1y도 동일. 1년 365개 데이터 포인트가 전부
- 따라서 3m/1y의 minVisible를 너무 작게 잡으면 "줌인은 되는데 볼 게 없는" UX가 됨 → 최소 3d/7d 정도가 현실적 하한
- UX 상 "1d 해상도 안내" 문구를 장기 뷰에서 보조 표시하는 것도 고려 대상 (PoC 이후 판단)

### ⚠️ minVisible 확정은 PoC 이후

bucketSize는 확정됐지만 `minVisible` 수치는 여전히 **1차 가설**. 값은 PoC 단계(특히 G3/G5)에서 다음을 보고 최종 확정:

1. 핀치 감도와 자연스러운 체감
2. X축 레이블 충돌 임계
3. 장기 뷰에서 "볼 게 없는" 구간 여부
4. iOS와 수치 일치 (크로스 플랫폼 UX 일관성)

### 기간 변경 시 정책

- 줌 리셋 (`visibleLength = null`, `scrollAnchor = null`)

---

## 4. 구현 경로 — Compose Canvas + `detectTransformGestures`

### 적용할 modifier

```kotlin
Modifier
    .pointerInput(Unit) {
        detectTransformGestures(
            panZoomLock = false,
        ) { _, pan, zoom, _ /* rotation, 무시 */ ->
            isInteracting = true
            // 줌 배율 → visibleLength 갱신 (minVisible..maxVisible 클램프)
            val newLength = ((visibleLength ?: totalLength) / zoom).toLong()
                .coerceIn(minVisibleLength, maxVisibleLength)
            visibleLength = if (newLength == totalLength) null else newLength

            // 팬 → scrollAnchor 갱신 (양 끝 clamp)
            val newAnchor = /* pan.x를 visible domain 단위로 환산 후 시프트 */
            scrollAnchor = newAnchor
        }
    }
    .pointerInput(Unit) {
        detectTapGestures(
            onDoubleTap = {
                // 기본 보기 리셋
                visibleLength = null
                scrollAnchor = null
                isInteracting = false
            }
        )
    }
```

### PoC 검증 게이트 (본 구현 착수 전 통과 필수)

| ID | 항목 | 통과 기준 |
|---|---|---|
| **G1** | HorizontalPager 수평 스와이프와의 gesture arbitration | 그래프 위에서 핀치/팬 동작 중 [MainScreen.kt:261](app/src/main/java/com/jay/fxi/ui/screen/MainScreen.kt#L261) HorizontalPager 스와이프가 의도대로 회피되거나 명확히 분리되는가 |
| **G2** | Visible domain 연쇄 재계산 정합성 | 확대 후 xMin/xMax, yMin/yMax, dxyMin/dxyMax, tick 생성이 **한 프레임 안에 일관되게** 갱신되는가 (※ 이 그래프의 가장 큰 잠재 디버깅 포인트) |
| **G3** | 핀치 줌 보간 | 줌 배율 변경이 부드럽게 보간, jitter 없음 |
| **G4** | WebSocket/주기 갱신 영향 격리 | 줌인 상태에서 새 데이터 도착 시 사용자 visible domain이 흔들리지 않음 (§6 잠금 동작 검증) |
| **G5** | 관성/fling 필요 여부 판정 | 관성 없이 손을 떼면 UX가 어색한지. 어색하면 `Animatable` + `splineBasedDecay`로 fling 추가 필요 — 이 판단 자체가 PoC의 산출물 |

### HorizontalPager 충돌 해결 전략 (G1 대응)

iOS의 `TabView(.page)`와 동일한 문제. Android는 다음 순으로 시도:

1. **`awaitPointerEventScope` 내에서 제스처를 먼저 잡고 `consume`** — 그래프 영역 포인터 이벤트를 Pager로 전파 차단. 한 손가락 탭(단일 탭 등)은 통과시킴.
2. **두 손가락 팬만 허용** — 한 손가락 드래그는 Pager에 양보. iOS 경로 B와 동일 전략. UX 희생 있음.
3. **확대 모드 토글 버튼** — 토글 ON일 때만 그래프가 제스처를 잡음. 가장 확실하지만 UX 손실 가장 큼.

1번이 실패하면 2번, 2번도 애매하면 3번으로 fallback.

---

## 5. 좌표계 재계산 지점

Canvas 기반이라 매 프레임 전체 재계산이므로 **수정점은 "visible domain을 반영한 필터/계산 함수로 입력만 바꾸면" 된다**. iOS보다 수정 위치 수는 적지만 연쇄 재계산 정합성은 같은 부담.

| 위치 | 현재 동작 | 수정 내용 |
|---|---|---|
| [RateGraphView.kt:161](app/src/main/java/com/jay/fxi/ui/components/RateGraphView.kt#L161) `RateGraphCanvas` | 전체 ratePoints 기반 렌더 | visibleDomain 내 포인트만 집계하여 xMin/xMax/yMin/yMax 재산출 |
| [RateGraphView.kt:373](app/src/main/java/com/jay/fxi/ui/components/RateGraphView.kt#L373) `computeXTicks` | period 기반 고정 간격 | visibleLength에 반비례하는 동적 간격 (예: 6h 미만 → 1h, 2h 미만 → 30m) |
| [RateGraphView.kt:579](app/src/main/java/com/jay/fxi/ui/components/RateGraphView.kt#L579) `computeDxyPath` | 전체 DXY 포인트 | visibleDomain 필터 적용 |
| [RateGraphView.kt:600](app/src/main/java/com/jay/fxi/ui/components/RateGraphView.kt#L600) `normalizeDxyValue` | 전체 dxyRange 기반 | visible 구간 dxyRange 기반으로 재계산 |
| DXY 좌측 Y축 레이블 (RateGraphView.kt 내 overlay 영역) | 전체 dxyRange 기반 step | 재계산된 dxyRange 기반 step 자동 갱신 |

### 연쇄 재계산 정합성 — 핵심 리스크

Canvas 좌표계가 한 벌이라 overlay/proxy 분리 이슈는 없지만, **visible domain이 바뀔 때마다 위 5개 계산이 하나도 어긋나지 않고 한 프레임에 갱신**되어야 함. 어긋나면 DXY 라벨이 선과 미묘하게 엇나가거나, tick이 엉뚱한 위치에 찍힘. 전부 **동일한 `visibleDomain` 값을 입력으로 받는 pure function**으로 정리하고, Compose `remember(visibleDomain, data)` 키에 일관되게 물리는 것이 방어책.

---

## 6. 데이터 수집 vs 표시 동기화 분리

### 용어 분리 (iOS와 동일 원칙)

- **데이터 수집 (줌 중에도 계속 동작)**
  - WebSocket 수신
  - [GraphViewModel](app/src/main/java/com/jay/fxi/ui/viewmodel/GraphViewModel.kt) 주기 갱신
  - 캐시 업데이트
  - Live tail 합성
  - **절대 멈추지 않는다**

- **표시 동기화 (잠금 대상)**
  - 새 데이터가 들어와도 차트 visible domain을 우측으로 자동 확장하지 않음
  - 사용자가 보고 있는 구간은 그대로 유지

### 잠금 조건

```kotlin
val freezeDomain = isInteracting || isZoomedOrPanned
```

### 해제 시 동작

사용자가 기본 보기로 복귀하는 순간 (`visibleLength = null`, `scrollAnchor = null`) 그동안 수집·합성된 최신 데이터가 즉시 반영. 누락 없음.

### 구현 힌트

Live tail append 자체는 막지 않되, 도메인 계산 함수가 `freezeDomain == true`일 때는 사용자의 `visibleLength`/`scrollAnchor`를 기준으로만 도메인을 산출. Live tail 데이터는 단지 "그 도메인 안에 들어와 있으면 그려질 뿐".

---

## 7. 제스처 정리 범위

| 위치 | 현재 동작 | 변경 |
|---|---|---|
| [CurrencyTabContent.kt:444](app/src/main/java/com/jay/fxi/ui/screen/CurrencyTabContent.kt#L444) 일반 모드 그래프 섹션 더블탭 | `setGraphFullscreen(true)` — 전체화면 **열기** | **삭제** (핀치/팬과 의도 충돌) |
| [CurrencyTabContent.kt:476](app/src/main/java/com/jay/fxi/ui/screen/CurrencyTabContent.kt#L476) `OpenInFull` 아이콘 `clickable` | `setGraphFullscreen(true)` — 전체화면 **열기** | **유지** — 전체화면 진입은 이 버튼으로 일원화 |
| [CurrencyTabContent.kt:298](app/src/main/java/com/jay/fxi/ui/screen/CurrencyTabContent.kt#L298) 전체화면 모드 내부 Box 더블탭 | `setGraphFullscreen(false)` — 전체화면 **닫기** | **용도 변경**: 핀치/팬 상태를 기본 보기로 리셋 (일반 모드에도 동일 적용) |
| [CurrencyTabContent.kt:275](app/src/main/java/com/jay/fxi/ui/screen/CurrencyTabContent.kt#L275) 전체화면 모드 내부 `Cancel` 아이콘 `clickable` | `setGraphFullscreen(false)` — 전체화면 **닫기** | **유지** (전체화면 닫기 버튼) |
| [GraphViewModel.kt:85](app/src/main/java/com/jay/fxi/ui/viewmodel/GraphViewModel.kt#L85) `setGraphFullscreen` | fullscreen 상태 토글 | 유지 (전체화면 기능 자체는 존속) |

---

## 8. LockedPreviewScreen 동기화

- [LockedPreviewScreen.kt:607](app/src/main/java/com/jay/fxi/ui/subscription/LockedPreviewScreen.kt#L607), [:1048](app/src/main/java/com/jay/fxi/ui/subscription/LockedPreviewScreen.kt#L1048)에서 **같은 `RateGraphView` composable을 재사용**하므로 **별도 작업 불필요**.
- iOS는 `SampleGraphView.swift`라는 복제 파일이 있어 양쪽 동기화가 필요하지만, Android는 **공용 composable 구조 덕분에 한 번의 수정으로 본화면/예시화면 동시 반영**. 설계상 장점.
- 단, [LockedPreviewScreen.kt:373](app/src/main/java/com/jay/fxi/ui/subscription/LockedPreviewScreen.kt#L373) 내부 HorizontalPager와의 G1 충돌도 동일하게 발생하므로 PoC는 **본화면과 예시화면 둘 다에서 검증** 필요.

---

## 9. 향후 검토

캐시 bucketSize 영속화 같은 플랫폼 공통 주제는 iOS 문서 `../ios/GRAPH_ZOOM_DESIGN.md` §9에 기록. Android에서도 같은 결정 사항이 적용되지만, **결정 시점에 양쪽 문서를 함께 갱신**.

---

## 10. 미해결 리스크

| # | 리스크 | 대응 |
|---|---|---|
| R1 | HorizontalPager 수평 스와이프와 `detectTransformGestures` 충돌 | PoC G1, 3단계 fallback 전략 (§4) |
| R2 | visible domain 연쇄 재계산 정합성 (xMin/xMax/yMin/yMax/dxyMin/dxyMax/tick) | PoC G2, `remember(visibleDomain)` 키 통일 |
| R3 | 줌 중 WebSocket 갱신이 도메인 흔듦 | `freezeDomain` 잠금 + PoC G4 |
| R4 | 1d 외 bucketSize 미검증 | §3 선행 작업 (iOS와 공유) |
| R5 | 관성/fling 필요 여부 미정 | PoC G5 산출물 |
| R6 | `detectTransformGestures`의 rotation 파라미터 오동작 시 UX 이상 | 테스트 단계에서 rotation 값 무시 명시 |
| R7 | LockedPreviewScreen 내부 HorizontalPager (§8)에서도 G1 재발 가능 | PoC 시 양쪽 화면 검증 |

---

## 11. 작업 순서

1. **백엔드 bucketSize 확정** (선행, iOS와 공유): `../exchange-rate/` 코드 또는 라이브 응답으로 period별 값 확정 + §3 표 업데이트
2. **PoC 브랜치**: §4 최소 구현 + G1~G5 검증 (반나절~1일)
3. **G1 결과에 따라 충돌 해결 전략 확정** (1번/2번/3번 중 하나)
4. **G5 결과에 따라 fling 구현 여부 확정**
5. **본 구현**: §5 재계산 지점 전부 반영
6. **제스처 정리**: §7 — 더블탭 용도 변경, 전체화면 진입 동선 정리
7. **본화면/LockedPreviewScreen 양쪽에서 수동 검증**
8. **iOS와 UX 일치 확인** (핀치 감도, minVisible 값 등)

---

## 부록 A. 참고 자료

- Compose Gestures: `detectTransformGestures`
  https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch
- Compose HorizontalPager gesture 공존 패턴
  https://developer.android.com/develop/ui/compose/layouts/pager
- Jay's Compose Canvas 그래프 현행 구현: [RateGraphView.kt](app/src/main/java/com/jay/fxi/ui/components/RateGraphView.kt)
