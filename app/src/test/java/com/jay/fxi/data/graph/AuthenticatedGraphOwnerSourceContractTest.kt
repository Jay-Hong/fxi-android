package com.jay.fxi.data.graph

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.di.NetworkModule
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * S4 INT-a (`int_api_agreed.r3.md` §2): the production-shaped [GraphOwnerSource] over the real [AuthTokenProvider] and
 * [AuthenticatedApiClient]. The live identity is the provider's fence read; capture is the client's fenced capture, delegated
 * on every call, so D24 admission and the stale-owner refusal stay in the transport. No HTTP is sent: capture reads the token
 * source only.
 */
class AuthenticatedGraphOwnerSourceContractTest {
    private class Source : AuthTokenSource {
        @Volatile var live: AuthIdentity? = AuthIdentity("user-a", 1)
        val fetches = AtomicInteger()
        val reads = AtomicInteger()
        val invalidations = AtomicInteger()

        /** When > 0, the read with this number first moves [live] to [moveTo]: the identity changes inside a call. */
        @Volatile var moveAtRead = 0
        @Volatile var moveTo: AuthIdentity? = null

        /** Thrown by every token fetch when set. */
        @Volatile var failure: Throwable? = null

        override fun currentIdentity(): AuthIdentity? {
            if (reads.incrementAndGet() == moveAtRead) live = moveTo
            return live
        }

        /** As production: retiring the current session moves its generation; counted so a read that does it is caught. */
        override fun invalidateCurrentSession(expected: AuthIdentity): Boolean {
            invalidations.incrementAndGet()
            if (live != expected) return false
            live = expected.copy(authGeneration = expected.authGeneration + 1)
            return true
        }

        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            fetches.incrementAndGet()
            failure?.let { throw it }
            return "credential-${identity.uid}-${identity.authGeneration}"
        }
    }

    private class Fixture(scope: CoroutineScope, admitted: () -> Boolean = { true }) {
        val source = Source()
        val tokens = AuthTokenProvider(source, scope, AccessOrderSequence())
        private val wireJson = NetworkModule.provideWireJson()
        private val retrofit = Retrofit.Builder().baseUrl("http://localhost/").client(OkHttpClient())
            .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build()
        val api = AuthenticatedApiClient(retrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(tokens, admitted = admitted), wireJson)
        val owners: GraphOwnerSource = AuthenticatedGraphOwnerSource(tokens, api)
    }

    /** What [block] threw, if anything. AuthIdentityChangedException is a CancellationException, so catch everything here. */
    private suspend fun thrownBy(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (thrown: Throwable) {
            thrown
        }

    private fun AuthIdentityFence?.pair() = this?.let { it.uid to it.authGeneration }

    @Test fun O01_currentIdentity_isTheProvidersLiveFence_andNullWhenSignedOut_withoutFetching() = runTest {
        val f = Fixture(backgroundScope)
        assertEquals(f.tokens.currentIdentityFence(), f.owners.currentIdentity())
        assertEquals("user-a" to 1L, f.owners.currentIdentity().pair())
        f.source.live = AuthIdentity("user-a", 2)
        assertEquals("user-a" to 2L, f.owners.currentIdentity().pair())
        f.source.live = AuthIdentity("user-b", 2)
        assertEquals("user-b" to 2L, f.owners.currentIdentity().pair())
        f.source.live = null
        assertNull(f.owners.currentIdentity())
        assertEquals(0, f.source.fetches.get())
        assertEquals(0, f.source.invalidations.get())
    }

    /** O01b: one read, never a fence assembled from two reads across a move. */
    @Test fun O01b_currentIdentity_isOneRead_neverATornFence() = runTest {
        for (k in 1..3) {
            val f = Fixture(backgroundScope)
            val before = f.tokens.currentIdentityFence()
            f.source.moveTo = AuthIdentity("user-b", 2)
            f.source.moveAtRead = f.source.reads.get() + k
            val seen = f.owners.currentIdentity()
            val after = f.tokens.currentIdentityFence()
            assertTrue("k=$k: $seen is neither $before nor $after", seen == before || seen == after)
        }
    }

    @Test fun O02_capture_ofTheLiveFence_returnsThatOwnersSnapshot() = runTest {
        val f = Fixture(backgroundScope)
        val expected = f.owners.currentIdentity()!!
        val snapshot = f.owners.capture(expected)
        assertEquals(expected, snapshot.fence)
        assertEquals("credential-user-a-1", snapshot.token)
        assertEquals(1, f.source.fetches.get())
        assertEquals(0, f.source.invalidations.get())
    }

    /** O02b: every capture is admitted, fenced and acquired again; nothing is remembered between calls. */
    @Test fun O02b_capture_isNotMemoized_eachCallIsAdmittedAndFencedAgain() = runTest {
        var open = true
        val f = Fixture(backgroundScope, admitted = { open })
        val fence = f.owners.currentIdentity()!!
        f.owners.capture(fence)
        f.owners.capture(fence)
        assertEquals("each capture acquires through the provider", 2, f.source.fetches.get())
        open = false
        assertEquals(IOException::class.java, thrownBy { f.owners.capture(fence) }?.javaClass)
        open = true
        f.source.live = AuthIdentity("user-a", 2)
        assertTrue(thrownBy { f.owners.capture(fence) } is AuthIdentityChangedException)
        assertEquals(2, f.source.fetches.get())
    }

    @Test fun O03_capture_withReleaseAdmissionOff_isRefusedBeforeAnyToken() = runTest {
        val f = Fixture(backgroundScope, admitted = { false })
        val expected = f.owners.currentIdentity()!!
        val thrown = thrownBy { f.owners.capture(expected) }
        assertEquals("refused as D24 itself, not reclassified: $thrown", IOException::class.java, thrown?.javaClass)
        f.source.live = AuthIdentity("user-a", 2)
        val closedAndStale = thrownBy { f.owners.capture(expected) }
        assertEquals("D24 is decided before the owner: $closedAndStale", IOException::class.java, closedAndStale?.javaClass)
        assertEquals(0, f.source.fetches.get())
        assertEquals(0, f.source.invalidations.get())
    }

    /** O03b: a token outage reaches the caller as itself, after one acquisition: no reclassification, no retry. */
    @Test fun O03b_capture_passesATokenFailureThroughUnchanged_once() = runTest {
        // Not backgroundScope: it reports a non-cancellation child failure and would fail even the reference.
        val f = Fixture(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        f.source.failure = AuthUnavailableException("offline")
        val thrown = thrownBy { f.owners.capture(f.owners.currentIdentity()!!) }
        assertEquals("an outage stays an outage: $thrown", AuthUnavailableException::class.java, thrown?.javaClass)
        assertEquals(1, f.source.fetches.get())
    }

    @Test fun O04_capture_ofAStaleFence_isRefused_neverTheNewOwnersSnapshot() = runTest {
        val f = Fixture(backgroundScope)
        val stale = f.owners.currentIdentity()!!
        f.source.live = AuthIdentity("user-a", 2)
        val sameUser = thrownBy { f.owners.capture(stale) }
        assertTrue("a stale generation must not be captured: $sameUser", sameUser is AuthIdentityChangedException)
        f.source.live = AuthIdentity("user-b", 1)
        val otherUser = thrownBy { f.owners.capture(stale) }
        assertTrue("another user's credential must not be captured: $otherUser", otherUser is AuthIdentityChangedException)
        f.source.live = null
        val signedOut = thrownBy { f.owners.capture(stale) }
        assertTrue("a signed-out owner is an identity change, not an outage: $signedOut", signedOut is AuthIdentityChangedException)
        listOf(sameUser, otherUser, signedOut).forEach {
            val e = it as AuthIdentityChangedException
            assertTrue("a capture refusal carries no HTTP evidence: ${e.statusCode} ${e.retryAfter} ${e.exchanges}",
                e.statusCode == null && e.retryAfter == null && e.exchanges.isEmpty())
        }
        assertEquals(0, f.source.fetches.get())
        assertEquals(0, f.source.invalidations.get())
    }

    /** O04b: the identity moving at any read inside capture never yields another owner's credential. */
    @Test fun O04b_capture_neverHandsOutAnotherOwnersCredential_whenTheIdentityMovesMidCapture() = runTest {
        var refusedAfterFetch = 0
        var completed = 0
        for (moved in listOf(AuthIdentity("user-a", 2), AuthIdentity("user-b", 1))) for (k in 1..12) {
            val f = Fixture(backgroundScope)
            val fence = f.owners.currentIdentity()!!
            f.source.moveTo = moved
            f.source.moveAtRead = f.source.reads.get() + k
            var got: AuthSnapshot? = null
            val thrown = thrownBy { got = f.owners.capture(fence) }
            val snapshot = got
            if (snapshot == null) {
                assertTrue("k=$k $moved: refused only as an identity change: $thrown", thrown is AuthIdentityChangedException)
                if (f.source.fetches.get() > 0) refusedAfterFetch++
            } else {
                assertEquals("k=$k $moved: the expected owner's fence", fence, snapshot.fence)
                assertEquals("k=$k $moved: the expected owner's credential",
                    "credential-${fence.uid}-${fence.authGeneration}", snapshot.token)
                completed++
            }
        }
        assertTrue("premise: some move landed after the owner check and was refused after acquisition", refusedAfterFetch > 0)
        assertTrue("premise: the read window extends past the whole capture", completed > 0)
    }

    @Test fun O05_currentIdentity_neverThrows_andIgnoresReleaseAdmission() = runTest {
        val f = Fixture(backgroundScope, admitted = { false })
        assertEquals(f.tokens.currentIdentityFence(), f.owners.currentIdentity())
        assertEquals("user-a" to 1L, f.owners.currentIdentity().pair())
        f.source.live = null
        assertNull(f.owners.currentIdentity())
        assertEquals(0, f.source.fetches.get())
        assertEquals(0, f.source.invalidations.get())
    }

    /**
     * O06: dormant until the cutover. No other compiled non-test source of :app names it. In its own file, the text remaining
     * after regex comment removal contains its name once and no DI name or package. This filter is not Kotlin-aware.
     * Mandatory implementation diff review verifies no factory, construction or Hilt/DI wiring, including code hidden from
     * the filter by comment markers in strings and annotation aliases declared in another file. Other annotations are allowed.
     */
    @Test fun O06_dormant_noProductionFileNamesIt_andNoDiAnnotation() {
        val roots = File("src").listFiles().orEmpty()
            .filter { it.isDirectory && it.name !in setOf("test", "androidTest", "testFixtures") }
            .flatMap { set -> listOf("java", "kotlin").map { File(set, it) } }
            .filter { it.isDirectory }
        val all = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }
                .map { "${root.parentFile.name}/${root.name}/${it.relativeTo(root).invariantSeparatorsPath}" to it.readText() }
        }.toMap()
        val path = "main/java/com/jay/fxi/data/graph/AuthenticatedGraphOwnerSource.kt"
        assertTrue("premise: the scan sees the file and a non-main source set",
            path in all && all.size > 100 && all.keys.any { it.startsWith("benchmark/") })
        val name = Regex("""\bAuthenticatedGraphOwnerSource\b""")
        val referencing = all.filter { (p, text) -> p != path && name.containsMatchIn(text) }.keys
        assertEquals("no other production file names it", emptySet<String>(), referencing)
        val code = all.getValue(path).replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")
        assertEquals("its own file only declares it: no factory, typealias or construction", 1, name.findAll(code).count())
        // A trip-wire over regex-stripped text, not a Kotlin parser. Comment markers inside strings can hide code;
        // mandatory implementation diff review verifies the full dormant boundary.
        assertFalse("regex-stripped text mentions no DI name or package",
            Regex("""\b(Inject|AssistedInject|Singleton|Module|InstallIn|EntryPoint|Provides|Binds|HiltViewModel)\b|""" +
                """\b(javax|jakarta)\.inject\b|\bdagger\b""").containsMatchIn(code))
    }
}
