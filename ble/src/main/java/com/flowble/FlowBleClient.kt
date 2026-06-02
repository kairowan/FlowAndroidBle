package com.flowble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.flowble.exception.AlreadyConnectedException
import com.flowble.exception.BleException
import com.flowble.exception.BleGattCallbackTimeoutException
import com.flowble.exception.BleGattOperationType
import com.flowble.exception.ConnectionException
import com.flowble.exception.TimeoutException
import com.flowble.internal.advertisesAllRequiredServiceUuids
import com.flowble.internal.BleConnectionImpl
import com.flowble.internal.DeviceConnectionRegistry
import com.flowble.internal.FlowBleDeviceImpl
import com.flowble.internal.GattCallbackRouter
import com.flowble.internal.getOrPutConcurrent
import com.flowble.model.BleScanResult
import com.flowble.model.BleAdapterState
import com.flowble.model.BleState
import com.flowble.model.ConnectionConfig
import com.flowble.model.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal fun interface ReceiverRegistration {
    fun unregister()
}

internal typealias ReceiverRegistrar = (Context, BroadcastReceiver, List<String>) -> ReceiverRegistration

/**
 * Main entry point for the FlowBLE library.
 *
 * This class provides factory methods for creating scanners and connections.
 *
 * Usage:
 * ```kotlin
 * val client = FlowBleClient.create(context)
 *
 * // Scan for devices
 * client.scan { setTimeoutMs(10000) }.collect { result ->
 *     println("Found device: ${result.address}")
 * }
 *
 * // Connect to a device
 * val connection = client.connect("AA:BB:CC:DD:EE:FF")
 * connection.discoverServices()
 * ```
 *
 * Note: The caller is responsible for checking and requesting BLE permissions before
 * calling any methods on this class.
 */
class FlowBleClient internal constructor(
    private val context: Context,
    private val receiverRegistrar: ReceiverRegistrar,
    private val scanFlowFactory: (Context, ScannerConfig) -> Flow<BleScanResult> = { appContext, config ->
        BleScanner(appContext).scan(config)
    },
    private val sharedScanFlowFactory: (Context) -> Flow<BleScanResult> = { appContext ->
        BleScanner(appContext).scan(ScannerConfig(timeoutMs = 0L))
    },
    private val sharedScanDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val backgroundScannerFactory: (Context) -> BackgroundScanner = { appContext ->
        BackgroundScanner(appContext)
    }
) {

    constructor(context: Context) : this(context, defaultReceiverRegistrar)
    internal constructor(
        context: Context,
        receiverRegistrar: ReceiverRegistrar
    ) : this(
        context = context,
        receiverRegistrar = receiverRegistrar,
        scanFlowFactory = { appContext, config -> BleScanner(appContext).scan(config) },
        sharedScanFlowFactory = { appContext -> BleScanner(appContext).scan(ScannerConfig(timeoutMs = 0L)) },
        sharedScanDispatcher = Dispatchers.IO,
        backgroundScannerFactory = { appContext -> BackgroundScanner(appContext) }
    )

    private val appContext = context.applicationContext
    private val deviceConnections = DeviceConnectionRegistry()
    private val deviceHandles = ConcurrentHashMap<String, FlowBleDevice>()
    private val sharedBondingManager by lazy(LazyThreadSafetyMode.NONE) {
        BondingManager(appContext)
    }
    private val sharedStateMonitor by lazy(LazyThreadSafetyMode.NONE) {
        BluetoothStateMonitor(appContext)
    }
    private val sharedMultiDeviceManager by lazy(LazyThreadSafetyMode.NONE) {
        MultiDeviceManager(this)
    }
    private val sharedBackgroundScanner by lazy(LazyThreadSafetyMode.NONE) {
        backgroundScannerFactory(appContext)
    }
    private val sharedUuidScanSessions = ConcurrentHashMap<Set<UUID>, SharedUuidScanSession>()

    /**
     * Create a scanner instance.
     */
    fun scanner(): BleScanner = BleScanner(context.applicationContext)

    /**
     * Create a batch scanner instance.
     *
     * @param batchInterval How often to emit batches in milliseconds.
     * @param maxBatchSize Maximum number of results per batch.
     */
    fun batchScanner(batchInterval: Long = 1000L, maxBatchSize: Int = 100): BatchScanner {
        return BatchScanner(appContext, batchInterval, maxBatchSize)
    }

    /**
     * Create a PendingIntent-based background scanner.
     */
    fun backgroundScanner(): BackgroundScanner = sharedBackgroundScanner

    /**
     * Convenience alias for [backgroundScanner].
     */
    fun getBackgroundScanner(): BackgroundScanner = backgroundScanner()

    /**
     * Convenience: scan with a builder DSL.
     *
     * Example:
     * ```kotlin
     * client.scan {
     *     setTimeoutMs(10000)
     *     addFilter(ScanFilter.Builder().setDeviceName("MyDevice").build())
     * }.collect { result ->
     *     // Handle scan result
     * }
     * ```
     */
    fun scan(block: ScannerConfig.Builder.() -> Unit = {}): Flow<BleScanResult> {
        return scanFlowFactory(appContext, ScannerConfig.build(block))
    }

    /**
     * Convenience alias for scanning BLE devices.
     */
    fun scanBleDevices(config: ScannerConfig = ScannerConfig(timeoutMs = 0L)): Flow<BleScanResult> {
        return scanFlowFactory(appContext, config)
    }

    /**
     * Convenience overload for scanning BLE devices with a builder DSL.
     */
    fun scanBleDevices(block: ScannerConfig.Builder.() -> Unit): Flow<BleScanResult> {
        return scanBleDevices(
            ScannerConfig.build {
                setTimeoutMs(0L)
                block()
            }
        )
    }

    /**
     * Convenience overload matching scan settings + vararg filters.
     *
     * This scan runs until the Flow collector is cancelled.
     */
    fun scanBleDevices(
        scanSettings: ScanSettings,
        vararg scanFilters: ScanFilter
    ): Flow<BleScanResult> {
        return scanBleDevices(
            ScannerConfig(
                filters = scanFilters.toList(),
                settings = scanSettings,
                timeoutMs = 0L
            )
        )
    }

    /**
     * Convenience overload for filtering by advertised service UUIDs.
     *
     * All provided UUIDs must be present in the advertisement payload for a result to match.
     */
    fun scanBleDevices(vararg filterServiceUuids: UUID): Flow<BleScanResult> {
        if (filterServiceUuids.isEmpty()) {
            return scanBleDevices()
        }

        val requiredServiceUuids = filterServiceUuids.toSet()
        return callbackFlow {
            val session = sharedUuidScanSessions.getOrPutConcurrent(requiredServiceUuids) {
                SharedUuidScanSession(requiredServiceUuids)
            }
            session.addCollector()

            val eventsJob = launch {
                session.results.collect { result ->
                    trySend(result)
                }
            }
            val terminationJob = launch {
                val cause = session.termination.await()
                if (cause == null) {
                    close()
                } else {
                    close(cause)
                }
            }

            awaitClose {
                eventsJob.cancel()
                terminationJob.cancel()
                session.removeCollector()
            }
        }
    }

    /**
     * Convenience alias for scanning a specific BLE device by MAC address.
     */
    fun scanBleDevice(address: String, timeoutMs: Long = 30_000L): Flow<BleScanResult> {
        return scanner().scanForDevice(address, timeoutMs)
    }

    /**
     * Obtain a stable device-scoped handle for the provided MAC address.
     */
    fun getBleDevice(address: String): FlowBleDevice {
        val normalizedAddress = address.trim()
        return deviceHandles.getOrPutConcurrent(normalizedAddress) {
            FlowBleDeviceImpl(
                address = normalizedAddress,
                connectionState = deviceConnections.stateFlow(normalizedAddress),
                bluetoothDeviceProvider = { getBluetoothDeviceOrThrow(normalizedAddress) },
                connector = { config -> connectInternal(normalizedAddress, config) }
            )
        }
    }

    /**
     * Obtain a stable device-scoped handle for an Android [BluetoothDevice].
     */
    fun getBleDevice(device: BluetoothDevice): FlowBleDevice {
        return getBleDevice(device.address)
    }

    /**
     * Return the currently bonded devices as stable [FlowBleDevice] handles.
     */
    @SuppressLint("MissingPermission")
    fun getBondedDevices(): Set<FlowBleDevice> {
        val adapter = bluetoothManagerOrThrow().adapter
            ?: throw BleException("Bluetooth adapter not available")
        return adapter.bondedDevices
            .orEmpty()
            .mapTo(linkedSetOf()) { device -> getBleDevice(device.address) }
    }

    /**
     * Observe the current bonded devices as stable [FlowBleDevice] handles.
     *
     * The flow emits the current snapshot immediately and then refreshes whenever the platform
     * reports bond or adapter state changes.
     */
    @SuppressLint("MissingPermission")
    fun observeBondedDevices(): Flow<Set<FlowBleDevice>> {
        return observeDeviceHandleSet(
            snapshotProvider = ::getBondedDevices,
            actions = listOf(
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED
            )
        )
    }

    /**
     * Return peripherals currently connected to the system GATT profile.
     */
    @SuppressLint("MissingPermission")
    fun getConnectedPeripherals(): Set<FlowBleDevice> {
        return bluetoothManagerOrThrow()
            .getConnectedDevices(BluetoothProfile.GATT)
            .mapTo(linkedSetOf()) { device -> getBleDevice(device.address) }
    }

    /**
     * Observe peripherals currently connected to the system GATT profile.
     *
     * The flow emits the current snapshot immediately and then refreshes whenever Bluetooth
     * connection or adapter broadcasts indicate the platform device set may have changed.
     */
    @SuppressLint("MissingPermission")
    fun observeConnectedPeripherals(): Flow<Set<FlowBleDevice>> {
        return observeDeviceHandleSet(
            snapshotProvider = ::getConnectedPeripherals,
            actions = listOf(
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED
            )
        )
    }

    /**
     * Connect to a BLE device by MAC address.
     *
     * This method suspends until the connection is established or fails.
     *
     * @param address The MAC address of the device to connect to.
     * @param config Connection configuration.
     * @return A [BleConnection] for the connected device.
     * @throws BleException if Bluetooth is not available.
     * @throws ConnectionException if the connection fails.
     * @throws TimeoutException if the connection times out.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(
        address: String,
        config: ConnectionConfig = ConnectionConfig()
    ): BleConnection {
        return connectInternal(address.trim(), config)
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectInternal(
        address: String,
        config: ConnectionConfig
    ): BleConnection {
        return withContext(Dispatchers.IO) {
            if (!deviceConnections.tryAcquire(address)) {
                throw AlreadyConnectedException(address)
            }
            deviceConnections.updateState(address, ConnectionState.Connecting)

            val device = try {
                getBluetoothDeviceOrThrow(address)
            } catch (e: Exception) {
                deviceConnections.release(address)
                throw e
            }

            BleLogger.logConnectionEvent("connecting", address)

            val router = GattCallbackRouter()
            val connectionDeferred = router.prepareConnection()

            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, config.autoConnect, router.callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(appContext, config.autoConnect, router.callback)
            }
            router.attachGatt(gatt)

            try {
                if (config.connectionTimeout > 0) {
                    withTimeout(config.connectionTimeout) {
                        connectionDeferred.await()
                    }
                } else {
                    connectionDeferred.await()
                }

                BleLogger.logConnectionEvent("connected", address)
            } catch (e: Exception) {
                BleLogger.logConnectionEvent("connection failed: ${e.message}", address)

                // Connection failed - cleanup
                try {
                    gatt.disconnect()
                    gatt.close()
                } catch (_: Exception) {
                    // Ignore cleanup errors
                }
                router.cleanup()
                deviceConnections.release(address)

                when (e) {
                    is TimeoutCancellationException -> throw BleGattCallbackTimeoutException(
                        timeoutMs = config.connectionTimeout,
                        operationType = BleGattOperationType.CONNECTION_STATE,
                        macAddress = address,
                        cause = e
                    )
                    is TimeoutException -> throw e
                    is ConnectionException -> throw e
                    is BleException -> throw e
                    else -> throw ConnectionException("Connection failed: ${e.message}", e)
                }
            }

            val connection = BleConnectionImpl(
                context = appContext,
                router = router,
                connectionConfig = config,
                onConnectionStateChanged = { state ->
                    deviceConnections.updateState(address, state)
                },
                onClosed = {
                    deviceConnections.release(address)
                }
            )

            config.preferredPhy?.let { preferredPhy ->
                try {
                    connection.requestPhy(preferredPhy)
                    BleLogger.d("Applied preferred PHY during connect", "$address -> $preferredPhy")
                } catch (e: Exception) {
                    BleLogger.logConnectionEvent("preferred PHY setup failed: ${e.message}", address)
                    connection.close()
                    throw e
                }
            }

            connection
        }
    }

    /**
     * Create a bonding manager for handling device pairing.
     */
    fun bondingManager(): BondingManager = sharedBondingManager

    /**
     * Create a Bluetooth state monitor.
     */
    fun stateMonitor(): BluetoothStateMonitor = sharedStateMonitor

    /**
     * Convenience alias for observing BLE client state changes.
     */
    fun observeStateChanges(): Flow<BleState> = stateMonitor().observeStateChanges()

    /**
     * Convenience snapshot of the current client environment state.
     */
    fun getState(): BleState = stateMonitor().getState()

    /**
     * Observe raw Bluetooth adapter state changes.
     */
    fun observeAdapterStateChanges(): Flow<BleAdapterState> = stateMonitor().observeAdapterStateChanges()

    /**
     * Snapshot of the raw Bluetooth adapter state.
     */
    fun getAdapterState(): BleAdapterState = stateMonitor().getAdapterState()

    /**
     * Whether scan-related runtime permissions are currently granted.
     */
    fun isScanRuntimePermissionGranted(): Boolean = stateMonitor().isScanRuntimePermissionGranted()

    /**
     * Whether connect-related runtime permissions are currently granted.
     */
    fun isConnectRuntimePermissionGranted(): Boolean = stateMonitor().isConnectRuntimePermissionGranted()

    /**
     * Recommended runtime permissions for performing BLE scans on this device/app target.
     */
    fun getRecommendedScanRuntimePermissions(): Array<String> {
        return stateMonitor().getRecommendedScanRuntimePermissions()
    }

    /**
     * Recommended runtime permissions for connecting to BLE devices on this device/app target.
     */
    fun getRecommendedConnectRuntimePermissions(): Array<String> {
        return stateMonitor().getRecommendedConnectRuntimePermissions()
    }

    /**
     * Create a multi-device manager for managing multiple connections.
     */
    fun multiDeviceManager(): MultiDeviceManager = sharedMultiDeviceManager

    private fun bluetoothManagerOrThrow(): BluetoothManager {
        return appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw BleException("BluetoothManager not available")
    }

    private fun observeDeviceHandleSet(
        snapshotProvider: () -> Set<FlowBleDevice>,
        actions: List<String>
    ): Flow<Set<FlowBleDevice>> = callbackFlow {
        fun publishSnapshot() {
            try {
                trySend(snapshotProvider())
            } catch (error: Throwable) {
                close(error)
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                publishSnapshot()
            }
        }

        val registration = receiverRegistrar(appContext, receiver, actions)
        publishSnapshot()

        awaitClose {
            registration.unregister()
        }
    }.distinctUntilChanged()

    @SuppressLint("MissingPermission")
    private fun getBluetoothDeviceOrThrow(address: String): BluetoothDevice {
        val adapter = bluetoothManagerOrThrow().adapter
            ?: throw BleException("Bluetooth adapter not available")
        if (!adapter.isEnabled) {
            throw BleException("Bluetooth is disabled")
        }

        return try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw BleException("Invalid device address: $address", e)
        }
    }

    private inner class SharedUuidScanSession(
        private val requiredServiceUuids: Set<UUID>
    ) {
        val results = MutableSharedFlow<BleScanResult>(
            extraBufferCapacity = SHARED_SCAN_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val termination = CompletableDeferred<Throwable?>()

        private val collectorCount = AtomicInteger(0)
        private val scope = CoroutineScope(SupervisorJob() + sharedScanDispatcher)
        private val upstreamJob: Job = scope.launch(start = CoroutineStart.LAZY) {
            var failure: Throwable? = null
            try {
                sharedScanFlowFactory(appContext).filter { result ->
                    advertisesAllRequiredServiceUuids(result, requiredServiceUuids)
                }.collect { result ->
                    results.emit(result)
                }
            } catch (_: CancellationException) {
                if (collectorCount.get() > 0) {
                    failure = BleException(
                        "Shared UUID-filtered scan was cancelled while collectors were still active"
                    )
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                sharedUuidScanSessions.remove(requiredServiceUuids, this@SharedUuidScanSession)
                if (!termination.isCompleted) {
                    termination.complete(failure)
                }
                scope.cancel()
            }
        }

        fun addCollector() {
            if (collectorCount.incrementAndGet() == 1) {
                upstreamJob.start()
            }
        }

        fun removeCollector() {
            if (collectorCount.decrementAndGet() == 0) {
                sharedUuidScanSessions.remove(requiredServiceUuids, this)
                if (!upstreamJob.isCompleted) {
                    upstreamJob.cancel()
                } else if (!termination.isCompleted) {
                    termination.complete(null)
                }
                scope.cancel()
            }
        }
    }

    companion object {
        private const val SHARED_SCAN_BUFFER = 64
        private val sharedInstances = ConcurrentHashMap<Int, FlowBleClient>()
        private val defaultReceiverRegistrar: ReceiverRegistrar = { appContext, receiver, actions ->
            val filter = IntentFilter().apply {
                actions.forEach(::addAction)
            }
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            ReceiverRegistration {
                appContext.unregisterReceiver(receiver)
            }
        }

        /**
         * Return the shared client instance for the application's process.
         *
         * Reusing one client keeps device handle state, connection guards, and helper managers
         * aligned across the application's process.
         */
        fun getInstance(context: Context): FlowBleClient {
            val appContext = context.applicationContext
            val key = System.identityHashCode(appContext)
            return sharedInstances.getOrPutConcurrent(key) {
                FlowBleClient(appContext)
            }
        }

        /**
         * Convenience factory for creating a client instance.
         *
         * This is an alias for [getInstance].
         */
        fun create(context: Context): FlowBleClient = getInstance(context)

        /**
         * Global log level helper backed by [BleLogger].
         */
        fun setLogLevel(level: BleLogger.Level) {
            BleLogger.setEnabled(level != BleLogger.Level.NONE)
            BleLogger.setLevel(level)
        }

        /**
         * Global logger hook using the `(level, tag, message)` argument order.
         */
        fun setLogger(logger: ((BleLogger.Level, String?, String) -> Unit)?) {
            if (logger == null) {
                BleLogger.clearCustomLogger()
                return
            }
            BleLogger.setCustomLogger { level, message, tag ->
                logger(level, tag, message)
            }
        }

        /**
         * Remove the currently configured custom logger, if any.
         */
        fun clearLogger() {
            BleLogger.clearCustomLogger()
        }

        internal fun clearInstancesForTests() {
            sharedInstances.clear()
        }
    }
}
