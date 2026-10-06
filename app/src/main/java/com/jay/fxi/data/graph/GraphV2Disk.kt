package com.jay.fxi.data.graph

import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphCarryIn
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphV2InProgress
import com.jay.fxi.domain.model.GraphV2Tab
import com.jay.fxi.domain.model.RateSanity
import java.nio.charset.CharacterCodingException
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val GRAPH_V2_DISK_SCHEMA_VERSION = 1

internal enum class GraphV2DiskComponent { GENERAL, KRX }

internal data class GraphV2GeneralKey(
    val uid: String,
    val userAccessEpoch: String,
    val tab: String,
    val period: String
)

internal data class GraphV2KrxKey(
    val uid: String,
    val userAccessEpoch: String,
    val krxCapabilityEpoch: String,
    val tab: String,
    val period: String
)

internal data class GraphV2ServerMetadata(
    val bucketSize: String,
    val fetchedAt: Instant,
    val rangeStart: String?,
    val rangeEnd: String?,
    val domainStartAt: Instant?,
    val domainEndAt: Instant?,
    val liveDomainMode: String?
)

internal data class GraphV2IndexedSeries(val ordinal: Int, val series: FreeGraphSeries)

internal data class GraphV2StoredComponent(
    val metadata: GraphV2ServerMetadata,
    val series: List<GraphV2IndexedSeries>,
    val inProgress: Map<String, GraphV2InProgress>
)

internal data class GraphV2GeneralEnvelope(
    val schemaVersion: Int,
    val key: GraphV2GeneralKey,
    val responseId: String,
    val component: GraphV2StoredComponent
)

internal data class GraphV2KrxEnvelope(
    val schemaVersion: Int,
    val key: GraphV2KrxKey,
    val responseId: String,
    val component: GraphV2StoredComponent
)

internal data class GraphV2DiskComponents(
    val general: GraphV2GeneralEnvelope,
    val krx: GraphV2KrxEnvelope?
)

internal sealed interface GraphV2Validation<out T> {
    data class Valid<T>(val value: T) : GraphV2Validation<T>
    data class Invalid(val reason: String) : GraphV2Validation<Nothing>
}

/** Accepts an A1-validated server tab, before any app observations or display synthesis. */
internal fun splitGraphV2ServerTab(
    serverTab: GraphV2Tab,
    generalKey: GraphV2GeneralKey,
    krxCapabilityEpoch: String?,
    responseId: String,
    catalog: GraphCatalog?
): GraphV2Validation<GraphV2DiskComponents> {
    generalKeyError(generalKey)?.let { return GraphV2Validation.Invalid(it) }
    if (responseId.isEmpty()) return GraphV2Validation.Invalid("Empty response ID")
    if (krxCapabilityEpoch != null && krxCapabilityEpoch.isEmpty()) {
        return GraphV2Validation.Invalid("Empty KRX capability epoch")
    }
    if (serverTab.tab != generalKey.tab || serverTab.period.code != generalKey.period) {
        return GraphV2Validation.Invalid("Server tab or period does not match the key")
    }

    val graph = serverTab.graph
    val component = GraphV2StoredComponent(
        metadata = GraphV2ServerMetadata(
            serverTab.bucketSize, serverTab.fetchedAt, graph.rangeStart, graph.rangeEnd,
            graph.domainStartAt, graph.domainEndAt, graph.liveDomainMode
        ),
        series = graph.series.mapIndexed { ordinal, series -> GraphV2IndexedSeries(ordinal, series) },
        inProgress = serverTab.inProgress
    ).filterCatalog(generalKey.tab, generalKey.period, catalog)
    val (krxSeries, generalSeries) = component.series.partition { it.series.seriesId.startsWith("krx.") }
    val general = GraphV2GeneralEnvelope(
        GRAPH_V2_DISK_SCHEMA_VERSION, generalKey, responseId, component.selectSeries(generalSeries)
    )
    val krx = krxCapabilityEpoch?.let { epoch ->
        GraphV2KrxEnvelope(
            GRAPH_V2_DISK_SCHEMA_VERSION,
            GraphV2KrxKey(generalKey.uid, generalKey.userAccessEpoch, epoch, generalKey.tab, generalKey.period),
            responseId,
            component.selectSeries(krxSeries)
        )
    }
    generalEnvelopeError(general)?.let { return GraphV2Validation.Invalid(it) }
    krx?.let { envelope -> krxEnvelopeError(envelope)?.let { return GraphV2Validation.Invalid(it) } }
    return GraphV2Validation.Valid(GraphV2DiskComponents(general, krx))
}

internal interface GraphV2EnvelopeCodec {
    fun encodeGeneral(envelope: GraphV2GeneralEnvelope): GraphV2Validation<ByteArray>
    fun encodeKrx(envelope: GraphV2KrxEnvelope): GraphV2Validation<ByteArray>
    fun decodeGeneral(
        bytes: ByteArray,
        expectedKey: GraphV2GeneralKey,
        catalog: GraphCatalog?
    ): GraphV2Validation<GraphV2GeneralEnvelope>
    fun decodeKrx(
        bytes: ByteArray,
        expectedKey: GraphV2KrxKey,
        catalog: GraphCatalog?
    ): GraphV2Validation<GraphV2KrxEnvelope>
}

/** Storage DTOs have no defaults: a damaged envelope cannot turn into an ordinary empty answer. */
internal class JsonGraphV2EnvelopeCodec : GraphV2EnvelopeCodec {
    // Strict JSON with explicit nulls preserves the exact schema, including absent server metadata.
    private val json = Json { encodeDefaults = true }

    override fun encodeGeneral(envelope: GraphV2GeneralEnvelope): GraphV2Validation<ByteArray> {
        generalEnvelopeError(envelope)?.let { return GraphV2Validation.Invalid(it) }
        return codecResult { json.encodeToString(GeneralEnvelopeDto.from(envelope)).encodeToByteArray() }
    }

    override fun encodeKrx(envelope: GraphV2KrxEnvelope): GraphV2Validation<ByteArray> {
        krxEnvelopeError(envelope)?.let { return GraphV2Validation.Invalid(it) }
        return codecResult { json.encodeToString(KrxEnvelopeDto.from(envelope)).encodeToByteArray() }
    }

    override fun decodeGeneral(
        bytes: ByteArray,
        expectedKey: GraphV2GeneralKey,
        catalog: GraphCatalog?
    ): GraphV2Validation<GraphV2GeneralEnvelope> {
        val envelope = when (val decoded = codecResult {
            json.decodeFromString<GeneralEnvelopeDto>(bytes.decodeToString(throwOnInvalidSequence = true)).toDomain()
        }) {
            is GraphV2Validation.Valid -> decoded.value
            is GraphV2Validation.Invalid -> return decoded
        }
        if (envelope.key != expectedKey) return GraphV2Validation.Invalid("General key tuple does not match")
        generalEnvelopeError(envelope)?.let { return GraphV2Validation.Invalid(it) }
        return GraphV2Validation.Valid(envelope.copy(
            component = envelope.component.filterCatalog(expectedKey.tab, expectedKey.period, catalog)
        ))
    }

    override fun decodeKrx(
        bytes: ByteArray,
        expectedKey: GraphV2KrxKey,
        catalog: GraphCatalog?
    ): GraphV2Validation<GraphV2KrxEnvelope> {
        val envelope = when (val decoded = codecResult {
            json.decodeFromString<KrxEnvelopeDto>(bytes.decodeToString(throwOnInvalidSequence = true)).toDomain()
        }) {
            is GraphV2Validation.Valid -> decoded.value
            is GraphV2Validation.Invalid -> return decoded
        }
        if (envelope.key != expectedKey) return GraphV2Validation.Invalid("KRX key tuple does not match")
        krxEnvelopeError(envelope)?.let { return GraphV2Validation.Invalid(it) }
        return GraphV2Validation.Valid(envelope.copy(
            component = envelope.component.filterCatalog(expectedKey.tab, expectedKey.period, catalog)
        ))
    }
}

internal data class GraphV2ComponentJoin(
    val tab: GraphV2Tab,
    val krxJoined: Boolean,
    val ignoredKrxReason: String?
)

/** Structural join of validated components. Current capability admission remains the caller's job. */
internal fun joinGraphV2Components(
    general: GraphV2GeneralEnvelope,
    krx: GraphV2KrxEnvelope?
): GraphV2ComponentJoin {
    val generalError = generalEnvelopeError(general)
    require(generalError == null) { generalError ?: "Invalid general envelope" }
    if (krx == null) return GraphV2ComponentJoin(general.toTab(general.component), false, null)

    val generalOrdinals = general.component.series.map { it.ordinal }.toSet()
    val reason = krxEnvelopeError(krx) ?: when {
        krx.key.generalKey() != general.key -> "Common key tuple does not match"
        krx.responseId != general.responseId -> "Response ID does not match"
        krx.component.metadata != general.component.metadata -> "Server metadata does not match"
        krx.component.series.any { it.ordinal in generalOrdinals } -> "Component ordinals collide"
        else -> null
    }
    if (reason != null) return GraphV2ComponentJoin(general.toTab(general.component), false, reason)
    val joined = general.component.copy(
        series = general.component.series + krx.component.series,
        inProgress = general.component.inProgress + krx.component.inProgress
    )
    return GraphV2ComponentJoin(general.toTab(joined), true, null)
}

private fun GraphV2GeneralEnvelope.toTab(component: GraphV2StoredComponent): GraphV2Tab {
    val metadata = component.metadata
    return GraphV2Tab(
        key.tab, requireNotNull(GraphPeriod.fromCode(key.period)), metadata.bucketSize, metadata.fetchedAt,
        FreeGraph(
            metadata.bucketSize, component.series.sortedBy { it.ordinal }.map { it.series },
            metadata.rangeStart, metadata.rangeEnd, metadata.domainStartAt, metadata.domainEndAt,
            metadata.liveDomainMode
        ),
        component.inProgress
    )
}

private fun GraphV2KrxKey.generalKey() = GraphV2GeneralKey(uid, userAccessEpoch, tab, period)

private fun generalKeyError(key: GraphV2GeneralKey): String? = when {
    listOf(key.uid, key.userAccessEpoch, key.tab, key.period).any { it.isEmpty() } -> "Incomplete general key"
    GraphPeriod.fromCode(key.period) == null -> "Unknown graph period"
    else -> null
}

private fun generalEnvelopeError(envelope: GraphV2GeneralEnvelope): String? =
    generalKeyError(envelope.key) ?: envelopeError(
        envelope.schemaVersion, envelope.responseId, envelope.component, GraphV2DiskComponent.GENERAL
    )

private fun krxEnvelopeError(envelope: GraphV2KrxEnvelope): String? =
    generalKeyError(envelope.key.generalKey()) ?: if (envelope.key.krxCapabilityEpoch.isEmpty()) {
        "Empty KRX capability epoch"
    } else {
        envelopeError(envelope.schemaVersion, envelope.responseId, envelope.component, GraphV2DiskComponent.KRX)
    }

private fun envelopeError(
    schemaVersion: Int,
    responseId: String,
    component: GraphV2StoredComponent,
    kind: GraphV2DiskComponent
): String? {
    if (schemaVersion != GRAPH_V2_DISK_SCHEMA_VERSION) return "Unsupported disk schema"
    if (responseId.isEmpty()) return "Empty response ID"
    val ids = mutableSetOf<String>()
    val ordinals = mutableSetOf<Int>()
    for (indexed in component.series) {
        if (indexed.ordinal < 0 || !ordinals.add(indexed.ordinal)) return "Invalid or duplicate ordinal"
        val series = indexed.series
        if (series.axisGroup == null) return "Missing series axis group"
        if (!ids.add(series.seriesId)) return "Duplicate series ID"
        if (series.seriesId.startsWith("krx.") != (kind == GraphV2DiskComponent.KRX)) {
            return "Series prefix does not match the component"
        }
        if (series.decimals != null && series.decimals !in 0..8) return "Invalid series decimals"
        if (series.points.any { point ->
                !RateSanity.isPlausible(point.rate) ||
                    (point.high != null && (!RateSanity.isPlausible(point.high) || point.high < point.rate)) ||
                    (point.low != null && (!RateSanity.isPlausible(point.low) || point.low > point.rate))
            }) return "Invalid stored point values"
        series.carryIn?.let { carry ->
            if (!RateSanity.isPlausible(carry.rate)) return "Invalid carry-in rate"
            val earliest = series.points.minOfOrNull { it.timestamp }
            if (earliest != null && carry.observedAt >= earliest) return "Carry-in does not precede points"
        }
    }
    for ((id, seed) in component.inProgress) {
        if (id !in ids) return "Seed does not belong to a stored series"
        if (!RateSanity.isPlausible(seed.high) || !RateSanity.isPlausible(seed.low) ||
            !RateSanity.isPlausible(seed.close) || seed.low > seed.close || seed.close > seed.high
        ) return "Invalid stored seed values"
    }
    return null
}

private fun GraphV2StoredComponent.selectSeries(selected: List<GraphV2IndexedSeries>): GraphV2StoredComponent {
    val ids = selected.map { it.series.seriesId }.toSet()
    return copy(series = selected, inProgress = inProgress.filterKeys { it in ids })
}

private fun GraphV2StoredComponent.filterCatalog(
    tab: String,
    period: String,
    catalog: GraphCatalog?
): GraphV2StoredComponent {
    if (catalog == null) return this
    // A present catalog without this registry permits none, just as at the A1 boundary.
    val allowedIds = catalog.tabs[tab]?.periods?.get(GraphPeriod.fromCode(period))?.allSeries.orEmpty().toSet()
    return selectSeries(series.filter { it.series.seriesId in allowedIds })
}

private inline fun <T> codecResult(operation: () -> T): GraphV2Validation<T> = try {
    GraphV2Validation.Valid(operation())
} catch (_: SerializationException) {
    GraphV2Validation.Invalid("Invalid graph envelope JSON")
} catch (_: CharacterCodingException) {
    GraphV2Validation.Invalid("Invalid graph envelope UTF-8")
} catch (_: IllegalArgumentException) {
    GraphV2Validation.Invalid("Invalid graph envelope encoding or timestamp")
}

// Private storage schema only. Nullable fields remain required and are encoded as explicit nulls.
@Serializable
private data class GeneralKeyDto(
    val uid: String,
    @SerialName("user_access_epoch") val userAccessEpoch: String,
    val tab: String,
    val period: String
) {
    fun toDomain() = GraphV2GeneralKey(uid, userAccessEpoch, tab, period)
    companion object {
        fun from(key: GraphV2GeneralKey) = GeneralKeyDto(key.uid, key.userAccessEpoch, key.tab, key.period)
    }
}

@Serializable
private data class KrxKeyDto(
    val uid: String,
    @SerialName("user_access_epoch") val userAccessEpoch: String,
    @SerialName("krx_capability_epoch") val krxCapabilityEpoch: String,
    val tab: String,
    val period: String
) {
    fun toDomain() = GraphV2KrxKey(uid, userAccessEpoch, krxCapabilityEpoch, tab, period)
    companion object {
        fun from(key: GraphV2KrxKey) = KrxKeyDto(key.uid, key.userAccessEpoch, key.krxCapabilityEpoch, key.tab, key.period)
    }
}

@Serializable
private data class GeneralEnvelopeDto(
    @SerialName("schema_version") val schemaVersion: Int,
    val key: GeneralKeyDto,
    @SerialName("response_id") val responseId: String,
    val component: ComponentDto
) {
    fun toDomain() = GraphV2GeneralEnvelope(schemaVersion, key.toDomain(), responseId, component.toDomain())
    companion object {
        fun from(envelope: GraphV2GeneralEnvelope) = GeneralEnvelopeDto(
            envelope.schemaVersion, GeneralKeyDto.from(envelope.key), envelope.responseId, ComponentDto.from(envelope.component)
        )
    }
}

@Serializable
private data class KrxEnvelopeDto(
    @SerialName("schema_version") val schemaVersion: Int,
    val key: KrxKeyDto,
    @SerialName("response_id") val responseId: String,
    val component: ComponentDto
) {
    fun toDomain() = GraphV2KrxEnvelope(schemaVersion, key.toDomain(), responseId, component.toDomain())
    companion object {
        fun from(envelope: GraphV2KrxEnvelope) = KrxEnvelopeDto(
            envelope.schemaVersion, KrxKeyDto.from(envelope.key), envelope.responseId, ComponentDto.from(envelope.component)
        )
    }
}

@Serializable
private data class MetadataDto(
    @SerialName("bucket_size") val bucketSize: String,
    @SerialName("fetched_at") val fetchedAt: String,
    @SerialName("range_start") val rangeStart: String?,
    @SerialName("range_end") val rangeEnd: String?,
    @SerialName("domain_start_at") val domainStartAt: String?,
    @SerialName("domain_end_at") val domainEndAt: String?,
    @SerialName("live_domain_mode") val liveDomainMode: String?
) {
    fun toDomain() = GraphV2ServerMetadata(
        bucketSize, Instant.parse(fetchedAt), rangeStart, rangeEnd,
        domainStartAt?.let(Instant::parse), domainEndAt?.let(Instant::parse), liveDomainMode
    )
    companion object {
        fun from(metadata: GraphV2ServerMetadata) = MetadataDto(
            metadata.bucketSize, metadata.fetchedAt.toString(), metadata.rangeStart, metadata.rangeEnd,
            metadata.domainStartAt?.toString(), metadata.domainEndAt?.toString(), metadata.liveDomainMode
        )
    }
}

@Serializable
private data class ComponentDto(
    val metadata: MetadataDto,
    val series: List<IndexedSeriesDto>,
    @SerialName("in_progress") val inProgress: Map<String, SeedDto>
) {
    fun toDomain() = GraphV2StoredComponent(
        metadata.toDomain(), series.map { it.toDomain() }, inProgress.mapValues { it.value.toDomain() }
    )
    companion object {
        fun from(component: GraphV2StoredComponent) = ComponentDto(
            MetadataDto.from(component.metadata), component.series.map(IndexedSeriesDto::from),
            component.inProgress.mapValues { SeedDto.from(it.value) }
        )
    }
}

@Serializable
private data class IndexedSeriesDto(val ordinal: Int, val series: SeriesDto) {
    fun toDomain() = GraphV2IndexedSeries(ordinal, series.toDomain())
    companion object {
        fun from(indexed: GraphV2IndexedSeries) = IndexedSeriesDto(indexed.ordinal, SeriesDto.from(indexed.series))
    }
}

@Serializable
private data class SeriesDto(
    val id: String,
    val label: String,
    @SerialName("axis_group") val axisGroup: String?,
    val unit: String?,
    val decimals: Int?,
    val points: List<PointDto>,
    @SerialName("insufficient_history") val insufficientHistory: Boolean,
    @SerialName("per_point_metadata") val perPointMetadata: List<String>,
    @SerialName("carry_in") val carryIn: CarryInDto?
) {
    fun toDomain() = FreeGraphSeries(
        id, points.map { it.toDomain() }, label, axisGroup, unit, decimals,
        insufficientHistory, perPointMetadata, carryIn?.toDomain()
    )
    companion object {
        fun from(series: FreeGraphSeries) = SeriesDto(
            series.seriesId, series.label, series.axisGroup, series.unit, series.decimals,
            series.points.map(PointDto::from), series.insufficientHistory, series.perPointMetadata,
            series.carryIn?.let(CarryInDto::from)
        )
    }
}

@Serializable
private data class PointDto(
    val timestamp: String,
    val rate: Double,
    val high: Double?,
    val low: Double?,
    val source: String?,
    @SerialName("close_basis") val closeBasis: String?,
    @SerialName("source_method") val sourceMethod: String?,
    @SerialName("contract_code") val contractCode: String?
) {
    fun toDomain() = FreeGraphPoint(Instant.parse(timestamp), rate, high, low, source, closeBasis, sourceMethod, contractCode)
    companion object {
        fun from(point: FreeGraphPoint) = PointDto(
            point.timestamp.toString(), point.rate, point.high, point.low, point.source,
            point.closeBasis, point.sourceMethod, point.contractCode
        )
    }
}

@Serializable
private data class CarryInDto(val rate: Double, @SerialName("observed_at") val observedAt: String) {
    fun toDomain() = FreeGraphCarryIn(rate, Instant.parse(observedAt))
    companion object {
        fun from(carry: FreeGraphCarryIn) = CarryInDto(carry.rate, carry.observedAt.toString())
    }
}

@Serializable
private data class SeedDto(
    @SerialName("bucket_start") val bucketStart: String,
    val high: Double,
    val low: Double,
    val close: Double,
    @SerialName("sampled_at") val sampledAt: String
) {
    fun toDomain() = GraphV2InProgress(Instant.parse(bucketStart), high, low, close, Instant.parse(sampledAt))
    companion object {
        fun from(seed: GraphV2InProgress) = SeedDto(seed.bucketStart.toString(), seed.high, seed.low, seed.close, seed.sampledAt.toString())
    }
}
