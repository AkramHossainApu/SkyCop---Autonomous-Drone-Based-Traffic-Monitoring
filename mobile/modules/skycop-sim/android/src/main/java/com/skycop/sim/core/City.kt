package com.skycop.sim.core

import kotlin.math.*
import kotlin.random.Random

/**
 * A real Dhaka neighbourhood built from OpenStreetMap data (see MapData): every building footprint,
 * road, park, canal and junction is where it is in the real city. Heights, facades and rooftops
 * follow how Dhaka is built: six to ten storey apartment blocks with water tanks on the roof,
 * shopfronts along the bigger roads, tin sheds in the lanes, glass towers and malls, mosques with
 * domes and minarets, and electric poles with sagging wires along the streets.
 *
 * Meshes are grouped in 100 m chunks so the renderer can cull them, and colliders are oriented boxes
 * registered in every chunk they overlap, so a query only looks at the chunks it touches.
 */
class City(val map: MapData) {
    companion object {
        const val HALF = 1000f
        const val CELL = 100f
        const val CELLS = 20
        const val CURB = 0.15f
        /** The drone's soft geofence. */
        const val BOUNDS = HALF - 25f
        const val DOCK_TOP = 7.2f
        const val DOCK_RADIUS = 1.7f
        const val ROAD_Y = 0.06f
        const val ASPHALT = 0xC9C9C6
        private const val WIRE = 0x23262A
    }

    /** [mesh] is what reads from far away; [detail] is the small stuff drawn only nearby. */
    class Chunk(val x: Float, val z: Float) {
        val mesh = MeshBuilder()
        val detail = MeshBuilder()
        var top = 6f
        /** How far this chunk's meshes reach from its center, for culling. */
        var reach = CELL / 2
        /** Oriented boxes, eight floats each: center x, center z, cos, sin, half x, half z, bottom, top. */
        var boxes = FloatArray(8 * 32)
        /** False for trees, poles and wires, which the chase camera looks through. */
        var blocksView = BooleanArray(32)
        var count = 0

        fun add(cx: Float, cz: Float, c: Float, s: Float, hx: Float, hz: Float, y0: Float, y1: Float, opaque: Boolean) {
            if (count * 8 == boxes.size) {
                boxes = boxes.copyOf(boxes.size * 2)
                blocksView = blocksView.copyOf(count * 2)
            }
            blocksView[count] = opaque
            val i = count * 8
            boxes[i] = cx; boxes[i + 1] = cz; boxes[i + 2] = c; boxes[i + 3] = s
            boxes[i + 4] = hx; boxes[i + 5] = hz; boxes[i + 6] = y0; boxes[i + 7] = y1
            count++
            top = max(top, y1 + 3f)
        }
    }

    val title = map.title
    val chunks = Array(CELLS * CELLS) { Chunk(-HALF + (it % CELLS + 0.5f) * CELL, -HALF + (it / CELLS + 0.5f) * CELL) }
    val terrain = MeshBuilder()
    var tallest = 0f
        private set

    /** x, landing height, z for each dock. */
    val dockCount = map.dockCount
    val docks = FloatArray(dockCount * 3)
    val homeDock = map.home.coerceIn(0, max(0, dockCount - 1))

    /** Distance from a junction's center to where its roads begin, per node. */
    val junctionRadius = FloatArray(map.nodeCount)

    private val random = Random(2026)
    private val point = FloatArray(4)
    private val apartmentTints = intArrayOf(0xF4F1EA, 0xEFE4CF, 0xE9D9BE, 0xDCDCD6, 0xF3E3B5, 0xF0CDB4, 0xCFDDE6, 0xD9E6D1, 0xE8C9C0, 0xC7C2B8)
    private val houseTints = intArrayOf(0xEFE7D6, 0xE6C9A0, 0xD8E4D8, 0xF2DCC4, 0xD7CFC4, 0xF4E9C9)
    private val towerTints = intArrayOf(0xFFFFFF, 0xDCE6EC, 0xC9D8E2, 0xE8E0D4)
    private val signColors = intArrayOf(0xC8202A, 0x1D7A3E, 0xF0B323, 0x1F4E9A, 0xE8641C, 0x8A1F7A, 0x0E8C8C, 0xF4F4F0)
    private val tankColors = intArrayOf(0x1C1E20, 0x1C1E20, 0x2D5D9A, 0xE8E6DF, 0xD9B43A)
    private val leafColors = intArrayOf(0x6F9A4E, 0x83A85A, 0x5E8A47, 0x92AE5F)

    init {
        for (d in 0 until dockCount) {
            docks[d * 3] = map.dockX[d]; docks[d * 3 + 1] = DOCK_TOP; docks[d * 3 + 2] = map.dockZ[d]
        }
        for (i in 0 until map.areaCount) buildArea(i)
        for (i in 0 until map.ribbonCount) buildRibbon(i)
        buildRoads()
        for (i in 0 until map.buildingCount) buildBuilding(i)
        for (i in 0 until map.landmarkCount) buildLandmark(i)
        for (d in 0 until dockCount) buildDock(map.dockX[d], map.dockZ[d])
        buildTerrain()
        for (chunk in chunks) tallest = max(tallest, chunk.top - 3f)
    }

    private fun between(low: Float, high: Float) = low + random.nextFloat() * (high - low)

    // ---- Spatial bookkeeping -------------------------------------------------------------------

    private fun cell(v: Float) = floor((v + HALF) / CELL).toInt().coerceIn(0, CELLS - 1)

    fun chunkAt(x: Float, z: Float): Chunk = chunks[cell(z) * CELLS + cell(x)]

    /** The chunk whose mesh holds things around (x, z); its reach grows to cover them. */
    private fun meshChunk(x: Float, z: Float, extent: Float): Chunk {
        val c = chunkAt(x, z)
        c.reach = max(c.reach, max(abs(x - c.x), abs(z - c.z)) + extent)
        return c
    }

    /** Registers an oriented box with every chunk it overlaps. */
    fun solid(cx: Float, cz: Float, angle: Float, hx: Float, hz: Float, y0: Float, y1: Float, opaque: Boolean = true) {
        val c = cos(angle); val s = sin(angle)
        val ex = abs(c) * hx + abs(s) * hz; val ez = abs(s) * hx + abs(c) * hz
        for (j in cell(cz - ez)..cell(cz + ez)) for (i in cell(cx - ex)..cell(cx + ex)) {
            chunks[j * CELLS + i].add(cx, cz, c, s, hx, hz, y0, y1, opaque)
        }
    }

    // ---- Geometry helpers ---------------------------------------------------------------------

    /** Ear-clipped flat polygon at height [y], facing up. */
    private fun flat(m: MeshBuilder, xs: FloatArray, zs: FloatArray, from: Int, to: Int, y: Float, color: Int, material: Int) {
        val n = to - from
        if (n < 3) return
        var signed = 0f
        for (k in 0 until n) {
            val a = from + k; val b = from + (k + 1) % n
            signed += xs[a] * zs[b] - xs[b] * zs[a]
        }
        val index = IntArray(n) { from + it }
        var left = n
        var guard = 0
        var k = 0
        while (left > 3 && guard < n * n) {
            guard++
            val ia = index[(k + left - 1) % left]; val ib = index[k % left]; val ic = index[(k + 1) % left]
            val cross = (xs[ib] - xs[ia]) * (zs[ic] - zs[ia]) - (zs[ib] - zs[ia]) * (xs[ic] - xs[ia])
            var ear = cross * signed > 0f
            if (ear) {
                for (t in 0 until left) {
                    val ip = index[t]
                    if (ip == ia || ip == ib || ip == ic) continue
                    if (inTriangle(xs[ip], zs[ip], xs[ia], zs[ia], xs[ib], zs[ib], xs[ic], zs[ic])) { ear = false; break }
                }
            }
            if (ear) {
                upTriangle(m, xs[ia], zs[ia], xs[ib], zs[ib], xs[ic], zs[ic], y, color, material)
                for (t in k % left until left - 1) index[t] = index[t + 1]
                left--
            } else {
                k = (k + 1) % left
            }
        }
        if (left == 3) upTriangle(m, xs[index[0]], zs[index[0]], xs[index[1]], zs[index[1]], xs[index[2]], zs[index[2]], y, color, material)
    }

    private fun inTriangle(px: Float, pz: Float, ax: Float, az: Float, bx: Float, bz: Float, cx: Float, cz: Float): Boolean {
        val d1 = (px - bx) * (az - bz) - (ax - bx) * (pz - bz)
        val d2 = (px - cx) * (bz - cz) - (bx - cx) * (pz - cz)
        val d3 = (px - ax) * (cz - az) - (cx - ax) * (pz - az)
        val negative = d1 < 0f || d2 < 0f || d3 < 0f
        val positive = d1 > 0f || d2 > 0f || d3 > 0f
        return !(negative && positive)
    }

    private fun upTriangle(m: MeshBuilder, ax: Float, az: Float, bx: Float, bz: Float, cx: Float, cz: Float, y: Float, color: Int, material: Int) {
        // The normal's y is uz * vx - ux * vz; keep it positive so the face looks up.
        if ((bz - az) * (cx - ax) - (bx - ax) * (cz - az) >= 0f) m.triangle(ax, y, az, bx, y, bz, cx, y, cz, color, material)
        else m.triangle(ax, y, az, cx, y, cz, bx, y, bz, color, material)
    }

    /** A vertical wall from a to b, facing away from (insideX, insideZ). */
    private fun wall(m: MeshBuilder, ax: Float, az: Float, bx: Float, bz: Float, y0: Float, y1: Float, insideX: Float, insideZ: Float, color: Int, material: Int) {
        // quad(a0, b0, b1, a1) faces along (-(bz - az), 0, bx - ax).
        val out = -(bz - az) * ((ax + bx) / 2 - insideX) + (bx - ax) * ((az + bz) / 2 - insideZ)
        if (out >= 0f) m.quad(ax, y0, az, bx, y0, bz, bx, y1, bz, ax, y1, az, color, material)
        else m.quad(bx, y0, bz, ax, y0, az, ax, y1, az, bx, y1, bz, color, material)
    }

    /** A flat strip along a polyline, with round joints so corners have no gaps. */
    private fun strip(m: MeshBuilder, xs: FloatArray, zs: FloatArray, ys: FloatArray?, from: Int, to: Int, width: Float, lift: Float, color: Int, material: Int) {
        val h = width / 2
        for (k in from until to - 1) {
            val dx = xs[k + 1] - xs[k]; val dz = zs[k + 1] - zs[k]
            val length = hypot(dx, dz)
            if (length < 0.01f) continue
            val px = -dz / length * h; val pz = dx / length * h
            val y0 = (ys?.get(k) ?: 0f) + lift; val y1 = (ys?.get(k + 1) ?: 0f) + lift
            val ax = xs[k]; val az = zs[k]; val bx = xs[k + 1]; val bz = zs[k + 1]
            // With p to the left of travel this winding always faces up.
            m.quad(ax + px, y0, az + pz, bx + px, y1, bz + pz, bx - px, y1, bz - pz, ax - px, y0, az - pz, color, material)
            if (k > from) disc(m, ax, y0, az, h, 8, color, material)
        }
    }

    private fun disc(m: MeshBuilder, x: Float, y: Float, z: Float, r: Float, sides: Int, color: Int, material: Int) {
        for (i in 0 until sides) {
            val a = i * 2 * PI_F / sides; val b = (i + 1) * 2 * PI_F / sides
            upTriangle(m, x, z, x + cos(a) * r, z + sin(a) * r, x + cos(b) * r, z + sin(b) * r, y, color, material)
        }
    }

    private fun upQuad(m: MeshBuilder, ax: Float, az: Float, bx: Float, bz: Float, cx: Float, cz: Float, dx: Float, dz: Float, y: Float, color: Int, material: Int) {
        upTriangle(m, ax, az, bx, bz, cx, cz, y, color, material)
        upTriangle(m, ax, az, cx, cz, dx, dz, y, color, material)
    }

    // ---- Ground cover -------------------------------------------------------------------------

    private fun buildArea(i: Int) {
        val from = map.areaStart[i]; val to = map.areaStart[i + 1]
        var cx = 0f; var cz = 0f
        for (k in from until to) { cx += map.areaX[k]; cz += map.areaZ[k] }
        cx /= (to - from); cz /= (to - from)
        var extent = 0f
        for (k in from until to) extent = max(extent, max(abs(map.areaX[k] - cx), abs(map.areaZ[k] - cz)))
        val c = meshChunk(cx, cz, extent)
        when (map.areaKind[i]) {
            MapData.GRASS -> {
                flat(c.mesh, map.areaX, map.areaZ, from, to, 0.03f, 0xFFFFFF, Mat.of(Tex.GRASS))
                scatterTrees(from, to, cx, cz, extent, 0.0016f)
            }
            MapData.PITCH -> flat(c.mesh, map.areaX, map.areaZ, from, to, 0.035f, 0xE4F0D0, Mat.of(Tex.GRASS))
            MapData.WATER -> flat(c.mesh, map.areaX, map.areaZ, from, to, 0.025f, 0x3F6E78, Mat.WATER)
            MapData.PAVED -> flat(c.mesh, map.areaX, map.areaZ, from, to, 0.04f, 0xE2DED4, Mat.of(Tex.PAVING))
            MapData.GRAVEYARD -> {
                flat(c.mesh, map.areaX, map.areaZ, from, to, 0.03f, 0xB9C4A0, Mat.of(Tex.GRASS))
                scatterTrees(from, to, cx, cz, extent, 0.0025f)
            }
            else -> flat(c.mesh, map.areaX, map.areaZ, from, to, 0.03f, 0xC9B79A, Mat.of(Tex.CONCRETE))
        }
    }

    private fun inside(xs: FloatArray, zs: FloatArray, from: Int, to: Int, x: Float, z: Float): Boolean {
        var hit = false
        var j = to - 1
        for (i in from until to) {
            if ((zs[i] > z) != (zs[j] > z) && x < (xs[j] - xs[i]) * (z - zs[i]) / (zs[j] - zs[i]) + xs[i]) hit = !hit
            j = i
        }
        return hit
    }

    private fun scatterTrees(from: Int, to: Int, cx: Float, cz: Float, extent: Float, density: Float) {
        val tries = min(60, ((2 * extent) * (2 * extent) * density).toInt())
        repeat(tries) {
            val x = cx + between(-extent, extent); val z = cz + between(-extent, extent)
            val s = between(0.8f, 1.4f)
            if (inside(map.areaX, map.areaZ, from, to, x, z)) tree(x, z, s)
        }
    }

    private fun tree(x: Float, z: Float, s: Float) {
        val m = meshChunk(x, z, 3f).detail
        val color = leafColors[random.nextInt(leafColors.size)]
        m.cylinder(x, 0f, z, 0.24f * s, 2.8f * s, 0.14f * s, 5, 0xFFFFFF, Mat.of(Tex.BARK), cap = false)
        val leaves = Mat.of(Tex.LEAVES)
        m.ellipsoid(x, 3.4f * s, z, 2.1f * s, 1.5f * s, 2f * s, 6, 4, color, leaves, 0.25f)
        m.ellipsoid(x + 0.4f * s, 4.5f * s, z - 0.3f * s, 1.3f * s, 1.1f * s, 1.3f * s, 5, 3, color, leaves, 0.3f)
        solid(x, z, 0f, 1.5f * s, 1.5f * s, 2.1f * s, 5.4f * s, false)
        solid(x, z, 0f, 0.22f, 0.22f, 0f, 2.1f * s, false)
    }

    private fun buildRibbon(i: Int) {
        val from = map.ribbonStart[i]; val to = map.ribbonStart[i + 1]
        if (to - from < 2) return
        val w = map.ribbonWidth[i]
        val c = meshChunk(map.ribbonX[from], map.ribbonZ[from], w)
        for (k in from until to) c.reach = max(c.reach, max(abs(map.ribbonX[k] - c.x), abs(map.ribbonZ[k] - c.z)) + w)
        when (map.ribbonKind[i]) {
            MapData.CANAL -> {
                strip(c.mesh, map.ribbonX, map.ribbonZ, null, from, to, w + 2.4f, 0.028f, 0xB8B2A6, Mat.of(Tex.CONCRETE))
                strip(c.mesh, map.ribbonX, map.ribbonZ, null, from, to, w, 0.034f, 0x46705F, Mat.WATER)
            }
            MapData.MINOR_ROAD -> strip(c.mesh, map.ribbonX, map.ribbonZ, null, from, to, w, ROAD_Y, ASPHALT, Mat.of(Tex.ASPHALT))
            else -> strip(c.mesh, map.ribbonX, map.ribbonZ, null, from, to, w, 0.045f, 0xE0DCD2, Mat.of(Tex.PAVING))
        }
    }

    // ---- Roads, junctions, signals, wires -----------------------------------------------------

    private fun buildRoads() {
        val degree = IntArray(map.nodeCount)
        val widest = FloatArray(map.nodeCount)
        for (e in 0 until map.edgeCount) {
            val a = map.edgeA[e]; val b = map.edgeB[e]
            degree[a]++; degree[b]++
            val h = map.edgeWidth[e] / 2
            widest[a] = max(widest[a], h); widest[b] = max(widest[b], h)
        }
        for (n in 0 until map.nodeCount) junctionRadius[n] = if (degree[n] >= 3) widest[n] + 1.2f else 0f

        for (e in 0 until map.edgeCount) {
            val from = map.edgeStart[e]; val to = map.edgeStart[e + 1]
            if (to - from < 2) continue
            val width = map.edgeWidth[e]
            val c = meshChunk(map.edgeX[from], map.edgeZ[from], width)
            for (k in from until to) c.reach = max(c.reach, max(abs(map.edgeX[k] - c.x), abs(map.edgeZ[k] - c.z)) + width)
            var elevated = false
            for (k in from until to) if (map.edgeY[k] > 0.5f) elevated = true
            strip(c.mesh, map.edgeX, map.edgeZ, map.edgeY, from, to, width, ROAD_Y, ASPHALT, Mat.of(Tex.ASPHALT))
            if (elevated) bridge(e, c)
            markings(e, c)
            if (map.edgeClass[e] <= 4 && !elevated) sidewalks(e, c)
            if (map.edgeClass[e] >= 3 && !elevated) wires(e)
        }
        for (n in 0 until map.nodeCount) {
            if (degree[n] >= 2) {
                disc(meshChunk(map.nodeX[n], map.nodeZ[n], widest[n]).mesh, map.nodeX[n], map.nodeY[n] + ROAD_Y, map.nodeZ[n], widest[n], 12, ASPHALT, Mat.of(Tex.ASPHALT))
            }
            if (map.nodeFlags[n] and 1 != 0 && degree[n] >= 3 && map.nodeY[n] < 0.5f) signals(n)
        }
    }

    /** Point and unit direction at distance [s] along edge [e] (forward), into [out]: x, z, dx, dz. */
    fun edgePoint(e: Int, s: Float, out: FloatArray) {
        val from = map.edgeStart[e]; val to = map.edgeStart[e + 1]
        var left = s
        for (k in from until to - 1) {
            val dx = map.edgeX[k + 1] - map.edgeX[k]; val dz = map.edgeZ[k + 1] - map.edgeZ[k]
            val length = hypot(dx, dz)
            if (left <= length || k == to - 2) {
                val t = if (length > 0f) (left / length).coerceIn(0f, 1f) else 0f
                out[0] = map.edgeX[k] + dx * t; out[1] = map.edgeZ[k] + dz * t
                out[2] = if (length > 0f) dx / length else 1f; out[3] = if (length > 0f) dz / length else 0f
                return
            }
            left -= length
        }
        out[0] = map.edgeX[from]; out[1] = map.edgeZ[from]; out[2] = 1f; out[3] = 0f
    }

    fun edgeLength(e: Int): Float {
        var total = 0f
        for (k in map.edgeStart[e] until map.edgeStart[e + 1] - 1) total += hypot(map.edgeX[k + 1] - map.edgeX[k], map.edgeZ[k + 1] - map.edgeZ[k])
        return total
    }

    private fun edgeHeight(e: Int, s: Float): Float {
        val from = map.edgeStart[e]; val to = map.edgeStart[e + 1]
        var left = s
        for (k in from until to - 1) {
            val length = hypot(map.edgeX[k + 1] - map.edgeX[k], map.edgeZ[k + 1] - map.edgeZ[k])
            if (left <= length || k == to - 2) {
                val t = if (length > 0f) (left / length).coerceIn(0f, 1f) else 0f
                return map.edgeY[k] + (map.edgeY[k + 1] - map.edgeY[k]) * t
            }
            left -= length
        }
        return map.edgeY[from]
    }

    /** Painted lines, offset to the left of the edge's forward direction by [offset]. */
    private fun dashes(e: Int, m: MeshBuilder, start: Float, end: Float, offset: Float, dash: Float, gap: Float, color: Int, half: Float) {
        var s = start
        while (s < end) {
            val s2 = min(end, s + dash)
            edgePoint(e, s, point)
            val ax = point[0] + point[3] * offset; val az = point[1] - point[2] * offset
            val y = edgeHeight(e, s) + ROAD_Y + 0.025f
            edgePoint(e, s2, point)
            val bx = point[0] + point[3] * offset; val bz = point[1] - point[2] * offset
            val dx = bx - ax; val dz = bz - az; val l = hypot(dx, dz)
            if (l > 0.05f) {
                val px = -dz / l * half; val pz = dx / l * half
                m.quad(ax + px, y, az + pz, bx + px, y, bz + pz, bx - px, y, bz - pz, ax - px, y, az - pz, color)
            }
            s += dash + gap
        }
    }

    private fun markings(e: Int, c: Chunk) {
        val cls = map.edgeClass[e]
        val length = edgeLength(e)
        val start = junctionRadius[map.edgeA[e]] + 1f; val end = length - junctionRadius[map.edgeB[e]] - 1f
        if (end - start < 4f) return
        val forward = map.lanesForward[e]; val backward = map.lanesBackward[e]
        val laneWidth = map.edgeWidth[e] / (forward + backward)
        val white = 0xE8E8E2; val yellow = 0xE0B23A
        // Traffic keeps left: forward lanes lie to the left of the center line, backward to the right.
        if (backward > 0) {
            if (cls <= 3) dashes(e, c.detail, start, end, 0f, 6f, 0f, yellow, 0.07f)
            else if (cls <= 6) dashes(e, c.detail, start, end, 0f, 3f, 5f, white, 0.06f)
            for (k in 1 until forward) dashes(e, c.detail, start, end, k * laneWidth, 3f, 6f, white, 0.06f)
            for (k in 1 until backward) dashes(e, c.detail, start, end, -k * laneWidth, 3f, 6f, white, 0.06f)
        } else {
            for (k in 1 until forward) dashes(e, c.detail, start, end, map.edgeWidth[e] / 2 - k * laneWidth, 3f, 6f, white, 0.06f)
        }
        if (cls <= 2 && backward > 0 && end - start > 20f && map.edgeY[map.edgeStart[e]] < 0.5f) median(e, c, start, end)
    }

    /** A raised, planted median on the main roads, stopping short of the junctions. */
    private fun median(e: Int, c: Chunk, start: Float, end: Float) {
        var s = start + 4f
        while (s < end - 4f) {
            val s2 = min(end - 4f, s + 12f)
            edgePoint(e, s, point); val ax = point[0]; val az = point[1]
            edgePoint(e, s2, point); val bx = point[0]; val bz = point[1]
            val dx = bx - ax; val dz = bz - az; val l = hypot(dx, dz)
            if (l > 0.5f) {
                val px = -dz / l * 0.45f; val pz = dx / l * 0.45f
                val mx = (ax + bx) / 2; val mz = (az + bz) / 2
                val h = 0.4f
                wall(c.mesh, ax + px, az + pz, bx + px, bz + pz, 0f, h, mx, mz, 0xD8D4CA, Mat.of(Tex.CONCRETE))
                wall(c.mesh, bx - px, bz - pz, ax - px, az - pz, 0f, h, mx, mz, 0xD8D4CA, Mat.of(Tex.CONCRETE))
                wall(c.mesh, ax - px, az - pz, ax + px, az + pz, 0f, h, mx, mz, 0xD8D4CA, Mat.of(Tex.CONCRETE))
                wall(c.mesh, bx + px, bz + pz, bx - px, bz - pz, 0f, h, mx, mz, 0xD8D4CA, Mat.of(Tex.CONCRETE))
                upQuad(c.mesh, ax + px, az + pz, bx + px, bz + pz, bx - px, bz - pz, ax - px, az - pz, h, 0x8DA65E, Mat.of(Tex.GRASS))
                solid(mx, mz, atan2(dz, dx), l / 2, 0.45f, 0f, h)
            }
            s = s2 + 1f
        }
    }

    private fun sidewalks(e: Int, c: Chunk) {
        val length = edgeLength(e)
        val start = junctionRadius[map.edgeA[e]] + 0.5f; val end = length - junctionRadius[map.edgeB[e]] - 0.5f
        if (end - start < 3f) return
        val h = map.edgeWidth[e] / 2
        for (side in intArrayOf(-1, 1)) {
            var s = start
            while (s < end) {
                val s2 = min(end, s + 10f)
                edgePoint(e, s, point)
                val ix = point[0] + point[3] * side * h; val iz = point[1] - point[2] * side * h
                val ox = point[0] + point[3] * side * (h + 1.8f); val oz = point[1] - point[2] * side * (h + 1.8f)
                edgePoint(e, s2, point)
                val jx = point[0] + point[3] * side * h; val jz = point[1] - point[2] * side * h
                val px = point[0] + point[3] * side * (h + 1.8f); val pz = point[1] - point[2] * side * (h + 1.8f)
                upQuad(c.mesh, ix, iz, jx, jz, px, pz, ox, oz, CURB, 0xDCD7CC, Mat.of(Tex.PAVING))
                wall(c.mesh, ix, iz, jx, jz, 0f, CURB, ox, oz, 0xC9C4B8, Mat.of(Tex.CONCRETE))
                s = s2
            }
        }
    }

    /** Concrete poles on one side of the street with a tangle of wires between them. */
    private fun wires(e: Int) {
        val length = edgeLength(e)
        val start = junctionRadius[map.edgeA[e]] + 3f; val end = length - junctionRadius[map.edgeB[e]] - 3f
        if (end - start < 20f) return
        val side = map.edgeWidth[e] / 2 + 1.1f
        val count = max(1, ((end - start) / 30f).roundToInt())
        var lastX = 0f; var lastZ = 0f
        for (k in 0..count) {
            val s = start + (end - start) * k / count
            edgePoint(e, s, point)
            val x = point[0] + point[3] * side; val z = point[1] - point[2] * side
            val ux = point[2]; val uz = point[3]
            val m = meshChunk(x, z, 32f).detail
            m.box(x - 0.13f, 0f, z - 0.13f, x + 0.13f, 9f, z + 0.13f, 0xB9B6AE, Mat.of(Tex.CONCRETE), bottom = false)
            val ax = -uz * 0.9f; val az = ux * 0.9f
            m.box(min(x - ax, x + ax) - 0.05f, 8.2f, min(z - az, z + az) - 0.05f, max(x - ax, x + ax) + 0.05f, 8.32f, max(z - az, z + az) + 0.05f, 0x5B5E60)
            solid(x, z, 0f, 0.15f, 0.15f, 0f, 9f, false)
            if (k > 0) {
                for (strand in 0..2) {
                    val o = (strand - 1f) * 0.5f
                    val y = 7.4f + (strand % 2) * 0.55f
                    val sag = 0.5f + strand * 0.12f
                    val x0 = lastX + ax / 0.9f * o; val z0 = lastZ + az / 0.9f * o
                    val x1 = x + ax / 0.9f * o; val z1 = z + az / 0.9f * o
                    // Two straight pieces dip in the middle, close enough to a hanging wire.
                    val mx = (x0 + x1) / 2; val mz = (z0 + z1) / 2
                    thinLine(m, x0, y, z0, mx, y - sag, mz)
                    thinLine(m, mx, y - sag, mz, x1, y, z1)
                }
            }
            lastX = x; lastZ = z
        }
    }

    private fun thinLine(m: MeshBuilder, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) {
        val dx = bx - ax; val dz = bz - az
        val l = hypot(dx, dz)
        if (l < 0.01f) return
        m.quad(ax, ay - 0.03f, az, bx, by - 0.03f, bz, bx, by + 0.03f, bz, ax, ay + 0.03f, az, WIRE)
        m.quad(bx, by - 0.03f, bz, ax, ay - 0.03f, az, ax, ay + 0.03f, az, bx, by + 0.03f, bz, WIRE)
    }

    private fun bridge(e: Int, c: Chunk) {
        val from = map.edgeStart[e]; val to = map.edgeStart[e + 1]
        val h = map.edgeWidth[e] / 2
        for (k in from until to - 1) {
            val ax = map.edgeX[k]; val az = map.edgeZ[k]; val bx = map.edgeX[k + 1]; val bz = map.edgeZ[k + 1]
            val ay = map.edgeY[k]; val by = map.edgeY[k + 1]
            val dx = bx - ax; val dz = bz - az; val l = hypot(dx, dz)
            if (l < 0.1f) continue
            val px = -dz / l * h; val pz = dx / l * h
            val mx = (ax + bx) / 2; val mz = (az + bz) / 2
            // Underside, and the deck's edges with a parapet.
            c.mesh.quad(ax - px, ay - 0.9f, az - pz, bx - px, by - 0.9f, bz - pz, bx + px, by - 0.9f, bz + pz, ax + px, ay - 0.9f, az + pz, 0xBDB8AD, Mat.of(Tex.CONCRETE))
            wall(c.mesh, ax + px, az + pz, bx + px, bz + pz, min(ay, by) - 0.9f, max(ay, by) + 1.0f, mx, mz, 0xCFCAC0, Mat.of(Tex.CONCRETE))
            wall(c.mesh, ax - px, az - pz, bx - px, bz - pz, min(ay, by) - 0.9f, max(ay, by) + 1.0f, mx, mz, 0xCFCAC0, Mat.of(Tex.CONCRETE))
            val mid = (ay + by) / 2
            if (mid > 0.5f) solid(mx, mz, atan2(dz, dx), l / 2, h, mid - 0.9f, mid + ROAD_Y)
            var s = 0f
            while (s < l) {
                val t = s / l
                val y = ay + (by - ay) * t
                if (y > 2.5f) {
                    val x = ax + dx * t; val z = az + dz * t
                    c.mesh.cylinder(x, 0f, z, 0.8f, y - 0.9f, 0.8f, 10, 0xC8C3B8, Mat.of(Tex.CONCRETE))
                    solid(x, z, 0f, 0.8f, 0.8f, 0f, y - 0.9f)
                }
                s += 24f
            }
        }
    }

    /**
     * A signal for each approach, on the driver's left as traffic keeps left in Bangladesh. The
     * lamps' material encodes group and axis, so the shader lights them in step with Traffic.
     */
    private fun signals(n: Int) {
        val x = map.nodeX[n]; val z = map.nodeZ[n]
        val group = n and 1
        val r = junctionRadius[n]
        val m = meshChunk(x, z, r + 12f).detail
        val colors = intArrayOf(0x3BFF83, 0xFFC531, 0xFF392F)
        for (e in 0 until map.edgeCount) {
            val a = map.edgeA[e]; val b = map.edgeB[e]
            if (a != n && b != n) continue
            val arriving = b == n
            if (arriving && map.lanesForward[e] == 0) continue
            if (!arriving && map.lanesBackward[e] == 0) continue
            val length = edgeLength(e)
            edgePoint(e, if (arriving) max(0f, length - r) else min(length, r), point)
            // The heading of a driver arriving here.
            val hx = if (arriving) point[2] else -point[2]; val hz = if (arriving) point[3] else -point[3]
            val axis = if (abs(hx) > abs(hz)) 1 else 0
            val leftX = hz; val leftZ = -hx
            val side = map.edgeWidth[e] / 2 + 0.7f
            val px = point[0] + leftX * side; val pz = point[1] + leftZ * side
            m.box(px - 0.1f, 0f, pz - 0.1f, px + 0.1f, 4.6f, pz + 0.1f, 0x6E767C, Mat.GLOSSY, bottom = false)
            solid(px, pz, 0f, 0.12f, 0.12f, 0f, 4.6f, false)
            m.box(px - 0.25f, 3.3f, pz - 0.25f, px + 0.25f, 4.6f, pz + 0.25f, 0x17191B)
            // A quad facing the arriving driver: red on top, green at the bottom.
            val fx = px - hx * 0.27f; val fz = pz - hz * 0.27f
            val sx = leftX * 0.15f; val sz = leftZ * 0.15f
            for (light in 0..2) {
                val y = 3.55f + (2 - light) * 0.36f
                m.quad(fx - sx, y - 0.14f, fz - sz, fx + sx, y - 0.14f, fz + sz, fx + sx, y + 0.14f, fz + sz, fx - sx, y + 0.14f, fz - sz,
                    colors[light], Mat.SIGNAL + group * 6 + axis * 3 + light)
                m.quad(fx + sx, y - 0.14f, fz + sz, fx - sx, y - 0.14f, fz - sz, fx - sx, y + 0.14f, fz - sz, fx + sx, y + 0.14f, fz + sz,
                    colors[light], Mat.SIGNAL + group * 6 + axis * 3 + light)
            }
            // A zebra crossing across this road, just outside the junction.
            edgePoint(e, if (arriving) max(0f, length - r + 0.6f) else min(length, r - 0.6f), point)
            val half = map.edgeWidth[e] / 2 - 0.3f
            var o = -half + 0.3f
            val y = edgeHeight(e, 0f) + ROAD_Y + 0.025f
            while (o < half) {
                val cx = point[0] + point[3] * o; val cz = point[1] - point[2] * o
                val ux = point[2] * 1.4f; val uz = point[3] * 1.4f
                val vx = point[3] * 0.25f; val vz = -point[2] * 0.25f
                upQuad(m, cx - ux - vx, cz - uz - vz, cx + ux - vx, cz + uz - vz, cx + ux + vx, cz + uz + vz, cx - ux + vx, cz - uz + vz, y, 0xE8E8E2, Mat.PLAIN)
                o += 1.1f
            }
        }
    }

    // ---- Buildings ----------------------------------------------------------------------------

    private fun buildBuilding(i: Int) {
        val from = map.buildingStart[i]; val to = map.buildingStart[i + 1]
        val n = to - from
        if (n < 3) return
        val kind = map.buildingKind[i]
        val floors = map.floors[i]
        val tintRoll = map.tint[i]
        val cx = map.boxX[i]; val cz = map.boxZ[i]
        val chunk = meshChunk(cx, cz, max(map.boxHalfX[i], map.boxHalfZ[i]) * 1.5f)
        val m = chunk.mesh
        val top = if (kind == MapData.SHED) 3.2f else floors * map.floorHeight[i]

        val facade: Int; val color: Int
        when (kind) {
            MapData.APARTMENT -> {
                color = apartmentTints[tintRoll % apartmentTints.size]
                facade = Mat.of(if (tintRoll % 7 == 3) Tex.BRICK_FLATS else Tex.PLASTER_FLATS)
            }
            MapData.HOUSE -> { color = houseTints[tintRoll % houseTints.size]; facade = Mat.of(if (tintRoll % 3 == 0) Tex.BRICK_FLATS else Tex.PLASTER_FLATS) }
            MapData.SHED -> { color = 0xB8A890; facade = Mat.of(Tex.BRICK) }
            MapData.COMMERCIAL -> { color = towerTints[tintRoll % towerTints.size]; facade = Mat.of(Tex.OFFICE) }
            MapData.TOWER -> { color = towerTints[tintRoll % towerTints.size]; facade = Mat.of(Tex.CURTAIN_WALL) }
            MapData.MALL -> { color = 0xCFE0EA; facade = Mat.of(Tex.CURTAIN_WALL) }
            MapData.EDUCATION -> { color = if (tintRoll % 2 == 0) 0xE9D2C0 else 0xF1EDE4; facade = Mat.of(if (tintRoll % 2 == 0) Tex.BRICK_FLATS else Tex.OFFICE) }
            MapData.HOSPITAL -> { color = 0xF2F2EE; facade = Mat.of(Tex.OFFICE) }
            MapData.MOSQUE -> { color = 0xF1EBDD; facade = Mat.of(Tex.PLASTER_FLATS) }
            MapData.INDUSTRIAL -> { color = 0xC4C6C2; facade = Mat.of(Tex.METAL) }
            else -> { color = 0xB9B5AC; facade = Mat.of(Tex.CONCRETE) }
        }
        val shopHeight = if (map.shops[i] && floors >= 2 && kind != MapData.SHED) 3.3f else 0f
        for (k in 0 until n) {
            val a = from + k; val b = from + (k + 1) % n
            val ax = map.buildingX[a]; val az = map.buildingZ[a]; val bx = map.buildingX[b]; val bz = map.buildingZ[b]
            if (shopHeight > 0f) {
                wall(m, ax, az, bx, bz, 0f, shopHeight, cx, cz, 0xFFFFFF, Mat.of(Tex.SHOPS))
                wall(m, ax, az, bx, bz, shopHeight, top, cx, cz, color, facade)
            } else {
                wall(m, ax, az, bx, bz, 0f, top, cx, cz, color, facade)
            }
        }
        val shed = kind == MapData.SHED
        flat(m, map.buildingX, map.buildingZ, from, to, top, if (shed) (if (tintRoll % 3 == 0) 0x5E7C9A else 0x9A6B4F) else 0xD9D6CF,
            Mat.of(if (shed) Tex.METAL else Tex.ROOF))
        solid(cx, cz, map.boxAngle[i], map.boxHalfX[i], map.boxHalfZ[i], 0f, top + 0.3f)

        if (shopHeight > 0f) signboards(from, n, cx, cz)
        when (kind) {
            MapData.APARTMENT, MapData.HOUSE, MapData.COMMERCIAL, MapData.EDUCATION, MapData.HOSPITAL -> rooftop(i, top, chunk)
            MapData.MOSQUE -> mosque(i, top, chunk)
        }
    }

    /** A long shop signboard over the ground floor on each of the two longest walls. */
    private fun signboards(from: Int, n: Int, cx: Float, cz: Float) {
        val m = meshChunk(cx, cz, 30f).detail
        var first = -1; var second = -1; var firstLength = 0f; var secondLength = 0f
        for (k in 0 until n) {
            val a = from + k; val b = from + (k + 1) % n
            val length = hypot(map.buildingX[b] - map.buildingX[a], map.buildingZ[b] - map.buildingZ[a])
            if (length > firstLength) { second = first; secondLength = firstLength; first = k; firstLength = length }
            else if (length > secondLength) { second = k; secondLength = length }
        }
        for (k in intArrayOf(first, second)) {
            if (k < 0) continue
            val a = from + k; val b = from + (k + 1) % n
            val ax = map.buildingX[a]; val az = map.buildingZ[a]; val bx = map.buildingX[b]; val bz = map.buildingZ[b]
            val length = hypot(bx - ax, bz - az)
            if (length < 4f) continue
            var nx = -(bz - az) / length; var nz = (bx - ax) / length
            if (nx * ((ax + bx) / 2 - cx) + nz * ((az + bz) / 2 - cz) < 0f) { nx = -nx; nz = -nz }
            val x0 = ax + (bx - ax) * 0.06f + nx * 0.12f; val z0 = az + (bz - az) * 0.06f + nz * 0.12f
            val x1 = ax + (bx - ax) * 0.94f + nx * 0.12f; val z1 = az + (bz - az) * 0.94f + nz * 0.12f
            val color = signColors[random.nextInt(signColors.size)]
            val y0 = 2.6f + random.nextFloat() * 0.15f
            wall(m, x0, z0, x1, z1, y0, y0 + 0.75f, x0 - nx, z0 - nz, color, Mat.PLAIN)
            // A pale band where the lettering would be.
            val lx0 = x0 + (x1 - x0) * 0.1f + nx * 0.01f; val lz0 = z0 + (z1 - z0) * 0.1f + nz * 0.01f
            val lx1 = x0 + (x1 - x0) * 0.9f + nx * 0.01f; val lz1 = z0 + (z1 - z0) * 0.9f + nz * 0.01f
            wall(m, lx0, lz0, lx1, lz1, y0 + 0.28f, y0 + 0.47f, lx0 - nx, lz0 - nz, 0xF4F2EA, Mat.PLAIN)
        }
    }

    /** The Dhaka roof: water tanks, a stair room, and sometimes a dish or potted plants. */
    private fun rooftop(i: Int, top: Float, chunk: Chunk) {
        val m = chunk.detail
        val c = cos(map.boxAngle[i]); val s = sin(map.boxAngle[i])
        val hx = map.boxHalfX[i]; val hz = map.boxHalfZ[i]
        if (hx < 2.5f || hz < 2.5f) return
        val cx = map.boxX[i]; val cz = map.boxZ[i]
        fun atX(u: Float, v: Float) = cx + u * c - v * s
        fun atZ(u: Float, v: Float) = cz + u * s + v * c
        if (map.floors[i] >= 4) {
            val x = atX(hx * 0.25f, -hz * 0.2f); val z = atZ(hx * 0.25f, -hz * 0.2f)
            m.box(x - 1.3f, top, z - 1.3f, x + 1.3f, top + 2.6f, z + 1.3f, 0xD5D1C8, Mat.of(Tex.CONCRETE), bottom = false)
            solid(x, z, 0f, 1.3f, 1.3f, top, top + 2.6f)
        }
        val tanks = if (hx * hz > 80f && random.nextBoolean()) 2 else 1
        for (k in 0 until tanks) {
            val x = atX(-hx * 0.55f + k * 1.6f, hz * 0.45f); val z = atZ(-hx * 0.55f + k * 1.6f, hz * 0.45f)
            val color = tankColors[random.nextInt(tankColors.size)]
            val stand = if (random.nextFloat() < 0.3f) 0.6f else 0f
            if (stand > 0f) m.box(x - 0.6f, top, z - 0.6f, x + 0.6f, top + stand, z + 0.6f, 0x8A8E90, bottom = false)
            m.cylinder(x, top + stand, z, 0.62f, top + stand + 1.4f, 0.6f, 6, color, Mat.GLOSSY)
        }
        if (random.nextFloat() < 0.18f) {
            val x = atX(-hx * 0.3f, -hz * 0.5f); val z = atZ(-hx * 0.3f, -hz * 0.5f)
            m.ellipsoid(x, top + 0.9f, z, 0.45f, 0.45f, 0.12f, 6, 3, 0xD8D8D2)
            m.box(x - 0.04f, top, z - 0.04f, x + 0.04f, top + 0.9f, z + 0.04f, 0x707070)
        }
        if (random.nextFloat() < 0.12f) {
            for (k in 0..2) {
                val x = atX(hx * 0.5f - k * 1.2f, hz * 0.55f); val z = atZ(hx * 0.5f - k * 1.2f, hz * 0.55f)
                m.box(x - 0.3f, top, z - 0.3f, x + 0.3f, top + 0.4f, z + 0.3f, 0xA0583A)
                m.ellipsoid(x, top + 0.75f, z, 0.45f, 0.4f, 0.45f, 5, 3, leafColors[k % leafColors.size], Mat.of(Tex.LEAVES))
            }
        }
    }

    private fun mosque(i: Int, top: Float, chunk: Chunk) {
        val cx = map.boxX[i]; val cz = map.boxZ[i]
        val r = min(map.boxHalfX[i], map.boxHalfZ[i]) * 0.55f
        if (r < 1.2f) return
        chunk.mesh.cylinder(cx, top, cz, r * 1.02f, top + r * 0.35f, r * 1.02f, 16, 0xEFE8D8, Mat.of(Tex.CONCRETE))
        chunk.mesh.ellipsoid(cx, top + r * 0.35f, cz, r, r * 0.95f, r, 14, 7, 0xE8E2D2, Mat.GLOSSY)
        chunk.mesh.cylinder(cx, top + r * 1.25f, cz, 0.08f, top + r * 1.25f + 1.2f, 0.03f, 5, 0xC9A040, Mat.GLOSSY)
        solid(cx, cz, 0f, r, r, top, top + r * 1.3f)
        // A minaret at one corner of the plot.
        val c = cos(map.boxAngle[i]); val s = sin(map.boxAngle[i])
        val u = map.boxHalfX[i] - 1.2f; val v = map.boxHalfZ[i] - 1.2f
        val mx = cx + u * c - v * s; val mz = cz + u * s + v * c
        val height = top + 10f + min(10f, r * 3f)
        chunk.mesh.cylinder(mx, 0f, mz, 0.9f, height, 0.75f, 10, 0xF1EBDD, Mat.of(Tex.CONCRETE))
        chunk.mesh.cylinder(mx, height - 2.4f, mz, 1.15f, height - 2.1f, 1.15f, 10, 0xE4DCCB, Mat.of(Tex.CONCRETE))
        chunk.mesh.ellipsoid(mx, height, mz, 0.8f, 1.1f, 0.8f, 8, 4, 0xE8E2D2, Mat.GLOSSY)
        solid(mx, mz, 0f, 0.95f, 0.95f, 0f, height + 1f)
    }

    private fun buildLandmark(i: Int) {
        val b = map.landmarkBuilding[i]
        if (b < 0 || b >= map.buildingCount) return
        val cx = map.boxX[b]; val cz = map.boxZ[b]
        val c = cos(map.boxAngle[b]); val s = sin(map.boxAngle[b])
        val hx = map.boxHalfX[b]; val hz = map.boxHalfZ[b]
        val top = map.floors[b] * map.floorHeight[b]
        val chunk = meshChunk(cx, cz, max(hx, hz) + 10f)
        when (map.landmarkKind[i]) {
            MapData.SEVEN_DOMES -> {
                // Saat Gambuj: three domes over the prayer hall and a domed tower at each corner.
                val long = hx >= hz
                val span = if (long) hx else hz
                val r = min(if (long) hz else hx, span / 2.2f) * 0.75f
                for (k in -1..1) {
                    val u = if (long) k * span * 0.62f else 0f
                    val v = if (long) 0f else k * span * 0.62f
                    val x = cx + u * c - v * s; val z = cz + u * s + v * c
                    chunk.mesh.cylinder(x, top, z, r * 1.02f, top + 0.5f, r * 1.02f, 14, 0xEEE6D6, Mat.of(Tex.CONCRETE))
                    chunk.mesh.ellipsoid(x, top + 0.5f, z, r, r * 1.05f, r, 14, 7, 0xF2ECDF, Mat.GLOSSY)
                    chunk.mesh.cylinder(x, top + 0.5f + r * 1.05f, z, 0.07f, top + 1.6f + r * 1.05f, 0.02f, 5, 0xC9A040, Mat.GLOSSY)
                }
                for (su in intArrayOf(-1, 1)) for (sv in intArrayOf(-1, 1)) {
                    val u = su * hx; val v = sv * hz
                    val x = cx + u * c - v * s; val z = cz + u * s + v * c
                    chunk.mesh.cylinder(x, 0f, z, 1.1f, top + 2.6f, 1f, 12, 0xEDE5D4, Mat.of(Tex.CONCRETE))
                    chunk.mesh.ellipsoid(x, top + 2.6f, z, 1.05f, 1.2f, 1.05f, 10, 5, 0xF2ECDF, Mat.GLOSSY)
                    solid(x, z, 0f, 1.1f, 1.1f, 0f, top + 3.8f)
                }
            }
            MapData.MALL_SIGN -> {
                // A big red sign along the mall's roof edge.
                val v = -hz + 0.5f
                val x = cx - v * s; val z = cz + v * c
                val w = min(hx * 0.6f, 40f)
                val x0 = x - w * c; val z0 = z - w * s; val x1 = x + w * c; val z1 = z + w * s
                chunk.mesh.quad(x0, top, z0, x1, top, z1, x1, top + 6f, z1, x0, top + 6f, z0, 0xD0202A, Mat.EMISSIVE)
                chunk.mesh.quad(x1, top, z1, x0, top, z0, x0, top + 6f, z0, x1, top + 6f, z1, 0xD0202A, Mat.EMISSIVE)
            }
            MapData.HOTEL -> {
                chunk.mesh.box(cx - 3f, top, cz - 3f, cx + 3f, top + 4f, cz + 3f, 0xDCE6EC, Mat.of(Tex.CURTAIN_WALL))
                chunk.mesh.cylinder(cx, top + 4f, cz, 0.1f, top + 14f, 0.04f, 5, 0xB7BDC0)
                chunk.mesh.centered(cx, top + 14f, cz, 0.3f, 0.3f, 0.3f, 0xFF4038, Mat.EMISSIVE)
                solid(cx, cz, 0f, 3f, 3f, top, top + 4f)
            }
            else -> Unit
        }
    }

    // ---- Docks --------------------------------------------------------------------------------

    /** A charging dock on a mast at a junction corner: a landing deck above head height, clear of traffic. */
    private fun buildDock(x: Float, z: Float) {
        val c = meshChunk(x, z, 3f)
        val m = c.mesh; val d = c.detail
        val deck = DOCK_TOP - 0.32f
        m.cylinder(x, 0.9f, z, 0.2f, deck - 0.5f, 0.16f, 6, 0x8A949A, Mat.GLOSSY, cap = false)
        m.cylinder(x, deck, z, DOCK_RADIUS, DOCK_TOP, DOCK_RADIUS, 16, 0x2B3136, Mat.GLOSSY, capMaterial = Mat.of(Tex.DECK))
        d.cylinder(x, 0f, z, 0.32f, 0.9f, 0.26f, 8, 0xE4E1D9, Mat.of(Tex.CONCRETE))
        d.cylinder(x, deck - 0.5f, z, 0.16f, deck, DOCK_RADIUS * 0.75f, 10, 0x5F686E, Mat.GLOSSY, cap = false)
        val mark = DOCK_TOP + 0.012f
        d.box(x - 0.62f, DOCK_TOP, z - 0.72f, x - 0.38f, mark, z + 0.72f, 0xD9A928, bottom = false)
        d.box(x + 0.38f, DOCK_TOP, z - 0.72f, x + 0.62f, mark, z + 0.72f, 0xD9A928, bottom = false)
        d.box(x - 0.38f, DOCK_TOP, z - 0.12f, x + 0.38f, mark, z + 0.12f, 0xD9A928, bottom = false)
        for (k in 0 until 8) {
            val a = k * PI_F / 4 + PI_F / 8
            d.centered(x + cos(a) * (DOCK_RADIUS - 0.08f), DOCK_TOP - 0.12f, z + sin(a) * (DOCK_RADIUS - 0.08f), 0.12f, 0.1f, 0.12f, 0x58E6FF, Mat.EMISSIVE)
        }
        solid(x, z, 0f, 0.3f, 0.3f, 0f, deck, false)
        solid(x, z, 0f, DOCK_RADIUS, DOCK_RADIUS, deck, DOCK_TOP, false)
    }

    /** Index of the dock nearest to a point on the map. */
    fun nearestDock(x: Float, z: Float): Int {
        var best = 0; var bestDistance = Float.MAX_VALUE
        for (d in 0 until dockCount) {
            val distance = hypot(docks[d * 3] - x, docks[d * 3 + 2] - z)
            if (distance < bestDistance) { bestDistance = distance; best = d }
        }
        return best
    }

    /** Highest obstacle top near a straight line, for picking a safe cruising height. */
    fun highestAlong(x0: Float, z0: Float, x1: Float, z1: Float): Float {
        val length = hypot(x1 - x0, z1 - z0)
        val samples = max(1, (length / 6f).toInt())
        var highest = 0f
        for (k in 0..samples) {
            val t = k.toFloat() / samples
            val px = x0 + (x1 - x0) * t; val pz = z0 + (z1 - z0) * t
            val c = chunkAt(px, pz)
            val b = c.boxes
            var i = 0
            while (i < c.count * 8) {
                val dx = px - b[i]; val dz = pz - b[i + 1]
                val u = dx * b[i + 2] + dz * b[i + 3]; val v = -dx * b[i + 3] + dz * b[i + 2]
                if (abs(u) < b[i + 4] + 8f && abs(v) < b[i + 5] + 8f) highest = max(highest, b[i + 7])
                i += 8
            }
        }
        return highest
    }

    // ---- Beyond the map -----------------------------------------------------------------------

    private fun buildTerrain() {
        val edge = 3200f
        terrain.quad(-edge, 0f, edge, edge, 0f, edge, edge, 0f, -edge, -edge, 0f, -edge, 0xFFFFFF, Mat.GROUND)
        // The rest of Dhaka goes on past the edge of the map: blocks of flats into the haze.
        repeat(900) {
            val a = between(0f, 2 * PI_F)
            val distance = between(HALF * 1.08f, HALF * 1.9f)
            val x = cos(a) * distance; val z = sin(a) * distance
            val w = between(10f, 22f); val d = between(10f, 22f)
            val h = (3 + random.nextInt(8)) * 3.2f
            val tint = apartmentTints[random.nextInt(apartmentTints.size)]
            if (abs(x) > HALF + 15f || abs(z) > HALF + 15f) {
                terrain.box(x - w / 2, 0f, z - d / 2, x + w / 2, h, z + d / 2, tint, Mat.of(Tex.PLASTER_FLATS), 0xD5D2CA, Mat.of(Tex.ROOF), false)
            }
        }
    }

    // ---- Queries ------------------------------------------------------------------------------

    /** Highest surface under a point that is no higher than [below]. */
    fun support(x: Float, z: Float, below: Float, radius: Float): Float {
        var best = 0f
        for (j in cell(z - radius)..cell(z + radius)) for (i in cell(x - radius)..cell(x + radius)) {
            val c = chunks[j * CELLS + i]; val b = c.boxes
            var k = 0
            while (k < c.count * 8) {
                val top = b[k + 7]
                if (top > best && top <= below) {
                    val dx = x - b[k]; val dz = z - b[k + 1]
                    val u = dx * b[k + 2] + dz * b[k + 3]; val v = -dx * b[k + 3] + dz * b[k + 2]
                    if (abs(u) < b[k + 4] + radius && abs(v) < b[k + 5] + radius) best = top
                }
                k += 8
            }
        }
        return best
    }

    /** Distance along a normalized ray to the first solid, or [range]. Writes the surface normal to [normal]. */
    fun raycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, range: Float, normal: FloatArray? = null, opaqueOnly: Boolean = false): Float {
        var best = range
        val ex = ox + dx * range; val ez = oz + dz * range
        for (j in cell(min(oz, ez))..cell(max(oz, ez))) for (i in cell(min(ox, ex))..cell(max(ox, ex))) {
            val c = chunks[j * CELLS + i]
            if (min(oy, oy + dy * range) > c.top) continue
            val b = c.boxes
            var k = 0
            while (k < c.count * 8) {
                if (opaqueOnly && !c.blocksView[k / 8]) { k += 8; continue }
                val cs = b[k + 2]; val sn = b[k + 3]
                val rx = ox - b[k]; val rz = oz - b[k + 1]
                // In the box's own frame it is an ordinary axis-aligned box.
                val lu = rx * cs + rz * sn; val lv = -rx * sn + rz * cs
                val du = dx * cs + dz * sn; val dv = -dx * sn + dz * cs
                var near = 0f; var far = best; var hitAxis = -1; var hit = true
                for (axis in 0..2) {
                    val o = if (axis == 0) lu else if (axis == 1) oy else lv
                    val d = if (axis == 0) du else if (axis == 1) dy else dv
                    val low = if (axis == 0) -b[k + 4] else if (axis == 1) b[k + 6] else -b[k + 5]
                    val high = if (axis == 0) b[k + 4] else if (axis == 1) b[k + 7] else b[k + 5]
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
                        val nu = if (hitAxis == 0) -sign(du) else 0f
                        val nv = if (hitAxis == 2) -sign(dv) else 0f
                        normal[0] = nu * cs - nv * sn
                        normal[1] = if (hitAxis == 1) -sign(dy) else 0f
                        normal[2] = nu * sn + nv * cs
                    }
                }
                k += 8
            }
        }
        return best
    }
}
