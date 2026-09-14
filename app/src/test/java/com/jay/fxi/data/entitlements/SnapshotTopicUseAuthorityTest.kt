package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L-4e E2a: the issuer's judgement of a protected use, over one published snapshot (`l4e_e2a_design_v4.md` §2.1).
 *
 * Each row changes one fact of an allowed snapshot, so a condition dropped from either judgement is the row that passes.
 */
class SnapshotTopicUseAuthorityTest {

    private val fence = TopicSessionFence(AuthIdentityFence("u1", 1L), "epoch-1", TopicGrantToken(7L))

    private fun snapshot(
        token: Long? = 7L,
        standing: Boolean = true,
        userBlocks: Set<TopicAccessBlock> = emptySet(),
        capabilityBlocks: Set<TopicAccessBlock> = emptySet(),
        invalidations: Long = 3L
    ) = TopicAccessSnapshot.INITIAL.copy(
        facts = TopicAccessFacts.NONE.copy(
            token = token?.let(::TopicGrantToken),
            tokenStanding = standing,
            userBlocks = userBlocks,
            capabilityBlocks = capabilityBlocks
        ),
        userInvalidations = invalidations
    )

    @Test
    fun anAllowedSnapshot_acquiresTheFencesGrantWithItsInvalidations_andAdmitsIt() {
        val lifetime = snapshot().acquireUse(fence)
        assertEquals(TopicUseLifetime(TopicGrantToken(7L), 3L), lifetime)
        assertTrue(snapshot().admitsUse(TopicUseLifetime(TopicGrantToken(7L), 3L)))
    }

    @Test
    fun aNewUse_isRefusedByEachMissingCondition() {
        assertNull("another token", snapshot(token = 8L).acquireUse(fence))
        assertNull("no token", snapshot(token = null).acquireUse(fence))
        assertNull("a token that no longer stands", snapshot(standing = false).acquireUse(fence))
        assertNull("the user axis held", snapshot(userBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE)).acquireUse(fence))
    }

    @Test
    fun anExistingUse_isRefusedByEachMissingCondition_andByAnInvalidationInBetween() {
        val lifetime = TopicUseLifetime(TopicGrantToken(7L), 3L)
        assertFalse("another token", snapshot(token = 8L).admitsUse(lifetime))
        assertFalse("no token", snapshot(token = null).admitsUse(lifetime))
        assertFalse("a token that no longer stands", snapshot(standing = false).admitsUse(lifetime))
        assertFalse("the user axis held", snapshot(userBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE)).admitsUse(lifetime))
        assertFalse("a hold that came and went", snapshot(invalidations = 4L).admitsUse(lifetime))
        assertFalse("an earlier count is not this use either", snapshot(invalidations = 2L).admitsUse(lifetime))
    }

    /** No topic this session consumes is a KRX one, so a capability-only hold stops neither judgement (C34). */
    @Test
    fun aCapabilityOnlyHold_stopsNeitherANewUseNorAnExistingOne() {
        val held = snapshot(capabilityBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE, TopicAccessBlock.NOT_GRANTED))
        val lifetime = held.acquireUse(fence)
        assertEquals(TopicUseLifetime(TopicGrantToken(7L), 3L), lifetime)
        assertTrue(held.admitsUse(TopicUseLifetime(TopicGrantToken(7L), 3L)))
    }

    /** The wrapper asks the supplier once per question, so each answer is decided from one published snapshot. */
    @Test
    fun theAuthority_readsThePublishedSnapshotOncePerQuestion() {
        var published = snapshot()
        var reads = 0
        val authority = SnapshotTopicUseAuthority { reads += 1; published }

        val lifetime = authority.acquire(fence)
        assertEquals(1, reads)
        assertTrue(authority.admits(checkNotNull(lifetime)))
        assertEquals(2, reads)

        published = snapshot(userBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE), invalidations = 4L)
        assertFalse(authority.admits(lifetime))
        assertNull(authority.acquire(fence))
        assertEquals(4, reads)
    }
}
