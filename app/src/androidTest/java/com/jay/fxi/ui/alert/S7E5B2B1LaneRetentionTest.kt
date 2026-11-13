package com.jay.fxi.ui.alert

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.service.NotificationCapability
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5b2b1 (start agreed D6, gap hunt G09): the Activity-scoped lane value and the helper's action have no strong path
 * back to their Activity, so the weak map's key can be collected. The walker follows fields of the app's own classes,
 * androidx.activity's result classes and synthetic lambdas, skips Reference referents, and is shown to find a strong path
 * in controls that do hold the Activity (a field, a kept launcher) and none through a WeakReference.
 */
@RunWith(AndroidJUnit4::class)
class S7E5B2B1LaneRetentionTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun followed(type: Class<*>): Boolean {
        val name = type.name
        return name.startsWith("com.jay.fxi.") || name.startsWith("androidx.activity.") || name.startsWith("kotlin.jvm.internal.Ref")
    }

    /** True if [start] reaches an Activity through strong references of followed classes. */
    private fun reachesActivity(start: Any?): Boolean {
        val seen = IdentityHashMap<Any, Boolean>()
        val queue = ArrayDeque<Any>()
        if (start != null) queue += start
        while (queue.isNotEmpty()) {
            val obj = queue.removeFirst()
            if (seen.put(obj, true) != null) continue
            if (obj is Activity) return true
            // The application (and any non-Activity context) outlives every Activity; it is a leaf, not a path.
            if (obj is android.content.Context) continue
            if (obj is Reference<*>) continue
            if (obj is Array<*>) { obj.filterNotNull().forEach { queue += it }; continue }
            if (obj is Iterable<*>) { obj.filterNotNull().forEach { queue += it }; continue }
            if (obj is Map<*, *>) { obj.forEach { (k, v) -> k?.let { queue += it }; v?.let { queue += it } }; continue }
            var type: Class<*>? = obj.javaClass
            if (!followed(type!!)) continue
            while (type != null && type != Any::class.java) {
                for (field in type.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                    field.isAccessible = true
                    field.get(obj)?.let { queue += it }
                }
                type = type.superclass
            }
        }
        return false
    }

    @Test fun S7E5B2B1_L01_theLaneValueAndTheActionReachNoActivity() {
        val env = NotificationPermissionEnvironment(
            admitted = { true },
            liveIdentity = { A },
            premium = MutableStateFlow(OwnedPremiumAccess(A.uid, A.authGeneration, PremiumAccessState.PremiumConfirmed)),
            displayed = MutableStateFlow<NotificationCapability?>(NotificationCapability.APP_DISABLED),
            onRequestResult = { false },
            onAlreadyGranted = { false }
        )
        lateinit var action: NotificationPermissionAction
        compose.setContent {
            CompositionLocalProvider(LocalNotificationPermissionEnvironment provides env) {
                action = rememberNotificationPermissionAction()
            }
        }
        compose.waitForIdle()
        val activity = compose.activity
        val value = NotificationPermissionLanes.valueFor(activity)
        assertNotNull("the helper attached a lane to its Activity", value)
        assertFalse("the map's value reaches no Activity", reachesActivity(value))
        assertFalse("the action (an in-flight attempt's target) reaches no Activity", reachesActivity(action))
    }

    @Test fun S7E5B2B1_L02_theWalkerFindsAFieldAKeptLauncherAndNotAWeakReference() {
        val activity = compose.activity
        class Holds(val a: Any)
        assertTrue("a field holding the Activity", reachesActivity(Holds(activity)))
        lateinit var launcher: Any
        compose.runOnUiThread {
            launcher = activity.activityResultRegistry.register("e5b2b1-control", ActivityResultContracts.RequestPermission()) { }
        }
        assertTrue("a kept launcher reaches its Activity through the registry", reachesActivity(Holds(launcher)))
        assertFalse("a weak reference does not", reachesActivity(Holds(WeakReference(activity))))
    }

    private companion object {
        val A = AuthIdentityFence("user-a", 1L)
    }
}
