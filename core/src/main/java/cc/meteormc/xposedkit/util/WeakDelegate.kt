package cc.meteormc.xposedkit.util

import java.lang.ref.WeakReference
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class WeakDelegate<T>(initial: T? = null) : ReadWriteProperty<Any?, T?> {
    private var ref = WeakReference(initial)

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
        ref = WeakReference(value)
    }
}