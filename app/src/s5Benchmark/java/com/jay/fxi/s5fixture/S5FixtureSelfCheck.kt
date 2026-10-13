package com.jay.fxi.s5fixture

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** One user notice a self-check registration heard: on the main thread or not, and whether the user was signed out then. */
internal data class S5Notice(val onMainThread: Boolean, val signedOut: Boolean)

/** What the start-up self-check saw in the real s5Benchmark process. A notice that did not come in time is null. */
internal data class S5SelfCheckSeen(
    val bindingsAreTheIdentity: Boolean,
    val closedReplay: S5Notice?,
    val openReplay: S5Notice?,
    val openSignIn: S5Notice?,
    val closedAfterClose: Int
)

/** The self-check's judgement: an empty list passes. Pure, so the JVM checks every way it can fail. */
internal fun judgeS5SelfCheck(seen: S5SelfCheckSeen): List<String> = buildList {
    if (!seen.bindingsAreTheIdentity) add("the auth bindings are not the one fixture identity")
    if (seen.closedReplay != S5Notice(onMainThread = true, signedOut = true)) add("registration X: no signed-out replay on the main thread")
    if (seen.openReplay != S5Notice(onMainThread = true, signedOut = true)) add("registration Y: no signed-out replay on the main thread")
    if (seen.openSignIn != S5Notice(onMainThread = true, signedOut = false)) add("registration Y: no sign-in notice on the main thread")
    if (seen.closedAfterClose != 0) add("registration X heard ${seen.closedAfterClose} notice(s) after its close")
}

/** A registration that records each notice it hears. */
internal class S5NoticeProbe(private val identity: S5FixtureIdentity, private val isMainThread: () -> Boolean) {
    private val notices = Channel<S5Notice>(Channel.UNLIMITED)
    private val heard = AtomicInteger()

    val count: Int get() = heard.get()

    fun onChange() {
        heard.incrementAndGet()
        notices.trySend(S5Notice(isMainThread(), identity.currentUser() == null))
    }

    suspend fun next(timeoutMillis: Long): S5Notice? = withTimeoutOrNull(timeoutMillis) { notices.receive() }
}

/** The line S5-4 4d looks for in the current run's log; it carries no value and no token. */
internal const val S5_SELF_CHECK_PASSED = "S5 fixture self-check passed"
