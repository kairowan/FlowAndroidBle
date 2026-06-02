package com.flowble.model

import android.bluetooth.le.ScanFilter
import android.os.ParcelUuid
import java.util.UUID

/**
 * Builder for creating BLE scan filters with a clean DSL.
 *
 * Usage:
 * ```kotlin
 * val filter = scanFilter {
 *     deviceName("MyDevice")
 *     deviceAddress("AA:BB:CC:DD:EE:FF")
 *     serviceUuid(UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"))
 * }
 * ```
 */
class ScanFilterBuilder {
    private var deviceName: String? = null
    private var deviceAddress: String? = null
    private var serviceUuid: UUID? = null
    private var serviceUuidMask: UUID? = null
    private var manufacturerCompanyId: Int? = null
    private var manufacturerData: ByteArray? = null
    private var manufacturerDataMask: ByteArray? = null
    private var serviceData: ByteArray? = null
    private var serviceDataMask: ByteArray? = null
    private var serviceDataUuid: UUID? = null

    /**
     * Filter by device name.
     */
    fun deviceName(name: String): ScanFilterBuilder {
        this.deviceName = name
        return this
    }

    /**
     * Filter by device MAC address.
     */
    fun deviceAddress(address: String): ScanFilterBuilder {
        this.deviceAddress = address
        return this
    }

    /**
     * Filter by service UUID.
     */
    fun serviceUuid(uuid: UUID, mask: UUID? = null): ScanFilterBuilder {
        this.serviceUuid = uuid
        this.serviceUuidMask = mask
        return this
    }

    /**
     * Filter by manufacturer specific data.
     */
    fun manufacturerData(
        companyId: Int,
        data: ByteArray,
        mask: ByteArray? = null
    ): ScanFilterBuilder {
        this.manufacturerCompanyId = companyId
        this.manufacturerData = data
        this.manufacturerDataMask = mask
        return this
    }

    /**
     * Filter by service data.
     */
    fun serviceData(
        uuid: UUID,
        data: ByteArray,
        mask: ByteArray? = null
    ): ScanFilterBuilder {
        this.serviceDataUuid = uuid
        this.serviceData = data
        this.serviceDataMask = mask
        return this
    }

    /**
     * Build the [ScanFilter].
     */
    fun build(): ScanFilter {
        val builder = ScanFilter.Builder()

        deviceName?.let { builder.setDeviceName(it) }
        deviceAddress?.let { builder.setDeviceAddress(it) }

        if (serviceUuid != null) {
            builder.setServiceUuid(
                ParcelUuid(serviceUuid),
                serviceUuidMask?.let { ParcelUuid(it) }
            )
        }

        if (manufacturerData != null) {
            builder.setManufacturerData(
                getManufacturerCompanyId(),
                manufacturerData,
                manufacturerDataMask
            )
        }

        if (serviceData != null && serviceDataUuid != null) {
            builder.setServiceData(
                ParcelUuid(serviceDataUuid),
                serviceData,
                serviceDataMask
            )
        }

        return builder.build()
    }

    internal fun getManufacturerCompanyId(): Int {
        return manufacturerCompanyId ?: 0
    }
}

/**
 * Create a [ScanFilter] using a DSL builder.
 */
fun scanFilter(block: ScanFilterBuilder.() -> Unit): ScanFilter {
    return ScanFilterBuilder().apply(block).build()
}
