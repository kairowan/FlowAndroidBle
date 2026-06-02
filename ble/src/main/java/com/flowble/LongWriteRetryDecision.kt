package com.flowble

/**
 * Decision returned by a long write retry strategy after a batch write failure.
 */
sealed interface LongWriteRetryDecision {
    /**
     * Abort the long write and rethrow the original failure.
     */
    data object Abort : LongWriteRetryDecision

    /**
     * Retry the failed batch after an optional delay.
     */
    data class RetryAfter(val delayMs: Long = 0L) : LongWriteRetryDecision
}
