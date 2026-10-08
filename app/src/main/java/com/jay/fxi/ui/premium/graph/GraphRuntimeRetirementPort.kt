package com.jay.fxi.ui.premium.graph

import com.jay.fxi.data.graph.GraphCapabilityScope
import com.jay.fxi.data.graph.GraphDataScope
import com.jay.fxi.data.graph.GraphRuntimeRetirement
import com.jay.fxi.data.graph.GraphV2RequestCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * S4 RT01-B3 dormant port: retires coordinator memory first, then the screen holders supplied by the
 * mount, in one block without suspension on [main]. LIVE_SCOPE_SELECTED returns without accessing
 * [holders]. Otherwise it walks a copy of the holder collection and returns the coordinator's result
 * unchanged. NOTHING_TO_REMOVE means only that the coordinator newly removed nothing; holder
 * publication and guard cleanup still run.
 *
 * Caller preconditions: coordinator and holders belong to the same assembly, and holders use its
 * main dispatcher, fence supplier, gate and recorder. Selected named epochs must have ended permanently
 * and must never be reused or republished; capability rotation must invalidate the old use lifetime.
 * Selectors must be pure and give the same answer for the same scope throughout the call.
 * None of these is checked here. RT01 fixtures (graphB301) verify the same-assembly and same-main
 * wiring for their own assembly only; epoch permanence, the lifetime invalidation and selector purity
 * remain obligations of the issuer and of the caller that supplies the selectors.
 *
 * Do not call from coordinator event processing, a sink consumer call or a holder callback: calls
 * already on [main] can run inline in [withContext], so those calls would reenter runtime mutation.
 * Exceptions propagate. A coordinator exception reaches no holder; a holder exception does not roll
 * back the coordinator or earlier holders. This port excludes topic graphLoss, recorder and sink purge.
 */
internal class GraphRuntimeRetirementPort(
    private val coordinator: GraphV2RequestCoordinator,
    private val holders: () -> Collection<GraphV2ScreenStateHolder>,
    private val main: CoroutineDispatcher
) {
    suspend fun retireScopes(selects: (GraphDataScope) -> Boolean): GraphRuntimeRetirement = withContext(main) {
        val result = coordinator.retireScopes(selects)
        if (result != GraphRuntimeRetirement.LIVE_SCOPE_SELECTED) {
            holders().toList().forEach { it.retireScopes(selects) }
        }
        result
    }

    suspend fun retireCapabilities(selects: (GraphCapabilityScope) -> Boolean): GraphRuntimeRetirement = withContext(main) {
        val result = coordinator.retireCapabilities(selects)
        if (result != GraphRuntimeRetirement.LIVE_SCOPE_SELECTED) {
            holders().toList().forEach { it.retireCapabilities() }
        }
        result
    }
}
