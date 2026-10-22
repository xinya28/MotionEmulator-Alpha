package com.zhufucdev.ws_plugin

import android.content.Context
import com.zhufucdev.me.plugin.MePlugin
import com.zhufucdev.me.plugin.PluginBroadcastReceiver
import com.zhufucdev.me.xposed.PREFERENCE_NAME_BRIDGE
import android.content.Context

class ControllerReceiver : PluginBroadcastReceiver() {
    override fun onEmulationStart(context: Context) {
        // left to Scheduler
    }

    override fun onSettingsChanged(context: Context) {
        context.getSharedPreferences(PREFERENCE_NAME_BRIDGE, Context.MODE_PRIVATE).edit {
            val server = MePlugin.queryServer(context)
            val method = MePlugin.queryMethod(context)
            putBoolean("me_server_tls", server.useTls)
            putInt("me_server_port", server.port)
            putString("me_method", method.name)
        }
    }
}