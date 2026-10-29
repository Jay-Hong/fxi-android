package com.jay.fxi

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.*
import org.junit.Test

/** HEAD-compatible wrapper/extractor. Never copy candidate production files to the archive. */
class S6Krx3cO04CaptureTest {
    @Test fun writeGraphTraceOnlyWhenRequested() {
        val destination = System.getProperty("s63c.o04.graphTraceOut") ?: return
        try {
            val traces = S6Krx3bO03HeadProbe.COLUMNS.associateWith { S6Krx3bO03HeadProbe.trace(it) }
            val bytes = buildJsonObject {
                put("schema", 1); put("head", "0302b582e5aa22ca1223bee229a5c9d44947ef22")
                put("columns", JsonObject(traces))
            }.toString().toByteArray(Charsets.UTF_8)
            val path = Paths.get(destination)
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (failure: Throwable) { throw AssertionError("contract(O04g/generate): HEAD graph capture failed", failure) }
    }
    @Test fun extractRawSpansOnlyWhenRequested() {
        val destination = System.getProperty("s63c.o04.shellOut") ?: return
        val sourceRoot = System.getProperty("s63c.o04.sourceRoot") ?: "src/main/java/com/jay/fxi"
        try { S6Krx3cShellSourceContract.extract(File(sourceRoot), File(destination)) }
        catch (failure: Throwable) { throw AssertionError("contract(O04s/generate): raw HEAD extraction failed", failure) }
    }
}
