package com.jay.fxi.data.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What one network's capabilities say, taken from a callback's own arguments; [type] is its transport. */
internal data class NetworkFacts(
    val internet: Boolean,
    val validated: Boolean,
    val captivePortal: Boolean,
    val type: ConnectionType
) {
    val usable: Boolean get() = internet && (validated || !captivePortal)
}

/**
 * The system default network's usability, driven only by default-network callback events.
 * Event handling is serialized so a late event from an older network cannot overwrite the current default.
 */
internal class DefaultNetworkState(initialConnected: Boolean, initialType: ConnectionType) {
    private val _connected = MutableStateFlow(initialConnected)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _type = MutableStateFlow(initialType)
    val type: StateFlow<ConnectionType> = _type.asStateFlow()

    private var currentNetwork: Long? = null

    @Synchronized
    fun onAvailable(network: Long) {
        if (currentNetwork == network) return
        currentNetwork = network
        // Availability identifies the default; only its capabilities establish usability.
        _connected.value = false
        _type.value = ConnectionType.UNKNOWN
    }

    @Synchronized
    fun onCapabilitiesChanged(network: Long, facts: NetworkFacts) {
        if (currentNetwork != network) return
        val usable = facts.usable
        _connected.value = usable
        _type.value = if (usable) facts.type else ConnectionType.UNKNOWN
    }

    @Synchronized
    fun onLost(network: Long) {
        if (currentNetwork != network) return
        currentNetwork = null
        _connected.value = false
        _type.value = ConnectionType.UNKNOWN
    }
}
