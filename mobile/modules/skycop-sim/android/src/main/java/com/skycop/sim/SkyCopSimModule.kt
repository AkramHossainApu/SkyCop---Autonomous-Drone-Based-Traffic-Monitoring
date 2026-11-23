package com.skycop.sim

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.Window
import androidx.core.os.bundleOf
import com.skycop.sim.core.Textures
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.lang.ref.WeakReference

class SkyCopSimModule : Module() {
    private companion object {
        const val REQUEST = 0x5C0B
        const val BUTTON_EVENT = "onControllerButton"
    }

    private val main = Handler(Looper.getMainLooper())
    private var pending: Promise? = null
    private var liveAvailable = false
    private var startLive = false
    private var linkArrived = false
    private var hookedWindow: WeakReference<Window>? = null
    private var hook: ControllerButtonCallback? = null

    override fun definition() = ModuleDefinition {
        Name("SkyCopSim")
        Events(BUTTON_EVENT)

        AsyncFunction("openSimulation") { liveAvailable: Boolean, startLive: Boolean, promise: Promise ->
            val activity = appContext.currentActivity
            if (activity == null) {
                promise.reject(CodedException("ERR_NO_ACTIVITY", "The app is not in the foreground.", null))
            } else if (pending != null) {
                promise.reject(CodedException("ERR_ALREADY_OPEN", "The simulation is already open.", null))
            } else {
                pending = promise
                this@SkyCopSimModule.liveAvailable = liveAvailable
                this@SkyCopSimModule.startLive = startLive
                linkArrived = false
                Textures.prepare()
                launch(activity)
            }
        }.runOnQueue(Queues.MAIN)

        // The live camera stream to show in VR, by react-native-webrtc stream URL; null when there is none.
        Function("setLiveStream") { streamUrl: String? ->
            main.post { LiveFeed.follow(appContext.reactContext as? com.facebook.react.bridge.ReactContext, streamUrl) }
            Unit
        }

        // Pairing crypto, matching the website's WebCrypto calls byte for byte.
        Function("sha256Hex") { text: String -> PairingCrypto.sha256Hex(text) }
        Function("seal") { keyHex: String, plain: String -> PairingCrypto.seal(keyHex, plain) }
        Function("open") { keyHex: String, sealed: String -> PairingCrypto.open(keyHex, sealed) }
        Function("loadValue") { key: String ->
            appContext.reactContext?.getSharedPreferences("skycop-app", android.content.Context.MODE_PRIVATE)?.getString(key, null)
        }
        Function("saveValue") { key: String, value: String ->
            appContext.reactContext?.getSharedPreferences("skycop-app", android.content.Context.MODE_PRIVATE)
                ?.edit()?.putString(key, value)?.apply()
            Unit
        }

        // Controller shortcuts on the receiver and live-video screens.
        AsyncFunction("watchControllerButtons") {
            appContext.currentActivity?.let(::install)
            Unit
        }.runOnQueue(Queues.MAIN)

        OnActivityResult { _, result ->
            if (result.requestCode != REQUEST || pending == null) return@OnActivityResult
            val answer = result.data?.getStringExtra(SimulationActivity.EXTRA_RESULT)
            if (answer != null) {
                finish(answer)
                return@OnActivityResult
            }
            // No answer means Android closed the simulation: MainActivity is singleTask, so tapping the
            // app icon clears everything above it. Put the flight back, unless an invitation was opened.
            main.post {
                val activity = appContext.currentActivity
                if (linkArrived || activity == null) finish("exit") else launch(activity)
            }
        }

        OnNewIntent { intent ->
            if (intent.data != null) linkArrived = true
        }

        OnActivityEntersForeground {
            appContext.currentActivity?.let(::install)
        }

        OnDestroy {
            hook?.onButton = null
            pending = null
        }
    }

    private fun launch(activity: Activity) {
        val intent = Intent(activity, SimulationActivity::class.java)
            .putExtra(SimulationActivity.EXTRA_LIVE_AVAILABLE, liveAvailable)
            .putExtra(SimulationActivity.EXTRA_START_LIVE, startLive)
        // Only the first opening starts on the live camera; a relaunch keeps whatever was on screen.
        startLive = false
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQUEST)
        } catch (error: RuntimeException) {
            pending?.reject(CodedException("ERR_OPEN_FAILED", "Could not open the simulation.", error))
            pending = null
        }
    }

    private fun finish(result: String) {
        pending?.resolve(result)
        pending = null
    }

    private fun install(activity: Activity) {
        if (activity is SimulationActivity) return
        val window = activity.window ?: return
        if (hookedWindow?.get() === window) return
        val current = window.callback ?: return
        // After a JS reload the previous module's hook is still installed; reuse it.
        val callback = current as? ControllerButtonCallback
            ?: ControllerButtonCallback(current).also { window.callback = it }
        callback.onButton = { button -> sendEvent(BUTTON_EVENT, bundleOf("button" to button)) }
        hook = callback
        hookedWindow = WeakReference(window)
    }
}

/** Sees controller X and B before a focused React Native view can swallow them. */
private class ControllerButtonCallback(private val original: Window.Callback) : Window.Callback by original {
    var onButton: ((String) -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val button = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_X -> "x"
            KeyEvent.KEYCODE_BUTTON_B -> "b"
            KeyEvent.KEYCODE_BUTTON_3 -> if (hasNamedButtons(event)) null else "x"
            KeyEvent.KEYCODE_BUTTON_2 -> if (hasNamedButtons(event)) null else "b"
            else -> null
        }
        if (button == null || event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return original.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onButton?.invoke(button)
        return true
    }

    private fun hasNamedButtons(event: KeyEvent) = event.device?.hasKeys(KeyEvent.KEYCODE_BUTTON_A)?.get(0) == true

    // Kotlin delegation skips Java default methods, so these must be forwarded by hand.
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) {
        if (Build.VERSION.SDK_INT >= 24) original.onProvideKeyboardShortcuts(data, menu, deviceId)
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        if (Build.VERSION.SDK_INT >= 26) original.onPointerCaptureChanged(hasCapture)
    }
}
