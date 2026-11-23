package com.skycop.sim.core

import kotlin.math.*
import kotlin.random.Random

/** Shared by the traffic model and, through the same constants, the signal shader. */
object Signals {
    const val CYCLE = 26f
    const val GREEN = 9f
    const val YELLOW = 11.5f

    /** 0 green, 1 yellow, 2 red. Axis 0 is traffic heading mostly north or south. */
    fun phase(time: Float, axis: Int, group: Int): Int {
        val t = (time + group * 6.5f + axis * 13f).mod(CYCLE)
        return if (t < GREEN) 0 else if (t < YELLOW) 1 else 2
    }
}

/** A growable list of ints, kept sorted by the caller. */
class IntList {
    var data = IntArray(8)
        private set
    var size = 0
        private set

    operator fun get(i: Int) = data[i]

    fun insert(at: Int, value: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        for (i in size downTo at + 1) data[i] = data[i - 1]
        data[at] = value
        size++
    }

    fun removeAt(at: Int) {
        for (i in at until size - 1) data[i] = data[i + 1]
        size--
    }

    fun indexOf(value: Int): Int {
        for (i in 0 until size) if (data[i] == value) return i
        return -1
    }
}

/**
 * Dhaka traffic on the real road network. Vehicles keep left, follow the car ahead with the
 * Intelligent Driver Model, pick their own turns, and wait their turn at junctions: one approach
 * at a time, signals where there are signals, and never into a junction whose exit is full. Busy
 * main roads, slow rickshaws and buses stopping for passengers make jams form and clear on their
 * own. Vehicles that reach the edge of the map come back in somewhere else along the edge.
 */
class Traffic(private val city: City, seed: Int = 71) {
    companion object {
        const val CAR = 0; const val CNG = 1; const val RICKSHAW = 2; const val BUS = 3; const val BIKE = 4; const val MICROBUS = 5
        const val MAX_VEHICLES = 2200
        private val LENGTH = floatArrayOf(4.4f, 2.7f, 2.0f, 10.5f, 2.0f, 4.7f)
        private val WIDTH = floatArrayOf(1.75f, 1.35f, 1.1f, 2.5f, 0.75f, 1.8f)
        private val HEIGHT = floatArrayOf(1.45f, 1.75f, 1.95f, 3.2f, 1.4f, 1.95f)
        private val TOP_SPEED = floatArrayOf(11f, 8.5f, 3.6f, 10f, 12f, 10.5f)
        private val CLASS_SPEED = floatArrayOf(16f, 14f, 12f, 11f, 9.5f, 8f, 6.5f, 5f, 5f)
        private val SPACING = floatArrayOf(16f, 16f, 16f, 20f, 26f, 40f, 50f, 70f, 80f)
        private val CAR_COLORS = intArrayOf(0xF2F2EE, 0xF2F2EE, 0xF2F2EE, 0xB9BCC0, 0x1E2227, 0x7A1E22, 0x2C4C78, 0xD7D2C4)
        private val CNG_COLORS = intArrayOf(0x1F8A3B, 0x1F8A3B, 0x1F8A3B, 0x23753A, 0x6B7176)
        private val RICKSHAW_COLORS = intArrayOf(0xD02A2A, 0x2C55B8, 0xE8B21E, 0x2E9C4A, 0xD8418A, 0x7A3FB0)
        private val BUS_COLORS = intArrayOf(0xC8302A, 0x2E5DA8, 0x2F8F4E, 0xE0A020, 0xE6E3DA, 0x8A2BB0, 0xD8572A)
        private val BIKE_COLORS = intArrayOf(0xC02020, 0x1E2227, 0x2050B0, 0xE0E0E0)
        private val MICROBUS_COLORS = intArrayOf(0xF2F2EE, 0xF2F2EE, 0xB9BCC0, 0x9FA4A8)
        private const val STEP = 1f / 60f
    }

    private val map = city.map

    // ---- Lanes --------------------------------------------------------------------------------
    private val laneEdge: IntArray
    private val laneEndNode: IntArray
    private val laneStartNode: IntArray
    private val laneClass: IntArray
    private val laneLength: FloatArray
    private val trimStart: FloatArray
    private val trimEnd: FloatArray
    private val laneKey: IntArray
    private val laneForward: BooleanArray
    private val pointStart: IntArray
    private val px: FloatArray; private val pz: FloatArray; private val py: FloatArray; private val pcum: FloatArray
    private val outStart: IntArray
    private val outLanes: IntArray
    private val portalStarts: IntArray
    val laneCount: Int

    // ---- Junctions ----------------------------------------------------------------------------
    private val holder: IntArray
    private val inside: IntArray
    private val requestKey: IntArray
    private val requestSince: FloatArray
    private val controlled: BooleanArray

    // ---- Vehicles -----------------------------------------------------------------------------
    val count: Int
    val type: IntArray
    val color: IntArray
    val x: FloatArray; val y: FloatArray; val z: FloatArray; val yaw: FloatArray
    val speed: FloatArray
    private val lane: IntArray
    private val s: FloatArray
    private val previousS: FloatArray
    private val nextLane: IntArray
    private val connector: FloatArray
    private val connectorLength: FloatArray
    private val admitted: BooleanArray
    private val segment: IntArray
    private val desired: FloatArray
    private val pace: FloatArray
    private val paceTarget: FloatArray
    private val paceUntil: FloatArray
    private val fromX: FloatArray; private val fromY: FloatArray; private val fromZ: FloatArray; private val fromYaw: FloatArray
    private val toX: FloatArray; private val toY: FloatArray; private val toZ: FloatArray; private val toYaw: FloatArray
    private val laneCars: Array<IntList>

    var time = 0f
        private set
    private var accumulator = 0f
    private val random = Random(seed)
    private val tmp = FloatArray(4)

    init {
        // One lane per direction and lane index of every edge that carries traffic.
        val edgeOf = ArrayList<Int>(); val dirOf = ArrayList<Int>(); val indexOf = ArrayList<Int>()
        for (e in 0 until map.edgeCount) {
            if (!map.edgeTraffic[e]) continue
            for (k in 0 until map.lanesForward[e]) { edgeOf.add(e); dirOf.add(1); indexOf.add(k) }
            for (k in 0 until map.lanesBackward[e]) { edgeOf.add(e); dirOf.add(-1); indexOf.add(k) }
        }
        laneCount = edgeOf.size
        laneEdge = IntArray(laneCount) { edgeOf[it] }
        laneStartNode = IntArray(laneCount) { if (dirOf[it] > 0) map.edgeA[edgeOf[it]] else map.edgeB[edgeOf[it]] }
        laneEndNode = IntArray(laneCount) { if (dirOf[it] > 0) map.edgeB[edgeOf[it]] else map.edgeA[edgeOf[it]] }
        laneClass = IntArray(laneCount) { map.edgeClass[edgeOf[it]] }
        // Each lane takes its own turn at a junction, so two lanes never merge into one at once.
        laneKey = IntArray(laneCount) { it }
        laneForward = BooleanArray(laneCount) { dirOf[it] > 0 }
        laneLength = FloatArray(laneCount)
        trimStart = FloatArray(laneCount) { city.junctionRadius[laneStartNode[it]] }
        trimEnd = FloatArray(laneCount) { city.junctionRadius[laneEndNode[it]] }
        pointStart = IntArray(laneCount + 1)
        val xs = ArrayList<Float>(); val zs = ArrayList<Float>(); val ys = ArrayList<Float>(); val cs = ArrayList<Float>()
        for (l in 0 until laneCount) {
            val e = laneEdge[l]; val dir = dirOf[l]
            val from = map.edgeStart[e]; val to = map.edgeStart[e + 1]
            val n = to - from
            val total = map.lanesForward[e] + map.lanesBackward[e]
            val width = map.edgeWidth[e] / total
            // Keep left: lanes sit to the left of the direction of travel.
            val offset = if (map.lanesBackward[e] > 0) (indexOf[l] + 0.5f) * width
            else (indexOf[l] + 0.5f - map.lanesForward[e] / 2f) * width
            var cumulative = 0f
            for (j in 0 until n) {
                val k = if (dir > 0) from + j else to - 1 - j
                val prev = if (dir > 0) max(from, k - 1) else min(to - 1, k + 1)
                val next = if (dir > 0) min(to - 1, k + 1) else max(from, k - 1)
                // Average the left normals of the segments either side, with a capped miter.
                var nx = 0f; var nz = 0f
                if (prev != k) { val dx = map.edgeX[k] - map.edgeX[prev]; val dz = map.edgeZ[k] - map.edgeZ[prev]; val d = hypot(dx, dz); if (d > 0f) { nx += dz / d; nz -= dx / d } }
                if (next != k) { val dx = map.edgeX[next] - map.edgeX[k]; val dz = map.edgeZ[next] - map.edgeZ[k]; val d = hypot(dx, dz); if (d > 0f) { nx += dz / d; nz -= dx / d } }
                val nl = hypot(nx, nz)
                var mx = 0f; var mz = 0f
                if (nl > 0f) {
                    nx /= nl; nz /= nl
                    var miter = 1f
                    if (prev != k) {
                        val dx = map.edgeX[k] - map.edgeX[prev]; val dz = map.edgeZ[k] - map.edgeZ[prev]; val d = hypot(dx, dz)
                        if (d > 0f) miter = 1f / max(0.6f, nx * dz / d - nz * dx / d)
                    }
                    mx = nx * offset * miter; mz = nz * offset * miter
                }
                val lx = map.edgeX[k] + mx; val lz = map.edgeZ[k] + mz
                if (j > 0) cumulative += hypot(lx - xs.last(), lz - zs.last())
                xs.add(lx); zs.add(lz); ys.add(map.edgeY[k]); cs.add(cumulative)
            }
            laneLength[l] = cumulative
            pointStart[l + 1] = xs.size
        }
        px = xs.toFloatArray(); pz = zs.toFloatArray(); py = ys.toFloatArray(); pcum = cs.toFloatArray()
        separate()

        // Where each lane can go next: any lane that starts at its end node, except straight back
        // along the same road unless the road ends there.
        val startsAt = HashMap<Int, ArrayList<Int>>()
        for (l in 0 until laneCount) startsAt.getOrPut(laneStartNode[l]) { ArrayList() }.add(l)
        val portals = ArrayList<Int>()
        for (l in 0 until laneCount) if (map.nodeFlags[laneStartNode[l]] and 2 != 0) portals.add(l)
        portalStarts = portals.toIntArray()
        outStart = IntArray(laneCount + 1)
        val outs = ArrayList<Int>()
        for (l in 0 until laneCount) {
            val candidates = startsAt[laneEndNode[l]] ?: arrayListOf()
            var added = 0
            for (c in candidates) if (laneEdge[c] != laneEdge[l]) { outs.add(c); added++ }
            if (added == 0) for (c in candidates) { outs.add(c); added++ }
            outStart[l + 1] = outs.size
        }
        outLanes = outs.toIntArray()

        holder = IntArray(map.nodeCount) { -1 }
        inside = IntArray(map.nodeCount)
        requestKey = IntArray(map.nodeCount) { -1 }
        requestSince = FloatArray(map.nodeCount)
        // Any node where more than one lane comes in needs turns taken, even a plain merge.
        val incoming = IntArray(map.nodeCount)
        for (l in 0 until laneCount) incoming[laneEndNode[l]]++
        controlled = BooleanArray(map.nodeCount) { incoming[it] >= 2 && map.nodeFlags[it] and 2 == 0 }

        // How many vehicles each lane starts with: dense on the main roads, light in the lanes.
        val wanted = IntArray(laneCount)
        var total = 0
        for (l in 0 until laneCount) {
            val room = laneLength[l] - trimStart[l] - trimEnd[l] - 6f
            if (room <= 0f) continue
            wanted[l] = min((room / SPACING[laneClass[l]]).toInt(), (room / 12f).toInt())
            total += wanted[l]
        }
        val scale = if (total > MAX_VEHICLES) MAX_VEHICLES.toFloat() / total else 1f
        var n = 0
        for (l in 0 until laneCount) { wanted[l] = (wanted[l] * scale).toInt(); n += wanted[l] }
        count = n

        type = IntArray(count); color = IntArray(count)
        x = FloatArray(count); y = FloatArray(count); z = FloatArray(count); yaw = FloatArray(count); speed = FloatArray(count)
        lane = IntArray(count); s = FloatArray(count); previousS = FloatArray(count)
        nextLane = IntArray(count) { -1 }; connector = FloatArray(count * 6); connectorLength = FloatArray(count)
        admitted = BooleanArray(count); segment = IntArray(count)
        desired = FloatArray(count); pace = FloatArray(count) { 1f }; paceTarget = FloatArray(count) { 1f }; paceUntil = FloatArray(count)
        fromX = FloatArray(count); fromY = FloatArray(count); fromZ = FloatArray(count); fromYaw = FloatArray(count)
        toX = FloatArray(count); toY = FloatArray(count); toZ = FloatArray(count); toYaw = FloatArray(count)
        laneCars = Array(laneCount) { IntList() }

        var car = 0
        for (l in 0 until laneCount) {
            val k = wanted[l]
            if (k == 0) continue
            val room = laneLength[l] - trimStart[l] - trimEnd[l] - 6f
            val gap = room / k
            for (j in 0 until k) {
                type[car] = pickType(laneClass[l])
                color[car] = pickColor(type[car])
                lane[car] = l
                s[car] = trimStart[l] + 3f + (j + 0.5f) * gap + (random.nextFloat() - 0.5f) * max(0f, gap - LENGTH[BUS] - 2f) * 0.5f
                previousS[car] = s[car]
                desired[car] = min(TOP_SPEED[type[car]], CLASS_SPEED[laneClass[l]]) * (0.85f + random.nextFloat() * 0.25f)
                speed[car] = 0f
                laneCars[l].insert(laneCars[l].size, car)
                chooseNext(car)
                car++
            }
        }
        for (c in 0 until count) {
            locate(c)
            fromX[c] = toX[c]; fromY[c] = toY[c]; fromZ[c] = toZ[c]; fromYaw[c] = toYaw[c]
        }
        place(1f)
    }

    private fun pickType(cls: Int): Int {
        val r = random.nextFloat()
        return when {
            cls <= 3 -> if (r < 0.34f) CAR else if (r < 0.54f) CNG else if (r < 0.68f) BUS else if (r < 0.85f) BIKE else if (r < 0.95f) MICROBUS else RICKSHAW
            cls <= 5 -> if (r < 0.30f) CAR else if (r < 0.52f) CNG else if (r < 0.57f) BUS else if (r < 0.75f) BIKE else if (r < 0.83f) MICROBUS else RICKSHAW
            else -> if (r < 0.22f) CAR else if (r < 0.37f) CNG else if (r < 0.57f) BIKE else if (r < 0.62f) MICROBUS else RICKSHAW
        }
    }

    private fun pickColor(t: Int): Int {
        val palette = when (t) {
            CAR -> CAR_COLORS; CNG -> CNG_COLORS; RICKSHAW -> RICKSHAW_COLORS
            BUS -> BUS_COLORS; BIKE -> BIKE_COLORS; else -> MICROBUS_COLORS
        }
        return palette[random.nextInt(palette.size)]
    }

    fun length(car: Int) = LENGTH[type[car]]
    fun width(car: Int) = WIDTH[type[car]]
    fun height(car: Int) = HEIGHT[type[car]]

    // ---- Geometry -----------------------------------------------------------------------------

    /** Position and heading on a lane at distance [d], into tmp: x, z, dx, dz. Returns the height. */
    private fun lanePoint(l: Int, d: Float, hint: Int): Int {
        val from = pointStart[l]; val to = pointStart[l + 1]
        var k = (from + hint).coerceIn(from, to - 2)
        while (k < to - 2 && pcum[k + 1] < d) k++
        while (k > from && pcum[k] > d) k--
        val length = pcum[k + 1] - pcum[k]
        val t = if (length > 0f) ((d - pcum[k]) / length).coerceIn(0f, 1f) else 0f
        val dx = px[k + 1] - px[k]; val dz = pz[k + 1] - pz[k]
        tmp[0] = px[k] + dx * t; tmp[1] = pz[k] + dz * t
        val l2 = hypot(dx, dz)
        tmp[2] = if (l2 > 0f) dx / l2 else 0f; tmp[3] = if (l2 > 0f) dz / l2 else -1f
        lastHeight = py[k] + (py[k + 1] - py[k]) * t
        return k - from
    }

    private var lastHeight = 0f

    /**
     * Where roads meet at a shallow angle their stop points would sit on top of each other, so each
     * lane stops (and each exit begins) far enough back to be clear of the other lanes there.
     */
    private fun separate() {
        val ending = HashMap<Int, ArrayList<Int>>(); val starting = HashMap<Int, ArrayList<Int>>()
        for (l in 0 until laneCount) {
            ending.getOrPut(laneEndNode[l]) { ArrayList() }.add(l)
            starting.getOrPut(laneStartNode[l]) { ArrayList() }.add(l)
        }
        val a = FloatArray(2); val b = FloatArray(2)
        fun at(l: Int, back: Float, fromEnd: Boolean, out: FloatArray) {
            lanePoint(l, if (fromEnd) max(0f, laneLength[l] - back) else min(laneLength[l], back), 0)
            out[0] = tmp[0]; out[1] = tmp[1]
        }
        for (pass in 0..1) {
            val groups = if (pass == 0) ending else starting
            val trims = if (pass == 0) trimEnd else trimStart
            // In node order, so the result does not depend on hash order (and matches the Swift port).
            for (node in groups.keys.sorted()) {
                val lanes = groups.getValue(node)
                if (lanes.size < 2 || map.nodeFlags[node] and 2 != 0) continue
                for (l in lanes) {
                    var d = trims[l]
                    val limit = min(25f, laneLength[l] * 0.4f)
                    while (d < limit) {
                        at(l, d, pass == 0, a)
                        var clear = true
                        for (m in lanes) {
                            if (m == l || laneEdge[m] == laneEdge[l]) continue
                            at(m, max(d, trims[m]), pass == 0, b)
                            if (hypot(a[0] - b[0], a[1] - b[1]) < 2.6f) { clear = false; break }
                        }
                        if (clear) break
                        d += 1f
                    }
                    trims[l] = min(d, limit)
                }
            }
        }
    }

    /** The curve through the junction from this lane's stop point to the next lane's start. */
    private fun chooseNext(car: Int) {
        val l = lane[car]
        val from = outStart[l]; val to = outStart[l + 1]
        if (map.nodeFlags[laneEndNode[l]] and 2 != 0 || from == to) {
            nextLane[car] = -1
            connectorLength[car] = 0f
            return
        }
        lanePoint(l, laneLength[l] - trimEnd[l], segment[car])
        val ax = tmp[0]; val az = tmp[1]; val hx = tmp[2]; val hz = tmp[3]
        var totalWeight = 0f
        for (i in from until to) totalWeight += turnWeight(car, hx, hz, outLanes[i])
        var pick = random.nextFloat() * totalWeight
        var chosen = outLanes[to - 1]
        for (i in from until to) {
            pick -= turnWeight(car, hx, hz, outLanes[i])
            if (pick <= 0f) { chosen = outLanes[i]; break }
        }
        nextLane[car] = chosen
        lanePoint(chosen, trimStart[chosen], 0)
        val bx = tmp[0]; val bz = tmp[1]; val gx = tmp[2]; val gz = tmp[3]
        // Control point where the two headings meet; the midpoint if they are nearly parallel.
        var cx = (ax + bx) / 2; var cz = (az + bz) / 2
        val det = hx * (-gz) - hz * (-gx)
        if (abs(det) > 0.15f) {
            val t = ((bx - ax) * (-gz) - (bz - az) * (-gx)) / det
            val span = hypot(bx - ax, bz - az)
            if (t > 0f && t < span * 1.5f) { cx = ax + hx * t; cz = az + hz * t }
        }
        val o = car * 6
        connector[o] = ax; connector[o + 1] = az; connector[o + 2] = cx; connector[o + 3] = cz; connector[o + 4] = bx; connector[o + 5] = bz
        var length = 0f
        var lx = ax; var lz = az
        for (k in 1..6) {
            val t = k / 6f; val u = 1f - t
            val qx = u * u * ax + 2 * u * t * cx + t * t * bx; val qz = u * u * az + 2 * u * t * cz + t * t * bz
            length += hypot(qx - lx, qz - lz)
            lx = qx; lz = qz
        }
        connectorLength[car] = max(0.5f, length)
    }

    private fun turnWeight(car: Int, hx: Float, hz: Float, next: Int): Float {
        lanePoint(next, trimStart[next], 0)
        val turn = hx * tmp[2] + hz * tmp[3]
        var w = if (turn > 0.85f) 3f else if (turn > -0.3f) 1.6f else 0.15f
        w *= (9 - laneClass[next]) / 4f + 0.5f
        if (type[car] == RICKSHAW && laneClass[next] <= 2) w *= 0.05f
        val e = laneEdge[next]
        val lanesThatWay = if (laneForward[next]) map.lanesForward[e] else map.lanesBackward[e]
        return w / max(1, lanesThatWay)
    }

    /** Where the vehicle is now, into to*: on its lane, or on the curve through a junction. */
    private fun locate(car: Int) {
        val l = lane[car]
        val stop = laneLength[l] - trimEnd[l]
        val d = s[car]
        if (d <= stop || nextLane[car] < 0) {
            segment[car] = lanePoint(l, min(d, laneLength[l]), segment[car])
            toX[car] = tmp[0]; toZ[car] = tmp[1]; toY[car] = lastHeight
            toYaw[car] = atan2(tmp[2], -tmp[3])
        } else {
            val t = ((d - stop) / connectorLength[car]).coerceIn(0f, 1f); val u = 1f - t
            val o = car * 6
            val ax = connector[o]; val az = connector[o + 1]; val cx = connector[o + 2]; val cz = connector[o + 3]
            val bx = connector[o + 4]; val bz = connector[o + 5]
            toX[car] = u * u * ax + 2 * u * t * cx + t * t * bx
            toZ[car] = u * u * az + 2 * u * t * cz + t * t * bz
            val dx = 2 * u * (cx - ax) + 2 * t * (bx - cx); val dz = 2 * u * (cz - az) + 2 * t * (bz - cz)
            if (abs(dx) + abs(dz) > 1e-4f) toYaw[car] = atan2(dx, -dz)
            toY[car] = map.nodeY[laneEndNode[l]]
        }
    }

    // ---- Simulation ---------------------------------------------------------------------------

    fun update(dt: Float) {
        accumulator += dt
        while (accumulator >= STEP) {
            accumulator -= STEP
            for (c in 0 until count) { fromX[c] = toX[c]; fromY[c] = toY[c]; fromZ[c] = toZ[c]; fromYaw[c] = toYaw[c] }
            step()
            for (c in 0 until count) locate(c)
        }
        place(accumulator / STEP)
    }

    private fun mood(car: Int) {
        if (time < paceUntil[car]) return
        if (paceTarget[car] != 1f) { paceTarget[car] = 1f; return }
        val roll = random.nextFloat()
        val l = lane[car]
        val midLane = s[car] > trimStart[l] + 6f && s[car] < laneLength[l] - trimEnd[l] - 18f
        val stopper = type[car] == BUS || type[car] == CNG || type[car] == RICKSHAW
        if (roll < (if (stopper) 0.0012f else 0.0004f) && midLane) {
            // Pulls over to pick someone up: buses, CNGs and rickshaws do it all the time.
            paceTarget[car] = 0f
            paceUntil[car] = time + 4f + random.nextFloat() * (if (type[car] == BUS) 9f else 5f)
        } else if (roll < 0.004f) {
            paceTarget[car] = 0.35f + random.nextFloat() * 0.3f
            paceUntil[car] = time + 1.5f + random.nextFloat() * 3f
        }
    }

    /** 0: go. 1: red light. 2: another approach has the junction. 3: no room on the far side. */
    private fun mayEnter(car: Int, node: Int, comfortable: Boolean): Int {
        val l = lane[car]
        val key = laneKey[l]
        if (map.nodeFlags[node] and 1 != 0) {
            val axis = if (abs(tmp[2]) > abs(tmp[3])) 1 else 0
            val phase = Signals.phase(time, axis, node and 1)
            if (phase == 2 || (phase == 1 && comfortable)) return 1
        }
        // A request nobody could act on for long is dropped, so a jam elsewhere cannot lock this junction.
        if (requestKey[node] != -1 && time - requestSince[node] > 12f) requestKey[node] = -1
        if (holder[node] != -1 && holder[node] != key) return 2
        if (requestKey[node] != -1 && requestKey[node] != key && time - requestSince[node] > 3.5f) return 2
        val next = nextLane[car]
        if (next >= 0) {
            val cars = laneCars[next]
            if (cars.size > 0) {
                val last = cars[0]
                if (s[last] - LENGTH[type[last]] / 2 < trimStart[next] + LENGTH[type[car]] + 2f) return 3
            }
        }
        return 0
    }

    private fun step() {
        time += STEP
        s.copyInto(previousS)
        for (l in 0 until laneCount) {
            val cars = laneCars[l]
            var k = cars.size - 1
            while (k >= 0) {
                val car = cars[k]
                val position = previousS[car]
                val v = speed[car]
                val half = LENGTH[type[car]] / 2
                var gap = 1000f
                var closing = 0f
                if (k + 1 < cars.size) {
                    val leader = cars[k + 1]
                    gap = previousS[leader] - position - half - LENGTH[type[leader]] / 2
                    closing = v - speed[leader]
                } else {
                    val next = nextLane[car]
                    if (next >= 0 && laneCars[next].size > 0) {
                        val leader = laneCars[next][0]
                        val end = laneLength[l] - trimEnd[l] + connectorLength[car]
                        gap = (end - position) + (previousS[leader] - trimStart[next]) - half - LENGTH[type[leader]] / 2
                        closing = v - speed[leader]
                    }
                }

                mood(car)
                pace[car] += (paceTarget[car] - pace[car]) * min(1f, 0.8f * STEP)

                val node = laneEndNode[l]
                val stop = laneLength[l] - trimEnd[l]
                if (controlled[node] && !admitted[car] && nextLane[car] >= 0) {
                    val toLine = stop - 0.4f - (position + half)
                    if (toLine < v * v / 5f + 6f) {
                        lanePoint(l, stop, segment[car])
                        val comfortable = toLine >= v * v / 9f - 0.3f
                        val answer = mayEnter(car, node, comfortable)
                        if (answer == 0) {
                            admitted[car] = true
                            holder[node] = laneKey[l]
                            inside[node]++
                            if (requestKey[node] == laneKey[l]) requestKey[node] = -1
                        } else {
                            // Waiting on cross traffic: ask for a turn, so a busy road cannot hold the junction forever.
                            if (answer == 2 && requestKey[node] == -1) { requestKey[node] = laneKey[l]; requestSince[node] = time }
                            if (toLine + 0.3f < gap) { gap = max(toLine + 0.3f, 0.05f); closing = v }
                        }
                    }
                } else if (nextLane[car] < 0 && map.nodeFlags[node] and 2 == 0) {
                    // Nowhere to go: stop at the end of the lane.
                    val toEnd = laneLength[l] - 0.5f - (position + half)
                    if (toEnd < gap) { gap = max(toEnd, 0.05f); closing = v }
                }

                val limit = max(0.05f, desired[car] * pace[car])
                val wantedGap = 1.2f + max(0f, v * 1.0f + v * closing / (2f * sqrt(2.2f * 3.2f)))
                val ratio = min(v / limit, 1.6f)
                val squeeze = wantedGap / max(gap, 0.05f)
                val acceleration = (2.2f * (1 - ratio * ratio * ratio * ratio - squeeze * squeeze)).coerceIn(-9f, 2.2f)
                val next = max(0f, v + acceleration * STEP)
                speed[car] = next
                s[car] = position + min(next * STEP, max(0f, gap - 0.04f))
                k--
            }
        }
        // Hand vehicles that cleared a junction to their next lane, front of each lane first.
        for (l in 0 until laneCount) {
            val cars = laneCars[l]
            while (cars.size > 0) {
                val car = cars[cars.size - 1]
                val next = nextLane[car]
                if (next < 0) {
                    if (map.nodeFlags[laneEndNode[l]] and 2 != 0 && s[car] >= laneLength[l] - 0.2f) {
                        if (!reenter(car)) { s[car] = laneLength[l] - 0.2f; speed[car] = 0f }
                        if (lane[car] == l) break
                        continue
                    }
                    break
                }
                val end = laneLength[l] - trimEnd[l] + connectorLength[car]
                if (s[car] < end) break
                cars.removeAt(cars.size - 1)
                val node = laneEndNode[l]
                if (admitted[car]) {
                    admitted[car] = false
                    inside[node] = max(0, inside[node] - 1)
                    if (inside[node] == 0) holder[node] = -1
                }
                val overshoot = s[car] - end
                lane[car] = next
                s[car] = trimStart[next] + overshoot
                previousS[car] = s[car]
                segment[car] = 0
                laneCars[next].insert(0, car)
                desired[car] = min(TOP_SPEED[type[car]], CLASS_SPEED[laneClass[next]]) * (0.85f + random.nextFloat() * 0.25f)
                chooseNext(car)
            }
        }
    }

    /** A vehicle that drove off the map comes back in at another edge, if there is room. */
    private fun reenter(car: Int): Boolean {
        if (portalStarts.isEmpty()) return false
        val start = random.nextInt(portalStarts.size)
        for (k in 0 until min(6, portalStarts.size)) {
            val target = portalStarts[(start + k) % portalStarts.size]
            val cars = laneCars[target]
            if (cars.size > 0 && s[cars[0]] - LENGTH[type[cars[0]]] / 2 < LENGTH[type[car]] + 6f) continue
            val old = lane[car]
            laneCars[old].removeAt(laneCars[old].indexOf(car))
            lane[car] = target
            s[car] = LENGTH[type[car]] / 2 + 0.5f
            previousS[car] = s[car]
            speed[car] = desired[car] * 0.6f
            segment[car] = 0
            admitted[car] = false
            laneCars[target].insert(0, car)
            chooseNext(car)
            locate(car)
            fromX[car] = toX[car]; fromY[car] = toY[car]; fromZ[car] = toZ[car]; fromYaw[car] = toYaw[car]
            return true
        }
        return false
    }

    private fun place(alpha: Float) {
        for (c in 0 until count) {
            x[c] = fromX[c] + (toX[c] - fromX[c]) * alpha
            y[c] = fromY[c] + (toY[c] - fromY[c]) * alpha
            z[c] = fromZ[c] + (toZ[c] - fromZ[c]) * alpha
            yaw[c] = fromYaw[c] + wrapAngle(toYaw[c] - fromYaw[c]) * alpha
        }
    }
}
