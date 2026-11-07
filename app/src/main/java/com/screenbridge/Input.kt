package com.screenbridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** وصف HID لقبضة بتخطيط Xbox 360 (VID 045e / PID 028e) ليتعرف عليها أندرويد تلقائيًا. */
object GamepadHid {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    const val VID = 0x045e
    const val PID = 0x028e
    const val ID = 10

    val DESC: ByteArray = b(
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01,
        // 16 زرًا
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x10, 0x81, 0x02,
        // Hat
        0x05, 0x01, 0x09, 0x39, 0x15, 0x01, 0x25, 0x08, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14,
        0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
        0x75, 0x04, 0x95, 0x01, 0x81, 0x03,
        0x35, 0x00, 0x45, 0x00, 0x65, 0x00,
        // العصا اليسرى X,Y
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x16, 0x00, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x02,
        // الزناد الأيسر (Z)
        0x09, 0x32, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x01, 0x81, 0x02,
        // العصا اليمنى Rx,Ry
        0x09, 0x33, 0x09, 0x34, 0x16, 0x00, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x02,
        // الزناد الأيمن (Rz)
        0x09, 0x35, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x01, 0x81, 0x02,
        0xC0
    )
}

class PadState {
    var buttons = 0
    var hat = 0
    var lx = 0; var ly = 0; var rx = 0; var ry = 0
    var lt = 0; var rt = 0
    fun report(): ByteArray {
        val r = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        r.putShort(buttons.toShort()).put(hat.toByte())
        r.putShort(lx.toShort()).putShort(ly.toShort()).put(lt.toByte())
        r.putShort(rx.toShort()).putShort(ry.toShort()).put(rt.toByte())
        return r.array()
    }
}

enum class Ctl(val label: String) {
    LS("مركز العصا اليسرى (الحركة)"), RS("مركز العصا اليمنى (الكاميرا)"),
    A("زر A"), B("زر B"), X("زر X"), Y("زر Y"),
    L1("L1"), R1("R1"), L2("L2"), R2("R2"),
    TL("ضغط العصا اليسرى"), TR("ضغط العصا اليمنى"),
    START("Start"), SELECT("Select"),
    FIRE("إطلاق النار (زر الفأرة الأيسر)"), AIM("التصويب (زر الفأرة الأيمن)")
}

/**
 * يوجّه القبضة والفأرة:
 *  - وضع "قبضة حقيقية": تُمرَّر عبر UHID.
 *  - وضع "ربط باللمس": تُحوَّل الأزرار والعصي إلى لمس في مواضع محفوظة.
 *  - وضع FPS: حركة الفأرة النسبية تتحول إلى سحب إصبع للكاميرا.
 */
class InputRouter(ctx: Context, private val onChord: () -> Unit) {
    @Volatile var vw = 0
    @Volatile var vh = 0
    var mapMode = false
    var profile = 1
    var sens = 1.6f

    var session: ScrcpySession? = null
        set(v) { field = v; uhidCreated = false }

    private val prefs = ctx.getSharedPreferences("sbmap", 0)
    private val map = HashMap<Ctl, FloatArray>()
    private val handler = Handler(Looper.getMainLooper())
    private val st = PadState()
    private var uhidCreated = false

    // ---------- ملفات التخطيط (لكل اتجاه شاشة ملف مستقل) ----------
    private fun key() = "p$profile" + if (vw >= vh) "L" else "P"

    fun reload() {
        map.clear()
        val s = prefs.getString(key(), "") ?: ""
        for (part in s.split(";")) {
            val kv = part.split("=")
            if (kv.size != 2) continue
            val c = Ctl.values().firstOrNull { it.name == kv[0] } ?: continue
            val xy = kv[1].split(",")
            if (xy.size == 2) map[c] = floatArrayOf(xy[0].toFloat(), xy[1].toFloat())
        }
    }

    private fun save() {
        prefs.edit().putString(key(), map.entries.joinToString(";") { "${it.key.name}=${it.value[0]},${it.value[1]}" }).apply()
    }

    fun setMapping(c: Ctl, x: Float, y: Float) { map[c] = floatArrayOf(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)); save() }
    fun clearProfile() { map.clear(); save() }
    fun setProfileIndex(p: Int) { profile = p; reload() }

    // ---------- لمس ----------
    private fun tch(a: Int, pid: Long, fx: Float, fy: Float, p: Float) {
        if (vw <= 0 || vh <= 0) return
        session?.touch(a, pid, (fx * vw).toInt().coerceIn(0, vw - 1), (fy * vh).toInt().coerceIn(0, vh - 1), vw, vh, p)
    }

    fun btn(c: Ctl, down: Boolean) {
        val p = map[c] ?: return
        tch(if (down) 0 else 1, 120L + c.ordinal, p[0], p[1], if (down) 1f else 0f)
    }

    private class Stick(val ctl: Ctl, val pid: Long) { var down = false; var lx = 0f; var ly = 0f }
    private val ls = Stick(Ctl.LS, 110)
    private val rs = Stick(Ctl.RS, 111)

    private fun stick(s: Stick, ax0: Float, ay0: Float) {
        val c = map[s.ctl] ?: return
        var ax = ax0; var ay = ay0
        val mag = hypot(ax, ay)
        if (mag < 0.2f) {
            if (s.down) { tch(1, s.pid, s.lx, s.ly, 0f); s.down = false }
            return
        }
        if (mag > 1f) { ax /= mag; ay /= mag }
        val r = 0.12f * minOf(vw, vh)
        val fx = c[0] + ax * r / vw
        val fy = c[1] + ay * r / vh
        if (!s.down) { tch(0, s.pid, c[0], c[1], 1f); s.down = true }
        tch(2, s.pid, fx, fy, 1f)
        s.lx = fx; s.ly = fy
    }

    // ---------- كاميرا الفأرة (FPS) ----------
    private var camDown = false
    private var cx = 0f
    private var cy = 0f
    private val camIdle = Runnable { camUp() }

    private fun camUp() { if (camDown) { tch(1, 130L, cx, cy, 0f); camDown = false } }

    /** dx, dy بوحدات بكسل الفيديو */
    fun mouseDelta(dx: Float, dy: Float) {
        if (vw <= 0) return
        val base = map[Ctl.RS] ?: floatArrayOf(0.7f, 0.5f)
        if (!camDown) { cx = base[0]; cy = base[1]; tch(0, 130L, cx, cy, 1f); camDown = true }
        cx += dx * sens / vw
        cy += dy * sens / vh
        if (cx < 0.04f || cx > 0.96f || cy < 0.04f || cy > 0.96f) { camUp(); return }
        tch(2, 130L, cx, cy, 1f)
        handler.removeCallbacks(camIdle)
        handler.postDelayed(camIdle, 120)
    }

    fun endCapture() { handler.removeCallbacks(camIdle); camUp(); wasd(0, 0, 0, 0) }

    private var wk = intArrayOf(0, 0, 0, 0)
    fun wasd(w: Int, a: Int, s: Int, d: Int) {
        wk = intArrayOf(w, a, s, d)
        stick(ls, (d - a).toFloat(), (s - w).toFloat())
    }
    fun wasdKey(code: Int, down: Boolean): Boolean {
        val i = when (code) {
            KeyEvent.KEYCODE_W -> 0; KeyEvent.KEYCODE_A -> 1; KeyEvent.KEYCODE_S -> 2; KeyEvent.KEYCODE_D -> 3
            else -> return false
        }
        wk[i] = if (down) 1 else 0
        stick(ls, (wk[3] - wk[1]).toFloat(), (wk[2] - wk[0]).toFloat())
        return true
    }

    // ---------- القبضة ----------
    private val keyBit = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to 0, KeyEvent.KEYCODE_BUTTON_B to 1, KeyEvent.KEYCODE_BUTTON_X to 3,
        KeyEvent.KEYCODE_BUTTON_Y to 4, KeyEvent.KEYCODE_BUTTON_L1 to 6, KeyEvent.KEYCODE_BUTTON_R1 to 7,
        KeyEvent.KEYCODE_BUTTON_L2 to 8, KeyEvent.KEYCODE_BUTTON_R2 to 9, KeyEvent.KEYCODE_BUTTON_SELECT to 10,
        KeyEvent.KEYCODE_BUTTON_START to 11, KeyEvent.KEYCODE_BUTTON_MODE to 12,
        KeyEvent.KEYCODE_BUTTON_THUMBL to 13, KeyEvent.KEYCODE_BUTTON_THUMBR to 14
    )
    private val keyCtl = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to Ctl.A, KeyEvent.KEYCODE_BUTTON_B to Ctl.B, KeyEvent.KEYCODE_BUTTON_X to Ctl.X,
        KeyEvent.KEYCODE_BUTTON_Y to Ctl.Y, KeyEvent.KEYCODE_BUTTON_L1 to Ctl.L1, KeyEvent.KEYCODE_BUTTON_R1 to Ctl.R1,
        KeyEvent.KEYCODE_BUTTON_L2 to Ctl.L2, KeyEvent.KEYCODE_BUTTON_R2 to Ctl.R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL to Ctl.TL, KeyEvent.KEYCODE_BUTTON_THUMBR to Ctl.TR,
        KeyEvent.KEYCODE_BUTTON_START to Ctl.START, KeyEvent.KEYCODE_BUTTON_SELECT to Ctl.SELECT
    )

    private var selHeld = false
    private var startHeld = false
    private val chord = Runnable { onChord() }
    private fun updateChord() {
        handler.removeCallbacks(chord)
        if (selHeld && startHeld) handler.postDelayed(chord, 1500)
    }

    private var kx = 0; private var ky = 0
    private var hx = 0; private var hy = 0
    private var l2d = false; private var r2d = false

    private fun send() {
        val s = session ?: return
        if (!uhidCreated) {
            uhidCreated = true
            s.uhidCreate(GamepadHid.ID, GamepadHid.VID, GamepadHid.PID, "ScreenBridge Gamepad", GamepadHid.DESC)
        }
        val x = if (hx != 0) hx else kx
        val y = if (hy != 0) hy else ky
        st.hat = when {
            x == 0 && y == -1 -> 1; x == 1 && y == -1 -> 2; x == 1 && y == 0 -> 3; x == 1 && y == 1 -> 4
            x == 0 && y == 1 -> 5; x == -1 && y == 1 -> 6; x == -1 && y == 0 -> 7; x == -1 && y == -1 -> 8
            else -> 0
        }
        s.uhidInput(GamepadHid.ID, st.report())
    }

    fun onKey(e: KeyEvent): Boolean {
        if (session == null) return false
        val down = e.action == KeyEvent.ACTION_DOWN
        val code = e.keyCode
        if (code == KeyEvent.KEYCODE_BUTTON_SELECT) { selHeld = down; updateChord() }
        if (code == KeyEvent.KEYCODE_BUTTON_START) { startHeld = down; updateChord() }
        if (e.repeatCount > 0) return code in keyBit || code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT

        if (mapMode) {
            val c = keyCtl[code] ?: return code in keyBit
            btn(c, down)
            return true
        }
        val bit = keyBit[code]
        if (bit != null) {
            st.buttons = if (down) st.buttons or (1 shl bit) else st.buttons and (1 shl bit).inv()
            if (code == KeyEvent.KEYCODE_BUTTON_L2) st.lt = if (down) 255 else 0
            if (code == KeyEvent.KEYCODE_BUTTON_R2) st.rt = if (down) 255 else 0
            send()
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> ky = if (down) -1 else 0
            KeyEvent.KEYCODE_DPAD_DOWN -> ky = if (down) 1 else 0
            KeyEvent.KEYCODE_DPAD_LEFT -> kx = if (down) -1 else 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> kx = if (down) 1 else 0
            else -> return false
        }
        send()
        return true
    }

    fun onMotion(e: MotionEvent): Boolean {
        if (session == null || e.actionMasked != MotionEvent.ACTION_MOVE) return false
        val lx = e.getAxisValue(MotionEvent.AXIS_X)
        val ly = e.getAxisValue(MotionEvent.AXIS_Y)
        val rx = e.getAxisValue(MotionEvent.AXIS_Z)
        val ry = e.getAxisValue(MotionEvent.AXIS_RZ)
        var lt = max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        var rt = max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
        val dev = e.device
        if (dev != null && dev.getMotionRange(MotionEvent.AXIS_LTRIGGER) == null &&
            dev.getMotionRange(MotionEvent.AXIS_BRAKE) == null && dev.getMotionRange(MotionEvent.AXIS_RX) != null
        ) {
            lt = (e.getAxisValue(MotionEvent.AXIS_RX) + 1f) / 2f
            rt = (e.getAxisValue(MotionEvent.AXIS_RY) + 1f) / 2f
        }
        hx = e.getAxisValue(MotionEvent.AXIS_HAT_X).roundToInt()
        hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y).roundToInt()

        if (mapMode) {
            stick(ls, lx, ly)
            stick(rs, rx, ry)
            if ((lt > 0.5f) != l2d) { l2d = lt > 0.5f; btn(Ctl.L2, l2d) }
            if ((rt > 0.5f) != r2d) { r2d = rt > 0.5f; btn(Ctl.R2, r2d) }
            return true
        }
        fun ax(v: Float) = if (abs(v) < 0.05f) 0 else (v.coerceIn(-1f, 1f) * 32767f).toInt()
        st.lx = ax(lx); st.ly = ax(ly); st.rx = ax(rx); st.ry = ax(ry)
        st.lt = (lt.coerceIn(0f, 1f) * 255f).toInt()
        st.rt = (rt.coerceIn(0f, 1f) * 255f).toInt()
        send()
        return true
    }
}
