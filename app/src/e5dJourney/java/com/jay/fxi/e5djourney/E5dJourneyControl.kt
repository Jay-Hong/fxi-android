package com.jay.fxi.e5djourney

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * S7 E5d-J (`e5dj_j2_plan.claude.r3.md` R4, R7; `s7e5dj_j2b_channel.codex.r1.md` R1–R3): the fixture's control channel, a
 * provider call. The provider is exported under the signature permission, and [call] checks that permission itself, first,
 * before anything else: a provider's call is not covered by its manifest permission. The commands run on the app's binder
 * threads, one at a time under a lock of their own that no UI, lifecycle or hook callback takes, and none waits on the main
 * thread; so a busy main thread or a crowded broadcast queue no longer stands between a command and its answer. Getting the
 * provider and starting the process still go through the system and the main thread's start-up.
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
            else -> answer(REJECT, mapOf("reason" to "command"))
        }
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
    private val NEEDS_READY = setOf(START, PHASE, ANSWER, SIGN_OUT, SIGN_IN, ARM, RELEASE, LOCAL_PREMIUM, ACCESS, SEED_LAST_BANK)
}
