package com.flowble.model

/**
 * Preferred PHY selection for a BLE connection.
 *
 * Empty sets mean "no specific preference" for that direction.
 */
data class PhyRequest(
    val txPhys: Set<PhyType>,
    val rxPhys: Set<PhyType> = txPhys,
    val option: PhyOption = PhyOption.NO_PREFERRED
)
