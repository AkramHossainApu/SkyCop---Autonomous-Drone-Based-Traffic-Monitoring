package com.skycop.sim.input

import android.content.Context
import android.content.SharedPreferences
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.skycop.sim.core.Axis
import com.skycop.sim.core.Button
import com.skycop.sim.core.InputMapping
import com.skycop.sim.core.PadState
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives controller events on the UI thread and hands the newest state to the render thread.
 *
 * An axis keeps its last value until Android reports a change; button presses are latched, so a tap
 * shorter than one frame is never lost. Only disconnecting or losing focus resets the state.
 */
internal class GamepadInput(context: Context, private val prefs: SharedPreferences) : InputManager.InputDeviceListener {
    private companion object {
        val ANDROID_AXES = intArrayOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS, MotionEvent.AXIS_THROTTLE, MotionEvent.AXIS_RUDDER,
        )
        const val HAT = Button.UP or Button.DOWN or Button.LEFT or Button.RIGHT
    }

    private val manager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val incoming = FloatArray(Axis.COUNT)
    private val lock = Any()
    private val presses = AtomicInteger()

    val mapping = InputMapping()
    /** Render-thread copy of the unmapped axes, for calibration. */
    val raw = FloatArray(Axis.COUNT)

    private var keys = 0
    private var hat = 0
    private var deviceId = -1
    private var numberedButtons = false
    private var descriptor: String? = null

    @Volatile var name: String? = null
        private set

    fun start() {
        manager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        val current = InputDevice.getDevice(deviceId)
        if (current != null && isController(current)) select(current) else selectFirst()
    }

    fun stop() {
        manager.unregisterInputDeviceListener(this)
        release()
    }

    /** Lets go of everything, for pause and focus loss. */
    fun release() {
        synchronized(lock) {
            incoming.fill(0f)
            keys = 0; hat = 0
        }
        presses.set(0)
    }

    private fun isController(device: InputDevice): Boolean {
        if (device.isVirtual) return false
        val sources = device.sources
        val joystick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        val gamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        if (!joystick && !gamepad) return false
        // Some keyboards and fingerprint readers claim to be gamepads.
        return (joystick && device.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_JOYSTICK) != null) ||
            (gamepad && device.hasKeys(KeyEvent.KEYCODE_BUTTON_A)[0])
    }

    private fun selectFirst() {
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (isController(device)) {
                select(device)
                return
            }
        }
        synchronized(lock) {
            deviceId = -1; descriptor = null; name = null
            incoming.fill(0f); keys = 0; hat = 0
        }
    }

    private fun select(device: InputDevice) {
        val axes = BooleanArray(Axis.COUNT) {
            device.getMotionRange(ANDROID_AXES[it], InputDevice.SOURCE_JOYSTICK) != null
        }
        synchronized(lock) {
            deviceId = device.id
            descriptor = device.descriptor
            incoming.fill(0f); keys = 0; hat = 0
            mapping.configure(axes, device.vendorId)
            // Numbered keys are face buttons only on pads without named ones; on a DS4, BUTTON_1 is the touchpad.
            numberedButtons = !device.hasKeys(KeyEvent.KEYCODE_BUTTON_A)[0]
            prefs.getString("mapping:${device.descriptor}", null)?.let {
                if (!mapping.decode(it)) mapping.configure(axes, device.vendorId)
            }
            name = device.name.uppercase()
        }
    }

    override fun onInputDeviceAdded(id: Int) {
        if (deviceId >= 0) return
        val device = InputDevice.getDevice(id) ?: return
        if (isController(device)) select(device)
    }

    override fun onInputDeviceRemoved(id: Int) {
        if (id == deviceId) selectFirst()
    }

    override fun onInputDeviceChanged(id: Int) {
        if (id != deviceId) return
        val device = InputDevice.getDevice(id)
        if (device != null && isController(device)) select(device) else selectFirst()
    }

    fun saveMapping(encoded: String) {
        descriptor?.let { prefs.edit().putString("mapping:$it", encoded).apply() }
    }

    fun clearMapping() {
        descriptor?.let { prefs.edit().remove("mapping:$it").apply() }
        InputDevice.getDevice(deviceId)?.let { select(it) }
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val device = event.device ?: return false
        if (event.deviceId != deviceId) {
            if (!isController(device)) return false
            // The controller being used wins, but noise from a second one must not steal control.
            var active = deviceId < 0
            for (axis in intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y)) {
                if (kotlin.math.abs(event.getAxisValue(axis)) > 0.6f) active = true
            }
            if (!active) return true
            select(device)
        }
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        var nextHat = 0
        if (hx < -0.5f) nextHat = nextHat or Button.LEFT else if (hx > 0.5f) nextHat = nextHat or Button.RIGHT
        if (hy < -0.5f) nextHat = nextHat or Button.UP else if (hy > 0.5f) nextHat = nextHat or Button.DOWN
        synchronized(lock) {
            for (i in incoming.indices) incoming[i] = event.getAxisValue(ANDROID_AXES[i])
            latch(nextHat and (hat or (keys and HAT)).inv())
            hat = nextHat
        }
        return true
    }

    fun onKey(event: KeyEvent): Boolean {
        val numbered = event.keyCode in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16
        if (numbered && !numberedButtons && event.deviceId == deviceId) return true
        val bit = buttonFor(event.keyCode)
        if (bit == 0) return false
        val fromPad = KeyEvent.isGamepadButton(event.keyCode) ||
            event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.isFromSource(InputDevice.SOURCE_DPAD)
        if (!fromPad) return false
        if (event.deviceId != deviceId && event.action == KeyEvent.ACTION_DOWN) {
            event.device?.let { if (isController(it)) select(it) }
        }
        synchronized(lock) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) latch(bit and (keys or hat).inv())
                keys = keys or bit
            } else if (event.action == KeyEvent.ACTION_UP) {
                keys = keys and bit.inv()
            }
        }
        // Consuming stops Android from synthesizing BACK or DPAD_CENTER from face buttons.
        return true
    }

    private fun latch(bits: Int) {
        if (bits == 0) return
        while (true) {
            val old = presses.get()
            if (presses.compareAndSet(old, old or bits)) return
        }
    }

    private fun buttonFor(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_1 -> Button.A
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_2 -> Button.B
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_3 -> Button.X
        KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_4 -> Button.Y
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_5 -> Button.LB
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_6 -> Button.RB
        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_7 -> Button.LT
        KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_8 -> Button.RT
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_9 -> Button.SELECT
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_10 -> Button.START
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_11 -> Button.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_12 -> Button.R3
        KeyEvent.KEYCODE_DPAD_UP -> Button.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Button.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Button.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Button.RIGHT
        else -> 0
    }

    /** Render thread. Returns whether a controller is connected. */
    fun read(out: PadState, deadzone: Float): Boolean {
        synchronized(lock) {
            incoming.copyInto(raw)
            if (deviceId < 0) {
                out.clear()
                out.held = keys or hat
                return false
            }
            mapping.read(raw, keys or hat, deadzone, out)
            return true
        }
    }

    fun takePresses(): Int = presses.getAndSet(0)

    fun rumble(strength: Float) {
        val device = InputDevice.getDevice(deviceId) ?: return
        val milliseconds = (60 + strength * 160).toLong()
        val amplitude = (strength * 255).toInt().coerceIn(1, 255)
        if (Build.VERSION.SDK_INT >= 31) {
            val vibrators = device.vibratorManager
            for (id in vibrators.vibratorIds) {
                vibrators.getVibrator(id).vibrate(VibrationEffect.createOneShot(milliseconds, amplitude))
            }
        } else if (Build.VERSION.SDK_INT >= 26) {
            @Suppress("DEPRECATION")
            val vibrator = device.vibrator
            if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, amplitude))
        }
    }
}
