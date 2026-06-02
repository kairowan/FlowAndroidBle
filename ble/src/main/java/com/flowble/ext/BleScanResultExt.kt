package com.flowble.ext

import com.flowble.FlowBleClient
import com.flowble.FlowBleDevice
import com.flowble.model.BleScanResult

/**
 * Convert a scan result into a stable device handle.
 */
fun BleScanResult.toFlowBleDevice(client: FlowBleClient): FlowBleDevice {
    return client.getBleDevice(address)
}

/**
 * Convenience alias.
 */
fun BleScanResult.getBleDevice(client: FlowBleClient): FlowBleDevice {
    return toFlowBleDevice(client)
}
