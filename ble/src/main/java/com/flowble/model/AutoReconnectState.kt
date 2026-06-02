package com.flowble.model

/**
 * Lifecycle state for an [com.flowble.AutoReconnectSession].
 */
sealed interface AutoReconnectState {
    /**
     * The session has been created but not started yet.
     */
    data object Idle : AutoReconnectState

    /**
     * A connection attempt is currently in progress.
     *
     * @property attempt 1-based attempt number within the current reconnect cycle.
     */
    data class Connecting(val attempt: Int) : AutoReconnectState

    /**
     * The low-level connection succeeded and the session is restoring higher-level state,
     * such as service discovery or user-provided setup hooks.
     *
     * @property attempt 1-based attempt number within the current reconnect cycle.
     */
    data class Recovering(val attempt: Int) : AutoReconnectState

    /**
     * A connection is currently active.
     */
    data object Connected : AutoReconnectState

    /**
     * The session is waiting before the next connection attempt.
     *
     * @property attempt The upcoming 1-based attempt number.
     * @property delayMs Delay before retrying.
     * @property lastError The most recent connection failure, if any.
     */
    data class WaitingToRetry(
        val attempt: Int,
        val delayMs: Long,
        val lastError: Throwable?
    ) : AutoReconnectState

    /**
     * Reconnection stopped after exhausting the configured retry budget.
     *
     * @property attempts Number of failed attempts in the last reconnect cycle.
     * @property lastError The last error that caused the session to fail.
     */
    data class Failed(
        val attempts: Int,
        val lastError: Throwable
    ) : AutoReconnectState

    /**
     * The session has been stopped explicitly.
     */
    data object Stopped : AutoReconnectState
}
