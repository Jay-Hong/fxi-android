package com.jay.fxi.data.repository

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AuthBoundResultTest {

    @Test
    fun uidChangeAfterRepositoryReturn_neverReachesSuccessOrFailureCallback() {
        val owner = AuthIdentityFence("user-a", 1)
        var current = owner
        var applied = false
        val result = AuthBoundResult(
            requireCurrent = {
                if (current != owner) throw AuthIdentityChangedException()
            },
            result = Result.success("old-user-data")
        )
        current = AuthIdentityFence("user-b", 2)

        assertThrows(AuthIdentityChangedException::class.java) {
            result.fold(
                onSuccess = { applied = true },
                onFailure = { applied = true }
            )
        }
        assertFalse(applied)
    }

    @Test
    fun sameUidNewGenerationAfterRepositoryReturn_neverReachesRollbackCallback() {
        val owner = AuthIdentityFence("user-a", 1)
        var current = owner
        var applied = false
        val result = AuthBoundResult<Unit>(
            requireCurrent = {
                if (current != owner) throw AuthIdentityChangedException()
            },
            result = Result.failure(IllegalStateException("old-user-failure"))
        )
        current = AuthIdentityFence("user-a", 2)

        assertThrows(AuthIdentityChangedException::class.java) {
            result.onFailure { applied = true }
        }
        assertFalse(applied)
    }

    @Test
    fun currentOwner_appliesCallbackExactlyOnce() {
        var applications = 0
        val result = AuthBoundResult(
            requireCurrent = {},
            result = Result.success("current-user-data")
        )

        val value = result.fold(
            onSuccess = {
                applications += 1
                it
            },
            onFailure = { throw it }
        )

        assertEquals("current-user-data", value)
        assertEquals(1, applications)
    }
}
