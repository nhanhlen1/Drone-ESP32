package dev.drone.controller.transport

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class NetworkLeaseTest {
    private data class NetworkId(val id:Int)
    @Test fun equalParcelInstanceRevokesTheBoundNetwork() {
        val initial=NetworkId(42)
        val callback=NetworkId(42)
        assertNotSame(initial,callback)
        val bound=AtomicReference<NetworkId?>(initial)
        assertTrue(clearLostNetwork(bound,callback));assertNull(bound.get())
        assertFalse(clearLostNetwork(bound,callback))
    }
    @Test fun staleNetworkLossCannotRevokeAReplacementNetwork() {
        val current=NetworkId(43)
        val bound=AtomicReference<NetworkId?>(current)
        assertFalse(clearLostNetwork(bound,NetworkId(42)))
        assertSame(current,bound.get())
        assertTrue(clearLostNetwork(bound,NetworkId(43)))
    }
}
