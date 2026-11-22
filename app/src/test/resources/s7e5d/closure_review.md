# S7 E5d bytecode closure 검토 기록

근거: 합의 계획 r4 `e5d_gaphunt.claude.r4.md`(756445c6d56828c0a6eb8a8aa9d41f905af18ccf31842cc43f8d963b42a1a7ec) G13·G14 교체 D1. 시험은 `S7E5DBytecodeClosureTest`, 해석기는 `S7E5DClassClosure`다. 아래 목록 다섯이 이 기록으로 봉인된다. 목록이나 이 기록이 바뀌면 시험이 실패하고 다시 검토해야 한다.

| 목록 | SHA-256 |
| --- | --- |
| closure_app_classes.txt | 944eeb54cc04f64b0a46e1c722fb02ebd3ce4598b9714b96d2dc3769299a2442 |
| closure_uses.txt | 5b339cb0709a1c14c7c42aa717bb205bf4894942a3f707721eb7159fb285d91f |
| closure_callbacks.txt | 02fb15e0d4995fdb789be88740eaed71786901319d1cc1d92a934d34a5f953de |
| closure_sources.txt | 0f3ce1576136665053f5f1449f41a18792b187e245cd5ed33d4566ecf3227977 |
| closure_versions.txt | 7491fa63d0dab9261842f9b8f7d51e95f5191e381f25a717707614134a3a4faa |

이 목록은 참조 트리(`s7e5d-ref`)의 바이트에서 나왔다. land 트리의 독립 구현 바이트는 다르다. 그러므로 land 단계에서 그 바이트로 목록을 다시 만들고, 이 기록과 같은 항목으로 다시 검토해 봉인한다. PostS6 원장 재결속과 같은 단계다. 참조 봉인을 land 통과의 근거로 쓰지 않는다.

## 입력과 대응

- 해석 대상은 variant의 실제 runtime class다. main은 `build/intermediates/runtime_app_classes_jar/debug/.../classes.jar`, 시험 fixture는 debugUnitTest class 디렉터리다. 둘 다 시험 실행 직전의 compile 산출이다.
- 루트는 preview 소스 다섯에서 나온 class다. 같은 package에서 `SourceFile`이 그 소스인 class와, 이름으로 그 안에 중첩된 class(`Outer$…`) 전부다. inline 함수가 만든 객체(`…$$inlined$onDispose$1`)는 inline 함수의 `SourceFile`(`Effects.kt`)을 지니므로, 중첩 이름으로 잡고 바깥 class의 소스로 대응시킨다. 소스가 없는 대응은 `no-source`로 남아 봉인과 어긋난다. 이번 목록에는 없다.
- G12 Screen 배선 범위는 bytecode 루트에 넣지 않았다. 그 범위는 소스 수준 배선 검사(`S7E5DStructureTest` G12)가 데이터·제어 의존까지 본다. Screen class 전체를 루트로 하면 무료 화면 전부가 closure에 들어온다.

## app class (78개)

- preview 다섯 소스의 class: session·host·sheet·section·CTA·fields와 그 lambda, Compose 합성 class, coroutine 본문 class.
- 순수 editor model: `BankAlertEditor`·`BankAlertEditorInput`·`BankAlertEditorCheck`·`BankAlertEditorMode`·`BankAlertSubmit`·`BankAlertRepeatTexts`·`BankAlertThresholdTone`·`AlertConditionExtensionsKt`, 그리고 tag 객체 `AlertAddSheetTags`(소스 `AlertAddSheet.kt`).
- domain model과 DTO: `Bank`·`AlertCondition`·`AlertSetting`·`ExchangeRate`·`FreeTab`·`RepeatInterval`·`SupportedCurrency`·`RepeatIntervalChange`, 그리고 그 serializer와 `InstantSerializer`. 값과 직렬화뿐이다.
- `BankAlertDraft`·`BankAlertEdit`: `BankAlertEditor.submit`이 만드는 값 class다. class 단위 closure라서 submit 경로가 함께 들어왔다. 소스는 `BankAlertSettingsOwner.kt`이지만, owner class 자체(`BankAlertSettingsOwner`)는 closure에 없다. 이 소스 hash가 봉인에 들어가므로 owner 파일이 바뀌면 이 검토가 다시 열린다.
- theme: `ColorKt`·`RateLayoutMetrics`. 색 값과 layout 값이다.
- host의 test seam이 받는 읽기 전용 값 class 둘: `PreviewHostContext`(host·session의 불투명 식별자와 그 session이 적용한 binding)·`PreviewSignalOrigin`(signal이 발행된 session·binding·token). 개정 3.
- D26이 0으로 두는 capability class는 0이다. 시험의 `FORBIDDEN_APP`이 이를 강제한다.

## library 사용 (335개, 종류·owner·member·descriptor)

모두 읽었다. 묶음별 판단은 다음과 같다.

- Compose ui·runtime·foundation·material·material3·animation(175): layout·text·shape·graphics·painterResource·testTag·icon·ModalBottomSheet·SheetState·DropdownMenu·Button·Divider·Text·Icon, composer·effect(DisposableEffect·LaunchedEffect의 `createCompositionCoroutineScope`)·state·CompositionLocal 읽기다. saveable·`LocalContext`·`LocalUriHandler`·clipboard·focus·haptic·window·lifecycle 사용은 0이다.
- JDK(53): `Object`·`String`·`StringBuilder`·박싱·`Math.abs/floor`·`Double` 판정·`Enum.valueOf/clone`(enum의 표준 생성 코드)·`LinkedHashMap` 복사·`CharSequence`·`Iterable/Iterator/Collection/List/Map/Set`·`NumberFormat`·`Locale.KOREA`·예외 생성자. 입출력·network·thread·reflection은 0이다.
- Kotlin 표준(43): collections·ranges·text·Pair·Lazy·enum entries·intrinsics·coroutine intrinsics·`SuspendLambda`·함수 interface.
- kotlinx.coroutines: `launch`·`ensureActive` 둘. kotlinx.datetime: `Instant`의 parse·toString·hashCode. kotlinx.serialization(37): domain model serializer의 생성 코드이며 encode·decode뿐이다.
- 나머지: kotlinx.coroutines 2, kotlinx.datetime 4, kotlinx.serialization 37, app의 SAM 1, resource 20. 합 335.
- invokedynamic: `LambdaMetafactory`(lambda, SAM은 `FunctionN#invoke`와 `FreeAlertPreviewOpener#open`)와 `StringConcatFactory` 둘만 있다. 다른 bootstrap과 `CONSTANT_Dynamic`은 0이다(있으면 해석기가 풀리지 않은 것으로 보고한다).
- resource: `R$drawable`의 은행 아이콘 20개(BankIcon).

## 함수값 호출 지점 (17개)

모두 preview가 받은 함수값을 부르는 곳이다. 연결은 참조 소스에서 확인했다.

| 지점 | 부르는 것 |
| --- | --- |
| `BankAlertEditorFieldsKt.BankAlertEditorFields$lambda$8…` 넷 | `onInput`(은행 선택·임계값·조건·반복). preview에서는 host의 `session.edit(token, …)`이다. |
| `BankAlertEditorFieldsKt$SegmentedBankGrid$1…` | 은행 칸의 `onBankSelected` → 위 은행 선택 |
| `BankAlertEditorFieldsKt$RepeatSection$1$4…` | 반복 메뉴의 `onRepeatSelected` → 위 반복 |
| `FreeAlertPreviewSessionKt.FreeAlertPreviewHost(…)` Function4 | host의 `content(opener)`. Screen이 넘긴 composable이며 G12가 본다. |
| `FreeAlertPreviewHost$1$1` Function1 | `hide` seam의 기본값 `{ it() }`이 실제 hide를 부른다. |
| `FreeAlertPreviewHost$10$1` Function0·Function1·Function2 | 전달 effect의 `onSubscribe`(Root, 제거 뒤 유효할 때만), `onEvent("delivered …")`, `beforeDeliver` seam(개정 3부터 signal의 origin을 받으므로 Function2) |
| `PreviewSheet$finish` Function1 | `onSignal` → host의 `signal` 상태 |
| `PreviewSheet$lambda$31$lambda$30` Function1 | `onEvent("shown …")` |
| `PreviewSheet…$$inlined$onDispose$1.dispose` Function1 | `onEvent("removed …")` |
| `FreeAlertPreviewHost$lambda$14$lambda$13` Function1 | `onContext(PreviewHostContext(…))`: invalidate 뒤 매 composition의 문맥 보고(개정 3) |
| `FreeAlertPreviewHost…$$inlined$onDispose$1.dispose` Function1 | `onContext(null)`: host 이탈(개정 3) |
| `PreviewSheet$requestHide$1` Function2 | `hide` seam(실제 hide closure를 받는다) |

닫기·CTA·section 진입의 `onClick`은 함수값을 Compose component에 넘기는 곳이다. preview 코드가 부르지 않으므로 호출 지점이 아니다. 무엇을 넘기는지는 소스 수준 구조 시험과 기기 시험이 본다.

## static 쓰기와 풀리지 않은 것

둘 다 0이다. 자기 class 초기화 밖의 static 쓰기(top-level var·object 속성·다른 class field)와, 풀리지 않은 참조(app class 파일 부재·모르는 bootstrap·`CONSTANT_Dynamic`)가 생기면 시험이 실패한다.

## 의존 버전

`gradle/libs.versions.toml` 전체 hash로 결속했다. 어느 의존이 바뀌어도 다시 검토한다.

## 개정 1 (2026-11-16 06:07 KST)

- 기기 첫 실행(`dev/e5d_dev1.*`)에서 host의 열림→닫는 중 전이가 시트를 새로 만드는 결함이 드러났다(`when` 두 분기의 호출이 서로 다른 group). `FreeAlertPreviewHost`가 `PreviewSheet`를 한 곳에서 부르고, 열림·닫는 중의 차이(`enabled`)는 `PreviewSheet` 안의 비-composable `when`이 정하도록 고쳤다. 새 class는 없다.
- 그 결과 lambda class 두 개의 번호만 바뀌었다(`FreeAlertPreviewHost$8$1` → `$7$1`, `PreviewSheet$lambda$24$lambda$23` → `$lambda$23$lambda$22`). `closure_uses.txt`·`closure_versions.txt`는 그대로, class 75·use 335·callback 15도 그대로다. 위 표의 해시 셋과 대응 표의 이름 둘을 갱신했다.

## 개정 2 (2026-11-18 19:13 KST)

- 근거: 합의 계획 r3 `e5d_devfix_plan.claude.r3.md`(77163efa527add4cf675bee56bbf5efdf4c50363585cdb3eacf8ddb53bb99686) X1·X6. 기기 3차에서 teaser의 펼침이 lazy item 안 `remember`라서 만료(위쪽 item 증감)와 스크롤 이탈로 사라졌다(`dev/e5d_probe3.out.txt`). 펼침을 page가 갖도록 옮겼다.
- 바뀐 바이트: `FreeBankAlertPreviewSection.kt`는 `expanded: Boolean`과 `onToggle: () -> Unit`을 받고 toggle의 `clickable(onClick = onToggle)`로 넘긴다. 자기 `remember`와 그 import 넷은 없어졌다. section 소스 SHA-256 7a9945fd453d226c1ad872544ba0884958e4ad2d2cccacde632383e9c39036c3.
- 재생성 결과를 본 뒤 봉인했다. 다섯 목록 중 `closure_sources.txt`의 section 줄만 바뀌었다. app class 75·library 사용 335·함수값 호출 지점 15는 그대로이고, class 이름 변화도 없다. `onToggle`은 preview 코드가 부르지 않고 Compose의 `clickable`에 넘기기만 하므로 호출 지점이 아니다(위 "닫기·CTA·section 진입의 `onClick`"과 같은 경우). 위 표의 `closure_sources.txt` 해시 하나를 갱신했다.
- 수동 연결: `onToggle`은 이제 section 밖에서 오는 함수값이다. Screen의 `FreeSnapshotTabContent`가 LazyColumn 밖, 조건 없이 `var previewExpanded by remember { mutableStateOf(false) }`(plain remember, 저장 0)로 상태를 갖고, item에서 `FreeBankAlertPreviewSection(expanded = previewExpanded, onToggle = { previewExpanded = !previewExpanded }, onOpen = onOpenPreview)`로 넘긴다. toggle은 그 Boolean만 뒤집는다. 최종 Screen 소스 SHA-256 b6aa3f72ba57a628fd7684f4a310daf7ed5eac37de7cf6838307fb6ceec042f8.
- Screen은 이 closure의 루트 다섯에 없다. 따라서 이 재봉인은 새 상태의 비영속성이나 부작용 부재를 검증하지 않는다. 그것은 `S7E5DStructureTest` G12의 `preview-state` 범위·`stateWiring`(선언 하나·위치·초기화의 허용 호출 `remember`·`mutableStateOf`·사용처는 section 인자뿐)과 그 음성 fixture 여섯, 기기 S03(복원 뒤 접힘)·S04(만료 뒤 유지)·S07(스크롤 왕복 뒤 유지)이 맡는다.

## 개정 3 (2026-11-18 22:05 KST)

- 근거: E5d-J J2 계획 r3 `e5dj_j2_plan.claude.r3.md`(190a20a90cb2e9c8184d24ce57633843618d013129ad4eddad8032720736d5c9)의 C1(C1_B_REQUIRED)·C3-1. fixture가 실제 host·session·binding과 signal의 origin을 대조하려면, 운영 seam이 그 값을 읽기 전용으로 넘겨야 한다(사건 순서로는 Closed에서의 binding 변경을 볼 수 없다).
- 바뀐 바이트(J1b):
  - `FreeAlertPreviewSession.kt`: session에 불투명 `identity`와 읽기 전용 `currentBinding`. host에 `onContext: (PreviewHostContext?) -> Unit` seam을 더한다. invalidate 뒤 매 composition에 `PreviewHostContext(hostKey, session.identity, session.currentBinding)`을 보고하고, host가 떠나면 null을 보고한다. `beforeDeliver`는 `PreviewSignalOrigin(wanted.origin.identity, wanted.binding, wanted.token)`을 받는다. 소스 SHA-256 dd53db2426b72b7a5affa37d5e0301eb883157e7b552c28e87daa0478f55f3af.
  - `FreeSnapshotScreen.kt`: `PreviewHostHooks`에 `beforeDeliver(origin)`·`onContext`·`onPage`, no-op 구현, host에 `onContext = hooks::onContext`. `FreeSnapshotTabs`→`FreeSnapshotTabContent`에 `onPage = hooks::onPage`를 넘긴다. page의 composition마다 `PreviewPageMount(tab)` 하나와 `DisposableEffect`의 mount·dispose 보고. 소스 SHA-256 21dc18ebfde460cc3dbd6b530e902f5d0d2cfde6a552c7a151fb52e19bfe177d.
- 재생성 결과를 본 뒤 봉인했다. app class 75→78(`PreviewHostContext`·`PreviewSignalOrigin`·host의 새 onDispose 객체, 나머지는 lambda 번호 이동), library 사용 335 그대로, 함수값 호출 지점 15→17이다. 새 지점은 둘이다: `onContext` 보고, host 이탈 때의 `onContext(null)`. 전달 effect의 `beforeDeliver`는 Function1→Function2다. 위 표의 해시 셋과 대응 표의 이름·행을 갱신했다.
- 수동 연결:
  - Screen이 `hooks::beforeDeliver`·`hooks::onContext`·`hooks::onPage`를 넘긴다. 출하·benchmark type의 accessor(`src/e5dHooksDefault/java`)는 `NoPreviewHostHooks`이고, 세 메서드는 모두 `Unit`이다.
  - page seam은 Screen 안에 있어 이 closure의 루트가 아니다. 대신 `S7E5DStructureTest`의 `seamWiring`(P 시험이 부르는 `reach`의 일부)이 범위로 검사한다. 대상은 다음과 같다.
    - 두 단계 onPage 전달(host → `FreeSnapshotTabs` → `FreeSnapshotTabContent`).
    - plain remember mount 하나와, 그 선언 바로 뒤에 공백만 두고 이어지는 effect. 그래서 사이에 중괄호 없는 조건을 끼울 수 없다. 그 묶음의 중괄호 소유 범위(`previewExpanded`와 같은 본문)와 LazyColumn 앞 위치도 본다.
    - 같은 mount의 mount/dispose 보고, no-op 구현의 정확한 본문.
    - host setup 여섯 문장(`val session` 선언부터 철회까지)의 연속 묶음. 그래서 조건부 실행과 순서 변경을 거부한다.
    - signal origin의 문자열과 생성 횟수.
  - 음성 fixture 열여섯(`P_theSeamWiringCatchesEachBreak_andLeavesTheRestAlone`, 중괄호 없는 조건 넷 포함)이 각 위반을 겨눈 tag로 잡아야 그 시험이 통과한다. 이 재봉인으로 page seam의 비영속성·부작용 부재를 검증했다고 쓰지 않는다.
  - host 쪽 새 seam의 실제 동작(Closed에서의 binding 이동, 새 owner의 새 session, 이탈 때 null, 보류된 전달의 origin)은 기기 H17이 맡는다.

## 개정 4 (2026-11-20 01:42 KST)

- 근거: push(`2c82da32`) 뒤 CI는 `testDebugUnitTest`의 `E5dJourneyStructureTest`에서 실패해 나머지 unit 세 변형을 실행하지 않았다. 무수정 같은 HEAD의 로컬 CI parity r0에서 이 closure 시험이 `testCiMinifiedUnitTest`·`testBenchmarkUnitTest`·`testS5BenchmarkUnitTest`에서 실패했다. 위 목록은 debug variant의 class에서만 만들고 봉인했다(위 '입력과 대응'의 `…/debug/…`).
- 원인: class가 쓰는 library member는 그 variant가 compile하는 library 판을 따른다. catalog가 같아도 판은 build type마다 다르다. debug만 `debugImplementation`으로 ui-tooling·ui-test-manifest를 더하고, debug의 compile classpath는 Compose foundation 1.9.2로 풀린다(`dependencyInsight`: "version resolved in configuration ':app:debugRuntimeClasspath' by consistent resolution", 그 runtime 경로에 ui-tooling-android 1.9.2). ciMinified·benchmark는 1.7.2다(BOM 2024.09.00). 그래서 위 '의존 버전'의 catalog hash 결속은 variant별 해석까지는 잡지 못한다.
- 비교: app class·함수값 호출 지점·소스·버전 목록은 debug와 benchmark·s5Benchmark가 같다. 다른 것은 library 사용 335개 중 둘뿐이고, benchmark와 s5Benchmark의 사용 목록은 바이트가 같다.
  - debug `ClickableKt.clickable-oSLSa3U$default`(`MutableInteractionSource` 인자가 있는 판) ↔ release 계열 `ClickableKt.clickable-XHw0xAI$default`(그 인자가 없는 판). 같은 `Modifier.clickable(…)` 호출이 판에 따라 다른 overload로 묶였다.
  - debug `ComposablesKt.getCurrentCompositeKeyHashCode (…)J` ↔ release 계열 `ComposablesKt.getCurrentCompositeKeyHash (…)I`. inline된 foundation 본문의 판 차이로 보인다(추정, 호출 지점을 따로 추적하지 않았다).
  - 둘 다 Compose foundation·runtime의 member이고 새 owner·package·capability가 아니다.
- 봉인: library 사용 목록을 build type별로 둔다. debug는 `closure_uses.txt`(그대로), ciMinified·benchmark·s5Benchmark는 `closure_uses.release.txt`다. 목록에 없는 build type은 봉인이 없어 실패한다. 판이 바뀌어 사용이 달라지면 그 build type에서 실패하고 다시 검토한다. 네 build type 모두를 확인하려면 unit 시험 네 변형을 모두 돌려야 한다(CI workflow는 넷을 순서대로 호출하지만, 같은 run 블록의 앞 명령이 실패하면 뒤 명령은 실행되지 않는다).

| 목록 | SHA-256 |
| --- | --- |
| closure_uses.release.txt | 12609f052a1b3a0d240aa8441823b0914cf8a4f2ca425c999df200ca2db0cdb6 |

- 범위 밖: 현재 parity r0의 dependencyInsight에서 debug compile은 foundation 1.9.2, ciMinified·benchmark compile은 1.7.2다. s5Benchmark 사용 목록은 benchmark와 바이트가 같다. 이 개정은 의존 판을 바꾸지 않으며, debug에서 수행한 기기 시험을 release 계열 의존 판의 동작 증거로 확대하지 않는다.

## 개정 5 (2026-11-22 17:04 KST)

- 근거: S8-5c 범위 합의 `R4c/S8/s8_5c_scope.agreed.record.r1.md`(8932b861e56a63c32bbbcdcfc018e6ce9859b2d30e802bef9934e4a1d06b7bbc). 은행 toggle 접수가 그보다 먼저 시작한 GET을 무효화하도록 `BankAlertSettingsOwner.requestRow`의 toggle 분기에 `revision++` 한 줄(과 주석 한 줄)을 넣었다. 최종 owner 소스 SHA-256 c1b861daa1e4458198210d69cb7b6dc42b677de509a6b7a759a1625bc705ede5.
- 위 '입력과 대응'의 `BankAlertDraft`·`BankAlertEdit` 항목대로, closure에 든 것은 같은 소스의 두 값 class이고 owner class 자체는 여전히 없다. 바뀐 줄은 owner class의 private 함수 안에 있어 두 값 class의 바이트와 무관하다.
- 재생성 결과(debug, `R4c/S8/s8_5c_impl/t1/closure_report/`)를 본 뒤 봉인했다. 다섯 목록 중 `closure_sources.txt`의 owner 줄만 바뀌었다. app class·library 사용(debug)·함수값 호출 지점·버전 목록은 봉인과 바이트가 같고, 풀리지 않은 것과 초기화 밖 static 쓰기는 0이다. 위 표의 `closure_sources.txt` 해시 하나를 갱신했다. release 계열 사용 목록(`closure_uses.release.txt`)은 unit 시험 세 변형에서 다시 확인한다.
