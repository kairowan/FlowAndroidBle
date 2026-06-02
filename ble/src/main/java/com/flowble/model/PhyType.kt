package com.flowble.model

import android.bluetooth.BluetoothDevice
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * BLE PHY types supported by Android's LE stack.
 */
enum class PhyType {
    LE_1M,
    LE_2M,
    LE_CODED,
    UNKNOWN;

    @RequiresApi(Build.VERSION_CODES.O)
    internal fun toMask(): Int = when (this) {
        LE_1M -> BluetoothDevice.PHY_LE_1M_MASK
        LE_2M -> BluetoothDevice.PHY_LE_2M_MASK
        LE_CODED -> BluetoothDevice.PHY_LE_CODED_MASK
        UNKNOWN -> throw IllegalArgumentException("UNKNOWN cannot be used as a preferred PHY")
    }

    companion object {
        @RequiresApi(Build.VERSION_CODES.O)
        internal fun fromAndroid(value: Int): PhyType = when (value) {
            BluetoothDevice.PHY_LE_1M -> LE_1M
            BluetoothDevice.PHY_LE_2M -> LE_2M
            BluetoothDevice.PHY_LE_CODED -> LE_CODED
            else -> UNKNOWN
        }
    }
}
