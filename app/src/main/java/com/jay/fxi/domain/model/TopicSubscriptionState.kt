package com.jay.fxi.domain.model

/** Topic data-plane delivery state, independent from control-plane acknowledgements. */
enum class TopicDeliveryState {
    NEVER_RECEIVED,
    HEALTHY,
    SUSPECT,
    REVALIDATING,
    DEGRADED
}

sealed interface TopicWholeRequestFailure {
    data class TemporarilyUnavailable(val retryAfterSeconds: Long) : TopicWholeRequestFailure
    data object InvalidToken : TopicWholeRequestFailure
    data object InvalidRequest : TopicWholeRequestFailure
    data object RequestTooLarge : TopicWholeRequestFailure
}

enum class TopicRejectionReason {
    TOPICS_DISABLED,
    TOPIC_UNAVAILABLE,
    UNKNOWN_TOPIC,
    PREMIUM_REQUIRED,
    KRX_ENTITLEMENT_REQUIRED
}

enum class TopicAuthResolution {
    RESOLVED,
    REFRESHING,
    FAILED
}

enum class TopicControlState {
    IDLE,
    PENDING,
    ACKNOWLEDGED,
    FAILED
}

enum class TopicAccessState {
    ALLOWED,
    DISABLED,
    AUTHORIZATION_DENIED,
    UNKNOWN_TOPIC,
    UNAVAILABLE
}

sealed interface TopicRetryTrigger {
    data class ServerDelay(val seconds: Long) : TopicRetryTrigger
    data object AuthChange : TopicRetryTrigger
    data object EntitlementChange : TopicRetryTrigger
    data object NextConnection : TopicRetryTrigger
    data object Foreground : TopicRetryTrigger
    data object Manual : TopicRetryTrigger
}

data class TopicSubscriptionState(
    val desired: Boolean = false,
    val confirmed: Boolean = false,
    val receiveGeneration: Long = 0,
    val deliveryState: TopicDeliveryState = TopicDeliveryState.NEVER_RECEIVED,
    val rejection: TopicRejectionReason? = null,
    val revalidationAttempt: Int = 0
) {
    fun accessState(authResolution: TopicAuthResolution): TopicAccessState {
        if (authResolution == TopicAuthResolution.FAILED) {
            return TopicAccessState.AUTHORIZATION_DENIED
        }
        return when (rejection) {
            null -> TopicAccessState.ALLOWED
            TopicRejectionReason.TOPICS_DISABLED -> TopicAccessState.DISABLED
            TopicRejectionReason.PREMIUM_REQUIRED,
            TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED -> TopicAccessState.AUTHORIZATION_DENIED
            TopicRejectionReason.UNKNOWN_TOPIC -> TopicAccessState.UNKNOWN_TOPIC
            TopicRejectionReason.TOPIC_UNAVAILABLE -> TopicAccessState.UNAVAILABLE
        }
    }
}

data class TopicSubscriptionSnapshot(
    val topics: Map<String, TopicSubscriptionState> = emptyMap(),
    val controlState: TopicControlState = TopicControlState.IDLE,
    val wholeFailure: TopicWholeRequestFailure? = null,
    val authResolution: TopicAuthResolution = TopicAuthResolution.RESOLVED
) {
    fun stateFor(topic: String): TopicSubscriptionState = topics[topic] ?: TopicSubscriptionState()

    val disabled: Boolean
        get() = topics.values.any { it.desired && it.rejection == TopicRejectionReason.TOPICS_DISABLED }

    val degradedTopics: Set<String>
        get() = topics.mapNotNullTo(mutableSetOf()) { (topic, state) ->
            topic.takeIf { state.desired && state.deliveryState == TopicDeliveryState.DEGRADED }
        }

    val manualRetryTopics: Set<String>
        get() = topics.mapNotNullTo(mutableSetOf()) { (topic, state) ->
            if (!state.desired) return@mapNotNullTo null
            topic.takeIf {
                TopicRetryTrigger.Manual in rejectionRetryTriggers(topic) ||
                    state.rejection == null && state.deliveryState == TopicDeliveryState.DEGRADED
            }
        }

    fun rejectionRetryTriggers(topic: String): Set<TopicRetryTrigger> {
        val state = stateFor(topic)
        if (authResolution == TopicAuthResolution.FAILED) {
            return setOf(TopicRetryTrigger.AuthChange, TopicRetryTrigger.Manual)
        }
        val failure = wholeFailure
        if (failure is TopicWholeRequestFailure.TemporarilyUnavailable) {
            return setOf(TopicRetryTrigger.ServerDelay(maxOf(1L, failure.retryAfterSeconds)))
        }
        return when (state.rejection) {
            null -> emptySet()
            TopicRejectionReason.TOPICS_DISABLED -> setOf(
                TopicRetryTrigger.NextConnection,
                TopicRetryTrigger.Foreground,
                TopicRetryTrigger.Manual
            )
            TopicRejectionReason.TOPIC_UNAVAILABLE -> setOf(
                TopicRetryTrigger.NextConnection,
                TopicRetryTrigger.Foreground
            )
            TopicRejectionReason.UNKNOWN_TOPIC -> setOf(TopicRetryTrigger.NextConnection)
            TopicRejectionReason.PREMIUM_REQUIRED,
            TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED -> setOf(TopicRetryTrigger.EntitlementChange)
        }
    }
}

sealed interface TopicPurgeScope {
    data object All : TopicPurgeScope
    data class Topics(val values: Set<String>) : TopicPurgeScope
}

/** Proof that the holder opened the request currently in flight. */
@ConsistentCopyVisibility
data class TopicRequestTicket internal constructor(private val serial: Long)

/** Proof that the holder started the credential recovery currently in flight. */
@ConsistentCopyVisibility
data class TopicAuthRefreshTicket internal constructor(private val serial: Long)

/** Mutable reducer for canonical topic facts. Every exposed snapshot is immutable. */
class TopicSubscriptionStateStore {
    private val topics = linkedMapOf<String, TopicSubscriptionState>()
    private var controlState = TopicControlState.IDLE
    private var wholeFailure: TopicWholeRequestFailure? = null
    private var authResolution = TopicAuthResolution.RESOLVED
    private var requestSerial = 0L
    private var currentTicket: TopicRequestTicket? = null
    private var authRefreshSerial = 0L
    private var currentAuthTicket: TopicAuthRefreshTicket? = null

    val snapshot: TopicSubscriptionSnapshot
        get() = TopicSubscriptionSnapshot(
            topics = topics.toMap(),
            controlState = controlState,
            wholeFailure = wholeFailure,
            authResolution = authResolution
        )

    fun setDesired(desired: Boolean, topic: String) {
        update(topic) { state ->
            if (desired) {
                state.copy(desired = true)
            } else {
                state.copy(
                    desired = false,
                    confirmed = false,
                    rejection = null,
                    deliveryState = TopicDeliveryState.NEVER_RECEIVED,
                    revalidationAttempt = 0
                )
            }
        }
    }

    /**
     * Opens a request, and hands back the only thing allowed to close it.
     *
     * The ticket exists because "is the state still `PENDING`?" is not a test of ownership: a
     * replacement command is `PENDING` too, so a late ending from the command it replaced would
     * close *its* request. Review demonstrated both halves of that — a released replacement and a
     * failed one — and a cancelled command with no replacement leaving `PENDING` behind for good.
     */
    fun beginRequest(): TopicRequestTicket {
        controlState = TopicControlState.PENDING
        wholeFailure = null
        return TopicRequestTicket(++requestSerial).also { currentTicket = it }
    }

    /**
     * The command gave up without the server ever having said why.
     *
     * A spent budget and a send that never left are real endings, and neither of them is a
     * `subscription_error`: there is no [TopicWholeRequestFailure] to record, and inventing one
     * would put a server verdict in the store that no server gave. What must not survive is
     * [TopicControlState.PENDING] — a request still reading as in flight after the only thing that
     * could answer it has stopped.
     *
     * The **ticket** is what keeps this off the next command's request; the `PENDING` check only
     * keeps it off a verdict that did arrive. Review demonstrated the difference: a replacement is
     * `PENDING` too, so the state alone said nothing about whose request this was.
     */
    fun giveUpRequest(ticket: TopicRequestTicket) {
        if (!claim(ticket)) return
        if (controlState == TopicControlState.PENDING) {
            controlState = TopicControlState.FAILED
        }
    }

    /** The command stopped because nobody wants its topics any more — not a failure. */
    fun releaseRequest(ticket: TopicRequestTicket) {
        if (!claim(ticket)) return
        if (controlState == TopicControlState.PENDING) {
            controlState = TopicControlState.IDLE
            wholeFailure = null
        }
    }

    /** True once, for the holder of the open request; every later or foreign ending is ignored. */
    private fun claim(ticket: TopicRequestTicket): Boolean {
        if (currentTicket != ticket) return false
        currentTicket = null
        return true
    }

    fun applyAck(
        activeTopics: Set<String>,
        rejections: Map<String, TopicRejectionReason>,
        sentTopics: Set<String>,
        authResolved: Boolean = true
    ) {
        controlState = TopicControlState.ACKNOWLEDGED
        wholeFailure = null
        if (authResolved) authResolution = TopicAuthResolution.RESOLVED

        val knownTopics = buildSet {
            addAll(topics.keys)
            addAll(sentTopics)
            addAll(activeTopics)
        }
        knownTopics.forEach { topic ->
            update(topic) { state ->
                state.copy(
                    confirmed = topic in activeTopics,
                    rejection = when {
                        topic in activeTopics -> null
                        topic in sentTopics -> rejections[topic]
                        else -> state.rejection
                    }
                )
            }
        }
    }

    fun applyWholeFailure(failure: TopicWholeRequestFailure) {
        controlState = TopicControlState.FAILED
        wholeFailure = failure
        if (failure == TopicWholeRequestFailure.InvalidToken) {
            authResolution = TopicAuthResolution.FAILED
        }
    }

    /**
     * Starts the one forced refresh a rejected credential is owed, and says who owns it.
     *
     * A ticket for the same reason the request has one: a command torn down beside its replacement
     * must not end the replacement's recovery, and — the case review demonstrated — a command
     * cancelled *during* its own refresh has to be able to end that recovery rather than leave
     * `REFRESHING` standing with nothing behind it.
     */
    fun beginAuthRefresh(): TopicAuthRefreshTicket {
        authResolution = TopicAuthResolution.REFRESHING
        return TopicAuthRefreshTicket(++authRefreshSerial).also { currentAuthTicket = it }
    }

    /**
     * The forced refresh finished and produced a usable credential.
     *
     * `REFRESHING` says a refresh is *running*, so it has to end when the refresh does rather than
     * when whatever follows is answered. Left to be cleared by the next acknowledgement, a replay
     * that goes unanswered — or a budget that runs out — leaves the app reading as mid-recovery
     * with nothing recovering. It says nothing about whether the new credential works: only an
     * `invalid_token` for it, through [applyWholeFailure], says that. Found by review.
     */
    fun endAuthRefresh(ticket: TopicAuthRefreshTicket) {
        if (!claimAuthRefresh(ticket)) return
        if (authResolution == TopicAuthResolution.REFRESHING) {
            authResolution = TopicAuthResolution.RESOLVED
        }
    }

    /**
     * The recovery stopped without producing anything, so the refusal that started it stands.
     *
     * Not [endAuthRefresh]: that one is for a refresh that handed back a usable credential, and
     * treating an abandoned one the same way would report a recovery that never happened. The
     * credential the server refused is still the last thing anybody knows. Found by review.
     */
    fun abandonAuthRefresh(ticket: TopicAuthRefreshTicket) {
        if (!claimAuthRefresh(ticket)) return
        if (authResolution == TopicAuthResolution.REFRESHING) {
            authResolution = TopicAuthResolution.FAILED
        }
    }

    private fun claimAuthRefresh(ticket: TopicAuthRefreshTicket): Boolean {
        if (currentAuthTicket != ticket) return false
        currentAuthTicket = null
        return true
    }

    fun recordFrame(topic: String) {
        update(topic) { state ->
            state.copy(
                receiveGeneration = state.receiveGeneration + 1,
                deliveryState = TopicDeliveryState.HEALTHY,
                revalidationAttempt = 0
            )
        }
    }

    fun markSuspect(topic: String) {
        update(topic) { state ->
            if (state.desired && state.confirmed && state.deliveryState == TopicDeliveryState.HEALTHY) {
                state.copy(deliveryState = TopicDeliveryState.SUSPECT)
            } else {
                state
            }
        }
    }

    fun beginRevalidation(topic: String): Boolean {
        var started = false
        update(topic) { state ->
            if (
                state.desired && state.confirmed && state.revalidationAttempt == 0 &&
                (state.deliveryState == TopicDeliveryState.SUSPECT ||
                    state.deliveryState == TopicDeliveryState.NEVER_RECEIVED)
            ) {
                started = true
                state.copy(
                    deliveryState = TopicDeliveryState.REVALIDATING,
                    revalidationAttempt = 1
                )
            } else {
                state
            }
        }
        return started
    }

    fun markDegraded(topic: String) {
        update(topic) { state ->
            if (state.desired) state.copy(deliveryState = TopicDeliveryState.DEGRADED) else state
        }
    }

    fun abortRevalidation(topic: String) {
        update(topic) { state ->
            if (state.deliveryState != TopicDeliveryState.REVALIDATING) return@update state
            state.copy(
                deliveryState = if (state.receiveGeneration > 0) {
                    TopicDeliveryState.HEALTHY
                } else {
                    TopicDeliveryState.NEVER_RECEIVED
                },
                revalidationAttempt = 0
            )
        }
    }

    fun beginManualRevalidation(topics: Set<String>) {
        topics.forEach { topic ->
            update(topic) { state ->
                if (
                    state.desired &&
                    (state.deliveryState == TopicDeliveryState.DEGRADED ||
                        state.deliveryState == TopicDeliveryState.SUSPECT)
                ) {
                    state.copy(
                        deliveryState = TopicDeliveryState.REVALIDATING,
                        revalidationAttempt = 1
                    )
                } else {
                    state
                }
            }
        }
    }

    /** Opens only rejection states that explicitly permit the supplied retry trigger. */
    fun openRejectedTopics(
        trigger: TopicRetryTrigger,
        matchingReason: TopicRejectionReason? = null,
        requestedTopics: Set<String>? = null
    ): Set<String> {
        val scope = requestedTopics ?: topics.keys.toSet()
        val opened = mutableSetOf<String>()

        scope.forEach { topic ->
            val current = snapshot.stateFor(topic)
            if (
                !current.desired || current.rejection == null ||
                (matchingReason != null && current.rejection != matchingReason) ||
                trigger !in snapshot.rejectionRetryTriggers(topic)
            ) {
                return@forEach
            }

            update(topic) { state ->
                state.copy(
                    confirmed = false,
                    rejection = null,
                    deliveryState = TopicDeliveryState.NEVER_RECEIVED,
                    revalidationAttempt = 0
                )
            }
            opened += topic
        }
        return opened
    }

    fun expire(topics: Set<String>) {
        topics.forEach { topic ->
            update(topic) { state ->
                state.copy(
                    confirmed = false,
                    deliveryState = if (state.desired) {
                        TopicDeliveryState.DEGRADED
                    } else {
                        state.deliveryState
                    }
                )
            }
        }
    }

    fun startNewConnection() {
        controlState = TopicControlState.IDLE
        wholeFailure = null
        authResolution = TopicAuthResolution.RESOLVED
        topics.keys.toList().forEach { topic ->
            update(topic) { state ->
                state.copy(
                    confirmed = false,
                    deliveryState = if (state.desired) {
                        TopicDeliveryState.NEVER_RECEIVED
                    } else {
                        state.deliveryState
                    },
                    revalidationAttempt = 0,
                    rejection = when (state.rejection) {
                        TopicRejectionReason.UNKNOWN_TOPIC,
                        TopicRejectionReason.TOPICS_DISABLED,
                        TopicRejectionReason.TOPIC_UNAVAILABLE -> null
                        else -> state.rejection
                    }
                )
            }
        }
    }

    private fun update(topic: String, body: (TopicSubscriptionState) -> TopicSubscriptionState) {
        topics[topic] = body(topics[topic] ?: TopicSubscriptionState())
    }
}
