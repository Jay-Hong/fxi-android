package com.jay.fxi.data.repository

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime

/** One history request's captured access: the screen owner it was opened for, the published fence and the use acquired under it. */
internal class BankHistoryUse internal constructor(
    val owner: TopicDisplayOwner,
    internal val fence: TopicSessionFence,
    internal val lifetime: TopicUseLifetime
)

internal interface BankHistoryAccess {
    /**
     * Main. A use for one new history request shown under [owner], or null: no live identity, no published fence, [owner] not
     * the displayed owner or of another identity, the fence of another identity, release or protected admission closed, or the issuer
     * refusing a new use. Side-effect free; acquires nothing it does not return.
     */
    fun acquire(owner: TopicDisplayOwner): BankHistoryUse?

    /**
     * Any thread, non-blocking, side-effect free: whether [use] may still send, and its answer still be applied, now.
     * A new [acquire] succeeding says nothing about an older use.
     */
    fun admits(use: BankHistoryUse): Boolean
}

internal class IssuerBankHistoryAccess(
    private val liveIdentity: () -> AuthIdentityFence?,
    private val currentAccessFence: () -> TopicSessionFence?,
    private val displayOwner: () -> TopicDisplayOwner?,
    private val uses: TopicUseAuthority,
    private val releaseAdmission: () -> Boolean,
    private val protectedAdmission: () -> Boolean
) : BankHistoryAccess {
    override fun acquire(owner: TopicDisplayOwner): BankHistoryUse? {
        val fence = currentAccessFence() ?: return null
        val lifetime = uses.acquire(fence) ?: return null
        // Every other condition is one admits() asks; asking it here too would only hide a mutation of it there.
        return BankHistoryUse(owner, fence, lifetime).takeIf(::admits)
    }

    override fun admits(use: BankHistoryUse): Boolean =
        releaseAdmission() && liveIdentity() == use.fence.identity && use.owner.identity == use.fence.identity &&
            displayOwner() == use.owner && currentAccessFence() == use.fence && uses.admits(use.lifetime) &&
            protectedAdmission()
}
