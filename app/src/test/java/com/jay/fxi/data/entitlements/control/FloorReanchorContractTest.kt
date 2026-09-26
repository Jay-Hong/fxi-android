package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 6-1C T8 contract, part c: the nine floor re-anchoring rows of design r3 §10.2 as pure inputs (API r2
 * §2.2·§4.1; revision 06 §4.2 "floor 재앵커링 상세 계약"), plus every other ReanchorField and floorLowerBound reason.
 * Fixtures are schema-valid HOLDs (floor wait = retryAfterSeconds × 1000, floor origin = HOLD origin).
 *
 * Source-binding reason mapping fixed by this contract (each negative changes one thing): sourceId ≠ parsedHold.id →
 * SOURCE_ID; the submitted original re-parses to a HOLD differing from parsedHold only in binding/origin/axes →
 * SOURCE_SUBJECT, only in provenance → PROVENANCE, only in outcome (the remaining payload) → SOURCE_TEXT;
 * FloorSource.floor ≠ parsedHold.floor → SOURCE_FLOOR. A re-ordered but otherwise identical original is the same
 * source (ControlNode payload, not byte formatting). parsedHold is caller-supplied typed data: the schema couples
 * binding/origin/axes/outcome to provenance and floor, so SOURCE_SUBJECT and SOURCE_TEXT negatives deliberately use an
 * inconsistent typed snapshot (not reachable through the schema) to aim at each binding predicate alone. Whether a storage confirmation really happened, and latest
 * re-chaining, are the 6-4b owner's (G05C/G05.confirmation). The implementation thread reads but does not edit this file.
 */
class FloorReanchorContractTest {
    private val originH = LifetimeId("originH")
    private val originC2 = LifetimeId("originC2")
    private val mergeNow = BootReading("B", 1000)
    private fun floorJson(boot: String?, elapsed: Long, wait: Long, origin: String) =
        """{"anchorBootId":${boot?.let { "\"$it\"" } ?: "null"},"anchorElapsedMillis":$elapsed,"waitMillis":$wait,"originLifetimeId":"$origin"}"""
    private fun holdJson(id: String = "h", seconds: Long = 1, provenance: String = ControlObligationFixtures.query) =
        """{"id":"$id","originLifetimeId":"originH","binding":3,"axes":["CAPABILITY"],"outcome":{"kind":"PENDING","krxVisible":false,"retryAfterSeconds":$seconds},"provenance":$provenance,"floor":${floorJson("A", 5, seconds * 1000, "originH")}}"""
    private fun holdJsonReordered() =
        """{"originLifetimeId":"originH","id":"h","binding":3,"axes":["CAPABILITY"],"outcome":{"kind":"PENDING","krxVisible":false,"retryAfterSeconds":1},"provenance":${ControlObligationFixtures.query},"floor":${floorJson("A", 5, 1000, "originH")}}"""
    private fun guardJson(id: String, floor: String?, auth: String? = null) =
        "{\"id\":\"$id\",\"kind\":\"SCHEDULE_GUARD\"" + (floor?.let { ",\"floor\":$it" } ?: "") + (auth?.let { ",\"auth\":$it" } ?: "") + "}"
    private fun parsed(json: String): RestoredHold =
        checkNotNull(ControlSchema.read(ControlKind.HOLD, node(json)) as? RestoredHold) { "fixture: HOLD must be schema-valid: $json" }
    private fun source(id: String = "h", seconds: Long = 1, guardBefore: String? = null): FloorSource {
        val base = parsed(holdJson(id = id, seconds = seconds))
        return FloorSource(id, node(holdJson(id = id, seconds = seconds)), base, checkNotNull(base.floor), guardBefore?.let(::node))
    }
    private val src = source()
    private val output = guardJson("g-new", floorJson("B", 1000, 1000, "originC2"))
    private fun link(s: FloorSource = src, out: String = output, confirmed: String = out, oldGuard: String? = null,
        destination: String = "g-new") = ConfirmedHoldReanchor(s, oldGuard?.let(::node), mergeNow, originC2, destination,
        node(out), node(confirmed), ConfirmationProof(RecordTransactionEvidence.CompletedWriteScope))
    private fun validate(s: FloorSource = src, out: String = output, oldGuard: String? = null, destination: String = "g-new",
        now: BootReading = mergeNow, origin: LifetimeId = originC2) = validHoldReanchor(s, oldGuard?.let(::node), now, origin, destination, node(out))
    private fun invalid(id: String, field: ReanchorField, result: ReanchorValidation) =
        assertEquals("D2B6/T8c.$id: $field", ReanchorValidation.Invalid(field), result)
    private fun bound(id: String, expected: Long, result: FloorBoundResult) =
        assertEquals("D2B6/T8c.$id: bound", FloorBoundResult.Bound(expected), result)
    private fun unavailable(id: String, reason: FloorBoundUnavailableReason, result: FloorBoundResult) =
        assertEquals("D2B6/T8c.$id: $reason", FloorBoundResult.Unavailable(reason), result)

    // 1 confirmedReanchorAcceptsElapsedRemainder
    @Test fun T8c_01_confirmedReanchorAcceptsElapsedRemainder() {
        val v = validate()
        assertEquals("D2B6/T8c.01: valid", ReanchorValidation.Valid(FloorV1("B", 1000, 1000, originC2)), v)
        val now = BootReading("B", 1100)
        bound("01", 900, floorLowerBound(listOf(src), listOf(link()), now)) // Not the old boot's full 1000 again.
        assertEquals("D2B6/T8c.01: latestMeets", TypedComparison.Matches, compareFloor(900, FloorV1("B", 1000, 1000, originC2), now))
    }
    // 2 unhandedSourceKeepsFullWait
    @Test fun T8c_02_unhandedSourceKeepsFullWait() {
        val now = BootReading("B", 1100)
        bound("02", 1000, floorLowerBound(listOf(src), emptyList(), now))
        assertEquals("D2B6/T8c.02: unrelated900Short", TypedComparison.Mismatch(FloorField.LOWER_BOUND),
            compareFloor(1000, FloorV1("B", 1000, 1000, originC2), now))
    }
    // 3 wrongReanchorSource — one binding at a time
    @Test fun T8c_03_wrongReanchorSource() {
        val otherQuery = ControlObligationFixtures.query.replace("\"order\":7", "\"order\":8").also { check(it != ControlObligationFixtures.query) }
        invalid("03a", ReanchorField.SOURCE_ID, validate(s = src.copy(sourceId = "h2")))
        invalid("03b", ReanchorField.SOURCE_SUBJECT, validate(s = src.copy(parsedHold = src.parsedHold.copy(binding = 4))))
        invalid("03c", ReanchorField.PROVENANCE, validate(s = src.copy(parsedHold = src.parsedHold.copy(
            provenance = parsed(holdJson(provenance = otherQuery)).provenance))))
        invalid("03d", ReanchorField.SOURCE_TEXT, validate(s = src.copy(parsedHold = src.parsedHold.copy(
            outcome = src.parsedHold.outcome.copy(krxVisible = true)))))
        invalid("03e", ReanchorField.SOURCE_FLOOR, validate(s = src.copy(floor = FloorV1("A", 5, 2000, originH))))
        assertTrue("D2B6/T8c.03f: reorderedOriginalIsTheSameSource",
            validate(s = src.copy(originalHold = node(holdJsonReordered()))) is ReanchorValidation.Valid)
    }
    // 4 wrongReanchorAnchor — one output field at a time
    @Test fun T8c_04_wrongReanchorAnchor() {
        fun out(boot: String, elapsed: Long, origin: String) = guardJson("g-new", floorJson(boot, elapsed, 1000, origin))
        invalid("04a", ReanchorField.ANCHOR_BOOT, validate(out = out("C", 1000, "originC2")))
        invalid("04b", ReanchorField.ANCHOR_ELAPSED, validate(out = out("B", 999, "originC2")))
        invalid("04c", ReanchorField.ORIGIN, validate(out = out("B", 1000, "originX")))
        invalid("04d", ReanchorField.OUTPUT_FLOOR, validate(out = guardJson("g-new", null)))
    }
    // 5 wrongInitialFullWait — short and long both rejected; old guard remainder participates in the max
    @Test fun T8c_05_wrongInitialFullWait() {
        fun out(wait: Long, id: String = "g-new") = guardJson(id, floorJson("B", 1000, wait, "originC2"))
        invalid("05a", ReanchorField.EXACT_MAX, validate(out = out(999)))
        invalid("05b", ReanchorField.EXACT_MAX, validate(out = out(1001)))
        val oldGuard = guardJson("g", floorJson("B", 1000, 1200, "originG"))
        val guarded = source(guardBefore = oldGuard)
        invalid("05c", ReanchorField.EXACT_MAX, validate(s = guarded, out = out(1000, "g"), oldGuard = oldGuard, destination = "g"))
        assertEquals("D2B6/T8c.05d: exactMax1200Valid", ReanchorValidation.Valid(FloorV1("B", 1000, 1200, originC2)),
            validate(s = guarded, out = out(1200, "g"), oldGuard = oldGuard, destination = "g"))
    }
    // 6 unhandedSecondSourceStillCounts
    @Test fun T8c_06_unhandedSecondSourceStillCounts() {
        val now = BootReading("B", 1100)
        val second = source(id = "h9", seconds = 2)
        bound("06", 2000, floorLowerBound(listOf(src, second), listOf(link()), now))
        assertEquals("D2B6/T8c.06: destination900Short", TypedComparison.Mismatch(FloorField.LOWER_BOUND),
            compareFloor(2000, FloorV1("B", 1000, 1000, originC2), now))
    }
    // 7 reanchorConfirmationRequired — the pure part
    @Test fun T8c_07_confirmedTupleMustMatchBeUniqueAndValid() {
        val now = BootReading("B", 1100)
        unavailable("07a", FloorBoundUnavailableReason.LINK_OUTPUT_MISMATCH,
            floorLowerBound(listOf(src), listOf(link(confirmed = guardJson("g-new", floorJson("B", 1000, 1000, "originX")))), now))
        unavailable("07b", FloorBoundUnavailableReason.DUPLICATE_LINK, floorLowerBound(listOf(src), listOf(link(), link()), now))
        unavailable("07c", FloorBoundUnavailableReason.LINK_SOURCE_MISMATCH,
            floorLowerBound(listOf(src), listOf(link(s = source(id = "h7"))), now))
        unavailable("07d", FloorBoundUnavailableReason.INVALID_REANCHOR,
            floorLowerBound(listOf(src), listOf(link(out = guardJson("g-new", floorJson("B", 1000, 999, "originC2")))), now))
    }
    // 8 currentDestinationBelowRemainder (latest re-chaining is 6-4b)
    @Test fun T8c_08_currentDestinationBelowRemainder() {
        assertEquals("D2B6/T8c.08", TypedComparison.Mismatch(FloorField.LOWER_BOUND),
            compareFloor(900, FloorV1("B", 1000, 999, originC2), BootReading("B", 1100)))
    }
    // 9 zeroUnknownAndReverse on the confirmed tuple (B,1000,1000,originC2)
    @Test fun T8c_09_zeroUnknownAndReverse() {
        fun at(now: BootReading) = floorLowerBound(listOf(src), listOf(link()), now)
        bound("09a", 1000, at(BootReading("B", 1000)))
        bound("09b", 900, at(BootReading("B", 1100)))
        bound("09c", 0, at(BootReading("B", 2000)))
        bound("09d: unknownBoot", 1000, at(BootReading(null, 5000)))
        bound("09e: otherBoot", 1000, at(BootReading("C", 5000)))
        bound("09f: backwards", 1000, at(BootReading("B", 999)))
        unavailable("09g: emptyBoot", FloorBoundUnavailableReason.INVALID_READING, at(BootReading("", 1100)))
        unavailable("09h: negativeElapsed", FloorBoundUnavailableReason.INVALID_READING, at(BootReading("B", -1)))
        assertEquals("D2B6/T8c.09i: zeroStillNeedsField", TypedComparison.Mismatch(FloorField.ABSENT), compareFloor(0, null, BootReading("B", 2000)))
    }

    // ── guard preimage / id / AUTH / reading, each alone ──
    @Test fun T8c_10_guardPreimageIdAuthAndReading() {
        val auth = ControlObligationFixtures.auth
        val oldGuard = guardJson("g", floorJson("B", 1000, 1200, "originG"), auth)
        val guarded = source(guardBefore = oldGuard)
        fun out(id: String = "g", a: String? = auth, wait: Long = 1200) = guardJson(id, floorJson("B", 1000, wait, "originC2"), a)
        assertTrue("D2B6/T8c.10a: valid", validate(s = guarded, out = out(), oldGuard = oldGuard, destination = "g") is ReanchorValidation.Valid)
        // A different submitted old guard whose own exact max (1300) is honored: only the preimage binding fails.
        val tampered = guardJson("g", floorJson("B", 1000, 1300, "originG"), auth)
        invalid("10b", ReanchorField.GUARD_PREIMAGE, validate(s = guarded, out = out(wait = 1300), oldGuard = tampered, destination = "g"))
        invalid("10c", ReanchorField.GUARD_ID, validate(s = guarded, out = out(id = "g2"), oldGuard = oldGuard, destination = "g"))
        invalid("10d", ReanchorField.AUTH, validate(s = guarded, out = out(a = null), oldGuard = oldGuard, destination = "g"))
        invalid("10e", ReanchorField.INVALID_READING, validate(s = guarded, out = out(), oldGuard = oldGuard, destination = "g", now = BootReading("", 1000)))
    }
    @Test fun T8c_11_invalidSourceFloorIsUnavailable() {
        unavailable("11", FloorBoundUnavailableReason.INVALID_SOURCE,
            floorLowerBound(listOf(src.copy(floor = FloorV1("", 5, 1000, originH))), emptyList(), BootReading("B", 1100)))
    }

    // ── r7 (6-1C measurement r1): source-binding predicates the r6 rows reached only in combination ──
    @Test fun T8c_12_sourceBindingEachRemainingPredicate() {
        // The submitted original's own id is bound too (SOURCE_ID), not only sourceId vs parsedHold.
        invalid("12a: originalIdAlone", ReanchorField.SOURCE_ID, validate(s = src.copy(originalHold = node(holdJson(id = "h2")))))
        invalid("12b: originAlone", ReanchorField.SOURCE_SUBJECT,
            validate(s = src.copy(parsedHold = src.parsedHold.copy(originLifetimeId = LifetimeId("other")))))
        invalid("12c: axesAlone", ReanchorField.SOURCE_SUBJECT,
            validate(s = src.copy(parsedHold = src.parsedHold.copy(axes = setOf(PurgeScope.USER)))))
        // Floor anchor is free in the schema, so this original differs from parsedHold only in its floor.
        val otherAnchor = holdJson().replace(floorJson("A", 5, 1000, "originH"), floorJson("A", 6, 1000, "originH"))
            .also { check(it != holdJson()); parsed(it) }
        invalid("12d: originalFloorAlone", ReanchorField.SOURCE_FLOOR, validate(s = src.copy(originalHold = node(otherAnchor))))
    }
    @Test fun T8c_13_emptyOriginDestinationAndExistingGuardIdentity() {
        invalid("13a: emptyOrigin", ReanchorField.INVALID_READING, validate(origin = LifetimeId("")))
        val oldGuard = guardJson("g", floorJson("B", 1000, 1200, "originG"))
        val guarded = source(guardBefore = oldGuard)
        fun out(id: String) = guardJson(id, floorJson("B", 1000, 1200, "originC2"))
        invalid("13b: emptyDestinationWithExistingGuard", ReanchorField.GUARD_ID,
            validate(s = guarded, out = out("g"), oldGuard = oldGuard, destination = ""))
        // An existing guard is re-anchored in place: its id stays, whatever destination is named.
        invalid("13c: existingGuardKeepsItsId", ReanchorField.GUARD_ID,
            validate(s = guarded, out = out("g-new"), oldGuard = oldGuard, destination = "g-new"))
    }
    @Test fun T8c_14_floorBoundRejectsEachBrokenLinkAndKeepsTheMaximum() {
        val now = BootReading("B", 1100)
        unavailable("14a: duplicateSourceId", FloorBoundUnavailableReason.INVALID_SOURCE, floorLowerBound(listOf(src, src), emptyList(), now))
        unavailable("14b: sourceBindingBroken", FloorBoundUnavailableReason.INVALID_SOURCE,
            floorLowerBound(listOf(src.copy(sourceId = "h2")), emptyList(), now))
        fun linked(l: ConfirmedHoldReanchor) = floorLowerBound(listOf(src), listOf(l), now)
        val mismatch = FloorBoundUnavailableReason.LINK_SOURCE_MISMATCH
        unavailable("14c: linkParsedHold", mismatch, linked(link(s = src.copy(parsedHold = src.parsedHold.copy(binding = 4)))))
        unavailable("14d: linkFloor", mismatch, linked(link(s = src.copy(floor = FloorV1("A", 5, 2000, originH)))))
        unavailable("14e: linkOriginal", mismatch, linked(link(s = src.copy(originalHold = node(holdJson(id = "h2"))))))
        unavailable("14f: linkGuardBefore", mismatch, linked(link(s = src.copy(guardBefore = node(guardJson("g", null))))))
        unavailable("14g: emptyDestination", FloorBoundUnavailableReason.INCOMPLETE_LINK, linked(link(destination = "")))
        unavailable("14h: emptyOrigin", FloorBoundUnavailableReason.INCOMPLETE_LINK, linked(link().copy(origin = LifetimeId(""))))
        val oldGuard = guardJson("g", floorJson("B", 1000, 1200, "originG"))
        val guarded = source(guardBefore = oldGuard)
        unavailable("14i: missingOldGuard", FloorBoundUnavailableReason.INCOMPLETE_LINK, floorLowerBound(listOf(guarded),
            listOf(link(s = guarded, out = guardJson("g", floorJson("B", 1000, 1200, "originC2")), destination = "g")), now))
        bound("14j: maxRegardlessOfOrder", 2000, floorLowerBound(listOf(source(id = "h9", seconds = 2), src), emptyList(), now))
    }
    @Test fun T8c_15_authRawLiteralMustBePreserved() {
        val auth0 = ControlObligationFixtures.auth.replace("\"authGeneration\":2", "\"authGeneration\":0")
            .also { check(it != ControlObligationFixtures.auth) }
        val authNeg0 = auth0.replace("\"authGeneration\":0", "\"authGeneration\":-0")
            .also { check(it != auth0) }
        val old = guardJson("g", floorJson("B", 1000, 1200, "originG"), auth0)
        val output = guardJson("g", floorJson("B", 1000, 1200, "originC2"), authNeg0)
        val oldNode = node(old)
        val outputNode = node(output)
        check(checkNotNull(guard(oldNode)).auth == checkNotNull(guard(outputNode)).auth)
        check(oldNode.toPayloadEntry().fields["auth"] != outputNode.toPayloadEntry().fields["auth"])
        invalid("15: authRawLiteral", ReanchorField.AUTH,
            validate(s = source(guardBefore = old), out = output, oldGuard = old, destination = "g"))
    }
}
