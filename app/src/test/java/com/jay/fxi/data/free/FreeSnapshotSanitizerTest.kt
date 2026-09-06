package com.jay.fxi.data.free

import com.jay.fxi.data.remote.dto.FreeSnapshotFixtures as F
import com.jay.fxi.data.remote.dto.with
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.FreeSnapshot
import com.jay.fxi.domain.model.SourceRate
import com.jay.fxi.domain.model.GraphPeriod
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FreeSnapshotSanitizerTest {
    private val sanitizer = FreeSnapshotSanitizer()

    @Test
    fun allTabsAndPeriods_acceptOwnEnvelopeAndRejectEachEchoMutation() {
        // Kills omitted tab OR period checks, unknown-tab acceptance, and deny-all implementations.
        listOf("usd", "jpy", "eur", "tether").forEach { tab ->
            GraphPeriod.entries.forEach { period ->
                val wire = F.snapshot(tab, period.code)
                val good = sanitizer.sanitize(F.decode(wire), tab, period)
                assertEquals(tab, good.tab)
                assertEquals(period, good.period)
                assertEquals(1, good.testRates.size)
                assertEquals(1, good.graph.series.size)
                assertThrows(FreeSnapshotValidationException::class.java) {
                    sanitizer.sanitize(F.decode(wire.with("tab", JsonPrimitive("unknown"))), tab, period)
                }
                assertThrows(FreeSnapshotValidationException::class.java) {
                    sanitizer.sanitize(F.decode(wire.with("period", JsonPrimitive("other"))), tab, period)
                }
            }
        }
        assertThrows(FreeSnapshotValidationException::class.java) {
            sanitizer.sanitize(F.decode(F.snapshot().with("tab", JsonPrimitive("unknown"))), "unknown", GraphPeriod.ONE_DAY)
        }
    }

    @Test
    fun rateShapeAndAsset_areBoundToTabIndependently() {
        // Kills shape-only and asset-only guards; both legitimate wire shapes are positive controls.
        listOf("usd", "jpy", "eur", "tether").forEach { tab ->
            val wire = F.snapshot(tab)
            assertEquals(1, sanitize(wire, tab).testRates.size)
            val rate = wire.getValue("rate") as JsonObject
            val wrongAsset = rate.with(if (tab == "tether") "primary_asset" else "asset", JsonPrimitive("bad-asset"))
            assertThrows(FreeSnapshotValidationException::class.java) { sanitize(wire.with("rate", wrongAsset), tab) }
            // Give the opposite shape the CORRECT asset so an asset check cannot hide a missing shape guard.
            val other = F.snapshot(if (tab == "tether") "usd" else "tether").getValue("rate") as JsonObject
            val shape = other.with(if (tab == "tether") "asset" else "primary_asset", JsonPrimitive(if (tab == "tether") "usdt-krw" else "$tab-krw"))
            assertThrows(FreeSnapshotValidationException::class.java) { sanitize(wire.with("rate", shape), tab) }
        }
    }

    @Test
    fun groupedRateAllowlist_checksGroupSourceAndAsset_andFirstWins() {
        // Kills pair-only filtering, swapped groups/assets, futures traversal, and last-wins dedup.
        val wire = F.snapshot("tether")
        val rate = (wire.getValue("rate") as JsonObject)
            .with("usdt_krw", JsonArray(listOf(
                F.rate("upbit", "usdt-krw", 1400.0, false), F.rate("upbit", "usdt-krw", 9000.0, false),
                F.rate("bithumb", "usdt-krw", flat = false), F.rate("coinone", "usdt-krw", flat = false),
                F.rate("korbit", "usdt-krw", flat = false), F.rate("gopax", "usdt-krw", flat = false),
                F.rate("kb", "usd-krw", flat = false), F.rate("upbit", "usd-krw", flat = false),
                F.rate("unknown", "usdt-krw", flat = false))))
            .with("usd_krw_banks", JsonArray(listOf(
                F.rate("kb", flat = false), F.rate("hana", flat = false), F.rate("citi", flat = false),
                F.rate("investing", flat = false), F.rate("upbit", "usdt-krw", flat = false), F.rate("hana", "usdt-krw", flat = false))))
            .with("usd_krw_reference", F.rate("investing", flat = false))
        val result = sanitize(wire.with("rate", rate), "tether").testRates
        assertEquals(listOf("upbit", "bithumb", "coinone", "korbit", "gopax", "kb", "hana", "investing"), result.map { it.source })
        assertEquals(1400.0, result.first().rate, 0.0)
        val groups = sanitize(wire.with("rate", rate), "tether").rate as FreeRate.Grouped
        assertEquals(listOf("upbit", "bithumb", "coinone", "korbit", "gopax"), groups.usdtKrw.map { it.source })
        assertEquals(listOf("kb", "hana"), groups.usdKrwBanks.map { it.bank })
        assertEquals("investing", groups.usdKrwReference!!.bank)
        listOf(F.rate("kb", flat = false), F.rate("investing", "usdt-krw", flat = false)).forEach { badRef ->
            assertEquals(7, sanitize(wire.with("rate", rate.with("usd_krw_reference", badRef)), "tether").testRates.size)
        }
        // Even an allowed pair in an extra container must be rejected before domain projection.
        val futuresOnly = (wire.getValue("rate") as JsonObject).with("usdt_krw", JsonArray(emptyList()))
            .with("usd_krw_futures", F.rate("upbit", "usdt-krw", flat = false))
        assertThrows(kotlinx.serialization.SerializationException::class.java) { sanitize(wire.with("rate", futuresOnly), "tether") }
    }

    @Test
    fun flatRates_keepAllLegacySourcesIncludingCiti_butRejectCrossAssetAndUnknown() {
        // Kills using the smaller graph-source registry for rates, source-only checks and last-wins.
        val sources = listOf("investing", "kb", "hana", "shinhan", "woori", "ibk", "nh", "sc", "bs", "citi")
        listOf("usd", "jpy", "eur").forEach { tab ->
            val valid = sources.map { F.rate(it, "$tab-krw") }
            val entries = valid + listOf(F.rate("investing", "$tab-krw", 9999.0), F.rate("new", "$tab-krw"), F.rate("kb", "usdt-krw"))
            val result = sanitize(withRates(F.snapshot(tab), entries), tab).testRates
            assertEquals(sources, result.map { it.source })
            assertEquals(List(10) { 1400.0 }, result.map { it.rate })
        }
    }

    @Test
    fun graphAllowlist_isPerTab_andDxyFuturesIsAllowedOnlyForTether() {
        // Kills global union/prefix filtering and blanket futures rejection. Assert complete exact sets.
        val banks = listOf("investing", "kb", "hana", "shinhan", "woori", "ibk", "nh", "sc", "bs")
        val expected = mapOf(
            "usd" to banks.map { "$it.usd" } + "dxy",
            "jpy" to banks.map { "$it.jpy" }, "eur" to banks.map { "$it.eur" },
            "tether" to listOf("upbit.usdt-krw", "bithumb.usdt-krw", "coinone.usdt-krw", "korbit.usdt-krw", "gopax.usdt-krw", "investing.usd", "kb.usd", "hana.usd", "dxy", "dxy_futures")
        )
        val all = expected.values.flatten().distinct() + listOf("krx.usd-krw-futures", "citi.usd", "unregistered")
        expected.forEach { (tab, ids) ->
            val result = sanitize(withSeries(F.snapshot(tab), all.map { F.series(it) }), tab)
            assertEquals(ids.toSet(), result.graph.series.map { it.seriesId }.toSet())
        }
    }

    @Test
    fun contaminatedPoint_removesWholeSeriesWithCleanSiblingAndPointControls() {
        // Kills source-only/provenance-only guards and filtering just the bad point from a series.
        val clean = F.point("hana")
        val mutations = listOf(
            clean.with("source", JsonPrimitive("krx")), clean.with("close_basis", JsonPrimitive("krx_settlement")),
            clean.with("source_method", JsonPrimitive("krx_daily")), clean.with("contract_code", JsonPrimitive("")),
            clean.with("source", JsonPrimitive("KRX")), clean.with("rate", JsonPrimitive(-1.0)),
            clean.with("rate", JsonPrimitive("1400")), clean.with("ts", JsonPrimitive("banana")),
            clean.with("ts", JsonPrimitive("2026-09-06T10:30:01+09:00")), clean.with("high", JsonPrimitive(1390.0))
        )
        val base = F.snapshot()
        assertEquals(listOf("investing.usd", "hana.usd"), sanitize(withSeries(base, listOf(F.series(), F.series("hana.usd", listOf(clean, clean))))).graph.series.map { it.seriesId })
        mutations.forEach { bad ->
            val result = sanitize(withSeries(base, listOf(F.series(), F.series("hana.usd", listOf(clean, bad)))))
            assertEquals("mutation=$bad", listOf("investing.usd"), result.graph.series.map { it.seriesId })
        }
    }

    @Test
    fun seriesProvenanceAndCarryIn_areCheckedBeforeProjection() {
        // Kills ignoring series-level metadata (including arrays/nested fields) or unsafe carry-in.
        val clean = F.series("hana.usd")
        val provenance = clean.getValue("provenance") as JsonObject
        val mutations = listOf(
            clean.with("provenance", provenance.with("default_close_basis", JsonPrimitive("krx_close"))),
            clean.with("provenance", provenance.with("source_method_values", JsonArray(listOf(JsonPrimitive("krx_daily"))))),
            clean.with("provenance", provenance.with("per_point_metadata", JsonArray(listOf(JsonPrimitive("contract_code"))))),
            clean.with("provenance", provenance.with("nested", F.obj("""{"source":"krx"}"""))),
            clean.with("carry_in", (clean.getValue("carry_in") as JsonObject).with("contract_code", JsonPrimitive("202609"))),
            clean.with("carry_in", (clean.getValue("carry_in") as JsonObject).with("rate", JsonPrimitive(0.0)))
        )
        val good = sanitize(withSeries(F.snapshot(), listOf(F.series(), clean)))
        assertEquals(2, good.graph.series.size)
        assertEquals(1398.0, good.graph.series.last().carryIn!!.rate, 0.0)
        assertEquals(listOf("close_basis", "source_method"), good.graph.series.last().perPointMetadata)
        mutations.forEach { bad ->
            assertEquals(listOf("investing.usd"), sanitize(withSeries(F.snapshot(), listOf(F.series(), bad))).graph.series.map { it.seriesId })
        }
    }

    @Test
    fun malformedRatesAndDuplicateSeries_cannotPoisonOrReplaceValidValues() {
        // Kills nonfinite/nonpositive/cutoff guard removal and silent last-wins series replacement.
        val clean = F.rate("hana")
        val bad = listOf(
            clean.with("rate", JsonPrimitive(0.0)), clean.with("rate", JsonPrimitive(-1.0)),
            clean.with("rate", JsonPrimitive(1e9)), clean.with("rate", JsonPrimitive("NaN")),
            clean.with("timestamp", JsonPrimitive("2026-09-06T10:30:01+09:00")),
            JsonPrimitive("broken")
        )
        assertEquals(2, sanitize(withRates(F.snapshot(), listOf(F.rate(), clean))).testRates.size)
        bad.forEach { entry -> assertEquals(listOf("investing"), sanitize(withRates(F.snapshot(), listOf(F.rate(), entry))).testRates.map { it.source }) }
        val duplicate = F.series().with("label", JsonPrimitive("replacement"))
        val result = sanitize(withSeries(F.snapshot(), listOf(F.series(), duplicate)))
        assertEquals(listOf("Reference"), result.graph.series.map { it.label })
        assertEquals(Instant.parse(F.BASIS), result.testRates.single().timestamp)
    }

    @Test
    fun emptyAfterSanitization_throwsInsteadOfReplacingLastGood() {
        // Kills accepting all-stripped rate or graph blocks; each rejection has a one-item positive control.
        assertEquals(1, sanitize(F.snapshot()).testRates.size)
        assertEquals(1, sanitize(F.snapshot()).graph.series.size)
        assertThrows(FreeSnapshotValidationException::class.java) { sanitize(withRates(F.snapshot(), listOf(F.rate("krx")))) }
        assertThrows(FreeSnapshotValidationException::class.java) { sanitize(withSeries(F.snapshot(), listOf(F.series("krx.usd-krw-futures")))) }
        assertThrows(FreeSnapshotValidationException::class.java) { sanitize(withSeries(F.snapshot(), listOf(F.series(points = emptyList())))) }
    }

    @Test
    fun krxRateScan_visitsEveryContainerBeforeShapeDispatch() {
        // Kills kind-dependent scanning, legacy-only/topic-only checks and projection before inspection.
        // Construct DTOs directly so strict wire decoding cannot conceal a broken sanitizer guard.
        listOf("usd", "tether").forEach { tab ->
            val good = F.decode(F.snapshot(tab))
            assertEquals(1, sanitizer.sanitize(good, tab, GraphPeriod.ONE_DAY).testRates.size)
            val markers = listOf(
                "bank" to "krx", "currency" to "usd-krw-futures",
                "source" to "krx", "asset" to "usd-krw-futures",
                "source" to " KRX ", "close_basis" to "krx_settlement",
                "source_method" to "krx_daily", "contract_code" to ""
            )
            listOf("entries", "usdt_krw", "usd_krw_banks", "usd_krw_reference").forEach { key ->
                markers.forEach { (field, value) ->
                    val entry = F.rate().with(field, JsonPrimitive(value))
                    val container = if (key == "usd_krw_reference") entry else JsonArray(listOf(entry))
                    val bad = good.copy(rate = good.rate.with(key, container))
                    val error = assertThrows(FreeSnapshotValidationException::class.java) {
                        sanitizer.sanitize(bad, tab, GraphPeriod.ONE_DAY)
                    }
                    // Shape rejection alone is insufficient evidence that the independent KRX scan ran.
                    assertEquals("KRX content in free snapshot rate", error.message)
                }
            }
        }
    }

    @Test
    fun directDtoHybridsWithoutKrx_areStillRejectedInBothDirections() {
        // Kills relying exclusively on wire decoding for strict shape admission.
        listOf("usd", "tether").forEach { tab ->
            val good = F.decode(F.snapshot(tab))
            assertEquals(1, sanitizer.sanitize(good, tab, GraphPeriod.ONE_DAY).testRates.size)
            val key = if (tab == "usd") "usdt_krw" else "entries"
            val bad = good.copy(rate = good.rate.with(key, JsonArray(emptyList())))
            val error = assertThrows(FreeSnapshotValidationException::class.java) {
                sanitizer.sanitize(bad, tab, GraphPeriod.ONE_DAY)
            }
            assertEquals("Invalid free snapshot rate shape", error.message)
        }
    }

    @Test
    fun historicalAllowlist_excludesIntradayOnlySourcesForEveryLongPeriod() {
        // Kills period-independent catalog unions; exact accepted sets prevent deny-all false greens.
        val expected = mapOf(
            "usd" to listOf("investing.usd", "hana.usd", "dxy"),
            "jpy" to listOf("investing.jpy", "hana.jpy"),
            "eur" to listOf("investing.eur", "hana.eur"),
            "tether" to listOf("bithumb.usdt-krw", "investing.usd", "hana.usd", "dxy")
        )
        expected.forEach { (tab, ids) ->
            GraphPeriod.entries.filter { it != GraphPeriod.ONE_DAY }.forEach { period ->
                val candidates = (ids + listOf("kb.usd", "kb.jpy", "kb.eur", "upbit.usdt-krw", "dxy_futures")).distinct()
                val dto = F.decode(withSeries(F.snapshot(tab, period.code), candidates.map { F.series(it) }))
                assertEquals(ids, sanitizer.sanitize(dto, tab, period).graph.series.map { it.seriesId })
            }
        }
        val intraday = sanitize(withSeries(F.snapshot("tether"), listOf(F.series("dxy_futures"))), "tether")
        assertEquals("dxy_futures", intraday.graph.series.single().seriesId)
    }

    @Test
    fun numericAndTimeBoundaries_preserveExactValuesAndRejectMalformedContent() {
        // Kills >= vs > cutoff mistakes, string-number coercion, nonfinite acceptance and off-grid basis.
        val good = F.decode()
        val result = sanitizer.sanitize(good, "usd", GraphPeriod.ONE_DAY)
        assertEquals(good.asOf, result.graph.series.single().points.single().timestamp)
        assertEquals(good.asOf, result.testRates.single().timestamp)
        assertEquals(good.generatedAt, result.generatedAt)
        assertEquals(good.graph.domainStartAt, result.graph.domainStartAt)
        assertEquals(good.graph.domainEndAt, result.graph.domainEndAt)
        assertEquals(good.graph.range.start, result.graph.rangeStart)
        listOf("2026-09-06T01:00:00Z", "2026-09-06T01:30:00.001Z").forEach { basis ->
            assertThrows(FreeSnapshotValidationException::class.java) {
                sanitizer.sanitize(good.copy(asOf = Instant.parse(basis)), "usd", GraphPeriod.ONE_DAY)
            }
        }
        listOf(0.001, 999999999.0).forEach { price ->
            assertEquals(price, sanitize(withRates(F.snapshot(), listOf(F.rate(value = price)))).testRates.single().rate, 0.0)
        }
        listOf("0", "-1", "1e9", "1e309", "\"1400\"", "null").forEach { price ->
            val badRate = F.rate("hana").with("rate", F.json.parseToJsonElement(price))
            assertEquals(listOf("investing"), sanitize(withRates(F.snapshot(), listOf(F.rate(), badRate))).testRates.map { it.source })
        }
    }

    @Test
    fun groupedPartialSources_andOptionalPointFields_remainUsable() {
        // Kills requiring every grouped source, every OHLC value, or carry-in for a valid series.
        val base = F.snapshot("tether")
        val rates = (base.getValue("rate") as JsonObject).with("usdt_krw", JsonArray(emptyList()))
        listOf(
            rates.with("usd_krw_banks", JsonArray(listOf(F.rate("kb", flat = false)))),
            rates.with("usd_krw_reference", F.rate("investing", flat = false))
        ).forEach { rate -> assertEquals(1, sanitize(base.with("rate", rate), "tether").testRates.size) }
        assertThrows(FreeSnapshotValidationException::class.java) { sanitize(base.with("rate", rates), "tether") }
        val point = JsonObject(F.point() - setOf("high", "low", "close_basis", "source_method"))
        val series = JsonObject(F.series(points = listOf(point)) - "carry_in")
        val result = sanitize(withSeries(F.snapshot(), listOf(series))).graph.series.single()
        assertEquals(1400.0, result.points.single().rate, 0.0)
        assertEquals(null, result.points.single().high)
        assertEquals(null, result.carryIn)
        val malformed = series.with("data", JsonArray(listOf(point.with("low", JsonPrimitive(1401.0)))))
        assertThrows(FreeSnapshotValidationException::class.java) { sanitize(withSeries(F.snapshot(), listOf(malformed))) }
    }

    private fun sanitize(wire: JsonObject, tab: String = "usd") = sanitizer.sanitize(F.decode(wire), tab, GraphPeriod.ONE_DAY)
    private fun withRates(wire: JsonObject, entries: List<JsonElement>): JsonObject = wire.with("rate", (wire.getValue("rate") as JsonObject).with("entries", JsonArray(entries)))
    private fun withSeries(wire: JsonObject, series: List<JsonElement>): JsonObject = wire.with("graph", (wire.getValue("graph") as JsonObject).with("series", JsonArray(series)))

    /**
     * Every scalar in this class is bounded — `decimals in 0..8`, rates against a plausible range —
     * and the three instants were not. Grid alignment holds for *any* `:30:00`, so a far-future
     * `as_of` was admitted, and the scheduler converts it to a local date to find its basis slot:
     * that throws, inside a single-consumer loop, taking the free tier down for the process.
     */
    @Test
    fun timestampsAreBounded_soAnAdmittedBasisCanAlwaysBeConvertedToItsSlot() {
        val wire = F.snapshot("usd", "3m")
        val period = GraphPeriod.THREE_MONTHS

        // A self-consistent far-future envelope: grid-aligned, generated after its own basis, hint
        // after it, and every rate timestamp still at or before it. Every *other* rule in this class
        // passes, so this case isolates the range bound — and it is exactly the payload that reaches
        // `basisOf` and throws inside the loop.
        val farFuture = wire
            .with("as_of", JsonPrimitive("+999999-12-31T23:30:00Z"))
            .with("generated_at", JsonPrimitive("+999999-12-31T23:31:00Z"))
            .with("refresh_not_before", JsonPrimitive("+999999-12-31T23:59:00Z"))
        val futureRejection = assertThrows(FreeSnapshotValidationException::class.java) {
            sanitizer.sanitize(F.decode(farFuture), "usd", period)
        }
        assertEquals(
            "Free snapshot timestamps are outside the supported window",
            futureRejection.message
        )
        val farPast = wire
            .with("as_of", JsonPrimitive("1899-01-01T00:30:00Z"))
            .with("generated_at", JsonPrimitive("1899-01-01T00:31:00Z"))
            .with("refresh_not_before", JsonPrimitive("1899-01-01T01:31:00Z"))
        assertThrows(FreeSnapshotValidationException::class.java) {
            sanitizer.sanitize(F.decode(farPast), "usd", period)
        }
        // A view cannot have been built before the basis it was built for.
        val builtBeforeBasis = wire.with("generated_at", JsonPrimitive("2026-09-06T09:00:00+09:00"))
        assertThrows(FreeSnapshotValidationException::class.java) {
            sanitizer.sanitize(F.decode(builtBeforeBasis), "usd", period)
        }
        // A refresh hint that is not after the basis would make the loop's own clamp the schedule.
        val hintAtBasis = wire.with("refresh_not_before", JsonPrimitive("2026-09-06T10:30:00+09:00"))
        assertThrows(FreeSnapshotValidationException::class.java) {
            sanitizer.sanitize(F.decode(hintAtBasis), "usd", period)
        }
        // A sane basis with an absurd hint: `nextEligibleAt` would become that hint, so the tab
        // would simply never refresh again — the same "off the air" hazard as an unbounded
        // `Retry-After`, arriving by a different field.
        val absurdHint = wire.with("refresh_not_before", JsonPrimitive("+999999-12-31T23:59:00Z"))
        assertEquals(
            "Free snapshot refresh hint is outside the supported window",
            assertThrows(FreeSnapshotValidationException::class.java) {
                sanitizer.sanitize(F.decode(absurdHint), "usd", period)
            }.message
        )

        // Positive control: a stale canonical's hint legitimately sits hours past its basis.
        val staleServe = wire.with("refresh_not_before", JsonPrimitive("2026-09-06T16:31:00+09:00"))
        sanitizer.sanitize(F.decode(staleServe), "usd", period)
    }
}

/** Flatten only for common assertions; grouped-shape assertions above verify the real projection. */
internal val FreeSnapshot.testRates: List<SourceRate>
    get() = when (val value = rate) {
        is FreeRate.Flat -> value.entries.map { SourceRate(it.bank, it.currency, it.rate, it.timestamp) }
        is FreeRate.Grouped -> value.usdtKrw +
            (value.usdKrwBanks + listOfNotNull(value.usdKrwReference))
                .map { SourceRate(it.bank, it.currency, it.rate, it.timestamp) }
    }
