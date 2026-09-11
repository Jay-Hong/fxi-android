package com.jay.fxi.service

import com.jay.fxi.data.auth.AuthIdentityFence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How the server answered a DELETE of one registration. */
internal enum class DeleteResult {
    /** The row for that uid and token was deleted. */
    DELETED,

    /**
     * The endpoint's own 404: no row for that uid and token now. It resolves the entry, but says
     * nothing about a late request still on its way.
     */
    ABSENT_FOR_UID,

    /** Any other answer, or none: the row may still be there. */
    FAILED
}

/**
 * The device-registration endpoints. A call is prepared first — every wait for credentials
 * happens in [session] — so that the admission check can come after the last wait; the session's
 * calls then send without waiting for credentials again. Premium plays no part in a DELETE.
 */
internal interface PushDeviceServer {
    /** What calls as [owner] need, or null when it cannot be had. Cancellation propagates. */
    suspend fun session(owner: AuthIdentityFence): PushServerSession?
}

internal interface PushServerSession {
    /** True when the server accepted the registration. Cancellation, identity changes included, propagates. */
    suspend fun register(token: String): Boolean

    /** Cancellation, identity changes included, propagates; everything else is an answer. */
    suspend fun unregister(token: String): DeleteResult
}

internal enum class RegisterOutcome {
    REGISTERED,
    POST_FAILED,
    NOT_ELIGIBLE,
    NO_TOKEN,

    /** A sign-out held registration at admission, or began since this evaluation was admitted. */
    HELD,

    /** The session is no longer the one this evaluation was for. */
    STALE,
    LEDGER_UNAVAILABLE,

    /** The key to register still owes a DELETE that has not resolved. */
    SAME_KEY_OWED
}

/**
 * Serialises push registration against sign-out teardown over the [PushRegistrationLedger].
 *
 * Registration and teardown share one network lock, so a sign-out waits for a POST already
 * admitted and deletes after it. A sign-out raises a hold and a revision before it first
 * suspends: nothing is admitted while the hold stands, and an evaluation admitted earlier finds
 * the revision moved and stops before its POST. Admission is the last state check before the POST.
 *
 * Owed DELETEs are resolved before any eligibility or token question, since a DELETE needs
 * neither premium nor a token. Only an obligation on the key being registered blocks its POST;
 * one on another key, or another uid, never does — the server keys a registration by token and
 * moves it to whoever registers that token.
 *
 * What counts as owed:
 *  - an OWED entry in the ledger;
 *  - a `(uid, token)` whose recording failed, kept in memory with the revision that found it;
 *  - while a sign-out's `markOwed` failed to land, that uid's entries from other sessions.
 * An entry recorded by an earlier session, with no such mark, is not reused as this session's
 * registration, but a new POST of the same key replaces it under a fresh id: the server upserts
 * the same row. That does not execute or confirm the earlier session's DELETE, and a POST that
 * fails or stops leaves the old row. Memory does not survive the process.
 */
internal class PushRegistrationCoordinator(
    private val ledger: PushRegistrationLedger,
    private val server: PushDeviceServer,
    private val currentFence: () -> AuthIdentityFence?,
    private val eligible: (AuthIdentityFence) -> Boolean,
    private val deviceToken: suspend () -> String?,
    private val clearLegacyLocalState: () -> Unit
) {
    private val network = Mutex()
    private val lock = Any()

    // Guarded by [lock]; never held across a suspension.
    private var holds = 0
    private var revision = 0L
    private var session: AuthIdentityFence? = null
    private val sessionIds = mutableSetOf<Long>()
    private val memory = UnrecordedObligations()

    /**
     * Resolves [owner]'s owed DELETEs, then registers the device for [owner] when eligible.
     * [knownToken] skips fetching one; the owner and revision are captured before any fetch.
     */
    suspend fun register(owner: AuthIdentityFence, knownToken: String? = null): RegisterOutcome {
        currentCoroutineContext().ensureActive()
        val admitted = synchronized(lock) {
            if (holds > 0) return RegisterOutcome.HELD
            revision
        }
        network.withLock {
            refusal(owner, admitted)?.let { return it }
            val entries = readOrNull() ?: return RegisterOutcome.LEDGER_UNAVAILABLE
            resolveOwed(owner, entries)
        }
        if (!eligible(owner)) return RegisterOutcome.NOT_ELIGIBLE
        val token = knownToken ?: deviceToken() ?: return RegisterOutcome.NO_TOKEN
        network.withLock {
            refusal(owner, admitted)?.let { return it }
            val entries = readOrNull() ?: return RegisterOutcome.LEDGER_UNAVAILABLE
            if (owesDelete(owner.uid, token, entries)) return RegisterOutcome.SAME_KEY_OWED
            entries.filter { it.uid == owner.uid && it.token != token && it.state == PushRegistrationState.MAY_EXIST }
                .forEach { retire(owner, it) }
            val recorded = try {
                ledger.recordMayExist(owner.uid, token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return RegisterOutcome.LEDGER_UNAVAILABLE
            } ?: return RegisterOutcome.SAME_KEY_OWED
            synchronized(lock) { if (session == owner) sessionIds += recorded.id }
            val call = server.session(owner) ?: return RegisterOutcome.POST_FAILED
            // Admission: the last check before the POST, after the credential's wait too.
            refusal(owner, admitted)?.let { return it }
            if (!eligible(owner)) return RegisterOutcome.NOT_ELIGIBLE
            currentCoroutineContext().ensureActive()
            return if (call.register(token)) RegisterOutcome.REGISTERED else RegisterOutcome.POST_FAILED
        }
    }

    /**
     * Tears down [owner]'s registrations for a sign-out. Known targets are deleted without
     * waiting for this install's current token, which is fetched alongside and recorded owed as
     * soon as it arrives. A cancelled call starts no further network work, keeps what it owes and
     * rethrows. The legacy local state is cleared however it ends, and the hold always drops.
     */
    suspend fun unregister(owner: AuthIdentityFence) {
        val raised = synchronized(lock) {
            holds += 1
            revision += 1
            revision
        }
        try {
            var cleared = false
            try {
                coroutineScope {
                    val pass = Pass()
                    val current = async { recordCurrentToken(owner.uid, raised) { it in pass.deleted } }
                    val marked = try {
                        ledger.markOwed(owner.uid)
                    } catch (failure: Exception) {
                        memory.markUid(owner.uid, raised)
                        if (failure is CancellationException) throw failure
                        null
                    }
                    network.withLock {
                        val known = marked ?: readOrNull()?.filter { it.uid == owner.uid }.orEmpty()
                        known.forEach { resolveOnce(owner, it, pass) }
                        memory.tokensFor(owner.uid).forEach { resolveRememberedOnce(owner, it, pass) }
                        // The current token is usually a known target: tried and failed, it waits for
                        // the next pass; deleted, only an entry recreated since is left to resolve.
                        current.await()?.let { token ->
                            if (token !in pass.attempted || token in pass.deleted) resolveCurrentToken(owner, token)
                        }
                        clearLegacyLocalState()
                        cleared = true
                    }
                }
            } finally {
                if (!cleared) clearLegacyLocalState()
            }
        } finally {
            synchronized(lock) {
                holds -= 1
                if (session == owner) {
                    session = null
                    sessionIds.clear()
                }
            }
        }
    }

    /**
     * Refusal before the network, or null to go on; also moves the session to [owner]. A hold
     * always moves the revision, so one raised since admission shows here; one standing at
     * admission was refused before it.
     */
    private fun refusal(owner: AuthIdentityFence, admitted: Long): RegisterOutcome? = synchronized(lock) {
        when {
            currentFence() != owner -> RegisterOutcome.STALE
            revision != admitted -> RegisterOutcome.HELD
            else -> {
                if (session != owner) {
                    session = owner
                    sessionIds.clear()
                }
                null
            }
        }
    }

    private suspend fun readOrNull(): List<PushLedgerEntry>? = try {
        ledger.entries()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /**
     * Every obligation of [owner]'s uid, whatever key; a failure keeps it and blocks nothing else.
     * A failed mark is read before the work, so a sign-out that fails meanwhile keeps its own.
     */
    private suspend fun resolveOwed(owner: AuthIdentityFence, entries: List<PushLedgerEntry>) {
        val markedAt = memory.uidMark(owner.uid)
        val pass = Pass()
        entries.filter { it.uid == owner.uid && owed(it) }.forEach { resolveOnce(owner, it, pass) }
        memory.tokensFor(owner.uid).forEach { resolveRememberedOnce(owner, it, pass) }
        if (markedAt != null) releaseMarkIfClear(owner.uid, markedAt)
    }

    private fun owed(entry: PushLedgerEntry): Boolean = synchronized(lock) {
        entry.state == PushRegistrationState.OWED ||
            (memory.uidMark(entry.uid) != null && entry.id !in sessionIds)
    }

    private fun owesDelete(uid: String, token: String, entries: List<PushLedgerEntry>): Boolean {
        val stored = entries.firstOrNull { it.uid == uid && it.token == token }
        return memory.tokenMark(uid, token) != null || (stored != null && owed(stored))
    }

    /** DELETE [entry]; an answer that resolves it completes the entry, conditionally. True once completed. */
    private suspend fun resolve(owner: AuthIdentityFence, entry: PushLedgerEntry): Boolean =
        when (delete(owner, entry.token)) {
            DeleteResult.DELETED, DeleteResult.ABSENT_FOR_UID -> completeOrKeep(entry)
            DeleteResult.FAILED -> {
                // A mark that did not land leaves the entry MAY_EXIST on disk: record it owed now.
                if (entry.state == PushRegistrationState.MAY_EXIST) recordOwedOrRemember(entry.uid, entry.token)
                false
            }
        }

    /**
     * A rotated or earlier-session key: owed first, then deleted. Never blocks the current POST.
     * Its entry resolves only by a conditional completion — the owed record, or the entry as it
     * was when that record did not land — and only then does memory let the key go.
     */
    private suspend fun retire(owner: AuthIdentityFence, entry: PushLedgerEntry) {
        val owedEntry = recordOwedOrRemember(entry.uid, entry.token)
        val at = if (owedEntry == null) memory.tokenMark(entry.uid, entry.token) else null
        when (delete(owner, entry.token)) {
            DeleteResult.DELETED, DeleteResult.ABSENT_FOR_UID ->
                if (completeOrKeep(owedEntry ?: entry)) memory.forget(entry.uid, entry.token, at)
            DeleteResult.FAILED -> Unit
        }
    }

    /**
     * This install's token, by what is owed for it now rather than by the known targets: its
     * recording can land after they were resolved, recreating the entry for a key just deleted.
     */
    private suspend fun resolveCurrentToken(owner: AuthIdentityFence, token: String) {
        val stored = readOrNull()?.firstOrNull { it.uid == owner.uid && it.token == token }
        val at = memory.tokenMark(owner.uid, token)
        when {
            stored != null -> if (resolve(owner, stored)) memory.forget(owner.uid, token, at)
            at != null -> resolveRemembered(owner, owner.uid to token, Pass())
            else -> Unit
        }
    }

    /** A remembered token confirmed absent from the ledger: its DELETE alone resolves it. */
    private suspend fun resolveUnrecorded(owner: AuthIdentityFence, key: Pair<String, String>): Boolean {
        val at = memory.tokenMark(key.first, key.second)
        return when (delete(owner, key.second)) {
            DeleteResult.DELETED, DeleteResult.ABSENT_FOR_UID -> {
                memory.forget(key.first, key.second, at)
                true
            }
            DeleteResult.FAILED -> false
        }
    }

    /**
     * One resolution pass: each pass deletes a token at most once. A current-token entry recorded
     * again after its DELETE may be resolved once more, outside the pass.
     */
    private class Pass {
        val attempted: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
        val deleted: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    }

    /**
     * A deleted key is resolved in memory as well, for the revision read before its DELETE. The
     * entries of one uid never repeat a token; the memory pass after them is what skips one.
     */
    private suspend fun resolveOnce(owner: AuthIdentityFence, entry: PushLedgerEntry, pass: Pass) {
        pass.attempted += entry.token
        val at = memory.tokenMark(entry.uid, entry.token)
        if (resolve(owner, entry)) {
            pass.deleted += entry.token
            memory.forget(entry.uid, entry.token, at)
        }
    }

    private suspend fun resolveRememberedOnce(owner: AuthIdentityFence, key: Pair<String, String>, pass: Pass) {
        if (key.second in pass.attempted) return
        resolveRemembered(owner, key, pass)
    }

    /**
     * A remembered token goes through the ledger whenever it can. Recorded owed again — storage may
     * have recovered — it leaves memory for the revision read first, and the entry resolves by its
     * conditional completion. Unrecordable but already in the ledger, the entry resolves the same
     * way. Only a token confirmed absent from the ledger resolves on its DELETE alone. A read that
     * fails decides nothing, and the token stays remembered.
     */
    private suspend fun resolveRemembered(owner: AuthIdentityFence, key: Pair<String, String>, pass: Pass) {
        val at = memory.tokenMark(key.first, key.second) ?: return
        val owedEntry = try {
            ledger.recordOwed(key.first, key.second)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (owedEntry != null) {
            memory.forget(key.first, key.second, at)
            resolveOnce(owner, owedEntry, pass)
            return
        }
        val entries = readOrNull() ?: return
        val stored = entries.firstOrNull { it.uid == key.first && it.token == key.second }
        if (stored != null) {
            resolveOnce(owner, stored, pass)
            return
        }
        pass.attempted += key.second
        if (resolveUnrecorded(owner, key)) pass.deleted += key.second
    }

    /**
     * No new server call once the caller is cancelled: checked after the credential's wait, right
     * before the call; what is owed stays for the next pass. No credential, no answer.
     */
    private suspend fun delete(owner: AuthIdentityFence, token: String): DeleteResult {
        val call = server.session(owner) ?: return DeleteResult.FAILED
        currentCoroutineContext().ensureActive()
        return call.unregister(token)
    }

    /** Completion that throws, or finds the entry moved on, is not a resolution. */
    private suspend fun completeOrKeep(entry: PushLedgerEntry): Boolean = try {
        ledger.complete(entry)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun recordOwedOrRemember(uid: String, token: String): PushLedgerEntry? {
        val at = memory.tokenMark(uid, token)
        return try {
            ledger.recordOwed(uid, token).also { memory.forget(uid, token, at) }
        } catch (failure: Exception) {
            memory.remember(uid, token, synchronized(lock) { revision })
            if (failure is CancellationException) throw failure
            null
        }
    }

    /**
     * This install's token, recorded owed the moment it is known unless [deletedAlready] says this
     * sign-out deleted it; null when it could not be had. A record racing that DELETE can still
     * recreate the entry, which [resolveCurrentToken] then deletes once more.
     */
    private suspend fun recordCurrentToken(uid: String, raised: Long, deletedAlready: (String) -> Boolean): String? {
        val token = try {
            deviceToken()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        if (deletedAlready(token)) return token
        try {
            ledger.recordOwed(uid, token)
        } catch (failure: Exception) {
            memory.remember(uid, token, raised)
            if (failure is CancellationException) throw failure
        }
        return token
    }

    /** A failed mark stands until none of that uid's earlier-session entries is left MAY_EXIST. */
    private suspend fun releaseMarkIfClear(uid: String, markedAt: Long) {
        val entries = readOrNull() ?: return
        val stillCovered = synchronized(lock) {
            entries.any { it.uid == uid && it.state == PushRegistrationState.MAY_EXIST && it.id !in sessionIds }
        }
        if (!stillCovered) memory.releaseUid(uid, markedAt)
    }
}

/**
 * What a sign-out owes that the ledger could not be made to hold. Lives only as long as the
 * process. Each mark carries the revision that set it, and is released only for the revision read
 * before the work that resolved it, so an older resolution never clears a newer failure's mark.
 */
internal class UnrecordedObligations {
    private val uidMarks = mutableMapOf<String, Long>()
    private val tokens = mutableMapOf<Pair<String, String>, Long>()

    /** [uid]'s entries from other sessions owe a DELETE: its `markOwed` did not land. */
    /** A record that arrives late keeps the newer revision already there. */
    @Synchronized
    fun markUid(uid: String, revision: Long) {
        uidMarks[uid] = maxOf(uidMarks[uid] ?: revision, revision)
    }

    @Synchronized
    fun uidMark(uid: String): Long? = uidMarks[uid]

    @Synchronized
    fun releaseUid(uid: String, at: Long) {
        if (uidMarks[uid] == at) uidMarks.remove(uid)
    }

    /** [uid]'s registration of [token] owes a DELETE the ledger does not record. */
    @Synchronized
    fun remember(uid: String, token: String, revision: Long) {
        tokens[uid to token] = maxOf(tokens[uid to token] ?: revision, revision)
    }

    @Synchronized
    fun tokenMark(uid: String, token: String): Long? = tokens[uid to token]

    @Synchronized
    fun forget(uid: String, token: String, at: Long?) {
        if (at != null && tokens[uid to token] == at) tokens.remove(uid to token)
    }

    @Synchronized
    fun tokensFor(uid: String): List<Pair<String, String>> = tokens.keys.filter { it.first == uid }
}
