package com.flowble.model

import android.bluetooth.BluetoothGattCharacteristic

/**
 * Specifies whether a write operation expects a response from the peripheral.
 */
enum class WriteType {
    /** Write with response (default). The peripheral will send a confirmation. */
    DEFAULT,

    /** Write without response. Faster but not guaranteed delivery. */
    NO_RESPONSE;

    /**
     * Maps to Android's [BluetoothGattCharacteristic] write type constant.
     */
    internal fun toAndroid(): Int = when (this) {
        DEFAULT -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        NO_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    }
}
