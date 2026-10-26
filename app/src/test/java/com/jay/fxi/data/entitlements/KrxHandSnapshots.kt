package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime

/**
 * S-1 (S6-2b-1b design r5 §13 T.2, T.3, T.14): hand-built topic access snapshots shared by the α rows of the use authority, the graph
 * gate, the graph coordinator and the session. The private builders they follow — SnapshotTopicUseAuthorityTest:22-36,
 * GraphV2AccessGateTest:63-94 and GraphV2RequestCoordinatorCacheTest:107-140 — stay as they are.
 *
 * Both builders default `krxPermitSeq` to 1 (T.0). The issuer publishes 0 only while the contest is off (PremiumAccessCoordinator
 * publishAccessSnapshotLocked), so an ON row left at the snapshot's own default 0 would put every consumer guard on its HEAD answer and
 * prove nothing. An OFF row passes 0 on purpose. `capabilityInvalidations` defaults to 0, the value the issuer publishes off and before
 * the first allowed-to-blocked transition on.
 *
 * Fixtures keep the issuer's invariants a row does not name: a context-uncertain block comes with its flag, and [a1Snap] derives
 * standing the way the issuer does. Neither adds a block a row did not ask for — a derived seal for a null record epoch, a missing record
 * or the contest's own block — so a row can isolate one block; it does not claim the issuer publishes that exact combination.
 */
internal object KrxHandSnapshots {
    val IDENTITY = AuthIdentityFence("u1", 1L)
    val BINDING = EntitlementsIdentity("u1", 1L)
    const val USER_EPOCH = "U1"
    val FENCE7 = TopicSessionFence(IDENTITY, USER_EPOCH, TopicGrantToken(7L))
    val LIFETIME7 = TopicUseLifetime(TopicGrantToken(7L), 3L)

    /** The binding's confirmed record with capability epoch [krxEpoch]. */
    fun record(krxEpoch: String?): AccessFence = AccessFence(BINDING.ownerUid, USER_EPOCH, krxEpoch)

    val RECORD_K1 = record("K1")
    val RECORD_K2 = record("K2")
}

/**
 * A snapshot whose standing a row states itself ([standing]), for the rows that judge one snapshot at a time (T.2: token, standing,
 * the two block sets, user invalidations, record epoch, permit, capability invalidations). [recordEpoch] is the confirmed record's
 * capability epoch, the one a send marker carries; [recordConfirmed] false publishes no record at all.
 */
internal fun krxSnap(
    token: Long? = 7L,
    standing: Boolean = true,
    userBlocks: Set<TopicAccessBlock> = emptySet(),
    capabilityBlocks: Set<TopicAccessBlock> = emptySet(),
    userInvalidations: Long = 3L,
    recordEpoch: String? = "K1",
    recordConfirmed: Boolean = true,
    krxPermitSeq: Long = 1L,
    capabilityInvalidations: Long = 0L,
    revision: Long = 100L
): TopicAccessSnapshot = TopicAccessSnapshot.INITIAL.copy(
    revision = revision,
    facts = TopicAccessFacts.NONE.copy(
        token = token?.let(::TopicGrantToken),
        binding = KrxHandSnapshots.BINDING,
        recordFence = if (recordConfirmed) KrxHandSnapshots.record(recordEpoch) else null,
        tokenStanding = standing,
        userBlocks = userBlocks,
        capabilityBlocks = capabilityBlocks,
        userContextUncertain = TopicAccessBlock.CONTEXT_UNCERTAIN in userBlocks,
        capabilityContextUncertain = TopicAccessBlock.CONTEXT_UNCERTAIN in capabilityBlocks
    ),
    userInvalidations = userInvalidations,
    krxPermitSeq = krxPermitSeq,
    capabilityInvalidations = capabilityInvalidations
)

/**
 * A snapshot whose token [token] was issued for [issuedRecord] at [issuedGeneration], with standing derived the way the issuer derives
 * it (T.3): the issued context equals the current binding, record fence and decision generation, and the user context is certain
 * (PremiumAccessCoordinator accessFactsLocked).
 *
 * [projectKrx] true models the switch on (A1): both contexts are built from the record fence without its capability epoch
 * (`grantFence`), so a record that moved only its KRX epoch keeps the same token standing. False models the switch off, where
 * `grantFence` is the identity and any epoch move ends standing — HEAD's own fixtures (GraphV2AccessGateTest `snap`). The record fence
 * the snapshot publishes, and a marker reads, always keeps the epoch.
 */
internal fun a1Snap(
    token: Long? = 7L,
    userBlocks: Set<TopicAccessBlock> = emptySet(),
    capabilityBlocks: Set<TopicAccessBlock> = emptySet(),
    binding: EntitlementsIdentity = KrxHandSnapshots.BINDING,
    record: AccessFence = KrxHandSnapshots.RECORD_K1,
    issuedRecord: AccessFence = record,
    generation: Long = 10L,
    issuedGeneration: Long = generation,
    userInvalidations: Long = 3L,
    krxPermitSeq: Long = 1L,
    capabilityInvalidations: Long = 0L,
    unconfirmed: Boolean = false,
    projectKrx: Boolean = true,
    revision: Long = 100L
): TopicAccessSnapshot {
    fun grantFence(fence: AccessFence) = if (projectKrx) fence.copy(krxCapabilityEpoch = null) else fence
    val issued = token?.let { TopicGrantContext(binding, grantFence(issuedRecord), issuedGeneration) }
    val userUncertain = TopicAccessBlock.CONTEXT_UNCERTAIN in userBlocks
    return TopicAccessSnapshot.INITIAL.copy(
        revision = revision,
        facts = TopicAccessFacts.NONE.copy(
            token = token?.let(::TopicGrantToken),
            issuedFor = issued,
            binding = binding,
            recordFence = record,
            decisionGeneration = generation,
            tokenStanding = issued != null && !userUncertain &&
                issued == TopicGrantContext(binding, grantFence(record), generation),
            userBlocks = userBlocks,
            capabilityBlocks = capabilityBlocks,
            recordUnconfirmed = unconfirmed,
            userContextUncertain = userUncertain,
            capabilityContextUncertain = TopicAccessBlock.CONTEXT_UNCERTAIN in capabilityBlocks
        ),
        userInvalidations = userInvalidations,
        krxPermitSeq = krxPermitSeq,
        capabilityInvalidations = capabilityInvalidations
    )
}
