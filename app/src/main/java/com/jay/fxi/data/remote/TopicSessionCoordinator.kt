package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.remote.dto.DxyTopicMessage
import com.jay.fxi.data.remote.dto.TopicSourceEntry
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.data.remote.dto.toDollarIndex
import com.jay.fxi.data.remote.dto.toQuote
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.domain.model.TopicReconnectPolicy
import com.jay.fxi.domain.model.TopicPurgeScope
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.domain.model.TopicSubscriptionStateStore
import kotlin.time.Duration
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

    data class CommandDone(val generation: Long, val outcome: TopicCommandOutcome) : SessionInput

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
        var command: TopicSubscribeCommand? = null
        var commandJob: Job? = null

        /** Both absolute, on the injected clock, so a wait that overslept is still late. */
        var connectDueAtMillis = 0L
        var pongDueAtMillis: Long? = null
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
                    connection?.let { if (expired(it)) end(it, TopicDisconnectCause.UNEXPECTED) }
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
                live.command = null
                live.commandJob = null
                // Deliberately nothing else. Re-sending `desired − confirmed` here would hand a
                // terminal refusal or a spent budget a fresh one on the next lap, which is the
                // ceiling those two exist to be. A new command needs a new reason: a reconnection,
                // or a trigger the store's own retry rules allow. Found by review.
            }

            is SessionInput.CommandIdentityChanged -> current(input.generation)?.let { live ->
                // Deliberate: the socket was opened for a grant that is no longer signed in, so
                // there is nothing to reconnect *to* until an `Access` says who is. No ladder,
                // and no new command.
                live.command = null
                live.commandJob = null
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
            is DecodedTopicFrame.Acknowledgement,
            is DecodedTopicFrame.RequestFailure -> live.command?.deliver(frame)

            is DecodedTopicFrame.Tether ->
                receive(TopicCatalogue.TETHER, frame.value.data.allEntries)

            is DecodedTopicFrame.Fx -> receive(frame.value.topic, frame.value.data.allEntries)
            is DecodedTopicFrame.Dxy -> receiveIndex(frame.value)

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
     * A data frame becomes prices and, if any of it survived, one unit of receive evidence.
     *
     * The order matters and there is nothing suspending in the middle of it: sanitise, merge,
     * then record. A frame whose every entry failed validation is not a delivery — it is a
     * publisher fault that would otherwise satisfy the very watchdog meant to notice it. A frame
     * that merged nothing because everything in it was older **is** a delivery: the socket
     * answered, which is all the generation counts.
     */
    private fun receive(topic: String, entries: List<TopicSourceEntry>) {
        if (topic !in desired) return
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

    private fun receiveIndex(message: DxyTopicMessage) {
        if (TopicCatalogue.DXY !in desired) return
        // The index has one slot rather than a list, so "everything failed validation" and
        // "nothing arrived" are the same frame here: either way there is no reading to record.
        val index = message.data.dxy.toDollarIndex() ?: return
        _rates.value = _rates.value.merge(index)
        store.recordFrame(TopicCatalogue.DXY)
    }

    // ---- subscribing ----------------------------------------------------------------------

    private fun subscribe(live: Connection) {
        if (live.command != null) return
        desired.forEach { store.setDesired(true, it) }

        // The transport is captured here rather than read from the field at send time. A command
        // that outlived its connection would otherwise write to whichever socket is current, which
        // is a subscribe sent on a connection that never asked for it. Found by review.
        val transport = live.transport
        val command = TopicSubscribeCommand(
            purpose = TopicCommandPurpose.FIRST_DELIVERY,
            store = store,
            credentials = boundTo(live.fence),
            clock = clock,
            encode = encode,
            send = { text -> transport.send(text) },
            newRequestId = newRequestId,
            jitter = jitter,
            scope = { if (current(live.generation) == null) emptySet() else desired },
            onAcknowledged = { ack -> onAcknowledged(live, ack) }
        )
        live.command = command
        live.commandJob = scope.launch {
            val outcome = try {
                command.run()
            } catch (moved: AuthIdentityChangedException) {
                post(SessionInput.CommandIdentityChanged(live.generation))
                return@launch
            }
            post(SessionInput.CommandDone(live.generation, outcome))
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
    private fun onAcknowledged(live: Connection, ack: TopicCommandAcknowledgement) {
        // Settled **before** anyone outside is told, and against the grant this connection was
        // opened for rather than whatever is current: a listener that throws must not be able to
        // leave the session still asking under an account that has been refused. Found by review.
        if (ack.rejected.values.any { it == TopicRejectionReason.PREMIUM_REQUIRED }) {
            refusedFor = live.fence
            end(live, TopicDisconnectCause.DELIBERATE)
            cancelReconnect()
        }
        onAcknowledgement(ack)
        if (ack.rejected.isNotEmpty()) onRejected(ack.rejected)
    }

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
        live.commandJob?.cancel()
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
