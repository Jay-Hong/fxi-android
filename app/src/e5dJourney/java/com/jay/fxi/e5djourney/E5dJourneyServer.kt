package com.jay.fxi.e5djourney

import com.jay.fxi.BuildConfig
import com.jay.fxi.data.free.FreeSnapshotSchedulePolicy
import com.jay.fxi.data.remote.dto.EntitlementsResponse
import com.jay.fxi.di.ServerEndpoints
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.ByteString

/**
 * S7 E5d-J (`e5dj_j2_plan.claude.r3.md` §2, R4, R8): the journey's loopback server, the one destination of every FXi REST and
 * WebSocket endpoint in this build type. Its table is exact on method, path and query; anything else is recorded as unexpected
 * and answered 404, never absorbed. Every request is recorded with the response kind it was given and, for entitlements, the
 * revision of the answer that was in force. A new process starts with a new server and a free answer: nothing carries over.
 *
 * Free snapshots are the checked-in `contracts/v2/free` documents, moved onto the current publish slot
 * ([FreeSnapshotSchedulePolicy.basisOf]) on the free scheduler's own clock, so they read as fresh.
 *
 * J2b2 (`s7e5dj_j2b2_design.codex.r1.md`–`r3.md`) adds what a premium session asks for, each row exact on method, path and
 * query (missing, extra or repeated query keys are outside the table):
 * - topic snapshots, REST and WebSocket alike, from the checked-in `contracts/v2/topic` documents, as they are;
 * - the graph catalog and the USD 1d tab from the two `contracts/v2/graph` documents the build type names
 *   ([BuildConfig.E5DJ_GRAPH_CATALOG_ASSET], [BuildConfig.E5DJ_GRAPH_USD_1D_ASSET]), as they are;
 * - bank alert settings held for the one owner this build signs in: the `contracts/v2/alerts` list to start with, its rows
 *   bound to that owner; a create is checked, applied with a new id and the values asked for, and answered from the contract's
 *   row shape bound to the same owner. A row's `updated_at` is its creation time where the contract has null, since the app's
 *   alert row cannot read a null there ([boundRow]);
 * - and three answers this fixture makes up, disclosed as such: the entitlements answer (the app's own DTO, as in J2a), an empty
 *   news answer in the app's news shape, and an empty success for a device registration.
 * Source settings start empty. The fixture returns the requested threshold on creation and adds 10 to the canonical
 * threshold served on subsequent reads. Source history is a fixed contract response independent of those settings.
 * The fixture synthesizes tether/1d with the USD document's metadata and an empty series.
 * The WebSocket answers a valid subscribe with an acknowledgement built to the contract's wire schema without lease fields, its
 * active set being everything this connection keeps, then each accepted topic's snapshot; a text `ping` with a JSON pong. Every
 * upgrade, open, frame in or out (with what `send` returned), close and failure is recorded against its connection number;
 * no frame is dumped and no id token value is ever recorded.
 */
internal class E5dJourneyServer(private val load: (String) -> ByteArray) : AutoCloseable {
    private val server = MockWebServer()
    private val json = Json { encodeDefaults = false }

    /** The entitlements answer in force: its kind and revision together, replaced whole (`s7e5dj_j2a.codex.r1.md` Q4-B). */
    private class AnswerState(val premium: Boolean, val revision: Int) {
        override fun toString() = "${if (premium) "active" else "free"} rev=$revision"
    }

    /** Read and replaced only inside [E5dJourneyRecord.addWith], so a switch and a request's choice keep their record order. */
    private var answer = AnswerState(premium = false, revision = 0)

    /** The one owner's bank alert settings as this server holds them; filled from the contract on first use. Record lock only. */
    private var alertRows: MutableList<JsonObject>? = null
    private var nextAlertId = 1001
    private val sourceRows = mutableListOf<JsonObject>()
    private var nextSourceId = 2001
    private val connections = AtomicInteger()

    /** The REST and WebSocket base of this process's server, once it is bound. */
    lateinit var endpoints: ServerEndpoints
        private set

    fun start(): ServerEndpoints {
        val starter = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "e5d-journey-server").apply { isDaemon = true } }
        try {
            endpoints = starter.submit<ServerEndpoints> {
                server.dispatcher = dispatcher
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                ServerEndpoints("http://127.0.0.1:${server.port}/", "ws://127.0.0.1:${server.port}/ws")
            }.get(10, TimeUnit.SECONDS)
        } finally {
            starter.shutdown()
        }
        E5dJourneyRecord.add("server", "bound ${endpoints.restBaseUrl}")
        return endpoints
    }

    /** Switches the entitlements answer; the next request chooses it. Returns the new revision. */
    fun answer(premium: Boolean): Int = E5dJourneyRecord.addWith("server") {
        val next = AnswerState(premium, answer.revision + 1)
        answer = next
        next.revision to "entitlements $next"
    }

    fun answerState(): String = E5dJourneyRecord.under { answer.toString() }

    /**
     * U3a-2: the owner's row [id] put in the shape the real server holds after a once alert was sent ("fired": disabled,
     * triggered, with a trigger time) or in the contract's own shape ("armed"). Only those three fields are replaced; the rows'
     * order and number and every other field stay. Another state, or a row that is not there, changes nothing — not even the
     * list being read for the first time — and leaves no line. The check, the change and its line are under the record's one
     * lock, as a request's answer and its line are.
     */
    fun alertRow(id: Int, state: String): Boolean = E5dJourneyRecord.under {
        val fired = when (state) {
            "fired" -> true
            "armed" -> false
            else -> return@under false
        }
        val rows = alertRows ?: initialRows()
        val index = rows.indexOfFirst { it.getValue("id").jsonPrimitive.intOrNull == id }
        if (index < 0) return@under false
        rows[index] = JsonObject(rows[index] + mapOf(
            "is_enabled" to JsonPrimitive(!fired),
            "triggered" to JsonPrimitive(fired),
            "triggered_at" to (if (fired) JsonPrimitive(ALERT_FIRED_AT) else JsonNull)
        ))
        alertRows = rows
        E5dJourneyRecord.add("server", "alerts row id=$id -> $state n=${rows.size}")
        true
    }

    fun sourceRow(id: Int, state: String): Boolean = E5dJourneyRecord.under {
        if (id != 102) return@under false
        if (state == "seed") {
            if (sourceRows.isNotEmpty()) return@under false
            val template = Json.parseToJsonElement(load(SOURCE_ROW).decodeToString()).jsonObject
            val seed = mapOf(
                "id" to JsonPrimitive(id), "source" to JsonPrimitive("upbit"), "asset" to JsonPrimitive(SOURCE_ASSET),
                "condition" to JsonPrimitive("below"), "threshold" to JsonPrimitive(1385.0), "repeat_interval_sec" to JsonNull,
                "is_enabled" to JsonPrimitive(true), "triggered" to JsonPrimitive(false), "triggered_at" to JsonNull
            )
            if (!seed.all { (key, value) -> template[key] == value }) return@under false
            sourceRows += JsonObject(template + ("user_id" to JsonPrimitive(E5dJourneyIdentity.UID)))
        } else {
            if (state != "armed" && state != "fired") return@under false
            val index = sourceRows.indices.singleOrNull { sourceRows[it]["id"] == JsonPrimitive(id) } ?: return@under false
            val row = sourceRows[index]
            if (row["user_id"] != JsonPrimitive(E5dJourneyIdentity.UID) || row["source"] != JsonPrimitive("upbit") ||
                row["asset"] != JsonPrimitive(SOURCE_ASSET)) return@under false
            val fired = state == "fired"
            sourceRows[index] = JsonObject(row + mapOf(
                "is_enabled" to JsonPrimitive(!fired), "triggered" to JsonPrimitive(fired),
                "triggered_at" to (if (fired) JsonPrimitive(ALERT_FIRED_AT) else JsonNull)
            ))
        }
        E5dJourneyRecord.add("server", "source row id=$id -> $state n=${sourceRows.size}")
        true
    }

    override fun close() {
        server.shutdown()
    }

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            // MockWebServer queues every request it records; drain it so a long run keeps nothing.
            while (server.takeRequest(0, TimeUnit.MILLISECONDS) != null) Unit
            val url = request.requestUrl
            val method = request.method.orEmpty()
            val path = url?.encodedPath.orEmpty()
            val query = url?.let { u -> u.queryParameterNames.associateWith { u.queryParameterValues(it) } }.orEmpty()
            val queryText = query.entries.sortedBy { it.key }.joinToString("&") { (k, v) -> "$k=${v.joinToString(",")}" }
            if (method == "GET" && path == ENTITLEMENTS) {
                // Chosen and recorded under one lock: the line names exactly the state served, in its order with the switches.
                return E5dJourneyRecord.addWith("http") {
                    val (kind, response) = entitlements(query)
                    response to "$method $path q=$queryText -> $kind"
                }
            }
            if (path == ALERT_SETTINGS) {
                // The owner's list is read and changed under the record's lock, so each line names the list as it then was.
                val body = request.body.readUtf8()
                return E5dJourneyRecord.addWith("http") {
                    val (kind, response) = alertSettings(method, query, body)
                    response to "$method $path q=$queryText -> $kind"
                }
            }
            if (path == SOURCE_SETTINGS) {
                val body = request.body.readUtf8()
                return E5dJourneyRecord.addWith("http") {
                    val (kind, response) = sourceSettings(method, query, body)
                    response to "$method $path q=$queryText -> $kind"
                }
            }
            val (kind, response) = route(method, path, query, request)
            E5dJourneyRecord.add("http", "$method $path q=$queryText -> $kind")
            return response
        }
    }

    private fun route(method: String, path: String, query: Map<String, List<String?>>, request: RecordedRequest): Pair<String, MockResponse> {
        fun single(name: String): String? = query[name]?.singleOrNull()
        if (method == "POST" && path == REGISTER_DEVICE) return registerDevice(query, request.body.readUtf8())
        if (method != "GET") return unexpected()
        return when (path) {
            "/api/v2/free/snapshot" -> {
                val tab = single("tab")
                val period = single("period")
                if (query.keys != setOf("tab", "period") || tab !in FREE_TABS || period !in PERIODS) return unexpected()
                "free-snapshot $tab $period" to ok(freeSnapshot(tab!!, period!!))
            }
            "/api/v2/topics/snapshot" -> {
                val topic = single("topic")
                val file = topic?.let { TOPIC_FILES[it] }
                if (query.keys != setOf("topic") || file == null) return unexpected()
                "topic-snapshot $topic" to ok(load(file))
            }
            "/api/comparison-alerts" -> {
                if (request.requestUrl?.encodedQuery != null) return unexpected()
                "comparison alerts list n=0" to ok("""{"alerts":[],"total_count":0}""".encodeToByteArray())
            }
            SOURCE_HISTORY -> {
                if (query != mapOf("asset" to listOf(SOURCE_ASSET), "limit" to listOf("100")) || request.bodySize != 0L) return unexpected()
                val bytes = load(SOURCE_HISTORY_LIST)
                "source history body=${Json.parseToJsonElement(bytes.decodeToString())}" to ok(bytes)
            }
            "/api/v2/graph/catalog" -> if (query.isNotEmpty()) unexpected() else "graph-catalog" to ok(load(BuildConfig.E5DJ_GRAPH_CATALOG_ASSET))
            "/api/v2/graph/tab" -> when (query) {
                mapOf("tab" to listOf("usd"), "period" to listOf("1d")) -> "graph-tab usd 1d" to ok(load(BuildConfig.E5DJ_GRAPH_USD_1D_ASSET))
                mapOf("tab" to listOf("tether"), "period" to listOf("1d")) ->
                    if (request.bodySize == 0L) "graph-tab tether 1d" to ok(tetherGraph()) else unexpected()
                else -> unexpected()
            }
            "/api/news" ->
                if (query != mapOf("limit" to listOf("100"), "hours" to listOf("24.0"))) unexpected() else "news empty" to ok(emptyNews())
            "/ws" -> {
                val upgrade = request.getHeader("Upgrade")
                if (query.isNotEmpty() || !upgrade.equals("websocket", ignoreCase = true)) return unexpected()
                val id = connections.incrementAndGet()
                "ws-upgrade conn=$id" to MockResponse().withWebSocketUpgrade(Socket(id))
            }
            else -> unexpected()
        }
    }

    private fun tetherGraph(): ByteArray {
        val usd = Json.parseToJsonElement(load(BuildConfig.E5DJ_GRAPH_USD_1D_ASSET).decodeToString()).jsonObject
        return JsonObject(mapOf(
            "tab" to JsonPrimitive("tether"), "period" to JsonPrimitive("1d"),
            "metadata" to usd.getValue("metadata"), "series" to JsonArray(emptyList())
        )).toString().encodeToByteArray()
    }

    /** The record lock covers validation, id allocation, publication and the bytes described by the evidence. */
    private fun sourceSettings(method: String, query: Map<String, List<String?>>, body: String): Pair<String, MockResponse> {
        if (method == "GET") {
            if (query != mapOf("asset" to listOf(SOURCE_ASSET)) || body.isNotEmpty()) return unexpected()
            if (sourceRows.isEmpty()) return "source settings list n=0" to ok("""{"settings":[],"total_count":0}""".encodeToByteArray())
            val bytes = JsonObject(mapOf(
                "settings" to JsonArray(sourceRows), "total_count" to JsonPrimitive(sourceRows.size)
            )).toString().encodeToByteArray()
            return "source settings list n=${sourceRows.size} body=${bytes.decodeToString()}" to ok(bytes)
        }
        if (method != "POST" || query.isNotEmpty()) return unexpected()
        val asked = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return unexpected()
        if (asked.keys != SOURCE_CREATE_KEYS) return unexpected()
        fun string(key: String) = (asked[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (string("source") !in SOURCE_EXCHANGES || string("asset") != SOURCE_ASSET ||
            string("condition") !in setOf("above", "below") || asked["is_enabled"] != JsonPrimitive(true)) return unexpected()
        val number = (asked["threshold"] as? JsonPrimitive)?.takeUnless { it.isString } ?: return unexpected()
        if (!JSON_NUMBER.matches(number.content)) return unexpected()
        val threshold = number.doubleOrNull ?: return unexpected()
        if (threshold <= 0 || !(threshold + 10.0).isFinite()) return unexpected()

        val template = Json.parseToJsonElement(load(SOURCE_ROW).decodeToString()).jsonObject
        val id = nextSourceId++
        val returned = JsonObject(template + asked + mapOf("id" to JsonPrimitive(id), "user_id" to JsonPrimitive(E5dJourneyIdentity.UID)))
        val bytes = returned.toString().encodeToByteArray()
        sourceRows += JsonObject(returned + ("threshold" to JsonPrimitive(threshold + 10.0)))
        val evidence = JsonObject(mapOf(
            "asked" to asked, "returned" to Json.parseToJsonElement(bytes.decodeToString()), "stored" to sourceRows.last()
        ))
        return "source create id=$id n=${sourceRows.size} evidence=$evidence" to ok(bytes)
    }

    /** Only ever called inside [E5dJourneyRecord.addWith]: the owner's list, or a create applied to it. */
    private fun alertSettings(method: String, query: Map<String, List<String?>>, body: String): Pair<String, MockResponse> {
        if (query.isNotEmpty()) return unexpected()
        val rows = alertRows ?: initialRows().also { alertRows = it }
        return when (method) {
            "GET" -> "alerts list n=${rows.size} ids=${rows.map { it.getValue("id") }}" to
                ok(Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("settings" to JsonArray(rows), "total_count" to JsonPrimitive(rows.size)))).encodeToByteArray())
            "POST" -> {
                val asked = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return unexpected()
                val created = alertRow(asked) ?: return unexpected()
                rows += created
                val repeat = if ("repeat_interval_sec" in asked) asked.getValue("repeat_interval_sec").toString() else "omitted"
                "alerts create id=${created.getValue("id")} owner=${E5dJourneyIdentity.UID} bank=${created.getValue("bank")} " +
                    "currency=${created.getValue("currency")} condition=${created.getValue("condition")} threshold=${created.getValue("threshold")} " +
                    "enabled=${created.getValue("is_enabled")} repeat=$repeat n=${rows.size}" to
                    ok(Json.encodeToString(JsonObject.serializer(), created).encodeToByteArray())
            }
            else -> unexpected()
        }
    }

    /** The owner's list as the contract's file gives it, every row bound ([boundRow]); whoever takes it decides whether to keep it. */
    private fun initialRows(): MutableList<JsonObject> = Json.parseToJsonElement(load(ALERT_LIST).decodeToString()).jsonObject
        .getValue("settings").jsonArray.map { row -> boundRow(row.jsonObject) }.toMutableList()

    /**
     * A contract row as this server holds it: bound to this build's owner, and with an update time. The contract's rows carry
     * `updated_at: null`, which the app's `AlertSetting` (a non-null `updated_at`) cannot read; the real server's table fills
     * it on insert. The fixture gives a row its creation time — a departure from the template, disclosed.
     */
    private fun boundRow(row: JsonObject): JsonObject = JsonObject(row + mapOf(
        "user_id" to JsonPrimitive(E5dJourneyIdentity.UID),
        "updated_at" to (row["updated_at"]?.takeUnless { it is JsonNull } ?: row.getValue("created_at"))
    ))

    /**
     * The row a create asks for, or null if the request is not one: the contract's row shape, bound to this build's owner, under
     * a new id, with the bank, currency, condition, threshold, enabled flag and repeat interval asked for.
     */
    private fun alertRow(asked: JsonObject): JsonObject? {
        val bank = (asked["bank"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in setOf("investing", "kb", "hana", "shinhan", "woori", "ibk", "nh", "sc", "bs", "citi") } ?: return null
        val currency = (asked["currency"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in ALERT_CURRENCIES } ?: return null
        val condition = (asked["condition"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it == "above" || it == "below" } ?: return null
        val threshold = (asked["threshold"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val enabled = (asked["is_enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
        val repeat: JsonElement = when (val value = asked["repeat_interval_sec"]) {
            null, JsonNull -> JsonNull
            is JsonPrimitive -> value.takeUnless { it.isString }?.intOrNull?.takeIf { it in setOf(60, 300, 600, 1800, 3600, 7200, 14400, 21600, 43200, 86400) }?.let(::JsonPrimitive) ?: return null
            else -> return null
        }
        if (asked.keys - ALERT_CREATE_KEYS != emptySet<String>()) return null
        val template = Json.parseToJsonElement(load(ALERT_ROW).decodeToString()).jsonObject
        val now = JsonPrimitive(kst(Clock.System.now()))
        return JsonObject(template + mapOf(
            "id" to JsonPrimitive(nextAlertId++),
            "user_id" to JsonPrimitive(E5dJourneyIdentity.UID),
            "bank" to JsonPrimitive(bank),
            "currency" to JsonPrimitive(currency),
            "condition" to JsonPrimitive(condition),
            "threshold" to JsonPrimitive(threshold),
            "is_enabled" to JsonPrimitive(enabled),
            "repeat_interval_sec" to repeat,
            "created_at" to now,
            "updated_at" to now,
            "triggered" to JsonPrimitive(false),
            "triggered_at" to JsonNull
        ))
    }

    /** A device registration: recorded without its token, answered with an empty success. */
    private fun registerDevice(query: Map<String, List<String?>>, body: String): Pair<String, MockResponse> {
        val asked = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val token = (asked?.get("device_token") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val platform = (asked?.get("platform") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (query.isNotEmpty() || asked == null || (token.isNullOrBlank() || token.length < 10) || platform != "android" || asked.keys != setOf("device_token", "platform")) {
            return unexpected()
        }
        return "register-device token=present platform=android" to ok("{}".encodeToByteArray())
    }

    private fun emptyNews(): ByteArray = Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
        "news" to JsonArray(emptyList()),
        "metadata" to JsonObject(mapOf(
            "returned_count" to JsonPrimitive(0),
            "window_hours" to JsonPrimitive(24.0),
            "responded_at" to JsonPrimitive(kst(Clock.System.now()))
        ))
    ))).encodeToByteArray()

    /** One WebSocket connection, by its number: what it keeps subscribed, and every event of its life in the record. */
    private inner class Socket(private val id: Int) : WebSocketListener() {
        private val active = LinkedHashSet<String>()

        private fun note(text: String) = E5dJourneyRecord.add("ws", "conn=$id $text")

        private fun send(socket: WebSocket, text: String, what: String) {
            val sent = runCatching { socket.send(text) }
            note("send $what -> ${sent.getOrNull() ?: "threw ${sent.exceptionOrNull()?.javaClass?.simpleName}"}")
        }

        override fun onOpen(webSocket: WebSocket, response: Response) = note("open")

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text == "ping") {
                note("recv ping")
                send(webSocket, PONG, "pong")
                return
            }
            val frame = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (frame == null) return note("recv UNEXPECTED text (${text.length} chars, not a JSON object)")
            val type = (frame["type"] as? JsonPrimitive)?.contentOrNull
            if (type != "subscribe") return note("recv UNEXPECTED text type=$type")
            val requestId = (frame["request_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
            val tokenPresent = (frame["id_token"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.isNotEmpty() == true
            val topics = (frame["topics"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            val valid = requestId != null && tokenPresent && topics != null && topics.isNotEmpty() && null !in topics &&
                topics.size == topics.toSet().size && topics.all { it != null && it in TOPIC_FILES } && frame.keys == setOf("type", "request_id", "id_token", "topics")
            if (!valid) return note("recv UNEXPECTED subscribe request=${requestId ?: "-"} token=${if (tokenPresent) "present" else "absent"} topics=$topics")
            val accepted = topics!!.filterNotNull()
            val now = synchronized(active) { active += accepted; active.toList() }
            note("recv subscribe request=$requestId token=present topics=$accepted")
            val ack = JsonObject(mapOf(
                "type" to JsonPrimitive("subscription_ack"),
                "request_id" to JsonPrimitive(requestId),
                "operation" to JsonPrimitive("subscribe"),
                "accepted_topics" to JsonArray(accepted.map { JsonObject(mapOf("topic" to JsonPrimitive(it))) }),
                "rejected_topics" to JsonArray(emptyList()),
                "removed_topics" to JsonArray(emptyList()),
                "active_subscriptions" to JsonArray(now.map { JsonObject(mapOf("topic" to JsonPrimitive(it))) })
            ))
            send(webSocket, Json.encodeToString(JsonObject.serializer(), ack), "ack request=$requestId accepted=$accepted active=$now")
            for (topic in accepted) send(webSocket, load(TOPIC_FILES.getValue(topic)).decodeToString(), "snapshot $topic")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = note("recv UNEXPECTED binary (${bytes.size} bytes)")

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            note("closing code=$code")
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = note("closed code=$code")

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = note("failure ${t.javaClass.simpleName}")
    }

    /** Only ever called inside [E5dJourneyRecord.addWith]. */
    private fun entitlements(query: Map<String, List<String?>>): Pair<String, MockResponse> {
        if (query.isNotEmpty() && query != mapOf("fresh_premium" to listOf("true"))) return unexpected()
        val served = answer
        val body = json.encodeToString(EntitlementsResponse.serializer(), EntitlementsResponse(false, served.premium))
        return "entitlements $served" to ok(body.encodeToByteArray())
    }

    private fun unexpected(): Pair<String, MockResponse> = "UNEXPECTED" to MockResponse().setResponseCode(404)

    private fun ok(body: ByteArray): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(Buffer().write(body))

    /** The contract document for [tab] and [period], moved so that its `as_of` is the current publish slot. */
    private fun freeSnapshot(tab: String, period: String): ByteArray {
        val template = Json.parseToJsonElement(load("$tab-$period.json").decodeToString()).jsonObject
        val oldAsOf = Instant.parse(template.getValue("as_of").jsonPrimitive.content)
        val asOf = FreeSnapshotSchedulePolicy.basisOf(Clock.System.now())
        val shift = asOf - oldAsOf
        val graph = template.getValue("graph").jsonObject
        val range = graph.getValue("range").jsonObject
        val oldStart = LocalDate.parse(range.getValue("start").jsonPrimitive.content)
        val oldEnd = LocalDate.parse(range.getValue("end").jsonPrimitive.content)
        val end = asOf.toLocalDateTime(SEOUL).date
        val startDays = oldStart.toEpochDays() - oldEnd.toEpochDays()
        val start = end.plus(DatePeriod(days = startDays))
        val domainStart = if (period == "1d") asOf - 1.days else start.atStartOfDayIn(SEOUL)
        val generated = minOf(Instant.parse(template.getValue("generated_at").jsonPrimitive.content) + shift, Clock.System.now())
        val rebuilt = JsonObject(template + mapOf(
            "as_of" to JsonPrimitive(kst(asOf)),
            "generated_at" to JsonPrimitive(kst(generated)),
            "refresh_not_before" to shifted(template["refresh_not_before"], shift),
            "rate" to rate(template.getValue("rate").jsonObject, shift),
            "graph" to JsonObject(graph + mapOf(
                "domain_start_at" to JsonPrimitive(kst(domainStart)),
                "domain_end_at" to JsonPrimitive(kst(asOf)),
                "range" to JsonObject(mapOf("start" to JsonPrimitive(start.toString()), "end" to JsonPrimitive(end.toString()))),
                "series" to JsonArray(graph.getValue("series").jsonArray.map { series ->
                    val s = series.jsonObject
                    JsonObject(s + ("data" to JsonArray(s.getValue("data").jsonArray.map { point ->
                        val p = point.jsonObject
                        JsonObject(p + ("ts" to shifted(p["ts"], shift)))
                    })))
                })
            ))
        ))
        return Json.encodeToString(JsonObject.serializer(), rebuilt).encodeToByteArray()
    }

    private fun rate(rate: JsonObject, shift: Duration): JsonObject {
        val entries = rate["entries"]?.jsonArray ?: return rate
        return JsonObject(rate + ("entries" to JsonArray(entries.map { entry ->
            val e = entry.jsonObject
            JsonObject(e + ("timestamp" to shifted(e["timestamp"], shift)))
        })))
    }

    private fun shifted(value: JsonElement?, shift: Duration): JsonElement {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return value ?: JsonPrimitive(null as String?)
        return JsonPrimitive(kst(Instant.parse(text) + shift))
    }

    private fun kst(instant: Instant): String {
        val t = instant.toLocalDateTime(SEOUL)
        return "%04d-%02d-%02dT%02d:%02d:%02d+09:00".format(t.year, t.monthNumber, t.dayOfMonth, t.hour, t.minute, t.second)
    }

    private companion object {
        const val ENTITLEMENTS = "/api/entitlements"
        const val ALERT_SETTINGS = "/api/notification-settings"
        const val SOURCE_SETTINGS = "/api/source-notification-settings"
        const val SOURCE_HISTORY = "/api/source-notification-logs"
        const val SOURCE_ASSET = "usdt-krw"
        const val SOURCE_ROW = "source-setting-response.json"
        const val SOURCE_HISTORY_LIST = "source-history-list.json"
        val SOURCE_CREATE_KEYS = setOf("source", "asset", "condition", "threshold", "is_enabled")
        val SOURCE_EXCHANGES = setOf("upbit", "bithumb", "coinone", "korbit", "gopax")
        val JSON_NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")
        const val REGISTER_DEVICE = "/api/register-device"
        const val ALERT_LIST = "bank-settings-list.json"
        const val ALERT_ROW = "bank-setting-response.json"

        /** U3a-2: the trigger time a row gets when it is put in the fired shape; a fixed value after the contract row's creation. */
        const val ALERT_FIRED_AT = "2026-09-01T09:00:00+09:00"
        const val PONG = "{\"type\":\"pong\"}"
        val ALERT_CURRENCIES = setOf("usd-krw", "jpy-krw", "eur-krw")
        val ALERT_CREATE_KEYS = setOf("bank", "currency", "condition", "threshold", "is_enabled", "repeat_interval_sec")

        /** The GENERAL topics and their contract snapshots; nothing else is served or accepted. */
        val TOPIC_FILES = mapOf(
            "fx:usd-krw" to "snapshot-fx-usd-krw.json",
            "fx:jpy-krw" to "snapshot-fx-jpy-krw.json",
            "fx:eur-krw" to "snapshot-fx-eur-krw.json",
            "usdt:krw" to "snapshot-tether.json",
            "dxy:spot" to "snapshot-dxy.json"
        )
        val SEOUL = TimeZone.of("Asia/Seoul")
        val FREE_TABS = setOf("usd", "jpy", "eur", "tether")
        val PERIODS = setOf("1d", "1w", "3m", "1y")
    }
}
