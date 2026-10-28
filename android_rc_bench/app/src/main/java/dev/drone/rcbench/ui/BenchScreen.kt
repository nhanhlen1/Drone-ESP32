package dev.drone.rcbench.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.drone.rcbench.R
import kotlin.math.roundToInt

/** UI boundary: transport/safety remains in the session, never in view callbacks. */
interface UiActions {
    fun connect(host: String, port: Int)
    fun disconnect()
    fun enableTest()
    fun stop(reason: String)
    fun deadman(held: Boolean)
    fun sticks(roll: Float, pitch: Float, throttle: Float, yaw: Float)
    /** channel 3 uses 0..1; all other single channels use -1..1. */
    fun singleChannel(channel: Int, value: Float)
    /** 0: dual sticks; 1..4: individual channel. */
    fun mode(channel: Int)
    fun channelConfig(channel: Int, minimum: Int, maximum: Int, trim: Int, verified: Boolean)
}

data class UiChannel(val channel: Int, val minimum: Int = 1100, val maximum: Int = 1900, val trim: Int = 1500, val verified: Boolean = false)
data class UiSnapshot(
    val tcpStatus: String = "CHƯA KẾT NỐI",
    val heartbeat: String = "Chưa nhận HEARTBEAT",
    val armed: String = "KHÔNG RÕ",
    val target: String = "— / —",
    /** All prerequisites including fresh disarmed heartbeat and verified channels. */
    val ready: Boolean = false,
    val testEnabled: Boolean = false,
    val modeChannel: Int = 0,
    val stopReason: String = "RC đang khóa. Không có lệnh ARM hoặc motor trong ứng dụng này.",
    val txRcChannels: IntArray? = null,
    val rxRcChannels: IntArray? = null,
    val lastTxAgeMs: Long? = null,
    val lastRxAgeMs: Long? = null,
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val txOverrides: Long = 0,
    val txHz: Double = 0.0,
    val crcErrors: Long = 0,
    val sequenceLoss: Long = 0,
    val parserErrors: Long = 0,
    val sensorReadings: List<Pair<String, String>> = emptyList(),
    val logs: List<String> = emptyList(),
    val channels: List<UiChannel> = (1..4).map { UiChannel(it) }
)

/** Constructed in code with real session actions; intentionally not an XML-inflatable view. */
class BenchScreen(private val activity: Activity, private val actions: UiActions) : LinearLayout(activity) {
    private var snapshot = UiSnapshot()
    private var activePage = 0
    private var disposed = false
    private var throttle = 0f
    private var yaw = 0f
    private var roll = 0f
    private var pitch = 0f
    private var selectedChannel = 0
    private var rendering = false
    private var appliedChannels: List<UiChannel>? = null
    private val textColor = Palette.text
    private val status = label("CHƯA KẾT NỐI", 12, Palette.muted)
    private val heartbeat = label("Chưa nhận HEARTBEAT", 12, Palette.muted)
    private val armed = label("KHÔNG RÕ", 12, Palette.amber)
    private val target = label("SYS / COMP: — / —", 11, Palette.muted)
    private val warning = label("RC đang khóa.", 12, Palette.amber)
    private val host = input("192.168.4.1", false)
    private val port = input("5760", true)
    private val connect = button("KẾT NỐI")
    private val disconnect = button("NGẮT")
    private val enable = button("CHO PHÉP RC BENCH")
    private val stop = button("DỪNG RC", Palette.red)
    private val confirmationBox = LinearLayout(activity)
    private val tx = label("TX override: chưa gửi", 13, Palette.mint)
    private val rx = label("RX RC_CHANNELS: chưa có", 13, Palette.text)
    private val timing = label("TX —  /  RX —", 11, Palette.muted)
    private val controlPage = LinearLayout(activity)
    private val diagnosticsPage = LinearLayout(activity)
    private val configPage = LinearLayout(activity)
    private val pages = FrameLayout(activity)
    private val sticksRow = LinearLayout(activity)
    private val leftStick = StickView(activity, true)
    private val rightStick = StickView(activity, false)
    private val deadman = DeadmanView(activity)
    private val throttleText = label("THROTTLE 0%", 11, Palette.mint)
    private val mode = Spinner(activity)
    private val singleBox = LinearLayout(activity)
    private val singleLabel = label("Kênh đơn", 12)
    private val singleSlider = SeekBar(activity)
    private val telemetryText = label("Chưa có telemetry.", 13)
    private val logText = label("Chưa có sự kiện.", 11, Palette.muted)
    private val channelEditors = mutableListOf<ChannelEditor>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.background)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        isMotionEventSplittingEnabled = true
        val header = row()
        header.addView(label("DRONE /", 20, Palette.mint).apply { typeface = Typeface.DEFAULT_BOLD }, weight())
        header.addView(label("RC BENCH", 11, Palette.muted))
        addView(header)
        addView(label("TCP • MAVLink 2 • thử RC trên bàn", 11, Palette.muted), full().apply { bottomMargin = dp(8) })
        val connection = row()
        connection.addView(status, weight())
        connection.addView(armed)
        addView(connection)
        addView(heartbeat)
        addView(target)
        addView(warning, full().apply { topMargin = dp(7); bottomMargin = dp(8) })
        val nav = row()
        listOf("ĐIỀU KHIỂN", "GIÁM SÁT", "CẤU HÌNH").forEachIndexed { index, title ->
            nav.addView(button(title).apply { textSize = 10f; setOnClickListener { showPage(index) } }, weight())
        }
        addView(nav)
        addView(pages, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        buildControlPage()
        buildDiagnosticsPage()
        buildConfigPage()
        pages.addView(controlPage)
        pages.addView(scroll(diagnosticsPage))
        pages.addView(scroll(configPage))
        confirmationBox.orientation = VERTICAL
        confirmationBox.setPadding(dp(12), dp(8), dp(12), dp(8))
        confirmationBox.background = cardBackground()
        confirmationBox.visibility = GONE
        confirmationBox.addView(label("XÁC NHẬN BENCH: tháo cánh quạt; FC DISARMED. Chỉ ghi đè RC1–RC4 đã đối chiếu. Không gửi lệnh ARM; RC vẫn có thể kích hoạt cử chỉ ARM trên FC. Phải giữ deadman riêng mới truyền RC.", 12, Palette.amber))
        val confirmActions = row()
        confirmActions.addView(button("HỦY").apply { setOnClickListener { confirmationBox.visibility = GONE } }, weight())
        confirmActions.addView(button("XÁC NHẬN BENCH").apply { setOnClickListener {
            confirmationBox.visibility = GONE
            if (!disposed && activePage == 0 && snapshot.ready && !snapshot.testEnabled) actions.enableTest()
        } }, weight())
        confirmationBox.addView(confirmActions)
        addView(confirmationBox)
        val bottom = row()
        bottom.addView(enable, weight())
        bottom.addView(stop, weight())
        addView(bottom)
        enable.setOnClickListener {
            if (activePage != 0 || !snapshot.ready || snapshot.testEnabled) return@setOnClickListener
            stopUi("Đang xác nhận RC bench")
            // An inline second action keeps the strict focus-loss stop policy.
            // A modal Dialog would take Activity focus before its positive action.
            if (snapshot.ready && !snapshot.testEnabled) confirmationBox.visibility = VISIBLE
        }
        stop.setOnClickListener { stopUi("Người dùng dừng RC") }
        showPage(0, stopSession = false)
        render(snapshot)
    }

    private fun buildControlPage() {
        controlPage.orientation = VERTICAL
        val modeRow = row()
        mode.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
            listOf("Hai joystick", "Kênh đơn RC1", "Kênh đơn RC2", "Kênh đơn RC3", "Kênh đơn RC4"))
        modeRow.addView(mode, weight())
        modeRow.addView(throttleText)
        controlPage.addView(modeRow)
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (rendering || position == selectedChannel) return
                stopUi("Đổi chế độ điều khiển")
                selectedChannel = position
                actions.mode(position)
                updateMode()
            }
        }
        sticksRow.orientation = HORIZONTAL
        sticksRow.isMotionEventSplittingEnabled = true
        sticksRow.addView(leftStick, weight(height = LayoutParams.MATCH_PARENT))
        sticksRow.addView(rightStick, weight(height = LayoutParams.MATCH_PARENT))
        controlPage.addView(sticksRow, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        leftStick.onValue = { x, value ->
            if (!disposed && inputsAllowed()) { yaw = x; throttle = value; publishSticks() }
        }
        rightStick.onValue = { x, y ->
            if (!disposed && inputsAllowed()) { roll = x; pitch = y; publishSticks() }
        }
        singleBox.orientation = VERTICAL
        singleBox.gravity = Gravity.CENTER_VERTICAL
        singleBox.addView(singleLabel)
        singleSlider.max = 1000
        singleBox.addView(singleSlider, full())
        singleBox.addView(label("Giữ deadman bằng ngón khác khi chỉnh slider. Thả deadman dừng truyền; không tự phát giá trị trung tính.", 12, Palette.muted))
        singleBox.addView(button("VỀ TRIM / THROTTLE MIN" ).apply {
            setOnClickListener {
                if (selectedChannel != 3) singleSlider.progress = 500
                else singleSlider.progress = 0
                sendSingle()
            }
        })
        controlPage.addView(singleBox, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        singleSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) { parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { parent?.requestDisallowInterceptTouchEvent(false) }
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) sendSingle() }
        })
        controlPage.addView(label("YAW / THROTTLE                                  ROLL / PITCH", 10, Palette.muted).apply { gravity = Gravity.CENTER })
        controlPage.addView(deadman, full().apply { height = dp(58); topMargin = dp(6); bottomMargin = dp(8) })
        deadman.onHeld = { held ->
            if (!disposed) {
                if (held && inputsAllowed()) actions.deadman(true)
                else { actions.deadman(false); if (!held) stopUi("Đã thả deadman") }
            }
        }
        controlPage.addView(label("CH4 yaw: giới hạn 25% biên • không thử toàn dải", 11, Palette.amber))
        controlPage.addView(tx)
        controlPage.addView(rx)
        controlPage.addView(timing)
    }

    private fun buildDiagnosticsPage() {
        diagnosticsPage.orientation = VERTICAL
        diagnosticsPage.setPadding(0, dp(16), 0, dp(16))
        diagnosticsPage.addView(label("TELEMETRY TỪ FC", 13, Palette.mint))
        diagnosticsPage.addView(telemetryText, full().apply { topMargin = dp(12); bottomMargin = dp(18) })
        diagnosticsPage.addView(label("NHẬT KÝ / CHẨN ĐOÁN", 13, Palette.mint))
        diagnosticsPage.addView(logText, full().apply { topMargin = dp(12) })
        diagnosticsPage.addView(label("TX là override đã gửi qua TCP; RX RC_CHANNELS là dữ liệu FC báo về và có thể khác. IGNORE ở TX là bỏ qua kênh; — ở RX là chưa có giá trị. Chênh sequence chỉ là ước lượng; TCP không cung cấp chất lượng RF.", 12, Palette.muted), full().apply { topMargin = dp(18) })
    }

    private fun buildConfigPage() {
        configPage.orientation = VERTICAL
        configPage.setPadding(0, dp(12), 0, dp(16))
        configPage.addView(label("ĐƯỜNG TRUYỀN TCP", 13, Palette.mint))
        configPage.addView(label("Host và port của cầu nối MAVLink TCP. Không tự kết nối hoặc tự nối lại.", 12, Palette.muted))
        host.hint = "Địa chỉ IP / hostname"
        port.hint = "Port"
        val endpoint = row()
        endpoint.addView(host, LayoutParams(0, dp(52), 3f))
        endpoint.addView(port, LayoutParams(0, dp(52), 1f))
        configPage.addView(endpoint)
        val controls = row()
        controls.addView(connect, weight())
        controls.addView(disconnect, weight())
        configPage.addView(controls)
        connect.setOnClickListener {
            val value = port.text.toString().toIntOrNull()
            val destination = host.text.toString().trim()
            if (value == null || value !in 1..65535 || destination.isEmpty()) {
                Toast.makeText(activity, "Nhập địa chỉ và port hợp lệ.", Toast.LENGTH_LONG).show()
            } else { stopUi("Kết nối mới"); appliedChannels = null; actions.connect(destination, value) }
        }
        disconnect.setOnClickListener { stopUi("Ngắt TCP"); appliedChannels = null; actions.disconnect() }
        configPage.addView(label("MIN / MAX / TRIM TỪNG KÊNH", 13, Palette.mint), full().apply { topMargin = dp(18) })
        configPage.addView(label("RC1: 1100 / 1900 / 1500 đã được cung cấp. RC2–RC4 dùng số tạm; phải đối chiếu tham số FC rồi xác nhận riêng cả bốn kênh. Mọi thay đổi chỉ có hiệu lực sau bấm LƯU KÊNH.", 12, Palette.amber))
        for (channel in 1..4) {
            val editor = ChannelEditor(channel)
            channelEditors.add(editor)
            configPage.addView(editor.root, full().apply { topMargin = dp(12) })
        }
        configPage.addView(label("CH4 yaw bị giới hạn 25% biên ở cả joystick và kênh đơn (mặc định 1400–1600 µs), để tránh thử rudder toàn dải khi cấu hình ARM của FC chưa được xác minh.", 12, Palette.amber), full().apply { topMargin = dp(12) })
        configPage.addView(label("STICK EXPO", 13, Palette.mint), full().apply { topMargin = dp(18) })
        val expoText = label("Expo 25% • dead zone 6%", 12, Palette.muted)
        val expoSlider = SeekBar(activity).apply { max = 80; progress = 25 }
        configPage.addView(expoText)
        configPage.addView(expoSlider, full())
        expoSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) { stopUi("Đổi expo"); leftStick.expo = progress / 100f; rightStick.expo = progress / 100f }
                expoText.text = activity.getString(R.string.ui_expo, progress)
            }
        })
    }

    private inner class ChannelEditor(val channel: Int) {
        val root = LinearLayout(activity).apply { orientation = VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(10)); background = cardBackground() }
        val minimum = input("1100", true)
        val maximum = input("1900", true)
        val trim = input("1500", true)
        val verified = CheckBox(activity).apply { text = activity.getString(R.string.ui_channel_verified, channel); setTextColor(Palette.text); textSize = 12f; isChecked = false }
        val save = button("LƯU KÊNH RC$channel")
        init {
            root.addView(label("RC$channel   min / max / trim (µs)", 12, Palette.mint))
            val fields = row()
            fields.addView(minimum, weight())
            fields.addView(maximum, weight())
            fields.addView(trim, weight())
            root.addView(fields)
            root.addView(verified)
            root.addView(save, full())
            save.setOnClickListener {
                val low = minimum.text.toString().toIntOrNull()
                val high = maximum.text.toString().toIntOrNull()
                val center = trim.text.toString().toIntOrNull()
                if (low == null || high == null || center == null || low !in 900..2100 || high !in 900..2100 || center !in 900..2100 || low >= center || center >= high) {
                    Toast.makeText(activity, "Cần 900 ≤ min < trim < max ≤ 2100 µs.", Toast.LENGTH_LONG).show()
                } else { stopUi("Cập nhật RC$channel"); actions.channelConfig(channel, low, high, center, verified.isChecked) }
            }
        }
        fun apply(config: UiChannel) {
            minimum.setText(activity.getString(R.string.ui_integer, config.minimum))
            maximum.setText(activity.getString(R.string.ui_integer, config.maximum))
            trim.setText(activity.getString(R.string.ui_integer, config.trim))
            verified.isChecked = config.verified
        }
        fun setLocked(locked: Boolean) {
            minimum.isEnabled = !locked; maximum.isEnabled = !locked; trim.isEnabled = !locked
            verified.isEnabled = !locked; save.isEnabled = !locked
        }
    }

    fun render(state: UiSnapshot) {
        if (disposed) return
        val previouslyAllowed = inputsAllowed()
        snapshot = state
        status.text = state.tcpStatus
        heartbeat.text = state.heartbeat
        armed.text = activity.getString(R.string.ui_armed, state.armed)
        target.text = activity.getString(R.string.ui_target, state.target)
        warning.text = state.stopReason
        armed.setTextColor(when { state.armed.contains("DISARM", true) -> Palette.mint; state.armed.equals("ARMED", true) -> Palette.red; else -> Palette.amber })
        if (previouslyAllowed && !inputsAllowed()) resetInputs()
        leftStick.isEnabled = inputsAllowed() && selectedChannel == 0
        rightStick.isEnabled = inputsAllowed() && selectedChannel == 0
        deadman.isEnabled = inputsAllowed()
        singleSlider.isEnabled = inputsAllowed() && selectedChannel != 0
        enable.isEnabled = activePage == 0 && state.ready && !state.testEnabled
        if (!state.ready || state.testEnabled) confirmationBox.visibility = GONE
        enable.alpha = if (enable.isEnabled) 1f else .4f
        if (appliedChannels != state.channels) {
            appliedChannels = state.channels.toList()
            val byChannel = state.channels.associateBy { it.channel }
            channelEditors.forEach { editor -> byChannel[editor.channel]?.let { editor.apply(it) } }
        }
        channelEditors.forEach { it.setLocked(state.testEnabled) }
        mode.isEnabled = !state.testEnabled
        connect.isEnabled = !state.testEnabled
        host.isEnabled = !state.testEnabled
        port.isEnabled = !state.testEnabled
        tx.text = activity.getString(R.string.ui_tx_channels, channels(state.txRcChannels, transmitted = true))
        rx.text = activity.getString(R.string.ui_rx_channels, channels(state.rxRcChannels, transmitted = false))
        timing.text = activity.getString(R.string.ui_timing, age(state.lastTxAgeMs), age(state.lastRxAgeMs), state.txHz)
        telemetryText.text = state.sensorReadings.joinToString("\n\n") { "${it.first}\n${it.second}" }.ifEmpty { "Chưa có telemetry hợp lệ." }
        logText.text = activity.getString(R.string.ui_diagnostics,
            state.txBytes, state.txOverrides, state.rxBytes, state.crcErrors,
            state.parserErrors, state.sequenceLoss, state.logs.take(30).joinToString("\n"))
        if (selectedChannel != state.modeChannel) {
            selectedChannel = state.modeChannel.coerceIn(0, 4)
            rendering = true; mode.setSelection(selectedChannel); rendering = false
            updateMode()
        }
    }

    /** Call on background, focus loss, lease/session loss and reconnect. Never transmits neutral. */
    fun resetInputs() {
        confirmationBox.visibility = GONE
        leftStick.reset(resetThrottle = true)
        rightStick.reset()
        deadman.release()
        throttle = 0f; yaw = 0f; roll = 0f; pitch = 0f
        throttleText.text = activity.getString(R.string.ui_throttle, 0)
        singleSlider.progress = if (selectedChannel == 3) 0 else 500
    }
    fun dispose() {
        if (disposed) return
        disposed = true
        deadman.dispose()
        leftStick.dispose()
        rightStick.dispose()
    }
    private fun inputsAllowed() = activePage == 0 && snapshot.ready && snapshot.testEnabled
    private fun publishSticks() {
        throttleText.text = activity.getString(R.string.ui_throttle, (throttle * 100).roundToInt())
        actions.sticks(roll, pitch, throttle, yaw)
    }
    private fun sendSingle() {
        if (!inputsAllowed() || selectedChannel !in 1..4) return
        val value = if (selectedChannel == 3) singleSlider.progress / 1000f else singleSlider.progress / 500f - 1f
        actions.singleChannel(selectedChannel, value)
        singleLabel.text = activity.getString(R.string.ui_single_value, selectedChannel, value)
    }
    private fun stopUi(reason: String) {
        if (disposed) return
        // release first, then stop; neither path generates an automatic neutral override.
        resetInputs()
        actions.stop(reason)
    }
    private fun updateMode() {
        sticksRow.visibility = if (selectedChannel == 0) View.VISIBLE else View.GONE
        singleBox.visibility = if (selectedChannel == 0) View.GONE else View.VISIBLE
        singleLabel.text = activity.getString(R.string.ui_single_selected, selectedChannel)
        singleSlider.progress = if (selectedChannel == 3) 0 else 500
    }
    private fun showPage(index: Int, stopSession: Boolean = true) {
        if (stopSession && index != activePage) stopUi("Đổi màn hình")
        activePage = index
        for (i in 0 until pages.childCount) pages.getChildAt(i).visibility = if (i == index) View.VISIBLE else View.GONE
        render(snapshot)
    }
    private fun channels(values: IntArray?, transmitted: Boolean): String {
        if (values == null) return activity.getString(R.string.ui_channels_missing)
        return values.take(4).mapIndexed { index, value ->
            // MAVLink uses the same sentinel for an ignored TX override and an
            // unavailable RX reading; these are different meanings on screen.
            val reading = if (value == 65535) activity.getString(
                if (transmitted) R.string.ui_channel_ignore else R.string.ui_channel_unknown
            ) else activity.getString(R.string.ui_integer, value)
            activity.getString(R.string.ui_channel_value, index + 1, reading)
        }.joinToString("  ")
    }
    private fun age(value: Long?) = value?.let { activity.getString(R.string.ui_age_ms, it) }
        ?: activity.getString(R.string.ui_age_missing)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun full() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    private fun weight(height: Int = LayoutParams.WRAP_CONTENT) = LayoutParams(0, height, 1f)
    private fun row() = LinearLayout(activity).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isMotionEventSplittingEnabled = true }
    private fun scroll(child: View) = ScrollView(activity).apply { isFillViewport = false; addView(child, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    private fun label(value: String, size: Int, color: Int = textColor) = TextView(activity).apply { text = value; textSize = size.toFloat(); setTextColor(color); setPadding(0, dp(2), 0, dp(2)); setLineSpacing(dp(2).toFloat(), 1f) }
    private fun input(value: String, numeric: Boolean) = EditText(activity).apply { setText(value); setTextColor(Palette.text); setHintTextColor(Palette.muted); textSize = 13f; isSingleLine = true; inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER else android.text.InputType.TYPE_CLASS_TEXT; setPadding(dp(8), dp(4), dp(8), dp(4)) }
    private fun button(value: String, color: Int = Palette.mint) = Button(activity).apply { text = value; textSize = 11f; isAllCaps = false; setTextColor(color); minHeight = dp(44); setPadding(dp(7), 0, dp(7), 0); backgroundTintList = android.content.res.ColorStateList.valueOf(Palette.card) }
    private fun cardBackground() = GradientDrawable().apply { setColor(Palette.card); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Palette.line) }
}
