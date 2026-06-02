package com.flowble.internal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import com.flowble.BleConnection
import com.flowble.FlowBleDevice
import com.flowble.model.ConnectionConfig
import com.flowble.model.ConnectionState
import kotlinx.coroutines.flow.StateFlow

internal class FlowBleDeviceImpl(
    override val address: String,
    override val connectionState: StateFlow<ConnectionState>,
    private val bluetoothDeviceProvider: () -> BluetoothDevice,
    private val connector: suspend (ConnectionConfig) -> BleConnection
) : FlowBleDevice {

    @SuppressLint("MissingPermission")
    override fun getName(): String? = getBluetoothDevice().name

    override fun getBluetoothDevice(): BluetoothDevice = bluetoothDeviceProvider()

    override suspend fun connect(config: ConnectionConfig): BleConnection = connector(config)
}
