package com.jay.fxi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicSubscriptionStateTest {
    private val topic = "usdt:krw"

    @Test
    fun `desired intent survives every server rejection`() {
        TopicRejectionReason.entries.forEach { reason ->
            val store = TopicSubscriptionStateStore()
            store.setDesired(true, topic)
            store.applyAck(emptySet(), mapOf(topic to reason), setOf(topic))

            assertTrue(reason.name, store.snapshot.stateFor(topic).desired)
            assertFalse(reason.name, store.snapshot.stateFor(topic).confirmed)
        }
    }

    @Test
    fun `access and retry policy are derived from canonical reasons`() {
        val store = TopicSubscriptionStateStore()
        store.setDesired(true, topic)
        store.applyAck(
            activeTopics = emptySet(),
            rejections = mapOf(topic to TopicRejectionReason.TOPICS_DISABLED),
            sentTopics = setOf(topic)
        )

        assertEquals(TopicAccessState.DISABLED, store.snapshot.stateFor(topic).accessState(TopicAuthResolution.RESOLVED))
        assertEquals(
            setOf(
                TopicRetryTrigger.NextConnection,
                TopicRetryTrigger.Foreground,
                TopicRetryTrigger.Manual
            ),
            store.snapshot.rejectionRetryTriggers(topic)
        )
    }

    @Test
    fun `temporary whole failure uses a one second retry floor`() {
        val store = TopicSubscriptionStateStore()
        store.setDesired(true, topic)
        store.applyWholeFailure(TopicWholeRequestFailure.TemporarilyUnavailable(0))

        assertEquals(
            setOf(TopicRetryTrigger.ServerDelay(1L)),
            store.snapshot.rejectionRetryTriggers(topic)
        )

        store.applyWholeFailure(TopicWholeRequestFailure.TemporarilyUnavailable(3))
        assertEquals(
            setOf(TopicRetryTrigger.ServerDelay(3L)),
            store.snapshot.rejectionRetryTriggers(topic)
        )
    }

    @Test
    fun `frame evidence and control-plane failure stay independent`() {
        val store = TopicSubscriptionStateStore()
        store.setDesired(true, topic)
        store.recordFrame(topic)
        store.applyWholeFailure(TopicWholeRequestFailure.InvalidToken)

        val state = store.snapshot.stateFor(topic)
        assertEquals(1, state.receiveGeneration)
        assertEquals(TopicDeliveryState.HEALTHY, state.deliveryState)
        assertEquals(TopicWholeRequestFailure.InvalidToken, store.snapshot.wholeFailure)
        assertEquals(TopicAuthResolution.FAILED, store.snapshot.authResolution)
    }

    @Test
    fun `pre-auth topics-disabled ack cannot resolve failed authentication`() {
        val store = TopicSubscriptionStateStore()
        store.setDesired(true, topic)
        store.applyWholeFailure(TopicWholeRequestFailure.InvalidToken)
        store.applyAck(
            activeTopics = emptySet(),
            rejections = mapOf(topic to TopicRejectionReason.TOPICS_DISABLED),
            sentTopics = setOf(topic),
            authResolved = false
        )

        assertEquals(TopicAuthResolution.FAILED, store.snapshot.authResolution)
        assertEquals(TopicRejectionReason.TOPICS_DISABLED, store.snapshot.stateFor(topic).rejection)
    }

    @Test
    fun `revalidation is single-shot until a frame recovers it`() {
        val store = TopicSubscriptionStateStore()
        store.setDesired(true, topic)
        store.applyAck(setOf(topic), emptyMap(), setOf(topic))
        store.recordFrame(topic)
        store.markSuspect(topic)

        assertTrue(store.beginRevalidation(topic))
        assertFalse(store.beginRevalidation(topic))
        store.recordFrame(topic)
        assertEquals(TopicDeliveryState.HEALTHY, store.snapshot.stateFor(topic).deliveryState)
        assertEquals(0, store.snapshot.stateFor(topic).revalidationAttempt)
    }

    @Test
    fun `terminal programming failure aborts revalidation without user degradation`() {
        val store = TopicSubscriptionStateStore()
        store.setDesired(true, topic)
        store.applyAck(setOf(topic), emptyMap(), setOf(topic))
        store.recordFrame(topic)
        store.markSuspect(topic)
        assertTrue(store.beginRevalidation(topic))

        store.applyWholeFailure(TopicWholeRequestFailure.InvalidRequest)
        store.abortRevalidation(topic)

        assertEquals(TopicWholeRequestFailure.InvalidRequest, store.snapshot.wholeFailure)
        assertEquals(TopicDeliveryState.HEALTHY, store.snapshot.stateFor(topic).deliveryState)
        assertTrue(store.snapshot.manualRetryTopics.isEmpty())
    }

    @Test
    fun `hard expiry drops confirmation but preserves intent`() {
        val store = TopicSubscriptionStateStore()
        store.setDesired(true, topic)
        store.applyAck(setOf(topic), emptyMap(), setOf(topic))
        store.expire(setOf(topic))

        val state = store.snapshot.stateFor(topic)
        assertTrue(state.desired)
        assertFalse(state.confirmed)
        assertEquals(TopicDeliveryState.DEGRADED, state.deliveryState)
    }

    @Test
    fun `unknown topic retry opens only on the next connection`() {
        val futureTopic = "future:topic"
        val store = TopicSubscriptionStateStore()
        store.setDesired(true, futureTopic)
        store.applyAck(
            activeTopics = emptySet(),
            rejections = mapOf(futureTopic to TopicRejectionReason.UNKNOWN_TOPIC),
            sentTopics = setOf(futureTopic)
        )

        assertEquals(
            setOf(TopicRetryTrigger.NextConnection),
            store.snapshot.rejectionRetryTriggers(futureTopic)
        )
        assertTrue(store.snapshot.manualRetryTopics.isEmpty())

        store.startNewConnection()
        assertNull(store.snapshot.stateFor(futureTopic).rejection)
        assertTrue(store.snapshot.stateFor(futureTopic).desired)
    }

    @Test
    fun `rejection retry triggers preserve canonical scope`() {
        val topics = mapOf(
            "disabled" to TopicRejectionReason.TOPICS_DISABLED,
            "unavailable" to TopicRejectionReason.TOPIC_UNAVAILABLE,
            "unknown" to TopicRejectionReason.UNKNOWN_TOPIC,
            "premium" to TopicRejectionReason.PREMIUM_REQUIRED,
            "krx" to TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED
        )
        val store = TopicSubscriptionStateStore()
        topics.keys.forEach { store.setDesired(true, it) }
        store.applyAck(emptySet(), topics, topics.keys)

        assertEquals(
            setOf(TopicRetryTrigger.NextConnection, TopicRetryTrigger.Foreground, TopicRetryTrigger.Manual),
            store.snapshot.rejectionRetryTriggers("disabled")
        )
        assertEquals(
            setOf(TopicRetryTrigger.NextConnection, TopicRetryTrigger.Foreground),
            store.snapshot.rejectionRetryTriggers("unavailable")
        )
        assertEquals(
            setOf(TopicRetryTrigger.NextConnection),
            store.snapshot.rejectionRetryTriggers("unknown")
        )
        assertEquals(
            setOf(TopicRetryTrigger.EntitlementChange),
            store.snapshot.rejectionRetryTriggers("premium")
        )
        assertEquals(
            setOf(TopicRetryTrigger.EntitlementChange),
            store.snapshot.rejectionRetryTriggers("krx")
        )
    }

    @Test
    fun `entitlement change opens only the matching authorization rejection`() {
        val premium = "fx:usd-krw"
        val krx = "krx:usd-krw-futures"
        val store = TopicSubscriptionStateStore()
        store.setDesired(true, premium)
        store.setDesired(true, krx)
        store.applyAck(
            activeTopics = emptySet(),
            rejections = mapOf(
                premium to TopicRejectionReason.PREMIUM_REQUIRED,
                krx to TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED
            ),
            sentTopics = setOf(premium, krx)
        )

        assertEquals(
            setOf(premium),
            store.openRejectedTopics(
                trigger = TopicRetryTrigger.EntitlementChange,
                matchingReason = TopicRejectionReason.PREMIUM_REQUIRED
            )
        )
        assertNull(store.snapshot.stateFor(premium).rejection)
        assertEquals(
            TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED,
            store.snapshot.stateFor(krx).rejection
        )
    }

    @Test
    fun `new connection clears session delivery state but preserves entitlement gates`() {
        val unavailable = "usdt:krw"
        val premium = "fx:usd-krw"
        val store = TopicSubscriptionStateStore()
        store.setDesired(true, unavailable)
        store.setDesired(true, premium)
        store.applyAck(
            activeTopics = setOf(unavailable),
            rejections = mapOf(premium to TopicRejectionReason.PREMIUM_REQUIRED),
            sentTopics = setOf(unavailable, premium)
        )
        store.recordFrame(unavailable)
        store.markSuspect(unavailable)
        assertTrue(store.beginRevalidation(unavailable))

        store.startNewConnection()

        val reset = store.snapshot.stateFor(unavailable)
        assertFalse(reset.confirmed)
        assertEquals(TopicDeliveryState.NEVER_RECEIVED, reset.deliveryState)
        assertEquals(0, reset.revalidationAttempt)
        assertEquals(
            TopicRejectionReason.PREMIUM_REQUIRED,
            store.snapshot.stateFor(premium).rejection
        )
    }
}
