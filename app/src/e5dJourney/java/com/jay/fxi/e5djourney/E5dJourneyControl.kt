package com.jay.fxi.e5djourney

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FcmBroadcastProcessor
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * S7 E5d-J (`e5dj_j2_plan.claude.r3.md` R4, R7; `s7e5dj_j2b_channel.codex.r1.md` R1–R3): the fixture's control channel, a
 * provider call. The provider is exported under the signature permission, and [call] checks that permission itself, first,
 * before anything else: a provider's call is not covered by its manifest permission. The provider serializes commands on
 * its binder threads, one at a time under a lock of their own that no UI, lifecycle or hook callback takes. The fcm command
 * waits for the SDK task on a dedicated worker. Initial service connection can depend on main-thread progress, and the client
 * bounds the call at 30 seconds. Getting the provider and starting the process still go through the system and the main
 * thread's start-up.
 *
 * Every request carries an id and, except `discover`, the nonce of the process it was meant for; another nonce is a REJECT
 * before the app's graph is touched, so nothing aimed at a dead process is replayed in a new one. `discover` and `dump` read only
 * the record; `hosts` reads only the hook sets. The commands that start the graph or change state wait for the app to be ready
 * — the lifecycle recorder's provider, process and Activity acknowledgements — and are a REJECT `not-ready` until then, never
 * kept for later. Process commands (phase, dump, answer, auth) are distinct from host commands (hosts, arm, release), which
 * name a live hook set and the exact host, session, binding, operation and token they are for (`e5dj_j2_plan.claude.r3.md` C1).
 *
 * An ACK is the command taken and the fixture's own synchronous state changed — not a consumer, Root, Route or screen having
 * handled it, and for a release not the held delivery having finished; those are read from the record and the screen.
 */
internal class E5dJourneyControlProvider : ContentProvider() {
    private val serial = Any()

    // Light: no graph, server or production start here.
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context ?: throw SecurityException("control: no context")
        if (context.checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) throw SecurityException("control: $PERMISSION is required")
        return synchronized(serial) { E5dJourneyControl.handle(context, method, extras ?: Bundle()) }
    }

    // Not a way in: no data, no type.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val PERMISSION = "com.jay.fxi.e5djourney.permission.CONTROL"
        const val AUTHORITY = "com.jay.fxi.e5djourney.control"
    }
}

internal object E5dJourneyControl {

    /** The answer: a code and a JSON body naming the request, the command actually handled, this process's nonce and pid. */
    fun handle(context: Context, command: String, extras: Bundle): Bundle {
        val request = extras.getString(EXTRA_REQUEST).orEmpty()
        val expected = extras.getString(EXTRA_NONCE)
        fun answer(code: Int, body: Map<String, Any?>): Bundle {
            val fields = body + mapOf("request" to request, "nonce" to E5dJourneyRecord.nonce, "pid" to E5dJourneyRecord.pid, "command" to command)
            return Bundle().apply {
                putInt(RESULT_CODE, code)
                putString(RESULT_BODY, encode(fields).toString())
            }
        }
        if (command != DISCOVER && expected != E5dJourneyRecord.nonce) return answer(REJECT, mapOf("reason" to "nonce"))
        if (command in NEEDS_READY && !ready()) return answer(REJECT, mapOf("reason" to "not-ready"))
        val entry by lazy { EntryPointAccessors.fromApplication(context.applicationContext, E5dJourneyEntryPoint::class.java) }
        return when (command) {
            DISCOVER -> answer(ACK, emptyMap())
            START -> answer(ACK, E5dJourneyStart.ack(context, entry))
            PHASE -> {
                val name = extras.getString(EXTRA_ARG).orEmpty()
                if (name.isEmpty()) return answer(REJECT, mapOf("reason" to "phase"))
                val sequence = E5dJourneyRecord.mark(name)
                if (sequence < 0) return answer(REJECT, mapOf("reason" to "overflowed"))
                answer(ACK, mapOf("phase" to name, "sequence" to sequence))
            }
            DUMP -> {
                // A page from an exact cursor up to the reader's fixed upper (absent on the first page): nothing is clamped, and a
                // cursor or upper this record cannot answer exactly is a REJECT (`s7e5dj_j2a.codex.r1.md` Q4-A).
                val cursor = extras.getInt(EXTRA_CURSOR, -1)
                val max = extras.getInt(EXTRA_MAX, 0)
                val upper = if (extras.containsKey(EXTRA_UPPER)) extras.getInt(EXTRA_UPPER, -1) else null
                if (max > PAGE_MAX) return answer(REJECT, mapOf("reason" to "page"))
                val page = E5dJourneyRecord.page(cursor, max, upper) ?: return answer(REJECT, mapOf("reason" to "page"))
                answer(ACK, mapOf("cursor" to page.cursor, "upper" to page.upper, "last" to page.last, "more" to page.more,
                    "overflowed" to page.overflowed, "lines" to page.lines))
            }
            ANSWER -> when (extras.getString(EXTRA_ARG)) {
                "free" -> answer(ACK, mapOf("revision" to entry.server().answer(premium = false)))
                "active" -> answer(ACK, mapOf("revision" to entry.server().answer(premium = true)))
                else -> answer(REJECT, mapOf("reason" to "answer"))
            }
            HOSTS -> answer(ACK, mapOf("sets" to E5dJourneyHooks.describe()))
            ARM -> {
                val set = E5dJourneyHooks.set(extras.getLong(EXTRA_SET, -1)) ?: return answer(REJECT, mapOf("reason" to "set"))
                val (operation, refused) = set.arm(extras.getLong(EXTRA_HOST, -1), extras.getLong(EXTRA_SESSION, -1), extras.getLong(EXTRA_BINDING, -1))
                if (operation == null) answer(REJECT, mapOf("reason" to refused)) else answer(ACK, mapOf("operation" to operation))
            }
            RELEASE -> {
                val set = E5dJourneyHooks.set(extras.getLong(EXTRA_SET, -1)) ?: return answer(REJECT, mapOf("reason" to "set"))
                if (set.release(extras.getLong(EXTRA_OPERATION, -1), extras.getLong(EXTRA_TOKEN, -1))) answer(ACK, emptyMap())
                else answer(REJECT, mapOf("reason" to "operation"))
            }
            SIGN_OUT -> { entry.identity().signOut(); answer(ACK, mapOf("signedIn" to entry.identity().isSignedIn())) }
            // J2b2 (`s7e5dj_j2b2_design.codex.r1.md` D1): the production entry a purchase or restore success calls, called once.
            // The ACK is the call taken; the probe it starts, and what the app makes of it, are read from the record and screen.
            LOCAL_PREMIUM -> {
                E5dJourneyRecord.add("control", "local-premium signal")
                entry.subscriptionManager().onLocalPremiumSignal()
                answer(ACK, emptyMap())
            }
            // J2b2 (D5): a read-only reading of the production access coordinator; see E5dJourneyAccess.
            ACCESS -> {
                // The record's upper just before and just after the reading: what had been received by then, and what came in
                // while it was taken, so a journey can tell which answer a decision can have come from.
                val before = E5dJourneyRecord.upper()
                val reading = E5dJourneyAccess.read(entry.premiumAccessCoordinator())
                answer(ACK, reading + mapOf("recordUpperBefore" to before, "recordUpperAfter" to E5dJourneyRecord.upper()))
            }
            SIGN_IN -> { entry.identity().signIn(); answer(ACK, mapOf("signedIn" to entry.identity().isSignedIn())) }
            // J2b2 R5 (plan r3 §2.1 R8): before a case's journey and outside its baseline, the owner's last bank is prepared
            // through the production store's own write and read back through its own read; the answer carries what was read.
            // KB only, the premium editor's normal start: this prepares a premise and is never a way to choose another bank.
            SEED_LAST_BANK -> {
                if (extras.getString(EXTRA_ARG) != "kb") return answer(REJECT, mapOf("reason" to "seed"))
                val store = entry.lastSelectedBankStore()
                val readBack = runBlocking {
                    store.remember(E5dJourneyIdentity.UID, "kb")
                    store.read(E5dJourneyIdentity.UID)
                }
                E5dJourneyRecord.add("control", "last-bank seeded kb read-back=$readBack")
                answer(ACK, mapOf("readBack" to readBack, "recordUpper" to E5dJourneyRecord.upper()))
            }
            // U3a (`u3a_design.claude.r2.md` §1–§2; `s7u3a_design.codex.r2.md` Q4): one synthetic delivery, handed once to the
            // SDK's own broadcast processor — the call FirebaseInstanceIdReceiver makes — off this binder thread, whose calling
            // uid is the test APK's and which the SDK's in-app binder refuses. The answer waits for the processor's Task and
            // carries its result; no timer here answers for an unfinished delivery, so the client's own deadline is the only
            // one, and a call it gives up on leaves the process to be isolated. An ACK is the processor having returned, not a
            // notification posted or the app's callback run: those are read from the notifications and the record.
            FCM -> {
                val intent = fcmIntent(extras) ?: return answer(REJECT, mapOf("reason" to "fcm"))
                val mid = extras.getString(EXTRA_MESSAGE_ID)
                val visible = intent.hasExtra("gcm.n.e")
                @Suppress("DEPRECATION") // the reading DisplayNotification.isAppForeground itself takes
                val keyguard = context.getSystemService(KeyguardManager::class.java).inKeyguardRestrictedInputMode()
                val before = importance()
                val outcome = runCatching {
                    fcmWorker.submit<Int> { Tasks.await(FcmBroadcastProcessor(context.applicationContext).process(intent)) }.get()
                }
                val after = importance()
                val failure = outcome.exceptionOrNull()?.let { (it.cause ?: it).toString() }
                E5dJourneyRecord.add("control", "fcm mid=$mid visible=$visible result=${outcome.getOrNull()} failure=$failure")
                val readings = mapOf("importanceBefore" to before, "importanceAfter" to after, "keyguard" to keyguard)
                if (failure != null) answer(REJECT, readings + mapOf("reason" to "fcm-failed", "failure" to failure))
                else answer(ACK, readings + mapOf("result" to outcome.getOrNull()))
            }
            // U3a: this package's notifications as they stand now, read without the graph; `now` is this device's clock.
            NOTIFICATIONS -> {
                val active = context.getSystemService(NotificationManager::class.java).activeNotifications.map {
                    mapOf("tag" to it.tag, "id" to it.id, "channel" to it.notification.channelId,
                        "title" to it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(), "postTime" to it.postTime)
                }
                answer(ACK, mapOf("active" to active, "now" to System.currentTimeMillis()))
            }
            NOTIFICATIONS_CLEAR -> {
                context.getSystemService(NotificationManager::class.java).cancelAll()
                answer(ACK, emptyMap())
            }
            // U3a-2: the loopback server's row put in the fired or the armed shape (E5dJourneyServer.alertRow). Both extras are
            // strings; anything else, an id that does not read as a number, a state the server does not take or a row it does
            // not hold is refused with nothing changed. `recordUpper` is the record after the change's own line.
            ALERT_ROW -> {
                @Suppress("DEPRECATION")
                val id = (extras.get(EXTRA_ID) as? String)?.toIntOrNull() ?: return answer(REJECT, mapOf("reason" to "alert-row"))
                @Suppress("DEPRECATION")
                val state = extras.get(EXTRA_ARG) as? String ?: return answer(REJECT, mapOf("reason" to "alert-row"))
                if (!entry.server().alertRow(id, state)) return answer(REJECT, mapOf("reason" to "alert-row"))
                answer(ACK, mapOf("id" to id, "state" to state, "recordUpper" to E5dJourneyRecord.upper()))
            }
            SOURCE_ROW -> {
                @Suppress("DEPRECATION")
                val id = extras.get(EXTRA_ID) as? String
                @Suppress("DEPRECATION")
                val state = extras.get(EXTRA_ARG) as? String
                if (id != "102" || state == null || state !in setOf("seed", "armed", "fired") ||
                    !entry.server().sourceRow(102, state)) return answer(REJECT, mapOf("reason" to SOURCE_ROW))
                answer(ACK, mapOf("id" to 102, "state" to state, "recordUpper" to E5dJourneyRecord.upper()))
            }
            else -> answer(REJECT, mapOf("reason" to "command"))
        }
    }

    /** U3a: the one thread a synthetic delivery is handed to the SDK on; never a binder thread. */
    private val fcmWorker by lazy { Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "e5d-journey-fcm").apply { isDaemon = true } } }

    private fun importance(): Int = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance

    /**
     * U3a: the RECEIVE intent of one delivery — the caller's data strings as they are, the notification keys only for a visible
     * one, and its own message id. A delivery is data-only when neither a title nor a body key is there, and visible when both
     * are there and both are Strings. Null for an input the command does not take: no message id, a data value that is not a
     * String, a data key in the SDK's or the envelope's own namespace, or any other title and body — one without the other, an
     * explicit null, or a value that is not a String.
     */
    private fun fcmIntent(extras: Bundle): Intent? {
        @Suppress("DEPRECATION")
        val mid = (extras.get(EXTRA_MESSAGE_ID) as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val data = extras.getBundle(EXTRA_DATA) ?: return null
        val visible = extras.containsKey(EXTRA_TITLE) || extras.containsKey(EXTRA_BODY)
        @Suppress("DEPRECATION")
        val title = extras.get(EXTRA_TITLE) as? String
        @Suppress("DEPRECATION")
        val body = extras.get(EXTRA_BODY) as? String
        if (visible && (title == null || body == null)) return null
        val intent = Intent(FCM_RECEIVE)
        for (key in data.keySet()) {
            @Suppress("DEPRECATION")
            val value = data.get(key) as? String ?: return null
            if (FCM_RESERVED_PREFIXES.any { key.startsWith(it) } || key in FCM_ENVELOPE_KEYS) return null
            intent.putExtra(key, value)
        }
        if (visible) {
            intent.putExtra("gcm.n.e", "1")
            intent.putExtra("gcm.n.title", title)
            intent.putExtra("gcm.n.body", body)
        }
        intent.putExtra("google.message_id", mid)
        intent.putExtra("from", FCM_SENDER)
        return intent
    }

    /** The lifecycle recorder's three acknowledgements (R5): the app has started far enough for its graph to be the app's own. */
    private fun ready(): Boolean = E5dJourneyLifecycle.providerAck && E5dJourneyLifecycle.processAck && E5dJourneyLifecycle.activityAcks >= 1

    private fun encode(value: Any?): kotlinx.serialization.json.JsonElement = when (value) {
        null -> JsonPrimitive(null as String?)
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to encode(v) })
        is List<*> -> JsonArray(value.map { encode(it) })
        else -> JsonPrimitive(value.toString())
    }

    const val RESULT_CODE = "code"
    const val RESULT_BODY = "body"
    const val EXTRA_REQUEST = "request"
    const val EXTRA_NONCE = "nonce"
    const val EXTRA_ARG = "arg"
    const val EXTRA_CURSOR = "cursor"
    const val EXTRA_MAX = "max"
    const val EXTRA_UPPER = "upper"
    const val EXTRA_SET = "set"
    const val EXTRA_HOST = "host"
    const val EXTRA_SESSION = "session"
    const val EXTRA_BINDING = "binding"
    const val EXTRA_OPERATION = "operation"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_DATA = "data"
    const val EXTRA_MESSAGE_ID = "mid"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val ACK = 1
    const val REJECT = 2
    const val PAGE_MAX = 200
    const val DISCOVER = "discover"
    const val START = "start"
    const val PHASE = "phase"
    const val DUMP = "dump"
    const val ANSWER = "answer"
    const val SIGN_OUT = "sign-out"
    const val SIGN_IN = "sign-in"
    const val HOSTS = "hosts"
    const val ARM = "arm"
    const val RELEASE = "release"
    const val LOCAL_PREMIUM = "local-premium"
    const val ACCESS = "access"
    const val SEED_LAST_BANK = "seed-last-bank"
    const val FCM = "fcm"
    const val NOTIFICATIONS = "notifications"
    const val NOTIFICATIONS_CLEAR = "notifications-clear"
    const val ALERT_ROW = "alert-row"
    const val SOURCE_ROW = "source-row"
    const val EXTRA_ID = "id"

    private const val FCM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE"
    private const val FCM_SENDER = "000000000000"
    private val FCM_RESERVED_PREFIXES = listOf("google.", "gcm.")
    private val FCM_ENVELOPE_KEYS = setOf("from", "message_type", "collapse_key")
    private val NEEDS_READY = setOf(START, PHASE, ANSWER, SIGN_OUT, SIGN_IN, ARM, RELEASE, LOCAL_PREMIUM, ACCESS, SEED_LAST_BANK, FCM, NOTIFICATIONS_CLEAR, ALERT_ROW, SOURCE_ROW)
}
