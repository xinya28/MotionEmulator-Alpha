package com.zhufucdev.ws_plugin.hook

import android.util.Log
import com.zhufucdev.ws_plugin.BuildConfig
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Modern (libxposed API 102) entry point, listed in `META-INF/xposed/java_init.list`.
 *
 * LSPosed instantiates this class once per module generation and calls [onPackageLoaded] for
 * every package (process) the module is scoped to. The legacy `assets/xposed_init` +
 * YukiHookAPI entry is gone.
 */
class MeModule : XposedModule() {
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (!param.isFirstPackage) return
        // The module app itself is not hooked by modern modules; guard anyway for safety.
        if (param.packageName == BuildConfig.APPLICATION_ID) return

        runCatching {
            Scheduler().attach(this, param)
        }.onFailure {
            runCatching { log(Log.ERROR, "MotionEmulator", "Failed to attach scheduler", it) }
        }
    }
}
