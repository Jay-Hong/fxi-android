package com.jay.fxi.data.entitlements.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The axes this build happens to know. A payload may name one it does not. */
private enum class Axis { USER, KRX }

private val KNOWN = listOf(Axis.USER, Axis.KRX)

/**
 * What one named field says, and what it refuses to say.
 *
 * Every refusal here is checked for its *reason*, not only for being a refusal: a field that answers
 * `Unreadable` for the wrong reason has still lost the thing the caller needed to act on.
 */
class ControlNodeReadTest {

    private fun node(json: String): ControlNode =
        ControlNode.of(Json.parseToJsonElement(json) as JsonObject)

    private fun unreadable(read: FieldRead<*>): FieldRead.Unreadable {
        assertTrue("$read", read is FieldRead.Unreadable)
        return read as FieldRead.Unreadable
    }

    // --- 6. 반입 별칭 ---------------------------------------------------------------------------

    /**
     * The caller's map is the caller's. Handing one in and changing it afterwards — at the top, three
     * levels down, or inside a list — does not change what this node says.
     */
    @Test
    fun `changing the map handed to of does not change the node`() {
        val deep = mutableMapOf<String, JsonElement>("bottom" to JsonPrimitive("first"))
        val list = mutableListOf<JsonElement>(JsonPrimitive("a"))
        val middle = mutableMapOf<String, JsonElement>("deep" to JsonObject(deep))
        val top = mutableMapOf<String, JsonElement>(
            "child" to JsonObject(middle),
            "list" to JsonArray(list),
            "text" to JsonPrimitive("kept")
        )

        val node = ControlNode.of(top)

        top["addedLater"] = JsonPrimitive("late")
        top["text"] = JsonPrimitive("overwritten")
        middle["addedLater"] = JsonPrimitive("late")
        deep["bottom"] = JsonPrimitive("changed")
        list.add(JsonPrimitive("b"))

        assertEquals(setOf("child", "list", "text"), node.names)
        assertEquals(FieldRead.Present("kept"), node.text("text"))
        val child = (node.child("child") as FieldRead.Present).value
        assertEquals(setOf("deep"), child.names)
        val bottom = (child.child("deep") as FieldRead.Present).value
        assertEquals(FieldRead.Present("first"), bottom.text("bottom"))
        assertEquals(
            JsonArray(listOf(JsonPrimitive("a"))),
            unreadable(node.text("list")).found
        )
    }

    /** What a diagnosis is handed is a copy too — reading the same damage twice is not the same object. */
    @Test
    fun `the element reported as unreadable is not the node's own`() {
        val node = node("""{"list":["a"],"child":{"k":"v"}}""")

        val first = unreadable(node.text("list")).found
        val second = unreadable(node.text("list")).found

        assertEquals(first, second)
        assertNotSame(first, second)
        assertNotSame(unreadable(node.text("child")).found, unreadable(node.text("child")).found)
    }

    // --- 8. JSON null 과 키 부재 ---------------------------------------------------------------

    @Test
    fun `a key holding json null is present and null`() {
        assertEquals(FieldRead.Present(null), node("""{"owner":null}""").nullableText("owner"))
    }

    @Test
    fun `a key that is not there is absent`() {
        assertEquals(FieldRead.Absent, node("""{"other":"x"}""").nullableText("owner"))
    }

    /** The field that cannot hold null says so, rather than turning it into one. */
    @Test
    fun `json null under a non-nullable field is unreadable and not absent`() {
        val read = unreadable(node("""{"owner":null}""").text("owner"))

        assertEquals(FieldUnreadable.WRONG_TYPE, read.why)
    }

    // --- 9. "5" 와 5 ----------------------------------------------------------------------------

    @Test
    fun `a number in quotes and a number are not the same field`() {
        assertEquals(FieldRead.Present("5"), node("""{"a":"5"}""").text("a"))
        assertEquals(FieldUnreadable.WRONG_TYPE, unreadable(node("""{"a":5}""").text("a")).why)

        assertEquals(FieldRead.Present(5L), node("""{"a":5}""").integer("a"))
        assertEquals(FieldUnreadable.WRONG_TYPE, unreadable(node("""{"a":"5"}""").integer("a")).why)
    }

    @Test
    fun `true and the word true are not the same field`() {
        assertEquals(FieldRead.Present(true), node("""{"a":true}""").flag("a"))
        assertEquals(FieldRead.Present(false), node("""{"a":false}""").flag("a"))
        assertEquals(FieldUnreadable.WRONG_TYPE, unreadable(node("""{"a":"true"}""").flag("a")).why)
        assertEquals(FieldUnreadable.WRONG_TYPE, unreadable(node("""{"a":1}""").flag("a")).why)
    }

    // --- 10. 일부만 알아본 목록 ------------------------------------------------------------------

    /**
     * The collapse this whole layer exists to avoid: keeping the names it recognised would silently
     * drop whatever the other one stood for, and the caller would act on a smaller obligation.
     *
     * `PurgeJournalCodec.scopesOf` refuses the same way for the same reason. That name is written here
     * rather than in the production KDoc on purpose: a trip-wire in `PurgeJournalCodecTest` reads every
     * file under `src/main/java` for the literal, and a mention there is indistinguishable from a call.
     */
    @Test
    fun `a list only partly recognised is unreadable and not narrowed`() {
        val read = unreadable(node("""{"axes":["USER","SOMETHING_NEW"]}""").enumNames("axes", KNOWN))

        assertEquals(FieldUnreadable.UNKNOWN_VALUE, read.why)
        assertEquals(
            JsonArray(listOf(JsonPrimitive("USER"), JsonPrimitive("SOMETHING_NEW"))),
            read.found
        )
    }

    @Test
    fun `a single enum name this build does not have is unreadable`() {
        val read = unreadable(node("""{"axis":"SOMETHING_NEW"}""").enumName("axis", KNOWN))

        assertEquals(FieldUnreadable.UNKNOWN_VALUE, read.why)
        assertEquals(JsonPrimitive("SOMETHING_NEW"), read.found)
        assertEquals(FieldRead.Present(Axis.KRX), node("""{"axis":"KRX"}""").enumName("axis", KNOWN))
        assertEquals(FieldRead.Absent, node("""{"other":"KRX"}""").enumName("axis", KNOWN))
        assertEquals(FieldUnreadable.WRONG_TYPE, unreadable(node("""{"axis":1}""").enumName("axis", KNOWN)).why)
    }

    // --- 21. 수 경계와 표기 ----------------------------------------------------------------------

    @Test
    fun `a whole number at the edge of the range is read`() {
        assertEquals(FieldRead.Present(Long.MAX_VALUE), node("""{"n":${Long.MAX_VALUE}}""").integer("n"))
        assertEquals(FieldRead.Present(Long.MIN_VALUE), node("""{"n":${Long.MIN_VALUE}}""").integer("n"))
        assertEquals(FieldRead.Present(0L), node("""{"n":-0}""").integer("n"))
    }

    /** One past the edge is damage, not a wrapped or clamped number. */
    @Test
    fun `a whole number past the edge of the range is out of domain`() {
        assertEquals(
            FieldUnreadable.OUT_OF_DOMAIN,
            unreadable(node("""{"n":9223372036854775808}""").integer("n")).why
        )
        assertEquals(
            FieldUnreadable.OUT_OF_DOMAIN,
            unreadable(node("""{"n":-9223372036854775809}""").integer("n")).why
        )
    }

    /** A decimal, an exponent and a leading zero are not whole numbers rounded into one. */
    @Test
    fun `a number that is not written as a whole number is unreadable`() {
        for (spelled in listOf("1.0", "1e3", "1E3", "1.5", "01", "-01")) {
            val read = unreadable(node("""{"n":$spelled}""").integer("n"))

            assertEquals(spelled, FieldUnreadable.WRONG_TYPE, read.why)
            assertEquals(spelled, JsonPrimitive(spelled).content, (read.found as JsonPrimitive).content)
        }
    }

    @Test
    fun `an absent number is absent and not zero`() {
        assertEquals(FieldRead.Absent, node("""{"other":1}""").integer("n"))
        assertEquals(FieldRead.Absent, node("""{"other":1}""").flag("n"))
        assertEquals(FieldRead.Absent, node("""{"other":1}""").text("n"))
        assertEquals(FieldRead.Absent, node("""{"other":1}""").child("n"))
        assertEquals(FieldRead.Absent, node("""{"other":1}""").enumNames("n", KNOWN))
    }

    // --- 22. 목록의 빈 값·중복·원소 타입 ----------------------------------------------------------

    @Test
    fun `an empty list of names is an empty set`() {
        assertEquals(FieldRead.Present(emptySet<Axis>()), node("""{"axes":[]}""").enumNames("axes", KNOWN))
    }

    /** A repeated name would vanish in the conversion to a set, so the list is refused instead. */
    @Test
    fun `a repeated name is not quietly deduplicated`() {
        val read = unreadable(node("""{"axes":["USER","USER"]}""").enumNames("axes", KNOWN))

        assertEquals(FieldUnreadable.OUT_OF_DOMAIN, read.why)
    }

    @Test
    fun `a list holding something that is not a name is unreadable`() {
        assertEquals(
            FieldUnreadable.WRONG_TYPE,
            unreadable(node("""{"axes":[1]}""").enumNames("axes", KNOWN)).why
        )
        assertEquals(
            FieldUnreadable.WRONG_TYPE,
            unreadable(node("""{"axes":["USER",null]}""").enumNames("axes", KNOWN)).why
        )
        assertEquals(
            FieldUnreadable.WRONG_TYPE,
            unreadable(node("""{"axes":["USER",{"k":"v"}]}""").enumNames("axes", KNOWN)).why
        )
        assertEquals(
            FieldUnreadable.WRONG_TYPE,
            unreadable(node("""{"axes":"USER"}""").enumNames("axes", KNOWN)).why
        )
    }

    // --- 자식과 미지 이름 -------------------------------------------------------------------------

    @Test
    fun `a nested object is another node and anything else under that key is not`() {
        val node = node("""{"child":{"k":"v"},"notAChild":[1],"alsoNot":"x"}""")

        assertEquals(setOf("k"), (node.child("child") as FieldRead.Present).value.names)
        assertEquals(FieldUnreadable.WRONG_TYPE, unreadable(node.child("notAChild")).why)
        assertEquals(FieldUnreadable.WRONG_TYPE, unreadable(node.child("alsoNot")).why)
    }

    /**
     * The question the obligation layer asks before deciding an obligation is one it may act on. It is
     * asked of names, not of values, because a name this build has never heard of is exactly the case
     * where it cannot tell what the value was owed for.
     */
    @Test
    fun `a name this build does not account for is visible`() {
        assertTrue(node("""{"known":1,"new":2}""").hasNamesBeyond(setOf("known")))
        assertFalse(node("""{"known":1}""").hasNamesBeyond(setOf("known", "absentIsFine")))
        assertFalse(node("""{}""").hasNamesBeyond(emptySet()))
        assertTrue(node("""{"new":1}""").hasNamesBeyond(emptySet()))
    }

    @Test
    fun `an empty object names nothing and answers absent`() {
        val node = node("""{}""")

        assertEquals(emptySet<String>(), node.names)
        assertEquals(FieldRead.Absent, node.text("anything"))
    }
}
