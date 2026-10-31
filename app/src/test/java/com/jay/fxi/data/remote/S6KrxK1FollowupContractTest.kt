package com.jay.fxi.data.remote

import com.jay.fxi.S64HeadWorld
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.data.remote.S6Krx4Rig.Companion.K
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import org.junit.Test

/** Real Factory/TSC -> service/client/token/endpoint and TGD/PAC reservation channels. */
@OptIn(ExperimentalCoroutinesApi::class)
class S6KrxK1FollowupContractTest {
    @Test fun d14_wsAndRestUnknownTopicShareOriginalChannel() {
        val cases = listOf("OFF", "WS", "REST", "WS-REST", "REST-WS", "WS-cancels-REST", "duplicate-WS", "old-grant", "old-p", "old-epoch")
        for (sub in s64Cases("D14", cases)) s64Test("D14",sub,on = sub != "OFF",worker = sub.startsWith("old-")) { r ->
            r.world.bodyHold = setOf(K); r.http.ignoreKrxCancellation = true
            r.start(open = false); r.world.open()
            val before = r.world.h.source.calls.size; val queryStart = r.queries.size
            if (sub == "OFF") {
                val general = r.sends.last { it.second && it.first.type == "subscribe" }.first
                r.pre("real OFF GENERAL request has no K",general.topics.toSet() == S6Krx4Rig.G && K !in general.topics)
                r.world.ackOn(r.world.wire,general,extraRejected=mapOf(K to "krx_entitlement_required"))
                r.world.advance(3_000)
                r.eq("OFF K REST absent", 0, r.krxCalls.size)
                r.eq("OFF reservation absent",0,r.world.issuer.reserved.size)
                r.eq("OFF extra FE zero", before,r.world.h.source.calls.size)
            } else {
                r.world.until("original K endpoint body",3_000) { r.krxCalls.any { it.physicalAt != null } }
                val call = r.krxCalls.single(); val original = checkNotNull(call.issue).copy()
                val request = r.krxSends.last().first; val oldWire = r.world.wire
                r.pre("actual original request marker", original.stamp.permitSeq > 0 && original.owner.grant == r.observation.connection()?.fence?.grant)
                val answer = CompletableDeferred<EntitlementsOutcome>()
                fun ws(drainAfter: Boolean = true) {
                    r.world.ackOn(oldWire,request,mapOf(K to "krx_entitlement_required"),drainAfter=drainAfter)
                }
                fun rest(drainAfter: Boolean = true) {
                    r.http.reply(call,S64Http.Reply(404,"""{"error":"unknown_topic","supported_topics":[]}"""),drainAfter=drainAfter)
                    if (drainAfter) r.pre("REST raw endpoint actually returned",call.endpointReturned)
                }
                if (sub.startsWith("old-")) {
                    fun phase(point: String,input: S6Krx4Rig.ReapprovalInput? = null): String {
                        val now = r.world.clock.nowMillis()
                        val evidence = "$point call.at=${call.at} now=$now serviceBudget=${call.attemptBudget} " +
                            "remainingMillis=${call.at + call.attemptBudget.inWholeMilliseconds - now} " +
                            "serviceReturned=${call.serviceReturned} outcome=${call.outcome?.javaClass?.simpleName} " +
                            "call.cancelled=${call.cancelled} jobActive=${call.job.isActive} jobCancelled=${call.job.isCancelled} jobCompleted=${call.job.isCompleted} " +
                            "armedAt=${input?.armedAt} queries=${r.queries.drop(queryStart).map { Triple(it.order,it.at,it.origin) }}"
                        println("D14/$sub $evidence")
                        return evidence
                    }
                    phase("before-reapproval-preparation")
                    val reapproval = if (sub == "old-grant") r.armReapproval(holdResponse=true).also { r.awaitHeldReapprovalQuery(it) } else null
                    phase("after-reapproval-preparation",reapproval)
                    var hookEntered = false; var hookCompleted = false; var hookFailure: Throwable? = null
                    var feAfterPublication = -1; var reservationsBeforeAnswer = -1
                    var queriesAfterPublication = -1; var publishedEffects: S6Krx4Rig.PacEffects? = null
                    var actualOutcome: TopicSnapshotOutcome? = null
                    r.http.afterServiceReturned = { ended, originalOutcome ->
                        hookEntered = true
                        actualOutcome = originalOutcome
                        try {
                            r.http.recordSeam("after-service-before-publication",ended)
                            r.pre("service hook owns original call",ended === call)
                            r.pre("real endpoint returned before service hook",call.endpointReturned)
                            r.pre("real body ended before service hook",call.bodyEnd != null)
                            r.pre("real service returned before publication",call.serviceReturned)
                            r.pre("real original Unsupported produced; actual=${originalOutcome.javaClass.simpleName}",originalOutcome is TopicSnapshotOutcome.Unsupported && call.outcome === originalOutcome)
                            r.pre("world callback not returned before service hook",call.source.outcome == null)
                            // Each publication completes on the real PAC worker; no test-scheduler re-entry.
                            when (sub) {
                                "old-p" -> { r.publishOnWorker(false); r.publishOnWorker(true) }
                                "old-grant" -> runBlocking { withTimeout(5_000) {
                                        val input = checkNotNull(reapproval)
                                        checkNotNull(input.response).complete(EntitlementsOutcome.StableActive(true))
                                        val next = r.verifyReapproval(input,input.completed.await())
                                        r.pre("verified new grant current at publication baseline",r.snapshot.facts.token == next.grant)
                                } }
                                else -> r.publishOnWorker(false) // Capability epoch rotates; ON grant and permit remain original.
                            }
                            r.http.recordSeam("after-publication-before-world-return",call)
                            r.pre("publication remains before TSC callback return",call.source.outcome == null)
                            publishedEffects = r.readPacEffects(checkNotNull(r.snapshot.facts.token))
                            r.pre("publication baseline has no pending refusal",checkNotNull(publishedEffects).rejection.pendingOrders.isEmpty())
                            queriesAfterPublication = r.queries.size
                            feAfterPublication = r.world.h.source.calls.size; reservationsBeforeAnswer = r.world.issuer.reserved.size; hookCompleted = true
                        } catch (failed: Throwable) {
                            hookFailure = failed
                            println("D14/$sub hookEntered=$hookEntered hookCompleted=$hookCompleted outcome=${originalOutcome.javaClass.simpleName} hookFailure=$failed")
                            throw failed
                        }
                    }
                    val beforeAnswer = phase("before-old-body-answer",reapproval)
                    r.pre("original default service budget; $beforeAnswer",call.attemptBudget == TopicSnapshotBootstrapService.ATTEMPT_BUDGET)
                    r.pre("original attempt budget still remains; $beforeAnswer",r.world.clock.nowMillis() >= call.at && r.world.clock.nowMillis() - call.at < call.attemptBudget.inWholeMilliseconds)
                    r.pre("no service result before old body; $beforeAnswer",!call.serviceReturned && call.outcome == null)
                    r.pre("call not cancelled before old body; $beforeAnswer",!call.cancelled)
                    r.pre("original Job active before old body; $beforeAnswer",call.job.isActive && !call.job.isCancelled)
                    try { rest() } finally {
                        r.http.recordSeam("after-world-dispatch hookEntered=$hookEntered hookCompleted=$hookCompleted hookFailure=$hookFailure",call)
                    }
                    hookFailure?.let { throw it } // Preserve the original callback cause ahead of follow-up flag assertions.
                    r.pre("afterServiceReturned hook entered; actual=${call.outcome?.javaClass?.simpleName} (UNREACHED_SEAM otherwise)",hookEntered)
                    r.pre("afterServiceReturned hook completed; actual=${call.outcome?.javaClass?.simpleName} (UNREACHED_SEAM otherwise)",hookCompleted)
                    r.pre("endpoint returned component",call.endpointReturned)
                    r.pre("body end component",call.bodyEnd != null)
                    r.pre("service returned component",call.serviceReturned)
                    r.pre("original service outcome retained",call.outcome === actualOutcome && call.outcome is TopicSnapshotOutcome.Unsupported)
                    r.pre("actual world callback returned original outcome (UNREACHED_SEAM otherwise)",call.source.outcome === actualOutcome)
                    when (sub) {
                        "old-grant" -> r.pre("new RefusalReapproval grant with same USER epoch",r.snapshot.facts.token != original.owner.grant && r.snapshot.facts.recordFence?.userAccessEpoch == original.owner.userAccessEpoch)
                        "old-p" -> {
                            r.pre("compound old-p capability epoch changed",r.snapshot.facts.recordFence?.krxCapabilityEpoch != original.marker.krxCapabilityEpoch)
                            r.pre("compound old-p permit advanced grant retained",r.snapshot.krxPermitSeq > original.marker.permitSeq && r.snapshot.facts.token == original.owner.grant)
                        }
                        else -> {
                            r.pre("old-epoch actual capability epoch changed",r.snapshot.facts.recordFence?.krxCapabilityEpoch != original.marker.krxCapabilityEpoch)
                            r.pre("old-epoch original grant and permit retained",r.snapshot.facts.token == original.owner.grant && r.snapshot.krxPermitSeq == original.marker.permitSeq)
                            r.pre("old-epoch USER epoch and premium retained",r.snapshot.facts.userAllowed && r.snapshot.facts.recordFence?.userAccessEpoch == original.owner.userAccessEpoch)
                            r.pre("old-epoch effective capability withheld",!r.snapshot.facts.capabilityAllowed)
                        }
                    }
                    val after = r.world.issuer.reserved.drop(reservationsBeforeAnswer)
                    if (sub == "old-p") {
                        r.pre("completed old-p control handover really reached",after.size == 1)
                        r.eq("old-p handover retains original marker",original.marker,after.single().marker)
                        r.eq("old-p handover retains original grant",original.owner.grant,after.single().grant)
                    } else if (sub == "old-epoch") {
                        r.pre("completed old-epoch original handover reached (UNREACHED_SEAM otherwise)",after.size == 1)
                        r.eq("old-epoch handover retains original epoch marker",original.marker,after.single().marker)
                        r.eq("old-epoch handover retains original grant",original.owner.grant,after.single().grant)
                    } else {
                        r.eq("old-grant original control reservation exactly one",1,after.size)
                        val stale = after.single()
                        r.eq("old-grant typed reservation arity",3,stale.arity)
                        r.eq("old-grant retains original grant",original.owner.grant,stale.grant)
                        r.eq("old-grant retains original marker",original.marker,stale.marker)
                        r.eq("old-grant retains original reason",
                            listOf(TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED),stale.reasons)
                    }
                    // Driver only: original grant/marker transport assertions above retain first responsibility.
                    val stale = after.single()
                    val baseline = checkNotNull(publishedEffects)
                    r.pre("publication baseline captured before world return",baseline.token != null)
                    if (sub == "old-grant") r.pre("new grant baseline differs from original",baseline.token != original.owner.grant)
                    r.bridgeWorkerRuntime("$sub stale PAC completion order=${stale.reservation.order}",r.runtimeReadWatermark()) {
                        stale.reservation in r.world.issuer.processed &&
                            stale.reservation.order !in r.pac.rejectionView(original.owner.grant).pendingOrders
                    }
                    r.yes("$sub stale reservation never abandoned",stale.reservation !in r.world.issuer.abandoned)
                    val current = r.readPacEffects(checkNotNull(baseline.token))
                    r.eq("$sub stale preserves published token",baseline.token,current.token)
                    r.eq("$sub stale preserves USER/CAP fence",baseline.facts.recordFence,current.facts.recordFence)
                    r.eq("$sub stale preserves permission facts",baseline.facts,current.facts)
                    r.eq("$sub stale preserves permit",baseline.permit,current.permit)
                    r.eq("$sub stale preserves contest view",baseline.contest,current.contest)
                    if (sub == "old-grant") {
                        r.eq("old-grant stale preserves new-grant rejection view",baseline.rejection,current.rejection)
                    } else {
                        r.eq("$sub stale restores pending baseline",baseline.rejection.pendingOrders,current.rejection.pendingOrders)
                        r.eq("$sub stale preserves latest current",baseline.rejection.latestCurrent,current.rejection.latestCurrent)
                        r.eq("$sub stale reports original order",maxOf(baseline.rejection.latestReported ?: 0L,stale.reservation.order),current.rejection.latestReported)
                    }
                    r.eq("$sub stale preserves owed intent",baseline.owedIntent,current.owedIntent)
                    r.eq("$sub stale preserves held candidates",baseline.heldCandidates,current.heldCandidates)
                    r.eq("stale original creates no FE",feAfterPublication,r.world.h.source.calls.size)
                    r.eq("stale original creates no query",queriesAfterPublication,r.queries.size)
                } else {
                    r.world.h.source.gates += answer
                    when (sub) {
                        "WS" -> ws()
                        "REST" -> rest()
                        // Both real endpoint/wire arrivals are queued while the virtual dispatcher is parked.
                        // No TSC queue is replaced or read destructively; ordinary runtime scheduling owns handover.
                        "REST-WS" -> { rest(false); ws(false); r.world.drain() }
                        "WS-REST" -> { ws(false); rest(false); r.world.drain() }
                        "WS-cancels-REST" -> { ws(); r.pre("WS really cancelled old K Job",call.job.isCancelled); rest() }
                        else -> { ws(); ws() }
                    }
                    if (sub == "REST") r.pre("actual REST unknown_topic produced",call.serviceReturned && call.outcome is TopicSnapshotOutcome.Unsupported)
                    val reservations = r.world.issuer.reserved.toList()
                    r.yes("original channel receives refusal",reservations.isNotEmpty())
                    r.eq("CALLER FE zero",0,r.queries.drop(queryStart).count { it.origin == QueryOrigin.CALLER })
                    if (sub in listOf("WS-REST","REST-WS")) {
                        // A cancelled second route is a seam failure, never evidence of a joined query.
                        r.pre("both original handovers reached (UNREACHED_SEAM otherwise)",reservations.size == 2 && call.serviceReturned)
                    }
                    if (sub == "WS-cancels-REST") {
                        r.pre("late REST endpoint returned after actual cancellation",call.endpointReturned && call.cancelled)
                        r.eq("cancelled second route never claimed as join",1,reservations.size)
                        r.eq("cancelled service cannot hand over Unsupported",false,call.serviceReturned)
                    }
                    r.yes("typed original marker",reservations.all { it.arity == 3 && it.grant == original.owner.grant && it.marker == original.marker })
                    r.yes("two-argument fallback zero",r.world.issuer.twoArgument.isEmpty())
                    r.eq("one joined FE query",1,r.world.h.source.calls.size - before)
                    r.eq("dedicated query cap",1,r.queries.drop(queryStart).size)
                    if (sub == "duplicate-WS") r.eq("duplicate WS does not reserve twice",1,reservations.size)
                    answer.complete(EntitlementsOutcome.StableActive(true)); r.world.drain()
                }
            }
        }
    }

    @Test fun d15_topicUnavailableDoesNotBecomeEntitlementLoss() {
        val cases = listOf("200", "unavailable", "unknown", "403", "transient", "token-failure", "token-deadline", "token-body-absolute-deadline", "real-elapsed-guard")
        for (sub in s64Cases("D15",cases)) s64Test("D15",sub,worker=false) { r ->
            when (sub) {
                "unavailable" -> r.http.response = replyForK(404,"topic_unavailable")
                "unknown" -> r.http.response = replyForK(404,"unknown_topic")
                "403" -> r.http.response = replyForK(403,null)
                "transient" -> r.http.response = replyForK(503,"temporarily_unavailable")
                "token-failure" -> r.http.tokenFailure = AuthUnavailableException("s64 actual fetchToken failure")
                "token-deadline", "token-body-absolute-deadline" -> { r.world.tokenHold = setOf(K); r.world.bodyHold = setOf(K) }
                "real-elapsed-guard" -> {
                    // Separate from virtual timeout: occupy the dispatcher past an injected real elapsed budget.
                    r.http.budget = 20.milliseconds
                    r.http.endpointWork = { val mark = TimeSource.Monotonic.markNow(); while (mark.elapsedNow() < 25.milliseconds) Thread.yield() }
                }
            }
            r.start(); val before = r.world.h.source.calls.size
            r.world.until("actual service callback",3_000) { r.krxCalls.isNotEmpty() }
            val first = r.krxCalls.single()
            r.pre("real token provider entered",first.tokenStarted)
            if (sub in listOf("token-deadline","token-body-absolute-deadline")) {
                r.pre("actual token wait, no HTTP",!first.tokenReturned && first.sends.isEmpty())
                if (sub == "token-body-absolute-deadline") {
                    r.world.advance(6_000); first.source.token.complete(Unit); r.world.drain()
                    r.pre("token then actual endpoint wait",first.tokenReturned && first.physicalAt != null && !first.endpointReturned)
                    r.world.advance(3_999)
                } else r.world.advance(9_999)
                r.eq("before absolute deadline no result",null,first.outcome)
                r.world.advance(1)
                r.eq("absolute ten seconds includes token",TopicSnapshotOutcome.TimedOut,first.outcome)
            } else {
                r.world.drain()
                when (sub) {
                    "200" -> { r.eq("physical exchange once",1,first.sends.size); r.yes("real decoded K body",first.outcome is TopicSnapshotOutcome.Delivered && first.bodyEnd != null) }
                    "unavailable" -> { r.yes("unavailable terminal",first.outcome == TopicSnapshotOutcome.Degraded); r.world.advance(3_000); r.eq("unavailable no retry",1,r.krxCalls.size) }
                    "unknown" -> { r.yes("unknown supported list preserved",first.outcome is TopicSnapshotOutcome.Unsupported); r.pre("unknown real rejection channel",r.world.issuer.reserved.isNotEmpty()) }
                    "403" -> { r.yes("403 remains ordinary refusal",first.outcome is TopicSnapshotOutcome.Refused); r.world.advance(3_000); r.eq("403 terminal",1,r.krxCalls.size) }
                    "real-elapsed-guard" -> { r.pre("endpoint returned without virtual advance",first.endpointReturned && r.world.clock.nowMillis() == first.at); r.eq("monotonic second guard",TopicSnapshotOutcome.TimedOut,first.outcome) }
                    else -> {
                        r.world.advance(4_000)
                        r.eq("three attempts terminal",3,r.krxCalls.size)
                        r.yes("each actual service returned",r.krxCalls.all { it.serviceReturned })
                        if (sub == "token-failure") {
                            r.eq("failed fetchToken sends zero",0,r.krxCalls.sumOf { it.sends.size })
                            r.yes("token failure remains retryable IOException",r.krxCalls.all { it.outcome is TopicSnapshotOutcome.Unreachable })
                        } else r.eq("three physical exchanges",3,r.krxCalls.sumOf { it.sends.size })
                        val terminal = r.krxCalls.size; r.world.advance(3_000); r.eq("no fourth attempt",terminal,r.krxCalls.size)
                    }
                }
            }
            if (sub != "unknown") {
                r.eq("non-entitlement reservation zero",0,r.world.issuer.reserved.size)
                r.eq("non-entitlement FE zero",before,r.world.h.source.calls.size)
            }
        }
    }

    private fun replyForK(status: Int,error: String?): (String) -> S64Http.Reply = { topic ->
        if (topic == K) S64Http.Reply(status,if (error == null) "{}" else """{"error":"$error","supported_topics":[]}""")
        else S64Http.Reply(200,S64HeadWorld.frame(topic,1555.0))
    }
}
