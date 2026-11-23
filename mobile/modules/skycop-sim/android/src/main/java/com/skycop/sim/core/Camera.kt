package com.skycop.sim.core

import kotlin.math.*

enum class View(val label: String) { CHASE("CHASE"), ONBOARD("DRONE CAMERA"), SURVEY("SURVEY") }

/**
 * The camera follows position exactly and eases only its heading.
 *
 * With head tracking the head alone decides pitch and roll, and adds its yaw to the drone's heading.
 * Any artificial tilt underneath it would make turning the head tilt the horizon, so there is none.
 */
class CameraRig(private val city: City) {
    private companion object {
        // Eyes sit above and in front of the neck pivot; moving them with the head gives natural parallax.
        const val NECK_UP = 0.075f
        const val NECK_FORWARD = 0.08f
        const val FOLLOW = 10f
    }

    val eye = FloatArray(3)
    val basis = FloatArray(16)
    var view = View.CHASE
        private set
    var gimbal = 0f
        private set
    var turnRate = 0f
        private set

    private val base = FloatArray(16)
    private var yaw = 0f
    private var yawVelocity = 0f
    private var pan = 0f
    private var panVelocity = 0f
    private var tiltVelocity = 0f
    private var distance = 4.2f

    fun next() {
        view = View.values()[(view.ordinal + 1) % View.values().size]
        recenter()
        distance = if (view == View.SURVEY) 16f else 4.3f
    }

    fun recenter() {
        gimbal = 0f; pan = 0f; panVelocity = 0f; tiltVelocity = 0f
    }

    fun snap(toYaw: Float) {
        yaw = toYaw; yawVelocity = 0f; turnRate = 0f
        recenter()
    }

    fun look(panRate: Float, tiltRate: Float, dt: Float) {
        panVelocity += (panRate * 1.8f - panVelocity) * blend(14f, dt)
        tiltVelocity += (tiltRate * 1.2f - tiltVelocity) * blend(14f, dt)
        pan = wrapAngle(pan + panVelocity * dt)
        gimbal = (gimbal + tiltVelocity * dt).coerceIn(-85f * DEG, 30f * DEG)
    }

    /** [tracked] means [head] holds a live head rotation. */
    fun update(dt: Float, drone: DronePose, vr: Boolean, tracked: Boolean, head: FloatArray) {
        val before = yaw
        val onboard = view == View.ONBOARD
        if (onboard) {
            yaw = drone.yaw; yawVelocity = 0f
        } else {
            // Exact critically damped spring: no jolt when a turn starts or stops, at any frame rate.
            val offset = yaw - drone.yaw
            val impulse = (yawVelocity + FOLLOW * offset) * dt
            val decay = exp(-FOLLOW * dt)
            yawVelocity = (yawVelocity - FOLLOW * impulse) * decay
            yaw = drone.yaw + (offset + impulse) * decay
        }
        if (dt > 0f) turnRate = abs(yaw - before) / dt
        val heading = yaw + pan

        var pitch: Float
        val wantedDistance: Float
        val lift: Float
        val elevation: Float
        when (view) {
            View.ONBOARD -> {
                pitch = gimbal; wantedDistance = 0f; lift = 0f; elevation = 0f
            }
            View.CHASE -> {
                val rest = if (vr) 0f else -9f * DEG
                pitch = (rest + gimbal).coerceIn(-85f * DEG, 35f * DEG)
                wantedDistance = 4.3f; lift = 1.15f
                elevation = (-gimbal).coerceIn(-0.2f, 1.35f)
            }
            View.SURVEY -> {
                // With a level base, a lower orbit keeps the drone in sight below the horizon.
                val rest = if (tracked) 28f else 55f
                pitch = (-rest * DEG + gimbal).coerceIn(-88f * DEG, -10f * DEG)
                wantedDistance = if (tracked) 16f else 20f; lift = 1f
                elevation = -pitch
            }
        }
        if (tracked) pitch = 0f

        val fx = sin(heading); val fz = -cos(heading)
        if (onboard) {
            eye[0] = drone.x + sin(drone.yaw) * 0.5f
            eye[1] = max(0.25f, drone.y - 0.05f)
            eye[2] = drone.z - cos(drone.yaw) * 0.5f
        } else {
            val dx = -fx * wantedDistance * cos(elevation)
            val dy = lift + wantedDistance * sin(elevation)
            val dz = -fz * wantedDistance * cos(elevation)
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            // Trees and poles are ignored, otherwise the view pumps in and out when passing them.
            val hit = city.raycast(drone.x, drone.y, drone.z, dx / length, dy / length, dz / length, length + 0.4f, null, true)
            val allowed = max(0.8f, min(length, hit - 0.4f))
            distance += (allowed - distance) * blend(if (allowed < distance) 18f else 3f, dt)
            eye[0] = drone.x + dx / length * distance
            eye[1] = max(0.5f, drone.y + dy / length * distance)
            eye[2] = drone.z + dz / length * distance
        }

        Mat4.pose(base, 0f, 0f, 0f, heading, -pitch, 0f)
        Mat4.multiply(basis, base, head)
        if (tracked) {
            for (i in 0..2) {
                eye[i] += (basis[4 + i] - base[4 + i]) * NECK_UP - (basis[8 + i] - base[8 + i]) * NECK_FORWARD
            }
        }
    }
}
