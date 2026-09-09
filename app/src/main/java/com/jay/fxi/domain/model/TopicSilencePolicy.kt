package com.jay.fxi.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Why a quiet revalidation is not being started right now. */
enum class TopicSilenceHold {
    /** No valid tether delivery has ever arrived in this scope, so nothing is armed yet. */
    NEVER_DELIVERED,

    /** No window is armed. Nothing has delivered yet in this scope, or a purge cleared it. */
    NOT_ARMED,

    /**
     * This window has already been handed over once.
     *
     * One question per episode, and the window is what an episode is. Without this the same expiry
     * asks again the moment the first question stops being outstanding — after it degrades, or
     * after `abortRevalidation` puts the topic back to healthy with its attempt counter at zero.
     * Only a new delivery, arming a new window, earns another question. Found by review.
     */
    WINDOW_ALREADY_HANDLED,

    /** The window has not run out. */
    STILL_INSIDE_WINDOW,

    /**
     * The first-delivery watchdog still owns this subscription.
     *
     * Two owners waiting on one topic is how one silence produces two resubscribes.
     */
    DELIVERY_OWNER_ACTIVE,

    /** A revalidation is already out; what happens if it stays quiet is the arbiter's to say. */
    ALREADY_REVALIDATING
}

sealed interface TopicSilenceDecision {
    /**
     * Whether this answer spends the expired window.
     *
     * An expiry is answered once, by whoever answers it — and being handed to somebody else counts
     * as answered. A window yielded to a live first-delivery effort or to an outstanding question
     * is not left loaded to fire again when that owner finishes; the caller records it as handled
     * exactly when this says so. Found by review.
     */
    val spendsWindow: Boolean

    data class Hold(
        val reason: TopicSilenceHold,
        override val spendsWindow: Boolean
    ) : TopicSilenceDecision

    /**
     * Hand this window to the state machine: ask it to begin a revalidation, and send one if it
     * agrees ([TopicSubscriptionStateStore.beginRevalidation] returning `true`). Values stay on
     * screen throughout, and the window is spent whether or not the store agreed — what this
     * answers is the fresh-to-silent transition, and a transition happens once.
     */
    data object Revalidate : TopicSilenceDecision {
        override val spendsWindow: Boolean get() = true
    }
}

/**
 * When forty-five seconds of tether silence is worth asking about.
 *
 * D14 (`ANDROID_V2_PLAN.md:191`; `:880-881` places it inside S3). Silence is not an error — a
 * quiet market is quiet — so the answer to it is one quiet question, not a visible failure and not
 * a stream of resubscribes. Values are never hidden on account of it.
 *
 * **Tether only.** FX has no time-based expiry at all, and KRX is an optional group whose absence
 * proves nothing about the connection. A KRX frame arriving alone must not arm this window, extend
 * it, or satisfy it — otherwise a KRX-only feed keeps a dead tether subscription looking alive.
 *
 * **What ends an episode is new receive evidence, not an acknowledgement.** An ACK says the command
 * was heard; the question here is whether anything is arriving. So a new tether delivery re-arms
 * the window and the silence after it is a new episode with its own single question. That is this
 * window only: the canonical delivery state and its revalidation budget move when `recordFrame`
 * says so, and a REST bootstrap does not call it.
 *
 * **What happens when the quiet question is itself met with silence is not decided here.** That
 * revalidation is a request like any other and carries the deadlines of one
 * ([TopicRequestPolicy]); its delivery deadline expiring with no new receive generation is what
 * degrades the topic. A second silence timer here would be a second owner for one fact.
 */
object TopicSilencePolicy {
    /** D14's window. iOS runs the same one on a clock that, like this one, counts sleep. */
    val SILENCE_WINDOW: Duration = 45.seconds

    /** The latest receive instant whose window still fits in a `Long`. */
    private val LATEST_RECEIVE_MILLIS = Long.MAX_VALUE - SILENCE_WINDOW.inWholeMilliseconds

    /**
     * What this delivery proposes for the window.
     *
     * Armed by **a tether delivery that passed validation**, whether it came over the socket or
     * through the REST bootstrap — both are evidence that data is arriving. Nothing else arms it.
     *
     * Anything else answers [TopicSilenceArming.Keep], which is *no new proposal* and not a
     * disarming: a KRX frame forty seconds into a tether window must leave that window exactly
     * where it was. Returning a bare `null` for both cases invited the caller to store it and
     * throw the guard away. Found by review.
     *
     * This takes an **outcome**, not a payload, because counting quotes cannot answer the
     * question. Its caller decides, and the rule the session settled on is that a payload leaving
     * no usable price is not a delivery: after D8 an empty tether snapshot cannot be told apart
     * from one carrying only `usd_krw_futures`, and `ANDROID_V2_PLAN.md` D14 forbids the latter
     * satisfying tether delivery. An earlier draft of this comment said the opposite — that an
     * empty snapshot is a quiet market answering — and that reading would have let a KRX-only
     * frame arm this window. Found by review.
     */
    fun armAfter(delivery: TopicSilenceEvidence, receivedAtMillis: Long): TopicSilenceArming = when {
        delivery != TopicSilenceEvidence.TETHER_DELIVERY -> TopicSilenceArming.Keep
        receivedAtMillis < 0 || receivedAtMillis > LATEST_RECEIVE_MILLIS ->
            TopicSilenceArming.Unusable
        else -> TopicSilenceArming.ArmAt(receivedAtMillis + SILENCE_WINDOW.inWholeMilliseconds)
    }

    /**
     * Whether to ask, given everything the transport knows.
     *
     * @param armedUntilMillis the deadline [armAfter] produced, or `null` if nothing armed one
     * @param handledWindowMillis the deadline already answered, if any. Record the armed deadline
     *   here whenever the answer says [TopicSilenceDecision.spendsWindow] — which covers being
     *   handed to another owner as well as producing a question of its own. Without it the same
     *   expiry asks again the moment its owner finishes, one way or the other
     * @param tetherDelivered whether a valid tether delivery has ever arrived in this scope. It
     *   survives the window expiring and a plain reconnect, and is cleared only by a purge — a
     *   restored disk seed does not set it, because a seed is not a delivery.
     * @param deliveryState what the canonical store says about this topic
     * @param firstDeliveryOwnerActive whether **the effort to get a first delivery** is still under
     *   way — not merely whether a timer is ticking. Acquiring a token and waiting out a retry
     *   cooldown are both part of it, and a silence answered during either would be answered twice.
     *   A reconnect starts a fresh effort while the window armed before it is still running
     */
    fun decide(
        nowMillis: Long,
        armedUntilMillis: Long?,
        handledWindowMillis: Long?,
        tetherDelivered: Boolean,
        deliveryState: TopicDeliveryState,
        firstDeliveryOwnerActive: Boolean
    ): TopicSilenceDecision = when {
        // Nothing has expired in these three, so there is no window to spend.
        !tetherDelivered -> hold(TopicSilenceHold.NEVER_DELIVERED)
        armedUntilMillis == null -> hold(TopicSilenceHold.NOT_ARMED)
        nowMillis < armedUntilMillis -> hold(TopicSilenceHold.STILL_INSIDE_WINDOW)
        armedUntilMillis == handledWindowMillis -> hold(TopicSilenceHold.WINDOW_ALREADY_HANDLED)

        // These two did expire, and are answered by somebody who already owns this silence.
        firstDeliveryOwnerActive -> hold(TopicSilenceHold.DELIVERY_OWNER_ACTIVE, spent = true)
        deliveryState == TopicDeliveryState.REVALIDATING ->
            hold(TopicSilenceHold.ALREADY_REVALIDATING, spent = true)

        else -> TopicSilenceDecision.Revalidate
    }

    private fun hold(reason: TopicSilenceHold, spent: Boolean = false) =
        TopicSilenceDecision.Hold(reason, spendsWindow = spent)
}

/**
 * What arrived, as far as this policy cares.
 *
 * Only one kind of delivery arms the tether window, and naming the other kind is what keeps a KRX
 * frame from being passed in as though it were one.
 */
sealed interface TopicSilenceArming {
    /**
     * No new proposal. Whatever is armed keeps guarding — this is not a disarming.
     */
    data object Keep : TopicSilenceArming

    /** Replace the window with this deadline. */
    data class ArmAt(val millis: Long) : TopicSilenceArming

    /**
     * A receive instant outside the contract.
     *
     * Either a negative instant — this takes milliseconds since boot, which are not negative, and
     * monotonic says nothing about that: `-2, -1, 0` rises too — or one so large that adding the
     * window overflows. Separated from [Keep] because the two mean different things: one is a
     * frame that arms nothing, the other is a number that cannot become a deadline at all.
     */
    data object Unusable : TopicSilenceArming
}

enum class TopicSilenceEvidence {
    /** A tether payload that passed validation, from the socket or the REST bootstrap. */
    TETHER_DELIVERY,

    /** Anything else — a KRX frame, an ACK, a restored seed. */
    OTHER
}
