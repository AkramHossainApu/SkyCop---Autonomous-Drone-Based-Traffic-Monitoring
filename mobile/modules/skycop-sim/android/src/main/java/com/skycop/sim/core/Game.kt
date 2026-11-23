package com.skycop.sim.core

/** Platform services the game needs. Called on the render thread. */
interface GameHost {
    fun exit(toLiveVideo: Boolean)
    fun settingsChanged()
    fun calibrationSaved(mapping: String)
    fun calibrationCleared()
    fun recenterHead()
    fun rumble(strength: Float)
    /** The pilot picked another map; the platform rebuilds the city and starts a new flight there. */
    fun mapChanged()
}

private object DetachedHost : GameHost {
    override fun exit(toLiveVideo: Boolean) = Unit
    override fun settingsChanged() = Unit
    override fun calibrationSaved(mapping: String) = Unit
    override fun calibrationCleared() = Unit
    override fun recenterHead() = Unit
    override fun rumble(strength: Float) = Unit
    override fun mapChanged() = Unit
}

enum class MenuItem(val label: String) {
    RESUME("RESUME FLIGHT"),
    EXIT("EXIT SIMULATION"),
    MAP("MAP"),
    TIME("TIME OF DAY"),
    WEATHER("WEATHER"),
    LAYOUT("CONTROL LAYOUT"),
    CONTROLS("SHOW CONTROLS"),
    CALIBRATE("CALIBRATE CONTROLLER"),
    DEFAULT_MAPPING("RESET CONTROLLER MAPPING"),
    SENSITIVITY("TURN SENSITIVITY"),
    DEADZONE("STICK DEADZONE"),
    INVERT_CLIMB("INVERT CLIMB STICK"),
    INVERT_LOOK("INVERT CAMERA TILT"),
    VR("DISPLAY"),
    HEAD("HEAD TRACKING"),
    SPACING("LENS SPACING"),
    COMFORT("COMFORT VIGNETTE"),
    SHADOWS("SHADOWS"),
    WIND("WIND"),
    SOUND("ROTOR SOUND"),
    RESTART("RESTART FLIGHT"),
}

enum class Screen { FLIGHT, MENU, CONTROLS, CALIBRATION }

class Game(val city: City, val settings: Settings, private var host: GameHost, var liveAvailable: Boolean) {
    val traffic = Traffic(city)
    val drone = DroneSim(city, traffic)
    val camera = CameraRig(city)
    val environment = Environment()
    val pedestrians = Pedestrians(city)
    val calibration = Calibration()
    val menu = MenuItem.values()

    var screen = Screen.FLIGHT
        private set
    var selected = 0
        private set
    var controllerName: String? = null
    var headSensorMissing = false
    var toast: String? = null
        private set
    var clock = 0f
        private set
    var propellerAngle = 0f
        private set
    /** Watching the live camera instead of flying; the flight waits, paused, until X or B. */
    var watchingLive = false
        private set
    /** Set by the renderer: a frame of live video has arrived. */
    var liveHasVideo = false
    /** The live picture is fixed to the view, not the room: no head tracking, always straight ahead. */
    val liveBasis = FloatArray(16).also { Mat4.identity(it) }
    var liveSince = 0f
        private set

    private val input = PilotInput()
    private var toastUntil = 0f
    private var repeatIn = 0f
    private var lastDirection = 0
    private var crashes = 0
    private var depletions = 0
    private var autoReturns = 0
    private var wasCharging = false

    private fun show(message: String, seconds: Float = 2.5f) {
        toast = message; toastUntil = clock + seconds
    }

    /** Hands a kept flight to a new window. */
    fun attach(newHost: GameHost, live: Boolean) {
        host = newHost; liveAvailable = live
    }

    /** Drops the reference to a window that is going away. */
    fun detach(oldHost: GameHost) {
        if (host === oldHost) host = DetachedHost
    }

    fun openMenu() {
        if (screen != Screen.MENU) {
            screen = Screen.MENU; selected = 0
        }
        calibration.cancel()
    }

    fun update(dt: Float, pad: PadState, pressed: Int, mapping: InputMapping, raw: FloatArray, head: FloatArray, tracked: Boolean) {
        clock += dt
        if (toast != null && clock > toastUntil) toast = null

        if (watchingLive && !liveAvailable) {
            watchingLive = false
            show("LIVE CAMERA DISCONNECTED")
        }
        when (screen) {
            Screen.FLIGHT -> if (watchingLive) watch(pressed) else fly(dt, pad, pressed)
            Screen.MENU -> navigate(dt, pad, pressed)
            Screen.CONTROLS -> if (pressed and (Button.A or Button.B or Button.START) != 0) screen = Screen.MENU
            Screen.CALIBRATION -> calibrate(dt, pressed, mapping, raw)
        }
        if (screen == Screen.FLIGHT && !watchingLive) {
            traffic.update(dt)
            pedestrians.update(dt)
            propellerAngle = (propellerAngle + drone.render.rotor * 31f * dt) % (2 * PI_F)
        }
        camera.update(if (screen == Screen.FLIGHT) dt else 0f, drone.render, settings.vr, tracked, head)
    }

    private fun fly(dt: Float, pad: PadState, pressed: Int) {
        if (pressed and Button.START != 0) {
            openMenu()
            return
        }
        if (pressed and Button.B != 0) {
            drone.toggleReturnHome()
            if (drone.phase == Phase.RETURN) show("RETURNING TO NEAREST DOCK")
            else if (drone.phase == Phase.FLYING) show("RETURN CANCELLED", 1.5f)
        }
        if (pressed and Button.Y != 0) {
            camera.next()
            show(camera.view.label)
        }
        if (pressed and Button.X != 0) {
            if (liveAvailable) watchLive() else show("NO LIVE CAMERA CONNECTED")
        }
        if (pressed and (Button.R3 or Button.SELECT) != 0) {
            camera.recenter(); host.recenterHead()
            show("VIEW RECENTERED", 1.2f)
        }
        var modeStep = 0
        if (pressed and Button.RIGHT != 0) modeStep++
        if (pressed and Button.LEFT != 0) modeStep--
        if (!settings.classicLayout) {
            if (pressed and Button.RB != 0) modeStep++
            if (pressed and Button.LB != 0) modeStep--
        }
        if (modeStep != 0) {
            val modes = FlightMode.values()
            drone.mode = modes[(drone.mode.ordinal + modeStep).coerceIn(0, modes.size - 1)]
            show(drone.mode.label, 1.5f)
        }

        ControlScheme.apply(pad, settings, input)
        var tilt = (if (pad.held and Button.UP != 0) 1f else 0f) - (if (pad.held and Button.DOWN != 0) 1f else 0f)
        var pan = 0f
        if (settings.classicLayout) {
            tilt += ControlScheme.curve(pad.rightY)
            pan = ControlScheme.curve(pad.rightX)
        }
        if (settings.invertLook) tilt = -tilt
        camera.look(pan * settings.sensitivity / 100f, tilt.coerceIn(-1f, 1f), dt)

        drone.yawSensitivity = settings.sensitivity / 100f
        drone.windEnabled = settings.wind
        environment.update(dt, settings)
        drone.windScale = environment.gust
        val before = drone.phase
        if (pressed and Button.A != 0) {
            if (drone.phase == Phase.LANDED && !drone.canTakeOff) show("BATTERY EMPTY - LET IT CHARGE ON A DOCK")
            drone.toggleTakeoffLanding()
        }
        drone.update(dt, input)
        if (drone.impact > 0f) {
            host.rumble((drone.impact / 9f).coerceIn(0.2f, 1f))
            drone.impact = 0f
        }
        if (drone.crashes != crashes) {
            crashes = drone.crashes
            show("CRASHED - RESPAWNING AT NEAREST DOCK", 3.4f)
        }
        if (drone.depletions != depletions) {
            depletions = drone.depletions
            show("BATTERY DEAD - RESPAWNING AT NEAREST DOCK", 3.6f)
        }
        if (drone.autoReturns != autoReturns) {
            autoReturns = drone.autoReturns
            show("LOW BATTERY - RETURNING TO NEAREST DOCK", 3f)
        }
        val charging = drone.charging
        if (wasCharging && !charging && drone.onDock) show("FULLY CHARGED", 2f)
        wasCharging = charging
        if (before == Phase.CRASHED && drone.phase == Phase.LANDED) camera.snap(drone.pose.yaw)
        // People put the headset on and then take off, so this is the direction they call forward.
        if (before == Phase.LANDED && drone.phase == Phase.TAKEOFF) host.recenterHead()
    }

    fun watchLive() {
        if (!liveAvailable) return
        watchingLive = true
        liveSince = clock
        screen = Screen.FLIGHT
        host.recenterHead()
    }

    private fun watch(pressed: Int) {
        if (pressed and Button.START != 0) { openMenu(); return }
        if (pressed and (Button.X or Button.B) != 0) {
            watchingLive = false
            show("SIMULATION", 1.2f)
        }
        if (pressed and (Button.R3 or Button.SELECT) != 0) {
            host.recenterHead()
            show("VIEW RECENTERED", 1.2f)
        }
    }

    private fun navigate(dt: Float, pad: PadState, pressed: Int) {
        if (pressed and (Button.B or Button.START) != 0) {
            screen = Screen.FLIGHT
            return
        }
        var direction = 0
        if (pad.held and Button.DOWN != 0 || pad.leftY < -0.6f) direction = Button.DOWN
        else if (pad.held and Button.UP != 0 || pad.leftY > 0.6f) direction = Button.UP
        else if (pad.held and Button.RIGHT != 0 || pad.leftX > 0.6f) direction = Button.RIGHT
        else if (pad.held and Button.LEFT != 0 || pad.leftX < -0.6f) direction = Button.LEFT
        // A tap shorter than one frame only exists in the pressed mask.
        var act = 0
        if (direction != lastDirection) {
            act = direction; repeatIn = 0.4f
        } else if (direction != 0) {
            repeatIn -= dt
            if (repeatIn <= 0f) { act = direction; repeatIn = 0.12f }
        }
        lastDirection = direction
        if (act == 0 && direction == 0) act = pressed and (Button.UP or Button.DOWN or Button.LEFT or Button.RIGHT)
        when (act) {
            Button.DOWN -> selected = (selected + 1) % menu.size
            Button.UP -> selected = (selected + menu.size - 1) % menu.size
            Button.LEFT -> adjust(menu[selected], -1)
            Button.RIGHT -> adjust(menu[selected], 1)
        }
        if (pressed and Button.A != 0) activate(selected)
    }

    fun activate(index: Int) {
        if (index !in menu.indices) return
        selected = index
        when (val item = menu[index]) {
            MenuItem.RESUME -> screen = Screen.FLIGHT
            MenuItem.CONTROLS -> screen = Screen.CONTROLS
            MenuItem.CALIBRATE -> {
                if (controllerName == null) show("CONNECT A CONTROLLER FIRST")
                else { screen = Screen.CALIBRATION; calibration.start() }
            }
            MenuItem.DEFAULT_MAPPING -> {
                host.calibrationCleared()
                show("AUTOMATIC MAPPING RESTORED")
            }
            MenuItem.RESTART -> {
                watchingLive = false
                drone.reset(); camera.snap(0f); host.recenterHead()
                screen = Screen.FLIGHT
            }
            MenuItem.EXIT -> host.exit(false)
            else -> adjust(item, 1)
        }
    }

    /** Touch scrolling in the menu: moves the selection, which scrolls the list with it. */
    fun scroll(rows: Int) {
        if (screen == Screen.MENU) selected = (selected + rows).coerceIn(0, menu.size - 1)
    }

    fun back() {
        when (screen) {
            Screen.FLIGHT -> openMenu()
            Screen.MENU -> screen = Screen.FLIGHT
            Screen.CONTROLS, Screen.CALIBRATION -> {
                calibration.cancel()
                screen = Screen.MENU
            }
        }
    }

    private fun adjust(item: MenuItem, by: Int) {
        val s = settings
        when (item) {
            MenuItem.LAYOUT -> s.classicLayout = !s.classicLayout
            MenuItem.INVERT_CLIMB -> s.invertClimb = !s.invertClimb
            MenuItem.INVERT_LOOK -> s.invertLook = !s.invertLook
            MenuItem.VR -> s.vr = !s.vr
            MenuItem.HEAD -> {
                s.headTracking = !s.headTracking
                host.recenterHead()
            }
            MenuItem.COMFORT -> s.comfort = !s.comfort
            MenuItem.SHADOWS -> s.shadows = !s.shadows
            MenuItem.WIND -> s.wind = !s.wind
            MenuItem.SOUND -> s.sound = !s.sound
            MenuItem.MAP -> {
                s.map = cycle(s.map, by, 0, MapData.FILES.size - 1)
                host.settingsChanged()
                host.mapChanged()
                return
            }
            MenuItem.TIME -> s.timeOfDay = cycle(s.timeOfDay, by, 0, Environment.TIMES.size - 1)
            MenuItem.WEATHER -> s.weather = cycle(s.weather, by, 0, Environment.WEATHERS.size - 1)
            MenuItem.SENSITIVITY -> s.sensitivity = cycle(s.sensitivity, by * 10, 50, 150)
            MenuItem.DEADZONE -> s.deadzone = cycle(s.deadzone, by * 2, 4, 30)
            MenuItem.SPACING -> s.lensSpacing = cycle(s.lensSpacing, by * 2, -10, 10)
            else -> return
        }
        host.settingsChanged()
    }

    private fun cycle(value: Int, step: Int, low: Int, high: Int): Int {
        val next = value + step
        return if (next > high) low else if (next < low) high else next
    }

    private fun calibrate(dt: Float, pressed: Int, mapping: InputMapping, raw: FloatArray) {
        if (pressed and Button.START != 0) {
            calibration.cancel()
            screen = Screen.MENU
            return
        }
        if (pressed and Button.B != 0) calibration.skip()
        if (calibration.update(raw, dt, mapping)) {
            host.calibrationSaved(mapping.encode())
            screen = Screen.MENU
            show("CONTROLLER CALIBRATED")
        }
    }

    fun padLost() {
        controllerName = null
        if (screen == Screen.CALIBRATION) {
            calibration.cancel()
            screen = Screen.MENU
        }
        if (screen == Screen.FLIGHT && drone.airborne) show("CONTROLLER LOST - HOVERING", 4f)
    }
}

