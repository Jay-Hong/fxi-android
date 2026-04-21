package com.jay.fxi.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jay.fxi.data.local.BankPreferenceManager
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.BankDisplayConfig
import com.jay.fxi.domain.model.BankPreferenceItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class BankPreferenceViewModel @Inject constructor(
    private val bankPreferenceManager: BankPreferenceManager
) : ViewModel() {
    val orderedBanks: StateFlow<List<BankPreferenceItem>> = bankPreferenceManager.orderedBanks
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = bankPreferenceManager.orderedBanks.value
        )

    val displayConfig = bankPreferenceManager.displayConfig
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BankDisplayConfig(
                order = bankPreferenceManager.orderedBanks.value.mapNotNull { item ->
                    if (item.isVisible) item.bank else null
                }
            )
        )

    fun apply(items: List<BankPreferenceItem>) {
        bankPreferenceManager.apply(items)
    }

    fun resetToDefaults() {
        bankPreferenceManager.resetToDefaults()
    }

    fun canHide(bank: Bank): Boolean = bankPreferenceManager.canHide(bank)
}
