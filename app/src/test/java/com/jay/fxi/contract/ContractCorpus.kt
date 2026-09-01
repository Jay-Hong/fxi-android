package com.jay.fxi.contract

import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal const val CONTRACT_ROOT = "contracts/v2"
internal const val CONTRACT_MANIFEST_RESOURCE = "$CONTRACT_ROOT/MANIFEST.json"

internal val ContractManifestJson = Json {
    ignoreUnknownKeys = false
    coerceInputValues = false
    isLenient = false
    explicitNulls = true
}

@Serializable
internal data class ContractManifest(
    val schemaVersion: Int,
    val fixtureAsOf: String,
    val server: ContractServerIdentity,
    val fixtures: List<ContractFixtureEntry>
)

@Serializable
internal data class ContractServerIdentity(
    val repository: String,
    val revision: String,
    val dependencyLock: String,
    val dependencyLockSha256: String,
    val contractTreeDirty: Boolean
)

@Serializable
internal data class ContractFixtureEntry(
    val id: String,
    val path: String,
    val family: ContractFamily,
    val mediaType: String,
    val origin: ContractOrigin,
    val producer: String,
    val runtimeReachability: ContractReachability,
    val config: ContractConfigAxes,
    val source: JsonObject,
    val sha256: String,
    val derivedFrom: String? = null,
    val mutation: String? = null
)

@Serializable
internal data class ContractConfigAxes(
    val authStage: ContractAuthStage,
    val g1: ContractToggle,
    val g2: ContractToggle,
    val g3: ContractToggle,
    val premium: ContractPremium
)

@Serializable
internal enum class ContractFamily {
    @SerialName("adversarial") ADVERSARIAL,
    @SerialName("alerts") ALERTS,
    @SerialName("fcm") FCM,
    @SerialName("free-snapshot") FREE_SNAPSHOT,
    @SerialName("graph-v2") GRAPH_V2,
    @SerialName("http") HTTP,
    @SerialName("registry") REGISTRY,
    @SerialName("topic") TOPIC
}

@Serializable
internal enum class ContractOrigin {
    @SerialName("adversarial") ADVERSARIAL,
    @SerialName("builder") BUILDER,
    @SerialName("external") EXTERNAL,
    @SerialName("pydantic-schema") PYDANTIC_SCHEMA,
    @SerialName("registry") REGISTRY,
    @SerialName("route") ROUTE,
    @SerialName("websocket-dispatcher") WEBSOCKET_DISPATCHER
}

@Serializable
internal enum class ContractReachability {
    @SerialName("configured") CONFIGURED,
    @SerialName("external") EXTERNAL,
    @SerialName("invalid-input") INVALID_INPUT,
    @SerialName("runtime") RUNTIME,
    @SerialName("vocabulary-only") VOCABULARY_ONLY
}

@Serializable
internal enum class ContractAuthStage {
    @SerialName("not-applicable") NOT_APPLICABLE,
    @SerialName("compatibility") COMPATIBILITY,
    @SerialName("enforce_authenticated_premium") ENFORCE_AUTHENTICATED_PREMIUM
}

@Serializable
internal enum class ContractToggle {
    @SerialName("not-applicable") NOT_APPLICABLE,
    @SerialName("off") OFF,
    @SerialName("on") ON
}

@Serializable
internal enum class ContractPremium {
    @SerialName("not-applicable") NOT_APPLICABLE,
    @SerialName("active") ACTIVE,
    @SerialName("inactive") INACTIVE,
    @SerialName("pending") PENDING
}

internal data class ContractResourceSet(
    val manifestBytes: ByteArray,
    val fixtureBytesByPath: Map<String, ByteArray>
)

internal object ContractResourceLoader {
    fun load(classLoader: ClassLoader = requireNotNull(javaClass.classLoader)): ContractResourceSet {
        val anchors = classLoader.getResources(CONTRACT_MANIFEST_RESOURCE).toList()
        require(anchors.size == 1) {
            "expected exactly one $CONTRACT_MANIFEST_RESOURCE, found ${anchors.size}: $anchors"
        }
        return when (anchors.single().protocol) {
            "file" -> loadFileTree(anchors.single())
            "jar" -> loadJarTree(anchors.single())
            else -> error("unsupported contract resource protocol: ${anchors.single().protocol}")
        }
    }

    private fun loadFileTree(anchor: URL): ContractResourceSet {
        val manifestPath = Paths.get(anchor.toURI())
        val root = requireNotNull(manifestPath.parent)
        val files = linkedMapOf<String, ByteArray>()
        Files.walk(root).use { stream ->
            stream.forEach { path ->
                require(!Files.isSymbolicLink(path)) {
                    "contract resource symlink is forbidden: $path"
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    addUnique(files, root.relativize(path).portable(), Files.readAllBytes(path))
                }
            }
        }
        return splitManifest(files)
    }

    private fun loadJarTree(anchor: URL): ContractResourceSet {
        val connection = anchor.openConnection() as? JarURLConnection
            ?: error("jar URL did not produce JarURLConnection: $anchor")
        connection.useCaches = false
        val prefix = "$CONTRACT_ROOT/"
        val files = linkedMapOf<String, ByteArray>()
        connection.jarFile.use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.startsWith(prefix)) {
                    val relative = entry.name.removePrefix(prefix)
                    val bytes = jar.getInputStream(entry).use { it.readAllBytes() }
                    addUnique(files, relative, bytes)
                }
            }
        }
        return splitManifest(files)
    }

    private fun splitManifest(files: MutableMap<String, ByteArray>): ContractResourceSet {
        val manifest = files.remove("MANIFEST.json")
            ?: error("contract manifest anchor disappeared while enumerating resources")
        return ContractResourceSet(manifest, files.toMap())
    }

    private fun addUnique(target: MutableMap<String, ByteArray>, path: String, bytes: ByteArray) {
        require(target.put(path, bytes) == null) {
            "duplicate contract resource path: $path"
        }
    }

    private fun Path.portable(): String = joinToString("/") { it.toString() }
}

internal data class VerifiedContractCorpus(
    val manifest: ContractManifest,
    val entriesById: Map<String, ContractFixtureEntry>,
    val fixtureBytesByPath: Map<String, ByteArray>
) {
    fun entry(id: String): ContractFixtureEntry =
        requireNotNull(entriesById[id]) { "unknown fixture id: $id" }

    fun bytes(id: String): ByteArray =
        requireNotNull(fixtureBytesByPath[entry(id).path]) { "missing fixture bytes: $id" }

    fun text(id: String): String = bytes(id).toString(Charsets.UTF_8)
}

internal object ContractCorpusVerifier {
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private val gitShaPattern = Regex("^[0-9a-f]{40}$")

    fun verify(resources: ContractResourceSet): VerifiedContractCorpus {
        val manifest = ContractManifestJson.decodeFromString<ContractManifest>(
            resources.manifestBytes.toString(Charsets.UTF_8)
        )
        require(manifest.schemaVersion == 1) { "unsupported manifest schema" }
        Instant.parse(manifest.fixtureAsOf)
        require(manifest.server.repository == "exchange-rate") { "wrong source repository" }
        require(gitShaPattern.matches(manifest.server.revision)) { "invalid server revision" }
        require(manifest.server.dependencyLock == "requirements.lock.txt") {
            "unexpected server dependency lock"
        }
        require(sha256Pattern.matches(manifest.server.dependencyLockSha256)) {
            "invalid dependency lock hash"
        }
        require(!manifest.server.contractTreeDirty) {
            "contract corpus was exported from a dirty server contract tree"
        }
        require(manifest.fixtures.isNotEmpty()) { "empty contract corpus" }

        val duplicateIds = manifest.fixtures.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicateIds.isEmpty()) { "duplicate fixture ids: $duplicateIds" }
        val duplicatePaths = manifest.fixtures.groupBy { it.path }.filterValues { it.size > 1 }.keys
        require(duplicatePaths.isEmpty()) { "duplicate fixture paths: $duplicatePaths" }

        val entriesById = manifest.fixtures.associateBy { it.id }
        val manifestPaths = manifest.fixtures.mapTo(mutableSetOf()) { entry ->
            verifyEntry(entry, entriesById)
            entry.path
        }
        val resourcePaths = resources.fixtureBytesByPath.keys
        require(manifestPaths == resourcePaths) {
            val missing = manifestPaths - resourcePaths
            val orphan = resourcePaths - manifestPaths
            "manifest/resource mismatch; missing=$missing orphan=$orphan"
        }

        manifest.fixtures.forEach { entry ->
            val bytes = requireNotNull(resources.fixtureBytesByPath[entry.path])
            require(sha256(bytes) == entry.sha256) { "fixture hash mismatch: ${entry.id}" }
            if (entry.mediaType == "application/json") {
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
            }
        }
        return VerifiedContractCorpus(
            manifest,
            entriesById,
            resources.fixtureBytesByPath
        )
    }

    private fun verifyEntry(
        entry: ContractFixtureEntry,
        entriesById: Map<String, ContractFixtureEntry>
    ) {
        require(entry.id.isNotBlank()) { "blank fixture id" }
        require(isSafeRelativePath(entry.path)) { "unsafe fixture path: ${entry.path}" }
        require(entry.producer.isNotBlank()) { "blank producer: ${entry.id}" }
        require(sha256Pattern.matches(entry.sha256)) { "invalid fixture hash: ${entry.id}" }
        require(entry.mediaType == "application/json" || entry.mediaType == "text/html") {
            "unsupported media type: ${entry.id} ${entry.mediaType}"
        }

        val routeTraversed = entry.source.boolean("routeTraversed")
        if (entry.source["http"] != null) {
            verifyHttpEvidence(entry)
        }
        when (entry.origin) {
            ContractOrigin.ROUTE -> {
                require(routeTraversed == true) { "route origin did not traverse route: ${entry.id}" }
                require(entry.source["http"] is JsonObject) {
                    "route origin lacks HTTP evidence: ${entry.id}"
                }
            }
            ContractOrigin.WEBSOCKET_DISPATCHER -> {
                require(routeTraversed == false) {
                    "dispatcher capture falsely claims route traversal: ${entry.id}"
                }
                require(entry.source.boolean("dispatcherTraversed") == true) {
                    "dispatcher origin lacks dispatcher evidence: ${entry.id}"
                }
            }
            else -> require(routeTraversed == false) {
                "non-route origin falsely claims route traversal: ${entry.id}"
            }
        }

        if (entry.origin == ContractOrigin.ADVERSARIAL) {
            val base = entry.derivedFrom?.let(entriesById::get)
            require(!entry.derivedFrom.isNullOrBlank() && base != null) {
                "adversarial fixture lacks a valid base: ${entry.id}"
            }
            require(entry.derivedFrom != entry.id) {
                "adversarial fixture cannot derive from itself: ${entry.id}"
            }
            require(
                base.origin != ContractOrigin.ADVERSARIAL &&
                    base.family != ContractFamily.ADVERSARIAL &&
                    base.runtimeReachability != ContractReachability.INVALID_INPUT
            ) {
                "adversarial fixture must derive from a valid golden: ${entry.id}"
            }
            require(!entry.mutation.isNullOrBlank()) {
                "adversarial fixture lacks a mutation: ${entry.id}"
            }
        } else {
            require(entry.derivedFrom == null && entry.mutation == null) {
                "non-adversarial fixture carries mutation metadata: ${entry.id}"
            }
        }
        if (entry.origin == ContractOrigin.EXTERNAL) {
            require(entry.source.boolean("bodyExact") == false) {
                "external body must declare that bytes are representative: ${entry.id}"
            }
        }
    }

    private fun verifyHttpEvidence(entry: ContractFixtureEntry) {
        val http = requireNotNull(entry.source["http"] as? JsonObject) {
            "HTTP evidence is not an object: ${entry.id}"
        }
        val method = (http["method"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) {
            "HTTP evidence has invalid method: ${entry.id}"
        }
        val path = (http["path"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
        require(path != null && path.startsWith('/') && !path.any(Char::isWhitespace)) {
            "HTTP evidence has invalid path: ${entry.id}"
        }
        val status = (http["status"] as? JsonPrimitive)?.intOrNull
        require(status != null && status in 100..599) {
            "HTTP evidence has invalid status: ${entry.id}"
        }
        val headers = http["headers"] as? JsonObject
        require(headers != null && headers.all { (name, value) ->
            name.isNotBlank() && value is JsonPrimitive && value.isString
        }) {
            "HTTP evidence has invalid headers: ${entry.id}"
        }
    }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.endsWith('/') || '\\' in path) {
            return false
        }
        return path.split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    private fun JsonObject.boolean(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
