package com.jay.fxi.s5fixture

import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthUnavailableException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * S5-4 slice 4b: the fixture identity's three surfaces, observed on the JVM. Posting goes through a queue standing in for the
 * main looper; the real looper delivery, its thread and release are S5-4 4b-2b's Android check.
 */
class S5FixtureIdentityTest {

    private val posted = ArrayDeque<Runnable>()
    private val identity = S5FixtureIdentity { posted.addLast(it) }
    private fun runPosted() { while (posted.isNotEmpty()) posted.removeFirst().run() }
    private val gen1 = AuthIdentity("s5-fixture", 1L)

    @Test
    fun `S5-4 4b signed out until signIn, then the fixture user at generation 1 on every surface`() {
        val fences = mutableListOf<AuthIdentityFence?>()
        identity.observe { fences += it }
        assertEquals("a signed-out observer gets the null replay", listOf<AuthIdentityFence?>(null), fences)
        assertNull(identity.currentIdentity())
        assertNull(identity.currentUser())

        identity.signIn()
        assertEquals(listOf(null, AuthIdentityFence("s5-fixture", 1L)), fences)
        assertEquals(gen1, identity.currentIdentity())
        assertEquals("repeated reads keep the generation", gen1, identity.currentIdentity())
        val user = checkNotNull(identity.currentUser())
        assertEquals("the observed user is the token's user", gen1.uid, user.uid)
        val profile = user.readProfile()
        assertEquals("s5-fixture", profile.uid)
        assertEquals("S5 fixture", profile.displayName)
        assertNull(profile.email)
        assertNull(profile.providerId)

        identity.signIn()
        assertEquals("a second signIn changes nothing", gen1, identity.currentIdentity())
        assertEquals(2, fences.size)
    }

    @Test
    fun `S5-4 4b user notices are posted, once on registration and once on sign-in, and stop after close`() {
        var a = 0
        var b = 0
        identity.register { a++ }
        val closing = identity.register { b++ }
        assertEquals("posted, not run inline", 0, a + b)
        runPosted()
        assertEquals(1, a)
        assertEquals(1, b)
        closing.close()
        identity.signIn()
        runPosted()
        assertEquals(2, a)
        assertEquals("a closed registration hears nothing more", 1, b)
    }

    @Test
    fun `S5-4 4b a close before the queue drains suppresses that registration's queued notices`() {
        var a = 0
        var b = 0
        val first = identity.register { a++ }
        identity.register { b++ }
        first.close()
        runPosted()
        assertEquals("the registration notice queued before the close is not delivered", 0, a)
        assertEquals(1, b)
        val second = identity.register { a++ }
        runPosted()
        assertEquals(1, a)
        identity.signIn()
        second.close()
        runPosted()
        assertEquals("the sign-in notice queued before the close is not delivered", 1, a)
        assertEquals("the open registration hears the sign-in", 2, b)
    }

    @Test
    fun `S5-4 4b registrations of the same callback are separate entries`() {
        var count = 0
        val callback: () -> Unit = { count++ }
        val closedOne = identity.register(callback)
        closedOne.close()
        identity.register(callback)
        runPosted()
        assertEquals("re-registering the callback does not revive the closed registration's notice", 1, count)

        count = 0
        val twin = identity.register(callback)
        runPosted()
        assertEquals(1, count)
        twin.close()
        identity.signIn()
        runPosted()
        assertEquals("only the registration still open hears the sign-in", 2, count)
    }

    @Test
    fun `S5-4 4b invalidation publishes the next generation, retirement does not`() {
        val fences = mutableListOf<AuthIdentityFence?>()
        identity.signIn()
        identity.observe { fences += it }
        assertTrue(identity.invalidateCurrentIdentity())
        assertEquals(listOf(AuthIdentityFence("s5-fixture", 1L), AuthIdentityFence("s5-fixture", 2L)), fences)
        assertFalse("a stale session is not retired", identity.invalidateCurrentSession(gen1))
        assertTrue(identity.invalidateCurrentSession(AuthIdentity("s5-fixture", 2L)))
        assertEquals("retirement announces nothing", 2, fences.size)
        assertEquals(AuthIdentity("s5-fixture", 3L), identity.currentIdentity())
    }

    @Test
    fun `S5-4 4b the user signs in only after a successful seed`() = runTest {
        assertFalse(identity.signInAfter { false })
        assertNull("a failed seed leaves the user signed out", identity.currentIdentity())
        assertNull(identity.currentUser())
        assertTrue(identity.signInAfter { true })
        assertEquals(gen1, identity.currentIdentity())
    }

    @Test
    fun `S5-4 4b after a retirement the sign-out still reaches observers as null`() {
        val fences = mutableListOf<AuthIdentityFence?>()
        identity.signIn()
        identity.observe { fences += it }
        assertTrue(identity.invalidateCurrentSession(gen1))
        assertEquals("retirement announces nothing", listOf(AuthIdentityFence("s5-fixture", 1L)), fences)
        identity.signOut()
        assertEquals("the sign-out does", listOf(AuthIdentityFence("s5-fixture", 1L), null), fences)
        assertNull(identity.currentIdentity())
        assertNull(identity.currentUser())
    }

    @Test
    fun `S5-4 4b tokens go only to the current identity`() = runTest {
        try {
            identity.fetchToken(gen1, forceRefresh = false)
            fail("signed out")
        } catch (expected: AuthUnavailableException) {
        }
        identity.signIn()
        assertEquals("s5-fixture-token", identity.fetchToken(gen1, forceRefresh = true))
        identity.invalidateCurrentIdentity()
        try {
            identity.fetchToken(gen1, forceRefresh = false)
            fail("stale generation")
        } catch (expected: AuthIdentityChangedException) {
        }
        try {
            identity.fetchToken(AuthIdentity("someone", 2L), forceRefresh = false)
            fail("another user")
        } catch (expected: AuthIdentityChangedException) {
        }
    }
}
