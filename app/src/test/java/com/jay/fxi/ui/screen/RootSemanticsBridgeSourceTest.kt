package com.jay.fxi.ui.screen

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S5-4 slice 4b: the root exposes test tags as resource ids only behind the build-type constant, so other build types get no
 * extra node. A source check: it pins the guard and the one place, not the composition at run time.
 */
class RootSemanticsBridgeSourceTest {

    @Test
    fun `S5-4 4b test tags become resource ids only behind SEMANTICS_RESOURCE_IDS`() {
        val file = File("src/main/java/com/jay/fxi/ui/screen/RootScreen.kt")
        assertTrue(file.isFile)
        val source = file.readText()
        assertEquals("one place", 1, Regex("""testTagsAsResourceId\s*=""").findAll(source).count())
        assertTrue("behind the constant", Regex(
            """if \(BuildConfig\.SEMANTICS_RESOURCE_IDS\) \{[^}]*Box\(Modifier\.semantics \{ testTagsAsResourceId = true \}\) \{ armed\(\) \}"""
        ).containsMatchIn(source))
        val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""testTagsAsResourceId\s*=""").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals("no other main source sets it", listOf("RootScreen.kt"), sources)
    }
}
