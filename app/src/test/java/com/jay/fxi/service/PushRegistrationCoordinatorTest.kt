package com.jay.fxi.service

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.service.PushRegistrationState.MAY_EXIST
import com.jay.fxi.service.PushRegistrationState.OWED
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class PushRegistrationCoordinatorTest {

    /** A spinning coordinator would outlive runTest's own timeout. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val a1 = AuthIdentityFence("a", 1)
    private val a2 = AuthIdentityFence("a", 2)
    private val b1 = AuthIdentityFence("b", 1)

    /** The production transitions over memory, with failures to order. Outlives a coordinator, as the file does a process. */
    private class Ledger : PushRegistrationLedger {
        var state = PushLedger.EMPTY
        var failEntries = false
        var failMarkOwed = false
        val failRecordOwed = mutableSetOf<String>()
        var failRecordMayExist = false
        var failComplete = false
        val completions = mutableListOf<Long>()
        var recordOwedGate: CompletableDeferred<Unit>? = null
        var afterRecordMayExist: () -> Unit = {}
        var reads = 0
        val failReads = mutableSetOf<Int>()

        override suspend fun entries(): List<PushLedgerEntry> {
            reads += 1
            if (failEntries || reads in failReads) throw IOException("read")
            return state.entries
        }

        override suspend fun recordMayExist(uid: String, token: String): PushLedgerEntry? {
            if (failRecordMayExist) throw IOException("write")
            return apply(PushLedgerTransitions.recordMayExist(state, uid, token)).also { afterRecordMayExist() }
        }

        override suspend fun markOwed(uid: String): List<PushLedgerEntry> {
            if (failMarkOwed) throw IOException("write")
            return apply(PushLedgerTransitions.markOwed(state, uid))
        }

        override suspend fun recordOwed(uid: String, token: String): PushLedgerEntry {
            recordOwedGate?.await()
            if (token in failRecordOwed) throw IOException("write")
            return apply(PushLedgerTransitions.recordOwed(state, uid, token))
        }

        override suspend fun complete(entry: PushLedgerEntry): Boolean {
            completions += entry.id
            if (failComplete) throw IOException("write")
            return apply(PushLedgerTransitions.complete(state, entry))
        }

        private fun <T> apply(result: PushLedgerTransitions.Result<T>): T {
            state = result.ledger
            return result.value
        }
    }

    private class Server : PushDeviceServer {
        val calls = mutableListOf<String>()
        val deletes = mutableMapOf<String, DeleteResult>()
        val deleteGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var accepts = true
        var postGate: CompletableDeferred<Unit>? = null
        var atPost: () -> Unit = {}
        var onDelete: (String) -> Unit = {}
        var sessionGate: CompletableDeferred<Unit>? = null
        var atSession: () -> Unit = {}
        var noSession = false

        override suspend fun session(owner: AuthIdentityFence): PushServerSession? {
            sessionGate?.await()
            atSession()
            if (noSession) return null
            return object : PushServerSession {
                override suspend fun register(token: String): Boolean {
                    calls += "POST ${owner.uid}${owner.authGeneration} $token"
                    atPost()
                    postGate?.await()
                    return accepts
                }

                override suspend fun unregister(token: String): DeleteResult {
                    calls += "DELETE ${owner.uid} $token"
                    deleteGates[token]?.await()
                    onDelete(token)
                    return deletes[token] ?: DeleteResult.DELETED
                }
            }
        }
    }

    private val ledger = Ledger()
    private val server = Server()
    private var current: AuthIdentityFence? = a1
    private var eligibleNow = true
    private var token: suspend () -> String? = { "t1" }
    private var legacyClears = 0
    private var legacyThrows = false

    private fun coordinator() = PushRegistrationCoordinator(
        ledger = ledger,
        server = server,
        currentFence = { current },
        eligible = { eligibleNow },
        deviceToken = { token() },
        clearLegacyLocalState = {
            legacyClears++
            if (legacyThrows) throw IllegalStateException("prefs")
        }
    )

    private fun owed(uid: String, token: String) {
        ledger.state = PushLedgerTransitions.recordOwed(ledger.state, uid, token).ledger
    }

    @Test
    fun aRegistrationRecordsItsTargetBeforeThePost() = runTest {
        var atPost: List<PushLedgerEntry>? = null
        server.atPost = { atPost = ledger.state.entries }

        assertEquals(RegisterOutcome.REGISTERED, coordinator().register(a1))

        assertEquals(listOf(PushLedgerEntry(1, "a", "t1", MAY_EXIST)), atPost)
        assertEquals(listOf("POST a1 t1"), server.calls)
    }

    @Test
    fun aHoldRefusesARegistrationAndItsPost() = runTest {
        val coordinator = coordinator()
        coordinator.register(a1)
        server.calls.clear()
        server.deleteGates["t1"] = CompletableDeferred()
        val signOut = launch { coordinator.unregister(a1) }
        runCurrent()

        assertEquals(RegisterOutcome.HELD, coordinator.register(a1))

        server.deleteGates.getValue("t1").complete(Unit)
        signOut.join()
        assertEquals(listOf("DELETE a t1"), server.calls)
    }

    @Test
    fun aSignOutWaitsForAnAdmittedPostThenDeletesIt() = runTest {
        val coordinator = coordinator()
        server.postGate = CompletableDeferred()
        val registration = async { coordinator.register(a1) }
        runCurrent()
        val signOut = launch { coordinator.unregister(a1) }
        runCurrent()
        assertEquals("POST 가 끝나기 전에 DELETE 했다", listOf("POST a1 t1"), server.calls)

        server.postGate!!.complete(Unit)
        signOut.join()

        assertEquals(RegisterOutcome.REGISTERED, registration.await())
        assertEquals(listOf("POST a1 t1", "DELETE a t1"), server.calls)
        assertEquals(emptyList<PushLedgerEntry>(), ledger.state.entries)
        assertEquals(1, legacyClears)
    }

    @Test
    fun aSignOutBeforeAdmissionStopsThePost() = runTest {
        val coordinator = coordinator()
        val arriving = CompletableDeferred<String?>()
        var fetches = 0
        token = { if (fetches++ == 0) arriving.await() else "t1" }
        val registration = async { coordinator.register(a1) }
        runCurrent()

        coordinator.unregister(a1)
        arriving.complete("t1")

        assertEquals(RegisterOutcome.HELD, registration.await())
        assertEquals("해제가 끝난 뒤 등록이 POST 했다", listOf("DELETE a t1"), server.calls)
    }

    @Test
    fun aDeadlineWhileThePostIsOutKeepsTheTargetOwed() = runTest {
        val coordinator = coordinator()
        server.postGate = CompletableDeferred()
        val registration = async { coordinator.register(a1) }
        runCurrent()
        val signOut = async { withTimeoutOrNull(10_000) { coordinator.unregister(a1) } }
        advanceTimeBy(10_001)
        runCurrent()

        assertNull(signOut.await())
        assertEquals(1, legacyClears)
        server.postGate!!.complete(Unit)
        assertEquals(RegisterOutcome.REGISTERED, registration.await())
        assertEquals(
            "결과를 모르는 등록을 해제된 것으로 지웠다",
            listOf(PushLedgerEntry(1, "a", "t1", OWED)), ledger.state.entries
        )

        // The hold dropped, and the owed DELETE comes before the key is registered again.
        server.postGate = null
        assertEquals(RegisterOutcome.REGISTERED, coordinator.register(a1))
        assertEquals(listOf("POST a1 t1", "DELETE a t1", "POST a1 t1"), server.calls)
    }

    @Test
    fun nestedHoldsKeepRegistrationHeldUntilTheLastOneEnds() = runTest {
        val coordinator = coordinator()
        coordinator.register(a1)
        val first = CompletableDeferred<Unit>()
        server.deleteGates["t1"] = first
        val one = launch { coordinator.unregister(a1) }
        runCurrent()
        val other = launch { coordinator.unregister(a1) }
        runCurrent()
        val second = CompletableDeferred<Unit>()
        server.deleteGates["t1"] = second

        first.complete(Unit)
        one.join()
        runCurrent()
        assertEquals(RegisterOutcome.HELD, coordinator.register(a1))

        second.complete(Unit)
        other.join()
        assertEquals(RegisterOutcome.REGISTERED, coordinator.register(a1))
    }

    /**
     * Whatever changes after the first check but before the POST stops it: the last check is the
     * admission. Each case holds the registration inside the rotation DELETE of an older key.
     */
    private suspend fun TestScope.changedBeforeAdmission(
        change: TestScope.(PushRegistrationCoordinator) -> Unit
    ): RegisterOutcome {
        val coordinator = coordinator()
        coordinator.register(a1)
        current = a2
        server.deleteGates["t1"] = CompletableDeferred()
        val registration = async { coordinator.register(a2, knownToken = "t2") }
        runCurrent()
        change(coordinator)
        runCurrent()
        server.deleteGates.getValue("t1").complete(Unit)
        advanceUntilIdle()
        assertTrue("변화 뒤에도 POST 했다", server.calls.none { it == "POST a2 t2" })
        return registration.await()
    }

    @Test
    fun aSignOutRaisedBeforeAdmissionStopsThePost() = runTest {
        assertEquals(RegisterOutcome.HELD, changedBeforeAdmission { launch { it.unregister(a2) } })
    }

    @Test
    fun anEligibilityLostBeforeAdmissionStopsThePost() = runTest {
        assertEquals(RegisterOutcome.NOT_ELIGIBLE, changedBeforeAdmission { eligibleNow = false })
    }

    @Test
    fun aSessionThatMovedBeforeAdmissionStopsThePost() = runTest {
        assertEquals(RegisterOutcome.STALE, changedBeforeAdmission { current = null })
    }

    /** The credential is the last wait before a POST, so admission is checked after it. */
    @Test
    fun aSignOutRaisedWhileTheCredentialIsFetchedStopsThePost() = runTest {
        val coordinator = coordinator()
        server.sessionGate = CompletableDeferred()
        val registration = async { coordinator.register(a1) }
        runCurrent()
        val signOut = launch { coordinator.unregister(a1) }
        runCurrent()

        server.sessionGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(RegisterOutcome.HELD, registration.await())
        assertTrue(server.calls.none { it.startsWith("POST") })
        signOut.join()
    }

    @Test
    fun anEligibilityLostWhileTheCredentialIsFetchedStopsThePost() = runTest {
        val coordinator = coordinator()
        server.sessionGate = CompletableDeferred()
        val registration = async { coordinator.register(a1) }
        runCurrent()

        eligibleNow = false
        server.sessionGate!!.complete(Unit)

        assertEquals(RegisterOutcome.NOT_ELIGIBLE, registration.await())
        assertEquals(emptyList<String>(), server.calls)
    }

    @Test
    fun aCallerCancelledAsItsCredentialArrivesSendsNeitherPostNorDelete() = runTest {
        val coordinator = coordinator()
        lateinit var registration: Job
        server.atSession = { registration.cancel() }
        registration = launch { coordinator.register(a1) }
        advanceUntilIdle()
        assertTrue(registration.isCancelled)

        lateinit var signOut: Job
        server.atSession = { signOut.cancel() }
        signOut = launch { coordinator.unregister(a1) }
        advanceUntilIdle()

        assertTrue(signOut.isCancelled)
        assertEquals(emptyList<String>(), server.calls)
        assertEquals(OWED, ledger.state.entryFor("a", "t1")?.state)
    }

    @Test
    fun noCredentialMeansNoPostAndTheTargetStays() = runTest {
        val coordinator = coordinator()
        server.noSession = true

        assertEquals(RegisterOutcome.POST_FAILED, coordinator.register(a1))
        coordinator.unregister(a1)

        assertEquals(emptyList<String>(), server.calls)
        assertEquals(listOf(PushLedgerEntry(1, "a", "t1", OWED)), ledger.state.entries)
    }

    @Test
    fun aLaterSignOutDeletesWhatAnEarlierOneOnlyRemembered() = runTest {
        val coordinator = coordinator()
        ledger.failRecordOwed += "u1"
        server.deletes["u1"] = DeleteResult.FAILED
        token = { "u1" }
        coordinator.unregister(a1)
        ledger.failRecordOwed.clear()
        server.deletes.remove("u1")
        token = { null }
        server.calls.clear()

        coordinator.unregister(a1)

        assertEquals(listOf("DELETE a u1"), server.calls)
        assertEquals(RegisterOutcome.REGISTERED, coordinator.register(a1, knownToken = "u1"))
    }

    @Test
    fun anEarlierSessionsSameKeyIsReplacedWithoutADelete() = runTest {
        val coordinator = coordinator()
        coordinator.register(a1)
        current = a2 // signed out and back outside the app: same uid, new generation

        assertEquals(RegisterOutcome.REGISTERED, coordinator.register(a2))

        assertEquals(listOf("POST a1 t1", "POST a2 t1"), server.calls)
        assertEquals(listOf(PushLedgerEntry(2, "a", "t1", MAY_EXIST)), ledger.state.entries)
    }

    @Test
    fun anotherKeysFailedDeleteNeverBlocksThePostNowNextTimeOrAfterARestart() = runTest {
        coordinator().register(a1)
        current = a2
        token = { "t2" }
        server.deletes["t1"] = DeleteResult.FAILED
        val coordinator = coordinator()

        assertEquals(RegisterOutcome.REGISTERED, coordinator.register(a2))
        assertEquals(RegisterOutcome.REGISTERED, coordinator.register(a2))
        assertEquals(RegisterOutcome.REGISTERED, coordinator().register(a2))

        assertEquals(
            listOf(
                "POST a1 t1",
                "DELETE a t1", "POST a2 t2",
                "DELETE a t1", "POST a2 t2",
                "DELETE a t1", "POST a2 t2"
            ),
            server.calls
        )
        assertEquals(PushLedgerEntry(1, "a", "t1", OWED), ledger.state.entryFor("a", "t1"))
    }

    @Test
    fun aTokenChangeDuringAHoldLeavesTheTargetAlone() = runTest {
        val coordinator = coordinator()
        coordinator.register(a1)
        server.deleteGates["t1"] = CompletableDeferred()
        val signOut = launch { coordinator.unregister(a1) }
        runCurrent()

        assertEquals(RegisterOutcome.HELD, coordinator.register(a1, knownToken = "t2"))
        assertEquals(listOf(PushLedgerEntry(1, "a", "t1", OWED)), ledger.state.entries)

        server.deleteGates.getValue("t1").complete(Unit)
        signOut.join()
    }

    @Test
    fun aTargetThatCannotBeRecordedOrALedgerThatCannotBeReadMeansNoPost() = runTest {
        ledger.failRecordMayExist = true
        assertEquals(RegisterOutcome.LEDGER_UNAVAILABLE, coordinator().register(a1))

        ledger.failRecordMayExist = false
        ledger.failEntries = true
        assertEquals(RegisterOutcome.LEDGER_UNAVAILABLE, coordinator().register(a1))

        assertEquals(emptyList<String>(), server.calls)
    }

    @Test
    fun aRestartedGateStillFindsTheTargetToDelete() = runTest {
        coordinator().register(a1)

        coordinator().unregister(a1)

        assertEquals(listOf("POST a1 t1", "DELETE a t1"), server.calls)
        assertEquals(emptyList<PushLedgerEntry>(), ledger.state.entries)
    }

    /** The current token's record lands after the sign-out deleted that key, and recreates it. */
    @Test
    fun aCurrentTokenRecordedAfterItsDeleteIsDeletedOnceMore() = runTest {
        coordinator().register(a1)
        server.calls.clear()
        val coordinator = coordinator()
        server.deleteGates["t1"] = CompletableDeferred()
        ledger.recordOwedGate = CompletableDeferred()
        val signOut = launch { coordinator.unregister(a1) }
        runCurrent() // the DELETE is out, and the token's record is waiting
        server.deleteGates.getValue("t1").complete(Unit)
        runCurrent()

        ledger.recordOwedGate!!.complete(Unit)
        signOut.join()

        assertEquals(listOf("DELETE a t1", "DELETE a t1"), server.calls)
        assertEquals("다시 생긴 의무가 남았다", emptyList<PushLedgerEntry>(), ledger.state.entries)
    }

    @Test
    fun anotherUidsTargetIsKeptAndNeverBlocksAPost() = runTest {
        val coordinator = coordinator()
        coordinator.register(a1)
        current = null // signed out outside the app: nothing tore the registration down
        current = b1

        assertEquals(RegisterOutcome.REGISTERED, coordinator.register(b1))

        assertEquals(listOf("POST a1 t1", "POST b1 t1"), server.calls)
        assertEquals(
            listOf(PushLedgerEntry(1, "a", "t1", MAY_EXIST), PushLedgerEntry(2, "b", "t1", MAY_EXIST)),
            ledger.state.entries
        )
    }

    @Test
    fun aCompletionThatFailsIsNotAResolution() = runTest {
        owed("a", "t1")
        ledger.failComplete = true

        assertEquals(RegisterOutcome.SAME_KEY_OWED, coordinator().register(a1))

        assertEquals(listOf("DELETE a t1"), server.calls)
    }

    @Test
    fun anAbsentAnswerResolvesTheOwedDelete() = runTest {
        owed("a", "t1")
        server.deletes["t1"] = DeleteResult.ABSENT_FOR_UID

        assertEquals(RegisterOutcome.REGISTERED, coordinator().register(a1))

        assertEquals(listOf("DELETE a t1", "POST a1 t1"), server.calls)
    }

    @Test
    fun noGrantStillDeletesWhatIsOwed() = runTest {
        owed("a", "t1")
        eligibleNow = false

        assertEquals(RegisterOutcome.NOT_ELIGIBLE, coordinator().register(a1))

        assertEquals(listOf("DELETE a t1"), server.calls)
        assertEquals(emptyList<PushLedgerEntry>(), ledger.state.entries)
    }

    @Test
    fun aReplacementWhosePostFailsIsNotACompletion() = runTest {
        val coordinator = coordinator()
        coordinator.register(a1)
        current = a2
        server.accepts = false

        assertEquals(RegisterOutcome.POST_FAILED, coordinator.register(a2))

        assertEquals(listOf(PushLedgerEntry(2, "a", "t1", MAY_EXIST)), ledger.state.entries)
        assertEquals(listOf("POST a1 t1", "POST a2 t1"), server.calls)
    }

    /** The unlanded mark blocks the same key while this process lives. */
    @Test
    fun aMarkThatDidNotLandBlocksTheSameKeyUntilItsDeleteResolves() = runTest {
        val coordinator = coordinator()
        coordinator.register(a1)
        ledger.failMarkOwed = true
        ledger.failRecordOwed += "t1"
        server.deletes["t1"] = DeleteResult.FAILED
        coordinator.unregister(a1)
        assertEquals(
            "표시가 착지하지 않은 해제가 알려진 대상을 DELETE 하지 않았다",
            listOf("POST a1 t1", "DELETE a t1"), server.calls
        )
        current = a2

        assertEquals(RegisterOutcome.SAME_KEY_OWED, coordinator.register(a2))
        assertTrue("같은 키를 DELETE 없이 다시 등록했다", server.calls.none { it == "POST a2 t1" })

        server.deletes.remove("t1")
        ledger.failRecordOwed.clear()
        assertEquals(RegisterOutcome.REGISTERED, coordinator.register(a2))
        assertEquals("POST a2 t1", server.calls.last())
        assertEquals(listOf(PushLedgerEntry(2, "a", "t1", MAY_EXIST)), ledger.state.entries)
    }

    /**
     * Cut off inside its DELETE, a sign-out whose mark did not land leaves the entry covered by
     * the mark alone. The mark has to outlast a pass that could not complete that entry.
     */
    @Test
    fun aMarkStaysWhileAnEntryItCoversIsStillThere() = runTest {
        val coordinator = coordinator()
        coordinator.register(a1)
        ledger.failMarkOwed = true
        token = { null }
        server.deleteGates["t1"] = CompletableDeferred()
        val signOut = async { withTimeoutOrNull(10_000) { coordinator.unregister(a1) } }
        advanceTimeBy(10_001)
        runCurrent()
        assertNull(signOut.await())
        server.deleteGates.clear()
        current = a2
        ledger.failComplete = true

        assertEquals(RegisterOutcome.SAME_KEY_OWED, coordinator.register(a2, knownToken = "t1"))
        assertTrue("표식이 덮는 항목이 남았는데 같은 키를 다시 등록했다", server.calls.none { it == "POST a2 t1" })
    }

    /** A completion that fails is not retried within the sign-out; the entry waits, owed, for the next pass. */
    @Test
    fun aCompletionThatFailsInASignOutKeepsTheEntryOwed() = runTest {
        val coordinator = coordinator()
        coordinator.register(a1)
        server.calls.clear()
        ledger.failComplete = true

        coordinator.unregister(a1)

        assertEquals(listOf("DELETE a t1"), server.calls)
        assertEquals(listOf(PushLedgerEntry(1, "a", "t1", OWED)), ledger.state.entries)
    }

    /** The disk has to carry what the unlanded mark only held in memory, or a restart loses it. */
    @Test
    fun aDeleteThatFailsAfterAnUnlandedMarkLeavesTheEntryOwedForTheNextProcess() = runTest {
        coordinator().register(a1)
        ledger.failMarkOwed = true
        server.deletes["t1"] = DeleteResult.FAILED
        token = { null }
        coordinator().unregister(a1)
        assertEquals(listOf(PushLedgerEntry(1, "a", "t1", OWED)), ledger.state.entries)
        server.deletes.remove("t1")
        server.calls.clear()
        token = { "t1" }
        current = a2

        assertEquals(RegisterOutcome.REGISTERED, coordinator().register(a2))

        assertEquals(listOf("DELETE a t1", "POST a2 t1"), server.calls)
    }

    /** A remembered key that is also in the ledger resolves only when that entry completes. */
    @Test
    fun aRememberedTokenWithALedgerEntryResolvesOnlyByThatEntrysCompletion() = runTest {
        val coordinator = coordinator()
        coordinator.register(a1)
        ledger.failRecordOwed += "t1"
        server.deletes["t1"] = DeleteResult.FAILED
        coordinator.register(a1, knownToken = "t2") // retiring t1 fails: MAY_EXIST on disk, remembered in memory
        server.deletes.remove("t1")
        ledger.failComplete = true
        server.calls.clear()

        assertEquals(RegisterOutcome.SAME_KEY_OWED, coordinator.register(a1, knownToken = "t1"))
        assertEquals(listOf("DELETE a t1"), server.calls)
    }

    /** Retiring a key whose owed record does not land still needs its entry's completion to let go of it. */
    @Test
    fun aRetiredKeyWhoseRecordFailsResolvesOnlyByItsEntrysCompletion() = runTest {
        val coordinator = coordinator()
        coordinator.register(a1)
        ledger.failRecordOwed += "t1"
        ledger.failComplete = true

        assertEquals(RegisterOutcome.REGISTERED, coordinator.register(a1, knownToken = "t2"))
        assertEquals("회전 정리가 t1 항목의 complete 를 시도하지 않았다", listOf(1L), ledger.completions)
        server.calls.clear()

        assertEquals(RegisterOutcome.SAME_KEY_OWED, coordinator.register(a1, knownToken = "t1"))
        assertTrue(server.calls.none { it == "POST a1 t1" })
    }

    /** Storage recovering is enough to make a remembered token durable, even while its DELETE fails. */
    @Test
    fun aRememberedTokenIsRecordedOwedOnceStorageRecovers() = runTest {
        val coordinator = coordinator()
        ledger.failRecordOwed += "u1"
        server.deletes["u1"] = DeleteResult.FAILED
        token = { "u1" }
        coordinator.unregister(a1)
        ledger.failRecordOwed.clear()
        current = a2

        assertEquals(RegisterOutcome.REGISTERED, coordinator.register(a2, knownToken = "w1"))
        assertEquals(PushLedgerEntry(1, "a", "u1", OWED), ledger.state.entryFor("a", "u1"))
        assertEquals("다음 프로세스가 u1 을 잃었다", RegisterOutcome.SAME_KEY_OWED, coordinator().register(a2, knownToken = "u1"))
    }

    /** Only a ledger read that succeeds can show a remembered key has no entry; a failed one decides nothing. */
    @Test
    fun aFailedReadDecidesNothingForARememberedToken() = runTest {
        val coordinator = coordinator()
        coordinator.register(a1)
        ledger.failRecordOwed += "t1"
        server.deletes["t1"] = DeleteResult.FAILED
        coordinator.register(a1, knownToken = "t2") // t1: MAY_EXIST on disk, remembered in memory
        server.deletes.remove("t1")
        ledger.failComplete = true
        server.calls.clear()
        ledger.reads = 0
        ledger.failReads += 2 // the remembered token's own read, after its record failed again

        assertEquals(RegisterOutcome.SAME_KEY_OWED, coordinator.register(a1, knownToken = "t1"))
        assertTrue("읽기 실패를 항목 없음으로 보고 메모리만으로 해소했다", server.calls.none { it == "POST a1 t1" })
    }

    @Test
    fun aCallerAlreadyCancelledRecordsNothing() = runTest {
        val coordinator = coordinator()

        val registration = launch {
            coroutineContext[Job]!!.cancel()
            coordinator.register(a1)
        }
        registration.join()

        assertEquals(emptyList<PushLedgerEntry>(), ledger.state.entries)
        assertEquals(emptyList<String>(), server.calls)
    }

    /** A cancelled caller sends nothing new, even when the call it was in returns normally. */
    @Test
    fun aCallerCancelledWhileItsRecordReturnsSendsNoPost() = runTest {
        val coordinator = coordinator()
        lateinit var registration: Job
        ledger.afterRecordMayExist = { registration.cancel() }
        registration = launch { coordinator.register(a1) }
        advanceUntilIdle()

        assertTrue(registration.isCancelled)
        assertEquals(emptyList<String>(), server.calls)
    }

    @Test
    fun aSignOutCancelledBetweenDeletesStartsNoFurtherDelete() = runTest {
        val coordinator = coordinator()
        // Two targets, deleted in id order: t1, then t2.
        ledger.state = PushLedgerTransitions.recordMayExist(PushLedger.EMPTY, "a", "t1").ledger
            .let { PushLedgerTransitions.recordMayExist(it, "a", "t2").ledger }
        token = { null }
        lateinit var signOut: Job
        server.onDelete = { if (it == "t1") signOut.cancel() }
        signOut = launch { coordinator.unregister(a1) }
        advanceUntilIdle()

        assertEquals(listOf("DELETE a t1"), server.calls)
        assertEquals(OWED, ledger.state.entryFor("a", "t2")?.state)
        assertEquals(1, legacyClears)
        server.onDelete = {}
        assertEquals(RegisterOutcome.REGISTERED, coordinator.register(a1, knownToken = "t9"))
    }

    /**
     * The agreed limit: after a restart nothing tells an unlanded mark apart, and the same key
     * is replaced without the earlier DELETE.
     */
    @Test
    fun afterARestartAnUnlandedMarkIsGoneAndTheSameKeyIsReplaced() = runTest {
        coordinator().register(a1)
        ledger.failMarkOwed = true
        ledger.failRecordOwed += "t1"
        server.deletes["t1"] = DeleteResult.FAILED
        coordinator().unregister(a1)
        server.calls.clear()
        current = a2

        assertEquals(RegisterOutcome.REGISTERED, coordinator().register(a2))

        assertEquals(listOf("POST a2 t1"), server.calls)
    }

    @Test
    fun knownTargetsAreDeletedWhileTheCurrentTokenIsStillComing() = runTest {
        val coordinator = coordinator()
        coordinator.register(a1)
        server.calls.clear()
        token = { CompletableDeferred<String?>().await() }
        val signOut = async { withTimeoutOrNull(10_000) { coordinator.unregister(a1) } }
        runCurrent()

        assertEquals(listOf("DELETE a t1"), server.calls)

        advanceTimeBy(10_001)
        runCurrent()
        assertNull(signOut.await())
        assertEquals(1, legacyClears)
        assertEquals(RegisterOutcome.REGISTERED, coordinator.register(a1, knownToken = "t9"))
    }

    @Test
    fun aCurrentTokenThatCannotBeRecordedIsKeptInMemory() = runTest {
        val coordinator = coordinator()
        ledger.failRecordOwed += "u1"
        server.deletes["u1"] = DeleteResult.FAILED
        token = { "u1" }
        coordinator.unregister(a1)
        current = a2
        token = { "w1" } // FCM moved on; u1 will not come back from it

        assertEquals(RegisterOutcome.REGISTERED, coordinator.register(a2))
        assertEquals(RegisterOutcome.SAME_KEY_OWED, coordinator.register(a2, knownToken = "u1"))
        assertNull(ledger.state.entryFor("a", "u1"))
        assertEquals(3, server.calls.count { it == "DELETE a u1" })
    }

    @Test
    fun aThrowingLegacyCleanupStillReleasesTheHold() = runTest {
        val coordinator = coordinator()
        legacyThrows = true

        val thrown = runCatching { coordinator.unregister(a1) }.exceptionOrNull()

        assertEquals("prefs", thrown?.message)
        legacyThrows = false
        assertEquals(RegisterOutcome.REGISTERED, coordinator.register(a1))
    }

    /**
     * A resolution reads the failed mark before its work, so a sign-out whose mark fails while it
     * runs keeps its own. The mark only shows once that sign-out has ended its session — cut off
     * here before its DELETE answers, so neither the ledger nor memory holds the key otherwise.
     */
    @Test
    fun anOlderResolutionKeepsTheMarkOfASignOutThatFailedMeanwhile() = runTest {
        val coordinator = coordinator()
        coordinator.register(a1)
        ledger.failMarkOwed = true
        ledger.failRecordOwed += "t1"
        server.deletes["t1"] = DeleteResult.FAILED
        coordinator.unregister(a1)
        current = a2
        coordinator.register(a2, knownToken = "t2")

        server.deletes.remove("t1")
        ledger.failRecordOwed.clear()
        server.deleteGates["t1"] = CompletableDeferred()
        val resolving = async { coordinator.register(a2, knownToken = "t2") }
        runCurrent()
        token = { "t9" }
        server.deleteGates["t2"] = CompletableDeferred()
        val signOut = async { withTimeoutOrNull(10_000) { coordinator.unregister(a2) } }
        runCurrent()
        server.deleteGates.getValue("t1").complete(Unit)
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertNull(signOut.await())
        assertEquals(RegisterOutcome.HELD, resolving.await())
        server.calls.clear()
        server.deleteGates.clear()

        assertEquals(RegisterOutcome.REGISTERED, coordinator.register(a2, knownToken = "t2"))

        assertEquals("새 표식이 지워져 같은 키를 DELETE 없이 등록했다", "DELETE a t2", server.calls.first())
        assertEquals("POST a2 t2", server.calls.last())
    }
}
