package com.jay.fxi.ui.free

import com.jay.fxi.data.alerts.SourceAlertRowPolicy
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.RateSanity

/**
 * S8-7 (`R4c/S8/s8_7_contract.codex.r2.md` §3.1): what the tether free preview opens with — each allowed exchange's usdt-krw
 * rate in the tether snapshot's own exchange list, hidden rows included, in the order received. A row counts only when its
 * exact (source, asset) pair is one of the row policy's and its rate is plausible; each source keeps its first valid rate. The
 * banks, the reference, the graph and the shown rows are not read.
 */
internal fun sourcePreviewQuotes(rate: FreeRate.Grouped): Map<String, Double> {
    val out = LinkedHashMap<String, Double>()
    for (entry in rate.usdtKrw) {
        if (!SourceAlertRowPolicy.EXCHANGES.admits(entry.source, entry.asset) || !RateSanity.isPlausible(entry.rate)) continue
        if (entry.source !in out) out[entry.source] = entry.rate
    }
    return out
}
