package com.jay.fxi.service

import android.content.Intent

/** Native typed access only; routing, custody and classification live in the pure entry. */
internal class AndroidAlertTapExtras(private val intent: Intent) : AlertTapExtras, AlertTapExtraSink {
    override fun readInt(key: String, default: Int): Int = intent.getIntExtra(key, default)

    override fun readString(key: String): String? = intent.getStringExtra(key)

    @Suppress("DEPRECATION")
    override fun kind(key: String): AlertTapExtraKind = when {
        !intent.hasExtra(key) -> AlertTapExtraKind.MISSING
        else -> when (intent.extras?.get(key)) {
            null -> AlertTapExtraKind.NULL
            is String -> AlertTapExtraKind.STRING
            is Int -> AlertTapExtraKind.INT
            else -> AlertTapExtraKind.OTHER
        }
    }

    override fun remove(key: String) { intent.removeExtra(key) }

    override fun putString(key: String, value: String) { intent.putExtra(key, value) }
}
