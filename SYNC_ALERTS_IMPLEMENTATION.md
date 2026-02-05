# 다중 기기 알림 동기화 구현 가이드

> **목적**: A 기기에서 알림 설정 변경 시 B 기기에서 동기화
> **스코프**: MVP (서버 + Android + iOS)
> **상태**: ✅ MVP 완료
> **동기화 정책**: Eventual Consistency (best-effort push + 포그라운드 복귀 시 동기화)
> **삭제 시점**: 구현 완료 및 검증 후

---

## 1. 기능 개요

```
A 기기: 알림 추가/수정/삭제 API 호출
    ↓
서버: CRUD 성공 → 해당 user_id의 모든 device_token에 사일런트 푸시 (best-effort)
    ↓
B 기기:
  - Android: FCM 수신 시 즉시 새로고침
  - iOS: 백그라운드에서 수신 → 플래그 저장 → 포그라운드 복귀 시 동기화
         (포그라운드 실시간 동기화는 플랫폼 제한으로 미보장)
```

**핵심 원칙:**
- data-only 푸시 (사용자 알림 배너 표시 안됨)
- CRUD 실패 전파 방지 (동기화 실패해도 CRUD 응답 성공)
- **Eventual Consistency**: 실시간 동기화 미보장, 포그라운드 복귀 시 동기화 보장
- MVP에서는 자기 기기 제외(exclude) 로직 생략

---

## 2. 구현 상태

| 플랫폼 | 상태 | 파일 |
|--------|------|------|
| **Android** | ✅ 완료 | `FXiMessagingService.kt` |
| **서버 (fcm.py)** | ✅ 완료 | `send_fcm_data_only()` 추가 |
| **서버 (main.py)** | ✅ 완료 | 헬퍼 + 3개 endpoint 수정 |
| **iOS** | ✅ 완료 | `PushNotificationService.swift`, `AppDelegate.swift` |

---

## 3. Android 구현 (완료)

### FXiMessagingService.kt

```kotlin
when (message.data["type"]) {
    "rate_alert" -> {
        // 환율 알림: UI 업데이트 + 사용자 알림 표시
        val settingId = message.data["setting_id"]?.toIntOrNull()
        if (settingId != null) {
            alertEventBus.emit(AlertEvent.SettingTriggered(settingId))
        } else {
            alertEventBus.emit(AlertEvent.RefreshNeeded)
        }
        showRateAlertNotification(message)
    }
    "sync_alerts" -> {
        // 다중 기기 동기화: 사일런트 새로고침 (알림 표시 없음)
        Log.d(TAG, "Sync alerts requested from another device")
        alertEventBus.emit(AlertEvent.RefreshNeeded)
    }
}
```

**핵심:**
- `sync_alerts` 타입일 때 `RefreshNeeded`만 emit
- `showRateAlertNotification()` 호출 안함 (사일런트)

---

## 4. 서버 구현 (완료)

### 4.1 fcm.py - data-only 발송 함수 추가

```python
async def send_fcm_data_only(
    tokens: list[str],
    data: dict
) -> dict:
    """
    data-only FCM 전송 (사일런트 동기화용)
    - notification 페이로드 없음 (사용자 알림 배너 표시 안됨)
    """
    if not _firebase_initialized:
        if not init_firebase():
            return {"success_count": 0, "failure_count": len(tokens), "failed_tokens": []}

    if not tokens:
        return {"success_count": 0, "failure_count": 0, "failed_tokens": []}

    normalized_data = {str(k): str(v) for k, v in data.items()}

    message = messaging.MulticastMessage(
        # ❌ notification 없음 (사일런트)
        data=normalized_data,
        tokens=tokens,
        # iOS: 사일런트 백그라운드 푸시
        apns=messaging.APNSConfig(
            headers={
                "apns-priority": "5",
                "apns-push-type": "background"
            },
            payload=messaging.APNSPayload(
                aps=messaging.Aps(content_available=True)
            )
        ),
        # Android: priority만 설정
        android=messaging.AndroidConfig(priority="high")
    )

    try:
        loop = asyncio.get_running_loop()  # Python 3.10+ 권장
        response = await loop.run_in_executor(
            None, messaging.send_each_for_multicast, message
        )

        failed_tokens = []
        for idx, result in enumerate(response.responses):
            if not result.success:
                error_code = (
                    result.exception.code
                    if hasattr(result.exception, 'code')
                    else "UNKNOWN"
                )
                if error_code in ['UNREGISTERED', 'INVALID_ARGUMENT', 'NOT_FOUND']:
                    failed_tokens.append(tokens[idx])

        logger.info(
            "FCM data-only 전송 완료",
            extra={
                "event": "fcm_data_only",
                "total": len(tokens),
                "success": response.success_count,
            }
        )

        return {
            "success_count": response.success_count,
            "failure_count": response.failure_count,
            "failed_tokens": failed_tokens
        }

    except Exception:
        logger.warning("FCM data-only 전송 실패", exc_info=True)
        return {"success_count": 0, "failure_count": len(tokens), "failed_tokens": []}
```

### 4.2 main.py - import 수정

```python
# 기존 import 줄에 send_fcm_data_only 추가
# Before:
from app.notifications.fcm import init_firebase, is_firebase_initialized

# After:
from app.notifications.fcm import init_firebase, is_firebase_initialized, send_fcm_data_only
```

### 4.3 main.py - 동기화 헬퍼 함수 추가

**위치**: main.py 상단 (엔드포인트 정의 전, 헬퍼는 crud.py가 아닌 main.py에 위치)

```python
async def notify_user_devices_sync(db: Session, user_id: str):
    """
    해당 사용자의 모든 기기에 sync_alerts 발송 (best-effort)
    - CRUD 성공 후 호출 (응답 전에 실행됨)
    - 실패해도 HTTP 응답에 영향 없음
    """
    try:
        devices = crud.get_devices_by_user(db, user_id)
        tokens = [d.device_token for d in devices]

        if not tokens:
            return

        result = await send_fcm_data_only(
            tokens=tokens,
            data={"type": "sync_alerts"}
        )

        # 무효 토큰 일괄 삭제 (batch)
        if result["failed_tokens"]:
            db.query(models.UserDevice).filter(
                models.UserDevice.device_token.in_(result["failed_tokens"])
            ).delete(synchronize_session=False)
            db.commit()

        logger.info(
            "동기화 푸시 발송",
            extra={
                "event": "sync_alerts_sent",
                "user_id": user_id,
                "success": result["success_count"],
                "total": len(tokens)
            }
        )

    except Exception:
        logger.warning("동기화 푸시 실패 (무시됨)", exc_info=True)
```

### 4.4 main.py - 3개 엔드포인트 수정

**⚠️ 중요: finally 패턴 금지 - CRUD 성공 직후에만 호출**

#### POST /api/notification-settings (~line 1253)

```python
        logger.info("🔔 알림 설정 생성", ...)

        # CRUD 성공 후 best-effort 동기화 (헬퍼 내부에서 예외 처리됨)
        await notify_user_devices_sync(db, user_id)

        return build_notification_setting_response(setting)

    except Exception as e:
        logger.error("알림 설정 생성 실패", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
```

#### PUT /api/notification-settings/{setting_id} (~line 1343)

```python
    logger.info("🔔 알림 설정 수정", ...)

    # CRUD 성공 후 best-effort 동기화 (헬퍼 내부에서 예외 처리됨)
    await notify_user_devices_sync(db, user_id)

    return build_notification_setting_response(updated)
```

#### DELETE /api/notification-settings/{setting_id} (~line 1374)

```python
    logger.info("🔔 알림 설정 삭제", ...)

    # CRUD 성공 후 best-effort 동기화 (헬퍼 내부에서 예외 처리됨)
    await notify_user_devices_sync(db, user_id)

    return schemas.DeleteResponse(...)
```

---

## 5. 플랫폼별 동작

### Android: 실시간 동기화 ✅

- data-only 푸시 수신 시 `onMessageReceived()` 즉시 호출
- 포그라운드/백그라운드 모두 정상 동작

### iOS: Eventual Consistency (포그라운드 복귀 시 동기화) ⚠️

> **플랫폼 제한**: iOS는 data-only 푸시(`apns-push-type: background`)를 포그라운드에서 `didReceiveRemoteNotification`으로 전달하지 않는 경우가 있음.
> 백그라운드에서는 수신되나, 포그라운드 실시간 동기화는 **미보장**.

**iOS 동기화 전략:**

1. **백그라운드 수신 시**: `pendingSyncAlerts` 플래그 설정
2. **포그라운드 복귀 시**: 플래그 확인 → `loadSettings()` 호출 (30초 디바운스 우회)
3. **화면 진입 시**: `loadSettingsIfNeeded()` 호출

**구현 위치:**
- `AppDelegate.swift`: `didReceiveRemoteNotification`에서 플래그 설정
- `FXiApp.swift`: `willEnterForegroundNotification`에서 플래그 확인 후 동기화

### ⚠️ 응답 지연

> `await notify_user_devices_sync()`는 FCM 발송(네트워크 I/O)을 포함하므로 CRUD 응답 시간이 **수십~수백 ms 증가**할 수 있음.
> MVP에서는 허용 가능한 수준이나, 추후 필요 시 백그라운드 태스크로 분리 검토.

### ❌ 금지 패턴

| 패턴 | 이유 |
|------|------|
| `finally`에서 동기화 호출 | 실패/예외 시에도 푸시 발송됨 |
| `send_fcm_multicast_sync()` 재사용 | notification payload 포함됨 (사일런트 아님) |
| `sync_alerts`에 notification 필드 | **절대 금지** - 배너 표시되면 MVP 실패 |
| `crud.delete_devices_by_token()` 루프 | 호출마다 commit (비효율) |
| `except Exception: pass` | 최소 warning 로그 남겨야 디버깅 가능 |

### ✅ 필수 사항

| 항목 | 상세 |
|------|------|
| data-only 메시지 | notification/apns alert/android notification 필드 없음 |
| iOS 헤더 | `apns-push-type: background` + `apns-priority: 5` |
| 실패 격리 | try/except로 감싸서 warning 로그만 |
| batch 삭제 | 무효 토큰 일괄 삭제 (1회 commit) |

---

## 6. 검증 기준 (MVP 완료 조건)

| # | 기준 | 확인 방법 |
|---|------|----------|
| 1 | Android: A기기 CRUD → B기기 즉시 반영 | 1~3초 내 목록 갱신 |
| 2 | iOS: A기기 CRUD → B기기 포그라운드 복귀 시 반영 | 백그라운드 다녀온 후 동기화 |
| 3 | 동기화 푸시 실패해도 API는 200/정상 응답 | FCM 오류 시에도 CRUD 성공 |
| 4 | 동기화용 사용자 배너 0건 | 알림 센터에 sync_alerts 관련 배너 없음 |

---

## 7. 향후 개선 (MVP 이후)

| 항목 | 설명 |
|------|------|
| 자기 기기 제외 | X-Device-Token 헤더로 요청 기기 제외 |
| ✅ 새로고침 버튼 | 알림 섹션에 수동 새로고침 액션 추가 완료 |
| 버전 체크 API | 전체 리스트 대신 변경 여부만 확인 (서버 부하 절감) |

---

**작성일**: 2026-02-05
**삭제 예정**: 서버 구현 완료 및 검증 후
