package com.jay.fxi.data.alerts

import com.jay.fxi.data.repository.AlertRepositoryFailureKind

internal sealed interface AlertLoadFailure {
    data object AuthRequired : AlertLoadFailure
    data object Forbidden : AlertLoadFailure
    data class Failed(val kind: AlertRepositoryFailureKind, val statusCode: Int?) : AlertLoadFailure
}

internal sealed interface AlertLoadPhase {
    data object Idle : AlertLoadPhase
    data object Loading : AlertLoadPhase
    data object Retrying : AlertLoadPhase
    data object Ready : AlertLoadPhase
    data class TerminalError(val failure: AlertLoadFailure) : AlertLoadPhase
}
