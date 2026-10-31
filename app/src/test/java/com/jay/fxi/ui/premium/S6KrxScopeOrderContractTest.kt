package com.jay.fxi.ui.premium

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.remote.*
import com.jay.fxi.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class S6KrxScopeOrderContractTest {
    @Test fun d12_bannerScopeRequiresDesired() {
        val cases = FreeTab.entries.flatMap { t -> listOf(false,true).flatMap { d -> listOf("degraded","disabled","entitlement").map { "$t/desired=$d/$it" } } }
        for (sub in s64Cases("D12",cases)) runTest {
            val (name,desiredText,kind) = sub.split('/'); val tab = FreeTab.valueOf(name); val desired = desiredText == "desired=true"
            val k = TopicCatalogue.KRX_FUTURES; val owner = TopicDisplayOwner(AuthIdentityFence("A",1),1)
            val state = when (kind) {
                "degraded" -> TopicSubscriptionState(desired,deliveryState = TopicDeliveryState.DEGRADED)
                "disabled" -> TopicSubscriptionState(desired,rejection = TopicRejectionReason.TOPICS_DISABLED)
                else -> TopicSubscriptionState(desired,rejection = TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED)
            }
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val snapshot = TopicSubscriptionSnapshot(mapOf(k to state),controlState = TopicControlState.ACKNOWLEDGED)
            val display = MutableStateFlow(TopicDisplayState(owner=owner,rates=TopicRates(),containsSeed=false,connection=TopicConnectionDisplay.OPEN,topicState=snapshot))
            val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(owner.identity,tab)); val retries = mutableListOf<FreeTab>()
            val consumer = PremiumTopicConsumer(display,focus,{ owner.identity },object : RateRowPreferenceStore {
                override suspend fun preferences(uid: String) = emptyMap<RateRowList,RateRowPreference>()
                override suspend fun remember(uid: String,list: RateRowList,preference: RateRowPreference) = Unit
            },{ _,_ -> },{},{ _,t -> retries += t },scope)
            try {
                consumer.start(); runCurrent()
                assertEquals("precondition(D12/$sub): stale marker retained",state,display.value.topicState.stateFor(k))
                if (!desired) {
                    assertFalse("contract(D12): $sub/degraded getter excludes K",k in snapshot.degradedTopics)
                    assertFalse("contract(D12): $sub/manual getter excludes K",k in snapshot.manualRetryTopics)
                }
                val inScope = desired && tab in listOf(FreeTab.TETHER,FreeTab.USD)
                assertEquals("contract(D12): $sub/banner scoped by desired and tab",inScope && kind != "entitlement",consumer.currentState().banner is PremiumTopicScreenBanner.Topic)
                consumer.retryTopics(owner,tab)
                val retryable = state.rejection == TopicRejectionReason.TOPICS_DISABLED || state.rejection == null
                assertEquals("contract(D12): $sub/retry shown desired only",if (inScope && retryable) listOf(tab) else emptyList<FreeTab>(),retries)
                if (kind == "entitlement") {
                    assertEquals("contract(D12): $sub/entitlement-only Topic banner absent on every tab",false,consumer.currentState().banner is PremiumTopicScreenBanner.Topic)
                    assertEquals("contract(D12): $sub/entitlement-only manual retry zero",emptyList<FreeTab>(),retries)
                }
                if (!desired && tab in listOf(FreeTab.TETHER,FreeTab.USD)) {
                    val general = if (tab == FreeTab.USD) "fx:usd-krw" else TopicCatalogue.TETHER
                    display.value = display.value.copy(topicState=snapshot.copy(topics=snapshot.topics + (general to TopicSubscriptionState(desired=true,rejection=TopicRejectionReason.TOPIC_UNAVAILABLE))))
                    runCurrent()
                    assertEquals("contract(D12): $sub/stale K never enables GENERAL retry",PremiumTopicScreenBanner.Topic(TopicBannerReason.TOPIC_UNAVAILABLE,false),consumer.currentState().banner)
                }
            } finally { scope.cancel() }
        }
    }

    @Test fun d13_bannerAndBootstrapScopesStaySeparate() {
        val k = TopicCatalogue.KRX_FUTURES
        val g = setOf("usdt:krw","fx:usd-krw","fx:jpy-krw","fx:eur-krw","dxy:spot")
        val shown = mapOf(FreeTab.TETHER to listOf("usdt:krw","dxy:spot"),FreeTab.USD to listOf("fx:usd-krw","dxy:spot"),FreeTab.JPY to listOf("fx:jpy-krw"),FreeTab.EUR to listOf("fx:eur-krw"),FreeTab.NEWS to emptyList())
        val cases = FreeTab.entries.flatMap { t -> listOf("G","G+K","partial","empty","unclaimed","G+unclaimed+K").map { "$t/$it" } } + "all-tabs/pacing" + FreeTab.entries.flatMap { t -> listOf("runtime/$t/initial-visible","runtime/$t/flip") }
        for (sub in s64Cases("D13",cases)) {
            if (sub.startsWith("runtime/")) {
                val tab = FreeTab.valueOf(sub.split('/')[1]); val initial = sub.endsWith("initial-visible")
                s64Test("D13",sub,tab=tab,worker=false) { r ->
                    r.world.floor = 10_000; r.start(visible=initial)
                    if (!initial) { r.world.refresh(true); r.world.ackAll() }
                    r.eq("runtime global desired independent of focus",g+k,r.desired)
                    r.eq("runtime original obligation set six",g+k,r.observation.plan.toSet())
                    val rank = (shown.getValue(tab)+FreeTab.entries.flatMap { shown.getValue(it) }).distinct()
                    r.world.advance(13_000)
                    r.eq("runtime actual callback rank K last",rank+k,r.calls.map { it.topic })
                    r.eq("runtime one physical K exchange",1,r.krxCalls.sumOf { it.sends.size })
                    r.eq("runtime GENERAL fanout one each",g.associateWith { 1 },r.calls.filter { it.topic in g }.groupingBy { it.topic }.eachCount())
                    r.yes("runtime K outside first shown batch",r.krxCalls.single().at > r.calls.first().at)
                    val first = maxOf(1,shown.getValue(tab).size); val times = r.calls.map { it.at }
                    r.yes("runtime actual 500ms after shown batch",(first until times.size).all { times[it]-times[it-1] >= 500 })
                }
            } else if (sub == "all-tabs/pacing") {
                assertEquals("contract(D13): all-tabs/pacing/500ms",500L,TopicBootstrapOrder.ISSUE_GAP.inWholeMilliseconds)
            } else {
                val tab = FreeTab.valueOf(sub.substringBefore('/')); val label = sub.substringAfter('/'); val scope = shown.getValue(tab)
                val desired = when (label) { "G" -> g; "G+K" -> g+k; "partial" -> setOf("fx:usd-krw",k); "empty" -> emptySet(); "unclaimed" -> setOf("z:unclaimed","a:unclaimed",k); else -> g+setOf("z:unclaimed","a:unclaimed",k) }
                assertEquals("contract(D13): $sub/bootstrap HEAD scope",scope,TopicBootstrapOrder.bootstrapShownBy(tab))
                val f1 = if (tab in listOf(FreeTab.TETHER,FreeTab.USD)) listOf(scope.first(),k,"dxy:spot") else scope
                assertEquals("contract(D13): $sub/banner F1 scope",f1,TopicBootstrapOrder.shownBy(tab))
                val order = (scope + FreeTab.entries.flatMap { shown.getValue(it) }).distinct()
                val expected = order.filter { it in desired } + desired.filter { it !in g && it != k }.sorted() + if (k in desired) listOf(k) else emptyList()
                assertEquals("contract(D13): $sub/exact plan K last",expected,TopicBootstrapOrder.plan(tab,desired))
                assertEquals("contract(D13): $sub/no duplicate or omission",desired,TopicBootstrapOrder.plan(tab,desired).toSet())
            }
        }
    }
}
