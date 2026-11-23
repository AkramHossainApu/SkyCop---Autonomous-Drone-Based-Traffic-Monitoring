package com.skycop.sim.render

import android.opengl.GLES11Ext
import android.opengl.GLES30.*
import com.skycop.sim.core.Tex
import com.skycop.sim.core.Textures
import kotlin.math.min

/** Uploads the painted surfaces as two mipmapped texture arrays. */
internal class TextureAtlas(maxAnisotropy: Float) {
    val albedo: Int
    val relief: Int

    init {
        val pixels = Textures.get()
        var anisotropy = 1f
        if (glGetString(GL_EXTENSIONS)?.contains("texture_filter_anisotropic") == true) {
            val supported = FloatArray(1)
            glGetFloatv(GLES11Ext.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, supported, 0)
            anisotropy = min(maxAnisotropy, supported[0])
        }
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        albedo = upload(GL_RGBA8, GL_RGBA, pixels.color, anisotropy)
        relief = upload(GL_RG8, GL_RG, pixels.normal, anisotropy)
    }

    private fun upload(internalFormat: Int, format: Int, data: java.nio.ByteBuffer, anisotropy: Float): Int {
        val id = IntArray(1)
        glGenTextures(1, id, 0)
        glBindTexture(GL_TEXTURE_2D_ARRAY, id[0])
        val size = Textures.SIZE
        val layers = Tex.values().size
        val levels = 32 - Integer.numberOfLeadingZeros(size)
        glTexStorage3D(GL_TEXTURE_2D_ARRAY, levels, internalFormat, size, size, layers)
        val source = data.duplicate()
        source.position(0)
        glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, size, size, layers, format, GL_UNSIGNED_BYTE, source)
        glGenerateMipmap(GL_TEXTURE_2D_ARRAY)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT)
        if (anisotropy > 1f) {
            glTexParameterf(GL_TEXTURE_2D_ARRAY, GLES11Ext.GL_TEXTURE_MAX_ANISOTROPY_EXT, anisotropy)
        }
        return id[0]
    }
}
