@file:Suppress("DEPRECATION")

package com.zhufucdev.me.xposed

import android.app.Activity
import android.content.ContentResolver
import android.location.GnssStatus
import android.location.GpsSatellite
import android.location.GpsStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.location.OnNmeaMessageListener
import android.net.NetworkInfo
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.zhufucdev.me.stub.Point
import com.zhufucdev.me.stub.android
import com.zhufucdev.me.stub.estimateDistance
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlin.concurrent.timer
import kotlin.random.Random
import kotlin.reflect.jvm.isAccessible

class LocationHooker(scheduler: XposedScheduler) : BaseHooker(scheduler) {
    private val locations = LocationDispatcher(SystemClock::elapsedRealtimeNanos, ::estimateDistance)

    fun hook() {
        if (scheduler.hookingMethod.directHook) {
            invalidateOthers()
            hookGps()
            val succeeded = hookAMap(classLoader)
            if (!succeeded) {
                hook(
                    Activity::class.java.methodOrNull("onCreate", Bundle::class.java),
                    before = { args ->
                        (args.thisObject as? Activity)?.classLoader?.let { hookAMap(it, log = true) }
                    }
                )
            }
        } else if (scheduler.hookingMethod.testProviderTrick) {
            testProviderTrick()
        }
    }

    fun raise(point: Point) {
        locations.raise(point)
    }

    fun resetSpeed() = locations.resetSpeed()

    private fun currentLocation(provider: String = LocationManager.GPS_PROVIDER): Location {
        val sample = locations.current
        return (sample?.point ?: scheduler.location).android(provider, sample?.speed)
    }

    private fun redirectListener(original: Any, l: (LocationSample) -> Unit) {
        locations.register(original, l)
    }

    /**
     * Make network and cell providers invalid
     */
    private fun invalidateOthers() {
        logI("-- block other location methods --")

        WifiManager::class.java.methodOrNull("getScanResults")?.let { m ->
            hookReplace(m) { emptyList<ScanResult>() }
        }
        WifiManager::class.java.methodOrNull("getWifiState")?.let { m ->
            hookReplace(m) { WifiManager.WIFI_STATE_ENABLED }
        }
        WifiManager::class.java.methodOrNull("isWifiEnabled")?.let { m ->
            hookReplace(m) { true }
        }
        WifiManager::class.java.methodOrNull("getConnectionInfo")?.let { m ->
            hookReplace(m) { null }
        }

        NetworkInfo::class.java.methodOrNull("isConnectedOrConnecting")?.let { m ->
            hookReplace(m) { true }
        }
        NetworkInfo::class.java.methodOrNull("isConnected")?.let { m ->
            hookReplace(m) { true }
        }
        NetworkInfo::class.java.methodOrNull("isAvailable")?.let { m ->
            hookReplace(m) { true }
        }

        WifiInfo::class.java.methodOrNull("getSSID")?.let { m ->
            hookReplace(m) { "null" }
        }
        WifiInfo::class.java.methodOrNull("getBSSID")?.let { m ->
            hookReplace(m) { "00-00-00-00-00-00-00-00" }
        }
        WifiInfo::class.java.methodOrNull("getMacAddress")?.let { m ->
            hookReplace(m) { "00-00-00-00-00-00-00-00" }
        }
    }

    private fun hookGps() {
        logI("-- hook GPS --")

        val manager = LocationManager::class.java

        runCatching { classLoader.loadClass("android.location.LastLocationRequest") }
            .getOrNull()
            ?.let { requestClass ->
                hookReplace(manager.methodOrNull("getLastKnownLocation", String::class.java, requestClass)) { args ->
                    currentLocation(args.cast<String>(0) ?: LocationManager.GPS_PROVIDER)
                }
            }

        hookReplace(manager.methodOrNull("getLastLocation")) {
            currentLocation()
        }

        manager.methodsNamed("requestLocationUpdates")
            .plus(manager.methodsNamed("requestSingleUpdate"))
            .forEach { method ->
                hookReplace(method) { args ->
                    val listener = args.args.filterIsInstance<LocationListener>().firstOrNull()
                        ?: return@hookReplace args.proceed()
                    val provider = args.args.filterIsInstance<String>().firstOrNull()
                        ?: LocationManager.GPS_PROVIDER
                    val handler = Handler(Looper.getMainLooper())
                    redirectListener(listener) {
                        val location = it.point.android(provider, it.speed)
                        handler.post {
                            listener.onLocationChanged(location)
                        }
                    }
                    listener.onLocationChanged(currentLocation(provider))
                    null
                }
            }

        hookReplace(manager.methodOrNull("removeUpdates", LocationListener::class.java)) { args ->
            val listener = args[0]
            if (listener == null || !locations.remove(listener)) {
                args.proceed()
            } else null
        }

        // make the app believe gps works
        hookUnit(manager.methodOrNull("getGpsStatus", GpsStatus::class.java)) { args ->
            if (scheduler.satellites <= 0) {
                args.proceed()
                return@hookUnit
            }

            val info = args.cast<GpsStatus>(0) ?: args.result as? GpsStatus ?: return@hookUnit
            val method7 =
                GpsStatus::class.members.firstOrNull { it.name == "setStatus" && it.parameters.size == 8 }

            if (method7 != null) {
                method7.isAccessible = true

                val prns = IntArray(scheduler.satellites) { it }
                val ones = FloatArray(scheduler.satellites) { 1f }
                val zeros = FloatArray(scheduler.satellites) { 0f }
                val ephemerisMask = 0x1f
                val almanacMask = 0x1f

                //5 Scheduler.satellites are fixed
                val usedInFixMask = 0x1f

                method7.call(
                    info,
                    scheduler.satellites,
                    prns,
                    ones,
                    zeros,
                    zeros,
                    ephemerisMask,
                    almanacMask,
                    usedInFixMask
                )
            } else {
                val method =
                    GpsStatus::class.members.firstOrNull { it.name == "setStatus" && it.parameters.size == 3 }
                if (method == null) {
                    logE("method GpsStatus::setStatus is not provided")
                    return@hookUnit
                }
                method.isAccessible = true
                val fake = fakeGnssStatus ?: return@hookUnit
                method.call(info, fake, 1000 + Random.nextInt(-500, 500))
            }
            args.result = info
        }

        hookReplace(manager.methodOrNull("addGpsStatusListener", GpsStatus.Listener::class.java)) { args ->
            if (scheduler.satellites <= 0) {
                return@hookReplace args.proceed()
            }
            val listener = args.cast<GpsStatus.Listener>(0)
            listener?.onGpsStatusChanged(GpsStatus.GPS_EVENT_STARTED)
            listener?.onGpsStatusChanged(GpsStatus.GPS_EVENT_FIRST_FIX)
            timer(name = "satellite heartbeat", period = 1000) {
                listener?.onGpsStatusChanged(GpsStatus.GPS_EVENT_SATELLITE_STATUS)
            }
            true
        }

        listOf(
            manager.methodOrNull("addNmeaListener", GpsStatus.NmeaListener::class.java),
            manager.methodOrNull("addNmeaListener", Executor::class.java, OnNmeaMessageListener::class.java)
        ).forEach { method ->
            hookReplace(method) { args ->
                if (scheduler.satellites <= 0) args.proceed() else false
            }
        }

        hookReplace(
            manager.methodOrNull("registerGnssStatusCallback", Executor::class.java, GnssStatus.Callback::class.java)
        ) { args ->
            if (scheduler.satellites <= 0) {
                return@hookReplace args.proceed()
            }

            val callback = args.cast<GnssStatus.Callback>(1)
            callback?.onStarted()
            callback?.onFirstFix(1000 + Random.nextInt(-500, 500))
            timer(name = "satellite heartbeat", period = 1000) {
                callback?.onSatelliteStatusChanged(fakeGnssStatus ?: return@timer)
            }
            true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hookReplace(
                manager.methodOrNull(
                    "getCurrentLocation",
                    String::class.java,
                    LocationRequest::class.java,
                    CancellationSignal::class.java,
                    Executor::class.java,
                    Consumer::class.java
                )
            ) { args ->
                args.cast<Consumer<Location>>(4)
                    ?.accept(currentLocation(args.cast<String>(0) ?: LocationManager.GPS_PROVIDER))
                null
            }
        }

        hook(
            GpsStatus::class.java.methodOrNull("getSatellites"),
            after = { args ->
                if (scheduler.satellites <= 0) return@hook
                args.result = fakeSatellites.also {
                    logD("${it.count()} satellites are fixed")
                }
            }
        )

        hookReplace(GpsStatus::class.java.methodOrNull("getMaxSatellites")) { args ->
            if (scheduler.satellites <= 0) args.proceed() else scheduler.satellites
        }

        hookReplace(GnssStatus::class.java.methodOrNull("usedInFix", Int::class.javaPrimitiveType!!)) { args ->
            if (scheduler.satellites <= 0) args.proceed() else true
        }
    }

    /**
     * AMap uses network location, which
     * is a troublemaker for this project
     *
     * Specially designed for it
     */
    private fun hookAMap(classLoader: ClassLoader, log: Boolean = false): Boolean {
        logI("-- hook Amap --")
        var succeeded = true

        try {
            val listenerOf = mutableMapOf<Any, Any>()
            val stateOf = mutableMapOf<Any, Boolean>()
            val options = AMapClientOptions()
            val factory = AMapLocationFactory(classLoader.loadAMapLocation())

            val clientClass = classLoader.loadAMapLocationClient()
            val optionClass = classLoader.loadClass("com.amap.api.location.AMapLocationClientOption")
            val listenerClass = classLoader.loadAMapListener()
            val locationClass = classLoader.loadAMapLocation()

            hook(
                clientClass.methodOrNull("setLocationOption", optionClass),
                before = { args ->
                    val client = args.thisObject ?: return@hook
                    options.set(client, args[0])
                }
            )

            hookReplace(clientClass.methodOrNull("setLocationListener", listenerClass)) { args ->
                val listener = args[0]
                if (listener == null) {
                    return@hookReplace args.proceed()
                }
                listenerOf[args.thisObject as Any] = listener
                logD("AMap location registered")
                null
            }

            hookUnit(clientClass.methodOrNull("startLocation")) { args ->
                logI("AMap location started")
                val client = args.thisObject as Any
                stateOf[client] = true

                val listener = listenerOf[client] ?: return@hookUnit
                val method = listenerClass.getMethod("onLocationChanged", locationClass)
                val handler = Handler(Looper.getMainLooper())

                redirectListener(listener) {
                    val amap = factory.create(it, options.offset(client), scheduler.satellites)
                    handler.post {
                        method.invoke(listener, amap)
                    }
                    logD("AMap location redirected")
                }
            }

            hookUnit(clientClass.methodOrNull("stopLocation")) { args ->
                val client = args.thisObject as Any
                stateOf[client] = false
                logI("AMap location stopped")

                val listener = listenerOf[client] ?: return@hookUnit
                locations.remove(listener)
            }

            hookReplace(clientClass.methodOrNull("isStarted")) { args ->
                stateOf[args.thisObject as Any] == true
            }
        } catch (e: ClassNotFoundException) {
            succeeded = false
            if (log) {
                logE("Failed to hook AMap Location Client", e)
            }
        }

        return succeeded
    }

    private fun testProviderTrick() {
        logI("-- make test provider undetectable --")

        hookReplace(Location::class.java.methodOrNull("isMock")) { false }
        hookReplace(Location::class.java.methodOrNull("isFromMockProvider")) { false }

        hook(
            Settings.Secure::class.java.methodOrNull(
                "getString",
                ContentResolver::class.java,
                String::class.java
            ),
            after = { args ->
                val item = args.cast<String>(1)
                if (item == Settings.Secure.ALLOW_MOCK_LOCATION) {
                    logI("Spoof mock location developer options")
                    args.result = "0"
                }
            }
        )

        hookUnit(LocationManager::class.java.methodOrNull("removeTestProvider", String::class.java)) {
            logI("Block test provider removal")
        }
    }

    private val fakeGnssStatus: GnssStatus?
        get() {
            val svid = IntArray(scheduler.satellites) { it }
            val zeros = FloatArray(scheduler.satellites) { 0f }
            val ones = FloatArray(scheduler.satellites) { 1f }

            val constructor = GnssStatus::class.constructors.firstOrNull { it.parameters.size >= 6 }
            if (constructor == null) {
                logE("GnssStatus constructor not available")
                return null
            }

            val constructorArgs = Array(constructor.parameters.size) { index ->
                when (index) {
                    0 -> scheduler.satellites
                    1 -> svid
                    2 -> ones
                    else -> zeros
                }
            }
            return constructor.call(*constructorArgs)
        }

    private val fakeSatellites: Iterable<GpsSatellite> =
        buildList {
            val clz = GpsSatellite::class.java
            for (i in 1..scheduler.satellites) {
                val instance = runCatching {
                    clz.getDeclaredConstructor(Int::class.javaPrimitiveType!!).apply { isAccessible = true }
                        .newInstance(i) as GpsSatellite
                }.getOrNull() ?: return@buildList
                listOf("mValid", "mHasEphemeris", "mHasAlmanac", "mUsedInFix").forEach {
                    runCatching {
                        clz.getDeclaredField(it).apply { isAccessible = true }.setBoolean(instance, true)
                    }
                }
                listOf("mSnr", "mElevation", "mAzimuth").forEach {
                    runCatching {
                        clz.getDeclaredField(it).apply { isAccessible = true }.setFloat(instance, 1F)
                    }
                }
                add(instance)
            }
        }
}
