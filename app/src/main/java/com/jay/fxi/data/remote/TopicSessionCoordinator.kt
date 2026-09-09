package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.remote.dto.DxyTopicMessage
import com.jay.fxi.data.remote.dto.TopicSourceEntry
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.data.remote.dto.toDollarIndex
import com.jay.fxi.data.remote.dto.toQuote
import com.jay.fxi.domain.model.TopicLeaseInput
import com.jay.fxi.domain.model.TopicLeasePolicy
import com.jay.fxi.domain.model.TopicLeaseRegistry
import com.jay.fxi.domain.model.TopicAuthResolution
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.domain.model.TopicReconnectPolicy
import com.jay.fxi.domain.model.TopicPurgeScope
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.domain.model.TopicSubscriptionStateStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which topics this build subscribes to, and the one it does not yet. */
object TopicCatalogue {
    const val TETHER = "usdt:krw"
    const val DXY = "dxy:spot"
    val FX = listOf("fx:usd-krw", "fx:jpy-krw", "fx:eur-krw")

    /**
     * The set a session asks for.
     *
     * **KRX is deliberately absent.** `ANDROID_V2_PLAN.md:849-850` defines the hook here and gives
     * turning it on to S6, because a topic in this set is a topic this build would subscribe to
     * before it has an entitlement check to refuse it with.
     */
    val DESIRED: Set<String> = (FX + listOf(TETHER, DXY)).toSet()

    /** Named so S6 has one place to add it, and so nothing else reaches for the string. */
    const val KRX_FUTURES = "krx:usd-krw-futures"
}

/**
 * Who the session is for, and which grant it is running under.
 *
 * Both halves, because they answer different questions and this slice needs both. [identity] is
 * the authenticated session — the same pair `confirmsPremiumFor` binds a premium grant to, so that
 * a *previous login of the same account* is not mistaken for the current one. [userAccessEpoch] is
 * the namespace id `AccessEpochStore` mints, an opaque string rather than a number; writing it as
 * a counter here would have been a second, disagreeing idea of the same value. Found by review.
 */
data class TopicSessionFence(
    val identity: AuthIdentityFence,
    val userAccessEpoch: String?
)

/** Why a connection ended, which decides whether another one is opened. */
enum class TopicDisconnectCause {
    /** The socket dropped or the server closed it: the automatic ladder applies. */
    UNEXPECTED,

    /** This side asked, or there is nothing to connect for. No ladder. */
    DELIBERATE
}

/**
 * Everything that can move a session, arriving on one queue.
 *
 * A queue rather than a set of entry points, because every one of these mutates state that
 * [TopicSubscriptionStateStore] does not synchronise, and because the answer to most of them
 * depends on the connection generation they belong to. One consumer, one order, one place to
 * check the generation.
 */
private sealed interface SessionInput {
    /** The app came to the foreground, or went away from it. */
    data class Foreground(val value: Boolean) : SessionInput

    data class Online(val value: Boolean) : SessionInput

    /** Premium access, and the grant it belongs to. `null` fence means nobody is signed in. */
    data class Access(val allowed: Boolean, val fence: TopicSessionFence?) : SessionInput

    data class Transport(val generation: Long, val event: TopicTransportEvent) : SessionInput

    /**
     * A command is over, however it ended.
     *
     * Carries which one, because more than one can be running on a connection, and **not** the
     * outcome: nothing here reads it yet, and it cannot be carried out of a `finally` that also
     * runs for a cancellation. D14 is the slice that gives the outcome a reader.
     */
    data class CommandDone(val generation: Long, val commandId: Long) : SessionInput

    /**
     * A command stopped because the account moved out from under it.
     *
     * Its own input because it is not an outcome: the command throws rather than returning, so
     * without this the session heard nothing — the reference stayed set, the socket stayed open,
     * and it went on pinging for a grant that was gone. Found by review.
     */
    data class CommandIdentityChanged(val generation: Long) : SessionInput

    /** A timer fired for [generation]; stale ones are dropped rather than acted on. */
    data class ConnectOverdue(val generation: Long) : SessionInput

    data class PongOverdue(val generation: Long) : SessionInput

    /**
     * A keep-alive could not be sent at all.
     *
     * Its own input rather than [PongOverdue], because that one is now checked against the pong's
     * absolute deadline — and a ping that never left has no answer coming, so waiting the ten
     * seconds out would be waiting for a reply to a question never asked. Found by review, when
     * the deadline check silently swallowed this path.
     */
    data class KeepAliveUnsendable(val generation: Long) : SessionInput

    data class StableFor(val generation: Long) : SessionInput

    data class SubscribeDue(val generation: Long) : SessionInput

    /** The lease renewal's lead time has run out; the leases are due to be asked for again. */
    data class RenewalDue(val generation: Long) : SessionInput

    /**
     * A lease's absolute deadline has arrived.
     *
     * Separate from [RenewalDue] because it is a different fact and has a different answer: one
     * says "ask again in time", the other says "the permission is gone". A renewal that failed or
     * never went out leaves this one to fire.
     */
    data class LeaseExpiryDue(val generation: Long) : SessionInput

    /**
     * A scheduled reconnection came due.
     *
     * Carries the reservation it belongs to. Cancelling the timer does not unpost an event it has
     * already queued, and without the ticket that stale event opened a second socket beside the
     * one a foreground transition had just made — leaving the first with nothing holding it, so
     * even `stop()` could not close it. Found by review.
     */
    data class ReconnectDue(val ticket: Long) : SessionInput

    data object Stop : SessionInput
}

/**
 * The one owner of a topic session: the socket, what is subscribed on it, and when to open another.
 *
 * `ANDROID_V2_PLAN.md:848-864`, the coordinator axis. This slice owns the connection's lifetime,
 * the desired set, D3's reconnection ladder, keep-alive, and routing frames into the stores. It
 * does **not** own lease renewal or the persistent-silence policy; both need this to exist first
 * and both are ordered against it, so they arrive next with the seams already here — the
 * acknowledgement and its instant reach [onAcknowledged], and foreground handling already runs
 * before any ping or resubscribe.
 *
 * **Threading.** Everything happens on [scope], which the caller must make serial — a single
 * thread or a confined dispatcher. Nothing here locks, because the store it writes does not, and
 * two coordinators sharing a store is not a supported arrangement. Commands run as their own jobs
 * on that same scope so a command waiting out a deadline does not stop the session from reading
 * its socket, and their results come back through the queue like everything else.
 *
 * **Generations.** Every connection attempt has a number, and every timer, callback and command
 * carries the number it was created under. An answer from a generation that is no longer current
 * is dropped rather than applied — the alternative is a timer from a socket that closed two
 * reconnects ago deciding what the live one does.
 */
class TopicSessionCoordinator(
    private val scope: CoroutineScope,
    private val clock: TopicCommandClock,
    /** Opens a socket. Given the generation only so a test can tell the attempts apart. */
    private val connect: (generation: Long) -> TopicTransport,
    private val credentials: TopicCommandCredentials,
    private val store: TopicSubscriptionStateStore,
    private val encode: (TopicSubscribeRequest) -> String,
    private val newRequestId: () -> String,
    private val jitter: () -> Double,
    /** Refusals, handed over as they arrive rather than when the command finishes. */
    private val onRejected: (Map<String, TopicRejectionReason>) -> Unit = {},
    /**
     * Every acknowledgement, as it arrives.
     *
     * Kept even though this slice uses only the refusals: the leases and the instant are what
     * S3k-2 renews against, and dropping them here would mean re-plumbing the seam that was the
     * point of handing an acknowledgement over early.
     */
    private val onAcknowledgement: (TopicCommandAcknowledgement) -> Unit = {},
    /** A frame the decoder refused: its length and the exception's class name, and nothing else. */
    private val onUndecodable: (length: Int, failure: String) -> Unit = { _, _ -> },
    private val desired: Set<String> = TopicCatalogue.DESIRED
) {
    private class Connection(
        val generation: Long,
        val transport: TopicTransport,
        /** The grant this socket was opened for. Nothing is sent under any other. */
        val fence: TopicSessionFence,
        /** Whether this was the session's first attempt at a socket, which subscribes at once. */
        val firstAttempt: Boolean
    ) {
        var opened = false
        var ended = false
        var pongAnswered = false
        val timers = mutableListOf<Job>()

        /**
         * Every command running on this socket, by id.
         *
         * More than one, because a lease with no more than the renewal's lead time left on it is
         * due the moment it is acknowledged — while the first delivery is still waiting out its
         * forty-five seconds. (A `duration 0` is not that case: the server has declared that one
         * finished, and the expiry takes the connection.) Their answers are told apart by
         * `request_id`, which the commands check themselves.
         */
        val commands = linkedMapOf<Long, RunningCommand>()

        /** This connection's leases. Per connection, so ending one forgets them all. */
        val leases = TopicLeaseRegistry()

        /** What the next renewal should ask for, as of the last acknowledgement. */
        var renewalScope: Set<String> = emptySet()

        var renewalTimer: Job? = null
        var expiryTimer: Job? = null

        /** Both absolute, on the injected clock, so a wait that overslept is still late. */
        var connectDueAtMillis = 0L
        var pongDueAtMillis: Long? = null
    }

    private class RunningCommand(val command: TopicSubscribeCommand) {
        var job: Job? = null
    }

    private val inputs = Channel<SessionInput>(Channel.UNLIMITED)

    private val _rates = MutableStateFlow(TopicRates())

    /** Everything the topics have said, under the strictly-newer rule. */
    val rates: StateFlow<TopicRates> = _rates.asStateFlow()

    private var loop: Job? = null
    private var generation = 0L
    private var connection: Connection? = null
    private var reconnectJob: Job? = null
    private var reconnectTicket = 0L
    private var reconnectAttempt = 0
    private var commandSerial = 0L
    private var stopped = false

    /**
     * The grant this session has been told it may not have, if it has been told.
     *
     * Held against the fence rather than the connection: a `premium_required` is a fact about the
     * account, and a reconnection used to clear it because the flag lived on the socket. Found by
     * review.
     */
    private var refusedFor: TopicSessionFence? = null

    /**
     * The grant whose credential turned out to belong to somebody else.
     *
     * A different reason from [refusedFor] and worth keeping apart: that one is the server saying
     * this account may not have the data, this one is the account not being signed in any more.
     * Closing the connection alone was not enough — `wanted()` stayed true, so the next foreground
     * return or network flip opened another socket for the same dead grant. Found by review.
     */
    private var identityLostFor: TopicSessionFence? = null

    /**
     * Whether this session has *tried* to connect before.
     *
     * Three near-misses live in this one boolean, and it is none of them. Not "is the reconnection
     * budget fresh" — a network or foreground transition resets the budget, and keying the spread
     * on that made those reconnections look like first connections. Not "has a socket ever
     * opened" either: a first attempt that fails before `Opened` is still followed by a
     * reconnection, and that one needs spreading too. Recorded **before** `connect()`, so the
     * attempt that never returns a transport counts as one. Found by review, twice.
     */
    private var everAttempted = false
    private var foreground = false
    private var online = false
    private var access = false
    private var fence: TopicSessionFence? = null

    fun start() {
        if (loop != null) return
        loop = scope.launch {
            try {
                for (input in inputs) handle(input)
            } finally {
                // Reached when the scope is cancelled too. Cancelling the socket alone was not
                // enough: the store went on reading as confirmed on a connection that no longer
                // existed, which is the thing `end` exists to prevent. Found by review.
                shutDown()
            }
        }
    }

    fun setForeground(value: Boolean) = post(SessionInput.Foreground(value))

    fun setOnline(value: Boolean) = post(SessionInput.Online(value))

    fun setAccess(allowed: Boolean, fence: TopicSessionFence?) =
        post(SessionInput.Access(allowed, fence))

    fun stop() = post(SessionInput.Stop)

    private fun post(input: SessionInput) {
        inputs.trySend(input)
    }

    // ---- the loop -------------------------------------------------------------------------

    private suspend fun handle(input: SessionInput) {
        if (stopped) return
        when (input) {
            is SessionInput.Foreground -> {
                // Only a change. A platform that announces the same state twice must not be able
                // to reopen the reconnection budget by repeating itself. Found by review.
                if (foreground == input.value) return
                foreground = input.value
                // Only *coming back* is a trigger. Leaving used to run `reconsider` too, and a
                // reconsider with no connection cancels the backoff and opens at once — so going
                // to the background was a way to skip the ladder. Found by review.
                if (input.value) {
                    // Before anything else: a connection whose deadline passed while the app was
                    // away is not one to keep. The waits are relative and the clock is not, so
                    // coming back is where the difference shows — `reconsider` would otherwise
                    // see a live connection and return, keeping a socket whose pong was already
                    // overdue. Found by review.
                    connection?.let { live ->
                        // Leases first, and **returning** if one had lapsed. `ANDROID_V2_PLAN.md`
                        // says the expiry is forced before any ping or resubscribe, and iOS
                        // returns from its foreground handler when it finds one
                        // (`WebSocketService.swift` `willEnterForegroundNotification`). The
                        // reconnection is already on its way — `enforceLeaseExpiry` ends the
                        // connection, which schedules it on the ladder — and `reconsider` would
                        // replace that spread with every returning client connecting at once.
                        if (enforceLeaseExpiry(live)) return
                        if (expired(live)) end(live, TopicDisconnectCause.UNEXPECTED)
                    }
                    reconsider(budgetIsFresh = true)
                }
            }

            is SessionInput.Online -> {
                if (online == input.value) return
                online = input.value
                reconsider(budgetIsFresh = input.value)
            }

            is SessionInput.Access -> {
                // The same grant, said again, is not a new trigger — repeating it during a
                // backoff used to open a socket immediately. Found by review.
                if (access == input.allowed && fence == input.fence) return
                val moved = fence != input.fence
                access = input.allowed
                fence = input.fence
                if (moved) {
                    // A different grant is a different session. The socket was authenticated as
                    // the grant that is gone, so it goes with it — leaving it open would carry
                    // one account's subscription into another's session. What it received is not
                    // evidence for the new grant either, and its prices are not the new prices.
                    connection?.let { end(it, TopicDisconnectCause.DELIBERATE) }
                    cancelReconnect()
                    _rates.value = TopicRates()
                    everAttempted = false
                    // `setDesired(false)` is not enough: it keeps the receive generation, because
                    // a user losing interest in a topic has not unseen its frames. A grant change
                    // has — those frames were another session's.
                    store.purge(TopicPurgeScope.All)
                }
                reconsider(budgetIsFresh = true)
            }

            is SessionInput.Transport ->
                current(input.generation)?.let { onTransportEvent(it, input.event) }

            is SessionInput.CommandDone -> current(input.generation)?.let { live ->
                live.commands.remove(input.commandId)
                // Deliberately nothing else. Re-sending `desired − confirmed` here would hand a
                // terminal refusal or a spent budget a fresh one on the next lap, which is the
                // ceiling those two exist to be. A new command needs a new reason: a reconnection,
                // or a trigger the store's own retry rules allow. Found by review.
            }

            is SessionInput.CommandIdentityChanged -> current(input.generation)?.let { live ->
                // Deliberate: the socket was opened for a grant that is no longer signed in, so
                // there is nothing to reconnect *to* until an `Access` says who is. No ladder,
                // and no new command. The commands are left for `end` to cancel: this connection
                // may be running a renewal beside the one that threw.
                identityLostFor = live.fence
                end(live, TopicDisconnectCause.DELIBERATE)
                cancelReconnect()
            }

            is SessionInput.ConnectOverdue -> current(input.generation)?.let { live ->
                if (!live.opened && clock.nowMillis() >= live.connectDueAtMillis) {
                    end(live, TopicDisconnectCause.UNEXPECTED)
                }
            }

            is SessionInput.KeepAliveUnsendable -> current(input.generation)?.let {
                end(it, TopicDisconnectCause.UNEXPECTED)
            }

            is SessionInput.PongOverdue -> current(input.generation)?.let { live ->
                // Re-read against the clock rather than trusted to have slept the right amount:
                // the waits are relative and the clock counts deep sleep, so a wait that spanned
                // it comes back long past its deadline — and, before this, a pong that arrived in
                // that gap was accepted as though it were on time. Found by review.
                val due = live.pongDueAtMillis
                if (due == null || clock.nowMillis() >= due) end(live, TopicDisconnectCause.UNEXPECTED)
            }

            is SessionInput.StableFor -> current(input.generation)?.let { live ->
                // Thirty seconds of an open connection is what reopens the **automatic**
                // ladder. Not a frame — the server sends legacy `rates` to everyone the moment
                // they connect. A lifecycle transition reopens it too, but that is a different
                // trigger and `reconsider` owns it; "and only that" would be false.
                if (live.opened) reconnectAttempt = 0
            }

            is SessionInput.SubscribeDue ->
                current(input.generation)?.let { live -> subscribe(live) }

            // The deadline is checked first. A renewal timer that woke after the deadline it was
            // meant to beat — the clock counts sleep and the wait does not — would otherwise send
            // a subscribe for a permission that had already lapsed, and the answer would re-grant
            // it. Reproduced by review with a 181-second lease and a clock a second past it.
            is SessionInput.RenewalDue -> current(input.generation)?.let { live ->
                if (!enforceLeaseExpiry(live)) startRenewal(live)
            }

            is SessionInput.LeaseExpiryDue ->
                current(input.generation)?.let { live -> enforceLeaseExpiry(live) }

            is SessionInput.ReconnectDue -> {
                if (input.ticket != reconnectTicket) return
                reconnectJob = null
                if (wanted() && connection == null) open()
            }

            SessionInput.Stop -> {
                // A flag, not only a cancellation. `Job.cancel()` takes effect at the next
                // suspension, and a queue that still has items lets `receive()` return them
                // without suspending — so a stopped session went on to process buffered inputs
                // and reconnect. Found by review.
                stopped = true
                shutDown()
                inputs.close()
            }
        }
    }

    /** Whether either of this connection's absolute deadlines has already passed. */
    private fun expired(live: Connection): Boolean {
        val now = clock.nowMillis()
        if (!live.opened) return now >= live.connectDueAtMillis
        val pongDue = live.pongDueAtMillis ?: return false
        return !live.pongAnswered && now >= pongDue
    }

    /** The live connection, or `null` when [generation] belongs to one already gone. */
    private fun current(generation: Long): Connection? =
        connection?.takeIf { it.generation == generation && !it.ended }

    /**
     * Whether a socket should be open at all.
     *
     * **Foreground is not one of the conditions**, deliberately. A rates screen that is merely
     * behind another app has not stopped wanting prices, and closing on every app switch buys a
     * reconnection — with its subscribe, its ACK and its delivery deadline — for each of them.
     * What foreground *is* for is the other direction. Coming back is a fresh trigger, so a
     * session that exhausted its ladder while away tries again — and it is also where a
     * connection that outlived one of its deadlines is noticed, because a socket that stopped
     * working while the app was suspended does **not** reliably announce itself as a dropped one.
     * Assuming it did is what left an expired connection standing. Found by review.
     */
    private fun wanted(): Boolean =
        access && online && fence != null && desired.isNotEmpty() &&
            fence != refusedFor && fence != identityLostFor

    private fun reconsider(budgetIsFresh: Boolean) {
        if (!wanted()) {
            connection?.let { end(it, TopicDisconnectCause.DELIBERATE) }
            cancelReconnect()
            return
        }
        if (connection != null) return
        // A lifecycle transition is a different trigger from the automatic ladder, and starts
        // again from zero — `TopicReconnectPolicy` says so, and a user who returns to a screen
        // should not inherit the exhaustion of a socket that failed while they were away.
        if (budgetIsFresh) reconnectAttempt = 0
        cancelReconnect()
        open()
    }

    private fun open() {
        val number = ++generation
        val firstAttempt = !everAttempted
        everAttempted = true
        val transport = try {
            connect(number)
        } catch (refused: Throwable) {
            // `TopicTransport.open` reports a factory failure twice — a `Failed` event *and* the
            // exception it rethrows — and counting both would spend two rungs of the ladder for
            // one attempt. Only one of them can reach this session: the transport is never handed
            // back, so nothing collects its events, and the exception is the whole report.
            scheduleReconnect()
            return
        }
        val live = Connection(number, transport, fence ?: return, firstAttempt)
        connection = live
        live.timers += scope.launch {
            transport.events.collect { post(SessionInput.Transport(number, it)) }
        }
        live.connectDueAtMillis = clock.nowMillis() + CONNECT_TIMEOUT.inWholeMilliseconds
        live.timers += after(CONNECT_TIMEOUT) { post(SessionInput.ConnectOverdue(number)) }
    }

    private fun onTransportEvent(live: Connection, event: TopicTransportEvent) {
        when (event) {
            TopicTransportEvent.Opened -> {
                // A socket that opens after its own deadline is late, not open. Accepting it
                // silently disarmed the timeout, because that one only fires while `!opened`.
                // Found by review.
                if (clock.nowMillis() >= live.connectDueAtMillis) {
                    end(live, TopicDisconnectCause.UNEXPECTED)
                    return
                }
                live.opened = true
                live.timers += after(TopicReconnectPolicy.STABILITY_RESET) {
                    post(SessionInput.StableFor(live.generation))
                }
                // A first connection asks at once; a reconnection spreads, because every client
                // that lost the same server comes back at the same moment.
                val spread = if (live.firstAttempt) Duration.ZERO else resubscribeJitter()
                live.timers += after(spread) { post(SessionInput.SubscribeDue(live.generation)) }
                startKeepAlive(live)
            }

            is TopicTransportEvent.Received -> onFrame(live, event.frame)

            // The connection survives one frame it could not read (D11) — and the two things the
            // transport was careful to keep are handed on rather than ending here. What is left
            // out is the body and the exception's message, both of which quote the frame back;
            // what travels is how long it was and which exception class refused it. Found by
            // review.
            is TopicTransportEvent.Undecodable -> onUndecodable(event.length, event.failure)

            is TopicTransportEvent.Closed -> end(live, TopicDisconnectCause.UNEXPECTED)
            is TopicTransportEvent.Failed -> end(live, TopicDisconnectCause.UNEXPECTED)
        }
    }

    private fun onFrame(live: Connection, frame: DecodedTopicFrame) {
        when (frame) {
            // Handed to every running command, because the coordinator does not know whose
            // answer this is — each one checks the `request_id` against its own and ignores the
            // rest. One place makes that judgment, and it is the place that sent the request.
            is DecodedTopicFrame.Acknowledgement,
            is DecodedTopicFrame.RequestFailure ->
                live.commands.values.toList().forEach { it.command.deliver(frame) }

            is DecodedTopicFrame.Tether ->
                receive(live, TopicCatalogue.TETHER, frame.value.data.allEntries)

            is DecodedTopicFrame.Fx ->
                receive(live, frame.value.topic, frame.value.data.allEntries)

            is DecodedTopicFrame.Dxy -> receiveIndex(live, frame.value)

            // S6 owns turning KRX on. Until then a frame for it is not this session's, and
            // counting it would let an unentitled topic satisfy a watchdog.
            is DecodedTopicFrame.Krx -> Unit

            // Keep-alive only. The pong deadline is cleared by a pong, and not by any frame that
            // happens to arrive: a server that streams prices while its control plane is wedged
            // is exactly what the deadline is for.
            // A pong that arrives after its deadline is a late answer, not an answer. The
            // deadline is absolute, so sleep does not buy the peer more time to reply in.
            DecodedTopicFrame.Pong -> {
                val due = live.pongDueAtMillis
                if (due != null && clock.nowMillis() < due) live.pongAnswered = true
            }

            // The legacy payload (I1), and anything this build does not model.
            DecodedTopicFrame.NotTopic -> Unit
            is DecodedTopicFrame.Unsupported -> Unit
        }
    }

    /**
     * Whether a data frame for [topic] may be applied at all.
     *
     * iOS asks the same three questions before it touches a topic's data
     * (`WebSocketService.swift` `canAcceptTopicFrame`): is it wanted, was it refused, and did the
     * credential fail. The two that were missing here let a topic the server had already refused,
     * or one arriving after an `invalid_token`, count as delivery — the watchdog cannot tell that
     * frame from a good one.
     *
     * The lease is the fourth, and it is **this side's own question**: an expiry the client has
     * already reached is a permission it no longer believes in, whatever the socket keeps sending.
     * It is not a new behaviour — [enforceLeaseExpiry] is the same enforcement the expiry timer,
     * the foreground return, the renewal and the acknowledgement already reach; a frame is simply
     * another thing that can arrive first. iOS enforces from its timer and its foreground return
     * only, so this side notices the same fact sooner.
     *
     * **The two deadlines need not coincide.** The server filters expired leases before
     * publishing (`app/topic_dispatcher.py:280`), but it floors the duration it puts on the wire
     * (`:77`) while this side counts from when the acknowledgement is processed, and the deadline is
     * read from a clock that counts sleep while the timer waiting for it is a relative wait that
     * does not (`TopicRequestPolicy.kt:17-25`). Which side is ahead, and by how much, is not
     * something measured here.
     *
     * The rejection read here is the store's, and an acknowledgement listing the topic as active
     * clears it (`TopicSubscriptionState.kt:250`).
     */
    private fun accepts(live: Connection, topic: String): Boolean {
        if (topic !in desired) return false
        if (enforceLeaseExpiry(live)) return false
        val snapshot = store.snapshot
        if (snapshot.authResolution == TopicAuthResolution.FAILED) return false
        return snapshot.stateFor(topic).rejection == null
    }

    /**
     * A data frame becomes prices and, if any of it survived, one unit of receive evidence.
     *
     * The order matters and there is nothing suspending in the middle of it: sanitise, merge,
     * then record. A frame whose every entry failed validation is not a delivery — it is a
     * publisher fault that would otherwise satisfy the very watchdog meant to notice it. A frame
     * that merged nothing because everything in it was older **is** a delivery: the socket
     * answered, which is all the generation counts.
     */
    private fun receive(live: Connection, topic: String, entries: List<TopicSourceEntry>) {
        if (!accepts(live, topic)) return
        val quotes: List<TopicQuote> = entries.mapNotNull { it.toQuote() }
        // No usable price, no delivery — whichever way the payload got that way. The narrow case
        // that decides it is a tether frame carrying only `usd_krw_futures`: D8 took that field
        // out of the DTO, so it is swallowed as an unknown key and what arrives here is
        // indistinguishable from an empty snapshot. `ANDROID_V2_PLAN.md:889` says KRX must not
        // satisfy tether delivery, and since the two cannot be told apart, neither counts.
        // Found by review.
        if (quotes.isEmpty()) return
        _rates.value = _rates.value.merge(quotes)
        store.recordFrame(topic)
    }

    private fun receiveIndex(live: Connection, message: DxyTopicMessage) {
        if (!accepts(live, TopicCatalogue.DXY)) return
        // The index has one slot rather than a list, so "everything failed validation" and
        // "nothing arrived" are the same frame here: either way there is no reading to record.
        val index = message.data.dxy.toDollarIndex() ?: return
        _rates.value = _rates.value.merge(index)
        store.recordFrame(TopicCatalogue.DXY)
    }

    // ---- subscribing ----------------------------------------------------------------------

    private fun subscribe(live: Connection) {
        if (live.commands.isNotEmpty()) return
        desired.forEach { store.setDesired(true, it) }
        startCommand(live, TopicCommandPurpose.FIRST_DELIVERY) { desired }
    }

    /**
     * The renewal this connection's leases are due for.
     *
     * Narrowed to what is still wanted: a topic dropped from the desired set since the
     * acknowledgement is not one to re-authenticate. An empty result asks for nothing rather than
     * sending an empty subscribe.
     *
     * **One request is open at a time**, and today that is a property of when this runs rather
     * than a rule enforced here: the renewal timer is armed by the very acknowledgement that ends
     * the previous command's control phase, so nothing is waiting on the wire when this fires. The
     * store keeps a single open request — [TopicSubscriptionStateStore.beginRequest] hands the
     * ticket to the newest caller — so whichever slice next starts a command from somewhere else
     * (D14's revalidation is the one ordered next) has to make that rule explicit instead.
     */
    private fun startRenewal(live: Connection) {
        if (live.renewalScope.intersect(desired).isEmpty()) return
        // The session never reads the outcome, so the purpose looks unobservable from here — and
        // is not: the command asks the injected clock for its delivery window, and a renewal that
        // opened one would be visible as a wait no other part of a session makes. That is what
        // `a renewal does not open a delivery window` holds. Found by review, after this comment
        // had claimed the opposite.
        startCommand(live, TopicCommandPurpose.LEASE_RENEWAL) { live.renewalScope intersect desired }
    }

    /**
     * Starts one command on this connection and gives it back through the queue.
     *
     * A **new** command every time, deliberately: re-running one that failed would hand it the
     * three attempts it has already spent, which is the ceiling those attempts exist to be.
     */
    private fun startCommand(
        live: Connection,
        purpose: TopicCommandPurpose,
        topics: () -> Set<String>
    ) {
        val id = ++commandSerial
        // The transport is captured here rather than read from the field at send time. A command
        // that outlived its connection would otherwise write to whichever socket is current, which
        // is a subscribe sent on a connection that never asked for it. Found by review.
        val transport = live.transport
        val command = TopicSubscribeCommand(
            purpose = purpose,
            store = store,
            credentials = boundTo(live.fence),
            clock = clock,
            encode = encode,
            send = { text -> transport.send(text) },
            newRequestId = newRequestId,
            jitter = jitter,
            scope = { if (current(live.generation) == null) emptySet() else topics() },
            onAcknowledged = { ack -> onAcknowledged(live, id, ack) }
        )
        val running = RunningCommand(command)
        live.commands[id] = running
        // `finally`, so a command that ends by cancellation is still taken off the connection. A
        // listener throwing `CancellationException` out of the acknowledgement callback used to
        // leave the entry behind for good: the socket stayed up, and every later control frame was
        // handed to a command whose unbounded inbox no longer had a reader. No test here catches
        // that — `an acknowledgement listener that throws leaves the session working` checks the
        // session, not the leftover — so what stands behind this line is review's reproduction
        // rather than a failing test. Found by review.
        running.job = scope.launch {
            try {
                command.run()
            } catch (moved: AuthIdentityChangedException) {
                post(SessionInput.CommandIdentityChanged(live.generation))
            } finally {
                post(SessionInput.CommandDone(live.generation, id))
            }
        }
    }

    /**
     * The credentials, refused unless they belong to the grant this socket was opened for.
     *
     * The provider answers with whoever is signed in *now*, and that is not necessarily who this
     * connection is for: a subscribe went out carrying `u1`'s token on a session opened for `u2`.
     * `AuthIdentityChangedException` is the right refusal — the command treats it as the
     * cancellation it is and does not retry around it. Found by review.
     */
    private fun boundTo(bound: TopicSessionFence) = object : TopicCommandCredentials {
        override suspend fun currentSnapshot(): AuthSnapshot =
            credentials.currentSnapshot().takeIf { it.fence == bound.identity }
                ?: throw AuthIdentityChangedException()

        override suspend fun refreshAfterUnauthorized(rejected: AuthSnapshot): AuthSnapshot? {
            // A `null` from the provider means the replay is unsafe, and the command answers that
            // with `AUTH_REPLAY_SPENT`. A credential for somebody *else* is a different thing and
            // must not be folded into it — `takeIf` did exactly that. Found by review.
            val refreshed = credentials.refreshAfterUnauthorized(rejected) ?: return null
            if (refreshed.fence != bound.identity) throw AuthIdentityChangedException()
            return refreshed
        }
    }

    /**
     * A refusal is acted on now, not when the command's delivery deadline runs out.
     *
     * Forty-five seconds is how long the *other* topics have to arrive; it is not how long an
     * account should be shown prices it is not entitled to. `premium_required` also stops this
     * session asking again — the answer will not change while the grant does not.
     */
    private fun onAcknowledged(
        live: Connection,
        commandId: Long,
        ack: TopicCommandAcknowledgement
    ) {
        // Settled **before** anyone outside is told, and against the grant this connection was
        // opened for rather than whatever is current: a listener that throws must not be able to
        // leave the session still asking under an account that has been refused. Found by review.
        if (ack.rejected.values.any { it == TopicRejectionReason.PREMIUM_REQUIRED }) {
            refusedFor = live.fence
            end(live, TopicDisconnectCause.DELIBERATE)
            cancelReconnect()
        }
        // Also before, and only while the connection is still standing: the leases belong to this
        // socket, and re-arming timers on one that has just been ended would schedule work for a
        // connection nobody is holding. A deadline that passed while this answer was in flight is
        // enforced **instead of** applying it — a new grant must not carry a subscription past the
        // absolute expiry of the one it replaces, which is what "hard" means. Found by review.
        current(live.generation)?.let { still ->
            if (!enforceLeaseExpiry(still)) applyLeases(still, ack)
        }

        onAcknowledgement(ack)
        if (ack.rejected.isNotEmpty()) onRejected(ack.rejected)
    }

    // ---- leases -----------------------------------------------------------------------------

    /**
     * One acknowledgement's leases, and the two timers they set.
     *
     * Both are re-armed on **every** acknowledgement, including one carrying no leases at all —
     * that is the server saying there is nothing left to renew or to expire, and a timer from a
     * previous answer left running would act on a set that no longer exists.
     */
    private fun applyLeases(live: Connection, ack: TopicCommandAcknowledgement) {
        val update = live.leases.apply(
            acknowledgedAtMillis = ack.acknowledgedAtMillis,
            leases = ack.leases.map {
                TopicLeaseInput(it.topic, it.leaseId, it.durationSeconds)
            },
            jitter = leaseJitter()
        )
        live.renewalScope = update.renewalScope

        // Expiry first, the order iOS arms them in.
        //
        // A `duration 0` makes both come due at this instant, and the outcome is the expiry — by
        // whichever of them is answered first, because each ends the connection: the expiry
        // directly, and the renewal because its handler reads the deadline before it starts
        // anything. iOS lands in the same place and says so — "실제 서비스는 즉시 만료를
        // 집행하지만" — reaching the re-authentication only through an injected sleep that holds
        // the expiry back. The immediate re-authentication is the answer for a lease that is
        // *nearly* over, not for one the server has already declared finished.
        //
        // ⚠️ Under this session's one serial queue, swapping these two lines changes nothing
        // observable — mutation says so. That is a property of how the two are dispatched here,
        // not a guarantee to lean on: keep the order for the parity it states.
        live.expiryTimer?.cancel()
        live.expiryTimer = null
        live.leases.earliestExpiryMillis()?.let { deadline ->
            val wait = (deadline - clock.nowMillis()).coerceAtLeast(0)
            live.expiryTimer =
                after(wait.milliseconds) { post(SessionInput.LeaseExpiryDue(live.generation)) }
        }

        live.renewalTimer?.cancel()
        live.renewalTimer = null
        update.renewAfter?.let { wait ->
            live.renewalTimer = after(wait) { post(SessionInput.RenewalDue(live.generation)) }
        }
    }

    /**
     * Lets go of every lease whose deadline has arrived, and takes the connection with them.
     *
     * The same judgment for every caller — the timer, the foreground return, a renewal coming due,
     * an acknowledgement being applied, and a data frame arriving — because the timer's wait is
     * relative and the deadline is not: an app suspended past the deadline has a timer that has
     * not fired yet, and whichever of the others runs first is where that is noticed.
     *
     * Ending the connection is what recovers: desired survives it, so the reconnection resubscribes
     * and the server issues new leases. Several deadlines arriving together still end it once,
     * because [end] is idempotent and the connection is gone after the first — which is also why
     * this needs no equivalent of iOS's `leaseExpiryReconnectIssuedGeneration`.
     *
     * **One thing iOS does here is deliberately not copied.** It marks the expired topics degraded
     * and publishes that snapshot (`WebSocketService.swift:1383-1384`) before reconnecting, so its
     * listeners see the degradation for the moment it lasts. Writing the same thing here would be
     * invisible: nothing publishes between the write and [end]'s `clearConnectionState`, which puts
     * a still-wanted topic back to never-received. The write belongs with the observer, and the
     * observer arrives with the screen that reads this store. Found by review, which measured the
     * difference rather than accepting the parity argument for it.
     */
    private fun enforceLeaseExpiry(live: Connection): Boolean {
        if (live.leases.expiredAt(clock.nowMillis()).isEmpty()) return false
        end(live, TopicDisconnectCause.UNEXPECTED)
        return true
    }

    /** The draw the lease policy wants: whole seconds, `0..60`, and only ever subtracted. */
    private fun leaseJitter(): Long =
        (jitter().coerceIn(0.0, 1.0) * TopicLeasePolicy.JITTER_UPPER_BOUND).toLong()

    // ---- keep-alive and endings -------------------------------------------------------------

    private fun startKeepAlive(live: Connection) {
        live.timers += scope.launch {
            while (true) {
                clock.sleep(PING_INTERVAL)
                if (current(live.generation) == null) return@launch
                live.pongAnswered = false
                live.pongDueAtMillis = clock.nowMillis() + PONG_TIMEOUT.inWholeMilliseconds
                if (!live.transport.send(PING)) {
                    post(SessionInput.KeepAliveUnsendable(live.generation))
                    return@launch
                }
                clock.sleep(PONG_TIMEOUT)
                if (current(live.generation) == null) return@launch
                if (!live.pongAnswered) {
                    post(SessionInput.PongOverdue(live.generation))
                    return@launch
                }
            }
        }
    }

    private fun end(live: Connection, cause: TopicDisconnectCause) {
        if (live.ended) return
        live.ended = true
        live.commands.values.forEach { it.job?.cancel() }
        live.renewalTimer?.cancel()
        live.expiryTimer?.cancel()
        live.timers.forEach(Job::cancel)
        live.transport.cancel()
        if (connection === live) connection = null

        // Desired survives a connection; everything the server told us about it does not. Called
        // at the *end* rather than at the next open, because between the two there is no
        // subscription — a topic still reading as confirmed there is confirmed on a dead socket.
        store.clearConnectionState()

        if (cause == TopicDisconnectCause.UNEXPECTED && wanted()) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectJob != null) return
        val next = TopicReconnectPolicy.nextAttempt(reconnectAttempt) ?: return
        val delay = TopicReconnectPolicy.delayFor(next, jitter()) ?: return
        reconnectAttempt = next
        val ticket = ++reconnectTicket
        reconnectJob = after(delay) { post(SessionInput.ReconnectDue(ticket)) }
    }

    /** Cancels the timer **and** invalidates anything it has already put on the queue. */
    /**
     * Everything this session holds, let go of once.
     *
     * Shared by `Stop` and by the loop ending for any other reason, so a cancelled scope leaves
     * the same state behind as an orderly stop rather than a socket-shaped hole.
     */
    private fun shutDown() {
        connection?.let { end(it, TopicDisconnectCause.DELIBERATE) }
        cancelReconnect()
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectTicket++
    }

    private fun resubscribeJitter(): Duration =
        RESUBSCRIBE_JITTER_MAX * jitter().coerceIn(0.0, 1.0)

    private fun after(delay: Duration, body: () -> Unit): Job = scope.launch {
        clock.sleep(delay)
        body()
    }

    private companion object {
        /** iOS `connectionTimeout`. Separate from a command's ACK deadline: different failure. */
        val CONNECT_TIMEOUT = 15.seconds
        val PING_INTERVAL = 30.seconds
        val PONG_TIMEOUT = 10.seconds
        val RESUBSCRIBE_JITTER_MAX = 2.seconds
        const val PING = "ping"
    }
}
