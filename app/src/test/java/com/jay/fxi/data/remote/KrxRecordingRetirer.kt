package com.jay.fxi.data.remote

/** Records the actual constructor-injected retirement calls. Deferred delivery models a delayed consumer of those requests;
 * it never fabricates a request, mutates the session/registry, or performs S6-3's unsubscribe/clear work.
 */
internal class KrxRecordingRetirer(private val now: () -> Long) : KrxSubscriptionRetirer {
    data class Received(val tuple: KrxSubscriptionRetirement, val sequence: Int, val at: Long)
    data class Delivered(val request: Received, val at: Long)
    private val received = mutableListOf<Received>()
    private val completed = mutableListOf<Delivered>()
    private val pending = ArrayDeque<Received>()
    private val journal = mutableListOf<String>()
    val requests: List<Received> get() = received.toList()
    val deliveries: List<Delivered> get() = completed.toList()
    val timeline: List<String> get() = journal.toList()
    val pendingCount: Int get() = pending.size
    var holdDelivery = false

    override fun retire(retirement: KrxSubscriptionRetirement) {
        val request = Received(retirement, received.size + 1, now())
        received += request
        journal += "received:${request.sequence}"
        if (holdDelivery) pending.addLast(request) else deliver(request)
    }
    private fun deliver(request: Received) {
        completed += Delivered(request, now())
        journal += "delivered:${request.sequence}"
    }
    fun mark(label: String) { journal += "mark:$label" }
    fun release() {
        holdDelivery = false
        while (pending.isNotEmpty()) deliver(pending.removeFirst())
    }
}
