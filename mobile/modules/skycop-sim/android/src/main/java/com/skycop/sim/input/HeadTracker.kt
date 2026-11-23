package com.skycop.sim.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import com.skycop.sim.core.HeadPose
import com.skycop.sim.core.Mat4
import kotlin.math.min

/**
 * Reads head motion at 200 Hz on its own high-priority thread. The fused orientation gives an
 * absolute, drift-corrected pose; the gyroscope says where the head will be when the frame is shown.
 */
internal class HeadTracker(context: Context, private val displayRotation: () -> Int) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Best first. The game vector ignores the compass, so magnets in a headset cannot pull the view.
    private val orientation: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val gyroscope: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val available get() = orientation != null

    private var thread: HandlerThread? = null
    private val device = FloatArray(9)
    private val screen = FloatArray(9)
    private val vector = FloatArray(4)
    private val world = FloatArray(16)

    private val lock = Any()
    private val latest = FloatArray(16)
    private val latestRate = FloatArray(3)
    private var latestTime = 0L
    private var hasPose = false
    private var wantRecenter = true

    private val current = FloatArray(16)
    private val pose = HeadPose()

    @Volatile private var rotation = Surface.ROTATION_90

    fun start() {
        val wanted = orientation ?: return
        updateRotation()
        synchronized(lock) {
            hasPose = false
            wantRecenter = true
            latestRate.fill(0f)
        }
        val worker = HandlerThread("SkyCopHead", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        thread = worker
        val handler = Handler(worker.looper)
        // 200 Hz is the fastest rate that needs no extra permission.
        manager.registerListener(this, wanted, 5000, handler)
        gyroscope?.let { manager.registerListener(this, it, 5000, handler) }
    }

    fun stop() {
        manager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    fun updateRotation() {
        val next = displayRotation()
        if (next != rotation) recenter()
        rotation = next
    }

    fun recenter() {
        synchronized(lock) { wantRecenter = true }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val turned = rotation
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val x = event.values[0]; val y = event.values[1]
            var sx = x; var sy = y
            when (turned) {
                Surface.ROTATION_90 -> { sx = -y; sy = x }
                Surface.ROTATION_180 -> { sx = -x; sy = -y }
                Surface.ROTATION_270 -> { sx = y; sy = -x }
            }
            synchronized(lock) {
                // A very short average: enough to settle prediction, too short to feel.
                latestRate[0] += (sx - latestRate[0]) * 0.6f
                latestRate[1] += (sy - latestRate[1]) * 0.6f
                latestRate[2] += (event.values[2] - latestRate[2]) * 0.6f
            }
            return
        }
        System.arraycopy(event.values, 0, vector, 0, min(4, event.values.size))
        SensorManager.getRotationMatrixFromVector(device, vector)
        var axisX = SensorManager.AXIS_X; var axisY = SensorManager.AXIS_Y
        when (turned) {
            Surface.ROTATION_90 -> { axisX = SensorManager.AXIS_Y; axisY = SensorManager.AXIS_MINUS_X }
            Surface.ROTATION_180 -> { axisX = SensorManager.AXIS_MINUS_X; axisY = SensorManager.AXIS_MINUS_Y }
            Surface.ROTATION_270 -> { axisX = SensorManager.AXIS_MINUS_Y; axisY = SensorManager.AXIS_X }
        }
        if (!SensorManager.remapCoordinateSystem(device, axisX, axisY, screen)) return
        // Sensor world is east, north, up; GL world is right, up, backward.
        for (column in 0..2) {
            world[column * 4] = screen[column]
            world[column * 4 + 1] = screen[6 + column]
            world[column * 4 + 2] = -screen[3 + column]
        }
        world[15] = 1f
        synchronized(lock) {
            world.copyInto(latest)
            latestTime = event.timestamp
            hasPose = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Render thread, called right before each frame is drawn. Identity when tracking is off. */
    fun read(out: FloatArray, enabled: Boolean) {
        val wx: Float; val wy: Float; val wz: Float
        val age: Float
        val recenter: Boolean
        synchronized(lock) {
            if (!enabled || !hasPose) {
                Mat4.identity(out)
                return
            }
            latest.copyInto(current)
            wx = latestRate[0]; wy = latestRate[1]; wz = latestRate[2]
            age = ((SystemClock.elapsedRealtimeNanos() - latestTime) / 1e9f).coerceIn(0f, 0.02f)
            recenter = wantRecenter
            wantRecenter = false
        }
        pose.resolve(out, current, wx, wy, wz, HeadPose.LOOKAHEAD + age, recenter)
    }
}
