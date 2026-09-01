import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Load local.properties
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

val releaseKeystoreFile = rootProject.file("fxi-release.jks")
val hasReleaseKeystore = releaseKeystoreFile.exists() &&
    localProperties.getProperty("KEYSTORE_PASSWORD", "").isNotBlank()

android {
    namespace = "com.jay.fxi"
    compileSdk = 36

    // S0: production keystore 는 CI checkout 에 없다(.gitignore `*.jks` + local.properties).
    // 있을 때만 release signingConfig 를 만든다. 없을 때 debug 서명으로 조용히 대체하지 않는다 —
    // 서명 주체가 바뀌는 것을 빌드가 침묵으로 넘기면 안 되므로 release 는 unsigned 로 남긴다.
    // CI 의 R8 검증은 아래 `ciMinified` 가 debug 서명으로 담당한다.
    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = localProperties.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = localProperties.getProperty("KEY_ALIAS", "fxi")
                keyPassword = localProperties.getProperty("KEY_PASSWORD", "")
            }
        }
    }

    defaultConfig {
        applicationId = "com.jay.fxi"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // RevenueCat API Key from local.properties
        buildConfigField(
            "String",
            "REVENUECAT_API_KEY",
            "\"${localProperties.getProperty("REVENUECAT_API_KEY", "")}\""
        )
        // False everywhere except the isolated benchmark source set/build type.
        buildConfigField("boolean", "BENCHMARK_NO_DATA_MODE", "false")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // S0: CI 전용 R8 lane. release 를 상속해 minify·proguard 규칙을 **동일하게** 태우되
        // production keystore 대신 debug 서명을 쓴다. kotlinx-serialization + R8 은 keep 규칙
        // 누락으로 실제로 깨지는 조합이라 이 검증을 S11 로 미루지 않는다(계획 §7 S0 각주).
        // applicationId 는 release 와 동일하게 둔다 — google-services 가 package_name 일치를 검사한다.
        create("ciMinified") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            // mapping 업로드는 Firebase 자격이 필요하다. CI lane 은 R8 통과만 검증한다.
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
        // S0-f: release-like target for the local physical-device Macrobenchmark lane.
        // It keeps release applicationId/R8 rules and is deliberately non-debuggable, but
        // uses ephemeral debug signing so production signing material is never required.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
            // A developer's local RevenueCat key must never leak into the ephemeral
            // benchmark artifact. The D24 OFF gate remains the runtime admission owner.
            buildConfigField("String", "REVENUECAT_API_KEY", "\"\"")
            buildConfigField("boolean", "BENCHMARK_NO_DATA_MODE", "true")
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// S0: Kotlin 컴파일 toolchain 을 17 로 고정한다. Gradle/plugin 실행 JDK는 빌드 동작에 영향을 줄 수
// 있으므로 CI도 setup-java로 JDK 17을 별도 pin한다. tracked 파일에는 host 절대경로를 두지 않는다.
kotlin {
    jvmToolchain(17)
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.crashlytics)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Network
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.retrofit.kotlinx.serialization)

    // Subscriptions
    implementation(libs.revenuecat)

    // Reorderable (drag-and-drop)
    implementation(libs.reorderable)

    // Splash Screen
    implementation(libs.androidx.splashscreen)

    // Image Loading
    implementation(libs.coil.compose)

    // Google Sign-In (Credential Manager)
    implementation(libs.google.credentials)
    implementation(libs.google.credentials.play.services)
    implementation(libs.google.id.identity)

    // Testing
    testImplementation(libs.junit)
    // S0 테스트 하네스: 가상 시각 기반 코루틴 제어 + 응답 시퀀스를 스크립팅하는 HTTP 오리진.
    // 계약 fixture 테스트(S0-d)가 이 둘을 쓴다.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // S0-f D31: the target benchmark APK must contain ProfileInstaller 1.4.1 as a
    // real resolved dependency. A catalog pin alone does not satisfy the gate.
    add("benchmarkImplementation", libs.androidx.profileinstaller)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
