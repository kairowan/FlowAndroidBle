package com.flowble

import com.flowble.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Supervises a BLE connection and detects connection loss.
 *
 * This class monitors the connection state and application-level activity to detect when
 * a connection is lost or degraded. When loss is detected it invokes [onConnectionLost],
 * which can be used to trigger reconnection or other recovery logic.
 *
 * @property connectionState The connection state flow to monitor.
 * @property onConnectionLost Callback invoked when connection is lost.
 * @property checkInterval How often to check connection health in milliseconds.
 * @property maxSilentDuration Maximum time without data before considering connection lost.
 */
class ConnectionSupervisor(
    private val connectionState: StateFlow<ConnectionState>,
    private val onConnectionLost: suspend () -> Unit,
    private val checkInterval: Long = 5000L,
    private val maxSilentDuration: Long = 30000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private var supervisorJob: Job? = null
    private var lastDataReceivedTime = nowMs()
    private var hasConnectedSession = false
    private var lossHandledForSession = false

    /**
     * Start supervising the connection.
     */
    fun start() {
        stop()

        supervisorJob = scope.launch {
            connectionState.collectLatest { state ->
                when (state) {
                    ConnectionState.Connected -> {
                        hasConnectedSession = true
                        lossHandledForSession = false
                        lastDataReceivedTime = nowMs()
                        monitorConnection()
                    }

                    ConnectionState.Disconnected -> {
                        if (hasConnectedSession && !lossHandledForSession) {
                            lossHandledForSession = true
                            onConnectionLost()
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    /**
     * Stop supervising the connection.
     */
    fun stop() {
        supervisorJob?.cancel()
        supervisorJob = null
    }

    /**
     * Notify that data was received on the connection.
     * This resets the silent duration timer.
     */
    fun notifyDataReceived() {
        lastDataReceivedTime = nowMs()
    }

    /**
     * Monitor the connection for health.
     */
    private suspend fun monitorConnection() {
        while (connectionState.value == ConnectionState.Connected) {
            delay(checkInterval)

            val silentDuration = nowMs() - lastDataReceivedTime
            if (silentDuration > maxSilentDuration && !lossHandledForSession) {
                // Connection appears to be silent for too long
                lossHandledForSession = true
                onConnectionLost()
                break
            }
        }
    }
}

/**
 * Extension function to add connection supervision to a [FlowBleClient].
 */
fun FlowBleClient.createConnectionSupervisor(
    connectionState: StateFlow<ConnectionState>,
    onConnectionLost: suspend () -> Unit,
    checkInterval: Long = 5000L,
    maxSilentDuration: Long = 30000L
): ConnectionSupervisor {
    return ConnectionSupervisor(
        connectionState = connectionState,
        onConnectionLost = onConnectionLost,
        checkInterval = checkInterval,
        maxSilentDuration = maxSilentDuration
    )
}
