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
import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicRuntimeOwner
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.data.repository.BankHistoryAccess
import com.jay.fxi.data.repository.BankHistoryUse
import com.jay.fxi.data.repository.DeletionAdmissionRevisions
import com.jay.fxi.data.repository.IssuerBankHistoryAccess
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.ui.premium.PremiumTopicScreenState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface BankHistoryEntryPoint {
    fun topicRuntimeOwner(): TopicRuntimeOwner
    fun authTokenProvider(): AuthTokenProvider
    fun topicUseAuthority(): TopicUseAuthority
    fun deletionAdmissionStore(): DeletionAdmissionStore
    fun alertRepository(): AlertRepository
    fun premiumAccessCoordinator(): PremiumAccessCoordinator
    fun deletionAdmissionRevisions(): DeletionAdmissionRevisions
}

internal class BankHistoryWiring(
    val access: BankHistoryAccess,
    val read: BankHistoryRead,
    val accessRevisions: StateFlow<Long>,
    val deletionRevisions: StateFlow<Long>
)

internal fun interface BankHistoryWiringFactory {
    fun create(context: Context, displayOwner: () -> TopicDisplayOwner?): BankHistoryWiring
}

/** Resolved by the entry's first click, using the process's existing shared providers. */
internal object ProductionBankHistoryWiring : BankHistoryWiringFactory {
    override fun create(context: Context, displayOwner: () -> TopicDisplayOwner?): BankHistoryWiring {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, BankHistoryEntryPoint::class.java)
        val owner = entry.topicRuntimeOwner()
        val tokens = entry.authTokenProvider()
        val deletions = entry.deletionAdmissionStore()
        return BankHistoryWiring(
            access = IssuerBankHistoryAccess(
                tokens::currentIdentityFence,
                owner.currentAccessFence,
                displayOwner,
                entry.topicUseAuthority(),
                { ReleaseAdmission.isOpen },
                GraphProtectedAdmission(tokens::currentIdentityFence, deletions)
            ),
            read = productionBankHistoryRead(entry.alertRepository()),
            accessRevisions = entry.premiumAccessCoordinator().accessRevisions,
            deletionRevisions = entry.deletionAdmissionRevisions().revisions
        )
    }
}

internal const val BANK_HISTORY_LIMIT = 100

internal fun productionBankHistoryRead(repository: AlertRepository): BankHistoryRead =
    BankHistoryRead { owner, currency, guard -> repository.getHistory(owner, currency, BANK_HISTORY_LIMIT, guard) }

/** Fresh screen reads are an additional transport guard; publication listening below owns the session's termination. */
private class ShownSessionAccess(
    private val base: BankHistoryAccess,
    private val shown: () -> Pair<TopicDisplayOwner?, FreeTab?>,
    private val tab: FreeTab
) : BankHistoryAccess {
    override fun acquire(owner: TopicDisplayOwner) = if (isShown(owner)) base.acquire(owner) else null
    override fun admits(use: BankHistoryUse) = isShown(use.owner) && base.admits(use)
    private fun isShown(owner: TopicDisplayOwner) = shown().let { (o, t) -> o == owner && t == tab }
}

/** Main-owned, created only by a user entry. Returning to a screen never reopens its closed holder. */
internal class BankHistoryController(
    private val owner: TopicDisplayOwner,
    private val tab: FreeTab,
    wiring: BankHistoryWiring,
    shown: () -> Pair<TopicDisplayOwner?, FreeTab?>,
    listen: ((PremiumTopicScreenState) -> Unit) -> () -> Unit,
    scope: CoroutineScope
) {
    val holder = BankHistoryStateHolder(ShownSessionAccess(wiring.access, shown, tab), wiring.read, scope)
    private val stopListening = listen { screen ->
        if (screen.ui.owner != owner || screen.ui.selectedTab != tab) holder.close()
    }
    private val rechecks = scope.launch {
        merge(wiring.accessRevisions, wiring.deletionRevisions).collect { holder.currentState() }
    }

    fun open() {
        bankHistoryCurrency(tab)?.let { holder.open(owner, it) }
    }

    fun close() {
        holder.close()
        rechecks.cancel()
        stopListening()
    }
}

internal object BankHistoryTags {
    const val ENTRY = "bank-history-entry"
}

private fun bankHistoryCurrency(tab: FreeTab): String? = when (tab) {
    FreeTab.USD -> "usd-krw"
    FreeTab.JPY -> "jpy-krw"
    FreeTab.EUR -> "eur-krw"
    else -> null
}

@Composable
internal fun BankHistoryEntry(
    tab: FreeTab,
    owner: TopicDisplayOwner,
    shown: () -> Pair<TopicDisplayOwner?, FreeTab?>,
    listen: ((PremiumTopicScreenState) -> Unit) -> () -> Unit,
    wiring: () -> BankHistoryWiring
) {
    if (bankHistoryCurrency(tab) == null) return
    val scope = rememberCoroutineScope()
    var controller by remember(owner, tab) { mutableStateOf<BankHistoryController?>(null) }
    DisposableEffect(owner, tab) {
        onDispose { controller?.close() }
    }

    Row(
        modifier = Modifier.fillMaxWidth().testTag(BankHistoryTags.ENTRY)
            .clickable(role = Role.Button) {
                val current = controller ?: BankHistoryController(owner, tab, wiring(), shown, listen, scope).also {
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
            BankHistorySheet(
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
