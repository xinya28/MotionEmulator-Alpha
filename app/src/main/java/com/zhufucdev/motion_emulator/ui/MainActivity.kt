package com.zhufucdev.motion_emulator.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.zhufucdev.motion_emulator.extension.RuntimePermissions
import com.zhufucdev.motion_emulator.extension.Updater
import com.zhufucdev.motion_emulator.extension.lazySharedPreferences
import com.zhufucdev.motion_emulator.plugin.Plugins
import com.zhufucdev.motion_emulator.extension.setUpStatusBar
import com.zhufucdev.motion_emulator.ui.home.AppHome
import com.zhufucdev.motion_emulator.ui.theme.MotionEmulatorTheme

class MainActivity : AppCompatActivity() {
    private val preferences by lazySharedPreferences()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUpStatusBar()

        requestRuntimePermissions()

        setContent {
            MotionEmulatorTheme {
                val updater = remember {
                    Updater(this)
                }
                LaunchedEffect(Unit) {
                    updater.check()
                }

                AppHome(updater = updater, enabledPlugins = Plugins.countEnabled) {
                    val target = Intent(this, it.activity)
                    if (it.mapping && !preferences.contains("map_provider")) {
                        target.setClass(this, MapPendingActivity::class.java)
                        target.putExtra("target", it.activity.name)
                    }
                    startActivity(target)
                }
            }
        }
    }

    /**
     * Asks for location, phone state/number, activity recognition and notification
     * permissions the first time the app is opened and whenever one of them is missing.
     * App list visibility ([android.Manifest.permission.QUERY_ALL_PACKAGES]) is a
     * manifest-only permission and has no runtime prompt.
     */
    private fun requestRuntimePermissions() {
        val missing = RuntimePermissions.missing(this)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
        }
    }
}
