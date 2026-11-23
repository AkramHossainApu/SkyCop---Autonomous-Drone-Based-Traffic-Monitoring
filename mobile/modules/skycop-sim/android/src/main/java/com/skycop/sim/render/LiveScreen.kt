package com.skycop.sim.render

import android.opengl.GLES30.*
import com.skycop.sim.LiveFeed
import com.skycop.sim.core.DEG
import com.skycop.sim.core.Mat4
import kotlin.math.min
import kotlin.math.tan

/**
 * The live camera in VR: a still picture in each eye, filling it as far as the video's shape allows,
 * with no head tracking. It takes the video's own shape, upright, after applying the rotation a
 * phone camera sends with each frame, so nothing is ever cropped whichever way the camera is held.
 */
internal class LiveScreen {
    private companion object {
        const val DISTANCE = 3f
        const val VERTEX = """#version 300 es
precision highp float;
precision highp int;
uniform mat4 uMatrix;
uniform vec4 uRect;
uniform vec4 uUvLow;
uniform vec4 uUvHigh;
out vec2 vUv;
void main() {
    // Two triangles from the vertex index alone: corners 0 1 2 and 0 2 3, counter-clockwise from
    // bottom left. Arithmetic rather than array lookups, which some drivers get wrong.
    int i = gl_VertexID;
    int c = i < 3 ? i : (i == 3 ? 0 : i - 2);
    float x = (c == 1 || c == 2) ? 1.0 : -1.0;
    float y = c >= 2 ? 1.0 : -1.0;
    vUv = c == 0 ? uUvLow.xy : c == 1 ? uUvLow.zw : c == 2 ? uUvHigh.xy : uUvHigh.zw;
    gl_Position = uMatrix * vec4(x * uRect.x, y * uRect.y, -uRect.z, 1.0);
}"""
        const val FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uY;
uniform sampler2D uU;
uniform sampler2D uV;
uniform vec4 uSolid;
in vec2 vUv;
out vec4 outColor;
void main() {
    if (uSolid.a > 0.5) { outColor = vec4(uSolid.rgb, 1.0); return; }
    float y = texture(uY, vUv).r - 0.0625;
    float u = texture(uU, vUv).r - 0.5;
    float v = texture(uV, vUv).r - 0.5;
    vec3 rgb = vec3(1.164 * y + 1.596 * v, 1.164 * y - 0.392 * u - 0.813 * v, 1.164 * y + 2.017 * u);
    outColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}"""
    }

    private val program = Program(VERTEX, FRAGMENT)
    private val matrix = program.uniform("uMatrix")
    private val rect = program.uniform("uRect")
    private val uvLow = program.uniform("uUvLow")
    private val uvHigh = program.uniform("uUvHigh")
    private val solid = program.uniform("uSolid")
    private val samplers = intArrayOf(program.uniform("uY"), program.uniform("uU"), program.uniform("uV"))
    private val textures = IntArray(3)
    private val vao: Int
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val corners = FloatArray(8)
    private var serial = -1L
    private var videoWidth = 0
    private var videoHeight = 0
    private var rotation = 0

    val hasVideo get() = videoWidth > 0

    /** The dark room around the screen; once for both eyes, since a clear ignores the viewport. */
    fun clear() {
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
    }

    init {
        val id = IntArray(1)
        glGenVertexArrays(1, id, 0)
        vao = id[0]
        glGenTextures(3, textures, 0)
        for (t in textures) {
            glBindTexture(GL_TEXTURE_2D, t)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        }
    }

    /** Uploads the newest decoded frame, if there is one. Called once per rendered frame. */
    fun upload() {
        serial = LiveFeed.latest(serial) { w, h, turn, y, u, v ->
            if (w == 0) {
                videoWidth = 0
                return@latest
            }
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
            val resized = w != videoWidth || h != videoHeight
            val planes = arrayOf(y, u, v)
            for (i in 0..2) {
                val pw = if (i == 0) w else (w + 1) / 2
                val ph = if (i == 0) h else (h + 1) / 2
                glBindTexture(GL_TEXTURE_2D, textures[i])
                if (resized) glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, pw, ph, 0, GL_RED, GL_UNSIGNED_BYTE, planes[i])
                else glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, pw, ph, GL_RED, GL_UNSIGNED_BYTE, planes[i])
            }
            videoWidth = w; videoHeight = h; rotation = ((turn % 360) + 360) % 360
        }
    }

    /** Width over height of the picture as it should be seen, upright. */
    private val uprightAspect: Float
        get() = if (videoWidth == 0) 16f / 9f
        else if (rotation % 180 == 0) videoWidth.toFloat() / videoHeight else videoHeight.toFloat() / videoWidth

    fun drawEye(basis: FloatArray, x: Float, y: Float, z: Float, tanHalfY: Float, viewAspect: Float, vr: Boolean) {
        glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE); glDisable(GL_BLEND)
        if (!hasVideo) return

        val a = uprightAspect
        // As big as each eye allows with the whole frame in view: it fills the eye's height or
        // width, whichever the picture's shape reaches first. In a headset a sliver is kept clear
        // so the lens correction does not push the frame's edges out of sight.
        val fitHeight = DISTANCE * tanHalfY * (if (vr) 0.96f else 1f)
        val halfHeight = min(fitHeight, fitHeight * viewAspect / a)
        val halfWidth = halfHeight * a

        Mat4.perspective(projection, tanHalfY, viewAspect, 0.05f, 50f)
        Mat4.view(view, basis, x, y, z)
        Mat4.multiply(viewProjection, projection, view)

        program.use()
        glUniformMatrix4fv(matrix, 1, false, viewProjection, 0)
        glBindVertexArray(vao)

        // A thin dark frame behind the picture.
        glUniform4f(solid, 0f, 0f, 0f, 1f)
        glUniform4f(rect, halfWidth + 0.04f, halfHeight + 0.04f, DISTANCE + 0.01f, 0f)
        glDrawArrays(GL_TRIANGLES, 0, 6)

        // Corner order: bottom left, bottom right, top right, top left. Display y runs down.
        val dx = floatArrayOf(0f, 1f, 1f, 0f); val dy = floatArrayOf(1f, 1f, 0f, 0f)
        for (c in 0..3) {
            val px = dx[c]; val py = dy[c]
            // Where in the decoded frame each displayed corner comes from, for a clockwise turn.
            when (rotation) {
                90 -> { corners[c * 2] = py; corners[c * 2 + 1] = 1f - px }
                180 -> { corners[c * 2] = 1f - px; corners[c * 2 + 1] = 1f - py }
                270 -> { corners[c * 2] = 1f - py; corners[c * 2 + 1] = px }
                else -> { corners[c * 2] = px; corners[c * 2 + 1] = py }
            }
        }
        glUniform4f(uvLow, corners[0], corners[1], corners[2], corners[3])
        glUniform4f(uvHigh, corners[4], corners[5], corners[6], corners[7])
        glUniform4f(solid, 0f, 0f, 0f, 0f)
        glUniform4f(rect, halfWidth, halfHeight, DISTANCE, 0f)
        for (i in 0..2) {
            glActiveTexture(GL_TEXTURE0 + i)
            glBindTexture(GL_TEXTURE_2D, textures[i])
            glUniform1i(samplers[i], i)
        }
        glDrawArrays(GL_TRIANGLES, 0, 6)
        glActiveTexture(GL_TEXTURE0)
    }
}
