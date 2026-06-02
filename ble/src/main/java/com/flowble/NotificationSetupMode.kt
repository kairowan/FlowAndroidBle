package com.flowble

/**
 * Controls how notification/indication setup is performed.
 */
enum class NotificationSetupMode {
    /**
     * Enable local notifications and wait for the CCCD write to succeed before exposing the stream.
     */
    DEFAULT,

    /**
     * Compatibility mode for peripherals that do not expose a CCCD descriptor.
     * Only local notification registration is performed.
     */
    COMPAT,

    /**
     * Expose the stream as soon as local notification registration succeeds, then write the CCCD
     * asynchronously. If the descriptor write later fails, the observation is terminated with that error.
     */
    QUICK_SETUP
}
