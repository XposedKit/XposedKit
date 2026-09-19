package cc.meteormc.xposedkit.hook

fun interface InvokeCallback {
    companion object {
        const val PRIORITY_HIGHEST = Int.MAX_VALUE
        const val PRIORITY_HIGH = 100
        const val PRIORITY_NORMAL = 0
        const val PRIORITY_LOW = -100
        const val PRIORITY_LOWEST = Int.MIN_VALUE
    }

    operator fun invoke(info: InvokeInfo)
}