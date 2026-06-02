package com.flowble.model

import com.flowble.internal.DEFAULT_OPERATION_TIMEOUT_MS

/**
 * Configuration for BLE connection behavior.
 *
 * @property autoConnect Whether to wait for the device to become available before connecting.
 *   This does not automatically reconnect after a later disconnect.
 * @property connectionTimeout Connection timeout in milliseconds. 0 means wait indefinitely.
 * @property operationTimeout Default timeout for GATT operations in milliseconds.
 * @property retryCount Number of times to retry failed operations. 0 means no retry.
 * @property retryDelay Delay between retries in milliseconds.
 * @property supervisionTimeout Connection supervision timeout in milliseconds.
 *   If no data is received within this time, the connection is considered lost.
 *   0 means use the device default.
 * @property preferredPhy Preferred PHY to request immediately after the connection is established.
 *   Null means leave PHY unchanged. Requires Android 8.0 (API 26) or newer.
 */
data class ConnectionConfig(
    val autoConnect: Boolean = false,
    val connectionTimeout: Long = DEFAULT_OPERATION_TIMEOUT_MS,
    val operationTimeout: Long = DEFAULT_OPERATION_TIMEOUT_MS,
    val retryCount: Int = 0,
    val retryDelay: Long = 1000L,
    val supervisionTimeout: Long = 0L,
    val preferredPhy: PhyRequest? = null
) {
    /**
     * Builder for creating [ConnectionConfig] instances.
     */
    class Builder {
        private var autoConnect: Boolean = false
        private var connectionTimeout: Long = DEFAULT_OPERATION_TIMEOUT_MS
        private var operationTimeout: Long = DEFAULT_OPERATION_TIMEOUT_MS
        private var retryCount: Int = 0
        private var retryDelay: Long = 1000L
        private var supervisionTimeout: Long = 0L
        private var preferredPhy: PhyRequest? = null

        fun setAutoConnect(autoConnect: Boolean): Builder {
            this.autoConnect = autoConnect
            return this
        }

        fun setConnectionTimeout(timeoutMs: Long): Builder {
            this.connectionTimeout = timeoutMs
            return this
        }

        fun setOperationTimeout(timeoutMs: Long): Builder {
            this.operationTimeout = timeoutMs
            return this
        }

        fun setRetryCount(count: Int): Builder {
            this.retryCount = count
            return this
        }

        fun setRetryDelay(delayMs: Long): Builder {
            this.retryDelay = delayMs
            return this
        }

        fun setSupervisionTimeout(timeoutMs: Long): Builder {
            this.supervisionTimeout = timeoutMs
            return this
        }

        fun setPreferredPhy(request: PhyRequest?): Builder {
            this.preferredPhy = request
            return this
        }

        fun setPreferredPhy(
            txPhy: PhyType,
            rxPhy: PhyType = txPhy,
            option: PhyOption = PhyOption.NO_PREFERRED
        ): Builder {
            this.preferredPhy = PhyRequest(
                txPhys = setOf(txPhy),
                rxPhys = setOf(rxPhy),
                option = option
            )
            return this
        }

        fun build(): ConnectionConfig = ConnectionConfig(
            autoConnect = autoConnect,
            connectionTimeout = connectionTimeout,
            operationTimeout = operationTimeout,
            retryCount = retryCount,
            retryDelay = retryDelay,
            supervisionTimeout = supervisionTimeout,
            preferredPhy = preferredPhy
        )
    }

    companion object {
        fun build(block: Builder.() -> Unit): ConnectionConfig = Builder().apply(block).build()

        /**
         * Default configuration for quick connections.
         */
        val QUICK = ConnectionConfig(
            autoConnect = false,
            connectionTimeout = 10_000L,
            operationTimeout = 10_000L,
            retryCount = 1,
            preferredPhy = null
        )

        /**
         * Configuration for waiting on background availability with autoConnect enabled.
         */
        val AUTO_CONNECT = ConnectionConfig(
            autoConnect = true,
            connectionTimeout = 0L,
            operationTimeout = 30_000L,
            retryCount = 3,
            retryDelay = 2000L,
            preferredPhy = null
        )
    }
}
