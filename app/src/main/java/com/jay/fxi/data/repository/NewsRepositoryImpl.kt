package com.jay.fxi.data.repository

import com.jay.fxi.data.local.CacheService
import com.jay.fxi.data.remote.FXiApiService
import com.jay.fxi.domain.model.NewsItem
import com.jay.fxi.domain.repository.NewsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsRepositoryImpl @Inject constructor(
    private val apiService: FXiApiService,
    private val cacheService: CacheService
) : NewsRepository {

    override suspend fun fetchNews(limit: Int, hours: Double): List<NewsItem> {
        return apiService.getNews(limit = limit, hours = hours).news
    }

    override suspend fun saveCache(items: List<NewsItem>) {
        cacheService.saveNewsCache(items)
    }

    override suspend fun loadCache(): List<NewsItem>? {
        return cacheService.loadNewsCache()
    }
}
