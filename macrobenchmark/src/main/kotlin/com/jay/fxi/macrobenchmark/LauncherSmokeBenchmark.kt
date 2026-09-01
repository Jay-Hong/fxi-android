package com.jay.fxi.macrobenchmark

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S0-f wiring smoke only. Performance journeys and thresholds belong to S5.
 *
 * It launches a benchmark-source-set-only no-data Activity. MainActivity and the app data
 * plane are not entered; S0-g remains the owner of D24 OFF admission for real app entrypoints.
 */
@RunWith(AndroidJUnit4::class)
class LauncherSmokeBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun launcherSmoke() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(Build.MODEL == EXPECTED_MODEL) {
            "Wrong benchmark model: expected $EXPECTED_MODEL, got ${Build.MODEL}"
        }
        check(Build.FINGERPRINT == EXPECTED_FINGERPRINT) {
            "Wrong benchmark fingerprint: the frozen D31 device contract does not match"
        }

        val device = UiDevice.getInstance(instrumentation)
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.None(),
            startupMode = StartupMode.COLD,
            iterations = 1,
            setupBlock = { pressHome() }
        ) {
            val intent = Intent(BENCHMARK_ACTION, Uri.parse(BENCHMARK_URI)).apply {
                setPackage(TARGET_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivityAndWait(intent)
            assertTrue(
                "Target package did not become visible",
                device.wait(
                    Until.hasObject(By.res(TARGET_PACKAGE, READY_RESOURCE)),
                    LAUNCH_TIMEOUT_MS
                )
            )
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.jay.fxi"
        const val EXPECTED_MODEL = "SM-F711N"
        const val EXPECTED_FINGERPRINT =
            "samsung/b2qksx/b2q:15/AP3A.240905.015.A2/F711NKSSEKZE1:user/release-keys"
        const val BENCHMARK_ACTION = "com.jay.fxi.action.BENCHMARK_SMOKE"
        const val BENCHMARK_URI = "fxi-benchmark://smoke/launcher"
        const val READY_RESOURCE = "benchmark_smoke_ready"
        const val LAUNCH_TIMEOUT_MS = 5_000L
    }
}
