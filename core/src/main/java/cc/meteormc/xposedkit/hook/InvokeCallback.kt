package cc.meteormc.xposedkit.hook

fun interface InvokeCallback {
    /**
     * 回调的执行优先级
     *
     * 在 BEFORE 类型的回调中，优先级高的回调先被调用；
     * 在 AFTER 类型的回调中，优先级低的回调先被调用；
     * 对于优先级相同的回调，将按照注册顺序调用。
     *
     * 这些数值只是参考，XposedKit 并不会强制限制优先级的范围
     */
    companion object {
        const val PRIORITY_HIGHEST = Int.MAX_VALUE
        const val PRIORITY_HIGH = 10000
        const val PRIORITY_NORMAL = 50
        const val PRIORITY_LOW = -10000
        const val PRIORITY_LOWEST = Int.MIN_VALUE
    }

    operator fun invoke(info: InvokeInfo)
}