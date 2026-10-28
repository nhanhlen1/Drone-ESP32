package dev.drone.controller.ui

import org.junit.Assert.*
import org.junit.Test

class JoystickModelTest {
    @Test fun twoThumbsRetainIndependentPointerIds(){
        val left=JoystickModel(true);val right=JoystickModel(false)
        left.begin(7,0f,0f);right.begin(11,0f,0f)
        left.move(7,.4f,.7f);right.move(11,.6f,-.2f)
        val oldLeft=left.value;val oldRight=right.value
        left.move(11,-1f,-1f);right.move(7,-1f,-1f)
        assertEquals(oldLeft,left.value);assertEquals(oldRight,right.value)
        right.end(7);assertEquals(11,right.pointer)
        left.end(7);assertNull(left.pointer);assertEquals(11,right.pointer)
    }
    @Test fun newFingerDoesNotStealStick(){val s=JoystickModel(false);s.begin(1,.2f,.2f);val v=s.value;s.begin(2,1f,1f);assertEquals(1,s.pointer);assertEquals(v,s.value)}
    @Test fun throttleDoesNotJumpToTouchPosition(){val s=JoystickModel(true);s.begin(4,0f,.9f);assertEquals(0f,s.value.vertical,0f);s.move(4,0f,1f);assertEquals(.05f,s.value.vertical,.0001f)}
    @Test fun throttleHoldsOnReleaseButResetsForNewSession(){val s=JoystickModel(true);s.begin(1,0f,-1f);s.move(1,0f,1f);assertEquals(1f,s.value.vertical,0f);s.end(1);assertEquals(1f,s.value.vertical,0f);assertEquals(0f,s.value.horizontal,0f);s.reset();assertEquals(0f,s.value.vertical,0f);s.begin(2,0f,1f);assertEquals(0f,s.value.vertical,0f)}
    @Test fun rollPitchYawReturnToCentre(){val s=JoystickModel(false);s.begin(3,1f,1f);assertTrue(s.value.horizontal>0);s.end(3);assertEquals(StickValue(),s.value)}
    @Test fun deadzoneAndClamping(){val s=JoystickModel(false);s.begin(0,.03f,-.03f);assertEquals(StickValue(),s.value);s.move(0,50f,50f);assertTrue(s.value.horizontal in 0f..1f);assertTrue(s.value.vertical in 0f..1f);assertTrue(s.value.horizontal*s.value.horizontal+s.value.vertical*s.value.vertical<=1f)}
    @Test fun nonFiniteInputCannotEnterPreview(){val s=JoystickModel(false);s.begin(4,0f,0f);s.move(4,Float.NaN,1f);s.move(4,1f,Float.POSITIVE_INFINITY);assertEquals(StickValue(),s.value)}
    @Test fun mode2AssignsAxesAndForwardPitchSign(){val v=Mode2Mapping.from(StickValue(.3f,.8f),StickValue(.2f,.4f));assertEquals(.2f,v.roll,0f);assertEquals(-.4f,v.pitch,0f);assertEquals(.8f,v.throttle,0f);assertEquals(.3f,v.yaw,0f)}
    @Test fun invalidTouchCannotPoisonThrottleSession(){val s=JoystickModel(true);s.begin(3,0f,Float.NaN);assertNull(s.pointer);s.begin(4,0f,0f);s.move(4,0f,1f);assertEquals(.5f,s.value.vertical,0f)}
    @Test fun invalidDeadzoneRejected(){val s=JoystickModel(false);assertThrows(IllegalArgumentException::class.java){s.deadzone=.99f};assertThrows(IllegalArgumentException::class.java){s.deadzone=Float.NaN}}
}
