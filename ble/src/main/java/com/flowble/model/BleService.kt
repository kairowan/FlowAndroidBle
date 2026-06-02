package com.flowble.model

import android.bluetooth.BluetoothGattService
import com.flowble.exception.BleCharacteristicConflictException
import com.flowble.exception.BleCharacteristicNotFoundException
import com.flowble.exception.BleDescriptorConflictException
import com.flowble.exception.BleDescriptorNotFoundException
import java.util.UUID

/**
 * BLE service representation after GATT discovery.
 *
 * @property uuid The UUID of this service.
 * @property characteristics The list of characteristics in this service.
 * @property isPrimary Whether this is a primary (vs secondary) service.
 */
data class BleService(
    val uuid: UUID,
    val characteristics: List<BleCharacteristic>,
    val isPrimary: Boolean
) {
    /**
     * Return every characteristic in this service with the given UUID.
     *
     * Duplicate UUIDs are preserved so callers can disambiguate by [BleCharacteristic.instanceId]
     * when needed.
     */
    fun getCharacteristics(characteristicUuid: UUID): List<BleCharacteristic> {
        return characteristics.filter { characteristic -> characteristic.uuid == characteristicUuid }
    }

    /**
     * Resolve a single characteristic in this service by UUID.
     *
     * Throws when no characteristic matches, or when more than one match exists.
     */
    fun getCharacteristic(characteristicUuid: UUID): BleCharacteristic {
        val matches = getCharacteristics(characteristicUuid)
        return when (matches.size) {
            0 -> throw BleCharacteristicNotFoundException(
                characteristicUuid = characteristicUuid,
                serviceUuid = uuid,
                message = "No characteristic found for UUID $characteristicUuid in service $uuid"
            )
            1 -> matches.single()
            else -> throw BleCharacteristicConflictException(
                characteristicUuid = characteristicUuid,
                serviceUuid = uuid,
                message = "Multiple characteristics found for UUID $characteristicUuid in service $uuid. " +
                    "Use getCharacteristics(...) and disambiguate by instanceId."
            )
        }
    }

    /**
     * Return every descriptor matching the given characteristic UUID and descriptor UUID pair.
     *
     * Duplicate matches are preserved so callers can inspect all candidates when a peripheral
     * exposes repeated characteristic instances.
     */
    fun getDescriptors(
        characteristicUuid: UUID,
        descriptorUuid: UUID
    ): List<BleDescriptor> {
        return getCharacteristics(characteristicUuid)
            .flatMap { characteristic -> characteristic.getDescriptors(descriptorUuid) }
    }

    /**
     * Resolve a single descriptor in this service by characteristic UUID and descriptor UUID.
     *
     * Throws when no descriptor matches, or when more than one match exists.
     */
    fun getDescriptor(
        characteristicUuid: UUID,
        descriptorUuid: UUID
    ): BleDescriptor {
        val matches = getDescriptors(characteristicUuid, descriptorUuid)
        return when (matches.size) {
            0 -> throw BleDescriptorNotFoundException(
                descriptorUuid = descriptorUuid,
                characteristicUuid = characteristicUuid,
                serviceUuid = uuid,
                message = "No descriptor found for UUID $descriptorUuid in service $uuid " +
                    "for characteristic $characteristicUuid"
            )
            1 -> matches.single()
            else -> throw BleDescriptorConflictException(
                descriptorUuid = descriptorUuid,
                characteristicUuid = characteristicUuid,
                serviceUuid = uuid,
                message = "Multiple descriptors found for UUID $descriptorUuid in service $uuid " +
                    "for characteristic $characteristicUuid. Use getDescriptors(...) to disambiguate."
            )
        }
    }

    companion object {
        /**
         * Creates a [BleService] from an Android [BluetoothGattService].
         */
        internal fun fromAndroid(service: BluetoothGattService): BleService {
            val characteristics = service.characteristics.map { characteristic ->
                BleCharacteristic.fromAndroid(characteristic, service.uuid)
            }
            return BleService(
                uuid = service.uuid,
                characteristics = characteristics,
                isPrimary = service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
        }
    }
}
