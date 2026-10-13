package com.jay.fxi.data.auth

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S5-4 slice 4b: the production [FirebaseAuthUserObservation] over a real FirebaseAuth. The app is a named one with placeholder
 * options, never the debug project, and nothing here signs in. What is checked is that the adapter passes on the SDK's own
 * delivery: the first notice after registration, its thread, and that a closed registration hears nothing more. Whether a close
 * cancels a notice already queued is not claimed.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseAuthUserObservationAndroidTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: FirebaseApp
    private lateinit var auth: FirebaseAuth
    private lateinit var observation: FirebaseAuthUserObservation

    @Before fun setUp() {
        val options = FirebaseOptions.Builder()
            .setApplicationId("1:000000000000:android:0000000000000000")
            .setApiKey("s5-adapter-test-placeholder")
            .setProjectId("s5-adapter-test")
            .build()
        app = FirebaseApp.initializeApp(instrumentation.targetContext, options, "s5-adapter-test")
        auth = FirebaseAuth.getInstance(app)
        observation = FirebaseAuthUserObservation(auth)
    }

    @After fun tearDown() = app.delete()

    /** Each notice: whether it ran on the main looper, and whether the user it saw was null. */
    private inner class Calls {
        val notices = LinkedBlockingQueue<Pair<Boolean, Boolean>>()
        val onChange: () -> Unit = { notices.put((Looper.myLooper() == Looper.getMainLooper()) to (observation.currentUser() == null)) }
        fun next() = notices.poll(5, TimeUnit.SECONDS)
    }

    @Test
    fun firstNoticeAfterRegistrationComesOnceOnMainThreadSignedOut() {
        assertNotEquals("registered off the main thread", Looper.getMainLooper(), Looper.myLooper())
        assertNull(auth.currentUser)
        val calls = Calls()
        val registration = observation.register(calls.onChange)
        try {
            assertEquals("main thread, signed out", true to true, calls.next())
            instrumentation.waitForIdleSync()
            assertNull("exactly one notice", calls.notices.poll(500, TimeUnit.MILLISECONDS))
        } finally {
            registration.close()
        }
    }

    @Test
    fun closedRegistrationHearsNothingMoreWhileOpenOneDoes() {
        val closed = Calls()
        val open = Calls()
        val x = observation.register(closed.onChange)
        val y = observation.register(open.onChange)
        try {
            assertNotNull(closed.next())
            assertNotNull(open.next())
            instrumentation.waitForIdleSync()
            x.close()
            auth.signOut()
            assertEquals("the open registration hears the sign-out, on the main thread", true to true, open.next())
            instrumentation.waitForIdleSync()
            assertNull("the closed one hears nothing", closed.notices.poll(500, TimeUnit.MILLISECONDS))
        } finally {
            y.close()
        }
    }
}
