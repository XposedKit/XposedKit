package cc.meteormc.xposedkit.hook

import cc.meteormc.xposedkit.XLog

abstract class BaseHooker<T : HookerContext> {
    var hooked = false
        private set
    protected val tag
        get() = this::class.simpleName ?: "Hooker"

    protected open fun T.hook() {

    }

    protected open fun unhook() {

    }

    fun installHook(context: T) {
        try {
            context.hook()
        } catch (e: Throwable) {
            XLog.e(tag, "Error occurred while installing hook", e)
        }
        hooked = true
    }

    fun uninstallHook() {
        try {
            unhook()
        } catch (e: Throwable) {
            XLog.e(tag, "Error occurred while uninstalling hook", e)
        }
        hooked = false
    }
}