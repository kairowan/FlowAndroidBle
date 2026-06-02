package com.flowble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.flowble.internal.allPermissionGroupsGranted
import com.flowble.internal.flattenPermissionGroups
import com.flowble.internal.recommendedConnectPermissionGroups
import com.flowble.internal.recommendedScanPermissionGroups
import com.flowble.internal.requiresLocationServicesForScan
import com.flowble.model.BleAdapterState
import com.flowble.model.BleState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Monitors Bluetooth and Location services state changes.
 *
 * This class provides a Flow-based API for observing the overall BLE system state,
 * including Bluetooth on/off, location services, and permission states.
 */
class BluetoothStateMonitor(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter = bluetoothManager?.adapter
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val packageManager = context.packageManager
    private val targetSdk = context.applicationInfo.targetSdkVersion
    private val isAndroidWear = packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
    private val isNearbyPermissionNeverForLocation = resolveNearbyPermissionNeverForLocation()

    /**
     * Observe the overall BLE state.
     *
     * This Flow emits the initial state and then any subsequent BLE environment changes.
     */
    fun observeBleState(): Flow<BleState> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED,
                    LocationManager.MODE_CHANGED_ACTION -> trySend(getCurrentState())
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }

        context.registerReceiver(receiver, filter)
        trySend(getCurrentState())

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }.distinctUntilChanged()

    /**
     * Convenience alias for observing BLE state changes.
     */
    fun observeStateChanges(): Flow<BleState> = observeBleState()

    /**
     * Observe raw Bluetooth adapter state changes without permission/location interpretation.
     */
    fun observeAdapterStateChanges(): Flow<BleAdapterState> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    trySend(getAdapterState())
                }
            }
        }

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(receiver, filter)
        trySend(getAdapterState())

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }.distinctUntilChanged()

    /**
     * Convenience snapshot of the current client state.
     */
    fun getState(): BleState = getCurrentState()

    /**
     * Snapshot of the raw Bluetooth adapter state.
     */
    fun getAdapterState(): BleAdapterState {
        return BleAdapterState.from(
            bluetoothAvailable = adapter != null,
            bluetoothState = adapter?.state ?: BluetoothAdapter.STATE_OFF
        )
    }

    /**
     * Observe Bluetooth adapter state changes.
     *
     * Emits `true` when Bluetooth is enabled, `false` when disabled.
     */
    fun observeBluetoothEnabled(): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.STATE_OFF
                    )
                    trySend(state == BluetoothAdapter.STATE_ON)
                }
            }
        }

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(receiver, filter)
        trySend(adapter?.isEnabled == true)

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }.distinctUntilChanged()

    /**
     * Observe Location services state changes.
     *
     * Emits `true` when location is enabled, `false` when disabled.
     */
    fun observeLocationEnabled(): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == LocationManager.MODE_CHANGED_ACTION) {
                    trySend(isLocationEnabled())
                }
            }
        }

        val filter = IntentFilter(LocationManager.MODE_CHANGED_ACTION)
        context.registerReceiver(receiver, filter)
        trySend(isLocationEnabled())

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }.distinctUntilChanged()

    /**
     * Get the current BLE state.
     */
    fun getCurrentState(): BleState {
        val bluetoothAvailable = adapter != null
        val bluetoothEnabled = adapter?.isEnabled == true
        val bluetoothState = adapter?.state ?: BluetoothAdapter.STATE_OFF
        val locationPermissionGranted = isScanRuntimePermissionGranted()
        val locationEnabled = if (isLocationProviderRequiredForScan()) isLocationEnabled() else true

        return BleState.determine(
            bluetoothAvailable = bluetoothAvailable,
            bluetoothEnabled = bluetoothEnabled,
            bluetoothState = bluetoothState,
            locationPermissionGranted = locationPermissionGranted,
            locationEnabled = locationEnabled
        )
    }

    /**
     * Check if Bluetooth is currently enabled.
     */
    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    /**
     * Whether all runtime permissions required for scanning are currently granted.
     */
    fun isScanRuntimePermissionGranted(): Boolean {
        return allPermissionGroupsGranted(scanPermissionGroups()) { permission ->
            hasPermission(permission)
        }
    }

    /**
     * Whether all runtime permissions required for connecting are currently granted.
     */
    fun isConnectRuntimePermissionGranted(): Boolean {
        return allPermissionGroupsGranted(connectPermissionGroups()) { permission ->
            hasPermission(permission)
        }
    }

    /**
     * Flattened list of recommended runtime permissions for scanning.
     */
    fun getRecommendedScanRuntimePermissions(): Array<String> {
        return flattenPermissionGroups(scanPermissionGroups())
    }

    /**
     * Flattened list of recommended runtime permissions for connecting.
     */
    fun getRecommendedConnectRuntimePermissions(): Array<String> {
        return flattenPermissionGroups(connectPermissionGroups())
    }

    /**
     * Check if Location services are enabled.
     */
    fun isLocationEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager?.isLocationEnabled == true
        } else {
            @Suppress("DEPRECATION")
            try {
                Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.LOCATION_MODE
                ) != Settings.Secure.LOCATION_MODE_OFF
            } catch (_: Settings.SettingNotFoundException) {
                false
            }
        }
    }

    private fun scanPermissionGroups(): List<List<String>> {
        return recommendedScanPermissionGroups(
            deviceSdk = Build.VERSION.SDK_INT,
            targetSdk = targetSdk,
            isNearbyPermissionNeverForLocation = isNearbyPermissionNeverForLocation
        )
    }

    private fun connectPermissionGroups(): List<List<String>> {
        return recommendedConnectPermissionGroups(
            deviceSdk = Build.VERSION.SDK_INT,
            targetSdk = targetSdk
        )
    }

    private fun isLocationProviderRequiredForScan(): Boolean {
        return requiresLocationServicesForScan(
            deviceSdk = Build.VERSION.SDK_INT,
            targetSdk = targetSdk,
            isAndroidWear = isAndroidWear,
            isNearbyPermissionNeverForLocation = isNearbyPermissionNeverForLocation
        )
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun resolveNearbyPermissionNeverForLocation(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }

        return try {
            @Suppress("DEPRECATION")
            val packageInfo = packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            val requestedPermissions = packageInfo.requestedPermissions ?: return false
            val requestedPermissionFlags = packageInfo.requestedPermissionsFlags ?: return false

            requestedPermissions.indices.firstOrNull { index ->
                requestedPermissions[index] == Manifest.permission.BLUETOOTH_SCAN
            }?.let { index ->
                (requestedPermissionFlags[index] and PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION) != 0
            } ?: false
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
