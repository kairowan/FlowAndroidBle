package com.flowble

/**
 * Metadata about a failed batch within a long write operation.
 */
data class LongWriteFailure(
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunk: ByteArray,
    val bytesWritten: Int,
    val totalBytes: Int,
    val attempt: Int,
    val cause: Throwable
)
