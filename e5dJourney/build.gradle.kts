// S7 E5d-J (`e5dj_design.claude.r3.md` §3.3, `e5dj_j2_plan.claude.r3.md` §2): the journey runs from outside the app, as
// :macrobenchmark does: a self-instrumenting test APK against the app's e5dJourney build type, signed with the same debug key
// so that it may hold the fixture's signature permission. Local only; CI does not build or run it.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.jay.fxi.e5djourney.test"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("e5dJourney") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

kotlin {
    jvmToolchain(17)
}

// Local only (design §3.3): the module keeps its e5dJourney variant alone, so CI's root-level debug tasks (assembleDebug,
// lintDebug) never build or lint it, and the app's e5dJourney variant, whose Firebase fixture is a local cp, is never asked for.
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.uiautomator)
}

// R12: a journey device run is only a result together with both local checks of the app's journey variant.
tasks.matching { it.name == "connectedE5dJourneyAndroidTest" }.configureEach {
    dependsOn(":app:verifyE5dJourneyManifest", ":app:verifyE5dJourneyFirebaseFixture")
}
