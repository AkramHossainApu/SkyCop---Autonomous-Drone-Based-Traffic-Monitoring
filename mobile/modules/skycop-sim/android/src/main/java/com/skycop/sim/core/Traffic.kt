package com.skycop.sim.core

import kotlin.math.*
import kotlin.random.Random

/** Shared by the traffic model and, through the same constants, the signal shader. */
object Signals {
    const val CYCLE = 26f
    const val GREEN = 9f
    const val YELLOW = 11.5f

    /** 0 green, 1 yellow, 2 red. Axis 0 is traffic moving along Z. */
    fun phase(time: Float, axis: Int, group: Int): Int {
        val t = (time + group * 6.5f + axis * 13f).mod(CYCLE)
        return if (t < GREEN) 0 else if (t < YELLOW) 1 else 2
    }
}

/** Intelligent Driver Model traffic that queues, obeys signals and keeps junctions clear. */
class Traffic(seed: Int = 71) {
    companion object {
        const val SPAN = 480f
        const val PER_LANE = 3
        const val LANES = (City.BLOCKS + 1) * 8
        const val COUNT = LANES * PER_LANE
        const val POLICE = 4
        const val BUS = 3
        private const val STOP_LINE = 12.2f
        private val LENGTH = floatArrayOf(4.2f, 3.6f, 4.9f, 10f, 4.3f, 2.2f)
        private val WIDTH = floatArrayOf(1.85f, 1.75f, 2f, 2.5f, 1.9f, 0.9f)
        private val HEIGHT = floatArrayOf(1.55f, 1.6f, 2.2f, 3.1f, 1.8f, 1.5f)
        private val COLORS = intArrayOf(0xE9E9E6, 0x1E2227, 0x9EA5AA, 0xA3302B, 0x2C5580, 0xE0B33C, 0x3C6B52, 0xD9702F)
        private const val STEP = 1f / 60f
    }

    val type = IntArray(COUNT)
    val color = IntArray(COUNT)
    val x = FloatArray(COUNT)
    val z = FloatArray(COUNT)
    val yaw = FloatArray(COUNT)
    val speed = FloatArray(COUNT)
    private val s = FloatArray(COUNT)
    private val previous = FloatArray(COUNT)
    private val desired = FloatArray(COUNT)

    var time = 0f
        private set
    private var accumulator = 0f
    private val random = Random(seed)

    init {
        val colors = COLORS
        for (lane in 0 until LANES) {
            val start = random.nextInt(12)
            for (k in 0 until PER_LANE) {
                val car = lane * PER_LANE + k
                val roll = random.nextFloat()
                type[car] = when {
                    roll < 0.36f -> 0
                    roll < 0.58f -> 1
                    roll < 0.74f -> 2
                    roll < 0.80f -> BUS
                    roll < 0.84f -> POLICE
                    else -> 5
                }
                color[car] = if (type[car] == POLICE) 0xF2F4F5 else colors[random.nextInt(colors.size)]
                // Start mid-block so nobody begins inside a junction.
                s[car] = -City.HALF + ((start + k * 4) % 12) * City.PITCH + 22f + random.nextFloat() * 20f
                previous[car] = s[car]
                desired[car] = 7.5f + random.nextFloat() * 3.5f
                speed[car] = desired[car] * 0.5f
            }
            sortLane(lane)
        }
        place(1f)
    }

    private fun sortLane(lane: Int) {
        val first = lane * PER_LANE
        for (a in first until first + PER_LANE) for (b in a + 1 until first + PER_LANE) {
            if (s[b] < s[a]) {
                val value = s[a]; s[a] = s[b]; s[b] = value
                previous[a] = s[a]; previous[b] = s[b]
            }
        }
    }

    fun length(car: Int) = LENGTH[type[car]]
    fun width(car: Int) = WIDTH[type[car]]
    fun height(car: Int) = HEIGHT[type[car]]

    private fun roadOf(lane: Int) = (lane / 4) % (City.BLOCKS + 1)
    private fun axisOf(lane: Int) = lane / ((City.BLOCKS + 1) * 4)
    private fun directionOf(lane: Int) = if (lane and 2 == 0) 1f else -1f

    private fun laneX(lane: Int, along: Float): Float =
        if (axisOf(lane) == 0) City.road(roadOf(lane)) - directionOf(lane) * offset(lane) else directionOf(lane) * along

    private fun laneZ(lane: Int, along: Float): Float =
        if (axisOf(lane) == 0) directionOf(lane) * along else City.road(roadOf(lane)) + directionOf(lane) * offset(lane)

    private fun laneYaw(lane: Int): Float {
        val direction = directionOf(lane)
        return if (axisOf(lane) == 1) direction * PI_F / 2 else if (direction > 0) PI_F else 0f
    }

    private fun offset(lane: Int) = if (lane and 1 == 0) 2.05f else 5.6f

    fun update(dt: Float) {
        accumulator += dt
        while (accumulator >= STEP) {
            accumulator -= STEP
            step()
        }
        place(accumulator / STEP)
    }

    private fun step() {
        time += STEP
        s.copyInto(previous)
        for (car in 0 until COUNT) {
            val lane = car / PER_LANE
            val leader = lane * PER_LANE + (car + 1) % PER_LANE
            val position = previous[car]
            val v = speed[car]
            val halfLength = length(car) / 2
            val leaderDistance = (previous[leader] - position).mod(2 * SPAN)
            var gap = leaderDistance - halfLength - length(leader) / 2
            var closing = v - speed[leader]

            // Junctions sit on multiples of the block pitch in lane coordinates too.
            val front = position + halfLength
            val junction = ceil((front + STOP_LINE - 0.4f) / City.PITCH) * City.PITCH
            if (abs(junction) <= City.HALF) {
                val toLine = junction - STOP_LINE - front
                val direction = directionOf(lane)
                val crossing = (junction * direction / City.PITCH).roundToInt() + City.BLOCKS / 2
                val phase = Signals.phase(time, axisOf(lane), (crossing + roadOf(lane)) and 1)
                val comfortable = toLine >= v * v / 9f - 0.3f
                val exitBlocked = speed[leader] < 2f &&
                    leaderDistance - toLine < 2 * STOP_LINE + length(leader) + halfLength && leaderDistance > toLine
                val stop = (phase != 0 || exitBlocked) && comfortable
                if (stop && toLine + 0.8f < gap) { gap = max(toLine + 0.8f, 0.05f); closing = v }
            }

            val limit = desired[car]
            val wanted = 1.4f + max(0f, v * 1.1f + v * closing / (2f * sqrt(2.4f * 3.2f)))
            val ratio = v / limit
            val squeeze = wanted / max(gap, 0.05f)
            val acceleration = (2.4f * (1 - ratio * ratio * ratio * ratio - squeeze * squeeze)).coerceIn(-12f, 2.4f)
            val next = max(0f, v + acceleration * STEP)
            speed[car] = next
            var moved = position + min(next * STEP, max(0f, gap - 0.04f))
            if (moved >= SPAN) {
                moved -= 2 * SPAN
                previous[car] -= 2 * SPAN
            }
            s[car] = moved
        }
    }

    private fun place(alpha: Float) {
        for (car in 0 until COUNT) {
            val lane = car / PER_LANE
            val along = previous[car] + (s[car] - previous[car]) * alpha
            x[car] = laneX(lane, along); z[car] = laneZ(lane, along); yaw[car] = laneYaw(lane)
        }
    }
}
