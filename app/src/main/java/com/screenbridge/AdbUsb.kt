package com.screenbridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

const val A_CNXN = 0x4e584e43
const val A_AUTH = 0x48545541
const val A_OPEN = 0x4e45504f
const val A_OKAY = 0x59414b4f
const val A_CLSE = 0x45534c43
const val A_WRTE = 0x45545257

class AdbMsg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

class AdbStream(private val c: AdbUsbConnection, val localId: Int) : Closeable {
    @Volatile var remoteId = 0
    @Volatile var closed = false
    val ready = Semaphore(0)
    val queue = LinkedBlockingQueue<ByteArray>()
    private var cur: ByteArray? = null
    private var pos = 0

    companion object { val EOF = ByteArray(0) }

    fun closeRemote() {
        closed = true
        ready.release()
        queue.put(EOF)
    }

    fun write(b: ByteArray, off: Int = 0, len: Int = b.size) {
        var o = off
        var rem = len
        while (rem > 0) {
            if (closed) throw IOException("stream closed")
            val n = minOf(rem, c.maxData)
            c.send(A_WRTE, localId, remoteId, b.copyOfRange(o, o + n))
            o += n
            rem -= n
            if (!ready.tryAcquire(10, TimeUnit.SECONDS) || closed) throw IOException("write failed")
        }
    }

    fun read(b: ByteArray, off: Int, len: Int): Int {
        var d: ByteArray = cur ?: EOF
        if (cur == null || pos >= d.size) {
            d = queue.take()
            if (d === EOF) { queue.put(EOF); return -1 }
            cur = d
            pos = 0
        }
        val n = minOf(len, d.size - pos)
        System.arraycopy(d, pos, b, off, n)
        pos += n
        return n
    }

    fun readFully(b: ByteArray, len: Int = b.size): Boolean {
        var o = 0
        while (o < len) {
            val r = read(b, o, len - o)
            if (r < 0) return false
            o += r
        }
        return true
    }

    override fun close() {
        if (!closed) {
            closed = true
            try { c.send(A_CLSE, localId, remoteId) } catch (_: Exception) {}
        }
        c.removeStream(localId)
        queue.put(EOF)
    }
}

class AdbUsbConnection(
    private val usb: UsbManager,
    private val dev: UsbDevice,
    private val key: AdbKey
) : Closeable {
    private var conn: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private lateinit var epIn: UsbEndpoint
    private lateinit var epOut: UsbEndpoint
    @Volatile var maxData = 4096
    @Volatile var closed = false
    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val ids = AtomicInteger(1)
    private val wlock = Any()

    companion object {
        fun findInterface(d: UsbDevice): UsbInterface? {
            for (i in 0 until d.interfaceCount) {
                val f = d.getInterface(i)
                if (f.interfaceClass == 255 && f.interfaceSubclass == 0x42 && f.interfaceProtocol == 1) return f
            }
            return null
        }
    }

    fun connect() {
        val f = findInterface(dev) ?: throw IOException("لا توجد واجهة ADB في الجهاز")
        val c = usb.openDevice(dev) ?: throw IOException("تعذر فتح جهاز USB")
        if (!c.claimInterface(f, true)) throw IOException("تعذر حجز واجهة ADB")
        conn = c
        iface = f
        for (i in 0 until f.endpointCount) {
            val e = f.getEndpoint(i)
            if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (e.direction == UsbConstants.USB_DIR_IN) epIn = e else epOut = e
            }
        }
        send(A_CNXN, 0x01000001, 256 * 1024, "host::\u0000".toByteArray())
        var sigSent = false
        while (true) {
            val m = read() ?: throw IOException("انقطع الاتصال أثناء المصادقة")
            if (m.cmd == A_CNXN) {
                maxData = minOf(m.arg1, 65536).coerceAtLeast(4096)
                break
            } else if (m.cmd == A_AUTH && m.arg0 == 1) {
                if (!sigSent) {
                    send(A_AUTH, 2, 0, key.sign(m.data))
                    sigSent = true
                } else {
                    send(A_AUTH, 3, 0, key.publicKeyBlob())
                }
            }
        }
        Thread(::readerLoop, "adb-reader").apply { isDaemon = true }.start()
    }

    fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        synchronized(wlock) {
            var sum = 0
            for (b in data) sum += (b.toInt() and 0xff)
            val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            h.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(sum).putInt(cmd.inv())
            write(h.array(), 24)
            if (data.isNotEmpty()) write(data, data.size)
        }
    }

    private fun write(b: ByteArray, len: Int) {
        var o = 0
        while (o < len) {
            val n = minOf(16384, len - o)
            val r = conn!!.bulkTransfer(epOut, b, o, n, 5000)
            if (r < 0) throw IOException("فشلت الكتابة عبر USB")
            o += r
        }
    }

    private fun readFully(b: ByteArray, len: Int, timeout: Int): Boolean {
        var o = 0
        while (o < len) {
            val n = minOf(16384, len - o)
            val r = conn?.bulkTransfer(epIn, b, o, n, timeout) ?: return false
            if (r < 0) return false
            o += r
        }
        return true
    }

    private fun read(): AdbMsg? {
        val h = ByteArray(24)
        if (!readFully(h, 24, 0)) return null
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val a0 = bb.int
        val a1 = bb.int
        val len = bb.int
        val data = ByteArray(len)
        if (len > 0 && !readFully(data, len, 10000)) return null
        return AdbMsg(cmd, a0, a1, data)
    }

    private fun readerLoop() {
        try {
            while (!closed) {
                val m = read() ?: break
                when (m.cmd) {
                    A_OKAY -> streams[m.arg1]?.let { it.remoteId = m.arg0; it.ready.release() }
                    A_WRTE -> {
                        val s = streams[m.arg1]
                        if (s != null) {
                            s.remoteId = m.arg0
                            s.queue.put(m.data)
                            send(A_OKAY, s.localId, s.remoteId)
                        }
                    }
                    A_CLSE -> streams.remove(m.arg1)?.closeRemote()
                }
            }
        } catch (_: Exception) {
        } finally {
            closed = true
            streams.values.forEach { it.closeRemote() }
            streams.clear()
        }
    }

    fun open(dest: String): AdbStream {
        val id = ids.getAndIncrement()
        val s = AdbStream(this, id)
        streams[id] = s
        send(A_OPEN, id, 0, (dest + "\u0000").toByteArray())
        if (!s.ready.tryAcquire(10, TimeUnit.SECONDS) || s.closed) {
            streams.remove(id)
            throw IOException("تعذر فتح $dest")
        }
        return s
    }

    fun removeStream(id: Int) { streams.remove(id) }

    override fun close() {
        closed = true
        streams.values.forEach { it.closeRemote() }
        streams.clear()
        try { iface?.let { conn?.releaseInterface(it) } } catch (_: Exception) {}
        try { conn?.close() } catch (_: Exception) {}
    }
}
