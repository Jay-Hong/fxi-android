package com.jay.fxi.contract

import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class ContractResourceLoaderTest {
    @Test
    fun `file classpath resources are enumerated from the manifest anchor`() {
        val root = Files.createTempDirectory("contract-file-loader")
        writeFileTree(root)

        URLClassLoader(arrayOf(root.toUri().toURL()), null).use { loader ->
            val loaded = ContractResourceLoader.load(loader)
            assertArrayEquals("{}\n".toByteArray(), loaded.manifestBytes)
            assertEquals(setOf("sample.json"), loaded.fixtureBytesByPath.keys)
        }
    }

    @Test
    fun `jar classpath resources do not require directory entries`() {
        val jarPath = Files.createTempFile("contract-loader", ".jar")
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            jar.entry("contracts/v2/MANIFEST.json", "{}\n".toByteArray())
            jar.entry("contracts/v2/nested/sample.json", "{\"ok\":true}\n".toByteArray())
        }

        URLClassLoader(arrayOf(jarPath.toUri().toURL()), null).use { loader ->
            val loaded = ContractResourceLoader.load(loader)
            assertEquals(setOf("nested/sample.json"), loaded.fixtureBytesByPath.keys)
        }
    }

    @Test
    fun `multiple manifest anchors fail instead of shadowing`() {
        val first = Files.createTempDirectory("contract-shadow-a")
        val second = Files.createTempDirectory("contract-shadow-b")
        writeFileTree(first)
        writeFileTree(second)

        URLClassLoader(arrayOf(first.toUri().toURL(), second.toUri().toURL()), null).use { loader ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                ContractResourceLoader.load(loader)
            }
            assertTrue(error.message.orEmpty().contains("expected exactly one"))
        }
    }

    @Test
    fun `symlinked fixture is rejected`() {
        val root = Files.createTempDirectory("contract-symlink")
        writeFileTree(root)
        val outside = Files.createTempFile("contract-outside", ".json")
        val link = root.resolve("contracts/v2/link.json")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (error: UnsupportedOperationException) {
            assumeNoException(error)
        }

        URLClassLoader(arrayOf(root.toUri().toURL()), null).use { loader ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                ContractResourceLoader.load(loader)
            }
            assertTrue(error.message.orEmpty().contains("symlink"))
        }
    }

    private fun writeFileTree(root: java.nio.file.Path) {
        val contract = root.resolve("contracts/v2")
        Files.createDirectories(contract)
        Files.write(contract.resolve("MANIFEST.json"), "{}\n".toByteArray())
        Files.write(contract.resolve("sample.json"), "{\"ok\":true}\n".toByteArray())
    }

    private fun JarOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(JarEntry(name))
        write(bytes)
        closeEntry()
    }
}
