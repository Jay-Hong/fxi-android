# Android v2.0.0 계획

```
Status                        : **SCOPE-FROZEN (2026-08-31)** — 범위·D-결정·슬라이스 경계·게이트 확정. 활성 미결정은 O1(비차단) 하나
동결 source baseline       : exchange-rate `2cb6624` · ios `a36682f` · android `676f320` (채집 당시 tracked worktree clean; 본 문서 2개는 docs 동결 커밋 `6cea639`에서 tracked 전환)
동결 기준 장비             : Samsung SM-F711N / Android 15(API 35) / OPENED / 120Hz (D31)
문서 identity              : docs 동결 커밋 **`6cea639`**이 확정한다 — 이후 개정은 개정 절차 기록과 함께
Document authorization        : GO — 문서 작성·보정 승인 (2026-08-30)
Plan approval                 : **APPROVED (2026-08-31)** — 이 계획을 작업의 기준으로 채택. 범위 재논의 종료,
                                개정은 아래 개정 절차로만. **슬라이스 착수는 여전히 개별 GO**(§7 도입부 체인)
Android implementation        : **S0 COMPLETE · S1·S2 부분구현 · S1.5 주요 구현 land · S3 진행 중 (2026-09-09)**.
                                구현 커밋의 land는 슬라이스 완료가 아니다 — 아래 미충족 항목을 함께 읽을 것.
                                S3는 a~k-1 land(`4f50b64`). 남은 S3 = k-2 lease · k-3 D14 침묵 ·
                                REST bootstrap · topic last-known disk · FX cutover. 구독자 목적지는 아직
                                `PremiumUnavailableScreen`이고 FX cutover가 그것을 치운다.
                                S0 근거: 구현·hosted CI `b477c22` / run `33496421777` green, current-runner S0-f·S0-g
                                실기기 evidence도 `b477c22`에서 validator green
S1 미충족                     : `UnimplementedScopePurger`가 `Deferred` 반환(실제 purge 없음) · 접근 판정의
                                `AccessEffect.PushDelete`는 `lastEffects` 기록만 · backup XML은 exclude 둘뿐이고
                                D27의 파일 단위 allowlist(+`domain="sharedpref"`)는 미구현.
                                **유예 사유는 셋이 서로 다르다** — purge는 대상이 아직 legacy rate/graph store,
                                PushDelete는 **서버 D21 순서 계약 미결**, backup은 기존 사용자 설정의 restore 동작
                                변경을 분리한 것. 이 기록은 완료 선언도, 후속 슬라이스로의 이관 승인도 아니다.
                                명시적 거부 저장 실패의 S1 잔여 중 S1r-1·S1r-2b·S1r-2a는 land했고, 이번 변경에 S1r-2c
                                구현을 포함한다. 봉인·재확인 요구·손실 후보 보류의 process death 지속은 미충족이다
                                (동결 후 8·9·10번 기록)
S1.5 확인 필요                : presenter·순서/표시 설정·legacy bank store 삭제는 land. `RateSourceRegistry`와
                                live/snapshot renderMode 등가까지 포함한 전체 DoD 충족은 미확인
S2 미충족                     : D26 무료 알림 in-memory 미리보기 미구현 · 마지막 탭은 `(owner_uid, last_tab)` 한 쌍이라
                                계정 간 혼입은 막지만 **§7 S2 DoD의 "UID별 마지막 탭 복원"은 미충족**
                                (`FreeTabStore` KDoc은 의도적 선택이라 적지만, 그것이 DoD를 완화하지는 않는다)
Server implementation         : NOT STARTED — SV 슬라이스 착수 GO 대기. SV-1/SV-2는 공개 rollout 차단 조건
S0 source / evidence / deploy  : source `b477c22`는 `origin/main` 반영 · current-runner evidence subject `b477c22` · deploy 0
Public release arming          : NOT APPROVED (계약은 D24)
Android public rollout        : BLOCKED by SV-1 / SV-2 production 배포·검증
iOS common server gate        : SV-1 / SV-2
Non-blocking architecture     : SV-0 중앙 evaluator 전환(O1) · SV-3 알림 전달 신뢰성 hardening(DEFERRED) · SV-4 cross-device 계정삭제 hardening
작성                          : 2026-08-29
최종 보정                     : 2026-09-09
```

> **`Plan approval`은 구현 착수 승인이 아니다.** 계획을 기준으로 채택했을 뿐이고, 각 슬라이스 착수에는 별도 GO가 필요하다.
>
> **동결이 뜻하는 것.** 범위(§1)·결정(§5.1)·슬라이스 경계(§6·§7)·게이트(§10)는 확정이며 재논의 대상이 아니다.
> 승인 상태와 구현 권한은 분리한다 — `Plan approval`은 구현을 자동으로 열지 않는다.
> S0는 별도 GO로 완료됐고, S1 이후에는 §7 도입부의 슬라이스별 GO 체인이 그대로 집행된다.
>
> **동결 후 변경은 두 종류뿐이다.**
> 1. **사실 오류 정정·비의미 명확화** — 코드 대조로 틀린 것이 확인된 서술(파일:줄 좌표, 코드 동작 묘사, 오탈자)이나
>    기존 의미를 바꾸지 않는 설명 보강. 범위·결정·게이트·DoD를 바꾸지 않는다. 별도 GO 없이 고치되 무엇을 왜 고쳤는지 기록한다.
> 2. **명시적 개정** — 범위·D-결정·게이트·DoD·미결정 상태를 바꾸는 모든 변경. **사용자 GO가 선행**하고, 바꾼 항목과
>    사유를 함께 기록한다. 릴리스·구현 현장에서 묵시적으로 완화하지 않는다(성능 gate는 §S11이 이를 이미 명시).
>
> **계획 변경이 아닌 구현 세부는 제3의 개정 유형이 아니다.** 동결된 범위·D-결정·슬라이스 경계·게이트·DoD를 바꾸지 않는
> 라이브러리 배선, 클래스/파일 배치, 테스트 내부 구조 같은 선택은 §7의 해당 슬라이스 승인 범위에서 구현하고 evidence에 남긴다.
> 그 세부를 이유로 계획 문구를 고쳐야 할 때 의미가 불변인 설명 보강만 1번이며, 완료조건이나 정책 의미가 바뀌면 2번이다.
>
> 어느 쪽인지 애매하면 **2번으로 처리한다**(false positive가 false negative보다 싸다).
> 검증 절차는 리포 표준(`../exchange-rate/CLAUDE.md` "코드/문서 변경 검증 게이트")을 따른다.
>
> **동결 후 명시적 개정 기록(사용자 수정 권한 위임, 2026-08-31).** 현재 Mac+SM-F711N은 수동 계측 전용으로 두고,
> build CI의 재현성·상시 가용성은 GitHub-hosted Linux/JDK 17이 소유하도록 S0 runner와 DoD를 확정했다.
> 함께 후속 문서에서 회수된 15분 후보값의 Android/서버 S5 이름 충돌을 제거하고, 구현 세부와 계획 개정의 경계를 명문화했다.
> 제품 범위·D-결정·슬라이스 경계·release gate는 바꾸지 않았다.
>
> **동결 후 1번 비의미 상태 명확화 기록(2026-09-01).** 별도 GO로 수행한 S0 구현과 GitHub-hosted CI run
> `33496421777`은 `b477c22`에서 green이다. 이후 `b477c22`가 두 실기기 runner의 임시 source/Gradle/staging 경로를
> 바꾼 데 따른 current-runner 재봉인도 S0-f `launcher-smoke-b477c22-r1`과 S0-g
> `release-admission-b477c22-r1`에서 각각 validator green이다. 기존 `8c18667`·`31df3b9` bundle은 역사 evidence로
> 보존한다. 이 상태 갱신은 범위·D-결정·게이트·DoD·활성 미결정 O1을 바꾸지 않았고, S1 착수와 public
> arming·rollout·deploy를 열지 않았다.
>
> **동결 후 2번 명시적 개정 기록(Claude·Codex 이중 합의, 사용자 위임 2026-09-08).** §7 S3의
> "최초 send 시점 기준 절대 45초 delivery deadline"이 두 가지로 읽혔다 — (a) 시도별 앵커,
> (b) 최초 송신부터 재시도 전체를 묶는 상한. §2.2의 "클라이언트 운용정책은 iOS 기준 SHA를
> 출발점으로 삼는다"에 따라 **(a)로 확정**했다. 동결 SHA `a36682f`의 iOS는 송신 직전에 두
> deadline을 새로 앵커하고 재시도에 이전 deadline을 넘기지 않으며
> (`WebSocketService.swift:533-561`), `testNoResponseStopsAfterExactlyThreeAttemptsIncludingTheFirst`가
> 최초 포함 3회를 기대한다. 전체 45초 상한을 도입할 제품 요구나 확인된 iOS 결함은 이번 대조에서
> 찾지 못했다. 원문이 (a)만 뜻했다는 증명은 아니다 — "ACK가 와도 45초를 다시 시작하지 않는다"는
> (b)도 만족하고, 새 `request_id`가 새 시간 앵커를 함의하지도 않으며, "3회 또는 45초 중 먼저"는
> 그 자체로 모순 없는 정책이다. 그래서 위 "애매하면 2번으로 처리한다"에 따라 1번이 아니라 2번으로
> 기록한다. 아래 §7 S3 본문과 DoD를 함께 고쳤고, 3회 시도 예산·슬라이스 경계·release gate는
> 바꾸지 않았다.

>
> **동결 후 3번 비의미 상태 명확화 기록(2026-09-09).** 머리말의 진행 상태가 "S0 COMPLETE · S1 착수 GO 대기"에
> 멈춰 있어 현재 위치를 설명하지 못했다. S1·S1.5·S2의 구현이 land했고 S3는 a~k-1까지 `origin/main`에 있다.
>
> **구현 커밋의 land는 슬라이스 완료가 아니다.** 그래서 머리말은 land와 미충족을 나눠 적는다 — S1은 실제
> purge·`AccessEffect.PushDelete` 실행·D27 파일 단위 allowlist가, S2는 D26 in-memory preview와 §7 S2 DoD의
> "UID별 마지막 탭 복원"이 미충족이다. S1.5는 주요 구현이 land했으나 전체 DoD 충족은 미확인이다.
> 코드에 적힌 세 유예 사유는 서로 다르므로 각각 적는다 — purge는 대상이 아직 legacy rate/graph store,
> PushDelete는 **서버 D21 순서 계약 미결**(`PremiumAccessCoordinator` 클래스 KDoc — 8번 기록에서 좌표 정정), backup은 기존 사용자 설정의
> restore 동작 변경을 분리한 것이다. 이 기록은 완료 선언이 아니고 미충족 작업의 후속 슬라이스 이관도
> 승인하지 않는다 — 소유권은 §7·§9.1이 정한 그대로다. 범위·D-결정·슬라이스 경계·게이트·DoD·활성
> 미결정 O1은 그대로이고, 어떤 슬라이스 착수나 public arming·rollout·deploy도 열지 않는다.
>
> **동결 후 4번 비의미 상태 명확화 기록(2026-09-10).** S3 REST bootstrap 착수 전 4방향 조사(서버·iOS·Android·
> 본 문서)에서 세 서술이 코드와 어긋난 것이 확인돼 고쳤다. (1) D8의 삭제 지시는 **이미 수행됐고** 그 행의 인용
> 좌표는 삭제 전 것이다 — 지시는 남기되 수행 사실과 '심볼 이름만 보고 재실행하지 말 것'을 덧붙였다.
> (2) `dxy:spot`은 '현재 topic 계층에 없음'이 아니라 **세 슬라이스에 나눠 이미 land**했다(DTO·decoder S3a
> `32f5994` / domain merge S3b `32446fc` / coordinator 수신 S3k-1 `4f50b64`). (3) REST bootstrap의 'typed 오류
> 행렬 적용'은 바로 위 WS 행렬을 가리키는데, **REST는 일부 오류 문자열을 공유할 뿐 envelope·적용 범위·인증/권한
> 표현이 달라 그 행렬을 그대로 적용할 수 없다** — 지시는 그대로 두고 이 endpoint의 실제 응답(503 세 종류 ·
> 인증 전 endpoint 404 `topics_disabled` · retry 입력 부재 · `topic_unavailable`과 `unknown_topic`의 body 차이 ·
> 인증/권한 코드 부재 · 스칼라 `topic`)을 사실로 덧붙였다. 함께 D14 항목에 빈 payload 처리의 iOS 이탈을 §2.2가
> 요구하는 대로 기록했고, 코드 세 곳(`TopicSessionCoordinator.kt`·`TopicSilencePolicy.kt`·`TopicSessionCoordinatorTest.kt`)이
> 인용하던 잘못된 좌표 `:889`를 실제 근거인 **D14**로 고쳤다. 범위·D-결정·슬라이스 경계·게이트·DoD·미결정은
> 바꾸지 않았고, 어떤 슬라이스 착수도 열지 않는다. bootstrap의 UID/`userAccessEpoch` fence는 §7 S3의 확정
> 계약이다. 발화 지점과 seam·fence 검사 배선은 이 커밋에서 구현하지 않는다. 이 기록은 계획서·인용 정정만
> 담으며, `evaluateSilence`의 전제 주석 수정과 bootstrap 시험은 후속 작업이다.
>
> ⚠️ **함께 측정된 것: 코드 주석의 이 문서 줄-인용은 이미 계통적으로 낡았다.** 이 기록을 쓰기 전 상태
> (Android `6895e64`의 문서)로 표본 대조한 결과 `:142`는 빈 줄, `:191`은 히스토리 표, `:813`은 FCM 콜백
> 문단이었다 — 어느 것도 인용한 코드가 주장하는 내용이 아니다. `app/src`에 이런 인용이 30곳 이상 있고, 원인은
> 이 기록이 아니라 동결 후 누적된 삽입이다(이 기록도 문서를 밀어 그 30곳을 한 번 더 어긋나게 한다).
> **줄 번호 대신 D-번호·§ 절 이름 같은 안정 식별자로 인용할 것** — 이 기록에서 숫자를 쓰지 않는 이유이기도
> 하다. 이번에 고친 세 곳은 그렇게 바꿨고, 나머지 일괄 정리는 별도 슬라이스로 둔다.
>
> **동결 후 5번 명시적 개정 기록(Claude·Codex 이중 합의, 사용자 위임 2026-09-08).** §7 S3는 FX·tether·dxy
> REST bootstrap의 **발화 시점을 적지 않았다**(적혀 있던 유일한 bootstrap trigger는 S6의 `krx_visible`
> flip뿐이다). L-3c 구현을 위해 그것을 **"유효 grant 획득당 1회, `desired` 전량"**으로 확정한다.
> 세션이 소켓을 열 수 있게 되는 전이 — `Access`와 `Online` 두 분기 — 에서 한 번 발화하고, 같은 grant를
> 두 번 묻지 않도록 grant 카운터로 잠근다. 재연결·foreground 복귀는 발화가 아니다.
>
> **S1의 cold-start 요청 예산과의 관계를 명시한다.** S1은 "인증·entitlement → 현재 탭 → 나머지 탭 →
> 알림 → 히스토리 순 지연, single-flight, 비활성 탭 lazy"를 요구한다.
> 이 coordinator에는 탭 입력이나 탭별 발급 제어가 없다. 현재 UI는 DXY를 USD 탭에서 사용한다.
> 이번 단계는 `desired` 전량의 논리 호출을 한 번에 시작하며, 이것만으로 S1 충족을 보장하지 않는다.
> S1의 요청 순서·single-flight·비활성 탭 lazy와 실제 요청 예산을 충족할 발급 제어는 L-4의 필수 항목이며,
> 이를 구현·검증하기 전 런타임에 연결하지 않는다. 함께 기록할 두 가지: (1) 논리 호출 수는 `desired` 크기(현재 5)이고
> 401 replay가 하나를 둘로 만들 수 있어 HTTP 송신 수와 같지 않다, (2) **요청 패턴을 자격의 함수로 만들지
> 않는다** — 가진 capability에 따라 묻는 topic 수를 줄이면 트래픽 모양 자체가 그 사용자의 entitlement에
> 대한 진술이 된다. 실제 동시 요청이 nginx 상한에 부딪히는지는 **측정하지 않았다**(production 호출자가
> 아직 없다).
>
> **1번이 아니라 2번으로 기록하는 이유.** 발화 시점은 문서가 침묵한 항목이고, 침묵을 메우는 것이
> 구현 세부인지 미결정 상태의 변경인지 애매하다. 머리말의 "애매하면 2번" 지시를 따른다.
>
> 범위·D-결정·슬라이스 경계·release gate·다른 DoD는 바꾸지 않는다. 이 개정은 어떤 arming·rollout·
> deploy도 열지 않으며, coordinator는 여전히 production에서 생성되지 않는다.
>
> **동결 후 6번 명시적 개정 기록(사용자 GO 2026-09-13, Claude·Codex 검토).** D23 `SignedOut` 행("Firebase UID
> 없음 → epoch를 먼저 rotate·persist한 뒤 purge")과 O5 회수 범위의 "앱 재시작은 … 명시적 거부 없이 보존 namespace를
> purge하지 않는다"가 **UID 없이 시작한 cold start**에서 서로 다르게 읽혔다. 같은 문단이 정리 사유로 드는 "UID 변경"으로
> 디스크의 이전 owner → 시작 시 UID 없음을 읽을 수도 있어 해석이 셋이었다. 또 로그아웃한 적이 없을 수도 있는
> 사용자(Firebase 복원 오류)의 cache namespace를 버리기로 **정하는** 제품 결정이므로 2번으로 기록한다.
>
> **확정한 것.** (1) cold start의 첫 신원 관측이 UID 없음이면, 디스크에 이전 owner가 남아 있어도 시작 신원과의
> 연속성이 확인되지 않은 것으로 보고 D23 `SignedOut` 행을 그 시작에서 적용한다 — 로그아웃이 있었다고 판정하는
> 것이 아니다. (2) owner가 없어도 보호 자료 marker(`mayContain*Data`)가 서 있으면 owner 불명으로 같은 rotate를 한다.
> (3) owner도 marker도 없으면 추가 rotate는 하지 않지만 이미 기록된 pendingPurge는 평소대로 재개한다.
> (4) 저장된 사용자가 같은 UID로 복원된 재시작은 namespace를 보존하되, 그 UID의 로그아웃 의도가 기록돼 있으면
> 그 정산이 먼저다. (5) 사용자 원본 preference(I4 uid scope)는 보존한다.
>
> **감수하는 비용.** Firebase 복원 오류·중단된 로그인 복구에서도 cache를 다시 받고 그동안 오프라인 연속성을
> 잃는다. 누출이 아니라 비용이다. 이 결정은 Firebase Auth가 저장된 사용자를 생성자에서 동기 복원한다는 가정
> (firebase-auth 24.0.1을 `javap`로 확인)에 기대므로 Firebase BoM을 올릴 때 다시 확인한다.
>
> **이 개정이 충족을 뜻하지 않는 것.** rotate와 pendingPurge 기록은 **실제 디스크 삭제가 아니다** — purger는 여전히
> `Deferred`를 돌려 journal을 남기므로 3번 기록의 S1 purge 미충족은 그대로다. 바꾼 곳은 D23 상태 전이 표의
> `SignedOut` 행과 O5 회수 범위 문단 두 곳이다. 범위·다른 D-결정·슬라이스 경계·release gate·다른 DoD는 바꾸지 않았고,
> 어떤 arming·rollout·deploy도 열지 않는다.
>
> **동결 후 7번 명시적 개정 기록(사용자 GO 2026-09-13, Claude·Codex 검토).** iOS 1일 그래프 음영 결함 조사와 확정 수정
> 계획(ios `89e866d`의 `GRAPH_LIVE_BAND_DECISIONS.md`·`GRAPH_LIVE_BAND_IMPLEMENTATION_PLAN.md`)을 Android 계획에 반영한다.
> 기존 §7 S4 1d live-tail 계약은 봉 배정 시각, 타이머와 관측의 분리, 복구 완료 판정을 충분히 명시하지 않아 동결 기준
> iOS 코드(`a36682f`)의 확인된 결함을 복제할 여지가 있었고, §7 S3에는 표시 병합(strictly-newer)이 버리는 재관측·역순
> 입력을 그래프로 넘기는 계약이 없었다. 기준은 수정된 iOS 코드가 아니라 **확정 동작 명세와 합성 입력**이다(iOS 수정
> 구현은 아직 없다). §2.2의 "확인된 결함은 의도적 divergence로 기록"에 따른다.
>
> **확정한 것(2번).** (1) S3: 표시 병합은 유지하고, 기존 권한·세션 검사와 `RateSanity`를 통과한 같은 entry에서 원 시각
> 관측과 연속성 사건을 coordinator 직렬 scope의 상태 전이 순서로 인계한다. 어댑터는 전달 topic이 아니라 source·asset
> 의미로 고른다. KRX는 S6. (2) S4 1d live-tail 재작성: 서버 시각 봉 배정, 타이머의 관측 합성 금지, 서버 범위·앱 관측 분리
> 보관과 반복 seed 합집합, 닫힌 봉 재오염 금지, 봉별 복구 필요 상태·복구 세대, 보존·폐기 규칙(명시적 철회의 저장 실패·
> 미확정은 일시 공백이 아니라 봉인), 기록기 수명·공유 버전·소비자별 접근 검사, 장기 끝점 tip, DXY tip. close는 검증 관측 중
> 관측 시각이 가장 늦은 가격이고 없으면 seed close다 — seed에는 가격 시각이 없으므로 이것이 seed보다 실제로 최신인
> 가격임을 보증하지 않는다는 한계를 받아들인다. (3) S4 disk에는 서버 유래 자료만 두고 관측·복구 상태는 세션 메모리에
> 둔다. last-known 복원 seed는 관측이 아니다. (4) S4·S5·S6 DoD에 합성 입력의 슬라이스별 적용을 넣는다.
>
> **유지한 것.** 600초 정렬, 현재 봉 seed fold 조건, high/low 합집합, 25시간 prune, 350ms publish·off-screen, freshness
> 600/1200 값, rollover 10초 tick, foreground ≥60초·30초 cooldown·첫 연결 제외, D14 배달·watchdog, D15, bootstrap 적용 전 검사,
> topic last-known 계약 본문, graph disk key·KRX cache 분리, catalog 규칙, single-flight.
>
> **1번 정정(의미 불변, 이 개정에 함께 싣되 구분).** S4 "25시간(+1h 여유)"을 "24시간 창 + 1시간 여유(총 25시간)"로 —
> 26시간으로도 읽혔고 iOS 코드(`start - 25*3600`)와 같은 값이다. `_bucket_align`이 계약 문서가 아니라 서버 코드 정의임을
> 명기. S5 성능 경로의 `MainScreen`(호출처 0)을 `RootScreen` 진입의 premium 목적지로 정정 — 측정 여정과 gate는 그대로다.
> S1 `UserScopePurger` 대상 "전체 domain/user data"에 세션 메모리 그래프 관측 기록기·복구 요청을 예시로 명기.
>
> **이 개정이 승인하지 않는 것.** 문서 개정이며 구현·배선 승인이 아니다. 선행조건과 시점: **관측 연결 구현 전** L-4d 권위
> 토큰·거부 결속, L-4c ACK 적용 경계, L-4f bootstrap 발급 제어의 최종 구현 대조(관측 채택이 입력 허용 조건에 닿는다) /
> **해당 어댑터 구현 전** 소스별 시각 의미·legacy/fallback의 fixture 확인(거래소별 원천과 전체 fallback, KRX fallback,
> Tether 경유 FX의 전 경로 동등성은 확인 필요) / **runtime 배선 전** S1 잔여(명시적 철회 저장 실패의 봉인·재승인·정리
> 재개 계약), L-4e grant 전달·재개와 live 접근권한 철회 검증 대조, 실제 purger 설계. 관측·복구·보관·폐기 책임은
> topic last-known 저장소와 실제 purger 설계를 확정하기 전에 이 계약으로 맞춘다. 서버 계약·기록 유무 필드, 음영 모양,
> KRX 관측 소비 시점(S6)은 바꾸지 않는다. 범위·다른 D-결정·release gate를 바꾸지 않고 어떤 arming·rollout·deploy도 열지 않는다.
>
> **동결 후 8번 비의미 상태 명확화 기록(2026-09-13, Claude·Codex 검토).** 7번 기록이 runtime 배선 전 선행조건으로 둔
> "S1 잔여(명시적 철회 저장 실패의 봉인·재승인·정리 재개 계약)"의 진행을 land와 미충족으로 나눠 적는다. **land는 선행조건
> 충족이 아니다.**
> (1) S1r-1 `727dff7`: 쓰기가 실패하거나 취소된 뒤 access epoch 되읽기가 저장되지 않은 레코드를 착지로 받아들이지 않게
> 저장소 어댑터에서 막았다. DataStore 1.1.7은 rename 전에 메모리 사본을 바꾸므로, 그 지점에서 실패하면 되읽기가 디스크에
> 없는 값을 돌려줄 수 있다(실패 지점을 주입한 JVM 시험으로 확인). 기존 신원 복구의 되읽기에도 적용된다.
> (2) S1r-2b: 검증된 명시적 손실의 회전 저장이 실패하거나 미확정이면 예외로 내보내지 않고 손실을 발행·봉인한다. 손실 복구가
> 프로세스 안에서 회전 재시도, 인계 기록이 없는 대상의 journal 복원, `Failed`·예외로 끝난 정리의 재시도를 맡는다. 회전 시도가
> 던진 뒤나 신원 전이가 대상을 은퇴시킨 뒤의 정리도 여기에 포함된다. `Deferred`만 남으면 손실 복구는 정리 재시도를
> 예약하지 않는다. 다른 항목·축의 실패로 회차가 재개되거나 외부 재개 계기가 오면 journal 전체를 다시 시도하므로, 이미
> `Deferred`를 반환한 항목·축도 다시 호출될 수 있다. 봉인 해소 뒤
> 재승인은 해당 binding에 축별 최소 intent를 **예약**할 뿐, 재확인 요구의 지속 소유와 실제 재조회 실행은 보장하지 않는다.
> (3) **미충족으로 남는 것**: S1r-2a(single-flight·예약 교체·취소·stale 폐기에도 재확인 요구를 보존) / S1r-2c(레코드를 읽지
> 못한 손실 응답 — 대기 중 권한을 어떻게 둘지는 정책 선택이라 명시적 개정 대상) / 봉인의 process death 지속(§7 S1 DoD의
> "명시적 거부 시 process-death 첫 protected use 전 journal materialize+purge"를 저장 실패 경로에서는 충족하지 못한다) /
> 레코드 필드가 불완전해 epoch 없이 할당된 경우의 null 대상 봉인 종료 규칙 / 실제 purger.
> (4) 사실 정정: 3번 기록의 `PremiumAccessCoordinator.kt:18-27` 좌표는 코드 이동으로 PushDelete 유예 사유가 아닌 곳을
> 가리키고 있어 "클래스 KDoc"으로 바꿨다.
> 이 기록은 7번의 선행조건을 완화하지 않는다. 범위·D-결정·슬라이스 경계·게이트·DoD는 그대로이고 어떤 arming·rollout·deploy도
> 열지 않는다.
>
> **동결 후 9번 비의미 상태 명확화 기록(2026-09-14, Claude·Codex 검토).** 8번 (3)이 미충족으로 적은 S1r-2a 구현을 이번 변경에 포함한다.
> **이 구현은 선행조건 전체의 충족 선언이 아니다.** 재확인 요구(reducer의 재확인, 보류 답의 재시도, S1r-2b 손실 재승인, floor에 막힌 호출)를
> 조회·타이머의 수명과 분리해 binding별로 보존한다. 요구보다 뒤에 시작한 같은 binding의 같거나 더 센 조회가 요구 축을 충족하는 정착 답으로
> 결정됐을 때 요구를 끝낸다. FP single-flight 반환·stale 폐기·예약 교체·AUTHENTICATION 답·topic 거부 자체로는 요구가 사라지지 않는다(프로세스 안).
> 인증 정지·재개는 마지막 상태 사건 순서보다 늦게 시작해 검증·결정된 답에만 반영한다. AUTHENTICATION 결정 답은 새 자동 재조회를 멈추고,
> AUTHENTICATION 이외의 결정 답은 재개한다. admission·binding·generation 검사를 통과한 CALLER는 실제 fetch 여부와 무관하게
> floor 검사 전에 재개 순서를 기록한다. 정지 시 이미 무장돼 남아 있는 타이머는 한 번 실행될 수 있다.
> 8번 (3)의 null 대상 봉인 종료 규칙 미충족 표기는 정정한다. S1r-2b의 `LossSealLedger.observe`는 조건을 만족하는 신원 은퇴의 착지로,
> `releaseByRotation`은 조건을 만족하며 정상 반환한 회전의 증거로 null 대상 봉인을 해제한다. 단순 epoch 할당은 명시적 null 대상 봉인의
> 해제 근거가 아니다. 현재 레코드의 epoch 누락에 따른 접근 차단은 이 명시적 봉인과 별도로 판정한다.
> 할당만 일어나고 덮는 journal 증거가 없으면 그 null 대상 봉인은 프로세스 안에서 유지된다(재시작 시 메모리와 함께 사라진다).
> **미충족으로 남는 것**: 같은 binding에서 신원 사건 없이 credential만 회복될 때의 자동 재개(재개 신호가 없다) / S1r-2c /
> 봉인과 재확인 요구의 process death 지속 / 실제 purger.
> 이 기록은 7번의 선행조건을 완화하지 않는다. 범위·D-결정·슬라이스 경계·게이트·DoD는 그대로이고 어떤 arming·rollout·deploy도
> 열지 않는다.
>
> **동결 후 10번 명시적 개정 기록(사용자 P4 GO 2026-09-14, Claude·Codex 설계 합의).** §7 S4의 명시적 철회 봉인 계약이 정하지 않은, 결정용 레코드 읽기 실패로 응답의 현재 신원·namespace·세대에 대한 유효성을 확인할 수 없는 경우를 보완한다(S1r-2c).
> 이 경우 권위 상태를 보존하고 해당 범위의 접근만 임시 보류한다(P4). 레코드 없이 확인된 stale은 보류 없이 버리고, identity 확인 불가를 다른 세션으로 간주하지 않는다.
> premium 후보는 USER·CAPABILITY, KRX 후보는 CAPABILITY만 보류한다. 보류 자체로 권위 손실·epoch 회전·purge를 합성하지 않으며 무료 snapshot과 기존 비-grant 상태의 최소 접근을 유지한다.
> 해당 범위의 protected read/render·관측 채택·신규 요청·재시도·지연 완료 적용은 현재 유효 접근으로 차단해야 한다. Root와 push 등록을 포함한 소비처에 적용하며, 이 문구는 아직 없는 topic/KRX/그래프 runtime 배선의 완료를 뜻하지 않는다.
> 보류를 해결하는 레코드 읽기·identity 재검증·권한 재확인과 이미 필요한 정리는 차단하지 않는다. 기존 D를 보존하고 후보 응답의 새 서버 하한도 기록하되, 보류 생성만으로 새 D를 만들지 않는다. 일정은 기존 admission·AUTH·in-flight·floor 규칙에 따라 기다리거나 재무장될 수 있다.
> LossRecovery와 별도인 단일 CandidateRecovery가 후보별 최신 레코드로 재검증한다. 유효한 후보는 기존 손실 발행·봉인·회전·미완료 정리의 인계 후 보류를 해제하며, 인계를 실제 purge 완료로 보고하지 않는다. stale이면 해당 후보의 보류만 해제한다. binding 종료와 취소에도 옛 grant의 중간 발행이나 후보 정리 누락이 없어야 한다.
> 보류의 process death 지속은 미충족이다. 이 개정은 위 미정의 경우를 보완하며 다른 D-결정·슬라이스 경계·게이트를 바꾸지 않는다. 어떤 arming·rollout·deploy도 열지 않는다.
> 이번 변경에 이 계약의 구현을 포함한다. 손실 후보가 아닌 답의 결정용 읽기 실패, 조회 전 읽기 실패, `topicGrant()` 읽기 실패는 이 계약 밖이며 예외 전파를 그대로 둔다.
>
> **동결 후 11번 명시적 개정 기록(사용자 GO 2026-09-14, Claude·Codex 설계 합의).** §7 S1 cold-start 요청 예산의 "비활성 탭 lazy"를 REST topic bootstrap에 적용하는 범위와, 탭 미확정 시 대기·복원 실패 시 기본 탭 정책을 확정한다(L-4f). 동결 후 5번의 grant당 자동 계획 1회·desired 전량은 유지한다.
> topic bootstrap은 **전량 지연 발급**한다. 현재 탭에 필요한 topic을 먼저 발급하고, 나머지 desired 시세 topic은 간격을 두고 미리 발급한다. 현재 탭에 필요한 topic은 그 탭의 시세 행과 그래프 live 입력을 포함하며, `dxy:spot`은 달러·테더 탭 양쪽에 속한다. lazy는 S1 cold-start에서 비활성 탭의 Graph 조회에 적용하며, 해당 조회는 그 탭에서 필요해질 때 시작한다. 다른 REST family의 발급 정책과 S4의 세션 관측·기록기 수명은 변경하지 않는다. "전량"은 desired 시세 topic이며 모든 탭의 그래프가 아니다.
> 현재 탭이 확정되기 전에는 bootstrap을 발급하지 않는다. 최초 확정은 기기에 저장된 마지막 탭의 복원이며, 저장값이 없거나 알 수 없는 값이거나 읽기에 실패하면 달러로 확정한다. 이후에는 유효한 현재 사용자의 탭 선택을 반영한다. 뉴스 선택은 확정 상태이고 미확정과 구분한다. 계정이 바뀐 뒤 도착한 이전 계정의 복원 결과는 적용하지 않는다.
> 발급 간격은 논리 호출 시작을 조절할 뿐이며 토큰 대기·401 replay·이전 grant의 진행 중 요청 때문에 실제 HTTP 소비율이나 동시성 상한을 보장하지 않는다. S1 합산 요청 예산의 검증은 별도이며 동결 후 5번의 "구현·검증 전 런타임 연결 금지"를 유지한다. 이 방식은 논리 호출의 시작 집중을 조절하지만 총 요청 수를 줄이지 않으며, 응답 실패·지연 때문에 탭 이동 시 데이터 존재를 보장하지 않는다. 이 기록은 실제 focus 제공자 구현·배선이나 S1 합산 예산 검증의 완료 선언이 아니다.
> 이 개정은 위 적용 범위를 확정하며 다른 D-결정·슬라이스 경계·게이트·DoD를 바꾸지 않는다. 어떤 arming·rollout·deploy도 열지 않는다.
> 이번 변경에 이 계약의 coordinator 쪽 구현(탭 입력의 신원 대조, grant당 발급 계획, 순서·간격·floor 읽기, 같은 grant 안의 요청 합류)을 포함한다. 탭을 복원해 넘기는 제공자와 런타임 배선은 포함하지 않는다.

---

## 1. 목적 · 범위 · 비범위

### 1.1 목적
iOS v2.0.0(topic-only, 5탭, 무료 hourly 스냅샷, KRX 조건부 노출)과 **동등한 접근 모델**을 Android에 구현한다.
이 작업은 기존 v1 화면에 기능을 더하는 것이 아니라 **데이터 런타임 교체 마이그레이션**이다.

### 1.2 범위 (in scope)
- legacy(`/api/rates`, `/api/graph/{currency}`, WS `rates` 배열) 소비 **완전 제거**
- topic-only 실시간(WS `subscribe`/lease/snapshot + REST bootstrap)
- 로그인한 비구독자용 **인증된 hourly 무료 스냅샷** 경로
- 5탭 구조(뉴스 · 테더 · 달러 · 엔화 · 유로)
- Graph API v2(catalog 기반 동적 series, 1d intraday + live-tail)
- 알림 3 family(은행 / source[거래소·KRX] / 비교·김프) + repeat + 히스토리 + FCM 4 type
- KRX 조건부 노출 및 **미승인 시 app-owned 전 표면 비노출 + 서버 철회 관측 뒤 신규 발송 0**
- 계정삭제 로컬 phase 상태기계 · 설정 IA(알림 권한 섹션) — 서버측 tombstone은 SV-4
- v1 → v2 로컬 상태 마이그레이션, 백업 제외, 릴리스 게이트

### 1.3 비범위 (out of scope)
- 전체 앱 다국어화(신규·수정 문구만 resource화)
- multi-module 재구성
- 비교 **그래프**(제품 로드맵, iOS 미구현)
- 위젯 / Wear / deep-link scheme(양 플랫폼 모두 없음 — 재조사 불필요)
- iOS 결함 수정(§10.3 후속 트랙)
- 알림 전달 신뢰성 재설계(조건-event ledger, client ACK, age tier, TTL/queue drain, missed-delivery 재평가,
  data-only local renderer). v2.0은 iOS와 현재 서버의 동작을 복제하고, 이 항목은
  `ANDROID_NOTIFICATION_RELIABILITY_FOLLOWUP.md`에서 사건 기반 후속으로 추적한다

---

## 2. 기준 커밋 · 정본 우선순위

### 2.1 기준 커밋 (2026-08-29 실측)
| 리포 | 커밋 |
| --- | --- |
| `exchange-rate` | `2cb6624` |
| `ios` | `a36682f` |
| `android` | `676f320` |

세 HEAD의 **source baseline은 문서 생성 전에 clean**이었다. docs 동결 커밋 직전 Android에는
본 계획서 `ANDROID_V2_PLAN.md`와 후속 기록 `ANDROID_NOTIFICATION_RELIABILITY_FOLLOWUP.md`, 총 2개만
untracked였고, 두 문서는 `6cea639`에서 tracked로 전환됐다. 이 문서 추가는 기준 코드 SHA `676f320`을 바꾸지 않았다.

경로 표기 규칙: `exchange-rate/...`는 서버 리포(`../exchange-rate`), `ios/...`는 iOS 리포(`../ios`),
`android/...`는 현재 Android 리포를 뜻한다. 리포 prefix가 없는 `app/...` 서버 경로는 `exchange-rate/app/...`의 축약이다.

### 2.2 정본의 두 축
- **목표 wire 계약**: 서버 기준 SHA의 route / builder / schema / contract test가 정본이다.
- **현재 배포 현실**: production read-only smoke와 배포 SHA/config가 정본이다.
- **클라이언트 운용정책**: iOS 기준 SHA를 출발점으로 삼되 확인된 결함은 의도적 divergence로 기록한다.
- 문서(`REALTIME_V2_CLIENT_GUIDE.md`, `GRAPH_API_V2_CONTRACT.md` 등)는 위 세 축을 설명하는 자료다.

서버 HEAD와 production이 다르면 어느 한쪽을 묵시적으로 우선하지 않는다. **drift로 기록하고 공개 rollout을 차단**한다.

> 문서를 정본에 두면 안 되는 이유: 본 조사에서 `REALTIME_V2_CLIENT_GUIDE.md` drift **4건**이 확인됐다.
> ① §2.5 "graph v2는 무인증" → 실제는 Firebase 인증 + premium 강제(`app/main.py:2849-2884`)
> ② "legacy KRX 410 + `use_topic`" → 실제는 generic 404(`app/legacy_policy.py` — 존재 비노출)
> ③ §6 "수신 프레임마다 reconnect 카운터 초기화 → 무한 재연결" → iOS는 30초 안정 창 뒤에만 초기화(stale 관측)
> ④ §"retry_after 부재 시 클라 기본값" → iOS는 기본값을 두지 않음(부재 = 재시도 안 함)

---

## 3. 제품 · 접근권한 불변식

**출시 시점의 최종 불변식**이며 적용 시작점은 서로 다르다. I2·I4·I5·I7=S1, I3=S2,
I1의 legacy rates=S3·legacy graph=S4, I8=S7~S9, I6=S11부터 회귀 금지다. 아직 도입되지 않은 불변식을 앞선 슬라이스의
완료조건으로 소급하지 않으며, 중간 artifact는 public distributable이 아니다.

| ID | 불변식 |
| --- | --- |
| **I1** | legacy 소비 0 — `/api/rates*`, `/api/graph/{currency}`, WS `rates` 프레임을 **읽지 않는다**. 서버는 접속 즉시 legacy `rates` 프레임을 1회 보내므로(`app/main.py:1103-1125`) **명시적으로 무시**하고, 이 프레임 수신으로 reconnect 실패 카운터를 초기화하지 않는다. |
| **I2** | KRX **deny-by-default**. 단일 신호 `GET /api/entitlements`의 `krx_visible`만 사용하고 클라가 G1/G2/G3를 조합하지 않는다(**G3**=`KRX_FUTURES_ENABLED` 수집 / **G2**=`KRX_CLIENT_DISTRIBUTION_ENABLED` 배포 / **G1**=`user_entitlements` row 운영자 수동 부여, `krx_visible = G3 ∧ G2 ∧ G1 ∧ premium` — ADR-038 Decision 1, `exchange-rate/app/entitlements.py:7-9,68`). `false`면 시세·그래프 series·알림 picker·**설정 목록·히스토리**·접근성 label·analytics에서 전부 제거하고, sender의 발송 직전 재판정에 철회가 반영된 뒤 시작하는 신규 KRX FCM은 0이다. 서버 row는 삭제하지 않는다(재승인 복구). 권한 확인과 RPC 시작 사이의 race, 이미 시작됐거나 FCM/APNs가 수락한 visible message의 사후 회수는 현 iOS와 현재 서버 topology가 보장하지 못하며 v2.0 known limitation으로 명시한다. |
| **I3** | 무료(로그인·비구독) 경로는 **인증된 hourly REST 전용**. WS 연결 0, FCM 기기 등록 0, 알림 mutation 0, app-owned KRX 데이터·UI 흔적 0. 철회 전에 broker가 수락한 visible message는 I2의 공통 한계를 따른다. |
| **I4** | 사용자 원본 preference는 `uid` scope다. 일반 권한 파생 cache/live/in-flight는 `(uid,userAccessEpoch)`, KRX 파생 데이터는 `(uid,userAccessEpoch,krxCapabilityEpoch)` scope다. 두 epoch는 backup 제외 로컬 저장소의 opaque UUID이며 **권한 teardown에서 새 UUID와 이전 namespace purge journal을 먼저 원자적으로 persist**하고 절대 재사용/0 reset하지 않는다. KRX revoke는 `krxCapabilityEpoch`만 바꿔 비-KRX 사용자 데이터를 무효화하지 않는다. same-UID clean cold start는 namespace epoch만 이어받을 수 있지만 fresh 서버 승인 전에는 protected cache를 읽거나 그리지 않는다. KRX effective projection은 파생 상태이며 원본 preference를 덮지 않는다(D17의 Graph hana↔KRX 쌍 예외 제외). 이전 UID/epoch에서 출발한 응답은 새 세션에 반영하지 않는다. |
| **I5** | **strict wire decode ⊥ domain 검증.** wire는 계약 위반 시 실패하고, 값 sanity(finite / >0 / 상한)는 domain 변환에서 검증한다. |
| **I6** | **Android 공개 rollout**은 SV-1·SV-2의 production 배포·검증 전에는 시작하지 않는다(§10.1). SV-1은 subprocess를 포함한 모든 visible-alert 발송 경로의 send-time premium/KRX 권한 확인, SV-2는 KRX settings/history의 filter-before-limit를 뜻한다. |
| **I7** | **premium 정본은 서버 `premium_active`**다. 로컬 RevenueCat은 구매·복원 신호일 뿐 runtime 접근 권위가 아니다. 서버 거부 후 로컬 `true`만으로 premium을 다시 열지 않는다(D23). |
| **I8** | v2.0 알림은 **iOS·현 서버 parity**를 따른다. `rate_alert`·`source_rate_alert`·`comparison_alert`는 `notification+data` visible push, `sync_alerts`는 data-only silent push다. 서버는 FCM 성공(`success_count>0`)을 발송 성공으로 보고 현 비원자적 commit 순서로 once/repeat setting 전이와 성공 history 기록을 시도한다. history 의미는 ‘조건 사건’이나 단말 수신이 아니라 **발송 히스토리**다. client delivery ACK·연령별 표시·자동 재평가를 v2.0에 추가하지 않는다. KRX 비노출은 client delivery state가 아니라 SV-1 send-time gate와 SV-2 read filter로 보장한다. |

---

## 4. 기능 추적표 (iOS ↔ 서버 ↔ Android)

| 기능 | 서버 계약 | iOS v2.0.0 | Android 현재(`676f320`) | Android 슬라이스 |
| --- | --- | --- | --- | --- |
| 실시간 시세 | WS topic + `/api/v2/topics/snapshot` | 구현 | legacy `rates` 배열만 | S3 |
| DXY | `dxy:spot` topic | 구현 | topic 계층에 DTO **없음** | S3 |
| 그래프 | `/api/v2/graph/{catalog,tab}` | 구현 | v1 `/api/graph` | S1.5·S4·S5 |
| 무료 스냅샷 | `/api/v2/free/snapshot` | 구현 | 없음(가짜 예시 데이터) | S2 |
| 테더 탭 | `usdt:krw` | 구현 | 없음 | S5 |
| KRX | `krx:usd-krw-futures` + `krx_visible` | 구현(비교알림 누락 §10.3) | 없음 | S1(deny)·S6(grant) |
| 은행 알림 | `/api/notification-settings` | repeat + history | repeat·history **없음** | S7 |
| source 알림 | `/api/source-notification-settings` | 구현 | 없음 | S8 |
| 비교·김프 | `/api/comparison-alerts` | 구현 | 없음 | S9 |
| 히스토리 3종 | `*-logs` | 구현 | 없음 | S7·S8·S9 |
| FCM type | 4종 | 4종 | `rate_alert`·`sync_alerts`만 | S7~S9 |
| topic 모델 계층 | — | — | **존재·미배선**(24 tests) | S3에서 배선 |
| `X-Client-*` | 관측 전용 | 구현 | **구현·테스트 잠금** | 유지 |

---

## 5. 결정 로그

### 5.1 확정 결정

근거 열은 **코드로 확인한 실측**과 **설계 판단**이 섞여 있다. file:line 이 있는 행은 실측이고,
근거가 `—` 이거나 서술형인 행(D2·D3·D21·D22·D24 등)은 판단이다.

| ID | 결정 | 근거 |
| --- | --- | --- |
| D1 | 탭 순서 **[뉴스, 테더, 달러, 엔화, 유로]**, 최초 선택 **달러**, 마지막 탭 영속 | `ios/FXi/ContentView.swift:21-22,52`, `Models/FreeSnapshotRouting.swift:16` |
| D2 | 릴리스 게이트는 **BuildConfig 컴파일 게이트**(default OFF). 전용 Remote Config 의존성 미추가 | iOS `TOPIC_V2_RELEASE_ON` 대응 |
| D3 | 재연결 정책은 iOS 복제: linear 2s×n ±20%, 최대 5회, **30초 안정 후에만** 초기화 | iOS 실전 검증(post-ACK close 루프 대응) |
| D4 | visible 알림은 iOS와 같은 서버 `notification+data` payload를 사용하고 Android는 기존 단일 `rate_alerts` 채널을 유지한다. foreground에서는 app renderer가 표시하고, background에서는 OS renderer가 표시한다. `sync_alerts`만 data-only silent다. 계열별 채널·data-only 전환은 v2.0 범위가 아니다 | `app/notifications/fcm.py:257-285,428-454,551-577,637-693`; iOS `PushNotificationService.swift:639-666`; Android `AndroidManifest.xml:21-22`, `FXiMessagingService.kt:91` |
| D5 | targetSdk **36**, versionName **2.0.0을 S0부터** (`X-Client-Version`이 versionName을 사용) | `android/data/remote/ClientMetadata.kt` |
| D6 | 알림 상한 = **클라이언트 soft cap, family별 30** (은행 30 / source 30 / 비교+김프 합산 30). 서버 제한 **없음**. 초과 기존 row는 숨기지 않고 조회·편집·삭제 허용 | `ios/Utils/Constants.swift:294` + 3 VM 각각 적용 / 서버 grep 0건 |
| D7 | **Citi**: 신규 생성 picker 제외. 기존 row는 현재 선택 은행을 라벨로 표시하고 조회·토글·threshold·repeat 편집·**picker에 있는 은행으로의 변경**·삭제를 허용한다. 편집 picker에도 Citi가 없으므로 저장 전 취소만 원상복구할 수 있고, 다른 은행으로 저장한 뒤에는 Citi로 되돌릴 수 없다. 현재가가 없어도 편집 가능하며, iOS에 없는 추가 경고 UI는 넣지 않는다 | `ios/Constants.swift:175 displayCases`, `AlertAddSheet.swift:124-136,302,412-418,744,751-758` |
| D8 | `usd_krw_futures`는 **Android tether domain에 노출하지 않는다**. ⚠️ **`ignoreUnknownKeys`가 알아서 무시해 주지 않는다** — 현재 Android DTO가 이 키를 **이미 선언·병합**하고 있으므로 **코드 삭제가 필요**하다: `TetherTopicData.usdKrwFutures` 필드 제거 + `allEntries`의 `usdKrwFutures?.let(::add)` 삭제(+ `TopicMessageTest` 갱신), 그 뒤 남는 wire 키를 `ignoreUnknownKeys`가 흡수. '의도적 강화'의 대상은 iOS만이 아니라 **현재 Android 코드**이기도 하다. ✅ **이 삭제는 이미 수행됐다**(2026-09-10 코드 대조): `TopicMessage.kt`의 `TetherTopicData`는 `usdt_krw`/`usd_krw_banks`/`usd_krw_reference` 셋뿐이고 `allEntries`도 그 셋만 더한다. 아래 좌표 `:165-166,173`은 삭제 전 것이라 더는 유효하지 않다. 살아 있는 `usdKrwFutures`는 같은 파일 `KrxTopicData`의 정당한 필드이므로 **심볼 이름만 찾아 이 지시를 재실행하지 말 것** | `android/.../dto/TopicMessage.kt:165-166,173`(선언·병합 중) / `android/di/NetworkModule.kt:31` / iOS 동형: `TopicMessage.swift:71` |
| D9 | `type:"update"`는 **지원 대상에서 제거**하고 무시(서버는 snapshot-only) | `android/TopicMessage.kt:12`, `TopicFrameDecoder.kt:34-35` |
| D10 | keep-alive: 클라가 raw text `"ping"` 송신 → 서버는 **JSON** `{"type":"pong"}`. raw pong fast-path는 없다 | `app/topic_dispatcher.py:496-497` |
| D11 | malformed frame은 **decoder가 실패**하고 **transport가 프레임 단위 격리**(store 미변경·로그·다음 프레임 계속). decoder 무시로 바꾸지 않는다 | `TopicFrameDecoderTest.kt:63` 회귀 계약 |
| D12 | **429에는 Retry-After가 없다.** 헤더가 있으면 하한으로 존중, 없으면 jitter backoff. 헤더 부재를 terminal로 취급 금지 | `nginx/conf.d/default.conf`에 `add_header Retry-After` 0건, 앱 429 0건 |
| D13 | 비구독 응답 규약: 알림 settings/history GET = **200 빈 목록**, 알림 mutation = 403, topic bootstrap·Graph V2 = 403, 무료 스냅샷 = 인증만 | `app/main.py` require_premium(allow_empty) 분기 |
| D14 | staleness: **테더만 45초** 무수신 → 침묵 에피소드당 **조용한 재검증 1회**(값 숨기지 않음), 재침묵 시 degraded. silence monitor는 **첫 유효 `usdt:krw` snapshot을 받은 뒤에만** arm한다. 최초 delivery 전에는 S3의 20s/45s watchdog만 owner이고 KRX-only 수신은 `tetherReceived`·테더 신선도·deadline을 만들거나 연장하지 않는다. FX/KRX는 시간 만료 없음 | iOS `ExchangeRateViewModel.swift:223-340,877-903` |
| D15 | Graph 갱신 2기전 **분리**: (a) `catalog.cache_ttl_seconds`(누락 시 3600s) `∧ 같은 KST 일자` = **전 기간 공통**, (b) 00:02 선제 타이머 + backoff 20/40/80 = **fixed_start(1w/3m/1y) 전용**, 1d는 rollover·foreground/reconnect resync | `ios/GraphV2ViewModel.swift:133-134` |
| D16 | series 개수를 계약으로 고정하지 않는다. `catalog.tabs[].periods`가 정본이고 **top-level `supported_periods`는 사용 금지**(1d 누락) | `app/graph_v2.py:32 MVP_PERIODS` |
| D17 | 저장 선택 정책은 **화면별로 다르다**: source 목록 preference는 원본 보존(`hasPersisted` 가드) + effective 투영 / **Graph는 hana↔krx 쌍만 재설정·영속**(다른 series 선택 불변) | `SourcePreferenceManager.swift:47-50` vs `GraphV2ViewModel.swift:281-286` |
| D18 | `eligible = rawVisible(사용자 순서) ∩ accessible`로 계산한다. 비면 entitlement-aware `defaultVisible` 후보 중 첫 accessible 항목 **1개만** 임시 투영한다(후보도 없으면 명시적 unavailable). 원본은 바꾸지 않고 설정 화면도 같은 effective 결과를 보여준다. Graph에는 적용하지 않는다(all-off 허용) | `SourcePreferenceManager.swift:142,193-202`의 빈 결과 결함을 보완하는 의도적 강화 |
| D19 | 시세 막대: **선형 폭**(ScalePolicy displayRange 도메인) · 0.5초 linear · **pulse 제거** · 첫 렌더·Reduce Motion snap · ▲▼ cue 임계 0.01 | `ios/RateBarAnimation.swift:9`, iOS `d29df48`(펄스 제거) / Android는 `tween(1000)`+`pulseScale` 잔존 |
| D20 | ▲▼ cue의 **latest-wins 타이머**와 **Reduce Motion 중 기준값 갱신**은 iOS parity가 아니라 **의도적 개선**(iOS는 무조건 예약 + reduceMotion에서 `previousRate` 미갱신) | `SourceRateBarView.swift:169,185-191` |
| D21 | push 등록 coordinator는 **S1에 실설치**한다(등록 로직을 S7까지 미루는 대안은 폐기). 별도 backup 가능한 `push intent`를 권위로 두지 않고, 현재 UID 세션에서 `release gate ON ∧ auth ∧ 서버 확정 premium runtime ∧ OS 알림 capability(runtime permission + app notification + channel)`를 매번 재구성한다. auth 복원·foreground·권한/channel·FCM token·premium 상태 변화에 재평가하며 DELETE는 premium과 무관하게 항상 허용 | 현재 Android도 권한 복구/설정 load에서 intent를 재구성(`AlertViewModel.kt:121-132,229-241`) |
| D22 | v2 앱 rollback은 legacy fallback이 아니라 Play rollout 중단 + forward-fix다. 이미 설치된 v2 cohort는 회수되지 않으며 topic 서버를 꺼서 legacy로 되돌릴 수 없다. 서버의 구버전용 legacy **data endpoint** 유지와 alert sender mode는 별개이고, sender는 어떤 mode에서도 SV-1 gate 바깥으로 나갈 수 없다 | ADR-039 Stage B |
| D23 | **premium 접근 상태기계**: runtime 허용은 동일 UID·`userAccessEpoch`에서 서버가 확정한 `premium_active=true`만 만든다. **최초 grant는 `fresh_premium=true` 요청이 raw typed `Determined(ACTIVE)`로 끝난 응답만** 만들 수 있다. 일반/`.forceEntitlements`의 cached ACTIVE는 이미 열린 동일 프로세스 상태만 유지할 수 있고 cold-start grant를 만들지 않는다. 로컬 RevenueCat은 구매·복원·변경을 알리고 fresh 조회를 촉발할 뿐 허용/거부를 직접 결정하지 않는다. 서버 거부는 로그아웃이 아니라 무료 전환이며 fresh ACTIVE만 거부 latch를 해제한다. **transport/HTTP/decoding 판정 불가가 기존 premium runtime grant를 종료하지 않으며 클라이언트 시간 상한을 두지 않는다**(O5 회수). KRX는 typed pending envelope의 fail-closed 값을 포함해 아래 reducer가 별도로 결정한다 | 서버 `GET /api/entitlements`; legacy availability cache의 1h positive stale 세탁 방지 |
| D24 | **arming 계약**: `BuildConfig.TOPIC_V2_RELEASE_ON` default OFF. **S0이 Activity/Root와 FCM token/message callback·notification intent 등 background admission에 이 게이트를 실제 설치**한다. OFF는 app-owned unavailable 화면만 내고 legacy·v2 data network/로컬 push 처리를 열지 않는 **출시 불가 artifact**다. 단 이미 서버에 등록된 token으로 OS가 background notification을 직접 그리는 것은 OFF 앱이 가로챌 수 없으므로, public OFF 배포 금지와 SV-1 sender gate가 방어선이다. CI minified/internal/public 후보는 ON으로 별도 생성한다. public release task는 OFF면 실패하고, 업로드 전 AAB에서 실제 값·SHA·versionCode·applicationId·서명 인증서를 검증한다. arming/build/upload/rollout 확대는 각각 별도 GO다 | iOS 런북 + Android compile-time gate 제약 |
| D25 | v2.0은 새 `alert_event_id`·`delivery_id`·client delivery ACK를 도입하지 않는다. 서버의 FCM 성공 결과를 발송 성공으로 쓰고 현 비원자적 setting/history 전이 순서를 유지한다. 전달 여부를 조건 소진과 분리하는 설계는 후속 문서로 이관한다 | iOS push handler와 현 history 모델에 delivery receipt가 없음 |
| D26 | 무료 알림은 실제 free snapshot을 고정한 **in-memory 체험**이다. Save toolbar 자체를 제거하고 별도 하단 `SubscribeCTA`만 표시한다. CTA는 preview를 먼저 닫고 paywall을 열며 draft는 폐기한다(구매 후 자동 저장·이관 없음). dismiss/process death에도 폐기하고 history 예시·가짜 발화·KRX를 두지 않는다 | iOS `AlertAddSheet.swift:191-238`, `SubscribeCTA.swift:3-35`, `FreeAlertPreviewSection.swift:40-54` |
| D27 | Android backup은 key 단위가 아니라 **파일 단위 allowlist**다. allowlist는 `domain="file"`(DataStore)뿐 아니라 **`domain="sharedpref"`** 도 명시적으로 다룬다 — 현재 `push_prefs`(FCM token·shouldRegister, `PushNotificationManager.kt:28-36`)와 `alert_prefs`(hasRequestedPermission, `AlertViewModel.kt:103-137`)가 그 도메인에 있고 규칙이 0개라 **오늘 백업·D2D 대상**이다(의도적 제외임을 기록해 후속 리뷰의 회귀 복원을 막는다). backup 가능한 순수 사용자 의도 DataStore를 token/capability/cache/journal과 물리적으로 분리하고, 같은 allowlist를 API 31+ `cloud-backup`·`device-transfer`와 API ≤30 `full-backup-content` 세 경로에 적용한다 | 현재 두 XML은 실질적으로 비어 있고 `device-transfer`는 주석 상태; DataStore는 `files/datastore/*.preferences_pb` 파일 단위 |
| D28 | foreground app-owned 처리와 tap callback은 현재 authenticated premium/KRX 상태를 재확인하고 실패하면 local state update/refetch를 하지 않는다. tap은 앱을 여는 현재 동작과 family state 동기화까지만 하며 section deep-link/focus는 iOS TODO와 맞춰 v2.0에 추가하지 않는다. background OS-rendered visible push는 client가 선필터할 수 없으므로 **SV-1의 send-time 권한 gate가 정본 방어선**이다. payload binding·암호화 quarantine은 v2.0에 추가하지 않는다 | Android notification message의 background 표시 특성; iOS `PushNotificationService.swift:669-769` |
| D29 | legacy queue 28일 drain·강제 data-only cutover는 **v2.0 release gate에서 제외**한다. 이미 broker에 수락된 메시지를 client가 완전히 회수하지 못하는 한계는 iOS와 동일한 known risk로 기록하고, 실제 stale-delivery/계정교차 사건이 확인될 때 후속 설계를 연다 | `ANDROID_NOTIFICATION_RELIABILITY_FOLLOWUP.md` |
| D30 | SV-1의 subprocess premium 판정은 **single-owner loopback IPC(B-IPC)**로 통일한다. FastAPI main의 `AlertPremiumCoordinator`가 기존 5분 fresh/총 age 1시간 availability cache·UID single-flight·webhook generation을 소유하고, async sender는 direct await, main sync worker는 bounded main-loop bridge, crawler child만 인증된 localhost batch endpoint를 사용한다. Redis/DB 공유 observation은 도입하지 않는다. 이 결정은 **FastAPI worker=1 · app replica=1**인 현재 production topology에서만 유효하다. v2.0은 이를 Dockerfile·Compose 구조 테스트와 배포 inventory로 증명하며, 자동 분산-owner 검출을 새로 만들지 않는다. topology를 증명하지 못하거나 scale-out/canary budget 위반이 생기면 rollout을 막고 Redis shared-store ADR 또는 SV-0을 다시 연다 | `Dockerfile:131`, `docker-compose.yml:103-124`; subprocess process-local cache 공백을 migration 없이 닫는 최소안 |
| D31 | S5/S11 Graph 성능 gate는 **Macrobenchmark로 확정**한다. S0에서 같은 AGP의 `com.android.test` 기반 `:macrobenchmark` 모듈, release 유사 `benchmark` variant, 결과 JSON 판정기를 만들고 S5에서 실제 `MainActivity → Tether` 사용자 여정을 고정 fixture·stable semantics tag로 완성한다. `androidx.benchmark:benchmark-macro-junit4:1.4.1`, `androidx.profileinstaller:profileinstaller:1.4.1`을 version catalog에 pin한다. 기준 장비는 **Samsung SM-F711N / Android 15(API 35) / 펼친 main display / 120Hz 고정**이며 S0 fingerprint를 S11까지 유지한다. S5 절대 gate는 각 여정의 3개 유효 run 중앙값이 **`frameOverrunMs P95 ≤ 0ms ∧ P99 ≤ 8.33ms`**인 것이고, 이를 통과한 artifact만 S11 회귀 golden을 만든다. `dumpsys gfxinfo framestats`는 triage 보조일 뿐 release pass/fail 대체물이 아니다 | Android 공식 Macrobenchmark setup·`FrameTimingMetric`·physical-device 권고; 2026-08-30 ADB 실측으로 API 35·1080×2640·120/96/60/48Hz 지원 확인 |

#### D23 상태 전이 (S1 소유, S2 Root가 소비)

| 상태 | 화면/runtime | 진입·이탈 규칙 |
| --- | --- | --- |
| `SignedOut` | Login only | Firebase UID 없음. stable state를 purge하되 user/KRX epoch를 먼저 rotate·persist하고 값을 reset/재사용하지 않음. **cold start의 첫 신원 관측이 UID 없음이면, 디스크에 이전 owner가 남아 있어도 시작 신원과의 연속성이 확인되지 않은 것으로 보고 그 시작에서 이 행을 적용한다. owner가 없더라도 보호 자료 marker(`mayContain*Data`)가 서 있으면 owner 불명으로 같은 rotate를 한다. owner도 marker도 없으면 추가 rotate는 하지 않지만 이미 기록된 pendingPurge는 평소대로 재개한다. 사용자 원본 preference(I4 uid scope)는 보존한다**(동결 후 6번 개정) |
| `Resolving(uid, userAccessEpoch, continuityEligible)` | 무료 snapshot | 새 UID 또는 same-UID cold start에서 서버 확정 전. continuity는 cache namespace 정리 provenance일 뿐 grant가 아니며 WS/bootstrap/Graph/알림 mutation/FCM 등록과 protected-cache read/render는 0 |
| `Pending(noGrant, continuityEligible)` | 무료 snapshot + 조용한 재확인 | 유효한 last-good 없이 **200 + `premium_pending=true`** 또는 권한 판정 불가. 재조회 시각은 직교 `RecheckSchedule`만 소유하며 `retry_after_seconds`/typed backoff 이전 재조회 금지; continuity가 있어도 protected cache는 봉인 |
| `FreeConfirmed` | 무료 snapshot | stable `premium_active=false`. 이전 premium runtime 또는 `mayContainPremiumData` marker가 있으면 user/KRX epoch rotate·cancel/purge·push DELETE. 로컬 구매·복원 성공은 fresh 조회와 bounded propagation probe(`[0,2,5,10,20]s`)만 시작 |
| `PremiumConfirmed(uid, userAccessEpoch)` | topic/bootstrap/Graph/알림 허용 | `.forcePremium`의 fresh stable ACTIVE만 진입시킨다. same-UID continuity epoch는 rotate하지 않고 이 승인 뒤에만 disk seed를 개방한다. **동일 UID·동일 프로세스에서 이후 조회가 판정 불가로 끝나도 이 상태를 유지하며 시간 만료가 없다**(iOS parity; 아래 reducer 계약). 로컬 false는 강제 재확인 신호이지 단독 veto가 아니다 |
| `Rejected` | 즉시 무료 전환 | WS `premium_required`(fresh stable `premium_active=false`는 `FreeConfirmed` 행이 담당 — 아래 reducer가 정본). 일반 protected REST 403은 stable code가 없으므로 곧바로 이 상태를 만들지 않고 `.forcePremium`으로 재확인한다. user/KRX epoch rotate, premium cache/topic/graph 폐기, push 해제. fresh ACTIVE만 해제 |
| `DeletionPending(uid, phase)` (S10 확장) | 삭제 재시도 UI only | backup 제외 tombstone이 Root보다 우선한다. `REQUESTING_SERVER`, `SERVER_DELETED`, `FIREBASE_DELETE_IN_FLIGHT`, `FIREBASE_DELETED` 어느 phase도 일반 API·WS·mutation·FCM/register-device를 열지 않고, 허용 동작은 재인증·idempotent server DELETE·SV-4 receipt 확인·Firebase Auth DELETE뿐 |

재조회 예약은 접근 상태와 **직교**한다. `PremiumAccessCoordinator`가 process-local 단일
`RecheckSchedule(uid, userAccessEpoch, mode, notBefore, refreshGeneration)`을 소유하고, `Pending`과
`PremiumConfirmed`가 같은 owner를 공유한다. 예약은 현재 grant를 만들거나 연장·만료시키지 않는다. 중복 pending/실패는
기존 `notBefore` hard floor를 앞당기지 않고 한 task로 합치며, stable non-pending 응답·reset/logout·UID 전환·typed reject에서
cancel/invalidate한다. background에서 별도 wake를 보장하지 않고 foreground 복귀 시 due 여부를 먼저 평가한다. process death에는
예약을 복원하지 않으며 cold start의 `Resolving`이 새 조회를 시작한다. 늦은 결과는 captured UID/`userAccessEpoch`/
`refreshGeneration` 중 하나라도 다르면 폐기한다.

동일 UID에서 entitlements 조회가 transport/HTTP/decoding 판정 불가로 끝나면 **새 권한을 만들지 않되 기존 premium runtime `PremiumConfirmed`는 그대로 유지한다 — 클라이언트 시간 상한은 두지 않는다.**
서버의 권한 경계와 클라이언트 표시 연속성은 분리한다. WS는 topic lease를 publish 직전에 집행하고, REST는 요청마다 premium/KRX를
다시 판정한다. 일반 REST premium 판정은 provider 장애 중 cache age `<1시간`인 값을 조건부 fallback할 수 있지만
(`app/subscription.py:454-465`), 이는 **요청이 있을 때 적용되는 서버 판정 정책이지 클라이언트 표시 TTL이 아니다**. 사용할 cache가
없고 provider 판정도 불가한 상태에서 `/api/entitlements`를 호출하면 200 + `premium_pending=true` + `krx_visible=false`가 오며,
클라이언트는 그 명시 응답을 즉시 적용한다. 응답 자체를 받지 못한 경우에는 별도 만료를 합성하지 않는다. 보호 API와 새 데이터의
최종 허용은 항상 서버가 집행한다.
새 UID·프로세스 cold start에는 last-good premium/KRX **grant**를 이관하지 않는다. 다만 backup 제외 control store의
`(ownerUid,userAccessEpoch,krxCapabilityEpoch,mayContainPremiumData,mayContainKrxData,pendingPurge)`는 정리 provenance로
유지한다. same-UID clean cold start는 epoch를 보존하되 protected cache를 봉인하고, fresh ACTIVE 뒤에만 같은 namespace를
개방한다. fresh false/typed reject/UID 변경/logout/KRX false는 해당 marker가 가리키는 old namespace를
`새 epoch+pendingPurge persist → old namespace purge → journal clear` 순으로 정리한다. marker는 권한 정보가 아니며
protected/KRX 데이터를 쓰기 전에 해당 `mayContain*Data=true`가 먼저 persist돼야 한다.

#### D23 reducer 전이 계약

| 입력 | 유효 last-good 없음 | 이전 `PremiumConfirmed` 있음 | 공통 부수효과 |
| --- | --- | --- | --- |
| `.forcePremium` fresh stable ACTIVE | 현재 `userAccessEpoch`로 `PremiumConfirmed`(fresh 승인 전 cache 봉인) | `PremiumConfirmed` 유지 | grant 자체는 epoch를 rotate하지 않음. fresh KRX true는 현재 `krxCapabilityEpoch`를 승인 뒤 개방하고 false만 KRX epoch rotate·purge; push admission 재평가 |
| 일반/`.forceEntitlements` cached ACTIVE | grant 금지, `.forcePremium` single-flight 시작 | 상태 유지 | KRX true는 premium runtime이 열려 있을 때만 유효; **false는 edge-trigger로 즉시 capability hide/purge** |
| stable `premium_active=false` | `FreeConfirmed` | `FreeConfirmed` | 기존 protected runtime 또는 `mayContainPremiumData` marker가 있으면 user/KRX epoch rotate→cancel/purge→push DELETE |
| **200 + `premium_pending=true`** (typed 판정 보류 envelope) | `Pending(noGrant, continuityEligible)` | **`PremiumConfirmed` 유지** | provider unavailable/cache miss에서도 나올 수 있으므로 authoritative premium false가 아니다. 다만 envelope의 fail-closed **`krx_visible` 값은 iOS parity로 즉시 적용**한다 — `false`면 KRX capability를 edge-trigger로 hide/purge(`EntitlementsManager.swift:437`이 pending 분기 **앞**에서 적용). 이미 hidden이고 `mayContainKrxData=false`·pending purge 없음이면 false→false는 epoch rotate/purge 없이 재조회만 예약한다 |
| 판정 불가(network·408/429/5xx·token replay 후 401·code 없는/미지 403·decode/protocol 오류) | `Pending(noGrant, continuityEligible)` | **`PremiumConfirmed` 유지, 시간 만료 없음** | affected operation은 중단. 401은 auth 복구, code 없는/미지 403은 `.forcePremium`, 그 밖은 typed backoff로 재확인한다. 확정 결과 전 epoch rotate·hide/purge·push DELETE·teardown 타이머는 0 |
| typed `premium_required` | `Rejected` | `Rejected` | 즉시 user/KRX epoch rotate→전체 protected runtime cancel/purge→push DELETE |
| typed `krx_entitlement_required` | premium 상태 유지 | premium 상태 유지 | KRX capability epoch만 rotate→즉시 hide/purge, `.forceEntitlements`; 전체 premium을 거부하지 않음 |

#### D24 variant 계약

| artifact | `TOPIC_V2_RELEASE_ON` | signing | 용도 |
| --- | --- | --- | --- |
| debug-off | OFF | debug | OFF UX·network-zero 회귀 |
| ci-minified | ON | ephemeral/debug | R8·serialization·v2 경로 보존 검증 |
| internal/closed | ON | 비공개 배포 서명 | 실제 서버 E2E |
| public AAB | ON 강제(OFF면 task 실패) | production | 별도 arming + upload GO 이후만 생성 |

### 5.2 미결정 로그 (동결 후 계속 추적)

| ID | 미결정 | 후보 / 제약 |
| --- | --- | --- |
| **O1** | **SV-0 중앙 evaluator ingress 방식** (rollout 차단 아님) | 후보 ① transactional DB outbox ② Redis Stream 등 durable queue ③ 중앙 전환 없이 gated legacy 유지. **process-local bridge와 Redis Pub/Sub는 부적격**(비내구성). ②는 현 Redis의 `100mb + allkeys-lru`에서 stream eviction이 가능하므로 별도 persistence/maxmemory/consumer-group 설계가 선행돼야 한다. ①은 **`db.commit() → Redis write → process_rate_alerts` 직렬 순서가 spec으로 고정**돼 있어(`crud.py:688-691`) 그 경계를 바꾸며, event identity·claim/lease·retry·dead-letter·ordering·중복 FCM·central↔legacy watermark/drain·rollback·rate-write 실패 경계를 전부 정해야 한다 → **채택 시 서버 ADR 선행**. 어느 후보든 **SV-1의 선행조건이 아니다** |
| ~~O2~~ | 서버 알림의 별도 **positive stale horizon** | **v2.0에서 새 정책을 도입하지 않고 회수.** premium은 현재 서버 `verify_premium_status`의 positive availability-first 의미(`ACTIVE`만 allow)를 재사용하고, KRX G1/G2/G3 각 축은 stale 없이 fail-closed한다. 단 KRX 최종식의 **premium 결합항에는 같은 조건부 stale ACTIVE가 적용**될 수 있다. 별도 15분 horizon·차단 중 observation 보존/재생 정책은 [`ANDROID_NOTIFICATION_RELIABILITY_FOLLOWUP.md` §5.H](ANDROID_NOTIFICATION_RELIABILITY_FOLLOWUP.md#h-premium-provider-장애-중-서버-발송-정책)에 이관한다. |
| ~~O3 · O4~~ | (초안 N차에서 각각 **D7**[Citi 편집]·**D26**[무료 preview]으로 승격되어 미결정에서 회수. **번호 재사용 금지** — 아래 O5·O6·O7을 재번호하지 않는 이유) | — |
| ~~O5~~ | Android runtime의 premium/KRX last-good 상한 | **회수(2026-08-30 사용자 결정) — iOS와 동일하게 클라이언트 시간 상한을 두지 않는다.** 사유: 동일 UID·동일 프로세스에서 이미 확인한 grant를 **권위적 false 없이 Android 자체 15분 타이머만으로** 종료하는 것은 제품 의도와 어긋난다. 확인된 사실이 이를 뒷받침한다 — ① iOS는 이미 `krxVisible=true`라면 조회 오류가 나도 값을 유지하고 시간으로 만료시키지 않는다(`EntitlementsManager.swift:420,465-483`; `freshnessTTLSeconds=10`은 중복조회 debounce). ② 서버는 새 데이터 접근을 독립적으로 집행한다 — WS topic lease와 REST의 요청별 premium/KRX 판정은 클라이언트 표시 TTL이 아니며, 일반 REST의 `<1시간` positive-stale fallback도 **요청 시점의 서버 가용성 정책**이다(`app/subscription.py:454-465`). ③ 서버가 여전히 인가하는 동안 REST Graph/snapshot은 새 값을 정상 제공할 수 있으므로 클라이언트 타이머가 이를 선제 은폐하면 안 된다. 단 `/api/entitlements`가 200+`premium_pending=true`+`krx_visible=false`를 실제 반환하면 iOS와 동일하게 false를 즉시 적용한다. 따라서 이 결정은 **모든 서버/provider 장애에서 표시 유지를 보장한다는 뜻이 아니다**. 시간 상한 또는 pending false 정책은 실제 철회 지연·오래된 KRX 잔상·pending 유발 오강등 사건이 생기면 **양 플랫폼 공통 후속**으로 연다 |
| ~~O6~~ | Android data-only FCM·binding·Worker·queue drain | **v2.0에서 회수.** iOS와 같은 `notification+data`를 사용하고, delivery profile·quarantine·28일 drain은 후속으로 이관한다. |
| ~~O7~~ | 입력 신선도·조건-event 보존·전달 만료 절충안 | **v2.0에서 회수.** age tier·client ACK·miss 재평가·summary/nudge를 구현하지 않는다. 실제 지연/오래된 값/FCM 수락 후 미표시 사건이나 운영상 명확한 신호가 생기면 별도 후속 계획으로 재검토한다. |
| ~~O8~~ | **SV-1 cross-process premium 관측·호출 예산** | **D30 B-IPC로 승격·회수.** Redis shared observation(A)은 현 topology에서 과하며, SV-0 선행(C)은 rollout 범위를 불필요하게 키운다. D30 canary 또는 worker/replica 증가가 trigger가 되면 별도 서버 ADR로 다시 연다. |

> O6/O7에서 검토했던 설계와 선택하지 않은 이유, 재검토 조건은
> `ANDROID_NOTIFICATION_RELIABILITY_FOLLOWUP.md`에 보존한다. 번호는 감사 이력을 위해 재사용하지 않는다.
> **O5 회수의 범위.** iOS parity로 돌아가되 다음은 그대로 유지한다 — 명시적 거부(fresh stable false / typed
> `premium_required` / typed `krx_entitlement_required`)·UID 변경·logout은 각 범위의 즉시 hide·purge, **저장된 사용자가
> 같은 UID로 복원된** 앱 재시작은 grant를 복원하지 않아 hide/seal하되 명시적 거부 없이 보존 namespace를 purge하지
> 않는다 **(단 그 UID의 로그아웃 의도가 기록돼 있으면 그 정산이 먼저다). UID 없이 시작한 재시작은 D23 `SignedOut`
> 행을 따른다**(동결 후 6번 개정). **200 pending envelope의
> `krx_visible=false`도 edge-trigger로 즉시 적용**한다. 사라지는 것은 *시간에 의한* 만료뿐이다.

#### D30 B-IPC 실행 계약

- main event loop의 process-scoped `AlertPremiumCoordinator`가 현재 `verify_premium_status` 의미와 cache를 소유한다.
  동일 UID는 single-flight, alert 경로의 distinct-UID provider in-flight는 **최대 4**, server batch hard deadline은
  **5.0초**다. async source/comparison sender는 직접 `await`, main scheduler sync worker는 DB session을 놓은 뒤
  `run_coroutine_threadsafe` 계열의 bounded request-response bridge를 사용한다. bridge를 기다리는 default-executor
  worker도 process-wide **최대 4개**이며 초과는 deny/no-mutation+metric이다. crawler child는 동기 HTTP client를 쓴다.
  이 세 경로 어디에도 `asyncio.run`을 쓰지 않으며, 기존 fire-and-forget `topic_trigger_bridge`도 판정 응답용으로
  재사용하지 않는다.
- crawler subprocess만 `POST http://127.0.0.1:8000/_internal/alert-auth/batch`를 호출한다. request는 UID를 재차
  dedupe하며 **최대 512 UID / raw body 1MiB / UID 128 Unicode 문자·UTF-8 512 bytes**, extra field 금지다.
  UID 상한은 bytes가 아니라 128자 계약이다(`ops/run_rc_canary.sh:111-147`,
  `tests/test_rc_canary_runner.py:244-265`). raw stream을 cap까지 읽기 전에 Pydantic/JSON 전체 parse를 시작하지 않는다.
  응답은 UID별 `ACTIVE|INACTIVE|PENDING`과 attempt generation만 포함하고 provider 원문·token을 포함하지 않는다.
  **512는 transport/parse 상한이지 cold UID 처리 보장이 아니다.** 한 평가의 distinct UID가 512를 넘으면
  chunk/fairness state machine을 만들지 않고 전체 평가를 deny/no-mutation하며 `candidate_uid_overflow` critical metric을
  남긴다. 512 이하는 cache hit을 먼저 확정하고, cold UID는 concurrency 4와 같은 absolute 5초 deadline 안에서만
  시도한다. deadline 안 처리되지 않은 UID는 deny하며
  `candidate_uid_total/cache_resolved_total/provider_attempt_total/allowed_total/denied_total/budget_unprocessed_total`을
  원문 UID 없이 기록한다. 여기서 `denied_total`은 명시 verdict/error이고 budget 미처리는 마지막 항목에만 센다.
  `ACTIVE`만 send한다.
- shared `fetch_revenuecat_result`의 전역 HTTP timeout(현재 5.0초)은 D30 때문에 임의로 1.5초로 바꾸지 않는다.
  이는 총 wall-clock 제한이 아니라 HTTPX 단계별 inactivity timeout이므로 D30의 task 수명으로 대신 쓰지 않는다.
  새 UID owner는 첫 요청의 absolute batch deadline을 `ownerDeadline`으로 고정하고, semaphore 대기와 provider fetch 전체를
  `asyncio.timeout_at(ownerDeadline)` 안에서 수행한다. 뒤에 합류한 waiter는 deadline을 연장하지 않으며 **남은 batch
  budget**까지만 `shield(ownerTask)`를 기다려 한 waiter의 timeout/cancel이 공유 owner를 취소하지 않게 한다. 늦은 결과는
  해당 send에 사용하지 않는다. process-wide pending-owner UID는 **최대 512**이며 초과는 deny/no-mutation+metric이다.
  owner는 timeout/cancel/success 모두 `finally`에서 registry를 제거한다. coordinator shutdown은 admission close → owner cancel →
  `gather(return_exceptions=True)` → registry 0 순서다. provider in-flight≤4·pending≤512·registry/queue bounded를
  fake-clock/load test로 증명한다.
- FastAPI lifespan이 scheduler보다 먼저 256-bit per-boot token을 만들고 scheduler·hana·woori 세 spawn 경로의
  **명시적 child env**에만 넘긴다. endpoint는 loopback peer와 `X-FXi-Internal-Token`을 모두 확인하고
  `hmac.compare_digest`를 쓴다. Uvicorn은 Dockerfile에서 **`--no-proxy-headers`를 명시 고정**해 외부
  `X-Forwarded-For`가 peer 주소를 바꾸지 못하게 하고 구조 테스트로 잠근다. endpoint는 `include_in_schema=False`,
  token/원문 UID 로그 금지, 재시작마다 token을 회전한다. old/orphan child의 구 token은 fail-closed다.
  `--no-proxy-headers`를 Dockerfile CMD에 넣을 때 **`DOCKER.md`의 CMD 복제본을 같은 커밋에서 갱신**한다
  (기존 테스트가 두 CMD의 바이트 동일성을 단언하므로 한쪽만 고치면 CI red).
- **nginx 규칙은 `listen 443 ssl` server 블록의 catch-all `location /`과 같은 레벨에 둔다**
  (`listen 80` 블록은 redirect-only라 대상 아님). 단일안은
  **`location ^~ /_internal/ { return 404; }`** 이며 이 location 안의 `proxy_pass`·rewrite·named-location 전달은 금지한다.
  외부에 404 status를 주는 것이 계약이고 임의 미등록 FastAPI 경로와 body/content-type/timing까지 동일하게 만드는 것은
  보안 경계나 KRX 비노출 요구가 아니므로 v2.0 범위에 넣지 않는다. 앱 endpoint도 non-loopback·invalid token은 generic
  404로 거부하되 nginx 응답과 byte-identical하다고 주장하지 않는다.
- **배포 순서를 못 박는다.** 같은 승인 SHA를 checkout한 뒤 다음 순서를 하나의 runbook으로 실행한다.
  1. route를 포함한 FastAPI는 아직 재생성하지 않은 상태에서 현재 host 파일을 mount하는
     `docker compose run --rm --no-deps nginx nginx -t`로 새 설정을 선검증한다.
  2. `docker compose up -d --no-deps --force-recreate nginx`로 deny 규칙을 먼저 로드하고, pre/post nginx container
     ID·`StartedAt` 변경, container 내부 `nginx -t`, host↔runtime config SHA-256 일치,
     외부 `/_internal/...` 404를 봉인한다.
  3. FastAPI candidate를 재생성한다.
  4. nginx upstream이 정적 `server fastapi:8000`이라 새 container IP를 다시 resolve하도록 nginx를 다시 재생성하고,
     container ID·`StartedAt` 변경, config SHA-256, 외부 `/health`와 대표 인증 API, `/_internal/...` 404를 재검증한다.
  역순 또는 마지막 nginx 재생성 누락은 §10.1 즉시 중단 조건의 사전 위반이다. rollback도 internal deny를 유지한 채
  FastAPI를 내린 뒤 nginx를 다시 재생성하고 같은 일반 API smoke를 통과해야 한다.
  `DOCKER.md`의 nginx bind-mount 배포 절차와 `KRX_CANARY.md` 04b의 hash 알고리즘은 검증된 선례지만 현재 독립 실행
  helper는 아니다. SV-1에서 **`ops/verify-nginx-runtime-config.sh`** 로 추출하고 D30 runbook과 `KRX_CANARY.md` 04b가
  같은 helper를 호출하게 하며, 기존 `test_krx_deploy_verifier.py`의 hash/fail-closed fixture도 그 실행 파일을 정본으로
  바꿔 host/runtime SHA 검사가 사본별로 갈라지지 않게 한다.
- **인증과 body read의 선후**: peer가 loopback이 아니거나 token이 불일치하면 **request body를 한 바이트도 읽지 않고**
  즉시 거부한다(단일 worker가 요청당 최대 1MiB를 소비한 뒤 거부하는 일이 없게). Pydantic body parameter가 auth보다
  먼저 parse되는 구현을 피하고 `Request` header/peer 검사 뒤 bounded raw stream을 읽는다. non-loopback이 handler에
  닿으면 원문 IP/UID 없이 `internal_auth_nonloopback_total`을 body read 전에 증가시켜 nginx 외부 차단 증거로 쓴다.
- **전송 결속 대안 기각 기록**: UDS 또는 별도 loopback-only 리스너를 쓰면 TCP 표면 자체가 사라져 drift 의존·배포 순서·
  외부 proxy 표면 두 위험이 소멸한다. 그럼에도 loopback peer + per-boot token + `--no-proxy-headers` 3중과
  위 배포 순서 게이트로 충분하다고 보아 채택하지 않는다. UDS/별도 포트는 두 번째 listener와 별도 lifespan/shutdown
  ownership을 추가하므로 현재 single-owner 최소안보다 복잡하다. **비교하지 않은 것이 아니라 기각한 것이다.**
- endpoint는 pure async다. DB dependency·`to_thread`·default executor·동기 I/O를 금지하고, caller도 DB
  transaction/session을 쥔 채 bridge/HTTP를 기다리지 않는다. startup 직후 endpoint accept 전 child가 뜨는 경합은
  최초 실패 뒤 **최대 2회(50ms, 150ms)** localhost connect retry로 흡수하되 absolute deadline을 연장하지 않고
  fixture로 잠근다. 연결은 되었으나 HTTP 응답이 실패/timeout인 경우에는 같은 평가에서 재전송하지 않는다.
- webhook invalidation과 cache write race를 막기 위해 process-local UID generation을 `subscription.py`의 공통
  state-machine layer에 둔다. alert coordinator뿐 아니라 **모든** `verify_premium_status*` fetch가 시작 generation을
  캡처하고 `_cache.set`/`_pending.mark`는 같은 generation일 때만 허용한다. webhook은 generation 증가 후 cache/pending을
  clear한다. 반환 직후에도 **중간 await 없이** generation을 비교해 달라졌으면 invalidate+deny한다. 이는 fetch 중 stale
  cache 부활만 막는다. REST/WS까지 provider single-flight라고 과장하지 않으며, 판정 응답 뒤 FCM 시작 전 revoke race와
  이미 시작/수락된 RPC는 현 SV-1 TOCTOU 한계로 남는다.
- child runtime budget은 runner start monotonic 기준으로 고정한다. `<32초`는 refresh 허용(server 5.0s/client 5.5s),
  `32초 ≤ elapsed < 38초`는 cache-only(server 0.25s/client 0.5s: fresh 또는 total-age `<1h` stale ACTIVE만 allow),
  `elapsed ≥38초`는 IPC도 시작하지 않고 deny한다. cache miss/expired/false/불완전 UID는 모두 deny/no-mutation이다.
  이 값은 **추가 auth 지연 상한**이지 child timeout/알림 누락 0 보장이 아니다. FCM sync에 hard timeout이 없고
  changed-rate만 평가하므로 timeout 뒤 같은 rate가 유지되면 crossing이 영구 누락될 수 있다.
- production invariant는 **Uvicorn worker=1 + app replica=1**이다. `tests/test_ws_message_limit.py`가 Dockerfile의
  `--workers=1`·`--no-proxy-headers`, Compose의 고정 `container_name`·`replicas` 부재·command override 부재를 잠근다.
  release evidence는 production의 FastAPI container/task와 nginx upstream target이 각각 정확히 1개임을 증명한다.
  이는 별도 runtime 분산 lease를 뜻하지 않는다. inventory로 단일성을 증명하지 못하면 rollout을 차단하고,
  scale-out 전에는 Redis shared-observation ADR 또는 SV-0 중앙 durable evaluator로 전환한다.
- **DoD**: main async+thread+4 child의 동일 UID 동시 요청에서 provider 1회 / 512 UID cold batch에서 provider
  in-flight≤4·해당 batch에서 생성된 pending queue·registry가 5.1초에 모두 0·deadline 뒤 send 반영 0 /
  HTTP trickle response도 absolute deadline을 넘지 않음 / 한 waiter cancel 뒤 다른 waiter는 정상 합류 /
  lifespan shutdown 뒤 owner task·registry 0 / caller의 513 distinct UID는
  endpoint 호출·provider·FCM 0 + overflow metric / 128자 다중바이트 UID 허용·129자/UTF-8 513-byte UID 거부 /
  `tests/test_route_auth_inventory.py`에 **INTERNAL 정책 클래스**를 추가하고(모듈 상단 set 정의 + 기존 `groups` 튜플 **한 곳**, 추가 전까지 CI red),
  그 클래스의 모든 route가 schema에서 빠지고 `route.body_field is None`인 Request-only handler이며, body receive 전에
  peer/token check를 직접 호출한 뒤에만 수동 decode함을 구조 검증 /
  invalid token·non-loopback은 generic 404 + ASGI receive 0회/body read 0바이트 + provider 0 /
  valid internal token의 1MiB 초과는 cap 이후 parse/provider 0 /
  nginx 구조 테스트에서 HTTPS server의 `^~ /_internal/`가 direct 404이며 proxy/rewrite 0 /
  nginx-first와 FastAPI recreate 후 nginx-second runbook에서 container ID·`StartedAt`·host/runtime SHA·외부 404·
  `/health`/대표 API green, 외부 probe 전후 `internal_auth_nonloopback_total` 불변 /
  31.999초 refresh·32.000초 cache-only·38.000초 IPC 0 / endpoint의 DB·default-executor 사용 0 /
  webhook-during-fetch stale allow/cache resurrection 0 / scheduler·hana·woori 실제 subprocess가 같은 owner cache를 소비 /
  child direct RevenueCat 호출 0 source tripwire / shutdown·startup 경합 deadlock 0.

**D30-CANARY 단일 게이트.** 아래 표가 SV-1·S11·§10.1에서 재사용하는 정본이며 다른 곳에 ‘3개 지표 0’ 사본을 두지 않는다.

| 구분 | 통과 조건 |
| --- | --- |
| 통제 positive/negative | known-ACTIVE UID가 main async·main sync bridge·runner child 각 경로에서 `allowed_total>0`; known-INACTIVE는 모두 deny. controlled ACTIVE의 `provider_unavailable\|cache_cold\|budget_unprocessed_total`은 0 |
| 24시간 실제 분모 | `candidate_uid_total>0` **그리고** runner 기원 `allowed_total+denied_total>0`; 0이면 통과가 아니라 창 연장 또는 동일 caller를 쓰는 합성 부하로 대체 |
| 보존식 | `candidate_uid_total = allowed_total + denied_total + budget_unprocessed_total`; `provider_attempt_total ≤ candidate_uid_total`; endpoint/FCM 원문 UID 로그 0 |
| infrastructure zero | invalid-token/non-loopback accept 0, production `internal_auth_nonloopback_total=0`, IPC connect/response timeout 0, **`budget_unprocessed_total=0`**, RevenueCat 429=0, D30에 기인한 child 45초 timeout=0, `candidate_uid_overflow=0` |
| provider 관측 | 실제 사용자의 `provider_unavailable\|cache_cold`는 원인이 외부 장애·정상 cold miss일 수 있어 미정 숫자 임계값으로 자동 판정하지 않는다. 단 **0이 아니면** family/sender별 count·rate·원인과 `expected_transient\|known_cold_start\|incident` 처분을 evidence에 남기고, 이를 수용하는 **별도 rollout 확대 GO** 없이는 다음 cohort로 확대하지 않는다. controlled ACTIVE 실패 또는 infrastructure-zero 위반은 즉시 gate 실패다 |

gate가 실패하면 수치를 임의 확대하지 않고 원인을 수정하거나 Redis ADR/SV-0을 재검토한다.

---

## 6. 서버 트랙 (SV-0 ~ SV-4)

> Android 구현의 **직렬 선행조건은 아니다.** 병렬 진행하되 **SV-1·SV-2가 공개 rollout을 차단**한다(I6).
> SV-0·SV-4와 deferred SV-3는 비차단이다.
> 이 트랙의 문서 반영(ADR / 서버 리포 문서)은 별도 승인 대상이다.

### SV-0 — 중앙 evaluator 전환 (아키텍처 정리 · **공개 rollout 차단 아님**)

**문제(실측).** 세 알림 family 중 은행 알림은 크롤러 저장 경로에서 **동기 인라인 발송**된다:
`app/crud.py:686 insert_bank_rates_into_db` → `:744` / `:660` `process_rate_alerts`.
그리고 shinhan·ibk·nh·sc는 **항상 별도 subprocess**에서 실행된다
(`app/scheduler.py:308 create_subprocess_exec(... "-m","app.crawlers.runner", …)` → `app/crawlers/runner.py:37-40`).
hana·woori는 Selenium fallback 시에만, kb·bs·citi·investing은 in-process다.

기존 FX cutover 골격(`FxNotificationBackend` `alert_storage_backend.py:238`,
`FxCanaryBackend` `:369`, `fx_alert_shadow.py`, `config.py:434/444`)은 존재하지만
**subprocess를 덮지 못한다**: `topic_trigger_bridge`는 IPC가 아니라 **동일 프로세스**의
worker-thread → main event loop 마샬링이고(`app/topic_trigger_bridge.py:31,40`),
`_main_loop`는 lifespan startup에서만 등록된다. subprocess에는 lifespan이 없어
(`runner.py`에 `register_main_loop`·bridge 호출 0건) enqueue가 skip되고 **legacy 인라인 발송으로 되돌아간다.**

> ⚠️ **설계 위험**: 이 상태로 cutover를 켜면 in-process 은행만 중앙 경로를 타고
> **4개 은행은 조용히 legacy 경로를 유지**한다. telemetry상으로는 완료로 보이는 반쪽 상태가 된다.

**결정.** 기존 FX cutover 트랙을 **기반으로 채택**하되, SV-0을 단순 canary 확대가 아니라
**cross-process authoritative cutover 설계**로 정의한다.
동시에 **SV-0을 SV-1의 선행조건에서 분리한다** — 권한 게이트(SV-1)는 중앙 전환을 기다리지 않고
현재의 legacy·subprocess 발송 경로에 **직접** 설치한다(§SV-1). SV-0은 그 위에서 진행하는 구조 개선이며
공개 rollout을 차단하지 않는다.

**SV-0 산출물**
- subprocess → 중앙 evaluator **durable ingress** (방식 = **O1**)
- full-live production backend (현재 `FxNotificationBackend.persist_result`는 shadow no-op `:328`, 실제 저장은 allowlist canary `:387`에만 있음)
- 명시적 mode: `legacy` / `canary` / `central`
- authoritative enqueue **실패 정책** (실패 시 발송하지 않음 + 경보)
- FX CRUD 캐시 무효화
- 발송 직전 **최신 device token 재조회**
- comparison trigger 단일 owner
- premium gate가 유지되는 rollback 경로

### SV-1 — 발송 직전 권한 게이트

**문제(실측).** 세 family의 실제 발송 경로는 현재 premium/entitlement를 재확인하지 않는다.
은행 후보 쿼리는 bank/currency/enabled/triggered만 보며, source·comparison evaluator에도 premium/KRX
권한 판정이 없다. 서버가 visible `notification+data`를 보내므로 background/killed 앱은 OS가 먼저 표시해
client gate로 보완할 수 없다.

**병행 크롤러 스케줄 트랙과 배포 격리.** 2026-08-30 기준 서버 worktree는 clean이고, 최근/진행 중인
크롤러 최적화는 주로 `switch_jobs`·IBK 수집 경로를, 이 슬라이스는 `execute_with_timeout`과 hana/woori
subprocess lifecycle을 바꾼다. 즉 현재 text conflict나 동일 함수 동시 편집은 아니지만, 호출 빈도와 단일 직렬
worker의 head-of-line 부하는 직접 결합된다. **스케줄/수집 경로 변경과 아래 PIPE·PGID·cancel 변경을 한 commit·한
배포·한 관측창에 섞지 않는다.** 어느 쪽을 먼저 하든 각자 baseline SHA·image/container identity·은행/모드/실제
수집 경로별 scheduled/executed/max-instance-skip 분모·duration·primary 실패·Selenium 진입/timeout·최종 모든 URL 실패·
3통화 완전성·queue reject·worker restart·orphan을 고정하고 독립 배포·관측·rollback한다. Hana/Woori top-level은
모든 fallback 실패를 로그만 남기고 반환할 수 있고 부분 통화도 저장하므로, outer wrapper의 success를 수집 성공으로
쓰지 않는다. 2026-09-01 전후 2차 스케줄 배포가 진행되면 그 관측과 판정을 먼저 닫은 뒤 SV-1 원자 patch로 handoff하는
것을 기본 순서로 하되, **현 리비전 관측창에서** stuck/queue pressure/orphan 또는 fallback/skip 증가가 나타나면
스케줄 확대를 멈추고 SV-1을 먼저 적용한다. 이는 Android scope-freeze blocker가 아니라 서버 변경의
원인 귀속·rollout gate다.

**subprocess 선행 보정(SV-1 완료조건에 포함).** `execute_with_timeout`은 현재 stdout/stderr를 `PIPE`로 만든 뒤
소비하지 않고 `proc.wait()`부터 기다린다(`app/scheduler.py:308-315`). 충분한 출력이 누적되면 child가 막혀 45초
timeout을 모두 쓰며, **Python patch level에 따라** 뒤의 `kill() → wait()`까지 정지할 수 있다. 2026-08-30 prod의
CPython 3.13.15는 gh-119710 backport로 direct child exit 시 pipe EOF와 무관하게 waiter를 깨우지만, 로컬 3.13.5에서는
SIGKILL 뒤에도 unread PIPE를 drain하기 전 `wait()`가 반환하지 않는 것이 재현됐다. Dockerfile도 floating
`python:3.13-slim`이고 `proc.kill()`은 Chrome descendant를 정리하지 않으므로, 제품 계약은 특정 patch 동작에 기대지 않는다.
발생 임계는 StreamReader limit·transport chunk·OS pipe에 따라 달라지므로 “64KiB에서 반드시 발생”이라고 고정하지 않는다.
이 경로에는 stdout/stderr protocol consumer가 없으므로 새 drain
state machine을 만들지 않고 **app-owned PIPE 자체를 제거**한다. 현 stdout은 읽히지 않은 채 버려지고 child의 structured
로그는 파일에도 남는다. 다만 ⚠️ **그 파일이 안전한 유일 사본이 아니다** — 부모·crawler child·cron one-shot 컨테이너가
모두 같은 `logs/app.log`에 **각자 `RotatingFileHandler`로** 쓰고(`app/logging.py:93-108`), 한 writer가 회전시키면 다른
writer가 붙잡은 inode가 backup으로 밀려 `app/admin/log_reader.py:43,51`(base 파일만 읽음)이 그 레코드를 못 본다.
→ 세 spawn 경로 모두 **`stdout=None`·`stderr=None`(parent/Docker stream 상속)** 으로 통일한다.
교착 회피에 필요한 것은 app-owned PIPE 제거뿐이고, 상속하면 child 로그가 파일 회전과 독립된 Docker json-file 사본에도
남는다. 이 사본은 Compose의 `50m × 3` 회전 상한을 따르지만 logging driver의 기본 blocking backpressure와 파일+stdout
중복량은 남으므로 canary에서 로그량·write 지연을 관측한다. 이를 비차단·무손실 채널이라고 과장하지 않는다.
같은 child `LogRecord`가 Docker와 `app.log` 양쪽에 생기므로 두 소스를 합산하는 판정기는 inner timestamp·message·bank·
path·outcome 등 의미 키로 source 간 dedup하거나 한 소스만 정본으로 쓴다. 현재 임시 IBK 판정기의 dedup이 동작하더라도
Hana/Woori fallback 집계와 `stdout=None` 배포 뒤에 동일 fixture로 다시 잠그며, 캡처만 늘고 summary가 없는 상태를 green으로
읽지 않는다.
**동반 수정(필수)**: `app/scheduler.py`의 non-zero exit 분기에 있는 `stderr = await proc.stderr.read()`와
hana/woori의 `result.stderr` 소비를 **함께 삭제**하고 bank/pid/exit_code/elapsed structured log로 대체한다.
삭제하지 않으면 scheduler의 `proc.stderr is None` → **AttributeError** → 상위 `except Exception`이 잡아
`crawler_stats.record_failure`가 **두 번** 호출되고(1회 실패가 `fail_count` 2로 기록 → 관리자 성공률 왜곡), 로그도
'subprocess 실행 실패'로 오귀속된다. raw stderr 앞 500자 parent 재기록은 없어지지만 원문은 Docker log에 남는다는
trade-off를 수용한다.

운영 `error.log`의 2026-08-17·18·24 IBK 45초 timeout 3건은 모두 8/29 POST-first 전 구 Selenium
날짜변경 실패→재시도 경로에서 발생했다. timeout 직전 보존 warning/error는 각 8줄·4,112 bytes이고
ChromeDriver도 DEVNULL 출력이라, 이를 PIPE 포화의 원인 증거로 쓰지 않는다. 반대로 각 timeout 약 1분 뒤
실행시간 71~77초인 Chrome/ChromeDriver 12개가 zombie cleanup에 잡혔다. PID lineage가 없어 전부 같은 IBK
실행의 후손이라고 단정하지는 않지만, direct-child `kill()`이 descendant 정리를 보장한다는 현 주석을 부정하는
강한 운영 상관 증거다. 당시 image의 Python patch version은 보존 증거가 없고 현 3.13.15 image는 8/29 생성됐으므로
소급하지 않는다. 정확 문구 기준 `Selenium Worker 멈춤 감지`·`worker_restart`는 0건이며, 단순
`grep 'Worker|worker'` 18건은 health-check job misfire라 stuck 분모에 포함하지 않는다.

process cleanup은 별도 계약이다. **적용 범위는 spawn 경로 3개** — `execute_with_timeout` + hana/woori
`_run_selenium_subprocess_fallback` 2개다(`hana.py:133`, `woori.py:154`). 후자는 현 `capture_output=True`가 내부
`communicate()`를 써 PIPE 교착 대상은 아니지만 `start_new_session` 인자가 없어 **descendant 고아 문제는 동일**하다 →
공통 sync helper의 `Popen(stdout=None, stderr=None, start_new_session=True)` + `wait(timeout=)`로 바꾸고
`TimeoutExpired` 시 같은 killpg 시퀀스를 쓴다.
단 대칭화 범위는 **timeout 축뿐**이다(APScheduler 스레드 잡은 취소 대상이 아니라 cancel 축·`CancelledError` 재전파는 해당 없음).
⚠️ **`start_new_session=True`는 산문이 아니라 구조 테스트와 런타임 가드로 잠근다.** POSIX child는 이 옵션이 없으면
caller process group을 상속하므로 무검증 `killpg`가 FastAPI까지 종료할 수 있다. spawn 직전 `parentPgid`를 캡처하고
handle 획득 직후 `childPgid = os.getpgid(proc.pid)`를 확인해 **`childPgid == proc.pid ∧ childPgid != parentPgid`** 일 때만
저장한다. cleanup 때 leader가 이미 끝났어도 시그널 직전 재조회하지 않고 이 **검증·저장한 PGID**를 사용한다.
검증 실패/`ProcessLookupError`는 critical metric을 남기고 group signal을 금지하며 살아 있는 immediate child만 종료한다.
exit/kill/PID race는 멱등 처리한다.
configured budget은 child runtime 45초 + TERM grace ≤2초 + final reap ≤2초로 **49초**다. spawn/scheduling/metric
overhead까지 포함한 엄밀한 wall-clock 등식은 아니므로 `execute_with_timeout`의 end-to-end가 60초 worker-health 경계 전에
끝나는지를 통합 테스트한다. 60초 watchdog은 dead code로 단정하거나 제거하지 않고 별도 safety-net cancel 경로로 유지한다.
`start_new_session=True`로 띄우고 timeout/cancel이면 **같은 PGID에 남은** child/descendant에
`SIGTERM → 2초 grace → SIGKILL`을 보낸 뒤 `proc.wait()`에도 최종 **2초 hard deadline**을 둔다. grace 뒤에는 leader의
종료 여부와 무관하게 저장한 group에 SIGKILL을 시도하고 이미 group이 없다는 `ProcessLookupError`만 성공으로 정규화한다.
success/nonzero exit에서도 반환 전 저장 PGID 잔존을 검사해 남은 descendant가 있으면 같은 bounded cleanup을 적용한다.
deadline 초과는 critical
metric과 process inventory를 남기되 함수 반환을 무기한 막지 않는다. `create_subprocess_exec` 중 또는 proc handle 인계 전
cancellation도 spawn helper가 handle을 회수해 같은 cleanup을 수행하고, exit/kill race를 멱등 처리한 뒤
`CancelledError`를 재전파한다. 보장 범위를 PGID에서 이탈한 descendant까지 과장하지 않고, 실제 runner/Chrome smoke에서
종료 후 same-PGID descendant와 알려진 Chrome process가 0인지 별도로 확인한다. 대용량 stdout/stderr success/failure,
timeout, cancellation, handle-handoff race integration test가 먼저 green이어야 runner child D30 canary를 시작한다.
이는 새 알림 전달 정책이 아니라 D30이 의존하는 기존 process-management 결함 보정이다.

**v2.0 최소 계약.**
- 은행·source·comparison의 **모든** visible-alert 경로에 공통 `authorizeAlertSend` 경계를 둔다.
  in-process뿐 아니라 shinhan·ibk·nh·sc subprocess와 hana·woori Selenium fallback도 포함한다.
- premium 판정은 새 15분 정책이나 별도 observation DB를 만들지 않고 현재 서버의
  **`verify_premium_status` availability-first 의미를 재사용**한다. `ACTIVE`만 이번 발송을 허용하고
  `INACTIVE`·`PENDING`은 발송하지 않는다. sender가 cache-free `fetch_revenuecat_result`를 직접 소비하지 않는다 —
  그러면 현 5분 fresh/조건부 stale fallback을 모두 우회해 provider transient마다 알림을 누락시키기 때문이다.
- 캐시 수치를 정확히 고정한다. 마지막 authoritative 관측의 `cached_at`부터 **5분 미만은 fresh**, 그 뒤 provider가
  불확정일 때만 **총 age 1시간 미만**까지 기존 값을 fallback한다(`app/subscription.py:56-69,404-465`).
  이는 `5분 + 추가 1시간`도, `구독 만료 후 1시간` 보증도 아니다. provider가 `Determined(false)`를 반환하거나
  해당 프로세스의 webhook 무효화가 적용되면 ACTIVE fallback은 즉시 끝난다.
- 현재 premium cache/pending은 process-local이다(`app/subscription.py:42,93`). 따라서 long-lived main process는 위 fallback을
  쓸 수 있지만 짧은 crawler subprocess가 직접 호출하면 매 실행 첫 관측이 cold다. D30은 cache/pending과 UID
  single-flight를 main `AlertPremiumCoordinator` 한 곳에 격리하고 child가 loopback IPC로 그 owner를 소비하게 해 이
  비대칭을 닫는다. child의 direct RevenueCat/로컬 premium cache 사용은 금지한다.
  ⚠️ **이득에는 시한이 있다.** 장애 중에는 `should_cache=False`라 `cached_at`이 갱신되지 않으므로 age가 단조 증가하고,
  **마지막 authoritative 관측 + `CACHE_STALE_TTL`(1시간)** 을 넘기면 기각된 cache-free 설계와 **동일한 전면 deny로 수렴**한다.
  즉 D30은 provider transient를 흡수하지만 장기 장애를 흡수하지는 않는다.
  `deny_reason=provider_unavailable|cache_cold` count/rate를 D30-CANARY evidence에 남기되, 원인이 다른 실제 사용자 aggregate에
  근거 없는 단일 임계값을 만들지 않는다. controlled ACTIVE가 이 사유로 deny되면 gate 실패다.
- provider 조회는 setting 수가 아니라 **평가 batch의 distinct UID 수**에 비례해야 한다. candidate를 immutable DTO로
  복사하고 기존 DB read transaction/session을 닫은 뒤 UID별 1회로 병합한다. 권한 판정 뒤 FCM 직전에는 짧은 새
  session으로 setting enabled/triggered/repeat와 최신 device token을 다시 읽는다. 후보 0이면 provider 조회도 0이다.
- **sync ↔ async 경계는 D30의 세 경로로 고정**한다: async evaluator=`direct await`, main sync worker=
  `run_coroutine_threadsafe` 기반 bounded main-loop bridge, crawler child=authenticated localhost sync HTTP.
  `asyncio.run`으로 새 loop·별도 cache owner를 만들지 않는다. main loop 미등록·종료·cancel·wait timeout과 불완전 UID는
  deny/no-mutation이며 동시성·deadline은 D30 수치가 정본이다.
- subprocess의 기존 45초는 권한 조회만의 budget이 아니라 **crawl 시작부터 runner 종료까지의 절대 timeout**이다.
  DB rate commit 뒤 authorization/FCM 중 parent가 child를 kill하면 setting/history는 보존되지만 같은 rate가 재발화하지 않아
  crossing이 영구 누락될 수 있다. D30의 32초/38초 admission fence는 이 availability 비용을 숨기지 않고 auth가
  무제한으로 child를 끄는 것만 막는다. deadline 부족·provider timeout은 deny/no-mutation + metric이다.
- KRX가 source 또는 comparison의 어느 leg에라도 포함되면 premium과 함께 G1·G2·G3를 매 발송 시점에
  다시 확인한다. G1 DB 조회 실패나 G2/G3 불확정은 **KRX만 fail-closed**한다. 다만 premium 결합항은 현 서버 `verify_premium_status`의
  availability-first 판정을 쓰므로 provider 불확정 + webhook 미반영 중에는 total cache age 1시간 미만의 stale ACTIVE가
  KRX send/read도 잠시 허용할 수 있다. G1/G2/G3 자체가 stale하다는 뜻은 아니다.
- candidate를 얻은 뒤 setting의 현재 enabled/triggered/repeat 상태와 사용자 권한을 다시 확인하고 즉시 FCM RPC를
  시작한다. 공통 context에는 적어도 UID·family·setting id·premium verdict·KRX predicate·authorization attempt id를
  담고 low-level helper가 호출 scope를 대조한다. D30 process-local generation은 fetch 중 stale cache 부활을 막지만
  cross-process durable revoke generation은 아니다. 따라서 권한 확인과 RPC 시작
  사이의 revoke race 및 이미 시작됐거나 FCM이 수락한 RPC는 회수할 수 없다. 이를 "철회 관측 뒤 전 경로 신규 RPC 0"으로
  과장하지 않고, 철회가 해당 실행 경로의 판정에 반영된 뒤 시작한 RPC 0으로 보증 범위를 한정한다.
- selected send가 거부되면 FCM·`triggered`·`last_notified_at`·history·token mutation은 모두 0이다.
  허용된 send에서 `success_count>0`이면 현재 서버/iOS와 똑같이 once를
  `triggered=true, enabled=false`로 소진하고 repeat는 enabled를 유지한 채 `last_notified_at`을 갱신하며
  성공 발송 history를 남긴다. client delivery receipt를 기다리지 않는다.
- low-level FCM helper는 `rate_alert|source_rate_alert|comparison_alert` 호출에 authorization context가 없으면
  fail-closed하고 critical metric을 남긴다. `sync_alerts`는 이 visible-alert gate와 분리한다.
- rollout은 `observe → canary → enforce` 순서다. public은 모든 sender가 enforce인 상태만 허용하고,
  rollback은 gated sender 유지 또는 alert pause만 허용한다. 구 binary·고아 subprocess가 남지 않았음을
  process inventory와 실제 subprocess smoke로 확인한다.

**등록 admission.** v2.0 서버의 `POST /api/register-device`에 새 premium gate를 추가하지 않는다.
Android S1 coordinator가 무료 등록을 만들지 않고, 정보 노출은 위 send-time gate가 최종 차단한다.
구 Android는 403/503 등록 실패를 재시도하지 않으므로 전역 POST gate 추가는 별도 호환 계획 없이는 금지한다.
DELETE는 premium과 무관하게 항상 허용한다. 등록 실패는 사용자가 설정을 만들었지만 기기가 없어 알림이
오지 않는 형태로 보이므로 register 결과와 ‘enabled 설정이 있으나 device가 없는 UID’는 운영 진단 지표로 남긴다.

**SV-1 DoD.**
- `execute_with_timeout`과 hana/woori sync fallback **세 경로 모두** app-owned stdout/stderr `PIPE`가 없고,
  success/nonzero/timeout에서 inherited Docker log와 bank/pid/exit-code/elapsed parent log를 남긴다. 세 경로 각각
  `start_new_session=True`, spawn 직후 PGID snapshot 검증, parent PGID 불변, timeout 뒤 immediate child reap·
  same-PGID descendant 0·final reap 2초 상한을 integration test로 잠근다. async 경로는 cancel/handle-handoff race와
  cleanup 뒤 `CancelledError` 재전파도 추가로 검증한다.
- process-group oracle는 실제 Chrome의 자진 종료에 기대지 않는다. 부모 종료와 무관하게 남는 **synthetic same-PGID
  grandchild**로 killpg ON이면 ≤2초 내 0, killpg 변이 OFF면 같은 시점 생존을 증명한 뒤 test finalizer가 명시 정리한다.
  실제 runner/Chrome smoke는 동시 Selenium spawn을 끈 격리 환경 또는 baseline 이후 생성된 PID/PGID delta만 추적해
  cleanup 직후 ≤2초 내 잔존 0을 확인한다. 60초-age janitor가 대신 통과시키는 global process-name=0 판정은 쓰지 않는다.
- 구조 테스트는 세 spawn 모두 `start_new_session=True`임을 잠그고, runtime snapshot이
  `childPgid != proc.pid` 또는 `childPgid == parentPgid`면 group signal 0·critical metric·immediate-child-only임을 검증한다.
  configured 49초 budget과 별도로 async 경로가 60초 worker-health 경계 전에 끝나며, watchdog cancel과 lifespan-shutdown
  cancel 양쪽이 bounded cleanup되는지도 검증한다.
- ⚠️ **실패 경로 assertion**: 반환값 `False`만 검사하면 위 AttributeError 경로가 green으로 통과한다 →
  "예상 외 예외 로그 0건 **및** `record_failure` 호출 정확히 1회"를 별도 assertion으로 둔다.
- bank legacy/atomic + in-process/subprocess, legacy source rollback 경로, common source/KRX evaluator,
  comparison evaluator 모두 같은 gate를 통과한다.
- premium 상태는 반환값 기준으로 전수 테스트한다 — fresh/cached/stale `ACTIVE`=allow,
  `INACTIVE`·`PENDING`=deny. `PENDING`이 두 원인(확정 비구독+cache miss의 12초 propagation window /
  provider 불확정+cache miss)을 같은 값으로 접어도 둘 다 deny라 접근 결과는 갈리지 않는다
  (`app/subscription.py:124-127,443-465`). provider가 `Determined(false)`를 반환하면 stale ACTIVE가 즉시
  INACTIVE로 내려가고, cache age 정확히 1시간이면 삭제되는 경계를 잠근다.
- main async caller + sync worker bridge의 same-UID single-flight·thread safety, batch UID dedupe·deadline·부분 timeout을
  별도 테스트한다. D30 endpoint 인증/body cap/단일 owner/cache 공유/세 구간 runtime budget과
  45초 parent deadline 직전 rate commit 뒤 auth timeout·영구 crossing 누락 metric을 fixture로 고정한다.
  그 밖에 G1/G2/G3 각 OFF, mixed normal/KRX comparison, setting disable/delete와 발송 경쟁,
  revoke 직전/직후 경계를 테스트한다.
- deny 뒤 FCM·setting 전이·history·token mutation 0, allow+FCM success 뒤 현재 once/repeat/history 의미를
  PostgreSQL integration test와 실제 runner subprocess test로 잠근다.
- canary 관측에 provider 호출 수/unique UID ratio, auth batch latency·timeout·deny reason과
  **default executor queue delay 회귀 없음**(`app/default_executor_probe.py`)을 포함한다.
- production deploy는 관측 metric 확인 → canary → enforce → old process 0 → 실제 FCM smoke 순이며,
  하나라도 ungated이면 SV-1 미완료다.


### SV-2 — settings · history의 서버 측 KRX 필터

구현은 SV-1과 병렬 가능하다. read 경로의 premium은 FastAPI의 기존 `verify_premium_status` projection을,
KRX는 같은 G1/G2/G3 predicate를 재사용한다. D30 coordinator refactor 뒤에도 REST의 12초 PENDING propagation UX는
그 adapter에서 그대로 보존한다.
read는 `PENDING`을 503으로 표현하고 sender는 이번 발송을 deny하지만, 어느 쪽도 premium/KRX 접근을 허용하지 않는다.

**문제(실측).** premium GET gate는 있으나 KRX entitlement 필터는 mutation 4곳(`main.py:4099,4222,4478,4570`)에만 있고 **대상 GET에는 없다.**
게다가 히스토리는 SQL에서 자른다(`crud.py:3590-3593` `order_by(sent_at.desc()).limit(limit)`, offset 없음).
→ 클라이언트가 KRX row를 사후 제거하면 **밀려난 정상 USDT 기록을 되가져올 수단이 없다.**

**요구**: KRX visibility filter(G3∧G2∧G1∧premium)를 **ORDER BY / LIMIT 이전**에 적용한다.
source KRX는 정확히 `(source,asset)==("krx","usd-krw-futures")`, comparison은 어느 leg든 그 pair면 제외한다. `total_count`는 API 변경 없이
**필터·limit 후 반환 페이지 길이** 의미를 유지한다.
DB row는 보존(재승인 시 복원). 대상: source settings/logs, comparison settings/logs(한쪽이라도 KRX인 row).

**SV-2 DoD**: G1/G2/G3 각 OFF, comparison 좌/우 KRX, 최신 KRX 100개가 limit을 채운 뒤 더 오래된 USDT가
정상 반환되는 사례, premium inactive/pending, entitlement 판정 오류, 재승인 row 복원을 서버 contract test로 잠근다.
entitlement/DB 판정 오류는 partial 200이 아니라
503으로 응답하고 row를 노출하지 않는다.

**영향 범위**: 승인이 철회된 프리미엄 사용자 중 KRX 이력 보유자. 교차 사용자 노출(IDOR)은 아니며 **기능 entitlement 인가 누락**이다.

### SV-3 — 알림 전달 신뢰성 hardening (**DEFERRED · 공개 rollout 비차단**)

범위·수용 한계·재개 trigger의 **정본은 `ANDROID_NOTIFICATION_RELIABILITY_FOLLOWUP.md`**(§1/§2/§4)다.
여기에 사본을 두지 않는다 — 이중 서술은 반드시 어긋난다(이 리포의 반복 실패 사례).
v2.0 release gate로 소급하지 않는다.

### SV-4 — cross-device 계정삭제 tombstone (**DEFERRED · 공개 rollout 비차단**)

Android S10의 v2.0 보증은 **현재 설치의 crash/process-death 복구**까지다. app-data clear·재설치·다른 기기에서도
서버 삭제 완료와 Firebase Auth 삭제 미완료 사이의 상태를 복원하려면 서버가 durable deletion request/receipt를 소유해야 한다.
그 후속 계약을 SV-4로 부른다.

- 후보 protocol: client가 network 전 영속한 `deletion_request_id`와 `X-Account-Deletion-Protocol: 2`를 보내고,
  서버는 동일 request를 멱등 처리하며 `pending|finalized|not_found` typed receipt를 제공한다.
- deletion pending UID의 일반 API·WS·device registration·data recreation은 typed
  `410 account_deletion_pending`으로 막고, 허용 동작은 재인증·동일 DELETE·receipt 확인뿐이다.
- receipt 보존 기간, Firebase 삭제 완료를 누가/어떻게 확정하는지, 재설치 UID 매칭, 정리·감사 보존, 구 client 호환,
  401/404/410 의미는 별도 서버 ADR과 수정 GO 전에는 확정 계약이 아니다.
- SV-4가 배포되기 전 S10은 위 header/status/receipt를 전송·소비하지 않는다. S10의 해당 문구는 **조건부 adapter seam**이며,
  cross-device 보증이나 public rollout gate로 읽지 않는다.
- 후속 DoD: 동일 request 재시도, 서버 commit 직후 crash, app-data clear/재설치/다른 기기, token 만료·재인증,
  typed 410에서 Root보다 tombstone 우선, 삭제된 domain/device row 재생성 0을 server+Android E2E로 검증한다.

---

## 7. Android 트랙 (S0 ~ S11)

계획 전체가 승인된 뒤에도 각 슬라이스는 다음 승인 경계를 지킨다:
**범위 확정 → 별도 수정 GO → 구현·테스트 → diff/self-claim 검증·외부 검토 → 별도 commit GO → 별도 push GO → 다음 슬라이스 GO**.
서버 production deploy/config, Android release arming, Play upload, rollout 확대도 서로 다른 GO다. 사용자가 수정·구현·실행 권한을
주었고 계획도 승인됐지만, **S0 및 이후 각 슬라이스는 해당 착수 GO 전에는 시작하지 않는다.** commit/push/deploy는 별도 GO가 필요하다.

### S0 — 계약 동결 · 하네스 · CI
- topic ACK/error/lease/snapshot, free FX·Tether snapshot, Graph catalog/tab/in_progress/carry_in, 알림 3 family + FCM payload, HTTP status/Retry-After, 허용 source·asset·series ID, **KRX negative payload** fixture
- 무료 오염 fixture: 허용 series ID로 위장한 KRX provenance, cross-tab series, futures rate group, duplicate key(first-wins), `in_progress` 주입
- 테스트 하네스: coroutines-test, MockWebServer(응답 시퀀스 스크립팅), 주입 가능한 Clock
- **CI 신설**(동결 baseline 당시 Android CI 없음): **GitHub Actions hosted `ubuntu-24.04`**에서 unit contract test + lint +
  debug compile + **v2-ON minified(R8) lane**
  - production keystore/local.properties를 쓰지 않는 `ciMinified`(또는 동등 variant) + ephemeral/debug signing
  - ignored `google-services.json`은 CI secret에서 임시 공급하거나 비운영 Firebase project fixture 사용
  - production signing은 protected manual job에서만 사용하며 일반 CI에서 접근 금지
  - tracked `gradle.properties`의 host 전용 `org.gradle.java.home=/Applications/...`를 제거하고 다른 절대경로로
    대체하지 않는다. 로컬은 Android Studio/user-level Gradle 설정, hosted CI는 명시적으로 pin한 **JDK 17** toolchain을
    사용한다. clean `ubuntu-24.04` checkout에서 `./gradlew --version`과 아래 모든 lane이 project-local 경로 수정 없이 실행돼야 한다
- **D31 Macrobenchmark 하네스 신설**:
  - root/version catalog에 같은 AGP `8.13.2`의 `com.android.test`, stable Macrobenchmark `1.4.1`,
    UIAutomator `2.4.0`, ProfileInstaller `1.4.1`을 pin하고 `include(":macrobenchmark")`한다
  - `:app`의 `benchmark` build type은 `release`를 `initWith`해 R8/minify·동일 applicationId를 유지하되
    non-debuggable + ephemeral/debug signing으로 만들며 production keystore를 쓰지 않는다. benchmark manifest overlay만
    `<profileable android:shell="true" />`와 fixture admission을 열고 public release variant에는 해당 action/data가 없어야 한다.
    target app에는 `benchmarkImplementation(androidx.profileinstaller:profileinstaller:1.4.1)`을 실제 연결하고
    merged benchmark APK의 ProfileInstaller component/version을 검사한다; catalog alias만 추가한 상태는 green이 아니다
  - `:macrobenchmark`는 `targetProjectPath=":app"`, compile/target 36, min 31, AndroidJUnitRunner,
    self-instrumenting과 app의 `benchmark` matching fallback을 명시하고 `benchmark-macro-junit4`+UIAutomator로 구성한다.
    hosted Linux CI는 target/test APK assemble과 결과-parser fixture를 검증한다. 계측 smoke·숫자 gate는 hosted runner나
    emulator가 아니라, 현재 Mac에 연결한 API 34+ **고정 실기기의 로컬 manual device-only lane**에서 실행한다.
    이를 위해 별도 macOS CI/self-hosted runner를 요구하지 않는다
  - v2 기준 실기기는 **Samsung SM-F711N**으로 고정한다. S0 기준 fingerprint는
    `samsung/b2qksx/b2q:15/AP3A.240905.015.A2/F711NKSSEKZE1:user/release-keys`이며 serial은 기록하지 않는다.
    preflight는 device state `OPENED`, inner display 1080×2640 portrait, min/peak 120Hz, animation scale 3종 1.0,
    low-power OFF, 충전 전원 OFF, battery ≥30%, Android thermal status `NONE(0)`를 assert한다. 설정 변경은 원값을
    캡처한 뒤 `finally`에서 복원하고, fingerprint·display/posture 조건이 다르면 측정하지 않는다
  - 실기기 evidence에는 model·build fingerprint·API·고정 refresh mode·animation scale·thermal 상태를 기록한다.
    결과 parser는 `frameOverrunMs` P95/P99 또는 `frameCount`가 누락되면 fail한다. S5 절대 경계와 S11
    `min(8.33ms, max(0ms, B99+2.0ms))` 회귀 경계를 pass/fail/missing JSON fixture로 자체 테스트한다.
    evidence manifest schema에는 source commit, target benchmark APK, macrobenchmark test APK, fixture payload,
    checked-in parser, harness dependency lock/resolved graph, production dependency graph·R8 rules의 각 SHA와
    Benchmark/UIAutomator/ProfileInstaller version을 필수 필드로 둔다. 하나라도 없거나 형식이 틀리면 parser가 fail한다
  - S0에서는 module/variant/parser와 launcher smoke까지만 만든다. Tether 11-series fixture·selector·세 측정 여정은
    기능이 생기는 S5가 소유하고, D24의 별도 ON-artifact 승인 전에는 benchmark build가 v2 arming을 우회하지 않는다
- `targetSdk 36`, `versionName 2.0.0`; debug-off는 OFF, ON artifact 생성 권한은 D24와 별도 GO를 따른다
- **D24 gate 실설치**: Activity/Root 진입, `FirebaseMessagingService.onNewToken/onMessageReceived`, MainActivity의
  notification extras/tap을 포함한 background entry point를
  BuildConfig admission 한 곳으로 묶는다. OFF는 내부 unavailable UI만 구성하고 app-owned legacy/v2 data REST·WS와
  register-device·AlertEventBus·app tray/tap callback을 시작하지 않는다. Firebase 자체 SDK 트래픽과 이미 등록된 token에 대한
  OS-rendered background tray는 앱이 차단할 수 없으므로 data-plane network-zero assertion 범위와 분리하고 debug-off 테스트는
  등록 이력이 없는 fixture token을 쓴다
- **DoD**: fixture가 서버 route/schema와 1:1 / ON-minified lane green / `:app`+`:macrobenchmark` benchmark APK assemble,
  GitHub-hosted `ubuntu-24.04` + pinned JDK 17에서 host 전용 Gradle property 없이 wrapper/CI lane green /
  target benchmark APK의 ProfileInstaller 1.4.1 dependency·merged component 확인 /
  parser pass/fail/missing fixture와 고정 실기기 launcher smoke green / debug-off에서 unavailable UX + 앱 data-plane
  legacy·v2 REST/WS·device registration **0** / gate를 우회한 Activity 재생성·process cold-start·FCM token/message callback·
  notification intent의 app-owned side effect 0 /
  기존 37 unit + 1 instrumented 테스트 무회귀. S3/S4는 별도로 legacy **소비 코드 0**을 증명한다

> R8 lane을 S11로 미루지 않는 이유: kotlinx-serialization + R8은 keep 규칙 누락으로 실제로 깨지는 조합이다.

### S1 — 기반 계약 (identity · cache · KRX deny · push admission)
- **WireJson**(`ignoreUnknownKeys=true`, `coerceInputValues=false`, `isLenient=false`, `explicitNulls=true`, 필수 필드 기본값 금지) ⊥ **StorageJson**(qualifier 분리)
- process-scoped coroutine **`AuthTokenProvider`**를 REST·WS·Graph·free snapshot·알림·entitlements·push registration이
  공용한다. token snapshot은 `(uid, authGeneration, token)`이며 current token 취득과 forced refresh를 UID별
  single-flight로 합친다. await 전후 current UID/generation을 다시 확인해 계정 전환 뒤 결과를 폐기하고,
  동일 rejected snapshot은 refresh/replay를 한 번만 소유한다. refresh가 같은 token을 돌려주거나 이미 reject된 token이면
  다시 replay하지 않는다. 현 OkHttp interceptor의 요청별 blocking `Tasks.await(getIdToken(false))`는 제거하고 transport가
  provider snapshot을 명시적으로 받아 header를 만든다(`NetworkModule.kt:38-52`).
- 인증 transport: Bearer, **GET/HEAD만 401 강제 refresh 후 1회 replay**, mutation 자동 재전송 금지,
  status/Retry-After와 현재 서버 body를 손실 없이 보존한다(snapshot은 `error`, FastAPI 예외는 `detail`).
  서버에 없는 공통 stable `error_code`를 가정하지 않고, endpoint+status+알려진 body 조합 외 403/404는
  `unknownAuthz`/`unknownNotFound`로 fail-closed한다. **D12**: 429는 Retry-After가 없는 것이 정상이며,
  헤더가 있으면 하한으로 존중하고 없으면 jitter backoff하되 terminal로 취급하지 않는다
- backup 제외 `AccessEpochStore`: `(ownerUid,userAccessEpoch,krxCapabilityEpoch,mayContainPremiumData,
  mayContainKrxData,pendingPurge)`를 한 crash-safe control record로 관리한다. 일반 key=`(uid,userAccessEpoch,feature,tab,period)`,
  KRX key에는 `krxCapabilityEpoch`를 추가하고 모든 in-flight가 동일 fence를 캡처한다.
  - same-UID clean cold start는 epoch namespace만 이어받되 `.forcePremium` fresh ACTIVE 전 protected read/render는 0.
    UID 불일치·불완전 teardown journal은 새 epoch를 먼저 persist하고 old namespace를 purge한다
  - protected/KRX 첫 write 전에 해당 `mayContain*Data=true`를 먼저 persist. teardown은
    `새 UUID + old namespace pendingPurge persist → runtime cancel → old namespace purge → journal clear` 순이며 각 crash 경계를 재실행한다
- **`PremiumAccessCoordinator`가 D23 sealed state를 소유**하고 `/api/entitlements`의 200 stable/pending,
  protected REST의 403/503 재확인 신호, WS `premium_required`, 로컬 purchase/restore 신호를 한 reducer로 직렬화한다.
  일반 REST 403은 `.forcePremium`을 촉발할 뿐 단독으로 free 전이를 확정하지 않는다. fresh stable false 또는
  WS `premium_required`의 확정 거부만 user/KRX epoch rotate·persist → topic/bootstrap/graph cancel·purge →
  push DELETE → Root free로 전이시키며 로컬 `true`가 재개방하지 못한다
  - refresh intent를 분리한다: `.ifStale`(10초 client debounce), `.forceEntitlements`(debounce만 우회),
    `.forcePremium`(`fresh_premium=true`, 서버 premium cache도 우회). cold start 최초 grant,
    purchase/restore, protected REST 403, WS `premium_required`, premium push tap은 `.forcePremium`; 일반 foreground는 `.ifStale`,
    KRX-only rejection은 `.forceEntitlements`를 쓴다. pending retry는 원 요청 mode를 보존하며 premium 판정 pending을
    ordinary mode로 약화하지 않는다
- UID namespace, migration journal, `UserScopePurger`/`CapabilityScopePurger` 인터페이스와 **Android 백업 제외 규칙**을
  먼저 만든다. backup 가능한 순수 사용자 의도는 전용 DataStore 파일에만 두고 token/capability/epoch/cache/journal/tap과
  물리적으로 분리한다(D27). 이 단계에서는 아직 소비자가 살아 있는 legacy rate/graph store를 삭제하지 않는다(§9의 슬라이스별 소유권)
- **purge 2종 분리**: ① `UserScopePurger` — logout / 계정 전환 / 계정삭제 시 전체 **domain/user data**(세션 메모리 그래프 관측 기록기·복구 요청 포함, 동결 후 7번). 단 active
  `DeletionPending`/deletion request+phase와 완료되지 않은 access/push/purge journal은 purge 대상이 아니라 작업을 지키는 control-plane이며,
  각 전용 finalizer만 마지막에 clear한다. ② `CapabilityScopePurger` — KRX 권한 철회 시 **KRX server-derived 범위만**(시세 행·그래프 series·live 버킷·settings/history 응답 cache·pending tap). source raw preference는 보존하며 철회가 사용자 전체 데이터를 지우지 않는다
- Entitlements repository — `krx_visible` 단일 신호. 정책명은 **첫 확인 전 deny-by-default + 동일 UID/`userAccessEpoch`/`krxCapabilityEpoch` 안에서 시간 만료 없는 last-known grant**다:
  새 UID/프로세스의 runtime grant는 false, stable true는 visible, 같은 두 epoch의 판정 불가는 메모리의 마지막 stable 값을 **시간 만료 없이 유지**하고, 200 응답에 실린 `krx_visible`은 pending이어도 즉시 적용,
  stable false/WS `krx_entitlement_required`/UID 변경은 즉시 false + KRX capability epoch rotate + capability purge.
  premium/UID 변경은 user epoch도 rotate한다. grant는 디스크에 영속하지 않는다
  - KRX false teardown은 **edge-trigger + idempotent**다. 현재 grant가 열려 있거나 `mayContainKrxData=true`이고 unfinished journal이
    없을 때만 새 capability epoch를 1회 rotate해 `pendingPurge`를 persist한다. 기존 `pendingPurge`가 있으면 같은 old namespace purge를
    재개할 뿐 추가 rotate하지 않고, already-hidden clean false→false는 epoch/journal/cache write 없이 no-op이다
  - `.ifStale` 10초는 **권한 TTL이 아니라 stable-success 중복조회 억제창**이다(만료값이 아님 — iOS `freshnessTTLSeconds` 동일 의미)
- **KRX deny-by-default**: registry capability, DTO sanitizer, pre-cache 필터, UI·접근성 제외, late-response 필터
- **push coordinator 실설치(D21)**: `release gate ON ∧ auth ∧ 서버 확정 premium runtime ∧ 현재 OS 알림 capability`를
  process/restore마다 재구성한다. 별도 persisted/backup `shouldRegister`를 권위로 쓰지 않는다.
  auth 복원·foreground·permission/channel·FCM token·premium 상태/`userAccessEpoch` 변화에 재평가하고,
  logout·UID 변경·무료 전환·서버 premium 거부에서는 DELETE를 시도한 뒤 local token-registration 상태와 app-owned
  pending tap/event를 지운다. DELETE는 premium과 무관하게 항상 허용한다.
  - token rotation과 unregister/register는 단일 mutex로 직렬화한다. old token DELETE를 best-effort로 완료한 뒤
    current UID만 latest token을 등록하며, late callback은 captured UID/generation이 다르면 폐기한다.
    process death 뒤에도 pending teardown을 새 POST보다 먼저 재개한다.
  - 등록의 503/Retry-After는 hard floor를 지켜 재시도하고 403은 fresh premium 판정 뒤에만 terminal 처리한다.
    현재 `PushNotificationManager.kt:71-77`의 로그-only 실패를 보강하되 delivery profile·registration id·recipient
    binding을 새 wire 계약으로 만들지 않는다.
  - foreground `onMessageReceived`와 tap callback은 현재 auth/premium을 확인하고, KRX family면 현재
    `krx_visible`도 확인한 뒤 local state event/refetch만 수행한다. 실패하면 side effect 없이 폐기한다.
    background/killed visible push는 OS renderer가 먼저 표시하므로 이 검사는 사전 표시 보증이 아니며 SV-1이 정본 방어선이다.
  - logout/free/KRX revoke와 v1→v2 update에서 `cancelAll()`은 이미 보이는 app tray를 정리하는 best-effort hygiene다.
    broker queue 회수나 D29식 28일 보증으로 해석하지 않는다.

- cold-start 요청 예산: nginx `3r/s burst=20` + `limit_conn 20` / `/ws 10r/s burst=20`(`nginx/conf.d/default.conf:19,25,88-90,116-119`) → 인증·entitlement → 현재 탭 → 나머지 탭 → 알림 → 히스토리 순 지연, single-flight, 비활성 탭 lazy
- **DoD**: 이전 UID/`userAccessEpoch`/`krxCapabilityEpoch` 응답 반영 불가 / 서버 ACTIVE 전
  WS·bootstrap·Graph·FCM 등록·mutation 0 / 200 pending을 premium 전체 INACTIVE로 오인하지 않되 응답의 `krx_visible=false`는 즉시 적용 /
  동일 UID의 기존 grant에서 HTTP 503·최종 401·code 없는/미지 403·decode/protocol 오류가 epoch rotate·hide/purge·push DELETE를 만들지 않음 /
  active/no-grant 양쪽의 pending·실패가 동일 `RecheckSchedule` owner로 합쳐지고 Retry-After hard floor 단축 0 /
  stable/reset/logout/UID 전환/typed reject가 예약을 취소하며 late generation 결과 반영 0 /
  반복 pending `krx_visible=false`에서 KRX epoch rotate·journal write·purge는 최초 edge에만 1회 /
  REST+WS 동시 401이 같은 rejected token에서 forced refresh 1회로 합쳐지고 replay도 각 GET/HEAD 1회뿐 / mutation replay 0 /
  token await 중 A→B 계정 전환·동일 token 재발급·late refresh에서 A header/응답 반영 0 /
  protected REST 403 뒤 fresh entitlements reconcile / fresh stable false·WS `premium_required`만 공통 free 전이 /
  KRX cold-start·UID 변경 hidden /
  명시적 거부(fresh false·typed reject·UID 변경·logout) 시 process-death 첫 protected use 전 journal materialize+purge /
  marker persist·epoch rotate·namespace purge 각 crash 경계 재실행 /
  token rotation·unregister 응답 유실·A logout 직후 B login에서도 current UID token만 등록 /
  foreground alert/tap의 current premium·KRX 실패 시 app-owned local update/refetch 0 /
  background notification은 SV-1 없이는 client가 사전 차단하지 못한다는 integration fixture /
  API 30 full backup과 API 36 cloud+D2D restore에서 allowlisted DataStore만 복원 /
  legacy rate·graph store는 이 단계에서 삭제되지 않음


### S1.5 — 공통 표시 기반
- Graph DTO/domain, prepared builder, renderMode(live · snapshot), `RateSourceRegistry`(wire source/asset · 표시명 · icon/color/precision · 사용 가능 표면 · tab/alert family · KRX 필요 여부)
- **`SourcePreferenceManager` 등가**(순서·표시 영속, 원본 보존 + effective 투영 D17·D18) — **무료 테더 탭(S2)이 소스 순서를 필요로 하므로 S5가 아니라 여기**
- `BankPreferenceManager` v2: known bank code만 dedup, 상대 순서·visibility 보존, Citi는 신규 표시 기본에서 제외.
  KRX는 raw bank preference에 섞지 않고 capability-aware effective row로 주입한다. v1 `fxi_bank_preferences`는 UID
  소유권/backup-restore provenance를 증명할 수 없으므로 **변환 이관하지 않고 v2 UID 기본값으로 재설정**한 뒤 legacy store를 삭제한다
- **공통 rate-row presenter**: 선형 폭(ScalePolicy displayRange 도메인) · 0.5s linear · pulse 제거 · 첫 composition·Reduce Motion snap · ▲▼ cue(0.01, latest-wins, Reduce Motion에서도 기준값 갱신)
- **DoD**: free/premium domain fixture가 같은 presenter contract test를 통과 / presenter가 runtime/network에 의존하지 않음 /
  기존 Bank enum에 거래소·KRX를 편입하지 않음 / upgrade·backup restore·계정 교체 모두 v1 bank preference가 다른 UID로
  귀속되지 않고 v2 default로 수렴. 실제 양 경로 wiring은 S2·S3/S5 DoD에서 확인

> 무료 탭(S2)도 시세 막대를 쓰므로 presenter를 S5로 미루면 재작업이 생긴다.

### S2 — Root 접근 상태 연결 + 무료 실데이터
- `SignedOut → Login only` / `SignedIn non-ACTIVE → 실제 FreeSnapshot` / `PremiumConfirmed → premium destination`.
  S10의 `DeletionPending`은 이 일반 non-ACTIVE 분기보다 우선하는 별도 차단 destination이다
  - 현재 `RootScreen.kt:133-144`는 로그아웃 상태에서도 예시 화면을 연다 → 제거
- S1의 `PremiumAccessCoordinator`를 Root에 바인딩한다. S2 종료 시 Topic은 아직 S3이므로 premium destination은
  **내부 전용 unavailable shell**이고 legacy로 fallback하지 않는다. S2와 S3 사이 artifact는 배포 불가이며 S3에서 이 임시 상태를 제거한다
- `GET /api/v2/free/snapshot?tab=&period=`, envelope의 tab/period echo **fail-closed** 검증
- **cache 전 무료 전용 sanitizer**(premium/KRX registry와 독립): rate `(group,source,asset)` allowlist, futures group 전량 폐기,
  duplicate first-wins; graph `(tab,seriesId)` allowlist + point/provenance contamination 검사. 정화된 값만 UID-scoped free cache에 저장
- snapshot adapter는 `in_progress=null`, live bridge/WS/resync 의존 0
- 기준시각 두 값: **as_of basis slot = HH:30**(서버 canonical 기준), **`refresh_not_before` = HH:31:00**(다음 조회 하한)
- 단일 scheduler: `refresh_not_before`(HH:31:00) + per-install/tab 결정적 jitter 10~30초 one-shot, stale backoff 20/40/80 → 300초, **선택 data 탭만 활성**(뉴스=0), 다른 기간 preload는 실제 실패 시만 300초 cooldown
  - fetch 완료 owner만 `nextEligibleAt` 재계산; 탭 활성화/foreground는 저장 deadline 재평가만 하고 backoff를 reset하지 않음
  - stale 응답은 20/40/80→300, 무캐시·fetch 실패는 즉시 300, cancellation은 실패/cooldown 아님
  - fetch scheduler와 독립된 24h expiry wake; 동일 `as_of`라도 새 `refresh_not_before` 반영
- 신선도: <2h 정상 / 2~24h 지연 배지 / 24h+ unavailable. 마지막 유효값 유지, 가짜 fallback 금지
- 5탭 공통 last-tab UID 저장(최초 USD), data 탭별 VM/cache 생존, 4기간 전환·순차 preload, zoom/fullscreen.
  무료 visible-series preference는 premium과 별도 UID namespace, all-off 허용, tether `dxy↔dxy_futures`만 상호배타
- 미캐시 기간 전환은 chart loading/empty이며 이전 기간 chart를 재사용하지 않는다. header/rates만 자기 `as_of`와 함께 sticky 허용
- 무료 알림은 D26의 in-memory preview + 별도 SubscribeCTA만(구조적 no-persist, 구매 후 draft transfer/auto-save 없음)
- **Sample\* 파일 삭제** — 소비자 0이 된 뒤 같은 슬라이스에서 제거
- **DoD**: 탭 순서 **뉴스→테더→달러→엔화→유로**, 최초 선택 달러, UID별 마지막 탭 복원 / 로그아웃 상태 API 호출 0 /
  무료 상태 WS·Graph V2·topic bootstrap·알림 mutation·FCM 등록 0 /
  모든 오염 fixture가 cache 이전 제거 / 24h 독립 만료 / 탭 왕복으로 backoff 우회 불가 / KRX 문자열·데이터·접근성 semantics 0 /
  fake generator 소비자 0. Sample 삭제와 Root cutover는 같은 변경 세트지만 S3 전에는 public artifact를 만들지 않는다

### S3 — Topic 런타임 + FX 수직 슬라이스
- `TopicSessionCoordinator`: desired = `usdt:krw`, `fx:usd-krw`, `fx:jpy-krw`, `fx:eur-krw`, `dxy:spot`.
  KRX hook은 정의하되 desired/bootstrap 활성화는 S6이 소유한다
- subscribe에 `request_id` + Firebase `id_token`, ack 상관, `active_subscriptions`를 최종 상태로 수렴(누적 금지)
- 매 시도는 새 `request_id`를 쓴다. **실제 송신에 도달한 각 시도**가 송신 직전의 단조 시각을 기준으로
  **20초 ACK deadline과 45초 delivery deadline**을 한 arbiter에 등록한다. ACK는 그 시도의 delivery deadline을
  연장하지 않는다(= "절대"의 뜻). 재시도는 이전 arbiter를 폐기하고 **새 송신 시각에 두 deadline을 다시 앵커**한다.
  재시도 가능한 송신 경로 실패·ACK 무응답·`temporarily_unavailable`는 최초 시도를 포함한 **총 3회 예산**을 공유하며,
  송신 전 준비 단계(토큰 취득 등)의 재시도 가능한 실패도 같은 예산을 쓴다. 최초 송신부터 재시도 전체를 묶는
  **별도의 45초 delivery deadline은 두지 않는다**(3회 횟수 상한과 기존 연결·권한·의도·lease 만료 종료 조건은 그대로).
  command 완료는 ACK, topic delivery 완료는 topic별 receive-generation 증가로 증명한다
- 재연결 backoff는 **D3**: linear `2s × attempt ±20%`, 최대 5회, **30초 안정 후에만** attempt 초기화(수신 프레임으로 초기화 금지 — legacy `rates` 프레임 포함)
- 재연결 후 desired set 일괄 재구독(jitter 0~2초). 앱 foreground/background, network change, premium access state/`userAccessEpoch`을
  coordinator 한 곳이 소유하고 연결 종료 시 confirmed/lease/zero-duration 기록은 폐기하되 desired만 보존한다
- lease: `max(0, shortestDuration−180−U(0,60))`에 갱신, `duration 0` = 즉시 재인증(연결·lease_id당 1회).
  `lease_id`/duration 한쪽 누락·음수·overflow는 control frame fail-closed(store 무변). 같은 `lease_id` ACK는 최초
  absolute hard-expiry를 연장하지 않으며 foreground 복귀 시 ping/resubscribe 전에 expiry를 강제한다
- **`dxy:spot` DTO/decoder/store 신규**(작성 시점에는 topic 계층에 없었음 — ✅ 이후 세 슬라이스에 나눠 land: DTO와 decoder 분기는 S3a `32f5994` (`DxySpotEntry`·`DxyTopicMessage`·`DxyTopicData`, `TopicFrameDecoder`의 `dxy:spot` 분기), domain 값과 merge는 S3b `32446fc`(`TopicDollarIndex`와 그 `merge` overload), coordinator 수신은 S3k-1 `4f50b64`(`receiveIndex`)), `type:"update"` 제거(D9), JSON pong(D10), malformed 프레임 격리(D11)
- 오류 행렬:
  - whole request `invalid_token` = token 강제 refresh 후 공유 예산 안 1회, `temporarily_unavailable` = body `retry_after_seconds` 하한,
    `invalid_request`/`request_too_large` = terminal programming error, 미지 request/error = 상태 무변·격리
  - per-topic `topics_disabled`/`unknown_topic`/`topic_unavailable` = 해당 topic terminal/degraded,
    `premium_required` = S1 공통 Rejected 전이, `krx_entitlement_required` = S6 hide+force refresh
  - topic body `retry_after_seconds`와 HTTP `Retry-After`는 서로 다른 typed source로 fixture를 둔다
- REST bootstrap `/api/v2/topics/snapshot?topic=` — UID/`userAccessEpoch` fence + 시도당 10초 monotonic 예산, typed 오류 행렬 적용
  - ⚠️ **REST는 WS와 일부 오류 문자열을 공유하지만, envelope·적용 범위·인증/권한 표현이 달라 WS 오류 행렬을
    그대로 적용할 수 없다.** 위 행렬을 복사하지 말고 이 endpoint의 실제 응답으로 만든다. 아래는 `exchange-rate`
    `52650f6`의 `get_v2_topic_snapshot`·`require_premium`·`verify_firebase_token`을 읽어 확인한 것이며, 줄 번호 대신
    심볼로 적는다(그 파일은 자주 움직인다).
    - `topic`은 **스칼라 필수 1개**(반복 시 마지막 값, 누락 422) → desired 5개는 **논리 호출 5회**. 401 replay가
      하나를 둘로 만들 수 있어 HTTP 송신 수와 같지 않다
    - `topics_disabled`: per-topic이 아니라 **인증 전 endpoint 전체** 404(dormant 분기가 `verify_firebase_token`
      호출보다 앞선다 — 서버 주석이 '첫 실행문 계약'이라고 명시)
    - `temporarily_unavailable`: 503, body `{"error":"temporarily_unavailable"}` — **`Retry-After`도
      `retry_after_seconds`도 없다**(서버 주석:
      분 단위 failover에 5초 재시도는 storm). 위 행렬의 원천 분리 규칙은 그대로 적용된다
    - `topic_unavailable`: **404**, body `{"error","topic"}`. 지원 topic이지만 현재 payload가 없을 때
      (예: `FX_TOPIC_ENABLED` off) — `unknown_topic`(body `error`+`detail`+`supported_topics`)과 **모양으로 구분된다**.
      S6는 prior-grant KRX bootstrap의 404를 body의 `error` 값으로 판별한다(`unknown_topic`=revoke /
      `topic_unavailable`=degraded). 두 응답 모두 L-1 fixture 대상
    - premium: INACTIVE 403 `detail` / PENDING **503 + `Retry-After: 5`**. **인증 인프라 실패도 503 + `detail`**
      (Firebase 미초기화·인증서 조회 실패) → **503은 세 종류**다
    - REST에는 `invalid_token`·`premium_required`·`krx_entitlement_required`가 **없다**. KRX 미자격은 404
      `unknown_topic` + `supported_topics`에서 제거(존재 은닉)
- merge: `(source, asset)` 키, `mergeAt = rate_changed_at ?? timestamp`, **strictly-newer only**, 동일 시각은 기존 유지, 그룹 누락 ≠ 삭제
  — 표시 저장소 규칙이다. 그래프 관측은 이 병합 전에, 기존 권한·세션 검사와 `RateSanity`를 통과한 같은 entry 집합에서 원 시각
  (`timestamp`·`rate_changed_at`·DXY 공급 source)과 전달 topic·경로를 보존해 따로 인계한다. 관측 채택 결과는 사용 가능한 가격
  판정·배달·watchdog을 바꾸지 않는다(동결 후 7번)
- **그래프 관측 연결**(동결 후 7번): WS·REST bootstrap의 일반 시세와 DXY 네 경로에서, 표시 병합이 버리는 재관측·역순 입력을
  포함한 관측 후보를 인계한다. 어댑터는 전달 topic이 아니라 source·asset 의미로 고르며(Tether payload의 은행·인베스팅도 단일
  시각), 원래 topic은 출처로 보존해 topic·source·asset·catalog 매핑을 S4 채택 전에 검사한다. 관측과 연속성 사건은 coordinator
  직렬 scope가 확정한 상태 전이 순서로 동기·비차단 인계하고 ACK·lease 만료·identity retirement·shutdown 경로를 포함한다 —
  입력 Channel 순서와의 일치는 주장하지 않는다. 연속성 사건은 초기·접근 재개, topic별 전달 중단·재개, 인증 실패·회복,
  ACK active set 변화, 연결 생성 실패, 인계 유실, 명시적 취소·접근 봉인·topic purge·기록기 종료를 구분하고 자료 scope·권위
  세대와 epoch 발생 시각을 싣는다(deadline용 monotonic 시각과 구분하며 가격 봉 배정에 쓰지 않는다). 연결 open은 전달 재개의
  증거가 아니고 WS 종료는 REST bootstrap 경로의 종료가 아니다. ACK·인증 회복만으로 과거 공백을 해제하지 않는다. 배선하는
  sink는 즉시 반환·비예외이며 enqueue 실패·닫힘·포화를 조용히 무시하지 않고, 인계 유실은 기록기에 연속성 상실·복구 필요로
  전달한다. 권위 토큰은 지연 작업 차단에만 쓰고, 보존·폐기는 entitlements가 관측과 독립적으로 발행하는 접근 제어 입력을
  따른다(§7 S4 보존·폐기). last-known 복원 seed는 관측이 아니다. KRX 분기는 S6 전까지 소비 0. 소비자(기록기)와 어댑터는 S4
  소유이며 연결 추가(dormant)와 runtime 배선을 분리한다. 관측 연결 구현 전 L-4d 권위 토큰·거부 결속,
  L-4c ACK 적용 경계, L-4f bootstrap 발급 제어의 최종 구현을 대조한다. L-4e grant 전달·재개와 live 접근권한
  철회 검증은 runtime 배선 전에 대조한다
- **topic last-known disk 계약**: 정화·domain 검증을 통과한 FX/Tether/DXY를 서로 다른
  `(uid,userAccessEpoch,topic-kind)` namespace에 저장하고, fresh server premium 승인 뒤 WS/bootstrap보다 먼저 복원한다.
  restored seed는 `refreshingCached/offline` 표시용일 뿐 ACK/delivery 완료·freshness·silence deadline을 충족시키지 않는다.
  현재 session의 strictly-newer live/bootstrap 값이 seed를 이기며, 변경 시 최대 5초 write throttle로 저장한다.
  S6 전에는 KRX/futures를 tether cache 경계에서 폐기하고, S6 이후 KRX를 저장한다면
  `(uid,userAccessEpoch,krxCapabilityEpoch)` 별도 namespace만 사용한다. 일반 tether/FX/DXY cache에 섞지 않는다.
- 최초 delivery watchdog과 지속 staleness를 분리한다. 지속 정책은 D14(첫 유효 Tether delivery 뒤에만 45초
  silence owner arm, KRX-only/미수신은 미arm, FX 시간 만료 없음), legacy `rates` 무시(I1)
  - **iOS 이탈(의도적, §2.2 기록).** iOS는 빈 tether payload에도 `markTetherFresh()`를 부른다
    (iOS `a36682f`의 `ExchangeRateViewModel.applyTetherSnapshot`, '수신 자체가 생존 증거'). Android는 정화 후
    **사용 가능한 가격이 하나도 없으면 배달로 세지 않는다**(`TopicSessionCoordinator.receive`의 `quotes.isEmpty()`
    되돌림) — D8로 `usd_krw_futures`를 DTO에서 뺀 뒤 빈 스냅샷과 KRX-only payload가 구분 불가라, iOS를
    그대로 옮기면 KRX-only 200이 tether 창을 무장시킨다. D14와 §7 S3 DoD(KRX-only가 Tether delivery·
    freshness를 충족하지 않음)를 따르는 강화이며 새 정책이 아니다. '사용 가능한 가격 존재'와 '캐시 변경
    발생'은 다른 검사다 — merge 결과가 불변이어도 유효한 가격이 있었으면 배달이다
- **USD/JPY/EUR 현재가를 topic으로 전환하고 `/api/rates`·legacy WS 소비 제거**. S2의 premium unavailable shell을
  실제 premium Root destination으로 교체한다
- rate consumer cutover와 같은 변경 세트에서 `fxi_cache`의 legacy `rates`·`rates_timestamp`를 제거하고
  legacy rate DTO/repository/VM을 삭제한다. 아직 S7 알림 편집기가 소비하는 `last_bank_*`는 이 단계에서 건드리지 않는다.
  S1에서 만든 journal은 성공한 cutover 뒤에만 rate target을 committed로 표시한다
- **DoD**: premium 런타임에서 legacy rate API 호출 0 / REST↔WS 경합에서 오래된 값이 최신을 덮지 못함 /
  3회 공유 budget·시도별 absolute 45초(ACK 비연장)·lease-id hard-expiry·30초 안정 후 reconnect reset / server premium rejection 즉시 무료 전환 /
  최초 delivery watchdog과 persistent-silence owner의 중복 resubscribe 0 / KRX-only가 Tether delivery·freshness를 충족하지 않음 /
  offline cold start에서 정화된 last-known 복원→`refreshingCached/offline` 표시, 복원 seed가 delivery/freshness로 오인되지 않음 /
  live-wins·5초 write throttle·UID/epoch 전환 purge / Tether payload와 disk cache의 KRX 오염 폐기(D8) /
  표시 병합이 버린 재관측·역순 입력이 관측 인계에 원 시각째 남음 / 무효 가격·검사 실패·KRX 입력의 관측 0 / 권위 종료 뒤 옛
  권위의 지연 관측·사건 0 / sink 실패·포화에서도 merge·배달·deadline 계속 / 관측 연결 전후 배달·watchdog 결과 동일(동결 후 7번)

### S4 — Graph V2 (FX)
- `catalog` + `tab` 전환. catalog `version`은 **String**, tab별 `periods`와 동적 series ID만 소비하고 top-level
  `supported_periods`는 사용하지 않는다. `metadata.domain_*` X축, carry_in, insufficient_history(200 + 빈/부분 data)
- 일반 graph disk key=`(uid,userAccessEpoch,tab,period)`. same UID의 `.forcePremium` fresh ACTIVE 뒤에만 seed를
  즉시 표시할 수 있고, 그 전 Resolving/Pending에서는 읽기·표시 0이다. seed는 server-confirmed freshness가 아니며 online 200만
  freshness를 기록한다. 판정 불가 fetch는 last-good를 시간 만료 없이 유지한다. KRX series는 일반 envelope에서 분리해
  `(uid,userAccessEpoch,krxCapabilityEpoch,tab,period)` cache에만 저장하고 현재 capability 승인 뒤 join한다. 따라서 KRX revoke가
  비-KRX series를 지우지 않으면서도 old KRX namespace를 재개방하지 않는다. `visibleSeriesIds`와 `initializedSeries`를 함께
  UID-scope 영속해 신규 default와 사용자 all-off를 구분한다. 디스크에는 검증·권한 필터·namespace 분리를 적용한 서버 유래 자료만
  저장하고, 앱 관측·재구성 표시 범위·복구 상태는 세션 메모리에 둔다. 캐시 표시를 복구 성공으로 세지 않는다(동결 후 7번)
- topic `(source,asset)`→catalog series bridge(DXY 별도)는 S3 관측 인계를 입력으로 받고, 원래 topic·source·asset·catalog 매핑을
  채택 전에 검사하며 non-finite/비양수/오염 sample을 reducer 전에 제거한다. 표시 저장소(`TopicRates`) snapshot을 관측으로
  재투입하지 않는다(동결 후 7번)
- **1d live-tail 계약**(동결 후 7번에서 재작성): 서버 closed bucket + optional `in_progress` seed, 클라는 진행 중 봉을 만든다.
  600초 epoch 정렬(서버 코드 `graph_v2_intraday._bucket_align`과 동일 — 계약 문서가 아니라 코드 정의)
  - **봉 배정은 관측의 서버 시각으로 한다.** 앱 수신 시각·현재 시각으로 배정하지 않고 관측 시각을 now로 보정하지 않는다.
    어댑터는 source·asset 의미로 고른다: 두 시각 소스(`usdt-krw` 거래소·`usd-krw-futures`)는 의미가 확인된 일반 경로에서
    `(가격, 변경 시각)`·`(가격, 재관측 시각)`을 각자 봉 후보로, 은행·인베스팅은 어느 topic으로 왔든 `timestamp` 하나로
    (snapshot 재전달·다른 source 갱신은 새 관측이 아님), DXY는 `timestamp`+공급 source. 소스별 시각 의미·legacy/fallback 채택은
    해당 어댑터 구현 전 fixture로 확정한다
  - **서버 범위와 검증된 앱 관측 범위를 `(자료 scope, series, 봉 시작)` 단위로 분리 보관**하고, 합친 결과는 표시용으로만 계산해
    다시 입력으로 누적하지 않는다. 저장 시와 렌더 시 seed 병합 규칙은 하나다. 현재 봉 = 채택 seed
    (`bucket_start == floor(now/600)*600`일 때만 fold) ∪ 검증 관측, high/low는 합집합 max/min. 같은 봉에서 채택한 서버 seed들의
    범위는 합집합으로 보존하고, 새 seed나 더 늦은 `sampled_at`만으로 기존 서버 극값이나 정상 앱 관측을 삭제하지 않는다. REST가
    제공하기 전 지난 앱 봉은 보존한 서버 범위와 검증 관측을 유지하고, 서버로 대체된 닫힌 봉에는 이후 도착한 앱 관측을
    재합성하지 않는다. 다른 봉의 응답 누락은 해당 봉의 대체나 복구 완료가 아니다
  - **close**: 현재 봉 close는 해당 봉 검증 관측 중 관측 시각이 가장 늦은 가격, 해당 관측이 없으면 채택 seed close. 동시각 가격
    충돌 규칙은 어댑터별로 고정한다. 이 우선순위는 seed close보다 실제로 최신임을 보증하지 않으며, `sampled_at`을 close 가격
    시각·집계 완료 경계·seed 간 포함 관계의 증거로 쓰지 않는다
  - **타이머**(10초 rollover tick·30초 freshness tick)는 시간축 이동·봉 경계 감지·재조회 예약·선 사용 판정만 하고, 마지막 가격을
    관측으로 넣거나 빈 봉 고저를 만들지 않는다. source freshness는 기본 600초/hana 1200초이고 선 사용 판단일 뿐 현재 봉 관측
    증거가 아니며, 만료만으로 채택한 극값을 지우지 않는다. bucket 600초와 freshness를 같은 상수로 취급하지 않음
  - 기록기는 화면·탭이 아니라 사용자 세션 자료 scope 수명이다. 공통 자료는 scope·series·period·봉 간격 단위로 적용 버전을
    관리해 늦은 응답이 새 버전을 되돌리지 않고, 요청 single-flight는 같은 scope·권한·tab·period 단위이며 서로 다른 탭 payload
    전체를 같은 요청으로 취급하지 않는다. 공유 자료는 각 소비자의 현재 세션·topic·권한 검사를 통과한 뒤에만 쓰며, 한 경로의
    허용으로 다른 경로의 취소를 우회하지 않는다. active period가 1d가 아니어도 600초 live bucket은 누적하고 24시간 창 + 1시간
    여유(총 25시간) 밖은 prune; 렌더는 1d에서만. 장기 응답의 마지막 봉 시각으로 1일 누적을 정리하지 않는다. age-prune 외에
    관측 근거·중복 식별·미복구 봉의 크기 한도를 두고, 한도 초과·시각 이상으로 근거를 버릴 때 복구 상태도 갱신한다. 기록기는
    화면 생성·catalog 도착과 무관하게 허용된 입력을 받고, 아직 매핑되지 않은 입력의 보관 한도와 복구 인계를 정하며, 표시
    저장소 snapshot 재투입으로 대체하지 않는다
  - 350ms trailing publish(리셋 debounce 아님), off-screen은 누적하되 publish task 0, 재노출 즉시 flush
  - **수신 공백이 걸친 모든 봉에 복구 필요 사유를 남긴다.** 현재 seed 적용 여부, 과거 수신 공백 이력, 닫힌 봉 복구 요구를 따로
    관리하고 seed 하나로 공백 전체가 수집됐다고 판정하지 않는다. 복구 요구는 캐시 hit·쿨다운·진행 중·실패·취소로 지워지지
    않으며, 요청 자료 scope·복구 세대·series·봉 구간을 검증해 실제 제공된 봉만 해제한다(최대 `lastClosedTs` 이하 일괄 해제 금지,
    응답 도중 경계가 바뀌어 온 옛 봉 seed를 현재 봉으로 옮기지 않음). 요청 중 새 공백은 새 복구 세대다. 그래프 REST의 실패·부분
    응답·취소는 S4 복구 담당자가 처리한다
  - **보존·폐기**: 기록기는 관측 유무와 독립적으로 entitlements에서 현재 신원·자료 namespace·접근 허용 상태·명시적 종료 사유를
    받는다. 이 제어 입력과 관측·응답 적용은 순서와 세대가 검증되는 한 소비 경계에서 처리한다.
    관측 envelope 자체가 현재 접근을 개방하지 않는다. 자료 namespace와 현재 접근 허용·명시적 철회·기록기 수명을 구분하며
    namespace 불변이나 권위 토큰 변경만으로 보존·폐기를 결정하지 않는다. 확인된 일시 수신 중단은 검증 자료와 복구 사유를
    보존한다. 명시적 premium 취소·사용자 세션 종료는 해당 범위를 재사용 불가로 하고 KRX 취소는 capability 범위만 대상으로 한다
    (D23: 판정 불가·pending envelope은 기존 grant 유지, stable false·typed `premium_required`·SignedOut·UID 교체는 premium 범위 취소,
    `krx_visible=false`·`krx_entitlement_required`는 KRX만, Resolving·Pending(noGrant)·DeletionPending은 protected read/render·요청 0).
    명시적 철회의 epoch 저장이 실패하거나 결과가 미확정이면 일시 공백으로 취급하지 않으며 해당 범위의 read/render·관측 채택·
    신규 요청·재시도·지연 완료 적용을 봉인한다. 영속 정리는 I4의 persist→cancel/purge→journal 완료 순서를 유지하고, 인계 완료를
    실제 purge 완료로 보고하지 않는다. 이 봉인·재승인·정리 재개 계약은 S1 잔여에서 확정·검증하며 그래프 runtime 배선의 선행조건이다
  - **복귀 직후** 출처 없는 누적·tip을 무효화하고 첫 발행 전에 한 직렬 실행자에서 재구성한다. REST 대기 중에도 수집을 계속한다
  - rollover 10초 tick, foreground 복귀(≥60초, 30초 cooldown)·WS 재연결 시 resync(첫 연결 제외). 실행·복귀·재연결로 생긴 복구
    요구는 ≥60초 조건·TTL·첫 연결 제외로 소멸하지 않고, 첫 연결 중복 요청을 생략할 때도 초기 요청 담당자가 실패·부분 응답
    이후의 미충족 요구를 이어받는다. 재시도 간격·상한·봉 경계 재조회의 단일 담당은 구현 전 고정한다. 10분 경계 재조회는 활성
    1d 전용이며 D15의 TTL·same-day·장기 자정 갱신은 유지한다
  - **렌더**: 현재 봉만 now까지 연장하고, 시각 없는 최신값으로 고저를 재확장하지 않으며, 무효 구간을 이웃 음영으로 잇지 않고,
    선의 마지막 가격을 음영 입력으로 역류시키지 않는다. 음영 모양은 바꾸지 않고(미결) DXY 음영을 신설하지 않는다
  - **장기(1w/3m/1y) 오른쪽 끝**은 검증되고 선 정책에 맞는 최신값만 쓰고, 출처 없는 옛 tip은 복귀 후 첫 발행부터 제외한다.
    끝점을 now에 두는 것은 현재 10분봉 관측 기록이 아니다. 1일 복구 성공이 장기 REST 요구를 해제하지 않는다
  - **DXY tip**: timestamp 우선, 동시각이면 investing > cnbc > yahoo. 같은 timestamp·source의 가격 충돌은 최초 채택 tip을 유지하고
    충돌 관측을 중복으로 삭제하지 않는다. 서버 row id가 wire에 없어 마지막 tie-break의 완전한 parity는 보장하지 않는다.
    `TopicRates` 병합은 유지한다
- 갱신 2기전 분리(D15), 단일 주입 Clock으로 TTL·same-day·타이머 공유
- **`/api/graph` 및 v1 graph cache 소비 제거**. 같은 변경 세트에서 `graph_cache_*.json`과
  `fxi_graph_preferences`를 삭제하고 v2 graph namespace를 authoritative로 전환한다
- **DoD**: catalog version String·TTL override / catalog에 없는 series 합성 0 / Resolving/Pending의 disk read/render 0,
  same-UID fresh ACTIVE 뒤에만 seed 개방·fresh로 오인하지 않음 / KRX cache 분리 및 capability revoke 뒤 비-KRX cache 유지 /
  은행·인베스팅(통화별)·DXY 어댑터에 iOS 확정 계획(ios `89e866d`) 합성 입력을 원문 입력·기대 결과 그대로 적용해 통과 —
  T02·T04~T12·T14~T21·T23(일반 범위)·T25(무료 회귀)·T26(은행)과 저가 대칭 T05-L·T06-L·T07-L·T08-L·T10-L·T16-L, 추가 경계 입력
  (빈 입력·NaN/Inf·파싱 실패·역순·중복·source 불일치·같은 5초 구간·시계 차이·보관 범위 밖·low>high·진단 로그 on/off). DXY에는
  범위·음영 사례를 적용하지 않고 시각·중복·복구·선 사례만, T21은 catalog가 제공하는 기간에만 적용한다 / 타이머만으로 봉·고저
  생성 0 / 복귀 후 모든 발행에서 출처 없는 옛 tip 0 / 반복 seed 합집합·닫힌 봉 재오염 0 / 명시적 철회·세션 종료 뒤 기록·요청
  부활 0, 철회 저장 실패·미확정 중 read/render·채택·요청 0, KRX 취소만으로 비-KRX 관측 폐기 0, 공유 자료의 소비자별 접근 검사 /
  close 계약·25h prune·크기 한도·off-screen flush·freshness 600/1200의 선 사용 역할 / 23:59→00:00 전이 / 동시 fetch single-flight
  (scope·권한·tab·period 단위)(동결 후 7번)
- **S4·S5·S6 공통 시험 계약**(동결 후 7번): 각 슬라이스에 배정된 T 번호는 iOS 원문의 전체 입력·기대 결과를 승계한다.
  위 추가 경계 입력도 S4·S5·S6의 해당 어댑터마다 적용한다. topic–asset 불일치 / WS·REST 같은 관측 재전달 /
  catalog·화면 전 입력 / 공백 이력≠복구 완료 / sparse 응답·경계 통과 / 크기 한도·미래 시각 / 비동기 purge 완료도
  세 슬라이스의 해당 어댑터에서 검증한다. T21은 catalog가 제공하는 기간만, T22는 장기 교집합·catalog 변경까지,
  T23은 공유 자료의 소비자별 접근 경계까지 포함한다. DXY의 범위·음영 사례 제외 규칙은 그대로 적용한다.

### S5 — 테더 탭
- 거래소 5 + 참조(kb·hana·investing) 시세 (preference는 S1.5에서 도입, 여기서 테더 표면에 연결)
- 테더 Graph — **1d만이 아니라 catalog가 제공하는 전 기간(1d/1w/3m/1y)** 소비. 1d 다중 series 성능 검증
- **DoD**: Graph → 시세 순서와 S8/S9용 안정 insertion slot / 거래소5+참조 effective projection /
  catalog 전 기간 동작 / 거래소 5종 각각에 합성 입력 T01·T03·T03-L과 해당 경계·복구·극값 보존·권한·기간 전환 사례(T02·T04~T12·
  T14~T16·T19·T21·T23, 저가 대칭 포함)를 적용해 통과, 테더 참조(은행·인베스팅) 행에 단일 시각 T17·T18, 비-KRX 공통 series 두 탭
  일치(T22 비-KRX 부분 — 1일과 장기 교집합·catalog 변경 포함), `dxy_futures` REST 전용(T24), 무료 snapshot 회귀(T25). 장기 거래소 series는 catalog 구성을 따르고 없는 series를 합성하지
  않는다(동결 후 7번). **성능은 판정 가능한 명제로 고정**: 고정한 실기기·빌드·fixture에서 1d 11-series 전체 표시 상태로
  실제 `MainActivity → RootScreen` 진입의 premium 목적지를 거친 Tether 경로를 사용하고 운영 계정·운영망에 의존하지 않는 benchmark-variant 전용
  deterministic fixture로 같은 11-series/period/clock을 공급한다. public release에는 fixture action·payload·우회 branch가 0이어야 한다.
  benchmark fixture root의 상위 semantics에 `testTagsAsResourceId=true`를 켜고 Tether 탭·기간·스크롤 대상에
  중복 없는 stable Compose `testTag`를 부여해 UIAutomator resource selector로 각각 독립 측정한다.
  각 여정은 `CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations=3)` + `StartupMode.WARM` +
  **측정 5 iterations**로 고정한다. 각 여정의 benchmark invocation을 thermal `NONE(0)`에서 **3회 독립 실행**하고,
  run별 Macrobenchmark `FrameTimingMetric.frameOverrunMs` P95/P99 중 각각의 중앙값을 판정값으로 쓴다.
  절대 완료조건은 모든 여정이 **`median(P95) ≤ 0.0ms ∧ median(P99) ≤ 8.33ms`**인 것이다. `frameOverrunMs`는
  실제 display deadline을 이미 반영하므로 120Hz의 8.33ms를 다시 빼거나 60Hz로 낮춰 기준을 완화하지 않는다.
  각 run의 P95는 그 run frame 95%의 on-time 여부를 나타내며, 세 run 중앙값은 run 간 noise를 제어한다.
  P99 중앙값은 대표 tail 99%가 deadline 뒤 한 120Hz interval을 넘지 않게 한다.
  각 tag의 UIAutomator lookup 정확히 1건, 기대 화면 상태와 action 완료·`frameCount>0`을 함께 assert해
  bridge/tag 누락·selector miss·무프레임 false-green을 막는다.
  이를 “dropped frame 0”으로 바꿔 쓰지 않는다.
  고정 SM-F711N 실기기의 model/build fingerprint/OPENED posture/120Hz가 S0 evidence와 다르거나
  thermal throttling·metric 누락이 있으면
  해당 run을 무효화한다. 여정별 최대 5회 시도 안에 3개 유효 run을 얻지 못하면 환경 gate 실패이며 무한 재실행하지 않는다.
  각 유효 run 사이에는 thermal `NONE(0)` 복귀를 기다리고 세 여정을 한 invocation으로 묶지 않는다.
  JSON과 Perfetto trace를 보존하고 checked-in parser가 threshold를 실제 process exit 실패로 연결한다.
  `dumpsys gfxinfo framestats`는 실패 triage에만 쓴다. S5 절대 gate 미달은 S11로 이월하지 않고 S5 미완료로 남긴다.
  세 여정이 통과하면 golden evidence manifest에 **source commit SHA, target benchmark APK SHA,
  macrobenchmark test APK SHA(선택자·action 포함), fixture payload SHA, checked-in parser SHA,
  macrobenchmark harness dependency lock/resolved graph SHA, Benchmark/UIAutomator/ProfileInstaller version,
  target production dependency graph·R8 rules SHA, device fingerprint·preflight, 결과 JSON·Perfetto trace**와
  여정별 `median(P95/P99)`를 이름이 구분된 필드로 봉인한다. 이 승인된 manifest의 여정별 `median(P99)`만
  S11 비교용 **`B99` golden**이며 S5 합격선을 만드는 데 사용하지 않는다.
  최종 Graph→시세→거래소 가격알림→김프→비교 순서는 S9에서 검증

### S6 — KRX positive lifecycle
- `krx_visible` flip → `krx:usd-krw-futures` desired/REST bootstrap 시작·중단, 표시 표면 노출. KRX bootstrap만
  transient 3회(0.5/1.5s ±20%)다. 401은 auth 복구, 403은 `.forcePremium` 재확인 신호로 D23 reducer에 보낸다.
  prior-grant KRX bootstrap의 404 body `error=unknown_topic`은 존재 비노출형 revoke 신호로 즉시 hide/purge +
  `.forceEntitlements`; `topic_unavailable`은 capability를 바꾸지 않고 degraded 처리한다. status만 보고 두 404를 합치지 않는다
- 표면 inventory: USD 현재가 row/fullscreen/customizer, Tether source row/fullscreen/customizer, tether·USD Graph
  series/toggle/cache/live/in-progress, USD source-alert, USD absolute comparison, tether signed 김프, settings/history,
  FCM event/tap, accessibility/analytics/log text. S6은 공통 gate/purger를 만들고 S8/S9가 해당 알림 표면을 완결한다
- Graph **tether 탭의 hana.usd↔krx.*** 쌍만 visible+initialized에서 제거 후 새 default를 영속(D17).
  다른 tab/series 선택은 건드리지 않고 source preference raw 원본은 보존
- WS `krx_entitlement_required` → 즉시 숨김 + 강제 재조회, `true→true` 재확정 콜백으로 재구독
- **DoD**: WS rejection과 REST `unknown_topic` 모두 revoke 즉시 unsubscribe + KRX 메모리·디스크·live-tail·pending tap purge + late response 폐기 /
  KRX 관측 인계 연결과 합성 입력 T01·T02·T03·T04~T16·T21·T22(KRX 부분)·T23(KRX 부분),
  저가 대칭 T03-L·T05-L·T06-L·T07-L·T08-L·T10-L·T16-L 통과. T12/T23은 KRX 취소를 포함한다(동결 후 7번) /
  승인 없으면 inventory의 구현 완료 표면에서 선택지·빈자리·placeholder·semantics도 없음 / 재승인 시 raw preference와 서버 row 복원

### S7 — 은행 알림 (repeat · history · `rate_alert`)
- `repeat_interval_sec` 허용값 `{60,300,600,1800,3600,7200,14400,21600,43200,86400}` + null,
  **PUT 3-state**(Unset 생략 / Null=1회 / Value=반복) — kotlinx-serialization에서 명시적으로 구분한다.
- 히스토리는 현 `GET /api/notification-logs` 계약을 그대로 소비한다: 기본 100·최대 200·offset 없음,
  success-only `sent_at DESC`, `condition/threshold`는 구 row에서 nullable이다. UI 문구도 iOS처럼
  **알림 히스토리 / 발송된 알림** 의미를 유지하며 조건-event나 단말 수신 기록으로 바꾸지 않는다.
  탭별 요청은 `currency` 필터를 최초 load·retry·pull-to-refresh 모두에 유지한다
  (`ios/FXi/Views/Components/AlertHistorySheet.swift:13-14,30,62,73`).
- 설정 GET 재시도는 iOS family별 소유권을 그대로 포트한다. S7은 공통 phase/controller interface와 bank 구현만
  연결하고, S8 source·S9 comparison이 각 family owner를 등록한다. **세 family 모두**
  `AlertRetryPolicy`·단일 wake-owner·중복 GET 병합(진행 중이면 합류)을 쓴다
  (`ComparisonAlertViewModel.swift:27,40,92-97,131-136`). **차이는 하나** — bank/source만 Retry-After hard floor(`notBefore`)를
  owner 경계 **밖**으로 보존하고, comparison은 owner 내부 sleep만 쓴다(별도 notBefore·manual cooldown 없음).
  세 history 화면은 on-demand 1회 load + 사용자 retry/pull-to-refresh이며 자동 wake-owner를 추가하지 않는다.
  이는 모두 **push delivery 재시도**가 아니다.
- **typed load phase(iOS 실재 계약)**: 3 family 섹션은 공통 `idle/loading/retrying/ready/terminalError`를 쓴다
  (`ios/FXi/ViewModels/AlertLoadPhase.swift:16-32`). `retrying`은 **사용자 비노출**(기존 목록 유지 또는 중립 로딩),
  오류 카드는 `terminalError`이면서 **표시할 목록이 비어 있을 때만** 그린다(`AlertSection.swift:178,181`).
  성공 snapshot(정상 빈 목록 포함)이 있으면 실패해도 snapshot 의미를 유지한다
  (현 `AlertViewModel.hadLoadedState:204,212-215,233-236` 승계).
  임시-오류 판정에 서버 문구 문자열을 쓰지 않는다(typed `AlertLoadFailure`).
  ⚠️ 현 Android `AlertState`(`AlertViewModel.kt:37-45`)에는 `Retrying`이 없어 첫 실패가 즉시 `Error`로 확정되고
  `AlertSection.kt:266`에 `settings.isEmpty` 가드가 없다 — 자동 재시도를 도입하는 이 슬라이스에서 함께 고친다.
- **lifecycle(iOS 실재 계약, family별 분리)**:
  - bank/source는 background에서 in-flight 응답을 generation guard로 폐기하고 대기 demand를 취소하되
    Retry-After hard floor·수동 쿨다운을 보존한다. 성공 snapshot은 유지하고 없으면 idle로 돌린다
    (`AlertSettingsViewModel.swift:415-431`, `SourceAlertSettingsViewModel.swift:167-183`). 일반 foreground는
    성공 snapshot + 마지막 load 30초 이내면 skip한다.
  - comparison은 background에서 generation/in-flight/pending refresh만 취소하고 alerts·phase를 그대로 보존한다
    (`ComparisonAlertViewModel.swift:166-173`). 별도 notBefore/manual cooldown이 없고, 일반 foreground의
    `loadIfNeeded()`는 snapshot이 있으면 경과 시간과 무관하게 skip하며 없으면 load한다(`:79-83`).
  - `sync_alerts` pending이면 **그 시점까지 registry에 등록된 family만** authoritative refetch한다. S9에서 세 family가 완성된다.
- S7에서 `AlertFamilyRefresherRegistry`와 family lifecycle adapter 계약을 정의하고 bank adapter를 등록한다.
  process/root-scoped lifecycle observer **한 곳만** background/foreground를 받아 등록된 adapter로 fan-out한다
  (iOS `FXiApp.swift:185-226` 대응). 탭·section·family별 별도 lifecycle observer는 금지한다.
- **알림 권한 진입점(iOS 실재 계약)**: permission helper는 권한 안내/banner·설정 row, 알림 **추가 진입**과
  bank/source 저장 시 방어 확인에서 호출한다(`NotificationPermissionManager.handleActionAsync` 10 call site).
  기존 row의 inline toggle은 helper를 호출하지 않는다(`AlertSection.swift:529-535`,
  `SourceAlertSection.swift:458-463`, `ComparisonAlertSection.swift:407-411`). premium이 아니면 요청하지 않고,
  notDetermined면 runtime 요청, denied면 앱 알림 설정 화면으로 유도, 이미 허용인데 미등록이면 **S1의 단일 push
  coordinator에 재평가 신호**를 보낸다. S7이 별도 token/register owner를 만들지 않으며 S8·S9도 같은 UI helper를 재사용한다.
- premium editor 전용 UID-scoped `lastSelectedBank`를 만들고 신규/저장 성공 시에만 갱신한다.
  free preview는 읽기·쓰기 0이며, 새 store 배선 후 legacy `fxi_cache.last_bank_*`를 삭제한다.
- **공통 typed FCM parser/refresher**를 여기서 도입하고 S8·S9가 family를 추가한다.
  - `rate_alert|source_rate_alert|comparison_alert`는 서버의 현 `notification+data` visible payload를 사용한다.
    foreground는 `FXiMessagingService`가 단일 `rate_alerts` 채널로 표시하고 local state event를 보낸다.
    background/killed는 OS tray가 표시한다. 이 차이를 이중 표시하지 않도록 lifecycle fixture로 잠근다.
  - `sync_alerts`는 data-only silent이며 현재 등록된 family의 설정을 refetch한다. killed delivery를 보증하지
    않으므로 일반 foreground/screen load도 서버 GET을 정본으로 사용한다.
  - `setting_id`는 family scope 정수, `is_repeat`는 문자열 `"true"|"false"`가 권위다.
    once만 local triggered/disabled로 반영하고 repeat는 enabled를 유지한다. ID가 없거나 로컬 row가 없으면
    해당 family를 refetch하고, unknown type은 무시한다.
  - foreground 처리와 tap callback은 current auth/premium을 확인하고 KRX family면 `krx_visible`도 확인한다.
    통과 뒤 setting을 authoritative refetch해 local state를 수렴시킨다. 실패·삭제·malformed는 side effect 없이
    무시한다. tap은 앱을 여는 기본 동작까지만 하며 section deep-link/focus는 추가하지 않는다(iOS TODO parity).
    background OS 표시를 이 검사로 차단했다고 주장하지 않는다.
- 서버 `success_count>0` 뒤 once/repeat/history 전이가 정본이며 Android는 client receipt·pending overlay·
  delivery suspension을 새로 만들지 않는다. authoritative GET이 local optimistic state와 다르면 서버 값을 따른다.
- Citi 정책(D7), family soft cap 30(초과 기존 row 편집·삭제 허용), 무료 preview(D26)를 함께 적용한다.
- **DoD**: absent/null/value JSON / once·repeat 상태 전이 / 404·타 기기 변경 refetch /
  현 FCM 4-type golden / visible alert foreground·background·process-killed 표시 동작 /
  background에서 app callback 없이 표시되는 경우의 다음 정상 load 수렴 / sync silent refetch /
  string `is_repeat`, unknown type, malformed/missing id, tap 기본 launch+family refetch /
  current premium·KRX 실패 시 app-owned local update/refetch 0 /
  발송 히스토리의 nullable old row·기본 100/최대 200·**currency filter 전 경로 유지 및 타 통화 row 혼입 0** /
  **bank** inline toggle의 OS permission prompt 0·추가/저장 진입 permission flow /
  root lifecycle observer 1개·family별 중복 callback/GET 0 / 무료 preview side effect 0


### S8 — source 알림 (거래소 · KRX · `source_rate_alert`)
- 유효 조합: 거래소 5 × `usdt-krw`, `krx` × `usd-krw-futures`. 참조 소스는 400 + 안내
- **asset 파티셔닝**: 테더 탭 = `usdt-krw`, 달러 탭 = `usd-krw-futures` (iOS 이중 렌더 버그 전례)
- 히스토리 필드명 비대칭 주의: FX는 `rate`, source는 `triggered_rate`
- 탭별 source 히스토리는 `asset` 필터를 최초 load·retry·pull-to-refresh 모두에 유지한다
  (`ios/FXi/Views/Components/SourceAlertHistorySheet.swift:12-13,29,61,72`).
- KRX row 2차 필터(응답·cache·in-flight event·tap·analytics 이전) 재검증
- premium 테더 editor 전용 UID-scoped `lastSelectedSource`를 만들고 저장 성공 시에만 갱신한다. free preview 및
  USD/KRX 단일-source scope는 이 preference를 읽거나 쓰지 않는다
- family soft cap 30(초과 기존 row 편집·삭제 허용), `source_rate_alert` parser/refresher, 무료 preview는 D26 동반
- S7 `AlertFamilyRefresherRegistry`에 source refresher와 lifecycle adapter를 등록
- **DoD**: 거래소·KRX source alert CRUD/history/FCM end-to-end / 현 서버 payload와 once/repeat 발송 의미 정합 /
  SV-1에서 KRX 미승인 신규 FCM·setting 전이·history 0 / SV-2와 client filter로 설정·히스토리·picker·tap 0 /
  **asset filter 전 경로 유지 및 테더/KRX history row 혼입 0** /
  **source** inline toggle의 OS permission prompt 0·추가/저장 진입 permission flow /
  visible notification의 foreground·background·process-killed 동작 / 무료 preview side effect 0

### S9 — 비교 · 김프 (`comparison_alert` · `sync_alerts`)
- absolute = Tether와 USD/JPY/EUR 각 탭 내부(순서 무관, threshold ≥ 0, canonical 정렬 응답 수용),
  signed = Tether 전용(음수 = 역프)
- pair 편집 = **create 성공 후 old delete**(새 id). old delete 실패 시 **새 row는 유지**하고 '기존 알림 삭제 실패'를
  사용자에게 노출하고 편집 sheet를 닫지 않는다(404는 성공 취급). 로컬 목록에는 old+new가 모두 남으며 사용자가
  old를 수동 삭제한다 — iOS `ComparisonAlertViewModel.swift:219-239`, `ComparisonAlertAddSheet.swift:425-429` 계약이다.
  **보상 삭제·authoritative refetch·자동 reconciliation은 넣지 않는다**. 저장 전 중복 차단 UI
- family soft cap 30(김프+비교 합산, 초과 기존 row 편집·삭제 허용), `comparison_alert` parser/refresher,
  S7 registry에 comparison handler를 등록해 이 시점부터 `sync_alerts` pending 경로가 bank+source+comparison 3 family를
  authoritative refetch(일반 foreground 복귀는 S7의 family별 디바운스 규칙을 따른다), 무료 preview는 D26 동반
- 각 섹션 히스토리는 **`tab` + `diff_type`**(김프=`signed` / 비교=`absolute`)으로 서버 필터를 걸어 조회한다
  (iOS `ComparisonAlertHistorySheet.swift:32`; 재시도·pull-to-refresh도 같은 필터). 필터 없이 구현하면 김프 시트에
  일반 비교 발송 기록이 섞인다
- KRX가 어느 leg든 포함되면 현재 entitlement를 **event/store/tap/analytics 전에** 재검증한다
- **DoD**: absolute 4탭 + signed Tether / create 실패 시 old 보존+delete 미호출 /
  old delete 404는 성공 취급+local old 제거 / 그 외 delete 실패 시 old+new 유지+sheet 유지+수동 삭제 고지(자동 refetch 0) /
  **섹션 간 히스토리 row 혼입 0** / 현 evaluator spread·fallback 의미 정합 /
  SV-1에서 KRX 포함 pair 미승인 신규 FCM·setting 전이·history 0 / SV-2와 client filter로 설정·히스토리·tap 0 /
  visible FCM foreground·background·process-killed 동작 /
  `is_repeat` string golden / **comparison·김프** inline toggle의 OS permission prompt 0·추가 진입 permission flow
  (save-time defensive prompt는 요구하지 않음) / 무료 UI network·권한·FCM side effect 0 /
  테더 탭 최종 섹션 순서 **Graph → 시세 → 거래소 가격알림 → 김프 → 비교**

### S10 — 뉴스 · 설정 · 계정삭제 · preview polish
- 뉴스: 프리미엄 호스트 필터(`fx.kbstar.com`, `rreport.einfomax.co.kr`) + 2줄 배너, `report_pdf`는 외부 브라우저
- KB 기본 정책은 Android WebView + 필요한 이미지 host만 allowlist한 mixed-content 설정이며 iOS regex HTML parser는 이식하지 않는다.
  실기기 smoke 실패 시 v2.0.0 fallback은 외부 브라우저이고, native parser 추가는 별도 계획/GO다
- 설정 IA: 알림 권한 섹션, 비구독은 평가 메뉴 숨김
- 계정삭제 UI/E2E: S1 push coordinator `registration hold`로 신규 POST를 먼저 닫고 in-flight를 drain → **서버 요청 전에**
  backup 제외 `DeletionPending(uid,REQUESTING_SERVER)`를 persist해 Root와 모든 data-plane/topic/Graph/free fetch·mutation·FCM
  admission을 차단 → idempotent `DELETE /api/user/me` 실행. 204 뒤 phase를 `SERVER_DELETED`로 persist하고 user/KRX epoch
  rotate·local/RevenueCat/user cache purge → Firebase Auth 삭제 직전에 `FIREBASE_DELETE_IN_FLIGHT`을 persist하고, SDK 성공 뒤
  `FIREBASE_DELETED`를 persist한다. 이 purge는 active deletion record/request id/phase와 완료되지 않은 push/access purge journal을
  보존하며, **`DeletionFinalizer`만** `FIREBASE_DELETED` terminal local cleanup 뒤 deletion record를 마지막으로 clear한다. SV-4 protocol을 사용하면
  `X-Account-Deletion-Protocol: 2`와 network 전 persist한 `deletion_request_id`를 보내고
  `FirebaseAuthInvalidUserException/ERROR_USER_NOT_FOUND`는 이미 삭제된 성공으로 normalize한다
  (`ERROR_USER_DISABLED`는 삭제 완료가 아니므로 성공 처리하지 않는다). Firebase 삭제가 실패해 auth token이 살아 있어도 tombstone이 Root보다 먼저 복원되어 허용 작업은
  재인증/Firebase 삭제 재시도뿐이며 서버 row/device를 다시 만들지 않는다. 성공하면 sign-out 뒤 tombstone을 clear한다
- startup에서 phase가 **`FIREBASE_DELETE_IN_FLIGHT`**이고 phase owner가 last-auth UID와 맞으며 coordinator 밖의 explicit
  sign-out이 없었는데 `auth.currentUser==null`이면 현재 설치의 Firebase delete terminal success로 normalize해 멱등 local purge/sign-out
  후 tombstone을 clear한다. `SERVER_DELETED+currentUser null`은 아직 SDK delete 호출 전일 수 있으므로 성공이 아니다. 재인증해 delete를
  수행하거나 SV-4 receipt status가 `finalized`임을 증명할 때까지 tombstone을 유지한다. `pending`은 locked retry UI,
  `not_found`는 재인증·DELETE 재시도이며 token-expired/network/disabled도 success로 세탁하지 않는다
- startup phase가 `FIREBASE_DELETED`면 auth restore 상태와 무관하게 remote delete가 이미 성공한 정본이므로 local purge/sign-out을
  멱등 완료하고 tombstone을 clear한다. 이 phase persist 직후 crash가 locked UI로 영구 잔류하지 않는다
- 사용자가 서버 요청 **전** 취소했거나 bytes 미전송이 증명된 local 실패만 tombstone/hold를 해제한다. timeout/5xx처럼 commit
  여부가 모호하면 tombstone을 유지하고 동일 DELETE를 재시도한다(0-row 재삭제도 204). 확정 401은 재인증 뒤 재시도하거나,
  요청이 DB 삭제 전에 거부됐음을 typed contract로 확인한 경우에만 사용자 취소를 허용한다. 서버 성공 뒤 Firebase 실패는
  부분성공 안내 + Firebase 삭제 재시도 CTA를 제공하며 process death 뒤에도 같은 phase에서 복구한다
- SV-4를 후속 배포한 뒤에는 app-data clear/reinstall/다른 기기의 typed `410 account_deletion_pending`도 일반 오류나
  Free로 가지 않고 local `DeletionPending(SERVER_DELETED)`을 재구성한다. SV-4 전에는 S10 보증 범위가 **현재 설치의
  crash/process-death**까지이며 기존 cross-device 재생성 공백은 후속 위험으로 명시한다
- 무료 알림 preview polish
- **DoD**: premium host 필터/free banner / KB WebView와 external fallback 실기기 smoke / deletion hold 중 token 회전 POST 0 /
  request 전 cancel은 admission 복원 / timeout·5xx·204 직후·Firebase 실패 각 process-death 지점에서
  API·WS·mutation·register-device 0 및 idempotent retry CTA만 노출 / Firebase remote success 직후 callback 전 crash에서
  `FIREBASE_DELETE_IN_FLIGHT+currentUser null`이 terminal local success / Firebase success 또는 user-not-found 뒤
  204→domain purge 각 crash 경계에서 deletion control record 보존 / `FIREBASE_DELETED` persist 직후 crash recovery /
  sign-out·finalizer-owned tombstone clear·UID-scoped domain store 0

### S11 — 릴리스
- versionCode = Play Console 실제 최대값 + 1 (업로드 직전 확정).
- **arming 검증(D24)**: v2-ON minified CI → internal/closed ON artifact → public ON AAB.
  AAB의 gate 값, artifact SHA, versionCode/name, applicationId, signing certificate를 evidence에 기록한다.
  arming·build·Play upload·rollout 확대는 각각 별도 GO다.
- **서버 evidence**: 정확한 SHA/config, SV-1의 모든 visible-alert call-site gate와 low-level tripwire,
  SV-2 filter-before-limit, D30 internal IPC 인증·worker/replica=1 inventory·**D30-CANARY 단일 게이트**,
  실제 scheduler/hana/woori subprocess FCM smoke, gated rollback rehearsal를 증명한다.
  deny 뒤 FCM/setting/history/token mutation 0과 allow+FCM success 뒤 현 once/repeat/history 전이를 함께 확인한다.
  D30 nginx runbook의 1차/2차 pre/post container ID·`StartedAt`, host/runtime config SHA-256,
  nginx 구조 검사, 외부 internal 404와 `internal_auth_nonloopback_total` 불변, 최종 `/health`·대표 API 결과를 같은 evidence에 봉인한다.
  multi-process `RotatingFileHandler`가 쓰는 `app.log`/admin reader만을 child evidence 정본으로 삼지 않고,
  세 spawn 경로의 child/cleanup 증거는 bounded Docker log와 PID/PGID inventory에서도 함께 수집한다.
- **관측 범위**: family·sender path별 authorization allow/deny/UNKNOWN, ungated attempt, FCM accepted/failure/latency,
  invalid-token, register-device 결과, enabled 설정+device 0 baseline을 저장한다. client ACK가 없으므로
  FCM accepted를 단말 표시나 impression으로 표현하지 않는다.
- 4상태(로그아웃/free/premium/premium+KRX) × v1 upgrade/backup restore/process death ×
  FCM foreground/background/process-killed 수신·표시·tap/refetch matrix를 통과한다.
  Android v1.2.2의 bank `rate_alert` 회귀와 새 source/comparison family의 구버전 제한도 별도 smoke/known risk로 기록한다.
- Graph 성능은 S5와 같은 고정 실기기·fixture·세 독립 여정·3개 유효 run 중앙값으로 다시 측정한다.
  각 여정은 절대 gate **`median(P95) ≤ 0.0ms`**를 다시 만족하고, candidate `median(P99)`는
  **`≤ min(8.33ms, max(0.0ms, B99 + 2.0ms))`**를 만족해야 한다. `B99`는 S5를 통과해 봉인된 같은 여정의
  golden 값이다. candidate도 source commit SHA, target benchmark APK SHA, macrobenchmark test APK SHA,
  fixture payload SHA, parser SHA, harness dependency lock/resolved graph와 library version, target production
  dependency graph·R8 rules SHA, device/preflight, 결과 JSON·Perfetto trace를 같은 schema로 봉인한다.
  **macrobenchmark test APK·fixture payload·parser·harness dependency lock/resolved graph·library version과
  device/preflight 측정 계약은 golden과 byte-identical**해야 한다. 하나라도 바뀌면 기존 `B99`를 비교에 쓰거나
  단순 재시도하지 않고, S5 절대 gate로 새 golden을 만든 뒤 별도 승인하여 교체한다. candidate의 source commit과
  target benchmark APK SHA가 golden과 다른 것은 정상이며 그 차이를 기록한다.
  public AAB는 candidate와 **같은 source commit·version catalog/lock·production dependency graph·R8 rules 입력**에서
  산출하고 AAB SHA를 기록한다. 차이는 승인된 signing/variant 설정과 benchmark-only fixture overlay뿐임을
  build provenance와 public artifact source scan으로 증명하며, public AAB에 fixture action·payload·우회 branch가 있으면 fail한다.
  `+2.0ms` 마진은 결과 관측 전 고정값으로, 수치·원인 기록만으로 통과를 대체하지 않는다. 기준 완화가 필요하면
  릴리스 현장에서 묵시적으로 처리하지 않고 이 계획의 명시적 개정으로 되돌린다.
- legacy REST 호출·WS `rates` 소비 0을 proxy/network assertion과 source scan으로 증명한다.
- USDT source evaluator owner는 source별 단일이어야 한다. 정상 v2는
  `USDT_LEGACY_REST_POLLING_ENABLED=false`이고 enabled WS/fallback owner만 발화한다.
- rollout 순서: Debug contract/integration → ON-minified R8 → Internal 24h → Closed 48h →
  staged 1%/24h → 5%/24h → 20%/48h → 50%/48h → 100%.
  각 확대는 dashboard snapshot과 별도 GO를 요구한다.
- **즉시 중단**: SV-1 authorization 없이 시작된 신규 KRX FCM RPC 1건, SV-2의 무권한 KRX settings/history 1건,
  ungated sender 1건, D30 worker/replica 2 이상·외부 probe의 FastAPI internal handler 도달·구 token 허용 또는
  D30-CANARY `infrastructure zero` 위반 1건,
  legacy data 소비 1건, auth account-crossing app-owned state 1건.
  revoke 전에 이미 broker가 수락한 stale tray는 v2 hard-zero gate가 아니라 incident로 기록하고 후속 문서의 재검토 trigger로 삼는다.
- 품질 중단: Play bad-behavior threshold 초과, v1 baseline 대비 crash-free users 0.2%p 이상 하락,
  ANR 0.2%p 이상 상승, server-confirmed premium의 잘못된 `premium_required` 1건.
- **DoD**: 위 evidence + SV-1/SV-2 production 검증 + arming GO + Play upload GO.
  미기입 SHA/config/threshold가 있으면 release blocked다.

---

## 8. 교차 검증 매트릭스 · rollback 원칙

> 슬라이스별 완료 조건은 §6 · §7 각 항목에 **인라인**으로 둔다(같은 내용을 두 곳에 적으면 반드시 어긋난다 —
> 이 리포의 반복 실패 사례). 이 절은 **슬라이스를 가로지르는** 검증만 다룬다.

### 8.1 접근 상태 4종 × 표면

| 표면 | 로그아웃 | 무료(로그인) | Premium 일반 | Premium + KRX 승인 |
| --- | --- | --- | --- | --- |
| 화면 | Login only | 5탭(무료) | 5탭(프리미엄) | 5탭 + KRX |
| 데이터 | 없음 | `/api/v2/free/snapshot` | topic WS + bootstrap + Graph V2 | + `krx:*` |
| WS | 0 | **0** | 연결 | 연결 |
| FCM 등록 | 0 | **0** | 등록 | 등록 |
| 알림 mutation | 0 | **0** | 전체 | 전체 |
| app-owned KRX 데이터·UI 흔적 | 0 | **0** | **0** | 노출 |

### 8.2 교차 테스트
- **계약 fixture**: 전 topic, ACK/error/lease, DXY, 무료 4탭×4기간, Graph, 알림 3 family, FCM 4 type
- **premium state**: 새 UID resolving, 200 pending, stable false/true, 판정 불가 last-good, purchase propagation probe,
  HTTP 403, WS `premium_required`, fresh recovery, `.forceEntitlements`/`.forcePremium` query 분리,
  **판정 불가 반복 후에도 기존 grant 유지**(시간 만료 없음), **200+pending에 실린 `krx_visible=false` 즉시 적용**,
  code 없는/미지 403의 선제 KRX purge 0, pending/실패 단일 retry owner·hard floor·취소/late-generation fence,
  반복 pending false의 KRX teardown 1회, late-response `userAccessEpoch`/`krxCapabilityEpoch` fence
  - sequence A: fresh ACTIVE + KRX true + `mayContainKrxData=true`를 먼저 만든 뒤 pending(false) 적용 → premium은
    `PremiumConfirmed` 유지 / KRX false / capability epoch rotate+purge 정확히 1회 / 동일 pending 반복 시 추가 rotate·purge 0
  - sequence B: fresh ACTIVE + KRX true를 먼저 만든 뒤 503·최종 401·code 없는/미지 403·decode/protocol 오류를 각각 주입 →
    premium/KRX true와 user/KRX epoch 불변, affected operation만 중단, 재확인 예약만 1 owner
- **merger**: stale / equal / newer, missing ≠ delete, wrong asset, KRX 오염
- **topic**: bootstrap↔WS 경합, reconnect, lease 0·same-id hard-expiry, absolute delivery deadline, whole/per-topic 오류표,
  body retry-after, UID/`userAccessEpoch`/`krxCapabilityEpoch` 전환
- **무료**: 4탭 × 4기간 조합, rate/series/provenance KRX 오염 (스케줄러·expiry 세부는 S2 DoD 소관 — 여기서 재기술하지 않는다)
- **알림**: tri-state JSON, signed/absolute, pair 변경, once/repeat, 발송 히스토리 3종,
  nullable old row, 기본 100/최대 200, SV-1 allow/deny 뒤 현 setting/history 전이, SV-2 filter-before-limit,
  D30 동일 UID single-flight·runtime budget·IPC down/timeout deny/no-mutation
- **FCM**: 현 `notification+data` visible 3 type + data-only silent `sync_alerts` golden,
  foreground/background/process-killed 표시 차이, foreground local state·tap family refetch, force-stop 뒤 정상 load,
  unknown type, string `is_repeat`, missing/malformed id, legacy Android v1 bank alert 회귀
- **보안**: `krx_visible=false`에서 app-owned UI·cache·settings/history·tap·접근성·analytics 0,
  SV-1 철회 관측 뒤 신규 KRX RPC 0. 철회 전 broker-accepted tray는 별도 known risk
- **migration**: 1.2.2 → 2.0.0, process death, 계정 교체, 백업 복원
- **release**: R8 serialization, API 26/33/36, FCM cold start, RevenueCat 복원
- **production E2E**: 무료 / premium 일반 / premium KRX 승인 3계정(+로그아웃)
- **network assertion**: legacy REST 호출 0, WS `rates` 소비 0

### 8.3 rollback 원칙
- v2 내부에 legacy fallback 스위치를 **두지 않는다**(D22)
- 배포 전 슬라이스 rollback = 기록된 commit set의 승인된 revert 또는 follow-up patch(수정·commit·push GO 각각 필요)
- 출시 후 = Play staged rollout 중단 + forward-fix. 이미 설치된 cohort는 rollout 중단으로 회수되지 않는다
- 서버 sender rollback = SV-1 `enforce`를 유지한 gated legacy 또는 SV-1 `pause`만 허용. ungated mode 전환 금지,
  SV-0 canary/central을 실제 배포한 경우에만 central drain/backlog 처리를 포함한 rehearsal을 production rollout 전에 증명

---

## 9. 마이그레이션 · legacy 제거 증명

### 9.1 v1 → v2 로컬 상태
v1 store는 소유권 또는 shape가 v2 계약과 맞지 않으므로 **이관보다 안전한 reset/purge**가 기본이다. 삭제는 새 소비자로
전환된 슬라이스가 소유하며 S1에서 선행 삭제하지 않는다.

| 대상 | 처리 | 소유 슬라이스 |
| --- | --- | --- |
| DataStore `fxi_cache`의 `rates`, `rates_timestamp` | decode/이관 없이 key 삭제 | **S3**, legacy rate consumer cutover와 원자적 |
| DataStore `fxi_cache`의 `last_bank_*` | v1 값은 UID 소유권 불명이라 이관하지 않음; v2 UID-scoped alert selection store 배선 후 삭제 | **S7** |
| 파일 `graph_cache_*.json` | v1 shape decode 없이 삭제 | **S4**, Graph V2 cutover와 원자적 |
| DataStore `fxi_graph_preferences` (`selected_sources`, `dxy_visible`) | v2 series id와 달라 reset | **S4** |
| DataStore `fxi_bank_preferences` (`ordered_banks`) | UID 소유권을 증명할 수 없어 변환 이관 금지; v2 UID 기본값 생성 후 legacy 삭제 | **S1.5** |
| `news_cache.json` | 모델 호환 fixture가 통과하면 유지; 실패/손상은 기존 decode-fail→refetch. 무조건 삭제하지 않음 | **S10** 확인 |
| Android Auto Backup | 아래 명시 allowlist/exclude 계약 적용 | **S1** |

- v1 OS tray와 FCM broker queue는 로컬 DataStore migration 대상이 아니다. update/logout 시 `cancelAll()`은
  이미 보이는 tray를 best-effort로 정리하지만 아직 전달되지 않은 broker message의 회수는 보증하지 않는다
- `PackageInfo.firstInstallTime < lastUpdateTime`은 v1 backup restore와 실제 in-place upgrade를 구분하지 못하므로 migration
  provenance로 사용하지 않는다. 소유권을 증명할 수 없는 v1 bank order/visibility와 `last_bank_*`를 현재 UID에 귀속하지 않는다
- migration journal은 대상별 `detected → consumer_cutover → legacy_deleted`를 기록한다. 각 target의 새 저장소 commit과
  소비자 전환이 성공한 뒤에만 legacy를 삭제하며, crash 후 재실행은 delete/commit을 멱등하게 반복한다.
  journal·legacy cache·token·capability는 backup에서 제외해 marker만/legacy만 복원되는 조합을 만들지 않는다
  - **예외 — `fxi_bank_preferences` 이 한 target에 한해 journal 전이를 요구하지 않는다.** 소비자 전환이 이미
    끝났고(v2 row preference store), commit할 새 상태가 없으며(:814가 변환 이관을 금지하므로 "저장된 선호 없음"
    이라는 부재 자체가 v2 기본값이라 쓰기가 0), 삭제는 파일 제거라 재실행이 멱등하고 거부는 성공으로 보고되지
    않는다. `detected → consumer_cutover → legacy_deleted`가 순서 지을 것이 남아 있지 않다. `RetiredStores`가
    이 경로를 맡는다. **이 예외는 여기서 끝난다** — "변환 없이 삭제"라는 성질만으로는 예외가 되지 않는다.
    S3의 `rates`·`rates_timestamp`(:1223)도 decode/이관 없이 지우지만 그쪽은 cutover가 아직이고 :886이 성공한
    cutover 뒤 journal 기록을 명시하므로, 그 target을 포함해 다른 모든 target의 journal·cutover 계약은 그대로다
- backup **allowlist 파일**에는 UID-scoped last tab, bank/source order·visibility, free/premium graph visible+initialized,
  premium alert last-selected bank/source, 순수 UI section expansion만 둔다. 이 값들은 전용
  `BackupableUserIntentStore`(단일 별도 DataStore 파일)에 물리적으로 격리하며 UID key가 없으면 backup을 허용하지 않는다
- **exclude 파일**: Firebase/FCM token, `PendingPushTeardown`/`pushTransitionEpoch`,
  permission-prompt/push admission 상태, server premium/KRX capability, `userAccessEpoch`/`krxCapabilityEpoch`와
  `mayContain*Data`, topic/free/graph/news/settings/history cache, pending tap/sync, deletion tombstone, migration/purge journal.
  한 DataStore 안 key 선택 backup은 불가능하므로
  allowlist 값과 이 상태를 같은 `.preferences_pb` 파일에 섞지 않는다
- 동일 file allowlist를 API 31+ `data_extraction_rules.xml`의 **`cloud-backup`과 `device-transfer` 둘 다**에 명시하고,
  API ≤30 `backup_rules.xml`의 `full-backup-content`에도 동일하게 명시한다. S1 fixture는 API 30 full backup과 API 36
  cloud+D2D payload/restore에서 allowlisted 파일만 돌아오고 token·epoch·cache·journal은 생성되지 않음을 검증한다
- logout/계정 전환/계정삭제의 domain data는 `UserScopePurger`, KRX 철회는 `CapabilityScopePurger`를 사용한다(§7 S1).
  active deletion/access/push/purge control journal은 해당 finalizer 완료 전 `UserScopePurger`가 지우지 않는다
  capability purge는 KRX server-derived cache/live/settings-history response/pending tap만 지우고 source raw preference는 보존한다

### 9.2 legacy 제거 증명
- 삭제 대상: `SampleData`, `SamplePreviewViewModel`, `SampleAlertAddSheet`, `SampleAlertSection`, `SampleAlertTriggerBanner`, `SampleAlertSetting`(6) + `LockedPreviewScreen` 재작성 + `RootScreen`의 SignedOut preview 분기 + legacy rate/graph DTO·repository·VM
- **소비자 0을 먼저 만들고 그 슬라이스 안에서 삭제**한다(반쪽 삭제 금지)
- 증명: 네트워크 assertion 테스트(legacy 경로 호출 0) + 소스 스캔 trip-wire
- 별건 정리(v2 무관, 별도 커밋): repo 루트 `com/revenuecat/purchases/*.kt` tracked 사본

---

## 10. 공개 rollout 게이트 · 후속 트랙

### 10.1 공개 rollout 게이트
> **SV-1·SV-2는 Android 구현의 직렬 선행조건은 아니다.** 서버와 병렬로 진행할 수 있지만 production
> 배포·운영 검증 전에는 Android v2 공개 rollout을 시작하지 않는다. 두 gate는 iOS v2에도 공통이다.

- 허용: Android 개발, 내부 테스트, Play 비공개/폐쇄 테스트.
- 차단: 최초 production rollout.
- SV-1 완료는 subprocess를 포함한 **모든** visible-alert sender가 enforce이고 low-level ungated call이 0인 상태다.
  D30 worker=1/replica=1, nginx direct external 404 + FastAPI handler 미도달, nginx 2단 재생성 evidence,
  세 child spawn token 상속과 **D30-CANARY 단일 게이트 전 항목**도
  포함한다(별도 ‘3개 지표 0’ 사본을 만들지 않는다).
- SV-2 완료는 KRX settings/history가 SQL ORDER BY/LIMIT 전에 필터되고 G1/G2/G3 각 OFF에서 0건인 상태다.
- SV-0·deferred SV-3·SV-4는 공개 차단 조건이 아니다.
- 이유: visible notification은 background/killed에서 OS가 먼저 표시하므로 client만으로 무권한 신규 push를 막을 수 없다.
- 이미 broker가 수락한 message의 사후 회수, TTL/age tier, delivery ACK는 이번 gate에 포함하지 않는다.
  이는 양 플랫폼 공통 known risk이며 후속 문서의 사건 기반 범위다.

### 10.2 rollout 전 최종 확인 (문서 신뢰 금지, 운영 실측)
아래는 **public 기대값**이며 mismatch는 즉시 block이다.

| config / owner | public 기대값 |
| --- | --- |
| `TOPIC_DISPATCHER_ENABLED`, `FX_TOPIC_ENABLED` | `true`, `true` |
| `WS_TOPIC_AUTH_STAGE` | `enforce_authenticated_premium` (exact string; 정규화·fallback 금지) |
| `KRX_FUTURES_ENABLED`, `KRX_CLIENT_DISTRIBUTION_ENABLED` | 둘 다 `true` |
| `COMPARISON_ALERT_ENABLED`, `KRX_ALERT_EVALUATOR_ENABLED` | 둘 다 `true` |
| SV-1 alert authorization mode | `enforce`, selector=`all`; old binary/orphan sender 0 |
| `USDT_LEGACY_REST_POLLING_ENABLED` | `false` |
| USDT WS 5종 + supervisor | 전부 `true` |
| `TETHER_TOPIC_TRIGGER_MODE`, `BANK_INVESTING_TOPIC_TRIGGER_MODE` | 둘 다 `direct_coalesced` |
| visible alert transport | 3 family 모두 현 `notification+data`; `sync_alerts`만 data-only silent |
| delivery ACK / event ledger / Android delivery profile | **없음** — v2.0에서 요구하거나 green 신호로 사용 금지 |

- 배포된 server SHA·migration·환경 snapshot과 S11 evidence가 일치해야 한다.
- D30 nginx deny는 route보다 먼저 로드됐고 FastAPI 재생성 뒤 nginx가 다시 재생성됐음을 container identity로 확인한다.
  최종 host/runtime config SHA-256 일치, 외부 internal 404·handler counter 불변, `/health`·대표 API green이 모두 필요하다.
- SV-1이 bank/source/comparison의 실제 in-process·subprocess 경로에서 동작하고,
  deny 뒤 FCM·setting 전이·history·token mutation이 0인지 확인한다.
- SV-2가 KRX row를 ORDER BY/LIMIT 전에 거르고 응답 `total_count`도 필터 후 페이지 길이인지 확인한다.
- 로그아웃/free/premium/premium+KRX **4계정** read-only access matrix와 실제 FCM
  foreground/background/process-killed smoke를 수행한다.
- allow+FCM success 뒤 once/repeat/발송 히스토리가 현 iOS/server 의미와 맞는지 확인한다.
- gated sender 유지 또는 alert pause rollback rehearsal를 완료한다.


### 10.3 후속 트랙 (Android 출시 **비차단**)
- iOS 비교알림 KRX 필터 누락 — `ComparisonAlertViewModel.swift` / `ComparisonAlertSection.swift`에 `krxVisible` 참조 0건
- iOS ▲▼ cue 타이머가 latest-wins 아님 + Reduce Motion에서 `previousRate` 미갱신 (`SourceRateBarView.swift:169,185-191`)
- iOS source 목록 effective 투영이 비는 경우(저장값이 KRX 단독일 때) 처리 부재
- iOS `TetherTopicData.allEntries`가 `usd_krw_futures`를 병합(`TopicMessage.swift:71`)
- iOS Graph V2는 UI에는 `gated(response)`를 쓰지만 disk에는 raw `response`를 저장하고 revoke 시 raw cache를
  삭제·재저장하지 않아 KRX series가 남는다(`GraphV2ViewModel.swift:286-299,454-458`)
- 알림 tap의 tab/section/setting deep-link는 iOS가 현재 TODO이므로 양 플랫폼 공통 route 계약을 정할 때 함께 추가
- 알림 전달 지연·오래된 값·FCM 수락 후 미표시·계정 전환 뒤 broker 잔존 message의 후속 설계는
  `ANDROID_NOTIFICATION_RELIABILITY_FOLLOWUP.md`에서 통합 추적한다. v2.0 release gate가 아니다
- (해소됨) iOS·Android last-good 정책은 **정렬 완료** — O5 회수로 양 플랫폼 모두 클라이언트 시간 상한 없음. 실제 철회 지연·오래된 KRX 잔상 또는 `premium_pending` false로 인한 오강등 사건이 관측되면 **양 플랫폼 공통 후속**으로 연다
- 서버 `RotatingFileHandler`의 multi-process/cron writer 경합과 base-file-only admin reader를 단일 writer 또는
  rotation-aware reader로 정리. v2.0에서는 Docker log를 child evidence의 독립 보조 사본으로 쓰되 근본 해결로 표현하지 않음
- Android 전체 다국어화 / multi-module 분리
- 서버 Stage B(legacy 종료): 양 플랫폼 구버전 활성 사용자 <1% + grace(ADR-039 S4 미결)

---

## 부록 A — 본 계획이 코드로 확인한 사실 (감사용)

| 사실 | 위치 |
| --- | --- |
| 은행 알림이 크롤러 저장 경로에서 인라인 발송 | `exchange-rate/app/crud.py:686,744,660` |
| shinhan·ibk·nh·sc는 항상 subprocess | `app/scheduler.py:308`, `app/crawlers/runner.py:37-40` |
| bridge는 IPC 아님(동일 프로세스 main loop 마샬링) | `app/topic_trigger_bridge.py:31,40` |
| 발송 경로에 premium 재확인 없음 | `app/crud.py:2234`, `alert_storage_backend.py`, `comparison_evaluator.py` |
| webhook은 캐시 무효화만 — 그나마 **FastAPI 프로세스의 `_cache`에만** 미친다 | `app/webhooks.py:90` → `app/subscription.py:130-140` |
| premium 캐시는 **process-local in-memory** — subprocess는 매 실행 첫 관측이 cold이고 cross-run cache·webhook 무효화를 공유하지 않지만, 같은 runner 안 후속 호출에는 로컬 cache가 적용될 수 있음 | `app/subscription.py:42,93`, `app/crawlers/runner.py:49-85` |
| 캐시 miss + 확정 비구독은 INACTIVE가 아니라 **PENDING**으로 접힌다(회귀 잠금 있음) | `app/subscription.py:443-452`, `tests/test_subscription_clock.py:352-356` |
| typed premium 분류는 이미 존재 | `app/subscription.py:195,201,202`, `app/topic_authorization.py:179` |
| `Determined`에 `expires_at` 없음 | `app/subscription.py:150` |
| premium 캐시에 `expires_at` 없음 | `app/subscription.py:75` (`CACHE_TTL=5m`, `CACHE_STALE_TTL=1h`) |
| 현 `EntitlementCache`·`PendingCache`에는 lock/single-flight 없음 | `app/subscription.py:38-117` |
| 히스토리 LIMIT이 SQL, entitlement 필터 없음 | `app/crud.py:3590-3593` |
| KRX entitlement 게이트는 mutation 4곳뿐 | `app/main.py:4099,4222,4478,4570` |
| 향후 register-device에 gate를 넣을 경우 현재 try 내부 HTTPException은 500으로 덮일 위험 | `app/main.py:3701,3727-3729` |
| nginx 429에 Retry-After 없음 | `nginx/conf.d/default.conf:19,25,88-90,116-119` |
| Redis 연결은 FastAPI lifespan에서만 수행되고 crawler runner에는 connect/close가 없음 | `app/main.py:599-611`, `app/crawlers/runner.py:49-85` |
| Redis wrapper는 miss와 오류를 모두 `None`으로 접고 atomic lease/NX 결과/Lua CAS가 없음 | `app/cache.py:114-134,251-269` |
| 운영 Redis는 `100mb allkeys-lru`라 observation eviction이 정상적으로 가능 | `docker-compose.yml:27-44` |
| crawler subprocess timeout은 crawl 시작부터 runner 종료까지 총 45초이며 rate commit 뒤 alert auth 중에도 kill 가능 | `app/scheduler.py:301-315`, `app/crud.py:641-664` |
| 서버 pong은 JSON | `app/topic_dispatcher.py:496-497` |
| `supported_periods`에 1d 없음 | `app/graph_v2.py:32` |
| 탭 순서·초기 선택 | `ios/FXi/ContentView.swift:21-22,52`, `Models/FreeSnapshotRouting.swift:16` |
| 알림 상한은 클라 family별 30 | `ios/Utils/Constants.swift:294` + 3 VM |
| Citi = displayCases 제외, 편집은 range nil skip | `ios/Utils/Constants.swift:175`, `AlertAddSheet.swift:124-136` |
| 막대 0.5s linear, 펄스 제거 | `ios/RateBarAnimation.swift:9`, iOS `d29df48` |
| Android는 pulse·1초 animation 잔존 | `android/ui/components/RateBarView.kt:83-139` |
| source pref는 원본 보존 / Graph는 쌍 재설정 | `ios/SourcePreferenceManager.swift:47-50` vs `GraphV2ViewModel.swift:281-286` |
| Android JSON이 `coerceInputValues=true` | `android/di/NetworkModule.kt:31-33` |
| Android topic 계층에 dxy 없음 | `android/data/remote/TopicFrameDecoder.kt` |
| Android 등록 실패 시 재시도 없음 | `android/service/PushNotificationManager.kt:71-77` |
| Android visible FCM helper 3곳은 notification block을 쓰고 TTL 없음 | `app/notifications/fcm.py:258-283,429-452,552-575` |
| Android v1.2.2 data handler는 `rate_alert`·`sync_alerts`만 지원 | `android/service/FXiMessagingService.kt:37-57` |
| Android SignedOut에서 preview 진입 가능 | `android/ui/screen/RootScreen.kt:133-144` |
| `user_devices`에 client version·delivery profile 컬럼이 없음 — v2.0은 새 profile routing을 도입하지 않음 | `app/models.py:73-80`, `app/schemas.py:85-88`, `app/crud.py:1748-1753` |
| 알림 fanout 기기 조회가 **platform 무필터**(user_id 단독) — iOS발 source/comparison·KRX가 같은 UID의 Android token으로 나가는 유일한 경로 | `app/crud.py:2270-2271,3270-3271`, `alert_storage_backend.py:88-89,275-276`, `comparison_evaluator.py:263-264` |
| 현 source evaluator의 send/persist 끝단은 observation 시각을 전달하지 않음 | `app/notifications/alert_evaluator.py:756-771` |
| 현 comparison은 Redis miss/stale에서 마지막 DB 값을 fallback하고 stale gate가 없음 | `app/notifications/comparison_evaluator.py:141-193` |
| 현 source registry도 timestamp가 수집 성공이 아니라 값 변경 시각이라고 명시 | `app/source_registry.py:21-26` |
| 현 bank/source once 전이와 **사용자 가시 success history**는 FCM success 뒤에만 기록(source all-fail 운영 row는 기본 GET에서 숨김) | `app/crud.py:2551-2567,3676-3720` |
| 현 iOS history는 required `sentAt`과 “발송된 알림” 의미 | `ios/FXi/Models/AlertHistoryItem.swift:22`, `SourceAlertHistoryItem.swift:23`, `ComparisonAlertSetting.swift:193`; `AlertHistorySheet.swift:48`, `SourceAlertHistorySheet.swift:47` |
| 현 entitlements response에는 client deadline 보정용 `server_now_ms`가 없음 | `app/schemas.py:379-387`, `app/main.py:4617-4650` |
