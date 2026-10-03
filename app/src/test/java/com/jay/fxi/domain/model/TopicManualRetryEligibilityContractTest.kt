package com.jay.fxi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Claude-owned R4-c C2+b-2a contract (R4c/C2b/b2_design_codex.r3.md §4, agreed): who a user may retry by hand is the canonical
 * model's answer, the same as iOS a36682f — failed authentication first, then a disabled topic unless the server asked for a
 * delay, and separately a degraded topic without a rejection. The session intersects this with a tab's topics; this file locks
 * the model side over every combination. The implementation reads but does not edit this file.
 */
class TopicManualRetryEligibilityContractTest {
    @Test fun `B2-01 the canonical model decides who can be retried by hand, over every combination`() {
        val failures = listOf<TopicWholeRequestFailure?>(
            null,
            TopicWholeRequestFailure.TemporarilyUnavailable(5L),
            TopicWholeRequestFailure.InvalidRequest,
            TopicWholeRequestFailure.InvalidToken
        )
        val rejections = listOf<TopicRejectionReason?>(null) + TopicRejectionReason.entries
        for (desired in listOf(false, true)) for (auth in TopicAuthResolution.entries) for (failure in failures)
            for (rejection in rejections) for (delivery in TopicDeliveryState.entries) {
                val snapshot = TopicSubscriptionSnapshot(
                    topics = mapOf("t" to TopicSubscriptionState(desired = desired, deliveryState = delivery, rejection = rejection)),
                    wholeFailure = failure,
                    authResolution = auth
                )
                val serverDelay = failure is TopicWholeRequestFailure.TemporarilyUnavailable
                val expected = desired && (
                    auth == TopicAuthResolution.FAILED ||
                        (!serverDelay && rejection == TopicRejectionReason.TOPICS_DISABLED) ||
                        (rejection == null && delivery == TopicDeliveryState.DEGRADED)
                    )
                assertEquals("B2-01 desired=$desired auth=$auth failure=$failure rejection=$rejection delivery=$delivery",
                    expected, "t" in snapshot.manualRetryTopics)
            }
    }
}
