package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.auth
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.emptyGuard
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.invalid
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.request
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.text
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.written
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ControlBuilderTest {
    @Test fun `root and nested builders close after successful publication`() {
        lateinit var root: ControlBuilder
        lateinit var child: ControlBuilder
        val built = written(ControlObligations.build(ControlKind.DEMAND) {
            root = this
            literal(emptyGuard)
            objectField("auth") { child = this; literal(auth) }
        })
        val saved = text(built)
        assertThrows(IllegalStateException::class.java) { root.set("id", ControlScalar.Text("late")) }
        assertThrows(IllegalStateException::class.java) { child.set("ownerUid", ControlScalar.Text("late")) }
        assertEquals(saved, text(built))
    }

    @Test fun `caught nested exception poisons build and closes all escaped builders`() {
        lateinit var root: ControlBuilder
        lateinit var child: ControlBuilder
        invalid(ControlObligations.build(ControlKind.DEMAND) {
            root = this
            literal(emptyGuard)
            try { objectField("auth") { child = this; literal(auth); error("abort") } } catch (_: IllegalStateException) { }
        })
        assertThrows(IllegalStateException::class.java) { root.set("id", ControlScalar.Text("late")) }
        assertThrows(IllegalStateException::class.java) { child.set("ownerUid", ControlScalar.Text("late")) }
        written(ControlObligations.build(ControlKind.DEMAND) { literal(request) })
    }

    @Test fun `top level exception and cancellation close builder and release reentry guard`() {
        lateinit var escaped: ControlBuilder
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            ControlObligations.build(ControlKind.DEMAND) { escaped = this; throw java.util.concurrent.CancellationException() }
        }
        assertThrows(IllegalStateException::class.java) { escaped.set("id", ControlScalar.Text("late")) }
        written(ControlObligations.build(ControlKind.DEMAND) { literal(request) })
    }

    @Test fun `builder and editor share reentry guard in both directions`() {
        assertThrows(IllegalStateException::class.java) { ControlObligations.build(ControlKind.DEMAND) {
            ControlObligations.editExisting(ControlKind.DEMAND, node(request)) {}
        } }
        assertThrows(IllegalStateException::class.java) { ControlObligations.editExisting(ControlKind.DEMAND, node(request)) {
            ControlObligations.build(ControlKind.DEMAND) { literal(request) }
        } }
        assertThrows(IllegalStateException::class.java) { ControlObligations.build(ControlKind.DEMAND) {
            ControlObligations.build(ControlKind.DEMAND) { literal(request) }
        } }
        written(ControlObligations.build(ControlKind.DEMAND) { literal(request) })
    }

    @Test fun `child cannot modify captured parent and ignored rejection poisons build`() {
        invalid(ControlObligations.build(ControlKind.DEMAND) {
            val root = this
            literal(emptyGuard)
            objectField("auth") {
                assertThrows(IllegalStateException::class.java) { root.set("id", ControlScalar.Text("changed")) }
                literal(auth)
                objectField("ownerUid") { fail("an existing scalar is not replaced") }
            }
        })
    }

    @Test fun `object creation refuses existing objects without invoking replacement block`() {
        val original = node(emptyGuard.dropLast(1) + ",\"auth\":$auth}")
        invalid(ControlObligations.editExisting(ControlKind.DEMAND, original) {
            createChild("auth") { fail("must not replace") }
        })
        assertEquals("[${emptyGuard.dropLast(1)},\"auth\":$auth}]", text(original))
    }

    @Test fun `new child closes and parent remains usable after returning`() {
        lateinit var escaped: ControlBuilder
        val built = written(ControlObligations.build(ControlKind.DEMAND) {
            literal(emptyGuard)
            objectField("auth") { escaped = this; literal(auth) }
            set("id", ControlScalar.Text("g2"))
        })
        assertTrue(text(built).startsWith("[{\"id\":\"g2\""))
        assertThrows(IllegalStateException::class.java) { escaped.objectField("late") {} }
    }

    @Test fun `other thread cannot use root or nested builder`() {
        fun refuseElsewhere(builder: ControlBuilder) {
            val failure = AtomicReference<Throwable?>()
            val other = Thread { try { builder.set("id", ControlScalar.Text("other-thread")) } catch (t: Throwable) { failure.set(t) } }
            other.start(); other.join()
            assertTrue(failure.get() is IllegalStateException)
        }
        written(ControlObligations.build(ControlKind.DEMAND) {
            literal(emptyGuard)
            refuseElsewhere(this)
            objectField("auth") { literal(auth); refuseElsewhere(this) }
        })
    }

    @Test fun `names input is copied and later mutation cannot change built hold`() {
        val names = mutableListOf("USER", "CAPABILITY")
        val built = written(ControlObligations.build(ControlKind.HOLD) {
            literal(ControlObligationFixtures.topicHold)
            set("axes", ControlScalar.Names(names))
            names.clear()
        })
        assertEquals("[${ControlObligationFixtures.topicHold}]", text(built))
    }

    @Test fun `written obligation is not a promise that envelope size and depth will fit`() {
        val built = written(ControlObligations.build(ControlKind.DEMAND) { literal(request); set("id", ControlScalar.Text("x".repeat(1_000))) })
        assertTrue(ControlPayloadCodec(maxPayloadBytes = 100).encode(listOf(built.toPayloadEntry())) is PayloadWrite.TooLarge)
        val deep = ControlObligationFixtures.node(ControlObligationFixtures.hold)
        assertThrows(IllegalArgumentException::class.java) { ControlPayloadCodec(maxDepth = 2).encode(listOf(deep.toPayloadEntry())) }
    }

    @Test fun `callback last expression never imports another node`() {
        val other = ControlNode.of(Json.parseToJsonElement("""{"foreign":{"x":1}}""") as JsonObject)
        val built = written(ControlObligations.build(ControlKind.DEMAND) { literal(request); other })
        val edited = written(ControlObligations.editExisting(ControlKind.DEMAND, built) { other })
        assertEquals("[$request]", text(edited))
    }
}
