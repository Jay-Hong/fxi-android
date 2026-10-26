package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.KrxHarness
import com.jay.fxi.data.entitlements.KrxHarnessDefaults
import com.jay.fxi.data.entitlements.KrxSendMarker
import com.jay.fxi.data.entitlements.PremiumAccessTopicGrantIssuer
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.TopicGrantDeliverer
import com.jay.fxi.data.entitlements.TopicGrantIssuer
import com.jay.fxi.data.entitlements.TopicGrantResult
import com.jay.fxi.data.entitlements.TopicRejectionReservation
import com.jay.fxi.data.remote.dto.SubscriptionAck
import com.jay.fxi.data.remote.dto.SubscriptionAckTopic
import com.jay.fxi.data.remote.dto.SubscriptionRejection
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.domain.model.TopicSubscriptionStateStore
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * S6-2b-1b-α contract tests r1 — S-3 `TopicSessionKrxHarness` with S-5 (design r5 §13 T.6 harness paragraph, T.14).
 *
 * One session under a real issuer, joined the way runtime wiring joins them (TopicRuntime.kt:128-152), with the KRX send read and the
 * marker refusal channel the α constructor adds (design r5 §3, OD-B06 (a), D-8):
 *
 * - **desired** = {TETHER, USD, KRX_FUTURES} by default — test-only; the operating DESIRED has no KRX (TSC:65, Z07 unchanged).
 * - The session is built with its **internal** constructor. [authority] and [krxSendReader] are two different
 *   [SnapshotTopicUseAuthority] instances over two counting suppliers of the same published snapshot (A for the authority, B for the
 *   send read), so reads are counted per role without wrapping either (T.6 forbids the anonymous wrapper TSCT:456-459 uses: a wrapper
 *   would hide the read interface).
 * - tests r2 (Codex tests r1 fix 2, E-17): supplier B also **records every snapshot it returns**, in order ([readerReturns]), with how
 *   many fake-bootstrap calls had begun. [Attempt.readerSnapshotsInSend] and [BootstrapCall.readerSnapshotsAtIssue] are what B returned
 *   for that attempt or issue; [Attempt.snapshotAtEncode] and [BootstrapCall.snapshotAtCall] stay harness captures of the published
 *   snapshot, not B's returns.
 * - tests r5 (mutation battery r1, agreed with Codex — **read attribution by calling path**): each return of B is classified by the
 *   call stack it was made on ([ReadPath], [ReaderRead.path]); a read is never attributed by elapsed order alone. r2–r4 tagged a read
 *   with the attempt "encoded and not yet at the socket" and let the next KRX call claim every other read, so a read the session made
 *   anywhere else (e.g. a mutant re-reading the marker when an acknowledgement arrives) was claimed as a REST issue read and failed the
 *   REST rows' 'exactly one issue read' on a non-REST cause. The stack is taken inside supplier B (`Thread.currentThread().stackTrace`),
 *   and only the **first run of session frames** from the top counts: the contiguous frames of [TopicSessionCoordinator] and its nested
 *   or lambda classes (`TopicSessionCoordinator$…`, indy `startCommand$lambda$N`) nearest to the read, i.e. the session code that
 *   called the send-read seam; the frames below that run are the session's caller.
 *   - [ReadPath.ISSUE] — that run contains `issueOwedBootstraps` and no `startBootstrap` above it: the REST issue path (§5B, the read
 *     before the topic is taken off what is owed and `startBootstrap` is called). An issue read is claimed by the KRX call that next
 *     enters the fake, as before ([BootstrapCall.readerSnapshotsAtIssue]).
 *   - [ReadPath.SEND] — the frames below the run are **exactly** (class, method, file, line, to the stack bottom) the frames below the
 *     run at the latest attempt's encode, and the run's entry frame (the session frame the caller called) is not that encode's: the
 *     caller is the one `send(encode(…))` line of the same TopicSubscribeCommand activation (`drive`), and the only session call on it
 *     besides encode is the send lambda. The read belongs to that latest attempt ([Attempt.readerSnapshotsInSend]); encode and send are
 *     synchronous, so no other attempt can be encoded in between.
 *   - [ReadPath.UNATTRIBUTED] — anything else: an acknowledgement-time read (its caller is TopicSubscribeCommand `settle`, not the send
 *     line), a read in the encode lambda before the harness encode, a read from an exchange inside the fake, a read with no session
 *     frame (a test reading B directly). It keeps its snapshot and a short [ReaderRead.origin] tag and is in [unattributedReads]; no
 *     attempt and no bootstrap claims it.
 *   - **Limits.** (1) It names two private production methods (`issueOwedBootstraps`, `startBootstrap`) and relies on `send(encode(…))`
 *     staying one source line in TopicSubscribeCommand: renaming `issueOwedBootstraps` or splitting that line makes every issue or send
 *     read UNATTRIBUTED, so the rows that need exactly one such read fail (a RED that names the read, never a silent GREEN); renaming
 *     `startBootstrap` only drops the exclusion of a read made inside it. (2) A read made by session code that the issue path or the
 *     send lambda calls through a non-session frame (a callback through another class) ends the first run early and is classified by
 *     that shorter run. (3) Frames are compared as the JVM reports them (default depth limit 1024): a truncated stack cuts the encode
 *     side and the read side at different frames, so they differ and the read fails closed (UNATTRIBUTED). The rows' passing send reads
 *     show the comparison holds on these stacks; their depth itself was not measured. (4) The JVM omits hidden lambda frames by
 *     default; one that is shown is still a session frame by its class-name prefix. The S-3 self-check `harnessSelfCheck_…` in
 *     `TopicSessionKrxRestMarkerTest` exercises all three paths.
 *   [Attempt.readerReadsInSend] stays a count of B reads between an attempt's encode and its socket send (unchanged); it is only read
 *   for an attempt that reached the socket.
 * - tests r2 (E-07, T.6 05-24): [publicConstructor] builds the session with its **public** constructor instead — no send read and no
 *   marker channel, the session a public-constructor caller gets (stub r2 internal-constructor KDoc, D-13; design r6 candidate).
 * - tests r3 (Codex tests r2 E-35, T.6 05-25): `sendReader = false` keeps the **internal** constructor but names
 *   `krxSendReader = null` (stub r3: nullable, no default), with the marker channel present or absent as [markerChannel] says.
 *   Reader absence and channel absence are separate settings: supplier B still exists in the harness but is never handed over, so
 *   its zero reads are the setup, not evidence.
 * - `onRejectedWithMarker` → the real [TopicGrantDeliverer] marker overload → S-5 [RecordingTopicGrantIssuer] (overrides BOTH
 *   `reserveRejection` forms, design r5 §3 D-21 (1)) → the real issuer ([PremiumAccessTopicGrantIssuer] over the
 *   KrxContestHarness PAC: ON = CHECKED_ON inside `checkedOnTest`, OFF = HEAD_FACTORY). The public two-argument `onRejected` is recorded
 *   and, by default, forwarded to the deliverer's two-argument form — the HEAD wiring — so a session that never uses the marker channel
 *   still reaches the issuer the HEAD way, and a session that used both would show two reservations.
 * - [KrxWire] carries one or more injected send failures ([KrxWire.failSends]).
 * - The graph sink is a recording test double of the public `fun interface` [TopicGraphSink] (not GraphRecorder).
 * - REST-ready: the `bootstrap` parameter is a gated fake that (1) waits on a gate before the exchange, (2) calls the passed
 *   `useAdmitted()` (the exchange, AuthenticatedTransport.kt:166 imitation — a refusal throws [TopicUseWithheldException] as the
 *   transport does), and (3) returns a per-topic outcome (default `Unsupported(emptyList())`, 404 `unknown_topic`). The 401 variant
 *   ([unauthorizedReplay]) calls (2) twice with a second gate in between. Every gate is held until the test releases it.
 * - REST branch additions (G4b, `TopicSessionKrxRestMarkerTest`): an answer gate after the exchange ([holdAnswer]), release of a
 *   given call ([releaseCall]), a pacing gap ([bootstrapIssueGap]) and the switch [forwardMarkerRejections]. All default to the
 *   behaviour above, so the WS rows run unchanged.
 * - g4-fix addition: [refreshResult], the credential a forced refresh after a socket `invalid_token` returns (default null, the old
 *   behaviour), so the 401 forced-refresh WS resend of T.6 05-4 can run. Also [subscriptionError].
 *
 * Observation is by the recording issuer, the ledger reservation `marker` (TopicRejectionLedger.kt:18), `krxContestView`, the
 * counting suppliers, the wire and the graph sink. **No observer is added to the session** (KW04).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TopicSessionKrxHarness(
    val krx: KrxHarness,
    desired: Set<String> = TEST_DESIRED,
    /** False builds the session without `onRejectedWithMarker` (the absent channel of D-8): its refusals take the public form. */
    markerChannel: Boolean = true,
    /** Whether the public two-argument `onRejected` goes on to the deliverer (the HEAD wiring). Recorded either way. */
    var forwardPublicRejections: Boolean = true,
    /**
     * The session's pacing between two bootstrap issues after the shown tab's first batch (TSC `bootstrapIssueGap`). Zero issues the
     * whole plan in one loop turn; a REST row that needs the pacing wait (T.6 05-14) passes a gap. Added for the REST branch (G4b).
     */
    bootstrapIssueGap: Duration = Duration.ZERO,
    /**
     * True builds the session with its public constructor: [krxSendReader] is not handed to it and it has no marker channel, so
     * [markerChannel] must be false. Added for T.6 05-24 (tests r2, E-07).
     */
    publicConstructor: Boolean = false,
    /**
     * False builds the session with its **internal** constructor and `krxSendReader = null` named explicitly (the parameter is
     * nullable and has no default, stub r3): the reader-less session of E-35. [markerChannel] still chooses whether the marker
     * channel is wired, so reader absence and channel absence can be set apart. Only meaningful with the internal constructor.
     * Added for T.6 05-25 (tests r3, Codex tests r2 E-35).
     */
    sendReader: Boolean = true
) {
    /**
     * Whether a marker-channel refusal goes on to the deliverer. Recorded either way. False keeps the issuer from hearing a refusal a
     * row only uses to set a session latch (T.6 05-21: a `premium_required` the issuer would otherwise act on). Added for G4b.
     */
    var forwardMarkerRejections: Boolean = true
    val test: TestScope get() = krx.test

    // ---- the issuer side ------------------------------------------------------------------------------------------------

    /** The published snapshot each supplier hands out. Replaceable per role for the hand-snapshot rows (T.6: 05-18~05-22). */
    var authoritySnapshot: () -> TopicAccessSnapshot = { krx.coordinator.accessSnapshot }
    var readerSnapshot: () -> TopicAccessSnapshot = { krx.coordinator.accessSnapshot }

    /** Reads of supplier A (the session's [authority]) and supplier B (its [krxSendReader]). */
    var authorityReads = 0
        private set
    var readerReads = 0
        private set

    val authority = SnapshotTopicUseAuthority { authorityReads++; authoritySnapshot() }
    val krxSendReader = SnapshotTopicUseAuthority {
        readerReads++
        readerSnapshot().also { returned -> readerReturnList += classifyReaderRead(returned) }
    }

    /** Where a return of supplier B was made, by its call stack (tests r5; see the class comment). */
    enum class ReadPath {
        /** In the session's REST issue path (`issueOwedBootstraps`, before `startBootstrap`). */
        ISSUE,

        /** In the send call of the latest encoded attempt (the send lambda `send(encode(…))` hands the encoded text to). */
        SEND,

        /** Anywhere else; never claimed by an attempt or a bootstrap. */
        UNATTRIBUTED
    }

    /**
     * One snapshot supplier B returned (tests r2, Codex tests r1 fix 2, E-17; path and origin tests r5): its place among B's returns,
     * the object B handed to the session's send-read seam, the [path] its call stack shows, the attempt whose send it was made in (a
     * [ReadPath.SEND] read, else null), how many fake-bootstrap calls had begun, the virtual time, and for a
     * [ReadPath.UNATTRIBUTED] read a short [origin] tag (the session frames that made it and their caller, or the first frame outside
     * the harness when no session frame is on the stack).
     */
    class ReaderRead(
        val index: Int,
        val snapshot: TopicAccessSnapshot,
        val path: ReadPath,
        val attempt: Attempt?,
        val callsBegun: Int,
        val atMillis: Long,
        val origin: String?
    ) {
        /** The KRX bootstrap call that claimed this issue read when it entered the fake; null for any other read or an unclaimed one. */
        var claimedBy: BootstrapCall? = null
            internal set
    }

    private val readerReturnList = mutableListOf<ReaderRead>()

    /** Every snapshot supplier B returned, in order. */
    val readerReturns: List<ReaderRead> get() = readerReturnList

    /** B's returns made neither in the REST issue path nor in an attempt's send (tests r5), in order. */
    val unattributedReads: List<ReaderRead> get() = readerReturnList.filter { it.path == ReadPath.UNATTRIBUTED }

    /** Classifies one return of B by the stack it is made on (tests r5; the rule and its limits are in the class comment). */
    private fun classifyReaderRead(returned: TopicAccessSnapshot): ReaderRead {
        val split = SessionStack.of(Thread.currentThread().stackTrace)
        val latest = attempts.lastOrNull()
        val encoded = latest?.encodeStack
        val path = when {
            split == null -> ReadPath.UNATTRIBUTED
            split.inIssuePath() -> ReadPath.ISSUE
            encoded != null && split.below == encoded.below && !split.entersLike(encoded) -> ReadPath.SEND
            else -> ReadPath.UNATTRIBUTED
        }
        val origin = if (path != ReadPath.UNATTRIBUTED) null
        else split?.describe() ?: SessionStack.outside(Thread.currentThread().stackTrace)
        return ReaderRead(
            readerReturnList.size, returned, path, latest.takeIf { path == ReadPath.SEND }, bootstrapCalls.size,
            test.testScheduler.currentTime, origin
        )
    }

    /**
     * A call stack split at its first run of session frames (tests r5): [run] is that run, top first, [entry] its last frame (the
     * session frame the caller called), [below] every frame under it to the stack bottom.
     */
    class SessionStack private constructor(val run: List<StackTraceElement>, val below: List<StackTraceElement>) {
        val entry: StackTraceElement get() = run.last()

        /** Whether the caller entered the session through the same method as in [other] (the encode lambda, for a send read). */
        fun entersLike(other: SessionStack): Boolean =
            entry.className == other.entry.className && entry.methodName == other.entry.methodName

        /** The run holds `issueOwedBootstraps` and no `startBootstrap` above it (the REST issue path before the bootstrap starts). */
        fun inIssuePath(): Boolean {
            val issue = run.indexOfFirst { it.isMethod(ISSUE_METHOD) }
            val start = run.indexOfFirst { it.isMethod(START_METHOD) }
            return issue >= 0 && (start < 0 || start > issue)
        }

        fun describe(): String {
            val caller = below.firstOrNull()?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            return "session ${run.first().methodName}…${entry.methodName} ← $caller"
        }

        companion object {
            private val SESSION_CLASS: String = TopicSessionCoordinator::class.java.name
            private const val ISSUE_METHOD = "issueOwedBootstraps"
            private const val START_METHOD = "startBootstrap"

            private fun StackTraceElement.inSession() = className == SESSION_CLASS || className.startsWith("$SESSION_CLASS$")

            // A private member keeps its name; an internal one would carry a module suffix after '$'.
            private fun StackTraceElement.isMethod(name: String) =
                inSession() && (methodName == name || methodName.startsWith("$name$"))

            /** The split of [frames], or null when no session frame is on the stack. */
            fun of(frames: Array<StackTraceElement>): SessionStack? {
                val start = frames.indexOfFirst { it.inSession() }
                if (start < 0) return null
                var end = start
                while (end < frames.size && frames[end].inSession()) end++
                val all = frames.asList()
                return SessionStack(all.subList(start, end).toList(), all.subList(end, frames.size).toList())
            }

            /** The origin of a read with no session frame: the first frame outside the JVM, the harness and the seam. */
            fun outside(frames: Array<StackTraceElement>): String =
                "outside the session: " + (frames.firstOrNull {
                    !it.className.startsWith("java.") && !it.className.startsWith(TopicSessionKrxHarness::class.java.name) &&
                        !it.className.startsWith(SnapshotTopicUseAuthority::class.java.name)
                }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "?")
        }
    }

    /** S-5 over the real issuer. */
    val issuer = RecordingTopicGrantIssuer(PremiumAccessTopicGrantIssuer(krx.coordinator))

    // ---- the session's surroundings ---------------------------------------------------------------------------------------

    val clock = object : TopicCommandClock {
        override fun nowMillis(): Long = test.testScheduler.currentTime
        override suspend fun sleep(duration: Duration) = delay(duration)
    }

    var credential = AuthSnapshot(IDENTITY.uid, IDENTITY.authGeneration, "token-1")

    /**
     * What a forced refresh after a socket `invalid_token` hands back (TopicSubscribeCommand.kt RefreshAndReplay →
     * `credentials.refreshAfterUnauthorized`, then a new attempt: a new encode and send lambda). Null, the default, makes the command
     * stop with REFRESH_UNUSABLE as before. Added for the 401 forced-refresh resend variant of T.6 05-4 (g4-fix).
     */
    var refreshResult: AuthSnapshot? = null
    val credentials = object : TopicCommandCredentials {
        override suspend fun currentSnapshot(): AuthSnapshot = credential
        override suspend fun refreshAfterUnauthorized(rejected: AuthSnapshot): AuthSnapshot? = refreshResult
        override suspend fun recordRejected(credential: AuthSnapshot) = Unit
        override suspend fun recordRejectionEvidence(credential: AuthSnapshot) = Unit
    }

    var liveFence: AuthIdentityFence? = IDENTITY
    var jitterUnit = 0.0
    var wallMillis = 1_000_000L
    val store = TopicSubscriptionStateStore()
    private val decode = TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode

    // ---- the wire -----------------------------------------------------------------------------------------------------------

    val wires = mutableListOf<KrxWire>()
    val wire: KrxWire get() = wires.last()

    /** Applied to the next socket made; see [KrxWire.failSends]. */
    var failSendsOnNextWire = 0

    /** One encoded subscribe: what it asked for, and the per-role read counts at its encode and at its socket send. */
    inner class Attempt(
        val requestId: String,
        val topics: List<String>,
        val atMillis: Long,
        val authorityReadsAtEncode: Int,
        val readerReadsAtEncode: Int,
        /**
         * The issuer's published snapshot when the attempt was encoded, right before its send lambda reads (TSC send lambda). A harness
         * capture, not what B returned: that is [readerSnapshotsInSend].
         */
        val snapshotAtEncode: TopicAccessSnapshot,
        /**
         * The call stack at this attempt's encode, split at its session frames (tests r5): a B read whose frames below the session are
         * these, entered through another session method than the encode lambda, is this attempt's send read. Null if the encode was not
         * called from session code (never, in these rows).
         */
        internal val encodeStack: SessionStack?
    ) {
        var reachedWire = false
        var wireAccepted = false
        var authorityReadsAtSend: Int? = null
        var readerReadsAtSend: Int? = null

        /** `authority.admits` reads between this attempt's encode and its socket send — the send lambda's own read (TSC send). */
        val authorityReadsInSend: Int? get() = authorityReadsAtSend?.minus(authorityReadsAtEncode)

        /** Send-read seam calls between this attempt's encode and its socket send. */
        val readerReadsInSend: Int? get() = readerReadsAtSend?.minus(readerReadsAtEncode)

        /**
         * The snapshots supplier B returned in this attempt's send, in order (tests r2): the [ReadPath.SEND] reads attributed to this
         * attempt by their call stack (tests r5), i.e. made in the session's send call that followed this attempt's encode.
         */
        val readerSnapshotsInSend: List<TopicAccessSnapshot>
            get() = readerReturnList.filter { it.path == ReadPath.SEND && it.attempt === this }.map { it.snapshot }

        val carriesKrx: Boolean get() = TopicCatalogue.KRX_FUTURES in topics
    }

    val attempts = mutableListOf<Attempt>()
    val requests = mutableListOf<TopicSubscribeRequest>()

    private fun encode(request: TopicSubscribeRequest): String {
        requests += request
        val attempt = Attempt(
            request.requestId, request.topics, test.testScheduler.currentTime, authorityReads, readerReads,
            krx.coordinator.accessSnapshot, SessionStack.of(Thread.currentThread().stackTrace)
        )
        attempts += attempt
        return "$ENCODED${request.requestId}"
    }

    private fun onWireSend(text: String, accepted: Boolean) {
        if (!text.startsWith(ENCODED)) return
        val id = text.removePrefix(ENCODED)
        val attempt = attempts.lastOrNull { it.requestId == id } ?: return
        attempt.reachedWire = true
        attempt.wireAccepted = accepted
        attempt.authorityReadsAtSend = authorityReads
        attempt.readerReadsAtSend = readerReads
    }

    // ---- the gated bootstrap fake (REST) ------------------------------------------------------------------------------------

    /** One bootstrap the session issued: its order, topic, start, the `useAdmitted` it was handed and each exchange's answer. */
    class BootstrapCall(
        val issue: Int,
        val owner: AuthIdentityFence,
        val topic: String,
        val atMillis: Long,
        val useAdmitted: () -> Boolean,
        /**
         * The issuer's published snapshot when the call began, right after its issue in the session's loop turn. A harness capture, not
         * what B returned: that is [readerSnapshotsAtIssue].
         */
        val snapshotAtCall: TopicAccessSnapshot,
        /**
         * What supplier B returned for this issue (tests r2, Codex tests r1 fix 2, E-17): for a KRX call, the issue reads (tests r5: B
         * returns made in the session's REST issue path, [ReadPath.ISSUE]) no earlier KRX call claimed, made before this call entered the
         * fake; empty for any other topic. An [ReadPath.UNATTRIBUTED] read is never claimed.
         */
        val readerSnapshotsAtIssue: List<TopicAccessSnapshot>
    ) {
        val exchanges = mutableListOf<Boolean>()
        var answered: TopicSnapshotOutcome? = null
        var withheld = false
    }

    val bootstrapCalls = mutableListOf<BootstrapCall>()
    private val exchangeGates = mutableMapOf<Int, CompletableDeferred<Unit>>()
    private val replayGates = mutableMapOf<Int, CompletableDeferred<Unit>>()

    /** The outcome per issue and topic, read after the exchange. Default: 404 `unknown_topic`. */
    var bootstrapOutcome: (issue: Int, topic: String) -> TopicSnapshotOutcome = { _, _ -> UNKNOWN_TOPIC }

    /** Issues answered first with a 401, so the exchange runs twice with [replayGate] between (AuthenticatedTransport.kt:246-263). */
    var unauthorizedReplay: (issue: Int, topic: String) -> Boolean = { _, _ -> false }

    fun exchangeGate(issue: Int): CompletableDeferred<Unit> = exchangeGates.getOrPut(issue) { CompletableDeferred() }
    fun replayGate(issue: Int): CompletableDeferred<Unit> = replayGates.getOrPut(issue) { CompletableDeferred() }

    private val answerGates = mutableMapOf<Int, CompletableDeferred<Unit>>()

    /**
     * Issues whose answer is held after their exchange(s), until [answerGate] completes: the exchange has happened (the use was
     * checked), the answer has not reached the session yet (T.6 05-12 'exchange → new permit → answer', 05-20). Added for G4b.
     */
    var holdAnswer: (issue: Int, topic: String) -> Boolean = { _, _ -> false }

    fun answerGate(issue: Int): CompletableDeferred<Unit> = answerGates.getOrPut(issue) { CompletableDeferred() }

    /** Lets [call] (not necessarily the latest of its topic) go to its exchange, answering [outcome]. Added for G4b. */
    fun releaseCall(call: BootstrapCall, outcome: TopicSnapshotOutcome) {
        val chosen = bootstrapOutcome
        bootstrapOutcome = { issue, t -> if (issue == call.issue) outcome else chosen(issue, t) }
        exchangeGate(call.issue).complete(Unit)
    }

    /** The latest issue of [topic], or an assertion failure naming [row] when there is none. */
    fun bootstrapOf(topic: String, row: String): BootstrapCall =
        bootstrapCalls.lastOrNull { it.topic == topic } ?: throw AssertionError("$row premise: no bootstrap was issued for $topic")

    /** Lets the latest issue of [topic] go to its exchange, answering [outcome]. */
    fun releaseBootstrap(topic: String, outcome: TopicSnapshotOutcome, row: String): BootstrapCall {
        val call = bootstrapOf(topic, row)
        val chosen = bootstrapOutcome
        bootstrapOutcome = { issue, t -> if (issue == call.issue) outcome else chosen(issue, t) }
        exchangeGate(call.issue).complete(Unit)
        return call
    }

    private suspend fun bootstrap(owner: AuthIdentityFence, topic: String, useAdmitted: () -> Boolean): TopicSnapshotOutcome {
        val issueReads =
            if (topic == KRX) readerReturnList.filter { it.path == ReadPath.ISSUE && it.claimedBy == null } else emptyList()
        val call = BootstrapCall(
            bootstrapCalls.size, owner, topic, test.testScheduler.currentTime, useAdmitted, krx.coordinator.accessSnapshot,
            issueReads.map { it.snapshot }
        )
        issueReads.forEach { it.claimedBy = call }
        bootstrapCalls += call
        exchangeGate(call.issue).await()
        exchange(call)
        if (unauthorizedReplay(call.issue, topic)) {
            replayGate(call.issue).await()
            exchange(call)
        }
        if (holdAnswer(call.issue, topic)) answerGate(call.issue).await()
        return bootstrapOutcome(call.issue, topic).also { call.answered = it }
    }

    private fun exchange(call: BootstrapCall) {
        val admitted = call.useAdmitted()
        call.exchanges += admitted
        if (!admitted) {
            call.withheld = true
            throw TopicUseWithheldException(emptyList())
        }
    }

    // ---- what the session hands out -------------------------------------------------------------------------------------------

    /** The public two-argument refusals, in order. */
    val publicRejections = mutableListOf<Pair<TopicSessionFence, Map<String, TopicRejectionReason>>>()

    /** The marker channel's calls, in order. */
    data class MarkerRejection(
        val owner: TopicSessionFence,
        val reasons: Map<String, TopicRejectionReason>,
        val marker: KrxSendMarker?
    )

    val markerRejections = mutableListOf<MarkerRejection>()
    val acknowledgements = mutableListOf<TopicCommandAcknowledgement>()
    val undelivered = mutableListOf<Triple<TopicUseAttribution, String, TopicSnapshotOutcome>>()

    /** Every graph input the session offered, in order. */
    val graphInputs = mutableListOf<TopicGraphInput>()
    val graphSink = TopicGraphSink { input -> graphInputs += input; TopicGraphOffer.ENQUEUED }

    /** AUTHORITY_ENDED continuity offers with [reason]. */
    fun authorityEnded(reason: TopicGraphEventReason): List<TopicGraphInput.Continuity> =
        graphInputs.filterIsInstance<TopicGraphInput.Continuity>()
            .filter { it.kind == TopicGraphEventKind.AUTHORITY_ENDED && it.reason == reason }

    fun authorityEndedAny(): List<TopicGraphInput.Continuity> =
        graphInputs.filterIsInstance<TopicGraphInput.Continuity>().filter { it.kind == TopicGraphEventKind.AUTHORITY_ENDED }

    // ---- the deliverer and the session ------------------------------------------------------------------------------------------

    /** Every grant call the deliverer made on the session, recorded before it is passed on. */
    val grantCalls = mutableListOf<String>()
    val granted = mutableListOf<TopicSessionFence>()

    private lateinit var deliverer: TopicGrantDeliverer

    // The arguments both constructors take, shared so the public-constructor session (T.6 05-24) differs only by what it lacks.
    private val connectWire: (Long) -> TopicTransport = { _ ->
        val wire = KrxWire(::onWireSend).also { it.failSends = failSendsOnNextWire }
        failSendsOnNextWire = 0
        wires += wire
        TopicTransport(wire, decode).also { it.open(Request.Builder().url(URL).build()) }
    }
    private val publicRejection: (TopicSessionFence, Map<String, TopicRejectionReason>) -> Unit = { owner, reasons ->
        publicRejections += owner to reasons
        if (forwardPublicRejections) deliverer.forwardRejection(owner, reasons)
    }
    /**
     * Run inside the session's public acknowledgement callback, after the acknowledgement is recorded (tests r5): the S-3 self-check uses
     * it to read supplier B at acknowledgement time, from a call path that is neither the issue path nor a send. Null (the default) does
     * nothing. It is the existing callback, not an observer added to the session (KW04).
     */
    var onAcknowledgementHook: ((TopicCommandAcknowledgement) -> Unit)? = null
    private val acknowledged: (TopicCommandAcknowledgement) -> Unit = { acknowledgements += it; onAcknowledgementHook?.invoke(it) }
    private val undeliveredBootstrap: (TopicUseAttribution, String, TopicSnapshotOutcome) -> Unit =
        { attribution, topic, outcome -> undelivered += Triple(attribution, topic, outcome) }

    val coordinator: TopicSessionCoordinator = if (publicConstructor) {
        require(!markerChannel) { "the public constructor has no marker channel: pass markerChannel = false" }
        require(sendReader) { "sendReader = false selects the internal constructor with krxSendReader = null; the public one has none" }
        // The public (primary) constructor: krxSendReader and onRejectedWithMarker cannot be named here (E-07).
        TopicSessionCoordinator(
            scope = test.backgroundScope,
            clock = clock,
            connect = connectWire,
            credentials = credentials,
            orders = krx.parts.orders,
            authority = authority,
            bootstrap = ::bootstrap,
            store = store,
            encode = ::encode,
            newRequestId = { "r${requests.size + 1}" },
            jitter = { jitterUnit },
            liveIdentity = { liveFence },
            bootstrapIssueGap = bootstrapIssueGap,
            onRejected = publicRejection,
            onAcknowledgement = acknowledged,
            onBootstrapUndelivered = undeliveredBootstrap,
            desired = desired,
            graphSink = graphSink,
            wallClock = { wallMillis }
        )
    } else {
        // The internal constructor. krxSendReader has no default (stub r3), so it is always named: supplier B's authority, or null
        // for the reader-less session of E-35 (T.6 05-25).
        TopicSessionCoordinator(
            scope = test.backgroundScope,
            clock = clock,
            connect = connectWire,
            credentials = credentials,
            orders = krx.parts.orders,
            authority = authority,
            bootstrap = ::bootstrap,
            store = store,
            encode = ::encode,
            newRequestId = { "r${requests.size + 1}" },
            jitter = { jitterUnit },
            liveIdentity = { liveFence },
            bootstrapIssueGap = bootstrapIssueGap,
            onRejected = publicRejection,
            onAcknowledgement = acknowledged,
            onBootstrapUndelivered = undeliveredBootstrap,
            desired = desired,
            graphSink = graphSink,
            wallClock = { wallMillis },
            krxSendReader = if (sendReader) krxSendReader else null,
            onRejectedWithMarker = if (markerChannel) {
                { owner, reasons, marker ->
                    markerRejections += MarkerRejection(owner, reasons, marker)
                    if (forwardMarkerRejections) deliverer.forwardRejection(owner, reasons, marker)
                }
            } else null
        )
    }

    private val grantSink = object : TopicGrantSink {
        override fun setAccess(allowed: Boolean, fence: TopicSessionFence?, origin: TopicGrantOrigin) {
            grantCalls += "access:$allowed:${fence?.grant?.value}"
            if (allowed && fence != null) granted += fence
            coordinator.setAccess(allowed, fence, origin)
        }

        override fun accessRevised() {
            grantCalls += "revised"
            coordinator.accessRevised()
        }
    }

    init {
        deliverer = TopicGrantDeliverer(
            issuer, grantSink, AuthFenceStream { onFence -> onFence(IDENTITY) }, test.backgroundScope, clock,
            deliveryDispatcher = test.backgroundScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        )
    }

    /** Starts the session and the deliverer, shows [tab], and lets the grant reach the session (TSCT liveThroughDeliverer :7643 form). */
    fun goLive(tab: FreeTab = FreeTab.USD) {
        coordinator.start()
        coordinator.setOnline(true)
        coordinator.setFocus(IDENTITY, tab)
        deliverer.start()
        step(100)
    }

    /** Opens the latest socket; its first subscribe goes out in this step. */
    fun openWire() {
        wire.open()
        step(1)
    }

    fun step(millis: Long = 0L) {
        if (millis > 0L) test.advanceTimeBy(millis)
        test.runCurrent()
    }

    /** The last attempt that carried KRX, or an assertion failure naming [row]. */
    fun lastKrxAttempt(row: String): Attempt =
        attempts.lastOrNull { it.carriesKrx }
            ?: throw AssertionError("$row premise: no subscribe attempt carried KRX: ${requests.map { it.topics }}")

    fun ack(requestId: String, active: List<String>, rejections: Map<String, String> = emptyMap()): String =
        Json.encodeToString(
            SubscriptionAck.serializer(),
            SubscriptionAck(
                requestId = requestId,
                operation = "subscribe",
                acceptedTopics = active.map { SubscriptionAckTopic(it) },
                rejectedTopics = rejections.map { SubscriptionRejection(it.key, it.value) },
                removedTopics = emptyList(),
                activeSubscriptions = active.map { SubscriptionAckTopic(it) }
            )
        ).replace("""{"request_id""", """{"type":"subscription_ack","request_id""")

    fun delivered(text: String): TopicSnapshotOutcome = TopicSnapshotOutcome.Delivered(decode(text))

    /** A whole-request socket error for [requestId] (TSCT `subscriptionError` :626 form), e.g. `invalid_token`. Added for g4-fix. */
    fun subscriptionError(requestId: String, code: String): String =
        """{"type":"subscription_error","request_id":"$requestId","error":"$code"}"""

    companion object {
        const val URL = "http://localhost/ws"
        private const val ENCODED = "encoded-"
        const val USD = "fx:usd-krw"
        const val TETHER = TopicCatalogue.TETHER
        const val KRX = TopicCatalogue.KRX_FUTURES
        val TEST_DESIRED: Set<String> = setOf(TETHER, USD, KRX)
        val IDENTITY = AuthIdentityFence(KrxHarnessDefaults.OWNER, 1L)
        val UNKNOWN_TOPIC: TopicSnapshotOutcome = TopicSnapshotOutcome.Unsupported(emptyList())

        fun tetherFrame(rate: Double, timestamp: String = "2026-08-31T10:20:00+09:00") =
            """{"type":"snapshot","version":1,"topic":"usdt:krw","data":{"usdt_krw":[
               {"source":"upbit","asset":"usdt-krw","rate":$rate,
                "timestamp":"$timestamp"}],"usd_krw_banks":[]}}"""

        fun usdFrame(rate: Double, timestamp: String = "2026-08-31T10:20:00+09:00") =
            """{"type":"snapshot","version":1,"topic":"$USD","data":{"banks":[
               {"source":"kb","asset":"usd-krw","rate":$rate,
                "timestamp":"$timestamp"}]}}"""
    }
}

/**
 * S6-2b-1b-α contract tests r1 — S-5 (design r5 §13 T.14, §3 D-21 (1)), used by TA-05 rows: records every reservation and passes it
 * on. Overrides BOTH `reserveRejection` forms — a `by` wrapper overriding only the two-argument form would not see the three-argument
 * call (TopicGrantDeliverer.kt TopicGrantIssuer KDoc).
 */
internal class RecordingTopicGrantIssuer(private val inner: TopicGrantIssuer) : TopicGrantIssuer {
    data class Reserved(
        val arity: Int,
        val grant: TopicGrantToken,
        val reasons: List<TopicRejectionReason>,
        val marker: KrxSendMarker?,
        val reservation: TopicRejectionReservation
    )

    val reserved = mutableListOf<Reserved>()
    val processed = mutableListOf<TopicRejectionReservation>()
    val abandoned = mutableListOf<TopicRejectionReservation>()
    val twoArgument: List<Reserved> get() = reserved.filter { it.arity == 2 }
    val threeArgument: List<Reserved> get() = reserved.filter { it.arity == 3 }

    override val accessRevisions: StateFlow<Long> get() = inner.accessRevisions

    override suspend fun topicGrantResult(): TopicGrantResult = inner.topicGrantResult()

    override fun reserveRejection(grant: TopicGrantToken, reasons: Collection<TopicRejectionReason>): TopicRejectionReservation =
        inner.reserveRejection(grant, reasons).also { reserved += Reserved(2, grant, reasons.toList(), null, it) }

    override fun reserveRejection(
        grant: TopicGrantToken,
        reasons: Collection<TopicRejectionReason>,
        marker: KrxSendMarker?
    ): TopicRejectionReservation =
        inner.reserveRejection(grant, reasons, marker).also { reserved += Reserved(3, grant, reasons.toList(), marker, it) }

    override fun abandonRejection(reservation: TopicRejectionReservation) {
        abandoned += reservation
        inner.abandonRejection(reservation)
    }

    override suspend fun onTopicRejected(reservation: TopicRejectionReservation) {
        processed += reservation
        inner.onTopicRejected(reservation)
    }
}

/**
 * S6-2b-1b-α contract tests r1 — S-3's socket (design r5 §13 T.6 'Wire는 송신 실패 1회 주입', T.14), used by TA-05 rows: one connection's
 * socket (TSCT Wire :145 form) with [failSends] injected send failures, and every send reported to [onSend].
 */
internal class KrxWire(private val onSend: (text: String, accepted: Boolean) -> Unit) : WebSocket.Factory {
    val sent = mutableListOf<String>()
    var cancelled = false
    var listener: WebSocketListener? = null

    /** The next this many sends of an encoded subscribe fail (the socket refuses them); pings are never refused. */
    var failSends = 0
    var autoPong = true

    private val socket = object : WebSocket {
        override fun cancel() {
            cancelled = true
        }
        override fun close(code: Int, reason: String?) = true
        override fun queueSize() = 0L
        override fun request() = Request.Builder().url(TopicSessionKrxHarness.URL).build()
        override fun send(text: String): Boolean {
            if (text != "ping" && failSends > 0) {
                failSends -= 1
                onSend(text, false)
                return false
            }
            sent += text
            onSend(text, true)
            if (autoPong && text == "ping") listener?.onMessage(this, """{"type":"pong"}""")
            return true
        }
        override fun send(bytes: okio.ByteString) = true
    }

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        this.listener = listener
        return socket
    }

    fun open() = listener!!.onOpen(
        socket,
        Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1).code(101).message("").build()
    )

    fun deliver(text: String) = listener!!.onMessage(socket, text)

    fun drop() = listener!!.onFailure(socket, java.io.IOException("dropped"), null)
}
