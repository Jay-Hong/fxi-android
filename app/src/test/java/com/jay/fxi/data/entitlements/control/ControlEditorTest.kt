package com.jay.fxi.data.entitlements.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an edit changes, and everything it must leave alone.
 *
 * The expectations here are written as literal payload text rather than read back with [ControlNode].
 * Reading the result with the same code that produced it would let a reader and a writer be wrong
 * together — the exact failure the envelope's number handling had before M19.
 */
class ControlEditorTest {

    private val codec = ControlPayloadCodec()

    private fun node(json: String): ControlNode =
        ControlNode.of(Json.parseToJsonElement(json) as JsonObject)

    private fun written(result: ControlWriteResult): ControlNode {
        assertTrue("$result", result is ControlWriteResult.Written)
        return (result as ControlWriteResult.Written).node
    }

    /** The one place a node's whole content is put into words, without going through a field reader. */
    private fun text(node: ControlNode): String =
        (codec.encode(listOf(node.toPayloadEntry())) as PayloadWrite.Encoded).text

    private fun rejected(result: ControlWriteResult) {
        assertEquals(ControlWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE), result)
    }

    // --- 4·5. 미접촉 필드의 보존 ------------------------------------------------------------------

    /**
     * The property this whole layer exists for: naming one field must not cost the fields it did not
     * name — including the number literals, which a reader that re-spelled them would quietly change.
     */
    @Test
    fun `changing one field leaves every other key exactly as it was`() {
        val base = node("""{"known":"old","unknown":1e5,"zero":-0,"list":[1,{"x":null}]}""")

        val result = written(base.edited { assertEquals(EditStep.EDITED, set("known", ControlScalar.Text("new"))) })

        assertEquals("""[{"known":"new","unknown":1e5,"zero":-0,"list":[1,{"x":null}]}]""", text(result))
        assertEquals("""[{"known":"old","unknown":1e5,"zero":-0,"list":[1,{"x":null}]}]""", text(base))
    }

    @Test
    fun `changing one field leaves untouched keys three levels down`() {
        val base = node("""{"a":"old","n":{"b":{"c":{"deep":"kept","num":1.50}}}}""")

        val result = written(base.edited { set("a", ControlScalar.Integer(7)) })

        assertEquals("""[{"a":7,"n":{"b":{"c":{"deep":"kept","num":1.50}}}}]""", text(result))
    }

    /** And the same when the edit happens *inside* that nesting. */
    @Test
    fun `changing a field inside a child leaves its siblings and its ancestors alone`() {
        val base = node("""{"top":"kept","n":{"b":{"target":"old","sibling":2e1},"other":"kept"}}""")

        val result = written(
            base.edited {
                assertEquals(EditStep.EDITED, descend("n") {
                    assertEquals(EditStep.EDITED, descend("b") { set("target", ControlScalar.Text("new")) })
                })
            }
        )

        assertEquals(
            """[{"top":"kept","n":{"b":{"target":"new","sibling":2e1},"other":"kept"}}]""",
            text(result)
        )
    }

    /**
     * The ordinary shape of a real change: one nested field and one beside it. The editor has to work
     * again once a child edit closes, at every level.
     *
     * Nothing else here asked an editor to do anything after a `descend` returned, so a mutant that
     * left the child flag raised — making every later change on that editor throw — survived the whole
     * battery. This is that gap.
     */
    @Test
    fun `an editor works again once a child edit has closed`() {
        val base = node("""{"order":1,"guard":{"floor":"old","inner":{"deep":"old"},"unknown":7},"kept":"x"}""")

        val result = written(
            base.edited {
                assertEquals(EditStep.EDITED, descend("guard") {
                    assertEquals(EditStep.EDITED, descend("inner") { set("deep", ControlScalar.Text("newDeep")) })
                    assertEquals(EditStep.EDITED, set("floor", ControlScalar.Text("newFloor")))
                })
                assertEquals(EditStep.EDITED, set("order", ControlScalar.Integer(2)))
            }
        )

        assertEquals(
            """[{"order":2,"guard":{"floor":"newFloor","inner":{"deep":"newDeep"},"unknown":7},"kept":"x"}]""",
            text(result)
        )
    }

    // --- 2·3. 없는 자식과 객체 아닌 자식 -------------------------------------------------------------

    @Test
    fun `descending into a key that is not there makes nothing and settles the edit`() {
        val base = node("""{"a":"1"}""")

        val result = base.edited { assertEquals(EditStep.ABSENT, descend("missing") { set("x", ControlScalar.Flag(true)) }) }

        rejected(result)
        assertEquals("""[{"a":"1"}]""", text(base))
    }

    @Test
    fun `descending into a key that is not an object leaves the value alone`() {
        val base = node("""{"a":[1,2],"b":"text"}""")

        rejected(base.edited { assertEquals(EditStep.NOT_AN_OBJECT, descend("a") { set("x", ControlScalar.Flag(true)) }) })
        rejected(base.edited { assertEquals(EditStep.NOT_AN_OBJECT, descend("b") { set("x", ControlScalar.Flag(true)) }) })
        assertEquals("""[{"a":[1,2],"b":"text"}]""", text(base))
    }

    // --- 14·15. 덮어쓰면 잃는 모양 ------------------------------------------------------------------

    /** A scalar over an object would take the object's whole contents with it. */
    @Test
    fun `an object field is not overwritten by any scalar`() {
        val base = node("""{"obj":{"inside":"kept"}}""")

        for (value in listOf(
            ControlScalar.Null,
            ControlScalar.Text("x"),
            ControlScalar.Integer(1),
            ControlScalar.Flag(true),
            ControlScalar.Names(listOf("x"))
        )) {
            val result = base.edited { assertEquals("$value", EditStep.REJECTED, set("obj", value)) }

            rejected(result)
        }
        assertEquals("""[{"obj":{"inside":"kept"}}]""", text(base))
    }

    /** A list of names can replace a list of strings, and nothing else. */
    @Test
    fun `a list is replaced only by names and only when it held strings`() {
        val ofStrings = node("""{"axes":["KRX"]}""")
        val result = written(ofStrings.edited { set("axes", ControlScalar.Names(listOf("USER", "KRX"))) })
        assertEquals("""[{"axes":["USER","KRX"]}]""", text(result))

        val mixed = node("""{"axes":["USER",{"k":"v"}]}""")
        rejected(mixed.edited { assertEquals(EditStep.REJECTED, set("axes", ControlScalar.Names(listOf("USER")))) })
        assertEquals("""[{"axes":["USER",{"k":"v"}]}]""", text(mixed))

        val ofNumbers = node("""{"axes":[1,2]}""")
        rejected(ofNumbers.edited { set("axes", ControlScalar.Names(listOf("USER"))) })

        rejected(ofStrings.edited { assertEquals(EditStep.REJECTED, set("axes", ControlScalar.Text("USER"))) })
        rejected(ofStrings.edited { set("axes", ControlScalar.Null) })
    }

    /**
     * The rule the three refusals add up to: an edit changes what a field says, never what kind of
     * thing it is. A field's kind is the one thing a reader of another build relies on without asking.
     */
    @Test
    fun `an edit never changes a field's kind`() {
        val scalar = node("""{"a":"text"}""")
        rejected(scalar.edited { assertEquals(EditStep.REJECTED, set("a", ControlScalar.Names(listOf("x")))) })

        val list = node("""{"a":["x"]}""")
        rejected(list.edited { assertEquals(EditStep.REJECTED, set("a", ControlScalar.Text("x"))) })

        val obj = node("""{"a":{"x":1}}""")
        rejected(obj.edited { assertEquals(EditStep.REJECTED, set("a", ControlScalar.Names(listOf("x")))) })

        assertEquals(
            """[{"a":"text","fresh":["x"]}]""",
            text(written(scalar.edited { set("fresh", ControlScalar.Names(listOf("x"))) }))
        )
    }

    /** A scalar field may become a scalar, and a key that was not there may be written. */
    @Test
    fun `an ordinary scalar is replaced and a new key is added`() {
        val base = node("""{"a":"old"}""")

        val result = written(
            base.edited {
                set("a", ControlScalar.Null)
                assertEquals(EditStep.EDITED, set("fresh", ControlScalar.Integer(-3)))
            }
        )

        assertEquals("""[{"a":null,"fresh":-3}]""", text(result))
    }

    // --- 16. 무시된 실패 ------------------------------------------------------------------------

    /**
     * A caller that looks away from a failed step does not get a smaller edit — it gets none. The
     * successful step before it is not published either.
     */
    @Test
    fun `a failed step settles the edit even when its result is ignored`() {
        val base = node("""{"a":"old","c":{"x":1}}""")

        rejected(base.edited { set("a", ControlScalar.Text("new")); descend("missing") {} })
        rejected(base.edited { set("a", ControlScalar.Text("new")); descend("a") {} })
        rejected(base.edited { set("a", ControlScalar.Text("new")); descend("c") { descend("missing") {} } })
        rejected(base.edited { set("a", ControlScalar.Text("new")); set("c", ControlScalar.Text("clobber")) })
        assertEquals("""[{"a":"old","c":{"x":1}}]""", text(base))
    }

    // --- 1·17·18. 수명 --------------------------------------------------------------------------

    @Test
    fun `an editor carried out of its block refuses to be used`() {
        val base = node("""{"a":"old","c":{"x":1}}""")
        var root: ControlEditor? = null
        var child: ControlEditor? = null

        val result = written(base.edited { root = this; descend("c") { child = this } })

        assertThrows(IllegalStateException::class.java) { root!!.set("a", ControlScalar.Text("late")) }
        assertThrows(IllegalStateException::class.java) { child!!.set("x", ControlScalar.Integer(9)) }
        assertEquals("""[{"a":"old","c":{"x":1}}]""", text(base))
        assertEquals("""[{"a":"old","c":{"x":1}}]""", text(result))
    }

    @Test
    fun `an editor whose block ended by throwing refuses to be used too`() {
        val base = node("""{"a":"old"}""")
        var root: ControlEditor? = null

        assertThrows(IllegalStateException::class.java) {
            base.edited { root = this; set("a", ControlScalar.Text("new")); error("boom") }
        }

        assertThrows(IllegalStateException::class.java) { root!!.set("a", ControlScalar.Text("late")) }
        assertEquals("""[{"a":"old"}]""", text(base))
    }

    /** A thread that ends badly must not leave the next edit on it refused. */
    @Test
    fun `an edit is possible again after one ended by throwing`() {
        val base = node("""{"a":"old"}""")

        assertThrows(IllegalStateException::class.java) { base.edited { error("boom") } }

        assertEquals("""[{"a":"new"}]""", text(written(base.edited { set("a", ControlScalar.Text("new")) })))
    }

    @Test
    fun `an edit opened inside another edit is refused`() {
        val outer = node("""{"a":"1"}""")
        val inner = node("""{"b":"2"}""")

        assertThrows(IllegalStateException::class.java) {
            outer.edited { inner.edited { set("b", ControlScalar.Text("3")) } }
        }

        assertEquals("""[{"b":"2"}]""", text(inner))
        assertEquals("""[{"a":"1"}]""", text(outer))
    }

    /**
     * The ancestor is refused while a child edit is open because the child's map is merged back
     * afterwards: a change to the same name would be overwritten and a change to another name kept,
     * which is a difference no caller could see coming.
     */
    @Test
    fun `an ancestor editor is refused while a child edit is open`() {
        val base = node("""{"a":"old","c":{"x":1}}""")
        var thrown: Throwable? = null

        base.edited {
            val parent = this
            descend("c") { thrown = runCatching { parent.set("a", ControlScalar.Text("late")) }.exceptionOrNull() }
        }

        assertTrue("$thrown", thrown is IllegalStateException)
        assertEquals("""[{"a":"old","c":{"x":1}}]""", text(base))
    }

    /** The mistake is reported on the thread that made it; no state crosses between them. */
    @Test
    fun `an editor is refused on a thread that did not make it`() {
        val base = node("""{"a":"old"}""")
        var thrown: Throwable? = null

        base.edited {
            val editor = this
            Thread { thrown = runCatching { editor.set("a", ControlScalar.Text("late")) }.exceptionOrNull() }
                .apply { start() }
                .join()
        }

        assertTrue("$thrown", thrown is IllegalStateException)
        assertEquals("""[{"a":"old"}]""", text(base))
    }

    /** Catching a nested failure does not turn it back into a success. */
    @Test
    fun `an exception caught by the surrounding block still settles the edit`() {
        val base = node("""{"a":"old","c":{"x":1}}""")

        val result = base.edited {
            set("a", ControlScalar.Text("new"))
            runCatching { descend("c") { error("boom") } }
        }

        rejected(result)
        assertEquals("""[{"a":"old","c":{"x":1}}]""", text(base))
    }

    // --- 19·20. 별칭과 무편집 --------------------------------------------------------------------

    /**
     * The list is read at the moment of the call, not at the end of the edit.
     *
     * Changing it *after* `edited` returns would prove less than it looks: the result is deep-copied on
     * the way out, so a `set` that kept the caller's list alive would still look right. Changing it
     * inside the block is what tells a snapshot from a reference.
     */
    @Test
    fun `the list handed to set is read at the call and not later`() {
        val given = mutableListOf("USER")
        val base = node("""{"axes":["KRX"]}""")

        val result = written(
            base.edited {
                set("axes", ControlScalar.Names(given))
                given.add("LATE")
                given[0] = "OVERWRITTEN"
            }
        )
        given.clear()

        assertEquals("""[{"axes":["USER"]}]""", text(result))
    }

    /**
     * What a caller can do to what it was handed, and what that must not reach.
     *
     * `JsonObject` delegates `Map` to the collection it was given, so `keys` is that collection's live
     * view — an ordinary Kotlin cast, not reflection, reaches it. If the entry wrapped this node's own
     * map, removing a key here would take it out of the node.
     */
    @Test
    fun `changing the keys of an exported entry cannot change the node`() {
        val base = node("""{"a":"old","child":{"kept":1,"also":2}}""")

        val entry = base.toPayloadEntry()
        @Suppress("UNCHECKED_CAST")
        (entry.fields.keys as MutableSet<String>).remove("a")
        @Suppress("UNCHECKED_CAST")
        ((entry.fields["child"] as JsonObject).keys as MutableSet<String>).remove("kept")

        assertEquals("""[{"a":"old","child":{"kept":1,"also":2}}]""", text(base))
    }

    /** The same for the names a node reports: a copy, not the keys it is holding. */
    @Test
    fun `changing the names a node reported cannot change the node`() {
        val base = node("""{"a":"old","keep":1e5}""")

        @Suppress("UNCHECKED_CAST")
        (base.names as MutableSet<String>).remove("a")

        assertEquals("""[{"a":"old","keep":1e5}]""", text(base))
        assertEquals(setOf("a", "keep"), base.names)
    }

    @Test
    fun `an entry handed to the envelope is not changed by a later edit`() {
        val base = node("""{"a":"old","keep":1e5}""")

        val entry = base.toPayloadEntry()
        written(base.edited { set("a", ControlScalar.Text("new")) })

        assertEquals("""[{"a":"old","keep":1e5}]""", (codec.encode(listOf(entry)) as PayloadWrite.Encoded).text)
    }

    @Test
    fun `an edit that changes nothing keeps the node's content`() {
        val base = node("""{"a":"old","unknown":{"deep":[1,2]}}""")

        assertEquals("""[{"a":"old","unknown":{"deep":[1,2]}}]""", text(written(base.edited { })))
    }

    /** The block's last expression is not a way to hand back a different node. */
    @Test
    fun `a block ending in another node does not produce that node`() {
        val base = node("""{"a":"old"}""")
        val other = node("""{"b":"other"}""")

        val result = written(base.edited { other })

        assertEquals("""[{"a":"old"}]""", text(result))
    }

    @Test
    fun `the node an edit started from is not the node it produced`() {
        val base = node("""{"a":"old"}""")

        val result = written(base.edited { set("a", ControlScalar.Text("new")) })

        assertEquals("""[{"a":"old"}]""", text(base))
        assertEquals("""[{"a":"new"}]""", text(result))
    }
}
