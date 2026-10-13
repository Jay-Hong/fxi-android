package com.jay.fxi.s5fixture

import com.jay.fxi.BuildConfig
import java.io.File
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S5-4 slice 4b: the s5Benchmark build type's own constants, and the fixture clock at the frozen bundle's now. */
class S5BuildTypeConstantsTest {

    @Test
    fun `S5-4 4b the s5Benchmark constants`() {
        assertTrue("compiled ON", BuildConfig.TOPIC_V2_RELEASE_ON)
        assertFalse("not the no-data benchmark", BuildConfig.BENCHMARK_NO_DATA_MODE)
        assertEquals("no RevenueCat key", "", BuildConfig.REVENUECAT_API_KEY)
        assertFalse("crash collection closed", BuildConfig.CRASH_COLLECTION_ALLOWED)
        assertTrue("test tags as resource ids", BuildConfig.SEMANTICS_RESOURCE_IDS)
        assertFalse("not debuggable", BuildConfig.DEBUG)
    }

    @Test
    fun `S5-4 4b the fixture graph clock is the bundle's now`() {
        val bundle = Json.parseToJsonElement(File("src/benchmark/assets/s5_4/bundle.json").readText()).jsonObject
        val now = OffsetDateTime.parse(bundle.getValue("now").jsonPrimitive.content).toInstant().toEpochMilli()
        assertEquals(now, S5FixtureBindingsModule.provideGraphWallClock().now().toEpochMilliseconds())
        assertEquals(now, S5FixtureBindingsModule.FIXTURE_NOW.toEpochMilliseconds())
    }
}
