package cc.meteormc.xposedkit.engine

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import cc.meteormc.xposedkit.XLog
import cc.meteormc.xposedkit.XposedKit
import cc.meteormc.xposedkit.hook.HookHandle
import cc.meteormc.xposedkit.hook.HookType
import cc.meteormc.xposedkit.hook.InvokeCallback
import cc.meteormc.xposedkit.provider.RemoteFileProvider
import cc.meteormc.xposedkit.provider.RemotePreferencesProvider
import java.lang.reflect.Member

/**
 * 引擎接口，提供给框架内部使用
 */
internal interface Engine {
    /**
     * @see [XposedKit.apiVersion]
     */
    fun getApiVersion(): Int

    /**
     * @see [XposedKit.frameworkName]
     */
    fun getFrameworkName(): String

    /**
     * @see [XposedKit.frameworkVersion]
     */
    fun getFrameworkVersion(): String

    /**
     * @see [XposedKit.frameworkVersionCode]
     */
    fun getFrameworkVersionCode(): Long

    /**
     * @see [XposedKit.frameworkProperties]
     */
    fun getFrameworkProperties(): Long

    /**
     * @see [XposedKit.moduleSource]
     */
    fun getModuleSource(): String

    /**
     * @see [XposedKit.moduleAppInfo]
     */
    fun getModuleAppInfo(): ApplicationInfo

    /**
     * TODO
     */
    fun deoptimize(member: Member): Boolean

    /**
     * TODO
     */
    fun hook(
        member: Member,
        type: HookType,
        priority: Int,
        callback: InvokeCallback
    ): HookHandle

    /**
     * TODO
     */
    fun hookClassInitializer(
        clazz: Class<*>,
        type: HookType,
        callback: InvokeCallback
    ): HookHandle

    /**
     * TODO
     */
    fun invokeOriginal(member: Member, obj: Any?, vararg args: Any?): Any?

    /**
     * TODO
     */
    fun invokeSpecial(member: Member, obj: Any, vararg args: Any?): Any?

    /**
     * @see [RemotePreferencesProvider.get]
     */
    fun getRemotePrefs(name: String): SharedPreferences

    /**
     * @see [RemoteFileProvider.get]
     */
    fun getRemoteFile(name: String): ParcelFileDescriptor

    /**
     * @see [RemoteFileProvider.files]
     */
    fun getRemoteFiles(): List<String>

    /**
     * 打印一条消息到 Xposed 日志
     */
    fun printLog(log: XLog.LogRecord)
}