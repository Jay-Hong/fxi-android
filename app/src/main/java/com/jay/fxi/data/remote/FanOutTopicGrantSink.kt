package com.jay.fxi.data.remote

/**
 * S4 CUT-P3a: hands every grant call to [first] and then to [second], in the deliverer's order, on the caller's thread.
 * Both delegates must provide short, synchronous, thread-safe hand-offs without I/O or waiting for loop execution.
 * The graph bridge may publish its in-memory fence before enqueueing its notification.
 * Exceptions from either delegate propagate unchanged: if [first] throws, [second] is not called; neither call is retried.
 * Dormant: nothing in production constructs it.
 */
internal class FanOutTopicGrantSink(
    private val first: TopicGrantSink,
    private val second: TopicGrantSink
) : TopicGrantSink {
    override fun setAccess(allowed: Boolean, fence: TopicSessionFence?, origin: TopicGrantOrigin) {
        first.setAccess(allowed, fence, origin)
        second.setAccess(allowed, fence, origin)
    }

    override fun accessRevised() {
        first.accessRevised()
        second.accessRevised()
    }
}
