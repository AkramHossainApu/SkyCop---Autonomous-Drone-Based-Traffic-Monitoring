package com.skycop.sim.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Synthesized rotor sound. Four slightly detuned motors give the beating a real quadcopter has. */
internal class RotorAudio {
    private companion object {
        const val RATE = 22_050
    }

    @Volatile private var rotor = 0f
    @Volatile private var enabled = false
    @Volatile private var running = false
    private var thread: Thread? = null

    fun set(rotorSpeed: Float, on: Boolean) {
        rotor = rotorSpeed
        enabled = on
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ play() }, "SkyCopRotor").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun play() {
        val minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return
        val track = try {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                max(minimum, RATE / 5),
                AudioTrack.MODE_STREAM,
                0,
            )
        } catch (error: RuntimeException) {
            Log.w("SkyCopSim", "Rotor sound is unavailable", error)
            return
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return
        }
        // A table keeps this thread cheap: sin() four times per sample adds up.
        val table = FloatArray(2048) {
            val t = it / 2048.0 * 2 * PI
            (sin(t) + 0.5 * sin(2 * t) + 0.22 * sin(3 * t)).toFloat()
        }
        val detune = floatArrayOf(1f, 1.013f, 0.988f, 1.024f)
        val phases = FloatArray(4)
        val samples = ShortArray(512)
        var speed = 0f; var volume = 0f; var noise = 22222; var rush = 0f
        track.play()
        while (running) {
            val targetSpeed = rotor
            val targetVolume = if (enabled) (targetSpeed * 0.8f).coerceIn(0f, 1f) else 0f
            for (i in samples.indices) {
                speed += (targetSpeed - speed) * 0.0007f
                volume += (targetVolume - volume) * 0.0007f
                val step = (45f + 135f * speed) / RATE * 2048f
                var tone = 0f
                for (m in 0..3) {
                    var p = phases[m] + step * detune[m]
                    if (p >= 2048f) p -= 2048f
                    phases[m] = p
                    tone += table[p.toInt() and 2047]
                }
                noise = noise * 1664525 + 1013904223
                rush += ((noise shr 16) / 32768f - rush) * 0.18f
                samples[i] = ((tone * 0.035f + rush * 0.12f) * volume * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            if (track.write(samples, 0, samples.size) < 0) break
        }
        track.pause()
        track.release()
    }
}
