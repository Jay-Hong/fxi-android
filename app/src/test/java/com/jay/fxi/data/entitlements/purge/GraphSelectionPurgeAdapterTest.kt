package com.jay.fxi.data.entitlements.purge

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeResult
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.local.GraphSelectionPreferencesCodec
import com.jay.fxi.data.local.GraphSelectionReadResult
import com.jay.fxi.data.local.GraphSelectionWriteResult
import com.jay.fxi.data.local.IntentStoreHarness
import com.jay.fxi.domain.model.GraphSeriesSelection
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned S4 B2b-1 contract r2: the account-deletion purge of `datastore:fxi_backupable_user_intent`.
 *
 * Oracles: ANDROID_V2_PLAN.md :397 I4 and §7 S1 (user intent is deleted only by an authorised account deletion, never by a
 * sign-out, a switch or a revoke). Design: b2b_api_codex.r1 §1 (one transaction deletes every v1 record whose physical key
 * names the UID - whatever its payload - and keeps other UIDs and keys outside the namespace; a reserved key whose owner
 * cannot be told refuses the deletion) and §4 (the adapter re-checks the canonical manifest target, the USER axis in the
 * request and in the entry, ACCOUNT_DELETION, a nonblank pending owner equal to the namespace owner, an authorisation for that
 * owner with localCleanupAllowed and a nonblank operationId, and DELETE_NOW from PurgeDecision; deletes the pending owner;
 * Committed(n>0) -> Removed, Committed(0) -> NothingToRemove, NotCommitted/Uncertain -> Failed, cancellation propagates).
 *
 * Aggregation goes through the real [ManifestScopePurger] over the manifest's own entry, with the same authorisation given to
 * the purger and to the adapter. Fixture: [IntentStoreHarness]. The implementation thread reads but does not edit this file.
 */
class GraphSelectionPurgeAdapterTest {

    @get:Rule val folder = TemporaryFolder()

    private companion object {
        val TARGET = checkNotNull(PurgeManifest.byId(GraphSelectionPurgeAdapter.TARGET_ID))
        val A_PREMIUM_USD = GraphSelectionKey("A", GraphSelectionAudience.PREMIUM, "usd")
        val A_FREE_USD = GraphSelectionKey("A", GraphSelectionAudience.FREE, "usd")
        val A_PREMIUM_JPY = GraphSelectionKey("A", GraphSelectionAudience.PREMIUM, "jpy")
        val B_PREMIUM_USD = GraphSelectionKey("B", GraphSelectionAudience.PREMIUM, "usd")
        val AUTH_A = DeletionAuthorization("A", "op-1", localCleanupAllowed = true)
        val NON_DELETION_CAUSES = PurgeCause.entries - PurgeCause.ACCOUNT_DELETION
    }

    private val harness by lazy { IntentStoreHarness(File(folder.root, "fxi_backupable_user_intent.preferences_pb")) }

    @After fun tearDown() = runBlocking { harness.close() }

    private fun sel(id: String) = GraphSeriesSelection(setOf(id), setOf(id))

    private fun name(key: GraphSelectionKey) = GraphSelectionPreferencesCodec.keyName(key)

    private fun namespace(owner: String? = "A", pendingOwner: String? = owner, scopes: Set<PurgeScope> = setOf(PurgeScope.USER)) =
        PurgeNamespace(owner, "epoch-live", null, PendingPurge(pendingOwner, "epoch-retired", null, scopes))

    private fun request(
        namespace: PurgeNamespace = namespace(),
        target: PurgeTarget = TARGET,
        scope: PurgeScope = PurgeScope.USER,
        cause: PurgeCause = PurgeCause.ACCOUNT_DELETION
    ) = PurgeRequest(target, scope, cause, namespace)

    /** Counts the adapter calls the purger makes. */
    private class Counting(private val delegate: PurgeTargetAdapter) : PurgeTargetAdapter {
        var calls = 0
        override suspend fun purge(request: PurgeRequest): TargetOutcome {
            calls += 1
            return delegate.purge(request)
        }
    }

    private fun purger(adapter: PurgeTargetAdapter?, cause: PurgeCause, auth: DeletionAuthorization?) = ManifestScopePurger(
        adapters = if (adapter == null) emptyMap() else mapOf(TARGET.id to adapter),
        manifest = listOf(TARGET),
        causeOf = { cause },
        authorizationFor = { auth }
    )

    private suspend fun IntentStoreHarness.Opened.seedAll() {
        listOf(A_PREMIUM_USD, A_FREE_USD, A_PREMIUM_JPY, B_PREMIUM_USD).forEach {
            assertEquals(GraphSelectionWriteResult.Committed, store.writeGraphSelection(it, sel(it.tab + it.audience)))
        }
    }

    private suspend fun IntentStoreHarness.Opened.keys() = raw().asMap().keys.map { it.name }.toSet()

    private val allSeeded get() = setOf(A_PREMIUM_USD, A_FREE_USD, A_PREMIUM_JPY, B_PREMIUM_USD).map(::name).toSet()
    private val onlyB get() = setOf(name(B_PREMIUM_USD))

    // --- the manifest entry --------------------------------------------------------------------------------------

    /** The target the adapter answers for is the manifest's own entry, by id. */
    @Test fun theTargetIdIsTheManifestEntry() {
        assertEquals("datastore:fxi_backupable_user_intent", GraphSelectionPurgeAdapter.TARGET_ID)
        assertEquals(PurgeClassification.ACCOUNT_DELETION_ONLY, TARGET.classification)
    }

    // --- B2-P ----------------------------------------------------------------------------------------------------

    /** B2-P01: no cause but an account deletion reaches the target - not on either axis, even with a valid authorisation. */
    @Test fun B2_P01_onlyAnAccountDeletionReachesTheTarget() = runBlocking {
        val o = harness.open()
        o.seedAll()
        val counting = Counting(GraphSelectionPurgeAdapter(o.store) { AUTH_A })
        NON_DELETION_CAUSES.forEach { cause ->
            assertEquals(
                "$cause: not applicable", TargetDisposition.NOT_APPLICABLE,
                PurgeDecision.disposition(TARGET, PurgeScope.USER, cause, namespace(), AUTH_A)
            )
            assertEquals("$cause", PurgeResult.Completed, purger(counting, cause, AUTH_A).purgeUserScope(namespace()))
        }
        assertEquals(PurgeResult.Completed,
            purger(counting, PurgeCause.ACCOUNT_DELETION, AUTH_A).purgeCapabilityScope(namespace(scopes = setOf(PurgeScope.CAPABILITY))))
        assertEquals("no adapter call", 0, counting.calls)
        assertEquals("everything kept", allSeeded, o.keys())
    }

    /**
     * B2-P02: an account deletion without a covering authorisation - none, another owner's, one that forbids local cleanup, or
     * an entry whose owner was lost - stays owed: Deferred with no adapter call. Called directly, the adapter answers Failed
     * and calls the store not at all.
     */
    @Test fun B2_P02_anUnauthorisedDeletionStaysOwed() = runBlocking {
        val o = harness.open()
        o.seedAll()
        val cases = listOf(
            "no authorisation" to (namespace() to null),
            "another owner" to (namespace() to DeletionAuthorization("B", "op-1", true)),
            "cleanup not allowed" to (namespace() to DeletionAuthorization("A", "op-1", false)),
            "pending owner lost" to (namespace(owner = "A", pendingOwner = null) to AUTH_A)
        )
        cases.forEach { (label, case) ->
            val (ns, auth) = case
            val counting = Counting(GraphSelectionPurgeAdapter(o.store) { auth })
            val result = purger(counting, PurgeCause.ACCOUNT_DELETION, auth).purgeUserScope(ns)
            assertTrue("$label: deferred, was $result", result is PurgeResult.Deferred)
            assertEquals("$label: no adapter call", 0, counting.calls)
            val updates = o.dataStore.updates
            val direct = GraphSelectionPurgeAdapter(o.store) { auth }.purge(request(ns))
            assertTrue("$label: direct call fails, was $direct", direct is TargetOutcome.Failed)
            assertEquals("$label: no store call", updates, o.dataStore.updates)
        }
        assertEquals("everything kept", allSeeded, o.keys())
    }

    /** B2-P03: an authorised deletion of A removes every A record - each audience and tab - and keeps B's raw value. */
    @Test fun B2_P03_anAuthorisedDeletionRemovesEveryRecordOfTheOwnerOnly() = runBlocking {
        val o = harness.open()
        o.seedAll()
        val bRaw = o.raw()[stringPreferencesKey(name(B_PREMIUM_USD))]
        val adapter = GraphSelectionPurgeAdapter(o.store) { AUTH_A }
        assertEquals(TargetOutcome.Removed, adapter.purge(request()))
        assertEquals(onlyB, o.keys())
        assertEquals("B's raw value kept", bRaw, o.raw()[stringPreferencesKey(name(B_PREMIUM_USD))])
        val again = harness.open()
        assertEquals(GraphSelectionReadResult.Absent, again.store.readGraphSelection(A_PREMIUM_USD))
        assertEquals(GraphSelectionReadResult.Absent, again.store.readGraphSelection(A_FREE_USD))
        assertEquals(GraphSelectionReadResult.Absent, again.store.readGraphSelection(A_PREMIUM_JPY))
        assertTrue(again.store.readGraphSelection(B_PREMIUM_USD) is GraphSelectionReadResult.Present)

        harness.close()
        harness.file.delete()
        val p = harness.open()
        p.seedAll()
        val counting = Counting(GraphSelectionPurgeAdapter(p.store) { AUTH_A })
        assertEquals("aggregated", PurgeResult.Completed, purger(counting, PurgeCause.ACCOUNT_DELETION, AUTH_A).purgeUserScope(namespace()))
        assertEquals(1, counting.calls)
        assertEquals(onlyB, p.keys())
    }

    /** B2-P04: a retry after a completed deletion finds nothing and is still a success. */
    @Test fun B2_P04_aRetryFindsNothingAndSucceeds() = runBlocking {
        val o = harness.open()
        o.seedAll()
        val adapter = GraphSelectionPurgeAdapter(o.store) { AUTH_A }
        assertEquals(TargetOutcome.Removed, adapter.purge(request()))
        assertEquals(TargetOutcome.NothingToRemove, adapter.purge(request()))
        assertEquals(PurgeResult.Completed, purger(adapter, PurgeCause.ACCOUNT_DELETION, AUTH_A).purgeUserScope(namespace()))
        assertEquals(onlyB, o.keys())
    }

    /**
     * B2-P05: the UID is matched whole, never as a prefix - of the decoded UID (A against AB) or of the encoded component (AAA
     * against AAAB, whose encoding starts with AAA's) - and keys outside the graph namespace are kept.
     */
    @Test fun B2_P05_theUidIsMatchedWholeAndOtherKeysAreKept() = runBlocking {
        val o = harness.open()
        val a = A_PREMIUM_USD
        val ab = GraphSelectionKey("AB", GraphSelectionAudience.PREMIUM, "usd")
        val aaa = GraphSelectionKey("AAA", GraphSelectionAudience.PREMIUM, "usd")
        val aaab = GraphSelectionKey("AAAB", GraphSelectionAudience.PREMIUM, "usd")
        assertTrue("premise: AAAB's component starts with AAA's", name(aaab).removePrefix("graph_selection/v1/")
            .startsWith(name(aaa).removePrefix("graph_selection/v1/").substringBefore('/')))
        listOf(a, ab, aaa, aaab).forEach { o.store.writeGraphSelection(it, sel(it.uid)) }
        o.putRaw { it[stringPreferencesKey("other_intent")] = "keep" }
        assertEquals(TargetOutcome.Removed, GraphSelectionPurgeAdapter(o.store) { AUTH_A }.purge(request()))
        assertEquals(setOf(name(ab), name(aaa), name(aaab), "other_intent"), o.keys())
        val authAaa = DeletionAuthorization("AAA", "op-2", true)
        assertEquals(TargetOutcome.Removed, GraphSelectionPurgeAdapter(o.store) { authAaa }.purge(request(namespace("AAA"))))
        assertEquals(setOf(name(ab), name(aaab), "other_intent"), o.keys())
        assertEquals("keep", o.raw()[stringPreferencesKey("other_intent")])
    }

    /**
     * B2-P06: a deletion that fails before the delegate, before the rename, or after DataStore returned is Failed - never
     * Removed or NothingToRemove - and the aggregate is Failed. The file keeps A, keeps A, and has lost A respectively; an
     * authorised retry then completes.
     */
    @Test fun B2_P06_aFailedDeletionIsNeverReportedDone() = runBlocking {
        data class Case(val label: String, val inject: (IntentStoreHarness.Opened) -> Unit, val aOnDisk: Boolean)
        val cases = listOf(
            Case("before the delegate", { it.dataStore.failBeforeUpdate = true }, aOnDisk = true),
            Case("before the rename", { it.storage.failAfterWrite = true }, aOnDisk = true),
            Case("after the delegate returned", { it.dataStore.failAfterUpdate = true }, aOnDisk = false)
        )
        cases.forEach { case ->
            harness.close()
            harness.file.delete()
            val o = harness.open()
            o.seedAll()
            val adapter = GraphSelectionPurgeAdapter(o.store) { AUTH_A }
            case.inject(o)
            val direct = adapter.purge(request())
            assertTrue("${case.label}: direct Failed, was $direct", direct is TargetOutcome.Failed)
            case.inject(o)
            val aggregated = purger(adapter, PurgeCause.ACCOUNT_DELETION, AUTH_A).purgeUserScope(namespace())
            assertTrue("${case.label}: aggregate Failed, was $aggregated", aggregated is PurgeResult.Failed)
            val disk = harness.open()
            assertEquals("${case.label}: file", if (case.aOnDisk) allSeeded else onlyB, disk.keys())
            assertEquals("${case.label}: retry",
                PurgeResult.Completed, purger(GraphSelectionPurgeAdapter(disk.store) { AUTH_A }, PurgeCause.ACCOUNT_DELETION, AUTH_A)
                    .purgeUserScope(namespace()))
            assertEquals("${case.label}: after the retry", onlyB, harness.open().keys())
        }
    }

    /**
     * B2-P07: a key that names A is deleted whatever its payload - malformed, another type, another owner inside. A reserved
     * graph key whose owner cannot be told - not canonical base64url, another version, another audience, a truncated key, a
     * key with a component too many (whose first five read as A's) - refuses the whole deletion: Failed, nothing changed.
     */
    @Test fun B2_P07_theKeyDecidesTheOwnerAndAnUnknownKeyRefuses() = runBlocking {
        val o = harness.open()
        o.store.writeGraphSelection(B_PREMIUM_USD, sel("b"))
        o.putRaw {
            it[stringPreferencesKey(name(A_PREMIUM_USD))] = "{not json"
            it[intPreferencesKey(name(A_FREE_USD))] = 3
            it[stringPreferencesKey(name(A_PREMIUM_JPY))] =
                "{\"schemaVersion\":1,\"uid\":\"B\",\"audience\":\"premium\",\"tab\":\"jpy\",\"visibleSeriesIds\":[],\"initializedSeries\":[]}"
        }
        assertEquals(TargetOutcome.Removed, GraphSelectionPurgeAdapter(o.store) { AUTH_A }.purge(request()))
        assertEquals(onlyB, o.keys())

        listOf(
            "graph_selection/v1/QR/premium/dXNk",
            "graph_selection/v1/Q Q/premium/dXNk",
            "graph_selection/v2/QQ/premium/dXNk",
            "graph_selection/v1/QQ/gold/dXNk",
            "graph_selection/v1/QQ",
            "graph_selection/v1/QQ/premium/dXNk/x"
        ).forEach { unknown ->
            harness.close()
            harness.file.delete()
            val p = harness.open()
            p.seedAll()
            p.putRaw { it[stringPreferencesKey(unknown)] = "?" }
            val before = p.raw()
            val result = GraphSelectionPurgeAdapter(p.store) { AUTH_A }.purge(request())
            assertTrue("$unknown: Failed, was $result", result is TargetOutcome.Failed)
            assertEquals("$unknown: unchanged", before, p.raw())
            assertEquals("$unknown: file unchanged", allSeeded + unknown, harness.open().keys())
        }
    }

    /**
     * B2-P08: a direct call the adapter must refuse - another target, an id-matching forgery of the entry, the capability
     * axis, an entry without USER, another cause, a blank pending owner, a namespace owner that is not the pending owner, a
     * blank operationId - is Failed with no store call.
     */
    @Test fun B2_P08_aMalformedRequestIsRefusedWithoutAStoreCall() = runBlocking {
        val o = harness.open()
        o.seedAll()
        val other = checkNotNull(PurgeManifest.byId("datastore:fxi_user_intent"))
        val forged = TARGET.copy(classification = PurgeClassification.DERIVED_HERE)
        val authBlankOwner = DeletionAuthorization("", "op-1", true)
        val cases: List<Triple<String, PurgeRequest, DeletionAuthorization?>> = listOf(
            Triple("another target", request(target = other), AUTH_A),
            Triple("a forged entry", request(target = forged), AUTH_A),
            Triple("the capability axis", request(scope = PurgeScope.CAPABILITY), AUTH_A),
            Triple("an entry without USER", request(namespace(scopes = setOf(PurgeScope.CAPABILITY))), AUTH_A),
            Triple("another cause", request(cause = PurgeCause.SIGN_OUT), AUTH_A),
            Triple("a blank pending owner", request(namespace(owner = "", pendingOwner = "")), authBlankOwner),
            Triple("the namespace owner differs", request(namespace(owner = "B", pendingOwner = "A")), AUTH_A),
            Triple("a blank operationId", request(), DeletionAuthorization("A", " ", true))
        )
        cases.forEach { (label, req, auth) ->
            val updates = o.dataStore.updates
            val result = GraphSelectionPurgeAdapter(o.store) { auth }.purge(req)
            assertTrue("$label: Failed, was $result", result is TargetOutcome.Failed)
            assertEquals("$label: no store call", updates, o.dataStore.updates)
        }
        assertEquals("everything kept", allSeeded, o.keys())
    }

    /** B2-P09: a valid authorisation with no adapter registered stays owed; a cancellation during the deletion propagates. */
    @Test fun B2_P09_noAdapterIsOwedAndACancellationPropagates() = runBlocking {
        val o = harness.open()
        o.seedAll()
        val unregistered = purger(null, PurgeCause.ACCOUNT_DELETION, AUTH_A).purgeUserScope(namespace())
        assertTrue("no adapter: deferred, was $unregistered", unregistered is PurgeResult.Deferred)
        assertEquals(allSeeded, o.keys())

        val adapter = GraphSelectionPurgeAdapter(o.store) { AUTH_A }
        o.dataStore.cancelBeforeUpdate = true
        assertThrows(CancellationException::class.java) { runBlocking { adapter.purge(request()) } }
        o.dataStore.cancelBeforeUpdate = true
        assertThrows(CancellationException::class.java) {
            runBlocking { purger(adapter, PurgeCause.ACCOUNT_DELETION, AUTH_A).purgeUserScope(namespace()) }
        }
        assertEquals(allSeeded, o.keys())
    }
}
