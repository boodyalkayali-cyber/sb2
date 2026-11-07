package com.screenbridge

import android.content.Context
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.random.Random

/** يشغّل سيرفر scrcpy الرسمي على الهاتف ويتكلم بروتوكوله. */
class ScrcpySession(
    private val ctx: Context,
    private val adb: AdbUsbConnection,
    private val cfg: Config,
    private val cb: Callback
) {
    data class Config(val bitrate: Int, val fps: Int, val autoOff: Boolean)

    interface Callback {
        fun status(s: String)
        fun videoStart(w: Int, h: Int): Boolean
        fun packet(d: ByteArray, pts: Long, config: Boolean, key: Boolean)
    }

    @Volatile private var control: AdbStream? = null
    private val sender = Executors.newSingleThreadExecutor()
    private val shellOut = StringBuilder()
    private val remote = "/data/local/tmp/scrcpy-server.jar"

    fun run() {
        try { runInner() } finally { sender.shutdownNow() }
    }

    private fun tail(): String = synchronized(shellOut) { shellOut.toString().takeLast(600) }

    private fun runInner() {
        val ver = ctx.assets.open("scrcpy-version.txt").bufferedReader().readText().trim()
        cb.status("رفع السيرفر إلى الهاتف…")
        push(ctx.assets.open("scrcpy-server.jar").readBytes())

        val scid = "%08x".format(Random.nextInt(1, Int.MAX_VALUE))
        val cmd = "CLASSPATH=$remote app_process / com.genymobile.scrcpy.Server $ver " +
            "scid=$scid log_level=info video_codec=h264 audio=false control=true " +
            "tunnel_forward=true video_bit_rate=${cfg.bitrate} max_fps=${cfg.fps} stay_awake=true"
        cb.status("تشغيل السيرفر…")
        val shell = adb.open("shell:$cmd")
        thread(isDaemon = true) {
            val b = ByteArray(4096)
            try {
                while (true) {
                    val n = shell.read(b, 0, b.size)
                    if (n < 0) break
                    synchronized(shellOut) { shellOut.append(String(b, 0, n)) }
                }
            } catch (_: Exception) {}
        }

        val sock = "localabstract:scrcpy_$scid"
        var video: AdbStream? = null
        for (i in 0 until 80) {
            try { video = adb.open(sock); break } catch (e: IOException) { Thread.sleep(150) }
        }
        val v = video ?: throw IOException("فشل تشغيل السيرفر:\n" + tail())

        val dummy = ByteArray(1)
        if (!v.readFully(dummy)) throw IOException("السيرفر توقف:\n" + tail())
        val name = ByteArray(64)
        v.readFully(name)
        val cm = ByteArray(12)
        if (!v.readFully(cm)) throw IOException("السيرفر توقف:\n" + tail())
        val cbuf = ByteBuffer.wrap(cm)
        cbuf.int
        val w = cbuf.int
        val h = cbuf.int

        val c = adb.open(sock)
        control = c
        thread(isDaemon = true) {
            val b = ByteArray(4096)
            try { while (c.read(b, 0, b.size) >= 0) { } } catch (_: Exception) {}
        }

        if (!cb.videoStart(w, h)) throw IOException("تعذر تهيئة فك الترميز")
        cb.status("متصل")

        var offSent = false
        val hdr = ByteArray(12)
        while (v.readFully(hdr)) {
            val bb = ByteBuffer.wrap(hdr)
            val pf = bb.long
            val size = bb.int
            val data = ByteArray(size)
            if (!v.readFully(data)) break
            val config = ((pf ushr 63) and 1L) != 0L
            val key = ((pf ushr 62) and 1L) != 0L
            val pts = pf and 0x3fffffffffffffffL
            cb.packet(data, pts, config, key)
            if (!config && !offSent && cfg.autoOff) {
                offSent = true
                screenOff()
            }
        }
        throw IOException("انتهى الاتصال")
    }

    private fun le(tag: String, v: Int): ByteArray =
        tag.toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun push(data: ByteArray) {
        val s = adb.open("sync:")
        val spec = "$remote,33206".toByteArray()
        s.write(le("SEND", spec.size) + spec)
        var o = 0
        while (o < data.size) {
            val n = minOf(65536, data.size - o)
            s.write(le("DATA", n) + data.copyOfRange(o, o + n))
            o += n
        }
        s.write(le("DONE", (System.currentTimeMillis() / 1000).toInt()))
        val r = ByteArray(8)
        if (!s.readFully(r) || String(r, 0, 4) != "OKAY") throw IOException("فشل رفع ملف السيرفر")
        s.close()
    }

    // ---------- رسائل التحكم ----------
    private fun ctl(b: ByteArray) {
        try {
            sender.execute {
                try { control?.write(b) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    fun touch(action: Int, pid: Long, x: Int, y: Int, w: Int, h: Int, pressure: Float) {
        val p = if (pressure >= 1f) 0xffff else (pressure * 0xffff).toInt()
        val b = ByteBuffer.allocate(32)
        b.put(2).put(action.toByte()).putLong(pid).putInt(x).putInt(y)
            .putShort(w.toShort()).putShort(h.toShort()).putShort((p and 0xffff).toShort())
            .putInt(0).putInt(0)
        ctl(b.array())
    }

    fun key(action: Int, code: Int, meta: Int = 0) {
        val b = ByteBuffer.allocate(14)
        b.put(0).put(action.toByte()).putInt(code).putInt(0).putInt(meta)
        ctl(b.array())
    }

    fun click(code: Int) { key(0, code); key(1, code) }

    fun back() {
        ctl(byteArrayOf(4, 0))
        ctl(byteArrayOf(4, 1))
    }

    fun scroll(x: Int, y: Int, w: Int, h: Int, hs: Float, vs: Float) {
        fun fp(f: Float): Short = (f.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        val b = ByteBuffer.allocate(21)
        b.put(3).putInt(x).putInt(y).putShort(w.toShort()).putShort(h.toShort())
            .putShort(fp(hs)).putShort(fp(vs)).putInt(0)
        ctl(b.array())
    }

    fun screenOff() { ctl(byteArrayOf(10, 0)) }

    // ---------- UHID (قبضة حقيقية) ----------
    fun uhidCreate(id: Int, vid: Int, pid: Int, name: String, desc: ByteArray) {
        val nb = name.toByteArray()
        val b = ByteBuffer.allocate(1 + 2 + 2 + 2 + 1 + nb.size + 2 + desc.size)
        b.put(12).putShort(id.toShort()).putShort(vid.toShort()).putShort(pid.toShort())
            .put(nb.size.toByte()).put(nb).putShort(desc.size.toShort()).put(desc)
        ctl(b.array())
    }

    fun uhidInput(id: Int, data: ByteArray) {
        val b = ByteBuffer.allocate(1 + 2 + 2 + data.size)
        b.put(13).putShort(id.toShort()).putShort(data.size.toShort()).put(data)
        ctl(b.array())
    }

    fun uhidDestroy(id: Int) {
        ctl(ByteBuffer.allocate(3).put(14).putShort(id.toShort()).array())
    }

    /** تشغيل أمر shell على الهاتف (تدوير/تقسيم...). */
    fun shell(cmd: String) {
        thread(isDaemon = true) {
            try {
                val s = adb.open("shell:$cmd")
                val b = ByteArray(1024)
                val end = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < end && s.read(b, 0, b.size) >= 0) { }
                s.close()
            } catch (_: Exception) {}
        }
    }

    fun stop() { adb.close() }
}
