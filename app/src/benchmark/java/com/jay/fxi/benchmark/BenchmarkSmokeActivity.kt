package com.jay.fxi.benchmark

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import com.jay.fxi.R

/** Benchmark-only, deterministic no-data surface for the S0-f launcher smoke. */
class BenchmarkSmokeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                id = R.id.benchmark_smoke_ready
                text = READY_TEXT
                contentDescription = READY_DESCRIPTION
                gravity = Gravity.CENTER
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        )
    }

    companion object {
        const val READY_TEXT = "FXi benchmark smoke ready"
        const val READY_DESCRIPTION = "benchmark-smoke-ready"
    }
}
