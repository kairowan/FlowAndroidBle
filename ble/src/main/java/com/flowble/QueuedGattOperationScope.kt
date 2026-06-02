package com.flowble

import android.bluetooth.BluetoothGatt

/**
 * Advanced escape hatch for running custom Android BLE operations inside the same
 * serialized GATT queue used by the library.
 *
 * Do not call other high-level [BleConnection] operations from inside a queued block,
 * because they would try to enqueue recursively. Use [gatt] and [execute] directly instead.
 */
interface QueuedGattOperationScope {

    /**
     * The underlying Android [BluetoothGatt] for the active connection.
     */
    val gatt: BluetoothGatt

    /**
     * Start an Android BLE operation and suspend until its callback completes.
     *
     * The [operation] block should initiate exactly one GATT operation and return `true`
     * when initiation succeeds.
     */
    suspend fun <T> execute(
        callbackType: QueuedGattCallbackType = QueuedGattCallbackType.GENERIC,
        operation: () -> Boolean
    ): T
}
