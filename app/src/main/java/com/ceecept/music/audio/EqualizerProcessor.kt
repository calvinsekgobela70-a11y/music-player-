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

        /** Legacy fixed Q, kept only so old response drawings still resolve. */
        const val Q = 1.05f

        val FREQUENCIES = floatArrayOf(
            31f, 62f, 125f, 250f, 400f, 630f, 1000f, 1600f,
            2500f, 4000f, 6300f, 8000f, 10000f, 12500f, 14000f, 16000f
        )

        /**
         * Per-band Q, derived from the actual spacing of these centres rather than
         * being one fixed number for every band.
         *
         * A graphic equaliser only behaves the way its front panel implies if each
         * band's skirts meet its neighbours' at the crossover point. With one shared Q
         * the closely spaced top bands (10 k, 12.5 k, 14 k, 16 k) overlap almost
         * completely, so moving one slider drags four bands with it — the effect Rane
         * calls "equalising the equaliser" (Bohn, *Constant-Q Graphic Equalizers*,
         * JAES). Each band's half-gain edges are placed at the geometric midpoints to
         * its neighbours, which gives Q = 1 / (sqrt(r_hi) - 1/sqrt(r_lo)).
         */
        val BAND_Q: FloatArray = FloatArray(BANDS) { i ->
            val lo = if (i == 0) FREQUENCIES[1] / FREQUENCIES[0] else FREQUENCIES[i] / FREQUENCIES[i - 1]
            val hi = if (i == BANDS - 1) FREQUENCIES[i] / FREQUENCIES[i - 1]
            else FREQUENCIES[i + 1] / FREQUENCIES[i]
            val width = Math.sqrt(hi.toDouble()) - 1.0 / Math.sqrt(lo.toDouble())
            (1.0 / width).toFloat().coerceIn(0.5f, 8f)
        }

        /**
         * Musical (proportional-Q) mode: small moves are broad and gentle, big moves
         * get progressively tighter, which is how the classic analogue consoles behave
         * (API 550, Neve 1073) and why they are easier to use by ear.
         */
        fun effectiveQ(band: Int, gainDb: Float, musical: Boolean): Float {
            val base = BAND_Q[band]
            if (!musical) return base
            val g = (Math.abs(gainDb) / MAX_GAIN_DB).coerceIn(0f, 1f)
            return (base * (0.55f + 0.65f * g)).coerceIn(0.4f, 10f)
        }

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
        fun responseDb(
            freq: Float,
            gains: FloatArray,
            sampleRate: Int,
            musical: Boolean = true
        ): Float {
            var total = 0f
            val tmp = Biquad()
            for (i in gains.indices) {
                val g = gains[i]
                if (g == 0f) continue
                configureBand(tmp, i, g, sampleRate, musical)
                total += tmp.magnitudeDb(freq, sampleRate)
            }
            return total
        }

        /**
         * The end bands are shelves, not bells. A 31 Hz bell does nothing for the
         * 20–25 Hz an 808 actually sits on, and a 16 kHz bell rolls off again above
         * itself; shelving the extremes is what every hardware graphic EQ does.
         */
        fun configureBand(
            biquad: Biquad,
            band: Int,
            gainDb: Float,
            sampleRate: Int,
            musical: Boolean
        ) {
            when (band) {
                0 -> biquad.setLowShelf(FREQUENCIES[0] * 1.25f, gainDb, sampleRate, 0.9f)
                BANDS - 1 -> biquad.setHighShelf(FREQUENCIES[BANDS - 1] * 0.8f, gainDb, sampleRate, 0.9f)
                else -> biquad.setPeaking(
                    FREQUENCIES[band], effectiveQ(band, gainDb, musical), gainDb, sampleRate
                )
            }
        }

        /**
         * Peak of the combined curve, so the preamp can be pulled down by exactly
         * enough to stop a boosted EQ clipping. Sampled on a log grid — cheap, and
         * accurate to a small fraction of a dB.
         */
        fun peakResponseDb(gains: FloatArray, sampleRate: Int, musical: Boolean): Float {
            var peak = 0f
            var f = 20f
            while (f < 20000f && f < sampleRate * 0.45f) {
                val r = responseDb(f, gains, sampleRate, musical)
                if (r > peak) peak = r
                f *= 1.06f
            }
            return peak
        }
    }

    @Volatile var enabled: Boolean = true

    @Volatile private var gainsRef: FloatArray = FloatArray(BANDS)

    @Volatile var preampDb: Float = 0f

    /** Proportional-Q ("Musical") when true, constant-Q ("Precision") when false. */
    @Volatile var musicalQ: Boolean = true
        set(value) { field = value; dirty = true }

    /** Pull the preamp down automatically so boosts cannot clip. */
    @Volatile var autoGain: Boolean = true
        set(value) { field = value; dirty = true }

    /** 20 Hz subsonic filter: removes inaudible rumble that only wastes headroom. */
    @Volatile var subsonicFilter: Boolean = true
        set(value) { field = value; dirty = true }

    /** Gain the auto-level stage is currently applying, in dB (<= 0). */
    @Volatile var autoGainDb: Float = 0f
        private set

    @Volatile private var dirty = true

    /** Last seen stream format, for the "signal path" readout in Settings. */
    @Volatile var lastSampleRate: Int = 0
        private set

    @Volatile var lastChannelCount: Int = 0
        private set

    private val biquads = Array(BANDS) { Biquad() }
    private val activeBands = IntArray(BANDS)
    private var activeBandCount = 0
    private val subsonic = Biquad()
    private var subsonicStates: Array<BiquadState> = emptyArray()
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
            subsonicStates = Array(channels) { BiquadState() }
        } else {
            states.forEach { ch -> ch.forEach { it.clear() } }
            subsonicStates.forEach { it.clear() }
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
        val noEqWork = !enabled || (!subsonicFilter && activeBandCount == 0 && kotlin.math.abs(preampDb + autoGainDb) < 0.001f)
        if (noEqWork && inFormat.encoding == C.ENCODING_PCM_FLOAT) {
            val out = replaceOutputBuffer(remaining).order(ByteOrder.LITTLE_ENDIAN)
            out.put(inputBuffer)
            out.flip()
            return
        }
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

        // Process. Active-band dispatch keeps the 16-band framework fully available
        // while avoiding 16 no-op biquads per sample when sliders are flat.
        if (enabled) {
            val pre = Dsp.dbToLinear(preampDb + autoGainDb)
            val st = states
            val useSubsonic = subsonicFilter
            val active = activeBands
            val activeCount = activeBandCount
            for (f in 0 until frames) {
                val base = f * channels
                for (c in 0 until channels) {
                    var x = s[base + c] * pre
                    if (useSubsonic) x = subsonic.process(x, subsonicStates[c])
                    val chState = st[c]
                    for (i in 0 until activeCount) {
                        val b = active[i]
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
        val musical = musicalQ
        activeBandCount = 0
        for (b in 0 until BANDS) {
            configureBand(biquads[b], b, gains[b], sampleRate, musical)
            if (kotlin.math.abs(gains[b]) > 0.001f) activeBands[activeBandCount++] = b
        }
        subsonic.setHighPass(20f, 0.7071f, sampleRate)
        autoGainDb = if (autoGain) -peakResponseDb(gains, sampleRate, musical).coerceAtLeast(0f) else 0f
    }

    override fun onFlush() {
        states.forEach { ch -> ch.forEach { it.clear() } }
    }

    override fun onReset() {
        states.forEach { ch -> ch.forEach { it.clear() } }
    }
}
