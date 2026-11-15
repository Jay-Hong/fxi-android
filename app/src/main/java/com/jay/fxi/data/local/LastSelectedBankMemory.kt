package com.jay.fxi.data.local

import android.util.Log
import com.jay.fxi.data.auth.AuthIdentityFence
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface BankSelectionSnapshot {
    data object None : BankSelectionSnapshot
    data class Loading(val identity: AuthIdentityFence, val revision: Long) : BankSelectionSnapshot
    data class Ready(val identity: AuthIdentityFence, val revision: Long, val code: String?) : BankSelectionSnapshot
    data class Failed(val identity: AuthIdentityFence, val revision: Long) : BankSelectionSnapshot
}

/** Main owns the selection; one process worker persists captured work in acceptance order. */
@Singleton
internal class LastSelectedBankMemory internal constructor(
    private val store: LastSelectedBankStore,
    private val main: CoroutineDispatcher,
    scope: CoroutineScope,
    private val report: (Throwable) -> Unit
) {
    @Inject constructor(store: DataStoreLastSelectedBankStore) : this(
        store,
        Dispatchers.Main.immediate,
        CoroutineScope(Dispatchers.IO + SupervisorJob()),
        { cause -> Log.w("LastSelectedBankMemory", "Last selected bank work failed", cause) }
    )

    private val state = MutableStateFlow<BankSelectionSnapshot>(BankSelectionSnapshot.None)
    val selection: StateFlow<BankSelectionSnapshot> = state.asStateFlow()

    @Volatile private var bound = false
    private var revision = 0L
    private val queue = Channel<Work>(Channel.UNLIMITED)
    private val worker = scope.launch {
        try {
            for (work in queue) {
                when (work) {
                    is Work.Read -> read(work.identity, work.ticket, work.completion)
                    is Work.Write -> write(work.identity, work.bankCode)
                }
            }
        } finally {
            queue.cancel()
        }
    }

    suspend fun prepare(identity: AuthIdentityFence) {
        val completion = withContext(main) {
            val current = state.value
            if (current is BankSelectionSnapshot.Ready && current.identity == identity) return@withContext null
            val ticket = ++revision
            state.value = BankSelectionSnapshot.Loading(identity, ticket)
            // A waiter does not own this deferred. Cancelling the worker cancels every outstanding read.
            CompletableDeferred<Unit>(worker).also {
                queue.trySend(Work.Read(identity, ticket, it)).getOrThrow()
            }
        }
        completion?.await()
    }

    /** Called on Main: publish immediately, then enqueue without doing file work. */
    fun acceptAddedBank(identity: AuthIdentityFence, bankCode: String) {
        val ticket = ++revision
        state.value = BankSelectionSnapshot.Ready(identity, ticket, bankCode)
        queue.trySend(Work.Write(identity, bankCode)).getOrThrow()
    }

    fun markConsumerBound() {
        bound = true
    }

    suspend fun requireReady() {
        check(bound) { "Last selected bank consumer not bound" }
        store.verifyReadable()
    }

    private suspend fun read(identity: AuthIdentityFence, ticket: Long, completion: CompletableDeferred<Unit>) {
        val result = try {
            Result.success(store.read(identity.uid))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report(failure)
            Result.failure(failure)
        }
        withContext(main) {
            val current = state.value
            if (current is BankSelectionSnapshot.Loading && current.identity == identity && current.revision == ticket) {
                state.value = result.fold(
                    onSuccess = { BankSelectionSnapshot.Ready(identity, ticket, it) },
                    onFailure = { BankSelectionSnapshot.Failed(identity, ticket) }
                )
            }
        }
        completion.complete(Unit)
    }

    private suspend fun write(identity: AuthIdentityFence, bankCode: String) {
        try {
            store.remember(identity.uid, bankCode)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report(failure)
        }
    }

    private sealed interface Work {
        data class Read(
            val identity: AuthIdentityFence,
            val ticket: Long,
            val completion: CompletableDeferred<Unit>
        ) : Work
        data class Write(val identity: AuthIdentityFence, val bankCode: String) : Work
    }
}
