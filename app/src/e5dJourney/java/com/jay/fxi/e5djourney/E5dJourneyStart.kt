package com.jay.fxi.e5djourney

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.security.NetworkSecurityPolicy
import com.jay.fxi.BuildConfig
import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.time.SystemAppClock
import kotlinx.coroutines.runBlocking

/**
 * S7 E5d-J (`e5dj_j2_plan.claude.r3.md` §2 E5dJourneyStart, R7): the start acknowledgement, each item on its own and none
 * standing in for another. The variant's real values (BuildConfig, the installed package's debuggable flag and manifest
 * meta-data, and whether one named production class can still be loaded under its original name); admission; the graph the
 * production consumers use resolving all three auth bindings to the one fixture identity, with the current user, the current
 * identity and a token for it naming the same owner and generation; the endpoints actually resolved against this process's
 * loopback server, never a fixture class name; the wall clock the qualifier resolves; the journey accessor's selection; and the
 * three lifecycle acknowledgements (R5). The DI comparisons read the application's actual graph. Recorder counters and fixture
 * state reports require their corresponding event and journey assertions; the class-name probe alone does not prove that
 * minification is disabled.
 *
 * The cleartext answers are the platform's network security policy in force in this process, i.e. the network config resource
 * the packaged variant actually selected (`s7e5dj_j2a.codex.r1.md` Q2-A): loopback allowed, main's news-image exception and any
 * other host not.
 */
internal object E5dJourneyStart {

    fun ack(context: Context, entry: E5dJourneyEntryPoint): Map<String, Any?> {
        val identity = entry.identity()
        val endpoints = entry.serverEndpoints()
        val server = entry.server().endpoints
        val current = identity.currentIdentity()
        val token = current?.let { runBlocking { runCatching { entry.authTokenSource().fetchToken(it, forceRefresh = false) }.getOrNull() } }
        val installed = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        val meta = installed.metaData
        @Suppress("DEPRECATION")
        fun metaValue(name: String): Any? = meta?.get(name)
        val policy = NetworkSecurityPolicy.getInstance()
        return mapOf(
            "buildType" to BuildConfig.BUILD_TYPE,
            "debug" to BuildConfig.DEBUG,
            "topicV2ReleaseOn" to BuildConfig.TOPIC_V2_RELEASE_ON,
            "benchmarkNoDataMode" to BuildConfig.BENCHMARK_NO_DATA_MODE,
            "revenueCatKeyEmpty" to BuildConfig.REVENUECAT_API_KEY.isEmpty(),
            "crashCollectionAllowed" to BuildConfig.CRASH_COLLECTION_ALLOWED,
            "semanticsResourceIds" to BuildConfig.SEMANTICS_RESOURCE_IDS,
            "debuggableFlag" to (installed.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0),
            "unminified" to runCatching { Class.forName("com.jay.fxi.ui.free.FreeSnapshotScreenKt") }.isSuccess,
            "manifestTopicV2ReleaseOn" to metaValue("com.jay.fxi.TOPIC_V2_RELEASE_ON"),
            "manifestAnalytics" to metaValue("firebase_analytics_collection_enabled"),
            "manifestCrashlytics" to metaValue("firebase_crashlytics_collection_enabled"),
            "manifestFcmAutoInit" to metaValue("firebase_messaging_auto_init_enabled"),
            "cleartextLoopback" to policy.isCleartextTrafficPermitted("127.0.0.1"),
            "cleartextLocalhost" to policy.isCleartextTrafficPermitted("localhost"),
            "cleartextNewsImage" to policy.isCleartextTrafficPermitted("newsimage.einfomax.co.kr"),
            "cleartextOther" to policy.isCleartextTrafficPermitted("example.com"),
            "admissionOpen" to ReleaseAdmission.isOpen,
            "authIsIdentity" to (entry.authUserObservation() === identity && entry.authTokenSource() === identity &&
                entry.authFenceStream() === identity),
            "currentUser" to identity.currentUser()?.uid,
            "identityUid" to current?.uid,
            "identityGeneration" to current?.authGeneration,
            "tokenForIdentity" to (token != null && token == E5dJourneyIdentity.TOKEN),
            "endpointsAreServer" to (endpoints.restBaseUrl == server.restBaseUrl && endpoints.webSocketUrl == server.webSocketUrl),
            "baseUrl" to endpoints.restBaseUrl,
            "webSocketUrl" to endpoints.webSocketUrl,
            "clockIsSystem" to (entry.graphWallClock() === SystemAppClock),
            "accessorSelections" to E5dJourneyHooks.selections,
            "providerAck" to E5dJourneyLifecycle.providerAck,
            "processAck" to E5dJourneyLifecycle.processAck,
            "activityAcks" to E5dJourneyLifecycle.activityAcks,
            "answer" to entry.server().answerState(),
            "phase" to E5dJourneyRecord.currentPhase()
        )
    }
}
