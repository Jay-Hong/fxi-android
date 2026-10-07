package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicGraphAuthority
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphEventKind
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicGraphOffer
import com.jay.fxi.data.remote.TopicGraphPath
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAttribution
import com.jay.fxi.data.remote.TopicUseLifetime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 F2b-2b contract r1 (JVM): the graph recorder's topic sink - the bounded queue between the S3 coordinator and
 * the recorder, its own refusal ledger, and close. A fake consumer stands in for the recorder so the calls themselves are seen;
 * what the recorder does with them is locked by GraphRecorderTest and GraphRecorderContinuityTest.
 *
 * Oracles: ANDROID_V2_PLAN.md :1169-1170 (the wired sink returns at once and never throws; enqueue failure, closing and
 * saturation are never silently ignored, and a hand-over loss reaches the recorder as lost continuity needing recovery).
 * Design: R4c/S4 f2b_design_codex.r1-r3 cut down by f2b_review_claude.r1/r2 and a four-lens verification
 * (f2b_design_verify_workflow.result.json); sink details agreed in f2b2b_design_codex.r1.
 *
 * Rules:
 *  - `tryOffer` never calls the consumer, reads no supplier and never throws. A whole batch is accepted or refused: an
 *    Observations batch costs its candidates, a continuity fact one unit, against 512 units; a batch that does not fit is
 *    FULL, nothing queued is evicted. An exception of its own is FAILED. After close, or once its worker has ended (also when
 *    the supplied scope is cancelled before the worker ever ran), it is CLOSED before the input is read.
 *  - A FULL or FAILED input goes to the sink's own ledger under its original owner's scope (uid, epoch), each topic keeping
 *    the highest original lifetime invalidation count; an input without an owner, an epoch or a lifetime is not ledgered.
 *    The sink filters nothing by the recorder's current scope or floor; the recorder decides.
 *  - One worker on the supplied serial executor hands queued inputs to the consumer as the same objects, in order, one at a
 *    time, never nested. A dequeued input's units are free again before its call. After each input, and also when nothing is
 *    queued, the ledger is swapped out and handed over as one `loseTopics` per scope; losses made meanwhile wait for the next
 *    turn. The executor must dispatch the worker, never run it inside an offer.
 *  - `close` stops acceptance, drops the queue and the ledger, ends its own worker and closes the consumer once; the supplied
 *    scope stays alive. A consumer call that throws ends the pipeline the same way: nothing retried, nothing more handed over,
 *    and the exception does not escape.
 *
 * Not here: the recorder's own rules (above), the S3 coordinator's traces with a real sink (TopicSessionCoordinatorTest
 * GOBS-20). The implementation thread reads but does not edit this file.
 *
 * r2 adds the S4 F2e purge row K07 (design R4c/S4 f2e_design_codex.r1, cut down by f2e_review_claude.r1 and agreed in
 * f2e_design_codex.r2; oracle ANDROID_V2_PLAN.md :1025):
 *  - `purge(selects)` removes the queued inputs and ledger scopes whose original owner scope (uid, epoch) is selected, derived
 *    as the ledger derives it. An input without an owner or an epoch has no scope and is never selected. Removed inputs give
 *    back their cached units; the rest keep their order. It answers whether anything was removed, judged by count, not by
 *    units. It neither wakes nor closes anything, and a closed sink answers false. It is called on the sink's executor,
 *    outside any consumer call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphRecorderTopicSinkTest {

    private val at = Instant.parse("2026-10-07T20:01:00+09:00")
    private val identity = AuthIdentityFence("u1", 1L)
    private val grant = TopicGrantToken(7L)
    private val fenceN = TopicSessionFence(identity, "e1", grant)
    private val fenceN2 = TopicSessionFence(identity, "e2", grant)
    private val noEpoch = TopicSessionFence(identity, null, grant)
    private val fenceN3 = TopicSessionFence(identity, "e3", grant)
    private val fenceU2 = TopicSessionFence(AuthIdentityFence("u2", 1L), "e1", grant)
    private val scopeN = GraphDataScope("u1", "e1")
    private val scopeN2 = GraphDataScope("u1", "e2")
    private val l3 = TopicUseLifetime(grant, 3L)
    private val l4 = TopicUseLifetime(grant, 4L)

    private fun obs(
        units: Int,
        topic: String = "fx:usd-krw",
        owner: TopicSessionFence = fenceN,
        lifetime: TopicUseLifetime = l3
    ) = TopicGraphInput.Observations(
        1L, topic, TopicGraphPath.WS, TopicUseAttribution(Any(), owner, 1L, lifetime), 1L,
        List(units) { TopicGraphCandidate.Quote("kb", "usd-krw", 1340.0 + it / 100.0, at, null) }
    )

    /** A batch whose size cannot be read: a failure inside the sink itself. */
    private fun broken(topic: String = "fx:usd-krw", lifetime: TopicUseLifetime = l3) = TopicGraphInput.Observations(
        1L, topic, TopicGraphPath.WS, TopicUseAttribution(Any(), fenceN, 1L, lifetime), 1L,
        object : AbstractList<TopicGraphCandidate>() {
            override val size: Int get() = throw IllegalStateException("size")
            override fun get(index: Int): TopicGraphCandidate = throw IllegalStateException("get")
        }
    )

    private fun fact(
        topics: Set<String> = setOf("fx:usd-krw"),
        owner: TopicSessionFence? = fenceN,
        lifetime: TopicUseLifetime? = l3
    ) = TopicGraphInput.Continuity(
        1L, TopicGraphEventKind.DELIVERY_RESUMED, null, topics, setOf(TopicGraphPath.WS),
        TopicGraphAuthority(Any(), owner, 1L, lifetime), 1L, at.toEpochMilliseconds()
    )

    /** Inputs are compared by identity: neither input class is a data class. */
    private sealed interface Call {
        data class Obs(val input: TopicGraphInput.Observations) : Call
        data class Cont(val input: TopicGraphInput.Continuity) : Call
        data class Lose(val scope: GraphDataScope, val topics: Map<String, Long>) : Call
        object Close : Call
    }

    private class Consumer : GraphTopicInputConsumer {
        val calls = mutableListOf<Call>()
        var maxDepth = 0
        private var depth = 0
        var onCall: (Call) -> Unit = {}

        private fun enter(call: Call) {
            depth++
            maxDepth = maxOf(maxDepth, depth)
            calls += call
            try {
                onCall(call)
            } finally {
                depth--
            }
        }

        override fun observe(input: TopicGraphInput.Observations) = enter(Call.Obs(input))
        override fun observe(input: TopicGraphInput.Continuity) = enter(Call.Cont(input))
        override fun loseTopics(ownerScope: GraphDataScope, maxInvalidationsByTopic: Map<String, Long>) =
            enter(Call.Lose(ownerScope, maxInvalidationsByTopic.toMap()))
        override fun close() = enter(Call.Close)
    }

    private val scopes = mutableListOf<CoroutineScope>()

    private fun sinkTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
        } finally {
            scopes.forEach { it.cancel() }
            scopes.clear()
        }
    }

    private inner class Rig(test: TestScope) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler)).also { scopes += it }
        val consumer = Consumer()
        val sink = GraphRecorderTopicSink(scope, consumer)
    }

    /** K01: 512 units, whole batches, no eviction, and a dequeued input's units free again before its call. */
    @Test fun K01_theBudgetIs512UnitsOfWholeBatches() = sinkTest {
        Rig(this).run {
            assertEquals(TopicGraphOffer.ENQUEUED, sink.tryOffer(obs(512)))
            assertEquals("a fact is one unit", TopicGraphOffer.FULL, sink.tryOffer(fact()))
            assertTrue("offering calls nothing", consumer.calls.isEmpty())
        }
        Rig(this).run {
            assertEquals(TopicGraphOffer.FULL, sink.tryOffer(obs(513)))
        }
        Rig(this).run {
            assertEquals(TopicGraphOffer.ENQUEUED, sink.tryOffer(obs(500)))
            repeat(12) { assertEquals("fact $it", TopicGraphOffer.ENQUEUED, sink.tryOffer(fact())) }
            assertEquals(TopicGraphOffer.FULL, sink.tryOffer(fact()))
            assertEquals(TopicGraphOffer.FULL, sink.tryOffer(obs(1)))
            assertTrue(consumer.calls.isEmpty())
            runCurrent()
            assertEquals("nothing queued was evicted", 13, consumer.calls.count { it is Call.Obs || it is Call.Cont })
            assertEquals("the budget is back", TopicGraphOffer.ENQUEUED, sink.tryOffer(obs(512)))
        }
        Rig(this).run {
            val first = obs(300)
            sink.tryOffer(first)
            sink.tryOffer(obs(212))
            val during = mutableListOf<TopicGraphOffer>()
            consumer.onCall = { call ->
                if (call == Call.Obs(first)) {
                    during += sink.tryOffer(obs(300))
                    during += sink.tryOffer(fact())
                }
            }
            runCurrent()
            assertEquals(listOf(TopicGraphOffer.ENQUEUED, TopicGraphOffer.FULL), during)
        }
    }

    /** K02: the same objects, in order, once each, never one call inside another. */
    @Test fun K02_inputsReachTheConsumerAsGivenInOrderOneAtATime() = sinkTest {
        Rig(this).run {
            val o1 = obs(2)
            val c1 = fact()
            val o2 = obs(3, topic = "dxy:spot")
            listOf(o1, c1, o2).forEach { assertEquals(TopicGraphOffer.ENQUEUED, sink.tryOffer(it)) }
            assertTrue(consumer.calls.isEmpty())
            runCurrent()
            assertEquals(listOf(Call.Obs(o1), Call.Cont(c1), Call.Obs(o2)), consumer.calls)
            assertEquals("no call inside another", 1, consumer.maxDepth)
            runCurrent()
            assertEquals("each once", 3, consumer.calls.size)
        }
    }

    /**
     * K03: a refusal reaches the consumer as a loss with no later offer - an oversized batch alone, the sink's own failure - once;
     * between queued inputs it is handed over after the input being handed over; a loss made meanwhile waits for the next turn.
     */
    @Test fun K03_aRefusalIsHandedOverAsALoss() = sinkTest {
        Rig(this).run {
            assertEquals(TopicGraphOffer.FULL, sink.tryOffer(obs(513)))
            runCurrent()
            assertEquals(listOf<Call>(Call.Lose(scopeN, mapOf("fx:usd-krw" to 3L))), consumer.calls)
            runCurrent()
            assertEquals("not handed over twice", 1, consumer.calls.size)
            val next = obs(1)
            sink.tryOffer(next)
            runCurrent()
            assertEquals(listOf(Call.Lose(scopeN, mapOf("fx:usd-krw" to 3L)), Call.Obs(next)), consumer.calls)
        }
        Rig(this).run {
            assertEquals(TopicGraphOffer.FAILED, sink.tryOffer(broken(topic = "dxy:spot", lifetime = l4)))
            runCurrent()
            assertEquals("failed and never queued", listOf<Call>(Call.Lose(scopeN, mapOf("dxy:spot" to 4L))), consumer.calls)
        }
        Rig(this).run {
            val a = obs(256)
            val b = obs(256)
            sink.tryOffer(a)
            sink.tryOffer(b)
            assertEquals(TopicGraphOffer.FULL, sink.tryOffer(obs(1, topic = "dxy:spot")))
            runCurrent()
            assertEquals(listOf(Call.Obs(a), Call.Lose(scopeN, mapOf("dxy:spot" to 3L)), Call.Obs(b)), consumer.calls)
        }
        Rig(this).run {
            sink.tryOffer(obs(513))
            consumer.onCall = { call ->
                if (call is Call.Lose && "fx:usd-krw" in call.topics) sink.tryOffer(obs(513, topic = "dxy:spot"))
            }
            runCurrent()
            assertEquals(
                listOf(Call.Lose(scopeN, mapOf("fx:usd-krw" to 3L)), Call.Lose(scopeN, mapOf("dxy:spot" to 3L))),
                consumer.calls
            )
        }
    }

    /** K04: the ledger keeps each original scope apart, the highest count per topic, and skips inputs it cannot attribute. */
    @Test fun K04_theLedgerKeepsTheOriginalScopeAndTheHighestCount() = sinkTest {
        Rig(this).run {
            sink.tryOffer(obs(512, topic = "dxy:spot"))
            listOf(
                obs(1, owner = fenceN, lifetime = l4),
                obs(1, owner = fenceN, lifetime = l3),
                obs(1, owner = fenceN2, lifetime = l3),
                fact(topics = setOf("fx:usd-krw", "fx:jpy-krw"), owner = fenceN2, lifetime = l4),
                fact(topics = setOf("fx:eur-krw"), owner = null, lifetime = null),
                fact(topics = setOf("fx:eur-krw"), owner = fenceN, lifetime = null),
                fact(topics = setOf("fx:eur-krw"), owner = noEpoch),
                obs(1, topic = "fx:eur-krw", owner = noEpoch)
            ).forEachIndexed { i, input -> assertEquals("input $i", TopicGraphOffer.FULL, sink.tryOffer(input)) }
            runCurrent()
            val losses = consumer.calls.filterIsInstance<Call.Lose>()
            assertEquals("one call per scope", 2, losses.size)
            assertEquals(
                mapOf(
                    scopeN to mapOf("fx:usd-krw" to 4L),
                    scopeN2 to mapOf("fx:usd-krw" to 4L, "fx:jpy-krw" to 4L)
                ),
                losses.associate { it.scope to it.topics }
            )
        }
    }

    /** K05: close ends the pipeline once and leaves the supplied scope alive; a worker that ended stops acceptance too. */
    @Test fun K05_closeEndsThePipelineOnce() = sinkTest {
        Rig(this).run {
            sink.tryOffer(obs(2))
            sink.tryOffer(obs(513))
            sink.close()
            assertEquals(listOf<Call>(Call.Close), consumer.calls)
            sink.close()
            assertEquals("closed once", 1, consumer.calls.size)
            assertEquals(TopicGraphOffer.CLOSED, sink.tryOffer(obs(1)))
            assertEquals("closed before the input is read", TopicGraphOffer.CLOSED, sink.tryOffer(broken()))
            runCurrent()
            assertEquals("nothing queued or ledgered comes back", listOf<Call>(Call.Close), consumer.calls)
            assertTrue("the supplied scope is left alive", scope.isActive)
        }
        Rig(this).run {
            scope.cancel()
            runCurrent()
            assertEquals(TopicGraphOffer.CLOSED, sink.tryOffer(obs(1)))
        }
    }

    /** K06: a consumer call that throws - an input or a loss - ends the pipeline: no retry, nothing more, no escape. */
    @Test fun K06_aConsumerFailureEndsThePipeline() = sinkTest {
        for (failOnLoss in listOf(false, true)) {
            Rig(this).run {
                val first = obs(256)
                sink.tryOffer(first)
                sink.tryOffer(obs(256))
                sink.tryOffer(obs(1, topic = "dxy:spot"))
                consumer.onCall = { call ->
                    if (if (failOnLoss) call is Call.Lose else call == Call.Obs(first)) throw IllegalStateException("consumer")
                }
                runCurrent()
                val expected = if (failOnLoss) {
                    listOf(Call.Obs(first), Call.Lose(scopeN, mapOf("dxy:spot" to 3L)), Call.Close)
                } else {
                    listOf(Call.Obs(first), Call.Close)
                }
                assertEquals("$failOnLoss", expected, consumer.calls)
                assertEquals("$failOnLoss", TopicGraphOffer.CLOSED, sink.tryOffer(obs(1)))
                runCurrent()
                assertEquals("$failOnLoss: nothing retried", expected, consumer.calls)
                assertTrue("$failOnLoss", scope.isActive)
            }
        }
    }

    /**
     * K07 (F2e C3): a purge removes exactly the selected queued inputs and ledger scopes - never one without an owner or an
     * epoch - gives their units back, keeps the rest in order and says whether anything went, also for a zero-unit input; a
     * second purge and a closed sink answer false.
     */
    @Test fun K07_aPurgeRemovesTheSelectedScopesOnly() = sinkTest {
        val retired: (GraphDataScope) -> Boolean = { it.uid == "u1" && it.userAccessEpoch != "e2" }
        Rig(this).run {
            val o1 = obs(300)
            val o2 = obs(10, owner = fenceU2)
            val o3 = obs(10, owner = fenceN2)
            val c4 = fact(owner = fenceN3)
            val c5 = fact(owner = null)
            val o6 = obs(5, owner = noEpoch)
            listOf(o1, o2, o3, c4, c5, o6).forEach { assertEquals(TopicGraphOffer.ENQUEUED, sink.tryOffer(it)) }
            listOf(fenceN, fenceU2, fenceN2).forEach { assertEquals(TopicGraphOffer.FULL, sink.tryOffer(obs(513, owner = it))) }
            assertTrue(sink.purge(retired))
            assertFalse("nothing selected is left", sink.purge(retired))
            val fill = obs(486, owner = fenceN2)
            assertEquals("the refund fits exactly", TopicGraphOffer.ENQUEUED, sink.tryOffer(fill))
            assertEquals("and no more", TopicGraphOffer.FULL, sink.tryOffer(fact(owner = null)))
            assertTrue("a purge calls nothing", consumer.calls.isEmpty())
            runCurrent()
            assertEquals(
                "the rest, in order",
                listOf(Call.Obs(o2), Call.Obs(o3), Call.Cont(c5), Call.Obs(o6), Call.Obs(fill)),
                consumer.calls.filter { it !is Call.Lose }
            )
            val losses = consumer.calls.filterIsInstance<Call.Lose>()
            assertEquals(2, losses.size)
            assertEquals(
                setOf(Call.Lose(GraphDataScope("u2", "e1"), mapOf("fx:usd-krw" to 3L)), Call.Lose(scopeN2, mapOf("fx:usd-krw" to 3L))),
                losses.toSet()
            )
        }
        Rig(this).run {
            assertEquals(TopicGraphOffer.ENQUEUED, sink.tryOffer(obs(0)))
            assertTrue("a zero-unit input counts", sink.purge(retired))
            runCurrent()
            assertTrue(consumer.calls.isEmpty())
        }
        Rig(this).run {
            sink.tryOffer(obs(1))
            sink.close()
            assertFalse(sink.purge(retired))
            runCurrent()
            assertEquals(listOf<Call>(Call.Close), consumer.calls)
        }
    }
}
