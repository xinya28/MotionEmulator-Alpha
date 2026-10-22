package com.zhufucdev.me.xposed

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import com.zhufucdev.me.stub.MotionMoment
import com.zhufucdev.me.stub.Toggle
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

class SensorHooker(scheduler: XposedScheduler) : BaseHooker(scheduler) {
    private val listeners = mutableSetOf<SensorListener>()

    var toggle = Toggle.PRESENT

    fun hook() {
        SensorManager::class.java.methodsNamed("registerListener")
            .filter { it.parameterTypes.firstOrNull() == SensorEventListener::class.java }
            .forEach { method ->
                hookReplace(method) { args ->
                    if (!scheduler.isWorking || toggle == Toggle.NONE) {
                        return@hookReplace args.proceed()
                    }
                    if (toggle == Toggle.BLOCK) {
                        return@hookReplace false
                    }
                    redirectToFakeHandler(args)
                }
            }

        SensorManager::class.java.methodsNamed("unregisterListener")
            .filter { it.parameterTypes.firstOrNull() == SensorEventListener::class.java }
            .forEach { method ->
                hookUnit(method) { args ->
                    if (!scheduler.isWorking || toggle == Toggle.NONE) {
                        args.proceed()
                        return@hookUnit
                    }
                    if (toggle == Toggle.BLOCK) {
                        return@hookUnit
                    }
                    val listener = args.cast<SensorEventListener>(0) ?: return@hookUnit
                    val sensor = if (args.size > 1) args.cast<Sensor>(1) else null
                    if (sensor == null) {
                        listeners.removeAll { it.listener == listener }
                    } else {
                        listeners.removeAll { it.listener == listener && it.type == sensor.type }
                    }
                }
            }
    }

    suspend fun raise(moment: MotionMoment) {
        val eventConstructor =
            SensorEvent::class.constructors.firstOrNull { it.parameters.size == 4 }
                ?: SensorEvent::class.constructors.firstOrNull { it.parameters.size == 1 }
                ?: error("SensorEvent constructor not available")
        val elapsed = SystemClock.elapsedRealtimeNanos()
        moment.data.forEach { (t, v) ->
            val sensor =
                appContext!!.getSystemService(SensorManager::class.java).getDefaultSensor(t)
            val values = scheduler.motion.data[t] ?: v
            val event = when (val pars = eventConstructor.parameters.size) {
                4 -> eventConstructor.call(
                    sensor,
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                    elapsed,
                    values
                )

                1 -> eventConstructor.call(values.size).apply {
                    values.copyInto(this.values)
                    accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                    timestamp = elapsed
                    this.sensor = sensor
                }

                else -> throw NotImplementedError("Constructor to SensorEvent with $pars parameters not implemented")
            }
            listeners.forEach { (lt, l, h) ->
                if (lt == t) {
                    supervisorScope {
                        async {
                            h?.post { l.onSensorChanged(event) } ?: l.onSensorChanged(event)
                        }
                    }.start()
                }
            }
        }
    }

    private fun redirectToFakeHandler(args: HookArgs): Boolean {
        val listener = args.cast<SensorEventListener>(0) ?: return false
        val type = args.cast<Sensor>(1)?.type ?: return false
        val handler = args.args.lastOrNull() as? Handler
        listeners.add(SensorListener(type, listener, handler))
        return true
    }
}

data class SensorListener(
    val type: Int,
    val listener: SensorEventListener,
    val handler: Handler? = null
)
