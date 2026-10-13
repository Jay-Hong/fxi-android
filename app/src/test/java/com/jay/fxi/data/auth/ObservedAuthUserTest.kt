package com.jay.fxi.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** S5-4 slice 3: the user fields the observation port hands the auth screen state. */
class ObservedAuthUserTest {

    @Test
    fun `S5-4 slice 3 the provider is the first sign-in provider that is not Firebase's own`() {
        assertEquals("apple.com", observedAuthUser("u", null, null, null, listOf("firebase", "apple.com", "google.com")).providerId)
        assertEquals("google.com", observedAuthUser("u", null, null, null, listOf("google.com", "firebase")).providerId)
        assertNull(observedAuthUser("u", null, null, null, listOf("firebase")).providerId)
        assertNull(observedAuthUser("u", null, null, null, emptyList()).providerId)
    }

    @Test
    fun `S5-4 slice 3 the profile fields pass through unchanged`() {
        assertEquals(
            ObservedAuthUser("uid-1", "a@example.invalid", "A", "https://example.invalid/a.png", "apple.com"),
            observedAuthUser("uid-1", "a@example.invalid", "A", "https://example.invalid/a.png", listOf("apple.com"))
        )
        assertEquals(ObservedAuthUser("uid-2", null, null, null, null), observedAuthUser("uid-2", null, null, null, emptyList()))
    }
}
