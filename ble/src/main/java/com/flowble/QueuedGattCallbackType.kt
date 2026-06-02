package com.flowble

/**
 * Expected callback channel for an advanced queued GATT operation.
 *
 * Most custom operations should use [GENERIC]. Use PHY-specific callback types only
 * when working directly with Android's PHY APIs.
 */
enum class QueuedGattCallbackType {
    GENERIC,
    PHY_READ,
    PHY_UPDATE
}
