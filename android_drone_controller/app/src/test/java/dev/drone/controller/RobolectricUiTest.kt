package dev.drone.controller

import android.content.res.Configuration
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.lifecycle.ViewModelProvider
import dev.drone.controller.ui.StickView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Host Android framework checks. Not an emulator, device or ESP32 acceptance test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w480dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class RobolectricUiTest {
    @Test fun actualActivityStartsWithReadOnlyDisconnectedStateAndLockedArmButtons() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val activity=controller.get();val root=activity.window.decorView
            layout(root);idle()
            val model=ViewModelProvider(activity)[ControllerViewModel::class.java]
            assertFalse(model.state.value!!.engine.connected)
            assertFalse(model.state.value!!.engine.enabled)
            assertEquals(1100,model.state.value!!.engine.previewChannels[2])
            val armButtons=descendants(root).filterIsInstance<Button>().filter { it.text.toString() in setOf("ARM ĐANG KHÓA","DISARM ĐANG KHÓA") }.toList()
            assertEquals(2,armButtons.size)
            assertTrue(armButtons.none { it.isEnabled })
            assertEquals(0L,model.state.value!!.txOverrides)
        } finally { controller.pause().stop().destroy();idle() }
    }

    @Test fun androidViewDispatchKeepsTwoPointerOwnersIndependentAndMapsAllFourChannels() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val activity=controller.get();val root=activity.window.decorView
            layout(root);idle();open(root,"TAY CẦN");layout(root);idle()
            val sticks=descendants(root).filterIsInstance<StickView>().filter { it.isShown }.toList()
            assertEquals(2,sticks.size)
            val left=sticks.first { it.contentDescription.toString().startsWith("Cần trái") }
            val right=sticks.first { it.contentDescription.toString().startsWith("Cần phải") }
            val a=center(left);val b=center(right);val time=SystemClock.uptimeMillis()
            send(root,time,time,MotionEvent.ACTION_DOWN,listOf(Pointer(7,a.first,a.second)))
            send(root,time,time+10,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(Pointer(7,a.first,a.second),Pointer(9,b.first,b.second)))
            val moving=listOf(Pointer(7,a.first+25,a.second-35),Pointer(9,b.first+35,b.second-25))
            send(root,time,time+20,MotionEvent.ACTION_MOVE,moving);idle()
            assertEquals(7,left.model.pointer);assertEquals(9,right.model.pointer)
            val model=ViewModelProvider(activity)[ControllerViewModel::class.java]
            val channels=model.state.value!!.engine.previewChannels
            assertTrue(channels[0]>1500);assertTrue(channels[1]<1500)
            assertTrue(channels[2]>1100);assertTrue(channels[3]>1500)
            send(root,time,time+30,MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),moving)
            assertEquals(7,left.model.pointer);assertNull(right.model.pointer)
            send(root,time,time+40,MotionEvent.ACTION_UP,listOf(moving.first()));idle()
            val released=model.state.value!!.engine.previewChannels
            assertEquals(1500,released[0]);assertEquals(1500,released[1]);assertEquals(channels[2],released[2]);assertEquals(1500,released[3])
            assertFalse(model.state.value!!.engine.enabled)
            assertEquals(0L,model.state.value!!.txOverrides)
        } finally { controller.pause().stop().destroy();idle() }
    }

    @Test fun androidTouchCancelAndStopClearOfflineThrottleWithoutNetworkConnection() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val activity=controller.get();val root=activity.window.decorView
            layout(root);idle();open(root,"TAY CẦN");layout(root);idle()
            val left=descendants(root).filterIsInstance<StickView>().first { it.isShown && it.contentDescription.toString().startsWith("Cần trái") }
            val p=center(left);val time=SystemClock.uptimeMillis()
            send(root,time,time,MotionEvent.ACTION_DOWN,listOf(Pointer(13,p.first,p.second)))
            send(root,time,time+10,MotionEvent.ACTION_MOVE,listOf(Pointer(13,p.first+20,p.second-50)));idle()
            val model=ViewModelProvider(activity)[ControllerViewModel::class.java]
            assertTrue(model.state.value!!.engine.previewChannels[2]>1100)
            send(root,time,time+20,MotionEvent.ACTION_CANCEL,listOf(Pointer(13,p.first+20,p.second-50)));idle()
            assertNull(left.model.pointer)
            assertEquals(1100,model.state.value!!.engine.previewChannels[2])
            open(root,"DỪNG PHÁT ĐIỀU KHIỂN");idle()
            assertFalse(model.state.value!!.engine.enabled)
            assertFalse(model.state.value!!.engine.benchConditions.complete)
            assertFalse(model.state.value!!.engine.connected)
            assertEquals(0L,model.state.value!!.txOverrides)
        } finally { controller.pause().stop().destroy();idle() }
    }

    @Test fun lifecycleAndConfigurationChangeDoNotRestoreThrottleOrControlAuthority() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            var activity=controller.get();var model=ViewModelProvider(activity)[ControllerViewModel::class.java]
            model.preview(dev.drone.controller.ui.Mode2Input(throttle=.8f));idle()
            assertTrue(model.state.value!!.engine.previewChannels[2]>1100)
            controller.pause().stop();idle()
            assertEquals(1100,model.state.value!!.engine.previewChannels[2])
            controller.start().resume().visible();idle()
            val configuration=Configuration(activity.resources.configuration).apply {
                orientation=Configuration.ORIENTATION_LANDSCAPE;screenWidthDp=800;screenHeightDp=480
            }
            controller.configurationChange(configuration);idle()
            activity=controller.get();model=ViewModelProvider(activity)[ControllerViewModel::class.java]
            assertEquals(Configuration.ORIENTATION_LANDSCAPE,activity.resources.configuration.orientation)
            assertFalse(model.state.value!!.engine.connected)
            assertFalse(model.state.value!!.engine.enabled)
            assertFalse(model.state.value!!.engine.benchConditions.complete)
            assertEquals(1100,model.state.value!!.engine.previewChannels[2])
            assertEquals(0L,model.state.value!!.txOverrides)
        } finally { controller.pause().stop().destroy();idle() }
    }

    private fun layout(root:View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY))
        root.layout(0,0,480,800)
    }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun open(root:View,text:String) {
        assertTrue(descendants(root).filterIsInstance<Button>().first { it.text.toString()==text }.performClick())
    }
    private fun descendants(view:View):Sequence<View> = sequence {
        yield(view)
        if(view is ViewGroup)for(i in 0 until view.childCount)yieldAll(descendants(view.getChildAt(i)))
    }
    private fun center(view:View):Pair<Float,Float> {
        assertTrue("Stick has measured dimensions",view.width>0 && view.height>0)
        val position=IntArray(2);view.getLocationOnScreen(position)
        return position[0]+view.width/2f to position[1]+view.height/2f
    }
    private data class Pointer(val id:Int,val x:Float,val y:Float)
    private fun send(root:View,down:Long,at:Long,action:Int,pointers:List<Pointer>) {
        val properties=Array(pointers.size){index->MotionEvent.PointerProperties().apply{id=pointers[index].id;toolType=MotionEvent.TOOL_TYPE_FINGER}}
        val coords=Array(pointers.size){index->MotionEvent.PointerCoords().apply{x=pointers[index].x;y=pointers[index].y;pressure=1f;size=1f}}
        val event=MotionEvent.obtain(down,at,action,pointers.size,properties,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
        try { assertTrue(root.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
}
