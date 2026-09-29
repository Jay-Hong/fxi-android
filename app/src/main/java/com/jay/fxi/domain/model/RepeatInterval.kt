package com.jay.fxi.domain.model

/**
 * The repeat intervals the server accepts for an alert (B2 ADR-036; iOS a36682f RepeatInterval). A setting without
 * one (null) fires once.
 */
enum class RepeatInterval(val seconds: Int) {
    ONE_MINUTE(60), FIVE_MINUTES(300), TEN_MINUTES(600), THIRTY_MINUTES(1800), ONE_HOUR(3600),
    TWO_HOURS(7200), FOUR_HOURS(14400), SIX_HOURS(21600), TWELVE_HOURS(43200), ONE_DAY(86400);

    /** Picker label ("5분"). */
    val label: String get() = when (this) {
        ONE_MINUTE -> "1분"
        FIVE_MINUTES -> "5분"
        TEN_MINUTES -> "10분"
        THIRTY_MINUTES -> "30분"
        ONE_HOUR -> "1시간"
        TWO_HOURS -> "2시간"
        FOUR_HOURS -> "4시간"
        SIX_HOURS -> "6시간"
        TWELVE_HOURS -> "12시간"
        ONE_DAY -> "1일"
    }

    /** Summary label ("5분마다"). */
    val everyLabel: String get() = "${label}마다"

    companion object {
        /** The interval stored as [seconds]; null for once and for a value the server does not accept. */
        fun from(seconds: Int?): RepeatInterval? = entries.firstOrNull { it.seconds == seconds }
    }
}
