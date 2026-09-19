package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences

/** Builds only a validated removal/absence candidate. This is never storage confirmation. */
internal object ControlReleaseCandidate {
    sealed interface Result {
        data class Ready(val snapshot: Preferences) : Result
        data class Rejected(val reason: RejectionReason) : Result
        data object Inconsistent : Result
    }

    fun build(read: ControlRecordRead.Supported, command: CommandRef, codec: ControlPayloadCodec): Result {
        val entries = (read.metadata as ControlMetadataRead.V2).evidence.entries
            .map { it as ControlEvidenceEntryRead.Interpreted }
        val remaining = entries.filterNot { it.value.commandId == command.id }
        val candidate = if (remaining.size == entries.size) read.original else {
            val encoded = try { codec.encode(remaining.map { it.original.toPayloadEntry() }) }
            catch (_: IllegalArgumentException) {
                return Result.Rejected(RejectionReason.InvalidRequest("release violates codec envelope constraints"))
            }
            when (encoded) {
                is PayloadWrite.TooLarge -> return Result.Rejected(RejectionReason.TooLarge(
                    ControlPayloadKey.COMMAND_EVIDENCE, encoded.bytes, encoded.limit))
                is PayloadWrite.Encoded -> read.original.toMutablePreferences().apply {
                    this[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)] = encoded.text
                }
            }
        }
        val complete = ControlRecordReader(codec).read(candidate)
        if (!hasAbsencePostcondition(complete, command)) return Result.Inconsistent
        val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
        if (candidate.toMutablePreferences().apply { remove(evidenceKey) } !=
            read.original.toMutablePreferences().apply { remove(evidenceKey) }) {
            return Result.Inconsistent
        }
        val finalEntries = ((complete as ControlRecordRead.Supported).metadata as ControlMetadataRead.V2).evidence.entries
            .map { (it as ControlEvidenceEntryRead.Interpreted).original.toPayloadEntry() }
        if (finalEntries != remaining.map { it.original.toPayloadEntry() }) return Result.Inconsistent
        return Result.Ready(candidate.toPreferences())
    }

    fun hasAbsencePostcondition(read: ControlRecordRead, command: CommandRef): Boolean =
        read is ControlRecordRead.Supported && read.schemaVersion == 2 &&
            !read.hasUninterpretableMetadata && !read.hasUninterpretable &&
            ControlAppliedEvidence.own(read, command) == null
}
