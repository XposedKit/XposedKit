package cc.meteormc.xposedkit.provider

import android.content.SharedPreferences

interface RemotePreferencesProvider {
    /**
     * 获取存储在 Xposed 框架中的 RemotePreferences，**它是只读的**
     */
    operator fun get(name: String): SharedPreferences
}