package com.flowble.model

/**
 * Combined observable state for one stable [com.flowble.FlowBleDevice] handle.
 */
data class BleDeviceSnapshot(
    val address: String,
    val name: String?,
    val connectionState: ConnectionState
)
