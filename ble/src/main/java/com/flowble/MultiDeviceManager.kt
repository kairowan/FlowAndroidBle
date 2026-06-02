package com.flowble

import android.annotation.SuppressLint
import android.content.Context
import com.flowble.model.ConnectionConfig
import com.flowble.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages multiple BLE device connections simultaneously.
 *
 * This class provides a centralized way to manage connections to multiple
 * BLE devices, with support for connection pooling and monitoring.
 */
class MultiDeviceManager internal constructor(
    private val client: FlowBleClient,
    private val connectOperation: suspend (String, ConnectionConfig) -> BleConnection = client::connect,
    private val observationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    constructor(context: Context) : this(FlowBleClient.getInstance(context))

    private val connections = ConcurrentHashMap<String, BleConnection>()
    private val observationJobs = ConcurrentHashMap<String, Job>()
    private val _activeConnections = MutableStateFlow<Map<String, BleConnection>>(emptyMap())

    /**
     * Managed connections keyed by normalized MAC address.
     *
     * Connections disappear from this state flow as soon as they are replaced or disconnected.
     */
    val activeConnections: StateFlow<Map<String, BleConnection>> = _activeConnections.asStateFlow()

    /**
     * Connect to a device and add it to the managed connections.
     *
     * @param address The MAC address of the device.
     * @param config Connection configuration.
     * @return The [BleConnection] for the device.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(
        address: String,
        config: ConnectionConfig = ConnectionConfig()
    ): BleConnection {
        val normalizedAddress = address.trim()

        // Disconnect existing connection if any
        removeTrackedConnection(normalizedAddress)?.let { existing ->
            try {
                existing.close()
            } catch (_: Exception) {
                // Ignore
            }
        }

        val connection = connectOperation(normalizedAddress, config)
        connections[normalizedAddress] = connection
        trackConnection(normalizedAddress, connection)
        return connection
    }

    /**
     * Disconnect from a specific device.
     *
     * @param address The MAC address of the device.
     */
    suspend fun disconnect(address: String) {
        removeTrackedConnection(address.trim())?.let { connection ->
            try {
                connection.disconnect()
            } catch (_: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Disconnect from all devices.
     */
    suspend fun disconnectAll() {
        val existingConnections = removeAllTrackedConnections()
        existingConnections.forEach { connection ->
            try {
                connection.disconnect()
            } catch (_: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Get a connection by device address.
     *
     * @param address The MAC address of the device.
     * @return The [BleConnection] or null if not connected.
     */
    fun getConnection(address: String): BleConnection? = connections[address.trim()]
        ?.takeUnless { connection ->
            if (connection.connectionState.value == ConnectionState.Disconnected) {
                removeTrackedConnectionIfSame(address.trim(), connection)
                true
            } else {
                false
            }
        }

    /**
     * Get all active connections.
     */
    fun getAllConnections(): Map<String, BleConnection> {
        pruneDisconnectedConnections()
        return connections.toMap()
    }

    /**
     * Get the number of active connections.
     */
    fun connectionCount(): Int {
        pruneDisconnectedConnections()
        return connections.size
    }

    /**
     * Check if a device is connected.
     */
    fun isConnected(address: String): Boolean {
        val connection = getConnection(address) ?: return false
        return connection.connectionState.value == ConnectionState.Connected
    }

    /**
     * Close and remove all connections.
     */
    fun closeAll() {
        val existingConnections = removeAllTrackedConnections()
        existingConnections.forEach { connection ->
            try {
                connection.close()
            } catch (_: Exception) {
                // Ignore
            }
        }
    }

    private fun trackConnection(address: String, connection: BleConnection) {
        publishConnection(address, connection)
        observationJobs.remove(address)?.cancel()
        observationJobs[address] = observationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            connection.connectionState.collect { state ->
                if (state == ConnectionState.Disconnected) {
                    removeTrackedConnectionIfSame(address, connection)
                    return@collect
                }
            }
            observationJobs.remove(address)
        }
    }

    private fun pruneDisconnectedConnections() {
        connections.forEach { (address, connection) ->
            if (connection.connectionState.value == ConnectionState.Disconnected) {
                removeTrackedConnectionIfSame(address, connection)
            }
        }
    }

    private fun removeTrackedConnection(address: String): BleConnection? {
        observationJobs.remove(address)?.cancel()
        val removed = connections.remove(address)
        if (removed != null) {
            publishConnectionRemoval(address)
        }
        return removed
    }

    private fun removeTrackedConnectionIfSame(address: String, connection: BleConnection) {
        if (connections[address] !== connection) {
            return
        }
        observationJobs.remove(address)?.cancel()
        connections.remove(address)
        publishConnectionRemoval(address)
    }

    private fun removeAllTrackedConnections(): List<BleConnection> {
        observationJobs.values.forEach(Job::cancel)
        observationJobs.clear()
        val existingConnections = connections.values.toList()
        connections.clear()
        _activeConnections.value = emptyMap()
        return existingConnections
    }

    private fun publishConnection(address: String, connection: BleConnection) {
        _activeConnections.value = _activeConnections.value.toMutableMap().apply {
            put(address, connection)
        }
    }

    private fun publishConnectionRemoval(address: String) {
        _activeConnections.value = _activeConnections.value.toMutableMap().apply {
            remove(address)
        }
    }
}
