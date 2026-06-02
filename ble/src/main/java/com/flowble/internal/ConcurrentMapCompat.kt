package com.flowble.internal

import java.util.concurrent.ConcurrentMap

internal inline fun <K, V> ConcurrentMap<K, V>.getOrPutConcurrent(
    key: K,
    createValue: () -> V
): V {
    get(key)?.let { return it }
    val newValue = createValue()
    return putIfAbsent(key, newValue) ?: newValue
}
