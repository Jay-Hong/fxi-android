# Android 그래프 줌/팬 — iOS v2.5 parity 포팅 로드맵 v2.0 (Android)

> **상태**: 계획 확정 (2026-04-14) — PoC v1.0 baseline 커밋 직후
> **플랫폼**: Android (Jetpack Compose Canvas 자체 구현).
> **크로스 플랫폼 기준**: iOS v2.5 (`../ios/GRAPH_ZOOM_DESIGN.md`) 완전 parity 목표.
> **스코프**: [app/src/main/java/com/jay/fxi/ui/components/RateGraphView.kt](app/src/main/java/com/jay/fxi/ui/components/RateGraphView.kt) (본화면 + 예시화면 공용 composable)
> **이전 문서**: v1.0은 2026-04-11 Android 단독 설계 초안. PoC 구현이 baseline으로 커밋된 시점(`8a53808`)에 **본 문서가 이를 대체**.

---

## 0. 버전 이력 요약

| 버전 | 시점 | 내용 |
|---|---|---|
| v1.0 | 2026-04-11 | Android 단독 설계 초안 (Android 고유 목표 + "모든 기간 인터랙션") |
| **v2.0** | **2026-04-14** | **iOS v2.5 parity 로드맵으로 전면 개편.** 1d only 원칙 확정. M1~M5 마일스톤 정의. PoC v1 baseline은 커밋 `8a53808`로 고정. |

---

## 1. 목표 / 비목표

### 목표

- **iOS v2.5 완전 기능 parity** — 양 플랫폼 사용자가 같은 UX 경험
- **1d period 한정** 인터랙션 (핀치/팬/더블탭/follow-latest)
- Android 공용 composable 구조 활용 — 본화면 + LockedPreviewScreen 동시 반영 (iOS의 `SampleGraphView` 복제 불필요)
- HorizontalPager와 제스처 공존 (iOS의 `TabView(.page)` 등가 문제 해결)
- Canvas 수동 렌더링의 장점 활용 — iOS Swift Charts의 일부 고유 이슈(chartXScale 자동 보간, identity diff) 회피

### 비목표 (parity 후보)

- **1w/3m/1y 줌/팬** — 1d bucketSize(10m)가 줌의 유일한 실용 가치 구간. 1h/1d bucket 기간은 효용 작음. **post-parity 확장 후보**로만 메모
- Y축 방향 줌
- Fling/관성 (iOS에 없고 Android도 우선순위 낮음)
- Rotation 제스처 (detectTransformGestures 부작용 무시)

### 1d only 원칙 (고정)

iOS v2.5가 1d만 줌 활성인 이유는 Swift Charts의 제약에서 파생된 결정이 아니라 **bucketSize 실용 가치** 때문. Android도 동일 이유로 1d에만 적용. 이 원칙을 깨면 문서/상태 모델/제스처 정책이 period-agnostic으로 복잡해지며 parity 작업이 "확장 설계 분기"로 변질. **parity 완료 전까지 이 원칙을 번복하지 않음**.

---

## 2. 현재 상태 (PoC v1 baseline, 커밋 `8a53808`)

### 구현됨

- Canvas 기반 수동 렌더링 (`ChartLayout.mapX/mapY`, `drawLine/drawPath/drawText`)
- 4개 period (1d/1w/3m/1y) 정적 그래프
- Rate 선 (소스별) + DXY 선 + 단일 소스 min/max 밴드
- X축 tick (1d는 3h 고정, 자정 굵은 선)
- Y축 D3-like nice step
- **DXY visible 기반 정규화** + 좌측 라벨 컬럼
- Flat DXY 단일 라벨
- **clipRect** 기반 plot 내부 clipping — **iOS v2.3의 `chartPlotStyle { plot.clipped() }` 등가가 원래부터 존재** (Canvas 특성상 자연)
- HorizontalPager 충돌 회피: `awaitEachGesture` 수동 루프로 **2+ pointer pinch만 consume**

### v1 한계 (parity 대상)

- Pinch: **right-edge anchor** (finger-centered 아님)
- 1-finger pan **없음**
- 더블탭 줌 토글 **없음**
- Follow-latest 암시적 (항상 우측 edge) — 명시 플래그 없음
- `pocVisibleLengthSec` + 파생 `pocVisibleWindow: IntRange?` — **raw/resolved domain 분리 없음**
- Plot bounds guard **없음** (`plot.contains` 등가)
- Gesture y-lock **없음**
- DXY 경계 보간 **없음**
- DXY 라벨 0개 fallback **없음**
- Adaptive xTicks **없음** (1d 고정 3h)
- 30분 보조 grid **없음**
- 더블탭 애니메이션 **없음**
- `CurrencyTabContent.kt`의 wrapper 더블탭 → fullscreen 진입 **정리 안 됨** (iOS v2.2에서 제거한 정책 충돌)

---

## 3. iOS v2.5 ↔ Android v1 gap (기능 매핑)

| 기능 | iOS v2.5 | Android v1 | 포팅 필요 | 플랫폼 차이 |
|---|---|---|---|---|
| 2-finger pinch | finger-centered | right-edge anchor | 개선 | centroid 기반 |
| 1-finger pan (zoomed only) | ✅ | ❌ | 필요 | Pager 공존 체크 |
| 더블탭 줌 토글 (6h + reset) | ✅ | ❌ | 필요 | - |
| plot bounds guard | `plot.contains` | ❌ | 필요 | Canvas size 기반 |
| Follow-latest | 명시 flag + resolved domain | 암시적 (항상) | 명시화 | pan 추가 후 필요 |
| Adaptive xTicks | 1h/3h | 1d 고정 3h | 필요 | - |
| **30분 보조 grid (v2.4)** | 점선 dashed | ❌ | 필요 | `PathEffect.dashPathEffect` |
| **Gesture y-lock (v2.3)** | ✅ | ❌ | 필요 | - |
| Rate 전체 렌더 + clipping | v2.3에서 추가 | **v1부터 존재** | - | **Canvas 자연 속성** |
| DXY visible-only 정규화 | ✅ | ✅ | - | - |
| **DXY 경계 보간 (v2.3)** | ✅ | ❌ | 필요 (보수적) | - |
| **DXY 라벨 0개 fallback** | ✅ | inset 기반 fallback 필요 | 필요 | - |
| **더블탭 애니메이션 (v2.5)** | 타협형 (X/Y 시차) | ❌ | 실험 | `Animatable` + coroutine |
| **Fullscreen 정책 (v2.2)** | wrapper 더블탭 제거 | 미정 | 필요 | `CurrencyTabContent.kt` |
| HorizontalPager 충돌 | N/A | 2+ pointer consume | - | 이미 해결 |

---

## 4. 포팅 마일스톤 (M1~M5)

### M1 — 문서 정렬 + 상태 모델 전환 + `computeChartState` 정리

**목표**: 기능 변화 없음. parity 작업을 위한 인프라 재편.

**작업**:
- [x] 본 문서(`GRAPH_ZOOM_DESIGN.md`) v1.0 → v2.0 재작성 (현재)
- [ ] 상태 모델 전환
  - `pocVisibleLengthSec: Long?` → `pocVisibleDomain: ClosedRange<Long>?` (free window, 양 경계 모두 제어)
  - `pocIsFollowingLatest: Boolean`
  - `resolvedVisibleDomain`: `derivedStateOf { ... }` — follow 반영 파생
  - 기존 PoC 동작 유지: right-edge anchor는 follow 상태의 자연 결과로 나옴
- [ ] `computeChartState` 함수 분해
  - `computeYRange(visibleRateBuckets)` pure function 분리
  - `computeDxyRange(visibleDxyBuckets, yRange)` pure function 분리 (yRange 정합성 패딩)
  - 나머지 `computeXTicks`, `computeRatePaths`, `computeDxyPath`는 **이미 분리됨** (baseline 상태)
- [ ] 빌드 + 리그레션 없음 확인 (PoC 동작 동일)

**커밋 전략**: 문서 1커밋 + 코드 1(~2)커밋

### M2 — Pinch/Pan + Pager arbitration (G1 검증)

**목표**: 핵심 제스처 완성 + HorizontalPager 공존 전략 최종 확정.

**작업**:
- Finger-centered pinch — centroid 기반 anchor time 계산
- 1-finger pan (**`pocIsZoomedOrPanned` 상태에서만** consume, 그 외 Pager로 pass through)
- Pager 충돌 검증 (G1 게이트)
  - iOS의 `UIPanGestureRecognizer.isEnabled` 토글과 등가 동작
  - 실패 시 fallback: v1.0 문서 §4의 "2-finger만 허용" 전략 유지

**성공 기준**:
- 기본 보기에서 통화 스와이프 정상 (Pager 양보)
- 줌된 상태에서 1-finger pan 정상
- 핀치 anchor가 손가락 위치 부근

### M3 — 더블탭 + Follow-latest 명시 + Fullscreen 정책 (v2.2 parity 완성)

**목표**: iOS v2.2 단계까지 기능 일치.

**작업**:
- **더블탭 줌 토글**: 6h window + reset
  - `detectTapGestures(onDoubleTap = ...)` 또는 수동 double tap 감지
  - plot bounds guard: Canvas size 내부 좌표만 처리
  - 줌 안 된 상태 + 탭 → 탭 위치 중심 6h 줌인
  - 줌 상태 + 탭 → reset
- **Follow-latest 명시화**
  - `pocIsFollowingLatest: Boolean` state
  - 제스처 ended 시 reevaluate (raw upperBound가 lastTs 근처면 follow=true)
- **Fullscreen 정책 정리** ([CurrencyTabContent.kt](app/src/main/java/com/jay/fxi/ui/screen/CurrencyTabContent.kt))
  - Wrapper 더블탭 → fullscreen 진입 **제거** (새 더블탭 정책과 충돌)
  - Fullscreen 진입은 [OpenInFull 아이콘 버튼](app/src/main/java/com/jay/fxi/ui/screen/CurrencyTabContent.kt) 유지
  - Fullscreen 내부 제스처 — v1.0 문서 §7 이식 (iOS 패턴과 같음)

### M4 — DXY 정합성 + 30분 보조 grid (v2.3/v2.4 parity)

**목표**: 폴리시 단계.

**작업**:
- **DXY 경계 보간** (v2.3)
  - `interpolateDxyBoundaryPoint` 등가 — visible 양 경계에 interpolated 가상 `GraphBucket` 삽입
  - 수직 상승 아티팩트 없음 (visible 밖 원본 버킷은 절대 렌더하지 않음)
- **DXY 라벨 0개 fallback** (v2.3)
  - 현재 `generateDxyLabels`는 `inset = span * 0.05` 보수적 로직이라 iOS보다 덜 민감하지만, parity 위해 추가 fallback 점검
- **Gesture y-lock** (v2.3)
  - Pan/pinch began에서 현재 yRange/dxyRange 캡처
  - ended에서 `Animatable` 또는 직접 해제
  - DXY 정규화 매핑 고정 → 애니메이션/보간 중 선 진동 방지
- **Rate 전체 렌더 + clipping** — 이미 `clipRect`로 자연 존재, skip
- **Adaptive xTicks + 30분 보조 grid** (v2.4)
  - `pocAdaptiveHourInterval` 등가 (1h/3h)
  - `generateHalfHourGrids` 등가 — minute == 30 위치만
  - `XTick`에 `isHalfHour: Boolean` 필드 + Canvas draw 시 `PathEffect.dashPathEffect` 점선
  - `isMidnight` 조건에 `minute == 0` 명시화 (M4 이전엔 tautological)

### M5 — 더블탭 애니메이션 (v2.5 실험, **옵션**)

**목표**: iOS v2.5 타협형 parity **또는 더 깔끔한 해결**.

**배경**:
- iOS v2.5는 더블탭 줌인/리셋에 `withAnimation(.easeOut(0.25))` + follow 재평가 in-block + yLock 지연 해제를 적용했지만, **X/Y 축 시차** + **DXY 미세 진동 일부 잔존**으로 **타협형**으로 기록.
- 원인 추정: Swift Charts의 chartXScale 보간이 chartYScale과 독립적으로 타이밍 어긋남.

**Android 가설**:
- Canvas 수동 렌더링은 **state 변화를 한 프레임에 정직하게 반영** → chartXScale/chartYScale 독립 보간 개념 자체가 없음
- `Animatable<Long>` 두 개(visibleLength, scrollAnchor)로 **완전 동기 보간** 가능할 수 있음
- 성공 시 **iOS보다 깔끔한 결과**

**작업** (M4 완료 후 착수 판단):
- `Animatable<Long>` 2개로 visibleDomain 상태 보간
- Coroutine `Job`으로 cancel 경로 관리 (iOS Task 등가)
- `DisposableEffect { onDispose { job?.cancel() } }` — iOS `onDisappear` 등가
- **실패 시 원상 복귀** (순수 실험)
- 결과가 iOS 타협형보다 나으면 iOS 문서에 역참조 메모

**의사결정**: M1~M4 완료 + 기기 검증 후에만 M5 착수 여부 재판단.

---

## 5. 상태 모델 상세 (M1 이후)

### View state (composable `remember`)

```kotlin
// Raw user input (single source of truth)
var pocVisibleDomain by remember { mutableStateOf<ClosedRange<Long>?>(null) }
    // null = 기본 보기 (전체 + right-edge natural follow)
    // non-null = 사용자가 명시적으로 설정한 visible window (초)

var pocIsFollowingLatest by remember { mutableStateOf(true) }
    // 기본 true — 새 데이터가 들어오면 자동 슬라이드

// Gesture baselines (제스처 .began에서 캡처)
var pocPinchBaselineDomain: ClosedRange<Long>? by remember { mutableStateOf(null) }
var pocPinchAnchorTimeSec: Long? by remember { mutableStateOf(null) }
var pocPinchAnchorFraction: Float by remember { mutableStateOf(0.5f) }
var pocPanBaselineDomain: ClosedRange<Long>? by remember { mutableStateOf(null) }

// Gesture y-lock (M4에서 추가)
var pocGestureYLock: GestureYLock? by remember { mutableStateOf(null) }

// M5 애니메이션 release task (옵션)
var pocGestureYLockReleaseJob: Job? by remember { mutableStateOf(null) }
```

### 파생 state

```kotlin
val pocIsZoomedOrPanned: Boolean by remember {
    derivedStateOf { pocVisibleDomain != null }
}

val resolvedVisibleDomain: ClosedRange<Long>? by remember(
    pocVisibleDomain, pocIsFollowingLatest, lastDataTs
) {
    derivedStateOf {
        val raw = pocVisibleDomain ?: return@derivedStateOf null
        if (pocIsFollowingLatest && lastDataTs != null) {
            val length = raw.endInclusive - raw.start
            (lastDataTs - length)..lastDataTs
        } else raw
    }
}
```

### 계산 단위 (pure function)

모두 `remember(resolvedVisibleDomain, data, ...)` 키로 통일 — **한 프레임 정합성 보장**.

- `visibleRateBuckets(data, domain)` — filter만
- `visibleDxyBuckets(data, domain)` — filter만
- `computeYRange(visibleRate)` — 패딩 포함 (M1)
- `computeDxyRange(visibleDxy, yRange)` — yRange 정합성 (M1)
- `computeXTicks(visibleLength, domain, period)` — adaptive (M4)
- `computeRatePaths(layout, buckets)` — 이미 분리
- `computeDxyPath(layout, buckets, ranges)` — 이미 분리
- `interpolateDxyBoundaryPoint(boundary, allDxy)` — M4 신규

---

## 6. Pager arbitration 전략 (G1)

### 현재 (baseline) — 2+ pointer consume

```kotlin
awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false)
    do {
        val event = awaitPointerEvent()
        if (event.changes.count { it.pressed } >= 2) {
            // pinch만 consume
        }
    } while (event.changes.any { it.pressed })
}
```

### M2 목표 — 1-finger pan 조건부 consume

- 1-finger 드래그가 발생할 때 **`pocIsZoomedOrPanned`가 true이면 consume** (pan 처리)
- false이면 consume 하지 않음 → Pager로 전파 (통화 스와이프)
- 이는 iOS의 `UIPanGestureRecognizer.isEnabled = pocIsZoomedOrPanned` 토글과 **개념적 등가**

### Fallback 전략 (v1.0 문서 §4 계승)

G1 실패 시:
1. 1-finger pan 포기 → 2-finger pan(핀치 후 두 손가락 drag)
2. 확대 모드 토글 버튼 — 가장 확실하지만 UX 손실

---

## 7. 검증 체크리스트 (마일스톤별)

### M1 리그레션 체크

- 기존 PoC pinch zoom 여전히 작동
- 기간 전환 시 리셋 동작
- yRange 재계산 정합성 (기존과 동일)

### M2 제스처 체크

- 기본 보기 + 좌우 스와이프 → Pager 페이지 전환
- 줌 상태 + 좌우 드래그 → 그래프 pan
- 핀치 anchor ≈ 손가락 위치
- 통화 3개 (달러/엔/유로) 모두 정상

### M3 v2.2 parity 체크

- 더블탭 줌인 (탭 위치 중심 6h)
- 더블탭 reset
- plot 밖 더블탭 → no-op
- Follow-latest: 우측 edge 근처 pan 해제 시 자동 follow 재진입
- 과거 pan → follow freeze
- Fullscreen 버튼으로 진입 / 버튼으로 해제 / wrapper 더블탭 무효

### M4 v2.3/v2.4 parity 체크

- 강한 줌(≤ 2.5h)에서 30분 보조 grid 점선
- DXY 선 plot 경계까지 이어짐 (경계 보간 효과)
- Pan/pinch 중 DXY 진동 없음 (y-lock 효과)

### M5 v2.5 실험 체크 (옵션)

- 더블탭 줌인/리셋 애니메이션 자연스러움
- Swift Charts 타협(X/Y 시차, DXY 미세 진동)이 Android에서는 어떤 양상인지 비교 기록

---

## 8. 공용 composable 이점 (iOS 대비)

iOS는 본화면 `RateGraphView` + 예시화면 `SampleGraphView` **복제본**이 별도 존재. 양쪽 동기화에 추가 작업 필요했음 (v2.2~v2.5 각 마일스톤마다 복사).

**Android는 [RateGraphView.kt](app/src/main/java/com/jay/fxi/ui/components/RateGraphView.kt) 단일 composable을 LockedPreviewScreen에서 재사용**. 한 번의 수정으로 본화면 + 예시화면 자동 반영. 포팅 작업량이 iOS 대비 **~1.5배 절약**됨.

단, [LockedPreviewScreen.kt](app/src/main/java/com/jay/fxi/ui/subscription/LockedPreviewScreen.kt) 내부 HorizontalPager도 Pager arbitration 대상이라 G1 검증은 **양쪽 화면에서 수행**.

---

## 9. 알려진 한계 / post-parity 후보

| 항목 | 상태 | 우선순위 |
|---|---|---|
| 1w/3m/1y 줌/팬 | parity에서 제외 | post-parity 후보. bucketSize가 1h~1d라 효용 작음 |
| plotFrame 갱신 트리거 보강 | iOS에도 후순위 | post-parity |
| 예시화면 follow-latest 자동 슬라이드 | iOS에 부재 (SampleData 정적) | 공용 composable 구조라 Android에서도 동일 한계 |
| xTicks 15m/10m 세분화 | 30m까지만 계획 | post-parity |

---

## 10. 참고 자료

- iOS 기준 문서: [`../ios/GRAPH_ZOOM_DESIGN.md`](../ios/GRAPH_ZOOM_DESIGN.md) v2.5
- Compose Gestures: https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch
- HorizontalPager gesture 공존: https://developer.android.com/develop/ui/compose/layouts/pager
- Compose Animatable: https://developer.android.com/jetpack/compose/animation/value-based

---

## 11. 작업 이력

```
2026-04-11  v1.0 Android 단독 설계 초안
            "모든 기간 동일 인터랙션" 전제 (후에 1d only로 정정)
2026-04-14  PoC v1 구현 (pinch-only, 1d only, right-edge anchor)
            커밋 8a53808 — "graph zoom PoC baseline"로 dirty 상태 고정
2026-04-14  v2.0 문서 재작성
            iOS v2.5 parity 로드맵으로 전면 개편
            1d only 원칙 확정, M1~M5 마일스톤 정의
```

git 커밋 (Android):

- `8a53808` — feat(android): graph zoom PoC baseline (v1.0 design + pinch-only 1d PoC)
- **current HEAD** (이후 M1 docs commit으로 갱신)
