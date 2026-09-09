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

    /**
     * A command that gave up leaves a failure, not a request that still reads as in flight.
     *
     * There is no `subscription_error` behind a spent budget, so there is no
     * [TopicWholeRequestFailure] to record — but `PENDING` outliving the only thing that could
     * answer it is worse than an unexplained failure.
     */
    @Test
    fun `giving up closes a pending request without inventing a server verdict`() {
        val store = TopicSubscriptionStateStore()
        store.setDesired(true, topic)
        store.giveUpRequest(store.beginRequest())

        assertEquals(TopicControlState.FAILED, store.snapshot.controlState)
        assertNull(store.snapshot.wholeFailure)
    }

    /** Stopping because nobody wants the topics is not a failure. */
    @Test
    fun `releasing a pending request leaves it idle`() {
        val store = TopicSubscriptionStateStore()
        store.setDesired(true, topic)
        store.releaseRequest(store.beginRequest())

        assertEquals(TopicControlState.IDLE, store.snapshot.controlState)
    }

    /**
     * Neither ending writes over a verdict that did arrive.
     *
     * Both are guarded on `PENDING`, so a late call — from a command being torn down beside the
     * one that replaced it — cannot turn an acknowledged subscription back into a failure, or a
     * server's failure into an idle request.
     */
    @Test
    fun `neither ending disturbs a request that was already answered`() {
        val acknowledged = TopicSubscriptionStateStore()
        acknowledged.setDesired(true, topic)
        val answered = acknowledged.beginRequest()
        acknowledged.applyAck(setOf(topic), emptyMap(), setOf(topic))
        acknowledged.giveUpRequest(answered)
        acknowledged.releaseRequest(answered)
        assertEquals(TopicControlState.ACKNOWLEDGED, acknowledged.snapshot.controlState)

        val failed = TopicSubscriptionStateStore()
        failed.setDesired(true, topic)
        val refused = failed.beginRequest()
        failed.applyWholeFailure(TopicWholeRequestFailure.InvalidRequest)
        failed.releaseRequest(refused)
        assertEquals(TopicControlState.FAILED, failed.snapshot.controlState)
        assertEquals(TopicWholeRequestFailure.InvalidRequest, failed.snapshot.wholeFailure)
    }

    /**
     * A replacement's request survives the ending of the command it replaced.
     *
     * `PENDING` is not ownership — the replacement is `PENDING` too — so without the ticket a late
     * ending from the outgoing command closed the incoming one's request. Both directions were
     * demonstrated in review; both are checked here.
     */
    @Test
    fun `an outgoing command cannot end its replacement's request`() {
        val released = TopicSubscriptionStateStore()
        val outgoing = released.beginRequest()
        released.beginRequest()
        released.releaseRequest(outgoing)
        assertEquals(TopicControlState.PENDING, released.snapshot.controlState)

        val failed = TopicSubscriptionStateStore()
        val abandoned = failed.beginRequest()
        failed.beginRequest()
        failed.giveUpRequest(abandoned)
        assertEquals(TopicControlState.PENDING, failed.snapshot.controlState)
    }

    /** A ticket is spent once, so a command cannot end the same request twice. */
    @Test
    fun `a ticket cannot be used twice`() {
        val store = TopicSubscriptionStateStore()
        val ticket = store.beginRequest()
        store.releaseRequest(ticket)
        val second = store.beginRequest()
        store.giveUpRequest(ticket)

        assertEquals(TopicControlState.PENDING, store.snapshot.controlState)
        store.giveUpRequest(second)
        assertEquals(TopicControlState.FAILED, store.snapshot.controlState)
    }

    /**
     * A finished refresh stops reading as one that is still running.
     *
     * `REFRESHING` describes a fetch in progress. Left for the next acknowledgement to clear, a
     * replay that is never answered leaves the app mid-recovery with nothing recovering.
     */
    @Test
    fun `ending a refresh resolves it without claiming the new credential works`() {
        val store = TopicSubscriptionStateStore()
        store.applyWholeFailure(TopicWholeRequestFailure.InvalidToken)
        store.endAuthRefresh(store.beginAuthRefresh())
        assertEquals(TopicAuthResolution.RESOLVED, store.snapshot.authResolution)

        // …and it only ends a refresh. A credential the server has just refused stays refused.
        store.applyWholeFailure(TopicWholeRequestFailure.InvalidToken)
        val stale = store.beginAuthRefresh()
        store.applyWholeFailure(TopicWholeRequestFailure.InvalidToken)
        store.endAuthRefresh(stale)
        assertEquals(TopicAuthResolution.FAILED, store.snapshot.authResolution)
    }

    /**
     * An abandoned recovery leaves the refusal that started it standing.
     *
     * `endAuthRefresh` is for one that produced a credential. Using it for a recovery that was
     * cancelled would report a recovery that never happened; the token the server refused is still
     * the last thing anybody knows about.
     */
    @Test
    fun `abandoning a refresh returns to the refusal that started it`() {
        val store = TopicSubscriptionStateStore()
        store.applyWholeFailure(TopicWholeRequestFailure.InvalidToken)
        store.abandonAuthRefresh(store.beginAuthRefresh())
        assertEquals(TopicAuthResolution.FAILED, store.snapshot.authResolution)
    }

    /** Neither ending reaches a recovery somebody else started. */
    @Test
    fun `an outgoing command cannot end its replacement's recovery`() {
        val ended = TopicSubscriptionStateStore()
        val outgoing = ended.beginAuthRefresh()
        ended.beginAuthRefresh()
        ended.endAuthRefresh(outgoing)
        assertEquals(TopicAuthResolution.REFRESHING, ended.snapshot.authResolution)

        val abandoned = TopicSubscriptionStateStore()
        val leaving = abandoned.beginAuthRefresh()
        abandoned.beginAuthRefresh()
        abandoned.abandonAuthRefresh(leaving)
        assertEquals(TopicAuthResolution.REFRESHING, abandoned.snapshot.authResolution)
    }

    /**
     * A purge forgets the frames too, which is what makes it different from losing interest.
     *
     * `setDesired(false)` keeps the receive generation on purpose — the same user turning a topic
     * off has not unseen what it sent. A UID or epoch change has: those frames were another
     * grant's, and leaving the count behind would let the next session's first-delivery watchdog
     * be satisfied by them.
     */
    @Test
    fun `purging forgets receive evidence, unlike no longer wanting a topic`() {
        val store = TopicSubscriptionStateStore()
        store.setDesired(true, topic)
        store.recordFrame(topic)
        store.setDesired(false, topic)
        assertEquals(1L, store.snapshot.stateFor(topic).receiveGeneration)

        store.purge(TopicPurgeScope.All)
        assertEquals(0L, store.snapshot.stateFor(topic).receiveGeneration)
        assertEquals(TopicControlState.IDLE, store.snapshot.controlState)
        assertEquals(TopicAuthResolution.RESOLVED, store.snapshot.authResolution)
    }

    /** A scoped purge leaves the topics it was not asked about. */
    @Test
    fun `a scoped purge leaves the other topics alone`() {
        val store = TopicSubscriptionStateStore()
        store.setDesired(true, topic)
        store.recordFrame(topic)
        store.setDesired(true, "fx:jpy-krw")
        store.recordFrame("fx:jpy-krw")

        store.purge(TopicPurgeScope.Topics(setOf(topic)))
        assertEquals(0L, store.snapshot.stateFor(topic).receiveGeneration)
        assertEquals(1L, store.snapshot.stateFor("fx:jpy-krw").receiveGeneration)
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

        store.clearConnectionState()
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

        store.clearConnectionState()

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
