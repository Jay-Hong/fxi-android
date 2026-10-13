package com.jay.fxi

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseCollectionPolicyTest {
    @Test
    fun `app-owned startup and Crashlytics remain closed unless admission is open`() {
        assertFalse(shouldStartAppOwnedServices(releaseAdmissionOpen = false, benchmarkNoData = false))
        assertFalse(shouldStartAppOwnedServices(releaseAdmissionOpen = true, benchmarkNoData = true))
        assertTrue(shouldStartAppOwnedServices(releaseAdmissionOpen = true, benchmarkNoData = false))

        assertFalse(
            shouldEnableCrashlytics(
                debug = true,
                benchmarkNoData = false,
                releaseAdmissionOpen = true,
                crashCollectionAllowed = true
            )
        )
        assertFalse(
            shouldEnableCrashlytics(
                debug = false,
                benchmarkNoData = true,
                releaseAdmissionOpen = true,
                crashCollectionAllowed = true
            )
        )
        assertFalse(
            shouldEnableCrashlytics(
                debug = false,
                benchmarkNoData = false,
                releaseAdmissionOpen = false,
                crashCollectionAllowed = true
            )
        )
        assertTrue(
            shouldEnableCrashlytics(
                debug = false,
                benchmarkNoData = false,
                releaseAdmissionOpen = true,
                crashCollectionAllowed = true
            )
        )
        assertFalse(
            "S5-4 slice 4b: a build type that does not allow crash collection keeps it closed",
            shouldEnableCrashlytics(
                debug = false,
                benchmarkNoData = false,
                releaseAdmissionOpen = true,
                crashCollectionAllowed = false
            )
        )
    }

    /** S5-4 slice 4b: the application passes the build type's constant; production build types cannot tell a hard-coded true apart. */
    @Test
    fun `S5-4 4b the application passes the build type's crash collection constant`() {
        val source = File("src/main/java/com/jay/fxi/FXiApplication.kt").readText()
        assertTrue(source.contains("crashCollectionAllowed = BuildConfig.CRASH_COLLECTION_ALLOWED"))
    }
}
