package com.flowble.model

/**
 * Controls whether characteristic updates are configured as notifications or indications.
 */
enum class CharacteristicObservationMode {
    /**
     * Prefer notifications when available, otherwise fall back to indications.
     */
    AUTO,

    /**
     * Require notifications.
     */
    NOTIFICATION,

    /**
     * Require indications.
     */
    INDICATION
}
