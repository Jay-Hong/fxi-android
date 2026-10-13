package com.jay.fxi.di

import com.google.firebase.auth.FirebaseAuth
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.auth.AuthTransitionCoordinator
import com.jay.fxi.data.auth.AuthUserObservation
import com.jay.fxi.data.entitlements.AuthAccessBinder
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.service.PushNotificationManager
import com.jay.fxi.subscription.SubscriptionManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * S5-4 slice 4b: the debug build's window onto its own graph, for its Android tests only. An entry point in androidTest would
 * not join the app's component, so this one lives in the debug source set; no other build type has it.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DebugAuthGraphProbe {
    fun firebaseAuth(): FirebaseAuth
    fun authTokenSource(): AuthTokenSource
    fun authFenceStream(): AuthFenceStream
    fun authUserObservation(): AuthUserObservation
    fun authTokenProvider(): AuthTokenProvider
    fun authTransitionCoordinator(): AuthTransitionCoordinator
    fun subscriptionManager(): SubscriptionManager
    fun pushNotificationManager(): PushNotificationManager
    fun authAccessBinder(): AuthAccessBinder
    fun premiumAccessCoordinator(): PremiumAccessCoordinator
}
