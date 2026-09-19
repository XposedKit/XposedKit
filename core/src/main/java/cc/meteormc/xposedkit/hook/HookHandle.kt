package cc.meteormc.xposedkit.hook

import java.lang.reflect.Member

data class HookHandle(
    val member: Member,
    val type: HookType,
    val priority: Int,
    val callback: InvokeCallback,
    private val unhook: () -> Unit
) {
    fun unhook() {
        unhook.invoke()
    }
}
