# 알림 전달 신뢰성 후속 검토

```
Status                 : DEFERRED — Android v2.0.0 범위 아님
Release gate           : NO
Implementation         : NOT STARTED
Commit / push / deploy : NONE
작성                    : 2026-08-30
```

이 문서는 `ANDROID_V2_PLAN.md` 초안에서 **O2**·O6·O7·D25·D28·D29·SV-3으로 검토했던
알림 전달 신뢰성 설계를 보존한다. **구현 지시나 사전 요구사항이 아니다.** 실제 지연·오래된 값·
미표시 사건이 확인되기 전에는 Android v2.0.0에 소급하지 않는다.

## 1. v2.0에서 내린 결정

Android v2.0은 복잡한 새 delivery state machine을 만들지 않고 iOS와 현재 서버의 의미를 따른다.

- `rate_alert`·`source_rate_alert`·`comparison_alert`: `notification+data` visible push
- `sync_alerts`: data-only silent push
- foreground: 앱이 표시와 local state update를 담당
- background/OS-managed delivery: 앱이 force-stop되지 않았고 알림 권한·channel·device 조건이 맞으면 OS가 visible notification을 표시
- 서버 FCM `success_count > 0`: 현 비원자적 commit 순서로 once 소진·repeat 시각 갱신·성공 발송 history 생성을 시도
- history 의미: 단말 수신 또는 조건-event가 아닌 **발송 히스토리**
- client delivery ACK, event/delivery ID, age tier, 자동 재평가, local nudge 없음

예외는 접근 제어다. 다음 두 항목은 지연 신뢰성 개선이 아니라 원래 제품 불변식이므로 v2 공개 전 필요하다.

- SV-1: 모든 sender의 send-time premium/KRX 권한 gate
- SV-2: KRX settings/history의 filter-before-limit

## 2. 현재 수용하는 한계

아래는 숨겨진 보증이 아니라 **의도적으로 수용한 known limitation**이다.

1. visible FCM/APNs 메시지에 명시적 TTL/expiration이 없어 broker 지연이 가능하다. visible Android 설정은
   `exchange-rate/app/notifications/fcm.py:277-284,446-453,569-576`이고 `ttl`이 없으며, APNs alert 설정
   `:265-275,435-445,558-568`에도 expiration header가 없다. `:692`는 visible이 아니라 `sync_alerts` data-only 설정이다.
   Android FCM은
   TTL 미지정 시 [최대 4주 보관을 시도할 수 있다](https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan).
   APNs의 expiration 의미는 별도로 다룬다.
2. FCM 수락은 단말 표시가 아니다. 그런데도 현재 once 소진과 발송 history의 기준이다
   (`app/crud.py:2551-2553`, `:3676-3677`, `app/notifications/comparison_evaluator.py:433-447`).
3. device가 없거나 모든 send가 실패하면 setting은 전이하지 않는다. 은행은 실패 history를 만들지 않고,
   source·comparison은 `success=false` 운영 row를 남기지만 기본 사용자 GET은 숨긴다. 별도 delivery retry job도 없다.
4. 앱은 background visible push를 표시 전에 검사할 수 없다. FCM notification message는 background에서
   system tray로 전달되고 data는 launcher intent에서 처리된다
   ([Firebase message handling](https://firebase.google.com/docs/cloud-messaging/android/receive#handling_messages)). 따라서
   `android/app/src/main/java/com/jay/fxi/service/FXiMessagingService.kt:37-57`의 type 분기를 사전 admission으로 쓸 수 없다.
   구독·KRX 철회 전에 broker가 이미
   수락한 메시지는 철회 관측 뒤에도 표시될 수 있다.
5. comparison evaluator는 Redis 실패 시 별도 input-age gate 없이 DB latest를 fallback할 수 있다.
6. 서버 fanout은 UID 중심(발송 대상 기기 조회에 platform 필터 없음 — `exchange-rate/app/crud.py:2270-2271,3270-3271`,
   `exchange-rate/app/notifications/alert_storage_backend.py:88-89,275-276`,
   `exchange-rate/app/notifications/comparison_evaluator.py:263-264`)이므로 Android 구버전은 새 source/comparison family를 받을 수 있지만 foreground
   처리는 제한될 수 있다.
7. background OS 표시에서는 local setting state가 즉시 갱신되지 않는다. 다음 정상 GET 전에는 화면 상태가
   잠시 오래될 수 있다.
8. Android [FCM notification message는 collapsible](https://firebase.google.com/docs/cloud-messaging/customize-messages/collapsible-message-types)이므로
   broker 대기 중 이전 알림이 더 새 알림으로 대체될 수 있다.
   APNs의 저장·대체 의미는 별도이며 Android 규칙을 그대로 대입하지 않는다.
9. FCM accept → setting mark commit → success history commit은 원자적이지 않다. 중간 crash는 재발송 또는
   once 소진 뒤 사용자 가시 history 누락을 만들 수 있다.
10. payload에 recipient/access generation binding이 없어 계정 전환 뒤 이미 수락된 message를 앱이 확정적으로 귀속할 수 없다.

## 3. 지금 가능한 관측과 불가능한 주장

현재 코드가 계산하거나 일부 남기는 값:

- sender별 aggregate FCM success/failure count와 failed/invalid token
- source·comparison의 `success=false` 운영 row(기본 사용자 history에는 비노출)

SV-1에서 새로 추가할 접근 제어 관측:

- family·sender path별 authorization allow/deny/UNKNOWN과 ungated attempt
- enabled setting이 있으나 등록 device가 없는 UID의 baseline

현재는 일관되게 보존되지 않아 별도 계측 없이는 주장하지 않을 값:

- FCM RPC 시작 시각·end-to-end latency
- 개별 Firebase message id와 setting/rate snapshot의 안정적인 correlation

client ACK가 없으므로 다음은 측정했다고 주장하지 않는다.

- 알림이 실제 tray에 표시된 시각
- 사용자가 알림을 보았는지 여부
- FCM accepted → device displayed 성공률
- per-device 정확히 한 번 표시

사건 조사에서는 사용자 신고 시각·알림 화면의 시각·남아 있는 서버 평가/FCM 로그·당시 환율 snapshot을
**best-effort로** 수집한다. stable event/delivery ID가 없으므로 서로의 결정적 상관관계를 보증하지 않는다.
관측을 추가하기 전까지 `FCM accepted`를 `delivered`나 `shown`으로 이름 붙이지 않는다.

> **이 문서에 도달하는 경로.** 사건 조사는 대개 서버 리포에서 시작된다. 현재 인바운드 링크는
> `android/ANDROID_V2_PLAN.md` 한 곳뿐이므로, SV-1 구현이 land할 때 서버 측 알림 코드 주석 또는
> `exchange-rate/DECISIONS.md`에서 이 문서를 역참조하도록 함께 남긴다(그 전에는 검색으로만 도달 가능).

## 4. 재검토를 여는 trigger

다음 중 하나가 확인되면 이 문서를 새 ADR/구현 계획으로 승격한다.

- 오래된 환율이 담긴 알림이 실제 표시되어 사용자가 잘못된 현재가로 오인한 사건
- 조건은 충족했고 FCM은 수락했지만 알림이 표시되지 않은 사례가 반복 확인됨
- once 설정이 서버에서 소진됐지만 사용자가 받지 못했다는 재현 가능한 사례
- RevenueCat/provider 불확정으로 무권한 발송 또는 조건 충족 알림의 반복·대규모 누락이 확인된 사례
- 로그아웃·계정 전환·구독/KRX 철회 뒤 이미 수락된 알림이 표시된 사건
- controlled device test에서 특정 OS/OEM/Doze 상태의 지연·누락이 반복됨
- 고객지원·운영 지표상 현 계약을 유지하는 비용이 새 상태기계 비용보다 커짐

KRX/account-crossing은 보안·접근 사건으로 즉시 triage한다. 그 밖의 한 번뿐인 모호한 신고는 로그와
재현 조건을 먼저 확보하고, 임계값은 실제 baseline을 본 뒤 제품 결정으로 동결한다.

## 5. 후속 후보 — 작은 변경부터 평가

각 후보는 독립적으로 비용과 누락 위험을 평가한다. 처음부터 전체 묶음을 구현하지 않는다.

### A. broker 수명 제한

- Android TTL과 APNs expiration 추가
- 장점: 아주 늦은 visible push 감소
- 대가: FCM 수락 뒤 TTL 만료 시 once는 이미 소진됐지만 사용자에게는 조용히 사라질 수 있음
- 선행 결정: once 소진 기준을 그대로 둘지 함께 바꿀지

### B. 진단용 시각과 foreground age fence

- payload에 평가/발송 시각을 넣고 foreground local renderer에서만 오래된 메시지를 제한
- 장점: 비교적 작은 변경, 지연 원인 진단 개선
- 한계: background/killed OS-rendered notification은 차단하지 못함

### C. data-only + local renderer

- 앱이 표시 전 current account/premium/KRX와 message age를 검사한다. 필요하면 recipient hash·access generation
  binding, 암호화 quarantine, bounded WorkManager handoff/retry를 별도 계약으로 설계한다.
- 장점: client admission 통제 가능
- 대가: force-stop·Doze·OEM·WorkManager 지연에서 누락 증가, iOS와의 동작 차이, migration 필요.
  data-only는 OS의 자동 visible 표시를 없앨 뿐 broker queue 자체를 없애지는 않는다.

### D. condition event와 delivery 분리

- durable `alert_event`, device별 delivery/attempt, stable ID, client handoff ACK
- once/repeat 소진을 FCM 수락이 아니라 합의된 presentation 기준으로 이동
- 대가: server schema·outbox·idempotency·cross-device·crash recovery가 모두 새 제품 계약이 됨

### E. 입력 신선도 gate

- source별 `seen_at/valid_until`, comparison 양 leg age/skew, stale DB fallback 차단
- 장점: 새로 만든 알림 자체의 값 신뢰도를 높임
- 대가: 수집 장애 때 실제 조건 충족 알림이 누락될 수 있어 source별 정책·운영 경보가 필요

### F. missed-delivery 복구 UX

- 한 번의 fresh recheck, history-only 복구, 무음 요약 또는 in-app nudge 후보
- 대가: 중복 알림·quiet interval·setting 소진·사용자 편집 경쟁을 새로 정의해야 함

### G. transport migration과 queue drain

- Android notification+data → data-only 전환 시 구 client profile 분리와 기존 FCM broker queue 처리를 설계한다.
- iOS/APNs 전환은 APNs expiration·collapse·background delivery 조건을 별도 계약으로 다루고 Android time gate를 복제하지 않는다.
- 공식 최대 보관기간을 기다리는 time gate는 **실제 transport 전환을 결정했을 때만** 검토한다.
- v2.0에는 28일 drain이나 client-version delivery profile을 넣지 않음

### H. premium provider 장애 중 서버 발송 정책

- v2.0은 별도 positive-stale horizon을 추가하지 않고 본 계획 SV-1처럼 현재 서버
  `verify_premium_status`의 availability-first 의미를 재사용한다. 이 항목은 broker TTL/ACK와 달리
  send-time 접근 gate가 반드시 정해야 하는 장애 정책이며, 여기서는 **선택하지 않은 강화 후보와 대가**를 보존한다.
- **회수된 후보값(O2)**: 관측 시작부터 총 15분(fresh 5분 + transient-only stale 10분), `provider expires_at`으로 cap.
  후보값은 `app/topic_lease.py:82 LEASE_MAX_SECONDS=900`에서 가져왔지만, topic lease 상한은 클라이언트 표시 TTL이나
  현재 v2 제품 결정의 근거가 아니다.
  단 현재 `Determined`는 `is_premium`만 가지므로(`app/subscription.py:143-150`) `expires_at` cap을 쓰려면
  typed provider model 확장이 먼저 필요하다.
- ⚠️ **재사용의 실제 수치**: `CACHE_TTL=5분`, `CACHE_STALE_TTL=1시간`(`app/subscription.py:33-34`)은
  합산 65분이 아니다. 마지막 authoritative cache 저장 시점부터 age `<5분`은 fresh이고, 그 뒤 provider가
  불확정일 때만 **총 age `<1시간`**까지 stale 값을 fallback한다(`:56-69,404-465`). provider가
  `Determined(false)`를 반환하거나 webhook 무효화가 해당 프로세스에 도달하면 즉시 내려가므로
  “구독 만료 후 최대 1시간”을 보증하지 않는다.
- 현 sender는 아직 premium gate가 없어 pre-SV-1 발송 상한이 사실상 없다. SV-1/D30 뒤에는 단일 FastAPI owner의
  기존 조건부 fallback을 crawler subprocess도 authenticated loopback IPC로 소비한다. 다만 cold batch/provider 지연 또는
  child runtime admission fence에서 deny된 뒤 환율이 그대로면 그 crossing은 영구 누락될 수 있다. D30은 auth 추가 지연을
  제한하고 이를 canary로 드러낼 뿐 delivery retry를 만들지 않는다.
- Redis shared observation은 worker/replica scale-out 또는 D30 canary budget 위반 때의 후속 후보다. 현 Redis wrapper는
  subprocess에서 connect되지 않고 miss와 장애를 구분하지 못하며 `SET NX`/Lua CAS가 없다. 채택 시 별도 서버 ADR에서
  non-sliding `observed_at`, token+generation fenced write/release, webhook 경합, runner lifecycle, Redis eviction/down
  fallback을 먼저 동결한다. Redis 장애 시 전역 single-flight가 사라지는 한계도 남는다.
- 실제 provider 장애가 무권한 발송 또는 대규모 알림 누락을 만들면 transient positive stale 상한,
  UNKNOWN 중 조건 관측 보존, 회복 뒤 재평가 여부를 함께 결정한다.
- KRX G1/G2/G3 각 축은 외부 provider와 무관하므로 이 후보와 상관없이 fail-closed를 유지한다. 다만 KRX 최종식의
  premium 결합항은 availability-first라 provider 불확정+webhook 미반영 중 total cache age `<1시간`의 stale ACTIVE가
  KRX send/read를 잠시 허용할 수 있다. 이를 G1/G2/G3의 stale-free 보증과 혼동하지 않는다.

## 6. 재개 전에 답할 제품 질문

1. 알림의 성공은 조건 확인, FCM 수락, tray 표시, 사용자 열람 중 무엇인가?
2. source별로 몇 초 지난 값까지 사용자에게 보여도 되는가?
3. 오래된 알림을 버릴 때 once 설정을 소진할 것인가, 다시 평가할 것인가?
4. 누락과 중복 중 어느 쪽이 더 큰 사용자 피해인가?
5. history는 발송 기록, 조건 충족 기록, 단말 표시 기록 중 무엇인가?
6. iOS와 Android를 동시에 바꿀 것인가, 플랫폼별 차이를 허용할 것인가?
7. KRX의 queue-level 절대 무흔적을 요구한다면 data-only 전환 비용을 수용할 것인가?

이 질문과 실제 incident evidence가 모이기 전에는 former O2/O6/O7의 추가 hardening을 v2.0 요구로 되살리지 않는다.
