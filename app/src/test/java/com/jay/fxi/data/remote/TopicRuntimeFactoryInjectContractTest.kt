package com.jay.fxi.data.remote

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthCredentialRecoveryStream
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.AccessEpochStore
import com.jay.fxi.data.entitlements.CapabilityScopePurger
import com.jay.fxi.data.entitlements.EntitlementsSource
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.UserScopePurger
import com.jay.fxi.data.local.FreeTabStore
import com.jay.fxi.data.local.TopicLastKnownStore
import com.jay.fxi.di.NetworkModule
import java.lang.reflect.Proxy
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Claude-owned R4-c C1 contract (R4c/design_codex.r1.md C1). The Hilt constructor of [TopicRuntimeFactory] hands the singleton
 * [TopicLastKnownStore] to the same factory field the test constructor fills, which `create()` turns into the session's
 * restore gate and live offer (TopicRuntimeFactoryTest D1–D4). Building the factory touches no dependency: every interface
 * here throws if called and the work scope never runs, so construction alone does no store, network or disk work. Nothing
 * in the app references the factory yet. The implementation reads but does not edit this file.
 */
class TopicRuntimeFactoryInjectContractTest {
    private val calls = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
    @After fun tearDown() = scope.cancel()

    /** An interface stand-in that records and fails any call other than identity methods. */
    private inline fun <reified T> untouched(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, m, args ->
        when (m.name) {
            "toString" -> "untouched ${T::class.java.simpleName}"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            else -> { calls += "${T::class.java.simpleName}.${m.name}"; error("construction used ${m.name}") }
        }
    } as T

    @Test fun C1_1_theInjectConstructorKeepsTheGivenStore_andConstructionUsesNothing() {
        val orders = AccessOrderSequence()
        val tokens = AuthTokenProvider(untouched<AuthTokenSource>(), scope, orders)
        val coordinator = PremiumAccessCoordinator(
            source = untouched<EntitlementsSource>(), store = untouched<AccessEpochStore>(),
            userPurger = untouched<UserScopePurger>(), capabilityPurger = untouched<CapabilityScopePurger>(),
            scope = scope, clock = { 0L }, liveFence = { null }, orders = orders)
        val json = NetworkModule.provideWireJson()
        val api = AuthenticatedApiClient(untouched<AuthenticatedApiService>(), AuthenticatedTransport(tokens) { false }, json)
        val store = TopicLastKnownStore(untouched<DataStore<Preferences>>(), { 0L }, 1L, scope)
        calls.clear()

        val factory = TopicRuntimeFactory(tokens, orders, untouched<AuthFenceStream>(), untouched<AuthCredentialRecoveryStream>(),
            coordinator, TopicSnapshotBootstrapService(api, TopicFrameDecoder(json), 10.seconds), TopicFrameDecoder(json),
            untouched<FreeTabStore>(), json, store)

        val held = TopicRuntimeFactory::class.java.getDeclaredField("lastKnown").apply { isAccessible = true }.get(factory)
        assertSame("C1-1 the factory holds the injected store", store, held)
        assertEquals("C1-1 construction called no dependency", emptyList<String>(), calls)
    }
}
