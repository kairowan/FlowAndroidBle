package com.flowble.internal

import android.bluetooth.BluetoothGatt
import com.flowble.QueuedGattCallbackType
import com.flowble.QueuedGattOperationScope

internal class QueuedGattOperationScopeImpl(
    override val gatt: BluetoothGatt,
    private val executeOperation: suspend (QueuedGattCallbackType, () -> Boolean) -> Any?
) : QueuedGattOperationScope {

    override suspend fun <T> execute(
        callbackType: QueuedGattCallbackType,
        operation: () -> Boolean
    ): T {
        @Suppress("UNCHECKED_CAST")
        return executeOperation(callbackType, operation) as T
    }
}
