package com.jay.fxi

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
                releaseAdmissionOpen = true
            )
        )
        assertFalse(
            shouldEnableCrashlytics(
                debug = false,
                benchmarkNoData = true,
                releaseAdmissionOpen = true
            )
        )
        assertFalse(
            shouldEnableCrashlytics(
                debug = false,
                benchmarkNoData = false,
                releaseAdmissionOpen = false
            )
        )
        assertTrue(
            shouldEnableCrashlytics(
                debug = false,
                benchmarkNoData = false,
                releaseAdmissionOpen = true
            )
        )
    }
}
