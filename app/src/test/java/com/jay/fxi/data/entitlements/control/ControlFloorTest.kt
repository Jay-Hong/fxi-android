package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.emptyGuard
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.guard
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.invalid
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.text
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.written
import org.junit.Assert.*
import org.junit.Test

class ControlFloorTest {
    private val floor = FloorV1("boot", 10_000, 30_000, LifetimeId("old-life"))

    @Test fun `same boot uses elapsed difference across process lifetimes`() {
        assertEquals(30_000L, floor.remainingAt(BootReading("boot", 10_000)))
        assertEquals(25_000L, floor.remainingAt(BootReading("boot", 15_000)))
        assertEquals(1L, floor.remainingAt(BootReading("boot", 39_999)))
        assertEquals(0L, floor.remainingAt(BootReading("boot", 40_000)))
        assertEquals(0L, floor.remainingAt(BootReading("boot", Long.MAX_VALUE)))
    }

    @Test fun `reboot unknown boot and clock regression repeat full wait regardless of uptime`() {
        listOf(BootReading("new-boot", 1), BootReading("new-boot", 90_000), BootReading(null, 90_000), BootReading("boot", 9_999)).forEach {
            assertEquals(30_000L, floor.remainingAt(it))
        }
        assertEquals(30_000L, floor.copy(anchorBootId = null).remainingAt(BootReading(null, 90_000)))
        assertEquals(30_000L, floor.copy(anchorBootId = null).remainingAt(BootReading("boot", 90_000)))
    }

    @Test fun `arithmetic handles zero and Long maxima without overflowing a deadline`() {
        assertEquals(0L, floor.copy(waitMillis = 0).remainingAt(BootReading("new", 0)))
        val huge = FloorV1("boot", Long.MAX_VALUE - 5, Long.MAX_VALUE, LifetimeId("life"))
        assertEquals(Long.MAX_VALUE - 5, huge.remainingAt(BootReading("boot", Long.MAX_VALUE)))
        assertEquals(Long.MAX_VALUE, huge.remainingAt(BootReading("other", Long.MAX_VALUE)))
        assertEquals(0L, FloorV1("boot", 0, Long.MAX_VALUE, LifetimeId("life")).remainingAt(BootReading("boot", Long.MAX_VALUE)))
    }

    @Test fun `invalid arithmetic inputs are not treated as expired floors`() {
        assertNull(floor.remainingAt(BootReading("", 20_000)))
        assertNull(floor.remainingAt(BootReading("boot", -1)))
        assertNull(floor.copy(waitMillis = -1).remainingAt(BootReading("boot", 20_000)))
        assertNull(floor.copy(anchorElapsedMillis = -1).remainingAt(BootReading("boot", 20_000)))
        assertNull(floor.copy(anchorBootId = "").remainingAt(BootReading("boot", 20_000)))
        assertNull(floor.copy(originLifetimeId = LifetimeId("")).remainingAt(BootReading("boot", 20_000)))
    }

    @Test fun `first capture is a positive named write with all four fields`() {
        val created = written(ControlObligations.recordFloor(node(emptyGuard), BootReading(null, 25), 90, LifetimeId("new-life")))
        assertEquals("""[{"id":"g","kind":"SCHEDULE_GUARD","floor":{"anchorBootId":null,"anchorElapsedMillis":25,"waitMillis":90,"originLifetimeId":"new-life"}}]""", text(created))
    }

    @Test fun `capture preserves remaining wait without inheriting demand or auth origin`() {
        val original = node(guard)
        val updated = written(ControlObligations.recordFloor(original, BootReading("boot", 15_000), 100, LifetimeId("new-life")))
        assertEquals("[${guard.replace("\"anchorElapsedMillis\":10000", "\"anchorElapsedMillis\":15000").replace("\"waitMillis\":30000", "\"waitMillis\":25000").replaceFirst("\"originLifetimeId\":\"life\"", "\"originLifetimeId\":\"new-life\"")}]", text(updated))
        assertEquals("[$guard]", text(original))
        val guardFact = (ControlObligations.read(ControlKind.DEMAND, updated) as ControlEntryRead.Interpreted).value as ScheduleGuardV1
        assertEquals(LifetimeId("life"), guardFact.auth!!.originLifetimeId)
        assertEquals(20_000L, guardFact.floor!!.remainingAt(BootReading("boot", 20_000)))
    }

    @Test fun `new longer floor wins and can change capture origin as one fact`() {
        val updated = written(ControlObligations.recordFloor(node(guard), BootReading("boot", 15_000), 90_000, LifetimeId("next-life")))
        val fact = (ControlObligations.read(ControlKind.DEMAND, updated) as ControlEntryRead.Interpreted).value as ScheduleGuardV1
        assertEquals(FloorV1("boot", 15_000, 90_000, LifetimeId("next-life")), fact.floor)
    }

    @Test fun `reboot recapture retains full old wait then same boot restart consumes remainder`() {
        val captured = written(ControlObligations.recordFloor(node(guard), BootReading("new-boot", 100), 1, LifetimeId("new-life")))
        val fact = (ControlObligations.read(ControlKind.DEMAND, captured) as ControlEntryRead.Interpreted).value as ScheduleGuardV1
        assertEquals(FloorV1("new-boot", 100, 30_000, LifetimeId("new-life")), fact.floor)
        assertEquals(29_000L, fact.floor!!.remainingAt(BootReading("new-boot", 1_100)))
    }

    @Test fun `generic edit cannot move any part of floor or create it`() {
        val original = node(guard)
        invalid(ControlObligations.editExisting(ControlKind.DEMAND, original) { descend("floor") { set("waitMillis", ControlScalar.Integer(40_000)) } })
        invalid(ControlObligations.editExisting(ControlKind.DEMAND, original) { descend("floor") { set("originLifetimeId", ControlScalar.Text("other")) } })
        invalid(ControlObligations.editExisting(ControlKind.DEMAND, node(emptyGuard)) { createChild("floor") {
            set("anchorBootId", ControlScalar.Null); set("anchorElapsedMillis", ControlScalar.Integer(0))
            set("waitMillis", ControlScalar.Integer(0)); set("originLifetimeId", ControlScalar.Text("life"))
        } })
    }

    @Test fun `named capture validates entire obligation and all caller values`() {
        val future = node(guard.dropLast(1) + ",\"future\":1}")
        assertEquals(ControlWriteResult.Rejected(ControlWriteFailure.UNINTERPRETABLE_OBLIGATION), ControlObligations.recordFloor(future, BootReading("boot", 20_000), 1, LifetimeId("life")))
        invalid(ControlObligations.recordFloor(node(guard), BootReading("", 0), 1, LifetimeId("life")))
        invalid(ControlObligations.recordFloor(node(guard), BootReading("boot", -1), 1, LifetimeId("life")))
        invalid(ControlObligations.recordFloor(node(guard), BootReading("boot", 0), -1, LifetimeId("life")))
        invalid(ControlObligations.recordFloor(node(guard), BootReading("boot", 0), 1, LifetimeId("")))
    }
}
