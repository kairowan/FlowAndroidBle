package com.flowble.model

/**
 * Priority levels for BLE operations.
 *
 * Higher priority operations will be executed before lower priority ones
 * when multiple operations are queued.
 */
enum class OperationPriority(val value: Int) {
    /** Low priority - used for background operations. */
    LOW(0),

    /** Normal priority - default for most operations. */
    NORMAL(1),

    /** High priority - used for time-sensitive operations. */
    HIGH(2),

    /** Critical priority - used for operations that must execute immediately. */
    CRITICAL(3)
}

/**
 * Configuration for a BLE operation.
 *
 * @property priority The priority of this operation.
 * @property timeout Timeout for this specific operation in milliseconds. 0 means use default.
 * @property retryCount Number of times to retry this operation on failure. -1 means use default.
 */
data class OperationConfig(
    val priority: OperationPriority = OperationPriority.NORMAL,
    val timeout: Long = 0L,
    val retryCount: Int = -1
) {
    companion object {
        val DEFAULT = OperationConfig()

        fun highPriority() = OperationConfig(priority = OperationPriority.HIGH)

        fun critical() = OperationConfig(priority = OperationPriority.CRITICAL)

        fun withTimeout(timeoutMs: Long) = OperationConfig(timeout = timeoutMs)

        fun withRetry(count: Int) = OperationConfig(retryCount = count)
    }
}
