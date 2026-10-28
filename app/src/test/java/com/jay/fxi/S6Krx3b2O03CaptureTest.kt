package com.jay.fxi

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/** Copy this file alone to the clean 1fff archive. It has no candidate-only dependencies. */
class S6Krx3b2O03CaptureTest {
    @Test fun writeHeadTraceOnlyWhenRequested() {
        val destination = System.getProperty("s63b2.o03.headTraceOut") ?: return
        try {
            val columns = S6Krx3bO03HeadProbe.COLUMNS.associateWith { S6Krx3bO03HeadProbe.trace(it) }
            val body = buildJsonObject {
                put("schema", 1)
                put("head", "1fff9b836a1d50e6a3a8b7d51913a1d670af1bad")
                put("columns", JsonObject(columns))
            }.toString().toByteArray(Charsets.UTF_8)
            val path = Paths.get(destination)
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.write(path, body, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (failure: Throwable) {
            throw AssertionError("contract(O01/head-generation): clean HEAD capture failed", failure)
        }
    }
}
