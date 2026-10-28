package dev.drone.controller.control

import kotlin.math.abs

/** Pure Mode 2 mapping shared by host tests and UI; this never sends network data.
 * Coordinates are already normalized: up is positive for throttle; right is
 * positive roll/yaw. Pitch sign must be verified against the exact RC config.
 * A deliberate throttle-enabled hold is required to change throttle. Releasing
 * a thumb keeps its value until explicit reset/new session; roll/pitch/yaw center.
 */
class JoystickMapping(private val deadzone:Float=0.06f) {
    init { require(deadzone.isFinite() && deadzone in 0f..0.3f) }
    private var roll=0f
    private var pitch=0f
    private var throttle=0f
    private var yaw=0f
    private fun axis(value:Float):Float {
        require(value.isFinite())
        val v=value.coerceIn(-1f,1f)
        return if(abs(v)<=deadzone)0f else (if(v>=0)1f else -1f)*(abs(v)-deadzone)/(1f-deadzone)
    }
    @Synchronized fun left(horizontal:Float,up:Float,throttleEnabled:Boolean) {
        yaw=axis(horizontal)
        require(up.isFinite())
        if(throttleEnabled)throttle=up.coerceIn(0f,1f)
    }
    @Synchronized fun right(horizontal:Float,vertical:Float) { roll=axis(horizontal);pitch=axis(vertical) }
    @Synchronized fun releaseRight(){roll=0f;pitch=0f}
    @Synchronized fun releaseLeft(){yaw=0f}
    @Synchronized fun reset(){roll=0f;pitch=0f;throttle=0f;yaw=0f}
    @Synchronized fun values()=floatArrayOf(roll,pitch,throttle,yaw)
}
