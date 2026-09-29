package com.jay.fxi.data.entitlements.purge

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.control.ControlRecordRead
import com.jay.fxi.data.entitlements.control.ControlRecordReader
import java.util.Collections

/**
 * Reads the control record and the purge journal from **one** Preferences snapshot (purger 설계 v3 final §7·§9, P2-J).
 *
 * Neither side's failure becomes the other side's "clean": the result is the union of the blocking reasons both
 * reads found. There is no `Open` variant — opening a protected entry also needs durable confirmation, settled
 * obligations, a finished purge and a fresh server approval, which are P3's. **Nothing in production calls this yet**:
 * the access-epoch store keeps its own four-field decoder and writer until P3 switches both together.
 */
class PurgeControlSnapshotReader(
    private val controlReader: ControlRecordReader = ControlRecordReader()
) {
    fun read(preferences: Preferences): PurgeControlSnapshotRead {
        val control = controlReader.read(preferences)
        val reasons = mutableSetOf<PurgeControlSnapshotRead.ClosedReason>()
        when (control) {
            is ControlRecordRead.MigrationOrRecoveryRequired ->
                reasons += PurgeControlSnapshotRead.ClosedReason.CONTROL_MIGRATION_OR_RECOVERY
            is ControlRecordRead.Unreadable ->
                reasons += PurgeControlSnapshotRead.ClosedReason.CONTROL_UNREADABLE
            is ControlRecordRead.Supported -> {
                if (control.schemaVersion != 2) reasons += PurgeControlSnapshotRead.ClosedReason.CONTROL_SCHEMA_MIGRATION_REQUIRED
                if (control.hasUninterpretable) reasons += PurgeControlSnapshotRead.ClosedReason.CONTROL_UNINTERPRETABLE_OBLIGATION
                if (control.hasUninterpretableMetadata) reasons += PurgeControlSnapshotRead.ClosedReason.CONTROL_UNINTERPRETABLE_METADATA
            }
        }

        // The access-epoch store's key name, written out: ControlOwnerStructureTest pins every line naming that store.
        // The contract builds its fixtures with the store's own key, so a renamed key fails it.
        val journalValue = control.original.asMap().entries.firstOrNull { it.key.name == "pending_purge_journal" }
        val journal = when {
            journalValue == null -> PurgeControlSnapshotRead.Journal.Absent
            journalValue.value !is String -> {
                reasons += PurgeControlSnapshotRead.ClosedReason.JOURNAL_WRONG_TYPE
                PurgeControlSnapshotRead.Journal.WrongType
            }
            else -> {
                val raw = journalValue.value as String
                val entries = Collections.unmodifiableList(PurgeJournalCodec.decodeAll(raw).map { entry ->
                    when (entry) {
                        is JournalEntry.Owed -> entry.copy(pending = entry.pending.copy(
                            scopes = Collections.unmodifiableSet(entry.pending.scopes.toSet())
                        ))
                        is JournalEntry.Uninterpretable -> entry.copy()
                    }
                })
                reasons += PurgeControlSnapshotRead.ClosedReason.JOURNAL_PRESENT
                if (entries.any { it is JournalEntry.Uninterpretable }) {
                    reasons += PurgeControlSnapshotRead.ClosedReason.JOURNAL_UNINTERPRETABLE
                }
                PurgeControlSnapshotRead.Journal.Present(raw, entries)
            }
        }

        return if (reasons.isEmpty()) {
            PurgeControlSnapshotRead.PendingRuntimeAdmission(control as ControlRecordRead.Supported)
        } else {
            PurgeControlSnapshotRead.Closed(control, journal, Collections.unmodifiableSet(reasons.toSet()))
        }
    }
}

sealed interface PurgeControlSnapshotRead {
    val control: ControlRecordRead
    val journal: Journal

    sealed interface Journal {
        /** The key is absent: the only empty journal (the writer removes the key when nothing is owed). */
        data object Absent : Journal

        /** The key holds text, even empty text; [entries] is the codec's reading of every line, in order. */
        data class Present internal constructor(val raw: String, val entries: List<JournalEntry>) : Journal

        /** The key holds something that is not text; the value stays in [control]'s original snapshot. */
        data object WrongType : Journal
    }

    enum class ClosedReason {
        CONTROL_MIGRATION_OR_RECOVERY,
        CONTROL_UNREADABLE,
        CONTROL_SCHEMA_MIGRATION_REQUIRED,
        CONTROL_UNINTERPRETABLE_OBLIGATION,
        CONTROL_UNINTERPRETABLE_METADATA,
        JOURNAL_WRONG_TYPE,
        JOURNAL_PRESENT,
        JOURNAL_UNINTERPRETABLE
    }

    /** At least one blocking reason; [reasons] holds every one found on either side. */
    data class Closed internal constructor(
        override val control: ControlRecordRead,
        override val journal: Journal,
        val reasons: Set<ClosedReason>
    ) : PurgeControlSnapshotRead

    /**
     * No blocking reason in the read: schema 2 with nothing uninterpretable and no journal key. This does not open a
     * protected entry — interpretable obligations may remain and admission is the runtime's decision.
     */
    data class PendingRuntimeAdmission internal constructor(
        override val control: ControlRecordRead.Supported
    ) : PurgeControlSnapshotRead {
        override val journal: Journal = Journal.Absent
    }
}
