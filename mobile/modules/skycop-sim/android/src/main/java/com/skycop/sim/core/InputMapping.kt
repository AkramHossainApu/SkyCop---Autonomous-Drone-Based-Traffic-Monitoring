package com.skycop.sim.core

import kotlin.math.*

object Button {
    const val A = 1
    const val B = 1 shl 1
    const val X = 1 shl 2
    const val Y = 1 shl 3
    const val LB = 1 shl 4
    const val RB = 1 shl 5
    const val LT = 1 shl 6
    const val RT = 1 shl 7
    const val SELECT = 1 shl 8
    const val START = 1 shl 9
    const val L3 = 1 shl 10
    const val R3 = 1 shl 11
    const val UP = 1 shl 12
    const val DOWN = 1 shl 13
    const val LEFT = 1 shl 14
    const val RIGHT = 1 shl 15
}

/** Indexes into the raw axis array filled by the platform layer. */
object Axis {
    const val X = 0
    const val Y = 1
    const val Z = 2
    const val RZ = 3
    const val RX = 4
    const val RY = 5
    const val LTRIGGER = 6
    const val RTRIGGER = 7
    const val BRAKE = 8
    const val GAS = 9
    const val THROTTLE = 10
    const val RUDDER = 11
    const val COUNT = 12
}

class PadState {
    var leftX = 0f
    var leftY = 0f
    var rightX = 0f
    var rightY = 0f
    var leftTrigger = 0f
    var rightTrigger = 0f
    var held = 0

    fun clear() {
        leftX = 0f; leftY = 0f; rightX = 0f; rightY = 0f
        leftTrigger = 0f; rightTrigger = 0f; held = 0
    }
}

/**
 * Turns whatever axes a controller reports into two sticks and two triggers.
 *
 * Android controllers disagree about where the right stick and triggers live:
 *  - with a key layout: stick on Z/RZ, triggers on LTRIGGER/RTRIGGER or BRAKE/GAS
 *  - raw Xbox style:    stick on RX/RY, triggers on Z/RZ, resting at -1
 *  - raw PlayStation:   stick on Z/RZ, triggers on RX/RY, resting at -1
 * Values are never expired: Android only sends an event when an axis changes.
 */
class InputMapping {
    private val channel = IntArray(6) { -1 }
    private val direction = FloatArray(6) { 1f }
    private val rest = FloatArray(6)
    private val span = FloatArray(6) { 1f }
    private val moved = BooleanArray(6)
    private var altLeft = -1
    private var altRight = -1
    private var undecided = false
    private var sony = false
    private var available = BooleanArray(Axis.COUNT)

    var calibrated = false
        private set

    fun configure(axes: BooleanArray, vendor: Int) {
        available = axes.copyOf()
        sony = vendor == 0x054c
        calibrated = false
        channel.fill(-1); direction.fill(1f); rest.fill(0f); span.fill(1f); moved.fill(false)
        altLeft = -1; altRight = -1; undecided = false

        channel[0] = Axis.X
        channel[1] = Axis.Y; direction[1] = -1f; direction[3] = -1f

        val zPair = axes[Axis.Z] && axes[Axis.RZ]
        val rPair = axes[Axis.RX] && axes[Axis.RY]
        val triggers = axes[Axis.LTRIGGER] && axes[Axis.RTRIGGER]
        val pedals = axes[Axis.BRAKE] && axes[Axis.GAS]
        if (triggers || pedals) {
            channel[4] = if (triggers) Axis.LTRIGGER else Axis.BRAKE
            channel[5] = if (triggers) Axis.RTRIGGER else Axis.GAS
            if (triggers && pedals) { altLeft = Axis.BRAKE; altRight = Axis.GAS }
            setRight(if (zPair) Axis.Z else if (rPair) Axis.RX else -1)
        } else if (zPair && rPair) {
            if (sony) splitSony() else splitXbox()
            undecided = true
        } else {
            setRight(if (zPair) Axis.Z else if (rPair) Axis.RX else -1)
        }
    }

    private fun setRight(first: Int) {
        channel[2] = first
        channel[3] = when (first) {
            Axis.Z -> Axis.RZ
            Axis.RX -> Axis.RY
            else -> -1
        }
    }

    private fun centeredTriggers(left: Int, right: Int) {
        channel[4] = left; channel[5] = right
        rest[4] = -1f; rest[5] = -1f; span[4] = 2f; span[5] = 2f
    }

    private fun splitXbox() {
        setRight(Axis.RX); centeredTriggers(Axis.Z, Axis.RZ)
    }

    private fun splitSony() {
        setRight(Axis.Z); centeredTriggers(Axis.RX, Axis.RY)
    }

    fun read(raw: FloatArray, held: Int, deadzone: Float, out: PadState) {
        if (undecided) {
            // A pair that rests at -1 can only be triggers.
            val zLow = raw[Axis.Z] < -0.95f && raw[Axis.RZ] < -0.95f
            val rLow = raw[Axis.RX] < -0.95f && raw[Axis.RY] < -0.95f
            if (zLow != rLow) {
                if (zLow) splitXbox() else splitSony()
                undecided = false
            }
        }
        val lx = signed(0, raw); val ly = signed(1, raw)
        var scale = radialScale(lx, ly, deadzone)
        out.leftX = lx * scale; out.leftY = ly * scale
        val rx = signed(2, raw); val ry = signed(3, raw)
        scale = radialScale(rx, ry, deadzone)
        out.rightX = rx * scale; out.rightY = ry * scale
        out.leftTrigger = trigger(4, raw, altLeft, held and Button.LT != 0)
        out.rightTrigger = trigger(5, raw, altRight, held and Button.RT != 0)
        out.held = held
    }

    private fun signed(slot: Int, raw: FloatArray): Float {
        val axis = channel[slot]
        return if (axis < 0) 0f else ((raw[axis] - rest[slot]) * direction[slot]).coerceIn(-1f, 1f)
    }

    private fun trigger(slot: Int, raw: FloatArray, alternative: Int, pressed: Boolean): Float {
        var value = 0f
        val axis = channel[slot]
        if (axis >= 0) {
            // Android reports 0 for an axis it has not sampled, which would look like a half pull at rest -1.
            if (!moved[slot] && abs(raw[axis]) > 0.001f) moved[slot] = true
            if (moved[slot]) value = ((raw[axis] - rest[slot]) * direction[slot] / span[slot]).coerceIn(0f, 1f)
        }
        if (alternative >= 0) {
            val other = raw[alternative].coerceIn(0f, 1f)
            if (other > 0f) moved[slot] = true
            value = max(value, other)
        }
        // The key is only a fallback; next to a working analog trigger it would turn every pull into 100%.
        if (pressed && !moved[slot]) value = 1f
        return if (value < 0.05f) 0f else (value - 0.05f) / 0.95f
    }

    private fun radialScale(x: Float, y: Float, deadzone: Float): Float {
        val magnitude = hypot(x, y)
        if (magnitude <= deadzone) return 0f
        return min(1f, (magnitude - deadzone) / (0.96f - deadzone)) / magnitude
    }

    fun assign(slot: Int, axis: Int, sign: Float, restValue: Float) {
        channel[slot] = axis
        direction[slot] = sign
        rest[slot] = restValue
        span[slot] = if (slot >= 4 && abs(restValue) > 0.5f) 2f else 1f
        moved[slot] = false
        if (slot == 4) altLeft = -1
        if (slot == 5) altRight = -1
        undecided = false
        calibrated = true
    }

    fun encode(): String = (0..5).joinToString(";") { "${channel[it]},${direction[it]},${rest[it]}" }

    fun decode(text: String): Boolean {
        val parts = text.split(';').map { it.split(',') }
        if (parts.size != 6 || parts.any { it.size != 3 }) return false
        val axes = parts.map { it[0].toIntOrNull() ?: return false }
        val signs = parts.map { it[1].toFloatOrNull() ?: return false }
        val rests = parts.map { it[2].toFloatOrNull() ?: return false }
        if (axes.any { it !in -1 until Axis.COUNT }) return false
        for (slot in 0..5) assign(slot, axes[slot], signs[slot], rests[slot])
        return true
    }
}

/** Guided mapping for controllers that no heuristic could get right. */
class Calibration {
    companion object {
        val PROMPTS = arrayOf(
            "PUSH LEFT STICK RIGHT", "PUSH LEFT STICK UP", "PUSH RIGHT STICK RIGHT",
            "PUSH RIGHT STICK UP", "PULL LEFT TRIGGER", "PULL RIGHT TRIGGER",
        )
    }

    var step = -1
        private set
    var waitingForRelease = false
        private set
    val active get() = step >= 0

    private val base = FloatArray(Axis.COUNT)
    private val axes = IntArray(6)
    private val signs = FloatArray(6)
    private val rests = FloatArray(6)
    private var peak = 0f
    private var last = 0f
    private var still = 0f
    private var settle = 0f

    fun start() {
        step = 0; waitingForRelease = false; settle = 0.5f
        axes.fill(-1); signs.fill(1f); rests.fill(0f)
    }

    fun cancel() {
        step = -1
    }

    fun skip() {
        if (active && !waitingForRelease) { step++; settle = 0.3f }
    }

    /** Returns true once every step is done and the result has been written to [mapping]. */
    fun update(raw: FloatArray, dt: Float, mapping: InputMapping): Boolean {
        if (!active) return false
        if (step >= 6) {
            for (slot in 0..5) mapping.assign(slot, axes[slot], signs[slot], rests[slot])
            step = -1
            return true
        }
        if (settle > 0f) {
            settle -= dt
            raw.copyInto(base)
            return false
        }
        if (!waitingForRelease) {
            var found = -1; var largest = 0.65f
            for (axis in 0 until Axis.COUNT) {
                var used = false
                for (slot in 0 until step) if (axes[slot] == axis) used = true
                val delta = abs(raw[axis] - base[axis])
                if (!used && delta > largest) { largest = delta; found = axis }
            }
            if (found >= 0) {
                axes[step] = found
                signs[step] = sign(raw[found] - base[found])
                peak = raw[found]; last = peak; still = 0f
                waitingForRelease = true
            }
        } else {
            val value = raw[axes[step]]
            if ((value - peak) * signs[step] > 0f) peak = value
            still = if (abs(value - last) < 0.03f) still + dt else 0f
            last = value
            if ((peak - value) * signs[step] > 0.5f && still > 0.35f) {
                rests[step] = value
                waitingForRelease = false
                step++
                settle = 0.25f
            }
        }
        return false
    }
}

/** Maps the normalized controller to flight commands for either layout. */
object ControlScheme {
    /** Fine control near the center with full authority at the edge. */
    fun curve(v: Float) = v * (0.6f + 0.4f * v * v)

    fun apply(pad: PadState, settings: Settings, out: PilotInput) {
        val leftVertical = curve(pad.leftY)
        val triggers = curve(pad.rightTrigger) - curve(pad.leftTrigger)
        out.yaw = curve(pad.leftX)
        if (settings.classicLayout) {
            out.forward = leftVertical
            out.strafe = (if (pad.held and Button.RB != 0) 1f else 0f) - (if (pad.held and Button.LB != 0) 1f else 0f)
            out.climb = triggers
        } else {
            out.forward = curve(pad.rightY)
            out.strafe = curve(pad.rightX)
            out.climb = ((if (settings.invertClimb) -leftVertical else leftVertical) + triggers).coerceIn(-1f, 1f)
        }
    }
}
