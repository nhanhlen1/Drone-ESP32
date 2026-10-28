package dev.drone.rcbench.transport

import dev.drone.rcbench.control.*
import dev.drone.rcbench.mavlink.*
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Android binds an unconnected channel to the manually selected Wi-Fi network.
 * Unit tests use the default unbound loopback connector. */
fun interface ChannelBinder { fun bind(channel:SocketChannel) }
data class SessionSnapshot(
    val engine:EngineSnapshot,val tcpStatus:String,val lastTxAgeMs:Long?,val lastRxAgeMs:Long?,
    val txBytes:Long,val rxBytes:Long,val txOverrides:Long,val txHz:Double,
    val crcErrors:Long,val parserErrors:Long,
)
private class LiveConnection(val generation:Long) {
    val channel=AtomicReference<SocketChannel?>()
    val parser=Mavlink2Parser()
    val encoder=Mavlink2Encoder()
    @Volatile var lastTx:Long?=null
    @Volatile var lastRx:Long?=null
    @Volatile var txBytes=0L
    @Volatile var rxBytes=0L
    @Volatile var overrides=0L
    @Volatile var txHz=0.0
    fun close(){runCatching{channel.getAndSet(null)?.close()}}
}
private data class Pending(val bytes:ByteBuffer,val permit:ControlPermit?,val started:Long)

/** A single nonblocking actor drains RX before choosing each TX. There is at most
 * one in-progress frame: no queue of stale control/throttle packets. */
class BenchSession(
    private val clock:MonotonicClock=MonotonicClock{System.nanoTime()/1_000_000},
    private val binder:ChannelBinder=ChannelBinder{},
    private val observer:(SessionSnapshot)->Unit={},
):Closeable {
    val engine=BenchEngine(clock)
    private val generation=AtomicLong(0)
    private val current=AtomicReference<LiveConnection?>()
    private val actor=Executors.newSingleThreadExecutor { r->Thread(r,"mavlink-tcp").apply{isDaemon=true} }
    @Volatile private var closed=false
    @Volatile private var status="Chưa kết nối"
    private fun isCurrent(c:LiveConnection)=!closed && current.get()===c && generation.get()==c.generation
    fun connect(host:String,port:Int) {
        if(closed)return
        require(port in 1..65535)
        val parts=host.split('.')
        require(parts.size==4 && parts.all{it.isNotEmpty() && it.all(Char::isDigit) && (it.toIntOrNull()?:-1) in 0..255}) { "Chỉ hỗ trợ địa chỉ IPv4, ví dụ 192.168.4.1" }
        disconnect("Kết nối mới thủ công; đã xóa RC cũ")
        val connection=LiveConnection(generation.incrementAndGet())
        current.set(connection);status="Đang kết nối $host:$port";publish()
        actor.execute {
            if(!isCurrent(connection))return@execute
            runConnection(connection,host,port)
        }
    }
    fun disconnect(reason:String="TCP ngắt; không phát lại ga cũ") {
        generation.incrementAndGet();current.getAndSet(null)?.close()
        status=reason;engine.disconnected(reason);publish()
    }
    fun stop(reason:String){engine.stop(reason);publish()}
    fun foreground(active:Boolean){engine.foreground(active);publish()}
    fun enableTest(){engine.enableTest();publish()}
    fun deadman(held:Boolean){engine.deadman(held);if(!held)publish()}
    fun sticks(roll:Float,pitch:Float,throttle:Float,yaw:Float)=engine.sticks(roll,pitch,throttle,yaw)
    fun singleChannel(channel:Int,value:Float)=engine.singleChannel(channel,value)
    fun mode(channel:Int){engine.selectMode(channel);publish()}
    fun configure(channel:Int,minimum:Int,maximum:Int,trim:Int,verified:Boolean){engine.configure(channel,ChannelCalibration(minimum,maximum,trim,verified));publish()}
    fun snapshot():SessionSnapshot {
        val c=current.get();val d=c?.parser?.diagnostics
        fun age(at:Long?):Long?=at?.let{(clock.nowMs()-it).coerceAtLeast(0)}
        return SessionSnapshot(engine.snapshot(),status,age(c?.lastTx),age(c?.lastRx),c?.txBytes?:0,c?.rxBytes?:0,c?.overrides?:0,c?.txHz?:0.0,d?.crcErrors?:0,
            if(d==null)0 else d.malformedFrames+d.signedRejected+d.unsupportedFlags)
    }
    private fun publish(){if(!closed)observer(snapshot())}
    private fun runConnection(c:LiveConnection,host:String,port:Int) {
        var selector:Selector?=null
        try {
            val channel=SocketChannel.open();c.channel.set(channel)
            if(!isCurrent(c))return
            channel.configureBlocking(false)
            channel.socket().tcpNoDelay=true;channel.socket().keepAlive=true;channel.socket().sendBufferSize=4096
            binder.bind(channel)
            selector=Selector.open()
            channel.connect(InetSocketAddress(InetAddress.getByAddress(host.split('.').map{it.toInt().toByte()}.toByteArray()),port))
            val key=channel.register(selector,SelectionKey.OP_CONNECT)
            val deadline=clock.nowMs()+2500
            while(isCurrent(c)&&!channel.finishConnect()) {
                if(clock.nowMs()>=deadline)throw IOException("TCP connect timeout")
                selector.select(10);selector.selectedKeys().clear()
            }
            if(!isCurrent(c))return
            key.interestOps(SelectionKey.OP_READ);status="TCP kết nối $host:$port";engine.connected();publish()
            val input=ByteBuffer.allocate(4096)
            var pending:Pending?=null;var nextHeartbeat=clock.nowMs();var nextUi=0L
            val sendTimes=ArrayDeque<Long>()
            while(isCurrent(c)) {
                // Drain available inbound data before making any RC authorization decision.
                for(readAttempt in 0 until 16) {
                    input.clear();val count=channel.read(input)
                    if(count<0)throw IOException("TCP peer đóng kết nối")
                    if(count==0)break
                    if(count>0) {
                        c.rxBytes+=count;c.lastRx=clock.nowMs()
                        val before=c.parser.diagnostics
                        val frames=c.parser.feed(input.array(),0,count)
                        val after=c.parser.diagnostics
                        if(after.crcErrors>before.crcErrors || after.signedRejected>before.signedRejected || after.unsupportedFlags>before.unsupportedFlags || after.malformedFrames>before.malformedFrames)
                            engine.streamFault("MAVLink CRC/flags/signature không tin cậy; đã ngừng RC")
                        frames.forEach(engine::onFrame)
                    }
                }
                engine.tick();val now=clock.nowMs()
                val old=pending
                if(old?.permit!=null&&!engine.permitValid(old.permit)) {
                    if(old.bytes.position()>0)throw IOException("Thu hồi RC trong frame TCP dở; đóng để không giữ frame cũ")
                    pending=null
                }
                if(pending==null) {
                    if(now>=nextHeartbeat){pending=Pending(ByteBuffer.wrap(c.encoder.heartbeat()),null,now);nextHeartbeat=now+1000}
                    else engine.planOverride()?.let{permit->pending=Pending(ByteBuffer.wrap(c.encoder.rcOverride(permit.targetSystem,permit.targetComponent,permit.channels)),permit,now)}
                }
                pending?.let{packet->
                    if(clock.nowMs()-packet.started>=200)throw IOException("TCP write quá hạn; đã mất điều khiển")
                    synchronized(engine) {
                    if(packet.permit==null || engine.permitValid(packet.permit)) {
                        // Nonblocking small-frame write: linearize final gate + write
                        // with local stop/background/deadman epoch changes.
                        c.txBytes+=channel.write(packet.bytes)
                        if(!packet.bytes.hasRemaining()) {
                            c.lastTx=clock.nowMs()
                            packet.permit?.let { permit ->
                                engine.sent(permit);c.overrides++;sendTimes.addLast(clock.nowMs())
                            }
                            pending=null
                        }
                    }
                    }
                }
                while(sendTimes.isNotEmpty()&&now-sendTimes.first()>1000)sendTimes.removeFirst()
                c.txHz=sendTimes.size.toDouble()
                if(now>=nextUi){nextUi=now+100;publish()}
                key.interestOps(SelectionKey.OP_READ or if(pending!=null)SelectionKey.OP_WRITE else 0)
                selector.select(10);selector.selectedKeys().clear()
            }
        } catch(e:Exception) {
            if(isCurrent(c)) {status="Mất kết nối: ${e.message?.take(150)}";engine.disconnected(status);engine.log("Kiểm tra RC_OVERRIDE_TIME=0.5s trên ArduPilot; không giả định lệnh trung tính đủ");publish()}
        } finally {
            c.close();runCatching{selector?.close()}
            if(current.compareAndSet(c,null)) {engine.disconnected(status);publish()}
        }
    }
    override fun close(){if(closed)return;disconnect("App đóng; dừng RC");closed=true;actor.shutdownNow()}
}
