package com.jay.fxi.data.local

import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.NewsContentType
import com.jay.fxi.domain.model.NewsItem
import java.io.File
import java.security.MessageDigest
import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class S102NewsCacheCompatTest {
    private val fixtureDir = File("src/androidTest/assets/fixtures/v122")
    private val fixtureFile = File(fixtureDir, "news_cache.json")
    private val legacyBytes = fixtureFile.readBytes()
    private val legacy = legacyBytes.toString(Charsets.UTF_8)
    private val storageJson = NetworkModule.provideStorageJson()

    // Taken from the plan's input table, independent of the cache decoder.
    private val expected = listOf(
        article("n1", 6, null, title = "환율 📰 \"서울\" \\ 경로\n둘째 줄"),
        article("n2", 5, "https://fx.kbstar.com/a"),
        article("n3", 4, "https://Fx.KbStar.CoM/b"),
        article("n4", 3, "https://rreport.einfomax.co.kr/a.pdf", type = "report_pdf"),
        article("n5", 2, "https://RReport.EInfoMax.Co.Kr/b.pdf", type = "report_pdf"),
        article("n6", 1, "https://example.org/fx.kbstar.com?q=rreport.einfomax.co.kr", type = "future_kind")
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun originalFixtureMatchesProvenanceAndRestoresAllSixArticles() {
        val digest = MessageDigest.getInstance("SHA-256").digest(legacyBytes).joinToString("") { "%02x".format(it) }
        assertEquals("legacy fixture SHA-256", LEGACY_SHA256, digest)
        assertEquals("legacy fixture size", 993, legacyBytes.size)

        val manifest = Json.parseToJsonElement(File(fixtureDir, "news_cache.provenance.json").readText()).jsonObject
        val output = manifest.getValue("output").jsonObject
        assertEquals(fixtureFile.name, output.getValue("name").jsonPrimitive.content)
        assertEquals(legacyBytes.size, output.getValue("bytes").jsonPrimitive.int)
        assertEquals(LEGACY_SHA256, output.getValue("sha256").jsonPrimitive.content)

        val observed = manifest.getValue("configuration").jsonObject
        val configuration = storageJson.configuration
        val current = mapOf(
            "ignoreUnknownKeys" to configuration.ignoreUnknownKeys,
            "coerceInputValues" to configuration.coerceInputValues,
            "isLenient" to configuration.isLenient,
            "explicitNulls" to configuration.explicitNulls,
            "encodeDefaults" to configuration.encodeDefaults
        )
        for ((key, value) in current) {
            assertEquals("storage Json $key", observed.getValue(key).jsonPrimitive.boolean, value)
        }

        val rawItems = Json.parseToJsonElement(legacy).jsonArray
        val first = rawItems.first().jsonObject
        assertFalse("legacy writer omitted n1.link", first.containsKey("link"))
        assertTrue("legacy title escapes", legacy.contains("""환율 📰 \"서울\" \\ 경로\n둘째 줄"""))
        val inputs = JsonArray(listOf(JsonObject(first + ("link" to JsonNull))) + rawItems.drop(1))
        assertEquals("manifest input table", manifest.getValue("inputs").jsonArray, inputs)

        val decoded = decode(legacy)
        assertEquals("legacy articles in storage order", expected, decoded)
        assertNull("unknown content_type stays unresolved", decoded.last().resolvedContentType)
        assertEquals(
            "resolved PDF content_type",
            listOf(NewsContentType.REPORT_PDF, NewsContentType.REPORT_PDF),
            decoded.subList(3, 5).map { it.resolvedContentType }
        )
        assertEquals("PDF external-open branch", listOf("n4", "n5"), decoded.filter { it.shouldOpenExternally }.map { it.id })
    }

    @Test
    fun explicitNullLinkRestoresTheSameArticles() {
        val input = changed(""""id":"n1",""", """"id":"n1","link":null,""")
        assertEquals("explicit null link", expected, decode(input))
    }

    @Test
    fun unknownObjectPropertyPreservesKnownFieldsAndOrder() {
        val input = changed(""""id":"n4",""", """"id":"n4","future_meta":{"edition":2},""")
        val decoded = try {
            decode(input)
        } catch (e: SerializationException) {
            throw AssertionError("S10-2 storage decoder rejected future_meta", e)
        }
        assertEquals("known fields with future_meta", expected, decoded)
    }

    @Test
    fun missingRequiredIdRejectsTheEntireInput() {
        val input = changed(""""id":"n3",""", "")
        assertThrows(SerializationException::class.java) { decode(input) }
    }

    @Test
    fun invalidPublishedAtRejectsTheEntireInput() {
        val input = changed("2025-01-02T03:04:03.123Z", "not-an-instant")
        assertThrows(IllegalArgumentException::class.java) { decode(input) }
    }

    @Test
    fun missingClosingArrayBracketRejectsTheEntireInput() {
        assertThrows(SerializationException::class.java) { decode(legacy.dropLast(1)) }
    }

    @Test
    fun zeroBytesAreRejected() {
        assertThrows(SerializationException::class.java) { decode("") }
    }

    @Test
    fun topLevelObjectIsRejected() {
        assertThrows(SerializationException::class.java) { decode("""{"news":$legacy}""") }
    }

    @Test
    fun emptyArrayDecodesAsAnEmptyList() {
        assertEquals(emptyList<NewsItem>(), decode("[]"))
    }

    private fun decode(input: String): List<NewsItem> = storageJson.decodeFromString(input)

    private fun changed(old: String, new: String): String {
        check(old in legacy) { "Missing fixture fragment: $old" }
        return legacy.replaceFirst(old, new)
    }

    private fun article(
        id: String,
        second: Int,
        link: String?,
        type: String = "external_link",
        title: String = id
    ) = NewsItem(
        id = id,
        title = title,
        link = link,
        source = "합성통신",
        contentType = type,
        publishedAt = Instant.parse("2025-01-02T03:04:0$second.123Z")
    )

    private companion object {
        const val LEGACY_SHA256 = "8d41534c682d52f76e38c2c943f224f201c3521212851d264aedf0129d87e39b"
    }
}
