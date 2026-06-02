package com.flowble.model

/**
 * Combined observable state for one active [com.flowble.BleConnection].
 *
 * This is a Flow-friendly view over the connection's current low-level state and the latest
 * discovered service tree.
 */
data class BleConnectionSnapshot(
    val connectionState: ConnectionState,
    val mtu: Int,
    val phy: BlePhy?,
    val services: List<BleService>?
)
