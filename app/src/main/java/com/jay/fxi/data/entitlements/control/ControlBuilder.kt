package com.jay.fxi.data.entitlements.control

/**
 * Starts empty and shares the editor's thread, nesting, rejection and closing checks. It accepts no
 * existing node. Both build and edit enter through ControlNode.edited, so re-entry is shared too.
 */
class ControlBuilder internal constructor(private val editor: ControlEditor) {
    fun set(name: String, value: ControlScalar): EditStep = editor.set(name, value)

    /** An existing name, even a scalar name, cannot be replaced with an object. */
    fun objectField(name: String, block: ControlBuilder.() -> Unit): EditStep =
        editor.createChild(name, block)
}
