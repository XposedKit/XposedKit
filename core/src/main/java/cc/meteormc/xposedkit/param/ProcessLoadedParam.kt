package cc.meteormc.xposedkit.param

import android.os.Bundle

data class ProcessLoadedParam(
    val processName: String,
    val isSystemServer: Boolean,
    val hotReloadInfo: HotReloadInfo? = null
) {
    val isHotReload: Boolean
        get() = hotReloadInfo != null

    data class HotReloadInfo(
        val extras: Bundle?,
        var savedData: Any?
    )
}
