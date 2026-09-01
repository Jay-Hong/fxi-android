package com.jay.fxi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseCollectionPolicyTest {
    @Test
    fun `Crashlytics is disabled for debug and benchmark no-data variants`() {
        assertFalse(shouldEnableCrashlytics(debug = true, benchmarkNoData = false))
        assertFalse(shouldEnableCrashlytics(debug = false, benchmarkNoData = true))
        assertTrue(shouldEnableCrashlytics(debug = false, benchmarkNoData = false))
    }
}
