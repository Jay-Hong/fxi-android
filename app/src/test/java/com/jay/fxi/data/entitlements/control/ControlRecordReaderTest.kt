package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.EpochIdGenerator
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.emptyGuard
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.guard
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.hold
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.recovery
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.request
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.seal
import org.junit.Assert.*
import org.junit.Test

class ControlRecordReaderTest {
    private val reader = ControlRecordReader()
    private val codec = ControlPayloadCodec()
    private val keys = mapOf(
        ControlKind.SEAL to "seal_v1",
        ControlKind.DEMAND to "demand_v1",
        ControlKind.HOLD to "hold_v1",
        ControlKind.RECOVERY_INTENT to "recovery_intent_v1"
    )
    private val examples = mapOf(ControlKind.SEAL to seal, ControlKind.DEMAND to request,
        ControlKind.HOLD to hold, ControlKind.RECOVERY_INTENT to recovery)
    private val schema = intPreferencesKey("control_schema")

    private fun normal() = mutablePreferencesOf(schema to 1).apply {
        keys.values.forEach { this[stringPreferencesKey(it)] = "[]" }
    }
    private fun MutablePreferences.payload(kind: ControlKind, raw: String) { this[stringPreferencesKey(keys.getValue(kind))] = raw }
    private fun supported(prefs: Preferences): ControlRecordRead.Supported {
        val result = reader.read(prefs)
        assertTrue("${result.javaClass}", result is ControlRecordRead.Supported)
        assertEquals(prefs, result.original)
        return result as ControlRecordRead.Supported
    }
    private fun unreadable(prefs: Preferences, vararg problems: ControlRecordProblem) {
        val result = reader.read(prefs)
        assertTrue("${result.javaClass}", result is ControlRecordRead.Unreadable)
        assertEquals(problems.toList(), (result as ControlRecordRead.Unreadable).problems)
        assertEquals(prefs, result.original)
    }
    private fun originals(array: ControlArrayRead.Parsed): String = (codec.encode(array.entries.map {
        when (it) {
            is ControlEntryRead.Interpreted -> it.original.toPayloadEntry()
            is ControlEntryRead.Uninterpretable -> it.original
        }
    }) as PayloadWrite.Encoded).text

    @Test fun `schema one with all explicit empty arrays is supported`() {
        val result = supported(normal())
        assertEquals(ControlKind.entries.toSet(), result.arrays.keys)
        assertTrue(result.arrays.values.all { it.entries.isEmpty() })
        assertFalse(result.hasUninterpretable)
    }

    @Test fun `all obligation types retain their stored identities and facts`() {
        val prefs = normal().apply { examples.forEach { (kind, raw) -> payload(kind, "[$raw]") } }
        val result = supported(prefs)
        assertFalse(result.hasUninterpretable)
        val expectedIds = listOf("s", "d", "h", "r")
        assertEquals(expectedIds, result.arrays.values.map { (it.entries.single() as ControlEntryRead.Interpreted).value.id })
        result.arrays.forEach { (kind, array) ->
            val entry = array.entries.single() as ControlEntryRead.Interpreted
            val expected = ControlObligations.read(kind, ControlObligationFixtures.node(examples.getValue(kind))) as ControlEntryRead.Interpreted
            assertEquals(expected.value, entry.value)
            assertEquals("[${examples.getValue(kind)}]", originals(array))
        }
        assertTrue((result.arrays.getValue(ControlKind.HOLD).entries.single() as ControlEntryRead.Interpreted).value is RestoredHold)
    }

    @Test fun `empty and nonempty uncontrolled snapshots require migration or recovery without writing`() {
        val inputs = listOf(
            mutablePreferencesOf(),
            mutablePreferencesOf(longPreferencesKey("read_barrier") to 7L),
            mutablePreferencesOf(stringPreferencesKey("owner_uid") to "A", stringPreferencesKey("user_access_epoch") to "u"),
            mutablePreferencesOf(stringPreferencesKey("pending_purge_journal") to "|||USER,CAPABILITY"),
            mutablePreferencesOf(stringSetPreferencesKey("unrelated") to setOf("x"))
        )
        for (prefs in inputs) {
            val before = prefs.toPreferences()
            val result = reader.read(prefs)
            assertTrue(result is ControlRecordRead.MigrationOrRecoveryRequired)
            assertEquals(before, result.original)
            assertEquals(before, prefs)
            assertFalse(schema in prefs)
        }
    }

    @Test fun `actual corruption replacement with fresh epochs is not proof of legacy or clean continuity`() {
        var issued = 0
        val replacement = DataStoreAccessEpochStore.recoveryPreferences(EpochIdGenerator { "epoch-${++issued}" })
        assertEquals(2, issued)
        assertNotNull(replacement[DataStoreAccessEpochStore.USER_EPOCH])
        assertNotNull(replacement[DataStoreAccessEpochStore.KRX_EPOCH])
        assertNotNull(replacement[DataStoreAccessEpochStore.PURGE_JOURNAL])
        repeat(3) {
            val result = reader.read(replacement)
            assertTrue(result is ControlRecordRead.MigrationOrRecoveryRequired)
            assertEquals(replacement, result.original)
        }
        assertEquals(2, issued)
    }

    @Test fun `each payload without schema is unreadable even when empty or wrong typed`() {
        for (key in keys.values) {
            unreadable(mutablePreferencesOf(stringPreferencesKey(key) to "[]"), ControlRecordProblem.MissingSchema)
            unreadable(mutablePreferencesOf(booleanPreferencesKey(key) to false), ControlRecordProblem.MissingSchema)
        }
    }

    @Test fun `schema absent with all payloads present never becomes a supported empty record`() {
        unreadable(normal().apply { remove(schema) }, ControlRecordProblem.MissingSchema)
    }

    @Test fun `every required payload is checked for absence`() {
        keys.forEach { (kind, name) ->
            unreadable(normal().apply { remove(stringPreferencesKey(name)) }, ControlRecordProblem.MissingPayload(ControlPayloadKey.forKind(kind)))
        }
        unreadable(mutablePreferencesOf(schema to 1), *ControlKind.entries.map { ControlRecordProblem.MissingPayload(ControlPayloadKey.forKind(it)) }.toTypedArray())
    }

    @Test fun `future zero and negative schema are unsupported even with healthy payloads`() {
        for (version in listOf(Int.MIN_VALUE, -1, 0, 3, Int.MAX_VALUE)) {
            unreadable(normal().apply { this[schema] = version }, ControlRecordProblem.UnsupportedSchema(version))
        }
    }

    @Test fun `unsupported schema is decided before missing wrong typed or malformed payloads`() {
        unreadable(mutablePreferencesOf(schema to 3, booleanPreferencesKey("seal_v1") to true,
            stringPreferencesKey("hold_v1") to "{"), ControlRecordProblem.UnsupportedSchema(3))
    }

    private fun wrongTypes(name: String): List<Preferences.Pair<*>> = listOf(
        booleanPreferencesKey(name) to true, longPreferencesKey(name) to 1L,
        floatPreferencesKey(name) to 1f, doublePreferencesKey(name) to 1.0,
        stringSetPreferencesKey(name) to setOf("[]"), byteArrayPreferencesKey(name) to byteArrayOf(1)
    )

    @Test fun `schema checks actual Preferences type without numeric or string coercion`() {
        for (pair in wrongTypes("control_schema") + (stringPreferencesKey("control_schema") to "1")) {
            unreadable(normal().apply { putAll(pair) }, ControlRecordProblem.WrongType("control_schema"))
        }
    }

    @Test fun `each payload checks every other actual Preferences type before typed access`() {
        for (name in keys.values) {
            for (pair in wrongTypes(name) + (intPreferencesKey(name) to 1)) {
                unreadable(normal().apply { putAll(pair) }, ControlRecordProblem.WrongType(name))
            }
        }
    }

    @Test fun `all structural payload failures are collected with no partial supported result`() {
        val prefs = normal().apply {
            remove(stringPreferencesKey("seal_v1"))
            this[intPreferencesKey("demand_v1")] = 1
            payload(ControlKind.HOLD, "{}")
            payload(ControlKind.RECOVERY_INTENT, "[$recovery]")
        }
        unreadable(prefs, ControlRecordProblem.MissingPayload(ControlPayloadKey.forKind(ControlKind.SEAL)),
            ControlRecordProblem.WrongType("demand_v1"),
            ControlRecordProblem.UnreadablePayload(ControlPayloadKey.forKind(ControlKind.HOLD), PayloadRead.Unreadable(PayloadUnreadable.NOT_AN_ARRAY, "{}")))
    }

    @Test fun `each envelope refusal makes whole record unreadable retaining exact raw text`() {
        val bad = mapOf("" to PayloadUnreadable.NOT_JSON, "   " to PayloadUnreadable.NOT_JSON,
            "[" to PayloadUnreadable.NOT_JSON, "{}" to PayloadUnreadable.NOT_AN_ARRAY,
            "null" to PayloadUnreadable.NOT_AN_ARRAY, "[{\"id\":\"a\",\"id\":\"b\"}]" to PayloadUnreadable.DUPLICATE_KEY)
        for (kind in ControlKind.entries) for ((raw, reason) in bad) {
            unreadable(normal().apply { payload(kind, raw) }, ControlRecordProblem.UnreadablePayload(ControlPayloadKey.forKind(kind), PayloadRead.Unreadable(reason, raw)))
        }
    }

    @Test fun `injected envelope limits still gate all four payloads at record level`() {
        for (kind in ControlKind.entries) {
            for ((limited, raw, reason) in listOf(
                Triple(ControlPayloadCodec(maxPayloadBytes = 4), "[null]", PayloadUnreadable.TOO_LARGE),
                Triple(ControlPayloadCodec(maxDepth = 1), "[[]]", PayloadUnreadable.TOO_DEEP)
            )) {
                val result = ControlRecordReader(limited).read(normal().apply { payload(kind, raw) }) as ControlRecordRead.Unreadable
                assertEquals(listOf(ControlRecordProblem.UnreadablePayload(ControlPayloadKey.forKind(kind), PayloadRead.Unreadable(reason, raw))), result.problems)
            }
        }
    }

    @Test fun `healthy siblings coexist with opaque entries in every array preserving order and literal values`() {
        for ((kind, valid) in examples) {
            val future = valid.replace("\"id\":\"", "\"id\":\"future-").dropLast(1) + ",\"future\":{\"n\":1e-400,\"deep\":[-0,1.0000000000000000001]}}"
            val raw = "[$future,null,7,$valid]"
            val prefs = normal().apply { payload(kind, "  $raw\n") }
            val result = supported(prefs)
            assertTrue(result.hasUninterpretable)
            val array = result.arrays.getValue(kind)
            assertEquals(4, array.entries.size)
            assertTrue(array.entries.take(3).all { it is ControlEntryRead.Uninterpretable })
            assertTrue(array.entries.last() is ControlEntryRead.Interpreted)
            assertEquals(raw, originals(array))
            assertEquals("  $raw\n", result.original[stringPreferencesKey(keys.getValue(kind))])
        }
    }

    @Test fun `missing empty null and nonstring ids are preserved as uninterpretable without replacement`() {
        val variants = listOf("{}", "{\"id\":\"\"}", "{\"id\":null}", "{\"id\":1}", "{\"id\":false}")
        for (kind in ControlKind.entries) {
            val raw = variants.joinToString(",", "[", "]")
            val result = supported(normal().apply { payload(kind, raw) })
            assertTrue(result.hasUninterpretable)
            assertTrue(result.arrays.getValue(kind).entries.all { it is ControlEntryRead.Uninterpretable })
            assertEquals(raw, originals(result.arrays.getValue(kind)))
        }
    }

    @Test fun `non UUID ids are preserved exactly across repeated reads`() {
        val raw = recovery.replace("\"r\"", "\"original/lifetime:9\"")
        val prefs = normal().apply { payload(ControlKind.RECOVERY_INTENT, "[$raw]") }
        val before = prefs.toPreferences()
        repeat(5) {
            val array = supported(prefs).arrays.getValue(ControlKind.RECOVERY_INTENT)
            assertEquals("original/lifetime:9", (array.entries.single() as ControlEntryRead.Interpreted).value.id)
            assertEquals("[$raw]", originals(array))
            assertEquals(before, prefs)
        }
    }

    @Test fun `same id in any two different payload kinds implicates both without losing siblings`() {
        for (first in ControlKind.entries) for (second in ControlKind.entries.filter { it != first }) {
            val left = examples.getValue(first)
            val id = (ControlObligationFixtures.node(left).text("id") as FieldRead.Present).value
            val future = "{\"id\":\"$id\",\"future\":1e400}"
            val result = supported(normal().apply { payload(first, "[$left]"); payload(second, "[$future]") })
            assertTrue(result.hasUninterpretable)
            assertTrue(result.arrays.getValue(first).entries.single() is ControlEntryRead.Uninterpretable)
            assertTrue(result.arrays.getValue(second).entries.single() is ControlEntryRead.Uninterpretable)
            assertEquals("[$left]", originals(result.arrays.getValue(first)))
            assertEquals("[$future]", originals(result.arrays.getValue(second)))
        }
    }

    @Test fun `collision among four valid kinds blocks every participant but keeps independent sibling`() {
        val prefs = normal().apply {
            examples.forEach { (kind, raw) ->
                val id = (ControlObligationFixtures.node(raw).text("id") as FieldRead.Present).value
                val colliding = raw.replace("\"id\":\"$id\"", "\"id\":\"shared\"")
                payload(kind, if (kind == ControlKind.DEMAND) "[$colliding,$emptyGuard]" else "[$colliding]")
            }
        }
        val result = supported(prefs)
        result.arrays.forEach { (kind, array) ->
            assertTrue(array.entries.first() is ControlEntryRead.Uninterpretable)
            assertEquals(prefs[stringPreferencesKey(keys.getValue(kind))], originals(array))
        }
        assertTrue(result.arrays.getValue(ControlKind.DEMAND).entries.last() is ControlEntryRead.Interpreted)
    }

    @Test fun `identical duplicate within one array remains two uninterpretable obligations`() {
        for ((kind, raw) in examples) {
            val array = supported(normal().apply { payload(kind, "[$raw,$raw]") }).arrays.getValue(kind)
            assertEquals(2, array.entries.size)
            assertTrue(array.entries.all { it is ControlEntryRead.Uninterpretable })
            assertEquals("[$raw,$raw]", originals(array))
        }
    }

    @Test fun `numeric ids are not coerced and nested identities are not obligation collisions`() {
        val numericSeal = seal.replace("\"s\"", "\"7\"")
        val opaque = "{\"id\":7,\"future\":{\"id\":\"7\"}}"
        val nested = recovery.replace("\"session\"", "\"7\"")
        val result = supported(normal().apply {
            payload(ControlKind.SEAL, "[$numericSeal]")
            payload(ControlKind.DEMAND, "[$opaque]")
            payload(ControlKind.RECOVERY_INTENT, "[$nested]")
        })
        assertTrue(result.arrays.getValue(ControlKind.SEAL).entries.single() is ControlEntryRead.Interpreted)
        assertTrue(result.arrays.getValue(ControlKind.RECOVERY_INTENT).entries.single() is ControlEntryRead.Interpreted)
        assertTrue(result.hasUninterpretable)
    }

    @Test fun `guard id participates in global collisions and multiple guards stay uninterpretable`() {
        val other = emptyGuard.replace("\"g\"", "\"g2\"")
        val duplicate = recovery.replace("\"r\"", "\"g\"")
        val result = supported(normal().apply {
            payload(ControlKind.DEMAND, "[$guard,$other,$request]")
            payload(ControlKind.RECOVERY_INTENT, "[$duplicate]")
        })
        val demands = result.arrays.getValue(ControlKind.DEMAND).entries
        assertTrue(demands[0] is ControlEntryRead.Uninterpretable)
        assertTrue(demands[1] is ControlEntryRead.Uninterpretable)
        assertTrue(demands[2] is ControlEntryRead.Interpreted)
        assertTrue(result.arrays.getValue(ControlKind.RECOVERY_INTENT).entries.single() is ControlEntryRead.Uninterpretable)
    }

    @Test fun `same seal key and different ids coexist in original order`() {
        val second = seal.replace("\"s\"", "\"a\"")
        val result = supported(normal().apply { payload(ControlKind.SEAL, "[$seal,$second]") })
        assertFalse(result.hasUninterpretable)
        assertEquals(listOf("s", "a"), result.arrays.getValue(ControlKind.SEAL).entries.map { (it as ControlEntryRead.Interpreted).value.id })
    }

    @Test fun `unrelated Preferences keys and actual types survive all classifications`() {
        val bytesKey = byteArrayPreferencesKey("opaque")
        for (prefs in listOf(normal(), mutablePreferencesOf(), mutablePreferencesOf(schema to 3))) {
            prefs[bytesKey] = byteArrayOf(3, 4)
            prefs[stringSetPreferencesKey("future")] = setOf("a", "b")
            prefs[intPreferencesKey("owner_uid")] = 7 // Outside D1's validation scope.
            val result = reader.read(prefs)
            assertEquals(prefs, result.original)
            prefs.clear()
            assertArrayEquals(byteArrayOf(3, 4), result.original[bytesKey])
            assertEquals(setOf("a", "b"), result.original[stringSetPreferencesKey("future")])
            assertEquals(7, result.original[intPreferencesKey("owner_uid")])
            result.original[bytesKey]!![0] = 99
            assertArrayEquals(byteArrayOf(3, 4), result.original[bytesKey])
        }
    }

    @Test fun `caller mutation after reading cannot alter saved payload or interpretation`() {
        val prefs = normal().apply { payload(ControlKind.SEAL, "[$seal]") }
        val result = supported(prefs)
        prefs.payload(ControlKind.SEAL, "[]")
        assertEquals("[$seal]", result.original[stringPreferencesKey("seal_v1")])
        assertEquals("[$seal]", originals(result.arrays.getValue(ControlKind.SEAL)))
        assertThrows(IllegalStateException::class.java) { (result.original as MutablePreferences)[schema] = 2 }
        assertThrows(UnsupportedOperationException::class.java) { (result.arrays as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.arrays.getValue(ControlKind.SEAL).entries as MutableList).clear() }
    }
}
