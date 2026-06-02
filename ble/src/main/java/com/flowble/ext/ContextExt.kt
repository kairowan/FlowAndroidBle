package com.flowble.ext

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * Get the [BluetoothAdapter] via [BluetoothManager].
 */
fun Context.bluetoothAdapter(): BluetoothAdapter? {
    val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return bluetoothManager?.adapter
}

/**
 * Check if BLE is supported on this device.
 */
fun Context.isBleSupported(): Boolean {
    return packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
}

/**
 * Check if Bluetooth is enabled.
 */
fun Context.isBluetoothEnabled(): Boolean {
    return bluetoothAdapter()?.isEnabled == true
}
