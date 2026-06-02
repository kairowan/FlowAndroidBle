package com.flowble.model

import android.bluetooth.BluetoothGatt

/**
 * Preferred connection priority for a BLE link.
 *
 * Higher priorities typically favor throughput over power consumption.
 */
enum class ConnectionPriority {
    LOW_POWER,
    BALANCED,
    HIGH;

    internal fun toAndroid(): Int = when (this) {
        LOW_POWER -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
        BALANCED -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        HIGH -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
    }
}
