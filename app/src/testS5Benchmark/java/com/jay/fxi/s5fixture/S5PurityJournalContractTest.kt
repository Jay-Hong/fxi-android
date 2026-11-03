package com.jay.fxi.s5fixture

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Every generated input has its own JUnit display sub-ID. No builds/tests were run for the L1 draft.
 * Runtime TODO failures are reported as L1-stub-prerequisite, not as a responsible mutation kill.
 * Actual loopback Server traffic is used for count/admission/API and startup errors. State checkpoints
 * exercise the actual journal for mid-transaction deaths, concurrency, bounds and fatal seams.
 */
@RunWith(Parameterized::class)
class S5PurityJournalContractTest(private val input: Input) {
    class Input(val id: String, val row: String, val duty: String, val body: (String) -> Unit) {
        override fun toString() = id
    }

    @Test fun contract() {
        try {
            input.body(input.id)
        } catch (stub: NotImplementedError) {
            throw AssertionError("precondition(D17a/${input.id}): L1-stub-prerequisite (${input.duty})", stub)
        } catch (assertion: AssertionError) {
            if (assertion.message?.startsWith("contract(D17a):") == true) throw assertion
            throw AssertionError("contract(D17a):${input.id}/${input.duty}", assertion)
        } catch (unexpected: Throwable) {
            val stub = generateSequence(unexpected) { it.cause }.filterIsInstance<NotImplementedError>().firstOrNull()
            if (stub != null) throw AssertionError("precondition(D17a/${input.id}): L1-stub-prerequisite (${input.duty})", stub)
            throw AssertionError("precondition(D17a/${input.id}): unexpected-exception (${input.duty})", unexpected)
        }
    }

    companion object {
        private fun message(id: String, duty: String) = "contract(D17a):$id/$duty"
        private fun yes(id: String, duty: String, condition: Boolean) = assertTrue(message(id, duty), condition)
        private fun eq(id: String, duty: String, expected: Any?, actual: Any?) = assertEquals(message(id, duty), expected, actual)
        private fun eventually(id: String, duty: String, condition: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!condition() && System.nanoTime() < end) TimeUnit.MILLISECONDS.sleep(5)
            yes(id, duty, condition())
        }
        private fun fatal(id: String, rig: D17Rig, code: Int, block: () -> Unit) {
            var returned = false
            try { block(); returned = true } catch (exit: SimulatedExit) { eq(id, "fatal-code", code, exit.code) }
            yes(id, "fatal-no-normal-return", !returned)
            eq(id, "fatal-reached", code, rig.fatals.poll(3, TimeUnit.SECONDS))
        }
        private fun rejected(id: String, duty: String, block: () -> Unit) {
            var rejected = false
            try { block() } catch (expected: IllegalArgumentException) { rejected = true }
            catch (expected: IllegalStateException) { rejected = true }
            yes(id, duty, rejected)
        }
        private fun noIdleAfterPoison(id: String, rig: D17Rig, journal: S5PurityJournal = rig.journal) {
            val poison = rig.events.indexOf("point:POISONED")
            yes(id, "sticky-poison-reached", poison >= 0)
            yes(id, "no-idle-after-poison", rig.events.drop(poison + 1).none { it.startsWith("summary:call:IDLE") })
            yes(id, "admission-closed", journal.snapshot().poisoned && journal.snapshot().closed)
        }

        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun inputs(): List<Array<Any>> {
            val inputs = mutableListOf<Input>()
            fun add(id: String, row: String, duty: String, body: (String) -> Unit) { inputs += Input(id, row, duty, body) }

            for (traffic in listOf("http", "ws", "ping")) {
                add("count-zero-$traffic", "D17a-count", "idle-counter-commit-zero") { id ->
                    D17Rig().use { r ->
                        val idleTruth = java.util.concurrent.CopyOnWriteArrayList<Triple<Int, Int, Int>>()
                        r.summary.afterApply = { if (it.state == SummaryState.IDLE) idleTruth += Triple(it.counter, r.counter.get(), r.io.lines("commit").size) }
                        when (traffic) {
                            "http" -> {
                                eq(id, "precondition-ok-http", 200, r.get("api/v2/graph/catalog"))
                                eq(id, "precondition-expected404", 404, r.get("api/v2/topics/snapshot?topic=fx:usd-krw"))
                            }
                            else -> {
                                val (socket, frames) = r.socket(id)
                                socket.send(if (traffic == "ping") "ping" else """{"type":"subscribe","request_id":"c1","topics":["usdt:krw"]}""")
                                val first = frames.frames.poll(3, TimeUnit.SECONDS)
                                yes(id, "precondition-server-reply", first?.contains(if (traffic == "ping") "pong" else "subscription_ack") == true)
                                if (traffic == "ws") yes(id, "precondition-snapshot", frames.frames.poll(3, TimeUnit.SECONDS)?.contains("snapshot") == true)
                            }
                        }
                        // A received WS frame precedes the callback's leave; wait for the actual IDLE publication.
                        eventually(id, "idle-counter-commit-zero") { r.localComplete(0) }
                        yes(id, "each-idle-actual-commit-summary", idleTruth.isNotEmpty() && idleTruth.all { it.first == it.second && it.second == it.third })
                        for (s in r.summary.returned.filter { it.state == SummaryState.IDLE }) eq(id, "every-idle-counter", 0, s.counter)
                    }
                }
            }
            for (transport in listOf("http", "ws")) for (count in listOf(1, 70)) {
                add("count-$transport-$count", "D17a-count", "all-commits-retained") { id ->
                    D17Rig().use { r ->
                        val idleTruth = java.util.concurrent.CopyOnWriteArrayList<Triple<Int, Int, Int>>()
                        r.summary.afterApply = { if (it.state == SummaryState.IDLE) idleTruth += Triple(it.counter, r.counter.get(), r.io.lines("commit").size) }
                        if (transport == "http") repeat(count) { eq(id, "precondition-unexpected-http", 404, r.get("api/unexpected/$it")) }
                        else {
                            val (socket, frames) = r.socket(id)
                            repeat(count) { socket.send("not json") }
                            socket.send("ping")
                            yes(id, "precondition-ws-drained", frames.frames.poll(3, TimeUnit.SECONDS)?.contains("pong") == true)
                        }
                        eq(id, "precondition-real-atomic-counter", count, r.server.unexpectedCount)
                        eq(id, "all-commits-retained", count, r.io.lines("commit").size)
                        eventually(id, "idle-counter-commit-equality") { r.localComplete(count) }
                        yes(id, "each-idle-actual-commit-summary", idleTruth.isNotEmpty() && idleTruth.all { it.first == it.second && it.second == it.third })
                        if (count == 70 && transport == "http") eq(id, "recent64-independent", 64, r.server.recentRequests().size)
                    }
                }
            }

            for (traffic in listOf("http", "ws-parse", "ws-ping")) {
                add("admit-barrier-$traffic", "D17a-admit", "busy-before-classification") { id ->
                    D17Rig().use { r ->
                        r.start()
                        val ws = if (traffic == "http") null else r.socket(id)
                        val entered = CountDownLatch(1)
                        val release = CountDownLatch(1)
                        val worker = Executors.newSingleThreadExecutor()
                        val beforeEvents = r.events.size
                        r.summary.beforeApply = { if (it.state == SummaryState.BUSY) { entered.countDown(); release.await(3, TimeUnit.SECONDS) } }
                        val future = worker.submit {
                            if (ws == null) r.get("api/unexpected/barrier")
                            else ws.first.send(if (traffic == "ws-ping") "ping" else "not json")
                        }
                        try {
                            yes(id, "busy-barrier-reached", entered.await(3, TimeUnit.SECONDS))
                            eq(id, "counter-before-busy-return", 0, r.counter.get())
                            yes(id, "route-parse-ping-before-busy-return", r.events.drop(beforeEvents).none { it in listOf("point:HTTP_URL", "point:WS_PARSE", "point:WS_PING", "point:HTTP_RESPONSE", "point:WS_SEND") })
                            if (ws == null) yes(id, "http-response-before-busy-return", !future.isDone)
                            else eq(id, "ws-send-before-busy-return", null, ws.second.frames.poll(100, TimeUnit.MILLISECONDS))
                            release.countDown()
                            future.get(3, TimeUnit.SECONDS)
                            if (ws != null) {
                                if (traffic == "ws-parse") ws.first.send("ping")
                                yes(id, "precondition-callback-drained", ws.second.frames.poll(3, TimeUnit.SECONDS)?.contains("pong") == true)
                            }
                            val requiredPoint = when (traffic) { "http" -> "point:HTTP_URL"; "ws-parse" -> "point:WS_PARSE"; else -> "point:WS_PING" }
                            val tail = r.events.drop(beforeEvents)
                            val busyReturned = tail.indexOfFirst { it.startsWith("summary:returned:BUSY") }
                            yes(id, "real-classification-point-after-busy", busyReturned >= 0 && tail.indexOf(requiredPoint) > busyReturned)
                        } finally { release.countDown(); worker.shutdownNow() }
                    }
                }
            }

            for (traffic in listOf("http", "ws-parse", "ws-ping", "ws-subscribe")) {
                for (applied in listOf(false, true)) for (poisonFails in listOf(false, true)) {
                    add("admit-throttle-$traffic-${if (applied) "after" else "before"}-${if (poisonFails) "poison-fails" else "poison-ok"}", "D17a-admit", "fatal88-no-block-no-response") { id ->
                        D17Rig().use { r ->
                            r.start()
                            val ws = if (traffic == "http") null else r.socket(id)
                            val beforeCount = r.events.size
                            val throwBusy: (ProcessSummary) -> Unit = { if (it.state == SummaryState.BUSY) throw RuntimeException("model throttle") }
                            if (applied) r.summary.afterApply = throwBusy else r.summary.beforeApply = throwBusy
                            if (poisonFails) {
                                val old = r.summary.beforeApply
                                r.summary.beforeApply = { if (it.state == SummaryState.POISON) throw RuntimeException("model poison failure"); old(it) }
                            }
                            val httpResult = if (ws == null) runCatching { r.get("api/unexpected/throttle") } else null
                            if (ws == null) yes(id, "http-response-zero", httpResult!!.isFailure)
                            if (ws != null) ws.first.send(when (traffic) {
                                "ws-ping" -> "ping"
                                "ws-subscribe" -> """{"type":"subscribe","request_id":"a1","topics":["usdt:krw"]}"""
                                else -> "not json"
                            })
                            eq(id, "fatal88-reached", 88, r.fatals.poll(3, TimeUnit.SECONDS))
                            eq(id, "counter-zero", 0, r.counter.get())
                            yes(id, "block-route-parse-send-zero", r.events.drop(beforeCount).none { it in listOf("point:HTTP_URL", "point:HTTP_RESPONSE", "point:WS_PARSE", "point:WS_PING", "point:WS_SEND") })
                            if (ws != null) eq(id, "ws-ack-pong-snapshot-zero", null, ws.second.frames.poll(100, TimeUnit.MILLISECONDS))
                            noIdleAfterPoison(id, r)
                            val poisonCalls = r.events.drop(beforeCount).count { it.startsWith("summary:call:POISON") }
                            yes(id, "poison-at-most-once", poisonCalls <= 1)
                            var ran = false
                            try { r.journal.classifiedCallback { ran = true } } catch (_: SimulatedExit) { }
                            yes(id, "later-callback-block-zero", !ran)
                        }
                    }
                }
            }
            for (boundary in listOf("before-apply-before-poison", "after-apply-before-poison", "poison-applied", "fatal88-old-idle")) {
                add("admit-kill-$boundary", "D17a-admit", "explicit-preclassification-boundary") { id ->
                    D17Rig().use { r ->
                        r.register()
                        var frozen: FrozenD17? = null
                        val capture = { frozen = r.freeze(); throw SimulatedKill() }
                        r.summary.beforeApply = {
                            if (it.state == SummaryState.BUSY && boundary != "after-apply-before-poison") throw RuntimeException("before apply")
                            if (it.state == SummaryState.POISON && boundary != "poison-applied") {
                                if (boundary == "fatal88-old-idle") throw RuntimeException("poison failed") else capture()
                            }
                        }
                        r.summary.afterApply = {
                            if (it.state == SummaryState.BUSY) throw RuntimeException("after apply")
                            if (it.state == SummaryState.POISON && boundary == "poison-applied") capture()
                        }
                        if (boundary == "fatal88-old-idle") r.fatalFailure = { code -> frozen = r.freeze(); throw SimulatedExit(code) }
                        var classified = false
                        try { r.journal.classifiedCallback { classified = true } } catch (_: SimulatedKill) { } catch (_: SimulatedExit) { }
                        yes(id, "snapshot-reached", frozen != null)
                        yes(id, "classification-zero", !classified)
                        eq(id, "actual-counter-zero", 0, frozen!!.count)
                        val oldIdle = boundary == "before-apply-before-poison" || boundary == "fatal88-old-idle"
                        eq(id, "os-state-at-kill", if (oldIdle) SummaryState.IDLE else if (boundary == "poison-applied") SummaryState.POISON else SummaryState.BUSY, frozen!!.summary?.state)
                        // Python bridge checks C6/C7: before-apply+allowed kill may be zero; EXIT_SELF/88 must fail C7.
                        eq(id, "fatal-reason-witness", boundary == "fatal88-old-idle", r.fatals.contains(88))
                    }
                }
            }
            add("admit-direct-unexpected", "D17a-admit", "unguarded-entry-rejected") { id ->
                D17Rig().use { r -> r.register(); rejected(id, "unguarded-entry-rejected") { r.journal.recordUnexpected("GET /direct ") }; eq(id, "counter-unchanged", 0, r.counter.get()) }
            }

            val stops = listOf(PurityPoint.CLASSIFIED_UNEXPECTED, PurityPoint.PREPARE_STARTED, PurityPoint.PREPARE_PARTIAL,
                PurityPoint.PREPARE_SYNCED, PurityPoint.BEFORE_COUNTER, PurityPoint.AFTER_COUNTER,
                PurityPoint.COMMIT_STARTED, PurityPoint.COMMIT_PARTIAL, PurityPoint.COMMIT_SYNCED,
                PurityPoint.BEFORE_LEAVE, PurityPoint.AFTER_LEAVE)
            for (stop in stops) add("order-${stop.name.lowercase()}", "D17a-order", "prepare-counter-commit-leave-order") { id ->
                D17Rig().use { r ->
                    r.register()
                    var frozen: FrozenD17? = null
                    val capture = { frozen = r.freeze(); throw SimulatedKill() }
                    if (stop in listOf(PurityPoint.PREPARE_PARTIAL, PurityPoint.COMMIT_PARTIAL)) {
                        val kind = if (stop == PurityPoint.PREPARE_PARTIAL) "prepare" else "commit"
                        r.io.fault = IOFault(kind, WriteFailure.PARTIAL)
                        r.io.afterBytes = { _, _, partial -> if (partial) capture() }
                    } else r.hook = { if (it == stop) capture() }
                    try { r.journal.classifiedCallback { r.journal.recordUnexpected("GET /order ") } } catch (_: SimulatedKill) { }
                    yes(id, "checkpoint-reached", frozen != null)
                    val beforeIncrement = stop in stops.take(5)
                    eq(id, "actual-counter-at-checkpoint", if (beforeIncrement) 0 else 1, frozen!!.count)
                    val committed = stop in listOf(PurityPoint.COMMIT_SYNCED, PurityPoint.BEFORE_LEAVE, PurityPoint.AFTER_LEAVE)
                    val raw = frozen!!.files.filterKeys { it.startsWith("instance-") }.values.single().decodeToString()
                    val completeLines = raw.split('\n').dropLast(1)
                    eq(id, "commit-before-return", committed, completeLines.any { it.contains("\"kind\":\"commit\"") })
                    eq(id, "idle-only-after-leave", if (stop == PurityPoint.AFTER_LEAVE) SummaryState.IDLE else SummaryState.BUSY, frozen!!.summary?.state)
                    val prepare = r.events.indexOf("sync:prepare")
                    val counter = r.events.indexOf("point:AFTER_COUNTER")
                    if (counter >= 0) yes(id, "prepare-sync-before-counter", prepare in 0 until counter)
                }
            }
            for (transport in listOf("http", "ws")) add("order-response-$transport", "D17a-order", "commit-before-response-send") { id ->
                D17Rig().use { r ->
                    r.start()
                    val ws = if (transport == "ws") r.socket(id) else null
                    val commitEntered = CountDownLatch(1); val release = CountDownLatch(1)
                    r.io.beforeWrite = { _, bytes -> if (bytes.decodeToString().contains("\"kind\":\"commit\"")) { commitEntered.countDown(); release.await(3, TimeUnit.SECONDS) } }
                    val worker = Executors.newSingleThreadExecutor()
                    val call = worker.submit { if (ws == null) r.get("api/order") else { ws.first.send("not json"); ws.first.send("ping") } }
                    try {
                        yes(id, "commit-write-barrier-reached", commitEntered.await(3, TimeUnit.SECONDS))
                        if (ws == null) yes(id, "no-http-response-before-commit", runCatching { call.get(100, TimeUnit.MILLISECONDS) }.exceptionOrNull() is java.util.concurrent.TimeoutException)
                        else eq(id, "no-ws-return-before-commit", null, ws.second.frames.poll(100, TimeUnit.MILLISECONDS))
                    } finally { release.countDown(); worker.shutdownNow() }
                }
            }

            for (boundary in listOf("zero-prepare-poison-before-fatal", "classified-before-prepare")) add("B1-$boundary", "D17a-B1", "classified-begin-only-never-idle") { id ->
                D17Rig().use { r ->
                    r.register()
                    var frozen: FrozenD17? = null
                    if (boundary.startsWith("zero")) {
                        r.io.fault = IOFault("prepare", WriteFailure.ZERO)
                        r.beforeFatal = { frozen = r.freeze(); throw SimulatedKill() }
                    } else r.hook = { if (it == PurityPoint.CLASSIFIED_UNEXPECTED) { frozen = r.freeze(); throw SimulatedKill() } }
                    try { r.journal.classifiedCallback { r.journal.recordUnexpected("GET /B1 ") } } catch (_: SimulatedKill) { }
                    yes(id, "kill-snapshot-reached", frozen != null)
                    eq(id, "counter-zero", 0, frozen!!.count)
                    yes(id, "busy-or-poison-even-with-allowed-kill", frozen!!.summary?.state in setOf(SummaryState.BUSY, SummaryState.POISON))
                    yes(id, "begin-only-bytes", frozen!!.files.filterKeys { it.startsWith("instance-") }.values.single().decodeToString().lineSequence().filter { it.isNotEmpty() }.count() == 1)
                }
            }

            for (kind in listOf("register", "begin", "prepare", "commit")) for (failure in WriteFailure.entries) {
                add("fail-$kind-${failure.name.lowercase()}", "D17a-fail", if (kind in listOf("register", "begin")) "fatal87-bind-zero" else "fatal86-response-zero") { id ->
                    D17Rig().use { r ->
                        if (kind in listOf("register", "begin")) {
                            r.io.fault = IOFault(kind, failure)
                            runCatching { r.start() }
                            eq(id, "fatal87-reached", 87, r.fatals.poll(3, TimeUnit.SECONDS))
                            eq(id, "bind-zero", 0, r.bound.get())
                        } else {
                            r.start(); r.io.fault = IOFault(kind, failure)
                            val result = runCatching { r.get("api/file-failure") }
                            eq(id, "fatal86-reached", 86, r.fatals.poll(3, TimeUnit.SECONDS))
                            yes(id, "response-zero", result.isFailure)
                            noIdleAfterPoison(id, r)
                        }
                    }
                }
            }
            for (phase in listOf("registration-busy", "registration-idle", "leave-idle")) for (applied in listOf(false, true)) {
                add("fail-$phase-${if (applied) "after" else "before"}", "D17a-fail", "summary-exception-not-success") { id ->
                    D17Rig().use { r ->
                        if (phase == "leave-idle") r.register()
                        val throwAt: (ProcessSummary) -> Unit = { if (it.state == if (phase == "registration-busy") SummaryState.BUSY else SummaryState.IDLE) throw RuntimeException("summary failure") }
                        if (applied) r.summary.afterApply = throwAt else r.summary.beforeApply = throwAt
                        fatal(id, r, if (phase == "leave-idle") 86 else 87) {
                            if (phase == "leave-idle") r.journal.classifiedCallback { r.journal.recordUnexpected("GET /idle ") } else r.register()
                        }
                        if (phase == "leave-idle" && applied) {
                            // At the successful OS application itself IDLE(1) was truthful, even if poison later replaces it.
                            yes(id, "applied-idle-already-truthful", "summary:applied:IDLE:1" in r.events)
                            eq(id, "actual-matches-commits", r.counter.get(), r.io.lines("commit").size)
                        }
                    }
                }
            }
            add("fail-admitted-poison-api", "D17a-fail", "failed-poison-keeps-busy") { id ->
                D17Rig().use { r -> r.register(); r.io.fault = IOFault("prepare", WriteFailure.ZERO)
                    r.summary.beforeApply = { if (it.state == SummaryState.POISON) throw RuntimeException("poison") }
                    fatal(id, r, 86) { r.journal.classifiedCallback { r.journal.recordUnexpected("GET /poison ") } }
                    eq(id, "previous-busy-retained", SummaryState.BUSY, r.summary.decoded()?.state); noIdleAfterPoison(id, r)
                }
            }
            for (code in listOf(86, 87, 88)) for (behavior in listOf("throws", "returns-reflection")) {
                add("fail-fatal$code-$behavior", "D17a-fail", "fatal-never-normal-leave") { id ->
                    D17Rig().use { r ->
                        // Nothing compiles as java.lang.Void. A proxy can violate that JVM contract and return null.
                        val hostile = if (behavior == "throws") Fatal { throw IllegalStateException("hostile fatal") } else
                            java.lang.reflect.Proxy.newProxyInstance(Fatal::class.java.classLoader, arrayOf(Fatal::class.java)) { _, _, _ -> null } as Fatal
                        val journal = S5PurityJournal(r.io, PurityMode.Supported(r.summary) { D17Inputs.identity }, hostile, r.process, r.counter, { r.events += "point:$it" })
                        var returned = false
                        try {
                            if (code == 87) { r.io.fault = IOFault("register", WriteFailure.ZERO); journal.registerBeforeBind(r.context) }
                            else {
                                journal.registerBeforeBind(r.context)
                                if (code == 86) r.io.fault = IOFault("prepare", WriteFailure.ZERO)
                                else r.summary.beforeApply = { if (it.state == SummaryState.BUSY) throw RuntimeException("throttle") }
                                journal.classifiedCallback { journal.recordUnexpected("GET /fatal ") }
                            }
                            returned = true
                        } catch (stub: NotImplementedError) { throw stub } catch (_: Throwable) { }
                        yes(id, "fatal-never-normal-return", !returned)
                        noIdleAfterPoison(id, r, journal)
                    }
                }
            }

            add("concurrent-http-ws", "D17a-concurrent", "overlapping-admissions-no-early-idle") { id ->
                D17Rig().use { r ->
                    r.start(); val (socket, frames) = r.socket(id)
                    val held = CountDownLatch(1); val release = CountDownLatch(1)
                    r.hook = { if (it == PurityPoint.HTTP_RESPONSE) { held.countDown(); release.await(3, TimeUnit.SECONDS) } }
                    val pool = Executors.newSingleThreadExecutor()
                    val http = pool.submit { r.get("api/entitlements") }
                    try {
                        yes(id, "http-admitted-barrier", held.await(3, TimeUnit.SECONDS))
                        socket.send("not json"); socket.send("ping")
                        yes(id, "ws-finished-while-http-active", frames.frames.poll(3, TimeUnit.SECONDS)?.contains("pong") == true)
                        eq(id, "busy-while-http-active", SummaryState.BUSY, r.summary.decoded()?.state)
                    } finally { release.countDown(); http.get(3, TimeUnit.SECONDS); pool.shutdownNow() }
                    eventually(id, "idle-after-all-finish") { r.localComplete(1) }
                }
            }
            add("concurrent-unexpected", "D17a-concurrent", "transaction-pairs-alternate") { id ->
                D17Rig().use { r ->
                    r.register(); val pool = Executors.newFixedThreadPool(4)
                    try {
                        val gate = CountDownLatch(1)
                        val jobs = (1..12).map { n -> pool.submit { gate.await(); r.journal.classifiedCallback { r.journal.recordUnexpected("GET /parallel/$n ") } } }
                        gate.countDown()
                        // A fatal in valid concurrent use is a contract violation, not a precondition; other failures keep the old path.
                        val fatals = jobs.mapNotNull { job -> try { job.get(3, TimeUnit.SECONDS); null } catch (e: java.util.concurrent.ExecutionException) { (e.cause as? SimulatedExit)?.toString() ?: throw e } }
                        eq(id, "no-fatal-under-valid-concurrency", emptyList<String>(), fatals)
                        val raw = r.io.snapshot().filterKeys { it.startsWith("instance-") }.values.single()
                        val lines = JournalLineCodec.readInstance(raw).drop(1)
                        eq(id, "p-c-alternate", (1..12).flatMap { listOf("prepare:$it", "commit:$it") }, lines.map { "${it["kind"]!!.jsonPrimitive.content}:${it["seq"]!!.jsonPrimitive.content}" })
                        yes(id, "all-counter-commits", r.localComplete(12))
                    } finally { pool.shutdownNow() }
                }
            }
            for (schedule in listOf("failure-first", "success-first")) add("concurrent-poison-$schedule", "D17a-concurrent", "last-success-never-clears-poison") { id ->
                D17Rig().use { r ->
                    r.register(); val pool = Executors.newFixedThreadPool(2)
                    val entered = CountDownLatch(2); val goodGo = CountDownLatch(1); val badGo = CountDownLatch(1)
                    try {
                        val good = pool.submit { r.journal.classifiedCallback { entered.countDown(); goodGo.await(3, TimeUnit.SECONDS) } }
                        val bad = pool.submit { try { r.journal.classifiedCallback<Unit> { entered.countDown(); badGo.await(3, TimeUnit.SECONDS); throw IllegalStateException("admitted failure") } } catch (_: SimulatedExit) { } }
                        yes(id, "both-callbacks-active", entered.await(3, TimeUnit.SECONDS))
                        if (schedule == "failure-first") { badGo.countDown(); bad.get(3, TimeUnit.SECONDS); goodGo.countDown() }
                        else { goodGo.countDown(); good.get(3, TimeUnit.SECONDS); eq(id, "no-idle-with-active-peer", SummaryState.BUSY, r.summary.decoded()?.state); badGo.countDown() }
                        runCatching { good.get(3, TimeUnit.SECONDS) }; bad.get(3, TimeUnit.SECONDS)
                        noIdleAfterPoison(id, r)
                    } finally { goodGo.countDown(); badGo.countDown(); pool.shutdownNow() }
                }
            }
            add("concurrent-registry-two-instances", "D17a-concurrent", "whole-registry-lines-same-lifetime") { id ->
                val events = java.util.concurrent.CopyOnWriteArrayList<String>()
                val io = RecordingJournalIO(events); val process = PurityProcessScope(D17Inputs.LIFETIME)
                val sharedSummary = RecordingSummary(events)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val gate = CountDownLatch(1)
                    // Detect overlap BEFORE the IO double's own map lock can serialize it away.
                    // A second intact startup lock may protect the same invariant: that is protected survival.
                    io.beforeWrite = { file, _ -> if (file == "registry.jsonl") TimeUnit.MILLISECONDS.sleep(100) }
                    val jobs = (1..2).map { n -> pool.submit {
                        val identity = D17Inputs.identity.copy(instanceId = n.toString().padStart(32, '0'))
                        val journal = S5PurityJournal(io, PurityMode.Supported(sharedSummary) { identity }, Fatal { throw SimulatedExit(it) }, process)
                        gate.await(); journal.registerBeforeBind(D17Inputs.context)
                    } }
                    gate.countDown(); jobs.forEach { it.get(3, TimeUnit.SECONDS) }
                    eq(id, "registry-lines-preserved", 2, io.lines("register").size)
                    eq(id, "registry-append-serialized-by-journal", 1, io.maxRegistryInFlight.get())
                    eq(id, "distinct-instance-files", 2, io.snapshot().keys.count { it.startsWith("instance-") })
                    eq(id, "same-lifetime-negative-for-python-C2", setOf(D17Inputs.LIFETIME), io.lines("register").map { it["lifetimeId"]!!.jsonPrimitive.content }.toSet())
                } finally { pool.shutdownNow() }
            }

            for (mode in listOf("success-then-overflow", "prepare-zero", "prepare-partial", "prepare-fsync", "commit-zero", "commit-partial", "commit-fsync", "kill-before-counter", "kill-after-counter", "kill-after-commit")) {
                add("limit-$mode", "D17a-limit", "max-boundary-no-wrap-reset") { id ->
                    D17Rig().use { r ->
                        r.register(); r.journal.seedForTest(Int.MAX_VALUE - 1)
                        eq(id, "actual-atomic-int-seam", Int.MAX_VALUE - 1, r.counter.get())
                        eq(id, "precondition-summary-counter-seam", Int.MAX_VALUE - 1, r.summary.decoded()?.counter)
                        if (mode.startsWith("prepare-") || mode.startsWith("commit-")) {
                            val parts = mode.split('-'); r.io.fault = IOFault(parts[0], WriteFailure.valueOf(parts[1].uppercase()))
                            fatal(id, r, 86) { r.journal.classifiedCallback { r.journal.recordUnexpected("GET /MAX ") } }
                            yes(id, "no-counter-wrap", r.counter.get() in (Int.MAX_VALUE - 1)..Int.MAX_VALUE); noIdleAfterPoison(id, r)
                        } else if (mode.startsWith("kill-")) {
                            val stop = when (mode) { "kill-before-counter" -> PurityPoint.BEFORE_COUNTER; "kill-after-counter" -> PurityPoint.AFTER_COUNTER; else -> PurityPoint.COMMIT_SYNCED }
                            var frozen: FrozenD17? = null
                            r.hook = { if (it == stop) { frozen = r.freeze(); throw SimulatedKill() } }
                            try { r.journal.classifiedCallback { r.journal.recordUnexpected("GET /MAX ") } } catch (_: SimulatedKill) { }
                            yes(id, "max-kill-reached", frozen != null)
                            eq(id, "max-kill-incomplete-busy", SummaryState.BUSY, frozen!!.summary?.state)
                        } else {
                            r.journal.classifiedCallback { r.journal.recordUnexpected("GET /MAX ") }
                            eq(id, "last-increment-to-max", Int.MAX_VALUE, r.counter.get())
                            val prepares = r.io.lines("prepare").size
                            fatal(id, r, 86) { r.journal.classifiedCallback { r.journal.recordUnexpected("GET /overflow ") } }
                            eq(id, "no-prepare-on-overflow", prepares, r.io.lines("prepare").size)
                            eq(id, "no-wrap-reset", Int.MAX_VALUE, r.counter.get()); noIdleAfterPoison(id, r)
                        }
                    }
                }
            }
            add("limit-active-max", "D17a-limit", "active-overflow-never-wraps") { id ->
                D17Rig().use { r -> r.register(); r.journal.seedForTest(0, Int.MAX_VALUE)
                    eq(id, "precondition-active-busy-seam", SummaryState.BUSY, r.summary.decoded()?.state)
                    var ran = false; fatal(id, r, 86) { r.journal.classifiedCallback { ran = true } }
                    yes(id, "block-zero-at-active-max", !ran); eq(id, "active-not-wrapped", Int.MAX_VALUE, r.journal.snapshot().active); noIdleAfterPoison(id, r)
                }
            }

            for (state in AttemptState.entries) add("context-${state.wire}", "D17a-context", "exact-common-fields-and-null") { id ->
                val context = if (state == AttemptState.OK) D17Inputs.context else AttemptContext(state, null, null, null)
                D17Rig(context).use { r ->
                    r.register(); val register = r.io.lines("register").single(); val begin = r.io.lines("begin").single()
                    eq(id, "register-begin-common-fields", register.filterKeys { it != "kind" }, begin.filterKeys { it != "kind" })
                    eq(id, "exact-register-keys", setOf("kind", "schema", "instanceId", "lifetimeId", "attemptId", "runnerNonce", "attemptState", "pid", "processName", "processStartWallMs", "userId", "packageUid", "bootId"), register.keys)
                    eq(id, "attempt-state", state.wire, register["attemptState"]!!.jsonPrimitive.content)
                    if (state != AttemptState.OK) {
                        for (field in listOf("attemptId", "runnerNonce", "bootId")) eq(id, "null-$field", JsonNull, register[field])
                        eq(id, "diagnostic-zero-nonce", Summary64Codec.ZERO_ID, r.summary.decoded()?.runnerNonce)
                        yes(id, "null-context-never-complete", !r.localComplete(0))
                    }
                }
            }
            val malformed = linkedMapOf(
                "duplicate" to D17Inputs.contextBytes.decodeToString().replace("\"schema\":2", "\"schema\":2,\"schema\":2"),
                "unknown" to D17Inputs.contextBytes.decodeToString().replace("\"schema\":2", "\"schema\":2,\"extra\":0"),
                "type" to D17Inputs.contextBytes.decodeToString().replace("\"schema\":2", "\"schema\":\"2\""),
                "bool" to D17Inputs.contextBytes.decodeToString().replace("\"schema\":2", "\"schema\":true"),
                "decimal" to D17Inputs.contextBytes.decodeToString().replace("\"schema\":2", "\"schema\":2.0"),
                "exponent" to D17Inputs.contextBytes.decodeToString().replace("\"schema\":2", "\"schema\":2e0"),
                "nonfinite" to D17Inputs.contextBytes.decodeToString().replace("\"schema\":2", "\"schema\":NaN"),
                "truncated" to D17Inputs.contextBytes.decodeToString().dropLast(2),
                "zero-nonce" to D17Inputs.contextBytes.decodeToString().replace(D17Inputs.NONCE, Summary64Codec.ZERO_ID),
                "uppercase-nonce" to D17Inputs.contextBytes.decodeToString().replace(D17Inputs.NONCE, D17Inputs.NONCE.uppercase()),
                "bad-boot" to D17Inputs.contextBytes.decodeToString().replace(D17Inputs.context.bootId!!, "bad"),
                "bad-hash" to D17Inputs.contextBytes.decodeToString().replace("a".repeat(64), "a".repeat(63)),
                "missing-field" to D17Inputs.contextBytes.decodeToString().replace("\"schema\":2,", ""),
                "no-lf" to D17Inputs.contextBytes.decodeToString().trimEnd(),
                "blank-line" to (D17Inputs.contextBytes.decodeToString() + "\n"),
                "null" to "null\n"
            )
            for ((kind, raw) in malformed) add("context-invalid-$kind", "D17a-context", "invalid-normalizes-null") { id ->
                val context = AttemptContext.read(raw.encodeToByteArray())
                eq(id, "invalid-state", AttemptState.INVALID, context.attemptState)
                eq(id, "invalid-null-attribution", listOf(null, null, null), listOf(context.attemptId, context.runnerNonce, context.bootId))
            }
            add("context-read-missing", "D17a-context", "missing-normalizes-null") { id -> eq(id, "missing-state", AttemptState.MISSING, AttemptContext.read(null).attemptState) }
            add("context-read-ok", "D17a-context", "strict-normal-context") { id -> eq(id, "all-context-fields", D17Inputs.context, AttemptContext.read(D17Inputs.contextBytes)) }
            add("context-provider-boundary", "D17a-context", "provider-absent-null-other-invalid") { id ->
                val dir = java.nio.file.Files.createTempDirectory("s5ctx")
                val locked = dir.resolve("locked")
                // A provider exception (the pre-fix behaviour) is a contract failure, not a precondition.
                fun provider(p: java.nio.file.Path) = runCatching { readAttemptContextBytes(p) }.getOrElse { throw AssertionError(message(id, "provider-never-throws"), it) }
                fun state(p: java.nio.file.Path) = AttemptContext.read(provider(p)).attemptState
                try {
                    val path = dir.resolve("attempt.json")
                    eq(id, "absent-null", null, provider(path))
                    java.nio.file.Files.write(path, D17Inputs.contextBytes)
                    yes(id, "regular-exact-bytes", provider(path)?.contentEquals(D17Inputs.contextBytes) == true)
                    java.nio.file.Files.write(path, ByteArray(0))
                    eq(id, "empty-regular-invalid", AttemptState.INVALID, state(path))
                    java.nio.file.Files.delete(path)
                    val real = java.nio.file.Files.write(dir.resolve("real.json"), D17Inputs.contextBytes)
                    java.nio.file.Files.createSymbolicLink(path, real)
                    eq(id, "symlink-not-followed-invalid", AttemptState.INVALID, state(path))
                    java.nio.file.Files.delete(path); java.nio.file.Files.createDirectory(path)
                    eq(id, "directory-invalid", AttemptState.INVALID, state(path))
                    java.nio.file.Files.delete(path); java.nio.file.Files.write(path, D17Inputs.contextBytes)
                    yes(id, "precondition-unreadable-file", path.toFile().setReadable(false, false) && !path.toFile().canRead())
                    eq(id, "read-failure-invalid", AttemptState.INVALID, state(path))
                    path.toFile().setReadable(true, false)
                    java.nio.file.Files.createDirectory(locked)
                    yes(id, "precondition-locked-parent", locked.toFile().setExecutable(false, false) && locked.toFile().setReadable(false, false))
                    eq(id, "lookup-denied-invalid", AttemptState.INVALID, state(locked.resolve("attempt.json")))
                } finally {
                    locked.toFile().setReadable(true, false); locked.toFile().setExecutable(true, false)
                    dir.toFile().deleteRecursively()
                }
                // The provider's INVALID bytes reach the journal as an invalid, never-complete context.
                D17Rig(AttemptContext.read(byteArrayOf())).use { r ->
                    r.register(); val register = r.io.lines("register").single()
                    eq(id, "provider-invalid-register", AttemptState.INVALID.wire, register["attemptState"]!!.jsonPrimitive.content)
                    eq(id, "provider-invalid-zero-nonce", Summary64Codec.ZERO_ID, r.summary.decoded()?.runnerNonce)
                    yes(id, "provider-invalid-never-complete", !r.localComplete(0))
                }
                val modules = File("src/s5Benchmark/java/com/jay/fxi/s5fixture/S5FixtureModules.kt").readText()
                yes(id, "real-provider-uses-boundary", "override val contextBytes: ByteArray? get() = readAttemptContextBytes(" in modules)
            }
            add("context-bind-order", "D17a-context", "busy-register-begin-idle-bind") { id ->
                D17Rig().use { r ->
                    r.start()
                    val expected = listOf("summary:returned:BUSY:0", "sync:register", "sync:begin", "summary:returned:IDLE:0", "bound")
                    val positions = expected.map { r.events.indexOf(it) }
                    yes(id, "all-startup-steps-reached", positions.all { it >= 0 })
                    yes(id, "bind-after-idle-normal-return", positions.zipWithNext().all { (a, b) -> a < b })
                }
            }
            val identity = D17Inputs.identity
            val begin = JournalLineCodec.registration("begin", identity, D17Inputs.context).decodeToString()
            val prepare = JournalLineCodec.transaction("prepare", identity, D17Inputs.context, 1, "GET /strict ").decodeToString()
            val commit = JournalLineCodec.transaction("commit", identity, D17Inputs.context, 1).decodeToString()
            val invalidJournals = linkedMapOf(
                "duplicate-key" to begin.replace("\"schema\":2", "\"schema\":2,\"schema\":2"),
                "unknown-key" to begin.replace("\"schema\":2", "\"schema\":2,\"extra\":0"),
                "unknown-kind" to begin.replace("\"begin\"", "\"alien\""),
                "wrong-type" to begin.replace("\"pid\":1234", "\"pid\":\"1234\""),
                "negative-pid" to begin.replace("\"pid\":1234", "\"pid\":-1"),
                "nonfinite" to begin.replace("\"pid\":1234", "\"pid\":NaN"),
                "empty" to "", "blank-line" to (begin + "\n"), "truncated" to begin.dropLast(2),
                "no-lf" to begin.trimEnd(), "second-begin" to (begin + begin),
                "prepare-only" to (begin + prepare), "commit-only" to (begin + commit),
                "crossed-pairs" to (begin + prepare + prepare.replace("\"seq\":1", "\"seq\":2") + commit + commit.replace("\"seq\":1", "\"seq\":2")),
                "seq-zero" to (begin + prepare.replace("\"seq\":1", "\"seq\":0") + commit.replace("\"seq\":1", "\"seq\":0")),
                "seq-gap" to (begin + prepare.replace("\"seq\":1", "\"seq\":2") + commit.replace("\"seq\":1", "\"seq\":2")),
                "seq-bool" to (begin + prepare.replace("\"seq\":1", "\"seq\":true") + commit),
                "seq-decimal" to (begin + prepare.replace("\"seq\":1", "\"seq\":1.0") + commit),
                "seq-exponent" to (begin + prepare.replace("\"seq\":1", "\"seq\":1e0") + commit),
                "seq-overflow" to (begin + prepare.replace("\"seq\":1", "\"seq\":2147483648") + commit),
                "transaction-attempt-state" to (begin + prepare.replace("\"schema\":2", "\"schema\":2,\"attemptState\":\"ok\"") + commit)
            )
            for ((kind, raw) in invalidJournals) add("context-journal-$kind", "D17a-context", "strict-jsonl-grammar") { id ->
                rejected(id, "strict-jsonl-grammar") { JournalLineCodec.readInstance(raw.encodeToByteArray()) }
            }
            add("context-journal-valid-pair", "D17a-context", "strict-jsonl-positive-pair") { id ->
                eq(id, "valid-pair-three-lines", 3, JournalLineCodec.readInstance((begin + prepare + commit).encodeToByteArray()).size)
            }
            add("context-journal-privacy", "D17a-context", "diagnostic-no-frame-header-body") { id ->
                D17Rig().use { r ->
                    val (socket, frames) = r.socket(id)
                    socket.send("""{"type":"untrusted-type-marker","request_id":"untrusted-id-marker","body":"untrusted-body-marker"}""")
                    socket.send("ping")
                    yes(id, "precondition-frame-processed", frames.frames.poll(3, TimeUnit.SECONDS)?.contains("pong") == true)
                    val raw = r.io.snapshot().values.joinToString("") { it.decodeToString() }
                    eq(id, "diagnostic-record-created", 1, r.io.lines("prepare").size)
                    for (marker in listOf("untrusted-type-marker", "untrusted-id-marker", "untrusted-body-marker")) yes(id, "no-raw-ws-$marker", marker !in raw)
                }
            }
            add("context-exclusive-restart", "D17a-context", "restart-never-truncates") { id ->
                D17Rig().use { r -> r.register(); val before = r.io.snapshot()
                    val restart = S5PurityJournal(r.io, PurityMode.Supported(r.summary) { D17Inputs.identity }, r.fatal, r.process)
                    fatal(id, r, 87) { restart.registerBeforeBind(r.context) }
                    for ((name, bytes) in before.filterKeys { it.startsWith("instance-") }) yes(id, "exclusive-file-bytes-preserved", r.io.snapshot().getValue(name).contentEquals(bytes))
                }
            }
            add("context-single-summary-writer", "D17a-context", "no-other-summary-writer") { id ->
                val root = File("src")
                val writers = root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") && !it.path.contains("/test") }
                    .filter { it.readText().contains("setProcessStateSummary(") }.map { it.name }.toSet()
                eq(id, "one-real-platform-writer", setOf("S5PurityJournal.kt"), writers)
            }

            for (sdk in listOf(26, 29, 30, 32, 33, 36)) add("api-app-$sdk", "D17a-api", "actual-module-server-runtime-branch") { id ->
                D17Rig(sdk = sdk).use { r ->
                    val adapterCalls = AtomicInteger()
                    val runtime = object : S5PurityRuntime {
                        override val sdk = sdk
                        override val io = r.io
                        override val process = r.process
                        override val contextBytes = D17Inputs.contextBytes
                        override fun createApi30(): PurityMode.Supported { adapterCalls.incrementAndGet(); return PurityMode.Supported(r.summary) { D17Inputs.identity } }
                        override fun diagnostic(line: String) { r.diagnostics += line }
                    }
                    // The real DI provider is the subject. A test-only select() clone cannot satisfy this case.
                    val boundJournal = S5FixtureEndpointsModule.provideS5PurityJournal(runtime, r.fatal)
                    val server = S5FixtureServer(load = { File("src/benchmark/assets/s5_4/$it").readBytes() }, report = {}, purity = boundJournal, attemptContext = D17Inputs.context, fatal = r.fatal)
                    try {
                        val base = server.awaitEndpoints().restBaseUrl
                        fun get(path: String) = r.client.newCall(okhttp3.Request.Builder().url(base + path).build()).execute().use { it.code }
                        eq(id, "D16-ok-retained", 200, get("api/entitlements"))
                        eq(id, "D16-unexpected-retained", 404, get("api/unknown"))
                        eq(id, "actual-counter-retained", 1, server.unexpectedCount)
                        if (sdk < 30) {
                            eq(id, "forbidden-adapter-load-zero", 0, adapterCalls.get())
                            yes(id, "explicit-unsupported-mode", boundJournal.mode == PurityMode.UnsupportedApi(sdk))
                            eq(id, "diagnostic-once", listOf("D17_UNSUPPORTED_API"), r.diagnostics.toList())
                            eq(id, "unsupported-no-os-evidence", null, r.summary.current)
                            eq(id, "unsupported-no-registration", 0, r.io.lines("register").size)
                        } else {
                            eq(id, "supported-adapter-reached", 1, adapterCalls.get())
                            yes(id, "supported-guard-journal-reached", r.io.lines("commit").size == 1 && r.summary.decoded()?.counter == 1)
                        }
                    } finally { server.close() }
                }
            }
            for (sdk in listOf(30, 32, 33)) add("api-supported-exception-$sdk", "D17a-api", "no-unsupported-fallback") { id ->
                var called = 0
                try { S5PurityPlatform.select(sdk) { called++; throw IllegalStateException("supported adapter failure") }; throw AssertionError(message(id, "must-propagate-supported-error")) }
                catch (expected: IllegalStateException) { eq(id, "supported-error-no-fallback", "supported adapter failure", expected.message) }
                eq(id, "adapter-once", 1, called)
            }
            for (sdk in listOf(26, 29)) for (op in listOf("absent", "inventory", "exits")) add("api-probe-$sdk-$op", "D17a-api", "unsupported-before-api-load") { id ->
                var apiLoads = 0; var rejected = false
                val runtime = object : ProbeRuntime {
                    override val sdk = sdk
                    override fun unsupported(): Nothing { throw SimulatedExit(64) }
                    override fun runApi30(args: Array<String>) { apiLoads++ }
                }
                try { S5PurityProbe.run(arrayOf(op, "0", "attempt-001", D17Inputs.NONCE, "command-1"), runtime) }
                catch (exit: SimulatedExit) { rejected = exit.code != 0 }
                yes(id, "nonzero-no-success-json", rejected); eq(id, "forbidden-api-loading-zero", 0, apiLoads)
            }
            add("api-probe-main-guard", "D17a-api", "real-main-delegates-to-guard") { id ->
                val source = File("src/s5Benchmark/java/com/jay/fxi/s5fixture/S5PurityJournal.kt").readText()
                val main = source.substringAfter("fun main(args: Array<String>)").substringBefore("internal fun run")
                yes(id, "main-runtime-sdk-before-ops", "run(args," in main && "Build.VERSION.SDK_INT" in source)
            }
            add("api-probe-absent-boundary", "D17a-api", "absent-only-nosuchfile-false") { id ->
                val dir = java.nio.file.Files.createTempDirectory("s5probe")
                val locked = dir.resolve("locked")
                try {
                    eq(id, "missing-false", false, purityRootExists(dir.resolve("s5purity")))
                    eq(id, "directory-true", true, purityRootExists(dir))
                    eq(id, "regular-true", true, purityRootExists(java.nio.file.Files.write(dir.resolve("file"), ByteArray(1))))
                    eq(id, "dangling-symlink-true", true, purityRootExists(java.nio.file.Files.createSymbolicLink(dir.resolve("dangling"), dir.resolve("nowhere"))))
                    java.nio.file.Files.createDirectory(locked)
                    yes(id, "precondition-locked-parent", locked.toFile().setExecutable(false, false) && locked.toFile().setReadable(false, false))
                    val error = runCatching { purityRootExists(locked.resolve("s5purity")) }.exceptionOrNull()
                    yes(id, "lookup-error-not-false", error is java.nio.file.AccessDeniedException)
                } finally {
                    locked.toFile().setReadable(true, false); locked.toFile().setExecutable(true, false)
                    dir.toFile().deleteRecursively()
                }
                val source = File("src/s5Benchmark/java/com/jay/fxi/s5fixture/S5PurityJournal.kt").readText()
                yes(id, "real-probe-uses-boundary", "\"absent\" -> buildJsonObject { put(\"exists\", purityRootExists(root)) }" in source)
            }
            add("api-probe-static-main", "D17a-api", "exact-public-static-entry-signature") { id ->
                val main = S5PurityProbe::class.java.getDeclaredMethod("main", Array<String>::class.java)
                yes(id, "public-static-main", java.lang.reflect.Modifier.isPublic(main.modifiers) && java.lang.reflect.Modifier.isStatic(main.modifiers))
                eq(id, "main-return-void", Void.TYPE, main.returnType)
                // This host signature check never substitutes for D17-pkg's final minified dex assertion.
            }
            for (sdk in listOf(30, 32)) add("api-identity-$sdk", "D17a-api", "application-name-no-api33-call") { id ->
                val called = mutableListOf<String>()
                val calls = object : S5PurityPlatform.IdentityCalls {
                    override fun applicationProcessName(): String { called += "Application.getProcessName"; return "com.jay.fxi" }
                    override fun processMyProcessName33(): String = throw AssertionError(message(id, "forbidden-api33-call"))
                    override fun pid() = 1234
                    override fun uid() = 10123
                    override fun userId(uid: Int) = 0
                    override fun processStartWallMs() = 1_800_000_000_000L
                    override fun newInstanceId() = D17Inputs.INSTANCE
                }
                var forbiddenRejected = false
                try { calls.processMyProcessName33() } catch (_: AssertionError) { forbiddenRejected = true }
                yes(id, "model-api33-call-forbidden", forbiddenRejected)
                val identity = S5PurityPlatform.Api30Impl(null, calls).identity(PurityProcessScope(D17Inputs.LIFETIME))
                eq(id, "real-identity-adapter-fields", D17Inputs.identity, identity)
                eq(id, "application-name-adapter-reached", listOf("Application.getProcessName"), called)
                val source = File("src/s5Benchmark/java/com/jay/fxi/s5fixture/S5PurityJournal.kt").readText()
                // Host source obligation complements runtime provider tests. Device API execution remains D17-pkg/device work.
                yes(id, "Application-name-real-call", Regex("(?:android\\.app\\.)?Application\\.getProcessName\\(\\)").containsMatchIn(source.lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")))
                yes(id, "Process-api33-call-zero", !Regex("(?<![A-Za-z])Process\\.myProcessName\\(\\)").containsMatchIn(source.lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")))
            }

            for (scenario in listOf("overlap", "poison-sticky", "close-active", "closed-entry", "active-max", "empty-leave")) add("state-$scenario", "D17a-concurrent", "pure-admission-invariants") { id ->
                when (scenario) {
                    "overlap" -> { val two = AdmissionState().admitted().admitted(); yes(id, "active-busy", !two.succeeded().mayPublishIdle); yes(id, "last-success-idle", two.succeeded().succeeded().mayPublishIdle) }
                    "poison-sticky" -> { val poisoned = AdmissionState().admitted().failed(); rejected(id, "poison-leave-rejected") { poisoned.succeeded() }; rejected(id, "poison-entry-rejected") { poisoned.admitted() } }
                    "close-active" -> yes(id, "close-active-poisons", AdmissionState().admitted().close().poisoned)
                    "closed-entry" -> rejected(id, "closed-entry-rejected") { AdmissionState().close().admitted() }
                    "active-max" -> rejected(id, "active-max-rejected") { AdmissionState(Int.MAX_VALUE).admitted() }
                    else -> rejected(id, "empty-leave-rejected") { AdmissionState().succeeded() }
                }
            }
            for (state in listOf("active", "idle")) add("concurrent-close-$state", "D17a-concurrent", "close-never-forces-idle") { id ->
                D17Rig().use { r ->
                    r.register()
                    if (state == "active") r.journal.seedForTest(0, 1)
                    val before = r.events.size
                    r.journal.close()
                    yes(id, "closed-admission", r.journal.snapshot().closed)
                    if (state == "active") {
                        yes(id, "cancel-active-poisons", r.journal.snapshot().poisoned)
                        yes(id, "no-forced-idle", r.events.drop(before).none { it.startsWith("summary:call:IDLE") })
                    }
                }
            }
            return inputs.map { arrayOf<Any>(it) }
        }
    }
}
