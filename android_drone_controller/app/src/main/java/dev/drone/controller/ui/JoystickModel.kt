package dev.drone.controller.ui

import kotlin.math.abs
import kotlin.math.sqrt

data class StickValue(val horizontal:Float=0f,val vertical:Float=0f)
/** Pointer ownership and Mode2 axis mapping are independent of rendering. */
class JoystickModel(val throttleStick:Boolean) {
    var pointer:Int?=null; private set
    var value=StickValue(0f,if(throttleStick)0f else 0f); private set
    var deadzone=.06f
        set(next){require(next.isFinite()&&next in 0f..0.3f);field=next}
    private var downY=0f
    private var downThrottle=0f
    fun begin(id:Int,x:Float,y:Float):StickValue {
        if(pointer!=null || !x.isFinite() || !y.isFinite())return value
        pointer=id;downY=y;downThrottle=value.vertical
        return move(id,x,y)
    }
    fun move(id:Int,x:Float,y:Float):StickValue {
        if(pointer!=id || !x.isFinite() || !y.isFinite())return value
        var horizontal=x.coerceIn(-1f,1f)
        var vertical=y.coerceIn(-1f,1f)
        if(throttleStick)vertical=(downThrottle+(y-downY)/2f).coerceIn(0f,1f)
        else {
            val length=sqrt(horizontal*horizontal+vertical*vertical)
            if(length>1){horizontal/=length;vertical/=length}
        }
        value=StickValue(curve(horizontal),if(throttleStick)vertical else curve(vertical))
        return value
    }
    fun end(id:Int):StickValue {
        if(pointer!=id)return value
        pointer=null;value=StickValue(0f,if(throttleStick)value.vertical else 0f)
        return value
    }
    fun reset():StickValue {pointer=null;value=StickValue();return value}
    private fun curve(v:Float):Float {
        if(abs(v)<=deadzone)return 0f
        val n=(abs(v)-deadzone)/(1f-deadzone)
        val sign=if(v<0)-1f else 1f
        return sign*(.75f*n+.25f*n*n*n)
    }
}

data class Mode2Input(val roll:Float=0f,val pitch:Float=0f,val throttle:Float=0f,val yaw:Float=0f)
object Mode2Mapping {
    /** Screen up means forward pitch, ArduPilot RC pitch is inverse. */
    fun from(left:StickValue,right:StickValue)=Mode2Input(right.horizontal,-right.vertical,left.vertical,left.horizontal)
}
