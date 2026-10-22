@file:Suppress("DEPRECATION")

package com.zhufucdev.me.xposed

import android.telephony.CellInfo
import android.telephony.CellLocation
import android.telephony.NeighboringCellInfo
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.zhufucdev.me.stub.CellMoment
import com.zhufucdev.me.stub.Toggle
import java.util.concurrent.Executor

class CellHooker(scheduler: XposedScheduler) : BaseHooker(scheduler) {
    private val listeners = mutableSetOf<(CellMoment) -> Unit>()

    var toggle = Toggle.PRESENT

    fun hook() {
        val telephony = TelephonyManager::class.java

        hook(
            telephony.methodOrNull("getCellLocation"),
            after = { args ->
                if (!scheduler.isWorking || toggle == Toggle.NONE) return@hook
                args.result = if (toggle == Toggle.BLOCK) null else scheduler.cells.cellLocation()
            }
        )

        hook(
            telephony.methodOrNull("getAllCellInfo"),
            after = { args ->
                if (!scheduler.isWorking || toggle == Toggle.NONE) return@hook
                args.result =
                    if (toggle == Toggle.BLOCK) null
                    else scheduler.cells.cell.takeIf { it.isNotEmpty() }
            }
        )

        hook(
            telephony.methodOrNull("getNeighboringCellInfo"),
            after = { args ->
                if (!scheduler.isWorking || toggle == Toggle.NONE) return@hook
                args.result =
                    if (toggle == Toggle.BLOCK) null
                    else scheduler.cells.neighboringInfo().takeIf { it.isNotEmpty() }
            }
        )

        hookUnit(
            telephony.methodOrNull("listen", PhoneStateListener::class.java, Int::class.javaPrimitiveType!!)
        ) { args ->
            if (!scheduler.isWorking || toggle == Toggle.NONE) {
                args.proceed()
                return@hookUnit
            }
            if (toggle == Toggle.BLOCK) {
                return@hookUnit
            }

            val listener = args.cast<PhoneStateListener>(0)
            val mode = args.cast<Int>(1) ?: 0
            addListener {
                listener?.treatWith(it, mode)
            }
        }

        hookUnit(
            telephony.methodOrNull(
                "registerTelephonyCallback",
                Executor::class.java,
                TelephonyCallback::class.java
            )
        ) { args ->
            if (!scheduler.isWorking || toggle == Toggle.NONE) {
                args.proceed()
                return@hookUnit
            }
            if (toggle == Toggle.BLOCK) {
                return@hookUnit
            }

            val executor = args.cast<Executor>(0)
            val callback = args.cast<TelephonyCallback>(1)
            addListener {
                executor?.execute {
                    callback?.treatWith(scheduler.cells)
                }
            }
        }

        listOf("getPhoneCount", "getActiveModemCount").forEach { name ->
            hookReplace(telephony.methodOrNull(name)) { args ->
                when {
                    !scheduler.isWorking || toggle == Toggle.NONE -> args.proceed()
                    toggle == Toggle.BLOCK -> 0
                    else -> 1
                }
            }
        }
    }

    private fun addListener(l: (CellMoment) -> Unit) {
        l(scheduler.cells)
        listeners.add(l)
    }

    fun raise(moment: CellMoment) {
        listeners.forEach {
            it(moment)
        }
    }
}
