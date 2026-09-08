package com.jay.fxi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When silence is worth one quiet question.
 *
 * Every hold below is a resubscribe that would otherwise have gone out — for a silence somebody
 * else was already waiting on, before anything had ever arrived to be silent about, or for the
 * second time over one expiry.
 */
class TopicSilencePolicyTest {

    private val TOPIC = "usdt:krw"
    private val armedAt = 1_000L
    private val deadline = armedAt + 45_000

    private fun decide(
        now: Long = deadline,
        armedUntil: Long? = deadline,
        handledWindow: Long? = null,
        tetherDelivered: Boolean = true,
        deliveryState: TopicDeliveryState = TopicDeliveryState.SUSPECT,
        firstDeliveryOwnerActive: Boolean = false
    ) = TopicSilencePolicy.decide(
        now, armedUntil, handledWindow, tetherDelivered, deliveryState, firstDeliveryOwnerActive
    )

    private fun hold(reason: TopicSilenceHold, spent: Boolean = false) =
        TopicSilenceDecision.Hold(reason, spendsWindow = spent)

    /** Forty-five seconds after the delivery that armed it. */
    @Test
    fun theWindowIsFortyFiveSecondsFromTheDelivery() {
        assertEquals(
            TopicSilenceArming.ArmAt(deadline),
            TopicSilencePolicy.armAfter(TopicSilenceEvidence.TETHER_DELIVERY, armedAt)
        )
    }

    /**
     * Anything that is not a tether delivery leaves the window alone.
     *
     * `Keep` rather than a bare `null`: a KRX frame forty seconds into a window must not disarm it.
     * A KRX-only feed that could keep re-arming — or clearing — this window would keep a dead
     * tether subscription looking alive for as long as KRX kept talking.
     */
    @Test
    fun aFrameThatIsNotATetherDeliveryLeavesTheWindowAlone() {
        assertEquals(
            TopicSilenceArming.Keep,
            TopicSilencePolicy.armAfter(TopicSilenceEvidence.OTHER, armedAt + 39_000)
        )
        // …and the window it did not touch is still the one that decides.
        assertEquals(hold(TopicSilenceHold.STILL_INSIDE_WINDOW), decide(now = armedAt + 39_000))
    }

    /**
     * A receive instant that cannot become a deadline is refused, and is not the same as `Keep`.
     *
     * Two different inputs land here for two different reasons: this takes milliseconds since
     * boot, so a negative one is outside its contract — monotonic alone would not say that, since
     * `-2, -1, 0` rises — and one near the top of the range overflows when the window is added.
     * Neither is a frame that arms nothing.
     */
    @Test
    fun aReceiveInstantOutsideTheContractIsRefused() {
        val latest = Long.MAX_VALUE - 45_000
        assertEquals(
            TopicSilenceArming.ArmAt(Long.MAX_VALUE),
            TopicSilencePolicy.armAfter(TopicSilenceEvidence.TETHER_DELIVERY, latest)
        )
        listOf(-1L, latest + 1, Long.MAX_VALUE).forEach {
            assertEquals(
                "$it",
                TopicSilenceArming.Unusable,
                TopicSilencePolicy.armAfter(TopicSilenceEvidence.TETHER_DELIVERY, it)
            )
        }
    }

    /** Reached is expired: the question goes out on the deadline, not a millisecond later. */
    @Test
    fun theQuestionGoesOutWhenTheWindowIsReached() {
        assertEquals(hold(TopicSilenceHold.STILL_INSIDE_WINDOW), decide(now = deadline - 1))
        assertEquals(TopicSilenceDecision.Revalidate, decide(now = deadline))
    }

    /**
     * Nothing is armed before the first delivery, and nothing decides without a window.
     *
     * Before anything has arrived the first-delivery watchdog is the only owner — D14 says so
     * outright, and a silence monitor that armed itself early would be a second one.
     */
    @Test
    fun silenceBeforeTheFirstDeliveryIsNotThisPolicysToAnswer() {
        assertEquals(
            hold(TopicSilenceHold.NEVER_DELIVERED),
            decide(tetherDelivered = false, deliveryState = TopicDeliveryState.NEVER_RECEIVED)
        )
        assertEquals(hold(TopicSilenceHold.NOT_ARMED), decide(armedUntil = null))
    }

    /**
     * A live first-delivery effort outranks an expired window.
     *
     * The overlap is reachable: a reconnect starts a fresh subscribe with its own delivery deadline
     * while the window armed before the reconnect is still counting. Both would answer the same
     * silence, and the plan asks for zero duplicate resubscribes.
     */
    @Test
    fun aLiveFirstDeliveryOwnerHoldsTheQuestion() {
        assertEquals(
            hold(TopicSilenceHold.DELIVERY_OWNER_ACTIVE, spent = true),
            decide(firstDeliveryOwnerActive = true)
        )
    }

    /** While the question is outstanding, it is not asked again. */
    @Test
    fun onlyOneQuestionIsAskedWhileOneIsOutstanding() {
        assertEquals(
            hold(TopicSilenceHold.ALREADY_REVALIDATING, spent = true),
            decide(deliveryState = TopicDeliveryState.REVALIDATING)
        )
    }

    /**
     * And once the question has been asked, that window never asks again.
     *
     * This is the case the outstanding-check misses. A revalidation that ends puts the topic
     * somewhere that is no longer `REVALIDATING` — degraded, or back to healthy with its attempt
     * counter cleared by `abortRevalidation` — and the same expiry would walk straight through.
     * Only a new delivery, arming a new window, earns another question. Found by review.
     */
    @Test
    fun anExpiryIsHandedOverOnlyOnce() {
        listOf(
            TopicDeliveryState.DEGRADED,
            TopicDeliveryState.HEALTHY,
            TopicDeliveryState.SUSPECT
        ).forEach { after ->
            assertEquals(
                "$after 로 끝난 뒤에도 같은 창이 다시 물었다",
                hold(TopicSilenceHold.WINDOW_ALREADY_HANDLED),
                decide(handledWindow = deadline, deliveryState = after)
            )
        }
    }

    /**
     * A window handed to another owner is spent too, through the real state machine.
     *
     * The hold is not the end of it: the owner it was handed to finishes — here by the abort that
     * a terminal error causes — and puts the topic back to healthy with its attempt counter at
     * zero. Nothing about that state says the silence was already answered, so without spending
     * the window on the hold, the same expiry opens a second question. Driven through
     * `TopicSubscriptionStateStore` rather than hand-set states, because it is that store's own
     * transitions that make the trap. Found by review.
     */
    @Test
    fun aWindowHandedToAnotherOwnerIsSpent() {
        val store = TopicSubscriptionStateStore()
        store.setDesired(desired = true, topic = TOPIC)
        store.applyAck(activeTopics = setOf(TOPIC), rejections = emptyMap(), sentTopics = setOf(TOPIC))
        store.recordFrame(TOPIC)

        // The expiry arrives while an outstanding question owns it.
        store.markSuspect(TOPIC)
        assertTrue(store.beginRevalidation(TOPIC))
        val handed = decide(deliveryState = store.snapshot.stateFor(TOPIC).deliveryState)
        assertEquals(hold(TopicSilenceHold.ALREADY_REVALIDATING, spent = true), handed)
        assertTrue("넘긴 창은 소진돼야 한다", handed.spendsWindow)

        // …that question ends with a terminal error, which puts the topic back to healthy with
        // its attempt counter cleared. Nothing in that state remembers the silence.
        store.abortRevalidation(TOPIC)
        val afterAbort = store.snapshot.stateFor(TOPIC)
        assertEquals(TopicDeliveryState.HEALTHY, afterAbort.deliveryState)
        // The counter, not `beginRevalidation`'s answer: it refuses a healthy topic whatever the
        // counter holds, so asking it would prove nothing about the budget being back at zero.
        assertEquals("abort 는 예산도 되돌린다", 0, afterAbort.revalidationAttempt)

        // The caller recorded the window because the answer said to, so it does not ask again.
        assertEquals(
            hold(TopicSilenceHold.WINDOW_ALREADY_HANDLED),
            decide(
                handledWindow = deadline,
                deliveryState = store.snapshot.stateFor(TOPIC).deliveryState
            )
        )
    }

    /**
     * Following the contract with an ordinary question closes the window too.
     *
     * The other spending tests go through a hold. This one drives the loop the caller actually
     * runs — ask, record if the answer says to, ask again — so that `Revalidate` carrying
     * `spendsWindow` is checked by what it causes rather than by comparing a singleton to itself.
     * Found by review.
     */
    @Test
    fun anOrdinaryQuestionAlsoClosesItsWindow() {
        var handled: Long? = null
        fun ask() = decide(handledWindow = handled).also { if (it.spendsWindow) handled = deadline }

        assertEquals(TopicSilenceDecision.Revalidate, ask())
        assertEquals(deadline, handled)
        assertEquals(hold(TopicSilenceHold.WINDOW_ALREADY_HANDLED), ask())
    }

    /**
     * A later delivery arms a new window, and that one may be asked about.
     *
     * What this locks is the window, not the canonical budget: the delivery state and its
     * revalidation counter move when `recordFrame` says so, and a REST bootstrap does not call it.
     */
    @Test
    fun aLaterDeliveryArmsAWindowThatMayBeAskedAbout() {
        val second = TopicSilencePolicy.armAfter(
            TopicSilenceEvidence.TETHER_DELIVERY, deadline + 5_000
        ) as TopicSilenceArming.ArmAt

        assertEquals(
            hold(TopicSilenceHold.STILL_INSIDE_WINDOW),
            decide(now = deadline + 10_000, armedUntil = second.millis, handledWindow = deadline)
        )
        assertEquals(
            TopicSilenceDecision.Revalidate,
            decide(now = second.millis, armedUntil = second.millis, handledWindow = deadline)
        )
    }
}
