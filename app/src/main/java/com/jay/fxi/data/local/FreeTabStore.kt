package com.jay.fxi.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jay.fxi.domain.model.FreeTab
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Where the free surface reopens. Reading is a suspend call so nothing guesses before it lands. */
interface FreeTabStore {
    suspend fun lastTab(uid: String): FreeTab
    suspend fun remember(uid: String, tab: FreeTab)
}

private val Context.freeTabDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fxi_free_tab"
)

/**
 * Keeps the last confirmed tab per UID, shared by free and subscriber surfaces. A legacy
 * `(owner_uid, last_tab)` pair remains readable for its owner until the next write migrates it.
 */
@Singleton
class DataStoreFreeTabStore internal constructor(
    private val dataStore: DataStore<Preferences>
) : FreeTabStore {

    @Inject constructor(@ApplicationContext context: Context) : this(context.freeTabDataStore)

    override suspend fun lastTab(uid: String): FreeTab {
        require(uid.isNotBlank()) { "UID must not be blank" }
        val preferences = dataStore.data.first()
        val stored = preferences[key(uid)]
            ?: preferences[LAST_TAB].takeIf { preferences[OWNER_UID] == uid }
        return FreeTab.fromStorageValue(stored)
    }

    override suspend fun remember(uid: String, tab: FreeTab) {
        require(uid.isNotBlank()) { "UID must not be blank" }
        dataStore.edit { preferences ->
            val oldOwner = preferences[OWNER_UID]
            val oldTab = preferences[LAST_TAB]
            if (!oldOwner.isNullOrBlank() && oldTab != null) {
                val oldKey = key(oldOwner)
                if (preferences[oldKey] == null) preferences[oldKey] = oldTab
            }
            preferences.remove(OWNER_UID)
            preferences.remove(LAST_TAB)
            preferences[key(uid)] = tab.storageValue
        }
    }

    private fun key(uid: String): Preferences.Key<String> = stringPreferencesKey(
        buildString {
            append("last_tab_uid_")
            uid.toByteArray(Charsets.UTF_8).forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0f])
            }
        }
    )

    private companion object {
        const val HEX_DIGITS = "0123456789abcdef"
        val OWNER_UID = stringPreferencesKey("owner_uid")
        val LAST_TAB = stringPreferencesKey("last_tab")
    }
}
