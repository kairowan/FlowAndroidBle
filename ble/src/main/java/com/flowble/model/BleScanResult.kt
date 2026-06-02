package com.flowble.model

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
import java.lang.reflect.Method
import java.util.UUID

/**
 * Rich scan result wrapping Android's [ScanResult].
 *
 * @property device The remote Bluetooth device found during scanning.
 * @property rssi The received signal strength indicator in dBm.
 * @property timestampNanos The timestamp when the scan result was reported.
 * @property scanRecord The parsed scan record from the advertisement data, if available.
 * @property callbackType Android scan callback type when the platform exposes it.
 * @property isConnectable Whether the advertisement is connectable when the platform exposes it.
 * @property advertisingSid Advertising SID when the platform exposes it.
 */
@SuppressLint("MissingPermission")
data class BleScanResult(
    val device: BluetoothDevice,
    val rssi: Int,
    val timestampNanos: Long,
    val scanRecord: ScanRecord?,
    val callbackType: Int? = null,
    val isConnectable: Boolean? = null,
    val advertisingSid: Int? = null
) {
    /**
     * The MAC address of the remote device.
     */
    val address: String get() = device.address

    /**
     * The device name from the scan record, if available.
     */
    val deviceName: String? get() = scanRecord?.deviceName ?: device.name

    /**
     * Raw scan record bytes from the advertisement packet, if available.
     */
    val scanRecordBytes: ByteArray? get() = scanRecord?.bytes

    /**
     * Advertising flags from the scan record, or null when unavailable.
     */
    val advertiseFlags: Int?
        get() = scanRecord?.advertiseFlags?.takeIf { it >= 0 }

    /**
     * Advertised Tx power level, or null when the advertisement did not include one.
     */
    val txPowerLevel: Int?
        get() = scanRecord?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }

    /**
     * Advertised service UUIDs parsed from the scan record.
     */
    val advertisedServiceUuids: List<UUID>
        get() = scanRecord?.serviceUuids?.map { parcelUuid -> parcelUuid.uuid } ?: emptyList()

    /**
     * Return service data for one advertised service UUID, if present.
     */
    fun getServiceData(serviceUuid: UUID): ByteArray? {
        return scanRecord?.getServiceData(ParcelUuid(serviceUuid))
    }

    /**
     * Return manufacturer specific data for one company identifier, if present.
     */
    fun getManufacturerSpecificData(companyId: Int): ByteArray? {
        return scanRecord?.getManufacturerSpecificData(companyId)
    }

    companion object {
        /**
         * Creates a [BleScanResult] from an Android [ScanResult].
         */
        internal fun fromAndroid(scanResult: ScanResult): BleScanResult = BleScanResult(
            device = scanResult.device,
            rssi = scanResult.rssi,
            timestampNanos = scanResult.timestampNanos,
            scanRecord = scanResult.scanRecord,
            callbackType = invokeInt(scanResult, callbackTypeMethod),
            isConnectable = invokeBoolean(scanResult, isConnectableMethod),
            advertisingSid = invokeInt(scanResult, advertisingSidMethod)
        )

        private val callbackTypeMethod = scanResultMethod("getCallbackType")
        private val isConnectableMethod = scanResultMethod("isConnectable")
        private val advertisingSidMethod = scanResultMethod("getAdvertisingSid")

        private fun scanResultMethod(name: String): Method? {
            return runCatching { ScanResult::class.java.getMethod(name) }.getOrNull()
        }

        private fun invokeInt(scanResult: ScanResult, method: Method?): Int? {
            return (method?.invoke(scanResult) as? Int)
        }

        private fun invokeBoolean(scanResult: ScanResult, method: Method?): Boolean? {
            return (method?.invoke(scanResult) as? Boolean)
        }
    }
}
