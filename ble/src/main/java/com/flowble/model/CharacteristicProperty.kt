package com.flowble.model

import android.bluetooth.BluetoothGattCharacteristic

/**
 * Enum mapping Android's characteristic property bit flags.
 */
enum class CharacteristicProperty {
    READ,
    WRITE,
    WRITE_NO_RESPONSE,
    NOTIFY,
    INDICATE,
    BROADCAST,
    SIGNED_WRITE,
    EXTENDED_PROPS;

    companion object {
        /**
         * Converts Android's property integer flags to a set of [CharacteristicProperty].
         */
        internal fun fromAndroid(properties: Int): Set<CharacteristicProperty> {
            val result = mutableSetOf<CharacteristicProperty>()
            if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) result.add(READ)
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) result.add(WRITE)
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) result.add(WRITE_NO_RESPONSE)
            if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) result.add(NOTIFY)
            if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) result.add(INDICATE)
            if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) result.add(BROADCAST)
            if (properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) result.add(SIGNED_WRITE)
            if (properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) result.add(EXTENDED_PROPS)
            return result
        }
    }
}
