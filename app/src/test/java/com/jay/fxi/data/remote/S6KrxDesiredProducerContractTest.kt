package com.jay.fxi.data.remote

import com.jay.fxi.S64HeadWorld
import com.jay.fxi.S6Krx4HeadProbe
import com.jay.fxi.BuildConfig
import com.jay.fxi.S6KrxReagreementContractTest
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.assertNotNull
import com.jay.fxi.data.auth.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.purge.*
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.domain.model.*
import com.jay.fxi.data.remote.S6Krx4Rig.Companion.G
import com.jay.fxi.data.remote.S6Krx4Rig.Companion.K
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.Request
import org.junit.Test

/** Inputs and expectations: contracts.r2 §2/§3/§4, amended by design.r4 §3.3. */
@OptIn(ExperimentalCoroutinesApi::class)
class S6KrxDesiredProducerContractTest {
    @Test fun d01_offNeverAddsKrx() {
        for (sub in s64Cases("D01",S6Krx4HeadProbe.D01_CASES)) s64Test("D01",sub,on=false,tab=FreeTab.valueOf(sub.substringBefore('/')),worker=false) { r ->
            S6Krx4HeadProbe.exerciseOffD01(r.world,sub) { point -> r.eq("$point desired GENERAL",G,r.desired) }
            val installed = checkNotNull(r.installation)
            r.eq("installer snapshot pulls zero",installed.readsBefore,installed.readsAfter)
            r.eq("installer jobs zero",installed.jobsBefore,installed.jobsAfter)
            r.eq("no K REST",0,r.krxCalls.size); r.eq("no K control",0,r.sends.count { K in it.first.topics })
            r.eq("OFF permit",0L,r.snapshot.krxPermitSeq)
            r.yes("OFF K memory and live tail zero",r.display.rates.quotes.values.none { it.source == "krx" } && r.observation.live.quotes.values.none { it.source == "krx" })
            val noNewWork = S6KrxReagreementContractTest.resource("source_inventory.json").jsonObject.getValue("head_coroutine_call_counts").jsonObject
            for ((path,expected) in noNewWork) {
                val text = KrxSessionSourceContract.strip(File(path).readText())
                for ((operator,count) in expected.jsonObject) {
                    val actual = Regex("""\b${Regex.escape(operator)}\s*(?:\(|\{)""").findAll(text).count()
                    r.yes("producer adds no coroutine/flow/timer $path/$operator",actual <= count.jsonPrimitive.int)
                }
            }
            val stream = S6KrxDesiredProducerContractTest::class.java.classLoader.getResourceAsStream("krx/s64a/off_head_trace.json")
            assertNotNull("precondition(D01/$sub): actual HEAD frozen OFF matrix",stream)
            val envelope = stream!!.bufferedReader().use { Json.parseToJsonElement(it.readText()) }.jsonObject
                .getValue("variants").jsonObject.getValue(BuildConfig.BUILD_TYPE).jsonObject
            r.pre("matching frozen schema",envelope.getValue("schema").jsonPrimitive.content == S6Krx4HeadProbe.SCHEMA)
            val expected = envelope.getValue("columns").jsonObject.getValue("D01/$sub").jsonObject
            val actual = S6Krx4HeadProbe.normalize(r.world.trace()).jsonObject
            r.pre("common HEAD observation shape",expected.keys == actual.keys)
            for (column in expected.keys) r.eq("HEAD $column",expected.getValue(column),actual.getValue(column))
        }
    }

    @Test fun d02_initialVisiblePlansKrxLast() {
        val cases = listOf("USD/FIRST", "TETHER/FIRST", "USD/REST", "TETHER/REST", "invalid-empty", "invalid-G+K", "invalid-G-DXY", "mutable-base-copy")
        for (sub in s64Cases("D02", cases)) s64Test("D02", sub, on = !sub.startsWith("invalid-"), tab = if (sub.startsWith("TETHER")) FreeTab.TETHER else FreeTab.USD,worker=false) { r ->
            when {
                sub.startsWith("invalid-") -> {
                    val base = when (sub) { "invalid-empty" -> emptySet(); "invalid-G+K" -> G + K; else -> G - "dxy:spot" }
                    val session = fixed(this, r.world, base).first
                    try {
                        val failure = runCatching { session.installKrxDesiredProducer(KrxContestSwitch.OFF) }.exceptionOrNull()
                        r.yes("non-G installation rejected", failure is IllegalArgumentException || failure is IllegalStateException)
                    } finally { session.stop() }
                }
                sub == "mutable-base-copy" -> {
                    r.world.approve(true); r.pre("EXPLICIT_ISSUER_INPUT base-copy fence",r.pac.topicGrantResult().fence != null); val base = G.toMutableSet(); val (session, requests) = fixed(this, r.world, base)
                    try {
                        session.installKrxDesiredProducer(KrxContestSwitch.OFF); base.clear(); base += K
                        session.start(); session.setFocus(r.world.identity, FreeTab.USD); session.setOnline(true)
                        session.setAccess(true, checkNotNull(r.pac.topicGrantResult().fence), TopicGrantOrigin.NewContext)
                        r.world.until("copied FIRST") { requests.isNotEmpty() }
                        r.eq("copied base excludes external mutation", G, session.display.value.topicState.topics.filterValues { it.desired }.keys)
                        r.yes("external mutation never sent", requests.none { K in it.topics })
                    } finally { session.stop() }
                }
                else -> {
                    r.world.floor = 10_000; r.start(open = false)
                    r.eq("original obligations exactly six",G+K,r.observation.plan.toSet())
                    r.eq("plan does not consume permit", 0L, r.observation.consumed)
                    r.eq("floor blocks all logical issues", 0, r.calls.size)
                    val p = r.snapshot.krxPermitSeq
                    if (sub.endsWith("FIRST")) {
                        r.world.open()
                        r.pre("actual FIRST accepted send", r.krxSends.size == 1)
                        r.eq("successful FIRST consumes once", p, r.observation.consumed)
                        r.world.ackAll()
                    } else r.world.tokenHold = setOf(K)
                    r.world.advance(12_000)
                    r.eq("actual callback order", expectedGeneral(r.world.tab) + K, r.calls.map { it.topic })
                    r.yes("K outside first batch", r.krxCalls.single().at > r.calls.first().at)
                    r.eq("REST issue consumes once", p, r.observation.consumed)
                    val times = r.calls.map { it.at }
                    r.yes("actual pacing after shown batch", times.drop(2).zip(times.drop(1)).all { (a,b) -> a - b >= 500 })
                    if (sub.endsWith("REST")) {
                        r.pre("actual token wait", r.krxCalls.single().tokenStarted && !r.krxCalls.single().tokenReturned)
                        r.eq("issue distinct from physical HTTP", 0, r.krxCalls.single().sends.size)
                        r.world.open(); r.world.ackAll(); r.eq("FIRST does not reconsume", p, r.observation.consumed)
                    }
                }
            }
        }
    }

    private fun fixed(test: TestScope, w: S64HeadWorld, desired: Set<String>): Pair<TopicSessionCoordinator, MutableList<TopicSubscribeRequest>> {
        val requests = mutableListOf<TopicSubscribeRequest>()
        val wire = KrxWire { _, _ -> }
        val session = TopicSessionCoordinator(scope = test.backgroundScope, clock = w.clock,
            connect = { TopicTransport(wire, w.decoder::decode).also { it.open(Request.Builder().url("wss://fixture.invalid/ws").build()); wire.open() } },
            credentials = object : TopicCommandCredentials {
                override suspend fun currentSnapshot() = AuthSnapshot(w.identity.uid, w.identity.authGeneration, "credential")
                override suspend fun refreshAfterUnauthorized(rejected: AuthSnapshot): AuthSnapshot? = null
                override suspend fun recordRejected(credential: AuthSnapshot) = Unit
                override suspend fun recordRejectionEvidence(credential: AuthSnapshot) = Unit
            }, orders = w.h.parts.orders, authority = w.uses,
            bootstrap = { _, _, _ -> TopicSnapshotOutcome.Degraded }, store = TopicSubscriptionStateStore(),
            encode = { requests += it; it.requestId }, newRequestId = { "fixed-${requests.size}" }, jitter = { 0.0 },
            liveIdentity = { w.identity }, desired = desired)
        return session to requests
    }

    @Test fun d03_flipUsesOnlyPermitLane() {
        for (sub in s64Cases("D03", listOf("same-grant/p1-p2"))) s64Test("D03", sub,worker=false) { r ->
            r.start(false); r.world.advance(3_000)
            r.eq("initial p0", 0L, r.snapshot.krxPermitSeq)
            val owner = r.display.owner; val epoch = r.observation.epoch; val wires = r.world.wires.size
            val general = r.sends.count { it.first.type == "subscribe" && it.first.topics.any(G::contains) }
            r.world.refresh(true); val p1 = r.snapshot.krxPermitSeq; r.world.ackAll(); r.world.advance(3_000)
            r.pre("actual p1", p1 > 0)
            r.eq("p1 K-only subscribe", listOf(listOf(K)), r.krxSends.map { it.first.topics })
            r.eq("p1 consumed", p1, r.observation.consumed)
            repeat(3) { r.world.refresh(true); r.world.ackAll(); r.eq("duplicate keeps p", p1, r.snapshot.krxPermitSeq) }
            r.eq("revision does not duplicate p1", 1, r.krxSends.size); r.eq("one ladder p1", 1, r.krxCalls.size)
            r.world.refresh(false); r.eq("false removes K", G, r.desired)
            r.world.refresh(true); val p2 = r.snapshot.krxPermitSeq; r.world.ackAll(); r.world.advance(3_000)
            r.yes("p2 strictly advances", p2 > p1)
            r.eq("p2 subscribe once", 2, r.krxSends.size); r.eq("one ladder per p", 2, r.krxCalls.size)
            r.eq("same grant", owner, r.display.owner); r.eq("same epoch", epoch, r.observation.epoch)
            r.eq("same socket", wires, r.world.wires.size)
            r.eq("GENERAL control preserved", general, r.sends.count { it.first.type == "subscribe" && it.first.topics.any(G::contains) })
            r.eq("GENERAL fanout once", G.associateWith { 1 }, r.calls.filter { it.topic in G }.groupingBy { it.topic }.eachCount())
        }
    }

    @Test fun d04_flipBeforeConnectionAndDuringPreparation() {
        val cases = listOf("socket", "token", "beforeEncode", "backoff").flatMap { w -> listOf(false,true).map { "$w/to=$it" } } + "initial-p0"
        for (sub in s64Cases("D04", cases)) s64Test("D04", sub, worker = sub.startsWith("beforeEncode")) { r ->
            if (sub == "initial-p0") {
                r.start(false); r.world.advance(3_000); val before = r.krxSends.size
                r.world.refresh(true); r.pre("real p1 published from p0",r.snapshot.krxPermitSeq > 0)
                r.world.advance(1_000)
                r.eq("automatic discovery without extra stimulus",before+1,r.krxSends.size)
                r.eq("discovery K-only scope",listOf(K),r.krxSends.last().first.topics)
            } else {
                val visible = sub.endsWith("true"); val window = sub.substringBefore('/')
                if (window == "beforeEncode") {
                    // Normal deliverer first creates p1 while the socket/credential preparation is held.
                    r.world.credentialGate = CompletableDeferred()
                    r.start(visible=true,open=false)
                    if (visible) {
                        val watermark = r.runtimeReadWatermark()
                        r.publishOnWorker(false)
                        val target = r.snapshot.revision
                        r.bridgeWorkerRuntime("runtime read false publication target=$target",watermark) {
                            synchronized(r.world.reads) { r.world.reads.drop(watermark).any { it.revision >= target && !it.facts.capabilityAllowed } }
                        }
                        r.eq("runtime false publication removes K after metadata read",G,r.desired)
                    }
                    var crossed = false
                    r.beforeTypedEncode = { attempt ->
                        val connection = checkNotNull(r.observation.connection())
                        r.pre("registered original command owns ticket", connection.controlOwner == attempt.commandId && connection.tickets[attempt.commandId] != null)
                        val revision = r.snapshot.revision
                        r.publishOnWorker(visible) // Watchdog only; never re-enter the test scheduler in this callback.
                        r.pre("actual publication revision crossed beforeEncode; ${r.credentialEvidence()}",r.snapshot.revision > revision)
                        r.pre("actual publication effective value beforeEncode; ${r.credentialEvidence()}",r.snapshot.facts.capabilityAllowed == visible)
                        crossed = true
                    }
                    val readsBeforeOpen = r.world.credentialReads
                    r.world.open(); r.awaitCredentialRead(readsBeforeOpen)
                    val evidence = r.credentialEvidence(); println("D04/$sub credential barrier: $evidence")
                    r.pre("credential gate not completed; $evidence",r.world.credentialGate?.isCompleted == false)
                    r.pre("successful subscribe sends zero; $evidence",r.sends.none { it.first.type == "subscribe" && it.second })
                    r.pre("failed subscribe sends zero; $evidence",r.sends.none { it.first.type == "subscribe" && !it.second })
                    r.pre("typed registration not entered; $evidence",r.registrations.isEmpty())
                    checkNotNull(r.world.credentialGate).complete(Unit); r.world.drain()
                    r.pre("typed beforeEncode callback executed", crossed)
                    r.yes("old attempt REPREPARE", r.boundaries.any { it.name == "checked" && it.verdict == KrxAttemptCheck.REPREPARE })
                    r.yes("old attempt discarded", r.boundaries.any { it.name == "discarded" })
                } else {
                    if (window == "token") r.world.credentialGate = CompletableDeferred()
                    r.start(!visible, open = false)
                    if (window == "backoff") { r.world.open(); r.world.ackAll(); r.world.wire.drop(); r.world.drain() }
                    if (window == "token") { r.world.open(); r.pre("credential suspended before registration", r.world.credentialReads > 0 && r.sends.isEmpty()) }
                    r.world.refresh(visible); r.world.credentialGate?.complete(Unit)
                    if (window == "backoff") r.world.until("real reconnect", 3_000) { r.world.wires.size == 2 }
                    r.world.open()
                }
                r.world.drain(); r.world.ackAll(); r.world.advance(3_000)
                r.eq("membership from latest publication", if (visible) G + K else G, r.desired)
                val sends = r.sends.zip(r.world.sendPublications).filter { it.first.second && it.first.first.type == "subscribe" }
                r.pre("real successful control", sends.isNotEmpty())
                for ((send,publication) in sends) {
                    val effective = publication.krxPermitSeq > 0 && publication.facts.capabilityAllowed && publication.facts.userAllowed && publication.facts.tokenStanding
                    if (send.first.topics.any(G::contains)) r.eq("same publication FIRST scope",effective,K in send.first.topics)
                    if (K in send.first.topics) r.yes("K send has visible original publication",effective)
                }
                r.eq("GENERAL obligation retained once", G.associateWith { 1 }, r.calls.filter { it.topic in G }.groupingBy { it.topic }.eachCount())
            }
        }
    }

    @Test fun d05_awaitingHoldAndPermitChange() {
        for (sub in s64Cases("D05", listOf("awaiting-stable-false", "hold-release", "ws-refusal-capability-end"))) s64Test("D05", sub,worker=false) { r ->
            r.start(leaseSeconds=181,fullActiveAck=true); r.acceptKrx(); val p = r.snapshot.krxPermitSeq
            r.world.wire.deliver(S64HeadWorld.frame("fx:usd-krw",1444.0)
                .replace("2026-09-26T10:20:00+09:00", "2026-09-26T10:20:02+09:00")); r.world.drain()
            val original = r.snapshot; val connection = checkNotNull(r.observation.connection())
            val capture = checkNotNull(connection.capture); val owner = r.display.owner
            val memory = r.display.rates.quotes.filterValues { it.source == "krx" }
            val tail = r.observation.live.quotes.filterValues { it.source == "krx" }
            val general = r.display.rates.quotes.filterValues { it.source != "krx" }
            r.pre("original accepted K memory and live tail",memory.isNotEmpty() && tail.isNotEmpty())
            r.pre("original GENERAL data",general.values.any { it.rate == 1444.0 })
            fun pure(phase: KrxContestPhase) {
                val s = r.snapshot
                r.pre("pure $phase phase (UNREACHED_SEAM otherwise)",r.pac.krxContestView?.state?.phase == phase)
                r.pre("pure raw true NOT_GRANTED absent",TopicAccessBlock.NOT_GRANTED !in s.facts.capabilityBlocks)
                r.pre("pure effective false contested",!s.facts.capabilityAllowed && TopicAccessBlock.KRX_CONTESTED in s.facts.capabilityBlocks)
                r.pre("pure live identity unchanged",r.world.identity == connection.fence.identity)
                r.pre("pure original standing grant",s.facts.tokenStanding && s.facts.token == original.facts.token)
                r.pre("pure original binding",s.facts.binding == original.facts.binding)
                r.pre("pure original record fence",s.facts.recordFence == original.facts.recordFence)
                r.pre("pure original USER epoch",s.facts.recordFence?.userAccessEpoch == original.facts.recordFence?.userAccessEpoch)
                r.pre("pure original CAP epoch",s.facts.recordFence?.krxCapabilityEpoch == original.facts.recordFence?.krxCapabilityEpoch)
                r.pre("pure USER end unchanged",s.lastUserEnd == original.lastUserEnd)
                r.pre("pure CAP end unchanged",s.lastCapabilityEnd == original.lastCapabilityEnd)
            }
            fun preserved(phase: String) {
                r.eq("$phase preserves withheld K memory",memory,r.display.rates.quotes.filterValues { it.source == "krx" })
                r.eq("$phase preserves withheld K live tail",tail,r.observation.live.quotes.filterValues { it.source == "krx" })
            }
            if (sub == "ws-refusal-capability-end") {
                val scope = KrxMemoryScope(connection.fence,capture.use,original.revision,
                    original.lastUserEnd?.sequence ?: 0,original.lastCapabilityEnd?.sequence ?: 0)
                val fetches = r.world.h.source.calls.size
                val gate = r.awaiting() // Readable refusal remains the explicit CAP-end input (also used by D09).
                val end = r.snapshot.lastCapabilityEnd
                val record = r.snapshot.facts.recordFence
                r.pre("readable refusal ends original CAP or confirms rotation",
                    end != null && end != original.lastCapabilityEnd && end.namespace == capture.use.krxCapabilityEpoch && end.ownerUid == connection.fence.identity.uid ||
                        record != null && !r.snapshot.facts.recordUnconfirmed && record.ownerUid == connection.fence.identity.uid &&
                        record.krxCapabilityEpoch != null && record.krxCapabilityEpoch != capture.use.krxCapabilityEpoch)
                val selections = krxMemorySelections(r.snapshot,setOf(scope),scope.userEndSequence,scope.capabilityEndSequence,false)
                r.eq("CAP end selects only original scope",setOf(scope),selections.filter { it.cause == KrxMemoryCause.CAPABILITY_END }.flatMap { it.scopes }.toSet())
                r.yes("original K memory selectively purged",r.display.rates.quotes.values.none { it.source == "krx" })
                r.yes("original K live tail selectively purged",r.observation.live.quotes.values.none { it.source == "krx" })
                r.yes("actual CAP cleanup observed",r.observation.cleanups.any { it.cause == KrxMemoryCause.CAPABILITY_END && it.result == KrxMemoryCleanupResult.SESSION_MEMORY_CLEANED })
                r.world.until("readable query waiting on response",5_000) { r.world.h.source.calls.size > fetches && r.world.h.source.gates.none { it === gate } }
                gate.complete(EntitlementsOutcome.StableActive(false)); r.world.drain()
                r.yes("fresh false does not exempt original CAP cleanup",r.display.rates.quotes.values.none { it.source == "krx" } && r.observation.live.quotes.values.none { it.source == "krx" })
                r.eq("GENERAL grant retained",owner,r.display.owner)
                r.eq("GENERAL use retained",connection.use,r.observation.connection()?.use)
                val generalAfter = r.display.rates.quotes.filterValues { it.source != "krx" }
                r.eq("GENERAL data retained",general,generalAfter.filterKeys { it in general.keys })
            } else if (sub == "awaiting-stable-false") {
                val input = r.pureAwaiting(); pure(KrxContestPhase.AWAITING)
                r.eq("AWAITING hides", G, r.desired); preserved("AWAITING")
                val sent = r.krxSends.size; val logical = r.krxCalls.size
                val physical = r.krxCalls.sumOf { it.sends.size }; val consumed = r.observation.consumed
                r.awaitPureQuery(input); pure(KrxContestPhase.AWAITING); preserved("AWAITING")
                r.eq("AWAITING no subscribe delta",sent,r.krxSends.size)
                r.eq("AWAITING no logical issue delta",logical,r.krxCalls.size)
                r.eq("AWAITING no physical REST delta",physical,r.krxCalls.sumOf { it.sends.size })
                r.eq("AWAITING no permit consumption delta",consumed,r.observation.consumed)
                input.response.complete(EntitlementsOutcome.StableActive(false)); r.world.drain()
                r.eq("stable false revokes contest permission",KrxContestPhase.NONE,r.pac.krxContestView?.state?.phase)
                r.eq("stable false removes", G, r.desired); r.eq("false sends no new K", sent, r.krxSends.size)
                r.yes("fresh false selects ended K memory",r.display.rates.quotes.values.none { it.source == "krx" } && r.observation.live.quotes.values.none { it.source == "krx" })
                r.world.refresh(true); r.ackAllFullActive()
            } else {
                val first = r.pureAwaiting(); pure(KrxContestPhase.AWAITING); preserved("AWAITING")
                r.awaitPureQuery(first); first.response.complete(EntitlementsOutcome.StableActive(true)); r.world.drain()
                r.pre("first fresh true really reopens",r.pac.krxContestView?.state?.phase == KrxContestPhase.OPEN && r.snapshot.krxPermitSeq > p && r.snapshot.facts.capabilityAllowed)
                val reopened = r.snapshot.krxPermitSeq
                val second = r.pureAwaiting(); pure(KrxContestPhase.AWAITING)
                r.awaitPureQuery(second)
                val sent = r.krxSends.size; val logical = r.krxCalls.size
                val physical = r.krxCalls.sumOf { it.sends.size }; val consumed = r.observation.consumed
                second.response.complete(EntitlementsOutcome.StableActive(true)); r.world.drain()
                pure(KrxContestPhase.HOLD)
                r.eq("HOLD hides contested K", G, r.desired)
                preserved("HOLD")
                r.eq("HOLD no subscribe delta",sent,r.krxSends.size)
                r.eq("HOLD no logical issue delta",logical,r.krxCalls.size)
                r.eq("HOLD no physical REST delta",physical,r.krxCalls.sumOf { it.sends.size })
                r.eq("HOLD no permit consumption delta",consumed,r.observation.consumed)
                val holdState = checkNotNull(r.pac.krxContestView?.state)
                val until = checkNotNull(holdState.holdUntilMillis)
                val holdPermit = r.snapshot.krxPermitSeq; val holdSeq = holdState.holdSeq; val holdExpired = holdState.holdExpired
                val holdNow = r.world.clock.nowMillis()
                r.pre("HOLD permit remains first reopened permit",holdPermit == reopened)
                r.pre("actual unexpired HOLD before third true",holdState.phase == KrxContestPhase.HOLD && !holdExpired && holdNow < until)
                r.pre("no endpoint response gate remains before third true",r.world.h.source.gates.isEmpty())
                val queriesBeforeTrue = r.queries.size; val endpointsBeforeTrue = r.world.h.source.calls.size
                val refusalsBeforeTrue = r.world.issuer.reserved.toList()
                r.pre("two actual refusals precede third true",refusalsBeforeTrue.size == 2)
                val freshTrue = backgroundScope.async { r.world.publish(true) }
                runBlocking { withTimeout(5_000) { // Real watchdog only; never advance the virtual HOLD timer.
                    while (!freshTrue.isCompleted) {
                        r.world.drain()
                        withContext(Dispatchers.IO) { yield() }
                    }
                    freshTrue.await() // Propagate the actual query's failure, including cancellation.
                    r.world.drain()
                } }
                r.pre("third true completes at unchanged virtual now",r.world.clock.nowMillis() == holdNow)
                r.pre("third true completes before original deadline",r.world.clock.nowMillis() < until)
                val thirdQueries = r.queries.drop(queriesBeforeTrue)
                r.pre("one actual third true query",thirdQueries.size == 1)
                r.pre("one actual third true endpoint",r.world.h.source.calls.size == endpointsBeforeTrue + 1)
                val third = thirdQueries.single()
                r.pre("third true is explicit FORCE_ENTITLEMENTS",third.origin == QueryOrigin.CALLER && third.intent == RefreshIntent.FORCE_ENTITLEMENTS)
                r.pre("third true query follows both refusal orders",refusalsBeforeTrue.all { third.order > it.reservation.order })
                r.pre("third true adds no refusal",refusalsBeforeTrue == r.world.issuer.reserved)
                r.eq("HOLD fresh true retains permit",holdPermit,r.snapshot.krxPermitSeq)
                r.eq("HOLD fresh true retains phase",KrxContestPhase.HOLD,r.pac.krxContestView?.state?.phase)
                r.eq("HOLD fresh true retains deadline",until,r.pac.krxContestView?.state?.holdUntilMillis)
                r.eq("HOLD fresh true retains hold sequence",holdSeq,r.pac.krxContestView?.state?.holdSeq)
                r.eq("HOLD fresh true remains unexpired",false,r.pac.krxContestView?.state?.holdExpired)
                pure(KrxContestPhase.HOLD)
                preserved("HOLD")
                r.eq("HOLD no subscribe delta",sent,r.krxSends.size)
                r.eq("HOLD no logical issue delta",logical,r.krxCalls.size)
                r.eq("HOLD no physical REST delta",physical,r.krxCalls.sumOf { it.sends.size })
                r.eq("HOLD no permit consumption delta",consumed,r.observation.consumed)
                r.world.advance(maxOf(0, until - r.world.clock.nowMillis() - 1))
                pure(KrxContestPhase.HOLD); preserved("HOLD")
                r.eq("HOLD no subscribe delta",sent,r.krxSends.size)
                r.eq("HOLD no logical issue delta",logical,r.krxCalls.size)
                r.eq("HOLD no physical REST delta",physical,r.krxCalls.sumOf { it.sends.size })
                r.eq("HOLD no permit consumption delta",consumed,r.observation.consumed)
                r.world.advance(1); r.pre("real hold release", r.pac.krxContestView?.state?.phase == KrxContestPhase.OPEN)
                r.yes("hold expiry advances actual permit",r.snapshot.krxPermitSeq > reopened); r.ackAllFullActive()
            }
            if (sub != "ws-refusal-capability-end") {
                r.yes("new permit after release", r.snapshot.krxPermitSeq > p); r.eq("release exposes K", G + K, r.desired)
                val opened = r.krxSends.size; val permit = r.snapshot.krxPermitSeq
                repeat(3) { r.world.refresh(true) }
                r.eq("revision-only no subscribe", opened, r.krxSends.size); r.eq("revision-only no permit", permit, r.snapshot.krxPermitSeq)
            }
        }
    }

    @Test fun d06_capabilityRefundKeepsOneLadder() {
        for (sub in s64Cases("D06", listOf("renewal", "recovery", "manual", "failed-send"))) s64Test("D06", sub,worker=false) { r ->
            r.world.floor = if (sub == "recovery") 30_000 else 10_000; r.world.bodyHold = setOf(K)
            if (sub == "recovery") r.world.credentialFailure = AuthUnavailableException("s64 actual credential failure")
            if (sub == "failed-send") r.world.failNextWire = 1
            r.start(open = false); r.world.open()
            if (sub == "failed-send") {
                r.pre("real failed FIRST", r.sends.any { !it.second && K in it.first.topics })
                r.eq("failed send does not consume", 0L, r.observation.consumed)
                r.world.until("same command successful retry",7_000) { r.krxSends.isNotEmpty() }; r.ackAllFullActive()
                r.eq("successful FIRST consumes", r.snapshot.krxPermitSeq, r.observation.consumed)
            } else if (sub == "recovery") {
                r.world.until("actual credential auth end", 12_000) { (r.observation.connection()?.authEndedCount ?: 0) > 0 }
                r.pre("three real token preparations", r.world.credentialReads >= 3)
                r.world.credentialFailure = null; r.world.recover(); r.world.drain(); r.ackAllFullActive()
            } else r.ackAllFullActive(rejected = if (sub == "manual") mapOf(K to "topics_disabled") else emptyMap(), seconds = if (sub == "renewal") 185 else 600)
            r.world.advance(maxOf(0,r.world.floor + 2_000 - r.world.clock.nowMillis()))
            r.pre("real initial ladder", r.krxCalls.size == 1)
            val p = r.snapshot.krxPermitSeq; val consumed = r.observation.consumed; val before = r.krxCalls.size
            val initial = r.observation.connection(); r.pre("real connection", initial != null)
            if (sub == "renewal") { r.world.until("actual renewal", 180_000) { r.boundaries.any { it.name == "sent" && it.attempt.source == KrxAttemptSource.RENEWAL } }; r.ackAllFullActive() }
            if (sub == "manual") { r.world.runtime.retryTopics(checkNotNull(r.display.owner), r.world.tab); r.world.drain(); r.ackAllFullActive() }
            val source = when (sub) { "renewal" -> KrxAttemptSource.RENEWAL; "recovery" -> KrxAttemptSource.RECOVERY; "manual" -> KrxAttemptSource.MANUAL; else -> KrxAttemptSource.FIRST }
            r.pre("actual requested control source",r.boundaries.any { it.name == "sent" && it.attempt.source == source })
            val actualRequest = r.boundaries.last { it.name == "sent" && it.attempt.source == source }.attempt
            val registration = r.registrations.singleOrNull { it.requestId == actualRequest.requestId }
            r.pre("original control owner ticket registered before encode",registration != null && registration.controlOwner == actualRequest.commandId && registration.ticket != null && registration.purpose != null)
            r.eq("actual ACK keeps canonical control",TopicControlState.ACKNOWLEDGED,r.observation.canonical.controlState)
            r.eq("actual ACK resolves authentication",TopicAuthResolution.RESOLVED,r.observation.canonical.authResolution)
            r.yes("non-permit paths never claim consumption", r.boundaries.filter { it.name == "sent" && it.attempt.source in setOf(KrxAttemptSource.RENEWAL,KrxAttemptSource.RECOVERY,KrxAttemptSource.MANUAL) }.all { it.attempt.consumePermit == null })
            r.eq("same permit", p, r.snapshot.krxPermitSeq); r.eq("no second consumption", consumed, r.observation.consumed)
            r.eq("no duplicate ladder", before, r.krxCalls.size)
            r.eq("GENERAL grant retained", initial?.fence, r.observation.connection()?.fence)
            r.yes("GENERAL leases retained", r.observation.connection()?.generalLeases?.keys?.containsAll(initial!!.generalLeases.keys) == true)
            if (sub == "manual") r.eq("full-active MANUAL restates original GENERAL IDs and expiry",initial?.generalLeases,r.observation.connection()?.generalLeases)
        }
    }

    @Test fun d07_wsAndRestOrderHaveExactPhysicalCounts() {
        val matrix = listOf("before-issue", "token", "body", "rest-first").flatMap { w -> listOf("accepted", "empty", "preACK", "invalid").map { "$w/$it" } }
        for (sub in s64Cases("D07", matrix + listOf("old-grant", "old-p", "next-p", "retry-WS", "explicit-after-receipt"))) s64Test("D07", sub,worker=false) { r ->
            r.world.floor = 10_000
            val window = sub.substringBefore('/'); val frame = sub.substringAfter('/', "accepted")
            if (window == "token") r.world.tokenHold = setOf(K)
            if (window == "body") r.world.bodyHold = setOf(K)
            if (sub == "retry-WS") r.http.response = { if (it == K) S64Http.Reply(503, "{}") else S64Http.Reply(200, S64HeadWorld.frame(it,1555.0)) }
            r.start(open = false)
            r.eq("actual original plan6",G+K,r.observation.plan.toSet())
            r.world.open(); if (frame != "preACK") r.world.ackAll()
            if (sub == "explicit-after-receipt") {
                r.acceptKrx()
                val receipt = r.receipts.single()
                val connection = checkNotNull(r.observation.connection())
                val capture = checkNotNull(connection.capture)
                val sent = r.boundaries.last { it.name == "sent" && it.attempt.requestId == capture.tuple.requestId }.attempt
                val owner = connection.fence; val displayOwner = r.display.owner
                val permit = r.snapshot.krxPermitSeq; val consumed = r.observation.consumed
                val originalUse = capture.use; val originalMarker = sent.marker
                r.pre("accepted receipt belongs to original grant permit use marker",receipt.epoch == r.observation.epoch &&
                    receipt.permit == permit && capture.stamp.permitSeq == permit && sent.krxUse == originalUse &&
                    originalMarker.permitSeq == permit && owner.grant == r.snapshot.facts.token)
                r.world.advance(13_000)
                r.eq("automatic after receipt logical zero",0,r.krxCalls.size)
                r.eq("automatic after receipt physical zero",0,r.krxCalls.sumOf { it.sends.size })
                r.yes("automatic receipt removes K queue",K !in r.observation.plan)
                r.eq("automatic receipt GENERAL topics five",G,r.calls.filter { it.physicalAt != null }.map { it.topic }.toSet())
                r.eq("automatic receipt GENERAL physical five",5,r.calls.filter { it.topic in G }.sumOf { it.sends.size })
                r.pre("explicit input after real floor and completed GENERAL pacing",r.world.clock.nowMillis() >= 13_000 &&
                    r.calls.filter { it.topic in G }.all { it.serviceReturned } && r.display.owner == displayOwner &&
                    r.observation.connection()?.fence == owner && r.snapshot.krxPermitSeq == permit)
                r.world.bodyHold = setOf(K)
                r.observation.session.requestBootstrap(K); r.world.drain()
                // First responsibility precedes any endpoint-reached prerequisite.
                r.eq("explicit after receipt logical issue one",1,r.krxCalls.size)
                val explicit = r.krxCalls.single(); val issue = checkNotNull(explicit.issue)
                r.eq("explicit after receipt original origin","EXPLICIT",issue.origin)
                r.eq("explicit after receipt original grant owner",owner,issue.owner)
                r.eq("explicit after receipt original epoch",receipt.epoch,issue.epoch)
                r.eq("explicit after receipt original permit",permit,issue.stamp.permitSeq)
                r.eq("explicit after receipt original use",originalUse,issue.use)
                r.eq("explicit after receipt original marker",originalMarker,issue.marker)
                r.world.until("explicit actual endpoint waiting for body",1_000) { explicit.physicalAt != null }
                r.pre("explicit actual token client endpoint and held body",explicit.tokenStarted && explicit.tokenReturned &&
                    explicit.sends.size == 1 && !explicit.endpointReturned && explicit.bodyEnd == null && explicit.job.isActive)
                r.eq("explicit after receipt physical one",1,r.krxCalls.sumOf { it.sends.size })
                r.observation.session.requestBootstrap(K); r.world.drain()
                r.eq("inflight explicit logical join one",1,r.krxCalls.size)
                r.eq("inflight explicit physical join one",1,r.krxCalls.sumOf { it.sends.size })
                val freshBody = S64HeadWorld.krxFrame(1888.0).replace("2026-09-26T10:20:00+09:00",
                    kotlinx.datetime.Instant.fromEpochMilliseconds(1_790_385_600_010L).toString())
                r.http.reply(explicit,S64Http.Reply(200,freshBody))
                r.world.until("explicit actual service and world return",1_000) { explicit.source.outcome != null }
                r.pre("explicit real delivered result returned unchanged",explicit.endpointReturned && explicit.bodyEnd != null &&
                    explicit.serviceReturned && explicit.outcome is TopicSnapshotOutcome.Delivered && explicit.source.outcome === explicit.outcome)
                r.world.advance(3_000)
                r.eq("settled explicit total K logical one",1,r.krxCalls.size)
                r.eq("settled explicit total K physical one",1,r.krxCalls.sumOf { it.sends.size })
                r.eq("settled explicit GENERAL physical five",5,r.calls.filter { it.topic in G }.sumOf { it.sends.size })
                r.eq("explicit does not consume permit again",consumed,r.observation.consumed)
                r.world.bodyHold = emptySet()
                r.world.refresh(false); r.world.refresh(true); r.world.drain()
                r.pre("later real permit with original grant",r.snapshot.krxPermitSeq > permit && r.snapshot.facts.token == owner.grant)
                r.world.ackAll()
                r.pre("later PERMIT actual send and ACK capture",r.boundaries.any {
                    it.name == "sent" && it.attempt.source == KrxAttemptSource.PERMIT && it.attempt.stamp.permitSeq == r.snapshot.krxPermitSeq
                } && r.observation.connection()?.capture?.stamp?.permitSeq == r.snapshot.krxPermitSeq)
                r.world.advance(13_000)
                r.eq("later permit independently issues one",2,r.krxCalls.size)
                val automatic = r.krxCalls.last()
                r.eq("later automatic retains PERMIT origin","PERMIT",automatic.issue?.origin)
                r.eq("later automatic owns new permit",r.snapshot.krxPermitSeq,automatic.issue?.stamp?.permitSeq)
                r.eq("later automatic retains original grant",owner.grant,automatic.issue?.owner?.grant)
                r.eq("later permit GENERAL never refanned",5,r.calls.filter { it.topic in G }.sumOf { it.sends.size })
            } else if (sub in listOf("old-grant", "old-p", "next-p")) {
                r.acceptKrx(); val receipt = r.receipts.single()
                r.eq("accepted receipt original epoch",r.observation.epoch,receipt.epoch)
                r.eq("accepted receipt original permit",r.snapshot.krxPermitSeq,receipt.permit)
                val oldEpoch = r.observation.epoch; val oldPermit = r.snapshot.krxPermitSeq; val oldWire = r.world.wire
                if (sub == "next-p") {
                    r.world.advance(13_000)
                    r.eq("WS terminal removes original unissued K",0,r.krxCalls.size)
                    r.yes("terminal queue item removed",K !in r.observation.plan)
                    r.eq("terminal pump completes GENERAL five",G,r.calls.filter { it.physicalAt != null }.map { it.topic }.toSet())
                    r.eq("terminal GENERAL physical count five",5,r.calls.filter { it.topic in G }.sumOf { it.sends.size })
                }
                if (sub == "old-grant") r.reapprove() else { r.world.refresh(false); r.world.refresh(true) }
                r.world.drain()
                if (sub == "old-grant") { oldWire.deliver(S64HeadWorld.krxFrame(1999.0)); r.world.drain() }
                r.world.ackAll()
                r.yes("old wire value never rebound",r.display.rates.quotes.values.none { it.rate == 1999.0 })
                r.pre("new lifetime or permit", r.observation.epoch != oldEpoch || r.snapshot.krxPermitSeq > oldPermit)
                r.eq("old receipt cannot erase new K obligation", true, K in r.observation.plan || r.krxCalls.isNotEmpty())
                r.world.advance(13_000)
                r.eq("new obligation pump alive", 1, r.krxCalls.size)
                r.pre("original new issue",r.krxCalls.single().issue?.stamp?.permitSeq == r.snapshot.krxPermitSeq)
                r.eq("new obligation original lane",if (sub == "old-grant") "INITIAL_PLAN" else "PERMIT",r.krxCalls.single().issue?.origin)
                r.eq("GENERAL never refanned out",5,r.calls.filter { it.topic in G }.sumOf { it.sends.size })
                r.yes("receipt not used as new result", r.display.topicState.stateFor(K).receiveGeneration <= receipt.after)
            } else if (sub == "retry-WS") {
                r.world.until("first failed service and its retry sleep",15_000) {
                    val first = r.krxCalls.singleOrNull()
                    first != null && first.serviceReturned && first.sends.size == 1 && r.world.sleeps.any { it.job === first.job && !it.returned && !it.cancelled }
                }
                r.pre("one failed physical exchange",r.krxCalls.size == 1 && r.krxCalls.single().serviceReturned)
                val job = r.krxCalls.single().job
                r.pre("real retry delay", r.world.sleeps.any { it.job === job && !it.returned && !it.cancelled })
                r.acceptKrx(); r.world.advance(3_000)
                r.eq("WS cancels retry", 1, r.krxCalls.size); r.yes("retry Job cancelled", job.isCancelled)
            } else {
                if (window != "before-issue") r.world.until("actual K $window boundary",15_000) {
                    val first = r.krxCalls.singleOrNull()
                    first != null && when (window) {
                        "token" -> first.tokenStarted && !first.tokenReturned && first.sends.isEmpty()
                        "body" -> first.physicalAt != null && !first.endpointReturned && first.bodyEnd == null
                        else -> first.serviceReturned && first.bodyEnd != null
                    }
                }
                if (window != "before-issue") r.pre("logical callback issued", r.krxCalls.size == 1)
                if (window == "body") r.pre("actual endpoint held", r.krxCalls.single().physicalAt != null && r.krxCalls.single().bodyEnd == null)
                if (window == "rest-first") r.pre("real REST returned first", r.krxCalls.single().serviceReturned && r.krxCalls.single().bodyEnd != null)
                val generationBeforeFrame = r.display.topicState.stateFor(K).receiveGeneration
                when (frame) {
                    "accepted" -> r.acceptKrx()
                    "empty" -> r.world.tick(empty = true)
                    "preACK" -> { r.world.tick(1777.0); r.eq("preACK not accepted", 0L, r.display.topicState.stateFor(K).receiveGeneration) }
                    else -> { r.world.wire.deliver("{invalid"); r.world.drain() }
                }
                if (frame != "accepted") {
                    r.eq("negative WS receive unchanged",generationBeforeFrame,r.display.topicState.stateFor(K).receiveGeneration)
                    r.world.ackAll()
                    if (window in listOf("token","body")) {
                        val outstanding = r.krxCalls.single()
                        r.pre("negative WS leaves original Job active",outstanding.job.isActive && !outstanding.cancelled)
                        if (window == "token") { outstanding.source.token.complete(Unit); r.world.drain() }
                        else r.http.reply(outstanding,S64Http.Reply(200,S64HeadWorld.krxFrame(1888.0)))
                    }
                }
                r.world.advance(if (window == "before-issue") 13_000 else 3_000)
                val accepted = frame == "accepted"
                r.eq("independent accepted WS receipts",if (accepted) 1 else 0,r.receipts.size)
                r.eq("K logical issued", if (accepted && window == "before-issue") 0 else 1, r.krxCalls.size)
                r.eq("K physically sent", if ((accepted && window == "token") || (accepted && window == "before-issue")) 0 else 1, r.krxCalls.sumOf { it.sends.size })
                if (accepted && window in listOf("token", "body")) r.yes("actual cancelled Job", r.krxCalls.single().job.isCancelled && r.krxCalls.single().cancelled)
                if (accepted && window == "before-issue") r.yes("unissued obligation removed", K !in r.observation.plan)
                r.eq("GENERAL physical five",G,r.calls.filter { it.physicalAt != null && it.topic in G }.map { it.topic }.toSet())
                r.eq("GENERAL physical count five",5,r.calls.filter { it.topic in G }.sumOf { it.sends.size })
                r.yes("bodyEnd independent of cancellation", r.krxCalls.filter { it.cancelled }.all { it.bodyEnd == null })
            }
        }
    }

    @Test fun d08_falseRetiresAndPurgesOnlyKrx() {
        for (sub in s64Cases("D08", listOf("open", "closed", "failed", "late-ACK", "new-capture"))) s64Test("D08", sub,worker=false) { r ->
            r.world.bodyHold = G
            r.start(open = false); r.world.open(); r.world.ackAll(seconds = if (sub == "new-capture") 185 else 600)
            r.acceptKrx(); r.world.wire.deliver(S64HeadWorld.frame("fx:usd-krw",1444.0)); r.world.drain()
            if (sub == "new-capture") {
                val first = checkNotNull(r.observation.connection()?.capture).tuple.copy()
                for (topic in G) { r.world.wire.deliver(S64HeadWorld.frame(topic,1444.0)); r.world.drain() }
                r.world.until("actual renewal replaces capture",8_000) { r.boundaries.any { it.name == "sent" && it.attempt.source == KrxAttemptSource.RENEWAL } }
                r.world.ackAll()
                val replacement = checkNotNull(r.observation.connection()?.capture).tuple
                r.pre("new capture ACK accepted",replacement != first)
                r.eq("old capture skips newer wire",KrxRetirementWire.SKIPPED_NEWER_CAPTURE,r.observation.retirements[first]?.wire)
                r.eq("old disposition superseded",KrxRetirementDisposition.SUPERSEDED_BY_NEW_CAPTURE,r.observation.retirements[first]?.disposition)
                r.acceptKrx(1778.0)
            }
            val before = checkNotNull(r.observation.connection()); val original = checkNotNull(before.capture).tuple.copy()
            r.pre("positive lease and K live tail", original.leaseId != null && r.observation.live.quotes.values.any { it.source == "krx" })
            r.pre("positive GENERAL data", r.display.rates.quotes.values.any { it.rate == 1444.0 })
            val oldWire = r.world.wire; val oldAck = r.krxSends.last().first; val owner = r.display.owner
            if (sub == "closed") { oldWire.drop(); r.world.drain() }
            if (sub == "failed") oldWire.failSends = 1
            val generalJobs = r.calls.filter { it.topic in G && !it.serviceReturned }.map { it.job }
            r.pre("GENERAL body jobs actually held",generalJobs.isNotEmpty() && generalJobs.none { it.isCancelled })
            val n = r.sends.size; r.world.refresh(false)
            val record = r.observation.retirements[original]
            r.yes("original tuple recorded", record != null)
            val expected = when (sub) { "closed" -> KrxRetirementWire.SKIPPED_CLOSED; "failed" -> KrxRetirementWire.FAILED_FALSE; else -> KrxRetirementWire.ACCEPTED_UNCONFIRMED }
            r.eq("wire retirement classification", expected, record?.wire)
            r.eq("local original cleared", KrxRetirementLocal.CLEARED_OLD, record?.local)
            r.eq("desired K removed",G,r.desired)
            r.eq("canonical K desired false",false,r.observation.canonical.stateFor(K).desired)
            r.yes("GENERAL in-flight jobs retained",generalJobs.none { it.isCancelled })
            r.yes("K memory selectively purged", r.display.rates.quotes.values.none { it.source == "krx" } && r.observation.live.quotes.values.none { it.source == "krx" })
            r.yes("GENERAL data preserved", r.display.rates.quotes.values.any { it.rate == 1444.0 })
            r.eq("GENERAL grant retained", owner, r.display.owner)
            if (sub != "closed") {
                val after = checkNotNull(r.observation.connection())
                r.eq("GENERAL leases preserved", before.generalLeases, after.generalLeases)
                r.eq("GENERAL lifetime preserved", before.use, after.use)
                r.eq("control ticket preserved", before.tickets, after.tickets)
            }
            r.yes("never GENERAL unsubscribe", r.sends.drop(n).filter { it.first.type == "unsubscribe" }.none { it.first.topics.any(G::contains) })
            val frames = r.sends.drop(n).count { it.first.type == "unsubscribe" && K in it.first.topics }
            r.eq("one K-only retirement frame",if (sub == "closed") 0 else 1,frames)
            val retired = r.observation.retirements; r.world.refresh(false)
            r.eq("repeat false retirement idempotent",retired,r.observation.retirements)
            r.eq("repeat false wire idempotent",frames,r.sends.drop(n).count { it.first.type == "unsubscribe" && K in it.first.topics })
            if (sub in listOf("late-ACK", "new-capture")) {
                r.world.refresh(true); r.world.ackAll(); val fresh = checkNotNull(r.observation.connection()?.capture).tuple
                r.pre("fresh original tuple differs", fresh != original)
                r.world.ackOn(oldWire,oldAck)
                r.eq("late ACK cannot overwrite new capture", fresh, r.observation.connection()?.capture?.tuple)
                r.eq("old tuple wire result immutable",record?.wire,r.observation.retirements[original]?.wire)
                r.eq("old tuple now superseded",KrxRetirementDisposition.SUPERSEDED_BY_NEW_CAPTURE,r.observation.retirements[original]?.disposition)
            }
        }
    }

    @Test fun d09_dynamicCancellationPreservesGeneral() {
        val cases = listOf("delay", "token", "body").flatMap { w -> listOf("false", "awaiting", "hold", "user").flatMap { loss -> (if (w == "body") listOf("200","unknown") else listOf("cancel")).map { "$w/$loss/$it" } } }
        for (sub in s64Cases("D09",cases + listOf("floor-after-cancel","pacing-after-cancel"))) s64Test("D09",sub,worker=false) { r ->
            if (sub.endsWith("after-cancel")) {
                r.world.bodyHold = setOf("fx:usd-krw","fx:eur-krw"); r.world.tokenHold = setOf(K)
                r.start(); r.world.until("K original token wait",3_000) { r.krxCalls.isNotEmpty() }
                val k = r.krxCalls.single(); val general = r.calls.first { it.topic == "fx:eur-krw" }
                r.pre("G body in flight",general.physicalAt != null && !general.serviceReturned)
                val at = r.world.clock.nowMillis(); val queued = setOf("dxy:spot","usdt:krw","fx:jpy-krw")
                queued.forEach { r.observation.session.requestBootstrap(it) }; r.world.drain()
                r.pre("public explicit G requests queued behind original pacing",r.observation.plan.containsAll(queued))
                var floor = at
                if (sub == "floor-after-cancel") {
                    val floorCall = r.calls.first { it.topic == "fx:usd-krw" }
                    r.http.reply(floorCall,S64Http.Reply(429,"{}","30")); floor = at + 30_000
                    r.pre("real HTTP Retry-After recorded",r.world.floor == floor)
                }
                r.world.refresh(false)
                r.yes("K cancellation retains pending GENERAL",k.job.isCancelled && !general.job.isCancelled)
                r.http.reply(general,S64Http.Reply(200,S64HeadWorld.frame(general.topic,1888.0)))
                r.yes("same G use completion positive",r.display.rates.quotes.values.any { it.rate == 1888.0 })
                r.world.bodyHold = emptySet()
                val earliest = maxOf(at + 500,floor)
                r.world.advance(maxOf(0,earliest - r.world.clock.nowMillis() - 1))
                r.eq("no early queued GENERAL issue",0,r.calls.count { it.topic in queued && it.at >= at })
                r.world.advance(1); r.world.until("queued GENERAL completed",2_000) { r.calls.count { it.topic in queued && it.at >= at } == 3 }
                val times = r.calls.filter { it.topic in queued && it.at >= at }.map { it.at }
                r.yes("shared floor and first gap retained",times.first() >= earliest)
                r.yes("GENERAL 500ms sequence retained",times.zipWithNext().all { (a,b) -> b-a >= 500 })
                r.eq("original K not retried",1,r.krxCalls.size)
                return@s64Test
            }
            val (window,loss,late) = sub.split('/')
            r.world.bodyHold = G + if (window == "body") setOf(K) else emptySet()
            if (window == "token") r.world.tokenHold = setOf(K)
            if (window == "delay") r.http.response = { if (it == K) S64Http.Reply(503,"{}") else S64Http.Reply(200,S64HeadWorld.frame(it,1555.0)) }
            r.http.ignoreKrxCancellation = window == "body"
            r.start(leaseSeconds=181); r.world.until("K callback",3_000) { r.krxCalls.isNotEmpty() }
            val old = r.krxCalls.single(); val original = checkNotNull(old.issue).copy(); val general = r.calls.first { it.topic in G }
            r.pre("GENERAL endpoint in flight", general.physicalAt != null && !general.serviceReturned)
            if (window == "delay") r.pre("actual retry wait", r.world.sleeps.any { it.job === old.job && !it.returned && !it.cancelled })
            r.pre("real GENERAL graph control observer active",r.world.graph.filterIsInstance<TopicGraphInput.Continuity>().any { it.authority.owner == original.owner })
            val graphBeforeCancel = r.world.graph.size
            r.world.floor = r.world.clock.nowMillis() + 30_000; val floor = r.world.floor
            var gate: CompletableDeferred<EntitlementsOutcome>? = null
            when (loss) { "false" -> r.world.refresh(false); "awaiting" -> gate = r.awaiting(); "hold" -> r.hold(); else -> r.withdraw() }
            val reservationsAfterLoss = r.world.issuer.reserved.size
            val feAfterLoss = r.world.h.source.calls.size
            val queriesAfterLoss = r.queries.size
            val oldReservationsAfterLoss = r.world.issuer.reserved.count { it.grant == original.owner.grant && it.marker == original.marker }
            r.yes("K Job cancelled", old.job.isCancelled)
            if (window != "delay") r.yes("CancellationException observed", old.cancelled && old.cancellation != null)
            if (window == "delay") r.yes("retry delay cancelled", r.world.sleeps.any { it.job === old.job && it.cancelled })
            r.yes("GENERAL Job never cancelled", !general.job.isCancelled && !general.cancelled)
            val count = r.krxCalls.size
            if (window == "body") {
                r.http.reply(old, if (late == "200") S64Http.Reply(200,S64HeadWorld.krxFrame(1999.0)) else S64Http.Reply(404,"""{"error":"unknown_topic","supported_topics":[]}"""))
                r.pre("late raw endpoint actually returned", old.endpointReturned)
            } else if (window == "token") { old.source.token.complete(Unit); r.world.drain(); r.eq("cancelled token no late endpoint", 0, old.sends.size) }
            if (window != "delay") {
                r.yes("cancellation unwrapped across service and caller",old.cancellation != null && old.cancellation === old.source.cancellation)
                r.yes("cancellation never converted to Unreachable",old.outcome !is TopicSnapshotOutcome.Unreachable && old.source.outcome !is TopicSnapshotOutcome.Unreachable)
                r.yes("TSC cancellation never published as Unreachable outcome",r.world.graph.drop(graphBeforeCancel).filterIsInstance<TopicGraphInput.Continuity>().none {
                    K in it.topics && (it.payload as? TopicGraphEventPayload.RestUndelivered)?.resultKind == TopicGraphRestResultKind.UNREACHABLE
                })
            }
            r.pre("original observation retained",original == old.issue)
            r.yes("old issued use remains unadmitted",!old.source.admitted())
            r.eq("original GENERAL use admission",loss != "user",general.source.admitted())
            r.http.completeGeneral(); r.pre("original GENERAL real service completed",general.serviceReturned && general.outcome is TopicSnapshotOutcome.Delivered)
            r.world.advance(1_000)
            r.eq("cancelled ladder never retries", count, r.krxCalls.size)
            r.yes("late K value not applied", r.display.rates.quotes.values.none { it.rate == 1999.0 })
            r.eq("late result reservation delta zero",reservationsAfterLoss,r.world.issuer.reserved.size)
            r.eq("late original marker handover delta zero",oldReservationsAfterLoss,r.world.issuer.reserved.count { it.grant == original.owner.grant && it.marker == original.marker })
            r.eq("late result FE delta zero",feAfterLoss,r.world.h.source.calls.size)
            r.eq("late result query delta zero",queriesAfterLoss,r.queries.size)
            r.eq("shared floor retained", floor, r.world.floor)
            if (loss == "user") r.yes("old GENERAL use not applied", r.display.rates.quotes.values.none { it.rate == 1888.0 })
            else r.yes("same GENERAL use completion applies", r.display.rates.quotes.values.any { it.rate == 1888.0 })
            gate?.complete(EntitlementsOutcome.StableActive(false)); r.world.drain()
        }
    }

    @Test fun d10_grantReplacementAndWithdrawalKeepOriginals() {
        for (sub in s64Cases("D10", listOf("domain-input-refusal-reapproval", "explicit-withdrawal"))) s64Test("D10", sub,worker=false) { r ->
            r.world.bodyHold = G + K; r.http.ignoreKrxCancellation = true; r.start()
            r.world.until("old K physical body",3_000) { r.krxCalls.any { it.physicalAt != null } }
            val old = r.krxCalls.single(); val marker = checkNotNull(old.issue).copy(); val oldG = r.calls.last { it.topic in G }; val owner = r.display.owner; val wiresBefore = r.world.wires.size
            val tuple = checkNotNull(r.observation.connection()?.capture).tuple.copy()
            if (sub.startsWith("domain")) r.reapprove() else { r.withdraw(); r.world.approve(true); r.world.drain() }
            r.pre("new grant actually published", owner != r.display.owner)
            val retained = r.observation.restIssues.singleOrNull { it.id == marker.id }
            r.pre("original source issue survives noncooperative body (UNREACHED_SEAM otherwise)",retained != null)
            r.eq("source original marker never rebound",marker,retained)
            r.yes("original retirement tuple retained",r.observation.retirements[tuple] != null)
            r.world.until("new grant socket created",3_000) { r.world.wires.size > wiresBefore }
            r.world.open(); r.world.ackAll()
            r.world.until("new grant K physical body",3_000) { r.krxCalls.any { it.issue?.epoch != marker.epoch && it.physicalAt != null } }
            val current = r.krxCalls.single { it.issue?.epoch != marker.epoch }
            val currentIssue = checkNotNull(current.issue)
            r.pre("new grant body really held",current.job.isActive && !current.serviceReturned)
            r.yes("old K actual cancellation", old.job.isCancelled && old.cancelled)
            r.yes("old GENERAL job not cancelled", !oldG.job.isCancelled)
            r.http.reply(old,S64Http.Reply(200,S64HeadWorld.krxFrame(1999.0))); r.pre("old endpoint returned",old.endpointReturned)
            r.pre("original observation retained",marker == old.issue)
            r.yes("old issued use remains unadmitted",!old.source.admitted())
            r.http.reply(oldG,S64Http.Reply(200,S64HeadWorld.frame(oldG.topic,1888.0)))
            r.pre("old GENERAL response really completed",oldG.serviceReturned && oldG.outcome is TopicSnapshotOutcome.Delivered)
            r.yes("old answers cannot apply",r.display.rates.quotes.values.none { it.rate == 1999.0 || it.rate == 1888.0 })
            r.eq("old completion never settles current source issue",currentIssue,r.observation.restIssues.singleOrNull { it.id == currentIssue.id })
            r.yes("old completion keeps current bootstrap registration",r.observation.bootstrapFor(current.job)?.let { it.epoch == currentIssue.epoch && it.topic == K && it.active } == true)
            r.yes("new body still pending after old completion",current.job.isActive && !current.serviceReturned)
            r.world.advance(500)
            r.http.completeGeneral(2444.0); r.acceptKrx(2777.0)
            r.yes("new GENERAL lifetime positive", r.display.rates.quotes.values.any { it.rate == 2444.0 })
            r.yes("new K lifetime positive", r.display.rates.quotes.values.any { it.rate == 2777.0 })
        }
    }

    @Test fun d11_uidChangeNeverRebindsOldKrx() {
        for (sub in s64Cases("D11", listOf("late-WS", "late-REST", "late-ACK", "late-rejection", "USER-CAP"))) s64Test("D11",sub,worker = sub == "USER-CAP") { r ->
            r.world.bodyHold = setOf(K); r.http.ignoreKrxCancellation = true; r.start()
            r.world.until("A K physical body",3_000) { r.krxCalls.any { it.physicalAt != null } }
            val old = r.krxCalls.single(); val oldWire = r.world.wire; val req = r.krxSends.last().first; val original = checkNotNull(old.issue).copy()
            val cleanupBefore = r.observation.cleanups.size
            if (sub == "USER-CAP") {
                r.acceptKrx()
                runBlocking { withTimeout(5_000) { r.world.publish(false) } }
                r.pre("real capability end published with runtime delivery held",r.snapshot.lastCapabilityEnd != null && !r.snapshot.facts.capabilityAllowed)
            }
            val publication = r.snapshot.revision
            if (sub == "late-REST") {
                r.http.onKrxBodyEnd = { ended ->
                    r.pre("post-transport old body EOF before consumer return",ended === old && old.endpointReturned && !old.serviceReturned && old.bodyEnd != null)
                    r.world.changeLiveUidOnly("user-b")
                    r.pre("live B at EOF before PAC publication",r.snapshot.revision == publication && r.world.identity == AuthIdentityFence("user-b",2))
                }
            } else r.world.changeLiveUidOnly("user-b")
            if (sub != "late-REST") r.pre("live B before PAC publication and delivery",r.world.identity == AuthIdentityFence("user-b",2) && r.snapshot.revision == publication)
            if (sub == "USER-CAP") {
                runBlocking { withTimeout(5_000) {
                    r.pac.onIdentityChanged(r.world.identity).applied()
                    r.world.h.source.next = { EntitlementsOutcome.StableActive(true) }
                    r.pac.refresh(RefreshIntent.FORCE_PREMIUM); r.pac.topicGrantResult()
                } }
                r.pre("both original owner ends published before queued runtime delivery",r.snapshot.lastUserEnd?.ownerUid == original.owner.identity.uid && r.snapshot.lastCapabilityEnd?.ownerUid == original.owner.identity.uid)
            }
            when (sub) {
                "late-WS" -> { oldWire.deliver(S64HeadWorld.krxFrame(1999.0)); r.world.drain() }
                "late-REST" -> {
                    r.http.reply(old,S64Http.Reply(200,S64HeadWorld.krxFrame(1999.0)))
                    r.pre("old decoded service result reaches actual consumer",old.serviceReturned && old.outcome is TopicSnapshotOutcome.Delivered && old.source.outcome is TopicSnapshotOutcome.Delivered)
                    r.pre("post-transport B window remains before PAC publication",r.world.identity == AuthIdentityFence("user-b",2) && r.snapshot.revision == publication)
                }
                "late-ACK", "late-rejection" -> r.world.ackOn(oldWire,req,if (sub == "late-rejection") mapOf(K to "krx_entitlement_required") else emptyMap())
                else -> { oldWire.deliver(S64HeadWorld.krxFrame(1999.0)); r.world.drain() }
            }
            r.yes("A value never applied under live B", r.display.rates.quotes.values.none { it.rate == 1999.0 })
            r.pre("A original observation retained",original == old.issue)
            r.yes("A original use blocked by live B",!old.source.admitted())
            if (sub == "USER-CAP") {
                r.prepareUserCapB(original,oldWire)
                r.acceptKrx(2777.0)
            } else {
                r.world.deliverIdentity(); r.world.approve(true); r.world.drain()
                r.world.until("B socket",1_000) { r.world.wires.size > 1 }; r.world.open(); r.world.ackAll(); r.acceptKrx(2777.0)
            }
            r.pre("B own display owner",r.display.owner?.identity == r.world.identity)
            r.yes("B actual new K receipt",r.display.rates.quotes.values.any { it.source == "krx" && it.rate == 2777.0 })
            if (sub == "USER-CAP") {
                r.eq("USER cleanup wins simultaneous CAP",com.jay.fxi.data.entitlements.purge.KrxMemoryCause.USER_END,
                    r.observation.cleanups.drop(cleanupBefore).firstOrNull { it.result == com.jay.fxi.data.entitlements.purge.KrxMemoryCleanupResult.SESSION_MEMORY_CLEANED }?.cause)
                r.yes("USER ends original GENERAL lifetime",!r.world.uses.admits(original.use.lifetime))
            }
        }
    }

    private fun expectedGeneral(tab: FreeTab) = when (tab) {
        FreeTab.USD -> listOf("fx:usd-krw", "dxy:spot", "usdt:krw", "fx:jpy-krw", "fx:eur-krw")
        else -> listOf("usdt:krw", "dxy:spot", "fx:usd-krw", "fx:jpy-krw", "fx:eur-krw")
    }
}
