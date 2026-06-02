package com.flowble

import android.annotation.SuppressLint
import android.content.Context
import com.flowble.model.BleScanResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Batch BLE scanner that collects multiple scan results before emitting.
 *
 * This is useful for reducing the number of emissions when scanning in
 * environments with many BLE devices.
 *
 * @property context Android context.
 * @property batchInterval How often to emit batches in milliseconds.
 * @property maxBatchSize Maximum number of results per batch.
 */
class BatchScanner(
    private val context: Context,
    private val batchInterval: Long = 1000L,
    private val maxBatchSize: Int = 100
) {
    private val scanner = BleScanner(context)

    /**
     * Scan for devices and emit results in batches.
     *
     * @param config Scan configuration.
     * @return A Flow that emits lists of [BleScanResult].
     */
    @SuppressLint("MissingPermission")
    fun scanBatch(config: ScannerConfig = ScannerConfig()): Flow<List<BleScanResult>> = channelFlow {
        val batchChannel = Channel<BleScanResult>(Channel.UNLIMITED)

        // Launch a coroutine to collect scan results
        launch {
            scanner.scan(config).collect { result ->
                batchChannel.send(result)
            }
            batchChannel.close()
        }

        // Batch and emit results
        val batch = mutableListOf<BleScanResult>()
        var lastEmitTime = System.currentTimeMillis()

        // Timer coroutine to emit batches periodically
        val timerJob = launch {
            while (true) {
                delay(batchInterval)
                if (batch.isNotEmpty()) {
                    send(batch.toList())
                    batch.clear()
                    lastEmitTime = System.currentTimeMillis()
                }
            }
        }

        // Collect and batch results
        for (result in batchChannel) {
            batch.add(result)

            val now = System.currentTimeMillis()
            if (now - lastEmitTime >= batchInterval || batch.size >= maxBatchSize) {
                send(batch.toList())
                batch.clear()
                lastEmitTime = now
            }
        }

        // Emit remaining items
        if (batch.isNotEmpty()) {
            send(batch.toList())
        }

        timerJob.cancel()
    }

    /**
     * Scan and deduplicate results by device address.
     *
     * Only emits a device once, keeping the most recent scan result.
     */
    @SuppressLint("MissingPermission")
    fun scanDistinct(config: ScannerConfig = ScannerConfig()): Flow<BleScanResult> = channelFlow {
        val seenAddresses = mutableSetOf<String>()

        scanner.scan(config).collect { result ->
            if (!seenAddresses.contains(result.address)) {
                seenAddresses.add(result.address)
                send(result)
            }
        }
    }
}
