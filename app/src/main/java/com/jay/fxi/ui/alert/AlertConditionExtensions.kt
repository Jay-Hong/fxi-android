package com.jay.fxi.ui.alert

import com.jay.fxi.domain.model.AlertCondition

val AlertCondition.symbol: String
    get() = when (this) {
        AlertCondition.ABOVE -> "△"
        AlertCondition.BELOW -> "▽"
    }
