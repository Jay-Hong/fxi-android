package com.jay.fxi.data.entitlements.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Why a payload string could not be read as a list of obligations. */
enum class PayloadUnreadable {
    /** Larger than the limit. The limit is ours, not the platform's — see [ControlPayloadCodec]. */
    TOO_LARGE,

    /** Not JSON at all. An empty string is this, and not an empty list. */
    NOT_JSON,

    /** JSON, but not an array. A single object or a bare number is the wrong shape for a payload. */
    NOT_AN_ARRAY,

    /**
     * An object names the same key twice, so the parser kept one value and dropped the other.
     *
     * The dropped value is already gone by the time this code runs, and re-writing what is left would
     * make the loss permanent and invisible. The payload is kept whole and unread instead.
     */
    DUPLICATE_KEY,

    /**
     * Nested deeper than [ControlPayloadCodec.maxDepth].
     *
     * An obligation is a flat record, so this is damage or a format this build does not know. It is
     * refused rather than written back, because the library's own `toString` walks a tree by
     * recursion and a deep one overflows the stack — at a depth that fits the size limit easily.
     */
    TOO_DEEP
}

/** One element of a payload array, as this build was able to read it. */
sealed interface PayloadEntry {

    /**
     * An object — the shape every obligation uses. Its fields are deliberately not interpreted here.
     *
     * Holding the parsed object whole is what keeps *this* layer from dropping a field: it cannot
     * lose what it never looked at. It does not make preservation structural for anyone else, and the
     * type does not enforce it — `copy(fields = …)` takes any tree, replacing a nested object drops
     * whatever was inside it, and `JsonObject` wraps the map it is given rather than copying it, so a
     * caller that mutates that map afterwards changes this entry. A reader that names some fields owes
     * carrying the rest through at every depth, and owes not writing into a map it shares.
     */
    data class Obj(val fields: JsonObject) : PayloadEntry

    /**
     * An element that is not an object, kept exactly as found.
     *
     * An obligation is an object, so a number, a string, a null or a nested array here is a wrong
     * type rather than an obligation — the distinction §7.1 asks for. It is neither interpreted nor
     * discarded: a later build that understands it would otherwise find the text gone.
     */
    data class Uninterpretable(val raw: JsonElement) : PayloadEntry
}

/** A payload string, read. */
sealed interface PayloadRead {

    /** The array, in order. An empty list here means the array was empty, which is the normal empty state. */
    data class Parsed(val entries: List<PayloadEntry>) : PayloadRead

    /** Kept whole, with the reason. The caller closes the access this payload governs and waits. */
    data class Unreadable(val reason: PayloadUnreadable, val raw: String) : PayloadRead
}

/** The result of writing a payload. */
sealed interface PayloadWrite {

    data class Encoded(val text: String) : PayloadWrite

    /**
     * Over the limit. Only [Encoded] carries text, so nothing here can be mistaken for something to
     * store; the count and the limit are what a diagnosis needs. The caller still holds the entries,
     * and the obligation it must not meet by dropping some of them to fit.
     */
    data class TooLarge(val bytes: Int, val limit: Int) : PayloadWrite
}

/**
 * The outer shape of one control payload: a JSON array of obligations, in both directions.
 *
 * **Nothing in production reads or writes through this yet** (purger 설계 v3 final §7.1, P2b).
 * The four control payloads — `seal_v1`, `demand_v1`, `hold_v1`, `recovery_intent_v1` — are separate
 * string keys of `fxi_access_epoch`, deliberately kept out of the purge journal string: the shipped
 * journal decoder widens any line it cannot read into the broadest possible obligation, so a control
 * line mixed in there would be read by an older build as "purge everything".
 *
 * This slice owns the envelope only. It names no obligation field, so it cannot lose one; what it
 * hands on is the tree it parsed. It cannot tell whether a tree handed *back* to it dropped anything,
 * so the next slice owes preserving the fields it does not name — see [PayloadEntry.Obj].
 *
 * ### Size
 *
 * [maxPayloadBytes] is a tripwire we chose, not a platform limit; the Preferences path this record
 * lives on has no small size cap of its own. Because the design closes the access a payload governs
 * rather than truncating it, setting the limit too low locks a user out — so the default is roughly
 * fifty times what one obligation of each kind costs, and it is injectable so a test can drive the
 * boundary without building a 64 KiB fixture.
 *
 * The check runs at different moments in each direction, and that asymmetry is deliberate. Reading,
 * it runs **before** parsing: the cost being guarded against is parsing something huge, so a payload
 * over the limit is [PayloadUnreadable.TOO_LARGE] even when it is also malformed. Writing, it runs
 * **after** the final encoding, which is the simple place to know the real count rather than the only
 * one — an encoder that counted as it went would do as well.
 *
 * ### What round-tripping guarantees
 *
 * Every value is written back with the characters it arrived with, at any depth. `encode(decode(x))`
 * differs from `x` only in the whitespace between tokens and in how a string spells an escape, so the
 * transform is idempotent from the first write on. That matters because DataStore skips the write when
 * the value has not changed (`DataStoreImpl.transformAndWrite`), so re-reading and re-writing an
 * untouched payload must not charge a file rewrite for nothing — though the *first* normalisation of a
 * payload written by something else can still differ, and so still writes.
 *
 * Idempotence on its own would prove nothing about preservation: a transform that rounds a number
 * keeps rounding it to the same thing. Preservation is what the verbatim write below buys; idempotence
 * is only what it costs DataStore.
 *
 * The one place the written text is not the tree's own spelling is an unpaired surrogate, which
 * [storable] writes as `\uXXXX` because that is the only spelling the record's storage keeps. It is
 * the same value either way, and the size below is measured after it.
 *
 * Writing goes through [JsonElement.toString] rather than a serializer, and that is not a style
 * choice. `JsonPrimitiveSerializer` re-reads an unquoted literal as a `Long` or a `Double` and writes
 * *that* back, which silently rounds `1.0000000000000000001` to `1.0`, turns `1e-400` into `0.0`, and
 * throws on `1e400` — a payload this layer claims to be preserving would come back changed, or not
 * come back at all. What [encode] does with a value's characters therefore cannot fail; its own two
 * refusals are [PayloadWrite.TooLarge] for a payload that will not fit, and a failed precondition for
 * a tree a caller handed it that this codec would not have read back — something JSON cannot express,
 * or nesting past [maxDepth].
 *
 * ### What the parser accepts and this does not
 *
 * The tree reader is looser than JSON in two ways, and both would cost a payload silently.
 *
 * It accepts literals JSON has no room for — `foo`, `01`, `NaN` — which a serializer then rewrites as
 * something else. Rather than pass them on and hope every later reader checks `isString` on every
 * value, [decode] refuses the payload and keeps the text.
 *
 * It also keeps only one value when an object names the same key twice, and by the time this code has
 * a tree the other value is gone. Counting the members the *text* writes — one per `:` outside a
 * string — against the members the tree kept finds that, including when the repeat is spelled
 * differently (`"a"` and `"\u0061"`) or sits inside a nested object. Such a payload is
 * [PayloadUnreadable.DUPLICATE_KEY], kept whole and unread.
 *
 * And it reads past an array's ending: `[1]2]` comes back as `[1,2]`, so damage would be written back
 * as a well-formed list of obligations. The same scan pairs every bracket and brace outside a string,
 * which is what refuses it. (`[1]]` and `[1] :` the parser does refuse on its own; `[1]2]` it does
 * not — measured.)
 *
 * ### Depth
 *
 * [maxDepth] is the second tripwire, and it exists because recursion is what walks a tree here:
 * `JsonElement.toString` overflows the stack on a payload that fits the size limit with room to spare
 * — measured at 2,000 levels and 12,003 bytes on this build's test JVM — and the tree reader recurses
 * on the way in as well.
 *
 * Reading counts it twice: off the text before the parser is handed anything, in the same scan that
 * counts members and pairs brackets, and again off the tree, in the same walk that checks the
 * literals. The first is what keeps the recursive parser from ever being given one; the second is
 * what the write side reuses. Both walks are iterative, so neither can overflow on the payload the
 * limit exists to refuse. A payload past it is [PayloadUnreadable.TOO_DEEP] on the way in and a
 * failed precondition on the way out.
 *
 * The size check is a separate thing and does not share either walk: it is the UTF-8 length of the
 * text, taken before the scan on the way in and after the encoding on the way out.
 */
class ControlPayloadCodec(
    private val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH
) {

    init {
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be positive" }
        require(maxDepth > 0) { "maxDepth must be positive" }
    }

    /**
     * Reads one payload string.
     *
     * The caller says whether the key was there at all; absence is a different fact and not this
     * function's to invent. An empty string is [PayloadUnreadable.NOT_JSON] rather than an empty
     * list, for the same reason the purge journal treats a key holding nothing as damage: the writer
     * stores the normal empty state as an explicit `[]`, so a key present and saying nothing did not
     * come from the writer.
     */
    fun decode(raw: String): PayloadRead {
        val bytes = raw.toByteArray(Charsets.UTF_8).size
        if (bytes > maxPayloadBytes) return PayloadRead.Unreadable(PayloadUnreadable.TOO_LARGE, raw)
        val written = when (val scan = scanText(raw)) {
            is Scan.Ok -> scan.members
            is Scan.Rejected -> return PayloadRead.Unreadable(scan.reason, raw)
        }
        val element = runCatching { JSON.parseToJsonElement(raw) }.getOrElse {
            return PayloadRead.Unreadable(PayloadUnreadable.NOT_JSON, raw)
        }
        val kept = when (val scan = scanTree(element)) {
            is Scan.Ok -> scan.members
            is Scan.Rejected -> return PayloadRead.Unreadable(scan.reason, raw)
        }
        if (element !is JsonArray) return PayloadRead.Unreadable(PayloadUnreadable.NOT_AN_ARRAY, raw)
        if (kept != written) return PayloadRead.Unreadable(PayloadUnreadable.DUPLICATE_KEY, raw)
        return PayloadRead.Parsed(element.map { item ->
            if (item is JsonObject) PayloadEntry.Obj(item) else PayloadEntry.Uninterpretable(item)
        })
    }

    /**
     * The text with every unpaired surrogate spelled as `\uXXXX`, which is the only way to store one.
     *
     * A lone surrogate is not a character UTF-8 can carry, and the record's storage is protobuf, whose
     * encoder substitutes `?` for it rather than failing. Measured on a real DataStore file:
     * `[{"future":"\uD800"}]` arrives as pure ASCII and stores fine, but the tree it parses to holds a
     * lone surrogate, and writing *that* back puts `3F` in the file — the payload comes back saying
     * something else. Two such keys in one object come back as two `?` keys, and every later read of
     * that file answers [PayloadUnreadable.DUPLICATE_KEY] — the output would manufacture exactly the
     * damage that branch exists to catch, and the branch has no way to tell it apart from the real
     * thing. What that costs a user is for the slice that wires this up to say; nothing here is wired.
     *
     * Escaping is not a change of value *to this reader*: `\uD800` and the lone surrogate parse to the
     * same tree, so this writes the same obligation in the spelling that survives the trip. The claim
     * is scoped on purpose — RFC 8259 §8.2 leaves unpaired surrogates to the implementation, so two
     * JSON readers need not agree, and what is measured here is that this parser's UTF-16 value comes
     * back from a real file unchanged. Surrogates that form a pair are left alone — they encode, and
     * the measured file keeps them.
     *
     * Every surrogate in the output sits inside a string literal: [scanTree] has already held every
     * non-string value to ASCII (`NUMBER`, `BARE_LITERALS`), and the structural characters are ASCII
     * too. So this needs no parser of its own, and it reaches object keys as readily as values.
     */
    private fun storable(text: String): String {
        if (text.none(Char::isSurrogate)) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                out.append(ch).append(text[i + 1])
                i += 2
                continue
            }
            if (text.hasUnpairedSurrogateAt(i)) out.append("\\u%04X".format(ch.code)) else out.append(ch)
            i++
        }
        return out.toString()
    }

    /**
     * How many members the text spells out, counted without parsing: one per `:` outside a string.
     *
     * A colon can only separate a member's name from its value, so this counts every member written
     * down — including the ones the parser is about to collapse into one. Null when the text cannot be
     * scanned that way at all: an unterminated string, or a raw control character inside one, which
     * the tree reader accepts and JSON does not.
     */
    private fun scanText(raw: String): Scan {
        var inString = false
        var escaped = false
        var members = 0
        val open = ArrayDeque<Char>()
        for (ch in raw) {
            when {
                !inString -> when (ch) {
                    '"' -> inString = true
                    ':' -> members++
                    '[', '{' -> {
                        open.addLast(ch)
                        if (open.size > maxDepth) return Scan.Rejected(PayloadUnreadable.TOO_DEEP)
                    }
                    ']' -> if (open.removeLastOrNull() != '[') return malformed
                    // Dropping this arm does not let damage through — the bracket arm, the emptiness
                    // check and the parser between them refuse every one of the 48,862 strings up to
                    // seven characters over `[]{}1,:"a` where its answer would change. It still matters
                    // for *which* answer: without it `[}` unwinds one too far, and what follows reads as
                    // depth rather than as the damage it follows.
                    '}' -> if (open.removeLastOrNull() != '{') return malformed
                }
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
                ch < ' ' -> return malformed
            }
        }
        return if (inString || open.isNotEmpty()) malformed else Scan.Ok(members)
    }

    /**
     * How many members the parsed tree kept, or null when it holds a value JSON cannot express.
     *
     * The tree reader accepts literals JSON does not — `foo`, `01`, `NaN` — and a serializer would
     * turn the first of those into a string on the way back out. They are refused here rather than
     * handed on in the hope that a later layer remembers to check `isString` on every value: what
     * this layer returns is either well formed or [PayloadRead.Unreadable] with the text intact.
     */
    private fun scanTree(root: JsonElement): Scan {
        val pending = ArrayDeque<Pair<JsonElement, Int>>().apply { addLast(root to 1) }
        var members = 0
        while (pending.isNotEmpty()) {
            val (element, depth) = pending.removeLast()
            if (depth > maxDepth) return Scan.Rejected(PayloadUnreadable.TOO_DEEP)
            when (element) {
                is JsonObject -> {
                    members += element.size
                    element.values.forEach { pending.addLast(it to depth + 1) }
                }
                is JsonArray -> element.forEach { pending.addLast(it to depth + 1) }
                is JsonPrimitive ->
                    if (!element.isString && element.content !in BARE_LITERALS && !NUMBER.matches(element.content)) {
                        return malformed
                    }
            }
        }
        return Scan.Ok(members)
    }

    /**
     * What a scan found: the members it counted, or why the payload is not read.
     *
     * Both scans are iterative, so neither can overflow on the very payload the depth limit exists to
     * refuse.
     */
    private sealed interface Scan {
        data class Ok(val members: Int) : Scan
        data class Rejected(val reason: PayloadUnreadable) : Scan
    }

    private val malformed = Scan.Rejected(PayloadUnreadable.NOT_JSON)

    /**
     * Writes a payload, with every value's characters as they were read.
     *
     * An empty list is written as `[]` — the explicit normal empty state, not an absent key.
     */
    fun encode(entries: List<PayloadEntry>): PayloadWrite {
        val array = JsonArray(entries.map { entry ->
            when (entry) {
                is PayloadEntry.Obj -> entry.fields
                is PayloadEntry.Uninterpretable -> entry.raw
            }
        })
        require(scanTree(array) is Scan.Ok) {
            "payload entries must hold values JSON can express, nested no deeper than $maxDepth"
        }
        val text = storable(array.toString())
        val bytes = text.toByteArray(Charsets.UTF_8).size
        return if (bytes > maxPayloadBytes) PayloadWrite.TooLarge(bytes, maxPayloadBytes) else PayloadWrite.Encoded(text)
    }

    companion object {

        private val BARE_LITERALS = setOf("true", "false", "null")

        /** JSON's number grammar. `01`, `+1`, `.5`, `1.` and `NaN` are outside it on purpose. */
        private val NUMBER = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")

        /**
         * 65,536 UTF-8 bytes per payload array.
         *
         * One obligation of each kind comes to roughly 1.3 KiB across all four payloads, so this is
         * about fifty times the normal load on a single one — a number chosen to catch runaway
         * accumulation, not to bound capacity. With all four at the limit the record's payloads come
         * to 256 KiB, which leaves room under a whole-record limit for the journal, the other keys
         * and the serialization overhead. Measuring the real cost of a filled payload is the
         * serializer slice's, and the figure above is an estimate rather than a measurement.
         */
        const val DEFAULT_MAX_PAYLOAD_BYTES: Int = 65_536

        /**
         * 64 levels of nesting.
         *
         * An obligation is a flat record — a value inside an object inside the array is three — so
         * this is far above anything the format needs and far below where writing one falls over.
         * Measured on this build's test JVM before the limit existed: `[{"a":` ×2,000 is 12,003 bytes,
         * comfortably inside the size limit, and `JsonElement.toString` overflows the stack on it; 100
         * and 500 did not. The limit is not derived from that number — where a stack runs out depends
         * on the thread it runs on, which this has not measured.
         */
        const val DEFAULT_MAX_DEPTH: Int = 64

        /**
         * Strict on purpose, and not the injected `StorageJson`.
         *
         * `StorageJson` has `ignoreUnknownKeys`, `coerceInputValues` and `isLenient` on, which is
         * right for a network body and wrong here: each of them turns damage into a plausible value
         * quietly, and the whole point of this record is that damage stays visible. Only `isLenient`
         * reaches an untyped tree read, and even it is not enough — hence the checks in [decode]. The
         * other two decide how a *declared* type is filled and nothing here declares one, so they are
         * spelled out for the typed reader that comes next rather than for this one.
         */
        private val JSON = Json {
            ignoreUnknownKeys = false
            coerceInputValues = false
            isLenient = false
            explicitNulls = true
        }
    }
}
