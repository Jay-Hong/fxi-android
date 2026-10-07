package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.DeletionAdmissionStore

/**
 * Live graph admission for identity presence and process-owned deletion state (S4 RT01-A2).
 * The coordinator and gate must share this same function object and deletion store at cutover.
 * This dormant supplier performs no DI registration, collection or operational wiring.
 *
 * Both requesting and server-confirmed deletions close graph use for their UID at every auth
 * generation; another UID's deletion does not. The store alone owns phase transitions and permits
 * release only with proof of an unsent request. Terminal clear remains S10's finalizer's authority.
 *
 * Issuer snapshot decisions remain with the coordinator's uses.admits and the gate's admitsUse
 * and KRX capability checks. This supplier neither reads that snapshot nor gates entitlement
 * control queries. Returning true supplies only the additional graph admission condition.
 *
 * Safe to call on transport threads when [liveIdentity] is a thread-safe live supplier: each call
 * reads identity once, then the store's lock-free publication. No admission result is cached;
 * these reads do not form a joint atomic snapshot with identity or issuer state.
 */
internal class GraphProtectedAdmission(
    private val liveIdentity: () -> AuthIdentityFence?,
    private val deletions: DeletionAdmissionStore
) : () -> Boolean {
    override fun invoke(): Boolean = liveIdentity()?.let { !deletions.blocks(it.uid) } ?: false
}
