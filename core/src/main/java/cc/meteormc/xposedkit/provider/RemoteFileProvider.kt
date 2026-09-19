package cc.meteormc.xposedkit.provider

import android.os.ParcelFileDescriptor

interface RemoteFileProvider {
    operator fun get(name: String): ParcelFileDescriptor

    fun files(): List<String>
}