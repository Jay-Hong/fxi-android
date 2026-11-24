package com.jay.fxi.e5djourney

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.IdentityHashMap

/**
 * S7 E5d-J (`e5dj_j2_plan.claude.r3.md` R5): installs the lifecycle recorder before Application.onCreate (providers are installed
 * first; this one's initOrder runs ahead of the others). Three kinds of acknowledgement are kept apart: the provider's own
 * registration of Application.ActivityLifecycleCallbacks; the process observer, attached to ProcessLifecycleOwner once the
 * application has been created; and each Activity's own Lifecycle observer, attached in its earliest callback. Each Activity gets
 * an id unique in this process, recorded with its PID, task, configuration and whether it was given a saved state. A kill is not
 * expected to deliver a destroy; a gap is reported as a gap.
 */
internal class E5dJourneyLifecycleProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val application = context?.applicationContext as? Application ?: return false
        application.registerActivityLifecycleCallbacks(E5dJourneyLifecycle)
        E5dJourneyLifecycle.providerAck = true
        E5dJourneyRecord.add("lifecycle", "ack provider nonce=${E5dJourneyRecord.nonce} pid=${E5dJourneyRecord.pid}")
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

internal object E5dJourneyLifecycle : Application.ActivityLifecycleCallbacks {
    @Volatile var providerAck = false
    @Volatile var processAck = false
    @Volatile var activityAcks = 0

    private val ids = IdentityHashMap<Activity, Long>()
    private var next = 0L

    private fun id(activity: Activity): Long = synchronized(ids) { ids.getOrPut(activity) { ++next } }

    private fun note(activity: Activity, event: String) {
        E5dJourneyRecord.add("activity", "${activity.javaClass.simpleName}#${id(activity)} $event task=${activity.taskId}")
    }

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (!processAck) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
                E5dJourneyRecord.add("process", event.name)
            })
            processAck = true
            E5dJourneyRecord.add("lifecycle", "ack process")
        }
        val activityId = id(activity)
        if (activity is LifecycleOwner) {
            activity.lifecycle.addObserver(LifecycleEventObserver { _: LifecycleOwner, event: Lifecycle.Event ->
                E5dJourneyRecord.add("activity-lifecycle", "${activity.javaClass.simpleName}#$activityId ${event.name}")
            })
            activityAcks += 1
            E5dJourneyRecord.add("lifecycle", "ack activity ${activity.javaClass.simpleName}#$activityId")
        }
        note(activity, "pre-created saved=${savedInstanceState != null} config=${activity.resources.configuration.orientation}/" +
            "${activity.resources.configuration.screenWidthDp}x${activity.resources.configuration.screenHeightDp}")
        // U3a-2: read only, and before the Activity's own code reads or clears them — what the Intent that started this
        // Activity carries under the keys an alert tap is parsed from. Its own stream: the `activity` lines keep their form.
        E5dJourneyRecord.add("intent", "${activity.javaClass.simpleName}#$activityId launch ${launch(activity.intent)}")
        if (activity is com.jay.fxi.MainActivity) {
            val carried = carried(activity.intent, SOURCE_KEYS)
            E5dJourneyRecord.add("intent-source", "MainActivity#$activityId extras[$carried]")
        }
    }

    /** The flags, the action and, for each of [TAP_KEYS], whether [intent] carries it and as what: its class and its text. */
    private fun launch(intent: Intent?): String {
        val carried = carried(intent, TAP_KEYS)
        return "flags=0x${Integer.toHexString(intent?.flags ?: 0)} action=${intent?.action} extras[$carried]"
    }

    private fun carried(intent: Intent?, keys: List<String>): String {
        val extras = intent?.extras
        return keys.joinToString(",") { key ->
            if (extras?.containsKey(key) != true) return@joinToString "$key:absent"
            @Suppress("DEPRECATION") // the value's own class is what is recorded; a typed getter would coerce or drop it
            val value = extras.get(key) ?: return@joinToString "$key:null"
            "$key:${value.javaClass.simpleName}=${value.toString().take(40)}"
        }
    }

    private val SOURCE_KEYS = listOf("source", "asset")
    private val TAP_KEYS = listOf("notification_type", "type", "setting_id", "is_repeat")

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = note(activity, "created")
    override fun onActivityStarted(activity: Activity) = note(activity, "started")
    override fun onActivityResumed(activity: Activity) = note(activity, "resumed")
    override fun onActivityPaused(activity: Activity) = note(activity, "paused")
    override fun onActivityStopped(activity: Activity) = note(activity, "stopped")
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = note(activity, "saved-state")
    override fun onActivityDestroyed(activity: Activity) = note(activity, "destroyed")
}
