package cc.meteormc.xposedkit

import android.app.Application
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageParser
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.content.res.ApkAssets
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import cc.meteormc.xposedkit.engine.Engine
import cc.meteormc.xposedkit.hook.HookType
import cc.meteormc.xposedkit.hook.InvokeCallback
import cc.meteormc.xposedkit.nativelib.NativeBridge
import cc.meteormc.xposedkit.provider.RemoteFileProvider
import cc.meteormc.xposedkit.provider.RemotePreferencesProvider
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

object XposedKit {
    internal const val TAG = "XposedKit"

    const val PROP_CAP_SYSTEM = 1L
    const val PROP_CAP_REMOTE = 1L shl 1
    const val PROP_API_PROTECTION = 1L shl 2

    internal lateinit var engine: Engine
    internal val attachedApplications = WeakHashMap<String, Application>()
    private val appAttachListeners = ConcurrentHashMap<String, MutableSet<(Application) -> Unit>>()

    internal fun init(engine: Engine) {
        this.engine = engine
        XLog.d(TAG, "Initializing XposedKit with engine: ${engine::class.java.name}")
        if (NativeBridge.isLoaded) {
            NativeBridge.Init()
        } else {
            XLog.w(
                TAG,
                "Unable to load XposedKit native library. " +
                        "Make sure the native library for the current ABI is included and libxposedkit.so has not been excluded, " +
                        "some features may not work properly!",
                NativeBridge.error
            )
        }
    }

    internal fun prepare() {
        engine.hook(
            Application::class.reflect.method("attach")!!,
            HookType.AFTER,
            InvokeCallback.PRIORITY_HIGHEST
        ) {
            val application = it.instance<Application>()
            attachedApplications[application.packageName] = application

            val listeners = appAttachListeners.remove(application.packageName) ?: return@hook
            synchronized(listeners) {
                listeners.toList()
            }.forEach { listener ->
                listener(application)
            }
        }
    }

    internal fun <T> withModule(block: XposedModule.() -> T): T {
        return block(moduleInstance)
    }

    val apiVersion
        get() = engine.getApiVersion()

    val frameworkName
        get() = engine.getFrameworkName()

    val frameworkVersion
        get() = engine.getFrameworkVersion()

    val frameworkVersionCode
        get() = engine.getFrameworkVersionCode()

    val frameworkProperties
        get() = engine.getFrameworkProperties()

    val moduleSource
        get() = engine.getModuleSource()

    val moduleAppInfo
        get() = engine.getModuleAppInfo()

    val modulePackageInfo by lazy {
        val pkg = parsedModulePackage
        PackageInfo().apply {
            packageName = pkg.packageName
            splitNames = pkg.splitNames
            versionName = pkg.mVersionName
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                versionCode = pkg.mVersionCode
            } else {
                longVersionCode = (pkg.mVersionCodeMajor.toLong() shl 32) or (pkg.mVersionCode.toLong() and 0xFFFFFFFFL)
            }

            baseRevisionCode = pkg.baseRevisionCode
            splitRevisionCodes = pkg.splitRevisionCodes
            sharedUserId = pkg.mSharedUserId
            sharedUserLabel = pkg.mSharedUserLabel
            installLocation = pkg.installLocation
            applicationInfo = ApplicationInfo(pkg.applicationInfo).apply { metaData = pkg.mAppMetaData }
            configPreferences = pkg.configPreferences?.toTypedArray()
            reqFeatures = pkg.reqFeatures?.toTypedArray()
            featureGroups = pkg.featureGroups?.toTypedArray()

            fun <T : ComponentInfo> T.copyInfo(from: PackageParser.Component) : T {
                applicationInfo = this@apply.applicationInfo
                metaData = from.metaData
                return this
            }
            activities = pkg.activities.map { ActivityInfo(it.info).copyInfo(it) }.toTypedArray()
            receivers = pkg.receivers.map { ActivityInfo(it.info).copyInfo(it) }.toTypedArray()
            services = pkg.services.map { ServiceInfo(it.info).copyInfo(it) }.toTypedArray()
            providers = pkg.providers.map { ProviderInfo(it.info).copyInfo(it) }.toTypedArray()
            instrumentation = pkg.instrumentation.map { it.info }.toTypedArray()
            permissions = pkg.permissions.map { it.info }.toTypedArray()
            requestedPermissions = pkg.requestedPermissions.toTypedArray()
        }
    }

    val modulePackageName
        get() = parsedModulePackage.packageName!!

    val remotePreferences by lazy {
        object : RemotePreferencesProvider {
            override fun get(name: String): SharedPreferences {
                return engine.getRemotePrefs(name)
            }
        }
    }

    val remoteFile by lazy {
        object : RemoteFileProvider {
            override fun get(name: String): ParcelFileDescriptor {
                return engine.getRemoteFile(name)
            }

            override fun files(): List<String> {
                return engine.getRemoteFiles()
            }
        }
    }

    internal val moduleInstance by lazy {
        val classLoader = javaClass.classLoader!!
        // ServiceLoader 不能直接适配 kotlin 的单例类, 所以改为自己实现
        // val services = ServiceLoader.load(XposedModule::class.java, classLoader)
        val services = classLoader
            .getResourceAsStream("META-INF/services/${XposedModule::class.java.name}")
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }

        if (services.isEmpty()) {
            throw IllegalStateException("No XposedModule implementation found!")
        }

        var result: XposedModule? = null
        for (service in services) {
            if (result != null) {
                XLog.w(TAG, "Multiple XposedModule implementations found, ignoring $service")
                continue
            }

            val reflect = classLoader.typedReflect<XposedModule>(service)
            if (reflect == null) {
                XLog.w(TAG, "XposedModule implementation $service not found, skipping it")
                continue
            }

            result = reflect.singleton ?: reflect.constructor()?.new()
            if (result == null) {
                XLog.w(TAG, "XposedModule implementation $service does not have a no-arg constructor, skipping it")
            }
        }

        if (result != null) {
            XLog.d(TAG, "Found XposedModule implementation: ${result::class.java.name}")
            return@lazy result
        }

        throw IllegalStateException("No valid XposedModule implementation found!")
    }

    internal val parsedModulePackage by lazy {
        val source = File(moduleSource).parentFile
        try {
            PackageParser().parsePackage(source, 0).apply {
                applicationInfo.sourceDir = moduleSource
                applicationInfo.publicSourceDir = applicationInfo.sourceDir
            }
        } catch (e: PackageParser.PackageParserException) {
            throw IllegalStateException("Failed to parse module package!", e)
        }
    }

    fun createModuleResources(
        metrics: DisplayMetrics? = null,
        config: Configuration? = null,
        copyFrom: AssetManager? = null
    ): Resources {
        val am = AssetManager::class.reflect { constructor()!!.new<AssetManager>() }
        @Suppress("DEPRECATION")
        val resources = Resources(am, metrics, config)
        addAssetPathToResources(resources, moduleSource)

        // Android 12+ 引入了 Fabricated Runtime Resources Overlay (FRRO) 机制
        // 然而手动创建的 AssetManager 并不会自动注册 FRRO 资源
        // 必须从另一个 AssetManager 中获取这些资源并手动添加到 AssetPath 中
        if (copyFrom != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val assets = AssetManager::class.reflect {
                method("getApkAssets")!!.call<Array<ApkAssets>>(copyFrom)
            }

            for (asset in assets) {
                val path = asset.assetPath
                if (!path.endsWith(".frro")) continue

                // 必须使用 addOverlayPath, 否则不生效
                // addAssetPathToResources(resources, path)
                AssetManager::class.reflect {
                    method("addOverlayPath")!!.call<Int>(am, path)
                }
            }
        }

        return resources
    }

    fun addAssetPathToResources(resources: Resources, path: String) {
        AssetManager::class.reflect {
            method("addAssetPath")!!.call<Int>(resources.assets, path)
        }
    }

    fun registerAppAttachListener(packageName: String, listener: (Application) -> Unit) {
        val attached = attachedApplications[packageName]
        if (attached != null) {
            listener(attached)
            return
        }

        val listeners = appAttachListeners.getOrPut(packageName) { mutableSetOf() }
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun unregisterAppAttachListeners(packageName: String) {
        appAttachListeners.remove(packageName)
    }
}