package com.jay.fxi.domain.model

import kotlinx.datetime.Instant

/** What one quote is about: who published it, and for what. */
data class TopicQuoteKey(val source: String, val asset: String)

/**
 * One source's price for one asset, at the moment that price became true.
 *
 * [at] is already the merge time — `rate_changed_at` when the server sent one, `timestamp`
 * otherwise. The wire distinction is resolved at the boundary and does not travel further: for
 * USDT and KRX entries served from Redis, `timestamp` can be a five-second `seen_at` bucket while
 * `rate_changed_at` is the real change, and every consumer wants the latter. Carrying both would
 * mean every consumer re-deciding which one it meant.
 */
data class TopicQuote(
    val source: String,
    val asset: String,
    val rate: Double,
    val at: Instant
) {
    val key: TopicQuoteKey get() = TopicQuoteKey(source, asset)
}

/**
 * The dollar index, which is nobody's quote for anything.
 *
 * It has no asset, so it cannot be keyed like the rest and lives in a slot of its own. [source] is
 * the supplier that answered — investing, cnbc or yahoo — and says where the number came from, not
 * what it is.
 */
data class TopicDollarIndex(
    val rate: Double,
    val at: Instant,
    val source: String
)

/**
 * Everything the topics have told us, and the rule for what a new frame is allowed to change.
 *
 * **Strictly newer only.** A frame replaces a quote when it is later than the one held, and does
 * nothing otherwise. Two paths deliver the same quotes — the REST bootstrap and the socket — and
 * they race: without this, a bootstrap response that left the server before the last live frame
 * could land after it and put an old price back on screen.
 *
 * **Equal times keep what is held**, which is the contract's answer to a case it cannot resolve.
 * Usually the second arrival is the same reading redelivered, and keeping the first stops the
 * display churning. But two genuinely different changes can share a millisecond, and v1 carries no
 * per-message sequence to order them (`REALTIME_V2_CLIENT_GUIDE.md` §5), so nothing here can tell
 * the two cases apart. What is held wins — which does mean the earlier arrival wins, deliberately
 * and consistently, rather than the display depending on which path answered last.
 *
 * **A group that is not in a frame is not a deletion.** Topics publish the groups they have; a
 * snapshot without a reference quote means the server had none to send, not that the one held
 * stopped existing. Nothing here removes a key — only the owner of a UID or epoch change does.
 */
data class TopicRates(
    val quotes: Map<TopicQuoteKey, TopicQuote> = emptyMap(),
    val dollarIndex: TopicDollarIndex? = null
) {
    /**
     * Folds a frame's quotes in, each under the same rule.
     *
     * Repeats inside one frame need no rule of their own: folding compares each against what is
     * held so far, so the later of the two wins exactly as it would across two frames — and two
     * repeats sharing an instant leave the earlier one, for the same reason as above. One rule is
     * the point: a separate first-wins clause here would be a second thing to remember and a
     * second thing to get wrong.
     */
    fun merge(incoming: List<TopicQuote>): TopicRates {
        if (incoming.isEmpty()) return this
        val next = quotes.toMutableMap()
        incoming.forEach { quote ->
            val held = next[quote.key]
            if (held == null || quote.at > held.at) next[quote.key] = quote
        }
        return if (next == quotes) this else copy(quotes = next)
    }

    /** The same rule for the index, which has one slot rather than a keyed map. */
    fun merge(incoming: TopicDollarIndex): TopicRates {
        val held = dollarIndex
        return if (held != null && incoming.at <= held.at) this else copy(dollarIndex = incoming)
    }
}
