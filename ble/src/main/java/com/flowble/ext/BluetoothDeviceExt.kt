package com.flowble.ext

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import com.flowble.FlowBleClient
import com.flowble.FlowBleDevice
import com.flowble.model.BleScanResult

/**
 * Create a minimal [BleScanResult] from a known [BluetoothDevice].
 *
 * This is useful for reconnection scenarios where you already have the device reference
 * but need a [BleScanResult] for the API.
 *
 * @param rssi The RSSI value. Default is 0 (unknown).
 */
@SuppressLint("MissingPermission")
fun BluetoothDevice.toBleScanResult(rssi: Int = 0): BleScanResult {
    return BleScanResult(
        device = this,
        rssi = rssi,
        timestampNanos = System.nanoTime(),
        scanRecord = null
    )
}

/**
 * Convert a platform [BluetoothDevice] into a stable [FlowBleDevice] handle.
 */
fun BluetoothDevice.toFlowBleDevice(client: FlowBleClient): FlowBleDevice {
    return client.getBleDevice(this)
}

/**
 * Convenience alias.
 */
fun BluetoothDevice.getBleDevice(client: FlowBleClient): FlowBleDevice {
    return toFlowBleDevice(client)
}
