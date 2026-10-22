@file:Suppress("unused", "DEPRECATION")

package com.zhufucdev.me.xposed

import android.hardware.Sensor
import com.aventrix.jnanoid.jnanoid.NanoIdUtils
import com.zhufucdev.me.plugin.AbstractScheduler
import com.zhufucdev.me.plugin.ServerScope
import com.zhufucdev.me.stub.Box
import com.zhufucdev.me.stub.CellMoment
import com.zhufucdev.me.stub.CellTimeline
import com.zhufucdev.me.stub.Intermediate
import com.zhufucdev.me.stub.MapProjector
import com.zhufucdev.me.stub.Method
import com.zhufucdev.me.stub.Motion
import com.zhufucdev.me.stub.MotionMoment
import com.zhufucdev.me.stub.Point
import com.zhufucdev.me.stub.Trace
import com.zhufucdev.me.stub.at
import com.zhufucdev.me.stub.generateSaltedTrace
import com.zhufucdev.me.stub.toPoint
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Scheduler bound to a modern-API module generation.
 *
 * Hooks are installed in [attach], which the module entry point calls from
 * `onPackageLoaded`; everything below is the same emulation logic the legacy module used.
 */
abstract class XposedScheduler : AbstractScheduler() {
    lateinit var module: XposedModule
        private set
    lateinit var packageParam: XposedModuleInterface.PackageLoadedParam
        private set

    override val packageName: String
        get() = if (::packageParam.isInitialized) packageParam.packageName
        else throw IllegalStateException("Not initialized")

    val classLoader: ClassLoader get() = packageParam.defaultClassLoader

    open val tag: String get() = "MotionEmulator"

    val sensorHooker = SensorHooker(this)
    val locationHooker = LocationHooker(this)
    val cellHooker = CellHooker(this)

    val id: String = NanoIdUtils.randomNanoId()
    var hookingMethod: Method = Method.XPOSED_ONLY
        private set

    /**
     * Invoked after hooks are installed, for scheduler specific setup (server, prefs, ...).
     */
    abstract fun initialize()

    /**
     * Invoked before installing hooks to decide which hook set applies.
     */
    abstract fun resolveHookingMethod(): Method

    /**
     * Installs every hooker for the package the module was just loaded into.
     */
    fun attach(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        this.module = module
        this.packageParam = param
        hookingMethod = resolveHookingMethod()

        if (!hookingMethod.involveXposed) {
            return
        }

        sensorHooker.hook()
        locationHooker.hook()
        cellHooker.hook()

        initialize()
    }

    protected var mLocation: Point? = null
    private var mCellMoment: CellMoment? = null

    private val intermediate
        get() = Intermediate(
            progress = loopProgress,
            location = location,
            elapsed = loopElapsed / 1000.0
        )

    val location get() = mLocation ?: Point.zero
    val cells get() = mCellMoment ?: CellMoment(0F)
    val motion = MotionMoment(0F, mutableMapOf())

    private val stepSensors = intArrayOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR)

    private var stepsCount: Int = -1

    override suspend fun ServerScope.startStepsEmulation(motion: Box<Motion>, velocity: Double) {
        sensorHooker.toggle = motion.status

        if (motion.value != null && motion.value!!.sensorsInvolved.any { it in stepSensors }) {
            val pause = (1.2 / velocity).seconds
            if (stepsCount == -1) {
                stepsCount =
                    (Random.nextFloat() * 5000).toInt() + 2000 // beginning with a random steps count
            }
            while (isWorking && loopProgress <= 1) {
                val moment =
                    MotionMoment(
                        loopElapsed / 1000F,
                        mutableMapOf(
                            Sensor.TYPE_STEP_COUNTER to floatArrayOf(1F * stepsCount++),
                            Sensor.TYPE_STEP_DETECTOR to floatArrayOf(1F)
                        )
                    )
                sensorHooker.raise(moment)

                sendProgress(intermediate)
                delay(pause)
            }
        }
    }

    override suspend fun ServerScope.startMotionSimulation(motion: Box<Motion>) {
        sensorHooker.toggle = motion.status
        val partial = motion.value?.validPart()

        if (partial != null && partial.sensorsInvolved.any { it !in stepSensors }) {
            // data other than steps
            while (isWorking && loopProgress <= 1) {
                var lastIndex = 0
                while (isWorking && lastIndex < partial.moments.size && loopProgress <= 1) {
                    val interp = partial.at(loopProgress, lastIndex)

                    sensorHooker.raise(interp.moment)
                    lastIndex = interp.index

                    sendProgress(intermediate)
                    delay(100)
                }
            }
        }
    }

    override suspend fun ServerScope.startTraceEmulation(trace: Trace) {
        // A route with fewer than two points cannot be interpolated: at()/length() would throw
        // or return NaN and the emulation would be reported as failed. Treat it as a no-op.
        if (trace.points.size < 2) {
            locationHooker.resetSpeed()
            return
        }
        locationHooker.resetSpeed()
        try {
            val salted = trace.generateSaltedTrace()
            var traceInterp = salted.at(0F, MapProjector)
            while (isWorking && loopProgress <= 1) {
                val interp = salted.at(loopProgress, MapProjector, traceInterp)
                traceInterp = interp
                val point = interp.point.toPoint(trace.coordinateSystem)
                mLocation = point
                locationHooker.raise(point)

                sendProgress(intermediate)
                delay(1000)
            }
        } finally {
            // Includes cancellation, stopping, and the boundary between repeated traces.
            locationHooker.resetSpeed()
        }
    }

    override suspend fun ServerScope.startCellEmulation(cells: Box<CellTimeline>) {
        cellHooker.toggle = cells.status
        val value = cells.value

        if (value != null) {
            var ptr = 0
            val timespan = value.moments.timespan()
            while (isWorking && loopProgress <= 1 && ptr < value.moments.size) {
                val current = value.moments[ptr]
                mCellMoment = current
                cellHooker.raise(current)

                if (value.moments.size == 1) {
                    delay(duration.seconds) // halt
                } else {
                    if (ptr == value.moments.lastIndex - 1) {
                        break
                    }
                    val pause =
                        (value.moments[ptr].elapsed - value.moments[ptr + 1].elapsed) /
                                timespan * duration
                    ptr++
                    delay(pause.seconds)
                }
            }
        }
    }
}
