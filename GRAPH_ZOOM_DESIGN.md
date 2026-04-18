# Android 그래프 줌/팬 — iOS v2.6 parity 포팅 로드맵 v2.1 (Android)

> **상태**: v2.5 핵심 M1~M5 포팅 완료(2026-04-14) → v2.6 확장(DXY live + followTick) Option A bridge로 반영 완료(2026-04-19).
> **플랫폼**: Android (Jetpack Compose Canvas 자체 구현).
> **크로스 플랫폼 기준**: iOS v2.6 (`../ios/GRAPH_ZOOM_DESIGN.md`) — 기능 + gating semantics parity. 구조는 Option A callback bridge (§4.6).
> **스코프**: [app/src/main/java/com/jay/fxi/ui/components/RateGraphView.kt](app/src/main/java/com/jay/fxi/ui/components/RateGraphView.kt) (본화면 + 예시화면 공용 composable), [CurrencyTabContent.kt](app/src/main/java/com/jay/fxi/ui/screen/CurrencyTabContent.kt) (tick state + payload).
> **이전 문서**: v1.0은 2026-04-11 Android 단독 설계 초안. PoC 구현이 baseline으로 커밋된 시점(`8a53808`)에 **v2.0 문서가 이를 대체**. v2.1은 iOS v2.6 parity 확장을 반영.

---

## 0. 버전 이력 요약

| 버전 | 시점 | 내용 |
|---|---|---|
| v1.0 | 2026-04-11 | Android 단독 설계 초안 (Android 고유 목표 + "모든 기간 인터랙션") |
| v2.0 | 2026-04-14 | iOS v2.5 parity 로드맵으로 전면 개편. 1d only 원칙 확정. M1~M5 마일스톤 정의. PoC v1 baseline은 커밋 `8a53808`로 고정. |
| **v2.1** | **2026-04-19** | **iOS v2.6 parity 확장 반영.** 서버 `data.indices.dxy` 수신 경로 추가(WebSocket DTO/Service/ViewModel/UI wiring) + follow-latest `followTick` synthetic tick 도입(Option A callback bridge). 기능+gating semantics parity 달성, 구조 통일은 Option B로 후속 유보. §4.6 참조. |

---

## 1. 목표 / 비목표

### 목표

- **iOS v2.6 기능 + gating semantics parity** — 양 플랫폼 사용자가 같은 UX 경험 (v2.5 핵심 M1~M5 + v2.6 DXY live/followTick 확장)
- **1d period 한정** 인터랙션 (핀치/팬/더블탭/follow-latest/followTick)
- Android 공용 composable 구조 활용 — 본화면 + LockedPreviewScreen 동시 반영 (iOS의 `SampleGraphView` 복제 불필요)
- HorizontalPager와 제스처 공존 (iOS의 `TabView(.page)` 등가 문제 해결)
- Canvas 수동 렌더링의 장점 활용 — iOS Swift Charts의 일부 고유 이슈(chartXScale 자동 보간, identity diff) 회피

### 구조 parity는 명시적 trade-off

iOS는 follow 상태 + synthetic tick + live tail 생성이 모두 `RateGraphView.swift`에 co-located. Android는 live tail 생성이 `CurrencyTabContent.kt`의 `buildGraphPayload()`에 있어 **callback bridge를 두어야 같은 gating semantics 달성**(Option A). 구조 통일(Option B)은 live tail 생성을 RateGraphView로 이동하는 추가 리팩토링이 필요해 비용 대비 가치 불균형으로 **후속 과제로 유보**. §4.6에서 현재 결정 명시.

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

## 3. iOS v2.5/v2.6 ↔ Android gap (기능 매핑)

> 2026-04-19 기준. "v1" 표기는 본 문서 작성 당시 baseline. "현재" 표기는 v2.1 문서 갱신 시점 반영.

| 기능 | iOS 기준 | Android v1 | Android 현재 | 플랫폼 차이 |
|---|---|---|---|---|
| 2-finger pinch | v2.5 finger-centered | right-edge anchor | ✅ 완료 | centroid 기반 |
| 1-finger pan (zoomed only) | v2.5 ✅ | ❌ | ✅ 완료 | Pager 공존 해결 |
| 더블탭 줌 토글 (6h + reset) | v2.5 ✅ | ❌ | ✅ 완료 | - |
| plot bounds guard | v2.5 `plot.contains` | ❌ | ✅ 완료 | Canvas size 기반 |
| Follow-latest | v2.5 명시 flag + resolved domain | 암시적 (항상) | ✅ 완료 (명시 flag) | pan 추가 후 명시화 |
| Adaptive xTicks | v2.5 1h/3h | 1d 고정 3h | ✅ 완료 | - |
| 30분 보조 grid | v2.4 점선 dashed | ❌ | ✅ 완료 | `PathEffect.dashPathEffect` |
| Gesture y-lock | v2.3 ✅ | ❌ | ✅ 완료 | - |
| Rate 전체 렌더 + clipping | v2.3 | **v1부터 존재** | - | **Canvas 자연 속성** |
| DXY visible-only 정규화 | v2.3 ✅ | ✅ | - | - |
| DXY 경계 보간 | v2.3 ✅ | ❌ | ✅ 완료 (보수적) | - |
| DXY 라벨 0개 fallback | v2.3 ✅ | inset 기반 fallback | ✅ 완료 | - |
| 더블탭 애니메이션 | v2.5 타협형 (X/Y 시차) | ❌ | ✅ 완료 | `Animatable` + coroutine |
| Fullscreen 정책 | v2.2 wrapper 더블탭 제거 | 미정 | ✅ 완료 | `CurrencyTabContent.kt` |
| HorizontalPager 충돌 | N/A | 2+ pointer consume | ✅ 완료 (M2) | 이미 해결 |
| **DXY live tick (indices.dxy)** | **v2.6 `dxyLive` @Observable** | **❌** | **✅ 완료 (v2.1 문서)** | **Kotlinx StateFlow + Serialization (ignoreUnknownKeys)** |
| **followTick synthetic tick** | **v2.6 `.task(id:)` in RateGraphView** | **❌** | **✅ 완료 (Option A bridge)** | **RateGraphView → callback → CurrencyTabContent `LaunchedEffect`** |
| **tailTimestamp 세멘틱** | **v2.6 `nowTs = Date()`** | `max(rate.timestamp)` | **✅ 완료 — `Clock.System.now()`** | 이전 max(data) 대비 broadcast 무관 전진 |

---

## 4. 포팅 마일스톤 (M1~M5 + v2.6 parity 확장 §4.6)

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

### M4 — DXY 정합성 + Gesture y-lock + 30분 보조 grid (v2.3/v2.4 parity) (완료)

**목표였던 것**: 폴리시 단계 — DXY 시각 결손 제거 + gesture 중 라벨 안정 + adaptive xTicks.

**완료 내역** (세부 커밋 해시는 §11 작업 이력 참조):

- **M4-a: DXY 경계 보간** (`interpolateDxyBoundary` helper + `ChartState.dxyDisplayBuckets`)
  - 1d 줌 상태에서 visible 양 경계에 linear interpolated 가상 `GraphBucket` prepend/append. iOS `interpolateDxyBoundaryPoint` 등가
  - data 범위 밖이면 null (extrapolation 안 함), 정확히 버킷에 걸리면 null (이미 visible 포함)
  - `computeChartState`에서 visible domain 줌 시점에만 보간, default view에서는 dxyGraphData 그대로 (회귀 0)
  - `computeDxyPath` 호출부를 `dxyGraphData` → `chartState.dxyDisplayBuckets`로 변경
  - 정규화 기준(`dxyMin`/`dxyMax`)에 boundary 값이 포함되므로 visible 직후 다음 버킷이 visible dxyMin/dxyMax 밖이라도 path가 chartTop/chartBottom으로 shoot되는 v1.x 수직 아티팩트 제거. 이전엔 `clipRect`로 시각만 잘려 변동 큰 줌 구간에서 잠재적으로 노출 가능했음 (단순 parity가 아니라 **잠재 결함의 구조적 fix**)
- **M4-a: DXY 라벨 0개 fallback** (`generateDxyLabels`)
  - 줌이 깊어 (dxyMin, dxyMax)가 step 경계 사이에 끼면 `start > end`가 되어 빈 배열 반환 → 라벨 표시 완전 결손되는 케이스 처리
  - `labels.isEmpty()` 시 mid 값 단일 라벨 (`%.2f` 정밀도) push. iOS 동일 패턴
- **M4-c: Adaptive xTicks** (1d 종속, `adaptiveHourIntervalOneDay` + `generateHourTicksOneDay`)
  - visible length > 12h → 3시간, > 6h → 1시간, 그 외 → 1시간 (30min은 정수 alignment 필요해 clamp)
  - 이전엔 1d 분기가 항상 3시간 하드코딩 → 줌인 시에도 라벨 간격이 변하지 않아 sparse했음
- **M4-c: 30분 보조 grid** (`generateHalfHourGridsOneDay` + `XTick.isHalfHour`)
  - visible length ≤ 9000s (2.5h)일 때만 활성. minute==30 위치만 별도 함수에서 생성 후 정각 tick과 merge sorted (한 루프에 섞으면 중복/누락 위험)
  - Canvas draw에서 `isHalfHour` 분기 — `PathEffect.dashPathEffect`로 3dp 점선, 라벨 그리지 않음, alpha 0.18 (정각 0.3보다 명확히 흐림)
- **M4-b: Gesture y-lock** (`GestureYLock` data class + `pocGestureYLock` state + `computeChartState(yLock)` 분기)
  - pinch/pan began에서 yRange/dxyRange를 통째 snapshot, gesture-end에서 release. iOS `pocGestureYLock` 등가
  - lock 동안 `effectiveY*`/`effectiveDxy*`가 lock 값 사용 — visible 데이터 갱신으로 인한 매 프레임 라벨 값 변동 차단
  - **사용자 체감 보고로 도입 결정**: M4-c 완료 후 jitter 평가 단계에서 "Y축 라벨이 빠르게 훅훅 변함" 확인 → skip 옵션 기각, 진행
  - `dxyDisplayBuckets`은 lock과 무관하게 visible 기반으로 매 프레임 갱신 — line은 신선한 데이터로 그리되 정규화 기준만 lock
  - `currentChartState by rememberUpdatedState(chartState)` — gesture coroutine에서 mutation 전 pre-gesture chartState 접근 (M2 fix-1 패턴)

**구현 중 발견·수정한 결함 4건** (역사 기록 — 모두 코덱스 리뷰 + 직접 트레이스로 발견):

1. **M4-a-1 raw filter base 결함**: 초기 구현은 `visibleDxyBuckets`의 `ifEmpty { full }` fallback을 base로 사용 → "between buckets" 줌 시나리오 (visible window가 두 인접 dxy bucket 사이에 끼임)에서 raw filter는 empty이지만 boundary는 양쪽에서 성공해야 하는데, base에 fallback이 적용돼 있으면 "전체 데이터 + boundary"가 mix되어 의미 없는 line 추가됨. raw filter를 base로 쓰고 최종 `withBoundaries`가 비었을 때만 fallback하도록 수정 (iOS 패턴 일치)
2. **M4-c adaptive interval 정수 나눗셈 임계점 결함**: `visibleLengthSec / 3600` 정수 truncate로 12h ~ 12h59m 구간이 hours==12로 ">12" false → iOS Double semantic(12.0001부터 즉시 3h) 대비 임계점 어긋남. 초 단위 직접 비교(`> 12 * 3600`)로 수정
3. **M4-c 30분 grid alpha 정반대 결함**: 주석은 "정각보다 흐리게"라고 적었으나 코드는 `gridColor.copy(alpha = 0.5f)` — `Color.copy(alpha)`는 곱셈이 아닌 교체이므로 보조선 alpha = 0.5 > 정각 0.3 → 정반대. `SecondaryText.copy(alpha = 0.18f)` 직접 지정으로 수정 (정각 60% 가시성)
4. **DISCARDED cleanup leak (M4-b 도입 시 발견된 M3-a 누락 + M4-b lock leak 결합)**: `pressedPointers == 1` 분기에서 `gestureMode == PINCH` 인 경우 `gestureMode = DISCARDED`로 전환되는데, gesture-end cleanup 분기 조건이 `PINCH || PAN`만 포함하고 DISCARDED는 빠져 있어 cleanup 전체가 skip. 누설되는 state 3종:
   - `pocGestureYLock` (M4-b 도입으로 새로 추가) — 라벨이 lock 값에 frozen
   - `pocVisibleDomain` full-unzoom 자동 release (M2-fix-4) — 거의 풀줌아웃 상태에서 default 복귀 안 함
   - `pocIsFollowingLatest` 재평가 (M2-fix-4) — follow 자동 reactivation 안 됨, 새 데이터 들어와도 그래프 슬라이드 안 함
   - 후자 2건은 M2-fix-4 도입 시점에 이미 누락된 상태였고 (DISCARDED는 M2 GestureMode FSM 도입과 함께 만들어졌으나 cleanup 분기 영향이 검토되지 않았음), M4-b yLock leak이 새로 추가되며 코덱스 리뷰가 발견. 같은 fix(분기 조건에 DISCARDED 포함)로 3건 모두 잡힘
   - **새 discipline**: GestureMode enum의 모든 종료 상태에 대해 cleanup 영향을 매트릭스로 확인. (UNDETERMINED → cleanup 불필요, PINCH/PAN/DISCARDED → cleanup 필수, PASS_THROUGH → cleanup 불필요)

**기기 검증 통과** (Jay 수동, 2026-04-15~16):

- 1d default view DXY 선이 plot 좌우 경계까지 자연스럽게 닿음
- 깊은 줌에서 DXY 라벨 fallback 작동 (mid 값 단일 라벨)
- "between buckets" 줌 시나리오에서 의미 없는 mix line 없음
- 12h 초과 즉시 3h 라벨 전환 (이전엔 13h 도달 전까지 1h 유지)
- 강한 줌 (≤ 2.5h)에서 30분 dashed line 정각 라인보다 명확히 흐림
- Pinch 중 rate/dxy 라벨 안정 (lock 효과)
- Pan 중 라벨 안정
- 정상 release vs DISCARDED 경로 release 모두 동일하게 정상 cleanup
- 풀줌아웃 자동 release 작동
- M2/M3 회귀 모두 정상 (pinch/pan/더블탭/통화 탭 스와이프)

**알려진 한계 (M4 시점, 의도적 divergence)**:

- DXY 라벨 `inset = span * 0.05` 유지 (iOS는 inset 없음). Android는 라벨이 plot 가장자리에 너무 가까이 붙는 것을 막는 보수적 정책으로 의도적 divergence. §9 참조
- y-lock 동안 visible 데이터의 새 max/min이 lock 범위 초과하면 line이 chart 경계 잠시 벗어나 잘림. iOS와 동일 trade-off, 라벨 안정성이 더 큰 가치라 수용

### M5 — 더블탭 애니메이션 (v2.5 실험적 parity) (완료)

**목표였던 것**: iOS v2.5 타협형 parity 또는 더 깔끔한 해결.

**완료 내역** (세부 커밋 해시는 §11 작업 이력 참조):

- **Animation infra**:
  - `pocAnimationJob: Job?` remembered state — 진행 중 애니메이션 관리
  - `animationScope = rememberCoroutineScope()` — composable scope의 coroutine launcher
  - `DisposableEffect { onDispose { pocAnimationJob?.cancel() } }` — composable dispose 시 정리
  - `LaunchedEffect(period)`에 job cancel 추가 — 기간 변경 시 정리
  - `animateDomainTransition(fromStart, fromEnd, toStart, toEnd, durationMs, onFrame)` file-bottom private suspend helper — `Animatable<Float>`로 progress 0→1 보간, 매 frame onFrame 콜백에서 caller가 (start, end)를 받아 state 갱신
- **Easing/duration**: iOS `.easeOut(duration: 0.25)` 등가로 Compose의 `LinearOutSlowInEasing` + 250ms `tween`. iOS Swift `.easeOut`은 quadratic, Compose `LinearOutSlowInEasing`은 cubic-ish 곡선이라 미세 차이는 있으나 시각적으로 유사
- **더블탭 분기 재구성** (iOS `pocHandleDoubleTap` 등가):
  - **Reset 케이스** (`preGestureDomain != null`): yLock 미사용 (iOS 동일 패턴 — reset은 도메인 expand라 자연스럽게 yRange 확장됨). animation으로 from(현재 pocVisibleDomain) → to(default) 보간 후 `pocVisibleDomain = null` + `follow on`
  - **Zoom in 케이스** (`preGestureDomain == null`): yLock snapshot → animation으로 default → 6h target 보간 → settle to clamped + follow 재평가 → yLock 즉시 release
  - **Chaining**: 새 더블탭 진입 시 진행 중 animation cancel. 새 from = 마지막 animated value (자연스러운 인계)
- **Cancel 경로**:
  - 새 더블탭 진입 시 — 진행 중 애니메이션 cancel + lock release (빠른 연속 시나리오 fix, 아래 결함 1 참조)
  - Pinch/pan begin 시 — 진행 중 애니메이션 cancel (새 gesture가 자체 lock snapshot)
  - LaunchedEffect(period) period 변경 시 — cancel + 모든 state reset
  - DisposableEffect onDispose composable dispose 시 — cancel

**구현 중 발견·수정한 결함 1건** (자기 검증으로 발견):

1. **빠른 연속 더블탭 (zoom in 직후 reset) lock leak**: 첫 zoom in이 yLock을 set한 상태에서 사용자가 즉시 다시 더블탭(reset)하면, 더블탭 진입부에서 `pocAnimationJob.cancel()`만 했을 뿐 yLock은 release하지 않아 reset 분기로 stale lock 누설. Reset 분기는 자체 yLock 사용/release 안 하므로 lock이 reset 애니메이션 동안 그대로 유지되고 reset 종료 후에도 frozen. 더블탭 진입부 직후 `pocGestureYLock = null` 명시 추가로 해결.

**기기 검증 결과** (Jay 수동, 2026-04-16):

- 기능적 14항목 통과 (1, 2, 5~14 정상 동작)
- **잔존 시각 차이 2건** (이번 M5 범위에서 **수용된 trade-off**, 명백한 결함 아님):
  - **줌인 애니메이션 중 라벨 안정**: yLock 효과로 라벨 값 자체의 급변은 억제되지만, 사용자 관찰 결과 chart 변형(visible domain 24h → 6h morph)과 함께 라벨이 같이 움직이는 듯한 시각 인상이 일부 남음. 사용자 평가 "크게 이상하진 않음". 코드 정적 분석으로 lock은 값 수준에서 작동(`effectiveYMin/Max = yLock.yMin/Max`)하지만, X축 morph가 dramatic해서 시각 인지에서 chart 전체 transformation으로 묶여 인식되는 것으로 추정. 더 나은 해법은 §9 post-M5 후보 참조
  - **부드러움 자체는 iOS Swift Charts 대비 살짝 부자연스러움**: Compose Canvas는 매 프레임 chartState recomputation (visible filter, computeYRange/DxyRange, dxyDisplayBuckets boundary 보간, computeDxyPath, computeRatePaths) + software path drawing. iOS Swift Charts는 GPU 가속 implicit 보간. 플랫폼 inherent 차이로 수용. 저비용 polish 후보(easing 곡선 변경, duration 미세 조정, computeChartState 비용 최적화)는 §9 참조

**iOS divergence (의도적, M5 시점)**:

- yLock 지연 release 미채택 (iOS는 0.26s Task로 settle 후 release): Compose에서 동일 패턴 안전 구현하려면 자연 chartState 파생 state 별도로 두거나 computeChartState를 두 번 계산해야 함 → ETC 원칙에 따라 즉시 release 채택. 자세한 근거는 §9 노트 참조
- Animation 중 pinch handoff 시 stale lock snapshot 이어받음: iOS도 같은 패턴(`pocHandlePinchBegan`이 `data.yRange` = locked 값 읽음). 의도적 visual continuity trade-off로 수용. renderDomainOverride 패턴 refactor가 더 나은 해법이지만 큰 refactor라 post-M5 후보. §9 노트 참조

### M5 post-polish 수정 (사용자 피드백 기반)

M5 완료 후 실기기 사용 피드백으로 발견된 기능/성능 이슈 9건을 후속 fix/perf 커밋으로 반영 (세부 커밋 해시는 §11 작업 이력 참조). 모두 post-M5 polish 범위.

1. **Gesture clamp에서 trailing buffer 제거** (`fix`)
   - 증상: 최대 줌 상태에서 pan 최우측 시 chart 우측 ~66%가 빈 공간. iOS엔 없는 geometry 버그
   - 원인: pinch/pan/double-tap clamp의 `maxBoundary`가 `lastDataTs + trailingBufferSec(period)` (= 40min 포함). default view(24h)에서는 2.8%라 미미했지만 max zoom(1h)에서는 40min/60min = 66.7%
   - 디버그 로그로 원인 확정 (`pocVisibleDomain = 1776301020..1776304620, lastDataTs = 1776302238` → 39.7min 뒤까지 visible)
   - iOS 비교: `setVisibleDomain`의 rightBoundary = `latestAnchor`(trailing 없음). chart rendering domain(`xDomain`)에만 trailing 포함. 즉 iOS는 render domain과 gesture clamp를 분리, Android는 혼용
   - 수정: gesture clamp `maxBoundary` 에서 trailing 제외. default view rendering domain의 trailing은 유지. 파급으로 gesture-end full-unzoom 판정의 `unzoomedLen`도 `dataBounds span` 기준으로 정렬 (안 그러면 full unzoom release 못 trigger)
2. **`computeRatePaths` visible 필터 (perf)**
   - 증상: pan 시 선이 툭툭 끊어지면서 재정렬되는 듯한 느낌. frame drop 의심
   - 원인: `computeRatePaths`가 줌 상태에서도 전체 bucket (3 sources × 145 = 435 `Path.lineTo`)로 path 재구성 후 `clipRect`로 시각만 자름. 매 프레임 재구성 비용이 16ms budget 초과로 frame drop
   - 수정: visible range + **index 기반 ±1 bucket 마진** 필터 추가. 하드코딩 상수(예: 600s) 는 bucket gap 있는 데이터에서 edge segment 잘림 위험이라 index 기반 사용. 간격 무관하게 edge segment가 chart 경계까지 이어짐. max zoom 1h 기준 435 → ~24 lineTo (18× 감소). default view는 전체가 visible이라 필터 통과 — 회귀 0
3. **yLock release 시 yRange easeOut 전환** (`perf`)
   - 증상: pinch/pan 손 뗀 직후 Y축 라벨과 line이 한 프레임에 "뚝" 변경. iOS는 부드러운 애니메이션, Android는 즉시 snap
   - 원인: iOS는 Swift Charts의 `withAnimation(.easeOut(0.25)) { pocGestureYLock = nil }` 으로 yRange 전환을 implicit 보간. Android Compose Canvas는 implicit animation 없어 즉시 snap
   - 수정: 직전 frame 대비 `pocGestureYLock`이 set→null로 전환된 시점만 감지(`lockJustReleased`). 해당 순간에 `animSpec = tween(250, LinearOutSlowInEasing)`, 그 외는 `snap()`으로 lock 진입/데이터 갱신 등 다른 변화는 즉시 반영 (iOS `withAnimation` scope 의미와 동일). 보간 대상 6개 값 (yMin/Max, rateRangeMin/Max, dxyMin/Max)을 같은 spec으로 병렬 보간하여 축/rate/DXY가 동기화된 transition으로 이동. DXY 라벨 VALUE는 `chartState.dxyMin/Max` (natural settled) 기준 생성, 위치만 animated 정규화로 계산 ("lock overlay 해제 시 true state는 natural, 위치만 interpolate" semantic)
   - 자기 비판 fix 1건: 초기 구현은 composition 본문에서 `MutableState`를 read 후 바로 write — Compose anti-pattern이라 `SideEffect`로 commit 후 update로 수정 (코덱스 지적)
4. **Double-tap 위치 slop을 AOSP 표준 100dp로** (`fix`)
   - 증상: "더블탭이 둔하게 먹힘, 10-20% 실패율. 몸 움직이며 시도할수록 실패 빈도 증가". 화면 반응 아예 없음 — 두 번째 tap이 `isDoubleTap` 조건에서 탈락하여 독립 single tap 2개로 분해
   - 원인: `distFromLast < tapSlopPx * 2f` 조건이 과도하게 tight. `touchSlop ≈ 24dp`, `× 2 = ~40dp` 수준 — AOSP `DOUBLE_TAP_SLOP_IN_DIPS = 100dp` 표준의 절반 이하. 사용자의 자연스러운 손/몸 움직임으로 인한 두 탭 위치 편차(50-90px)를 흡수 못함
   - 수정: `distFromLast < 100.dp.toPx()`. Compose `ViewConfiguration`은 `doubleTapSlop`을 직접 노출하지 않아 `100.dp.toPx()` 로 동일 계산 (AOSP 방식과 일치)
   - 자기 비판: M3-a 최초 구현 시 `tapSlopPx * 2`를 직관으로 설정, 플랫폼 표준 상수와 대조 생략. 반복 지적받은 "API/상수 인용 시 플랫폼 문서 대조 필수" 규율의 재발. 새 discipline: 수치 상수를 직관 설정 금지, 플랫폼 표준 또는 근거 있는 실측에 연동
   - 한계: 이번 수정은 distance axis 개선만 확증됨. `wasTap`의 다른 조건(`maxTravel`, `duration`, plot bounds)이나 zoomed 상태 PAN-init 경로의 간섭 가능성은 조사되지 않음. 잔존 miss가 일상 사용에서 거슬리면 추가 조사 필요
5. **회전 시 줌 상태 보존** (`feat`)
   - 증상: iOS는 가로/세로 전환 후 줌이 유지되지만 Android는 기본 보기로 리셋
   - 원인: iOS는 `@State private var viewModel`이 SwiftUI에 의해 configuration change에서도 생존. Android는 Activity recreate + composable dispose로 `remember` value가 소실되어 `pocVisibleDomain`/`pocIsFollowingLatest`가 초기값으로 돌아감
   - 수정: 두 상태를 `rememberSaveable(period, stateSaver = VisibleDomainSaver)` / `rememberSaveable(period) { ... }`로 전환. `ClosedRange<Long>?` 직렬화를 위한 `VisibleDomainSaver`(listSaver, null→emptyList) 추가. `LaunchedEffect(period)` reset 블록에서 두 상태 리셋 라인 제거 — saveable inputs(`period`)가 period 변경 시 기본값 재초기화를 이미 처리하므로 중복 리셋이 복원된 값을 파괴하는 것을 차단
   - 설계 의도: rotation (inputs 동일) → saver 복원 / period 변경 (inputs 변화) → default 재생성. 분기 로직 불필요
6. **회전 시 '환율 정보 로딩중' 플래시 제거** (`fix`)
   - 증상: iOS는 rotation이 자연스럽게 전환되지만 Android는 "환율 정보 로딩중" 텍스트 + spinner가 순간적으로 보임
   - 원인: `MainScreen` DisposableEffect onDispose가 rotation에서도 발동 → `exchangeRateViewModel.stop()` 호출 → `stop()`이 transport 정리 외에 `_appState.value = AppState.Loading` 까지 수행. 이후 Activity 재생성 → 새 DisposableEffect → `start()` → `loadInitialRates()`의 진입 라인이 다시 `_appState.value = AppState.Loading` 을 덮어쓰고 REST fetch. 두 지점 모두에서 Loading 플래시가 찍힘
   - 수정 (2곳 + 호출부):
     - `ExchangeRateViewModel.stop()`: `webSocketService.stop()`만 남김 (transport-only). UI state 유지 → Activity 재생성 후에도 이전 Connected 화면 그대로 표시
     - `ExchangeRateViewModel.reset()` 신설: `stop()` + `_appState`/`_lastUpdated` 초기화. 진짜 세션 teardown(로그아웃/구독 해지)에서만 호출
     - `loadInitialRates()`: 진입 시 `hasExistingData = _appState is Connected || _appState is Offline` 판정. 기존 데이터 있으면 Loading 플래시 생략 후 silent refresh. fetch 실패 시 기존 상태 그대로 유지하고 WebSocket만 재시작 (기존 Connected 화면 + 네트워크 복구 대기)
     - `RootScreen`: 로그아웃/premium 해제 경로에서 `exchangeRateViewModel.stop()` → `reset()`로 배선 교체. `MainScreen.kt:121` onDispose는 `stop()` 유지 (회전 시 transport만 정리)
   - iOS 비교: iOS `ExchangeRateViewModel.stop()`도 `webSocketService.stop()`만 수행. Android의 "UI reset까지 포함된 stop"은 Android 고유의 과잉 설계였음. 이번 수정으로 iOS와 semantic 일치
7. **회전 시 연결 배너 flicker 제거 — 서비스 lifecycle을 session scope로 이동** (`fix`)
   - 증상: 회전 시 상단 연결 상태 배너가 잠깐 나타났다 사라지며 아래 콘텐츠가 "내려갔다 올라오는" 출렁임. 6번 loading flash 제거 후에도 잔존
   - 원인 체인: `MainScreen` DisposableEffect onDispose가 회전에서도 발동 → `exchangeRateViewModel.stop()`/`graphViewModel.stop()` 호출 → `webSocketService.stop()` → `connectionState=Disconnected` → Activity 재생성 → `start()` → `Connecting` → `Connected`. `ConnectionStatusBanner`가 `connectionState != Connected` 구간에 `AnimatedVisibility(expandVertically/shrinkVertically)`로 표시되며 아래 콘텐츠 reflow
   - 수정: `MainScreen.DisposableEffect.onDispose`에서 `stop()` 호출 두 개 제거 (`newsViewModel.onTabDisappear()`는 탭 라이프사이클 용도라 유지). `ExchangeRateViewModel.onCleared()`에 `webSocketService.stop()` 추가 — 앱 종료 시의 transport cleanup을 ViewModel 소멸 시점에서 보장
   - 아키텍처 관점: 서비스(WebSocket/periodic refresh) lifecycle이 **MainScreen composable scope → session scope(RootScreen auth/premium)** 로 의도적으로 이동. 회전으로 MainScreen이 dispose돼도 ViewModel이 생존하므로 서비스 유지. 세션 teardown은 RootScreen의 logout/premium 해제 `LaunchedEffect`가 `reset()` 호출로 이미 커버하고 있어 구조 변경 없이 적용 가능
   - 이전 6번(loading flash)이 UI state 보존이었다면 이번은 **transport state까지 보존**. iOS의 SwiftUI @State 생존 + WebSocket 연결 유지 동작과 완결적 parity
8. **회전 시 REST 재호출 생략 — `start()` idempotency 가드 + in-flight 추적** (`perf`)
   - 증상: 7번 수정 후에도 `MainScreen` 재진입 시 `exchangeRateViewModel.start()` → `loadInitialRates()` → REST `/api/rates` 1회 호출 잔존. WebSocket이 이미 실시간 stream 제공 중이라 중복 요청
   - 1차 수정 (a7ec05e, steady-state만): `start()`에 `hasUsableData && connectionState == Connected` 가드 추가. Connected 상태 + appState가 Connected/Offline이면 `loadInitialRates()` launch 생략. cold start / reset 후 / 연결 끊김에서는 기존 fetch 경로 유지
     - `connectionState` 단독이 아닌 `hasUsableData` 곱조건으로 둔 이유: WS 연결 수립 ≠ 첫 메시지 수신. 연결만 살아있고 UI 데이터가 비어있는 이상 상태를 방어 (코덱스 리뷰 반영)
   - 2차 보강 (a3be016, in-flight edge case): 1차 가드는 cold start 진행 중(REST 300~500ms) 회전 시 여전히 중복 launch. `initialLoadJob: Job?` 필드로 in-flight 추적, `launchInitialLoadIfNotActive()` 헬퍼 추출, `invokeOnCompletion`으로 stale reference 정리. `reset()`/`onCleared()`에서 명시적 cancel 추가 — 로그아웃/구독 해지 직후 늦은 응답이 state를 덮는 race를 **실무적으로** 차단 (cooperative cancellation 한계상 이론적 window는 극미량 남음)
   - 효과: 회전 시 REST 0회. WebSocket은 이미 7번 수정으로 연결 유지 중이라 실시간 rates stream 정상. iOS parity 완결
   - 한계: 위 cooperative cancellation window는 generation token 패턴까지 가야 완전 차단이지만 over-engineering 영역이라 불채택
9. **Sample shell에 M3-b fullscreen gesture policy 전파 — 그래프 outer double-tap wrapper 제거** (`fix`)
   - 증상: 예시(LockedPreview) 화면에서 그래프 섹션을 더블탭하면 RateGraphView 내부 6h zoom이 아니라 **fullscreen 전환**이 발동. 섹션 전체 영역 어디서 더블탭해도 fullscreen 진입
   - 원인: M3-b 작업 당시 Live(CurrencyTabContent)에서만 outer `detectTapGestures(onDoubleTap = ...)` wrapper를 제거했고 Sample shell(LockedPreviewScreen)에는 전파 누락. 섹션 전체 영역의 outer double-tap wrapper가 RateGraphView 내부 M3-a 더블탭 zoom 이벤트를 consume
   - 수정: Sample의 **그래프** 관련 outer wrapper 2곳 제거
     - `SampleGraphSection` ([LockedPreviewScreen.kt:988]): `onDoubleTap = onFullscreenTap()` 제거 → RateGraphView 내부 6h zoom 복귀
     - `SampleFullscreenGraphView` ([LockedPreviewScreen.kt:600]): `onDoubleTap = onClose()` 제거 → fullscreen 내부에서도 double-tap = 내부 zoom (Cancel 아이콘으로만 종료, Live와 동일)
   - **rates 섹션 wrapper는 원복 (Live와 parity)**: 초기 시도(2819fc7)에서 4곳 모두 제거했으나 재조사 결과 Live의 `CurrencyTabContent`에도 rates section에 동일 wrapper가 존재함 ([CurrencyTabContent.kt:534-536, 399-401](app/src/main/java/com/jay/fxi/ui/screen/CurrencyTabContent.kt#L534-L536)) → Sample의 rates wrapper 2곳(`SampleRatesSection`, `SampleFullscreenRatesView`)은 7ef8633에서 복구
   - Live/Sample 통일 정책 (결과):
     - Graph: 진입/종료 아이콘 전용, 섹션 더블탭은 RateGraphView 내부 6h zoom
     - Rates: 진입/종료 아이콘 + 섹션 더블탭 모두 지원
   - 자기 검증 반성: Phase 2 초기 판단 시 Live의 rates wrapper 실존 여부를 Read 없이 "없음"으로 단정 → feedback pattern 4(원인 확정 전 해결책 금지) 위반. Live 실제 코드 확인 후 2819fc7의 rates 2곳 제거를 7ef8633으로 자가 교정

**기기 검증 통과** (Jay 수동, 2026-04-16 후속):

- 1번: max zoom + pan 최우측 시 chart 우측 끝까지 데이터 채워짐 (빈공간 제거)
- 2번: pan 시 선이 눈에 띄게 부드러워짐 (frame drop 감소)
- 3번: pinch/pan 손 뗀 직후 Y축 라벨/DXY 라벨 smooth transition
- 4번: 몸 움직이며 더블탭해도 실패율 "획기적 감소"
- 5번: pan/pinch로 줌한 상태에서 가로↔세로 전환 시 줌 window 그대로 보존
- 6번: 회전 시 "환율 정보 로딩중" 텍스트/spinner 더 이상 노출 안 됨
- 7번: 회전 시 상단 연결 상태 배너 flicker 및 콘텐츠 reflow 없음
- 8번: 회전 시 REST `/api/rates` 재호출 0회 (WebSocket stream 유효 유지)
- 9번: Sample 그래프 섹션 더블탭 = 탭 위치 중심 6h zoom (toggle) / fullscreen 진입·종료 아이콘 정상 / Sample rates 섹션 더블탭 fullscreen 진입·종료 유지 (Live와 parity)
- 회귀: M2/M3/M4/M5 기존 기능 모두 정상

### 4.6 — v2.6 parity 확장: DXY live tick + followTick synthetic tick (완료, 2026-04-19)

**범위**: iOS v2.6에서 추가된 두 축을 Android에 포팅.

1. **DXY live tick** (`data.indices.dxy` 10초 WebSocket live)
2. **followTick synthetic tick** (broadcast 뜸한 구간의 창 이동 보장)

#### 4.6.1 DXY live tick 경로

기존 Android는 DXY 그래프 끝점을 `graphViewModel.latestDxyRate()`(graph cache 마지막 bucket close, 1분 stale)에서만 가져왔음 → iOS v2.6과 비대칭. 이번 파이프라인을 추가:

- **DTO**: `WebSocketMessage.kt`의 `RatesData`에 `indices: IndicesPayload? = null` 필드 추가. `IndicesPayload { dxy: DxyLiveTick? }`, `DxyLiveTick { rate, timestamp: Instant, source }` 신규. `@Serializable` + `ignoreUnknownKeys = true`로 구 서버 응답 양방향 호환.
- **WebSocketService**: `onIndicesReceived: ((IndicesPayload?) -> Unit)?` 콜백 추가, `parseMessage`에서 `onRatesReceived` 직후 invoke.
- **ExchangeRateViewModel**: `dxyLive: StateFlow<DxyLiveTick?>` 신규. `reset()`/`onCleared()`에서 null 초기화 + 콜백 null-out.
- **UI wiring**: `MainScreen.kt`에서 `collectAsStateWithLifecycle` → `CurrencyTabContent`에 전달. `buildGraphPayload`의 `latestDxyRate = dxyLive?.rate ?: graphViewModel.latestDxyRate()`로 live-first fallback.

#### 4.6.2 followTick synthetic tick (Option A callback bridge)

**왜 필요한가**: Android도 `lastDataTs`가 그래프 데이터에서 계산되고 follow-latest anchor로 쓰이므로, broadcast 뜸한 구간(주말/저변동)에 `buildGraphPayload`가 재평가되지 않아 창이 freeze될 수 있음 (iOS v2.6 이전과 같은 구조적 한계).

**동작**:

- `RateGraphView` 내부에서 `isFollowActive = pocIsFollowingLatest && pocVisibleDomain != null && period == ONE_DAY` 계산. `LaunchedEffect(isFollowActive)`로 상위에 전달.
- 상위 `CurrencyTabContent`가 `onFollowActiveChanged` 콜백으로 수신, `@State isFollowActive`에 저장. `LaunchedEffect(isFollowActive)`에서 조건 true일 때만 10초 `delay` 루프로 `followTick &+= 1`.
- `graphPayload` remember key에 `followTick` + `dxyLive` 포함 → 재평가 유도.
- `buildGraphPayload`의 `tailTimestamp`를 `max(filteredRates.timestamp)` → `Clock.System.now().epochSeconds.toInt()`로 변경 (iOS `nowTs` 세멘틱). 이전 세멘틱에서는 followTick 단독으로는 `appendLiveTail`의 `tailTimestamp > lastBucket.bucketTs` 조건 실패하여 무력이었음.

#### 4.6.3 Option A 채택 근거 + Option B 후속 유보

**구조 gap**: iOS는 follow 상태 + synthetic tick + live tail 생성이 모두 `RateGraphView.swift`에 co-located. Android는 `buildGraphPayload`가 `CurrencyTabContent.kt`에 있어 follow 상태와 물리적으로 분리.

| 대안 | 방식 | 평가 |
|---|---|---|
| **Option A (채택)** | RateGraphView → callback bridge → CurrencyTabContent `LaunchedEffect` | 작은 차분. 기능 + gating parity 달성. 구조 통일은 X |
| Option B (유보) | `buildGraphPayload` 로직을 RateGraphView 내부로 이동 | 큰 리팩토링. 구조 통일까지 달성. 비용 대비 가치 불균형 |
| Option C (기각) | 현 상태 유지 + 문서로 parity 인정 | "기능은 됨" 수준. "깔끔한 parity" 표현과 괴리 |

**결과**: Option A로 기능·gating parity 달성. Option B는 **"남은 부채"**로 본 문서에 기록(§9 post-parity 후보와 연동).

#### 4.6.4 하위 호환성

서버는 이미 `indices.dxy` 전송 중이나 Android 구 버전 앱은 Kotlinx Serialization `Json { ignoreUnknownKeys = true }`로 해당 필드를 무시하고 정상 동작 중. 따라서 이번 패치 배포 순서 리스크 0 (서버 선배포 완료 상태에서 앱만 출시하면 됨).

#### 4.6.5 커밋

- `964122e` feat(android): DXY live tick (indices.dxy) + follow-latest 10초 synthetic tick — iOS parity
- `f37b94b` refactor(android): followTick gating을 iOS v2.6 수준으로 정밀화 (Option A callback bridge)

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
| ~~DXY 라벨 inset 정책 (iOS divergence)~~ | ~~의도적 divergence~~ → **M5 post-polish에서 iOS align** (1446a16) | ~~유지~~ **해소** |
| **y-lock 동안 line이 chart 경계 잠시 벗어남** | **수용된 trade-off (iOS와 동일)** | **유지** |
| **M5 yLock 지연 release 미채택 (iOS divergence)** | **의도적 divergence** | **post-M5 polish 후보** |
| **M5 animation→pinch handoff stale lock (iOS와 동일 패턴)** | **수용된 trade-off** | **post-M5 polish 후보** |
| **M5 zoom-in 시각 동조감 (chart morph)** | **시각 인상, 코드 정적으론 lock 작동** | **post-M5 polish 후보** |
| **M5 부드러움 iOS 대비 살짝 부자연** | **M5 post-polish에서 일부 개선** (`computeRatePaths` visible 필터 + yLock release easeOut) | **추가 polish 시 검토: easing 곡선, duration 미세 조정, chartState memoization** |
| **M5 post-polish: zoomed 상태 tap이 PAN-init 경로 경유** | **잔존 tap miss 가능성 (distance slop 수정 외 축)** | **일상 사용에서 거슬리면 PAN-init 지연 발동(slop 감지 후 commit) 패턴 검토** |
| ~~**회전 시 REST `loadInitialRates()` 재호출**~~ | ~~UX는 §4 M5 post-polish 6번에서 해소, 네트워크 요청 자체는 잔존~~ → **§4 M5 post-polish 8번에서 해소** (a7ec05e + a3be016, hasUsableData && Connected + initialLoadJob in-flight 추적) | ~~별도 최적화~~ **완료** |

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

### ~~iOS divergence — DXY 라벨 inset 정책~~ (M4-a → M5 post-polish 후속에서 해소)

**상태**: 사용자 실기기 스크린샷 비교로 Android 3개 / iOS 5개 DXY 라벨 차이 확인 후 iOS align으로 결정. `generateDxyLabels` 의 `inset = span * 0.05` 제거 (커밋 `1446a16`). Android 도 iOS `generateDxyAxisLabels` 와 동일하게 range 전체에서 step 정렬.

**당시 divergence 유지 근거와 철회 이유**:

- 당시(M4-a) 근거: "dp 기반 polish 환경에서 라벨이 plot 가장자리에 너무 가까이 붙으면 시각적으로 답답해 보인다"
- 실기기 비교 결과: iOS 5 라벨 표시가 DXY 변화 폭 해석에 더 명확. "답답함" 우려보다 "해상도 부족" 손실이 큼
- 철회: divergence 유지 가치 < 비용 → iOS align 채택

**남은 원칙**:

- 라벨 0개 fallback (mid 값 single) 은 그대로 유지 — inset 제거해도 깊은 줌에서 `start > end` 가능한 case 방어
- 최종 라벨 개수는 실제 `dxyMin`/`dxyMax` 와 rounding 에 따라 결정 — iOS 도 동일 (data-dependent)

### iOS divergence — y-lock 동안 line이 chart 경계 잠시 벗어남 (M4-b)

**무엇이 다른가**:

이건 사실 **divergence가 아니라 iOS와 동일한 의도된 trade-off**. 그래도 명시 기록.

- pinch/pan 동안 `pocGestureYLock`이 yRange/dxyRange를 freeze
- 정규화는 lock 값으로 수행하되, 표시되는 데이터(`dxyDisplayBuckets`, rate buckets)는 visible 기반으로 매 프레임 갱신
- visible 데이터의 max/min이 lock 범위를 초과하면 정규화된 line 값이 `[rateRangeMin, rateRangeMax]` 밖으로 나감 → Canvas `clipRect`로 chart 경계에서 잘림
- 시각: line 끝이 chart 위/아래로 잠시 사라졌다가 gesture 종료 시 lock release되며 자연 위치로 복귀

**왜 수용하나**:

대안은 두 가지였음:
1. lock 안 함 → 사용자 체감 보고 "Y축 라벨이 빠르게 훅훅 변함" (M4-c 완료 후 jitter 평가에서 확인)
2. lock + line도 동시에 freeze → line이 visible 데이터와 시각적으로 어긋나 더 이상함

iOS는 동일 trade-off를 채택하여 v2.3에서 도입했고, 사용자가 라벨 안정성을 line 잘림보다 더 가치 있게 여긴다는 검증이 끝남. Android도 동일 결정.

pinch는 점진적이라 한 프레임의 line vs lock 편차가 작아 line 끝이 잠시 잘리는 정도이며, 거슬리는 수준은 아님.

### iOS divergence — M5 yLock 지연 release 미채택 (M5)

**무엇이 다른가**:

- iOS: `pocHandleDoubleTap` zoom in 케이스에서 `withAnimation { setVisibleDomain }` 직후 `pocGestureYLockReleaseTask = Task { sleep(0.26s); withAnimation { yLock = nil } }`로 yLock을 0.26s 지연 release. 애니메이션(0.25s) 끝난 뒤 살짝 대기 후 lock 해제하여 settle 프레임이 lock 값으로 그려진 뒤 자연 yRange로 부드럽게 전환되는 polish
- Android: 애니메이션 종료 즉시 `pocGestureYLock = null`. 지연 없음

**왜 다른가**:

iOS의 0.26s 지연은 settle 후 부드러운 yRange 전환을 위한 polish. Compose Canvas에서 동일 패턴 안전 구현하려면 "지연 release window 동안 새 pinch/pan이 시작되면 stale lock 값을 snapshot하는" 위험을 막아야 하는데, 이를 위한 정공법은:

1. 자연(unlocked) chartState를 별도 derived state로 유지하여 gesture begin 시 그 값에서 snapshot
2. 또는 computeChartState를 매번 yLock=null로 별도 호출

둘 다 인프라 추가가 필요. ETC 원칙(M5는 polish 단계, 추가 복잡도 비례 이익 작음)에 따라 즉시 release 채택. iOS의 settle 후 yRange 전환 polish는 일부 손실하지만 정확성/단순성 우선.

**언제 재검토**:

post-M5 polish 단계에서 `renderDomainOverride` 패턴(아래 항목 참조)으로 visual/logical 분리하는 큰 refactor와 함께 재검토. 그 시점에는 자연 chartState 파생도 자연스럽게 따라옴.

### M5 trade-off — Animation→pinch handoff stale lock snapshot (iOS와 동일 패턴)

**무엇인가**:

Animation 도중 사용자가 pinch/pan을 시작하면, 새 gesture의 yLock snapshot이 `currentChartState`(여전히 직전 애니메이션의 lock 값이 적용된 상태)에서 읽혀 stale lock을 이어받음. iOS `pocHandlePinchBegan`도 같은 패턴 — `data.yRange`가 lock 적용 값을 반환하므로 동일 결과.

**왜 수용하나**:

- iOS와 동일 동작이라 parity 관점에서 OK
- 스칼라 분석상 사용자가 보고 있는 라벨 값을 그대로 이어받는 visual continuity 효과 (대안: pinch begin 시 fresh natural로 snapshot → 한 번 jump가 발생)
- Edge case (250ms 애니메이션 동안 pinch 시작 시나리오) 빈도 낮음

**언제 재검토**:

`renderDomainOverride` 패턴(아래) refactor 시 자연스럽게 해소됨.

### post-M5 polish 후보 — `renderDomainOverride` 패턴 (logical/visual 분리)

iOS는 Swift Charts의 `withAnimation` 메커니즘으로 logical state(`pocVisibleDomain`)를 즉시 target 값으로 commit하고 visual interpolation은 Swift Charts가 implicit로 처리. Android는 현재 Animatable callback에서 매 프레임 `pocVisibleDomain`을 progressive하게 write하므로 logical state == visual intermediate가 되어 다음 차이가 발생:

1. Pinch baseline이 intermediate (iOS는 target)
2. yLock snapshot stale 위험 (위 항목)
3. 더블탭 결과를 즉시 상태 머신으로 commit하는 iOS 패턴과 비교해 이질감

**대안 패턴**:

```kotlin
var pocVisibleDomain by remember { mutableStateOf(...) }  // logical (즉시 target commit)
var renderDomainOverride: ClosedRange<Long>? by remember { mutableStateOf(null) }  // visual interpolation

// chartState는 effectiveDomain = renderDomainOverride ?: pocVisibleDomain 사용
// 더블탭: pocVisibleDomain = target (logical) + animate renderDomainOverride from→target
// 애니메이션 끝: renderDomainOverride = null (logical로 fall through)
// pinch begin: cancel animation + clear override (logical state는 이미 target)
// pinch baseline = pocVisibleDomain (target, not intermediate)
```

**왜 지금 안 했나**:

- M5 범위는 polish — visual/logical 분리는 큰 refactor (chartState 파생, gesture handler 모두 영향)
- ETC: 변경 범위 대비 이익이 polish 단계에서 비례하지 않음
- 현재 구현은 iOS-equivalent로 ship 가능 (parity 목표 달성)

**언제 진행**:

post-M5 polish 단계에서 위 trade-off 3건이 사용자 체감으로 명확한 불편으로 보고되면 진행. 현재는 "크게 이상하진 않음" 수준이라 보류.

### M5 부드러움 — Compose Canvas inherent 차이 (M5 post-polish에서 일부 개선)

**무엇인가**:

iOS Swift Charts 대비 Android Compose Canvas의 더블탭 애니메이션이 살짝 부자연스러움. 사용자 평가 "iOS는 부드러운 반면, android는 비교적 살짝 부자연스럽다는 느낌".

**M5 post-polish로 적용된 개선** (§4 M5 post-polish 수정 항목 2, 3 참조):

- `computeRatePaths` visible 필터로 매 프레임 path 재구성 비용 18× 감소 → pan 중 frame drop 감소 → 선이 더 부드럽게 따라옴
- yLock release 시 yRange 전환을 easeOut 250ms 보간 → gesture 손 뗀 직후 Y축 라벨/line이 "뚝" 대신 부드럽게 이동

남은 잠재 개선 (필요 시 추가 polish 후보):

- Easing 곡선 변경 실험 (`LinearOutSlowInEasing` → `FastOutSlowInEasing` 등)
- Animation duration 미세 조정 (250ms → 200 또는 300)
- `computeChartState` 비용 최적화 (dxyDisplayBuckets memoization 등)

**원인 추정**:

- Compose Canvas는 매 프레임 `chartState` recomputation: visible filter, computeYRange, computeDxyRange, dxyDisplayBuckets boundary 보간, computeDxyPath, computeRatePaths, computeXTicks 등
- 60fps에서 매 프레임 ~16ms 예산. 위 작업이 무거우면 frame drop 가능
- iOS Swift Charts는 GPU 가속 implicit 보간 (CPU 부담 거의 없음)
- Compose Canvas는 software path drawing (CPU 의존)

**저비용 polish 후보** (post-M5):

- Easing 곡선 변경: `LinearOutSlowInEasing` → `FastOutSlowInEasing` (Material 표준) 또는 cubic-bezier 직접 정의
- Animation duration 미세 조정: 250ms → 200ms 또는 300ms로 시도
- `computeChartState` 비용 최적화:
  - dxyDisplayBuckets 산출을 visible domain 변경에만 dependent하도록 memoize
  - computeDxyRange를 dxyDisplayBuckets identity 기반으로 캐시
  - Path 재구성 비용 측정 후 partial update 가능성 검토

**왜 지금 안 했나**:

위 polish는 측정 기반(profiler로 frame time 확인) + 시각 비교가 필요한 실험 단계. M5 범위는 기능 구현이고, 측정/비교 polish는 별도 작업. 현재 결과는 "기능 정상, 부드러움 약간 부족" 수준이라 ship 후 사용자 피드백 보고 결정.

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
2026-04-15  M4 구현 (DXY 정합성 + adaptive xTicks/30분 grid + gesture y-lock)
            M4-a: DXY 경계 보간 + 라벨 0개 fallback. 구현 중 raw filter base 결함
                  (visible empty 시 ifEmpty fallback 누설) 자기 검증 + 코덱스 리뷰로 발견
            M4-c: adaptive hour interval (1d 종속) + 30분 dashed grid. 정수 나눗셈
                  임계점 결함 + grid alpha 정반대 결함 코덱스 리뷰로 발견
            M4-b: gesture y-lock — 사용자 체감 "Y축 라벨이 훅훅 변함" 보고로 진행
                  결정. 구현 중 DISCARDED cleanup leak (M4-b yLock + M2-fix-4
                  full-unzoom/follow-latest 누락 3종) 코덱스 리뷰로 발견. 같은
                  fix(분기 조건에 DISCARDED 포함)로 모두 해결
            기기 검증 통과 후 커밋 db7be63 / 0d7cd24 / 2497c8c
2026-04-16  M5 구현 (더블탭 애니메이션, v2.5 실험적 parity)
            Animatable<Float> + 매 프레임 pocVisibleDomain 보간 + zoom in 케이스 yLock.
            구현 중 빠른 연속 더블탭 lock leak 자기 검증으로 발견·수정.
            기기 검증 14항목 통과. 잔존 시각 차이 2건(zoom-in 시각 동조감, 부드러움)은
            iOS divergence/inherent 차이로 수용. iOS divergence 2건(yLock 지연 release
            미채택, animation→pinch handoff stale lock) + post-M5 polish 후보 (renderDomainOverride
            패턴, easing/duration/recompute 최적화) 모두 §9에 기록
            커밋 bff1676
2026-04-16  M5 post-polish 4종 (사용자 실기기 피드백 기반)
            1) gesture clamp에서 trailing buffer 제거: 줌 + pan 최우측 시 우측 빈공간
               버그 수정. 디버그 로그로 원인 확정 (visible end가 lastDataTs + 40min
               까지 허용). iOS setVisibleDomain의 rightBoundary = latestAnchor와 정렬
            2) computeRatePaths visible 필터 (index 기반 ±1 bucket 마진): 전체 bucket
               path 재구성 비용 18× 감소 → pan smoothness 개선
            3) yLock release easeOut 250ms: gesture 손 뗀 직후 yRange snap을 animated
               전환으로. 보간 대상 6개 값(yMin/Max, rateRangeMin/Max, dxyMin/Max) 병렬.
               자기 비판 fix 1건: composition 본문 MutableState write → SideEffect 패턴
            4) double-tap 위치 slop 100dp (AOSP 표준): tapSlopPx * 2 (~40dp)는 손/몸
               움직임을 흡수 못해 실패율 10-20%. AOSP DOUBLE_TAP_SLOP_IN_DIPS 관례
               적용으로 획기적 감소
            커밋 1862f89 / 1dae300 / b597246 / 93679ee
2026-04-16  M5 post-polish 후속: DXY 라벨 inset 제거 — iOS align
            (§9 divergence 해소로 분류, §4 8건 체계엔 미포함)
            사용자 실기기 스크린샷 비교(Android 3 라벨 / iOS 5 라벨) 로 M4-a 당시
            의도적 divergence였던 `inset = span * 0.05` 유지 가치 재평가. "가장자리
            답답함 방지" 근거보다 "DXY 해상도 부족" 손실이 커서 철회 결정.
            generateDxyLabels에서 inset 제거, range 전체에서 step 정렬 (iOS와 동일).
            §9 divergence 노트를 "유지 → 해소"로 업데이트
            커밋 1446a16
2026-04-16  M5 post-polish 5번째 / 6번째: rotation 시 줌 상태 보존 + loading flash 제거
            사용자 피드백: "iOS는 가로/세로 전환 시 줌 유지 + 자연스러운 전환,
            Android는 줌 리셋 + '환율 정보 로딩중' 텍스트/spinner 노출".
            두 이슈를 iOS parity 관점에서 해소.
            5) rotation 줌 보존 (feat): pocVisibleDomain/pocIsFollowingLatest를
               rememberSaveable(period, ...)로 전환. ClosedRange<Long>? 직렬화를
               위한 VisibleDomainSaver(listSaver) 추가. LaunchedEffect(period)
               reset 블록에서 두 상태 리셋 라인 제거 — saveable inputs가 period
               변경 시 기본값 재초기화를 이미 처리하므로 중복 리셋 제거가 복원
               된 값을 파괴하는 것을 차단. iOS @State와 semantic 일치
            6) loading flash 제거 (fix): stop()/reset() 의미 분리 + loadInitialRates
               silent 분기. MainScreen onDispose는 stop()(transport-only), RootScreen
               로그아웃/premium 해제는 reset()(UI 초기화 포함)으로 배선. 이미
               Connected/Offline 상태에서 loadInitialRates()가 호출되면 Loading
               플래시 생략하고 silent refresh, 실패 시 기존 상태 유지. iOS
               ExchangeRateViewModel.stop()의 transport-only semantic과 align
            커밋 1c220d3 / ce4d1ee (기기 검증 통과: 줌 window 보존 확인,
            '환율 정보 로딩중' 플래시 미노출 확인)
            잔존 과제: 회전 시 REST `loadInitialRates()` 재호출 자체는 남음 —
            §9 polish 후보에 "rotation refetch skip" 항목 추가
2026-04-16  M5 post-polish 7번째: 회전 시 연결 배너 flicker 제거
            사용자 피드백: "화면 회전 시 전체 화면이 내려갔다 올라오는 현상 반복,
            상단 연결 정보 배너가 나왔다 들어가면서 생기는 현상". 6번(loading flash)
            제거 후에도 connectionState Disconnected→Connecting→Connected 전환이
            ConnectionStatusBanner의 AnimatedVisibility expand/shrink를 유발하여
            아래 콘텐츠 reflow가 계속됨.
            원인: MainScreen DisposableEffect onDispose가 회전에서도 발동하여
            webSocketService.stop()까지 호출. 6번은 UI state만 보존했고 transport
            자체는 stop→start 사이클이 유지됨.
            수정 (아키텍처 변경): 서비스 lifecycle을 MainScreen composable scope →
            session scope(RootScreen auth/premium)로 의도적 이동. onDispose에서
            stop 호출 두 개 제거, onCleared에 webSocketService.stop 추가.
            세션 teardown은 기존 RootScreen의 reset() 호출로 이미 커버.
            7번으로 iOS SwiftUI @State 생존 + WebSocket 연결 유지 parity 완결.
            커밋 cd468aa (기기 검증 통과: 배너 flicker 미노출 + 콘텐츠 reflow 없음)
2026-04-16  M5 post-polish 8번째: 회전 시 REST 재호출 생략 (2단계 보강)
            7번으로 WebSocket transport는 유지되지만 ExchangeRateViewModel.start()
            가 매 MainScreen 재진입마다 loadInitialRates()를 launch하여 REST 1회
            잔존. §9 polish 후보였던 "rotation refetch skip" 본격 해소.
            1차 (a7ec05e, steady-state): start()에 hasUsableData && Connected skip
            가드 추가. 코덱스 리뷰 반영으로 connectionState 단독이 아닌 곱조건 —
            WS 연결 수립 ≠ 첫 메시지 수신 edge case 방어.
            2차 (a3be016, in-flight): cold start REST 진행 중 회전 edge case를
            initialLoadJob: Job? in-flight 추적으로 차단. launchInitialLoadIfNotActive
            private helper로 추출, invokeOnCompletion으로 stale reference 정리.
            reset()/onCleared() 명시적 cancel로 logout/구독해지 직후 late response
            race 실무적 차단(cooperative cancellation 한계상 이론적 window 극미량
            잔존, generation token 패턴은 over-engineering이라 불채택).
            효과: 회전 시 REST 0회 확인. iOS parity 완결.
            커밋 a7ec05e + a3be016 (기기 검증 통과: REST 0회 확인, 회귀 없음)
            §9 polish 후보에서 strikethrough 및 "완료" 표기
2026-04-16  M5 post-polish 9번째: Sample shell에 M3-b fullscreen gesture policy 전파
            사용자 피드백: "LockedPreview 그래프 더블탭이 6h zoom이 아니라 전체
            화면으로 전환된다. 섹션 빈 곳 더블탭도 동일". Live는 이미 M3-b에서
            outer detectTapGestures를 제거해 RateGraphView 내부 M3-a 더블탭 zoom이
            도달하도록 했으나, Sample shell(LockedPreviewScreen)에는 전파 누락.
            1차 (2819fc7): 4곳 outer wrapper 모두 제거 (graph 2곳 + rates 2곳).
                초기 Phase 2 판단 — "Live rates에도 wrapper 없을 것"으로 Read 없이
                단정. feedback pattern 4(원인 확정 전 해결책 금지) 위반.
            2차 (7ef8633): Live CurrencyTabContent 재조사 결과 rates section 2곳에
                double-tap fullscreen wrapper가 실존 확인 ([L534-536, 399-401]). Sample
                의 rates wrapper 2곳 복구. 정책을 Live와 동일하게 재정립:
                  - Graph: 아이콘 전용 진입/종료, 섹션 더블탭 = RateGraphView 내부
                    6h zoom toggle
                  - Rates: 아이콘 + 섹션 더블탭 모두 fullscreen 진입/종료
            Net effect: Sample에서 그래프 관련 outer wrapper 2곳만 제거 (L600 /
            L988), rates 2곳은 Live parity 위해 유지.
            커밋 2819fc7 / 7ef8633 (기기 검증 통과: 그래프 6h zoom 복귀, 아이콘
            경로 정상, rates 더블탭 fullscreen 유지, 회귀 없음)
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
- `107ad8f` — docs(android): M3 완료 이력 반영 (§4 M3 + §9 iOS divergence + §11)
- `db7be63` — feat(android): M4-a DXY 경계 보간 + 라벨 0개 fallback (v2.3 parity)
- `0d7cd24` — feat(android): M4-c adaptive xTicks + 30분 보조 grid (v2.4 parity)
- `2497c8c` — feat(android): M4-b gesture y-lock + DISCARDED cleanup leak fix (v2.3 parity)
- `c8fa7c3` — docs(android): M4 완료 이력 반영 (§4 M4 + §9 DXY inset/y-lock divergence + §11)
- `bff1676` — feat(android): M5 더블탭 줌 애니메이션 (v2.5 실험적 parity)
- `3293bc7` — docs(android): M5 완료 이력 반영 (§4 M5 + §9 M5 divergence/polish 후보 + §11)
- `1862f89` — fix(android): gesture clamp에서 trailing buffer 제거 — 줌 상태 우측 빈공간 버그 수정
- `1dae300` — perf(android): computeRatePaths visible 필터 — pan 끊김 개선
- `b597246` — perf(android): yLock release 시 yRange 전환을 easeOut 250ms로 부드럽게
- `93679ee` — fix(android): double-tap 위치 slop을 AOSP 표준 100dp로
- `c431bf2` — docs(android): M5 post-polish 4종 이력 반영 (§4 M5 post-polish + §9 + §11)
- `1446a16` — fix(android): DXY 라벨 inset 제거 — iOS 라벨 생성 규칙과 align
- `d54b619` — docs(android): DXY 라벨 inset divergence 해소 반영 (§9 + §11)
- `1c220d3` — feat(android): graph 줌/follow 상태 rotation 보존
- `ce4d1ee` — fix(android): rotation 시 '환율 정보 로딩중' 플래시 제거
- `2f42d20` — docs(android): rotation 보존 + loading flash 제거 이력 반영 (§4 M5 post-polish + §11)
- `758a644` — docs(android): M5 post-polish 5·6번 기기 검증 통과 표기 + §9에 rotation refetch skip 후보 추가
- `cd468aa` — fix(android): rotation 시 연결 배너 flicker 제거 — 서비스 lifecycle을 session scope로 이동
- `a7ec05e` — perf(android): rotation 시 REST 재호출 생략 — start() idempotency 가드
- `a3be016` — perf(android): initial load in-flight 중 회전 시 REST 중복 launch 차단
- `f1a0192` — docs(android): M5 post-polish 7·8번 반영 — 배너 flicker 제거 + rotation refetch skip 해소 (§4 + §9 + §11)
- `c17a8d7` — docs(android): §11 작업 이력 numbering을 §4와 일관되게 정비
- `becf785` — docs(android): §11 current HEAD 마커 동기화 (c17a8d7 반영)
- `2819fc7` — fix(android): LockedPreviewScreen sample shell에 M3-b fullscreen gesture policy 전파 (4곳 제거 — 초기 판단)
- `7ef8633` — fix(android): LockedPreviewScreen rates 섹션 outer double-tap wrapper 원복 (Live parity 회복)
- **current HEAD** — docs(android): M5 post-polish 9번째 반영 — Sample graph shell M3-b 전파 + rates wrapper 원복 경위 (§4 + §11)
