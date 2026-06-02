package com.flowble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.flowble.exception.BleException
import com.flowble.internal.getBluetoothDeviceExtra
import com.flowble.model.BondState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Manages BLE device bonding (pairing) operations.
 *
 * This class provides methods for creating bonds, removing bonds,
 * and monitoring bond state changes.
 */
class BondingManager(private val context: Context) {

    /**
     * Create a bond with a Bluetooth device.
     *
     * This method initiates pairing with the device and suspends until
     * the bonding process completes (either successfully or with failure).
     *
     * @param device The device to bond with.
     * @return The final [BondState].
     * @throws BleException if bonding fails.
     */
    @SuppressLint("MissingPermission")
    suspend fun createBond(device: BluetoothDevice): BondState {
        // Check if already bonded
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            return BondState.Bonded
        }

        // Start bonding
        val success = device.createBond()
        if (!success) {
            throw BleException("Failed to initiate bonding")
        }

        // Wait for bonding to complete
        return withTimeout(30_000L) {
            observeBondState(device)
                .filter { it != BondState.Bonding }
                .first()
        }
    }

    /**
     * Remove bond from a Bluetooth device.
     *
     * @param device The device to remove bond from.
     * @return true if bond removal was initiated successfully.
     */
    @SuppressLint("MissingPermission")
    suspend fun removeBond(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device) as Boolean
        } catch (e: Exception) {
            throw BleException("Failed to remove bond: ${e.message}", e)
        }
    }

    /**
     * Observe bond state changes for a device.
     *
     * @param device The device to monitor.
     * @return A Flow that emits [BondState] changes.
     */
    @SuppressLint("MissingPermission")
    fun observeBondState(device: BluetoothDevice): Flow<BondState> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    val bondDevice = intent.getBluetoothDeviceExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (bondDevice?.address == device.address) {
                        val bondState = intent.getIntExtra(
                            BluetoothDevice.EXTRA_BOND_STATE,
                            BluetoothDevice.BOND_NONE
                        )
                        trySend(BondState.fromAndroid(bondState))
                    }
                }
            }
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        context.registerReceiver(receiver, filter)

        // Emit current state
        trySend(BondState.fromAndroid(device.bondState))

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }

    /**
     * Check if a device is bonded.
     */
    @SuppressLint("MissingPermission")
    fun isBonded(device: BluetoothDevice): Boolean {
        return device.bondState == BluetoothDevice.BOND_BONDED
    }

    /**
     * Get the current bond state of a device.
     */
    @SuppressLint("MissingPermission")
    fun getBondState(device: BluetoothDevice): BondState {
        return BondState.fromAndroid(device.bondState)
    }
}
