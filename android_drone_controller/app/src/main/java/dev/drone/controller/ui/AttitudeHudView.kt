package dev.drone.controller.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.PI

/** The horizon is drawn only from a fresh ATTITUDE sample, never an invented zero. */
class AttitudeHudView(context:Context):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var roll:Float?=null
    private var pitch:Float?=null
    private var yaw:Float?=null
    private var altitude:String="— m"
    private var voltage:String="— V"
    private var mode:String="CHƯA CÓ FC"
    init {minimumHeight=dp(116);contentDescription="HUD: chưa có dữ liệu attitude mới"}
    private fun dp(n:Int)=n*resources.displayMetrics.density
    fun render(rollRad:Float?,pitchRad:Float?,yawRad:Float?,height:String,battery:String,modeName:String) {
        roll=rollRad?.takeIf{it.isFinite()};pitch=pitchRad?.takeIf{it.isFinite()};yaw=yawRad?.takeIf{it.isFinite()}
        altitude=height;voltage=battery;mode=modeName
        contentDescription=if(roll!=null&&pitch!=null)"HUD: roll ${(roll!!*180/PI).toInt()} độ, pitch ${(pitch!!*180/PI).toInt()} độ, $height, $battery" else "HUD: attitude quá hạn hoặc chưa có dữ liệu"
        invalidate()
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val r=RectF(0f,0f,width.toFloat(),height.toFloat())
        paint.color=Palette.card;paint.style=Paint.Style.FILL;canvas.drawRoundRect(r,dp(12),dp(12),paint)
        val valid=roll!=null&&pitch!=null
        val save=canvas.save();canvas.clipRect(dp(2),dp(2),width-dp(2),height-dp(2))
        if(valid) {
            canvas.translate(width/2f,height/2f)
            canvas.rotate((-roll!!*180/PI).toFloat())
            canvas.translate(0f,(pitch!!*height*.7f).coerceIn(-height.toFloat(),height.toFloat()))
            paint.color=0xff173d59.toInt();canvas.drawRect(-width*2f,-height*3f,width*2f,0f,paint)
            paint.color=0xff503c28.toInt();canvas.drawRect(-width*2f,0f,width*2f,height*3f,paint)
            paint.color=Palette.text;paint.strokeWidth=dp(1);canvas.drawLine(-width*2f,0f,width*2f,0f,paint)
            paint.alpha=110
            for(y in listOf(-.4f,-.2f,.2f,.4f))canvas.drawLine(-width*.18f,y*height,width*.18f,y*height,paint)
            paint.alpha=255
        }
        canvas.restoreToCount(save)
        paint.color=Palette.mint;paint.strokeWidth=dp(2)
        canvas.drawLine(width*.28f,height*.5f,width*.44f,height*.5f,paint)
        canvas.drawLine(width*.56f,height*.5f,width*.72f,height*.5f,paint)
        canvas.drawCircle(width/2f,height/2f,dp(3),paint)
        paint.textAlign=Paint.Align.CENTER;paint.typeface=android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize=dp(10);paint.color=Palette.text
        canvas.drawText(mode,width/2f,dp(15),paint)
        if(!valid){paint.color=Palette.amber;canvas.drawText("CHƯA CÓ ATTITUDE MỚI",width/2f,height*.72f,paint)}
        else {paint.color=Palette.muted;canvas.drawText("HƯỚNG ${((yaw!!*180/PI+360)%360).toInt()}°",width/2f,height*.77f,paint)}
        paint.color=Palette.text;paint.textSize=dp(11)
        canvas.drawText("$altitude  •  $voltage",width/2f,height-dp(10),paint)
    }
}
