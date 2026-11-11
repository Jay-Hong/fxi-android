package com.jay.fxi.data.alerts

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.DeletionAdmissionPhase
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 N1 contract (R4c/S7/s7n1 API r1-r3): the bank settings GET's access (`AlertSettingsAccess`, API r1 §4) on
 * the N1-B/C/D/T access axes. [AlertSettingsAccess.acquire] hands out a use only for a live identity, a published fence and a
 * lifetime the issuer grants, and the use it returns carries exactly those three; there is no use while no fence is published,
 * before the graph is installed or after it was given up (N1-T). [AlertSettingsAccess.admits] refuses when any single
 * condition fails: release admission, the same live identity, a fence of that identity, the same published fence, the
 * lifetime still admitted (even while a new acquire succeeds), premium confirmed for exactly this session, and the shared
 * deletion admission in both phases for the live UID only (a real [DeletionAdmissionStore] under a real
 * [GraphProtectedAdmission]). When a condition returns only a fresh acquire is admitted; a use from before a fence, identity or
 * lifetime change never is again. The use authority is an explicit fake: [ExplicitUses.issuing] is what a new acquire hands
 * out and [ExplicitUses.admitted] the lifetimes it still admits, so each row moves one input and keeps every other standing.
 * The owner's waiting, signals and GET counts are the owner and wire contracts' rows, not this file's. The implementation
 * thread reads but does not edit this file.
 */
class AlertSettingsAccessContractTest {

    private companion object {
        val alice1 = AuthIdentityFence("user-a", 1)
        val alice2 = AuthIdentityFence("user-a", 2)
        val alice3 = AuthIdentityFence("user-a", 3)
        val bob1 = AuthIdentityFence("user-b", 1)
        val bob2 = AuthIdentityFence("user-b", 2)
        val token11 = TopicGrantToken(11)
        val token12 = TopicGrantToken(12)
        val token13 = TopicGrantToken(13)
        val token20 = TopicGrantToken(20)
        val token21 = TopicGrantToken(21)
        val fenceA = TopicSessionFence(alice1, "epoch-1", token11)
        val lifetime0 = TopicUseLifetime(token11, 0)

        fun confirmed(session: AuthIdentityFence) =
            OwnedPremiumAccess(session.uid, session.authGeneration, PremiumAccessState.PremiumConfirmed)
    }

    /** A use authority with explicit lifetimes; it reads nothing of the fence it is asked under, so only these two decide. */
    private class ExplicitUses(initial: TopicUseLifetime) : TopicUseAuthority {
        /** What a new acquire hands out, or null when the issuer refuses one. */
        var issuing: TopicUseLifetime? = initial

        /** The lifetimes [admits] says yes to. */
        var admitted: Set<TopicUseLifetime> = setOf(initial)

        /** Runs once, after acquire has decided its lifetime and before it returns. */
        var afterAcquire: () -> Unit = {}

        val asked = mutableListOf<TopicSessionFence>()
        val issued = mutableListOf<TopicUseLifetime?>()

        /** The grant moves to [next]: a new acquire gets it, and it is the only lifetime still admitted. */
        fun moveTo(next: TopicUseLifetime?) {
            issuing = next
            admitted = setOfNotNull(next)
        }

        override fun acquire(fence: TopicSessionFence): TopicUseLifetime? {
            val decided = issuing
            asked += fence
            issued += decided
            val hook = afterAcquire
            afterAcquire = {}
            hook()
            return decided
        }

        override fun admits(lifetime: TopicUseLifetime): Boolean = lifetime in admitted
    }

    /** Every input the access reads, each standing until a test moves it: alice's premium session under [fenceA]. */
    private class World {
        var release = true
        var identity: AuthIdentityFence? = alice1
        var fence: TopicSessionFence? = fenceA
        var premium: OwnedPremiumAccess = confirmed(alice1)
        val uses = ExplicitUses(lifetime0)
        val deletions = DeletionAdmissionStore()

        val access = AlertSettingsAccess(
            liveIdentity = { identity },
            currentAccessFence = { fence },
            uses = uses,
            premium = { premium },
            releaseAdmission = { release },
            protectedAdmission = GraphProtectedAdmission({ identity }, deletions)
        )

        /** A whole new standing session: identity, its fence, premium confirmed for it and the issuer's grant for it. */
        fun session(session: AuthIdentityFence, epoch: String, grant: TopicGrantToken, invalidations: Long = 0) {
            identity = session
            fence = TopicSessionFence(session, epoch, grant)
            premium = confirmed(session)
            uses.moveTo(TopicUseLifetime(grant, invalidations))
        }

        fun phases(): List<DeletionAdmissionPhase> = deletions.records.map { it.phase }
    }

    /** One single-input move; [refusesNew] says whether a new acquire is refused under it as well. */
    private class Move(val label: String, val refusesNew: Boolean, val change: World.() -> Unit)

    private fun singleMoves(): List<Move> = listOf(
        Move("N1-B release admission closed", refusesNew = true) { release = false },
        Move("N1-B signed out, no live identity", refusesNew = true) { identity = null },
        Move("N1-C live identity A to B", refusesNew = true) { identity = bob1 },
        Move("N1-C same uid, new authGeneration", refusesNew = true) { identity = alice2 },
        Move("N1-C published fence moved to a new userAccessEpoch", refusesNew = false) {
            fence = TopicSessionFence(alice1, "epoch-2", token11)
        },
        Move("N1-C published fence moved to another grant", refusesNew = false) {
            fence = TopicSessionFence(alice1, "epoch-1", token12)
        },
        Move("N1-T published fence given up", refusesNew = true) { fence = null },
        Move("N1-C lifetime invalidations moved", refusesNew = false) { uses.moveTo(TopicUseLifetime(token11, 1)) },
        Move("N1-C lifetime grant ended", refusesNew = true) { uses.moveTo(null) },
        Move("N1-B premium confirmed for another uid", refusesNew = true) { premium = confirmed(bob1) },
        Move("N1-B premium confirmed for the same uid at another authGeneration", refusesNew = true) {
            premium = OwnedPremiumAccess(alice1.uid, alice2.authGeneration, PremiumAccessState.PremiumConfirmed)
        },
        Move("N1-B premium FreeConfirmed", refusesNew = true) {
            premium = premium.copy(state = PremiumAccessState.FreeConfirmed)
        },
        Move("N1-B premium NoGrant", refusesNew = true) { premium = premium.copy(state = PremiumAccessState.NoGrant) },
        Move("N1-B premium resolving, Pending", refusesNew = true) {
            premium = premium.copy(state = PremiumAccessState.Pending(continuityEligible = true))
        },
        Move("N1-B premium Rejected", refusesNew = true) { premium = premium.copy(state = PremiumAccessState.Rejected) },
        Move("N1-B premium bound to no owner", refusesNew = true) { premium = OwnedPremiumAccess() },
        Move("N1-D deletion of the live uid requesting", refusesNew = true) { deletions.begin(alice1, "op-a") },
        Move("N1-D deletion of the live uid server-deleted", refusesNew = true) {
            deletions.begin(alice1, "op-a")
            deletions.serverDeleted("op-a")
        }
    )

    @Test
    fun `S7N1_B01 a standing premium session acquires a use carrying its identity, fence and lifetime`() {
        val world = World()
        val use = checkNotNull(world.access.acquire()) { "N1-B: every condition admits, so a use is acquired" }
        assertEquals("N1-B: the use carries the live identity", alice1, use.identity)
        assertEquals("N1-B: the use carries the published fence", fenceA, use.fence)
        assertEquals("N1-B: the use carries the lifetime the issuer granted", lifetime0, use.lifetime)
        assertEquals("N1-B: the issuer was asked under the published fence", setOf(fenceA), world.uses.asked.toSet())
        assertTrue("N1-B: the acquired use is admitted", world.access.admits(use))
    }

    @Test
    fun `S7N1_B02 no live identity acquires no use`() {
        val world = World()
        assertNotNull("N1-B premise: the standing world acquires", world.access.acquire())
        world.identity = null
        assertNull("N1-B: signed out, no use", world.access.acquire())
    }

    @Test
    fun `S7N1_T01 no use before the graph publishes a fence, and a fresh one once it does`() {
        assertNotNull("N1-T premise: the standing world acquires", World().access.acquire())
        val world = World()
        world.fence = null
        repeat(3) { ask ->
            assertNull("N1-T: graph not installed, no fence, ask ${ask + 1}", world.access.acquire())
        }
        world.fence = fenceA
        val use = checkNotNull(world.access.acquire()) { "N1-T: a late fence admits a fresh acquire" }
        assertEquals("N1-T: the fresh use carries the late fence", fenceA, use.fence)
        assertTrue("N1-T: the fresh use is admitted", world.access.admits(use))
    }

    @Test
    fun `S7N1_T02 a fence given up withdraws the use and no new one is ever acquired`() {
        val world = World()
        val use = checkNotNull(world.access.acquire()) { "N1-T premise: acquired while the graph published its fence" }
        assertTrue("N1-T premise: admitted", world.access.admits(use))
        world.fence = null
        assertFalse("N1-T: the graph was given up, the use is withdrawn", world.access.admits(use))
        assertNull("N1-T: the graph was given up, no new use", world.access.acquire())
        // Every other input asserts itself again: a newer grant from the issuer, premium re-confirmed, release open.
        world.uses.moveTo(TopicUseLifetime(token11, 1))
        world.premium = confirmed(alice1)
        world.release = true
        assertNotNull("N1-T premise: the issuer would hand out a new lifetime", world.uses.issuing)
        repeat(3) { ask ->
            assertNull("N1-T: given up for good, still no use, ask ${ask + 1}", world.access.acquire())
        }
        assertFalse("N1-T: given up for good, the old use stays withdrawn", world.access.admits(use))
    }

    @Test
    fun `S7N1_B03 an issuer refusing a new use refuses the acquire and withdraws nothing already admitted`() {
        val world = World()
        val earlier = checkNotNull(world.access.acquire()) { "N1-B premise: the standing world acquires" }
        world.uses.issuing = null
        assertNull("N1-B: the issuer refuses a new use, so does the access", world.access.acquire())
        assertEquals("N1-B premise: the issuer was asked under the published fence", fenceA, world.uses.asked.last())
        assertTrue(
            "N1-B: a refused new acquire says nothing about a use whose lifetime is still admitted",
            world.access.admits(earlier)
        )
    }

    @Test
    fun `S7N1_B04 each condition alone withdraws an acquired use`() {
        for (move in singleMoves()) {
            val world = World()
            val use = checkNotNull(world.access.acquire()) { "${move.label}: premise acquired" }
            assertTrue("${move.label}: premise admitted", world.access.admits(use))
            world.(move.change)()
            assertFalse("${move.label}: withdrawn", world.access.admits(use))
        }
    }

    @Test
    fun `S7N1_B05 each refusing condition alone refuses a new use`() {
        val refusing = singleMoves().filter { it.refusesNew }
        assertEquals("premise: the table lists every refusing row", 15, refusing.size)
        for (move in refusing) {
            val world = World()
            assertNotNull("${move.label}: premise, the same world acquires before the move", world.access.acquire())
            world.(move.change)()
            assertNull("${move.label}: refused", world.access.acquire())
        }
    }

    @Test
    fun `S7N1_B06 a fence of another identity or another generation is never admitted`() {
        // Another uid's fence, while the live identity, premium and issuer all stand for alice.
        val world = World()
        assertTrue(
            "N1-B premise: a use built for the standing fence is admitted",
            world.access.admits(AlertSettingsUse(alice1, fenceA, lifetime0))
        )
        val bobsFence = TopicSessionFence(bob1, "epoch-1", token11)
        world.fence = bobsFence
        val refused = world.access.acquire()
        assertEquals("N1-B premise: the issuer granted under that fence", lifetime0, world.uses.issued.firstOrNull())
        assertNull("N1-B: the published fence names another uid", refused)
        assertFalse(
            "N1-B: a use whose fence names another uid, under that same published fence",
            world.access.admits(AlertSettingsUse(alice1, bobsFence, lifetime0))
        )

        // The same uid's fence from the session before, while the live identity and premium are the new generation.
        val later = World()
        later.identity = alice2
        later.premium = confirmed(alice2)
        val alice2Fence = TopicSessionFence(alice2, "epoch-1", token11)
        later.fence = alice2Fence
        assertNotNull("N1-B premise: the new generation acquires under its own fence", later.access.acquire())
        later.fence = fenceA
        assertNull("N1-B: the published fence is the previous authGeneration's", later.access.acquire())
        assertFalse(
            "N1-B: a use whose fence is the previous authGeneration's",
            later.access.admits(AlertSettingsUse(alice2, fenceA, lifetime0))
        )
    }

    @Test
    fun `S7N1_B07 a withdrawal right after the issuer grants refuses the acquire`() {
        // Each move runs inside the issuer's acquire, after it decided its lifetime; refusesNew is not read here.
        val cases = listOf(
            Move("N1-C the issuer's lifetime moved", refusesNew = true) { uses.moveTo(TopicUseLifetime(token11, 1)) },
            Move("N1-C the published fence moved to a new epoch", refusesNew = true) {
                fence = TopicSessionFence(alice1, "epoch-2", token11)
            },
            Move("N1-C the live identity moved to a new authGeneration", refusesNew = true) { identity = alice2 },
            Move("N1-B release admission closed", refusesNew = true) { release = false },
            Move("N1-B premium lost", refusesNew = true) { premium = premium.copy(state = PremiumAccessState.FreeConfirmed) },
            Move("N1-D deletion of the live uid began", refusesNew = true) { deletions.begin(alice1, "op-a") }
        )
        for (move in cases) {
            val world = World()
            world.uses.afterAcquire = { world.(move.change)() }
            val result = world.access.acquire()
            assertEquals("${move.label}: premise, the issuer granted a lifetime", lifetime0, world.uses.issued.firstOrNull())
            assertNull("${move.label}: the access hands out no use the move withdrew", result)
        }
    }

    /** One recovery: the move, and the fence and lifetime a fresh use is acquired under afterwards. */
    private class Recovery(
        val label: String,
        val freshFence: TopicSessionFence,
        val freshLifetime: TopicUseLifetime,
        val move: World.() -> Unit
    )

    @Test
    fun `S7N1_C01 after the fence or the lifetime moves only a fresh use is admitted`() {
        val newEpoch = TopicSessionFence(alice1, "epoch-2", token11)
        val newGrant = TopicSessionFence(alice1, "epoch-1", token12)
        val cases = listOf(
            Recovery("N1-C userAccessEpoch changed at the same authGeneration", newEpoch, lifetime0) { fence = newEpoch },
            Recovery("N1-C a short use invalidation and its recovery", fenceA, TopicUseLifetime(token11, 1)) {
                uses.moveTo(null)
                uses.moveTo(TopicUseLifetime(token11, 1))
            },
            Recovery("N1-C the grant replaced by a new token", newGrant, TopicUseLifetime(token12, 0)) {
                fence = newGrant
                uses.moveTo(TopicUseLifetime(token12, 0))
            }
        )
        for (case in cases) {
            val world = World()
            val old = checkNotNull(world.access.acquire()) { "${case.label}: premise acquired" }
            assertTrue("${case.label}: premise admitted", world.access.admits(old))
            world.(case.move)()
            val fresh = checkNotNull(world.access.acquire()) { "${case.label}: a fresh acquire succeeds" }
            assertEquals("${case.label}: the fresh use's fence", case.freshFence, fresh.fence)
            assertEquals("${case.label}: the fresh use's lifetime", case.freshLifetime, fresh.lifetime)
            assertTrue("${case.label}: the fresh use is admitted", world.access.admits(fresh))
            assertFalse("${case.label}: the old use is not admitted again", world.access.admits(old))
        }
    }

    @Test
    fun `S7N1_C02 while a grant is away nothing is admitted and when it returns only a fresh use is`() {
        val world = World()
        val old = checkNotNull(world.access.acquire()) { "N1-C premise: acquired" }
        assertTrue("N1-C premise: admitted", world.access.admits(old))
        world.uses.moveTo(null)
        assertFalse("N1-C grant away: the old use", world.access.admits(old))
        assertNull("N1-C grant away: no new use", world.access.acquire())
        // The same token comes back under the same fence; one more invalidation tells it from the grant before.
        val returned = TopicUseLifetime(token11, 1)
        world.uses.moveTo(returned)
        val fresh = checkNotNull(world.access.acquire()) { "N1-C grant returned: a fresh acquire succeeds" }
        assertEquals("N1-C grant returned: the fresh use's lifetime", returned, fresh.lifetime)
        assertEquals("N1-C premise: the published fence never moved", old.fence, fresh.fence)
        assertTrue("N1-C grant returned: the fresh use is admitted", world.access.admits(fresh))
        assertFalse("N1-C grant returned: the old use is not admitted again", world.access.admits(old))
    }

    @Test
    fun `S7N1_C03 premium loss then regrant admits only a fresh use`() {
        val world = World()
        val old = checkNotNull(world.access.acquire()) { "N1-C premise: acquired" }
        assertTrue("N1-C premise: admitted", world.access.admits(old))
        // Loss: the coordinator publishes free and the issuer ends the grant.
        world.premium = world.premium.copy(state = PremiumAccessState.FreeConfirmed)
        world.uses.moveTo(null)
        assertFalse("N1-C premium lost: the old use", world.access.admits(old))
        assertNull("N1-C premium lost: no new use", world.access.acquire())
        // Regrant: premium confirmed again, the same token back with one more invalidation.
        world.premium = confirmed(alice1)
        world.uses.moveTo(TopicUseLifetime(token11, 1))
        val fresh = checkNotNull(world.access.acquire()) { "N1-C premium regranted: a fresh acquire succeeds" }
        assertTrue("N1-C premium regranted: the fresh use is admitted", world.access.admits(fresh))
        assertFalse("N1-C premium regranted: the use from before the loss", world.access.admits(old))
    }

    @Test
    fun `S7N1_C04 A to B to A admits only the newest session's fresh use`() {
        val world = World()
        val first = checkNotNull(world.access.acquire()) { "N1-C premise: A acquired" }
        assertTrue("N1-C premise: A admitted", world.access.admits(first))

        world.session(bob2, "epoch-b", token20)
        val bobs = checkNotNull(world.access.acquire()) { "N1-C premise: B's session acquires" }
        assertEquals("N1-C premise: B's use belongs to B", bob2, bobs.identity)
        assertTrue("N1-C premise: B's use is admitted", world.access.admits(bobs))
        assertFalse("N1-C A to B: A's use", world.access.admits(first))

        world.session(alice3, "epoch-1", token21)
        val back = checkNotNull(world.access.acquire()) { "N1-C A to B to A: a fresh acquire succeeds" }
        assertEquals("N1-C A to B to A: the fresh use belongs to A's new session", alice3, back.identity)
        assertTrue("N1-C A to B to A: the fresh use is admitted", world.access.admits(back))
        assertFalse("N1-C A to B to A: A's first use", world.access.admits(first))
        assertFalse("N1-C A to B to A: B's use", world.access.admits(bobs))
    }

    @Test
    fun `S7N1_C05 a same uid re-login withdraws the old use even under the same grant and lifetime`() {
        val world = World()
        val old = checkNotNull(world.access.acquire()) { "N1-C premise: acquired" }
        assertTrue("N1-C premise: admitted", world.access.admits(old))
        // The same user signs in again: only the authGeneration moves; the epoch, the grant token and the lifetime are the same.
        world.session(alice2, "epoch-1", token11)
        assertEquals("N1-C premise: the issuer's lifetime did not move", setOf(lifetime0), world.uses.admitted)
        val fresh = checkNotNull(world.access.acquire()) { "N1-C re-login: a fresh acquire succeeds" }
        assertEquals("N1-C re-login: the fresh use belongs to the new session", alice2, fresh.identity)
        assertTrue("N1-C re-login: the fresh use is admitted", world.access.admits(fresh))
        assertFalse("N1-C re-login: the previous session's use", world.access.admits(old))
    }

    @Test
    fun `S7N1_D01 a requesting deletion of the live uid withdraws the in-flight use and refuses new ones`() {
        val world = World()
        val inFlight = checkNotNull(world.access.acquire()) { "N1-D premise: acquired before the deletion" }
        assertTrue("N1-D premise: admitted before the deletion", world.access.admits(inFlight))
        world.deletions.begin(alice1, "op-a")
        assertEquals(
            "N1-D premise: the requesting record is published",
            listOf(DeletionAdmissionPhase.REQUESTING_SERVER),
            world.phases()
        )
        assertFalse("N1-D requesting: the in-flight answer is not applied", world.access.admits(inFlight))
        assertNull("N1-D requesting: no new use", world.access.acquire())
        // A failed, timed-out or cancelled DELETE releases nothing; the other inputs recovering do not open it either.
        world.uses.moveTo(TopicUseLifetime(token11, 1))
        world.premium = confirmed(alice1)
        assertNotNull("N1-D premise: the issuer would hand out a new lifetime", world.uses.issuing)
        assertEquals("N1-D premise: the record still requests", listOf(DeletionAdmissionPhase.REQUESTING_SERVER), world.phases())
        assertNull("N1-D requesting, outcome uncertain: still no new use", world.access.acquire())
    }

    @Test
    fun `S7N1_D02 a server confirmed deletion keeps the uid closed at a later auth generation too`() {
        val control = World()
        control.session(alice2, "epoch-2", token13)
        assertNotNull(
            "N1-D premise: a later generation of the same user acquires when no deletion stands",
            control.access.acquire()
        )

        val world = World()
        val inFlight = checkNotNull(world.access.acquire()) { "N1-D premise: acquired before the deletion" }
        assertTrue("N1-D premise: admitted before the deletion", world.access.admits(inFlight))
        world.deletions.begin(alice1, "op-a")
        world.deletions.serverDeleted("op-a")
        assertEquals(
            "N1-D premise: the deletion is server-confirmed",
            listOf(DeletionAdmissionPhase.SERVER_DELETED),
            world.phases()
        )
        assertFalse("N1-D server-deleted: the in-flight answer is not applied", world.access.admits(inFlight))
        assertNull("N1-D server-deleted: no new use", world.access.acquire())
        world.session(alice2, "epoch-2", token13)
        assertNull("N1-D server-deleted: a later authGeneration of the deleted uid", world.access.acquire())
    }

    @Test
    fun `S7N1_D03 another uid's deletion in either phase leaves this session open`() {
        val world = World()
        val use = checkNotNull(world.access.acquire()) { "N1-D premise: acquired" }
        assertTrue("N1-D premise: admitted before any deletion", world.access.admits(use))
        world.deletions.begin(bob1, "op-b")
        assertTrue("N1-D premise: the other uid's deletion is published", world.deletions.blocks(bob1.uid))
        assertTrue("N1-D another uid requesting: this use stays admitted", world.access.admits(use))
        assertNotNull("N1-D another uid requesting: a new use is acquired", world.access.acquire())
        world.deletions.serverDeleted("op-b")
        assertEquals(
            "N1-D premise: the other uid's deletion is server-confirmed",
            listOf(DeletionAdmissionPhase.SERVER_DELETED),
            world.phases()
        )
        assertTrue("N1-D another uid server-deleted: this use stays admitted", world.access.admits(use))
        assertNotNull("N1-D another uid server-deleted: a new use is acquired", world.access.acquire())
        // Positive control: the same store does close this session for its own uid.
        world.deletions.begin(alice1, "op-a")
        assertFalse("N1-D control: the live uid's own deletion withdraws the use", world.access.admits(use))
    }
}
