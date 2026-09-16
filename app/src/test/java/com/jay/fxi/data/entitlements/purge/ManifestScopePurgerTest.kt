package com.jay.fxi.data.entitlements.purge

import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeResult
import com.jay.fxi.data.entitlements.PurgeScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the journal is told, and why it matters that it is the truth.
 *
 * The coordinator drops a pending entry when every scope answered `Completed`, so each assertion
 * below is really about whether an obligation survives the answer it was given.
 */
class ManifestScopePurgerTest {

    private val namespace = PurgeNamespace(
        ownerUid = "u1",
        currentUserAccessEpoch = "live",
        currentKrxCapabilityEpoch = "live-krx",
        pending = PendingPurge("u1", "old", null, setOf(PurgeScope.USER))
    )

    private fun derived(id: String) = PurgeTarget(
        id, PurgeClassification.DERIVED_HERE, setOf(PurgeScope.USER), "this purger", "derived"
    )

    private class RecordingAdapter(private val outcome: TargetOutcome) : PurgeTargetAdapter {
        var calls = 0
        override suspend fun purge(request: PurgeRequest): TargetOutcome {
            calls++
            return outcome
        }
    }

    /**
     * The real manifest, today: every user-axis surface belongs to somebody else, so the entry is
     * still owed. This is the assertion that would have caught a purger reporting success for the
     * work the topic and graph runtimes have not started.
     */
    @Test
    fun `the production manifest defers because every surface is owed elsewhere`() = runTest {
        val purger = ManifestScopePurger(adapters = emptyMap())

        val result = purger.purgeUserScope(namespace)

        val deferred = result as PurgeResult.Deferred
        assertTrue("S3 의 rates 가 빠졌다: ${deferred.reason}", deferred.reason.contains("fxi_cache#rates"))
        assertTrue("S10 의 news 가 빠졌다", deferred.reason.contains("news_cache"))
        assertTrue("선호는 로그아웃에서 대상이 아니다", !deferred.reason.contains("fxi_user_intent"))
    }

    /** A target this axis must delete stays owed when no adapter is registered for it. */
    @Test
    fun `a target with no adapter defers instead of completing`() = runTest {
        val purger = ManifestScopePurger(adapters = emptyMap(), manifest = listOf(derived("derived:a")))

        val result = purger.purgeUserScope(namespace)

        assertTrue((result as PurgeResult.Deferred).reason.contains("no adapter"))
    }

    /** Everything this scope covers gone or absent, and nothing owed elsewhere, is the only completion. */
    @Test
    fun `all covered targets removed completes`() = runTest {
        val removed = RecordingAdapter(TargetOutcome.Removed)
        val absent = RecordingAdapter(TargetOutcome.NothingToRemove)
        val purger = ManifestScopePurger(
            adapters = mapOf("derived:a" to removed, "derived:b" to absent),
            manifest = listOf(derived("derived:a"), derived("derived:b"))
        )

        assertEquals(PurgeResult.Completed, purger.purgeUserScope(namespace))
        assertEquals(1, removed.calls)
        assertEquals(1, absent.calls)
    }

    /** Running it again is the same answer and the same work: crash recovery repeats this call. */
    @Test
    fun `a second run repeats the deletion and the answer`() = runTest {
        val adapter = RecordingAdapter(TargetOutcome.NothingToRemove)
        val purger = ManifestScopePurger(
            adapters = mapOf("derived:a" to adapter),
            manifest = listOf(derived("derived:a"))
        )

        assertEquals(PurgeResult.Completed, purger.purgeUserScope(namespace))
        assertEquals(PurgeResult.Completed, purger.purgeUserScope(namespace))
        assertEquals(2, adapter.calls)
    }

    /** One refusal does not stop the others, and it is what the caller hears about. */
    @Test
    fun `a failing target is reported and the rest still run`() = runTest {
        val failing = RecordingAdapter(TargetOutcome.Failed("not deleted: graph_cache_v1_usd.json"))
        val other = RecordingAdapter(TargetOutcome.Removed)
        val purger = ManifestScopePurger(
            adapters = mapOf("derived:a" to failing, "derived:b" to other),
            manifest = listOf(derived("derived:a"), derived("derived:b"))
        )

        val result = purger.purgeUserScope(namespace)

        val failure = (result as PurgeResult.Failed).cause as PurgeTargetsFailedException
        assertEquals(listOf("derived:a: not deleted: graph_cache_v1_usd.json"), failure.targets)
        assertEquals("실패가 나머지 대상을 막았다", 1, other.calls)
    }

    /** An adapter that throws is a failure, not an escape: the journal must survive it. */
    @Test
    fun `a throwing adapter is a failure`() = runTest {
        val purger = ManifestScopePurger(
            adapters = mapOf("derived:a" to PurgeTargetAdapter { error("disk gone") }),
            manifest = listOf(derived("derived:a"))
        )

        val result = purger.purgeUserScope(namespace)

        val failure = (result as PurgeResult.Failed).cause as PurgeTargetsFailedException
        assertTrue(failure.targets.single().contains("disk gone"))
    }

    /**
     * The cause reported is the first one that had a cause, and a later failure cannot take it.
     *
     * The second case is the one that loses evidence outright: a later failure carrying no cause
     * would otherwise overwrite a real exception with `null`.
     */
    @Test
    fun `later failures preserve the first non-null cause`() = runTest {
        listOf<Throwable?>(IllegalStateException("second"), null).forEach { later ->
            val first = IllegalStateException("first")
            val purger = ManifestScopePurger(
                adapters = mapOf(
                    "derived:a" to PurgeTargetAdapter { TargetOutcome.Failed("first", first) },
                    "derived:b" to PurgeTargetAdapter { TargetOutcome.Failed("later", later) }
                ),
                manifest = listOf(derived("derived:a"), derived("derived:b"))
            )

            val failure = (purger.purgeUserScope(namespace) as PurgeResult.Failed)
                .cause as PurgeTargetsFailedException

            assertEquals(listOf("derived:a: first", "derived:b: later"), failure.targets)
            assertTrue("나중 실패가 첫 원인을 덮었다 (later=$later)", failure.cause === first)
        }
    }

    /** Cancellation belongs to the caller: it unwinds rather than being filed as a failed purge. */
    @Test
    fun `cancellation propagates`() = runTest {
        val purger = ManifestScopePurger(
            adapters = mapOf("derived:a" to PurgeTargetAdapter { throw CancellationException("stop") }),
            manifest = listOf(derived("derived:a"))
        )

        val thrown = runCatching { purger.purgeUserScope(namespace) }.exceptionOrNull()

        assertTrue("취소를 실패로 삼켰다", thrown is CancellationException)
    }

    /** A failure outranks the deferral so the louder answer reaches the caller; both keep the journal. */
    @Test
    fun `a failure outranks work owed elsewhere`() = runTest {
        val cutover = PurgeTarget(
            "legacy:x", PurgeClassification.CUTOVER_OWNED, setOf(PurgeScope.USER), "S3", "legacy"
        )
        val purger = ManifestScopePurger(
            adapters = mapOf("derived:a" to RecordingAdapter(TargetOutcome.Failed("refused"))),
            manifest = listOf(derived("derived:a"), cutover)
        )

        assertTrue(purger.purgeUserScope(namespace) is PurgeResult.Failed)
    }

    /** The capability axis only reaches what the manifest says it reaches. */
    @Test
    fun `the capability axis skips user-axis targets`() = runTest {
        val adapter = RecordingAdapter(TargetOutcome.Removed)
        val purger = ManifestScopePurger(
            adapters = mapOf("derived:a" to adapter),
            manifest = listOf(derived("derived:a"))
        )

        assertEquals(PurgeResult.Completed, purger.purgeCapabilityScope(namespace))
        assertEquals("user 축 대상을 capability 철회가 지웠다", 0, adapter.calls)
    }

    /** The cause the journal cannot carry yet reads as UNKNOWN, and UNKNOWN never reaches preferences. */
    @Test
    fun `preferences are untouched under the default unknown cause`() = runTest {
        val preferences = RecordingAdapter(TargetOutcome.Removed)
        val purger = ManifestScopePurger(
            adapters = mapOf("datastore:fxi_user_intent" to preferences)
        )

        purger.purgeUserScope(namespace)

        assertEquals("원인 없는 journal 이 사용자 선호를 지웠다", 0, preferences.calls)
    }

    /** With a deletion's own authorisation, the same target is deleted — the only path that may. */
    @Test
    fun `an authorised account deletion reaches preferences`() = runTest {
        val preferences = RecordingAdapter(TargetOutcome.Removed)
        val purger = ManifestScopePurger(
            adapters = mapOf("datastore:fxi_user_intent" to preferences),
            manifest = PurgeManifest.TARGETS.filter { it.id == "datastore:fxi_user_intent" },
            causeOf = { PurgeCause.ACCOUNT_DELETION },
            authorizationFor = { DeletionAuthorization("u1", "op-1", localCleanupAllowed = true) }
        )

        assertEquals(PurgeResult.Completed, purger.purgeUserScope(namespace))
        assertEquals(1, preferences.calls)
    }
}
