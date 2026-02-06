# Canvas Graph Design (Android)

## Goal
Build a 24-hour rate chart in Jetpack Compose Canvas that matches iOS Swift Charts as closely as possible. The chart must support a true min~max band for single-source mode and time-based X-axis formatting identical to iOS.

## Scope
- Replace the current `RateGraphView` (Vico-based) with a Canvas-based implementation.
- Keep data loading, caching, source toggles, and fullscreen state unchanged.
- No zoom/scroll/marker interactions in this phase.

## Non-Goals
- Advanced interactions (pinch zoom, crosshair, marker tooltips)
- Accessibility/TalkBack enhancements
- Animations beyond basic transitions

## Inputs
- `graphData: Map<GraphSource, List<GraphBucket>>`
- `selectedSources: Set<GraphSource>`
- `isLoading: Boolean`

## Data Preparation
1. Sort buckets by `bucketTs` ascending per source.
2. Compute X domain:
   - `xMin = first.bucketTs` (across all selected sources)
   - `xMax = last.bucketTs + 1800` (30-minute trailing buffer)
3. Compute Y domain (iOS 동일 로직):
   - **close 값만 사용** (min/max 아님) — iOS `yAxisRange`와 동일
   - 5% margin: `rangeMin = minClose - (maxClose - minClose) * 0.05`, `rangeMax = maxClose + (maxClose - minClose) * 0.05`
   - ±0.8 padding: `yMin = rangeMin - 0.8`, `yMax = rangeMax + 0.8`
   - 밴드(min/max)가 Y domain 밖으로 나갈 수 있으므로, 렌더링 시 **clipRect** 적용 필수

## Coordinate Mapping
Define chart area excluding axis label padding:
- `chartLeft`, `chartTop`, `chartRight`, `chartBottom`

Mapping formula:
```
x = chartLeft + (ts - xMin) / (xMax - xMin) * chartWidth
y = chartBottom - (value - yMin) / (yMax - yMin) * chartHeight
```

## Layout & Padding
- Right padding (Y labels): 40dp
- Bottom padding (X labels): 16dp
- Left padding: 8dp
- **Inner plot padding**: top+bottom 12dp (상단/하단 동일)

Graph height: 170dp (현재 Android 고정값 유지, 필요시 추후 가변화)

## X-Axis
- Tick cadence: every 3 hours KST (00, 03, 06, 09, 12, 15, 18, 21)
- Tick 생성: xMin~xMax 범위 내 KST 기준 3시간 정각 timestamp 목록
- Label formatting (KST):
  - If hour == 0: `M/d` (예: 1/30)
  - Else: `HH` (예: 04, 08, 12)
- Hide labels if:
  - `labelTs > lastDataTs` (마지막 데이터보다 뒤)
  - `lastDataTs - labelTs < 600` (마지막 데이터와 10분 미만)
- Grid lines: **모든 tick에 수직선** (레이블 숨김 여부와 무관)
- Grid style: 두께 0.5dp, 색상 `SecondaryText.copy(alpha = 0.3f)`
- Label placement: chartBottom 바로 아래 (iOS `AxisValueLabel(anchor: .top)` = 레이블 상단이 축선에 정렬, 텍스트는 아래로)

## Y-Axis (Right)
- Tick 수: iOS 출력과 유사하게 **낮은 개수 중심(대략 3~4개)**으로 유지 (기기 높이에 크게 의존하지 않음)
- Label format: 소수점 1자리 (`"%.1f"`)
- 위치: chartRight 우측에 4dp 간격
- Font: 11sp, `fontFeatureSettings = "tnum"` (tabular/monospaced digits, iOS `.caption2` + `.monospacedDigit()` 매칭)
- 색상: `SecondaryText`
- Grid lines: 수평선, 두께 0.5dp, 색상 `SecondaryText.copy(alpha = 0.3f)`
- 최상단/최하단 tick(=yMax/yMin)은 그리드/레이블 **렌더링에서 제외**하여 상단/하단 불필요한 축선이 생기지 않게 함

### Y-Axis Tick Algorithm (Nice Numbers)
목표: "Swift Charts 내부 구현 복제"가 아니라, 현재 iOS 출력 패턴을 안정적으로 재현.
1. **고정 tick count 힌트**  
   - `desiredCountHint = 4`
2. **nice step 계산 (표준 1/2/5/10 × 10^n)**  
   - `rawStep = (yMax - yMin) / desiredCountHint`
   - `niceStep ∈ {1, 2, 5, 10} × 10^n`
   - 선택은 기하평균 임계값 기반 (d3-array의 tickStep과 유사한 방식)
3. **niceMin / niceMax**  
   - `niceMin = floor(yMin / niceStep) * niceStep`  
   - `niceMax = ceil(yMax / niceStep) * niceStep`  
4. **tick 생성**  
   - `ticks = niceMin..niceMax step niceStep`

## Rendering Order
1. **clipRect** 적용 (차트 영역 내부로 제한)
2. Grid lines (vertical + horizontal)
3. Band (single source only):
   - Path: max 값으로 좌→우, min 값으로 우→좌, close()
   - Fill: source color alpha=0.35
4. Close line(s):
   - Stroke width: **1.5dp** (iOS `lineWidth: 1.5` 매칭)
5. clipRect 해제 후 Axis labels 렌더링

## Single Source Mode (Band)
- Build Path: max values left→right, then min values right→left, close()
- Fill with `source.color.copy(alpha = 0.35f)`
- Draw close line on top (1.5dp, source color)

## Multi Source Mode
- Draw close lines only (no band).
- Sort sources by `GraphSource.ordinal` to ensure consistent draw order.
- Each line: 1.5dp, source.color

## Loading / Empty States
- Loading: spinner (`SecondaryText` 색상) + "그래프 로딩 중..." (iOS 동일)
- Empty: chart icon + "그래프 데이터가 없습니다" (iOS 동일)

## Text Rendering
- Use `TextMeasurer` for label sizing and rendering.
- Axis labels: 11sp with `fontFeatureSettings = "tnum"` (iOS `.caption2` + `.monospacedDigit()` 매칭)
- Loading/empty text: 12sp 수준(= iOS `.caption`에 대응)
- 색상: `SecondaryText`
- KST timezone (`TimeZone.of("Asia/Seoul")`) for all label formatting.

## Performance
- Cache computed paths and coordinates using `remember` keyed by `graphData` and `selectedSources`.
- 144 points × 3 sources = 432 points max — Canvas per-frame에 충분히 가벼움.

## Integration Plan
- `RateGraphView` 내부를 Canvas 기반으로 교체 (composable 이름 유지 가능)
- `CurrencyTabContent` 호출부 변경 없음

## Validation Checklist
- [ ] Single source: band area reflects min~max properly (클리핑 정상)
- [ ] X-axis: 3-hour KST ticks, 00시 날짜(M/d) 표시, 마지막 10분 이내 레이블 숨김
- [ ] X-axis: trailing buffer 30분 여백 존재
- [ ] Y-axis: 오른쪽 배치, 소수점 1자리
- [ ] Grid: 0.5dp, SecondaryText 30% opacity
- [ ] Line: 1.5dp 굵기
- [ ] Loading: SecondaryText 스피너 + 텍스트
- [ ] Empty: 아이콘 + 텍스트
- [ ] Visual parity with iOS (색상, 두께, 여백 비교)

## Resolved Questions
- **Graph height**: 170dp 고정 유지 (현재 Android 값, 추후 가변화 가능)
- **Y-axis tick count**: 초기 4~5개 고정, 추후 nice numbers 알고리즘 적용 가능
- **Font**: `fontFeatureSettings = "tnum"` 적용 (tabular numbers)
- **Spinner color**: `SecondaryText` (iOS와 동일, 현재 Android의 `Primary`에서 변경)
