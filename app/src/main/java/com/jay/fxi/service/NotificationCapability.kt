package com.jay.fxi.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

internal enum class NotificationCapability {
    GRANTED,
    RUNTIME_MISSING,
    APP_DISABLED,
    CHANNEL_DISABLED
}

internal fun notificationCapabilityOf(
    sdkInt: Int,
    runtimeGranted: Boolean,
    appEnabled: Boolean,
    channelImportance: Int?
): NotificationCapability = when {
    sdkInt >= 33 && !runtimeGranted -> NotificationCapability.RUNTIME_MISSING
    !appEnabled -> NotificationCapability.APP_DISABLED
    sdkInt >= 26 && channelImportance == 0 -> NotificationCapability.CHANNEL_DISABLED
    else -> NotificationCapability.GRANTED
}

internal fun readNotificationCapability(context: Context): NotificationCapability {
    val sdkInt = Build.VERSION.SDK_INT
    val runtimeGranted = sdkInt < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val appEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val channelImportance = if (sdkInt >= Build.VERSION_CODES.O) {
        context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(FXiMessagingService.CHANNEL_RATE_ALERTS)
            ?.importance
    } else {
        null
    }
    return notificationCapabilityOf(sdkInt, runtimeGranted, appEnabled, channelImportance)
}
