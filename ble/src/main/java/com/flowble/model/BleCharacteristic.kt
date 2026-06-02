package com.flowble.model

import android.bluetooth.BluetoothGattCharacteristic
import com.flowble.exception.BleDescriptorConflictException
import com.flowble.exception.BleDescriptorNotFoundException
import java.util.UUID

/**
 * BLE characteristic representation.
 *
 * @property uuid The UUID of this characteristic.
 * @property serviceUuid The UUID of the service this characteristic belongs to.
 * @property properties The set of properties this characteristic supports.
 * @property descriptors The list of descriptors attached to this characteristic.
 */
data class BleCharacteristic(
    val uuid: UUID,
    val serviceUuid: UUID,
    val instanceId: Int = 0,
    val properties: Set<CharacteristicProperty>,
    val descriptors: List<BleDescriptor>
) {
    /**
     * Return every descriptor on this characteristic with the given UUID.
     */
    fun getDescriptors(descriptorUuid: UUID): List<BleDescriptor> {
        return descriptors.filter { descriptor -> descriptor.uuid == descriptorUuid }
    }

    /**
     * Resolve a single descriptor on this characteristic by UUID.
     *
     * Throws when no descriptor matches, or when more than one match exists.
     */
    fun getDescriptor(descriptorUuid: UUID): BleDescriptor {
        val matches = getDescriptors(descriptorUuid)
        return when (matches.size) {
            0 -> throw BleDescriptorNotFoundException(
                descriptorUuid = descriptorUuid,
                characteristicUuid = uuid,
                serviceUuid = serviceUuid,
                message = "No descriptor found for UUID $descriptorUuid in characteristic $uuid (service=$serviceUuid)"
            )
            1 -> matches.single()
            else -> throw BleDescriptorConflictException(
                descriptorUuid = descriptorUuid,
                characteristicUuid = uuid,
                serviceUuid = serviceUuid,
                message = "Multiple descriptors found for UUID $descriptorUuid in characteristic $uuid " +
                    "(service=$serviceUuid). Use getDescriptors(...) to disambiguate."
            )
        }
    }

    /**
     * The underlying Android characteristic, used internally for GATT operations.
     * This is not exposed publicly to keep the API clean.
     */
    @Transient
    internal var androidCharacteristic: BluetoothGattCharacteristic? = null

    /**
     * Two characteristics are considered equal if they have the same UUID, instance ID,
     * and belong to the same service.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleCharacteristic) return false
        return uuid == other.uuid &&
            serviceUuid == other.serviceUuid &&
            instanceId == other.instanceId
    }

    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + serviceUuid.hashCode()
        result = 31 * result + instanceId
        return result
    }

    companion object {
        /**
         * Creates a [BleCharacteristic] from an Android [BluetoothGattCharacteristic].
         */
        internal fun fromAndroid(
            characteristic: BluetoothGattCharacteristic,
            serviceUuid: UUID
        ): BleCharacteristic {
            val descriptors = characteristic.descriptors.map { descriptor ->
                BleDescriptor.fromAndroid(descriptor, serviceUuid, characteristic.uuid)
            }
            return BleCharacteristic(
                uuid = characteristic.uuid,
                serviceUuid = serviceUuid,
                instanceId = characteristic.instanceId,
                properties = CharacteristicProperty.fromAndroid(characteristic.properties),
                descriptors = descriptors
            ).apply {
                androidCharacteristic = characteristic
            }
        }
    }
}
