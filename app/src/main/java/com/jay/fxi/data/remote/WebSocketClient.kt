package com.jay.fxi.data.remote

import com.jay.fxi.admission.ReleaseAdmissionInterceptor
import com.jay.fxi.util.WebSocketConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal fun buildWebSocketClient(): OkHttpClient = OkHttpClient.Builder()
    // D24 must run before metadata, DNS, or socket work on the independent WS stack.
    .addInterceptor(ReleaseAdmissionInterceptor())
    .addInterceptor(ClientMetadataInterceptor())
    .connectTimeout(WebSocketConfig.CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .writeTimeout(WebSocketConfig.CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .build()
