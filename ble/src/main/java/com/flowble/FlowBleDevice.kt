package com.flowble

import android.bluetooth.BluetoothDevice
import com.flowble.model.BleDeviceSnapshot
import com.flowble.model.ConnectionConfig
import com.flowble.model.ConnectionState
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Device-scoped BLE entry point.
 *
 * This wrapper is useful when code wants to keep a stable handle for one peripheral,
 * observe its connection state, and establish connections later.
 */
interface FlowBleDevice {

    /** MAC address of the peripheral. */
    val address: String

    /** Convenience alias for [address]. */
    val macAddress: String
        get() = address

    /** Current connection state for this device handle. */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Observe connection state changes for this device.
     *
     * This flow immediately emits the current state snapshot and then continues with future changes.
     */
    fun observeConnectionStateChanges(): Flow<ConnectionState> = connectionState

    /** Convenience snapshot of the current connection state. */
    fun getConnectionState(): ConnectionState = connectionState.value

    /**
     * Combined observable state for this stable device handle.
     *
     * This is intentionally lightweight: address is stable, name is best-effort, and
     * connection state tracks the latest client-side view for this handle.
     */
    val snapshot: Flow<BleDeviceSnapshot>
        get() = connectionState
            .map { currentSnapshot() }
            .distinctUntilChanged()

    /**
     * Synchronous convenience snapshot of the current device handle state.
     */
    fun currentSnapshot(): BleDeviceSnapshot {
        return BleDeviceSnapshot(
            address = address,
            name = runCatching { getName() }.getOrNull(),
            connectionState = connectionState.value
        )
    }

    /** Name reported by the underlying [BluetoothDevice], if present. */
    fun getName(): String?

    /** Underlying Android [BluetoothDevice]. */
    fun getBluetoothDevice(): BluetoothDevice

    /**
     * Establish a connection using the provided configuration.
     *
     * Only one active or in-progress connection is allowed per device handle.
     */
    suspend fun connect(config: ConnectionConfig = ConnectionConfig()): BleConnection

    /**
     * Convenience overload that derives a [ConnectionConfig] from [autoConnect].
     */
    suspend fun establishConnection(autoConnect: Boolean): BleConnection {
        return connect(establishConnectionConfig(autoConnect = autoConnect))
    }

    /**
     * Convenience overload with explicit operation timeout override.
     *
     * `autoConnect = true` waits indefinitely for the initial connection unless a full
     * [ConnectionConfig] is supplied.
     */
    suspend fun establishConnection(
        autoConnect: Boolean,
        operationTimeoutMs: Long
    ): BleConnection {
        return connect(
            establishConnectionConfig(
                autoConnect = autoConnect,
                operationTimeoutMs = operationTimeoutMs
            )
        )
    }

    /**
     * Convenience alias that forwards to [connect].
     */
    suspend fun establishConnection(config: ConnectionConfig): BleConnection {
        return connect(config)
    }

    /**
     * Long-lived Flow variant for connection lifecycle management.
     *
     * Collection initiates the connection attempt, emits the connected [BleConnection] once,
     * stays active while that connection remains alive, and closes the connection when the
     * collector cancels.
     */
    fun establishConnectionFlow(
        autoConnect: Boolean
    ): Flow<BleConnection> {
        return establishConnectionFlow(establishConnectionConfig(autoConnect = autoConnect))
    }

    /**
     * Long-lived Flow variant with explicit operation timeout override.
     */
    fun establishConnectionFlow(
        autoConnect: Boolean,
        operationTimeoutMs: Long
    ): Flow<BleConnection> {
        return establishConnectionFlow(
            establishConnectionConfig(
                autoConnect = autoConnect,
                operationTimeoutMs = operationTimeoutMs
            )
        )
    }

    /**
     * Long-lived Flow variant for connection lifecycle management.
     */
    fun establishConnectionFlow(
        config: ConnectionConfig = ConnectionConfig()
    ): Flow<BleConnection> = callbackFlow {
        val connection = connect(config)
        val sendResult = trySend(connection)
        if (sendResult.isFailure) {
            connection.close()
            return@callbackFlow
        }

        val disconnectionJob = launch {
            connection.connectionState.first { state -> state == ConnectionState.Disconnected }
            channel.close()
        }

        awaitClose {
            disconnectionJob.cancel()
            connection.close()
        }
    }
}

private fun establishConnectionConfig(
    autoConnect: Boolean,
    operationTimeoutMs: Long? = null
): ConnectionConfig {
    val base = if (autoConnect) {
        ConnectionConfig.AUTO_CONNECT
    } else {
        ConnectionConfig()
    }
    return if (operationTimeoutMs == null) {
        base
    } else {
        base.copy(operationTimeout = operationTimeoutMs)
    }
}
