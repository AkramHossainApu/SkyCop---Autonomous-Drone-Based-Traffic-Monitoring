package com.skycop.sim.render

import android.opengl.GLES30.*

internal class Presenter {
    private val program = Program(Shaders.PRESENT_VERTEX, Shaders.PRESENT_FRAGMENT)
    private val image = program.uniform("uImage")
    private val rect = program.uniform("uRect")
    private val texel = program.uniform("uTexel")
    private val aspectUniform = program.uniform("uAspect")
    private val lensUniform = program.uniform("uLens")
    private val shiftUniform = program.uniform("uShift")
    private val vignetteUniform = program.uniform("uVignette")
    private val vao: Int

    init {
        val id = IntArray(1)
        glGenVertexArrays(1, id, 0)
        vao = id[0]
    }

    fun begin() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glDisable(GL_CULL_FACE)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
    }

    fun eye(
        target: RenderTarget, index: Int, eyes: Int, usedWidth: Int, usedHeight: Int,
        screenWidth: Int, screenHeight: Int, lensA: Float, lensB: Float, shift: Float, vignette: Float,
    ) {
        glViewport(index * screenWidth / eyes, 0, screenWidth / eyes, screenHeight)
        program.use()
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, target.texture)
        glUniform1i(image, 0)
        val part = usedWidth.toFloat() / target.width / eyes
        glUniform4f(rect, index * part, 0f, part, usedHeight.toFloat() / target.height)
        glUniform2f(texel, 0.5f / target.width, 0.5f / target.height)
        glUniform1f(aspectUniform, screenWidth.toFloat() / eyes / screenHeight)
        glUniform2f(lensUniform, lensA, lensB)
        glUniform1f(shiftUniform, shift)
        glUniform1f(vignetteUniform, vignette)
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLES, 0, 3)
    }
}
