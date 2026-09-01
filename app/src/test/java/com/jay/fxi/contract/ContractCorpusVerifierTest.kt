package com.jay.fxi.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractCorpusVerifierTest {
    private val resources by lazy { ContractResourceLoader.load() }
    private val verified by lazy { ContractCorpusVerifier.verify(resources) }

    @Test
    fun `checked corpus has complete identity and expected coverage`() {
        val corpus = verified

        assertEquals(1, corpus.manifest.schemaVersion)
        assertEquals("exchange-rate", corpus.manifest.server.repository)
        assertFalse(corpus.manifest.server.contractTreeDirty)
        assertEquals(97, corpus.manifest.fixtures.size)
        assertEquals(16, corpus.manifest.fixtures.count { it.family == ContractFamily.FREE_SNAPSHOT })
        assertTrue(corpus.manifest.fixtures.any { it.origin == ContractOrigin.ROUTE })
        assertTrue(corpus.manifest.fixtures.any {
            it.runtimeReachability == ContractReachability.VOCABULARY_ONLY
        })
        assertEquals(corpus.manifest.fixtures.size, corpus.entriesById.size)
    }

    @Test
    fun `strict manifest rejects unknown fields`() {
        val text = resources.manifestBytes.toString(Charsets.UTF_8)
        val changed = text.replaceFirst("{", "{\n  \"unexpected\": true,")

        assertThrows(SerializationException::class.java) {
            ContractCorpusVerifier.verify(resources.copy(manifestBytes = changed.toByteArray()))
        }
    }

    @Test
    fun `duplicate scenario id is a real red path`() {
        val manifest = verified.manifest
        val fixtures = manifest.fixtures.toMutableList()
        fixtures[1] = fixtures[1].copy(id = fixtures[0].id)

        assertInvalid(manifest.copy(fixtures = fixtures), "duplicate fixture ids")
    }

    @Test
    fun `duplicate fixture path is a real red path`() {
        val manifest = verified.manifest
        val fixtures = manifest.fixtures.toMutableList()
        fixtures[1] = fixtures[1].copy(path = fixtures[0].path)

        assertInvalid(manifest.copy(fixtures = fixtures), "duplicate fixture paths")
    }

    @Test
    fun `unsafe fixture path is a real red path`() {
        val manifest = verified.manifest
        val fixtures = manifest.fixtures.toMutableList()
        fixtures[0] = fixtures[0].copy(path = "../escape.json")

        assertInvalid(manifest.copy(fixtures = fixtures), "unsafe fixture path")
    }

    @Test
    fun `manifest entry without a file is a real red path`() {
        val removed = resources.fixtureBytesByPath.keys.first()
        val changed = resources.copy(
            fixtureBytesByPath = resources.fixtureBytesByPath - removed
        )

        assertInvalid(changed, "manifest/resource mismatch")
    }

    @Test
    fun `orphan file without a manifest entry is a real red path`() {
        val changed = resources.copy(
            fixtureBytesByPath = resources.fixtureBytesByPath +
                ("orphan.json" to "{}\n".toByteArray())
        )

        assertInvalid(changed, "manifest/resource mismatch")
    }

    @Test
    fun `content hash mismatch is a real red path`() {
        val path = resources.fixtureBytesByPath.keys.first()
        val changed = resources.copy(
            fixtureBytesByPath = resources.fixtureBytesByPath +
                (path to "tampered\n".toByteArray())
        )

        assertInvalid(changed, "fixture hash mismatch")
    }

    @Test
    fun `route origin requires positive route traversal evidence`() {
        val manifest = verified.manifest
        val fixtures = manifest.fixtures.toMutableList()
        val index = fixtures.indexOfFirst { it.origin == ContractOrigin.ROUTE }
        val entry = fixtures[index]
        fixtures[index] = entry.copy(
            source = JsonObject(entry.source + ("routeTraversed" to JsonPrimitive(false)))
        )

        assertInvalid(manifest.copy(fixtures = fixtures), "route origin did not traverse route")
    }

    @Test
    fun `route origin requires typed nonempty HTTP evidence`() {
        val manifest = verified.manifest
        val fixtures = manifest.fixtures.toMutableList()
        val index = fixtures.indexOfFirst { it.origin == ContractOrigin.ROUTE }
        val entry = fixtures[index]
        fixtures[index] = entry.copy(
            source = JsonObject(entry.source + ("http" to JsonObject(emptyMap())))
        )

        assertInvalid(manifest.copy(fixtures = fixtures), "HTTP evidence has invalid method")
    }

    @Test
    fun `adversarial fixture cannot derive from itself`() {
        val manifest = verified.manifest
        val fixtures = manifest.fixtures.toMutableList()
        val index = fixtures.indexOfFirst { it.origin == ContractOrigin.ADVERSARIAL }
        val entry = fixtures[index]
        fixtures[index] = entry.copy(derivedFrom = entry.id)

        assertInvalid(manifest.copy(fixtures = fixtures), "cannot derive from itself")
    }

    @Test
    fun `adversarial fixture cannot derive from another mutation`() {
        val manifest = verified.manifest
        val fixtures = manifest.fixtures.toMutableList()
        val indexes = fixtures.indices.filter {
            fixtures[it].origin == ContractOrigin.ADVERSARIAL
        }
        val entry = fixtures[indexes[0]]
        fixtures[indexes[0]] = entry.copy(derivedFrom = fixtures[indexes[1]].id)

        assertInvalid(manifest.copy(fixtures = fixtures), "must derive from a valid golden")
    }

    private fun assertInvalid(manifest: ContractManifest, message: String) {
        val bytes = ContractManifestJson.encodeToString(manifest).toByteArray()
        assertInvalid(resources.copy(manifestBytes = bytes), message)
    }

    private fun assertInvalid(resources: ContractResourceSet, message: String) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ContractCorpusVerifier.verify(resources)
        }
        assertTrue(error.message.orEmpty().contains(message))
    }
}
