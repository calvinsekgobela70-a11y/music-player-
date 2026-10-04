package com.ceecept.music.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * Poweramp-inspired tone/DVC stage ported from the uploaded APK's exposed DSP model.
 *
 * The uploaded app's native `libpowerampcore.so` exposes a Poweramp-style chain with
 * DVC/headroom, bass/tone EQ, compressor/reverb and stereo controls. We cannot safely
 * link that protected native core into a Media3 app, so this is a clean Kotlin/Media3
 * implementation of the same recoverable behaviour: bass + treble shelves, DVC-like
 * pre-headroom, stereo width/crossfeed and a small tempo-synced room send.
 */
data class PowerampToneParams(
    val enabled: Boolean = true,
    val dvcHeadroomDb: Float = -2.2f,
    val bassDb: Float = 1.6f,
    val trebleDb: Float = 0.8f,
    val stereoWidth: Float = 0.18f,
    val crossfeed: Float = 0.04f,
    val reverbMix: Float = 0.035f,
    val warmDrive: Float = 0.10f
) {
    companion object {
        val OFF = PowerampToneParams(enabled = false)
        val DEFAULT = PowerampToneParams()
        val PRESETS = mapOf(
            "Poweramp Balanced" to DEFAULT,
            "Poweramp Bass" to DEFAULT.copy(bassDb = 3.2f, trebleDb = 0.6f, dvcHeadroomDb = -3.4f, warmDrive = 0.13f),
            "Poweramp Air" to DEFAULT.copy(bassDb = 0.9f, trebleDb = 2.4f, stereoWidth = 0.24f, reverbMix = 0.045f),
            "Poweramp Clean" to DEFAULT.copy(bassDb = 0.5f, trebleDb = 0.4f, stereoWidth = 0.08f, crossfeed = 0.02f, reverbMix = 0.0f, warmDrive = 0.04f),
            "Poweramp Wide Room" to DEFAULT.copy(bassDb = 1.2f, trebleDb = 1.2f, stereoWidth = 0.34f, crossfeed = 0.02f, reverbMix = 0.08f)
        )
    }
}

class PowerampDspProcessor : BaseAudioProcessor() {
    val params = AtomicReference(PowerampToneParams.DEFAULT)

    private var sampleRate = 48000
    private var channels = 2
    private var scratch = FloatArray(0)
    private var bass = Biquad()
    private var treble = Biquad()
    private var bassStates: Array<BiquadState> = emptyArray()
    private var trebleStates: Array<BiquadState> = emptyArray()
    private var roomLines: Array<DelayLine> = emptyArray()
    private var lastParams: PowerampToneParams? = null

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AF): AF {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        bassStates = Array(channels) { BiquadState() }
        trebleStates = Array(channels) { BiquadState() }
        roomLines = Array(channels) { c ->
            DelayLine((sampleRate * 0.45f).toInt()).also {
                it.delay = sampleRate * if (c % 2 == 0) 0.031f else 0.043f
            }
        }
        rebuild(force = true)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        if (format === AF.NOT_SET) return
        val frames = inputBuffer.remaining() / format.bytesPerFrame
        if (frames <= 0) return
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val count = frames * channels
        if (scratch.size < count) scratch = FloatArray(count)
        for (i in 0 until count) scratch[i] = inputBuffer.float

        val p = params.get()
        if (p.enabled) {
            rebuild(force = false)
            val headroom = Dsp.dbToLinear(p.dvcHeadroomDb.coerceIn(-9f, 0f))
            val drive = p.warmDrive.coerceIn(0f, 0.35f)
            val wet = p.reverbMix.coerceIn(0f, 0.20f)
            val width = p.stereoWidth.coerceIn(-0.25f, 0.60f)
            val cross = p.crossfeed.coerceIn(0f, 0.22f)
            for (f in 0 until frames) {
                val base = f * channels
                if (channels >= 2) {
                    var l = processTone(scratch[base], 0, headroom, drive, wet)
                    var r = processTone(scratch[base + 1], 1, headroom, drive, wet)
                    val mid = (l + r) * 0.5f
                    val side = (l - r) * (0.5f + width)
                    val wl = mid + side
                    val wr = mid - side
                    l = wl * (1f - cross) + wr * cross
                    r = wr * (1f - cross) + wl * cross
                    scratch[base] = l.coerceIn(-1.12f, 1.12f)
                    scratch[base + 1] = r.coerceIn(-1.12f, 1.12f)
                    for (c in 2 until channels) {
                        scratch[base + c] = processTone(scratch[base + c], c, headroom, drive, wet)
                    }
                } else {
                    scratch[base] = processTone(scratch[base], 0, headroom, drive, wet)
                }
            }
        }

        val out = replaceOutputBuffer(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) out.putFloat(scratch[i])
        out.flip()
    }

    private fun processTone(input: Float, channel: Int, headroom: Float, drive: Float, wet: Float): Float {
        var x = input * headroom
        x = bass.process(x, bassStates[channel])
        x = treble.process(x, trebleStates[channel])
        if (wet > 0f) {
            val delayed = roomLines[channel].peek()
            roomLines[channel].store(x + delayed * 0.23f)
            x += delayed * wet
        } else {
            roomLines[channel].store(x)
        }
        if (drive > 0f) {
            x = Dsp.softClip(x * (1f + drive * 0.7f)) / (1f + drive * 0.22f)
        }
        return x
    }

    private fun rebuild(force: Boolean) {
        val p = params.get()
        if (!force && p == lastParams) return
        lastParams = p
        if (!p.enabled) {
            bass.setIdentity()
            treble.setIdentity()
            return
        }
        bass.setLowShelf(82f, p.bassDb.coerceIn(-9f, 9f), sampleRate, 0.72f)
        treble.setHighShelf(7600f, p.trebleDb.coerceIn(-9f, 9f), sampleRate, 0.78f)
        val baseDelay = (sampleRate * 0.036f).coerceAtLeast(1f)
        for (i in roomLines.indices) {
            val spread = if (i % 2 == 0) 0.82f else 1.17f
            roomLines[i].delay = max(1f, baseDelay * spread)
        }
    }

    override fun onFlush() {
        bassStates.forEach { it.clear() }
        trebleStates.forEach { it.clear() }
        roomLines.forEach { it.clear() }
    }

    override fun onReset() = onFlush()
}
