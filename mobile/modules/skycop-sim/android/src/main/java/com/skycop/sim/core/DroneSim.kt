package com.skycop.sim.core

import kotlin.math.*

enum class FlightMode(
    val label: String, val speed: Float, val accel: Float, val tilt: Float,
    val climb: Float, val yawRate: Float, val avoid: Boolean, val gps: Boolean,
) {
    CINE("CINE", 5f, 3.5f, 25f * DEG, 2.5f, 55f * DEG, true, true),
    NORMAL("NORMAL", 14f, 7.5f, 36f * DEG, 5f, 100f * DEG, true, true),
    SPORT("SPORT", 24f, 11.5f, 50f * DEG, 8f, 145f * DEG, false, true),
    ATTI("ATTI (NO GPS)", 14f, 6f, 25f * DEG, 4f, 90f * DEG, false, false),
}

enum class Phase(val label: String) {
    LANDED("LANDED"), TAKEOFF("TAKE-OFF"), FLYING("FLYING"), LANDING("LANDING"), RETURN("RETURN HOME"), CRASHED("CRASHED"),
}

/** All axes are -1..1: right, up and forward are positive. */
class PilotInput {
    var yaw = 0f
    var climb = 0f
    var forward = 0f
    var strafe = 0f

    fun clear() {
        yaw = 0f; climb = 0f; forward = 0f; strafe = 0f
    }
}

class DronePose {
    var x = 0f
    var y = 0f
    var z = 0f
    var yaw = 0f
    var pitch = 0f
    var roll = 0f
    var rotor = 0f

    fun copyFrom(o: DronePose) {
        x = o.x; y = o.y; z = o.z; yaw = o.yaw; pitch = o.pitch; roll = o.roll; rotor = o.rotor
    }

    fun interpolate(a: DronePose, b: DronePose, t: Float) {
        x = a.x + (b.x - a.x) * t; y = a.y + (b.y - a.y) * t; z = a.z + (b.z - a.z) * t
        yaw = a.yaw + (b.yaw - a.yaw) * t
        pitch = a.pitch + (b.pitch - a.pitch) * t
        roll = a.roll + (b.roll - a.roll) * t
        rotor = a.rotor + (b.rotor - a.rotor) * t
    }
}

/**
 * Quadcopter model at a fixed 250 Hz, independent of display refresh rate.
 *
 * Sticks command a velocity; the flight computer turns that into an acceleration; the airframe has
 * to lean to produce it, because thrust only acts along the drone's own up axis. Gravity, drag,
 * wind, motor lag, ground effect and battery drain act on top of that.
 */
class DroneSim(private val city: City, private val traffic: Traffic) {
    companion object {
        const val STEP = 1f / 250f
        const val GEAR = 0.3f
        private const val RADIUS = 0.45f
        private const val MAX_THRUST = 2.4f * GRAVITY
        private const val CEILING = 120f
        /** How quickly the last bit of a speed change eases in. */
        private const val EASE = 4.5f
        /** Full acceleration is reached in 1 / JERK seconds. */
        private const val JERK = 12f
        private const val CLIMB_ACCEL = 9f
        /** Natural frequency of the attitude response, in rad/s. */
        private const val AGILITY = 15f
    }

    val pose = DronePose()
    val render = DronePose()
    private val previous = DronePose()

    var vx = 0f
        private set
    var vy = 0f
        private set
    var vz = 0f
        private set
    var phase = Phase.LANDED
        private set
    var mode = FlightMode.NORMAL
    var battery = 100f
        private set
    var thrust = 0f
        private set
    var obstacle = Float.MAX_VALUE
        private set
    var yawSensitivity = 1f
    var windEnabled = true

    /** Strongest bump since the host last cleared it; used for rumble and camera feedback. */
    var impact = 0f
    var crashes = 0
        private set

    private var accumulator = 0f
    private var time = 0f
    private var phaseTime = 0f
    private var yawVelocity = 0f
    private var pitchVelocity = 0f
    private var rollVelocity = 0f
    private var refX = 0f
    private var refY = 0f
    private var refZ = 0f
    private var refAx = 0f
    private var refAy = 0f
    private var refAz = 0f
    private var modelX = 0f
    private var modelZ = 0f
    private var modelAx = 0f
    private var modelAz = 0f
    private var settledFor = 0f
    private var settledHeightFor = 0f
    private var holdX = 0f
    private var holdY = 0f
    private var holdZ = 0f
    private var holdingPosition = false
    private var holdingHeight = false
    private var ground = 0f
    private var touching = false
    private var takeoffHeight = 0f
    private var landingPressed = 0f
    private var lowBatteryHandled = false
    private val normal = FloatArray(3)

    val airborne get() = phase != Phase.LANDED && phase != Phase.CRASHED
    val height get() = max(0f, pose.y - GEAR - ground)
    val speed get() = hypot(vx, vz)
    val homeDistance get() = hypot(pose.x - City.HOME_X, pose.z - City.HOME_Z)

    init {
        reset()
    }

    fun reset() {
        pose.x = City.HOME_X; pose.z = City.HOME_Z; pose.y = City.HOME_Y + GEAR
        pose.yaw = 0f; pose.pitch = 0f; pose.roll = 0f; pose.rotor = 0f
        previous.copyFrom(pose); render.copyFrom(pose)
        vx = 0f; vy = 0f; vz = 0f
        yawVelocity = 0f; pitchVelocity = 0f; rollVelocity = 0f
        clearTargets()
        thrust = 0f; battery = 100f; impact = 0f; accumulator = 0f
        obstacle = Float.MAX_VALUE; ground = City.HOME_Y
        lowBatteryHandled = false
        change(Phase.LANDED)
    }

    private fun clearTargets() {
        refX = 0f; refY = 0f; refZ = 0f
        refAx = 0f; refAy = 0f; refAz = 0f
        modelX = 0f; modelZ = 0f; modelAx = 0f; modelAz = 0f
        settledFor = 0f; settledHeightFor = 0f
        holdingPosition = false; holdingHeight = false
    }

    private fun change(next: Phase) {
        phase = next
        phaseTime = 0f
        landingPressed = 0f
    }

    fun toggleTakeoffLanding() {
        when (phase) {
            Phase.LANDED -> takeOff()
            Phase.CRASHED -> reset()
            Phase.LANDING -> change(Phase.FLYING)
            else -> change(Phase.LANDING)
        }
    }

    fun toggleReturnHome() {
        when (phase) {
            Phase.RETURN -> change(Phase.FLYING)
            Phase.FLYING, Phase.TAKEOFF, Phase.LANDING -> change(Phase.RETURN)
            else -> Unit
        }
    }

    private fun takeOff() {
        if (battery < 3f) return
        clearTargets()
        takeoffHeight = pose.y + 2.6f
        change(Phase.TAKEOFF)
    }

    fun update(dt: Float, input: PilotInput) {
        accumulator += min(dt, 0.1f)
        while (accumulator >= STEP) {
            accumulator -= STEP
            previous.copyFrom(pose)
            step(input)
        }
        render.interpolate(previous, pose, accumulator / STEP)
    }

    private fun step(input: PilotInput) {
        val dt = STEP
        time += dt
        phaseTime += dt
        ground = city.support(pose.x, pose.z, pose.y - GEAR + 0.08f, 0.15f)

        if (phase == Phase.LANDED) {
            vx = 0f; vy = 0f; vz = 0f; thrust = 0f
            pose.pitch *= 1 - 10 * dt; pose.roll *= 1 - 10 * dt
            pose.rotor *= 1 - 2 * dt
            if (homeDistance < 5.5f) {
                battery = min(100f, battery + 10f * dt)
                lowBatteryHandled = false
            }
            if (input.climb > 0.45f) takeOff()
            return
        }
        if (phase == Phase.CRASHED) {
            fall(dt)
            return
        }

        val pilot = max(max(abs(input.forward), abs(input.strafe)), abs(input.climb))
        if ((phase == Phase.RETURN || phase == Phase.LANDING) && phaseTime > 0.4f && battery > 1f) {
            val override = if (phase == Phase.LANDING) max(max(abs(input.forward), abs(input.strafe)), input.climb) else pilot
            if (override > 0.4f) change(Phase.FLYING)
        }
        if (battery < 8f && !lowBatteryHandled && phase == Phase.FLYING) {
            lowBatteryHandled = true
            change(Phase.RETURN)
        }
        if (battery <= 1f && phase != Phase.LANDING) change(Phase.LANDING)

        var wantX = 0f; var wantY = 0f; var wantZ = 0f
        var wantYawRate = 0f
        val sy = sin(pose.yaw); val cy = cos(pose.yaw)
        when (phase) {
            Phase.TAKEOFF -> {
                if (phaseTime > 0.8f) wantY = ((takeoffHeight - pose.y) * 2f).coerceIn(0.3f, 3f)
                if (pose.y >= takeoffHeight - 0.2f || (phaseTime > 1.4f && pilot > 0.3f)) change(Phase.FLYING)
                wantYawRate = input.yaw * mode.yawRate * yawSensitivity
            }
            Phase.FLYING -> {
                var forward = input.forward; var strafe = input.strafe
                val magnitude = hypot(forward, strafe)
                if (magnitude > 1f) { forward /= magnitude; strafe /= magnitude }
                wantX = (sy * forward + cy * strafe) * mode.speed
                wantZ = (-cy * forward + sy * strafe) * mode.speed
                wantY = input.climb * mode.climb
                if (height < 4f && wantY < 0f) wantY = max(wantY, -(0.5f + height * 0.8f))
                if (pose.y > CEILING - 4f) wantY = min(wantY, (CEILING - pose.y).coerceAtLeast(-1f))
                wantYawRate = input.yaw * mode.yawRate * yawSensitivity
                landingPressed = if (input.climb < -0.6f && height < 0.08f) landingPressed + dt else 0f
            }
            Phase.LANDING -> {
                wantY = -(0.55f + height * 0.45f).coerceAtMost(2.6f)
                wantYawRate = input.yaw * mode.yawRate * yawSensitivity
                landingPressed = if (height < 0.08f) landingPressed + dt else 0f
            }
            Phase.RETURN -> {
                val dx = City.HOME_X - pose.x; val dz = City.HOME_Z - pose.z
                val distance = hypot(dx, dz)
                val safe = min(city.tallest + 7f, CEILING - 4f)
                if (distance < 0.7f && speed < 0.8f) {
                    change(Phase.LANDING)
                } else if (pose.y < safe - 1.5f && distance > 4f) {
                    wantY = min(7f, (safe - pose.y) * 1.2f + 0.5f)
                } else {
                    val pace = min(14f, distance * 0.6f)
                    if (distance > 0.05f) { wantX = dx / distance * pace; wantZ = dz / distance * pace }
                    if (distance > 4f) {
                        wantY = ((safe - pose.y) * 0.8f).coerceIn(-2f, 3f)
                        wantYawRate = (wrapAngle(atan2(dx, -dz) - pose.yaw) * 2f).coerceIn(-1.4f, 1.4f)
                    }
                }
            }
            else -> Unit
        }

        // Soft geofence: slide along the boundary instead of hitting a wall.
        wantX = wantX.coerceIn(min(14f, (-City.BOUNDS - pose.x) * 0.5f), max(-14f, (City.BOUNDS - pose.x) * 0.5f))
        wantZ = wantZ.coerceIn(min(14f, (-City.BOUNDS - pose.z) * 0.5f), max(-14f, (City.BOUNDS - pose.z) * 0.5f))

        obstacle = Float.MAX_VALUE
        val wanted = hypot(wantX, wantZ)
        if (mode.avoid && phase == Phase.FLYING && wanted > 0.2f) {
            val dx = wantX / wanted; val dz = wantZ / wanted
            val range = 4f + max(wanted, speed) * max(wanted, speed) / mode.accel
            val hit = city.raycast(pose.x, pose.y, pose.z, dx, 0f, dz, range, normal)
            if (hit < range) {
                val into = -(wantX * normal[0] + wantZ * normal[2])
                if (into > 0f) {
                    val clearance = hit * into / wanted - 1.6f
                    val allowed = if (clearance <= 0f) 0f else min(sqrt(mode.accel * clearance), clearance * 1.4f)
                    if (allowed < into) {
                        wantX += normal[0] * (into - allowed); wantZ += normal[2] * (into - allowed)
                        obstacle = hit
                    }
                }
            }
        }

        val gps = mode.gps || phase != Phase.FLYING
        // The commanded velocity has limited acceleration and limited jerk, so the airframe rolls
        // smoothly into every move and out of it, with no kink when a stick is slammed or released.
        val changeX = wantX - refX; val changeZ = wantZ - refZ
        val change = hypot(changeX, changeZ)
        val pull = if (change * EASE > mode.accel) mode.accel / change else EASE
        val jerkX = changeX * pull - refAx; val jerkZ = changeZ * pull - refAz
        val jerk = hypot(jerkX, jerkZ)
        val jerkLimit = mode.accel * JERK * dt
        val jerkScale = if (jerk > jerkLimit) jerkLimit / jerk else 1f
        refAx += jerkX * jerkScale; refAz += jerkZ * jerkScale
        refX += refAx * dt; refZ += refAz * dt
        if (change < 0.01f && hypot(refAx, refAz) < 0.05f) {
            refX = wantX; refZ = wantZ; refAx = 0f; refAz = 0f
        }
        // The airframe needs a moment to lean. Feedback compares against the velocity it can really
        // have by now; comparing against the raw command is what makes a drone spring back on release.
        modelAx += (AGILITY * AGILITY * (refX - modelX) - 2f * AGILITY * modelAx) * dt
        modelAz += (AGILITY * AGILITY * (refZ - modelZ) - 2f * AGILITY * modelAz) * dt
        modelX += modelAx * dt; modelZ += modelAz * dt
        var ax: Float; var az: Float
        val maxTilt = mode.tilt
        val horizontalDrag = 0.06f + 0.009f * sqrt(vx * vx + vy * vy + vz * vz)
        if (gps) {
            var targetX = modelX; var targetZ = modelZ
            if (wantX == 0f && wantZ == 0f) {
                if (!holdingPosition && hypot(modelX, modelZ) < 0.03f) {
                    settledFor += dt
                    if (speed < 0.12f || settledFor > 0.6f) {
                        // Hold where it will come to rest, not where it is now, so it never creeps back.
                        holdingPosition = true
                        holdX = pose.x + vx / 2f; holdZ = pose.z + vz / 2f
                    }
                }
                if (holdingPosition) {
                    targetX += ((holdX - pose.x) * 1.4f).coerceIn(-2f, 2f)
                    targetZ += ((holdZ - pose.z) * 1.4f).coerceIn(-2f, 2f)
                }
            } else {
                holdingPosition = false
                settledFor = 0f
            }
            ax = refAx + (targetX - vx) * 3f + vx * horizontalDrag
            az = refAz + (targetZ - vz) * 3f + vz * horizontalDrag
        } else {
            holdingPosition = false
            ax = wantX / mode.speed * GRAVITY * tan(maxTilt)
            az = wantZ / mode.speed * GRAVITY * tan(maxTilt)
            refX = vx; refZ = vz; refAx = 0f; refAz = 0f
            modelX = vx; modelZ = vz; modelAx = 0f; modelAz = 0f
        }
        val horizontal = hypot(ax, az)
        val horizontalLimit = GRAVITY * tan(maxTilt)
        if (horizontal > horizontalLimit) { ax *= horizontalLimit / horizontal; az *= horizontalLimit / horizontal }

        val wantAy = ((wantY - refY) * 6f).coerceIn(-CLIMB_ACCEL, CLIMB_ACCEL)
        refAy = approach(refAy, wantAy, CLIMB_ACCEL * 9f * dt)
        refY += refAy * dt
        if (abs(wantY - refY) < 0.01f && abs(refAy) < 0.08f) { refY = wantY; refAy = 0f }
        var targetY = refY
        if (wantY == 0f) {
            if (!holdingHeight && abs(refY) < 0.03f) {
                settledHeightFor += dt
                if (abs(vy) < 0.08f || settledHeightFor > 0.5f) { holdingHeight = true; holdY = pose.y + vy / 3f }
            }
            if (holdingHeight) targetY += ((holdY - pose.y) * 2f).coerceIn(-2f, 2f)
        } else {
            holdingHeight = false
            settledHeightFor = 0f
        }
        val ay = refAy + (targetY - vy) * 4.5f + vy * horizontalDrag

        // Solve the lean that points the thrust vector along the wanted acceleration.
        val localForward = ax * sy - az * cy
        val localRight = ax * cy + az * sy
        val total = sqrt(localForward * localForward + localRight * localRight + GRAVITY * GRAVITY)
        val wantRoll = asin(localRight / total)
        val wantPitch = atan2(localForward, GRAVITY)
        val omega = AGILITY
        pitchVelocity += (omega * omega * (wantPitch - pose.pitch) - 2f * omega * pitchVelocity) * dt
        rollVelocity += (omega * omega * (wantRoll - pose.roll) - 2f * omega * rollVelocity) * dt
        pose.pitch += pitchVelocity * dt
        pose.roll += rollVelocity * dt
        // Ease into a turn, stop promptly when the stick is let go.
        val yawResponse = if (abs(wantYawRate) < abs(yawVelocity)) 22f else 11f
        yawVelocity += (wantYawRate - yawVelocity) * min(1f, yawResponse * dt)
        if (wantYawRate == 0f && abs(yawVelocity) < 0.001f) yawVelocity = 0f
        pose.yaw += yawVelocity * dt

        val sp = sin(pose.pitch); val cp = cos(pose.pitch)
        val sr = sin(pose.roll); val cr = cos(pose.roll)
        val spool = if (phase == Phase.TAKEOFF) (phaseTime / 0.8f).coerceIn(0f, 1f) else 1f
        val aboveGround = pose.y - GEAR - ground
        val cushion = if (aboveGround < 1.2f) 1f + 0.05f * (1f - aboveGround.coerceAtLeast(0f) / 1.2f) else 1f
        val command = ((GRAVITY + ay) / max(0.5f, cp * cr) / cushion).coerceIn(0f, MAX_THRUST) * spool * spool
        thrust += (command - thrust) * min(1f, dt / 0.045f)
        val lift = thrust * cushion
        val pushForward = lift * sp * cr; val pushRight = lift * sr

        var windX = 0f; var windZ = 0f
        if (windEnabled && aboveGround > 0.2f) {
            val strength = 0.15f + min(pose.y, 80f) * 0.004f
            windX = strength * (0.6f + sin(time * 0.7f) * 0.3f + sin(time * 2.3f) * 0.2f)
            windZ = strength * (0.3f + sin(time * 0.43f + 2f) * 0.4f + sin(time * 1.7f) * 0.15f)
        }
        vx += (sy * pushForward + cy * pushRight + windX - vx * horizontalDrag) * dt
        vy += (lift * cp * cr - GRAVITY - vy * horizontalDrag) * dt
        vz += (-cy * pushForward + sy * pushRight + windZ - vz * horizontalDrag) * dt
        pose.x += vx * dt; pose.y += vy * dt; pose.z += vz * dt
        pose.rotor = sqrt(max(0f, thrust) / GRAVITY).coerceAtLeast(0.25f * spool)

        val load = thrust / GRAVITY
        battery = max(0f, battery - dt * (0.025f + 0.085f * load * sqrt(max(0f, load))))

        collide()
        if (phase != Phase.CRASHED && landingPressed > 0.55f) {
            clearTargets()
            vx = 0f; vy = 0f; vz = 0f; yawVelocity = 0f
            change(Phase.LANDED)
        }
    }

    private fun fall(dt: Float) {
        thrust = 0f
        pose.rotor *= 1 - 3 * dt
        vy -= GRAVITY * dt
        vx *= 1 - 0.5f * dt; vz *= 1 - 0.5f * dt
        pose.x += vx * dt; pose.y += vy * dt; pose.z += vz * dt
        val floor = ground + 0.15f
        if (pose.y < floor) {
            pose.y = floor
            vy = if (vy < -2f) -vy * 0.25f else 0f
            vx *= 1 - 8 * dt; vz *= 1 - 8 * dt
        } else {
            pose.pitch += 3.5f * dt; pose.roll += 2.1f * dt
        }
        if (phaseTime > 3.5f) reset()
    }

    private fun collide() {
        touching = false
        val below = previous.y - GEAR + 0.08f
        val floor = max(ground, city.support(pose.x, pose.z, below, 0.15f))
        if (pose.y - GEAR < floor) {
            pose.y = floor + GEAR
            touching = true
            hit(0f, 1f, 0f, 0f)
            if (phase == Phase.FLYING || phase == Phase.LANDING) {
                vx *= 1 - 6 * STEP; vz *= 1 - 6 * STEP
            }
        }
        // Blocks are bucketed by center, so neighbors can hold boxes that reach this point.
        val here = city.chunkAt(pose.x, pose.z)
        collideBoxes(here)
        val sideX = if (pose.x > here.x) City.PITCH else -City.PITCH
        val sideZ = if (pose.z > here.z) City.PITCH else -City.PITCH
        val nearEdgeX = abs(pose.x - here.x) > 24f
        val nearEdgeZ = abs(pose.z - here.z) > 24f
        var other: City.Chunk
        if (nearEdgeX) {
            other = city.chunkAt(here.x + sideX, here.z)
            if (other !== here) collideBoxes(other)
        }
        if (nearEdgeZ) {
            other = city.chunkAt(here.x, here.z + sideZ)
            if (other !== here) collideBoxes(other)
        }
        if (nearEdgeX && nearEdgeZ) {
            other = city.chunkAt(here.x + sideX, here.z + sideZ)
            if (other !== here) collideBoxes(other)
        }
        if (pose.y < 4f) collideTraffic()
    }

    private fun collideBoxes(c: City.Chunk) {
        if (pose.y - RADIUS > c.top) return
        val b = c.boxes
        var k = 0
        while (k < c.count * 6) {
            if (b[k + 4] > pose.y - GEAR + 0.03f) pushOut(b[k], b[k + 1], b[k + 2], b[k + 3], b[k + 4], b[k + 5], 0f, 0f)
            k += 6
        }
    }

    private fun collideTraffic() {
        for (car in 0 until Traffic.COUNT) {
            val x = traffic.x[car]; val z = traffic.z[car]
            if (abs(x - pose.x) > 7f || abs(z - pose.z) > 7f) continue
            val alongX = abs(sin(traffic.yaw[car])) > 0.5f
            val halfX = (if (alongX) traffic.length(car) else traffic.width(car)) / 2
            val halfZ = (if (alongX) traffic.width(car) else traffic.length(car)) / 2
            val v = traffic.speed[car]
            pushOut(
                x - halfX, 0f, z - halfZ, x + halfX, traffic.height(car), z + halfZ,
                sin(traffic.yaw[car]) * v, -cos(traffic.yaw[car]) * v,
            )
        }
    }

    private fun pushOut(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, moveX: Float, moveZ: Float) {
        val dx = pose.x - pose.x.coerceIn(x0, x1)
        val dy = pose.y - pose.y.coerceIn(y0, y1)
        val dz = pose.z - pose.z.coerceIn(z0, z1)
        val squared = dx * dx + dy * dy + dz * dz
        if (squared >= RADIUS * RADIUS) return
        var nx = 0f; var ny = 0f; var nz = 0f
        val push: Float
        if (squared > 1e-6f) {
            val distance = sqrt(squared)
            nx = dx / distance; ny = dy / distance; nz = dz / distance
            push = RADIUS - distance
        } else {
            val left = pose.x - x0; val right = x1 - pose.x
            val back = pose.z - z0; val front = z1 - pose.z
            val up = y1 - pose.y
            val least = min(min(min(left, right), min(back, front)), up)
            when (least) {
                left -> nx = -1f
                right -> nx = 1f
                back -> nz = -1f
                front -> nz = 1f
                else -> ny = 1f
            }
            push = least + RADIUS
        }
        pose.x += nx * push; pose.y += ny * push; pose.z += nz * push
        hit(nx, ny, nz, moveX * nx + moveZ * nz)
    }

    private fun hit(nx: Float, ny: Float, nz: Float, surfaceSpeed: Float) {
        val approaching = vx * nx + vy * ny + vz * nz - surfaceSpeed
        if (approaching >= 0f) return
        val force = -approaching
        val bounce = if (force > 1.5f) 1.25f else 1f
        vx -= nx * approaching * bounce; vy -= ny * approaching * bounce; vz -= nz * approaching * bounce
        val into = refX * nx + refZ * nz
        if (into < 0f) { refX -= nx * into; refZ -= nz * into }
        val pushing = refAx * nx + refAz * nz
        if (pushing < 0f) { refAx -= nx * pushing; refAz -= nz * pushing }
        val modelled = modelX * nx + modelZ * nz
        if (modelled < 0f) { modelX -= nx * modelled; modelZ -= nz * modelled }
        if (abs(ny) < 0.8f) holdingPosition = false
        if (force > 1.5f) impact = max(impact, force)
        if (force > 9f && phase != Phase.CRASHED) {
            crashes++
            change(Phase.CRASHED)
        }
    }
}
