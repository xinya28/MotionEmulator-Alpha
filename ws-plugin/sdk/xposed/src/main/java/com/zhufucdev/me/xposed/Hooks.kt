package com.zhufucdev.me.xposed

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * Per-call view of a modern-API hook chain.
 *
 * `proceed()` maps to the original call; `result` is only meaningful in "after" bodies and can
 * be overwritten to change the return value.
 */
class HookArgs internal constructor(private val chain: XposedInterface.Chain) {
    val thisObject: Any? get() = chain.thisObject
    val args: List<Any?> get() = chain.args
    val size: Int get() = args.size

    operator fun get(index: Int): Any? = args.getOrNull(index)

    @Suppress("UNCHECKED_CAST")
    fun <T> cast(index: Int): T? = args.getOrNull(index) as? T

    var result: Any? = null

    fun proceed(): Any? = chain.proceed()

    fun proceedWith(newArgs: Array<Any?>): Any? = chain.proceed(newArgs)
}

/**
 * Common plumbing for hookers: logging, application context and the small set of hook shapes
 * this module needs (before / after / full replacement).
 */
abstract class BaseHooker(protected val scheduler: XposedScheduler) {
    protected val module: XposedModule get() = scheduler.module

    protected val classLoader: ClassLoader get() = scheduler.classLoader

    protected val appContext: Context? get() = currentApplication()

    protected fun logI(msg: String) = runCatching { module.log(Log.INFO, scheduler.tag, msg) }

    protected fun logD(msg: String) = runCatching { module.log(Log.DEBUG, scheduler.tag, msg) }

    protected fun logE(msg: String, tr: Throwable? = null) =
        runCatching {
            if (tr == null) module.log(Log.ERROR, scheduler.tag, msg)
            else module.log(Log.ERROR, scheduler.tag, msg, tr)
        }

    /** Runs [before] first, then the original call, then [after] with the produced result. */
    protected fun hook(method: Method?, before: ((HookArgs) -> Unit)? = null, after: ((HookArgs) -> Unit)? = null) {
        if (method == null) return
        install(method) { chain ->
            val args = HookArgs(chain)
            before?.invoke(args)
            val result = chain.proceed()
            args.result = result
            after?.invoke(args)
            args.result
        }
    }

    /** Replaces the whole call with [body]. Use [HookArgs.proceed] to fall back to the original. */
    protected fun hookReplace(method: Method?, body: (HookArgs) -> Any?) {
        if (method == null) return
        install(method) { chain -> body(HookArgs(chain)) }
    }

    /** Replaces a call whose body only runs side effects. */
    protected fun hookUnit(method: Method?, body: (HookArgs) -> Unit) =
        hookReplace(method) { args -> body(args); null }

    private fun install(method: Method, body: (XposedInterface.Chain) -> Any?) {
        runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? = body(chain)
            })
        }.onFailure { logE("Unable to hook ${method.declaringClass.name}#${method.name}", it) }
    }

    /** All overloads with the given name, walking up the class hierarchy. */
    protected fun Class<*>.methodsNamed(name: String): List<Method> {
        val result = mutableListOf<Method>()
        var clazz: Class<*>? = this
        while (clazz != null && clazz != Any::class.java) {
            clazz.declaredMethods
                .filter { it.name == name && !it.isSynthetic }
                .forEach {
                    runCatching { it.isAccessible = true }
                    result.add(it)
                }
            clazz = clazz.superclass
        }
        return result
    }

    protected fun Class<*>.methodOrNull(name: String, vararg params: Class<*>): Method? {
        var current: Class<*>? = this
        while (current != null) {
            val clazz = current
            val found = runCatching { clazz.getDeclaredMethod(name, *params) }.getOrNull()
            if (found != null) {
                runCatching { found.isAccessible = true }
                return found
            }
            current = clazz.superclass
        }
        return null
    }

    private fun currentApplication(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()
}
