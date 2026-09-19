package cc.meteormc.xposedkit.param

import android.app.AppComponentFactory
import android.content.pm.ApplicationInfo

data class PackageLoadedParam(
    val processName: String,
    val packageName: String,
    val classLoader: ClassLoader,
    val appInfo: ApplicationInfo,
    val componentFactory: AppComponentFactory?,
    val isFirstPackage: Boolean
)
