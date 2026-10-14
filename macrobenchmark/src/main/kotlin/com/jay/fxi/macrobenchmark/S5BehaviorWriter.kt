package com.jay.fxi.macrobenchmark

import android.os.Bundle
import android.os.Environment
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * S5-4 E slice 5: where the behavior record is written, and whether (S5Behavior.runId). The record goes in an `s5-behavior`
 * folder under the output folder AndroidX uses, never in that folder itself: AndroidX deletes its plain files when it starts
 * writing (benchmark-common 1.4.1 Outputs).
 */
internal object S5BehaviorWriter {
    private const val FOLDER = "s5-behavior"

    /** The run id, or null when the record is off (S5Behavior.runId). */
    fun runId(arguments: Bundle = InstrumentationRegistry.getArguments()): String? = S5Behavior.runId(arguments::getString)

    /** Writes [record] once; an existing file or a write error fails. */
    fun write(runId: String, record: Map<String, Any?>): File {
        val folder = File(baseFolder(), FOLDER)
        check(folder.isDirectory || folder.mkdirs()) { "cannot create $folder" }
        val file = File(folder, "s5-behavior-$runId.json")
        check(file.createNewFile()) { "$file already exists" }
        file.writeText(toJson(record).toString(), Charsets.UTF_8)
        return file
    }

    private fun baseFolder(): File {
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { return File(it) }
        @Suppress("DEPRECATION")
        return InstrumentationRegistry.getInstrumentation().targetContext.externalMediaDirs
            .firstOrNull { it != null && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
            ?: error("no mounted media folder for the behavior record")
    }

    /** Maps, lists, strings, numbers and booleans; null becomes JSON null so every key stays. */
    private fun toJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> put(k as String, toJson(v)) } }
        is List<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
        is String, is Int, is Boolean -> value
        else -> error("unexpected record value ${value.javaClass}")
    }
}
