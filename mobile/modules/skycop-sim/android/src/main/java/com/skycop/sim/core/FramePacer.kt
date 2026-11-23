package com.skycop.sim.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Wall-clock time between render callbacks jitters by a few milliseconds even when frames reach the
 * screen at a perfectly even rate. Snapping to whole refresh periods removes that visible stutter,
 * and the carried error keeps the simulation locked to real time.
 */
class FramePacer(var period: Float) {
    private var error = 0f

    fun next(elapsed: Float): Float {
        val raw = elapsed.coerceIn(0f, 0.1f)
        val periods = (raw / period).roundToInt().coerceAtLeast(1)
        val snapped = periods * period
        val base = if (abs(raw - snapped) < 0.45f * period) snapped else raw
        error += raw - base
        val correction = (error * 0.05f).coerceIn(-0.05f * period, 0.05f * period)
        error -= correction
        return (base + correction).coerceAtLeast(0f)
    }

    fun reset() {
        error = 0f
    }
}
