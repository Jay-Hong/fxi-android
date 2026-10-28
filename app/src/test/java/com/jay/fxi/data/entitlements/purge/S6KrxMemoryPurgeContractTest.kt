package com.jay.fxi.data.entitlements.purge

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.remote.*
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.KRX
import com.jay.fxi.data.remote.KrxSessionIntegrationRig.Companion.USD
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.field
import com.jay.fxi.domain.model.TopicDeliveryState
import com.jay.fxi.domain.model.TopicRejectionReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class S6KrxMemoryPurgeContractTest {
    @Test fun m01_wsAndRestRevocation_clearOnlyKrxMemory() = s63Test("M01", "ws", "rest-prior-grant") { s, column ->
        s.live(seconds = 181)
        val general = s.r.rates.quotes.filterKeys { it.source != "krx" }
        val epoch = s.epoch(); val gate = s.waitQuery()
        if (column == "ws") s.r.ack(s.renew(), reject = true)
        else {
            val call = s.issue()
            s.yes("REST was issued while KRX was granted", call.read.facts.capabilityAllowed)
            s.f.reply(call, KrxA1bFixture.unsupported)
        }
        s.yes("valid PAC revocation publication before FE answer", s.r.snapshot.lastCapabilityEnd != null || s.epoch() != epoch)
        s.yes("FE response remains held", !gate.isCompleted)
        s.noKrx()
        s.eq("GENERAL quotes survive capability-only revocation", general, s.r.rates.quotes.filterKeys { it.source != "krx" })
        s.event(KrxMemoryCause.CAPABILITY_END)
        s.yes("read-only snapshot was used", s.cleanupReads > 0)
        s.yes("session result does not claim disk completion", s.cleanup.all { it.result in KrxMemoryCleanupResult.values() })
    }

    @Test fun m02_falseAndEpochRetirement_preserveNewScope() = s63Test("M02", "stable-false", "epoch-rotation") { s, column ->
        s.live(); val original = s.r.snapshot; val originalEpoch = s.epoch()
        s.f.loss(if (column == "stable-false") "false" else "epoch")
        s.yes("explicit end/retired original epoch published", s.r.snapshot.lastCapabilityEnd != original.lastCapabilityEnd || s.epoch() != originalEpoch)
        s.noKrx(); s.event(KrxMemoryCause.CAPABILITY_END)
        s.r.h.source.next = { EntitlementsOutcome.StableActive(true) }
        s.r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS); s.r.drain()
        s.acceptLatest(1772.0)
        val current = s.r.rates; val currentLive = s.liveRates
        val currentSnapshot = s.r.snapshot
        // Boundary-input: a late old end with its original owner/namespace; use remains current K2.
        s.memorySnapshot = { currentSnapshot.copy(revision = currentSnapshot.revision + 1,
            lastCapabilityEnd = TopicAccessEnd((currentSnapshot.lastCapabilityEnd?.sequence ?: 0) + 1,
                TopicAccessEndReason.CAPABILITY_REVOKED, original.facts.binding,
                original.facts.recordFence?.ownerUid, originalEpoch)) }
        s.r.revise(); s.r.drain()
        s.eq("late old end cannot select K2 display", current, s.r.rates)
        s.eq("late old end cannot select K2 live data", currentLive, s.liveRates)
        s.yes("new scope differs from original", s.epoch() != originalEpoch)
        s.generalWorks()
    }

    @Test fun m03_repeatedCleanup_andUserEnd_keepTheirScopes() = s63Test("M03", "repeat-end", "withheld", "user-end", "uid-change") { s, column ->
        s.live()
        when (column) {
            "repeat-end" -> {
                s.f.loss("false"); s.noKrx()
                val before = s.cleanup.count { it.result == KrxMemoryCleanupResult.SESSION_MEMORY_CLEANED }
                repeat(3) { s.r.revise(); s.r.drain() }
                s.eq("one effective explicit cleanup", 1, before)
                s.eq("same end has no repeated effect", before, s.cleanup.count { it.result == KrxMemoryCleanupResult.SESSION_MEMORY_CLEANED })
            }
            "withheld" -> {
                val rates = s.r.rates; val live = s.liveRates; val call = s.issue()
                s.hold()
                s.eq("simple hold preserves verified display data", rates, s.r.rates)
                s.eq("simple hold preserves verified live data", live, s.liveRates)
                s.yes("simple hold blocks use and cancels in-flight", !s.r.snapshotSupply().facts.capabilityAllowed && call.cancelled)
                s.event(KrxMemoryCause.WITHHELD, KrxMemoryCleanupResult.NOTHING_SELECTED)
            }
            else -> {
                val general = s.r.rates.copy(quotes = s.r.rates.quotes.filterKeys {
                    it != com.jay.fxi.domain.model.TopicQuoteKey("krx", "usd-krw-futures") })
                val liveGeneral = s.liveRates.copy(quotes = s.liveRates.quotes.filterKeys {
                    it != com.jay.fxi.domain.model.TopicQuoteKey("krx", "usd-krw-futures") })
                s.f.loss(if (column == "uid-change") "uid" else "premium")
                s.yes("USER boundary really published", s.r.snapshot.lastUserEnd != null)
                s.noKrx()
                if (column == "user-end") {
                    // HEAD TGD forwards an end with the SAME delivered fence. TSC preserves
                    // GENERAL backing rates on withdrawal; only a moved grant resets all rates.
                    s.eq("same-fence USER end preserves HEAD GENERAL display data", general, s.r.rates)
                    s.eq("same-fence USER end preserves HEAD GENERAL live data", liveGeneral, s.liveRates)
                } else {
                    // Establish the actual new UID grant before claiming whole-context cleanup.
                    s.r.h.source.next = { EntitlementsOutcome.StableActive(true) }
                    s.r.pac.refresh(RefreshIntent.FORCE_PREMIUM); s.r.drain()
                    s.yes("new UID grant really published", s.r.snapshot.facts.userAllowed &&
                        s.r.snapshot.facts.binding?.ownerUid == s.r.identity?.uid)
                    s.eq("moved UID grant clears prior display data as HEAD does", emptyMap<Any, Any>(), s.r.rates.quotes)
                    s.eq("moved UID grant clears prior live data as HEAD does", emptyMap<Any, Any>(), s.liveRates.quotes)
                }
                s.event(KrxMemoryCause.USER_END)
            }
        }
    }

    @Test fun m04_unresolvedAndForeignScopes_neverExpandDeletion() = s63Test("M04", "null-owner", "null-namespace", "unknown-owner", "foreign-uid", "current-k2") { s, column ->
        s.live()
        val retiredEpoch = s.epoch()
        if (column == "current-k2") { s.f.loss("epoch"); s.acceptLatest(1774.0) }
        val original = s.r.snapshot; val before = s.r.rates; val liveBefore = s.liveRates
        val cleanedBefore = s.cleanup.count { it.result == KrxMemoryCleanupResult.SESSION_MEMORY_CLEANED }
        val fence = s.need(original.facts.recordFence, "original data scope")
        val end = TopicAccessEnd((original.lastCapabilityEnd?.sequence ?: 0) + 1, TopicAccessEndReason.CAPABILITY_REVOKED,
            original.facts.binding,
            when (column) { "null-owner" -> null; "unknown-owner" -> null; "foreign-uid" -> "other-user"; else -> fence.ownerUid },
            when (column) { "null-namespace" -> null; "current-k2" -> retiredEpoch; else -> fence.krxCapabilityEpoch })
        // Boundary-input: raw end scope is ambiguous/foreign, while the ordinary use reader is held.
        s.memorySnapshot = { original.copy(revision = original.revision + 2, lastCapabilityEnd = end,
            facts = if (column == "unknown-owner") original.facts.copy(recordFence = null) else original.facts) }
        s.hold()
        s.yes("scope selection happens in held window", !s.r.snapshotSupply().facts.capabilityAllowed)
        s.eq("unresolved/foreign end cannot expand display deletion", before, s.r.rates)
        s.eq("unresolved/foreign end cannot expand live deletion", liveBefore, s.liveRates)
        if (column in setOf("null-owner", "null-namespace", "unknown-owner")) {
            s.event(KrxMemoryCause.CAPABILITY_END, KrxMemoryCleanupResult.UNRESOLVED_SCOPE)
        } else s.eq("foreign namespace cannot report this session cleaned", cleanedBefore,
            s.cleanup.count { it.result == KrxMemoryCleanupResult.SESSION_MEMORY_CLEANED })
        s.r.krxTick(1994.0)
        s.yes("unresolved scope does not authorize fresh data", s.r.rates.quotes.values.none { it.rate == 1994.0 })
        s.yes("cleanup was actually evaluated", s.cleanupReads > 0)
    }

    @Test fun m07_wsUnavailable_keepsCapabilityAndRejection() = s63Test("M07") { s, _ ->
        s.live(seconds = 181); val request = s.renew()
        val snapshot = s.r.snapshot; val calls = s.r.h.source.calls.size
        val journal = s.r.h.store.record.pendingPurges
        val general = s.r.rates.quotes.filterKeys { it.source != "krx" }
        val generalGeneration = s.r.store.snapshot.stateFor(USD).receiveGeneration
        s.yes("inside current WS ACK window", s.r.sends.last().request == request && field(s.live, "ended") == false)
        s.unavailable(request)
        s.noKrx()
        s.eq("canonical D1 rejection survives expire", TopicRejectionReason.TOPIC_UNAVAILABLE, s.r.store.snapshot.stateFor(KRX).rejection)
        s.eq("D1 does not revoke or issue a permit", snapshot, s.r.snapshot)
        s.eq("D1 issues no FE", calls, s.r.h.source.calls.size)
        s.eq("D1 creates no journal work", journal, s.r.h.store.record.pendingPurges)
        s.eq("GENERAL data untouched", general, s.r.rates.quotes.filterKeys { it.source != "krx" })
        s.eq("GENERAL receive generation unchanged", generalGeneration, s.r.store.snapshot.stateFor(USD).receiveGeneration)
        s.r.krxTick(1997.0)
        s.yes("canonical rejection continues to block late frame", s.r.rates.quotes.values.none { it.rate == 1997.0 })
        s.event(KrxMemoryCause.TOPIC_UNAVAILABLE)
        s.generalWorks()
    }

    @Test fun m08_rest404Kinds_doNotShareWsPurge() = s63Test("M08", "degraded", "dormant", "other-404", "unsupported") { s, column ->
        s.live(); val call = s.issue()
        s.eq("real HTTP body window", KrxA1bFixture.Stage.BODY, call.stage)
        val before = s.r.rates; val live = s.liveRates; val snapshot = s.r.snapshot
        val queries = s.r.h.source.calls.size
        val cleaned = s.cleanup.count { it.result == KrxMemoryCleanupResult.SESSION_MEMORY_CLEANED }
        val error = when (column) { "degraded" -> "topic_unavailable"; "dormant" -> "topics_disabled"; "other-404" -> "unrecognized_error"; else -> "unknown_topic" }
        val gate = if (column == "unsupported") s.waitQuery() else null
        s.f.reply(call, KrxA1bFixture.Reply(404, """{"error":"$error","supported_topics":[]}"""))
        if (column == "unsupported") {
            s.yes("typed Unsupported delivered its original marker", s.r.issuer.reserved.any { it.marker == com.jay.fxi.data.entitlements.KrxSendMarker(call.read.facts.recordFence?.krxCapabilityEpoch, call.read.krxPermitSeq) })
            s.yes("FE response remains withheld", gate?.isCompleted == false)
            s.noKrx(); s.event(KrxMemoryCause.CAPABILITY_END)
        } else {
            s.eq("REST status/name alone does not purge display", before, s.r.rates)
            s.eq("REST status/name alone does not purge live memory", live, s.liveRates)
            s.eq("non-Unsupported has no FE handover", queries, s.r.h.source.calls.size)
            s.eq("no capability revocation", snapshot, s.r.snapshot)
            s.eq("no D1 cleanup", cleaned, s.cleanup.count { it.result == KrxMemoryCleanupResult.SESSION_MEMORY_CLEANED })
        }
    }

    @Test fun m09_displayAndLiveRates_arePurgedTogether() = s63Test("M09") { s, _ ->
        s.live(seconds = 181)
        s.r.wire.deliver(TopicSessionKrxHarness.usdFrame(1339.0))
        s.r.wire.deliver("""{"type":"snapshot","version":1,"topic":"dxy:spot","data":{"dxy":{"rate":99.9,"timestamp":"2026-09-26T01:20:00Z","source":"investing"}}}""")
        s.r.drain()
        s.yes("DXY positive control populated", s.r.rates.dollarIndex?.rate == 99.9)
        s.yes("exchange positive control populated", s.r.rates.quotes.keys.any { it.source == "upbit" })
        // Boundary data positive controls: exact KRX key must not overmatch source/asset lookalikes.
        val at = kotlinx.datetime.Instant.parse("2026-09-26T01:20:00Z")
        val extras = listOf(com.jay.fxi.domain.model.TopicQuote("krx", "other-asset", 91.0, at),
            com.jay.fxi.domain.model.TopicQuote("other-source", "usd-krw-futures", 92.0, at))
        val shown = field(s.session, "_rates") as kotlinx.coroutines.flow.MutableStateFlow<com.jay.fxi.domain.model.TopicRates>
        shown.value = shown.value.merge(extras)
        S6KrxCleanupRig.setField(s.session, "liveRates", s.liveRates.merge(extras))
        val displayBefore = s.r.rates; val liveBefore = s.liveRates
        val state = s.r.store.snapshot.stateFor(KRX)
        s.yes("both surfaces populated before D1", S6KrxCleanupRig.hasKrx(displayBefore) && S6KrxCleanupRig.hasKrx(liveBefore))
        s.unavailable(s.renew()); s.noKrx()
        val key = com.jay.fxi.domain.model.TopicQuoteKey("krx", "usd-krw-futures")
        s.eq("display removes exactly KRX quote key", displayBefore.copy(quotes = displayBefore.quotes - key), s.r.rates)
        s.eq("live removes exactly KRX quote key", liveBefore.copy(quotes = liveBefore.quotes - key), s.liveRates)
        val after = s.r.store.snapshot.stateFor(KRX)
        s.eq("KRX confirmed false", false, after.confirmed)
        s.eq("KRX desired preserved", state.desired, after.desired)
        s.eq("KRX receive generation preserved", state.receiveGeneration, after.receiveGeneration)
        s.eq("KRX delivery degraded", TopicDeliveryState.DEGRADED, after.deliveryState)
        s.eq("rejection not forgotten", TopicRejectionReason.TOPIC_UNAVAILABLE, after.rejection)
    }

    @Test fun m10_generalInFlightAndNamespace_areUnchanged() = s63Test("M10", "ws", "rest") { s, column ->
        s.live(); s.issue()
        val call = s.f.calls.singleOrNull { it.topic == USD }
            ?: throw AssertionError("premise: M10 exactly one original GENERAL REST call exists")
        s.yes("GENERAL REST body in flight", !call.cancelled && call.stage == KrxA1bFixture.Stage.BODY)
        val namespace = s.r.snapshot.facts.recordFence?.userAccessEpoch
        val generation = s.r.store.snapshot.stateFor(USD).receiveGeneration
        val connection = s.live; val generalLeases = s.generalRegistry.held
        val userEnd = s.r.snapshot.lastUserEnd
        s.hold()
        s.eq("KRX registry cleanup really ran", emptyMap<Any, Any>(), s.registry.held)
        s.eq("same connection and GENERAL lease", connection, s.live)
        s.eq("GENERAL leases preserved", generalLeases, s.generalRegistry.held)
        s.yes("GENERAL in-flight use remains valid", !call.cancelled && call.admitted())
        if (column == "rest") s.f.reply(call, KrxA1bFixture.Reply(200, TopicSessionKrxHarness.usdFrame(1399.0)))
        else { s.r.wire.deliver(TopicSessionKrxHarness.usdFrame(1399.0)); s.r.drain() }
        s.yes("GENERAL completion applies under original use", s.r.rates.quotes.values.any { it.rate == 1399.0 })
        s.eq("GENERAL namespace unchanged", namespace, s.r.snapshot.facts.recordFence?.userAccessEpoch)
        s.eq("no USER end fabricated", userEnd, s.r.snapshot.lastUserEnd)
        if (column == "rest") {
            // HEAD applyBootstrapEntries deliberately does not call recordFrame: the REST
            // publication and adopted live offer are evidence; WS receiveGeneration stays put.
            s.eq("GENERAL REST does not fabricate a WS receipt", generation, s.r.store.snapshot.stateFor(USD).receiveGeneration)
            s.yes("GENERAL REST publishes under its original attribution", s.r.graph.any {
                it is TopicGraphInput.Observations && it.topic == USD && it.path == TopicGraphPath.REST_BOOTSTRAP
            })
            s.yes("GENERAL REST adopted data reaches the live offer", s.r.offers.any { rates -> rates.quotes.values.any { it.rate == 1399.0 } })
        } else s.eq("GENERAL WS records exactly one receipt", generation + 1, s.r.store.snapshot.stateFor(USD).receiveGeneration)
    }

    @Test fun m11_canonicalOnlyExplicitEnd_expiresWithoutRates() = s63Test("M11", "canonical-only", "nothing-selected") { s, column ->
        val canonical = column == "canonical-only"
        s.live(ack = canonical, rates = false)
        val original = s.r.snapshot
        val tuple = s.tuple()
        s.noKrx()
        s.eq("ACK-only window has the expected canonical confirmation", canonical, s.r.store.snapshot.stateFor(KRX).confirmed)
        s.hold()
        s.yes("capture retired before the explicit end", tuple in s.records && field(s.live, "krxActive") == null)
        val before = s.r.store.snapshot.stateFor(KRX)
        s.yes("desired canonical has not already expired", before.desired && before.deliveryState != TopicDeliveryState.DEGRADED)
        s.noKrx()
        val events = s.cleanup.size
        // The hold is the labelled boundary-input in holdRoundTrip; the end below is a real PAC publication.
        s.memorySnapshot = { s.r.snapshot }
        s.f.loss("false")
        assertSameOwnerEnd(s, original)
        val after = s.r.store.snapshot.stateFor(KRX)
        if (canonical) {
            s.eq("canonical-only end expires confirmation", false, after.confirmed)
            s.eq("canonical-only end degrades desired delivery", TopicDeliveryState.DEGRADED, after.deliveryState)
            s.eq("expire preserves desired, rejection and receive evidence", before.copy(confirmed = false,
                deliveryState = TopicDeliveryState.DEGRADED), after)
            assertNewEvent(s, events, KrxMemoryCause.CAPABILITY_END, KrxMemoryCleanupResult.SESSION_MEMORY_CLEANED)
        } else {
            s.eq("no canonical or rates selected means expire has no effect", before, after)
            assertNewEvent(s, events, KrxMemoryCause.CAPABILITY_END, KrxMemoryCleanupResult.NOTHING_SELECTED)
            s.yes("empty selection reports no cleaning", s.cleanup.drop(events).none {
                it.result == KrxMemoryCleanupResult.SESSION_MEMORY_CLEANED })
        }
        s.noKrx()
    }

    @Test fun m12_generalOnlyAckBeforeEnd_keepsCanonicalOwnership() = s63Test("M12", "with-rates", "without-rates", "old-rates-valid-k2") { s, column ->
        s.live(rates = column != "without-rates", seconds = 181)
        val original = s.r.snapshot
        val oldTuple = s.tuple()
        val display = s.r.rates; val live = s.liveRates
        val sends = s.r.sends.size
        s.hold()
        s.yes("hold retired the original capture before GENERAL ACK", oldTuple in s.records && field(s.live, "krxActive") == null)
        if (column == "old-rates-valid-k2") {
            s.release()
            s.r.ack(s.nextKrx(sends)) // K2 canonical only: deliberately do not send a K2 price.
            val use = s.need(field(s.active(), "use") as? KrxUseLifetime, "K2 original use")
            s.yes("K2 is valid after the old use's hold", s.r.snapshotSupply().admitsKrxUse(use) &&
                use.capabilityInvalidations > original.capabilityInvalidations)
            // A lease renewal can legitimately include the valid K2. Select only USD for a
            // real manual command instead; this changes GENERAL state, never KRX provenance.
            val owner = s.r.display.owner
                ?: throw AssertionError("premise: M12 old-rates-valid-k2: current display owner exists")
            s.r.store.markDegraded(USD)
            org.junit.Assert.assertTrue("premise: M12 old-rates-valid-k2: only USD is manual-eligible on its tab",
                USD in s.r.store.snapshot.manualRetryTopics &&
                    (TopicBootstrapOrder.shownBy(com.jay.fxi.domain.model.FreeTab.USD) + KRX)
                        .filter { it in s.r.store.snapshot.manualRetryTopics }.toSet() == setOf(USD))
            val after = s.r.sends.size
            s.session.retryTopics(owner, com.jay.fxi.domain.model.FreeTab.USD)
            s.r.until("premise: M12 old-rates-valid-k2: manual command reaches a physical subscribe", 750) {
                s.r.sends.drop(after).any { it.accepted && it.request.type == "subscribe" }
            }
            val request = s.r.sends.drop(after).filter { it.accepted && it.request.type == "subscribe" }
                .singleOrNull()?.request
                ?: throw AssertionError("premise: M12 old-rates-valid-k2: exactly one current manual subscribe")
            org.junit.Assert.assertTrue("premise: M12 old-rates-valid-k2: inside current USD-only ACK window with valid K2",
                request.topics.toSet() == setOf(USD) && field(s.live, "opened") == true && field(s.live, "ended") == false &&
                    field(s.r.store, "currentTicket") != null && s.r.snapshotSupply().admitsKrxUse(use) &&
                    s.r.store.snapshot.stateFor(KRX).confirmed)
            ackGeneralOnly(s, request)
        } else ackGeneralOnly(s, s.renew())
        val before = s.r.store.snapshot.stateFor(KRX)
        s.eq("GENERAL ACK retains delivery evidence", display, s.r.rates)
        s.eq("GENERAL ACK retains live evidence", live, s.liveRates)
        s.yes("desired delivery has not already degraded", before.desired && before.deliveryState != TopicDeliveryState.DEGRADED)
        val events = s.cleanup.size
        if (column == "old-rates-valid-k2") {
            s.yes("valid K2 remains confirmed despite a GENERAL-only wire restatement", before.confirmed)
            val current = s.r.snapshotSupply()
            val owner = s.need(original.facts.recordFence, "old price namespace")
            // boundary-input: a late old end with the same epoch but the pre-hold use. Actual PAC
            // reachability of this coalesced same-p publication is unconfirmed; K2 uses the real SUA.
            val ended = current.copy(revision = current.revision + 1,
                lastCapabilityEnd = TopicAccessEnd((current.lastCapabilityEnd?.sequence ?: 0) + 1,
                    TopicAccessEndReason.CAPABILITY_REVOKED, original.facts.binding,
                    owner.ownerUid, owner.krxCapabilityEpoch))
            s.memorySnapshot = { ended }
            s.r.revise(); s.r.drain()
            s.noKrx()
            s.eq("old prices cannot expire the valid K2 canonical", before, s.r.store.snapshot.stateFor(KRX))
            s.yes("K2 capture remains valid after old price cleanup", s.r.snapshotSupply().admitsKrxUse(
                s.need(field(s.active(), "use") as? KrxUseLifetime, "retained K2 original use")))
        } else {
            s.eq("GENERAL-only ACK omitted KRX from canonical active state", false, before.confirmed)
            s.yes("still inside held use window", !s.r.snapshotSupply().facts.capabilityAllowed && field(s.live, "krxActive") == null)
            s.memorySnapshot = { s.r.snapshot }
            s.f.loss("false")
            assertSameOwnerEnd(s, original)
            s.eq("retained canonical ownership still expires after GENERAL ACK", before.copy(confirmed = false,
                deliveryState = TopicDeliveryState.DEGRADED), s.r.store.snapshot.stateFor(KRX))
            s.noKrx()
        }
        assertNewEvent(s, events, KrxMemoryCause.CAPABILITY_END, KrxMemoryCleanupResult.SESSION_MEMORY_CLEANED)
    }

    @Test fun m13_rotationWithUnscopedKrxKey_isUnresolved() = s63Test("M13",
        "unknown-only", "known-old-plus-unknown", "known-foreign", "current-k2") { s, column ->
        s.live(rates = false)
        val original = s.r.snapshot
        val oldEpoch = s.epoch()
        if (column == "current-k2") {
            s.f.loss("epoch"); s.acceptLatest(1783.0)
            s.yes("positive control really adopted a different current namespace", s.epoch() != oldEpoch)
        } else {
            if (column == "unknown-only") {
                injectUnscopedKrx(s, "display"); injectUnscopedKrx(s, "live")
            } else if (column == "known-old-plus-unknown") {
                injectUnscopedKrx(s, "display")
                s.r.krxTick(1583.0) // Older than injected display: only live adopts the original scope.
                s.eq("known original live value was adopted", 1583.0, s.liveRates.quotes[exactKrxKey]?.rate)
                s.eq("unknown display value was not replaced", 1983.0, s.r.rates.quotes[exactKrxKey]?.rate)
            } else s.r.krxTick(1583.0)
            s.hold()
            s.yes("rotation selection starts after capture retirement", field(s.live, "krxActive") == null)
        }
        val current = s.r.snapshotSupply()
        val record = s.need(current.facts.recordFence, "confirmed record before rotation")
        // boundary-input: read-only cleanup publication with no explicit end record. A real PAC
        // may coalesce an intermediate end, but this exact publication's reachability is unconfirmed.
        // Foreign record and current-K2 are deletion-zero controls; K2 was established by real PAC.
        val rotated = current.copy(revision = current.revision + 1, lastUserEnd = null, lastCapabilityEnd = null,
            facts = current.facts.copy(recordFence = record.copy(
                ownerUid = if (column == "known-foreign") "other-owner" else record.ownerUid,
                krxCapabilityEpoch = if (column == "current-k2") record.krxCapabilityEpoch else "s63a-rotated-$oldEpoch"),
                recordUnconfirmed = false, userContextUncertain = false, capabilityContextUncertain = false))
        s.yes("confirmed changed epoch is visible without an explicit end", rotated.lastCapabilityEnd == null &&
            rotated.lastUserEnd == null && !rotated.facts.recordUnconfirmed && !rotated.facts.capabilityContextUncertain &&
            rotated.facts.recordFence?.krxCapabilityEpoch != original.facts.recordFence?.krxCapabilityEpoch)
        val display = s.r.rates; val live = s.liveRates
        val state = s.r.store.snapshot.stateFor(KRX)
        val events = s.cleanup.size
        s.memorySnapshot = { rotated }
        s.r.revise(); s.r.drain()
        if (column == "known-old-plus-unknown") {
            s.eq("rotation preserves unknown display key", display, s.r.rates)
            s.eq("rotation removes only known old live key", live.copy(quotes = live.quotes - exactKrxKey), s.liveRates)
        } else {
            s.eq("rotation does not expand display deletion", display, s.r.rates)
            s.eq("rotation does not expand live deletion", live, s.liveRates)
        }
        if (column in setOf("unknown-only", "known-old-plus-unknown")) {
            assertNewEvent(s, events, KrxMemoryCause.CAPABILITY_END, KrxMemoryCleanupResult.UNRESOLVED_SCOPE)
        } else {
            s.eq("foreign/current K2 canonical is not expired", state, s.r.store.snapshot.stateFor(KRX))
            s.yes("foreign/current K2 deletion count is zero", s.cleanup.drop(events).none {
                it.result == KrxMemoryCleanupResult.SESSION_MEMORY_CLEANED })
        }
    }

    @Test fun m14_d1WithUnscopedKrxKey_isUnresolved() = s63Test("M14",
        "unknown-display", "unknown-live", "unknown-both", "known-old-unknown-display", "known-old-unknown-live") { s, column ->
        s.live(rates = false, seconds = 181)
        val surface = if (column.endsWith("live")) "live" else "display"
        injectUnscopedKrx(s, surface)
        if (column == "unknown-both") injectUnscopedKrx(s, "live")
        val knownOther = column.startsWith("known-old")
        if (knownOther) {
            s.r.krxTick(1584.0) // Only the other surface adopts; the injected future key stays unscoped.
            val other = if (surface == "display") s.liveRates else s.r.rates
            s.eq("other surface adopted the original WS namespace", 1584.0, other.quotes[exactKrxKey]?.rate)
        }
        s.r.generalTick()
        val request = s.renew()
        s.yes("inside valid current KRX WS ACK window", KRX in request.topics &&
            s.r.sends.any { it.accepted && it.request == request } && field(s.live, "ended") == false &&
            field(s.pending(), "sent") == true && s.r.snapshotSupply().facts.capabilityAllowed)
        val display = s.r.rates; val live = s.liveRates
        val state = s.r.store.snapshot.stateFor(KRX)
        val snapshot = s.r.snapshot
        val events = s.cleanup.size
        s.unavailable(request)
        val expectedDisplay = if (knownOther && surface == "live") display.copy(quotes = display.quotes - exactKrxKey) else display
        val expectedLive = if (knownOther && surface == "display") live.copy(quotes = live.quotes - exactKrxKey) else live
        s.eq("D1 preserves unknown display and selects only known original scope", expectedDisplay, s.r.rates)
        s.eq("D1 preserves unknown live and selects only known original scope", expectedLive, s.liveRates)
        val after = s.r.store.snapshot.stateFor(KRX)
        s.eq("D1 canonical rejection survives partial cleanup", TopicRejectionReason.TOPIC_UNAVAILABLE, after.rejection)
        s.eq("D1 canonical is unconfirmed and degraded", state.copy(confirmed = false,
            deliveryState = TopicDeliveryState.DEGRADED, rejection = TopicRejectionReason.TOPIC_UNAVAILABLE), after)
        s.eq("D1 does not revoke capability to resolve unknown provenance", snapshot, s.r.snapshot)
        assertNewEvent(s, events, KrxMemoryCause.TOPIC_UNAVAILABLE, KrxMemoryCleanupResult.UNRESOLVED_SCOPE)
    }

    private val exactKrxKey get() = com.jay.fxi.domain.model.TopicQuoteKey("krx", "usd-krw-futures")

    /** boundary-input: exact key inserted without provenance; no real PAC/transport path is known
     * to create this state. Only the backing rates are changed; no sidecar or cleanup helper is called.
     * A later older real frame can adopt the other surface without claiming this synthetic key. */
    private fun injectUnscopedKrx(s: S6KrxCleanupRig, surface: String) {
        s.yes("unscoped injection starts with no exact KRX key on its surface", !S6KrxCleanupRig.hasKrx(
            if (surface == "display") s.r.rates else s.liveRates))
        val quote = com.jay.fxi.domain.model.TopicQuote("krx", "usd-krw-futures", 1983.0,
            kotlinx.datetime.Instant.parse("2026-10-01T00:00:00Z"))
        if (surface == "display") {
            @Suppress("UNCHECKED_CAST")
            val shown = s.need(field(s.session, "_rates") as? kotlinx.coroutines.flow.MutableStateFlow<com.jay.fxi.domain.model.TopicRates>,
                "display backing state flow")
            shown.value = shown.value.merge(listOf(quote))
        } else S6KrxCleanupRig.setField(s.session, "liveRates", s.liveRates.merge(listOf(quote)))
        s.eq("exact unscoped key installed on $surface", quote,
            (if (surface == "display") s.r.rates else s.liveRates).quotes[exactKrxKey])
    }

    private fun ackGeneralOnly(s: S6KrxCleanupRig, request: com.jay.fxi.data.remote.dto.TopicSubscribeRequest) {
        s.yes("inside a real GENERAL-only subscribe ACK window", request.type == "subscribe" && request.topics.isNotEmpty() &&
            KRX !in request.topics && s.r.sends.any { it.accepted && it.request == request } &&
            field(s.live, "ended") == false && field(s.r.store, "currentTicket") != null)
        val entries = request.topics.joinToString(",") {
            """{"topic":"$it","lease_id":"${request.requestId}-$it","lease_duration_seconds":600}"""
        }
        // A real current request's valid whole-connection answer deliberately omits KRX from active.
        s.r.wire.deliver("""{"type":"subscription_ack","request_id":"${request.requestId}","operation":"subscribe","accepted_topics":[$entries],"rejected_topics":[],"removed_topics":[],"active_subscriptions":[$entries]}""")
        s.r.drain()
        s.yes("GENERAL ACK was admitted", request.topics.all { s.r.store.snapshot.stateFor(it).confirmed })
    }

    private fun assertSameOwnerEnd(s: S6KrxCleanupRig, original: TopicAccessSnapshot) {
        val owner = s.need(original.facts.recordFence, "original canonical namespace")
        val end = s.need(s.r.snapshot.lastCapabilityEnd, "real PAC explicit capability end")
        s.yes("same owner and original namespace explicitly ended", end.ownerUid == owner.ownerUid &&
            end.namespace == owner.krxCapabilityEpoch && end.binding == original.facts.binding &&
            end.sequence > (original.lastCapabilityEnd?.sequence ?: 0))
    }

    private fun assertNewEvent(s: S6KrxCleanupRig, from: Int, cause: KrxMemoryCause, result: KrxMemoryCleanupResult) {
        s.yes("new memory event $cause/$result", s.cleanup.drop(from).any { it.cause == cause && it.result == result })
    }
}
