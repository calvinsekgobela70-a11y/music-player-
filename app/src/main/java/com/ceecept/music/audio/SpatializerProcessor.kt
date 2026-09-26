package com.ceecept.music.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.ceecept.music.audio.spatial.PlaybackCapabilities
import com.ceecept.music.audio.spatial.RenderStrategy
import com.ceecept.music.audio.spatial.SpatialAudioEngine
import com.ceecept.music.audio.spatial.SpatialScene
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * "Ceecept Immerse" — object-based 3D audio, rebuilt against the spatial-audio
 * guidelines (`document_pages_1-75.txt`).
 *
 * This class is only the Media3 plumbing: it decodes whatever PCM the decoder hands
 * over, folds it to stereo float, and hands blocks to [SpatialAudioEngine], which owns
 * the actual renderer (objects → speaker layout → two channels).
 *
 * @see SpatialAudioEngine for the pipeline and the section-by-section mapping.
 */
data class SpaceParams(
    val enabled: Boolean = true,
    /** Overall immersion 0..1 (dry/wet). */
    val strength: Float = 0.8f,
    /** Scene rotation in degrees, -180..180. */
    val azimuth: Float = 0f,
    /** Scene elevation in degrees, -40..90. */
    val elevation: Float = 12f,
    /** Virtual listening distance in metres, 0.5..8. */
    val distance: Float = 1.6f,
    /** Scene width 0..1.5 (1 = the ±30° pair). */
    val width: Float = 1f,
    /** Room size 0..1. */
    val roomSize: Float = 0.55f,
    /** Late reverb amount 0..1. */
    val reverb: Float = 0.35f,
    /** HF damping 0..1. */
    val damping: Float = 0.5f,
    /** Height-cue weight 0..1.5 (§5.2 virtual height / height-layer emphasis). */
    val height: Float = 1f,
    /** Per-band spatial widths from §12.1. */
    val multiband: Boolean = true,
    /** Orbit rate in Hz for the §8 moving-object mode; 0 = static scene. */
    val orbitHz: Float = 0f
) {
    fun toScene(): SpatialScene = SpatialScene(
        enabled = enabled,
        strength = strength,
        azimuthDeg = azimuth,
        elevationDeg = elevation,
        distanceM = distance,
        width = width,
        roomSize = roomSize,
        reverb = reverb,
        damping = damping,
        height = height,
        orbitHz = orbitHz,
        multiband = multiband
    )

    companion object {
        val DEFAULT = SpaceParams()
        val PRESETS: Map<String, SpaceParams> = mapOf(
            "Off" to SpaceParams(enabled = false),
            "Natural" to SpaceParams(
                strength = 0.65f, width = 0.9f, roomSize = 0.4f, reverb = 0.22f,
                damping = 0.45f, height = 0.7f
            ),
            "Wide Stage" to SpaceParams(
                strength = 0.8f, width = 1.35f, roomSize = 0.5f, reverb = 0.28f,
                damping = 0.5f, height = 1f
            ),
            "Concert Hall" to SpaceParams(
                strength = 0.9f, elevation = 18f, distance = 3.2f, width = 1.1f,
                roomSize = 0.85f, reverb = 0.55f, damping = 0.35f, height = 1.1f
            ),
            "Club" to SpaceParams(
                strength = 0.85f, distance = 1.2f, width = 1.2f, roomSize = 0.65f,
                reverb = 0.4f, damping = 0.6f, height = 0.8f
            ),
            "Cinema" to SpaceParams(
                strength = 1f, elevation = 8f, distance = 2.6f, width = 1.25f,
                roomSize = 0.75f, reverb = 0.45f, damping = 0.45f, height = 1.2f
            ),
            "Overhead" to SpaceParams(
                strength = 0.95f, elevation = 45f, distance = 2.2f, width = 1.15f,
                roomSize = 0.6f, reverb = 0.35f, damping = 0.4f, height = 1.4f
            ),
            "Intimate" to SpaceParams(
                strength = 0.55f, distance = 0.8f, width = 0.7f, roomSize = 0.3f,
                reverb = 0.15f, damping = 0.55f, height = 0.6f
            ),
            "Orbit" to SpaceParams(
                strength = 0.9f, elevation = 10f, distance = 2.0f, width = 1.1f,
                roomSize = 0.6f, reverb = 0.35f, damping = 0.45f, height = 1f,
                orbitHz = 0.08f
            )
        )
    }
}

class SpatializerProcessor : BaseAudioProcessor() {

    val params = AtomicReference(SpaceParams.DEFAULT)

    /** Updated by [AudioEngine] whenever the Android audio route changes (§11.2). */
    val strategy = AtomicReference(RenderStrategy.select(PlaybackCapabilities()))

    val engine = SpatialAudioEngine()

    private var sampleRate = 48000
    private var appliedParams: SpaceParams? = null
    private var appliedStrategy: RenderStrategy? = null
    private var scratch = FloatArray(0)

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AF): AF {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT &&
            enc != C.ENCODING_PCM_24BIT &&
            enc != C.ENCODING_PCM_32BIT &&
            enc != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount !in 1..8) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        engine.prepare(sampleRate)
        appliedParams = null
        appliedStrategy = null
        // Immerse always renders a stereo image (the phone has two output channels).
        return AF(sampleRate, 2, C.ENCODING_PCM_FLOAT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inFormat = inputAudioFormat
        if (inFormat === AF.NOT_SET) return

        val st = strategy.get()
        if (appliedStrategy !== st) {
            engine.setStrategy(st)
            appliedStrategy = st
        }
        val p = params.get()
        if (appliedParams !== p) {
            engine.setScene(p.toScene())
            appliedParams = p
        }

        val inChannels = inFormat.channelCount
        val bytesPerFrame = inFormat.bytesPerFrame
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val frames = remaining / bytesPerFrame
        if (frames == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)
        val s = scratch

        decodeToStereo(inputBuffer, inFormat, inChannels, frames, s)
        engine.process(s, frames)
        writeOutput(s, frames)
    }

    /** Decode any supported PCM encoding and fold multichannel content down to stereo. */
    private fun decodeToStereo(
        input: ByteBuffer,
        format: AF,
        channels: Int,
        frames: Int,
        out: FloatArray
    ) {
        for (f in 0 until frames) {
            var l = 0f
            var r = 0f
            for (c in 0 until channels) {
                val v = when (format.encoding) {
                    C.ENCODING_PCM_16BIT -> input.short / 32768f
                    C.ENCODING_PCM_24BIT -> {
                        val b0 = input.get().toInt() and 0xFF
                        val b1 = input.get().toInt() and 0xFF
                        val b2 = input.get().toInt()
                        ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                    }
                    C.ENCODING_PCM_32BIT -> input.int / 2147483648f
                    else -> input.float
                }
                when (c) {
                    0 -> l += v
                    1 -> r += v
                    else -> {
                        l += v * 0.4f
                        r += v * 0.4f
                    }
                }
            }
            if (channels > 2) {
                l *= 0.8f
                r *= 0.8f
            }
            if (channels == 1) r = l
            out[f * 2] = l
            out[f * 2 + 1] = r
        }
    }

    private fun writeOutput(s: FloatArray, frames: Int) {
        val out = replaceOutputBuffer(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames * 2) out.putFloat(s[i].coerceIn(-1f, 1f))
        out.flip()
    }

    override fun onFlush() {
        engine.reset()
    }

    override fun onReset() {
        engine.reset()
    }
}
