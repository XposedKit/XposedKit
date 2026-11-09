package cc.meteormc.xposedkit.support

import android.app.Activity
import android.app.AppComponentFactory
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.P)
open class AppComponentFactoryWrapper(private val mBase: AppComponentFactory) : AppComponentFactory() {
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun instantiateClassLoader(cl: ClassLoader, aInfo: ApplicationInfo): ClassLoader {
        return mBase.instantiateClassLoader(cl, aInfo)
    }

    override fun instantiateApplication(cl: ClassLoader, className: String): Application {
        return mBase.instantiateApplication(cl, className)
    }

    override fun instantiateActivity(cl: ClassLoader, className: String, intent: Intent?): Activity {
        return mBase.instantiateActivity(cl, className, intent)
    }

    override fun instantiateReceiver(cl: ClassLoader, className: String, intent: Intent?): BroadcastReceiver {
        return mBase.instantiateReceiver(cl, className, intent)
    }

    override fun instantiateService(cl: ClassLoader, className: String, intent: Intent?): Service {
        return mBase.instantiateService(cl, className, intent)
    }

    override fun instantiateProvider(cl: ClassLoader, className: String): ContentProvider {
        return mBase.instantiateProvider(cl, className)
    }
}