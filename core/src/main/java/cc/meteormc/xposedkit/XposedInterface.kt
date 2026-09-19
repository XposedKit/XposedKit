package cc.meteormc.xposedkit

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import cc.meteormc.xposedkit.hook.HookHandle
import cc.meteormc.xposedkit.hook.HookType
import cc.meteormc.xposedkit.hook.InvokeCallback
import java.lang.reflect.Member

internal interface XposedInterface {
    val apiVer: Int

    val frameworkLabel: String

    val frameworkVer: String

    val frameworkVerCode: Long

    val frameworkProp: Long

    val moduleSource: String

    val moduleAppInfo: ApplicationInfo

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