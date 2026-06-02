package com.flowble.model

import android.bluetooth.BluetoothAdapter

/**
 * Direct Bluetooth adapter state independent of scan/location permission policy.
 *
 * This is useful when code wants the raw adapter lifecycle without conflating it with
 * location services or runtime permission requirements.
 */
enum class BleAdapterState {
    ON,
    OFF,
    TURNING_ON,
    TURNING_OFF,
    NOT_AVAILABLE;

    companion object {
        fun from(
            bluetoothAvailable: Boolean,
            bluetoothState: Int
        ): BleAdapterState {
            if (!bluetoothAvailable) {
                return NOT_AVAILABLE
            }

            return when (bluetoothState) {
                BluetoothAdapter.STATE_ON -> ON
                BluetoothAdapter.STATE_TURNING_ON -> TURNING_ON
                BluetoothAdapter.STATE_TURNING_OFF -> TURNING_OFF
                BluetoothAdapter.STATE_OFF -> OFF
                else -> OFF
            }
        }
    }
}
