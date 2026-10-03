package cc.meteormc.xposedkit.engine

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import cc.meteormc.xposedkit.XLog
import cc.meteormc.xposedkit.hook.HookHandle
import cc.meteormc.xposedkit.hook.HookType
import cc.meteormc.xposedkit.hook.InvokeCallback
import java.lang.reflect.Member

internal interface Engine {
    fun getApiVersion(): Int

    fun getFrameworkName(): String

    fun getFrameworkVersion(): String

    fun getFrameworkVersionCode(): Long

    fun getFrameworkProperties(): Long

    fun getModuleSource(): String

    fun getModuleAppInfo(): ApplicationInfo

    fun deoptimize(member: Member): Boolean

    fun hook(
        member: Member,
        type: HookType,
        priority: Int,
        callback: InvokeCallback
    ): HookHandle

    fun hookClassInitializer(
        clazz: Class<*>,
        type: HookType,
        callback: InvokeCallback
    ): HookHandle

    fun invokeOriginal(member: Member, obj: Any?, vararg args: Any?): Any?

    fun invokeSpecial(member: Member, obj: Any, vararg args: Any?): Any?

    fun getRemotePrefs(name: String): SharedPreferences

    fun getRemoteFile(name: String): ParcelFileDescriptor

    fun getRemoteFiles(): List<String>

    fun printLog(log: XLog.LogRecord)
}