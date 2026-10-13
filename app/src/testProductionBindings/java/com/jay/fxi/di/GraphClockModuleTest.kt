package com.jay.fxi.di

import com.jay.fxi.time.SystemAppClock
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * S5-4 slice 2: the production graph clock binding. It runs in the unit tests of the build types that take the production
 * bindings (src/testProductionBindings is wired to the same four). Whether each variant's Hilt component actually compiles
 * with it is the hiltJavaCompile task of that variant, not this test.
 */
class GraphClockModuleTest {

    @Test
    fun `S5-4 slice 2 the production graph clock is the system clock`() {
        assertSame(SystemAppClock, GraphClockModule.provideGraphWallClock())
    }

    @Test
    fun `S5-4 slice 2 the production bindings belong to the four existing build types`() {
        val gradle = File("build.gradle.kts").readText()
        val wiring = Regex("""listOf\(([^)]*)\)\.forEach \{ type ->\s*getByName\(type\)\.java\.srcDir\("src/productionBindings/java"\)""")
            .find(gradle)
        assertEquals("S5-4 slice 2 the production bindings' build types",
            listOf("debug", "release", "ciMinified", "benchmark"),
            wiring?.groupValues?.get(1)?.split(",")?.map { it.trim().removeSurrounding("\"") })
    }
}
