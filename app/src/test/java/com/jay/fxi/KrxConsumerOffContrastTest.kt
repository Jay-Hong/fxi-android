package com.jay.fxi

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.HEAD_FACTORY
import com.jay.fxi.data.entitlements.KrxHarness
import com.jay.fxi.data.entitlements.KrxHarnessDefaults
import com.jay.fxi.data.entitlements.KrxSendAcquisition
import com.jay.fxi.data.entitlements.KrxSendMarker
import com.jay.fxi.data.entitlements.KrxSendReadAuthority
import com.jay.fxi.data.entitlements.KrxUseLifetime
import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.grantedKrxVisible
import com.jay.fxi.data.entitlements.issueGrant
import com.jay.fxi.data.entitlements.krxHarness
import com.jay.fxi.data.entitlements.observePublications
import com.jay.fxi.data.entitlements.purge.TargetOutcome
import com.jay.fxi.data.entitlements.rejectKrx
import com.jay.fxi.data.entitlements.settleKrx
import com.jay.fxi.data.entitlements.step
import com.jay.fxi.data.entitlements.undecidedRejection
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphV2AccessBinding
import com.jay.fxi.data.graph.GraphV2AccessCapture
import com.jay.fxi.data.graph.GraphV2AccessGate
import com.jay.fxi.data.graph.GraphV2CachePorts
import com.jay.fxi.data.graph.GraphV2CapabilityConfiguration
import com.jay.fxi.data.graph.GraphV2ComponentJoin
import com.jay.fxi.data.graph.GraphV2DiskComponent
import com.jay.fxi.data.graph.GraphV2DiskComponents
import com.jay.fxi.data.graph.GraphV2DiskRead
import com.jay.fxi.data.graph.GraphV2DiskSeedResult
import com.jay.fxi.data.graph.GraphV2DiskStore
import com.jay.fxi.data.graph.GraphV2GeneralEnvelope
import com.jay.fxi.data.graph.GraphV2GeneralKey
import com.jay.fxi.data.graph.GraphV2IoAdmission
import com.jay.fxi.data.graph.GraphV2KrxEnvelope
import com.jay.fxi.data.graph.GraphV2KrxKey
import com.jay.fxi.data.graph.GraphV2Validation
import com.jay.fxi.data.graph.GraphV2WriteReport
import com.jay.fxi.data.graph.GraphV2WriteReservation
import com.jay.fxi.data.graph.GraphV2WriteTicket
import com.jay.fxi.data.graph.generalKey
import com.jay.fxi.data.graph.joinGraphV2Components
import com.jay.fxi.data.graph.readGraphV2DiskSeed
import com.jay.fxi.data.graph.splitGraphV2ServerTab
import com.jay.fxi.data.remote.TopicCatalogue
import com.jay.fxi.data.remote.TopicCommandClock
import com.jay.fxi.data.remote.TopicCommandCredentials
import com.jay.fxi.data.remote.TopicGrantOrigin
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionCoordinator
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicSnapshotOutcome
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphV2Tab
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.domain.model.TopicSubscriptionStateStore
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **S6-2b-1b-α contract tests r1** — TA-11 (design `s6_2b1b_contracts.r5.md` §13 T.11, §2 ③(b2), T.13 column (나)): the real
 * switch-off coordinator (KrxContestHarness `HEAD_FACTORY`, unchanged) feeds every α KRX consumer through its production read, and each
 * consumer's answer equals HEAD's. Rows: 11-1 SUA, 11-2 graph gate (bind, admits GENERAL/KRX, ioAdmission, joinForExposure), 11-3 seed
 * (`readGraphV2DiskSeed` over a fake disk port), 11-4 session send read (the TSC decision seam), 11-5 REST unknown_topic hand-over guard
 * (a real session with a test desired set).
 *
 * Input: the A1-shaped sequence of PremiumAccessKrxA1Test od2Sequence (:643-685 — H1′ block, C1 false edge, re-permit, C1 from
 * allowed, re-permit, H1 rotation) on the switch-off coordinator. Rows 11-1..11-4 judge **every publication**: the consumers read the
 * supplier in the production form `{ coordinator.accessSnapshot }` (GraphRuntimeModule.kt:37, ProcessGraphBuilder.kt:97) from inside
 * the publication hook ([observePublications], S-4), where that read is the publication just made; the hook only records, and every
 * assertion runs after the sequence (throwing inside the hook would change the coordinator's control flow — S-4 KDoc). Row 11-5 drives a
 * session at checkpoints between the steps.
 *
 * HEAD references are frozen copies written in this file from de50885: SnapshotTopicUseAuthority.kt:14-22 (acquireUse/admitsUse),
 * GraphV2AccessGate.kt:24-81 (the gate) and GraphV2Cache.kt:85-123 (the seed read). HEAD had no KRX use, no send read and no REST
 * hand-over: its answers there are "none".
 *
 * Off, the A1 projection is absent: a record epoch move comes with a grant-context move (grantFence is the identity, PAC:1792-1793), so
 * the C-1 differential on this path is vacuous by construction (§2 ③(b2)) — G-OFF on the gate's admits and ioAdmission is caught by
 * TA-10 10-1, not here; on the exposure path 11-2 catches it through the D-6 parameter (another original count). What this class proves
 * is wiring: the p = 0 the coordinator actually publishes reaches every consumer guard, and (11-5) the REST KRX issue reads the send read
 * and carries the non-null marker (record epoch, 0) the guard is given.
 *
 * Revised after the adversarial review of r1 (g5-fix): 11-2's explicit-count probe (identical to the default) became another original
 * count; 11-5 wraps the send read to record the marker of every KRX issue and asserts it at each entered checkpoint.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class KrxConsumerOffContrastTest {

    private companion object {
        val OWNER = AuthIdentityFence(KrxHarnessDefaults.OWNER, 1L)
        val KEY = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        val T0: Instant = Instant.parse("2026-10-05T03:00:00Z")
        const val KRX = TopicCatalogue.KRX_FUTURES
        val ACTIVE = EntitlementsOutcome.StableActive(krxVisible = true)
        val KRX_FALSE = EntitlementsOutcome.StableActive(krxVisible = false)
        val UNSUPPORTED = TopicSnapshotOutcome.Unsupported(listOf("fx:usd-krw"))
    }

    // --- frozen HEAD expressions (de50885) ----------------------------------------------------------------------------------

    /** de50885 SnapshotTopicUseAuthority.kt:14-18, verbatim body. */
    private fun TopicAccessSnapshot.headAcquireUse(fence: TopicSessionFence): TopicUseLifetime? {
        val token = facts.token ?: return null
        if (token != fence.grant || !facts.tokenStanding || !facts.userAllowed) return null
        return TopicUseLifetime(token, userInvalidations)
    }

    /** de50885 SnapshotTopicUseAuthority.kt:21-22, verbatim body. */
    private fun TopicAccessSnapshot.headAdmitsUse(lifetime: TopicUseLifetime): Boolean =
        facts.token == lifetime.grant && facts.tokenStanding && facts.userAllowed && userInvalidations == lifetime.invalidations

    /**
     * de50885 GraphV2AccessGate.kt:24-81, verbatim except the class name and `it.admitsUse` → the frozen [headAdmitsUse] (the HEAD gate
     * called SnapshotTopicUseAuthority.kt:21's `admitsUse`). `GraphV2AccessCapture(fence, lifetime, epoch)` is HEAD's three-argument
     * construction; `joinGraphV2Components` is the unchanged production join.
     */
    private inner class HeadGate(
        private val currentIdentity: () -> AuthIdentityFence?,
        private val currentAccessFence: () -> TopicSessionFence?,
        private val snapshot: () -> TopicAccessSnapshot,
        private val protectedAdmission: () -> Boolean
    ) {
        fun bind(fence: TopicSessionFence, lifetime: TopicUseLifetime): GraphV2AccessCapture? =
            bindWithConfiguration(fence, lifetime)?.captured

        fun bindWithConfiguration(fence: TopicSessionFence, lifetime: TopicUseLifetime): GraphV2AccessBinding? {
            if (lifetime.grant != fence.grant) return null
            val access = admittedSnapshot(fence, lifetime) ?: return null
            val configuration = GraphV2CapabilityConfiguration(
                access.facts.recordFence?.krxCapabilityEpoch, access.facts.capabilityAllowed
            )
            val epoch = configuration.recordEpoch.takeIf { configuration.allowed }
            return GraphV2AccessBinding(GraphV2AccessCapture(fence, lifetime, epoch), configuration)
        }

        fun admits(captured: GraphV2AccessCapture, component: GraphV2DiskComponent): Boolean {
            val access = admittedSnapshot(captured.fence, captured.lifetime) ?: return false
            return when (component) {
                GraphV2DiskComponent.GENERAL -> true
                GraphV2DiskComponent.KRX -> admitsKrx(captured, access)
            }
        }

        fun ioAdmission(captured: GraphV2AccessCapture): GraphV2IoAdmission =
            GraphV2IoAdmission { component -> admits(captured, component) }

        fun joinForExposure(
            captured: GraphV2AccessCapture,
            components: GraphV2DiskComponents
        ): GraphV2ComponentJoin? {
            val access = admittedSnapshot(captured.fence, captured.lifetime) ?: return null
            val general = components.general
            if (general.key.uid != captured.fence.identity.uid ||
                general.key.userAccessEpoch != captured.fence.userAccessEpoch
            ) return null
            val krx = components.krx?.takeIf {
                admitsKrx(captured, access) && it.key.krxCapabilityEpoch == captured.krxCapabilityEpoch
            }
            return joinGraphV2Components(general, krx)
        }

        private fun admittedSnapshot(fence: TopicSessionFence, lifetime: TopicUseLifetime): TopicAccessSnapshot? {
            if (!protectedAdmission() || currentIdentity() != fence.identity || currentAccessFence() != fence ||
                fence.userAccessEpoch == null
            ) return null
            val access = snapshot()
            return access.takeIf { it.headAdmitsUse(lifetime) }
        }

        private fun admitsKrx(captured: GraphV2AccessCapture, access: TopicAccessSnapshot): Boolean =
            captured.krxCapabilityEpoch != null && access.facts.capabilityAllowed
    }

    /** de50885 GraphV2Cache.kt:85-114, verbatim except `ports.gate` → [gate] (the frozen HEAD gate) and `ports.store` → [store]. */
    private suspend fun headReadSeed(
        gate: HeadGate,
        store: GraphV2DiskStore,
        key: GraphKey,
        captured: GraphV2AccessCapture,
        catalog: GraphCatalog?
    ): GraphV2DiskSeedResult {
        val generalKey = captured.generalKey(key)
            ?: return GraphV2DiskSeedResult(GraphV2DiskRead.Rejected("Missing USER access epoch"), null)
        val general = headDiskSeedRead {
            if (!gate.admits(captured, GraphV2DiskComponent.GENERAL)) {
                GraphV2DiskRead.Rejected("GENERAL seed admission is closed")
            } else {
                store.readGeneral(generalKey, catalog, gate.ioAdmission(captured))
            }
        }
        if (general !is GraphV2DiskRead.Found) return GraphV2DiskSeedResult(general, null)

        val krx = try {
            val epoch = captured.krxCapabilityEpoch
            if (epoch == null || !gate.admits(captured, GraphV2DiskComponent.KRX)) null else {
                val krxKey = GraphV2KrxKey(generalKey.uid, generalKey.userAccessEpoch, epoch, key.tab, key.period.code)
                store.readKrx(krxKey, catalog, gate.ioAdmission(captured))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            GraphV2DiskRead.Failed(failure)
        }
        return GraphV2DiskSeedResult(general, krx)
    }

    /** de50885 GraphV2Cache.kt:117-123, verbatim. */
    private suspend fun <T> headDiskSeedRead(read: suspend () -> GraphV2DiskRead<T>): GraphV2DiskRead<T> = try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        GraphV2DiskRead.Failed(failure)
    }

    // --- fixtures -------------------------------------------------------------------------------------------------------------

    /** A disk port that holds [components] and honours the I/O admission it is handed, like the file store's read gate. */
    private class FakeStore(private val components: GraphV2DiskComponents) : GraphV2DiskStore {
        override fun reserveWrite(components: GraphV2DiskComponents): GraphV2WriteReservation = error("TA-11 store: not used")
        override fun cancelWrite(ticket: GraphV2WriteTicket) { error("TA-11 store: not used") }
        override fun withdrawKrx(ticket: GraphV2WriteTicket): Boolean = error("TA-11 store: not used")
        override suspend fun write(ticket: GraphV2WriteTicket, admission: GraphV2IoAdmission): GraphV2WriteReport = error("TA-11 store: not used")
        override suspend fun readGeneral(key: GraphV2GeneralKey, catalog: GraphCatalog?, admission: GraphV2IoAdmission): GraphV2DiskRead<GraphV2GeneralEnvelope> =
            when {
                !admission.admits(GraphV2DiskComponent.GENERAL) -> GraphV2DiskRead.Rejected("TA-11 store: GENERAL admission closed")
                key != components.general.key -> GraphV2DiskRead.Absent
                else -> GraphV2DiskRead.Found(components.general)
            }
        override suspend fun readKrx(key: GraphV2KrxKey, catalog: GraphCatalog?, admission: GraphV2IoAdmission): GraphV2DiskRead<GraphV2KrxEnvelope> {
            val krx = components.krx
            return when {
                !admission.admits(GraphV2DiskComponent.KRX) -> GraphV2DiskRead.Rejected("TA-11 store: KRX admission closed")
                krx == null || key != krx.key -> GraphV2DiskRead.Absent
                else -> GraphV2DiskRead.Found(krx)
            }
        }
        override suspend fun purge(component: GraphV2DiskComponent, scope: PurgeScope, namespace: PurgeNamespace): TargetOutcome =
            error("TA-11 store: not used")
    }

    private fun point(rate: Double) = FreeGraphPoint(T0 - 1.days, rate, rate + 1, rate - 1, "hana")

    /** GENERAL ordinals around one KRX series (GraphV2AccessGateTest components, rebuilt here — that helper is private). */
    private fun components(uid: String, userEpoch: String, krxEpoch: String?): GraphV2DiskComponents {
        val series = listOf(
            FreeGraphSeries("hana.usd-krw", listOf(point(1390.0)), "하나", "krw", "KRW", 2),
            FreeGraphSeries("krx.usd-krw-futures", listOf(point(1391.0)), "KRX", "krw", "KRW", 1),
            FreeGraphSeries("kb.usd-krw", listOf(point(1392.0)), "국민", "krw", "KRW", 2)
        )
        val tab = GraphV2Tab("usd", GraphPeriod.THREE_MONTHS, "1d", T0,
            FreeGraph("1d", series, "2026-07-05", "2026-10-05", T0 - 90.days, T0, "fixed_start"), emptyMap())
        val split = splitGraphV2ServerTab(tab, GraphV2GeneralKey(uid, userEpoch, "usd", GraphPeriod.THREE_MONTHS.code), krxEpoch, "r1", null)
        return (split as GraphV2Validation.Valid).value
    }

    /** Runs a suspend read that never suspends (the fake port answers at once) and returns its result in place. */
    private fun <T> runNow(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return checkNotNull(outcome) { "premise: 11-2/11-3 the seed read finished without suspending" }.getOrThrow()
    }

    /** One row's record: mismatches by contract part, probe failures, and the window counts its premises read. */
    private class RowRecord {
        val mismatches = linkedMapOf<String, MutableList<String>>()
        val failures = mutableListOf<Throwable>()
        val windows = linkedMapOf<String, Int>()
        fun mismatch(part: String, line: String) { mismatches.getOrPut(part) { mutableListOf() } += line }
        fun window(name: String) { windows[name] = (windows[name] ?: 0) + 1 }
        fun count(name: String) = windows[name] ?: 0
        fun part(name: String): List<String> = mismatches[name].orEmpty()
    }

    /**
     * The four snapshot consumers on one switch-off coordinator, all reading `{ coordinator.accessSnapshot }`, judged at every
     * publication from inside the hook. A probe fence names the publication's own token (the use authority reads only the grant).
     */
    private inner class ConsumerRig(val h: KrxHarness) {
        private val supplier: () -> TopicAccessSnapshot = { h.coordinator.accessSnapshot }
        val sua = SnapshotTopicUseAuthority(supplier)
        private var gateIdentity: AuthIdentityFence? = null
        private var gateFence: TopicSessionFence? = null
        val gate = GraphV2AccessGate({ gateIdentity }, { gateFence }, supplier, { true })
        private val head = HeadGate({ gateIdentity }, { gateFence }, supplier, { true })

        val publications = mutableListOf<TopicAccessSnapshot>()
        val readsOtherThanThePublication = mutableListOf<Long>()
        val sua11 = RowRecord()
        val gate11 = RowRecord()
        val seed11 = RowRecord()
        val read11 = RowRecord()
        private val lifetimes = linkedSetOf<TopicUseLifetime>()
        private val krxUses = linkedSetOf<KrxUseLifetime>()
        private val captures = linkedSetOf<GraphV2AccessCapture>()

        fun install() {
            h.coordinator.observePublications { s ->
                publications += s
                if (supplier() !== s) readsOtherThanThePublication += s.revision
                runCatching { probeSua(s) }.onFailure { sua11.failures += it }
                runCatching { probeGateAndSeed(s) }.onFailure { gate11.failures += it; seed11.failures += it }
                runCatching { probeSendRead(s) }.onFailure { read11.failures += it }
            }
        }

        private fun fenceFor(s: TopicAccessSnapshot) =
            TopicSessionFence(OWNER, s.facts.recordFence?.userAccessEpoch, s.facts.token ?: TopicGrantToken(-1L))

        private fun probeSua(s: TopicAccessSnapshot) {
            val r = sua11
            val fence = fenceFor(s)
            val head = s.headAcquireUse(fence)
            val real = sua.acquire(fence)
            if (real != head) r.mismatch("general", "rev=${s.revision} acquire: real=$real head=$head")
            head?.let { lifetimes += it }
            for (lifetime in lifetimes) {
                val expected = s.headAdmitsUse(lifetime)
                r.window(if (expected) "admitted" else "refused")
                if (sua.admits(lifetime) != expected) r.mismatch("general", "rev=${s.revision} admits($lifetime): head=$expected")
            }
            val epoch = s.facts.recordFence?.krxCapabilityEpoch
            if (head != null && s.facts.capabilityAllowed && epoch != null) r.window("krxIssueWindow")
            val krx = sua.acquireKrx(fence)
            if (krx != null) r.mismatch("krx", "rev=${s.revision} acquireKrx = $krx (HEAD: no KRX use)")
            // The N cell of this publication — what a missing guard (G-OFF) would admit — and every earlier one.
            head?.let { krxUses += KrxUseLifetime(it, epoch, s.capabilityInvalidations) }
            for (use in krxUses) {
                r.window("krxJudged")
                if (sua.admitsKrx(use)) r.mismatch("krx", "rev=${s.revision} admitsKrx($use) = true (HEAD: no KRX use)")
            }
        }

        private fun probeGateAndSeed(s: TopicAccessSnapshot) {
            val fence = fenceFor(s)
            val lifetime = s.headAcquireUse(fence)
            if (lifetime != null) {
                gateIdentity = fence.identity
                gateFence = fence
                val real = gate.bindWithConfiguration(fence, lifetime)
                val expected = head.bindWithConfiguration(fence, lifetime)
                gate11.window("bound")
                if (expected?.captured?.krxCapabilityEpoch != null) gate11.window("boundWithKrx")
                if (real != expected) gate11.mismatch("bind", "rev=${s.revision}: real=$real head=$expected")
                real?.captured?.let { captures += it }
            }
            for (c in captures) {
                gateIdentity = c.fence.identity
                gateFence = c.fence
                for (component in GraphV2DiskComponent.entries) {
                    val expected = head.admits(c, component)
                    if (expected) gate11.window("admits$component")
                    if (gate.admits(c, component) != expected) gate11.mismatch("admits", "rev=${s.revision} $component $c: head=$expected")
                    if (gate.ioAdmission(c).admits(component) != head.ioAdmission(c).admits(component)) {
                        gate11.mismatch("ioAdmission", "rev=${s.revision} $component $c")
                    }
                }
                val parts = components(c.fence.identity.uid, c.fence.userAccessEpoch ?: "none",
                    c.krxCapabilityEpoch ?: s.facts.recordFence?.krxCapabilityEpoch ?: "none")
                val joined = head.joinForExposure(c, parts)
                if (joined?.krxJoined == true) gate11.window("joinedKrx")
                if (gate.joinForExposure(c, parts) != joined) gate11.mismatch("join", "rev=${s.revision} $c default count: head=$joined")
                // The D-6 parameter with another original count (design 02-O1's OFF cell): off, the count must not decide, so the
                // answer is HEAD's. A missing or bypassed guard (G-OFF) applies C-1 here and drops a KRX HEAD joins.
                val otherCount = c.capabilityInvalidations + 1
                if (joined?.krxJoined == true) gate11.window("joinedKrxUnderOtherCount")
                if (gate.joinForExposure(c, parts, otherCount) != joined) {
                    gate11.mismatch("join", "rev=${s.revision} $c another original count (off: the D-6 count must not decide): head=$joined")
                }
                // Self-check (G-OFF control): HEAD's join with C-1 applied on the D-6 count and no guard — what the mutation answers.
                val c1 = c.krxCapabilityEpoch == s.facts.recordFence?.krxCapabilityEpoch && otherCount == s.capabilityInvalidations
                val gOffJoined = if (joined == null || c1) joined else joinGraphV2Components(parts.general, null)
                if (gOffJoined != joined) gate11.window("gOffControlCaught")
                val store = FakeStore(parts)
                val realSeed = runNow { readGraphV2DiskSeed(GraphV2CachePorts(store, gate, {}), KEY, c, null) }
                val headSeed = runNow { headReadSeed(head, store, KEY, c, null) }
                if (headSeed.general is GraphV2DiskRead.Found) seed11.window("generalFound")
                if (headSeed.krx is GraphV2DiskRead.Found) seed11.window("krxFound")
                if (headSeed.general is GraphV2DiskRead.Found && headSeed.krx == null) seed11.window("krxNotAttempted")
                if (realSeed != headSeed) seed11.mismatch("seed", "rev=${s.revision} $c: real=$realSeed head=$headSeed")
            }
        }

        private fun probeSendRead(s: TopicAccessSnapshot) {
            val r = read11
            val epoch = s.facts.recordFence?.krxCapabilityEpoch
            val expectedMarker = KrxSendMarker(epoch, 0L)
            if (epoch != null) r.window("markerWithEpoch")
            for (lifetime in lifetimes) {
                val admission = sua.admitsWithKrx(lifetime)
                val expected = s.headAdmitsUse(lifetime)
                r.window(if (expected) "admitted" else "refused")
                if (admission.admitted != expected) r.mismatch("general", "rev=${s.revision} admitsWithKrx($lifetime).admitted=${admission.admitted} head=$expected")
                if (admission.krxUse != null) r.mismatch("krxUse", "rev=${s.revision} admitsWithKrx($lifetime).krxUse=${admission.krxUse}")
                if (admission.marker != expectedMarker) r.mismatch("marker", "rev=${s.revision} admitsWithKrx marker=${admission.marker} expected=$expectedMarker")
            }
            val fence = fenceFor(s)
            val acquisition = sua.acquireWithKrx(fence)
            val head = s.headAcquireUse(fence)
            if (head != null) r.window("acquired")
            if (acquisition.lifetime != head) r.mismatch("general", "rev=${s.revision} acquireWithKrx.lifetime=${acquisition.lifetime} head=$head")
            if (acquisition.krxUse != null) r.mismatch("krxUse", "rev=${s.revision} acquireWithKrx.krxUse=${acquisition.krxUse}")
            if (acquisition.marker != expectedMarker) r.mismatch("marker", "rev=${s.revision} acquireWithKrx marker=${acquisition.marker} expected=$expectedMarker")
        }
    }

    /** A session with the KRX topic as its whole test desired set, on the same authority as its send read (D-3), answering REST with unknown_topic. */
    private class RestRig(test: TestScope, authority: KrxSendReadAuthority, job: Job) {
        var live: AuthIdentityFence? = null
        var fence: TopicSessionFence? = null
        var focused = false
        val bootstrapCalls = mutableListOf<String>()
        val undelivered = mutableListOf<Pair<String, TopicSnapshotOutcome>>()
        val withMarker = mutableListOf<Triple<TopicSessionFence, Map<String, TopicRejectionReason>, KrxSendMarker?>>()
        val twoArgument = mutableListOf<Map<String, TopicRejectionReason>>()
        val session = TopicSessionCoordinator(
            scope = CoroutineScope(job + StandardTestDispatcher(test.testScheduler)),
            clock = object : TopicCommandClock {
                override fun nowMillis(): Long = test.testScheduler.currentTime
                override suspend fun sleep(duration: Duration) = delay(duration)
            },
            connect = { throw IOException("TA-11 rig: no socket") },
            credentials = object : TopicCommandCredentials {
                override suspend fun currentSnapshot() = AuthSnapshot(OWNER.uid, OWNER.authGeneration, "token")
                override suspend fun refreshAfterUnauthorized(rejected: AuthSnapshot): AuthSnapshot? = null
                override suspend fun recordRejected(credential: AuthSnapshot) {}
                override suspend fun recordRejectionEvidence(credential: AuthSnapshot) {}
            },
            orders = AccessOrderSequence(),
            authority = authority,
            bootstrap = { _, topic, _ ->
                bootstrapCalls += topic
                UNSUPPORTED
            },
            store = TopicSubscriptionStateStore(),
            encode = { "encoded" },
            newRequestId = { "r" },
            jitter = { 0.0 },
            liveIdentity = { live },
            bootstrapIssueGap = Duration.ZERO,
            onRejected = { _, rejected -> twoArgument += rejected },
            onBootstrapUndelivered = { _, topic, outcome -> undelivered += topic to outcome },
            desired = setOf(KRX),
            krxSendReader = authority,
            onRejectedWithMarker = { owner, rejected, marker -> withMarker += Triple(owner, rejected, marker) }
        )
    }

    /**
     * One KRX REST issue as the send read saw it (11-5): the marker `acquireWithKrx` returned, the marker the switch-off contract
     * expects from the same published snapshot (`(recordFence.krxCapabilityEpoch, 0)`, §2 ④ D-20), and that snapshot's permit.
     */
    private data class IssuedMarker(val marker: KrxSendMarker, val expected: KrxSendMarker, val permit: Long)

    private data class RestCheckpoint(
        val label: String,
        val permit: Long,
        val issueExpected: Boolean,
        val bootstraps: Int,
        val krxUnsupportedUndelivered: Int,
        val markerForwards: Int,
        val krxTwoArgumentForwards: Int,
        val issued: List<IssuedMarker>
    )

    private fun offContrast(body: suspend TestScope.(KrxHarness) -> Unit) = runTest {
        val processJob = SupervisorJob()
        try {
            body(krxHarness(processJob, HEAD_FACTORY))
        } finally {
            processJob.cancel()
        }
    }

    /** The A1-shaped input (A1Test od2Sequence :643-685) on the switch-off coordinator, with [checkpoint] after each step. */
    private suspend fun TestScope.a1Sequence(h: KrxHarness, checkpoint: suspend TestScope.(String) -> Unit = {}) {
        h.grantedKrxVisible()
        checkpoint("granted")
        h.undecidedRejection()
        checkpoint("H1′ undecided rejection")
        h.source.next = { KRX_FALSE }
        step(5_010)
        checkpoint("H1′ FE answered false (C1 false edge)")
        h.source.next = { ACTIVE }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        checkpoint("re-permit")
        h.source.next = { KRX_FALSE }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        checkpoint("C1 from allowed")
        h.source.next = { ACTIVE }
        h.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        checkpoint("re-permit 2")
        h.rejectKrx(h.issueGrant())
        checkpoint("H1 rotation")
        step(100)
        checkpoint("H1 FE answered")
        settleKrx()
        checkpoint("settled")
    }

    /** premise(OFF) and the read premise shared by 11-1..11-4. */
    private fun assertOffPublications(row: String, rig: ConsumerRig) {
        assertTrue("premise: $row the coordinator published through the sequence (${rig.publications.size})", rig.publications.size >= 10)
        assertEquals("premise(OFF): $row every publication has krxPermitSeq = 0",
            emptyList<Long>(), rig.publications.filter { it.krxPermitSeq != 0L }.map { it.revision })
        assertEquals("premise(OFF): $row every publication has capabilityInvalidations = 0",
            emptyList<Long>(), rig.publications.filter { it.capabilityInvalidations != 0L }.map { it.revision })
        assertTrue("premise: $row the sequence reached a capability-allowed publication", rig.publications.any { it.facts.capabilityAllowed })
        assertTrue("premise: $row the sequence reached a capability-blocked publication", rig.publications.any { !it.facts.capabilityAllowed })
        assertEquals("premise: $row every probe's supplier read was the publication it judged", emptyList<Long>(), rig.readsOtherThanThePublication)
    }

    /**
     * 11-1..11-4 input: the A1-shaped sequence with a grant issued at every checkpoint (the harness's explicit issue, HEAD path
     * topicGrantResult — KrxContestHarness.kt:261-262), so each epoch and capability state between the steps is also judged under a
     * standing token.
     */
    private suspend fun TestScope.observedSequence(h: KrxHarness): ConsumerRig {
        val rig = ConsumerRig(h)
        rig.install()
        a1Sequence(h) { h.coordinator.topicGrantResult() }
        return rig
    }

    /**
     * 11-1 (SUA, §2 ③(b2), T.13): at every switch-off publication the use authority's general acquire/admits equal the frozen HEAD
     * copy, and it issues no KRX use and admits none (HEAD had none). Window premise: a publication with a general use, capability
     * allowed and a record epoch — where a missing guard would issue a KRX use — was judged. Catches G-OFF on the SUA.
     */
    @Test fun r11_1_suaOff_generalIsHeadsAndNoKrxUseIsIssuedOrAdmitted() = offContrast { h ->
        val rig = observedSequence(h)
        val r = rig.sua11
        println("11-1 windows=${r.windows} publications=${rig.publications.size}")
        assertOffPublications("11-1", rig)
        assertTrue("premise: 11-1 general admits was judged both ways (${r.windows})", r.count("admitted") >= 1 && r.count("refused") >= 1)
        assertTrue("premise: 11-1 a KRX issue window was published (general use ∧ capabilityAllowed ∧ record epoch) (${r.windows})", r.count("krxIssueWindow") >= 1)
        assertTrue("premise: 11-1 candidate KRX uses were judged (${r.windows})", r.count("krxJudged") >= 1)
        assertEquals("11-1 probe failures", emptyList<Throwable>(), r.failures)
        assertEquals("11-1 SUA general acquire/admits = HEAD copy at every publication", emptyList<String>(), r.part("general"))
        assertEquals("11-1 SUA issues and admits no KRX use at any switch-off publication", emptyList<String>(), r.part("krx"))
    }

    /**
     * 11-2 (gate: bind, admits GENERAL/KRX, ioAdmission, joinForExposure — §2 ③(b2), T.13): over the same publications, each capture
     * already bound is judged again under the current publication, and every answer equals the frozen HEAD gate (GraphV2AccessGate.kt:
     * 24-81 at de50885). joinForExposure is asked with the default count and with another original count (`capabilityInvalidations + 1`,
     * the D-6 parameter — design 02-O1 treats `joinForExposure(n, krx, c0)` with p = 0 as an OFF cell whose answer is HEAD's). Window
     * premises: KRX was admitted and joined somewhere, including under the other count, so the KRX branch really ran. Catches G-OFF on
     * the exposure path (a missing or bypassed guard applies C-1, and the other count drops a KRX HEAD joins); for admits and
     * ioAdmission G-OFF is not observable on this path (an off epoch move breaks the token, §2 ③(b2)) and TA-10 10-1 catches it.
     */
    @Test fun r11_2_gateOff_everyAnswerIsHeads() = offContrast { h ->
        val rig = observedSequence(h)
        val r = rig.gate11
        println("11-2 windows=${r.windows} publications=${rig.publications.size}")
        assertOffPublications("11-2", rig)
        assertTrue("premise: 11-2 binds were judged, some with a KRX epoch (${r.windows})", r.count("bound") >= 1 && r.count("boundWithKrx") >= 1)
        assertTrue("premise: 11-2 HEAD admitted GENERAL and KRX somewhere (${r.windows})", r.count("admitsGENERAL") >= 1 && r.count("admitsKRX") >= 1)
        assertTrue("premise: 11-2 HEAD joined KRX somewhere (${r.windows})", r.count("joinedKrx") >= 1)
        assertTrue("premise: 11-2 KRX joined under another original count (the G-OFF window of the D-6 parameter) (${r.windows})",
            r.count("joinedKrxUnderOtherCount") >= 1)
        assertTrue("11-2 self-check: a G-OFF join (C-1 on the D-6 count, no guard) differs from HEAD on these publications (${r.windows})",
            r.count("gOffControlCaught") >= 1)
        assertEquals("11-2 probe failures", emptyList<Throwable>(), r.failures)
        for (part in listOf("bind", "admits", "ioAdmission", "join")) {
            assertEquals("11-2 gate $part = HEAD at every switch-off publication", emptyList<String>(), r.part(part))
        }
    }

    /**
     * 11-3 (seed, §13 T.11 11-3): design text "replaced by 11-2 while α leaves GraphV2Cache.kt:103-104 alone; if α changes the seed code,
     * add a fake-port row for readGraphV2DiskSeed (:85-114)". Written here as that fake-port row regardless: the production seed read,
     * with the production gate fed by the switch-off publications, returns exactly what the frozen HEAD seed read returns with the frozen
     * HEAD gate. Window premises: a KRX half was read and one was not attempted.
     */
    @Test fun r11_3_seedOff_theSeedReadIsHeads() = offContrast { h ->
        val rig = observedSequence(h)
        val r = rig.seed11
        println("11-3 windows=${r.windows} publications=${rig.publications.size}")
        assertOffPublications("11-3", rig)
        assertTrue("premise: 11-3 a GENERAL half and a KRX half were read (${r.windows})", r.count("generalFound") >= 1 && r.count("krxFound") >= 1)
        assertTrue("premise: 11-3 a KRX half was not attempted (${r.windows})", r.count("krxNotAttempted") >= 1)
        assertEquals("11-3 probe failures", emptyList<Throwable>(), r.failures)
        assertEquals("11-3 readGraphV2DiskSeed = HEAD at every switch-off publication", emptyList<String>(), r.part("seed"))
    }

    /**
     * 11-4 (the session's send read — the TSC decision seam, §5A.3, §2 ④ D-20, T.13): at every switch-off publication the WS form
     * (`admitsWithKrx`) and the REST form (`acquireWithKrx`) give the general answer HEAD gives (`admitsUse` / `acquireUse`), no KRX use,
     * and a non-null marker `(recordFence.krxCapabilityEpoch, 0)` — off, the marker carries no permit guard. Window premises: both forms
     * ran with a general answer, and with a record epoch to carry.
     */
    @Test fun r11_4_sendReadOff_generalIsHeadsNoKrxUseAndTheMarkerIsEpochZero() = offContrast { h ->
        val rig = observedSequence(h)
        val r = rig.read11
        println("11-4 windows=${r.windows} publications=${rig.publications.size}")
        assertOffPublications("11-4", rig)
        assertTrue("premise: 11-4 the WS form was judged both ways and the REST form acquired (${r.windows})",
            r.count("admitted") >= 1 && r.count("refused") >= 1 && r.count("acquired") >= 1)
        assertTrue("premise: 11-4 publications carried a record epoch (${r.windows})", r.count("markerWithEpoch") >= 1)
        assertEquals("11-4 probe failures", emptyList<Throwable>(), r.failures)
        assertEquals("11-4 send read general answer = HEAD admitsUse/acquireUse", emptyList<String>(), r.part("general"))
        assertEquals("11-4 send read carries no KRX use off", emptyList<String>(), r.part("krxUse"))
        assertEquals("11-4 send read marker = (recordFence.krxCapabilityEpoch, 0), non-null", emptyList<String>(), r.part("marker"))
    }

    /**
     * 11-5 (REST unknown_topic hand-over guard, §6.2 ②, §6.6, T.13): a real session — the internal constructor with the same authority
     * as its send read and the marker refusal channel, the KRX topic as its test desired set — is given the switch-off coordinator's
     * current grant at every checkpoint and asked for the KRX bootstrap, which the server refuses with `unknown_topic`. Window premise
     * per checkpoint: when HEAD's acquireUse grants a use, the KRX bootstrap was issued and its Unsupported answer reached the
     * undelivered branch (HEAD's `onBootstrapUndelivered`, where the hand-over sits). Contract: (i) at each such checkpoint the KRX issue
     * read the send read — `acquireWithKrx`, recorded by a wrapper passed as both `authority` and `krxSendReader` (D-3) — and the marker
     * it carries is the non-null `(recordFence.krxCapabilityEpoch, 0)` of that read (§2 ③(b2) "표지 값", §5B, §2 ④ D-20: the input the
     * hand-over guard is given); (ii) nothing is ever handed over — no marker refusal, no two-argument refusal of KRX. Catches R-OFF (the
     * guard missing: off, a p = 0 marker would be handed over and the coordinator would decide KrxEntitlementRequired on HEAD's path),
     * on the guard's real input rather than on a session that never reaches the α branch. On the α stub (i) fails: its session issues
     * KRX through `authority.acquire` and never asks the send read.
     */
    @Test fun r11_5_restOff_unknownTopicIsNeverHandedOver() = offContrast { h ->
        val sessionJob = SupervisorJob()
        try {
            val base = SnapshotTopicUseAuthority { h.coordinator.accessSnapshot }
            val issued = mutableListOf<IssuedMarker>()
            val read = object : KrxSendReadAuthority by base {
                override fun acquireWithKrx(fence: TopicSessionFence): KrxSendAcquisition {
                    val s = h.coordinator.accessSnapshot
                    return base.acquireWithKrx(fence).also {
                        issued += IssuedMarker(it.marker, KrxSendMarker(s.facts.recordFence?.krxCapabilityEpoch, 0L), s.krxPermitSeq)
                    }
                }
            }
            val rest = RestRig(this, read, sessionJob)
            val publications = h.coordinator.observePublications()
            rest.session.start()
            rest.session.setOnline(true)
            val checkpoints = mutableListOf<RestCheckpoint>()
            a1Sequence(h) { label ->
                val granted = h.coordinator.topicGrantResult().fence
                if (granted != null && granted != rest.fence) {
                    rest.fence = granted
                    rest.live = granted.identity
                    rest.session.setAccess(true, granted, TopicGrantOrigin.NewContext)
                    if (!rest.focused) {
                        rest.session.setFocus(granted.identity, FreeTab.USD)
                        rest.focused = true
                    }
                }
                runCurrent()
                val snapshot = h.coordinator.accessSnapshot
                val expected = rest.fence?.let { snapshot.headAcquireUse(it) } != null
                val b0 = rest.bootstrapCalls.size
                val u0 = rest.undelivered.count { it == KRX to UNSUPPORTED }
                val m0 = rest.withMarker.size
                val t0 = rest.twoArgument.count { KRX in it }
                val i0 = issued.size
                rest.session.requestBootstrap(KRX)
                runCurrent()
                checkpoints += RestCheckpoint(
                    label, snapshot.krxPermitSeq, expected,
                    rest.bootstrapCalls.size - b0,
                    rest.undelivered.count { it == KRX to UNSUPPORTED } - u0,
                    rest.withMarker.size - m0,
                    rest.twoArgument.count { KRX in it } - t0,
                    issued.subList(i0, issued.size).toList()
                )
            }
            println("11-5 checkpoints=$checkpoints publications=${publications.size} bootstraps=${rest.bootstrapCalls.size} undelivered=${rest.undelivered.size} issued=${issued.size}")
            assertEquals("premise(OFF): 11-5 every publication has krxPermitSeq = 0",
                emptyList<Long>(), publications.filter { it.krxPermitSeq != 0L }.map { it.revision })
            assertEquals("premise(OFF): 11-5 every checkpoint snapshot has krxPermitSeq = 0", emptyList<String>(), checkpoints.filter { it.permit != 0L }.map { it.label })
            val entered = checkpoints.filter { it.issueExpected }
            assertTrue("premise: 11-5 checkpoints where HEAD grants a use (${checkpoints.map { it.label to it.issueExpected }})", entered.size >= 3)
            assertEquals("premise: 11-5 at each such checkpoint the KRX bootstrap was issued and its unknown_topic reached the undelivered branch",
                emptyList<String>(), entered.filter { it.bootstraps < 1 || it.krxUnsupportedUndelivered < 1 }.map { "${it.label}: $it" })
            assertEquals("premise: 11-5 every KRX issue was answered unknown_topic", setOf(KRX), rest.bootstrapCalls.toSet())
            assertEquals("premise(OFF): 11-5 every send-read KRX issue read a snapshot with krxPermitSeq = 0",
                emptyList<IssuedMarker>(), issued.filter { it.permit != 0L })
            assertEquals("11-5 at each entered checkpoint the KRX issue read the send read (acquireWithKrx) and carries the non-null marker " +
                "(recordFence.krxCapabilityEpoch, 0) of that read",
                emptyList<String>(), entered.filter { it.issued.isEmpty() || it.issued.any { m -> m.marker != m.expected } }.map { "${it.label}: issued=${it.issued}" })
            assertEquals("11-5 no marker refusal is handed over at any checkpoint", emptyList<String>(), checkpoints.filter { it.markerForwards != 0 }.map { "${it.label}: $it" })
            assertEquals("11-5 no marker refusal is handed over in the whole run", emptyList<Any>(), rest.withMarker)
            assertEquals("11-5 no two-argument refusal names KRX in the whole run", emptyList<Any>(), rest.twoArgument.filter { KRX in it })
        } finally {
            sessionJob.cancel()
        }
    }
}
