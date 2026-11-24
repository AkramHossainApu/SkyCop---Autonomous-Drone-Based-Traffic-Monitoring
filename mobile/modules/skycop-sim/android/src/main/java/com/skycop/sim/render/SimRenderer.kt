package com.skycop.sim.render

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.util.Log
import com.skycop.sim.LiveFeed
import com.skycop.sim.Session
import com.skycop.sim.audio.RotorAudio
import com.skycop.sim.core.*
import com.skycop.sim.input.GamepadInput
import com.skycop.sim.input.HeadTracker
import com.skycop.sim.input.TouchSticks
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/** The game loop. It runs on the GL thread and does not wait for JavaScript or the UI thread. */
internal class SimRenderer(
    private val settings: Settings,
    private val host: GameHost,
    private val liveAvailable: Boolean,
    private var startLive: Boolean,
    private val gamepad: GamepadInput,
    private val head: HeadTracker,
    private val touch: TouchSticks,
    private val audio: RotorAudio,
    private val refreshRate: () -> Float,
    private val loadMap: (Int) -> MapData,
    private val onFatal: (Throwable) -> Unit,
) : GLSurfaceView.Renderer {

    companion object {
        const val TAG = "SkyCopSim"
        private const val EYE_OFFSET = 0.032f
    }

    @Volatile var inFlight = true
        private set
    @Volatile var vr = settings.vr
        private set

    val touchPresses = AtomicInteger()

    private var game: Game? = Session.game?.also { it.attach(host, liveAvailable) }
    private var world: WorldRenderer? = null
    private var hud: Hud? = null
    private var hudBatch: HudBatch? = null
    private var presenter: Presenter? = null
    private var live: LiveScreen? = null
    private var profile: GpuProfile? = null
    private val target = RenderTarget()
    private val governor = QualityGovernor()
    private val pacer = FramePacer(1f / 60f)
    private val pad = PadState()
    private val headPose = FloatArray(16)
    private var screenWidth = 1
    private var screenHeight = 1
    private var lastFrame = 0L
    private var vignette = 0f
    private var hadPad = false
    private var failed = false

    @Volatile private var pendingTap = false
    private var tapX = 0f
    private var tapY = 0f
    @Volatile private var pauseRequested = false
    @Volatile private var backRequested = false
    @Volatile private var resumed = true
    @Volatile private var mapRequested = false

    /** Called when the pilot picks another map: the next frame builds it and starts a flight there. */
    fun requestMap() {
        mapRequested = true
    }

    private fun switchMap() {
        val city = Session.city(settings.map, loadMap)
        val next = Game(city, settings, host, liveAvailable)
        next.camera.snap(0f)
        Session.game = next
        game = next
        world?.load(city)
        resumed = true
    }

    fun tap(x: Float, y: Float) {
        tapX = x; tapY = y
        pendingTap = true
    }

    @Volatile private var pendingScroll = 0

    /** Vertical drag in screen pixels while a menu is open. */
    fun drag(pixels: Float) {
        val rows = (pixels / screenHeight * 540f / 34f).toInt()
        if (rows != 0) pendingScroll += rows
    }

    fun requestPause() {
        pauseRequested = true
    }

    fun requestBack() {
        backRequested = true
    }

    fun resume() {
        resumed = true
    }

    private fun fail(error: Throwable) {
        failed = true
        Log.e(TAG, "Simulation stopped", error)
        onFatal(error)
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        try {
            val city = game?.city ?: Session.city(settings.map, loadMap)
            if (game == null) {
                game = Game(city, settings, host, liveAvailable).also {
                    // Settle camera and interpolation before the first visible frame.
                    it.camera.snap(0f)
                    Session.game = it
                }
            }
            val gpu = GpuProfile.detect()
            profile = gpu
            world = WorldRenderer(city, gpu)
            val batch = HudBatch()
            hudBatch = batch
            hud = Hud(batch)
            presenter = Presenter()
            live = LiveScreen()
            target.forget()
            resumed = true
            failed = false
            Log.i(TAG, "GL ${glGetString(GL_VERSION)} on ${gpu.renderer}, modest=${gpu.modest}")
        } catch (error: Exception) {
            fail(error)
        }
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        if (failed) return
        val gpu = profile ?: return
        screenWidth = width; screenHeight = height
        val shrink = min(1f, gpu.maxHeight.toFloat() / height)
        try {
            target.resize((width * shrink).toInt(), (height * shrink).toInt(), gpu.samples)
        } catch (error: Exception) {
            fail(error)
        }
    }

    override fun onDrawFrame(unused: GL10?) {
        if (failed) return
        try {
            frame()
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun frame() {
        if (mapRequested) {
            mapRequested = false
            switchMap()
        }
        val game = game ?: return
        val world = world ?: return
        val hud = hud ?: return
        val batch = hudBatch ?: return
        val presenter = presenter ?: return

        val now = System.nanoTime()
        if (resumed) {
            resumed = false
            lastFrame = now
            governor.start(refreshRate(), profile?.startLevel ?: 0)
            pacer.reset()
        }
        val elapsed = (now - lastFrame) / 1e9f
        lastFrame = now
        val measured = governor.frame(elapsed)
        pacer.period = governor.period
        val dt = if (elapsed == 0f) 0f else pacer.next(elapsed)

        val connected = gamepad.read(pad, settings.deadzone / 100f)
        if (connected) game.controllerName = gamepad.name
        else if (hadPad) game.padLost()
        hadPad = connected
        if (!connected) {
            if (!settings.vr && game.screen == Screen.FLIGHT) {
                pad.leftX += touch.x[0]; pad.leftY += touch.y[0]
                pad.rightX += touch.x[1]; pad.rightY += touch.y[1]
            }
        }
        var pressed = gamepad.takePresses() or touchPresses.getAndSet(0)
        if (pauseRequested) {
            pauseRequested = false
            game.openMenu()
        }
        if (backRequested) {
            backRequested = false
            game.back()
        }
        if (pendingScroll != 0) {
            game.scroll(-pendingScroll)
            pendingScroll = 0
        }
        game.liveAvailable = LiveFeed.available
        // Before taps: a tap while the view was opening must not count as a press in the live view.
        if (startLive) {
            startLive = false
            game.watchLive(only = true)
        }
        if (pendingTap) {
            pendingTap = false
            pressed = pressed or handleTap(game, hud)
        }
        val tracked = settings.headTracking && settings.vr && head.available
        head.read(headPose, tracked)
        game.update(dt, pad, pressed, gamepad.mapping, gamepad.raw, headPose, tracked)
        game.headSensorMissing = settings.headTracking && settings.vr && !head.available
        inFlight = game.screen == Screen.FLIGHT && !game.watchingLive
        vr = settings.vr
        audio.set(game.drone.render.rotor, settings.sound && inFlight)

        draw(game, world, hud, batch, presenter, dt)

        if (measured && Log.isLoggable(TAG, Log.DEBUG)) report(game)
    }

    private fun draw(game: Game, world: WorldRenderer, hud: Hud, batch: HudBatch, presenter: Presenter, dt: Float) {
        val eyes = if (settings.vr) 2 else 1
        val usedWidth = ((target.width * governor.scale).toInt() / 2 * 2).coerceAtLeast(2)
        val usedHeight = (target.height * governor.scale).toInt().coerceAtLeast(2)
        val eyeWidth = usedWidth / eyes
        val aspect = screenWidth.toFloat() / eyes / screenHeight
        val tanHalf = if (settings.vr) tan(43f * DEG) else tan(31f * DEG)

        val live = live
        val watching = game.watchingLive && live != null
        if (watching) {
            live!!.upload()
            game.liveHasVideo = live.hasVideo
        } else {
            world.prepare(game, QUALITY_LEVELS[governor.level])
        }
        hud.build(game, pad, aspect, governor.fps, touch)

        target.bindScene()
        if (watching) live!!.clear()
        val basis = if (watching) game.liveBasis else game.camera.basis
        val eye = game.camera.eye
        for (i in 0 until eyes) {
            glViewport(i * eyeWidth, 0, eyeWidth, usedHeight)
            val offset = if (eyes == 1) 0f else (i * 2 - 1) * EYE_OFFSET
            if (watching) {
                // The same flat picture in both eyes, centered.
                live!!.drawEye(basis, 0f, 0f, 0f, tanHalf, aspect, settings.vr)
            } else {
                world.drawEye(
                    basis, eye[0] + basis[0] * offset, eye[1] + basis[1] * offset, eye[2] + basis[2] * offset,
                    tanHalf, aspect,
                )
            }
        }
        target.resolve(usedWidth, usedHeight)

        glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE)
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        for (i in 0 until eyes) {
            glViewport(i * eyeWidth, 0, eyeWidth, usedHeight)
            batch.draw(Hud.HALF_HEIGHT * aspect / hud.scale, Hud.HALF_HEIGHT / hud.scale)
        }

        val wanted =
            if (settings.vr && settings.comfort) ((game.camera.turnRate - 0.35f) * 0.8f).coerceIn(0f, 0.7f) else 0f
        vignette += (wanted - vignette) * blend(6f, dt)
        // No lens pre-warp: it magnified the middle of each eye, which looked like a zoom.
        presenter.begin()
        for (i in 0 until eyes) {
            presenter.eye(
                target, i, eyes, eyeWidth * eyes, usedHeight, screenWidth, screenHeight,
                0f, 0f, lensShift(i), vignette,
            )
        }
    }

    private fun lensShift(eye: Int) = if (settings.vr) (eye * 2 - 1) * settings.lensSpacing / 100f else 0f

    private fun handleTap(game: Game, hud: Hud): Int {
        if (game.screen != Screen.FLIGHT && game.screen != Screen.MENU) {
            game.back()
            return 0
        }
        val eyes = if (settings.vr) 2 else 1
        val eyeWidth = screenWidth.toFloat() / eyes
        val eye = (tapX / eyeWidth).toInt().coerceIn(0, eyes - 1)
        val x = (tapX - eye * eyeWidth) / eyeWidth * 2f - 1f - lensShift(eye)
        val y = tapY / screenHeight * 2f - 1f
        val aspect = eyeWidth / screenHeight
        val hudX = x * Hud.HALF_HEIGHT * aspect / hud.scale
        val hudY = y * Hud.HALF_HEIGHT / hud.scale
        if (game.screen == Screen.FLIGHT && game.watchingLive && game.liveOnly) {
            game.liveTap(hud.exitHit(hudX, hudY))
            return 0
        }
        if (game.screen == Screen.FLIGHT) return if (settings.vr || game.watchingLive) Button.START else 0
        val row = hud.menuRowAt(hudX, hudY)
        if (row >= 0) game.activate(row)
        return 0
    }

    private fun report(game: Game) {
        val d = game.drone; val p = d.pose; val b = game.camera.basis; val e = game.camera.eye
        Log.d(
            TAG,
            "fps=${governor.fps} worst=${governor.worstFrameMs} scale=${governor.scale} level=${governor.level} screen=${game.screen} " +
                "phase=${d.phase} mode=${d.mode} x=${p.x} y=${p.y} z=${p.z} yaw=${p.yaw} speed=${d.speed} " +
                "forward=${-b[8]},${-b[9]},${-b[10]} side=${b[1]} eye=${e[0]},${e[1]},${e[2]} " +
                "view=${game.camera.view} gimbal=${game.camera.gimbal} pad=${game.controllerName} " +
                "live=${game.watchingLive} video=${game.liveHasVideo} liveForward=${-game.liveBasis[8]},${-game.liveBasis[9]},${-game.liveBasis[10]} " +
                "l=${pad.leftX},${pad.leftY} r=${pad.rightX},${pad.rightY} t=${pad.leftTrigger},${pad.rightTrigger}",
        )
    }
}
