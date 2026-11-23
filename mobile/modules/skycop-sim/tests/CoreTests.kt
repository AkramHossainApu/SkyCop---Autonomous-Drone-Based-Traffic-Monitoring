// Plain-JVM tests for the simulation core: no Android device or test framework needed. Run ./run.sh
import com.skycop.sim.core.*
import kotlin.math.*

private var failures = 0
private var passed = 0

private fun check(name: String, ok: Boolean, detail: Any = "") {
    if (ok) passed++ else {
        failures++
        println("FAIL $name $detail")
    }
}

private fun near(name: String, actual: Float, expected: Float, tolerance: Float) =
    check(name, abs(actual - expected) <= tolerance, "got $actual, want $expected +/- $tolerance")

private fun loadMap(name: String) = MapData(java.io.File(System.getProperty("skycop.maps"), "$name.skymap").readBytes())
private val city = City(loadMap("bashundhara"))
private val sharedTraffic = Traffic(city)

private class Rig(wind: Boolean = true) {
    val input = PilotInput()
    val drone = DroneSim(city, sharedTraffic).apply { windEnabled = wind }

    fun run(seconds: Float, frame: Float = 1f / 60f) {
        repeat((seconds / frame).roundToInt()) { drone.update(frame, input) }
    }

    fun hoverHigh(): Rig {
        drone.toggleTakeoffLanding(); run(4f)
        input.climb = 1f; run(16f)
        input.climb = 0f; run(4f)
        return this
    }
}

private fun mappingTests() {
    fun axes(vararg list: Int) = BooleanArray(Axis.COUNT) { it in list }
    val pad = PadState()

    fun layout(name: String, available: BooleanArray, vendor: Int, rightX: Int, rightY: Int, lt: Int, rt: Int, rest: Float) {
        val mapping = InputMapping().apply { configure(available, vendor) }
        val raw = FloatArray(Axis.COUNT)
        mapping.read(raw, 0, 0.1f, pad)
        check("$name neutral before first event", pad.leftTrigger == 0f && pad.rightTrigger == 0f && pad.rightX == 0f)
        raw[lt] = rest; raw[rt] = rest; raw[rightX] = 1f
        mapping.read(raw, 0, 0.1f, pad)
        near("$name right stick right", pad.rightX, 1f, 0.001f)
        check("$name triggers at rest", pad.leftTrigger == 0f && pad.rightTrigger == 0f)
        raw[rightX] = 0f; raw[rightY] = -1f
        mapping.read(raw, 0, 0.1f, pad)
        near("$name right stick up", pad.rightY, 1f, 0.001f)
        raw[rightY] = 0f; raw[rt] = 1f
        mapping.read(raw, 0, 0.1f, pad)
        check("$name right trigger", pad.rightTrigger > 0.99f && pad.leftTrigger == 0f && pad.rightX == 0f && pad.rightY == 0f)
        raw[rt] = rest; raw[lt] = 1f
        mapping.read(raw, 0, 0.1f, pad)
        check("$name left trigger", pad.leftTrigger > 0.99f && pad.rightTrigger == 0f)
    }
    with(Axis) {
        layout("key layout", axes(X, Y, Z, RZ, LTRIGGER, RTRIGGER), 1, Z, RZ, LTRIGGER, RTRIGGER, 0f)
        layout("pedals", axes(X, Y, Z, RZ, BRAKE, GAS), 1, Z, RZ, BRAKE, GAS, 0f)
        layout("dual reported", axes(X, Y, Z, RZ, LTRIGGER, RTRIGGER, BRAKE, GAS), 1, Z, RZ, BRAKE, GAS, 0f)
        layout("extra RX/RY", axes(X, Y, Z, RZ, RX, RY, LTRIGGER, RTRIGGER), 1, Z, RZ, LTRIGGER, RTRIGGER, 0f)
        layout("raw Xbox", axes(X, Y, Z, RZ, RX, RY), 0x045e, RX, RY, Z, RZ, -1f)
        layout("raw PlayStation", axes(X, Y, Z, RZ, RX, RY), 0x054c, Z, RZ, RX, RY, -1f)
        layout("unknown PlayStation clone", axes(X, Y, Z, RZ, RX, RY), 1, Z, RZ, RX, RY, -1f)

        val m = InputMapping().apply { configure(axes(X, Y, Z, RZ), 1) }
        val raw = FloatArray(COUNT)
        raw[Y] = -1f
        repeat(10_000) { m.read(raw, 0, 0.1f, pad) }
        near("held stick never expires", pad.leftY, 1f, 0.001f)
        raw[Y] = -0.08f
        m.read(raw, 0, 0.1f, pad)
        check("drift is ignored", pad.leftY == 0f)
        m.read(raw, Button.RT, 0.1f, pad)
        near("digital trigger", pad.rightTrigger, 1f, 0.001f)

        val analog = InputMapping().apply { configure(axes(X, Y, Z, RZ, LTRIGGER, RTRIGGER), 1) }
        raw.fill(0f); raw[RTRIGGER] = 0.5f
        analog.read(raw, Button.RT, 0.1f, pad)
        check("analog trigger not forced to 100%", pad.rightTrigger in 0.4f..0.55f, pad.rightTrigger)

        val odd = InputMapping().apply { configure(axes(X, Y, Z, RZ, RX, RY, THROTTLE, RUDDER), 1) }
        val wizard = Calibration().apply { start() }
        val source = intArrayOf(RX, RY, THROTTLE, RUDDER, Z, RZ)
        val toward = floatArrayOf(1f, -1f, -1f, 1f, 1f, 1f)
        val restAt = floatArrayOf(0f, 0f, 0f, 0f, -1f, -1f)
        raw.fill(0f)
        fun wait(seconds: Float) = repeat((seconds * 60).roundToInt()) { wizard.update(raw, 1f / 60, odd) }
        wait(0.7f)
        for (i in 0..5) {
            raw[source[i]] = toward[i]; wait(0.3f)
            raw[source[i]] = restAt[i]; wait(0.9f)
        }
        check("calibration finishes", !wizard.active && odd.calibrated)
        val saved = InputMapping()
        check("calibration reloads", saved.decode(odd.encode()))
        raw[THROTTLE] = -1f; raw[RZ] = 1f
        saved.read(raw, 0, 0.1f, pad)
        check("calibrated axes", pad.rightX > 0.99f && pad.rightTrigger > 0.99f && pad.leftTrigger == 0f)
    }

    val settings = Settings()
    val pilot = PilotInput()
    pad.clear(); pad.leftY = 1f; pad.rightX = 1f
    ControlScheme.apply(pad, settings, pilot)
    check("pilot layout", pilot.climb == 1f && pilot.strafe == 1f && pilot.forward == 0f)
    settings.classicLayout = true
    ControlScheme.apply(pad, settings, pilot)
    check("classic layout", pilot.forward == 1f && pilot.climb == 0f && pilot.strafe == 0f)
    settings.sensitivity = 130
    check("settings round trip", Settings().apply { decode(settings.encode()) }.encode() == settings.encode())
}

private fun flightTests() {
    Rig().apply {
        run(5f)
        check("stays parked", drone.phase == Phase.LANDED && drone.homeDistance < 0.001f)
        drone.toggleTakeoffLanding(); run(8f)
        check("takes off", drone.phase == Phase.FLYING && drone.height in 2f..3.3f, drone.height)
        val x = drone.pose.x; val y = drone.pose.y; val z = drone.pose.z
        run(30f)
        check("hover holds in wind", hypot(drone.pose.x - x, drone.pose.z - z) < 0.5f && abs(drone.pose.y - y) < 0.1f)
        drone.toggleTakeoffLanding(); run(9f)
        check("lands", drone.phase == Phase.LANDED && drone.height < 0.1f && drone.crashes == 0, drone.phase)
    }

    for (mode in FlightMode.values().filter { it.gps }) Rig(false).hoverHigh().apply {
        drone.mode = mode
        val x = drone.pose.x; val z = drone.pose.z
        input.forward = 1f; run(0.5f)
        check("$mode leans nose down", drone.pose.pitch > 0.05f, drone.pose.pitch)
        run(5.5f)
        near("$mode reaches its speed", drone.speed, mode.speed, mode.speed * 0.06f)
        check("$mode forward is north", drone.pose.z < z - 8 && abs(drone.pose.x - x) < 0.3f)
        input.forward = 0f; run(5f)
        check("$mode brakes to a hover", drone.speed < 0.2f, drone.speed)
    }

    Rig(false).hoverHigh().apply {
        val x = drone.pose.x
        input.strafe = 1f; run(2f)
        check("stick right moves and banks right", drone.pose.x > x + 3 && drone.pose.roll > 0.01f)
    }

    Rig(false).hoverHigh().apply {
        input.yaw = 1f
        while (drone.pose.yaw < PI_F / 2) run(1f / 60f)
        input.yaw = 0f; run(1.5f)
        check("yaw right turns clockwise", drone.pose.yaw in 1.5f..2.0f, drone.pose.yaw)
        val x = drone.pose.x
        input.forward = 1f; run(3f)
        check("forward follows the nose", drone.pose.x > x + 10)
        val m = FloatArray(16)
        val p = drone.pose
        Mat4.pose(m, 0f, 0f, 0f, p.yaw, 0f, 0f)
        check("model nose matches motion", -m[8] * drone.vx + -m[10] * drone.vz > drone.speed * 0.95f)
    }

    val m = FloatArray(16)
    Mat4.pose(m, 0f, 0f, 0f, 0.7f, 0.4f, -0.3f)
    for (a in 0..2) for (b in 0..2) {
        val dot = m[a * 4] * m[b * 4] + m[a * 4 + 1] * m[b * 4 + 1] + m[a * 4 + 2] * m[b * 4 + 2]
        near("pose is orthonormal $a$b", dot, if (a == b) 1f else 0f, 0.0001f)
    }
    Mat4.pose(m, 0f, 0f, 0f, 0f, 0.4f, 0f)
    check("pitch lowers the nose", -m[9] < 0f && m[6] < 0f, "${m[9]} ${m[6]}")
    Mat4.pose(m, 0f, 0f, 0f, 0f, 0f, 0.4f)
    check("roll lowers the right side", m[1] < 0f && m[4] > 0f)

    val ends = listOf(30, 60, 90, 120, 144).map { rate ->
        Rig().run {
            drone.toggleTakeoffLanding(); run(4f, 1f / rate)
            input.climb = 1f; run(12f, 1f / rate)
            input.climb = 0f; input.forward = 0.8f; input.yaw = 0.2f; run(5f, 1f / rate)
            floatArrayOf(drone.pose.x, drone.pose.y, drone.pose.z)
        }
    }
    for (end in ends) for (i in 0..2) near("same flight at any frame rate", end[i], ends[1][i], 0.6f)

    val wall = wallAhead()
    check("found a tall building to fly at", wall != null)
    if (wall != null) {
        Rig().apply {
            drone.placeAt(wall[0], 10f, wall[1], wall[2])
            input.forward = 1f; run(12f)
            val gap = city.raycast(drone.pose.x, drone.pose.y, drone.pose.z, sin(wall[2]), 0f, -cos(wall[2]), 30f)
            check("obstacle assist stops before the wall", drone.crashes == 0 && gap in 0.9f..4.5f, "${drone.crashes} $gap")
        }
        Rig().apply {
            drone.placeAt(wall[0], 10f, wall[1], wall[2])
            drone.mode = FlightMode.SPORT
            input.forward = 1f; run(4f)
            check("sport mode can crash", drone.crashes == 1, drone.crashes)
            input.forward = 0f; run(5f)
            check("crash respawns on a dock", drone.phase == Phase.LANDED && drone.onDock, "${drone.phase} ${drone.dockDistance}")
        }
    }
    Rig().hoverHigh().apply {
        input.forward = 1f; input.strafe = 0.5f; run(12f)
        input.forward = 0f; input.strafe = 0f; run(2f)
        drone.toggleReturnHome(); run(70f)
        check("return lands on the nearest dock", drone.phase == Phase.LANDED && drone.onDock, "${drone.phase} ${drone.dockDistance}")
        check("return home does not crash", drone.crashes == 0)
    }
    dockTests()
    Rig().hoverHigh().apply {
        input.forward = 1f; run(90f)
        check("geofence holds", abs(drone.pose.z) < City.BOUNDS + 3 && drone.pose.y < 123f)
    }
    Rig().apply {
        // A long stick-mashing soak: state must stay finite and above ground.
        val random = kotlin.random.Random(4)
        var valid = true
        repeat(400) {
            input.forward = random.nextFloat() * 2 - 1; input.strafe = random.nextFloat() * 2 - 1
            input.yaw = random.nextFloat() * 2 - 1; input.climb = random.nextFloat() * 2 - 0.9f
            drone.mode = FlightMode.values()[random.nextInt(4)]
            run(1.5f)
            val p = drone.pose
            valid = valid && p.x.isFinite() && p.y.isFinite() && p.z.isFinite() && p.y >= 0f && abs(p.x) < City.BOUNDS + 50 && abs(p.z) < City.BOUNDS + 50
        }
        check("ten minute soak", valid)
    }
}

/** A point 35 m in front of a tall building's wall with a clear line to it, and the heading that faces it. */
private fun wallAhead(): FloatArray? {
    val map = city.map
    val home = city.homeDock
    val order = (0 until map.buildingCount).sortedBy { hypot(map.boxX[it] - city.docks[home * 3], map.boxZ[it] - city.docks[home * 3 + 2]) }
    for (i in order) {
        if (map.floors[i] * map.floorHeight[i] < 22f || map.boxHalfX[i] < 6f || map.boxHalfZ[i] < 6f) continue
        for (side in intArrayOf(1, -1)) {
            val nx = cos(map.boxAngle[i]) * side; val nz = sin(map.boxAngle[i]) * side
            val sx = map.boxX[i] + nx * (map.boxHalfX[i] + 35f); val sz = map.boxZ[i] + nz * (map.boxHalfX[i] + 35f)
            if (abs(sx) > City.BOUNDS - 50 || abs(sz) > City.BOUNDS - 50) continue
            if (city.support(sx, sz, 30f, 1f) > 0.5f) continue
            val hit = city.raycast(sx, 10f, sz, -nx, 0f, -nz, 40f)
            if (abs(hit - 35f) < 0.5f) return floatArrayOf(sx, sz, atan2(-nx, nz))
        }
    }
    return null
}

private fun dockTests() {
    check("docks at the junctions", city.dockCount > 50, city.dockCount)
    val some = city.dockCount / 2
    check("nearest dock", city.nearestDock(city.docks[some * 3] + 1f, city.docks[some * 3 + 2] - 1f) == some)
    check("starts on a dock, charged", Rig().drone.let { it.onDock && it.battery == 100f && !it.charging })

    Rig(false).hoverHigh().apply {
        val before = drone.battery
        run(120f)
        near("hover drains 10 minutes per charge", before - drone.battery, 120f * DroneSim.HOVER_DRAIN, 2.5f)
    }

    // Hovering until the battery is low sends it to a dock by itself, where it charges in seconds.
    Rig(false).hoverHigh().apply {
        input.forward = 1f; run(8f); input.forward = 0f
        var t = 0f
        while (drone.autoReturns == 0 && t < 700f) { run(1f); t += 1f }
        check("low battery returns by itself", drone.autoReturns == 1 && drone.phase == Phase.RETURN, "${drone.phase} $t")
        run(60f)
        check("auto return lands on a dock", drone.phase == Phase.LANDED && drone.onDock, "${drone.phase} ${drone.dockDistance}")
        check("charges on the dock", drone.battery > 50f || drone.charging, drone.battery)
        run(10.5f)
        check("full charge within ten seconds", drone.battery == 100f, drone.battery)
    }

    // Ignoring the warning: the battery dies in the air and the drone comes back on the nearest dock.
    Rig(false).hoverHigh().apply {
        input.forward = 1f; run(8f)
        input.forward = 0.5f
        var t = 0f
        while (drone.depletions == 0 && t < 700f) { run(1f); t += 1f }
        input.forward = 0f
        check("battery runs flat", drone.depletions == 1, t)
        run(5f)
        check("flat battery respawns on a dock", drone.phase == Phase.LANDED && drone.onDock, "${drone.phase} ${drone.dockDistance}")
        check("comes back with little charge", drone.battery < 30f && drone.charging, drone.battery)
        run(10.5f)
        check("dock recharges it", drone.battery == 100f, drone.battery)
    }
}

/** Do two vehicles' footprints overlap? Separating-axis test on their rotated rectangles, shrunk a little. */
private fun overlap(t: Traffic, a: Int, b: Int): Boolean {
    if (abs(t.y[a] - t.y[b]) > 2f) return false
    val ax = sin(t.yaw[a]); val az = -cos(t.yaw[a]); val bx = sin(t.yaw[b]); val bz = -cos(t.yaw[b])
    val dx = t.x[b] - t.x[a]; val dz = t.z[b] - t.z[a]
    val la = t.length(a) / 2 - 0.2f; val wa = t.width(a) / 2 - 0.15f
    val lb = t.length(b) / 2 - 0.2f; val wb = t.width(b) / 2 - 0.15f
    for ((ux, uz) in listOf(ax to az, -az to ax, bx to bz, -bz to bx)) {
        val ra = la * abs(ax * ux + az * uz) + wa * abs(-az * ux + ax * uz)
        val rb = lb * abs(bx * ux + bz * uz) + wb * abs(-bz * ux + bx * uz)
        if (abs(dx * ux + dz * uz) > ra + rb) return false
    }
    return true
}

private val overlapKinds = IntArray(4)
private fun trafficTests(name: String, t: Traffic) {
    overlapKinds.fill(0)
    check("$name: lots of traffic", t.count > 1200, t.count)
    var overlaps = 0
    var queued = 0
    var moving = 0
    val cells = HashMap<Int, MutableList<Int>>()
    val startX = t.x.copyOf(); val startZ = t.z.copyOf()
    repeat(4 * 60 * 30) { frame ->
        t.update(1f / 30f)
        if (frame % 10 != 0) return@repeat
        queued = max(queued, t.speed.count { it < 0.5f })
        cells.clear()
        for (car in 0 until t.count) {
            val key = floor(t.x[car] / 12f).toInt() * 1000 + floor(t.z[car] / 12f).toInt()
            cells.getOrPut(key) { ArrayList() }.add(car)
        }
        for (a in 0 until t.count) for (cx in -1..1) for (cz in -1..1) {
            val key = (floor(t.x[a] / 12f).toInt() + cx) * 1000 + floor(t.z[a] / 12f).toInt() + cz
            for (b in cells[key] ?: continue) if (b > a && overlap(t, a, b)) {
                overlaps++
                val dot = sin(t.yaw[a]) * sin(t.yaw[b]) + cos(t.yaw[a]) * cos(t.yaw[b])
                val kind = if (dot > 0.7f) 0 else if (dot < -0.7f) 1 else 2
                overlapKinds[kind]++
                if (frame == 4 * 60 * 30 - 10 && overlapKinds[3] < 12) {
                    overlapKinds[3]++
                    println("  overlap ${t.type[a]}/${t.type[b]} at ${t.x[a].toInt()},${t.z[a].toInt()} speeds ${t.speed[a]} ${t.speed[b]} dot $dot")
                }
            }
        }
    }
    for (car in 0 until t.count) if (hypot(t.x[car] - startX[car], t.z[car] - startZ[car]) > 60f) moving++
    println("$name overlaps: same way ${overlapKinds[0]}, opposite ${overlapKinds[1]}, crossing ${overlapKinds[2]}")
    // Sampled 720 times: on average fewer than one vehicle in 150 touching another. What is left is
    // where the map itself has roads on top of each other, like the ramps of the Kuril interchange.
    check("$name: vehicles hardly ever overlap", overlaps / 720f < t.count / 150f, overlaps / 720f)
    check("$name: traffic gets around", moving > t.count / 3, moving)
    check("$name: queues and jams form", queued > t.count / 8, queued)
    check("$name: all on the map", (0 until t.count).all { abs(t.x[it]) <= City.HALF + 5f && abs(t.z[it]) <= City.HALF + 5f && t.x[it].isFinite() })
    var bothGreen = false
    for (tenth in 0..520) for (g in 0..1) {
        if (Signals.phase(tenth / 10f, 0, g) != 2 && Signals.phase(tenth / 10f, 1, g) != 2) bothGreen = true
    }
    check("signals never conflict", !bothGreen)

}

private fun headTests() {
    val pose = HeadPose()
    val world = FloatArray(16); val out = FloatArray(16); val centered = FloatArray(16)
    fun forward(m: FloatArray) = floatArrayOf(-m[8], -m[9], -m[10])

    Mat4.pose(world, 0f, 0f, 0f, 1.1f, 0f, 0f)
    pose.resolve(centered, world, 0f, 0f, 0f, 0f, true)
    near("recenter looks ahead", forward(centered)[2], -1f, 0.001f)

    // yaw right, yaw left, look down, look up, tilt right, tilt left
    val poses = arrayOf(
        floatArrayOf(1.4f, 0f, 0f), floatArrayOf(-1.4f, 0f, 0f), floatArrayOf(0f, 1.2f, 0f),
        floatArrayOf(0f, -1.2f, 0f), floatArrayOf(0f, 0f, 0.5f), floatArrayOf(0f, 0f, -0.5f),
        floatArrayOf(3.0f, 0.6f, 0.2f),
    )
    val expected = FloatArray(16)
    for (p in poses) {
        Mat4.pose(world, 0f, 0f, 0f, 1.1f + p[0], p[1], p[2])
        pose.resolve(out, world, 0f, 0f, 0f, 0f, false)
        Mat4.pose(expected, 0f, 0f, 0f, p[0], p[1], p[2])
        for (i in 0..10) near("head is one-to-one ${p.toList()} [$i]", out[i], expected[i], 0.001f)
    }

    Mat4.pose(world, 0f, 0f, 0f, 1.1f, 0f, 0f)
    pose.resolve(out, world, 0f, -2f, 0f, 0.05f, false)
    near("predicts a turn to the right", forward(out)[0], sin(0.1f), 0.002f)
    pose.resolve(out, world, 2f, 0f, 0f, 0.05f, false)
    near("predicts looking up", forward(out)[1], sin(0.1f), 0.002f)
    pose.resolve(out, world, 0.004f, 0.004f, 0f, 0.05f, false)
    check("no shimmer at rest", hypot(forward(out)[0], forward(out)[1]) < 0.0001f)

    // Compass-free orientation creeps in heading on a still head; the view must not follow it.
    val steady = HeadPose()
    Mat4.pose(world, 0f, 0f, 0f, 0.3f, 0f, 0f)
    steady.resolve(out, world, 0f, 0f, 0f, 0f, true)
    for (frame in 1..2000) {
        Mat4.pose(world, 0f, 0f, 0f, 0.3f + frame * 0.0005f, 0f, 0f)
        steady.resolve(out, world, 0.004f, 0.003f, 0.002f, 0f, false)
    }
    near("still head does not drift", forward(out)[0], 0f, 0.002f)
    // A real turn, with the gyroscope reporting it, still turns the view one to one.
    for (frame in 1..60) {
        Mat4.pose(world, 0f, 0f, 0f, 1.3f + frame * 0.01f, 0f, 0f)
        steady.resolve(out, world, 0f, -0.6f, 0f, 0f, false)
    }
    val turned = atan2(forward(out)[0], -forward(out)[2])
    near("head turn still follows", turned, 0.6f, 0.02f)

    for (pitch in floatArrayOf(1.55f, -1.55f)) {
        Mat4.pose(world, 0f, 0f, 0f, 0.8f, pitch, 0f)
        val fresh = HeadPose()
        fresh.resolve(out, world, 0f, 0f, 0f, 0f, true)
        Mat4.pose(world, 0f, 0f, 0f, 0.8f, 0f, 0f)
        fresh.resolve(out, world, 0f, 0f, 0f, 0f, false)
        near("recenter while looking vertical $pitch", forward(out)[2], -1f, 0.01f)
    }

    val drone = DronePose().apply { x = 36f; y = 90f; z = 30f; yaw = 0.9f }
    val head = FloatArray(16)
    for (view in View.values()) {
        val rig = CameraRig(city)
        while (rig.view != view) rig.next()
        rig.snap(drone.yaw)
        repeat(30) { rig.look(0f, -1f, 1f / 60) }
        Mat4.pose(head, 0f, 0f, 0f, 1f, 0f, 0f)
        rig.update(1f / 60, drone, true, true, head)
        near("$view turning the head keeps the horizon level", rig.basis[1], 0f, 0.0001f)
        near("$view turning the head does not change pitch", rig.basis[9], 0f, 0.0001f)
        near("$view view yaw is heading plus head", atan2(-rig.basis[8], rig.basis[10]), 1.9f, 0.001f)
    }
}

private fun smoothnessTests() {
    for (mode in FlightMode.values().filter { it.gps }) Rig(false).hoverHigh().apply {
        drone.mode = mode
        input.forward = 1f; run(5f)
        input.forward = 0f
        var furthest = drone.pose.z; var back = 0f
        repeat(8 * 60) {
            run(1f / 60)
            furthest = min(furthest, drone.pose.z); back = max(back, drone.pose.z - furthest)
        }
        check("$mode does not spring back on release", back < 0.06f, back)
        input.climb = 1f; run(2f); input.climb = 0f
        var highest = drone.pose.y; var sink = 0f
        repeat(4 * 60) {
            run(1f / 60)
            highest = max(highest, drone.pose.y); sink = max(sink, highest - drone.pose.y)
        }
        check("$mode does not bounce in height", sink < 0.06f, sink)
        input.yaw = 1f; run(1.5f); input.yaw = 0f
        val released = drone.pose.yaw
        run(1.5f)
        check("$mode turn stops promptly", drone.pose.yaw - released < 7f * DEG, (drone.pose.yaw - released) / DEG)
    }
}

private fun textureTests() {
    val pixels = Textures.get()
    val size = Textures.SIZE
    check("color size", pixels.color.capacity() == Tex.values().size * size * size * 4)
    for (tex in Tex.values()) {
        val start = tex.ordinal * size * size * 4
        var low = 255; var high = 0
        for (i in start until start + size * size * 4 step 4) {
            val green = pixels.color.get(i + 1).toInt() and 255
            low = min(low, green); high = max(high, green)
        }
        check("$tex has detail", high - low > 20, "$low..$high")
    }
    for (tex in arrayOf(Tex.BRICK_FLATS, Tex.PLASTER_FLATS, Tex.OFFICE, Tex.CURTAIN_WALL, Tex.STONE_TOWER, Tex.SHOPS)) {
        check("$tex lines up with the building grid", tex.width % 3f == 0f && abs(tex.height / 3.3f - (tex.height / 3.3f).roundToInt()) < 0.001f)
    }
}

private fun pacerTests() {
    val random = kotlin.random.Random(8)
    val pacer = FramePacer(1f / 60)
    var real = 0f; var simulated = 0f; var worst = 0f
    repeat(3000) {
        val raw = 1f / 60 + (random.nextFloat() - 0.5f) * 0.006f
        val out = pacer.next(raw)
        real += raw; simulated += out
        worst = max(worst, abs(out - 1f / 60))
    }
    check("pacer removes jitter", worst < 0.0011f, worst)
    near("pacer keeps real time", simulated, real, 0.05f)
}

/** Jams must clear: after twelve simulated minutes no vehicle may have stood still for more than four. */
private fun gridlockTests(name: String, city: City) {
    val t = Traffic(city)
    val still = FloatArray(t.count)
    var lastX = t.x.copyOf(); var lastZ = t.z.copyOf()
    var longest = 0f
    repeat(12 * 60) {
        repeat(60) { t.update(1f / 60) }
        for (c in 0 until t.count) {
            still[c] = if (hypot(t.x[c] - lastX[c], t.z[c] - lastZ[c]) < 0.05f) still[c] + 1f else 0f
            longest = max(longest, still[c])
        }
        lastX = t.x.copyOf(); lastZ = t.z.copyOf()
    }
    check("$name traffic never locks up", longest < 240f, "longest wait ${longest}s")
}

fun main() {
    mappingTests()
    flightTests()
    trafficTests("bashundhara", Traffic(city))
    val other = City(loadMap("mohammadpur"))
    check("mohammadpur builds", other.chunks.sumOf { it.mesh.vertices } > 100000 && other.dockCount > 50)
    trafficTests("mohammadpur", Traffic(other))
    gridlockTests("bashundhara", city)
    gridlockTests("mohammadpur", other)
    println("mohammadpur: ${other.chunks.sumOf { it.mesh.vertices }} + ${other.chunks.sumOf { it.detail.vertices }} detail + ${other.chunks.sumOf { it.shade.vertices }} shade vertices, tallest ${other.tallest} m")
    headTests()
    smoothnessTests()
    textureTests()
    pacerTests()
    val vertices = city.chunks.sumOf { it.mesh.vertices }
    val detail = city.chunks.sumOf { it.detail.vertices }
    println("city: $vertices + $detail detail vertices, tallest ${city.tallest} m")
    println("$passed passed, $failures failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
