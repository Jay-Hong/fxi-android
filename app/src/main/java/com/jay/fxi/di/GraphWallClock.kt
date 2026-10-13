package com.jay.fxi.di

import javax.inject.Qualifier

/**
 * S5-4 slice 2 (limited 3e): the premium graph's wall clock, one instance for the graph assembly and the holders the builder
 * makes. Main does not bind it: each build type takes its binding from its own source set (app/build.gradle.kts).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GraphWallClock
