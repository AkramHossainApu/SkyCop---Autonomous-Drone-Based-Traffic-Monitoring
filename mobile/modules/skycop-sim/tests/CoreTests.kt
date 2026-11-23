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

private val city = City()

private class Rig(wind: Boolean = true) {
    val input = PilotInput()
    val drone = DroneSim(city, Traffic()).apply { windEnabled = wind }

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

    Rig().apply {
        drone.toggleTakeoffLanding(); run(4f)
        input.forward = -1f; run(12f)
        check("obstacle assist stops before the wall", drone.crashes == 0 && drone.pose.z in 38f..43.2f, drone.pose.z)
    }
    Rig().apply {
        drone.toggleTakeoffLanding(); run(4f)
        drone.mode = FlightMode.SPORT
        input.forward = -1f; run(3f)
        check("sport mode can crash", drone.crashes == 1, drone.crashes)
        input.forward = 0f; run(5f)
        check("crash resets home", drone.phase == Phase.LANDED && drone.homeDistance < 0.01f)
    }
    Rig().hoverHigh().apply {
        input.forward = 1f; input.strafe = 0.5f; run(12f)
        input.forward = 0f; input.strafe = 0f; run(2f)
        drone.toggleReturnHome(); run(70f)
        check("return home lands on pad", drone.phase == Phase.LANDED && drone.homeDistance < 2f, "${drone.phase} ${drone.homeDistance}")
        check("return home does not crash", drone.crashes == 0)
    }
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
            valid = valid && p.x.isFinite() && p.y.isFinite() && p.z.isFinite() && p.y >= 0f && abs(p.x) < 620 && abs(p.z) < 620
        }
        check("ten minute soak", valid)
    }
}

private fun trafficTests() {
    val traffic = Traffic()
    var overlaps = 0
    repeat(5 * 60 * 30) { frame ->
        traffic.update(1f / 30f)
        if (frame % 5 != 0) return@repeat
        for (a in 0 until Traffic.COUNT) for (b in a + 1 until Traffic.COUNT) {
            val dx = abs(traffic.x[a] - traffic.x[b]); val dz = abs(traffic.z[a] - traffic.z[b])
            if (dx > 11 || dz > 11) continue
            val ax = abs(sin(traffic.yaw[a])) > 0.5f; val bx = abs(sin(traffic.yaw[b])) > 0.5f
            val w = ((if (ax) traffic.length(a) else traffic.width(a)) + (if (bx) traffic.length(b) else traffic.width(b))) / 2
            val d = ((if (ax) traffic.width(a) else traffic.length(a)) + (if (bx) traffic.width(b) else traffic.length(b))) / 2
            if (dx < w - 0.15f && dz < d - 0.15f) overlaps++
        }
    }
    check("cars never overlap", overlaps == 0, overlaps)
    check("traffic keeps flowing", traffic.speed.count { it > 2f } > Traffic.COUNT / 4)
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

fun main() {
    mappingTests()
    flightTests()
    trafficTests()
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
