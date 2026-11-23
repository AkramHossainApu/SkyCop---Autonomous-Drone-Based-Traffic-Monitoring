package com.skycop.sim.core

import kotlin.math.*

/**
 * Time of day and weather: where the light comes from, its color, the sky, the haze, how wet the
 * streets are and how gusty the wind is. The renderer reads these every frame; the drone feels the gusts.
 */
class Environment {
    companion object {
        val TIMES = arrayOf("DAY", "SUNSET", "NIGHT", "CYCLE")
        val WEATHERS = arrayOf("CLEAR", "MONSOON RAIN")
        /** Real seconds per hour of the day when the time cycles. */
        const val HOUR_SECONDS = 40f
    }

    /** Hours since midnight. */
    var hour = 10f
        private set
    /** Unit vector toward the light that casts shadows: the sun, or the moon at night. */
    val light = floatArrayOf(0.52f, 0.6f, 0.46f)
    val lightColor = floatArrayOf(3.1f, 2.8f, 2.3f)
    val skyTop = floatArrayOf(0.33f, 0.52f, 0.76f)
    val skyLow = floatArrayOf(0.8f, 0.8f, 0.77f)
    /** 0 by day, 1 at night: lamps, lit windows and headlights. */
    var night = 0f
        private set
    /** How hard it is raining now, 0 to 1. */
    var rain = 0f
        private set
    /** How wet the streets are; they dry slowly after the rain. */
    var wet = 0f
        private set
    var fog = 0.0021f
        private set
    /** Multiplies the wind the drone feels. */
    var gust = 1f
        private set

    private var time = 0f
    private var lastSetting = -1

    fun update(dt: Float, settings: Settings) {
        time += dt
        if (settings.timeOfDay != lastSetting) {
            hour = when (settings.timeOfDay) { 0 -> 10f; 1 -> 17.6f; 2 -> 21.5f; else -> 16.5f }
            lastSetting = settings.timeOfDay
        }
        if (settings.timeOfDay == 3) hour = (hour + dt / HOUR_SECONDS) % 24f
        val raining = settings.weather == 1
        rain += ((if (raining) 1f else 0f) - rain) * min(1f, dt / 6f)
        wet = if (raining) min(1f, wet + dt / 20f) else max(0f, wet - dt / 120f)
        gust = 1f + rain * (1.6f + 0.9f * sin(time * 0.37f) * sin(time * 1.13f))

        // The sun rises in the east (+x) at six and sets in the west at six, leaning south.
        val day = (hour - 6f) / 12f * PI_F
        val sunUp = sin(day)
        val sx = cos(day); val sy = sunUp * 0.62f; val sz = 0.38f
        night = smooth(0.08f, -0.12f, sunUp)
        val low = smooth(0.55f, 0.05f, sunUp)
        if (sunUp > -0.02f) {
            setLight(sx, max(sy, 0.12f), sz)
            val clear = 1f - rain * 0.72f
            val fade = smooth(-0.02f, 0.18f, sunUp)
            lightColor[0] = (3.1f + 0.1f * low) * clear * fade
            lightColor[1] = (2.8f - 1.15f * low) * clear * fade
            lightColor[2] = (2.3f - 1.6f * low) * clear * fade
        } else {
            // Moonlight, high in the south.
            setLight(-0.25f, 0.85f, 0.45f)
            lightColor[0] = 0.16f; lightColor[1] = 0.2f; lightColor[2] = 0.32f
            for (k in 0..2) lightColor[k] *= 1f - rain * 0.7f
        }
        // Sky colors: blue noon, orange dusk, deep blue night, and a gray monsoon overcast over all.
        mix3(skyTop, 0.33f, 0.52f, 0.76f, 0.3f, 0.36f, 0.55f, low)
        mix3(skyLow, 0.8f, 0.8f, 0.77f, 0.95f, 0.62f, 0.4f, low)
        blend(skyTop, 0.03f, 0.05f, 0.11f, night)
        blend(skyLow, 0.1f, 0.1f, 0.14f, night)
        blend(skyTop, 0.42f * (1f - night * 0.85f), 0.44f * (1f - night * 0.85f), 0.47f * (1f - night * 0.85f), rain * 0.85f)
        blend(skyLow, 0.56f * (1f - night * 0.85f), 0.57f * (1f - night * 0.85f), 0.58f * (1f - night * 0.85f), rain * 0.85f)
        fog = 0.0021f + rain * 0.0016f + night * 0.0004f
    }

    private fun setLight(x: Float, y: Float, z: Float) {
        val l = sqrt(x * x + y * y + z * z)
        light[0] = x / l; light[1] = y / l; light[2] = z / l
    }

    private fun smooth(edge0: Float, edge1: Float, v: Float): Float {
        val t = ((v - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun mix3(out: FloatArray, r0: Float, g0: Float, b0: Float, r1: Float, g1: Float, b1: Float, t: Float) {
        out[0] = r0 + (r1 - r0) * t; out[1] = g0 + (g1 - g0) * t; out[2] = b0 + (b1 - b0) * t
    }

    private fun blend(out: FloatArray, r: Float, g: Float, b: Float, t: Float) {
        out[0] += (r - out[0]) * t; out[1] += (g - out[1]) * t; out[2] += (b - out[2]) * t
    }
}
