package com.flowble

import android.util.Log

/**
 * Logger for BLE operations.
 *
 * This logger can be used to debug BLE operations and track
 * the flow of data through the library.
 *
 * Usage:
 * ```kotlin
 * BleLogger.setEnabled(true)
 * BleLogger.setLevel(BleLogger.Level.DEBUG)
 * ```
 */
object BleLogger {

    /**
     * Log level enumeration.
     */
    enum class Level(val value: Int) {
        VERBOSE(0),
        DEBUG(1),
        INFO(2),
        WARNING(3),
        ERROR(4),
        NONE(5)
    }

    private const val TAG = "FlowBLE"

    @Volatile
    private var enabled = false

    @Volatile
    private var level = Level.INFO

    private var customLogger: ((Level, String, String?) -> Unit)? = null

    /**
     * Enable or disable logging.
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    /**
     * Return whether logging is currently enabled.
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Set the minimum log level.
     */
    fun setLevel(level: Level) {
        this.level = level
    }

    /**
     * Return the currently configured minimum log level.
     */
    fun getLevel(): Level = level

    /**
     * Set a custom logger implementation.
     *
     * @param logger A function that receives the log level, message, and optional tag.
     */
    fun setCustomLogger(logger: (Level, String, String?) -> Unit) {
        this.customLogger = logger
    }

    /**
     * Remove any custom logger implementation and fall back to platform logging.
     */
    fun clearCustomLogger() {
        customLogger = null
    }

    /**
     * Log a verbose message.
     */
    fun v(message: String, tag: String? = null) {
        log(Level.VERBOSE, message, tag)
    }

    /**
     * Log a debug message.
     */
    fun d(message: String, tag: String? = null) {
        log(Level.DEBUG, message, tag)
    }

    /**
     * Log an info message.
     */
    fun i(message: String, tag: String? = null) {
        log(Level.INFO, message, tag)
    }

    /**
     * Log a warning message.
     */
    fun w(message: String, tag: String? = null) {
        log(Level.WARNING, message, tag)
    }

    /**
     * Log an error message.
     */
    fun e(message: String, throwable: Throwable? = null, tag: String? = null) {
        log(Level.ERROR, message, tag, throwable)
    }

    private fun log(level: Level, message: String, tag: String?, throwable: Throwable? = null) {
        if (!enabled || level.value < this.level.value) return

        val logTag = tag ?: TAG

        customLogger?.invoke(level, message, tag) ?: when (level) {
            Level.VERBOSE -> Log.v(logTag, message, throwable)
            Level.DEBUG -> Log.d(logTag, message, throwable)
            Level.INFO -> Log.i(logTag, message, throwable)
            Level.WARNING -> Log.w(logTag, message, throwable)
            Level.ERROR -> Log.e(logTag, message, throwable)
            Level.NONE -> { /* Do nothing */ }
        }
    }

    /**
     * Log a GATT operation.
     */
    internal fun logGattOperation(operation: String, details: String? = null) {
        d("GATT Operation: $operation${details?.let { " - $it" } ?: ""}")
    }

    /**
     * Log a connection event.
     */
    internal fun logConnectionEvent(event: String, address: String? = null) {
        i("Connection: $event${address?.let { " ($it)" } ?: ""}")
    }

    /**
     * Log a scan event.
     */
    internal fun logScanEvent(event: String, details: String? = null) {
        d("Scan: $event${details?.let { " - $it" } ?: ""}")
    }
}
