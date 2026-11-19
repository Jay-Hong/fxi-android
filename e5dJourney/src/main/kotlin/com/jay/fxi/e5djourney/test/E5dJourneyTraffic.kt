package com.jay.fxi.e5djourney.test

/**
 * S7 E5d-J J2b2 (`s7e5dj_j2b2_design.codex.r1.md` D4 with r2): what the fixture server received, judged against a table
 * written before the phase. A phase is the name the record gave its lines when they were written; it is not their cause.
 * Each line is assigned to the one [Rule] it matches; a matching rule does not by itself prove the line's actual request origin.
 * A rule says how many such lines the phase may have. A line that matches no rule, or more than one, or a rule outside its
 * count, is a failure — never assigned to a phase by where it landed.
 *
 * The server's lines are the `http` stream (`<method> <path> q=<query> -> <kind>`) and the `ws` stream (`conn=<n> …`, J2b2 D3).
 */
internal object E5dJourneyTraffic {

    /** One record line; [nanos] is the device's elapsed-realtime clock when it was written. */
    class Line(val sequence: Int, val nanos: Long, val stream: String, val phase: String, val text: String)

    /** One expected kind of server line, by a pattern on `<stream> <text>`, with how many the phase may have. */
    class Rule(val name: String, pattern: String, val min: Int, val max: Int) {
        val regex = Regex(pattern)
        fun matches(line: Line) = regex.matches("${line.stream} ${line.text}")
    }

    fun parse(lines: List<String>): List<Line> = lines.mapNotNull { raw ->
        val parts = raw.split('\t', limit = 5)
        if (parts.size < 5) null else parts[0].toIntOrNull()?.let { Line(it, parts[1].toLongOrNull() ?: -1L, parts[2], parts[3], parts[4]) }
    }

    /** The server's lines after sequence [from] (exclusive) and up to [to] (inclusive, or the end). */
    fun server(lines: List<String>, from: Int, to: Int = Int.MAX_VALUE): List<Line> =
        parse(lines).filter { it.sequence in (from + 1)..to && (it.stream == "http" || it.stream == "ws") }

    /** Every problem with [lines] against [rules]: unmatched, matched twice, or a count outside its rule. Empty when it holds. */
    fun problems(lines: List<Line>, rules: List<Rule>): List<String> {
        val counts = rules.associateWith { 0 }.toMutableMap()
        val out = ArrayList<String>()
        for (line in lines) {
            val hits = rules.filter { it.matches(line) }
            when (hits.size) {
                0 -> out += "unclassified: ${line.sequence} ${line.stream} ${line.text}"
                1 -> counts[hits.single()] = counts.getValue(hits.single()) + 1
                else -> out += "ambiguous: ${line.sequence} ${line.stream} ${line.text} (${hits.map { it.name }})"
            }
        }
        for ((rule, n) in counts) if (n < rule.min || n > rule.max) out += "${rule.name}: $n (allowed ${rule.min}..${rule.max})"
        return out
    }

    /** How many of [lines] match [pattern] on `<stream> <text>`. */
    fun count(lines: List<Line>, pattern: String): Int = Regex(pattern).let { r -> lines.count { r.matches("${it.stream} ${it.text}") } }
}
