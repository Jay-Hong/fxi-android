package com.jay.fxi.s5fixture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.auth.AuthUserObservation
import com.jay.fxi.di.GraphWallClock
import com.jay.fxi.di.ServerEndpoints
import com.jay.fxi.time.AppClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant

/** S5-4 slice 4b: the frozen fixture files, read from this build type's assets (src/benchmark/assets/s5_4, never copied). */
internal class S5FixtureAssets @Inject constructor(@ApplicationContext private val context: Context) {
    fun bytes(name: String): ByteArray = context.assets.open(name).use { it.readBytes() }
}

/**
 * S5-4 slice 4b: the S5 fixture's bindings for the points the production build types bind in src/productionBindings: one
 * identity behind the three auth bindings, and the graph clock fixed at the bundle's now (2026-10-05T12:01+09:00).
 *
 * Creating the identity (once per process, when the first auth consumer resolves it during application start) starts the
 * selection seed on a background coroutine through [startS5Fixture]; only a committed and read-back seed signs the fixture user
 * in. Around the seed it checks, in this process, that the three auth bindings are the identity and that user notices replay,
 * arrive on the main thread and stop at close. A failed seed or check is thrown from that coroutine, which has no handler,
 * instead of being logged while the app runs on.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object S5FixtureBindingsModule {

    @Provides
    @Singleton
    fun provideS5FixtureIdentity(
        seeder: S5FixtureSelectionSeeder,
        observations: Provider<AuthUserObservation>,
        tokens: Provider<AuthTokenSource>,
        fences: Provider<AuthFenceStream>
    ): S5FixtureIdentity {
        val main = Handler(Looper.getMainLooper())
        val identity = S5FixtureIdentity { main.post(it) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            startS5Fixture(
                identity,
                isMainThread = { Looper.getMainLooper().isCurrentThread },
                bindings = { listOf(observations.get(), tokens.get(), fences.get()) },
                report = { Log.i("S5Fixture", it) },
                awaitPostedNotices = {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        main.post { continuation.resume(Unit) }
                    }
                }
            ) { seeder.seed() }
        }
        return identity
    }

    @Provides
    fun provideAuthUserObservation(identity: S5FixtureIdentity): AuthUserObservation = identity

    @Provides
    fun provideAuthTokenSource(identity: S5FixtureIdentity): AuthTokenSource = identity

    @Provides
    fun provideAuthFenceStream(identity: S5FixtureIdentity): AuthFenceStream = identity

    @Provides
    @GraphWallClock
    fun provideGraphWallClock(): AppClock = AppClock { FIXTURE_NOW }

    val FIXTURE_NOW: Instant = Instant.parse("2026-10-05T03:01:00Z")
}

/**
 * S5-4 slice 4b: signs the fixture user in once [seed] has written and read back the selection, and checks the fixture in the
 * running process: registrations X and Y each hear the signed-out replay on the main thread, exactly once, before the seed
 * starts (a failed replay never reaches the seed); X is closed; after the sign-in, Y
 * hears it on the main thread and X hears nothing; the three auth bindings are all [identity]. Only then is [S5_SELF_CHECK_PASSED]
 * reported; a failed seed, a missing notice or any violation is thrown. Nothing here blocks the main thread.
 */
internal suspend fun startS5Fixture(
    identity: S5FixtureIdentity,
    isMainThread: () -> Boolean,
    bindings: () -> List<Any>,
    report: (String) -> Unit,
    awaitPostedNotices: suspend () -> Unit = {},
    timeoutMillis: Long = 10_000L,
    seed: suspend () -> Boolean
) {
    val closed = S5NoticeProbe(identity, isMainThread)
    val open = S5NoticeProbe(identity, isMainThread)
    val x = identity.register(closed::onChange)
    val y = identity.register(open::onChange)
    try {
        val closedReplay = closed.next(timeoutMillis)
        val openReplay = open.next(timeoutMillis)
        val signedOutOnMain = S5Notice(onMainThread = true, signedOut = true)
        check(closedReplay == signedOutOnMain && openReplay == signedOutOnMain) {
            "S5 fixture self-check failed: no signed-out replay on the main thread for both registrations"
        }
        check(withTimeoutOrNull(timeoutMillis) { awaitPostedNotices(); true } == true) {
            "S5 fixture self-check failed: main notice barrier timed out"
        }
        check(closed.count == 1 && open.count == 1) {
            "S5 fixture self-check failed: initial notice count is not one per registration"
        }
        x.close()
        val heardBeforeClose = closed.count
        check(identity.signInAfter(seed)) { "S5 fixture selection seed failed" }
        // Notices are posted in registration order on one looper: once Y has heard the sign-in, a notice to X would have run.
        val openSignIn = open.next(timeoutMillis)
        check(withTimeoutOrNull(timeoutMillis) { awaitPostedNotices(); true } == true) {
            "S5 fixture self-check failed: main notice barrier timed out"
        }
        check(open.count == 2) {
            "S5 fixture self-check failed: unexpected open registration notice count"
        }
        val problems = judgeS5SelfCheck(
            S5SelfCheckSeen(
                bindingsAreTheIdentity = bindings().let { it.size == 3 && it.all { binding -> binding === identity } },
                closedReplay = closedReplay,
                openReplay = openReplay,
                openSignIn = openSignIn,
                closedAfterClose = closed.count - heardBeforeClose
            )
        )
        check(problems.isEmpty()) { "S5 fixture self-check failed: " + problems.joinToString("; ") }
        report(S5_SELF_CHECK_PASSED)
    } finally {
        x.close()
        y.close()
    }
}

/** S5-4 slice 4b: the server addresses, the fixture server's own, given only after it is bound. The one ServerEndpoints binding. */
@Module
@InstallIn(SingletonComponent::class)
internal object S5FixtureEndpointsModule {

    @Provides
    @Singleton
    fun provideS5FixtureServer(assets: S5FixtureAssets): S5FixtureServer =
        S5FixtureServer(load = assets::bytes, report = { Log.e("S5Fixture", it) })

    @Provides
    @Singleton
    fun provideServerEndpoints(server: S5FixtureServer): ServerEndpoints = server.awaitEndpoints()
}
