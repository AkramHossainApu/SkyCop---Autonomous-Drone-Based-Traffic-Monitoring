package com.skycop.sim

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decodes the H.264 video that comes through the relay (see ../../../../../../../../../src/frameRelay.ts)
 * on the phone's hardware decoder and hands each picture to [LiveFeed], the same planes the WebRTC
 * path fills. Each message is one access unit in Annex B form; key frames carry SPS and PPS.
 * A preview JPEG of the newest picture is made a few times a second for the app's own screen.
 */
internal object RelayVideo {
    private const val PREVIEW_MS = 160L
    private val worker = Executors.newSingleThreadExecutor()
    private val queued = AtomicInteger(0)
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var waitKey = true
    private var dropped = false
    private var pts = 0L
    private var lastPreview = 0L
    private var nv21 = ByteArray(0)
    @Volatile private var preview: String? = null

    /** Queues one access unit; [gap] says frames were lost before it. Returns a new preview, if any. */
    fun push(unit: ByteArray, gap: Boolean): String? {
        // A decoder that cannot keep up drops whole stretches up to the next key frame.
        if (queued.get() > 4) dropped = true
        else {
            queued.incrementAndGet()
            worker.execute {
                try { decode(unit, gap) } catch (_: Exception) { reset() } finally { queued.decrementAndGet() }
            }
        }
        val out = preview
        preview = null
        return out
    }

    /** Frees the hardware decoder when the relay is no longer used. */
    fun close() {
        worker.execute { reset() }
    }

    private fun reset() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        sps = null
        waitKey = true
    }

    /** Start offsets of the NAL units (after their start codes) and their types. */
    private fun units(data: ByteArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                out.add(Pair(i + 3, data[i + 3].toInt() and 0x1f))
                i += 3
            } else i++
        }
        return out
    }

    private fun slice(data: ByteArray, units: List<Pair<Int, Int>>, index: Int): ByteArray {
        val start = units[index].first
        var end = if (index + 1 < units.size) units[index + 1].first - 3 else data.size
        while (end > start && data[end - 1].toInt() == 0) end--
        return data.copyOfRange(start, end)
    }

    private fun decode(unit: ByteArray, gap: Boolean) {
        if (gap || dropped) { waitKey = true; dropped = false }
        val nals = units(unit)
        val key = nals.any { it.second == 5 }
        if (waitKey && !key) return
        val spsAt = nals.indexOfFirst { it.second == 7 }
        val ppsAt = nals.indexOfFirst { it.second == 8 }
        if (spsAt >= 0 && ppsAt >= 0) {
            val newSps = slice(unit, nals, spsAt)
            if (codec == null || !newSps.contentEquals(sps)) {
                reset()
                val start = byteArrayOf(0, 0, 0, 1)
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(start + newSps))
                    setByteBuffer("csd-1", ByteBuffer.wrap(start + slice(unit, nals, ppsAt)))
                    if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                val next = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                next.configure(format, null, null, 0)
                next.start()
                codec = next
                sps = newSps
            }
        }
        val decoder = codec ?: return
        if (key) waitKey = false
        val input = decoder.dequeueInputBuffer(20_000)
        if (input < 0) { waitKey = true; return }
        decoder.getInputBuffer(input)!!.apply { clear(); put(unit) }
        pts += 66_666
        decoder.queueInputBuffer(input, 0, unit.size, pts, 0)
        // Baseline H.264 comes out right after it goes in; wait briefly for it, then take what is there.
        val info = MediaCodec.BufferInfo()
        var wait = 30_000L
        while (true) {
            val output = decoder.dequeueOutputBuffer(info, wait)
            wait = 0
            if (output == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (output < 0) continue
            try {
                decoder.getOutputImage(output)?.let { show(it) }
            } finally {
                decoder.releaseOutputBuffer(output, false)
            }
        }
    }

    private fun show(image: Image) {
        val crop = image.cropRect ?: Rect(0, 0, image.width, image.height)
        val w = crop.width() and 1.inv(); val h = crop.height() and 1.inv()
        if (w < 2 || h < 2 || image.planes.size < 3) return
        val p = image.planes
        LiveFeed.putPlanes(w, h, crop.left, crop.top,
            p[0].buffer, p[0].rowStride, p[0].pixelStride,
            p[1].buffer, p[1].rowStride, p[1].pixelStride,
            p[2].buffer, p[2].rowStride, p[2].pixelStride)
        val now = System.currentTimeMillis()
        if (now - lastPreview >= PREVIEW_MS) {
            lastPreview = now
            preview = jpeg(w, h, crop.left, crop.top, p)
        }
    }

    private fun jpeg(w: Int, h: Int, left: Int, top: Int, p: Array<Image.Plane>): String? {
        val size = w * h * 3 / 2
        if (nv21.size < size) nv21 = ByteArray(size)
        val y = p[0].buffer; val u = p[1].buffer; val v = p[2].buffer
        var o = 0
        for (row in 0 until h) for (column in 0 until w) nv21[o++] = y.get((top + row) * p[0].rowStride + (left + column) * p[0].pixelStride)
        for (row in 0 until h / 2) for (column in 0 until w / 2) {
            val cy = top / 2 + row; val cx = left / 2 + column
            nv21[o++] = v.get(cy * p[2].rowStride + cx * p[2].pixelStride)
            nv21[o++] = u.get(cy * p[1].rowStride + cx * p[1].pixelStride)
        }
        val out = ByteArrayOutputStream()
        if (!YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 60, out)) return null
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
