package com.jay.fxi.service

import com.jay.fxi.data.auth.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.purge.KrxMemoryScope
import com.jay.fxi.data.graph.S6KrxGraphRig
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.field
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.test.*
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.*

/** Real checked PAC/session/graph plus the production owner, entry and bus. Fakes are typed I/O only. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class S6PendingAlertTapRig(val test: TestScope, val row: String, val g: S6KrxGraphRig = S6KrxGraphRig(test, row)) {
    val owner = PendingAlertTapOwner()
    val observers = mutableListOf<(AuthIdentityFence?) -> Unit>()
    private var insideAuthCallback = false
    var snapshotReads = 0
    var liveReads = 0
    val fences = AuthFenceStream { callback ->
        val guarded: (AuthIdentityFence?) -> Unit = { value ->
            insideAuthCallback = true
            try { callback(value) } finally { insideAuthCallback = false }
        }
        observers += guarded; guarded(g.r.identity)
    }
    val bus = AlertEventBus(owner, true, { true }, {
        yes("auth-callback", "callback enqueues without PAC reentry", !insideAuthCallback)
        snapshotReads++; g.snapshot
    }, {
        yes("auth-callback", "callback enqueues without live identity read", !insideAuthCallback)
        liveReads++; g.r.identity
    }, g.revisions, fences, g.scope)
    val delivered = mutableListOf<AlertEvent>()
    val carriers = mutableListOf<AlertEvent>()
    val consumers = mutableListOf<AutoCloseable>()
    val jobs = mutableListOf<Job>()
    var localRefreshes = 0
    fun c(column: String, message: String) = "contract($row/$column): $message"
    fun yes(column: String, message: String, value: Boolean) = assertTrue(c(column, message), value)
    fun eq(column: String, message: String, expected: Any?, actual: Any?) = assertEquals(c(column, message), expected, actual)
    fun <T : Any> need(column: String, value: T?, message: String): T = value ?: throw AssertionError(c(column, message))
    suspend fun start(leaseSeconds: Long = 600) = g.start(leaseSeconds = leaseSeconds)
    fun context(snapshot: TopicAccessSnapshot = g.snapshot, identity: AuthIdentityFence? = g.r.identity): AlertTapContext {
        val original = g.owner
        val use = original?.let(snapshot::acquireKrxUse)
        val scope = if (use != null && original != null) KrxMemoryScope(original, use, snapshot.revision,
            snapshot.lastUserEnd?.sequence ?: 0, snapshot.lastCapabilityEnd?.sequence ?: 0) else null
        return AlertTapContext(identity, snapshot.revision, snapshot.lastUserEnd?.sequence ?: 0,
            snapshot.lastCapabilityEnd?.sequence ?: 0, snapshot.facts.recordFence?.userAccessEpoch,
            snapshot.facts.recordFence?.krxCapabilityEpoch, scope)
    }
    @Suppress("UNCHECKED_CAST")
    fun inventory(target: PendingAlertTapOwner = owner): Map<AlertTapTicket, PendingAlertTap> {
        val map = field(target, "pending") as? Map<*, *>
            ?: throw AssertionError("premise: $row owner.pending is the locked immutable-record inventory")
        return (map as Map<AlertTapTicket, PendingAlertTap>).toMap()
    }
    fun owned(ticket: AlertTapTicket, column: String = "owned"): PendingAlertTap =
        need(column, inventory()[ticket], "target is positively owned inside the pending window")
    fun capture(event: AlertEvent? = sourceEvent(), metadata: AlertTapMetadata = source(),
        context: AlertTapContext = context(), classify: Boolean = true, column: String = "capture"): AlertTapTicket {
        val result = owner.capture(event, metadata, context)
        val ticket = need(column, (result as? AlertTapCaptureResult.CAPTURED)?.ticket, "capture transfers actual custody")
        val held = owned(ticket, column)
        eq(column, "immutable raw event", event, held.event)
        eq(column, "immutable metadata", metadata, held.metadata)
        eq(column, "original context captured before classification", context, held.context)
        if (classify) owner.classify(ticket)
        return ticket
    }
    fun report() = owner.reconcile(g.snapshot, g.r.identity)
    fun consume(ticket: AlertTapTicket, receipt: AlertTapReceipt = AlertTapReceipt.HANDLED): AlertTapReceipt =
        owner.consume(ticket, g.snapshot, g.r.identity) { delivered += it; receipt }
    fun bankConsumer(event: AlertEvent): AlertTapReceipt {
        val action = AlertPushRouting.bankAction(event) ?: return AlertTapReceipt.UNHANDLED
        delivered += event
        if (action == BankAlertAction.Refresh) localRefreshes++
        return AlertTapReceipt.HANDLED
    }
    fun collect(action: (AlertEvent) -> AlertTapReceipt = ::bankConsumer): Job {
        val job = test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
            bus.events.collect { event -> carriers += event; bus.consumePendingTap(event, action) }
        }
        jobs += job
        test.runCurrent()
        // Signal ready only after SharedFlow has actually registered this collector.
        yes("collector", "collector is active before ready", (field(bus, "_events") as? kotlinx.coroutines.flow.MutableSharedFlow<*>)?.subscriptionCount?.value?.let { it > 0 } == true)
        consumers += bus.registerPendingTapConsumer()
        return job
    }
    fun audit() = listOf(g.r.h.source.calls.size, g.r.events.count { it.startsWith("auth:") },
        g.session.cleanup.f.calls.size, g.session.cleanup.f.calls.sumOf { it.sends.size })
    fun identity(value: AuthIdentityFence?) {
        g.r.identity = value
        observers.toList().forEach { it(value) }
        test.runCurrent()
    }
    fun intake(context: () -> AlertTapContext = { this.context() }, capture: ((AlertEvent?, AlertTapMetadata, AlertTapContext) -> AlertTapCaptureResult)? = null) =
        AlertTapIntake(context, capture ?: owner::capture, owner::classify) { bus.forwardPendingTap(it) }
    suspend fun close() {
        consumers.forEach { it.close() }; jobs.forEach { it.cancel() }
        g.r.beta?.check?.assertHeld()
        g.close()
    }
    companion object {
        val EXCHANGES = listOf("upbit", "bithumb", "coinone", "korbit", "gopax")
        fun sourceEvent(id: Int = 7) = AlertEvent.SettingTriggered(id, AlertFamily.SOURCE)
        fun comparisonEvent(id: Int = 7) = AlertEvent.SettingTriggered(id, AlertFamily.COMPARISON)
        fun source(source: String = "krx", asset: String = if (source == "krx") "usd-krw-futures" else "usdt-krw") =
            metadata(mapOf("source" to source, "asset" to asset))
        fun comparison(tab: String = "usd", left: String = "investing", right: String = "krx",
            leftAsset: String = if (left == "krx") "usd-krw-futures" else if (tab == "tether") "usdt-krw" else "$tab-krw",
            rightAsset: String = if (right == "krx") "usd-krw-futures" else if (tab == "tether" && right in EXCHANGES) "usdt-krw" else if (tab == "tether") "usd-krw" else "$tab-krw") =
            metadata(mapOf("tab" to tab, "left_source" to left, "left_asset" to leftAsset, "right_source" to right, "right_asset" to rightAsset))
        fun metadata(raw: Map<String, Any?>, custom: String? = null, type: String? = null) = AlertTapMetadata(
            source = raw["source"] as? String, asset = raw["asset"] as? String, tab = raw["tab"] as? String,
            leftSource = raw["left_source"] as? String, leftAsset = raw["left_asset"] as? String,
            rightSource = raw["right_source"] as? String, rightAsset = raw["right_asset"] as? String,
            wrongTypeKeys = raw.filterValues { it != null && it !is String }.keys.toSet(),
            typeConflict = custom != null && type != null && custom != type,
            presentKeys = raw.keys.toSet(), rawType = type, rawCustomType = custom)
        fun raw(m: AlertTapMetadata): Map<String, Any?> = linkedMapOf("source" to m.source, "asset" to m.asset,
            "tab" to m.tab, "left_source" to m.leftSource, "left_asset" to m.leftAsset,
            "right_source" to m.rightSource, "right_asset" to m.rightAsset).filterKeys { it in m.presentKeys }
    }
}

internal class S6TypedTapExtras(raw: Map<String, Any?> = emptyMap()) : AlertTapExtras, AlertTapExtraSink {
    val values = LinkedHashMap(raw)
    val calls = mutableListOf<String>()
    var beforeRead: (() -> Unit)? = null
    override fun kind(key: String): AlertTapExtraKind {
        beforeRead?.invoke(); calls += "kind:$key"
        return if (!values.containsKey(key)) AlertTapExtraKind.MISSING else when (values[key]) {
            null -> AlertTapExtraKind.NULL
            is String -> AlertTapExtraKind.STRING
            is Int -> AlertTapExtraKind.INT
            else -> AlertTapExtraKind.OTHER
        }
    }
    override fun readInt(key: String, default: Int): Int { beforeRead?.invoke(); calls += "int:$key:$default"; return values[key] as? Int ?: default }
    override fun readString(key: String): String? { beforeRead?.invoke(); calls += "string:$key"; return values[key] as? String }
    override fun remove(key: String) { calls += "remove:$key"; values.remove(key) }
    override fun putString(key: String, value: String) { calls += "put:$key"; values[key] = value }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun s63cTest(row: String, vararg columns: String, leaseSeconds: Long = 600, body: suspend TestScope.(S6PendingAlertTapRig, String) -> Unit) {
    for (column in columns.ifEmpty { arrayOf("main") }) {
        try {
            runTest(timeout = 30.seconds) {
                val p = S6PendingAlertTapRig(this, row)
                var primary: Throwable? = null
                try { p.start(leaseSeconds); body(p, column) }
                catch (failure: Throwable) { primary = failure; throw failure }
                finally {
                    try { p.close() } catch (failure: Throwable) { if (primary != null) primary.addSuppressed(failure) else throw failure }
                }
            }
        } catch (failure: Throwable) {
            if (failure is AssertionError && (failure.message.orEmpty().startsWith("contract(") || failure.message.orEmpty().startsWith("premise:"))) throw failure
            throw AssertionError("contract($row/$column): bounded scenario failed: ${failure.message}", failure)
        }
    }
}

/** Wall-clock bounds also apply when a defective synchronous consume never yields to runTest.
 * Daemon workers plus interrupt-aware injected suppliers keep a failed boundary from hanging JVM exit.
 */
private class S63cBoundaryWorkers(val p: S6PendingAlertTapRig, val column: String) : AutoCloseable {
    private val executor = Executors.newFixedThreadPool(3) { task ->
        Thread(task, "s63c-$column").apply { isDaemon = true }
    }
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val tasks = ConcurrentLinkedQueue<Future<*>>()
    fun <T> submit(block: () -> T): Future<T> = executor.submit(Callable { block() }).also { tasks += it }
    fun await(latch: CountDownLatch, message: String, seconds: Long = 3) =
        p.yes(column, message, latch.await(seconds, TimeUnit.SECONDS))
    fun <T> result(task: Future<T>, message: String): T = try { task.get(3, TimeUnit.SECONDS) }
        catch (failure: Exception) { throw AssertionError(p.c(column, "$message: ${failure.javaClass.simpleName}"), failure) }
    override fun close() {
        tasks.filterNot { it.isDone }.forEach { it.cancel(true) }
        executor.shutdownNow()
        p.yes(column, "boundary workers terminate within one second", executor.awaitTermination(1, TimeUnit.SECONDS))
    }
}

private fun s63cCheckWorkerInterruption() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("boundary-input: worker teardown")
}

/** Captures through shared APIs only. Preservation is proved by later receiving this same raw event,
 * without recapture; no pending-map, delivery-state, version or lock observation is used here.
 */
private fun s63cPublicBoundaryTicket(p: S6PendingAlertTapRig, owner: PendingAlertTapOwner,
    event: AlertEvent, context: AlertTapContext, column: String): AlertEvent.PendingTap {
    val captured = owner.capture(event, AlertTapMetadata(), context)
    val ticket = p.need(column, (captured as? AlertTapCaptureResult.CAPTURED)?.ticket, "public capture owns original")
    p.eq(column, "BANK positive classification", AlertTapClassification.NON_KRX, owner.classify(ticket))
    return AlertEvent.PendingTap(ticket)
}

/** F02: two native calls contend on distinct real queued tickets, with immutable access inputs.
 * Supplier rendezvous encourages overlap but times out after 50ms, so an implementation that
 * serializes whole consumer turns is also valid. No particular read count/retry/locking scheme is required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.s63cConcurrentConsumeBoundary(p: S6PendingAlertTapRig) {
    val col = "F02-two-tickets"
    val snapshot = p.g.snapshot; val live = p.g.r.identity; val context = p.context(snapshot, live)
    val owner = PendingAlertTapOwner()
    val contention = AtomicBoolean(true)
    val consumerThread = ThreadLocal.withInitial { false }
    val rendezvous = CyclicBarrier(2)
    val bus = AlertEventBus(owner, true, { true }, {
        s63cCheckWorkerInterruption()
        if (consumerThread.get() && contention.get()) {
            try { rendezvous.await(50, TimeUnit.MILLISECONDS) }
            catch (_: java.util.concurrent.TimeoutException) { contention.set(false) }
            catch (_: java.util.concurrent.BrokenBarrierException) { contention.set(false) }
        }
        snapshot
    }, { s63cCheckWorkerInterruption(); live }, scope = p.g.scope)
    val originals = listOf(AlertEvent.SettingTriggered(9201), AlertEvent.SettingTriggered(9202))
    val carriers = originals.map { s63cPublicBoundaryTicket(p, owner, it, context, col) }
    val queued = ConcurrentLinkedQueue<AlertEvent>()
    val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { bus.events.collect { queued += it } }
    runCurrent()
    val lease = bus.registerPendingTapConsumer(); runCurrent()
    S63cBoundaryWorkers(p, col).use { workers ->
        val start = CountDownLatch(1); val entered = CountDownLatch(2)
        val progressOrReturn = CountDownLatch(1); val finished = AtomicInteger()
        val callbacks = ConcurrentLinkedQueue<AlertEvent>()
        try {
            p.yes(col, "both carriers reached an actual active collector before consumption", carriers.all { it in queued })
            val calls = carriers.mapIndexed { index, carrier -> workers.submit {
                entered.countDown()
                workers.await(start, "both calls are released from the same bounded start gate")
                consumerThread.set(true)
                try {
                    bus.consumePendingTap(carrier) { raw ->
                        p.eq(col, "contending callback keeps its own original", originals[index], raw)
                        callbacks += raw; progressOrReturn.countDown()
                        // A refused action makes no ACK, leaving the raw original available below.
                        AlertTapReceipt.REJECTED
                    }
                } finally {
                    consumerThread.remove()
                    if (finished.incrementAndGet() == 2) progressOrReturn.countDown()
                }
            } }
            workers.await(entered, "two distinct consumer calls are ready concurrently")
            start.countDown()
            workers.await(progressOrReturn, "stable two-ticket contention makes callback progress or both calls return", seconds = 5)
            p.yes(col, "progress/finite-return condition is observed", callbacks.isNotEmpty() || finished.get() == 2)
            contention.set(false); rendezvous.reset()
            calls.forEach { p.eq(col, "no contending refusal ACKs custody", AlertTapReceipt.REJECTED,
                workers.result(it, "consumer returns after the bounded contention window")) }
            p.eq(col, "both originals remain owned after contention", 2, bus.pendingTapReport().retainedNonKrx)
            for ((index, carrier) in carriers.withIndex()) {
                var handled = 0
                p.eq(col, "preserved ticket can be handled without recapture", AlertTapReceipt.HANDLED,
                    bus.consumePendingTap(carrier) { raw ->
                        p.eq(col, "settled delivery keeps the complete original raw value", originals[index], raw)
                        handled++; AlertTapReceipt.HANDLED
                    })
                p.eq(col, "settled original handled exactly once", 1, handled)
                p.eq(col, "late duplicate cannot execute again", AlertTapReceipt.REJECTED,
                    bus.consumePendingTap(carrier) { handled++; AlertTapReceipt.HANDLED })
                p.eq(col, "duplicate callback zero", 1, handled)
            }
            p.eq(col, "only explicit HANDLED removed the two originals", 0, bus.pendingTapReport().retainedNonKrx)
        } finally {
            start.countDown(); contention.set(false); rendezvous.reset()
            collector.cancel(); runCurrent()
            workers.result(workers.submit { lease.close() }, "consumer detach completes during teardown")
        }
    }
}

/** F03: pause the old call at its post-reservation external read, complete close on another worker,
 * then resume it. A new real subscription/registration is the only action allowed to handle the original.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.s63cDetachReadBoundary(p: S6PendingAlertTapRig) {
    val col = "F03-detach-reservation"
    val snapshot = p.g.snapshot; val live = p.g.r.identity; val context = p.context(snapshot, live)
    val owner = PendingAlertTapOwner()
    val oldThread = AtomicReference<Thread?>()
    val armRead = AtomicBoolean(true)
    val reading = CountDownLatch(1); val resume = CountDownLatch(1)
    val bus = AlertEventBus(owner, true, { true }, {
        s63cCheckWorkerInterruption()
        if (Thread.currentThread() === oldThread.get() && armRead.compareAndSet(true, false)) {
            reading.countDown()
            p.yes(col, "paused supplier resumes within its wall-clock bound", resume.await(10, TimeUnit.SECONDS))
        }
        snapshot
    }, { s63cCheckWorkerInterruption(); live }, scope = p.g.scope)
    val original = AlertEvent.SettingTriggered(9301)
    val carrier = s63cPublicBoundaryTicket(p, owner, original, context, col)
    val oldQueue = ConcurrentLinkedQueue<AlertEvent>()
    val oldCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { bus.events.collect { oldQueue += it } }
    runCurrent()
    val oldLease = bus.registerPendingTapConsumer(); runCurrent()
    var newCollector: Job? = null; var newLease: AutoCloseable? = null
    S63cBoundaryWorkers(p, col).use { workers ->
        val oldCallbacks = AtomicInteger()
        try {
            p.yes(col, "old consumer actually received the queued carrier", carrier in oldQueue)
            val oldCall = workers.submit {
                oldThread.set(Thread.currentThread())
                try { bus.consumePendingTap(carrier) { oldCallbacks.incrementAndGet(); AlertTapReceipt.HANDLED } }
                finally { oldThread.set(null) }
            }
            workers.await(reading, "old call reaches fresh supplier before any callback")
            p.eq(col, "preclaim callback zero", 0, oldCallbacks.get())
            workers.result(workers.submit { oldLease.close() }, "old consumer close completes while read is paused")
            p.eq(col, "close is complete before old supplier resumes", 1L, resume.count)
            p.eq(col, "completed detach has executed no callback", 0, oldCallbacks.get())
            oldCollector.cancel(); runCurrent()
            resume.countDown()
            p.eq(col, "detached invocation ends without taking another reservation", AlertTapReceipt.REJECTED,
                workers.result(oldCall, "old consume returns after completed detach"))
            p.eq(col, "old callback remains zero after resumption", 0, oldCallbacks.get())
            p.eq(col, "original remains owned before any new registration", 1, bus.pendingTapReport().retainedNonKrx)
            val freshQueue = mutableListOf<AlertEvent>(); var handled = 0
            newCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                bus.events.collect { fresh ->
                    freshQueue += fresh
                    bus.consumePendingTap(fresh) { raw ->
                        p.eq(col, "ready replay retains the original raw event", original, raw)
                        handled++; AlertTapReceipt.HANDLED
                    }
                }
            }
            runCurrent()
            p.eq(col, "subscription alone has not handled the retained original", 0, handled)
            newLease = bus.registerPendingTapConsumer(); runCurrent()
            p.yes(col, "new ready re-emits the same ticket without recapture", carrier in freshQueue)
            p.eq(col, "fresh registered consumer handles exactly once", 1, handled)
            p.eq(col, "old invocation never calls back after new ready", 0, oldCallbacks.get())
            p.eq(col, "HANDLED removes retained original", 0, bus.pendingTapReport().retainedNonKrx)
            p.eq(col, "late old carrier cannot duplicate handling", AlertTapReceipt.REJECTED,
                bus.consumePendingTap(carrier) { handled++; AlertTapReceipt.HANDLED })
            p.eq(col, "one handling across old/new/duplicate calls", 1, handled)
        } finally {
            armRead.set(false); resume.countDown()
            oldCollector.cancel(); newCollector?.cancel(); runCurrent()
            workers.result(workers.submit { oldLease.close(); newLease?.close() }, "old/new leases close within teardown bound")
        }
    }
}

/** mf02: X's own call must HANDLED under stable access and unrelated Y turns, not merely return.
 * The first-read latches establish X reserve -> Y reserve. At each public X tryLock boundary a
 * complete Y turn finishes before the real acquisition, so a hidden retry cannot evade the input.
 * No owner attempt count, version name, private state, or actual lock contention is used.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.s63cUnrelatedTicketBoundary(p: S6PendingAlertTapRig) {
    val col = "F02-unrelated-turns-own-HANDLED"
    val snapshot = p.g.snapshot; val live = p.g.r.identity; val context = p.context(snapshot, live)
    val xThread = AtomicReference<Thread?>(); val yThread = AtomicReference<Thread?>()
    val beforeCompare = AtomicReference<() -> Unit>({})
    val suppliedLock = object : ReentrantLock() {
        override fun tryLock(): Boolean {
            if (Thread.currentThread() === xThread.get()) beforeCompare.get().invoke()
            return super.tryLock()
        }
    }
    val owner = PendingAlertTapOwner(lock = suppliedLock)
    val xReading = CountDownLatch(1); val yReading = CountDownLatch(1)
    val resumeX = CountDownLatch(1); val resumeY = CountDownLatch(1)
    val xFirstRead = AtomicBoolean(true); val yFirstRead = AtomicBoolean(true)
    val bus = AlertEventBus(owner, true, { true }, {
        s63cCheckWorkerInterruption()
        if (Thread.currentThread() === xThread.get() && xFirstRead.compareAndSet(true, false)) {
            xReading.countDown(); p.yes(col, "X first fresh read resumes within bound", resumeX.await(10, TimeUnit.SECONDS))
        }
        if (Thread.currentThread() === yThread.get() && yFirstRead.compareAndSet(true, false)) {
            yReading.countDown(); p.yes(col, "Y first fresh read resumes within bound", resumeY.await(10, TimeUnit.SECONDS))
        }
        snapshot
    }, { s63cCheckWorkerInterruption(); live }, scope = p.g.scope)
    val originals = listOf(AlertEvent.SettingTriggered(9501), AlertEvent.SettingTriggered(9502))
    val carriers = originals.map { s63cPublicBoundaryTicket(p, owner, it, context, col) }
    val seen = ConcurrentLinkedQueue<AlertEvent>()
    val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { bus.events.collect { seen += it } }
    runCurrent(); val lease = bus.registerPendingTapConsumer(); runCurrent()
    val handled = ConcurrentLinkedQueue<AlertEvent>(); val compared = AtomicInteger()
    val initialY = AtomicReference<Future<AlertTapReceipt>?>()
    S63cBoundaryWorkers(p, col).use { workers ->
        var deadline = 0L
        beforeCompare.set {
            if (deadline == 0L) deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            p.yes(col, "unrelated-turn schedule stays within its wall-clock bound", System.nanoTime() < deadline)
            if (compared.getAndIncrement() == 0) {
                resumeY.countDown()
                p.eq(col, "initial Y reservation completes without ACK", AlertTapReceipt.REJECTED,
                    workers.result(p.need(col, initialY.get(), "Y call installed before X resumes"), "initial Y turn finishes before X comparison"))
            } else {
                p.eq(col, "another unrelated Y turn finishes without ACK", AlertTapReceipt.REJECTED,
                    workers.result(workers.submit {
                        bus.consumePendingTap(carriers[1]) { raw -> p.eq(col, "Y retains its raw original", originals[1], raw); AlertTapReceipt.REJECTED }
                    }, "independent Y turn finishes before X comparison"))
            }
            p.eq(col, "real owner lock is free after Y and before X comparison", false, suppliedLock.isLocked)
        }
        try {
            p.yes(col, "both real carriers are available before the calls", carriers.all { it in seen })
            val xCall = workers.submit {
                xThread.set(Thread.currentThread())
                try { bus.consumePendingTap(carriers[0]) { raw ->
                    p.eq(col, "X own invocation receives its unchanged original", originals[0], raw)
                    handled += raw; AlertTapReceipt.HANDLED
                } } finally { xThread.set(null) }
            }
            workers.await(xReading, "X is reserved and stopped at its first external read")
            initialY.set(workers.submit {
                yThread.set(Thread.currentThread())
                try { bus.consumePendingTap(carriers[1]) { raw ->
                    p.eq(col, "Y refusal preserves its original", originals[1], raw); AlertTapReceipt.REJECTED
                } } finally { yThread.set(null) }
            })
            workers.await(yReading, "Y reserves after X and pauses in its own first external read")
            resumeX.countDown()
            p.eq(col, "X own call HANDLED despite unrelated reservation/release", AlertTapReceipt.HANDLED,
                workers.result(xCall, "X own consume returns HANDLED within bound"))
            p.yes(col, "positive unrelated Y turn occurred before X comparison", compared.get() > 0)
            p.eq(col, "only X has handled so far", listOf(originals[0]), handled.toList())
            p.eq(col, "Y remains owned without recapture", 1, bus.pendingTapReport().retainedNonKrx)
            beforeCompare.set({})
            p.eq(col, "Y then handles normally", AlertTapReceipt.HANDLED, workers.result(workers.submit {
                bus.consumePendingTap(carriers[1]) { raw -> p.eq(col, "Y final raw original", originals[1], raw); handled += raw; AlertTapReceipt.HANDLED }
            }, "Y normal consumption returns within bound"))
            p.eq(col, "each original handles once in X then Y order", originals, handled.toList())
            for (carrier in carriers) p.eq(col, "late duplicate executes zero", AlertTapReceipt.REJECTED,
                workers.result(workers.submit { bus.consumePendingTap(carrier) { handled += it; AlertTapReceipt.HANDLED } }, "late carrier returns"))
            p.eq(col, "duplicate callback zero", originals, handled.toList())
        } finally {
            beforeCompare.set({}); resumeX.countDown(); resumeY.countDown()
            collector.cancel(); runCurrent()
            workers.result(workers.submit { lease.close() }, "unrelated-turn consumer closes within bound")
        }
    }
}

/** F04 positive controls: ineligible/UNHANDLED earlier custody must not block a later BANK. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.s63cFifoExcludedPredecessor(p: S6PendingAlertTapRig, col: String) {
    var restoreLoadFailures: Int? = null
    try {
        val initial = p.g.snapshot; val live = p.g.r.identity; val originalContext = p.context(initial, live)
        val owner = PendingAlertTapOwner(); val publication = AtomicReference(initial)
        val bus = AlertEventBus(owner, true, { true }, { publication.get() }, { live }, scope = p.g.scope)
        fun capture(raw: AlertEvent, metadata: AlertTapMetadata, context: AlertTapContext, classification: AlertTapClassification): AlertEvent.PendingTap {
            val result = owner.capture(raw, metadata, context)
            val ticket = p.need(col, (result as? AlertTapCaptureResult.CAPTURED)?.ticket, "earlier original captured by shared API")
            p.eq(col, "earlier original classification positive", classification, owner.classify(ticket))
            return AlertEvent.PendingTap(ticket)
        }
        val headRaw = if (col == "exclude-CONTEXT_UNKNOWN") AlertEvent.SettingTriggered(9601) else S6PendingAlertTapRig.sourceEvent(9601)
        val headClass = if (col == "exclude-UNKNOWN") AlertTapClassification.UNKNOWN else if (col == "exclude-withheld-KRX") AlertTapClassification.KRX else AlertTapClassification.NON_KRX
        val headMetadata = when (col) {
            "exclude-UNKNOWN", "exclude-CONTEXT_UNKNOWN" -> AlertTapMetadata()
            "exclude-withheld-KRX" -> S6PendingAlertTapRig.source()
            else -> S6PendingAlertTapRig.source("upbit")
        }
        val headContext = if (col == "exclude-CONTEXT_UNKNOWN") originalContext.copy(identity = null, scope = null, userAccessEpoch = null, krxCapabilityEpoch = null) else originalContext
        val head = capture(headRaw, headMetadata, headContext, headClass)
        if (col == "exclude-UNHANDLED") p.eq(col, "earlier consumer records UNHANDLED", AlertTapReceipt.UNHANDLED,
            owner.consume(head.ticket, initial, live) { raw -> p.eq(col, "unhandled original raw", headRaw, raw); AlertTapReceipt.UNHANDLED })
        if (col == "exclude-withheld-KRX") {
            val grant = p.g.r.h.issueGrant()
            val reservation = p.g.r.pac.reserveRejection(grant, KrxHarnessDefaults.KRX_REFUSAL,
                KrxSendMarker(p.need(col, originalContext.krxCapabilityEpoch, "original capability epoch"), initial.krxPermitSeq))
            // Keep the real store unreadable throughout this finite observation window, including
            // immediate candidate-recovery reads. Restore the I/O port in the outer finally.
            restoreLoadFailures = p.g.r.h.store.loadFailures
            p.g.r.h.store.loadFailures = Int.MAX_VALUE
            p.g.r.pac.onTopicRejected(reservation); runCurrent()
            val held = p.g.r.pac.accessSnapshot
            p.yes(col, "actual PAC publication holds a capability loss candidate", TopicAccessBlock.LOSS_CANDIDATE in held.facts.capabilityBlocks)
            p.eq(col, "temporary withholding has no explicit capability end", initial.lastCapabilityEnd, held.lastCapabilityEnd)
            p.eq(col, "original capability epoch is unchanged", initial.facts.recordFence?.krxCapabilityEpoch, held.facts.recordFence?.krxCapabilityEpoch)
            p.yes(col, "original KRX use is actually withheld", originalContext.scope?.let { !held.admitsKrxUse(it.use) } == true)
            p.yes(col, "BANK user access remains allowed", held.facts.userAllowed)
            publication.set(held)
        }
        val bankRaw = AlertEvent.SettingTriggered(9602)
        val bank = s63cPublicBoundaryTicket(p, owner, bankRaw, p.context(publication.get(), live), col)
        val callbacks = mutableListOf<AlertEvent>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { event -> bus.consumePendingTap(event) { callbacks += it; AlertTapReceipt.HANDLED } }
        }
        runCurrent(); val lease = bus.registerPendingTapConsumer(); runCurrent()
        try {
            p.eq(col, "excluded predecessor permits following BANK immediately", listOf(bankRaw), callbacks)
            val report = bus.pendingTapReport()
            p.eq(col, "no capability custody deleted for this positive control", 0, report.removedKrx)
            p.eq(col, "no user custody deleted", 0, report.removedUser)
            when (col) {
                "exclude-UNKNOWN" -> p.eq(col, "earlier UNKNOWN custody remains reported", 1, report.unknownClassification)
                "exclude-CONTEXT_UNKNOWN" -> p.eq(col, "earlier context-unknown custody remains reported", 1, report.unknownContext)
                "exclude-UNHANDLED" -> { p.eq(col, "earlier UNHANDLED remains reported", 1, report.unhandled); p.eq(col, "earlier nonKRX custody remains", 1, report.retainedNonKrx) }
                else -> {
                    p.eq(col, "withheld known original remains classifiable in custody", AlertTapClassification.KRX, owner.classify(head.ticket))
                    p.yes(col, "actual PAC still holds capability throughout BANK handling", TopicAccessBlock.LOSS_CANDIDATE in p.g.r.pac.accessSnapshot.facts.capabilityBlocks)
                    p.eq(col, "no CAP end occurred during BANK handling", initial.lastCapabilityEnd, p.g.r.pac.accessSnapshot.lastCapabilityEnd)
                }
            }
            var forbidden = 0
            p.eq(col, "excluded original cannot accidentally handle", AlertTapReceipt.REJECTED,
                bus.consumePendingTap(head) { forbidden++; AlertTapReceipt.HANDLED })
            p.eq(col, "excluded original callback zero", 0, forbidden)
            p.eq(col, "following BANK late duplicate callback zero", AlertTapReceipt.REJECTED,
                bus.consumePendingTap(bank) { callbacks += it; AlertTapReceipt.HANDLED })
            p.eq(col, "one BANK callback total", listOf(bankRaw), callbacks)
        } finally { lease.close(); collector.cancel(); runCurrent() }
    } finally { restoreLoadFailures?.let { p.g.r.h.store.loadFailures = it } }
}

/** A CLAIMED earlier callback remains a FIFO barrier until its actual receipt returns. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.s63cFifoClaimedPredecessor(p: S6PendingAlertTapRig, unhandled: Boolean) {
    val col = if (unhandled) "CLAIMED-UNHANDLED" else "CLAIMED-HANDLED"
    val snapshot = p.g.snapshot; val live = p.g.r.identity; val context = p.context(snapshot, live)
    val owner = PendingAlertTapOwner()
    val bus = AlertEventBus(owner, true, { true }, { s63cCheckWorkerInterruption(); snapshot }, { live }, scope = p.g.scope)
    val originals = listOf(AlertEvent.SettingTriggered(9701), AlertEvent.SettingTriggered(9702))
    val carriers = originals.map { s63cPublicBoundaryTicket(p, owner, it, context, col) }
    val callbackEntered = CountDownLatch(1); val returnCallback = CountDownLatch(1)
    val permitY = CompletableDeferred<Unit>()
    val seen = ConcurrentLinkedQueue<AlertEvent>(); val callbacks = ConcurrentLinkedQueue<AlertEvent>()
    val yReceipts = ConcurrentLinkedQueue<AlertTapReceipt>()
    val yCallbacks = AtomicInteger()
    val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        bus.events.collect { event ->
            seen += event
            if (event == carriers[1]) {
                permitY.await()
                yReceipts += bus.consumePendingTap(event) { raw ->
                    p.eq(col, "following BANK retains its original", originals[1], raw)
                    yCallbacks.incrementAndGet(); callbacks += raw; AlertTapReceipt.HANDLED
                }
            }
        }
    }
    runCurrent(); val lease = bus.registerPendingTapConsumer(); runCurrent()
    S63cBoundaryWorkers(p, col).use { workers ->
        val receipt = if (unhandled) AlertTapReceipt.UNHANDLED else AlertTapReceipt.HANDLED
        try {
            p.yes(col, "actual X and Y carriers reached the collector", carriers.all { it in seen })
            val xCall = workers.submit { bus.consumePendingTap(carriers[0]) { raw ->
                p.eq(col, "earlier callback retains its original", originals[0], raw)
                callbacks += raw; callbackEntered.countDown()
                p.yes(col, "CLAIMED callback returns within safety bound", returnCallback.await(10, TimeUnit.SECONDS))
                receipt
            } }
            workers.await(callbackEntered, "earlier known is positively CLAIMED inside its callback")
            permitY.complete(Unit); runCurrent()
            p.eq(col, "following call yields while earlier callback has not returned", listOf(AlertTapReceipt.REJECTED), yReceipts.toList())
            p.eq(col, "following callback zero during CLAIMED predecessor", 0, yCallbacks.get())
            p.eq(col, "both originals remain unACKed during the CLAIMED window", 2, bus.pendingTapReport().retainedNonKrx)
            returnCallback.countDown()
            p.eq(col, "earlier call returns its original receipt", receipt, workers.result(xCall, "earlier callback/consume completes"))
            runCurrent()
            p.eq(col, "only automatic pump replays following BANK after receipt", 1, yCallbacks.get())
            p.yes(col, "following ticket actually re-emitted", seen.count { it == carriers[1] } >= 2)
            p.eq(col, "callback order and multiplicity", originals, callbacks.toList())
            p.eq(col, "UNHANDLED survives while HANDLED is removed", if (unhandled) 1 else 0, bus.pendingTapReport().retainedNonKrx)
            p.eq(col, "UNHANDLED is reported separately", if (unhandled) 1 else 0, bus.pendingTapReport().unhandled)
            p.eq(col, "late following carrier cannot duplicate", AlertTapReceipt.REJECTED,
                bus.consumePendingTap(carriers[1]) { callbacks += it; AlertTapReceipt.HANDLED })
            p.eq(col, "duplicate callback zero", originals, callbacks.toList())
        } finally {
            returnCallback.countDown(); permitY.complete(Unit)
            collector.cancel(); runCurrent()
            workers.result(workers.submit { lease.close() }, "CLAIMED boundary consumer closes")
        }
    }
}

/** An injected real lock, contended by a different worker at its public nonblocking acquisition.
 * The finite budget is test input, not an assertion about the owner's internal retry count.
 */
private class S63cFifoContentionLock(val p: S6PendingAlertTapRig, val column: String,
    private val enabled: Boolean) : ReentrantLock() {
    private class Hold {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val released = CountDownLatch(1)
    }
    private val requests = ArrayBlockingQueue<Hold>(1)
    private val stopping = AtomicBoolean(false)
    val consumingX = ThreadLocal.withInitial { false }
    val freshReads = ThreadLocal.withInitial { 0 }
    val xHandled = AtomicBoolean(false)
    val failures = AtomicInteger()

    fun holdOnWorker() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!stopping.get() && System.nanoTime() < deadline) {
            val next = requests.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (stopping.get()) break
            val acquired = super.tryLock(2, TimeUnit.SECONDS)
            p.yes(column, "independent worker acquires the supplied real owner lock", acquired)
            try {
                next.held.countDown()
                p.yes(column, "injected contention ends within three seconds", next.release.await(3, TimeUnit.SECONDS))
            } finally { if (acquired) unlock(); next.released.countDown() }
        }
    }

    override fun tryLock(): Boolean {
        s63cCheckWorkerInterruption()
        if (!enabled || !consumingX.get() || xHandled.get() || stopping.get() || failures.get() >= 4) return super.tryLock()
        p.yes(column, "contention follows an external fresh read", freshReads.get() > 0)
        val next = Hold()
        p.yes(column, "bounded request reaches independent lock holder", requests.offer(next, 1, TimeUnit.SECONDS))
        try {
            p.yes(column, "other worker holds lock at the nonblocking comparison", next.held.await(3, TimeUnit.SECONDS))
            val acquired = super.tryLock()
            if (acquired) unlock()
            p.eq(column, "actual other-worker occupancy makes tryLock fail", false, acquired)
            failures.incrementAndGet()
            return false
        } finally {
            next.release.countDown()
            p.yes(column, "other worker releases before a subsequent owner turn", next.released.await(3, TimeUnit.SECONDS))
        }
    }

    fun stopHolding() { stopping.set(true); requests.offer(Hold()) }
}

/** F04: Y is observed on a passive real subscription while the consuming subscription is still
 * stopped in X's body. Thus Y is already emitted and waiting in that subscription's SharedFlow buffer.
 * After finite contention/detach, only the production consume/finally pump may make progress.
 */
internal fun TestScope.s63cFifoRetryBoundary(p: S6PendingAlertTapRig, detachOther: Boolean) {
    val col = if (detachOther) "F04-detach-other-registration" else "F04-contention"
    val snapshot = p.g.snapshot; val live = p.g.r.identity; val context = p.context(snapshot, live)
    val suppliedLock = S63cFifoContentionLock(p, col, enabled = !detachOther)
    val owner = PendingAlertTapOwner(lock = suppliedLock)
    val readingX = CountDownLatch(1); val resumeRead = CountDownLatch(1)
    val pauseOnce = AtomicBoolean(detachOther)
    val bus = AlertEventBus(owner, true, { true }, {
        s63cCheckWorkerInterruption()
        suppliedLock.freshReads.set(suppliedLock.freshReads.get() + 1)
        if (suppliedLock.consumingX.get() && pauseOnce.compareAndSet(true, false)) {
            readingX.countDown()
            p.yes(col, "old X supplier resumes within ten seconds", resumeRead.await(10, TimeUnit.SECONDS))
        }
        snapshot
    }, { s63cCheckWorkerInterruption(); live }, scope = p.g.scope)
    val originals = listOf(AlertEvent.SettingTriggered(9401), AlertEvent.SettingTriggered(9402))
    val carriers = originals.map { s63cPublicBoundaryTicket(p, owner, it, context, col) }
    val callbacks = ConcurrentLinkedQueue<AlertEvent>()
    val targetSeen = ConcurrentLinkedQueue<AlertEvent>()
    val monitorSeen = ConcurrentLinkedQueue<AlertEvent>()
    val subscribed = CountDownLatch(2); val enteredX = CountDownLatch(1)
    val startX = CountDownLatch(1); val emittedY = CountDownLatch(1)
    val settled = CountDownLatch(1); val firstReturned = CountDownLatch(1)
    val firstReceipt = AtomicReference<AlertTapReceipt?>()
    val cancelledCallbacks = AtomicInteger()
    val streamFailure = AtomicReference<Throwable?>()
    val firstCall = AtomicBoolean(true)
    val targetExited = CountDownLatch(1)
    var leaseA: AutoCloseable? = null; var leaseB: AutoCloseable? = null
    S63cBoundaryWorkers(p, col).use { workers ->
        val streamScope = CoroutineScope(SupervisorJob() + workers.dispatcher + CoroutineExceptionHandler { _, failure ->
            streamFailure.compareAndSet(null, failure); settled.countDown()
        })
        val holder = if (detachOther) null else workers.submit { suppliedLock.holdOnWorker() }
        val target = streamScope.launch {
            try {
                bus.events.onSubscription { subscribed.countDown() }.collect { event ->
                    targetSeen += event
                    val first = firstCall.compareAndSet(true, false)
                    if (first) {
                        p.eq(col, "first actual carrier is original X", carriers[0], event)
                        enteredX.countDown()
                        p.yes(col, "X body starts within the bounded setup gate", startX.await(3, TimeUnit.SECONDS))
                    }
                    suppliedLock.consumingX.set(if (detachOther) first else event == carriers[0] && !suppliedLock.xHandled.get())
                    suppliedLock.freshReads.set(0)
                    try {
                        val receipt = bus.consumePendingTap(event) { raw ->
                            if (detachOther && first) cancelledCallbacks.incrementAndGet()
                            p.yes(col, "callback retains a complete original BANK value", raw in originals)
                            callbacks += raw
                            if (raw == originals[0]) suppliedLock.xHandled.set(true)
                            AlertTapReceipt.HANDLED
                        }
                        if (first) { firstReceipt.set(receipt); firstReturned.countDown() }
                        if (callbacks.size >= 2 && bus.pendingTapReport().retainedNonKrx == 0) settled.countDown()
                    } finally {
                        suppliedLock.consumingX.remove(); suppliedLock.freshReads.remove()
                    }
                }
            } finally { targetExited.countDown() }
        }
        val monitor = streamScope.launch {
            bus.events.onSubscription { subscribed.countDown() }.collect { event ->
                monitorSeen += event
                if (event == carriers[1]) emittedY.countDown()
            }
        }
        try {
            workers.await(subscribed, "consuming and passive real subscriptions are active before ready")
            // A is the passive registration. B is the consuming registration whose unclaimed X
            // will be invalidated by close(A), while B stays registered with Y already buffered.
            if (detachOther) leaseA = bus.registerPendingTapConsumer()
            leaseB = bus.registerPendingTapConsumer()
            workers.await(enteredX, "consuming collector is stopped in X before Y")
            // Initial boundary input, before X starts: put the already-owned Y carrier in the
            // real buffer even if a valid implementation pumps only the oldest ticket. An
            // automatic Y carrier already present is a duplicate carrier, never another intent.
            p.eq(col, "initial owned Y carrier enters the real SharedFlow buffer", true, bus.emit(carriers[1]))
            workers.await(emittedY, "passive subscription positively observes actual Y emission")
            p.eq(col, "original production emission order is X then Y", carriers, monitorSeen.toList().take(2))
            p.eq(col, "consuming collector has not advanced to buffered Y", listOf(carriers[0]), targetSeen.toList())
            p.eq(col, "neither raw original is ACKed during the pending window", 2, bus.pendingTapReport().retainedNonKrx)
            startX.countDown()
            if (detachOther) {
                workers.await(readingX, "active B's unclaimed X reaches the paused fresh supplier")
                workers.result(workers.submit { leaseA!!.close() }, "close(A) completes while B remains active")
                p.eq(col, "no callback before completed owner-wide detach resumes X", emptyList<AlertEvent>(), callbacks.toList())
                resumeRead.countDown()
                workers.await(firstReturned, "cancelled B invocation returns within the bound")
                p.eq(col, "old invocation cannot renew its detached reservation", AlertTapReceipt.REJECTED, firstReceipt.get())
                p.eq(col, "cancelled invocation callback zero", 0, cancelledCallbacks.get())
            }
            workers.await(settled, "without another external signal both originals reach HANDLED", seconds = 5)
            streamFailure.get()?.let { throw AssertionError(p.c(col, "native collector failed"), it) }
            p.eq(col, "final callback FIFO and exact multiplicity are X then Y", originals, callbacks.toList())
            p.eq(col, "both originals removed only after their HANDLED callbacks", 0, bus.pendingTapReport().retainedNonKrx)
            if (!detachOther) p.yes(col, "positive real final-comparison contention occurred", suppliedLock.failures.get() > 0)
            for (carrier in carriers) {
                p.eq(col, "post-completion late carrier is not delivered again", AlertTapReceipt.REJECTED,
                    workers.result(workers.submit { bus.consumePendingTap(carrier) { callbacks += it; AlertTapReceipt.HANDLED } },
                        "late duplicate consume returns within bound"))
            }
            p.eq(col, "duplicate callbacks zero across both original tickets", originals, callbacks.toList())
        } finally {
            startX.countDown(); resumeRead.countDown(); suppliedLock.stopHolding()
            target.cancel(); monitor.cancel(); streamScope.cancel()
            workers.await(targetExited, "native consuming collector exits within teardown bound")
            if (holder != null) workers.result(holder, "finite lock-holder worker exits")
            workers.result(workers.submit { leaseA?.close(); leaseB?.close() }, "all registrations close within teardown bound")
        }
    }
}

/** F01 boundary input: S1/S2 are genuine checked PAC publications; only publication timing is scripted.
 * No owner lock, private method, claim state or implementation-specific hook is touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.s63cFinalReadBoundary(p: S6PendingAlertTapRig, queued: Boolean, afterClaim: Boolean) {
    val col = "F01-${if (queued) "queued" else "direct"}-${if (afterClaim) "after-claim" else "read-then-revoke"}"
    val s1 = p.g.snapshot
    val original = p.context(s1)
    p.yes(col, "positive original scope before boundary construction", original.scope?.let { s1.admitsKrxUse(it.use) } == true)
    p.g.session.capEnd()
    val s2 = p.g.snapshot
    p.yes(col, "S2 is a real capability-end publication that withdraws original", s2.lastCapabilityEnd != s1.lastCapabilityEnd &&
        original.scope?.let { !s2.admitsKrxUse(it.use) } == true)
    val publication = java.util.concurrent.atomic.AtomicReference(s1)
    val armed = java.util.concurrent.atomic.AtomicBoolean(false)
    val flips = java.util.concurrent.atomic.AtomicInteger(0)
    val reads = java.util.concurrent.atomic.AtomicInteger(0)
    val owner = PendingAlertTapOwner()
    val bus = AlertEventBus(owner, true, { true }, {
        val result = publication.get()
        reads.incrementAndGet()
        // The caller receives S1, but the next observable publication is already S2 before
        // this supplier call returns. A one-read-before-owner-lock implementation is stale.
        if (armed.compareAndSet(true, false)) { publication.set(s2); flips.incrementAndGet() }
        result
    }, { original.identity }, scope = p.g.scope)
    fun capture(event: AlertEvent, metadata: AlertTapMetadata): AlertTapTicket {
        val result = owner.capture(event, metadata, original)
        val ticket = p.need(col, (result as? AlertTapCaptureResult.CAPTURED)?.ticket, "boundary owner captured immutable original")
        owner.classify(ticket)
        p.eq(col, "positive raw/context custody", listOf(event, metadata, original),
            p.need(col, p.inventory(owner)[ticket], "boundary ticket is owned").let { listOf(it.event, it.metadata, it.context) })
        return ticket
    }
    val krxEvent = S6PendingAlertTapRig.sourceEvent(7001)
    val krx = capture(krxEvent, S6PendingAlertTapRig.source())
    val non = capture(S6PendingAlertTapRig.sourceEvent(7002), S6PendingAlertTapRig.source("upbit"))
    val nonRaw = p.need(col, p.inventory(owner)[non], "nonKRX control is positively owned")
    var callbacks = 0
    val receipts = mutableListOf<AlertTapReceipt>()
    val action: (AlertEvent) -> AlertTapReceipt = { event ->
        if (event == krxEvent) {
            callbacks++
            if (afterClaim) publication.set(s2) // accepted synchronous callback proves claim has occurred.
            AlertTapReceipt.HANDLED
        } else AlertTapReceipt.UNHANDLED
    }
    val carrier = AlertEvent.PendingTap(krx)
    if (!queued) {
        p.eq(col, "carrier accepted before final-read race", true, bus.forwardPendingTap(carrier))
        p.yes(col, "target still owned immediately before claim", krx in p.inventory(owner))
        armed.set(!afterClaim)
        receipts += bus.consumePendingTap(carrier, action)
    } else {
        val gate = CompletableDeferred<Unit>()
        val arrived = mutableListOf<AlertEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { event -> arrived += event; gate.await(); receipts += bus.consumePendingTap(event, action) }
        }
        runCurrent()
        val lease = bus.registerPendingTapConsumer()
        try {
            bus.forwardPendingTap(carrier); runCurrent()
            p.yes(col, "real bus carrier is suspended in collector before claim", carrier in arrived)
            p.yes(col, "queued raw target still owned before gate release", krx in p.inventory(owner))
            armed.set(!afterClaim)
            gate.complete(Unit); runCurrent()
        } finally { lease.close(); job.cancel(); gate.complete(Unit); runCurrent() }
    }
    p.yes(col, "final consumer path actually read supplied snapshot", reads.get() > 0)
    p.eq(col, "scripted preclaim publication occurred exactly once", if (afterClaim) 0 else 1, flips.get())
    p.eq(col, "claim-before-revoke is accepted, revoke-before-claim executes zero", if (afterClaim) 1 else 0, callbacks)
    if (afterClaim) p.yes(col, "postclaim revocation does not retract HANDLED receipt", AlertTapReceipt.HANDLED in receipts)
    else p.yes(col, "preclaim revocation never ACKs KRX", AlertTapReceipt.HANDLED !in receipts)
    owner.reconcile(publication.get(), original.identity)
    p.yes(col, "selected KRX payload absent at latest reconcile", krx !in p.inventory(owner))
    p.eq(col, "capability end preserves nonKRX immutable payload/context", listOf(nonRaw.event, nonRaw.metadata, nonRaw.context),
        p.need(col, p.inventory(owner)[non], "nonKRX remains owned").let { listOf(it.event, it.metadata, it.context) })
}

/** Actual bounded HTTP fixture for P10. Settings failures are installed, never called by tap classification. */
internal class S6SettingsEndpointFixture(val mode: String) : AutoCloseable {
    val paths = setOf("/api/notification-settings", "/api/source-notification-settings", "/api/comparison-alerts")
    private val requests = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val controlRequests = java.util.concurrent.atomic.AtomicInteger(0)
    private val server = okhttp3.mockwebserver.MockWebServer()
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    init {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                if (path == "/__fixture__/ready") {
                    controlRequests.incrementAndGet()
                    return okhttp3.mockwebserver.MockResponse().setResponseCode(204)
                }
                if (path !in paths) return okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                requests += "${request.method} $path"
                return when (mode) {
                    "exists", "no-tap" -> okhttp3.mockwebserver.MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"settings":[{"id":7,"source":"krx","asset":"usd-krw-futures"}]}""")
                    "deleted" -> okhttp3.mockwebserver.MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}""")
                    "throw" -> okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    "timeout" -> okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                    "503" -> okhttp3.mockwebserver.MockResponse().setResponseCode(503).setBody("""{"error":"unavailable"}""")
                    else -> throw AssertionError("contract(P10/$mode): unclassified settings endpoint state")
                }
            }
        }
        // If sockets are unavailable this is an observable contract failure, never an empty/skip column.
        try { server.start() }
        catch (failure: Throwable) { throw AssertionError("contract(P10/$mode): real settings HTTP endpoint could not be installed", failure) }
    }
    val baseUrl get() = server.url("/")
    fun settingsRequests(): List<String> = requests.toList()
    fun verifyServing() {
        try {
            client.newCall(okhttp3.Request.Builder().url(server.url("/__fixture__/ready")).build()).execute().use {
                org.junit.Assert.assertEquals("contract(P10/$mode): actual HTTP serving positive control", 204, it.code)
            }
            org.junit.Assert.assertEquals("contract(P10/$mode): dispatcher positive control observed exactly once", 1, controlRequests.get())
            org.junit.Assert.assertTrue("contract(P10/$mode): no settings request in serving control", requests.isEmpty())
        } catch (failure: Throwable) {
            if (failure is AssertionError) throw failure
            throw AssertionError("contract(P10/$mode): bounded HTTP positive control failed", failure)
        }
    }
    override fun close() {
        client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        server.shutdown()
    }
}

/** Hold the first actual completed KRX REST200; later refusals and fresh K2 remain deliverable. */
internal class S6QueuedBootstrap200(private val g: S6KrxGraphRig, private val row: String) : AutoCloseable {
    private val target = g.session.cleanup.session
    @Suppress("UNCHECKED_CAST")
    private val original = field(target, "inputs") as kotlinx.coroutines.channels.Channel<Any>
    private val held = ArrayDeque<Any>()
    private var released = false
    val count: Int get() = held.size
    init {
        val wrapped = object : kotlinx.coroutines.channels.Channel<Any> by original {
            override fun trySend(element: Any): kotlinx.coroutines.channels.ChannelResult<Unit> {
                val selected = !released && held.isEmpty() && element.javaClass.simpleName == "BootstrapAnswered" &&
                    field(element, "topic") == com.jay.fxi.data.remote.TopicCatalogue.KRX_FUTURES &&
                    field(element, "outcome") is com.jay.fxi.data.remote.TopicSnapshotOutcome.Delivered
                if (!selected) return original.trySend(element)
                held.addLast(element)
                val acceptance = kotlinx.coroutines.channels.Channel<Any>(1)
                return acceptance.trySend(element).also { acceptance.close() }
            }
        }
        com.jay.fxi.data.remote.S6KrxCleanupRig.setField(target, "inputs", wrapped)
        org.junit.Assert.assertSame("premise: $row actual SESSION input dependency installed", wrapped, field(target, "inputs"))
    }
    fun retained(): List<Any> = held.toList()
    fun release() {
        released = true
        val originals = held.toList(); held.clear()
        originals.forEach { org.junit.Assert.assertTrue("contract($row): completed original REST200 enters actual SESSION FIFO", original.trySend(it).isSuccess) }
        g.r.drain()
    }
    override fun close() {
        com.jay.fxi.data.remote.S6KrxCleanupRig.setField(target, "inputs", original)
        release()
    }
}
