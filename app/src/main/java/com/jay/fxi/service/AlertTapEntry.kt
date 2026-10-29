package com.jay.fxi.service

/** Typed boundary shared by the native adapter and JVM callers. No Android objects cross it. */
internal enum class AlertTapExtraKind { MISSING, NULL, STRING, INT, OTHER }

internal interface AlertTapExtras {
    fun readInt(key: String, default: Int): Int
    fun readString(key: String): String?
    fun kind(key: String): AlertTapExtraKind
    fun remove(key: String)
}

internal fun interface AlertTapExtraSink {
    fun putString(key: String, value: String)
}

internal data class AlertTapDecoded(val event: AlertEvent?, val metadata: AlertTapMetadata)

/** Context must be captured before the first raw getter runs. */
internal class AlertTapIntake(
    val context: () -> AlertTapContext,
    val capture: (AlertEvent?, AlertTapMetadata, AlertTapContext) -> AlertTapCaptureResult,
    val classify: (AlertTapTicket) -> AlertTapClassification,
    val forward: (AlertEvent) -> Boolean
)

internal object AlertTapCodec {
    const val CUSTOM_TYPE = "notification_type"
    const val SERVER_TYPE = "type"
    const val CUSTOM_ID = "setting_id"
    const val SERVER_ID = "setting_id"
    val CLASSIFICATION_KEYS: List<String> = java.util.Collections.unmodifiableList(listOf(
        "source", "asset", "tab", "left_source", "left_asset", "right_source", "right_asset"
    ))

    fun read(extras: AlertTapExtras): AlertTapDecoded {
        val customType = extras.readString(CUSTOM_TYPE)
        val serverType = extras.readString(SERVER_TYPE)
        val customKind = extras.kind(CUSTOM_TYPE)
        val serverKind = extras.kind(SERVER_TYPE)
        // Preserve the legacy Int(-1) -> custom String -> server String precedence.
        val intId = extras.readInt(CUSTOM_ID, -1)
        val settingId = intId.takeIf { it != -1 }?.toString()
            ?: extras.readString(CUSTOM_ID)
            ?: extras.readString(SERVER_ID)
        val values = CLASSIFICATION_KEYS.associateWith { extras.readString(it) }
        val kinds = CLASSIFICATION_KEYS.associateWith { extras.kind(it) }
        val wrongTypes = kinds.filterValues { it == AlertTapExtraKind.INT || it == AlertTapExtraKind.OTHER }.keys.toMutableSet()
        for ((key, kind) in listOf(CUSTOM_TYPE to customKind, SERVER_TYPE to serverKind)) {
            if (kind != AlertTapExtraKind.MISSING && kind != AlertTapExtraKind.STRING) wrongTypes.add(key)
        }
        val conflict = customKind != AlertTapExtraKind.MISSING && serverKind != AlertTapExtraKind.MISSING &&
            customType != serverType
        val route = AlertPushRouting.route(customType ?: serverType, settingId)
        return AlertTapDecoded(
            if (wrongTypes.any { it == CUSTOM_TYPE || it == SERVER_TYPE } || conflict) null else route?.event,
            AlertTapMetadata(
                source = values["source"], asset = values["asset"], tab = values["tab"],
                leftSource = values["left_source"], leftAsset = values["left_asset"],
                rightSource = values["right_source"], rightAsset = values["right_asset"],
                wrongTypeKeys = wrongTypes.toSet(), typeConflict = conflict,
                presentKeys = kinds.filterValues { it != AlertTapExtraKind.MISSING }.keys.toSet(),
                rawCustomType = customType, rawType = serverType, rawSettingId = settingId
            )
        )
    }

    fun clear(extras: AlertTapExtras) {
        for (key in listOf(CUSTOM_TYPE, CUSTOM_ID, SERVER_TYPE, SERVER_ID) + CLASSIFICATION_KEYS) {
            extras.remove(key)
        }
    }
}

internal object AlertTapEntry {
    fun intakeNew(extras: AlertTapExtras, restored: Boolean, intake: AlertTapIntake): AlertTapCaptureResult {
        if (restored) return AlertTapCaptureResult.NOT_ALERT
        val context = intake.context()
        val decoded = AlertTapCodec.read(extras)
        val result = intake.capture(decoded.event, decoded.metadata, context)
        val ticket = (result as? AlertTapCaptureResult.CAPTURED)?.ticket ?: return result
        // Custody, not emission or consumption, authorizes removing the original extras.
        AlertTapCodec.clear(extras)
        intake.classify(ticket)
        return result
    }

    fun intakeWarm(extras: AlertTapExtras, intake: AlertTapIntake): AlertTapCaptureResult {
        val result = intakeNew(extras, false, intake)
        if (result is AlertTapCaptureResult.CAPTURED) intake.forward(AlertEvent.PendingTap(result.ticket))
        return result
    }

    fun forwardRoot(
        event: AlertEvent?,
        hasCustody: (AlertEvent) -> Boolean,
        consumeSlot: (AlertEvent) -> Unit
    ): Boolean {
        if (event !is AlertEvent.PendingTap || !hasCustody(event)) return false
        consumeSlot(event)
        return true
    }

    fun clearSlot(current: AlertEvent?, expected: AlertEvent): AlertEvent? =
        if (current == expected) null else current

    fun copyForegroundClassification(data: Map<String, String>, sink: AlertTapExtraSink) {
        for (key in AlertTapCodec.CLASSIFICATION_KEYS) {
            if (data.containsKey(key)) sink.putString(key, data.getValue(key))
        }
    }
}
