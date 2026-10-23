package com.jay.fxi.data.entitlements

/**
 * 송신 시점에 한 스냅숏에서 같이 읽은 KRX capability epoch와 permitSeq(D4 3.3).
 * 값을 채우는 송신 경로는 S6-2b-1b다. 예약에 표지가 없으면(null) 처리 시점 permitSeq로 fail-closed 판정한다.
 */
internal data class KrxSendMarker(val krxCapabilityEpoch: String?, val permitSeq: Long)

/** S6-2b-1a 관측용: 막힌 신선한 true의 시작 순번과 막던 예약 순번들(D4 3.2 withheld). */
internal data class KrxWithheld(val fromOrder: Long, val blockingOrders: List<Long>)

/** S6-2b-1a 관측용: 승인 닫힘으로 인계된 허가의 같은 키 재개 FE 의무(D4 3.2 reopenOwed). */
internal data class KrxReopenOwed(val key: KrxContestKey, val permitSeq: Long)
