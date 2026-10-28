package dev.drone.controller.transport

import java.util.concurrent.atomic.AtomicReference

/** Android parcels Network objects across Binder: equal network IDs need not
 * have object identity. Compare logical identity, CAS the actual stored object. */
internal fun <T> clearLostNetwork(bound:AtomicReference<T?>,lost:T):Boolean {
    val current=bound.get() ?: return false
    return current==lost && bound.compareAndSet(current,null)
}
