package cc.meteormc.xposedkit.util

import cc.meteormc.xposedkit.call
import cc.meteormc.xposedkit.hook.InvokeCallback
import cc.meteormc.xposedkit.hook.InvokeInfo
import java.lang.reflect.Method
import java.lang.reflect.Proxy

fun <T : Any> newProxy(
    classLoader: ClassLoader,
    vararg interfaces: Class<*>,
    callback: InvokeCallback
): T {
    return buildProxy(classLoader, interfaces) {
        callback(it)
    }
}

fun <T : Any> newFunctionalProxy(
    classLoader: ClassLoader,
    ifc: Class<T>,
    callback: InvokeCallback
): T {
    return buildProxy(classLoader, arrayOf(ifc)) {
        val method = it.member as Method
        if (method.declaringClass == Any::class.java) {
            it.result = method.call(it.instance, *it.args)
            return@buildProxy
        }

        callback(it)
    }
}

fun <T : Any> newSamProxy(
    classLoader: ClassLoader,
    ifc: Class<T>,
    methodName: String,
    callback: InvokeCallback
): T {
    return buildProxy(classLoader, arrayOf(ifc)) {
        val method = it.member as Method
        if (method.declaringClass == Any::class.java) {
            it.result = method.call(it.instance, *it.args)
            return@buildProxy
        }

        if (method.name == methodName) {
            callback(it)
            return@buildProxy
        }

        throw IllegalStateException("Unexpected method ${method.declaringClass}->${method.name}! " +
                "The target may not be a Single Abstract Method interface")
    }
}

private inline fun <T : Any> buildProxy(
    classLoader: ClassLoader,
    interfaces: Array<out Class<*>>,
    crossinline callback: (InvokeInfo) -> Unit
): T {
    @Suppress("UNCHECKED_CAST")
    return Proxy.newProxyInstance(
        classLoader, interfaces
    ) { proxy: Any, method: Method, args: Array<Any?>? ->
        val info = InvokeInfo(method, proxy, args ?: emptyArray(), null, null)
        callback(info)
        return@newProxyInstance info.result
    } as T
}