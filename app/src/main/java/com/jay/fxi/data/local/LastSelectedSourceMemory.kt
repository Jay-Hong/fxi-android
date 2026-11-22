package com.jay.fxi.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.auth.AuthIdentityFence
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * S8-5b (`R4c/S8/s8_5b_contract.agreed.r1.md` §2 "저장 형식과 오류"): the premium tether editor's last added exchange, one
 * source-only key per UID in the shared user-intent file, so A → B → A restores each account's own last add. Only an
 * adopted add is remembered; a read failure or an unknown code falls back without writing anything.
 */
internal interface LastSelectedSourceStore {
    suspend fun read(uid: String): String?

    suspend fun remember(uid: String, sourceCode: String)
}

@Singleton
internal class DataStoreLastSelectedSourceStore @Inject constructor(
    @ApplicationContext private val context: Context
) : LastSelectedSourceStore {

    override suspend fun read(uid: String): String? {
        require(uid.isNotBlank())
        return context.userIntentDataStore.data.first()[key(uid)]
    }

    override suspend fun remember(uid: String, sourceCode: String) {
        require(uid.isNotBlank())
        context.userIntentDataStore.edit { preferences -> preferences[key(uid)] = sourceCode }
    }

    private fun key(uid: String) = stringPreferencesKey("last_selected_source_uid_$uid")
}

internal sealed interface SourceSelectionSnapshot {
    data object None : SourceSelectionSnapshot
    data class Loading(val identity: AuthIdentityFence, val revision: Long) : SourceSelectionSnapshot
    data class Ready(val identity: AuthIdentityFence, val revision: Long, val code: String?) : SourceSelectionSnapshot
    data class Failed(val identity: AuthIdentityFence, val revision: Long) : SourceSelectionSnapshot
}

/** Main owns the selection; one process worker reads and persists captured work in acceptance order. */
@Singleton
internal class LastSelectedSourceMemory internal constructor(
    private val store: LastSelectedSourceStore,
    private val main: CoroutineDispatcher,
    scope: CoroutineScope,
    private val report: (Throwable) -> Unit
) {
    @Inject constructor(store: DataStoreLastSelectedSourceStore) : this(
        store,
        Dispatchers.Main.immediate,
        CoroutineScope(Dispatchers.IO + SupervisorJob()),
        // A fixed message only: the failure's own text may carry a path, a key or a value.
        { Log.w("LastSelectedSourceMemory", "Last selected source work failed") }
    )

    private val state = MutableStateFlow<SourceSelectionSnapshot>(SourceSelectionSnapshot.None)
    val selection: StateFlow<SourceSelectionSnapshot> = state.asStateFlow()

    private var revision = 0L
    private val queue = Channel<Work>(Channel.UNLIMITED)
    private val worker = scope.launch {
        try {
            for (work in queue) {
                when (work) {
                    is Work.Read -> read(work.identity, work.ticket, work.completion)
                    is Work.Write -> write(work.identity, work.sourceCode)
                }
            }
        } finally {
            queue.cancel()
        }
    }

    /** Ready for [identity] once its read ends, by success or failure; a later read or write of another revision wins. */
    suspend fun prepare(identity: AuthIdentityFence) {
        val completion = withContext(main) {
            val current = state.value
            if (current is SourceSelectionSnapshot.Ready && current.identity == identity) return@withContext null
            val ticket = ++revision
            state.value = SourceSelectionSnapshot.Loading(identity, ticket)
            // A waiter does not own this deferred. Cancelling the worker cancels every outstanding read.
            CompletableDeferred<Unit>(worker).also {
                queue.trySend(Work.Read(identity, ticket, it)).getOrThrow()
            }
        }
        completion?.await()
    }

    /** Called on Main for an adopted add only: publish at once, then enqueue without doing file work. */
    fun acceptAddedSource(identity: AuthIdentityFence, sourceCode: String) {
        val ticket = ++revision
        state.value = SourceSelectionSnapshot.Ready(identity, ticket, sourceCode)
        queue.trySend(Work.Write(identity, sourceCode)).getOrThrow()
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
            if (current is SourceSelectionSnapshot.Loading && current.identity == identity && current.revision == ticket) {
                state.value = result.fold(
                    onSuccess = { SourceSelectionSnapshot.Ready(identity, ticket, it) },
                    onFailure = { SourceSelectionSnapshot.Failed(identity, ticket) }
                )
            }
        }
        completion.complete(Unit)
    }

    private suspend fun write(identity: AuthIdentityFence, sourceCode: String) {
        try {
            store.remember(identity.uid, sourceCode)
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
        data class Write(val identity: AuthIdentityFence, val sourceCode: String) : Work
    }
}
