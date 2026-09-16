package com.jay.fxi.data.entitlements.purge

import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeScope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The table that decides what a purge may delete.
 *
 * Everything here is a rule the design argued for rather than an implementation detail: a
 * capability revoke must not reach a user's own preferences, an account deletion needs its own
 * authorisation, and a surface another slice owns keeps the entry owed instead of quietly
 * disappearing from it.
 */
class PurgeDecisionTest {

    private fun namespace(owner: String? = "u1") = PurgeNamespace(
        ownerUid = owner,
        currentUserAccessEpoch = "live-user",
        currentKrxCapabilityEpoch = "live-krx",
        pending = PendingPurge(
            ownerUid = owner,
            userAccessEpoch = "old-user",
            krxCapabilityEpoch = null,
            scopes = setOf(PurgeScope.USER)
        )
    )

    private fun target(
        classification: PurgeClassification,
        scopes: Set<PurgeScope> = setOf(PurgeScope.USER)
    ) = PurgeTarget("t", classification, scopes, owner = "owner", note = "note")

    private fun disposition(
        classification: PurgeClassification,
        scope: PurgeScope = PurgeScope.USER,
        cause: PurgeCause = PurgeCause.SIGN_OUT,
        owner: String? = "u1",
        authorization: DeletionAuthorization? = null,
        scopes: Set<PurgeScope> = setOf(PurgeScope.USER)
    ) = PurgeDecision.disposition(
        target(classification, scopes),
        scope,
        cause,
        namespace(owner),
        authorization
    )

    private val authorized = DeletionAuthorization("u1", "op-1", localCleanupAllowed = true)

    /** A target the retiring axis does not reach is not this obligation's business. */
    @Test
    fun `an axis the target does not belong to is not applicable`() {
        assertEquals(
            TargetDisposition.NOT_APPLICABLE,
            disposition(PurgeClassification.DERIVED_HERE, scope = PurgeScope.CAPABILITY)
        )
        assertEquals(
            TargetDisposition.DELETE_NOW,
            disposition(
                PurgeClassification.DERIVED_HERE,
                scope = PurgeScope.CAPABILITY,
                scopes = setOf(PurgeScope.USER, PurgeScope.CAPABILITY)
            )
        )
    }

    /** Server-derived data of a retired namespace is what this purger is for, whatever the cause. */
    @Test
    fun `derived data is deleted under every cause`() {
        PurgeCause.entries.forEach { cause ->
            assertEquals(
                "cause=$cause",
                TargetDisposition.DELETE_NOW,
                disposition(PurgeClassification.DERIVED_HERE, cause = cause)
            )
        }
    }

    /**
     * A user's own preferences survive everything but an authorised account deletion.
     *
     * `ANDROID_V2_PLAN.md` §9.1 keeps these in the backup allowlist as user intent, so a sign-out
     * that deleted them would be throwing away something the plan carries to a new device.
     */
    @Test
    fun `preferences survive sign-out, identity switch and revokes`() {
        listOf(
            PurgeCause.SIGN_OUT,
            PurgeCause.IDENTITY_SWITCH,
            PurgeCause.PREMIUM_REVOKE,
            PurgeCause.CAPABILITY_REVOKE,
            PurgeCause.UNVERIFIED_START,
            PurgeCause.UNCLEAN_RESTART,
            PurgeCause.UNKNOWN
        ).forEach { cause ->
            assertEquals(
                "cause=$cause",
                TargetDisposition.NOT_APPLICABLE,
                disposition(PurgeClassification.ACCOUNT_DELETION_ONLY, cause = cause, authorization = authorized)
            )
        }
    }

    /** …and a capability revoke never reaches them even if it names the deletion cause. */
    @Test
    fun `a capability axis never reaches preferences`() {
        assertEquals(
            TargetDisposition.NOT_APPLICABLE,
            disposition(
                PurgeClassification.ACCOUNT_DELETION_ONLY,
                scope = PurgeScope.CAPABILITY,
                cause = PurgeCause.ACCOUNT_DELETION,
                authorization = authorized,
                scopes = setOf(PurgeScope.USER, PurgeScope.CAPABILITY)
            )
        )
    }

    /**
     * An account deletion deletes them only with its own authorisation, and never reports them done
     * without one: the cause alone is a claim, the obligation is the evidence.
     */
    @Test
    fun `an account deletion needs its own authorisation`() {
        assertEquals(
            TargetDisposition.DELETE_NOW,
            disposition(
                PurgeClassification.ACCOUNT_DELETION_ONLY,
                cause = PurgeCause.ACCOUNT_DELETION,
                authorization = authorized
            )
        )
        assertEquals(
            "권한 없는 계정삭제 원인이 삭제했거나 완료로 셌다",
            TargetDisposition.OUTSTANDING,
            disposition(PurgeClassification.ACCOUNT_DELETION_ONLY, cause = PurgeCause.ACCOUNT_DELETION)
        )
        assertEquals(
            "정리 phase 전 권한으로 삭제했다",
            TargetDisposition.OUTSTANDING,
            disposition(
                PurgeClassification.ACCOUNT_DELETION_ONLY,
                cause = PurgeCause.ACCOUNT_DELETION,
                authorization = authorized.copy(localCleanupAllowed = false)
            )
        )
    }

    /** Another user's authorisation, and an entry whose owner could not be narrowed, both refuse. */
    @Test
    fun `authorisation must name this entry's owner`() {
        assertEquals(
            TargetDisposition.OUTSTANDING,
            disposition(
                PurgeClassification.ACCOUNT_DELETION_ONLY,
                cause = PurgeCause.ACCOUNT_DELETION,
                authorization = authorized.copy(ownerUid = "u2")
            )
        )
        assertEquals(
            "owner 를 좁힐 수 없는 항목으로 선호를 지웠다",
            TargetDisposition.OUTSTANDING,
            disposition(
                PurgeClassification.ACCOUNT_DELETION_ONLY,
                cause = PurgeCause.ACCOUNT_DELETION,
                owner = null,
                authorization = authorized
            )
        )
    }

    /**
     * The authorisation is judged against the entry's owner, not whoever owns the namespace now.
     *
     * After A signs out and B signs in, an entry still owed for A meets a namespace whose current
     * owner is B. Reading the live owner here fails in both directions at once: B's deletion would
     * clear A's preferences, and A's own deletion would be refused and left owed forever.
     */
    @Test
    fun `authorisation is judged against the entry's owner and not the current one`() {
        val owedForA = PurgeNamespace(
            ownerUid = "u2",
            currentUserAccessEpoch = "live-user",
            currentKrxCapabilityEpoch = "live-krx",
            pending = PendingPurge("u1", "old-user", null, setOf(PurgeScope.USER))
        )
        val preferences = target(PurgeClassification.ACCOUNT_DELETION_ONLY)

        assertEquals(
            "현재 owner 의 권한으로 옛 항목의 선호를 지웠다",
            TargetDisposition.OUTSTANDING,
            PurgeDecision.disposition(
                preferences, PurgeScope.USER, PurgeCause.ACCOUNT_DELETION, owedForA,
                DeletionAuthorization("u2", "op-1", localCleanupAllowed = true)
            )
        )
        assertEquals(
            "항목 owner 의 정당한 권한을 거절했다",
            TargetDisposition.DELETE_NOW,
            PurgeDecision.disposition(
                preferences, PurgeScope.USER, PurgeCause.ACCOUNT_DELETION, owedForA, authorized
            )
        )
    }

    /** Surfaces another owner deletes keep the entry owed; control plane and device state do not. */
    @Test
    fun `other owners keep the entry owed and control plane stays out of it`() {
        listOf(
            PurgeClassification.CUTOVER_OWNED,
            PurgeClassification.UNDER_REVIEW,
            PurgeClassification.HANDED_OVER
        ).forEach { classification ->
            assertEquals(
                "classification=$classification",
                TargetDisposition.OUTSTANDING,
                disposition(classification)
            )
        }
        assertEquals(
            TargetDisposition.NOT_APPLICABLE,
            disposition(PurgeClassification.NOT_USER_DATA, scopes = setOf(PurgeScope.USER))
        )
    }
}
