package com.skycop.sim

import com.facebook.react.bridge.ReactContext
import com.oney.WebRTCModule.SkyCopStreamAccess
import com.oney.WebRTCModule.WebRTCModule
import android.graphics.BitmapFactory
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receives the live camera's decoded frames from react-native-webrtc and keeps the latest one as
 * tightly packed I420 planes, for the simulator to draw in VR. The app tells it which stream to
 * follow; frames arrive on WebRTC's decoder thread and are read on the GL thread.
 */
internal object LiveFeed : VideoSink {
    /** The tag for video that comes as JPEG frames through the relay instead of over WebRTC. */
    const val RELAY = "relay"

    private val lock = Any()
    private val decoder = Executors.newSingleThreadExecutor()
    private val decoding = AtomicBoolean(false)
    private var pixels = IntArray(0)
    @Volatile private var track: VideoTrack? = null
    @Volatile var tag: String? = null
        private set

    private var y: ByteBuffer = ByteBuffer.allocateDirect(1)
    private var u: ByteBuffer = ByteBuffer.allocateDirect(1)
    private var v: ByteBuffer = ByteBuffer.allocateDirect(1)
    private var width = 0
    private var height = 0
    private var rotation = 0
    private var serial = 0L

    val available get() = tag != null

    fun follow(context: ReactContext?, newTag: String?) {
        if (newTag == tag) return
        tag = newTag
        val old = track
        track = null
        if (old != null) SkyCopStreamAccess.onExecutor { try { old.removeSink(this) } catch (_: Exception) {} }
        synchronized(lock) { width = 0; serial++ }
        if (newTag == null || newTag == RELAY || context == null) return
        val module = try { context.getNativeModule(WebRTCModule::class.java) } catch (_: Exception) { null } ?: return
        SkyCopStreamAccess.videoTrack(module, newTag) { found ->
            val next = found ?: return@videoTrack
            if (tag != newTag) return@videoTrack
            try {
                next.addSink(this)
                track = next
            } catch (_: Exception) {
            }
        }
    }

    override fun onFrame(frame: VideoFrame) {
        val i420 = try { frame.buffer.toI420() } catch (_: Exception) { null } ?: return
        try {
            val w = i420.width; val h = i420.height
            val cw = (w + 1) / 2; val ch = (h + 1) / 2
            synchronized(lock) {
                if (y.capacity() < w * h) y = ByteBuffer.allocateDirect(w * h)
                if (u.capacity() < cw * ch) u = ByteBuffer.allocateDirect(cw * ch)
                if (v.capacity() < cw * ch) v = ByteBuffer.allocateDirect(cw * ch)
                pack(i420.dataY, i420.strideY, w, h, y)
                pack(i420.dataU, i420.strideU, cw, ch, u)
                pack(i420.dataV, i420.strideV, cw, ch, v)
                width = w; height = h; rotation = frame.rotation
                serial++
            }
        } finally {
            i420.release()
        }
    }

    /**
     * A JPEG frame from the relay, decoded off the caller's thread into the same I420 planes the
     * WebRTC path fills. A frame that arrives while the previous one is still decoding is dropped.
     */
    fun pushJpeg(jpeg: ByteArray) {
        if (tag != RELAY || !decoding.compareAndSet(false, true)) return
        decoder.execute {
            try {
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@execute
                val w = bitmap.width and 1.inv(); val h = bitmap.height and 1.inv()
                if (w < 2 || h < 2) return@execute
                if (pixels.size < w * h) pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                bitmap.recycle()
                val cw = w / 2; val ch = h / 2
                synchronized(lock) {
                    if (tag != RELAY) return@synchronized
                    if (y.capacity() < w * h) y = ByteBuffer.allocateDirect(w * h)
                    if (u.capacity() < cw * ch) u = ByteBuffer.allocateDirect(cw * ch)
                    if (v.capacity() < cw * ch) v = ByteBuffer.allocateDirect(cw * ch)
                    y.clear(); u.clear(); v.clear()
                    // BT.601 video range, as WebRTC delivers it.
                    for (row in 0 until h) for (column in 0 until w) {
                        val p = pixels[row * w + column]
                        val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                        y.put((((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                        if (row and 1 == 0 && column and 1 == 0) {
                            u.put((((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                            v.put((((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
                        }
                    }
                    y.flip(); u.flip(); v.flip()
                    width = w; height = h; rotation = 0
                    serial++
                }
            } catch (_: Exception) {
            } finally {
                decoding.set(false)
            }
        }
    }

    private fun pack(source: ByteBuffer, stride: Int, w: Int, h: Int, out: ByteBuffer) {
        out.clear()
        val src = source.duplicate()
        if (stride == w) {
            src.position(0).limit(w * h)
            out.put(src)
        } else {
            for (row in 0 until h) {
                src.limit(row * stride + w).position(row * stride)
                out.put(src)
            }
        }
        out.flip()
    }

    /** Hands the newest frame to [use] if it is newer than [since]; returns its serial. */
    fun latest(since: Long, use: (width: Int, height: Int, rotation: Int, y: ByteBuffer, u: ByteBuffer, v: ByteBuffer) -> Unit): Long {
        synchronized(lock) {
            if (serial == since) return since
            if (width > 0) use(width, height, rotation, y, u, v) else use(0, 0, 0, y, u, v)
            return serial
        }
    }
}
