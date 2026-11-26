package cc.meteormc.xposedkit.util

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

fun extrasOf(obj: Any): ObjectExtras {
    return ObjectExtras.of(obj)
}

class ObjectExtras private constructor(private val key: HashKey) {
    companion object {
        // 自实现的存储机制 等价于WeakHashMap+IdentityHashMap
        private val queue = ReferenceQueue<Any>()
        private val extrasMap = HashMap<Int, ExtrasBucket>()

        internal fun of(obj: Any): ObjectExtras {
            return synchronized(extrasMap) {
                // 1. 清理队列中待删除的对象
                while (true) {
                    val key = queue.poll() as HashKey? ?: break
                    val hash = key.hash
                    val bucket = extrasMap[hash] ?: continue
                    if (bucket.remove(key)) {
                        bucket.clear()
                        extrasMap.remove(hash, bucket)
                    }
                }

                // 2. 根据哈希存储实例并填充Bucket
                val hash = System.identityHashCode(obj)
                var bucket = extrasMap[hash]
                var extras = if (bucket != null) {
                    bucket.get(obj)
                } else {
                    bucket = ExtrasBucket()
                    extrasMap[hash] = bucket
                    null
                }

                if (extras == null) {
                    val key = HashKey(obj, hash)
                    extras = ObjectExtras(key)
                    bucket.put(extras)
                }

                extras
            }
        }

        internal fun cleanup() {
            synchronized(extrasMap) {
                extrasMap.clear()
            }
        }
    }

    private val store = HashMap<String, Any?>()

    operator fun <T : Any> get(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return store[key] as T?
    }

    operator fun set(key: String, value: Any?) {
        store[key] = value
    }

    fun clear() {
        store.clear()
    }

    // 一种处理哈希碰撞的解决方案 绝大多数情况下使用single就已经足够
    private class ExtrasBucket {
        private var single: ObjectExtras? = null
        private var multiple: ArrayList<ObjectExtras>? = null

        fun get(obj: Any): ObjectExtras? {
            val single = this.single
            val multiple = this.multiple

            if (single != null) {
                return if (single.key.get() === obj) single else null
            }

            if (multiple != null) {
                for (extras in multiple) {
                    if (extras.key.get() !== obj) continue
                    return extras
                }
            }

            return null
        }

        fun put(extras: ObjectExtras) {
            val single = this.single
            val multiple = this.multiple

            // level 3
            if (multiple != null) {
                multiple.add(extras)
                return
            }

            // level 2
            if (single != null) {
                this.single = null
                this.multiple = ArrayList<ObjectExtras>(2).apply {
                    add(single)
                    add(extras)
                }
                return
            }

            // level 1
            this.single = extras
        }

        fun remove(key: HashKey): Boolean {
            val single = this.single
            val multiple = this.multiple

            if (single != null) {
                return single.key === key
            }

            if (multiple != null) {
                if (multiple.isEmpty()) {
                    return true
                }

                if (multiple.size == 1 && multiple[0].key === key) {
                    return true
                }

                multiple.removeAll { it.key === key }
                if (multiple.size == 1) {
                    // 如果只剩下单元素 自动降级为single
                    this.single = multiple[0]
                    this.multiple = null
                }

                return false
            }

            return true
        }

        fun clear() {
            single = null
            multiple = null
        }
    }

    private class HashKey(referent: Any, val hash: Int) : WeakReference<Any>(referent, queue)
}