package com.jay.fxi.ui.auth

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.jay.fxi.BuildConfig
import com.jay.fxi.data.auth.AuthUserObservation
import com.jay.fxi.data.auth.ObservedAuthUser
import com.jay.fxi.data.auth.ObservedAuthUserHandle
import com.jay.fxi.di.DebugAuthGraphProbe
import com.jay.fxi.domain.model.AuthProvider
import com.jay.fxi.domain.model.AuthState
import com.jay.fxi.domain.model.UserInfo
import com.revenuecat.purchases.Purchases
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S5-4 slice 4b: the real [AuthViewModel] over a controlled user observation. The other constructor arguments come from the
 * debug graph; the FirebaseAuth argument is a placeholder app's, which the observation path does not use. The preconditions
 * fail rather than skip: each keeps the test away from RevenueCat and from token refresh. The empty-key case checks field and
 * provider delivery and that the selected handle is kept; it is not the RevenueCat-in-progress profile change test, which
 * stays open (4b-2b plan r3).
 */
@RunWith(AndroidJUnit4::class)
class AuthViewModelObservationAndroidTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val probe = EntryPointAccessors.fromApplication(instrumentation.targetContext.applicationContext, DebugAuthGraphProbe::class.java)
    private val store = ViewModelStore()
    private val users = ControlledUserObservation()
    private lateinit var app: FirebaseApp
    private lateinit var placeholderAuth: FirebaseAuth

    @Before fun setUp() {
        assertFalse("a D24 OFF debug build", BuildConfig.TOPIC_V2_RELEASE_ON)
        assertTrue("no RevenueCat key in this build", BuildConfig.REVENUECAT_API_KEY.isBlank())
        assertFalse("RevenueCat is not configured in this process", Purchases.isConfigured)
        assertFalse(probe.subscriptionManager().isRevenueCatConfigured())
        assertNull("no signed-in user in the default Firebase app", probe.firebaseAuth().currentUser)
        val options = FirebaseOptions.Builder()
            .setApplicationId("1:000000000000:android:0000000000000000")
            .setApiKey("s5-viewmodel-test-placeholder")
            .setProjectId("s5-viewmodel-test")
            .build()
        app = FirebaseApp.initializeApp(instrumentation.targetContext, options, "s5-viewmodel-test")
        placeholderAuth = FirebaseAuth.getInstance(app)
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { store.clear() }
        app.delete()
    }

    private fun newViewModel(): AuthViewModel {
        lateinit var vm: AuthViewModel
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AuthViewModel(
                    placeholderAuth, users, probe.authTokenProvider(), probe.authTransitionCoordinator(), probe.subscriptionManager(),
                    probe.pushNotificationManager(), probe.authAccessBinder(), probe.premiumAccessCoordinator()
                ) as T
            })[AuthViewModel::class.java]
        }
        return vm
    }

    private fun AuthViewModel.awaitState(expected: AuthState) = runBlocking {
        withTimeout(5_000) { authState.first { it == expected } }
    }

    @Test
    fun initialSignedOutReplayGivesSignedOut() {
        newViewModel().awaitState(AuthState.SignedOut)
    }

    @Test
    fun userFieldsAndAppleProviderReachTheState() {
        users.current = ControlledUserObservation.User("uid-a", "a@example.test", "A", "https://example.test/a.png", "apple.com")
        newViewModel().awaitState(AuthState.SignedIn(UserInfo("uid-a", "a@example.test", "A", "https://example.test/a.png", AuthProvider.APPLE)))
    }

    @Test
    fun googleAndMissingProvidersAreGoogleAndSignOutFollows() {
        users.current = ControlledUserObservation.User("uid-g", null, "G", null, "google.com")
        val vm = newViewModel()
        vm.awaitState(AuthState.SignedIn(UserInfo("uid-g", null, "G", null, AuthProvider.GOOGLE)))
        users.change(null)
        vm.awaitState(AuthState.SignedOut)
        users.change(ControlledUserObservation.User("uid-n", "n@example.test", null, null, null))
        vm.awaitState(AuthState.SignedIn(UserInfo("uid-n", "n@example.test", null, null, AuthProvider.GOOGLE)))
    }

    @Test
    fun theSelectedUserIsKeptWhenTheCurrentUserChangesAfterSelection() {
        val a = ControlledUserObservation.User("uid-a", "a@example.test", "A", null, "apple.com")
        val b = ControlledUserObservation.User("uid-b", "b@example.test", "B", null, "google.com")
        users.current = a
        users.afterSelect = {
            a.displayName = "A changed"
            users.current = b
            users.afterSelect = {}
        }
        newViewModel().awaitState(AuthState.SignedIn(UserInfo("uid-a", "a@example.test", "A changed", null, AuthProvider.APPLE)))
    }

    @Test
    fun onClearedClosesTheRegistrationAndNothingIsDeliveredAfter() {
        val vm = newViewModel()
        vm.awaitState(AuthState.SignedOut)
        val delivered = users.delivered()
        instrumentation.runOnMainSync { store.clear() }
        assertEquals("one close", 1, users.closes.get())
        users.change(ControlledUserObservation.User("uid-late", null, null, null, null))
        instrumentation.waitForIdleSync()
        assertEquals("no delivery after the close", delivered, users.delivered())
        assertEquals(AuthState.SignedOut, vm.authState.value)
    }
}

/** Delivers like FirebaseAuth: a posted notice right after registration, then one per change, all on the main looper. */
private class ControlledUserObservation : AuthUserObservation {
    private val main = Handler(Looper.getMainLooper())
    private val registrations = CopyOnWriteArrayList<Registration>()
    private val deliveries = AtomicInteger()
    val closes = AtomicInteger()

    @Volatile var current: User? = null

    /** Runs after currentUser() has selected its user and before it returns. */
    @Volatile var afterSelect: () -> Unit = {}

    class User(
        override val uid: String,
        @Volatile var email: String?,
        @Volatile var displayName: String?,
        @Volatile var photoUrl: String?,
        @Volatile var providerId: String?
    ) : ObservedAuthUserHandle {
        override fun readProfile() = ObservedAuthUser(uid, email, displayName, photoUrl, providerId)
    }

    private class Registration(val onChange: () -> Unit) {
        @Volatile var open = true
    }

    fun delivered(): Int = deliveries.get()

    fun change(user: User?) {
        current = user
        registrations.forEach(::post)
    }

    override fun currentUser(): ObservedAuthUserHandle? {
        val selected = current
        afterSelect()
        return selected
    }

    override fun register(onChange: () -> Unit): AutoCloseable {
        val registration = Registration(onChange)
        registrations += registration
        post(registration)
        return AutoCloseable {
            registration.open = false
            registrations -= registration
            closes.incrementAndGet()
        }
    }

    private fun post(registration: Registration) {
        main.post {
            if (registration.open) {
                deliveries.incrementAndGet()
                registration.onChange()
            }
        }
    }
}
