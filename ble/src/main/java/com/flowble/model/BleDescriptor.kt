package com.flowble.model

import android.bluetooth.BluetoothGattDescriptor
import java.util.UUID

/**
 * BLE descriptor representation.
 *
 * @property uuid The UUID of this descriptor.
 * @property serviceUuid The UUID of the service this descriptor belongs to.
 * @property characteristicUuid The UUID of the characteristic this descriptor belongs to.
 */
data class BleDescriptor(
    val uuid: UUID,
    val serviceUuid: UUID,
    val characteristicUuid: UUID
) {
    /**
     * The underlying Android descriptor, used internally for GATT operations.
     * This is not exposed publicly to keep the API clean.
     */
    @Transient
    internal var androidDescriptor: BluetoothGattDescriptor? = null

    companion object {
        /**
         * Creates a [BleDescriptor] from an Android [BluetoothGattDescriptor].
         */
        internal fun fromAndroid(
            descriptor: BluetoothGattDescriptor,
            serviceUuid: UUID,
            characteristicUuid: UUID
        ): BleDescriptor = BleDescriptor(
            uuid = descriptor.uuid,
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid
        ).apply {
            androidDescriptor = descriptor
        }
    }
}
