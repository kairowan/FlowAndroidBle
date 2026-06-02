package com.flowble.internal

import com.flowble.exception.BleCharacteristicConflictException
import com.flowble.exception.BleCharacteristicNotFoundException
import com.flowble.exception.BleDescriptorNotFoundException
import com.flowble.exception.BleServiceConflictException
import com.flowble.exception.BleServiceNotFoundException
import com.flowble.model.BleCharacteristic
import com.flowble.model.BleDescriptor
import com.flowble.model.BleService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache for discovered BLE services, characteristics, and descriptors.
 *
 * This cache reduces the need to repeatedly discover services and
 * provides quick lookups by UUID.
 */
internal class CharacteristicCache {

    @Volatile
    private var discoveredServices: List<BleService> = emptyList()

    private val services = ConcurrentHashMap<UUID, MutableList<BleService>>()
    private val characteristics = ConcurrentHashMap<String, MutableList<BleCharacteristic>>()
    private val descriptors = ConcurrentHashMap<String, BleDescriptor>()

    /**
     * Cache a list of services and their characteristics.
     */
    fun cacheServices(serviceList: List<BleService>) {
        discoveredServices = serviceList.toList()
        services.clear()
        characteristics.clear()
        descriptors.clear()

        serviceList.forEach { service ->
            services.getOrPut(service.uuid) { mutableListOf() }.add(service)

            service.characteristics.forEach { char ->
                val charKey = characteristicGroupKey(service.uuid, char.uuid)
                characteristics.getOrPut(charKey) { mutableListOf() }.add(char)

                char.descriptors.forEach { desc ->
                    val descKey = descriptorKey(service.uuid, char.uuid, desc.uuid)
                    descriptors[descKey] = desc
                }
            }
        }
    }

    /**
     * Get a cached service by UUID.
     */
    fun getService(uuid: UUID): BleService? = getServices(uuid).singleOrNull()

    /**
     * Get all cached services matching the UUID.
     */
    fun getServices(uuid: UUID): List<BleService> = services[uuid]?.toList().orEmpty()

    /**
     * Get all cached services.
     */
    fun getAllServices(): List<BleService> = discoveredServices

    /**
     * Get a cached characteristic by service UUID and characteristic UUID.
     */
    fun getCharacteristic(serviceUuid: UUID, characteristicUuid: UUID): BleCharacteristic? {
        val matches = getCharacteristics(serviceUuid, characteristicUuid)
        return if (matches.size == 1) matches.first() else null
    }

    /**
     * Get all cached characteristics matching the given service UUID and characteristic UUID.
     */
    fun getCharacteristics(serviceUuid: UUID, characteristicUuid: UUID): List<BleCharacteristic> {
        return characteristics[characteristicGroupKey(serviceUuid, characteristicUuid)]?.toList().orEmpty()
    }

    /**
     * Get a cached descriptor by service UUID, characteristic UUID, and descriptor UUID.
     */
    fun getDescriptor(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID
    ): BleDescriptor? {
        return descriptors[descriptorKey(serviceUuid, characteristicUuid, descriptorUuid)]
    }

    /**
     * Get all characteristics for a service.
     */
    fun getCharacteristicsForService(serviceUuid: UUID): List<BleCharacteristic> {
        return getServices(serviceUuid).flatMap { service -> service.characteristics }
    }

    /**
     * Search for a characteristic across all services.
     */
    fun findCharacteristic(characteristicUuid: UUID): BleCharacteristic? {
        return findCharacteristics(characteristicUuid).singleOrNull()
    }

    /**
     * Search for all matching characteristics across all services.
     */
    fun findCharacteristics(characteristicUuid: UUID): List<BleCharacteristic> {
        return discoveredServices
            .flatMap { it.characteristics }
            .filter { it.uuid == characteristicUuid }
    }

    /**
     * Search for all matching characteristics within a specific service.
     */
    fun findCharacteristics(serviceUuid: UUID, characteristicUuid: UUID): List<BleCharacteristic> {
        return getCharacteristics(serviceUuid, characteristicUuid)
    }

    /**
     * Check if the cache has any data.
     */
    fun hasData(): Boolean = discoveredServices.isNotEmpty()

    fun requireService(serviceUuid: UUID): BleService {
        val matches = getServices(serviceUuid)
        return when (matches.size) {
            0 -> throw BleServiceNotFoundException(serviceUuid)
            1 -> matches.first()
            else -> throw BleServiceConflictException(
                serviceUuid = serviceUuid,
                message = "Multiple services found for UUID $serviceUuid. " +
                    "Use discoverServices() and inspect the returned list explicitly."
            )
        }
    }

    fun requireCharacteristic(characteristicUuid: UUID): BleCharacteristic {
        val matches = findCharacteristics(characteristicUuid)
        return when (matches.size) {
            0 -> throw BleCharacteristicNotFoundException(characteristicUuid)
            1 -> matches.first()
            else -> throw BleCharacteristicConflictException(
                characteristicUuid = characteristicUuid,
                message = "Multiple characteristics found for UUID $characteristicUuid. " +
                    "Use discoverServices() and pass an explicit BleCharacteristic instead."
            )
        }
    }

    fun requireCharacteristic(serviceUuid: UUID, characteristicUuid: UUID): BleCharacteristic {
        val matches = findCharacteristics(serviceUuid, characteristicUuid)
        return when (matches.size) {
            0 -> throw BleCharacteristicNotFoundException(
                characteristicUuid = characteristicUuid,
                serviceUuid = serviceUuid
            )
            1 -> matches.first()
            else -> throw BleCharacteristicConflictException(
                characteristicUuid = characteristicUuid,
                serviceUuid = serviceUuid,
                message = "Multiple characteristics found for service=$serviceUuid characteristic=$characteristicUuid. " +
                    "Use discoverServices() and pass an explicit BleCharacteristic instead."
            )
        }
    }

    fun requireDescriptor(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID
    ): BleDescriptor {
        return getDescriptor(serviceUuid, characteristicUuid, descriptorUuid)
            ?: throw BleDescriptorNotFoundException(
                descriptorUuid = descriptorUuid,
                characteristicUuid = characteristicUuid,
                serviceUuid = serviceUuid
            )
    }

    /**
     * Clear the cache.
     */
    fun clear() {
        discoveredServices = emptyList()
        services.clear()
        characteristics.clear()
        descriptors.clear()
    }

    private fun characteristicGroupKey(serviceUuid: UUID, characteristicUuid: UUID): String {
        return "$serviceUuid/$characteristicUuid"
    }

    private fun descriptorKey(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID
    ): String {
        return "$serviceUuid/$characteristicUuid/$descriptorUuid"
    }
}
