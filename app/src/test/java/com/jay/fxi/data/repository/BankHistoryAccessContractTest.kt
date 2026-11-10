package com.jay.fxi.data.repository

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 step (a) contract: the bank history access adapter decides a history use from the deliverer's published fence,
 * the issuer's use authority, D24 release admission, the live identity, the displayed owner and protected admission
 * (`R4c/S7/s7a_api_proposal.claude.r2.md` §2.1 and §3 S7A_00–05; scope H07·H08 on the issuer and identity axes only — the sheet
 * generation, application and render are step (b), the Route and production wiring step (c)). The authority is the production
 * [SnapshotTopicUseAuthority] over a test snapshot. Each row moves one input and keeps every other one standing, so another
 * term's refusal never hides the one under test. The implementation thread reads but does not edit this file.
 */
class BankHistoryAccessContractTest {
    private val alice = AuthIdentityFence("user-a", 1)
    private val bob = AuthIdentityFence("user-b", 1)
    private val token = TopicGrantToken(11)
    private val shownOwner = TopicDisplayOwner(alice, 3)

    private fun standing(
        grant: TopicGrantToken?,
        userBlocks: Set<TopicAccessBlock> = emptySet(),
        capabilityBlocks: Set<TopicAccessBlock> = emptySet(),
        tokenStanding: Boolean = grant != null,
        invalidations: Long = 0L,
        capabilityUncertain: Boolean = false
    ) = TopicAccessSnapshot.INITIAL.copy(
        revision = 1L,
        facts = TopicAccessFacts.NONE.copy(
            token = grant,
            tokenStanding = tokenStanding,
            userBlocks = userBlocks,
            capabilityBlocks = capabilityBlocks,
            capabilityContextUncertain = capabilityUncertain
        ),
        userInvalidations = invalidations
    )

    /** Every input the adapter reads, each standing until a test moves it. */
    private inner class World {
        var release = true
        var identity: AuthIdentityFence? = alice
        var fence: TopicSessionFence? = TopicSessionFence(alice, "epoch-1", token)
        var owner: TopicDisplayOwner? = shownOwner
        var protected = true
        var snapshot = standing(token)
        /** Runs once, after the production authority's acquire has returned its lifetime and before the adapter does. */
        var afterIssuerAcquire: () -> Unit = {}
        var issuerAcquired: TopicUseLifetime? = null

        private val issuer = SnapshotTopicUseAuthority { snapshot }
        private val uses = object : TopicUseAuthority {
            override fun acquire(fence: TopicSessionFence): TopicUseLifetime? =
                issuer.acquire(fence).also { issuerAcquired = it; afterIssuerAcquire(); afterIssuerAcquire = {} }
            override fun admits(lifetime: TopicUseLifetime): Boolean = issuer.admits(lifetime)
        }

        val access: BankHistoryAccess = IssuerBankHistoryAccess(
            liveIdentity = { identity },
            currentAccessFence = { fence },
            displayOwner = { owner },
            uses = uses,
            releaseAdmission = { release },
            protectedAdmission = { protected }
        )
    }

    /** One case: a label and the single move it makes; a typed helper, so the lambda gets [World] as its receiver. */
    private fun row(label: String, move: World.() -> Unit): Pair<String, World.() -> Unit> = label to move

    @Test fun S7A_00_positiveControl_aStandingPremiumUseIsAcquiredAndAdmitted() {
        val world = World()
        val use = checkNotNull(world.access.acquire(shownOwner)) { "acquired" }
        assertTrue(world.access.admits(use))
        assertEquals(shownOwner, use.owner)
    }

    /** S7A_01 (H07): each refusing input alone refuses a new use, and no use leaks out. */
    @Test fun S7A_01_eachRefusingInputAloneRefusesANewUse() {
        val cases: List<Pair<String, World.() -> Unit>> = listOf(
            row("release admission closed") { release = false },
            row("no live identity") { identity = null },
            row("no published fence") { fence = null },
            row("no displayed owner") { owner = null },
            row("owner asked for is not the displayed one") { owner = TopicDisplayOwner(alice, 4) },
            row("live identity moved") { identity = alice.copy(authGeneration = 2) },
            row("fence of another identity") { fence = TopicSessionFence(bob, "epoch-1", token) },
            row("protected admission closed") { protected = false },
            row("no token") { snapshot = standing(null) },
            row("token is not the fence's grant") { snapshot = standing(TopicGrantToken(12)) },
            row("token not standing") { snapshot = standing(token, tokenStanding = false) }
        ) + TopicAccessBlock.entries.map { block ->
            row("user block $block") { snapshot = standing(token, userBlocks = setOf(block)) }
        }
        for ((label, move) in cases) {
            val world = World()
            world.move()
            assertNull("$label: refused", world.access.acquire(shownOwner))
        }
        // An owner of another identity, displayed and asked for, while the fence and the live identity agree with each other.
        val world = World()
        val bobs = TopicDisplayOwner(bob, 3)
        world.owner = bobs
        assertNull("owner of another identity: refused", world.access.acquire(bobs))
        assertTrue("positive control: the authority did grant a lifetime", world.issuerAcquired != null)
    }

    /** S7A_02 (H08 "capability-only loss는 bank use 허용"): capability blocks alone neither refuse nor withdraw a bank use. */
    @Test fun S7A_02_capabilityBlocksAloneNeitherRefuseNorWithdraw() {
        for (block in TopicAccessBlock.entries) {
            val world = World()
            world.snapshot = standing(token, capabilityBlocks = setOf(block), capabilityUncertain = true)
            val use = checkNotNull(world.access.acquire(shownOwner)) { "$block: acquired" }
            world.snapshot = standing(token, capabilityBlocks = TopicAccessBlock.entries.toSet(), capabilityUncertain = true)
            assertTrue("$block: still admitted", world.access.admits(use))
        }
    }

    /** S7A_03 (H07·H08): each change alone after acquisition withdraws the use. */
    @Test fun S7A_03_eachChangeAloneWithdrawsAnAcquiredUse() {
        val cases: List<Pair<String, World.() -> Unit>> = listOf(
            row("release admission closed") { release = false },
            row("live identity moved") { identity = alice.copy(authGeneration = 2) },
            row("signed out") { identity = null },
            // The fence alone: the authority's snapshot still names the old token.
            row("fence replaced") { fence = TopicSessionFence(alice, "epoch-1", TopicGrantToken(12)) },
            row("fence ended") { fence = null },
            // The display alone: the fence is unchanged.
            row("displayed owner moved") { owner = TopicDisplayOwner(alice, 4) },
            row("no displayed owner") { owner = null },
            // The authority alone: the fence still names the old token.
            row("token replaced") { snapshot = standing(TopicGrantToken(12)) },
            row("token ended") { snapshot = standing(null) },
            row("token not standing") { snapshot = standing(token, tokenStanding = false) },
            row("user invalidated") { snapshot = standing(token, invalidations = 1) },
            row("protected admission closed") { protected = false }
        ) + TopicAccessBlock.entries.map { block ->
            row("user block $block") { snapshot = standing(token, userBlocks = setOf(block)) }
        }
        for ((label, move) in cases) {
            val world = World()
            val use = checkNotNull(world.access.acquire(shownOwner)) { "$label: positive control" }
            world.move()
            assertFalse("$label: withdrawn", world.access.admits(use))
        }
    }

    /** S7A_04 (H08): after A→B→A, a premium end and a new grant, or a hold released, only a fresh use is admitted. */
    @Test fun S7A_04_anOldUseStaysWithdrawnAndOnlyAFreshOneIsAdmitted() {
        val world = World()
        val old = checkNotNull(world.access.acquire(shownOwner))

        // A → B → A: the grant comes back as a new token, the session as a new epoch.
        world.identity = bob; world.fence = TopicSessionFence(bob, "epoch-b", TopicGrantToken(20))
        world.owner = TopicDisplayOwner(bob, 4); world.snapshot = standing(TopicGrantToken(20))
        val back = TopicGrantToken(21)
        world.identity = alice; world.fence = TopicSessionFence(alice, "epoch-1", back)
        world.owner = TopicDisplayOwner(alice, 5); world.snapshot = standing(back)
        assertFalse("A→B→A: old use", world.access.admits(old))
        val afterReturn = checkNotNull(world.access.acquire(TopicDisplayOwner(alice, 5))) { "A→B→A: fresh use" }
        assertTrue(world.access.admits(afterReturn))

        // The same UID: premium ends, then a new grant.
        world.fence = null; world.owner = null; world.snapshot = standing(null, userBlocks = setOf(TopicAccessBlock.NOT_GRANTED))
        assertFalse("premium ended: use", world.access.admits(afterReturn))
        val regranted = TopicGrantToken(22)
        world.fence = TopicSessionFence(alice, "epoch-1", regranted)
        world.owner = TopicDisplayOwner(alice, 6); world.snapshot = standing(regranted, invalidations = 1)
        assertFalse("new grant: the use before the end", world.access.admits(afterReturn))
        val afterRegrant = checkNotNull(world.access.acquire(TopicDisplayOwner(alice, 6))) { "new grant: fresh use" }
        assertTrue(world.access.admits(afterRegrant))

        // A hold that came and went: same token, one more invalidation.
        world.snapshot = standing(regranted, userBlocks = setOf(TopicAccessBlock.PERSISTENCE_HOLD), invalidations = 2)
        world.snapshot = standing(regranted, invalidations = 2)
        assertFalse("hold released: old use", world.access.admits(afterRegrant))
        val afterHold = checkNotNull(world.access.acquire(TopicDisplayOwner(alice, 6))) { "hold released: fresh use" }
        assertTrue(world.access.admits(afterHold))
    }

    /** S7A_05: a withdrawal after the authority granted a lifetime, before the adapter returns, refuses the use. */
    @Test fun S7A_05_aWithdrawalRightAfterTheAuthorityGrantsRefusesTheUse() {
        val cases: List<Pair<String, World.() -> Unit>> = listOf(
            row("user invalidated") { snapshot = standing(token, invalidations = 1) },
            row("user block") { snapshot = standing(token, userBlocks = setOf(TopicAccessBlock.CONTEXT_UNCERTAIN)) },
            row("fence replaced") { fence = TopicSessionFence(alice, "epoch-1", TopicGrantToken(12)) },
            row("displayed owner moved") { owner = TopicDisplayOwner(alice, 4) },
            row("protected admission closed") { protected = false }
        )
        for ((label, move) in cases) {
            val world = World()
            world.afterIssuerAcquire = { world.move() }
            assertNull("$label: refused", world.access.acquire(shownOwner))
            assertTrue("$label: positive control: the authority did grant a lifetime", world.issuerAcquired != null)
        }
    }
}
