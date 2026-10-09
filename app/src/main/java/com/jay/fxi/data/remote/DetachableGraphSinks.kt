package com.jay.fxi.data.remote

/**
 * S4 CUT-CC4a: the session's graph input, forwarded to [target] until detached. The check and the whole [target] call share
 * one lock with [detach], so once detach() returns no call reaches [target] again; a later offer is DORMANT, which the session
 * records as no loss. The [target] call must be the short synchronous hand-off its own contract already requires. Dormant:
 * nothing in production builds it until CC4b.
 */
internal class DetachableTopicGraphSink(private val target: TopicGraphSink) : TopicGraphSink {
    private val lock = Any()
    private var detached = false

    override fun tryOffer(input: TopicGraphInput): TopicGraphOffer = synchronized(lock) {
        if (detached) TopicGraphOffer.DORMANT else target.tryOffer(input)
    }

    fun detach() {
        synchronized(lock) { detached = true }
    }
}

/**
 * S4 CUT-CC4a: the graph's grant delivery, forwarded to [target] until detached; afterwards each call does nothing. Same lock
 * contract as [DetachableTopicGraphSink]. Dormant until CC4b.
 */
internal class DetachableTopicGrantSink(private val target: TopicGrantSink) : TopicGrantSink {
    private val lock = Any()
    private var detached = false

    override fun setAccess(allowed: Boolean, fence: TopicSessionFence?, origin: TopicGrantOrigin) {
        synchronized(lock) { if (!detached) target.setAccess(allowed, fence, origin) }
    }

    override fun accessRevised() {
        synchronized(lock) { if (!detached) target.accessRevised() }
    }

    fun detach() {
        synchronized(lock) { detached = true }
    }
}
