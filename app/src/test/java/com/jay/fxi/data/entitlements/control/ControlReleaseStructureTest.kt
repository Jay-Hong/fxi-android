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
    private fun sources() = SealSourceTripwire.read(SealSourceTripwire.sourceRoot(
        File(checkNotNull(System.getProperty("user.dir"))), System.getProperty("fxi.seal.sourceRoot")))

    @Test fun M09_lifecycleTransitionsHaveOnlyTheOwnerReleaseCaller() {
        val all = sources()
        val ref = all.getValue(control + "ControlStoreResult.kt")
        assertTrue(Modifier.isPrivate(CommandRef::class.java.getDeclaredField("lifecycle").modifiers))
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
        assertEquals(2, Regex("lifecycle\\.compareAndSet").findAll(ref).count())
        assertFalse(ref.contains("lifecycle.set("))
        assertFalse(ref.contains("lifecycle.getAndSet("))
        assertFalse(all.values.any { it.contains("ControlReleaseFixtures") })
        assertFalse(all.filterKeys { it.startsWith(control) }.values.any {
            it.contains("getDeclaredField") || it.contains("getDeclaredMethod") || it.contains("java.lang.reflect")
        })
    }
    @Test fun M09_lifecycleCellHasNoProductionAccessor() {
        val ref = sources().getValue(control + "ControlStoreResult.kt")
        assertEquals(1, ref.lineSequence().count {
            it.trim() ==
                "private val lifecycle = AtomicReference(ControlCommandLifecycle.RETAINED)"
        })
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
        assertEquals(setOf("id", "body", "ownerTrackingLifetimeId", "lifecycle", "diagnostic"),
            contractFieldNames(CommandRef::class.java))
        assertEquals(java.util.concurrent.atomic.AtomicReference::class.java,
            CommandRef::class.java.getDeclaredField("lifecycle").type)
        // 5a adds one replaceable observation, never a growing history or a release authority.
        assertEquals("java.util.concurrent.atomic.AtomicReference<com.jay.fxi.data.entitlements.control.ControlLifecycleDiagnostic>",
            CommandRef::class.java.getDeclaredField("diagnostic").genericType.typeName)
        assertEquals(setOf("RETAINED", "RELEASE_PENDING", "RELEASED"), ControlCommandLifecycle.entries.map { it.name }.toSet())
    }
    @Test fun M08_noReleasedIdReservationAndOnlyConditionalCleanup() {
        assertEquals(setOf("lifetimeId", "evidenceDiscontinuityCount", "commands", "executing", "recoveryWork", "Companion", "collected", "owners"),
            contractFieldNames(ControlCommandTracking::class.java))
        val tracking = sources().getValue(control + "ControlCommandTracking.kt")
        assertEquals(1, tracking.lineSequence().count { it.trim() == "commands.remove(command.id, tracked)" })
        assertEquals(1, Regex("commands\\.remove").findAll(tracking).count())
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
    @Test fun A17_terminalResultCarriesNoAuthority() {
        assertEquals(setOf("command", "localUnresolvedCommands", "localPendingReleases"),
            contractFieldNames(ControlStoreResult.Released::class.java))
        assertEquals(setOf("command", "localUnresolvedCommands", "localPendingReleases"),
            contractFieldNames(ControlStoreResult.ReleasePending::class.java))
        assertEquals(setOf("command", "localUnresolvedCommands", "localPendingReleases"),
            contractFieldNames(ControlCommandReleaseResult.AlreadyReleased::class.java))
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
}
