package com.flowble.model

import android.bluetooth.BluetoothDevice

/**
 * Type-safe representation of BLE bond (pairing) states.
 */
sealed interface BondState {
    /** Device is not paired. */
    data object None : BondState

    /** Pairing is in progress. */
    data object Bonding : BondState

    /** Device is paired. */
    data object Bonded : BondState

    /** Unknown bond state. */
    data object Unknown : BondState

    companion object {
        /**
         * Maps Android's [BluetoothDevice] bond state integer to [BondState].
         */
        fun fromAndroid(bondState: Int): BondState = when (bondState) {
            BluetoothDevice.BOND_NONE -> None
            BluetoothDevice.BOND_BONDING -> Bonding
            BluetoothDevice.BOND_BONDED -> Bonded
            else -> Unknown
        }
    }
}
