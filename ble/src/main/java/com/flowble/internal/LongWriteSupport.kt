package com.flowble.internal

import com.flowble.LongWriteAckStrategy
import com.flowble.LongWriteAcknowledgement
import com.flowble.LongWriteRetryStrategy
import kotlinx.coroutines.delay

internal const val GATT_WRITE_OVERHEAD = 3

internal fun splitForLongWrite(payload: ByteArray, maxChunkSize: Int): List<ByteArray> {
    require(maxChunkSize > 0) { "maxChunkSize must be > 0" }
    if (payload.isEmpty()) {
        return listOf(ByteArray(0))
    }

    val chunks = ArrayList<ByteArray>((payload.size + maxChunkSize - 1) / maxChunkSize)
    var offset = 0
    while (offset < payload.size) {
        val end = minOf(offset + maxChunkSize, payload.size)
        chunks += payload.copyOfRange(offset, end)
        offset = end
    }
    return chunks
}

internal suspend fun executeLongWriteChunks(
    payload: ByteArray,
    maxChunkSize: Int,
    interChunkDelayMs: Long,
    ackStrategy: LongWriteAckStrategy?,
    retryStrategy: LongWriteRetryStrategy?,
    writeChunk: suspend (chunk: ByteArray) -> Unit
): ByteArray {
    require(maxChunkSize > 0) { "maxChunkSize must be > 0" }
    require(interChunkDelayMs >= 0) { "interChunkDelayMs must be >= 0" }

    val chunks = splitForLongWrite(payload, maxChunkSize)
    var bytesWritten = 0
    chunks.forEachIndexed { index, chunk ->
        retryLongWriteChunkIfNeeded(
            chunkIndex = index,
            totalChunks = chunks.size,
            chunk = chunk,
            bytesWritten = bytesWritten,
            totalBytes = payload.size,
            retryStrategy = retryStrategy
        ) {
            writeChunk(chunk)
        }
        bytesWritten += chunk.size

        val acknowledgement = LongWriteAcknowledgement(
            chunkIndex = index,
            totalChunks = chunks.size,
            chunk = chunk,
            bytesWritten = bytesWritten,
            totalBytes = payload.size,
            hasMoreChunks = index < chunks.lastIndex
        )

        ackStrategy?.invoke(acknowledgement)
        if (interChunkDelayMs > 0 && acknowledgement.hasMoreChunks) {
            delay(interChunkDelayMs)
        }
    }

    return payload
}
