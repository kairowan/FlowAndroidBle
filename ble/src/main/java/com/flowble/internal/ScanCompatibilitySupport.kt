package com.flowble.internal

import com.flowble.model.BleScanResult
import java.util.UUID

internal fun containsAllAdvertisedServiceUuids(
    advertisedServiceUuids: Collection<UUID>,
    requiredServiceUuids: Collection<UUID>
): Boolean {
    if (requiredServiceUuids.isEmpty()) {
        return true
    }
    if (advertisedServiceUuids.isEmpty()) {
        return false
    }

    val advertised = advertisedServiceUuids.toSet()
    return requiredServiceUuids.all(advertised::contains)
}

internal fun advertisesAllRequiredServiceUuids(
    result: BleScanResult,
    requiredServiceUuids: Collection<UUID>
): Boolean {
    return containsAllAdvertisedServiceUuids(
        advertisedServiceUuids = result.advertisedServiceUuids,
        requiredServiceUuids = requiredServiceUuids
    )
}
