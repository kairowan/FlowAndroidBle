package com.flowble.exception

import java.util.UUID

/**
 * Base exception for all BLE-related errors.
 */
open class BleException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Base type for lookup and UUID disambiguation failures in discovered GATT metadata.
 */
open class BleLookupException(message: String) : BleException(message)

/**
 * Mirrors the main Android GATT callback families that callers usually care about when handling
 * BLE errors.
 */
enum class BleGattOperationType {
    CONNECTION_STATE,
    SERVICE_DISCOVERY,
    CHARACTERISTIC_READ,
    CHARACTERISTIC_WRITE,
    CHARACTERISTIC_LONG_WRITE,
    CHARACTERISTIC_CHANGED,
    DESCRIPTOR_READ,
    DESCRIPTOR_WRITE,
    READ_RSSI,
    MTU_CHANGED,
    PHY_READ,
    PHY_UPDATE,
    CONNECTION_PRIORITY_CHANGE,
    CUSTOM
}

/**
 * Rich GATT failure carrying the platform status code and the operation family that failed.
 */
open class BleGattException(
    val status: Int,
    val operationType: BleGattOperationType,
    val macAddress: String? = null,
    cause: Throwable? = null
) : BleException(
    createGattExceptionMessage(
        status = status,
        operationType = operationType,
        macAddress = macAddress
    ),
    cause
)

/**
 * Backward-compatible alias for older call sites and tests.
 */
class GattException(
    status: Int,
    operationType: BleGattOperationType = BleGattOperationType.CUSTOM,
    macAddress: String? = null,
    cause: Throwable? = null
) : BleGattException(
    status = status,
    operationType = operationType,
    macAddress = macAddress,
    cause = cause
)

/**
 * A GATT operation started but the expected callback did not arrive before the timeout expired.
 */
class BleGattCallbackTimeoutException(
    val timeoutMs: Long,
    operationType: BleGattOperationType,
    macAddress: String? = null,
    cause: Throwable? = null
) : TimeoutException(
    createGattTimeoutMessage(
        timeoutMs = timeoutMs,
        operationType = operationType,
        macAddress = macAddress
    ),
    cause
)

/**
 * A GATT operation could not be initiated because the Android API returned `false`.
 */
open class BleGattCannotStartException(
    val operationType: BleGattOperationType,
    val macAddress: String? = null,
    cause: Throwable? = null
) : OperationFailedException(
    createGattCannotStartMessage(
        operationType = operationType,
        macAddress = macAddress
    ),
    cause
)

/**
 * A disconnect happened either during connection establishment or while a connection was active.
 */
class BleDisconnectedException(
    val deviceAddress: String?,
    val status: Int = UNKNOWN_STATUS,
    cause: Throwable? = null
) : ConnectionException(
    createDisconnectedMessage(
        deviceAddress = deviceAddress,
        status = status
    ),
    cause
) {
    companion object {
        const val UNKNOWN_STATUS = -1
    }
}

/**
 * BLE scan failed with a typed reason.
 */
open class BleScanException(
    val reason: Int,
    cause: Throwable? = null
) : BleException(createScanExceptionMessage(reason), cause) {
    companion object {
        const val BLUETOOTH_CANNOT_START = 0
        const val BLUETOOTH_DISABLED = 1
        const val BLUETOOTH_NOT_AVAILABLE = 2
        const val LOCATION_PERMISSION_MISSING = 3
        const val LOCATION_SERVICES_DISABLED = 4
        const val SCAN_FAILED_ALREADY_STARTED = 5
        const val SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 6
        const val SCAN_FAILED_INTERNAL_ERROR = 7
        const val SCAN_FAILED_FEATURE_UNSUPPORTED = 8
        const val SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES = 9
        const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 10
        const val UNKNOWN_ERROR_CODE = Int.MAX_VALUE
    }
}

/**
 * Backward-compatible alias for the old name used by the library.
 */
class ScanFailedException(
    val errorCode: Int
) : BleScanException(scanCallbackErrorToReason(errorCode))

/**
 * Connection-related error.
 */
open class ConnectionException(message: String, cause: Throwable? = null) : BleException(message, cause)

/**
 * Attempted to establish a second connection to the same device while one is already active
 * or still being established.
 */
class AlreadyConnectedException(address: String) : BleException(
    "A connection to device $address is already active or in progress"
)

/**
 * Backward-compatible generic timeout exception. Prefer [BleGattCallbackTimeoutException] when
 * an operation-specific timeout is available.
 */
open class TimeoutException(message: String, cause: Throwable? = null) : BleException(message, cause)

/**
 * Attempted an operation on a device that is not connected.
 */
class NotConnectedException : BleException("Device is not connected")

/**
 * Backward-compatible generic initiation failure. Prefer [BleGattCannotStartException] when
 * an operation-specific failure is available.
 */
open class OperationFailedException(message: String, cause: Throwable? = null) : BleException(message, cause)

class BleServiceNotFoundException(
    val serviceUuid: UUID,
    message: String = "Service not found: $serviceUuid"
) : BleLookupException(message)

class BleServiceConflictException(
    val serviceUuid: UUID,
    message: String
) : BleLookupException(message)

class BleCharacteristicNotFoundException(
    val characteristicUuid: UUID,
    val serviceUuid: UUID? = null,
    message: String = serviceUuid?.let { service ->
        "Characteristic not found: service=$service characteristic=$characteristicUuid"
    } ?: "Characteristic not found: $characteristicUuid"
) : BleLookupException(message)

class BleCharacteristicConflictException(
    val characteristicUuid: UUID,
    val serviceUuid: UUID? = null,
    message: String
) : BleLookupException(message)

class BleDescriptorNotFoundException(
    val descriptorUuid: UUID,
    val characteristicUuid: UUID,
    val serviceUuid: UUID,
    message: String = "Descriptor not found: service=$serviceUuid characteristic=$characteristicUuid descriptor=$descriptorUuid"
) : BleLookupException(message)

class BleDescriptorConflictException(
    val descriptorUuid: UUID,
    val characteristicUuid: UUID,
    val serviceUuid: UUID,
    message: String
) : BleLookupException(message)

private fun createGattExceptionMessage(
    status: Int,
    operationType: BleGattOperationType,
    macAddress: String?
): String {
    val macPart = macAddress?.let { " from MAC address $it" } ?: ""
    return "GATT exception$macPart, status $status (${gattStatusName(status)}), type $operationType"
}

private fun createGattTimeoutMessage(
    timeoutMs: Long,
    operationType: BleGattOperationType,
    macAddress: String?
): String {
    val macPart = macAddress?.let { " from MAC address $it" } ?: ""
    return "GATT callback timeout$macPart after ${timeoutMs}ms, type $operationType"
}

private fun createGattCannotStartMessage(
    operationType: BleGattOperationType,
    macAddress: String?
): String {
    val macPart = macAddress?.let { " from MAC address $it" } ?: ""
    return "GATT operation could not start$macPart, type $operationType"
}

private fun createDisconnectedMessage(
    deviceAddress: String?,
    status: Int
): String {
    val target = deviceAddress ?: "unknown device"
    return if (status == BleDisconnectedException.UNKNOWN_STATUS) {
        "Disconnected from $target"
    } else {
        "Disconnected from $target with status $status (${gattStatusName(status)})"
    }
}

private fun createScanExceptionMessage(reason: Int): String {
    return "${scanReasonName(reason)} (code $reason)"
}

private fun scanCallbackErrorToReason(errorCode: Int): Int = when (errorCode) {
    1 -> BleScanException.SCAN_FAILED_ALREADY_STARTED
    2 -> BleScanException.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED
    3 -> BleScanException.SCAN_FAILED_INTERNAL_ERROR
    4 -> BleScanException.SCAN_FAILED_FEATURE_UNSUPPORTED
    5 -> BleScanException.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES
    6 -> BleScanException.SCAN_FAILED_SCANNING_TOO_FREQUENTLY
    else -> BleScanException.UNKNOWN_ERROR_CODE
}

/**
 * Maps GATT status codes to human-readable names.
 */
private fun gattStatusName(status: Int): String = when (status) {
    -1 -> "UNKNOWN_STATUS"
    0 -> "GATT_SUCCESS"
    1 -> "GATT_INVALID_HANDLE"
    2 -> "GATT_READ_NOT_PERMIT"
    3 -> "GATT_WRITE_NOT_PERMIT"
    4 -> "GATT_INVALID_PDU"
    5 -> "GATT_INSUFFICIENT_AUTHENTICATION"
    6 -> "GATT_REQ_NOT_SUPPORTED"
    7 -> "GATT_INVALID_OFFSET"
    8 -> "GATT_INSUFFICIENT_AUTHORIZATION"
    9 -> "GATT_PREPARE_QUEUE_FULL"
    10 -> "GATT_NOT_FOUND"
    11 -> "GATT_NOT_LONG"
    12 -> "GATT_INSUFFICIENT_KEY_SIZE"
    13 -> "GATT_INVALID_ATTRIBUTE_LENGTH"
    14 -> "GATT_ERR_UNLIKELY"
    15 -> "GATT_INSUFFICIENT_ENCRYPTION"
    16 -> "GATT_UNSUPPORT_GRP_TYPE"
    17 -> "GATT_INSUFFICIENT_RESOURCES"
    128 -> "GATT_FAILURE"
    133 -> "GATT_ERROR"
    257 -> "GATT_CONN_L2C_FAILURE"
    else -> "UNKNOWN($status)"
}

/**
 * Maps scan reason codes to human-readable names.
 */
private fun scanReasonName(reason: Int): String = when (reason) {
    BleScanException.BLUETOOTH_CANNOT_START -> "BLUETOOTH_CANNOT_START"
    BleScanException.BLUETOOTH_DISABLED -> "BLUETOOTH_DISABLED"
    BleScanException.BLUETOOTH_NOT_AVAILABLE -> "BLUETOOTH_NOT_AVAILABLE"
    BleScanException.LOCATION_PERMISSION_MISSING -> "LOCATION_PERMISSION_MISSING"
    BleScanException.LOCATION_SERVICES_DISABLED -> "LOCATION_SERVICES_DISABLED"
    BleScanException.SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED"
    BleScanException.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
    BleScanException.SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR"
    BleScanException.SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
    BleScanException.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES"
    BleScanException.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "SCAN_FAILED_SCANNING_TOO_FREQUENTLY"
    BleScanException.UNKNOWN_ERROR_CODE -> "UNKNOWN_ERROR_CODE"
    else -> "UNKNOWN($reason)"
}
