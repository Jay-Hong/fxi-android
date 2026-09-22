package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.RefreshIntent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Independent truth vectors for the named DemandAuth writers (design §3.1 135–139, §4.2 239–268, §4.5 306–312,
 * §5.1 316–353, §5.2 355–364). Each entry is a named sub-condition computed from fixture facts and the design
 * sentence. It never calls DemandAuthBoundary, DemandAuthTransition or a candidate validator; reader/schema
 * parsing and `locations` are used only to observe stored rows.
 *
 * A condition that reads an absent fact is vacuously true; the absence itself is its own entry. Grant issuance is
 * the source's documented rule (the issued value is one past the largest of last issue, binding start and lower bound).
 */
internal object DemandAuthTruthVector {
    fun falses(vector: List<Pair<String, Boolean>>): Set<String> = vector.filterNot { it.second }.map { it.first }.toSet()

    private fun rawFence(raw: Preferences) = FenceV1(raw[OWNER_UID], raw[USER_EPOCH], raw[KRX_EPOCH])
    private fun id(node: ControlNode) = (node.toPayloadEntry().fields["id"] as? JsonPrimitive)?.content
    private fun read(raw: Preferences) = ControlRecordReader().read(raw) as ControlRecordRead.Supported

    /** Exactly one stored location of that kind, interpretable, with the same payload. */
    fun exactRow(read: ControlRecordRead.Supported, kind: ControlKind, node: ControlNode): Boolean {
        val hits = id(node)?.let { read.locations(it) } ?: return false
        if (hits.size != 1 || hits[0].first != kind) return false
        return (hits[0].second as? ControlEntryRead.Interpreted)?.original?.toPayloadEntry() == node.toPayloadEntry()
    }

    /** §4.2 table 262–268. */
    fun settles(outcome: EntitlementsOutcome, intent: RefreshIntent) = when (outcome) {
        is EntitlementsOutcome.StableActive, is EntitlementsOutcome.StableInactive, EntitlementsOutcome.PremiumRequired -> true
        EntitlementsOutcome.KrxEntitlementRequired -> intent != RefreshIntent.FORCE_PREMIUM
        else -> false
    }

    private class Builder {
        val v = mutableListOf<Pair<String, Boolean>>()
        fun p(name: String, holds: Boolean) { v += name to holds }
        /** §5.1 316: scope = (ownerUid, authGeneration, binding, origin). */
        fun scope(tag: String, a: AuthSnapshotV1, b: LifecycleBinding) {
            p("$tag.scopeOwner", a.ownerUid == b.executor.ownerUid)
            p("$tag.scopeGeneration", a.authGeneration == b.identity?.authGeneration)
            p("$tag.scopeBinding", a.binding == b.executor.binding)
            p("$tag.scopeOrigin", a.originLifetimeId == b.executor.originLifetimeId)
        }
        /** §4.5 306–310: current origin, issued by the source, after binding start and after the lower bound. */
        fun order(tag: String, g: LifecycleOrderGrant, b: LifecycleBinding, lower: Long) {
            p("$tag.orderOrigin", g.origin == b.executor.originLifetimeId)
            p("$tag.orderIssued", g.value == maxOf(g.previous, g.bindingStart, g.after) + 1)
            p("$tag.orderAfterStart", g.value > b.startedOrder)
            p("$tag.orderAfterLower", g.value > lower)
        }
        /** §5.1 320: initial installation. */
        fun init(tag: String, b: LifecycleBinding, rt: DemandAuthRuntime, existing: AuthSnapshotV1?) {
            p("$tag.startEvent", b.startEventId != null)
            p("$tag.identity", b.identity != null)
            p("$tag.live", b.identity == null || b.identity == rt.liveIdentity)
            p("$tag.binding", b == rt.binding)
            p("$tag.noAuth", existing == null)
            p("$tag.noAcceptedEvent", b.acceptedAuthOrder == 0L)
            p("$tag.owner", b.identity == null || b.identity.ownerUid == b.executor.ownerUid)
        }
    }

    /** Eligibility of a prepared plan against a freshly captured runtime and the current raw record. */
    fun eligibility(plan: DemandAuthPlan, rt: DemandAuthRuntime, raw: Preferences): List<Pair<String, Boolean>> = Builder().run {
        val x = plan.binding.executor
        val current = read(raw)
        val before = guard(plan.guardBefore)?.auth
        val after = guard(plan.guardAfter)?.auth
        val d = plan.decision
        if (d != null) {
            val q = d.query
            val answered = d.answeredAs
            p("q.source", d.source == LifecycleQuerySource.REGISTERED_QUERY)
            p("q.registered", d.registration in rt.registrations)
            p("q.answered", answered != null)
            p("q.beforeGeneration", d.acceptedBeforeGeneration == q.generation)
            p("q.beforeFence", d.acceptedBeforeFence == q.fence)
            p("q.answeredOwner", answered == null || answered.ownerUid == q.fence.ownerUid)
            p("q.answeredLive", answered == null || answered == rt.liveIdentity)
            p("q.answeredBound", answered == null || answered == q.boundIdentity)
            p("q.owner", q.fence.ownerUid == x.ownerUid)
            p("q.binding", q.binding == x.binding)
            p("q.origin", q.order.origin == x.originLifetimeId)
            p("after.generation", rt.generation == d.expectedAfterGeneration)
            p("after.fence", rawFence(raw) == d.confirmedAfterFence)
            val ns = d.namespaceConfirmation
            val namespaceRequired = d.confirmedAfterFence != d.acceptedBeforeFence
            p("effects.namespaceProof", !namespaceRequired || ns != null)
            p("effects.namespaceFence", !namespaceRequired || ns == null ||
                rawFence(ns.record.original) == d.confirmedAfterFence)
            d.effects.forEachIndexed { i, e ->
                val parses = ControlSchema.read(e.kind, e.node) != null
                p("effects[$i].parses", parses)
                p("effects[$i].proof", e.confirmation != null)
                p("effects[$i].proofExact", !parses || e.confirmation == null || exactRow(e.confirmation.record, e.kind, e.node))
                p("effects[$i].currentExact", !parses || exactRow(current, e.kind, e.node))
            }
            for (t in plan.targets.filter { it.target.effect == LifecycleEffect.REMOVE }) {
                val tag = "remove[${t.target.id}]"
                val r = demand(t.before)
                p("$tag.request", r != null)
                if (r != null) {
                    p("$tag.owner", r.ownerUid == q.fence.ownerUid)
                    p("$tag.binding", r.binding == q.binding)
                    p("$tag.origin", r.raisedAt.origin == q.order.origin)
                    p("$tag.start", q.order.value > r.raisedAt.value)
                    p("$tag.intent", q.intent >= r.intent)
                    p("$tag.reapproval", d.reapproval != LifecycleReapproval.BLOCKED)
                    p("$tag.settles", settles(d.outcome, r.intent))
                }
            }
            if (before != null) scope("answer", before, plan.binding)
            if (plan.transition == LifecycleTransition.UPDATE_AUTH) {
                p("update.auth", before != null)
                p("update.order", before == null || q.order.value > before.authStateOrder)
                p("update.change", before == null || before != after)
            }
            if (plan.transition == LifecycleTransition.SETTLE_QUERY && plan.targets.none { it.target.effect == LifecycleEffect.REMOVE })
                p("settle.zeroRemoveKeepsAuth", before == after)
        }
        for (t in plan.targets.filter { it.role == LifecycleRole.REQUEST && it.target.effect != LifecycleEffect.REMOVE }) {
            val tag = "request[${t.target.id}]"
            val old = demand(t.before)
            val next = demand(t.after)
            p("$tag.typed", next != null)
            if (plan.transition == LifecycleTransition.REBIND_REQUESTS || plan.transition == LifecycleTransition.END_AUTH_BINDING) {
                p("$tag.old", old != null)
                if (old != null && next != null) {
                    p("$tag.rebindOwner", old.ownerUid == x.ownerUid)
                    p("$tag.rebindNeeded", !(old.binding == x.binding && old.raisedAt.origin == x.originLifetimeId))
                    p("$tag.rebindIntent", next.intent >= old.intent)
                }
            }
            val g = plan.grants[t.target.id]
            p("$tag.grant", g != null)
            if (g != null && next != null) {
                val sameOrigin = old != null && old.raisedAt.origin == x.originLifetimeId
                order(tag, g, plan.binding, maxOf(d?.query?.order?.value ?: 0, if (sameOrigin) old!!.raisedAt.value else 0))
                p("$tag.link", next.raisedAt.value == g.value)
            }
        }
        when (val e = plan.event) {
            LifecycleAuthEvent.Initialize -> init("init", plan.binding, rt, before)
            is LifecycleAuthEvent.Caller -> {
                val f = e.fact
                p("caller.event", rt.caller == f)
                p("caller.pin", f.binding == plan.binding)
                p("caller.identity", rt.liveIdentity == plan.binding.identity)
                p("caller.origin", f.origin == LifecycleCallerOrigin.CALLER)
                p("caller.auth", before != null)
                if (before != null) { scope("caller", before, plan.binding); order("caller", f.order, plan.binding, before.authStateOrder) }
            }
            is LifecycleAuthEvent.Recovery -> {
                val f = e.fact
                val bound = rt.binding.identity
                p("recovery.event", rt.recovery == f)
                p("recovery.origin", f.origin == x.originLifetimeId)
                p("recovery.auth", before != null)
                if (before != null) {
                    scope("recovery", before, plan.binding)
                    p("recovery.stopped", before.authStopped)
                    p("recovery.bound", bound != null)
                    p("recovery.uid", bound == null || f.identity.ownerUid == bound.ownerUid)
                    p("recovery.generation", bound == null || f.identity.authGeneration == bound.authGeneration)
                    p("recovery.live", rt.liveIdentity == f.identity)
                    p("recovery.fetchAfterStart", f.fetchStartedOrder > rt.binding.startedOrder)
                    p("recovery.fetchAfterState", f.fetchStartedOrder > before.authStateOrder)
                    p("recovery.afterStopApplied", f.recoveredOrder > before.authStopAppliedOrder)
                    p("recovery.episode", f.episode > rt.consumedRecoveryEpisode)
                }
            }
            is LifecycleAuthEvent.Answer, null -> Unit
        }
        if (plan.transition == LifecycleTransition.END_AUTH_BINDING) {
            val c = plan.closure
            p("end.closure", c != null)
            p("end.auth", before != null)
            if (c != null) {
                p("end.entriesClosed", c.entriesClosed)
                p("end.joined", c.joinedWork == c.capturedWork)
                p("end.closureCurrent", rt.closure == c)
                p("end.closureGeneration", rt.generation == c.generation)
                if (before != null) {
                    p("end.scope", c.scope == before)
                    p("end.notCurrentScope", !(before.ownerUid == x.ownerUid && before.binding == x.binding &&
                        before.originLifetimeId == x.originLifetimeId))
                }
            }
            plan.replacement?.let { init("end.replacement", it, rt, null) }
            val owed = current.arrays.getValue(ControlKind.DEMAND).entries.filterIsInstance<ControlEntryRead.Interpreted>()
                .mapNotNull { it.value as? DemandV1 }
                .filter { it.ownerUid == x.ownerUid && (it.binding != x.binding || it.raisedAt.origin != x.originLifetimeId) }
                .map { it.id }.toSet()
            p("end.owed", owed == plan.targets.filter { it.role == LifecycleRole.REQUEST }.map { it.target.id }.toSet())
        } else if (before != null && after != null && before != after) {
            p("change.state", after.authStateOrder > before.authStateOrder)
            p("change.stop", !after.authStopped || after.authStopAppliedOrder > before.authStopAppliedOrder)
            p("change.resume", after.authStopped || after.authStopAppliedOrder == before.authStopAppliedOrder)
            if (after.authStopped) {
                val g = plan.authStopGrant
                p("stop.grant", g != null)
                if (g != null) {
                    order("stop", g, plan.binding, maxOf(before.authStopAppliedOrder, after.authStateOrder))
                    p("stop.link", after.authStopAppliedOrder == g.value)
                }
            }
        }
        v.toList()
    }

    /**
     * Partial writer-gate vector: context, named currentness, preimages and guard creation.
     * This does not check schema/metadata, descriptor validity, raw types/epoch domains,
     * command-ID availability, journal validity or candidate effects.
     * Callers must establish those premises separately before claiming all non-target gates hold.
     */
    fun decideGates(plan: DemandAuthPlan, context: AttemptContext, raw: Preferences): List<Pair<String, Boolean>> = Builder().run {
        val x = plan.binding.executor
        val current = read(raw)
        p("lc.contextOwner", context.ownerUid == x.ownerUid)
        p("lc.contextBinding", context.binding == x.binding)
        p("lc.contextOrigin", context.originLifetimeId == x.originLifetimeId)
        p("lc.signOut", !context.signOutOpen)
        p("lc.identityPending", !context.identityPersistencePending)
        p("lc.teardown", raw[TEARDOWN_OWED_FOR] == null)
        val rt = context.demandAuth
        p("dt.runtime", rt != null)
        p("dt.rawOwner", raw[OWNER_UID] == x.ownerUid)
        p("dt.runtimeBinding", rt == null || rt.binding == plan.binding)
        p("dt.origin", x.originLifetimeId.value.isNotEmpty())
        p("dt.start", plan.binding.startedOrder >= 0)
        p("dt.bindingDomain", x.binding >= 0)
        for (t in plan.targets) {
            if (t.target.effect == LifecycleEffect.CREATE) p("target[${t.target.id}].absent", current.locations(t.target.id).isEmpty())
            else p("target[${t.target.id}].preimage", exactRow(current, t.target.kind, t.before!!))
        }
        for (t in plan.unchanged) p("unchanged[${t.target.id}].preimage", exactRow(current, t.target.kind, t.before!!))
        val guards = current.arrays.getValue(ControlKind.DEMAND).entries.filterIsInstance<ControlEntryRead.Interpreted>()
            .count { it.value is ScheduleGuardV1 }
        p("dt.guardCreate", plan.guardBefore != null || plan.guardAfter == null || guards == 0)
        v.toList()
    }
    /**
     * The premises decideGates leaves out (design §3.1 gates 1, 2, 4, 5 and §3.2 target shapes), computed from the raw
     * record and the plan's fixed targets: schema 2 with interpretable obligations and metadata; descriptor ids unique;
     * each target's kind/role/effect/before/after shape and the transition's target order; raw types and epoch domains;
     * a nonempty command id free in the evidence log. Inputs are Reader-produced Supported records for the four named
     * DemandAuth transitions. Empty SEAL and absent journal are asserted fixture restrictions, not general eligibility
     * rules. The empty SEAL restriction also excludes settlement-operation id collisions. Candidate effects are not covered here.
     */
    fun commonPremises(plan: DemandAuthPlan, raw: Preferences, commandId: String): List<Pair<String, Boolean>> = Builder().run {
        val current = read(raw)
        p("lc.schema2", current.schemaVersion == 2)
        p("lc.obligationsInterpretable", !current.hasUninterpretable)
        p("lc.metadataInterpretable", !current.hasUninterpretableMetadata)
        val all = plan.targets + plan.unchanged
        p("lc.descriptorIds", all.map { it.target.id }.toSet().size == all.size)
        fun typed(node: ControlNode?, id: String, role: LifecycleRole): Boolean {
            val value = node?.let { ControlSchema.read(ControlKind.DEMAND, it) } ?: return false
            return value.id == id && when (role) {
                LifecycleRole.REQUEST -> value is DemandV1
                LifecycleRole.GUARD -> value is ScheduleGuardV1
                else -> false
            }
        }
        for (t in plan.targets) {
            val id = t.target.id
            p("descriptor[$id].shape", t.target.kind == ControlKind.DEMAND && when (t.target.effect) {
                LifecycleEffect.REMOVE -> typed(t.before, id, t.role) && t.after == null
                LifecycleEffect.CREATE -> t.before == null && typed(t.after, id, t.role)
                LifecycleEffect.REPLACE -> typed(t.before, id, t.role) && typed(t.after, id, t.role) &&
                    t.before!!.toPayloadEntry() != t.after!!.toPayloadEntry()
            })
        }
        for (t in plan.unchanged) p("unchanged[${t.target.id}].shape",
            t.target.kind == ControlKind.DEMAND && t.target.effect == LifecycleEffect.REPLACE &&
                typed(t.before, t.target.id, t.role) && t.before!!.toPayloadEntry() == t.after?.toPayloadEntry())
        val seq = plan.targets.map { it.role to it.target.effect }
        val request = LifecycleRole.REQUEST; val guardRole = LifecycleRole.GUARD
        p("descriptor.order", seq.isNotEmpty() && when (plan.transition) {
            LifecycleTransition.REBIND_REQUESTS -> seq.all { it == request to LifecycleEffect.REPLACE }
            LifecycleTransition.SETTLE_QUERY -> {
                val removes = seq.takeWhile { it == request to LifecycleEffect.REMOVE }
                val rest = seq.drop(removes.size)
                rest.none { it.second == LifecycleEffect.REMOVE } &&
                    rest.map { it.first } in listOf(emptyList(), listOf(request), listOf(guardRole), listOf(request, guardRole)) &&
                    (removes.isEmpty() || rest.none { it.first == request && it.second != LifecycleEffect.CREATE })
            }
            LifecycleTransition.UPDATE_AUTH -> seq[0].first == guardRole && seq[0].second != LifecycleEffect.REMOVE &&
                seq.drop(1).let { it.isEmpty() || (it.size == 1 && it[0].first == request && it[0].second != LifecycleEffect.REMOVE) }
            LifecycleTransition.END_AUTH_BINDING -> seq[0] == guardRole to LifecycleEffect.REPLACE &&
                seq.drop(1).all { it == request to LifecycleEffect.REPLACE }
            else -> false
        })
        val rawValues = raw.asMap().entries.associate { it.key.name to it.value }
        for (key in listOf(OWNER_UID, USER_EPOCH, KRX_EPOCH, TEARDOWN_OWED_FOR, PURGE_JOURNAL)) {
            val value = rawValues[key.name]
            p("raw[${key.name}].string", value == null || value is String)
        }
        for (key in listOf(MAY_CONTAIN_PREMIUM, MAY_CONTAIN_KRX)) {
            val value = rawValues[key.name]
            p("raw[${key.name}].boolean", value == null || value is Boolean)
        }
        p("lc.userEpoch", rawValues[USER_EPOCH.name].let { it !is String || it.isNotEmpty() })
        p("lc.krxEpoch", rawValues[KRX_EPOCH.name].let { it !is String || it.isNotEmpty() })
        p("lc.commandIdNonempty", commandId.isNotEmpty())
        val evidence = raw[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)]
        p("lc.commandIdFree", evidence == null || (Json.parseToJsonElement(evidence) as JsonArray).none {
            ((it as? JsonObject)?.get("commandId") as? JsonPrimitive)?.content == commandId })
        p("lc.noSeals", current.arrays.getValue(ControlKind.SEAL).entries.isEmpty())
        p("dt.journalAbsent", rawValues[PURGE_JOURNAL.name] == null)
        v.toList()
    }
}
