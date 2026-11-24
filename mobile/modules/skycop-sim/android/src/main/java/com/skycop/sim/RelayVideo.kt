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
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plays relay frames at the pace they were filmed. The brokers deliver them in bursts and pauses;
 * each frame is shown at its capture time plus a delay just long enough to cover how unevenly they
 * have been arriving lately (RelayVideo.swift has the same logic).
 */
internal class Pacer {
    private val delays = LongArray(90)
    private var count = 0
    private var next = 0
    private var lastDue = 0L
    /** Extra delay after a frame came too late: the picture pauses once instead of rushing to catch up. */
    private var shift = 0L

    /** The local time (uptime ms) at which a frame captured at [captured] (camera ms) should show. */
    fun due(captured: Long, arrived: Long): Long {
        val raw = arrived - captured
        // The camera started over (its clock jumped): forget what was learned.
        if (count > 0 && kotlin.math.abs(raw - delays[(next + delays.size - 1) % delays.size]) > 4000) { count = 0; next = 0 }
        delays[next] = raw; next = (next + 1) % delays.size; if (count < delays.size) count++
        val sorted = delays.copyOf(count).also { it.sort() }
        val fastest = sorted[0]
        val slow = sorted[((count - 1) * 95) / 100]
        val buffer = (slow - fastest + 60).coerceIn(100, 1000)
        // Running late eases back down a little with every frame (about 30 ms a second).
        if (shift > 0) shift = (shift - 2).coerceAtLeast(0)
        var due = captured + fastest + buffer + shift
        val now = SystemClock.uptimeMillis()
        if (due < now) { shift += now - due; due = now }
        if (due < lastDue) due = lastDue
        if (due > now + 1500) { shift = 0; due = now }
        lastDue = due
        return due
    }

    fun reset() { count = 0; next = 0; lastDue = 0; shift = 0 }
}

/**
 * Decodes the H.264 video that comes through the relay (see ../../../../../../../../../src/frameRelay.ts)
 * on the phone's hardware decoder and hands each picture to [LiveFeed], the same planes the WebRTC
 * path fills. Each message is one access unit in Annex B form; key frames carry SPS and PPS.
 * A preview JPEG of the newest picture is made a few times a second for the app's own screen.
 */
internal object RelayVideo {
    private const val PREVIEW_MS = 500L
    private val worker = Executors.newSingleThreadExecutor()
    /** Shows decoded frames when they are due. */
    private val display = Executors.newSingleThreadScheduledExecutor()
    private val pacer = Pacer()
    private val waitingFrames = AtomicInteger(0)
    private val pool = ArrayDeque<Frame>()
    private var due = 0L

    private class Frame(val w: Int, val h: Int) {
        val y = ByteArray(w * h); val u = ByteArray(w * h / 4); val v = ByteArray(w * h / 4)
    }
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
    fun push(unit: ByteArray, gap: Boolean, captured: Long): String? {
        val arrived = SystemClock.uptimeMillis()
        // A decoder that cannot keep up drops whole stretches up to the next key frame.
        if (queued.get() > 40) dropped = true
        else {
            queued.incrementAndGet()
            worker.execute {
                try {
                    due = pacer.due(captured, arrived)
                    decode(unit, gap)
                } catch (_: Exception) { reset() } finally { queued.decrementAndGet() }
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
        pacer.reset()
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
        var wait = 8_000L
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
        val frame = synchronized(pool) { pool.pollFirst()?.takeIf { it.w == w && it.h == h } } ?: Frame(w, h)
        copy(p[0], crop.left, crop.top, w, h, frame.y)
        copy(p[1], crop.left / 2, crop.top / 2, w / 2, h / 2, frame.u)
        copy(p[2], crop.left / 2, crop.top / 2, w / 2, h / 2, frame.v)
        // Too far behind to wait for its turn: show it now.
        val wait = if (waitingFrames.get() > 20) 0L else (due - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        waitingFrames.incrementAndGet()
        display.schedule({
            try { present(frame) } catch (_: Exception) {} finally {
                waitingFrames.decrementAndGet()
                synchronized(pool) { if (pool.size < 24) pool.addLast(frame) }
            }
        }, wait, TimeUnit.MILLISECONDS)
    }

    private fun copy(plane: Image.Plane, left: Int, top: Int, w: Int, h: Int, out: ByteArray) {
        val buffer = plane.buffer; val row = plane.rowStride; val step = plane.pixelStride
        var o = 0
        if (step == 1) {
            val src = buffer.duplicate()
            for (r in 0 until h) {
                src.position((top + r) * row + left)
                src.get(out, o, w)
                o += w
            }
        } else {
            for (r in 0 until h) {
                val at = (top + r) * row + left * step
                for (c in 0 until w) out[o++] = buffer.get(at + c * step)
            }
        }
    }

    private fun present(frame: Frame) {
        LiveFeed.putI420(frame.w, frame.h, frame.y, frame.u, frame.v)
        val now = System.currentTimeMillis()
        if (now - lastPreview >= PREVIEW_MS) {
            lastPreview = now
            preview = jpeg(frame)
        }
    }

    private fun jpeg(frame: Frame): String? {
        val w = frame.w; val h = frame.h
        val size = w * h * 3 / 2
        if (nv21.size < size) nv21 = ByteArray(size)
        System.arraycopy(frame.y, 0, nv21, 0, w * h)
        var o = w * h
        for (i in 0 until w * h / 4) { nv21[o++] = frame.v[i]; nv21[o++] = frame.u[i] }
        val out = ByteArrayOutputStream()
        if (!YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 60, out)) return null
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
