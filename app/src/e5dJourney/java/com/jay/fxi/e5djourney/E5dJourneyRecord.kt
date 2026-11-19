package com.jay.fxi.e5djourney

import android.os.Process
import android.os.SystemClock
import java.util.UUID

/**
 * S7 E5d-J (`e5dj_j2_plan.claude.r3.md` R4): one ordered record of what this process saw — server requests, lifecycle events,
 * hook events, phase marks — each line with a dense sequence, a monotonic time, a stream and the phase it fell in. The process is
 * named by [nonce], so two processes never share a sequence. Bounded: past [LIMIT] it stops and says so ([overflowed]); a reader
 * pages from a cursor up to an upper sequence fixed by its first page, and treats a gap, a cut tail, an overflow, a moved upper
 * or another nonce as a failure, never as "nothing happened" (`s7e5dj_j2a.codex.r1.md` Q4-A).
 */
internal object E5dJourneyRecord {
    val nonce: String = UUID.randomUUID().toString()
    val pid: Int = Process.myPid()
    const val LIMIT = 50_000

    private val lock = Any()
    private val lines = ArrayList<String>()
    private var phase = "boot"

    @Volatile
    var overflowed = false
        private set

    fun add(stream: String, text: String) {
        synchronized(lock) { append(stream, text) }
    }

    /** Runs [block] under the record's lock without recording anything. */
    fun <T> under(block: () -> T): T = synchronized(lock) { block() }

    /** Runs [block] and records its line under the same lock, so the line's place in the record is where [block] ran. */
    fun <T> addWith(stream: String, block: () -> Pair<T, String>): T = synchronized(lock) {
        val (value, text) = block()
        append(stream, text)
        value
    }

    /** Starts [next] as the phase of every later line, and records the mark itself in the same order; returns its sequence. */
    fun mark(next: String): Int = synchronized(lock) {
        phase = next
        append("phase", next)
    }

    fun currentPhase(): String = synchronized(lock) { phase }

    /** The sequence of the last line written so far (J2b2 D5: an access reading is bounded by the record around it). */
    fun upper(): Int = synchronized(lock) { lines.size }

    /** The new line's sequence, or -1 once the record is full. */
    private fun append(stream: String, text: String): Int {
        if (lines.size >= LIMIT) {
            overflowed = true
            return -1
        }
        val clean = text.replace('\t', ' ').replace('\n', ' ')
        lines += "${lines.size + 1}\t${SystemClock.elapsedRealtimeNanos()}\t$stream\t$phase\t$clean"
        return lines.size
    }

    /**
     * The lines after sequence [cursor] up to [upper], at most [max] of them, or null if the request is not one this record can
     * answer exactly: no clamping. [upper] is the reader's fixed snapshot; null means "the last sequence now", which a reader
     * takes on its first page and sends back on every later one.
     */
    fun page(cursor: Int, max: Int, upper: Int?): Page? = synchronized(lock) {
        val fixed = upper ?: lines.size
        if (cursor < 0 || cursor > fixed || fixed > lines.size || max < 1) return null
        val to = minOf(fixed, cursor + max)
        Page(cursor, fixed, lines.size, lines.subList(cursor, to).toList(), to < fixed, overflowed)
    }

    class Page(val cursor: Int, val upper: Int, val last: Int, val lines: List<String>, val more: Boolean, val overflowed: Boolean)
}
