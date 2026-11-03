package cc.meteormc.xposedkit.ipc

import android.os.IBinder

data class BinderInfo(
    val identifier: String,
    val registrarPackageName: String,
    val processName: String,
    val instanceId: Long,
    val binder: IBinder
)