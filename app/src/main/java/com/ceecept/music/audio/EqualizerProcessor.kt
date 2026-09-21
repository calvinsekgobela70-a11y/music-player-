package com.ceecept.music.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder


/**
 * 16-band parametric equalizer (RBJ peaking biquads, one cascade per channel).
 *
 * Accepts 16/24/32-bit PCM and float input, always outputs 32-bit float.
 * Parameter updates are lock-free: the UI swaps the gains array and the audio
 * thread rebuilds coefficients on the next block (no reconfigure clicks).
 */
class EqualizerProcessor : BaseAudioProcessor() {

    companion object {
        const val BANDS = 16
        const val MAX_GAIN_DB = 12f
        const val Q = 1.05f

        val FREQUENCIES = floatArrayOf(
            31f, 62f, 125f, 250f, 400f, 630f, 1000f, 1600f,
            2500f, 4000f, 6300f, 8000f, 10000f, 12500f, 14000f, 16000f
        )

        val PRESETS: Map<String, FloatArray> = mapOf(
            "Flat" to FloatArray(BANDS),
            "Bass Boost" to floatArrayOf(7f, 6f, 5f, 3.5f, 2f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Deep Sub" to floatArrayOf(9f, 7f, 4f, 1.5f, 0f, 0f, 0f, 0f, 0f, 0f, -1f, -1f, -1f, -1f, -1f, -1f),
            "Treble Boost" to floatArrayOf(-1f, -1f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 2f, 3f, 4f, 5f, 6f, 6.5f, 7f),
            "Vocal" to floatArrayOf(-2f, -1.5f, -1f, 0f, 1f, 2f, 3f, 3.5f, 3f, 2f, 1f, 0f, 0f, -1f, -1f, -1f),
            "Acoustic" to floatArrayOf(2f, 1.5f, 1f, 0f, 0f, 1f, 2f, 2f, 2f, 1.5f, 1f, 1.5f, 2f, 2f, 2f, 2f),
            "Electronic" to floatArrayOf(5f, 4f, 2.5f, 1f, 0f, -1f, 0f, 1f, 2f, 2.5f, 3f, 3.5f, 4f, 4f, 4.5f, 5f),
            "Hip-Hop" to floatArrayOf(6f, 5f, 3f, 1.5f, 0f, -1f, -1f, 0f, 1f, 1.5f, 2f, 3f, 3.5f, 4f, 4f, 4f),
            "Rock" to floatArrayOf(4f, 3f, 2f, 1f, 0f, -1f, -1.5f, -1f, 0f, 1f, 2f, 3f, 3.5f, 4f, 4f, 4.5f),
            "Jazz" to floatArrayOf(3f, 2.5f, 1.5f, 1f, 0f, 0f, 1f, 1.5f, 2f, 2f, 1.5f, 1f, 1.5f, 2f, 2.5f, 3f),
            "Classical" to floatArrayOf(3f, 2f, 1f, 0f, 0f, 0f, -1f, -1f, -1f, 0f, 0f, 1f, 2f, 3f, 4f, 4.5f),
            "Pop" to floatArrayOf(2f, 3f, 3.5f, 2f, 0f, -1f, -1.5f, -1f, 0f, 1f, 2f, 3f, 3f, 2.5f, 2f, 2f),
            "Lo-Fi" to floatArrayOf(3f, 2f, 1f, 0f, 0f, -1f, -1f, -2f, -2f, -3f, -4f, -5f, -6f, -7f, -8f, -9f),
            "Podcast" to floatArrayOf(-6f, -5f, -3f, -1f, 1f, 2.5f, 3.5f, 4f, 3.5f, 2.5f, 1f, 0f, -1f, -2f, -3f, -4f)
        )

        /** Combined magnitude response of [gains] at [freq], for drawing the EQ curve. */
        fun responseDb(freq: Float, gains: FloatArray, sampleRate: Int): Float {
            var total = 0f
            val tmp = Biquad()
            for (i in gains.indices) {
                val g = gains[i]
                if (g != 0f) {
                    tmp.setPeaking(FREQUENCIES[i], Q, g, sampleRate)
                    total += tmp.magnitudeDb(freq, sampleRate)
                }
            }
            return total
        }
    }

    @Volatile var enabled: Boolean = true

    @Volatile private var gainsRef: FloatArray = FloatArray(BANDS)

    @Volatile var preampDb: Float = 0f

    @Volatile private var dirty = true

    /** Last seen stream format, for the "signal path" readout in Settings. */
    @Volatile var lastSampleRate: Int = 0
        private set

    @Volatile var lastChannelCount: Int = 0
        private set

    private val biquads = Array(BANDS) { Biquad() }
    private var states: Array<Array<BiquadState>> = emptyArray()
    private var scratch: FloatArray = FloatArray(0)

    fun setBandGain(index: Int, gainDb: Float) {
        val copy = gainsRef.copyOf()
        copy[index.coerceIn(0, BANDS - 1)] = gainDb.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        gainsRef = copy
        dirty = true
    }

    fun setAll(gains: FloatArray, preamp: Float) {
        gainsRef = gains.copyOf(BANDS)
        preampDb = preamp.coerceIn(-12f, 12f)
        dirty = true
    }

    fun currentGains(): FloatArray = gainsRef.copyOf()

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
        lastSampleRate = inputAudioFormat.sampleRate
        lastChannelCount = inputAudioFormat.channelCount
        ensureCapacity(inputAudioFormat.channelCount)
        dirty = true
        return AF(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT)
    }

    private fun ensureCapacity(channels: Int) {
        if (states.size != channels) {
            states = Array(channels) { Array(BANDS) { BiquadState() } }
        } else {
            states.forEach { ch -> ch.forEach { it.clear() } }
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inFormat = inputAudioFormat
        if (inFormat === AF.NOT_SET) return
        if (dirty) {
            rebuild(inFormat.sampleRate)
            dirty = false
        }
        val channels = inFormat.channelCount
        val bytesPerFrame = inFormat.bytesPerFrame
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val frames = remaining / bytesPerFrame
        if (frames == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        if (scratch.size < frames * channels) scratch = FloatArray(frames * channels)
        val s = scratch

        // Decode to float.
        var idx = 0
        when (inFormat.encoding) {
            C.ENCODING_PCM_16BIT -> repeat(frames * channels) {
                s[idx++] = inputBuffer.short / 32768f
            }
            C.ENCODING_PCM_24BIT -> repeat(frames * channels) {
                val b0 = inputBuffer.get().toInt() and 0xFF
                val b1 = inputBuffer.get().toInt() and 0xFF
                val b2 = inputBuffer.get().toInt()
                s[idx++] = ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
            }
            C.ENCODING_PCM_32BIT -> repeat(frames * channels) {
                s[idx++] = inputBuffer.int / 2147483648f
            }
            else -> repeat(frames * channels) {
                s[idx++] = inputBuffer.float
            }
        }

        // Process.
        if (enabled) {
            val pre = Dsp.dbToLinear(preampDb)
            val st = states
            for (f in 0 until frames) {
                val base = f * channels
                for (c in 0 until channels) {
                    var x = s[base + c] * pre
                    val chState = st[c]
                    for (b in 0 until BANDS) {
                        x = biquads[b].process(x, chState[b])
                    }
                    s[base + c] = x.coerceIn(-1.2f, 1.2f)
                }
            }
        }

        val out = replaceOutputBuffer(frames * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames * channels) out.putFloat(s[i])
        out.flip()
    }

    private fun rebuild(sampleRate: Int) {
        val gains = gainsRef
        for (b in 0 until BANDS) {
            biquads[b].setPeaking(FREQUENCIES[b], Q, gains[b], sampleRate)
        }
    }

    override fun onFlush() {
        states.forEach { ch -> ch.forEach { it.clear() } }
    }

    override fun onReset() {
        states.forEach { ch -> ch.forEach { it.clear() } }
    }
}
