package com.jay.fxi.ui.alert

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.confirmsPremiumFor
import com.jay.fxi.service.NotificationCapability
import com.jay.fxi.service.NotificationCapability.APP_DISABLED
import com.jay.fxi.service.NotificationCapability.CHANNEL_DISABLED
import com.jay.fxi.service.NotificationCapability.GRANTED
import com.jay.fxi.service.NotificationCapability.RUNTIME_MISSING
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 E5b2b1 contract (s7e5b2_api_agreed.r1.md §3.1 + errata r1–r2, s7e5b2b_start_agreed.r1.md D3–D8 and §4): the helper's
 * decision table. The OS port is a fake here; the real dialog, launcher and settings are device evidence, not this.
 */
class S7E5B2B1HelperContractTest {

    private class Os : NotificationPermissionOs {
        /** null makes the read throw. */
        var capability: NotificationCapability? = RUNTIME_MISSING
        var rationale = false
        var request = NotificationLaunchOutcome.ACCEPTED
        val settingsOutcome = mutableMapOf<NotificationSettingsTarget, NotificationLaunchOutcome>()
        var duringRequest: () -> Unit = {}
        val log = mutableListOf<String>()
        var marks = 0

        override fun read(): NotificationCapability {
            log += "read"
            return capability ?: throw IllegalStateException("read failed")
        }

        override fun shouldShowRationale(): Boolean {
            log += "rationale"
            return rationale
        }

        override fun launchRequest(): NotificationLaunchOutcome {
            log += "request"
            duringRequest()
            return request
        }

        override fun launchSettings(target: NotificationSettingsTarget): NotificationLaunchOutcome {
            log += "settings:$target"
            return settingsOutcome[target] ?: NotificationLaunchOutcome.ACCEPTED
        }

        override fun markRequested() {
            log += "mark"
            marks += 1
        }
    }

    private class Rig {
        var admitted = true
        var identity: AuthIdentityFence? = A
        val premium = MutableStateFlow(grantFor(A))
        val displayed = MutableStateFlow<NotificationCapability?>(RUNTIME_MISSING)
        val os = Os()
        val results = mutableListOf<AuthIdentityFence?>()
        val already = mutableListOf<AuthIdentityFence>()
        var orphans = 0
        var granted = 0
        /** Makes the monitor answer false whatever the inputs (its own read failed, say). */
        var monitorRefuses = false

        /** The monitor's answer: the fresh read is GRANTED, the owner is live and its server premium is confirmed. */
        private fun answer(owner: AuthIdentityFence?): Boolean = !monitorRefuses &&
            owner != null && identity == owner && premium.value.confirmsPremiumFor(owner) && os.capability == GRANTED

        val env = NotificationPermissionEnvironment(
            admitted = { admitted },
            liveIdentity = { identity },
            premium = premium,
            displayed = displayed,
            onRequestResult = { owner -> results += owner; answer(owner) },
            onAlreadyGranted = { owner -> already += owner; answer(owner) }
        )
        val lane = NotificationPermissionLane(orphanResult = { orphans += 1; env.onRequestResult(null) })
        val action = NotificationPermissionAction(env, os, lane)

        fun run() = action.run { granted += 1 }
        fun other(os: Os = Os()) = NotificationPermissionAction(env, os, lane)
    }

    private fun quiet(r: Rig) {
        assertEquals("no OS work", emptyList<String>(), r.os.log)
        assertEquals(0, r.os.marks)
        assertEquals(emptyList<AuthIdentityFence?>(), r.results)
        assertEquals(emptyList<AuthIdentityFence>(), r.already)
        assertEquals(0, r.granted)
        assertNull(r.action.dialog)
        assertFalse("no lease", r.lane.busy)
    }

    @Test fun `S7E5B2B1_H01 a closed gate does no read launch record delegation or continuation and needs no action`() {
        val r = Rig().apply { admitted = false }
        r.run()
        quiet(r)
        assertFalse(r.action.needsAction)
    }

    @Test fun `S7E5B2B1_H02 no owner or a premium not confirmed for exactly the live session does nothing`() {
        for (access in listOf(
            OwnedPremiumAccess(), grantFor(A, PremiumAccessState.NoGrant), grantFor(A, PremiumAccessState.Pending(false)),
            grantFor(A, PremiumAccessState.FreeConfirmed), grantFor(A, PremiumAccessState.Rejected), grantFor(B),
            OwnedPremiumAccess(A.uid, A.authGeneration + 1, PremiumAccessState.PremiumConfirmed)
        )) {
            val r = Rig().apply { premium.value = access }
            r.run()
            quiet(r)
        }
        val r = Rig().apply { identity = null }
        r.run()
        quiet(r)
    }

    @Test fun `S7E5B2B1_H03 granted asks the monitor for the owner and continues once only when it confirms and the action is valid`() {
        val r = Rig().apply { os.capability = GRANTED }
        r.run()
        assertEquals(listOf("read"), r.os.log)
        assertEquals(listOf(A), r.already)
        assertEquals(1, r.granted)
        assertFalse(r.lane.busy)

        val refused = Rig().apply { os.capability = GRANTED; monitorRefuses = true }
        refused.run()
        assertEquals(listOf(A), refused.already)
        assertEquals("no confirmation, no continuation", 0, refused.granted)
        assertNull(refused.action.dialog)
        assertFalse(refused.lane.busy)
    }

    @Test fun `S7E5B2B1_H04 runtime missing without a rationale requests first and records only the accepted launch`() {
        val r = Rig()
        r.run()
        assertEquals(listOf("read", "rationale", "request", "mark"), r.os.log)
        assertEquals(1, r.os.marks)
        assertTrue("the lease waits for the result", r.lane.busy)
        assertNull(r.action.dialog)
    }

    @Test fun `S7E5B2B1_H05 a refused or unavailable request launch records nothing and frees the lease`() {
        for (outcome in listOf(NotificationLaunchOutcome.FAILED, NotificationLaunchOutcome.UNAVAILABLE)) {
            val r = Rig().apply { os.request = outcome }
            r.run()
            assertEquals(listOf("read", "rationale", "request"), r.os.log)
            assertEquals(0, r.os.marks)
            assertFalse(r.lane.busy)
            r.lane.onRequestResult()
            assertEquals("a later result is an orphan", 1, r.orphans)
            assertEquals(0, r.granted)
        }
    }

    @Test fun `S7E5B2B1_H06 a rationale is explained first and only continue requests while cancel or dismiss do nothing`() {
        val r = Rig().apply { os.rationale = true }
        r.run()
        assertEquals(NotificationPermissionDialog.Explain, r.action.dialog)
        assertEquals(listOf("read", "rationale"), r.os.log)
        assertTrue("the dialog holds the lease", r.lane.busy)
        r.action.onExplainContinue()
        assertNull(r.action.dialog)
        assertEquals(listOf("read", "rationale", "read", "request", "mark"), r.os.log)

        val c = Rig().apply { os.rationale = true }
        c.run()
        c.action.onDialogDismiss()
        assertEquals(listOf("read", "rationale"), c.os.log)
        assertEquals(0, c.os.marks)
        assertFalse(c.lane.busy)
        assertNull(c.action.dialog)
    }

    @Test fun `S7E5B2B1_H07 continue re-checks the action and the fresh capability`() {
        val lost = Rig().apply { os.rationale = true }
        lost.run()
        lost.premium.value = grantFor(A, PremiumAccessState.FreeConfirmed)
        lost.action.onExplainContinue()
        assertEquals(listOf("read", "rationale"), lost.os.log)
        assertFalse(lost.lane.busy)

        val nowGranted = Rig().apply { os.rationale = true }
        nowGranted.run()
        nowGranted.os.capability = GRANTED
        nowGranted.action.onExplainContinue()
        assertEquals(listOf(A), nowGranted.already)
        assertEquals(1, nowGranted.granted)
        assertFalse(nowGranted.lane.busy)
    }

    @Test fun `S7E5B2B1_H08 a confirmed grant continues once and a second result is an orphan`() {
        val r = Rig()
        r.run()
        r.os.capability = GRANTED
        r.lane.onRequestResult()
        assertEquals(listOf<AuthIdentityFence?>(A), r.results)
        assertEquals(1, r.granted)
        assertFalse(r.lane.busy)
        r.lane.onRequestResult()
        assertEquals(1, r.granted)
        assertEquals(1, r.orphans)
        assertEquals(listOf<AuthIdentityFence?>(A, null), r.results)
    }

    @Test fun `S7E5B2B1_H09 a fresh not-allowed result of a valid action shows the guidance and keeps the lease`() {
        for (fresh in listOf(RUNTIME_MISSING, APP_DISABLED, CHANNEL_DISABLED)) {
            val r = Rig()
            r.run()
            r.os.capability = fresh
            r.lane.onRequestResult()
            assertEquals(NotificationPermissionDialog.Denied(fresh), r.action.dialog)
            assertTrue(r.lane.busy)
            assertEquals(0, r.granted)
        }
    }

    @Test fun `S7E5B2B1_H10 a monitor false that is not a confirmed refusal never opens the guidance`() {
        val alreadyGranted = Rig()
        alreadyGranted.run()
        alreadyGranted.os.capability = GRANTED
        alreadyGranted.premium.value = grantFor(A, PremiumAccessState.Pending(true))
        alreadyGranted.lane.onRequestResult()
        assertNull("premium lost: invalid action", alreadyGranted.action.dialog)
        assertEquals(0, alreadyGranted.granted)
        assertFalse(alreadyGranted.lane.busy)

        val readFails = Rig()
        readFails.run()
        readFails.os.capability = null
        readFails.lane.onRequestResult()
        assertNull(readFails.action.dialog)
        assertEquals(0, readFails.granted)
        assertFalse(readFails.lane.busy)
    }

    @Test fun `S7E5B2B1_H11 an owner change before the publication invalidates the action`() {
        val r = Rig()
        r.run()
        r.identity = B
        r.os.capability = GRANTED
        r.lane.onRequestResult()
        assertEquals("the monitor is asked for the original owner", listOf<AuthIdentityFence?>(A), r.results)
        assertNull(r.action.dialog)
        assertEquals(0, r.granted)
        assertFalse(r.lane.busy)
    }

    @Test fun `S7E5B2B1_H12 a premium loss seen in flight invalidates the action even after premium returns`() {
        val r = Rig()
        r.run()
        r.action.onPremium(grantFor(A, PremiumAccessState.FreeConfirmed))
        r.premium.value = grantFor(A)
        r.os.capability = GRANTED
        r.lane.onRequestResult()
        assertEquals(listOf<AuthIdentityFence?>(A), r.results)
        assertEquals(0, r.granted)
        val s = Rig()
        s.run()
        s.action.onPremium(grantFor(B))
        s.lane.onRequestResult()
        assertNull("no guidance for an invalid action", s.action.dialog)
    }

    @Test fun `S7E5B2B1_H13 retry ends the old action and runs one fresh explicit action without its continuation`() {
        val r = Rig()
        r.run()
        r.lane.onRequestResult()
        assertEquals(NotificationPermissionDialog.Denied(RUNTIME_MISSING), r.action.dialog)
        r.os.log.clear()
        r.action.onDeniedRetry()
        assertNull(r.action.dialog)
        assertEquals(listOf("read", "rationale", "request", "mark"), r.os.log)
        assertTrue(r.lane.busy)
        r.os.capability = GRANTED
        r.lane.onRequestResult()
        assertEquals("the original continuation is discarded", 0, r.granted)
        assertFalse(r.lane.busy)

        val gone = Rig()
        gone.run()
        gone.lane.onRequestResult()
        gone.identity = B
        gone.os.log.clear()
        gone.action.onDeniedRetry()
        assertEquals("an invalid action retries nothing", emptyList<String>(), gone.os.log)
        assertFalse(gone.lane.busy)
    }

    @Test fun `S7E5B2B1_H14 open settings fits the fresh capability carries no continuation and its result only frees the lease`() {
        for ((fresh, target) in listOf(
            RUNTIME_MISSING to NotificationSettingsTarget.APP,
            APP_DISABLED to NotificationSettingsTarget.APP,
            CHANNEL_DISABLED to NotificationSettingsTarget.CHANNEL
        )) {
            val r = Rig()
            r.run()
            r.lane.onRequestResult()
            r.os.capability = fresh
            r.os.log.clear()
            r.action.onDeniedSettings()
            assertEquals(listOf("read", "settings:$target"), r.os.log)
            assertTrue("the lease waits for the settings result", r.lane.busy)
            val before = r.results.size
            r.lane.onSettingsResult()
            assertFalse(r.lane.busy)
            assertEquals("no monitor result call", before, r.results.size)
            assertEquals(emptyList<AuthIdentityFence>(), r.already)
            assertEquals(0, r.granted)
        }
        val granted = Rig()
        granted.run()
        granted.lane.onRequestResult()
        granted.os.capability = GRANTED
        granted.os.log.clear()
        granted.action.onDeniedSettings()
        assertEquals(listOf("read"), granted.os.log)
        assertFalse(granted.lane.busy)
    }

    @Test fun `S7E5B2B1_H15 cancel or dismiss of the guidance does nothing`() {
        val r = Rig()
        r.run()
        r.lane.onRequestResult()
        r.os.log.clear()
        r.action.onDialogDismiss()
        assertNull(r.action.dialog)
        assertEquals(emptyList<String>(), r.os.log)
        assertFalse(r.lane.busy)
        assertEquals(1, r.os.marks)
    }

    @Test fun `S7E5B2B1_H16 app and channel blocks open their settings with one details fallback when unavailable`() {
        val app = Rig().apply { os.capability = APP_DISABLED }
        app.run()
        assertEquals(listOf("read", "settings:APP"), app.os.log)
        assertTrue(app.lane.busy)

        val channel = Rig().apply { os.capability = CHANNEL_DISABLED }
        channel.run()
        assertEquals(listOf("read", "settings:CHANNEL"), channel.os.log)

        val fallback = Rig().apply {
            os.capability = CHANNEL_DISABLED
            os.settingsOutcome[NotificationSettingsTarget.CHANNEL] = NotificationLaunchOutcome.UNAVAILABLE
        }
        fallback.run()
        assertEquals(listOf("read", "settings:CHANNEL", "settings:DETAILS"), fallback.os.log)
        assertTrue(fallback.lane.busy)

        val none = Rig().apply {
            os.capability = APP_DISABLED
            os.settingsOutcome[NotificationSettingsTarget.APP] = NotificationLaunchOutcome.UNAVAILABLE
            os.settingsOutcome[NotificationSettingsTarget.DETAILS] = NotificationLaunchOutcome.UNAVAILABLE
        }
        none.run()
        assertEquals(listOf("read", "settings:APP", "settings:DETAILS"), none.os.log)
        assertFalse(none.lane.busy)

        val failed = Rig().apply {
            os.capability = APP_DISABLED
            os.settingsOutcome[NotificationSettingsTarget.APP] = NotificationLaunchOutcome.FAILED
        }
        failed.run()
        assertEquals("a launch exception has no fallback", listOf("read", "settings:APP"), failed.os.log)
        assertFalse(failed.lane.busy)
        for (r in listOf(app, channel, fallback, none, failed)) assertEquals(0, r.os.marks)
    }

    @Test fun `S7E5B2B1_H17 one lease per Activity makes a second action Busy without reading or replacing anything`() {
        val r = Rig()
        r.run()
        val secondOs = Os().apply { capability = APP_DISABLED }
        val second = r.other(secondOs)
        var secondGranted = 0
        second.run { secondGranted += 1 }
        assertEquals(emptyList<String>(), secondOs.log)
        r.os.capability = GRANTED
        r.lane.onRequestResult()
        assertEquals("the first action's continuation still runs", 1, r.granted)
        assertEquals(0, secondGranted)
        second.run { secondGranted += 1 }
        assertEquals("free again: the second action now proceeds", listOf("read", "settings:APP"), secondOs.log)
    }

    @Test fun `S7E5B2B1_H18 a settings launch keeps the lease until its own result and a late request result frees nothing`() {
        val r = Rig().apply { os.capability = APP_DISABLED }
        r.run()
        r.action.run { r.granted += 1 }
        assertEquals("Busy while the settings launch is pending", listOf("read", "settings:APP"), r.os.log)
        r.lane.onRequestResult()
        assertEquals(1, r.orphans)
        assertTrue("an orphan request result does not free a settings lease", r.lane.busy)
        r.lane.onSettingsResult()
        assertFalse(r.lane.busy)
        r.lane.onSettingsResult()
        assertFalse(r.lane.busy)
    }

    @Test fun `S7E5B2B1_H19 dispose frees a dialog's lease now and an in-flight request's lease at its result`() {
        val dialog = Rig().apply { os.rationale = true }
        dialog.run()
        dialog.action.dispose()
        assertNull(dialog.action.dialog)
        assertFalse(dialog.lane.busy)

        val inFlight = Rig()
        inFlight.run()
        inFlight.action.dispose()
        assertTrue("an OS request in flight keeps the lease", inFlight.lane.busy)
        inFlight.os.capability = GRANTED
        inFlight.lane.onRequestResult()
        assertEquals(listOf<AuthIdentityFence?>(A), inFlight.results)
        assertEquals(0, inFlight.granted)
        assertNull(inFlight.action.dialog)
        assertFalse(inFlight.lane.busy)
        inFlight.run()
        assertEquals("a disposed helper runs nothing", listOf("read", "rationale", "request", "mark"), inFlight.os.log)
    }

    @Test fun `S7E5B2B1_H20 an orphan result refreshes the display only`() {
        val r = Rig()
        r.lane.onRequestResult()
        assertEquals(1, r.orphans)
        assertEquals(listOf<AuthIdentityFence?>(null), r.results)
        assertNull(r.action.dialog)
        assertEquals(emptyList<String>(), r.os.log)
        r.lane.onSettingsResult()
        assertEquals(emptyList<String>(), r.os.log)
    }

    @Test fun `S7E5B2B1_H21 a read failure at run does nothing and frees the lease`() {
        val r = Rig().apply { os.capability = null }
        r.run()
        assertEquals(listOf("read"), r.os.log)
        assertFalse(r.lane.busy)
        assertEquals(0, r.os.marks)
        assertNull(r.action.dialog)
        assertEquals("a failed read is not granted: the monitor is not asked", emptyList<AuthIdentityFence>(), r.already)
        assertEquals(0, r.granted)
    }

    @Test fun `S7E5B2B1_H22 a result delivered inside the launch neither loses the record nor sticks the lease`() {
        val r = Rig()
        r.os.duringRequest = {
            r.os.capability = GRANTED
            r.lane.onRequestResult()
        }
        r.run()
        assertEquals(listOf("read", "rationale", "request", "mark"), r.os.log)
        assertEquals(1, r.granted)
        assertFalse(r.lane.busy)
    }

    @Test fun `S7E5B2B1_H23 needsAction is the gate the shown capability and premium for the live identity`() {
        val r = Rig()
        assertTrue(r.action.needsAction)
        r.displayed.value = null
        assertFalse("unknown is not allowed and not an action either", r.action.needsAction)
        r.displayed.value = GRANTED
        assertFalse(r.action.needsAction)
        for (shown in listOf(RUNTIME_MISSING, APP_DISABLED, CHANNEL_DISABLED)) {
            r.displayed.value = shown
            assertTrue(r.action.needsAction)
        }
        r.identity = B
        assertFalse("auth moved before the publication", r.action.needsAction)
        r.identity = A
        r.premium.value = grantFor(A, PremiumAccessState.FreeConfirmed)
        assertFalse(r.action.needsAction)
        r.premium.value = grantFor(A)
        r.admitted = false
        assertFalse(r.action.needsAction)
        assertEquals("needsAction reads nothing from the OS", emptyList<String>(), r.os.log)
    }

    @Test fun `S7E5B2B1_H24 the request record is written only for accepted launches`() {
        val explainedThenCancelled = Rig().apply { os.rationale = true }
        explainedThenCancelled.run()
        explainedThenCancelled.action.onDialogDismiss()
        val busy = Rig()
        busy.run()
        val busyOs = Os()
        busy.other(busyOs).run()
        val settings = Rig().apply { os.capability = APP_DISABLED }
        settings.run()
        assertEquals(0, explainedThenCancelled.os.marks)
        assertEquals(1, busy.os.marks)
        assertEquals(0, busyOs.marks)
        assertEquals(0, settings.os.marks)
    }

    @Test fun `S7E5B2B1_H25 a valid action whose monitor says false while the fresh read is granted gets no guidance`() {
        val r = Rig()
        r.run()
        r.os.capability = GRANTED
        r.monitorRefuses = true
        r.lane.onRequestResult()
        assertEquals(listOf<AuthIdentityFence?>(A), r.results)
        assertNull("granted is no refusal", r.action.dialog)
        assertEquals(0, r.granted)
        assertFalse(r.lane.busy)
    }

    @Test fun `S7E5B2B1_H26 every dialog choice acts once and only on its own dialog`() {
        val explain = Rig().apply { os.rationale = true }
        explain.run()
        explain.action.onExplainContinue()
        explain.action.onExplainContinue()
        assertEquals("one request for two taps", 1, explain.os.log.count { it == "request" })
        explain.action.onDeniedRetry()
        explain.action.onDeniedSettings()
        explain.action.onDialogDismiss()
        assertTrue("no dialog: the in-flight request keeps its lease", explain.lane.busy)
        assertEquals(1, explain.os.log.count { it == "request" })

        val r = Rig()
        r.run()
        r.lane.onRequestResult()
        r.action.onDialogDismiss()
        val otherOs = Os()
        val other = r.other(otherOs)
        other.run()
        assertTrue("the other action holds the lease", r.lane.busy)
        r.action.onDialogDismiss()
        r.action.onDeniedRetry()
        r.action.onDeniedSettings()
        r.action.onExplainContinue()
        assertTrue("a stale choice frees nobody else's lease", r.lane.busy)
        assertEquals(listOf("read", "rationale", "request", "mark"), otherOs.log)
    }

    @Test fun `S7E5B2B1_H27 a guidance choice on an action invalidated in flight does nothing even after premium returns`() {
        for (choice in listOf("retry", "settings")) {
            val r = Rig()
            r.run()
            r.lane.onRequestResult()
            assertEquals(NotificationPermissionDialog.Denied(RUNTIME_MISSING), r.action.dialog)
            r.action.onPremium(grantFor(A, PremiumAccessState.FreeConfirmed))
            r.premium.value = grantFor(A)
            r.os.log.clear()
            if (choice == "retry") r.action.onDeniedRetry() else r.action.onDeniedSettings()
            assertEquals("$choice: nothing for an invalidated action", emptyList<String>(), r.os.log)
            assertNull(r.action.dialog)
            assertFalse(r.lane.busy)
        }
    }

    @Test fun `S7E5B2B1_H28 a screen that leaves after its action ended frees nobody else's lease`() {
        val r = Rig().apply { os.capability = GRANTED }
        r.run()
        assertFalse(r.lane.busy)
        val otherOs = Os()
        r.other(otherOs).run()
        assertTrue("the other action holds the lease", r.lane.busy)
        r.action.dispose()
        assertTrue("the ended action's dispose frees nothing", r.lane.busy)
        r.lane.onRequestResult()
        assertFalse("the holder's own result frees it", r.lane.busy)
    }

    @Test fun `S7E5B2B1_H29 a settings launch in flight keeps its lease through dispose until its own result`() {
        val r = Rig().apply { os.capability = CHANNEL_DISABLED }
        r.run()
        r.action.dispose()
        assertTrue("the settings screen is still open: the lease stays", r.lane.busy)
        val otherOs = Os()
        r.other(otherOs).run()
        assertEquals("Busy meanwhile", emptyList<String>(), otherOs.log)
        r.lane.onSettingsResult()
        assertFalse(r.lane.busy)
        assertEquals(0, r.granted)
        assertEquals(emptyList<AuthIdentityFence?>(), r.results)
    }

    @Test fun `S7E5B2B1_H30 the same helper tapped again while its request is in flight keeps the original continuation`() {
        val r = Rig()
        r.run()
        var second = 0
        r.action.run { second += 1 }
        assertEquals("Busy: no second read or request", listOf("read", "rationale", "request", "mark"), r.os.log)
        r.os.capability = GRANTED
        r.lane.onRequestResult()
        assertEquals("the first continuation runs once", 1, r.granted)
        assertEquals(0, second)
        assertFalse(r.lane.busy)

        val explain = Rig().apply { os.rationale = true }
        explain.run()
        explain.action.run { second += 1 }
        assertEquals("the explanation stays", NotificationPermissionDialog.Explain, explain.action.dialog)
        explain.os.capability = GRANTED
        explain.action.onExplainContinue()
        assertEquals("the first continuation survives the Busy tap", 1, explain.granted)
        assertEquals(0, second)
    }

    @Test fun `S7E5B2B1_H31 a projection already granted does not stop a run from reading fresh`() {
        val r = Rig().apply { displayed.value = GRANTED; os.capability = GRANTED }
        assertFalse(r.action.needsAction)
        r.run()
        assertEquals(listOf("read"), r.os.log)
        assertEquals(listOf(A), r.already)
        assertEquals(1, r.granted)
        val stale = Rig().apply { displayed.value = GRANTED; os.capability = APP_DISABLED }
        stale.run()
        assertEquals("the fresh read wins over the projection", listOf("read", "settings:APP"), stale.os.log)
        assertEquals(emptyList<AuthIdentityFence>(), stale.already)
        assertEquals(0, stale.granted)
    }

    @Test fun `S7E5B2B1_H32 over the real monitor a result joins its in-flight evaluation and a settings result asks nothing`() {
        val scope = kotlinx.coroutines.test.TestScope(kotlinx.coroutines.test.StandardTestDispatcher())
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var starts = 0
        var capability = GRANTED
        val premium = MutableStateFlow(grantFor(A))
        val monitor = com.jay.fxi.service.NotificationCapabilityMonitor(
            read = { capability },
            reconcile = { starts += 1; gate.await() },
            premium = premium,
            liveIdentity = { A },
            scope = scope,
            admitted = { true },
            reportFailure = {}
        )
        try {
            monitor.onForeground()
            scope.testScheduler.runCurrent()
            assertEquals("the first foreground's evaluation is in flight", 1, starts)
            val env = NotificationPermissionEnvironment(
                admitted = { true },
                liveIdentity = { A },
                premium = premium,
                displayed = monitor.state,
                onRequestResult = monitor::onRequestResult,
                onAlreadyGranted = monitor::onAlreadyGranted
            )
            val lane = NotificationPermissionLane(orphanResult = { env.onRequestResult(null) })
            val os = Os()
            val action = NotificationPermissionAction(env, os, lane)
            var granted = 0
            action.run { granted += 1 }
            lane.onRequestResult()
            scope.testScheduler.runCurrent()
            assertEquals("the same observation joins: no second evaluation", 1, starts)
            assertEquals(1, granted)

            capability = APP_DISABLED
            val settingsOs = Os().apply { this.capability = APP_DISABLED }
            NotificationPermissionAction(env, settingsOs, lane).run()
            lane.onSettingsResult()
            scope.testScheduler.runCurrent()
            assertEquals("a settings result asks the monitor nothing", 1, starts)
            monitor.onForeground()
            scope.testScheduler.runCurrent()
            gate.complete(Unit)
            scope.testScheduler.runCurrent()
            assertEquals("the return's different observation runs once after the first", 2, starts)
            assertEquals(APP_DISABLED, monitor.state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test fun `S7E5B2B1_H33 a run started inside a granted continuation is not disturbed by the ended attempt`() {
        val r = Rig().apply { os.capability = GRANTED }
        var second = 0
        r.action.run {
            r.granted += 1
            r.action.run { second += 1 }
        }
        assertEquals(1, r.granted)
        assertEquals(1, second)
        assertFalse(r.lane.busy)
        r.action.onDialogDismiss()
        r.action.onExplainContinue()
        r.lane.onRequestResult()
        assertEquals("late callbacks of either attempt do nothing", listOf(1, 1), listOf(r.granted, second))
    }

    private companion object {
        val A = AuthIdentityFence("user-a", 1L)
        val B = AuthIdentityFence("user-b", 2L)

        fun grantFor(owner: AuthIdentityFence, state: PremiumAccessState = PremiumAccessState.PremiumConfirmed) =
            OwnedPremiumAccess(owner.uid, owner.authGeneration, state)
    }
}
