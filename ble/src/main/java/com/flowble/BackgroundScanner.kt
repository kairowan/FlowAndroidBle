package com.flowble

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import com.flowble.exception.BleScanException
import com.flowble.exception.ScanFailedException
import com.flowble.model.BleScanResult

/**
 * PendingIntent-based background BLE scanning support.
 *
 * This wraps the platform's background scan entry points while keeping the result parsing
 * consistent with the rest of FlowAndroidBle.
 */
class BackgroundScanner internal constructor(
    private val context: Context,
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val scannerProvider: () -> BluetoothLeScanner? = {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
    }
) {
    /**
     * Whether PendingIntent-based background scans are supported on this device/runtime.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
    fun isSupported(): Boolean = sdkIntProvider() >= Build.VERSION_CODES.O

    /**
     * Start a background BLE scan using a full [ScannerConfig].
     *
     * The scan continues after process death as long as the platform keeps the scan active and
     * delivers callbacks to [callbackIntent].
     */
    @SuppressLint("MissingPermission")
    fun startScan(
        callbackIntent: PendingIntent,
        config: ScannerConfig = ScannerConfig(timeoutMs = 0L)
    ) {
        if (!isSupported()) {
            throw BleScanException(BleScanException.BLUETOOTH_CANNOT_START)
        }
        val scanner = scannerOrThrow()
        val status = scanner.startScan(config.filters, config.settings, callbackIntent)
        if (status != START_SCAN_SUCCESS) {
            throw ScanFailedException(status)
        }
    }

    /**
     * Convenience overload using scan settings and vararg filters.
     */
    @SuppressLint("MissingPermission")
    fun startScan(
        callbackIntent: PendingIntent,
        scanSettings: ScanSettings,
        vararg scanFilters: ScanFilter
    ) {
        startScan(
            callbackIntent = callbackIntent,
            config = ScannerConfig(
                filters = scanFilters.toList(),
                settings = scanSettings,
                timeoutMs = 0L
            )
        )
    }

    /**
     * Stop a previously started background scan.
     */
    @SuppressLint("MissingPermission")
    fun stopScan(callbackIntent: PendingIntent) {
        if (!isSupported()) {
            throw BleScanException(BleScanException.BLUETOOTH_CANNOT_START)
        }
        scannerOrThrow().stopScan(callbackIntent)
    }

    private fun scannerOrThrow(): BluetoothLeScanner {
        return scannerProvider()
            ?: throw BleScanException(BleScanException.BLUETOOTH_CANNOT_START)
    }

    companion object {
        private const val START_SCAN_SUCCESS = 0

        /**
         * Parse background scan results delivered through a `PendingIntent` callback [Intent].
         */
        fun getScanResults(intent: Intent): List<BleScanResult> {
            return BleScanner.getBackgroundScanResults(intent)
        }

        /**
         * Return the callback type delivered through a background scan [Intent], if present.
         */
        fun getCallbackType(intent: Intent): Int? {
            return BleScanner.getBackgroundScanCallbackType(intent)
        }

        /**
         * Return a typed scan failure when the background scan [Intent] carries an error code.
         */
        fun getScanError(intent: Intent): ScanFailedException? {
            return BleScanner.getBackgroundScanError(intent)
        }
    }
}
