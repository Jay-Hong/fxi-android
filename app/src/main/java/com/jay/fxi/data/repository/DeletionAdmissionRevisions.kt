package com.jay.fxi.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * S7-c: moves after each publication to the shared deletion admission store, on the publishing thread with no suspension in
 * between. A trigger to recheck, never a decision: whoever rechecks reads the store's fresh blocks. Memory only.
 */
@Singleton
internal class DeletionAdmissionRevisions @Inject constructor() {
    private val revision = MutableStateFlow(0L)
    val revisions: StateFlow<Long> = revision.asStateFlow()

    fun published() { revision.update { it + 1 } }
}
