package com.jay.fxi.data.remote

import com.jay.fxi.S64HeadPacFactory
import com.jay.fxi.S64HeadParts
import com.jay.fxi.S64HeadWorld
import com.jay.fxi.data.auth.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.remote.dto.SubscriptionAck
import com.jay.fxi.data.remote.dto.SubscriptionAckTopic
import com.jay.fxi.data.remote.dto.SubscriptionRejection
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.domain.model.TopicRejectionReason
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * S6-4b K1x host contract (k1x_proposal.r3.md §4 K1j-W/K1j-R, k1j_probe/judgment.r1.md §2): the K1c follow-up on the
 * actual host runtime. Derived from the reachability probe K1jProbeTest.r2.kt (premise unchanged); the single expectation
 * for each route is locked BEFORE this run from the code path, never chosen from a result. Not a C02 measurement row.
 * Reuses S64HeadWorld / S64Observation / the normal Factory, producer and deliverer.
 * S6Krx4Rig hard-wires an outcome-only source, so the body-aware adapter is local to this file.
 * No permit, marker, ledger, schedule, grant or PAC field is assigned by the driver.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class S6KrxK1xFollowupHostContractTest {
    @Test fun k1jW_additionalWsRefusalCancelsHeldScheduledThenOneFollowUp() = probe("W")

    @Test fun k1jR_additionalRestUnknownTopicCancelsHeldScheduledThenOneFollowUp() = probe("R")

    /** k1x_proposal.r3.md §4 C02M-K1e host duties: shared ON, raw/effective KRX false, no KRX request of any kind. */
    @Test fun k1e_sharedOnKrxHiddenSubscribesNoKrxAndDrawsNoRefusal() = probe("E")

    /** k1x_proposal.r3.md §4 C02M-K1w host duties: the server keeps refusing KRX on the socket for 60 s. Backoff is recorded only. */
    @Test fun k1w_repeatedWsRefusalEachPermitOneEffectDuplicateNoEffect() = probe("WREP")

    /** k1x_proposal.r3.md §4 C02M-K1r-A host duties: socket accepts KRX, snapshot withheld, REST answers unknown_topic for 60 s. */
    @Test fun k1rA_restUnknownTopicAloneEachPermitOneEffectNoHttpOutcome() = probe("RA")

    /** k1x_proposal.r3.md §4 C02M-K1r-B1/B2: both refusals of one permit reach PAC (WS then REST / REST then WS). */
    @Test fun k1rB1_wsThenRestSamePermitTwoHandoversOneJoinedQuery() = probe("RB1")

    @Test fun k1rB2_restThenWsSamePermitTwoHandoversOneJoinedQuery() = probe("RB2")

    /**
     * k1x_proposal.r3.md §4 C02M-M1k-K/I host duties: the production AuthAccessBinder, ON, a KRX-visible approval, then a real
     * socket KRX refusal, a same-user generation change and a purchase/restore signal within 2 s. K: the refusal's scheduled
     * query is in flight first. I: the generation change's binder ask is in flight first, so the refusal answers the old grant.
     */
    @Test fun m1kK_krxScheduledQueryFirstThenGenerationAndPurchaseWithin2s() = probe("MK")

    @Test fun m1kI_generationAskFirstThenOldGrantRefusalAndPurchaseWithin2s() = probe("MI")

    private fun probe(route: String) = runTest {
        val trace = K1jTrace(route) { testScheduler.currentTime }
        lateinit var source: K1jEntitlements
        lateinit var beta: KrxBetaPac
        lateinit var loads: K1jLoadGate
        val queries = mutableListOf<K1jQuery>()
        val world = S64HeadWorld(this, "K1j-$route",
            pacFactory = S64HeadPacFactory { p ->
                source = K1jEntitlements(p, trace, krxVisible = route != "E")
                loads = K1jLoadGate(p.store)
                beta = krxBetaPac(source, loads, p.purger, p.purger, p.scope, p.clock,
                    { p.source.liveFence }, p.orders)
                beta.coordinator.also { pac ->
                    pac.onQueryStartedForTest = { order, at, origin, intent, dedicated ->
                        val q = K1jQuery(order, at, origin, intent, dedicated)
                        queries += q
                        source.nextQuery = q
                        trace.event("QUERY_START", "$q contest=${pac.krxContestView}")
                    }
                }
            }, configure = { _, factory -> factory.installKrxDesiredProducer(beta.sharedSwitch) })
        val pac = world.pac
        val observation = S64Observation(world.runtime, "K1j-$route")
        val rest = K1jRest(world, observation, trace)
        world.bootstrapEndpoint = rest::bootstrap
        val sent = mutableListOf<KrxCommandAttempt>()
        observation.session.krxAttemptBoundaryDecorator = { delegate ->
            object : KrxCommandAttemptBoundary {
                override fun prepare(scope: KrxAttemptScope) = delegate.prepare(scope)
                override fun beforeEncode(attempt: KrxCommandAttempt) = delegate.beforeEncode(attempt)
                override fun sent(attempt: KrxCommandAttempt) {
                    delegate.sent(attempt)
                    val frozen = attempt.copy(topics = attempt.topics.toSet(),
                        pendingAckTopics = attempt.pendingAckTopics.toSet(),
                        watched = attempt.watched.toSet(), manualTopics = attempt.manualTopics.toSet())
                    sent += frozen
                    trace.event("K_SEND", "request=${frozen.requestId} source=${frozen.source} " +
                        "grant=${frozen.generalLifetime.grant} stamp=${frozen.stamp} marker=${frozen.marker}")
                }
                override fun discard(attempt: KrxCommandAttempt) = delegate.discard(attempt)
            }
        }

        fun pre(label: String, value: Boolean) {
            assertTrue("precondition(K1j-$route): $label", value)
        }
        val server = K1jServer(world, observation, trace, ::pre)

        // A real-time watchdog pumps only ready virtual work. It NEVER advances virtual time.
        // This prevents runTest auto-advance from firing ACK/service timers during real IO.
        fun awaitNow(label: String, checkSource: Boolean = true, predicate: () -> Boolean) {
            val at = world.clock.nowMillis()
            try {
                runBlocking {
                    withTimeout(5_000) {
                        while (true) {
                            world.drain()
                            if (checkSource) source.checkBodyFaults()
                            if (predicate()) break
                            yield()
                        }
                    }
                }
            } catch (failure: Throwable) {
                trace.event("UNREACHED_SEAM", "$label type=${failure.javaClass.simpleName} " +
                    "queries=$queries fetches=${source.calls.map { it.describe() }}")
                throw failure
            }
            pre("$label leaves virtual clock unchanged", world.clock.nowMillis() == at)
        }

        /** Like awaitNow, but a miss fails on the named contract duty instead of a timeout. */
        fun awaitContract(message: String, predicate: () -> Boolean) {
            try {
                awaitNow(message, predicate = predicate)
            } catch (miss: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError(message, miss)
            }
        }

        suspend fun <T> input(label: String, block: suspend () -> T): T {
            val result = world.h.parts.scope.async { block() }
            awaitNow(label) { result.isCompleted }
            return result.await() // Already complete: propagates the actual input failure.
        }

        suspend fun state(point: String) {
            val diagnostics = input("$point diagnostics") { pac.recheckDiagnostics() }
            trace.event("PAC_STATE", "point=$point grant=${world.snapshot.facts.token} " +
                "fence=${world.snapshot.facts.recordFence} permit=${world.snapshot.krxPermitSeq} " +
                "effective=${world.snapshot.facts.capabilityAllowed} contest=${pac.krxContestView} " +
                "diagnostics=$diagnostics fetches=${source.calls.map { it.describe() }}")
        }

        suspend fun awaitAccepted(label: String, from: Int, marker: KrxSendMarker,
            grant: TopicGrantToken): RecordingTopicGrantIssuer.Reserved {
            awaitNow("$label original marker handover") { world.issuer.reserved.size > from }
            val additions = world.issuer.reserved.drop(from)
            pre("$label exactly one original handover", additions.size == 1)
            val reservation = additions.single()
            trace.event("ORIGINAL_HANDOVER", "point=$label arity=${reservation.arity} " +
                "order=${reservation.reservation.order} grant=${reservation.grant} marker=${reservation.marker}")
            pre("$label typed original marker and grant", reservation.arity == 3 &&
                reservation.marker == marker && reservation.grant == grant)
            awaitNow("$label PAC acceptance, not merely issuer entry") {
                val view = pac.rejectionView(grant)
                reservation.reservation in world.issuer.processed &&
                    reservation.reservation.order !in view.pendingOrders &&
                    view.latestCurrent == reservation.reservation.order &&
                    pac.krxContestView?.rejectedPermit == marker.permitSeq
            }
            trace.event("PAC_ACCEPTED", "point=$label order=${reservation.reservation.order} " +
                "marker=$marker view=${pac.rejectionView(grant)} contest=${pac.krxContestView}")
            return reservation
        }

        var primary: Throwable? = null
        try {
            if (route == "MK" || route == "MI") {
                val c = if (route == "MK") "contract(K1x):M1k-K" else "contract(K1x):M1k-I"
                // The world's fence stream holds one listener (the session's), so the binder gets its own subscription to the
                // same identity; every change below is delivered to both, binder first.
                var binderListener: ((AuthIdentityFence?) -> Unit)? = null
                val binderJob = SupervisorJob()
                try {
                    AuthAccessBinder(pac, CoroutineScope(world.h.parts.scope.coroutineContext + binderJob),
                        fenceStream = AuthFenceStream { listener -> binderListener = listener; listener(world.identity) },
                        recoveryStream = AuthCredentialRecoveryStream { }).start()
                    // The server accepts each new socket the session opens (World.open covers only the first wire).
                    val opened = mutableSetOf<KrxWire>()
                    fun openNewWires() {
                        world.wires.toList().filter { it !in opened && it.listener != null && !it.cancelled }.forEach {
                            opened += it
                            it.open()
                            world.drain()
                            trace.event("M1K_WIRE_OPENED", "wire=${world.wires.indexOf(it)} at=${world.clock.nowMillis()}")
                        }
                    }
                    fun until(label: String, budget: Long, answer: Boolean, predicate: () -> Boolean) {
                        val end = world.clock.nowMillis() + budget
                        while (!predicate() && world.clock.nowMillis() < end) {
                            world.advance(100)
                            awaitNow("$label IO settles") { source.calls.all { it.fetchExited } }
                            if (answer) {
                                openNewWires()
                                server.answerPending(refuseKrx = false)
                            }
                        }
                        pre("$label within $budget ms", predicate())
                    }
                    until("the binder bound the user and its own ask settled KRX-visible", 5_000, answer = false) {
                        pac.state.value.state == PremiumAccessState.PremiumConfirmed && world.snapshot.facts.userAllowed &&
                            world.snapshot.facts.capabilityAllowed && source.calls.isNotEmpty() && source.calls.all { it.fetchExited }
                    }
                    pre("no caller approval was used: the binder's ask is the only query",
                        queries.size == 1 && queries.single().origin != QueryOrigin.SCHEDULED)
                    world.start()
                    world.open()
                    opened += world.wire
                    awaitNow("initial real K WS send") { sent.any { S64HeadWorld.K in it.topics } }
                    val first = sent.last { S64HeadWorld.K in it.topics }
                    // A05 on the topic layer (l1_lock/draft_request.r2.md T3): the server accepts the FIRST with leases, the general
                    // topics and KRX arrive over the socket, every REST bootstrap ends, then 3.5 s of quiet. The graph catalog/tab
                    // part of A05 has no layer in this world and is not claimed by this row; it is the 4c PGCAT row's premise.
                    // The session's KRX receipt is the very capture this connection's ACK installed (same object), not two non-nulls.
                    fun krxReceived(): Boolean {
                        val receipt = k1jPeek(observation.session, "krxWsReceipt")
                        return receipt != null && receipt === k1jPeek(k1jPeek(observation.session, "connection"), "krxActive")
                    }
                    fun serveRest() = rest.calls.filter { it.physical && !it.reply.isCompleted && !it.job.isCancelled }.forEach {
                        it.reply.complete(K1jReply(200, k1jFrame(it.topic, 1555.0, world.clock.nowMillis())))
                    }
                    rest.autoSuccess = true
                    server.answerPending(refuseKrx = false)
                    pre("the FIRST was answered with KRX accepted", first.requestId in server.answered)
                    server.krxSnapshot()
                    until("$c/a05-topic-settled", 10_000, answer = true) {
                        serveRest()
                        krxReceived() && S64HeadWorld.G.all { observation.canonical.stateFor(it).receiveGeneration > 0 } &&
                            S64HeadWorld.G.all { t -> rest.calls.any { it.topic == t && it.returned } } &&
                            rest.calls.all { it.returned || it.job.isCancelled } && source.calls.all { it.fetchExited }
                    }
                    trace.event("M1K_A05_TOPIC", "at=${world.clock.nowMillis()} rest=${rest.calls.map { "${it.topic}:" +
                        (it.outcome?.javaClass?.simpleName ?: "cancelled") }} capture=${observation.connection()?.capture}")
                    for (t in S64HeadWorld.G) pre("a05: $t REST Delivered with its body ended",
                        rest.calls.any { it.topic == t && it.outcome is TopicSnapshotOutcome.Delivered } &&
                            world.calls.filter { it.topic == t }.all { it.bodyEnd != null })
                    val settledGeneration = checkNotNull(observation.connection()).generation
                    val quietEnd = world.clock.nowMillis() + 3_500L
                    until("A05 quiet 3.5 s", 3_600, answer = true) { world.clock.nowMillis() >= quietEnd }
                    pre("still settled after the quiet", krxReceived() && source.calls.all { it.fetchExited })
                    // The new KRX command is the operational lease renewal (lease 660 s, renewed 180 s early). Every other command is
                    // answered meanwhile; the renewal is held for the stimulus.
                    var renewal: KrxCommandAttempt? = null
                    val renewalDeadline = world.clock.nowMillis() + 600_000L
                    while (renewal == null && world.clock.nowMillis() < renewalDeadline) {
                        world.advance(1_000)
                        awaitNow("renewal wait IO settles") { source.calls.all { it.fetchExited } }
                        openNewWires()
                        server.answerPending(refuseKrx = false) { S64HeadWorld.K in it.topics }
                        renewal = sent.lastOrNull { S64HeadWorld.K in it.topics && it.requestId !in server.answered }
                    }
                    val renewed = checkNotNull(renewal) { "precondition(K1j-$route): the lease renewal sent a new KRX command" }
                    pre("the new KRX command is a renewal on the settled connection: ${renewed.source}",
                        renewed.source == KrxAttemptSource.RENEWAL && world.wires.size == 1 &&
                            renewed.connectionGeneration == settledGeneration && observation.connection()?.generation == settledGeneration &&
                            krxReceived())
                    val renewalWire = world.wire
                    val renewalRequest = server.requestsOn(renewalWire).single { it.requestId == renewed.requestId }
                    trace.event("M1K_RENEWAL", "request=${renewed.requestId} at=${world.clock.nowMillis()} marker=${renewed.marker}")
                    val oldIdentity = world.identity
                    val oldGrant = renewed.generalLifetime.grant
                    val d0 = input("diagnostics before the stimuli") { pac.recheckDiagnostics() }
                    val fetchesBefore = source.calls.size
                    val queriesBefore = queries.size
                    val reservedBefore = world.issuer.reserved.size
                    fun changeGeneration() {
                        world.changeLiveUidOnly(world.identity.uid)
                        binderListener?.invoke(world.identity)
                        world.deliverIdentity()
                    }
                    val t0 = world.clock.nowMillis()
                    val stimuli = linkedMapOf<String, Long>()
                    source.holdNextBody = true
                    if (route == "MK") {
                        stimuli["krx-refusal"] = world.clock.nowMillis()
                        server.answer(renewalRequest, refuseKrx = true, target = renewalWire)
                        awaitContract("$c/krx-refusal-accepted") {
                            pac.krxContestView?.rejectedPermit == renewed.marker.permitSeq && world.issuer.reserved.drop(reservedBefore)
                                .any { it.marker == renewed.marker && it.reservation in world.issuer.processed }
                        }
                        awaitNow("the refusal's scheduled query is in flight and held") {
                            source.calls.size > fetchesBefore && source.calls.last().let {
                                it.query.origin == QueryOrigin.SCHEDULED && it.bodyStarted && !it.bodyEnded
                            }
                        }
                        source.holdNextBody = true
                        stimuli["generation"] = world.clock.nowMillis()
                        changeGeneration()
                        stimuli["purchase"] = world.clock.nowMillis()
                        pac.onLocalPremiumSignal()
                        world.drain()
                    } else {
                        stimuli["generation"] = world.clock.nowMillis()
                        changeGeneration()
                        awaitNow("the new binding's ask is in flight and held") {
                            source.calls.size > fetchesBefore && source.calls.last().let { it.bodyStarted && !it.bodyEnded }
                        }
                        val contestBefore = pac.krxContestView
                        stimuli["krx-refusal"] = world.clock.nowMillis()
                        server.answer(renewalRequest, refuseKrx = true, target = renewalWire)
                        stimuli["purchase"] = world.clock.nowMillis()
                        pac.onLocalPremiumSignal()
                        awaitNow("the stimuli's immediate consequences settle") { source.calls.all { it.fetchExited || it.held } }
                        // Old grant stale0 belongs to this sub-column only. The session reads the live identity itself
                        // (liveFence), so the old connection's refusal is dropped before any reservation; PAC's own grant check
                        // is the second, redundant defence (a mutation removing it is expected to survive as protected).
                        val oldRefusals = world.issuer.reserved.drop(reservedBefore).filter { it.grant == oldGrant }
                        trace.event("M1K_OLD_GRANT_REFUSAL", "reservations=${oldRefusals.size} " +
                            "droppedBefore=${if (oldRefusals.isEmpty()) "issuer (session identity guard)" else "PAC"} contest=${pac.krxContestView}")
                        assertEquals("$c/old-grant-refusal-no-contest-effect", contestBefore?.rejectedPermit, pac.krxContestView?.rejectedPermit)
                        assertTrue("$c/old-grant-refusal-no-scheduled-query", queries.drop(queriesBefore).none {
                            it.origin == QueryOrigin.SCHEDULED && it.intent == RefreshIntent.FORCE_ENTITLEMENTS
                        })
                    }
                    assertTrue("$c/three-stimuli-within-2s: $stimuli", stimuli.values.max() - t0 <= 2_000)
                    val newIdentity = world.identity
                    pre("a same-user generation change", newIdentity.uid == oldIdentity.uid &&
                        newIdentity.authGeneration == oldIdentity.authGeneration + 1)
                    awaitContract("$c/generation-accepted") {
                        pac.state.value.uid == newIdentity.uid && pac.state.value.authGeneration == newIdentity.authGeneration
                    }
                    val d1 = input("diagnostics after the stimuli") { pac.recheckDiagnostics() }
                    assertTrue("$c/binding-epoch-rose ${d0.bindingEpoch} -> ${d1.bindingEpoch}", d1.bindingEpoch > d0.bindingEpoch)
                    val held = source.calls.drop(fetchesBefore).filter { it.held }
                    trace.event("M1K_HELD", held.joinToString { it.describe() })
                    // The first held answer is released alone. In K it is the old binding's scheduled query; nothing of it may
                    // reach the new generation's state.
                    val firstHeld = held.first()
                    firstHeld.release.countDown()
                    awaitNow("the first held body ends") { firstHeld.bodyEnded && firstHeld.fetchExited }
                    world.advance(1_000) // As C02M-M1a: the state is read 1 s after the old answer, the rest still held.
                    awaitNow("one second after the first release") { source.calls.all { it.fetchExited || it.held } }
                    val afterFirstRelease = pac.state.value
                    trace.event("M1K_AFTER_FIRST_RELEASE", "state=$afterFirstRelease query=${firstHeld.query}")
                    if (route == "MK") {
                        assertTrue("$c/old-binding-answer-not-applied: $afterFirstRelease",
                            afterFirstRelease.authGeneration == newIdentity.authGeneration &&
                                afterFirstRelease.state != PremiumAccessState.PremiumConfirmed)
                    }
                    source.releaseAll()
                    rest.autoSuccess = true // After the stimuli the server serves KRX again; a new permit's snapshot is answered.
                    until("$c/new-binding-settles", 30_000, answer = true) {
                        pac.state.value == OwnedPremiumAccess(newIdentity.uid, newIdentity.authGeneration,
                            PremiumAccessState.PremiumConfirmed) && source.calls.all { it.fetchExited }
                    }
                    // The probe and any retry must end: run to t0 + 90 s answering every command, then require a 25 s quiet tail.
                    while (world.clock.nowMillis() < t0 + 90_000L) {
                        world.advance(100)
                        awaitNow("tail IO settles") { source.calls.all { it.fetchExited } }
                        openNewWires()
                        server.answerPending(refuseKrx = false)
                        rest.calls.filter { it.physical && !it.reply.isCompleted }.forEach {
                            it.reply.complete(K1jReply(200, k1jFrame(it.topic, 1555.0, world.clock.nowMillis())))
                        }
                    }
                    val lastFetchAt = queries.maxOf { it.at }
                    val dEnd = input("diagnostics at the end") { pac.recheckDiagnostics() }
                    // Purchase stimulus: PROBE_DELAY_MILLIS[0] = 0, so its first tick ran while the ask was held (a latch return,
                    // no query registered); its next tick finds PremiumConfirmed and ends (PAC:1436-1442, :3587).
                    trace.event("M1K_RECORD", "route=$route stimuli=$stimuli held=${held.map { it.query }} " +
                        "queries=${queries.map { "${it.at}:${it.origin}:${it.intent}" }} fetches=${source.calls.size} " +
                        "maxBodiesTogether=${source.maxBodiesTogether} wire=${world.wires.map { w -> server.requestsOn(w).map { "${it.requestId}:${it.type}:${it.topics}" } }} " +
                        "rest=${rest.calls.map { "${it.topic}:${it.outcome?.javaClass?.simpleName}" }} reservations=${world.issuer.reserved.size} " +
                        "diagnostics before=$d0 after=$d1 end=$dEnd state=${pac.state.value}")
                    assertEquals("$c/latest-binding-settled", OwnedPremiumAccess(newIdentity.uid, newIdentity.authGeneration,
                        PremiumAccessState.PremiumConfirmed), pac.state.value)
                    assertTrue("$c/probe-and-retry-ended: last query at $lastFetchAt", t0 + 90_000L - lastFetchAt >= 25_000L)
                    // Not a PAC-only settle: the session's socket path came back for the new generation. Each delivery on the
                    // replacement wire was validated by the canonical receive count when the server sent it.
                    val replacement = world.wires.last()
                    assertTrue("$c/general-ws-received-after-the-change: ${server.delivered.filter { it.first === replacement }.map { it.second }}",
                        replacement !== renewalWire && S64HeadWorld.G.all { t -> server.delivered.any { it.first === replacement && it.second == t } })
                    assertTrue("$c/one-replacement-connection: ${opened.size} opened of ${world.wires.size}",
                        opened.size == 2 && observation.connection() != null)
                    assertEquals("$c/nothing-owed-at-end", null, dEnd.owedIntent)
                    assertTrue("$c/nothing-in-flight-at-end", dEnd.inFlightOrders.isEmpty())
                } finally {
                    source.releaseAll()
                    binderJob.cancel()
                }
                return@runTest
            }
            if (route == "RB1" || route == "RB2") {
                val c = if (route == "RB1") "contract(K1x):K1r-B1" else "contract(K1x):K1r-B2"
                input("normal visible approval") {
                    pac.onIdentityChanged(world.identity).applied()
                    pac.refresh(RefreshIntent.FORCE_PREMIUM)
                }
                pre("normal premium approval reached", world.snapshot.facts.userAllowed)
                world.start()
                world.open()
                awaitNow("initial real K WS send") { sent.any { S64HeadWorld.K in it.topics } }
                val segments = linkedMapOf<Long, Long>() // permit -> virtual time of its joined segment
                var firstRefusalAt: Long? = null
                // One permit's segment: both refusals of the same original permit reach PAC in the route's order.
                suspend fun segment(attempt: KrxCommandAttempt, firstSegment: Boolean) {
                    val permit = attempt.marker.permitSeq
                    val request = world.sends.single { it.second && it.first.requestId == attempt.requestId }.first
                    // REST is issued before the socket answers (no ACK yet); bootstrap issuance is paced, so let time run.
                    fun restIssued() = rest.calls.any { it.issue?.marker == attempt.marker && it.physical && !it.returned }
                    val deadline = world.clock.nowMillis() + 10_000L
                    while (!restIssued() && world.clock.nowMillis() < deadline) {
                        world.advance(100)
                        awaitNow("pacing IO settles") { source.calls.all { call -> call.fetchExited } }
                    }
                    pre("p=$permit: the KRX REST bootstrap is in flight before any ACK", restIssued() &&
                        observation.connection()?.pendingRequestId == attempt.requestId)
                    val call = rest.calls.single { it.issue?.marker == attempt.marker && it.physical && !it.returned }
                    val before = world.issuer.reserved.size
                    val queriesBefore = queries.size
                    fun wsRefusal() {
                        if (firstSegment && firstRefusalAt == null) firstRefusalAt = world.clock.nowMillis()
                        server.answer(request, refuseKrx = true)
                    }
                    fun restRefusal() {
                        if (firstSegment && firstRefusalAt == null) firstRefusalAt = world.clock.nowMillis()
                        call.reply.complete(K1jReply(404, """{"error":"unknown_topic","supported_topics":[]}"""))
                        awaitNow("REST service returns") { call.returned }
                    }
                    if (route == "RB1") {
                        // k1rb1/report.r1.md §4: PAC's refusal path suspends in the real store load (PAC:1926,
                        // DataStoreAccessEpochStore:130-141). Holding that load's return once, with the record unchanged, lets
                        // the session reserve the REST result while the WS refusal waits for its decision.
                        awaitNow("ready work settles before the gate") { source.calls.all { it.fetchExited } }
                        pre("p=$permit: no store gate or identity fault is armed",
                            loads.gate == null && world.h.source.identityGate == null && world.h.source.identityNulls == 0)
                        val contestBefore = pac.krxContestView?.rejectedPermit
                        val gate = CompletableDeferred<Unit>()
                        loads.gate = gate
                        try {
                            wsRefusal()
                            awaitNow("WS refusal reserved and PAC's store load held") {
                                world.issuer.reserved.size == before + 1 && loads.gate == null && !gate.isCompleted
                            }
                            trace.event("K1RB_LOAD_GATE", "permit=$permit heldIn=${loads.heldIn}")
                            pre("p=$permit: the held load is the refusal path's", loads.heldIn.any { it.startsWith("PremiumAccessCoordinator.onTopicRejected") })
                            val rW = world.issuer.reserved[before]
                            pre("p=$permit: the held reservation is the WS original", rW.arity == 3 && rW.marker == attempt.marker)
                            pre("p=$permit: the gated refusal has no effect yet", queries.size == queriesBefore &&
                                pac.krxContestView?.rejectedPermit == contestBefore && call.job.isActive && !call.returned)
                            restRefusal()
                            awaitNow("REST result reserved behind the gated PAC") { world.issuer.reserved.size == before + 2 }
                            val rR = world.issuer.reserved[before + 1]
                            pre("p=$permit: two distinct original reservations, WS first",
                                rW.reservation !== rR.reservation && rW.reservation.order < rR.reservation.order)
                            pre("p=$permit: still no effect before the gate opens",
                                queries.size == queriesBefore && pac.krxContestView?.rejectedPermit == contestBefore)
                        } finally {
                            if (!gate.isCompleted) gate.complete(Unit)
                        }
                    } else { restRefusal(); wsRefusal() }
                    pre("p=$permit: the REST refusal was classified Unsupported", call.outcome is TopicSnapshotOutcome.Unsupported)
                    trace.event("K1RB_ORDER", "route=$route permit=$permit before=$before reservedAtRestReturn=${call.reservedAtReturn}")
                    if (route == "RB1") assertEquals("$c/ws-reservation-before-rest-result p=$permit", before + 1, call.reservedAtReturn)
                    else assertEquals("$c/rest-result-before-socket-refusal p=$permit", before, call.reservedAtReturn)
                    awaitContract("$c/two-original-handovers p=$permit") { world.issuer.reserved.size >= before + 2 }
                    val handovers = world.issuer.reserved.drop(before)
                    assertEquals("$c/exactly-two-handovers p=$permit", 2, handovers.size)
                    assertTrue("$c/both-carry-the-original-marker-arity3 p=$permit: $handovers",
                        handovers.all { it.arity == 3 && it.marker == attempt.marker })
                    awaitContract("$c/pac-processed-both p=$permit") { handovers.all { it.reservation in world.issuer.processed } }
                    assertEquals("$c/rejected-permit-is-p p=$permit", permit, pac.krxContestView?.rejectedPermit)
                    val ledger = pac.rejectionView(handovers.first().reservation.grant)
                    assertTrue("$c/both-ended-in-ledger p=$permit: $ledger", handovers.none { it.reservation.order in ledger.pendingOrders })
                    assertEquals("$c/first-handover-took-effect p=$permit", handovers[0].reservation.order, ledger.latestCurrent)
                    assertEquals("$c/second-handover-reported p=$permit", handovers[1].reservation.order, ledger.latestReported)
                    awaitNow("the joined segment settles") { source.calls.all { it.fetchExited } }
                    val segmentQueries = queries.drop(queriesBefore).filter { it.at <= world.clock.nowMillis() }
                    if (firstSegment) assertEquals("$c/one-joined-query-for-the-segment: $segmentQueries", 1, segmentQueries.size)
                    segments[permit] = world.clock.nowMillis()
                }
                val first = sent.last { S64HeadWorld.K in it.topics }
                segment(first, firstSegment = true)
                // k1rb2/report.r1.md §5: the 60 s from the first actual refusal keep the server policy. Every subscribe and
                // unsubscribe is answered with the connection's whole state; KRX is refused on the socket and every physically
                // sent KRX REST answers unknown_topic. A later PERMIT's socket answer waits for its own REST result; if that
                // result hides KRX first, the product cancels the KRX-only command (TSC:905-914) and the late socket refusal
                // makes no reservation. That is recorded, not failed. Only the first segment owns the two-handover duty.
                val windowEnd = checkNotNull(firstRefusalAt) + 60_000L
                pre("the first segment fits inside the refusal window", world.clock.nowMillis() < windowEnd)
                val windowWs = linkedMapOf<Long, Int>() // permit -> WS reservations made by its socket refusal
                val windowRestPermits = linkedSetOf<Long>()
                val windowRest = mutableListOf<String>()
                while (world.clock.nowMillis() < windowEnd) {
                    awaitNow("window IO settles") { source.calls.all { it.fetchExited } }
                    for (call in rest.calls.toList().filter {
                        it.topic == S64HeadWorld.K && it.physical && !it.returned && !it.reply.isCompleted && !it.job.isCancelled
                    }) {
                        val issue = checkNotNull(call.issue)
                        val before = world.issuer.reserved.size
                        call.reply.complete(K1jReply(404, """{"error":"unknown_topic","supported_topics":[]}"""))
                        awaitNow("window REST service returns or is cancelled") { call.returned || call.job.isCancelled }
                        windowRest += "${issue.marker.permitSeq}@${world.clock.nowMillis()}:${if (call.returned) call.outcome else "cancelled"}"
                        if (!call.returned) continue
                        assertTrue("$c/window-rest-unsupported p=${issue.marker.permitSeq}", call.outcome is TopicSnapshotOutcome.Unsupported)
                        fun originals() = world.issuer.reserved.drop(before).filter {
                            it.arity == 3 && it.grant == issue.owner.grant && it.marker == issue.marker
                        }
                        awaitContract("$c/window-rest-original-handover p=${issue.marker.permitSeq}") { originals().isNotEmpty() }
                        awaitContract("$c/window-rest-pac-completed p=${issue.marker.permitSeq}") {
                            val view = pac.rejectionView(issue.owner.grant)
                            originals().all { it.reservation in world.issuer.processed && it.reservation.order !in view.pendingOrders } &&
                                pac.krxContestView?.rejectedPermit == issue.marker.permitSeq
                        }
                        windowRestPermits += issue.marker.permitSeq
                    }
                    for ((request, accepted, sentAt) in world.sends.toList()) {
                        if (!accepted || request.requestId in server.answered) continue
                        val attempt = if (request.type == "subscribe" && S64HeadWorld.K in request.topics) {
                            sent.single { it.requestId == request.requestId && S64HeadWorld.K in it.topics }
                        } else null
                        if (attempt?.source == KrxAttemptSource.PERMIT && rest.calls.none {
                                it.issue?.marker == attempt.marker && it.physical && (it.returned || it.job.isCancelled)
                            }) {
                            pre("a new PERMIT's REST result is handled before its ACK deadline",
                                world.clock.nowMillis() - sentAt < 19_000L)
                            continue
                        }
                        val before = world.issuer.reserved.size
                        server.answer(request, refuseKrx = true)
                        if (attempt != null) {
                            val additions = world.issuer.reserved.drop(before)
                            assertTrue("$c/window-ws-originals-if-delivered p=${attempt.marker.permitSeq}", additions.all {
                                it.arity == 3 && it.grant == attempt.generalLifetime.grant && it.marker == attempt.marker
                            })
                            windowWs[attempt.marker.permitSeq] = additions.size
                            trace.event("K1RB_WINDOW_WS_POLICY", "request=${request.requestId} permit=${attempt.marker.permitSeq} " +
                                "source=${attempt.source} wsReservations=${additions.size} (zero may follow PERMIT cancellation)")
                        }
                    }
                    awaitNow("window consequences settle") { source.calls.all { it.fetchExited } }
                    world.advance(minOf(100L, windowEnd - world.clock.nowMillis()))
                }
                assertTrue("$c/repeated-ws-policy-on-new-permit: $windowWs", windowWs.keys.any { it > first.marker.permitSeq })
                assertTrue("$c/reissued-rest-refused-and-pac-completed: rest=$windowRestPermits ws=$windowWs",
                    windowRestPermits.any { it > first.marker.permitSeq && it in windowWs })
                assertTrue("$c/http-outcome-never-krx-required", source.calls.all { it.fetchExited })
                trace.event("K1RB_RECORD", "route=$route start=$firstRefusalAt end=$windowEnd segments=$segments ws=$windowWs " +
                    "windowRest=$windowRest queries=${queries.map { "${it.at}:${it.origin}:${it.intent}" }} " +
                    "wire=${world.sends.map { "${it.first.requestId}:${it.first.type}:${it.first.topics}:${it.second}@${it.third}" }}")
                return@runTest
            }
            if (route == "RA") {
                val c = "contract(K1x):K1r-A"
                input("normal visible approval") {
                    pac.onIdentityChanged(world.identity).applied()
                    pac.refresh(RefreshIntent.FORCE_PREMIUM)
                }
                pre("normal premium approval reached", world.snapshot.facts.userAllowed)
                world.start()
                world.open()
                awaitNow("initial real K WS send") { sent.any { S64HeadWorld.K in it.topics } }
                val refused = linkedMapOf<Long, Long>()
                var firstRefusalAt: Long? = null
                suspend fun refuseOverRest(attempt: KrxCommandAttempt) {
                    val permit = attempt.marker.permitSeq
                    val request = world.sends.single { it.second && it.first.requestId == attempt.requestId }.first
                    server.answer(request, refuseKrx = false) // The socket accepts KRX; no KRX snapshot is delivered.
                    // Bootstrap issuance is paced (TopicSessionCoordinator issueOwedBootstraps), so let virtual time run, bounded.
                    fun restIssued() = rest.calls.any { it.issue?.marker == attempt.marker && it.physical && !it.returned }
                    val issueDeadline = world.clock.nowMillis() + 10_000L
                    while (!restIssued() && world.clock.nowMillis() < issueDeadline) {
                        world.advance(100)
                        awaitNow("pacing IO settles") { source.calls.all { call -> call.fetchExited } }
                    }
                    assertTrue("$c/rest-issued p=$permit within 10 s of the accepting ACK", restIssued())
                    val call = rest.calls.single { it.issue?.marker == attempt.marker && it.physical && !it.returned }
                    val before = world.issuer.reserved.size
                    trace.event("K1RA_REST_INPUT", "permit=$permit issue=${call.issue}")
                    if (firstRefusalAt == null) firstRefusalAt = world.clock.nowMillis()
                    call.reply.complete(K1jReply(404, """{"error":"unknown_topic","supported_topics":[]}"""))
                    awaitNow("REST service returns") { call.returned }
                    assertTrue("$c/rest-classified-unsupported p=$permit", call.outcome is TopicSnapshotOutcome.Unsupported)
                    awaitContract("$c/handover-arrives p=$permit") { world.issuer.reserved.size > before }
                    val additions = world.issuer.reserved.drop(before)
                    assertEquals("$c/first-effect-one-handover p=$permit", 1, additions.size)
                    val handover = additions.single()
                    assertTrue("$c/first-effect-original-marker p=$permit", handover.arity == 3 && handover.marker == attempt.marker)
                    awaitContract("$c/first-effect-pac-accepted p=$permit") {
                        handover.reservation in world.issuer.processed && pac.krxContestView?.rejectedPermit == permit
                    }
                    awaitContract("$c/first-effect-ended-in-ledger p=$permit") {
                        handover.reservation.order !in pac.rejectionView(handover.grant).pendingOrders
                    }
                    refused[permit] = world.clock.nowMillis()
                    trace.event("K1RA_PAC_ACCEPTED", "permit=$permit contest=${pac.krxContestView}")
                }
                refuseOverRest(sent.last { S64HeadWorld.K in it.topics })
                // k1rb2/report.r1.md §5-6: the 60 s run from the first actual REST refusal; general commands are answered.
                val windowEnd = checkNotNull(firstRefusalAt) + 60_000L
                while (world.clock.nowMillis() < windowEnd) {
                    world.advance(minOf(100L, windowEnd - world.clock.nowMillis()))
                    awaitNow("window IO settles") { source.calls.all { call -> call.fetchExited } }
                    server.answerPending(refuseKrx = false) { S64HeadWorld.K in it.topics }
                    val outstanding = sent.lastOrNull { S64HeadWorld.K in it.topics }
                    if (outstanding != null && outstanding.marker.permitSeq !in refused &&
                        observation.connection()?.pendingRequestId == outstanding.requestId) refuseOverRest(outstanding)
                }
                assertTrue("$c/repeated-transition reissued bootstrap refused again at least once: $refused", refused.size >= 2)
                assertTrue("$c/http-outcome-never-krx-required",
                    source.calls.isNotEmpty() && source.calls.all { it.fetchExited })
                trace.event("K1RA_RECORD", "refusals=$refused queries=${queries.map { "${it.at}:${it.origin}:${it.intent}" }}")
                return@runTest
            }
            if (route == "WREP") {
                val c = "contract(K1x):K1w"
                input("normal visible approval") {
                    pac.onIdentityChanged(world.identity).applied()
                    pac.refresh(RefreshIntent.FORCE_PREMIUM)
                }
                pre("normal premium approval reached", world.snapshot.facts.userAllowed)
                world.start()
                world.open()
                awaitNow("initial real K WS send") { sent.any { S64HeadWorld.K in it.topics } }
                val rejected = linkedMapOf<Long, Long>() // permit -> virtual time of the refusal input
                var firstRefusalAt: Long? = null
                suspend fun refuse(attempt: KrxCommandAttempt) {
                    val permit = attempt.marker.permitSeq
                    val before = world.issuer.reserved.size
                    val request = world.sends.single { it.second && it.first.requestId == attempt.requestId }.first
                    trace.event("K1W_REFUSAL_INPUT", "permit=$permit request=${attempt.requestId} marker=${attempt.marker}")
                    if (firstRefusalAt == null) firstRefusalAt = world.clock.nowMillis()
                    server.answer(request, refuseKrx = true)
                    awaitContract("$c/handover-arrives p=$permit") { world.issuer.reserved.size > before }
                    val additions = world.issuer.reserved.drop(before)
                    assertEquals("$c/first-effect-one-handover p=$permit", 1, additions.size)
                    val handover = additions.single()
                    assertTrue("$c/first-effect-original-marker p=$permit", handover.arity == 3 && handover.marker == attempt.marker)
                    awaitContract("$c/first-effect-pac-accepted p=$permit") {
                        handover.reservation in world.issuer.processed && pac.krxContestView?.rejectedPermit == permit
                    }
                    awaitContract("$c/first-effect-ended-in-ledger p=$permit") {
                        handover.reservation.order !in pac.rejectionView(handover.grant).pendingOrders
                    }
                    rejected[permit] = world.clock.nowMillis()
                    trace.event("K1W_PAC_ACCEPTED", "permit=$permit contest=${pac.krxContestView} " +
                        "hidden=${!world.snapshot.facts.capabilityAllowed} owed=${pac.recheckDiagnostics()}")
                }
                val first = sent.last { S64HeadWorld.K in it.topics }
                val queriesBeforeFirst = queries.size
                refuse(first)
                // Let the first refusal's own consequences settle (its follow-up query), so the duplicate is compared against
                // a quiescent state rather than racing legitimate progress.
                awaitNow("first refusal's follow-up settles") { queries.size > queriesBeforeFirst && source.calls.all { call -> call.fetchExited } }
                // A second refusal of the same permit's command: the permit is already consumed, so no further effect.
                val contestAfterFirst = pac.krxContestView
                val queriesAfterFirst = queries.size
                val firstRequest = world.sends.single { it.second && it.first.requestId == first.requestId }.first
                world.ackOn(world.wire, firstRequest, mapOf(S64HeadWorld.K to "krx_entitlement_required"))
                awaitNow("duplicate refusal settles") { source.calls.all { call -> call.fetchExited } }
                assertEquals("$c/duplicate-same-permit-no-contest-change", contestAfterFirst, pac.krxContestView)
                assertEquals("$c/duplicate-same-permit-no-new-query", queriesAfterFirst, queries.size)
                // The 60 s window from the first actual refusal: every newly permitted KRX command is refused again by the
                // server, and every general command is answered (k1rb2/report.r1.md §5-6).
                val windowEnd = checkNotNull(firstRefusalAt) + 60_000L
                while (world.clock.nowMillis() < windowEnd) {
                    world.advance(minOf(100L, windowEnd - world.clock.nowMillis()))
                    awaitNow("window IO settles") { source.calls.all { call -> call.fetchExited } }
                    server.answerPending(refuseKrx = true) { S64HeadWorld.K in it.topics }
                    val outstanding = sent.lastOrNull { S64HeadWorld.K in it.topics }
                    if (outstanding != null && outstanding.marker.permitSeq !in rejected &&
                        observation.connection()?.pendingRequestId == outstanding.requestId) refuse(outstanding)
                }
                assertTrue("$c/repeated-transition re-permit and re-refusal at least once: $rejected", rejected.size >= 2)
                assertEquals("$c/permits-strictly-increase", rejected.keys.sorted(), rejected.keys.toList())
                trace.event("K1W_RECORD", "refusals=$rejected queries=${queries.map { "${it.at}:${it.origin}:${it.intent}" }} " +
                    "fetches=${source.calls.size} hiddenAtEnd=${!world.snapshot.facts.capabilityAllowed}")
                return@runTest
            }
            if (route == "E") {
                val c = "contract(K1x):K1e"
                input("normal premium approval with KRX hidden") {
                    pac.onIdentityChanged(world.identity).applied()
                    pac.refresh(RefreshIntent.FORCE_PREMIUM)
                }
                pre("premium approved", world.snapshot.facts.userAllowed)
                pre("PremiumConfirmed", pac.state.value.state == PremiumAccessState.PremiumConfirmed)
                pre("effective KRX capability is false", !world.snapshot.facts.capabilityAllowed)
                world.start()
                world.open()
                awaitNow("the general subscribe is sent") { world.sends.any { it.second && it.first.type == "subscribe" } }
                val generalRequest = world.sends.first { it.second && it.first.type == "subscribe" }.first
                pre("the first subscribe names the general topics", generalRequest.topics.toSet().containsAll(S64HeadWorld.G))
                server.answer(generalRequest, refuseKrx = true)
                // Bootstrap issuance is paced (k1rb2/report.r1.md §6): wait, bounded, for every general topic's REST to be
                // issued and to return, instead of judging only the calls that happened to exist already.
                val restDeadline = world.clock.nowMillis() + 10_000L
                fun restSettled() = rest.calls.map { it.topic }.toSet() == S64HeadWorld.G && rest.calls.all { it.returned }
                while (!restSettled() && world.clock.nowMillis() < restDeadline) {
                    world.advance(100)
                    awaitNow("bootstrap pacing IO settles") { source.calls.all { call -> call.fetchExited } }
                    server.answerPending(refuseKrx = true)
                }
                pre("every general topic's REST bootstrap was issued and returned: ${rest.calls.map { "${it.topic}:${it.returned}" }}",
                    restSettled())
                pre("every REST bootstrap is a general topic, Delivered: ${rest.calls.map { "${it.topic}:${it.outcome}" }}",
                    rest.calls.all { it.topic in S64HeadWorld.G && it.outcome is TopicSnapshotOutcome.Delivered })
                pre("the connection is live", observation.connection() != null)
                val entitlementsAfterPreparation = source.calls.size
                val reservationsAfterPreparation = world.issuer.reserved.size
                fun noKrx(stage: String) {
                    assertTrue("$c/k-subscribe-0 ($stage)", world.sends.none { S64HeadWorld.K in it.first.topics })
                    assertTrue("$c/k-command-0 ($stage)", sent.none { S64HeadWorld.K in it.topics })
                    assertTrue("$c/k-rest-0 ($stage)", rest.calls.none { it.topic == S64HeadWorld.K })
                    assertEquals("$c/refusal-handover-0 ($stage)", reservationsAfterPreparation, world.issuer.reserved.size)
                }
                noKrx("after preparation")
                val windowEnd = world.clock.nowMillis() + 60_000L
                while (world.clock.nowMillis() < windowEnd) {
                    world.advance(100)
                    awaitNow("window IO settles") { source.calls.all { call -> call.fetchExited } }
                    server.answerPending(refuseKrx = true)
                    noKrx("at ${world.clock.nowMillis()}")
                }
                assertEquals("$c/entitlements-0-in-window", entitlementsAfterPreparation, source.calls.size)
                assertEquals("$c/nothing-owed", null, input("owed after window") { pac.recheckDiagnostics().owedIntent })
                // Not a vacuous zero: every general topic was received over the socket and the premium state is unchanged.
                assertTrue("$c/general-still-live", observation.connection() != null &&
                    S64HeadWorld.G.all { observation.canonical.stateFor(it).receiveGeneration > 0 })
                assertEquals("$c/premium-confirmed-at-end", PremiumAccessState.PremiumConfirmed, pac.state.value.state)
                assertTrue("$c/krx-still-hidden-at-end", !world.snapshot.facts.capabilityAllowed)
                noKrx("after the window")
                trace.event("CONTRACT_END", "route=E entitlements=${source.calls.size}")
                return@runTest
            }
            input("normal visible approval") {
                pac.onIdentityChanged(world.identity).applied()
                pac.refresh(RefreshIntent.FORCE_PREMIUM)
            }
            pre("normal premium approval reached", world.snapshot.facts.userAllowed)
            world.start()
            world.open() // Leave the actual FIRST K command awaiting ACK.
            awaitNow("initial real K WS send") {
                sent.any { S64HeadWorld.K in it.topics }
            }
            val first = sent.last { S64HeadWorld.K in it.topics }
            val p = first.marker.permitSeq
            val originalGrant = first.generalLifetime.grant
            val originalKey = checkNotNull(pac.krxContestView).key
            pre("initial p is current and unconsumed", p > 0 && world.snapshot.krxPermitSeq == p &&
                checkNotNull(pac.krxContestView).rejectedPermit < p)
            trace.event("P_INITIAL", "p=$p grant=$originalGrant key=$originalKey marker=${first.marker}")

            // Same EXPLICIT_ISSUER_INPUT as S6Krx4Rig.armReapproval, before the K refusal.
            // The one unreadable identity observation models H1'; it writes no PAC/permit/marker state.
            val premiumAt = world.clock.nowMillis()
            world.h.source.identityNulls = 1
            trace.event("PREMIUM_REFUSAL_INPUT", "grant=$originalGrant identityReadUnavailableOnce=true")
            input("premium refusal retaining a stronger demand") {
                pac.onTopicRejected(originalGrant, listOf(TopicRejectionReason.PREMIUM_REQUIRED))
            }
            pre("identity-read fault consumed", world.h.source.identityNulls == 0)
            pre("stronger FP demand and same standing grant", input("FP premise") {
                pac.recheckDiagnostics().owedIntent == RefreshIntent.FORCE_PREMIUM
            } && world.snapshot.facts.token == originalGrant && world.snapshot.facts.tokenStanding)
            trace.event("PREMIUM_REFUSAL_ACCEPTED", "view=${pac.rejectionView(originalGrant)}")

            source.holdNextBody = true
            val reservationsBeforeFirst = world.issuer.reserved.size
            val firstRequest = world.sends.single { it.second && it.first.requestId == first.requestId }.first
            trace.event("FIRST_K_REFUSAL_INPUT", "request=${first.requestId} p=$p marker=${first.marker}")
            world.ackOn(world.wire, firstRequest, mapOf(S64HeadWorld.K to "krx_entitlement_required"))
            val firstReservation = awaitAccepted("first-p", reservationsBeforeFirst, first.marker, originalGrant)
            pre("first refusal reached AWAITING", pac.krxContestView?.state?.phase == KrxContestPhase.AWAITING)

            // The K handover merges into the existing FP demand. Do not call refresh(SCHEDULED).
            world.advance(maxOf(0L, premiumAt + PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS - world.clock.nowMillis()))
            awaitNow("real SCHEDULED HTTP body read is held") {
                source.calls.any { it.held && it.bodyStarted && !it.bodyEnded && !it.fetchExited }
            }
            val held = source.calls.single { it.held }
            pre("held query is rejection-derived SCHEDULED FP", held.query.origin == QueryOrigin.SCHEDULED &&
                held.query.intent == RefreshIntent.FORCE_PREMIUM && held.freshPremium &&
                held.query.order > firstReservation.reservation.order &&
                held.query.at >= premiumAt + PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS)
            pre("held query has not already been cancelled", !held.job.isCancelled)
            trace.event("HELD_SCHEDULED", held.describe())
            state("before-CALLER-FE")

            // FE bypasses neither the absolute floor nor any state checks. At this boundary the
            // floor has elapsed; FE has no FP token and cannot settle the stronger premium demand.
            val beforeCaller = queries.size
            trace.event("CALLER_FE_INPUT", "heldOrder=${held.query.order} p=$p")
            input("normal CALLER FE StableActive(true)") { pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
            val callerQueries = queries.drop(beforeCaller).filter { it.origin == QueryOrigin.CALLER }
            pre("one later actual caller FE", callerQueries.size == 1 &&
                callerQueries.single().intent == RefreshIntent.FORCE_ENTITLEMENTS &&
                callerQueries.single().order > held.query.order)
            val pPrime = world.snapshot.krxPermitSeq
            pre("operationally issued current unconsumed p-prime", pPrime > p &&
                pac.krxContestView?.state?.phase == KrxContestPhase.OPEN &&
                checkNotNull(pac.krxContestView).rejectedPermit < pPrime && world.snapshot.facts.capabilityAllowed)
            pre("same contest key and standing grant", pac.krxContestView?.key == originalKey &&
                world.snapshot.facts.token == originalGrant && world.snapshot.facts.tokenStanding)
            pre("scheduled body still running after p-prime issue", held.bodyStarted && !held.bodyEnded &&
                !held.fetchExited && !held.job.isCancelled)
            trace.event("P_PRIME_ISSUED", "p=$p pPrime=$pPrime caller=${callerQueries.single()} " +
                "grant=$originalGrant key=${pac.krxContestView?.key} fence=${world.snapshot.facts.recordFence} ${held.describe()}")

            awaitNow("p-prime real K send") { sent.any { S64HeadWorld.K in it.topics && it.marker.permitSeq == pPrime } }
            val additional = sent.last { S64HeadWorld.K in it.topics && it.marker.permitSeq == pPrime }
            val request = world.sends.single { it.second && it.first.requestId == additional.requestId }.first
            pre("p-prime marker belongs to current grant and epoch", additional.generalLifetime.grant == originalGrant &&
                additional.marker.krxCapabilityEpoch == world.snapshot.facts.recordFence?.krxCapabilityEpoch)
            pre("actual outstanding p-prime WS command", observation.connection()?.pendingRequestId == additional.requestId)
            trace.event("P_PRIME_K_SENT", "request=${additional.requestId} stamp=${additional.stamp} " +
                "marker=${additional.marker} grant=$originalGrant ${held.describe()}")

            val beforeAdditional = world.issuer.reserved.size
            // review/report.r1.md F1: the baseline is fixed before the additional input, so a query registered while that refusal
            // is processed is counted rather than absorbed.
            val queriesBeforeAdditional = queries.size
            if (route == "R") {
                world.ackOn(world.wire, request) // Keep WS accepted; REST is the sole additional refusal.
                awaitNow("p-prime real K REST endpoint") {
                    rest.calls.any { it.issue?.marker == additional.marker && it.physical && !it.returned }
                }
                val k = rest.calls.single { it.issue?.marker == additional.marker && it.physical && !it.returned }
                pre("REST original issue uses current grant", k.issue?.owner?.grant == originalGrant)
                pre("held scheduled body at REST input", !held.bodyEnded && !held.fetchExited && !held.job.isCancelled)
                trace.event("ADDITIONAL_REST_INPUT", "issue=${k.issue} ${held.describe()}")
                k.reply.complete(K1jReply(404, """{"error":"unknown_topic","supported_topics":[]}"""))
                awaitNow("real REST service produced Unsupported") { k.returned }
                pre("HTTP classification produced Unsupported", k.outcome is TopicSnapshotOutcome.Unsupported)
            } else {
                pre("held scheduled body at WS input", !held.bodyEnded && !held.fetchExited && !held.job.isCancelled)
                trace.event("ADDITIONAL_WS_INPUT", "request=${request.requestId} marker=${additional.marker} ${held.describe()}")
                world.ackOn(world.wire, request, mapOf(S64HeadWorld.K to "krx_entitlement_required"))
            }
            // --- Locked expectation (judgment.r1.md §2 table), identical for W and R once PAC has the refusal. ---
            val c = "contract(K1x):K1j-$route"
            awaitContract("$c/handover-arrives") { world.issuer.reserved.size > beforeAdditional }
            val additions = world.issuer.reserved.drop(beforeAdditional)
            assertEquals("$c/handover-exactly-one", 1, additions.size)
            val handover = additions.single()
            assertTrue("$c/handover-original-marker-arity3: $handover", handover.arity == 3 &&
                handover.marker == additional.marker && handover.grant == originalGrant)
            awaitContract("$c/pac-accepted") {
                val view = pac.rejectionView(originalGrant)
                handover.reservation in world.issuer.processed && handover.reservation.order !in view.pendingOrders &&
                    view.latestCurrent == handover.reservation.order
            }
            assertEquals("$c/rejected-permit-is-p-prime", pPrime, pac.krxContestView?.rejectedPermit)
            assertEquals("$c/contest-awaiting", KrxContestPhase.AWAITING, pac.krxContestView?.state?.phase)
            assertTrue("$c/held-scheduled-cancel-requested: ${held.describe()}", held.cancellationRequested)
            pre("original physical body and fetch still held at PAC acceptance", held.bodyStarted &&
                !held.bodyEnded && !held.fetchExited)
            trace.event("SIMULTANEOUS_REACHED", "p=$p pPrime=$pPrime marker=${additional.marker} ${held.describe()}")
            val queriesAtAcceptance = queriesBeforeAdditional

            // Termination: the driver releases the held body at virtual 5000; each end is its own duty.
            assertEquals("$c/no-followup-before-original-ends: ${queries.drop(queriesBeforeAdditional)}", queriesAtAcceptance, queries.size)
            trace.event("RELEASE_ORIGINAL_BODY", held.describe())
            held.release.countDown()
            awaitContract("$c/original-body-ends") { held.bodyEnded }
            awaitContract("$c/original-io-ends") { held.ioExited }
            awaitContract("$c/original-fetch-ends") { held.fetchExited }
            awaitContract("$c/original-job-completes") { held.job.isCompleted }
            assertTrue("$c/original-inflight-removed", input("original query registry") {
                held.query.order !in pac.recheckDiagnostics().inFlightOrders
            })
            assertEquals("$c/no-followup-before-original-ends-after-release", queriesAtAcceptance, queries.size)
            assertEquals("$c/virtual-clock-still-5000", 5_000L, world.clock.nowMillis())
            rest.releaseAll()
            rest.autoSuccess = true

            // Follow-up: none before virtual 15000, exactly one SCHEDULED FORCE_PREMIUM at 15000, one in the window to 20000.
            while (world.clock.nowMillis() < 15_000L) {
                world.advance(100)
                awaitNow("follow-up endpoint IO settles") { source.calls.all { call -> call.fetchExited } }
                if (world.clock.nowMillis() < 15_000L)
                    assertEquals("$c/no-followup-before-15000 at ${world.clock.nowMillis()}", queriesAtAcceptance, queries.size)
            }
            val followUps = queries.drop(queriesAtAcceptance)
            assertEquals("$c/one-scheduled-fp-followup-at-15000",
                listOf(Triple(15_000L, QueryOrigin.SCHEDULED, RefreshIntent.FORCE_PREMIUM)),
                followUps.map { Triple(it.at, it.origin, it.intent) })
            while (world.clock.nowMillis() < 20_000L) {
                world.advance(100)
                awaitNow("window endpoint IO settles") { source.calls.all { call -> call.fetchExited } }
            }
            assertEquals("$c/one-followup-in-window-to-20000", 1, queries.drop(queriesAtAcceptance).size)
            assertEquals("$c/demand-resolved-after-followup", null, input("final owed intent") { pac.recheckDiagnostics().owedIntent })
            trace.event("CONTRACT_END", "route=$route queries=$queries")
        } catch (failure: Throwable) {
            primary = failure
            trace.event("CONTRACT_FAILED", "type=${failure.javaClass.simpleName}")
            throw failure
        } finally {
            try {
                // KrxContestWiringContractTest KW15: the β factory's publication check is audited at teardown, failing body or not.
                try { beta.check.assertHeld() } finally {
                    source.releaseAll()
                    rest.releaseAll()
                    world.runtime.stop()
                    val root = checkNotNull(world.h.parts.scope.coroutineContext[Job])
                    root.cancel()
                    awaitNow("cleanup root settles", checkSource = false) { root.isCompleted }
                    world.close() // Delete the fixture's temporary DataStore only after its jobs settle.
                    trace.event("CLEANUP_END", "rootCompleted=${root.isCompleted}")
                }
            } catch (cleanup: Throwable) {
                val earlier = primary
                if (earlier == null) throw cleanup
                earlier.addSuppressed(cleanup)
            }
        }
    }
}

private class K1jTrace(private val row: String, private val now: () -> Long) {
    private var sequence = 0L
    private val started = System.nanoTime()
    fun event(name: String, detail: String) = synchronized(this) {
        println("K1J-TRACE row=$row seq=${++sequence} virtual=${now()} " +
            "realNanos=${System.nanoTime() - started} event=$name $detail")
    }
}

private data class K1jQuery(val order: Long, val at: Long, val origin: QueryOrigin,
    val intent: RefreshIntent, val reapprovalOnly: Boolean)

private class K1jFetch(val query: K1jQuery, val freshPremium: Boolean, val held: Boolean, val job: Job) {
    val release = CountDownLatch(if (held) 1 else 0)
    @Volatile var bodyStarted = false
    @Volatile var bodyEnded = false
    @Volatile var ioExited = false
    @Volatile var fetchExited = false
    @Volatile var cancellationRequested = false
    @Volatile var bodyFault: Throwable? = null
    fun describe() = "query=$query fresh=$freshPremium held=$held bodyStarted=$bodyStarted " +
        "bodyEnded=$bodyEnded ioExited=$ioExited fetchExited=$fetchExited cancelRequested=$cancellationRequested " +
        "jobActive=${job.isActive} jobCancelled=${job.isCancelled} jobCompleted=${job.isCompleted}"
}

/** Reads a private field for observation only; nothing is ever written through it. */
private fun k1jPeek(target: Any?, name: String): Any? = target?.let { t ->
    generateSequence<Class<*>>(t.javaClass) { it.superclass }
        .firstNotNullOfOrNull { k -> runCatching { k.getDeclaredField(name) }.getOrNull() }
        ?.apply { isAccessible = true }?.get(t)
}

/**
 * A valid WS or REST snapshot body. World.frame's tether body lacks the DTO's required arrays (TopicMessage.kt TetherTopicData,
 * k1rb2/report.r1.md §3), so the tether general path never delivered. The timestamp follows the virtual clock.
 */
private fun k1jFrame(topic: String, rate: Double, atMillis: Long): String {
    val stamp = kotlinx.datetime.Instant.fromEpochMilliseconds(1_790_385_600_000L + atMillis).toString()
    return when (topic) {
        S64HeadWorld.K -> """{"type":"snapshot","version":1,"topic":"$topic","data":{"usd_krw_futures":{"source":"krx","asset":"usd-krw-futures","rate":$rate,"timestamp":"$stamp"}}}"""
        "usdt:krw" -> """{"type":"snapshot","version":1,"topic":"usdt:krw","data":{"usdt_krw":[{"source":"upbit","asset":"usdt-krw","rate":$rate,"timestamp":"$stamp"}],"usd_krw_banks":[{"source":"kb","asset":"usd-krw","rate":$rate,"timestamp":"$stamp"}]}}"""
        "dxy:spot" -> """{"type":"snapshot","version":1,"topic":"dxy:spot","data":{"dxy":{"rate":99.0,"timestamp":"$stamp","source":"investing"}}}"""
        else -> """{"type":"snapshot","version":1,"topic":"$topic","data":{"banks":[{"source":"kb","asset":"${topic.removePrefix("fx:")}","rate":$rate,"timestamp":"$stamp"}]}}"""
    }
}

/**
 * k1rb2/report.r1.md §5: the server side of the socket as a local model, never product state. Every subscribe or unsubscribe
 * that reached the wire is answered once; `active_subscriptions` restates the connection's whole state; an accepted general
 * topic gets one valid WS snapshot after its ACK (the D16 fixture's order, S5FixtureServer.kt:123-157), checked by the
 * canonical receiveGeneration. A KRX snapshot is never sent: the rows that accept KRX withhold it.
 */
private class K1jServer(private val world: S64HeadWorld, private val observation: S64Observation,
    private val trace: K1jTrace, private val pre: (String, Boolean) -> Unit) {
    private val actives = mutableMapOf<KrxWire, LinkedHashMap<String, SubscriptionAckTopic>>() // per connection
    private val json = Json { ignoreUnknownKeys = true }
    val answered = linkedSetOf<String>()

    fun answer(request: TopicSubscribeRequest, refuseKrx: Boolean, target: KrxWire = world.wire) {
        val active = actives.getOrPut(target) { linkedMapOf() }
        val subscribe = request.type == "subscribe"
        pre("server model: supported operation ${request.type}", subscribe || request.type == "unsubscribe")
        pre("server model: modelled topics ${request.topics}", request.topics.all { it in S64HeadWorld.G || it == S64HeadWorld.K })
        pre("server model: one answer per request ${request.requestId}", answered.add(request.requestId))
        val refused = if (subscribe && refuseKrx && S64HeadWorld.K in request.topics) {
            listOf(SubscriptionRejection(S64HeadWorld.K, "krx_entitlement_required"))
        } else emptyList()
        val accepted = if (subscribe) {
            request.topics.filter { topic -> refused.none { it.topic == topic } }
                .map { SubscriptionAckTopic(it, "${request.requestId}-$it", 660L) }
        } else emptyList()
        val removed = if (subscribe) emptyList() else request.topics
        accepted.forEach { active[it.topic] = it }
        removed.forEach { active.remove(it) }
        val ack = SubscriptionAck(request.requestId, request.type, accepted, refused, removed, active.values.toList())
        target.deliver(Json.encodeToString(SubscriptionAck.serializer(), ack).replaceFirst("{", """{"type":"subscription_ack","""))
        world.drain()
        trace.event("SERVER_ACK", "wire=${world.wires.indexOf(target)} request=${request.requestId} op=${request.type} " +
            "accepted=${accepted.map { it.topic }} refused=${refused.map { it.topic }} removed=$removed active=${active.keys}")
        if (target === world.wire && !target.cancelled) accepted.filter { it.topic in S64HeadWorld.G }.forEach { snapshot(it.topic, target) }
    }

    /** Each validated general snapshot the server sent: the wire and the topic. */
    val delivered = mutableListOf<Pair<KrxWire, String>>()

    fun snapshot(topic: String, target: KrxWire = world.wire) {
        val before = observation.canonical.stateFor(topic).receiveGeneration
        target.deliver(k1jFrame(topic, 1555.0, world.clock.nowMillis()))
        world.drain()
        val after = observation.canonical.stateFor(topic).receiveGeneration
        pre("server model: validated general WS receive $topic ($before -> $after)", after > before)
        delivered += target to topic
        trace.event("SERVER_SNAPSHOT", "topic=$topic receive=$after")
    }

    /** The KRX snapshot over the socket, for rows whose server serves KRX; the row checks the session's receipt. */
    fun krxSnapshot(target: KrxWire = world.wire) {
        target.deliver(k1jFrame(S64HeadWorld.K, 1555.0, world.clock.nowMillis()))
        world.drain()
        trace.event("SERVER_SNAPSHOT", "topic=${S64HeadWorld.K}")
    }

    /** The requests a wire actually sent (pings and other control texts excluded), in send order. */
    fun requestsOn(wire: KrxWire): List<TopicSubscribeRequest> =
        wire.sent.toList().mapNotNull { runCatching { json.decodeFromString(TopicSubscribeRequest.serializer(), it) }.getOrNull() }

    /** Answers, on its own wire, each request an open wire sent and nobody answered, except those [hold] keeps for the row. */
    fun answerPending(refuseKrx: Boolean, hold: (TopicSubscribeRequest) -> Boolean = { false }): List<TopicSubscribeRequest> {
        val now = mutableListOf<TopicSubscribeRequest>()
        for (wire in world.wires.toList()) {
            if (wire.cancelled) continue
            for (request in requestsOn(wire)) {
                if (request.requestId in answered || hold(request)) continue
                answer(request, refuseKrx, wire)
                now += request
            }
        }
        return now
    }
}

/**
 * k1rb1/report.r1.md §4: the real store with one held load return. The delegate's load runs first and its record is
 * returned unchanged after the gate opens; every other member is delegated. [heldIn] names the PAC frames that waited.
 */
private class K1jLoadGate(private val delegate: AccessEpochStore) : AccessEpochStore by delegate {
    @Volatile var gate: CompletableDeferred<Unit>? = null
    @Volatile var heldIn: List<String> = emptyList()
    override suspend fun load(): AccessEpochRecord {
        // Read before the suspending load: a resumed continuation no longer has its caller's frames on the stack.
        val caller = Throwable().stackTrace.filter { it.className.startsWith(PremiumAccessCoordinator::class.java.name) }
            .map { "${it.className.substringAfterLast('.')}.${it.methodName}" }
        val record = delegate.load()
        gate?.let { held ->
            gate = null
            heldIn = caller
            held.await()
        }
        return record
    }
}

/** Actual production source/client/classifier; only credential and HTTP endpoint are fixtures. */
@OptIn(InternalCoroutinesApi::class)
private class K1jEntitlements(private val p: S64HeadParts, private val trace: K1jTrace,
    private val krxVisible: Boolean = true) : EntitlementsSource {
    var nextQuery: K1jQuery? = null
    var holdNextBody = false
    val calls = mutableListOf<K1jFetch>()
    /** review/report.r1.md F2: guards [calls] appends and the IO-thread concurrency sample. */
    private val callsLock = Any()
    override suspend fun currentIdentity() = p.source.currentIdentity() // Same H1' fault seam as existing rig.

    override suspend fun fetch(freshPremium: Boolean): EntitlementsResult {
        val call = K1jFetch(checkNotNull(nextQuery), freshPremium, holdNextBody,
            checkNotNull(currentCoroutineContext()[Job]))
        nextQuery = null
        holdNextBody = false
        synchronized(callsLock) { calls += call }
        trace.event("FETCH_START", call.describe())
        val cancellation = call.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) {
                call.cancellationRequested = true
                trace.event("CANCEL_REQUEST_OBSERVED", "cause=${cause.javaClass.simpleName} ${call.describe()}")
            }
        }
        try {
            // ResponseBody.bytes() is synchronous production code. IO prevents its deliberately
            // blocked read from occupying the virtual scheduler. No NonCancellable fetch is used.
            return withContext(Dispatchers.IO) {
                try {
                    val endpoint = object : AuthenticatedApiService by k1jBlockedApi() {
                        override suspend fun getEntitlements(auth: AuthRequestTag, freshPremium: Boolean?): retrofit2.Response<ResponseBody> {
                            trace.event("ENTITLEMENTS_ENDPOINT_SEND", "order=${call.query.order} freshPremium=$freshPremium status=200")
                            val body = k1jBody("""{"premium_active":true,"premium_pending":false,"krx_visible":$krxVisible}""",
                                onRead = {
                                    val bodyStartedNow = synchronized(callsLock) {
                                        if (call.bodyStarted) {
                                            false
                                        } else {
                                            call.bodyStarted = true
                                            maxBodiesTogether = maxOf(maxBodiesTogether, calls.count { it.bodyStarted && !it.bodyEnded })
                                            true
                                        }
                                    }
                                    if (bodyStartedNow) {
                                        trace.event("BODY_READ_START", call.describe())
                                    }
                                    try {
                                        if (!call.release.await(15, TimeUnit.SECONDS)) {
                                            throw AssertionError("precondition(K1j): body-release watchdog expired")
                                        }
                                    } catch (failure: Throwable) {
                                        call.bodyFault = failure
                                        throw failure
                                    }
                                }, onEnd = { reason ->
                                    synchronized(callsLock) { call.bodyEnded = true }
                                    trace.event("BODY_END", "reason=$reason ${call.describe()}")
                                })
                            return k1jResponse(200, body, "/api/entitlements")
                        }
                    }
                    val actual = AuthenticatedEntitlementsSource(k1jClient(endpoint, p.source, p.scope, p.orders))
                    actual.fetch(freshPremium).also { result ->
                        trace.event("SOURCE_RESULT", "order=${call.query.order} result=$result")
                    }
                } finally {
                    call.ioExited = true
                    trace.event("FETCH_IO_END", call.describe())
                }
            }
        } finally {
            call.fetchExited = true
            trace.event("FETCH_END", call.describe())
            cancellation.dispose()
        }
    }

    fun releaseAll() = calls.forEach { it.release.countDown() }
    /** The largest number of entitlements bodies open together, sampled at each body start under [callsLock]. */
    var maxBodiesTogether = 0
        get() = synchronized(callsLock) { field }
        private set
    fun checkBodyFaults() {
        calls.firstOrNull { it.bodyFault != null }?.let {
            throw AssertionError("precondition(K1j): body gate failed, order=${it.query.order}", it.bodyFault)
        }
    }
}

private data class K1jReply(val status: Int, val body: String)

private class K1jRestCall(val topic: String, val issue: S64Observation.RestIssue?, val job: Job) {
    val reply = CompletableDeferred<K1jReply>()
    var physical = false
    var returned = false
    var outcome: TopicSnapshotOutcome? = null
    /** Refusal handovers the issuer had already recorded when this REST service returned (order evidence for K1r-B). */
    @Volatile var reservedAtReturn: Int? = null
}

/** S64Http's endpoint pattern on the reused base world; original issue is read, never rebuilt. */
private class K1jRest(private val world: S64HeadWorld, private val observation: S64Observation,
    private val trace: K1jTrace) {
    val calls = mutableListOf<K1jRestCall>()
    var autoSuccess = false
    suspend fun bootstrap(owner: AuthIdentityFence, topic: String, admitted: () -> Boolean): TopicSnapshotOutcome {
        val call = K1jRestCall(topic, observation.issue(checkNotNull(currentCoroutineContext()[Job]), topic),
            checkNotNull(currentCoroutineContext()[Job])).also { calls += it }
        val sourceCall = world.calls.last()
        val endpoint = object : AuthenticatedApiService by k1jBlockedApi() {
            override suspend fun getTopicSnapshot(auth: AuthRequestTag, topic: String): retrofit2.Response<ResponseBody> {
                auth.use?.let { if (!it.context.admitted()) throw TopicUseWithheldException(it.context.observed()) }
                call.physical = true
                sourceCall.physicallySent = world.clock.nowMillis()
                trace.event("TOPIC_ENDPOINT_SEND", "topic=$topic issue=${call.issue}")
                if (topic != S64HeadWorld.K || autoSuccess) {
                    call.reply.complete(K1jReply(200, k1jFrame(topic, 1555.0, world.clock.nowMillis())))
                }
                val reply = call.reply.await() // Cancellation propagates normally.
                sourceCall.responseBody = reply.body
                sourceCall.exchanges += S64HeadWorld.Exchange("GET", "/api/v2/topics/snapshot",
                    mapOf("topic" to topic), world.clock.nowMillis(), reply.status)
                val body = k1jBody(reply.body,
                    onRead = { trace.event("TOPIC_BODY_READ", "topic=$topic status=${reply.status} issue=${call.issue}") },
                    onEnd = { reason ->
                        sourceCall.bodyEnd = world.clock.nowMillis()
                        trace.event("TOPIC_BODY_END", "topic=$topic reason=$reason issue=${call.issue}")
                    })
                return k1jResponse(reply.status, body, "/api/v2/topics/snapshot?topic=$topic")
            }
        }
        try {
            val client = k1jClient(endpoint, world.h.source, world.tokenScope, world.h.parts.orders)
            return TopicSnapshotBootstrapService(client, world.decoder).bootstrap(owner, topic, admitted).also {
                call.reservedAtReturn = world.issuer.reserved.size
                call.outcome = it
                call.returned = true
                trace.event("TOPIC_SERVICE_RESULT", "topic=$topic result=$it issue=${call.issue}")
            }
        } catch (cancelled: CancellationException) {
            trace.event("TOPIC_CANCELLED", "topic=$topic issue=${call.issue}")
            throw cancelled
        }
    }
    fun releaseAll() = calls.forEach { it.reply.complete(K1jReply(200, k1jFrame(it.topic, 1555.0, world.clock.nowMillis()))) }
}

private fun k1jBody(json: String, onRead: () -> Unit, onEnd: (String) -> Unit): ResponseBody {
    val raw = json.toResponseBody("application/json".toMediaType())
    val ended = AtomicBoolean()
    fun end(reason: String) { if (ended.compareAndSet(false, true)) onEnd(reason) }
    return object : ResponseBody() {
        override fun contentType() = raw.contentType()
        override fun contentLength() = raw.contentLength()
        private val observed by lazy {
            object : ForwardingSource(raw.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    onRead()
                    return super.read(sink, byteCount).also { if (it == -1L) end("EOF") }
                }
                override fun close() { try { super.close() } finally { end("CLOSE") } }
            }.buffer()
        }
        override fun source() = observed
    }
}

private fun k1jBlockedApi(): AuthenticatedApiService = Retrofit.Builder()
    .baseUrl("https://fixture.invalid/")
    .client(OkHttpClient.Builder().addInterceptor {
        throw AssertionError("precondition(K1j): unexpected delegated endpoint; external IO forbidden")
    }.build())
    .build().create(AuthenticatedApiService::class.java)

private fun k1jClient(endpoint: AuthenticatedApiService, source: KrxHarnessSource,
    scope: CoroutineScope, orders: AccessOrderSequence): AuthenticatedApiClient {
    val provider = AuthTokenProvider(object : AuthTokenSource {
        override fun currentIdentity() = source.identity?.let { AuthIdentity(it.ownerUid, it.authGeneration) }
        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean) = "k1j-local-fixture-credential"
    }, processScope = scope, orders = orders)
    return AuthenticatedApiClient(endpoint, AuthenticatedTransport(provider) { true }, Json { ignoreUnknownKeys = true })
}

private fun k1jResponse(status: Int, body: ResponseBody, path: String): retrofit2.Response<ResponseBody> {
    val raw = okhttp3.Response.Builder().request(Request.Builder().url("https://fixture.invalid$path").get().build())
        .protocol(Protocol.HTTP_1_1).code(status).message("K1j fixture").build()
    return if (status in 200..299) retrofit2.Response.success(body, raw) else retrofit2.Response.error(body, raw)
}
