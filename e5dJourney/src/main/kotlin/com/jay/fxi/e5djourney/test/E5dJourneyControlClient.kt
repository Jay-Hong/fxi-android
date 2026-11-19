package com.jay.fxi.e5djourney.test

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONObject

/**
 * S7 E5d-J (`e5dj_j2_plan.claude.r3.md` R4; `s7e5dj_j2b_channel.codex.r1.md` R1–R3): the journey's side of the fixture's control
 * channel, a call to the fixture's provider at its one authority from this test APK's own context, under the signature
 * permission the provider checks. The call goes through an unstable client of this context's resolver, closed after each call,
 * so the provider's process dying under a call fails that call instead of taking this process with it. The method is the command. Each request has its own id and Bundle; the request id and the
 * nonce are written after the caller's extras, so no extras can replace them, and the Bundle is not touched once submitted.
 *
 * One request at a time: the call runs on one worker thread, and the wait for it — getting the provider, the call, the result —
 * is [timeoutMillis] at most ([TIMEOUT_SECONDS] unless a test sets another to bring about a timeout). A synchronous binder call
 * is not cancelled by giving up on it, so a timeout leaves the remote command possibly still running: the client is then
 * spoiled — it sends nothing more, the run's final collection included. Every timeout also leaves the package owing an
 * isolation ([isolationOwed]), whether or not this client knew which process the call went to — a first discovery has no nonce
 * (`s7e5dj_j2b1.codex.r1.md` F1); a known nonce is noted in [spoiled] as well, and kept in [isolated] once isolated, so a
 * discovery answering from it is refused for good. An exception, a null or malformed answer, or a timeout is a failure with its
 * cause kept; never a retry, another channel or a late answer reused. Nothing about a command that got no answer is assumed,
 * not even that it did not run.
 *
 * Every answer is checked before its body is used ([checkAnswer]; `s7e5dj_j2a.codex.r1.md` Q4-C, `s7e5dj_j2b1.codex.r1.md` F2):
 * an Int code that is ACK or REJECT and a String body; the body names this request and the command actually handled and
 * carries a positive Int pid; discovery names a real nonce; an acknowledged command meant for a nonce answers from that very
 * process. A record is read page by page up to the upper its first page fixed
 * (or the reader gave), and every page is checked against that cursor and upper ([checkPage], Q4-A).
 */
internal class E5dJourneyControlClient(private val context: Context, private val timeoutMillis: Long = TIMEOUT_SECONDS * 1_000) {
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "e5d-journey-control").apply { isDaemon = true } }

    @Volatile
    private var spoiledBy: String? = null

    /** The nonce of the process this client last heard from, for [spoiled] if a call to it times out. */
    @Volatile
    private var lastNonce: String? = null

    class Answer(val code: Int, val body: JSONObject) {
        val acked: Boolean get() = code == ACK
    }

    /** The record of one process up to the upper its first page fixed (or the reader gave), and the size of each page read. */
    class Dump(val nonce: String, val upper: Int, val lines: List<String>, val pages: List<Int>)

    @Synchronized
    fun send(command: String, nonce: String?, extras: Intent.() -> Unit = {}): Answer {
        spoiledBy?.let { error("control $command: this client is spoiled ($it); nothing more is sent") }
        val request = UUID.randomUUID().toString()
        val bundle = Bundle(Intent().apply(extras).extras ?: Bundle()).apply {
            putString("request", request)
            if (nonce != null) putString("nonce", nonce) else remove("nonce")
        }
        val pending = worker.submit<Bundle?> {
            // An unstable reference, released after every call: a stable one (what ContentResolver.call takes) makes the
            // platform kill this process when the provider's process dies under it, and isolating a given-up call is exactly a
            // force-stop while that call may still be running (`e5dj_j2b_dev15`: the test process died with the force-stop).
            val provider = checkNotNull(context.contentResolver.acquireUnstableContentProviderClient(URI)) { "control $command: no provider at $URI" }
            try {
                provider.call(command, null, bundle)
            } finally {
                provider.close()
            }
        }
        val result = try {
            pending.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            spoiledBy = "a $command call got no answer in ${timeoutMillis}ms"
            isolationOwed = true
            (nonce ?: lastNonce)?.let { spoiled += it }
            throw IllegalStateException("control $command: no answer in ${timeoutMillis}ms; the client is spoiled", timeout)
        } catch (failed: ExecutionException) {
            throw IllegalStateException("control $command: no answer (${failed.cause?.javaClass?.simpleName})", failed.cause ?: failed)
        }
        val answer = checkAnswer(command, request, nonce, result)
        if (answer.acked) lastNonce = answer.body.optString("nonce")
        return answer
    }

    /** [command] for [nonce], which must be acknowledged; its body. */
    fun ack(command: String, nonce: String?, extras: Intent.() -> Unit = {}): JSONObject {
        val answer = send(command, nonce, extras)
        check(answer.acked) { "control $command: expected ACK, got ${answer.code}: ${answer.body}" }
        return answer.body
    }

    /** [command], which must be rejected for [reason]. The answering process may be another than [nonce] says. */
    fun reject(command: String, nonce: String?, reason: String, extras: Intent.() -> Unit = {}) {
        val answer = send(command, nonce, extras)
        check(answer.code == REJECT) { "control $command: expected REJECT, got ${answer.code}: ${answer.body}" }
        check(answer.body.optString("reason") == reason) { "control $command: rejected for ${answer.body.optString("reason")}, not $reason" }
    }

    /** Whether a timed-out call may still be running; then this client sends nothing more. */
    val isSpoiled: Boolean get() = spoiledBy != null

    /** [nonce]'s record up to [upTo], or the upper the first page fixes when it is null, [max] lines a page. */
    fun dump(nonce: String, max: Int = PAGE_MAX, upTo: Int? = null): Dump {
        val lines = ArrayList<String>()
        val pages = ArrayList<Int>()
        var cursor = 0
        var upper: Int? = upTo
        while (true) {
            val fixed = upper
            val body = ack("dump", nonce) {
                putExtra("cursor", cursor)
                putExtra("max", max)
                if (fixed != null) putExtra("upper", fixed)
            }
            val page = checkPage(nonce, cursor, fixed, max, body)
            lines += page.lines
            pages += page.lines.size
            cursor += page.lines.size
            upper = page.upper
            if (!page.more) break
        }
        check(cursor == upper) { "dump: ended at $cursor short of upper $upper" }
        return Dump(nonce, upper, lines, pages)
    }

    /** Lets go of the worker without waiting for a call that may still be running. */
    fun close() {
        worker.shutdownNow()
    }

    class Page(val upper: Int, val lines: List<String>, val more: Boolean)

    companion object {
        const val PACKAGE = "com.jay.fxi"
        val URI: Uri = Uri.parse("content://com.jay.fxi.e5djourney.control")
        const val DISCOVER = "discover"
        const val ACK = 1
        const val REJECT = 2
        const val RESULT_CODE = "code"
        const val RESULT_BODY = "body"
        const val TIMEOUT_SECONDS = 30L
        const val PAGE_MAX = 200

        /** Processes a timed-out call was addressed to: a case starts from a new process before reading evidence again. */
        val spoiled: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

        /** Processes force-stopped for that: never cleared, so a discovery answering from one of them is refused for good. */
        val isolated: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

        /**
         * Set by every timeout, known nonce or not; cleared only by the next launch, after a force-stop has left no process of
         * the package. While it is set, nothing is sent to the app.
         */
        @Volatile
        var isolationOwed: Boolean = false

        /**
         * One answer to [command] under [request] (for [nonce], if given), checked before its body is used. A missing or
         * mistyped field is a failure, never read as a default: the code and body are taken as they are and checked for type.
         */
        fun checkAnswer(command: String, request: String, nonce: String?, result: Bundle?): Answer {
            checkNotNull(result) { "control $command: no answer (null)" }
            val code = result.get(RESULT_CODE)
            val text = result.get(RESULT_BODY)
            check(code is Int && text is String) { "control $command: a malformed answer (code=$code, body=${text?.javaClass?.simpleName})" }
            val body = JSONObject(text)
            check(body.optString("request") == request) { "control $command: an answer for another request: $body" }
            check(body.optString("command") == command) { "control $command: an answer for another command: $body" }
            check(code == ACK || code == REJECT) { "control $command: code $code is neither ACK nor REJECT: $body" }
            val pid = body.opt("pid")
            check(pid is Int && pid > 0) { "control $command: no valid pid ($pid): $body" }
            if (code == ACK && command == DISCOVER) {
                check(runCatching { UUID.fromString(body.optString("nonce")) }.isSuccess) { "discover: no real nonce: $body" }
            }
            if (code == ACK && nonce != null) {
                check(body.optString("nonce") == nonce) { "control $command: acknowledged by another process: $body" }
            }
            return Answer(code, body)
        }

        /**
         * One dump page, checked against what was asked: the same process, the cursor echoed, the upper fixed (or, on the first
         * page, at least the cursor), no overflow, a last at or past the upper, exactly the lines between the cursor and the upper
         * that fit in [max] with dense sequences from the cursor on, and `more` exactly when the upper is not reached yet. A page
         * that claims more but carries nothing, a cut tail or a moved upper fails.
         */
        fun checkPage(nonce: String, cursor: Int, upper: Int?, max: Int, body: JSONObject): Page {
            check(body.optString("nonce") == nonce) { "dump: another process answered: $body" }
            check(body.getInt("cursor") == cursor) { "dump: cursor ${body.getInt("cursor")} is not $cursor" }
            val fixed = body.getInt("upper")
            if (upper != null) check(fixed == upper) { "dump: upper moved from $upper to $fixed" } else check(fixed >= cursor) { "dump: upper $fixed before cursor $cursor" }
            check(!body.getBoolean("overflowed")) { "dump: the record overflowed" }
            check(body.getInt("last") >= fixed) { "dump: last ${body.getInt("last")} before upper $fixed" }
            val page = body.getJSONArray("lines")
            val expected = minOf(max, fixed - cursor)
            check(page.length() == expected) { "dump: ${page.length()} lines where $expected were due (cursor $cursor, upper $fixed)" }
            val lines = (0 until page.length()).map { page.getString(it) }
            lines.forEachIndexed { i, line ->
                check(line.substringBefore('\t').toIntOrNull() == cursor + i + 1) { "dump: line ${cursor + i + 1} is out of place: $line" }
            }
            val more = body.getBoolean("more")
            check(more == (cursor + lines.size < fixed)) { "dump: more=$more at ${cursor + lines.size} of $fixed" }
            check(!(more && lines.isEmpty())) { "dump: more with an empty page" }
            return Page(fixed, lines, more)
        }
    }
}
