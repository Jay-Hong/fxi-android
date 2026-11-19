package com.jay.fxi.ui.free

/**
 * S7 E5d gap hunt r1 G13·G14: made-up roots the closure test feeds to [S7E5DClassClosure] to show each way out is caught.
 * Test code only; nothing in the app refers to these.
 */
internal object S7E5DClosureFixtureReflection {
    fun probe(): Any = Class.forName("java.lang.String")
}

internal var s7e5dKeptCallback: (() -> Unit)? = null

/** A callback stored where it outlives the call: a static write outside the class initialiser. */
internal fun s7e5dKeepCallback(callback: () -> Unit) {
    s7e5dKeptCallback = callback
}

/** A neutral-named helper that reaches an app capability by class literal. */
internal fun s7e5dNeutralHelper(): Any = com.jay.fxi.data.local.LastSelectedBankMemory::class.java

/** A library call whose owner and name an allowlist might carry under another descriptor. */
internal fun s7e5dOverload(a: Long, b: Long): Long = Math.max(a, b)

/** An inline body: its references land in this class, not in the inline function's own. */
internal fun s7e5dInlinePositive(): Int {
    var n = 0
    repeat(2) { n += Math.abs(-1) }
    return n
}
