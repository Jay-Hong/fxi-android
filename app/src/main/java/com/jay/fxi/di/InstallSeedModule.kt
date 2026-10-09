package com.jay.fxi.di

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jay.fxi.data.free.InstallSeedPrefetch
import com.jay.fxi.data.free.InstallSeedSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

/**
 * S4 CUT-CC1: the one install seed source shared by the free scheduler and, from CC4, the graph runtime, and the start that
 * reads it ahead on IO (`cut_cc1_agreed.r3.md` §1.3). Resolving either reads nothing from disk.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object InstallSeedModule {

    @Provides
    @Singleton
    fun provideInstallSeedSource(@ApplicationContext context: Context): InstallSeedSource =
        InstallSeedSource({ installSeed(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)) }, Dispatchers.IO)

    /** Resolved after Firebase initialization, like every app-owned start; the report reaches Crashlytics only on failure. */
    @Provides
    @Singleton
    fun provideInstallSeedPrefetch(seeds: InstallSeedSource): InstallSeedPrefetch =
        InstallSeedPrefetch(seeds, Dispatchers.IO) { FirebaseCrashlytics.getInstance().recordException(it) }

    private const val PREFS = "free_snapshot"
}

private const val KEY_INSTALL_SEED = "install_seed"

/**
 * A stable random value, drawn once per install.
 *
 * It only ever feeds the jitter that spreads clients across the refresh window, so its single
 * requirement is that it survive restarts — a value redrawn on every launch would still spread,
 * but would move this install's slot around and make a herd harder to reason about. It is
 * deliberately not a device identifier: nothing derives it from the hardware and it does not
 * leave the process. iOS keeps the same value under `free_snapshot_install_seed` in
 * `UserDefaults`; this is the same idea in `SharedPreferences`. A device-transfer restore can
 * copy it, so "per install" is not strict — uniqueness is not a correctness requirement here,
 * only spread.
 */
internal fun installSeed(prefs: SharedPreferences): String {
    prefs.getString(KEY_INSTALL_SEED, null)?.let { return it }
    val seed = UUID.randomUUID().toString()
    prefs.edit().putString(KEY_INSTALL_SEED, seed).apply()
    return seed
}
