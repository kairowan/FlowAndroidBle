package com.flowble.internal

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Build

internal fun Intent.getBluetoothDeviceExtra(name: String): BluetoothDevice? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
}
