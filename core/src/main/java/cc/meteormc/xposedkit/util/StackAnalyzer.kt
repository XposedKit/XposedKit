package cc.meteormc.xposedkit.util

import cc.meteormc.xposedkit.XLog
import java.lang.reflect.Method

object StackAnalyzer {
    private const val TAG = "StackAnalyzer"

    val stackTrace: Iterable<StackTraceElement>
        get() {
            // 删除以下两条堆栈和当前类的堆栈:
            // dalvik.system.VMStack.getThreadStackTrace
            // java.lang.Thread.getStackTrace
            return Thread.currentThread()
                .stackTrace
                .asSequence()
                .drop(2)
                .dropWhile { it.className == this.javaClass.name }
                .filter {
                    val className = it.className
                    val methodName = it.methodName
                    val sourceName = it.fileName
                    if (className.startsWith($$$"-$$Nest")) return@filter false
                    if (className.contains($$$"$$ExternalSynthetic")) return@filter false
                    if (methodName.startsWith("access$")) return@filter false
                    if (sourceName.contentEquals($$$"D8$$SyntheticClass")) return@filter false
                    return@filter true
                }
                .asIterable()
        }

    val caller: StackTraceElement?
        get() {
            val stack = stackTrace.iterator()
            if (!stack.hasNext()) return null
            stack.next() // 跳过调用者自身的堆栈
            return if (stack.hasNext()) stack.next() else null
        }

    val callerClass: String?
        get() = caller?.className

    val callerMethod: String?
        get() = caller?.methodName

    fun dumpStack() {
        XLog.d(TAG, stackTrace.joinToString("\n", "Stack trace:\n") { "\tat $it" })
    }

    fun isCalledFrom(fullName: String): Boolean {
        val normalized = fullName.replace('#', '.')
        return stackTrace.any { normalized == "${it.className}.${it.methodName}" }
    }

    fun isCalledFrom(className: String? = null, methodName: String? = null): Boolean {
        if (className == null && methodName == null) return false
        return stackTrace.any {
            if (className != null && className != it.className) return@any false
            if (methodName != null && methodName != it.methodName) return@any false
            true
        }
    }

    fun isCalledFrom(clazz: Class<*>): Boolean {
        val className = clazz.name
        return stackTrace.any { className == it.className }
    }

    fun isCalledFrom(method: Method): Boolean {
        val className = method.declaringClass.name
        val methodName = method.name
        return stackTrace.any { className == it.className && methodName == it.methodName }
    }
}