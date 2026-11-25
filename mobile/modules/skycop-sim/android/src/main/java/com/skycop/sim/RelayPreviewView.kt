package com.skycop.sim

import android.content.Context
import android.graphics.Bitmap
import android.view.Choreographer
import android.widget.ImageView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's own on-screen preview of relay video: draws every picture [LiveFeed] gets (the same
 * ones the VR view shows), natively, so the preview moves as smoothly as VR without going through
 * JavaScript. The colour conversion runs off the main thread; a picture that comes while the last
 * one is still converting is skipped.
 */
class RelayPreviewView(context: Context, appContext: AppContext) : ExpoView(context, appContext), Choreographer.FrameCallback {
    private companion object {
        val converter = Executors.newSingleThreadExecutor()
    }

    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val busy = AtomicBoolean(false)
    private var running = false
    private var serial = -1L
    /** Two bitmaps: one on screen while the other is filled. */
    private val bitmaps = arrayOfNulls<Bitmap>(2)
    private var flip = 0
    private var argb = IntArray(0)

    init {
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        image.layout(0, 0, r - l, b - t)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        if (busy.get()) return
        var w = 0; var h = 0
        var planes: Triple<ByteArray, ByteArray, ByteArray>? = null
        serial = LiveFeed.latest(serial) { fw, fh, _, y, u, v ->
            if (fw <= 0 || y.remaining() < fw * fh || u.remaining() < fw * fh / 4 || v.remaining() < fw * fh / 4) return@latest
            w = fw; h = fh
            // Copied under LiveFeed's lock; the conversion then runs on its own thread.
            val py = ByteArray(fw * fh); val pu = ByteArray(fw * fh / 4); val pv = ByteArray(fw * fh / 4)
            y.duplicate().get(py); u.duplicate().get(pu); v.duplicate().get(pv)
            planes = Triple(py, pu, pv)
        }
        val (py, pu, pv) = planes ?: return
        busy.set(true)
        val target = flip
        converter.execute {
            try {
                var bitmap = bitmaps[target]
                if (bitmap == null || bitmap.width != w || bitmap.height != h) {
                    bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmaps[target] = bitmap
                }
                if (argb.size < w * h) argb = IntArray(w * h)
                toArgb(w, h, py, pu, pv, argb)
                bitmap!!.setPixels(argb, 0, w, 0, 0, w, h)
                post {
                    if (running) { image.setImageBitmap(bitmap); flip = 1 - target }
                    busy.set(false)
                }
            } catch (_: Exception) {
                busy.set(false)
            }
        }
    }

    /** BT.601 video range, as the decoders give it. */
    private fun toArgb(w: Int, h: Int, py: ByteArray, pu: ByteArray, pv: ByteArray, out: IntArray) {
        val cw = w / 2
        for (row in 0 until h) {
            val line = row * w
            val chroma = (row / 2) * cw
            for (column in 0 until w) {
                val c = chroma + column / 2
                val y = ((py[line + column].toInt() and 255) - 16).coerceAtLeast(0) * 1192
                val u = (pu[c].toInt() and 255) - 128
                val v = (pv[c].toInt() and 255) - 128
                val r = ((y + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((y - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((y + 2066 * u) shr 10).coerceIn(0, 255)
                out[line + column] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
