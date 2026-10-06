package com.jay.fxi.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jay.fxi.di.StorageJson
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.NewsItem
import com.jay.fxi.domain.model.SupportedCurrency
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fxi_cache")

/**
 * 로컬 캐시 서비스
 *
 * 마지막 선택 은행: DataStore (v1 환율 키는 cutover 후 삭제)
 */
@Singleton
class CacheService @Inject constructor(
    @ApplicationContext private val context: Context,
    @StorageJson private val json: Json
) {
    companion object {
        private const val TAG = "CacheService"

        private const val GRAPH_CACHE_VERSION = "v1"
        private const val GRAPH_FILE_PREFIX = "graph_cache_"
        private const val GRAPH_FILE_SUFFIX = ".json"

        private const val KEY_LAST_BANK_PREFIX = "last_bank_"
        private const val NEWS_CACHE_FILE = "news_cache.json"
    }

    /** Deletes only retired rate keys through this service's existing fxi_cache instance. */
    internal suspend fun deleteRetiredRateKeys() = deleteLegacyRateKeys(context.dataStore)

    // ============ 마지막 선택 은행 (알림 추가용) ============

    /**
     * 통화별 마지막 선택 은행 저장
     */
    suspend fun saveLastSelectedBank(currency: String, bankCode: String) {
        val key = stringPreferencesKey("$KEY_LAST_BANK_PREFIX$currency")
        context.dataStore.edit { preferences ->
            preferences[key] = bankCode
        }
    }

    /**
     * 통화별 마지막 선택 은행 로드
     */
    suspend fun loadLastSelectedBank(currency: String): String? {
        val key = stringPreferencesKey("$KEY_LAST_BANK_PREFIX$currency")
        val preferences = context.dataStore.data.first()
        return preferences[key]
    }

    // ============ 뉴스 캐시 (File) ============

    suspend fun saveNewsCache(items: List<NewsItem>) {
        withContext(Dispatchers.IO) {
            try {
                File(context.filesDir, NEWS_CACHE_FILE).writeText(json.encodeToString(items))
            } catch (_: Exception) {
                // 저장 실패 무시
            }
        }
    }

    suspend fun loadNewsCache(): List<NewsItem>? {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, NEWS_CACHE_FILE)
                if (!file.exists()) return@withContext null
                json.decodeFromString<List<NewsItem>>(file.readText())
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 전체 캐시 삭제
     */
    suspend fun clearAllCache() {
        // DataStore 클리어
        context.dataStore.edit { it.clear() }

        // 그래프 파일 삭제
        withContext(Dispatchers.IO) {
            for (currency in SupportedCurrency.entries) {
                for (period in GraphPeriod.entries) {
                    try {
                        graphCacheFile(currency.code, period).delete()
                    } catch (_: Exception) {
                        // 삭제 실패 무시
                    }
                    try {
                        legacyGraphCacheFile(currency.code, period).delete()
                    } catch (_: Exception) {
                        // 삭제 실패 무시
                    }
                }
            }
        }
    }

    // ============ Private Helpers ============

    private fun graphCacheFile(currency: String, period: GraphPeriod): File {
        val filename = if (period == GraphPeriod.ONE_DAY) {
            "${GRAPH_FILE_PREFIX}${GRAPH_CACHE_VERSION}_${currency}$GRAPH_FILE_SUFFIX"
        } else {
            "${GRAPH_FILE_PREFIX}${GRAPH_CACHE_VERSION}_${currency}_${period.code}$GRAPH_FILE_SUFFIX"
        }
        return File(context.filesDir, filename)
    }

    private fun legacyGraphCacheFile(currency: String, period: GraphPeriod): File {
        val filename = if (period == GraphPeriod.ONE_DAY) {
            "$GRAPH_FILE_PREFIX$currency$GRAPH_FILE_SUFFIX"
        } else {
            "${GRAPH_FILE_PREFIX}${currency}_${period.code}$GRAPH_FILE_SUFFIX"
        }
        return File(context.filesDir, filename)
    }
}
