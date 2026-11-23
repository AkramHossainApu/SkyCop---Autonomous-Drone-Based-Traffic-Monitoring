package com.skycop.sim.input

import android.view.MotionEvent
import com.skycop.sim.core.Button
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** On-screen sticks, used in full-screen mode when no controller is connected. */
internal class TouchSticks {
    companion object {
        const val RADIUS = 72f
        const val CENTER_Y = 98f
        const val ACTION_X = -68f
        const val MENU_X = 68f
        const val ACTION_Y = 150f
    }

    val x = FloatArray(2)
    val y = FloatArray(2)
    private val owner = IntArray(2) { -1 }

    fun centerX(index: Int, halfWidth: Float) = if (index == 0) -halfWidth + 130f else halfWidth - 130f

    /** Returns a button mask for taps on the on-screen buttons. */
    fun handle(event: MotionEvent, width: Int, height: Int): Int {
        if (width == 0 || height == 0) return 0
        val scale = 540f / height
        val halfWidth = width * scale / 2
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val px = event.getX(i) * scale - halfWidth; val py = event.getY(i) * scale - 270f
                if (abs(py - ACTION_Y) < 28f) {
                    if (abs(px - ACTION_X) < 60f) return Button.A
                    if (abs(px - MENU_X) < 60f) return Button.START
                }
                val stick = if (px < 0) 0 else 1
                if (owner[stick] < 0 && hypot(px - centerX(stick, halfWidth), py - CENTER_Y) < RADIUS * 1.6f) {
                    owner[stick] = event.getPointerId(i)
                    move(stick, px, py, halfWidth)
                }
            }
            MotionEvent.ACTION_MOVE -> for (stick in 0..1) {
                if (owner[stick] < 0) continue
                val i = event.findPointerIndex(owner[stick])
                if (i >= 0) move(stick, event.getX(i) * scale - halfWidth, event.getY(i) * scale - 270f, halfWidth)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                for (stick in 0..1) if (owner[stick] == id) release(stick)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> clear()
        }
        return 0
    }

    private fun move(stick: Int, px: Float, py: Float, halfWidth: Float) {
        var dx = (px - centerX(stick, halfWidth)) / RADIUS
        var dy = -(py - CENTER_Y) / RADIUS
        val magnitude = max(1f, hypot(dx, dy))
        dx /= magnitude; dy /= magnitude
        x[stick] = if (abs(dx) < 0.12f) 0f else dx
        y[stick] = if (abs(dy) < 0.12f) 0f else dy
    }

    private fun release(stick: Int) {
        owner[stick] = -1; x[stick] = 0f; y[stick] = 0f
    }

    fun clear() {
        release(0); release(1)
    }
}
