package cc.meteormc.xposedkit.impl

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.Keep
import cc.meteormc.xposedkit.XLog
import cc.meteormc.xposedkit.XposedInterface
import cc.meteormc.xposedkit.XposedKit
import cc.meteormc.xposedkit.XposedKit.TAG
import cc.meteormc.xposedkit.hook.HookHandle
import cc.meteormc.xposedkit.hook.HookType
import cc.meteormc.xposedkit.hook.InvokeCallback
import cc.meteormc.xposedkit.hook.InvokeInfo
import cc.meteormc.xposedkit.nativelib.NativeBridge
import cc.meteormc.xposedkit.param.PackageLoadedParam
import cc.meteormc.xposedkit.param.ProcessLoadedParam
import cc.meteormc.xposedkit.param.SystemServerStartingParam
import cc.meteormc.xposedkit.reflect
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.FileNotFoundException
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList

class Xposed : XposedInterface, IXposedHookZygoteInit, IXposedHookLoadPackage {
    init {
        XposedKit.init(this)
    }

    override val apiVer: Int
        get() = XposedBridge.getXposedVersion()
    override val frameworkLabel: String
        get() = runCatching {
            XposedBridge::class.java.getDeclaredField("TAG").get(null) as String
        }.getOrDefault("Xposed").filter {
            it == ' ' || it.isLetterOrDigit()
        }
    override val frameworkVer: String
        get() = "Unknown"
    override val frameworkVerCode: Long
        get() = -1L
    override val frameworkProp: Long
        get() = 0x00
    override var moduleSource: String = ""
        get() = field.ifBlank { throw IllegalStateException("Module source is not set!") }
        private set
    override val moduleAppInfo: ApplicationInfo
        get() = XposedKit.modulePackageInfo.applicationInfo

    private data class HookedMember(
        val member: Member,
        val unhook: XC_MethodHook.Unhook,
        val handles: MutableList<HookHandle>
    )

    private val hookedMembers = HashMap<Member, HookedMember>()
    private val xcallback = object : XC_MethodHook() {
        @Keep
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCallback(HookType.BEFORE, param)
        }

        @Keep
        override fun afterHookedMethod(param: MethodHookParam) {
            runCallback(HookType.AFTER, param)
        }

        private fun runCallback(type: HookType, param: MethodHookParam) {
            val member = param.method
            val target = hookedMembers[member] ?: return
            val info = InvokeInfo(
                member,
                param.thisObject,
                param.args,
                param.result,
                param.throwable
            )
            runCatching {
                for (handle in target.handles) {
                    if (handle.type != type) continue
                    handle.callback(info)
                }
            }.onFailure {
                param.throwable = it
            }
            if (info.hasChanged) {
                param.result = info.result
            }
        }
    }

    override fun deoptimize(member: Member): Boolean {
        if (Modifier.isNative(member.modifiers)) {
            XLog.w(TAG, "Deoptimizing native method is not supported: $member")
            return false
        }

        if (member !is Constructor<*> && member !is Method) {
            throw IllegalArgumentException("Member must be a Constructor or Method!")
        }

        return XposedBridge::class.reflect {
            // 先尝试反射LSPosed框架额外提供的方法
            method("deoptimizeMethod")?.run {
                runCatching { invoke(null, member) }.isSuccess
            }?.takeIf { it }
        } ?: run {
            NativeBridge.SetEntryPointsToInterpreter(member)
        }
    }

    override fun hook(
        member: Member,
        type: HookType,
        priority: Int,
        callback: InvokeCallback
    ): HookHandle {
        val target = hookedMembers.getOrPut(member) {
            HookedMember(
                member,
                XposedBridge.hookMethod(member, xcallback),
                CopyOnWriteArrayList()
            )
        }

        val handle = HookHandle(
            member,
            type,
            priority,
            callback
        ) {
            val handles = target.handles
            handles.removeIf { it.callback == callback }
             if (handles.isEmpty()) {
                 hookedMembers.remove(member)
                  target.unhook.unhook()
             }
        }

        val handles = target.handles
        val insertIndex = handles.indexOfFirst { it.priority < priority }.takeIf { it >= 0 } ?: handles.size
        handles.add(insertIndex, handle)
        return handle
    }

    override fun hookClassInitializer(
        clazz: Class<*>,
        type: HookType,
        callback: InvokeCallback
    ): HookHandle {
        val clinit = NativeBridge.FindClassInitializer(clazz)
        val fakeMember = object : Member {
            override fun getDeclaringClass() = clazz

            override fun getModifiers() = 0x0

            override fun getName() = "<clinit>"

            override fun isSynthetic() = false
        }
        val unhook = XposedBridge.hookMethod(clinit, object : XC_MethodHook() {
            private val info = InvokeInfo(fakeMember, null, emptyArray(), null, null)

            override fun beforeHookedMethod(param: MethodHookParam) {
                if (type != HookType.BEFORE) return
                callback(info)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (type != HookType.AFTER) return
                callback(info)
            }
        })
        return HookHandle(
            fakeMember,
            type,
            InvokeCallback.PRIORITY_NORMAL,
            callback
        ) { unhook.unhook() }
    }

    override fun invokeOriginal(member: Member, obj: Any?, vararg args: Any?): Any? {
        return XposedBridge.invokeOriginalMethod(member, obj, args)
    }

    override fun invokeSpecial(member: Member, obj: Any, vararg args: Any?): Any? {
        if (member !is Constructor<*> && member !is Method) {
            throw IllegalArgumentException("Member must be a Constructor or Method!")
        }

        if (!NativeBridge.isLoaded) {
            var result: Any? = obj
            val builder = StringBuilder("NativeBridge is not available!")
            if (member is Method) {
                builder.append(" The method will be invoked directly instead.")
                result = member.invoke(obj, *args)
            }

            XLog.w(TAG, builder.toString())
            return result
        }

        return NativeBridge.CallNonvirtualMethod(member, obj, *args)
    }

    override fun getRemotePrefs(name: String): SharedPreferences {
        return XSharedPreferences(
            XposedKit.modulePackageName,
            name
        ).apply {
            if (all.isNotEmpty()) return@apply
            makeWorldReadable()
            reload()
        }
    }

    override fun getRemoteFile(name: String): ParcelFileDescriptor {
        throw FileNotFoundException("RemotePreferences is not implemented in Xposed API!")
    }

    override fun getRemoteFiles(): List<String> {
        return emptyList()
    }

    override fun printLog(log: XLog.LogRecord) {
        val level = when (log.priority) {
            Log.VERBOSE -> "VERBOSE"
            Log.DEBUG -> "DEBUG"
            Log.INFO -> "INFO"
            Log.WARN -> "WARN"
            Log.ERROR -> "ERROR"
            Log.ASSERT -> "ASSERT"
            else -> ""
        }
        val values = mapOf(
            "level" to level,
            "level_short" to (level.firstOrNull() ?: "").toString(),
            "module_package" to XposedKit.modulePackageName,
            "tag" to log.tag,
            "message" to log.message
        )
        var formated = "%(\\w+)%".toRegex().replace(XLog.pattern) {
            values[it.groupValues[1]] ?: it.value
        }
        if (log.exception != null) {
            formated += "\n${Log.getStackTraceString(log.exception)}"
        }

        XposedBridge.log(formated)
    }

    override fun initZygote(param: IXposedHookZygoteInit.StartupParam) {
        XLog.v(TAG, "Zygote initialized: modulePath=${param.modulePath}")
        moduleSource = param.modulePath
    }

    @Keep
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        XLog.v(TAG, "Package loaded: processName=${param.processName}, packageName=${param.packageName}, isFirstApplication=${param.isFirstApplication}")

        if (param.packageName == XposedKit.modulePackageName) {
            XLog.d(TAG, "Skipping module package: ${param.packageName}")
            return
        }

        if (param.processName == "android") {
            XLog.v(TAG, "The process is system_server, calling onSystemServerStarting only")
            val processParam = ProcessLoadedParam(param.processName, true)
            val systemParam = SystemServerStartingParam(param.classLoader)
            XposedKit.mount {
                onProcessLoaded(processParam)
                onSystemServerStarting(systemParam)
            }
            return
        }

        if (param.isFirstApplication) {
            XLog.v(TAG, "The process is first application, means its a newly started process, calling onProcessLoaded")
            XposedKit.prepare()
            val processParam = ProcessLoadedParam(param.processName, false)
            XposedKit.mount { onProcessLoaded(processParam) }
        }

        val packageParam = PackageLoadedParam(
            param.processName,
            param.packageName,
            param.classLoader,
            param.appInfo,
            null,
            param.isFirstApplication
        )
        XposedKit.mount { onPackageLoaded(packageParam) }
    }
}