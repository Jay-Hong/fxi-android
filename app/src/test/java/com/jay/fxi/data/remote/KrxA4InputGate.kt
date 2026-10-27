package com.jay.fxi.data.remote

import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.privateValue
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.setPrivate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult

/** α-4 InputGate: holds original posted objects, then lets later connection inputs pass before release.
 * Like the α-2 gate, it changes only the producer's channel reference. The running loop retains the
 * original FIFO channel. No SessionInput, generation, acknowledgement or outcome is fabricated.
 */
internal class KrxA4InputGate(
    private val r: KrxSessionIntegrationRig,
    private val kind: String,
    private val matches: (Any) -> Boolean = { true }
) : AutoCloseable {
    private val target = checkNotNull(r.session) { "premise: ${r.row} InputGate requires the actual session" }
    @Suppress("UNCHECKED_CAST")
    private val original = privateValue(target, "inputs") as Channel<Any>
    private val held = Channel<Any>(Channel.UNLIMITED)
    private var holding = true
    private val capturedList = mutableListOf<Any>()
    private val releasedList = mutableListOf<Any>()
    val captured: List<Any> get() = capturedList.toList()
    val released: List<Any> get() = releasedList.toList()

    init {
        setPrivate(target, "inputs", object : Channel<Any> by original {
            override fun trySend(element: Any): ChannelResult<Unit> =
                if (holding && element.javaClass.simpleName == kind && matches(element)) {
                    capturedList += element
                    held.trySend(element)
                } else original.trySend(element)
        })
    }

    /** Future inputs (including the replacement socket's Opened/ACK) pass; old objects remain held. */
    fun passFutureInputs() { holding = false }

    /** Does not drain: a caller can post a real observable FIFO barrier after these exact inputs. */
    fun release() {
        holding = false
        while (true) {
            val input = held.tryReceive().getOrNull() ?: break
            check(original.trySend(input).isSuccess) { "premise: ${r.row} original session queue accepts held $kind" }
            releasedList += input
        }
    }

    /** A real current-wire decoder event is a FIFO barrier without modifying rates/store/leases.
     * Its existing session observer runs only after the loop has processed the released inputs.
     */
    fun assertProcessedBarrier() {
        @Suppress("UNCHECKED_CAST")
        val originalObserver = privateValue(target, "onUndecodable") as (Int, String) -> Unit
        var observed = 0
        val observer: (Int, String) -> Unit = { length, failure ->
            observed++; originalObserver(length, failure)
        }
        setPrivate(target, "onUndecodable", observer)
        try {
            r.wire.deliver("{a4-session-fifo-barrier")
            r.drain()
            check(observed == 1) { "premise: ${r.row} session processed the real current-wire FIFO barrier after old input release" }
        } finally { setPrivate(target, "onUndecodable", originalObserver) }
    }

    override fun close() {
        release()
        setPrivate(target, "inputs", original)
        held.close()
    }
}
