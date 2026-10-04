package cc.meteormc.xposedkit.provider

import android.os.ParcelFileDescriptor

interface RemoteFileProvider {
    /**
     * 获取模块共享数据目录中的文件，**该文件以只读模式打开**
     */
    operator fun get(name: String): ParcelFileDescriptor

    /**
     * 获取模块共享数据目录中的文件列表
     */
    fun files(): List<String>
}