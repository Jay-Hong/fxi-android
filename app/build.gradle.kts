import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
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

private data class GoogleServicesIdentity(
    val projectNumber: String,
    val projectId: String,
    val mobileSdkAppId: String,
    val apiKeys: Set<String>,
    val webClientIds: Set<String>
)

private fun JsonObject.requiredString(key: String, label: String): String {
    val value = get(key)
    check(value != null && value.isJsonPrimitive) { "$label must be a JSON primitive." }
    return value.asString.also { parsed ->
        check(parsed.isNotBlank()) { "$label must not be blank." }
    }
}

private fun googleServicesIdentity(file: File, applicationId: String): GoogleServicesIdentity {
    val document = runCatching {
        file.bufferedReader().use { reader -> JsonParser().parse(reader) }
    }.getOrNull()
    check(document?.isJsonObject == true) {
        "${file.name} must be a parseable JSON object."
    }
    val root = document.asJsonObject
    val projectInfo = root.getAsJsonObject("project_info")
    check(projectInfo != null) { "${file.name} must contain project_info." }

    val clients = root.getAsJsonArray("client")
    check(clients != null) { "${file.name} must contain a client array." }
    val matchingClients = clients.mapNotNull { element ->
        if (!element.isJsonObject) return@mapNotNull null
        val client = element.asJsonObject
        val packageName = client.getAsJsonObject("client_info")
            ?.getAsJsonObject("android_client_info")
            ?.get("package_name")
            ?.takeIf { value -> value.isJsonPrimitive }
            ?.asString
        client.takeIf { packageName == applicationId }
    }
    check(matchingClients.size == 1) {
        "${file.name} must contain exactly one client for the production applicationId."
    }
    val client = matchingClients.single()
    val clientInfo = client.getAsJsonObject("client_info")
    check(clientInfo != null) { "${file.name} production client must contain client_info." }

    val apiKeys = client.getAsJsonArray("api_key")
        ?.mapNotNull { element ->
            element.takeIf { value -> value.isJsonObject }
                ?.asJsonObject
                ?.get("current_key")
                ?.takeIf { value -> value.isJsonPrimitive }
                ?.asString
                ?.takeIf(String::isNotBlank)
        }
        ?.toSet()
        .orEmpty()
    check(apiKeys.isNotEmpty()) { "${file.name} production client must contain an API key." }

    val webClientIds = client.getAsJsonArray("oauth_client")
        ?.mapNotNull { element ->
            val oauthClient = element.takeIf { value -> value.isJsonObject }?.asJsonObject
                ?: return@mapNotNull null
            val clientType = oauthClient.get("client_type")
                ?.takeIf { value -> value.isJsonPrimitive }
                ?.asString
            oauthClient.get("client_id")
                ?.takeIf { value -> clientType == "3" && value.isJsonPrimitive }
                ?.asString
                ?.takeIf(String::isNotBlank)
        }
        ?.toSet()
        .orEmpty()
    check(webClientIds.isNotEmpty()) {
        "${file.name} production client must contain a type-3 web OAuth client."
    }

    return GoogleServicesIdentity(
        projectNumber = projectInfo.requiredString("project_number", "project_info.project_number"),
        projectId = projectInfo.requiredString("project_id", "project_info.project_id"),
        mobileSdkAppId = clientInfo.requiredString(
            "mobilesdk_app_id",
            "client_info.mobilesdk_app_id"
        ),
        apiKeys = apiKeys,
        webClientIds = webClientIds
    )
}

// Load local.properties
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

val releaseKeystoreFile = rootProject.file("fxi-release.jks")
val productionApplicationId = "com.jay.fxi"
val productionGoogleServicesFile = project.file("google-services.json")
val productionGoogleServicesPath = productionGoogleServicesFile.toPath().toAbsolutePath().normalize()
val ciGoogleServicesFixtureFile = rootProject.file("ci/google-services.ci-fixture.json")
// Use the pinned plugin's own resolver rather than duplicating its unusual no-flavor paths
// (`src//release`, `src/release/`, `src/Release`, ...). The plugin selects the first regular
// file, so public release must reject every normalized candidate except the project-root owner.
val publicReleaseGoogleServicesCandidates =
    com.google.gms.googleservices.GoogleServicesPlugin.getJsonFiles(
        "release",
        emptyList(),
        project.projectDir
    ).map { candidate -> candidate.toPath().toAbsolutePath().normalize() }.distinct()
check(productionGoogleServicesPath in publicReleaseGoogleServicesCandidates) {
    "google-services plugin no longer resolves app/google-services.json for release; " +
        "review the D24 production Firebase owner before building."
}
val publicReleaseGoogleServicesShadows =
    publicReleaseGoogleServicesCandidates.filterNot { candidate ->
        candidate == productionGoogleServicesPath
    }
val hasCompleteReleaseSigning = releaseKeystoreFile.exists() &&
    listOf("KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").all { key ->
        localProperties.getProperty(key, "").isNotBlank()
    }

// D24 keeps artifact bytes separate from build authorization. The release variant is
// deterministically ON; this property only allows its tasks to execute after a separate GO.
val publicReleaseBuildApprovalProperty = "fxi.publicReleaseBuildApproved"
val persistedPublicReleaseApproval = providers.gradleProperty(publicReleaseBuildApprovalProperty).orNull
val invocationPublicReleaseApproval = gradle.startParameter.projectProperties[
    publicReleaseBuildApprovalProperty
]
check(persistedPublicReleaseApproval == null || invocationPublicReleaseApproval != null) {
    "$publicReleaseBuildApprovalProperty must not be persisted in a Gradle properties file; " +
        "supply it explicitly with -P for the approved invocation only."
}
val publicReleaseBuildApproved = invocationPublicReleaseApproval
    ?.let { raw ->
        when (raw) {
            "true" -> true
            "false" -> false
            else -> error(
                "-P$publicReleaseBuildApprovalProperty must be exactly true or false " +
                    "(got '$raw')"
            )
        }
    }
    ?: false

android {
    namespace = "com.jay.fxi"
    compileSdk = 36

    // S0: production keystore 는 CI checkout 에 없다(.gitignore `*.jks` + local.properties).
    // 있을 때만 release signingConfig 를 만든다. 없을 때 debug 서명으로 조용히 대체하지 않는다 —
    // 서명 주체가 바뀌는 것을 빌드가 침묵으로 넘기면 안 되므로 release 는 unsigned 로 남긴다.
    // CI 의 R8 검증은 아래 `ciMinified` 가 debug 서명으로 담당한다.
    signingConfigs {
        if (hasCompleteReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = localProperties.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = localProperties.getProperty("KEY_ALIAS", "")
                keyPassword = localProperties.getProperty("KEY_PASSWORD", "")
            }
        }
    }

    defaultConfig {
        applicationId = productionApplicationId
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
        // D24 is fail-closed. Individual non-public verification variants may override
        // this, but every new build type inherits OFF unless it opts in explicitly.
        buildConfigField("boolean", "TOPIC_V2_RELEASE_ON", "false")
        manifestPlaceholders["topicV2ReleaseOn"] = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // The public candidate is always compiled ON. Authorization never changes
            // artifact bytes; it only permits this variant's tasks to execute.
            buildConfigField("boolean", "TOPIC_V2_RELEASE_ON", "true")
            manifestPlaceholders["topicV2ReleaseOn"] = true
            if (hasCompleteReleaseSigning) {
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
            // S0-g's non-public ON/R8 lane. This is not a public-release arming action.
            buildConfigField("boolean", "TOPIC_V2_RELEASE_ON", "true")
            manifestPlaceholders["topicV2ReleaseOn"] = true
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
            // S0-f launcher smoke must not bypass D24. Functional ON journeys belong to S5.
            buildConfigField("boolean", "TOPIC_V2_RELEASE_ON", "false")
            manifestPlaceholders["topicV2ReleaseOn"] = false
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

// Owner structure tests inspect all three merged manifests, including library declarations.
// Build their current inputs even when only one unit-test variant is requested, and invalidate
// cached test results when any merged manifest changes. This does not assemble the other variants.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn("processDebugManifest", "processBenchmarkManifest", "processCiMinifiedManifest")
    inputs.files(
        layout.buildDirectory.file("intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"),
        layout.buildDirectory.file("intermediates/merged_manifests/benchmark/processBenchmarkManifest/AndroidManifest.xml"),
        layout.buildDirectory.file("intermediates/merged_manifests/ciMinified/processCiMinifiedManifest/AndroidManifest.xml")
    ).withPropertyName("controlOwnerMergedManifests")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}

// The accumulation suite is deliberately opt-in; keep the shared manifest setup above intact.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (name != "testRotationAccumulation") {
        filter.excludeTestsMatching("*.RotationAccumulationLongTest")
    }
}
tasks.register<org.gradle.api.tasks.testing.Test>("testRotationAccumulation") {
    val debugUnitTestForAccumulation = tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest")
    group = "verification"
    description = "Runs only the opt-in rotation accumulation suite"
    dependsOn("compileDebugUnitTestKotlin", "compileDebugUnitTestJavaWithJavac", "processDebugUnitTestJavaRes")
    testClassesDirs = files(debugUnitTestForAccumulation.map { it.testClassesDirs })
    classpath = files(debugUnitTestForAccumulation.map { it.classpath })
    filter.includeTestsMatching("*.RotationAccumulationLongTest")
    filter.isFailOnNoMatchingTests = true
    systemProperty("fxi.rotation.report", layout.buildDirectory.file("reports/rotation-accumulation.txt").get().asFile.absolutePath)
    testLogging.showStandardStreams = true
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (name != "testSettlementAccumulation") {
        filter.excludeTestsMatching("*.SettlementAccumulationLongTest")
    }
}
tasks.register<org.gradle.api.tasks.testing.Test>("testSettlementAccumulation") {
    val debugUnitTestForAccumulation = tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest")
    group = "verification"
    description = "Runs only the opt-in settlement accumulation suite"
    dependsOn("compileDebugUnitTestKotlin", "compileDebugUnitTestJavaWithJavac", "processDebugUnitTestJavaRes")
    testClassesDirs = files(debugUnitTestForAccumulation.map { it.testClassesDirs })
    classpath = files(debugUnitTestForAccumulation.map { it.classpath })
    filter.includeTestsMatching("*.SettlementAccumulationLongTest")
    filter.isFailOnNoMatchingTests = true
    systemProperty("fxi.settlement.report", layout.buildDirectory.file("reports/settlement-accumulation.txt").get().asFile.absolutePath)
    testLogging.showStandardStreams = true
}

val verifyProductionGoogleServicesConfig = {
    check(Files.isRegularFile(productionGoogleServicesPath, LinkOption.NOFOLLOW_LINKS)) {
        "Public release requires a regular, non-symlink app/google-services.json; " +
            "build-type CI fixtures are invalid."
    }
    val shadowingGoogleServicesFiles = publicReleaseGoogleServicesShadows.filter { candidate ->
        Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
    }
    check(shadowingGoogleServicesFiles.isEmpty()) {
        "Public release Firebase config is ambiguous: remove higher-priority source-set " +
            "google-services.json files (${shadowingGoogleServicesFiles.joinToString { path ->
                project.projectDir.toPath().toAbsolutePath().normalize().relativize(path).toString()
            }})."
    }
    val productionIdentity = googleServicesIdentity(
        productionGoogleServicesFile,
        productionApplicationId
    )
    if (ciGoogleServicesFixtureFile.isFile) {
        val ciIdentity = googleServicesIdentity(ciGoogleServicesFixtureFile, productionApplicationId)
        val reusedIdentityFields = buildList {
            if (productionIdentity.projectNumber == ciIdentity.projectNumber) add("project_number")
            if (productionIdentity.projectId == ciIdentity.projectId) add("project_id")
            if (productionIdentity.mobileSdkAppId == ciIdentity.mobileSdkAppId) {
                add("mobilesdk_app_id")
            }
            if (productionIdentity.apiKeys.intersect(ciIdentity.apiKeys).isNotEmpty()) {
                add("api_key.current_key")
            }
            if (productionIdentity.webClientIds.intersect(ciIdentity.webClientIds).isNotEmpty()) {
                add("oauth_client[type=3].client_id")
            }
        }
        check(reusedIdentityFields.isEmpty()) {
            "Public release app/google-services.json must not reuse checked-in CI fixture " +
                "identity fields (${reusedIdentityFields.joinToString()})."
        }
    }
}

val enforcePublicReleaseBuildApproval = {
    check(publicReleaseBuildApproved) {
        "Public release build is not approved. A separate arming/build GO must precede " +
            "-P$publicReleaseBuildApprovalProperty=true."
    }
    verifyProductionGoogleServicesConfig()
    check(hasCompleteReleaseSigning) {
        "Public release requires fxi-release.jks and explicit KEYSTORE_PASSWORD, " +
            "KEY_ALIAS, and KEY_PASSWORD values in local.properties."
    }
    check(localProperties.getProperty("REVENUECAT_API_KEY", "").isNotBlank()) {
        "Public release requires an explicit REVENUECAT_API_KEY in local.properties."
    }
}

val verifyPublicReleaseBuildApproved = tasks.register("verifyPublicReleaseBuildApproved") {
    group = "verification"
    description = "Fails unless public release build and production signing are approved."
    doLast {
        enforcePublicReleaseBuildApproval()
    }
}

tasks.register("verifyProductionGoogleServicesConfig") {
    group = "verification"
    description = "Verifies the single production Firebase owner without arming release."
    doLast {
        verifyProductionGoogleServicesConfig()
    }
}

val appProjectPath = project.path
val isPublicReleaseBuildTaskName: (String) -> Boolean = { taskName ->
    taskName.contains("release", ignoreCase = true) &&
        !taskName.startsWith("uninstall", ignoreCase = true)
}
// `uninstallRelease` depends on `preReleaseBuild`, so excluding only the uninstall task name
// still trips the build gate. Exempt only an invocation whose complete requested task set is
// the exact recovery action; combined uninstall+build/package invocations remain gated.
val releaseUninstallOnlyInvocation = gradle.startParameter.taskNames.isNotEmpty() &&
    gradle.startParameter.taskNames.all { requestedTask ->
        requestedTask.substringAfterLast(':').equals("uninstallRelease", ignoreCase = true)
    }
gradle.taskGraph.whenReady {
    val publicReleaseTaskInGraph = !releaseUninstallOnlyInvocation && allTasks.any { task ->
        task.project.path == appProjectPath &&
            task.name != verifyPublicReleaseBuildApproved.name &&
            isPublicReleaseBuildTaskName(task.name)
    }
    if (publicReleaseTaskInGraph) {
        // Runs even when a task is UP-TO-DATE/FROM-CACHE or the verifier is excluded.
        enforcePublicReleaseBuildApproval()
    }
}

// Every task that touches the public `release` variant waits on the same authorization
// gate. This includes low-level package/sign tasks, so invoking them directly cannot bypass
// the lifecycle task. ciMinified and benchmark have distinct names and remain unaffected.
if (!releaseUninstallOnlyInvocation) {
    tasks.matching {
        it.name != verifyPublicReleaseBuildApproved.name &&
            isPublicReleaseBuildTaskName(it.name)
    }.configureEach {
        dependsOn(verifyPublicReleaseBuildApproved)
        // `-x verifyPublicReleaseBuildApproved` must not turn task exclusion into arming.
        doFirst {
            enforcePublicReleaseBuildApproval()
        }
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
