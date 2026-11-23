package com.skycop.sim.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30.*
import android.opengl.GLUtils
import kotlin.math.hypot

/**
 * Text and shapes as batched quads over one glyph atlas. Nothing is rasterized or uploaded as a
 * bitmap during flight, and drawing does not allocate.
 */
internal class HudBatch {
    private companion object {
        const val CELL_W = 34
        const val CELL_H = 56
        const val COLUMNS = 16
        const val ROWS = 6
        const val MAX_VERTICES = 24_000
        const val FLOATS = 8
        const val SOLID = 95
    }

    private val program = Program(Shaders.HUD_VERTEX, Shaders.HUD_FRAGMENT)
    private val halfSize = program.uniform("uHalfSize")
    private val fontUniform = program.uniform("uFont")
    private val data = FloatArray(MAX_VERTICES * FLOATS)
    private val upload = directFloats(MAX_VERTICES * FLOATS)
    private var count = 0
    private val vao: Int
    private val buffer: Int
    private val texture: Int
    private var r = 1f
    private var g = 1f
    private var b = 1f
    private var a = 1f

    init {
        val width = CELL_W * COLUMNS; val height = CELL_H * ROWS
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 46f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        for (i in 0 until SOLID) {
            canvas.drawText(((i + 32).toChar()).toString(), (i % COLUMNS + 0.5f) * CELL_W, (i / COLUMNS + 0.79f) * CELL_H, paint)
        }
        val sx = (SOLID % COLUMNS * CELL_W).toFloat(); val sy = (SOLID / COLUMNS * CELL_H).toFloat()
        canvas.drawRect(sx, sy, sx + CELL_W, sy + CELL_H, paint)

        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        texture = ids[0]
        glBindTexture(GL_TEXTURE_2D, texture)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        glGenerateMipmap(GL_TEXTURE_2D)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

        glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        glBindVertexArray(vao)
        glGenBuffers(1, ids, 0)
        buffer = ids[0]
        glBindBuffer(GL_ARRAY_BUFFER, buffer)
        val stride = FLOATS * 4
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 8)
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 16)
        glBindVertexArray(0)
    }

    fun begin() {
        count = 0
    }

    fun color(rgb: Int, alpha: Float = 1f) {
        r = ((rgb shr 16) and 255) / 255f; g = ((rgb shr 8) and 255) / 255f; b = (rgb and 255) / 255f; a = alpha
    }

    private fun vertex(x: Float, y: Float, u: Float, v: Float) {
        val at = count * FLOATS
        data[at] = x; data[at + 1] = y; data[at + 2] = u; data[at + 3] = v
        data[at + 4] = r; data[at + 5] = g; data[at + 6] = b; data[at + 7] = a
        count++
    }

    private val solidU = (SOLID % COLUMNS + 0.5f) / COLUMNS
    private val solidV = (SOLID / COLUMNS + 0.5f) / ROWS

    fun triangle(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) {
        if (count + 3 > MAX_VERTICES) return
        vertex(ax, ay, solidU, solidV); vertex(bx, by, solidU, solidV); vertex(cx, cy, solidU, solidV)
    }

    fun rect(x: Float, y: Float, width: Float, height: Float) {
        triangle(x, y, x + width, y, x + width, y + height)
        triangle(x, y, x + width, y + height, x, y + height)
    }

    fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float) {
        val length = hypot(x1 - x0, y1 - y0)
        if (length < 0.001f) return
        val sx = -(y1 - y0) / length * width / 2; val sy = (x1 - x0) / length * width / 2
        triangle(x0 + sx, y0 + sy, x1 + sx, y1 + sy, x1 - sx, y1 - sy)
        triangle(x0 + sx, y0 + sy, x1 - sx, y1 - sy, x0 - sx, y0 - sy)
    }

    /** [align] is 0 for left, 0.5 for centered and 1 for right. [y] is the top of the line. */
    fun text(value: CharSequence, x: Float, y: Float, size: Float, align: Float = 0f) {
        val width = size * CELL_W / CELL_H
        val step = width * 0.86f
        var cx = x - align * value.length * step
        for (i in 0 until value.length) {
            val code = value[i].code - 32
            if (code in 1 until SOLID && count + 6 <= MAX_VERTICES) {
                val u0 = (code % COLUMNS + 0.02f) / COLUMNS; val u1 = (code % COLUMNS + 0.98f) / COLUMNS
                val v0 = (code / COLUMNS + 0.01f) / ROWS; val v1 = (code / COLUMNS + 0.99f) / ROWS
                val left = cx - (width - step) / 2; val right = left + width
                vertex(left, y, u0, v0); vertex(right, y, u1, v0); vertex(right, y + size, u1, v1)
                vertex(left, y, u0, v0); vertex(right, y + size, u1, v1); vertex(left, y + size, u0, v1)
            }
            cx += step
        }
    }

    fun upload() {
        upload.clear()
        upload.put(data, 0, count * FLOATS)
        upload.position(0)
        glBindBuffer(GL_ARRAY_BUFFER, buffer)
        glBufferData(GL_ARRAY_BUFFER, count * FLOATS * 4, upload, GL_STREAM_DRAW)
    }

    fun draw(halfWidth: Float, halfHeight: Float) {
        if (count == 0) return
        program.use()
        glUniform2f(halfSize, halfWidth, halfHeight)
        glUniform1i(fontUniform, 0)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, texture)
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLES, 0, count)
    }
}
