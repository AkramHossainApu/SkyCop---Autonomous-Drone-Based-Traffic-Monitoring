package com.skycop.sim.core

import kotlin.math.*

const val PI_F = PI.toFloat()
const val DEG = PI_F / 180f
const val GRAVITY = 9.81f

fun approach(value: Float, target: Float, step: Float): Float =
    if (value < target) min(value + step, target) else max(value - step, target)

/** Frame-rate independent exponential smoothing factor. */
fun blend(rate: Float, dt: Float): Float = 1f - exp(-rate * dt)

fun wrapAngle(angle: Float): Float {
    var a = angle % (2 * PI_F)
    if (a > PI_F) a -= 2 * PI_F
    if (a < -PI_F) a += 2 * PI_F
    return a
}

/** Column-major 4x4 helpers. None of them allocate, so they are safe to call every frame. */
object Mat4 {
    fun identity(m: FloatArray) {
        m.fill(0f)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
    }

    /** [out] must not be [a] or [b]. */
    fun multiply(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (c in 0..3) for (r in 0..3) {
            out[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] +
                a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3]
        }
    }

    /** Writes a pose whose local -Z is forward. Positive pitch is nose down, positive roll is right side down. */
    fun pose(out: FloatArray, x: Float, y: Float, z: Float, yaw: Float, pitch: Float, roll: Float) {
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        val cr = cos(roll); val sr = sin(roll)
        out[0] = cy * cr - sy * sp * sr; out[1] = -cp * sr; out[2] = sy * cr + cy * sp * sr; out[3] = 0f
        out[4] = cy * sr + sy * sp * cr; out[5] = cp * cr; out[6] = sy * sr - cy * sp * cr; out[7] = 0f
        out[8] = -sy * cp; out[9] = sp; out[10] = cy * cp; out[11] = 0f
        out[12] = x; out[13] = y; out[14] = z; out[15] = 1f
    }

    fun perspective(out: FloatArray, tanHalfY: Float, aspect: Float, near: Float, far: Float) {
        out.fill(0f)
        out[0] = 1f / (tanHalfY * aspect)
        out[5] = 1f / tanHalfY
        out[10] = (far + near) / (near - far)
        out[11] = -1f
        out[14] = 2f * far * near / (near - far)
    }

    fun ortho(out: FloatArray, half: Float, near: Float, far: Float) {
        out.fill(0f)
        out[0] = 1f / half; out[5] = 1f / half
        out[10] = -2f / (far - near); out[14] = -(far + near) / (far - near)
        out[15] = 1f
    }

    /** View matrix from a camera rotation (columns are right, up, back) and an eye point. */
    fun view(out: FloatArray, basis: FloatArray, x: Float, y: Float, z: Float) {
        for (i in 0..2) {
            out[i] = basis[i * 4]
            out[4 + i] = basis[i * 4 + 1]
            out[8 + i] = basis[i * 4 + 2]
            out[12 + i] = -(basis[i * 4] * x + basis[i * 4 + 1] * y + basis[i * 4 + 2] * z)
        }
        out[3] = 0f; out[7] = 0f; out[11] = 0f; out[15] = 1f
    }
}
