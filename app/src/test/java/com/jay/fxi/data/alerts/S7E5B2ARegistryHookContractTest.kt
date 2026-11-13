package com.jay.fxi.data.alerts

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S7 E5b2a contract (s7e5b2_api_agreed.r1.md §2.4, R07a): the registry's generic foreground hooks. Each foreground
 * transition runs every hook once, before the families; a repeated value runs none; a background runs none; the state at
 * start counts as a transition. A throwing hook is reported once and neither the other hooks nor the families are skipped.
 * Nothing runs before [AlertFamilyRefresherRegistry.start].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class S7E5B2ARegistryHookContractTest {

    private class Process : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class Rig(initial: Lifecycle.State, hookCount: Int = 2, private val throwingHook: Int? = null) {
        val process = Process().also { it.registry.currentState = initial }
        val log = mutableListOf<String>()
        var failures = 0
        val scope = TestScope(StandardTestDispatcher())
        private val adapter = object : AlertFamilyAdapter {
            override fun onForeground(authoritative: Boolean) { log += "family:fg" }
            override fun onBackground() { log += "family:bg" }
            override fun onSync() { log += "family:sync" }
        }
        val registry = AlertFamilyRefresherRegistry(
            families = listOf({ adapter }),
            lifecycle = { process.lifecycle },
            postToMain = { it() },
            premium = MutableStateFlow(OwnedPremiumAccess("user-a", 1L, PremiumAccessState.PremiumConfirmed)),
            liveIdentity = { AuthIdentityFence("user-a", 1L) },
            scope = scope,
            reportFailure = { failures++ },
            foregroundHooks = List(hookCount) { index ->
                {
                    log += "hook$index"
                    if (index == throwingHook) throw IllegalStateException("hook $index")
                }
            }
        )

        fun move(state: Lifecycle.State) {
            process.registry.currentState = state
            scope.runCurrent()
        }

        fun close() = scope.cancel()
    }

    private fun withRig(rig: Rig, block: Rig.() -> Unit) {
        try {
            rig.block()
        } finally {
            rig.close()
        }
    }

    @Test fun `S7E5B2A_H01 a start in the foreground runs every hook once before the families`() = withRig(Rig(Lifecycle.State.RESUMED)) {
        registry.start(); scope.runCurrent()
        assertEquals(listOf("hook0", "hook1", "family:fg"), log)
    }

    @Test fun `S7E5B2A_H02 a start in the background runs no hook and the first foreground runs them once`() = withRig(Rig(Lifecycle.State.CREATED)) {
        registry.start(); scope.runCurrent()
        assertEquals(listOf("family:bg"), log)
        log.clear()
        move(Lifecycle.State.STARTED)
        assertEquals(listOf("hook0", "hook1", "family:fg"), log)
    }

    @Test fun `S7E5B2A_H03 each transition to the foreground runs the hooks once and a repeat or a background runs none`() = withRig(Rig(Lifecycle.State.RESUMED)) {
        registry.start(); scope.runCurrent()
        log.clear()
        move(Lifecycle.State.STARTED)
        move(Lifecycle.State.RESUMED)
        assertEquals("staying in the foreground runs nothing", emptyList<String>(), log)
        move(Lifecycle.State.CREATED)
        assertEquals(listOf("family:bg"), log)
        log.clear()
        move(Lifecycle.State.STARTED)
        assertEquals(listOf("hook0", "hook1", "family:fg"), log)
    }

    @Test fun `S7E5B2A_H04 a throwing hook is reported and skips neither the next hook nor the families`() = withRig(Rig(Lifecycle.State.RESUMED, hookCount = 3, throwingHook = 0)) {
        registry.start(); scope.runCurrent()
        assertEquals(listOf("hook0", "hook1", "hook2", "family:fg"), log)
        assertEquals(1, failures)
    }

    @Test fun `S7E5B2A_H05 nothing runs before start`() = withRig(Rig(Lifecycle.State.RESUMED)) {
        move(Lifecycle.State.CREATED)
        move(Lifecycle.State.RESUMED)
        assertEquals(emptyList<String>(), log)
    }
}
