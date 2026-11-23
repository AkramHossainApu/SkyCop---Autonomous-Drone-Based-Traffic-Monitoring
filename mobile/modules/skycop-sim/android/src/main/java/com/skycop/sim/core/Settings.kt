package com.skycop.sim.core

import kotlin.math.abs

/** Written as a single string so the platform layer does not need to know the individual fields. */
class Settings {
    var classicLayout = false
    var vr = true
    var headTracking = true
    var lensCorrection = true
    var lensSpacing = 0
    var comfort = true
    var invertClimb = false
    var invertLook = false
    var sensitivity = 100
    var deadzone = 10
    var sound = true
    var shadows = true
    var wind = true

    fun encode(): String = intArrayOf(
        classicLayout.bit(), vr.bit(), headTracking.bit(), lensCorrection.bit(), lensSpacing, comfort.bit(),
        invertClimb.bit(), invertLook.bit(), sensitivity, deadzone, sound.bit(), shadows.bit(), wind.bit(),
    ).joinToString(",")

    fun decode(text: String) {
        val v = text.split(',').map { it.toIntOrNull() ?: return }
        if (v.size != 13) return
        classicLayout = v[0] == 1; vr = v[1] == 1; headTracking = v[2] == 1; lensCorrection = v[3] == 1
        lensSpacing = v[4].takeIf { abs(it) <= 10 } ?: 0
        comfort = v[5] == 1; invertClimb = v[6] == 1; invertLook = v[7] == 1
        sensitivity = v[8].coerceIn(50, 150); deadzone = v[9].coerceIn(4, 30)
        sound = v[10] == 1; shadows = v[11] == 1; wind = v[12] == 1
    }

    private fun Boolean.bit() = if (this) 1 else 0
}
