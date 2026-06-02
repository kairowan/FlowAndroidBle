package com.flowble

import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import com.flowble.internal.DEFAULT_SCAN_TIMEOUT_MS

/**
 * Immutable configuration for BLE scanning.
 *
 * Use the [Builder] or the companion [build] function to create instances.
 *
 * @property filters List of scan filters to apply. Empty list means scan for all devices.
 * @property settings Android scan settings.
 * @property timeoutMs Scan timeout in milliseconds. 0 means no timeout.
 */
data class ScannerConfig(
    val filters: List<ScanFilter> = emptyList(),
    val settings: ScanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build(),
    val timeoutMs: Long = DEFAULT_SCAN_TIMEOUT_MS
) {
    /**
     * Builder for creating [ScannerConfig] instances.
     */
    class Builder {
        private val filters = mutableListOf<ScanFilter>()
        private var settings: ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        private var timeoutMs: Long = DEFAULT_SCAN_TIMEOUT_MS

        /**
         * Add a scan filter.
         */
        fun addFilter(filter: ScanFilter): Builder {
            filters.add(filter)
            return this
        }

        /**
         * Set the scan settings.
         */
        fun setSettings(settings: ScanSettings): Builder {
            this.settings = settings
            return this
        }

        /**
         * Set the scan timeout in milliseconds. Set to 0 for no timeout.
         */
        fun setTimeoutMs(timeoutMs: Long): Builder {
            this.timeoutMs = timeoutMs
            return this
        }

        /**
         * Build the [ScannerConfig].
         */
        fun build(): ScannerConfig = ScannerConfig(
            filters = filters.toList(),
            settings = settings,
            timeoutMs = timeoutMs
        )
    }

    companion object {
        /**
         * Create a [ScannerConfig] using a builder DSL.
         */
        fun build(block: Builder.() -> Unit): ScannerConfig = Builder().apply(block).build()
    }
}
