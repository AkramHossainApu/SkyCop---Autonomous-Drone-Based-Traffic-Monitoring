package com.skycop.sim

import com.facebook.react.bridge.ReactContext
import com.oney.WebRTCModule.SkyCopStreamAccess
import com.oney.WebRTCModule.WebRTCModule
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

/**
 * Receives the live camera's decoded frames from react-native-webrtc and keeps the latest one as
 * tightly packed I420 planes, for the simulator to draw in VR. The app tells it which stream to
 * follow; frames arrive on WebRTC's decoder thread and are read on the GL thread.
 */
internal object LiveFeed : VideoSink {
    private val lock = Any()
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
        if (newTag == null || context == null) return
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
