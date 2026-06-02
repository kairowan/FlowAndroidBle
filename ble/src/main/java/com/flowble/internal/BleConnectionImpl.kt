package com.flowble.internal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import com.flowble.BleConnection
import com.flowble.BleLogger
import com.flowble.BondingManager
import com.flowble.LongWriteAckStrategy
import com.flowble.LongWriteOperationBuilder
import com.flowble.LongWriteRetryStrategy
import com.flowble.NotificationSetupMode
import com.flowble.QueuedGattCallbackType
import com.flowble.QueuedGattOperationScope
import com.flowble.exception.BleGattCallbackTimeoutException
import com.flowble.exception.BleGattOperationType
import com.flowble.exception.BleException
import com.flowble.model.BlePhy
import com.flowble.exception.ConnectionException
import com.flowble.exception.NotConnectedException
import com.flowble.exception.BleGattCannotStartException
import com.flowble.exception.OperationFailedException
import com.flowble.exception.TimeoutException
import com.flowble.model.BleCharacteristic
import com.flowble.model.BleDescriptor
import com.flowble.model.BleService
import com.flowble.model.BondState
import com.flowble.model.CharacteristicObservationMode
import com.flowble.model.CharacteristicProperty
import com.flowble.model.ConnectionConfig
import com.flowble.model.ConnectionPriority
import com.flowble.model.ConnectionState
import com.flowble.model.OperationConfig
import com.flowble.model.PhyRequest
import com.flowble.model.WriteType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Implementation of [BleConnection] using [GattCallbackRouter].
 *
 * This class bridges the public BLE connection API with the internal GATT callback routing system.
 * All GATT operations are serialized through the operation queue and router to prevent conflicts.
 */
@SuppressLint("MissingPermission")
internal class BleConnectionImpl(
    context: Context,
    private val router: GattCallbackRouter,
    private val connectionConfig: ConnectionConfig,
    private val onConnectionStateChanged: ((ConnectionState) -> Unit)? = null,
    private val onClosed: (() -> Unit)? = null
) : BleConnection {

    override val connectionState: StateFlow<ConnectionState> = router.connectionState
    override val bondState: Flow<BondState> by lazy(LazyThreadSafetyMode.NONE) {
        BondingManager(context.applicationContext).observeBondState(router.getGattOrThrow().device)
    }
    override val mtu: StateFlow<Int> = router.mtu
    override val phy: StateFlow<BlePhy?> = router.phy
    override val servicesState = MutableStateFlow<List<BleService>?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = CharacteristicCache()
    private val operationQueue = GattOperationQueue(
        defaultTimeoutMs = connectionConfig.operationTimeout.takeIf { it > 0 } ?: DEFAULT_OPERATION_TIMEOUT_MS,
        defaultRetryCount = connectionConfig.retryCount.coerceAtLeast(0),
        defaultRetryDelayMs = connectionConfig.retryDelay.coerceAtLeast(0L)
    )
    private val observationMutex = Mutex()
    private val activeObservations = ActiveObservationStore<GattCallbackRouter.NotificationKey, ActiveObservation>()
    private val closeNotified = AtomicBoolean(false)

    init {
        if (onConnectionStateChanged != null || onClosed != null) {
            scope.launch {
                router.connectionState.collect { state ->
                    handleObservedConnectionState(state)
                }
            }
        }
    }

    override suspend fun discoverServices(): List<BleService> {
        ensureConnected()
        BleLogger.logGattOperation("discoverServices")

        val result = executeGattOperation(operationType = BleGattOperationType.SERVICE_DISCOVERY) { timeoutMs ->
            router.executeOperation<List<BleService>>(
                timeoutMs = timeoutMs,
                operationType = BleGattOperationType.SERVICE_DISCOVERY
            ) {
                router.getGattOrThrow().discoverServices()
            }
        }
        servicesState.value = result
        cache.cacheServices(result)

        BleLogger.d("Discovered ${result.size} services")
        return result
    }

    override suspend fun getServices(forceRefresh: Boolean): List<BleService> {
        ensureServicesDiscovered(forceRefresh)
        return servicesState.value.orEmpty()
    }

    override suspend fun getService(
        serviceUuid: UUID,
        forceRefresh: Boolean
    ): BleService {
        ensureServicesDiscovered(forceRefresh)
        return cache.requireService(serviceUuid)
    }

    override suspend fun getCharacteristic(
        characteristicUuid: UUID,
        forceRefresh: Boolean
    ): BleCharacteristic {
        ensureServicesDiscovered(forceRefresh)
        return cache.requireCharacteristic(characteristicUuid)
    }

    override suspend fun getCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        forceRefresh: Boolean
    ): BleCharacteristic {
        ensureServicesDiscovered(forceRefresh)
        return cache.requireCharacteristic(serviceUuid, characteristicUuid)
    }

    override suspend fun getDescriptor(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        forceRefresh: Boolean
    ): BleDescriptor {
        ensureServicesDiscovered(forceRefresh)
        return cache.requireDescriptor(serviceUuid, characteristicUuid, descriptorUuid)
    }

    override suspend fun readCharacteristic(
        characteristic: BleCharacteristic,
        config: OperationConfig
    ): ByteArray {
        ensureConnected()
        requireReadable(characteristic)
        val androidChar = characteristic.androidCharacteristic
            ?: throw IllegalArgumentException("Characteristic not bound to Android object")

        BleLogger.logGattOperation("readCharacteristic", "uuid=${characteristic.uuid}")

        return executeGattOperation(config, BleGattOperationType.CHARACTERISTIC_READ) { timeoutMs ->
            router.executeOperation(
                timeoutMs = timeoutMs,
                operationType = BleGattOperationType.CHARACTERISTIC_READ
            ) {
                router.getGattOrThrow().readCharacteristic(androidChar)
            }
        }
    }

    override suspend fun writeCharacteristic(
        characteristic: BleCharacteristic,
        value: ByteArray,
        writeType: WriteType,
        config: OperationConfig
    ): ByteArray {
        ensureConnected()
        requireWritable(characteristic, writeType)
        val androidChar = characteristic.androidCharacteristic
            ?: throw IllegalArgumentException("Characteristic not bound to Android object")

        BleLogger.logGattOperation("writeCharacteristic", "uuid=${characteristic.uuid}, size=${value.size}")

        executeGattOperation(config, BleGattOperationType.CHARACTERISTIC_WRITE) { timeoutMs ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                router.executeOperation<Unit>(
                    timeoutMs = timeoutMs,
                    operationType = BleGattOperationType.CHARACTERISTIC_WRITE
                ) {
                    router.getGattOrThrow().writeCharacteristic(
                        androidChar,
                        value,
                        writeType.toAndroid()
                    ) == BluetoothStatusCodes.SUCCESS
                }
            } else {
                @Suppress("DEPRECATION")
                androidChar.value = value
                @Suppress("DEPRECATION")
                androidChar.writeType = writeType.toAndroid()
                router.executeOperation<Unit>(
                    timeoutMs = timeoutMs,
                    operationType = BleGattOperationType.CHARACTERISTIC_WRITE
                ) {
                    @Suppress("DEPRECATION")
                    router.getGattOrThrow().writeCharacteristic(androidChar)
                }
            }
        }

        return value
    }

    override suspend fun writeCharacteristicLong(
        characteristic: BleCharacteristic,
        value: ByteArray,
        writeType: WriteType,
        maxChunkSize: Int?,
        interChunkDelayMs: Long,
        config: OperationConfig
    ): ByteArray {
        return writeCharacteristicLongInternal(
            target = LongWriteTarget.Characteristic(characteristic),
            value = value,
            writeType = writeType,
            maxChunkSize = maxChunkSize,
            interChunkDelayMs = interChunkDelayMs,
            config = config,
            ackStrategy = null,
            retryStrategy = null
        )
    }

    override fun observeCharacteristic(characteristic: BleCharacteristic): Flow<ByteArray> {
        return observeCharacteristic(characteristic, CharacteristicObservationMode.AUTO)
    }

    override fun observeCharacteristic(
        characteristic: BleCharacteristic,
        mode: CharacteristicObservationMode
    ): Flow<ByteArray> {
        return callbackFlow {
            if (connectionState.value != ConnectionState.Connected) {
                throw NotConnectedException()
            }

            val observationSetup = requireObservable(
                characteristic = characteristic,
                mode = mode,
                setupMode = NotificationSetupMode.DEFAULT
            )
            val androidChar = characteristic.androidCharacteristic
                ?: throw IllegalArgumentException("Characteristic not bound to Android object")

            val gatt = router.getGattOrThrow()
            val activeObservation = acquireObservation(
                gatt = gatt,
                characteristic = androidChar,
                characteristicUuid = characteristic.uuid,
                observationSetup = observationSetup
            )

            val job = launch {
                observationEvents(activeObservation, characteristic.uuid).collect { data ->
                    trySend(data)
                }
            }

            awaitClose {
                job.cancel()
                scope.launch {
                    releaseObservation(
                        gatt = gatt,
                        characteristic = androidChar,
                        observation = activeObservation
                    )
                }
            }
        }
    }

    override fun setupCharacteristicObservation(
        characteristic: BleCharacteristic,
        mode: CharacteristicObservationMode,
        setupMode: NotificationSetupMode
    ): Flow<Flow<ByteArray>> {
        return callbackFlow {
            if (connectionState.value != ConnectionState.Connected) {
                throw NotConnectedException()
            }

            val observationSetup = requireObservable(
                characteristic = characteristic,
                mode = mode,
                setupMode = setupMode
            )
            val androidChar = characteristic.androidCharacteristic
                ?: throw IllegalArgumentException("Characteristic not bound to Android object")

            val gatt = router.getGattOrThrow()
            val activeObservation = acquireObservation(
                gatt = gatt,
                characteristic = androidChar,
                characteristicUuid = characteristic.uuid,
                observationSetup = observationSetup
            )

            trySend(observationEvents(activeObservation, characteristic.uuid))

            awaitClose {
                scope.launch {
                    releaseObservation(
                        gatt = gatt,
                        characteristic = androidChar,
                        observation = activeObservation
                    )
                }
            }
        }
    }

    override suspend fun readDescriptor(
        descriptor: BleDescriptor,
        config: OperationConfig
    ): ByteArray {
        ensureConnected()
        val androidDesc = descriptor.androidDescriptor
            ?: throw IllegalArgumentException("Descriptor not bound to Android object")

        BleLogger.logGattOperation("readDescriptor", "uuid=${descriptor.uuid}")

        return executeGattOperation(config, BleGattOperationType.DESCRIPTOR_READ) { timeoutMs ->
            router.executeOperation(
                timeoutMs = timeoutMs,
                operationType = BleGattOperationType.DESCRIPTOR_READ
            ) {
                router.getGattOrThrow().readDescriptor(androidDesc)
            }
        }
    }

    override suspend fun writeDescriptor(
        descriptor: BleDescriptor,
        value: ByteArray,
        config: OperationConfig
    ) {
        ensureConnected()
        val androidDesc = descriptor.androidDescriptor
            ?: throw IllegalArgumentException("Descriptor not bound to Android object")

        BleLogger.logGattOperation("writeDescriptor", "uuid=${descriptor.uuid}")

        executeGattOperation(config, BleGattOperationType.DESCRIPTOR_WRITE) { timeoutMs ->
            router.executeOperation<Unit>(
                timeoutMs = timeoutMs,
                operationType = BleGattOperationType.DESCRIPTOR_WRITE
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    router.getGattOrThrow().writeDescriptor(
                        androidDesc,
                        value
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    androidDesc.value = value
                    @Suppress("DEPRECATION")
                    router.getGattOrThrow().writeDescriptor(androidDesc)
                }
            }
        }
    }

    override suspend fun requestMtu(mtu: Int, config: OperationConfig): Int {
        ensureConnected()
        require(mtu in DEFAULT_MTU..517) {
            "Requested MTU must be between $DEFAULT_MTU and 517, was $mtu"
        }
        BleLogger.logGattOperation("requestMtu", "mtu=$mtu")

        return executeGattOperation(config, BleGattOperationType.MTU_CHANGED) { timeoutMs ->
            router.executeOperation(
                timeoutMs = timeoutMs,
                operationType = BleGattOperationType.MTU_CHANGED
            ) {
                router.getGattOrThrow().requestMtu(mtu)
            }
        }
    }

    override suspend fun requestConnectionPriority(
        priority: ConnectionPriority,
        settleDelayMs: Long,
        config: OperationConfig
    ) {
        ensureConnected()
        require(settleDelayMs >= 0) { "settleDelayMs must be >= 0" }

        BleLogger.logGattOperation(
            "requestConnectionPriority",
            "priority=$priority, settleDelayMs=$settleDelayMs"
        )

        executeGattOperation(config, BleGattOperationType.CONNECTION_PRIORITY_CHANGE) {
            val started = router.getGattOrThrow().requestConnectionPriority(priority.toAndroid())
            if (!started) {
                throw BleGattCannotStartException(
                    operationType = BleGattOperationType.CONNECTION_PRIORITY_CHANGE,
                    macAddress = router.getGattOrThrow().device.address
                )
            }
            if (settleDelayMs > 0) {
                delay(settleDelayMs)
            }
        }
    }

    override suspend fun readRssi(config: OperationConfig): Int {
        ensureConnected()
        return executeGattOperation(config, BleGattOperationType.READ_RSSI) { timeoutMs ->
            router.executeOperation(
                timeoutMs = timeoutMs,
                operationType = BleGattOperationType.READ_RSSI
            ) {
                router.getGattOrThrow().readRemoteRssi()
            }
        }
    }

    override suspend fun readPhy(config: OperationConfig): BlePhy {
        ensureConnected()
        if (!isPhySupported()) {
            throw BleException("PHY operations require Android 8.0 (API 26) or newer")
        }
        BleLogger.logGattOperation("readPhy")

        return executeGattOperation(config, BleGattOperationType.PHY_READ) { timeoutMs ->
            router.executeOperation(
                timeoutMs = timeoutMs,
                expectedCallbackType = GattCallbackRouter.PendingCallbackType.PHY_READ,
                operationType = BleGattOperationType.PHY_READ
            ) {
                router.getGattOrThrow().readPhy()
                true
            }
        }
    }

    override suspend fun requestPhy(
        request: PhyRequest,
        config: OperationConfig
    ): BlePhy {
        ensureConnected()
        if (!isPhySupported()) {
            throw BleException("PHY operations require Android 8.0 (API 26) or newer")
        }

        val txMask = request.txMask()
        val rxMask = request.rxMask()
        BleLogger.logGattOperation(
            "requestPhy",
            "txMask=$txMask, rxMask=$rxMask, option=${request.option}"
        )

        return executeGattOperation(config, BleGattOperationType.PHY_UPDATE) { timeoutMs ->
            router.executeOperation(
                timeoutMs = timeoutMs,
                expectedCallbackType = GattCallbackRouter.PendingCallbackType.PHY_UPDATE,
                operationType = BleGattOperationType.PHY_UPDATE
            ) {
                router.getGattOrThrow().setPreferredPhy(
                    txMask,
                    rxMask,
                    request.option.toAndroid()
                )
                true
            }
        }
    }

    override fun createNewLongWriteBuilder(): LongWriteOperationBuilder {
        return LongWriteOperationBuilderImpl { request ->
            writeCharacteristicLongInternal(
                target = request.target,
                value = request.value,
                writeType = request.writeType,
                maxChunkSize = request.maxChunkSize,
                interChunkDelayMs = request.interChunkDelayMs,
                config = request.config,
                ackStrategy = request.ackStrategy,
                retryStrategy = request.retryStrategy
            )
        }
    }

    override suspend fun <T> queue(
        config: OperationConfig,
        operation: suspend QueuedGattOperationScope.() -> T
    ): T {
        ensureConnected()
        BleLogger.logGattOperation("queueCustomOperation")

        return executeGattOperation(config, BleGattOperationType.CUSTOM) { timeoutMs ->
            val scope = QueuedGattOperationScopeImpl(
                gatt = router.getGattOrThrow()
            ) { callbackType, queuedOperation ->
                router.executeOperation(
                    timeoutMs = timeoutMs,
                    expectedCallbackType = callbackType.toInternal(),
                    operationType = callbackType.toOperationType(),
                    operation = queuedOperation
                )
            }

            scope.operation()
        }
    }

    override suspend fun readCharacteristicByUuid(
        characteristicUuid: UUID,
        config: OperationConfig
    ): ByteArray {
        val characteristic = getCharacteristic(characteristicUuid)
        return readCharacteristic(characteristic, config)
    }

    override suspend fun readCharacteristicByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        config: OperationConfig
    ): ByteArray {
        val characteristic = getCharacteristic(serviceUuid, characteristicUuid)
        return readCharacteristic(characteristic, config)
    }

    override suspend fun writeCharacteristicByUuid(
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: WriteType,
        config: OperationConfig
    ): ByteArray {
        val characteristic = getCharacteristic(characteristicUuid)
        return writeCharacteristic(characteristic, value, writeType, config)
    }

    override suspend fun writeCharacteristicByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: WriteType,
        config: OperationConfig
    ): ByteArray {
        val characteristic = getCharacteristic(serviceUuid, characteristicUuid)
        return writeCharacteristic(characteristic, value, writeType, config)
    }

    override suspend fun writeCharacteristicLongByUuid(
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: WriteType,
        maxChunkSize: Int?,
        interChunkDelayMs: Long,
        config: OperationConfig
    ): ByteArray {
        return writeCharacteristicLongInternal(
            target = LongWriteTarget.CharacteristicUuid(characteristicUuid),
            value = value,
            writeType = writeType,
            maxChunkSize = maxChunkSize,
            interChunkDelayMs = interChunkDelayMs,
            config = config,
            ackStrategy = null,
            retryStrategy = null
        )
    }

    override suspend fun writeCharacteristicLongByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: WriteType,
        maxChunkSize: Int?,
        interChunkDelayMs: Long,
        config: OperationConfig
    ): ByteArray {
        return writeCharacteristicLongInternal(
            target = LongWriteTarget.ServiceCharacteristicUuid(serviceUuid, characteristicUuid),
            value = value,
            writeType = writeType,
            maxChunkSize = maxChunkSize,
            interChunkDelayMs = interChunkDelayMs,
            config = config,
            ackStrategy = null,
            retryStrategy = null
        )
    }

    override fun observeCharacteristicByUuid(characteristicUuid: UUID): Flow<ByteArray> {
        return observeCharacteristicByUuid(characteristicUuid, CharacteristicObservationMode.AUTO)
    }

    override fun observeCharacteristicByUuid(
        characteristicUuid: UUID,
        mode: CharacteristicObservationMode
    ): Flow<ByteArray> {
        return flow {
            val characteristic = getCharacteristic(characteristicUuid)
            emitAll(observeCharacteristic(characteristic, mode))
        }
    }

    override fun setupCharacteristicObservationByUuid(
        characteristicUuid: UUID,
        mode: CharacteristicObservationMode,
        setupMode: NotificationSetupMode
    ): Flow<Flow<ByteArray>> {
        return flow {
            val characteristic = getCharacteristic(characteristicUuid)
            emitAll(setupCharacteristicObservation(characteristic, mode, setupMode))
        }
    }

    override fun observeCharacteristicByUuid(serviceUuid: UUID, characteristicUuid: UUID): Flow<ByteArray> {
        return observeCharacteristicByUuid(serviceUuid, characteristicUuid, CharacteristicObservationMode.AUTO)
    }

    override fun observeCharacteristicByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        mode: CharacteristicObservationMode
    ): Flow<ByteArray> {
        return flow {
            val characteristic = getCharacteristic(serviceUuid, characteristicUuid)
            emitAll(observeCharacteristic(characteristic, mode))
        }
    }

    override fun setupCharacteristicObservationByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        mode: CharacteristicObservationMode,
        setupMode: NotificationSetupMode
    ): Flow<Flow<ByteArray>> {
        return flow {
            val characteristic = getCharacteristic(serviceUuid, characteristicUuid)
            emitAll(setupCharacteristicObservation(characteristic, mode, setupMode))
        }
    }

    override suspend fun readDescriptorByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        config: OperationConfig
    ): ByteArray {
        val descriptor = getDescriptor(serviceUuid, characteristicUuid, descriptorUuid)
        return readDescriptor(descriptor, config)
    }

    override suspend fun writeDescriptorByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        value: ByteArray,
        config: OperationConfig
    ) {
        val descriptor = getDescriptor(serviceUuid, characteristicUuid, descriptorUuid)
        writeDescriptor(descriptor, value, config)
    }

    override suspend fun disconnect() {
        BleLogger.logConnectionEvent("disconnecting")

        val currentState = connectionState.value
        if (currentState == ConnectionState.Connected || currentState == ConnectionState.Connecting) {
            try {
                router.getGattOrThrow().disconnect()
            } catch (_: Exception) {
                // Ignore disconnect errors
            }

            try {
                withTimeout(5000L) {
                    connectionState.first { it == ConnectionState.Disconnected }
                }
            } catch (_: Exception) {
                // Timeout - force cleanup
            }
        }
        close()
    }

    override fun close() {
        BleLogger.logConnectionEvent("closing")
        operationQueue.cancelAll(ConnectionException("Connection closed"))
        clearDiscoveredServices()
        router.cleanup()
        handleObservedConnectionState(ConnectionState.Disconnected)
        scope.cancel()
    }

    private fun ensureConnected() {
        if (connectionState.value != ConnectionState.Connected) {
            throw NotConnectedException()
        }
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
    private fun isPhySupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    private fun QueuedGattCallbackType.toInternal(): GattCallbackRouter.PendingCallbackType = when (this) {
        QueuedGattCallbackType.GENERIC -> GattCallbackRouter.PendingCallbackType.GENERIC
        QueuedGattCallbackType.PHY_READ -> GattCallbackRouter.PendingCallbackType.PHY_READ
        QueuedGattCallbackType.PHY_UPDATE -> GattCallbackRouter.PendingCallbackType.PHY_UPDATE
    }

    private fun QueuedGattCallbackType.toOperationType(): BleGattOperationType = when (this) {
        QueuedGattCallbackType.GENERIC -> BleGattOperationType.CUSTOM
        QueuedGattCallbackType.PHY_READ -> BleGattOperationType.PHY_READ
        QueuedGattCallbackType.PHY_UPDATE -> BleGattOperationType.PHY_UPDATE
    }

    private suspend fun writeCharacteristicLongInternal(
        target: LongWriteTarget,
        value: ByteArray,
        writeType: WriteType,
        maxChunkSize: Int?,
        interChunkDelayMs: Long,
        config: OperationConfig,
        ackStrategy: LongWriteAckStrategy?,
        retryStrategy: LongWriteRetryStrategy?
    ): ByteArray {
        require(interChunkDelayMs >= 0) { "interChunkDelayMs must be >= 0" }

        val characteristic = resolveLongWriteTarget(target)
        val resolvedChunkSize = maxChunkSize ?: (mtu.value - GATT_WRITE_OVERHEAD).coerceAtLeast(1)
        return executeLongWriteChunks(
            payload = value,
            maxChunkSize = resolvedChunkSize,
            interChunkDelayMs = interChunkDelayMs,
            ackStrategy = ackStrategy,
            retryStrategy = retryStrategy
        ) { chunk ->
            writeCharacteristic(characteristic, chunk, writeType, config)
        }
    }

    private suspend fun resolveLongWriteTarget(target: LongWriteTarget): BleCharacteristic {
        return when (target) {
            is LongWriteTarget.Characteristic -> target.characteristic
            is LongWriteTarget.CharacteristicUuid -> {
                getCharacteristic(target.characteristicUuid)
            }
            is LongWriteTarget.ServiceCharacteristicUuid -> {
                getCharacteristic(target.serviceUuid, target.characteristicUuid)
            }
        }
    }

    private suspend fun ensureServicesDiscovered(forceRefresh: Boolean) {
        if (forceRefresh || !cache.hasData()) {
            discoverServices()
        }
    }

    private suspend fun <T> executeGattOperation(
        config: OperationConfig = OperationConfig.DEFAULT,
        operationType: BleGattOperationType = BleGattOperationType.CUSTOM,
        operation: suspend (timeoutMs: Long) -> T
    ): T {
        return try {
            operationQueue.enqueue(config, operation)
        } catch (e: TimeoutCancellationException) {
            val timeoutMs = if (config.timeout > 0) {
                config.timeout
            } else {
                connectionConfig.operationTimeout.takeIf { it > 0 } ?: DEFAULT_OPERATION_TIMEOUT_MS
            }
            throw BleGattCallbackTimeoutException(
                timeoutMs = timeoutMs,
                operationType = operationType,
                macAddress = runCatching { router.getGattOrThrow().device.address }.getOrNull(),
                cause = e
            )
        } catch (e: BleGattCallbackTimeoutException) {
            throw e
        }
    }

    private fun requireReadable(characteristic: BleCharacteristic) {
        if (CharacteristicProperty.READ !in characteristic.properties) {
            throw IllegalArgumentException("Characteristic ${characteristic.uuid} is not readable")
        }
    }

    private fun requireWritable(characteristic: BleCharacteristic, writeType: WriteType) {
        val supportsWrite = when (writeType) {
            WriteType.DEFAULT -> {
                CharacteristicProperty.WRITE in characteristic.properties ||
                    CharacteristicProperty.SIGNED_WRITE in characteristic.properties
            }

            WriteType.NO_RESPONSE -> CharacteristicProperty.WRITE_NO_RESPONSE in characteristic.properties
        }

        if (!supportsWrite) {
            throw IllegalArgumentException(
                "Characteristic ${characteristic.uuid} does not support write type $writeType"
            )
        }
    }

    private suspend fun writeDescriptorValue(descriptor: BluetoothGattDescriptor, value: ByteArray) {
        executeGattOperation(operationType = BleGattOperationType.DESCRIPTOR_WRITE) { timeoutMs ->
            router.executeOperation<Unit>(
                timeoutMs = timeoutMs,
                operationType = BleGattOperationType.DESCRIPTOR_WRITE
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    router.getGattOrThrow().writeDescriptor(
                        descriptor,
                        value
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    router.getGattOrThrow().writeDescriptor(descriptor)
                }
            }
        }
    }

    private suspend fun acquireObservation(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        characteristicUuid: UUID,
        observationSetup: ObservationSetup
    ): ActiveObservation {
        observationMutex.lock()
        try {
            val key = router.notificationKeyOf(characteristic)
            when (val result = activeObservations.acquire(key, observationSetup.kind)) {
                is ActiveObservationStore.AcquireResult.Existing -> return result.session
                ActiveObservationStore.AcquireResult.CreateNew -> Unit
            }

            BleLogger.logGattOperation(
                "observeCharacteristic",
                "uuid=$characteristicUuid, instanceId=${characteristic.instanceId}, mode=${observationSetup.label}"
            )

            val notificationChannel = Channel<ByteArray>(NOTIFICATION_CHANNEL_BUFFER)
            if (router.notificationChannels.putIfAbsent(key, notificationChannel) != null) {
                notificationChannel.close()
                throw OperationFailedException(
                    "Internal observation conflict for characteristic $characteristicUuid " +
                        "(instanceId=${characteristic.instanceId})"
                )
            }

            try {
                if (!gatt.setCharacteristicNotification(characteristic, true)) {
                    throw OperationFailedException(
                        "Failed to enable local ${observationSetup.label} for characteristic $characteristicUuid"
                    )
                }

                val cccdDescriptor = resolveCccdDescriptor(
                    characteristic = characteristic,
                    characteristicUuid = characteristicUuid,
                    observationSetup = observationSetup
                )

                try {
                    if (observationSetup.cccdWriteMode == ObservationCccdWriteMode.BEFORE_EMIT) {
                        writeDescriptorValue(cccdDescriptor ?: error("CCCD descriptor expected"), observationSetup.enableValue)
                    }
                } catch (e: Exception) {
                    gatt.setCharacteristicNotification(characteristic, false)
                    throw e
                }

                val events = MutableSharedFlow<ByteArray>(
                    extraBufferCapacity = NOTIFICATION_CHANNEL_BUFFER,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
                )
                val observation = activeObservations.register(
                    key = key,
                    kind = observationSetup.kind,
                    session = ActiveObservation(
                        key = key,
                        sourceChannel = notificationChannel,
                        events = events,
                        termination = CompletableDeferred<Throwable?>()
                    )
                )

                startObservationRelay(observation)
                if (observationSetup.cccdWriteMode == ObservationCccdWriteMode.AFTER_EMIT) {
                    val descriptor = cccdDescriptor ?: error("CCCD descriptor expected")
                    scope.launch {
                        try {
                            writeDescriptorValue(descriptor, observationSetup.enableValue)
                        } catch (e: Exception) {
                            terminateObservation(
                                gatt = gatt,
                                characteristic = characteristic,
                                observation = observation,
                                cause = e
                            )
                        }
                    }
                }

                return observation
            } catch (e: Exception) {
                router.notificationChannels.remove(key, notificationChannel)
                notificationChannel.close(e)
                throw e
            }
        } catch (e: IllegalStateException) {
            throw OperationFailedException(
                "Conflicting observation already active for characteristic $characteristicUuid " +
                    "(instanceId=${characteristic.instanceId}): ${e.message}"
            )
        } finally {
            observationMutex.unlock()
        }
    }

    private suspend fun releaseObservation(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        observation: ActiveObservation
    ) {
        observationMutex.lock()
        try {
            val shouldTearDown = activeObservations.release(observation.key, observation)
            if (!shouldTearDown) {
                return
            }

            router.notificationChannels.remove(observation.key, observation.sourceChannel)
            observation.sourceChannel.close()

            try {
                gatt.setCharacteristicNotification(characteristic, false)
                val disableDescriptor = characteristic.getDescriptor(CCCD_UUID)
                if (disableDescriptor != null) {
                    writeDescriptorValue(disableDescriptor, DISABLE_NOTIFICATION_VALUE)
                }
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        } finally {
            observationMutex.unlock()
        }
    }

    private fun requireObservable(
        characteristic: BleCharacteristic,
        mode: CharacteristicObservationMode,
        setupMode: NotificationSetupMode
    ): ObservationSetup {
        return resolveObservationSetup(
            characteristicUuid = characteristic.uuid,
            properties = characteristic.properties,
            mode = mode,
            setupMode = setupMode
        )
    }

    private fun observationEvents(observation: ActiveObservation, characteristicUuid: UUID): Flow<ByteArray> {
        return callbackFlow {
            val eventsJob = launch {
                observation.events.collect { data ->
                    BleLogger.d("Notification received", "uuid=$characteristicUuid, size=${data.size}")
                    trySend(data)
                }
            }
            val terminationJob = launch {
                val cause = observation.termination.await()
                if (cause == null) {
                    close()
                } else {
                    close(cause)
                }
            }

            awaitClose {
                eventsJob.cancel()
                terminationJob.cancel()
            }
        }
    }

    private fun startObservationRelay(observation: ActiveObservation) {
        scope.launch {
            var failure: Throwable? = null
            try {
                for (data in observation.sourceChannel) {
                    observation.events.emit(data)
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                observation.termination.complete(failure)
                cleanupObservationRegistration(observation)
            }
        }
    }

    private suspend fun cleanupObservationRegistration(observation: ActiveObservation) {
        observationMutex.lock()
        try {
            activeObservations.remove(observation.key, observation)
            router.notificationChannels.remove(observation.key, observation.sourceChannel)
        } finally {
            observationMutex.unlock()
        }
    }

    private fun resolveCccdDescriptor(
        characteristic: BluetoothGattCharacteristic,
        characteristicUuid: UUID,
        observationSetup: ObservationSetup
    ): BluetoothGattDescriptor? {
        if (observationSetup.cccdWriteMode == ObservationCccdWriteMode.SKIP) {
            return null
        }

        return characteristic.getDescriptor(CCCD_UUID)
            ?: throw OperationFailedException(
                "CCCD descriptor not found for characteristic $characteristicUuid"
            )
    }

    private suspend fun terminateObservation(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        observation: ActiveObservation,
        cause: Throwable
    ) {
        observationMutex.lock()
        try {
            val removed = activeObservations.remove(observation.key, observation)
            if (!removed) {
                return
            }

            router.notificationChannels.remove(observation.key, observation.sourceChannel)
            observation.sourceChannel.close(cause)

            try {
                gatt.setCharacteristicNotification(characteristic, false)
            } catch (_: Exception) {
                // Ignore cleanup errors after failed setup.
            }
        } finally {
            observationMutex.unlock()
        }
    }

    private data class ActiveObservation(
        val key: GattCallbackRouter.NotificationKey,
        val sourceChannel: Channel<ByteArray>,
        val events: MutableSharedFlow<ByteArray>,
        val termination: CompletableDeferred<Throwable?>
    )

    private fun handleObservedConnectionState(state: ConnectionState) {
        onConnectionStateChanged?.invoke(state)
        if (state == ConnectionState.Disconnected) {
            clearDiscoveredServices()
        }
        if (state == ConnectionState.Disconnected && closeNotified.compareAndSet(false, true)) {
            onClosed?.invoke()
        }
    }

    private fun clearDiscoveredServices() {
        cache.clear()
        servicesState.value = null
    }
}
