package cc.meteormc.xposedkit.ipc

fun interface IpcConnection {
    fun onConnected(binder: BinderInfo)
}