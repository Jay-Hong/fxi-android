package com.jay.fxi.ui.rates

import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.ScalePolicyCalculator
import com.jay.fxi.domain.model.SourceRate
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rate row contract, run over every domain that reaches it.
 *
 * S1.5's first DoD item asks that free and premium fixtures pass the *same* presenter test. Two
 * lists of the same type carrying the same numbers would pass that sentence and prove only that
 * the presenter is a function, so the axis here is the one that actually differs: `ExchangeRate`
 * (banks, both tabs) against `SourceRate` (exchanges, tether only), reaching the presenter through
 * their own projections rather than through hand-built quotes.
 */
class RateRowPresenterTest {

    private val at = Instant.parse("2026-09-08T04:30:00Z")

    private fun bank(code: String, value: Double, currency: String = "usd-krw") =
        ExchangeRate(currency = currency, bank = code, rate = value, timestamp = at)

    private fun exchange(code: String, value: Double) =
        SourceRate(source = code, asset = "usdt-krw", rate = value, timestamp = at)

    /** The tether snapshot the server actually sends: three headings, two assets. */
    private val tether = FreeRate.Grouped(
        primaryAsset = "usdt-krw",
        usdtKrw = listOf(
            exchange("upbit", 1402.0), exchange("bithumb", 1401.5), exchange("coinone", 1403.0),
            exchange("korbit", 1400.5), exchange("gopax", 1404.0)
        ),
        usdKrwBanks = listOf(bank("kb", 1399.0), bank("hana", 1398.4)),
        usdKrwReference = bank("investing", 1398.8)
    )

    private val freeFx = FreeRate.Flat(
        asset = "usd-krw",
        entries = listOf(
            bank("investing", 1398.8), bank("kb", 1399.0), bank("hana", 1398.4),
            bank("shinhan", 1399.2), bank("woori", 1398.9), bank("citi", 1401.6)
        )
    )

    /** The premium rate list is the same shape the free FX tab uses — one bank list for one asset. */
    private val premium = listOf(
        bank("investing", 1398.8), bank("kb", 1399.0), bank("hana", 1398.4),
        bank("shinhan", 1399.2), bank("woori", 1398.9), bank("ibk", 1399.4),
        bank("nh", 1399.1), bank("sc", 1398.6), bank("bs", 1401.9), bank("citi", 1401.6)
    ).asRateScale("은행별 환율", "usd-krw")

    private data class Case(val name: String, val scale: RateScale, val quotes: Int)

    private fun cases(): List<Case> {
        val tetherScales = tether.asRateScales()
        return listOf(
            Case("무료 FX(달러)", freeFx.asRateScales().single(), 6),
            Case("무료 테더 — 거래소", tetherScales[0], 5),
            Case("무료 테더 — USD/KRW 두 섹션", tetherScales[1], 3),
            Case("유료 은행 목록", premium, 10)
        )
    }

    // ---- the battery every case runs ----

    /** Nothing the server sent disappears between the wire and the row. */
    @Test
    fun noQuoteIsLost() {
        cases().forEach { case ->
            val views = RateRowPresenter.present(case.scale)
            assertEquals(case.name, case.quotes, views.sumOf { it.rows.size })
            assertEquals(
                "${case.name}: 서버 순서가 바뀌었다",
                case.scale.groups.map { g -> g.quotes.map { it.id } },
                views.map { v -> v.rows.map { it.id } }
            )
        }
    }

    /**
     * The reference is marked exactly once, in the group that contains it.
     *
     * A group measuring against a quote from another heading marks none of its own rows — the blue
     * border belongs to the row that *is* the reference, and that row is drawn under the other
     * heading. Asserting "one per group" instead is what this test said first, and the tether tab
     * failed it for the right reason.
     */
    @Test
    fun theReferenceIsMarkedOnceInTheGroupThatHoldsIt() {
        cases().forEach { case ->
            val views = RateRowPresenter.present(case.scale)
            case.scale.groups.zip(views).forEach { (group, view) ->
                val marked = view.rows.count { it.isReference }
                val expected = when {
                    view.rows.isEmpty() -> 0
                    group.reference is RateReference.External -> 0
                    else -> 1
                }
                assertEquals("${case.name} / ${view.title}", expected, marked)
            }
            // …and across the whole ruler, every row measured against something can find it.
            val ids = views.flatMap { it.rows }.map { it.id }
            case.scale.groups.forEach { group ->
                (group.reference as? RateReference.External)?.let {
                    assertTrue("${case.name}: 기준 행이 이 자 안에 없다", it.quote.id in ids)
                }
            }
        }
    }

    /** The reference row has no difference to itself, and everyone else does. */
    @Test
    fun theReferenceHasNoDifferenceAndTheOthersDo() {
        cases().forEach { case ->
            RateRowPresenter.present(case.scale).forEach { view ->
                view.rows.forEach { row ->
                    if (row.isReference) {
                        assertNull("${case.name} / ${row.id}", row.difference)
                    } else {
                        assertNotNull("${case.name} / ${row.id}", row.difference)
                    }
                }
            }
        }
    }

    /**
     * The difference is what the two printed numbers imply.
     *
     * Subtracting the raw values and rounding afterwards puts a third number on screen that neither
     * of the other two accounts for — 1399.004 beside 1398.4 prints 1399.00 and 1398.40 but would
     * report +0.604.
     */
    @Test
    fun theDifferenceIsBetweenThePrintedNumbers() {
        cases().forEach { case ->
            val views = RateRowPresenter.present(case.scale)
            val byId = views.flatMap { it.rows }.associateBy { it.id }
            case.scale.groups.forEach { group ->
                val reference = when (val r = group.reference) {
                    is RateReference.External -> r.quote
                    RateReference.FirstRow -> group.quotes.firstOrNull()
                } ?: return@forEach
                group.quotes.filter { it.id != reference.id }.forEach { quote ->
                    val expected = RateDisplay.quantized(quote.value) - RateDisplay.quantized(reference.value)
                    assertEquals("${case.name} / ${quote.id}", expected, byId.getValue(quote.id).difference!!, 1e-9)
                }
            }
        }
    }

    /**
     * One scale is one domain, and it is the scale policy's — clipped, not raw.
     *
     * A raw domain lets one outlier squash everyone else into the minimum bar, which is the whole
     * reason `ScalePolicy` exists; a per-group domain would draw the same 1399원 at two lengths.
     */
    @Test
    fun oneScaleIsOneClippedDomain() {
        cases().forEach { case ->
            val views = RateRowPresenter.present(case.scale)
            val expected = ScalePolicyCalculator
                .computeValues(case.scale.groups.flatMap { g -> g.quotes.map { it.value } })
                .displayRange
            assertEquals("${case.name}: 스케일이 갈렸다", 1, views.map { it.domain }.distinct().size)
            assertEquals("${case.name}", expected?.first, views.first().domain?.start)
            assertEquals("${case.name}", expected?.second, views.first().domain?.endInclusive)
        }
    }

    /** Every drawn value is inside the domain's reach, so no bar is placed by clamping alone. */
    @Test
    fun everyValueIsPlaceableInItsDomain() {
        cases().forEach { case ->
            RateRowPresenter.present(case.scale).forEach { view ->
                val domain = view.domain ?: return@forEach
                view.rows.forEach { row ->
                    val width = RateBarWidth.of(
                        row.value, domain.start, domain.endInclusive,
                        available = 300f, minBar = 60f, diffMin = 55f
                    )
                    assertTrue("${case.name} / ${row.id}: 폭 $width", width.isFinite() && width >= 60f)
                }
            }
        }
    }

    // ---- what is specific to one shape ----

    /**
     * The tether tab's two USD/KRW headings share a ruler, and the exchanges do not join them.
     *
     * Reviewed into existence: with a domain per heading, "기준 USD/KRW" holds one row, so its span
     * is zero and its bar sits at the middle of the track while the identical figure under "은행
     * USD/KRW" sits somewhere else entirely. And folding USDT/KRW in would be comparing two assets.
     */
    @Test
    fun theTethersTwoUsdKrwHeadingsShareOneRulerAndTheExchangesDoNot() {
        val scales = tether.asRateScales()
        assertEquals(2, scales.size)

        val exchangeViews = RateRowPresenter.present(scales[0])
        val usdViews = RateRowPresenter.present(scales[1])
        assertEquals(listOf("거래소 USDT/KRW"), exchangeViews.map { it.title })
        assertEquals(listOf("은행 USD/KRW", "기준 USD/KRW"), usdViews.map { it.title })
        assertEquals(usdViews[0].domain, usdViews[1].domain)
        assertTrue(
            "거래소와 은행이 한 자를 썼다",
            exchangeViews.single().domain != usdViews.first().domain
        )
        assertEquals(setOf("usdt-krw"), exchangeViews.map { it.asset }.toSet())
        assertEquals(setOf("usd-krw"), usdViews.map { it.asset }.toSet())
    }

    /**
     * The banks are measured against the reference section, not against each other.
     *
     * Otherwise the heading that says "기준" measures nothing, and the first bank silently becomes
     * the baseline for a screen that names a different one.
     */
    @Test
    fun theBanksAreMeasuredAgainstTheReferenceSection() {
        val usd = RateRowPresenter.present(tether.asRateScales()[1])
        val banks = usd.first { it.title == "은행 USD/KRW" }
        assertTrue("은행 섹션이 자기 첫 행을 기준으로 삼았다", banks.rows.none { it.isReference })
        assertEquals(1399.0 - 1398.8, banks.rows.first { it.id == "kb" }.difference!!, 1e-9)
        assertEquals(1398.4 - 1398.8, banks.rows.first { it.id == "hana" }.difference!!, 1e-9)

        val reference = usd.first { it.title == "기준 USD/KRW" }
        assertEquals(listOf("investing"), reference.rows.map { it.id })
        assertTrue(reference.rows.single().isReference)
    }

    /** No reference quote is normal, and then the banks measure against their own first row. */
    @Test
    fun anAbsentReferenceFallsBackToTheGroupsFirstRow() {
        val scales = tether.copy(usdKrwReference = null).asRateScales()
        val usd = RateRowPresenter.present(scales[1])
        assertEquals("빈 기준 섹션이 남았다", listOf("은행 USD/KRW", "기준 USD/KRW"), usd.map { it.title })
        assertTrue(usd.first { it.title == "기준 USD/KRW" }.rows.isEmpty())
        val banks = usd.first { it.title == "은행 USD/KRW" }
        assertEquals("kb", banks.rows.first { it.isReference }.id)
        assertEquals(1398.4 - 1399.0, banks.rows.first { it.id == "hana" }.difference!!, 1e-9)
    }

    /**
     * A code with no display name keeps the code, and keeps its row.
     *
     * The sanitizer decides what may be shown; by the time a quote is here, dropping it would take
     * a real number off the screen with nothing left to notice the loss by. Citi is the live case —
     * `FreeSnapshotSanitizer` admits it deliberately, and a filter that hides it belongs to the
     * preference layer, not to a projection.
     */
    @Test
    fun anUnknownCodeKeepsItsRowAndItsName() {
        val withStranger = FreeRate.Flat("usd-krw", listOf(bank("kb", 1399.0), bank("nonghyup2", 1400.0)))
        val rows = RateRowPresenter.present(withStranger.asRateScales().single()).single().rows
        assertEquals(listOf("kb", "nonghyup2"), rows.map { it.id })
        assertEquals("nonghyup2", rows.last().label)
        // …and citi, which the sanitizer admits on purpose, survives the FX projection.
        val fx = RateRowPresenter.present(freeFx.asRateScales().single()).single()
        assertTrue("씨티가 사라졌다", fx.rows.any { it.id == "citi" })
    }

    /**
     * Where rounding and identity actually bite. Both cases were mutation survivors first.
     *
     * 1399.004 and 1398.996 both print `1399.00`, so the column between them must say `+0.00` —
     * rounding the difference instead of differencing the rounded values reports `-0.01` for two
     * rows the screen says are equal. And two rows really can carry the identical Double, so the
     * reference border has to follow the source code rather than the number: marking by value
     * would put the border on both.
     */
    @Test
    fun roundingAndIdentityAreDecidedOnThePrintedNumberAndTheCode() {
        val twins = FreeRate.Flat(
            "usd-krw",
            listOf(bank("kb", 1399.004), bank("hana", 1398.996), bank("shinhan", 1399.004))
        )
        val rows = RateRowPresenter.present(twins.asRateScales().single()).single().rows
        assertEquals(listOf("1399.00", "1399.00", "1399.00"), rows.map { RateDisplay.format(it.value) })

        assertEquals("같은 숫자를 인쇄한 두 행의 차이가 0 이 아니다", 0.0, rows[1].difference!!, 1e-9)
        assertEquals(0.0, rows[2].difference!!, 1e-9)
        assertEquals("기준 표식이 값을 따라갔다", listOf(true, false, false), rows.map { it.isReference })
    }

    /** Empty in, empty out — a group with no quotes is not a crash and not an invented row. */
    @Test
    fun anEmptyGroupProducesNoRowsAndNoDomain() {
        val empty = RateScale(listOf(RateQuoteGroup("빈 섹션", "usd-krw", emptyList())))
        val view = RateRowPresenter.present(empty).single()
        assertTrue(view.rows.isEmpty())
        assertNull(view.domain)
    }

    /**
     * A projection checks the asset it declares. Found by review, not by a fixture.
     *
     * Past the projection a quote has only a number, so a JPY row folded into a USD group would be
     * drawn on the won scale and reported about 450원 below it with nothing left to catch it. The
     * free sanitizer already refuses the mismatch; this keeps it true for callers that do not come
     * through the sanitizer, which is every premium caller.
     */
    @Test
    fun aProjectionRefusesQuotesThatAreNotTheAssetItDeclares() {
        val mixed = listOf(bank("kb", 1400.0), bank("hana", 950.0, currency = "jpy-krw"))
        val thrown = runCatching { mixed.asRateScale("은행별 환율", "usd-krw") }.exceptionOrNull()
        assertTrue("자산이 섞인 목록이 통과했다: $thrown", thrown is IllegalArgumentException)

        val mixedSources = FreeRate.Grouped(
            primaryAsset = "usdt-krw",
            usdtKrw = listOf(exchange("upbit", 1402.0), SourceRate("bithumb", "usd-krw", 1399.0, at)),
            usdKrwBanks = emptyList(),
            usdKrwReference = null
        )
        assertTrue(
            "거래소 목록의 자산 혼합이 통과했다",
            runCatching { mixedSources.asRateScales() }.exceptionOrNull() is IllegalArgumentException
        )
    }

    /**
     * A namesake in the referring group is not the reference.
     *
     * Two sections can carry the same source code — the tether tab's banks and its reference
     * section both quote USD/KRW. Marking by code alone put the border on the namesake *and* on the
     * real reference, and robbed the namesake of the difference it should show. Found by review.
     */
    @Test
    fun aRowSharingTheReferencesCodeIsStillMeasuredAgainstIt() {
        val referenceQuote = RateQuote("kb", "국민은행", 1400.0, at)
        val scale = RateScale(
            listOf(
                RateQuoteGroup(
                    "은행 USD/KRW", "usd-krw",
                    listOf(RateQuote("kb", "국민은행", 1401.0, at)),
                    RateReference.External(referenceQuote)
                ),
                RateQuoteGroup("기준 USD/KRW", "usd-krw", listOf(referenceQuote))
            )
        )
        val views = RateRowPresenter.present(scale)
        val namesake = views[0].rows.single()
        assertTrue("동명이인에게 기준 표식이 붙었다", !namesake.isReference)
        assertEquals(1.0, namesake.difference!!, 1e-9)
        assertTrue(views[1].rows.single().isReference)
    }

    /** An external reference has to be one of this ruler's own rows, or the domain never saw it. */
    @Test
    fun anExternalReferenceMustBeDrawnOnTheSameScale() {
        val elsewhere = RateQuote("investing", "인베스팅", 1398.8, at)
        val orphaned = RateScale(
            listOf(
                RateQuoteGroup(
                    "은행 USD/KRW", "usd-krw",
                    listOf(RateQuote("kb", "국민은행", 1399.0, at)),
                    RateReference.External(elsewhere)
                )
            )
        )
        val thrown = runCatching { RateRowPresenter.present(orphaned) }.exceptionOrNull()
        assertTrue("이 자에 없는 기준이 통과했다: $thrown", thrown is IllegalArgumentException)
    }

    /** Two assets under one ruler is a programming error, not a rounding one. */
    @Test
    fun oneScaleRefusesTwoAssets() {
        val mixed = RateScale(
            listOf(
                RateQuoteGroup("거래소", "usdt-krw", listOf(RateQuote("upbit", "업비트", 1402.0, at))),
                RateQuoteGroup("은행", "usd-krw", listOf(RateQuote("kb", "국민은행", 1399.0, at)))
            )
        )
        val thrown = runCatching { RateRowPresenter.present(mixed) }.exceptionOrNull()
        assertTrue("자산 혼합이 통과했다: $thrown", thrown is IllegalArgumentException)
    }

    /**
     * The presenter stays off the platform. Checked by reading it, not by asserting it in prose.
     *
     * S1.5's DoD says the presenter must not depend on runtime or network. A comment saying so goes
     * stale the first time somebody reaches for a `Context`; the import list cannot.
     */
    @Test
    fun thePresenterImportsNothingFromThePlatform() {
        val root = File("src/main/java/com/jay/fxi/ui/rates")
        val sources = root.listFiles { f: File -> f.name.endsWith(".kt") }?.toList().orEmpty()
        assertTrue("presenter 소스를 못 찾았다: ${root.absolutePath}", sources.size >= 4)
        val banned = listOf("import android.", "import androidx.", "import io.ktor", "import retrofit")
        sources.forEach { file ->
            val offending = file.readLines().filter { line -> banned.any { line.trimStart().startsWith(it) } }
            assertEquals("${file.name} 이 플랫폼을 끌어들였다: $offending", emptyList<String>(), offending)
        }
    }
}
