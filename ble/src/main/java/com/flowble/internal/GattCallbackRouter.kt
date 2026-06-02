package com.flowble.internal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Build
import androidx.annotation.RequiresApi
import com.flowble.model.BlePhy
import com.flowble.exception.BleDisconnectedException
import com.flowble.exception.BleGattCallbackTimeoutException
import com.flowble.exception.BleGattCannotStartException
import com.flowble.exception.BleGattOperationType
import com.flowble.exception.ConnectionException
import com.flowble.exception.GattException
import com.flowble.exception.NotConnectedException
import com.flowble.model.BleService
import com.flowble.model.ConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Core routing engine for BLE GATT callbacks.
 *
 * This class owns a single [BluetoothGattCallback] instance and routes all callbacks
 * to the appropriate coroutine primitives:
 * - [CompletableDeferred] for one-shot operations (read, write, etc.)
 * - [Channel] for streaming notifications
 * - [MutableStateFlow] for connection state
 *
 * All GATT operations are serialized via a [Mutex] since Android BLE only supports
 * one outstanding operation at a time.
 */
@SuppressLint("MissingPermission")
internal class GattCallbackRouter {
    data class NotificationKey(val uuid: UUID, val instanceId: Int)
    enum class PendingCallbackType {
        GENERIC,
        PHY_READ,
        PHY_UPDATE
    }

    private val mutex = Mutex()
    private var pendingOperation: CompletableDeferred<Any>? = null
    private var pendingCallbackType: PendingCallbackType = PendingCallbackType.GENERIC
    private var connectionDeferred: CompletableDeferred<Unit>? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private val _mtu = MutableStateFlow(DEFAULT_MTU)
    val mtu: StateFlow<Int> = _mtu.asStateFlow()
    private val _phy = MutableStateFlow<BlePhy?>(null)
    val phy: StateFlow<BlePhy?> = _phy.asStateFlow()

    /**
     * Channels for notification routing, keyed by characteristic UUID.
     */
    val notificationChannels = ConcurrentHashMap<NotificationKey, Channel<ByteArray>>()

    private var gatt: BluetoothGatt? = null

    /**
     * The GATT callback that routes all BLE events to coroutine primitives.
     */
    val callback: BluetoothGattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val state = when (newState) {
                BluetoothGatt.STATE_CONNECTED -> ConnectionState.Connected
                BluetoothGatt.STATE_DISCONNECTED -> ConnectionState.Disconnected
                BluetoothGatt.STATE_CONNECTING -> ConnectionState.Connecting
                BluetoothGatt.STATE_DISCONNECTING -> ConnectionState.Disconnecting
                else -> ConnectionState.Disconnected
            }
            _connectionState.value = state

            when {
                newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    connectionDeferred?.complete(Unit)
                }
                newState == BluetoothGatt.STATE_DISCONNECTED -> {
                    _mtu.value = DEFAULT_MTU
                    _phy.value = null
                    val cause = BleDisconnectedException(
                        deviceAddress = gatt.device?.address,
                        status = if (status == BluetoothGatt.GATT_SUCCESS) {
                            BluetoothGatt.GATT_SUCCESS
                        } else {
                            status
                        }
                    )
                    connectionDeferred?.completeExceptionally(cause)
                    cancelPendingOperations(cause)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val services = gatt.services.map { service ->
                    BleService.fromAndroid(service)
                }
                pendingOperation?.complete(services as Any)
            } else {
                pendingOperation?.completeExceptionally(
                    GattException(
                        status = status,
                        operationType = BleGattOperationType.SERVICE_DISCOVERY,
                        macAddress = gatt.device?.address
                    )
                )
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingOperation?.complete(characteristic.value ?: ByteArray(0) as Any)
            } else {
                pendingOperation?.completeExceptionally(
                    GattException(
                        status = status,
                        operationType = BleGattOperationType.CHARACTERISTIC_READ,
                        macAddress = gatt.device?.address
                    )
                )
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingOperation?.complete(value as Any)
            } else {
                pendingOperation?.completeExceptionally(
                    GattException(
                        status = status,
                        operationType = BleGattOperationType.CHARACTERISTIC_READ,
                        macAddress = gatt.device?.address
                    )
                )
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingOperation?.complete(Unit as Any)
            } else {
                pendingOperation?.completeExceptionally(
                    GattException(
                        status = status,
                        operationType = BleGattOperationType.CHARACTERISTIC_WRITE,
                        macAddress = gatt.device?.address
                    )
                )
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val channel = notificationChannels[notificationKeyOf(characteristic)]
            channel?.trySend(characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val channel = notificationChannels[notificationKeyOf(characteristic)]
            channel?.trySend(value)
        }

        @Suppress("DEPRECATION")
        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingOperation?.complete(descriptor.value ?: ByteArray(0) as Any)
            } else {
                pendingOperation?.completeExceptionally(
                    GattException(
                        status = status,
                        operationType = BleGattOperationType.DESCRIPTOR_READ,
                        macAddress = gatt.device?.address
                    )
                )
            }
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingOperation?.complete(value as Any)
            } else {
                pendingOperation?.completeExceptionally(
                    GattException(
                        status = status,
                        operationType = BleGattOperationType.DESCRIPTOR_READ,
                        macAddress = gatt.device?.address
                    )
                )
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingOperation?.complete(Unit as Any)
            } else {
                pendingOperation?.completeExceptionally(
                    GattException(
                        status = status,
                        operationType = BleGattOperationType.DESCRIPTOR_WRITE,
                        macAddress = gatt.device?.address
                    )
                )
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingOperation?.complete(rssi as Any)
            } else {
                pendingOperation?.completeExceptionally(
                    GattException(
                        status = status,
                        operationType = BleGattOperationType.READ_RSSI,
                        macAddress = gatt.device?.address
                    )
                )
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _mtu.value = mtu
                pendingOperation?.complete(mtu as Any)
            } else {
                pendingOperation?.completeExceptionally(
                    GattException(
                        status = status,
                        operationType = BleGattOperationType.MTU_CHANGED,
                        macAddress = gatt.device?.address
                    )
                )
            }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val phy = blePhyFromAndroid(txPhy, rxPhy)
                _phy.value = phy
                if (pendingCallbackType == PendingCallbackType.PHY_READ) {
                    pendingOperation?.complete(phy as Any)
                }
            } else {
                if (pendingCallbackType == PendingCallbackType.PHY_READ) {
                    pendingOperation?.completeExceptionally(
                        GattException(
                            status = status,
                            operationType = BleGattOperationType.PHY_READ,
                            macAddress = gatt.device?.address
                        )
                    )
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val phy = blePhyFromAndroid(txPhy, rxPhy)
                _phy.value = phy
                if (pendingCallbackType == PendingCallbackType.PHY_UPDATE) {
                    pendingOperation?.complete(phy as Any)
                }
            } else {
                if (pendingCallbackType == PendingCallbackType.PHY_UPDATE) {
                    pendingOperation?.completeExceptionally(
                        GattException(
                            status = status,
                            operationType = BleGattOperationType.PHY_UPDATE,
                            macAddress = gatt.device?.address
                        )
                    )
                }
            }
        }
    }

    /**
     * Execute a GATT operation with mutex serialization and timeout.
     *
     * @param timeoutMs Timeout for the operation in milliseconds.
     * @param operation The GATT operation to execute. Should return true if the operation was initiated.
     * @return The result of the operation.
     */
    suspend fun <T> executeOperation(
        timeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS,
        expectedCallbackType: PendingCallbackType = PendingCallbackType.GENERIC,
        operationType: BleGattOperationType = BleGattOperationType.CUSTOM,
        operation: () -> Boolean
    ): T {
        return mutex.withLock {
            val deferred = CompletableDeferred<Any>()
            pendingOperation = deferred
            pendingCallbackType = expectedCallbackType

            try {
                val initiated = operation()
                if (!initiated) {
                    throw BleGattCannotStartException(
                        operationType = operationType,
                        macAddress = gatt?.device?.address
                    )
                }

                @Suppress("UNCHECKED_CAST")
                try {
                    withTimeout(timeoutMs) { deferred.await() as T }
                } catch (e: TimeoutCancellationException) {
                    throw BleGattCallbackTimeoutException(
                        timeoutMs = timeoutMs,
                        operationType = operationType,
                        macAddress = gatt?.device?.address,
                        cause = e
                    )
                }
            } finally {
                pendingOperation = null
                pendingCallbackType = PendingCallbackType.GENERIC
            }
        }
    }

    /**
     * Set up the connection deferred for the connect operation.
     */
    fun prepareConnection(): CompletableDeferred<Unit> {
        val deferred = CompletableDeferred<Unit>()
        connectionDeferred = deferred
        return deferred
    }

    /**
     * Attach the BluetoothGatt instance after connectGatt returns.
     */
    fun attachGatt(gatt: BluetoothGatt) {
        this.gatt = gatt
        _mtu.value = DEFAULT_MTU
        _phy.value = null
    }

    fun notificationKeyOf(characteristic: BluetoothGattCharacteristic): NotificationKey {
        return NotificationKey(
            uuid = characteristic.uuid,
            instanceId = characteristic.instanceId
        )
    }

    /**
     * Get the BluetoothGatt instance or throw if not connected.
     */
    fun getGattOrThrow(): BluetoothGatt {
        return gatt ?: throw NotConnectedException()
    }

    /**
     * Cancel all pending operations with the given cause.
     */
    fun cancelPendingOperations(cause: Throwable) {
        pendingOperation?.completeExceptionally(cause)
        pendingCallbackType = PendingCallbackType.GENERIC
        connectionDeferred?.completeExceptionally(cause)
        notificationChannels.values.forEach { channel ->
            channel.close(cause)
        }
        notificationChannels.clear()
    }

    /**
     * Clean up all resources.
     */
    fun cleanup() {
        cancelPendingOperations(ConnectionException("Connection closed"))
        try {
            gatt?.close()
        } catch (_: Exception) {
            // Ignore close errors
        }
        gatt = null
        _connectionState.value = ConnectionState.Disconnected
        _mtu.value = DEFAULT_MTU
        _phy.value = null
        connectionDeferred = null
    }
}
