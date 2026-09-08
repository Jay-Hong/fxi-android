package com.jay.fxi.data.remote

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Everything one connection tells the layer above it, in the order it happened. */
sealed interface TopicTransportEvent {
    data object Opened : TopicTransportEvent

    data class Received(val frame: DecodedTopicFrame) : TopicTransportEvent

    /**
     * A frame the decoder refused.
     *
     * D11 splits this in two: the decoder **fails**, and the transport isolates the failure to the
     * one frame — nothing is stored, and the next frame is read as though this had not arrived.
     * Making the decoder shrug instead would put the deciding somewhere that cannot see whether
     * anything else is still working.
     *
     * **Only the length and the kind of failure travel.** Not the frame, and not the exception:
     * both decoders quote the input back in their message — `JSON input: { …` from
     * kotlinx.serialization, `Failed to parse an instant from '…'` from the date one — so carrying
     * the `Throwable` would put the body into every log that prints this, which is the thing not
     * carrying the body was for. Measured, not assumed. Found by review.
     *
     * [failure] is the exception's simple name: enough to tell a broken envelope from a bad date,
     * and nothing that came off the wire.
     */
    data class Undecodable(val length: Int, val failure: String) : TopicTransportEvent

    data class Closed(val code: Int, val reason: String) : TopicTransportEvent

    data class Failed(val cause: Throwable) : TopicTransportEvent
}

/**
 * One WebSocket connection, decoded in order.
 *
 * Deliberately small: it opens a socket, turns text into [TopicTransportEvent]s, and stops. It
 * does not reconnect, does not count attempts, and does not know what a topic is. Those belong to
 * the coordinator, which can then be tested against a fake of this.
 *
 * **One connection, once.** [open] refuses a second call rather than replacing the socket: the
 * replaced one would keep delivering into the same stream with nobody holding a handle to close
 * it. A reconnect is a new instance, which is also what makes "this connection's events" a phrase
 * that means something.
 *
 * **Order is the contract.** Frames are decoded on the reader thread that delivered them, so what
 * comes out is what came in, in sequence. The legacy service launched a coroutine per message,
 * which is the same work in an order nobody chose. What that costs is not an old price winning —
 * the merge is strictly-newer and refuses one — but the relative order of everything the merge
 * cannot referee: an ACK against the snapshot it admits, a close against the frame before it, and
 * two readings sharing an instant, where the rule is that the first one stays.
 *
 * **The reconnect budget is not reset here**, and this class has no idea one exists. D3 reopens it
 * only after a connection has held for thirty seconds; the legacy service reset it in `onOpen`,
 * which is how a server that accepts, answers and closes gets hammered forever.
 */
class TopicTransport(
    private val factory: WebSocket.Factory,
    /**
     * Injected as a function, the way the schedulers take theirs.
     *
     * `TopicFrameDecoder::decode` in production. A function rather than the class because the
     * choice below is a policy — one family of exceptions is filed as bad input and everything
     * else travels on — and a policy nobody can feed an unexpected failure to is a policy nobody
     * can check.
     */
    private val decode: (String) -> DecodedTopicFrame
) {
    private val outbox = Channel<TopicTransportEvent>(Channel.UNLIMITED)

    /**
     * Buffered without limit and completed by the connection ending.
     *
     * Dropping an event would be losing a frame nobody can ask for again; leaving the stream open
     * after [TopicTransportEvent.Closed] would leave every collector waiting on a socket that no
     * longer exists.
     */
    val events: Flow<TopicTransportEvent> = outbox.receiveAsFlow()

    private enum class Stage { NEW, OPEN, FINISHED }

    /**
     * One lock over everything about the connection's life, because none of it decides alone.
     *
     * A socket can arrive after this transport is done with — `cancel` while the factory is still
     * constructing one, a callback that was already in flight — and what to do with it depends on
     * *why* it is done. Keeping these apart is what let a cancelled transport hold a live socket,
     * and then what let a cancelled-and-already-failed one reach into OkHttp for a call it never
     * made. Found by review, twice.
     */
    private val lock = Any()
    private var stage = Stage.NEW
    private var socket: WebSocket? = null

    /** A [close] asked for before there was anything to close, kept until a socket turns up. */
    private var pendingClose: Pair<Int, String>? = null

    /**
     * Set the moment a graceful close is asked for. From then on nothing more is sent.
     *
     * Only the graceful path needs a flag: [cancel] releases the socket outright, so a send after
     * one has nothing to send on. A close leaves the socket in place — it is still finishing the
     * handshake — and that is the gap a send could otherwise slip through.
     */
    private var closeRequested = false

    /** Someone asked this connection to stop. A socket arriving afterwards is a live one to drop. */
    private var cancelRequested = false

    /**
     * A callback said the connection is already over.
     *
     * Independent of [cancelRequested], and both can be true: a collector that sees `Failed` may
     * call `cancel` at once, and a cancel during the factory's work may be followed by the
     * synchronous failure that was already on its way. Whichever order they arrive in, a socket
     * that has reported terminal must not be cancelled — OkHttp hands back one it never finished
     * building.
     */
    private var terminalObserved = false

    fun open(request: Request) {
        synchronized(lock) {
            check(stage == Stage.NEW) { "TopicTransport is single-use" }
            stage = Stage.OPEN
        }
        // Outside the lock: OkHttp may run `onOpen` — or `onFailure` — before this returns, and
        // those callbacks take the same lock. Whichever arrives first binds or ends.
        val opened = try {
            factory.newWebSocket(request, listener)
        } catch (refused: Throwable) {
            // A factory can refuse outright (a request it will not upgrade). Leaving `stage` open
            // and the stream running would strand every collector on a connection that never was.
            end(terminal = true)
            outbox.trySend(TopicTransportEvent.Failed(refused))
            outbox.close()
            throw refused
        }
        adopt(opened)
    }

    /**
     * `false` when there is no socket, when a stop has been asked for, or when OkHttp will not
     * take it.
     *
     * The close check is not the socket's: a close leaves the socket in place while the handshake
     * finishes, and one requested while the factory was still working is applied a moment later.
     * A send slipped into either gap would go out on a connection its caller had asked to end.
     * A cancel needs no flag — it releases the socket. Found by review.
     */
    fun send(text: String): Boolean {
        val open = synchronized(lock) { if (closeRequested) null else socket }
        return open?.send(text) ?: false
    }

    /**
     * Graceful: whatever OkHttp has queued still goes out, and the peer answers.
     *
     * Asked for before a socket exists — while the factory is still building one — it is kept and
     * applied to whichever socket arrives. Dropping it there was a close request that vanished and
     * left the connection running. Found by review.
     */
    fun close(code: Int = NORMAL_CLOSURE, reason: String = "") {
        val open = synchronized(lock) {
            if (stage == Stage.FINISHED) return
            closeRequested = true
            socket ?: run { pendingClose = code to reason; null }
        }
        open?.close(code, reason)
    }

    /**
     * Immediate. Nothing queued is sent and no close handshake is waited for.
     *
     * Separate from [close] because they are different needs: shutting down cleanly, and letting
     * go of a socket that is not going to answer. The stream is completed here rather than waiting
     * for a callback that a cancelled connection may never deliver.
     */
    fun cancel() {
        end(cancelled = true)?.cancel()
        outbox.close()
    }

    /**
     * Takes the socket, or refuses it because this transport is done.
     *
     * Called from both places one can arrive: the factory's return and `onOpen`. Whichever is
     * first wins, so a consumer that hears [TopicTransportEvent.Opened] and sends at once finds
     * something to send on even when the callback beat the factory.
     */
    private fun adopt(candidate: WebSocket): Boolean {
        val outcome = synchronized(lock) {
            when {
                stage == Stage.FINISHED ->
                    // Only a cancel that was asked for, and only while nothing has reported the
                    // connection already over, reaches for the socket to drop it.
                    if (cancelRequested && !terminalObserved) Adoption.REFUSED_DROP
                    else Adoption.REFUSED_LEAVE
                socket == null -> {
                    socket = candidate
                    if (pendingClose != null) Adoption.TAKEN_AND_CLOSING else Adoption.TAKEN
                }
                else -> Adoption.TAKEN
            }
        }
        when (outcome) {
            Adoption.REFUSED_DROP -> candidate.cancel()
            Adoption.REFUSED_LEAVE -> Unit
            Adoption.TAKEN_AND_CLOSING -> {
                val (code, reason) = requireNotNull(synchronized(lock) { pendingClose })
                candidate.close(code, reason)
            }
            Adoption.TAKEN -> Unit
        }
        return outcome == Adoption.TAKEN || outcome == Adoption.TAKEN_AND_CLOSING
    }

    private enum class Adoption { TAKEN, TAKEN_AND_CLOSING, REFUSED_DROP, REFUSED_LEAVE }

    /**
     * Records how this connection ended and hands back the socket, if this call is the one that
     * ended it.
     *
     * Both flags are recorded whatever the stage already was: a terminal callback after a cancel
     * still means the socket is dead, and forgetting that is how the cancel path reached for it.
     */
    private fun end(cancelled: Boolean = false, terminal: Boolean = false): WebSocket? =
        synchronized(lock) {
            if (cancelled) cancelRequested = true
            if (terminal) terminalObserved = true
            if (stage == Stage.FINISHED) return@synchronized null
            stage = Stage.FINISHED
            socket.also { socket = null }
        }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Bound before the event, so a consumer that sends the moment it hears `Opened` has a
            // socket. A transport already finished with refuses it, and says nothing.
            if (adopt(webSocket)) outbox.trySend(TopicTransportEvent.Opened)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = try {
                TopicTransportEvent.Received(decode(text))
            } catch (cause: IllegalArgumentException) {
                // The family both decoders fail in: `SerializationException` **is** an
                // `IllegalArgumentException`, and the date one throws its own subclass of it. A
                // limited family on purpose — everything outside it travels on rather than being
                // filed as a bad frame, because a fault in this app's own code answered that way
                // is a bug hidden behind a connection that looks healthy. The family is not a
                // perfect fence: an `IllegalArgumentException` raised by our own mistake inside
                // the decoder lands here too, and nothing can tell it apart from a bad date.
                undecodable(text, cause)
            }
            outbox.trySend(event)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // The peer is done sending. Completing the handshake is this side's part of it;
            // leaving it open holds a socket nobody will ever speak on again.
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // Recorded before the event goes out: a collector that answers `Closed` by calling
            // `cancel` must find a transport that already knows the socket is gone.
            end(terminal = true)
            outbox.trySend(TopicTransportEvent.Closed(code, reason))
            outbox.close()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            end(terminal = true)
            outbox.trySend(TopicTransportEvent.Failed(t))
            outbox.close()
        }
    }

    private fun undecodable(text: String, cause: Throwable) =
        TopicTransportEvent.Undecodable(text.length, cause.javaClass.simpleName)

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
