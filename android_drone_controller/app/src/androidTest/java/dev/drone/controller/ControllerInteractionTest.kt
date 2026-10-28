package dev.drone.controller

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.drone.controller.ui.StickView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android event dispatch tests, not a physical Wi-Fi/RC acceptance test.
 * Both sticks remain offline. No socket, command or RC permit is requested. */
@RunWith(AndroidJUnit4::class)
class ControllerInteractionTest {
    @Test fun simultaneousIndependentPointersMapMode2AndReleaseAxesWithoutTransmitting() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSticks(scenario)
            scenario.onActivity { activity ->
                val sticks=descendants(activity.window.decorView).filterIsInstance<StickView>().filter { it.isShown }.toList()
                assertEquals(2,sticks.size)
                val left=sticks.first { it.contentDescription.toString().startsWith("Cần trái") }
                val right=sticks.first { it.contentDescription.toString().startsWith("Cần phải") }
                assertTrue(left.width>0 && right.width>0)
                val a=center(left);val b=center(right)
                val time=SystemClock.uptimeMillis()
                val root=activity.window.decorView
                send(root,time,time,MotionEvent.ACTION_DOWN,listOf(Pointer(7,a.first,a.second)))
                send(root,time,time+10,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(Pointer(7,a.first,a.second),Pointer(9,b.first,b.second)))
                val moving=listOf(Pointer(7,a.first+25,a.second-35),Pointer(9,b.first+35,b.second-25))
                send(root,time,time+20,MotionEvent.ACTION_MOVE,moving)
                assertEquals(7,left.model.pointer)
                assertEquals(9,right.model.pointer)
                val model=ViewModelProvider(activity)[ControllerViewModel::class.java]
                val active=model.state.value!!.engine
                assertTrue("Right horizontal maps roll",active.previewChannels[0]>1500)
                assertTrue("Screen up maps lower pitch PWM",active.previewChannels[1]<1500)
                assertTrue("Left upward relative motion raises offline throttle",active.previewChannels[2]>1100)
                assertTrue("Left horizontal maps yaw",active.previewChannels[3]>1500)
                val retainedThrottle=active.previewChannels[2]
                send(root,time,time+30,MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),moving)
                assertEquals(7,left.model.pointer)
                assertNull(right.model.pointer)
                assertEquals(1500,model.state.value!!.engine.previewChannels[0])
                assertEquals(1500,model.state.value!!.engine.previewChannels[1])
                send(root,time,time+40,MotionEvent.ACTION_UP,listOf(moving.first()))
                val released=model.state.value!!.engine
                assertEquals(retainedThrottle,released.previewChannels[2])
                assertEquals(1500,released.previewChannels[3])
                assertFalse(released.connected)
                assertFalse(released.enabled)
                assertEquals(0L,model.state.value!!.txOverrides)
            }
        }
    }

    @Test fun gestureCancellationAndStopClearThrottleAndAuthority() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSticks(scenario)
            scenario.onActivity { activity ->
                val left=descendants(activity.window.decorView).filterIsInstance<StickView>().first { it.isShown && it.contentDescription.toString().startsWith("Cần trái") }
                val p=center(left);val time=SystemClock.uptimeMillis();val root=activity.window.decorView
                send(root,time,time,MotionEvent.ACTION_DOWN,listOf(Pointer(13,p.first,p.second)))
                send(root,time,time+10,MotionEvent.ACTION_MOVE,listOf(Pointer(13,p.first+20,p.second-50)))
                val model=ViewModelProvider(activity)[ControllerViewModel::class.java]
                assertTrue(model.state.value!!.engine.previewChannels[2]>1100)
                send(root,time,time+20,MotionEvent.ACTION_CANCEL,listOf(Pointer(13,p.first+20,p.second-50)))
                assertNull(left.model.pointer)
                assertEquals(1100,model.state.value!!.engine.previewChannels[2])
                val stop=descendants(root).filterIsInstance<Button>().first { it.text.toString()=="DỪNG PHÁT ĐIỀU KHIỂN" }
                assertTrue(stop.performClick())
                assertFalse(model.state.value!!.engine.enabled)
                assertFalse(model.state.value!!.engine.benchConditions.complete)
                assertEquals(0L,model.state.value!!.txOverrides)
            }
        }
    }

    @Test fun orientationChangeDoesNotRestoreThrottleOrGrantNetworkControl() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(Configuration.ORIENTATION_LANDSCAPE,activity.resources.configuration.orientation)
                val model=ViewModelProvider(activity)[ControllerViewModel::class.java]
                val snapshot=model.state.value!!
                assertFalse(snapshot.engine.connected)
                assertFalse(snapshot.engine.enabled)
                assertFalse(snapshot.engine.benchConditions.complete)
                assertEquals(1100,snapshot.engine.previewChannels[2])
                assertEquals(0L,snapshot.txOverrides)
                assertTrue(descendants(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString()=="DỪNG PHÁT ĐIỀU KHIỂN" })
            }
        }
    }

    private fun openSticks(scenario:ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val button=descendants(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString()=="TAY CẦN" }
            assertTrue(button.performClick())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    private fun descendants(view:View):Sequence<View> = sequence {
        yield(view)
        if(view is ViewGroup)for(i in 0 until view.childCount)yieldAll(descendants(view.getChildAt(i)))
    }
    private fun center(view:View):Pair<Float,Float> {
        val position=IntArray(2);view.getLocationOnScreen(position)
        return position[0]+view.width/2f to position[1]+view.height/2f
    }
    private data class Pointer(val id:Int,val x:Float,val y:Float)
    private fun send(root:View,down:Long,at:Long,action:Int,pointers:List<Pointer>) {
        val properties=Array(pointers.size){index->MotionEvent.PointerProperties().apply{id=pointers[index].id;toolType=MotionEvent.TOOL_TYPE_FINGER}}
        val coords=Array(pointers.size){index->MotionEvent.PointerCoords().apply{x=pointers[index].x;y=pointers[index].y;pressure=1f;size=1f}}
        MotionEvent.obtain(down,at,action,pointers.size,properties,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0).let { event ->
            try { assertTrue("Touch event must reach Android view hierarchy",root.dispatchTouchEvent(event)) }
            finally { event.recycle() }
        }
    }
}
