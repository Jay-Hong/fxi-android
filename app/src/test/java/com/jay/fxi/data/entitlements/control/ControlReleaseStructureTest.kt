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

    @Test fun M09_lifecycleMutationIsPrivateAndHasNoProductionCaller() {
        val all = sources()
        val ref = all.getValue(control + "ControlStoreResult.kt")
        assertTrue(Modifier.isPrivate(CommandRef::class.java.getDeclaredField("lifecycle").modifiers))
        val begin = CommandRef::class.java.declaredMethods.singleOrNull { it.name == "beginRelease" }
        assertNotNull("private lifecycle method must retain private JVM shape", begin)
        assertTrue(Modifier.isPrivate(checkNotNull(begin).modifiers))
        val complete = CommandRef::class.java.declaredMethods.singleOrNull { it.name == "completeRelease" }
        assertNotNull("private completion method must retain private JVM shape", complete)
        assertTrue(Modifier.isPrivate(checkNotNull(complete).modifiers))
        assertEquals(1, Regex("\\bbeginRelease\\b").findAll(all.values.joinToString("\n")).count())
        assertEquals(1, Regex("\\bcompleteRelease\\b").findAll(all.values.joinToString("\n")).count())
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

    @Test fun M09_descriptorBindingPrivateAndUncalled() {
        val method = TrackedControlCommand::class.java.declaredMethods.singleOrNull { it.name == "bindReleaseDescriptor" }
        assertNotNull("descriptor binder must retain private JVM shape", method)
        assertTrue(Modifier.isPrivate(checkNotNull(method).modifiers))
        assertEquals(1, Regex("\\bbindReleaseDescriptor\\b").findAll(sources().values.joinToString("\n")).count())
        assertFalse(TrackedControlCommand::class.java.declaredMethods.any { it.name.startsWith("setReleaseDescriptor") })
    }
    @Test fun M07_refHasOnlyIdentityInputsAndSmallTerminalCell() {
        assertEquals(setOf("id", "body", "ownerTrackingLifetimeId", "lifecycle"),
            contractFieldNames(CommandRef::class.java))
        assertEquals(java.util.concurrent.atomic.AtomicReference::class.java,
            CommandRef::class.java.getDeclaredField("lifecycle").type)
        assertEquals(setOf("RETAINED", "RELEASE_PENDING", "RELEASED"), ControlCommandLifecycle.entries.map { it.name }.toSet())
    }
    @Test fun M08_noReleasedIdReservationOrProductionEviction() {
        assertEquals(setOf("lifetimeId", "evidenceDiscontinuityCount", "commands", "executing", "recoveryWork", "Companion", "collected", "owners"),
            contractFieldNames(ControlCommandTracking::class.java))
        val tracking = sources().getValue(control + "ControlCommandTracking.kt")
        assertFalse(tracking.contains("commands.remove")); assertFalse(tracking.contains("commands.clear"))
    }
    @Test fun A17_twoRecoverySetsUseOneAtomicCell() {
        val fields = ControlCommandTracking::class.java.declaredFields.filter { it.type == java.util.concurrent.atomic.AtomicReference::class.java }
        assertEquals(listOf("recoveryWork"), fields.map { it.name })
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
    @Test fun M09_noFacadeReleaseUntilOwnerReclamationExists() {
        assertFalse(ControlRecordStore::class.java.declaredMethods.any { it.name.contains("release", ignoreCase = true) })
        val store = sources().getValue(control + "ControlRecordStore.kt")
        assertFalse(store.contains("ControlCommandReleaseResult"))
        assertFalse(store.contains("ControlCommandReleaseDecision"))
    }
}
