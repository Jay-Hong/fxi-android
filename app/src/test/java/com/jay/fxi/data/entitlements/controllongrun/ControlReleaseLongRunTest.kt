package com.jay.fxi.data.entitlements.controllongrun

import com.jay.fxi.data.entitlements.control.ReleaseAccumulationFixture
import com.jay.fxi.data.entitlements.control.ReleaseCycleSample
import com.jay.fxi.data.entitlements.control.ReleaseStringProfile
import com.jay.fxi.data.entitlements.control.controlTestTimeout
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Deliberately outside the mutation battery's com.jay.fxi.data.entitlements.control.* filter.
 * 1,024 is a regression budget, never a row limit or a claim about operational lifetime.
 */
class ControlReleaseLongRunTest {
    @get:Rule val folder = TemporaryFolder()

    private fun repeatProfile(profile: ReleaseStringProfile) = runBlocking {
        controlTestTimeout("FileStorage long run ${profile.name}", 120_000) {
            val started = System.nanoTime()
            val fixture = ReleaseAccumulationFixture(File(folder.root, "long.preferences_pb"), profile)
            var first: ReleaseCycleSample? = null
            var last: ReleaseCycleSample? = null
            var setupNanos = 0L
            var cyclesNanos = 0L
            var teardownNanos = 0L
            try {
                fixture.seed()
                setupNanos = System.nanoTime() - started
                val cyclesStarted = System.nanoTime()
                repeat(1_024) {
                    val sample = fixture.cycle()
                    if (first == null) first = sample
                    last = sample
                }
                cyclesNanos = System.nanoTime() - cyclesStarted
            } finally {
                val closing = System.nanoTime()
                fixture.close()
                teardownNanos = System.nanoTime() - closing
            }
            println("LONG_RUN {\"profile\":\"${profile.name}\",\"cycles\":1024,\"setupNanos\":$setupNanos," +
                "\"cyclesNanos\":$cyclesNanos,\"teardownNanos\":$teardownNanos,\"first\":${first?.json()},\"last\":${last?.json()}}")
        }
    }

    @Test fun shortAscii1024() = repeatProfile(ReleaseStringProfile.SHORT_ASCII)
    @Test fun longAscii1024() = repeatProfile(ReleaseStringProfile.LONG_ASCII)
    @Test fun koreanAndSupplementaryPlane1024() = repeatProfile(ReleaseStringProfile.UTF8)
    @Test fun quotesAndBackslashes1024() = repeatProfile(ReleaseStringProfile.ESCAPED)
}
