package com.jay.fxi.s5fixture

import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.local.GraphSelectionReadResult
import com.jay.fxi.data.local.GraphSelectionRecord
import com.jay.fxi.data.local.GraphSelectionWriteResult
import com.jay.fxi.domain.model.GraphSeriesSelection
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S5-4 slice 4b: the fixture's selection seed is the bundle's, and it counts only when the store committed and read it back. */
class S5FixtureSeedRuleTest {

    private val seed = bundleSelection(File("src/benchmark/assets/s5_4/bundle.json").readBytes())
    private val ids = listOf(
        "upbit.usdt-krw", "bithumb.usdt-krw", "coinone.usdt-krw", "korbit.usdt-krw", "gopax.usdt-krw", "krx.usd-krw-futures",
        "investing.usd", "kb.usd", "hana.usd", "dxy", "dxy_futures"
    ).toSet()

    private fun record(key: GraphSelectionKey, selection: GraphSeriesSelection) =
        GraphSelectionRecord(1, key.uid, key.audience, key.tab, selection.visibleSeriesIds, selection.initializedSeries)

    @Test
    fun `S5-4 4b the seed is the bundle's eleven series under the fixture user's premium tether key`() {
        assertEquals(GraphSeriesSelection(ids, ids), seed)
        assertEquals(GraphSelectionKey("s5-fixture", GraphSelectionAudience.PREMIUM, "tether"), S5_FIXTURE_SELECTION_KEY)
    }

    @Test
    fun `S5-4 4b a committed and read-back seed succeeds, anything else fails`() = runTest {
        val writes = mutableListOf<Pair<GraphSelectionKey, GraphSeriesSelection>>()
        val ok = seedSelection(seed, { k, s -> writes += k to s; GraphSelectionWriteResult.Committed },
            { k -> GraphSelectionReadResult.Present(record(k, seed)) })
        assertTrue(ok)
        assertEquals(listOf(S5_FIXTURE_SELECTION_KEY to seed), writes)

        val failure = IOException("x")
        val other = GraphSeriesSelection(setOf("dxy"), ids)
        val cases = listOf(
            "not committed" to seedSelection(seed, { _, _ -> GraphSelectionWriteResult.NotCommitted(failure) }, { k -> GraphSelectionReadResult.Present(record(k, seed)) }),
            "uncertain" to seedSelection(seed, { _, _ -> GraphSelectionWriteResult.Uncertain(failure) }, { k -> GraphSelectionReadResult.Present(record(k, seed)) }),
            "absent" to seedSelection(seed, { _, _ -> GraphSelectionWriteResult.Committed }, { GraphSelectionReadResult.Absent }),
            "unreadable" to seedSelection(seed, { _, _ -> GraphSelectionWriteResult.Committed }, {
                GraphSelectionReadResult.Unreadable(com.jay.fxi.data.local.GraphSelectionUnreadableReason.IO) }),
            "another selection" to seedSelection(seed, { _, _ -> GraphSelectionWriteResult.Committed }, { k -> GraphSelectionReadResult.Present(record(k, other)) }),
            "another user" to seedSelection(seed, { _, _ -> GraphSelectionWriteResult.Committed }, { k ->
                GraphSelectionReadResult.Present(record(k.copy(uid = "someone"), seed)) }),
            "another tab" to seedSelection(seed, { _, _ -> GraphSelectionWriteResult.Committed }, { k ->
                GraphSelectionReadResult.Present(record(k.copy(tab = "usd"), seed)) })
        )
        for ((label, result) in cases) assertFalse(label, result)
    }
}
