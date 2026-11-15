package com.jay.fxi.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** The last selected bank, owned by one UID within the shared user-intent file. */
interface LastSelectedBankStore {
    suspend fun read(uid: String): String?

    suspend fun remember(uid: String, bankCode: String)

    suspend fun verifyReadable()
}

@Singleton
class DataStoreLastSelectedBankStore @Inject constructor(
    @ApplicationContext private val context: Context
) : LastSelectedBankStore {

    override suspend fun read(uid: String): String? {
        require(uid.isNotBlank())
        var code: String? = null
        context.userIntentDataStore.edit { preferences ->
            // Compare the current owner inside the same edit that clears stale bank keys.
            if (preferences[OWNER_UID] == uid) {
                code = preferences[BANK]
            } else {
                preferences.remove(OWNER_UID)
                preferences.remove(BANK)
            }
        }
        return code
    }

    override suspend fun remember(uid: String, bankCode: String) {
        require(uid.isNotBlank())
        context.userIntentDataStore.edit { preferences ->
            preferences[OWNER_UID] = uid
            preferences[BANK] = bankCode
        }
    }

    override suspend fun verifyReadable() {
        context.userIntentDataStore.data.first()
    }

    private companion object {
        val OWNER_UID = stringPreferencesKey("last_selected_bank_owner_uid")
        val BANK = stringPreferencesKey("last_selected_bank")
    }
}
