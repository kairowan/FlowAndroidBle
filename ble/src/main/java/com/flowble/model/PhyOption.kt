package com.flowble.model

import android.bluetooth.BluetoothDevice
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Coding preference for LE Coded PHY requests.
 */
enum class PhyOption {
    NO_PREFERRED,
    S2,
    S8;

    @RequiresApi(Build.VERSION_CODES.O)
    internal fun toAndroid(): Int = when (this) {
        NO_PREFERRED -> BluetoothDevice.PHY_OPTION_NO_PREFERRED
        S2 -> BluetoothDevice.PHY_OPTION_S2
        S8 -> BluetoothDevice.PHY_OPTION_S8
    }
}
