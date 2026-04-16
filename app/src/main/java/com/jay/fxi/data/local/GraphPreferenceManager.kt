package com.jay.fxi.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jay.fxi.domain.model.GraphSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.graphPreferenceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fxi_graph_preferences"
)

/**
 * 그래프 토글 상태(소스 선택, DXY 표시 여부) 영속화.
 *
 * iOS GraphViewModel의 selectedSources / dxyVisible 영속화와 동일한 의미를 가진다.
 * 저장 포맷은 GraphSource.code 기반 JSON List<String>로 cross-platform과 일치시킨다.
 */
@Singleton
class GraphPreferenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val selectedSourcesKey = stringPreferencesKey("selected_sources")
    private val dxyVisibleKey = booleanPreferencesKey("dxy_visible")

    private val _selectedSources = MutableStateFlow(setOf(GraphSource.INVESTING))
    val selectedSources: StateFlow<Set<GraphSource>> = _selectedSources.asStateFlow()

    private val _dxyVisible = MutableStateFlow(false)
    val dxyVisible: StateFlow<Boolean> = _dxyVisible.asStateFlow()

    init {
        scope.launch {
            context.graphPreferenceDataStore.data.collect { snapshot ->
                _selectedSources.value = snapshot[selectedSourcesKey]
                    ?.let(::deserializeSources)
                    ?.let(::sanitizeSources)
                    ?: setOf(GraphSource.INVESTING)
                _dxyVisible.value = snapshot[dxyVisibleKey] ?: false
            }
        }
    }

    fun toggleSource(source: GraphSource) {
        if (source !in GraphSource.realtimeSources) return

        val current = _selectedSources.value.toMutableSet()
        if (source in current) {
            if (current.size > 1) {
                current.remove(source)
            }
        } else {
            current.add(source)
        }
        _selectedSources.value = current
        persistSelectedSources(current)
    }

    fun toggleDxy() {
        val newValue = !_dxyVisible.value
        _dxyVisible.value = newValue
        persistDxyVisible(newValue)
    }

    private fun persistSelectedSources(sources: Set<GraphSource>) {
        val orderedCodes = GraphSource.realtimeSources
            .filter { it in sources }
            .map { it.code }
        scope.launch {
            context.graphPreferenceDataStore.edit { preferences ->
                preferences[selectedSourcesKey] = json.encodeToString(orderedCodes)
            }
        }
    }

    private fun persistDxyVisible(value: Boolean) {
        scope.launch {
            context.graphPreferenceDataStore.edit { preferences ->
                preferences[dxyVisibleKey] = value
            }
        }
    }

    private fun deserializeSources(rawValue: String): List<String>? {
        return try {
            json.decodeFromString<List<String>>(rawValue)
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeSources(codes: List<String>): Set<GraphSource> {
        val restored = codes
            .mapNotNull(GraphSource::fromCode)
            .filter { it in GraphSource.realtimeSources }
            .toSet()
        return restored.ifEmpty { setOf(GraphSource.INVESTING) }
    }
}
