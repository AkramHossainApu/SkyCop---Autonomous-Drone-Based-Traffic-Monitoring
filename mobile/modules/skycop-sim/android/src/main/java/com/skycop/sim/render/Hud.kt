package com.skycop.sim.render

import com.skycop.sim.core.*
import com.skycop.sim.input.TouchSticks
import kotlin.math.*

/** Flight instruments, menu and help. Coordinates are centered, y down, 540 units per eye height. */
internal class Hud(private val batch: HudBatch) {
    companion object {
        const val HALF_HEIGHT = 270f
        private const val WHITE = 0xF2F6F7
        private const val MUTED = 0x9DB0B8
        private const val ACCENT = 0x58E6FF
        private const val WARN = 0xFFC247
        private const val DANGER = 0xFF5A4E
        private const val GOOD = 0x62E58F
        private const val PANEL = 0x0A1419
        private const val ROWS = 9
        private const val ROW_HEIGHT = 34f
        private const val LIST_TOP = -158f
    }

    private val line = StringBuilder(64)
    private var halfWidth = 300f
    private var menuHalf = 300f
    private var firstRow = 0

    /** Menus shrink in a headset, where the edges of each lens are unreadable. */
    var scale = 1f
        private set

    private val pilotHelp = arrayOf(
        "LEFT STICK", "UP / DOWN + TURN",
        "RIGHT STICK", "MOVE IN ANY DIRECTION",
        "LT / RT", "DOWN / UP",
        "LB / RB", "SLOWER / FASTER MODE",
        "D-PAD UP/DOWN", "CAMERA HEIGHT",
        "A", "TAKE OFF / LAND",
        "B", "RETURN HOME",
        "X", "LIVE CAMERA",
        "Y", "CHANGE VIEW",
        "R3 / SELECT", "RECENTER VIEW",
        "START", "MENU",
    )
    private val classicHelp = arrayOf(
        "LEFT STICK", "FORWARD / BACK + TURN",
        "RIGHT STICK", "LOOK AROUND",
        "LT / RT", "DOWN / UP",
        "LB / RB", "SLIDE LEFT / RIGHT",
        "D-PAD LEFT/RIGHT", "FLIGHT MODE",
        "A", "TAKE OFF / LAND",
        "B", "RETURN HOME",
        "X", "LIVE CAMERA",
        "Y", "CHANGE VIEW",
        "R3 / SELECT", "RECENTER VIEW",
        "START", "MENU",
    )

    fun build(game: Game, pad: PadState, eyeAspect: Float, fps: Int, touch: TouchSticks) {
        scale = if (game.settings.vr && game.screen != Screen.FLIGHT) 0.8f else 1f
        halfWidth = HALF_HEIGHT * eyeAspect / scale
        menuHalf = min(310f, halfWidth * 0.86f)
        batch.begin()
        when (game.screen) {
            Screen.FLIGHT -> {
                flight(game)
                if (!game.settings.vr && game.controllerName == null) sticks(touch)
            }
            Screen.MENU -> menu(game, pad, fps)
            Screen.CONTROLS -> controls(game)
            Screen.CALIBRATION -> calibration(game)
        }
        val toast = game.toast
        if (toast != null) {
            val y = if (game.screen == Screen.FLIGHT) -100f else 222f
            val w = toast.length * 10.5f + 28f
            batch.color(PANEL, 0.82f); batch.rect(-w / 2, y - 5, w, 32f)
            batch.color(ACCENT); batch.text(toast, 0f, y, 22f, 0.5f)
        }
        batch.upload()
    }

    private fun panel(x: Float, y: Float, width: Float, height: Float) {
        batch.color(PANEL, 0.5f)
        batch.rect(x, y, width, height)
    }

    private fun flight(game: Game) {
        // In a headset only the middle of each eye is readable.
        val vr = game.settings.vr
        val w = if (vr) min(250f, halfWidth * 0.76f) else halfWidth - 28f
        val h = if (vr) 178f else HALF_HEIGHT - 18f
        val drone = game.drone; val pose = drone.render

        panel(-w, -h, 152f, 50f)
        batch.color(ACCENT); batch.text(drone.mode.label, -w + 8, -h + 5, 19f)
        batch.color(WHITE); batch.text(drone.phase.label, -w + 8, -h + 25, 17f)

        panel(w - 128, -h, 128f, 70f)
        val battery = drone.battery
        line.setLength(0); line.append("BATT ").append(ceil(battery).toInt()).append('%')
        batch.color(if (battery < 10) DANGER else if (battery < 25) WARN else GOOD)
        batch.text(line, w - 8, -h + 5, 19f, 1f)
        batch.rect(w - 120, -h + 27, 112f * battery / 100f, 4f)
        line.setLength(0); line.append("HOME ").append(drone.homeDistance.toInt()).append('M')
        batch.color(MUTED); batch.text(line, w - 8, -h + 38, 17f, 1f)

        compass(game, -h)

        panel(-w, h - 62, 120f, 62f)
        batch.color(MUTED); batch.text("SPEED KM/H", -w + 8, h - 58, 15f)
        line.setLength(0); line.append((drone.speed * 3.6f).roundToInt())
        batch.color(WHITE); batch.text(line, -w + 8, h - 40, 34f)
        panel(w - 120, h - 62, 120f, 62f)
        batch.color(MUTED); batch.text("HEIGHT M", w - 8, h - 58, 15f, 1f)
        val height = (drone.height * 10).roundToInt()
        line.setLength(0); line.append(height / 10).append('.').append(height % 10)
        batch.color(WHITE); batch.text(line, w - 8, h - 40, 34f, 1f)

        radar(pose, 0f, h - 34f)

        if (game.camera.view == View.ONBOARD) horizon(pose)

        val blink = game.clock % 0.8f < 0.5f
        if (drone.phase == Phase.LANDED) {
            batch.color(PANEL, 0.7f); batch.rect(-120f, -62f, 240f, 54f)
            batch.color(WHITE); batch.text("PRESS A TO TAKE OFF", 0f, -57f, 21f, 0.5f)
            batch.color(MUTED)
            batch.text(if (game.controllerName == null && !vr) "OR PUSH LEFT STICK UP" else "START: MENU + CONTROLS", 0f, -34f, 17f, 0.5f)
        } else if (blink && drone.obstacle < 14f) {
            batch.color(WARN); batch.text("OBSTACLE", 0f, 62f, 22f, 0.5f)
        } else if (blink && battery < 15f) {
            batch.color(DANGER); batch.text("LOW BATTERY", 0f, 62f, 22f, 0.5f)
        }
        if (game.headSensorMissing) {
            batch.color(WARN); batch.text("THIS PHONE HAS NO MOTION SENSOR", 0f, -118f, 17f, 0.5f)
        }
        if (game.controllerName == null && vr) {
            batch.color(WARN); batch.text("NO CONTROLLER - TAP FOR MENU", 0f, 90f, 17f, 0.5f)
        }
    }

    private fun compass(game: Game, top: Float) {
        val span = 62f
        // The compass follows where the pilot is looking, so it moves with the head.
        val b = game.camera.basis
        val heading = atan2(-b[8], b[10])
        batch.color(PANEL, 0.4f); batch.rect(-span - 6, top, span * 2 + 12, 30f)
        for (k in 0 until 24) {
            val offset = wrapAngle(k * PI_F / 12 - heading) * 70f
            if (abs(offset) > span) continue
            batch.color(WHITE, 0.8f)
            if (k % 6 == 0) batch.text(if (k == 0) "N" else if (k == 6) "E" else if (k == 12) "S" else "W", offset, top + 4, 18f, 0.5f)
            else batch.rect(offset - 1, top + 18, 2f, 7f)
        }
    }

    private fun horizon(pose: DronePose) {
        val c = cos(pose.roll) * 70f; val s = sin(pose.roll) * 70f
        val offset = pose.pitch * 120f
        batch.color(WHITE, 0.6f)
        batch.line(-c, s - offset, -c * 0.3f, s * 0.3f - offset, 2f)
        batch.line(c * 0.3f, -s * 0.3f - offset, c, -s - offset, 2f)
        batch.rect(-2f, -2f, 4f, 4f)
    }

    private fun radar(pose: DronePose, cx: Float, cy: Float) {
        val radius = 38f; val scale = radius / 170f
        batch.color(PANEL, 0.5f)
        for (i in 0 until 20) {
            val a = i * PI_F / 10; val b = (i + 1) * PI_F / 10
            batch.triangle(cx, cy, cx + cos(a) * radius, cy + sin(a) * radius, cx + cos(b) * radius, cy + sin(b) * radius)
        }
        val c = cos(pose.yaw); val s = sin(pose.yaw)
        fun blip(x: Float, z: Float, color: Int, size: Float) {
            val dx = x - pose.x; val dz = z - pose.z
            var rx = (dx * c + dz * s) * scale; var ry = (-dx * s + dz * c) * scale
            val d = hypot(rx, ry)
            if (d > radius - 3) { rx *= (radius - 3) / d; ry *= (radius - 3) / d }
            batch.color(color); batch.rect(cx + rx - size / 2, cy + ry - size / 2, size, size)
        }
        blip(City.HOME_X, City.HOME_Z, GOOD, 6f)
        batch.color(WHITE); batch.triangle(cx - 5, cy + 5, cx + 5, cy + 5, cx, cy - 7)
    }

    private fun sticks(touch: TouchSticks) {
        for (i in 0..1) {
            val cx = touch.centerX(i, halfWidth); val cy = TouchSticks.CENTER_Y
            batch.color(WHITE, 0.3f)
            for (k in 0 until 32) {
                val a = k * PI_F / 16; val b = (k + 1) * PI_F / 16
                batch.line(
                    cx + cos(a) * TouchSticks.RADIUS, cy + sin(a) * TouchSticks.RADIUS,
                    cx + cos(b) * TouchSticks.RADIUS, cy + sin(b) * TouchSticks.RADIUS, 3f,
                )
            }
            val x = cx + touch.x[i] * TouchSticks.RADIUS; val y = cy - touch.y[i] * TouchSticks.RADIUS
            batch.color(ACCENT, 0.55f); batch.rect(x - 15, y - 15, 30f, 30f)
        }
        batch.color(PANEL, 0.6f)
        batch.rect(TouchSticks.ACTION_X - 55, TouchSticks.ACTION_Y - 20, 110f, 40f)
        batch.rect(TouchSticks.MENU_X - 55, TouchSticks.ACTION_Y - 20, 110f, 40f)
        batch.color(WHITE)
        batch.text("FLY/LAND", TouchSticks.ACTION_X, TouchSticks.ACTION_Y - 10, 19f, 0.5f)
        batch.text("MENU", TouchSticks.MENU_X, TouchSticks.ACTION_Y - 10, 19f, 0.5f)
    }

    private fun header(title: String) {
        batch.color(PANEL, 0.93f); batch.rect(-menuHalf - 10, -218f, menuHalf * 2 + 20, 436f)
        batch.color(ACCENT); batch.text(title, 0f, -205f, 28f, 0.5f)
    }

    private fun menu(game: Game, pad: PadState, fps: Int) {
        header("SKYCOP FLIGHT")
        firstRow = (game.selected - ROWS / 2).coerceIn(0, game.menu.size - ROWS)
        val textSize = min(21f, menuHalf / 13f)
        for (row in 0 until ROWS) {
            val index = firstRow + row
            val item = game.menu[index]
            val y = LIST_TOP + row * ROW_HEIGHT
            val selected = index == game.selected
            if (selected) {
                batch.color(ACCENT, 0.22f); batch.rect(-menuHalf, y - 3, menuHalf * 2, ROW_HEIGHT - 2)
            }
            batch.color(if (selected) ACCENT else WHITE)
            batch.text(item.label, -menuHalf + 8, y + 2, textSize)
            val value = value(item, game.settings)
            if (value != null) batch.text(value, menuHalf - 8, y + 2, textSize, 1f)
        }
        batch.color(MUTED)
        batch.rect(menuHalf + 3, LIST_TOP + firstRow * ROW_HEIGHT * ROWS / game.menu.size, 4f, ROW_HEIGHT * ROWS * ROWS / game.menu.size)

        // A live input test, so a bad mapping can be spotted without taking off.
        val y = 180f
        stick(-menuHalf + 30, y, pad.leftX, pad.leftY)
        stick(-menuHalf + 92, y, pad.rightX, pad.rightY)
        trigger(-menuHalf + 132, y, pad.leftTrigger)
        trigger(-menuHalf + 152, y, pad.rightTrigger)
        batch.color(MUTED)
        val name = game.controllerName
        line.setLength(0)
        if (name == null) line.append("NO CONTROLLER") else line.append(name, 0, min(name.length, 22))
        batch.text(line, menuHalf - 4, 162f, 16f, 1f)
        line.setLength(0); line.append(fps).append(" FPS   A SELECT  B BACK")
        batch.text(line, menuHalf - 4, 182f, 16f, 1f)
        batch.text("SWIPE TO SCROLL", -menuHalf + 170, 182f, 14f)
    }

    private fun stick(cx: Float, cy: Float, x: Float, y: Float) {
        batch.color(MUTED, 0.3f); batch.rect(cx - 25, cy - 25, 50f, 50f)
        batch.color(ACCENT); batch.rect(cx + x * 20 - 4, cy - y * 20 - 4, 8f, 8f)
    }

    private fun trigger(x: Float, cy: Float, value: Float) {
        batch.color(MUTED, 0.3f); batch.rect(x, cy - 25, 12f, 50f)
        batch.color(ACCENT); batch.rect(x, cy + 25 - value * 50, 12f, value * 50)
    }

    private fun value(item: MenuItem, s: Settings): CharSequence? = when (item) {
        MenuItem.LAYOUT -> if (s.classicLayout) "CLASSIC" else "PILOT"
        MenuItem.VR -> if (s.vr) "VR HEADSET" else "FULL SCREEN"
        MenuItem.INVERT_CLIMB -> onOff(s.invertClimb)
        MenuItem.INVERT_LOOK -> onOff(s.invertLook)
        MenuItem.HEAD -> onOff(s.headTracking)
        MenuItem.LENS -> onOff(s.lensCorrection)
        MenuItem.COMFORT -> onOff(s.comfort)
        MenuItem.SHADOWS -> onOff(s.shadows)
        MenuItem.WIND -> onOff(s.wind)
        MenuItem.SOUND -> onOff(s.sound)
        MenuItem.SENSITIVITY -> number(s.sensitivity, "%")
        MenuItem.DEADZONE -> number(s.deadzone, "%")
        MenuItem.SPACING -> number(s.lensSpacing, "")
        else -> null
    }

    private fun onOff(value: Boolean) = if (value) "ON" else "OFF"

    private fun number(value: Int, suffix: String): CharSequence {
        line.setLength(0)
        line.append(value).append(suffix)
        return line
    }

    private fun controls(game: Game) {
        val classic = game.settings.classicLayout
        header(if (classic) "CLASSIC CONTROLS" else "PILOT CONTROLS")
        val rows = if (classic) classicHelp else pilotHelp
        val size = min(19f, menuHalf / 15f)
        for (i in 0 until rows.size / 2) {
            val y = -156f + i * 30f
            batch.color(ACCENT); batch.text(rows[i * 2], -menuHalf + 8, y, size)
            batch.color(WHITE); batch.text(rows[i * 2 + 1], menuHalf - 8, y, size, 1f)
        }
        batch.color(MUTED); batch.text("B: BACK", 0f, 186f, 18f, 0.5f)
    }

    private fun calibration(game: Game) {
        header("CALIBRATION")
        val c = game.calibration
        val step = c.step.coerceIn(0, 5)
        line.setLength(0); line.append("STEP ").append(step + 1).append(" OF 6")
        batch.color(MUTED); batch.text(line, 0f, -90f, 20f, 0.5f)
        batch.color(WHITE)
        batch.text(if (c.waitingForRelease) "NOW LET GO" else Calibration.PROMPTS[step], 0f, -20f, min(27f, menuHalf / 11f), 0.5f)
        batch.color(MUTED)
        batch.text("B: SKIP STEP", 0f, 90f, 18f, 0.5f)
        batch.text("START: CANCEL", 0f, 118f, 18f, 0.5f)
    }

    /** Menu row under a point in HUD coordinates, or -1. */
    fun menuRowAt(x: Float, y: Float): Int {
        if (abs(x) > menuHalf + 10) return -1
        val row = floor((y - LIST_TOP + 3) / ROW_HEIGHT).toInt()
        return if (row in 0 until ROWS) firstRow + row else -1
    }
}
