package com.jay.fxi.data.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned R4-c N1 contract (R4c/N1; origin: C4 device evidence F1, R4c/C4/review_codex.impl.r1.md). The default network's
 * state follows default-network callbacks by their own arguments: the current network's loss reads offline, a newer default is
 * not overwritten by an older network's late events, and the usable policy stays INTERNET and (VALIDATED or not a captive
 * portal). The adapter half — `NetworkMonitor` registering a default-network callback and querying nothing inside it — is held
 * by the structure test below; the platform delivery itself is the device procedure's. The implementation thread reads but
 * does not edit this file.
 */
class DefaultNetworkStateContractTest {

    private companion object {
        const val A = 101L
        const val B = 102L
        val WIFI = NetworkFacts(internet = true, validated = true, captivePortal = false, type = ConnectionType.WIFI)
        val CELL = NetworkFacts(internet = true, validated = true, captivePortal = false, type = ConnectionType.CELLULAR)
    }

    private fun DefaultNetworkState.read() = connected.value to type.value

    private fun onWifi(): DefaultNetworkState = DefaultNetworkState(initialConnected = false, initialType = ConnectionType.UNKNOWN).apply {
        onAvailable(A)
        onCapabilitiesChanged(A, WIFI)
    }

    @Test
    fun `N1-01 the current default network's loss reads offline`() {
        val s = onWifi()
        assertEquals("N1-01 fixture", true to ConnectionType.WIFI, s.read())
        s.onLost(A)
        assertEquals("N1-01", false to ConnectionType.UNKNOWN, s.read())
    }

    @Test
    fun `N1-02 a newer default is not overwritten by the older network's late events`() {
        val s = onWifi()
        s.onAvailable(B)
        s.onCapabilitiesChanged(B, CELL)
        assertEquals("N1-02 handover", true to ConnectionType.CELLULAR, s.read())
        s.onLost(A)
        assertEquals("N1-02 the older network's loss", true to ConnectionType.CELLULAR, s.read())
        s.onCapabilitiesChanged(A, WIFI.copy(internet = false))
        assertEquals("N1-02 the older network's capabilities", true to ConnectionType.CELLULAR, s.read())
    }

    @Test
    fun `N1-03 from no network, a usable default reads online once its capabilities arrive`() {
        val s = DefaultNetworkState(initialConnected = false, initialType = ConnectionType.UNKNOWN)
        s.onAvailable(B)
        assertFalse("N1-03 availability alone is not usability", s.connected.value)
        s.onCapabilitiesChanged(B, CELL)
        assertEquals("N1-03", true to ConnectionType.CELLULAR, s.read())
    }

    @Test
    fun `N1-04 the usable policy is unchanged`() {
        val cases = listOf(
            NetworkFacts(internet = true, validated = true, captivePortal = false, type = ConnectionType.WIFI) to true,
            NetworkFacts(internet = true, validated = false, captivePortal = false, type = ConnectionType.WIFI) to true,
            NetworkFacts(internet = true, validated = false, captivePortal = true, type = ConnectionType.WIFI) to false,
            NetworkFacts(internet = true, validated = true, captivePortal = true, type = ConnectionType.WIFI) to true,
            NetworkFacts(internet = false, validated = true, captivePortal = false, type = ConnectionType.WIFI) to false
        )
        for ((facts, usable) in cases) {
            val s = DefaultNetworkState(initialConnected = !usable, initialType = ConnectionType.UNKNOWN)
            s.onAvailable(A)
            s.onCapabilitiesChanged(A, facts)
            assertEquals("N1-04 $facts", usable, s.connected.value)
            assertEquals("N1-04 type $facts", if (usable) ConnectionType.WIFI else ConnectionType.UNKNOWN, s.type.value)
        }
    }

    @Test
    fun `N1-05 the current default losing and regaining usability is followed`() {
        val s = onWifi()
        s.onCapabilitiesChanged(A, WIFI.copy(validated = false, captivePortal = true))
        assertEquals("N1-05 captive", false to ConnectionType.UNKNOWN, s.read())
        s.onCapabilitiesChanged(A, WIFI)
        assertEquals("N1-05 back", true to ConnectionType.WIFI, s.read())
    }

    @Test
    fun `N1-06 after a loss, the next default reads online`() {
        val s = onWifi()
        s.onLost(A)
        s.onAvailable(B)
        s.onCapabilitiesChanged(B, CELL)
        assertEquals("N1-06", true to ConnectionType.CELLULAR, s.read())
    }

    @Test
    fun `N1-07 the initial reading stands until an event`() {
        assertEquals("N1-07 online", true to ConnectionType.WIFI,
            DefaultNetworkState(initialConnected = true, initialType = ConnectionType.WIFI).read())
        assertEquals("N1-07 offline", false to ConnectionType.UNKNOWN,
            DefaultNetworkState(initialConnected = false, initialType = ConnectionType.UNKNOWN).read())
    }

    @Test
    fun `N1-08 NetworkMonitor follows the default network and queries nothing inside its callback`() {
        val text = File("src/main/java/com/jay/fxi/data/network/NetworkMonitor.kt").readText()
        assertTrue("N1-08 default-network registration", "registerDefaultNetworkCallback(" in text)
        assertFalse("N1-08 all-network registration", Regex("""\bregisterNetworkCallback\(""").containsMatchIn(text))
        assertTrue("N1-08 the state core", "DefaultNetworkState(" in text)
        val start = text.indexOf("ConnectivityManager.NetworkCallback()")
        assertTrue("N1-08 fixture: a callback object", start >= 0)
        val callback = text.substring(start, text.indexOf("\n    }\n", start))
        for (query in listOf("activeNetwork", "getNetworkCapabilities(", "checkCurrentConnectivity(", "getCurrentConnectionType("))
            assertFalse("N1-08 the callback queries $query", query in callback)

        // N1 r2 (R4c/N1/review_codex.r1.md): the platform types cannot be built on the JVM, so the wiring is held by its shape —
        // one core, the public flows are that core's, and each override hands its own arguments to that same core.
        val cores = Regex("""val\s+(\w+)\s*=\s*DefaultNetworkState\(""").findAll(text).toList()
        assertEquals("N1-08 one state core", 1, cores.size)
        val core = cores.single().groupValues[1]
        assertTrue("N1-08 isConnected is the core's",
            Regex("""val\s+isConnected\s*:\s*StateFlow<Boolean>\s*=\s*$core\.connected\b""").containsMatchIn(text))
        assertTrue("N1-08 connectionType is the core's",
            Regex("""val\s+connectionType\s*:\s*StateFlow<ConnectionType>\s*=\s*$core\.type\b""").containsMatchIn(text))
        fun body(name: String): String {
            val at = callback.indexOf("override fun $name(")
            assertTrue("N1-08 the callback overrides $name", at >= 0)
            return callback.substring(at, callback.indexOf("\n        }\n", at).let { if (it < 0) callback.length else it })
        }
        assertTrue("N1-08 onAvailable forwards", "$core.onAvailable(network.networkHandle)" in body("onAvailable"))
        val capabilities = body("onCapabilitiesChanged")
        val parameter = checkNotNull(Regex("""override fun onCapabilitiesChanged\(\s*network\s*:\s*Network\s*,\s*(\w+)\s*:\s*NetworkCapabilities""")
            .find(capabilities)) { "N1-08 fixture: the capabilities parameter" }.groupValues[1]
        assertTrue("N1-08 onCapabilitiesChanged forwards its own capabilities",
            Regex("""$core\.onCapabilitiesChanged\(\s*network\.networkHandle\s*,[^\n]*\b$parameter\b""").containsMatchIn(capabilities))
        assertTrue("N1-08 onLost forwards", "$core.onLost(network.networkHandle)" in body("onLost"))
    }
}
