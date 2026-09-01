package com.jay.fxi.time

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Small wall-clock seam for state machines whose timestamps are part of a
 * contract. Coroutine virtual time remains a separate duration axis.
 *
 * S0 establishes the injectable boundary; later slices wire their production
 * state-machine owners to it when those owners are introduced.
 */
fun interface AppClock {
    fun now(): Instant
}

object SystemAppClock : AppClock {
    override fun now(): Instant = Clock.System.now()
}
