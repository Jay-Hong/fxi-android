package com.jay.fxi.macrobenchmark

/**
 * S5-4 E slice 5: the behavior record the S5 gate reads (tools/performance/benchmark_gate.py), kept free of Android so the
 * host harness (macrobenchmark/hostTest) compiles and tests this very file.
 *
 * Slots: the gate has eight, warmup 0-2 then measured 0-4. Macrobenchmark 1.4.1 with Partial(warmupIterations = 3) and WARM
 * calls setup and measure nine times: three compile warmups with `iteration` null, one unmeasured WARM pass with `iteration`
 * null, then the measured iterations 0-4 (s5_research_macro_lifecycle.md). The fourth null pair is the WARM pass and has no
 * slot; a failure in it is measured 0's setup failure, so its observations never stand in for a measured one.
 */
internal object S5Behavior {
    const val SCHEMA_VERSION = 1
    const val WARMUPS = 3
    const val MEASURED = 5
    const val SLOTS = WARMUPS + MEASURED
    val RUN_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    const val RUN_ID_ARGUMENT = "s5.runId"

    /**
     * The run id from the instrumentation arguments ([read] is Bundle.getString), or null when the record is off. With one, the
     * run must be real: no dry run, compilation on, no profiling phase - only that run calls setup and measure as the slots assume.
     */
    fun runId(read: (String) -> String?): String? {
        val runId = read(RUN_ID_ARGUMENT) ?: return null
        check(RUN_ID.matches(runId)) { "$RUN_ID_ARGUMENT is not a gate run id" }
        check(read("androidx.benchmark.dryRunMode.enable")?.toBoolean() != true) { "a behavior record needs a real run, not a dry run" }
        check(read("androidx.benchmark.compilation.enabled")?.toBoolean() != false) { "a behavior record needs compilation" }
        val profiling = read("androidx.benchmark.profiling.mode")
        check(profiling == null || profiling.trim().lowercase() == "none") { "a behavior record needs no profiling phase" }
        return runId
    }

    const val CHART = "graph_v2:inline:chart"
    const val PAGE_SCROLL = "premium_page_scroll"

    /** A journey's id, its lookups and its expected states, as the gate's EXPECTED_BEHAVIOR (the host harness compares them). */
    enum class Journey(
        val id: String,
        val lookups: List<String>,
        val before: Map<String, Any>,
        val after: Map<String, Any>,
        val during: String?
    ) {
        TAB_ENTRY("tab-entry", listOf("premium_tab_TETHER", CHART), mapOf("tab" to "USD"), oneDay("na"), null),
        PERIOD_SWITCH(
            "period-switch", listOf("period_tab:1d", CHART),
            mapOf("tab" to "TETHER", "period" to "1w", "ready" to true, "seriesShown" to 5, "scroll" to "na"), oneDay("na"), null
        ),
        SCROLL("scroll", listOf(PAGE_SCROLL, CHART), oneDay("top"), oneDay("top"), "moved")
    }

    private fun oneDay(scroll: String) = mapOf("tab" to "TETHER", "period" to "1d", "ready" to true, "seriesShown" to 11, "scroll" to scroll)

    enum class Stage(val wire: String) { SETUP("setup"), LOOKUP("lookup"), BEFORE("before"), ACTION("action"), DURING("during"), AFTER("after") }

    /** One observed screen state; a null field was not observed. */
    data class State(val tab: String?, val period: String?, val ready: Boolean?, val seriesShown: Int?, val scroll: String?) {
        fun asMap(): Map<String, Any?> = linkedMapOf("tab" to tab, "period" to period, "ready" to ready, "seriesShown" to seriesShown, "scroll" to scroll)
        val complete: Boolean get() = asMap().values.none { it == null }
    }

    /** What one slot observed, filled in stage order. */
    class Observation(val journey: Journey) {
        val lookups: MutableMap<String, Int?> = linkedMapOf<String, Int?>().apply { journey.lookups.forEach { put(it, null) } }
        var lookupsTouched = false
        var before: State? = null
        var during: String? = null
        var after: State? = null
        var actionDone = false

        /** The number of objects a lookup of [tag] found; null when that read itself failed. */
        fun lookup(tag: String, found: Int?) {
            require(tag in lookups) { "$tag is not a lookup of ${journey.id}" }
            lookups[tag] = found
            lookupsTouched = true
        }
    }

    class Failure(val slot: Int, val stage: Stage, val kind: String, val reason: String)

    /**
     * Follows setup and measure calls by `scope.iteration` and refuses any order other than three warmups, the WARM pass and
     * measured 0-4. Used only with a run id; without one (a 4d dry run) the journey keeps nothing.
     */
    class Tracker(val journey: Journey) {
        private val slots = arrayOfNulls<Observation>(SLOTS)
        private var nullPairs = 0
        private var lastMeasured = -1
        private var current: Int? = null
        private var inWarmPass = false
        private var warmPassObservation: Observation? = null
        private var currentStage: Stage? = null
        /** Set for good once any order is refused: such a run gets no record at all, success or failure (plan r2 §1a). */
        var slotOrderInvalid = false
            private set
        var failure: Failure? = null
            private set

        /** The observation for the setup that starts now; the WARM pass gets one that no slot keeps. */
        fun beginSetup(iteration: Int?): Observation {
            if (current != null) refuse("a setup while slot $current is open")
            currentStage = Stage.SETUP
            if (iteration == null) {
                nullPairs++
                return when {
                    nullPairs <= WARMUPS -> open(nullPairs - 1, warmPass = false)
                    nullPairs == WARMUPS + 1 -> open(WARMUPS, warmPass = true)
                    else -> refuse("null-iteration call $nullPairs, after the WARM pass")
                }
            }
            if (iteration == 0 && nullPairs != WARMUPS + 1) refuse("measured 0 after $nullPairs null-iteration calls, expected ${WARMUPS + 1}")
            if (iteration != lastMeasured + 1 || iteration !in 0 until MEASURED) refuse("measured $iteration after $lastMeasured")
            lastMeasured = iteration
            return open(WARMUPS + iteration, warmPass = false)
        }

        /** The open slot's measure. */
        fun beginMeasure(iteration: Int?): Observation {
            val slot = current ?: refuse("a measure without its setup")
            val expected = if (slot < WARMUPS || inWarmPass) null else slot - WARMUPS
            if (iteration != expected) refuse("measure iteration $iteration in slot $slot")
            currentStage = Stage.BEFORE
            return if (inWarmPass) checkNotNull(warmPassObservation) else checkNotNull(slots[slot])
        }

        fun stage(stage: Stage) {
            currentStage = stage
        }

        /** Closes the open slot; a recorded slot must be fully observed. */
        fun endMeasure() {
            val slot = current ?: refuse("a measure end without its setup")
            if (!inWarmPass) check(slotComplete(checkNotNull(slots[slot]))) { "slot $slot ended with an incomplete observation" }
            current = null
            inWarmPass = false
            warmPassObservation = null
        }

        /**
         * Records the failure of the open slot and stage, once, or of the next slot's setup when none is open. A failure in the
         * WARM pass is measured 0's setup failure with nothing observed. Null when every slot already completed, or when the
         * call order was refused (no record then).
         */
        fun fail(error: Throwable): Failure? {
            if (slotOrderInvalid) return null
            failure?.let { return it }
            val reason0 = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.name
            val open = current
            val result = when {
                open != null && inWarmPass -> {
                    slots[WARMUPS] = Observation(journey)
                    Failure(WARMUPS, Stage.SETUP, OPERATION, "WARM pass ${currentStage?.wire ?: "setup"}: $reason0")
                }
                open != null -> {
                    val stage = currentStage ?: Stage.SETUP
                    Failure(open, stage, kindOf(stage, slots[open]), reason0)
                }
                else -> nextSlot()?.let { slot ->
                    slots[slot] = Observation(journey)
                    Failure(slot, Stage.SETUP, OPERATION, reason0)
                }
            }
            failure = result
            return result
        }

        /** Whether the run placed exactly three warmups, the WARM pass and five measured slots, each fully observed. */
        val complete: Boolean
            get() = !slotOrderInvalid && failure == null && current == null && nullPairs == WARMUPS + 1 && lastMeasured == MEASURED - 1 &&
                slots.all { it != null && slotComplete(it) }

        fun observation(slot: Int): Observation? = slots[slot]

        private fun nextSlot(): Int? = when {
            nullPairs < WARMUPS -> nullPairs
            nullPairs == WARMUPS -> WARMUPS
            lastMeasured < MEASURED - 1 -> WARMUPS + lastMeasured + 1
            else -> null
        }

        private fun open(slot: Int, warmPass: Boolean): Observation {
            current = slot
            inWarmPass = warmPass
            val observation = Observation(journey)
            if (warmPass) warmPassObservation = observation else slots[slot] = observation
            return observation
        }

        private fun refuse(why: String): Nothing {
            slotOrderInvalid = true
            throw IllegalStateException("S5 behavior slots: $why")
        }

        private fun slotComplete(o: Observation): Boolean =
            o.actionDone && o.lookups.values.none { it == null } && o.before?.complete == true && o.after?.complete == true &&
                (journey.during == null || o.during != null)

        /** expectation-mismatch only when the stage's own recorded fields show it; otherwise operation-error (plan r2 §1c). */
        private fun kindOf(stage: Stage, o: Observation?): String = when (stage) {
            Stage.LOOKUP -> if (o != null && o.lookups.values.any { it != null && it != 1 }) MISMATCH else OPERATION
            Stage.BEFORE -> if (mismatch(o?.before, journey.before)) MISMATCH else OPERATION
            Stage.AFTER -> if (mismatch(o?.after, journey.after)) MISMATCH else OPERATION
            Stage.DURING -> if (o?.during != null && o.during != journey.during) MISMATCH else OPERATION
            Stage.SETUP, Stage.ACTION -> OPERATION
        }

        private fun mismatch(state: State?, expected: Map<String, Any>): Boolean {
            val observed = state?.asMap() ?: return false
            return expected.any { (key, value) -> observed[key] != null && observed[key] != value }
        }
    }

    const val MISMATCH = "expectation-mismatch"
    const val OPERATION = "operation-error"

    /** The success record; call only when [Tracker.complete]. */
    fun successRecord(runId: String, tracker: Tracker): Map<String, Any?> {
        check(tracker.complete) { "the run did not fill its eight slots" }
        return linkedMapOf(
            "schemaVersion" to SCHEMA_VERSION, "runId" to runId, "journey" to tracker.journey.id,
            "iterations" to (0 until SLOTS).map { slot -> slotMap(slot, checkNotNull(tracker.observation(slot)), tracker.journey, null) }
        )
    }

    /** The slice 1a failure record: completed slots before the failure, the failed slot as observed, not-run after it. */
    fun failureRecord(runId: String, tracker: Tracker): Map<String, Any?> {
        val failure = checkNotNull(tracker.failure) { "no failure to record" }
        return linkedMapOf(
            "schemaVersion" to SCHEMA_VERSION, "runId" to runId, "journey" to tracker.journey.id, "status" to "failed",
            "failure" to linkedMapOf("slot" to failure.slot, "stage" to failure.stage.wire, "kind" to failure.kind, "reason" to failure.reason),
            "iterations" to (0 until SLOTS).map { slot ->
                when {
                    slot < failure.slot -> slotMap(slot, checkNotNull(tracker.observation(slot)), tracker.journey, "completed")
                    slot == failure.slot -> slotMap(slot, tracker.observation(slot) ?: Observation(tracker.journey), tracker.journey, "failed",
                        failedAction = failure.stage == Stage.ACTION)
                    else -> notRun(slot, tracker.journey)
                }
            }
        )
    }

    private fun position(slot: Int): Pair<String, Int> = if (slot < WARMUPS) "warmup" to slot else "measured" to slot - WARMUPS

    private fun slotMap(slot: Int, o: Observation, journey: Journey, status: String?, failedAction: Boolean = false): Map<String, Any?> {
        val (phase, index) = position(slot)
        val partial = status == "failed"
        val map = linkedMapOf<String, Any?>("phase" to phase, "index" to index)
        map["lookups"] = if (partial && !o.lookupsTouched) null else LinkedHashMap(o.lookups)
        map["before"] = o.before?.asMap()
        map["after"] = o.after?.asMap()
        if (journey.during != null) map["during"] = o.during?.let { mapOf("scroll" to it) }
        map["action"] = when {
            o.actionDone -> "completed"
            failedAction -> "failed"
            else -> null
        }
        if (status != null) map["status"] = status
        return map
    }

    private fun notRun(slot: Int, journey: Journey): Map<String, Any?> {
        val (phase, index) = position(slot)
        val map = linkedMapOf<String, Any?>("phase" to phase, "index" to index, "lookups" to null, "before" to null, "after" to null)
        if (journey.during != null) map["during"] = null
        map["action"] = null
        map["status"] = "not-run"
        return map
    }
}
