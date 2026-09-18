package com.jay.fxi.data.entitlements.control

/** Shared UTF-16 rule: JSON escapes these code units; raw Preferences strings must refuse them. */
internal fun String.hasUnpairedSurrogateAt(index: Int): Boolean = when {
    this[index].isHighSurrogate() -> index + 1 == length || !this[index + 1].isLowSurrogate()
    this[index].isLowSurrogate() -> index == 0 || !this[index - 1].isHighSurrogate()
    else -> false
}

internal fun String.hasUnpairedSurrogate(): Boolean = indices.any { hasUnpairedSurrogateAt(it) }
