package com.jay.fxi.domain.repository

import com.jay.fxi.domain.model.NewsItem

interface NewsRepository {
    suspend fun fetchNews(limit: Int = 100, hours: Double = 24.0): List<NewsItem>
    suspend fun saveCache(items: List<NewsItem>)
    suspend fun loadCache(): List<NewsItem>?
}
