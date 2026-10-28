package dev.drone.controller.ui

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.drone.controller.R
import dev.drone.controller.control.ArmState
import dev.drone.controller.control.SystemHealth
import dev.drone.controller.mavlink.Telemetry
import dev.drone.controller.transport.SessionSnapshot
import java.util.Locale

interface UiActions {
    fun connect(host:String,port:Int)
    fun disconnect()
    fun stop(reason:String)
    fun deadman(held:Boolean)
    fun preview(input:Mode2Input)
    fun configure(channel:Int,min:Int,max:Int,trim:Int,verified:Boolean)
    fun startBench(props:Boolean,esc:Boolean,authorised:Boolean,criticalRcDiagnosticAuthorised:Boolean=false)
    fun testValue(value:Float)
    fun openWifi()
    fun export()
    fun logDescription():String
}

/** Native views; per-view pointer splitting keeps two thumb inputs independent. */
class ControllerScreen(private val activity:Activity,private val actions:UiActions):LinearLayout(activity) {
    private val pages=FrameLayout(activity)
    private val nav=LinearLayout(activity)
    private val navButtons=mutableListOf<Button>()
    private val status=label("Chưa kết nối",13,Palette.muted)
    private val identity=label("Chưa nhận trạng thái flight controller",11,Palette.muted)
    private val healthWarning=label("Sức khỏe FC: chưa có dữ liệu",11,Palette.amber)
    private val safety=label("ĐANG KHÓA • Chưa có quyền phát RC",12,Palette.amber)
    private val host=input("192.168.4.1",false)
    private val port=input("5760",true)
    private val connection=label("",13)
    private val telemetry=label("Chưa có dữ liệu",14)
    private val diagnostics=label("Chưa có dữ liệu",12)
    private val parameterText=label("Chưa nhận tham số từ FC",12,Palette.muted)
    private val firmwareText=label("Chưa nhận phiên bản firmware",12,Palette.muted)
    private val events=label(tr(R.string.no_events),11,Palette.muted)
    private val stickLeft=StickView(activity,true)
    private val stickRight=StickView(activity,false)
    private val preview=label("CH1 1500 • CH2 1500 • CH3 1100 • CH4 1500 µs",12,Palette.mint)
    private val benchState=label("Chưa xác nhận điều kiện kiểm thử",13,Palette.amber)
    private val benchFeedback=label("Chưa phát gói thử; chưa có bằng chứng áp dụng RC",12,Palette.muted)
    private val props=check("Tôi đã tháo toàn bộ cánh quạt")
    private val esc=check("Tôi đã ngắt vật lý nguồn ESC; động lực không thể hoạt động")
    private val consent=check("Tôi cho phép riêng phép thử CH1 ±20 µs, tối đa 750 ms")
    private val criticalConsent=check("Tôi cho phép riêng chẩn đoán CH1 khi FC báo CRITICAL và chưa có RC; nguyên nhân chưa được xác minh đầy đủ. Tôi đã đọc cảnh báo, nguồn ESC đã ngắt.").apply{visibility=GONE}
    private val criticalWarning=label("",12,Palette.red).apply{visibility=GONE}
    private val deadman=DeadmanView(activity)
    private val armBench=button("CHO PHÉP MỘT PHÉP THỬ CH1")
    private val testSlider=SeekBar(activity)
    private val testLabel=label("CH1: tại trim (+0 µs)",12)
    private var disposed=false
    private var lastEnabled=false
    private var activePage=0
    private var snapshot:SessionSnapshot?=null
    private val channelEditors=mutableListOf<ChannelEditor>()

    init {
        orientation=VERTICAL;setBackgroundColor(Palette.background)
        isMotionEventSplittingEnabled=true
        addView(label("ESP32 / DRONE",21,Palette.mint).apply{typeface=Typeface.DEFAULT_BOLD})
        addView(label("BỘ ĐIỀU KHIỂN • 0.2.1 • KIỂM THỬ TRÊN BÀN",10,Palette.muted))
        addView(status,full(8));addView(identity);addView(healthWarning);addView(safety,full(7))
        nav.orientation=HORIZONTAL
        val navScroll=HorizontalScrollView(activity).apply{isHorizontalScrollBarEnabled=false;addView(nav)}
        addView(navScroll,full(8))
        listOf("KẾT NỐI","TAY CẦN","TELEMETRY","KIỂM TRA RC","CẤU HÌNH","CHẨN ĐOÁN","HƯỚNG DẪN").forEachIndexed{i,title->
            val b=button(title).apply{textSize=10f;setPadding(dp(12),0,dp(12),0);setOnClickListener{show(i)}}
            nav.addView(b,LayoutParams(LayoutParams.WRAP_CONTENT,dp(44)).apply{rightMargin=dp(6)});navButtons+=b
        }
        addView(pages,LayoutParams(LayoutParams.MATCH_PARENT,0,1f))
        val content=listOf(connectionPage(),controllerPage(),telemetryPage(),benchPage(),configPage(),diagnosticsPage(),helpPage())
        content.forEach{body->pages.addView(ScrollView(activity).apply{isFillViewport=true;addView(body)})}
        addView(button("DỪNG PHÁT ĐIỀU KHIỂN",Palette.red).apply{setOnClickListener{actions.stop("Người dùng bấm DỪNG");resetInputs()}},full(8))
        addView(label("STOP thu hồi quyền phát của app; dừng động lực cần cơ chế độc lập Wi-Fi.",10,Palette.muted))
        show(0,false)
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun full(margin:Int=0)=LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply{topMargin=dp(margin)}
    private fun weight()=LayoutParams(0,LayoutParams.WRAP_CONTENT,1f)
    private fun label(value:String,size:Int=13,color:Int=Palette.text)=TextView(activity).apply{text=value;textSize=size.toFloat();setTextColor(color);setLineSpacing(dp(3).toFloat(),1f)}
    private fun column()=LinearLayout(activity).apply{orientation=VERTICAL;isMotionEventSplittingEnabled=true;setPadding(0,dp(8),0,dp(12))}
    private fun row()=LinearLayout(activity).apply{orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;isMotionEventSplittingEnabled=true}
    private fun background(color:Int=Palette.card)=GradientDrawable().apply{setColor(color);cornerRadius=dp(14).toFloat();setStroke(dp(1),Palette.line)}
    private fun card(parent:LinearLayout,title:String):LinearLayout {
        val box=column().apply{background=background();setPadding(dp(14),dp(12),dp(14),dp(12))}
        box.addView(label(title,12,Palette.mint).apply{typeface=Typeface.DEFAULT_BOLD},full())
        parent.addView(box,full(10));return box
    }
    private fun button(title:String,color:Int=Palette.mint)=Button(activity).apply {
        text=title;textSize=12f;isAllCaps=false;minimumHeight=dp(48);minHeight=dp(48)
        setTextColor(color);background=background();setPadding(dp(10),dp(5),dp(10),dp(5))
    }
    private fun input(value:String,numeric:Boolean)=EditText(activity).apply {
        setText(value);textSize=14f;setTextColor(Palette.text);setHintTextColor(Palette.muted);setSingleLine(true)
        inputType=if(numeric)InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        minimumHeight=dp(48)
    }
    private fun check(title:String)=CheckBox(activity).apply{text=title;textSize=12f;setTextColor(Palette.text);minHeight=dp(48)}
    private fun connectionPage()=column().also{page->
        card(page,"01 / KẾT NỐI WI-FI").apply {
            addView(label("Chọn Wi-Fi ESP32_DRONE trong cài đặt điện thoại. Khi Android báo không có Internet, giữ kết nối Wi-Fi này.",14),full(8))
            addView(button("MỞ CÀI ĐẶT WI-FI").apply{setOnClickListener{actions.openWifi()}},full(10))
            addView(label("App không lưu mật khẩu Wi-Fi. Socket được gắn vào mạng Wi-Fi, kể cả khi dữ liệu di động đang bật.",11,Palette.muted),full(8))
        }
        card(page,"02 / TCP → MAVLINK 2").apply {
            val fields=row();fields.addView(host,LayoutParams(0,dp(52),2f));fields.addView(port,LayoutParams(0,dp(52),1f));addView(fields)
            val controls=row()
            controls.addView(button("KẾT NỐI").apply{setOnClickListener{val p=port.text.toString().toIntOrNull();if(p==null||p !in 1..65535)Toast.makeText(activity,"Cổng phải từ 1 đến 65535",Toast.LENGTH_LONG).show() else{resetInputs();actions.connect(host.text.toString().trim(),p)}}},weight())
            controls.addView(button("NGẮT",Palette.amber).apply{setOnClickListener{actions.disconnect();resetInputs()}},weight());addView(controls,full(8));addView(connection,full(12));addView(firmwareText,full(8))
        }
        card(page,"MỨC XÁC MINH").addView(label("Wi-Fi → TCP → HEARTBEAT hợp lệ → telemetry. Mỗi bước có bằng chứng riêng. TCP kết nối không có nghĩa RC đã được flight controller nhận hoặc áp dụng.",13),full(8))
    }
    private fun controllerPage()=column().also{page->
        card(page,"TAY CẦN MODE 2 • XEM TRƯỚC NGOẠI TUYẾN").apply {
            addView(label("Đường điều khiển bay đang khóa. Hai cần dưới đây chỉ xem mapping; không phát RC. Phép thử CH1 nằm riêng ở tab KIỂM TRA RC.",12,Palette.amber),full(8))
            val titles=row();titles.addView(label("TRÁI / GA · YAW",11,Palette.muted),weight());titles.addView(label("PHẢI / PITCH · ROLL",11,Palette.muted),weight());addView(titles,full(14))
            val sticks=row();sticks.isMotionEventSplittingEnabled=true
            sticks.addView(stickLeft,LayoutParams(0,dp(190),1f));sticks.addView(stickRight,LayoutParams(0,dp(190),1f));addView(sticks,full(4))
            fun update(){actions.preview(Mode2Mapping.from(stickLeft.model.value,stickRight.model.value))}
            stickLeft.onValue={update()};stickRight.onValue={update()}
            addView(preview,full(8))
            addView(label("Ga thay đổi tương đối từ điểm chạm, giữ vị trí khi nhấc tay. Mỗi phiên mới/STOP/mất focus đưa ga về thấp nhất. Roll, pitch, yaw tự về giữa.",11,Palette.muted),full(8))
            addView(button("ĐẶT GA THẤP NHẤT").apply{setOnClickListener{resetInputs();update()}},full(10))
        }
        card(page,"ĐIỀU KHIỂN ĐỘNG LỰC / CHẾ ĐỘ").apply {
            val controls=row();listOf("ARM ĐANG KHÓA","DISARM ĐANG KHÓA").forEach{title->controls.addView(button(title,Palette.muted).apply{isEnabled=false;alpha=.5f},weight())};addView(controls,full(8))
            addView(label("Chưa nghiệm thu nguồn động lực, failsafe và RC input: không bật ARM/DISARM hoặc đổi chế độ. Không tự mở khóa sau phép thử CH1.",12,Palette.amber),full(8))
        }
    }
    private fun telemetryPage()=column().also{page->
        card(page,"TELEMETRY TỪ FLIGHT CONTROLLER").apply{addView(telemetry,full(12))}
        card(page,"ĐƠN VỊ VÀ GIỚI HẠN ĐO").addView(label("Không nội suy dữ liệu thiếu. INA219 hiện chỉ được xác minh điện áp: dòng, phần trăm pin và thời gian còn lại không được dùng làm bằng chứng vận hành. Áp suất không tự chứng minh độ cao khí áp. Không có dữ liệu EKF thì hiển thị chưa có dữ liệu.",12,Palette.muted),full(8))
    }
    private fun benchPage()=column().also{page->
        card(page,"CHẨN ĐOÁN RC • KHÔNG KÍCH HOẠT ĐỘNG CƠ").apply {
            addView(benchState,full(8));addView(benchFeedback,full(8))
            addView(label("Bắt đầu bằng chẩn đoán chỉ đọc. Phép thử dưới đây không cần RC_CHANNELS ban đầu, nhưng bắt buộc HEARTBEAT DISARMED mới, CH1 đã đối chiếu và ba xác nhận riêng. Chỉ CH1 ±20 µs; CH2–CH18 bỏ qua. Không thử ga, yaw hay flight mode.",12),full(10))
            addView(props);addView(esc);addView(consent);addView(criticalWarning,full(8));addView(criticalConsent)
            armBench.setOnClickListener{actions.startBench(props.isChecked,esc.isChecked,consent.isChecked,criticalConsent.isChecked)};addView(armBench,full(8))
            testSlider.max=200;testSlider.progress=100
            testSlider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar?,value:Int,fromUser:Boolean){testLabel.text=tr(R.string.ch1_offset, if(value>=100)"+" else "", ((value-100)*.2f).toInt());if(fromUser)actions.testValue((value-100)/100f)}
                override fun onStartTrackingTouch(bar:SeekBar?){}
                override fun onStopTrackingTouch(bar:SeekBar?){}
            })
            addView(testLabel,full(10));addView(testSlider,full(2));deadman.onHeld={actions.deadman(it)};addView(deadman,full(8))
            addView(label("Phải giữ deadman độc lập; thả tay để dừng ngay. Hết 750 ms quyền tự thu hồi kể cả khi có phản hồi khớp. Không phát lại khi reconnect. RC_CHANNELS không phải ACK của override.",11,Palette.muted),full(10))
        }
    }
    private fun configPage()=column().also{page->
        card(page,"CẤU HÌNH ỨNG DỤNG • KHÔNG GHI THAM SỐ FC").apply{addView(label("Giá trị dự kiến MIN/TRIM/MAX = 1100/1500/1900 µs theo thông tin đã cung cấp. Đối chiếu lại với FC rồi xác nhận. Các cờ xác nhận bị xóa sau kết nối mới.",12),full(8))}
        (1..4).forEach{channel->
            val box=card(page,"RC$channel / ${listOf("ROLL","PITCH","GA","YAW")[channel-1]}")
            val minimum=input("1100",true);val trim=input("1500",true);val maximum=input("1900",true)
            val fields=row();listOf("MIN" to minimum,"TRIM" to trim,"MAX" to maximum).forEach{(title,edit)->val col=column();col.addView(label(title,10,Palette.muted));col.addView(edit,full());fields.addView(col,weight())};box.addView(fields)
            val verified=check("Tôi đã đối chiếu kênh RC$channel với tham số FC")
            val readback=label("Chưa xác nhận",11,Palette.amber);box.addView(verified);box.addView(readback)
            box.addView(button("LƯU RC$channel TRONG APP").apply{setOnClickListener{
                val low=minimum.text.toString().toIntOrNull();val mid=trim.text.toString().toIntOrNull();val high=maximum.text.toString().toIntOrNull()
                if(low==null||mid==null||high==null||low !in 900..2100||high !in 900..2100||mid<=low||mid>=high)Toast.makeText(activity,"RC không hợp lệ: 900–2100 µs và MIN < TRIM < MAX",Toast.LENGTH_LONG).show()
                else actions.configure(channel,low,high,mid,verified.isChecked)
            }},full(8))
            channelEditors+=ChannelEditor(verified,readback)
        }
        card(page,"THAM SỐ ĐỌC TỪ FC • CHỈ ĐỌC").apply {addView(parameterText,full(8))}
        card(page,"PHƯƠNG THỨC / NHẬT KÝ").apply {
            addView(label("Phương thức đang chọn: RC_CHANNELS_OVERRIDE. MANUAL_CONTROL chỉ được kiểm thử byte-level ngoại tuyến, không tự chuyển phương thức. Deadzone 6%, xem trước không vượt biên. Nhịp thử tối đa 20 Hz; thời hạn được kiểm soát bởi engine.",12),full(8))
            addView(button("XUẤT CSV NHẬT KÝ PHIÊN").apply{setOnClickListener{actions.export()}},full(10))
        }
    }
    private fun diagnosticsPage()=column().also{page->
        card(page,"ĐƯỜNG TRUYỀN / MAVLINK").addView(diagnostics,full(10))
        card(page,"BỐN MỨC BẰNG CHỨNG").addView(label("1. App tạo gói\n2. Ghi đủ byte vào socket\n3. FC chấp nhận: chưa có ACK cho RC override\n4. FC áp dụng: cần bằng chứng độc lập từ FC/log/USB.\n\nPhản hồi PWM khớp chỉ là tương quan telemetry; không tự nâng thành đã nghiệm thu.",12,Palette.amber),full(8))
        card(page,"SỰ KIỆN GẦN NHẤT").apply{addView(events,full(8));addView(button("XUẤT LOG ĐỂ PHÂN TÍCH").apply{setOnClickListener{actions.export()}},full(10))}
    }
    private fun helpPage()=column().also{page->
        card(page,"QUY TRÌNH ĐẦU TIÊN").addView(label("1. Cài APK dev.drone.controller cạnh app cũ.\n2. Tháo cánh, ngắt vật lý nguồn ESC. Chưa phát RC.\n3. Chọn Wi-Fi ESP32_DRONE và giữ mạng không Internet.\n4. Kết nối TCP, đợi HEARTBEAT DISARMED từ đúng SYS/COMP.\n5. Xem telemetry, tham số và chẩn đoán chỉ đọc.\n6. Đối chiếu RC1 MIN/TRIM/MAX, lưu xác nhận trong app.\n7. Chỉ sau mô phỏng đạt và được phép riêng: xác nhận ba điều kiện, bật phép thử CH1, giữ deadman. Nếu FC báo CRITICAL, mặc định khóa; chỉ có thể xét chẩn đoán CH1 khi engine xác minh điều kiện RC hẹp và người dùng xác nhận riêng cảnh báo CRITICAL. CRITICAL không có nghĩa an toàn.\n8. Thu log phía FC độc lập để xác minh áp dụng và timeout. Không bật ARM hoặc động cơ.",13),full(8))
        card(page,"MẤT KẾT NỐI / KHÔI PHỤC").addView(label("STOP, chuyển tab, background, khóa màn hình, mất focus, mất Wi-Fi, HEARTBEAT stale hoặc CRC lỗi sẽ thu hồi quyền thử. Không gửi lại ga cũ và không tự reconnect. Sau sự cố, kiểm tra trạng thái FC và timeout thực tế rồi kết nối thủ công. Không coi một gói neutral là bảo đảm an toàn.",13),full(8))
        card(page,"THU BẰNG CHỨNG").addView(label("Xuất CSV qua trình lưu tài liệu Android, hoặc ADB debug:\nadb shell run-as dev.drone.controller ls files/logs\nadb exec-out run-as dev.drone.controller cat files/logs/TEN_FILE.csv > controller.csv\n\nLog được giữ nội bộ, không gửi lên máy chủ. Kiểm tra dropped/error trong chẩn đoán. Kết quả chưa đo trên thiết bị thật được ghi NOT VERIFIED.",12,Palette.muted),full(8))
        card(page,"NGUỒN ĐỘNG LỰC VÀ FAILSAFE").addView(label("Ứng dụng không thay thế dừng khẩn cấp độc lập Wi-Fi. RC_OVERRIDE_TIME, radio/GCS failsafe, reset ESP32, socket half-open và nguồn điện thoại phải được đo riêng. Bench PASS không mở khóa ARM, chạy motor hoặc bay. Không sửa ARMING_CHECK hay failsafe để vượt lỗi.",12,Palette.amber),full(8))
    }
    private data class ChannelEditor(val checked:CheckBox,val status:TextView)
    private fun show(index:Int,notify:Boolean=true){
        if(disposed||index !in 0 until pages.childCount)return
        if(notify&&index!=activePage){actions.stop("Chuyển trang: thu hồi quyền thử RC");resetInputs()}
        activePage=index
        for(i in 0 until pages.childCount)pages.getChildAt(i).visibility=if(i==index)VISIBLE else GONE
        navButtons.forEachIndexed{i,b->b.setTextColor(if(i==index)Palette.background else Palette.muted);b.background=background(if(i==index)Palette.mint else Palette.card)}
    }
    fun resetInputs(){stickLeft.reset();stickRight.reset();deadman.release();props.isChecked=false;esc.isChecked=false;consent.isChecked=false;criticalConsent.isChecked=false;testSlider.progress=100}
    fun dispose(){disposed=true;stickLeft.onValue=null;stickRight.onValue=null;deadman.dispose()}
    private fun tr(@androidx.annotation.StringRes id:Int,vararg args:Any)=activity.getString(id,*args)
    private fun armName(arm:ArmState)=when(arm){ArmState.UNKNOWN->tr(R.string.arm_unknown);ArmState.DISARMED->"DISARMED";ArmState.ARMED->tr(R.string.arm_monitoring)}
    private fun number(n:Double,digits:Int=2)=String.format(Locale.US,"%.${digits}f",n)
    private fun age(value:Long?)=value?.let{"$it ms"}?:"Chưa có dữ liệu"
    private fun channels(values:IntArray?)=values?.take(4)?.joinToString(" / ")?:"Chưa có dữ liệu"
    fun render(s:SessionSnapshot){
        if(disposed)return
        val e=s.engine
        val previous=snapshot?.engine
        if(previous!=null&&previous.connected!=e.connected)resetInputs()
        if(previous?.systemHealth!=e.systemHealth)criticalConsent.isChecked=false
        snapshot=s
        status.text=s.tcpStatus
        identity.text=tr(R.string.vehicle_identity, e.targetSystem?:"—", e.targetComponent?:"—", armName(e.arm), age(e.heartbeatAgeMs))
        identity.setTextColor(if(e.arm==ArmState.DISARMED&&e.systemHealth in setOf(SystemHealth.STANDBY,SystemHealth.ACTIVE))Palette.mint else Palette.amber)
        healthWarning.text=tr(R.string.health_status, healthName(e.systemHealth), if(e.systemHealth==SystemHealth.CRITICAL)tr(R.string.health_critical_suffix) else "")
        healthWarning.setTextColor(if(e.systemHealth==SystemHealth.CRITICAL||e.systemHealth==SystemHealth.EMERGENCY)Palette.red else Palette.amber)
        safety.text=e.reason
        connection.text=tr(R.string.connection_status, if(e.connected)"Đã kết nối" else "Chưa kết nối", age(e.heartbeatAgeMs), if(e.targetSystem!=null)"Đã nhận autopilot" else "Chưa xác minh")
        preview.text=tr(R.string.joystick_preview, channels(e.previewChannels))
        benchState.text=tr(R.string.bench_state, stateName(e.state.name), healthName(e.systemHealth), e.reason)
        val critical=e.systemHealth==SystemHealth.CRITICAL
        criticalConsent.visibility=if(critical)VISIBLE else GONE
        criticalWarning.visibility=if(critical)VISIBLE else GONE
        criticalConsent.isEnabled=critical&&e.criticalRcDiagnosticEligible&&!e.enabled
        criticalWarning.text=if(e.criticalRcDiagnosticActive)tr(R.string.critical_diagnostic_active) else if(e.criticalRcDiagnosticEligible)tr(R.string.critical_diagnostic_eligible) else tr(R.string.critical_diagnostic_blocked)
        benchFeedback.text=tr(R.string.bench_feedback, channels(e.received), s.txOverrides, if(e.feedbackCorrelated)"Có tương quan" else "Chưa thấy")
        armBench.isEnabled=!e.enabled&&e.connected&&e.arm==ArmState.DISARMED&&(e.systemHealth in setOf(SystemHealth.STANDBY,SystemHealth.ACTIVE)||e.criticalRcDiagnosticEligible)
        armBench.alpha=if(armBench.isEnabled)1f else .45f
        deadman.isEnabled=e.enabled&&activePage==3
        testSlider.isEnabled=e.enabled&&activePage==3
        if(lastEnabled&&!e.enabled)resetInputs()
        lastEnabled=e.enabled
        e.channels.forEachIndexed{i,c->if(i<channelEditors.size){channelEditors[i].status.text=if(c.verified)tr(R.string.channel_verified, c.minimum, c.trim, c.maximum) else tr(R.string.channel_unverified)}}
        telemetry.text=telemetryText(s)
        diagnostics.text=tr(R.string.diagnostic_values, stateName(e.state.name), s.rxBytes, s.txBytes, e.generatedOverrides, s.txOverrides, number(s.txHz,0), s.validMessages, number(s.messagesPerSecond,0), s.unknownMessages, s.crcErrors, s.parserErrors, e.sequenceGaps, age(s.lastRxAgeMs), age(s.lastTxAgeMs), age(e.heartbeatAgeMs), age(e.rcAgeMs), channels(e.lastSent), channels(e.received), if(e.duplicateIdentity)"Có • bị khóa" else "Chưa phát hiện", healthName(e.systemHealth), e.criticalRcDiagnosticEligible, e.criticalRcDiagnosticActive)
        val pd=s.parserDiagnostics
        val timing=s.rcTiming
        val sources=s.sourceSequences.entries.sortedWith(compareBy({it.key.systemId},{it.key.componentId})).joinToString("\n") { (source,stats) ->
            tr(R.string.source_sequence,source.systemId,source.componentId,stats.sequenceGaps,stats.duplicates,stats.reordered)
        }.ifEmpty{tr(R.string.no_events)}
        fun sample(v:Double?)=v?.let{number(it)}?:"Chưa đủ mẫu"
        diagnostics.append(tr(R.string.protocol_counters,pd.bytesReceived,pd.unsupportedMessages,pd.signedRejected,pd.mavlink1Ignored,pd.partialTimeouts,pd.resynchronizations,
            sample(timing.intervalP50Ms),sample(timing.intervalP95Ms),sample(timing.intervalP99Ms),sample(timing.jitterP95Ms),s.gcsHeartbeatsWritten,sources))
        parameterText.text=e.parameters.entries.sortedBy{it.key}.joinToString("\n"){(key,value)->"$key = ${value.value}"}.ifEmpty{tr(R.string.no_parameters)}
        (e.telemetry["AutopilotVersion"] as? Telemetry.AutopilotVersion)?.let{firmwareText.text=tr(R.string.firmware_version, it.flightSoftwareVersion.toString(16), it.boardVersion, it.uid, it.flightCustomVersion.joinToString(""){byte->"%02x".format(byte)})}
        diagnostics.append(tr(R.string.telemetry_requests, s.streamRequests.joinToString("\n"), s.lastCommandAck?:"Chưa có dữ liệu", actions.logDescription()))
        events.text=e.logs.joinToString("\n").ifEmpty{tr(R.string.no_events)}
    }
    private fun telemetryText(s:SessionSnapshot):String {
        val values=s.engine.telemetry
        fun fresh(key:String,maxAge:Long)=eAge(s,key)?.let{it<maxAge}==true
        val heartbeat=if(fresh("Heartbeat",1500))values["Heartbeat"] as? Telemetry.Heartbeat else null
        val sys=if(fresh("SysStatus",5000))values["SysStatus"] as? Telemetry.SysStatus else null
        val attitude=if(fresh("Attitude",2000))values["Attitude"] as? Telemetry.Attitude else null
        val gps=if(fresh("GpsRawInt",3000))values["GpsRawInt"] as? Telemetry.GpsRawInt else null
        val global=if(fresh("GlobalPosition",3000))values["GlobalPosition"] as? Telemetry.GlobalPosition else null
        val pressure=if(fresh("ScaledPressure",5000))values["ScaledPressure"] as? Telemetry.ScaledPressure else null
        val ekf=if(fresh("EkfStatus",5000))values["EkfStatus"] as? Telemetry.EkfStatus else null
        val text=values["StatusText"] as? Telemetry.StatusText
        val lines=mutableListOf<String>()
        if(s.engine.heartbeatAgeMs==null||s.engine.heartbeatAgeMs>=1500)lines+="DỮ LIỆU QUÁ HẠN / CHƯA CÓ HEARTBEAT MỚI\n"
        fun no()="Chưa có dữ liệu"
        lines+="Chế độ ArduCopter: ${heartbeat?.let{modeName(it.customMode)}?:no()}"
        lines+="Pin: ${sys?.let{if(it.voltageMv==65535)no() else number(it.voltageMv/1000.0)+" V"}?:no()}"
        lines+="Dòng / % pin / thời gian còn lại: Chưa xác minh phép đo INA219"
        lines+="\nRoll / Pitch / Yaw (độ)\n${attitude?.let{listOf(it.rollRad,it.pitchRad,it.yawRad).joinToString(" / "){rad->number(Math.toDegrees(rad.toDouble()))}}?:no()}"
        lines+="\nGPS fix / vệ tinh: ${gps?.let{"${it.fixType} / ${if(it.satellitesVisible==255)no() else it.satellitesVisible}"}?:no()}"
        lines+="Vĩ độ / kinh độ: ${gps?.takeIf{it.fixType>=2}?.let{"${number(it.latitudeE7/1e7,7)} / ${number(it.longitudeE7/1e7,7)}"}?:no()}"
        lines+="Tốc độ mặt đất: ${gps?.takeIf{it.velocityCms!=65535}?.let{number(it.velocityCms/100.0)+" m/s"}?:no()}"
        lines+="Độ cao tương đối: ${global?.let{number(it.relativeAltitudeMm/1000.0)+" m"}?:no()}"
        lines+="Tốc độ thẳng đứng (lên +): ${global?.let{number(-it.velocityDownCms/100.0)+" m/s"}?:no()}"
        lines+="\nÁp suất: ${pressure?.let{number(it.absoluteHpa.toDouble())+" hPa"}?:no()}"
        lines+="Độ cao khí áp: Chưa có nguồn độ cao xác minh"
        lines+="\nEKF flags: ${ekf?.flags?:no()}"
        lines+="Cảm biến enabled / healthy: ${sys?.let{"0x${it.sensorsEnabled.toString(16)} / 0x${it.sensorsHealthy.toString(16)}"}?:no()}"
        lines+="STATUSTEXT / PreArm:\n${text?.text?:no()}"
        lines+="\nTuổi mẫu telemetry (ms):\n"+s.engine.telemetryAgeMs.entries.sortedBy{it.key}.joinToString(" · "){"${it.key} ${it.value}"}
        return lines.joinToString("\n")
    }
    private fun healthName(health:SystemHealth)=when(health){
        SystemHealth.UNKNOWN->"Chưa rõ"
        SystemHealth.UNINITIALISED->"Chưa khởi tạo"
        SystemHealth.BOOT->"Đang khởi động"
        SystemHealth.CALIBRATING->"Đang hiệu chuẩn"
        SystemHealth.STANDBY->"Chờ (STANDBY)"
        SystemHealth.ACTIVE->"Hoạt động (ACTIVE)"
        SystemHealth.CRITICAL->"Lỗi nghiêm trọng (CRITICAL)"
        SystemHealth.EMERGENCY->"Khẩn cấp (EMERGENCY)"
        SystemHealth.POWEROFF->"Đang tắt nguồn"
        SystemHealth.FLIGHT_TERMINATION->"Kết thúc vận hành (FLIGHT_TERMINATION)"
    }
    private fun eAge(s:SessionSnapshot,key:String)=s.engine.telemetryAgeMs[key]
    private fun stateName(name:String)=mapOf(
        "DISCONNECTED" to "Chưa kết nối", "CONNECTING" to "Đang kết nối", "CONNECTED_UNVERIFIED" to "TCP đã nối, chưa xác minh FC",
        "TELEMETRY_READY" to "Telemetry đã sẵn sàng", "RC_DIAGNOSTIC" to "Chẩn đoán RC chỉ đọc",
        "RC_BENCH_READY" to "Đủ điều kiện phép thử CH1", "RC_BENCH_ACTIVE" to "Đang thử CH1 hữu hạn",
        "RC_INPUT_VERIFIED" to "RC input đã xác minh", "CONTROL_LOCKED" to "Điều khiển bị khóa",
        "LINK_LOST" to "Mất liên kết", "FAILSAFE" to "Thu hồi quyền do lỗi", "ARMED_MONITORING" to "ARMED: chỉ theo dõi"
    )[name]?:name
    private fun modeName(mode:Long)=mapOf(0L to "Ổn định (STABILIZE)",1L to "ACRO",2L to "Giữ cao (ALT_HOLD)",3L to "Tự động (AUTO)",4L to "GUIDED",5L to "LOITER",6L to "Về nhà (RTL)",9L to "Hạ cánh (LAND)",16L to "POSHOLD")[mode]?:"Mã $mode (chưa có tên)"
}
