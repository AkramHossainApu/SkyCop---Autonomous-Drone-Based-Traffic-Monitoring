package com.skycop.sim.render

import kotlin.math.abs

/** Trades resolution for frame rate, so the simulation stays smooth as a phone heats up. */
internal class QualityGovernor {
    private companion object {
        const val MIN_SCALE = 0.5f
        const val START_SCALE = 0.85f
        const val WINDOW = 1f
    }

    var scale = START_SCALE
        private set
    var level = 0
        private set
    var period = 1f / 60f
        private set
    var fps = 0
        private set
    var worstFrameMs = 0
        private set

    private var bestLevel = 0
    private var refresh = 1f / 60f
    private var sum = 0f
    private var frames = 0
    private var worst = 0f
    private var warmup = 2f
    private var good = 0f
    private var bad = 0f
    private var retryAfter = 4f
    private var sinceIncrease = 100f

    fun start(refreshRate: Float, startLevel: Int) {
        bestLevel = startLevel
        level = startLevel
        refresh = 1f / refreshRate.coerceIn(30f, 165f)
        period = refresh
        scale = START_SCALE
        sum = 0f; frames = 0; worst = 0f; good = 0f; bad = 0f
        warmup = 2f; retryAfter = 4f
    }

    /** Returns true once per measurement window. */
    fun frame(dt: Float): Boolean {
        if (dt > 0.5f) return false
        sum += dt; frames++
        if (dt > worst) worst = dt
        if (sum < WINDOW) return false
        val average = sum / frames
        fps = (1f / average + 0.5f).toInt()
        worstFrameMs = (worst * 1000).toInt()
        val window = sum
        sum = 0f; frames = 0; worst = 0f
        sinceIncrease += window
        if (warmup > 0f) {
            warmup -= window
            return true
        }
        if (average > period * 1.08f) {
            good = 0f
            if (scale > MIN_SCALE) {
                // A step up that did not hold: wait longer before trying again.
                if (sinceIncrease < 3f) retryAfter = (retryAfter * 2f).coerceAtMost(60f)
                scale = (scale - if (average > period * 1.3f) 0.12f else 0.06f).coerceAtLeast(MIN_SCALE)
            } else {
                // An even lower rate looks smoother than constantly missing a higher one.
                bad += window
                if (level < QUALITY_LEVELS.size - 1) {
                    if (bad >= 2f) {
                        level++
                        scale = 0.65f
                        bad = 0f
                    }
                    return true
                }
                val half = period * 2f
                if (bad > 2f && half <= 1f / 29f && abs(average - half) < half * 0.25f) {
                    period = half
                    scale = 0.7f
                    bad = 0f
                }
            }
        } else {
            bad = 0f
            good += window
            if (good >= retryAfter && scale < 1f) {
                scale = (scale + 0.08f).coerceAtMost(1f)
                good = 0f; sinceIncrease = 0f
            }
            if (average < period * 0.7f) period = refresh
            // Geometry comes back only after a long stretch of headroom, so detail does not flicker.
            if (scale >= 1f && level > bestLevel && good >= 10f) {
                level--
                good = 0f
            }
        }
        return true
    }
}
