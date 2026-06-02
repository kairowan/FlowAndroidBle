package com.flowble.sample

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.flowble.AutoReconnectSession
import com.flowble.BleConnection
import com.flowble.FlowBleClient
import com.flowble.FlowBleDevice
import com.flowble.createAutoReconnectSession
import com.flowble.model.AutoReconnectSnapshot
import com.flowble.model.AutoReconnectState
import com.flowble.model.BleScanResult
import com.flowble.model.BleService
import com.flowble.model.ConnectionConfig
import com.flowble.model.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : Activity() {

    private val client by lazy(LazyThreadSafetyMode.NONE) {
        FlowBleClient.getInstance(applicationContext)
    }
    private val activityScope = MainScope()
    private val logLines = mutableListOf<String>()
    private val recentScanResults = linkedMapOf<String, BleScanResult>()

    private var scanJob: Job? = null
    private var bondedDevicesJob: Job? = null
    private var connectedPeripheralsJob: Job? = null
    private var selectedDeviceStateJob: Job? = null
    private var connectionJob: Job? = null
    private var selectedDevice: FlowBleDevice? = null
    private var activeConnection: BleConnection? = null
    private var autoReconnectSession: AutoReconnectSession? = null
    private var autoReconnectSnapshotJob: Job? = null

    private lateinit var summaryView: TextView
    private lateinit var bondedDevicesContainer: LinearLayout
    private lateinit var connectedPeripheralsContainer: LinearLayout
    private lateinit var deviceAddressInput: EditText
    private lateinit var connectionStatusView: TextView
    private lateinit var connectionDetailsView: TextView
    private lateinit var autoReconnectStatusView: TextView
    private lateinit var autoReconnectDetailsView: TextView
    private lateinit var recentScanResultsContainer: LinearLayout
    private lateinit var logView: TextView
    private lateinit var refreshButton: Button
    private lateinit var permissionButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var startAutoReconnectButton: Button
    private lateinit var stopAutoReconnectButton: Button
    private lateinit var startScanButton: Button
    private lateinit var stopScanButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        summaryView = findViewById(R.id.summaryView)
        bondedDevicesContainer = findViewById(R.id.bondedDevicesContainer)
        connectedPeripheralsContainer = findViewById(R.id.connectedPeripheralsContainer)
        deviceAddressInput = findViewById(R.id.deviceAddressInput)
        connectionStatusView = findViewById(R.id.connectionStatusView)
        connectionDetailsView = findViewById(R.id.connectionDetailsView)
        autoReconnectStatusView = findViewById(R.id.autoReconnectStatusView)
        autoReconnectDetailsView = findViewById(R.id.autoReconnectDetailsView)
        recentScanResultsContainer = findViewById(R.id.recentScanResultsContainer)
        logView = findViewById(R.id.logView)
        refreshButton = findViewById(R.id.refreshButton)
        permissionButton = findViewById(R.id.permissionButton)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        startAutoReconnectButton = findViewById(R.id.startAutoReconnectButton)
        stopAutoReconnectButton = findViewById(R.id.stopAutoReconnectButton)
        startScanButton = findViewById(R.id.startScanButton)
        stopScanButton = findViewById(R.id.stopScanButton)

        refreshButton.setOnClickListener { refreshSummary() }
        permissionButton.setOnClickListener { requestMissingPermissions() }
        connectButton.setOnClickListener { startConnection() }
        disconnectButton.setOnClickListener { disconnectCurrentConnection() }
        startAutoReconnectButton.setOnClickListener { startAutoReconnectSession() }
        stopAutoReconnectButton.setOnClickListener {
            activityScope.launch {
                stopAutoReconnectSession()
            }
        }
        startScanButton.setOnClickListener { startScan() }
        stopScanButton.setOnClickListener { stopScan() }
        deviceAddressInput.doAfterTextChanged {
            deviceAddressInput.error = null
            if (
                currentNormalizedAddress().isNullOrEmpty() &&
                connectionJob == null &&
                activeConnection == null
            ) {
                selectedDevice = null
                selectedDeviceStateJob?.cancel()
                selectedDeviceStateJob = null
                showIdleConnectionUi()
            }
            refreshSelectableLists()
            updateConnectionButtons()
            updateAutoReconnectButtons()
        }

        appendLog(getString(R.string.sample_ready))
        showIdleConnectionUi()
        showIdleAutoReconnectUi()
        renderRecentScanResults()
        startSummaryObservation()
        refreshSummary()
        updateScanButtons()
        updateConnectionButtons()
        updateAutoReconnectButtons()
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
    }

    override fun onDestroy() {
        stopScan()
        shutdownAutoReconnectSession()
        activityScope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) {
            return
        }

        val deniedPermissions = permissions
            .zip(grantResults.toTypedArray())
            .filter { (_, result) -> result != PackageManager.PERMISSION_GRANTED }
            .map { (permission, _) -> permission }

        if (deniedPermissions.isEmpty()) {
            appendLog(getString(R.string.permissions_granted))
        } else {
            appendLog(
                getString(
                    R.string.permissions_denied,
                    deniedPermissions.joinToString()
                )
            )
        }
        refreshSummary()
    }

    private fun startScan() {
        if (scanJob != null) {
            appendLog(getString(R.string.scan_already_running))
            return
        }

        val missingPermissions = missingRuntimePermissions()
        if (missingPermissions.isNotEmpty()) {
            appendLog(
                getString(
                    R.string.permissions_required,
                    missingPermissions.joinToString()
                )
            )
            requestMissingRuntimePermissions(missingPermissions)
            return
        }

        appendLog(getString(R.string.scan_started))
        scanJob = activityScope.launch {
            try {
                client.scanBleDevices().collect { result ->
                    updateRecentScanResults(result)
                    appendLog(
                        getString(
                            R.string.scan_result_format,
                            result.address,
                            result.deviceName ?: getString(R.string.unknown_device_name),
                            result.rssi,
                            result.isConnectable?.toString() ?: getString(R.string.unknown_value)
                        )
                    )
                }
            } catch (_: CancellationException) {
                appendLog(getString(R.string.scan_stopped))
            } catch (error: Exception) {
                appendLog(
                    getString(
                        R.string.scan_failed,
                        error.message ?: error.javaClass.simpleName
                    )
                )
            } finally {
                scanJob = null
                updateScanButtons()
            }
        }
        updateScanButtons()
    }

    private fun stopScan() {
        scanJob?.cancel()
    }

    private fun refreshSummary() {
        val recommendedScanPermissions = client.getRecommendedScanRuntimePermissions()
        val recommendedConnectPermissions = client.getRecommendedConnectRuntimePermissions()
        val summaryText = getString(
            R.string.summary_format,
            client.getState().toString(),
            client.getAdapterState().toString(),
            runtimePermissionStateText(),
            formatPermissionList(recommendedScanPermissions),
            formatPermissionList(recommendedConnectPermissions)
        )
        summaryView.text = summaryText
        syncDeviceObservation()
        updateConnectionButtons()
        updateAutoReconnectButtons()
    }

    private fun updateScanButtons() {
        val scanning = scanJob != null
        startScanButton.isEnabled = !scanning
        stopScanButton.isEnabled = scanning
    }

    private fun requestMissingPermissions() {
        val missingPermissions = missingRuntimePermissions()
        if (missingPermissions.isEmpty()) {
            appendLog(getString(R.string.permissions_already_granted))
            refreshSummary()
            return
        }
        requestMissingRuntimePermissions(missingPermissions)
    }

    private fun requestMissingRuntimePermissions(missingPermissions: Array<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(missingPermissions, REQUEST_PERMISSIONS)
        } else {
            appendLog(getString(R.string.permissions_not_required))
            refreshSummary()
        }
    }

    private fun missingRuntimePermissions(): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return emptyArray()
        }

        return (client.getRecommendedScanRuntimePermissions().toList() +
            client.getRecommendedConnectRuntimePermissions().toList())
            .distinct()
            .filterNot(::isPermissionGranted)
            .toTypedArray()
    }

    private fun runtimePermissionStateText(): String {
        val missingPermissions = missingRuntimePermissions()
        return if (missingPermissions.isEmpty()) {
            getString(R.string.permissions_ready)
        } else {
            getString(
                R.string.permissions_missing,
                missingPermissions.joinToString()
            )
        }
    }

    private fun isPermissionGranted(permission: String): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            true
        } else {
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun appendLog(message: String) {
        if (logLines.size == MAX_LOG_LINES) {
            logLines.removeAt(0)
        }
        logLines += message
        logView.text = logLines.joinToString(separator = "\n")
    }

    private fun startSummaryObservation() {
        observeRefreshTrigger(
            flow = client.observeStateChanges(),
            errorMessageRes = R.string.state_observation_failed
        )
        observeRefreshTrigger(
            flow = client.observeAdapterStateChanges(),
            errorMessageRes = R.string.adapter_state_observation_failed
        )
    }

    private fun observeRefreshTrigger(
        flow: Flow<*>,
        errorMessageRes: Int
    ) {
        activityScope.launch {
            try {
                flow.collect {
                    refreshSummary()
                }
            } catch (_: CancellationException) {
                // Activity scope is shutting down.
            } catch (error: Throwable) {
                appendLog(
                    getString(
                        errorMessageRes,
                        error.message ?: error.javaClass.simpleName
                    )
                )
            }
        }
    }

    private fun startConnection() {
        if (connectionJob != null) {
            appendLog(getString(R.string.connection_already_active))
            return
        }

        if (!client.isConnectRuntimePermissionGranted()) {
            appendLog(getString(R.string.connection_requires_permission))
            requestMissingPermissions()
            return
        }

        val address = normalizeAddressInput() ?: return
        activityScope.launch {
            if (autoReconnectSession != null) {
                appendLog(getString(R.string.connection_taking_over_auto_reconnect))
                stopAutoReconnectSession(logStop = false)
            }
            startManualConnection(address)
        }
    }

    private fun disconnectCurrentConnection() {
        val connection = activeConnection
        when {
            connection != null -> {
                appendLog(getString(R.string.connection_disconnect_requested))
                activityScope.launch {
                    try {
                        connection.disconnect()
                    } catch (error: Throwable) {
                        appendLog(
                            getString(
                                R.string.connection_disconnect_failed,
                                error.message ?: error.javaClass.simpleName
                            )
                        )
                        connection.close()
                    }
                }
            }
            connectionJob != null -> {
                appendLog(getString(R.string.connection_cancel_requested))
                connectionJob?.cancel()
            }
            else -> appendLog(getString(R.string.connection_none_active))
        }
    }

    private fun startAutoReconnectSession() {
        if (!client.isConnectRuntimePermissionGranted()) {
            appendLog(getString(R.string.auto_reconnect_requires_permission))
            requestMissingPermissions()
            return
        }

        val address = normalizeAddressInput() ?: return
        activityScope.launch {
            if (isAutoReconnectSessionActive()) {
                appendLog(getString(R.string.auto_reconnect_already_active))
                return@launch
            }

            if (autoReconnectSession != null) {
                stopAutoReconnectSession(logStop = false)
            }

            if (connectionJob != null || activeConnection != null) {
                appendLog(getString(R.string.auto_reconnect_taking_over_manual_connection))
                stopManualConnectionForTakeover()
            }

            observeSelectedDevice(client.getBleDevice(address))
            val session = client.createAutoReconnectSession(
                address = address,
                config = ConnectionConfig.QUICK.copy(
                    retryDelay = DEFAULT_AUTO_RECONNECT_DELAY_MS
                ),
                reconnectDelayMs = DEFAULT_AUTO_RECONNECT_DELAY_MS,
                discoverServicesOnConnect = true,
                scope = activityScope
            )

            attachAutoReconnectSession(address, session)
            session.start()
            appendLog(
                getString(
                    R.string.auto_reconnect_started,
                    address,
                    DEFAULT_AUTO_RECONNECT_DELAY_MS
                )
            )
            updateAutoReconnectButtons()
        }
    }

    private suspend fun stopAutoReconnectSession(logStop: Boolean = true) {
        val session = autoReconnectSession
        if (session == null) {
            if (logStop) {
                appendLog(getString(R.string.auto_reconnect_none_active))
            }
            return
        }

        if (logStop) {
            appendLog(getString(R.string.auto_reconnect_stop_requested))
        }

        try {
            session.stop()
        } catch (error: Throwable) {
            appendLog(
                getString(
                    R.string.auto_reconnect_stop_failed,
                    error.message ?: error.javaClass.simpleName
                )
            )
            session.connection.value?.close()
        } finally {
            autoReconnectSnapshotJob?.cancel()
            autoReconnectSnapshotJob = null
            autoReconnectSession = null
            showIdleAutoReconnectUi()
            updateAutoReconnectButtons()
        }
    }

    private fun syncDeviceObservation() {
        if (!client.isConnectRuntimePermissionGranted()) {
            bondedDevicesJob?.cancel()
            connectedPeripheralsJob?.cancel()
            bondedDevicesJob = null
            connectedPeripheralsJob = null

            val permissionMessage = getString(R.string.device_observation_requires_permission)
            renderDeviceSectionMessage(bondedDevicesContainer, permissionMessage)
            renderDeviceSectionMessage(connectedPeripheralsContainer, permissionMessage)
            return
        }

        if (bondedDevicesJob?.isActive != true) {
            bondedDevicesJob = activityScope.launch {
                try {
                    client.observeBondedDevices().collect { devices ->
                        renderDeviceSet(
                            container = bondedDevicesContainer,
                            devices = devices,
                            emptyStateRes = R.string.bonded_devices_empty,
                            selectionLogRes = R.string.bonded_device_selected
                        )
                    }
                } catch (_: CancellationException) {
                    // Observation was stopped by the Activity lifecycle.
                } catch (error: Throwable) {
                    renderDeviceSectionMessage(
                        bondedDevicesContainer,
                        getString(R.string.device_section_unavailable)
                    )
                    appendLog(
                        getString(
                            R.string.bonded_devices_observation_failed,
                            error.message ?: error.javaClass.simpleName
                        )
                    )
                }
            }
        }

        if (connectedPeripheralsJob?.isActive != true) {
            connectedPeripheralsJob = activityScope.launch {
                try {
                    client.observeConnectedPeripherals().collect { devices ->
                        renderDeviceSet(
                            container = connectedPeripheralsContainer,
                            devices = devices,
                            emptyStateRes = R.string.connected_peripherals_empty,
                            selectionLogRes = R.string.connected_peripheral_selected
                        )
                    }
                } catch (_: CancellationException) {
                    // Observation was stopped by the Activity lifecycle.
                } catch (error: Throwable) {
                    renderDeviceSectionMessage(
                        connectedPeripheralsContainer,
                        getString(R.string.device_section_unavailable)
                    )
                    appendLog(
                        getString(
                            R.string.connected_peripherals_observation_failed,
                            error.message ?: error.javaClass.simpleName
                        )
                    )
                }
            }
        }
    }

    private fun startManualConnection(address: String) {
        val device = client.getBleDevice(address)
        observeSelectedDevice(device)

        appendLog(getString(R.string.connection_starting, address))
        connectionDetailsView.text = getString(R.string.connection_details_connecting)
        connectionJob = activityScope.launch {
            var shouldRestoreIdleDetails = true
            try {
                device.establishConnectionFlow(autoConnect = false).collect { connection ->
                    activeConnection = connection
                    appendLog(getString(R.string.connection_established, address))
                    updateConnectionButtons()

                    val servicesResult = runCatching { connection.discoverServices() }
                    val services = servicesResult.getOrNull()
                    connectionDetailsView.text = formatConnectionDetails(
                        device = device,
                        connection = connection,
                        services = services
                    )

                    servicesResult
                        .onSuccess { discoveredServices ->
                            appendLog(
                                getString(
                                    R.string.connection_services_discovered,
                                    discoveredServices.size
                                )
                            )
                        }
                        .onFailure { error ->
                            appendLog(
                                getString(
                                    R.string.connection_service_discovery_failed,
                                    error.message ?: error.javaClass.simpleName
                                )
                            )
                        }
                }
            } catch (_: CancellationException) {
                appendLog(getString(R.string.connection_stopped))
            } catch (error: Throwable) {
                shouldRestoreIdleDetails = false
                connectionDetailsView.text = getString(
                    R.string.connection_failed_details,
                    error.message ?: error.javaClass.simpleName
                )
                appendLog(
                    getString(
                        R.string.connection_failed_log,
                        error.message ?: error.javaClass.simpleName
                    )
                )
            } finally {
                activeConnection = null
                connectionJob = null
                if (
                    shouldRestoreIdleDetails &&
                    selectedDevice?.getConnectionState() == ConnectionState.Disconnected
                ) {
                    connectionDetailsView.text = getString(R.string.connection_idle_details)
                }
                updateConnectionButtons()
            }
        }
        updateConnectionButtons()
    }

    private suspend fun stopManualConnectionForTakeover() {
        val currentJob = connectionJob
        if (currentJob == null && activeConnection == null) {
            return
        }

        val currentConnection = activeConnection
        if (currentConnection != null) {
            try {
                currentConnection.disconnect()
            } catch (_: Throwable) {
                currentConnection.close()
            }
        } else {
            currentJob?.cancel()
        }

        currentJob?.join()
    }

    private fun attachAutoReconnectSession(
        address: String,
        session: AutoReconnectSession
    ) {
        autoReconnectSession = session
        autoReconnectSnapshotJob?.cancel()
        autoReconnectSnapshotJob = activityScope.launch {
            session.snapshot.collect { snapshot ->
                renderAutoReconnectSnapshot(address, snapshot)
            }
        }
        renderAutoReconnectSnapshot(address, session.snapshot.value)
    }

    private fun observeSelectedDevice(device: FlowBleDevice) {
        selectedDevice = device
        selectedDeviceStateJob?.cancel()
        selectedDeviceStateJob = activityScope.launch {
            try {
                device.snapshot.collect { snapshot ->
                    connectionStatusView.text = getString(
                        R.string.connection_status_format,
                        snapshot.address,
                        snapshot.connectionState.toDisplayText()
                    )
                    if (
                        snapshot.connectionState == ConnectionState.Disconnected &&
                        connectionJob == null &&
                        activeConnection == null
                    ) {
                        connectionDetailsView.text = getString(R.string.connection_idle_details)
                    }
                    refreshInteractiveDeviceLists()
                    updateConnectionButtons()
                }
            } catch (_: CancellationException) {
                // Observation was replaced or the Activity is finishing.
            } catch (error: Throwable) {
                appendLog(
                    getString(
                        R.string.connection_observation_failed,
                        error.message ?: error.javaClass.simpleName
                    )
                )
            }
        }
    }

    private fun updateConnectionButtons() {
        val hasPermission = client.isConnectRuntimePermissionGranted()
        val hasValidAddress = currentNormalizedAddress()
            ?.let(BluetoothAdapter::checkBluetoothAddress)
            ?: false
        val connectionActive = connectionJob != null

        connectButton.isEnabled = hasPermission && hasValidAddress && !connectionActive
        disconnectButton.isEnabled = connectionActive || activeConnection != null
    }

    private fun updateAutoReconnectButtons() {
        val hasPermission = client.isConnectRuntimePermissionGranted()
        val hasValidAddress = currentNormalizedAddress()
            ?.let(BluetoothAdapter::checkBluetoothAddress)
            ?: false

        startAutoReconnectButton.isEnabled =
            hasPermission && hasValidAddress && !isAutoReconnectSessionActive()
        stopAutoReconnectButton.isEnabled = autoReconnectSession != null
    }

    private fun isAutoReconnectSessionActive(): Boolean {
        val state = autoReconnectSession?.state?.value ?: return false
        return state != AutoReconnectState.Stopped && state !is AutoReconnectState.Failed
    }

    private fun normalizeAddressInput(): String? {
        val normalizedAddress = currentNormalizedAddress()
        return when {
            normalizedAddress.isNullOrEmpty() -> {
                deviceAddressInput.error = getString(R.string.device_address_required)
                null
            }
            !BluetoothAdapter.checkBluetoothAddress(normalizedAddress) -> {
                deviceAddressInput.error = getString(
                    R.string.invalid_device_address,
                    normalizedAddress
                )
                appendLog(
                    getString(
                        R.string.invalid_device_address,
                        normalizedAddress
                    )
                )
                null
            }
            else -> {
                if (deviceAddressInput.text.toString() != normalizedAddress) {
                    deviceAddressInput.setText(normalizedAddress)
                    deviceAddressInput.setSelection(normalizedAddress.length)
                }
                normalizedAddress
            }
        }
    }

    private fun currentNormalizedAddress(): String? {
        val rawValue = deviceAddressInput.text?.toString()?.trim().orEmpty()
        return rawValue.takeIf { it.isNotEmpty() }?.uppercase(Locale.US)
    }

    private fun showIdleConnectionUi() {
        connectionStatusView.text = getString(R.string.connection_idle_status)
        connectionDetailsView.text = getString(R.string.connection_idle_details)
    }

    private fun showIdleAutoReconnectUi() {
        autoReconnectStatusView.text = getString(R.string.auto_reconnect_idle_status)
        autoReconnectDetailsView.text = getString(R.string.auto_reconnect_idle_details)
    }

    private fun shutdownAutoReconnectSession() {
        autoReconnectSnapshotJob?.cancel()
        autoReconnectSnapshotJob = null
        autoReconnectSession?.cancel()
        autoReconnectSession = null
    }

    private fun renderAutoReconnectSnapshot(
        address: String,
        snapshot: AutoReconnectSnapshot
    ) {
        autoReconnectStatusView.text = getString(
            R.string.auto_reconnect_status_format,
            address,
            snapshot.state.toDisplayText()
        )

        val activeConnectionSnapshot = snapshot.activeConnectionSnapshot
        autoReconnectDetailsView.text = getString(
            R.string.auto_reconnect_details_format,
            if (snapshot.hasActiveConnection) {
                getString(R.string.auto_reconnect_active_yes)
            } else {
                getString(R.string.auto_reconnect_active_no)
            },
            snapshot.activeConnectionState?.toDisplayText()
                ?: getString(R.string.auto_reconnect_value_none),
            activeConnectionSnapshot?.mtu?.toString()
                ?: getString(R.string.auto_reconnect_value_unavailable),
            activeConnectionSnapshot?.services?.size?.toString()
                ?: getString(R.string.auto_reconnect_value_unavailable),
            activeConnectionSnapshot?.services
                ?.sumOf { service -> service.characteristics.size }
                ?.toString()
                ?: getString(R.string.auto_reconnect_value_unavailable),
            snapshot.lastError?.message
                ?: snapshot.lastError?.javaClass?.simpleName
                ?: getString(R.string.auto_reconnect_value_none)
        )

        updateAutoReconnectButtons()
    }

    private fun renderDeviceSectionMessage(
        container: LinearLayout,
        message: String
    ) {
        container.removeAllViews()
        container.addView(buildPanelItemTextView(text = message))
    }

    private fun renderDeviceSet(
        container: LinearLayout,
        devices: Set<FlowBleDevice>,
        emptyStateRes: Int,
        selectionLogRes: Int
    ) {
        container.removeAllViews()

        if (devices.isEmpty()) {
            container.addView(
                buildPanelItemTextView(text = getString(emptyStateRes))
            )
            return
        }

        val selectedAddress = currentNormalizedAddress()
        devices
            .sortedBy { it.address }
            .forEachIndexed { index, device ->
                container.addView(
                    buildPanelItemTextView(
                        text = formatSelectableDevice(device),
                        selected = selectedAddress == device.address.uppercase(Locale.US),
                        topMarginDp = if (index == 0) 0 else 8,
                        onClick = { selectDeviceHandle(device, selectionLogRes) }
                    )
                )
            }
    }

    private fun updateRecentScanResults(result: BleScanResult) {
        val normalizedAddress = result.address.uppercase(Locale.US)
        recentScanResults.remove(normalizedAddress)
        recentScanResults[normalizedAddress] = result

        while (recentScanResults.size > MAX_RECENT_SCAN_RESULTS) {
            val oldestAddress = recentScanResults.entries.firstOrNull()?.key ?: break
            recentScanResults.remove(oldestAddress)
        }

        renderRecentScanResults()
    }

    private fun renderRecentScanResults() {
        recentScanResultsContainer.removeAllViews()

        if (recentScanResults.isEmpty()) {
            recentScanResultsContainer.addView(
                buildPanelItemTextView(text = getString(R.string.scan_results_empty))
            )
            return
        }

        val selectedAddress = currentNormalizedAddress()
        recentScanResults.values
            .toList()
            .asReversed()
            .forEachIndexed { index, result ->
                recentScanResultsContainer.addView(
                    buildPanelItemTextView(
                        text = formatRecentScanResult(result),
                        selected = selectedAddress == result.address.uppercase(Locale.US),
                        topMarginDp = if (index == 0) 0 else 8,
                        onClick = { selectScanResult(result) }
                    )
                )
            }
    }

    private fun buildPanelItemTextView(
        text: String,
        selected: Boolean = false,
        topMarginDp: Int = 0,
        onClick: (() -> Unit)? = null
    ): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { params ->
                params.topMargin = dp(topMarginDp)
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(
                if (selected) {
                    0xFFE6F2FF.toInt()
                } else {
                    0xFFF7F7F7.toInt()
                }
            )
            typeface = Typeface.MONOSPACE
            this.text = text
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
    }

    private fun selectScanResult(result: BleScanResult) {
        selectAddress(
            address = result.address,
            previewDevice = client.getBleDevice(result.device),
            selectionLogRes = R.string.scan_result_selected
        )
    }

    private fun selectDeviceHandle(
        device: FlowBleDevice,
        selectionLogRes: Int
    ) {
        selectAddress(
            address = device.address,
            previewDevice = device,
            selectionLogRes = selectionLogRes
        )
    }

    private fun canPreviewSelectedDevice(): Boolean {
        return connectionJob == null &&
            activeConnection == null &&
            !isAutoReconnectSessionActive()
    }

    private fun selectAddress(
        address: String,
        previewDevice: FlowBleDevice,
        selectionLogRes: Int
    ) {
        val normalizedAddress = address.uppercase(Locale.US)
        updateAddressInput(normalizedAddress)

        if (canPreviewSelectedDevice()) {
            observeSelectedDevice(previewDevice)
            connectionDetailsView.text = getString(R.string.connection_idle_details)
        }

        appendLog(getString(selectionLogRes, normalizedAddress))
        refreshSelectableLists()
        updateConnectionButtons()
        updateAutoReconnectButtons()
    }

    private fun updateAddressInput(normalizedAddress: String) {
        if (deviceAddressInput.text.toString() != normalizedAddress) {
            deviceAddressInput.setText(normalizedAddress)
            deviceAddressInput.setSelection(normalizedAddress.length)
        }
    }

    private fun formatRecentScanResult(result: BleScanResult): String {
        return getString(
            R.string.scan_result_card_format,
            result.deviceName ?: getString(R.string.unknown_device_name),
            result.address,
            result.rssi,
            result.isConnectable?.toString() ?: getString(R.string.unknown_value)
        )
    }

    private fun formatSelectableDevice(device: FlowBleDevice): String {
        val snapshot = device.currentSnapshot()
        val deviceName = snapshot.name
            ?.takeUnless { it.isBlank() }
            ?: getString(R.string.unknown_device_name)
        return getString(
            R.string.device_card_format,
            deviceName,
            snapshot.address,
            snapshot.connectionState.toDisplayText()
        )
    }

    private fun refreshInteractiveDeviceLists() {
        runCatching {
            renderDeviceSet(
                container = bondedDevicesContainer,
                devices = client.getBondedDevices(),
                emptyStateRes = R.string.bonded_devices_empty,
                selectionLogRes = R.string.bonded_device_selected
            )
        }
        runCatching {
            renderDeviceSet(
                container = connectedPeripheralsContainer,
                devices = client.getConnectedPeripherals(),
                emptyStateRes = R.string.connected_peripherals_empty,
                selectionLogRes = R.string.connected_peripheral_selected
            )
        }
    }

    private fun refreshSelectableLists() {
        renderRecentScanResults()
        refreshInteractiveDeviceLists()
    }

    private fun formatConnectionDetails(
        device: FlowBleDevice,
        connection: BleConnection,
        services: List<BleService>?
    ): String {
        val deviceName = device.currentSnapshot().name
            ?.takeUnless { it.isBlank() }
            ?: getString(R.string.unknown_device_name)

        return if (services == null) {
            getString(
                R.string.connection_details_without_services,
                device.address,
                deviceName,
                connection.mtu.value
            )
        } else {
            getString(
                R.string.connection_details_format,
                device.address,
                deviceName,
                connection.mtu.value,
                services.size,
                services.sumOf { service -> service.characteristics.size }
            )
        }
    }

    private fun formatPermissionList(permissions: Array<String>): String {
        return permissions.joinToString().ifBlank {
            getString(R.string.summary_list_none)
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        private const val MAX_LOG_LINES = 40
        private const val MAX_RECENT_SCAN_RESULTS = 8
        private const val DEFAULT_AUTO_RECONNECT_DELAY_MS = 1_500L
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}

private fun ConnectionState.toDisplayText(): String {
    return when (this) {
        ConnectionState.Connected -> "Connected"
        ConnectionState.Connecting -> "Connecting"
        ConnectionState.Disconnected -> "Disconnected"
        ConnectionState.Disconnecting -> "Disconnecting"
    }
}

private fun AutoReconnectState.toDisplayText(): String {
    return when (this) {
        AutoReconnectState.Connected -> "Connected"
        is AutoReconnectState.Connecting -> "Connecting (attempt $attempt)"
        is AutoReconnectState.Recovering -> "Recovering (attempt $attempt)"
        is AutoReconnectState.WaitingToRetry -> "Waiting to retry (attempt $attempt in ${delayMs}ms)"
        is AutoReconnectState.Failed -> "Failed after $attempts attempts"
        AutoReconnectState.Idle -> "Idle"
        AutoReconnectState.Stopped -> "Stopped"
    }
}
