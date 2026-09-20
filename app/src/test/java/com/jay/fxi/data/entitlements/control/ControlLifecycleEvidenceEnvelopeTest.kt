package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlLifecycleEvidenceFixtures as F
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ControlLifecycleEvidenceEnvelopeTest {
    private val codec = ControlPayloadCodec()
    private fun sized(bytes: Int, token: String, commandId: Boolean): String {
        val initial = if (commandId) F.wire(command = token) else F.wire(targets = F.target(id = token))
        val base = initial.toByteArray(Charsets.UTF_8).size + 2
        require(base <= bytes)
        val id = token + "x".repeat(bytes - base)
        return "[" + (if (commandId) F.wire(command = id) else F.wire(targets = F.target(id = id))) + "]"
    }
    private fun exactSize(id: String, token: String, commandId: Boolean, bytes: Int) {
        val raw = sized(bytes, token, commandId)
        assertEquals(bytes, raw.toByteArray(Charsets.UTF_8).size)
        val unconstrained = ControlPayloadCodec(maxPayloadBytes = 100_000).decode(raw) as PayloadRead.Parsed
        assertFalse(ControlEvidenceReader.read(unconstrained).hasUninterpretable)
        if (bytes == 65_536) {
            assertTrue(F.atomic(id), codec.decode(raw) is PayloadRead.Parsed && codec.encode(unconstrained.entries) is PayloadWrite.Encoded)
        } else {
            assertTrue(F.atomic(id), codec.decode(raw) is PayloadRead.Unreadable && codec.encode(unconstrained.entries) is PayloadWrite.TooLarge)
        }
    }
    @Test fun E01_asciiAtLimit() = exactSize("E01", "ascii", false, 65_536)
    @Test fun E02_asciiOverLimit() = exactSize("E02", "ascii", false, 65_537)
    @Test fun E03_unicodeAtLimit() = exactSize("E03", "한글😀".repeat(100), false, 65_536)
    @Test fun E04_unicodeOverLimit() = exactSize("E04", "한글😀".repeat(100), false, 65_537)
    @Test fun E05_escapesAtLimit() = exactSize("E05", "\"\\\n".repeat(100), false, 65_536)
    @Test fun E06_escapesOverLimit() = exactSize("E06", "\"\\\n".repeat(100), false, 65_537)
    @Test fun E07_longCommandAtLimit() = exactSize("E07", "command".repeat(100), true, 65_536)
    @Test fun E08_longCommandOverLimit() = exactSize("E08", "command".repeat(100), true, 65_537)
    private fun deep(depth: Int): String = "[${F.wire()}," + "[".repeat(depth - 2) + "0" + "]".repeat(depth - 2) + "]"
    @Test fun E09_depth64KeepsSupportedOpaqueSibling() {
        val raw = deep(64)
        val decoded = codec.decode(raw)
        assertTrue(F.atomic("E09"), decoded is PayloadRead.Parsed)
        assertTrue(codec.encode((decoded as PayloadRead.Parsed).entries) is PayloadWrite.Encoded)
        val read = F.read(F.raw(evidence = raw))
        assertTrue((read.metadata as ControlMetadataRead.V2).evidence.entries.first() is ControlEvidenceEntryRead.Interpreted)
        assertTrue(read.hasUninterpretableMetadata)
    }
    @Test fun E10_depth65RejectsWholeEnvelope() {
        val raw = deep(65)
        val decoded = codec.decode(raw)
        assertTrue(F.atomic("E10"), decoded is PayloadRead.Unreadable)
        val parsed = ControlPayloadCodec(maxDepth = 66).decode(raw) as PayloadRead.Parsed
        assertTrue(runCatching { codec.encode(parsed.entries) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(ControlRecordReader().read(F.raw(evidence = raw)) is ControlRecordRead.Unreadable)
    }
    @Test fun E11_appendAtLimitCannotDropEvidenceToFit() {
        val full = sized(65_536, "existing", true)
        val read = F.read(F.raw(evidence = full))
        assertFalse(read.blocksProtectedAdmission)
        val candidate = read.original.toMutablePreferences()
        val before = candidate.toPreferences()
        val failure = ControlAppliedEvidence.append(candidate, read, F.row(F.command()), codec)
        assertTrue(F.atomic("E11"), failure is RejectionReason.TooLarge && before == candidate)
    }
}
