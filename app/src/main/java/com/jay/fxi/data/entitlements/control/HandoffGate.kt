package com.jay.fxi.data.entitlements.control

import java.io.IOException

/** The current boot observation for one owner decision. Production binding (boot id source) is D2c. */
internal fun interface BootReadingSource {
    fun read(): BootReading
}

internal enum class BootReadingRefusal {
    SOURCE_MISSING, SOURCE_EXCEPTION, INVALID_READING
}

internal sealed interface HandoffGateRefusal {
    data class Precondition(val reason: CompletionRejectionReason) : HandoffGateRefusal
    data object NotUnresolved : HandoffGateRefusal
    data object NotOnceConfirm : HandoffGateRefusal
    data object DeclarationRefMismatch : HandoffGateRefusal
    data class Clock(val reason: BootReadingRefusal) : HandoffGateRefusal
    data class G05(val failures: List<G05Failure>) : HandoffGateRefusal
}

/** An observation only: Eligible carries no command, snapshot, time or deletion authority and nothing consumes it. */
internal sealed interface HandoffGateDecision {
    data object Eligible : HandoffGateDecision
    data object AlreadyTerminated : HandoffGateDecision
    data class Rejected(val reason: HandoffGateRefusal) : HandoffGateDecision
    data class RecoveryRequired(val reason: RecoveryReason) : HandoffGateDecision
    data class ReadFailed(val failure: IOException) : HandoffGateDecision
}
