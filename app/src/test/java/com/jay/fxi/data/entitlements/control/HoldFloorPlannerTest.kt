package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import kotlinx.serialization.json.*
import com.jay.fxi.data.entitlements.control.FloorGuardFixtures as F
import org.junit.Assert.*
import org.junit.Test

class HoldFloorPlannerTest {
    @Test fun F07_nonGuardRace() {
        val input = F.input(old = null)
        val source = ControlSchema.read(ControlKind.HOLD, input.hold) as RestoredHold
        val request = DemandAuthFixtures.request(id = "unrelated")
        assertNull(input.guard)
        assertEquals(FloorV1("boot", 10000, 30000, LifetimeId("life")), source.floor)
        assertEquals(BootReading("boot", 11000), input.mergeNow)
        assertEquals(LifetimeId("new-life"), input.origin)
        assertEquals("new-guard", input.newGuardId)
        assertNotNull(demand(request))
        assertNull(guard(request))
        val read = F.read(F.raw(request, hold = input.hold))
        assertEquals(2, read.schemaVersion)
        assertFalse(read.hasUninterpretable)
        assertFalse(read.hasUninterpretableMetadata)
        assertTrue(read.locations("new-guard").isEmpty())
        assertEquals(listOf(request.toPayloadEntry()), read.arrays.getValue(ControlKind.DEMAND).entries.map {
            (it as ControlEntryRead.Interpreted).original.toPayloadEntry()
        })
        assertEquals(input.hold.toPayloadEntry(), (read.locations(source.id).single().second as ControlEntryRead.Interpreted).original.toPayloadEntry())
        val plan = prepared(input)
        assertEquals(FloorV1("boot", 11000, 29000, LifetimeId("new-life")), guard(plan.guardAfter)?.floor)
        // A different REQUEST ID cannot race the creation of a schedule guard.
        assertNull(F.atomic("F07_nonGuardRace"), plan.preimageProblem(read))
    }

    private fun prepared(input: HoldFloorInput) = checkNotNull(HoldFloorPlan.prepare(input))
    private fun merge(id: String, input: HoldFloorInput, expected: Long) {
        assertNotNull(ControlSchema.read(ControlKind.HOLD, input.hold))
        if (input.guard != null) assertNotNull(ControlSchema.read(ControlKind.DEMAND, input.guard))
        val saved = input.hold.toPayloadEntry().fields.toString()
        val plan = HoldFloorPlan.prepare(input)
        assertNotNull(F.atomic(id), plan)
        val after = guard(plan!!.guardAfter)!!
        assertEquals(F.atomic(id), FloorV1(input.mergeNow.bootId, input.mergeNow.elapsedMillis, expected, input.origin), after.floor)
        assertEquals(F.atomic(id), saved, input.hold.toPayloadEntry().fields.toString())
        assertEquals(F.atomic(id), input.guard?.toPayloadEntry()?.fields?.get("auth")?.toString(), plan.guardAfter!!.toPayloadEntry().fields["auth"]?.toString())
    }
    @Test fun F07a() = merge("F07a", F.input(old = F.guard(F.floor(90000))), 89000L)
    @Test fun F07b() = merge("F07b", F.input(old = F.guard(F.floor(1000))), 29000L)
    @Test fun F07c() = merge("F07c", F.input(now = BootReading(null, 99999)), 30000L)
    @Test fun newFloorOnlyGuard() {
        val input = F.input(old = null); val p = prepared(input)
        assertEquals("new-guard", guard(p.guardAfter)!!.id)
        assertFalse(p.guardAfter!!.toPayloadEntry().fields.containsKey("auth"))
        assertEquals(FloorV1("boot", 11000, 29000, F.origin), guard(p.guardAfter)!!.floor)
    }
    @Test fun sourceWithoutFloorLeavesGuardVerbatim() {
        val input = F.input(old = F.guard(auth = true), hold = F.hold(null))
        assertEquals(input.guard!!.toPayloadEntry(), prepared(input).guardAfter!!.toPayloadEntry())
    }
    @Test fun sourceWithoutFloorDoesNotCreateGuard() {
        assertNull(prepared(F.input(old = null, hold = F.hold(null))).guardAfter)
    }
    @Test fun differentBootRestoresFullWait() = merge("F07.different", F.input(now = BootReading("new-boot", 99999)), 30000)
    @Test fun backwardsRestoresFullWait() = merge("F07.backwards", F.input(now = BootReading("boot", 9999)), 30000)
    @Test fun maxGuardWaitDoesNotOverflow() = merge("F07.max", F.input(old = F.guard(F.floor(Long.MAX_VALUE)), now = BootReading(null, Long.MAX_VALUE)), Long.MAX_VALUE)
    @Test fun sameBootZeroFloorRemainsStored() = merge("F08a.stored", F.input(now = BootReading("boot", 99999)), 0)
    @Test fun absentOldFloorIsZero() = merge("F07.absent", F.input(old = F.empty), 29000)
    @Test fun HF_boot() {
        val input = F.input(hold = F.hold(null), now = BootReading("", 0))
        assertNotNull(ControlSchema.read(ControlKind.HOLD, input.hold))
        val invalid = listOf(input.mergeNow.bootId == "", input.mergeNow.elapsedMillis < 0, input.origin.value.isEmpty(), input.newGuardId.isEmpty())
        assertEquals(1, invalid.count { it })
        assertNull(F.eligible("HF_boot"), HoldFloorPlan.prepare(input))
    }
    @Test fun HF_elapsed() {
        val input = F.input(hold = F.hold(null), now = BootReading("boot", -1))
        assertNotNull(ControlSchema.read(ControlKind.HOLD, input.hold))
        val invalid = listOf(input.mergeNow.bootId == "", input.mergeNow.elapsedMillis < 0, input.origin.value.isEmpty(), input.newGuardId.isEmpty())
        assertEquals(1, invalid.count { it })
        assertNull(F.eligible("HF_elapsed"), HoldFloorPlan.prepare(input))
    }
    @Test fun HF_origin() {
        val input = F.input(hold = F.hold(null), origin = LifetimeId(""))
        assertNotNull(ControlSchema.read(ControlKind.HOLD, input.hold))
        val invalid = listOf(input.mergeNow.bootId == "", input.mergeNow.elapsedMillis < 0, input.origin.value.isEmpty(), input.newGuardId.isEmpty())
        assertEquals(1, invalid.count { it })
        assertNull(F.eligible("HF_origin"), HoldFloorPlan.prepare(input))
    }
    @Test fun HF_id() {
        val input = F.input(old = null, id = "")
        assertNotNull(ControlSchema.read(ControlKind.HOLD, input.hold))
        val invalid = listOf(input.mergeNow.bootId == "", input.mergeNow.elapsedMillis < 0, input.origin.value.isEmpty(), input.newGuardId.isEmpty())
        assertEquals(1, invalid.count { it })
        assertFalse(F.eligible("HF_id"), HoldFloorPlan.validNewGuardId(input.newGuardId))
    }
    private fun bad(id: String, input: HoldFloorInput, candidate: ControlNode?) {
        assertNotNull(ControlSchema.read(ControlKind.HOLD, input.hold))
        if (candidate != null) assertNotNull(ControlSchema.read(ControlKind.DEMAND, candidate))
        val source = ControlSchema.read(ControlKind.HOLD, input.hold) as RestoredHold
        val actual = candidate?.let(::guard)
        fun remaining(f: FloorV1?): Long {
            if (f == null) return 0
            return if (f.anchorBootId != null && f.anchorBootId == input.mergeNow.bootId && input.mergeNow.elapsedMillis >= f.anchorElapsedMillis)
                maxOf(0, f.waitMillis - (input.mergeNow.elapsedMillis - f.anchorElapsedMillis)) else f.waitMillis
        }
        val left = remaining(guard(input.guard)?.floor); val right = remaining(source.floor)
        val floor = actual?.floor
        val facts = if (source.floor == null) listOf(candidate?.toPayloadEntry()?.fields?.toString() == input.guard?.toPayloadEntry()?.fields?.toString())
            else if (actual == null) listOf(false) else listOf(
                actual.id == (guard(input.guard)?.id ?: input.newGuardId),
                candidate.toPayloadEntry().fields["auth"]?.toString() == input.guard?.toPayloadEntry()?.fields?.get("auth")?.toString(),
                floor != null) + if (floor == null) emptyList() else listOf(floor.waitMillis >= left, floor.waitMillis >= right,
                floor.waitMillis <= maxOf(left, right), floor.anchorBootId == input.mergeNow.bootId,
                floor.anchorElapsedMillis == input.mergeNow.elapsedMillis, floor.originLifetimeId == input.origin)
        assertEquals("only the named candidate fact differs", 1, facts.count { !it })
        assertFalse(F.atomic(id), HoldFloorPlan.validCandidate(input, candidate))
    }
    private fun expected(input: HoldFloorInput, wait: Long = 29000, boot: String? = "boot", elapsed: Long = 11000,
        origin: String = "new-life") = F.field(input.guard ?: F.field(F.empty, "id", JsonPrimitive(input.newGuardId)), "floor", F.floor(wait, boot, elapsed, origin))
    @Test fun C08_auth() { val i = F.input(old = F.guard(auth = true)); bad("C08_auth", i, F.field(expected(i), "auth", null)) }
    @Test fun C09_floor() { val i = F.input(); bad("C09_floor", i, F.field(expected(i), "floor", null)) }
    @Test fun C09_oldWait() { val i = F.input(old = F.guard(F.floor(90000))); bad("C09_oldWait", i, expected(i, 29000)) }
    @Test fun C09_sourceWait() { val i = F.input(old = F.guard(F.floor(1000))); bad("C09_sourceWait", i, expected(i, 0)) }
    @Test fun C09_exactMax() { val i = F.input(); bad("C09_exactMax", i, expected(i, 30000)) }
    @Test fun C09_anchorBoot() { val i = F.input(); bad("C09_anchorBoot", i, expected(i, boot = "other")) }
    @Test fun C09_anchorElapsed() { val i = F.input(); bad("C09_anchorElapsed", i, expected(i, elapsed = 11001)) }
    @Test fun C09_origin() { val i = F.input(); bad("C09_origin", i, expected(i, origin = "old")) }
    @Test fun C09_guardId() { val i = F.input(); bad("C09_guardId", i, F.field(expected(i), "id", JsonPrimitive("wrong"))) }
    @Test fun C09_guard() { val i = F.input(old = null); bad("C09_guard", i, null) }
    @Test fun C09_noFloor() { val i = F.input(hold = F.hold(null)); bad("C09_noFloor", i, F.empty) }
    private fun race(id: String, input: HoldFloorInput, raw: Preferences, expected: ConflictReason?) {
        assertFalse(F.read(raw).hasUninterpretable)
        val p = prepared(input)
        assertEquals(F.eligible(id), expected, p.preimageProblem(F.read(raw)))
    }
    @Test fun F07_guardRace() = race("F07_guardRace", F.input(old = null), F.raw(F.empty, hold = F.hold()), ConflictReason.TargetChanged)
    @Test fun F07_idRace() = race("F07_idRace", F.input(old = null), F.raw(DemandAuthFixtures.request(id = "new-guard"), hold = F.hold()), ConflictReason.IdCollision)
    @Test fun F07_changedGuard() = race("F07_changedGuard", F.input(), F.raw(F.guard(F.floor(30001)), hold = F.hold()), ConflictReason.TargetChanged)
    @Test fun F07_changedHold() = race("F07_changedHold", F.input(), F.raw(F.guard(), hold = F.hold(F.floor(elapsed = 10001))), ConflictReason.TargetChanged)
    @Test fun F07_missingGuard() = race("F07_missingGuard", F.input(), F.raw(hold = F.hold()), ConflictReason.TargetMissing)
    @Test fun F07_missingHold() = race("F07_missingHold", F.input(), F.raw(F.guard()), ConflictReason.TargetMissing)
    @Test fun repeatedPreimageCheckDoesNotRecapture() {
        val i = F.input(); val p = prepared(i); val raw = F.raw(i.guard!!, hold = i.hold)
        assertNull(p.preimageProblem(F.read(raw)))
        assertNull(p.preimageProblem(F.read(raw)))
        assertEquals(FloorV1("boot", 11000, 29000, F.origin), guard(p.guardAfter)!!.floor)
        assertEquals(i.hold.toPayloadEntry(), F.read(raw).arrays.getValue(ControlKind.HOLD).entries.filterIsInstance<ControlEntryRead.Interpreted>().single().original.toPayloadEntry())
    }
    @Test fun invalidSourceIsNotEmptyFloor() { assertNull(HoldFloorPlan.prepare(F.input(hold = F.empty))) }
    @Test fun invalidGuardIsNotAbsent() { assertNull(HoldFloorPlan.prepare(F.input(old = DemandAuthFixtures.request()))) }

    @Test fun C08_ownerUid() {
        val i = F.input(old = F.guard(auth = true)); val good = expected(i)
        val auth = good.toPayloadEntry().fields["auth"]!!.jsonObject
        val altered = F.field(good, "auth", JsonObject(auth + ("ownerUid" to JsonPrimitive("other"))))
        assertEquals(JsonObject(auth - "ownerUid"), JsonObject(altered.toPayloadEntry().fields["auth"]!!.jsonObject - "ownerUid"))
        bad("C08_ownerUid", i, altered)
    }
    @Test fun C08_authGeneration() {
        val i = F.input(old = F.guard(auth = true)); val good = expected(i)
        val auth = good.toPayloadEntry().fields["auth"]!!.jsonObject
        val altered = F.field(good, "auth", JsonObject(auth + ("authGeneration" to JsonPrimitive(3))))
        assertEquals(JsonObject(auth - "authGeneration"), JsonObject(altered.toPayloadEntry().fields["auth"]!!.jsonObject - "authGeneration"))
        bad("C08_authGeneration", i, altered)
    }
    @Test fun C08_binding() {
        val i = F.input(old = F.guard(auth = true)); val good = expected(i)
        val auth = good.toPayloadEntry().fields["auth"]!!.jsonObject
        val altered = F.field(good, "auth", JsonObject(auth + ("binding" to JsonPrimitive(4))))
        assertEquals(JsonObject(auth - "binding"), JsonObject(altered.toPayloadEntry().fields["auth"]!!.jsonObject - "binding"))
        bad("C08_binding", i, altered)
    }
    @Test fun C08_originLifetimeId() {
        val i = F.input(old = F.guard(auth = true)); val good = expected(i)
        val auth = good.toPayloadEntry().fields["auth"]!!.jsonObject
        val altered = F.field(good, "auth", JsonObject(auth + ("originLifetimeId" to JsonPrimitive("other"))))
        assertEquals(JsonObject(auth - "originLifetimeId"), JsonObject(altered.toPayloadEntry().fields["auth"]!!.jsonObject - "originLifetimeId"))
        bad("C08_originLifetimeId", i, altered)
    }
    @Test fun C08_authStopped() {
        val i = F.input(old = F.guard(auth = true)); val good = expected(i)
        val auth = good.toPayloadEntry().fields["auth"]!!.jsonObject
        val altered = F.field(good, "auth", JsonObject(auth + ("authStopped" to JsonPrimitive(true))))
        assertEquals(JsonObject(auth - "authStopped"), JsonObject(altered.toPayloadEntry().fields["auth"]!!.jsonObject - "authStopped"))
        bad("C08_authStopped", i, altered)
    }
    @Test fun C08_authStateOrder() {
        val i = F.input(old = F.guard(auth = true)); val good = expected(i)
        val auth = good.toPayloadEntry().fields["auth"]!!.jsonObject
        val altered = F.field(good, "auth", JsonObject(auth + ("authStateOrder" to JsonPrimitive(11))))
        assertEquals(JsonObject(auth - "authStateOrder"), JsonObject(altered.toPayloadEntry().fields["auth"]!!.jsonObject - "authStateOrder"))
        bad("C08_authStateOrder", i, altered)
    }
    @Test fun C08_authStopAppliedOrder() {
        val i = F.input(old = F.guard(auth = true)); val good = expected(i)
        val auth = good.toPayloadEntry().fields["auth"]!!.jsonObject
        val altered = F.field(good, "auth", JsonObject(auth + ("authStopAppliedOrder" to JsonPrimitive(21))))
        assertEquals(JsonObject(auth - "authStopAppliedOrder"), JsonObject(altered.toPayloadEntry().fields["auth"]!!.jsonObject - "authStopAppliedOrder"))
        bad("C08_authStopAppliedOrder", i, altered)
    }
    @Test fun F07_authBuilder() = merge("F07_authBuilder", F.input(old = F.guard(auth = true)), 29000)
    @Test fun C08_keyOrder() {
        val i = F.input(old = F.guard(auth = true)); val good = expected(i)
        val auth = good.toPayloadEntry().fields["auth"]!!.jsonObject
        val reordered = JsonObject(auth.entries.reversed().associate { it.key to it.value })
        assertEquals(auth, reordered)
        bad("C08_keyOrder", i, F.field(good, "auth", reordered))
    }
    @Test fun C09_noFloorAuthOnly() {
        val i = F.input(old = F.guard(null, true), hold = F.hold(null))
        bad("C09_noFloorAuthOnly", i, F.empty)
    }
    @Test fun C09_originBuilder() = merge("C09_originBuilder", F.input(), 29000)
    @Test fun C09_anchorBootBuilder() = merge("C09_anchorBootBuilder", F.input(), 29000)
    @Test fun C09_anchorElapsedBuilder() = merge("C09_anchorElapsedBuilder", F.input(), 29000)
    @Test fun C09_floorBuilder() = merge("C09_floorBuilder", F.input(), 29000)
    @Test fun C09_newGuardId() {
        val i = F.input(old = null); bad("C09_newGuardId", i, F.field(expected(i), "id", JsonPrimitive("wrong")))
    }

    @Test fun HF_sourceKind() {
        val input = F.input(hold = F.empty)
        assertNotNull(guard(input.hold))
        assertFalse(input.hold.toPayloadEntry().fields.containsKey("floor"))
        assertNotNull(guard(input.guard))
        assertEquals(BootReading("boot", 11000), input.mergeNow)
        assertEquals(LifetimeId("new-life"), input.origin)
        assertEquals("new-guard", input.newGuardId)
        assertNull(F.eligible("HF_sourceKind"), HoldFloorPlan.prepare(input))
    }

    @Test fun HF_guardKind() {
        val input = F.input(
            old = DemandAuthFixtures.request(),
            hold = F.hold(null)
        )
        assertNotNull(demand(input.guard))
        val source = ControlSchema.read(ControlKind.HOLD, input.hold) as RestoredHold
        assertNull(source.floor)
        assertEquals(BootReading("boot", 11000), input.mergeNow)
        assertEquals(LifetimeId("new-life"), input.origin)
        assertEquals("new-guard", input.newGuardId)
        assertNull(F.eligible("HF_guardKind"), HoldFloorPlan.prepare(input))
    }

    @Test fun HF_candidateSourceKind() {
        val input = F.input(hold = F.empty)
        val candidate = checkNotNull(input.guard)
        assertNotNull(guard(input.hold))
        assertFalse(input.hold.toPayloadEntry().fields.containsKey("floor"))
        assertNotNull(guard(candidate))
        // A floorless source requires this exact unchanged guard; only the source's kind is wrong.
        assertEquals(input.guard!!.toPayloadEntry().fields.toString(), candidate.toPayloadEntry().fields.toString())
        assertEquals(BootReading("boot", 11000), input.mergeNow)
        assertEquals(LifetimeId("new-life"), input.origin)
        assertEquals("new-guard", input.newGuardId)
        assertFalse(F.eligible("HF_candidateSourceKind"), HoldFloorPlan.validCandidate(input, candidate))
    }

    @Test fun HF_candidateGuardKind() {
        val input = F.input(old = DemandAuthFixtures.request())
        val source = ControlSchema.read(ControlKind.HOLD, input.hold) as RestoredHold
        val request = checkNotNull(input.guard)
        assertNotNull(source.floor)
        assertNotNull(demand(request))
        assertFalse(request.toPayloadEntry().fields.containsKey("floor"))
        assertFalse(request.toPayloadEntry().fields.containsKey("auth"))
        assertEquals(BootReading("boot", 11000), input.mergeNow)
        assertEquals(LifetimeId("new-life"), input.origin)
        assertEquals("new-guard", input.newGuardId)
        assertEquals(FloorV1("boot", 10000, 30000, LifetimeId("life")), source.floor)
        // Literal postcondition: max(0, 30000 - (11000 - 10000)) = 29000; no plan output is used.
        val candidate = F.node("""{"id":"new-guard","kind":"SCHEDULE_GUARD","floor":{"anchorBootId":"boot","anchorElapsedMillis":11000,"waitMillis":29000,"originLifetimeId":"new-life"}}""")
        val after = guard(candidate)!!
        assertEquals(input.newGuardId, after.id)
        assertNull(after.auth)
        assertEquals(FloorV1("boot", 11000, 29000, LifetimeId("new-life")), after.floor)
        assertFalse(F.eligible("HF_candidateGuardKind"), HoldFloorPlan.validCandidate(input, candidate))
    }

    @Test fun HF_idScope() {
        val input = F.input(id = "")
        val source = ControlSchema.read(ControlKind.HOLD, input.hold) as RestoredHold
        assertNotNull(source.floor)
        assertEquals(FloorV1("boot", 10000, 30000, LifetimeId("life")), source.floor)
        val before = guard(input.guard)
        assertNotNull(before)
        assertTrue(before!!.id.isNotEmpty())
        assertEquals(FloorV1("boot", 10000, 30000, LifetimeId("life")), before.floor)
        assertEquals(BootReading("boot", 11000), input.mergeNow)
        assertEquals(LifetimeId("new-life"), input.origin)
        assertEquals("", input.newGuardId)
        // Both fixed input floors have 30000 - (11000 - 10000) = 29000 remaining.
        val expectedFloor = FloorV1("boot", 11000, 29000, input.origin)
        val plan = HoldFloorPlan.prepare(input)
        assertNotNull(F.atomic("HF_idScope"), plan)
        val after = guard(plan!!.guardAfter)
        assertNotNull(after)
        assertEquals(before.id, after!!.id)
        assertEquals(expectedFloor, after.floor)
    }

    @Test fun F07_holdPreimageCall() {
        val input = F.input()
        val plan = prepared(input)
        val source = ControlSchema.read(ControlKind.HOLD, input.hold) as RestoredHold
        val oldGuard = checkNotNull(input.guard)
        val before = guard(oldGuard)!!
        val changed = F.hold(F.floor(elapsed = 10001))
        val changedSource = ControlSchema.read(ControlKind.HOLD, changed) as RestoredHold
        assertEquals(FloorV1("boot", 10000, 30000, LifetimeId("life")), source.floor)
        assertEquals(FloorV1("boot", 10001, 30000, LifetimeId("life")), changedSource.floor)
        assertEquals(input.hold.toPayloadEntry(), F.field(changed, "floor", input.hold.toPayloadEntry().fields["floor"]).toPayloadEntry())
        val read = F.read(F.raw(oldGuard, hold = changed))
        assertEquals(2, read.schemaVersion)
        assertFalse(read.hasUninterpretable)
        assertFalse(read.hasUninterpretableMetadata)
        val sourceTarget = LifecycleFixedTarget(LifecycleTarget(ControlKind.HOLD, source.id, LifecycleEffect.REMOVE),
            LifecycleRole.HOLD, input.hold, null)
        val guardTarget = LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, before.id, LifecycleEffect.REPLACE),
            LifecycleRole.GUARD, oldGuard, plan.guardAfter)
        // The helper still rejects the changed HOLD; the unchanged guard passes independently.
        assertEquals(ConflictReason.TargetChanged, ControlLifecycleBoundary.preimage(read, sourceTarget))
        assertNull(ControlLifecycleBoundary.preimage(read, guardTarget))
        assertEquals(oldGuard.toPayloadEntry(), (read.locations(before.id).single().second as ControlEntryRead.Interpreted).original.toPayloadEntry())
        val problem = plan.preimageProblem(read)
        assertNotNull(F.eligible("F07_holdPreimageCall"), problem)
        assertEquals(ConflictReason.TargetChanged, problem)
    }

    @Test fun F07_guardPreimageCall() {
        val input = F.input()
        val plan = prepared(input)
        val source = ControlSchema.read(ControlKind.HOLD, input.hold) as RestoredHold
        val oldGuard = checkNotNull(input.guard)
        val before = guard(oldGuard)!!
        val changed = F.guard(F.floor(30001))
        val changedGuard = guard(changed)!!
        assertEquals(FloorV1("boot", 10000, 30000, LifetimeId("life")), before.floor)
        assertEquals(FloorV1("boot", 10000, 30001, LifetimeId("life")), changedGuard.floor)
        assertEquals(oldGuard.toPayloadEntry(), F.field(changed, "floor", oldGuard.toPayloadEntry().fields["floor"]).toPayloadEntry())
        val read = F.read(F.raw(changed, hold = input.hold))
        assertEquals(2, read.schemaVersion)
        assertFalse(read.hasUninterpretable)
        assertFalse(read.hasUninterpretableMetadata)
        val sourceTarget = LifecycleFixedTarget(LifecycleTarget(ControlKind.HOLD, source.id, LifecycleEffect.REMOVE),
            LifecycleRole.HOLD, input.hold, null)
        val guardTarget = LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, before.id, LifecycleEffect.REPLACE),
            LifecycleRole.GUARD, oldGuard, plan.guardAfter)
        assertNull(ControlLifecycleBoundary.preimage(read, sourceTarget))
        assertEquals(ConflictReason.TargetChanged, ControlLifecycleBoundary.preimage(read, guardTarget))
        assertEquals(input.hold.toPayloadEntry(), (read.locations(source.id).single().second as ControlEntryRead.Interpreted).original.toPayloadEntry())
        val problem = plan.preimageProblem(read)
        assertNotNull(F.eligible("F07_guardPreimageCall"), problem)
        assertEquals(ConflictReason.TargetChanged, problem)
    }

    @Test fun HF_noFloorPreserve() {
        val input = F.input(old = F.guard(auth = true), hold = F.hold(null))
        val source = ControlSchema.read(ControlKind.HOLD, input.hold) as RestoredHold
        val before = guard(input.guard)!!
        assertNull(source.floor)
        assertNotNull(before.floor)
        assertNotNull(before.auth)
        assertEquals(BootReading("boot", 11000), input.mergeNow)
        assertEquals(LifetimeId("new-life"), input.origin)
        assertEquals("new-guard", input.newGuardId)
        val expected = input.guard!!.toPayloadEntry().fields.toString()
        val plan = HoldFloorPlan.prepare(input)
        assertNotNull(F.atomic("HF_noFloorPreserve"), plan)
        assertEquals(F.atomic("HF_noFloorPreserve"), expected, plan!!.guardAfter?.toPayloadEntry()?.fields?.toString())
    }

    @Test fun F07_guardBranch() {
        val input = F.input(hold = F.hold(null))
        val source = ControlSchema.read(ControlKind.HOLD, input.hold) as RestoredHold
        // A floor-bearing HOLD would enter the fallback guard-race check and mask this bypass.
        assertNull(source.floor)
        val oldGuard = checkNotNull(input.guard)
        val before = guard(oldGuard)!!
        val changed = F.guard(F.floor(30001))
        val changedGuard = guard(changed)!!
        assertEquals(FloorV1("boot", 10000, 30000, LifetimeId("life")), before.floor)
        assertEquals(FloorV1("boot", 10000, 30001, LifetimeId("life")), changedGuard.floor)
        val oldFloor = oldGuard.toPayloadEntry().fields["floor"]!!.jsonObject
        assertEquals(JsonObject(oldFloor + ("waitMillis" to JsonPrimitive(30001))), changed.toPayloadEntry().fields["floor"])
        assertEquals(oldGuard.toPayloadEntry(), F.field(changed, "floor", oldFloor).toPayloadEntry())
        assertEquals(BootReading("boot", 11000), input.mergeNow)
        assertEquals(LifetimeId("new-life"), input.origin)
        assertEquals("new-guard", input.newGuardId)
        val plan = prepared(input)
        val read = F.read(F.raw(changed, hold = input.hold))
        assertEquals(2, read.schemaVersion)
        assertFalse(read.hasUninterpretable)
        assertFalse(read.hasUninterpretableMetadata)
        val savedHold = read.locations(source.id).single()
        assertEquals(ControlKind.HOLD, savedHold.first)
        assertEquals(input.hold.toPayloadEntry(), (savedHold.second as ControlEntryRead.Interpreted).original.toPayloadEntry())
        val savedGuard = read.locations(before.id).single()
        assertEquals(ControlKind.DEMAND, savedGuard.first)
        assertEquals(changed.toPayloadEntry(), (savedGuard.second as ControlEntryRead.Interpreted).original.toPayloadEntry())
        assertTrue(read.locations(input.newGuardId).isEmpty())
        val problem = plan.preimageProblem(read)
        assertNotNull(F.eligible("F07_guardBranch"), problem)
        assertEquals(ConflictReason.TargetChanged, problem)
    }

    @Test fun F07_floorBranch() {
        val input = F.input(old = null)
        val source = ControlSchema.read(ControlKind.HOLD, input.hold) as RestoredHold
        assertNull(input.guard)
        assertNotNull(source.floor)
        assertEquals(FloorV1("boot", 10000, 30000, LifetimeId("life")), source.floor)
        assertEquals(BootReading("boot", 11000), input.mergeNow)
        assertEquals(LifetimeId("new-life"), input.origin)
        assertEquals("new-guard", input.newGuardId)
        val plan = prepared(input)
        assertEquals("new-guard", guard(plan.guardAfter)?.id)
        val appeared = guard(F.empty)!!
        assertEquals("g", appeared.id)
        assertNull(appeared.floor)
        assertNull(appeared.auth)
        assertEquals(setOf("id", "kind"), F.empty.toPayloadEntry().fields.keys)
        val read = F.read(F.raw(F.empty, hold = input.hold))
        assertEquals(2, read.schemaVersion)
        assertFalse(read.hasUninterpretable)
        assertFalse(read.hasUninterpretableMetadata)
        val savedHold = read.locations(source.id).single()
        assertEquals(ControlKind.HOLD, savedHold.first)
        assertEquals(input.hold.toPayloadEntry(), (savedHold.second as ControlEntryRead.Interpreted).original.toPayloadEntry())
        val savedGuard = read.arrays.getValue(ControlKind.DEMAND).entries.single() as ControlEntryRead.Interpreted
        assertTrue(savedGuard.value is ScheduleGuardV1)
        assertEquals(F.empty.toPayloadEntry(), savedGuard.original.toPayloadEntry())
        assertTrue(read.locations(input.newGuardId).isEmpty())
        val problem = plan.preimageProblem(read)
        assertNotNull(F.eligible("F07_floorBranch"), problem)
        assertEquals(ConflictReason.TargetChanged, problem)
    }

    @Test fun F07_noFloorBranch() {
        val input = F.input(old = null, hold = F.hold(null))
        val source = ControlSchema.read(ControlKind.HOLD, input.hold) as RestoredHold
        assertNull(input.guard)
        assertNull(source.floor)
        assertEquals(BootReading("boot", 11000), input.mergeNow)
        assertEquals(LifetimeId("new-life"), input.origin)
        assertEquals("new-guard", input.newGuardId)
        val plan = prepared(input)
        assertNull(plan.guardAfter)
        val appeared = guard(F.empty)!!
        assertEquals("g", appeared.id)
        assertNull(appeared.floor)
        assertNull(appeared.auth)
        assertEquals(setOf("id", "kind"), F.empty.toPayloadEntry().fields.keys)
        val read = F.read(F.raw(F.empty, hold = input.hold))
        assertEquals(2, read.schemaVersion)
        assertFalse(read.hasUninterpretable)
        assertFalse(read.hasUninterpretableMetadata)
        val savedHold = read.locations(source.id).single()
        assertEquals(ControlKind.HOLD, savedHold.first)
        assertEquals(input.hold.toPayloadEntry(), (savedHold.second as ControlEntryRead.Interpreted).original.toPayloadEntry())
        val savedGuard = read.arrays.getValue(ControlKind.DEMAND).entries.single() as ControlEntryRead.Interpreted
        assertTrue(savedGuard.value is ScheduleGuardV1)
        assertEquals(F.empty.toPayloadEntry(), savedGuard.original.toPayloadEntry())
        assertTrue(read.locations(input.newGuardId).isEmpty())
        assertNull(F.atomic("F07_noFloorBranch"), plan.preimageProblem(read))
    }
}
