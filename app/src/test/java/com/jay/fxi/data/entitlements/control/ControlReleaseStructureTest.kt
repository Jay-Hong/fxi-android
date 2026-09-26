package com.jay.fxi.data.entitlements.control

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

/** Source and JVM-shape tripwires, not a proof against arbitrary coordinated reflection rewrites. */
class ControlReleaseStructureTest {
    private fun contractFieldNames(type: Class<*>): Set<String> =
        type.declaredFields.filterNot {
            it.name == "\$stable" &&
                it.type == Int::class.javaPrimitiveType &&
                Modifier.isStatic(it.modifiers) &&
                Modifier.isFinal(it.modifiers)
        }.map { it.name }.toSet()

    private val control = "main/java/com/jay/fxi/data/entitlements/control/"
    /**
     * Text of one member, including multi-line expression bodies: from the header through a closing brace at the
     * header's indent, or up to (excluding) the next non-blank line at the header's indent or less (a signature-closing
     * ")" line at that indent does not end the member).
     */
    private fun member(source: String, header: String): String {
        val lines = source.lines(); val at = lines.indexOfFirst { it.trim().startsWith(header) }
        check(at >= 0) { "missing member: $header" }
        val indent = lines[at].takeWhile { it == ' ' }
        var end = at
        for (j in at + 1 until lines.size) {
            val line = lines[j]
            if (line == "$indent}") { end = j; break }
            // A multi-line parameter list closes with ")" at the header indent; that line is still the signature.
            if (line.isNotBlank() && line.takeWhile { it == ' ' }.length <= indent.length && !line.trim().startsWith(")")) break
            end = j
        }
        return lines.subList(at, end + 1).joinToString("\n")
    }
    private fun sources() = SealSourceTripwire.read(SealSourceTripwire.sourceRoot(
        File(checkNotNull(System.getProperty("user.dir"))), System.getProperty("fxi.seal.sourceRoot")))

    @Test fun M09_lifecycleTransitionsHaveOnlyTheOwnerReleaseCaller() {
        val all = sources()
        val ref = all.getValue(control + "ControlStoreResult.kt")
        assertTrue(Modifier.isPrivate(CommandRef::class.java.getDeclaredField("cell").modifiers))
        assertEquals(setOf("beginRelease", "completeRelease"), CommandRef::class.java.declaredMethods
            .map { it.name.substringBefore('$') }.filter { it in setOf("beginRelease", "completeRelease") }.toSet())
        val store = all.getValue(control + "ControlRecordStore.kt")
        assertEquals(1, ref.lineSequence().count { it.trim() == "internal fun beginRelease() {" })
        assertEquals(1, ref.lineSequence().count { it.trim() == "internal fun completeRelease() {" })
        assertEquals(mapOf(control + "ControlStoreResult.kt" to 1, control + "ControlRecordStore.kt" to 1),
            SealSourceTripwire.occurrences(all, "beginRelease"))
        assertEquals(mapOf(control + "ControlStoreResult.kt" to 1, control + "ControlRecordStore.kt" to 1),
            SealSourceTripwire.occurrences(all, "completeRelease"))
        val attempt = store.substringAfter("private suspend fun releaseAttempt(").substringBefore("private fun releaseRejected(")
        val steps = listOf("ControlCommandReleaseDecision.decide", "ControlReleaseCandidate.build",
            "tracked.bindReleaseDescriptor(descriptor)", "tracking.publishPendingRelease(command)",
            "command.beginRelease()", "RecordTransactionDecision.Confirm(candidate, null)",
            "check(ControlReleaseCandidate.hasAbsencePostcondition", "command.completeRelease()", "tracking.finishRelease(tracked)")
        assertTrue(steps.all { attempt.contains(it) })
        assertEquals(steps.map { attempt.indexOf(it) }.sorted(), steps.map { attempt.indexOf(it) })
        // 6-1A: release 2 + termination 2 CAS on the one cell; no other write primitive.
        assertEquals(4, Regex("cell\\.compareAndSet").findAll(ref).count())
        for (write in listOf("cell.set(", "cell.getAndSet(", "cell.lazySet(", "cell.updateAndGet(", "cell.getAndUpdate(",
            "cell.accumulateAndGet(", "cell.getAndAccumulate(", "cell.weakCompareAndSet")) assertFalse(write, ref.contains(write))
        assertFalse(ref.contains("AtomicReference(ControlCommandLifecycle"))
        assertEquals(setOf("beginTermination", "completeTermination"), CommandRef::class.java.declaredMethods
            .map { it.name.substringBefore('$') }.filter { it in setOf("beginTermination", "completeTermination") }.toSet())
        assertEquals(1, ref.lineSequence().count { it.trim() == "internal fun beginTermination() {" })
        assertEquals(1, ref.lineSequence().count { it.trim() == "internal fun completeTermination() {" })
        // 6-1B: exactly one owner-path caller each, inside terminationAttempt.
        assertEquals(mapOf(control + "ControlStoreResult.kt" to 1, control + "ControlRecordStore.kt" to 1),
            SealSourceTripwire.occurrences(all, "beginTermination"))
        assertEquals(mapOf(control + "ControlStoreResult.kt" to 1, control + "ControlRecordStore.kt" to 1),
            SealSourceTripwire.occurrences(all, "completeTermination"))
        assertFalse(all.values.any { it.contains("ControlReleaseFixtures") })
        assertFalse(all.filterKeys { it.startsWith(control) }.values.any {
            it.contains("getDeclaredField") || it.contains("getDeclaredMethod") || it.contains("java.lang.reflect")
        })
    }
    @Test fun M09_lifecycleCellHasNoProductionAccessor() {
        val ref = sources().getValue(control + "ControlStoreResult.kt")
        assertEquals(1, ref.lineSequence().count { it.trim().startsWith("private val cell = AtomicReference(RefCell(ControlCommandLifecycle.RETAINED, ") })
        assertFalse("lifecycle cell must not escape through a getter",
            CommandRef::class.java.declaredMethods.any {
                !Modifier.isPrivate(it.modifiers) &&
                    java.util.concurrent.atomic.AtomicReference::class.java
                        .isAssignableFrom(it.returnType)
            })
    }

    @Test fun M09_descriptorBindingHasOnlyTheValidatedReleaseCaller() {
        val all = sources()
        val tracking = all.getValue(control + "ControlCommandTracking.kt")
        assertEquals(1, tracking.lineSequence().count { it.trim() == "internal fun bindReleaseDescriptor(descriptor: ReleasePendingDescriptor) {" })
        assertEquals(mapOf(control + "ControlCommandTracking.kt" to 1, control + "ControlRecordStore.kt" to 1),
            SealSourceTripwire.occurrences(all, "bindReleaseDescriptor"))
        assertFalse(TrackedControlCommand::class.java.declaredMethods.any { it.name.startsWith("setReleaseDescriptor") })
    }
    @Test fun M07_refHasOnlyIdentityInputsAndSmallTerminalCell() {
        // One cell owns state and body; no separate body backing field (revision 06 §3.6).
        assertEquals(setOf("id", "ownerTrackingLifetimeId", "cell", "diagnostic"),
            contractFieldNames(CommandRef::class.java))
        assertEquals("java.util.concurrent.atomic.AtomicReference<com.jay.fxi.data.entitlements.control.RefCell>",
            CommandRef::class.java.getDeclaredField("cell").genericType.typeName)
        assertEquals(setOf("state", "body"), contractFieldNames(RefCell::class.java))
        assertTrue(RefCell::class.java.declaredFields.filter { it.name in setOf("state", "body") }.all { Modifier.isFinal(it.modifiers) })
        assertEquals(setOf("state", "body"), contractFieldNames(RefView::class.java))
        // 5a adds one replaceable observation, never a growing history or a release authority.
        assertEquals("java.util.concurrent.atomic.AtomicReference<com.jay.fxi.data.entitlements.control.ControlLifecycleDiagnostic>",
            CommandRef::class.java.getDeclaredField("diagnostic").genericType.typeName)
        assertEquals(listOf("RETAINED", "RELEASE_PENDING", "RELEASED", "TERMINATION_PENDING", "TERMINATED"),
            ControlCommandLifecycle.entries.map { it.name })
    }
    @Test fun M08_noReleasedIdReservationAndOnlyConditionalCleanup() {
        assertEquals(setOf("lifetimeId", "evidenceDiscontinuityCount", "commands", "executing", "recoveryWork", "Companion", "collected", "owners"),
            contractFieldNames(ControlCommandTracking::class.java))
        val tracking = sources().getValue(control + "ControlCommandTracking.kt")
        // finishRelease and finishTermination each remove only their exact tracked entry.
        assertEquals(2, tracking.lineSequence().count { it.trim() == "commands.remove(command.id, tracked)" })
        assertEquals(2, Regex("commands\\.remove").findAll(tracking).count())
        assertFalse(tracking.contains("commands.clear"))
        val all = sources()
        for (method in listOf("finishRelease", "publishPendingRelease")) {
            assertEquals(mapOf(control + "ControlCommandTracking.kt" to 1, control + "ControlRecordStore.kt" to 1),
                SealSourceTripwire.occurrences(all, method))
        }
    }
    @Test fun A17_twoRecoverySetsUseOneAtomicCell() {
        val fields = ControlCommandTracking::class.java.declaredFields.filter { it.type == java.util.concurrent.atomic.AtomicReference::class.java }
        assertEquals(setOf("recoveryWork"), fields.map { it.name }.toSet())
        val store = sources().getValue(control + "ControlRecordStore.kt")
        assertFalse("facade must not independently read unresolved", store.contains("tracking.snapshot()"))
        assertFalse(sources().getValue(control + "ControlStoreResult.kt").contains("localPendingReleases: Set<CommandRef> ="))
        assertFalse(sources().getValue(control + "ControlCommandReleaseResult.kt").contains("localPendingReleases: Set<CommandRef> ="))
    }
    @Test fun D2B6_checkpointUsesOneCellViewAfterTheExactLookup() {
        val all = sources()
        val ref = all.getValue(control + "ControlStoreResult.kt")
        val capture = member(ref, "internal fun captureStateAndBody(")
        assertEquals("single cell read", 1, Regex("\\bcell\\.get\\(\\)").findAll(capture).count())
        // Both view fields must come from the one local cell value; no state/body/actions getter re-read. Only these
        // two whitespace-free shapes are accepted (local name free): expression body with let, or a block with one val.
        val shape = capture.replace(Regex("\\s"), "")
        val expression = Regex("internalfuncaptureStateAndBody\\(\\):RefView=cell\\.get\\(\\)\\.let\\{(?:(\\w+)->)?RefView\\((\\w+)\\.state,(\\w+)\\.body\\)\\}")
        val block = Regex("internalfuncaptureStateAndBody\\(\\):RefView\\{val(\\w+)=cell\\.get\\(\\);?returnRefView\\((\\w+)\\.state,(\\w+)\\.body\\)\\}")
        val e = expression.matchEntire(shape); val b = block.matchEntire(shape)
        assertTrue("capture shape: $shape", when {
            e != null -> { val local = e.groupValues[1].ifEmpty { "it" }; e.groupValues[2] == local && e.groupValues[3] == local }
            b != null -> b.groupValues[1] == b.groupValues[2] && b.groupValues[1] == b.groupValues[3]
            else -> false
        })
        val checkpoint = member(all.getValue(control + "ControlRecordStore.kt"), "fun checkpoint(command: CommandRef)")
        assertEquals(1, Regex("captureStateAndBody\\(\\)").findAll(checkpoint).count())
        for (reread in listOf("command.body", "command.actions", "command.lifecycleState", "executing"))
            assertFalse("checkpoint must not use $reread", checkpoint.contains(reread))
        assertTrue("exact lookup before the view", checkpoint.indexOf("findPrepared(") in 0 until checkpoint.indexOf("captureStateAndBody()"))
    }
    @Test fun D2B6_runPassesTheCapturedBodyOnly() {
        val run = member(sources().getValue(control + "ControlRecordStore.kt"), "private suspend fun run(")
        assertEquals(1, Regex("captureStateAndBody\\(\\)").findAll(run).count())
        assertEquals(1, run.lineSequence().count { it.trim().startsWith("return attempt(command, body, actions, ") })
        for (reread in listOf("command.body", "command.actions")) assertFalse("run must not use $reread", run.contains(reread))
    }
    @Test fun D2B6_terminationRunsUnderTheCommandLeaseInOrder() {
        val all = sources(); val store = all.getValue(control + "ControlRecordStore.kt")
        for (method in listOf("publishPendingTermination", "finishTermination", "bindTerminationDescriptor"))
            assertEquals(method, 1, all.getValue(control + "ControlRecordStore.kt").let { Regex("\\b$method\\(").findAll(it).count() })
        // 6-2B: declaration 1 + abandon/retry/consume calls 3; 6-3B2: + settlement consume call = 5.
        assertEquals(mapOf(control + "ControlRecordStore.kt" to 5), SealSourceTripwire.occurrences(all, "terminationAttempt"))
        // 6-1 completion (§7 "finishTermination 1곳"): one declaration and one owner call across every production file.
        assertEquals(mapOf(control + "ControlCommandTracking.kt" to 1, control + "ControlRecordStore.kt" to 1),
            SealSourceTripwire.occurrences(all, "finishTermination"))
        // The only termination transitions happen in terminationAttempt, reached only after the entry acquired c's lease.
        for (entry in listOf("suspend fun abandonBeforeFirstConfirm(", "suspend fun retryTermination(", "suspend fun completeAfterConsumption(",
            "suspend fun completeSettlementAfterConsumption(")) {
            val body = member(store, entry)
            val lease = body.indexOf("tracking.executing.add(command)"); val call = body.indexOf("terminationAttempt(")
            assertTrue("$entry: lease before attempt", lease in 0 until call)
            assertTrue("$entry: lease released in finally", body.contains("tracking.executing.remove(command)"))
        }
        val attempt = member(store, "private suspend fun terminationAttempt(")
        val steps = listOf("tracked.bindTerminationDescriptor(", "tracking.publishPendingTermination(command)",
            "command.beginTermination()", "RecordTransactionDecision.Confirm(",
            // 6-1 completion (§7 same strength as release) as generalized by 6-2B (skeleton r3 §7): the returned snapshot is
            // verified by plan kind in one call before the terminal step; each plan's branch has its own behavior tests.
            "validateTerminationReturn(",
            "command.completeTermination()", "tracking.finishTermination(tracked)")
        assertTrue(steps.filterNot { attempt.contains(it) }.toString(), steps.all { attempt.contains(it) })
        assertEquals(steps.map { attempt.indexOf(it) }.sorted(), steps.map { attempt.indexOf(it) })
    }
    @Test fun A17_terminalResultCarriesNoAuthority() {
        assertEquals(setOf("command", "localUnresolvedCommands", "localPendingReleases"),
            contractFieldNames(ControlStoreResult.Released::class.java))
        assertEquals(setOf("command", "localUnresolvedCommands", "localPendingReleases"),
            contractFieldNames(ControlStoreResult.ReleasePending::class.java))
        assertEquals(setOf("command", "localUnresolvedCommands", "localPendingReleases"),
            contractFieldNames(ControlCommandReleaseResult.AlreadyReleased::class.java))
        // 6-1A: the new closed results carry exactly the same authority-free shape.
        for (type in listOf<Class<*>>(ControlStoreResult.TerminationPending::class.java, ControlStoreResult.Terminated::class.java,
            ControlCommandReleaseResult.AlreadyTerminated::class.java))
            assertEquals(type.name, setOf("command", "localUnresolvedCommands", "localPendingReleases"), contractFieldNames(type))
    }
    @Test fun M09_releaseFacadeHasOneRefAndNoProductionConsumer() {
        val methods = ControlRecordStore::class.java.declaredMethods.filter { it.name == "releaseAfterConsumption" }
        assertEquals(1, methods.size)
        assertEquals(listOf(CommandRef::class.java, kotlin.coroutines.Continuation::class.java), methods.single().parameterTypes.toList())
        val all = sources()
        assertEquals(mapOf(control + "ControlRecordStore.kt" to 1, control + "ControlCommandReleaseResult.kt" to 1),
            SealSourceTripwire.occurrences(all, "releaseAfterConsumption"))
        assertEquals(mapOf(control + "ControlRecordStore.kt" to 2), SealSourceTripwire.occurrences(all, "releaseAttempt"))
        val store = all.getValue(control + "ControlRecordStore.kt")
        assertEquals(1, Regex("\\breleaseAfterConsumption\\b").findAll(store).count())
        assertEquals(1, store.lineSequence().count { it.trim() == "return releaseAttempt(command, checkNotNull(tracked))" })
        val release = store.substringAfter("suspend fun releaseAfterConsumption(").substringBefore("private suspend fun releaseAttempt(")
        assertEquals(2, release.lineSequence().count { it.trim() == "if (command.lifecycleState == ControlCommandLifecycle.RELEASED) return alreadyReleased(command)" })
        assertTrue(release.indexOf("tracking.executing.add(command)") < release.indexOf("val tracked = tracking.findPrepared(command)"))
        assertEquals(1, store.lineSequence().count { it.trim() == "if (command.lifecycleState == ControlCommandLifecycle.RETAINED) {" })
    }

    // 6-2D (contracts-6-2D S1–S6; skeleton r3 last structure item). S1 follows the 6-2C2 agreement that withdrew
    // consensus d's in-decide re-check: the closure is a pure value check done once at each entry (6-2D.1 correction).
    @Test fun D2B6_g11BeforeBindingAndG11StaysLocal() {
        val all = sources(); val store = all.getValue(control + "ControlRecordStore.kt")
        val attempt = member(store, "private suspend fun terminationAttempt(")
        // S1: inside the owner decide, G11 runs before any binding/Confirm, and the closure is not re-checked there.
        // 6-3B2: the G11 helper is shared by Rotation and Settlement consumption (API consensus a).
        val order = listOf("consumptionDependencyViolation(", "tracked.bindTerminationDescriptor(", "RecordTransactionDecision.Confirm(")
        assertTrue(order.filterNot { attempt.contains(it) }.toString(), order.all { attempt.contains(it) })
        assertEquals(order.map { attempt.indexOf(it) }.sorted(), order.map { attempt.indexOf(it) })
        assertFalse("S1: no closure re-check inside the decide", attempt.contains("violation("))
        // 6-3C2: four termination entry checks plus one previous-reclamation entry check, each inside its own entry.
        assertEquals("S1: five entry checks in total", 5, Regex("closure\\.violation\\(").findAll(store).count())
        val terminationEntries = listOf("suspend fun abandonBeforeFirstConfirm(", "suspend fun completeAfterConsumption(",
            "suspend fun completeSettlementAfterConsumption(", "suspend fun retryTermination(")
        assertEquals("S1: one check per termination entry", List(4) { 1 },
            terminationEntries.map { Regex("closure\\.violation\\(").findAll(member(store, it)).count() })
        assertEquals("S1: one previous entry check", 1, Regex("closure\\.violation\\(")
            .findAll(member(store, "internal suspend fun reclaimPreviousSettlementOrLifecycleEvidence(")).count())
        // S2: projectDependency has one production caller, inside consumptionDependencyViolation.
        assertEquals(mapOf(control + "ControlRecordStore.kt" to 1, control + "DependencyProjection.kt" to 1),
            SealSourceTripwire.occurrences(all, "projectDependency"))
        val g11 = member(store, "private fun consumptionDependencyViolation(")
        assertTrue("S2: caller inside G11", g11.contains("projectDependency("))
        // S3: no other ref's lease, one captured view per dependent, no body re-read.
        assertFalse("S3: no lease", g11.contains("executing"))
        assertEquals("S3: one capture", 1, Regex("captureStateAndBody\\(\\)").findAll(g11).count())
        assertFalse("S3: no body re-read", g11.contains("dependent.body") || g11.contains(".captureStateAndBody().body"))
        assertEquals("S3: candidates enumerated once", 1, Regex("dependencyCandidatesExcluding\\(").findAll(g11).count())
        // S5: ordinary release takes Mutations only (behavior: HandoverCombinedTest R/N/L, CurrentNullOwnerTest).
        assertEquals(1, all.getValue(control + "ControlCommandReleaseDecision.kt").lineSequence().count {
            it.trim() == "if (view.body !is ControlCommandBody.Mutations) return reject(ReleaseRejectionReason.UnsupportedCommandKind)" })
        // S6: 2c previous allowlist is exactly Mutations and Rotation.
        val reclaim = all.getValue(control + "ReclaimPreviousLifetimeEvidence.kt")
        for (line in listOf("is AppliedEvidence.Mutations, is AppliedEvidence.Rotation -> true",
            "is AppliedEvidence.Settlement -> false", "is AppliedEvidence.Lifecycle -> false"))
            assertEquals(line, 1, reclaim.lineSequence().count { it.trim() == line })
    }
    @Test fun D2B6_noPersistentIdentityCache() {
        // S4: the candidate list is a local of dependencyCandidatesExcluding; neither holder gains a field for it.
        fun instanceFields(type: Class<*>) = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(setOf("lifetimeId", "evidenceDiscontinuityCount", "commands", "executing", "recoveryWork"),
            instanceFields(ControlCommandTracking::class.java))
    }
}
