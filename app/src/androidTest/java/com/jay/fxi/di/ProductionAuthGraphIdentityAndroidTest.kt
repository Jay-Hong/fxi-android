package com.jay.fxi.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jay.fxi.data.auth.FirebaseAuthTokenSource
import com.jay.fxi.data.auth.FirebaseAuthUserObservation
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S5-4 slice 4b: in the real debug graph, the token source and the fence stream are one FirebaseAuthTokenSource, and every
 * observation reads the graph's one FirebaseAuth. The observation binding is unscoped, so the observation objects themselves
 * are not required to be the same.
 */
@RunWith(AndroidJUnit4::class)
class ProductionAuthGraphIdentityAndroidTest {

    private val probe = EntryPointAccessors.fromApplication(
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
        DebugAuthGraphProbe::class.java
    )

    private fun authOf(owner: Any): Any = owner.javaClass.getDeclaredField("auth").apply { isAccessible = true }.get(owner)

    @Test
    fun tokenAndFenceAreOneSourceAndEveryObservationReadsTheGraphsFirebaseAuth() {
        val auth = probe.firebaseAuth()
        assertNull("no signed-in user in the default Firebase app", auth.currentUser)
        assertSame(auth, probe.firebaseAuth())

        val tokens = probe.authTokenSource()
        val fences = probe.authFenceStream()
        assertTrue(tokens is FirebaseAuthTokenSource)
        assertSame("the token source is the fence stream", tokens, fences)
        assertSame(tokens, probe.authTokenSource())
        assertSame(fences, probe.authFenceStream())
        assertSame(auth, authOf(tokens))

        for (observation in listOf(probe.authUserObservation(), probe.authUserObservation())) {
            assertTrue(observation is FirebaseAuthUserObservation)
            assertSame("the observation reads the graph's FirebaseAuth", auth, authOf(observation))
        }
    }
}
