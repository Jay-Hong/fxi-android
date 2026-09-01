package com.jay.fxi.admission

import com.jay.fxi.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseAdmissionVariantTest {
    @Test
    fun `compiled admission matches the fixed variant matrix`() {
        val expected = when (BuildConfig.BUILD_TYPE) {
            "debug", "benchmark" -> false
            "ciMinified", "release" -> true
            else -> error("Unclassified build type: ${BuildConfig.BUILD_TYPE}")
        }

        assertEquals(expected, BuildConfig.TOPIC_V2_RELEASE_ON)
        assertEquals(expected, ReleaseAdmission.isOpen)
    }
}
