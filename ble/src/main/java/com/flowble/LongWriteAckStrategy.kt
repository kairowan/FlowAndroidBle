package com.flowble

/**
 * Suspendable hook invoked after each completed batch of a long write.
 *
 * This can be used to wait for device-side readiness signals, add extra pacing,
 * or observe long write progress before the next batch is sent.
 */
typealias LongWriteAckStrategy = suspend (LongWriteAcknowledgement) -> Unit
