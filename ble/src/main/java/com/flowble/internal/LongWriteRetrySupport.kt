package com.flowble.internal

import com.flowble.LongWriteFailure
import com.flowble.LongWriteRetryDecision
import com.flowble.LongWriteRetryStrategy
import kotlinx.coroutines.delay

internal suspend fun retryLongWriteChunkIfNeeded(
    chunkIndex: Int,
    totalChunks: Int,
    chunk: ByteArray,
    bytesWritten: Int,
    totalBytes: Int,
    retryStrategy: LongWriteRetryStrategy?,
    write: suspend () -> Unit
) {
    var attempt = 1
    while (true) {
        try {
            write()
            return
        } catch (cause: Throwable) {
            val strategy = retryStrategy ?: throw cause
            when (val decision = strategy(
                LongWriteFailure(
                    chunkIndex = chunkIndex,
                    totalChunks = totalChunks,
                    chunk = chunk,
                    bytesWritten = bytesWritten,
                    totalBytes = totalBytes,
                    attempt = attempt,
                    cause = cause
                )
            )) {
                LongWriteRetryDecision.Abort -> throw cause
                is LongWriteRetryDecision.RetryAfter -> {
                    require(decision.delayMs >= 0) { "Retry delay must be >= 0" }
                    if (decision.delayMs > 0) {
                        delay(decision.delayMs)
                    }
                    attempt += 1
                }
            }
        }
    }
}
