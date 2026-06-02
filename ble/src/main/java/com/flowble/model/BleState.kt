package com.flowble.model

import android.bluetooth.BluetoothAdapter
import android.location.LocationManager

/**
 * Represents the overall state of the BLE system.
 */
sealed interface BleState {
    /** BLE is ready for operations. */
    data object Ready : BleState

    /** Bluetooth is not available on this device. */
    data object BluetoothNotAvailable : BleState

    /** Bluetooth is turned off. */
    data object BluetoothDisabled : BleState

    /** Location permission is not granted (required for scanning on older Android). */
    data object LocationPermissionNotGranted : BleState

    /** Location services are disabled (required for scanning on older Android). */
    data object LocationServicesDisabled : BleState

    /** Bluetooth is turning on. */
    data object BluetoothTurningOn : BleState

    /** Bluetooth is turning off. */
    data object BluetoothTurningOff : BleState

    companion object {
        /**
         * Determines the BLE state based on current system state.
         */
        fun determine(
            bluetoothAvailable: Boolean,
            bluetoothEnabled: Boolean,
            bluetoothState: Int,
            locationPermissionGranted: Boolean,
            locationEnabled: Boolean
        ): BleState {
            if (!bluetoothAvailable) return BluetoothNotAvailable

            return when (bluetoothState) {
                BluetoothAdapter.STATE_OFF -> BluetoothDisabled
                BluetoothAdapter.STATE_TURNING_ON -> BluetoothTurningOn
                BluetoothAdapter.STATE_TURNING_OFF -> BluetoothTurningOff
                BluetoothAdapter.STATE_ON -> {
                    if (!locationPermissionGranted) return LocationPermissionNotGranted
                    if (!locationEnabled) return LocationServicesDisabled
                    Ready
                }
                else -> BluetoothDisabled
            }
        }
    }
}
