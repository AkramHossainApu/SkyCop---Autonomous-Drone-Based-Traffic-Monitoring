package com.skycop.sim.render

import com.skycop.sim.core.Mat
import com.skycop.sim.core.MeshBuilder
import com.skycop.sim.core.PI_F
import com.skycop.sim.core.Tex
import kotlin.math.*

/** Original low-poly models, built in code. Local -Z is forward. */
internal object Models {
    val MOTOR_X = floatArrayOf(-0.48f, 0.48f, -0.5f, 0.5f)
    val MOTOR_Z = floatArrayOf(-0.4f, -0.4f, 0.42f, 0.42f)
    val MOTOR_Y = floatArrayOf(0.12f, 0.12f, 0.16f, 0.16f)

    private const val BODY = 0xE3E6E8
    private const val BLUE = 0x1F5FA8
    private const val TINTED = 0xFFFFFF
    private const val WINDOW = 0x1B2730
    private const val TIRE = 0x17191B

    fun drone() = MeshBuilder().apply {
        val carbon = Mat.of(Tex.CARBON)
        hull(
            floatArrayOf(-0.4f, -0.33f, -0.2f, 0.12f, 0.27f, 0.37f),
            floatArrayOf(0.06f, 0.125f, 0.175f, 0.175f, 0.135f, 0.07f),
            floatArrayOf(-0.03f, -0.075f, -0.1f, -0.1f, -0.08f, -0.03f),
            floatArrayOf(-0.005f, 0f, 0.01f, 0.02f, 0.015f, 0.01f),
            floatArrayOf(0.02f, 0.065f, 0.105f, 0.12f, 0.1f, 0.05f),
            0xC8C8C8, carbon, BODY, Mat.GLOSSY,
        )
        box(-0.177f, -0.005f, -0.15f, -0.17f, 0.03f, 0.14f, BLUE, Mat.GLOSSY)
        box(0.17f, -0.005f, -0.15f, 0.177f, 0.03f, 0.14f, BLUE, Mat.GLOSSY)
        box(-0.1f, 0.115f, 0.0f, 0.1f, 0.15f, 0.22f, 0xFFFFFF, carbon)
        ellipsoid(0f, 0.15f, 0.12f, 0.075f, 0.04f, 0.075f, 14, 6, BODY, Mat.GLOSSY)
        box(-0.1f, 0.105f, -0.13f, -0.01f, 0.15f, -0.06f, 0xFF2A2A, Mat.STROBE)
        box(0.01f, 0.105f, -0.13f, 0.1f, 0.15f, -0.06f, 0x2468FF, Mat.STROBE)
        for (k in 0..3) box(-0.05f, 0.152f, 0.19f + k * 0.012f, 0.05f, 0.156f, 0.196f + k * 0.012f, 0x101315)

        for (i in 0..3) {
            val x = MOTOR_X[i]; val mz = MOTOR_Z[i]; val y = MOTOR_Y[i]
            beam(sign(x) * 0.1f, if (mz < 0) -0.18f else 0.17f, x, mz, y - 0.09f, 0.048f, 0.026f, 0.045f, 0xFFFFFF, carbon)
            cylinder(x, y - 0.13f, mz, 0.06f, y - 0.07f, 0.062f, 14, 0x24282C, Mat.GLOSSY)
            cylinder(x, y - 0.07f, mz, 0.056f, y - 0.01f, 0.05f, 14, 0x99A3A9, Mat.GLOSSY)
            cylinder(x, y - 0.052f, mz, 0.0575f, y - 0.037f, 0.0575f, 14, 0xD9A23A, Mat.GLOSSY)
            cylinder(x, y - 0.01f, mz, 0.022f, y + 0.04f, 0.012f, 8, 0xC9D1D6, Mat.GLOSSY)
            // Navigation lights: red on the left, green on the right, white behind.
            val light = if (mz > 0) 0xFFFFFF else if (x < 0) 0xFF3030 else 0x30FF70
            centered(x, y - 0.145f, mz, 0.06f, 0.025f, 0.06f, light, Mat.EMISSIVE)
            cylinder(x, -0.29f, mz, 0.014f, y - 0.15f, 0.024f, 6, 0xFFFFFF, carbon)
            ellipsoid(x, -0.28f, mz, 0.026f, 0.02f, 0.032f, 6, 4, 0x15181A)
        }
        centered(0f, -0.12f, -0.3f, 0.14f, 0.05f, 0.09f, 0xFFFFFF, carbon)
        box(-0.095f, -0.22f, -0.36f, -0.075f, -0.12f, -0.31f, 0x394148, Mat.GLOSSY)
        box(0.075f, -0.22f, -0.36f, 0.095f, -0.12f, -0.31f, 0x394148, Mat.GLOSSY)
        centered(0f, -0.2f, -0.345f, 0.13f, 0.105f, 0.12f, 0x2B3136, Mat.GLOSSY)
        centered(0f, -0.2f, -0.42f, 0.085f, 0.075f, 0.04f, 0x10161B, Mat.GLOSSY)
        centered(0f, -0.2f, -0.444f, 0.05f, 0.045f, 0.012f, 0x2B7FA8, Mat.GLASS)
    }

    fun propeller() = MeshBuilder().apply {
        for (side in floatArrayOf(-1f, 1f)) {
            beam(side * 0.02f, 0f, side * 0.15f, side * 0.006f, 0.05f, 0.014f, 0.027f, 0.008f, 0x1A1D20, Mat.GLOSSY)
            beam(side * 0.15f, side * 0.006f, side * 0.25f, 0f, 0.05f, 0.027f, 0.014f, 0.008f, 0x1A1D20, Mat.GLOSSY)
            beam(side * 0.25f, 0f, side * 0.275f, -side * 0.006f, 0.05f, 0.014f, 0.008f, 0.009f, 0xF2B544, Mat.GLOSSY)
        }
    }

    fun rotorDisc() = MeshBuilder().apply {
        for (i in 0 until 20) {
            val a = i * 2 * PI_F / 20; val b = (i + 1) * 2 * PI_F / 20
            triangle(0f, 0.06f, 0f, cos(b) * 0.28f, 0.06f, sin(b) * 0.28f, cos(a) * 0.28f, 0.06f, sin(a) * 0.28f, 0xFFFFFF)
        }
    }

    /** Index order matches Traffic's type ids: car, CNG, cycle rickshaw, bus, motorbike, microbus. */
    fun vehicles(): Array<MeshBuilder> = arrayOf(sedan(), cng(), cycleRickshaw(), bus(), motorbike(), van())

    private fun MeshBuilder.wheels(x: Float, front: Float, back: Float, radius: Float) {
        for (sideX in floatArrayOf(-x, x)) for (z in floatArrayOf(front, back)) {
            axle(sideX - 0.12f, sideX + 0.12f, radius, z, radius, 10, TIRE, 0x24272A)
            val face = sideX + sign(sideX) * 0.125f
            axle(face - 0.01f, face + 0.01f, radius, z, radius * 0.55f, 8, 0xA9B0B4, 0xC3CACE, Mat.GLOSSY)
        }
    }

    private fun MeshBuilder.lights(halfWidth: Float, front: Float, back: Float, y: Float) {
        for (x in floatArrayOf(-halfWidth, halfWidth)) {
            centered(x, y, front, 0.34f, 0.14f, 0.04f, 0xFFF6CF, Mat.EMISSIVE)
            centered(x, y, back, 0.34f, 0.14f, 0.04f, 0xC9201C, Mat.EMISSIVE)
        }
    }

    /** Grille, bumpers, number plates and mirrors. */
    private fun MeshBuilder.trim(halfWidth: Float, front: Float, back: Float, mirror: Float, mirrorY: Float) {
        centered(0f, 0.56f, front, 0.62f, 0.14f, 0.03f, 0x14181B, Mat.GLOSSY)
        centered(0f, 0.4f, front, halfWidth * 1.5f, 0.1f, 0.07f, 0x30353A)
        centered(0f, 0.4f, back, halfWidth * 1.5f, 0.1f, 0.07f, 0x30353A)
        centered(0f, 0.43f, front - 0.04f, 0.4f, 0.13f, 0.02f, 0xF1F1EA)
        centered(0f, 0.43f, back + 0.04f, 0.4f, 0.13f, 0.02f, 0xF1F1EA)
        centered(-halfWidth - 0.06f, mirrorY, mirror, 0.16f, 0.1f, 0.09f, TINTED, Mat.TINT)
        centered(halfWidth + 0.06f, mirrorY, mirror, 0.16f, 0.1f, 0.09f, TINTED, Mat.TINT)
    }

    /** A cabin narrower at the roof, glazed all round. */
    private fun MeshBuilder.cabin(w0: Float, w1: Float, y0: Float, y1: Float, f0: Float, f1: Float, b0: Float, b1: Float, roof: Int) {
        quad(-w0, y0, f0, -w1, y1, f1, w1, y1, f1, w0, y0, f0, WINDOW, Mat.GLASS)
        quad(-w0, y0, b0, w0, y0, b0, w1, y1, b1, -w1, y1, b1, WINDOW, Mat.GLASS)
        quad(w0, y0, b0, w0, y0, f0, w1, y1, f1, w1, y1, b1, WINDOW, Mat.GLASS)
        quad(-w0, y0, f0, -w0, y0, b0, -w1, y1, b1, -w1, y1, f1, WINDOW, Mat.GLASS)
        quad(-w1, y1, b1, w1, y1, b1, w1, y1, f1, -w1, y1, f1, roof, Mat.TINT)
        val middle = (f1 + b1) / 2
        box(-w0 - 0.005f, y0, middle - 0.07f, -w1 + 0.01f, y1, middle + 0.07f, TINTED, Mat.TINT, bottom = false)
        box(w1 - 0.01f, y0, middle - 0.07f, w0 + 0.005f, y1, middle + 0.07f, TINTED, Mat.TINT, bottom = false)
    }

    private fun MeshBuilder.saloon() {
        hull(
            floatArrayOf(-2.12f, -1.95f, -1.0f, 1.35f, 1.95f, 2.12f),
            floatArrayOf(0.78f, 0.9f, 0.93f, 0.93f, 0.9f, 0.8f),
            floatArrayOf(0.36f, 0.28f, 0.27f, 0.27f, 0.29f, 0.38f),
            floatArrayOf(0.58f, 0.66f, 0.74f, 0.76f, 0.72f, 0.62f),
            floatArrayOf(0.72f, 0.82f, 0.94f, 0.95f, 0.9f, 0.78f),
            TINTED, Mat.TINT,
        )
        trim(0.93f, -2.13f, 2.13f, -0.95f, 0.98f)
        cabin(0.86f, 0.72f, 0.92f, 1.5f, -1.05f, -0.45f, 1.4f, 0.95f, TINTED)
        wheels(0.82f, -1.35f, 1.35f, 0.34f)
        lights(0.55f, -2.13f, 2.13f, 0.63f)
    }

    private fun sedan() = MeshBuilder().apply { saloon() }

    private fun van() = MeshBuilder().apply {
        hull(
            floatArrayOf(-2.47f, -2.3f, 2.47f),
            floatArrayOf(0.88f, 1f, 1f),
            floatArrayOf(0.4f, 0.33f, 0.35f),
            floatArrayOf(0.8f, 0.9f, 0.9f),
            floatArrayOf(1f, 1.15f, 1.15f),
            TINTED, Mat.TINT,
        )
        trim(1f, -2.48f, 2.48f, -1.8f, 1.2f)
        box(-0.98f, 1.15f, -0.5f, 0.98f, 2.2f, 2.45f, TINTED, Mat.TINT)
        cabin(0.95f, 0.88f, 1.15f, 2.0f, -1.9f, -1.3f, -0.48f, -0.5f, TINTED)
        wheels(0.9f, -1.6f, 1.6f, 0.38f)
        lights(0.62f, -2.48f, 2.48f, 0.82f)
    }

    /** A Dhaka local bus: painted body with a pale band, open door, roof rack and sliding windows. */
    private fun bus() = MeshBuilder().apply {
        box(-1.25f, 0.45f, -5.2f, 1.25f, 3.1f, 5.2f, TINTED, Mat.TINT)
        box(-1.27f, 1.32f, -5.21f, 1.27f, 1.52f, 5.21f, 0xF2EEE2)
        for (x in floatArrayOf(-1.26f, 1.26f)) {
            for (k in 0..6) centered(x, 2.2f, -4.1f + k * 1.3f, 0.03f, 0.95f, 1.1f, WINDOW, Mat.GLASS)
        }
        centered(1.265f, 1.55f, -3.9f, 0.03f, 2.1f, 0.9f, 0x15191C)
        centered(0f, 2.15f, -5.215f, 2.25f, 1.25f, 0.03f, WINDOW, Mat.GLASS)
        centered(0f, 2.95f, -5.22f, 1.5f, 0.24f, 0.03f, 0x101214)
        centered(0f, 2.95f, -5.235f, 1.3f, 0.12f, 0.02f, 0xF0C030, Mat.EMISSIVE)
        centered(0f, 2.3f, 5.215f, 1.9f, 0.8f, 0.03f, WINDOW, Mat.GLASS)
        for (k in -2..2) centered(0f, 3.17f, k * 1.6f, 2.1f, 0.06f, 0.06f, 0x3A3D40)
        wheels(1.15f, -3.4f, 3.5f, 0.5f)
        lights(0.9f, -5.22f, 5.22f, 0.85f)
    }

    /** A rider or a rickshaw puller: shirt, trousers, head, and a helmet for the bikers. */
    private fun MeshBuilder.person(x: Float, seat: Float, z: Float, shirt: Int, helmet: Boolean) {
        centered(x, seat + 0.32f, z, 0.36f, 0.6f, 0.24f, shirt)
        centered(x - 0.1f, seat - 0.05f, z - 0.2f, 0.13f, 0.18f, 0.42f, 0x2B2F3A)
        centered(x + 0.1f, seat - 0.05f, z - 0.2f, 0.13f, 0.18f, 0.42f, 0x2B2F3A)
        ellipsoid(x, seat + 0.78f, z, 0.11f, 0.13f, 0.12f, 6, 4, if (helmet) 0x1C1E22 else 0x8C5A3C)
        centered(x - 0.22f, seat + 0.42f, z - 0.25f, 0.08f, 0.08f, 0.5f, shirt)
        centered(x + 0.22f, seat + 0.42f, z - 0.25f, 0.08f, 0.08f, 0.5f, shirt)
    }

    /** A CNG auto-rickshaw: green three-wheeler with a canopy and caged doors. */
    private fun cng() = MeshBuilder().apply {
        box(-0.64f, 0.28f, -0.55f, 0.64f, 0.82f, 1.25f, TINTED, Mat.TINT)
        box(-0.42f, 0.28f, -1.35f, 0.42f, 1.05f, -0.55f, TINTED, Mat.TINT)
        quad(-0.4f, 1.05f, -1.3f, 0.4f, 1.05f, -1.3f, 0.6f, 1.62f, -0.95f, -0.6f, 1.62f, -0.95f, WINDOW, Mat.GLASS)
        box(-0.68f, 1.62f, -1.0f, 0.68f, 1.74f, 1.3f, TINTED, Mat.TINT)
        for (x in floatArrayOf(-0.64f, 0.64f)) for (z in floatArrayOf(-0.95f, -0.2f, 0.55f, 1.25f)) {
            centered(x, 1.22f, z, 0.05f, 0.8f, 0.05f, 0x2C3034)
        }
        // The steel grille doors either side of the passengers.
        for (x in floatArrayOf(-0.645f, 0.645f)) {
            for (k in 0..6) centered(x, 1.22f, -0.15f + k * 0.2f, 0.02f, 0.78f, 0.025f, 0x3E4246)
            for (k in 0..2) centered(x, 0.95f + k * 0.27f, 0.45f, 0.02f, 0.025f, 1.3f, 0x3E4246)
        }
        box(-0.62f, 0.82f, 0.85f, 0.62f, 1.62f, 1.27f, 0x1E2124)
        person(0f, 0.85f, -0.35f, 0xE8E2D2, false)
        axle(-0.07f, 0.07f, 0.22f, -1.05f, 0.22f, 8, TIRE, 0x8E969B)
        axle(-0.7f, -0.56f, 0.22f, 0.85f, 0.22f, 8, TIRE, 0x8E969B)
        axle(0.56f, 0.7f, 0.22f, 0.85f, 0.22f, 8, TIRE, 0x8E969B)
        centered(0f, 0.85f, -1.365f, 0.2f, 0.14f, 0.03f, 0xFFF6CF, Mat.EMISSIVE)
        centered(-0.45f, 0.6f, 1.26f, 0.12f, 0.1f, 0.03f, 0xC9201C, Mat.EMISSIVE)
        centered(0.45f, 0.6f, 1.26f, 0.12f, 0.1f, 0.03f, 0xC9201C, Mat.EMISSIVE)
    }

    /** A cycle rickshaw: painted hood, cushioned seat, decorated back panel and its puller. */
    private fun cycleRickshaw() = MeshBuilder().apply {
        centered(0f, 0.5f, -0.25f, 0.06f, 0.06f, 1.4f, 0x2C3034)
        centered(0f, 0.85f, -0.75f, 0.06f, 0.5f, 0.06f, 0x2C3034)
        centered(0f, 1.05f, -0.82f, 0.5f, 0.04f, 0.04f, 0x2C3034)
        box(-0.5f, 0.55f, 0.25f, 0.5f, 0.68f, 0.95f, 0x5A3A22)
        box(-0.5f, 0.68f, 0.3f, 0.5f, 0.86f, 0.85f, 0xC0303A)
        box(-0.5f, 0.86f, 0.8f, 0.5f, 1.35f, 0.92f, TINTED, Mat.TINT)
        centered(0f, 0.72f, 0.96f, 0.8f, 0.3f, 0.02f, 0xF0C030)
        // The folding hood, in bands of colour.
        for (k in 0..3) {
            val z0 = 0.95f - k * 0.18f; val z1 = z0 - 0.18f
            val y0 = 1.35f + k * 0.12f; val y1 = y0 + 0.12f
            quad(-0.52f, y0, z0, 0.52f, y0, z0, 0.52f, y1, z1, -0.52f, y1, z1, if (k % 2 == 0) TINTED else 0xF2EEE2, if (k % 2 == 0) Mat.TINT else Mat.PLAIN)
            quad(0.52f, y0, z0, -0.52f, y0, z0, -0.52f, y1, z1, 0.52f, y1, z1, 0x1E2124)
        }
        person(0f, 0.95f, -0.45f, 0xD9D2C2, false)
        axle(-0.05f, 0.05f, 0.33f, -0.95f, 0.33f, 10, TIRE, 0xA0A6AA)
        axle(-0.58f, -0.5f, 0.33f, 0.6f, 0.33f, 10, TIRE, 0xA0A6AA)
        axle(0.5f, 0.58f, 0.33f, 0.6f, 0.33f, 10, TIRE, 0xA0A6AA)
    }

    /** A motorbike with its rider. */
    private fun motorbike() = MeshBuilder().apply {
        axle(-0.06f, 0.06f, 0.31f, -0.7f, 0.31f, 10, TIRE, 0x9AA0A4)
        axle(-0.07f, 0.07f, 0.31f, 0.68f, 0.31f, 10, TIRE, 0x9AA0A4)
        box(-0.16f, 0.4f, -0.45f, 0.16f, 0.7f, 0.5f, 0x2C3034)
        box(-0.18f, 0.7f, -0.45f, 0.18f, 0.92f, -0.05f, TINTED, Mat.TINT)
        box(-0.15f, 0.72f, -0.05f, 0.15f, 0.84f, 0.6f, 0x15181A)
        centered(0f, 0.95f, -0.62f, 0.62f, 0.04f, 0.04f, 0x2C3034)
        centered(0f, 0.8f, -0.82f, 0.16f, 0.14f, 0.05f, 0xFFF6CF, Mat.EMISSIVE)
        centered(0f, 0.72f, 0.72f, 0.12f, 0.08f, 0.04f, 0xC9201C, Mat.EMISSIVE)
        person(0f, 0.84f, 0.15f, 0x3A5C8C, true)
    }

}
