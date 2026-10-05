package com.jay.fxi.data.graph

import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.purge.PurgeClassification
import com.jay.fxi.data.entitlements.purge.PurgeRequest
import com.jay.fxi.data.entitlements.purge.PurgeTargetAdapter
import com.jay.fxi.data.entitlements.purge.TargetOutcome
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphPeriod
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Opaque, one-use reservation interpreted only by the store that issued it. */
internal class GraphV2WriteTicket internal constructor()

internal sealed interface GraphV2WriteReservation {
    data class Reserved(val ticket: GraphV2WriteTicket) : GraphV2WriteReservation
    data class Rejected(val reason: String) : GraphV2WriteReservation
}

/** A memory-only check of the caller's captured authority; the store issues no authority. */
internal fun interface GraphV2IoAdmission {
    fun admits(component: GraphV2DiskComponent): Boolean
}

internal sealed interface GraphV2DiskRead<out T> {
    data class Found<T>(val envelope: T) : GraphV2DiskRead<T>
    data object Absent : GraphV2DiskRead<Nothing>
    data class Rejected(val reason: String) : GraphV2DiskRead<Nothing>
    data class Failed(val cause: Throwable) : GraphV2DiskRead<Nothing>
}

internal sealed interface GraphV2ComponentWriteOutcome {
    data object Replaced : GraphV2ComponentWriteOutcome
    data class Skipped(val reason: String) : GraphV2ComponentWriteOutcome
    data class Failed(val cause: Throwable) : GraphV2ComponentWriteOutcome
}

internal data class GraphV2WriteReport(
    val general: GraphV2ComponentWriteOutcome,
    val krx: GraphV2ComponentWriteOutcome?
)

internal interface GraphV2DiskStore {
    fun reserveWrite(components: GraphV2DiskComponents): GraphV2WriteReservation
    fun cancelWrite(ticket: GraphV2WriteTicket)
    suspend fun write(ticket: GraphV2WriteTicket, admission: GraphV2IoAdmission): GraphV2WriteReport
    suspend fun readGeneral(
        key: GraphV2GeneralKey,
        catalog: GraphCatalog?,
        admission: GraphV2IoAdmission
    ): GraphV2DiskRead<GraphV2GeneralEnvelope>
    suspend fun readKrx(
        key: GraphV2KrxKey,
        catalog: GraphCatalog?,
        admission: GraphV2IoAdmission
    ): GraphV2DiskRead<GraphV2KrxEnvelope>
    suspend fun purge(component: GraphV2DiskComponent, scope: PurgeScope, namespace: PurgeNamespace): TargetOutcome
}

internal interface GraphV2PreparedReplace

internal interface GraphV2AtomicFileIo {
    fun read(file: File): ByteArray?
    fun prepareReplace(target: File, bytes: ByteArray): GraphV2PreparedReplace
    fun publishReplace(prepared: GraphV2PreparedReplace)
    fun discardReplace(prepared: GraphV2PreparedReplace)
    fun enumerateFiles(root: File): List<File>
    fun deleteIfExists(file: File): Boolean
}

/** Same-directory, synced staging followed by one atomic move; no non-atomic fallback. */
internal class DefaultGraphV2AtomicFileIo : GraphV2AtomicFileIo {
    private data class Prepared(val target: Path, val temporary: Path) : GraphV2PreparedReplace

    override fun read(file: File): ByteArray? = try {
        Files.readAllBytes(file.toPath())
    } catch (_: NoSuchFileException) {
        null
    }

    override fun prepareReplace(target: File, bytes: ByteArray): GraphV2PreparedReplace {
        val path = target.toPath()
        val parent = path.parent ?: throw IOException("Graph target has no parent directory")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".graph-v2-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            return Prepared(path, temporary)
        } catch (thrown: Throwable) {
            try {
                Files.deleteIfExists(temporary)
            } catch (cleanup: Throwable) {
                thrown.addSuppressed(cleanup)
            }
            throw thrown
        }
    }

    override fun publishReplace(prepared: GraphV2PreparedReplace) {
        val p = prepared as Prepared
        Files.move(p.temporary, p.target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun discardReplace(prepared: GraphV2PreparedReplace) {
        Files.deleteIfExists((prepared as Prepared).temporary)
    }

    override fun enumerateFiles(root: File): List<File> {
        val path = root.toPath()
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return emptyList()
        }
        if (!attributes.isDirectory) throw IOException("Graph namespace root is not a directory")
        val found = mutableListOf<File>()
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                found += file.toFile()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc
        })
        return found
    }

    override fun deleteIfExists(file: File): Boolean = Files.deleteIfExists(file.toPath())
}

/** Writer and purge adapters for a root must share this instance. The caller supplies a backup-excluded root. */
internal class FileGraphV2DiskStore(
    private val root: File,
    private val codec: GraphV2EnvelopeCodec,
    private val files: GraphV2AtomicFileIo,
    private val ioDispatcher: CoroutineDispatcher
) : GraphV2DiskStore {
    private data class DiskNamespace(
        val component: GraphV2DiskComponent,
        val uid: String,
        val userEpoch: String,
        val krxEpoch: String? = null
    )

    private data class UserNamespace(val component: GraphV2DiskComponent, val uid: String, val epoch: String)
    private data class CapabilityNamespace(val uid: String, val epoch: String)

    /** Encoded path tokens; a null keep means there is no live namespace to preserve. */
    private data class Sweep(
        val component: GraphV2DiskComponent,
        val scope: PurgeScope,
        val uid: String,
        val keepUser: String?,
        val keepKrx: String?
    ) {
        fun selects(namespace: DiskNamespace): Boolean =
            namespace.component == component && namespace.uid == uid && when (scope) {
                PurgeScope.USER -> namespace.userEpoch != keepUser
                PurgeScope.CAPABILITY -> namespace.userEpoch != keepUser || namespace.krxEpoch != keepKrx
            }
    }

    private class Reservation(val components: GraphV2DiskComponents, val sequence: Long) {
        var claimed = false
        var generalValid = true
        var krxValid = true
    }

    private val stateLock = Any()
    private val mutationMutex = Mutex()
    private var sequence = 0L
    private val reservations = mutableMapOf<GraphV2WriteTicket, Reservation>()
    private val latest = mutableMapOf<GraphV2GeneralKey, Long>()
    private val knownNamespaces = mutableSetOf<DiskNamespace>()
    private val retiredUsers = mutableSetOf<UserNamespace>()
    private val retiredKrx = mutableSetOf<DiskNamespace>()
    private val retiredCapabilities = mutableSetOf<CapabilityNamespace>()
    private val sweeps = mutableMapOf<Triple<GraphV2DiskComponent, PurgeScope, String>, Sweep>()

    override fun reserveWrite(components: GraphV2DiskComponents): GraphV2WriteReservation {
        keyError(components.general.key)?.let { return GraphV2WriteReservation.Rejected(it) }
        components.krx?.let { krx ->
            keyError(krx.key)?.let { return GraphV2WriteReservation.Rejected(it) }
            if (krx.key.generalKey() != components.general.key || krx.responseId != components.general.responseId ||
                krx.component.metadata != components.general.component.metadata
            ) return GraphV2WriteReservation.Rejected("Graph components do not belong to one server response")
        }
        // Snapshot collections so a caller's mutable input cannot change a registered candidate.
        val candidate = components.copy(
            general = components.general.copy(component = components.general.component.snapshot()),
            krx = components.krx?.let { it.copy(component = it.component.snapshot()) }
        )
        return synchronized(stateLock) {
            val namespaces = listOfNotNull(candidate.general.key.namespace(), candidate.krx?.key?.namespace())
            knownNamespaces += namespaces
            if (namespaces.any { isRetired(it) }) {
                GraphV2WriteReservation.Rejected("Graph namespace is retired")
            } else {
                val ticket = GraphV2WriteTicket()
                val next = Math.incrementExact(sequence)
                sequence = next
                latest[candidate.general.key] = next
                reservations[ticket] = Reservation(candidate, next)
                GraphV2WriteReservation.Reserved(ticket)
            }
        }
    }

    override fun cancelWrite(ticket: GraphV2WriteTicket) {
        synchronized(stateLock) { reservations.remove(ticket) }
    }

    override suspend fun write(ticket: GraphV2WriteTicket, admission: GraphV2IoAdmission): GraphV2WriteReport {
        val reservation = synchronized(stateLock) {
            reservations[ticket]?.takeUnless { it.claimed }?.also { it.claimed = true }
        } ?: return GraphV2WriteReport(GraphV2ComponentWriteOutcome.Skipped("Unknown or consumed ticket"), null)
        try {
            return withContext(ioDispatcher) {
                mutationMutex.withLock {
                    val c = reservation.components
                    val general = replace(ticket, reservation, GraphV2DiskComponent.GENERAL, admission,
                        generalFile(c.general.key)) { codec.encodeGeneral(c.general) }
                    val krx = c.krx?.let {
                        if (general != GraphV2ComponentWriteOutcome.Replaced) {
                            GraphV2ComponentWriteOutcome.Skipped("General component was not replaced")
                        } else {
                            replace(ticket, reservation, GraphV2DiskComponent.KRX, admission, krxFile(it.key)) {
                                codec.encodeKrx(it)
                            }
                        }
                    }
                    GraphV2WriteReport(general, krx)
                }
            }
        } finally {
            synchronized(stateLock) { reservations.remove(ticket) }
        }
    }

    private suspend fun replace(
        ticket: GraphV2WriteTicket,
        reservation: Reservation,
        component: GraphV2DiskComponent,
        admission: GraphV2IoAdmission,
        target: File,
        encode: () -> GraphV2Validation<ByteArray>
    ): GraphV2ComponentWriteOutcome {
        return try {
            val bytes = when (val encoded = encode()) {
                is GraphV2Validation.Valid -> encoded.value
                is GraphV2Validation.Invalid -> return GraphV2ComponentWriteOutcome.Skipped(encoded.reason)
            }
            coroutineContext.ensureActive()
            synchronized(stateLock) { skipReason(ticket, reservation, component, admission) }?.let {
                return GraphV2ComponentWriteOutcome.Skipped(it)
            }
            val prepared = files.prepareReplace(target, bytes)
            var primaryFailure: Throwable? = null
            try {
                coroutineContext.ensureActive()
                // Reservation/retirement cannot change between this last check and the atomic publish.
                synchronized(stateLock) {
                    val reason = skipReason(ticket, reservation, component, admission)
                    if (reason != null) GraphV2ComponentWriteOutcome.Skipped(reason) else {
                        files.publishReplace(prepared)
                        GraphV2ComponentWriteOutcome.Replaced
                    }
                }
            } catch (thrown: Throwable) {
                primaryFailure = thrown
                throw thrown
            } finally {
                try {
                    files.discardReplace(prepared)
                } catch (cleanup: Throwable) {
                    val primary = primaryFailure
                    if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Throwable) {
            GraphV2ComponentWriteOutcome.Failed(thrown)
        }
    }

    /** Called only under stateLock. */
    private fun skipReason(
        ticket: GraphV2WriteTicket,
        reservation: Reservation,
        component: GraphV2DiskComponent,
        admission: GraphV2IoAdmission
    ): String? {
        val c = reservation.components
        val namespace = if (component == GraphV2DiskComponent.GENERAL) c.general.key.namespace() else {
            c.krx?.key?.namespace() ?: return "No KRX candidate"
        }
        return when {
            reservations[ticket] !== reservation -> "Ticket was cancelled"
            latest[c.general.key] != reservation.sequence -> "A newer server response was reserved"
            !(if (component == GraphV2DiskComponent.GENERAL) reservation.generalValid else reservation.krxValid) ||
                isRetired(namespace) -> "Graph namespace is retired"
            !admission.admits(component) -> "Graph I/O admission is closed"
            else -> null
        }
    }

    override suspend fun readGeneral(
        key: GraphV2GeneralKey,
        catalog: GraphCatalog?,
        admission: GraphV2IoAdmission
    ): GraphV2DiskRead<GraphV2GeneralEnvelope> {
        keyError(key)?.let { return GraphV2DiskRead.Rejected(it) }
        return read(GraphV2DiskComponent.GENERAL, generalFile(key), admission) { codec.decodeGeneral(it, key, catalog) }
    }

    override suspend fun readKrx(
        key: GraphV2KrxKey,
        catalog: GraphCatalog?,
        admission: GraphV2IoAdmission
    ): GraphV2DiskRead<GraphV2KrxEnvelope> {
        keyError(key)?.let { return GraphV2DiskRead.Rejected(it) }
        return read(GraphV2DiskComponent.KRX, krxFile(key), admission) { codec.decodeKrx(it, key, catalog) }
    }

    private suspend fun <T> read(
        component: GraphV2DiskComponent,
        file: File,
        admission: GraphV2IoAdmission,
        decode: (ByteArray) -> GraphV2Validation<T>
    ): GraphV2DiskRead<T> = withContext(ioDispatcher) {
        mutationMutex.withLock {
            try {
                coroutineContext.ensureActive()
                if (!admission.admits(component)) return@withLock GraphV2DiskRead.Rejected("Graph I/O admission is closed")
                val bytes = files.read(file) ?: return@withLock GraphV2DiskRead.Absent
                when (val decoded = decode(bytes)) {
                    is GraphV2Validation.Valid -> GraphV2DiskRead.Found(decoded.value)
                    is GraphV2Validation.Invalid -> GraphV2DiskRead.Rejected(decoded.reason)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (thrown: Throwable) {
                GraphV2DiskRead.Failed(thrown)
            }
        }
    }

    override suspend fun purge(component: GraphV2DiskComponent, scope: PurgeScope, namespace: PurgeNamespace): TargetOutcome {
        val pending = namespace.pending
        val owner = pending.ownerUid ?: return TargetOutcome.Failed("Graph purge requires an owner UID")
        if (owner.isEmpty() || namespace.ownerUid != owner || scope !in pending.scopes) {
            return TargetOutcome.Failed("Graph purge owner or scope does not match the journal entry")
        }
        val pendingEpoch = if (scope == PurgeScope.USER) pending.userAccessEpoch else pending.krxCapabilityEpoch
        val currentEpoch = if (scope == PurgeScope.USER) namespace.currentUserAccessEpoch else namespace.currentKrxCapabilityEpoch
        if (currentEpoch != null && pendingEpoch == currentEpoch) {
            return TargetOutcome.Failed("Pending graph epoch is the current epoch")
        }
        if (scope == PurgeScope.CAPABILITY && component == GraphV2DiskComponent.GENERAL) return TargetOutcome.NothingToRemove
        val sweep = Sweep(component, scope, hex(owner), namespace.currentUserAccessEpoch?.let(::hex),
            namespace.currentKrxCapabilityEpoch?.let(::hex))
        val sweepKey = Triple(component, scope, sweep.uid)
        // This is deliberately before any dispatcher/Mutex wait, and is never rolled back on failure or cancellation.
        val invalidated = synchronized(stateLock) {
            sweeps[sweepKey] = sweep
            if (scope == PurgeScope.USER) {
                pending.userAccessEpoch?.let { retiredUsers += UserNamespace(component, sweep.uid, hex(it)) }
            } else {
                // A named capability epoch is retired across user epochs, including a capability-only journal entry.
                pending.krxCapabilityEpoch?.let { retiredCapabilities += CapabilityNamespace(sweep.uid, hex(it)) }
            }
            knownNamespaces.filter { sweep.selects(it) }.forEach { retire(it, scope) }
            var changed = false
            reservations.values.forEach { r ->
                val n = if (component == GraphV2DiskComponent.GENERAL) r.components.general.key.namespace()
                    else r.components.krx?.key?.namespace()
                if (n != null && sweep.selects(n)) {
                    if (component == GraphV2DiskComponent.GENERAL) {
                        changed = changed || r.generalValid
                        r.generalValid = false
                    } else {
                        changed = changed || r.krxValid
                        r.krxValid = false
                    }
                }
            }
            changed
        }
        return withContext(ioDispatcher) {
            mutationMutex.withLock {
                try {
                    var removed = invalidated
                    val ownerRoot = File(File(root, component.directory()), sweep.uid)
                    files.enumerateFiles(ownerRoot).forEach { file ->
                        coroutineContext.ensureActive()
                        val n = namespaceOf(file, ownerRoot, component, sweep.uid)
                        val selected = synchronized(stateLock) {
                            // An older queued sweep uses the latest keep rule on its axis.
                            val current = checkNotNull(sweeps[sweepKey])
                            knownNamespaces += n
                            if (current.selects(n)) {
                                retire(n, scope)
                                true
                            } else false
                        }
                        if (selected && files.deleteIfExists(file)) removed = true
                    }
                    if (removed) TargetOutcome.Removed else TargetOutcome.NothingToRemove
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (thrown: Throwable) {
                    TargetOutcome.Failed("Graph namespace purge failed", thrown)
                }
            }
        }
    }

    private fun namespaceOf(file: File, ownerRoot: File, component: GraphV2DiskComponent, uid: String): DiskNamespace {
        val relative = ownerRoot.toPath().toAbsolutePath().normalize().relativize(file.toPath().toAbsolutePath().normalize())
        val parts = relative.map { it.toString() }
        val depth = if (component == GraphV2DiskComponent.GENERAL) 3 else 4
        if (parts.size < depth || parts.any { it == ".." } || !isHex(parts[0]) ||
            (component == GraphV2DiskComponent.KRX && !isHex(parts[1]))
        ) throw IOException("Graph file does not have a valid namespace path")
        return DiskNamespace(component, uid, parts[0], if (component == GraphV2DiskComponent.KRX) parts[1] else null)
    }

    /** State helpers below are called only under stateLock. Concrete retirements survive keep-rule changes. */
    private fun retire(namespace: DiskNamespace, scope: PurgeScope) {
        if (scope == PurgeScope.USER) retiredUsers += UserNamespace(namespace.component, namespace.uid, namespace.userEpoch)
        else retiredKrx += namespace
    }

    private fun isRetired(namespace: DiskNamespace): Boolean =
        UserNamespace(namespace.component, namespace.uid, namespace.userEpoch) in retiredUsers ||
            namespace in retiredKrx ||
            (namespace.component == GraphV2DiskComponent.KRX &&
                namespace.krxEpoch?.let { CapabilityNamespace(namespace.uid, it) in retiredCapabilities } == true)

    private fun GraphV2GeneralKey.namespace() = DiskNamespace(GraphV2DiskComponent.GENERAL, hex(uid), hex(userAccessEpoch))
    private fun GraphV2KrxKey.namespace() = DiskNamespace(GraphV2DiskComponent.KRX, hex(uid), hex(userAccessEpoch), hex(krxCapabilityEpoch))

    private fun generalFile(key: GraphV2GeneralKey) =
        File(root, "general/${hex(key.uid)}/${hex(key.userAccessEpoch)}/${hex(key.tab)}/${key.period}.json")

    private fun krxFile(key: GraphV2KrxKey) =
        File(root, "krx/${hex(key.uid)}/${hex(key.userAccessEpoch)}/${hex(key.krxCapabilityEpoch)}/${hex(key.tab)}/${key.period}.json")
}

/** Prepared for fixture registration only; production wiring is outside B1a-2. */
internal class GraphV2PurgeAdapter(
    private val store: GraphV2DiskStore,
    private val component: GraphV2DiskComponent
) : PurgeTargetAdapter {
    override suspend fun purge(request: PurgeRequest): TargetOutcome {
        val expectedId = if (component == GraphV2DiskComponent.GENERAL) "file:graph_v2_general" else "file:graph_v2_krx"
        val expectedScopes = if (component == GraphV2DiskComponent.GENERAL) setOf(PurgeScope.USER)
            else setOf(PurgeScope.USER, PurgeScope.CAPABILITY)
        if (request.target.id != expectedId || request.target.classification != PurgeClassification.DERIVED_HERE ||
            request.target.scopes != expectedScopes || request.scope !in expectedScopes
        ) return TargetOutcome.Failed("Graph purge adapter received a different target or scope")
        return store.purge(component, request.scope, request.namespace)
    }
}

private fun GraphV2DiskComponent.directory() = if (this == GraphV2DiskComponent.GENERAL) "general" else "krx"
private fun GraphV2KrxKey.generalKey() = GraphV2GeneralKey(uid, userAccessEpoch, tab, period)

private fun keyError(key: GraphV2GeneralKey): String? = when {
    listOf(key.uid, key.userAccessEpoch, key.tab, key.period).any { it.isEmpty() } -> "Incomplete graph key"
    GraphPeriod.fromCode(key.period) == null -> "Unknown graph period"
    else -> null
}

private fun keyError(key: GraphV2KrxKey): String? =
    keyError(key.generalKey()) ?: if (key.krxCapabilityEpoch.isEmpty()) "Empty KRX capability epoch" else null

private fun hex(value: String): String = value.encodeToByteArray().joinToString("") { byte ->
    val n = byte.toInt() and 0xff
    "0123456789abcdef"[n ushr 4].toString() + "0123456789abcdef"[n and 0x0f]
}

private fun isHex(value: String) = value.isNotEmpty() && value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' }

private fun GraphV2StoredComponent.snapshot() = copy(
    series = series.map { indexed -> indexed.copy(series = indexed.series.copy(
        points = indexed.series.points.toList(), perPointMetadata = indexed.series.perPointMetadata.toList()
    )) },
    inProgress = inProgress.toMap()
)
