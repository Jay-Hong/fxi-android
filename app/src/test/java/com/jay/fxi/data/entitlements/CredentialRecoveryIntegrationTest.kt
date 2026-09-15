package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.remote.AuthRequestTag
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.di.NetworkModule
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * S1 recovery signal §6: the real token provider, transport, API client, entitlements source, issuer and binder, wired as production
 * wires them, with only Firebase and the HTTP service faked.
 *
 * An owed premium re-check is stopped by an authentication answer, a credential the same session acquires for somebody else's
 * request recovers, and the re-check runs once more with that credential — never sooner than its floor, and with no new premium
 * approval before its own fresh answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CredentialRecoveryIntegrationTest {

    private val processJob = SupervisorJob()
    private val fence = AuthIdentityFence(OWNER, 1L)

    private class Tokens : AuthTokenSource {
        var current = "token-1"
        /** What a forced refresh hands out; null keeps [current]. */
        var forced: String? = null
        /** A forced refresh fails, as the SDK's refresh does when it cannot reach Firebase. */
        var forcedFails = false
        var forceRefreshCount = 0

        override fun currentIdentity() = AuthIdentity(OWNER, 1L)

        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            if (forceRefresh) {
                forceRefreshCount += 1
                if (forcedFails) throw AuthUnavailableException("Firebase ID token acquisition failed")
                forced?.let { current = it }
            }
            return current
        }
    }

    /** One `/api/entitlements` send: the bearer it carried and whether it asked for a fresh premium answer. */
    private data class Send(val token: String, val freshPremium: Boolean)

    private class Service : AuthenticatedApiService by unused() {
        val sends = mutableListOf<Send>()
        var answer: (String) -> Response<ResponseBody> = { active() }

        override suspend fun getEntitlements(auth: AuthRequestTag, freshPremium: Boolean?): Response<ResponseBody> {
            sends += Send(auth.snapshot.token, freshPremium == true)
            return answer(auth.snapshot.token)
        }
    }

    private class Store : AccessEpochStore {
        private var n = 0
        private val ids = EpochIdGenerator { "epoch-${n++}" }
        var record = AccessEpochRecord()

        override suspend fun load() = record
        override suspend fun bindOwner(uid: String) = AccessEpochTransitions.bindOwner(record, uid, ids).also { record = it }
        override suspend fun signOut() = AccessEpochTransitions.signOut(record, ids).also { record = it }
        override suspend fun retireUnverifiedStart() =
            AccessEpochTransitions.retireUnverifiedStart(record, ids).also { record = it }
        override suspend fun beginSignOut(uid: String) = AccessEpochTransitions.beginSignOut(record, uid).also { record = it }
        override suspend fun beginRotation(rotateUser: Boolean, rotateKrx: Boolean) =
            AccessEpochTransitions.rotate(record, rotateUser, rotateKrx, ids).also { record = it }
        override suspend fun completePurges(completed: Collection<PendingPurge>) =
            AccessEpochTransitions.completePurges(record, completed).also { record = it }
        override suspend fun journalRetired(obligation: LossObligation) =
            AccessEpochTransitions.journalRetired(record, obligation).also { record = it }
        override suspend fun markMayContainData(premium: Boolean, krx: Boolean) =
            AccessEpochTransitions.markMayContainData(record, premium, krx).also { record = it }
    }

    private object Purger : UserScopePurger, CapabilityScopePurger {
        override suspend fun purgeUserScope(namespace: PurgeNamespace) = PurgeResult.Completed
        override suspend fun purgeCapabilityScope(namespace: PurgeNamespace) = PurgeResult.Completed
    }

    private class Wired(
        val tokens: Tokens,
        val service: Service,
        val provider: AuthTokenProvider,
        val coordinator: PremiumAccessCoordinator
    )

    /** Bound through the binder and confirmed premium by the binding's own query. */
    private fun TestScope.wired(): Wired {
        val scope = CoroutineScope(processJob + StandardTestDispatcher(testScheduler))
        val orders = AccessOrderSequence()
        val tokens = Tokens()
        val service = Service()
        val provider = AuthTokenProvider(tokens, scope, orders)
        val api = AuthenticatedApiClient(service, AuthenticatedTransport(provider, admitted = { true }), NetworkModule.provideWireJson())
        val coordinator = PremiumAccessCoordinator(
            source = AuthenticatedEntitlementsSource(api),
            store = Store(),
            userPurger = Purger,
            capabilityPurger = Purger,
            scope = scope,
            clock = { testScheduler.currentTime },
            jitter = ProbeJitter.None,
            liveFence = provider::currentIdentityFence,
            orders = orders
        )
        AuthAccessBinder(coordinator, scope, AuthFenceStream { it(fence) }, provider, recoverAutomatically = false).start()
        runCurrent()
        assertEquals(PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)
        assertEquals(listOf(Send("token-1", freshPremium = true)), service.sends)
        return Wired(tokens, service, provider, coordinator)
    }

    /** A premium re-check owed behind a 30-second `Retry-After`, whose run is answered 401 and stops re-queries. */
    private suspend fun TestScope.stoppedByA401(w: Wired) {
        w.service.answer = { pending(retryAfterSeconds = 30) }
        w.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        runCurrent()
        w.service.answer = { unauthorized() }
        advanceTimeBy(30_010)
        runCurrent()
        val stopped = w.coordinator.recheckDiagnostics()
        assertTrue("the 401 stopped re-queries", stopped.authStopped)
        assertEquals(RefreshIntent.FORCE_PREMIUM, stopped.owedIntent)
        val sends = w.service.sends.size
        advanceTimeBy(SETTLE)
        runCurrent()
        assertEquals("stopped: nothing more was sent", sends, w.service.sends.size)
    }

    private fun integrationTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            processJob.cancel()
        }
    }

    /** Recovers through another consumer's lookup and checks the owed re-check runs once with the new credential. */
    private suspend fun TestScope.recoversWith(w: Wired, token: String) {
        val snapshot = w.coordinator.accessSnapshot
        val state = w.coordinator.state.value
        val sends = w.service.sends.size
        w.service.answer = { active() }

        assertEquals(token, w.provider.currentSnapshot().token)
        runCurrent()

        assertFalse("the recovery did not resume re-queries", w.coordinator.recheckDiagnostics().authStopped)
        assertSame("the recovery itself published access", snapshot, w.coordinator.accessSnapshot)
        assertEquals(state, w.coordinator.state.value)
        advanceTimeBy(SETTLE)
        runCurrent()
        assertEquals(listOf(Send(token, freshPremium = true)), w.service.sends.drop(sends))
        assertNull(w.coordinator.recheckDiagnostics().owedIntent)
    }

    @Test
    fun aFirst401ReturnedBecauseTheRefreshWasUnusable_stops_andARecoveredCredentialRunsTheOwedRecheckOnce() = integrationTest {
        val w = wired()
        // The forced refresh hands the rejected token back: Unusable, so the first 401 is the answer.
        stoppedByA401(w)
        assertEquals(1, w.tokens.forceRefreshCount)
        assertEquals(listOf("token-1", "token-1", "token-1"), w.service.sends.map { it.token })

        w.tokens.current = "token-2"
        recoversWith(w, "token-2")
        assertEquals(1, w.tokens.forceRefreshCount)
    }

    @Test
    fun aFirst401ReturnedBecauseTheRefreshFailed_stops_andARecoveredCredentialRunsTheOwedRecheckOnce() = integrationTest {
        val w = wired()
        w.tokens.forcedFails = true
        stoppedByA401(w)
        assertEquals(listOf("token-1", "token-1", "token-1"), w.service.sends.map { it.token })

        w.tokens.forcedFails = false
        w.tokens.current = "token-2"
        recoversWith(w, "token-2")
        assertEquals(1, w.tokens.forceRefreshCount)
    }

    @Test
    fun aReplayAnswered401_stops_theRejectedCredentialIsNoRecovery_andANewOneRunsTheOwedRecheckOnce() = integrationTest {
        val w = wired()
        w.tokens.forced = "token-2"
        stoppedByA401(w)
        assertEquals(listOf("token-1", "token-1", "token-1", "token-2"), w.service.sends.map { it.token })

        // The replayed credential was recorded as rejected: acquiring it again recovers nothing.
        assertEquals("token-2", w.provider.currentSnapshot().token)
        runCurrent()
        assertTrue(w.coordinator.recheckDiagnostics().authStopped)

        w.tokens.current = "token-3"
        recoversWith(w, "token-3")
        assertEquals(1, w.tokens.forceRefreshCount)
    }

    private companion object {
        const val OWNER = "user-a"
        const val SETTLE = 60 * 60 * 1_000L
        private val JSON = "application/json".toMediaType()

        fun active(): Response<ResponseBody> =
            Response.success("""{"krx_visible":false,"premium_active":true}""".toResponseBody(JSON))

        fun pending(retryAfterSeconds: Int): Response<ResponseBody> = Response.success(
            """{"krx_visible":false,"premium_active":false,"premium_pending":true,"retry_after_seconds":$retryAfterSeconds}"""
                .toResponseBody(JSON)
        )

        fun unauthorized(): Response<ResponseBody> = Response.error(401, "expired".toResponseBody(JSON))

        /** Every other endpoint: this test must not reach them. */
        fun unused(): AuthenticatedApiService = Proxy.newProxyInstance(
            AuthenticatedApiService::class.java.classLoader,
            arrayOf(AuthenticatedApiService::class.java)
        ) { _, method, _ -> throw UnsupportedOperationException(method.name) } as AuthenticatedApiService
    }
}
