package com.jay.fxi.service

import com.jay.fxi.admission.ReleaseAdmission
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.seconds
import org.junit.Test

/** Copy unchanged to clean0302. Only HEAD routing, bankAction, bus and admission APIs are called. */
@OptIn(ExperimentalCoroutinesApi::class)
class S6PendingTap0302HeadProbe {
    @Test fun writeTapTraceOnlyWhenRequested() {
        val destination = System.getProperty("s63c.o04.tapTraceOut") ?: return
        try {
            val path = Paths.get(destination)
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.write(path, trace().toString().toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (failure: Throwable) { throw AssertionError("contract(O04t/generate): HEAD capture failed", failure) }
    }
    companion object {
        const val HEAD = "0302b582e5aa22ca1223bee229a5c9d44947ef22"
        internal fun trace(busFactory: () -> AlertEventBus = { AlertEventBus() }): JsonObject {
            val routes = mutableListOf<JsonElement>()
            for (type in listOf("rate_alert", "source_rate_alert", "comparison_alert", "sync_alerts", "unknown", "", null)) {
                for (id in listOf("42", "-1", "0", "2147483647", "2147483648", null, "", "x", "4.2")) {
                    val route = AlertPushRouting.route(type, id)
                    routes += buildJsonObject {
                        put("type", type?.let(::JsonPrimitive) ?: JsonNull)
                        put("id", id?.let(::JsonPrimitive) ?: JsonNull)
                        put("event", event(route?.event)); put("banner", route?.showBanner?.let(::JsonPrimitive) ?: JsonNull)
                        put("action", action(route?.event))
                    }
                }
            }
            val phases = mutableListOf<JsonElement>()
            try {
                runTest(timeout = 30.seconds) {
                    val bus = busFactory()
                    phases += buildJsonObject { put("phase", "subscriber0"); put("emit", bus.emit(AlertEvent.SettingTriggered(7))) }
                    val delivered = mutableListOf<AlertEvent>()
                    val gate = CompletableDeferred<Unit>()
                    val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        bus.events.collect { value -> gate.await(); delivered += value }
                    }
                    runCurrent()
                    val accepted = (0..31).map { bus.emit(AlertEvent.SettingTriggered(if (it < 2) 77 else it)) }
                    phases += buildJsonObject {
                        put("phase", "active-FULL"); put("emit", JsonArray(accepted.map(::JsonPrimitive)))
                        put("before_release", JsonArray(delivered.map(::event)))
                    }
                    gate.complete(Unit); runCurrent()
                    phases += buildJsonObject { put("phase", "drain"); put("events", JsonArray(delivered.map(::event))) }
                    collector.cancel(); runCurrent()
                    phases += buildJsonObject { put("phase", "detached"); put("emit", bus.emit(AlertEvent.RefreshNeeded)) }
                    val after = mutableListOf<AlertEvent>()
                    val second = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { bus.events.collect { after += it } }
                    runCurrent()
                    val emits = listOf(AlertEvent.SettingTriggered(9), AlertEvent.SettingTriggered(9), AlertEvent.RefreshNeeded).map(bus::emit)
                    runCurrent()
                    phases += buildJsonObject {
                        put("phase", "reregister"); put("emit", JsonArray(emits.map(::JsonPrimitive)))
                        put("events", JsonArray(after.map(::event)))
                    }
                    second.cancel()
                }
            } catch (failure: Throwable) { throw AssertionError("contract(O04t/probe): bounded public HEAD bus execution failed", failure) }
            return buildJsonObject {
                put("schema", 1); put("head", HEAD)
                put("d24_profile", if (ReleaseAdmission.isOpen) "admitted" else "closed")
                put("routes", JsonArray(routes)); put("bus", JsonArray(phases))
            }
        }
        private fun event(e: AlertEvent?): JsonElement = when (e) {
            null -> JsonNull
            is AlertEvent.SettingTriggered -> buildJsonObject { put("kind", "setting"); put("family", e.family.name); put("id", e.settingId) }
            is AlertEvent.FamilyRefreshNeeded -> buildJsonObject { put("kind", "family-refresh"); put("family", e.family.name) }
            AlertEvent.RefreshNeeded -> buildJsonObject { put("kind", "refresh") }
            else -> throw AssertionError("contract(O04t/probe): HEAD trace unexpectedly received a non-HEAD event")
        }
        private fun action(e: AlertEvent?): JsonElement = when (val a = e?.let(AlertPushRouting::bankAction)) {
            null -> JsonNull
            is BankAlertAction.Trigger -> buildJsonObject { put("kind", "trigger"); put("id", a.settingId) }
            BankAlertAction.Refresh -> buildJsonObject { put("kind", "refresh") }
        }
    }
}
