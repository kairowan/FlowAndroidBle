package com.flowble

/**
 * Suspendable hook invoked when a long write batch fails.
 *
 * Return [LongWriteRetryDecision.RetryAfter] to retry the failed batch, or
 * [LongWriteRetryDecision.Abort] to stop and rethrow the original failure.
 */
typealias LongWriteRetryStrategy = suspend (LongWriteFailure) -> LongWriteRetryDecision
