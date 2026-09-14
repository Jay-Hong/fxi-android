package com.jay.fxi.data.entitlements

import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence

/**
 * Why an axis cannot be used right now (L-4e E1).
 *
 * Several can hold at once and none is ranked above another: a consumer that needs to know whether a hold or a seal is what
 * stands in the way reads the whole set, never a single "reason".
 */
internal enum class TopicAccessBlock {
    /** A sign-out attempt is open and admits no access queries. */
    ATTEMPT_OPEN,

    /** An identity persistence hold is unresolved. */
    PERSISTENCE_HOLD,

    /** The decided state is not a premium grant (USER) / the capability is not visible (CAPABILITY). */
    NOT_GRANTED,

    /** No binding is published. */
    NO_BINDING,

    /** No record has been confirmed yet, so the context cannot be compared. */
    NO_CONFIRMED_RECORD,

    /** The confirmed record belongs to someone other than the binding. */
    OWNER_MISMATCH,

    /** A loss answer whose record could not be read holds this axis (S1r-2c). */
    LOSS_CANDIDATE,

    /** An explicit loss whose rotation this process could not establish seals this axis (S1r-2b). */
    EXPLICIT_SEAL,

    /** The confirmed record of this owner has no epoch on this axis. */
    DERIVED_SEAL,

    /** A loss rotation that can change this axis's epoch is running, or the decision it serves is not yet published. */
    CONTEXT_UNCERTAIN
}

/** What ended access on an axis. Kept apart from the blocks: an end outlives the hold or seal that may follow it. */
internal enum class TopicAccessEndReason {
    /** The server's stable false or typed premium refusal was decided (D23). */
    AUTHORITATIVE_LOSS,

    /** A decided loss required capability epoch rotation; this does not establish that the write completed. */
    CAPABILITY_REVOKED,

    /** A session ended: sign-out, or a sign-out being prepared. */
    SIGNED_OUT,

    /** An identity binding boundary or recovery barrier began; persistence may still be unresolved. */
    IDENTITY_CHANGED,

    /** An unverified-start boundary invalidated access; namespace retirement is decided and reconciled separately. */
    UNVERIFIED_START,

    /** An explicit loss could not be established and sealed the axis. */
    SEALED
}

/**
 * One end of access on an axis: the binding it ended for, and the owner and namespace the event itself named.
 *
 * [sequence] only grows. A hold released afterwards, or a hold added afterwards, does not replace the record of an end.
 * [namespace] is null when the event did not establish one for that owner — it is never filled from another owner's record.
 */
internal data class TopicAccessEnd(
    val sequence: Long,
    val reason: TopicAccessEndReason,
    val binding: EntitlementsIdentity?,
    val ownerUid: String?,
    val namespace: String?
)

/**
 * What the issuer knows about topic access at one moment, read from memory only.
 *
 * No live identity and no disk: the session checks the live identity itself, and [recordFence] is the last record this
 * coordinator confirmed. [tokenStanding] is true only when the last issued token's context equals the current binding,
 * confirmed record fence and decision generation, and no loss rotation of the user axis is waiting for its decision to be
 * published.
 */
internal data class TopicAccessFacts(
    val token: TopicGrantToken?,
    val issuedFor: TopicGrantContext?,
    val binding: EntitlementsIdentity?,
    val recordFence: AccessFence?,
    val decisionGeneration: Long,
    val tokenStanding: Boolean,
    val userBlocks: Set<TopicAccessBlock>,
    val capabilityBlocks: Set<TopicAccessBlock>,
    val recordUnconfirmed: Boolean,
    val userContextUncertain: Boolean,
    val capabilityContextUncertain: Boolean
) {
    val userAllowed: Boolean get() = userBlocks.isEmpty()

    /** The capability on its own. A KRX use also needs the user axis and the token; this does not fold them in. */
    val capabilityAllowed: Boolean get() = capabilityBlocks.isEmpty()

    companion object {
        val NONE = TopicAccessFacts(
            token = null,
            issuedFor = null,
            binding = null,
            recordFence = null,
            decisionGeneration = 0L,
            tokenStanding = false,
            userBlocks = setOf(TopicAccessBlock.NO_BINDING, TopicAccessBlock.NO_CONFIRMED_RECORD, TopicAccessBlock.NOT_GRANTED),
            capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED),
            recordUnconfirmed = false,
            userContextUncertain = false,
            capabilityContextUncertain = false
        )
    }
}

/**
 * The issuer's published topic access, replaced whole on every change (L-4e E1, `ANDROID_V2_PLAN.md` 동결 후 12번).
 *
 * Published before any state flow that shows the same change, so a reader that has seen a withdrawal never reads an
 * older allowance here. [revision] moves only when [facts] or the history moved; an unchanged recomputation publishes
 * nothing, so a consumer that pulls on a revision change does not loop.
 *
 * [userInvalidations] counts transitions that took the user axis or the standing token away. It is the use-lifetime
 * marker a session keeps with each request: a hold that came and went between a request and its answer leaves it
 * changed even though the token is the same again.
 */
internal data class TopicAccessSnapshot(
    val revision: Long,
    val facts: TopicAccessFacts,
    val userInvalidations: Long,
    val lastUserEnd: TopicAccessEnd?,
    val lastCapabilityEnd: TopicAccessEnd?
) {
    companion object {
        val INITIAL = TopicAccessSnapshot(
            revision = 0L,
            facts = TopicAccessFacts.NONE,
            userInvalidations = 0L,
            lastUserEnd = null,
            lastCapabilityEnd = null
        )
    }
}

/** A topic grant answer and the snapshot published by the same lock hold that produced it. */
internal data class TopicGrantResult(val fence: TopicSessionFence?, val snapshot: TopicAccessSnapshot)
