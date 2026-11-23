package com.skycop.sim.render

import android.opengl.GLES30.*
import com.skycop.sim.core.MeshBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal fun directFloats(count: Int): FloatBuffer =
    ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

internal class Program(vertexSource: String, fragmentSource: String) {
    private val id: Int

    init {
        val vertex = compile(GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GL_FRAGMENT_SHADER, fragmentSource)
        id = glCreateProgram()
        glAttachShader(id, vertex); glAttachShader(id, fragment)
        glLinkProgram(id)
        val ok = IntArray(1)
        glGetProgramiv(id, GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader link failed: ${glGetProgramInfoLog(id)}" }
        glDeleteShader(vertex); glDeleteShader(fragment)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        val ok = IntArray(1)
        glGetShaderiv(shader, GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader compile failed: ${glGetShaderInfoLog(shader)}" }
        return shader
    }

    fun use() = glUseProgram(id)
    fun uniform(name: String) = glGetUniformLocation(id, name)
}

/** Static vertex data, optionally paired with a per-frame instance buffer. */
internal class GpuMesh(builder: MeshBuilder, private val maxInstances: Int = 0) {
    private val count = builder.vertices
    private val vao: Int
    private val vertexBuffer: Int
    private val instanceBuffer: Int
    private val instances: FloatBuffer? = if (maxInstances > 0) directFloats(maxInstances * 8) else null
    private var instanceCount = 0

    init {
        val ids = IntArray(1)
        glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        glBindVertexArray(vao)
        glGenBuffers(1, ids, 0)
        vertexBuffer = ids[0]
        glBindBuffer(GL_ARRAY_BUFFER, ids[0])
        val bytes = ByteBuffer.allocateDirect(count * 20).order(ByteOrder.nativeOrder())
        bytes.asIntBuffer().put(builder.data, 0, count * 5)
        glBufferData(GL_ARRAY_BUFFER, count * 20, bytes, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 20, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_BYTE, true, 20, 12)
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true, 20, 16)
        if (maxInstances > 0) {
            glGenBuffers(1, ids, 0)
            instanceBuffer = ids[0]
            glBindBuffer(GL_ARRAY_BUFFER, instanceBuffer)
            glBufferData(GL_ARRAY_BUFFER, maxInstances * 32, null, GL_STREAM_DRAW)
            glEnableVertexAttribArray(3); glVertexAttribPointer(3, 4, GL_FLOAT, false, 32, 0)
            glEnableVertexAttribArray(4); glVertexAttribPointer(4, 4, GL_FLOAT, false, 32, 16)
            glVertexAttribDivisor(3, 1); glVertexAttribDivisor(4, 1)
        } else {
            instanceBuffer = 0
        }
        glBindVertexArray(0)
    }

    /** Frees the GPU buffers; the mesh must not be drawn again. */
    fun release() {
        glDeleteBuffers(1, intArrayOf(vertexBuffer), 0)
        if (instanceBuffer != 0) glDeleteBuffers(1, intArrayOf(instanceBuffer), 0)
        glDeleteVertexArrays(1, intArrayOf(vao), 0)
    }

    fun beginInstances() {
        instances?.clear()
        instanceCount = 0
    }

    fun addInstance(x: Float, y: Float, z: Float, yaw: Float, color: Int) {
        val out = instances ?: return
        if (instanceCount == maxInstances) return
        out.put(x); out.put(y); out.put(z); out.put(yaw)
        out.put(((color shr 16) and 255) / 255f); out.put(((color shr 8) and 255) / 255f)
        out.put((color and 255) / 255f); out.put(1f)
        instanceCount++
    }

    fun uploadInstances() {
        val out = instances ?: return
        if (instanceCount == 0) return
        out.position(0)
        glBindBuffer(GL_ARRAY_BUFFER, instanceBuffer)
        // Re-specifying the store lets the driver hand back fresh memory instead of waiting on the GPU.
        glBufferData(GL_ARRAY_BUFFER, maxInstances * 32, null, GL_STREAM_DRAW)
        glBufferSubData(GL_ARRAY_BUFFER, 0, instanceCount * 32, out)
    }

    fun draw() {
        if (count == 0) return
        glBindVertexArray(vao)
        if (maxInstances == 0) {
            glVertexAttrib4f(3, 0f, 0f, 0f, 0f)
            glVertexAttrib4f(4, 1f, 1f, 1f, 1f)
            glDrawArrays(GL_TRIANGLES, 0, count)
        } else if (instanceCount > 0) {
            glDrawArraysInstanced(GL_TRIANGLES, 0, count, instanceCount)
        }
    }
}
