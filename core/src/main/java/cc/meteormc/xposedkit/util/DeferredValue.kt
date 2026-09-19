package cc.meteormc.xposedkit.util

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class DeferredValue<T : Any> {
    private val lock = Any()
    private val callbacks = mutableListOf<(T) -> Unit>()
    private val waiters = mutableListOf<Continuation<T>>()

    var value: T? = null
        internal set(value) {
            requireNotNull(value)
            val callbacks: List<(T) -> Unit>
            val waiters: List<Continuation<T>>
            synchronized(lock) {
                if (field != null) {
                    throw IllegalStateException("Value has already been set!")
                }

                field = value

                callbacks = this.callbacks.toList()
                this.callbacks.clear()

                waiters = this.waiters.toList()
                this.waiters.clear()
            }

            callbacks.forEachCatching { it(value) }
            waiters.forEachCatching { it.resume(value) }
        }

    val initialized
        get() = value != null

    fun require(lazyMessage: () -> String = { "Value has not been set yet!" }): T {
        return value ?: throw IllegalStateException(lazyMessage())
    }

    fun orElse(defaultValue: () -> T): T {
        return value ?: defaultValue()
    }

    fun orDefault(defaultValue: T): T {
        return value ?: defaultValue
    }

    suspend fun await(): T {
        value?.let { return it }
        return suspendCoroutine { safe ->
            synchronized(lock) {
                value?.let {
                    safe.resume(it)
                    return@suspendCoroutine
                }

                waiters += safe
            }
        }
    }

    fun ifInitialize(block: (T) -> Unit) {
        value?.let(block)
    }

    fun whenInitialize(block: (T) -> Unit) {
        synchronized(lock) {
            value?.let { return@synchronized it }
            callbacks += block
            null
        }?.also(block)
    }

    private inline fun <T> Iterable<T>.forEachCatching(action: (T) -> Unit) {
        for (element in this) {
            try {
                action(element)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }
}