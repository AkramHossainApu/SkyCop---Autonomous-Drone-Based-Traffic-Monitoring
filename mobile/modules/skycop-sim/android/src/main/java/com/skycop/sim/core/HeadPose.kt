package com.skycop.sim.core

import kotlin.math.*

/**
 * Turns the phone's fused orientation into a camera rotation.
 *
 * Matrices here have the columns right, up and back, the same as the camera basis, so the result is
 * exactly the rotation of the head: no smoothing, scaling or limits.
 */
class HeadPose {
    companion object {
        /** Sensor to photon on a phone is two to three frames; looking this far ahead cancels most of it. */
        const val LOOKAHEAD = 0.032f
        private const val STILL = 0.15f
        /** Below this turn rate (rad/s, about 3 degrees a second) the head counts as still. */
        private const val RESTING = 0.06f
    }

    private val delta = FloatArray(16)
    private val predicted = FloatArray(16)
    private val reference = FloatArray(16)
    private var yawOffset = 0f
    private var lastYaw = Float.NaN

    /**
     * @param world screen axes in a gravity-aligned world.
     * @param wx angular velocity around screen right, [wy] around screen up, [wz] around screen out.
     * @param seconds how far ahead to predict.
     */
    fun resolve(out: FloatArray, world: FloatArray, wx: Float, wy: Float, wz: Float, seconds: Float, recenter: Boolean) {
        val rate = sqrt(wx * wx + wy * wy + wz * wz)
        // Orientation without a compass drifts in heading even on a still head, which in a headset
        // looks like the world slowly turning. While the gyroscope says the head is still, any change
        // of heading is drift and is taken back out.
        val hx = -world[8]; val hz = -world[10]
        if (hypot(hx, hz) > 0.3f) {
            val yaw = atan2(hx, -hz)
            val change = wrapAngle(yaw - lastYaw)
            if (!lastYaw.isNaN() && rate < RESTING && abs(change) < 0.01f) yawOffset = wrapAngle(yawOffset + change)
            lastYaw = yaw
        }        // Fade prediction out near rest so sensor noise cannot make a still view shimmer.
        val angle = min(0.35f, rate * seconds * min(1f, rate / STILL))
        if (angle > 1e-5f) {
            axisAngle(delta, wx / rate, wy / rate, wz / rate, angle)
            Mat4.multiply(predicted, world, delta)
        } else {
            world.copyInto(predicted)
        }
        if (recenter) {
            // Only heading is recentered: pitch and roll stay true to gravity.
            val fx = -predicted[8]; val fy = -predicted[9]; val fz = -predicted[10]
            yawOffset = if (hypot(fx, fz) > 0.2f) {
                atan2(fx, -fz)
            } else {
                // Looking straight down the top of the screen points ahead; straight up, it points behind.
                val sign = if (fy < 0f) 1f else -1f
                atan2(predicted[4] * sign, -predicted[6] * sign)
            }
        }
        Mat4.pose(reference, 0f, 0f, 0f, -yawOffset, 0f, 0f)
        Mat4.multiply(out, reference, predicted)
    }

    private fun axisAngle(m: FloatArray, x: Float, y: Float, z: Float, angle: Float) {
        val c = cos(angle); val s = sin(angle); val t = 1 - c
        m[0] = t * x * x + c; m[1] = t * x * y + s * z; m[2] = t * x * z - s * y; m[3] = 0f
        m[4] = t * x * y - s * z; m[5] = t * y * y + c; m[6] = t * y * z + s * x; m[7] = 0f
        m[8] = t * x * z + s * y; m[9] = t * y * z - s * x; m[10] = t * z * z + c; m[11] = 0f
        m[12] = 0f; m[13] = 0f; m[14] = 0f; m[15] = 1f
    }
}
