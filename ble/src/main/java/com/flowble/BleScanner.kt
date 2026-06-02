package com.flowble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import com.flowble.exception.BleScanException
import com.flowble.exception.ScanFailedException
import com.flowble.model.BleScanResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * BLE scanning functionality wrapping Android's [android.bluetooth.le.BluetoothLeScanner].
 *
 * This class provides a Flow-based API for scanning BLE devices.
 * The scan automatically stops when the Flow collector is cancelled.
 *
 * Note: The caller is responsible for checking and requesting BLE scanning permissions
 * (BLUETOOTH_SCAN on Android 12+, ACCESS_FINE_LOCATION on older versions) before calling
 * scan methods.
 */
class BleScanner internal constructor(private val context: Context) {

    /**
     * Start a BLE scan with the given configuration.
     *
     * @param config The scan configuration. Uses default values if not specified.
     * @return A Flow that emits [BleScanResult] for each discovered device.
     */
    @SuppressLint("MissingPermission")
    fun scan(config: ScannerConfig = ScannerConfig()): Flow<BleScanResult> = callbackFlow {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: run {
                close(BleScanException(BleScanException.BLUETOOTH_NOT_AVAILABLE))
                return@callbackFlow
            }

        val adapter = bluetoothManager.adapter
            ?: run {
                close(BleScanException(BleScanException.BLUETOOTH_NOT_AVAILABLE))
                return@callbackFlow
            }

        if (!adapter.isEnabled) {
            close(BleScanException(BleScanException.BLUETOOTH_DISABLED))
            return@callbackFlow
        }

        val scanner = adapter.bluetoothLeScanner
            ?: run {
                close(BleScanException(BleScanException.BLUETOOTH_CANNOT_START))
                return@callbackFlow
            }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(BleScanResult.fromAndroid(result))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    trySend(BleScanResult.fromAndroid(result))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(ScanFailedException(errorCode))
            }
        }

        scanner.startScan(config.filters, config.settings, callback)

        val timeoutJob = if (config.timeoutMs > 0) {
            launch {
                delay(config.timeoutMs)
                close()
            }
        } else {
            null
        }

        awaitClose {
            timeoutJob?.cancel()
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
                // Ignore errors during cleanup
            }
        }
    }

    /**
     * Scan for a specific device by MAC address.
     *
     * @param address The MAC address of the device to scan for.
     * @param timeoutMs Timeout in milliseconds. Default is 30 seconds.
     * @return A Flow that emits [BleScanResult] for the specified device.
     */
    @SuppressLint("MissingPermission")
    fun scanForDevice(address: String, timeoutMs: Long = 30_000): Flow<BleScanResult> {
        val filter = ScanFilter.Builder()
            .setDeviceAddress(address)
            .build()

        val config = ScannerConfig.build {
            addFilter(filter)
            setTimeoutMs(timeoutMs)
        }

        return scan(config)
    }

    companion object {
        private const val EXTRA_LIST_SCAN_RESULT = "android.bluetooth.le.extra.LIST_SCAN_RESULT"
        private const val EXTRA_CALLBACK_TYPE = "android.bluetooth.le.extra.CALLBACK_TYPE"
        private const val EXTRA_ERROR_CODE = "android.bluetooth.le.extra.ERROR_CODE"

        /**
         * Parse background scan results delivered through a `PendingIntent`-based BLE scan.
         *
         * This is a lightweight bridge for apps that use platform background scans but still want
         * FlowAndroidBle's richer [BleScanResult] model.
         */
        fun getBackgroundScanResults(intent: Intent): List<BleScanResult> {
            val callbackType = getBackgroundScanCallbackType(intent)
            val results = intent.getScanResultsExtra().orEmpty()
            return results.map { scanResult ->
                val parsed = BleScanResult.fromAndroid(scanResult)
                if (parsed.callbackType == null && callbackType != null) {
                    parsed.copy(callbackType = callbackType)
                } else {
                    parsed
                }
            }
        }

        /**
         * Return the scan callback type delivered through a background scan `Intent`, if present.
         */
        fun getBackgroundScanCallbackType(intent: Intent): Int? {
            return if (intent.hasExtra(EXTRA_CALLBACK_TYPE)) {
                intent.getIntExtra(EXTRA_CALLBACK_TYPE, -1).takeIf { it >= 0 }
            } else {
                null
            }
        }

        /**
         * Return a typed scan failure when a background scan `Intent` carries an error code.
         */
        fun getBackgroundScanError(intent: Intent): ScanFailedException? {
            if (!intent.hasExtra(EXTRA_ERROR_CODE)) {
                return null
            }

            val errorCode = intent.getIntExtra(EXTRA_ERROR_CODE, -1)
            return errorCode.takeIf { it >= 0 }?.let(::ScanFailedException)
        }

        private fun Intent.getScanResultsExtra(): ArrayList<ScanResult>? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableArrayListExtra(EXTRA_LIST_SCAN_RESULT, ScanResult::class.java)
            } else {
                @Suppress("DEPRECATION")
                getParcelableArrayListExtra(EXTRA_LIST_SCAN_RESULT)
            }
        }
    }
}
