package com.jay.fxi.service

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 E5b2a contract (s7e5b2_api_agreed.r1.md §2.3, R03): the capability monitor. Every admitted foreground — the first one
 * included — re-reads the capability and re-evaluates, also when it was GRANTED before; the first foreground's demand and
 * the premium publication's replay are one run. Each later premium/owner publication re-evaluates, so a premium confirmed
 * after the first read is not lost, and every publication re-reads the display (gap G04). A user-action result re-reads,
 * and only for the still-current, server-confirmed owner — the exact session, never a newer one in its place (G12) —
 * re-evaluates and allows its one continuation (true only when the OS can show alerts); a completed action can be retried
 * (G14). Re-evaluations run one at a time; a demand equal to the one in flight joins it (G03), a different one waits for
 * one more run with the latest observation (G05). A failing re-evaluation or read is reported and the next demand still
 * runs (G13); a cancellation inside a re-evaluation ends only its consumer, and a waiting demand still runs (G06). A
 * closed admission reads, observes and re-evaluates nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class S7E5B2AMonitorContractTest {

    private companion object {
        val A1 = AuthIdentityFence("user-a", 1L)
        val B1 = AuthIdentityFence("user-b", 1L)
        fun confirmed(owner: AuthIdentityFence) = OwnedPremiumAccess(owner.uid, owner.authGeneration, PremiumAccessState.PremiumConfirmed)
        fun unconfirmed(owner: AuthIdentityFence) = OwnedPremiumAccess(owner.uid, owner.authGeneration, PremiumAccessState.NoGrant)
    }

    private class Rig(
        var capability: NotificationCapability = NotificationCapability.GRANTED,
        var live: AuthIdentityFence? = A1,
        access: OwnedPremiumAccess = confirmed(A1),
        var admitted: Boolean = true,
        var failNext: Boolean = false,
        var cancelNext: Boolean = false,
        var failReadOnce: Boolean = false
    ) {
        val scope = TestScope(StandardTestDispatcher())
        val premium = MutableStateFlow(access)
        var reads = 0
        val runs = mutableListOf<AuthIdentityFence?>()
        val seen = mutableListOf<Pair<NotificationCapability, OwnedPremiumAccess>>()
        val jobs = mutableListOf<kotlinx.coroutines.Job?>()
        var identityReads = 0
        var identityScript: ((Int) -> AuthIdentityFence?)? = null
        var gate: CompletableDeferred<Unit>? = null
        var failures = 0
        val monitor = NotificationCapabilityMonitor(
            read = {
                reads++
                if (failReadOnce) {
                    failReadOnce = false
                    throw IllegalStateException("read")
                }
                capability
            },
            reconcile = { owner ->
                runs += owner
                seen += capability to premium.value
                jobs += kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
                gate?.await()
                if (failNext) {
                    failNext = false
                    throw IllegalStateException("reconcile")
                }
                if (cancelNext) {
                    cancelNext = false
                    throw kotlinx.coroutines.CancellationException("an inner cancellation, not the monitor's scope")
                }
            },
            premium = premium,
            liveIdentity = { identityScript?.invoke(identityReads++) ?: live },
            scope = scope,
            admitted = { admitted },
            reportFailure = { failures++ }
        )

        fun settle() = scope.runCurrent()
        fun close() = scope.cancel()
    }

    private fun withRig(rig: Rig, block: Rig.() -> Unit) {
        try {
            rig.block()
        } finally {
            rig.close()
        }
    }

    @Test fun `S7E5B2A_M01 the first foreground reads and re-evaluates once for the live owner`() = withRig(Rig()) {
        assertNull("unknown before the first read", monitor.state.value)
        monitor.onForeground()
        assertEquals(NotificationCapability.GRANTED, monitor.state.value)
        assertEquals(1, reads)
        settle()
        assertEquals("the foreground demand and the publication replay are one run", listOf<AuthIdentityFence?>(A1), runs)
    }

    @Test fun `S7E5B2A_M02 every later foreground re-evaluates again even when it was granted all along`() = withRig(Rig()) {
        monitor.onForeground(); settle()
        monitor.onForeground(); settle()
        monitor.onForeground(); settle()
        assertEquals(List(3) { A1 }, runs)
        assertEquals("one read per foreground, and one for the first publication's replay", 4, reads)
    }

    @Test fun `S7E5B2A_M03 a premium confirmed after the first read gets its own re-evaluation`() = withRig(Rig(access = unconfirmed(A1))) {
        monitor.onForeground(); settle()
        assertEquals("the first read re-evaluates whatever the premium", listOf<AuthIdentityFence?>(A1), runs)
        premium.value = confirmed(A1); settle()
        assertEquals(listOf<AuthIdentityFence?>(A1, A1), runs)
    }

    @Test fun `S7E5B2A_M04 an owner change is re-evaluated for the owner live at the publication`() = withRig(Rig()) {
        monitor.onForeground(); settle()
        live = B1
        premium.value = confirmed(B1); settle()
        assertEquals(listOf<AuthIdentityFence?>(A1, B1), runs)
        live = null
        premium.value = OwnedPremiumAccess(); settle()
        assertEquals("a sign-out re-evaluates with no owner", listOf(A1, B1, null), runs)
    }

    @Test fun `S7E5B2A_M05 publications are observed once whatever the number of foregrounds`() = withRig(Rig(access = unconfirmed(A1))) {
        repeat(3) { monitor.onForeground(); settle() }
        runs.clear()
        premium.value = confirmed(A1); settle()
        assertEquals("one subscription, one run", listOf<AuthIdentityFence?>(A1), runs)
    }

    @Test fun `S7E5B2A_M06 nothing is observed before the first foreground`() = withRig(Rig(access = unconfirmed(A1))) {
        premium.value = confirmed(A1); settle()
        assertEquals(emptyList<AuthIdentityFence?>(), runs)
        assertEquals(0, reads)
    }

    @Test fun `S7E5B2A_M07 a closed admission reads observes and re-evaluates nothing`() = withRig(Rig(admitted = false)) {
        monitor.onForeground(); settle()
        assertFalse(monitor.onRequestResult(A1))
        assertFalse(monitor.onAlreadyGranted(A1))
        premium.value = confirmed(B1); settle()
        assertEquals(0, reads)
        assertEquals(emptyList<AuthIdentityFence?>(), runs)
        assertNull(monitor.state.value)
    }

    @Test fun `S7E5B2A_M08 a result for the current confirmed owner re-evaluates and allows its continuation only when granted`() {
        for (capability in NotificationCapability.entries) withRig(Rig(capability = capability)) {
            val expected = capability == NotificationCapability.GRANTED
            assertEquals("result $capability", expected, monitor.onRequestResult(A1))
            assertEquals("already granted $capability", expected, monitor.onAlreadyGranted(A1))
            assertEquals("each result re-read", 2, reads)
            assertEquals(capability, monitor.state.value)
            settle()
            assertTrue("$capability: re-evaluated for that owner", runs.isNotEmpty() && runs.all { it == A1 })
        }
    }

    @Test fun `S7E5B2A_M09 a result captured for another session or an unconfirmed one is not handed on`() {
        for ((label, rig, owner) in listOf(
            Triple("no owner", Rig(), null),
            Triple("another uid", Rig(live = B1, access = confirmed(B1)), A1),
            Triple("signed out", Rig(live = null), A1),
            Triple("unconfirmed", Rig(access = unconfirmed(A1)), A1),
            Triple("another session's grant", Rig(access = confirmed(B1)), A1)
        )) withRig(rig) {
            assertFalse(label, monitor.onRequestResult(owner))
            if (owner != null) assertFalse(label, monitor.onAlreadyGranted(owner))
            settle()
            assertEquals("$label: no re-evaluation for it", emptyList<AuthIdentityFence?>(), runs)
            assertEquals("$label: the display is still refreshed", NotificationCapability.GRANTED, monitor.state.value)
        }
    }

    @Test fun `S7E5B2A_M10 demands during a run collapse into one more run for the latest owner`() = withRig(Rig()) {
        gate = CompletableDeferred()
        monitor.onForeground(); settle()
        assertEquals(listOf<AuthIdentityFence?>(A1), runs)
        monitor.onForeground()
        monitor.onAlreadyGranted(A1)
        live = B1
        premium.value = confirmed(B1)
        monitor.onForeground(); settle()
        assertEquals("still one run in flight", listOf<AuthIdentityFence?>(A1), runs)
        gate!!.complete(Unit); settle()
        assertEquals("one more run, for the latest owner", listOf<AuthIdentityFence?>(A1, B1), runs)
    }

    @Test fun `S7E5B2A_M11 a failing re-evaluation is reported and the next demand still runs`() = withRig(Rig(failNext = true)) {
        monitor.onForeground(); settle()
        assertEquals(1, failures)
        monitor.onForeground(); settle()
        assertEquals(listOf<AuthIdentityFence?>(A1, A1), runs)
        assertEquals(1, failures)
    }

    @Test fun `S7E5B2A_M12 a cancellation thrown by a re-evaluation is not swallowed and the next demand still runs`() = withRig(Rig(cancelNext = true)) {
        monitor.onForeground(); settle()
        assertEquals("not reported as a failure", 0, failures)
        monitor.onForeground(); settle()
        assertEquals("a new consumer took the next demand", listOf<AuthIdentityFence?>(A1, A1), runs)
    }

    // ---- gap hunt r1 (s7e5b2a_gaphunt.codex.r1.md) ----

    @Test fun `S7E5B2A_GAP03 a demand equal to the one in flight joins it and a later one runs again`() = withRig(Rig()) {
        gate = CompletableDeferred()
        monitor.onForeground(); settle()
        assertEquals(listOf<AuthIdentityFence?>(A1), runs)
        monitor.onForeground()
        assertTrue(monitor.onRequestResult(A1))
        assertTrue(monitor.onAlreadyGranted(A1))
        settle()
        gate!!.complete(Unit); settle()
        assertEquals("joined the run in flight", listOf<AuthIdentityFence?>(A1), runs)
        monitor.onForeground(); settle()
        assertEquals("after it, a new demand runs", listOf<AuthIdentityFence?>(A1, A1), runs)
    }

    @Test fun `S7E5B2A_GAP04 each publication re-reads the display`() = withRig(Rig(access = unconfirmed(A1))) {
        monitor.onForeground(); settle()
        val before = reads
        capability = NotificationCapability.APP_DISABLED
        premium.value = confirmed(A1); settle()
        assertEquals(NotificationCapability.APP_DISABLED, monitor.state.value)
        assertEquals(before + 1, reads)
        capability = NotificationCapability.GRANTED
        premium.value = unconfirmed(A1); settle()
        assertEquals(NotificationCapability.GRANTED, monitor.state.value)
        assertEquals(before + 2, reads)
        assertEquals(List(3) { A1 }, runs)
    }

    @Test fun `S7E5B2A_GAP05 a changed capability or access for the same owner is not lost while a run is in flight`() {
        withRig(Rig()) {
            gate = CompletableDeferred()
            monitor.onForeground(); settle()
            capability = NotificationCapability.APP_DISABLED
            monitor.onForeground(); settle()
            gate!!.complete(Unit); settle()
            assertEquals("capability changed: one more run", listOf<AuthIdentityFence?>(A1, A1), runs)
            assertEquals(NotificationCapability.APP_DISABLED, monitor.state.value)
            assertEquals("the follow-up saw the latest capability", NotificationCapability.APP_DISABLED, seen.last().first)
        }
        withRig(Rig()) {
            gate = CompletableDeferred()
            monitor.onForeground(); settle()
            premium.value = unconfirmed(A1); settle()
            gate!!.complete(Unit); settle()
            assertEquals("access changed: one more run", listOf<AuthIdentityFence?>(A1, A1), runs)
            assertEquals("the follow-up saw the latest access", unconfirmed(A1), seen.last().second)
        }
    }

    @Test fun `S7E5B2A_GAP06a an inner cancellation cancels its consumer and is not reported`() = withRig(Rig(cancelNext = true)) {
        monitor.onForeground(); settle()
        assertEquals(0, failures)
        assertEquals("the consumer that took the cancellation is cancelled", true, jobs.single()?.isCancelled)
        monitor.onForeground(); settle()
        assertEquals(listOf<AuthIdentityFence?>(A1, A1), runs)
        assertTrue("a new consumer took the next demand", jobs[1] !== jobs[0])
    }

    @Test fun `S7E5B2A_GAP06b a demand waiting behind an inner cancellation still runs without a new demand`() = withRig(Rig()) {
        gate = CompletableDeferred()
        monitor.onForeground(); settle()
        live = B1
        premium.value = confirmed(B1); settle()
        cancelNext = true
        gate!!.complete(Unit); settle()
        assertEquals(listOf<AuthIdentityFence?>(A1, B1), runs)
        assertEquals(0, failures)
    }

    @Test fun `S7E5B2A_GAP06c a cancelled scope runs nothing more`() = withRig(Rig()) {
        gate = CompletableDeferred()
        monitor.onForeground(); settle()
        live = B1
        premium.value = confirmed(B1); settle()
        scope.cancel()
        gate!!.complete(Unit); settle()
        assertEquals(listOf<AuthIdentityFence?>(A1), runs)
    }

    @Test fun `S7E5B2A_GAP12a a result for the same uid under an older generation is rejected`() {
        val a2 = AuthIdentityFence("user-a", 2L)
        for (access in listOf(confirmed(A1), confirmed(a2))) withRig(Rig(live = a2, access = access)) {
            assertFalse("$access", monitor.onRequestResult(A1))
            assertFalse("$access", monitor.onAlreadyGranted(A1))
            settle()
            assertEquals("$access", emptyList<AuthIdentityFence?>(), runs)
            assertEquals("the display is still refreshed", NotificationCapability.GRANTED, monitor.state.value)
        }
    }

    @Test fun `S7E5B2A_GAP12b a result never hands on a newer live owner in place of its own`() {
        for (useAlreadyGranted in listOf(false, true)) withRig(Rig()) {
            identityScript = { index -> if (index == 0) A1 else B1 }
            val allowed = if (useAlreadyGranted) monitor.onAlreadyGranted(A1) else monitor.onRequestResult(A1)
            assertTrue(allowed)
            premium.value = confirmed(B1)
            settle()
            assertFalse("B1 was handed on for A1's action ($useAlreadyGranted)", runs.contains(B1))
        }
    }

    @Test fun `S7E5B2A_GAP13 a failed read is reported and the publications go on being observed`() = withRig(Rig(access = unconfirmed(A1))) {
        monitor.onForeground(); settle()
        failReadOnce = true
        premium.value = confirmed(A1); settle()
        assertEquals(1, failures)
        assertEquals("the last value stays shown", NotificationCapability.GRANTED, monitor.state.value)
        capability = NotificationCapability.APP_DISABLED
        premium.value = unconfirmed(A1); settle()
        assertEquals(NotificationCapability.APP_DISABLED, monitor.state.value)
        assertEquals(List(3) { A1 }, runs)
        assertEquals(1, failures)
    }

    @Test fun `S7E5B2A_GAP14 a completed user action re-evaluates again on the next one`() {
        for (useAlreadyGranted in listOf(false, true)) withRig(Rig()) {
            val act = { if (useAlreadyGranted) monitor.onAlreadyGranted(A1) else monitor.onRequestResult(A1) }
            assertTrue(act()); settle()
            assertEquals(listOf<AuthIdentityFence?>(A1), runs)
            assertTrue(act()); settle()
            assertEquals("no cache of the completed action ($useAlreadyGranted)", listOf<AuthIdentityFence?>(A1, A1), runs)
        }
    }
}
