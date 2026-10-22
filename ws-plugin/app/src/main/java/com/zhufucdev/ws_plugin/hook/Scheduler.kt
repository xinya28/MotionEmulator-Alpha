package com.zhufucdev.ws_plugin.hook

import android.util.Log
import com.zhufucdev.me.plugin.WsServer
import com.zhufucdev.me.plugin.connect
import com.zhufucdev.me.stub.Method
import com.zhufucdev.me.xposed.PREFERENCE_NAME_BRIDGE
import com.zhufucdev.me.xposed.XposedScheduler
import io.github.libxposed.api.XposedModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class Scheduler : XposedScheduler() {
    companion object {
        private const val TAG = "Scheduler"
        private const val DEFAULT_PORT = 20230
        private const val DEFAULT_METHOD = "xposed_only"
    }

    override val tag: String get() = TAG

    private lateinit var server: WsServer
    private val bridge by lazy { BridgePrefs(module) }

    override fun getHookingMethod(): Method = runCatching {
        Method.valueOf(bridge.getString("me_method", DEFAULT_METHOD).uppercase())
    }.getOrDefault(Method.XPOSED_ONLY)

    override fun initialize() {
        server = WsServer(
            port = bridge.getInt("me_server_port", DEFAULT_PORT),
            useTls = bridge.getBoolean("me_server_tls", true)
        )

        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            startServer()
        }
    }

    private suspend fun startServer() {
        var warned = false

        while (true) {
            runCatching {
                server.connect(id) {
                    if (emulation.isPresent)
                        startEmulation(emulation.get())
                }
            }.onFailure {
                runCatching { module.log(Log.WARN, TAG, "Data channel failure", it) }
            }

            if (!warned) {
                runCatching {
                    module.log(Log.INFO, TAG, "Provider offline. Waiting for data channel to become online")
                }
                warned = true
            }
            delay(1.seconds)
        }
    }
}

/**
 * Bridge settings published by the module app.
 *
 * API 102 exposes remote preferences to hooked processes through [XposedModule.getRemotePreferences];
 * when the framework does not provide them (or nothing was published yet) the previous defaults
 * are used, which matches the legacy module's behaviour for untouched configurations.
 */
class BridgePrefs(private val module: XposedModule) {
    private val remote by lazy {
        runCatching { module.getRemotePreferences(PREFERENCE_NAME_BRIDGE) }.getOrNull()
    }

    fun getInt(key: String, def: Int): Int = runCatching { remote?.getInt(key, def) }.getOrDefault(def)

    fun getBoolean(key: String, def: Boolean): Boolean =
        runCatching { remote?.getBoolean(key, def) }.getOrDefault(def)

    fun getString(key: String, def: String): String =
        runCatching { remote?.getString(key, def) }.getOrDefault(def)
}
