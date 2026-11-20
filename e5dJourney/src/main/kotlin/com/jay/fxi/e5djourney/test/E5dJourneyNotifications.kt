package com.jay.fxi.e5djourney.test

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jay.fxi.e5djourney.test.E5dJourneyRun.Companion.texts

/**
 * S7 U3a (`u3a_design.claude.r2.md` §3; `s7u3a_design.codex.r1.md` Q3): this test APK's own observer of the app's notification
 * posts. Every onNotificationPosted callback received for the app's package is recorded in order — a second post under the same
 * key as well, which the active list cannot show — with its tag, id, channel, title and post time. Connections and disconnections are counted, so a window during
 * which the listener was not connected throughout is known and is not a result.
 */
class E5dJourneyNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() = synchronized(lock) {
        connections++
        connected = true
    }

    override fun onListenerDisconnected() = synchronized(lock) {
        disconnections++
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != E5dJourneyControlClient.PACKAGE) return
        synchronized(lock) {
            posted += Posted(posted.size, sbn.tag, sbn.id, sbn.notification.channelId,
                sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(), sbn.postTime)
        }
    }

    data class Posted(val sequence: Int, val tag: String?, val id: Int, val channel: String?, val title: String?, val postTime: Long)

    /** The listener's connection as read at one moment, with how many posts it has kept. */
    data class State(val connected: Boolean, val connections: Int, val disconnections: Int, val posted: Int)

    companion object {
        private val lock = Any()
        private var connected = false
        private var connections = 0
        private var disconnections = 0
        private val posted = ArrayList<Posted>()

        fun state(): State = synchronized(lock) { State(connected, connections, disconnections, posted.size) }

        /** The connection state and the posts after the first [from], read under one lock: one moment, not two. */
        fun snapshot(from: Int): Pair<State, List<Posted>> = synchronized(lock) { State(connected, connections, disconnections, posted.size) to posted.drop(from) }
    }
}

/**
 * S7 U3a: one observation window over the app's notifications, its bank settings GETs and the service's own type log. A window
 * is opened after the app's notifications were cleared, none is active and no post arrived for [QUIET_MILLIS]; it is closed
 * [TRAILING_MILLIS] after the journey's action. What it gives is what was observed in between: every post the listener got,
 * the notifications active at the end, the `GET /api/notification-settings` lines the fixture server wrote, and the
 * `FXiMessaging` "Message received" lines of the app's process. A post count is the listener's; the active list is only what
 * was left at the end. A type line is positive evidence of the app's callback; none is "not observed", not a count of zero
 * calls.
 */
internal class E5dJourneyNotifications(private val run: E5dJourneyRun) {
    private val component = "${run.instrumentation.context.packageName}/${E5dJourneyNotificationListener::class.java.name}"

    data class Active(val tag: String?, val id: Int, val channel: String?, val title: String?, val postTime: Long)

    class Window(val from: Int, val state: E5dJourneyNotificationListener.State, val startedAt: Long, val gets: Int)

    class Observation(val posted: List<E5dJourneyNotificationListener.Posted>, val active: List<Active>, val gets: Int, val typeLogs: List<String>)

    /** Lets this test APK's listener see notifications and waits for it to be connected: a premise, not a result. */
    fun allow() {
        run.device.executeShellCommand("cmd notification allow_listener $component")
        val deadline = System.currentTimeMillis() + CONNECT_MILLIS
        while (!E5dJourneyNotificationListener.state().connected) {
            check(System.currentTimeMillis() < deadline) { "premise: 알림 listener 가 접속하지 않았다" }
            Thread.sleep(100)
        }
    }

    /** Takes the access back. */
    fun revoke() {
        run.device.executeShellCommand("cmd notification disallow_listener $component")
    }

    /** The app's active notifications and the app's clock at that reading. */
    fun active(nonce: String): Pair<List<Active>, Long> {
        val body = run.client.ack("notifications", nonce)
        val list = body.getJSONArray("active")
        val active = (0 until list.length()).map { list.getJSONObject(it) }.map {
            Active(if (it.isNull("tag")) null else it.getString("tag"), it.getInt("id"), if (it.isNull("channel")) null else it.getString("channel"),
                if (it.isNull("title")) null else it.getString("title"), it.getLong("postTime"))
        }
        return active to body.getLong("now")
    }

    fun settingsGets(nonce: String): Int = texts(run.client.dump(nonce).lines, "http").count { it.startsWith("GET /api/notification-settings ") }

    /** Clears, waits for none active and for the listener to be quiet, and fixes where the window starts. */
    fun open(nonce: String): Window {
        run.client.ack("notifications-clear", nonce)
        val deadline = System.currentTimeMillis() + OPEN_MILLIS
        while (active(nonce).first.isNotEmpty()) {
            check(System.currentTimeMillis() < deadline) { "premise: clear 뒤에도 active 알림이 남았다" }
            Thread.sleep(200)
        }
        var last = E5dJourneyNotificationListener.state().posted
        var quietSince = System.currentTimeMillis()
        while (System.currentTimeMillis() - quietSince < QUIET_MILLIS) {
            check(System.currentTimeMillis() < deadline + QUIET_MILLIS) { "premise: 알림 게시가 잦아들지 않았다" }
            Thread.sleep(100)
            val now = E5dJourneyNotificationListener.state().posted
            if (now != last) { last = now; quietSince = System.currentTimeMillis() }
        }
        val (still, startedAt) = active(nonce)
        check(still.isEmpty()) { "premise: 관측창을 열 때 active 알림이 있다 $still" }
        val state = E5dJourneyNotificationListener.state()
        check(state.connected) { "premise: 관측창을 열 때 listener 가 접속돼 있지 않다" }
        return Window(state.posted, state, startedAt, settingsGets(nonce))
    }

    /**
     * Waits out the trailing time, then fixes the listener's connection state and the window's posts in one reading. The active
     * list, the settings GETs and the type lines are read after that, one after another; a listener whose connection changed
     * during the window or while those were read is a failure (not observed), never a result.
     */
    fun close(nonce: String, window: Window, pid: Int): Observation {
        Thread.sleep(TRAILING_MILLIS)
        val (state, posted) = E5dJourneyNotificationListener.snapshot(window.from)
        fun same(now: E5dJourneyNotificationListener.State) =
            now.connected && now.connections == window.state.connections && now.disconnections == window.state.disconnections
        check(same(state)) { "관측창 동안 listener 접속이 끊겼다(미확인): ${window.state} -> $state" }
        val active = active(nonce).first
        val gets = settingsGets(nonce) - window.gets
        val types = typeLogs(pid, window.startedAt)
        val end = E5dJourneyNotificationListener.state()
        check(same(end)) { "관측창을 닫는 동안 listener 접속이 바뀌었다(미확인): ${window.state} -> $end" }
        return Observation(posted, active, gets, types)
    }

    /** The type each `FXiMessaging` "Message received" line of process [pid] names, from [since] on the device's clock. */
    fun typeLogs(pid: Int, since: Long): List<String> =
        run.device.executeShellCommand("logcat -d -v epoch -s FXiMessaging:D").lines().mapNotNull { line ->
            val match = TYPE_LINE.find(line) ?: return@mapNotNull null
            val (seconds, millis, linePid, type) = match.destructured
            if (linePid.toInt() == pid && seconds.toLong() * 1_000 + millis.toLong() >= since) type else null
        }

    companion object {
        const val CONNECT_MILLIS = 20_000L
        const val OPEN_MILLIS = 10_000L
        const val QUIET_MILLIS = 1_500L
        const val TRAILING_MILLIS = 8_000L
        const val SDK_TAG_PREFIX = "FCM-Notification:"
        const val CHANNEL = "rate_alerts"
        private val TYPE_LINE = Regex("""^\s*(\d+)\.(\d{3})\s+(\d+)\s+\d+\s+D\s+FXiMessaging\s*:\s*Message received: type=(\S+)""")
    }
}
