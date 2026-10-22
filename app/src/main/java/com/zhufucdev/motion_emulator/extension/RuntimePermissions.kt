package com.zhufucdev.motion_emulator.extension

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions the emulator actively asks for on start-up.
 *
 * - **App list**: on Android 11+ the package visibility filter is a manifest-only
 *   permission ([Manifest.permission.QUERY_ALL_PACKAGES], declared in
 *   `AndroidManifest.xml`); there is no runtime dialog for it, so it is not listed here.
 * - **Location**: fine/coarse location are needed to read and compare the device's real
 *   position against the mocked one.
 * - **Phone info**: SIM/operator state and line number are used by the telephony
 *   emulation and by the cell tower editor.
 * - **Activity recognition**: step counter/detector hooks report activity transitions.
 * - **Notifications**: the foreground emulation monitor posts a progress notification on
 *   Android 13+.
 */
object RuntimePermissions {

    /**
     * Permissions requested on start-up, filtered by the running OS version.
     */
    fun required(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * The subset of [required] that has not been granted yet.
     */
    fun missing(context: Context): Array<String> = required()
        .filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        .toTypedArray()
}
