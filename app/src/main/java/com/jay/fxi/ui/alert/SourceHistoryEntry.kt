package com.jay.fxi.ui.alert

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jay.fxi.data.alerts.AlertRateLimitEvidence
import com.jay.fxi.data.alerts.AlertRateLimitFloor
import com.jay.fxi.data.alerts.AlertSettingsAccess
import com.jay.fxi.data.alerts.AlertSettingsUse
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.remote.AuthenticatedEndpoint
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.data.repository.DeletionAdmissionRevisions
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.ui.premium.PremiumTopicScreenState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** S8-4b: the premium tether tab's history reads the process's one access, floor and repository; it writes nothing. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SourceHistoryEntryPoint {
    fun alertSettingsAccess(): AlertSettingsAccess
    fun alertRateLimitFloor(): AlertRateLimitFloor
    fun alertRepository(): AlertRepository
    fun premiumAccessCoordinator(): PremiumAccessCoordinator
    fun deletionAdmissionRevisions(): DeletionAdmissionRevisions
}

internal class SourceHistoryWiring(
    val access: SourceHistoryAccess,
    val read: SourceHistoryRead,
    val floor: SourceHistoryFloor,
    val accessRevisions: StateFlow<Long>,
    val deletionRevisions: StateFlow<Long>
)

internal fun interface SourceHistoryWiringFactory {
    fun create(context: Context, displayOwner: () -> TopicDisplayOwner?): SourceHistoryWiring
}

/** Resolved by the entry's first click of a session, using the process's existing singletons. */
internal object ProductionSourceHistoryWiring : SourceHistoryWiringFactory {
    override fun create(context: Context, displayOwner: () -> TopicDisplayOwner?): SourceHistoryWiring {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, SourceHistoryEntryPoint::class.java)
        val floor = entry.alertRateLimitFloor()
        return SourceHistoryWiring(
            access = SettingsSourceHistoryAccess(entry.alertSettingsAccess(), displayOwner),
            read = productionSourceHistoryRead(entry.alertRepository()),
            floor = object : SourceHistoryFloor {
                override fun remainingMillis() = floor.remainingMillis(AuthenticatedEndpoint.SOURCE_NOTIFICATION_LOGS)
                override fun record(evidence: AlertRateLimitEvidence) = floor.record(AuthenticatedEndpoint.SOURCE_NOTIFICATION_LOGS, evidence)
            },
            accessRevisions = entry.premiumAccessCoordinator().accessRevisions,
            deletionRevisions = entry.deletionAdmissionRevisions().revisions
        )
    }
}

internal const val SOURCE_HISTORY_LIMIT = 100

internal fun productionSourceHistoryRead(repository: AlertRepository): SourceHistoryRead =
    SourceHistoryRead { identity, asset, guard -> repository.getSourceHistory(identity, asset, SOURCE_HISTORY_LIMIT, guard) }

/** The process access, for the displayed owner's identity only; the factory's display owner is a screen check, not a grant. */
internal class SettingsSourceHistoryAccess(
    private val base: AlertSettingsAccess,
    private val displayOwner: () -> TopicDisplayOwner?
) : SourceHistoryAccess {
    override fun acquire(owner: TopicDisplayOwner): AlertSettingsUse? =
        if (displayOwner() == owner) base.acquire()?.takeIf { it.identity == owner.identity } else null

    override fun admits(owner: TopicDisplayOwner, use: AlertSettingsUse): Boolean =
        displayOwner() == owner && use.identity == owner.identity && base.admits(use)
}

/** Fresh screen reads are an additional guard: the shown owner and the tether tab. */
private class ShownSourceHistoryAccess(
    private val base: SourceHistoryAccess,
    private val shown: () -> Pair<TopicDisplayOwner?, FreeTab?>
) : SourceHistoryAccess {
    override fun acquire(owner: TopicDisplayOwner) = if (isShown(owner)) base.acquire(owner) else null
    override fun admits(owner: TopicDisplayOwner, use: AlertSettingsUse) = isShown(owner) && base.admits(owner, use)
    private fun isShown(owner: TopicDisplayOwner) = shown().let { (o, t) -> o == owner && t == FreeTab.TETHER }
}

/**
 * Main-owned, made by a user's click, for one session. A screen publication that leaves this owner's tether tab, a dismissal,
 * or a lost access ends its holder, and the holder's end releases this controller's listener and revision collection.
 */
internal class SourceHistoryController(
    private val owner: TopicDisplayOwner,
    wiring: SourceHistoryWiring,
    shown: () -> Pair<TopicDisplayOwner?, FreeTab?>,
    listen: ((PremiumTopicScreenState) -> Unit) -> () -> Unit,
    scope: CoroutineScope
) {
    private var released = false
    private var stopListening: (() -> Unit)? = null
    private var rechecks: Job? = null
    val holder = SourceHistoryStateHolder(ShownSourceHistoryAccess(wiring.access, shown), wiring.read, wiring.floor, scope,
        onRetired = ::release)

    init {
        // The listener is called at once with the published screen; a screen already left ends the holder during registration.
        val stop = listen { screen ->
            if (screen.ui.owner != owner || screen.ui.selectedTab != FreeTab.TETHER) holder.close()
        }
        stopListening = stop
        if (released) {
            stop()
        } else {
            rechecks = scope.launch {
                merge(wiring.accessRevisions, wiring.deletionRevisions).collect { holder.currentState() }
            }
        }
    }

    /** Whether this controller can still open; once released, a new click makes a new controller. */
    val live: Boolean get() = !released

    fun open() {
        if (!released) holder.open(owner)
    }

    fun close() = holder.close()

    private fun release() {
        if (released) return
        released = true
        stopListening?.invoke()
        rechecks?.cancel()
    }
}

internal object SourceHistoryTags {
    const val ENTRY = "source-history-entry"
    const val SHEET = "source-history-sheet"
    const val ROW = "source-history-row"
}

@Composable
internal fun SourceHistoryEntry(
    tab: FreeTab,
    owner: TopicDisplayOwner,
    shown: () -> Pair<TopicDisplayOwner?, FreeTab?>,
    listen: ((PremiumTopicScreenState) -> Unit) -> () -> Unit,
    wiring: () -> SourceHistoryWiring
) {
    if (tab != FreeTab.TETHER) return
    val scope = rememberCoroutineScope()
    var controller by remember(owner, tab) { mutableStateOf<SourceHistoryController?>(null) }
    DisposableEffect(owner, tab) {
        onDispose { controller?.close() }
    }

    Row(
        modifier = Modifier.fillMaxWidth().testTag(SourceHistoryTags.ENTRY)
            .clickable(role = Role.Button) {
                val current = controller?.takeIf { it.live } ?: SourceHistoryController(owner, wiring(), shown, listen, scope).also {
                    controller = it
                }
                current.open()
            }.padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.History, contentDescription = null)
        Text("알림 히스토리", style = MaterialTheme.typography.bodyMedium)
    }

    controller?.let { current ->
        val observed by current.holder.state.collectAsState()
        observed.let { current.holder.currentState() }?.let { sheet ->
            SourceHistorySheet(
                sheet = sheet,
                onRetry = current.holder::retry,
                onRefresh = current.holder::refresh,
                onDismiss = {
                    current.close()
                    controller = null
                }
            )
        }
    }
}
