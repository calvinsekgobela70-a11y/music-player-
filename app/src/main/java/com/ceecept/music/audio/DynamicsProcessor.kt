package com.ceecept.music.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

private typealias AF = AudioProcessor.AudioFormat

/** Parameters for one compressor band. Immutable — swapped atomically from the UI thread. */
data class BandParams(
    val gateOn: Boolean = false,
    /** Gate (downward expander) threshold in dB. */
    val gateDb: Float = -60f,
    val thresholdDb: Float = -18f,
    val ratio: Float = 3f,
    val attackMs: Float = 10f,
    val releaseMs: Float = 120f,
    val makeupDb: Float = 0f
)

data class DynamicsParams(
    val enabled: Boolean = true,
    val xoverLowHz: Float = 250f,
    val xoverHighHz: Float = 4000f,
    val low: BandParams = BandParams(),
    val mid: BandParams = BandParams(),
    val high: BandParams = BandParams(),
    val limiterOn: Boolean = true,
    val limiterCeilingDb: Float = -1f,
    val limiterReleaseMs: Float = 80f,
    val outputDb: Float = 0f
) {
    fun band(i: Int): BandParams = when (i) {
        0 -> low
        1 -> mid
        else -> high
    }

    companion object {
        val DEFAULT = DynamicsParams()
        val PRESETS: Map<String, DynamicsParams> = mapOf(
            "Transparent" to DynamicsParams(
                low = BandParams(thresholdDb = -14f, ratio = 2f, attackMs = 12f, releaseMs = 160f, makeupDb = 1.5f),
                mid = BandParams(thresholdDb = -14f, ratio = 2f, attackMs = 8f, releaseMs = 140f, makeupDb = 1.5f),
                high = BandParams(thresholdDb = -16f, ratio = 2f, attackMs = 5f, releaseMs = 120f, makeupDb = 1f)
            ),
            "Punchy" to DynamicsParams(
                xoverLowHz = 180f, xoverHighHz = 3600f,
                low = BandParams(thresholdDb = -20f, ratio = 4f, attackMs = 6f, releaseMs = 110f, makeupDb = 3f),
                mid = BandParams(thresholdDb = -16f, ratio = 3f, attackMs = 8f, releaseMs = 120f, makeupDb = 2f),
                high = BandParams(thresholdDb = -18f, ratio = 3f, attackMs = 3f, releaseMs = 90f, makeupDb = 2f)
            ),
            "Glue" to DynamicsParams(
                low = BandParams(thresholdDb = -10f, ratio = 1.7f, attackMs = 20f, releaseMs = 240f, makeupDb = 1f),
                mid = BandParams(thresholdDb = -10f, ratio = 1.7f, attackMs = 15f, releaseMs = 220f, makeupDb = 1f),
                high = BandParams(thresholdDb = -12f, ratio = 1.7f, attackMs = 10f, releaseMs = 200f, makeupDb = 1f),
                limiterCeilingDb = -1.5f
            ),
            "Podcast" to DynamicsParams(
                xoverLowHz = 300f, xoverHighHz = 5000f,
                low = BandParams(gateOn = true, gateDb = -55f, thresholdDb = -24f, ratio = 5f, attackMs = 5f, releaseMs = 150f, makeupDb = 5f),
                mid = BandParams(gateOn = true, gateDb = -55f, thresholdDb = -22f, ratio = 4f, attackMs = 4f, releaseMs = 140f, makeupDb = 4f),
                high = BandParams(gateOn = true, gateDb = -58f, thresholdDb = -26f, ratio = 4f, attackMs = 2f, releaseMs = 120f, makeupDb = 3f)
            )
        )
    }
}

/**
 * 3-band upward/downward dynamics: Linkwitz-Riley 4th-order crossover, per-band
 * gate (downward expander) + soft-knee compressor with makeup gain, followed by
 * a lookahead brick-wall limiter. Stereo-linked to preserve the stereo image.
 */
class DynamicsProcessor : BaseAudioProcessor() {

    val params = AtomicReference(DynamicsParams.DEFAULT)

    /** Live meter snapshot for the UI: GR dB for [low, mid, high, limiter] + output peak dB. */
    @Volatile var meters: FloatArray = FloatArray(5)
        private set

    private var sampleRate = 48000
    private var channels = 2

    // Crossover state: per channel: lp1a,lp1b (LR4 low @fc1), hp1a,hp1b, lp2a,lp2b (@fc2), hp2a,hp2b
    private var xStates: Array<Array<BiquadState>> = emptyArray()
    private val lp1a = Biquad(); private val lp1b = Biquad()
    private val hp1a = Biquad(); private val hp1b = Biquad()
    private val lp2a = Biquad(); private val lp2b = Biquad()
    private val hp2a = Biquad(); private val hp2b = Biquad()

    private val bandEnvelopes = Array(3) { Envelope() }
    private val limiterEnvelope = Envelope()
    private var limiterLines: Array<DelayLine> = emptyArray()
    private var limiterGain = 1f

    private var attackCoefs = FloatArray(3)
    private var releaseCoefs = FloatArray(3)
    private var appliedParams: DynamicsParams? = null

    private var scratch: FloatArray = FloatArray(0)
    private var bandScratch: FloatArray = FloatArray(0) // frames*channels*3

    @Volatile private var dirty = true

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
        channels = inputAudioFormat.channelCount
        ensureCapacity()
        dirty = true
        return AF(sampleRate, channels, C.ENCODING_PCM_FLOAT)
    }

    private fun ensureCapacity() {
        xStates = Array(channels) { Array(8) { BiquadState() } }
        val lookahead = (0.005f * sampleRate).toInt().coerceAtLeast(16)
        limiterLines = Array(channels) { DelayLine(lookahead + 8).also { it.delay = lookahead.toFloat() } }
        bandEnvelopes.forEach { it.reset() }
        limiterEnvelope.reset()
        limiterGain = 1f
    }

    private fun rebuild(p: DynamicsParams) {
        val q = 0.7071f
        lp1a.setLowPass(p.xoverLowHz, q, sampleRate)
        lp1b.setLowPass(p.xoverLowHz, q, sampleRate)
        hp1a.setHighPass(p.xoverLowHz, q, sampleRate)
        hp1b.setHighPass(p.xoverLowHz, q, sampleRate)
        lp2a.setLowPass(p.xoverHighHz, q, sampleRate)
        lp2b.setLowPass(p.xoverHighHz, q, sampleRate)
        hp2a.setHighPass(p.xoverHighHz, q, sampleRate)
        hp2b.setHighPass(p.xoverHighHz, q, sampleRate)
        for (i in 0..2) {
            val b = p.band(i)
            attackCoefs[i] = Dsp.envelopeCoef(b.attackMs, sampleRate)
            releaseCoefs[i] = Dsp.envelopeCoef(b.releaseMs, sampleRate)
        }
        appliedParams = p
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inFormat = inputAudioFormat
        if (inFormat === AF.NOT_SET) return
        val p = params.get()
        if (dirty || appliedParams !== p) {
            rebuild(p)
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
        var idx = 0
        when (inFormat.encoding) {
            C.ENCODING_PCM_16BIT -> repeat(frames * channels) { s[idx++] = inputBuffer.short / 32768f }
            C.ENCODING_PCM_24BIT -> repeat(frames * channels) {
                val b0 = inputBuffer.get().toInt() and 0xFF
                val b1 = inputBuffer.get().toInt() and 0xFF
                val b2 = inputBuffer.get().toInt()
                s[idx++] = ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
            }
            C.ENCODING_PCM_32BIT -> repeat(frames * channels) { s[idx++] = inputBuffer.int / 2147483648f }
            else -> repeat(frames * channels) { s[idx++] = inputBuffer.float }
        }

        if (!p.enabled) {
            writeOutput(s, frames, channels)
            return
        }

        if (bandScratch.size < frames * channels * 3) bandScratch = FloatArray(frames * channels * 3)
        val bands = bandScratch

        // 1) Crossover split (per channel).
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val x = s[f * channels + c]
                val st = xStates[c]
                val low = lp1b.process(lp1a.process(x, st[0]), st[1])
                val rest = hp1b.process(hp1a.process(x, st[2]), st[3])
                val mid = lp2b.process(lp2a.process(rest, st[4]), st[5])
                val high = hp2b.process(hp2a.process(rest, st[6]), st[7])
                val o = (f * channels + c) * 3
                bands[o] = low
                bands[o + 1] = mid
                bands[o + 2] = high
            }
        }

        // 2) Per-band linked dynamics.
        val grDb = FloatArray(3)
        val makeupLin = FloatArray(3) { Dsp.dbToLinear(p.band(it).makeupDb) }
        for (f in 0 until frames) {
            for (b in 0..2) {
                // Linked peak across channels.
                var peak = 0f
                for (c in 0 until channels) {
                    val v = kotlin.math.abs(bands[(f * channels + c) * 3 + b])
                    if (v > peak) peak = v
                }
                val bp = p.band(b)
                val env = bandEnvelopes[b].process(peak, attackCoefs[b], releaseCoefs[b])
                val xDb = Dsp.linearToDb(env)
                var gr = compGainDb(xDb, bp.thresholdDb, bp.ratio)
                if (bp.gateOn && xDb < bp.gateDb) {
                    gr += (bp.gateDb - xDb) * -0.5f // 1:2 downward expansion
                    gr = gr.coerceAtLeast(-48f)
                }
                grDb[b] = gr
                val g = Dsp.dbToLinear(gr) * makeupLin[b]
                for (c in 0 until channels) {
                    val o = (f * channels + c) * 3 + b
                    bands[o] = bands[o] * g
                }
            }
        }

        // 3) Sum bands back.
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val o = (f * channels + c) * 3
                s[f * channels + c] = bands[o] + bands[o + 1] + bands[o + 2]
            }
        }

        // 4) Lookahead brick-wall limiter (linked).
        var limiterGr = 0f
        if (p.limiterOn) {
            val ceiling = Dsp.dbToLinear(p.limiterCeilingDb)
            val relCoef = Dsp.envelopeCoef(p.limiterReleaseMs, sampleRate)
            val atkCoef = Dsp.envelopeCoef(0.05f, sampleRate)
            for (f in 0 until frames) {
                var peak = 0f
                for (c in 0 until channels) {
                    val v = kotlin.math.abs(s[f * channels + c])
                    if (v > peak) peak = v
                }
                val env = limiterEnvelope.process(peak, atkCoef, relCoef)
                val target = if (env > ceiling && env > 1e-6f) ceiling / env else 1f
                // Fast attack via lookahead, smooth recovery.
                val coef = if (target < limiterGain) 0.5f else relCoef * 0.25f + 0.001f
                limiterGain += coef * (target - limiterGain)
                limiterGr = Dsp.linearToDb(limiterGain.coerceAtLeast(1e-6f)).coerceAtMost(0f)
                for (c in 0 until channels) {
                    s[f * channels + c] = limiterLines[c].push(s[f * channels + c]) * limiterGain
                }
            }
        }

        // 5) Output gain + safety clip.
        val outG = Dsp.dbToLinear(p.outputDb)
        var peak = 0f
        for (i in 0 until frames * channels) {
            val v = (s[i] * outG).coerceIn(-1f, 1f)
            s[i] = v
            val a = kotlin.math.abs(v)
            if (a > peak) peak = a
        }

        meters = floatArrayOf(grDb[0], grDb[1], grDb[2], limiterGr, Dsp.linearToDb(peak))
        writeOutput(s, frames, channels)
    }

    private fun compGainDb(xDb: Float, threshold: Float, ratio: Float): Float {
        if (xDb < threshold) return 0f
        val r = ratio.coerceAtLeast(1f)
        val knee = 6f
        return if (xDb > threshold + knee / 2) {
            (threshold + (xDb - threshold) / r) - xDb
        } else {
            val d = xDb - threshold + knee / 2
            ((1f / r - 1f) * d * d / (2f * knee))
        }
    }

    private fun writeOutput(s: FloatArray, frames: Int, channels: Int) {
        val out = replaceOutputBuffer(frames * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames * channels) out.putFloat(s[i])
        out.flip()
    }

    override fun onFlush() {
        xStates.forEach { ch -> ch.forEach { it.clear() } }
        bandEnvelopes.forEach { it.reset() }
        limiterEnvelope.reset()
        limiterLines.forEach { it.clear() }
        limiterGain = 1f
    }

    override fun onReset() {
        onFlush()
    }
}
