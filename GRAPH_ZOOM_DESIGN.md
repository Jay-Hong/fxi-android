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

### M1 — 문서 정렬 + 상태 모델 전환 + `computeChartState` 정리 (완료)

**목표**: 기능 변화 없음. parity 작업을 위한 인프라 재편.

**완료 내역** (세부 커밋 해시는 §11 작업 이력 참조):

- **PoC baseline 고정** (`8a53808`) — dirty 상태(기존 pinch PoC + v1.0 설계)를 커밋으로 명시
- **v2.0 문서 재작성** (`05ee913`) — v1.0 Android 단독 설계 초안을 iOS v2.5 parity 로드맵으로 개편. 1d only 원칙 확정, M1~M5 마일스톤 정의, 공용 composable 이점 명시
- **상태 모델 전환** (`4814d98`):
  - `pocVisibleLengthSec: Long?` → `pocVisibleDomain: ClosedRange<Long>?` (raw, 양 경계 모두 제어)
  - `pocIsFollowingLatest: Boolean` (default true) 신규
  - `resolvedVisibleDomain: ClosedRange<Long>?` derived — follow 모드 반영된 실제 표시 domain (chart 렌더/필터/gesture baseline single source of truth)
  - `lastDataTs: Long?` 별도 캡처 — follow-latest anchor
  - 기존 right-edge anchor 동작은 follow=true 기본값의 자연 결과로 유지
- **`computeChartState` 함수 분해** (`4814d98`):
  - `computeYRange(visibleRate, visibleDxy) → YRange?` pure function 분리
  - `computeDxyRange(visibleDxy) → DxyRangeInfo?` pure function 분리
  - `computeChartState`는 thin orchestrator (visible filter → yRange → dxyRange → 조립)
  - 시그니처: `visibleWindow: IntRange?` → `visibleDomain: ClosedRange<Long>?`
  - 기존 분리 유지: `computeXTicks`, `computeRatePaths`, `computeDxyPath`
- **M1 이력 보정** (본 섹션의 이력 서술형 전환 포함) — §11 git 커밋 섹션에 `current HEAD` 표기 규칙 적용, 기존 체크박스를 완료 서술로 대체. self-reference 역설 회피를 위해 본 섹션 본문에서는 amend 대상 커밋 해시 참조 제거
- **빌드 통과**: `./gradlew assembleDebug` → BUILD SUCCESSFUL (23s)
- **기기 리그레션 확인**: Jay 수동 검증 완료. 기존 PoC pinch zoom 동작 동일 (right-edge anchor 유지)

**알려진 한계**: M1 범위상 right-edge anchor 유지 → 사용자가 과거 쪽으로 줌인 불가. M2의 finger-centered pinch + 1-finger pan에서 해결.

### M2 — Pinch/Pan + Pager arbitration (G1 검증) (완료)

**목표였던 것**: 핵심 제스처 완성 + HorizontalPager 공존 전략 최종 확정.

**완료 내역** (세부 커밋 해시는 §11 작업 이력 참조):

- **Finger-centered pinch** — `calculateCentroid(useCurrent = true)`로 plot 내 fraction 계산, baseline 시작점 기준 anchor time 고정. iOS v2.2의 centroid-anchored pinch와 개념적 등가
- **1-finger pan (조건부 consume)** — `pocVisibleDomain != null`(줌 상태)일 때만 consume, 그 외는 `PASS_THROUGH`로 HorizontalPager에 양보. iOS의 `UIPanGestureRecognizer.isEnabled = pocIsZoomedOrPanned` 토글과 등가
- **GestureMode FSM** — `UNDETERMINED → PINCH/PAN/PASS_THROUGH/DISCARDED`. pinch 도중 1-finger 전환은 DISCARDED로 차단하여 의도치 않은 pan 시작 방지. 기본 보기에서 1-finger 발생 시 PASS_THROUGH로 Pager 전달
- **Gesture-end follow-latest 재평가** — `unzoomedLen = totalLength + trailingBufferSec(period)` 기준으로 `rawLen >= 99%` 이면 `pocVisibleDomain = null` + follow on, 그 외는 최신 근접(< 600s) 여부로 follow 판정

**구현 중 발견·수정한 버그 4건** (역사 기록):

1. **`pointerInput` stale capture** — `pointerInput(period, totalLengthSec)` key 안에 없는 `lastDataTs`/`dataBounds`/`resolvedVisibleDomain`/`chartState.hasDxy`가 로컬 `val remember`로 캡처돼, pointerInput coroutine 생애 동안 첫 composition 시점 값에 고정. 해결: `rememberUpdatedState` 4종으로 감싸 최신 state 참조 (iOS UIKit gesture recognizer의 action block 패턴과 등가). key 확장은 제스처 도중 cancel 위험이 있어 배제
2. **Pinch baseline의 trailing buffer 누락** — 기본 보기 시 pinch baseline fallback이 `dataBounds`(minTs..maxTs)였으나, 실제 default 표시 domain은 `fullXMin..(lastDataTs + trailingBufferSec(period))`. 길이 차이 = 2400초(40분). Pinch 시작 첫 프레임에 오른쪽 40분 영역이 사라지는 점프 발생. 해결: baseline fallback을 `bounds.start..(bounds.endInclusive + trailingBufferSec(period))` 로 맞춰 default view의 xMax와 정확히 일치
3. **`clampVisibleDomain` mid-gesture null 반환으로 인한 화면 떨림** — 이전 helper(`setVisibleDomainClamped`)는 `clampedLen >= totalLength * 99%` 이면 null 반환하여 "전체 복귀"로 해석. baseline이 trailing buffer 포함(`totalLength + 2400`)으로 확장된 상태에서는 per-frame pinch 시 proposedLen이 자주 threshold를 넘나들어 null ↔ 직전값 플리커 발생. 사용자 관찰 "화면만 흔들린다"의 직접 원인. 해결: `clampVisibleDomain`으로 재설계 — non-null 반환, 명시적 `minBoundary/maxBoundary` 파라미터, 전체 복귀 판정은 gesture-end로 이동
4. **Frozen baseline + per-frame zoomChange = 누적 상실** — `pocPinchBaselineDomain`을 gesture 시작 시 1회 캡처하고 매 프레임 `baselineLen / perFrameZoom`으로 계산했으나, `PointerEvent.calculateZoom()`은 이벤트 간 증분 배율이라 이 방식은 가장 최근 delta만 반영. 사용자 관찰 "반복 시도해야 아주 조금씩 확대" 증상의 원인. 해결: `awaitEachGesture` scope에 `var pinchCumZoom: Float` gesture-local 변수 도입, 매 프레임 `pinchCumZoom *= zoomChange` 누적, `newLength = baselineLen / pinchCumZoom`로 계산

**기기 검증 5종 통과** (Jay 수동, 2026-04-15):

- 기본 보기 2-finger pinch → 손가락 중심 기준 부드러운 줌, 떨림/플리커 없음
- 오른쪽 40분 영역 → pinch 시작 시 점프 없음 (baseline이 default view와 동일)
- 줌 상태 1-finger pan → 과거/미래 양방향 이동
- Pinch로 완전 줌아웃 → default 자동 복귀 + follow 자동 on
- 기본 보기 1-finger 드래그 → 통화 탭 스와이프 유지 (Pager 전달)

**알려진 한계 (M2 시점)**: 더블탭 토글과 fullscreen 제스처 정책이 미구현 상태였음 — 둘 다 M3에서 해결 (아래 M3 섹션 참조). Follow-latest 명시 인디케이터는 M3 검토 결과 iOS parity 기준 불필요로 결론.

### M3 — 더블탭 + Follow-latest 명시 + Fullscreen 정책 (v2.2 parity) (완료)

**목표였던 것**: iOS v2.2 단계까지 기능 일치.

**완료 내역** (세부 커밋 해시는 §11 작업 이력 참조):

- **더블탭 줌 토글 — manual 감지** ([RateGraphView.kt](app/src/main/java/com/jay/fxi/ui/components/RateGraphView.kt))
  - `awaitEachGesture` 내부에서 tap 자격(`maxPointers == 1` + `maxTravel < touchSlop` + `duration < longPressTimeout` + pinch 미발생 + plot 영역 내부) 판정.
  - `detectTapGestures`를 별도 `pointerInput` chain으로 얹는 접근은 배제. 줌 상태의 PAN 경로에서 1-finger 이벤트가 consume되므로 `detectTapGestures`(default `requireUnconsumed = true`) 가 이벤트를 못 보고 reset 더블탭이 작동하지 않기 때문.
  - `ViewConfiguration` 표준값(`touchSlop`, `longPressTimeoutMillis`, `doubleTapTimeoutMillis`, `doubleTapMinTimeMillis`) 사용.
  - 줌 OFF + 더블탭 → `currentResolvedVisibleDomain ?: (currentDataBounds.start..(lastDataTs + trailingBufferSec(period)))` baseline 기반으로 탭 fraction → tapTimeSec → 6h window → `clampVisibleDomain`. distance(raw.endInclusive, lastDataTs) < 600s이면 follow on.
  - 줌 ON + 더블탭 → `pocVisibleDomain = null` + follow on (reset).
  - 단일 탭 복구: PAN 경로가 줌 상태에서 `pocIsFollowingLatest = false` + raw sync 같은 side-effect를 남기는데, 단일 탭이면 gesture 시작 시점의 `preGestureDomain`/`preGestureFollow` 스냅샷을 복구 → 의도치 않은 follow 중단 방지.
  - Plot bounds guard: `downPosition.x in plotLeft..(plotLeft + plotWidth)` + `downPosition.y in plotTopPx..plotBottomPx`. x축 라벨/Y축 숫자 영역 탭은 자격 탈락.
- **구현 중 발견·수정한 edge case 2건** (역사 기록):
  1. **더블탭 timeout 기준을 `downTimeMs`로 저장**: 초기 구현에서 `pocLastTapTimeMs = downTimeMs`로 기록하고 `sinceLast = (tap2 down) - (tap1 down)`로 비교. Android `GestureDetector.DOUBLE_TAP_TIMEOUT` 관례는 "첫 탭의 up → 두 번째 탭의 down" 간격이라, down 기준 저장 시 첫 탭이 길었던 만큼 허용 window가 단축돼 느린 더블탭이 놓쳐질 수 있음. 해결: `pocLastTapTimeMs = lastUpTimeMs` (up 시점 저장).
  2. **non-tap gesture가 lastTap 후보를 무효화하지 않음**: `wasTap == false`로 종료된 gesture(실패 pan, pinch, long press, plot 밖 짧은 탭, pager pass-through)에서 `pocLastTapTimeMs`를 그대로 두면, `tap1(plot 안) → 실패 pan → tap2(plot 안)` 시퀀스에서 tap1과 tap2가 잘못 묶여 false-positive double tap이 발생. 해결: `wasTap == false`인 모든 종료에서 `pocLastTapTimeMs = 0L` 리셋. Android `GestureDetector`와 동일 semantic.
- **Follow-latest 명시화** — M1에서 이미 `pocIsFollowingLatest: Boolean` state 도입, M2에서 gesture-end 재평가(`unzoomedLen * 99%` full-unzoom 릴리스 + `distance(raw.upperBound, lastTs) < 600s`)가 완료돼 있어 M3 범위에서 추가 구현 없음. iOS도 인디케이터/아이콘 없이 자동 재평가만 사용하므로 parity 유지.
- **Fullscreen 정책 정리** ([CurrencyTabContent.kt](app/src/main/java/com/jay/fxi/ui/screen/CurrencyTabContent.kt))
  - Normal mode 그래프 wrapper의 `detectTapGestures(onDoubleTap = setGraphFullscreen(true))` **제거**. 그래프 내부 더블탭 = 줌인/리셋, 그래프 "주변"(label/여백) 더블탭 = fullscreen 진입이 되어 같은 제스처가 위치에 따라 의미가 달라지는 혼란 발생. iOS도 동일 이유로 제거함. 진입은 `OpenInFull` 아이콘 clickable로 단일화.
  - Fullscreen mode 그래프 wrapper의 `detectTapGestures(onDoubleTap = setGraphFullscreen(false))` **제거**. wrapper `detectTapGestures`가 outer에서 event를 consume해 RateGraphView 내부 M3-a 더블탭 줌이 먹통이 되는 역회귀가 발생. 종료는 `Cancel` 아이콘 clickable로 단일화.
  - **Rates 섹션** 더블탭 (진입/나가기)은 M3-b 범위 밖으로 유지 — rates 영역에는 내부 더블탭 제스처가 없어 wrapper와 충돌할 대상이 없기 때문.

**기기 검증 통과** (Jay 수동, 2026-04-15 후속):

- 기본 보기 2-finger pinch (떨림 없음), finger-centered anchor 유지 (M2 회귀)
- 기본 보기 plot 안 더블탭 → 6h 줌인, 탭 위치 중심
- 줌 상태 더블탭 → default 복귀 + follow 자동 on
- 느린 더블탭 (첫 탭 100ms+ 지속) → 정상 double tap (edge case 1 fix 확인)
- tap → 빠른 pan → tap 시퀀스 → 각각 독립 single tap (edge case 2 fix 확인)
- 그래프 주변(라벨/여백) 더블탭 → fullscreen 진입 안 됨 (원래 버그 해소)
- OpenInFull 아이콘 → fullscreen 정상 진입
- Cancel 아이콘 → fullscreen 정상 나가기
- Fullscreen 안 더블탭 → 줌인/리셋 동작 (wrapper 가로채기 없음)
- Rates 섹션 더블탭 → rates fullscreen 진입/나가기 정상 (범위 밖 회귀)
- 기본 보기 1-finger 드래그 → 통화 탭 스와이프 (Pager arbitration 회귀)

**알려진 한계**: fullscreen mode의 단일 탭 종료 affordance는 iOS에 있지만 Android에는 없음. §9 iOS divergence 항목 참조.

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

### v1 PoC baseline — 2+ pointer only consume

M1 이전 PoC 단계의 단순 정책. 1-finger 드래그는 항상 Pager로 양보되고 2+ pointer만 pinch로 인식.

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

### M2 이후 (현재 구현) — GestureMode FSM 기반 조건부 consume

`awaitEachGesture` 안에서 gesture-local `GestureMode` FSM을 유지하며 pointer 수와 줌 상태에 따라 분기한다. iOS의 `UIPanGestureRecognizer.isEnabled = pocIsZoomedOrPanned` 토글과 **개념적 등가**.

```kotlin
enum class GestureMode { UNDETERMINED, PINCH, PAN, PASS_THROUGH, DISCARDED }

awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false)
    var gestureMode = GestureMode.UNDETERMINED
    var pinchCumZoom = 1f  // 누적 배율 (calculateZoom은 per-frame 증분)
    do {
        val event = awaitPointerEvent()
        val pressedPointers = event.changes.count { it.pressed }
        when {
            pressedPointers >= 2 -> {
                // pinch: baseline + finger-centered anchor 캡처 후 pinchCumZoom 누적
                // baseline fallback은 lastDataTs + trailingBufferSec(period) 포함
                // consume 필수
            }
            pressedPointers == 1 -> when (gestureMode) {
                PINCH -> gestureMode = DISCARDED        // pinch → 1-finger 전환 차단
                DISCARDED -> { /* 무시 */ }
                UNDETERMINED -> {
                    if (pocVisibleDomain != null) {
                        // 줌 상태: pan baseline 캡처, consume
                        gestureMode = PAN
                    } else {
                        // 기본 보기: Pager에 양보
                        gestureMode = PASS_THROUGH  // consume 안 함
                    }
                }
                PAN -> { /* translation 기반 이동, consume */ }
                PASS_THROUGH -> { /* 계속 양보 */ }
            }
        }
    } while (event.changes.any { it.pressed })
    // gesture ended: unzoomedLen 기준 full-unzoom 릴리스 또는 distance 기반 follow 재평가
}
```

**설계 근거**:

- `UNDETERMINED → PASS_THROUGH` 는 **첫 이벤트에서 consume 하지 않음**이 핵심. Compose HorizontalPager는 child가 consume 하지 않은 드래그를 자연스럽게 획득하므로, 명시적 "양보" 제스처 없이도 스와이프가 동작
- `DISCARDED`는 "pinch 중 한 손가락을 떼면 남은 gesture cycle 동안 pan 시작 금지" 용도. 의도치 않은 pan 시작 방지
- rememberUpdatedState로 감싼 `currentLastDataTs`/`currentDataBounds`/`currentResolvedVisibleDomain`/`currentHasDxy`를 pointerInput 내부에서 참조 — pointerInput의 key를 `(period, totalLengthSec)`로 유지한 채 새 데이터/state 변경을 반영

### Fallback 전략 (v1.0 문서 §4 계승)

G1 실패 시에 대비해 남겨둔 대안:

1. 1-finger pan 포기 → 2-finger pan(핀치 후 두 손가락 drag)
2. 확대 모드 토글 버튼 — 가장 확실하지만 UX 손실

M2 기기 검증 5종 통과로 G1 게이트는 통과됐으며, 위 fallback은 폴백 옵션으로 유지.

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
| **Graph fullscreen 단일 탭 종료 (iOS divergence)** | **의도적 divergence** | **post-parity 후보** |

### iOS divergence — Graph fullscreen 단일 탭 종료 (M3-b)

**무엇이 다른가**:

- iOS: `RateGraphView` fullscreen overlay에 `.onTapGesture {}` (단일 탭) + `.xmark.circle.fill` 버튼 두 종료 경로. UIKit recognizer chain의 double-tap-to-fail 메커니즘으로 단일 탭과 내부 더블탭 zoom이 자연스럽게 공존.
- Android: `Cancel` 아이콘 clickable 단일 종료 경로. wrapper `detectTapGestures` 완전 제거.

**왜 다른가**:

1. Compose에서 `detectTapGestures`를 outer로 chain하면 event를 consume하여 `RateGraphView` 내부의 M3-a manual double-tap zoom이 작동하지 않는 역회귀가 발생 (M3-b 초기 테스트에서 확인).
2. iOS와 동일한 "단일 탭 종료 + 더블탭 zoom" 공존을 위해선 `RateGraphView`에 `onSingleTap: (() -> Unit)?` 콜백 파라미터를 추가하고 manual tap 감지 내부에서 단일 탭 분기를 외부로 라우팅해야 함 → API 확장 + 테스트 부담.
3. 사용자 의도가 "확대 버튼만 남기고 wrapper 더블탭 제거" 방향이었고, ETC 원칙(복잡도 대비 실익)으로 평가 시 Android는 단순 정책이 합리적이라 판단.

**언제 재검토**:

단일 탭 fullscreen 종료 affordance가 UX 요구사항으로 돌아오면 (예: Cancel 아이콘 도달성 문제 보고) `RateGraphView` onSingleTap 콜백 확장 방향으로 재검토. 현재는 Cancel 아이콘이 우상단에 명확히 배치돼 있어 실익 없음.

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
2026-04-15  M2 구현 (finger-centered pinch + 1-finger pan + pager arbitration)
            구현 중 버그 4건 발견·수정 (stale capture / trailing buffer baseline /
            clamp mid-gesture null / frozen baseline per-frame zoom)
            기기 검증 5종 통과 후 커밋 6b111cb
2026-04-15  M3 구현 (graph manual double-tap zoom + wrapper fullscreen 정책 정리)
            M3-a: awaitEachGesture 내부 manual tap 감지 + 더블탭 6h 토글.
                  edge case 2건(lastUpTimeMs 저장 기준 / non-tap lastTap 리셋) 수정
            M3-b: CurrencyTabContent.kt wrapper detectTapGestures 2건 제거 +
                  iOS divergence 주석 (fullscreen 단일 탭 종료는 의도적으로 미채택)
            기기 검증 11종 통과 후 커밋 ef2de3c / 38064aa
```

git 커밋 (Android):

- `8a53808` — feat(android): graph zoom PoC baseline (v1.0 design + pinch-only 1d PoC)
- `05ee913` — docs(android): GRAPH_ZOOM_DESIGN.md v2.0 — iOS v2.5 parity 로드맵 개편
- `4814d98` — refactor(android): M1 상태 모델 전환 + computeChartState 분해
- `37cae1b` — docs(android): M1 이력 보정 + current HEAD 표기 규칙 적용
- `6b111cb` — feat(android): M2 graph zoom — finger-centered pinch + 1-finger pan + pager arbitration
- `424501b` — docs(android): M2 완료 이력 반영 (§4 M2 + §6 Pager arbitration + §11)
- `ef2de3c` — feat(android): M3-a graph manual double-tap zoom (awaitEachGesture 내부 감지)
- `38064aa` — refactor(android): M3-b graph wrapper 더블탭 fullscreen 제거 + iOS divergence 주석
- **current HEAD** — docs(android): M3 완료 이력 반영 (§4 M3 + §9 iOS divergence + §11)
