package com.jay.fxi.e5djourney.test

import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeoutException
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5d-J J2b (`s7e5dj_j2b_channel.codex.r1.md` R1, R3 and the list of what the new channel must be shown to do): the two
 * boundaries of the control channel that no journey crosses on its own.
 * - A caller without the CONTROL permission — the device shell, which the app's signature does not cover — is refused with a
 *   SecurityException naming the permission, and gets no result. Which layer refuses (the system on acquiring the provider, or
 *   the provider's own check in `call`) is not told apart here; the provider's own check is locked by the structure test.
 * - A call given up on is never trusted again (`s7e5dj_j2b1.codex.r1.md` F1): every timeout leaves the package owing an
 *   isolation, whether the client knew the process (a dump for a known nonce, J01_1) or not (a new client's first discovery,
 *   J01_2). Until the next launch force-stops the package and its process is gone, nothing is sent to the app — a discovery is
 *   refused on this side; afterwards the discovery answers from a new process. A 0 ms wait brings the timeout about; it does
 *   not show the remote call itself running late, and that a late answer is never read is a property of the client's code
 *   (one Future per request, nothing sent once spoiled), not of this run.
 * - Every answer carries a positive Int pid, checked with the rest before the body is used (F2): a well-formed answer passes,
 *   and one without a pid, or with a pid of another type or not positive, fails (J01_3).
 */
@RunWith(AndroidJUnit4::class)
class J01ChannelTest {
    private val run = E5dJourneyRun()

    @After
    fun close() {
        run.close()
    }

    @Test
    fun J01_0_aCallerWithoutThePermissionIsRefused() {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { "premise: a shell command's stderr needs API 31" }
        run.launch()
        run.waitText(SCREEN_TITLE, timeoutMillis = COLD_START_MILLIS)
        run.run { _ ->
            val (out, err) = shell("content call --uri ${E5dJourneyControlClient.URI} --method ${E5dJourneyControlClient.DISCOVER}")
            assertTrue("SecurityException 으로 거절된다: out=$out err=$err", "SecurityException" in err && CONTROL in err)
            assertFalse("결과가 없다: $out", "Result:" in out)
        }
    }

    @Test
    fun J01_1_aCallGivenUpOnSpoilsTheClient_andItsProcessIsNotReadAgain() {
        run.launch()
        run.waitText(SCREEN_TITLE, timeoutMillis = COLD_START_MILLIS)
        val nonce = run.discover().getString("nonce")
        val impatient = E5dJourneyControlClient(run.instrumentation.context, timeoutMillis = 0)
        try {
            val thrown = runCatching { impatient.send("dump", nonce) { putExtra("cursor", 0); putExtra("max", 1) } }.exceptionOrNull()
            assertTrue("premise: 0 ms 안에 답이 오지 않았다: $thrown", thrown is IllegalStateException && thrown.cause is TimeoutException)
            assertTrue("spoiled", impatient.isSpoiled)
            assertTrue("그 process 가 적혔다", nonce in E5dJourneyControlClient.spoiled)
            val again = runCatching { impatient.send(E5dJourneyControlClient.DISCOVER, null) }.exceptionOrNull()
            assertTrue("더 보내지 않는다: $again", again is IllegalStateException && "spoiled" in again.message.orEmpty())
            assertTrue("격리 필요가 남았다", E5dJourneyControlClient.isolationOwed)
        } finally {
            impatient.close()
        }
        run.launch()
        run.waitText(SCREEN_TITLE, timeoutMillis = COLD_START_MILLIS)
        assertFalse("격리가 끝났다", E5dJourneyControlClient.isolationOwed)
        assertTrue("force-stop 한 process 로 남았다", nonce in E5dJourneyControlClient.isolated && nonce !in E5dJourneyControlClient.spoiled)
        val renewed = run.discover().getString("nonce")
        assertNotEquals("새 process", nonce, renewed)
    }

    @Test
    fun J01_2_aFirstDiscoveryGivenUpOnStillIsolatesThePackage() {
        run.launch()
        run.waitText(SCREEN_TITLE, timeoutMillis = COLD_START_MILLIS)
        // The nonce is kept by an observer; the client that times out has asked nothing before and knows no process.
        val observed = run.discover().getString("nonce")
        val impatient = E5dJourneyControlClient(run.instrumentation.context, timeoutMillis = 0)
        try {
            val thrown = runCatching { impatient.send(E5dJourneyControlClient.DISCOVER, null) }.exceptionOrNull()
            assertTrue("premise: 0 ms 안에 답이 오지 않았다: $thrown", thrown is IllegalStateException && thrown.cause is TimeoutException)
            assertTrue("격리 필요가 남았다", E5dJourneyControlClient.isolationOwed)
            assertFalse("이 client 는 process 를 몰랐다", observed in E5dJourneyControlClient.spoiled)
        } finally {
            impatient.close()
        }
        val refused = runCatching { run.discover() }.exceptionOrNull()
        assertTrue("격리 전에는 앱에 보내지 않는다: $refused", refused is IllegalStateException && "premise" in refused.message.orEmpty())
        run.launch()
        run.waitText(SCREEN_TITLE, timeoutMillis = COLD_START_MILLIS)
        assertFalse("격리가 끝났다", E5dJourneyControlClient.isolationOwed)
        val renewed = run.discover().getString("nonce")
        assertNotEquals("새 process", observed, renewed)
    }

    @Test
    fun J01_3_everyAnswerNeedsAPositiveIntPid() {
        fun body(pid: Any?, withPid: Boolean = true) = JSONObject().apply {
            put("request", REQUEST); put("command", "phase"); put("nonce", NONCE)
            if (withPid) put("pid", pid)
        }
        fun answer(body: JSONObject) = Bundle().apply {
            putInt(E5dJourneyControlClient.RESULT_CODE, E5dJourneyControlClient.ACK)
            putString(E5dJourneyControlClient.RESULT_BODY, body.toString())
        }
        fun checked(body: JSONObject) = runCatching { E5dJourneyControlClient.checkAnswer("phase", REQUEST, NONCE, answer(body)) }
        assertTrue("정상 pid 는 통과한다", checked(body(4321)).getOrNull()?.acked == true)
        for ((what, broken) in listOf(
            "pid 없음" to body(null, withPid = false),
            "문자열 pid" to body("4321"),
            "0" to body(0),
            "음수" to body(-1),
            "소수" to body(43.5),
            "null" to body(JSONObject.NULL)
        )) {
            val thrown = checked(broken).exceptionOrNull()
            assertTrue("$what 은(는) 실패한다: $thrown", thrown is IllegalStateException && "pid" in thrown.message.orEmpty())
        }
    }

    /** A shell command's stdout and stderr, each read to its end. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun shell(command: String): Pair<String, String> {
        val (stdout, stdin, stderr) = run.instrumentation.uiAutomation.executeShellCommandRwe(command)
        stdin.close()
        val out = ParcelFileDescriptor.AutoCloseInputStream(stdout).bufferedReader().use { it.readText() }
        val err = ParcelFileDescriptor.AutoCloseInputStream(stderr).bufferedReader().use { it.readText() }
        return out to err
    }

    private companion object {
        const val SCREEN_TITLE = "무료 · 시간별 스냅샷"
        const val COLD_START_MILLIS = 60_000L
        const val CONTROL = "com.jay.fxi.e5djourney.permission.CONTROL"
        const val REQUEST = "e5d-journey-j01-request"
        const val NONCE = "e5d-journey-j01-nonce"
    }
}
