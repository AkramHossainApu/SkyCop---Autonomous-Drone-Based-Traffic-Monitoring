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
        "B", "RETURN TO DOCK",
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
        "B", "RETURN TO DOCK",
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
            Screen.FLIGHT -> if (game.watchingLive) live(game) else {
                flight(game)
                if (!game.settings.vr && game.controllerName == null) sticks(touch)
            }
            Screen.MENU -> menu(game, pad, fps)
            Screen.CONTROLS -> controls(game)
            Screen.CALIBRATION -> calibration(game)
        }
        val toast = game.toast
        if (toast != null) {
            if (game.screen == Screen.FLIGHT) {
                val vr = game.settings.vr
                tag(toast, 0f, if (vr) -112f else -HALF_HEIGHT + 40f, if (vr) 13f else 16f, ACCENT, 0.5f)
            } else {
                val w = toast.length * 10.5f + 28f
                batch.color(PANEL, 0.82f); batch.rect(-w / 2, 217f, w, 32f)
                batch.color(ACCENT); batch.text(toast, 0f, 222f, 22f, 0.5f)
            }
        }
        batch.upload()
    }

    private fun panel(x: Float, y: Float, width: Float, height: Float) {
        batch.color(PANEL, 0.5f)
        batch.rect(x, y, width, height)
    }

    /** Small text with a faint backing, so it reads over sky and buildings without hiding much. */
    private fun tag(text: CharSequence, x: Float, y: Float, size: Float, color: Int, align: Float) {
        val width = text.length * size * 0.5f
        batch.color(PANEL, 0.35f); batch.rect(x - width * align - 5f, y - 3f, width + 10f, size + 6f)
        batch.color(color); batch.text(text, x, y, size, align)
    }

    /**
     * Only what a pilot needs, small and in the corners, so nearly the whole view is the world:
     * mode and phase top left, battery top right, speed bottom left, height bottom right.
     */
    private fun flight(game: Game) {
        // In a headset the corners of each lens are out of view, so the corners sit further in.
        val vr = game.settings.vr
        val w = if (vr) min(205f, halfWidth * 0.6f) else halfWidth - 14f
        val h = if (vr) 138f else HALF_HEIGHT - 12f
        val size = if (vr) 13f else 15f
        val drone = game.drone; val pose = drone.render

        line.setLength(0); line.append(drone.mode.label).append("  ").append(drone.phase.label)
        tag(line, -w, -h, size, WHITE, 0f)

        val battery = drone.battery
        val batteryColor = if (battery < 10) DANGER else if (battery < 25) WARN else GOOD
        line.setLength(0); line.append(ceil(battery).toInt()).append('%')
        if (drone.charging) line.append(" CHARGING")
        else if (drone.phase == Phase.RETURN) line.append(" DOCK ").append(drone.dockDistance.toInt()).append('M')
        else {
            val seconds = drone.flightSecondsLeft.toInt()
            line.append(' ').append(seconds / 60).append(':')
            if (seconds % 60 < 10) line.append('0')
            line.append(seconds % 60)
        }
        tag(line, w, -h, size, batteryColor, 1f)
        batch.color(batteryColor, 0.9f); batch.rect(w - 44f, -h + size + 5f, 44f * battery / 100f, 2f)

        line.setLength(0); line.append((drone.speed * 3.6f).roundToInt()).append(" KM/H")
        tag(line, -w, h - size, size, WHITE, 0f)
        val height = (drone.height * 10).roundToInt()
        line.setLength(0); line.append(height / 10).append('.').append(height % 10).append(" M")
        tag(line, w, h - size, size, WHITE, 1f)

        if (game.camera.view == View.ONBOARD) horizon(pose)

        val blink = game.clock % 0.8f < 0.5f
        val hint = h - size * 3.2f
        if (drone.phase == Phase.LANDED && drone.charging) {
            line.setLength(0); line.append("CHARGING ").append(ceil(battery).toInt()).append('%')
            tag(line, 0f, hint, size, ACCENT, 0.5f)
        } else if (drone.phase == Phase.LANDED) {
            tag(if (!drone.canTakeOff) "BATTERY EMPTY" else if (game.controllerName == null && !vr) "FLY/LAND OR LEFT STICK UP" else "A: TAKE OFF", 0f, hint, size, WHITE, 0.5f)
        } else if (blink && drone.obstacle < 14f) {
            tag("OBSTACLE", 0f, hint, size, WARN, 0.5f)
        } else if (blink && battery < 15f) {
            tag("LOW BATTERY", 0f, hint, size, DANGER, 0.5f)
        }
        if (game.headSensorMissing) tag("NO MOTION SENSOR", 0f, -h, size, WARN, 0.5f)
        if (game.controllerName == null && vr) tag("NO CONTROLLER - TAP FOR MENU", 0f, h - size, size, WARN, 0.5f)
    }

    /** Live camera: a small label, and the controls for a few seconds after switching. */
    private fun live(game: Game) {
        val vr = game.settings.vr
        val w = if (vr) min(205f, halfWidth * 0.6f) else halfWidth - 14f
        val h = if (vr) 138f else HALF_HEIGHT - 12f
        val size = if (vr) 13f else 15f
        if (game.liveHasVideo) tag("LIVE CAMERA", -w, -h, size, GOOD, 0f)
        else tag("WAITING FOR VIDEO", 0f, 0f, size + 2f, WARN, 0.5f)
        if (game.clock - game.liveSince < 5f) {
            tag(if (game.controllerName == null) "TAP: MENU" else "X OR B: SIMULATION   START: MENU", 0f, h - size, size, MUTED, 0.5f)
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
        batch.text("MAP DATA (C) OPENSTREETMAP CONTRIBUTORS", 0f, 200f, 12f, 0.5f)
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
        MenuItem.MAP -> MapData.TITLES[s.map]
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
