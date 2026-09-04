package com.jay.fxi.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jay.fxi.di.StorageJson
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.BankDisplayConfig
import com.jay.fxi.domain.model.BankPreferenceItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.bankPreferenceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fxi_bank_preferences"
)

@Singleton
class BankPreferenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @StorageJson private val json: Json
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val orderedBanksKey = stringPreferencesKey("ordered_banks")

    private val _orderedBanks = MutableStateFlow(defaultOrder)
    val orderedBanks: StateFlow<List<BankPreferenceItem>> = _orderedBanks.asStateFlow()

    val displayConfig = orderedBanks.map { items ->
        BankDisplayConfig(
            order = items.mapNotNull { item -> item.bank.takeIf { item.isVisible } }
        )
    }

    init {
        scope.launch {
            context.bankPreferenceDataStore.data.collect { snapshot ->
                val rawValue = snapshot[orderedBanksKey]
                val loaded = rawValue?.let(::deserialize)
                _orderedBanks.value = sanitize(loaded ?: defaultOrder)
            }
        }
    }

    fun canHide(bank: Bank): Boolean {
        val current = _orderedBanks.value
        val target = current.firstOrNull { it.bankCode == bank.code } ?: return false
        if (!target.isVisible) return true
        return current.count { it.isVisible } > 1
    }

    fun resetToDefaults() {
        apply(defaultOrder)
    }

    fun apply(items: List<BankPreferenceItem>) {
        val sanitized = sanitize(items)
        _orderedBanks.value = sanitized
        save(sanitized)
    }

    fun move(fromIndex: Int, toIndex: Int) {
        val current = _orderedBanks.value.toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val item = current.removeAt(fromIndex)
        current.add(toIndex, item)
        apply(current)
    }

    fun toggleVisibility(bank: Bank) {
        val current = _orderedBanks.value.toMutableList()
        val index = current.indexOfFirst { it.bankCode == bank.code }
        if (index == -1) return

        val item = current[index]
        if (item.isVisible && current.count { it.isVisible } <= 1) return

        current[index] = item.copy(isVisible = !item.isVisible)
        apply(current)
    }

    private fun save(items: List<BankPreferenceItem>) {
        scope.launch {
            context.bankPreferenceDataStore.edit { preferences ->
                preferences[orderedBanksKey] = json.encodeToString(items)
            }
        }
    }

    private fun deserialize(rawValue: String): List<BankPreferenceItem>? {
        return try {
            json.decodeFromString<List<BankPreferenceItem>>(rawValue)
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitize(items: List<BankPreferenceItem>): List<BankPreferenceItem> {
        val seen = mutableSetOf<String>()
        val sanitized = items.mapNotNull { item ->
            val bank = Bank.fromCode(item.bankCode) ?: return@mapNotNull null
            if (!seen.add(bank.code)) return@mapNotNull null
            BankPreferenceItem(bank = bank, isVisible = item.isVisible)
        }.toMutableList()

        defaultOrder.forEach { defaultItem ->
            if (seen.add(defaultItem.bankCode)) {
                sanitized += defaultItem
            }
        }

        if (sanitized.none { it.isVisible } && sanitized.isNotEmpty()) {
            sanitized[0] = sanitized[0].copy(isVisible = true)
        }

        return sanitized
    }

    companion object {
        val defaultOrder: List<BankPreferenceItem> = Bank.entries.map { bank ->
            BankPreferenceItem(bank = bank, isVisible = bank != Bank.CITI)
        }
    }
}
