package com.jay.fxi.data.remote

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Claude-owned S4 CUT-CC4b contract: the process foreground stream delivers the current state once, before observe() returns,
 * then each change, never a repeat (`cut_cc4b_agreed.r1.md` §3). A LifecycleRegistry without main-thread checks stands in for
 * the process lifecycle.
 */
class ProcessTopicForegroundStreamTest {

    private class Process : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** CC4b-S01: registered in each state, the current value arrives once before observe() returns. */
    @Test
    fun `CC4b-S01 the current state arrives once on registration`() {
        for ((state, expected) in listOf(
            Lifecycle.State.INITIALIZED to false,
            Lifecycle.State.CREATED to false,
            Lifecycle.State.STARTED to true,
            Lifecycle.State.RESUMED to true
        )) {
            val process = Process()
            process.registry.currentState = state
            val seen = mutableListOf<Boolean>()
            ProcessTopicForegroundStream { process.lifecycle }.observe { seen += it }
            assertEquals("CC4b-S01 registered in $state", listOf(expected), seen)
        }
    }

    /** CC4b-S02: after registration each change arrives once; moves that keep the value — resume, pause — deliver nothing. */
    @Test
    fun `CC4b-S02 changes arrive once and repeats are skipped`() {
        val process = Process()
        process.registry.currentState = Lifecycle.State.CREATED
        val seen = mutableListOf<Boolean>()
        ProcessTopicForegroundStream { process.lifecycle }.observe { seen += it }
        process.registry.currentState = Lifecycle.State.STARTED
        process.registry.currentState = Lifecycle.State.RESUMED
        process.registry.currentState = Lifecycle.State.STARTED
        process.registry.currentState = Lifecycle.State.CREATED
        process.registry.currentState = Lifecycle.State.RESUMED
        assertEquals("CC4b-S02 false, then each change once", listOf(false, true, false, true), seen)
    }

    /**
     * CC4b-S03: a registration made re-entrantly — inside another observer's ON_START — still gets the current value, once,
     * before its observe() returns, and later changes once.
     */
    @Test
    fun `CC4b-S03 a re-entrant registration still gets the current value once`() {
        val process = Process()
        process.registry.currentState = Lifecycle.State.CREATED
        val stream = ProcessTopicForegroundStream { process.lifecycle }
        val inner = mutableListOf<Boolean>()
        var atReturn: List<Boolean>? = null
        stream.observe { foreground ->
            if (foreground && atReturn == null) {
                stream.observe { inner += it }
                atReturn = inner.toList()
            }
        }
        process.registry.currentState = Lifecycle.State.STARTED
        assertEquals("CC4b-S03 the current value before observe() returned", listOf(true), atReturn)
        process.registry.currentState = Lifecycle.State.CREATED
        assertEquals("CC4b-S03 then each change once", listOf(true, false), inner)
    }
}
