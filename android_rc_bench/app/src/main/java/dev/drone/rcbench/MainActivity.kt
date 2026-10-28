package dev.drone.rcbench

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import dev.drone.rcbench.control.*
import dev.drone.rcbench.mavlink.Telemetry
import dev.drone.rcbench.transport.*
import dev.drone.rcbench.ui.*
import java.io.File
import java.util.Locale

class MainActivity:Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var session:BenchSession
    private lateinit var screen:BenchScreen
    private lateinit var wifi:AndroidWifiBinder
    private lateinit var logger:SessionLogger
    @Volatile private var destroyed=false
    private var resumed=false
    private var focused=false
    private val clock=MonotonicClock{SystemClock.elapsedRealtime()}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(Build.VERSION.SDK_INT<30) {
            @Suppress("DEPRECATION")
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        logger=SessionLogger(File(filesDir,"logs"))
        wifi=AndroidWifiBinder(this){if(::session.isInitialized)session.disconnect("Wi-Fi AP bị mất; không tự kết nối lại")}
        session=BenchSession(clock,wifi) { state ->
            logger.record(state,clock.nowMs())
            if(!destroyed)handler.post { if(!destroyed&&::screen.isInitialized)screen.render(toUi(state)) }
        }
        screen=BenchScreen(this,object:UiActions {
            override fun connect(host:String,port:Int){
                try { screen.resetInputs();session.connect(host,port) }
                catch(e:IllegalArgumentException){Toast.makeText(this@MainActivity,e.message?:"Địa chỉ IPv4/port không hợp lệ",Toast.LENGTH_LONG).show()}
            }
            override fun disconnect(){screen.resetInputs();session.disconnect()}
            override fun enableTest(){session.enableTest()}
            override fun stop(reason:String){session.stop(reason)}
            override fun deadman(held:Boolean){session.deadman(held)}
            override fun sticks(roll:Float,pitch:Float,throttle:Float,yaw:Float){session.sticks(roll,-pitch,throttle,yaw)}
            override fun singleChannel(channel:Int,value:Float){session.singleChannel(channel,value)}
            override fun mode(channel:Int){session.mode(channel)}
            override fun channelConfig(channel:Int,minimum:Int,maximum:Int,trim:Int,verified:Boolean){session.configure(channel,minimum,maximum,trim,verified)}
        })
        setContentView(screen)
        // Android 15/16 enforces edge-to-edge; keep controls clear of bars/cutouts.
        screen.setOnApplyWindowInsetsListener { view,insets ->
            val side=(16*resources.displayMetrics.density).toInt();val vertical=(10*resources.displayMetrics.density).toInt()
            if(Build.VERSION.SDK_INT>=30) {
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime=insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(side+bars.left,vertical+bars.top,side+bars.right,vertical+maxOf(bars.bottom,ime.bottom))
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(side+insets.systemWindowInsetLeft,vertical+insets.systemWindowInsetTop,side+insets.systemWindowInsetRight,vertical+insets.systemWindowInsetBottom)
            }
            insets
        }
        screen.requestApplyInsets();screen.render(toUi(session.snapshot()))
    }
    override fun onResume(){super.onResume();resumed=true;if(::session.isInitialized)session.foreground(focused)}
    override fun onWindowFocusChanged(hasFocus:Boolean){
        super.onWindowFocusChanged(hasFocus);focused=hasFocus
        if(::session.isInitialized)session.foreground(resumed&&hasFocus)
        if(!hasFocus&&::screen.isInitialized)screen.resetInputs()
    }
    override fun onPause(){
        resumed=false
        if(::session.isInitialized){session.foreground(false);session.disconnect("App nền: dừng TCP/RC, không tự khôi phục")}
        if(::screen.isInitialized)screen.resetInputs()
        super.onPause()
    }
    override fun onDestroy(){
        destroyed=true;handler.removeCallbacksAndMessages(null)
        if(::screen.isInitialized)screen.dispose()
        if(::session.isInitialized)session.close()
        if(::wifi.isInitialized)wifi.close()
        if(::logger.isInitialized)logger.close()
        super.onDestroy()
    }
    private fun toUi(s:SessionSnapshot):UiSnapshot {
        val e=s.engine
        fun number(n:Double,d:Int=2)=String.format(Locale.US,"%.${d}f",n)
        val rows=mutableListOf<Pair<String,String>>()
        rows+="GCS / giao thức" to "SYS 255 / COMP 190 • MAVLink2 unsigned • TCP Wi-Fi"
        rows+="Tuổi HEARTBEAT / RC_CHANNELS" to "${e.heartbeatAgeMs?:"—"} / ${e.rcAgeMs?:"—"} ms"
        rows+="Giới hạn bench" to "RC ≤20 Hz; deadman 200ms; CH4 yaw ±25% span (không thử hết biên)"
        rows+="Timeout ArduPilot" to "RC_OVERRIDE_TIME=0.5s được cung cấp; phải đo phía FC, không phải ACK"
        rows+="TCP TX gần nhất" to "${s.lastTxAgeMs?:"—"} ms; khoảng sequence không phải mất gói RF"
        val values=e.telemetry
        (values["Heartbeat"] as? Telemetry.Heartbeat)?.let { rows+="HEARTBEAT" to "custom_mode=${it.customMode}; base_mode=${it.baseMode}; trạng thái=${it.systemStatus}" }
        (values["SysStatus"] as? Telemetry.SysStatus)?.let {
            rows+="SYS_STATUS" to "Pin ${if(it.voltageMv==65535)"—" else number(it.voltageMv/1000.0)+" V"} • ${if(it.currentCentiAmp==-1)"—" else number(it.currentCentiAmp/100.0)+" A"} • ${if(it.batteryRemainingPercent<0)"—" else "${it.batteryRemainingPercent}%"}\nsensors present/enabled/healthy: ${it.sensorsPresent}/${it.sensorsEnabled}/${it.sensorsHealthy} • extended ${it.sensorsPresentExtended}/${it.sensorsEnabledExtended}/${it.sensorsHealthyExtended}"
        }
        (values["Attitude"] as? Telemetry.Attitude)?.let {
            rows+="ATTITUDE (độ)" to "Roll ${number(Math.toDegrees(it.rollRad.toDouble()))} / Pitch ${number(Math.toDegrees(it.pitchRad.toDouble()))} / Yaw ${number(Math.toDegrees(it.yawRad.toDouble()))}\nRate rad/s: ${number(it.rollSpeedRad.toDouble())},${number(it.pitchSpeedRad.toDouble())},${number(it.yawSpeedRad.toDouble())}"
        }
        (values["GpsRawInt"] as? Telemetry.GpsRawInt)?.let {
            rows+="GPS_RAW_INT" to "Fix ${it.fixType} • Sat ${if(it.satellitesVisible==255)"—" else it.satellitesVisible}\n${if(it.fixType<2)"Chưa có vị trí hợp lệ" else "${number(it.latitudeE7/1e7,7)}, ${number(it.longitudeE7/1e7,7)}"}\nCao AMSL ${number(it.altitudeMm/1000.0)}m • HDOP ${if(it.eph==65535)"—" else number(it.eph/100.0)} • tốc độ ${if(it.velocityCms==65535)"—" else number(it.velocityCms/100.0)+"m/s"}"
        }
        (values["RcChannels"] as? Telemetry.RcChannels)?.let { rows+="RC_CHANNELS" to "chancount=${it.channelCount} • RSSI=${if(it.rssi==255)"không có" else it.rssi}" }
        rows+="CSV app-private" to "${logger.file.name}\nLog queue dropped=${logger.dropped.get()} • lỗi=${logger.error?:"không"}"
        return UiSnapshot(
            tcpStatus=s.tcpStatus,heartbeat="HEARTBEAT: ${e.heartbeatAgeMs?.let{"$it ms trước"}?:"chưa có/quá hạn"}",
            armed=when(e.arm){ArmState.UNKNOWN->"KHÔNG RÕ";else->e.arm.name},target="${e.targetSystem?:"—"} / ${e.targetComponent?:"—"}",
            ready=e.ready,testEnabled=e.enabled,modeChannel=e.modeChannel,stopReason=e.reason,
            txRcChannels=e.lastSent,rxRcChannels=e.received,lastTxAgeMs=e.lastOverrideMs?.let{(clock.nowMs()-it).coerceAtLeast(0)},lastRxAgeMs=s.lastRxAgeMs,
            txBytes=s.txBytes,rxBytes=s.rxBytes,txOverrides=s.txOverrides,txHz=s.txHz,crcErrors=s.crcErrors,sequenceLoss=e.sequenceGaps,parserErrors=s.parserErrors,
            sensorReadings=rows,logs=e.logs,channels=e.channels.mapIndexed{i,c->UiChannel(i+1,c.minimum,c.maximum,c.trim,c.verified)},
        )
    }
}
