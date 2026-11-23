package com.skycop.sim.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*

/** Each entry is one layer of the texture array; [width] and [height] are the meters one tile covers. */
enum class Tex(val width: Float, val height: Float) {
    ASPHALT(4f, 4f),
    PAVING(3f, 3f),
    CONCRETE(4f, 4f),
    GRASS(3.5f, 3.5f),
    BRICK(2f, 2f),
    BRICK_FLATS(6f, 6.6f),
    PLASTER_FLATS(6f, 6.6f),
    OFFICE(6f, 6.6f),
    CURTAIN_WALL(6f, 6.6f),
    STONE_TOWER(6f, 6.6f),
    SHOPS(12f, 3.3f),
    ROOF(6f, 6f),
    ROOF_TILES(2.4f, 2.4f),
    BARK(1.2f, 1.6f),
    LEAVES(2.2f, 2.2f),
    CARBON(0.16f, 0.16f),
    DECK(2.4f, 2.4f),
    METAL(2f, 2f),
}

/**
 * Surface textures painted in code at first launch, so there are no image files to ship or license.
 * Every layer has color, gloss and relief; relief becomes a normal map lit by the same sun as the city.
 */
object Textures {
    const val SIZE = 512

    class Pixels(val color: ByteBuffer, val normal: ByteBuffer)

    private val built: Pixels by lazy { build() }

    fun get(): Pixels = built

    /** Starts painting early so the render thread has less to wait for. */
    fun prepare() {
        Thread({ get() }, "SkyCopTextures").start()
    }

    private fun build(): Pixels {
        val layers = Tex.values()
        val color = ByteBuffer.allocateDirect(layers.size * SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
        val normal = ByteBuffer.allocateDirect(layers.size * SIZE * SIZE * 2).order(ByteOrder.nativeOrder())
        val next = AtomicInteger()
        val workers = List(Runtime.getRuntime().availableProcessors().coerceIn(1, 6)) {
            Thread {
                val painter = Painter()
                val colorOut = color.duplicate(); val normalOut = normal.duplicate()
                while (true) {
                    val index = next.getAndIncrement()
                    if (index >= layers.size) break
                    colorOut.position(index * SIZE * SIZE * 4)
                    normalOut.position(index * SIZE * SIZE * 2)
                    painter.paint(layers[index], colorOut, normalOut)
                }
            }.also { it.start() }
        }
        workers.forEach { it.join() }
        return Pixels(color, normal)
    }
}

/** Paints one layer at a time. Coordinates are meters, with y pointing up the wall. */
private class Painter {
    private val n = Textures.SIZE
    private val relief = FloatArray(n * n)
    private val colorBytes = ByteArray(n * n * 4)
    private val normalBytes = ByteArray(n * n * 2)

    private var tex = Tex.ASPHALT
    private var u = 0f
    private var v = 0f
    private var x = 0f
    private var y = 0f

    private var r = 0f
    private var g = 0f
    private var b = 0f
    private var gloss = 0f
    private var depth = 0f

    fun paint(target: Tex, color: ByteBuffer, normal: ByteBuffer) {
        tex = target
        for (row in 0 until n) for (column in 0 until n) {
            u = (column + 0.5f) / n; v = (row + 0.5f) / n
            x = u * target.width; y = v * target.height
            gloss = 0f; depth = 0f
            pixel()
            val i = row * n + column
            relief[i] = depth
            colorBytes[i * 4] = byte(r); colorBytes[i * 4 + 1] = byte(g)
            colorBytes[i * 4 + 2] = byte(b); colorBytes[i * 4 + 3] = byte(gloss)
        }
        val stepX = 2f * target.width / n; val stepY = 2f * target.height / n
        for (row in 0 until n) for (column in 0 until n) {
            val dx = (relief[row * n + (column + 1) % n] - relief[row * n + (column + n - 1) % n]) / stepX
            val dy = (relief[(row + 1) % n * n + column] - relief[(row + n - 1) % n * n + column]) / stepY
            val length = sqrt(dx * dx + dy * dy + 1f)
            val i = (row * n + column) * 2
            normalBytes[i] = byte(0.5f - 0.5f * dx / length)
            normalBytes[i + 1] = byte(0.5f - 0.5f * dy / length)
        }
        color.put(colorBytes)
        normal.put(normalBytes)
    }

    private fun byte(value: Float) = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()

    private fun pixel() = when (tex) {
        Tex.ASPHALT -> asphalt()
        Tex.PAVING -> paving()
        Tex.CONCRETE -> concrete()
        Tex.GRASS -> grass()
        Tex.BRICK -> bricks()
        Tex.BRICK_FLATS -> brickFlats()
        Tex.PLASTER_FLATS -> plasterFlats()
        Tex.OFFICE -> office()
        Tex.CURTAIN_WALL -> curtainWall()
        Tex.STONE_TOWER -> stoneTower()
        Tex.SHOPS -> shops()
        Tex.ROOF -> roof()
        Tex.ROOF_TILES -> roofTiles()
        Tex.BARK -> bark()
        Tex.LEAVES -> leaves()
        Tex.CARBON -> carbon()
        Tex.DECK -> deck()
        Tex.METAL -> metal()
    }

    private fun rgb(color: Int, light: Float = 1f) {
        r = ((color shr 16) and 255) / 255f * light
        g = ((color shr 8) and 255) / 255f * light
        b = (color and 255) / 255f * light
    }

    private fun mixTo(color: Int, amount: Float) {
        r += (((color shr 16) and 255) / 255f - r) * amount
        g += (((color shr 8) and 255) / 255f - g) * amount
        b += ((color and 255) / 255f - b) * amount
    }

    private fun shade(amount: Float) {
        r *= amount; g *= amount; b *= amount
    }

    private fun hash(i: Int, j: Int, seed: Int): Float {
        var h = i * 374761393 + j * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xffff) / 65536f
    }

    /** Value noise that repeats exactly at the tile edge, so no seam shows. */
    private fun noise(fx: Int, fy: Int, seed: Int): Float {
        val px = u * fx; val py = v * fy
        val ix = px.toInt(); val iy = py.toInt()
        var tx = px - ix; var ty = py - iy
        tx *= tx * (3f - 2f * tx); ty *= ty * (3f - 2f * ty)
        val x0 = ix % fx; val x1 = (ix + 1) % fx
        val y0 = iy % fy; val y1 = (iy + 1) % fy
        val a = hash(x0, y0, seed); val b = hash(x1, y0, seed)
        val c = hash(x0, y1, seed); val d = hash(x1, y1, seed)
        val low = a + (b - a) * tx
        return low + (c + (d - c) * tx - low) * ty
    }

    private fun fbm(frequency: Int, seed: Int) = noise(frequency, frequency, seed) * 0.55f +
        noise(frequency * 2, frequency * 2, seed + 1) * 0.3f + noise(frequency * 4, frequency * 4, seed + 2) * 0.15f

    private fun inBox(px: Float, py: Float, left: Float, bottom: Float, right: Float, top: Float) =
        px >= left && px < right && py >= bottom && py < top

    private fun asphalt() {
        val grain = noise(170, 170, 1); val stones = noise(90, 90, 2); val wear = fbm(4, 3)
        rgb(0x565B60, 0.78f + grain * 0.26f + (wear - 0.5f) * 0.22f)
        if (stones > 0.8f) mixTo(0x9A9C98, (stones - 0.8f) * 3f)
        val crack = abs(fbm(5, 9) - 0.5f)
        if (crack < 0.006f && noise(3, 3, 12) > 0.58f) shade(0.62f)
        depth = grain * 0.006f
    }

    private fun paving() {
        val size = tex.width / 5
        val i = (x / size).toInt(); val j = (y / size).toInt()
        val lx = x - i * size; val ly = y - j * size
        val edge = min(min(lx, size - lx), min(ly, size - ly))
        val grain = noise(150, 150, 20)
        if (edge < 0.012f) {
            rgb(0x6E6A63); depth = -0.008f
        } else {
            rgb(0xB9B3A8, 0.82f + hash(i, j, 21) * 0.17f + grain * 0.1f + (fbm(5, 22) - 0.5f) * 0.18f)
            if (edge < 0.03f) shade(0.93f)
            depth = grain * 0.002f
        }
    }

    private fun concrete() {
        val grain = noise(140, 140, 30)
        rgb(0xC4C2BB, 0.8f + fbm(4, 31) * 0.2f + grain * 0.07f)
        if (noise(70, 70, 33) > 0.9f) shade(0.85f)
        depth = grain * 0.003f
    }

    private fun grass() {
        val blades = noise(190, 90, 40); val clumps = fbm(9, 41); val dry = fbm(3, 45)
        rgb(0x55803A, 0.62f + blades * 0.38f + clumps * 0.24f)
        mixTo(0x93975A, ((dry - 0.5f) * 1.6f).coerceIn(0f, 0.5f))
        if (noise(120, 120, 49) > 0.94f) mixTo(0xC9CF8A, 0.4f)
        depth = blades * 0.025f
    }

    private fun brickPattern() {
        val rows = (tex.height / 0.11f).roundToInt(); val columns = (tex.width / 0.27f).roundToInt()
        val h = tex.height / rows; val w = tex.width / columns
        val row = (y / h).toInt()
        val shifted = x + if (row % 2 == 1) w / 2 else 0f
        val column = (shifted / w).toInt()
        val lx = shifted - column * w; val ly = y - row * h
        val grain = noise(180, 180, 50)
        if (lx < 0.018f || ly < 0.018f) {
            rgb(0xC3BBAE, 0.85f + grain * 0.15f); depth = -0.008f
        } else {
            val pick = hash(column % columns, row, 51)
            rgb(if (pick < 0.3f) 0x9A5A45 else if (pick < 0.8f) 0xA96A50 else 0x86503F, 0.85f + pick * 0.17f + (grain - 0.5f) * 0.16f)
            depth = grain * 0.003f
        }
    }

    private fun bricks() = brickPattern()

    /** Glazing with frame, reveal shadow, curtains or blinds and a hint of the room behind. */
    private fun window(lx: Float, ly: Float, w: Float, h: Float, frame: Int, seed: Float, panes: Int) {
        val border = min(min(lx, w - lx), min(ly, h - ly))
        val pane = w / panes
        val divider = abs(lx - ((lx / pane).roundToInt() * pane))
        if (border < 0.065f || (panes > 1 && divider < 0.025f)) {
            rgb(frame, if (border < 0.02f) 0.75f else 1f)
            gloss = 0.15f; depth = -0.035f
            return
        }
        depth = -0.07f; gloss = 0.85f
        rgb(0x25323B, 0.75f + 0.5f * (1f - ly / h) + seed * 0.2f)
        if (seed > 0.8f) mixTo(0xC9A86A, 0.3f * ly / h + 0.1f)
        if (ly < h * 0.3f && abs(lx - w * (0.3f + seed * 0.4f)) < w * 0.16f) shade(0.65f)
        if (seed < 0.3f) {
            val drop = h * (0.25f + seed * 2f)
            if (ly > h - drop) {
                rgb(0xB5B2A6, if (((h - ly) / 0.07f).toInt() % 2 == 0) 0.95f else 0.78f)
                gloss = 0.45f
            }
        } else if (seed < 0.62f) {
            if (min(lx, w - lx) < w * 0.22f) {
                rgb(if (seed < 0.45f) 0xC8B9A4 else 0x7C8FA0, 0.8f + 0.2f * sin(lx * 45f))
                gloss = 0.45f
            }
        }
        // Contact shadow under the head of the window.
        if (h - ly < 0.16f) shade(0.65f)
    }

    private fun brickFlats() {
        val bay = (x / 3f).toInt(); val floor = (y / 3.3f).toInt()
        val lx = x - bay * 3f; val ly = y - floor * 3.3f
        if (inBox(lx, ly, 0.85f, 1.0f, 2.15f, 2.65f)) {
            window(lx - 0.85f, ly - 1f, 1.3f, 1.65f, 0xEFEDE6, hash(bay, floor, 60), 2)
        } else if (inBox(lx, ly, 0.72f, 0.86f, 2.28f, 1.0f)) {
            rgb(0xD8D2C6); depth = 0.04f
        } else if (inBox(lx, ly, 0.78f, 2.65f, 2.22f, 2.86f)) {
            rgb(0xCFC6B8, 0.92f); depth = 0.025f
        } else {
            brickPattern()
            if (inBox(lx, ly, 0.75f, 0.45f, 2.25f, 0.86f)) shade(0.72f + (0.86f - ly) * 0.5f)
            if (ly < 0.12f) {
                rgb(0xBDB4A6); depth = 0.02f
            }
            shade(0.92f + fbm(3, 61) * 0.12f)
        }
    }

    private fun plasterFlats() {
        val bay = (x / 3f).toInt(); val floor = (y / 3.3f).toInt()
        val lx = x - bay * 3f; val ly = y - floor * 3.3f
        val balcony = bay == 0
        if (balcony && inBox(lx, ly, 0.35f, 0.2f, 2.65f, 1.15f)) {
            if (ly > 1.06f || ly < 0.32f) {
                rgb(if (ly < 0.32f) 0xB9B6AE else 0x464C50); depth = 0.07f
            } else if ((lx / 0.07f).toInt() % 2 == 0) {
                rgb(0x50575C); depth = 0.04f
            } else {
                rgb(0x39434A); depth = -0.05f
            }
            return
        }
        if (balcony && inBox(lx, ly, 0.7f, 0.32f, 2.3f, 2.6f)) {
            window(lx - 0.7f, ly - 0.32f, 1.6f, 2.28f, 0x5B6166, hash(bay, floor, 70), 2)
            return
        }
        if (!balcony && inBox(lx, ly, 0.9f, 1.05f, 2.1f, 2.5f)) {
            window(lx - 0.9f, ly - 1.05f, 1.2f, 1.45f, 0xF4F2EC, hash(bay, floor, 70), 2)
            return
        }
        if (!balcony && inBox(lx, ly, 2.2f, 0.55f, 2.8f, 0.98f)) {
            rgb(0xB4B8B7, if ((ly / 0.06f).toInt() % 2 == 0) 1f else 0.7f)
            depth = 0.07f
            return
        }
        val grain = noise(160, 160, 72)
        rgb(0xE6DFD2, 0.86f + fbm(4, 73) * 0.13f + grain * 0.04f)
        if (noise(40, 3, 74) > 0.72f) shade(0.94f)
        if (inBox(lx, ly, 0.35f, 0f, 2.65f, 0.2f) && balcony) shade(0.7f)
        if (!balcony && inBox(lx, ly, 0.85f, 0.95f, 2.15f, 1.05f)) {
            rgb(0xC9C5BC); depth = 0.04f
        } else {
            depth = grain * 0.003f
        }
    }

    private fun office() {
        val bay = (x / 1.5f).toInt(); val floor = (y / 3.3f).toInt()
        val lx = x - bay * 1.5f; val ly = y - floor * 3.3f
        val pillar = bay % 2 == 0 && lx < 0.22f
        if (!pillar && ly >= 0.95f && ly < 2.9f) {
            val start = if (bay % 2 == 0) 0.22f else 0f
            window(lx - start, ly - 0.95f, 1.5f - start, 1.95f, 0x41484E, hash(bay, floor, 80), 1)
            return
        }
        val grain = noise(170, 170, 82)
        rgb(0xD2CFC6, 0.84f + fbm(4, 83) * 0.13f + grain * 0.05f)
        depth = if (pillar) 0.06f else grain * 0.003f
        if (!pillar && (ly in 0.45f..0.48f)) {
            shade(0.7f); depth = -0.01f
        }
    }

    private fun curtainWall() {
        val bay = (x / 1.5f).toInt(); val floor = (y / 3.3f).toInt()
        val lx = x - bay * 1.5f; val ly = y - floor * 3.3f
        if (lx < 0.07f || ly < 0.09f || (ly >= 0.8f && ly < 0.86f)) {
            rgb(0xA9B4BB); gloss = 0.4f; depth = 0.035f
        } else if (ly < 0.8f) {
            rgb(0x3C4E5A); gloss = 0.6f
        } else {
            val seed = hash(bay, floor, 90)
            rgb(0x28465A, 0.85f + seed * 0.3f + 0.2f * (ly - 0.86f) / 2.44f)
            if (seed < 0.2f && ly > 3.3f - 2f * (0.2f + seed * 3f)) mixTo(0xAEB4B2, 0.45f)
            if (seed > 0.85f && ly > 3.0f) mixTo(0xF5E2B0, 0.7f)
            if (ly < 1.5f && hash((x / 0.5f).toInt(), floor, 91) > 0.7f) shade(0.78f)
            gloss = 0.95f; depth = -0.02f
        }
    }

    private fun stoneTower() {
        val bay = (x / 1.5f).toInt(); val floor = (y / 3.3f).toInt()
        val lx = x - bay * 1.5f; val ly = y - floor * 3.3f
        if (inBox(lx, ly, 0.33f, 0.65f, 1.17f, 2.85f)) {
            window(lx - 0.33f, ly - 0.65f, 0.84f, 2.2f, 0x6A6458, hash(bay, floor, 100), 1)
            return
        }
        val row = (y / 0.55f).toInt()
        val shifted = x + if (row % 2 == 1) 0.375f else 0f
        val column = (shifted / 0.75f).toInt()
        val grain = noise(150, 150, 102)
        rgb(0xCFC1A8, 0.83f + hash(column % 8, row, 101) * 0.12f + grain * 0.07f)
        if (shifted - column * 0.75f < 0.014f || y - row * 0.55f < 0.014f) {
            shade(0.72f); depth = -0.008f
        } else {
            depth = grain * 0.004f
        }
    }

    private fun shops() {
        val shop = (x / 3f).toInt()
        val lx = x - shop * 3f
        val brand = when (shop) { 0 -> 0x1F5F8B; 1 -> 0xA33A2E; 2 -> 0x2F6B45; else -> 0x3A3F47 }
        when {
            lx < 0.14f || lx > 2.86f -> {
                rgb(0x8D8A83, 0.9f + noise(90, 90, 110) * 0.15f); depth = 0.05f
            }
            y > 2.65f -> {
                rgb(brand); depth = 0.07f
                // Lettering-like blocks; no real brand names.
                if (inBox(lx, y, 0.5f, 2.82f, 2.5f, 3.12f)) {
                    val letter = ((lx - 0.5f) / 0.25f).toInt()
                    val inside = lx - 0.5f - letter * 0.25f
                    if (inside < 0.18f && hash(shop, letter, 111) > 0.15f) {
                        val hole = inside in 0.06f..0.12f && (y - 2.82f) in 0.07f..0.22f
                        if (!hole) rgb(0xF4F1E6)
                    }
                }
            }
            y > 2.2f -> {
                val stripe = ((lx - 0.14f) / 0.3f).toInt() % 2 == 0
                rgb(if (stripe) brand else 0xE9E4D8, 0.7f + (y - 2.2f) * 0.8f)
                depth = 0.12f - (y - 2.2f) * 0.1f
            }
            y < 0.22f -> rgb(0x4B4D4E)
            shop == 3 && lx < 1.9f -> {
                rgb(0x9BA1A4, 0.75f + 0.25f * sin(y * 45f)); depth = sin(y * 45f) * 0.006f; gloss = 0.2f
            }
            inBox(lx, y, 2.0f, 0.22f, 2.75f, 2.1f) -> {
                if (inBox(lx, y, 2.1f, 0.5f, 2.65f, 1.95f)) {
                    rgb(0x2B3A42); gloss = 0.8f
                } else {
                    rgb(brand, 0.75f)
                }
                if (inBox(lx, y, 2.12f, 1.1f, 2.17f, 1.3f)) rgb(0xD8D8D0)
            }
            else -> {
                rgb(0x2A3238, 0.9f + (2.2f - y) * 0.2f); gloss = 0.7f; depth = -0.05f
                // Goods on shelves behind the glass.
                val shelf = (y / 0.45f).toInt(); val item = (x / 0.22f).toInt()
                if (shelf in 1..3 && y - shelf * 0.45f < 0.1f + hash(item, shelf, 112) * 0.2f) {
                    rgb(0x8A6F52, 0.5f + hash(item, shelf, 113) * 0.9f)
                    if (hash(item, shelf, 114) > 0.6f) mixTo(brand, 0.6f)
                }
                if (abs(lx - 0.17f) < 0.04f || abs(lx - 1.95f) < 0.05f || abs(y - 2.17f) < 0.04f) rgb(0x50565B)
            }
        }
    }

    private fun roof() {
        val stones = noise(200, 200, 120); val dirt = fbm(3, 121)
        rgb(0x8B8A85, 0.62f + stones * 0.35f + dirt * 0.2f)
        depth = stones * 0.01f
        val seam = x % 2f
        if (seam < 0.05f) {
            shade(0.75f); depth = 0.012f
        }
        if (fbm(5, 125) > 0.74f) shade(0.85f)
    }

    private fun roofTiles() {
        val row = (y / 0.3f).toInt()
        val shifted = x + if (row % 2 == 1) 0.12f else 0f
        val column = (shifted / 0.24f).toInt()
        val lx = (shifted - column * 0.24f) / 0.24f; val ly = (y - row * 0.3f) / 0.3f
        val curve = sin(lx * PI_F)
        rgb(0xD9D2CB, (0.62f + curve * 0.3f + hash(column % 10, row, 130) * 0.14f) * if (ly > 0.88f) 0.6f else 1f)
        depth = curve * 0.02f + (1 - ly) * 0.025f
    }

    private fun bark() {
        val ridges = noise(28, 5, 140) * 0.7f + noise(70, 14, 141) * 0.3f
        rgb(0x6E5844, 0.55f + ridges * 0.65f)
        depth = ridges * 0.03f
    }

    private fun leaves() {
        val small = noise(80, 80, 150); val clump = noise(17, 17, 151); val mass = fbm(5, 152)
        val light = small * 0.45f + clump * 0.4f + mass * 0.35f
        rgb(0xB9D19A, 0.2f + light * 0.95f)
        if (small > 0.83f) mixTo(0xE6F0BE, 0.35f)
        depth = small * 0.05f + clump * 0.08f
    }

    private fun carbon() {
        val i = (u * 16).toInt(); val j = (v * 16).toInt()
        val across = (i + j) % 4 < 2
        val f = if (across) u * 16 - i else v * 16 - j
        rgb(0x3A4046, 0.6f + 0.55f * sin(f * PI_F) + if (across) 0.1f else 0f)
        gloss = 0.35f
        depth = sin(f * PI_F) * 0.0006f
    }

    private fun deck() {
        val size = tex.width / 2
        val lx = x % size; val ly = y % size
        val grain = noise(170, 170, 170)
        rgb(0x4A5D68, 0.8f + grain * 0.15f + fbm(4, 171) * 0.2f)
        depth = grain * 0.003f
        if (lx < 0.015f || ly < 0.015f) {
            shade(0.6f); depth = -0.008f
        }
        val bx = min(lx, size - lx) - 0.1f; val by = min(ly, size - ly) - 0.1f
        if (bx * bx + by * by < 0.0012f) {
            rgb(0x93A0A6); depth = 0.01f; gloss = 0.4f
        }
    }

    private fun metal() {
        val wave = sin(u * 2 * PI_F * 18)
        val streak = noise(90, 4, 180)
        rgb(0xB6BCBE, 0.72f + wave * 0.07f + streak * 0.14f + fbm(4, 181) * 0.1f)
        if (noise(12, 12, 185) > 0.86f) mixTo(0x8A6A50, 0.3f)
        depth = wave * 0.006f
        gloss = 0.2f
    }
}
