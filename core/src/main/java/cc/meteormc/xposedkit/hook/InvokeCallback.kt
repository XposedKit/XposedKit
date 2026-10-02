package cc.meteormc.xposedkit.hook

fun interface InvokeCallback {
    companion object {
        const val PRIORITY_HIGHEST = Int.MAX_VALUE
        const val PRIORITY_HIGH = 10000
        const val PRIORITY_NORMAL = 50
        const val PRIORITY_LOW = -10000
        const val PRIORITY_LOWEST = Int.MIN_VALUE
    }

    operator fun invoke(info: InvokeInfo)
}