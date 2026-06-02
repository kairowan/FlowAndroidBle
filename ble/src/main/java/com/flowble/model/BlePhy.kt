package com.flowble.model

/**
 * Current transmit and receive PHY of a BLE connection.
 */
data class BlePhy(
    val txPhy: PhyType,
    val rxPhy: PhyType
)
