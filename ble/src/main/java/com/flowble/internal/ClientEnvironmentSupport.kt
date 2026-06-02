package com.flowble.internal

import android.Manifest
import android.os.Build
import kotlin.math.min

internal fun recommendedScanPermissionGroups(
    deviceSdk: Int,
    targetSdk: Int,
    isNearbyPermissionNeverForLocation: Boolean
): List<List<String>> {
    val sdkVersion = min(deviceSdk, targetSdk)
    return when {
        sdkVersion < Build.VERSION_CODES.M -> emptyList()
        sdkVersion < Build.VERSION_CODES.Q -> listOf(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )

        sdkVersion < Build.VERSION_CODES.S -> listOf(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        )

        isNearbyPermissionNeverForLocation -> listOf(
            listOf(BLUETOOTH_SCAN_PERMISSION)
        )

        else -> listOf(
            listOf(BLUETOOTH_SCAN_PERMISSION),
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        )
    }
}

internal fun recommendedConnectPermissionGroups(
    deviceSdk: Int,
    targetSdk: Int
): List<List<String>> {
    val sdkVersion = min(deviceSdk, targetSdk)
    return if (sdkVersion < Build.VERSION_CODES.S) {
        emptyList()
    } else {
        listOf(listOf(BLUETOOTH_CONNECT_PERMISSION))
    }
}

internal fun requiresLocationServicesForScan(
    deviceSdk: Int,
    targetSdk: Int,
    isAndroidWear: Boolean,
    isNearbyPermissionNeverForLocation: Boolean
): Boolean {
    if (isAndroidWear) {
        return false
    }

    return when {
        deviceSdk >= Build.VERSION_CODES.S -> !isNearbyPermissionNeverForLocation
        deviceSdk >= Build.VERSION_CODES.M -> {
            deviceSdk >= Build.VERSION_CODES.Q || targetSdk >= Build.VERSION_CODES.M
        }

        else -> false
    }
}

internal fun flattenPermissionGroups(groups: List<List<String>>): Array<String> {
    return groups.flatten().toTypedArray()
}

internal fun allPermissionGroupsGranted(
    groups: List<List<String>>,
    hasPermission: (String) -> Boolean
): Boolean {
    return groups.all { alternatives -> alternatives.any(hasPermission) }
}

private const val BLUETOOTH_SCAN_PERMISSION = "android.permission.BLUETOOTH_SCAN"
private const val BLUETOOTH_CONNECT_PERMISSION = "android.permission.BLUETOOTH_CONNECT"
