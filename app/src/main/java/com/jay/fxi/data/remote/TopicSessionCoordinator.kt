package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.HttpExchangeEvidence
import com.jay.fxi.data.remote.dto.DxyTopicMessage
import com.jay.fxi.data.remote.dto.TopicSourceEntry
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.data.remote.dto.toDollarIndex
import com.jay.fxi.data.remote.dto.toQuote
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.TopicLeaseInput
import com.jay.fxi.domain.model.TopicLeasePolicy
import com.jay.fxi.domain.model.TopicLeaseRegistry
import com.jay.fxi.domain.model.TopicAuthResolution
import com.jay.fxi.domain.model.TopicDeliveryState
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.domain.model.TopicReconnectPolicy
import com.jay.fxi.domain.model.TopicPurgeScope
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.domain.model.TopicSilenceArming
import com.jay.fxi.domain.model.TopicSilenceDecision
import com.jay.fxi.domain.model.TopicSilenceEvidence
import com.jay.fxi.domain.model.TopicSilencePolicy
import com.jay.fxi.domain.model.TopicSubscriptionSnapshot
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
     * **KRX is deliberately absent.** `ANDROID_V2_PLAN.md §7 S3` defines the hook here and gives
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
 *
 * [grant] names the access decision the session was issued under. It is part of equality on
 * purpose: a fence that differs only in its grant is a **different session** here, so everything
 * a grant change does — ending the socket, clearing prices and the store, forgetting both refusal
 * latches, and reconnecting and re-asking bootstraps where the session may ask at all — follows
 * from a new grant too, including one caused by nothing more than a KRX capability rotation. That is the cost of carrying the grant at all; without it
 * a same-fence re-issue would leave the socket holding the old grant, and a refusal it later
 * carried back would be discarded as stale while the account was being refused now.
 *
 * One exception keeps what a grant change would otherwise reset (L-4e E5): a grant the issuer re-approved in the same context after a
 * refusal ([TopicGrantOrigin.Reapproval]) is still a different session for the socket, the prices and the store, but it takes over the
 * automatic reconnection budget instead of starting it again — otherwise `refused → re-approved` would reconnect at once forever.
 */
data class TopicSessionFence(
    val identity: AuthIdentityFence,
    val userAccessEpoch: String?,
    val grant: TopicGrantToken
)

/**
 * The access decision a session runs under, as the entitlements side issued it.
 *
 * Opaque to this layer: nothing here mints, advances or interprets it. It rides on the fence and
 * comes back with a refusal, and the issuer decides what it still stands for.
 */
@JvmInline
value class TopicGrantToken(val value: Long)

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

    /** Premium access, the grant it belongs to, and why that grant was issued. `null` fence means nobody is signed in. */
    data class Access(val allowed: Boolean, val fence: TopicSessionFence?, val origin: TopicGrantOrigin) : SessionInput

    /**
     * Ask the REST twin for one topic's snapshot.
     *
     * Posted rather than started where it is asked for, so the grant it binds to is read on the
     * loop. A caller reading `fence` from its own turn is reading it from a turn that may already
     * be over — and the whole point of binding is that the request carries the grant it was
     * decided under, not whichever one is current when the job happens to start.
     */
    data class BootstrapRequested(val topic: String) : SessionInput

    /**
     * The tab being shown, for the account whose restore or choice produced it (L-4f).
     *
     * [owner] is the identity captured when that work started, not the one current when it finished: a restore that outlived
     * a sign-out must not be able to speak for whoever signed in next.
     */
    data class Focus(val owner: AuthIdentityFence, val tab: FreeTab) : SessionInput

    /** The pacing wait for the next bootstrap ran out. Stale unless both numbers are still the session's. */
    data class BootstrapIssueDue(val grantEpoch: Long, val ticket: Long) : SessionInput

    /** A bootstrap's job ended, however it ended — including the paths that post no answer. */
    data class BootstrapSettled(val requestId: Long) : SessionInput

    /**
     * One REST bootstrap finished.
     *
     * Carries the **grant epoch it was issued under**, and no instant. The loop reads the clock
     * when it applies this, because that is when the answer became this session's; a stamp taken
     * inside the job would be when the HTTP call returned, and a queue that ran late would then
     * arm a window that had already partly elapsed.
     */
    data class BootstrapAnswered(
        val grantEpoch: Long,
        val topic: String,
        val outcome: TopicSnapshotOutcome,
        /** The use its issue acquired (L-4e E2a). A delivered snapshot is applied only while this is still admitted. */
        val lifetime: TopicUseLifetime
    ) : SessionInput

    data class Transport(val generation: Long, val event: TopicTransportEvent) : SessionInput

    /**
     * A command returned an outcome.
     *
     * Separate from [CommandDone], which is the bookkeeping and runs however the command ended: a
     * cancelled one has no outcome, and saying so by simply not sending this is more honest than
     * inventing one.
     */
    data class CommandFinished(
        val generation: Long,
        val commandId: Long,
        val purpose: TopicCommandPurpose,
        val outcome: TopicCommandOutcome
    ) : SessionInput

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
     * D14's window may have run out.
     *
     * Carries a **window** ticket rather than a generation: the window outlives the connection it
     * was armed on, so filtering by generation would drop the events that matter most.
     */
    data class SilenceDue(val ticket: Long) : SessionInput

    /**
     * The connection's request channel is free again.
     *
     * A start that stood aside for another command is resumed from here rather than from a timer,
     * so there is one thing that wakes it.
     */
    data class ControlLaneFree(val generation: Long) : SessionInput

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

    /**
     * A command found, at its send, that the access its connection's use serves no longer admits it (L-4e E2a).
     *
     * Posted rather than acted on for the same reason as [LeaseExpiryDue]: ending the connection from the command would cancel
     * its own coroutine. The handler checks the generation, so a dead connection cannot end a newer one.
     */
    data class AccessWithheldDue(val generation: Long) : SessionInput

    /** A deferred protected side effect asking to run under the session that attributed it (L-4e E2a). */
    class DeferredUse(val attribution: TopicUseAttribution, val action: () -> Unit) : SessionInput

    /** The issuer published a new access snapshot under the context this session already holds (L-4e E2b). */
    data object AccessRevised : SessionInput

    data object Stop : SessionInput
}

/**
 * The one owner of a topic session: the socket, what is subscribed on it, and when to open another.
 *
 * `ANDROID_V2_PLAN.md §7 S3`, the coordinator axis. This slice owns the connection's lifetime,
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
    /**
     * The issuer's published access, as the session asks it (L-4e E2a): a new protected use acquires a lifetime from it, and every
     * send and application under that use asks whether it is still admitted. Thread-safe and side-effect free.
     */
    private val authority: TopicUseAuthority,
    /**
     * One REST snapshot for one topic, bound to the grant it was asked for.
     *
     * Takes an [AuthIdentityFence] rather than a whole [TopicSessionFence] because that is all the
     * transport can enforce: it re-checks uid and auth generation before the token is read and
     * again once the response is in hand, and knows nothing of `userAccessEpoch`. The half it
     * cannot see is refused here instead, by [SessionInput.BootstrapAnswered]'s epoch.
     *
     * [useAdmitted] is the use this call was issued under (L-4e E2a): the transport asks it before every send, and a refusal
     * ends the call with [TopicUseWithheldException].
     */
    private val bootstrap: suspend (owner: AuthIdentityFence, topic: String, useAdmitted: () -> Boolean) -> TopicSnapshotOutcome,
    private val store: TopicSubscriptionStateStore,
    private val encode: (TopicSubscribeRequest) -> String,
    private val newRequestId: () -> String,
    private val jitter: () -> Double,
    /**
     * Synchronous observation, not a pure getter. In production this reconciles Firebase's
     * current user with the auth tracker and may advance its generation.
     *
     * At a guarded apply boundary, a mismatch, including `null`, retires the current grant.
     * A cached Access input cannot replace that observation, and repeating it does not restore the grant.
     * L-4f also reads this to admit Focus inputs and retain focus during Access processing:
     * a stale Focus is dropped, while a missing or different live UID clears the held focus.
     * These focus checks do not themselves retire the grant.
     * Runtime wiring must therefore observe auth-generation changes
     * independently of bare-uid deduplication and obtain a valid grant for the current identity
     * before it supplies a new `Access` fence; `AuthAccessBinder` alone does not guarantee that.
     *
     * This is also not a global identity monitor: a timer or a command result can run before any
     * guarded data input observes the loss. A command's answer is read against it at application
     * (L-4c, [admitsAnswer]). It does not validate live access revocation or authorize deferred
     * callbacks — the refusal hand-over carries its connection's fence for the consumer to check, and
     * nothing here checks it. Those, and generation-aware grant recovery, remain prerequisites for
     * runtime wiring.
     */
    private val liveIdentity: () -> AuthIdentityFence?,
    /**
     * HTTP evidence observed before grant filtering, including identity-change failures.
     * Called only when at least one evidence field exists.
     *
     * Synchronous and non-throwing. The attached sink owns retry-floor recording;
     * [onBootstrapUndelivered] may later receive the same `Refused` outcome for its
     * owner-bound handling, but must not apply that response's retry floor again.
     * Runtime wiring must attach a floor owner before requests are enabled, and the
     * default below is only honest while nothing is wired.
     */
    private val onBootstrapHttpEvidence: (statusCode: Int?, retryAfter: String?) -> Unit =
        { _, _ -> },
    /**
     * The earliest instant, on [clock]'s timeline, a bootstrap may start (L-4f). Synchronous and non-throwing.
     *
     * Read, never written: the retry floor is recorded by whoever [onBootstrapHttpEvidence] hands the server's evidence to, and a
     * grant change does not reset it. Read before every logical call and again when a wait ends. It cannot space the sends of a
     * call that has already started and is waiting for its token, nor a replay inside the transport. The default is only honest
     * while nothing is wired.
     */
    private val bootstrapNotBeforeMillis: () -> Long = { 0L },
    /** The least time between logical calls after the shown tab's first batch; see [TopicBootstrapOrder.ISSUE_GAP]. */
    private val bootstrapIssueGap: Duration = TopicBootstrapOrder.ISSUE_GAP,
    /**
     * Refusals, handed over as they arrive rather than when the command finishes, with the fence
     * of the connection that was refused.
     *
     * That fence is the one the connection was opened under, not whatever is current when this
     * runs: a `premium_required` refusal ends the connection **before** this is called, and a
     * consumer has to be able to tell a refusal for the grant it still holds from a late one.
     * Carrying it is all this does — whether the refusal still applies is the consumer's
     * decision. The acknowledgement was admitted for application before its store writes and the
     * latch (L-4c); this call does not validate them again.
     */
    private val onRejected: (owner: TopicSessionFence, rejected: Map<String, TopicRejectionReason>) -> Unit =
        { _, _ -> },
    /**
     * Every acknowledgement admitted for application (L-4c), as it arrives — one refused at application is not handed over.
     *
     * Kept even though this slice uses only the refusals: the leases and the instant are what
     * S3k-2 renews against, and dropping them here would mean re-plumbing the seam that was the
     * point of handing an acknowledgement over early.
     *
     * Receiving one authorises nothing later. A consumer that acts on it afterwards goes through the session's own boundaries,
     * and a deferred protected side effect through [runIfStillOwned]; its attribution argument is added with that consumer (S3k-2).
     */
    private val onAcknowledgement: (TopicCommandAcknowledgement) -> Unit = {},
    /** A frame the decoder refused: its length and the exception's class name, and nothing else. */
    private val onUndecodable: (length: Int, failure: String) -> Unit = { _, _ -> },
    /**
     * The subscription state, whenever it changes.
     *
     * Sent from one place rather than paired with each write, and from the transitions a write can
     * be undone inside of — an expiry marks a topic degraded and then tears the connection down,
     * which puts it back to never-received in the same breath.
     */
    private val onTopicState: (TopicSubscriptionSnapshot) -> Unit = {},
    /**
     * A bootstrap answer that was **not** a snapshot, handed over with its evidence intact.
     *
     * This is a hand-off, not a recovery. Nothing here re-checks an entitlement, refreshes a
     * token or honours a retry floor — it carries the server's own answer out of the session so
     * that whoever owns those decisions can make them. Until something is attached, the effect of
     * every non-delivery is exactly what it was: nothing.
     *
     * **What each outcome is, and why this session has no seat for it.**
     *
     * | outcome | why it stops here |
     * |---|---|
     * | `Dormant` | endpoint-wide, and answered *before* authentication. The only named slot,
     *   `TopicRejectionReason.TOPICS_DISABLED`, is per-topic and reachable only through
     *   `applyAck` — writing it would assert an acknowledgement that never happened |
     * | `Unsupported` | [desired] is a copied `val`. `store.setDesired(false, …)` neither
     *   removes the topic from that set nor stops [startBootstrap]; it also clears rejection,
     *   which [accepts] reads. It is not a coordinator-level way to stop consuming a topic |
     * | `Degraded` | `markDegraded` is reachable, but the value represents socket subscription
     *   delivery/lease state, including expiry, rather than REST endpoint availability |
     * | `TemporarilyUnavailable` | carries no retry input; this coordinator has no REST
     *   retry policy, and must not invent a floor from the socket protocol |
     * | `Refused` | an HTTP failure after the transport's conditional, at-most-once 401 replay.
     *   A first-response 403, 429 or 5xx also lands here. The preserved status and headers
     *   belong to the appropriate authentication, entitlement or retry owner |
     * | `Malformed` | a server contract violation. `onUndecodable` is the socket decoder's seam
     *   and takes a length and a class name, which is a different fact |
     * | `TimedOut` / `Unreachable` | the store's "gave up without a verdict" is keyed to a
     *   request ticket, and a ticket belongs to the socket's single control channel |
     *
     * **The attribution identifies who the outcome belongs to; it authorises nothing (L-4e E2a).**
     * This callback runs synchronously on the coordinator loop and must return promptly. The hand-over itself is control
     * flow, so a use that is no longer admitted does not stop it. A deferred protected side effect goes back through
     * [runIfStillOwned], which checks this session instance, its grant epoch and fence, both latches, the live identity
     * and the use's lifetime at the moment it runs.
     *
     * **Do not log the outcome whole.** `Unsupported` is a data class, so its default
     * `toString()` prints the server's supported-topic list — which, with a topic removed for
     * this user, *is* an entitlement. `Malformed`'s reason may contain body excerpts from decoder errors.
     */
    private val onBootstrapUndelivered:
        (attribution: TopicUseAttribution, topic: String, outcome: TopicSnapshotOutcome) -> Unit =
        { _, _, _ -> },
    desired: Set<String> = TopicCatalogue.DESIRED
) : TopicGrantSink {
    /**
     * Copied, so what this session consumes cannot change under it.
     *
     * `val` stops the field being re-assigned and says nothing about the set's contents: a caller
     * that hands in a `mutableSetOf` still holds the same object. The copy is what lets the
     * answer path trust the check the issue already made.
     */
    private val desired: Set<String> = desired.toSet()

    /** This instance, as the attributions it hands out name it: another session's grant epoch can be the same number. */
    private val sessionKey = Any()

    private class Connection(
        val generation: Long,
        val transport: TopicTransport,
        /** The grant this socket was opened for. Nothing is sent under any other. */
        val fence: TopicSessionFence,
        /** The use this socket was opened under (L-4e E2a). Every send and application on it asks whether it is still admitted. */
        val lifetime: TopicUseLifetime,
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

        /**
         * The command holding the request channel, if any.
         *
         * The store keeps **one** open request — [TopicSubscriptionStateStore.beginRequest] hands
         * its ticket to the newest caller, and `applyAck` has no ticket guard at all — so a second
         * command sending while one is unanswered writes over the first one's verdict. Held from
         * registration, because the token wait and the retry cooldowns are inside the command.
         */
        var controlOwner: Long? = null

        /**
         * The revalidation whose result may still be applied.
         *
         * "Is one in progress" is not "is this the one that asked": a question answered by data
         * lets a second episode begin, and the first one's result arrives after it. Found by
         * review.
         */
        var revalidationOwner: Long? = null

        /** Starts that stood aside for [controlOwner], resumed when it lets go. */
        var renewalDeferred = false
        var revalidationDeferred = false

        var renewalTimer: Job? = null
        var expiryTimer: Job? = null

        /** Both absolute, on the injected clock, so a wait that overslept is still late. */
        var connectDueAtMillis = 0L
        var pongDueAtMillis: Long? = null
    }

    private class RunningCommand(
        val command: TopicSubscribeCommand,
        val purpose: TopicCommandPurpose
    ) {
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
     * Which grant's authority is current, counted rather than compared.
     *
     * The case a fence comparison cannot see is [setAccess] withdrawing access while handing
     * back the **same** fence — a shape the public API permits and that nothing else here
     * handles. What the entitlements layer will actually send is not settled: its reducer rotates
     * the user epoch on an authoritative loss only when something protected existed, and this
     * coordinator has no production caller yet. So this is a contract the session keeps, not a
     * claim about `userAccessEpoch`. A number that only rises also cannot be re-entered, which is
     * what makes a withdrawal that is granted again still refuse the older answer.
     */
    private var grantEpoch = 0L

    /**
     * The grant whose automatic bootstrap plan has been created, or `null` before any plan exists.
     *
     * The counter **is** the reset: a grant whose authority ended raised it, so the next one
     * cannot match a number spent on the one before. There is no clearing code, and nothing to
     * forget to clear.
     *
     * `Long?` rather than `0L` is a readability choice, not a safety one — the two are
     * observationally identical here, because a fence only becomes non-null through the branch
     * that raises the counter, and [wanted] requires a non-null fence. It is written this way so
     * the equivalence does not have to be re-derived to read the line.
     */
    private var fannedOutForGrant: Long? = null

    /**
     * The REST bootstraps still out, by request, with the grant and topic each was issued for.
     *
     * Held for shutdown, which is the one moment the answers stop having anywhere to go. A grant
     * change does not cancel them — [grantEpoch] refuses those answers instead — which can leave
     * multiple requests running to the end of their budgets. An entry leaves on its own
     * [SessionInput.BootstrapSettled], by request id, so an older request finishing late cannot take
     * a newer one's entry with it. Touched only on the loop.
     */
    private val bootstrapsOut = HashMap<Long, BootstrapOut>()
    private var nextBootstrapRequestId = 0L

    private class BootstrapOut(val grantEpoch: Long, val topic: String, val job: Job)

    /** Whose shown tab this session knows, if any (L-4f). Kept per account, not per grant. */
    private sealed interface FocusState {
        data object Undetermined : FocusState
        data class Confirmed(val uid: String, val tab: FreeTab) : FocusState
    }

    private var focus: FocusState = FocusState.Undetermined

    /**
     * The topics still owed a bootstrap under [unissuedForGrant]: the grant's plan, and explicit requests waiting their turn.
     *
     * A set, and ordered only when one is taken: the next is whichever ranks first for the tab shown **then**, which is what
     * moves a newly shown tab's topics ahead and what folds an explicit request into the plan without a second list. Emptied
     * the moment the grant it belongs to ends.
     */
    private val unissuedBootstraps = LinkedHashSet<String>()
    private var unissuedForGrant: Long? = null

    /**
     * The topics owed only because a caller asked for them, bound to the same grant as [unissuedBootstraps] (L-4e E5). Under a
     * re-approved grant with no connection these are the only ones issued; taking one off takes it off both.
     */
    private val requestedBootstraps = HashSet<String>()

    /**
     * The grant epoch a refusal re-approval started, or null (L-4e E5). Under it the automatic reconnection budget carried over, the
     * first batch is not exempt from the gap, and automatic bootstraps wait for a connection this grant started.
     */
    private var reapprovedEpoch: Long? = null

    /** null until this grant first issues; thereafter, the remaining topics eligible for its initial batch. */
    private var bootstrapFirstBatch: Set<String>? = null
    private var lastBootstrapIssuedAtMillis: Long? = null
    private var bootstrapIssueTimer: Job? = null
    private var bootstrapIssueTicket = 0L

    /**
     * D14's window, and the two facts that decide it. **Session-scoped, not per connection.**
     *
     * A window is evidence that this grant's data was flowing, and swapping sockets does not
     * undo that — `TopicSilencePolicy` says they survive a plain reconnect and are cleared only by
     * a purge. Putting them on [Connection] would drop them at every [end].
     */
    private var silenceArmedUntilMillis: Long? = null
    private var silenceHandledWindowMillis: Long? = null
    private var tetherDelivered = false
    private var silenceTicket = 0L
    private var silenceTimer: Job? = null

    /**
     * Whether the effort to get a first delivery is still under way.
     *
     * Not "is a command object alive": the ladder, the connect and the resubscribe spread are all
     * part of the effort, and a silence answered during any of them would be answered twice. Set
     * when an attempt starts, cleared when that connection's first-delivery command ends.
     */
    private var firstDeliveryOutstanding = false

    /** What the last publication said, so an unchanged snapshot is not sent again. */
    private var publishedSnapshot: TopicSubscriptionSnapshot? = null

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

    /**
     * The grant this session may run under, or its explicit end.
     *
     * A hold that keeps the context — and its release — is not sent here (L-4e E2b): `false` for the same fence ends the grant, so
     * the plan it asked for is made again when access returns, and a different fence is a different session. Holds go to
     * [accessRevised]. [origin] says whether a new grant re-approved the one this session holds in the same context (L-4e E5).
     */
    override fun setAccess(allowed: Boolean, fence: TopicSessionFence?, origin: TopicGrantOrigin) =
        post(SessionInput.Access(allowed, fence, origin))

    /**
     * The issuer published a new access snapshot under the context this session already holds (L-4e E2b).
     *
     * Not a context change and not an explicit end — those still come as [setAccess]. A deliverer calls this for the latest revision
     * it observes, including one whose hold it missed: the session asks its own questions again and changes only what they answer
     * differently. Repeating it is harmless, and it resets no budget — neither the reconnection ladder nor, under a re-approved grant,
     * the wait for a connection before automatic bootstraps (L-4e E5).
     */
    override fun accessRevised() = post(SessionInput.AccessRevised)

    /**
     * Asks the REST twin for [topic] once.
     *
     * Deliberately not "and keep it fresh": one answer, decided by whoever calls this. Whether the
     * session is in a state to ask at all is decided on the loop, not here.
     */
    fun requestBootstrap(topic: String) = post(SessionInput.BootstrapRequested(topic))

    /**
     * Which tab is shown, for [owner] — the identity captured when the restore or the choice that produced it began (L-4f).
     *
     * A bootstrap waits for this: nothing is issued before the shown tab is known. The provider owes one on every outcome of
     * the last-tab restore — the stored tab, or 달러 when there is none, it is unknown, or it cannot be read — and must not
     * publish a restore that was cancelled or replaced. Refused on the loop unless [owner] is exactly who is signed in now.
     */
    fun setFocus(owner: AuthIdentityFence, tab: FreeTab) = post(SessionInput.Focus(owner, tab))

    /**
     * Runs [action] on this session's scope if what [attribution] names still owns a protected use (L-4e E2a).
     *
     * Checked on the loop, in this order: the same session instance, the same grant epoch, access still granted to the same fence,
     * neither latch, the live identity, and the use's lifetime. [action] runs in that same turn or not at all; no answer is handed
     * back to act on later. It must return promptly and not throw — an exception ends the session as any loop failure does. Work it
     * starts asynchronously does not carry this admission with it, and each send or application of that work is checked again.
     */
    fun runIfStillOwned(attribution: TopicUseAttribution, action: () -> Unit) = post(SessionInput.DeferredUse(attribution, action))

    fun stop() = post(SessionInput.Stop)

    private fun post(input: SessionInput) {
        inputs.trySend(input)
    }

    // ---- the loop -------------------------------------------------------------------------

    private suspend fun handle(input: SessionInput) {
        if (stopped) return
        dispatch(input)
        publishIfChanged()
    }

    private suspend fun dispatch(input: SessionInput) {
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
                    // Only after the lease question, and only on a connection that survived it:
                    // the window is read from a clock that counts sleep while the timer waiting
                    // for it is not, so coming back is where a window that ran out during the
                    // sleep is noticed.
                    if (connection != null) evaluateSilence()
                    reconsider(budgetIsFresh = true)
                }
            }

            is SessionInput.Online -> {
                if (online == input.value) return
                online = input.value
                reconsider(budgetIsFresh = input.value)
                // A grant that arrived while the network was down is asked for here, and a plan the
                // network interrupted carries on from here: `Access` already ran and could not.
                pumpBootstraps()
            }

            is SessionInput.Access -> {
                // The same grant, said again, is not a new trigger — repeating it during a
                // backoff used to open a socket immediately. Found by review.
                if (access == input.allowed && fence == input.fence) return
                val moved = fence != input.fence
                // A re-approval in the same context takes over the reconnection budget (L-4e E5 §3.1): a different grant, the same
                // account and namespace. Not required to name the grant held: deliveries coalesce, and a chain of re-approvals can
                // arrive as its last one — the deliverer has already told a skipped context change apart.
                val held = fence
                val inherits = input.allowed && input.origin is TopicGrantOrigin.Reapproval && moved &&
                    held != null && input.fence != null &&
                    input.fence.identity == held.identity && input.fence.userAccessEpoch == held.userAccessEpoch
                // An access withdrawal that hands back the same fence is invisible to a
                // comparison of fences, and it is still the transition after which an answer
                // authorised under that access is no longer this session's.
                val withdrawn = access && !input.allowed
                access = input.allowed
                fence = input.fence
                // Raised at **every** end of this grant's authority, which is a wider set than
                // the purge below: the values already on screen survive a plain withdrawal, and
                // an answer still in flight does not. Raised before the teardown so nothing
                // between here and the end of this branch can issue under the old number.
                //
                // The bootstraps in flight are deliberately **not** cancelled. Cancelling and
                // this counter are not alternatives — one frees a socket, the other refuses a
                // late answer, and only the second is a correctness question. What not cancelling
                // costs is real and bounded: a request whose grant is over runs to the end of its
                // ten-second budget before anyone stops paying for it.
                if (moved || withdrawn) grantEpoch++
                reapprovedEpoch = grantEpoch.takeIf { inherits }
                if (moved) {
                    // A different grant is a different session. The socket was authenticated as
                    // the grant that is gone, so it goes with it — leaving it open would carry
                    // one account's subscription into another's session. What it received is not
                    // evidence for the new grant either, and its prices are not the new prices.
                    connection?.let { end(it, TopicDisconnectCause.DELIBERATE) }
                    cancelReconnect()
                    _rates.value = TopicRates()
                    if (!inherits) everAttempted = false
                    // `setDesired(false)` is not enough: it keeps the receive generation, because
                    // a user losing interest in a topic has not unseen its frames. A grant change
                    // has — those frames were another session's.
                    store.purge(TopicPurgeScope.All)
                    // The window is evidence about the grant that is gone, so it goes with it —
                    // the one place `TopicSilencePolicy` says clears these.
                    silenceArmedUntilMillis = null
                    silenceHandledWindowMillis = null
                    tetherDelivered = false
                    silenceTicket++
                }
                // The shown tab belongs to the account, not the grant: a grant arriving, ending or rotating
                // under the same account leaves it. It goes only when the account it was confirmed for is
                // no longer the one signed in — read from the account itself, since `moved` also fires on
                // a session's very first grant, which would drop a tab restored before it.
                (focus as? FocusState.Confirmed)?.let { held ->
                    if (liveIdentity()?.uid != held.uid) focus = FocusState.Undetermined
                }
                // A grant given again or changed starts its budget from zero here rather than inside `reconsider`, which returns
                // before it when the use is held (L-4e E2b): the connection a later release opens must still have its ladder.
                if (inherits) {
                    // On the ladder, whether or not a connection was ever attempted: nothing here is a fresh trigger.
                    if (connection == null && reconnectJob == null && wanted()) scheduleReconnect()
                } else {
                    if (input.allowed && input.fence != null) reconnectAttempt = 0
                    reconsider(budgetIsFresh = true)
                }
                // After the socket is decided, because reading order should follow the session's:
                // open the connection, then fill the screen while it negotiates. Nothing here
                // depends on that order — `reconsider` changes none of [wanted]'s inputs — and no
                // test tells the two placements apart.
                pumpBootstraps()
            }

            is SessionInput.BootstrapRequested -> requestBootstrapOnLoop(input.topic)

            is SessionInput.Focus -> {
                // Before anything is touched: an input that is not the signed-in account's — a restore
                // that outlived a sign-out, or an earlier session of the same account — is dropped
                // whole, and the tab, the plan and the latch stay as they were.
                if (liveIdentity() != input.owner) return
                focus = FocusState.Confirmed(input.owner.uid, input.tab)
                pumpBootstraps()
            }

            is SessionInput.BootstrapIssueDue -> {
                if (input.grantEpoch != grantEpoch || input.ticket != bootstrapIssueTicket) return
                bootstrapIssueTimer = null
                pumpBootstraps()
            }

            is SessionInput.BootstrapSettled -> {
                bootstrapsOut.remove(input.requestId)
            }

            is SessionInput.BootstrapAnswered -> {
                // The grant it was authorised under, against the one this session is on now. The
                // transport already refused an answer whose account had moved; what it cannot see
                // is a fence that arrives unchanged across a transition — an access withdrawal
                // being the shape this session is handed.
                if (input.grantEpoch != grantEpoch) return
                // The grant this answer was issued under **is** the one held now: an equal epoch
                // is exactly the statement that nothing ended it in between. So this is not a
                // second reading of the fence, it is the first — and it is where the null goes,
                // because a session with nobody signed in has raised the counter on its way out.
                val owner = fence ?: return
                // The epoch above and the latches below only read state this loop was told
                // about. Observe the account itself before applying an answer whose identity may
                // have moved without an `Access` input.
                if (!enforceLiveIdentity(owner)) return
                // Two facts the counter cannot see, because neither arrives as a transition: the
                // server refusing this grant on the socket, and the credential turning out to be
                // somebody else's. The session has already stopped acting on the grant in both,
                // and an answer that raced either of them must not be what puts the data back.
                if (owner == refusedFor || owner == identityLostFor) return
                // No second `desired` check: the set is copied at construction and never changes,
                // and the issue already refused anything outside it.
                val delivered = input.outcome as? TopicSnapshotOutcome.Delivered
                    // A refusal or a failure is handed to its owner whatever the use came to: that is control flow (L-4e E2a).
                    ?: return onBootstrapUndelivered(
                        TopicUseAttribution(sessionKey, owner, grantEpoch, input.lifetime),
                        input.topic,
                        input.outcome
                    )
                // The snapshot is protected data: applied only while the use its issue acquired is still admitted, however the
                // access came back in between.
                if (!authority.admits(input.lifetime)) return
                applyBootstrap(input.topic, delivered.frame)
            }

            is SessionInput.Transport ->
                current(input.generation)?.let { onTransportEvent(it, input.event) }

            // The deadline first, as at every other place a start can come from. A first delivery
            // hands its silence to the same revalidation D14 asks for, and this is the only one of
            // the four ways in that had no check of its own — the question would have gone out on
            // a connection whose permission had lapsed. It also keeps a revalidation's own result
            // from writing degraded onto a grant that is already over. Found by review.
            is SessionInput.CommandFinished -> current(input.generation)?.let { live ->
                if (enforceLeaseExpiry(live)) return@let
                if (
                    input.purpose == TopicCommandPurpose.REVALIDATION &&
                    live.revalidationOwner != input.commandId
                ) return@let

                applyOutcome(live, input.purpose, input.outcome)
                if (live.revalidationOwner == input.commandId) {
                    live.revalidationOwner = null
                }
            }

            is SessionInput.CommandDone -> current(input.generation)?.let { live ->
                val done = live.commands.remove(input.commandId)
                if (done?.purpose == TopicCommandPurpose.FIRST_DELIVERY) {
                    firstDeliveryOutstanding = false
                }
                releaseControlLane(live, input.commandId)
                // A revalidation that ended without a result — cancelled, so no `CommandFinished`
                // — would otherwise leave the topic revalidating for good, with its window already
                // spent and nothing left to ask again. Found by review.
                if (live.revalidationOwner == input.commandId) {
                    live.revalidationOwner = null
                    store.abortRevalidation(TopicCatalogue.TETHER)
                }
                // Deliberately nothing else. Re-sending `desired − confirmed` here would hand a
                // terminal refusal or a spent budget a fresh one on the next lap, which is the
                // ceiling those two exist to be. A new command needs a new reason: a reconnection,
                // or a trigger the store's own retry rules allow. Found by review.
            }

            is SessionInput.CommandIdentityChanged -> current(input.generation)?.let { live ->
                // Deliberate, and no ladder: the socket was opened for a grant that is no longer
                // signed in, so there is nothing to reconnect *to* until an `Access` says who is.
                // The commands are left for `end` to cancel — this connection may be running a
                // renewal beside the one that threw. `retireIdentity` is that whole sequence, and
                // sharing it is what keeps a command's report and an apply-time mismatch from
                // being two different meanings of the same event.
                retireIdentity(live.fence)
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

            // The deadline first, here as everywhere else a start can come from. A window that
            // ran out is not permission to ask on a subscription that also ran out.
            is SessionInput.SilenceDue -> {
                if (input.ticket != silenceTicket) return
                val live = connection
                if (live != null && enforceLeaseExpiry(live)) return
                evaluateSilence()
            }

            is SessionInput.ControlLaneFree -> current(input.generation)?.let { live ->
                if (enforceLeaseExpiry(live)) return@let
                if (live.renewalDeferred) startRenewal(live)
                if (live.revalidationDeferred) startRevalidation(live)
            }

            is SessionInput.LeaseExpiryDue ->
                current(input.generation)?.let { live -> enforceLeaseExpiry(live) }

            is SessionInput.AccessWithheldDue ->
                current(input.generation)?.let { live -> end(live, TopicDisconnectCause.DELIBERATE) }

            is SessionInput.DeferredUse -> if (stillOwns(input.attribution)) input.action()

            SessionInput.AccessRevised -> reevaluateAccess()

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
     * Whether [owner] is still who is signed in, retiring the session's grant when it is not.
     *
     * The counter and the two latches all read state this loop was *told* about; this reads the
     * identity itself. Between the transport's own post-response check and the turn that applies
     * the answer, the account can move without an `Access` having been dequeued — and some moves
     * never produce one at all, because `AuthUidStream` carries a bare uid while `authGeneration`
     * also advances through `advanceGenerationBeforeSignIn()`, which the tracked sign-in path
     * calls before the Firebase operation.
     *
     * A mismatch retires rather than merely refuses: refusing frame after frame on a socket that
     * stays up starves the delivery evidence `receive` records and lets D14's silence window fire,
     * which would file an identity move as a topic going quiet.
     */
    private fun enforceLiveIdentity(owner: TopicSessionFence): Boolean {
        if (liveIdentity() == owner.identity) return true
        retireIdentity(owner)
        return false
    }

    /**
     * Whether a command's answer on [live] may be applied now (L-4c): the application boundary a data frame and a bootstrap
     * answer already have, for the answers a command writes itself.
     *
     * The pure checks first; then the live identity, which can advance its generation and so must not be driven by an answer
     * the connection or the latches already rule out; then the lease this connection holds, read at the one instant the
     * answer is applied and stamped at. An identity loss retires the grant and wins over an expiry seen at the same time.
     *
     * A `premium_required` refusal skips the lease, as it always came first: it latches the grant — which also stops a
     * bootstrap answer for it — and ends the connection before any lease or publication is looked at. Expiring the lease
     * first would drop that refusal, and with it the latch.
     *
     * [TopicAnswerDenial.ENDED_CONNECTION] and [TopicAnswerDenial.LATCHED] are defensive: an ended connection has already
     * cancelled its commands, and both latches end their connection as they are set.
     */
    private fun admitsAnswer(live: Connection, rejected: Map<String, TopicRejectionReason>): TopicAnswerAdmission {
        val still = current(live.generation) ?: return TopicAnswerAdmission.Denied(TopicAnswerDenial.ENDED_CONNECTION)
        if (still.fence == refusedFor || still.fence == identityLostFor) {
            return TopicAnswerAdmission.Denied(TopicAnswerDenial.LATCHED)
        }
        if (!enforceLiveIdentity(still.fence)) return TopicAnswerAdmission.Denied(TopicAnswerDenial.IDENTITY_LOST)
        // The use no longer admitted (L-4e E2a): what the answer refuses is still handed over — that is control flow, and a
        // premium refusal still latches — but nothing it grants is applied, and the connection ends.
        if (!authority.admits(still.lifetime)) {
            settleRefusal(still, rejected)
            end(still, TopicDisconnectCause.DELIBERATE)
            if (rejected.isNotEmpty()) onRejected(still.fence, rejected)
            return TopicAnswerAdmission.Denied(TopicAnswerDenial.ACCESS_WITHHELD)
        }
        val refusesPremium = rejected.values.any { it == TopicRejectionReason.PREMIUM_REQUIRED }
        val atMillis = clock.nowMillis()
        if (!refusesPremium && enforceLeaseExpiry(still, atMillis)) {
            return TopicAnswerAdmission.Denied(TopicAnswerDenial.LEASE_EXPIRED)
        }
        return TopicAnswerAdmission.Admitted(atMillis)
    }

    /**
     * A `premium_required` refusal latches the grant [live] was opened for and ends it, before anyone outside is told.
     *
     * Shared by an admitted acknowledgement and by one refused because its use was withheld (L-4e E2a): the latch is what stops
     * the session asking again under a grant the server refused, whichever of the two carried the refusal.
     */
    private fun settleRefusal(live: Connection, rejected: Map<String, TopicRejectionReason>) {
        if (rejected.values.none { it == TopicRejectionReason.PREMIUM_REQUIRED }) return
        refusedFor = live.fence
        end(live, TopicDisconnectCause.DELIBERATE)
        cancelReconnect()
    }

    /**
     * Retires the current grant after an observed identity loss.
     *
     * Callers run on the session's serial scope, either on the loop or in a command coroutine.
     * A bootstrap answer checks the current fence directly; connection inputs and command
     * admission pass `current()` before reaching this method. An `Access` replacing the fence
     * ends the old connection, so each caller holds the current session fence — a
     * `fence != owner` term would be a guard on a state that cannot arrive.
     *
     * The latch is what stops this grant being reopened; repeated retirement needs no further
     * cleanup, and `cancelReconnect` releases a pending timer *and* invalidates an event it has
     * already queued.
     */
    private fun retireIdentity(owner: TopicSessionFence) {
        if (identityLostFor == owner) return
        identityLostFor = owner
        connection?.takeIf { it.fence == owner }?.let {
            end(it, TopicDisconnectCause.DELIBERATE)
        }
        cancelReconnect()
    }

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
    private fun wanted(): Boolean = sessionWanted() && useLifetime() != null

    /** [wanted] without the issuer: only what this session was told. Pure, so it cannot change inside one turn. */
    private fun sessionWanted(): Boolean =
        access && online && fence != null && desired.isNotEmpty() &&
            fence != refusedFor && fence != identityLostFor

    /**
     * A lifetime for a new protected use under the current fence, or null (L-4e E2a).
     *
     * New starts only. An existing connection or bootstrap keeps the lifetime it started with, and a new one succeeding does not
     * make that one valid again.
     */
    private fun useLifetime(): TopicUseLifetime? = fence?.let(authority::acquire)

    /**
     * Whether a protected use under the session attributed to [attribution] may still run now (L-4e E2a).
     *
     * The session's own facts first, then the live identity — which can advance its generation, so it is not driven by an
     * attribution the pure checks already rule out — then the issuer.
     */
    private fun stillOwns(attribution: TopicUseAttribution): Boolean {
        if (attribution.sessionKey !== sessionKey) return false
        if (attribution.grantEpoch != grantEpoch) return false
        val owner = fence ?: return false
        if (!access || owner != attribution.owner) return false
        if (owner == refusedFor || owner == identityLostFor) return false
        if (!enforceLiveIdentity(owner)) return false
        return authority.admits(attribution.lifetime)
    }

    private fun reconsider(budgetIsFresh: Boolean) {
        if (!wanted()) {
            connection?.let { end(it, TopicDisconnectCause.DELIBERATE) }
            cancelReconnect()
            firstDeliveryOutstanding = false
            return
        }
        connection?.let { live ->
            // A connection keeps the use it opened under; a new start being possible does not make that one valid again.
            if (authority.admits(live.lifetime)) return
            end(live, TopicDisconnectCause.DELIBERATE)
        }
        // A lifecycle transition is a different trigger from the automatic ladder, and starts
        // again from zero — `TopicReconnectPolicy` says so, and a user who returns to a screen
        // should not inherit the exhaustion of a socket that failed while they were away.
        if (budgetIsFresh) reconnectAttempt = 0
        cancelReconnect()
        open()
    }

    /**
     * Asks again what an access revision under the same context may have changed, and changes only that (L-4e E2b).
     *
     * Not a lifecycle trigger: nothing pending is cancelled or brought forward, and no budget is reset.
     * - A connection whose use is no longer admitted ends, as at any other boundary.
     * - A new start that has become possible goes on the ladder, one rung reserved as any reconnection reserves it — a hold coming
     *   and going must not open a socket per flap. A re-approved grant uses the ladder even before the first actual attempt.
     *   Otherwise, a session that never attempted a connection opens at once: the held `Access` or `Online` already owed that start.
     * - The plan carries on under the same grant: only what is still owed is issued, each under its own new use.
     */
    private fun reevaluateAccess() {
        connection?.let { live ->
            if (!authority.admits(live.lifetime)) end(live, TopicDisconnectCause.DELIBERATE)
        }
        if (connection == null && reconnectJob == null && wanted()) {
            if (everAttempted || reapprovedEpoch == grantEpoch) scheduleReconnect() else open()
        }
        pumpBootstraps()
    }

    private fun open() {
        // Acquired before anything is numbered or opened: a publication between `wanted()` and here does not open a socket.
        val lifetime = useLifetime() ?: return
        val number = ++generation
        val firstAttempt = !everAttempted
        everAttempted = true
        firstDeliveryOutstanding = true
        val transport = try {
            connect(number)
        } catch (refused: Throwable) {
            // `TopicTransport.open` reports a factory failure twice — a `Failed` event *and* the
            // exception it rethrows — and counting both would spend two rungs of the ladder for
            // one attempt. Only one of them can reach this session: the transport is never handed
            // back, so nothing collects its events, and the exception is the whole report.
            // Asked again rather than trusting the lifetime just acquired: the issuer can publish while `connect` runs.
            if (wanted()) scheduleReconnect()
            return
        }
        val live = Connection(number, transport, fence ?: return, lifetime, firstAttempt)
        connection = live
        live.timers += scope.launch {
            transport.events.collect { post(SessionInput.Transport(number, it)) }
        }
        live.connectDueAtMillis = clock.nowMillis() + CONNECT_TIMEOUT.inWholeMilliseconds
        live.timers += after(CONNECT_TIMEOUT) { post(SessionInput.ConnectOverdue(number)) }
        // Under a re-approved grant this connection is what lets automatic bootstraps go (L-4e E5): whatever path opened it.
        if (reapprovedEpoch == grantEpoch) pumpBootstraps()
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
        // After the cheap, pure check: `liveIdentity` reconciles Firebase with the auth tracker
        // and can advance its generation, so a frame this session was never going to consume
        // must not be what drives it.
        if (!enforceLiveIdentity(live.fence)) return false
        // Ended rather than refused, as an identity loss is: refusing frame after frame on a standing socket would starve the
        // delivery evidence and let D14 file a withheld use as a topic gone quiet (L-4e E2a).
        if (!authority.admits(live.lifetime)) {
            end(live, TopicDisconnectCause.DELIBERATE)
            return false
        }
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
        // indistinguishable from an empty snapshot. `ANDROID_V2_PLAN.md` D14 says KRX must not
        // satisfy tether delivery, and since the two cannot be told apart, neither counts.
        // Found by review.
        if (quotes.isEmpty()) return
        _rates.value = _rates.value.merge(quotes)
        store.recordFrame(topic)
        recordDelivery(evidenceFor(topic), clock.nowMillis())
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
        if (live.commands.values.any { it.purpose == TopicCommandPurpose.FIRST_DELIVERY }) return
        desired.forEach { store.setDesired(true, it) }
        startCommand(live, TopicCommandPurpose.FIRST_DELIVERY) { desired }
    }

    /**
     * The renewal this connection's leases are due for.
     *
     * Narrowed to what is still wanted: a topic dropped from the desired set since the
     * acknowledgement is not one to re-authenticate. An empty result asks for nothing rather than
     * sending an empty subscribe.
     */
    private fun startRenewal(live: Connection) {
        if (live.renewalScope.intersect(desired).isEmpty()) {
            live.renewalDeferred = false
            return
        }
        if (live.controlOwner != null) {
            live.renewalDeferred = true
            return
        }
        live.renewalDeferred = false
        // The session never reads the outcome, so the purpose looks unobservable from here — and
        // is not: the command asks the injected clock for its delivery window, and the test
        // observes whether a renewal opens that window. That is what
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
            // Refuses the send and asks the loop to enforce the expiry. Ending the connection
            // from here would cancel this command's own coroutine, so the teardown stays with the
            // loop — but the discovery must not stay here: without the message the socket lives on
            // until the relative timer happens to wake, and this boundary is the one the three
            // starters cannot cover, because the deadline can pass between the decision and the
            // send. The handler checks the generation, so a dead connection cannot end a newer one.
            send = { text ->
                when {
                    // The use first (L-4e E2a): a withheld use is not a lease that ran out, and ending it must not start a ladder.
                    !authority.admits(live.lifetime) -> {
                        post(SessionInput.AccessWithheldDue(live.generation))
                        false
                    }
                    live.leases.expiredAt(clock.nowMillis()).isNotEmpty() -> {
                        post(SessionInput.LeaseExpiryDue(live.generation))
                        false
                    }
                    else -> transport.send(text)
                }
            },
            newRequestId = newRequestId,
            jitter = jitter,
            scope = { if (current(live.generation) == null) emptySet() else topics() },
            onAcknowledged = { ack -> onAcknowledged(live, id, ack) },
            admitAnswer = { rejected -> admitsAnswer(live, rejected) }
        )
        val running = RunningCommand(command, purpose)
        live.commands[id] = running
        live.controlOwner = id
        if (purpose == TopicCommandPurpose.REVALIDATION) {
            live.revalidationOwner = id
        }
        // `finally`, so a command that ends by cancellation is still taken off the connection. A
        // listener throwing `CancellationException` out of the acknowledgement callback used to
        // leave the entry behind for good: the socket stayed up, and every later control frame was
        // handed to a command whose unbounded inbox no longer had a reader. No test here catches
        // that — `an acknowledgement listener that throws leaves the session working` checks the
        // session, not the leftover.
        running.job = scope.launch {
            try {
                post(SessionInput.CommandFinished(live.generation, id, purpose, command.run()))
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
        // The server answered, so the request channel is free — before the delivery wait, which
        // needs nothing another command wants.
        releaseControlLane(live, commandId)

        // Settled **before** anyone outside is told, and against the grant this connection was
        // opened for rather than whatever is current: a listener that throws must not be able to
        // leave the session still asking under an account that has been refused. Found by review.
        settleRefusal(live, ack.rejected)
        // Also before, and only while the connection is still standing: the leases belong to this
        // socket, and re-arming timers on one that has just been ended would schedule work for a
        // connection nobody is holding. A deadline that passed while this answer was in flight was
        // enforced **instead of** applying it, by the admission this acknowledgement already passed
        // (L-4c) — at one instant, the one it is stamped with. Judging it again here, at a later
        // reading of the clock, could find the deadline passed after the store had taken the answer
        // and publish that answer on the way to the teardown.
        current(live.generation)?.let { still -> applyLeases(still, ack) }
        // Reported before the two observers below (L-4e E4a): the refusal is settled and its leases handled, and an observer that
        // throws must not keep the grant's refusal from the issuer. `live.fence`, not the session's current one: the refusal
        // above may already have ended this connection, and the grant it answered is the one this connection was opened under.
        if (ack.rejected.isNotEmpty()) onRejected(live.fence, ack.rejected)
        // The acknowledgement's own store writes happen inside the command, not on the loop, so
        // the turn-boundary publication would not carry them until something else arrives.
        publishIfChanged()

        onAcknowledgement(ack)
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
        // This answer is the current state of the leases, so what it schedules supersedes a
        // renewal that stood aside for the channel — otherwise the release that follows sends one
        // immediately, moments after the server granted a fresh lease.
        live.renewalDeferred = false

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
     * a command's answer being admitted, and a data frame arriving — because the timer's wait is
     * relative and the deadline is not: an app suspended past the deadline has a timer that has
     * not fired yet, and whichever of the others runs first is where that is noticed.
     *
     * Ending the connection is what recovers: desired survives it, so the reconnection resubscribes
     * and the server issues new leases. Several deadlines arriving together still end it once,
     * because [end] is idempotent and the connection is gone after the first — which is also why
     * this needs no equivalent of iOS's `leaseExpiryReconnectIssuedGeneration`.
     */
    private fun enforceLeaseExpiry(live: Connection, nowMillis: Long = clock.nowMillis()): Boolean {
        val expired = live.leases.expiredAt(nowMillis)
        if (expired.isEmpty()) return false
        // Written and published before the teardown, which puts a still-wanted topic back to
        // never-received in the same turn. iOS does the same (`WebSocketService.swift:1383-1384`).
        store.expire(expired)
        // The teardown is owed whatever the observer does with the snapshot: this runs on a
        // command's coroutine as well as on the loop, and a listener throwing there would leave
        // the socket standing on a lease that has gone. Found by review.
        try {
            publishIfChanged()
        } finally {
            end(live, TopicDisconnectCause.UNEXPECTED)
        }
        return true
    }

    // ---- the REST bootstrap ------------------------------------------------------------------

    /**
     * Plans this grant's bootstraps once, and issues what is owed as the pacing allows (L-4f, 동결 후 11번).
     *
     * **[wanted] and the shown tab come before the latch, and the order is the whole function.** A session that has an
     * account but no network, or no tab yet, reaches here — and burning the latch there would mean that grant is never asked
     * for at all. So the gates come first and the latch is spent only when the plan is actually made.
     *
     * **Every desired topic, once per grant, the shown tab's first.** The plan is [TopicBootstrapOrder.plan]: nothing is
     * dropped, only ordered. Laziness is a graph-query matter, not this one. The shape does not depend on what the account
     * holds — asking for fewer topics because a capability is missing would make the traffic a statement about entitlements.
     *
     * **A plan outlives an interruption, not its grant.** Offline, or no longer wanted, the wait is dropped and what is owed
     * stays; the same grant coming back carries on where it stopped. The latch only ever guarded making the plan. A new grant
     * empties it: those topics are asked for again under the new one.
     */
    private fun pumpBootstraps() {
        if (unissuedForGrant != grantEpoch) {
            unissuedBootstraps.clear()
            requestedBootstraps.clear()
            unissuedForGrant = grantEpoch
            // A re-approved grant's first issue keeps the gap after the last one (L-4e E5): it is not a new plan's first batch.
            bootstrapFirstBatch = if (reapprovedEpoch == grantEpoch) emptySet() else null
        }
        val tab = shownTab()
        if (!wanted() || tab == null) {
            cancelBootstrapIssue()
            return
        }
        if (fannedOutForGrant != grantEpoch) {
            fannedOutForGrant = grantEpoch
            unissuedBootstraps += TopicBootstrapOrder.plan(tab, desired)
        }
        issueOwedBootstraps(tab)
    }

    /**
     * Starts owed bootstraps in rank order until the gap or the floor says wait, and then waits.
     *
     * Nothing owed is already out under this grant. The plan is made before anything of this grant starts, and taking a topic
     * off what is owed is what starts it; the one way back in while it is out is an explicit request, which joins the call
     * instead ([requestBootstrapOnLoop]).
     */
    private fun issueOwedBootstraps(tab: FreeTab) {
        val rank = TopicBootstrapOrder.plan(tab, desired)
        val shown = TopicBootstrapOrder.shownBy(tab).filter { it in desired }.toSet()
        while (true) {
            // Under a re-approved grant, only a connection this grant started lets automatic bootstraps go (L-4e E5 §5-2); without
            // one, what a caller asked for still goes by the usual rules and the rest waits.
            val automatic = reapprovedEpoch != grantEpoch || connection?.fence == fence
            val candidates = if (automatic) unissuedBootstraps else unissuedBootstraps.filter { it in requestedBootstraps }
            val next = candidates.minByOrNull { rank.indexOf(it) } ?: return cancelBootstrapIssue()
            val batch = bootstrapFirstBatch
            val usesFirstBatch = batch == null || (next in batch && next in shown)
            val now = clock.nowMillis()
            val paced = lastBootstrapIssuedAtMillis
                ?.takeIf { !usesFirstBatch }
                ?.let { it + bootstrapIssueGap.inWholeMilliseconds }
                ?: now
            val notBefore = maxOf(paced, bootstrapNotBeforeMillis())
            if (notBefore > now) {
                scheduleBootstrapIssue((notBefore - now).milliseconds)
                return
            }
            // Each issue is its own start (L-4e E2a): acquired right before it is taken off what is owed, never carried over from
            // the issue before it, whose job was already enqueued. Refused, nothing is consumed and the plan waits.
            val lifetime = useLifetime() ?: return cancelBootstrapIssue()
            unissuedBootstraps.remove(next)
            requestedBootstraps.remove(next)
            bootstrapFirstBatch = if (usesFirstBatch) (batch ?: shown) - next else emptySet()
            lastBootstrapIssuedAtMillis = now
            startBootstrap(next, lifetime)
        }
    }

    /**
     * An explicit request: owed like the plan's, and never started ahead of the shown tab being known.
     *
     * Dropped when the session may not ask at all, or for a topic it does not consume. Otherwise it joins a call already out
     * under this grant, or waits among what is owed — bound to this grant and gone with it — and takes its turn by rank.
     * Once a topic's call has completed, asking again is a new call; the latch that guards the plan does not guard this.
     */
    private fun requestBootstrapOnLoop(topic: String) {
        if (!wanted() || topic !in desired) return
        // What is owed is already bound to the current grant here: the counter moves only in `Access`, which pumps in the
        // same turn, and the pump is what rebinds it.
        if (bootstrapsOut.values.any { it.grantEpoch == grantEpoch && it.topic == topic }) return
        unissuedBootstraps += topic
        requestedBootstraps += topic
        pumpBootstraps()
    }

    /** The shown tab, when it was confirmed for the account this session's grant is for; `null` is "not known yet". */
    private fun shownTab(): FreeTab? =
        (focus as? FocusState.Confirmed)?.takeIf { it.uid == fence?.identity?.uid }?.tab

    private fun scheduleBootstrapIssue(wait: Duration) {
        cancelBootstrapIssue()
        val epoch = grantEpoch
        val ticket = bootstrapIssueTicket
        bootstrapIssueTimer = after(wait) { post(SessionInput.BootstrapIssueDue(epoch, ticket)) }
    }

    /** Drops the pacing wait. The ticket moves too, so a wait that already posted its input is not acted on. */
    private fun cancelBootstrapIssue() {
        bootstrapIssueTimer?.cancel()
        bootstrapIssueTimer = null
        bootstrapIssueTicket++
    }

    /**
     * Issues one bootstrap for the grant that is current **now**.
     *
     * Both halves of the binding are taken here, on the loop, and both travel with the request.
     * The identity goes to the transport, which refuses the token before the send and the answer
     * after it if the account moved. The epoch comes back on the result, and refuses the one thing
     * the transport has no way to object to.
     *
     * [wanted] is the gate rather than a fence being present: a session that is offline, that has
     * been refused this grant, or whose credential turned out to be somebody else's should not be
     * spending a request. [desired] is the other half — a topic this build does not consume has no
     * reader for its answer.
     *
     * The job reports that it ended from `finally`, apart from its answer: the identity-change path posts no answer, and a
     * cancelled job posts none either, and both must still leave [bootstrapsOut].
     */
    private fun startBootstrap(topic: String, lifetime: TopicUseLifetime) {
        // The session's own conditions only: the use was acquired in this turn and the topic already taken off what is owed, so
        // a live re-read here could only drop the topic silently.
        if (!sessionWanted() || topic !in desired) return
        val owner = fence?.identity ?: return
        val epoch = grantEpoch
        val requestId = ++nextBootstrapRequestId
        val job = scope.launch {
            try {
                val outcome = try {
                    bootstrap(owner, topic) { authority.admits(lifetime) }
                } catch (withheld: TopicUseWithheldException) {
                    // The use was withheld before a send (L-4e E2a): nothing was applied, and what the server had already said
                    // about retrying is still the floor owner's. No outcome — the issue is not asked again by itself.
                    handOverEvidence(withheld.exchanges, statusCode = null, retryAfter = null)
                    return@launch
                } catch (moved: AuthIdentityChangedException) {
                    // The answer is refused, the rate limit is not: it was levied on the transport by
                    // address and outlives the credential that carried it. Handed over here, ahead of
                    // the grant filter, because that filter drops the whole outcome.
                    handOverEvidence(moved.exchanges, moved.statusCode, moved.retryAfter)
                    // This exception carries no topic verdict. Identity propagation and recovery
                    // need the runtime auth bridge — a bare-uid `Access` notification can miss the
                    // move — so do not turn it into a topic outcome the server never gave.
                    return@launch
                }
                // Same reason, on the path that returns instead of throwing: a `Refused` carries
                // the status and `Retry-After` the server did mean, and the grant filter would take
                // them down with the outcome. No guard — an HTTP failure always has a status.
                (outcome as? TopicSnapshotOutcome.Refused)?.failure?.let { failure ->
                    onBootstrapHttpEvidence(failure.statusCode, failure.retryAfter)
                }
                post(SessionInput.BootstrapAnswered(epoch, topic, outcome, lifetime))
            } finally {
                post(SessionInput.BootstrapSettled(requestId))
            }
        }
        bootstrapsOut[requestId] = BootstrapOut(epoch, topic, job)
    }

    /**
     * HTTP evidence a refused call carries, handed to the floor owner once per response (L-4e E2a).
     *
     * A use-checked call carries every response it saw; when it did, the single status and `Retry-After` only repeat the last of
     * them and are not handed over again. Any other call carries at most that single pair, handed over as before.
     */
    private fun handOverEvidence(exchanges: List<HttpExchangeEvidence>, statusCode: Int?, retryAfter: String?) {
        if (exchanges.isNotEmpty()) {
            exchanges.forEach { onBootstrapHttpEvidence(it.statusCode, it.retryAfter) }
        } else if (statusCode != null || retryAfter != null) {
            onBootstrapHttpEvidence(statusCode, retryAfter)
        }
    }

    /**
     * Lets go of every bootstrap still out, and of everything still owed. Idempotent, and safe with none.
     *
     * Shutdown only. The session's scope outlives [stop], so without this a request issued a
     * moment before it would run to completion and answer into a closed queue.
     */
    private fun cancelBootstraps() {
        bootstrapsOut.values.forEach { it.job.cancel() }
        bootstrapsOut.clear()
        cancelBootstrapIssue()
        unissuedBootstraps.clear()
    }

    /**
     * A bootstrap answer becomes prices, and — for tether — one unit of delivery evidence.
     *
     * Deliberately **not** [receive]. That path is bound to a connection whose leases and
     * rejections it re-reads, and this answer has neither: it is authorised by the route's own
     * check, which is authentication, premium and the per-user KRX filter, so a 200 is a statement
     * about this account made after whatever the socket was told. A session with no socket at all
     * must be able to apply one, which is the case [accepts] cannot express.
     *
     * It is also deliberately not `recordFrame`. That counter is the socket's, and the
     * first-delivery watchdog reads it — a REST answer satisfying it would let a session that
     * never received a frame look like one that did. What a bootstrap does earn is D14's window,
     * which `TopicSilencePolicy` arms from either path.
     */
    private fun applyBootstrap(topic: String, frame: DecodedTopicFrame) {
        when (frame) {
            is DecodedTopicFrame.Tether -> applyBootstrapEntries(topic, frame.value.data.allEntries)
            is DecodedTopicFrame.Fx -> applyBootstrapEntries(topic, frame.value.data.allEntries)

            // One slot rather than a list, so "everything failed validation" and "nothing arrived"
            // are the same answer here — and it arms nothing either way, exactly as the socket's
            // index path records no delivery.
            is DecodedTopicFrame.Dxy -> frame.value.data.dxy.toDollarIndex()?.let {
                _rates.value = _rates.value.merge(it)
            }

            // S6 owns KRX and `desired` cannot name it, so this is unreachable rather than
            // ignored — but a `when` that stopped being exhaustive is not how it should be found.
            is DecodedTopicFrame.Krx -> Unit

            // The service refuses these before they can be an answer, because over one response a
            // non-answer is a failure rather than something to skip.
            is DecodedTopicFrame.Acknowledgement,
            is DecodedTopicFrame.RequestFailure,
            is DecodedTopicFrame.Unsupported,
            DecodedTopicFrame.Pong,
            DecodedTopicFrame.NotTopic -> Unit
        }
    }

    /**
     * The same order the socket's data path uses: sanitise, merge, then record.
     *
     * A payload leaving no usable price is not a delivery — after D8 an empty tether snapshot and
     * one carrying only `usd_krw_futures` are the same bytes here, and D14 forbids the second
     * satisfying tether delivery. A payload that merged **nothing because everything in it was
     * older** is still a delivery: the server answered, and that is what the window measures.
     */
    private fun applyBootstrapEntries(topic: String, entries: List<TopicSourceEntry>) {
        val quotes: List<TopicQuote> = entries.mapNotNull { it.toQuote() }
        if (quotes.isEmpty()) return
        _rates.value = _rates.value.merge(quotes)
        recordDelivery(evidenceFor(topic), clock.nowMillis())
    }

    // ---- silence (D14) -----------------------------------------------------------------------

    /**
     * Which evidence a delivered payload is, whichever path it arrived on.
     *
     * One rule in one place. Only tether arms D14's window — FX has no time-based expiry at all,
     * and KRX is an optional group whose absence proves nothing — so a socket frame and a REST
     * answer have to classify identically. Two copies of this were two chances for them to stop.
     */
    private fun evidenceFor(topic: String): TopicSilenceEvidence =
        if (topic == TopicCatalogue.TETHER) {
            TopicSilenceEvidence.TETHER_DELIVERY
        } else {
            TopicSilenceEvidence.OTHER
        }

    /**
     * One delivery, classified by the path it came in on.
     *
     * The classification is the caller's because the two sinks are different: `recordFrame` counts
     * **socket** frames only, while this window is armed by a validated tether payload from either
     * the socket or the REST bootstrap (`TopicSilencePolicy:78-80`, `:97-98`). Deriving one from
     * the other would either lose the bootstrap or let it satisfy the first-delivery watchdog.
     * Both callers exist now: [receive] for the socket, [applyBootstrapEntries] for the REST twin,
     * and [evidenceFor] is the single rule they share.
     */
    private fun recordDelivery(evidence: TopicSilenceEvidence, receivedAtMillis: Long) {
        if (evidence == TopicSilenceEvidence.TETHER_DELIVERY) tetherDelivered = true
        val arming = TopicSilencePolicy.armAfter(evidence, receivedAtMillis)
        if (arming !is TopicSilenceArming.ArmAt) return
        silenceArmedUntilMillis = arming.millis
        armSilenceTimer(arming.millis)
    }

    /**
     * Replaced on every delivery rather than watched by a poller.
     *
     * One coroutine per tether frame is the cost, next to decoding that same frame. The timer is a
     * **session** field: `end` cancels everything in [Connection.timers], and a window that
     * outlives its connection must not be cancelled with it.
     */
    private fun armSilenceTimer(deadlineMillis: Long) {
        silenceTimer?.cancel()
        val ticket = ++silenceTicket
        val wait = (deadlineMillis - clock.nowMillis()).coerceAtLeast(0)
        silenceTimer = after(wait.milliseconds) { post(SessionInput.SilenceDue(ticket)) }
    }

    /**
     * Asks [TopicSilencePolicy] what this silence is worth, and records the answer as spent.
     *
     * With no connection the first-delivery effort owns the silence: either one is being made, or
     * nothing is wanted and there is nothing to ask about. Either way this is not the owner.
     */
    private fun evaluateSilence() {
        val live = connection
        val decision = TopicSilencePolicy.decide(
            nowMillis = clock.nowMillis(),
            armedUntilMillis = silenceArmedUntilMillis,
            handledWindowMillis = silenceHandledWindowMillis,
            tetherDelivered = tetherDelivered,
            deliveryState = store.snapshot.stateFor(TopicCatalogue.TETHER).deliveryState,
            firstDeliveryOwnerActive = live == null || firstDeliveryOutstanding
        )
        // A withheld use is not a silence (L-4e E2a). Nothing on the socket has to arrive for this window to run out, so no frame
        // boundary ends the connection first; asked before anything is marked, and ended as a frame is. The live identity is read
        // only on this branch, where a move retires ahead of the hold as at the other boundaries. The window is still spent: the
        // next connection's first delivery owns this silence, and an unspent window would ask again once that effort and its
        // revalidation have ended.
        if (decision is TopicSilenceDecision.Revalidate && live != null && !authority.admits(live.lifetime)) {
            if (enforceLiveIdentity(live.fence)) end(live, TopicDisconnectCause.DELIBERATE)
            silenceHandledWindowMillis = silenceArmedUntilMillis
            return
        }
        if (decision.spendsWindow) silenceHandledWindowMillis = silenceArmedUntilMillis
        if (decision is TopicSilenceDecision.Revalidate && live != null) {
            store.markSuspect(TopicCatalogue.TETHER)
            startRevalidation(live)
        }
    }

    /** D14's quiet question: tether only, and only if the store agrees to start one. */
    private fun startRevalidation(live: Connection) {
        // Both direct and deferred starts pass here: a question that stood aside for the
        // request channel is resumed from `ControlLaneFree`, and the credential can have failed
        // while it waited.
        if (store.snapshot.authResolution == TopicAuthResolution.FAILED) {
            live.revalidationDeferred = false
            return
        }
        if (TopicCatalogue.TETHER !in desired) {
            live.revalidationDeferred = false
            return
        }
        if (live.controlOwner != null) {
            live.revalidationDeferred = true
            return
        }
        live.revalidationDeferred = false
        if (!store.beginRevalidation(TopicCatalogue.TETHER)) return
        startCommand(live, TopicCommandPurpose.REVALIDATION) { setOf(TopicCatalogue.TETHER) }
    }

    /**
     * What a finished command means for the tether topic, by what it was asking.
     *
     * **A first delivery hands its silence over.** Its watchdog asked whether anything is arriving
     * and forty-five seconds said no; that is worth the one quiet question, and it is the same
     * question D14 asks — so it goes through the same door rather than a second one. Without this a
     * topic that never delivered is never asked about at all: the window is only armed by a
     * delivery, so `TopicSilencePolicy` holds it at `NEVER_DELIVERED` for ever.
     *
     * **A revalidation is the one that can degrade.** Degrading needs the acknowledgement *and* the
     * delivery deadline passing with nothing new — `TopicSilencePolicy` puts the second half on the
     * request's own deadlines — so an ending with no answer at all is not a failure of the topic
     * and is taken back instead. The store is asked whether this is still that revalidation: the
     * outcome is computed at the instant the deadline passes and applied a turn later, and a frame
     * whose input was queued before it is handled first, putting the topic back to healthy.
     *
     * **A renewal means nothing here.** It reports no silence at all, and reading its empty answer
     * as one would take back a revalidation that has only just begun.
     */
    private fun applyOutcome(
        live: Connection,
        purpose: TopicCommandPurpose,
        outcome: TopicCommandOutcome
    ) {
        val topic = TopicCatalogue.TETHER
        val silent = (outcome as? TopicCommandOutcome.Acknowledged)?.silent.orEmpty()
        when (purpose) {
            TopicCommandPurpose.FIRST_DELIVERY -> if (topic in silent) startRevalidation(live)

            TopicCommandPurpose.REVALIDATION -> {
                if (
                    store.snapshot.stateFor(topic).deliveryState != TopicDeliveryState.REVALIDATING
                ) {
                    return
                }
                if (topic in silent) store.markDegraded(topic) else store.abortRevalidation(topic)
            }

            TopicCommandPurpose.LEASE_RENEWAL -> Unit
        }
    }

    /** One publication point, and only when the snapshot actually moved. */
    private fun publishIfChanged() {
        val next = store.snapshot
        if (next == publishedSnapshot) return
        publishedSnapshot = next
        onTopicState(next)
    }

    /** Lets go of the request channel, and wakes whatever stood aside for it. */
    private fun releaseControlLane(live: Connection, commandId: Long) {
        if (live.controlOwner != commandId) return
        live.controlOwner = null
        if (live.renewalDeferred || live.revalidationDeferred) {
            post(SessionInput.ControlLaneFree(live.generation))
        }
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
        cancelBootstraps()
        silenceTimer?.cancel()
        silenceTimer = null
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
