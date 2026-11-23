package com.skycop.sim

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import com.skycop.sim.audio.RotorAudio
import com.skycop.sim.core.GameHost
import com.skycop.sim.input.GamepadInput
import com.skycop.sim.input.HeadTracker
import com.skycop.sim.input.TouchSticks
import com.skycop.sim.render.SimRenderer

/** Hosts the simulation in its own window, away from React Native's focus handling and JS thread. */
class SimulationActivity : Activity(), GameHost {
    companion object {
        const val EXTRA_LIVE_AVAILABLE = "liveAvailable"
        const val EXTRA_START_LIVE = "startLive"
        const val EXTRA_RESULT = "result"
        private const val SETTINGS_KEY = "settings"
    }

    private val settings = Session.settings
    private val prefs by lazy { getSharedPreferences("skycop-simulation", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private val touch = TouchSticks()
    private val audio = RotorAudio()
    private lateinit var gamepad: GamepadInput
    private lateinit var head: HeadTracker
    private lateinit var renderer: SimRenderer
    private lateinit var surface: GLSurfaceView
    private lateinit var screen: Display
    private var exiting = false
    private var dragStartY = 0f
    private var dragLastY = 0f
    private var dragged = false

    private val displayListener = object : DisplayManager.DisplayListener {
        // Flipping between the two landscape orientations does not change the configuration.
        override fun onDisplayChanged(id: Int) = head.updateRotation()
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs.getString(SETTINGS_KEY, null)?.let(settings::decode)
        @Suppress("DEPRECATION")
        screen = windowManager.defaultDisplay
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        val attributes = window.attributes
        if (Build.VERSION.SDK_INT >= 28) {
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Ask for the panel's fastest mode at its current resolution.
        val active = screen.mode
        var best = active
        for (mode in screen.supportedModes) {
            if (mode.physicalWidth == active.physicalWidth && mode.physicalHeight == active.physicalHeight &&
                mode.refreshRate > best.refreshRate
            ) best = mode
        }
        attributes.preferredDisplayModeId = best.modeId
        window.attributes = attributes

        gamepad = GamepadInput(this, prefs)
        head = HeadTracker(this) { screen.rotation }
        renderer = SimRenderer(
            settings, this, intent.getBooleanExtra(EXTRA_LIVE_AVAILABLE, false), intent.getBooleanExtra(EXTRA_START_LIVE, false),
            gamepad, head, touch, audio, { screen.refreshRate },
            { index -> com.skycop.sim.core.MapData(assets.open("maps/${com.skycop.sim.core.MapData.FILES[index]}.skymap").use { it.readBytes() }) },
        ) { error ->
            main.post {
                Toast.makeText(this, "3D graphics failed: ${error.message}", Toast.LENGTH_LONG).show()
                finishWith("error")
            }
        }
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 0, 0, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            isFocusable = true
            isFocusableInTouchMode = true
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val rate = best.refreshRate
            surface.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    try {
                        holder.surface.setFrameRate(rate, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                    } catch (error: IllegalStateException) {
                        Log.w(SimRenderer.TAG, "Could not request the display frame rate", error)
                    }
                }
            })
        }
        setContentView(surface)
        if (Session.game != null && !Session.switchedToLive) renderer.requestPause()
        Session.switchedToLive = false
        surface.requestFocus()
        if (Build.VERSION.SDK_INT >= 30) surface.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK)
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        gamepad.start()
        head.start()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, main)
        renderer.resume()
        surface.onResume()
        audio.start()
    }

    override fun onPause() {
        renderer.requestPause()
        surface.onPause()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        gamepad.stop()
        head.stop()
        audio.stop()
        touch.clear()
        super.onPause()
    }

    override fun onDestroy() {
        Session.game?.detach(this)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            head.updateRotation()
        } else {
            gamepad.release()
            touch.clear()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        head.updateRotation()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepad.onMotion(event) || super.dispatchGenericMotionEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gamepad.onKey(event)) return true
        // Handled here because onBackPressed is not called when predictive back is enabled.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) renderer.requestBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        renderer.requestBack()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (renderer.inFlight && !renderer.vr && gamepad.name == null) {
            val buttons = touch.handle(event, surface.width, surface.height)
            if (buttons != 0) renderer.touchPresses.getAndUpdate { it or buttons }
        } else {
            touch.clear()
            // A tap acts on release; a drag scrolls the menu, one row per row height moved.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragStartY = event.y; dragLastY = event.y; dragged = false }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.y - dragStartY) > 24f) dragged = true
                    val rowPixels = surface.height * 34f / 540f
                    if (dragged && kotlin.math.abs(event.y - dragLastY) >= rowPixels) {
                        renderer.drag(event.y - dragLastY)
                        dragLastY = event.y
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragged) renderer.tap(event.x, event.y)
            }
        }
        return true
    }

    private fun finishWith(result: String) {
        if (exiting) return
        exiting = true
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, result))
        finish()
    }

    override fun exit(toLiveVideo: Boolean) {
        main.post {
            if (toLiveVideo) Session.switchedToLive = true else Session.end()
            finishWith(if (toLiveVideo) "live" else "exit")
        }
    }

    override fun settingsChanged() {
        prefs.edit().putString(SETTINGS_KEY, settings.encode()).apply()
    }

    override fun calibrationSaved(mapping: String) {
        main.post { gamepad.saveMapping(mapping) }
    }

    override fun calibrationCleared() {
        main.post { gamepad.clearMapping() }
    }

    override fun recenterHead() {
        head.recenter()
    }

    override fun rumble(strength: Float) {
        main.post { gamepad.rumble(strength) }
    }

    override fun mapChanged() {
        renderer.requestMap()
    }
}
