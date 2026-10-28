package dev.drone.controller

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.drone.controller.control.MonotonicClock
import dev.drone.controller.transport.AndroidWifiBinder
import dev.drone.controller.transport.BenchSession
import dev.drone.controller.transport.SessionLogger
import dev.drone.controller.transport.SessionSnapshot
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
):ViewModel() {
    val state:StateFlow<SessionSnapshot?> = mutableState.asStateFlow()
    init {refresh()}
    fun refresh(){mutableState.value=session.snapshot()}
    fun connect(host:String,port:Int){session.connect(host,port);refresh()}
    fun disconnect(reason:String="Đã ngắt kết nối theo yêu cầu"){session.disconnect(reason);refresh()}
    fun focus(active:Boolean){session.foreground(active);refresh()}
    fun background(){session.foreground(false);disconnect("Ứng dụng ra nền/đổi màn hình: đã thu hồi quyền và đóng TCP")}
    fun stop(reason:String="Người dùng dừng phát RC"){session.stop(reason);refresh()}
    fun deadman(held:Boolean){session.deadman(held);refresh()}
    fun preview(input:Mode2Input){session.sticks(input.roll,input.pitch,input.throttle,input.yaw);refresh()}
    fun configure(channel:Int,min:Int,max:Int,trim:Int,verified:Boolean){session.configure(channel,min,max,trim,verified);refresh()}
    fun startBench(propsRemoved:Boolean,escIsolated:Boolean,authorised:Boolean,criticalRcDiagnosticAuthorised:Boolean=false){
        if(session.engine.confirmBenchConditions(propsRemoved,escIsolated,authorised,criticalRcDiagnosticAuthorised))session.enableTest()
        refresh()
    }
    fun testValue(value:Float){session.singleChannel(1,value);refresh()}
    override fun onCleared(){session.close();wifi.close();logger.close();super.onCleared()}

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
                return ControllerViewModel(session,logger,wifi,state) as T
            }
        }
    }
}
