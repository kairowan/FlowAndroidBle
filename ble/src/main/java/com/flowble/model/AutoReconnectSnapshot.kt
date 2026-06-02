package com.flowble.model

/**
 * Observable snapshot of an [com.flowble.AutoReconnectSession].
 *
 * This combines the high-level reconnect lifecycle with the currently active connection state
 * plus the most recent failure and active connection snapshot seen by the session.
 */
data class AutoReconnectSnapshot(
    val state: AutoReconnectState,
    val activeConnectionState: ConnectionState?,
    val activeConnectionSnapshot: BleConnectionSnapshot?,
    val hasActiveConnection: Boolean,
    val lastError: Throwable?
)
