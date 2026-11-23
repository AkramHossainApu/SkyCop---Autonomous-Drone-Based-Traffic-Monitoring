package com.skycop.sim.render

import android.opengl.GLES30.*
import kotlin.math.min

/**
 * Off-screen target allocated once at maximum size. Resolution changes only move the viewport, so
 * adapting to a busy or hot phone never causes a reallocation hitch.
 */
internal class RenderTarget {
    var width = 0
        private set
    var height = 0
        private set
    var texture = 0
        private set

    private var framebuffer = 0
    private var samples = 0
    private var resolving = false
    private var multisampleFramebuffer = 0
    private val renderbuffers = IntArray(2)
    private val depthAttachment = intArrayOf(GL_DEPTH_ATTACHMENT)
    private val colorAttachment = intArrayOf(GL_COLOR_ATTACHMENT0)

    /** The EGL context was replaced, so the old ids are meaningless. */
    fun forget() {
        width = 0; height = 0; texture = 0; framebuffer = 0; multisampleFramebuffer = 0
        renderbuffers.fill(0)
    }

    fun resize(newWidth: Int, newHeight: Int, wantedSamples: Int) {
        if (newWidth == width && newHeight == height) return
        release()
        width = newWidth; height = newHeight
        val id = IntArray(1)
        glGenTextures(1, id, 0)
        texture = id[0]
        glBindTexture(GL_TEXTURE_2D, texture)
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glGenFramebuffers(1, id, 0)
        framebuffer = id[0]
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)

        glGetIntegerv(GL_MAX_SAMPLES, id, 0)
        samples = min(wantedSamples, id[0])
        resolving = samples > 1
        glGenFramebuffers(1, id, 0)
        multisampleFramebuffer = id[0]
        glBindFramebuffer(GL_FRAMEBUFFER, multisampleFramebuffer)
        glGenRenderbuffers(2, renderbuffers, 0)
        if (!resolving) {
            // Render straight into the texture; only depth needs a renderbuffer.
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
            glBindRenderbuffer(GL_RENDERBUFFER, renderbuffers[1])
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width, height)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, renderbuffers[1])
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "Render target is incomplete" }
            return
        }
        glBindRenderbuffer(GL_RENDERBUFFER, renderbuffers[0])
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA8, width, height)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, renderbuffers[0])
        glBindRenderbuffer(GL_RENDERBUFFER, renderbuffers[1])
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH_COMPONENT24, width, height)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, renderbuffers[1])
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "Render target is incomplete" }
    }

    fun bindScene() {
        glBindFramebuffer(GL_FRAMEBUFFER, if (resolving) multisampleFramebuffer else framebuffer)
        glDepthMask(true)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
    }

    /** Resolves MSAA and leaves the plain texture bound for the HUD. */
    fun resolve(usedWidth: Int, usedHeight: Int) {
        if (!resolving) {
            glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, depthAttachment, 0)
            return
        }
        glBindFramebuffer(GL_READ_FRAMEBUFFER, multisampleFramebuffer)
        glInvalidateFramebuffer(GL_READ_FRAMEBUFFER, 1, depthAttachment, 0)
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
        glBlitFramebuffer(0, 0, usedWidth, usedHeight, 0, 0, usedWidth, usedHeight, GL_COLOR_BUFFER_BIT, GL_NEAREST)
        glInvalidateFramebuffer(GL_READ_FRAMEBUFFER, 1, colorAttachment, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
    }

    private fun release() {
        if (texture == 0) return
        glDeleteTextures(1, intArrayOf(texture), 0)
        glDeleteFramebuffers(2, intArrayOf(framebuffer, multisampleFramebuffer), 0)
        glDeleteRenderbuffers(2, renderbuffers, 0)
        texture = 0
    }
}
