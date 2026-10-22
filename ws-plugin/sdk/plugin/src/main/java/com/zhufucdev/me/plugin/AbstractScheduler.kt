package com.zhufucdev.me.plugin

import android.os.SystemClock
import com.zhufucdev.me.stub.Box
import com.zhufucdev.me.stub.CellTimeline
import com.zhufucdev.me.stub.Emulation
import com.zhufucdev.me.stub.EmulationInfo
import com.zhufucdev.me.stub.Motion
import com.zhufucdev.me.stub.Trace
import com.zhufucdev.me.stub.length
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * An scheduler is the first thing that picks the
 * [Emulation] request, perceives it and deploy
 * to concrete actions
 */
abstract class AbstractScheduler {
    abstract val packageName: String

    var isWorking = false
        private set

    /**
     * Duration of this emulation in seconds
     */
    protected var duration = -1.0
    protected var length = 0.0

    /**
     * How many satellites to simulate
     *
     * 0 to not simulate
     */
    var satellites: Int = 0
        private set

    private var loopStart = 0L
    val loopElapsed get() = SystemClock.elapsedRealtime() - loopStart
    protected val loopProgress: Float
        get() {
            if (duration <= 0.0 || !duration.isFinite()) return Float.MAX_VALUE
            val progress = (loopElapsed / duration / 1000).toFloat()
            // Keep values above 1 so replay loops terminate, but never hand out NaN/Infinity.
            return if (progress.isFinite()) progress.coerceAtLeast(0f) else Float.MAX_VALUE
        }

    protected suspend fun ServerScope.startEmulation(emulation: Emulation) {
        // A trace with fewer than two points has no length: length() would throw before
        // sendStarted() and the manager would wait forever for an EmulationInfo. Degenerate
        // traces (also produced when the manager serves a stale/empty route) are reported as
        // a zero-length emulation instead of hanging or dividing by zero.
        val points = emulation.trace.points
        length = if (points.size >= 2) runCatching { emulation.trace.length() }.getOrDefault(0.0) else 0.0
        if (!length.isFinite() || length < 0.0) length = 0.0
        duration = if (emulation.velocity > 0.0 && length > 0.0) (length / emulation.velocity).coerceAtLeast(MIN_DURATION) else MIN_DURATION
        satellites = emulation.satelliteCount

        sendStarted(EmulationInfo(duration, length, packageName))
        onEmulationStarted(emulation)
        for (i in 0 until emulation.repeat) {
            loopStart = SystemClock.elapsedRealtime()
            coroutineScope {
                launch {
                    startStepsEmulation(emulation.motion, emulation.velocity)
                }
                launch {
                    startMotionSimulation(emulation.motion)
                }
                launch {
                    startTraceEmulation(emulation.trace)
                }
                launch {
                    startCellEmulation(emulation.cells)
                }
            }
        }

        onEmulationCompleted(emulation)
    }

    open fun onEmulationStarted(emulation: Emulation) {
        isWorking = true
    }

    open fun onEmulationCompleted(emulation: Emulation) {
        isWorking = false
    }

    open suspend fun ServerScope.startStepsEmulation(motion: Box<Motion>, velocity: Double) {
    }

    open suspend fun ServerScope.startMotionSimulation(motion: Box<Motion>) {
    }

    open suspend fun ServerScope.startTraceEmulation(trace: Trace) {
    }

    open suspend fun ServerScope.startCellEmulation(cells: Box<CellTimeline>) {
    }

    companion object {
        /**
         * Floor for one replay loop so a zero-length trace cannot turn into Inf/NaN progress.
         */
        private const val MIN_DURATION = 1e-3
    }
}
