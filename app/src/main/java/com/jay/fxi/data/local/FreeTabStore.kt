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
 * One owner, one tab.
 *
 * The plan asks for the last tab to be restored per UID, and what that has to guarantee is that a
 * second account never opens on the first one's tab. Storing the owner beside the value gives
 * exactly that: a UID that does not match reads as 달러. What it deliberately does not do is keep a
 * tab *per* account — a map would grow one entry per UID that ever signed in here, with no moment
 * at which anything prunes it, to restore a choice only an account-switching user would notice.
 * If that is ever wanted, it is a change behind [FreeTabStore] and nothing above it moves.
 */
@Singleton
class DataStoreFreeTabStore @Inject constructor(
    @ApplicationContext private val context: Context
) : FreeTabStore {

    override suspend fun lastTab(uid: String): FreeTab {
        require(uid.isNotBlank())
        val preferences = context.freeTabDataStore.data.first()
        return if (preferences[OWNER_UID] == uid) FreeTab.fromStorageValue(preferences[LAST_TAB]) else FreeTab.INITIAL
    }

    override suspend fun remember(uid: String, tab: FreeTab) {
        require(uid.isNotBlank())
        context.freeTabDataStore.edit { preferences ->
            preferences[OWNER_UID] = uid
            preferences[LAST_TAB] = tab.storageValue
        }
    }

    private companion object {
        val OWNER_UID = stringPreferencesKey("owner_uid")
        val LAST_TAB = stringPreferencesKey("last_tab")
    }
}
