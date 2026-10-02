package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.local.FreeTabStore
import com.jay.fxi.domain.model.FreeTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A tab the focus provider accepted for [identity] (R4-c C3a): restored or chosen — not the session having handled it. */
internal data class OwnedTopicFocus(val identity: AuthIdentityFence, val tab: FreeTab)

/**
 * L-4f: tells the topic session which tab is shown. On each identity it restores that UID's last tab and hands it to
 * [setFocus] — 달러 when nothing is stored, the value is unknown, or the read fails — and never before the restore settles.
 * A later confirmed choice ([onTabSelected]) wins over a restore still in flight and is remembered for the UID. A restore or a
 * choice for an identity that is no longer the live one is dropped, as is a restore that was cancelled.
 *
 * The runtime constructs this provider; its Main consumer forwards the subscriber surface's confirmed tab choices.
 */
internal class TopicFocusProvider(
    private val scope: CoroutineScope,
    private val fences: AuthFenceStream,
    private val liveFence: () -> AuthIdentityFence?,
    private val tabs: FreeTabStore,
    private val setFocus: (AuthIdentityFence, FreeTab) -> Unit
) {
    private sealed interface Event {
        data class Fence(val owner: AuthIdentityFence?) : Event
        data class Restored(val owner: AuthIdentityFence, val tab: FreeTab) : Event
        data class Selected(val owner: AuthIdentityFence, val tab: FreeTab) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val writes = Channel<Pair<String, FreeTab>>(Channel.UNLIMITED)
    private var started = false

    private val _focus = MutableStateFlow<OwnedTopicFocus?>(null)

    /** The latest accepted tab, or null before the live identity's restore settles. A state, not a log of every choice. */
    val focus: StateFlow<OwnedTopicFocus?> = _focus.asStateFlow()

    @Synchronized
    fun start() {
        if (started) return
        started = true

        scope.launch {
            var active: AuthIdentityFence? = null
            var selected = false
            for (event in events) {
                when (event) {
                    is Event.Fence -> {
                        if (event.owner != liveFence() || event.owner == active) continue
                        active = event.owner
                        selected = false
                        _focus.value = null
                        val owner = event.owner ?: continue
                        scope.launch restore@{
                            val tab = try {
                                tabs.lastTab(owner.uid)
                            } catch (cancelled: CancellationException) {
                                return@restore
                            } catch (_: Exception) {
                                FreeTab.USD
                            }
                            events.send(Event.Restored(owner, tab))
                        }
                    }
                    is Event.Restored -> {
                        if (event.owner == active && !selected && liveFence() == event.owner) {
                            setFocus(event.owner, event.tab)
                            _focus.value = OwnedTopicFocus(event.owner, event.tab)
                        }
                    }
                    is Event.Selected -> {
                        if (liveFence() != event.owner) continue
                        active = event.owner
                        selected = true
                        setFocus(event.owner, event.tab)
                        _focus.value = OwnedTopicFocus(event.owner, event.tab)
                        writes.send(event.owner.uid to event.tab)
                    }
                }
            }
        }

        scope.launch {
            for ((uid, tab) in writes) {
                try {
                    tabs.remember(uid, tab)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // The focus has already changed; a failed save cannot undo that choice.
                }
            }
        }

        // The auth callback only enqueues. Reading live identity can itself deliver callbacks.
        fences.observe { owner -> events.trySend(Event.Fence(owner)) }
    }

    fun onTabSelected(owner: AuthIdentityFence, tab: FreeTab) {
        events.trySend(Event.Selected(owner, tab))
    }
}
