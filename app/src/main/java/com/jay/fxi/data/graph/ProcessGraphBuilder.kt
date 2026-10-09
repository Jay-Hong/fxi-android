package com.jay.fxi.data.graph

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.free.FreeSnapshotSchedulePolicy
import com.jay.fxi.data.free.InstallSeedSource
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionStore
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicGraphRecoveryPermit
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.time.SystemAppClock
import com.jay.fxi.ui.premium.graph.GraphV2ScreenStateHolder
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * S4 CUT-CC4b: what the topic owner's graph boundary builds: the early assembly and the seed source its starter reads.
 * S4 CUT-CC5-2: and [newHolder], which builds one fresh, unstarted premium FX holder for a tab over this assembly and a fresh
 * PREMIUM selection session, on the given scope and the assembly's Main; the screen host starts and closes what it returns. If
 * building the holder fails after its session exists, it secures a non-cancellable job on that scope that closes the
 * session and rethrows the original at once; it reports nothing itself. A failure closing the session is attached to the
 * original later, as suppressed, so a report already made of the original does not carry it.
 */
internal class ProcessGraphParts(
    val assembly: GraphRuntimeAssembly,
    val seeds: InstallSeedSource,
    val newHolder: (tab: String, display: StateFlow<TopicDisplayState>, focus: StateFlow<OwnedTopicFocus?>, scope: CoroutineScope) ->
        GraphV2ScreenStateHolder
)

/**
 * S4 CUT-CC4b: builds the process graph pieces inside the topic owner's graph boundary, on Main (`cut_cc4b_agreed.r1.md` §2.1).
 * The assembly reads its permit through [build]'s permit slot, its jitter seed through the seed slot and its time event through
 * the given function; it is a child of the given parent. A builder that returns owns nothing more; until it returns, it is
 * responsible for cleaning up what it built.
 */
internal fun interface ProcessGraphBuilder {
    fun build(
        permit: LateBound<() -> TopicGraphRecoveryPermit?>,
        seed: LateBound<String>,
        timeEvent: () -> Unit,
        parent: Job
    ): ProcessGraphParts
}

/**
 * S4 CUT-CC4b: the production [ProcessGraphBuilder]. Constructing it resolves nothing; [build] resolves the graph's providers
 * first — the seed source, the disk store, the deletion admission, the authenticated client, the CC1 use authority the
 * topic factory shares and (S4 CUT-CC5-2) the selection store — and only then constructs the assembly, whose holders capture
 * that same authority, whose own constructor cleans up after its failures. Event
 * failures go to Crashlytics; diagnostics log only their component and reason, never a key, fence or cause.
 */
internal class AppProcessGraphBuilder @Inject constructor(
    private val disk: Provider<FileGraphV2DiskStore>,
    private val deletions: Provider<DeletionAdmissionStore>,
    private val seeds: Provider<InstallSeedSource>,
    private val api: Provider<AuthenticatedApiClient>,
    private val uses: Provider<TopicUseAuthority>,
    private val selections: Provider<GraphSelectionStore>,
    private val coordinator: PremiumAccessCoordinator,
    private val tokens: AuthTokenProvider
) : ProcessGraphBuilder {

    override fun build(
        permit: LateBound<() -> TopicGraphRecoveryPermit?>,
        seed: LateBound<String>,
        timeEvent: () -> Unit,
        parent: Job
    ): ProcessGraphParts {
        val seedSource = seeds.get()
        val store = disk.get()
        val admission = GraphProtectedAdmission(tokens::currentIdentityFence, deletions.get())
        val client = api.get()
        val authority = uses.get()
        val selectionStore = selections.get()
        val assembly = GraphRuntimeAssembly(
            accessSnapshot = { coordinator.accessSnapshot },
            accessRevisions = coordinator.accessRevisions,
            liveIdentity = tokens::currentIdentityFence,
            uses = authority,
            protectedAdmission = admission,
            fetcher = AuthenticatedGraphV2Fetcher(client),
            owners = AuthenticatedGraphOwnerSource(tokens, client),
            cachePorts = { gate ->
                GraphV2CachePorts(
                    store = store,
                    gate = gate,
                    onSeedDiagnostic = { Log.w(TAG, "graph seed ${it.component}: ${it.reason}") },
                    writePorts = GraphV2WritePorts(
                        prepareWrite = IssuerGraphWritePreparation(coordinator::markGraphData),
                        onPreparationBlocked = { Log.w(TAG, "graph write preparation ${it.component}: ${it.reason}") },
                        onWriteDiagnostic = { Log.w(TAG, "graph write ${it.component}: ${it.reason}") }
                    )
                )
            },
            main = Dispatchers.Main,
            parent = parent,
            clock = SystemAppClock,
            rateLimitJitter = { tab -> FreeSnapshotSchedulePolicy.jitterFor(seed.require(), tab) },
            onEventFailure = ::reportGraphFailure,
            recoveryPermit = { permit.require()() },
            timeEvent = timeEvent
        )
        return ProcessGraphParts(assembly, seedSource) { tab, display, focus, scope ->
            val session = GraphSeriesSelectionSession(selectionStore, GraphSelectionAudience.PREMIUM, tab, scope, Dispatchers.Main)
            try {
                GraphV2ScreenStateHolder(
                    tab = tab,
                    coordinator = assembly.coordinator,
                    selectionSession = session,
                    liveIdentity = tokens::currentIdentityFence,
                    display = display,
                    focus = focus,
                    currentAccessFence = assembly.fences.current,
                    uses = authority,
                    gate = assembly.gate,
                    accessRevisions = coordinator.accessRevisions,
                    scope = scope,
                    dispatcher = Dispatchers.Main,
                    recorder = assembly.recorder
                )
            } catch (failure: Throwable) {
                scope.launch(start = CoroutineStart.ATOMIC) {
                    withContext(NonCancellable) {
                        runCatching { session.close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                    }
                }
                throw failure
            }
        }
    }

    private companion object {
        const val TAG = "ProcessGraph"
    }
}

/** S4 CUT-CC4b: a recoverable graph failure, to Crashlytics; a failure to report does not escape. */
internal fun reportGraphFailure(failure: Throwable) {
    runCatching { FirebaseCrashlytics.getInstance().recordException(failure) }
}
