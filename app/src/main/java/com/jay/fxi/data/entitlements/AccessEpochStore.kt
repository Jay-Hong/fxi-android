package com.jay.fxi.data.entitlements

import java.util.UUID

/** Which namespace an outstanding purge still owes work to. */
enum class PurgeScope { USER, CAPABILITY }

/** Generates a fresh namespace id. Injected so tests are deterministic. */
fun interface EpochIdGenerator {
    fun next(): String

    companion object {
        val Random = EpochIdGenerator { UUID.randomUUID().toString() }
    }
}

/**
 * One superseded namespace still owed a purge.
 *
 * Entries accumulate: a rotation while an earlier purge is still outstanding appends rather than
 * overwrites. Collapsing them into a single "oldest" record loses every namespace in between —
 * an account switch chain A → B → C would purge A, clear the journal, and strand B.
 *
 * The owner is the user whose data this is, which after a switch is *not* whoever is signed in
 * now.
 *
 * [scopes] decides which axes this entry covers; an axis it does not name is not cleaned. Within a
 * covered axis a null epoch means that epoch is **unknown**, which is not the same as nothing to
 * do: the obligation is then every past namespace of that axis except the live one. A null owner
 * means the target cannot be narrowed to one uid. Both happen when the record itself was lost.
 */
data class PendingPurge(
    val ownerUid: String?,
    val userAccessEpoch: String?,
    val krxCapabilityEpoch: String?,
    val scopes: Set<PurgeScope>
)

/**
 * Durable cleanup provenance.
 *
 * `ANDROID_V2_PLAN.md` I4: both epochs are opaque UUIDs in backup-excluded local storage, are
 * never reused or reset to a sentinel, and a teardown persists the new id together with the purge
 * journal atomically *before* anything else happens. They are provenance, not permission — a
 * marker never grants anything, it records that the *current* namespace may hold data.
 *
 * Markers describe the live namespace only. A rotation hands the old namespace to a journal entry
 * and resets the corresponding marker, so a later completion cannot clear a marker that belongs to
 * data written after the rotation.
 */
data class AccessEpochRecord(
    val ownerUid: String? = null,
    val userAccessEpoch: String? = null,
    val krxCapabilityEpoch: String? = null,
    val mayContainPremiumData: Boolean = false,
    val mayContainKrxData: Boolean = false,
    val pendingPurges: List<PendingPurge> = emptyList(),
    /**
     * The owner whose sign-out was decided but has not landed yet.
     *
     * Without a persisted intent, a sign-out whose rotation edit never became durable leaves
     * this record indistinguishable from one in which that sign-out was not attempted. An
     * exception or cancellation alone does not establish whether the edit became durable;
     * recovery must inspect the persisted record.
     *
     * This intent must be persisted before the rotation edit. `ANDROID_V2_PLAN.md` §S1 asks for
     * a logout's journal to materialize before the first protected use even across process death.
     *
     * Keyed on the uid alone, deliberately. The namespace to tear down is whatever the record
     * holds while that uid still owns it, and the epochs can legitimately move in between — a KRX
     * revoke rotates one axis on its own — so matching on the epochs recorded at decision time
     * would skip a teardown that is still owed. Settling and rotating happen in the same edit, so a
     * crash cannot leave the marker set on a namespace that was already retired.
     *
     * Not an access fact: [toSnapshotFacts] and [fence] deliberately do not read it.
     */
    val teardownOwedFor: String? = null
) {
    fun toSnapshotFacts(
        state: PremiumAccessState,
        krx: KrxCapabilityState
    ): AccessSnapshot = AccessSnapshot(
        state = state,
        krx = krx,
        mayContainPremiumData = mayContainPremiumData,
        mayContainKrxData = mayContainKrxData,
        pendingUserPurge = pendingPurges.any { PurgeScope.USER in it.scopes },
        pendingCapabilityPurge = pendingPurges.any { PurgeScope.CAPABILITY in it.scopes }
    )

    /** The identity an in-flight request is bound to. */
    fun fence(): AccessFence = AccessFence(ownerUid, userAccessEpoch, krxCapabilityEpoch)
}

/**
 * Identity a request captured when it started.
 *
 * I4: "이전 UID/epoch에서 출발한 응답은 새 세션에 반영하지 않는다". This covers namespace changes.
 * It is not sufficient on its own — a rejection from a no-grant state rotates nothing, so the
 * coordinator additionally carries a loss generation and the request's real authenticated owner.
 */
data class AccessFence(
    val ownerUid: String?,
    val userAccessEpoch: String?,
    val krxCapabilityEpoch: String?
)

/**
 * Pure record semantics, kept out of the Android-dependent store so they can be unit tested and
 * so a test fake and the production store cannot drift apart — both call these functions.
 */
object AccessEpochTransitions {

    /**
     * Mints new ids for the requested axes and appends a journal entry for the superseded ones.
     *
     * The entry absorbs the markers of the axes it retired and the live markers reset, so a
     * completion later clears exactly the namespace it purged and nothing written since.
     */
    fun rotate(
        record: AccessEpochRecord,
        rotateUser: Boolean,
        rotateKrx: Boolean,
        ids: EpochIdGenerator,
        purgedOwnerUid: String? = record.ownerUid
    ): AccessEpochRecord {
        if (!rotateUser && !rotateKrx) return record
        val scopes = buildSet {
            if (rotateUser) add(PurgeScope.USER)
            if (rotateKrx) add(PurgeScope.CAPABILITY)
        }
        val entry = PendingPurge(
            ownerUid = purgedOwnerUid,
            userAccessEpoch = record.userAccessEpoch.takeIf { rotateUser },
            krxCapabilityEpoch = record.krxCapabilityEpoch.takeIf { rotateKrx },
            scopes = scopes
        )
        return record.copy(
            userAccessEpoch = if (rotateUser) ids.next() else record.userAccessEpoch,
            krxCapabilityEpoch = if (rotateKrx) ids.next() else record.krxCapabilityEpoch,
            mayContainPremiumData = record.mayContainPremiumData && !rotateUser,
            mayContainKrxData = record.mayContainKrxData && !rotateKrx,
            pendingPurges = record.pendingPurges + entry
        )
    }

    /** Allocates the first namespace for an owner that has none. Not a teardown. */
    fun ensureNamespace(record: AccessEpochRecord, ids: EpochIdGenerator): AccessEpochRecord =
        record.copy(
            userAccessEpoch = record.userAccessEpoch ?: ids.next(),
            krxCapabilityEpoch = record.krxCapabilityEpoch ?: ids.next()
        )

    /**
     * A different owner is a namespace change.
     *
     * Both axes are journalled even when the markers looked clean: purging an already-empty
     * namespace is harmless, missing one is not.
     *
     * An unowned namespace is retired too when a marker stands. The record does not establish who
     * owns the data in a namespace, so it is not attributed to the arriving uid: both axes rotate
     * and the journal keeps a null owner. The markers say only that protected data may exist; they
     * are not evidence of a write, nor of a sign-out having happened.
     */
    fun bindOwner(
        record: AccessEpochRecord,
        uid: String,
        ids: EpochIdGenerator
    ): AccessEpochRecord {
        // A sign-out of this same uid that never landed has to land before the uid can bind again,
        // or the same-owner branch below would hand it the namespace it was leaving. For any other
        // uid the owner change retires that namespace anyway, so the marker only has to go.
        val base = if (record.teardownOwedFor == uid) {
            settleOwedTeardown(record, ids)
        } else {
            record.copy(teardownOwedFor = null)
        }
        if (base.ownerUid == uid) return ensureNamespace(base, ids)
        val previousOwner = base.ownerUid
        val rotated = when {
            previousOwner != null ->
                rotate(base, rotateUser = true, rotateKrx = true, ids = ids, purgedOwnerUid = previousOwner)
            // No owner, yet a marker says protected data may sit in this namespace. The marker
            // proves neither a write nor a past sign-out, and the record does not establish whose
            // data it is, so the entry is journalled with no owner rather than the arriving uid.
            base.mayContainPremiumData || base.mayContainKrxData ->
                rotate(base, rotateUser = true, rotateKrx = true, ids = ids, purgedOwnerUid = null)
            // Nothing to retire. A first install gets its namespace from ensureNamespace, which is
            // an allocation and not a rotation.
            else -> base
        }
        return ensureNamespace(rotated.copy(ownerUid = uid), ids)
    }

    /**
     * Records that the sign-out of [uid] has been decided, ahead of the edit that performs it.
     *
     * [uid] is the account captured by the caller. A non-owner request returns the record
     * unchanged. The caller must require both ownerUid and teardownOwedFor in the returned
     * record to equal [uid]. This function has no auth state; the caller must also validate the
     * captured auth generation before persisting the intent.
     *
     * Changes only teardownOwedFor; epochs, purge entries and mayContain flags stay unchanged.
     * Repeating this function before the teardown lands is idempotent. Once persisted, the
     * intent remains owed if the Firebase operation is abandoned. The caller must keep
     * protected access blocked and arrange settlement independently of another auth event.
     *
     * A sign-out that landed leaves no owner ([signOut]), so this cannot arm on top of one until a
     * binding names an owner again. That bound state is indistinguishable from any other, so the
     * guarantee ends there: an end event still has to carry its whole fence, keep its FIFO place and
     * consume its receipt. This function only stops a landed sign-out from being re-decided while
     * nobody is bound.
     */
    fun beginSignOut(record: AccessEpochRecord, uid: String): AccessEpochRecord {
        if (record.ownerUid != uid) return record
        return record.copy(teardownOwedFor = uid)
    }

    /**
     * Lands a sign-out that was decided but never landed.
     *
     * Owed only while the marker names the current owner; a marker naming anyone else was already
     * covered by the owner change that replaced them, so it is dropped without rotating again.
     * [beginSignOut] never arms a non-owner, so that branch is reachable only from a record built
     * some other way — kept as a pin, because a stale marker must never become a rotation.
     */
    fun settleOwedTeardown(record: AccessEpochRecord, ids: EpochIdGenerator): AccessEpochRecord {
        val owed = record.teardownOwedFor ?: return record
        if (owed != record.ownerUid) return record.copy(teardownOwedFor = null)
        return signOut(record, ids)
    }

    /**
     * Sign-out is a teardown, not just a memory reset.
     *
     * The plan lists logout with fresh-false and typed reject as an event that rotates and
     * journals. Leaving the namespace live would let the next sign-in of the same uid inherit
     * protected data that was never re-authorised.
     *
     * Landing also gives up the owner, in the same record as the rotation. That is what a caller
     * reads to see there is nothing left to tear down here: [beginSignOut] cannot arm and
     * `planEnd` cannot rotate until something binds an owner again.
     */
    fun signOut(record: AccessEpochRecord, ids: EpochIdGenerator): AccessEpochRecord {
        // Cleared in the same record the rotation returns, so the marker and the teardown it
        // stands for land together or not at all.
        val landed = record.copy(teardownOwedFor = null)
        if (record.ownerUid == null) return landed
        // The owner goes with them. The journal entry above already names whose namespace is being
        // cleaned, so nothing needs the live field to remember it, and leaving it behind is what let
        // a landed sign-out arm again. Direction matters: a landed sign-out leaves no owner, but no
        // owner does not identify a landed sign-out — a fresh install has none either.
        return rotate(landed, rotateUser = true, rotateKrx = true, ids = ids, purgedOwnerUid = record.ownerUid)
            .copy(ownerUid = null)
    }

    /**
     * What a cold start owes when its first identity observation is no uid (plan amendment 6).
     *
     * This is not a sign-out being inferred. Nothing here can tell a sign-out that never reached the
     * record from a restore error, so the namespace is retired because its continuity with the
     * starting identity cannot be shown, and the previous owner's stays unreachable either way.
     *
     * - An owner: the same record a landed sign-out leaves (both axes, journal names the owner).
     * - No owner, a marker standing: both axes, with the owner left unknown in the journal — the
     *   rule [bindOwner] already applies to that record. Null epochs are journalled as they are: an
     *   entry's null epoch means "unknown", not "nothing to purge" ([PendingPurge]).
     * - No owner, no marker, an intent left behind: the stale intent is dropped, as
     *   [settleOwedTeardown] drops one that names nobody bound.
     * - Otherwise unchanged. Journal entries already owed are not touched here; the startup resume
     *   and the cleanup behind a landing own them.
     *
     * Decided on the record passed in, which a store must read inside the same atomic edit: a marker
     * set between the caller's read and this edit then decides the edit, and the landing check
     * recognises the rotation it caused.
     */
    fun retireUnverifiedStart(record: AccessEpochRecord, ids: EpochIdGenerator): AccessEpochRecord = when {
        record.ownerUid != null -> signOut(record, ids)
        record.mayContainPremiumData || record.mayContainKrxData ->
            rotate(record.copy(teardownOwedFor = null), rotateUser = true, rotateKrx = true, ids = ids, purgedOwnerUid = null)
        record.teardownOwedFor != null -> record.copy(teardownOwedFor = null)
        else -> record
    }

    /** Removes exactly the entries that were purged. Others stay owed. */
    fun completePurges(
        record: AccessEpochRecord,
        completed: Collection<PendingPurge>
    ): AccessEpochRecord {
        if (completed.isEmpty()) return record
        val remaining = record.pendingPurges.toMutableList()
        completed.forEach { remaining.remove(it) }
        return record.copy(pendingPurges = remaining.toList())
    }

    fun markMayContainData(
        record: AccessEpochRecord,
        premium: Boolean,
        krx: Boolean
    ): AccessEpochRecord = record.copy(
        mayContainPremiumData = record.mayContainPremiumData || premium,
        mayContainKrxData = record.mayContainKrxData || krx
    )

    val ALL_SCOPES: Set<PurgeScope> = setOf(PurgeScope.USER, PurgeScope.CAPABILITY)
}

/**
 * Persistence for [AccessEpochRecord].
 *
 * Every mutation must be atomic: a crash leaves either the whole prior record or the whole new
 * one. The three-step teardown order the plan requires — persist new epoch and journal, purge the
 * old namespace, then clear that journal entry — is only crash-safe if step one cannot half-land.
 *
 * This file must be excluded from Android backup. Restoring an epoch or a marker onto a different
 * device would point cleanup at a namespace that never existed there.
 */
interface AccessEpochStore {
    suspend fun load(): AccessEpochRecord

    /** Binds the record to [uid], allocating or rotating the namespace as required. */
    suspend fun bindOwner(uid: String): AccessEpochRecord

    /** Rotates and journals on sign-out. */
    suspend fun signOut(): AccessEpochRecord

    /**
     * Settles a cold start whose first observation is no uid; see
     * [AccessEpochTransitions.retireUnverifiedStart]. Must decide on the record read inside the same
     * atomic edit, not on one the caller read earlier.
     */
    suspend fun retireUnverifiedStart(): AccessEpochRecord

    /**
     * Persists that the sign-out of [uid] has been decided; see [AccessEpochTransitions.beginSignOut].
     * The caller must require both ownerUid and teardownOwedFor in the returned record to equal
     * [uid] before going on.
     */
    suspend fun beginSignOut(uid: String): AccessEpochRecord

    /**
     * Step one of teardown: mint new ids for the requested axes and append the journal entry,
     * atomically. Returns the new record. No purge has run yet.
     */
    suspend fun beginRotation(rotateUser: Boolean, rotateKrx: Boolean): AccessEpochRecord

    /** Step three: drop exactly the journal entries whose purge actually completed. */
    suspend fun completePurges(completed: Collection<PendingPurge>): AccessEpochRecord

    /**
     * Records that protected data may now exist in the *current* namespace. Must be persisted
     * before the first such write, otherwise a crash leaves data with no marker pointing at it.
     */
    suspend fun markMayContainData(premium: Boolean = false, krx: Boolean = false): AccessEpochRecord
}
