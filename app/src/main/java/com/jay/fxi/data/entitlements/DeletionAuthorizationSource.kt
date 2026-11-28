package com.jay.fxi.data.entitlements

import com.jay.fxi.data.entitlements.purge.DeletionAuthorization
import com.jay.fxi.data.entitlements.purge.JournalEntry
import com.jay.fxi.data.entitlements.purge.PurgeClassification
import com.jay.fxi.data.entitlements.purge.PurgeCause
import com.jay.fxi.data.entitlements.purge.PurgeDecision
import com.jay.fxi.data.entitlements.purge.PurgeControlSnapshotRead.Journal
import com.jay.fxi.data.entitlements.purge.PurgeManifest
import com.jay.fxi.data.entitlements.purge.TargetDisposition
import java.util.concurrent.atomic.AtomicBoolean

/** Evidence read together by the access owner. The journal retains the individual original rows. */
internal data class RetirementObservation(val ownerUid: String?, val epochs: DeletionEpochs, val journal: Journal) {
    fun canReserve(owner: String, plan: DeletionRetirement): Boolean {
        if (owner != ownerUid || epochs != plan.before) return false
        return when (journal) {
            Journal.Absent -> true
            Journal.WrongType -> false
            is Journal.Present -> journal.entries.isNotEmpty() && journal.entries.all { entry ->
                entry is JournalEntry.Owed && entry.pending.ownerUid != null &&
                    entry.pending != plan.obligation(owner).pending
            }
        }
    }

    fun confirmedRow(owner: String, plan: DeletionRetirement): String? {
        if (owner != ownerUid || epochs != plan.after || journal !is Journal.Present) return null
        val lines = journal.raw.split('\n')
        if (lines.size != journal.entries.size) return null
        val expected = plan.obligation(owner)
        var matched: String? = null
        for ((index, entry) in journal.entries.withIndex()) {
            if (entry !is JournalEntry.Owed || entry.pending.ownerUid == null) return null
            if (entry.pending == expected.pending) {
                if (entry != expected || matched != null) return null
                matched = lines[index]
            }
        }
        return matched
    }
}

internal fun journalReference(row: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(row.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** Tickets carry no reusable purge authorization. Only the serial, checked application mints it. */
internal class DeletionAuthorizationSource(private val store: DeletionRecordStore) {
    internal class Ticket internal constructor(
        internal val record: DeletionRecord, internal val row: String, internal val scope: PurgeScope,
        internal val targetId: String
    ) {
        internal val spent = AtomicBoolean(false)
    }

    suspend fun issue(record: DeletionRecord, row: String, scope: PurgeScope, targetId: String,
        observe: suspend () -> RetirementObservation): Ticket? {
        val valid = store.withCurrent(record) { eligible(record, row, scope, targetId, observe()) }
        return if (valid) Ticket(record, row, scope, targetId) else null
    }

    /** Caller holds the access/journal owner's serial section through observe and destructive apply. */
    suspend fun consume(ticket: Ticket, observe: suspend () -> RetirementObservation, apply: suspend () -> Unit): Boolean =
        store.withCurrent(ticket.record) {
            if (!ticket.spent.compareAndSet(false, true) ||
                !eligible(ticket.record, ticket.row, ticket.scope, ticket.targetId, observe())) false
            else { apply(); true }
        }

    private fun eligible(record: DeletionRecord, row: String, scope: PurgeScope, targetId: String,
        observed: RetirementObservation): Boolean {
        if (record.phase == DeletionPhase.REQUESTING_SERVER || record.journalReference != journalReference(row)) return false
        val plan = record.retirement ?: return false
        if (observed.confirmedRow(record.ownerUid, plan) != row) return false
        val target = PurgeManifest.TARGETS.singleOrNull { it.id == targetId } ?: return false
        val namespace = PurgeNamespace(record.ownerUid, plan.after.user, plan.after.capability,
            plan.obligation(record.ownerUid).pending)
        if (scope !in target.scopes) return false
        val authorization = if (scope == PurgeScope.USER && target.classification == PurgeClassification.ACCOUNT_DELETION_ONLY) {
            DeletionAuthorization(record.ownerUid, record.operationId, true)
        } else null
        return PurgeDecision.disposition(target, scope, PurgeCause.ACCOUNT_DELETION, namespace, authorization) ==
            TargetDisposition.DELETE_NOW
    }
}
