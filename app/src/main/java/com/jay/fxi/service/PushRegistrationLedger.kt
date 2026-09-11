package com.jay.fxi.service

import java.io.IOException

/** What the server may still hold for an entry, and whether it is owed a DELETE. */
enum class PushRegistrationState {
    /** A POST for this key may have reached the server. Recorded before the POST is sent. */
    MAY_EXIST,

    /** A DELETE is owed: the session that registered the key ended, or its teardown began. */
    OWED
}

/**
 * One `(uid, token)` the server may hold a device registration for.
 *
 * [id] is the ledger's own revision, issued from a counter persisted with the entries that never
 * goes back. It is not an auth generation, which exists only within one process. A DELETE's
 * completion removes an entry only while the stored entry still carries the same id, so a
 * completion that lands after the key was recorded again cannot remove the newer record.
 */
data class PushLedgerEntry(
    val id: Long,
    val uid: String,
    val token: String,
    val state: PushRegistrationState
) {
    init {
        require(id >= 1) { "id must be positive" }
        require(uid.isNotEmpty()) { "uid must not be empty" }
        require(token.isNotEmpty()) { "token must not be empty" }
    }

    override fun toString(): String =
        "PushLedgerEntry(id=$id, uid=<redacted>, token=<redacted>, state=$state)"
}

/**
 * Every `(uid, token)` the server may hold a device registration for, and which are owed a DELETE.
 *
 * An entry is written before its POST is sent, so the target exists from the moment it can
 * exist on the server, not only once a sign-out fails. Different keys never overwrite each other.
 *
 * Each call returns only once its write is on disk, or throws. A caller cancelled while waiting
 * cannot tell whether the write landed and must treat it as unknown. A ledger this model could not
 * have written throws [PushLedgerCorruptedException]; a storage failure, parse failures included,
 * propagates as it is. Neither is read as an empty ledger, which would drop owed DELETEs silently.
 */
interface PushRegistrationLedger {
    suspend fun entries(): List<PushLedgerEntry>

    /**
     * Before a POST: records that [uid]'s registration of [token] may exist, under a fresh id.
     * Null, recording nothing, while that key is owed — its DELETE has to come first.
     */
    suspend fun recordMayExist(uid: String, token: String): PushLedgerEntry?

    /** Every entry of [uid] becomes owed, keeping its id. Returns all of [uid]'s entries. */
    suspend fun markOwed(uid: String): List<PushLedgerEntry>

    /**
     * Records that [uid]'s registration of [token] is owed a DELETE. An entry already there keeps
     * its id; an absent key gets a fresh one.
     */
    suspend fun recordOwed(uid: String, token: String): PushLedgerEntry

    /**
     * After a DELETE: removes [entry] only while the stored entry for its key has the same id.
     * False, without removing an entry, when it is gone or was recorded again since.
     */
    suspend fun complete(entry: PushLedgerEntry): Boolean
}

/** The stored ledger is not one [PushLedgerTransitions] could have produced. */
class PushLedgerCorruptedException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * The whole ledger: its entries, in id order, and the next id it will issue.
 *
 * One entry per key, unique ids, every id below [nextId]. A value breaking any of these did not
 * come from [PushLedgerTransitions].
 */
internal data class PushLedger(val entries: List<PushLedgerEntry>, val nextId: Long) {
    init {
        require(nextId >= 1) { "nextId must be positive" }
        require(entries.all { it.id < nextId }) { "an entry id is not below nextId" }
        require(entries.distinctBy { it.id }.size == entries.size) { "duplicate entry id" }
        require(entries.distinctBy { it.uid to it.token }.size == entries.size) { "duplicate key" }
        require(entries == entries.sortedBy { it.id }) { "entries out of id order" }
    }

    fun entryFor(uid: String, token: String): PushLedgerEntry? =
        entries.firstOrNull { it.uid == uid && it.token == token }

    companion object {
        val EMPTY = PushLedger(emptyList(), 1)
    }
}

/**
 * Every change to the ledger, as pure functions, so the rules are judged without Android and the
 * store only reads, transforms and writes.
 */
internal object PushLedgerTransitions {

    data class Result<T>(val ledger: PushLedger, val value: T)

    fun recordMayExist(ledger: PushLedger, uid: String, token: String): Result<PushLedgerEntry?> {
        val existing = ledger.entryFor(uid, token)
        if (existing?.state == PushRegistrationState.OWED) return Result(ledger, null)
        val entry = PushLedgerEntry(ledger.nextId, uid, token, PushRegistrationState.MAY_EXIST)
        return Result(ledger.with(entry, replacing = existing), entry)
    }

    fun markOwed(ledger: PushLedger, uid: String): Result<List<PushLedgerEntry>> {
        val entries = ledger.entries.map {
            if (it.uid == uid) it.copy(state = PushRegistrationState.OWED) else it
        }
        return Result(ledger.copy(entries = entries), entries.filter { it.uid == uid })
    }

    fun recordOwed(ledger: PushLedger, uid: String, token: String): Result<PushLedgerEntry> {
        val existing = ledger.entryFor(uid, token)
        val owed = existing?.copy(state = PushRegistrationState.OWED)
            ?: PushLedgerEntry(ledger.nextId, uid, token, PushRegistrationState.OWED)
        return Result(ledger.with(owed, replacing = existing), owed)
    }

    fun complete(ledger: PushLedger, entry: PushLedgerEntry): Result<Boolean> {
        val stored = ledger.entryFor(entry.uid, entry.token)
        if (stored == null || stored.id != entry.id) return Result(ledger, false)
        return Result(ledger.copy(entries = ledger.entries - stored), true)
    }

    /** [entry] in place of [replacing]; the counter moves past [entry] when it took a fresh id. */
    private fun PushLedger.with(entry: PushLedgerEntry, replacing: PushLedgerEntry?): PushLedger =
        PushLedger(
            entries = (entries - listOfNotNull(replacing).toSet() + entry).sortedBy { it.id },
            nextId = maxOf(nextId, entry.id + 1)
        )
}
