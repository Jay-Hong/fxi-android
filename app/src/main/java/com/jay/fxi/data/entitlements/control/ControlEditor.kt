package com.jay.fxi.data.entitlements.control

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A value a named key can be given.
 *
 * There is no case that carries a [JsonElement] and none that carries a [ControlNode]. That absence is
 * the point: the envelope kept every field because writing handed back the node that was read, never a
 * tree built from named values. This layer names fields, so the way it keeps that property is to make
 * a `T -> JsonElement` write impossible to spell. [Names] is the one list case, and it carries plain
 * strings — nothing a caller could smuggle structure through. Whether those strings are names this
 * build has is a question for the obligation entry point, not for this type.
 */
sealed interface ControlScalar {
    data class Text(val value: String) : ControlScalar
    data class Integer(val value: Long) : ControlScalar
    data class Flag(val value: Boolean) : ControlScalar
    data class Names(val values: List<String>) : ControlScalar
    data object Null : ControlScalar
}

/**
 * What one step of an edit did.
 *
 * Anything but [EDITED] also settles the whole edit: the top level returns
 * [ControlWriteResult.Rejected] whether or not the caller looked at this value. A step result is a
 * diagnosis, not a decision the caller gets to overrule — a caller that ignores an [ABSENT] and carries
 * on would otherwise publish an obligation missing the very thing it meant to change.
 */
enum class EditStep {

    /** The key now holds the new value. */
    EDITED,

    /** [ControlEditor.descend] found no such key, and did not create one. */
    ABSENT,

    /** The key holds something this step cannot enter or replace without losing what is there. */
    NOT_AN_OBJECT,

    /** The change itself is one this layer will not make. */
    REJECTED
}

/** Why an edit produced nothing. */
enum class ControlWriteFailure {

    /** Some step of the edit was refused, so no part of it was published. */
    INVALID_CHANGE
}

/** An edit's whole outcome. There is no partial success. */
sealed interface ControlWriteResult {
    data class Written(val node: ControlNode) : ControlWriteResult
    data class Rejected(val why: ControlWriteFailure) : ControlWriteResult
}

/**
 * Changes to one node, made against that node and nothing else.
 *
 * Preservation here is structural rather than promised: the editor starts from a copy of the node's own
 * map and replaces single entries in it. Nothing enumerates the keys, so a key this build has no name
 * for is not something the editor could drop even if it tried. The same holds one level down —
 * [descend] merges back the child's map, not a child rebuilt from the fields someone read.
 *
 * What is **not** established by any type here: that this object is only used while its block runs,
 * that the calling thread is the one that made it, or that an obligation is one this build may edit.
 * Those are checked below, at run time, and by the tests. A reference to this object can be carried out
 * of the block — Kotlin has no way to stop that — so it is the use that is refused, not the escape.
 */
class ControlEditor internal constructor(private val fields: MutableMap<String, JsonElement>) {

    private val owner: Thread = Thread.currentThread()
    private var open = true
    private var childRunning = false
    private var failed = false

    /**
     * Give a named key a value.
     *
     * An edit changes a field's value and never its kind. A key holding an object cannot be set at
     * all — whatever the object held would go with it. A key holding a list takes only
     * [ControlScalar.Names], and only when every element it already holds is a JSON string. A key
     * holding a scalar takes only another scalar. A key that was not there may be given anything,
     * because there is nothing under it to lose.
     *
     * The test on an existing list is only that every element is a JSON string — this layer does not
     * know which strings are names this build has, which lists may be empty, or which fields a caller
     * is entitled to change. Those belong to the obligation entry point.
     *
     * A [ControlScalar.Names] list is read here and now, so changing the list afterwards changes
     * nothing.
     */
    fun set(name: String, value: ControlScalar): EditStep {
        guard()
        val existing = fields[name]
        if (existing is JsonObject) return refuse()
        if (existing is JsonArray && !(value is ControlScalar.Names && existing.all { it.isJsonString() })) {
            return refuse()
        }
        if (existing !is JsonArray && existing != null && value is ControlScalar.Names) return refuse()
        fields[name] = when (value) {
            is ControlScalar.Text -> JsonPrimitive(value.value)
            is ControlScalar.Integer -> JsonPrimitive(value.value)
            is ControlScalar.Flag -> JsonPrimitive(value.value)
            is ControlScalar.Names -> JsonArray(value.values.map { JsonPrimitive(it) })
            ControlScalar.Null -> JsonNull
        }
        return EditStep.EDITED
    }

    /**
     * Edit the object under a name, in place.
     *
     * A missing key is [EditStep.ABSENT] and a key holding something else is [EditStep.NOT_AN_OBJECT];
     * neither creates or overwrites anything. Both still settle the whole edit — asking to change a
     * child that is not there is not a thing this layer can do half of.
     *
     * While the block runs, this editor refuses changes: the child's map is merged back under [name]
     * afterwards, so a change made to the parent in the meantime would be either kept or silently
     * overwritten depending on which name it touched.
     */
    fun descend(name: String, change: ControlEditor.() -> Unit): EditStep {
        guard()
        val existing = fields[name] ?: return refuse(EditStep.ABSENT)
        if (existing !is JsonObject) return refuse(EditStep.NOT_AN_OBJECT)

        val child = ControlEditor(LinkedHashMap(existing))
        childRunning = true
        try {
            child.change()
        } catch (t: Throwable) {
            failed = true
            throw t
        } finally {
            child.close()
            childRunning = false
        }
        if (child.failed) return refuse()
        fields[name] = JsonObject(LinkedHashMap(child.fields))
        return EditStep.EDITED
    }

    private fun JsonElement.isJsonString(): Boolean = this is JsonPrimitive && isString

    private fun refuse(step: EditStep = EditStep.REJECTED): EditStep {
        failed = true
        return step
    }

    /**
     * The uses this object refuses. Each is something only this codebase can do — no payload reaches
     * them — so each is a mistake to be surfaced rather than an outcome to be returned. Returning
     * [EditStep.REJECTED] instead would file our own bug under the same heading as a payload this build
     * declines to change.
     *
     * Ownership is checked first so that a thread this editor does not belong to never reads [open],
     * which the owning thread writes without synchronising. Which of the two a call that breaks both
     * rules reports is deliberately not fixed — both say the same thing about the same bug.
     */
    private fun guard() {
        check(owner == Thread.currentThread()) { "an editor belongs to the thread that made it" }
        check(open) { "this editor's block has ended; a change made now would be published by nothing" }
        check(!childRunning) { "a child edit is open under this node; finish it before changing its parent" }
    }

    internal fun close() {
        open = false
    }

    internal fun outcome(): ControlWriteResult =
        if (failed) ControlWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE)
        else ControlWriteResult.Written(ControlNode.of(fields))
}
