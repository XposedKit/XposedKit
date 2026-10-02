@file:OptIn(ExperimentalContracts::class)

package cc.meteormc.xposedkit.hook

import cc.meteormc.xposedkit.Reflect
import cc.meteormc.xposedkit.XposedKit
import cc.meteormc.xposedkit.reflect
import cc.meteormc.xposedkit.typedReflect
import java.lang.reflect.Member
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

open class HookerContext(
    open val classLoader: ClassLoader,
    protected val handles: MutableList<HookHandle> = mutableListOf()
) {
    inline val String.clazz: Class<*>?
        get() = reflect?.type
    inline val String.reflect: Reflect<*>?
        get() = classLoader.reflect(this)

    inline fun <R> String.reflect(block: Reflect<*>.() -> R): R? {
        contract {
            callsInPlace(block, InvocationKind.AT_MOST_ONCE)
        }
        return classLoader.reflect(this, block)
    }

    inline fun <T : Any, R> String.typedReflect(block: Reflect<T>.() -> R): R? {
        contract {
            callsInPlace(block, InvocationKind.AT_MOST_ONCE)
        }
        return classLoader.typedReflect(this, block)
    }

    fun Member.hookBefore(priority: Int = InvokeCallback.PRIORITY_NORMAL, callback: InvokeCallback): HookHandle {
        return hook(HookType.BEFORE, priority, callback)
    }

    fun Member.hookAfter(priority: Int = InvokeCallback.PRIORITY_NORMAL, callback: InvokeCallback): HookHandle {
        return hook(HookType.AFTER, priority, callback)
    }

    fun <T : Iterable<Member>> T.hookBefore(callback: InvokeCallback): List<HookHandle> {
        return map { it.hookBefore(callback = callback) }
    }

    fun <T : Iterable<Member>> T.hookAfter(callback: InvokeCallback): List<HookHandle> {
        return map { it.hookAfter(callback = callback) }
    }

    fun Class<*>.hookClinitBefore(callback: InvokeCallback): HookHandle {
        return hookClinit(HookType.BEFORE, callback)
    }

    fun Class<*>.hookClinitAfter(callback: InvokeCallback): HookHandle {
        return hookClinit(HookType.AFTER, callback)
    }

    private fun Member.hook(type: HookType, priority: Int = InvokeCallback.PRIORITY_NORMAL, callback: InvokeCallback): HookHandle {
        return XposedKit.engine.hook(
            this,
            type,
            priority,
            callback
        ).apply {
            handles += this
        }
    }

    private fun Class<*>.hookClinit(type: HookType, callback: InvokeCallback): HookHandle {
        return XposedKit.engine.hookClassInitializer(
            this,
            type,
            callback
        ).apply {
            handles += this
        }
    }
}