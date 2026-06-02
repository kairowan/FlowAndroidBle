package com.flowble.internal

import java.util.UUID

/**
 * Client Characteristic Configuration Descriptor (CCCD) UUID.
 * Used to enable/disable notifications and indications on a characteristic.
 */
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * Default timeout for GATT operations in milliseconds.
 */
const val DEFAULT_OPERATION_TIMEOUT_MS = 30_000L

/**
 * Default negotiated MTU before any explicit request.
 */
const val DEFAULT_MTU = 23

/**
 * Default timeout for BLE scanning in milliseconds.
 */
const val DEFAULT_SCAN_TIMEOUT_MS = 30_000L

/**
 * Buffer size for notification channels.
 */
const val NOTIFICATION_CHANNEL_BUFFER = 64

/**
 * CCCD value to enable notifications.
 */
val ENABLE_NOTIFICATION_VALUE = byteArrayOf(0x01, 0x00)

/**
 * CCCD value to enable indications.
 */
val ENABLE_INDICATION_VALUE = byteArrayOf(0x02, 0x00)

/**
 * CCCD value to disable notifications/indications.
 */
val DISABLE_NOTIFICATION_VALUE = byteArrayOf(0x00, 0x00)
