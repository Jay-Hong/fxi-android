import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
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
// S5-4 slice 4b: the local s5Benchmark fixture must resolve the checked-in non-production CI Firebase fixture. The plugin takes
// the first regular file among these candidates; a missing, shadowed, linked or different file fails the build before the
// plugin reads it.
val s5BenchmarkGoogleServicesCandidates =
    com.google.gms.googleservices.GoogleServicesPlugin.getJsonFiles(
        "s5Benchmark",
        emptyList(),
        project.projectDir
    ).map { candidate -> candidate.toPath().toAbsolutePath().normalize() }.distinct()
val s5BenchmarkGoogleServicesPath = project.file("src/s5Benchmark/google-services.json").toPath().toAbsolutePath().normalize()
val ciFirebaseFixturePath = rootProject.file("ci/google-services.ci-fixture.json").toPath().toAbsolutePath().normalize()
check(s5BenchmarkGoogleServicesPath in s5BenchmarkGoogleServicesCandidates) {
    "google-services plugin no longer resolves src/s5Benchmark/google-services.json for s5Benchmark; review the S5 fixture."
}
val verifyS5BenchmarkFirebaseFixture = tasks.register("verifyS5BenchmarkFirebaseFixture") {
    doLast {
        val selected = s5BenchmarkGoogleServicesCandidates.firstOrNull { candidate ->
            Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
        }
        check(selected == s5BenchmarkGoogleServicesPath) {
            "s5Benchmark would resolve $selected, not its own non-production Firebase fixture"
        }
        check(Files.readAllBytes(selected).contentEquals(Files.readAllBytes(ciFirebaseFixturePath))) {
            "src/s5Benchmark/google-services.json is not ci/google-services.ci-fixture.json"
        }
    }
}
tasks.matching { it.name == "processS5BenchmarkGoogleServices" }.configureEach { dependsOn(verifyS5BenchmarkFirebaseFixture) }
// S7 E5d-J (`e5dj_j2_plan.claude.r3.md` R1): the journey fixture must resolve the checked-in non-production CI Firebase fixture,
// and nothing ahead of it. The plugin takes the first candidate that is a file, following links, so that is the one checked:
// it must be this fixture's own path, a regular file reached through no link, byte for byte the CI fixture. Nothing is printed.
val e5dJourneyGoogleServicesCandidates =
    com.google.gms.googleservices.GoogleServicesPlugin.getJsonFiles(
        "e5dJourney",
        emptyList(),
        project.projectDir
    ).map { candidate -> candidate.toPath().toAbsolutePath().normalize() }.distinct()
val e5dJourneyGoogleServicesPath = project.file("src/e5dJourney/google-services.json").toPath().toAbsolutePath().normalize()
check(e5dJourneyGoogleServicesPath in e5dJourneyGoogleServicesCandidates) {
    "google-services plugin no longer resolves src/e5dJourney/google-services.json for e5dJourney; review the E5d-J fixture."
}
val verifyE5dJourneyFirebaseFixture = tasks.register("verifyE5dJourneyFirebaseFixture") {
    doLast {
        val selected = e5dJourneyGoogleServicesCandidates.firstOrNull { candidate -> Files.isRegularFile(candidate) }
        check(selected == e5dJourneyGoogleServicesPath) {
            "e5dJourney would resolve $selected, not its own non-production Firebase fixture"
        }
        check(Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(selected)) {
            "src/e5dJourney/google-services.json must be a regular file, not a link"
        }
        check(selected.toRealPath() == selected) {
            "src/e5dJourney/google-services.json must not be reached through a linked directory"
        }
        check(Files.readAllBytes(selected).contentEquals(Files.readAllBytes(ciFirebaseFixturePath))) {
            "src/e5dJourney/google-services.json is not ci/google-services.ci-fixture.json"
        }
    }
}
tasks.matching { it.name == "processE5dJourneyGoogleServices" }.configureEach { dependsOn(verifyE5dJourneyFirebaseFixture) }
// S7 E5d-J (`e5dj_j2_plan.claude.r3.md` R2): the journey variant's own merged manifest, read after it is produced. Only that
// manifest and the network config it selects are read; no existing Test task, owner check or CI step depends on this one.
// AGP already owns check<Variant>Manifest, so this local check is named verify… like the Firebase one above.
val verifyE5dJourneyManifest = tasks.register("verifyE5dJourneyManifest") {
    dependsOn("processE5dJourneyManifest")
    val merged = layout.buildDirectory.file("intermediates/merged_manifests/e5dJourney/processE5dJourneyManifest/AndroidManifest.xml")
    val network = project.file("src/e5dJourney/res/xml/network_security_config.xml")
    inputs.file(merged).withPropertyName("e5dJourneyMergedManifest")
    inputs.file(network).withPropertyName("e5dJourneyNetworkConfig")
    doLast {
        val ns = "http://schemas.android.com/apk/res/android"
        fun parse(file: File) = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
        fun elements(parent: org.w3c.dom.Element, tag: String): List<org.w3c.dom.Element> =
            parent.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as org.w3c.dom.Element } }
        val manifest = parse(merged.get().asFile)
        val application = elements(manifest, "application").single()
        check(application.getAttributeNS(ns, "networkSecurityConfig") == "@xml/network_security_config") {
            "e5dJourney: the application no longer names @xml/network_security_config"
        }
        val meta = elements(application, "meta-data").associate { it.getAttributeNS(ns, "name") to it.getAttributeNS(ns, "value") }
        for ((name, value) in listOf(
            "com.jay.fxi.TOPIC_V2_RELEASE_ON" to "true",
            "firebase_analytics_collection_enabled" to "false",
            "firebase_crashlytics_collection_enabled" to "false",
            "firebase_messaging_auto_init_enabled" to "false"
        )) {
            check(meta[name] == value) { "e5dJourney: meta-data $name is ${meta[name]}, not $value" }
        }
        val control = "com.jay.fxi.e5djourney.permission.CONTROL"
        val permissions = elements(manifest, "permission").filter { it.getAttributeNS(ns, "name") == control }
        check(permissions.size == 1 && permissions.single().getAttributeNS(ns, "protectionLevel") == "signature") {
            "e5dJourney: $control must be declared once with protectionLevel signature"
        }
        // The control channel (`s7e5dj_j2b_channel.codex.r1.md` R3): one provider, one exact authority, exported, bound to the
        // permission, in the app's own process; the application not split off either; the old receiver gone.
        val controls = elements(application, "provider").filter { it.getAttributeNS(ns, "name") == "com.jay.fxi.e5djourney.E5dJourneyControlProvider" }
        check(controls.size == 1 && controls.single().getAttributeNS(ns, "authorities") == "com.jay.fxi.e5djourney.control" &&
            controls.single().getAttributeNS(ns, "exported") == "true" && controls.single().getAttributeNS(ns, "permission") == control &&
            !controls.single().hasAttributeNS(ns, "process") && !application.hasAttributeNS(ns, "process")) {
            "e5dJourney: the control provider must be one, exported at its one authority, bound to $control, in the app's own process"
        }
        check(elements(application, "receiver").none { it.getAttributeNS(ns, "name").contains("E5dJourneyControl") }) {
            "e5dJourney: the old control receiver is still declared"
        }
        val providers = elements(application, "provider").filter { it.getAttributeNS(ns, "name") == "com.jay.fxi.e5djourney.E5dJourneyLifecycleProvider" }
        check(providers.size == 1 && providers.single().getAttributeNS(ns, "exported") == "false" &&
            providers.single().getAttributeNS(ns, "authorities") == "com.jay.fxi.e5djourney.lifecycle" &&
            !providers.single().hasAttributeNS(ns, "process")) {
            "e5dJourney: the lifecycle provider must be one, not exported, at its authority, in the app's own process"
        }
        val config = parse(network)
        check(elements(config, "base-config").singleOrNull()?.getAttribute("cleartextTrafficPermitted") == "false") {
            "e5dJourney: the network config's base must keep cleartext off"
        }
        val domainConfigs = elements(config, "domain-config")
        check(domainConfigs.size == 1 && domainConfigs.single().getAttribute("cleartextTrafficPermitted") == "true") {
            "e5dJourney: exactly one cleartext domain config"
        }
        val domains = elements(domainConfigs.single(), "domain").map { it.textContent.trim() to it.getAttribute("includeSubdomains") }.toSet()
        check(domains == setOf("127.0.0.1" to "false", "localhost" to "false")) {
            "e5dJourney: cleartext only for 127.0.0.1 and localhost, without subdomains, not $domains"
        }
    }
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
        // S5-4 slice 4b: crash collection may open only where the other gates allow it; the local S5 fixture build type keeps it
        // closed. The fixture's benchmark selectors read test tags as resource ids; no other build type exposes them.
        buildConfigField("boolean", "CRASH_COLLECTION_ALLOWED", "true")
        buildConfigField("boolean", "SEMANTICS_RESOURCE_IDS", "false")
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
        // S5-4 slice 4b: the local S5 Macrobenchmark fixture. Release R8 rules with debug signing and not debuggable, D24 ON and
        // data-on; its own source set binds a loopback fixture server, one fixture identity and a fixed graph clock in place of
        // src/productionBindings. Crash collection stays closed and test tags become resource ids for the benchmark selectors.
        // Built locally only: never uploaded or published. Its name holds no "release", so release authorization does not apply.
        create("s5Benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
            buildConfigField("String", "REVENUECAT_API_KEY", "\"\"")
            buildConfigField("boolean", "BENCHMARK_NO_DATA_MODE", "false")
            buildConfigField("boolean", "TOPIC_V2_RELEASE_ON", "true")
            manifestPlaceholders["topicV2ReleaseOn"] = true
            buildConfigField("boolean", "CRASH_COLLECTION_ALLOWED", "false")
            buildConfigField("boolean", "SEMANTICS_RESOURCE_IDS", "true")
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
        // S7 E5d-J (`e5dj_design.claude.r3.md` §3.2): the local journey fixture. A behaviour journey, not a measurement, so it
        // starts from debug. It takes neither src/productionBindings nor the no-op hook accessor: its own source set supplies
        // the five bindings and the accessor. Built locally only, never uploaded; its name holds no "release".
        create("e5dJourney") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "REVENUECAT_API_KEY", "\"\"")
            buildConfigField("boolean", "BENCHMARK_NO_DATA_MODE", "false")
            buildConfigField("boolean", "TOPIC_V2_RELEASE_ON", "true")
            manifestPlaceholders["topicV2ReleaseOn"] = true
            buildConfigField("boolean", "CRASH_COLLECTION_ALLOWED", "false")
            buildConfigField("boolean", "SEMANTICS_RESOURCE_IDS", "true")
            // S7 E5d-J J2b2 (`s7e5dj_j2b2_design.codex.r2.md` §2-1): the two graph contract documents the fixture serves, named
            // here so the fixture's own sources stay free of their names; the server opens them by these keys only.
            buildConfigField("String", "E5DJ_GRAPH_CATALOG_ASSET", "\"catalog-krx-hidden.json\"")
            buildConfigField("String", "E5DJ_GRAPH_USD_1D_ASSET", "\"usd-1d-krx-hidden.json\"")
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
    }
    // S5-4 slice 2: bindings that a fixture build type replaces with its own. The four existing build types take them, and
    // their unit tests the matching tests, from these shared directories; a build type without a binding fails Hilt's compile.
    sourceSets {
        listOf("debug", "release", "ciMinified", "benchmark").forEach { type ->
            getByName(type).java.srcDir("src/productionBindings/java")
            getByName("test" + type.replaceFirstChar { it.uppercase() }).java.srcDir("src/testProductionBindings/java")
        }
        // S5-4 slice 4b: the S5 fixture reads the one frozen fixture source by reference; nothing is copied.
        getByName("s5Benchmark").assets.srcDir("src/benchmark/assets/s5_4")
        // S7 E5d-J: the journey fixture serves the checked-in free snapshot contract documents by reference; nothing is copied.
        getByName("e5dJourney").assets.srcDir("src/test/resources/contracts/v2/free")
        // S7 E5d-J J2b2: and, for the premium journeys, the topic snapshots, the graph documents and the bank alert documents.
        getByName("e5dJourney").assets.srcDir("src/test/resources/contracts/v2/topic")
        getByName("e5dJourney").assets.srcDir("src/test/resources/contracts/v2/graph")
        getByName("e5dJourney").assets.srcDir("src/test/resources/contracts/v2/alerts")
        // S7 E5d-J: the preview host's no-op hook accessor. Every build type here compiles it; a build type that supplies its
        // own accessor (the local journey fixture) must not take this one, or the accessor is declared twice.
        listOf("debug", "release", "ciMinified", "benchmark", "s5Benchmark").forEach { type ->
            getByName(type).java.srcDir("src/e5dHooksDefault/java")
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

// Owner structure tests inspect all four merged manifests (S5-4 slice 4b added s5Benchmark), including library declarations.
// Build their current inputs even when only one unit-test variant is requested, and invalidate
// cached test results when any merged manifest changes. This does not assemble the other variants.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn("processDebugManifest", "processBenchmarkManifest", "processCiMinifiedManifest", "processS5BenchmarkManifest")
    inputs.files(
        layout.buildDirectory.file("intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"),
        layout.buildDirectory.file("intermediates/merged_manifests/benchmark/processBenchmarkManifest/AndroidManifest.xml"),
        layout.buildDirectory.file("intermediates/merged_manifests/ciMinified/processCiMinifiedManifest/AndroidManifest.xml"),
        layout.buildDirectory.file("intermediates/merged_manifests/s5Benchmark/processS5BenchmarkManifest/AndroidManifest.xml")
    ).withPropertyName("controlOwnerMergedManifests")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}

// S7 E5d-J R6 ③④ (`s7e5dj_j2a.codex.r1.md` Q2-A): what AGP finally resolves as six variants' inputs — the static Java, Kotlin,
// Java-resource, Android resource, asset and manifest locations, each lexical and canonical, and the final debuggable, minify
// and resource-shrink values — one report per variant for E5dJourneyStructureTest. Like `:app:sourceSets`, it reads the variant
// model only: nothing is compiled, merged or packaged, and no release task enters the graph, so the public release gate stays
// shut. Generated directories (`all`) are left out on purpose, since resolving them would run their generators; the structure
// test's text lock covers the calls that add one.
//
// Android resources are the one exception. AGP's static resource layers include the resValues generator's output, and Gradle
// refuses to read them before generate<Variant>ResValues runs, which for release would put a release task in the graph. So
// every variant also reports `res-dsl`, the resource directories of its own source sets (main and its build type) as the DSL
// finally holds them, and the five variants other than release report the model's `res` as well, run after their own
// resValues generator. The test checks the two agree, generated directories aside, wherever both exist.
abstract class E5dJourneyVariantInputs : DefaultTask() {
    @get:Input
    abstract val entries: MapProperty<String, List<String>>

    @get:OutputDirectory
    abstract val reports: DirectoryProperty

    @TaskAction
    fun write() {
        val dir = reports.get().asFile
        dir.listFiles()?.forEach { it.delete() }
        for ((variant, lines) in entries.get()) {
            File(dir, "$variant.txt").writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        }
    }
}
val e5dJourneyIsolationVariants = listOf("debug", "release", "ciMinified", "benchmark", "s5Benchmark", "e5dJourney")
val reportE5dJourneyVariantInputs = tasks.register<E5dJourneyVariantInputs>("reportE5dJourneyVariantInputs") {
    reports.set(layout.buildDirectory.dir("intermediates/e5dj_variant_inputs"))
}
androidComponents {
    onVariants { variant ->
        if (variant.name !in e5dJourneyIsolationVariants) return@onVariants
        fun location(kind: String, file: File) =
            "$kind\t${file.toPath().toAbsolutePath().normalize()}\t${file.canonicalPath}"
        val located = variant.sources
        val lines = objects.listProperty(String::class.java)
        lines.add("variant\t${variant.name}\t${variant.buildType}")
        lines.add("debuggable\t${variant.debuggable}")
        lines.add("minify\t${variant.isMinifyEnabled}")
        lines.add("shrink\t${variant.shrinkResources}")
        // The invocation's own boundary (`s7e5dj_j2a.codex.r2.md` Q2-A): no included build, and no init script but those CI's
        // gradle/actions/setup-gradle writes into the Gradle user home's init.d. Each init script is reported by its canonical
        // path, with the user home, for the test to name.
        val startParameter = gradle.startParameter
        val initScripts = startParameter.allInitScripts
        lines.add("initScripts\t${initScripts.size}")
        lines.add("gradleUserHome\t${startParameter.gradleUserHomeDir.canonicalPath}")
        lines.addAll(initScripts.map { "initScript\t${it.canonicalPath}" })
        lines.add("includedBuilds\t${gradle.includedBuilds.size}")
        for ((kind, flat) in listOf("java" to located.java, "kotlin" to located.kotlin, "resources" to located.resources)) {
            if (flat == null) lines.add("$kind\tabsent") else lines.addAll(flat.static.map { dirs -> dirs.map { location(kind, it.asFile) } })
        }
        val layers = buildList {
            add("assets" to located.assets)
            if (variant.name != "release") add("res" to located.res)
        }
        for ((kind, layered) in layers) {
            if (layered == null) lines.add("$kind\tabsent")
            else lines.addAll(layered.static.map { found -> found.flatten().map { location(kind, it.asFile) } })
        }
        val ownSourceSets = listOf("main", checkNotNull(variant.buildType) { "${variant.name}: no build type" })
        lines.addAll(provider { ownSourceSets.flatMap { android.sourceSets.getByName(it).res.srcDirs }.map { location("res-dsl", it) } })
        lines.addAll(located.manifests.all.map { files -> files.map { location("manifest", it.asFile) } })
        reportE5dJourneyVariantInputs.configure { entries.put(variant.name, lines) }
    }
}

// S7 E5d-J R6 (`s7e5dj_j2a.codex.r1.md` Q2-B): E5dJourneyStructureTest reads the variant reports above and these trees and
// scripts from disk. They are its inputs, so any change, a file added or removed included, reruns it. Secret-named files are
// left out: the test never reads them, and Gradle would otherwise hash them.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    inputs.files(reportE5dJourneyVariantInputs).withPropertyName("e5dJourneyVariantInputs")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(fileTree("src") {
        include(listOf("main", "productionBindings", "e5dHooksDefault", "debug", "release", "ciMinified", "benchmark", "s5Benchmark",
            "e5dJourney").map { "$it/**" })
        exclude("**/google-services.json", "**/GoogleService-Info.plist", "**/*.p8", "**/*adminsdk*", "**/.env",
            "**/local.properties", "**/*.jks")
    }).withPropertyName("e5dJourneyStructureTrees").withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(
        "build.gradle.kts", rootProject.file("build.gradle.kts"), rootProject.file("settings.gradle.kts"),
        rootProject.file("gradle/libs.versions.toml"), rootProject.file("e5dJourney/build.gradle.kts"),
        rootProject.file("e5dJourney/src/main/AndroidManifest.xml"), rootProject.file("macrobenchmark/build.gradle.kts"),
        // Absent today; appearing, either would build code onto every script's classpath.
        rootProject.file("buildSrc"), rootProject.file("build-logic")
    ).withPropertyName("e5dJourneyStructureScripts").withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    // S7 U3a (`s7u3a1_contract.codex.r1.md` Q6): E5dJourneyJ13GoldenDataTest reads the journey's copies of the FCM goldens from
    // this file of the test module and compares them with the corpus, so a change to it reruns the test.
    inputs.file(rootProject.file("e5dJourney/src/main/kotlin/com/jay/fxi/e5djourney/test/J13FcmDisplayTest.kt"))
        .withPropertyName("e5dJourneyJ13GoldenCopies").withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    // S7 U3a-2: the same test reads J14's own copies and its `deliver` from this file.
    inputs.file(rootProject.file("e5dJourney/src/main/kotlin/com/jay/fxi/e5djourney/test/J14FcmReturnAndTapTest.kt"))
        .withPropertyName("e5dJourneyJ14GoldenCopies").withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("e5dJourney/src/main/kotlin/com/jay/fxi/e5djourney/test/J16SourceFcmDisplayTest.kt"))
        .withPropertyName("e5dJourneyJ16GoldenCopies").withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    // The names of every Gradle script in the repository (`s7e5dj_j2a.codex.r3.md` C), walked as the structure test walks
    // them — build and tool directories skipped, links not followed, names only — so a script added or removed anywhere reruns it.
    inputs.property("e5dJourneyRepoScripts", provider { e5dJourneyRepoScripts() })
}

fun e5dJourneyRepoScripts(): List<String> {
    val repo = rootProject.projectDir.toPath()
    val skipped = setOf("build", ".gradle", ".git", ".idea", ".kotlin")
    val found = ArrayList<String>()
    Files.walkFileTree(repo, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) =
            if (dir != repo && dir.fileName.toString() in skipped) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            val name = file.fileName.toString()
            if (name.endsWith(".gradle.kts") || name.endsWith(".gradle")) found += repo.relativize(file).toString().replace('\\', '/')
            return FileVisitResult.CONTINUE
        }
    })
    return found.sorted()
}

// S5-4 A: the benchmark's tether fixture has a single source under src/benchmark/assets, which the unit tests read by path.
// Invalidate cached test results when it changes; it is never copied into main or debug assets.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("src/benchmark/assets/s5_4")).withPropertyName("s54BenchmarkFixture")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}

// S4 CUT-C01·C02: the production-composition cutover acceptance runs on the real clock for many minutes, so no ordinary
// unit-test task runs it; its own task runs it once per candidate in a required CI job, which checks the exact case list.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (name != "testCutoverAcceptance" && name != "testC02Measurement") {
        filter.excludeTestsMatching("*.PremiumGraphCutoverAcceptanceTest")
    }
}
tasks.register<org.gradle.api.tasks.testing.Test>("testCutoverAcceptance") {
    val debugUnitTestForAcceptance = tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest")
    group = "verification"
    description = "Runs only the S4 cutover acceptance harness (production composition, real clock)"
    dependsOn("compileDebugUnitTestKotlin", "compileDebugUnitTestJavaWithJavac", "processDebugUnitTestJavaRes")
    // Read from the configured unit test task, not mapped from its provider: a mapped task provider carries a dependency on
    // that task, which would run the whole unit suite before this one. These collections depend only on what compiles them.
    testClassesDirs = debugUnitTestForAcceptance.get().testClassesDirs
    classpath = debugUnitTestForAcceptance.get().classpath
    filter.includeTestsMatching("*.PremiumGraphCutoverAcceptanceTest")
    filter.isFailOnNoMatchingTests = true
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/cutoverAcceptance"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/cutoverAcceptance"))
    // S4 CUT-C02 (b′): the same class's measurement rows run only in testC02Measurement, so this list stays the cutover's.
    useJUnit { excludeCategories("com.jay.fxi.budget.C02Measurement") }
    // Every candidate runs it again: its evidence is the physical sends of this run, never a cached result.
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}
// S4 CUT-C02 (b′) (cut_c02_bprime_agreed.r1.md, cut_c02_m1_agreed.r1.md): the measurement rows of the same harness, on the
// real clock. Each row passes or fails on its own budget; its list and table are tools/contracts/c02_measurement_*.
tasks.register<org.gradle.api.tasks.testing.Test>("testC02Measurement") {
    val debugUnitTestForMeasurement = tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest")
    group = "verification"
    description = "Runs only the S4 CUT-C02 measurement rows (production composition, real clock)"
    dependsOn("compileDebugUnitTestKotlin", "compileDebugUnitTestJavaWithJavac", "processDebugUnitTestJavaRes")
    testClassesDirs = debugUnitTestForMeasurement.get().testClassesDirs
    classpath = debugUnitTestForMeasurement.get().classpath
    filter.includeTestsMatching("*.PremiumGraphCutoverAcceptanceTest")
    filter.isFailOnNoMatchingTests = true
    useJUnit { includeCategories("com.jay.fxi.budget.C02Measurement") }
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/c02Measurement"))
    // Each row's own output sits in its testcase element, so the table links a row to its timeline and verdict.
    reports.junitXml.isOutputPerTestCase = true
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/c02Measurement"))
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
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

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (name != "testLifecycleAccumulation") {
        filter.excludeTestsMatching("*.LifecycleAccumulationLongTest")
    }
}
tasks.register<org.gradle.api.tasks.testing.Test>("testLifecycleAccumulation") {
    val debugUnitTestForAccumulation = tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest")
    group = "verification"
    description = "Runs only the opt-in lifecycle accumulation suite"
    dependsOn("compileDebugUnitTestKotlin", "compileDebugUnitTestJavaWithJavac", "processDebugUnitTestJavaRes")
    testClassesDirs = files(debugUnitTestForAccumulation.map { it.testClassesDirs })
    classpath = files(debugUnitTestForAccumulation.map { it.classpath })
    filter.includeTestsMatching("*.LifecycleAccumulationLongTest")
    filter.isFailOnNoMatchingTests = true
    systemProperty("fxi.lifecycle.report", layout.buildDirectory.file("reports/lifecycle-accumulation.txt").get().asFile.absolutePath)
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
    // S5-4 C: the resource-id bridge test looks the page tags up through UIAutomator (same 2.4.0 as :macrobenchmark).
    androidTestImplementation(libs.androidx.uiautomator)
    // S0-f D31: the target benchmark APK must contain ProfileInstaller 1.4.1 as a
    // real resolved dependency. A catalog pin alone does not satisfy the gate.
    add("benchmarkImplementation", libs.androidx.profileinstaller)
    // S5-4 slice 4b: the S5 fixture's own runtime: ProfileInstaller as in benchmark, and the loopback fixture server.
    add("s5BenchmarkImplementation", libs.androidx.profileinstaller)
    add("s5BenchmarkImplementation", libs.okhttp.mockwebserver)
    // S7 E5d-J: the journey fixture's loopback server.
    add("e5dJourneyImplementation", libs.okhttp.mockwebserver)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
