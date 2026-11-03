package cc.meteormc.xposedkit.ipc

import android.os.IBinder
import java.util.concurrent.Executor

class BinderRegistry {
    fun register(identifier: String, binder: IBinder) {

    }

    fun bind(identifier: String, connection: IpcConnection) {

    }

    fun bind(identifier: String, executor: Executor, connection: IpcConnection) {

    }
}