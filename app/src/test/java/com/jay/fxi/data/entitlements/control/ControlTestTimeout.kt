package com.jay.fxi.data.entitlements.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A suspended test call must fail locally, so its finally can unblock the peer it is testing. */
internal suspend fun <T> controlTestTimeout(
    label: String, timeoutMillis: Long = 10_000, block: suspend CoroutineScope.() -> T
): T = try {
    withTimeout(timeoutMillis, block)
} catch (timeout: TimeoutCancellationException) {
    throw AssertionError("$label did not complete within $timeoutMillis ms", timeout)
}

/** Includes direct facade calls and child coroutines; intentionally held storage has a larger budget. */
internal fun <T> runReleaseTest(block: suspend CoroutineScope.() -> T): T = runBlocking {
    controlTestTimeout("release test scenario", 30_000, block)
}

/** Cleanup still joins after the test is cancelled, with its own finite deadline. */
internal suspend fun Job.cancelAndJoinForTest() = withContext(NonCancellable) {
    controlTestTimeout("caller cancellation and join") { this@cancelAndJoinForTest.cancelAndJoin() }
}
