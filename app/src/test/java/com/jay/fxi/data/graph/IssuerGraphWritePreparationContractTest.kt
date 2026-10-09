package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.AccessEpochRecord
import com.jay.fxi.data.entitlements.GraphDataMarking
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * S4 CUT-P2 (`cut_p2_agreed.r2.md` §1, graph adapter): the production [GraphV2PrepareWrite] over the issuer's marking. It asks
 * once with the capture's own fence, lifetime and KRX epoch and what was asked; a marked record prepares GENERAL, and KRX only
 * for a non-null KRX epoch equal to the record's on a record carrying the KRX marker; a refusal blocks both with its reason and
 * cause; cancellation propagates.
 */
class IssuerGraphWritePreparationContractTest {
    private data class Call(val fence: TopicSessionFence, val lifetime: TopicUseLifetime, val krxEpoch: String?, val krx: Boolean)

    private val fence = TopicSessionFence(AuthIdentityFence("u1", 1L), "user-epoch", TopicGrantToken(7L))
    private val lifetime = TopicUseLifetime(TopicGrantToken(7L), 3L)

    private fun capture(krxEpoch: String?) = GraphV2AccessCapture(fence, lifetime, krxEpoch)

    private fun record(krxEpoch: String?, krxMarked: Boolean) = AccessEpochRecord(
        ownerUid = "u1", userAccessEpoch = "user-epoch", krxCapabilityEpoch = krxEpoch,
        mayContainPremiumData = true, mayContainKrxData = krxMarked
    )

    private class Marker(private val answer: GraphDataMarking) {
        val calls = mutableListOf<Call>()
        val port = IssuerGraphWritePreparation { fence, lifetime, krxEpoch, krx ->
            calls += Call(fence, lifetime, krxEpoch, krx)
            answer
        }
    }

    @Test fun W01_asksOnceWithTheCapturesOwnFenceLifetimeAndKrxEpoch() = runTest {
        for (wantsKrx in listOf(false, true)) for (krxEpoch in listOf(null, "krx-1")) {
            val m = Marker(GraphDataMarking.Marked(record(krxEpoch, krxMarked = false)))
            m.port.prepare(capture(krxEpoch), wantsKrx)
            assertEquals("wantsKrx=$wantsKrx krxEpoch=$krxEpoch", listOf(Call(fence, lifetime, krxEpoch, wantsKrx)), m.calls)
            assertSame(fence, m.calls.single().fence)
            assertSame(lifetime, m.calls.single().lifetime)
        }
    }

    @Test fun W02_aMarkedRecordPreparesGeneral_andKrxOnlyForAMatchingMarkedEpoch() = runTest {
        // (capture epoch, record epoch, record KRX marker) -> KRX ready?
        val cases = listOf(
            Triple(null, null, true) to false,
            Triple(null, "krx-1", true) to false,
            Triple("krx-1", null, true) to false,
            Triple("krx-1", "krx-2", true) to false,
            Triple("krx-1", "krx-1", false) to false,
            Triple("krx-1", "krx-1", true) to true
        )
        for ((case, ready) in cases) {
            val (captureEpoch, recordEpoch, marked) = case
            val record = record(recordEpoch, marked)
            val asked = Marker(GraphDataMarking.Marked(record)).port.prepare(capture(captureEpoch), wantsKrx = true)
            val general = asked.general as GraphV2NamespacePreparation.Ready
            assertSame("$case: GENERAL is the marked record", record, general.record)
            if (ready) {
                assertSame("$case: KRX ready on the same record", record, (asked.krx as GraphV2NamespacePreparation.Ready).record)
            } else {
                assertEquals("$case: KRX blocked", GraphV2NamespacePreparation.Blocked("KRX not marked"), asked.krx)
            }
            val notAsked = Marker(GraphDataMarking.Marked(record)).port.prepare(capture(captureEpoch), wantsKrx = false)
            assertSame("$case: GENERAL without KRX", record, (notAsked.general as GraphV2NamespacePreparation.Ready).record)
            assertNull("$case: no KRX preparation when not wanted", notAsked.krx)
        }
    }

    @Test fun W03_aRefusalBlocksBothWithItsReasonAndCause() = runTest {
        val cause = IOException("store")
        for (refusal in listOf(GraphDataMarking.Refused("access"), GraphDataMarking.Refused("store", cause))) {
            val asked = Marker(refusal).port.prepare(capture("krx-1"), wantsKrx = true)
            val blocked = GraphV2NamespacePreparation.Blocked(refusal.reason, refusal.cause)
            assertEquals(blocked, asked.general)
            assertEquals(blocked, asked.krx)
            assertSame("the cause object is kept", refusal.cause, (asked.general as GraphV2NamespacePreparation.Blocked).cause)
            val notAsked = Marker(refusal).port.prepare(capture("krx-1"), wantsKrx = false)
            assertEquals(blocked, notAsked.general)
            assertNull(notAsked.krx)
        }
    }

    @Test fun W04_cancellationPropagates() = runTest {
        val cancelled = CancellationException("cancelled")
        val port = IssuerGraphWritePreparation { _, _, _, _ -> throw cancelled }
        try {
            port.prepare(capture("krx-1"), wantsKrx = true)
            fail("cancellation must propagate")
        } catch (thrown: CancellationException) {
            assertSame(cancelled, thrown)
        }
    }

    /** W05: dormant - nothing in production constructs the adapter or calls the issuer's marking. */
    @Test fun W05_dormant() {
        val main = File("src/main/java")
        val all = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(main).invariantSeparatorsPath to it.readText() }
        val adapter = "com/jay/fxi/data/graph/IssuerGraphWritePreparation.kt"
        val issuer = "com/jay/fxi/data/entitlements/PremiumAccessCoordinator.kt"
        assertTrue("premise: the scan sees both files", adapter in all && issuer in all && all.size > 100)
        assertTrue("premise: the issuer declares it", Regex("""fun markGraphData\(""").containsMatchIn(all.getValue(issuer)))
        val code = all.mapValues { (_, text) -> text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "") }
        assertEquals("only the adapter names its class, constructor references included", setOf(adapter),
            code.filter { (_, text) -> Regex("""\bIssuerGraphWritePreparation\b""").containsMatchIn(text) }.keys)
        assertEquals("only the issuer's declaration names the marking", mapOf(issuer to 1),
            code.mapValues { (_, text) -> Regex("""\bmarkGraphData\b""").findAll(text).count() }.filterValues { it > 0 })
    }
}
