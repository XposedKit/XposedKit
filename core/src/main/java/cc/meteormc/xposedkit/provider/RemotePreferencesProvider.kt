package cc.meteormc.xposedkit.provider

import android.content.SharedPreferences

interface RemotePreferencesProvider {
    operator fun get(name: String): SharedPreferences
}