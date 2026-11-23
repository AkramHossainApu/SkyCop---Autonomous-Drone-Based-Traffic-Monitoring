package com.skycop.sim.render

import android.opengl.GLES30.*
import android.util.Log

/** Costs that resolution scaling cannot reduce, chosen from the GPU when the surface is created. */
internal class GpuProfile(val renderer: String) {
    /**
     * Mid-range and older mobile GPUs. They skip MSAA, whose resolve costs these chips most of their
     * memory bandwidth, and start one quality level down. `adb shell setprop log.tag.SkyCopLowGpu DEBUG`
     * forces this profile for testing.
     */
    val modest: Boolean = Log.isLoggable("SkyCopLowGpu", Log.DEBUG) || isModest(renderer)

    val samples = if (modest) 0 else 4
    val maxHeight = if (modest) 900 else 1080
    val shadowSize = if (modest) 1024 else 2048
    val startLevel = if (modest) 1 else 0
    val anisotropy = if (modest) 2f else 8f

    private fun isModest(name: String): Boolean {
        val adreno = Regex("Adreno \\(TM\\) (\\d+)").find(name)
        if (adreno != null) return adreno.groupValues[1].toInt().let { it < 640 }
        if (name.contains("Mali-T") || name.contains("PowerVR")) return true
        val mali = Regex("Mali-G(\\d+)").find(name)
        // Two-digit Malis below G77, plus the small three-digit parts (G310, G510, G610).
        if (mali != null) return mali.groupValues[1].toInt().let { it < 77 || it in 100..619 }
        return false
    }

    companion object {
        fun detect() = GpuProfile(glGetString(GL_RENDERER) ?: "")
    }
}

/** 0 is full detail; each higher level draws less geometry. */
internal class QualityLevel(val detailRange: Float, val shadowDetailRange: Float, val carRange: Float, val shadows: Boolean, val relief: Boolean)

internal val QUALITY_LEVELS = arrayOf(
    QualityLevel(210f, 120f, 300f, true, true),
    QualityLevel(125f, 55f, 210f, true, true),
    QualityLevel(90f, 0f, 150f, false, false),
)
