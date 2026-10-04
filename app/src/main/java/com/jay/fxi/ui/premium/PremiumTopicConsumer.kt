package com.jay.fxi.ui.premium

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicBootstrapOrder
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicRecoveryDisplay
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.TopicAuthResolution
import com.jay.fxi.domain.model.TopicReconnectPolicy
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.domain.model.TopicSubscriptionSnapshot
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The topic status reasons (R4-c C2+b-3), worded as iOS words them. */
internal enum class TopicBannerReason(val text: String) {
    AUTH_FAILED("로그인 상태를 다시 확인해 주세요"),
    TOPICS_DISABLED("실시간 시세를 일시적으로 제공할 수 없습니다"),
    TOPIC_UNAVAILABLE("일부 실시간 시세를 사용할 수 없습니다"),
    DELIVERY_DELAYED("실시간 시세 수신이 지연되고 있습니다")
}

/** The one status line the premium screen draws, worded as iOS words it; [action] is the button, when there is one. */
internal sealed class PremiumTopicScreenBanner(val text: String, val action: String? = null) {
    data object Offline : PremiumTopicScreenBanner("오프라인 모드")
    data object RefreshingCached : PremiumTopicScreenBanner("저장된 환율 · 최신 데이터 확인 중")
    data object Connecting : PremiumTopicScreenBanner("연결 중...")
    /** iOS keeps the first retry quiet: it reads as connecting. */
    data class Reconnecting(val attempt: Int) : PremiumTopicScreenBanner(
        if (attempt == 1) "연결 중..." else "재연결 중 ($attempt/${TopicReconnectPolicy.MAX_ATTEMPTS})"
    )
    data object Failed : PremiumTopicScreenBanner("연결할 수 없습니다", "재연결")
    data class Topic(val reason: TopicBannerReason, val canRetry: Boolean) : PremiumTopicScreenBanner(
        reason.text, if (canRetry) "다시 시도" else null
    )
}

/** What the premium screen draws: the presenter's model, the final status line and its time text (KST HH:mm). */
internal data class PremiumTopicScreenState(
    val ui: PremiumTopicUiState,
    val banner: PremiumTopicScreenBanner?,
    val updatedText: String?
) {
    companion object {
        val NONE = PremiumTopicScreenState(PremiumTopicUiState.NONE, null, null)
    }
}

/**
 * R4-c C3c-1: joins the topic runtime's display and accepted focus with the live identity and the user's row preferences, and
 * turns user actions into runtime commands only for the owner they were taken under. No Compose, Android or runtime lifecycle.
 * All public calls (including identity-change notifications) and [scope] coroutines must run on the same Main thread.
 * [onIdentityChanged] must be called on every sign-in change; [liveIdentity] is read fresh at each decision.
 */
internal class PremiumTopicConsumer(
    private val display: StateFlow<TopicDisplayState>,
    private val focus: StateFlow<OwnedTopicFocus?>,
    private val liveIdentity: () -> AuthIdentityFence?,
    private val rowPreferenceStore: RateRowPreferenceStore,
    private val selectTab: (AuthIdentityFence, FreeTab) -> Unit,
    private val retryConnection: (TopicDisplayOwner) -> Unit,
    private val retryTopics: (TopicDisplayOwner, FreeTab) -> Unit,
    private val scope: CoroutineScope
) {
    private val mutableState = MutableStateFlow(PremiumTopicScreenState.NONE)
    val state: StateFlow<PremiumTopicScreenState> = mutableState.asStateFlow()

    private var started = false
    private var preferences: PreferenceState = Unbound
    private var generation = 0L
    private var restoreJob: Job? = null
    private var selectionOwner: TopicDisplayOwner? = null
    private var lastTarget: FreeTab? = null
    private val pendingWrites = ArrayDeque<PreferenceWrite>()
    private val writeSignal = Channel<Unit>(Channel.CONFLATED)

    /** The state for the current inputs, read just before drawing. */
    fun currentState(): PremiumTopicScreenState = refresh()

    /** Idempotent. Construction and collecting [state] alone do not start any work. */
    fun start() {
        // A second start would create another writer. On an ended scope the launches below run nothing.
        if (started) return
        started = true
        scope.launch { writePreferences() }
        scope.launch { display.collect { currentState() } }
        scope.launch { focus.collect { currentState() } }
        refresh()
    }

    fun onIdentityChanged() {
        refresh()
    }

    fun onUserTabSelected(owner: TopicDisplayOwner, tab: FreeTab) {
        val screen = refresh()
        // An event captured under an old owner/grant must not select for the current screen.
        if (screen.ui.owner != owner) return
        // Repeated clicks can arrive before focus acknowledges the last requested tab.
        if (tab == (lastTarget ?: screen.ui.selectedTab)) return
        // Record before forwarding: a delayed focus response must not overwrite this target.
        lastTarget = tab
        selectTab(owner.identity, tab)
    }

    fun retryConnection(owner: TopicDisplayOwner) {
        val screen = refresh()
        // Reject an old owner's click or a click after failure ended; Failed already excludes offline and requires Exhausted.
        if (screen.ui.owner == owner && screen.banner == PremiumTopicScreenBanner.Failed) {
            retryConnection.invoke(owner)
        }
    }

    fun retryTopics(owner: TopicDisplayOwner, tab: FreeTab) {
        val screen = refresh()
        val banner = screen.banner as? PremiumTopicScreenBanner.Topic ?: return
        // Recheck the accepted tab and current retry eligibility, including any higher-priority status line.
        if (screen.ui.owner == owner && screen.ui.selectedTab == tab && banner.canRetry) {
            retryTopics.invoke(owner, tab)
        }
    }

    fun applyRowPreference(
        owner: TopicDisplayOwner,
        tab: FreeTab,
        list: RateRowList,
        seeded: List<String>,
        order: List<String>,
        hidden: Set<String>
    ) {
        val screen = refresh()
        // A sheet can answer after its owner/grant or accepted tab changed.
        if (screen.ui.owner != owner || screen.ui.selectedTab != tab) return
        val bound = preferences as Bound // refresh bound this screen's identity.
        val applied = PreferenceApplication(list, seeded.toList(), order.toList(), hidden.toSet())
        val next = applied.over(bound.values[list])
        val values = bound.values + (list to next)
        when (bound) {
            // An edit during the read must be shown now but saved only after replay over the restored map.
            is Restoring -> preferences = bound.copy(values = values, applied = bound.applied + applied)
            is Ready -> {
                preferences = bound.copy(values = values)
                enqueue(PreferenceWrite(bound.fence, bound.generation, list, next))
            }
        }
        refresh()
    }

    private fun bindPreferences(identity: AuthIdentityFence?) {
        // Display/focus refreshes and grant changes under the same sign-in must not restart its restore.
        if ((preferences as? Bound)?.fence == identity) return
        // A sign-in change can leave the old store read suspended; cancel it and discard its unapplied edits.
        restoreJob?.cancel()
        restoreJob = null
        preferences = Unbound
        generation += 1
        // Signing out must not read preferences for a missing identity.
        if (identity == null) return

        val restoring = Restoring(identity, generation, emptyMap(), emptyList())
        preferences = restoring
        // Install the job before starting, also when the injected scope uses an immediate dispatcher.
        restoreJob = scope.launch(start = CoroutineStart.LAZY) {
            val restored = try {
                rowPreferenceStore.preferences(identity.uid)
            } catch (_: IOException) {
                // An unreadable file safely restores defaults; accepted edits are still replayed below.
                emptyMap()
            }
            // A store may ignore cancellation; reject its result after rebind or scope cancellation.
            coroutineContext.ensureActive()
            val current = preferences as Restoring
            var values = restored.mapValues { (_, value) ->
                value.copy(order = value.order?.toList(), hidden = value.hidden?.toSet())
            }
            val writes = current.applied.map { applied ->
                val next = applied.over(values[applied.list])
                values = values + (applied.list to next)
                PreferenceWrite(identity, current.generation, applied.list, next)
            }
            preferences = Ready(identity, current.generation, values)
            restoreJob = null
            // Queue the whole replay before waking even an immediate writer, preserving acceptance order.
            pendingWrites.addAll(writes)
            // Replayed edits otherwise have no new user event to wake the writer after restore.
            if (writes.isNotEmpty()) writeSignal.trySend(Unit)
            refresh()
        }
        restoreJob?.start()
    }

    private fun refresh(): PremiumTopicScreenState {
        // currentState or an input may arrive before start; neither is allowed to initiate a restore.
        if (!started) {
            selectionOwner = null
            lastTarget = null
            return PremiumTopicScreenState.NONE
        }
        val identity = liveIdentity()
        bindPreferences(identity)
        val currentDisplay = display.value
        val currentFocus = focus.value
        val values = (preferences as? Bound)?.values.orEmpty() // bindPreferences established the identity.
        val ui = PremiumTopicPresenter.present(currentDisplay, currentFocus, identity, values)
        // Missing or mismatched display/focus/identity must not produce a banner or time without an allowed screen.
        val screen = if (ui.owner == null) PremiumTopicScreenState.NONE else {
            val banner = when {
                currentDisplay.connection == TopicConnectionDisplay.OFFLINE -> PremiumTopicScreenBanner.Offline
                currentDisplay.recovery == TopicRecoveryDisplay.Exhausted -> PremiumTopicScreenBanner.Failed
                ui.statusBanner == PremiumTopicBanner.REFRESHING_CACHED -> PremiumTopicScreenBanner.RefreshingCached
                currentDisplay.recovery == TopicRecoveryDisplay.Connecting -> PremiumTopicScreenBanner.Connecting
                currentDisplay.recovery is TopicRecoveryDisplay.Reconnecting ->
                    PremiumTopicScreenBanner.Reconnecting(currentDisplay.recovery.attempt)
                currentDisplay.connection == TopicConnectionDisplay.OPEN ->
                    ui.selectedTab?.let { topicBanner(currentDisplay.topicState, it) }
                else -> null
            }
            val time = ui.lastUpdated?.toLocalDateTime(KST)?.let {
                "${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}"
            }
            val updated = when (banner) {
                PremiumTopicScreenBanner.Offline -> time?.let { "마지막 업데이트: $it" }
                PremiumTopicScreenBanner.RefreshingCached -> time
                else -> null
            }
            PremiumTopicScreenState(ui, banner, updated)
        }
        // Cancellation can occur during projection; serve NONE without publishing a late result or retaining a target.
        if (!scope.isActive) {
            selectionOwner = null
            lastTarget = null
            return PremiumTopicScreenState.NONE
        }
        // Runtime display/focus can change during projection on another dispatcher.
        val currentScreen = if (ui.forCurrent(display.value, focus.value, liveIdentity()).owner == null) {
            PremiumTopicScreenState.NONE
        } else screen
        // A new owner/grant or disallowed screen must not inherit the old owner's last target.
        if (selectionOwner != currentScreen.ui.owner) {
            selectionOwner = currentScreen.ui.owner
            lastTarget = null
        }
        mutableState.value = currentScreen
        return currentScreen
    }

    /** Projects only the accepted tab's canonical topics; row visibility does not narrow its scope. */
    private fun topicBanner(snapshot: TopicSubscriptionSnapshot, tab: FreeTab): PremiumTopicScreenBanner.Topic? {
        val topics = TopicBootstrapOrder.shownBy(tab)
        val desired = topics.map(snapshot::stateFor).filter { it.desired }
        val degraded = snapshot.degradedTopics
        val reason = when {
            snapshot.authResolution == TopicAuthResolution.FAILED && desired.isNotEmpty() -> TopicBannerReason.AUTH_FAILED
            desired.any { it.rejection == TopicRejectionReason.TOPICS_DISABLED } -> TopicBannerReason.TOPICS_DISABLED
            desired.any { it.rejection == TopicRejectionReason.TOPIC_UNAVAILABLE } -> TopicBannerReason.TOPIC_UNAVAILABLE
            topics.any { it in degraded } -> TopicBannerReason.DELIVERY_DELAYED
            else -> return null
        }
        val retryable = snapshot.manualRetryTopics
        return PremiumTopicScreenBanner.Topic(reason, canRetry = topics.any { it in retryable })
    }

    private fun enqueue(write: PreferenceWrite) {
        pendingWrites.addLast(write)
        writeSignal.trySend(Unit)
    }

    private suspend fun writePreferences() {
        for (signal in writeSignal) {
            while (true) {
                val write = pendingWrites.removeFirstOrNull() ?: break
                // A preceding save may ignore cancellation; stop before calling the store again.
                coroutineContext.ensureActive()
                val bound = preferences as? Ready
                val current = liveIdentity() == write.fence && bound != null &&
                    bound.fence == write.fence && bound.generation == write.generation
                // A queued edit can outlive its sign-in/restore while an earlier save suspends; discard it here only.
                if (!current) continue
                try {
                    // Await each save before taking the next: a suspended store must never overlap another save.
                    rowPreferenceStore.remember(write.fence.uid, write.list, write.preference)
                } catch (_: IOException) {
                    // Persistence failure does not undo accepted intent or block the next queued save.
                }
            }
        }
    }

    private sealed interface PreferenceState
    private data object Unbound : PreferenceState
    private sealed interface Bound : PreferenceState {
        val fence: AuthIdentityFence
        val generation: Long
        val values: Map<RateRowList, RateRowPreference>
    }
    private data class Restoring(
        override val fence: AuthIdentityFence,
        override val generation: Long,
        override val values: Map<RateRowList, RateRowPreference>,
        val applied: List<PreferenceApplication>
    ) : Bound
    private data class Ready(
        override val fence: AuthIdentityFence,
        override val generation: Long,
        override val values: Map<RateRowList, RateRowPreference>
    ) : Bound
    private data class PreferenceApplication(
        val list: RateRowList,
        val seeded: List<String>,
        val order: List<String>,
        val hidden: Set<String>
    ) {
        fun over(stored: RateRowPreference?): RateRowPreference =
            PremiumTopicPresenter.nextRowPreference(list, stored, seeded, order, hidden)
    }
    private data class PreferenceWrite(
        val fence: AuthIdentityFence,
        val generation: Long,
        val list: RateRowList,
        val preference: RateRowPreference
    )

    private companion object {
        val KST = TimeZone.of("Asia/Seoul")
    }
}
