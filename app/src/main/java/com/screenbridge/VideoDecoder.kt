package com.screenbridge

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface

class VideoDecoder(
    surface: Surface,
    w: Int,
    h: Int,
    private val onSize: (Int, Int) -> Unit
) {
    private val codec: MediaCodec = MediaCodec.createDecoderByType("video/avc")
    private val info = MediaCodec.BufferInfo()

    init {
        val f = MediaFormat.createVideoFormat("video/avc", w, h)
        val m = maxOf(w, h)
        f.setInteger(MediaFormat.KEY_MAX_WIDTH, m)
        f.setInteger(MediaFormat.KEY_MAX_HEIGHT, m)
        f.setInteger(MediaFormat.KEY_PRIORITY, 0)
        if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        codec.configure(f, surface, null, 0)
        codec.start()
    }

    fun feed(data: ByteArray, ptsUs: Long, config: Boolean, key: Boolean) {
        var tries = 0
        while (tries++ < 5) {
            val i = codec.dequeueInputBuffer(50_000)
            if (i >= 0) {
                val buf = codec.getInputBuffer(i)!!
                buf.clear()
                buf.put(data)
                val flags = when {
                    config -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                    key -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                    else -> 0
                }
                codec.queueInputBuffer(i, 0, data.size, ptsUs, flags)
                break
            }
            drain()
        }
        drain()
    }

    private fun drain() {
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 0)
            when {
                o >= 0 -> codec.releaseOutputBuffer(o, true)
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    val w = if (f.containsKey("crop-left") && f.containsKey("crop-right"))
                        f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                    else f.getInteger(MediaFormat.KEY_WIDTH)
                    val h = if (f.containsKey("crop-top") && f.containsKey("crop-bottom"))
                        f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
                    else f.getInteger(MediaFormat.KEY_HEIGHT)
                    onSize(w, h)
                }
                else -> return
            }
        }
    }

    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }
}
