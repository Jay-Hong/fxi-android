package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.IndeterminateReason
import com.jay.fxi.data.entitlements.PremiumAccessReducer
import com.jay.fxi.data.entitlements.RefreshIntent
import java.util.Collections

/** One source per runtime origin, shared by binding, query and lifecycle issuance in D2c.
 * The source is never retained by a command. Issuance happens once during preparation.
 */
internal class LifecycleOrderSource(val origin: LifetimeId, initial: Long = 0) {
    private var last = initial
    init { require(initial >= 0); require(origin.value.isNotEmpty()) }
    @Synchronized fun issue(bindingStart: Long, after: Long = 0): LifecycleOrderGrant? {
        val lower = maxOf(last, bindingStart, after)
        if (lower == Long.MAX_VALUE) return null // Q16bc
        val grant = LifecycleOrderGrant(origin, last, bindingStart, after, lower + 1)
        last = grant.value
        return grant
    }
}

internal data class LifecycleOrderGrant(
    val origin: LifetimeId, val previous: Long, val bindingStart: Long, val after: Long, val value: Long
)

internal data class LifecycleBinding(
    val executor: SettlementExecutor,
    val identity: IdentityV1?,
    val startedOrder: Long,
    val startEventId: String?,
    val acceptedAuthOrder: Long = 0
)
internal enum class LifecycleQuerySource { REGISTERED_QUERY, TOPIC, RESTORED_HOLD, CREDENTIAL, TIMER }
internal data class LifecycleQueryRegistration(val id: String, val started: StartedQueryV1)
internal enum class LifecycleReapproval { NOT_REQUIRED, APPROVED, BLOCKED }

/** Exact required rows and a storage-confirmed snapshot; a memory reducer result is insufficient. */
internal data class LifecycleDurableEffect(
    val kind: ControlKind, val node: ControlNode, val confirmation: ConfirmedControlSnapshot?
)
internal class AcceptedQueryDecision(
    val registration: LifecycleQueryRegistration,
    val source: LifecycleQuerySource,
    val answeredAs: IdentityV1?,
    val acceptedBeforeFence: FenceV1,
    val acceptedBeforeGeneration: Long,
    val confirmedAfterFence: FenceV1,
    val expectedAfterGeneration: Long,
    val outcome: EntitlementsOutcome,
    val reapproval: LifecycleReapproval,
    val capture: BootReading,
    val mergeNow: BootReading,
    val floorOrigin: LifetimeId,
    val decisionMinDelayMillis: Long = 0,
    val followUp: RefreshIntent? = null,
    effects: List<LifecycleDurableEffect> = emptyList(),
    val namespaceConfirmation: ConfirmedControlSnapshot? = null
) {
    val effects = Collections.unmodifiableList(effects.toList())
    val query get() = registration.started
}
internal enum class LifecycleCallerOrigin { CALLER, SCHEDULED }
internal data class LifecycleCaller(
    val id: String, val origin: LifecycleCallerOrigin, val binding: LifecycleBinding,
    val order: LifecycleOrderGrant, val intent: RefreshIntent, val now: BootReading
)
internal data class LifecycleRecovery(
    val id: String, val identity: IdentityV1, val origin: LifetimeId,
    val fetchStartedOrder: Long, val recoveredOrder: Long, val episode: Long
)
internal data class LifecycleBindingClosure(
    val scope: AuthSnapshotV1,
    val entriesClosed: Boolean,
    val capturedWork: Set<String>,
    val joinedWork: Set<String>,
    val generation: Long,
    val previousTracker: String? = null
)

/** Fresh facts captured under the coordinator lock on each attempt. No admission/arming output. */
internal class DemandAuthRuntime(
    val binding: LifecycleBinding,
    val liveIdentity: IdentityV1?,
    val generation: Long,
    registrations: List<LifecycleQueryRegistration> = emptyList(),
    val caller: LifecycleCaller? = null,
    val recovery: LifecycleRecovery? = null,
    val consumedRecoveryEpisode: Long = 0,
    val closure: LifecycleBindingClosure? = null
) {
    val registrations = Collections.unmodifiableList(registrations.toList())
}
internal sealed interface LifecycleAuthEvent {
    data object Initialize : LifecycleAuthEvent
    class Answer(val decision: AcceptedQueryDecision) : LifecycleAuthEvent
    data class Caller(val fact: LifecycleCaller) : LifecycleAuthEvent
    data class Recovery(val fact: LifecycleRecovery) : LifecycleAuthEvent
}

/** Small boundaries keep implication/overlap tests separate from schema and final-candidate gates. */
internal object DemandAuthBoundary {
    fun orderValueMatches(request: DemandV1, grant: LifecycleOrderGrant): Boolean {
        if (request.raisedAt.value != grant.value) return false // W.orderLink
        return true
    }
    fun order(grant: LifecycleOrderGrant, binding: LifecycleBinding, after: Long): Boolean {
        if (grant.origin != binding.executor.originLifetimeId) return false // Q12a
        if (grant.value != maxOf(grant.previous, grant.bindingStart, grant.after) + 1) return false // Q12b
        if (grant.value <= binding.startedOrder) return false // Q16a
        if (grant.value <= after) return false // Q16d
        return true
    }
    fun rebind(before: DemandV1, binding: LifecycleBinding, intent: RefreshIntent): Boolean {
        if (before.ownerUid != binding.executor.ownerUid) return false // Q11a
        if (before.binding == binding.executor.binding && before.raisedAt.origin == binding.executor.originLifetimeId) return false // Q11b
        if (intent < before.intent) return false // Q11c
        return true
    }
    fun request(request: DemandV1, query: StartedQueryV1): Boolean {
        if (request.ownerUid != query.fence.ownerUid) return false // Q02a
        if (request.binding != query.binding) return false // Q02b
        if (request.raisedAt.origin != query.order.origin) return false // Q02c
        if (query.order.value <= request.raisedAt.value) return false // Q03
        if (query.intent < request.intent) return false // Q04
        return true
    }
    fun consumes(outcome: EntitlementsOutcome, intent: RefreshIntent, reapproval: LifecycleReapproval): Boolean {
        if (reapproval == LifecycleReapproval.BLOCKED) return false // Q07
        return when (outcome) {
            is EntitlementsOutcome.Pending -> false // Q08a
            is EntitlementsOutcome.Indeterminate -> when (outcome.reason) {
                IndeterminateReason.AUTHENTICATION -> false // Q08b
                else -> false // Q08c
            }
            EntitlementsOutcome.KrxEntitlementRequired -> intent != RefreshIntent.FORCE_PREMIUM // Q09
            else -> true
        }
    }
    fun acceptance(d: AcceptedQueryDecision, runtime: DemandAuthRuntime): Boolean {
        if (d.source != LifecycleQuerySource.REGISTERED_QUERY) return false // Q01source
        if (d.registration !in runtime.registrations) return false // Q01
        if (d.answeredAs == null) return false // Q15a
        if (d.acceptedBeforeGeneration != d.query.generation) return false // Q05a
        if (d.acceptedBeforeFence != d.query.fence) return false // Q05b
        if (d.answeredAs?.ownerUid != d.query.fence.ownerUid) return false // Q05c
        if (d.answeredAs != runtime.liveIdentity) return false // Q05d
        if (d.answeredAs != d.query.boundIdentity) return false // Q05e
        return true
    }
    fun after(d: AcceptedQueryDecision, generation: Long, fence: FenceV1): Boolean {
        if (generation != d.expectedAfterGeneration) return false // Q06a
        if (fence != d.confirmedAfterFence) return false // Q06b
        return true
    }
    fun scope(auth: AuthSnapshotV1, binding: LifecycleBinding): Boolean {
        if (auth.ownerUid != binding.executor.ownerUid) return false // A01a
        if (auth.authGeneration != binding.identity?.authGeneration) return false // A01b
        if (auth.binding != binding.executor.binding) return false // A01c
        if (auth.originLifetimeId != binding.executor.originLifetimeId) return false // A01d
        return true
    }
    fun answerOrder(auth: AuthSnapshotV1, order: Long): Boolean {
        if (order <= auth.authStateOrder) return false // A02
        return true
    }
    fun authChange(before: AuthSnapshotV1, after: AuthSnapshotV1): Boolean {
        if (after.authStateOrder <= before.authStateOrder) return false // A03a
        if (after.authStopped && after.authStopAppliedOrder <= before.authStopAppliedOrder) return false // A03b
        if (!after.authStopped && after.authStopAppliedOrder != before.authStopAppliedOrder) return false // A03c
        return true
    }
    fun caller(fact: LifecycleCaller): Boolean {
        if (fact.origin != LifecycleCallerOrigin.CALLER) return false // A04
        return true
    }
    fun recovery(auth: AuthSnapshotV1, fact: LifecycleRecovery, runtime: DemandAuthRuntime): Boolean {
        if (!auth.authStopped) return false // A05a
        val bound = runtime.binding.identity
        if (bound == null) return false // A05b
        if (bound != null && fact.identity.ownerUid != bound.ownerUid) return false // A05c
        if (bound != null && fact.identity.authGeneration != bound.authGeneration) return false // A05d
        if (runtime.liveIdentity != fact.identity) return false // A05e
        if (fact.fetchStartedOrder <= runtime.binding.startedOrder) return false // A05f
        if (fact.fetchStartedOrder <= auth.authStateOrder) return false // A05g
        if (fact.recoveredOrder <= auth.authStopAppliedOrder) return false // A05h
        if (fact.episode <= runtime.consumedRecoveryEpisode) return false // A06
        return true
    }
    fun closure(auth: AuthSnapshotV1, closure: LifecycleBindingClosure): Boolean {
        if (!closure.entriesClosed) return false // A07a
        if (closure.joinedWork != closure.capturedWork) return false // A07b
        if (closure.scope != auth) return false // A07c
        return true
    }
    fun initialization(binding: LifecycleBinding, runtime: DemandAuthRuntime, existing: AuthSnapshotV1?): Boolean {
        if (binding.startEventId == null) return false // A12a
        if (binding.identity == null) return false // A12identity
        if (binding.identity != runtime.liveIdentity) return false // A12b
        if (binding != runtime.binding) return false // A12c
        if (existing != null) return false // A12d
        if (binding.acceptedAuthOrder != 0L) return false // A08
        if (binding.identity != null && binding.identity.ownerUid != binding.executor.ownerUid) return false // A12owner
        return true
    }
    fun seconds(seconds: Long?): Boolean {
        if (seconds != null && seconds < 0) return false // A15negative
        if (seconds != null && seconds > Long.MAX_VALUE / 1000) return false // A15a
        return true
    }
    fun floorOrigin(d: AcceptedQueryDecision): Boolean {
        if (d.floorOrigin != d.query.order.origin) return false // A15b
        return true
    }
    fun statedSeconds(outcome: EntitlementsOutcome): Long? = when (outcome) {
        is EntitlementsOutcome.Pending -> outcome.retryAfterSeconds
        is EntitlementsOutcome.Indeterminate -> outcome.retryAfterSeconds
        else -> null
    }
    fun retryIntent(d: AcceptedQueryDecision): RefreshIntent? {
        val intrinsic = when (val outcome = d.outcome) {
            is EntitlementsOutcome.Pending -> d.query.intent
            is EntitlementsOutcome.Indeterminate -> when (outcome.reason) {
                IndeterminateReason.AUTHENTICATION -> null
                IndeterminateReason.UNTYPED_FORBIDDEN -> RefreshIntent.FORCE_PREMIUM
                else -> d.query.intent
            }
            EntitlementsOutcome.KrxEntitlementRequired -> RefreshIntent.FORCE_ENTITLEMENTS
            else -> null
        }
        return listOfNotNull(intrinsic, d.followUp,
            RefreshIntent.FORCE_PREMIUM.takeIf { d.reapproval == LifecycleReapproval.BLOCKED }).maxOrNull()
    }
    fun minimumDelay(d: AcceptedQueryDecision): Long {
        val defaultNeeded = d.reapproval == LifecycleReapproval.BLOCKED ||
            (statedSeconds(d.outcome) == null && (d.outcome is EntitlementsOutcome.Pending ||
                (d.outcome as? EntitlementsOutcome.Indeterminate)?.reason in setOf(IndeterminateReason.TRANSIENT, IndeterminateReason.DECODE)))
        return maxOf(d.decisionMinDelayMillis, if (defaultNeeded) PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS else 0)
    }
}
