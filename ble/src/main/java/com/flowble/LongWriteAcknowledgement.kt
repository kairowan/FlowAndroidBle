package com.flowble

/**
 * Metadata about a completed batch within a long write operation.
 */
data class LongWriteAcknowledgement(
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunk: ByteArray,
    val bytesWritten: Int,
    val totalBytes: Int,
    val hasMoreChunks: Boolean
)
