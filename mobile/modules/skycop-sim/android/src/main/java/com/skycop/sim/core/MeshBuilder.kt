package com.skycop.sim.core

import kotlin.math.*

/** Material ids travel in the alpha byte of each vertex color. */
object Mat {
    const val PLAIN = 0
    const val GLOSSY = 1
    const val GLASS = 2
    const val GROUND = 3
    const val EMISSIVE = 4
    /** Glossy paint that takes the instance color. */
    const val TINT = 5
    const val WATER = 6
    const val STROBE = 7
    /** Twelve ids: group * 6 + axis * 3 + (0 green, 1 yellow, 2 red). */
    const val SIGNAL = 10
    /** From here on, an id is [TEXTURED] + the texture layer. */
    const val TEXTURED = 32

    fun of(tex: Tex) = TEXTURED + tex.ordinal
}

/** Packs non-indexed triangles into five ints per vertex: position, normal, color + material. */
class MeshBuilder {
    var data = IntArray(4096)
        private set
    var vertices = 0
        private set

    fun triangle(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float, color: Int, material: Int = Mat.PLAIN,
    ) {
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (length < 1e-7f) return
        nx /= length; ny /= length; nz /= length
        val face = face(nx, ny, nz)
        val normal = packNormal(nx, ny, nz, face)
        val packed = packColor(color, material)
        vertex(ax, ay, az, normal, packed)
        vertex(bx, by, bz, normal, packed)
        vertex(cx, cy, cz, normal, packed)
    }

    /**
     * The face code travels in the spare byte of the normal and picks the texture projection, so
     * every corner of a triangle projects the same way even when its normals are smoothed.
     */
    private fun face(nx: Float, ny: Float, nz: Float): Int {
        val x = abs(nx); val y = abs(ny); val z = abs(nz)
        return when {
            y >= x && y >= z -> if (ny >= 0) 4 else 5
            x >= z -> if (nx >= 0) 0 else 1
            else -> if (nz >= 0) 2 else 3
        }
    }

    private fun packNormal(nx: Float, ny: Float, nz: Float, face: Int) =
        packByte(nx) or (packByte(ny) shl 8) or (packByte(nz) shl 16) or (face shl 24)

    private fun packColor(color: Int, material: Int) =
        ((color shr 16) and 255) or (color and 0xff00) or ((color and 255) shl 16) or (material shl 24)

    private fun packByte(v: Float) = (v * 127f).roundToInt() and 255

    private fun vertex(x: Float, y: Float, z: Float, normal: Int, color: Int) {
        val at = vertices * 5
        if (at + 5 > data.size) data = data.copyOf(data.size * 2)
        data[at] = x.toRawBits(); data[at + 1] = y.toRawBits(); data[at + 2] = z.toRawBits()
        data[at + 3] = normal; data[at + 4] = color
        vertices++
    }

    /** Four corners, counter-clockwise when seen from the front. */
    fun quad(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float,
        color: Int, material: Int = Mat.PLAIN,
    ) {
        triangle(ax, ay, az, bx, by, bz, cx, cy, cz, color, material)
        triangle(ax, ay, az, cx, cy, cz, dx, dy, dz, color, material)
    }

    fun box(
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        color: Int, material: Int = Mat.PLAIN, top: Int = color, topMaterial: Int = material, bottom: Boolean = true,
    ) {
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, color, material)
        quad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, color, material)
        quad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, color, material)
        quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, color, material)
        quad(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, top, topMaterial)
        if (bottom) quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, color, material)
    }

    fun centered(x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, color: Int, material: Int = Mat.PLAIN) =
        box(x - w / 2, y - h / 2, z - d / 2, x + w / 2, y + h / 2, z + d / 2, color, material)

    /** A vertical prism or cone frustum, smooth-shaded once it has five sides or more. */
    fun cylinder(
        x: Float, y0: Float, z: Float, r0: Float, y1: Float, r1: Float, sides: Int,
        color: Int, material: Int = Mat.PLAIN, cap: Boolean = true, capMaterial: Int = material,
    ) {
        val rise = y1 - y0
        val length = hypot(rise, r0 - r1)
        val nh = rise / length; val ny = (r0 - r1) / length
        val packed = packColor(color, material)
        for (i in 0 until sides) {
            val a = i * 2 * PI_F / sides
            val b = (i + 1) * 2 * PI_F / sides
            val ca = cos(a); val sa = sin(a); val cb = cos(b); val sb = sin(b)
            if (sides >= 5) {
                val middle = (a + b) / 2
                val face = face(cos(middle) * nh, ny, sin(middle) * nh)
                val na = packNormal(ca * nh, ny, sa * nh, face); val nb = packNormal(cb * nh, ny, sb * nh, face)
                vertex(x + ca * r0, y0, z + sa * r0, na, packed)
                vertex(x + ca * r1, y1, z + sa * r1, na, packed)
                vertex(x + cb * r1, y1, z + sb * r1, nb, packed)
                vertex(x + ca * r0, y0, z + sa * r0, na, packed)
                vertex(x + cb * r1, y1, z + sb * r1, nb, packed)
                vertex(x + cb * r0, y0, z + sb * r0, nb, packed)
            } else {
                quad(
                    x + ca * r0, y0, z + sa * r0, x + ca * r1, y1, z + sa * r1,
                    x + cb * r1, y1, z + sb * r1, x + cb * r0, y0, z + sb * r0,
                    color, material,
                )
            }
            if (cap) triangle(x, y1, z, x + cb * r1, y1, z + sb * r1, x + ca * r1, y1, z + sa * r1, color, capMaterial)
        }
    }

    /** A wheel or lens: a capped cylinder lying along the X axis. */
    fun axle(x0: Float, x1: Float, y: Float, z: Float, radius: Float, sides: Int, color: Int, hub: Int, material: Int = Mat.PLAIN) {
        val packed = packColor(color, material)
        for (i in 0 until sides) {
            val a = i * 2 * PI_F / sides
            val b = (i + 1) * 2 * PI_F / sides
            val ca = cos(a); val sa = sin(a); val cb = cos(b); val sb = sin(b)
            val face = face(0f, cos((a + b) / 2), sin((a + b) / 2))
            val na = packNormal(0f, ca, sa, face); val nb = packNormal(0f, cb, sb, face)
            vertex(x0, y + ca * radius, z + sa * radius, na, packed)
            vertex(x1, y + cb * radius, z + sb * radius, nb, packed)
            vertex(x1, y + ca * radius, z + sa * radius, na, packed)
            vertex(x0, y + ca * radius, z + sa * radius, na, packed)
            vertex(x0, y + cb * radius, z + sb * radius, nb, packed)
            vertex(x1, y + cb * radius, z + sb * radius, nb, packed)
            triangle(x1, y, z, x1, y + ca * radius, z + sa * radius, x1, y + cb * radius, z + sb * radius, hub)
            triangle(x0, y, z, x0, y + cb * radius, z + sb * radius, x0, y + ca * radius, z + sa * radius, hub)
        }
    }

    /** A smooth blob, slightly irregular when [bumps] is above zero. */
    fun ellipsoid(
        x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float, sides: Int, rings: Int,
        color: Int, material: Int = Mat.PLAIN, bumps: Float = 0f,
    ) {
        val packed = packColor(color, material)
        fun point(i: Int, j: Int, face: Int) {
            val around = (i % sides) * 2 * PI_F / sides
            val down = j * PI_F / rings
            val nx = cos(around) * sin(down); val ny = cos(down); val nz = sin(around) * sin(down)
            val swell = 1f + bumps * sin((i % sides) * 2.4f + x) * sin(j * 1.9f + z) * sin(down)
            vertex(x + nx * rx * swell, y + ny * ry * swell, z + nz * rz * swell, packNormal(nx, ny, nz, face), packed)
        }
        for (j in 0 until rings) for (i in 0 until sides) {
            val around = (i + 0.5f) * 2 * PI_F / sides; val down = (j + 0.5f) * PI_F / rings
            val face = face(cos(around) * sin(down), cos(down), sin(around) * sin(down))
            if (j > 0) {
                point(i, j, face); point(i + 1, j, face); point(i + 1, j + 1, face)
            }
            if (j < rings - 1) {
                point(i, j, face); point(i + 1, j + 1, face); point(i, j + 1, face)
            }
        }
    }

    /**
     * Lofts a chamfered body through cross-sections ordered from front (-Z) to back.
     * [upper] is null when the part above the shoulder has the same material.
     */
    fun hull(
        z: FloatArray, halfWidth: FloatArray, bottom: FloatArray, shoulder: FloatArray, top: FloatArray,
        color: Int, material: Int, upperColor: Int = color, upper: Int = material,
    ) {
        fun px(s: Int, p: Int) = halfWidth[s] * when (p % 6) { 0 -> -0.9f; 1 -> 0.9f; 2 -> 1f; 3 -> 0.82f; 4 -> -0.82f; else -> -1f }
        fun py(s: Int, p: Int) = when (p % 6) { 0, 1 -> bottom[s]; 2, 5 -> shoulder[s]; else -> top[s] }
        val last = z.size - 1
        for (s in 0 until last) for (p in 0..5) {
            val high = p in 2..4
            quad(
                px(s, p), py(s, p), z[s], px(s, p + 1), py(s, p + 1), z[s],
                px(s + 1, p + 1), py(s + 1, p + 1), z[s + 1], px(s + 1, p), py(s + 1, p), z[s + 1],
                if (high) upperColor else color, if (high) upper else material,
            )
        }
        for (p in 1..4) {
            triangle(px(0, 0), py(0, 0), z[0], px(0, p + 1), py(0, p + 1), z[0], px(0, p), py(0, p), z[0], color, material)
            triangle(
                px(last, 0), py(last, 0), z[last], px(last, p), py(last, p), z[last],
                px(last, p + 1), py(last, p + 1), z[last], color, material,
            )
        }
    }

    /** A tapered beam between two points in the horizontal plane, used for drone arms. */
    fun beam(x0: Float, z0: Float, x1: Float, z1: Float, y: Float, w0: Float, w1: Float, h: Float, color: Int, material: Int = Mat.PLAIN) {
        val length = hypot(x1 - x0, z1 - z0)
        val sx = -(z1 - z0) / length; val sz = (x1 - x0) / length
        val ax = x0 + sx * w0; val az = z0 + sz * w0; val bx = x0 - sx * w0; val bz = z0 - sz * w0
        val cx = x1 - sx * w1; val cz = z1 - sz * w1; val dx = x1 + sx * w1; val dz = z1 + sz * w1
        val t = y + h / 2; val b = y - h / 2
        quad(ax, t, az, dx, t, dz, cx, t, cz, bx, t, bz, color, material)
        quad(bx, b, bz, cx, b, cz, dx, b, dz, ax, b, az, color, material)
        quad(ax, b, az, dx, b, dz, dx, t, dz, ax, t, az, color, material)
        quad(cx, b, cz, bx, b, bz, bx, t, bz, cx, t, cz, color, material)
    }
}
