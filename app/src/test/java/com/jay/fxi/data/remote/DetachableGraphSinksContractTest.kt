package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityFence
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** S4 CUT-CC4a: the graph's two detachable connections (`cut_cc4_agreed.r1.md`). */
class DetachableGraphSinksContractTest {

    private val fence = TopicSessionFence(AuthIdentityFence("u1", 1L), "epoch-1", TopicGrantToken(1L))
    private val input = TopicGraphInput.Continuity(
        sequence = 1L,
        kind = TopicGraphEventKind.INITIAL,
        reason = null,
        topics = emptySet(),
        paths = emptySet(),
        authority = TopicGraphAuthority(Any(), fence, 1L, null),
        connectionGeneration = null,
        occurredAtEpochMillis = 0L
    )

    /** W01: until detached each offer reaches the target and returns its answer; afterwards DORMANT and the target is not called. */
    @Test fun W01_theInputSinkForwardsUntilDetached_thenAnswersDormant() {
        val offered = mutableListOf<TopicGraphInput>()
        val sink = DetachableTopicGraphSink { offered += it; TopicGraphOffer.FULL }
        assertEquals("W01 the target's answer", TopicGraphOffer.FULL, sink.tryOffer(input))
        sink.detach()
        assertEquals("W01 dormant once detached", TopicGraphOffer.DORMANT, sink.tryOffer(input))
        assertEquals("W01 the target saw only the first", listOf(input), offered)
        sink.detach()
        assertEquals("W01 detaching again is harmless", TopicGraphOffer.DORMANT, sink.tryOffer(input))
    }

    /** W02: until detached both grant calls reach the target; afterwards neither does. */
    @Test fun W02_theGrantSinkForwardsUntilDetached_thenDoesNothing() {
        val calls = mutableListOf<String>()
        val target = object : TopicGrantSink {
            override fun setAccess(allowed: Boolean, fence: TopicSessionFence?, origin: TopicGrantOrigin) {
                calls += "set:$allowed:${fence?.grant?.value}"
            }
            override fun accessRevised() { calls += "revised" }
        }
        val sink = DetachableTopicGrantSink(target)
        sink.setAccess(true, fence, TopicGrantOrigin.NewContext)
        sink.accessRevised()
        sink.detach()
        sink.setAccess(false, fence, TopicGrantOrigin.NewContext)
        sink.accessRevised()
        sink.detach()
        assertEquals("W02 only the calls before detach", listOf("set:true:1", "revised"), calls)
    }

    /**
     * W03: a detach issued while a call is inside the target waits for that call to return — observed as the detaching thread
     * BLOCKED on the shared monitor — and nothing reaches the target after detach returns. Both connections, both grant calls.
     */
    @Test fun W03_detachWaitsForACallInsideTheTarget_andNothingFollowsIt() {
        for (kind in listOf("input", "grant access", "grant revision")) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            var afterDetach = 0
            var detached = false
            val hold = {
                if (detached) afterDetach++
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            val detach: () -> Unit
            val call: () -> Unit
            if (kind == "input") {
                val sink = DetachableTopicGraphSink { hold(); TopicGraphOffer.ENQUEUED }
                detach = sink::detach
                call = { sink.tryOffer(input) }
            } else {
                val sink = DetachableTopicGrantSink(object : TopicGrantSink {
                    override fun setAccess(allowed: Boolean, fence: TopicSessionFence?, origin: TopicGrantOrigin) { hold() }
                    override fun accessRevised() { hold() }
                })
                detach = sink::detach
                call = if (kind == "grant access") {
                    { sink.setAccess(true, fence, TopicGrantOrigin.NewContext) }
                } else {
                    { sink.accessRevised() }
                }
            }
            val caller = Thread { call() }.apply { start() }
            assertTrue("W03 $kind fixture: the call is inside the target", entered.await(5, TimeUnit.SECONDS))
            val detacher = Thread { detach(); detached = true }.apply { start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (detacher.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
            assertSame("W03 $kind the detach waits on the call", Thread.State.BLOCKED, detacher.state)
            release.countDown()
            caller.join(5_000)
            detacher.join(5_000)
            assertTrue("W03 $kind fixture: detached", detached)
            call()
            assertEquals("W03 $kind nothing reached the target after detach", 0, afterDetach)
        }
    }
}
