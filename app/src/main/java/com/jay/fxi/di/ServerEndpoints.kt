package com.jay.fxi.di

/**
 * S5-4 slice 4a: the server addresses the app talks to, the REST base URL and the topic WebSocket URL. Main does not bind them:
 * each build type takes its binding from its own source set (app/build.gradle.kts), the production one from ApiConfig.
 */
data class ServerEndpoints(val restBaseUrl: String, val webSocketUrl: String)
