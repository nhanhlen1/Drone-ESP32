package dev.drone.controller

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.drone.controller.command.ArmCommandStatus
import dev.drone.controller.control.ArmState
import dev.drone.controller.transport.SessionSnapshot
import dev.drone.controller.ui.StickView
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in simulation test. The normal APK factory, Wi-Fi binder, UI,
 * ViewModel, authority, scheduler and encoder are used without a test transport.
 * The only permitted endpoint is the Android emulator's host alias and the FC
 * must identify as simulation system 42. No physical drone may be connected.
 */
@RunWith(AndroidJUnit4::class)
class LiveControllerSitlActivityTest {
    @Test(timeout=180_000) fun normalApkUiControlsFourChannelsAndArmsOnlyTheExplicitSimulator() {
        val args=InstrumentationRegistry.getArguments()
        val host=args.getString("sitlHost")
        val port=args.getString("sitlPort")?.toIntOrNull()
        assumeTrue("SITL APK test requires explicit emulator endpoint and simulated ARM opt-in",
            host=="10.0.2.2" && port!=null && port in 1024..65535 && args.getString("simulateArm")=="true")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model:ControllerViewModel
            scenario.onActivity { activity ->
                model=ViewModelProvider(activity)[ControllerViewModel::class.java]
                activity.findViewById<EditText>(R.id.connect_host).setText(host)
                activity.findViewById<EditText>(R.id.connect_port).setText(port.toString())
                assertTrue(activity.findViewById<Button>(R.id.connect_button).performClick())
            }
            fun snapshot()=model.state.value ?:error("No ViewModel snapshot")
            await("Normal Android Wi-Fi binding must discover the simulation",45_000) {
                val s=snapshot();s.engine.connected&&s.engine.targetSystem==42&&s.engine.arm==ArmState.DISARMED&&s.engine.parameters.containsKey("RC4_REVERSED")
            }
            assertFalse("Connecting must never ARM",snapshot().engine.arm==ArmState.ARMED)
            assertEquals("Read-only startup sends no RC",0L,snapshot().txOverrides)
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString()=="TAY CẦN" }.performClick()
                activity.findViewById<Spinner>(R.id.control_profile).setSelection(1)
            }
            idle()
            enable(scenario,true)
            await("Four-channel disarmed authority and FC echo",5_000) {
                val s=snapshot();s.live.authority.canTransmit&&s.live.authority.rcEchoVerified&&s.live.rcWriteCount>=4
            }
            scenario.onActivity { activity -> moveBothSticks(activity.window.decorView) }
            await("Actual RC_CHANNELS must reflect all four joystick axes",3_000) {
                val s=snapshot();val echo=s.live.lastEcho
                echo!=null&&echo[0]>1500&&echo[1]<1500&&echo[2]>1100&&echo[3]>1500&&s.live.authority.rcEchoVerified
            }
            scenario.onActivity { activity -> releaseBothSticks(activity.window.decorView) }
            await("Throttle holds while roll/pitch/yaw recenter",3_000) {
                val echo=snapshot().live.lastEcho
                echo!=null&&echo[0]==1500&&echo[1]==1500&&echo[2]>1100&&echo[3]==1500
            }
            scenario.onActivity { activity ->
                stopButton(activity.window.decorView).performClick()
                activity.findViewById<Spinner>(R.id.control_profile).setSelection(2)
            }
            idle()
            enable(scenario,false)
            await("FC PreArm, EKF, ground state and current RC echo must clear ARM gate",25_000) {
                val s=snapshot();s.live.authority.canTransmit&&s.live.armBlocker==null
            }
            scenario.onActivity { activity ->
                assertFalse("ARM needs its separate explicit confirmation",activity.findViewById<CheckBox>(R.id.arm_confirm).isChecked)
                activity.findViewById<CheckBox>(R.id.arm_confirm).isChecked=true
                assertTrue(activity.findViewById<Button>(R.id.arm_button).isEnabled)
                assertTrue(activity.findViewById<Button>(R.id.arm_button).performClick())
            }
            await("ARM socket write plus accepted COMMAND_ACK and later ARMED HEARTBEAT",5_000) {
                val s=snapshot();s.engine.arm==ArmState.ARMED&&s.live.armCommand.status==ArmCommandStatus.CONFIRMED&&s.live.armCommand.socketWritten&&s.live.armCommand.heartbeatConfirmed
            }
            val armedWrites=snapshot().live.rcWriteCount
            SystemClock.sleep(600)
            assertTrue("Expected ARMED transition must preserve the real RC scheduler",snapshot().live.authority.canTransmit)
            assertTrue("Live RC continues while ARMED",snapshot().live.rcWriteCount>=armedWrites+5)
            scenario.onActivity { activity ->
                activity.findViewById<CheckBox>(R.id.disarm_confirm).isChecked=true
                assertTrue(activity.findViewById<Button>(R.id.disarm_button).performClick())
            }
            await("DISARM accepted ACK and later DISARMED HEARTBEAT",5_000) {
                val s=snapshot();s.engine.arm==ArmState.DISARMED&&s.live.armCommand.status==ArmCommandStatus.CONFIRMED&&s.live.armCommand.heartbeatConfirmed
            }
            assertFalse("DISARM ends the old control authority",snapshot().live.authority.canTransmit)
            scenario.onActivity { activity -> activity.findViewById<Spinner>(R.id.control_profile).setSelection(1) }
            idle()
            enable(scenario,true)
            await("New disarmed test session",3_000) { snapshot().live.authority.canTransmit }
            scenario.onActivity { activity -> moveBothSticks(activity.window.decorView) }
            await("Non-minimum throttle before Wi-Fi loss",3_000) { snapshot().live.lastEcho?.get(2)?.let{it>1100}==true }
            shell("svc wifi disable")
            await("Android Wi-Fi callback must close the live session",5_000) { !snapshot().engine.connected }
            assertFalse(snapshot().live.authority.canTransmit)
            shell("svc wifi enable")
            SystemClock.sleep(1_000)
            scenario.onActivity { activity -> model.connect(host!!,port!!) }
            await("Explicit reconnect receives new FC heartbeat",15_000) { snapshot().engine.connected&&snapshot().engine.targetSystem==42 }
            assertFalse("Reconnect never restores control authority",snapshot().live.authority.canTransmit)
            assertEquals("Reconnect must not replay the old throttle",1100,snapshot().live.lastRequested[2])
            assertEquals("Reconnect sends no RC before a new confirmation",0L,snapshot().live.rcWriteCount)
            scenario.onActivity { model.disconnect("SITL APK test complete") }
            println("APK LIVE SITL: production Wi-Fi binder + Mode2 UI + four RC echoes + ARM/DISARM ACK/HB + ARMED scheduler + Wi-Fi loss/reconnect verified; physical hardware NOT VERIFIED")
        }
    }

    private fun enable(scenario:ActivityScenario<MainActivity>,isolated:Boolean) {
        scenario.onActivity { activity ->
            activity.findViewById<CheckBox>(R.id.control_props).isChecked=true
            activity.findViewById<CheckBox>(R.id.control_esc).isChecked=isolated
            activity.findViewById<CheckBox>(R.id.control_consent).isChecked=true
            assertTrue(activity.findViewById<Button>(R.id.control_enable).performClick())
        }
    }
    private var down=0L
    private var moving=emptyList<Pointer>()
    private fun moveBothSticks(root:View) {
        val left=descendants(root).filterIsInstance<StickView>().first{it.id==R.id.left_stick}
        val right=descendants(root).filterIsInstance<StickView>().first{it.id==R.id.right_stick}
        // Exercise the actual view touch handlers, including pointer splitting.
        val parent=left.parent as ViewGroup
        val a=centerIn(left,parent);val b=centerIn(right,parent)
        down=SystemClock.uptimeMillis()
        send(parent,MotionEvent.ACTION_DOWN,listOf(Pointer(7,a.first,a.second)))
        send(parent,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(Pointer(7,a.first,a.second),Pointer(9,b.first,b.second)))
        moving=listOf(Pointer(7,a.first+20,a.second-25),Pointer(9,b.first+25,b.second-20))
        send(parent,MotionEvent.ACTION_MOVE,moving)
        assertEquals(7,left.model.pointer);assertEquals(9,right.model.pointer)
    }
    private fun releaseBothSticks(root:View) {
        val left=root.findViewById<StickView>(R.id.left_stick)
        val parent=left.parent as ViewGroup
        send(parent,MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),moving)
        send(parent,MotionEvent.ACTION_UP,listOf(moving.first()))
    }
    private data class Pointer(val id:Int,val x:Float,val y:Float)
    private fun send(root:View,action:Int,pointers:List<Pointer>) {
        val properties=Array(pointers.size){i->MotionEvent.PointerProperties().apply{id=pointers[i].id;toolType=MotionEvent.TOOL_TYPE_FINGER}}
        val coords=Array(pointers.size){i->MotionEvent.PointerCoords().apply{x=pointers[i].x;y=pointers[i].y;pressure=1f;size=1f}}
        val at=maxOf(SystemClock.uptimeMillis(),down+1)
        MotionEvent.obtain(down,at,action,pointers.size,properties,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0).let { event ->
            try{assertTrue(root.dispatchTouchEvent(event))}finally{event.recycle()}
        }
    }
    private fun centerIn(view:View,parent:View):Pair<Float,Float> {
        val p=IntArray(2);val origin=IntArray(2);view.getLocationOnScreen(p);parent.getLocationOnScreen(origin)
        return p[0]-origin[0]+view.width/2f to p[1]-origin[1]+view.height/2f
    }
    private fun descendants(view:View):Sequence<View> = sequence {
        yield(view);if(view is ViewGroup)for(i in 0 until view.childCount)yieldAll(descendants(view.getChildAt(i)))
    }
    private fun stopButton(root:View)=descendants(root).filterIsInstance<Button>().first{it.text.toString()=="DỪNG PHÁT ĐIỀU KHIỂN"}
    private fun idle()=InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    private fun shell(command:String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use{it.readBytes()}
        }
    }
    private fun await(message:String,timeoutMs:Long,condition:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+timeoutMs
        while(SystemClock.elapsedRealtime()<until){if(condition())return;SystemClock.sleep(25)}
        assertTrue(message,condition())
    }
}
