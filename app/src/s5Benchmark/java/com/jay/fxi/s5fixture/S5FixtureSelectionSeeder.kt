package com.jay.fxi.s5fixture

import com.jay.fxi.data.local.GraphSelectionStore
import javax.inject.Inject

/**
 * S5-4 slice 4b: stores the bundle's tether selection for the fixture user in the app's one selection store, the instance the
 * graph builder reads, and confirms it. The only fixture file allowed to name that store.
 */
internal class S5FixtureSelectionSeeder @Inject constructor(
    private val store: GraphSelectionStore,
    private val assets: S5FixtureAssets
) {
    suspend fun seed(): Boolean =
        seedSelection(bundleSelection(assets.bytes("bundle.json")), store::writeGraphSelection, store::confirmGraphSelection)
}
