package com.skycop.sim.render

import android.opengl.GLES30.*
import com.skycop.sim.core.*
import kotlin.math.*

/** Draws the city, traffic and drone: one shadow pass, then one color pass per eye. */
internal class WorldRenderer(private var city: City, gpu: GpuProfile) {
    private companion object {
        const val SHADOW_HALF = 85f
        const val NEAR = 0.15f
        const val FAR = 1400f
    }

    private val shadowSize = gpu.shadowSize
    private val atlas = TextureAtlas(gpu.anisotropy)
    private val world = Program(Shaders.WORLD_VERTEX, Shaders.WORLD_FRAGMENT)
    private val worldMatrix = world.uniform("uViewProjection")
    private val worldModel = world.uniform("uModel")
    private val worldLight = world.uniform("uLight")
    private val worldEye = world.uniform("uEye")
    private val worldTime = world.uniform("uTime")
    private val worldSignalTime = world.uniform("uSignalTime")
    private val worldShadowOn = world.uniform("uShadowOn")
    private val worldAlbedo = world.uniform("uAlbedo")
    private val worldRelief = world.uniform("uRelief")
    private val worldReliefOn = world.uniform("uReliefOn")
    private val worldShadowTexel = world.uniform("uShadowTexel")
    private val worldShadowMap = world.uniform("uShadowMap")

    private val depth = Program(Shaders.SHADOW_VERTEX, Shaders.SHADOW_FRAGMENT)
    private val depthMatrix = depth.uniform("uViewProjection")
    private val depthModel = depth.uniform("uModel")

    private val sky = Program(Shaders.SKY_VERTEX, Shaders.SKY_FRAGMENT)
    private val skyBasis = sky.uniform("uBasis")
    private val skyTan = sky.uniform("uTan")
    private val skyTime = sky.uniform("uTime")

    private val glow = Program(Shaders.GLOW_VERTEX, Shaders.GLOW_FRAGMENT)
    private val glowMatrix = glow.uniform("uViewProjection")
    private val glowModel = glow.uniform("uModel")
    private val glowColor = glow.uniform("uGlow")

    private var terrain = GpuMesh(city.terrain)
    private var blocks = Array(city.chunks.size) { GpuMesh(city.chunks[it].mesh) }
    private var details = Array(city.chunks.size) { GpuMesh(city.chunks[it].detail) }
    private val droneBody = GpuMesh(Models.drone())
    private val propeller = GpuMesh(Models.propeller())
    private val rotorDisc = GpuMesh(Models.rotorDisc())
    private val vehicles: Array<GpuMesh>

    private val emptyVao: Int
    private val shadowTexture: Int
    private val shadowFramebuffer: Int

    private val identity = FloatArray(16).also { Mat4.identity(it) }
    private val droneMatrix = FloatArray(16)
    private val propMatrices = Array(4) { FloatArray(16) }
    private val discMatrices = Array(4) { FloatArray(16) }
    private val local = FloatArray(16)
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val lightBasis = FloatArray(16)
    private val light = FloatArray(16)
    private val planes = FloatArray(24)
    private var quality = QUALITY_LEVELS[0]
    private var showDrone = true
    private var showShadows = true
    private var rotor = 0f
    private var time = 0f
    private var signalTime = 0f

    init {
        val models = Models.vehicles()
        vehicles = Array(models.size) { GpuMesh(models[it], Traffic.MAX_VEHICLES) }

        val id = IntArray(1)
        glGenVertexArrays(1, id, 0)
        emptyVao = id[0]
        glGenTextures(1, id, 0)
        shadowTexture = id[0]
        glBindTexture(GL_TEXTURE_2D, shadowTexture)
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_DEPTH_COMPONENT24, shadowSize, shadowSize)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL)
        glGenFramebuffers(1, id, 0)
        shadowFramebuffer = id[0]
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFramebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, shadowTexture, 0)
        val none = intArrayOf(GL_NONE)
        glDrawBuffers(1, none, 0)
        glReadBuffer(GL_NONE)

        // Same direction as SUN in the shaders.
        val length = sqrt(0.45f * 0.45f + 0.78f * 0.78f + 0.38f * 0.38f)
        val bx = 0.45f / length; val by = 0.78f / length; val bz = 0.38f / length
        val h = hypot(bx, bz)
        val rx = bz / h; val rz = -bx / h
        lightBasis[0] = rx; lightBasis[2] = rz
        lightBasis[4] = by * rz; lightBasis[5] = bz * rx - bx * rz; lightBasis[6] = -by * rx
        lightBasis[8] = bx; lightBasis[9] = by; lightBasis[10] = bz
    }

    /** Swaps in another map's city, freeing the old one's meshes. Shaders and textures stay. */
    fun load(next: City) {
        terrain.release()
        for (mesh in blocks) mesh.release()
        for (mesh in details) mesh.release()
        city = next
        terrain = GpuMesh(next.terrain)
        blocks = Array(next.chunks.size) { GpuMesh(next.chunks[it].mesh) }
        details = Array(next.chunks.size) { GpuMesh(next.chunks[it].detail) }
    }

    /** Uploads what moves and renders the shadow map. Called once per frame. */
    fun prepare(game: Game, level: QualityLevel) {
        quality = level
        time = game.clock % 3600f
        signalTime = game.traffic.time % Signals.CYCLE
        showShadows = game.settings.shadows && level.shadows
        val pose = game.drone.render
        val eye = game.camera.eye
        showDrone = game.camera.view != View.ONBOARD
        rotor = pose.rotor
        Mat4.pose(droneMatrix, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, pose.roll)
        for (i in 0..3) {
            val spin = if (i == 0 || i == 3) game.propellerAngle else -game.propellerAngle
            Mat4.pose(local, Models.MOTOR_X[i], Models.MOTOR_Y[i], Models.MOTOR_Z[i], spin + i * 0.7f, 0f, 0f)
            Mat4.multiply(propMatrices[i], droneMatrix, local)
            Mat4.pose(local, Models.MOTOR_X[i], Models.MOTOR_Y[i], Models.MOTOR_Z[i], 0f, 0f, 0f)
            Mat4.multiply(discMatrices[i], droneMatrix, local)
        }

        for (mesh in vehicles) mesh.beginInstances()
        val traffic = game.traffic
        for (car in 0 until traffic.count) {
            val x = traffic.x[car]; val z = traffic.z[car]
            val dx = x - eye[0]; val dz = z - eye[2]
            if (abs(dx) > level.carRange || abs(dz) > level.carRange) continue
            // Well behind the viewer and too far away to cast a shadow into view.
            val b = game.camera.basis
            if (abs(dx) + abs(dz) > 70f && -(dx * b[8] + dz * b[10]) < -30f && abs(b[9]) < 0.45f) continue
            val color = traffic.color[car]
            vehicles[traffic.type[car]].addInstance(x, traffic.y[car], z, traffic.yaw[car], color)
        }
        for (mesh in vehicles) mesh.uploadInstances()

        if (showShadows) renderShadowMap(pose)
    }

    private fun renderShadowMap(pose: DronePose) {
        // Center a little ahead of the drone, snapped to shadow texels so edges do not crawl.
        val cx = pose.x + sin(pose.yaw) * 30f; val cy = 10f; val cz = pose.z - cos(pose.yaw) * 30f
        val texel = 2 * SHADOW_HALF / shadowSize
        val b = lightBasis
        val sx = floor((cx * b[0] + cy * b[1] + cz * b[2]) / texel) * texel
        val sy = floor((cx * b[4] + cy * b[5] + cz * b[6]) / texel) * texel
        val sz = cx * b[8] + cy * b[9] + cz * b[10] + 220f
        Mat4.view(
            view, b,
            b[0] * sx + b[4] * sy + b[8] * sz,
            b[1] * sx + b[5] * sy + b[9] * sz,
            b[2] * sx + b[6] * sy + b[10] * sz,
        )
        Mat4.ortho(projection, SHADOW_HALF, 20f, 420f)
        Mat4.multiply(light, projection, view)

        glBindFramebuffer(GL_FRAMEBUFFER, shadowFramebuffer)
        glViewport(0, 0, shadowSize, shadowSize)
        glEnable(GL_DEPTH_TEST); glDepthMask(true)
        glClear(GL_DEPTH_BUFFER_BIT)
        glDisable(GL_CULL_FACE)
        glEnable(GL_POLYGON_OFFSET_FILL); glPolygonOffset(1.5f, 3f)
        depth.use()
        glUniformMatrix4fv(depthMatrix, 1, false, light, 0)
        glUniformMatrix4fv(depthModel, 1, false, identity, 0)
        extractPlanes(light)
        for (i in blocks.indices) {
            if (!visible(city.chunks[i])) continue
            blocks[i].draw()
            if (hypot(city.chunks[i].x - pose.x, city.chunks[i].z - pose.z) < quality.shadowDetailRange) details[i].draw()
        }
        for (mesh in vehicles) mesh.draw()
        glUniformMatrix4fv(depthModel, 1, false, droneMatrix, 0)
        droneBody.draw()
        glDisable(GL_POLYGON_OFFSET_FILL)
    }

    fun drawEye(basis: FloatArray, x: Float, y: Float, z: Float, tanHalfY: Float, aspect: Float) {
        Mat4.perspective(projection, tanHalfY, aspect, NEAR, FAR)
        Mat4.view(view, basis, x, y, z)
        Mat4.multiply(viewProjection, projection, view)
        extractPlanes(viewProjection)

        glEnable(GL_DEPTH_TEST); glDepthMask(true); glDepthFunc(GL_LEQUAL)
        glEnable(GL_CULL_FACE); glCullFace(GL_BACK); glFrontFace(GL_CCW)
        glDisable(GL_BLEND)

        world.use()
        glUniformMatrix4fv(worldMatrix, 1, false, viewProjection, 0)
        glUniformMatrix4fv(worldLight, 1, false, light, 0)
        glUniform3f(worldEye, x, y, z)
        glUniform1f(worldTime, time)
        glUniform1f(worldSignalTime, signalTime)
        glUniform1f(worldShadowOn, if (showShadows) 1f else 0f)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, shadowTexture)
        glUniform1i(worldShadowMap, 1)
        glUniform1f(worldShadowTexel, 1f / shadowSize)
        glActiveTexture(GL_TEXTURE2)
        glBindTexture(GL_TEXTURE_2D_ARRAY, atlas.albedo)
        glUniform1i(worldAlbedo, 2)
        glActiveTexture(GL_TEXTURE3)
        glBindTexture(GL_TEXTURE_2D_ARRAY, atlas.relief)
        glUniform1i(worldRelief, 3)
        glUniform1f(worldReliefOn, if (quality.relief) 1f else 0f)

        // Nearest first, so hidden fragments are rejected before the expensive shader runs.
        if (showDrone) {
            glUniformMatrix4fv(worldModel, 1, false, droneMatrix, 0)
            droneBody.draw()
            for (matrix in propMatrices) {
                glUniformMatrix4fv(worldModel, 1, false, matrix, 0)
                propeller.draw()
            }
        }
        glUniformMatrix4fv(worldModel, 1, false, identity, 0)
        for (band in 0..2) {
            val from = if (band == 0) 0f else if (band == 1) 110f else 250f
            val to = if (band == 0) 110f else if (band == 1) 250f else Float.MAX_VALUE
            for (i in blocks.indices) {
                val chunk = city.chunks[i]
                val distance = hypot(chunk.x - x, chunk.z - z)
                // Past this the haze has swallowed everything anyway.
                if (distance < from || distance >= to || distance - chunk.reach > 950f || !visible(chunk)) continue
                blocks[i].draw()
                if (distance < quality.detailRange) details[i].draw()
            }
        }
        for (mesh in vehicles) mesh.draw()
        terrain.draw()

        sky.use()
        glUniformMatrix4fv(skyBasis, 1, false, basis, 0)
        glUniform2f(skyTan, tanHalfY * aspect, tanHalfY)
        glUniform1f(skyTime, time)
        glDepthMask(false)
        glBindVertexArray(emptyVao)
        glDrawArrays(GL_TRIANGLES, 0, 3)

        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDisable(GL_CULL_FACE)
        glow.use()
        glUniformMatrix4fv(glowMatrix, 1, false, viewProjection, 0)
        if (showDrone && rotor > 0.1f) {
            glUniform4f(glowColor, 0.12f, 0.14f, 0.16f, min(0.22f, rotor * 0.17f))
            for (matrix in discMatrices) {
                glUniformMatrix4fv(glowModel, 1, false, matrix, 0)
                rotorDisc.draw()
            }
        }
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    private fun extractPlanes(m: FloatArray) {
        for (row in 0..2) for (side in 0..1) {
            val sign = if (side == 0) 1f else -1f
            for (c in 0..3) planes[(row * 2 + side) * 4 + c] = m[c * 4 + 3] + sign * m[c * 4 + row]
        }
    }

    private fun visible(chunk: City.Chunk): Boolean {
        val half = chunk.reach + 2f
        for (p in 0..5) {
            val a = planes[p * 4]; val b = planes[p * 4 + 1]; val c = planes[p * 4 + 2]; val d = planes[p * 4 + 3]
            val reach = a * chunk.x + abs(a) * half + max(0f, b * chunk.top) + c * chunk.z + abs(c) * half + d
            if (reach < 0f) return false
        }
        return true
    }
}
