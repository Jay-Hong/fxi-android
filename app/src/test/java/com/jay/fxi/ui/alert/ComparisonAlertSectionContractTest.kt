package com.jay.fxi.ui.alert

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.data.alerts.*
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.TopicUseTag
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.ComparisonAlertSetting
import com.jay.fxi.domain.model.FreeTab
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit

class ComparisonAlertSectionContractTest {
    private val a1 = AuthIdentityFence("user-a", 1L)
    private val stamp = Instant.parse("2026-10-01T01:00:00Z")
    private fun row(id: Int, tab: String = "usd", kind: String = "absolute"): ComparisonAlertSetting {
        val tether = tab == "tether"
        val asset = if (tether) "usdt-krw" else "$tab-krw"
        return ComparisonAlertSetting(id, a1.uid, tab, if (tether) "bithumb" else "kb", asset,
            if (tether && kind == "absolute") "upbit" else "hana", if (kind == "signed") "usd-krw" else asset,
            kind, "gte", 4.25, true, false, 60, null, Instant.fromEpochMilliseconds(stamp.toEpochMilliseconds() + (id % 7) * 1000L), stamp)
    }
    private fun mixed() = listOf(row(81, "tether", "signed"), row(62), row(17, "jpy"), row(51, "tether"), row(14, "eur"),
        row(12, "tether", "signed").copy(isEnabled = false, triggered = true), row(3), row(8, "tether"),
        row(90).copy(leftSource = "krx"), row(91).copy(rightSource = "krx"), row(92).copy(userId = "other"),
        row(93), row(93).copy(userId = "other"))

    private fun loaded(rows: List<ComparisonAlertSetting>) = ComparisonAlertSettingsState(
        AlertLoadPhase.Ready, a1, ComparisonAlertRowPolicy.adopt(a1.uid, rows))

    @Test fun B08_projectsTheEntireAdoptedListInServerOrder() {
        val state = loaded(mixed())
        for ((tab, kind, ids) in listOf(Triple("tether", "signed", listOf(81, 12)), Triple("usd", "absolute", listOf(62, 3)),
            Triple("jpy", "absolute", listOf(17)), Triple("eur", "absolute", listOf(14)), Triple("tether", "absolute", listOf(51, 8)))) {
            val body = state.body(a1, tab, kind) as ComparisonSectionBody.Loaded
            assertEquals(ids, body.rows.map { it.id })
            assertEquals(if (kind == "signed") 1 else ids.size, body.enabledCount)
        }
        val rows = (1..32).map { row(200 - it).copy(isEnabled = it % 3 == 0) }
        val body = loaded(rows).body(a1, "usd", "absolute") as ComparisonSectionBody.Loaded
        assertEquals(rows, body.rows)
        assertEquals(10, body.enabledCount)
    }

    @Test fun B09_snapshotPrecedenceAndTypedFirstErrors() {
        val failures = listOf(
            AlertLoadFailure.AuthRequired to "로그인이 필요합니다.",
            AlertLoadFailure.Forbidden to "접근 권한이 없습니다.",
            AlertLoadFailure.Failed(AlertRepositoryFailureKind.NETWORK, null) to "네트워크 연결을 확인해 주세요.",
            AlertLoadFailure.Failed(AlertRepositoryFailureKind.HTTP, 500) to "알림 설정을 불러오지 못했습니다."
        )
        for ((failure, message) in failures) {
            val phase = AlertLoadPhase.TerminalError(failure)
            assertEquals(ComparisonSectionBody.Error(message), ComparisonAlertSettingsState(phase, a1).body(a1, "usd", "absolute"))
            for (rows in listOf(emptyList(), listOf(row(1)), listOf(row(2, "tether")))) {
                val state = loaded(rows).copy(phase = phase)
                assertEquals(ComparisonSectionBody.Loaded(rows.filter { it.tab == "usd" }), state.body(a1, "usd", "absolute"))
                assertEquals(ComparisonSectionBody.Loading, state.body(AuthIdentityFence(a1.uid, 2), "usd", "absolute"))
            }
        }
        for (phase in listOf(AlertLoadPhase.Idle, AlertLoadPhase.Loading, AlertLoadPhase.Retrying)) {
            assertEquals(ComparisonSectionBody.Loading, ComparisonAlertSettingsState(phase).body(a1, "usd", "absolute"))
        }
    }

    @Test fun B10_separateScreenAndSpokenLabelsInBothLocales() {
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.GERMANY)) {
                Locale.setDefault(locale)
                val signed = row(1, "tether", "signed")
                for ((value, visible, spoken) in listOf(Triple(30.0, "+30", "+30"), Triple(0.0, "0", "+0"),
                    Triple(-4.125, "-4.12", "-4.12"))) {
                    val lower = value < 0
                    val text = signed.copy(threshold = value, operator = if (lower) "lte" else "gte").rowText()
                    assertEquals("빗썸-하나", text.pair)
                    assertEquals(visible, text.value)
                    assertEquals(if (lower) "▽ 이하" else "△ 이상", text.direction)
                    assertEquals("빗썸 vs 하나 김프 ${spoken}원 " + if (lower) "이하" else "이상", text.description)
                }
                for (n in listOf(4, 1000, 10000)) {
                    assertEquals("$n.0", row(1).copy(threshold = n.toDouble()).rowText().value)
                    assertEquals("$n", row(1, "tether").copy(threshold = n.toDouble()).rowText().value)
                }
                val tether = row(1, "tether").copy(leftSource = "upbit", rightSource = "bithumb", operator = "lte", threshold = 10000.0)
                assertEquals("업비트↔빗썸", tether.rowText().pair)
                assertEquals("업비트−빗썸 차이 10000원 이하 좁혀지면", tether.rowText().description)
                assertEquals("국민↔하나", row(1).rowText().pair)
                assertEquals("국민−하나 차이 4.2원 이상 벌어지면", row(1).rowText().description)
                assertEquals("4.25", row(1, "tether").rowText().value)
                for (enabled in listOf(true, false)) for (triggered in listOf(true, false)) for (repeat in listOf(60, 31, null)) {
                    assertEquals(if (triggered) "알림 발송됨" else if (repeat == 60) "1분마다" else null,
                        row(1).copy(isEnabled = enabled, triggered = triggered, repeatIntervalSec = repeat).rowText().note)
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun B11_retirementAndDisposalArePermanent() {
        for (leave in listOf("publication", "hidden", "dispose")) {
            var shown = true
            var calls = 0
            var retired = 0
            val mount = ComparisonSectionMount({ shown }, { retired++ })
            val saved = { mount.runShown { calls++ } }
            saved()
            when (leave) {
                "publication" -> mount.end()
                "hidden" -> { shown = false; saved() }
                else -> mount.dispose()
            }
            shown = true
            saved()
            assertEquals(1, calls)
            assertEquals(if (leave == "dispose") 0 else 1, retired)
            ComparisonSectionMount({ shown }, {}).runShown { calls++ }
            assertEquals(2, calls)
        }
    }

    @Test fun B11_publishedOwnerAndTabInvalidateEvenAfterImmediateReturn() {
        val owner = TopicDisplayOwner(a1, 1L)
        val tab = FreeTab.USD
        val slot = ComparisonSlot(owner, tab)
        for ((other, selection) in listOf(null to tab, owner to FreeTab.JPY, owner to FreeTab.NEWS,
            TopicDisplayOwner(AuthIdentityFence(a1.uid, 2), 1L) to tab, TopicDisplayOwner(a1, 2L) to tab)) {
            var calls = 0
            val mount = ComparisonSectionMount({ slot.matches(owner, tab) }, {})
            if (!slot.matches(other, selection)) mount.end()
            mount.runShown { calls++ }
            assertEquals(0, calls)
            ComparisonSectionMount({ slot.matches(owner, tab) }, {}).runShown { calls++ }
            assertEquals(1, calls)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun B12_entireMountLifetimeUsesOnlyQuerylessSettingsReads() = runTest {
        val json = NetworkModule.provideWireJson()
        val tokens = object : AuthTokenSource {
            override fun currentIdentity() = AuthIdentity(a1.uid, a1.authGeneration)
            override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean) = "test"
        }
        val provider = AuthTokenProvider(tokens, orders = AccessOrderSequence())
        val tagged = java.util.concurrent.atomic.AtomicBoolean()
        val client = OkHttpClient.Builder().addInterceptor(AuthSnapshotInterceptor(provider))
            .addNetworkInterceptor { chain ->
                tagged.set(chain.request().tag(TopicUseTag::class.java) != null)
                chain.proceed(chain.request())
            }.addNetworkInterceptor(TopicUseNetworkInterceptor(provider)).build()
        MockWebServer().use { server ->
            val rows = listOf(row(1), row(2, "tether", "signed"))
            server.enqueue(MockResponse().setBody("""{"alerts":${json.encodeToString(rows)},"total_count":2}"""))
            val service = Retrofit.Builder().baseUrl(server.url("/")).client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
                .create(AuthenticatedApiService::class.java)
            val repository = AlertRepository(AuthenticatedApiClient(service, AuthenticatedTransport(provider, admitted = { true }), json))
            val read = comparisonSettingsGet(repository)
            val fence = TopicSessionFence(a1, "epoch", TopicGrantToken(11))
            var snapshot = TopicAccessSnapshot.INITIAL.copy(revision = 1L,
                facts = TopicAccessFacts.NONE.copy(token = fence.grant, tokenStanding = true, userBlocks = emptySet()))
            val premium = MutableStateFlow(OwnedPremiumAccess(a1.uid, a1.authGeneration, PremiumAccessState.PremiumConfirmed))
            val revisions = MutableStateFlow(0L)
            val access = AlertSettingsAccess({ a1 }, { fence }, SnapshotTopicUseAuthority { snapshot }, { premium.value }, { true },
                GraphProtectedAdmission({ a1 }, DeletionAdmissionStore()))
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val releaseFirst = CompletableDeferred<Unit>()
            var reads = 0
            val owner = ComparisonAlertSettingsOwner({ use, guard ->
                val response = runBlocking { read(use, guard) }
                if (++reads == 1) withContext(NonCancellable) { releaseFirst.await() }
                response
            }, access, { a1 }, premium, revisions, scope, { delay(it) }, { 0.0 },
                comparisonCreate(repository), comparisonUpdate(repository), comparisonDelete(repository))
            val source = OwnerComparisonSectionSource(owner)
            val mount = ComparisonSectionMount({ true }, {})
            try {
                owner.onForeground(false)
                mount.runShown(source::ensure)
                runCurrent()
                assertEquals(1, reads)
                snapshot = snapshot.copy(userInvalidations = snapshot.userInvalidations + 1,
                    facts = snapshot.facts.copy(userBlocks = setOf(TopicAccessBlock.PERSISTENCE_HOLD)))
                revisions.value++; runCurrent()
                releaseFirst.complete(Unit); runCurrent()
                snapshot = snapshot.copy(facts = snapshot.facts.copy(userBlocks = emptySet()))
                revisions.value++; runCurrent()
                assertEquals(ComparisonAlertSettingsState(), source.state.value)
                assertEquals(1, reads)
                server.enqueue(MockResponse().setResponseCode(403))
                mount.runShown(source::authoritative); runCurrent()
                assertEquals(ComparisonSectionBody.Error("접근 권한이 없습니다."), source.state.value.body(a1, "usd", "absolute"))
                server.enqueue(MockResponse().setBody("""{"alerts":${json.encodeToString(rows)},"total_count":2}"""))
                mount.runShown(source::authoritative); runCurrent()
                assertEquals(rows, source.state.value.adopted?.rows)
                mount.dispose()
                mount.runShown(source::authoritative)
                ComparisonSectionMount({ true }, {}).runShown(source::ensure)
                runCurrent()
                assertTrue(tagged.get())
                assertEquals(3, server.requestCount)
                repeat(3) {
                    val request = server.takeRequest()
                    assertEquals("GET", request.method)
                    assertEquals("/api/comparison-alerts", request.path)
                }
            } finally {
                releaseFirst.complete(Unit)
                scope.cancel()
                runCurrent()
            }
        }
    }
}
