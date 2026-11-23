package com.skycop.sim.core

import kotlin.math.*
import kotlin.random.Random

/**
 * People on the footpaths: each walks along a stretch of pavement at their own pace, carries on to
 * the next stretch of the same street, and turns back where the pavement ends.
 */
class Pedestrians(private val city: City) {
    companion object {
        const val MAX = 1400
        /** Shirts, saris and panjabis: the vertex color the walker model is tinted with. */
        val CLOTHES = intArrayOf(0xF2F0E8, 0x2D5DA8, 0xC8343C, 0x3C8C4A, 0xE8B83A, 0x7A3A8C, 0xE07A2A, 0x1F2A3A, 0xD87AA0, 0x6A7A82)
    }

    private val walkways = city.walkways
    private val segments = city.walkwayCount
    private val next = IntArray(segments) { -1 }
    private val previous = IntArray(segments) { -1 }
    private val length = FloatArray(segments)

    val count = min(MAX, (segments * 0.6f).toInt())
    private val segment = IntArray(count)
    private val along = FloatArray(count)
    private val heading = FloatArray(count)
    private val speed = FloatArray(count)
    private val offset = FloatArray(count)
    val x = FloatArray(count)
    val z = FloatArray(count)
    val yaw = FloatArray(count)
    val color = IntArray(count)
    /** Where each walker is in their stride, in steps. */
    val stride = FloatArray(count)

    init {
        for (s in 0 until segments) {
            val i = s * 4
            length[s] = hypot(walkways[i + 2] - walkways[i], walkways[i + 3] - walkways[i + 1])
            if (s > 0 && close(walkways[i], walkways[i + 1], walkways[i - 2], walkways[i - 1])) {
                previous[s] = s - 1; next[s - 1] = s
            }
        }
        val random = Random(99)
        for (p in 0 until count) {
            segment[p] = random.nextInt(segments)
            along[p] = random.nextFloat() * length[segment[p]]
            heading[p] = if (random.nextBoolean()) 1f else -1f
            speed[p] = 0.9f + random.nextFloat() * 0.6f
            offset[p] = random.nextFloat() * 1.2f - 0.6f
            color[p] = CLOTHES[random.nextInt(CLOTHES.size)]
            stride[p] = random.nextFloat() * 2f
        }
        update(0f)
    }

    private fun close(ax: Float, az: Float, bx: Float, bz: Float) = abs(ax - bx) < 0.2f && abs(az - bz) < 0.2f

    fun update(dt: Float) {
        for (p in 0 until count) {
            var s = segment[p]
            along[p] += heading[p] * speed[p] * dt
            stride[p] = (stride[p] + speed[p] * dt * 1.6f) % 2f
            if (along[p] > length[s]) {
                if (next[s] >= 0) { along[p] -= length[s]; s = next[s] } else { along[p] = length[s]; heading[p] = -1f }
            } else if (along[p] < 0f) {
                if (previous[s] >= 0) { s = previous[s]; along[p] += length[s] } else { along[p] = 0f; heading[p] = 1f }
            }
            segment[p] = s
            val i = s * 4
            val l = max(length[s], 0.01f)
            val dx = (walkways[i + 2] - walkways[i]) / l; val dz = (walkways[i + 3] - walkways[i + 1]) / l
            x[p] = walkways[i] + dx * along[p] - dz * offset[p]
            z[p] = walkways[i + 1] + dz * along[p] + dx * offset[p]
            yaw[p] = atan2(dx * heading[p], -dz * heading[p])
        }
    }
}
