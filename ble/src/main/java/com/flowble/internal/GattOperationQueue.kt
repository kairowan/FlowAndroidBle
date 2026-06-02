package com.flowble.internal

import com.flowble.model.OperationConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.PriorityBlockingQueue

internal class GattOperationQueue(
    private val defaultTimeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS,
    private val defaultRetryCount: Int = 0,
    private val defaultRetryDelayMs: Long = DEFAULT_RETRY_DELAY
) {
    private val mutex = Mutex()
    private val queue = PriorityBlockingQueue<QueuedOperation>(11, compareByDescending { it.config.priority.value })
    @Volatile private var isProcessing = false

    suspend fun <T> enqueue(
        config: OperationConfig = OperationConfig.DEFAULT,
        operation: suspend (timeoutMs: Long) -> T
    ): T {
        val deferred = CompletableDeferred<Any>()
        @Suppress("UNCHECKED_CAST")
        val op: suspend (Long) -> Any = operation as suspend (Long) -> Any
        queue.offer(QueuedOperation(config, deferred, op))
        processQueue()
        @Suppress("UNCHECKED_CAST")
        return deferred.await() as T
    }

    suspend fun <T> executeImmediate(timeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS, operation: suspend () -> T): T {
        return mutex.withLock { withTimeout(timeoutMs) { operation() } }
    }

    private suspend fun processQueue() {
        if (isProcessing) return
        mutex.withLock {
            isProcessing = true
            try {
                while (queue.isNotEmpty()) {
                    val op = queue.poll() ?: break
                    val timeout = if (op.config.timeout > 0) op.config.timeout else defaultTimeoutMs
                    val maxRetries = if (op.config.retryCount >= 0) op.config.retryCount else defaultRetryCount
                    var lastException: Exception? = null
                    for (attempt in 0..maxRetries) {
                        try {
                            val result = withTimeout(timeout) { op.operation(timeout) }
                            op.deferred.complete(result)
                            break
                        } catch (e: Exception) {
                            lastException = e
                            if (attempt < maxRetries) delay(defaultRetryDelayMs)
                        }
                    }
                    if (lastException != null && !op.deferred.isCompleted) {
                        op.deferred.completeExceptionally(lastException)
                    }
                }
            } finally {
                isProcessing = false
            }
        }
    }

    fun cancelAll(cause: Throwable) {
        while (queue.isNotEmpty()) queue.poll()?.deferred?.completeExceptionally(cause)
    }

    fun pendingCount(): Int = queue.size
    fun isEmpty(): Boolean = queue.isEmpty()

    private class QueuedOperation(
        val config: OperationConfig,
        val deferred: CompletableDeferred<Any>,
        val operation: suspend (Long) -> Any
    )
}

private const val DEFAULT_RETRY_DELAY = 1000L
