package dev.drone.controller

import android.app.Application
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.drone.controller.control.MonotonicClock
import dev.drone.controller.transport.AndroidWifiBinder
import dev.drone.controller.transport.BenchSession
import dev.drone.controller.transport.SessionLogger
import dev.drone.controller.transport.SessionSnapshot
import dev.drone.controller.transport.ControlProfile
import dev.drone.controller.command.ArmRequestResult
import dev.drone.controller.ui.Mode2Input
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.File

/** Inject the runtime boundary so lifecycle policy runs on the host with the real engine.
 * The session owns network scheduling; collecting StateFlow never schedules RC. */
class ControllerViewModel internal constructor(
    private val session:BenchSession,
    val logger:SessionLogger,
    private val wifi:Closeable,
    private val mutableState:MutableStateFlow<SessionSnapshot?> = MutableStateFlow(null),
    private val pulseDriver:ControlPulseDriver?=null,
):ViewModel() {
    val state:StateFlow<SessionSnapshot?> = mutableState.asStateFlow()
    private var focused=false
    private var activeControl=false
    private var latestInput=Mode2Input()
    init {refresh()}
    private fun revokeLocalInput() {
        activeControl=false;latestInput=Mode2Input();pulseDriver?.stop()
    }
    /** Main-thread liveness renews held Mode2 input; a blocked UI cannot renew it.
     * This is not a second RC scheduler: only the session actor writes 20Hz RC. */
    fun pulseControl() {
        if(!activeControl||!focused)return
        session.controlInput(latestInput.roll,latestInput.pitch,latestInput.throttle,latestInput.yaw)
        session.controlPulse()
        if(!session.snapshot().live.authority.canTransmit)revokeLocalInput()
        refresh()
    }
    fun refreshControlReadback(){stop("Đọc lại điều kiện RC: cần xác nhận quyền mới");session.refreshControlReadback();refresh()}
    fun selectControlMode(profile:ControlProfile){revokeLocalInput();session.selectControlMode(profile);refresh()}
    fun startControl(propsRemoved:Boolean,escIsolated:Boolean,authorised:Boolean):Boolean {
        revokeLocalInput()
        if(!focused)return false
        val enabled=session.enableControl(propsRemoved,escIsolated,authorised)
        if(enabled){activeControl=true;latestInput=Mode2Input();pulseControl();if(activeControl)pulseDriver?.start(::pulseControl)}
        refresh();return enabled&&activeControl
    }
    fun requestArm(desired:Boolean,explicitConfirmation:Boolean):ArmRequestResult {
        val result=session.requestArm(desired,explicitConfirmation);refresh();return result
    }
    fun requestFlightMode(mode:Int,explicitConfirmation:Boolean):Boolean {
        val accepted=session.requestFlightMode(mode,explicitConfirmation);refresh();return accepted
    }
    fun refresh(){mutableState.value=session.snapshot()}
    fun connect(host:String,port:Int){revokeLocalInput();session.connect(host,port);refresh()}
    fun disconnect(reason:String="Đã ngắt kết nối theo yêu cầu"){revokeLocalInput();session.disconnect(reason);refresh()}
    fun focus(active:Boolean){focused=active;if(!active)revokeLocalInput();session.foreground(active);refresh()}
    fun background(){focused=false;revokeLocalInput();session.foreground(false);disconnect("Ứng dụng ra nền/đổi màn hình: đã thu hồi quyền và đóng TCP")}
    fun stop(reason:String="Người dùng dừng phát RC"){revokeLocalInput();session.stop(reason);refresh()}
    fun deadman(held:Boolean){session.deadman(held);refresh()}
    fun preview(input:Mode2Input){latestInput=input;session.sticks(input.roll,input.pitch,input.throttle,input.yaw);if(activeControl)pulseControl() else refresh()}
    fun configure(channel:Int,min:Int,max:Int,trim:Int,verified:Boolean){session.configure(channel,min,max,trim,verified);refresh()}
    fun startBench(propsRemoved:Boolean,escIsolated:Boolean,authorised:Boolean,criticalRcDiagnosticAuthorised:Boolean=false){
        if(session.engine.confirmBenchConditions(propsRemoved,escIsolated,authorised,criticalRcDiagnosticAuthorised))session.enableTest()
        refresh()
    }
    fun testValue(value:Float){session.singleChannel(1,value);refresh()}
    override fun onCleared(){revokeLocalInput();pulseDriver?.close();session.close();wifi.close();logger.close();super.onCleared()}

    companion object {
        fun factory(application:Application):ViewModelProvider.Factory=object:ViewModelProvider.Factory {
            override fun <T:ViewModel> create(modelClass:Class<T>):T {
                require(modelClass.isAssignableFrom(ControllerViewModel::class.java))
                val clock=MonotonicClock{SystemClock.elapsedRealtime()}
                val logger=SessionLogger(File(application.filesDir,"logs"))
                val state=MutableStateFlow<SessionSnapshot?>(null)
                var activeSession:BenchSession?=null
                val wifi=AndroidWifiBinder(application){activeSession?.disconnect("Wi-Fi bị mất; cần kết nối và xác nhận lại")}
                val session=BenchSession(clock,wifi){snapshot->logger.record(snapshot,clock.nowMs());state.value=snapshot}
                activeSession=session
                @Suppress("UNCHECKED_CAST")
                return ControllerViewModel(session,logger,wifi,state,AndroidControlPulseDriver()) as T
            }
        }
    }
}

/** UI thread watchdog source, deliberately separate from socket scheduling. */
internal interface ControlPulseDriver:Closeable {
    fun start(pulse:()->Unit)
    fun stop()
}
private class AndroidControlPulseDriver:ControlPulseDriver {
    private val handler=Handler(Looper.getMainLooper())
    private var action:(()->Unit)?=null
    private val tick=object:Runnable {
        override fun run(){val pulse=action?:return;pulse();if(action!=null)handler.postDelayed(this,50)}
    }
    override fun start(pulse:()->Unit){stop();action=pulse;handler.postDelayed(tick,50)}
    override fun stop(){action=null;handler.removeCallbacks(tick)}
    override fun close()=stop()
}
