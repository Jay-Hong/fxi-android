package com.jay.fxi.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.GraphBucket
import com.jay.fxi.domain.model.GraphCache
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.util.GraphConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fxi_cache")

/**
 * 로컬 캐시 서비스
 *
 * 환율 캐시: DataStore (JSON 문자열, ~5KB)
 * 그래프 캐시: File (통화별 JSON 파일, ~50KB each)
 *
 * 캐시 규칙:
 * - 그래프 TTL: 24시간
 * - 갭 검증: 144개 버킷, 15분 이상 갭 없어야 유효
 */
@Singleton
class CacheService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {
    companion object {
        private const val TAG = "CacheService"
        private val KEY_RATES = stringPreferencesKey("rates")
        private val KEY_RATES_TIMESTAMP = longPreferencesKey("rates_timestamp")

        private const val GRAPH_CACHE_TTL_MS = 24 * 60 * 60 * 1000L  // 24시간
        private const val GRAPH_FILE_PREFIX = "graph_cache_"
        private const val GRAPH_FILE_SUFFIX = ".json"

        private const val KEY_LAST_BANK_PREFIX = "last_bank_"
    }

    // ============ 환율 캐시 (DataStore) ============

    /**
     * 환율 데이터 저장
     */
    suspend fun saveRates(rates: List<ExchangeRate>) {
        context.dataStore.edit { preferences ->
            preferences[KEY_RATES] = json.encodeToString(rates)
            preferences[KEY_RATES_TIMESTAMP] = System.currentTimeMillis()
        }
    }

    /**
     * 캐시된 환율 데이터 로드
     */
    suspend fun loadCachedRates(): List<ExchangeRate>? {
        val preferences = context.dataStore.data.first()
        val ratesJson = preferences[KEY_RATES] ?: return null
        return try {
            json.decodeFromString<List<ExchangeRate>>(ratesJson)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 환율 캐시 타임스탬프 조회
     */
    suspend fun cachedRatesTimestamp(): Instant? {
        val preferences = context.dataStore.data.first()
        val timestamp = preferences[KEY_RATES_TIMESTAMP] ?: return null
        return Instant.fromEpochMilliseconds(timestamp)
    }

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

    // ============ 그래프 캐시 (File) ============

    /**
     * 통화별 그래프 데이터 저장
     * @param data 소스별 버킷 맵 (source -> buckets)
     * @param currency 통화쌍 코드 (예: "usd-krw")
     */
    suspend fun saveGraphData(data: Map<String, List<GraphBucket>>, currency: String) {
        withContext(Dispatchers.IO) {
            try {
                val file = graphCacheFile(currency)
                file.writeText(json.encodeToString(data))
            } catch (e: Exception) {
                // 저장 실패 무시 (다음 요청에서 재시도)
            }
        }
    }

    /**
     * 통화별 그래프 데이터 로드
     * TTL(24시간) 초과 시 null 반환
     */
    suspend fun loadGraphData(currency: String): Map<String, List<GraphBucket>>? {
        return withContext(Dispatchers.IO) {
            try {
                val file = graphCacheFile(currency)
                if (!file.exists()) return@withContext null

                // TTL 체크 (24시간)
                val modifiedTime = file.lastModified()
                if (System.currentTimeMillis() - modifiedTime > GRAPH_CACHE_TTL_MS) {
                    return@withContext null
                }

                val content = file.readText()
                try {
                    json.decodeFromString<Map<String, List<GraphBucket>>>(content)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to deserialize graph cache: currency=$currency", e)
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 전체 그래프 캐시 저장 (앱 백그라운드 진입 시)
     */
    suspend fun saveAllGraphData(memoryCache: GraphCache) {
        withContext(Dispatchers.IO) {
            for ((currency, sourceData) in memoryCache) {
                try {
                    val file = graphCacheFile(currency)
                    file.writeText(json.encodeToString(sourceData))
                } catch (e: Exception) {
                    // 개별 저장 실패 무시
                }
            }
        }
    }

    /**
     * 전체 그래프 캐시 로드 (앱 시작 시)
     */
    suspend fun loadAllGraphData(): GraphCache {
        return withContext(Dispatchers.IO) {
            val result = mutableMapOf<String, Map<String, List<GraphBucket>>>()
            for (currency in SupportedCurrency.entries) {
                try {
                    val file = graphCacheFile(currency.code)
                    if (!file.exists()) continue

                    // TTL 체크
                    val modifiedTime = file.lastModified()
                    if (System.currentTimeMillis() - modifiedTime > GRAPH_CACHE_TTL_MS) {
                        continue
                    }

                    val content = file.readText()
                    try {
                        val data = json.decodeFromString<Map<String, List<GraphBucket>>>(content)
                        result[currency.code] = data
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to deserialize graph cache: currency=${currency.code}", e)
                    }
                } catch (e: Exception) {
                    // 개별 로드 실패 무시
                }
            }
            result
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
                try {
                    graphCacheFile(currency.code).delete()
                } catch (e: Exception) {
                    // 삭제 실패 무시
                }
            }
        }
    }

    // ============ 그래프 캐시 유효성 검증 ============

    /**
     * 그래프 캐시 유효성 검사
     * - 모든 소스가 144개 이상의 버킷을 가지고 있어야 함
     * - 버킷 간 15분 이상 갭이 없어야 함
     */
    fun hasValidCache(data: Map<String, List<GraphBucket>>): Boolean {
        if (data.isEmpty()) return false

        for ((_, buckets) in data) {
            // 개수 체크
            if (buckets.size < GraphConfig.MAX_BUCKETS) return false

            // 갭 체크 (연속성)
            val sorted = buckets.sortedBy { it.bucketTs }
            for (i in 1 until sorted.size) {
                val gap = sorted[i].bucketTs - sorted[i - 1].bucketTs
                if (gap >= GraphConfig.GAP_THRESHOLD_SEC) return false
            }
        }
        return true
    }

    /**
     * 마지막 버킷 타임스탬프 조회 (갭 감지용)
     */
    fun lastBucketTimestamp(data: Map<String, List<GraphBucket>>, source: String): Int? {
        return data[source]?.maxByOrNull { it.bucketTs }?.bucketTs
    }

    // ============ Private Helpers ============

    private fun graphCacheFile(currency: String): File {
        return File(context.filesDir, "$GRAPH_FILE_PREFIX$currency$GRAPH_FILE_SUFFIX")
    }
}
