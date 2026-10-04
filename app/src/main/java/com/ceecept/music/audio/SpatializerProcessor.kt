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
    val orbitHz: Float = 0f,

    // ---- Immerse 3.0 ----
    /** Analyse the mix and render its parts as separate objects. */
    val stems: Boolean = true,
    /** Vocal presence/clarity on the separated lead, 0..1. */
    val vocal: Float = 0.45f,
    /** Psychoacoustic bass extension on the separated bass, 0..1. */
    val bass: Float = 0.45f,
    /** Transient emphasis on the separated percussion, 0..1. */
    val punch: Float = 0.35f,
    /** Rear/height ambience level, 0..1. */
    val ambience: Float = 0.5f,
    /** Speaker rig: 0 = follow the output route, 1 = stereo, 2 = 5.1, 3 = 7.1.4. */
    val rigMode: Int = 0,
    /** Imaging: how easy it is to point at each source, 0..1. */
    val imaging: Float = 0.5f,
    /** Analogue second-harmonic colour on the finished mix, 0..1. */
    val warmth: Float = 0.35f,
    /** Level of the wide pad/synth layer, 0..1. */
    val padLevel: Float = 0.6f
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
        multiband = multiband,
        stems = stems,
        vocal = vocal,
        bass = bass,
        punch = punch,
        ambience = ambience,
        rigMode = rigMode,
        imaging = imaging,
        warmth = warmth,
        padLevel = padLevel
    )

    companion object {
        val DEFAULT = SpaceParams()
        val PRESETS: Map<String, SpaceParams> = mapOf(
            "Off" to SpaceParams(enabled = false),
            "Natural" to SpaceParams(
                strength = 0.65f, width = 0.9f, roomSize = 0.4f, reverb = 0.22f,
                damping = 0.45f, height = 0.7f,
                vocal = 0.35f, bass = 0.3f, punch = 0.25f, ambience = 0.4f,
                imaging = 0.45f, warmth = 0.4f, padLevel = 0.55f
            ),
            "Wide Stage" to SpaceParams(
                strength = 0.8f, width = 1.35f, roomSize = 0.5f, reverb = 0.28f,
                damping = 0.5f, height = 1f,
                vocal = 0.45f, bass = 0.4f, punch = 0.4f, ambience = 0.6f,
                imaging = 0.7f, warmth = 0.35f, padLevel = 0.75f
            ),
            "Concert Hall" to SpaceParams(
                strength = 0.9f, elevation = 18f, distance = 3.2f, width = 1.1f,
                roomSize = 0.85f, reverb = 0.55f, damping = 0.35f, height = 1.1f,
                vocal = 0.5f, bass = 0.35f, punch = 0.2f, ambience = 0.8f,
                imaging = 0.55f, warmth = 0.5f, padLevel = 0.8f
            ),
            "Club" to SpaceParams(
                strength = 0.85f, distance = 1.2f, width = 1.2f, roomSize = 0.65f,
                reverb = 0.4f, damping = 0.6f, height = 0.8f,
                vocal = 0.4f, bass = 0.85f, punch = 0.7f, ambience = 0.5f,
                imaging = 0.6f, warmth = 0.45f, padLevel = 0.6f
            ),
            "Cinema" to SpaceParams(
                strength = 1f, elevation = 8f, distance = 2.6f, width = 1.25f,
                roomSize = 0.75f, reverb = 0.45f, damping = 0.45f, height = 1.2f,
                vocal = 0.7f, bass = 0.55f, punch = 0.45f, ambience = 0.75f, rigMode = 3,
                imaging = 0.8f, warmth = 0.3f, padLevel = 0.7f
            ),
            "Overhead" to SpaceParams(
                strength = 0.95f, elevation = 45f, distance = 2.2f, width = 1.15f,
                roomSize = 0.6f, reverb = 0.35f, damping = 0.4f, height = 1.4f,
                vocal = 0.5f, bass = 0.4f, punch = 0.35f, ambience = 0.95f, rigMode = 3,
                imaging = 0.65f, warmth = 0.35f, padLevel = 0.9f
            ),
            "Intimate" to SpaceParams(
                strength = 0.55f, distance = 0.8f, width = 0.7f, roomSize = 0.3f,
                reverb = 0.15f, damping = 0.55f, height = 0.6f,
                vocal = 0.8f, bass = 0.35f, punch = 0.3f, ambience = 0.25f,
                imaging = 0.4f, warmth = 0.45f, padLevel = 0.4f
            ),
            "Orbit" to SpaceParams(
                strength = 0.9f, elevation = 10f, distance = 2.0f, width = 1.1f,
                roomSize = 0.6f, reverb = 0.35f, damping = 0.45f, height = 1f,
                orbitHz = 0.08f,
                vocal = 0.45f, bass = 0.5f, punch = 0.4f, ambience = 0.7f,
                imaging = 0.75f, warmth = 0.35f, padLevel = 0.7f
            ),
            "Vocal Focus" to SpaceParams(
                strength = 0.7f, elevation = 6f, distance = 1.3f, width = 1.0f,
                roomSize = 0.35f, reverb = 0.18f, damping = 0.5f, height = 0.8f,
                vocal = 1f, bass = 0.35f, punch = 0.3f, ambience = 0.35f,
                imaging = 0.5f, warmth = 0.4f, padLevel = 0.45f
            ),
            "Bass Culture" to SpaceParams(
                strength = 0.85f, distance = 1.4f, width = 1.15f, roomSize = 0.55f,
                reverb = 0.3f, damping = 0.55f, height = 0.9f,
                vocal = 0.4f, bass = 1f, punch = 0.75f, ambience = 0.45f,
                imaging = 0.6f, warmth = 0.5f, padLevel = 0.65f
            ),
            "Airy Pads" to SpaceParams(
                strength = 0.85f, elevation = 16f, distance = 2.2f, width = 1.3f,
                roomSize = 0.7f, reverb = 0.4f, damping = 0.4f, height = 1.2f,
                vocal = 0.45f, bass = 0.4f, punch = 0.3f, ambience = 0.8f,
                imaging = 0.7f, warmth = 0.45f, padLevel = 1f
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
        val startPosition = inputBuffer.position()
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val frames = remaining / bytesPerFrame
        val processBytes = frames * bytesPerFrame
        if (frames == 0 || processBytes <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        if (!p.enabled && inChannels == 2 && inFormat.encoding == C.ENCODING_PCM_FLOAT) {
            copyExactFrames(inputBuffer, startPosition, processBytes)
            return
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)
        val s = scratch

        decodeToStereo(inputBuffer, inFormat, inChannels, frames, s)
        // MediaCodec/AudioSink can occasionally hand a partial frame around flush or
        // decoder-format changes on EMUI. Always consume it so the pipeline cannot
        // re-enter with a dangling non-frame-aligned ByteBuffer and crash in put().
        inputBuffer.position(inputBuffer.limit())
        engine.process(s, frames)
        writeOutput(s, frames)
    }

    private fun copyExactFrames(inputBuffer: ByteBuffer, startPosition: Int, byteCount: Int) {
        val safeBytes = byteCount.coerceAtMost(inputBuffer.limit() - startPosition).coerceAtLeast(0)
        if (safeBytes == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val src = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        src.position(startPosition)
        src.limit(startPosition + safeBytes)
        val out = replaceOutputBuffer(safeBytes).order(ByteOrder.LITTLE_ENDIAN)
        out.put(src)
        out.flip()
        inputBuffer.position(inputBuffer.limit())
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
