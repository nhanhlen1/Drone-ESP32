package dev.drone.controller.transport

import dev.drone.controller.mavlink.Mavlink2Parser
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ConnectionChurnTest {
    @Test fun hundredConnectionsResetThrottleAndNeverInheritControlOrCalibration() {
        // pymavlink2.4.49 ardupilotmega: SYS42/COMP1, Copter, DISARMED/STANDBY.
        val heartbeat="fd090000002a010000000000000002030003039a9f".chunked(2).map{it.toInt(16).toByte()}.toByteArray()
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val running=AtomicBoolean(true);val accepted=AtomicInteger();val rc=AtomicInteger()
        val peerFailure=AtomicReference<Throwable?>()
        server.soTimeout=100
        val worker=Thread({
            while(running.get()) {
                try {
                    server.accept().use { socket ->
                        accepted.incrementAndGet();socket.soTimeout=100
                        socket.getOutputStream().write(heartbeat);socket.getOutputStream().flush()
                        val parser=Mavlink2Parser();val bytes=ByteArray(1024)
                        while(running.get()) {
                            try {
                                val count=socket.getInputStream().read(bytes)
                                if(count<0)break
                                parser.feed(bytes,0,count).forEach{if(it.messageId==70)rc.incrementAndGet()}
                            }catch(_:SocketTimeoutException){ }
                        }
                    }
                }catch(_:SocketTimeoutException){ }
                catch(error:Throwable){if(running.get())peerFailure.compareAndSet(null,error)}
            }
        },"connection-churn-peer").apply{isDaemon=true;start()}
        try {
            BenchSession().use { session ->
                session.foreground(true)
                repeat(100) { round ->
                    session.connect("127.0.0.1",server.localPort)
                    val deadline=System.nanoTime()+2_000_000_000
                    while(System.nanoTime()<deadline&&session.snapshot().engine.targetSystem!=42)Thread.sleep(2)
                    val e=session.snapshot().engine
                    assertEquals("Handshake round$round ${session.snapshot().tcpStatus}",42,e.targetSystem)
                    assertFalse(e.enabled);assertFalse(e.deadman)
                    assertTrue(e.channels.none{it.verified})
                    assertEquals(1100,e.previewChannels[2])
                    assertNull(e.received)
                    session.configure(1,1100,1900,1500,true)
                    session.sticks(0f,0f,1f,0f)
                    assertEquals(1900,session.snapshot().engine.previewChannels[2])
                    session.disconnect("Churn round$round")
                    assertFalse(session.snapshot().engine.connected)
                    assertEquals(1100,session.snapshot().engine.previewChannels[2])
                    assertTrue(session.snapshot().engine.logs.size<=100)
                }
                assertEquals(100,accepted.get())
                assertEquals(0,rc.get())
                assertNull(peerFailure.get())
            }
        }finally{running.set(false);server.close();worker.join(1000)}
    }
}
