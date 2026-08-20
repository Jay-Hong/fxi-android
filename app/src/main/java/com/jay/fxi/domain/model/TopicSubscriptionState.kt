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

/** Mutable reducer for canonical topic facts. Every exposed snapshot is immutable. */
class TopicSubscriptionStateStore {
    private val topics = linkedMapOf<String, TopicSubscriptionState>()
    private var controlState = TopicControlState.IDLE
    private var wholeFailure: TopicWholeRequestFailure? = null
    private var authResolution = TopicAuthResolution.RESOLVED

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

    fun beginRequest() {
        controlState = TopicControlState.PENDING
        wholeFailure = null
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

    fun beginAuthRefresh() {
        authResolution = TopicAuthResolution.REFRESHING
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
