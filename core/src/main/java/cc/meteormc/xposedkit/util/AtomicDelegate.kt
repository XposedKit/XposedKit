package cc.meteormc.xposedkit.util

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class AtomicDelegate<T>(initial: T? = null) : ReadWriteProperty<Any?, T?> {
    private var ref = AtomicReference(initial)

    override fun getValue(
        thisRef: Any?,
        property: KProperty<*>
    ): T? {
        return ref.get()
    }

    override fun setValue(
        thisRef: Any?,
        property: KProperty<*>,
        value: T?
    ) {
        ref.set(value)
    }
}

class AtomicBooleanDelegate(initial: Boolean = false) : ReadWriteProperty<Any?, Boolean> {
    private var ref = AtomicBoolean(initial)

    override fun getValue(
        thisRef: Any?,
        property: KProperty<*>
    ): Boolean {
        return ref.get()
    }

    override fun setValue(
        thisRef: Any?,
        property: KProperty<*>,
        value: Boolean
    ) {
        ref.set(value)
    }
}

class AtomicIntDelegate(initial: Int = 0) : ReadWriteProperty<Any?, Int> {
    private var ref = AtomicInteger(initial)

    override fun getValue(
        thisRef: Any?,
        property: KProperty<*>
    ): Int {
        return ref.get()
    }

    override fun setValue(
        thisRef: Any?,
        property: KProperty<*>,
        value: Int
    ) {
        ref.set(value)
    }
}

class AtomicLongDelegate(initial: Long = 0L) : ReadWriteProperty<Any?, Long> {
    private var ref = AtomicLong(initial)

    override fun getValue(
        thisRef: Any?,
        property: KProperty<*>
    ): Long {
        return ref.get()
    }

    override fun setValue(
        thisRef: Any?,
        property: KProperty<*>,
        value: Long
    ) {
        ref.set(value)
    }
}