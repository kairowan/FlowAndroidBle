package com.flowble.internal

import com.flowble.model.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal class DeviceConnectionRegistry {

    private val states = ConcurrentHashMap<String, MutableStateFlow<ConnectionState>>()
    private val activeConnections = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun stateFlow(address: String): StateFlow<ConnectionState> = mutableState(address).asStateFlow()

    fun currentState(address: String): ConnectionState = mutableState(address).value

    fun updateState(address: String, state: ConnectionState) {
        mutableState(address).value = state
    }

    fun tryAcquire(address: String): Boolean = activeConnections.add(address)

    fun release(address: String) {
        activeConnections.remove(address)
        updateState(address, ConnectionState.Disconnected)
    }

    fun isActive(address: String): Boolean = activeConnections.contains(address)

    private fun mutableState(address: String): MutableStateFlow<ConnectionState> {
        return states.getOrPutConcurrent(address) {
            MutableStateFlow(ConnectionState.Disconnected)
        }
    }
}
