package com.jay.fxi.budget

import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphV2RequestCoordinator
import com.jay.fxi.data.graph.LateBound
import com.jay.fxi.data.remote.TopicGraphRecoveryPermit
import com.jay.fxi.data.remote.TopicUseContext
import com.jay.fxi.data.remote.TopicUseTag
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.RecordedRequest

/**
 * S6-4a-obs (scope r27, report.design.r42/r46): binds one graph send's original registration to the one permit read the real
 * final network guard made for it, and the server's receipt to both. Test source only; production code is untouched.
 *
 * - The origin is read, never invoked, from the callback GraphV2RequestCoordinator handed the fetcher: GRC:1446/1449 capture the
 *   registration in `{ sendAdmitted(registration) }`, the fetcher and API client pass it on unchanged and AT:245 keeps it in the
 *   TopicUseContext. Exactly one captured Registration must be found; anything else is an origin fault, never "not a round".
 * - [recordingSlot] stands in for the production builder's permit slot (ProcessGraphBuilder:121 reads `permit.require()()` on
 *   every check): each read is the original slot's, once, and is recorded only inside a guard frame.
 * - [wrap] replaces the real TopicUseNetworkInterceptor at its own index and delegates to it exactly once; the frame lives only
 *   in that synchronous call and the previous frame is restored in finally.
 * - [stamp] makes the selected rows' permit header from the guard's own read (never a live read) and the join header; the server
 *   records each graph request with [recordServer] before its hook runs, and [factsFor] gives a hook the receipt's facts.
 *
 * A row opts in by building its rig with a row id; a rig without one never builds an observer, its client keeps the real guard
 * instance and [slotOrOriginal] hands its builder the original slot unchanged.
 */
internal class GraphSendObservation(val row: String) {
    /** The original registration's immutable values, as captured by its send callback. */
    data class OriginMark(
        val instance: Any,
        val requestId: Long,
        val key: String,
        val automatic: Boolean,
        val fence: Any,
        val lifetime: Any
    )

    enum class Stage { GUARD, DOWNSTREAM }

    /** One entry into the real guard for a use-checked graph request. [reads] are the permit reads made at [Stage.GUARD]. */
    class Attempt internal constructor(
        val id: Long,
        val context: TopicUseContext,
        val send: Int,
        /** This entry's number among the entries for the same context and send: a test count, not production's exchange. */
        val ordinal: Int,
        val origin: OriginMark?,
        val originFault: String?
    ) {
        @Volatile var stage: Stage = Stage.GUARD
        val reads: MutableList<TopicGraphRecoveryPermit?> = Collections.synchronizedList(mutableListOf())
        @Volatile var guardPassed: Boolean = false
        @Volatile var outcome: String? = null
    }

    /** A graph request as the server took it, joined to its attempt (null when the join header was missing or foreign). */
    class ServerReceipt internal constructor(val header: String?, val kind: String, val attempt: Attempt?)

    /** What a server hook needs to build its SendOrigin: the original round flag and the guard's own permit read. */
    data class SendFacts(val byRound: Boolean?, val permitOpen: Boolean?, val notRound: Boolean)

    /** One observed rig at a time for the server dispatcher; only the rig that bound it releases it. */
    class Port {
        @Volatile private var bound: GraphSendObservation? = null

        fun current(): GraphSendObservation? = bound

        @Synchronized
        fun bind(observation: GraphSendObservation) {
            check(bound == null) { "precondition(OBS/port): another observed rig is bound (${bound?.row})" }
            bound = observation
        }

        @Synchronized
        fun release(observation: GraphSendObservation) {
            if (bound === observation) bound = null
        }
    }

    val session: String = "s${sessions.incrementAndGet()}"
    private val frame = ThreadLocal<Attempt?>()
    private val ids = AtomicLong()
    private val ordinals = IdentityHashMap<TopicUseContext, MutableMap<Int, Int>>()
    val attempts: MutableList<Attempt> = Collections.synchronizedList(mutableListOf())
    val servers: MutableList<ServerReceipt> = Collections.synchronizedList(mutableListOf())
    /** Observation faults found off the test thread (server, transport); reported by [problems], never thrown there. */
    val faults: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The production builder gets this slot instead of [original]; every read is the original's, made once. */
    fun recordingSlot(original: LateBound<() -> TopicGraphRecoveryPermit?>): LateBound<() -> TopicGraphRecoveryPermit?> =
        LateBound<() -> TopicGraphRecoveryPermit?>("observed graph permit").also { slot ->
            slot.set {
                val permit = original.require()()
                frame.get()?.takeIf { it.stage == Stage.GUARD }?.reads?.add(permit)
                permit
            }
        }

    /** Delegates to [real] exactly once; a use-checked graph request runs it inside a fresh frame. */
    fun wrap(real: Interceptor): Interceptor = object : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val tag = request.tag(TopicUseTag::class.java)
            if (tag == null || !isGraph(request)) return real.intercept(chain)
            val origin = runCatching { originOf(tag.context) }
            val ordinal = synchronized(ordinals) {
                val perSend = ordinals.getOrPut(tag.context) { mutableMapOf() }
                ((perSend[tag.send] ?: 0) + 1).also { perSend[tag.send] = it }
            }
            val attempt = Attempt(ids.incrementAndGet(), tag.context, tag.send, ordinal, origin.getOrNull(), origin.exceptionOrNull()?.toString())
            attempts += attempt
            val previous = frame.get()
            frame.set(attempt)
            try {
                return real.intercept(chain).also { attempt.outcome = "response ${it.code}" }
            } catch (failure: Throwable) {
                attempt.outcome = failure.javaClass.name
                throw failure
            } finally {
                if (previous == null) frame.remove() else frame.set(previous)
            }
        }
    }

    /** The attempt whose guard frame is open on this thread, if any. */
    fun current(): Attempt? = frame.get()

    /** At the header interceptor: the real guard let this exchange through, and its reads are frozen from here on. */
    fun beforeHeader(): Attempt? = frame.get()?.also {
        it.guardPassed = true
        it.stage = Stage.DOWNSTREAM
    }

    /**
     * Whether a round's one guard read admits it by the immutable relations the real guard's predicate requires (r42 §3): a
     * permit that exists, is automatic, carries the original fence and, when re-approved, a live connection of the original
     * lifetime. Only the read value and the original registration's values; no live source is read again.
     */
    fun roundPermitAdmits(attempt: Attempt): Boolean {
        val origin = attempt.origin ?: return false
        if (!origin.automatic || attempt.reads.size != 1) return false
        val permit = attempt.reads.single() ?: return false
        return permit.automatic && permit.fence == origin.fence &&
            (!permit.reapproved || permit.connectionGeneration != null && permit.connectionLifetime == origin.lifetime)
    }

    /** The permit header from the guard's own read: an admitting round read is "open", any other round "closed", a non-round none. */
    fun permitHeader(attempt: Attempt): String? = when (attempt.origin?.automatic) {
        true -> if (roundPermitAdmits(attempt)) "open" else "closed"
        false -> null
        null -> "closed"
    }

    fun joinHeader(attempt: Attempt): String = "$session/${attempt.id}"

    /**
     * The selected rows' header step: from the attempt the guard just let through, set the join header and the permit header made
     * from its receipt, replacing whatever an earlier exchange left on the request; without an attempt, the request as it is.
     */
    fun stamp(request: Request, attempt: Attempt?): Request {
        if (attempt == null) return request
        val builder = request.newBuilder().header(JOIN_HEADER, joinHeader(attempt))
        val permit = permitHeader(attempt)
        if (permit == null) builder.removeHeader(PERMIT_HEADER) else builder.header(PERMIT_HEADER, permit)
        return builder.build()
    }

    /** The server's record of a graph request, on the server thread, before its hook runs. */
    fun recordServer(request: RecordedRequest, kind: String) = recordServerHeader(request.getHeader(JOIN_HEADER), kind)

    fun recordServerHeader(header: String?, kind: String) {
        val attempt = joined(header)
        if (header == null) faults += "server $kind without a join header"
        else if (attempt == null) faults += "server $kind with a foreign or unknown join header $header"
        else if (synchronized(servers) { servers.any { it.attempt === attempt } }) faults += "server $kind joined attempt ${attempt.id} twice"
        servers += ServerReceipt(header, kind, attempt)
    }

    /** The receipt's facts for the request a hook is answering; a missing join gives no round flag, so row premises fail too. */
    fun factsFor(request: RecordedRequest): SendFacts = factsForHeader(request.getHeader(JOIN_HEADER))

    fun factsForHeader(header: String?): SendFacts {
        val attempt = joined(header) ?: return SendFacts(null, null, false)
        val origin = attempt.origin ?: return SendFacts(null, null, false)
        return if (origin.automatic) SendFacts(true, roundPermitAdmits(attempt), false)
        else SendFacts(false, null, true)
    }

    private fun joined(header: String?): Attempt? {
        val parts = header?.split("/") ?: return null
        if (parts.size != 2 || parts[0] != session) return null
        val id = parts[1].toLongOrNull() ?: return null
        return synchronized(attempts) { attempts.firstOrNull { it.id == id } }
    }

    /** Everything that keeps this row's observation from being complete; empty means every server receipt has its guard. */
    fun problems(): List<String> {
        val out = mutableListOf<String>()
        val attemptsNow = synchronized(attempts) { attempts.toList() }
        val serversNow = synchronized(servers) { servers.toList() }
        out += synchronized(faults) { faults.toList() }
        if (attemptsNow.size > CAPACITY) out += "more than $CAPACITY attempts"
        // Every attempt, received or not: an origin fault, and a passed guard whose evidence does not match its origin.
        for (attempt in attemptsNow) {
            val origin = attempt.origin
            when {
                origin == null -> out += "attempt ${attempt.id} has no origin: ${attempt.originFault}"
                !attempt.guardPassed -> {}
                origin.automatic && attempt.reads.size != 1 -> out += "round attempt ${attempt.id} made ${attempt.reads.size} guard reads"
                origin.automatic && !roundPermitAdmits(attempt) ->
                    out += "round attempt ${attempt.id} passed its guard on a permit that does not admit it: ${attempt.reads.single()}"
                !origin.automatic && attempt.reads.isNotEmpty() -> out += "non-round attempt ${attempt.id} made ${attempt.reads.size} guard reads"
            }
        }
        for (receipt in serversNow) {
            val attempt = receipt.attempt ?: continue
            val origin = attempt.origin ?: continue
            when {
                !attempt.guardPassed -> out += "attempt ${attempt.id} reached the server without passing its guard"
                (origin.key == "catalog") != (receipt.kind == "catalog") -> out += "attempt ${attempt.id} is ${origin.key}, received as ${receipt.kind}"
            }
        }
        val received = serversNow.mapNotNull { it.attempt }.toSet()
        for (attempt in attemptsNow) {
            if (attempt.guardPassed && attempt.outcome?.startsWith("response") == true && attempt !in received) {
                out += "attempt ${attempt.id} was answered without a server receipt"
            }
        }
        val joined = serversNow.mapNotNull { it.attempt?.origin }
        if (joined.none { it.automatic }) out += "no round send joined"
        if (joined.none { !it.automatic }) out += "no non-round send joined"
        return out
    }

    fun assertComplete(label: String) {
        val found = problems()
        if (found.isNotEmpty()) throw AssertionError("contract(OBS/$label/complete): $found")
    }

    companion object {
        const val CAPACITY = 4096
        const val JOIN_HEADER = "X-Cut-Obs"
        /** PremiumGraphCutoverAcceptanceTest.PERMIT_HEADER, the header the rows' SendOrigin reads. */
        const val PERMIT_HEADER = "X-Cut-Permit"
        private val sessions = AtomicLong()

        fun isGraph(request: Request): Boolean = request.url.encodedPath.startsWith("/api/v2/graph/")

        /**
         * An observed row's end (r47 R47-01): the body; then [finish] once and [close] whatever finish did. A body failure stays
         * first with finish's and close's failures suppressed onto it; with the body passing, finish's failure stays first with a
         * close failure suppressed onto it; with both passing, a close failure is the row's failure. A later failure that is the
         * same object as the one already first is not suppressed onto itself (review r1 R1), so close still runs and the first stays.
         */
        suspend fun runRow(body: suspend () -> Unit, finish: suspend () -> Unit, close: () -> Unit) {
            try {
                body()
            } catch (failure: Throwable) {
                runCatching { finish() }.exceptionOrNull()?.takeUnless { it === failure }?.let(failure::addSuppressed)
                runCatching { close() }.exceptionOrNull()?.takeUnless { it === failure }?.let(failure::addSuppressed)
                throw failure
            }
            var finishFailure: Throwable? = null
            try {
                finish()
            } catch (failure: Throwable) {
                finishFailure = failure
                throw failure
            } finally {
                try {
                    close()
                } catch (failure: Throwable) {
                    val original = finishFailure
                    if (original == null) throw failure
                    if (original !== failure) original.addSuppressed(failure)
                }
            }
        }

        /** The slot a rig hands the production builder: the recording slot when observed, else [original] itself. */
        fun slotOrOriginal(observation: GraphSendObservation?, original: LateBound<() -> TopicGraphRecoveryPermit?>) =
            observation?.recordingSlot(original) ?: original

        private val registrationType: Class<*> =
            GraphV2RequestCoordinator::class.java.declaredClasses.single { it.simpleName == "Registration" }

        private fun read(owner: Any, name: String): Any? =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

        /** Reads the context's callback and the one Registration it captured; the callback is never invoked. */
        fun originOf(context: TopicUseContext): OriginMark {
            val callback = TopicUseContext::class.java.getDeclaredField("useAdmitted").apply { isAccessible = true }.get(context)
                ?: error("origin: no callback")
            val captured = callback.javaClass.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) && registrationType.isAssignableFrom(it.type) }
            check(captured.size == 1) { "origin: ${captured.size} captured registrations in ${callback.javaClass.name}" }
            val registration = captured.single().apply { isAccessible = true }.get(callback) ?: error("origin: null registration")
            val requestContext = read(registration, "context") ?: error("origin: no request context")
            val key = read(registration, "key") as GraphKey?
            return OriginMark(
                instance = registration,
                requestId = read(registration, "requestId") as Long,
                key = key?.let { "${it.tab}/${it.period.code}" } ?: "catalog",
                automatic = read(registration, "automaticRecovery") as Boolean,
                fence = read(requestContext, "fence") ?: error("origin: no fence"),
                lifetime = read(requestContext, "lifetime") ?: error("origin: no lifetime")
            )
        }
    }
}
