package com.screenbridge

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {
    companion object {
        const val ACTION_PERM = "com.screenbridge.USB_PERMISSION"
        const val DEFAULT_SPLIT = "input keyevent --longpress KEYCODE_APP_SWITCH"
    }

    private lateinit var usb: UsbManager
    private lateinit var prefs: SharedPreferences
    private lateinit var router: InputRouter
    private lateinit var root: FrameLayout
    private lateinit var menu: View
    private lateinit var statusTv: TextView
    private lateinit var sv: SurfaceView
    private lateinit var bar: LinearLayout
    private lateinit var prompt: LinearLayout
    private lateinit var promptTv: TextView
    private lateinit var presets: RadioGroup
    private lateinit var autoOff: CheckBox

    @Volatile private var session: ScrcpySession? = null
    @Volatile private var adb: AdbUsbConnection? = null
    @Volatile private var decoder: VideoDecoder? = null
    @Volatile private var vw = 0
    @Volatile private var vh = 0
    @Volatile private var running = false
    private var lastBack = 0L
    private var mouseTouch = false
    private var editing = false
    private var editIdx = 0
    private var fps = false
    private var prevBtn = 0
    private var rotLandscape = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usb = getSystemService(USB_SERVICE) as UsbManager
        prefs = getSharedPreferences("sb", 0)
        router = InputRouter(this) { session?.screenOff() }
        router.mapMode = prefs.getBoolean("mapMode", false)
        router.profile = prefs.getInt("profile", 1)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        sv = SurfaceView(this).apply {
            visibility = View.GONE
            setOnTouchListener { _, e -> onSvTouch(e) }
            setOnGenericMotionListener { _, e -> onSvGeneric(e) }
            if (Build.VERSION.SDK_INT >= 26) setOnCapturedPointerListener { _, e -> onCaptured(e); true }
        }
        root.addView(sv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        buildMenu()
        buildBar()
        buildPrompt()
        setContentView(root)
        hideSystemUi()

        val f = IntentFilter().apply {
            addAction(ACTION_PERM)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        session?.stop()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    // ---------- واجهة ----------
    private fun buildMenu() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        fun tv(t: String, size: Float) = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        col.addView(tv("ScreenBridge", 26f))
        col.addView(tv("وصّل كابل USB من هذا الجهاز إلى الهاتف (الذي تشغّل عليه اللعبة)", 14f))

        presets = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf("متوازن: 20 Mbps / 60 إطار", "عالي: 40 Mbps / 60 إطار", "أقصى: 40 Mbps / 120 إطار").forEachIndexed { i, n ->
            presets.addView(RadioButton(this).apply { id = 100 + i; text = n; setTextColor(Color.WHITE) })
        }
        presets.check(100 + prefs.getInt("preset", 1))
        col.addView(presets)

        autoOff = CheckBox(this).apply {
            text = "إطفاء شاشة الهاتف تلقائيًا عند الاتصال"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("autoOff", true)
        }
        col.addView(autoOff)
        col.addView(Button(this).apply { text = "اتصال"; setOnClickListener { connectClicked() } })
        statusTv = tv("", 13f).apply { setPadding(0, dp(12), 0, 0) }
        col.addView(statusTv)

        val sc = ScrollView(this)
        sc.addView(col, ViewGroup.LayoutParams(-1, -2))
        menu = sc
        root.addView(sc, FrameLayout.LayoutParams(-1, -1))
    }

    private fun buildBar() {
        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; alpha = 0.4f; visibility = View.GONE }
        fun barBtn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        bar.addView(barBtn("⏻ إطفاء") { session?.screenOff() })
        bar.addView(barBtn("⟳") { rotLandscape = !rotLandscape; forceRot(if (rotLandscape) 1 else 0) })
        bar.addView(barBtn("⋯") { showTools() })
        bar.addView(barBtn("✕") { session?.stop() })
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
    }

    private fun buildPrompt() {
        prompt = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        promptTv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        prompt.addView(promptTv)
        prompt.addView(Button(this).apply { text = "تخطي"; setOnClickListener { editNext() } })
        prompt.addView(Button(this).apply { text = "إنهاء"; setOnClickListener { endEditor() } })
        root.addView(prompt, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
    }

    private fun status(s: String) = runOnUiThread { statusTv.text = s }

    // ---------- أدوات (تدوير/تقسيم/قبضة) ----------
    private fun forceRot(r: Int) {
        session?.shell(
            "wm set-ignore-orientation-request true; wm set-fix-to-user-rotation enabled; " +
                "wm fixed-to-user-rotation enabled; wm user-rotation lock $r; " +
                "settings put system accelerometer_rotation 0; settings put system user_rotation $r"
        )
    }

    private fun autoRot() {
        session?.shell(
            "wm set-ignore-orientation-request false; wm set-fix-to-user-rotation disabled; " +
                "wm fixed-to-user-rotation default; wm user-rotation free; " +
                "settings put system accelerometer_rotation 1"
        )
    }

    private fun showTools() {
        val s = session ?: return
        val items = arrayOf(
            "تدوير قسري: أفقي",
            "تدوير قسري: عمودي",
            "تدوير قسري: أفقي معكوس",
            "إلغاء التدوير القسري (تلقائي)",
            "المهام الأخيرة (للتقسيم من القائمة)",
            "تقسيم الشاشة (محاولة بالأمر المحفوظ)",
            "تهيئة التقسيم القسري (مرة واحدة)",
            "تعديل أمر التقسيم",
            "وضع القبضة: " + (if (router.mapMode) "ربط باللمس" else "قبضة حقيقية") + " (تبديل)",
            "تعديل تخطيط القبضة (ملف ${router.profile})",
            "تبديل ملف التخطيط",
            "مسح تخطيط هذا الملف",
            "وضع الفأرة FPS: " + (if (fps) "مفعّل" else "متوقف")
        )
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) {
                0 -> forceRot(1)
                1 -> forceRot(0)
                2 -> forceRot(3)
                3 -> autoRot()
                4 -> s.click(KeyEvent.KEYCODE_APP_SWITCH)
                5 -> s.shell(prefs.getString("splitCmd", DEFAULT_SPLIT) ?: DEFAULT_SPLIT)
                6 -> {
                    s.shell(
                        "settings put global force_resizable_activities 1; " +
                            "settings put global enable_freeform_support 1; " +
                            "settings put global enable_non_resizable_multi_window 1"
                    )
                    toast("تم. أعد تشغيل الهاتف مرة واحدة ليسري المفعول")
                }
                7 -> editSplitCmd()
                8 -> {
                    router.mapMode = !router.mapMode
                    prefs.edit().putBoolean("mapMode", router.mapMode).apply()
                    toast(if (router.mapMode) "ربط باللمس (حدّد التخطيط أولًا)" else "قبضة حقيقية")
                }
                9 -> startEditor()
                10 -> {
                    val p = router.profile % 3 + 1
                    router.setProfileIndex(p)
                    prefs.edit().putInt("profile", p).apply()
                    toast("ملف التخطيط: $p")
                }
                11 -> { router.clearProfile(); toast("تم المسح") }
                12 -> setFps(!fps)
            }
        }.show()
    }

    private fun editSplitCmd() {
        val et = EditText(this)
        et.setText(prefs.getString("splitCmd", DEFAULT_SPLIT))
        AlertDialog.Builder(this).setTitle("أمر shell للتقسيم").setView(et)
            .setPositiveButton("حفظ") { _, _ -> prefs.edit().putString("splitCmd", et.text.toString()).apply() }
            .setNegativeButton("إلغاء", null).show()
    }

    // ---------- محرر تخطيط القبضة ----------
    private fun startEditor() {
        if (vw == 0) return
        editing = true
        editIdx = 0
        prompt.visibility = View.VISIBLE
        showPrompt()
    }

    private fun showPrompt() { promptTv.text = "اضغط على الشاشة مكان: " + Ctl.values()[editIdx].label }

    private fun editNext() {
        editIdx++
        if (editIdx >= Ctl.values().size) endEditor() else showPrompt()
    }

    private fun endEditor() { editing = false; prompt.visibility = View.GONE }

    // ---------- وضع الفأرة FPS ----------
    private fun setFps(on: Boolean) {
        fps = on
        if (Build.VERSION.SDK_INT < 26) { toast("يتطلب أندرويد 8 أو أحدث"); fps = false; return }
        if (on) {
            sv.isFocusableInTouchMode = true
            sv.requestFocus()
            sv.requestPointerCapture()
            toast("وضع FPS: WASD للحركة. زر الفأرة الجانبي الأمامي أو Esc للخروج")
        } else {
            sv.releasePointerCapture()
            router.endCapture()
        }
    }

    private fun onCaptured(e: MotionEvent) {
        if (vw == 0 || sv.width == 0) return
        if (e.actionMasked == MotionEvent.ACTION_MOVE || e.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            router.mouseDelta(e.x * vw / sv.width, e.y * vh / sv.height)
        }
        val b = e.buttonState
        val ch = b xor prevBtn
        if (ch and MotionEvent.BUTTON_PRIMARY != 0) router.btn(Ctl.FIRE, b and MotionEvent.BUTTON_PRIMARY != 0)
        if (ch and MotionEvent.BUTTON_SECONDARY != 0) router.btn(Ctl.AIM, b and MotionEvent.BUTTON_SECONDARY != 0)
        if (b and MotionEvent.BUTTON_FORWARD != 0) setFps(false)
        prevBtn = b
    }

    // ---------- الاتصال ----------
    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_PERM -> {
                    val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (d != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) startSession(d)
                    else status("تم رفض إذن USB")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> adb?.close()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(i: Intent?) {
        if (i?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && !running) {
            val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (d != null) requestOrStart(d)
        }
    }

    private fun connectClicked() {
        val d = usb.deviceList.values.firstOrNull { AdbUsbConnection.findInterface(it) != null }
        if (d == null) {
            status("لم يتم العثور على هاتف. تأكد من الكابل (OTG) ومن تفعيل تصحيح USB.")
            return
        }
        requestOrStart(d)
    }

    private fun requestOrStart(d: UsbDevice) {
        if (running) return
        if (usb.hasPermission(d)) { startSession(d); return }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName), flags)
        usb.requestPermission(d, pi)
    }

    private fun startSession(dev: UsbDevice) {
        if (running) return
        running = true
        prefs.edit().putInt("preset", presets.checkedRadioButtonId - 100)
            .putBoolean("autoOff", autoOff.isChecked).apply()
        val (mbps, targetFps) = when (presets.checkedRadioButtonId - 100) {
            0 -> 20 to 60
            2 -> 40 to 120
            else -> 40 to 60
        }
        val cfg = ScrcpySession.Config(mbps * 1_000_000, targetFps, autoOff.isChecked)
        status("جاري الاتصال… وافق على نافذة تصحيح USB في الهاتف إن ظهرت")
        thread(name = "session") {
            var err: String? = null
            try {
                val c = AdbUsbConnection(usb, dev, AdbKey(filesDir))
                adb = c
                c.connect()
                val s = ScrcpySession(this, c, cfg, callback)
                session = s
                router.session = s
                s.run()
            } catch (e: Exception) {
                err = e.message
            } finally {
                session = null
                router.session = null
                adb?.close()
                adb = null
                decoder?.release()
                decoder = null
                running = false
                runOnUiThread {
                    if (fps) setFps(false)
                    endEditor()
                    sv.visibility = View.GONE
                    bar.visibility = View.GONE
                    menu.visibility = View.VISIBLE
                    statusTv.text = err ?: "انتهى الاتصال"
                }
            }
        }
    }

    private val callback = object : ScrcpySession.Callback {
        override fun status(s: String) { this@MainActivity.status(s) }

        override fun videoStart(w: Int, h: Int): Boolean {
            runOnUiThread {
                menu.visibility = View.GONE
                sv.visibility = View.VISIBLE
                bar.visibility = View.VISIBLE
            }
            var surface: android.view.Surface? = null
            for (i in 0 until 50) {
                if (sv.holder.surface.isValid) { surface = sv.holder.surface; break }
                Thread.sleep(100)
            }
            if (surface == null) return false
            decoder = VideoDecoder(surface, w, h) { nw, nh -> onVideoSize(nw, nh) }
            onVideoSize(w, h)
            return true
        }

        override fun packet(d: ByteArray, pts: Long, config: Boolean, key: Boolean) {
            decoder?.feed(d, pts, config, key)
        }
    }

    private fun onVideoSize(w: Int, h: Int) {
        vw = w; vh = h
        router.vw = w; router.vh = h
        router.reload()
        runOnUiThread {
            val rw = root.width
            val rh = root.height
            if (rw == 0 || rh == 0) return@runOnUiThread
            val sc = minOf(rw.toFloat() / w, rh.toFloat() / h)
            sv.layoutParams = FrameLayout.LayoutParams((w * sc).toInt(), (h * sc).toInt(), Gravity.CENTER)
        }
    }

    // ---------- الفأرة واللمس ----------
    private fun mx(x: Float) = (x / sv.width * vw).toInt().coerceIn(0, maxOf(vw - 1, 0))
    private fun my(y: Float) = (y / sv.height * vh).toInt().coerceIn(0, maxOf(vh - 1, 0))

    private fun onSvTouch(e: MotionEvent): Boolean {
        val s = session ?: return true
        if (vw == 0 || sv.width == 0) return true

        if (editing) {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                router.setMapping(Ctl.values()[editIdx], e.x / sv.width, e.y / sv.height)
                editNext()
            }
            return true
        }

        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val bs = e.buttonState
                    when {
                        bs and MotionEvent.BUTTON_SECONDARY != 0 -> {
                            lastBack = System.currentTimeMillis(); s.back(); mouseTouch = false
                        }
                        bs and MotionEvent.BUTTON_TERTIARY != 0 -> { s.click(KeyEvent.KEYCODE_HOME); mouseTouch = false }
                        else -> { mouseTouch = true; s.touch(0, -2L, mx(e.x), my(e.y), vw, vh, 1f) }
                    }
                }
                MotionEvent.ACTION_MOVE -> if (mouseTouch) s.touch(2, -2L, mx(e.x), my(e.y), vw, vh, 1f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (mouseTouch) { s.touch(1, -2L, mx(e.x), my(e.y), vw, vh, 0f); mouseTouch = false }
            }
            return true
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                s.touch(0, e.getPointerId(i).toLong(), mx(e.getX(i)), my(e.getY(i)), vw, vh, 1f)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount)
                s.touch(2, e.getPointerId(i).toLong(), mx(e.getX(i)), my(e.getY(i)), vw, vh, 1f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                s.touch(1, e.getPointerId(i).toLong(), mx(e.getX(i)), my(e.getY(i)), vw, vh, 0f)
            }
            MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount)
                s.touch(1, e.getPointerId(i).toLong(), mx(e.getX(i)), my(e.getY(i)), vw, vh, 0f)
        }
        return true
    }

    private fun onSvGeneric(e: MotionEvent): Boolean {
        val s = session ?: return false
        if (e.actionMasked == MotionEvent.ACTION_SCROLL && vw > 0 && sv.width > 0) {
            s.scroll(
                mx(e.x), my(e.y), vw, vh,
                e.getAxisValue(MotionEvent.AXIS_HSCROLL), e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            )
            return true
        }
        return false
    }

    // ---------- القبضة والكيبورد ----------
    private fun isPad(src: Int) =
        (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (session != null && (e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            if (router.onMotion(e)) return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val s = session
        if (s != null && !editing) {
            if (isPad(e.source)) {
                if (router.onKey(e)) return true
            } else if ((e.source and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD &&
                e.keyCode != KeyEvent.KEYCODE_BACK
            ) {
                val down = e.action == KeyEvent.ACTION_DOWN
                if (fps) {
                    if (e.keyCode == KeyEvent.KEYCODE_ESCAPE) { if (down) setFps(false); return true }
                    if (router.wasdKey(e.keyCode, down)) return true
                }
                s.key(if (down) 0 else 1, e.keyCode, e.metaState)
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = session
        if (keyCode == KeyEvent.KEYCODE_BACK && s != null) {
            if (System.currentTimeMillis() - lastBack > 300) { lastBack = System.currentTimeMillis(); s.back() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (session == null) super.onBackPressed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
            if (fps && Build.VERSION.SDK_INT >= 26) sv.requestPointerCapture()
        }
    }
}
