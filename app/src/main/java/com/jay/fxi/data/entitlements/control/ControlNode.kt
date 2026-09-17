package com.jay.fxi.data.entitlements.control

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Why a named field is something this build cannot use. */
enum class FieldUnreadable {

    /** The key holds a different kind of thing — a number where text belongs, an object where a flag does. */
    WRONG_TYPE,

    /** The right kind of thing, spelling something this build has no name for. */
    UNKNOWN_VALUE,

    /** The right kind and spelling, outside what the field can mean — a negative count, an empty id. */
    OUT_OF_DOMAIN
}

/**
 * What one named field says, or why this build cannot use it. There is no fourth answer and no default.
 *
 * A default would be the whole loss this record exists to prevent: a value substituted for damage reads
 * as though the payload said it. [Absent] and `Present(null)` are kept apart for the same reason — a
 * seal's `ownerUid` of null is an owner, not a missing key.
 */
sealed interface FieldRead<out T> {

    data class Present<out T>(val value: T) : FieldRead<T>

    /** No such key. Different from a key holding JSON null. */
    data object Absent : FieldRead<Nothing>

    /**
     * The key is there and this build cannot use it. [found] is a copy, so a diagnosis can say what was
     * there without handing out a reference into the node.
     */
    data class Unreadable(val found: JsonElement, val why: FieldUnreadable) : FieldRead<Nothing>
}

/**
 * One object node of a control payload, read without being rebuilt.
 *
 * The envelope ([ControlPayloadCodec]) could not lose a field because it named none. This layer names
 * them, so it owes the property the envelope got for free: the fields it does not name survive being
 * read, and survive an edit of the ones it does. That is bought here by never handing out the tree and
 * never reconstructing it from named values — see [ControlEditor] for the writing half.
 *
 * What this type does **not** establish: that an editor is only used while its block runs, that a
 * result stays unchanged afterwards, or that an obligation is one this build may edit at all. Those are
 * implementation contracts checked by tests, not shapes the compiler enforces. Saying otherwise is a
 * mistake this design already made once.
 */
class ControlNode private constructor(private val fields: Map<String, JsonElement>) {

    /** The names present, as a copy. A way to check that unnamed keys survived, not the way they survive. */
    val names: Set<String> get() = fields.keys.toSet()

    /**
     * Text, and only text: a number spelled in JSON is [FieldUnreadable.WRONG_TYPE], not a string.
     *
     * Whether a given field may be empty is the obligation's judgement, not this one's — a session id
     * of `""` is damage, a note of `""` is a note. Deciding that here would put every text field under
     * one field's rule.
     */
    fun text(name: String): FieldRead<String> = read(name) { element ->
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) wrongType(element) else FieldRead.Present(primitive.content)
    }

    /**
     * Text that may be JSON null.
     *
     * `Present(null)` is the key saying null; a missing key is [FieldRead.Absent]. Collapsing the two
     * would make a seal owned by null indistinguishable from a seal whose owner was never written.
     */
    fun nullableText(name: String): FieldRead<String?> = read(name) { element ->
        if (element is JsonNull) FieldRead.Present(null) else text(name)
    }

    /**
     * A whole number that fits a `Long`.
     *
     * A decimal, an exponent, a number too large for `Long`, and a number in quotes are all
     * [FieldUnreadable.WRONG_TYPE] or [FieldUnreadable.OUT_OF_DOMAIN] rather than something rounded or
     * truncated into range.
     */
    fun integer(name: String): FieldRead<Long> = read(name) { element ->
        val primitive = element as? JsonPrimitive
        when {
            primitive == null || primitive.isString -> wrongType(element)
            !INTEGER.matches(primitive.content) -> wrongType(element)
            else -> primitive.content.toLongOrNull()
                ?.let { FieldRead.Present(it) }
                ?: FieldRead.Unreadable(copyOf(element), FieldUnreadable.OUT_OF_DOMAIN)
        }
    }

    /** `true` or `false`, and not the strings that spell them. */
    fun flag(name: String): FieldRead<Boolean> = read(name) { element ->
        val primitive = element as? JsonPrimitive
        when {
            primitive == null || primitive.isString -> wrongType(element)
            primitive.content == "true" -> FieldRead.Present(true)
            primitive.content == "false" -> FieldRead.Present(false)
            else -> wrongType(element)
        }
    }

    /** One of [values] by name. A name this build does not have is [FieldUnreadable.UNKNOWN_VALUE]. */
    fun <E : Enum<E>> enumName(name: String, values: List<E>): FieldRead<E> =
        when (val text = text(name)) {
            is FieldRead.Present -> values.firstOrNull { it.name == text.value }
                ?.let { FieldRead.Present(it) }
                ?: FieldRead.Unreadable(copyOf(checkNotNull(fields[name])), FieldUnreadable.UNKNOWN_VALUE)
            is FieldRead.Absent -> FieldRead.Absent
            is FieldRead.Unreadable -> text
        }

    /**
     * Every name in a list, or none of them.
     *
     * A list only partly recognised is not narrowed to the names that were: keeping those would drop
     * whatever the other stood for. The purge journal's scope decoder refuses the same way, and for
     * the same reason. A repeated name is not quietly deduplicated into a smaller set either — the set would say
     * something the list did not, so the list is out of this field's domain rather than narrowed. An
     * empty list is an empty set; whether a given field may be empty is the obligation's judgement.
     */
    fun <E : Enum<E>> enumNames(name: String, values: List<E>): FieldRead<Set<E>> = read(name) { element ->
        val array = element as? JsonArray ?: return@read wrongType(element)
        val spelled = array.map { item ->
            val primitive = item as? JsonPrimitive
            if (primitive == null || !primitive.isString) return@read wrongType(element)
            primitive.content
        }
        if (spelled.size != spelled.toSet().size) {
            return@read FieldRead.Unreadable(copyOf(element), FieldUnreadable.OUT_OF_DOMAIN)
        }
        val found = spelled.map { spelling ->
            values.firstOrNull { it.name == spelling }
                ?: return@read FieldRead.Unreadable(copyOf(element), FieldUnreadable.UNKNOWN_VALUE)
        }
        FieldRead.Present(found.toSet())
    }

    /** A nested object, as another node. Anything else under that key is [FieldUnreadable.WRONG_TYPE]. */
    fun child(name: String): FieldRead<ControlNode> = read(name) { element ->
        if (element is JsonObject) FieldRead.Present(of(element)) else wrongType(element)
    }

    /**
     * Whether anything is here that [known] does not name.
     *
     * The obligation layer asks this to decide `Uninterpretable`: an obligation carrying a field this
     * build cannot account for is not one it may edit or settle, because it cannot tell what the field
     * was owed for.
     */
    fun hasNamesBeyond(known: Set<String>): Boolean = fields.keys.any { it !in known }

    /**
     * Hand this node to the envelope.
     *
     * The result is a deep copy, so nothing that happens to the entry afterwards reaches this node and
     * nothing that happens to this node reaches the entry. Number literals come through as they were
     * read — a `-0` that no edit touched is still `-0`, because this walks the tree rather than
     * rebuilding it from values something read out of it.
     */
    internal fun toPayloadEntry(): PayloadEntry.Obj =
        PayloadEntry.Obj(JsonObject(fields.mapValues { (_, value) -> copyOf(value) }))

    /**
     * Change some named fields and get a new node, or a refusal and no node at all.
     *
     * The edit starts from a copy of this node's own map and replaces single entries in it, so a key
     * this build has no name for is not something the edit could drop. This node is not changed; the
     * result shares no backing with it or with any later edit.
     *
     * This is the mechanism, not the business rule. It does not ask whether the obligation is one this
     * build understands well enough to change, or whether the change is one this build is allowed to
     * make — `ControlObligations` owes both, and calling this directly goes around them.
     */
    internal fun edited(change: ControlEditor.() -> Unit): ControlWriteResult {
        check(editing.get() == null) {
            "an edit is already open on this thread; its result would be discarded by the one around it"
        }
        val editor = ControlEditor(LinkedHashMap(fields))
        editing.set(true)
        return try {
            editor.change()
            editor.outcome()
        } finally {
            editor.close()
            editing.remove()
        }
    }

    private inline fun <T> read(name: String, of: (JsonElement) -> FieldRead<T>): FieldRead<T> {
        val element = fields[name] ?: return FieldRead.Absent
        return of(element)
    }

    private fun wrongType(element: JsonElement) =
        FieldRead.Unreadable(copyOf(element), FieldUnreadable.WRONG_TYPE)

    companion object {

        /**
         * Whether this thread is inside an edit.
         *
         * A nested [edited] would produce a result its caller has no way to use, because a block
         * returns nothing. That is a caller's mistake rather than a way to lose a field — nothing here
         * is what makes preservation true — but it is silent, so it is refused.
         */
        private val editing = ThreadLocal<Boolean>()

        /**
         * The only way to make one, and it copies.
         *
         * `JsonObject` and `JsonArray` wrap the collection they are given rather than taking their own,
         * so a caller that keeps the map can change what this node says after the fact. Copying on the
         * way in — at every depth — is what makes "read without being rebuilt" true of the value and not
         * only of the reference.
         */
        internal fun of(fields: Map<String, JsonElement>): ControlNode =
            ControlNode(fields.mapValues { (_, value) -> copyOf(value) })

        /** JSON's integer grammar. `1.0`, `1e3`, `01` and `"1"` are outside it on purpose. */
        private val INTEGER = Regex("""-?(0|[1-9][0-9]*)""")

        private fun copyOf(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { (_, value) -> copyOf(value) })
            is JsonArray -> JsonArray(element.map(::copyOf))
            is JsonPrimitive -> element
        }
    }
}
