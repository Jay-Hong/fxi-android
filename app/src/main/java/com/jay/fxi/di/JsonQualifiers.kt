package com.jay.fxi.di

import javax.inject.Qualifier

/** JSON used at server-controlled REST and WebSocket boundaries. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WireJson

/** JSON used for app-owned DataStore and file persistence. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StorageJson

/** Retrofit/OkHttp graph for unauthenticated, read-only REST endpoints. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PublicRest

/** Retrofit/OkHttp graph for endpoints that require an explicitly captured auth snapshot. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProtectedRest
