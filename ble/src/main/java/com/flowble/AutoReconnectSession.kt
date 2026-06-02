package com.flowble

import com.flowble.model.AutoReconnectState
import com.flowble.model.AutoReconnectSnapshot
import com.flowble.model.BleCharacteristic
import com.flowble.model.BleConnectionSnapshot
import com.flowble.model.BleDescriptor
import com.flowble.model.BlePhy
import com.flowble.model.BleService
import com.flowble.model.CharacteristicObservationMode
import com.flowble.model.ConnectionConfig
import com.flowble.model.ConnectionPriority
import com.flowble.model.ConnectionState
import com.flowble.model.OperationConfig
import com.flowble.model.PhyOption
import com.flowble.model.PhyRequest
import com.flowble.model.PhyType
import com.flowble.model.WriteType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Keeps reconnecting a [FlowBleDevice] after disconnects or failed connection attempts.
 *
 * The session exposes the current [connection] as a [StateFlow] so callers can swap to the
 * latest [BleConnection] after a reconnect. It can also optionally attach a
 * [ConnectionSupervisor] to each successful connection to turn long periods of silence into
 * a disconnect/reconnect cycle.
 */
class AutoReconnectSession(
    private val device: FlowBleDevice,
    private val config: ConnectionConfig = ConnectionConfig(),
    private val reconnectDelayMs: Long = config.retryDelay.coerceAtLeast(0L),
    private val maxReconnectAttempts: Int = Int.MAX_VALUE,
    private val supervisorCheckIntervalMs: Long = 5000L,
    private val maxSilentDurationMs: Long? = null,
    private val discoverServicesOnConnect: Boolean = false,
    private val recoveryPlan: AutoReconnectRecoveryPlan? = null,
    private val onConnectionReady: (suspend BleConnection.() -> Unit)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    val connection: StateFlow<BleConnection?> get() = _connection.asStateFlow()
    val state: StateFlow<AutoReconnectState> get() = _state.asStateFlow()
    val activeConnectionState: StateFlow<ConnectionState?> get() = _activeConnectionState.asStateFlow()
    val activeConnectionSnapshot: StateFlow<BleConnectionSnapshot?> get() = _activeConnectionSnapshot.asStateFlow()
    val services: StateFlow<List<BleService>?> get() = _services.asStateFlow()
    val lastError: StateFlow<Throwable?> get() = _lastError.asStateFlow()
    val snapshot: StateFlow<AutoReconnectSnapshot> get() = _snapshot.asStateFlow()

    private val _connection = MutableStateFlow<BleConnection?>(null)
    private val _state = MutableStateFlow<AutoReconnectState>(AutoReconnectState.Idle)
    private val _activeConnectionState = MutableStateFlow<ConnectionState?>(null)
    private val _activeConnectionSnapshot = MutableStateFlow<BleConnectionSnapshot?>(null)
    private val _services = MutableStateFlow<List<BleService>?>(null)
    private val _lastError = MutableStateFlow<Throwable?>(null)
    private val _snapshot = MutableStateFlow(
        AutoReconnectSnapshot(
            state = AutoReconnectState.Idle,
            activeConnectionState = null,
            activeConnectionSnapshot = null,
            hasActiveConnection = false,
            lastError = null
        )
    )

    private var sessionJob: Job? = null
    private var activeSupervisor: ConnectionSupervisor? = null
    private var activeConnectionStateJob: Job? = null
    private var activeConnectionSnapshotJob: Job? = null
    private var activeServicesJob: Job? = null
    @Volatile
    private var pendingConnection: BleConnection? = null
    @Volatile
    private var stopRequested = false

    init {
        require(maxReconnectAttempts > 0) { "maxReconnectAttempts must be > 0" }
        require(reconnectDelayMs >= 0) { "reconnectDelayMs must be >= 0" }
        require(supervisorCheckIntervalMs > 0) { "supervisorCheckIntervalMs must be > 0" }
        if (maxSilentDurationMs != null) {
            require(maxSilentDurationMs > 0) { "maxSilentDurationMs must be > 0 when provided" }
        }
    }

    /**
     * Start maintaining the connection. Calling this repeatedly while already running is a no-op.
     */
    fun start() {
        if (sessionJob?.isActive == true) {
            return
        }

        stopRequested = false
        publishState(AutoReconnectState.Idle)
        publishLastError(null)
        sessionJob = scope.launch {
            runSession()
        }
    }

    /**
     * Stop reconnecting. When [disconnect] is true, the current active connection is also closed.
     */
    suspend fun stop(disconnect: Boolean = true) {
        stopRequested = true
        stopObservationHelpers()
        closePendingConnection()
        disconnectActiveConnectionIfRequested(disconnect)

        sessionJob?.cancelAndJoin()
        sessionJob = null
        pendingConnection = null
        publishConnection(null)
        publishState(AutoReconnectState.Stopped)
    }

    /**
     * Cancel reconnecting immediately without waiting for disconnect callbacks.
     *
     * This is useful from non-suspending teardown hooks such as `onDestroy()` or `onCleared()`.
     * When [disconnect] is true, the current connection is closed immediately.
     */
    fun cancel(disconnect: Boolean = true) {
        stopRequested = true
        stopObservationHelpers()
        closePendingConnection()
        if (disconnect) {
            _connection.value?.close()
        }
        sessionJob?.cancel()
        sessionJob = null
        pendingConnection = null
        publishConnection(null)
        publishState(AutoReconnectState.Stopped)
    }

    /**
     * Forward a data-received signal to the optional [ConnectionSupervisor].
     *
     * This matters only when [maxSilentDurationMs] was configured.
     */
    fun notifyDataReceived() {
        activeSupervisor?.notifyDataReceived()
    }

    /**
     * Return the current active connection, or suspend until one becomes available.
     *
     * If the session has not been started yet, or if it has already stopped or failed,
     * this throws instead of suspending forever.
     */
    suspend fun awaitConnection(): BleConnection {
        _connection.value?.let { return it }

        val currentState = _state.value
        if (currentState == AutoReconnectState.Idle && sessionJob?.isActive != true) {
            throw IllegalStateException("AutoReconnectSession has not been started")
        }
        if (currentState == AutoReconnectState.Stopped) {
            throw IllegalStateException("AutoReconnectSession has been stopped")
        }
        if (currentState is AutoReconnectState.Failed) {
            throw IllegalStateException(
                "AutoReconnectSession failed after ${currentState.attempts} attempts",
                currentState.lastError
            )
        }

        val (resolvedConnection, resolvedState) = combine(connection, state) { connection, state ->
            connection to state
        }.first { (connection, state) ->
            connection != null || state is AutoReconnectState.Failed || state == AutoReconnectState.Stopped
        }

        return resolvedConnection ?: when (resolvedState) {
            AutoReconnectState.Stopped -> throw IllegalStateException("AutoReconnectSession has been stopped")
            is AutoReconnectState.Failed -> throw IllegalStateException(
                "AutoReconnectSession failed after ${resolvedState.attempts} attempts",
                resolvedState.lastError
            )
            else -> throw IllegalStateException("AutoReconnectSession has no active connection")
        }
    }

    /**
     * Run an arbitrary block against the current active connection.
     *
     * This is the session-level escape hatch for higher-level operations that are not yet
     * surfaced directly on [AutoReconnectSession].
     */
    suspend fun <T> withConnection(
        block: suspend BleConnection.() -> T
    ): T {
        return awaitConnection().block()
    }

    suspend fun discoverServices(): List<BleService> {
        return awaitConnection().discoverServices()
    }

    suspend fun getServices(forceRefresh: Boolean = false): List<BleService> {
        return awaitConnection().getServices(forceRefresh)
    }

    suspend fun getService(
        serviceUuid: UUID,
        forceRefresh: Boolean = false
    ): BleService {
        return awaitConnection().getService(serviceUuid, forceRefresh)
    }

    suspend fun getCharacteristic(
        characteristicUuid: UUID,
        forceRefresh: Boolean = false
    ): BleCharacteristic {
        return awaitConnection().getCharacteristic(characteristicUuid, forceRefresh)
    }

    suspend fun getCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        forceRefresh: Boolean = false
    ): BleCharacteristic {
        return awaitConnection().getCharacteristic(serviceUuid, characteristicUuid, forceRefresh)
    }

    suspend fun getDescriptor(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        forceRefresh: Boolean = false
    ): BleDescriptor {
        return awaitConnection().getDescriptor(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            descriptorUuid = descriptorUuid,
            forceRefresh = forceRefresh
        )
    }

    suspend fun readCharacteristic(
        characteristic: BleCharacteristic,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return awaitConnection().readCharacteristicByUuid(
            serviceUuid = characteristic.serviceUuid,
            characteristicUuid = characteristic.uuid,
            config = config
        )
    }

    suspend fun readCharacteristicByUuid(
        characteristicUuid: UUID,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return awaitConnection().readCharacteristicByUuid(characteristicUuid, config)
    }

    suspend fun readCharacteristic(
        characteristicUuid: UUID,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return readCharacteristicByUuid(characteristicUuid, config)
    }

    suspend fun readCharacteristicByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return awaitConnection().readCharacteristicByUuid(serviceUuid, characteristicUuid, config)
    }

    suspend fun readCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return readCharacteristicByUuid(serviceUuid, characteristicUuid, config)
    }

    suspend fun writeCharacteristic(
        characteristic: BleCharacteristic,
        value: ByteArray,
        writeType: WriteType = WriteType.DEFAULT,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return awaitConnection().writeCharacteristicByUuid(
            serviceUuid = characteristic.serviceUuid,
            characteristicUuid = characteristic.uuid,
            value = value,
            writeType = writeType,
            config = config
        )
    }

    suspend fun writeCharacteristicByUuid(
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: WriteType = WriteType.DEFAULT,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return awaitConnection().writeCharacteristicByUuid(
            characteristicUuid = characteristicUuid,
            value = value,
            writeType = writeType,
            config = config
        )
    }

    suspend fun writeCharacteristic(
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: WriteType = WriteType.DEFAULT,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return writeCharacteristicByUuid(characteristicUuid, value, writeType, config)
    }

    suspend fun writeCharacteristicByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: WriteType = WriteType.DEFAULT,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return awaitConnection().writeCharacteristicByUuid(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            value = value,
            writeType = writeType,
            config = config
        )
    }

    suspend fun writeCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: WriteType = WriteType.DEFAULT,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return writeCharacteristicByUuid(serviceUuid, characteristicUuid, value, writeType, config)
    }

    suspend fun writeCharacteristicLong(
        characteristic: BleCharacteristic,
        value: ByteArray,
        writeType: WriteType = WriteType.DEFAULT,
        maxChunkSize: Int? = null,
        interChunkDelayMs: Long = 0L,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return awaitConnection().writeCharacteristicLongByUuid(
            serviceUuid = characteristic.serviceUuid,
            characteristicUuid = characteristic.uuid,
            value = value,
            writeType = writeType,
            maxChunkSize = maxChunkSize,
            interChunkDelayMs = interChunkDelayMs,
            config = config
        )
    }

    suspend fun writeCharacteristicLongByUuid(
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: WriteType = WriteType.DEFAULT,
        maxChunkSize: Int? = null,
        interChunkDelayMs: Long = 0L,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return awaitConnection().writeCharacteristicLongByUuid(
            characteristicUuid = characteristicUuid,
            value = value,
            writeType = writeType,
            maxChunkSize = maxChunkSize,
            interChunkDelayMs = interChunkDelayMs,
            config = config
        )
    }

    suspend fun writeCharacteristicLongByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: WriteType = WriteType.DEFAULT,
        maxChunkSize: Int? = null,
        interChunkDelayMs: Long = 0L,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return awaitConnection().writeCharacteristicLongByUuid(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            value = value,
            writeType = writeType,
            maxChunkSize = maxChunkSize,
            interChunkDelayMs = interChunkDelayMs,
            config = config
        )
    }

    suspend fun readDescriptorByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return awaitConnection().readDescriptorByUuid(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            descriptorUuid = descriptorUuid,
            config = config
        )
    }

    suspend fun readDescriptor(
        descriptor: BleDescriptor,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return awaitConnection().readDescriptorByUuid(
            serviceUuid = descriptor.serviceUuid,
            characteristicUuid = descriptor.characteristicUuid,
            descriptorUuid = descriptor.uuid,
            config = config
        )
    }

    suspend fun readDescriptor(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        config: OperationConfig = OperationConfig.DEFAULT
    ): ByteArray {
        return readDescriptorByUuid(serviceUuid, characteristicUuid, descriptorUuid, config)
    }

    suspend fun writeDescriptor(
        descriptor: BleDescriptor,
        value: ByteArray,
        config: OperationConfig = OperationConfig.DEFAULT
    ) {
        awaitConnection().writeDescriptorByUuid(
            serviceUuid = descriptor.serviceUuid,
            characteristicUuid = descriptor.characteristicUuid,
            descriptorUuid = descriptor.uuid,
            value = value,
            config = config
        )
    }

    suspend fun writeDescriptorByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        value: ByteArray,
        config: OperationConfig = OperationConfig.DEFAULT
    ) {
        awaitConnection().writeDescriptorByUuid(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            descriptorUuid = descriptorUuid,
            value = value,
            config = config
        )
    }

    suspend fun writeDescriptor(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID,
        value: ByteArray,
        config: OperationConfig = OperationConfig.DEFAULT
    ) {
        writeDescriptorByUuid(serviceUuid, characteristicUuid, descriptorUuid, value, config)
    }

    suspend fun requestMtu(
        mtu: Int,
        config: OperationConfig = OperationConfig.DEFAULT
    ): Int {
        return awaitConnection().requestMtu(mtu, config)
    }

    suspend fun requestConnectionPriority(
        priority: ConnectionPriority,
        settleDelayMs: Long = 500L,
        config: OperationConfig = OperationConfig.DEFAULT
    ) {
        awaitConnection().requestConnectionPriority(
            priority = priority,
            settleDelayMs = settleDelayMs,
            config = config
        )
    }

    suspend fun readRssi(config: OperationConfig = OperationConfig.DEFAULT): Int {
        return awaitConnection().readRssi(config)
    }

    suspend fun readPhy(config: OperationConfig = OperationConfig.DEFAULT): BlePhy {
        return awaitConnection().readPhy(config)
    }

    suspend fun requestPhy(
        request: PhyRequest,
        config: OperationConfig = OperationConfig.DEFAULT
    ): BlePhy {
        return awaitConnection().requestPhy(request, config)
    }

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

    suspend fun setPreferredPhy(
        request: PhyRequest,
        config: OperationConfig = OperationConfig.DEFAULT
    ): BlePhy {
        return requestPhy(request, config)
    }

    fun createNewLongWriteBuilder(): LongWriteOperationBuilder {
        return SessionLongWriteOperationBuilder { awaitConnection() }
    }

    suspend fun <T> queue(
        config: OperationConfig = OperationConfig.DEFAULT,
        operation: suspend QueuedGattOperationScope.() -> T
    ): T {
        return awaitConnection().queue(config, operation)
    }

    fun observeCharacteristic(characteristic: BleCharacteristic): Flow<ByteArray> {
        return observeCharacteristic(characteristic, CharacteristicObservationMode.AUTO)
    }

    fun observeCharacteristic(
        characteristic: BleCharacteristic,
        mode: CharacteristicObservationMode,
        notifySessionActivity: Boolean = true
    ): Flow<ByteArray> {
        return observeCharacteristicByUuid(
            serviceUuid = characteristic.serviceUuid,
            characteristicUuid = characteristic.uuid,
            mode = mode,
            notifySessionActivity = notifySessionActivity
        )
    }

    fun setupNotification(characteristic: BleCharacteristic): Flow<ByteArray> {
        return setupNotificationSession(characteristic).flattenSessionObservation()
    }

    fun setupNotification(
        characteristic: BleCharacteristic,
        setupMode: NotificationSetupMode
    ): Flow<ByteArray> {
        return setupNotificationSession(characteristic, setupMode).flattenSessionObservation()
    }

    fun setupIndication(characteristic: BleCharacteristic): Flow<ByteArray> {
        return setupIndicationSession(characteristic).flattenSessionObservation()
    }

    fun setupIndication(
        characteristic: BleCharacteristic,
        setupMode: NotificationSetupMode
    ): Flow<ByteArray> {
        return setupIndicationSession(characteristic, setupMode).flattenSessionObservation()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun setupCharacteristicObservation(
        characteristic: BleCharacteristic,
        mode: CharacteristicObservationMode,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT,
        notifySessionActivity: Boolean = true
    ): Flow<ByteArray> {
        return setupCharacteristicObservationSession(
            characteristic = characteristic,
            mode = mode,
            setupMode = setupMode,
            notifySessionActivity = notifySessionActivity
        ).flattenSessionObservation()
    }

    fun setupNotificationSession(
        characteristic: BleCharacteristic,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT,
        notifySessionActivity: Boolean = true
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationSession(
            characteristic = characteristic,
            mode = CharacteristicObservationMode.NOTIFICATION,
            setupMode = setupMode,
            notifySessionActivity = notifySessionActivity
        )
    }

    fun setupIndicationSession(
        characteristic: BleCharacteristic,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT,
        notifySessionActivity: Boolean = true
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationSession(
            characteristic = characteristic,
            mode = CharacteristicObservationMode.INDICATION,
            setupMode = setupMode,
            notifySessionActivity = notifySessionActivity
        )
    }

    fun setupCharacteristicObservationSession(
        characteristic: BleCharacteristic,
        mode: CharacteristicObservationMode,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT,
        notifySessionActivity: Boolean = true
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationSessionByUuid(
            serviceUuid = characteristic.serviceUuid,
            characteristicUuid = characteristic.uuid,
            mode = mode,
            setupMode = setupMode,
            notifySessionActivity = notifySessionActivity
        )
    }

    /**
     * Observe a characteristic continuously across reconnects.
     *
     * The returned Flow stays active while the session reconnects and automatically resumes
     * observation on the next connection.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeCharacteristicByUuid(
        characteristicUuid: UUID,
        mode: CharacteristicObservationMode = CharacteristicObservationMode.AUTO,
        notifySessionActivity: Boolean = true
    ): Flow<ByteArray> {
        return connection.flatMapLatest { activeConnection ->
            if (activeConnection == null) {
                emptyFlow()
            } else {
                activeConnection.observeCharacteristicByUuid(characteristicUuid, mode)
            }
        }.onEach {
            if (notifySessionActivity) {
                notifyDataReceived()
            }
        }
    }

    /**
     * Observe a characteristic continuously across reconnects with an explicit service UUID.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeCharacteristicByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        mode: CharacteristicObservationMode = CharacteristicObservationMode.AUTO,
        notifySessionActivity: Boolean = true
    ): Flow<ByteArray> {
        return connection.flatMapLatest { activeConnection ->
            if (activeConnection == null) {
                emptyFlow()
            } else {
                activeConnection.observeCharacteristicByUuid(serviceUuid, characteristicUuid, mode)
            }
        }.onEach {
            if (notifySessionActivity) {
                notifyDataReceived()
            }
        }
    }

    fun setupNotificationByUuid(
        characteristicUuid: UUID,
        notifySessionActivity: Boolean
    ): Flow<ByteArray> {
        return setupNotificationByUuid(
            characteristicUuid = characteristicUuid,
            setupMode = NotificationSetupMode.DEFAULT,
            notifySessionActivity = notifySessionActivity
        )
    }

    fun setupNotification(characteristicUuid: UUID): Flow<ByteArray> {
        return setupNotificationByUuid(characteristicUuid)
    }

    fun setupNotification(
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode
    ): Flow<ByteArray> {
        return setupNotificationByUuid(
            characteristicUuid = characteristicUuid,
            setupMode = setupMode
        )
    }

    fun setupNotificationByUuid(
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT,
        notifySessionActivity: Boolean = true
    ): Flow<ByteArray> {
        return setupNotificationSessionByUuid(
            characteristicUuid = characteristicUuid,
            setupMode = setupMode,
            notifySessionActivity = notifySessionActivity
        ).flattenSessionObservation()
    }

    fun setupIndicationByUuid(
        characteristicUuid: UUID,
        notifySessionActivity: Boolean
    ): Flow<ByteArray> {
        return setupIndicationByUuid(
            characteristicUuid = characteristicUuid,
            setupMode = NotificationSetupMode.DEFAULT,
            notifySessionActivity = notifySessionActivity
        )
    }

    fun setupIndication(characteristicUuid: UUID): Flow<ByteArray> {
        return setupIndicationByUuid(characteristicUuid)
    }

    fun setupIndication(
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode
    ): Flow<ByteArray> {
        return setupIndicationByUuid(
            characteristicUuid = characteristicUuid,
            setupMode = setupMode
        )
    }

    fun setupIndicationByUuid(
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT,
        notifySessionActivity: Boolean = true
    ): Flow<ByteArray> {
        return setupIndicationSessionByUuid(
            characteristicUuid = characteristicUuid,
            setupMode = setupMode,
            notifySessionActivity = notifySessionActivity
        ).flattenSessionObservation()
    }

    fun setupNotificationByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        notifySessionActivity: Boolean
    ): Flow<ByteArray> {
        return setupNotificationByUuid(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            setupMode = NotificationSetupMode.DEFAULT,
            notifySessionActivity = notifySessionActivity
        )
    }

    fun setupNotification(serviceUuid: UUID, characteristicUuid: UUID): Flow<ByteArray> {
        return setupNotificationByUuid(serviceUuid, characteristicUuid)
    }

    fun setupNotification(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode
    ): Flow<ByteArray> {
        return setupNotificationByUuid(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            setupMode = setupMode
        )
    }

    fun setupNotificationByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT,
        notifySessionActivity: Boolean = true
    ): Flow<ByteArray> {
        return setupNotificationSessionByUuid(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            setupMode = setupMode,
            notifySessionActivity = notifySessionActivity
        ).flattenSessionObservation()
    }

    fun setupIndicationByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        notifySessionActivity: Boolean
    ): Flow<ByteArray> {
        return setupIndicationByUuid(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            setupMode = NotificationSetupMode.DEFAULT,
            notifySessionActivity = notifySessionActivity
        )
    }

    fun setupIndication(serviceUuid: UUID, characteristicUuid: UUID): Flow<ByteArray> {
        return setupIndicationByUuid(serviceUuid, characteristicUuid)
    }

    fun setupIndication(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode
    ): Flow<ByteArray> {
        return setupIndicationByUuid(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            setupMode = setupMode
        )
    }

    fun setupIndicationByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT,
        notifySessionActivity: Boolean = true
    ): Flow<ByteArray> {
        return setupIndicationSessionByUuid(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            setupMode = setupMode,
            notifySessionActivity = notifySessionActivity
        ).flattenSessionObservation()
    }

    fun setupNotificationSessionByUuid(
        characteristicUuid: UUID,
        notifySessionActivity: Boolean
    ): Flow<Flow<ByteArray>> {
        return setupNotificationSessionByUuid(
            characteristicUuid = characteristicUuid,
            setupMode = NotificationSetupMode.DEFAULT,
            notifySessionActivity = notifySessionActivity
        )
    }

    fun setupNotificationSession(
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT
    ): Flow<Flow<ByteArray>> {
        return setupNotificationSessionByUuid(
            characteristicUuid = characteristicUuid,
            setupMode = setupMode
        )
    }

    fun setupNotificationSessionByUuid(
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT,
        notifySessionActivity: Boolean = true
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationSessionByUuid(
            characteristicUuid = characteristicUuid,
            mode = CharacteristicObservationMode.NOTIFICATION,
            setupMode = setupMode,
            notifySessionActivity = notifySessionActivity
        )
    }

    fun setupIndicationSessionByUuid(
        characteristicUuid: UUID,
        notifySessionActivity: Boolean
    ): Flow<Flow<ByteArray>> {
        return setupIndicationSessionByUuid(
            characteristicUuid = characteristicUuid,
            setupMode = NotificationSetupMode.DEFAULT,
            notifySessionActivity = notifySessionActivity
        )
    }

    fun setupIndicationSession(
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT
    ): Flow<Flow<ByteArray>> {
        return setupIndicationSessionByUuid(
            characteristicUuid = characteristicUuid,
            setupMode = setupMode
        )
    }

    fun setupIndicationSessionByUuid(
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT,
        notifySessionActivity: Boolean = true
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationSessionByUuid(
            characteristicUuid = characteristicUuid,
            mode = CharacteristicObservationMode.INDICATION,
            setupMode = setupMode,
            notifySessionActivity = notifySessionActivity
        )
    }

    fun setupNotificationSessionByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        notifySessionActivity: Boolean
    ): Flow<Flow<ByteArray>> {
        return setupNotificationSessionByUuid(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            setupMode = NotificationSetupMode.DEFAULT,
            notifySessionActivity = notifySessionActivity
        )
    }

    fun setupNotificationSession(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT
    ): Flow<Flow<ByteArray>> {
        return setupNotificationSessionByUuid(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            setupMode = setupMode
        )
    }

    fun setupNotificationSessionByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT,
        notifySessionActivity: Boolean = true
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationSessionByUuid(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            mode = CharacteristicObservationMode.NOTIFICATION,
            setupMode = setupMode,
            notifySessionActivity = notifySessionActivity
        )
    }

    fun setupIndicationSessionByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        notifySessionActivity: Boolean
    ): Flow<Flow<ByteArray>> {
        return setupIndicationSessionByUuid(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            setupMode = NotificationSetupMode.DEFAULT,
            notifySessionActivity = notifySessionActivity
        )
    }

    fun setupIndicationSession(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT
    ): Flow<Flow<ByteArray>> {
        return setupIndicationSessionByUuid(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            setupMode = setupMode
        )
    }

    fun setupIndicationSessionByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT,
        notifySessionActivity: Boolean = true
    ): Flow<Flow<ByteArray>> {
        return setupCharacteristicObservationSessionByUuid(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            mode = CharacteristicObservationMode.INDICATION,
            setupMode = setupMode,
            notifySessionActivity = notifySessionActivity
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun setupCharacteristicObservationByUuid(
        characteristicUuid: UUID,
        mode: CharacteristicObservationMode,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT,
        notifySessionActivity: Boolean = true
    ): Flow<ByteArray> {
        return setupCharacteristicObservationSessionByUuid(
            characteristicUuid = characteristicUuid,
            mode = mode,
            setupMode = setupMode,
            notifySessionActivity = notifySessionActivity
        ).flattenSessionObservation()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun setupCharacteristicObservationSessionByUuid(
        characteristicUuid: UUID,
        mode: CharacteristicObservationMode,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT,
        notifySessionActivity: Boolean = true
    ): Flow<Flow<ByteArray>> {
        return connection.flatMapLatest { activeConnection ->
            if (activeConnection == null) {
                emptyFlow()
            } else {
                activeConnection.setupCharacteristicObservationByUuid(
                    characteristicUuid = characteristicUuid,
                    mode = mode,
                    setupMode = setupMode
                ).map { updates ->
                    if (notifySessionActivity) {
                        updates.onEach { notifyDataReceived() }
                    } else {
                        updates
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun setupCharacteristicObservationByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        mode: CharacteristicObservationMode,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT,
        notifySessionActivity: Boolean = true
    ): Flow<ByteArray> {
        return setupCharacteristicObservationSessionByUuid(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            mode = mode,
            setupMode = setupMode,
            notifySessionActivity = notifySessionActivity
        ).flattenSessionObservation()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun setupCharacteristicObservationSessionByUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        mode: CharacteristicObservationMode,
        setupMode: NotificationSetupMode = NotificationSetupMode.DEFAULT,
        notifySessionActivity: Boolean = true
    ): Flow<Flow<ByteArray>> {
        return connection.flatMapLatest { activeConnection ->
            if (activeConnection == null) {
                emptyFlow()
            } else {
                activeConnection.setupCharacteristicObservationByUuid(
                    serviceUuid = serviceUuid,
                    characteristicUuid = characteristicUuid,
                    mode = mode,
                    setupMode = setupMode
                ).map { updates ->
                    if (notifySessionActivity) {
                        updates.onEach { notifyDataReceived() }
                    } else {
                        updates
                    }
                }
            }
        }
    }

    private suspend fun runSession() {
        var consecutiveFailures = 0

        while (currentCoroutineContext().isActive && !stopRequested) {
            val attempt = consecutiveFailures + 1
            publishState(AutoReconnectState.Connecting(attempt))

            val currentConnection = try {
                device.connect(config)
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    throw t
                }

                consecutiveFailures = attempt
                publishLastError(t)
                if (consecutiveFailures >= maxReconnectAttempts) {
                    publishConnection(null)
                    publishState(AutoReconnectState.Failed(consecutiveFailures, t))
                    return
                }

                publishState(AutoReconnectState.WaitingToRetry(
                    attempt = consecutiveFailures + 1,
                    delayMs = reconnectDelayMs,
                    lastError = t
                ))
                if (reconnectDelayMs > 0) {
                    delay(reconnectDelayMs)
                }
                continue
            }

            pendingConnection = currentConnection
            try {
                prepareConnectedSession(currentConnection, attempt)
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    throw t
                }

                pendingConnection = null
                currentConnection.close()
                publishConnection(null)
                consecutiveFailures = attempt
                publishLastError(t)
                if (consecutiveFailures >= maxReconnectAttempts) {
                    publishState(AutoReconnectState.Failed(consecutiveFailures, t))
                    return
                }

                publishState(AutoReconnectState.WaitingToRetry(
                    attempt = consecutiveFailures + 1,
                    delayMs = reconnectDelayMs,
                    lastError = t
                ))
                if (reconnectDelayMs > 0) {
                    delay(reconnectDelayMs)
                }
                continue
            }

            consecutiveFailures = 0
            pendingConnection = null
            publishConnection(currentConnection)
            publishState(AutoReconnectState.Connected)

            activeSupervisor = createSupervisorIfNeeded(currentConnection)?.also { supervisor ->
                supervisor.start()
            }

            try {
                currentConnection.connectionState.first { state ->
                    state == ConnectionState.Disconnected
                }
            } finally {
                activeSupervisor?.stop()
                activeSupervisor = null
                if (_connection.value === currentConnection) {
                    publishConnection(null)
                }
            }

            if (!currentCoroutineContext().isActive || stopRequested) {
                break
            }

            if (reconnectDelayMs > 0) {
                publishState(AutoReconnectState.WaitingToRetry(
                    attempt = 1,
                    delayMs = reconnectDelayMs,
                    lastError = null
                ))
                delay(reconnectDelayMs)
            }
        }

        publishConnection(null)
        if (_state.value !is AutoReconnectState.Failed) {
            publishState(AutoReconnectState.Stopped)
        }
    }

    private fun stopObservationHelpers() {
        activeSupervisor?.stop()
        activeSupervisor = null
        activeConnectionStateJob?.cancel()
        activeConnectionStateJob = null
        activeConnectionSnapshotJob?.cancel()
        activeConnectionSnapshotJob = null
        activeServicesJob?.cancel()
        activeServicesJob = null
    }

    private fun closePendingConnection() {
        val inFlightConnection = pendingConnection
        val activeConnection = _connection.value
        if (inFlightConnection != null && inFlightConnection !== activeConnection) {
            inFlightConnection.close()
        }
    }

    private suspend fun disconnectActiveConnectionIfRequested(disconnect: Boolean) {
        if (!disconnect) {
            return
        }

        _connection.value?.let { current ->
            try {
                current.disconnect()
            } catch (_: Exception) {
                current.close()
            }
        }
    }

    private suspend fun prepareConnectedSession(connection: BleConnection, attempt: Int) {
        if (!discoverServicesOnConnect && recoveryPlan == null && onConnectionReady == null) {
            return
        }

        publishState(AutoReconnectState.Recovering(attempt))
        if (discoverServicesOnConnect) {
            connection.discoverServices()
        }
        recoveryPlan?.execute(connection)
        onConnectionReady?.invoke(connection)
    }

    private fun createSupervisorIfNeeded(connection: BleConnection): ConnectionSupervisor? {
        val silentDuration = maxSilentDurationMs ?: return null

        return ConnectionSupervisor(
            connectionState = connection.connectionState,
            onConnectionLost = {
                if (!stopRequested) {
                    try {
                        connection.disconnect()
                    } catch (_: Exception) {
                        connection.close()
                    }
                }
            },
            checkInterval = supervisorCheckIntervalMs,
            maxSilentDuration = silentDuration,
            scope = scope
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Flow<Flow<ByteArray>>.flattenSessionObservation(): Flow<ByteArray> {
        return flatMapLatest { updates -> updates }
    }

    private fun publishState(state: AutoReconnectState) {
        _state.value = state
        refreshSnapshot()
    }

    private fun publishConnection(connection: BleConnection?) {
        _connection.value = connection
        activeConnectionStateJob?.cancel()
        activeConnectionStateJob = null
        activeConnectionSnapshotJob?.cancel()
        activeConnectionSnapshotJob = null
        activeServicesJob?.cancel()
        activeServicesJob = null

        if (connection == null) {
            _activeConnectionState.value = null
            _activeConnectionSnapshot.value = null
            _services.value = null
            refreshSnapshot()
            return
        }

        _activeConnectionState.value = connection.connectionState.value
        _activeConnectionSnapshot.value = connection.currentSnapshot()
        _services.value = connection.servicesState.value
        refreshSnapshot()
        activeConnectionStateJob = scope.launch {
            connection.connectionState.collect { state ->
                _activeConnectionState.value = state
                refreshSnapshot()
            }
        }
        activeConnectionSnapshotJob = scope.launch {
            combine(
                connection.connectionState,
                connection.mtu,
                connection.phy,
                connection.servicesState
            ) { connectionState, mtu, phy, services ->
                BleConnectionSnapshot(
                    connectionState = connectionState,
                    mtu = mtu,
                    phy = phy,
                    services = services
                )
            }.distinctUntilChanged().collect { snapshot ->
                _activeConnectionSnapshot.value = snapshot
                refreshSnapshot()
            }
        }
        activeServicesJob = scope.launch {
            connection.servicesState.collect { services ->
                _services.value = services
                refreshSnapshot()
            }
        }
    }

    private fun publishLastError(error: Throwable?) {
        _lastError.value = error
        refreshSnapshot()
    }

    private fun refreshSnapshot() {
        _snapshot.value = AutoReconnectSnapshot(
            state = _state.value,
            activeConnectionState = _activeConnectionState.value,
            activeConnectionSnapshot = _activeConnectionSnapshot.value,
            hasActiveConnection = _connection.value != null,
            lastError = _lastError.value
        )
    }
}

private class SessionLongWriteOperationBuilder(
    private val connectionProvider: suspend () -> BleConnection
) : LongWriteOperationBuilder {
    private var value: ByteArray? = null
    private var characteristicUuid: UUID? = null
    private var serviceUuid: UUID? = null
    private var writeType: WriteType = WriteType.DEFAULT
    private var maxBatchSize: Int? = null
    private var interChunkDelayMs: Long = 0L
    private var config: OperationConfig = OperationConfig.DEFAULT
    private var ackStrategy: LongWriteAckStrategy? = null
    private var retryStrategy: LongWriteRetryStrategy? = null

    override fun setBytes(value: ByteArray): LongWriteOperationBuilder = apply {
        this.value = value
    }

    override fun setCharacteristic(characteristic: BleCharacteristic): LongWriteOperationBuilder = apply {
        this.serviceUuid = characteristic.serviceUuid
        this.characteristicUuid = characteristic.uuid
    }

    override fun setCharacteristicUuid(characteristicUuid: UUID): LongWriteOperationBuilder = apply {
        this.characteristicUuid = characteristicUuid
        this.serviceUuid = null
    }

    override fun setCharacteristicUuid(
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): LongWriteOperationBuilder = apply {
        this.serviceUuid = serviceUuid
        this.characteristicUuid = characteristicUuid
    }

    override fun setWriteType(writeType: WriteType): LongWriteOperationBuilder = apply {
        this.writeType = writeType
    }

    override fun setMaxBatchSize(maxBatchSize: Int): LongWriteOperationBuilder = apply {
        this.maxBatchSize = maxBatchSize
    }

    override fun setInterChunkDelayMs(delayMs: Long): LongWriteOperationBuilder = apply {
        interChunkDelayMs = delayMs
    }

    override fun setOperationConfig(config: OperationConfig): LongWriteOperationBuilder = apply {
        this.config = config
    }

    override fun setWriteOperationAckStrategy(strategy: LongWriteAckStrategy): LongWriteOperationBuilder = apply {
        ackStrategy = strategy
    }

    override fun setWriteOperationRetryStrategy(strategy: LongWriteRetryStrategy): LongWriteOperationBuilder = apply {
        retryStrategy = strategy
    }

    override suspend fun build(): ByteArray {
        val connection = connectionProvider()
        val builder = connection.createNewLongWriteBuilder()
        val resolvedValue = value
            ?: throw IllegalStateException("Long write bytes not set. Call setBytes(...) first.")

        when {
            characteristicUuid != null && serviceUuid != null -> {
                builder.setCharacteristicUuid(serviceUuid!!, characteristicUuid!!)
            }
            characteristicUuid != null -> builder.setCharacteristicUuid(characteristicUuid!!)
            else -> throw IllegalStateException(
                "Long write target not set. Call setCharacteristic(...) or setCharacteristicUuid(...) first."
            )
        }

        builder
            .setBytes(resolvedValue)
            .setWriteType(writeType)
            .setOperationConfig(config)

        maxBatchSize?.let(builder::setMaxBatchSize)
        if (interChunkDelayMs != 0L) {
            builder.setInterChunkDelayMs(interChunkDelayMs)
        }
        ackStrategy?.let(builder::setWriteOperationAckStrategy)
        retryStrategy?.let(builder::setWriteOperationRetryStrategy)

        return builder.build()
    }
}

/**
 * Create an [AutoReconnectSession] for this stable device handle.
 */
fun FlowBleDevice.createAutoReconnectSession(
    config: ConnectionConfig = ConnectionConfig(),
    reconnectDelayMs: Long = config.retryDelay.coerceAtLeast(0L),
    maxReconnectAttempts: Int = Int.MAX_VALUE,
    supervisorCheckIntervalMs: Long = 5000L,
    maxSilentDurationMs: Long? = null,
    discoverServicesOnConnect: Boolean = false,
    recoveryPlan: AutoReconnectRecoveryPlan? = null,
    onConnectionReady: (suspend BleConnection.() -> Unit)? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
): AutoReconnectSession {
    return AutoReconnectSession(
        device = this,
        config = config,
        reconnectDelayMs = reconnectDelayMs,
        maxReconnectAttempts = maxReconnectAttempts,
        supervisorCheckIntervalMs = supervisorCheckIntervalMs,
        maxSilentDurationMs = maxSilentDurationMs,
        discoverServicesOnConnect = discoverServicesOnConnect,
        recoveryPlan = recoveryPlan,
        onConnectionReady = onConnectionReady,
        scope = scope
    )
}

/**
 * Convenience overload for creating an [AutoReconnectSession] from a MAC address.
 */
fun FlowBleClient.createAutoReconnectSession(
    address: String,
    config: ConnectionConfig = ConnectionConfig(),
    reconnectDelayMs: Long = config.retryDelay.coerceAtLeast(0L),
    maxReconnectAttempts: Int = Int.MAX_VALUE,
    supervisorCheckIntervalMs: Long = 5000L,
    maxSilentDurationMs: Long? = null,
    discoverServicesOnConnect: Boolean = false,
    recoveryPlan: AutoReconnectRecoveryPlan? = null,
    onConnectionReady: (suspend BleConnection.() -> Unit)? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
): AutoReconnectSession {
    return getBleDevice(address).createAutoReconnectSession(
        config = config,
        reconnectDelayMs = reconnectDelayMs,
        maxReconnectAttempts = maxReconnectAttempts,
        supervisorCheckIntervalMs = supervisorCheckIntervalMs,
        maxSilentDurationMs = maxSilentDurationMs,
        discoverServicesOnConnect = discoverServicesOnConnect,
        recoveryPlan = recoveryPlan,
        onConnectionReady = onConnectionReady,
        scope = scope
    )
}
