package com.flowble.model

/**
 * Type-safe representation of BLE connection states.
 */
sealed interface ConnectionState {
    /** Device is not connected. */
    data object Disconnected : ConnectionState

    /** Connection is in progress. */
    data object Connecting : ConnectionState

    /** Device is connected. */
    data object Connected : ConnectionState

    /** Disconnection is in progress. */
    data object Disconnecting : ConnectionState
}
