package cc.meteormc.xposedkit.hook

import cc.meteormc.xposedkit.call
import cc.meteormc.xposedkit.callOriginal
import cc.meteormc.xposedkit.callSpecial
import cc.meteormc.xposedkit.reflect
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method

@Suppress("UNCHECKED_CAST")
class InvokeInfo(
    val member: Member,
    val instance: Any?,
    val args: Array<Any?>,
    result: Any?,
    val exception: Throwable?
) {
    internal var hasChanged = false
    var result = result
        set(value) {
            field = value
            hasChanged = true
        }

    fun <T> instance() = this.instance as T

    fun <T> argumentAt(index: Int) = this.args[index] as T

    fun <T> findArg(type: Class<T>, occurrence: Int = 0): T? {
        return findArgIndexed(type, occurrence).value
    }

    fun <T> findArgIndexed(type: Class<T>, occurrence: Int = 0): IndexedValue<T?> {
        var count = 0
        val paramTypes = when (member) {
            is Constructor<*> -> member.parameterTypes
            is Method -> member.parameterTypes
            else -> throw IllegalStateException(/* This should not happen */)
        }
        for ((i, param) in paramTypes.withIndex()) {
            val argument = args[i]
            if (!type.isInstance(argument) && !type.isAssignableFrom(param)) continue
            if (count++ == occurrence) {
                return IndexedValue(i, args[i] as T?)
            }
        }

        throw NoSuchElementException()
    }

    inline fun <reified T : Any> findArg(occurrence: Int = 0): T {
        return findArgIndexed<T>(occurrence).value
    }

    inline fun <reified T : Any> findArgIndexed(occurrence: Int = 0): IndexedValue<T> {
        var count = 0
        for (argument in args.withIndex()) {
            if (argument.value !is T) continue
            if (count++ == occurrence) {
                return argument as IndexedValue<T>
            }
        }

        throw NoSuchElementException()
    }

    fun <T> result() = this.result as T

    fun <T : Throwable?> exception() = this.exception as T

    fun cancel() {
        result = null
    }

    fun <T> callSuper(): T {
        return callSuper(*args)
    }

    fun <T> callSuper(vararg args: Any?): T {
        val superclass = member.declaringClass.superclass?.reflect
            ?: throw IllegalArgumentException("The declaring class of the member has no superclass")
        return when (member) {
            is Constructor<*> -> {
                val ctor = superclass.constructor(*member.parameterTypes)
                    ?: throw IllegalArgumentException("No matching constructor found in superclass")
                ctor.call(instance(), *args)
            }
            is Method -> {
                val method = superclass.method(member.name, *member.parameterTypes)
                    ?: throw IllegalArgumentException("No matching method found in superclass")
                method.callSpecial(instance(), *args)
            }
            else -> {
                throw IllegalArgumentException("Unsupported member type: ${member::class.java.name}")
            }
        }
    }

    fun <T> callOriginal(): T {
        return callOriginal(*args)
    }

    fun <T> callOriginal(vararg args: Any?): T {
        return when (member) {
            is Constructor<*> -> {
                member.callOriginal(instance, *args) as T
            }
            is Method -> {
                member.callOriginal(instance, *args)
            }
            else -> {
                throw IllegalArgumentException("Unsupported member type: ${member::class.java.name}")
            }
        }
    }

    override fun equals(other: Any?) = other is InvokeInfo && this.member == other.member

    override fun hashCode() = this.member.hashCode()

    override fun toString(): String {
        return "InvokeInfo(member=$member, instance=$instance, args=${args.contentToString()}, result=$result, exception=$exception, hasChanged=$hasChanged)"
    }
}