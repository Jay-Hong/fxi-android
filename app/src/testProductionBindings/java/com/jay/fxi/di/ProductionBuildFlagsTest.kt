package com.jay.fxi.di

import com.jay.fxi.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S5-4 slice 4b: the production build types keep crash collection allowed and expose no test tags as resource ids. */
class ProductionBuildFlagsTest {

    @Test
    fun `S5-4 4b production build types keep the default flags`() {
        assertTrue("crash collection allowed", BuildConfig.CRASH_COLLECTION_ALLOWED)
        assertFalse("no test tags as resource ids", BuildConfig.SEMANTICS_RESOURCE_IDS)
    }
}
