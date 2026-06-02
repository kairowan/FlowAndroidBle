package com.flowble

import com.flowble.model.BleCharacteristic
import com.flowble.model.BleConnectionSnapshot
import com.flowble.model.BleDescriptor
import com.flowble.model.BlePhy
import com.flowble.model.BleService
import com.flowble.model.BondState
import com.flowble.model.CharacteristicObservationMode
import com.flowble.model.ConnectionPriority
import com.flowble.model.ConnectionState
import com.flowble.model.OperationConfig
import com.flowble.model.PhyOption
import com.flowble.model.PhyRequest
import com.flowble.model.PhyType
import com.flowble.model.WriteType
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Public interface for BLE connection operations.
 *
 * This interface provides a clean, Flow-based API for interacting with a connected
 * BLE device. All GATT operations are serialized internally to prevent conflicts.
 *
 * Note: The caller is responsible for checking and requesting BLE permissions before
 * calling any methods on this interface.
 */
interface BleConnection {

    /** Current connection state as a StateFlow. */
    val connectionState: StateFlow<ConnectionState>

    /** Bond (pairing) state changes as a SharedFlow. */
    val bondState: Flow<BondState>

    /** Current negotiated MTU. Defaults to 23 before any successful MTU request. */
    val mtu: StateFlow<Int>

    /**
     * Current PHY reported by Android, or null if it has not been read yet or the API level
     * does not support PHY operations.
     */
    val phy: StateFlow<BlePhy?>

    /**
     * Current discovered services for this connection, or null if discovery hasn't run yet.
     *
     * This resets back to null when the connection closes or disconnects.
     */
    val servicesState: StateFlow<List<BleService>?>

    /** Cached list of discovered services. Null if discovery hasn't been called. */
    val services: List<BleService>?
        get() = servicesState.value

    /**
     * Combined observable state for this connection.
     */
    val snapshot: Flow<BleConnectionSnapshot>
        get() = combine(
            connectionState,
            mtu,
            phy,
            servicesState
        ) { connectionState, mtu, phy, services ->
            BleConnectionSnapshot(
                connectionState = connectionState,
                mtu = mtu,
                phy = phy,
                services = services
            )
        }.distinctUntilChanged()

    /**
     * Synchronous convenience snapshot of the current connection state.
     */
    fun currentSnapshot(): BleConnectionSnapshot {
        return BleConnectionSnapshot(
            connectionState = connectionState.value,
            mtu = mtu.value,
            phy = phy.value,
            services = servicesState.value
        )
    }

    /**
     * Return discovered services, refreshing them first when needed.
     *
     * This offers a query-oriented entry point on top of [discoverServices].
     */
    suspend fun getServices(forceRefresh: Boolean = false): List<BleService>

    /**
     * Discover GATT services on the remote device.
     * Must be called before read/write operations on characteristics.
     *
     * @return The list of discovered services.
     */
    suspend fun discoverServices(): List<BleService>

    /**
     * Resolve a single discovered service by UUID.
     *
     * Throws when no service matches, or when more than one service with that UUID exists.
     */
    suspend fun getService(
        serviceUuid: UUID,
        forceRefresh: Boolean = false
    ): BleService

    /**
     * Resolve a single discovered characteristic by UUID across all services.
     *
     * Throws when no characteristic matches, or when multiple matches exist.
     */
    suspend fun getCharacteristic(
        characteristicUuid: UUID,
        forceRefresh: Boolean = false
    ): BleCharacteristic

    /**
     * Resolve a single discovered characteristic by service UUID and characteristic UUID.
     *
     * Throws when no characteristic matches, or when multiple matches exist.
     */
    suspend fun getCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        forceRefresh: Boolean = false
    ): BleCharacteristic

    /**
     * Resolve a discovered descriptor by service UUID, characteristic UUID, and descriptor UUID.
     */
    suspend fun getDescriptor(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        forceRefresh: Boolean = false
    ): BleDescriptor

    /**
     * Read a characteristic's value.
     *
     * @param characteristic The characteristic to read.
     * @param config Optional operation configuration for priority, timeout, and retry.
     * @return The value as a byte array.
     */
    suspend fun readCharacteristic(
        characteristic: BleCharacteristic,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray

    /**
     * Convenience alias for reading by characteristic UUID.
     */
    suspend fun readCharacteristic(
        characteristicUuid: UUID,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return readCharacteristicByUuid(characteristicUuid, config)
    }

    /**
     * Alias for reading by service UUID and characteristic UUID.
     */
    suspend fun readCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return readCharacteristicByUuid(serviceUuid, characteristicUuid, config)
    }

    /**
     * Write a value to a characteristic.
     *
     * @param characteristic The characteristic to write to.
     * @param value The value to write.
     * @param writeType Whether to expect a response from the peripheral.
     * @param config Optional operation configuration for priority, timeout, and retry.
     * @return The written value.
     */
    suspend fun writeCharacteristic(
        characteristic: BleCharacteristic,
        value: ByteArray,
        writeType: WriteType = WriteType.DEFAULT,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray

    /**
     * Convenience alias for writing by characteristic UUID.
     */
    suspend fun writeCharacteristic(
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: WriteType = WriteType.DEFAULT,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return writeCharacteristicByUuid(characteristicUuid, value, writeType, config)
    }

    /**
     * Alias for writing by service UUID and characteristic UUID.
     */
    suspend fun writeCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: WriteType = WriteType.DEFAULT,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return writeCharacteristicByUuid(serviceUuid, characteristicUuid, value, writeType, config)
    }

    /**
     * Write a value larger than a single ATT packet by splitting it into multiple writes.
     *
     * When [maxChunkSize] is null the current MTU minus ATT write overhead is used.
     *
     * @return The original value after all chunks are written successfully.
     */
    suspend fun writeCharacteristicLong(
        characteristic: BleCharacteristic,
        value: ByteArray,
        writeType: WriteType = WriteType.DEFAULT,
        maxChunkSize: Int? = null,
        interChunkDelayMs: Long = 0L,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray

    /**
     * Observe notifications/indications from a characteristic.
     *
     * This method enables notifications on the characteristic and returns a Flow
     * that emits values whenever the characteristic changes. The Flow will automatically
     * disable notifications when cancelled.
     *
     * @param characteristic The characteristic to observe.
     * @return A Flow that emits characteristic values.
     */
    fun observeCharacteristic(characteristic: BleCharacteristic): Flow<ByteArray>

    /**
     * Observe notifications/indications from a characteristic with explicit mode selection.
     *
     * @param characteristic The characteristic to observe.
     * @param mode Whether to use notifications, indications, or choose automatically.
     * @return A Flow that emits characteristic values.
     */
    fun observeCharacteristic(
        characteristic: BleCharacteristic,
        mode: CharacteristicObservationMode
    ): Flow<ByteArray>

    /**
     * Nested setup API.
     *
     * The outer Flow represents setup/teardown, while the emitted inner Flow carries
     * characteristic updates and closes when the observation ends.
     */
    fun setupCharacteristicObservation(
        characteristic: BleCharacteristic,
        mode: CharacteristicObservationMode,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT
    ): Flow<Flow<ByteArray>>

    /**
     * Convenience alias for observing notifications only.
     */
    fun setupNotification(characteristic: BleCharacteristic): Flow<ByteArray> {
        return observeCharacteristic(characteristic, CharacteristicObservationMode.NOTIFICATION)
    }

    /**
     * Convenience alias for observing notifications by characteristic UUID.
     */
    fun setupNotification(characteristicUuid: UUID): Flow<ByteArray> {
        return observeCharacteristicByUuid(characteristicUuid, CharacteristicObservationMode.NOTIFICATION)
    }

    /**
     * Overload that preserves the outer setup Flow.
     */
    fun setupNotification(
        characteristic: BleCharacteristic,
        setupMode: NotificationSetupMode
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservation(
            characteristic,
            CharacteristicObservationMode.NOTIFICATION,
            setupMode
        )
    }

    /**
     * Convenience alias for observing notifications by UUID while preserving the outer setup Flow.
     */
    fun setupNotification(
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationByUuid(
            characteristicUuid,
            CharacteristicObservationMode.NOTIFICATION,
            setupMode
        )
    }

    /**
     * Convenience alias for observing notifications by service UUID and characteristic UUID.
     */
    fun setupNotification(serviceUuid: UUID, characteristicUuid: UUID): Flow<ByteArray> {
        return observeCharacteristicByUuid(
            serviceUuid,
            characteristicUuid,
            CharacteristicObservationMode.NOTIFICATION
        )
    }

    /**
     * Convenience alias for observing notifications by service UUID and characteristic UUID
     * while preserving the outer setup Flow.
     */
    fun setupNotification(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationByUuid(
            serviceUuid,
            characteristicUuid,
            CharacteristicObservationMode.NOTIFICATION,
            setupMode
        )
    }

    /**
     * Convenience alias for observing indications only.
     */
    fun setupIndication(characteristic: BleCharacteristic): Flow<ByteArray> {
        return observeCharacteristic(characteristic, CharacteristicObservationMode.INDICATION)
    }

    /**
     * Convenience alias for observing indications by characteristic UUID.
     */
    fun setupIndication(characteristicUuid: UUID): Flow<ByteArray> {
        return observeCharacteristicByUuid(characteristicUuid, CharacteristicObservationMode.INDICATION)
    }

    /**
     * Overload that preserves the outer setup Flow.
     */
    fun setupIndication(
        characteristic: BleCharacteristic,
        setupMode: NotificationSetupMode
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservation(
            characteristic,
            CharacteristicObservationMode.INDICATION,
            setupMode
        )
    }

    /**
     * Convenience alias for observing indications by UUID while preserving the outer setup Flow.
     */
    fun setupIndication(
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationByUuid(
            characteristicUuid,
            CharacteristicObservationMode.INDICATION,
            setupMode
        )
    }

    /**
     * Convenience alias for observing indications by service UUID and characteristic UUID.
     */
    fun setupIndication(serviceUuid: UUID, characteristicUuid: UUID): Flow<ByteArray> {
        return observeCharacteristicByUuid(
            serviceUuid,
            characteristicUuid,
            CharacteristicObservationMode.INDICATION
        )
    }

    /**
     * Convenience alias for observing indications by service UUID and characteristic UUID
     * while preserving the outer setup Flow.
     */
    fun setupIndication(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationByUuid(
            serviceUuid,
            characteristicUuid,
            CharacteristicObservationMode.INDICATION,
            setupMode
        )
    }

    /**
     * Nested setup API for notifications.
     */
    fun setupNotificationSession(
        characteristic: BleCharacteristic,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservation(
            characteristic,
            CharacteristicObservationMode.NOTIFICATION,
            setupMode
        )
    }

    /**
     * Nested setup API for indications.
     */
    fun setupIndicationSession(
        characteristic: BleCharacteristic,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservation(
            characteristic,
            CharacteristicObservationMode.INDICATION,
            setupMode
        )
    }

    /**
     * Read a descriptor's value.
     *
     * @param descriptor The descriptor to read.
     * @param config Optional operation configuration for priority, timeout, and retry.
     * @return The value as a byte array.
     */
    suspend fun readDescriptor(
        descriptor: BleDescriptor,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray

    /**
     * Alias for reading a descriptor by service UUID, characteristic UUID, and descriptor UUID.
     */
    suspend fun readDescriptor(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return readDescriptorByUuid(serviceUuid, characteristicUuid, descriptorUuid, config)
    }

    /**
     * Write a value to a descriptor.
     *
     * @param descriptor The descriptor to write to.
     * @param value The value to write.
     * @param config Optional operation configuration for priority, timeout, and retry.
     */
    suspend fun writeDescriptor(
        descriptor: BleDescriptor,
        value: ByteArray,
        config: OperationConfig = OperationConfig.DEFAULT
    )

    /**
     * Alias for writing a descriptor by service UUID, characteristic UUID, and descriptor UUID.
     */
    suspend fun writeDescriptor(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        value: ByteArray,
        config: OperationConfig = OperationConfig.DEFAULT
    ) {
        writeDescriptorByUuid(serviceUuid, characteristicUuid, descriptorUuid, value, config)
    }

    /**
     * Request a specific MTU (Maximum Transmission Unit).
     *
     * @param mtu The desired MTU size (must be between 23 and 517).
     * @param config Optional operation configuration for priority, timeout, and retry.
     * @return The negotiated MTU value.
     */
    suspend fun requestMtu(mtu: Int, config: OperationConfig = OperationConfig.DEFAULT): Int

    /**
     * Request a preferred connection priority.
     *
     * Since Android does not expose a completion callback for this request, the call waits
     * for [settleDelayMs] before returning when the request is accepted by the platform.
     */
    suspend fun requestConnectionPriority(
        priority: ConnectionPriority,
        settleDelayMs: Long = 500L,
        config: OperationConfig = OperationConfig.DEFAULT
    )

    /**
     * Read the current PHY of the connection.
     *
     * Requires Android 8.0 (API 26) or newer.
     */
    suspend fun readPhy(config: OperationConfig = OperationConfig.DEFAULT): BlePhy

    /**
     * Request preferred PHYs for transmit and receive directions.
     *
     * Requires Android 8.0 (API 26) or newer.
     */
    suspend fun requestPhy(
        request: PhyRequest,
        config: OperationConfig = OperationConfig.DEFAULT
    ): BlePhy

    /**
     * Convenience overload for requesting a single preferred PHY in each direction.
     */
    suspend fun requestPhy(
        txPhy: PhyType,
        rxPhy: PhyType = txPhy,
        option: PhyOption = PhyOption.NO_PREFERRED,
        config: OperationConfig = OperationConfig.DEFAULT
    ): BlePhy {
        return requestPhy(
            PhyRequest(
                txPhys = setOf(txPhy),
                rxPhys = setOf(rxPhy),
                option = option
            ),
            config
        )
    }

    /**
     * Android-style alias for requesting preferred PHYs.
     */
    suspend fun setPreferredPhy(
        request: PhyRequest,
        config: OperationConfig = OperationConfig.DEFAULT
    ): BlePhy {
        return requestPhy(request, config)
    }

    /**
     * Create an advanced long write builder.
     *
     * This is useful when a long write needs per-batch pacing and acknowledgement hooks.
     */
    fun createNewLongWriteBuilder(): LongWriteOperationBuilder

    /**
     * Run a custom Android BLE operation inside the same serialized queue used by the library.
     *
     * This is an advanced escape hatch for platform APIs that do not yet have dedicated wrappers.
     * Do not call other high-level [BleConnection] methods from inside [operation].
     */
    suspend fun <T> queue(
        config: OperationConfig = OperationConfig.DEFAULT,
        operation: suspend QueuedGattOperationScope.() -> T
    ): T

    /**
     * Read the current RSSI (Received Signal Strength Indicator) of the connection.
     *
     * @param config Optional operation configuration for priority, timeout, and retry.
     * @return The RSSI value in dBm.
     */
    suspend fun readRssi(config: OperationConfig = OperationConfig.DEFAULT): Int

    /**
     * Read the value of a characteristic by its UUID.
     *
     * This is a convenience method that automatically discovers services if needed.
     *
     * @param characteristicUuid The UUID of the characteristic to read.
     * @param config Optional operation configuration for priority, timeout, and retry.
     * @return The value as a byte array.
     */
    suspend fun readCharacteristicByUuid(
        characteristicUuid: UUID,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray

    /**
     * Read the value of a characteristic by service UUID and characteristic UUID.
     *
     * This is useful when the same characteristic UUID appears in multiple services.
     */
    suspend fun readCharacteristicByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray

    /**
     * Write a value to a characteristic by its UUID.
     *
     * This is a convenience method that automatically discovers services if needed.
     *
     * @param characteristicUuid The UUID of the characteristic to write to.
     * @param value The value to write.
     * @param writeType Whether to expect a response from the peripheral.
     * @param config Optional operation configuration for priority, timeout, and retry.
     * @return The written value.
     */
    suspend fun writeCharacteristicByUuid(
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: WriteType = WriteType.DEFAULT,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray

    /**
     * Write a value to a characteristic by service UUID and characteristic UUID.
     */
    suspend fun writeCharacteristicByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: WriteType = WriteType.DEFAULT,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray

    /**
     * Write a value larger than a single ATT packet by UUID.
     *
     * This is a convenience method that automatically discovers services if needed.
     */
    suspend fun writeCharacteristicLongByUuid(
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: WriteType = WriteType.DEFAULT,
        maxChunkSize: Int? = null,
        interChunkDelayMs: Long = 0L,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray

    /**
     * Write a value larger than a single ATT packet by service UUID and characteristic UUID.
     */
    suspend fun writeCharacteristicLongByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: WriteType = WriteType.DEFAULT,
        maxChunkSize: Int? = null,
        interChunkDelayMs: Long = 0L,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray

    /**
     * Observe notifications from a characteristic by its UUID.
     *
     * This is a convenience method that automatically discovers services if needed.
     *
     * @param characteristicUuid The UUID of the characteristic to observe.
     * @return A Flow that emits characteristic values.
     */
    fun observeCharacteristicByUuid(characteristicUuid: UUID): Flow<ByteArray>

    /**
     * Observe notifications or indications from a characteristic by UUID with explicit mode selection.
     */
    fun observeCharacteristicByUuid(
        characteristicUuid: UUID,
        mode: CharacteristicObservationMode
    ): Flow<ByteArray>

    /**
     * Nested setup API by characteristic UUID.
     */
    fun setupCharacteristicObservationByUuid(
        characteristicUuid: UUID,
        mode: CharacteristicObservationMode,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT
    ): Flow<Flow<ByteArray>>

    /**
     * Convenience alias for observing notifications only by UUID.
     */
    fun setupNotificationByUuid(characteristicUuid: UUID): Flow<ByteArray> {
        return observeCharacteristicByUuid(characteristicUuid, CharacteristicObservationMode.NOTIFICATION)
    }

    /**
     * Overload that preserves the outer setup Flow.
     */
    fun setupNotificationByUuid(
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationByUuid(
            characteristicUuid,
            CharacteristicObservationMode.NOTIFICATION,
            setupMode
        )
    }

    /**
     * Convenience alias for observing indications only by UUID.
     */
    fun setupIndicationByUuid(characteristicUuid: UUID): Flow<ByteArray> {
        return observeCharacteristicByUuid(characteristicUuid, CharacteristicObservationMode.INDICATION)
    }

    /**
     * Overload that preserves the outer setup Flow.
     */
    fun setupIndicationByUuid(
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationByUuid(
            characteristicUuid,
            CharacteristicObservationMode.INDICATION,
            setupMode
        )
    }

    fun setupNotificationSessionByUuid(
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationByUuid(
            characteristicUuid,
            CharacteristicObservationMode.NOTIFICATION,
            setupMode
        )
    }

    fun setupIndicationSessionByUuid(
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationByUuid(
            characteristicUuid,
            CharacteristicObservationMode.INDICATION,
            setupMode
        )
    }

    /**
     * Observe notifications from a characteristic by service UUID and characteristic UUID.
     */
    fun observeCharacteristicByUuid(serviceUuid: UUID, characteristicUuid: UUID): Flow<ByteArray>

    /**
     * Observe notifications or indications from a characteristic by service UUID and
     * characteristic UUID with explicit mode selection.
     */
    fun observeCharacteristicByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        mode: CharacteristicObservationMode
    ): Flow<ByteArray>

    /**
     * Nested setup API by service UUID and characteristic UUID.
     */
    fun setupCharacteristicObservationByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        mode: CharacteristicObservationMode,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT
    ): Flow<Flow<ByteArray>>

    /**
     * Convenience alias for observing notifications only by service UUID and characteristic UUID.
     */
    fun setupNotificationByUuid(serviceUuid: UUID, characteristicUuid: UUID): Flow<ByteArray> {
        return observeCharacteristicByUuid(
            serviceUuid,
            characteristicUuid,
            CharacteristicObservationMode.NOTIFICATION
        )
    }

    /**
     * Overload that preserves the outer setup Flow.
     */
    fun setupNotificationByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationByUuid(
            serviceUuid,
            characteristicUuid,
            CharacteristicObservationMode.NOTIFICATION,
            setupMode
        )
    }

    /**
     * Convenience alias for observing indications only by service UUID and characteristic UUID.
     */
    fun setupIndicationByUuid(serviceUuid: UUID, characteristicUuid: UUID): Flow<ByteArray> {
        return observeCharacteristicByUuid(
            serviceUuid,
            characteristicUuid,
            CharacteristicObservationMode.INDICATION
        )
    }

    /**
     * Overload that preserves the outer setup Flow.
     */
    fun setupIndicationByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationByUuid(
            serviceUuid,
            characteristicUuid,
            CharacteristicObservationMode.INDICATION,
            setupMode
        )
    }

    fun setupNotificationSessionByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationByUuid(
            serviceUuid,
            characteristicUuid,
            CharacteristicObservationMode.NOTIFICATION,
            setupMode
        )
    }

    fun setupIndicationSessionByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationByUuid(
            serviceUuid,
            characteristicUuid,
            CharacteristicObservationMode.INDICATION,
            setupMode
        )
    }

    /**
     * Read a descriptor value by service UUID, characteristic UUID, and descriptor UUID.
     */
    suspend fun readDescriptorByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray

    /**
     * Write a descriptor value by service UUID, characteristic UUID, and descriptor UUID.
     */
    suspend fun writeDescriptorByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        value: ByteArray,
        config: OperationConfig = OperationConfig.DEFAULT
    )

    /**
     * Initiate disconnect. Suspends until disconnected.
     */
    suspend fun disconnect()

    /**
     * Close the connection and release all resources.
     * After calling this method, the connection cannot be reused.
     * To reconnect, create a new connection via [FlowBleClient.connect].
     */
    fun close()
}
