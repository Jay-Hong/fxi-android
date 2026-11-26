# S9-5 결정 기록 r1 (사용자가 Claude·Codex 끝장토론에 위임)

기록 2026-11-26 20:54 KST(date 확인).

## 1. 사용자 위임(원문)

- 결정서: `S9/s9_5_user_decision_sheet.r2.md`(sha256 `dd02e070cec40a5b85f68074cdef8d43c25766542654e6572ca15a09dbdd2164`), 선택지 A/B/C
- 질문 근거: S9-0 합의(`S9/s9_0_scope.agreed.record.r1.md`, sha256 `c2c4030998b1c8ee0ddbedff00763df77e7a0528b873e17afdcb4b3e7f3533e6`)의 "보호 파일 변경은 변경 전에 최소 diff를 사용자에게 올린다"
- 2026-11-26 Claude 보고는 "결정이 필요한 것"으로 이 결정서의 선택과 옛 시험 결과 XML의 hostname 처리 두 건을 올렸다. 사용자 답(원문 그대로):

> 결정이 필요한 사항은 코덱스와 끝장 토론을 통해 결정해줘. 곁가지는 최소화 하고 우리의 목표를 최대한 빠르고 정확하게 이룰 수 있도록. 아이폰 버전은 이미 출시 되었지만 동급의 안드로이드 버전이 일정이 많이 뒤쳐져 있어. iOS의 주요 기능과 UI는 최대한 동등하게 가져가되, 사용자가 알기 어려운 아주 세부적인 부분은 공개 후 다듬어도 좋겠어.

- 위임 기록: `direction_1126/user_decision_fast_parity.r1.md`(sha256 `e4b11ba2efdd79ea138bd631cad1ea9fdccf24f98cd61fe18f10d43063649574`)
- 위임 범위: 이 결정서의 선택(A/B/C)과 그 보호 바이트다. 다른 보호 경로 변경으로 위임을 넓히지 않는다.

## 2. 두 판정

- 위임 전 독립 판정(대상은 결정서 r1이고, r2는 Codex 판정의 정정 표를 반영한 판이다)
  - Claude: `S9/s9_5_decision.claude.r1.md`(sha256 `6b51f282bb8b61ece85080f2095ef1897488083378ebf9dd2fd2396faa85eee1`) — **A**
  - Codex: `S9/s9_5_decision.codex.r1.md`(sha256 `49acbbee4cca79abd46088806fb42d35615ddc714788a0f52202c19cdf9861ef`) — **S9_5_DECISION_CODEX_R1: A**
- 위임 뒤 토론
  - Codex: `direction_1126/debate.codex.r1.md`(sha256 `8c490d7de4e74a0ab3538dc82b76b6ced155d364148878a213a4ffce2469dae8`) T2 — **AGREE, 결정서 r2의 A를 확정한다. 이월할 부분은 없다.**
  - 합의 기록: `direction_1126/agreed.record.r1.md`(sha256 `41d39e45af01d40f197b6595894680a54d08d5b9defc9b88d4b6a865eafa2ca7`)
- 근거 기록

| 기록 | sha256 |
|---|---|
| `S9/s9_5_scope.codex.r3.md` | 4a922cddfcad7735223371a2423586f4a98b1eabe27ab5796d9526d1a2c7f6c2 |
| `S9/s9_5_scope_review.wf1.findings.md` | d4e14a4fde7786177e367b970b744488679930da7b61641e0634849aa277bf6d |
| `S9/s9_5_scope_r2_check.wf1.findings.md` | 8f430bcba21a75b89002df57f789a2e3cfeb0a7a91197b4acc87695ceaac4df4 |

## 3. 결정: A

- 기준 parent는 bf98a3c7ecd6db981885f73632d222eadb75e565(S9-4c land)다.
- 승인 바이트(범위 r3 §3·§8의 hunk를 각 old가 정확히 한 번 일치하는 순서로 적용)

| 운영 파일(`app/src/main/java/com/jay/fxi/`) | 승인 범위 | from SHA-256 | 승인 to SHA-256 | KRX 행(from = to) |
|---|---|---|---|---|
| `service/FXiMessagingService.kt` | F1·F2. CoreException | 3ab46e30fff43339bd09c4a5cde8fc0c0d429cfde93065d9fa086ac07a0a575a | 12c554dfd36ef3daf44886f5db88113b18d0bd207d21a45084471720d148c058 | 08e142a551e0e47752a09853de243aa65b727b5863c75171d1cb7157ece13d02 |
| `MainActivity.kt` | M1. CoreException | 3328908334f4388686891812483df4fe7ba3d043aa7b6f969850c6d670230de5 | 17bcaee7748430831b3d4f9e3962bb131444d2c56f7831b80d9117cfa16fb9fb | e61ab21d19206fde24d0b2a8d74e0e75def7397c1ac1d3be33f8514b07b725aa |
| `data/alerts/AlertFamilyRefresherRegistry.kt` | 일반 changed | 725efe217ce9592a34dff062aee6ee6861d06cb5cff4451b03727798ba73d41e | 78e003910ff65d0428a8a6ab511f4affcb722432bd2493ffe141a2500f2f9a18 | 해당 없음 |
| `data/alerts/ComparisonAlertSettingsOwner.kt` | 일반 changed | ad409a548f678d3eca93bf250f49a6b3ae8831d25e22fa224bf619cc5c560d31 | 84ef50acffc7f83ed7198a64add70c24324736bb6d393c72dbb4cc951d1f9eb5 | 해당 없음 |

- O04s 검사기(예외 허용 기준 확장, 이 결정에 포함)
  - 아홉 span 가운데 parser 하나만 잇는다: 전임 78aa21b1a2c6a6dfc220cca0d32fbad2b4c6ef344ac261c790834be2dfc1a4d3(1370 B) → 후임 89266e5fea2e4994d71b86ce7735e8193837ec486779b1ef5687bc87d7796c9b(1644 B).
  - `PostS6ApprovedDelta.kt`의 parser 허용 기준과 `S6Krx3cOffContractTest.kt`의 공백 한 칸 변조 기대 결과("전임 불일치")를 범위 r3 §4대로 옮긴다. 나머지 여덟 span은 기존 규칙이다.
- 남는 위험: 결정서 r2 "A의 제약" 여섯 항목 그대로다.
  - 확인과 표시 사이의 틈
  - 계정 전환 직후 도착한 알림
  - 배경·종료 상태의 OS 표시(서버 SV-1 몫)
  - 누르면 앱만 열림
  - 권한이 바뀌는 순간의 누르기
  - KRX 스위치를 켜도 같은 기준

## 4. 조건

- 실제 S9-4c land에서 네 from 해시를 다시 비교한다. 적용한 네 to 해시와 두 KRX 행 해시가 위 표와 같아야 한다. 다르면 멈추고 재합의한다.
- 대장 단위 `S9-5`와 두 CoreException의 `approval_record_sha256`은 S9-0 합의 SHA(`c2c4030998b1c8ee0ddbedff00763df77e7a0528b873e17afdcb4b3e7f3533e6`)다.
- 이 기록의 raw 사본을 시험 자료 `krx/post_s6/s95_protected_decision.md`로 보존하고, 그 SHA-256을 별도 pin으로 검사한다(범위 r3 §4 "두 승인 기록의 결속").
- 시험·변이·동결·대장은 범위 r3 §4–§5를 따른다. 변이는 JVM에서 관측한다. 실기기 수신·여정은 S9-7과 §10.2 몫이다.
- commit·push는 각각 exact-state 합의로 진행한다.
- 범위 밖: KRX 스위치 ON의 custody, 달러선물 조합 허용, 서버 SV-1/2, 실기기·실제 FCM 수신 확인
