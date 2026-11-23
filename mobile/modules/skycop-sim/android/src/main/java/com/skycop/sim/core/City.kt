package com.skycop.sim.core

import kotlin.math.*
import kotlin.random.Random

/**
 * A fixed 12 x 12 block city. It is built once, so nothing has to be generated or uploaded in
 * flight, and colliders are bucketed by block so a query only looks at the neighboring blocks.
 */
class City(seed: Int = 1971) {
    companion object {
        const val BLOCKS = 12
        const val PITCH = 72f
        const val HALF = BLOCKS / 2 * PITCH
        const val ROAD_HALF = 8f
        const val CURB = 0.15f
        const val BOUNDS = HALF + 40f

        const val HOME_X = 36f
        const val HOME_Y = 0.35f
        const val HOME_Z = 30f

        fun road(index: Int) = (index - BLOCKS / 2) * PITCH
    }

    /** [mesh] is what reads from far away; [detail] is small street furniture drawn only nearby. */
    class Chunk(val mesh: MeshBuilder, val x: Float, val z: Float) {
        val detail = MeshBuilder()
        var top = 6f
        var boxes = FloatArray(6 * 16)
        /** False for trees and poles, which the chase camera looks through. */
        var blocksView = BooleanArray(16)
        var count = 0

        fun solid(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, opaque: Boolean = true) {
            if (count * 6 == boxes.size) {
                boxes = boxes.copyOf(boxes.size * 2)
                blocksView = blocksView.copyOf(count * 2)
            }
            blocksView[count] = opaque
            val i = count * 6
            boxes[i] = x0; boxes[i + 1] = y0; boxes[i + 2] = z0
            boxes[i + 3] = x1; boxes[i + 4] = y1; boxes[i + 5] = z1
            count++
            top = max(top, y1 + 3f)
        }
    }

    val chunks: Array<Chunk>
    val terrain = MeshBuilder()
    var tallest = 0f
        private set

    private val random = Random(seed)
    // Tints multiply the texture, except on glass.
    private val plasterTints = intArrayOf(0xFFFFFF, 0xF6D9B8, 0xD8E6DA, 0xF2C9B4, 0xCCDBEA, 0xF3DF9F, 0xE6CFC6)
    private val brickTints = intArrayOf(0xFFFFFF, 0xEBD9CF, 0xD2C6C0, 0xFFE8D0)
    private val towerTints = intArrayOf(0xFFFFFF, 0xDCE6EC, 0xE8E0D4, 0xC9D3CF, 0xF4EEE6)
    private val leafColors = intArrayOf(0x7FA35C, 0x93B568, 0xA9B86A, 0x6E9254)

    init {
        chunks = Array(BLOCKS * BLOCKS) {
            Chunk(MeshBuilder(), road(it % BLOCKS) + PITCH / 2, road(it / BLOCKS) + PITCH / 2)
        }
        for (j in 0 until BLOCKS) for (i in 0 until BLOCKS) buildBlock(chunks[j * BLOCKS + i], i - BLOCKS / 2, j - BLOCKS / 2)
        for (j in 0..BLOCKS) for (i in 0..BLOCKS) buildJunction(i, j)
        buildTerrain()
        for (chunk in chunks) tallest = max(tallest, chunk.top - 3f)
    }

    private fun between(low: Float, high: Float) = low + random.nextFloat() * (high - low)

    private fun buildBlock(c: Chunk, i: Int, j: Int) {
        val x0 = c.x - PITCH / 2 + ROAD_HALF; val x1 = c.x + PITCH / 2 - ROAD_HALF
        val z0 = c.z - PITCH / 2 + ROAD_HALF; val z1 = c.z + PITCH / 2 - ROAD_HALF
        c.mesh.box(x0, 0f, z0, x1, CURB, z1, 0xD8D8D4, Mat.of(Tex.CONCRETE), 0xF2F0EA, Mat.of(Tex.PAVING), false)
        c.solid(x0, 0f, z0, x1, CURB, z1)

        val ring = hypot(i + 0.5f, j + 0.5f)
        when {
            i == 0 && j == 0 -> station(c, x0, z0, x1, z1)
            (i == 0 && j == -1) || (i == -4 && j == 2) || random.nextFloat() < 0.09f -> park(c, x0, z0, x1, z1)
            random.nextFloat() < 0.05f -> carPark(c, x0, z0, x1, z1)
            ring < 3.3f -> downtown(c, x0, z0, ring)
            ring < 4.8f -> midtown(c, x0, z0)
            else -> suburb(c, x0, z0)
        }

        var along = x0 + 7f
        while (along < x1 - 4f) {
            if (i != 0 || j != 0) {
                tree(c, along, z0 + 1.6f, 0.75f)
                tree(c, along, z1 - 1.6f, 0.75f)
            }
            along += 14f
        }
        lamp(c, x0 + 0.5f, c.z, 1f)
        lamp(c, x1 - 0.5f, c.z, -1f)
    }

    private fun building(
        c: Chunk, x0: Float, z0: Float, x1: Float, z1: Float, base: Float, top: Float,
        color: Int, style: Tex, shops: Boolean = false,
    ) {
        val m = c.mesh
        var wallFrom = base
        if (shops) {
            // A storefront is exactly one floor, so its texture never has to switch within a triangle.
            wallFrom = base + 3.3f
            m.box(x0, base, z0, x1, wallFrom, z1, 0xFFFFFF, Mat.of(Tex.SHOPS), bottom = false)
            val ledge = 0.3f
            m.box(x0 - ledge, wallFrom - 0.12f, z0 - ledge, x1 + ledge, wallFrom + 0.1f, z0, 0xDDDAD2, Mat.of(Tex.CONCRETE))
            m.box(x0 - ledge, wallFrom - 0.12f, z1, x1 + ledge, wallFrom + 0.1f, z1 + ledge, 0xDDDAD2, Mat.of(Tex.CONCRETE))
            m.box(x0 - ledge, wallFrom - 0.12f, z0, x0, wallFrom + 0.1f, z1, 0xDDDAD2, Mat.of(Tex.CONCRETE))
            m.box(x1, wallFrom - 0.12f, z0, x1 + ledge, wallFrom + 0.1f, z1, 0xDDDAD2, Mat.of(Tex.CONCRETE))
        }
        m.box(x0, wallFrom, z0, x1, top, z1, color, Mat.of(style), 0xE8E8E4, Mat.of(Tex.ROOF), false)
        c.solid(x0, base, z0, x1, top + 0.45f, z1)
        val t = 0.35f
        val lip = Mat.of(Tex.CONCRETE)
        m.box(x0 - 0.12f, top - 0.05f, z0 - 0.12f, x1 + 0.12f, top + 0.45f, z0 + t, 0xE4E1D9, lip)
        m.box(x0 - 0.12f, top - 0.05f, z1 - t, x1 + 0.12f, top + 0.45f, z1 + 0.12f, 0xE4E1D9, lip)
        m.box(x0 - 0.12f, top - 0.05f, z0 + t, x0 + t, top + 0.45f, z1 - t, 0xE4E1D9, lip)
        m.box(x1 - t, top - 0.05f, z0 + t, x1 + 0.12f, top + 0.45f, z1 - t, 0xE4E1D9, lip)
    }

    private fun rooftop(c: Chunk, x: Float, z: Float, top: Float) {
        val m = c.mesh
        m.box(x - 2.2f, top, z - 1.5f, x + 1f, top + 2f, z + 1.5f, 0xE0E0DC, Mat.of(Tex.CONCRETE), bottom = false)
        c.solid(x - 2.2f, top, z - 1.5f, x + 1f, top + 2f, z + 1.5f)
        if (random.nextBoolean()) {
            m.cylinder(x + 3f, top + 0.3f, z, 1.1f, top + 2.6f, 1.1f, 10, 0xE5E0D5, Mat.of(Tex.METAL))
            c.solid(x + 1.9f, top, z - 1.1f, x + 4.1f, top + 2.6f, z + 1.1f)
        }
        val d = c.detail
        for (k in 0 until random.nextInt(2, 4)) {
            val ux = x - 2f + k * 2.1f; val uz = z + 3.2f
            d.box(ux - 0.7f, top, uz - 0.5f, ux + 0.7f, top + 0.8f, uz + 0.5f, 0xEEF0EE, Mat.of(Tex.METAL), 0x4A5054, Mat.PLAIN, false)
            d.cylinder(ux, top + 0.8f, uz, 0.36f, top + 0.88f, 0.36f, 8, 0x2C3033)
        }
        if (random.nextBoolean()) {
            d.cylinder(x - 0.6f, top + 2f, z, 0.07f, top + 5.5f, 0.02f, 3, 0xB7BDC0)
            d.centered(x - 0.6f, top + 5.5f, z, 0.14f, 0.14f, 0.14f, 0xFF4038, Mat.EMISSIVE)
        }
    }

    // Footprints sit on a 3 m grid and floors are 3.3 m, the units the facade textures are painted in.
    private fun downtown(c: Chunk, x0: Float, z0: Float, ring: Float) {
        for (a in 0..1) for (b in 0..1) {
            val left = x0 + 4f + a * 27f; val back = z0 + 4f + b * 27f
            val width = 15f + random.nextInt(3) * 3f; val depth = 15f + random.nextInt(3) * 3f
            val floors = max(5, (between(8f, 19f) - ring * 1.3f).toInt())
            val style = when (random.nextInt(7)) {
                0, 1, 2 -> Tex.CURTAIN_WALL
                3, 4 -> Tex.OFFICE
                else -> Tex.STONE_TOWER
            }
            val color = towerTints[random.nextInt(towerTints.size)]
            val top = CURB + floors * 3.3f
            val shops = random.nextInt(4) != 0
            if (floors > 11) {
                val middle = CURB + (floors - 4) * 3.3f
                building(c, left, back, left + width, back + depth, CURB, middle, color, style, shops)
                building(c, left + 3f, back + 3f, left + width - 3f, back + depth - 3f, middle, top, color, style)
            } else {
                building(c, left, back, left + width, back + depth, CURB, top, color, style, shops)
            }
            rooftop(c, left + width / 2, back + depth / 2, top)
        }
    }

    private fun midtown(c: Chunk, x0: Float, z0: Float) {
        for (a in 0..2) for (b in 0..1) {
            val left = x0 + 4f + a * 18f; val back = z0 + 4f + b * 30f
            val width = 12f + random.nextInt(2) * 3f; val depth = 12f + random.nextInt(3) * 3f
            val top = CURB + random.nextInt(3, 8) * 3.3f
            val style = when (random.nextInt(5)) {
                0, 1 -> Tex.BRICK_FLATS
                2, 3 -> Tex.PLASTER_FLATS
                else -> Tex.OFFICE
            }
            val tints = if (style == Tex.BRICK_FLATS) brickTints else if (style == Tex.OFFICE) towerTints else plasterTints
            building(c, left, back, left + width, back + depth, CURB, top, tints[random.nextInt(tints.size)], style, random.nextBoolean())
            rooftop(c, left + width / 2, back + depth / 2, top)
        }
    }

    private fun suburb(c: Chunk, x0: Float, z0: Float) {
        for (a in 0..2) for (b in 0..1) {
            val left = x0 + 4f + a * 18f; val back = z0 + 7f + b * 27f
            val right = left + 12f; val front = back + 12f
            val top = CURB + random.nextInt(1, 4) * 3.3f
            val ridge = top + 3.2f; val middle = (back + front) / 2
            val brick = random.nextInt(3) == 0
            val wall = if (brick) brickTints[random.nextInt(brickTints.size)] else plasterTints[random.nextInt(plasterTints.size)]
            val roof = when (random.nextInt(3)) { 0 -> 0xC7664A; 1 -> 0x7C8992; else -> 0x9C6B52 }
            val gable = Mat.of(if (brick) Tex.BRICK else Tex.CONCRETE)
            val tiles = Mat.of(Tex.ROOF_TILES)
            val m = c.mesh
            m.box(left, CURB, back, right, top, front, wall, Mat.of(if (brick) Tex.BRICK_FLATS else Tex.PLASTER_FLATS), bottom = false)
            val e = 0.5f; val eave = top - 0.2f
            m.quad(left - e, eave, front + e, right + e, eave, front + e, right + e, ridge, middle, left - e, ridge, middle, roof, tiles)
            m.quad(right + e, eave, back - e, left - e, eave, back - e, left - e, ridge, middle, right + e, ridge, middle, roof, tiles)
            m.quad(left - e, eave, front + e, left - e, eave, back - e, right + e, eave, back - e, right + e, eave, front + e, 0xCBC5BB)
            m.triangle(right, top, front, right, top, back, right, ridge, middle, wall, gable)
            m.triangle(left, top, back, left, top, front, left, ridge, middle, wall, gable)
            c.solid(left, 0f, back, right, top + 1.8f, front)
            val d = c.detail
            val leaves = Mat.of(Tex.LEAVES)
            d.box(left, CURB, front + 1.4f, left + 4.8f, 1.05f, front + 2.2f, 0x86A860, leaves, bottom = false)
            d.box(left + 7f, CURB, front + 1.4f, right, 1.05f, front + 2.2f, 0x86A860, leaves, bottom = false)
            tree(c, right + 3f, front + 3f, between(0.7f, 1.1f))
        }
    }

    private fun park(c: Chunk, x0: Float, z0: Float, x1: Float, z1: Float) {
        val m = c.mesh
        m.box(x0 + 3, CURB, z0 + 3, x1 - 3, 0.28f, z1 - 3, 0xFFFFFF, Mat.of(Tex.GRASS), bottom = false)
        m.box(c.x - 1.6f, 0.28f, z0 + 3, c.x + 1.6f, 0.34f, z1 - 3, 0xF3DFC0, Mat.of(Tex.PAVING), bottom = false)
        m.cylinder(c.x, 0.28f, c.z, 10f, 0.5f, 10f, 32, 0xEFE8DA, Mat.of(Tex.CONCRETE))
        m.cylinder(c.x, 0.5f, c.z, 9f, 0.6f, 9f, 32, 0x356E85, Mat.WATER)
        for (side in floatArrayOf(-1f, 1f)) for (along in floatArrayOf(-18f, -13.5f, 13.5f, 18f)) {
            val bx = c.x + side * 2.3f; val bz = c.z + along
            c.detail.centered(bx, 0.72f, bz, 0.5f, 0.07f, 1.6f, 0xA8794C)
            c.detail.centered(bx + side * 0.25f, 1.0f, bz, 0.06f, 0.4f, 1.6f, 0xA8794C)
            c.detail.centered(bx, 0.5f, bz - 0.6f, 0.4f, 0.4f, 0.07f, 0x3A3F42)
            c.detail.centered(bx, 0.5f, bz + 0.6f, 0.4f, 0.4f, 0.07f, 0x3A3F42)
        }
        c.solid(x0 + 3, 0f, z0 + 3, x1 - 3, 0.3f, z1 - 3)
        repeat(26) {
            val x = between(x0 + 6, x1 - 6); val z = between(z0 + 6, z1 - 6)
            if (hypot(x - c.x, z - c.z) > 13f && abs(x - c.x) > 4f) tree(c, x, z, between(0.8f, 1.5f))
        }
    }

    private fun carPark(c: Chunk, x0: Float, z0: Float, x1: Float, z1: Float) {
        val m = c.mesh
        m.box(x0 + 3, CURB, z0 + 3, x1 - 3, 0.22f, z1 - 3, 0xFFFFFF, Mat.of(Tex.ASPHALT), bottom = false)
        val colors = intArrayOf(0xE8E8E4, 0x22262B, 0x9C2F2A, 0x2E5277, 0x8B9297)
        for (row in 0..2) {
            var x = x0 + 7f
            while (x < x1 - 6) {
                val z = z0 + 10f + row * 17f
                m.box(x, 0.22f, z, x + 0.15f, 0.25f, z + 5.5f, 0xDDDDD4, bottom = false)
                if (random.nextFloat() < 0.6f) {
                    val color = colors[random.nextInt(colors.size)]
                    c.detail.centered(x + 1.6f, 0.85f, z + 2.7f, 1.8f, 0.8f, 4.1f, color, Mat.GLOSSY)
                    c.detail.centered(x + 1.6f, 1.5f, z + 2.8f, 1.55f, 0.55f, 2.2f, 0x26323A, Mat.GLASS)
                    c.solid(x + 0.7f, 0.2f, z + 0.6f, x + 2.5f, 1.8f, z + 4.8f)
                }
                x += 3.2f
            }
        }
    }

    private fun station(c: Chunk, x0: Float, z0: Float, x1: Float, z1: Float) {
        val m = c.mesh
        val top = CURB + 3 * 3.3f
        building(c, 18f, 45f, 54f, 60f, CURB, top, 0xC4DCEF, Tex.OFFICE)
        m.box(27f, 4.0f, 43.2f, 45f, 4.6f, 45f, 0x1F5FA8, Mat.GLOSSY)
        c.solid(27f, 0f, 43.2f, 45f, 4.6f, 45f)
        rooftop(c, 30f, 53f, top)
        val px = HOME_X; val pz = HOME_Z
        val pad = HOME_Y - 0.03f
        m.cylinder(px, CURB, pz, 5.4f, pad, 5.4f, 48, 0xFFFFFF, Mat.of(Tex.CONCRETE))
        m.cylinder(px, pad, pz, 4.8f, HOME_Y, 4.8f, 48, 0xC0CCD2, Mat.of(Tex.DECK))
        val mark = HOME_Y + 0.012f
        m.box(px - 1.8f, HOME_Y, pz - 2.2f, px - 1.1f, mark, pz + 2.2f, 0xD9A928)
        m.box(px + 1.1f, HOME_Y, pz - 2.2f, px + 1.8f, mark, pz + 2.2f, 0xD9A928)
        m.box(px - 1.1f, HOME_Y, pz - 0.35f, px + 1.1f, mark, pz + 0.35f, 0xD9A928)
        c.solid(px - 4.8f, 0f, pz - 4.8f, px + 4.8f, HOME_Y, pz + 4.8f)
        for (k in 0 until 12) {
            val a = k * 2 * PI_F / 12
            m.centered(px + cos(a) * 5.1f, 0.42f, pz + sin(a) * 5.1f, 0.2f, 0.2f, 0.2f, 0x58E6FF, Mat.EMISSIVE)
        }
        for (x in floatArrayOf(x0 + 5f, x1 - 5f)) for (z in floatArrayOf(z0 + 5f, c.z, z1 - 5f)) tree(c, x, z, 0.9f)
    }

    private fun tree(c: Chunk, x: Float, z: Float, scale: Float) {
        val m = c.detail
        val color = leafColors[random.nextInt(leafColors.size)]
        val s = scale
        m.cylinder(x, CURB, z, 0.22f * s, 2.6f * s, 0.13f * s, 5, 0xFFFFFF, Mat.of(Tex.BARK), cap = false)
        val leaves = Mat.of(Tex.LEAVES)
        val lean = (random.nextFloat() - 0.5f) * s
        m.ellipsoid(x, 3.0f * s, z, 1.75f * s, 1.35f * s, 1.65f * s, 6, 4, color, leaves, 0.25f)
        m.ellipsoid(x + lean * 0.8f, 4.0f * s, z + lean * 0.5f, 1.15f * s, 1.05f * s, 1.15f * s, 5, 3, color, leaves, 0.3f)
        c.solid(x - 1.3f * s, 1.9f * s, z - 1.3f * s, x + 1.3f * s, 4.8f * s, z + 1.3f * s, false)
        c.solid(x - 0.2f, 0f, z - 0.2f, x + 0.2f, 1.9f * s, z + 0.2f, false)
    }

    private fun lamp(c: Chunk, x: Float, z: Float, towardBlock: Float) {
        c.detail.box(x - 0.09f, CURB, z - 0.09f, x + 0.09f, 6.2f, z + 0.09f, 0x8A9298, Mat.GLOSSY)
        val tip = x - towardBlock * 2f
        c.detail.box(min(x, tip), 6.2f, z - 0.08f, max(x, tip), 6.35f, z + 0.08f, 0x555D62)
        c.detail.centered(tip, 6.15f, z, 0.7f, 0.12f, 0.35f, 0xFFF1C2, Mat.EMISSIVE)
        c.solid(x - 0.12f, 0f, z - 0.12f, x + 0.12f, 6.35f, z + 0.12f, false)
    }

    private fun buildJunction(i: Int, j: Int) {
        val x = road(i); val z = road(j)
        val c = chunkAt(x.coerceIn(-HALF + 1, HALF - 1), z.coerceIn(-HALF + 1, HALF - 1))
        val group = (i + j) and 1
        // One mast for each approach, on the far right corner from the driver's point of view.
        for (k in 0..3) {
            val yaw = k * PI_F / 2
            val fx = sin(yaw).roundToInt().toFloat(); val fz = -cos(yaw).roundToInt().toFloat()
            val rx = -fz; val rz = fx
            val px = x + fx * 8.7f + rx * 8.7f; val pz = z + fz * 8.7f + rz * 8.7f
            c.detail.box(px - 0.13f, 0f, pz - 0.13f, px + 0.13f, 6.4f, pz + 0.13f, 0x7A8288, Mat.GLOSSY, bottom = false)
            c.solid(px - 0.15f, 0f, pz - 0.15f, px + 0.15f, 6.5f, pz + 0.15f, false)
            val ex = px - rx * 6.4f; val ez = pz - rz * 6.4f
            c.detail.box(min(px, ex) - 0.08f, 6.2f, min(pz, ez) - 0.08f, max(px, ex) + 0.08f, 6.36f, max(pz, ez) + 0.08f, 0x7A8288, Mat.GLOSSY)
            c.solid(min(px, ex) - 0.3f, 5.3f, min(pz, ez) - 0.3f, max(px, ex) + 0.3f, 6.5f, max(pz, ez) + 0.3f, false)
            val axis = if (fz != 0f) 0 else 1
            for (back in floatArrayOf(3.0f, 6.4f)) {
                val hx = px - rx * back; val hz = pz - rz * back
                c.detail.centered(hx, 5.85f, hz, if (axis == 0) 0.55f else 0.3f, 1.4f, if (axis == 0) 0.3f else 0.55f, 0x17191B)
                val colors = intArrayOf(0x3BFF83, 0xFFC531, 0xFF392F)
                for (light in 0..2) {
                    // A single quad facing the approaching driver.
                    val bx = hx - fx * 0.165f; val bz = hz - fz * 0.165f
                    val sx = rx * 0.16f; val sz = rz * 0.16f
                    val y = 5.43f + light * 0.42f
                    c.detail.quad(
                        bx - sx, y - 0.16f, bz - sz, bx + sx, y - 0.16f, bz + sz,
                        bx + sx, y + 0.16f, bz + sz, bx - sx, y + 0.16f, bz - sz,
                        colors[light], Mat.SIGNAL + group * 6 + axis * 3 + light,
                    )
                }
            }
        }
    }

    private fun buildTerrain() {
        val edge = 3000f
        terrain.quad(-edge, 0f, edge, edge, 0f, edge, edge, 0f, -edge, -edge, 0f, -edge, 0xFFFFFF, Mat.GROUND)
        for (k in 0 until 56) {
            val a = k * 2 * PI_F / 56
            val distance = between(850f, 1000f)
            val color = if (k % 3 == 0) 0x8FA592 else 0xA8B7A0
            terrain.cylinder(cos(a) * distance, -2f, sin(a) * distance, between(90f, 150f), between(20f, 65f), 12f, 12, color, Mat.of(Tex.GRASS))
        }
        repeat(500) {
            val a = between(0f, 2 * PI_F); val distance = between(670f, 800f)
            val x = cos(a) * distance; val z = sin(a) * distance
            val h = between(5f, 10f)
            terrain.cylinder(x, 0f, z, h * 0.45f, h, 0.1f, 5, leafColors[random.nextInt(leafColors.size)], Mat.of(Tex.LEAVES))
        }
    }

    private fun cell(v: Float) = floor((v + HALF) / PITCH).toInt().coerceIn(0, BLOCKS - 1)

    fun chunkAt(x: Float, z: Float): Chunk = chunks[cell(z) * BLOCKS + cell(x)]

    /** Highest surface under a point that is no higher than [below]. */
    fun support(x: Float, z: Float, below: Float, radius: Float): Float {
        var best = 0f
        for (j in cell(z - 12f)..cell(z + 12f)) for (i in cell(x - 12f)..cell(x + 12f)) {
            val c = chunks[j * BLOCKS + i]; val b = c.boxes
            var k = 0
            while (k < c.count * 6) {
                val top = b[k + 4]
                if (top > best && top <= below && x + radius > b[k] && x - radius < b[k + 3] &&
                    z + radius > b[k + 2] && z - radius < b[k + 5]
                ) best = top
                k += 6
            }
        }
        return best
    }

    /** Distance along a normalized ray to the first solid, or [range]. Writes the surface normal to [normal]. */
    fun raycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, range: Float, normal: FloatArray? = null, opaqueOnly: Boolean = false): Float {
        var best = range
        val ex = ox + dx * range; val ez = oz + dz * range
        for (j in cell(min(oz, ez) - 12f)..cell(max(oz, ez) + 12f)) for (i in cell(min(ox, ex) - 12f)..cell(max(ox, ex) + 12f)) {
            val c = chunks[j * BLOCKS + i]
            if (min(oy, oy + dy * range) > c.top) continue
            val b = c.boxes
            var k = 0
            while (k < c.count * 6) {
                if (opaqueOnly && !c.blocksView[k / 6]) { k += 6; continue }
                var near = 0f; var far = best; var hitAxis = -1; var hit = true
                for (axis in 0..2) {
                    val o = if (axis == 0) ox else if (axis == 1) oy else oz
                    val d = if (axis == 0) dx else if (axis == 1) dy else dz
                    val low = b[k + axis]; val high = b[k + axis + 3]
                    if (abs(d) < 1e-6f) {
                        if (o < low || o > high) { hit = false; break }
                    } else {
                        val enter = ((if (d > 0) low else high) - o) / d
                        val leave = ((if (d > 0) high else low) - o) / d
                        if (enter > near) { near = enter; hitAxis = axis }
                        far = min(far, leave)
                        if (near > far) { hit = false; break }
                    }
                }
                if (hit && hitAxis >= 0 && near < best) {
                    best = near
                    if (normal != null) {
                        normal[0] = if (hitAxis == 0) -sign(dx) else 0f
                        normal[1] = if (hitAxis == 1) -sign(dy) else 0f
                        normal[2] = if (hitAxis == 2) -sign(dz) else 0f
                    }
                }
                k += 6
            }
        }
        return best
    }
}
