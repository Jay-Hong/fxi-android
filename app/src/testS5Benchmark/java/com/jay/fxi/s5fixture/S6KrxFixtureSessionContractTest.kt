package com.jay.fxi.s5fixture

import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.KrxContestPhase
import com.jay.fxi.data.entitlements.PremiumAccessReducer
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.data.remote.DecodedTopicFrame
import com.jay.fxi.data.remote.TopicCatalogue
import com.jay.fxi.data.remote.TopicSnapshotOutcome
import com.jay.fxi.data.remote.dto.toQuote
import com.jay.fxi.domain.model.FreeTab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** L1 draft: production PAC/SUA/RT/producer, real fixture HTTP and unmodified fixture WS frames. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class S6KrxFixtureSessionContractTest(private val subId: String, private val tab: FreeTab, private val input: String) {
    @Test fun fixtureSessionContract() = runTest {
        val b = S6FixtureSessionBridge(this, subId, tab, on = input != "OFF")
        var planned: Set<String> = emptySet()
        var failure: Throwable? = null
        try {
            b.eq("literal-G", G, TopicCatalogue.DESIRED)
            val w = b.world
            w.approve(input != "FALSE")
            when (input) {
                "AWAITING" -> {
                    // Explicit issuer input before RT starts: no fabricated socket refusal or
                    // synthesized ACK. The endpoint's readable identity fails once, as in H1'.
                    w.h.source.gates += CompletableDeferred<EntitlementsOutcome>()
                    enterAwaiting(b)
                }
                "HOLD" -> {
                    enterAwaiting(b)
                    b.advance(PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS)
                    b.awaitIo("first scheduled true reopens") { w.pac.krxContestView?.state?.phase == KrxContestPhase.OPEN }
                    enterAwaiting(b)
                    b.advance(PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS)
                    b.awaitIo("second scheduled true enters HOLD") { w.pac.krxContestView?.state?.phase == KrxContestPhase.HOLD }
                }
            }
            // The real retry floor exposes the original plan without issuing an HTTP call.
            w.floor = w.clock.nowMillis() + 1_000
            if (input == "W-TOKEN") w.tokenHold = setOf(K)
            w.start()
            val epoch = b.observation.epoch
            val grant = w.snapshot.facts.token
            planned = b.observation.plan.toSet()
            val effective = input in setOf("W-BEFORE", "W-TOKEN", "R", "KEYS")
            b.eq("planned", if (effective) G + K else G, planned)
            b.eq("issued-before-floor", 0, b.calls.size)
            b.eq("physical-before-floor", 0, b.restArrivals().size)
            b.eq("desired", if (effective) G + K else G, b.observation.desired)
            if (effective) b.premise("real ON permit and effective use", w.snapshot.krxPermitSeq > 0 && w.snapshot.facts.capabilityAllowed)
            if (input == "OFF") b.premise("OFF raw true input retained", w.h.source.next() == EntitlementsOutcome.StableActive(true))
            if (input == "FALSE") b.premise("real E=false", !w.snapshot.facts.capabilityAllowed)
            val phase = w.pac.krxContestView?.state?.phase
            if (input == "AWAITING" || input == "HOLD") {
                b.premise("actual $input phase", phase?.name == input)
                b.premise("contested effective use withheld", !w.snapshot.facts.capabilityAllowed)
            }

            // Five means these literal five actual HTTP arrivals, never just five callback entries.
            repeat(6) { if (b.calls.count { it.topic in G } < 5) b.advance(500) }
            b.premise("literal five G issued", b.calls.filter { it.topic in G }.map { it.topic }.toSet() == G)
            b.premise("literal five G physically arrived", b.restArrivals().filter { line -> G.any { line == S6FixtureSessionBridge.restLine(it) } }.size == 5)
            assertGeneralHttp(b)
            b.assertPhysicalPairing()

            when (input) {
                "KEYS" -> {
                    b.eq("requestable-set", G + K, planned)
                    b.eq("fixture-keys=requestable", planned, S5FixtureRoutes.TOPIC_SNAPSHOTS.keys)
                    return@runTest
                }
                "W-BEFORE" -> {
                    b.eq("K-issued-before-WS", 0, b.calls.count { it.topic == K })
                    b.eq("physical-before-WS", 5, b.restArrivals().size)
                }
                "W-TOKEN" -> {
                    b.advance(500)
                    val k = b.calls.filter { it.topic == K }
                    b.eq("K-issued-token-wait", 1, k.size)
                    b.premise("real token wait on original issue", k.single().tokenStarted && !k.single().tokenReturned && k.single().issue?.epoch == epoch)
                    b.eq("K-physical-token-wait", 0, k.count { it.serverReached })
                    b.eq("physical-token-wait", 5, b.restArrivals().size)
                }
                "R" -> {
                    b.advance(500)
                    val k = b.calls.filter { it.topic == K }
                    b.premise("R original issue reached actual fixture", k.size == 1 && k.single().issue?.epoch == epoch && k.single().serverReached)
                    b.eq("K-issued-R", 1, k.size)
                    b.eq("K-physical-R", 1, k.count { it.serverReached })
                    b.eq("physical-R", 6, b.restArrivals().size)
                    b.assertPhysicalPairing()
                    // HEAD must fail HERE on its real 404, after all positive arrival premises.
                    b.eq("REST200", 200, k.single().status)
                    b.yes("decoder-Delivered", k.single().outcome is TopicSnapshotOutcome.Delivered)
                    val decoded = (k.single().outcome as TopicSnapshotOutcome.Delivered).frame
                    b.yes("decoder-K", decoded is DecodedTopicFrame.Krx)
                    b.yes("fixture-K-key", K in S5FixtureRoutes.TOPIC_SNAPSHOTS)
                    b.eq("REST-bytes=fixture", Json.parseToJsonElement(S5FixtureRoutes.TOPIC_SNAPSHOTS.getValue(K)),
                        Json.parseToJsonElement(checkNotNull(k.single().source.responseBody)))
                }
                else -> {
                    b.eq("K-issued-withheld", 0, b.calls.count { it.topic == K })
                    b.eq("K-physical-withheld", 0, b.restArrivals().count { it == S6FixtureSessionBridge.restLine(K) })
                }
            }

            b.openActualSocket()
            // A real server ACK is an independent arrival witness for the rig's outgoing frame.
            b.premise("one real FIRST enqueue", w.sends.size == 1 && w.sends.single().second)
            val sent = w.sends.single().first
            b.eq("subscribe-topics", if (effective) G + K else G, sent.topics.toSet())
            val ack = w.decoder.decode(b.nextFrame("server-ACK"))
            b.yes("ACK-decoded", ack is DecodedTopicFrame.Acknowledgement)
            val accepted = (ack as DecodedTopicFrame.Acknowledgement).value
            b.eq("ACK-request-id", sent.requestId, accepted.requestId)
            b.eq("ACK-operation", "subscribe", accepted.operation)
            b.eq("ACK-accepted", sent.topics.toSet(), accepted.acceptedTopics.map { it.topic }.toSet())
            b.eq("ACK-rejected", emptyList<Any>(), accepted.rejectedTopics)
            b.eq("ACK-active", sent.topics.toSet(), accepted.activeSubscriptions.map { it.topic }.toSet())
            val original = b.observation.connection()?.capture
            val beforeReceive = w.runtime.display.value.topicState.stateFor(K).receiveGeneration

            // The five existing G snapshots are positive control frames from this very socket.
            val generalFrames = linkedSetOf<String>()
            for (topic in sent.topics.filter { it in G }) {
                val frame = b.nextFrame("G-snapshot-$topic")
                val json = Json.parseToJsonElement(frame).jsonObject
                b.eq("G-snapshot-type-$topic", "snapshot", json["type"]?.jsonPrimitive?.content)
                b.eq("G-snapshot-topic-$topic", topic, json["topic"]?.jsonPrimitive?.content)
                generalFrames += topic
            }
            b.eq("literal-G-WS-positive", G, generalFrames)
            if (effective) {
                // HEAD has sent the ACK and five G frames, but no K frame. Missing K is a
                // contract RED, not an original-capture precondition failure hidden before it.
                val frame = b.nextFrame("K-snapshot-after-ACK") { text ->
                    val json = Json.parseToJsonElement(text).jsonObject
                    b.eq("K-snapshot-type", "snapshot", json["type"]?.jsonPrimitive?.content)
                    b.eq("K-snapshot-topic", K, json["topic"]?.jsonPrimitive?.content)
                    b.yes("original-capture-installed-by-ACK", original != null)
                    b.eq("original-capture-request", sent.requestId, original?.tuple?.requestId)
                    b.eq("original-capture-grant", epoch, original?.epoch)
                    b.yes("original-capture-lease", original?.tuple?.leaseId != null)
                    b.eq("no-receipt-before-K-frame", false, b.wsSatisfiedByOriginalCapture())
                }
                b.eq("snapshot-count-K", 1, b.frames.count { Json.parseToJsonElement(it).jsonObject["topic"]?.jsonPrimitive?.content == K })
                b.eq("original-capture-retained", original, b.observation.connection()?.capture)
                b.yes("original-capture-receipt", b.wsSatisfiedByOriginalCapture())
                b.eq("receipt-generation", beforeReceive + 1, w.runtime.display.value.topicState.stateFor(K).receiveGeneration)
                val decoded = w.decoder.decode(frame)
                b.yes("WS-decoder-K", decoded is DecodedTopicFrame.Krx)
                val entries = (decoded as DecodedTopicFrame.Krx).value.data.allEntries.filter { it.source == "krx" && it.asset == "usd-krw-futures" }
                b.eq("WS-quote-count", 1, entries.size)
                val quote = entries.single().toQuote()
                b.yes("WS-quote-valid", quote != null)
                val adopted = checkNotNull(quote)
                b.eq("quote-adopted", adopted, w.runtime.display.value.rates.quotes[adopted.key])
                b.eq("live-quote-adopted", adopted, b.observation.live.quotes[adopted.key])
                if (input == "R") b.eq("REST-bytes=WS", Json.parseToJsonElement(checkNotNull(b.calls.single { it.topic == K }.source.responseBody)), Json.parseToJsonElement(frame))
                b.settleRest()
            } else {
                b.eq("K-subscribe-withheld", 0, w.krxSends.size)
                b.eq("K-capture-withheld", null, original)
                b.eq("wsSatisfied-withheld", false, b.wsSatisfiedByOriginalCapture())
                b.eq("K-quote-withheld", emptyList<Any>(), w.runtime.display.value.rates.quotes.values.filter { it.source == "krx" })
            }
            b.assertNoExtraFrames()
            b.advance(500)
            if (input == "W-TOKEN") {
                val k = b.calls.single { it.topic == K }
                b.yes("K-token-wait-cancelled", k.cancelled && k.source.cancelled && k.job.isCancelled)
                // A detached token producer completing later must still cause no HTTP send.
                k.source.token.complete(Unit)
                w.drain()
            }
            b.advance(500)
            b.eq("grant-unchanged", grant, w.snapshot.facts.token)
            b.eq("epoch-unchanged", epoch, b.observation.epoch)
            b.eq("issued-final", if (input == "R" || input == "W-TOKEN") 6 else 5, b.calls.size)
            b.eq("K-issued-final", if (input == "R" || input == "W-TOKEN") 1 else 0, b.calls.count { it.topic == K })
            b.eq("physical-final", if (input == "R") 6 else 5, b.restArrivals().size)
            b.eq("K-physical-final", if (input == "R") 1 else 0, b.calls.count { it.topic == K && it.serverReached })
            b.eq("cancelled-final", if (input == "W-TOKEN") 1 else 0, b.calls.count { it.cancelled })
            b.eq("bodyEnd-final", if (input == "R") 6 else 5, w.calls.count { it.bodyEnd != null })
            b.eq("wsSatisfied-final", effective, b.wsSatisfiedByOriginalCapture())
            val acknowledgedIds = b.frames.map { w.decoder.decode(it) }
                .filterIsInstance<DecodedTopicFrame.Acknowledgement>()
                .filter { it.value.operation == "subscribe" }.map { it.value.requestId }
            b.eq("subscribe-enqueue-once", 1, w.sends.count { it.second && it.first.type == "subscribe" })
            b.eq("subscribe-server-arrival-pairs-enqueue", w.sends.filter { it.second && it.first.type == "subscribe" }.map { it.first.requestId }, acknowledgedIds)
            b.eq("K-subscribe-server-arrival", if (effective) 1 else 0,
                w.sends.count { it.first.requestId in acknowledgedIds && K in it.first.topics })
            if (input == "R" || input == "W-TOKEN") b.yes("current-grant-initial-slot-issued", b.initialSlotIssuedForCurrentGrant())
            b.assertKTerminal()
            b.assertPhysicalPairing()
            b.eq("fixture-unexpected", 0, b.server.unexpectedCount)
            if (input == "AWAITING" || input == "HOLD") b.eq("phase-remains-withheld", phase, w.pac.krxContestView?.state?.phase)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            println("D16/S-$subId planned=$planned issued=${b.calls.map { it.topic }} " +
                "physicallySent=${b.restArrivals()} cancelled=${b.calls.filter { it.cancelled }.map { it.topic }} " +
                "wsSatisfied=${b.wsSatisfiedByOriginalCapture()} bodyEnd=${b.world.calls.filter { it.bodyEnd != null }.map { it.topic }}")
            try { b.assertOnPublicationHeld() } catch (check: Throwable) {
                if (failure != null) failure.addSuppressed(check) else throw check
            } finally { b.close() }
        }
    }

    private suspend fun enterAwaiting(b: S6FixtureSessionBridge) {
        val w = b.world
        val grant = w.pac.topicGrantResult().fence
        b.premise("real issuer grant before contest", grant != null)
        w.h.source.identityNulls = 1
        w.pac.onTopicRejected(checkNotNull(grant).grant, listOf(TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED))
        w.drain()
        b.premise("issuer consumed identity-read fault", w.h.source.identityNulls == 0)
        b.premise("real AWAITING publication", w.pac.krxContestView?.state?.phase == KrxContestPhase.AWAITING)
    }

    private fun assertGeneralHttp(b: S6FixtureSessionBridge) {
        val calls = b.calls.filter { it.topic in G }
        b.eq("G-issued-exact", G, calls.map { it.topic }.toSet())
        b.eq("G-issued-once", 5, calls.size)
        for (call in calls) {
            b.eq("G-status-${call.topic}", if (call.topic == "usdt:krw") 200 else 404, call.status)
            if (call.topic == "usdt:krw") b.yes("G-REST200-decoder-Delivered", call.outcome is TopicSnapshotOutcome.Delivered)
            b.eq("G-cancelled-${call.topic}", false, call.cancelled)
        }
    }

    companion object {
        private const val K = "krx:usd-krw-futures"
        // Independent literals: a mutated candidate DESIRED is never the test's oracle.
        private val G = setOf("usdt:krw", "dxy:spot", "fx:usd-krw", "fx:jpy-krw", "fx:eur-krw")
        @JvmStatic @Parameterized.Parameters(name = "S-{0}")
        fun inputs(): List<Array<Any>> = listOf(FreeTab.TETHER, FreeTab.USD).flatMap { tab ->
            listOf("OFF", "W-BEFORE", "W-TOKEN", "R", "FALSE", "AWAITING", "HOLD", "KEYS").map { input ->
                arrayOf<Any>("${tab.name}-$input", tab, input)
            }
        }
    }
}
