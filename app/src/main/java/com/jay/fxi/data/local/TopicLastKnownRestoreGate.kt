package com.jay.fxi.data.local

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.domain.model.TopicRates

/** What a restore under one grant produced; only [Seed] carries prices, and those are display seeds only. */
sealed interface TopicLastKnownRestore {
    data object NotAdmitted : TopicLastKnownRestore
    data object Withdrawn : TopicLastKnownRestore
    data class Seed(val rates: TopicRates, val fence: TopicSessionFence, val lifetime: TopicUseLifetime) : TopicLastKnownRestore
}

/** Read-only gate for last-known display seeds. */
internal class TopicLastKnownRestoreGate(
    private val store: TopicLastKnownStore,
    private val authority: TopicUseAuthority,
    private val liveIdentity: () -> AuthIdentityFence?
) {
    /** Reads a display seed only while the fenced identity and grant still admit this use. */
    suspend fun restore(fence: TopicSessionFence): TopicLastKnownRestore {
        val epoch = fence.userAccessEpoch ?: return TopicLastKnownRestore.NotAdmitted
        if (liveIdentity() != fence.identity) return TopicLastKnownRestore.NotAdmitted
        val lifetime = authority.acquire(fence) ?: return TopicLastKnownRestore.NotAdmitted

        val rates = store.restore(TopicLastKnownOwner(fence.identity.uid, epoch))
        val stillAdmitted = authority.admits(lifetime)
        val sameIdentity = liveIdentity() == fence.identity
        if (!stillAdmitted || !sameIdentity) {
            return TopicLastKnownRestore.Withdrawn
        }
        return TopicLastKnownRestore.Seed(rates, fence, lifetime)
    }
}
