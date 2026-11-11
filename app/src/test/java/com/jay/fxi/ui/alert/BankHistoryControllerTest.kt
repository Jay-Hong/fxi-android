package com.jay.fxi.ui.alert

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.dto.AlertHistoryItem
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.DeletionAdmissionRevisions
import com.jay.fxi.data.repository.IssuerBankHistoryAccess
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.ui.settings.AccountDeletionServerStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 step (c) contract for the bank history controller (`R4c/S7/s7c_api_proposal.claude.r2.md` §2.2–2.3, §4
 * S7C_L02·D01·D02·N02): the session's access follows the route's published owner and tab at once, deletion publications and
 * issuer revisions recheck the shown sheet, and nothing reads on its own. The access is the production adapter over the
 * production use authority and the shared deletion store; the read is a fake that records each call and finishes on command.
 * The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BankHistoryControllerTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val bob = AuthIdentityFence("user-b", 1)
    private val ownerA = TopicDisplayOwner(alice, 3)
    private val ownerB = TopicDisplayOwner(bob, 4)
    private val token = TopicGrantToken(11)
    private val generic = BankHistoryBody.Failed(BANK_HISTORY_GENERIC_FAILURE, temporary = false)
    private val start = Instant.parse("2026-07-16T01:00:00Z")

    private class Call(val currency: String, val guard: () -> Boolean) {
        val answer = CompletableDeferred<AuthBoundResult<List<AlertHistoryItem>>>()
    }

    private class Reads : BankHistoryRead {
        val calls = mutableListOf<Call>()
        override suspend fun read(owner: AuthIdentityFence, currency: String, useAdmitted: () -> Boolean): AuthBoundResult<List<AlertHistoryItem>> =
            Call(currency, useAdmitted).also { calls += it }.answer.await()
    }

    private fun item(id: Int, currency: String) =
        AlertHistoryItem(id, id, "hana", currency, AlertCondition.ABOVE, 1400.5, 1401.2, Instant.fromEpochSeconds(start.epochSeconds - id * 60L))
    private fun success(items: List<AlertHistoryItem>) = AuthBoundResult({}, Result.success(items))

    /** Every input standing: identity, fence, displayed owner, issuer snapshot, release; the shared deletion store empty. */
    private inner class World(val test: TestScope) {
        var identity: AuthIdentityFence? = alice
        var fence: TopicSessionFence? = TopicSessionFence(alice, "epoch-1", token)
        var snapshot = TopicAccessSnapshot.INITIAL.copy(revision = 1L,
            facts = TopicAccessFacts.NONE.copy(token = token, tokenStanding = true, userBlocks = emptySet()))
        val shown = MutableStateFlow<Pair<TopicDisplayOwner?, FreeTab?>>(ownerA to FreeTab.USD)
        val deletions = DeletionAdmissionStore()
        val deletionRevisions = DeletionAdmissionRevisions()
        val accessRevisions = MutableStateFlow(0L)
        val reads = Reads()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val wiring = BankHistoryWiring(
            access = IssuerBankHistoryAccess(
                liveIdentity = { identity },
                currentAccessFence = { fence },
                displayOwner = { shown.value.first },
                uses = SnapshotTopicUseAuthority { snapshot },
                releaseAdmission = { true },
                protectedAdmission = GraphProtectedAdmission({ identity }, deletions)
            ),
            read = reads,
            accessRevisions = accessRevisions,
            deletionRevisions = deletionRevisions.revisions
        )

        /** The listening a consumer would give, driven by the test: [publish] tells every listener, as a publication does. */
        val listeners = mutableListOf<(com.jay.fxi.ui.premium.PremiumTopicScreenState) -> Unit>()
        fun controller(owner: TopicDisplayOwner = ownerA, tab: FreeTab = FreeTab.USD) =
            BankHistoryController(owner, tab, wiring, { shown.value }, { l ->
                listeners += l
                l(screen(shown.value.first, shown.value.second))
                val unregister: () -> Unit = { listeners.remove(l) }
                unregister
            }, scope)
        fun screen(owner: TopicDisplayOwner?, tab: FreeTab?) = com.jay.fxi.ui.premium.PremiumTopicScreenState(
            com.jay.fxi.ui.premium.PremiumTopicUiState.NONE.copy(owner = owner, selectedTab = tab), null, null)
        /** A consumer publication: the published value moves, then every listener is told, with nothing else in between. */
        fun publish(owner: TopicDisplayOwner?, tab: FreeTab?) {
            shown.value = owner to tab
            listeners.toList().forEach { it(screen(owner, tab)) }
        }
        fun settle() = test.runCurrent()
    }

    @Test fun S7C_L00_positiveControl_anOpenReadsOnceAndShowsItsCurrency() = runTest {
        val w = World(this)
        val c = w.controller()
        c.open()
        w.settle()
        assertEquals("one read, its currency", listOf("usd-krw"), w.reads.calls.map { it.currency })
        assertTrue("its guard stands", w.reads.calls.single().guard())
        w.reads.calls.single().answer.complete(success(listOf(item(1, "usd-krw"))))
        w.settle()
        assertEquals(BankHistoryBody.Loaded(listOf(item(1, "usd-krw")), refreshing = false), c.holder.currentState()?.body)
        c.close()
        w.scope.cancel()
    }

    /** S7C_L02: a published screen of another tab or owner refuses the session at once, before any recomposition. */
    @Test fun S7C_L02_anotherPublishedTabOrOwnerRefusesTheSessionAtOnce() = runTest {
        for ((label, moved) in listOf<Pair<String, Pair<TopicDisplayOwner?, FreeTab?>>>(
                "USD to JPY" to (ownerA to FreeTab.JPY), "USD to NEWS" to (ownerA to FreeTab.NEWS),
                "owner A to B" to (ownerB to FreeTab.USD), "no owner" to (null to FreeTab.USD))) {
            val w = World(this)
            val c = w.controller()
            c.open()
            w.settle()
            val first = w.reads.calls.single()
            assertTrue("premise($label): the guard stands", first.guard())
            w.shown.value = moved
            assertFalse("$label: the guard falls with the publication", first.guard())
            first.answer.complete(success(listOf(item(1, "usd-krw"))))
            w.settle()
            assertEquals("$label: the late answer is not applied", generic, c.holder.currentState()?.body)
            w.shown.value = ownerA to FreeTab.USD
            w.settle()
            assertEquals("$label: coming back reads nothing on its own", 1, w.reads.calls.size)
            c.holder.retry()
            w.settle()
            assertEquals("$label: the user's retry reads once more", 2, w.reads.calls.size)
            assertTrue("$label: under a standing guard", w.reads.calls.last().guard())
            c.close()
            w.scope.cancel()
        }
    }

    /**
     * S7C_L02c: a loss and a return published back to back, with no guard, recheck or render in between, still end the session
     * for good — loading or shown; the return opens nothing, only the user's next open does.
     */
    @Test fun S7C_L02c_aLossAndAReturnInBetweenStillEndTheSession() = runTest {
        for (state in listOf("loading", "shown")) for ((label, away) in listOf<Pair<String, Pair<TopicDisplayOwner?, FreeTab?>>>(
                "USD-JPY-USD" to (ownerA to FreeTab.JPY), "USD-NEWS-USD" to (ownerA to FreeTab.NEWS), "A-B-A" to (ownerB to FreeTab.USD))) {
            val w = World(this)
            val c = w.controller()
            c.open()
            w.settle()
            val first = w.reads.calls.single()
            if (state == "shown") {
                first.answer.complete(success(listOf(item(1, "usd-krw"))))
                w.settle()
                assertTrue("premise($state $label): shown", w.reads.calls.size == 1 && c.holder.state.value?.body is BankHistoryBody.Loaded)
            } else assertTrue("premise($state $label): loading under a standing guard", first.guard())
            w.publish(away.first, away.second)
            w.publish(ownerA, FreeTab.USD)
            assertNull("$state $label: the session ended at the loss", c.holder.state.value)
            assertFalse("$state $label: its guard is down for good", first.guard())
            if (state == "loading") first.answer.complete(success(listOf(item(1, "usd-krw"))))
            w.settle()
            assertNull("$state $label: nothing comes back", c.holder.currentState())
            assertEquals("$state $label: the return reads nothing", 1, w.reads.calls.size)
            c.open()
            w.settle()
            assertEquals("$state $label: only the user's next open reads", 2, w.reads.calls.size)
            assertTrue("$state $label: under a new standing guard", w.reads.calls.last().guard())
            c.close()
            assertTrue("$state $label: closing stops the listening", w.listeners.isEmpty())
            w.scope.cancel()
        }
    }

    /** S7C_L02b: a shown list is withdrawn by the render-time recheck as soon as the screen moves, before any recomposition. */
    @Test fun S7C_L02b_aShownListIsWithdrawnAtTheNextFreshRead() = runTest {
        val w = World(this)
        val c = w.controller()
        c.open()
        w.settle()
        w.reads.calls.single().answer.complete(success(listOf(item(1, "usd-krw"))))
        w.settle()
        assertTrue("premise: loaded", c.holder.currentState()?.body is BankHistoryBody.Loaded)
        w.shown.value = ownerA to FreeTab.EUR
        assertEquals(generic, c.holder.currentState()?.body)
        c.close()
        w.scope.cancel()
    }

    /**
     * S7C_D01: the real deletion stage, held at its snapshot capture while the history shows a list and a refresh is in flight.
     * Its begin refuses the refresh's guard at once and its publication rechecks the sheet: the list goes, the late answer does
     * not come back.
     */
    @Test fun S7C_D01_aDeletionBeginWithdrawsTheShownListAndTheRequestInFlight() = runTest {
        val w = World(this)
        val c = w.controller()
        c.open()
        w.settle()
        w.reads.calls.single().answer.complete(success(listOf(item(1, "usd-krw"))))
        w.settle()
        c.holder.refresh()
        w.settle()
        val refresh = w.reads.calls.last()
        assertTrue("premise: a refresh in flight under a standing guard", w.reads.calls.size == 2 && refresh.guard())
        val captured = CompletableDeferred<Unit>()
        var guardAtRequest: Boolean? = null
        val stage = AccountDeletionServerStage(
            captureSnapshot = { captured.await(); AuthSnapshot(it.uid, it.authGeneration, "credential") },
            // Right after the begin and its publication, in the same frame: before any recheck has run.
            deleteUser = { guardAtRequest = refresh.guard(); CompletableDeferred<AuthenticatedHttpResponse<Unit>>().await() },
            requireCurrent = {},
            deletions = w.deletions,
            onPublished = w.deletionRevisions::published
        )
        val deletion = w.scope.launch { stage.execute(alice) { Unit } }
        w.settle()
        assertTrue("premise: held before begin", refresh.guard())
        captured.complete(Unit)
        w.settle()
        assertTrue("premise: begun", w.deletions.blocks(alice.uid))
        assertEquals("the refresh's guard fell with the begin itself", false, guardAtRequest)
        assertFalse("and stays down", refresh.guard())
        assertEquals("the recheck withdrew the list", generic, c.holder.state.value?.body)
        refresh.answer.complete(success(listOf(item(2, "usd-krw"))))
        w.settle()
        assertEquals("the late answer does not come back", generic, c.holder.currentState()?.body)
        deletion.cancel()
        c.close()
        w.scope.cancel()
    }

    /** S7C_D02: a deletion begun before the first open, for this UID in any generation, refuses it; another UID's does not. */
    @Test fun S7C_D02_aDeletionBefore_orOfAnotherUid() = runTest {
        for ((label, blocked, refused) in listOf(
                Triple("this UID", alice, true), Triple("this UID, a new generation", alice.copy(authGeneration = 2), true),
                Triple("another UID", bob, false))) {
            val w = World(this)
            w.deletions.begin(blocked, "op-$label")
            w.deletionRevisions.published()
            val c = w.controller()
            c.open()
            w.settle()
            if (refused) {
                assertEquals("$label: no read", 0, w.reads.calls.size)
                assertEquals("$label: a generic failure", generic, c.holder.currentState()?.body)
            } else {
                assertEquals("$label: read once", 1, w.reads.calls.size)
                assertTrue("$label: under a standing guard", w.reads.calls.single().guard())
            }
            w.deletionRevisions.published()
            w.settle()
            assertEquals("$label: a publication reads nothing on its own", if (refused) 0 else 1, w.reads.calls.size)
            c.close()
            w.scope.cancel()
        }
    }

    /**
     * S7C_D03: the stage tells its publications right after each one, with the store already showing it: after the begin, and
     * after the server deletion on a plain 204 and on a 204 the session change refused. Other statuses tell the begin only.
     */
    @Test fun S7C_D03_theStageTellsEachPublicationRightAfterIt() = runTest {
        for ((label, answer) in listOf<Pair<String, suspend () -> AuthenticatedHttpResponse<Unit>>>(
                "204" to { AuthenticatedHttpResponse(204, Headers.headersOf(), Unit, null, byteArrayOf()) },
                "session-changed 204" to { throw com.jay.fxi.data.auth.AuthIdentityChangedException(statusCode = 204) },
                "500" to { AuthenticatedHttpResponse(500, Headers.headersOf(), Unit, null, byteArrayOf()) })) {
            val store = DeletionAdmissionStore()
            val seen = mutableListOf<List<com.jay.fxi.data.entitlements.DeletionAdmissionPhase>>()
            val stage = AccountDeletionServerStage(
                captureSnapshot = { AuthSnapshot(it.uid, it.authGeneration, "credential") },
                deleteUser = { answer() },
                requireCurrent = {},
                deletions = store,
                onPublished = { seen += store.records.map { it.phase } }
            )
            runCatching { stage.execute(alice) { Unit } }
            val requesting = com.jay.fxi.data.entitlements.DeletionAdmissionPhase.REQUESTING_SERVER
            val deleted = com.jay.fxi.data.entitlements.DeletionAdmissionPhase.SERVER_DELETED
            assertEquals(label, if (label == "500") listOf(listOf(requesting)) else listOf(listOf(requesting), listOf(deleted)), seen)
        }
    }

    /** S7C_N02: issuer and deletion revisions and a long time read nothing, whether the sheet is closed, loading or shown. */
    @Test fun S7C_N02_revisionsAndTimeReadNothing() = runTest {
        val w = World(this)
        val c = w.controller()
        fun nudge() { w.accessRevisions.value += 1; w.deletionRevisions.published(); testScheduler.advanceTimeBy(24 * 3_600_000L); w.settle() }
        nudge()
        assertEquals("closed", 0, w.reads.calls.size)
        c.open()
        w.settle()
        nudge()
        assertEquals("loading", 1, w.reads.calls.size)
        w.reads.calls.single().answer.complete(success(listOf(item(1, "usd-krw"))))
        w.settle()
        nudge()
        assertEquals("shown", 1, w.reads.calls.size)
        c.holder.close()
        nudge()
        assertEquals("closed again", 1, w.reads.calls.size)
        assertNull(c.holder.currentState())
        c.close()
        w.scope.cancel()
    }
}
