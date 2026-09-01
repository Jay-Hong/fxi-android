package com.jay.fxi.admission

import com.jay.fxi.BuildConfig

/**
 * Single runtime owner for D24 admission.
 *
 * Remote config, preferences, intent extras, or server responses must never open this
 * gate. A build variant is either compiled ON or it remains fail-closed for its lifetime.
 */
internal object ReleaseAdmission {
    val isOpen: Boolean
        get() = BuildConfig.TOPIC_V2_RELEASE_ON
}
