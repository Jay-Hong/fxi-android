package com.jay.fxi.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * A file of its own, for user intent.
 *
 * D27 wants backup-eligible user intent kept physically apart from token, capability and cache
 * state. An order and a hidden set are exactly that — worth carrying to a new device, and harmless
 * if they arrive there. The two backup XMLs are exclude-only and the manifest allows backup, so
 * landing this file with no rule change is what makes it eligible; `BackupRulesLedgerTest` records
 * that as a decision rather than an accident.
 */
internal val Context.userIntentDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fxi_user_intent"
)
