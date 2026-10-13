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
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * in. A failed or throwing seed is thrown from that coroutine, which has no handler, instead of being logged while the app runs
 * on signed out.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object S5FixtureBindingsModule {

    @Provides
    @Singleton
    fun provideS5FixtureIdentity(seeder: S5FixtureSelectionSeeder): S5FixtureIdentity {
        val main = Handler(Looper.getMainLooper())
        val identity = S5FixtureIdentity { main.post(it) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { startS5Fixture(identity) { seeder.seed() } }
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

/** S5-4 slice 4b: signs the fixture user in once [seed] has written and read back the selection; anything else is a startup failure. */
internal suspend fun startS5Fixture(identity: S5FixtureIdentity, seed: suspend () -> Boolean) {
    check(identity.signInAfter(seed)) { "S5 fixture selection seed failed" }
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
