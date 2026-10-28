package dev.drone.controller

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.drone.controller.ui.ControllerScreen
import dev.drone.controller.ui.UiActions
import dev.drone.controller.ui.Mode2Input
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity:ComponentActivity() {
    private val model:ControllerViewModel by viewModels { ControllerViewModel.factory(application) }
    private lateinit var screen:ControllerScreen
    private var resumed=false
    private var focused=false
    private val exportLog=registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->
        if(uri!=null) lifecycleScope.launch {
            val outcome=withContext(Dispatchers.IO){runCatching{
                contentResolver.openOutputStream(uri)?.use{out->model.logger.file.inputStream().use{it.copyTo(out)}}
                    ?:error("Không mở được nơi lưu")
            }}
            Toast.makeText(this@MainActivity,if(outcome.isSuccess)"Đã xuất log phiên hiện tại" else "Không xuất được log: ${outcome.exceptionOrNull()?.message}",Toast.LENGTH_LONG).show()
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(Build.VERSION.SDK_INT<30){@Suppress("DEPRECATION") window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)}
        screen=ControllerScreen(this,object:UiActions {
            override fun connect(host:String,port:Int){runCatching{model.connect(host,port)}.onFailure{Toast.makeText(this@MainActivity,it.message?:"Địa chỉ không hợp lệ",Toast.LENGTH_LONG).show()}}
            override fun disconnect(){model.disconnect()}
            override fun stop(reason:String){model.stop(reason)}
            override fun deadman(held:Boolean){model.deadman(held)}
            override fun preview(input:Mode2Input){model.preview(input)}
            override fun configure(channel:Int,min:Int,max:Int,trim:Int,verified:Boolean){model.configure(channel,min,max,trim,verified)}
            override fun startBench(props:Boolean,esc:Boolean,authorised:Boolean,criticalRcDiagnosticAuthorised:Boolean){model.startBench(props,esc,authorised,criticalRcDiagnosticAuthorised)}
            override fun testValue(value:Float){model.testValue(value)}
            override fun openWifi(){startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))}
            override fun logDescription()="CSV: ${model.logger.file.name}\nDòng log bị bỏ: ${model.logger.dropped.get()} • Lỗi ghi: ${model.logger.error?:"Không"}"
            override fun export(){model.stop("Xuất log: đã dừng quyền phát RC");exportLog.launch(model.logger.file.name)}
        })
        setContentView(screen)
        screen.setOnApplyWindowInsetsListener{view,insets->
            val side=(16*resources.displayMetrics.density).toInt()
            if(Build.VERSION.SDK_INT>=30){
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime=insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(side+bars.left,bars.top+8,side+bars.right,maxOf(bars.bottom,ime.bottom)+8)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(side+insets.systemWindowInsetLeft,insets.systemWindowInsetTop+8,side+insets.systemWindowInsetRight,insets.systemWindowInsetBottom+8)
            }
            insets
        }
        screen.requestApplyInsets()
        lifecycleScope.launch {repeatOnLifecycle(Lifecycle.State.STARTED){model.state.collect{it?.let(screen::render)}}}
    }
    override fun onResume(){super.onResume();resumed=true;model.focus(focused)}
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);focused=hasFocus;model.focus(resumed&&focused);if(!hasFocus&&::screen.isInitialized)screen.resetInputs()}
    override fun onPause(){resumed=false;model.background();if(::screen.isInitialized)screen.resetInputs();super.onPause()}
    override fun onDestroy(){if(::screen.isInitialized)screen.dispose();super.onDestroy()}
}
